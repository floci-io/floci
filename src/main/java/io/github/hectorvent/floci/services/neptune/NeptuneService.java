package io.github.hectorvent.floci.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class NeptuneService {

    private static final Logger LOG = Logger.getLogger(NeptuneService.class);
    private static final String ENGINE_VERSION_DEFAULT = "1.3.2.1";

    private final StorageBackend<String, NeptuneCluster> clusters;
    private final StorageBackend<String, NeptuneInstance> instances;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final NeptuneContainerManager containerManager;
    private final NeptuneProxyManager proxyManager;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public NeptuneService(EmulatorConfig config,
                          RegionResolver regionResolver,
                          NeptuneContainerManager containerManager,
                          NeptuneProxyManager proxyManager,
                          StorageFactory storageFactory) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.clusters = storageFactory.create("neptune", "neptune-clusters.json",
                new TypeReference<Map<String, NeptuneCluster>>() {});
        this.instances = storageFactory.create("neptune", "neptune-instances.json",
                new TypeReference<Map<String, NeptuneInstance>>() {});
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public NeptuneCluster createDbCluster(String id, String engineVersion, boolean iamEnabled) {
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "Neptune cluster " + id + " already exists.", 400);
        }

        int proxyPort = allocateProxyPort();
        String image = config.services().neptune().defaultImage();

        LOG.infov("Creating Neptune cluster {0} on proxy port {1}, image={2}", id, String.valueOf(proxyPort), image);

        NeptuneContainerHandle handle = containerManager.start(id, image);

        String region = regionResolver.getDefaultRegion();
        String endpointHost = resolveEndpointHost();

        NeptuneCluster cluster = new NeptuneCluster();
        cluster.setDbClusterIdentifier(id);
        cluster.setStatus("available");
        cluster.setEngineVersion(engineVersion != null ? engineVersion : ENGINE_VERSION_DEFAULT);
        cluster.setEndpoint(endpointHost);
        cluster.setReaderEndpoint(endpointHost);
        cluster.setPort(proxyPort);
        cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        cluster.setDbClusterArn(regionResolver.buildArn("neptune", region, "cluster:" + id));
        cluster.setDbClusterResourceId("cluster-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        cluster.setCreatedAt(Instant.now());
        cluster.setDbClusterMembers(new ArrayList<>());
        cluster.setContainerId(handle.getContainerId());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());
        cluster.setProxyPort(proxyPort);

        proxyManager.startProxy(id, proxyPort, handle.getHost(), handle.getPort());

        clusters.put(id, cluster);
        LOG.infov("Neptune cluster {0} created, Gremlin endpoint={1}:{2}", id, endpointHost, String.valueOf(proxyPort));
        return cluster;
    }

    public NeptuneCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));
    }

    public boolean hasCluster(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return clusters.get(id).isPresent();
    }

    public boolean hasInstance(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return instances.get(id).isPresent();
    }

    public Collection<NeptuneCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return clusters.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return clusters.scan(k -> true);
    }

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        NeptuneCluster cluster = getDbCluster(id);
        if (engineVersion != null && !engineVersion.isBlank()) {
            cluster.setEngineVersion(engineVersion);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        clusters.put(id, cluster);
        LOG.infov("Neptune cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        NeptuneCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));

        if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "Cannot delete Neptune cluster " + id + " — it still has DB instances.", 400);
        }

        cluster.setStatus("deleting");
        clusters.put(id, cluster);

        proxyManager.stopProxy(id);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new NeptuneContainerHandle(
                    cluster.getContainerId(), id,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(id);
        LOG.infov("Neptune cluster {0} deleted", id);
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled) {
        if (instances.get(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "Neptune instance " + id + " already exists.", 400);
        }

        NeptuneCluster cluster = getDbCluster(dbClusterIdentifier);
        String region = regionResolver.getDefaultRegion();

        NeptuneInstance instance = new NeptuneInstance();
        instance.setDbInstanceIdentifier(id);
        instance.setDbClusterIdentifier(dbClusterIdentifier);
        instance.setDbInstanceClass(dbInstanceClass != null ? dbInstanceClass : "db.r5.large");
        instance.setEngineVersion(engineVersion != null ? engineVersion : cluster.getEngineVersion());
        instance.setStatus("available");
        instance.setEndpoint(cluster.getEndpoint());
        instance.setPort(cluster.getPort());
        instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        instance.setDbInstanceArn(regionResolver.buildArn("neptune", region, "db:" + id));
        instance.setDbiResourceId("db-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        instance.setCreatedAt(Instant.now());

        cluster.getDbClusterMembers().add(id);
        clusters.put(dbClusterIdentifier, cluster);

        instances.put(id, instance);
        LOG.infov("Neptune instance {0} created in cluster {1}", id, dbClusterIdentifier);
        return instance;
    }

    public NeptuneInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));
    }

    public Collection<NeptuneInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        NeptuneInstance instance = getDbInstance(id);
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        instances.put(id, instance);
        LOG.infov("Neptune instance {0} modified", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        NeptuneInstance instance = instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));

        String clusterId = instance.getDbClusterIdentifier();
        NeptuneCluster cluster = clusters.get(clusterId).orElse(null);
        if (cluster != null) {
            cluster.getDbClusterMembers().remove(id);
            clusters.put(clusterId, cluster);
        }

        instances.delete(id);
        LOG.infov("Neptune instance {0} deleted", id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().neptune().proxyBasePort();
        int max = config.services().neptune().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientNeptuneCapacity",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
