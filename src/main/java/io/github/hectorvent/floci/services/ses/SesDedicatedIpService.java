package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    @Inject
    public SesDedicatedIpService(StorageFactory storageFactory) {
        this.dedicatedIpPoolStore = storageFactory.create("ses", "ses-dedicated-ip-pools.json",
                new TypeReference<Map<String, DedicatedIpPool>>() {});
    }

    SesDedicatedIpService(StorageBackend<String, DedicatedIpPool> dedicatedIpPoolStore) {
        this.dedicatedIpPoolStore = dedicatedIpPoolStore;
    }

    public DedicatedIpPool createDedicatedIpPool(String poolName, String scalingMode, String region) {
        if (poolName == null || poolName.isBlank()) {
            throw new AwsException("BadRequestException", "PoolName is required.", 400);
        }
        String effectiveScaling = (scalingMode == null || scalingMode.isBlank()) ? "STANDARD" : scalingMode;
        if (!SCALING_MODES.contains(effectiveScaling)) {
            throw new AwsException("BadRequestException", "The ScalingMode parameter is invalid.", 400);
        }
        String key = dedicatedIpPoolKey(region, poolName);
        if (dedicatedIpPoolStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "The pool <" + poolName + "> already exists.", 400);
        }
        DedicatedIpPool pool = new DedicatedIpPool(poolName, effectiveScaling);
        dedicatedIpPoolStore.put(key, pool);
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
        if (dedicatedIpPoolStore.get(key).isEmpty()) {
            throw new AwsException("NotFoundException",
                    "The requested pool <" + poolName + "> does not exist.", 404);
        }
        dedicatedIpPoolStore.delete(key);
        LOG.infov("Deleted SES dedicated IP pool: {0} in region {1}", poolName, region);
    }

    private static String dedicatedIpPoolKey(String region, String name) {
        return "dedicatedIpPool::" + region + "::" + name;
    }
}
