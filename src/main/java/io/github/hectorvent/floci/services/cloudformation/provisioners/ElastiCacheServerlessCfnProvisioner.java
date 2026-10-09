package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheServerlessService;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.CacheUsageLimits;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.DataStorage;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.EcpuPerSecond;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Provisions AWS::ElastiCache::ServerlessCache. Injects only ElastiCacheServerlessService. */
@ApplicationScoped
public class ElastiCacheServerlessCfnProvisioner implements CfnResourceProvisioner {

    private static final String SERVERLESS_CACHE = "AWS::ElastiCache::ServerlessCache";

    private final ElastiCacheServerlessService serverlessService;

    @Inject
    public ElastiCacheServerlessCfnProvisioner(ElastiCacheServerlessService serverlessService) {
        this.serverlessService = serverlessService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SERVERLESS_CACHE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String name = ctx.resolveOptional(props, "ServerlessCacheName");
        String engine = ctx.resolveOptional(props, "Engine");
        if (name == null || name.isBlank() || engine == null || engine.isBlank()) {
            throw new AwsException("ValidationException", "ServerlessCacheName and Engine are required", 400);
        }
        // The service stores the name in lowercase, so the prior physical id only matches in that form.
        name = name.toLowerCase(Locale.ROOT);
        ServerlessCache cache;
        if (ctx.reusesPriorEntity(name)) {
            cache = modify(name, props, ctx);
        } else {
            cache = create(name, engine, props, ctx);
        }

        r.setPhysicalId(cache.getServerlessCacheName());
        r.getAttributes().put("ARN", cache.getArn());
        r.getAttributes().put("Status", cache.getStatus());
        if (cache.getFullEngineVersion() != null) {
            r.getAttributes().put("FullEngineVersion", cache.getFullEngineVersion());
        }
        if (cache.getCreateTime() != null) {
            r.getAttributes().put("CreateTime", cache.getCreateTime().toString());
        }
        putEndpoint(r, "Endpoint", cache.getEndpoint());
        putEndpoint(r, "ReaderEndpoint", cache.getReaderEndpoint());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private ServerlessCache create(String name, String engine, JsonNode props, ProvisionContext ctx) {
        List<String> subnetIds = ctx.resolveStringList(props, "SubnetIds");
        List<String> securityGroupIds = ctx.resolveStringList(props, "SecurityGroupIds");
        List<String> snapshotArns = ctx.resolveStringList(props, "SnapshotArnsToRestore");
        return serverlessService.createServerlessCache(new ElastiCacheServerlessService.CreateServerlessCacheRequest(
                name,
                engine,
                ctx.resolveOptional(props, "MajorEngineVersion"),
                ctx.resolveOptional(props, "Description"),
                subnetIds.isEmpty() ? null : subnetIds,
                securityGroupIds.isEmpty() ? null : securityGroupIds,
                ctx.resolveOptional(props, "UserGroupId"),
                ctx.resolveOptional(props, "KmsKeyId"),
                usageLimits(props, ctx),
                optionalInt(ctx.resolveOptional(props, "SnapshotRetentionLimit")),
                ctx.resolveOptional(props, "DailySnapshotTime"),
                snapshotArns.isEmpty() ? null : snapshotArns,
                ctx.resolveTags(props, "Tags")));
    }

    private ServerlessCache modify(String name, JsonNode props, ProvisionContext ctx) {
        ServerlessCache existing = serverlessService.getServerlessCache(name);
        Set<String> subnets = new HashSet<>(ctx.resolveStringList(props, "SubnetIds"));
        Set<String> existingSubnets = existing.getSubnetIds() == null
                ? Set.of() : new HashSet<>(existing.getSubnetIds());
        String kms = ctx.resolveOptional(props, "KmsKeyId");
        String existingKms = existing.getKmsKeyId();
        // Replacing the cache needs a new name, and the name is create-only too, so there is no way to apply this.
        if (!subnets.equals(existingSubnets)
                || !Objects.equals(kms == null || kms.isBlank() ? null : kms,
                        existingKms == null || existingKms.isBlank() ? null : existingKms)) {
            throw new AwsException("InvalidParameterValue", "Updating SubnetIds or KmsKeyId of serverless cache "
                    + name + " requires a new ServerlessCacheName", 400);
        }
        List<String> securityGroupIds = ctx.resolveStringList(props, "SecurityGroupIds");
        boolean hadSecurityGroups = existing.getSecurityGroupIds() != null && !existing.getSecurityGroupIds().isEmpty();
        // A null list means leave unchanged, so clearing the groups needs an explicit empty one.
        List<String> desiredSecurityGroups = !securityGroupIds.isEmpty() ? securityGroupIds
                : hadSecurityGroups ? List.of() : null;
        String description = ctx.resolveOptional(props, "Description");
        String userGroupId = ctx.resolveOptional(props, "UserGroupId");
        boolean removeUserGroup = userGroupId == null && existing.getUserGroupId() != null;
        serverlessService.modifyServerlessCache(new ElastiCacheServerlessService.ModifyServerlessCacheRequest(
                name,
                // A removed Description goes back to the empty one a new cache gets.
                description == null ? "" : description,
                ctx.resolveOptional(props, "Engine"),
                ctx.resolveOptional(props, "MajorEngineVersion"),
                desiredSecurityGroups,
                userGroupId,
                removeUserGroup ? Boolean.TRUE : null,
                usageLimits(props, ctx),
                optionalInt(ctx.resolveOptional(props, "SnapshotRetentionLimit")),
                ctx.resolveOptional(props, "DailySnapshotTime")));
        return serverlessService.replaceTags(name, ctx.resolveTags(props, "Tags"));
    }

    private CacheUsageLimits usageLimits(JsonNode props, ProvisionContext ctx) {
        if (!props.hasNonNull("CacheUsageLimits")) {
            return null;
        }
        JsonNode limits = ctx.engine().resolveNode(props.get("CacheUsageLimits"));
        JsonNode storage = limits.path("DataStorage");
        JsonNode ecpu = limits.path("ECPUPerSecond");
        DataStorage dataStorage = storage.isObject()
                ? new DataStorage(intOrNull(storage, "Minimum"), intOrNull(storage, "Maximum"),
                        storage.hasNonNull("Unit") ? storage.get("Unit").asText() : null)
                : null;
        EcpuPerSecond ecpuPerSecond = ecpu.isObject()
                ? new EcpuPerSecond(intOrNull(ecpu, "Minimum"), intOrNull(ecpu, "Maximum"))
                : null;
        return new CacheUsageLimits(dataStorage, ecpuPerSecond);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? Integer.valueOf(node.get(field).asInt()) : null;
    }

    private static Integer optionalInt(String raw) {
        return raw == null || raw.isBlank() ? null : Integer.valueOf(raw);
    }

    private void putEndpoint(StackResource r, String prefix, Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        r.getAttributes().put(prefix + ".Address", endpoint.address());
        r.getAttributes().put(prefix + ".Port", String.valueOf(endpoint.port()));
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null) {
            return;
        }
        CfnDeletes.safeDelete("ElastiCache serverless cache", physicalId,
                () -> serverlessService.deleteServerlessCache(physicalId), "ServerlessCacheNotFoundFault");
    }
}
