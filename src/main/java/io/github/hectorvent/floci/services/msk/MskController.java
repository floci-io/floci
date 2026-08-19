package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MskController {

    private final MskService mskService;

    @Inject
    public MskController(MskService mskService) {
        this.mskService = mskService;
    }

    @POST
    @Path("/v1/clusters")
    public Response createCluster(Map<String, Object> request) {
        String clusterName = (String) request.get("clusterName");
        String kafkaVersion = (String) request.get("kafkaVersion");
        MskCluster cluster = mskService.createCluster(clusterName, kafkaVersion);
        return Response.ok(Map.of("clusterArn", cluster.getClusterArn(), "clusterName", cluster.getClusterName(), "state", cluster.getState())).build();
    }

    @POST
    @Path("/api/v2/clusters")
    @SuppressWarnings("unchecked")
    public Response createClusterV2(Map<String, Object> request) {
        // Simple mapping to V1 for now
        String clusterName = (String) request.get("clusterName");
        Map<String, Object> provisioned = (Map<String, Object>) request.get("provisioned");
        String kafkaVersion = provisioned != null ? (String) provisioned.get("kafkaVersion") : null;
        MskCluster cluster = mskService.createCluster(clusterName, kafkaVersion);
        return Response.ok(Map.of("clusterArn", cluster.getClusterArn(), "clusterName", cluster.getClusterName(), "state", cluster.getState())).build();
    }

    @GET
    @Path("/v1/clusters")
    public Response listClusters() {
        var clusters = mskService.listClusters();
        return Response.ok(Map.of("clusterInfoList", clusters)).build();
    }

    @GET
    @Path("/api/v2/clusters")
    public Response listClustersV2() {
        var clusters = mskService.listClusters();
        return Response.ok(Map.of("clusterInfoList", clusters)).build();
    }

    @GET
    @Path("/v1/clusters/{clusterArn}")
    public Response describeCluster(@PathParam("clusterArn") String clusterArn) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        return Response.ok(Map.of("clusterInfo", cluster)).build();
    }

    @GET
    @Path("/api/v2/clusters/{clusterArn}")
    public Response describeClusterV2(@PathParam("clusterArn") String clusterArn) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        return Response.ok(Map.of("clusterInfo", cluster)).build();
    }

    @DELETE
    @Path("/v1/clusters/{clusterArn}")
    public Response deleteCluster(@PathParam("clusterArn") String clusterArn) {
        mskService.deleteCluster(clusterArn);
        return Response.ok(Map.of("clusterArn", clusterArn, "state", "DELETING")).build();
    }

    @GET
    @Path("/v1/clusters/{clusterArn}/bootstrap-brokers")
    public Response getBootstrapBrokers(@PathParam("clusterArn") String clusterArn) {
        String bootstrapBrokers = mskService.getBootstrapBrokers(clusterArn);
        return Response.ok(Map.of("bootstrapBrokerString", bootstrapBrokers)).build();
    }

    // ── Configurations ───────────────────────────────────────────────────────

    @POST
    @Path("/v1/configurations")
    public Response createConfiguration(Map<String, Object> request) {
        String name = asString(request.get("name"), "name");
        String description = asString(request.get("description"), "description");
        List<String> kafkaVersions = asStringList(request.get("kafkaVersions"), "kafkaVersions");
        String serverProperties = decodeServerProperties(asString(request.get("serverProperties"), "serverProperties"));

        MskConfiguration configuration = mskService.createConfiguration(name, description, kafkaVersions, serverProperties);
        return Response.ok(Map.of(
                "arn", configuration.getArn(),
                "name", configuration.getName(),
                "state", configuration.getState(),
                "creationTime", configuration.getCreationTime(),
                "latestRevision", configuration.getLatestRevision())).build();
    }

    @GET
    @Path("/v1/configurations")
    public Response listConfigurations(@QueryParam("maxResults") String maxResultsParam,
                                        @QueryParam("nextToken") String nextToken) {
        PaginatedResult<MskConfiguration> result = mskService.listConfigurations(
                Pagination.parseMaxResults(maxResultsParam, "BadRequestException"), nextToken);
        var configurations = result.items().stream()
                .map(this::toConfigurationView)
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("configurations", configurations);
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/configurations/{arn}")
    public Response describeConfiguration(@PathParam("arn") String arn) {
        MskConfiguration configuration = mskService.describeConfiguration(arn);
        return Response.ok(toConfigurationView(configuration)).build();
    }

    @DELETE
    @Path("/v1/configurations/{arn}")
    public Response deleteConfiguration(@PathParam("arn") String arn) {
        mskService.deleteConfiguration(arn);
        return Response.ok(Map.of("arn", arn, "state", "DELETING")).build();
    }

    // AWS's Configuration/DescribeConfigurationResponse shape never includes
    // serverProperties (that's only returned via DescribeConfigurationRevision), so build
    // an explicit view instead of serializing the model directly.
    private Map<String, Object> toConfigurationView(MskConfiguration configuration) {
        Map<String, Object> view = new HashMap<>();
        view.put("arn", configuration.getArn());
        view.put("name", configuration.getName());
        view.put("description", configuration.getDescription() != null ? configuration.getDescription() : "");
        view.put("kafkaVersions", configuration.getKafkaVersions() != null ? configuration.getKafkaVersions() : List.of());
        view.put("state", configuration.getState());
        view.put("creationTime", configuration.getCreationTime());
        view.put("latestRevision", configuration.getLatestRevision());
        return view;
    }

    private String decodeServerProperties(String serverPropertiesB64) {
        if (serverPropertiesB64 == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(serverPropertiesB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "serverProperties must be base64-encoded.", 400);
        }
    }

    private String asString(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new AwsException("BadRequestException", fieldName + " must be a string.", 400);
        }
        return s;
    }

    private List<String> asStringList(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new AwsException("BadRequestException", fieldName + " must be an array of strings.", 400);
        }
        return list.stream().map(String.class::cast).toList();
    }
}
