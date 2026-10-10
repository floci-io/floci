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
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions AWS::ElastiCache::CacheCluster and SubnetGroup. A cache cluster needs the Memcached
 * service as well as the Redis one, because the two engines keep their clusters in separate stores.
 */
@ApplicationScoped
public class ElastiCacheCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ElastiCacheCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CACHE_CLUSTER = "AWS::ElastiCache::CacheCluster";
    private static final String SUBNET_GROUP = "AWS::ElastiCache::SubnetGroup";
    private static final String SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR = "__FlociCacheSubnetGroupUpdateSnapshot";
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
        return Set.of(CACHE_CLUSTER, SUBNET_GROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case CACHE_CLUSTER -> provisionCacheCluster(r, props, ctx);
            case SUBNET_GROUP -> provisionSubnetGroup(r, props, ctx);
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
        }
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SUBNET_GROUP_UPDATE_SNAPSHOT_ATTR);
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
            default -> {
            }
        }
    }

    private static Integer optionalInt(String raw) {
        return raw == null || raw.isBlank() ? null : Integer.valueOf(raw);
    }

    private record SubnetGroupSnapshot(String name, String description, List<String> subnetIds,
                                       Map<String, String> tags) {}
}
