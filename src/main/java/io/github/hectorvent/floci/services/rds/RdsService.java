package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    private final StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups;
    private final RdsContainerManager containerManager;
    private final RdsProxyManager proxyManager;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public RdsService(RdsContainerManager containerManager,
                      RdsProxyManager proxyManager,
                      RegionResolver regionResolver,
                      EmulatorConfig config,
                      StorageFactory storageFactory) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.regionResolver = regionResolver;
        this.config = config;
        this.instances = storageFactory.create("rds", "rds-instances.json",
                new TypeReference<Map<String, DbInstance>>() {});
        this.clusters = storageFactory.create("rds", "rds-clusters.json",
                new TypeReference<Map<String, DbCluster>>() {});
        this.parameterGroups = storageFactory.create("rds", "rds-parameter-groups.json",
                new TypeReference<Map<String, DbParameterGroup>>() {});
        this.clusterParameterGroups = storageFactory.create("rds", "rds-cluster-parameter-groups.json",
                new TypeReference<Map<String, DbClusterParameterGroup>>() {});
    }

    RdsService(RdsContainerManager containerManager,
               RdsProxyManager proxyManager,
               RegionResolver regionResolver,
               EmulatorConfig config,
               StorageBackend<String, DbInstance> instances,
               StorageBackend<String, DbCluster> clusters,
               StorageBackend<String, DbParameterGroup> parameterGroups,
               StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.regionResolver = regionResolver;
        this.config = config;
        this.instances = instances;
        this.clusters = clusters;
        this.parameterGroups = parameterGroups;
        this.clusterParameterGroups = clusterParameterGroups;
    }

    public void restorePersistedRuntime() {
        restoreClusters();
        restoreInstances();
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
        String instanceVolumeId = null;
        String instanceDockerVolumeName = null;

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
            instanceDockerVolumeName = cluster.getDockerVolumeName() != null
                    ? cluster.getDockerVolumeName()
                    : volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier());
        } else {
            // Standalone instance — start its own container
            String image = imageForEngine(engine);
            instanceVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
            RdsContainerHandle handle = containerManager.start(id, instanceVolumeId, engine, image, masterUsername, masterPassword, dbName);
            backendHost = handle.getHost();
            backendPort = handle.getPort();
            containerId = handle.getContainerId();
            containerHost = handle.getHost();
            containerPort = handle.getPort();
            instanceDockerVolumeName = volumeName(instanceVolumeId, id);
        }

        DbEndpoint endpoint = new DbEndpoint("localhost", proxyPort);
        DbInstance instance = new DbInstance(id, engine, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, DbInstanceStatus.AVAILABLE,
                endpoint, iamEnabled, paramGroupName, dbClusterIdentifier, Instant.now(), proxyPort);
        instance.setContainerId(containerId);
        instance.setContainerHost(containerHost);
        instance.setContainerPort(containerPort);
        instance.setVolumeId(instanceVolumeId);
        instance.setDockerVolumeName(instanceDockerVolumeName);

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
        LOG.infov("DB instance {0} created, engine={1}, endpoint=localhost:{2}", id, engine, String.valueOf(proxyPort));
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
            RdsContainerHandle handle = containerManager.start(id, instance.getVolumeId(), instance.getEngine(), image,
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
            // Standalone — stop its container and clean up its Docker volume
            if (instance.getContainerId() != null) {
                containerManager.stop(buildHandle(instance));
            }
            containerManager.removeVolume(instance.getDbInstanceIdentifier(), instance.getVolumeId());
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
        String clusterVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));

        RdsContainerHandle handle = containerManager.start(id, clusterVolumeId, engine, image, masterUsername, masterPassword, databaseName);

        DbEndpoint endpoint = new DbEndpoint("localhost", proxyPort);
        DbCluster cluster = new DbCluster(id, engine, engineVersion, masterUsername, masterPassword,
                databaseName, DbInstanceStatus.AVAILABLE, endpoint, endpoint,
                iamEnabled, new ArrayList<>(), paramGroupName, Instant.now(), proxyPort);
        cluster.setContainerId(handle.getContainerId());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());
        cluster.setVolumeId(clusterVolumeId);
        cluster.setDockerVolumeName(volumeName(clusterVolumeId, id));

        String region = regionResolver.getDefaultRegion();
        cluster.setDbClusterResourceId("cluster-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        cluster.setDbClusterArn(regionResolver.buildArn("rds", region, "cluster:" + id));

        String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
        proxyManager.startProxy(id, engine, iamEnabled, proxyPort, handle.getHost(), handle.getPort(),
                effectiveMasterUser, masterPassword, databaseName,
                (user, pw) -> validateDbClusterPassword(id, user, pw));

        clusters.put(id, cluster);
        LOG.infov("DB cluster {0} created, engine={1}, endpoint=localhost:{2}", id, engine, String.valueOf(proxyPort));
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
        containerManager.removeVolume(id, cluster.getVolumeId());

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

    // ── Cluster Parameter Groups ──────────────────────────────────────────────

    public DbClusterParameterGroup createDbClusterParameterGroup(String name, String family, String description) {
        if (clusterParameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB cluster parameter group " + name + " already exists.", 400);
        }
        DbClusterParameterGroup group = new DbClusterParameterGroup(name, family, description);
        clusterParameterGroups.put(name, group);
        return group;
    }

    public DbClusterParameterGroup getDbClusterParameterGroup(String name) {
        return clusterParameterGroups.get(name).orElseThrow(() ->
                new AwsException("DBParameterGroupNotFound",
                        "DB cluster parameter group " + name + " not found.", 404));
    }

    public Collection<DbClusterParameterGroup> listDbClusterParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return clusterParameterGroups.get(filterName).map(List::of).orElse(List.of());
        }
        return clusterParameterGroups.scan(k -> true);
    }

    public void deleteDbClusterParameterGroup(String name) {
        if (clusterParameterGroups.get(name).isEmpty()) {
            throw new AwsException("DBParameterGroupNotFound",
                    "DB cluster parameter group " + name + " not found.", 404);
        }
        clusterParameterGroups.delete(name);
    }

    public DbClusterParameterGroup modifyDbClusterParameterGroup(String name,
                                                                  java.util.Map<String, String> parameters) {
        DbClusterParameterGroup group = getDbClusterParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        clusterParameterGroups.put(name, group);
        return group;
    }

    // ── Password validation callbacks ─────────────────────────────────────────

    public boolean validateDbPassword(String instanceId, String clientUser, String password) {
        DbInstance instance = instances.get(instanceId).orElse(null);
        if (instance == null) {
            return false;
        }
        if (!instance.getMasterUsername().equals(clientUser)) {
            return true; // non-master user: backend is the authority
        }
        return password != null && password.equals(instance.getMasterPassword());
    }

    public boolean validateDbClusterPassword(String clusterId, String clientUser, String password) {
        DbCluster cluster = clusters.get(clusterId).orElse(null);
        if (cluster == null) {
            return false;
        }
        if (!cluster.getMasterUsername().equals(clientUser)) {
            return true; // non-master user: backend is the authority
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

    private void restoreClusters() {
        for (DbCluster cluster : allClusters()) {
            if (cluster.getStatus() == DbInstanceStatus.DELETING) {
                continue;
            }
            int proxyPort = reserveOrAllocateProxyPort(cluster.getProxyPort());
            cluster.setProxyPort(proxyPort);
            cluster.setEndpoint(new DbEndpoint("localhost", proxyPort));
            cluster.setReaderEndpoint(new DbEndpoint("localhost", proxyPort));
            if (cluster.getDockerVolumeName() == null) {
                cluster.setDockerVolumeName(volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier()));
            }
            try {
                String image = imageForEngine(cluster.getEngine());
                RdsContainerHandle handle = containerManager.start(cluster.getDbClusterIdentifier(),
                        cluster.getVolumeId(), cluster.getEngine(), image,
                        cluster.getMasterUsername(), cluster.getMasterPassword(), cluster.getDatabaseName());
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                String effectiveMasterUser = cluster.getMasterUsername() != null
                        ? cluster.getMasterUsername() : "root";
                proxyManager.startProxy(cluster.getDbClusterIdentifier(), cluster.getEngine(),
                        cluster.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        handle.getHost(), handle.getPort(), effectiveMasterUser,
                        cluster.getMasterPassword(), cluster.getDatabaseName(),
                        (user, pw) -> validateDbClusterPassword(cluster.getDbClusterIdentifier(), user, pw));
                cluster.setStatus(DbInstanceStatus.AVAILABLE);
            } catch (Exception e) {
                releaseProxyPort(proxyPort);
                LOG.warnv(e, "Failed to restore RDS cluster {0}", cluster.getDbClusterIdentifier());
            }
        }
    }

    private void restoreInstances() {
        for (DbInstance instance : allInstances()) {
            if (instance.getStatus() == DbInstanceStatus.DELETING) {
                continue;
            }
            int proxyPort = reserveOrAllocateProxyPort(instance.getProxyPort());
            instance.setProxyPort(proxyPort);
            instance.setEndpoint(new DbEndpoint("localhost", proxyPort));
            try {
                String backendHost;
                int backendPort;
                String clusterId = instance.getDbClusterIdentifier();
                if (clusterId != null && !clusterId.isBlank()) {
                    DbCluster cluster = clusters.get(clusterId).orElseThrow(() ->
                            new AwsException("DBClusterNotFoundFault",
                                    "DB cluster " + clusterId + " not found.", 404));
                    backendHost = cluster.getContainerHost();
                    backendPort = cluster.getContainerPort();
                    if (backendHost == null || backendPort <= 0) {
                        throw new AwsException("InvalidDBClusterStateFault",
                                "DB cluster " + clusterId + " runtime is not available.", 400);
                    }
                    instance.setContainerId(cluster.getContainerId());
                    instance.setContainerHost(cluster.getContainerHost());
                    instance.setContainerPort(cluster.getContainerPort());
                    if (instance.getDockerVolumeName() == null) {
                        instance.setDockerVolumeName(cluster.getDockerVolumeName() != null
                                ? cluster.getDockerVolumeName()
                                : volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier()));
                    }
                } else {
                    if (instance.getDockerVolumeName() == null) {
                        instance.setDockerVolumeName(volumeName(instance.getVolumeId(), instance.getDbInstanceIdentifier()));
                    }
                    String image = imageForEngine(instance.getEngine());
                    RdsContainerHandle handle = containerManager.start(instance.getDbInstanceIdentifier(),
                            instance.getVolumeId(), instance.getEngine(), image,
                            instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
                    backendHost = handle.getHost();
                    backendPort = handle.getPort();
                    instance.setContainerId(handle.getContainerId());
                    instance.setContainerHost(handle.getHost());
                    instance.setContainerPort(handle.getPort());
                }

                String effectiveMasterUser = instance.getMasterUsername() != null
                        ? instance.getMasterUsername() : "root";
                proxyManager.startProxy(instance.getDbInstanceIdentifier(), instance.getEngine(),
                        instance.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        backendHost, backendPort, effectiveMasterUser,
                        instance.getMasterPassword(), instance.getDbName(),
                        (user, pw) -> validateDbPassword(instance.getDbInstanceIdentifier(), user, pw));
                instance.setStatus(DbInstanceStatus.AVAILABLE);
            } catch (Exception e) {
                releaseProxyPort(proxyPort);
                LOG.warnv(e, "Failed to restore RDS instance {0}", instance.getDbInstanceIdentifier());
            }
        }
    }

    private Collection<DbCluster> allClusters() {
        if (clusters instanceof AccountAwareStorageBackend<DbCluster> aware) {
            return aware.scanAllAccounts();
        }
        return clusters.scan(k -> true);
    }

    private Collection<DbInstance> allInstances() {
        if (instances instanceof AccountAwareStorageBackend<DbInstance> aware) {
            return aware.scanAllAccounts();
        }
        return instances.scan(k -> true);
    }

    private int reserveOrAllocateProxyPort(int persistedPort) {
        if (persistedPort > 0 && usedPorts.add(persistedPort)) {
            return persistedPort;
        }
        return allocateProxyPort();
    }

    private String volumeName(String volumeId, String fallbackId) {
        return ContainerStorageHelper.resourceName("rds", volumeId, fallbackId);
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
