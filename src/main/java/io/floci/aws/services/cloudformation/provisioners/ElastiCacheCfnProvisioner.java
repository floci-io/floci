package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.elasticache.ElastiCacheMemcachedService;
import io.floci.aws.services.elasticache.ElastiCacheService;
import io.floci.aws.services.elasticache.UserAuthentication;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.CacheCluster;
import io.floci.aws.services.elasticache.model.CacheSubnetGroup;
import io.floci.aws.services.elasticache.model.ElastiCacheUser;
import io.floci.aws.services.elasticache.model.ElastiCacheUserGroup;
import io.floci.aws.services.elasticache.model.Endpoint;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import io.floci.aws.services.elasticache.model.ReplicationGroupSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provisions AWS::ElastiCache::CacheCluster, ReplicationGroup, SubnetGroup, User and UserGroup. A cache cluster needs
 * the Memcached service as well as the Redis one, because the two engines keep their clusters in
 * separate stores.
 */
@ApplicationScoped
public class ElastiCacheCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ElastiCacheCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CACHE_CLUSTER = "AWS::ElastiCache::CacheCluster";
    private static final String REPLICATION_GROUP = "AWS::ElastiCache::ReplicationGroup";
    private static final String SUBNET_GROUP = "AWS::ElastiCache::SubnetGroup";
    private static final String USER = "AWS::ElastiCache::User";
    private static final String USER_GROUP = "AWS::ElastiCache::UserGroup";
    private static final String SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR = "__FlociCacheSubnetGroupUpdateSnapshot";
    private static final String USER_UPDATE_SNAPSHOT_ATTR = "__FlociUserUpdateSnapshot";
    private static final String USER_GROUP_UPDATE_SNAPSHOT_ATTR = "__FlociUserGroupUpdateSnapshot";
    private static final List<String> UNSUPPORTED_CLUSTER_PROPERTIES = List.of("CacheSecurityGroupNames",
            "NotificationTopicArn", "AZMode", "PreferredAvailabilityZones", "LogDeliveryConfigurations",
            "SnapshotArns", "SnapshotName");
    private static final List<String> UNSUPPORTED_REPLICATION_GROUP_PROPERTIES = List.of("CacheSecurityGroupNames",
            "NotificationTopicArn", "PreferredCacheClusterAZs", "LogDeliveryConfigurations", "SnapshotArns",
            "SnapshotName", "GlobalReplicationGroupId", "NodeGroupConfiguration", "PrimaryClusterId",
            "SnapshottingClusterId", "DataTieringEnabled");
    private static final List<String> REPLICATION_GROUP_ENDPOINT_ATTRIBUTES = List.of(
            "PrimaryEndPoint.Address", "PrimaryEndPoint.Port", "ReaderEndPoint.Address", "ReaderEndPoint.Port",
            "ReadEndPoint.Addresses", "ReadEndPoint.Addresses.List", "ReadEndPoint.Ports", "ReadEndPoint.Ports.List",
            "ConfigurationEndPoint.Address", "ConfigurationEndPoint.Port");
    private static final int CLUSTER_ID_MAX_LENGTH = 50;
    private static final int REPLICATION_GROUP_ID_MAX_LENGTH = 40;
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
        return Set.of(CACHE_CLUSTER, REPLICATION_GROUP, SUBNET_GROUP, USER, USER_GROUP);
    }

    @Override
    public Map<String, Set<String>> jsonAttributes() {
        return Map.of(REPLICATION_GROUP, Set.of("ReadEndPoint.Addresses.List", "ReadEndPoint.Ports.List"));
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
        r.getAttributes().remove(USER_UPDATE_SNAPSHOT_ATTR);
        r.getAttributes().remove(USER_GROUP_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case CACHE_CLUSTER -> provisionCacheCluster(r, props, ctx);
            case REPLICATION_GROUP -> provisionReplicationGroup(r, props, ctx);
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
        // The engine's change decides, as it does for the change set's Replacement: Port, SnapshotArns,
        // SnapshotName, Engine, CacheSubnetGroupName, NetworkType and ClusterName are create-only in the
        // registry. Port is read from the template, not the live cluster: the emulator reports its own
        // host proxy port rather than the requested one. An unnamed cluster gets a fresh generated id.
        boolean createOnlyChanged = prior != null && ctx.changes(CACHE_CLUSTER, props).requiresReplacement();
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

    private static void warnUnsupportedProperties(String kind, String id, JsonNode props, List<String> names) {
        for (String name : names) {
            if (props.hasNonNull(name)) {
                LOG.warnv("ElastiCache {0} {1}: {2} is accepted and ignored", kind, id, name);
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
        warnUnsupportedProperties("cache cluster", id, props, UNSUPPORTED_CLUSTER_PROPERTIES);
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

    private void provisionReplicationGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "ReplicationGroupDescription");
        if (description == null || description.isBlank()) {
            throw new AwsException("ValidationException", "ReplicationGroupDescription is required", 400);
        }
        String explicitId = ctx.resolveOptional(props, "ReplicationGroupId");
        if (explicitId != null && explicitId.length() > REPLICATION_GROUP_ID_MAX_LENGTH) {
            throw new AwsException("ValidationException",
                    "ReplicationGroupId exceeds maximum length of " + REPLICATION_GROUP_ID_MAX_LENGTH, 400);
        }

        ReplicationGroup prior = ctx.isUpdate() ? findReplicationGroup(ctx.priorPhysicalId()) : null;
        // As for a cache cluster: a create-only change replaces the group, which needs a new id.
        boolean createOnlyChanged = prior != null && ctx.changes(REPLICATION_GROUP, props).requiresReplacement();
        if (createOnlyChanged && explicitId != null && explicitId.equals(ctx.priorPhysicalId())) {
            throw new AwsException("InvalidParameterValue", "Updating a create-only property of replication group "
                    + explicitId + " requires a new ReplicationGroupId", 400);
        }
        String id = createOnlyChanged && explicitId == null
                ? ctx.generatePhysicalName(r.getLogicalId(), REPLICATION_GROUP_ID_MAX_LENGTH, true)
                : ctx.stablePhysicalName(explicitId, r.getLogicalId(), REPLICATION_GROUP_ID_MAX_LENGTH, true);

        ReplicationGroup group;
        if (ctx.reusesPriorEntity(id)) {
            group = prior != null ? prior : findReplicationGroup(id);
            if (group == null) {
                LOG.warnv("ElastiCache replication group {0} was deleted outside the stack, recreating it", id);
                group = createReplicationGroup(id, description, props, ctx);
            } else {
                warnIgnoredReplicationGroupChanges(id, group, description, props, ctx);
            }
        } else {
            group = createReplicationGroup(id, description, props, ctx);
        }

        r.setPhysicalId(id);
        putReplicationGroupEndpointAttributes(r, group);
    }

    private static void warnIgnoredReplicationGroupChanges(String id, ReplicationGroup group, String description,
                                                           JsonNode props, ProvisionContext ctx) {
        List<String> changed = new ArrayList<>();
        if (!description.equals(group.getDescription())) {
            changed.add("ReplicationGroupDescription");
        }
        String nodeType = ctx.resolveOptional(props, "CacheNodeType");
        if (nodeType != null && !nodeType.equals(group.getCacheNodeType())) {
            changed.add("CacheNodeType");
        }
        String version = ctx.resolveOptional(props, "EngineVersion");
        if (version != null && !version.equals(group.getEngineVersion())) {
            changed.add("EngineVersion");
        }
        Integer clusters = ctx.resolveOptionalInt(props, "NumCacheClusters");
        if (clusters != null && clusters != group.getNumCacheClusters()) {
            changed.add("NumCacheClusters");
        }
        // the service stores user group ids in lowercase
        Set<String> userGroupIds = new HashSet<>();
        ctx.resolveStringList(props, "UserGroupIds").forEach(g -> userGroupIds.add(g.toLowerCase(Locale.ROOT)));
        if (!userGroupIds.equals(group.getUserGroupIds())) {
            changed.add("UserGroupIds");
        }
        if (!changed.isEmpty()) {
            LOG.warnv("ElastiCache replication group {0}: in-place modification is not supported, changes to {1} "
                    + "are ignored", id, String.join(", ", changed));
        }
    }

    private ReplicationGroup createReplicationGroup(String id, String description, JsonNode props,
                                                    ProvisionContext ctx) {
        warnUnsupportedProperties("replication group", id, props, UNSUPPORTED_REPLICATION_GROUP_PROPERTIES);
        String authToken = ctx.resolveOptional(props, "AuthToken");
        boolean hasAuthToken = authToken != null && !authToken.isBlank();
        List<String> userGroupIds = ctx.resolveStringList(props, "UserGroupIds");
        List<String> securityGroupIds = ctx.resolveStringList(props, "SecurityGroupIds");
        ReplicationGroupSettings settings = new ReplicationGroupSettings(
                ctx.resolveOptionalBoolean(props, "AtRestEncryptionEnabled"),
                ctx.resolveOptional(props, "KmsKeyId"),
                ctx.resolveOptionalInt(props, "SnapshotRetentionLimit"),
                ctx.resolveOptional(props, "SnapshotWindow"),
                ctx.resolveOptional(props, "TransitEncryptionMode"),
                ctx.resolveOptionalBoolean(props, "TransitEncryptionEnabled"));
        return elastiCacheService.createReplicationGroup(new ElastiCacheService.CreateReplicationGroupRequest(
                id,
                description,
                hasAuthToken ? AuthMode.PASSWORD : AuthMode.NO_AUTH,
                hasAuthToken ? authToken : null,
                ctx.region(),
                ctx.resolveOptional(props, "Engine"),
                ctx.resolveOptional(props, "EngineVersion"),
                ctx.resolveOptional(props, "CacheNodeType"),
                ctx.resolveOptional(props, "CacheParameterGroupName"),
                ctx.resolveOptional(props, "CacheSubnetGroupName"),
                ctx.resolveOptional(props, "ClusterMode"),
                ctx.resolveOptionalInt(props, "NumNodeGroups"),
                ctx.resolveOptionalInt(props, "ReplicasPerNodeGroup"),
                ctx.resolveOptionalInt(props, "NumCacheClusters"),
                ctx.resolveOptionalBoolean(props, "AutomaticFailoverEnabled"),
                ctx.resolveOptionalBoolean(props, "MultiAZEnabled"),
                ctx.resolveOptionalInt(props, "Port"),
                settings,
                ctx.resolveTags(props, "Tags"),
                userGroupIds.isEmpty() ? null : userGroupIds,
                securityGroupIds.isEmpty() ? null : securityGroupIds));
    }

    /**
     * Sets the endpoint attributes AWS exposes for the group's mode: a cluster-mode group answers on its
     * ConfigurationEndPoint, any other on its primary and reader endpoints and one read endpoint per node.
     */
    private void putReplicationGroupEndpointAttributes(StackResource r, ReplicationGroup group) {
        REPLICATION_GROUP_ENDPOINT_ATTRIBUTES.forEach(r.getAttributes()::remove);
        Endpoint endpoint = group.getConfigurationEndpoint();
        if (endpoint == null) {
            return;
        }
        String port = String.valueOf(endpoint.port());
        if (group.isClusterEnabled()) {
            r.getAttributes().put("ConfigurationEndPoint.Address", endpoint.address());
            r.getAttributes().put("ConfigurationEndPoint.Port", port);
            return;
        }
        r.getAttributes().put("PrimaryEndPoint.Address", endpoint.address());
        r.getAttributes().put("PrimaryEndPoint.Port", port);
        r.getAttributes().put("ReaderEndPoint.Address", endpoint.address());
        r.getAttributes().put("ReaderEndPoint.Port", port);
        List<String> addresses = new ArrayList<>();
        List<String> ports = new ArrayList<>();
        for (ElastiCacheService.MemberCacheCluster member : elastiCacheService.memberCacheClusters(group)) {
            addresses.add(endpoint.address());
            ports.add(String.valueOf(member.port()));
        }
        r.getAttributes().put("ReadEndPoint.Addresses", String.join(",", addresses));
        r.getAttributes().put("ReadEndPoint.Ports", String.join(",", ports));
        try {
            r.getAttributes().put("ReadEndPoint.Addresses.List", MAPPER.writeValueAsString(addresses));
            r.getAttributes().put("ReadEndPoint.Ports.List", MAPPER.writeValueAsString(ports));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ReplicationGroup findReplicationGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        try {
            return elastiCacheService.getReplicationGroup(groupId);
        } catch (AwsException e) {
            if ("ReplicationGroupNotFoundFault".equals(e.getErrorCode())) {
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

    private UserAuthentication resolveAuthentication(JsonNode props, ProvisionContext ctx) {
        JsonNode mode = props.has("AuthenticationMode")
                ? ctx.engine().resolveNode(props.get("AuthenticationMode")) : null;
        String type = mode != null && mode.hasNonNull("Type") ? mode.get("Type").asText() : null;
        List<String> passwords = mode != null && mode.has("Passwords")
                ? ctx.engine().resolveStringList(mode.get("Passwords")) : List.of();
        if (passwords.isEmpty()) {
            passwords = ctx.resolveStringList(props, "Passwords");
        }
        String noPasswordFlag = ctx.resolveOptional(props, "NoPasswordRequired");
        // parseBoolean reads every unrecognised value as false, which would hide a malformed template.
        if (noPasswordFlag != null && !noPasswordFlag.isBlank()
                && !"true".equalsIgnoreCase(noPasswordFlag) && !"false".equalsIgnoreCase(noPasswordFlag)) {
            throw new AwsException("ValidationException",
                    "NoPasswordRequired must be true or false, got: " + noPasswordFlag, 400);
        }
        boolean noPasswordRequired = Boolean.parseBoolean(noPasswordFlag);
        return UserAuthentication.resolve(type, passwords, noPasswordRequired);
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
            case REPLICATION_GROUP -> CfnDeletes.safeDelete("ElastiCache replication group", physicalId,
                    () -> elastiCacheService.deleteReplicationGroup(physicalId), "ReplicationGroupNotFoundFault");
            case SUBNET_GROUP -> CfnDeletes.safeDelete("ElastiCache subnet group", physicalId,
                    () -> elastiCacheService.deleteCacheSubnetGroup(physicalId), "CacheSubnetGroupNotFoundFault");
            case USER_GROUP -> CfnDeletes.safeDelete("ElastiCache user group", physicalId,
                    () -> elastiCacheService.deleteUserGroup(physicalId), "UserGroupNotFound");
            case USER -> CfnDeletes.safeDelete("ElastiCache user", physicalId,
                    () -> elastiCacheService.deleteUser(physicalId), "UserNotFound");
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
