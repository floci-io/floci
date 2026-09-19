package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ElastiCacheMemcachedService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheMemcachedService.class);
    private static final String ENGINE = "memcached";
    private static final String ENGINE_VERSION = "1.6.22";
    /** Memcached's well-known port, used as the endpoint port when no backing container exists. */
    private static final int BACKEND_PORT = 11211;

    private final StorageBackend<String, CacheCluster> clusters;
    /**
     * The two stores {@link ElastiCacheService} writes, read here for the id check alone.
     * {@link StorageFactory#create} keys backends by file path and hands back the instance that
     * service already holds, so these are those stores rather than copies: a cache cluster id is
     * one namespace whatever engine claims it.
     */
    private final StorageBackend<String, CacheCluster> redisClusters;
    private final StorageBackend<String, ReplicationGroup> groups;
    private final ElastiCacheMemcachedContainerManager containerManager;
    private final EmulatorConfig config;
    /** Shared with {@link ElastiCacheService}: one namespace, one set of in-flight claims. */
    private final ElastiCacheProvisioningIds provisioningIds;

    @Inject
    public ElastiCacheMemcachedService(ElastiCacheMemcachedContainerManager containerManager,
                                       StorageFactory storageFactory,
                                       EmulatorConfig config,
                                       ElastiCacheProvisioningIds provisioningIds) {
        this.containerManager = containerManager;
        this.config = config;
        this.provisioningIds = provisioningIds;
        this.clusters = storageFactory.create("elasticache", "elasticache-cache-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
        this.redisClusters = storageFactory.create("elasticache", "elasticache-redis-clusters.json",
                new TypeReference<Map<String, CacheCluster>>() {});
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
    }

    public CacheCluster createCacheCluster(String clusterId) {
        // Claimed before the store checks rather than after, because no create here or in
        // ElastiCacheService persists its record until its container has started: a store check
        // that passes is no promise the id is still free by the time this one writes. The claim
        // is the same set the redis paths take, so of two concurrent creates for one id only the
        // one that claims it reaches the stores at all.
        if (!provisioningIds.claim(clusterId)) {
            throw new AwsException("CacheClusterAlreadyExists",
                    "Cache cluster " + clusterId + " is already being created.", 400);
        }
        try {
            return provisionCacheCluster(clusterId);
        } finally {
            provisioningIds.release(clusterId);
        }
    }

    private CacheCluster provisionCacheCluster(String clusterId) {
        // Every store that answers DescribeCacheClusters, not just this one: two records sharing
        // an id would have one describe report it twice, each with a different engine.
        if (clusters.get(clusterId).isPresent()
                || redisClusters.get(clusterId).isPresent()
                || groups.get(clusterId).isPresent()) {
            throw new AwsException("CacheClusterAlreadyExists",
                    "Cache cluster " + clusterId + " already exists.", 400);
        }

        String image = config.services().elasticache().defaultMemcachedImage();
        LOG.infov("Creating Memcached cluster {0} with image {1}", clusterId, image);

        // A cache cluster record is metadata: its id and endpoint are derived from configuration,
        // so the cluster is created and reaches 'available' even when no Docker daemon is
        // reachable. Only connecting to the cache needs the container.
        ElastiCacheContainerHandle handle = containerManager.tryStart(clusterId, image);

        String endpointHost = resolveEndpointHost(handle);
        int endpointPort = handle != null ? handle.getPort() : BACKEND_PORT;
        Endpoint endpoint = new Endpoint(endpointHost, endpointPort);

        CacheCluster cluster = new CacheCluster(
                clusterId, CacheClusterStatus.AVAILABLE, ENGINE, ENGINE_VERSION,
                endpoint, Instant.now());
        if (handle != null) {
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
        } else {
            LOG.warnv("Memcached cluster {0} created without a backing container: no Docker daemon "
                    + "is reachable. Metadata operations work; connections to the cache do not "
                    + "until a daemon appears.", clusterId);
        }

        clusters.put(clusterId, cluster);
        LOG.infov("Memcached cluster {0} created, endpoint={1}:{2}", clusterId, endpointHost, endpointPort);
        return cluster;
    }

    public CacheCluster getCacheCluster(String clusterId) {
        return clusters.get(clusterId).orElseThrow(() ->
                new AwsException("CacheClusterNotFound",
                        "Cache cluster " + clusterId + " not found.", 404));
    }

    public Collection<CacheCluster> listCacheClusters(String filterClusterId) {
        if (filterClusterId != null && !filterClusterId.isBlank()) {
            return clusters.get(filterClusterId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("CacheClusterNotFound",
                            "Cache cluster " + filterClusterId + " not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public CacheCluster deleteCacheCluster(String clusterId) {
        CacheCluster cluster = getCacheCluster(clusterId);

        cluster.setCacheClusterStatus(CacheClusterStatus.DELETING);
        clusters.put(clusterId, cluster);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    cluster.getContainerId(), clusterId,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        clusters.delete(clusterId);
        LOG.infov("Memcached cluster {0} deleted", clusterId);
        return cluster;
    }

    private String resolveEndpointHost(ElastiCacheContainerHandle handle) {
        return config.hostname().orElse(handle != null ? handle.getHost() : "localhost");
    }
}
