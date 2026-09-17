package io.github.hectorvent.floci.services.timestreaminfluxdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbEndpoint;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbSetup;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbBackup;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbCluster;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbInstance;
import io.github.hectorvent.floci.services.timestreaminfluxdb.model.DbParameterGroup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;

import static io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbValidation.conflict;
import static io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbValidation.notFound;
import static io.github.hectorvent.floci.services.timestreaminfluxdb.TimestreamInfluxDbValidation.validation;

@ApplicationScoped
public class TimestreamInfluxDbService implements Resettable {

    static final String ENGINE_V2 = "INFLUXDB_V2";
    static final String ENGINE_V3_CORE = "INFLUXDB_V3_CORE";
    static final String ENGINE_V3_ENTERPRISE = "INFLUXDB_V3_ENTERPRISE";
    static final String DB_INSTANCE = "DB_INSTANCE";
    static final String DB_CLUSTER = "DB_CLUSTER";
    static final String DB_PARAMETER_GROUP = "DB_PARAMETER_GROUP";
    static final String DB_BACKUP = "DB_BACKUP";
    static final String AVAILABLE = "AVAILABLE";
    static final String LOCAL_ENDPOINT = "localhost";

    private static final Logger LOG = Logger.getLogger(TimestreamInfluxDbService.class);
    private static final String ARN_SERVICE = "timestream-influxdb";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_ORGANIZATION = "default";
    private static final String DEFAULT_BUCKET = "default";
    private static final int V2_PORT = 8086;
    private static final int V3_PORT = 8181;
    private static final int MAX_TAGS = 200;
    private static final int MAX_PAGE = 100;
    private static final List<String> REPLACE_EXISTING_REJECTED_MEMBERS = List.of(
            "vpcSubnetIds", "vpcSecurityGroupIds", "publiclyAccessible", "logDeliveryConfiguration",
            "maintenanceSchedule", "tags", "port", "networkType", "deploymentType", "dbBackupConfigurations",
            "kmsKeyId");

    public record Reported<T>(T resource, String status) {
    }

    public record RestoreResult(String restoredDbResourceId, String resourceType, String engineType,
                                String deploymentType) {
    }

    private final AccountAwareStorageBackend<DbInstance> instances;
    private final AccountAwareStorageBackend<DbCluster> clusters;
    private final AccountAwareStorageBackend<DbParameterGroup> parameterGroups;
    private final AccountAwareStorageBackend<DbBackup> backups;
    private final RegionResolver regionResolver;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;
    private final TimestreamInfluxDbContainerManager containerManager;
    private final boolean mock;
    private final Executor executor;

    @Inject
    public TimestreamInfluxDbService(StorageFactory storageFactory, RegionResolver regionResolver,
                                     SecretsManagerService secretsManagerService, ObjectMapper objectMapper,
                                     EmulatorConfig config, TimestreamInfluxDbContainerManager containerManager) {
        this(storageFactory.create("timestreaminfluxdb", "timestream-influxdb-instances.json",
                        new TypeReference<Map<String, DbInstance>>() {}),
                storageFactory.create("timestreaminfluxdb", "timestream-influxdb-clusters.json",
                        new TypeReference<Map<String, DbCluster>>() {}),
                storageFactory.create("timestreaminfluxdb", "timestream-influxdb-parameter-groups.json",
                        new TypeReference<Map<String, DbParameterGroup>>() {}),
                storageFactory.create("timestreaminfluxdb", "timestream-influxdb-backups.json",
                        new TypeReference<Map<String, DbBackup>>() {}),
                regionResolver, secretsManagerService, objectMapper, containerManager,
                config.services().timestreamInfluxdb().mock(),
                Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "timestream-influxdb-provisioner");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    TimestreamInfluxDbService(AccountAwareStorageBackend<DbInstance> instances,
                              AccountAwareStorageBackend<DbCluster> clusters,
                              AccountAwareStorageBackend<DbParameterGroup> parameterGroups,
                              AccountAwareStorageBackend<DbBackup> backups,
                              RegionResolver regionResolver, SecretsManagerService secretsManagerService,
                              ObjectMapper objectMapper, TimestreamInfluxDbContainerManager containerManager,
                              boolean mock, Executor executor) {
        this.instances = instances;
        this.clusters = clusters;
        this.parameterGroups = parameterGroups;
        this.backups = backups;
        this.regionResolver = regionResolver;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
        this.containerManager = containerManager;
        this.mock = mock;
        this.executor = executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdownNow();
        }
    }

