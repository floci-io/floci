package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for all ElastiCache actions (form-encoded POST, XML response).
 * Covers both the management plane (replication groups, users) and the auth-token
 * validation endpoint used by the Redis IAM auth flow.
 */
@ApplicationScoped
public class ElastiCacheQueryHandler {

    private static final Logger LOG = Logger.getLogger(ElastiCacheQueryHandler.class);

    private final SigV4Validator sigV4Validator;
    private final ElastiCacheService service;
    private final ElastiCacheMemcachedService memcachedService;
    private final RegionResolver regionResolver;

    @Inject
    public ElastiCacheQueryHandler(SigV4Validator sigV4Validator, ElastiCacheService service,
                                   ElastiCacheMemcachedService memcachedService,
                                   RegionResolver regionResolver) {
        this.sigV4Validator = sigV4Validator;
        this.service = service;
        this.memcachedService = memcachedService;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("ElastiCache action: {0}", action);
        return switch (action) {
            case "ValidateIamAuthToken"       -> handleValidateIamAuthToken(params);
            case "CreateReplicationGroup"     -> handleCreateReplicationGroup(params, region);
            case "DescribeReplicationGroups"  -> handleDescribeReplicationGroups(params);
            case "ModifyReplicationGroup"     -> handleModifyReplicationGroup(params);
            case "DeleteReplicationGroup"     -> handleDeleteReplicationGroup(params);
            case "CreateUser"                 -> handleCreateUser(params);
            case "DescribeUsers"              -> handleDescribeUsers(params);
            case "ModifyUser"                 -> handleModifyUser(params);
            case "DeleteUser"                 -> handleDeleteUser(params);
            case "CreateCacheCluster"         -> handleCreateCacheCluster(params);
            case "DescribeCacheClusters"      -> handleDescribeCacheClusters(params);
            case "DeleteCacheCluster"         -> handleDeleteCacheCluster(params);
            case "CreateCacheSubnetGroup"     -> handleCreateCacheSubnetGroup(params);
            case "DescribeCacheSubnetGroups"  -> handleDescribeCacheSubnetGroups(params);
            case "ModifyCacheSubnetGroup"     -> handleModifyCacheSubnetGroup(params);
            case "DeleteCacheSubnetGroup"     -> handleDeleteCacheSubnetGroup(params);
            case "CreateCacheParameterGroup" -> handleCreateCacheParameterGroup(params);
            case "DescribeCacheParameterGroups" -> handleDescribeCacheParameterGroups(params);
            case "ModifyCacheParameterGroup" -> handleModifyCacheParameterGroup(params);
            case "DescribeCacheParameters" -> handleDescribeCacheParameters(params);
            case "DeleteCacheParameterGroup" -> handleDeleteCacheParameterGroup(params);
            case "ListTagsForResource" -> handleListTagsForResource(params);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported.", AwsNamespaces.EC, 400);
        };
    }

    // ── Replication Groups ────────────────────────────────────────────────────

    private Response handleCreateReplicationGroup(MultivaluedMap<String, String> params, String region) {
        String groupId = params.getFirst("ReplicationGroupId");
        String description = params.getFirst("ReplicationGroupDescription");
        String authToken = params.getFirst("AuthToken");

        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }

        String transitEncryption = params.getFirst("TransitEncryptionEnabled");
        AuthMode authMode;
        if (authToken != null && !authToken.isBlank()) {
            authMode = AuthMode.PASSWORD;
        } else if ("true".equalsIgnoreCase(transitEncryption)) {
            authMode = AuthMode.IAM;
        } else {
            authMode = AuthMode.NO_AUTH;
        }

