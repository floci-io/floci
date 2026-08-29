package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated IP pools (the {@code dedicatedIpPoolStore}), extracted from {@link SesService} as part of
 * the store-based domain split. A clean leaf reached through the {@code SesService}
 * facade, which delegates here; the facade's configuration-set delivery-options validation also
 * checks pool existence through {@link #dedicatedIpPoolExists}.
 */
@ApplicationScoped
public class SesDedicatedIpService {

    private static final Logger LOG = Logger.getLogger(SesDedicatedIpService.class);

    private static final Set<String> SCALING_MODES = Set.of("STANDARD", "MANAGED");

    private final StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore;
    // Serializes pool check-then-write mutations (create/delete/tag) so concurrent creates for the
    // same name can't both succeed and tagging can't resurrect a concurrently deleted pool.
    private final Object poolMutationLock = new Object();

    @Inject
    public SesDedicatedIpService(StorageFactory storageFactory) {
        this.dedicatedIpPoolStore = storageFactory.create("ses", "ses-dedicated-ip-pools.json",
                new TypeReference<Map<String, DedicatedIpPool>>() {});
    }

    SesDedicatedIpService(StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore) {
        this.dedicatedIpPoolStore = dedicatedIpPoolStore;
    }

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, List<Tag> tags,
                                                 String region) {
        if (poolName == null || poolName.isBlank()) {
            throw new AwsException("BadRequestException", "PoolName is required.", 400);
        }
        String effectiveScaling = (scalingMode == null || scalingMode.isBlank()) ? "STANDARD" : scalingMode;
        if (!SCALING_MODES.contains(effectiveScaling)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        SesTags.validate(tags);
        String key = dedicatedIpPoolKey(region, poolName);
        DedicatedIpPool pool = new DedicatedIpPool(poolName, effectiveScaling);
        pool.setTags(tags);
        synchronized (poolMutationLock) {
            if (dedicatedIpPoolStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException",
                        "The pool <" + poolName + "> already exists.", 400);
            }
            dedicatedIpPoolStore.put(key, pool);
        }
        LOG.infov("Created SES dedicated IP pool: {0} in region {1}", poolName, region);
        return pool;
    }

    public DedicatedIpPool getDedicatedIpPool(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "The requested pool <" + poolName + "> does not exist.", 404));
    }

    public boolean dedicatedIpPoolExists(String poolName, String region) {
        return dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName)).isPresent();
    }

    public List<String> listDedicatedIpPools(String region) {
        String prefix = "dedicatedIpPool::" + region + "::";
        return dedicatedIpPoolStore.scan(k -> k.startsWith(prefix)).stream()
                .map(DedicatedIpPool::getPoolName)
                .sorted()
                .toList();
    }

    public void deleteDedicatedIpPool(String poolName, String region) {
        String key = dedicatedIpPoolKey(region, poolName);
        synchronized (poolMutationLock) {
            if (dedicatedIpPoolStore.get(key).isEmpty()) {
                throw new AwsException("NotFoundException",
                        "The requested pool <" + poolName + "> does not exist.", 404);
            }
            dedicatedIpPoolStore.delete(key);
        }
        LOG.infov("Deleted SES dedicated IP pool: {0} in region {1}", poolName, region);
    }

    public List<Tag> listTags(String poolName, String region) {
        DedicatedIpPool pool = dedicatedIpPoolStore.get(dedicatedIpPoolKey(region, poolName))
                .orElseThrow(() -> tagTargetNotFound(poolName));
        return new ArrayList<>(pool.getTags());
    }

    /**
     * Merges the incoming tags into the stored pool. The lookup and write share the mutation lock
     * so tagging can't resurrect a concurrently deleted pool or lose a concurrent tag update.
     */
    public void tag(String poolName, String region, List<Tag> newTags) {
        String key = dedicatedIpPoolKey(region, poolName);
        synchronized (poolMutationLock) {
            DedicatedIpPool pool = dedicatedIpPoolStore.get(key)
                    .orElseThrow(() -> tagTargetNotFound(poolName));
            pool.setTags(SesTags.merge(pool.getTags(), newTags));
            dedicatedIpPoolStore.put(key, pool);
        }
        LOG.infov("Tagged SES dedicated IP pool: {0} in region {1} (+{2} tags)", poolName, region, newTags.size());
    }

    public void untag(String poolName, String region, List<String> tagKeys) {
        String key = dedicatedIpPoolKey(region, poolName);
        synchronized (poolMutationLock) {
            DedicatedIpPool pool = dedicatedIpPoolStore.get(key)
                    .orElseThrow(() -> tagTargetNotFound(poolName));
            Set<String> toRemove = new HashSet<>(tagKeys);
            // Copy-on-write: the stored list may be immutable, and unlocked readers iterate it.
            List<Tag> remaining = new ArrayList<>(pool.getTags());
            remaining.removeIf(t -> toRemove.contains(t.key()));
            pool.setTags(remaining);
            dedicatedIpPoolStore.put(key, pool);
        }
        LOG.infov("Untagged SES dedicated IP pool: {0} in region {1} (-{2} keys)", poolName, region, tagKeys.size());
    }

    private static AwsException tagTargetNotFound(String poolName) {
        // The tag endpoints use AWS's "No DedicatedIpPool present with name" wording
        // (probe-confirmed), unlike the CRUD "The requested pool <X> does not exist."
        return new AwsException("NotFoundException",
                "No DedicatedIpPool present with name: " + poolName, 404);
    }

    private static String dedicatedIpPoolKey(String region, String name) {
        return "dedicatedIpPool::" + region + "::" + name;
    }
}
