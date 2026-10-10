package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheMemcachedService;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheService;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUserGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions AWS::ElastiCache::CacheCluster, SubnetGroup, User and UserGroup. A cache cluster needs
 * the Memcached service as well as the Redis one, because the two engines keep their clusters in
 * separate stores.
 */
@ApplicationScoped
public class ElastiCacheCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ElastiCacheCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CACHE_CLUSTER = "AWS::ElastiCache::CacheCluster";
    private static final String SUBNET_GROUP = "AWS::ElastiCache::SubnetGroup";
    private static final String USER = "AWS::ElastiCache::User";
    private static final String USER_GROUP = "AWS::ElastiCache::UserGroup";
    private static final String SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR = "__FlociCacheSubnetGroupUpdateSnapshot";
    private static final String USER_UPDATE_SNAPSHOT_ATTR = "__FlociUserUpdateSnapshot";
    private static final String USER_GROUP_UPDATE_SNAPSHOT_ATTR = "__FlociUserGroupUpdateSnapshot";
    private static final List<String> UNSUPPORTED_CLUSTER_PROPERTIES = List.of("CacheSecurityGroupNames",
            "NotificationTopicArn", "AZMode", "PreferredAvailabilityZones", "LogDeliveryConfigurations",
            "SnapshotArns", "SnapshotName");
    private static final String DEFAULT_NETWORK_TYPE = "ipv4";
    private static final int CLUSTER_ID_MAX_LENGTH = 50;
    private static final int SUBNET_GROUP_NAME_MAX_LENGTH = 255;

    private final ElastiCacheService elastiCacheService;
    private final ElastiCacheMemcachedService memcachedService;

    @Inject
    public ElastiCacheCfnProvisioner(ElastiCacheService elastiCacheService,
                                     ElastiCacheMemcachedService memcachedService) {
        this.elastiCacheService = elastiCacheService;
        this.memcachedService = memcachedService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CACHE_CLUSTER, SUBNET_GROUP, USER, USER_GROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
        r.getAttributes().remove(USER_UPDATE_SNAPSHOT_ATTR);
        r.getAttributes().remove(USER_GROUP_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case CACHE_CLUSTER -> provisionCacheCluster(r, props, ctx);
            case SUBNET_GROUP -> provisionSubnetGroup(r, props, ctx);
            case USER -> provisionUser(r, props, ctx);
            case USER_GROUP -> provisionUserGroup(r, props, ctx);
            default -> throw new IllegalStateException(
                    "ElastiCacheCfnProvisioner cannot provision " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
            resource.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
            resource.getAttributes().remove(USER_UPDATE_SNAPSHOT_ATTR);
            resource.getAttributes().remove(USER_GROUP_UPDATE_SNAPSHOT_ATTR);
        }
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(USER_UPDATE_SNAPSHOT_ATTR);
        resource.getAttributes().remove(USER_GROUP_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (SUBNET_GROUP.equals(resource.getResourceType())) {
            String rawSnapshot = resource.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
            if (rawSnapshot != null) {
                try {
                    SubnetGroupSnapshot snapshot = MAPPER.readValue(rawSnapshot, SubnetGroupSnapshot.class);
                    elastiCacheService.modifyCacheSubnetGroup(snapshot.name(), snapshot.description(),
                            snapshot.subnetIds(), snapshot.tags());
                    return true;
                } catch (Exception e) {
                    LOG.errorv("Could not restore subnet group {0} after failed update: {1}",
                            resource.getPhysicalId(), e.getMessage());
                }
            }
        }
        if (USER.equals(resource.getResourceType())) {
            String rawSnapshot = resource.getAttributes().remove(USER_UPDATE_SNAPSHOT_ATTR);
            if (rawSnapshot != null) {
                try {
                    UserSnapshot snapshot = MAPPER.readValue(rawSnapshot, UserSnapshot.class);
                    elastiCacheService.modifyUser(snapshot.userId(), snapshot.authMode(),
                            snapshot.passwords(), snapshot.accessString(), null, snapshot.engine());
                    return true;
                } catch (Exception e) {
                    LOG.errorv("Could not restore user {0} after failed update: {1}",
                            resource.getPhysicalId(), e.getMessage());
                }
            }
        }
        if (USER_GROUP.equals(resource.getResourceType())) {
            String rawSnapshot = resource.getAttributes().remove(USER_GROUP_UPDATE_SNAPSHOT_ATTR);
            if (rawSnapshot != null) {
                try {
                    UserGroupSnapshot snapshot = MAPPER.readValue(rawSnapshot, UserGroupSnapshot.class);
                    elastiCacheService.setUserGroupMembers(snapshot.userGroupId(), snapshot.userIds(), snapshot.engine());
                    return true;
                } catch (Exception e) {
                    LOG.errorv("Could not restore user group {0} after failed update: {1}",
                            resource.getPhysicalId(), e.getMessage());
                }
            }
        }
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private void provisionCacheCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String engine = ctx.resolveOptional(props, "Engine");
        if (engine == null || engine.isBlank()) {
            throw new AwsException("ValidationException", "Engine is required", 400);
        }
        String nodeType = ctx.resolveOptional(props, "CacheNodeType");
        if (nodeType == null || nodeType.isBlank()) {
            throw new AwsException("ValidationException", "CacheNodeType is required", 400);
        }
        String explicitNodes = ctx.resolveOptional(props, "NumCacheNodes");
        if (explicitNodes == null || explicitNodes.isBlank()) {
            throw new AwsException("ValidationException", "NumCacheNodes is required", 400);
        }
        String explicitName = ctx.resolveOptional(props, "ClusterName");
        if (explicitName != null && explicitName.length() > CLUSTER_ID_MAX_LENGTH) {
            throw new AwsException("ValidationException",
                    "ClusterName exceeds maximum length of " + CLUSTER_ID_MAX_LENGTH, 400);
        }
        String subnetGroup = ctx.resolveOptional(props, "CacheSubnetGroupName");
        Integer port = optionalInt(ctx.resolveOptional(props, "Port"));

        CacheCluster prior = ctx.isUpdate() ? findCluster(ctx.priorPhysicalId()) : null;
        // Engine, CacheSubnetGroupName and NetworkType are create-only in AWS: a change replaces the
        // cluster, so an unnamed one gets a fresh generated id. Port is not compared, because the
        // emulator reports its own host proxy port rather than the requested one.
        String networkType = ctx.resolveOptional(props, "NetworkType");
        String desiredNetworkType = networkType == null || networkType.isBlank() ? DEFAULT_NETWORK_TYPE : networkType;
        // A cluster stored before the type was recorded reads as the default, which is what it was created with.
        String storedNetworkType = prior == null || prior.getNetworkType() == null
                ? DEFAULT_NETWORK_TYPE : prior.getNetworkType();
        boolean createOnlyChanged = prior != null
                && (!engine.equalsIgnoreCase(prior.getEngine())
                || !Objects.equals(subnetGroup, prior.getCacheSubnetGroupName())
                || !desiredNetworkType.equals(storedNetworkType));
        if (createOnlyChanged && explicitName != null && explicitName.equals(ctx.priorPhysicalId())) {
            throw new AwsException("InvalidParameterValue", "Updating a create-only property of cache cluster "
                    + explicitName + " requires a new ClusterName", 400);
        }
        String id;
        if (createOnlyChanged && explicitName == null) {
            id = ctx.generatePhysicalName(r.getLogicalId(), CLUSTER_ID_MAX_LENGTH, true);
        } else {
            id = ctx.stablePhysicalName(explicitName, r.getLogicalId(), CLUSTER_ID_MAX_LENGTH, true);
        }

        CacheCluster cluster;
        if (ctx.reusesPriorEntity(id)) {
            cluster = prior != null ? prior : findCluster(id);
            if (cluster == null) {
                LOG.warnv("ElastiCache cache cluster {0} was deleted outside the stack, recreating it", id);
                cluster = createCluster(id, engine, port, props, ctx);
            } else {
                warnIgnoredClusterChanges(id, cluster, props, ctx);
            }
        } else {
            cluster = createCluster(id, engine, port, props, ctx);
        }

        r.setPhysicalId(id);
        putEndpointAttributes(r, cluster);
    }

    private void warnUnsupportedClusterProperties(String id, JsonNode props) {
        for (String name : UNSUPPORTED_CLUSTER_PROPERTIES) {
            if (props.hasNonNull(name)) {
                LOG.warnv("ElastiCache cache cluster {0}: {1} is accepted and ignored", id, name);
            }
        }
    }

    private void warnIgnoredClusterChanges(String id, CacheCluster cluster, JsonNode props, ProvisionContext ctx) {
        String nodeType = ctx.resolveOptional(props, "CacheNodeType");
        String nodes = ctx.resolveOptional(props, "NumCacheNodes");
        String version = ctx.resolveOptional(props, "EngineVersion");
        boolean changed = (nodeType != null && !nodeType.equals(cluster.getCacheNodeType()))
                || (nodes != null && Integer.parseInt(nodes) != cluster.getNumCacheNodes())
                || (version != null && !version.equals(cluster.getEngineVersion()));
        if (changed) {
            LOG.warnv("ElastiCache cache cluster {0}: in-place modification is not supported, changes to CacheNodeType, NumCacheNodes and EngineVersion are ignored", id);
        }
    }

    private CacheCluster createCluster(String id, String engine, Integer port, JsonNode props,
                                       ProvisionContext ctx) {
        warnUnsupportedClusterProperties(id, props);
        List<String> securityGroupIds = ctx.resolveStringList(props, "VpcSecurityGroupIds");
        ElastiCacheService.CreateCacheClusterRequest request = new ElastiCacheService.CreateCacheClusterRequest(
                id,
                engine,
                ctx.resolveOptional(props, "EngineVersion"),
                ctx.resolveOptional(props, "CacheNodeType"),
                optionalInt(ctx.resolveOptional(props, "NumCacheNodes")),
                port,
                AuthMode.NO_AUTH,
                null,
                ctx.resolveOptional(props, "CacheParameterGroupName"),
                ctx.resolveOptional(props, "CacheSubnetGroupName"),
                optionalInt(ctx.resolveOptional(props, "SnapshotRetentionLimit")),
                ctx.resolveOptional(props, "SnapshotWindow"),
                ctx.resolveOptional(props, "PreferredMaintenanceWindow"),
                ctx.resolveOptional(props, "PreferredAvailabilityZone"),
                securityGroupIds.isEmpty() ? null : securityGroupIds,
                ctx.resolveOptional(props, "NetworkType"),
                ctx.resolveOptional(props, "IpDiscovery"),
                null,
                ctx.region(),
                ctx.resolveTags(props, "Tags"));
        return "memcached".equalsIgnoreCase(engine)
                ? memcachedService.createCacheCluster(request)
                : elastiCacheService.createCacheCluster(request);
    }

    private void putEndpointAttributes(StackResource r, CacheCluster cluster) {
        r.getAttributes().remove("RedisEndpoint.Address");
        r.getAttributes().remove("RedisEndpoint.Port");
        r.getAttributes().remove("ConfigurationEndpoint.Address");
        r.getAttributes().remove("ConfigurationEndpoint.Port");
        Endpoint endpoint = cluster.getConfigurationEndpoint();
        if (endpoint == null) {
            return;
        }
        // CloudFormation exposes a memcached cluster through ConfigurationEndpoint and a
        // redis or valkey one through RedisEndpoint.
        String prefix = "memcached".equalsIgnoreCase(cluster.getEngine())
                ? "ConfigurationEndpoint" : "RedisEndpoint";
        r.getAttributes().put(prefix + ".Address", endpoint.address());
        r.getAttributes().put(prefix + ".Port", String.valueOf(endpoint.port()));
    }

    private CacheCluster findCluster(String clusterId) {
        if (clusterId == null || clusterId.isBlank()) {
            return null;
        }
        List<CacheCluster> found = elastiCacheService.findCacheClusters(clusterId);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        try {
            return memcachedService.getCacheCluster(clusterId);
        } catch (AwsException e) {
            if ("CacheClusterNotFound".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private void provisionSubnetGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "Description");
        if (description == null || description.isBlank()) {
            throw new AwsException("ValidationException", "Description is required", 400);
        }
        String explicitName = ctx.resolveOptional(props, "CacheSubnetGroupName");
        if (explicitName != null && explicitName.length() > SUBNET_GROUP_NAME_MAX_LENGTH) {
            throw new AwsException("ValidationException",
                    "CacheSubnetGroupName exceeds maximum length of " + SUBNET_GROUP_NAME_MAX_LENGTH, 400);
        }
        String id = ctx.stablePhysicalName(explicitName, r.getLogicalId(), SUBNET_GROUP_NAME_MAX_LENGTH, true);
        List<String> subnetIds = ctx.resolveStringList(props, "SubnetIds");

        CacheSubnetGroup group;
        if (ctx.reusesPriorEntity(id)) {
            List<CacheSubnetGroup> existing = elastiCacheService.describeCacheSubnetGroups(id);
            if (!existing.isEmpty()) {
                CacheSubnetGroup prior = existing.getFirst();
                SubnetGroupSnapshot snapshot = new SubnetGroupSnapshot(
                        prior.getName(),
                        prior.getDescription(),
                        new ArrayList<>(prior.getSubnetAvailabilityZones().keySet()),
                        prior.getTags());
                try {
                    r.getAttributes().put(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
                } catch (JsonProcessingException e) {
                    LOG.errorv("Could not snapshot subnet group {0} before update: {1}", id, e.getMessage());
                }
            }
            group = elastiCacheService.modifyCacheSubnetGroup(id, description, subnetIds,
                    ctx.resolveTags(props, "Tags"));
        } else {
            group = elastiCacheService.createCacheSubnetGroup(id, description, subnetIds,
                    ctx.resolveTags(props, "Tags"));
        }
        r.setPhysicalId(group.getName());
    }

    private void provisionUser(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userId = ctx.resolveOptional(props, "UserId");
        String userName = ctx.resolveOptional(props, "UserName");
        String engine = ctx.resolveOptional(props, "Engine");
        if (userId == null || userId.isBlank() || userName == null || userName.isBlank()
                || engine == null || engine.isBlank()) {
            throw new AwsException("ValidationException", "UserId, UserName and Engine are required", 400);
        }
        String accessString = ctx.resolveOptional(props, "AccessString");
        UserAuthentication auth = resolveAuthentication(props, ctx);
        if (!ctx.resolveTags(props, "Tags").isEmpty()) {
            LOG.warnv("ElastiCache user {0}: Tags are ignored, the emulator does not tag users", userId);
        }

        ElastiCacheUser user;
        if (ctx.reusesPriorEntity(userId)) {
            ElastiCacheUser existing = elastiCacheService.getUser(userId);
            if (!userName.equals(existing.getUserName())) {
                throw new AwsException("InvalidParameterValue",
                        "UserName of user " + userId + " is create-only; use a new UserId to change it", 400);
            }
            UserSnapshot snapshot = new UserSnapshot(
                    existing.getUserId(),
                    existing.getAuthMode(),
                    existing.getPasswords(),
                    existing.getAccessString(),
                    existing.getEngine());
            try {
                r.getAttributes().put(USER_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
            } catch (JsonProcessingException e) {
                LOG.errorv("Could not snapshot user {0} before update: {1}", userId, e.getMessage());
            }
            user = elastiCacheService.modifyUser(userId,
                    auth != null ? auth.mode() : null,
                    auth != null ? auth.passwords() : null,
                    accessString, null, engine);
        } else {
            AuthMode mode = auth != null ? auth.mode() : AuthMode.NO_AUTH;
            user = elastiCacheService.createUser(userId, userName, mode,
                    auth != null ? auth.passwords() : List.of(), accessString, engine);
        }
        r.setPhysicalId(user.getUserId());
        r.getAttributes().put("Arn", user.getArn());
        r.getAttributes().put("Status", user.getStatus());
    }

    private void provisionUserGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String groupId = ctx.resolveOptional(props, "UserGroupId");
        String engine = ctx.resolveOptional(props, "Engine");
        if (groupId == null || groupId.isBlank() || engine == null || engine.isBlank()) {
            throw new AwsException("ValidationException", "UserGroupId and Engine are required", 400);
        }
        if (!props.hasNonNull("UserIds")) {
            throw new AwsException("ValidationException", "UserIds is required", 400);
        }
        // The service stores the id in lowercase, so the prior physical id only matches in that form.
        groupId = groupId.toLowerCase(Locale.ROOT);
        List<String> userIds = ctx.resolveStringList(props, "UserIds");
        if (!ctx.resolveTags(props, "Tags").isEmpty()) {
            LOG.warnv("ElastiCache user group {0}: Tags are ignored, the emulator does not tag user groups", groupId);
        }

        ElastiCacheUserGroup group;
        if (ctx.reusesPriorEntity(groupId)) {
            ElastiCacheUserGroup existing = elastiCacheService.getUserGroup(groupId);
            UserGroupSnapshot snapshot = new UserGroupSnapshot(
                    existing.getUserGroupId(),
                    existing.getEngine(),
                    new ArrayList<>(existing.getUserIds()));
            try {
                r.getAttributes().put(USER_GROUP_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
            } catch (JsonProcessingException e) {
                LOG.errorv("Could not snapshot user group {0} before update: {1}", groupId, e.getMessage());
            }
            group = elastiCacheService.setUserGroupMembers(groupId, userIds, engine);
        } else {
            group = elastiCacheService.createUserGroup(groupId, engine, userIds, ctx.region());
        }
        r.setPhysicalId(group.getUserGroupId());
        r.getAttributes().put("Arn", group.getArn());
        r.getAttributes().put("Status", group.getStatus());
    }

    private record UserAuthentication(AuthMode mode, List<String> passwords) {}

    /**
     * AuthenticationMode and the top-level Passwords and NoPasswordRequired members select the same
     * thing in AWS; passwords given either way mean password authentication.
     */
    private UserAuthentication resolveAuthentication(JsonNode props, ProvisionContext ctx) {
        JsonNode mode = props.has("AuthenticationMode")
                ? ctx.engine().resolveNode(props.get("AuthenticationMode")) : null;
        String type = mode != null && mode.hasNonNull("Type") ? mode.get("Type").asText() : null;
        List<String> passwords = mode != null && mode.has("Passwords")
                ? ctx.engine().resolveStringList(mode.get("Passwords")) : List.of();
        if (passwords.isEmpty()) {
            passwords = ctx.resolveStringList(props, "Passwords");
        }
        boolean noPasswordRequired = Boolean.parseBoolean(ctx.resolveOptional(props, "NoPasswordRequired"));

        if (type == null || type.isBlank()) {
            if (noPasswordRequired && !passwords.isEmpty()) {
                throw new AwsException("InvalidParameterCombination",
                        "NoPasswordRequired cannot be true when Passwords are provided.", 400);
            }
            if (!passwords.isEmpty()) {
                return new UserAuthentication(AuthMode.PASSWORD, passwords);
            }
            return noPasswordRequired ? new UserAuthentication(AuthMode.NO_AUTH, List.of()) : null;
        }

        String normalizedType = type.toLowerCase(Locale.ROOT);
        if (noPasswordRequired && !"no-password-required".equals(normalizedType)) {
            throw new AwsException("InvalidParameterCombination",
                    "NoPasswordRequired cannot be true when AuthenticationMode.Type is " + type + ".", 400);
        }
        return switch (normalizedType) {
            case "password" -> {
                if (passwords.isEmpty()) {
                    throw new AwsException("InvalidParameterValue",
                            "Passwords are required when AuthenticationMode.Type is password.", 400);
                }
                yield new UserAuthentication(AuthMode.PASSWORD, passwords);
            }
            case "iam", "no-password-required" -> {
                if (!passwords.isEmpty()) {
                    throw new AwsException("InvalidParameterCombination",
                            "Passwords cannot be provided when AuthenticationMode.Type is " + type + ".", 400);
                }
                yield new UserAuthentication("iam".equals(normalizedType) ? AuthMode.IAM : AuthMode.NO_AUTH, List.of());
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Invalid AuthenticationMode.Type " + type + ". Valid values are password, no-password-required and iam.", 400);
        };
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (resourceType == null || physicalId == null) {
            return;
        }
        switch (resourceType) {
            case CACHE_CLUSTER -> CfnDeletes.safeDelete("ElastiCache cache cluster", physicalId, () -> {
                if (elastiCacheService.findCacheClusters(physicalId).isEmpty()) {
                    memcachedService.deleteCacheCluster(physicalId);
                } else {
                    elastiCacheService.deleteCacheCluster(physicalId);
                }
            }, "CacheClusterNotFound");
            case SUBNET_GROUP -> CfnDeletes.safeDelete("ElastiCache subnet group", physicalId,
                    () -> elastiCacheService.deleteCacheSubnetGroup(physicalId), "CacheSubnetGroupNotFoundFault");
            case USER_GROUP -> CfnDeletes.safeDelete("ElastiCache user group", physicalId,
                    () -> elastiCacheService.deleteUserGroup(physicalId), "UserGroupNotFound");
            case USER -> CfnDeletes.safeDelete("ElastiCache user", physicalId,
                    () -> elastiCacheService.deleteUser(physicalId), "UserNotFoundFault");
            default -> {
            }
        }
    }

    private static Integer optionalInt(String raw) {
        return raw == null || raw.isBlank() ? null : Integer.valueOf(raw);
    }

    private record SubnetGroupSnapshot(String name, String description, List<String> subnetIds,
                                       Map<String, String> tags) {}

    private record UserSnapshot(String userId, AuthMode authMode, List<String> passwords,
                                String accessString, String engine) {}

    private record UserGroupSnapshot(String userGroupId, String engine, List<String> userIds) {}
}
