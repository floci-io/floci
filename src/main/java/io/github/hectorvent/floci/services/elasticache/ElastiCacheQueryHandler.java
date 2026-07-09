package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    public Response handle(String action, MultivaluedMap<String, String> params) {
        LOG.debugv("ElastiCache action: {0}", action);
        return switch (action) {
            case "ValidateIamAuthToken"       -> handleValidateIamAuthToken(params);
            case "CreateReplicationGroup"     -> handleCreateReplicationGroup(params);
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
            case "DescribeCacheSubnetGroups"  -> handleDescribeCacheSubnetGroups(params);
            case "DescribeCacheParameterGroups" -> handleDescribeCacheParameterGroups(params);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported.", AwsNamespaces.EC, 400);
        };
    }

    // ── Replication Groups ────────────────────────────────────────────────────

    private Response handleCreateReplicationGroup(MultivaluedMap<String, String> params) {
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
                    groupId, description != null ? description : "", authMode, authToken);
            String result = replicationGroupXml(group);
            return Response.ok(AwsQueryResponse.envelope("CreateReplicationGroup", AwsNamespaces.EC, result)).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
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
            ReplicationGroup group = service.modifyReplicationGroup(groupId,
                    userIdsToAdd.isEmpty() ? null : userIdsToAdd,
                    userIdsToRemove.isEmpty() ? null : userIdsToRemove);
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
            ElastiCacheUser user = service.createUser(userId, userName, authMode, passwords, accessString);
            return Response.ok(AwsQueryResponse.envelope("CreateUser", AwsNamespaces.EC, userXml(user))).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
    }

    private Response handleDescribeUsers(MultivaluedMap<String, String> params) {
        String filterId = params.getFirst("UserId");
        try {
            Collection<ElastiCacheUser> users = service.listUsers(filterId);
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
        if (userId == null || userId.isBlank()) {
            return AwsQueryResponse.error("InvalidParameterValue", "UserId is required.", AwsNamespaces.EC, 400);
        }
        List<String> passwords = extractMemberList(params, "AuthenticationMode.Passwords.member.");
        try {
            ElastiCacheUser user = service.modifyUser(userId, passwords.isEmpty() ? null : passwords);
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
        try {
            Collection<CacheCluster> clusterList = memcachedService.listCacheClusters(filterId);
            var xml = new XmlBuilder().start("CacheClusters");
            for (CacheCluster c : clusterList) {
                xml.raw(cacheClusterXml(c));
            }
            xml.end("CacheClusters").start("Marker").end("Marker");
            return Response.ok(AwsQueryResponse.envelope("DescribeCacheClusters", AwsNamespaces.EC, xml.build())).build();
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.EC, e.getHttpStatus());
        }
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

    private Response handleDescribeCacheSubnetGroups(MultivaluedMap<String, String> params) {
        // Cache subnet groups are not modeled by the emulator; return the wire-accurate
        // empty result so SDK clients get a valid 200 instead of an unsupported-action 400.
        var xml = new XmlBuilder().start("CacheSubnetGroups").end("CacheSubnetGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeCacheSubnetGroups", AwsNamespaces.EC, xml.build())).build();
    }

    private Response handleDescribeCacheParameterGroups(MultivaluedMap<String, String> params) {
        // Cache parameter groups are not modeled by the emulator; return the wire-accurate
        // empty result so SDK clients get a valid 200 instead of an unsupported-action 400.
        var xml = new XmlBuilder().start("CacheParameterGroups").end("CacheParameterGroups");
        return Response.ok(AwsQueryResponse.envelope("DescribeCacheParameterGroups", AwsNamespaces.EC, xml.build())).build();
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
        var xml = new XmlBuilder()
                .start("ReplicationGroup")
                  .elem("ReplicationGroupId", g.getReplicationGroupId())
                  .elem("Description", g.getDescription())
                  .elem("Status", g.getStatus().name().toLowerCase())
                  .elem("AuthTokenEnabled", authTokenEnabled)
                  .elem("TransitEncryptionEnabled", authTokenEnabled)
                  .elem("AtRestEncryptionEnabled", false)
                  .elem("ClusterEnabled", false)
                  .elem("MultiAZ", "disabled")
                  .elem("AutomaticFailover", "disabled")
                  .elem("SnapshotRetentionLimit", 0L);
        if (ep != null) {
            xml.start("ConfigurationEndpoint")
               .elem("Address", ep.address())
               .elem("Port", (long) ep.port())
               .end("ConfigurationEndpoint");
        }
        return xml.end("ReplicationGroup").build();
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
                .elem("Engine", "redis")
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
