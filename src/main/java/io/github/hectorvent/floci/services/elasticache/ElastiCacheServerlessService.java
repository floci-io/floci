package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.CacheUsageLimits;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.DataStorage;
import io.github.hectorvent.floci.services.elasticache.model.ServerlessCache.EcpuPerSecond;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ElastiCacheServerlessService implements Resettable {
    private final AccountAwareStorageBackend<ServerlessCache> caches;
    private final RegionResolver resolver;
    private final ElastiCacheService runtime;
    private final ElastiCacheMemcachedService memcached;
    private final ElastiCacheService groups;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheServerlessService(StorageFactory factory, RegionResolver resolver, ElastiCacheService runtime,
                                        ElastiCacheMemcachedService memcached, ElastiCacheService groups) {
        this.caches = factory.create("elasticache", "elasticache-serverless-caches.json",
                new TypeReference<Map<String, ServerlessCache>>() {});
        this.resolver = resolver;
        this.runtime = runtime;
        this.memcached = memcached;
        this.groups = groups;
    }

    public record CreateServerlessCacheRequest(String serverlessCacheName, String engine, String majorEngineVersion,
                                               String description, List<String> subnetIds, List<String> securityGroupIds,
                                               String userGroupId, String kmsKeyId, CacheUsageLimits cacheUsageLimits,
                                               Integer snapshotRetentionLimit, String dailySnapshotTime,
                                               List<String> snapshotArnsToRestore, Map<String, String> tags) {}

    public record ModifyServerlessCacheRequest(String serverlessCacheName, String description, String engine,
                                               String majorEngineVersion, List<String> securityGroupIds,
                                               String userGroupId, Boolean removeUserGroup, CacheUsageLimits cacheUsageLimits,
                                               Integer snapshotRetentionLimit, String dailySnapshotTime) {}

    public ServerlessCache createServerlessCache(CreateServerlessCacheRequest request) {
        String name = normalizedName(request.serverlessCacheName());
        String engine = normalizedEngine(request.engine());
        String major = request.majorEngineVersion() == null ? defaultMajor(engine) : request.majorEngineVersion();
        if (!defaultMajor(engine).equals(major)) {
            throw invalid("Only engine version " + defaultMajor(engine) + " is supported for " + engine + ".");
        }
        validateMetadata(request.description(), request.cacheUsageLimits(), request.snapshotRetentionLimit(),
                request.dailySnapshotTime());
        if (request.snapshotArnsToRestore() != null && !request.snapshotArnsToRestore().isEmpty()) {
            throw invalid("Snapshot restore is not supported locally.");
        }
        if (request.tags() != null && request.tags().size() > 50) {
            throw new AwsException("TagQuotaPerResourceExceeded", "At most 50 tags are supported.", 400);
        }
        String rawGroup = request.userGroupId();
        String group = normalizedUserGroupId(rawGroup);
        String region = resolver.getRegion();
        validateGroup(group, engine, region);
        runtime.validateServerlessDependencies(request.subnetIds(), request.securityGroupIds(), request.kmsKeyId(), region);
        synchronized (lockFor(name)) {
            if (caches.get(key(name)).isPresent()) {
                throw new AwsException("ServerlessCacheAlreadyExistsFault", "Serverless cache " + name + " already exists.", 400);
            }
            boolean attached = false;
            CacheCluster backing = null;
            try {
                if (group != null) {
                    groups.attachServerlessCache(group, key(name), engine);
                    attached = true;
                }
                String runtimeId = "serverless-" + UUID.randomUUID();
                ElastiCacheService.CreateCacheClusterRequest backingRequest = new ElastiCacheService.CreateCacheClusterRequest(
                        runtimeId, engine, null, null, 1, null, AuthMode.NO_AUTH, null, null, null,
                        0, null, null, null, null, null, null, false, region, Map.of());
                String account = resolver.getAccountId();
                backing = "memcached".equals(engine)
                        ? memcached.createServerlessBacking(backingRequest, name, account)
                        : runtime.createServerlessBacking(backingRequest, name, account, group);
                ServerlessCache cache = new ServerlessCache();
                cache.setServerlessCacheName(name);
                cache.setEngine(engine);
                cache.setMajorEngineVersion(major);
                cache.setFullEngineVersion(backing.getEngineVersion());
                cache.setDescription(request.description() == null ? "" : request.description());
                cache.setStatus("available");
                cache.setCreateTime(Instant.now());
                cache.setArn(resolver.buildArn("elasticache", region, "serverlesscache:" + name));
                cache.setEndpoint(backing.getConfigurationEndpoint());
                cache.setReaderEndpoint(backing.getConfigurationEndpoint());
                cache.setRegion(region);
                cache.setAccountId(account);
                cache.setBackingCacheClusterId(runtimeId);
                cache.setSubnetIds(request.subnetIds());
                cache.setSecurityGroupIds(request.securityGroupIds());
                cache.setUserGroupId(group);
                cache.setKmsKeyId(request.kmsKeyId());
                cache.setCacheUsageLimits(request.cacheUsageLimits());
                cache.setSnapshotRetentionLimit(request.snapshotRetentionLimit() == null ? 0 : request.snapshotRetentionLimit());
                cache.setDailySnapshotTime(request.dailySnapshotTime());
                cache.setTags(request.tags());
                caches.put(key(name), cache);
                return copy(cache);
            } catch (RuntimeException exception) {
                // Each step runs on its own so a failed backing delete never leaves the group attached.
                if (backing != null) {
                    try {
                        deleteBacking(engine, backing.getCacheClusterId());
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                if (attached) {
                    try {
                        groups.detachServerlessCache(group, key(name));
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                throw exception;
            }
        }
    }

    public ServerlessCache getServerlessCache(String name) {
        String normalized = normalizedName(name);
        synchronized (lockFor(normalized)) {
            return current(requireCache(normalized));
        }
    }

    public List<ServerlessCache> describeServerlessCaches(String name) {
        if (name != null && !name.isBlank()) {
            return List.of(getServerlessCache(name));
        }
        return caches.scan(key -> key.startsWith(resolver.getRegion() + "/")).stream()
                .map(cache -> getServerlessCache(cache.getServerlessCacheName())).toList();
    }

    public ServerlessCache modifyServerlessCache(ModifyServerlessCacheRequest request) {
        String name = normalizedName(request.serverlessCacheName());
        validateMetadata(request.description(), request.cacheUsageLimits(), request.snapshotRetentionLimit(),
                request.dailySnapshotTime());
        if (Boolean.TRUE.equals(request.removeUserGroup()) && request.userGroupId() != null) {
            throw new AwsException("InvalidParameterCombination", "UserGroupId and RemoveUserGroup conflict.", 400);
        }
        synchronized (lockFor(name)) {
            ServerlessCache previous = requireCache(name);
            if (request.securityGroupIds() != null) {
                runtime.validateServerlessDependencies(previous.getSubnetIds(), request.securityGroupIds(), null, previous.getRegion());
            }
            if (request.engine() != null && !previous.getEngine().equals(normalizedEngine(request.engine()))
                    || request.majorEngineVersion() != null && !previous.getMajorEngineVersion().equals(request.majorEngineVersion())) {
                throw invalid("Changing engine or engine version is not supported locally.");
            }
            ServerlessCache updated = copy(previous);
            String group = Boolean.TRUE.equals(request.removeUserGroup()) ? null
                    : request.userGroupId() == null ? previous.getUserGroupId() : normalizedUserGroupId(request.userGroupId());
            validateGroup(group, previous.getEngine(), previous.getRegion());
            boolean changedGroup = !Objects.equals(group, previous.getUserGroupId());
            if (changedGroup && group != null) {
                groups.attachServerlessCache(group, key(name), previous.getEngine());
            }
            try {
                if (changedGroup && !"memcached".equals(previous.getEngine())) {
                    runtime.updateServerlessUserGroup(previous.getBackingCacheClusterId(), previous.getAccountId(), group);
                }
                updated.setUserGroupId(group);
                if (request.description() != null) {
                    updated.setDescription(request.description());
                }
                if (request.securityGroupIds() != null) {
                    updated.setSecurityGroupIds(request.securityGroupIds());
                }
                if (request.cacheUsageLimits() != null) {
                    updated.setCacheUsageLimits(request.cacheUsageLimits());
                }
                if (request.snapshotRetentionLimit() != null) {
                    updated.setSnapshotRetentionLimit(request.snapshotRetentionLimit());
                }
                if (request.dailySnapshotTime() != null) {
                    updated.setDailySnapshotTime(request.dailySnapshotTime());
                }
                caches.put(key(name), updated);
            } catch (RuntimeException exception) {
                if (changedGroup) {
                    try {
                        runtime.updateServerlessUserGroup(previous.getBackingCacheClusterId(), previous.getAccountId(), previous.getUserGroupId());
                        if (group != null) {
                            groups.detachServerlessCache(group, key(name));
                        }
                    } catch (RuntimeException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                }
                throw exception;
            }
            if (changedGroup && previous.getUserGroupId() != null) {
                groups.detachServerlessCache(previous.getUserGroupId(), key(name));
            }
            return current(updated);
        }
    }

    /** Drives the tags to exactly the given set, the way CloudFormation reconciles a resource on update. */
    public ServerlessCache replaceTags(String name, Map<String, String> tags) {
        if (tags != null && tags.size() > 50) {
            throw new AwsException("TagQuotaPerResourceExceeded", "At most 50 tags are supported.", 400);
        }
        String normalized = normalizedName(name);
        synchronized (lockFor(normalized)) {
            ServerlessCache updated = copy(requireCache(normalized));
            updated.setTags(tags);
            caches.put(key(normalized), updated);
            return current(updated);
        }
    }

    public ServerlessCache deleteServerlessCache(String name) {
        return deleteServerlessCache(name, null);
    }

    public ServerlessCache deleteServerlessCache(String name, String finalSnapshotName) {
        if (finalSnapshotName != null) {
            throw invalid("Final snapshots are not supported locally.");
        }
        String normalized = normalizedName(name);
        synchronized (lockFor(normalized)) {
            ServerlessCache cache = requireCache(normalized);
            deleteBacking(cache.getEngine(), cache.getBackingCacheClusterId());
            if (cache.getUserGroupId() != null) {
                groups.detachServerlessCache(cache.getUserGroupId(), key(normalized));
            }
            caches.delete(key(normalized));
            ServerlessCache deleted = copy(cache);
            deleted.setStatus("deleting");
            return deleted;
        }
    }

    private void deleteBacking(String engine, String id) {
        if ("memcached".equals(engine)) {
            memcached.deleteServerlessBacking(id);
        } else {
            runtime.deleteServerlessBacking(id);
        }
    }

    private ServerlessCache current(ServerlessCache cache) {
        ServerlessCache snapshot = copy(cache);
        CacheCluster backing = "memcached".equals(cache.getEngine())
                ? memcached.getServerlessBacking(cache.getBackingCacheClusterId(), cache.getAccountId())
                : runtime.getServerlessBacking(cache.getBackingCacheClusterId(), cache.getAccountId());
        snapshot.setEndpoint(backing.getConfigurationEndpoint());
        snapshot.setReaderEndpoint(backing.getConfigurationEndpoint());
        snapshot.setStatus(backing.getCacheClusterStatus().wireName());
        return snapshot;
    }

    private ServerlessCache requireCache(String name) {
        return caches.get(key(name)).orElseThrow(() -> new AwsException("ServerlessCacheNotFoundFault",
                "Serverless cache " + name + " not found.", 404));
    }

    private String key(String name) {
        return resolver.getRegion() + "/" + name;
    }

    private Object lockFor(String name) {
        return locks.computeIfAbsent(resolver.getAccountId() + "/" + key(name), key -> new Object());
    }

    private void validateGroup(String group, String engine, String region) {
        if (group == null) {
            return;
        }
        if ("memcached".equals(engine)) {
            throw new AwsException("InvalidParameterCombination", "Memcached does not support user groups.", 400);
        }
        groups.validateServerlessAssociation(group, engine);
    }

    private static String normalizedName(String name) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9-]{0,39}") || name.endsWith("-") || name.contains("--")) {
            throw invalid("ServerlessCacheName must contain 1 to 40 letters, digits and hyphens and begin with a letter.");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String normalizedUserGroupId(String userGroupId) {
        return userGroupId == null ? null : userGroupId.toLowerCase(Locale.ROOT);
    }

    private static String normalizedEngine(String engine) {
        if (engine == null || !List.of("valkey", "redis", "memcached").contains(engine.toLowerCase(Locale.ROOT))) {
            throw invalid("Engine must be valkey, redis or memcached.");
        }
        return engine.toLowerCase(Locale.ROOT);
    }

    private static String defaultMajor(String engine) {
        return switch (engine) {
            case "valkey" -> "8";
            case "redis" -> "7";
            default -> "1.6";
        };
    }

    private static void validateMetadata(String description, CacheUsageLimits limits, Integer retention, String time) {
        if (description != null && description.length() > 255) {
            throw invalid("Description cannot exceed 255 characters.");
        }
        if (retention != null && (retention < 0 || retention > 35)) {
            throw invalid("SnapshotRetentionLimit must be between 0 and 35.");
        }
        if (time != null && !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw invalid("DailySnapshotTime must be HH:mm.");
        }
        if (limits != null) {
            DataStorage storage = limits.dataStorage();
            if (storage != null) {
                if (!"GB".equals(storage.unit())) {
                    throw invalid("DataStorage.Unit must be GB.");
                }
                validateBounds(storage.minimum(), storage.maximum());
            }
            EcpuPerSecond ecpu = limits.ecpuPerSecond();
            if (ecpu != null) {
                validateBounds(ecpu.minimum(), ecpu.maximum());
            }
        }
    }

    private static void validateBounds(Integer minimum, Integer maximum) {
        if (minimum != null && minimum < 0 || maximum != null && maximum < 1
                || minimum != null && maximum != null && minimum > maximum) {
            throw invalid("Usage limits must be nonnegative and minimum cannot exceed maximum.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }

    @Override
    public void clear() {
        caches.clear();
    }

    private static ServerlessCache copy(ServerlessCache cache) {
        ServerlessCache copy = new ServerlessCache();
        copy.setServerlessCacheName(cache.getServerlessCacheName());
        copy.setEngine(cache.getEngine());
        copy.setMajorEngineVersion(cache.getMajorEngineVersion());
        copy.setFullEngineVersion(cache.getFullEngineVersion());
        copy.setDescription(cache.getDescription());
        copy.setStatus(cache.getStatus());
        copy.setArn(cache.getArn());
        copy.setCreateTime(cache.getCreateTime());
        copy.setEndpoint(cache.getEndpoint());
        copy.setReaderEndpoint(cache.getReaderEndpoint());
        copy.setRegion(cache.getRegion());
        copy.setAccountId(cache.getAccountId());
        copy.setBackingCacheClusterId(cache.getBackingCacheClusterId());
        copy.setUserGroupId(cache.getUserGroupId());
        copy.setKmsKeyId(cache.getKmsKeyId());
        copy.setSnapshotRetentionLimit(cache.getSnapshotRetentionLimit());
        copy.setDailySnapshotTime(cache.getDailySnapshotTime());
        copy.setCacheUsageLimits(cache.getCacheUsageLimits());
        copy.setSubnetIds(cache.getSubnetIds());
        copy.setSecurityGroupIds(cache.getSecurityGroupIds());
        copy.setTags(cache.getTags());
        return copy;
    }

}
