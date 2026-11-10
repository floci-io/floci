package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.elasticache.ElastiCacheServerlessService;
import io.floci.aws.services.elasticache.model.Endpoint;
import io.floci.aws.services.elasticache.model.ServerlessCache;
import io.floci.aws.services.elasticache.model.ServerlessCache.CacheUsageLimits;
import io.floci.aws.services.elasticache.model.ServerlessCache.DataStorage;
import io.floci.aws.services.elasticache.model.ServerlessCache.EcpuPerSecond;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Provisions AWS::ElastiCache::ServerlessCache. Injects only ElastiCacheServerlessService. */
@ApplicationScoped
public class ElastiCacheServerlessCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVERLESS_CACHE = "AWS::ElastiCache::ServerlessCache";
    private static final String CREATE_ONLY_ATTR = "__FlociCreateOnly";

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
            cache = modify(r, name, props, ctx);
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
        r.getAttributes().put(CREATE_ONLY_ATTR, createOnlyRecord(props, ctx).toString());
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

    private ServerlessCache modify(StackResource r, String name, JsonNode props, ProvisionContext ctx) {
        ServerlessCache existing = serverlessService.getServerlessCache(name);
        if (createOnlyPropertiesChanged(r, props, ctx)) {
            throw CfnExceptions.customNamedReplacement(name);
        }
        snapshotBeforeUpdate(r, existing);
        List<String> securityGroupIds = ctx.resolveStringList(props, "SecurityGroupIds");
        boolean hadSecurityGroups = existing.getSecurityGroupIds() != null && !existing.getSecurityGroupIds().isEmpty();
        // A null list means leave unchanged, so clearing the groups needs an explicit empty one.
        List<String> desiredSecurityGroups = !securityGroupIds.isEmpty() ? securityGroupIds
                : hadSecurityGroups ? List.of() : null;
        String description = ctx.resolveOptional(props, "Description");
        String userGroupId = ctx.resolveOptional(props, "UserGroupId");
        boolean removeUserGroup = userGroupId == null && existing.getUserGroupId() != null;
        try {
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
                    ctx.resolveOptional(props, "DailySnapshotTime"),
                    true));
            return serverlessService.replaceTags(name, ctx.resolveTags(props, "Tags"));
        } catch (RuntimeException failure) {
            // The stack skips rollbackUpdate for the resource whose own update failed, and the tag step
            // runs after the settings were saved, so the settings are put back here.
            try {
                restoreSnapshot(r);
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    /** Keeps what an in-place update changes, so a failed stack update can put it back. */
    private static void snapshotBeforeUpdate(StackResource r, ServerlessCache existing) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("name", existing.getServerlessCacheName());
        snapshot.put("description", existing.getDescription() == null ? "" : existing.getDescription());
        snapshot.set("securityGroupIds", MAPPER.valueToTree(
                existing.getSecurityGroupIds() == null ? List.of() : existing.getSecurityGroupIds()));
        if (existing.getUserGroupId() != null) {
            snapshot.put("userGroupId", existing.getUserGroupId());
        }
        if (existing.getCacheUsageLimits() != null) {
            snapshot.set("cacheUsageLimits", MAPPER.valueToTree(existing.getCacheUsageLimits()));
        }
        snapshot.put("snapshotRetentionLimit",
                existing.getSnapshotRetentionLimit() == null ? 0 : existing.getSnapshotRetentionLimit());
        if (existing.getDailySnapshotTime() != null) {
            snapshot.put("dailySnapshotTime", existing.getDailySnapshotTime());
        }
        ObjectNode tags = snapshot.putObject("tags");
        if (existing.getTags() != null) {
            existing.getTags().forEach(tags::put);
        }
        r.getAttributes().put(CfnRollback.SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    private void restoreSnapshot(StackResource r) {
        String raw = r.getAttributes().get(CfnRollback.SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the serverless cache update snapshot for "
                    + r.getLogicalId(), e);
        }
        String name = snapshot.path("name").asText();
        List<String> securityGroupIds = new ArrayList<>();
        snapshot.path("securityGroupIds").forEach(id -> securityGroupIds.add(id.asText()));
        String userGroupId = snapshot.hasNonNull("userGroupId") ? snapshot.get("userGroupId").asText() : null;
        CacheUsageLimits limits = snapshot.hasNonNull("cacheUsageLimits")
                ? MAPPER.convertValue(snapshot.get("cacheUsageLimits"), CacheUsageLimits.class) : null;
        Map<String, String> tags = new HashMap<>();
        snapshot.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        serverlessService.modifyServerlessCache(new ElastiCacheServerlessService.ModifyServerlessCacheRequest(
                name,
                snapshot.path("description").asText(""),
                null,
                null,
                securityGroupIds,
                userGroupId,
                userGroupId == null ? Boolean.TRUE : null,
                limits,
                snapshot.path("snapshotRetentionLimit").asInt(0),
                snapshot.hasNonNull("dailySnapshotTime") ? snapshot.get("dailySnapshotTime").asText() : null,
                true));
        serverlessService.replaceTags(name, tags);
        r.getAttributes().remove(CfnRollback.SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR);
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

    private static ObjectNode createOnlyRecord(JsonNode props, ProvisionContext ctx) {
        ObjectNode record = MAPPER.createObjectNode();
        if (props == null) {
            return record;
        }
        String kms = ctx.resolveOptional(props, "KmsKeyId");
        if (kms != null && !kms.isBlank()) {
            record.put("KmsKeyId", kms.trim());
        }
        String connectionType = ctx.resolveOptional(props, "ConnectionType");
        if (connectionType != null && !connectionType.isBlank()) {
            record.put("ConnectionType", connectionType.trim());
        }
        String networkType = ctx.resolveOptional(props, "NetworkType");
        if (networkType != null && !networkType.isBlank()) {
            record.put("NetworkType", networkType.trim());
        }
        List<String> subnets = ctx.resolveStringList(props, "SubnetIds");
        if (!subnets.isEmpty()) {
            ArrayNode arr = record.putArray("SubnetIds");
            subnets.stream().sorted().forEach(arr::add);
        }
        List<String> snapshots = ctx.resolveStringList(props, "SnapshotArnsToRestore");
        if (!snapshots.isEmpty()) {
            ArrayNode arr = record.putArray("SnapshotArnsToRestore");
            snapshots.forEach(arr::add);
        }
        return record;
    }

    private static boolean createOnlyPropertiesChanged(StackResource r, JsonNode props, ProvisionContext ctx) {
        String recorded = r.getAttributes().getOrDefault(CREATE_ONLY_ATTR, "{}");
        return !recorded.equals(createOnlyRecord(props, ctx).toString());
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
        resource.getAttributes().remove(CfnRollback.SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        if (resource.getAttributes().get(CfnRollback.SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR) == null) {
            return false;
        }
        restoreSnapshot(resource);
        return true;
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