    public synchronized Reported<DbInstance> createDbInstance(JsonNode request, String region) {
        String name = TimestreamInfluxDbValidation.requiredString(request, "name", 3, 40,
                TimestreamInfluxDbValidation.RESOURCE_NAME);
        String username = TimestreamInfluxDbValidation.optionalString(request, "username", 1, 64, null);
        String password = TimestreamInfluxDbValidation.password(request, true);
        String organization = TimestreamInfluxDbValidation.optionalString(request, "organization", 1, 64, null);
        String bucket = TimestreamInfluxDbValidation.bucket(request);
        String instanceType = TimestreamInfluxDbValidation.requiredEnum(request, "dbInstanceType",
                TimestreamInfluxDbValidation.INSTANCE_TYPES);
        List<String> subnets = TimestreamInfluxDbValidation.subnetIds(request, true);
        List<String> securityGroups = TimestreamInfluxDbValidation.securityGroupIds(request, true);
        Boolean publiclyAccessible = TimestreamInfluxDbValidation.optionalBoolean(request, "publiclyAccessible");
        String storageType = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "dbStorageType",
                TimestreamInfluxDbValidation.STORAGE_TYPES), "InfluxIOIncludedT1");
        Integer allocatedStorage = TimestreamInfluxDbValidation.requiredInt(request, "allocatedStorage", 20, 15360);
        TimestreamInfluxDbValidation.storageForType(storageType, allocatedStorage);
        String parameterGroupId = TimestreamInfluxDbValidation.optionalString(request, "dbParameterGroupIdentifier",
                3, 64, TimestreamInfluxDbValidation.IDENTIFIER);
        String deploymentType = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "deploymentType",
                TimestreamInfluxDbValidation.INSTANCE_DEPLOYMENT_TYPES), "SINGLE_AZ");
        JsonNode logDelivery = TimestreamInfluxDbValidation.logDeliveryConfiguration(request);
        JsonNode maintenance = TimestreamInfluxDbValidation.maintenanceSchedule(request);
        Map<String, String> tags = TimestreamInfluxDbValidation.tags(request, "tags", false);
        Integer port = TimestreamInfluxDbValidation.port(request);
        String networkType = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "networkType",
                TimestreamInfluxDbValidation.NETWORK_TYPES), "IPV4");
        JsonNode backupConfigurations = TimestreamInfluxDbValidation.dbBackupConfigurations(request);
        String kmsKeyId = TimestreamInfluxDbValidation.kmsKeyId(request);

        DbParameterGroup group = parameterGroupId == null ? null : requireParameterGroup(region, parameterGroupId);
        requireV2Group(group);
        requireUniqueInstanceName(region, name);

        String accountId = regionResolver.getAccountId();
        DbInstance instance = new DbInstance();
        instance.setId(newId());
        instance.setName(name);
        instance.setArn(arn(region, "db-instance", instance.getId()));
        instance.setAccountId(accountId);
        instance.setRegion(region);
        instance.setDbInstanceType(instanceType);
        instance.setVpcSubnetIds(subnets);
        instance.setVpcSecurityGroupIds(securityGroups);
        instance.setPubliclyAccessible(publiclyAccessible != null && publiclyAccessible);
        instance.setDbStorageType(storageType);
        instance.setAllocatedStorage(allocatedStorage);
        instance.setDbParameterGroupIdentifier(parameterGroupId);
        instance.setDeploymentType(deploymentType);
        instance.setLogDeliveryConfiguration(logDelivery);
        instance.setMaintenanceSchedule(maintenance);
        instance.setTags(tags);
        instance.setPort(port != null ? port : V2_PORT);
        instance.setNetworkType(networkType);
        instance.setDbBackupConfigurations(backupConfigurations);
        instance.setKmsKeyId(kmsKeyId);
        instance.setEngineType(ENGINE_V2);
        instance.setAvailabilityZone(region + "a");
        if ("WITH_MULTIAZ_STANDBY".equals(deploymentType)) {
            instance.setSecondaryAvailabilityZone(region + "b");
        }
        instance.setCreatedAt(Instant.now());

        InfluxDbSetup setup = new InfluxDbSetup(orDefault(username, DEFAULT_USERNAME), password,
                orDefault(organization, DEFAULT_ORGANIZATION), orDefault(bucket, DEFAULT_BUCKET));
        instance.setInfluxAuthParametersSecretArn(createAuthSecret(instance.getId(), setup, region));

        if (mock) {
            instance.setEndpoint(LOCAL_ENDPOINT);
            instance.setStatus(AVAILABLE);
            instances.put(key(region, instance.getId()), instance);
        } else {
            instance.setStatus("CREATING");
            instances.put(key(region, instance.getId()), instance);
            provision(DB_INSTANCE, accountId, region, instance.getId(), setup, engineEnvironment(group), null,
                    "FAILED");
        }
        return new Reported<>(instance, "CREATING");
    }

    public DbInstance getDbInstance(JsonNode request, String region) {
        return requireInstance(region, instanceIdentifier(request));
    }

    public PaginatedResult<DbInstance> listDbInstances(JsonNode request, String region) {
        return page(instances.scan(regionPrefix(region)), DbInstance::getId, request);
    }

    public synchronized Reported<DbInstance> updateDbInstance(JsonNode request, String region) {
        DbInstance instance = requireInstance(region, instanceIdentifier(request));
        JsonNode logDelivery = TimestreamInfluxDbValidation.logDeliveryConfiguration(request);
        String parameterGroupId = TimestreamInfluxDbValidation.optionalString(request, "dbParameterGroupIdentifier",
                3, 64, TimestreamInfluxDbValidation.IDENTIFIER);
        Integer port = TimestreamInfluxDbValidation.port(request);
        String instanceType = TimestreamInfluxDbValidation.optionalEnum(request, "dbInstanceType",
                TimestreamInfluxDbValidation.INSTANCE_TYPES);
        String deploymentType = TimestreamInfluxDbValidation.optionalEnum(request, "deploymentType",
                TimestreamInfluxDbValidation.INSTANCE_DEPLOYMENT_TYPES);
        String storageType = TimestreamInfluxDbValidation.optionalEnum(request, "dbStorageType",
                TimestreamInfluxDbValidation.STORAGE_TYPES);
        Integer allocatedStorage = TimestreamInfluxDbValidation.optionalInt(request, "allocatedStorage", 20, 15360);
        JsonNode maintenance = TimestreamInfluxDbValidation.maintenanceSchedule(request);
        JsonNode backupConfigurations = TimestreamInfluxDbValidation.dbBackupConfigurations(request);

        requireStandalone(instance);
        DbParameterGroup group = parameterGroupId == null ? null : requireParameterGroup(region, parameterGroupId);
        requireV2Group(group);
        TimestreamInfluxDbValidation.storageForType(orDefault(storageType, instance.getDbStorageType()),
                allocatedStorage != null ? allocatedStorage : instance.getAllocatedStorage());

        String status = "UPDATING";
        if (instanceType != null && !instanceType.equals(instance.getDbInstanceType())) {
            status = "UPDATING_INSTANCE_TYPE";
        } else if (deploymentType != null && !deploymentType.equals(instance.getDeploymentType())) {
            status = "UPDATING_DEPLOYMENT_TYPE";
        }
        boolean parameterGroupChanged = parameterGroupId != null
                && !parameterGroupId.equals(instance.getDbParameterGroupIdentifier());

        setIfPresent(logDelivery, instance::setLogDeliveryConfiguration);
        setIfPresent(parameterGroupId, instance::setDbParameterGroupIdentifier);
        setIfPresent(port, instance::setPort);
        setIfPresent(instanceType, instance::setDbInstanceType);
        setIfPresent(storageType, instance::setDbStorageType);
        setIfPresent(allocatedStorage, instance::setAllocatedStorage);
        setIfPresent(maintenance, instance::setMaintenanceSchedule);
        setIfPresent(backupConfigurations, instance::setDbBackupConfigurations);
        if (deploymentType != null) {
            instance.setDeploymentType(deploymentType);
            instance.setSecondaryAvailabilityZone("WITH_MULTIAZ_STANDBY".equals(deploymentType) ? region + "b" : null);
        }

        if (parameterGroupChanged && hasDataPlane(instance.getContainerId())) {
            instance.setStatus(status);
            instances.put(key(region, instance.getId()), instance);
            provision(DB_INSTANCE, instance.getAccountId(), region, instance.getId(), null, engineEnvironment(group),
                    null, "FAILED");
        } else {
            instance.setStatus(AVAILABLE);
            instances.put(key(region, instance.getId()), instance);
        }
        return new Reported<>(instance, status);
    }

    public synchronized Reported<DbInstance> rebootDbInstance(JsonNode request, String region) {
        DbInstance instance = requireInstance(region, instanceIdentifier(request));
        String containerId = instance.getDbClusterId() == null
                ? instance.getContainerId()
                : clusterContainerId(region, instance.getDbClusterId());
        if (hasDataPlane(containerId)) {
            instance.setStatus("REBOOTING");
            instances.put(key(region, instance.getId()), instance);
            restartAsync(DB_INSTANCE, instance.getAccountId(), region, instance.getId(),
                    new InfluxDbEndpoint(containerId, instance.getEndpoint(), instance.getPort()));
        }
        return new Reported<>(instance, "REBOOTING");
    }

    public synchronized Reported<DbInstance> deleteDbInstance(JsonNode request, String region) {
        DbInstance instance = requireInstance(region, instanceIdentifier(request));
        TimestreamInfluxDbValidation.optionalBoolean(request, "retainAutomatedBackups");
        if (instance.getDbClusterId() != null) {
            throw conflict(DB_INSTANCE, instance.getId(), "DB instance " + instance.getId()
                    + " belongs to DB cluster " + instance.getDbClusterId()
                    + "; nodes can't be removed individually, delete the DB cluster instead.");
        }
        removeDataPlane(instance.getId(), instance.getContainerId());
        instances.delete(key(region, instance.getId()));
        return new Reported<>(instance, "DELETING");
    }

    public synchronized Reported<DbCluster> createDbCluster(JsonNode request, String region) {
        String name = TimestreamInfluxDbValidation.requiredString(request, "name", 3, 40,
                TimestreamInfluxDbValidation.CLUSTER_NAME);
        String username = TimestreamInfluxDbValidation.optionalString(request, "username", 1, 64, null);
        String password = TimestreamInfluxDbValidation.password(request, false);
        String organization = TimestreamInfluxDbValidation.optionalString(request, "organization", 1, 64, null);
        String bucket = TimestreamInfluxDbValidation.bucket(request);
        Integer port = TimestreamInfluxDbValidation.port(request);
        String parameterGroupId = TimestreamInfluxDbValidation.optionalString(request, "dbParameterGroupIdentifier",
                3, 64, TimestreamInfluxDbValidation.IDENTIFIER);
        String instanceType = TimestreamInfluxDbValidation.requiredEnum(request, "dbInstanceType",
                TimestreamInfluxDbValidation.INSTANCE_TYPES);
        String storageType = TimestreamInfluxDbValidation.optionalEnum(request, "dbStorageType",
                TimestreamInfluxDbValidation.STORAGE_TYPES);
        Integer allocatedStorage = TimestreamInfluxDbValidation.optionalInt(request, "allocatedStorage", 20, 15360);
        String networkType = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "networkType",
                TimestreamInfluxDbValidation.NETWORK_TYPES), "IPV4");
        Boolean publiclyAccessible = TimestreamInfluxDbValidation.optionalBoolean(request, "publiclyAccessible");
        List<String> subnets = TimestreamInfluxDbValidation.subnetIds(request, true);
        List<String> securityGroups = TimestreamInfluxDbValidation.securityGroupIds(request, true);
        String deploymentType = TimestreamInfluxDbValidation.optionalEnum(request, "deploymentType",
                TimestreamInfluxDbValidation.CLUSTER_DEPLOYMENT_TYPES);
        String failoverMode = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "failoverMode",
                TimestreamInfluxDbValidation.FAILOVER_MODES), "AUTOMATIC");
        JsonNode logDelivery = TimestreamInfluxDbValidation.logDeliveryConfiguration(request);
        JsonNode maintenance = TimestreamInfluxDbValidation.maintenanceSchedule(request);
        JsonNode backupConfigurations = TimestreamInfluxDbValidation.dbBackupConfigurations(request);
        String kmsKeyId = TimestreamInfluxDbValidation.kmsKeyId(request);
        Map<String, String> tags = TimestreamInfluxDbValidation.tags(request, "tags", false);
        if (storageType != null) {
            TimestreamInfluxDbValidation.storageForType(storageType, allocatedStorage);
        }

        DbParameterGroup group = parameterGroupId == null ? null : requireParameterGroup(region, parameterGroupId);
        String engineType = group == null ? ENGINE_V2 : group.getEngineType();
        requireUniqueClusterName(region, name);

        String accountId = regionResolver.getAccountId();
        DbCluster cluster = new DbCluster();
        cluster.setId(newId());
        cluster.setName(name);
        cluster.setArn(arn(region, "db-cluster", cluster.getId()));
        cluster.setAccountId(accountId);
        cluster.setRegion(region);
        cluster.setEngineType(engineType);
        cluster.setPort(port != null ? port : ENGINE_V2.equals(engineType) ? V2_PORT : V3_PORT);
        cluster.setDbParameterGroupIdentifier(parameterGroupId);
        cluster.setDbInstanceType(instanceType);
        cluster.setDbStorageType(storageType != null ? storageType
                : ENGINE_V2.equals(engineType) ? "InfluxIOIncludedT1" : null);
        cluster.setAllocatedStorage(allocatedStorage);
        cluster.setNetworkType(networkType);
        cluster.setPubliclyAccessible(publiclyAccessible != null && publiclyAccessible);
        cluster.setVpcSubnetIds(subnets);
        cluster.setVpcSecurityGroupIds(securityGroups);
        cluster.setDeploymentType(deploymentType != null ? deploymentType
                : ENGINE_V2.equals(engineType) ? "MULTI_NODE_READ_REPLICAS" : null);
        cluster.setFailoverMode(failoverMode);
        cluster.setLogDeliveryConfiguration(logDelivery);
        cluster.setMaintenanceSchedule(maintenance);
        cluster.setDbBackupConfigurations(backupConfigurations);
        cluster.setKmsKeyId(kmsKeyId);
        cluster.setTags(tags);
        cluster.setCreatedAt(Instant.now());
        if (ENGINE_V3_ENTERPRISE.equals(engineType)) {
            cluster.setClusterConfiguration(clusterConfiguration(group.getParameters().get("InfluxDBv3Enterprise")));
        }

        InfluxDbSetup setup = password == null ? null : new InfluxDbSetup(orDefault(username, DEFAULT_USERNAME),
                password, orDefault(organization, DEFAULT_ORGANIZATION), orDefault(bucket, DEFAULT_BUCKET));
        if (setup != null) {
            cluster.setInfluxAuthParametersSecretArn(createAuthSecret(cluster.getId(), setup, region));
        }

        boolean containerBacked = !mock && ENGINE_V2.equals(engineType) && setup != null;
        cluster.setStatus(containerBacked ? "CREATING" : AVAILABLE);
        if (!containerBacked) {
            cluster.setEndpoint(LOCAL_ENDPOINT);
            cluster.setReaderEndpoint(LOCAL_ENDPOINT);
            if (!mock) {
                LOG.infov("DB cluster {0} uses engine {1}{2}; it has no InfluxDB 2.x data plane container",
                        cluster.getId(), engineType, setup == null ? " without a password" : "");
            }
        }
        clusters.put(key(region, cluster.getId()), cluster);
        for (DbInstance member : buildMembers(cluster, group)) {
            instances.put(key(region, member.getId()), member);
        }
        if (containerBacked) {
            provision(DB_CLUSTER, accountId, region, cluster.getId(), setup, engineEnvironment(group), null, "FAILED");
        }
        return new Reported<>(cluster, "CREATING");
    }

    public DbCluster getDbCluster(JsonNode request, String region) {
        return requireCluster(region, clusterIdentifier(request));
    }

    public PaginatedResult<DbCluster> listDbClusters(JsonNode request, String region) {
        return page(clusters.scan(regionPrefix(region)), DbCluster::getId, request);
    }

    public PaginatedResult<DbInstance> listDbInstancesForCluster(JsonNode request, String region) {
        DbCluster cluster = requireCluster(region, clusterIdentifier(request));
        return page(clusterMembers(region, cluster.getId()), DbInstance::getId, request);
    }

    public synchronized Reported<DbCluster> updateDbCluster(JsonNode request, String region) {
        DbCluster cluster = requireCluster(region, clusterIdentifier(request));
        JsonNode logDelivery = TimestreamInfluxDbValidation.logDeliveryConfiguration(request);
        String parameterGroupId = TimestreamInfluxDbValidation.optionalString(request, "dbParameterGroupIdentifier",
                3, 64, TimestreamInfluxDbValidation.IDENTIFIER);
        Integer port = TimestreamInfluxDbValidation.port(request);
        String instanceType = TimestreamInfluxDbValidation.optionalEnum(request, "dbInstanceType",
                TimestreamInfluxDbValidation.INSTANCE_TYPES);
        String failoverMode = TimestreamInfluxDbValidation.optionalEnum(request, "failoverMode",
                TimestreamInfluxDbValidation.FAILOVER_MODES);
        JsonNode maintenance = TimestreamInfluxDbValidation.maintenanceSchedule(request);
        JsonNode backupConfigurations = TimestreamInfluxDbValidation.dbBackupConfigurations(request);

        DbParameterGroup group = parameterGroupId == null ? null : requireParameterGroup(region, parameterGroupId);
        if (group != null && !group.getEngineType().equals(cluster.getEngineType())) {
            throw validation("The DB parameter group engine " + group.getEngineType()
                    + " does not match the DB cluster engine " + cluster.getEngineType() + ".");
        }
        String status = instanceType != null && !instanceType.equals(cluster.getDbInstanceType())
                ? "UPDATING_INSTANCE_TYPE" : "UPDATING";
        boolean parameterGroupChanged = parameterGroupId != null
                && !parameterGroupId.equals(cluster.getDbParameterGroupIdentifier());

        setIfPresent(logDelivery, cluster::setLogDeliveryConfiguration);
        setIfPresent(parameterGroupId, cluster::setDbParameterGroupIdentifier);
        setIfPresent(port, cluster::setPort);
        setIfPresent(instanceType, cluster::setDbInstanceType);
        setIfPresent(failoverMode, cluster::setFailoverMode);
        setIfPresent(maintenance, cluster::setMaintenanceSchedule);
        setIfPresent(backupConfigurations, cluster::setDbBackupConfigurations);

        boolean recreate = parameterGroupChanged && hasDataPlane(cluster.getContainerId());
        String storedStatus = recreate ? status : AVAILABLE;
        cluster.setStatus(storedStatus);
        clusters.put(key(region, cluster.getId()), cluster);
        for (DbInstance member : clusterMembers(region, cluster.getId())) {
            setIfPresent(logDelivery, member::setLogDeliveryConfiguration);
            setIfPresent(parameterGroupId, member::setDbParameterGroupIdentifier);
            setIfPresent(port, member::setPort);
            setIfPresent(instanceType, member::setDbInstanceType);
            setIfPresent(maintenance, member::setMaintenanceSchedule);
            member.setStatus(storedStatus);
            instances.put(key(region, member.getId()), member);
        }
        if (recreate) {
            provision(DB_CLUSTER, cluster.getAccountId(), region, cluster.getId(), null, engineEnvironment(group),
                    null, "FAILED");
        }
        return new Reported<>(cluster, status);
    }

    public synchronized Reported<DbCluster> rebootDbCluster(JsonNode request, String region) {
        DbCluster cluster = requireCluster(region, clusterIdentifier(request));
        List<String> instanceIds = TimestreamInfluxDbValidation.stringList(request, "instanceIds", false, 0, 3,
                TimestreamInfluxDbValidation.IDENTIFIER);
        if (instanceIds != null) {
            List<String> memberIds = clusterMembers(region, cluster.getId()).stream().map(DbInstance::getId).toList();
            for (String instanceId : instanceIds) {
                if (!memberIds.contains(instanceId)) {
                    throw notFound(DB_INSTANCE, instanceId);
                }
            }
        }
        if (hasDataPlane(cluster.getContainerId())) {
            cluster.setStatus("REBOOTING");
            clusters.put(key(region, cluster.getId()), cluster);
            restartAsync(DB_CLUSTER, cluster.getAccountId(), region, cluster.getId(),
                    new InfluxDbEndpoint(cluster.getContainerId(), cluster.getEndpoint(), cluster.getPort()));
        }
        return new Reported<>(cluster, "REBOOTING");
    }

    public synchronized Reported<DbCluster> deleteDbCluster(JsonNode request, String region) {
        DbCluster cluster = requireCluster(region, clusterIdentifier(request));
        TimestreamInfluxDbValidation.optionalBoolean(request, "retainAutomatedBackups");
        removeDataPlane(cluster.getId(), cluster.getContainerId());
        for (DbInstance member : clusterMembers(region, cluster.getId())) {
            instances.delete(key(region, member.getId()));
        }
        clusters.delete(key(region, cluster.getId()));
        return new Reported<>(cluster, "DELETING");
    }

    public synchronized DbParameterGroup createDbParameterGroup(JsonNode request, String region) {
        String name = TimestreamInfluxDbValidation.requiredString(request, "name", 3, 64,
                TimestreamInfluxDbValidation.RESOURCE_NAME);
        String description = TimestreamInfluxDbValidation.optionalString(request, "description", 0, 500, null);
        JsonNode parameters = request.get("parameters");
        String engineType = TimestreamInfluxDbValidation.parameters(parameters);
        Map<String, String> tags = TimestreamInfluxDbValidation.tags(request, "tags", false);
        parameterGroups.scan(regionPrefix(region)).stream()
                .filter(existing -> name.equals(existing.getName()))
                .findFirst()
                .ifPresent(existing -> {
                    throw conflict(DB_PARAMETER_GROUP, existing.getId(),
                            "A DB parameter group named " + name + " already exists.");
                });

        DbParameterGroup group = new DbParameterGroup();
        group.setId(newId());
        group.setName(name);
        group.setArn(arn(region, "db-parameter-group", group.getId()));
        group.setDescription(description);
        group.setParameters(parameters == null || parameters.isNull() ? null : parameters);
        group.setEngineType(engineType);
        group.setTags(tags);
        group.setCreatedAt(Instant.now());
        parameterGroups.put(key(region, group.getId()), group);
        return group;
    }

    public DbParameterGroup getDbParameterGroup(JsonNode request, String region) {
        String identifier = TimestreamInfluxDbValidation.requiredString(request, "identifier", 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
        return requireParameterGroup(region, identifier);
    }

    public PaginatedResult<DbParameterGroup> listDbParameterGroups(JsonNode request, String region) {
        return page(parameterGroups.scan(regionPrefix(region)), DbParameterGroup::getId, request);
    }

    public synchronized Reported<DbBackup> createDbBackup(JsonNode request, String region) {
        String name = TimestreamInfluxDbValidation.requiredString(request, "name", 3, 40,
                TimestreamInfluxDbValidation.RESOURCE_NAME);
        String resourceId = TimestreamInfluxDbValidation.requiredString(request, "dbResourceId", 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
        Integer retentionDays = TimestreamInfluxDbValidation.optionalInt(request, "retentionDays", 1, 3650);
        Map<String, String> tags = TimestreamInfluxDbValidation.tags(request, "tags", false);
        backups.scan(regionPrefix(region)).stream()
                .filter(existing -> name.equals(existing.getName()))
                .findFirst()
                .ifPresent(existing -> {
                    throw conflict(DB_BACKUP, existing.getId(), "A backup named " + name + " already exists.");
                });

        DbBackup backup = new DbBackup();
        String containerId;
        String resourceStatus;
        Optional<DbInstance> instance = instances.get(key(region, resourceId));
        if (instance.isPresent()) {
            DbInstance source = instance.get();
            snapshotInstance(backup, source);
            containerId = source.getDbClusterId() == null
                    ? source.getContainerId() : clusterContainerId(region, source.getDbClusterId());
            resourceStatus = source.getStatus();
        } else {
            DbCluster source = clusters.get(key(region, resourceId))
                    .orElseThrow(() -> notFound(DB_INSTANCE, resourceId));
            snapshotCluster(backup, source);
            containerId = source.getContainerId();
            resourceStatus = source.getStatus();
        }
        if (!AVAILABLE.equals(resourceStatus)) {
            throw conflict(backup.getResourceType(), resourceId,
                    "Resource " + resourceId + " must be AVAILABLE to take a backup; it is " + resourceStatus + ".");
        }

        Instant now = Instant.now();
        backup.setId(newId());
        backup.setName(name);
        backup.setArn(arn(region, "db-backup", backup.getId()));
        backup.setAccountId(regionResolver.getAccountId());
        backup.setRegion(region);
        backup.setCreatedAt(now);
        backup.setType("ON_DEMAND");
        backup.setDbResourceId(resourceId);
        backup.setTags(tags);
        if (retentionDays != null) {
            backup.setExpiresAfter(LocalDate.ofInstant(now.plus(retentionDays, ChronoUnit.DAYS), ZoneOffset.UTC)
                    .toString());
        }

        if (hasDataPlane(containerId)) {
            backup.setStatus("IN_PROGRESS");
            backups.put(key(region, backup.getId()), backup);
            captureBackupAsync(backup.getAccountId(), region, backup.getId(), containerId);
        } else {
            backup.setStatus("COMPLETED");
            backups.put(key(region, backup.getId()), backup);
        }
        return new Reported<>(backup, "IN_PROGRESS");
    }

    public DbBackup getDbBackup(JsonNode request, String region) {
        return requireBackup(region, backupIdentifier(request, "identifier"));
    }

    public PaginatedResult<DbBackup> listDbBackups(JsonNode request, String region) {
        String resourceId = TimestreamInfluxDbValidation.optionalString(request, "dbResourceId", 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
        List<DbBackup> items = backups.scan(regionPrefix(region)).stream()
                .filter(backup -> resourceId == null || resourceId.equals(backup.getDbResourceId()))
                .toList();
        return page(new ArrayList<>(items), DbBackup::getId, request);
    }

    public synchronized Reported<DbBackup> deleteDbBackup(JsonNode request, String region) {
        DbBackup backup = requireBackup(region, backupIdentifier(request, "identifier"));
        backups.delete(key(region, backup.getId()));
        if (backup.isDataCaptured()) {
            containerManager.deleteBackupArtifacts(backup.getId());
        }
        return new Reported<>(backup, "DELETING");
    }

    public synchronized RestoreResult restoreFromDbBackup(JsonNode request, String region) {
        String name = TimestreamInfluxDbValidation.requiredString(request, "name", 3, 40,
                TimestreamInfluxDbValidation.RESOURCE_NAME);
        DbBackup backup = requireBackup(region, backupIdentifier(request, "dbBackupId"));
        JsonNode restoreToTime = request.get("restoreToTime");
        if (restoreToTime != null && !restoreToTime.isNull() && !restoreToTime.isNumber() && !restoreToTime.isTextual()) {
            throw validation("restoreToTime must be a timestamp.");
        }
        if (restoreToTime != null && !restoreToTime.isNull()) {
            throw validation("restoreToTime requests a point-in-time restore, which is only available for "
                    + "continuous backups and is not supported.");
        }
        String restoreMode = orDefault(TimestreamInfluxDbValidation.optionalEnum(request, "restoreMode",
                TimestreamInfluxDbValidation.RESTORE_MODES), "NEW_RESOURCE");
        List<String> subnets = TimestreamInfluxDbValidation.subnetIds(request, false);
        List<String> securityGroups = TimestreamInfluxDbValidation.securityGroupIds(request, false);
        Boolean publiclyAccessible = TimestreamInfluxDbValidation.optionalBoolean(request, "publiclyAccessible");
        JsonNode logDelivery = TimestreamInfluxDbValidation.logDeliveryConfiguration(request);
        JsonNode maintenance = TimestreamInfluxDbValidation.maintenanceSchedule(request);
        Map<String, String> tags = TimestreamInfluxDbValidation.tags(request, "tags", false);
        Integer port = TimestreamInfluxDbValidation.port(request);
        String networkType = TimestreamInfluxDbValidation.optionalEnum(request, "networkType",
                TimestreamInfluxDbValidation.NETWORK_TYPES);
        String deploymentType = TimestreamInfluxDbValidation.optionalEnum(request, "deploymentType",
                TimestreamInfluxDbValidation.RESOURCE_DEPLOYMENT_TYPES);
        JsonNode backupConfigurations = TimestreamInfluxDbValidation.dbBackupConfigurations(request);
        String kmsKeyId = TimestreamInfluxDbValidation.kmsKeyId(request);

        if (!"COMPLETED".equals(backup.getStatus())) {
            throw conflict(DB_BACKUP, backup.getId(), "Backup " + backup.getId() + " is not COMPLETED.");
        }
        boolean clusterBackup = DB_CLUSTER.equals(backup.getResourceType());
        String effectiveDeployment = orDefault(deploymentType, backup.getDeploymentType());
        if (deploymentType != null && clusterBackup != "MULTI_NODE_READ_REPLICAS".equals(deploymentType)) {
            throw validation("deploymentType " + deploymentType + " is not valid for a "
                    + backup.getResourceType() + " backup.");
        }

        if ("REPLACE_EXISTING".equals(restoreMode)) {
            rejectReplaceExistingOverrides(request);
            return replaceExisting(backup, region, name, clusterBackup);
        }

        if (clusterBackup) {
            requireUniqueClusterName(region, name);
            DbCluster cluster = new DbCluster();
            cluster.setId(newId());
            cluster.setName(name);
            cluster.setArn(arn(region, "db-cluster", cluster.getId()));
            cluster.setAccountId(backup.getAccountId());
            cluster.setRegion(region);
            cluster.setEngineType(backup.getEngineType());
            cluster.setDeploymentType(effectiveDeployment);
            cluster.setDbInstanceType(backup.getDbInstanceType());
            cluster.setDbStorageType(backup.getDbStorageType());
            cluster.setAllocatedStorage(backup.getAllocatedStorage());
            cluster.setDbParameterGroupIdentifier(backup.getDbParameterGroupId());
            cluster.setFailoverMode(backup.getFailoverMode());
            cluster.setClusterConfiguration(backup.getClusterConfiguration());
            cluster.setInfluxAuthParametersSecretArn(backup.getInfluxAuthParametersSecretArn());
            cluster.setPort(port != null ? port : backup.getPort());
            cluster.setNetworkType(orDefault(networkType, backup.getNetworkType()));
            cluster.setPubliclyAccessible(publiclyAccessible != null ? publiclyAccessible : backup.getPubliclyAccessible());
            cluster.setVpcSubnetIds(subnets != null ? subnets : backup.getVpcSubnetIds());
            cluster.setVpcSecurityGroupIds(securityGroups != null ? securityGroups : backup.getVpcSecurityGroupIds());
            cluster.setLogDeliveryConfiguration(logDelivery != null ? logDelivery : backup.getLogDeliveryConfiguration());
            cluster.setMaintenanceSchedule(maintenance != null ? maintenance : backup.getMaintenanceSchedule());
            cluster.setDbBackupConfigurations(backupConfigurations != null
                    ? backupConfigurations : backup.getDbBackupConfigurations());
            cluster.setKmsKeyId(orDefault(kmsKeyId, backup.getKmsKeyId()));
            cluster.setTags(tags);
            cluster.setCreatedAt(Instant.now());
            boolean restoreData = restoresData(backup);
            cluster.setStatus(restoreData ? "RESTORING" : AVAILABLE);
            cluster.setEndpoint(restoreData ? null : LOCAL_ENDPOINT);
            cluster.setReaderEndpoint(restoreData ? null : LOCAL_ENDPOINT);
            clusters.put(key(region, cluster.getId()), cluster);
            DbParameterGroup group = parameterGroups.get(key(region, orDefault(backup.getDbParameterGroupId(), "")))
                    .orElse(null);
            for (DbInstance member : buildMembers(cluster, group)) {
                instances.put(key(region, member.getId()), member);
            }
            if (restoreData) {
                provision(DB_CLUSTER, cluster.getAccountId(), region, cluster.getId(), temporarySetup(),
                        engineEnvironment(group), backup.getId(), "RESTORE_FAILED");
            }
            return new RestoreResult(cluster.getId(), DB_CLUSTER, cluster.getEngineType(), cluster.getDeploymentType());
        }

        requireUniqueInstanceName(region, name);
        DbInstance instance = new DbInstance();
        instance.setId(newId());
        instance.setName(name);
        instance.setArn(arn(region, "db-instance", instance.getId()));
        instance.setAccountId(backup.getAccountId());
        instance.setRegion(region);
        instance.setEngineType(backup.getEngineType());
        instance.setDeploymentType(effectiveDeployment);
        instance.setDbInstanceType(backup.getDbInstanceType());
        instance.setDbStorageType(backup.getDbStorageType());
        instance.setAllocatedStorage(backup.getAllocatedStorage());
        instance.setDbParameterGroupIdentifier(backup.getDbParameterGroupId());
        instance.setInfluxAuthParametersSecretArn(backup.getInfluxAuthParametersSecretArn());
        instance.setPort(port != null ? port : backup.getPort());
        instance.setNetworkType(orDefault(networkType, backup.getNetworkType()));
        instance.setPubliclyAccessible(publiclyAccessible != null ? publiclyAccessible : backup.getPubliclyAccessible());
        instance.setVpcSubnetIds(subnets != null ? subnets : backup.getVpcSubnetIds());
        instance.setVpcSecurityGroupIds(securityGroups != null ? securityGroups : backup.getVpcSecurityGroupIds());
        instance.setLogDeliveryConfiguration(logDelivery != null ? logDelivery : backup.getLogDeliveryConfiguration());
        instance.setMaintenanceSchedule(maintenance != null ? maintenance : backup.getMaintenanceSchedule());
        instance.setDbBackupConfigurations(backupConfigurations != null
                ? backupConfigurations : backup.getDbBackupConfigurations());
        instance.setKmsKeyId(orDefault(kmsKeyId, backup.getKmsKeyId()));
        instance.setAvailabilityZone(region + "a");
        if ("WITH_MULTIAZ_STANDBY".equals(effectiveDeployment)) {
            instance.setSecondaryAvailabilityZone(region + "b");
        }
        instance.setTags(tags);
        instance.setCreatedAt(Instant.now());
        boolean restoreData = restoresData(backup);
        instance.setStatus(restoreData ? "RESTORING" : AVAILABLE);
        instance.setEndpoint(restoreData ? null : LOCAL_ENDPOINT);
        instances.put(key(region, instance.getId()), instance);
        if (restoreData) {
            DbParameterGroup group = parameterGroups.get(key(region, orDefault(backup.getDbParameterGroupId(), "")))
                    .orElse(null);
            provision(DB_INSTANCE, instance.getAccountId(), region, instance.getId(), temporarySetup(),
                    engineEnvironment(group), backup.getId(), "RESTORE_FAILED");
        }
        return new RestoreResult(instance.getId(), DB_INSTANCE, instance.getEngineType(), instance.getDeploymentType());
    }

    public synchronized Map<String, String> listTagsForResource(JsonNode request) {
        return tagsOf(TimestreamInfluxDbValidation.requiredString(request, "resourceArn", 1, 1011, null));
    }

    public synchronized void tagResource(JsonNode request) {
        String arn = TimestreamInfluxDbValidation.requiredString(request, "resourceArn", 1, 1011, null);
        Map<String, String> added = TimestreamInfluxDbValidation.tags(request, "tags", true);
        Map<String, String> tags = tagsOf(arn);
        tags.putAll(added);
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        saveTags(arn, tags);
    }

    public synchronized void untagResource(JsonNode request) {
        String arn = TimestreamInfluxDbValidation.requiredString(request, "resourceArn", 1, 1011, null);
        List<String> keys = TimestreamInfluxDbValidation.tagKeys(request);
        Map<String, String> tags = tagsOf(arn);
        keys.forEach(tags::remove);
        saveTags(arn, tags);
    }

    /**
     * Starts the InfluxDB containers of resources persisted by a previous run. Their volumes still
     * hold the data and CLI configuration, so the image skips its setup mode and needs no credentials.
     */
    public void restorePersistedRuntime() {
        if (mock) {
            return;
        }
        for (DbInstance instance : instances.scanAllAccounts()) {
            if (instance.getContainerId() != null && instance.getDbClusterId() == null) {
                DbParameterGroup group = parameterGroup(instance.getAccountId(), instance.getRegion(),
                        instance.getDbParameterGroupIdentifier());
                provision(DB_INSTANCE, instance.getAccountId(), instance.getRegion(), instance.getId(), null,
                        engineEnvironment(group), null, "FAILED");
            }
        }
        for (DbCluster cluster : clusters.scanAllAccounts()) {
            if (cluster.getContainerId() != null) {
                DbParameterGroup group = parameterGroup(cluster.getAccountId(), cluster.getRegion(),
                        cluster.getDbParameterGroupIdentifier());
                provision(DB_CLUSTER, cluster.getAccountId(), cluster.getRegion(), cluster.getId(), null,
                        engineEnvironment(group), null, "FAILED");
            }
        }
    }

    @Override
    public void clear() {
        if (!mock) {
            try {
                containerManager.stopManagedContainers();
            } catch (RuntimeException e) {
                LOG.warnv("Failed to stop Timestream for InfluxDB containers during reset: {0}", e.getMessage());
            }
        }
        instances.clear();
        clusters.clear();
        parameterGroups.clear();
        backups.clear();
    }

    private void provision(String resourceType, String accountId, String region, String resourceId,
                           InfluxDbSetup setup, Map<String, String> engineEnvironment, String backupId,
                           String failureStatus) {
        executor.execute(() -> {
            InfluxDbEndpoint endpoint = null;
            try {
                if (!containerManager.isDockerReachable()) {
                    LOG.warnv("No Docker daemon is reachable; {0} {1} is available without an InfluxDB container",
                            resourceType, resourceId);
                    applyDataPlane(resourceType, accountId, region, resourceId, null, AVAILABLE);
                    return;
                }
                endpoint = containerManager.start(resourceId, accountId, region, setup, engineEnvironment);
                containerManager.waitUntilReady(endpoint);
                if (backupId != null) {
                    containerManager.restore(endpoint.containerId(), backupId);
                }
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, AVAILABLE);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to provision the InfluxDB container for {0} {1}", resourceType, resourceId);
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, failureStatus);
            }
        });
    }

    private void restartAsync(String resourceType, String accountId, String region, String resourceId,
                              InfluxDbEndpoint endpoint) {
        executor.execute(() -> {
            try {
                containerManager.restart(endpoint.containerId());
                containerManager.waitUntilReady(endpoint);
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, AVAILABLE);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to reboot the InfluxDB container for {0} {1}", resourceType, resourceId);
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, "REBOOT_FAILED");
            }
        });
    }

    private void captureBackupAsync(String accountId, String region, String backupId, String containerId) {
        executor.execute(() -> {
            boolean captured = false;
            try {
                containerManager.backup(containerId, backupId);
                captured = true;
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to capture InfluxDB backup {0}", backupId);
            }
            synchronized (this) {
                Optional<DbBackup> stored = backups.getForAccount(accountId, key(region, backupId));
                if (stored.isEmpty()) {
                    if (captured) {
                        containerManager.deleteBackupArtifacts(backupId);
                    }
                    return;
                }
                DbBackup backup = stored.get();
                backup.setDataCaptured(captured);
                backup.setStatus(captured ? "COMPLETED" : "FAILED");
                backups.putForAccount(accountId, key(region, backupId), backup);
            }
        });
    }

    private synchronized void applyDataPlane(String resourceType, String accountId, String region, String resourceId,
                                             InfluxDbEndpoint endpoint, String status) {
        String host = endpoint != null ? endpoint.host() : LOCAL_ENDPOINT;
        if (DB_INSTANCE.equals(resourceType)) {
            Optional<DbInstance> stored = instances.getForAccount(accountId, key(region, resourceId));
            if (stored.isEmpty()) {
                discardOrphan(resourceId, endpoint);
                return;
            }
            DbInstance instance = stored.get();
            instance.setEndpoint(host);
            if (endpoint != null) {
                instance.setPort(endpoint.port());
                instance.setContainerId(endpoint.containerId());
            }
            instance.setStatus(status);
            instances.putForAccount(accountId, key(region, resourceId), instance);
            return;
        }
        Optional<DbCluster> stored = clusters.getForAccount(accountId, key(region, resourceId));
        if (stored.isEmpty()) {
            discardOrphan(resourceId, endpoint);
            return;
        }
        DbCluster cluster = stored.get();
        cluster.setEndpoint(host);
        cluster.setReaderEndpoint(host);
        if (endpoint != null) {
            cluster.setPort(endpoint.port());
            cluster.setContainerId(endpoint.containerId());
        }
        cluster.setStatus(status);
        clusters.putForAccount(accountId, key(region, resourceId), cluster);
        for (DbInstance member : instances.scanForAccount(accountId, regionPrefix(region))) {
            if (resourceId.equals(member.getDbClusterId())) {
                member.setEndpoint(host);
                member.setPort(cluster.getPort());
                member.setStatus(status);
                instances.putForAccount(accountId, key(region, member.getId()), member);
            }
        }
    }

    private void discardOrphan(String resourceId, InfluxDbEndpoint endpoint) {
        if (endpoint != null) {
            removeDataPlane(resourceId, endpoint.containerId());
        }
    }

    private void removeDataPlane(String resourceId, String containerId) {
        if (mock) {
            return;
        }
        try {
            containerManager.stop(resourceId, containerId);
            containerManager.removeStorage(resourceId);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to remove the InfluxDB container for {0}: {1}", resourceId, e.getMessage());
        }
    }

    private boolean hasDataPlane(String containerId) {
        return !mock && containerId != null;
    }

    private boolean restoresData(DbBackup backup) {
        return !mock && backup.isDataCaptured();
    }

    private InfluxDbSetup temporarySetup() {
        String secret = UUID.randomUUID().toString().replace("-", "");
        return new InfluxDbSetup("floci-restore", secret, "floci-restore", "floci-restore");
    }

    /**
     * Restoring into an existing resource accepts no parameters beyond the resource to restore and the
     * backup to restore from: the existing configuration is left untouched and only the data is restored.
     */
    private static void rejectReplaceExistingOverrides(JsonNode request) {
        for (String member : REPLACE_EXISTING_REJECTED_MEMBERS) {
            JsonNode value = request.get(member);
            if (value != null && !value.isNull()) {
                throw validation(member + " can't be supplied when restoreMode is REPLACE_EXISTING: restoring "
                        + "into an existing resource makes no configuration changes.");
            }
        }
    }

    private RestoreResult replaceExisting(DbBackup backup, String region, String name, boolean clusterBackup) {
        if (clusterBackup) {
            DbCluster cluster = requireCluster(region, backup.getDbResourceId());
            if (!name.equals(cluster.getName())) {
                throw validation("name must match the existing resource name when restoreMode is REPLACE_EXISTING.");
            }
            if (restoresData(backup) && cluster.getContainerId() != null) {
                cluster.setStatus("RESTORING");
                clusters.put(key(region, cluster.getId()), cluster);
                restoreInPlaceAsync(DB_CLUSTER, cluster.getAccountId(), region, cluster.getId(),
                        new InfluxDbEndpoint(cluster.getContainerId(), cluster.getEndpoint(), cluster.getPort()),
                        backup.getId());
            }
            return new RestoreResult(cluster.getId(), DB_CLUSTER, cluster.getEngineType(), cluster.getDeploymentType());
        }
        DbInstance instance = requireInstance(region, backup.getDbResourceId());
        if (!name.equals(instance.getName())) {
            throw validation("name must match the existing resource name when restoreMode is REPLACE_EXISTING.");
        }
        if (restoresData(backup) && instance.getContainerId() != null) {
            instance.setStatus("RESTORING");
            instances.put(key(region, instance.getId()), instance);
            restoreInPlaceAsync(DB_INSTANCE, instance.getAccountId(), region, instance.getId(),
                    new InfluxDbEndpoint(instance.getContainerId(), instance.getEndpoint(), instance.getPort()),
                    backup.getId());
        }
        return new RestoreResult(instance.getId(), DB_INSTANCE, instance.getEngineType(), instance.getDeploymentType());
    }

    private void restoreInPlaceAsync(String resourceType, String accountId, String region, String resourceId,
                                     InfluxDbEndpoint endpoint, String backupId) {
        executor.execute(() -> {
            try {
                containerManager.restore(endpoint.containerId(), backupId);
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, AVAILABLE);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to restore backup {0} into {1} {2}", backupId, resourceType, resourceId);
                applyDataPlane(resourceType, accountId, region, resourceId, endpoint, "RESTORE_FAILED");
            }
        });
    }

    private List<DbInstance> buildMembers(DbCluster cluster, DbParameterGroup group) {
        List<List<String>> modes = new ArrayList<>();
        switch (cluster.getEngineType()) {
            case ENGINE_V3_CORE -> modes.add(List.of("INGEST", "QUERY", "COMPACT", "PROCESS"));
            case ENGINE_V3_ENTERPRISE -> {
                JsonNode configuration = cluster.getClusterConfiguration();
                int ingestQuery = configuration.path("ingestQueryInstances").asInt(1);
                int queryOnly = configuration.path("queryOnlyInstances").asInt(0);
                boolean dedicatedCompactor = configuration.path("dedicatedCompactor").asBoolean(false);
                for (int i = 0; i < ingestQuery; i++) {
                    modes.add(i == 0 && !dedicatedCompactor
                            ? List.of("INGEST", "QUERY", "COMPACT") : List.of("INGEST", "QUERY"));
                }
                for (int i = 0; i < queryOnly; i++) {
                    modes.add(List.of("QUERY"));
                }
                if (dedicatedCompactor) {
                    modes.add(List.of("COMPACT"));
                }
            }
            default -> {
                modes.add(List.of("PRIMARY"));
                modes.add(List.of("REPLICA"));
            }
        }
        List<DbInstance> members = new ArrayList<>();
        String baseName = cluster.getName().length() > 36 ? cluster.getName().substring(0, 36) : cluster.getName();
        for (int i = 0; i < modes.size(); i++) {
            DbInstance member = new DbInstance();
            member.setId(newId());
            member.setName(baseName + "-" + (i + 1));
            member.setArn(arn(cluster.getRegion(), "db-instance", member.getId()));
            member.setAccountId(cluster.getAccountId());
            member.setRegion(cluster.getRegion());
            member.setStatus(cluster.getStatus());
            member.setEndpoint(cluster.getEndpoint());
            member.setPort(cluster.getPort());
            member.setNetworkType(cluster.getNetworkType());
            member.setDbInstanceType(cluster.getDbInstanceType());
            member.setDbStorageType(cluster.getDbStorageType());
            member.setAllocatedStorage(cluster.getAllocatedStorage());
            member.setVpcSubnetIds(cluster.getVpcSubnetIds());
            member.setVpcSecurityGroupIds(cluster.getVpcSecurityGroupIds());
            member.setPubliclyAccessible(cluster.getPubliclyAccessible());
            member.setDbParameterGroupIdentifier(group == null ? null : group.getId());
            member.setAvailabilityZone(cluster.getRegion() + (char) ('a' + (i % 3)));
            member.setLogDeliveryConfiguration(cluster.getLogDeliveryConfiguration());
            member.setMaintenanceSchedule(cluster.getMaintenanceSchedule());
            member.setInfluxAuthParametersSecretArn(cluster.getInfluxAuthParametersSecretArn());
            member.setKmsKeyId(cluster.getKmsKeyId());
            member.setEngineType(cluster.getEngineType());
            member.setDbClusterId(cluster.getId());
            member.setInstanceModes(modes.get(i));
            member.setInstanceMode(modes.get(i).get(0));
            member.setCreatedAt(cluster.getCreatedAt());
            members.add(member);
        }
        return members;
    }

    private ObjectNode clusterConfiguration(JsonNode enterpriseParameters) {
        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.put("ingestQueryInstances", enterpriseParameters.path("ingestQueryInstances").asInt());
        configuration.put("queryOnlyInstances", enterpriseParameters.path("queryOnlyInstances").asInt());
        configuration.put("dedicatedCompactor", enterpriseParameters.path("dedicatedCompactor").asBoolean());
        return configuration;
    }

    private void snapshotInstance(DbBackup backup, DbInstance source) {
        backup.setResourceType(DB_INSTANCE);
        backup.setEngineType(source.getEngineType());
        backup.setDeploymentType(source.getDeploymentType());
        backup.setKmsKeyId(source.getKmsKeyId());
        backup.setDbParameterGroupId(source.getDbParameterGroupIdentifier());
        backup.setDbInstanceType(source.getDbInstanceType());
        backup.setLogDeliveryConfiguration(source.getLogDeliveryConfiguration());
        backup.setDbStorageType(source.getDbStorageType());
        backup.setAllocatedStorage(source.getAllocatedStorage());
        backup.setVpcSubnetIds(source.getVpcSubnetIds());
        backup.setVpcSecurityGroupIds(source.getVpcSecurityGroupIds());
        backup.setPubliclyAccessible(source.getPubliclyAccessible());
        backup.setPort(source.getPort());
        backup.setNetworkType(source.getNetworkType());
        backup.setInfluxAuthParametersSecretArn(source.getInfluxAuthParametersSecretArn());
        backup.setMaintenanceSchedule(source.getMaintenanceSchedule());
        backup.setDbBackupConfigurations(source.getDbBackupConfigurations());
    }

    private void snapshotCluster(DbBackup backup, DbCluster source) {
        backup.setResourceType(DB_CLUSTER);
        backup.setEngineType(source.getEngineType());
        backup.setDeploymentType(source.getDeploymentType());
        backup.setKmsKeyId(source.getKmsKeyId());
        backup.setClusterConfiguration(source.getClusterConfiguration());
        backup.setDbParameterGroupId(source.getDbParameterGroupIdentifier());
        backup.setDbInstanceType(source.getDbInstanceType());
        backup.setLogDeliveryConfiguration(source.getLogDeliveryConfiguration());
        backup.setFailoverMode(source.getFailoverMode());
        backup.setDbStorageType(source.getDbStorageType());
        backup.setAllocatedStorage(source.getAllocatedStorage());
        backup.setVpcSubnetIds(source.getVpcSubnetIds());
        backup.setVpcSecurityGroupIds(source.getVpcSecurityGroupIds());
        backup.setPubliclyAccessible(source.getPubliclyAccessible());
        backup.setPort(source.getPort());
        backup.setNetworkType(source.getNetworkType());
        backup.setInfluxAuthParametersSecretArn(source.getInfluxAuthParametersSecretArn());
        backup.setMaintenanceSchedule(source.getMaintenanceSchedule());
        backup.setDbBackupConfigurations(source.getDbBackupConfigurations());
    }

    private String createAuthSecret(String resourceId, InfluxDbSetup setup, String region) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("organization", setup.organization());
        value.put("bucket", setup.bucket());
        value.put("username", setup.username());
        value.put("password", setup.password());
        try {
            Secret secret = secretsManagerService.createSecret("READONLY-InfluxDB-auth-parameters-" + resourceId,
                    value.toString(), null, "Initial InfluxDB authorization parameters for " + resourceId,
                    null, null, region);
            return secret == null ? null : secret.getArn();
        } catch (AwsException e) {
            LOG.warnv("Could not create the InfluxDB auth parameters secret for {0}: {1}", resourceId, e.getMessage());
            return null;
        }
    }

    private Map<String, String> engineEnvironment(DbParameterGroup group) {
        if (group == null || !ENGINE_V2.equals(group.getEngineType()) || group.getParameters() == null) {
            return Map.of();
        }
        return TimestreamInfluxDbContainerManager.engineEnvironment(group.getParameters().get("InfluxDBv2"));
    }

    private Map<String, String> tagsOf(String arn) {
        ArnReference reference = parseArn(arn);
        return switch (reference.type()) {
            case "db-instance" -> new LinkedHashMap<>(requireInstance(reference.region(), reference.id()).getTags());
            case "db-cluster" -> new LinkedHashMap<>(requireCluster(reference.region(), reference.id()).getTags());
            case "db-parameter-group" -> new LinkedHashMap<>(
                    requireParameterGroup(reference.region(), reference.id()).getTags());
            default -> new LinkedHashMap<>(requireBackup(reference.region(), reference.id()).getTags());
        };
    }

    private void saveTags(String arn, Map<String, String> tags) {
        ArnReference reference = parseArn(arn);
        String storageKey = key(reference.region(), reference.id());
        switch (reference.type()) {
            case "db-instance" -> {
                DbInstance instance = requireInstance(reference.region(), reference.id());
                instance.setTags(tags);
                instances.put(storageKey, instance);
            }
            case "db-cluster" -> {
                DbCluster cluster = requireCluster(reference.region(), reference.id());
                cluster.setTags(tags);
                clusters.put(storageKey, cluster);
            }
            case "db-parameter-group" -> {
                DbParameterGroup group = requireParameterGroup(reference.region(), reference.id());
                group.setTags(tags);
                parameterGroups.put(storageKey, group);
            }
            default -> {
                DbBackup backup = requireBackup(reference.region(), reference.id());
                backup.setTags(tags);
                backups.put(storageKey, backup);
            }
        }
    }

    private record ArnReference(String region, String accountId, String type, String id) {
    }

    private ArnReference parseArn(String arn) {
        Matcher matcher = TimestreamInfluxDbValidation.ARN.matcher(arn);
        if (!matcher.matches()) {
            throw validation("resourceArn is not a valid Timestream for InfluxDB ARN.");
        }
        ArnReference reference = new ArnReference(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        if (!reference.accountId().equals(regionResolver.getAccountId())) {
            throw notFound(resourceTypeFor(reference.type()), reference.id());
        }
        return reference;
    }

    private static String resourceTypeFor(String arnType) {
        return switch (arnType) {
            case "db-instance" -> DB_INSTANCE;
            case "db-cluster" -> DB_CLUSTER;
            case "db-parameter-group" -> DB_PARAMETER_GROUP;
            default -> DB_BACKUP;
        };
    }

    private void requireStandalone(DbInstance instance) {
        if (instance.getDbClusterId() != null) {
            throw conflict(DB_INSTANCE, instance.getId(), "DB instance " + instance.getId()
                    + " belongs to DB cluster " + instance.getDbClusterId() + "; update the DB cluster instead.");
        }
    }

    private static void requireV2Group(DbParameterGroup group) {
        if (group != null && !ENGINE_V2.equals(group.getEngineType())) {
            throw validation("DB instances support only InfluxDB v2 parameter groups; use CreateDbCluster for InfluxDB 3.");
        }
    }

    private void requireUniqueInstanceName(String region, String name) {
        instances.scan(regionPrefix(region)).stream()
                .filter(existing -> existing.getDbClusterId() == null && name.equals(existing.getName()))
                .findFirst()
                .ifPresent(existing -> {
                    throw conflict(DB_INSTANCE, existing.getId(), "A DB instance named " + name + " already exists.");
                });
    }

    private void requireUniqueClusterName(String region, String name) {
        clusters.scan(regionPrefix(region)).stream()
                .filter(existing -> name.equals(existing.getName()))
                .findFirst()
                .ifPresent(existing -> {
                    throw conflict(DB_CLUSTER, existing.getId(), "A DB cluster named " + name + " already exists.");
                });
    }

    private String clusterContainerId(String region, String clusterId) {
        return clusters.get(key(region, clusterId)).map(DbCluster::getContainerId).orElse(null);
    }

    private List<DbInstance> clusterMembers(String region, String clusterId) {
        return instances.scan(regionPrefix(region)).stream()
                .filter(instance -> clusterId.equals(instance.getDbClusterId()))
                .sorted(Comparator.comparing(DbInstance::getName))
                .toList();
    }

    private DbParameterGroup parameterGroup(String accountId, String region, String groupId) {
        if (groupId == null) {
            return null;
        }
        return parameterGroups.getForAccount(accountId, key(region, groupId)).orElse(null);
    }

    private DbInstance requireInstance(String region, String id) {
        return instances.get(key(region, id)).orElseThrow(() -> notFound(DB_INSTANCE, id));
    }

    private DbCluster requireCluster(String region, String id) {
        return clusters.get(key(region, id)).orElseThrow(() -> notFound(DB_CLUSTER, id));
    }

    private DbParameterGroup requireParameterGroup(String region, String id) {
        return parameterGroups.get(key(region, id)).orElseThrow(() -> notFound(DB_PARAMETER_GROUP, id));
    }

    private DbBackup requireBackup(String region, String id) {
        return backups.get(key(region, id)).orElseThrow(() -> notFound(DB_BACKUP, id));
    }

    private static String instanceIdentifier(JsonNode request) {
        return TimestreamInfluxDbValidation.requiredString(request, "identifier", 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
    }

    private static String clusterIdentifier(JsonNode request) {
        return TimestreamInfluxDbValidation.requiredString(request, "dbClusterId", 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
    }

    private static String backupIdentifier(JsonNode request, String field) {
        return TimestreamInfluxDbValidation.requiredString(request, field, 3, 64,
                TimestreamInfluxDbValidation.IDENTIFIER);
    }

    private static <T> PaginatedResult<T> page(List<T> items, Function<T, String> cursor, JsonNode request) {
        Integer maxResults = TimestreamInfluxDbValidation.maxResults(request);
        String nextToken = TimestreamInfluxDbValidation.nextToken(request);
        try {
            return Pagination.paginate(items, cursor, maxResults, nextToken, MAX_PAGE, "ValidationException");
        } catch (AwsException e) {
            throw validation(e.getMessage());
        }
    }

    private String arn(String region, String resourceType, String id) {
        return regionResolver.buildArn(ARN_SERVICE, region, resourceType + "/" + id);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String key(String region, String id) {
        return region + ":" + id;
    }

    private static Predicate<String> regionPrefix(String region) {
        return storageKey -> storageKey.startsWith(region + ":");
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
