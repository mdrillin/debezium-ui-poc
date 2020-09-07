package io.debezium.configserver.rest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import io.debezium.configserver.model.ConnectorConfig;
import io.debezium.configserver.model.KafkaConnectClusterList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;
import java.util.stream.Collectors;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import io.debezium.DebeziumException;
import io.debezium.configserver.model.ConnectionValidationResult;
import io.debezium.configserver.model.ConnectorType;
import io.debezium.configserver.model.FilterValidationResult;
import io.debezium.configserver.model.PropertiesValidationResult;
import io.debezium.configserver.rest.client.KafkaConnectClient;
import io.debezium.configserver.rest.model.BadRequestResponse;
import io.debezium.configserver.rest.model.ServerError;
import io.debezium.configserver.service.ConnectorIntegrator;

@Path(ConnectorResource.API_PREFIX)
public class ConnectorResource {

    public static final String PROPERTY_KAFKA_CONNECT_URI = "kafka.connect.uri";
    public static final String API_PREFIX = "/api";
    public static final String CONNECT_CLUSTERS_ENDPOINT = "/connect-clusters";
    public static final String CONNECTOR_TYPES_ENDPOINT = "/connector-types";
    public static final String CONNECTOR_TYPES_ENDPOINT_FOR_CONNECTOR = "/connector-types/{id}";
    public static final String CONNECTION_VALIDATION_ENDPOINT = "/connector-types/{id}/validation/connection";
    public static final String FILTERS_VALIDATION_ENDPOINT = "/connector-types/{id}/validation/filters";
    public static final String PROPERTIES_VALIDATION_ENDPOINT = "/connector-types/{id}/validation/properties";
    public static final String CREATE_CONNECTOR_ENDPOINT = "/connector/{cluster}/{connector-type-id}";

    private static final Logger LOGGER = Logger.getLogger(ConnectorResource.class);

    /**
     * A comma-separated list of Kafka Connect base URIs
     */
    @ConfigProperty(name = PROPERTY_KAFKA_CONNECT_URI, defaultValue = "http://localhost:8083")
    List<String> kafkaConnectBaseUris;

    private final Map<String, ConnectorIntegrator> integrators;

    public ConnectorResource() {
        Map<String, ConnectorIntegrator> integrators = new HashMap<>();

        ServiceLoader.load(ConnectorIntegrator.class)
                .forEach(integrator -> integrators.put(integrator.getDescriptor().id, integrator));

        this.integrators = Collections.unmodifiableMap(integrators);
    }