        try {
            ReplicationGroup group = service.createReplicationGroup(
                    new ElastiCacheService.CreateReplicationGroupRequest(
                            groupId,
                            description != null ? description : "",
                            authMode,
                            authToken,
                            region,
                            params.getFirst("Engine"),
                            params.getFirst("EngineVersion"),
                            params.getFirst("CacheNodeType"),
                            params.getFirst("CacheParameterGroupName"),
                            params.getFirst("CacheSubnetGroupName"),
                            params.getFirst("ClusterMode"),
                            intParam(params, "NumNodeGroups"),
                            intParam(params, "ReplicasPerNodeGroup"),
                            intParam(params, "NumCacheClusters"),
                            boolParam(params, "AutomaticFailoverEnabled"),
                            boolParam(params, "MultiAZEnabled"),
                            replicationGroupSettings(params),
                            parseTags(params)));
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private static ReplicationGroupSettings replicationGroupSettings(MultivaluedMap<String, String> params) {
        String atRest = params.getFirst("AtRestEncryptionEnabled");
        // A live account reads any value that is not "false" as true — "banana", "yes" and
        // "TRUE " all created an encrypted group — so the flag is read the same way here.
        return new ReplicationGroupSettings(
                atRest == null ? null : !"false".equalsIgnoreCase(atRest.trim()),
                params.getFirst("KmsKeyId"),
                optionalInt(params.getFirst("SnapshotRetentionLimit")),
                params.getFirst("SnapshotWindow"));
    }

    private static Integer optionalInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", "Value " + value + " is not a valid integer.", 400);
        }
    }

    private static Integer intParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter " + name + " must be an integer.", 400);
        }
    }

    private static Boolean boolParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value.trim());
    }


    private Response handleDescribeReplicationGroups(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("ReplicationGroupId");
        try {
            Collection<ReplicationGroup> groups = service.listReplicationGroups(filterId);
            var xml = new XmlBuilder().start("ReplicationGroups");
            for (ReplicationGroup g : groups) {
                xml.raw(replicationGroupXml(g));
            }
            xml.end("ReplicationGroups").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeReplicationGroups", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteReplicationGroup(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }
        try {
            ReplicationGroup group = service.getReplicationGroup(groupId);
            service.deleteReplicationGroup(groupId);
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("DeleteReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyReplicationGroup(MultivaluedMap<String, String> params) {
        String groupId = params.getFirst("ReplicationGroupId");
        if (groupId == null || groupId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "ReplicationGroupId is required.", AwsNamespaces.EC, 400);
        }
        List<String> userIdsToAdd = extractMemberList(params, "UserGroupIdsToAdd.member.");
        List<String> userIdsToRemove = extractMemberList(params, "UserGroupIdsToRemove.member.");
        try {
            // AtRestEncryptionEnabled and KmsKeyId are fixed at create and not in the modify shape
            ReplicationGroupSettings settings = new ReplicationGroupSettings(null, null,
                    optionalInt(params.getFirst("SnapshotRetentionLimit")), params.getFirst("SnapshotWindow"));
            ReplicationGroup group = service.modifyReplicationGroup(groupId,
                    userIdsToAdd.isEmpty() ? null : userIdsToAdd,
                    userIdsToRemove.isEmpty() ? null : userIdsToRemove, settings);
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("ModifyReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    private Response handleCreateUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        String userName = params.getFirst("UserName");
        String accessString = params.getFirst("AccessString");
        String authModeType = params.getFirst("AuthenticationMode.Type");
        String engine = params.getFirst("Engine");

        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        if (userName == null || userName.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserName is required.", AwsNamespaces.EC, 400);
        }

        AuthMode authMode;
        List<String> passwords = new ArrayList<>();
        if ("iam".equalsIgnoreCase(authModeType)) {
            authMode = AuthMode.IAM;
        } else if ("password".equalsIgnoreCase(authModeType)) {
            authMode = AuthMode.PASSWORD;
            passwords = extractMemberList(params, "AuthenticationMode.Passwords.member.");
        } else {
            authMode = AuthMode.NO_AUTH;
        }

        try {
            ElastiCacheUser user = service.createUser(userId, userName, authMode, passwords, accessString, engine);
            return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeUsers(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("UserId");
        String filterEngine = params.getFirst("Engine");
        try {
            Collection<ElastiCacheUser> users = service.listUsers(filterId, filterEngine);
            var xml = new XmlBuilder().start("Users");
            for (ElastiCacheUser u : users) {
                xml.start("member").raw(userXml(u)).end("member");
            }
            xml.end("Users").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeUsers", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        String engine = params.getFirst("Engine");
        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        List<String> passwords = extractMemberList(params, "AuthenticationMode.Passwords.member.");
        try {
            ElastiCacheUser user = service.modifyUser(userId, passwords.isEmpty() ? null : passwords, engine);
            return Response.ok(AwsQueryResponse.envelope("ModifyUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteUser(MultivaluedMap<String, String> params) {
        String userId = params.getFirst("UserId");
        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        try {
            ElastiCacheUser user = service.getUser(userId);
            service.deleteUser(userId);
            return Response.ok(AwsQueryResponse.envelope("DeleteUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Cache Clusters (Memcached) ────────────────────────────────────────────

    private Response handleCreateCacheCluster(MultivaluedMap<String, String> params) {
        String clusterId = params.getFirst("CacheClusterId");
        String engine = params.getFirst("Engine");

        if (clusterId == null || clusterId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "CacheClusterId is required.", AwsNamespaces.EC, 400);
        }
        if (!"memcached".equalsIgnoreCase(engine)) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "Engine must be 'memcached'. For Redis/Valkey use CreateReplicationGroup.", AwsNamespaces.EC, 400);
        }

        try {
            CacheCluster cluster = memcachedService.createCacheCluster(clusterId);
            return Response.ok(AwsQueryResponse.envelope("CreateCacheCluster", AwsNamespaces.EC, cacheClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheClusters(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("CacheClusterId");
        boolean showNodeInfo = "true".equalsIgnoreCase(params.getFirst("ShowCacheNodeInfo"));
        try {
            List<ElastiCacheService.MemberCacheCluster> members = service.listMemberCacheClusters(filterId);
            Collection<CacheCluster> clusterList;
            if (filterId != null && !filterId.isBlank() && !members.isEmpty()) {
                clusterList = List.of();
            } else {
                clusterList = memcachedService.listCacheClusters(filterId);
            }
            var xml = new XmlBuilder().start("CacheClusters");
            for (CacheCluster c : clusterList) {
                xml.raw(cacheClusterXml(c));
            }
            for (ElastiCacheService.MemberCacheCluster member : members) {
                xml.raw(memberCacheClusterXml(member, showNodeInfo));
            }
            xml.end("CacheClusters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheClusters", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private String memberCacheClusterXml(ElastiCacheService.MemberCacheCluster member, boolean showNodeInfo) {
        ReplicationGroup g = member.group();
        boolean authTokenEnabled = g.getAuthMode() == AuthMode.PASSWORD;
        var xml = new XmlBuilder()
                .start("CacheCluster")
                  .elem("CacheClusterId", member.cacheClusterId())
                  .elem("CacheClusterStatus", memberCacheClusterStatus(g))
                  .elem("NumCacheNodes", 1L)
                  .elem("ReplicationGroupId", g.getReplicationGroupId())
                  .elem("AutoMinorVersionUpgrade", true)
                  .elem("AuthTokenEnabled", authTokenEnabled)
                  .elem("TransitEncryptionEnabled", authTokenEnabled)
                  .elem("AtRestEncryptionEnabled", false);
        if (g.getEngine() != null) {
            xml.elem("Engine", g.getEngine());
        }
        if (g.getEngineVersion() != null) {
            xml.elem("EngineVersion", g.getEngineVersion());
        }
        if (g.getCacheNodeType() != null) {
            xml.elem("CacheNodeType", g.getCacheNodeType());
        }
        if (g.getCacheParameterGroupName() != null) {
            xml.start("CacheParameterGroup")
               .elem("CacheParameterGroupName", g.getCacheParameterGroupName())
               .elem("ParameterApplyStatus", "in-sync")
               .end("CacheParameterGroup");
        }
        if (g.getCacheSubnetGroupName() != null) {
            xml.elem("CacheSubnetGroupName", g.getCacheSubnetGroupName());
        }
        if (showNodeInfo && g.getConfigurationEndpoint() != null) {
            xml.start("CacheNodes")
               .start("CacheNode")
                 .elem("CacheNodeId", "0001")
                 .elem("CacheNodeStatus", "available")
                 .start("Endpoint")
                   .elem("Address", g.getConfigurationEndpoint().address())
                   .elem("Port", (long) member.port())
                 .end("Endpoint")
               .end("CacheNode")
               .end("CacheNodes");
        }
        return xml.end("CacheCluster").build();
    }

    /**
     * Members mirror the group's status, except {@code create-failed}: that value is only legal
     * on ReplicationGroup.Status, so members report the documented {@code restore-failed}.
     */
    private static String memberCacheClusterStatus(ReplicationGroup g) {
        if (g.getStatus() == ReplicationGroupStatus.CREATE_FAILED) {
            return "restore-failed";
        }
        return g.getStatus().wireName();
    }

    private Response handleDeleteCacheCluster(MultivaluedMap<String, String> params) {
        String clusterId = params.getFirst("CacheClusterId");
        if (clusterId == null || clusterId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue",
                    "CacheClusterId is required.", AwsNamespaces.EC, 400);
        }
        try {
            CacheCluster cluster = memcachedService.deleteCacheCluster(clusterId);
            return Response.ok(AwsQueryResponse.envelope("DeleteCacheCluster", AwsNamespaces.EC, cacheClusterXml(cluster))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    // ── Subnet / Parameter Groups (read-only describes for resources not modeled) ────

    private Response handleCreateCacheSubnetGroup(MultivaluedMap<String, String> params) {
        try {
            CacheSubnetGroup group = service.createCacheSubnetGroup(
                    params.getFirst("CacheSubnetGroupName"),
                    params.getFirst("CacheSubnetGroupDescription"),
                    parseSubnetIds(params),
                    parseTags(params));
            var xml = new XmlBuilder();
            appendSubnetGroup(xml, group);
            return Response.ok(AwsQueryResponse.envelope("CreateCacheSubnetGroup", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheSubnetGroups(MultivaluedMap<String, String> params) {
        try {
            List<CacheSubnetGroup> groups =
                    service.describeCacheSubnetGroups(params.getFirst("CacheSubnetGroupName"));
            var xml = new XmlBuilder().start("CacheSubnetGroups");
            groups.forEach(group -> appendSubnetGroup(xml, group));
            xml.end("CacheSubnetGroups");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheSubnetGroups", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyCacheSubnetGroup(MultivaluedMap<String, String> params) {
        try {
            CacheSubnetGroup group = service.modifyCacheSubnetGroup(
                    params.getFirst("CacheSubnetGroupName"),
                    params.getFirst("CacheSubnetGroupDescription"),
                    parseSubnetIds(params));
            var xml = new XmlBuilder();
            appendSubnetGroup(xml, group);
            return Response.ok(AwsQueryResponse.envelope("ModifyCacheSubnetGroup", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteCacheSubnetGroup(MultivaluedMap<String, String> params) {
        try {
            service.deleteCacheSubnetGroup(params.getFirst("CacheSubnetGroupName"));
            return Response.ok(AwsQueryResponse.envelope("DeleteCacheSubnetGroup", AwsNamespaces.EC, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private void appendSubnetGroup(XmlBuilder xml, CacheSubnetGroup group) {
        xml.start("CacheSubnetGroup")
                .elem("CacheSubnetGroupName", group.getName())
                .elem("CacheSubnetGroupDescription", group.getDescription())
                .elem("VpcId", group.getVpcId())
                .start("Subnets");
        group.getSubnetAvailabilityZones().forEach((subnetId, availabilityZone) -> xml
                .start("Subnet")
                .elem("SubnetIdentifier", subnetId)
                .start("SubnetAvailabilityZone").elem("Name", availabilityZone).end("SubnetAvailabilityZone")
                .start("SupportedNetworkTypes").elem("member", "ipv4").end("SupportedNetworkTypes")
                .end("Subnet"));
        xml.end("Subnets")
                .start("SupportedNetworkTypes").elem("member", "ipv4").end("SupportedNetworkTypes")
                .elem("ARN", "arn:aws:elasticache:" + regionResolver.getRegion() + ":"
                        + regionResolver.getAccountId() + ":subnetgroup:" + group.getName())
                .end("CacheSubnetGroup");
    }

    /** Reads the SubnetIds list under every spelling the Query protocol sends it in. */
    private static List<String> parseSubnetIds(MultivaluedMap<String, String> params) {
        List<String> subnetIds = new ArrayList<>();
        for (String prefix : List.of("SubnetIds.SubnetIdentifier", "SubnetIds.member")) {
            for (int i = 1; ; i++) {
                String subnetId = params.getFirst(prefix + "." + i);
                if (subnetId == null) {
                    break;
                }
                subnetIds.add(subnetId);
            }
        }
        return subnetIds;
    }

private Response handleCreateCacheParameterGroup(MultivaluedMap<String, String> params) {
        try {
            CacheParameterGroup group = service.createCacheParameterGroup(
                    params.getFirst("CacheParameterGroupName"),
                    params.getFirst("CacheParameterGroupFamily"),
                    params.getFirst("Description"),
                    parseTags(params));
            var xml = new XmlBuilder();
            appendParameterGroup(xml, group);
            return Response.ok(AwsQueryResponse.envelope("CreateCacheParameterGroup", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheParameterGroups(MultivaluedMap<String, String> params) {
        try {
            List<CacheParameterGroup> groups =
                    service.describeCacheParameterGroups(params.getFirst("CacheParameterGroupName"));
            var xml = new XmlBuilder().start("CacheParameterGroups");
            groups.forEach(group -> appendParameterGroup(xml, group));
            xml.end("CacheParameterGroups");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheParameterGroups", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleModifyCacheParameterGroup(MultivaluedMap<String, String> params) {
        try {
            String name = params.getFirst("CacheParameterGroupName");
            service.modifyCacheParameterGroup(name, parseParameterNameValues(params));
            var xml = new XmlBuilder().elem("CacheParameterGroupName", name);
            return Response.ok(AwsQueryResponse.envelope("ModifyCacheParameterGroup", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeCacheParameters(MultivaluedMap<String, String> params) {
        try {
            CacheParameterGroup group = service.requireParameterGroup(params.getFirst("CacheParameterGroupName"));
            // floci stores only what a caller set, so every parameter it can report has source "user".
            // A request for another source gets the empty list rather than invented defaults.
            String source = params.getFirst("Source");
            boolean includeUserParameters = source == null || source.isBlank() || "user".equals(source);

            var xml = new XmlBuilder().start("Parameters");
            if (includeUserParameters) {
                group.getParameters().forEach((name, value) -> xml
                        .start("Parameter")
                        .elem("ParameterName", name)
                        .elem("ParameterValue", value)
                        .elem("Source", "user")
                        .elem("IsModifiable", true)
                        .end("Parameter"));
            }
            xml.end("Parameters")
                    .start("CacheNodeTypeSpecificParameters").end("CacheNodeTypeSpecificParameters");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheParameters", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    /**
     * Tags for a resource ARN. Only parameter groups carry tags in floci, so any other ARN reports
     * none — which is what floci knows, rather than a guess at what AWS would hold.
     */
    private Response handleListTagsForResource(MultivaluedMap<String, String> params) {
        try {
            String resourceName = params.getFirst("ResourceName");
            String[] arn = resourceName == null ? new String[0] : resourceName.split(":", -1);
            if (arn.length != 7 || !"arn".equals(arn[0])) {
                throw new AwsException("InvalidARN", "Input ARN string does not have 7 components.", 400);
            }
            // Every component names part of the resource, so each is checked before the name is
            // used: a partition, service, region or account that is not this one asks about a
            // different resource, and answering from the trailing name would describe the wrong.
            if (!"aws".equals(arn[1])) {
                throw new AwsException("InvalidARN", "partition field is wrong. Expected value is aws", 400);
            }
            if (!"elasticache".equals(arn[2])) {
                throw new AwsException("InvalidARN",
                        "service field is wrong. Expected value is elasticache", 400);
            }
            if (!regionResolver.getRegion().equals(arn[3])) {
                throw new AwsException("InvalidParameterValue",
                        "Unauthorized call. Please check the region or customer id", 400);
            }
            if (!regionResolver.getAccountId().equals(arn[4])) {
                throw new AwsException("InvalidParameterValue",
                        "The resource ARN does not belong to the caller's account.", 400);
            }

            Map<String, String> tags = Map.of();
            if ("replicationgroup".equals(arn[5])) {
                // the store keys groups by id alone; the record must be the one the ARN names
                ReplicationGroup group = service.getReplicationGroup(arn[6]);
                if (group.getArn() != null && !group.getArn().equalsIgnoreCase(resourceName)) {
                    throw new AwsException("ReplicationGroupNotFoundFault",
                            "Replication group " + arn[6] + " not found.", 404);
                }
                tags = group.getTags();
            }
            if ("subnetgroup".equals(arn[5])) {
                tags = service.describeCacheSubnetGroups(arn[6]).getFirst().getTags();
            }
            if ("parametergroup".equals(arn[5])) {
                tags = service.findParameterGroup(arn[6])
                        .orElseThrow(() -> new AwsException("CacheParameterGroupNotFound",
                                arn[6] + " is not present", 404))
                        .getTags();
            }
            var xml = new XmlBuilder().start("TagList");
            tags.forEach((key, value) -> xml.start("Tag")
                    .elem("Key", key)
                    .elem("Value", value)
                    .end("Tag"));
            xml.end("TagList");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDeleteCacheParameterGroup(MultivaluedMap<String, String> params) {
        try {
            service.deleteCacheParameterGroup(params.getFirst("CacheParameterGroupName"));
            return Response.ok(AwsQueryResponse.envelope("DeleteCacheParameterGroup", AwsNamespaces.EC, "")).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private void appendParameterGroup(XmlBuilder xml, CacheParameterGroup group) {
        xml.start("CacheParameterGroup")
                .elem("CacheParameterGroupName", group.getName())
                .elem("CacheParameterGroupFamily", group.getFamily())
                .elem("Description", group.getDescription())
                .elem("IsGlobal", false)
                .elem("ARN", parameterGroupArn(group.getName()))
                .end("CacheParameterGroup");
    }

    private String parameterGroupArn(String name) {
        return "arn:aws:elasticache:" + regionResolver.getRegion() + ":"
                + regionResolver.getAccountId() + ":parametergroup:" + name;
    }

    /**
     * Reads a Query-protocol list of pairs under every spelling it arrives in. The member element
     * takes its name from the shape's {@code locationName} — {@code Tag} and
     * {@code ParameterNameValue} here, not {@code member} — and SDKs differ, so each is tried.
     */
    private static Map<String, String> parsePairs(MultivaluedMap<String, String> params,
                                                  List<String> prefixes, String keyName, String valueName) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            for (int i = 1; ; i++) {
                String key = params.getFirst(prefix + "." + i + "." + keyName);
                if (key == null) {
                    break;
                }
                pairs.put(key, params.getFirst(prefix + "." + i + "." + valueName));
            }
        }
        return pairs;
    }

    private static Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        return parsePairs(params, List.of("Tags.Tag", "Tags.member", "Tag"), "Key", "Value");
    }

    private static Map<String, String> parseParameterNameValues(MultivaluedMap<String, String> params) {
        return parsePairs(params,
                List.of("ParameterNameValues.ParameterNameValue", "ParameterNameValues.member"),
                "ParameterName", "ParameterValue");
    }

    // ── IAM Token Validation ──────────────────────────────────────────────────

    private Response handleValidateIamAuthToken(MultivaluedMap<String, String> params) {
        String token = params.getFirst("Token");
        if (token == null || token.isBlank()) {
            return AwsQueryResponse.error("InvalidParameter", "Token parameter is required.", AwsNamespaces.EC, 400);
        }
        try {
            boolean valid = sigV4Validator.validate(token, null, null);
            if (!valid) {
                return AwsQueryResponse.error("SignatureDoesNotMatch",
                        "The request signature does not match.", AwsNamespaces.EC, 403);
            }
            String clusterId = extractUriHost(token);
            String userId = extractQueryParam(token, "User");
            LOG.infov("ElastiCache IAM token validated: clusterId={0} userId={1}", clusterId, userId);
            String result = new XmlBuilder()
                    .elem("Valid", true)
                    .elem("ClusterId", clusterId)
                    .elem("UserId", userId)
                    .build();
            return Response.ok(AwsQueryResponse.envelope("ValidateIamAuthToken", AwsNamespaces.EC, result)).build();
        } catch (Exception e) {
            LOG.warnv("ElastiCache token validation error: {0}", e.getMessage());
            return AwsQueryResponse.error("InvalidToken",
                    "Failed to validate token: " + e.getMessage(), AwsNamespaces.EC, 400);
        }
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private String cacheClusterXml(CacheCluster c) {
        Endpoint ep = c.getConfigurationEndpoint();
        var xml = new XmlBuilder()
                .start("CacheCluster")
                  .elem("CacheClusterId", c.getCacheClusterId())
                  .elem("CacheClusterStatus", c.getCacheClusterStatus().name().toLowerCase())
                  .elem("Engine", c.getEngine())
                  .elem("EngineVersion", c.getEngineVersion());
        if (ep != null) {
            xml.start("ConfigurationEndpoint")
               .elem("Address", ep.address())
               .elem("Port", (long) ep.port())
               .end("ConfigurationEndpoint");
        }
        return xml.end("CacheCluster").build();
    }

    private String replicationGroupXml(ReplicationGroup g) {
        Endpoint ep = g.getConfigurationEndpoint();
        boolean authTokenEnabled = g.getAuthMode() == AuthMode.PASSWORD;
        List<ElastiCacheService.MemberCacheCluster> members = service.memberCacheClusters(g);
        var xml = new XmlBuilder()
                .start("ReplicationGroup")
                  .elem("ReplicationGroupId", g.getReplicationGroupId())
                  .elem("Description", g.getDescription())
                  .elem("Status", g.getStatus().wireName())
                  .elem("AuthTokenEnabled", authTokenEnabled)
                  .elem("TransitEncryptionEnabled", authTokenEnabled)
                  .elem("AtRestEncryptionEnabled", g.isAtRestEncryptionEnabled())
                  .elem("ClusterEnabled", g.isClusterEnabled())
                  .elem("ClusterMode", g.isClusterEnabled() ? "enabled" : "disabled")
                  .elem("MultiAZ", g.isMultiAzEnabled() ? "enabled" : "disabled")
                  .elem("AutomaticFailover", g.isAutomaticFailoverEnabled() ? "enabled" : "disabled")
                  .elem("SnapshotRetentionLimit", (long) g.getSnapshotRetentionLimit());
        xml.elem("SnapshotWindow", g.getSnapshotWindow() != null
                ? g.getSnapshotWindow() : ReplicationGroupSettings.DEFAULT_SNAPSHOT_WINDOW);
        if (g.getKmsKeyId() != null) {
            xml.elem("KmsKeyId", g.getKmsKeyId());
        }
        if (g.getEngine() != null) {
            xml.elem("Engine", g.getEngine());
        }
        if (g.getCacheNodeType() != null) {
            xml.elem("CacheNodeType", g.getCacheNodeType());
        }
        if (g.getArn() != null) {
            xml.elem("ARN", g.getArn());
        }
        xml.start("MemberClusters");
        for (ElastiCacheService.MemberCacheCluster member : members) {
            xml.elem("ClusterId", member.cacheClusterId());
        }
        xml.end("MemberClusters");
        xml.start("NodeGroups");
        if (g.isClusterEnabled() && !g.getClusterNodes().isEmpty()) {
            appendClusterModeNodeGroups(xml, g);
        } else {
            appendSingleNodeGroup(xml, g, members, ep);
        }
        xml.end("NodeGroups");
        if (ep != null) {
            xml.start("ConfigurationEndpoint")
               .elem("Address", ep.address())
               .elem("Port", (long) ep.port())
               .end("ConfigurationEndpoint");
        }
        return xml.end("ReplicationGroup").build();
    }

    private static void appendClusterModeNodeGroups(XmlBuilder xml, ReplicationGroup g) {
        Map<String, List<ClusterNode>> byNodeGroup = new LinkedHashMap<>();
        for (ClusterNode node : g.getClusterNodes()) {
            byNodeGroup.computeIfAbsent(node.getNodeGroupId(), key -> new ArrayList<>()).add(node);
        }
        byNodeGroup.forEach((nodeGroupId, nodes) -> {
            xml.start("NodeGroup")
                    .elem("NodeGroupId", nodeGroupId)
                    .elem("Status", "available")
                    .elem("Slots", nodes.getFirst().getSlots())
                    .start("NodeGroupMembers");
            for (ClusterNode node : nodes) {
                xml.start("NodeGroupMember")
                        .elem("CacheClusterId", node.getMemberClusterId())
                        .elem("CacheNodeId", "0001")
                        .end("NodeGroupMember");
            }
            xml.end("NodeGroupMembers").end("NodeGroup");
        });
    }

    private static void appendSingleNodeGroup(XmlBuilder xml, ReplicationGroup g,
                                              List<ElastiCacheService.MemberCacheCluster> members,
                                              Endpoint ep) {
        xml.start("NodeGroup")
                .elem("NodeGroupId", "0001")
                .elem("Status", "available");
        if (ep != null) {
            xml.start("PrimaryEndpoint")
                    .elem("Address", ep.address())
                    .elem("Port", (long) ep.port())
                    .end("PrimaryEndpoint")
                    .start("ReaderEndpoint")
                    .elem("Address", ep.address())
                    .elem("Port", (long) ep.port())
                    .end("ReaderEndpoint");
        }
        xml.start("NodeGroupMembers");
        for (ElastiCacheService.MemberCacheCluster member : members) {
            xml.start("NodeGroupMember")
                    .elem("CacheClusterId", member.cacheClusterId())
                    .elem("CacheNodeId", "0001")
                    .elem("CurrentRole", member.primary() ? "primary" : "replica");
            if (ep != null) {
                xml.start("ReadEndpoint")
                        .elem("Address", ep.address())
                        .elem("Port", (long) member.port())
                        .end("ReadEndpoint");
            }
            xml.end("NodeGroupMember");
        }
        xml.end("NodeGroupMembers").end("NodeGroup");
    }

    private String userXml(ElastiCacheUser u) {
        String authType = switch (u.getAuthMode()) {
            case IAM -> "iam";
            case PASSWORD -> "password";
            case NO_AUTH -> "no-password-required";
        };
        int pwCount = (u.getPasswords() != null) ? u.getPasswords().size() : 0;
        return new XmlBuilder()
                .elem("UserId", u.getUserId())
                .elem("UserName", u.getUserName())
                .elem("Status", u.getStatus())
                .elem("AccessString", u.getAccessString())
                .start("Authentication")
                  .elem("Type", authType)
                  .elem("PasswordCount", (long) pwCount)
                .end("Authentication")
                .elem("Engine", u.getEngine())
                // MinimumEngineVersion: the only value AWS documents; no valkey-specific one is published.
                .elem("MinimumEngineVersion", "6.0")
                .start("UserGroupIds").end("UserGroupIds")
                .elem("ARN", AwsArnUtils.Arn.of("elasticache", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "user:" + u.getUserId()).toString())
                .build();
    }

    private static List<String> extractMemberList(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static String extractUriHost(String token) {
        try {
            return java.net.URI.create("http://" + token).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractQueryParam(String token, String name) {
        try {
            String rawQuery = java.net.URI.create("http://" + token).getRawQuery();
            if (rawQuery == null) {
                return "";
            }
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0 && name.equals(pair.substring(0, eq))) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
