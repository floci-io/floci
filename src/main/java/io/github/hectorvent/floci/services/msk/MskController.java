package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevisionDetail;
import io.github.hectorvent.floci.services.msk.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.msk.model.CreateClusterV2Request;
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

    // Fallback for clusters persisted before clusterType was stored - everything written back
    // then was provisioned, since serverless had no representation at all.
    private static final String PROVISIONED_CLUSTER_TYPE = "PROVISIONED";

    private final MskService mskService;

    @Inject
    public MskController(MskService mskService) {
        this.mskService = mskService;
    }

    @POST
    @Path("/v1/clusters")
    public Response createCluster(CreateClusterRequest request) {
        MskCluster cluster = mskService.createCluster(request);
        return Response.ok(Map.of("clusterArn", cluster.getClusterArn(), "clusterName", cluster.getClusterName(), "state", cluster.getState())).build();
    }

    @POST
    @Path("/api/v2/clusters")
    public Response createClusterV2(CreateClusterV2Request request) {
        MskCluster cluster = mskService.createCluster(request);
        return Response.ok(Map.of(
                "clusterArn", cluster.getClusterArn(),
                "clusterName", cluster.getClusterName(),
                "state", cluster.getState(),
                "clusterType", clusterType(cluster))).build();
    }

    @GET
    @Path("/v1/clusters")
    public Response listClusters() {
        var clusters = mskService.listProvisionedClusters().stream().map(this::toClusterViewV1).toList();
        return Response.ok(Map.of("clusterInfoList", clusters)).build();
    }

    @GET
    @Path("/api/v2/clusters")
    public Response listClustersV2() {
        var clusters = mskService.listClusters().stream().map(this::toClusterViewV2).toList();
        return Response.ok(Map.of("clusterInfoList", clusters)).build();
    }

    @GET
    @Path("/v1/clusters/{clusterArn}")
    public Response describeCluster(@PathParam("clusterArn") String clusterArn) {
        MskCluster cluster = mskService.describeClusterV1(clusterArn);
        return Response.ok(Map.of("clusterInfo", toClusterViewV1(cluster))).build();
    }

    @GET
    @Path("/api/v2/clusters/{clusterArn}")
    public Response describeClusterV2(@PathParam("clusterArn") String clusterArn) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        return Response.ok(Map.of("clusterInfo", toClusterViewV2(cluster))).build();
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

    @PUT
    @Path("/v1/configurations/{arn}")
    public Response updateConfiguration(@PathParam("arn") String arn, Map<String, Object> request) {
        String description = asString(request.get("description"), "description");
        String serverProperties = decodeServerProperties(asString(request.get("serverProperties"), "serverProperties"));

        MskConfiguration configuration = mskService.updateConfiguration(arn, description, serverProperties);
        return Response.ok(Map.of(
                "arn", configuration.getArn(),
                "latestRevision", configuration.getLatestRevision())).build();
    }

    @GET
    @Path("/v1/configurations/{arn}/revisions")
    public Response listConfigurationRevisions(@PathParam("arn") String arn,
                                                @QueryParam("maxResults") String maxResultsParam,
                                                @QueryParam("nextToken") String nextToken) {
        PaginatedResult<ConfigurationRevision> result = mskService.listConfigurationRevisions(arn,
                Pagination.parseMaxResults(maxResultsParam, "BadRequestException"), nextToken);

        Map<String, Object> response = new HashMap<>();
        response.put("revisions", result.items());
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/v1/configurations/{arn}/revisions/{revision}")
    public Response describeConfigurationRevision(@PathParam("arn") String arn,
                                                    @PathParam("revision") String revisionParam) {
        long revision = parseRevision(revisionParam);
        ConfigurationRevisionDetail detail = mskService.describeConfigurationRevision(arn, revision);

        Map<String, Object> response = new HashMap<>();
        response.put("arn", detail.getArn());
        response.put("creationTime", detail.getCreationTime());
        response.put("description", detail.getDescription() != null ? detail.getDescription() : "");
        response.put("revision", detail.getRevision());
        response.put("serverProperties",
                Base64.getEncoder().encodeToString(detail.getServerProperties().getBytes(StandardCharsets.UTF_8)));
        return Response.ok(response).build();
    }

    // The v1 ClusterInfo is flat: broker node group, encryption, client auth, monitoring and
    // logging all sit directly on the cluster object.
    //
    // Built explicitly rather than by serializing MskCluster, because that model is also the
    // persisted shape and carries internal bookkeeping (bootstrapBrokers, containerId,
    // accountId, volumeId) that must stay in the store but never reach a client.
    private Map<String, Object> toClusterViewV1(MskCluster cluster) {
        Map<String, Object> view = commonClusterFields(cluster);
        view.putAll(provisionedFields(cluster));
        return view;
    }

    // The v2 ClusterInfo is NOT the v1 shape with extra members: everything provisioned-specific
    // nests under "provisioned", and a "clusterType" discriminator selects between that and the
    // serverless shape. An AWS SDK v2 client - which is what terraform-provider-aws calls -
    // looks for these fields only there, so returning them flat leaves them invisible to it.
    private Map<String, Object> toClusterViewV2(MskCluster cluster) {
        Map<String, Object> view = commonClusterFields(cluster);
        view.put("clusterType", clusterType(cluster));
        if (mskService.isServerless(cluster)) {
            putIfPresent(view, "serverless", cluster.getServerless());
        } else {
            view.put("provisioned", provisionedFields(cluster));
        }
        return view;
    }

    private String clusterType(MskCluster cluster) {
        return cluster.getClusterType() != null ? cluster.getClusterType() : PROVISIONED_CLUSTER_TYPE;
    }

    // Members that stay top-level in both versions.
    private Map<String, Object> commonClusterFields(MskCluster cluster) {
        Map<String, Object> view = new HashMap<>();
        view.put("clusterArn", cluster.getClusterArn());
        view.put("clusterName", cluster.getClusterName());
        view.put("state", cluster.getState());
        view.put("creationTime", cluster.getCreationTime());
        view.put("currentVersion", cluster.getCurrentVersion());
        view.put("tags", cluster.getTags() != null ? cluster.getTags() : Map.of());
        return view;
    }

    // Members that are flat on v1's ClusterInfo and nested under v2's "provisioned".
    private Map<String, Object> provisionedFields(MskCluster cluster) {
        Map<String, Object> view = new HashMap<>();
        view.put("numberOfBrokerNodes", cluster.getNumberOfBrokerNodes());
        view.put("zookeeperConnectString", cluster.getZookeeperConnectString());
        view.put("currentBrokerSoftwareInfo", cluster.getCurrentBrokerSoftwareInfo());
        // Absent optional members are omitted rather than sent as null, matching AWS.
        putIfPresent(view, "brokerNodeGroupInfo", cluster.getBrokerNodeGroupInfo());
        putIfPresent(view, "encryptionInfo", cluster.getEncryptionInfo());
        putIfPresent(view, "clientAuthentication", cluster.getClientAuthentication());
        putIfPresent(view, "enhancedMonitoring", cluster.getEnhancedMonitoring());
        putIfPresent(view, "loggingInfo", cluster.getLoggingInfo());
        putIfPresent(view, "openMonitoring", cluster.getOpenMonitoring());
        putIfPresent(view, "storageMode", cluster.getStorageMode());
        putIfPresent(view, "rebalancing", cluster.getRebalancing());
        return view;
    }

    private void putIfPresent(Map<String, Object> view, String key, Object value) {
        if (value != null) {
            view.put(key, value);
        }
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

    // Bound as String rather than @PathParam long, matching the same reasoning as
    // maxResults: a failed path-param conversion should return an AWS-shaped 400, not
    // whatever the framework's own default handling produces.
    private long parseRevision(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new AwsException("BadRequestException", "revision must be an integer.", 400);
        }
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
