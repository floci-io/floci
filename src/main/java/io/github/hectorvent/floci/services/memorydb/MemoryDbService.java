package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core MemoryDB business logic — clusters and tags.
 * Creates a real Redis-compatible container plus an auth proxy on cluster creation.
 */
@ApplicationScoped
public class MemoryDbService {

    private static final Logger LOG = Logger.getLogger(MemoryDbService.class);
    private static final String DEFAULT_ENGINE = "redis";
    private static final String DEFAULT_ACL = "open-access";
    private static final int REDIS_PORT = 6379;

    private final StorageBackend<String, Cluster> clusters;
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusters = storageFactory.create("memorydb", "memorydb-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
    }

    public Cluster createCluster(Cluster spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        if (clusters.get(name).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with specified name already exists.", 400);
        }

        AuthMode authMode = spec.getAuthMode() != null ? spec.getAuthMode() : AuthMode.NO_AUTH;

        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setDescription(spec.getDescription());
        cluster.setStatus(ClusterStatus.AVAILABLE);
        cluster.setNodeType(spec.getNodeType() != null ? spec.getNodeType() : "db.t4g.small");
        cluster.setNumberOfShards(spec.getNumberOfShards() > 0 ? spec.getNumberOfShards() : 1);
        cluster.setEngine(spec.getEngine() != null ? spec.getEngine() : DEFAULT_ENGINE);
        cluster.setEngineVersion(spec.getEngineVersion() != null ? spec.getEngineVersion() : "7.1");
        cluster.setAclName(spec.getAclName() != null ? spec.getAclName() : DEFAULT_ACL);
        cluster.setTlsEnabled(spec.isTlsEnabled());
        cluster.setAuthMode(authMode);
        cluster.setArn(buildArn(region, name));
        cluster.setCreatedAt(Instant.now());
        cluster.setAuthToken(spec.getAuthToken());
        cluster.setTags(spec.getTags());

        if (config.services().memorydb().mock()) {
            LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
            cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), REDIS_PORT));
        } else {
            startBackend(cluster, authMode);
        }

        clusters.put(name, cluster);
        LOG.infov("MemoryDB cluster {0} created, endpoint={1}:{2}",
                name, cluster.getClusterEndpoint().address(),
                String.valueOf(cluster.getClusterEndpoint().port()));
        return cluster;
    }

    public Cluster getCluster(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return clusters.get(name).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return clusters.get(filterName)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("ClusterNotFoundFault",
                            "Cluster not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public Cluster updateCluster(String name, String description) {
        Cluster cluster = getCluster(name);
        if (description != null) {
            cluster.setDescription(description);
        }
        clusters.put(name, cluster);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = getCluster(name);
        cluster.setStatus(ClusterStatus.DELETING);

        proxyManager.stopProxy(name);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new MemoryDbContainerHandle(
                    cluster.getContainerId(), name, cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(name);
        LOG.infov("MemoryDB cluster {0} deleted", name);
        return cluster;
    }

    public Map<String, String> listTags(String resourceArn) {
        return clusterByArn(resourceArn).getTags();
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        Cluster cluster = clusterByArn(resourceArn);
        cluster.getTags().putAll(tags);
        clusters.put(cluster.getName(), cluster);
        return cluster.getTags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        Cluster cluster = clusterByArn(resourceArn);
        tagKeys.forEach(cluster.getTags()::remove);
        clusters.put(cluster.getName(), cluster);
        return cluster.getTags();
    }

    /**
     * Validates a Redis AUTH password for the given cluster against its stored auth token.
     */
    public boolean validatePassword(String clusterName, String username, String password) {
        Cluster cluster = clusters.get(clusterName).orElse(null);
        if (cluster == null || cluster.getAuthToken() == null) {
            return false;
        }
        return cluster.getAuthToken().equals(password);
    }

    private Cluster clusterByArn(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidParameterValueException", "ResourceArn is required.", 400);
        }
        return clusters.scan(k -> true).stream()
                .filter(c -> resourceArn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    private void startBackend(Cluster cluster, AuthMode authMode) {
        String name = cluster.getName();
        int proxyPort = allocateProxyPort();
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authMode={1} on proxy port {2}",
                name, authMode, String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            handle = containerManager.start(name, image);
            cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), proxyPort));
            cluster.setProxyPort(proxyPort);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());

            proxyManager.startProxy(name, authMode, proxyPort,
                    handle.getHost(), handle.getPort(),
                    (username, password) -> validatePassword(name, username, password));
        } catch (RuntimeException e) {
            LOG.warnv("MemoryDB cluster {0} provisioning failed, rolling back: {1}", name, e.getMessage());
            rollbackBackend(name, handle, proxyPort);
            throw e;
        }
    }

    private void rollbackBackend(String name, MemoryDbContainerHandle handle, int proxyPort) {
        try {
            proxyManager.stopProxy(name);
            if (handle != null) {
                containerManager.stop(handle);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error rolling back MemoryDB cluster {0}: {1}", name, e.getMessage());
        } finally {
            releaseProxyPort(proxyPort);
        }
    }

    private String buildArn(String region, String name) {
        return "arn:aws:memorydb:" + region + ":" + regionResolver.getAccountId() + ":cluster/" + name;
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().memorydb().proxyBasePort();
        int max = config.services().memorydb().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacityFault",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
