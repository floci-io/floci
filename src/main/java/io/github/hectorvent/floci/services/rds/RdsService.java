package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
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
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core RDS business logic — DB instances, clusters, and parameter groups.
 * Starts DB containers and auth proxies on creation.
 */
@ApplicationScoped
public class RdsService implements Resettable, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(RdsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private final ResourceGroupsTaggingService taggingService;
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
                      ResourceGroupsTaggingService taggingService) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.secretsManagerService = secretsManagerService;
        this.dockerHostResolver = dockerHostResolver;
        this.taggingService = taggingService;
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
               ResourceGroupsTaggingService taggingService) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.secretsManagerService = secretsManagerService;
        this.dockerHostResolver = dockerHostResolver;
        this.instances = instances;
        this.clusters = clusters;
        this.parameterGroups = parameterGroups;
        this.clusterParameterGroups = clusterParameterGroups;
        this.subnetGroups = subnetGroups;
        this.taggingService = taggingService;
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
                // Standalone instance — start its own container
                String image = imageForEngine(engine, engineVersion);
                instanceVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
                RdsContainerHandle handle = containerManager.start(id, instanceVolumeId, engine, image, masterUsername, masterPassword, dbName);
                backendHost = handle.getHost();
                backendPort = handle.getPort();
                containerId = handle.getContainerId();
                containerHost = handle.getHost();
                containerPort = handle.getPort();
                instanceDockerVolumeName = volumeName(instanceVolumeId, id);
            }
        }

        DbEndpoint endpoint = new DbEndpoint(mock ? "localhost" : proxyEndpointHost(), proxyPort);
        DbInstance instance = new DbInstance(id, engine, engineVersion, masterUsername, masterPassword,
                dbName, dbInstanceClass, allocatedStorage, DbInstanceStatus.AVAILABLE,
                endpoint, iamEnabled, paramGroupName, dbClusterIdentifier, Instant.now(), proxyPort);
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

        if (!mock) {
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
        LOG.infov("DB instance {0} created, engine={1}, endpoint=localhost:{2}", id, engine, String.valueOf(proxyPort));
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
            // Valid RDS resource types Floci does not model yet (og, pg, snapshot, ...) — taggable
            // on real AWS, so the message states the Floci limitation rather than AWS semantics.
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
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
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
                RdsContainerHandle handle = containerManager.start(id, instance.getVolumeId(), instance.getEngine(), image,
                        instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
                instance.setContainerId(handle.getContainerId());
                instance.setContainerHost(handle.getHost());
                instance.setContainerPort(handle.getPort());
            }
        }

        instance.setStatus(DbInstanceStatus.AVAILABLE);
        instances.put(id, instance);

        if (!mock) {
            String effectiveMasterUser = instance.getMasterUsername() != null
                    ? instance.getMasterUsername() : "root";
            proxyManager.startProxy(id, instance.getEngine(),
                    instance.isIamDatabaseAuthenticationEnabled(),
                    instance.getProxyPort(), instance.getContainerHost(), instance.getContainerPort(),
                    effectiveMasterUser, instance.getMasterPassword(), instance.getDbName(),
                    (user, pw) -> validateDbPassword(id, user, pw));
        }

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

        boolean mock = config.services().rds().mock();
        if (!mock) {
            proxyManager.stopProxy(id);
        }

        String clusterId = instance.getDbClusterIdentifier();
        if (clusterId == null || clusterId.isBlank()) {
            // Standalone — stop its container and clean up its Docker volume (neither exists in mock mode)
            if (!mock) {
                if (instance.getContainerId() != null) {
                    containerManager.stop(buildHandle(instance));
                }
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
        validateClusterParameterGroup(paramGroupName, engineParam, engineVersion);
        PlacementResolution placement = resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, effectiveRegion);

        boolean mock = config.services().rds().mock();
        // Always reserve a unique port (even in mock) so endpoints stay distinct and usedPorts
        // is consistent; mock mode only skips starting the container and auth proxy.
        int proxyPort = allocateProxyPort();
        DbEndpoint endpoint = new DbEndpoint(mock ? "localhost" : proxyEndpointHost(), proxyPort);
        DbCluster cluster = new DbCluster(id, engine, engineVersion, masterUsername, masterPassword,
                databaseName, DbInstanceStatus.AVAILABLE, endpoint, endpoint,
                iamEnabled, new ArrayList<>(), paramGroupName, Instant.now(), proxyPort);
        if (!mock) {
            String image = imageForEngine(engine, engineVersion);
            String clusterVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
            RdsContainerHandle handle = containerManager.start(id, clusterVolumeId, engine, image, masterUsername, masterPassword, databaseName);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setVolumeId(clusterVolumeId);
            cluster.setDockerVolumeName(volumeName(clusterVolumeId, id));
        }
        cluster.setDbSubnetGroupName(placement.dbSubnetGroupName());
        cluster.setVpcId(placement.vpcId());
        cluster.setAvailabilityZone(placement.availabilityZone());
        cluster.setMultiAz(placement.multiAz());
        cluster.setSubnetAvailabilityZones(placement.subnetAvailabilityZones());

        cluster.setDbClusterResourceId("cluster-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        cluster.setDbClusterArn(regionResolver.buildArn("rds", effectiveRegion, "cluster:" + id));

        if (!mock) {
            String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
            proxyManager.startProxy(id, engine, iamEnabled, proxyPort, cluster.getContainerHost(), cluster.getContainerPort(),
                    effectiveMasterUser, masterPassword, databaseName,
                    (user, pw) -> validateDbClusterPassword(id, user, pw));
        }

        clusters.put(id, cluster);
        LOG.infov("DB cluster {0} created (mock={1}), engine={2}, endpoint=localhost:{3}",
                id, String.valueOf(mock), engine, String.valueOf(proxyPort));
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

        if (!config.services().rds().mock()) {
            proxyManager.stopProxy(id);
            if (cluster.getContainerId() != null) {
                containerManager.stop(buildClusterHandle(cluster));
            }
            containerManager.removeVolume(id, cluster.getVolumeId());
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
        DbParameterGroup group = getDbParameterGroup(name);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
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
                new AwsException("DBClusterParameterGroupNotFound",
                        "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404));
    }

    public Collection<DbClusterParameterGroup> listDbClusterParameterGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return clusterParameterGroups.get(filterName).map(List::of).orElse(List.of());
        }
        return clusterParameterGroups.scan(k -> true);
    }

    public void deleteDbClusterParameterGroup(String name) {
        if (clusterParameterGroups.get(name).isEmpty()) {
            throw new AwsException("DBClusterParameterGroupNotFound",
                    "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404);
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
            default -> throw new AwsException("InvalidParameterValue", invalidParameterValueMessage(), 400);
        };
    }

    private String imageForEngine(DatabaseEngine engine, String engineVersion) {
        String defaultImage = switch (engine) {
            case POSTGRES -> config.services().rds().defaultPostgresImage();
            case MYSQL -> config.services().rds().defaultMysqlImage();
            case MARIADB -> config.services().rds().defaultMariadbImage();
        };
        return imageForRequestedVersion(defaultImage, engineVersion);
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
        validateParameterGroupFamily(paramGroupName, group.getDbParameterGroupFamily(), engineParam, engineVersion);
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
            cluster.setEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
            cluster.setReaderEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
            if (cluster.getDockerVolumeName() == null) {
                cluster.setDockerVolumeName(volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier()));
            }
            try {
                String image = imageForEngine(cluster.getEngine(), cluster.getEngineVersion());
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
            if (config.services().rds().mock()) {
                int mockPort = reserveOrAllocateProxyPort(instance.getProxyPort());
                instance.setProxyPort(mockPort);
                instance.setEndpoint(new DbEndpoint("localhost", mockPort));
                instance.setStatus(DbInstanceStatus.AVAILABLE);
                continue;
            }
            int proxyPort = reserveOrAllocateProxyPort(instance.getProxyPort());
            instance.setProxyPort(proxyPort);
            instance.setEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
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
                    String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
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

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (DbInstance instance : listDbInstances(null)) {
            String arn = instance.getDbInstanceArn();
            if (arn == null) continue;
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(arn, "rds:db", "rds",
                    parsed.region(), parsed.accountId(),
                    instance.getCreatedAt() != null ? instance.getCreatedAt() : Instant.now(),
                    tagsFor(parsed.region(), arn)));
        }
        for (DbCluster cluster : listDbClusters(null)) {
            String arn = cluster.getDbClusterArn();
            if (arn == null) continue;
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(arn, "rds:cluster", "rds",
                    parsed.region(), parsed.accountId(),
                    cluster.getCreatedAt() != null ? cluster.getCreatedAt() : Instant.now(),
                    tagsFor(parsed.region(), arn)));
        }
        return resources;
    }

    /**
     * Resolves tags for a resource, tolerating a null taggingService. The CDI constructor always
     * supplies one; the storage-backed test constructors pass null, and unit tests that exercise
     * getResources() would otherwise NPE.
     */
    private Map<String, String> tagsFor(String region, String arn) {
        return taggingService != null ? taggingService.getTagsForResource(region, arn) : Map.of();
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(
                new SupportedResourceType("rds:db", "rds", true),
                new SupportedResourceType("rds:cluster", "rds", true));
    }
}