    @Path(CONNECT_CLUSTERS_ENDPOINT)
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = KafkaConnectClusterList.class)
            ))
    @APIResponse(
            responseCode = "500",
            description = "Exception during Kafka Connect URI validation",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ServerError.class)
            ))
    public Response getClusters() {
        try {
            return Response.ok(getAllKafkaConnectClusters()).build();
        } catch (RuntimeException | URISyntaxException e) {
            String errorMessage = "Error with Kafka Connect cluster URI: " + e.getLocalizedMessage();
            LOGGER.error(errorMessage);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(new ServerError(errorMessage, traceAsString(e))).build();
        }

    }

    private KafkaConnectClusterList getAllKafkaConnectClusters() throws URISyntaxException {
        KafkaConnectClusterList kafkaConnectBaseURIsList = new KafkaConnectClusterList(kafkaConnectBaseUris.size());
        for (String s : kafkaConnectBaseUris) {
            kafkaConnectBaseURIsList.add(new URI(s.trim()));
        }
        return kafkaConnectBaseURIsList;
    }

    @Path(CONNECTOR_TYPES_ENDPOINT)
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ConnectorType> getConnectorTypes() {
        return integrators.values()
                .stream()
                .map(ConnectorIntegrator::getDescriptor)
                .collect(Collectors.toList());
    }

    @Path(CONNECTOR_TYPES_ENDPOINT_FOR_CONNECTOR)
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ConnectorType.class)
            ))
    @APIResponse(
            responseCode = "400",
            description = "Invalid connector type provided",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BadRequestResponse.class)
            ))
    public Response getConnectorTypes(@PathParam("id") String connectorTypeId) {
        if (null == connectorTypeId || "".equals(connectorTypeId)) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("You have to specify a connector type!"))
                    .build();
        }

        ConnectorIntegrator integrator = integrators.get(connectorTypeId);

        if (integrator == null) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Unknown connector type: " + connectorTypeId))
                    .build();
        }

        return Response.ok(integrator.getDescriptor()).build();
    }

    @Path(CONNECTION_VALIDATION_ENDPOINT)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ConnectionValidationResult.class)
            ))
    @APIResponse(
            responseCode = "400",
            description = "Invalid connector type provided",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BadRequestResponse.class)
            ))
    public Response validateConnectionProperties(@PathParam("id") String connectorTypeId, Map<String, String> properties) {
        ConnectorIntegrator integrator = integrators.get(connectorTypeId);

        if (integrator == null) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Unknown connector type: " + connectorTypeId))
                    .build();
        }

        ConnectionValidationResult validationResult = integrator.validateConnection(properties);

        return Response.ok(validationResult)
                .build();
    }

    @Path(FILTERS_VALIDATION_ENDPOINT)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = FilterValidationResult.class)
            ))
    @APIResponse(
            responseCode = "400",
            description = "Invalid connector type provided",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BadRequestResponse.class)
            ))
    @APIResponse(
            responseCode = "500",
            description = "Exception during validation",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ServerError.class)
            ))
    public Response validateFilters(@PathParam("id") String connectorTypeId, Map<String, String> properties) {
        ConnectorIntegrator integrator = integrators.get(connectorTypeId);

        if (integrator == null) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Unknown connector type: " + connectorTypeId))
                    .build();
        }

        try {
            FilterValidationResult validationResult = integrator.validateFilters(properties);

            return Response.ok(validationResult)
                    .build();
        }
        catch(DebeziumException e) {
            e.printStackTrace();
            return Response.serverError()
                    .entity(new ServerError("Failed to apply table filters", traceAsString(e)))
                    .build();
        }
    }

    @Path(PROPERTIES_VALIDATION_ENDPOINT)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = PropertiesValidationResult.class)
            ))
    @APIResponse(
            responseCode = "400",
            description = "Invalid connector type provided",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BadRequestResponse.class)
            ))
    public Response validateConnectorProperties(@PathParam("id") String connectorTypeId, Map<String, String> properties) {
        ConnectorIntegrator integrator = integrators.get(connectorTypeId);

        if (integrator == null) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Unknown connector type: " + connectorTypeId))
                    .build();
        }

        PropertiesValidationResult validationResult = integrator.validateProperties(properties);

        return Response.ok(validationResult)
                .build();
    }

    private String traceAsString(Exception e) {
        return e.getStackTrace() != null && e.getStackTrace().length > 0 ? Arrays.toString(e.getStackTrace()) : null;
    }

    /**
     * @param cluster the number of the cluster in the list of configured cluster URIs in #PROPERTY_KAFKA_CONNECT_URI (1, 2, 3, ...)
     *
     * @return the URI for the selected cluster
     */
    private URI getKafkaConnectURIforCluster(int cluster) throws RuntimeException, URISyntaxException {
        KafkaConnectClusterList baseURIsList = getAllKafkaConnectClusters();

        if (baseURIsList.size() < cluster) {
            throw new RuntimeException("Selected cluster is not available in the list of configured clusters.");
        }

        return baseURIsList.get(cluster - 1);
    }

    @Path(CREATE_CONNECTOR_ENDPOINT)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = FilterValidationResult.class)
            ))
    @APIResponse(
            responseCode = "400",
            description = "Missing or invalid properties or invalid connector type provided",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = BadRequestResponse.class)
            ))
    @APIResponse(
            responseCode = "500",
            description = "Exception during Kafka Connect URI validation",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ServerError.class)
            ))
    @APIResponse(
            responseCode = "503",
            description = "Exception while trying to connect to the selected Kafka Conenct cluster",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ServerError.class)
            ))
    public Response createConnector(
            @PathParam("cluster") int cluster,
            @PathParam("connector-type-id") String connectorTypeId,
            ConnectorConfig kafkaConnectConfig
            ) {
        if (kafkaConnectConfig.config == null || kafkaConnectConfig.config.isEmpty()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Connector \"config\" property is not set!"))
                    .build();
        }

        if (null == kafkaConnectConfig.name || kafkaConnectConfig.name.isBlank()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Connector \"name\" property is not set!"))
                    .build();
        }

        ConnectorIntegrator integrator = integrators.get(connectorTypeId);
        if (integrator == null) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(new BadRequestResponse("Unknown connector type: " + connectorTypeId))
                    .build();
        }

        PropertiesValidationResult validationResult = integrator.validateProperties(kafkaConnectConfig.config);

        if (validationResult.status == PropertiesValidationResult.Status.INVALID) {
            return Response.status(Status.BAD_REQUEST).entity(validationResult).build();
        }

        URI kafkaConnectURI;
        KafkaConnectClient kafkaConnectClient;
        try {
            kafkaConnectURI = getKafkaConnectURIforCluster(cluster);
            kafkaConnectClient = RestClientBuilder.newBuilder()
                    .baseUri(kafkaConnectURI)
                    .build(KafkaConnectClient.class);
        } catch (RuntimeException | URISyntaxException e) {
            String errorMessage = "Error on choosing the Kafka Connect cluster URI: " + e.getLocalizedMessage();
            LOGGER.error(errorMessage);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(new ServerError(errorMessage, traceAsString(e))).build();
        }

        kafkaConnectConfig.config.put("connector.class", integrator.getDescriptor().className);

        String result;
        LOGGER.debug("Sending valid connector config: " + kafkaConnectConfig);
        try {
            result = kafkaConnectClient.createConnector(kafkaConnectConfig);
        } catch (ProcessingException | IOException e) {
            String errorMessage = "Could not connect to Kafka Connect! Kafka Connect REST API is not available at \""
                    + kafkaConnectURI + "\". (" + e.getLocalizedMessage() + ")";
            LOGGER.error(errorMessage);
            return Response.status(Status.SERVICE_UNAVAILABLE).entity(new ServerError(errorMessage, traceAsString(e))).build();
        }
        LOGGER.debug("Kafka Connect response: " + result);

        return Response.ok(result).build();
    }
}
