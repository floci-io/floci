package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core RDS business logic — DB instances, clusters, and parameter groups.
 * Starts DB containers and auth proxies on creation.
 */
@ApplicationScoped
public class RdsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsService.class);
    // AWS's own CreateDBInstance default when PreferredBackupWindow is omitted.
    private static final String DEFAULT_PREFERRED_BACKUP_WINDOW = "04:00-06:00";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ManagedClusterParameterGroup> MANAGED_CLUSTER_PARAMETER_GROUPS = List.of(
            managedDefault("aurora-mysql5.7"),
            managedDefault("aurora-mysql8.0"),
            managedDefault("aurora-mysql8.4"),
            managedDefault("aurora-postgresql11"),
            managedDefault("aurora-postgresql12"),
            managedDefault("aurora-postgresql13"),
            managedDefault("aurora-postgresql14"),
            managedDefault("aurora-postgresql15"),
            managedDefault("aurora-postgresql16"),
            managedDefault("aurora-postgresql17"),
            managedDefault("aurora-postgresql18"),
            managedDefault("mysql8.0"),
            managedDefault("mysql8.4"),
            managedDefault("postgres13"),
            managedDefault("postgres14"),
            managedDefault("postgres15"),
            managedDefault("postgres16"),
            managedDefault("postgres17"),
            managedDefault("postgres18"));

    private final StorageBackend<String, DbInstance> instances;
    private final StorageBackend<String, DbCluster> clusters;
    private final StorageBackend<String, DbParameterGroup> parameterGroups;
    private final StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups;
    private final StorageBackend<String, DbSubnetGroup> subnetGroups;
    private final RdsContainerManager containerManager;
    private final RdsProxyManager proxyManager;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final SecretsManagerService secretsManagerService;
    private final DockerHostResolver dockerHostResolver;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private static final Pattern IMAGE_TAG_VERSION_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");
    private static final Pattern SAFE_IMAGE_TAG_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    @Inject
    public RdsService(RdsContainerManager containerManager,
                      RdsProxyManager proxyManager,
                      Ec2Service ec2Service,
                      RegionResolver regionResolver,
                      EmulatorConfig config,
                      StorageFactory storageFactory,
                      SecretsManagerService secretsManagerService,
                      DockerHostResolver dockerHostResolver,
                      CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.secretsManagerService = secretsManagerService;
        this.dockerHostResolver = dockerHostResolver;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        this.instances = storageFactory.create("rds", "rds-instances.json",
                new TypeReference<Map<String, DbInstance>>() {});
        this.clusters = storageFactory.create("rds", "rds-clusters.json",
                new TypeReference<Map<String, DbCluster>>() {});
        this.parameterGroups = storageFactory.create("rds", "rds-parameter-groups.json",
                new TypeReference<Map<String, DbParameterGroup>>() {});
        this.clusterParameterGroups = storageFactory.create("rds", "rds-cluster-parameter-groups.json",
                new TypeReference<Map<String, DbClusterParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("rds", "rds-subnet-groups.json",
                new TypeReference<Map<String, DbSubnetGroup>>() {});
    }

    RdsService(RdsContainerManager containerManager,
               RdsProxyManager proxyManager,
               Ec2Service ec2Service,
               RegionResolver regionResolver,
               EmulatorConfig config,
               StorageBackend<String, DbInstance> instances,
               StorageBackend<String, DbCluster> clusters,
               StorageBackend<String, DbParameterGroup> parameterGroups,
               StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
               StorageBackend<String, DbSubnetGroup> subnetGroups) {
        this(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, subnetGroups,
                null, null, null);
    }

    RdsService(RdsContainerManager containerManager,
               RdsProxyManager proxyManager,
               Ec2Service ec2Service,
               RegionResolver regionResolver,
               EmulatorConfig config,
               StorageBackend<String, DbInstance> instances,
               StorageBackend<String, DbCluster> clusters,
               StorageBackend<String, DbParameterGroup> parameterGroups,
               StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
               StorageBackend<String, DbSubnetGroup> subnetGroups,
               SecretsManagerService secretsManagerService,
               DockerHostResolver dockerHostResolver,
               CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.secretsManagerService = secretsManagerService;
        this.dockerHostResolver = dockerHostResolver;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        this.instances = instances;
        this.clusters = clusters;
        this.parameterGroups = parameterGroups;
        this.clusterParameterGroups = clusterParameterGroups;
        this.subnetGroups = subnetGroups;
    }

    public void restorePersistedRuntime() {
        restoreClusters();
        restoreInstances();
    }

    public void clear() {
        usedPorts.clear();
    }

    // ── DB Instances ──────────────────────────────────────────────────────────

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, null, false, false, null, Map.of());
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier,
                                       boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, null, false, manageMasterUserPassword,
                masterUserSecretKmsKeyId, Map.of());
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier,
                                       boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId,
                                       Map<String, String> tags) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, null, false, manageMasterUserPassword,
                masterUserSecretKmsKeyId, tags);
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier, String availabilityZone,
                                       boolean multiAz) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                false, null, Map.of());
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier, String availabilityZone,
                                       boolean multiAz, boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId,
                                       Map<String, String> tags) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                manageMasterUserPassword, masterUserSecretKmsKeyId, tags, List.of(), regionResolver.getDefaultRegion());
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier, String availabilityZone,
                                       boolean multiAz, boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId,
                                       Map<String, String> tags,
                                       List<String> vpcSecurityGroupIds) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                manageMasterUserPassword, masterUserSecretKmsKeyId, tags, vpcSecurityGroupIds,
                regionResolver.getDefaultRegion());
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier, String availabilityZone,
                                       boolean multiAz, boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId,
                                       Map<String, String> tags, String region) {
        return createDbInstance(id, engineParam, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, iamEnabled, paramGroupName,
                dbSubnetGroupName, dbClusterIdentifier, availabilityZone, multiAz,
                manageMasterUserPassword, masterUserSecretKmsKeyId, tags, List.of(), region);
    }

    public DbInstance createDbInstance(String id, String engineParam, String engineVersion,
                                       String masterUsername, String masterPassword,
                                       String dbName, String dbInstanceClass,
                                       int allocatedStorage, boolean iamEnabled,
                                       String paramGroupName, String dbSubnetGroupName,
                                       String dbClusterIdentifier, String availabilityZone,
                                       boolean multiAz, boolean manageMasterUserPassword,
                                       String masterUserSecretKmsKeyId,
                                       Map<String, String> tags,
                                       List<String> vpcSecurityGroupIds,
                                       String region) {
        String effectiveRegion = effectiveRegion(region);
        if (instances.get(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "DB instance " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        String engineIdentifier = normalizeEngineIdentifier(engineParam, engine);
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank() && !"default".equalsIgnoreCase(dbSubnetGroupName)) {
            getDbSubnetGroup(dbSubnetGroupName);
        }
        validateInstanceParameterGroup(paramGroupName, engineParam, engineVersion);
        boolean mock = config.services().rds().mock();
        // Always reserve a unique port (even in mock) so endpoints stay distinct and usedPorts
        // is consistent; mock mode only skips starting the container and auth proxy.
        int proxyPort = allocateProxyPort();
        if (masterUsername == null || masterUsername.isBlank()) {
            masterUsername = "root";
        }
        if (manageMasterUserPassword && (masterPassword == null || masterPassword.isBlank())) {
            masterPassword = generatedMasterPassword();
        }

        String backendHost = null;
        int backendPort = 0;
        String containerId = null;
        String containerHost = null;
        int containerPort = 0;
        String instanceVolumeId = null;
        String instanceDockerVolumeName = null;
        PlacementResolution placement;

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            // Cluster member — share the cluster's container (none exists in mock mode)
            DbCluster cluster = clusters.get(dbClusterIdentifier).orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault",
                            "DB cluster " + dbClusterIdentifier + " not found.", 404));
            backendHost = cluster.getContainerHost();
            backendPort = cluster.getContainerPort();
            containerId = cluster.getContainerId();
            containerHost = cluster.getContainerHost();
            containerPort = cluster.getContainerPort();
            if (!mock) {
                // In mock mode the cluster has no volume id, so the fallback would persist a
                // bogus volume name that a later non-mock restore could try to reference.
                instanceDockerVolumeName = cluster.getDockerVolumeName() != null
                        ? cluster.getDockerVolumeName()
                        : volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier());
            }
            placement = PlacementResolution.fromCluster(cluster);
        } else {
            placement = resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, effectiveRegion);
            if (!mock) {
                // Standalone instance — start its own container. A DB instance record is metadata:
                // its identifier, ARN, endpoint address and tags are derived from configuration and
                // need no Docker, so the instance is created and reaches 'available' even when no
                // daemon is reachable. Only connecting to the database needs the container.
                String image = imageForEngine(engine, engineVersion);
                String candidateVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
                RdsContainerHandle handle = containerManager.tryStart(id, candidateVolumeId, engine,
                        image, masterUsername, masterPassword, dbName);
                if (handle != null) {
                    backendHost = handle.getHost();
                    backendPort = handle.getPort();
                    containerId = handle.getContainerId();
                    containerHost = handle.getHost();
                    containerPort = handle.getPort();
                    instanceVolumeId = candidateVolumeId;
                    instanceDockerVolumeName = volumeName(candidateVolumeId, id);
                } else {
                    LOG.warnv("DB instance {0} created without a backing database container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the database do not until a daemon appears.", id);
                }
            }
        }

        DbEndpoint endpoint = mock ? new DbEndpoint("localhost", proxyPort) : proxyEndpoint(proxyPort);
        DbInstance instance = new DbInstance(id, engine, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, DbInstanceStatus.AVAILABLE,
                endpoint, iamEnabled, paramGroupName, dbClusterIdentifier, Instant.now(), proxyPort);
        instance.setEngineIdentifier(engineIdentifier);
        instance.setDbSubnetGroupName(dbSubnetGroupName);
        instance.setContainerId(containerId);
        instance.setContainerHost(containerHost);
        instance.setContainerPort(containerPort);
        instance.setVolumeId(instanceVolumeId);
        instance.setDockerVolumeName(instanceDockerVolumeName);
        instance.setTags(tags);
        instance.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        instance.setDbSubnetGroupName(placement.dbSubnetGroupName());
        instance.setVpcId(placement.vpcId());
        instance.setAvailabilityZone(placement.availabilityZone());
        instance.setMultiAz(placement.multiAz());
        instance.setSubnetAvailabilityZones(placement.subnetAvailabilityZones());

        instance.setDbiResourceId("db-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        instance.setDbInstanceArn(regionResolver.buildArn("rds", effectiveRegion, "db:" + id));
        if (manageMasterUserPassword) {
            attachManagedMasterUserSecret(instance, effectiveRegion, masterUserSecretKmsKeyId);
        }

        if (!mock && hasBackend(backendHost, backendPort)) {
            proxyManager.startProxy(id, engine, iamEnabled, proxyPort, backendHost, backendPort,
                    masterUsername, masterPassword, dbName,
                    (user, pw) -> validateDbPassword(id, user, pw));
        }

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            DbCluster cluster = clusters.get(dbClusterIdentifier).orElse(null);
            if (cluster != null) {
                cluster.getDbClusterMembers().add(id);
                clusters.put(dbClusterIdentifier, cluster);
            }
        }

        instances.put(id, instance);
        LOG.infov("DB instance {0} created, engine={1}, endpoint={2}:{3}",
                id, engine, endpoint.address(), String.valueOf(endpoint.port()));
        return instance;
    }

    /**
     * Records the CreateDBInstance-time attributes that AWS always echoes back from
     * DescribeDBInstances but that Floci does not otherwise model: StorageEncrypted (immutable
     * after creation on real AWS), DeletionProtection, AutoMinorVersionUpgrade,
     * CopyTagsToSnapshot, BackupRetentionPeriod and PerformanceInsightsEnabled. Without this,
     * a replan sees these fields go from unset to their configured value and proposes a change
     * (a forced replacement for StorageEncrypted specifically, since it is a ForceNew attribute).
     */
    public DbInstance setCreateTimeInstanceAttributes(String id, boolean storageEncrypted,
                                                       boolean deletionProtection,
                                                       boolean autoMinorVersionUpgrade,
                                                       boolean copyTagsToSnapshot,
                                                       int backupRetentionPeriod,
                                                       boolean performanceInsightsEnabled) {
        DbInstance instance = getDbInstance(id);
        instance.setStorageEncrypted(storageEncrypted);
        instance.setDeletionProtection(deletionProtection);
        instance.setAutoMinorVersionUpgrade(autoMinorVersionUpgrade);
        instance.setCopyTagsToSnapshot(copyTagsToSnapshot);
        instance.setBackupRetentionPeriod(backupRetentionPeriod);
        instance.setPerformanceInsightsEnabled(performanceInsightsEnabled);
        instances.put(id, instance);
        return instance;
    }

    // lex00/floci#120: DescribeDBInstances never echoed back several documented, client-set
    // fields at all - PreferredBackupWindow/MonitoringInterval/MonitoringRoleArn/
    // PerformanceInsightsRetentionPeriod/EngineLifecycleSupport/EnabledCloudwatchLogsExports/
    // MaxAllocatedStorage were either hardcoded (PreferredBackupWindow) or simply not stored
    // anywhere (the rest). Same post-create-attribute-setter shape as
    // setCreateTimeInstanceAttributes above, so CreateDBInstance's own overload chain doesn't
    // need to grow. `preferredBackupWindow` defaults to AWS's own "04:00-06:00" default when
    // the request never set it, matching what every DBInstance already showed before this fix
    // (now sourced from a real default constant instead of being unconditionally hardcoded).
    public DbInstance setCreateTimeInstanceOptionalFields(String id, String preferredBackupWindow,
                                                          Integer monitoringInterval, String monitoringRoleArn,
                                                          Integer performanceInsightsRetentionPeriod,
                                                          String engineLifecycleSupport,
                                                          List<String> enabledCloudwatchLogsExports,
                                                          Integer maxAllocatedStorage) {
        DbInstance instance = getDbInstance(id);
        instance.setPreferredBackupWindow(
                preferredBackupWindow != null && !preferredBackupWindow.isBlank()
                        ? preferredBackupWindow : DEFAULT_PREFERRED_BACKUP_WINDOW);
        instance.setMonitoringInterval(monitoringInterval);
        instance.setMonitoringRoleArn(monitoringRoleArn);
        instance.setPerformanceInsightsRetentionPeriod(performanceInsightsRetentionPeriod);
        instance.setEngineLifecycleSupport(engineLifecycleSupport);
        if (enabledCloudwatchLogsExports != null && !enabledCloudwatchLogsExports.isEmpty()) {
            instance.setEnabledCloudwatchLogsExports(enabledCloudwatchLogsExports);
        }
        instance.setMaxAllocatedStorage(maxAllocatedStorage);
        instances.put(id, instance);
        return instance;
    }

    // lex00/floci#120: real per-request semantics, applied to an existing instance from
    // ModifyDBInstance - only overwrites fields the request actually set (nulls mean "leave
    // alone"), unlike setCreateTimeInstanceOptionalFields above which always sets outright at
    // create time.
    public DbInstance modifyInstanceOptionalFields(String id, String preferredBackupWindow,
                                                   Integer monitoringInterval, String monitoringRoleArn,
                                                   Integer performanceInsightsRetentionPeriod,
                                                   String engineLifecycleSupport,
                                                   List<String> enableCloudwatchLogsExports,
                                                   List<String> disableCloudwatchLogsExports,
                                                   Integer maxAllocatedStorage) {
        DbInstance instance = getDbInstance(id);
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            instance.setPreferredBackupWindow(preferredBackupWindow);
        }
        if (monitoringInterval != null) {
            instance.setMonitoringInterval(monitoringInterval);
        }
        if (monitoringRoleArn != null) {
            instance.setMonitoringRoleArn(monitoringRoleArn);
        }
        if (performanceInsightsRetentionPeriod != null) {
            instance.setPerformanceInsightsRetentionPeriod(performanceInsightsRetentionPeriod);
        }
        if (engineLifecycleSupport != null) {
            instance.setEngineLifecycleSupport(engineLifecycleSupport);
        }
        if ((enableCloudwatchLogsExports != null && !enableCloudwatchLogsExports.isEmpty())
                || (disableCloudwatchLogsExports != null && !disableCloudwatchLogsExports.isEmpty())) {
            LinkedHashSet<String> exports = new LinkedHashSet<>(instance.getEnabledCloudwatchLogsExports());
            if (enableCloudwatchLogsExports != null) {
                exports.addAll(enableCloudwatchLogsExports);
            }
            if (disableCloudwatchLogsExports != null) {
                exports.removeAll(disableCloudwatchLogsExports);
            }
            instance.setEnabledCloudwatchLogsExports(new ArrayList<>(exports));
        }
        if (maxAllocatedStorage != null) {
            instance.setMaxAllocatedStorage(maxAllocatedStorage);
        }
        instances.put(id, instance);
        return instance;
    }

    // lex00/floci#120: Endpoint.Port previously always came from allocateProxyPort's own
    // base/max range, ignoring whatever Port/DBPortNumber a client requested at Create or
    // Modify time - see allocateProxyPort(Integer)'s own doc comment for the collision
    // fallback this honors. Re-points this instance's own local TCP listener at the exact
    // requested port when it's free, so the returned Endpoint.Port stays a real, connectable
    // value rather than diverging metadata.
    // Create-time-only wrapper: unlike ModifyDBInstance's DBPortNumber (where "not set" means
    // "leave alone"), CreateDBInstance's Port defaults to the engine's own standard port when
    // omitted - matching real AWS, which never leaves a running instance on a made-up port.
    public DbInstance applyCreateTimePort(String id, Integer requestedPort) {
        DbInstance instance = getDbInstance(id);
        int effectivePort = requestedPort != null && requestedPort > 0
                ? requestedPort : instance.getEngine().defaultPort();
        return applyRequestedPort(id, effectivePort);
    }

    public DbInstance applyRequestedPort(String id, Integer requestedPort) {
        DbInstance instance = getDbInstance(id);
        if (requestedPort == null || requestedPort <= 0 || requestedPort == instance.getProxyPort()) {
            return instance;
        }
        boolean mock = config.services().rds().mock();
        int oldPort = instance.getProxyPort();
        int newPort = allocateProxyPort(requestedPort);
        if (!mock && hasBackend(instance.getContainerHost(), instance.getContainerPort())) {
            proxyManager.stopProxy(id);
            proxyManager.startProxy(id, instance.getEngine(), instance.isIamDatabaseAuthenticationEnabled(),
                    newPort, instance.getContainerHost(), instance.getContainerPort(),
                    instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName(),
                    (user, pw) -> validateDbPassword(id, user, pw));
        }
        releaseProxyPort(oldPort);
        instance.setProxyPort(newPort);
        instance.setEndpoint(mock ? new DbEndpoint("localhost", newPort) : proxyEndpoint(newPort));
        instances.put(id, instance);
        return instance;
    }

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public void addTagsToResource(String resourceName, Map<String, String> tags) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new java.util.LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new java.util.LinkedHashMap<>(handle.tags());
        tagKeys.forEach(updated::remove);
        handle.save().accept(updated);
    }

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, java.util.function.Consumer<Map<String, String>> save) {}

    /**
     * Resolves a tagging ResourceName to its backing resource.
     *
     * RDS tags can be attached to many resource types (DB instances, clusters, subnet groups, ...),
     * each identified by an ARN of the form {@code arn:aws:rds:<region>:<account>:<type>:<id>}.
     * A bare resource name (no ARN) is treated as a DB instance identifier for backwards compatibility.
     */
    private TagHandle resolveTagHandle(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }

        String type = "db";
        String id = resourceName;
        if (resourceName.startsWith("arn:")) {
            AwsArnUtils.Arn arn;
            try {
                arn = AwsArnUtils.parse(resourceName);
            } catch (IllegalArgumentException malformed) {
                throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
            }
            if (!"rds".equals(arn.service())) {
                throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
            }
            String resource = arn.resource();
            int sep = resource.indexOf(':');
            if (sep < 0) {
                // Real AWS requires the resource part of an RDS ARN to be <type>:<id>.
                throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
            }
            type = resource.substring(0, sep);
            id = resource.substring(sep + 1);
        }
        // A bare (non-ARN) resource name is treated as a DB instance identifier for backwards compatibility.

        String resourceId = id;
        return switch (type) {
            case "db" -> {
                DbInstance instance = getDbInstance(resourceId);
                yield new TagHandle(instance.getTags(), updated -> {
                    instance.setTags(updated);
                    instances.put(resourceId, instance);
                });
            }
            case "cluster" -> {
                DbCluster cluster = getDbCluster(resourceId);
                yield new TagHandle(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(resourceId, cluster);
                });
            }
            case "subgrp" -> {
                DbSubnetGroup group = getDbSubnetGroup(resourceId);
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    subnetGroups.put(resourceId, group);
                });
            }
            case "pg" -> {
                DbParameterGroup group = getDbParameterGroup(resourceId);
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    parameterGroups.put(resourceId, group);
                });
            }
            // Valid RDS resource types Floci does not model yet (og, cluster-pg, snapshot, ...) —
            // taggable on real AWS, so the message states the Floci limitation rather than AWS semantics.
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not yet implemented by Floci: " + resourceName, 400);
        };
    }

    private void attachManagedMasterUserSecret(DbInstance instance, String region, String kmsKeyId) {
        if (secretsManagerService == null) {
            throw new AwsException("InvalidParameterCombination",
                    "ManageMasterUserPassword requires Secrets Manager support.", 400);
        }
        String secretName = "rds!" + instance.getDbiResourceId();
        Secret secret = secretsManagerService.createSecret(
                secretName,
                managedMasterSecretString(instance),
                null,
                "Managed RDS master user secret for " + instance.getDbInstanceIdentifier(),
                kmsKeyId,
                null,
                region);
        instance.setMasterUserSecretArn(secret.getArn());
        instance.setMasterUserSecretStatus("active");
        instance.setMasterUserSecretKmsKeyId(kmsKeyId);
    }

    private static String managedMasterSecretString(DbInstance instance) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "username", instance.getMasterUsername(),
                    "password", instance.getMasterPassword(),
                    "engine", instance.getEngine().name().toLowerCase(),
                    "host", instance.getEndpoint().address(),
                    "port", instance.getEndpoint().port(),
                    "dbname", instance.getDbName() == null ? "" : instance.getDbName(),
                    "dbInstanceIdentifier", instance.getDbInstanceIdentifier()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize RDS master user secret", e);
        }
    }

    private static String generatedMasterPassword() {
        return "floci-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public DbInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DB instance " + id + " not found.", 404));
    }

    public Collection<DbInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // DBInstanceIdentifier also accepts an ARN per the AWS model. Match the
            // full ARN against each instance's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local instance.
            if (filterId.startsWith("arn:")) {
                return instances.scan(k -> true).stream()
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public Collection<DbInstance> listDbInstancesByDbiResourceIds(Collection<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return instances.scan(k -> true);
        }
        return instances.scan(k -> true).stream()
                .filter(instance -> resourceIds.contains(instance.getDbiResourceId()))
                .toList();
    }

    public DbInstance modifyDbInstance(String id, String newPassword, Boolean iamEnabled,
                                       String dbSubnetGroupName) {
        return modifyDbInstance(id, newPassword, iamEnabled, dbSubnetGroupName, null);
    }

    public DbInstance modifyDbInstance(String id, String newPassword, Boolean iamEnabled,
                                       String dbSubnetGroupName, List<String> vpcSecurityGroupIds) {
        DbInstance instance = getDbInstance(id);
        instance.setStatus(DbInstanceStatus.AVAILABLE);
        if (newPassword != null && !newPassword.isBlank()) {
            instance.setMasterPassword(newPassword);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank()) {
            getDbSubnetGroup(dbSubnetGroupName);
            instance.setDbSubnetGroupName(dbSubnetGroupName);
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            instance.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        instances.put(id, instance);
        LOG.infov("DB instance {0} modified", id);
        return instance;
    }

    public List<Map<String, String>> describeOrderableDbInstanceOptions(String engine,
                                                                        String engineVersion,
                                                                        String dbInstanceClass) {
        List<Map<String, String>> options = List.of(
                Map.of("engine", "postgres", "engineVersion", "16.3", "dbInstanceClass", "db.t3.micro"),
                Map.of("engine", "postgres", "engineVersion", "16.14", "dbInstanceClass", "db.t3.micro"),
                Map.of("engine", "postgres", "engineVersion", "18.1", "dbInstanceClass", "db.t3.micro"),
                Map.of("engine", "postgres", "engineVersion", "18.1", "dbInstanceClass", "db.m8g.large"),
                Map.of("engine", "postgres", "engineVersion", "18.4", "dbInstanceClass", "db.m8g.large"),
                Map.of("engine", "postgres", "engineVersion", "16.3", "dbInstanceClass", "db.t4g.micro"),
                Map.of("engine", "postgres", "engineVersion", "16.3", "dbInstanceClass", "db.t4g.small"),
                Map.of("engine", "postgres", "engineVersion", "16.14", "dbInstanceClass", "db.t4g.small"),
                Map.of("engine", "postgres", "engineVersion", "16.3", "dbInstanceClass", "db.t4g.medium"),
                Map.of("engine", "mysql", "engineVersion", "8.0", "dbInstanceClass", "db.t3.micro"),
                Map.of("engine", "mariadb", "engineVersion", "11", "dbInstanceClass", "db.t3.micro")
        );
        return options.stream()
                .filter(option -> engine == null || engine.isBlank() || engine.equalsIgnoreCase(option.get("engine")))
                .filter(option -> engineVersion == null || engineVersion.isBlank()
                        || engineVersion.equalsIgnoreCase(option.get("engineVersion")))
                .filter(option -> dbInstanceClass == null || dbInstanceClass.isBlank()
                        || dbInstanceClass.equalsIgnoreCase(option.get("dbInstanceClass")))
                .toList();
    }

    public DbInstance rebootDbInstance(String id) {
        DbInstance instance = getDbInstance(id);

        instance.setStatus(DbInstanceStatus.REBOOTING);
        instances.put(id, instance);

        boolean mock = config.services().rds().mock();
        if (!mock) {
            // Stop proxy during reboot
            proxyManager.stopProxy(id);

            // Restart container if it's a standalone instance
            if (instance.getDbClusterIdentifier() == null && instance.getContainerId() != null) {
                try {
                    containerManager.stop(buildHandle(instance));
                } catch (Exception e) {
                    LOG.warnv("Error stopping container during reboot of {0}: {1}", id, e.getMessage());
                }
                String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
                RdsContainerHandle handle = containerManager.tryStart(id, instance.getVolumeId(),
                        instance.getEngine(), image, instance.getMasterUsername(),
                        instance.getMasterPassword(), instance.getDbName());
                instance.setContainerId(handle != null ? handle.getContainerId() : null);
                instance.setContainerHost(handle != null ? handle.getHost() : null);
                instance.setContainerPort(handle != null ? handle.getPort() : 0);
            }
        }

        instance.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put(id, instance);

        if (!mock) {
            if (hasBackend(instance.getContainerHost(), instance.getContainerPort())) {
                String effectiveMasterUser = instance.getMasterUsername() != null
                        ? instance.getMasterUsername() : "root";
                proxyManager.startProxy(id, instance.getEngine(),
                        instance.isIamDatabaseAuthenticationEnabled(),
                        instance.getProxyPort(), instance.getContainerHost(), instance.getContainerPort(),
                        effectiveMasterUser, instance.getMasterPassword(), instance.getDbName(),
                        (user, pw) -> validateDbPassword(id, user, pw));
            } else {
                // No backing container — created or last rebooted while no daemon was reachable.
                instance = ensureInstanceBackend(id);
            }
        }

        LOG.infov("DB instance {0} rebooted", id);
        return instance;
    }

    /**
     * Reports whether a recorded backend address points at a live database container.
     */
    private static boolean hasBackend(String host, int port) {
        return host != null && !host.isBlank() && port > 0;
    }

    /**
     * Starts the backing database container and auth proxy for a DB instance recorded without one,
     * because no Docker daemon was reachable when it was created or restored. Every operation that
     * needs the live database calls this first, so the backend comes up as soon as a daemon
     * appears. Instances that already have a backend, and mock-mode instances, are left untouched.
     *
     * @return the instance, with its container fields populated when a backend became available
     */
    public DbInstance ensureInstanceBackend(String id) {
        DbInstance instance = getDbInstance(id);
        if (config.services().rds().mock()
                || hasBackend(instance.getContainerHost(), instance.getContainerPort())) {
            return instance;
        }

        String clusterId = instance.getDbClusterIdentifier();
        if (clusterId != null && !clusterId.isBlank()) {
            DbCluster cluster = ensureClusterBackend(clusterId);
            if (!hasBackend(cluster.getContainerHost(), cluster.getContainerPort())) {
                return instance;
            }
            instance.setContainerId(cluster.getContainerId());
            instance.setContainerHost(cluster.getContainerHost());
            instance.setContainerPort(cluster.getContainerPort());
            instance.setVolumeId(cluster.getVolumeId());
            instance.setDockerVolumeName(cluster.getDockerVolumeName());
        } else {
            String volumeId = instance.getVolumeId() != null
                    ? instance.getVolumeId()
                    : String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
            String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
            RdsContainerHandle handle = containerManager.tryStart(id, volumeId, instance.getEngine(),
                    image, instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
            if (handle == null) {
                return instance;
            }
            instance.setContainerId(handle.getContainerId());
            instance.setContainerHost(handle.getHost());
            instance.setContainerPort(handle.getPort());
            instance.setVolumeId(volumeId);
            instance.setDockerVolumeName(volumeName(volumeId, id));
        }

        String effectiveMasterUser = instance.getMasterUsername() != null
                ? instance.getMasterUsername() : "root";
        proxyManager.startProxy(id, instance.getEngine(),
                instance.isIamDatabaseAuthenticationEnabled(), instance.getProxyPort(),
                instance.getContainerHost(), instance.getContainerPort(), effectiveMasterUser,
                instance.getMasterPassword(), instance.getDbName(),
                (user, pw) -> validateDbPassword(id, user, pw));
        instances.put(id, instance);
        LOG.infov("Backing database container for DB instance {0} started on retry", id);
        return instance;
    }

    /**
     * The {@link #ensureInstanceBackend} counterpart for DB clusters.
     *
     * @return the cluster, with its container fields populated when a backend became available
     */
    public DbCluster ensureClusterBackend(String id) {
        DbCluster cluster = getDbCluster(id);
        if (config.services().rds().mock()
                || hasBackend(cluster.getContainerHost(), cluster.getContainerPort())) {
            return cluster;
        }

        String volumeId = cluster.getVolumeId() != null
                ? cluster.getVolumeId()
                : String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
        String image = imageForEngine(cluster.getEngine(), cluster.getEngineVersion());
        RdsContainerHandle handle = containerManager.tryStart(id, volumeId, cluster.getEngine(), image,
                cluster.getMasterUsername(), cluster.getMasterPassword(), cluster.getDatabaseName());
        if (handle == null) {
            return cluster;
        }
        cluster.setContainerId(handle.getContainerId());
        cluster.setContainerHost(handle.getHost());
        cluster.setContainerPort(handle.getPort());
        cluster.setVolumeId(volumeId);
        cluster.setDockerVolumeName(volumeName(volumeId, id));

        String effectiveMasterUser = cluster.getMasterUsername() != null
                ? cluster.getMasterUsername() : "root";
        proxyManager.startProxy(id, cluster.getEngine(),
                cluster.isIamDatabaseAuthenticationEnabled(), cluster.getProxyPort(),
                cluster.getContainerHost(), cluster.getContainerPort(), effectiveMasterUser,
                cluster.getMasterPassword(), cluster.getDatabaseName(),
                (user, pw) -> validateDbClusterPassword(id, user, pw));
        clusters.put(id, cluster);
        LOG.infov("Backing database container for DB cluster {0} started on retry", id);
        return cluster;
    }

    /**
     * Whether Floci can reach a Docker daemon at all. The RDS data plane (a real database
     * connection) cannot be emulated without one, so callers use this to raise a modelled error
     * naming the missing daemon instead of reporting a generic runtime failure.
     */
    public boolean isBackendRuntimeAvailable() {
        return config.services().rds().mock() || containerManager.isDockerReachable();
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

        boolean mock = config.services().rds().mock();
        if (!mock) {
            proxyManager.stopProxy(id);
        }

        String clusterId = instance.getDbClusterIdentifier();
        if (clusterId == null || clusterId.isBlank()) {
            // Standalone — stop its container and clean up its Docker volume. Neither exists in mock
            // mode, nor for an instance created while no Docker daemon was reachable, and touching
            // Docker in those cases would fail a delete that is otherwise pure metadata.
            if (!mock && instance.getContainerId() != null) {
                containerManager.stop(buildHandle(instance));
                containerManager.removeVolume(instance.getDbInstanceIdentifier(), instance.getVolumeId());
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
        return createDbCluster(id, engineParam, engineVersion, masterUsername, masterPassword,
                databaseName, iamEnabled, paramGroupName, null, null, false);
    }

    public DbCluster createDbCluster(String id, String engineParam, String engineVersion,
                                     String masterUsername, String masterPassword,
                                     String databaseName, boolean iamEnabled,
                                     String paramGroupName, String dbSubnetGroupName,
                                     String availabilityZone, boolean multiAz) {
        return createDbCluster(id, engineParam, engineVersion, masterUsername, masterPassword,
                databaseName, iamEnabled, paramGroupName, dbSubnetGroupName,
                availabilityZone, multiAz, regionResolver.getDefaultRegion());
    }

    public DbCluster createDbCluster(String id, String engineParam, String engineVersion,
                                     String masterUsername, String masterPassword,
                                     String databaseName, boolean iamEnabled,
                                     String paramGroupName, String dbSubnetGroupName,
                                     String availabilityZone, boolean multiAz, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "DB cluster " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        String engineIdentifier = normalizeEngineIdentifier(engineParam, engine);
        validateClusterParameterGroup(paramGroupName, engineParam, engineVersion);
        PlacementResolution placement = resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, effectiveRegion);

        boolean mock = config.services().rds().mock();
        // Always reserve a unique port (even in mock) so endpoints stay distinct and usedPorts
        // is consistent; mock mode only skips starting the container and auth proxy.
        int proxyPort = allocateProxyPort();
        DbEndpoint endpoint = mock ? new DbEndpoint("localhost", proxyPort) : proxyEndpoint(proxyPort);
        DbCluster cluster = new DbCluster(id, engine, engineVersion, masterUsername, masterPassword,
                databaseName, DbInstanceStatus.AVAILABLE, endpoint, endpoint,
                iamEnabled, new ArrayList<>(), paramGroupName, Instant.now(), proxyPort);
        cluster.setEngineIdentifier(engineIdentifier);
        if (!mock) {
            String image = imageForEngine(engine, engineVersion);
            String clusterVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
            RdsContainerHandle handle = containerManager.tryStart(id, clusterVolumeId, engine, image,
                    masterUsername, masterPassword, databaseName);
            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                cluster.setVolumeId(clusterVolumeId);
                cluster.setDockerVolumeName(volumeName(clusterVolumeId, id));
            } else {
                LOG.warnv("DB cluster {0} created without a backing database container: no Docker "
                        + "daemon is reachable. Metadata operations work; connections to the "
                        + "database do not until a daemon appears.", id);
            }
        }
        cluster.setDbSubnetGroupName(placement.dbSubnetGroupName());
        cluster.setVpcId(placement.vpcId());
        cluster.setAvailabilityZone(placement.availabilityZone());
        cluster.setMultiAz(placement.multiAz());
        cluster.setSubnetAvailabilityZones(placement.subnetAvailabilityZones());

        cluster.setDbClusterResourceId("cluster-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        cluster.setDbClusterArn(regionResolver.buildArn("rds", effectiveRegion, "cluster:" + id));

        if (!mock && hasBackend(cluster.getContainerHost(), cluster.getContainerPort())) {
            String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
            proxyManager.startProxy(id, engine, iamEnabled, proxyPort, cluster.getContainerHost(), cluster.getContainerPort(),
                    effectiveMasterUser, masterPassword, databaseName,
                    (user, pw) -> validateDbClusterPassword(id, user, pw));
        }

        clusters.put(id, cluster);
        LOG.infov("DB cluster {0} created (mock={1}), engine={2}, endpoint={3}:{4}",
                id, String.valueOf(mock), engine, endpoint.address(), String.valueOf(endpoint.port()));
        return cluster;
    }

    public DbCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));
    }

    public Collection<DbCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // DBClusterIdentifier also accepts an ARN per the AWS model. Match the
            // full ARN against each cluster's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local cluster.
            if (filterId.startsWith("arn:")) {
                return clusters.scan(k -> true).stream()
                        .filter(c -> filterId.equalsIgnoreCase(c.getDbClusterArn()))
                        .toList();
            }
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

        if (!config.services().rds().mock()) {
            proxyManager.stopProxy(id);
            if (cluster.getContainerId() != null) {
                containerManager.stop(buildClusterHandle(cluster));
                containerManager.removeVolume(id, cluster.getVolumeId());
            }
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(id);
        LOG.infov("DB cluster {0} deleted", id);
    }

    // ── DB Subnet Groups ──────────────────────────────────────────────────────

    public DbSubnetGroup createDbSubnetGroup(String name, String description, List<String> subnetIds) {
        return createDbSubnetGroup(name, description, subnetIds, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup createDbSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        return createDbSubnetGroup(name, description, subnetIds, Map.of(), region);
    }

    public DbSubnetGroup createDbSubnetGroup(String name, String description, List<String> subnetIds,
                                              Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter DBSubnetGroupName.", 400);
        }
        if (subnetGroups.get(name).isPresent() || "default".equalsIgnoreCase(name)) {
            throw new AwsException("DBSubnetGroupAlreadyExists",
                    "DB subnet group " + name + " already exists.", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter SubnetIds.", 400);
        }

        DbSubnetGroup group = buildSubnetGroup(name, description, subnetIds, effectiveRegion(region));
        // AWS always echoes back the Tags a CreateDBSubnetGroup request set, the same as every
        // other RDS resource - see lex00/floci#105.
        group.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        subnetGroups.put(name, group);
        return group;
    }

    public Collection<DbSubnetGroup> listDbSubnetGroups(String filterName) {
        return listDbSubnetGroups(filterName, regionResolver.getDefaultRegion());
    }

    public Collection<DbSubnetGroup> listDbSubnetGroups(String filterName, String region) {
        List<DbSubnetGroup> groups = new ArrayList<>();
        if (filterName == null || filterName.isBlank() || "default".equalsIgnoreCase(filterName)) {
            groups.add(buildDefaultSubnetGroup(effectiveRegion(region)));
        }
        if (filterName != null && !filterName.isBlank()) {
            if (!"default".equalsIgnoreCase(filterName)) {
                // Specific name: AWS DescribeDBSubnetGroups faults when absent (not empty 200).
                groups.add(resolveDbSubnetGroupView(filterName, region));
            }
            return groups;
        }
        groups.addAll(subnetGroups.scan(k -> true));
        return groups;
    }

    public DbSubnetGroup resolveDbSubnetGroupView(String name) {
        return resolveDbSubnetGroupView(name, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup resolveDbSubnetGroupView(String name, String region) {
        String effectiveName = (name == null || name.isBlank()) ? "default" : name;
        if ("default".equalsIgnoreCase(effectiveName)) {
            return buildDefaultSubnetGroup(effectiveRegion(region));
        }
        return subnetGroups.get(effectiveName).orElseThrow(() ->
                new AwsException("DBSubnetGroupNotFoundFault",
                        "DB subnet group " + effectiveName + " not found.", 404));
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    public DbParameterGroup createDbParameterGroup(String name, String family, String description) {
        return createDbParameterGroup(name, family, description, Map.of(), regionResolver.getDefaultRegion());
    }

    public DbParameterGroup createDbParameterGroup(String name, String family, String description,
                                                     Map<String, String> tags, String region) {
        if (parameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB parameter group " + name + " already exists.", 400);
        }
        DbParameterGroup group = new DbParameterGroup(name, family, description);
        group.setDbParameterGroupArn(regionResolver.buildArn("rds", effectiveRegion(region), "pg:" + name));
        group.setTags(tags);
        parameterGroups.put(name, group);
        return group;
    }

    public DbParameterGroup getDbParameterGroup(String name) {
        return parameterGroups.get(name).orElseThrow(() ->
                new AwsException("DBParameterGroupNotFound",
                        "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404));
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
                    "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404);
        }
        parameterGroups.delete(name);
    }

    public DbParameterGroup modifyDbParameterGroup(String name,
                                                    java.util.Map<String, String> parameters) {
        return modifyDbParameterGroup(name, parameters, Map.of());
    }

    // lex00/floci#120: see DbParameterGroup's own doc comment for the oracle/context.
    public DbParameterGroup modifyDbParameterGroup(String name, java.util.Map<String, String> parameters,
                                                    java.util.Map<String, String> applyMethods) {
        DbParameterGroup group = getDbParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        if (applyMethods != null) {
            group.getParameterApplyMethods().putAll(applyMethods);
        }
        parameterGroups.put(name, group);
        return group;
    }

    public DbSubnetGroup getDbSubnetGroup(String name) {
        return getDbSubnetGroup(name, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup getDbSubnetGroup(String name, String region) {
        if ("default".equalsIgnoreCase(name)) {
            return buildDefaultSubnetGroup(effectiveRegion(region));
        }
        return subnetGroups.get(name).orElseThrow(() ->
                new AwsException("DBSubnetGroupNotFoundFault",
                        "DB subnet group " + name + " not found.", 404));
    }

    public DbSubnetGroup modifyDbSubnetGroup(String name, List<String> subnetIds) {
        return modifyDbSubnetGroup(name, subnetIds, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup modifyDbSubnetGroup(String name, List<String> subnetIds, String region) {
        DbSubnetGroup existing = getDbSubnetGroup(name);
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "SubnetIds must contain at least one subnet.", 400);
        }
        DbSubnetGroup group = buildSubnetGroup(name, existing.getDescription(), subnetIds, effectiveRegion(region));
        group.setTags(existing.getTags());
        subnetGroups.put(name, group);
        return group;
    }

    public void deleteDbSubnetGroup(String name) {
        if (subnetGroups.get(name).isEmpty()) {
            throw new AwsException("DBSubnetGroupNotFoundFault",
                    "DB subnet group " + name + " not found.", 404);
        }
        subnetGroups.delete(name);
    }

    // ── Cluster Parameter Groups ──────────────────────────────────────────────

    public DbClusterParameterGroup createDbClusterParameterGroup(String name, String family, String description) {
        if (managedClusterParameterGroup(name) != null || clusterParameterGroups.get(name).isPresent()) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB cluster parameter group " + name + " already exists.", 400);
        }
        DbClusterParameterGroup group = new DbClusterParameterGroup(name, family, description);
        clusterParameterGroups.put(name, group);
        return group;
    }

    public DbClusterParameterGroup getDbClusterParameterGroup(String name) {
        if (name != null) {
            DbClusterParameterGroup persisted = clusterParameterGroups.get(name).orElse(null);
            if (persisted != null) {
                return persisted;
            }
            ManagedClusterParameterGroup managed = managedClusterParameterGroup(name);
            if (managed != null) {
                return managed.toModel();
            }
        }
        throw new AwsException("DBClusterParameterGroupNotFound",
                "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404);
    }

    public Collection<DbClusterParameterGroup> listDbClusterParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            try {
                return List.of(getDbClusterParameterGroup(filterName));
            } catch (AwsException e) {
                if ("DBClusterParameterGroupNotFound".equals(e.getErrorCode())) {
                    throw new AwsException("DBParameterGroupNotFound",
                            "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404);
                }
                throw e;
            }
        }
        Map<String, DbClusterParameterGroup> groups = new LinkedHashMap<>();
        for (ManagedClusterParameterGroup managed : MANAGED_CLUSTER_PARAMETER_GROUPS) {
            groups.put(managed.name(), managed.toModel());
        }
        clusterParameterGroups.scan(k -> true).stream()
                .sorted(Comparator.comparing(
                        DbClusterParameterGroup::getDbClusterParameterGroupName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(group -> groups.put(group.getDbClusterParameterGroupName(), group));
        return List.copyOf(groups.values());
    }

    private static ManagedClusterParameterGroup managedClusterParameterGroup(String name) {
        for (ManagedClusterParameterGroup group : MANAGED_CLUSTER_PARAMETER_GROUPS) {
            if (group.name().equals(name)) {
                return group;
            }
        }
        return null;
    }

    private static ManagedClusterParameterGroup managedDefault(String family) {
        return new ManagedClusterParameterGroup(
                "default." + family,
                family,
                "Default cluster parameter group");
    }

    private record ManagedClusterParameterGroup(String name, String family, String description) {
        private DbClusterParameterGroup toModel() {
            return new DbClusterParameterGroup(name, family, description);
        }
    }

    public void deleteDbClusterParameterGroup(String name) {
        if (managedClusterParameterGroup(name) != null) {
            throw new AwsException("InvalidDBParameterGroupState",
                    "The default DB cluster parameter group cannot be deleted.", 400);
        }
        if (clusterParameterGroups.get(name).isEmpty()) {
            throw new AwsException("DBClusterParameterGroupNotFound",
                    "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404);
        }
        clusterParameterGroups.delete(name);
    }

    public DbClusterParameterGroup modifyDbClusterParameterGroup(String name,
                                                                  java.util.Map<String, String> parameters) {
        if (managedClusterParameterGroup(name) != null) {
            throw new AwsException("InvalidDBParameterGroupState",
                    "The default DB cluster parameter group cannot be modified.", 400);
        }
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
            default -> throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
        };
    }

    // resolveEngine() collapses AWS's distinct engine identifiers (e.g. "aurora-mysql",
    // "aurora", "mysql") into the internal family used to pick a container image/protocol
    // handler. AWS's own API always echoes back the exact Engine value the caller supplied
    // (DescribeDBInstances/DescribeDBClusters.Engine matches CreateDBInstance/CreateDBCluster's
    // Engine verbatim) — a cluster instance created with Engine=aurora-mysql must still report
    // aurora-mysql, not the internal "mysql" family it happens to share a container with.
    private static String normalizeEngineIdentifier(String engineParam, DatabaseEngine resolved) {
        return engineParam != null && !engineParam.isBlank()
                ? engineParam.toLowerCase()
                : resolved.name().toLowerCase();
    }

    private String imageForEngine(DatabaseEngine engine, String engineVersion) {
        return switch (engine) {
            case POSTGRES -> config.services().rds().defaultPostgresImage()
                    .orElseGet(() -> imageForRequestedVersion(
                            EmulatorConfig.RdsServiceConfig.DEFAULT_POSTGRES_IMAGE, engineVersion));
            case MYSQL -> config.services().rds().defaultMysqlImage()
                    .orElseGet(() -> imageForRequestedVersion(
                            EmulatorConfig.RdsServiceConfig.DEFAULT_MYSQL_IMAGE, engineVersion));
            case MARIADB -> config.services().rds().defaultMariadbImage()
                    .orElseGet(() -> imageForRequestedVersion(
                            EmulatorConfig.RdsServiceConfig.DEFAULT_MARIADB_IMAGE, engineVersion));
        };
    }

    private void validateInstanceParameterGroup(String paramGroupName, String engineParam, String engineVersion) {
        if (paramGroupName == null || paramGroupName.isBlank()) {
            return;
        }
        DbParameterGroup group = getDbParameterGroup(paramGroupName);
        validateParameterGroupFamily(paramGroupName, group.getDbParameterGroupFamily(), engineParam, engineVersion);
    }

    private void validateClusterParameterGroup(String paramGroupName, String engineParam, String engineVersion) {
        if (paramGroupName == null || paramGroupName.isBlank()) {
            return;
        }
        DbClusterParameterGroup group = getDbClusterParameterGroup(paramGroupName);
        String family = group.getDbParameterGroupFamily();
        String expectedFamily = expectedClusterParameterGroupFamily(engineParam, engineVersion);
        if (family == null || !family.equalsIgnoreCase(expectedFamily)) {
            throw new AwsException("InvalidParameterCombination", invalidParameterCombinationMessage(), 400);
        }
    }

    private String expectedClusterParameterGroupFamily(String engineParam, String engineVersion) {
        String normalizedEngine = effectiveEngineName(engineParam).toLowerCase();
        String effectiveVersion = engineVersion;
        if (effectiveVersion == null || effectiveVersion.isBlank()) {
            effectiveVersion = switch (normalizedEngine) {
                case "postgres", "aurora-postgresql" -> "16.3";
                case "mysql", "aurora", "aurora-mysql" -> "8.0.36";
                case "mariadb" -> "11.2";
                default -> throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
            };
        }

        Matcher matcher = IMAGE_TAG_VERSION_PATTERN.matcher(effectiveVersion.trim());
        if (!matcher.matches()) {
            throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
        }
        String[] versionParts = matcher.group(1).split("\\.");
        String familyVersion = switch (normalizedEngine) {
            case "postgres", "aurora-postgresql" -> versionParts[0];
            case "mysql", "aurora", "aurora-mysql", "mariadb" -> {
                if (versionParts.length < 2) {
                    throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
                }
                yield versionParts[0] + "." + versionParts[1];
            }
            default -> throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
        };
        return expectedFamilyPrefix(normalizedEngine) + familyVersion;
    }

    private void validateParameterGroupFamily(String groupName, String family, String engineParam, String engineVersion) {
        String normalizedFamily = family == null ? "" : family.toLowerCase();
        String expectedPrefix = expectedFamilyPrefix(engineParam);
        if (!normalizedFamily.startsWith(expectedPrefix)) {
            throw new AwsException("InvalidParameterCombination", invalidParameterCombinationMessage(), 400);
        }
    }

    private String expectedFamilyPrefix(String engineParam) {
        String normalizedEngine = effectiveEngineName(engineParam).toLowerCase();
        return switch (normalizedEngine) {
            case "postgres" -> "postgres";
            case "aurora-postgresql" -> "aurora-postgresql";
            case "mysql" -> "mysql";
            case "aurora", "aurora-mysql" -> "aurora-mysql";
            case "mariadb" -> "mariadb";
            default -> throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
        };
    }

    private String effectiveEngineName(String engineParam) {
        return engineParam == null || engineParam.isBlank() ? "postgres" : engineParam;
    }

    private String invalidParameterValueMessage() {
        return "A value that you provided for a parameter isn't valid. Check the parameter constraints and try again.";
    }

    private String invalidParameterCombinationMessage() {
        return "Parameters that must not be used together were used together. Remove one of the conflicting parameters and try again.";
    }

    static String imageForRequestedVersion(String defaultImage, String engineVersion) {
        if (engineVersion == null || engineVersion.isBlank()) {
            return defaultImage;
        }

        String requestedTag = engineVersion.trim();
        if (!SAFE_IMAGE_TAG_PATTERN.matcher(requestedTag).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "Unsupported engine version tag: " + engineVersion, 400);
        }

        int tagSeparator = defaultImage.lastIndexOf(':');
        int lastSlash = defaultImage.lastIndexOf('/');
        if (tagSeparator <= lastSlash) {
            return defaultImage + ":" + requestedTag;
        }

        String imageName = defaultImage.substring(0, tagSeparator);
        String defaultTag = defaultImage.substring(tagSeparator + 1);
        Matcher matcher = IMAGE_TAG_VERSION_PATTERN.matcher(defaultTag);
        if (!matcher.matches()) {
            return imageName + ":" + requestedTag;
        }

        String suffix = matcher.group(2);
        if (!suffix.isEmpty() && !requestedTag.endsWith(suffix)) {
            requestedTag += suffix;
        }
        return imageName + ":" + requestedTag;
    }

    private int allocateProxyPort() {
        return allocateProxyPort(null);
    }

    // lex00/floci#120: Endpoint.Port previously always came from this method's own
    // base/max range (e.g. 7001/7002), completely ignoring whatever Port a client actually
    // requested - "a made-up, non-configured value, different per instance", worse than
    // absence per the issue's own framing. A `preferredPort` is honored outright when it's
    // free, which is the common case (most estates never collide on the same explicit port);
    // on collision this falls back to the existing range-scan exactly as before, since two
    // instances sharing one emulator host cannot both literally listen on the same port -
    // an unavoidable, honestly-documented limit of floci's shared-host proxy architecture,
    // not a regression relative to the prior always-different behavior.
    private int allocateProxyPort(Integer preferredPort) {
        if (preferredPort != null && preferredPort > 0 && usedPorts.add(preferredPort)) {
            return preferredPort;
        }
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

    private DbEndpoint proxyEndpoint(int proxyPort) {
        Optional<String> endpointHost = config.services().rds().endpointHost()
                .filter(host -> !host.isBlank());
        if (endpointHost.isEmpty()) {
            return new DbEndpoint(proxyEndpointHost(), proxyPort);
        }

        int endpointPort = currentContainerNetworkResolver == null
                ? proxyPort
                : currentContainerNetworkResolver.resolvePublishedPort(proxyPort).orElse(proxyPort);
        return new DbEndpoint(endpointHost.get(), endpointPort);
    }

    private String proxyEndpointHost() {
        return dockerHostResolver != null ? dockerHostResolver.resolve() : "localhost";
    }

    private void restoreClusters() {
        for (DbCluster cluster : allClusters()) {
            if (cluster.getStatus() == DbInstanceStatus.DELETING) {
                continue;
            }
            if (config.services().rds().mock()) {
                int mockPort = reserveOrAllocateProxyPort(cluster.getProxyPort());
                cluster.setProxyPort(mockPort);
                cluster.setEndpoint(new DbEndpoint("localhost", mockPort));
                cluster.setReaderEndpoint(new DbEndpoint("localhost", mockPort));
                cluster.setStatus(DbInstanceStatus.AVAILABLE);
                continue;
            }
            int proxyPort = reserveOrAllocateProxyPort(cluster.getProxyPort());
            cluster.setProxyPort(proxyPort);
            DbEndpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setEndpoint(endpoint);
            cluster.setReaderEndpoint(endpoint);
            if (cluster.getDockerVolumeName() == null) {
                cluster.setDockerVolumeName(volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier()));
            }
            try {
                String image = imageForEngine(cluster.getEngine(), cluster.getEngineVersion());
                RdsContainerHandle handle = containerManager.tryStart(cluster.getDbClusterIdentifier(),
                        cluster.getVolumeId(), cluster.getEngine(), image,
                        cluster.getMasterUsername(), cluster.getMasterPassword(), cluster.getDatabaseName());
                cluster.setContainerId(handle != null ? handle.getContainerId() : null);
                cluster.setContainerHost(handle != null ? handle.getHost() : null);
                cluster.setContainerPort(handle != null ? handle.getPort() : 0);

                if (handle != null) {
                    String effectiveMasterUser = cluster.getMasterUsername() != null
                            ? cluster.getMasterUsername() : "root";
                    proxyManager.startProxy(cluster.getDbClusterIdentifier(), cluster.getEngine(),
                            cluster.isIamDatabaseAuthenticationEnabled(), proxyPort,
                            handle.getHost(), handle.getPort(), effectiveMasterUser,
                            cluster.getMasterPassword(), cluster.getDatabaseName(),
                            (user, pw) -> validateDbClusterPassword(cluster.getDbClusterIdentifier(), user, pw));
                }
                // The cluster record survives a restart with no reachable Docker daemon; its
                // container is retried the next time something needs the live database.
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
            if (config.services().rds().mock()) {
                int mockPort = reserveOrAllocateProxyPort(instance.getProxyPort());
                instance.setProxyPort(mockPort);
                instance.setEndpoint(new DbEndpoint("localhost", mockPort));
                instance.setStatus(DbInstanceStatus.AVAILABLE);
                continue;
            }
            int proxyPort = reserveOrAllocateProxyPort(instance.getProxyPort());
            instance.setProxyPort(proxyPort);
            instance.setEndpoint(proxyEndpoint(proxyPort));
            try {
                String backendHost;
                int backendPort;
                String clusterId = instance.getDbClusterIdentifier();
                if (clusterId != null && !clusterId.isBlank()) {
                    DbCluster cluster = clusters.get(clusterId).orElseThrow(() ->
                            new AwsException("DBClusterNotFoundFault",
                                    "DB cluster " + clusterId + " not found.", 404));
                    // A cluster restored without a backing container leaves its members without one
                    // too; both are retried when something needs the live database.
                    backendHost = cluster.getContainerHost();
                    backendPort = cluster.getContainerPort();
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
                    String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
                    RdsContainerHandle handle = containerManager.tryStart(instance.getDbInstanceIdentifier(),
                            instance.getVolumeId(), instance.getEngine(), image,
                            instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
                    backendHost = handle != null ? handle.getHost() : null;
                    backendPort = handle != null ? handle.getPort() : 0;
                    instance.setContainerId(handle != null ? handle.getContainerId() : null);
                    instance.setContainerHost(backendHost);
                    instance.setContainerPort(backendPort);
                }

                if (hasBackend(backendHost, backendPort)) {
                    String effectiveMasterUser = instance.getMasterUsername() != null
                            ? instance.getMasterUsername() : "root";
                    proxyManager.startProxy(instance.getDbInstanceIdentifier(), instance.getEngine(),
                            instance.isIamDatabaseAuthenticationEnabled(), proxyPort,
                            backendHost, backendPort, effectiveMasterUser,
                            instance.getMasterPassword(), instance.getDbName(),
                            (user, pw) -> validateDbPassword(instance.getDbInstanceIdentifier(), user, pw));
                }
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

    private PlacementResolution resolvePlacement(String dbSubnetGroupName, String availabilityZone, boolean multiAz) {
        return resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, regionResolver.getDefaultRegion());
    }

    private PlacementResolution resolvePlacement(String dbSubnetGroupName, String availabilityZone, boolean multiAz,
                                                 String region) {
        String effectiveSubnetGroupName = (dbSubnetGroupName == null || dbSubnetGroupName.isBlank())
                ? "default"
                : dbSubnetGroupName;
        DbSubnetGroup group = "default".equals(effectiveSubnetGroupName)
                ? buildDefaultSubnetGroup(region)
                : subnetGroups.get(effectiveSubnetGroupName).orElseThrow(() ->
                        new AwsException("DBSubnetGroupNotFoundFault",
                                "DB subnet group " + effectiveSubnetGroupName + " not found.", 404));

        Map<String, String> subnetAvailabilityZones = group.getSubnetAvailabilityZones();
        String vpcId = group.getVpcId();

        if (multiAz && availabilityZone != null && !availabilityZone.isBlank()) {
            throw new AwsException("InvalidParameterCombination",
                    "AvailabilityZone cannot be specified when MultiAZ is enabled.", 400);
        }

        String effectiveAvailabilityZone = availabilityZone;
        if (effectiveAvailabilityZone == null || effectiveAvailabilityZone.isBlank()) {
            effectiveAvailabilityZone = subnetAvailabilityZones.values().stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(config.defaultAvailabilityZone());
        } else if (!subnetAvailabilityZones.containsValue(effectiveAvailabilityZone)) {
            throw new AwsException("InvalidVPCNetworkStateFault",
                    "Availability Zone " + effectiveAvailabilityZone
                            + " is not valid for DB subnet group " + effectiveSubnetGroupName + ".", 400);
        }

        if (multiAz) {
            long distinctZoneCount = subnetAvailabilityZones.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            if (distinctZoneCount < 2) {
                throw new AwsException("DBSubnetGroupDoesNotCoverEnoughAZs",
                        "DB subnet group " + effectiveSubnetGroupName
                                + " does not cover multiple Availability Zones.", 400);
            }
        }

        return new PlacementResolution(
                effectiveSubnetGroupName,
                vpcId,
                effectiveAvailabilityZone,
                multiAz,
                new LinkedHashMap<>(subnetAvailabilityZones));
    }

    private DbSubnetGroup buildDefaultSubnetGroup(String region) {
        List<Subnet> subnets = ec2Service.describeSubnets(region, List.of(), Map.of("vpc-id", List.of("vpc-default")));
        if (subnets.isEmpty()) {
            throw new AwsException("InvalidVPCNetworkStateFault",
                    "No subnets available for DB subnet group default.", 400);
        }
        return buildSubnetGroup("default", "default subnet group", extractSubnetIds(subnets), region);
    }

    private DbSubnetGroup buildSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        List<Subnet> resolvedSubnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        if (resolvedSubnets.size() != subnetIds.size()) {
            throw new AwsException("InvalidSubnet",
                    "One or more subnets for DB subnet group " + name + " do not exist.", 400);
        }

        String vpcId = resolvedSubnets.getFirst().getVpcId();
        boolean sameVpc = resolvedSubnets.stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidVPCNetworkStateFault",
                    "DB subnet group " + name + " contains subnets in multiple VPCs.", 400);
        }

        Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
        for (Subnet subnet : resolvedSubnets) {
            subnetAvailabilityZones.put(subnet.getSubnetId(), subnet.getAvailabilityZone());
        }

        DbSubnetGroup group = new DbSubnetGroup(name, description, vpcId, subnetIds, subnetAvailabilityZones);
        group.setDbSubnetGroupArn(regionResolver.buildArn("rds", region, "subgrp:" + name));
        group.setSubnetGroupStatus("Complete");
        return group;
    }

    private String effectiveRegion(String region) {
        return region == null || region.isBlank() ? regionResolver.getDefaultRegion() : region;
    }

    private static List<String> extractSubnetIds(List<Subnet> subnets) {
        return subnets.stream().map(Subnet::getSubnetId).toList();
    }

    private String volumeName(String volumeId, String fallbackId) {
        return ContainerStorageHelper.resourceName(config, "rds", volumeId, fallbackId);
    }

    private RdsContainerHandle buildHandle(DbInstance instance) {
        return new RdsContainerHandle(instance.getContainerId(), instance.getDbInstanceIdentifier(),
                instance.getContainerHost(), instance.getContainerPort());
    }

    private RdsContainerHandle buildClusterHandle(DbCluster cluster) {
        return new RdsContainerHandle(cluster.getContainerId(), cluster.getDbClusterIdentifier(),
                cluster.getContainerHost(), cluster.getContainerPort());
    }

    private record PlacementResolution(String dbSubnetGroupName, String vpcId, String availabilityZone,
                                       boolean multiAz, Map<String, String> subnetAvailabilityZones) {
        private static PlacementResolution fromCluster(DbCluster cluster) {
            return new PlacementResolution(
                    cluster.getDbSubnetGroupName(),
                    cluster.getVpcId(),
                    cluster.getAvailabilityZone(),
                    cluster.isMultiAz(),
                    cluster.getSubnetAvailabilityZones());
        }
    }
}
