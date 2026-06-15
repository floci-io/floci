package io.github.hectorvent.floci.services.docdb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerHandle;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DocDbService {

    private static final Logger LOG = Logger.getLogger(DocDbService.class);
    private static final String ENGINE_VERSION_DEFAULT = "5.0.0";
    private static final int MONGO_PORT = 27017;

    private final StorageBackend<String, DocDbCluster> clusters;
    private final StorageBackend<String, DocDbInstance> instances;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final DocDbContainerManager containerManager;

    @Inject
    public DocDbService(EmulatorConfig config,
                        RegionResolver regionResolver,
                        DocDbContainerManager containerManager,
                        StorageFactory storageFactory) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.clusters = storageFactory.create("docdb", "docdb-clusters.json",
                new TypeReference<Map<String, DocDbCluster>>() {});
        this.instances = storageFactory.create("docdb", "docdb-instances.json",
                new TypeReference<Map<String, DocDbInstance>>() {});
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public DocDbCluster createDbCluster(String id, String engineVersion,
                                        String masterUsername, String masterPassword,
                                        boolean iamEnabled) {
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "DocDB cluster " + id + " already exists.", 400);
        }

        String region = regionResolver.getDefaultRegion();

        DocDbCluster cluster = new DocDbCluster();
        cluster.setDbClusterIdentifier(id);
        cluster.setStatus("available");
        cluster.setEngineVersion(engineVersion != null ? engineVersion : ENGINE_VERSION_DEFAULT);
        cluster.setMasterUsername(masterUsername);
        cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        cluster.setDbClusterArn(regionResolver.buildArn("rds", region, "cluster:" + id));
        cluster.setDbClusterResourceId("cluster-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        cluster.setCreatedAt(Instant.now());
        cluster.setDbClusterMembers(new ArrayList<>());

        if (config.services().docdb().mock()) {
            LOG.infov("Creating DocDB cluster {0} in mock mode (no container)", id);
            cluster.setEndpoint("localhost");
            cluster.setReaderEndpoint("localhost");
            cluster.setPort(MONGO_PORT);
        } else {
            String image = config.services().docdb().defaultImage();
            LOG.infov("Creating DocDB cluster {0}, image={1}", id, image);
            DocDbContainerHandle handle = containerManager.start(id, image, masterUsername, masterPassword);
            cluster.setEndpoint(handle.getHost());
            cluster.setReaderEndpoint(handle.getHost());
            cluster.setPort(handle.getPort());
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
        }

        clusters.put(id, cluster);
        LOG.infov("DocDB cluster {0} created, endpoint={1}:{2}",
                id, cluster.getEndpoint(), String.valueOf(cluster.getPort()));
        return cluster;
    }

    public DocDbCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DocDB cluster " + id + " not found.", 404));
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

    public Collection<DocDbCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return clusters.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return clusters.scan(k -> true);
    }

    public DocDbCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        DocDbCluster cluster = getDbCluster(id);
        if (engineVersion != null && !engineVersion.isBlank()) {
            cluster.setEngineVersion(engineVersion);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        clusters.put(id, cluster);
        LOG.infov("DocDB cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        DocDbCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DocDB cluster " + id + " not found.", 404));

        if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "Cannot delete DocDB cluster " + id + " — it still has DB instances.", 400);
        }

        cluster.setStatus("deleting");
        clusters.put(id, cluster);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new DocDbContainerHandle(
                    cluster.getContainerId(), id,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        clusters.delete(id);
        LOG.infov("DocDB cluster {0} deleted", id);
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public DocDbInstance createDbInstance(String id, String dbClusterIdentifier,
                                          String dbInstanceClass, String engineVersion,
                                          boolean iamEnabled) {
        if (instances.get(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "DocDB instance " + id + " already exists.", 400);
        }

        DocDbCluster cluster = getDbCluster(dbClusterIdentifier);
        String region = regionResolver.getDefaultRegion();

        DocDbInstance instance = new DocDbInstance();
        instance.setDbInstanceIdentifier(id);
        instance.setDbClusterIdentifier(dbClusterIdentifier);
        instance.setDbInstanceClass(dbInstanceClass != null ? dbInstanceClass : "db.r5.large");
        instance.setEngineVersion(engineVersion != null ? engineVersion : cluster.getEngineVersion());
        instance.setStatus("available");
        instance.setEndpoint(cluster.getEndpoint());
        instance.setPort(cluster.getPort());
        instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        instance.setDbInstanceArn(regionResolver.buildArn("rds", region, "db:" + id));
        instance.setDbiResourceId("db-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        instance.setCreatedAt(Instant.now());

        cluster.getDbClusterMembers().add(id);
        clusters.put(dbClusterIdentifier, cluster);

        instances.put(id, instance);
        LOG.infov("DocDB instance {0} created in cluster {1}", id, dbClusterIdentifier);
        return instance;
    }

    public DocDbInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DocDB instance " + id + " not found.", 404));
    }

    public Collection<DocDbInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public DocDbInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        DocDbInstance instance = getDbInstance(id);
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        instances.put(id, instance);
        LOG.infov("DocDB instance {0} modified", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        DocDbInstance instance = instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DocDB instance " + id + " not found.", 404));

        String clusterId = instance.getDbClusterIdentifier();
        DocDbCluster cluster = clusters.get(clusterId).orElse(null);
        if (cluster != null) {
            cluster.getDbClusterMembers().remove(id);
            clusters.put(clusterId, cluster);
        }

        instances.delete(id);
        LOG.infov("DocDB instance {0} deleted", id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }
}