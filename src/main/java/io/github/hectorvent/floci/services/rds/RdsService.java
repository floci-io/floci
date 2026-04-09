package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core RDS business logic — DB instances, clusters, and parameter groups.
 * Starts DB containers and auth proxies on creation.
 */
@ApplicationScoped
public class RdsService {

    private static final Logger LOG = Logger.getLogger(RdsService.class);

    private final StorageBackend<String, DbInstance> instances;
    private final StorageBackend<String, DbCluster> clusters;
    private final StorageBackend<String, DbParameterGroup> parameterGroups;
    private final RdsContainerManager containerManager;
    private final RdsProxyManager proxyManager;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public RdsService(RdsContainerManager containerManager,
                      RdsProxyManager proxyManager,
                      RegionResolver regionResolver,
                      EmulatorConfig config) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.regionResolver = regionResolver;
        this.config = config;
        this.instances = new InMemoryStorage<>();
        this.clusters = new InMemoryStorage<>();
        this.parameterGroups = new InMemoryStorage<>();
    }

    // ── DB Instances ──────────────────────────────────────────────────────────

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbClusterIdentifier) {
        if (instances.get(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "DB instance " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        int proxyPort = allocateProxyPort();

        String backendHost;
        int backendPort;
        String containerId = null;
        String containerHost = null;
        int containerPort = 0;

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            // Cluster member — share the cluster's container
            DbCluster cluster = clusters.get(dbClusterIdentifier).orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault",
                            "DB cluster " + dbClusterIdentifier + " not found.", 404));
            backendHost = cluster.getContainerHost();
            backendPort = cluster.getContainerPort();
            containerId = cluster.getContainerId();
            containerHost = cluster.getContainerHost();
            containerPort = cluster.getContainerPort();
        } else {
            // Standalone instance — start its own container
            String image = imageForEngine(engine);
            RdsContainerHandle handle = containerManager.start(id, engine, image, masterUsername, masterPassword, dbName);
            backendHost = handle.getHost();
            backendPort = handle.getPort();
            containerId = handle.getContainerId();
            containerHost = handle.getHost();
            containerPort = handle.getPort();
        }

        DbEndpoint endpoint = new DbEndpoint("localhost", proxyPort);
        DbInstance instance = new DbInstance(id, engine, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, DbInstanceStatus.AVAILABLE,
                endpoint, iamEnabled, paramGroupName, dbClusterIdentifier, Instant.now(), proxyPort);
        instance.setContainerId(containerId);
        instance.setContainerHost(containerHost);
        instance.setContainerPort(containerPort);

        String region = regionResolver.getDefaultRegion();
        instance.setDbiResourceId("db-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        instance.setDbInstanceArn(regionResolver.buildArn("rds", region, "db:" + id));

        String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
        proxyManager.startProxy(id, engine, iamEnabled, proxyPort, backendHost, backendPort,
                effectiveMasterUser, masterPassword, dbName,
                (user, pw) -> validateDbPassword(id, user, pw));

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            DbCluster cluster = clusters.get(dbClusterIdentifier).orElse(null);
            if (cluster != null) {
                cluster.getDbClusterMembers().add(id);
                clusters.put(dbClusterIdentifier, cluster);
            }
        }

        instances.put(id, instance);
        LOG.infov("DB instance {0} created, engine={1}, endpoint=localhost:{2}", id, engine, proxyPort);
        return instance;
    }

    public DbInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DB instance " + id + " not found.", 404));
    }

    public Collection<DbInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public DbInstance modifyDbInstance(String id, String newPassword, Boolean iamEnabled) {
        DbInstance instance = getDbInstance(id);
        instance.setStatus(DbInstanceStatus.AVAILABLE);
        if (newPassword != null && !newPassword.isBlank()) {
            instance.setMasterPassword(newPassword);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        instances.put(id, instance);
        LOG.infov("DB instance {0} modified", id);
        return instance;
    }

    public DbInstance rebootDbInstance(String id) {
        DbInstance instance = getDbInstance(id);

        instance.setStatus(DbInstanceStatus.REBOOTING);
        instances.put(id, instance);

        // Stop proxy during reboot
        proxyManager.stopProxy(id);

        // Restart container if it's a standalone instance
        if (instance.getDbClusterIdentifier() == null && instance.getContainerId() != null) {
            try {
                containerManager.stop(buildHandle(instance));
            } catch (Exception e) {
                LOG.warnv("Error stopping container during reboot of {0}: {1}", id, e.getMessage());
            }
            String image = imageForEngine(instance.getEngine());
            RdsContainerHandle handle = containerManager.start(id, instance.getEngine(), image,
                    instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
            instance.setContainerId(handle.getContainerId());
            instance.setContainerHost(handle.getHost());
            instance.setContainerPort(handle.getPort());
        }

        instance.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put(id, instance);

        String effectiveMasterUser = instance.getMasterUsername() != null
                ? instance.getMasterUsername() : "root";
        proxyManager.startProxy(id, instance.getEngine(),
                instance.isIamDatabaseAuthenticationEnabled(),
                instance.getProxyPort(), instance.getContainerHost(), instance.getContainerPort(),
                effectiveMasterUser, instance.getMasterPassword(), instance.getDbName(),
                (user, pw) -> validateDbPassword(id, user, pw));

        LOG.infov("DB instance {0} rebooted", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        DbInstance instance = instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound", "DB instance " + id + " not found.", 404));

        if (instance.getStatus() == DbInstanceStatus.DELETING) {
            throw new AwsException("InvalidDBInstanceState",
                    "DB instance " + id + " is already being deleted.", 400);
        }

        instance.setStatus(DbInstanceStatus.DELETING);
        instances.put(id, instance);

        proxyManager.stopProxy(id);

        String clusterId = instance.getDbClusterIdentifier();
        if (clusterId == null || clusterId.isBlank()) {
            // Standalone — stop its container
            if (instance.getContainerId() != null) {
                containerManager.stop(buildHandle(instance));
            }
        } else {
            // Cluster member — remove from cluster's member list
            DbCluster cluster = clusters.get(clusterId).orElse(null);
            if (cluster != null) {
                cluster.getDbClusterMembers().remove(id);
                clusters.put(clusterId, cluster);
            }
        }

        releaseProxyPort(instance.getProxyPort());
        instances.delete(id);
        LOG.infov("DB instance {0} deleted", id);
    }

    // ── DB Clusters ───────────────────────────────────────────────────────────

    public DbCluster createDbCluster(String id, String engineParam, String engineVersion,
                                     String masterUsername, String masterPassword,
                                     String databaseName, boolean iamEnabled,
                                     String paramGroupName) {
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "DB cluster " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        int proxyPort = allocateProxyPort();
        String image = imageForEngine(engine);

        RdsContainerHandle handle = containerManager.start(id, engine, image, masterUsername, masterPassword, databaseName);

        DbEndpoint endpoint = new DbEndpoint("localhost", proxyPort);
        DbCluster cluster = new DbCluster(id, engine, engineVersion, masterUsername, masterPassword,
                databaseName, DbInstanceStatus.AVAILABLE, endpoint, endpoint,
                iamEnabled, new ArrayList<>(), paramGroupName, Instant.now(), proxyPort);
        cluster.setContainerId(handle.getContainerId());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());

        String region = regionResolver.getDefaultRegion();
        cluster.setDbClusterResourceId("cluster-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        cluster.setDbClusterArn(regionResolver.buildArn("rds", region, "cluster:" + id));

        String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
        proxyManager.startProxy(id, engine, iamEnabled, proxyPort, handle.getHost(), handle.getPort(),
                effectiveMasterUser, masterPassword, databaseName,
                (user, pw) -> validateDbClusterPassword(id, user, pw));

        clusters.put(id, cluster);
        LOG.infov("DB cluster {0} created, engine={1}, endpoint=localhost:{2}", id, engine, proxyPort);
        return cluster;
    }

    public DbCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));
    }

    public Collection<DbCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            return clusters.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return clusters.scan(k -> true);
    }

    public DbCluster modifyDbCluster(String id, String newPassword, Boolean iamEnabled) {
        DbCluster cluster = getDbCluster(id);
        if (newPassword != null && !newPassword.isBlank()) {
            cluster.setMasterPassword(newPassword);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        clusters.put(id, cluster);
        LOG.infov("DB cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        DbCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));

        if (!cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "DB cluster " + id + " still has DB instances.", 400);
        }

        cluster.setStatus(DbInstanceStatus.DELETING);
        clusters.put(id, cluster);

        proxyManager.stopProxy(id);

        if (cluster.getContainerId() != null) {
            containerManager.stop(buildClusterHandle(cluster));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(id);
        LOG.infov("DB cluster {0} deleted", id);
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    public DbParameterGroup createDbParameterGroup(String name, String family, String description) {
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB parameter group " + name + " already exists.", 400);
        }
        DbParameterGroup group = new DbParameterGroup(name, family, description);
        parameterGroups.put(name, group);
        return group;
    }

    public DbParameterGroup getDbParameterGroup(String name) {
        return parameterGroups.get(name).orElseThrow(() ->
                new AwsException("DBParameterGroupNotFound",
                        "DB parameter group " + name + " not found.", 404));
    }

    public Collection<DbParameterGroup> listDbParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return parameterGroups.get(filterName).map(List::of).orElse(List.of());
        }
        return parameterGroups.scan(k -> true);
    }

    public void deleteDbParameterGroup(String name) {
        if (parameterGroups.get(name).isEmpty()) {
            throw new AwsException("DBParameterGroupNotFound",
                    "DB parameter group " + name + " not found.", 404);
        }
        parameterGroups.delete(name);
    }

    public DbParameterGroup modifyDbParameterGroup(String name,
                                                    java.util.Map<String, String> parameters) {
        DbParameterGroup group = getDbParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        parameterGroups.put(name, group);
        return group;
    }

    // ── Password validation callbacks ─────────────────────────────────────────

    public boolean validateDbPassword(String instanceId, String clientUser, String password) {
        DbInstance instance = instances.get(instanceId).orElse(null);
        if (instance == null) {
            return false;
        }
        return password != null && password.equals(instance.getMasterPassword());
    }

    public boolean validateDbClusterPassword(String clusterId, String clientUser, String password) {
        DbCluster cluster = clusters.get(clusterId).orElse(null);
        if (cluster == null) {
            return false;
        }
        return password != null && password.equals(cluster.getMasterPassword());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DatabaseEngine resolveEngine(String engineParam) {
        if (engineParam == null) {
            return DatabaseEngine.POSTGRES;
        }
        return switch (engineParam.toLowerCase()) {
            case "postgres", "aurora-postgresql" -> DatabaseEngine.POSTGRES;
            case "mysql", "aurora-mysql", "aurora" -> DatabaseEngine.MYSQL;
            case "mariadb" -> DatabaseEngine.MARIADB;
            default -> throw new AwsException("InvalidParameterValue",
                    "Unsupported engine: " + engineParam + ". Supported: postgres, mysql, mariadb.", 400);
        };
    }

    private String imageForEngine(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRES -> config.services().rds().defaultPostgresImage();
            case MYSQL -> config.services().rds().defaultMysqlImage();
            case MARIADB -> config.services().rds().defaultMariadbImage();
        };
    }

    private int allocateProxyPort() {
        int base = config.services().rds().proxyBasePort();
        int max = config.services().rds().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientDBInstanceCapacity",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    private RdsContainerHandle buildHandle(DbInstance instance) {
        return new RdsContainerHandle(instance.getContainerId(), instance.getDbInstanceIdentifier(),
                instance.getContainerHost(), instance.getContainerPort());
    }

    private RdsContainerHandle buildClusterHandle(DbCluster cluster) {
        return new RdsContainerHandle(cluster.getContainerId(), cluster.getDbClusterIdentifier(),
                cluster.getContainerHost(), cluster.getContainerPort());
    }
}
