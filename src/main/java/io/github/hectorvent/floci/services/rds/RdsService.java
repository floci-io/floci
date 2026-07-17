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
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
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
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
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
public class RdsService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageBackend<String, DbInstance> instances;
    private final StorageBackend<String, DbCluster> clusters;
    private final StorageBackend<String, DbParameterGroup> parameterGroups;
    private final StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups;
    private final StorageBackend<String, DbSubnetGroup> subnetGroups;
    private final StorageBackend<String, DbProxy> proxies;
    private final StorageBackend<String, DbProxyTargetGroup> proxyTargetGroups;
    private final RdsContainerManager containerManager;
    private final RdsProxyManager proxyManager;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final SecretsManagerService secretsManagerService;
    private final DockerHostResolver dockerHostResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private static final Pattern IMAGE_TAG_VERSION_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");
    private static final Pattern SAFE_IMAGE_TAG_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DB_PROXY_NAME_PATTERN =
            Pattern.compile("[a-zA-Z](?:-?[a-zA-Z0-9]+)*");

    @Inject
    public RdsService(RdsContainerManager containerManager,
                      RdsProxyManager proxyManager,
                      Ec2Service ec2Service,
                      RegionResolver regionResolver,
                      EmulatorConfig config,
                      StorageFactory storageFactory,
                      SecretsManagerService secretsManagerService,
                      DockerHostResolver dockerHostResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.secretsManagerService = secretsManagerService;
        this.dockerHostResolver = dockerHostResolver;
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
        this.proxies = storageFactory.create("rds", "rds-proxies.json",
                new TypeReference<Map<String, DbProxy>>() {});
        this.proxyTargetGroups = storageFactory.create("rds", "rds-proxy-target-groups.json",
                new TypeReference<Map<String, DbProxyTargetGroup>>() {});
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
                null, null);
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
               DockerHostResolver dockerHostResolver) {
        this(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, subnetGroups,
                secretsManagerService, dockerHostResolver,
                new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    // Test overload that also injects the DB-proxy stores (for restore-across-restart tests).
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
               StorageBackend<String, DbProxy> proxies,
               StorageBackend<String, DbProxyTargetGroup> proxyTargetGroups) {
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
        this.proxies = proxies;
        this.proxyTargetGroups = proxyTargetGroups;
    }

    public void restorePersistedRuntime() {
        restoreClusters();
        restoreInstances();
        restoreProxies();
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
            final String accountId = accountIdFromArn(instance.getDbInstanceArn());
            proxyManager.startProxy(id, engine, iamEnabled, proxyPort, backendHost, backendPort,
                    masterUsername, masterPassword, dbName,
                    (user, pw) -> validateDbPasswordForAccount(accountId, id, user, pw));
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
            case "db-proxy" -> {
                DbProxy proxy = proxies.scan(k -> true).stream()
                        .filter(candidate -> resourceId.equals(candidate.getDbProxyResourceId()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBProxyNotFoundFault",
                                "DB proxy " + resourceId + " not found.", 404));
                yield new TagHandle(proxy.getTags(), updated -> {
                    proxy.setTags(updated);
                    proxies.put(proxy.getDbProxyName(), proxy);
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
            final String accountId = accountIdFromArn(instance.getDbInstanceArn());
            proxyManager.startProxy(id, instance.getEngine(),
                    instance.isIamDatabaseAuthenticationEnabled(),
                    instance.getProxyPort(), instance.getContainerHost(), instance.getContainerPort(),
                    effectiveMasterUser, instance.getMasterPassword(), instance.getDbName(),
                    (user, pw) -> validateDbPasswordForAccount(accountId, id, user, pw));
        }

        LOG.infov("DB instance {0} rebooted", id);
        return instance;
    }

    public synchronized void deleteDbInstance(String id) {
        DbInstance instance = instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound", "DB instance " + id + " not found.", 404));

        if (instance.getStatus() == DbInstanceStatus.DELETING) {
            throw new AwsException("InvalidDBInstanceState",
                    "DB instance " + id + " is already being deleted.", 400);
        }
        if (isRegisteredProxyTarget("RDS_INSTANCE", id)) {
            throw new AwsException("InvalidDBInstanceState",
                    "DB instance " + id + " is registered with a DB proxy target group.", 400);
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
            final String accountId = accountIdFromArn(cluster.getDbClusterArn());
            proxyManager.startProxy(id, engine, iamEnabled, proxyPort, cluster.getContainerHost(), cluster.getContainerPort(),
                    effectiveMasterUser, masterPassword, databaseName,
                    (user, pw) -> validateDbClusterPasswordForAccount(accountId, id, user, pw));
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

    public synchronized void deleteDbCluster(String id) {
        DbCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));

        if (!cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "DB cluster " + id + " still has DB instances.", 400);
        }
        if (isRegisteredProxyTarget("TRACKED_CLUSTER", id)) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "DB cluster " + id + " is registered with a DB proxy target group.", 400);
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

    // ── DB Proxies (AWS::RDS::DBProxy) ──────────────────────────────────────────

    /**
     * Creates a DB Proxy. No relay is started here — the backend target is unknown until a target
     * group registers a cluster/instance (see {@link #registerDbProxyTargets}). The relay listens on
     * the engine family's default port so the endpoint is a bare host clients reach at 5432/3306.
     */
    public DbProxy createDbProxy(String dbProxyName, String engineFamily, boolean requireTls,
                                 boolean iamAuth, String roleArn, List<String> vpcSubnetIds,
                                 List<String> vpcSecurityGroupIds, List<DbProxyAuth> auth,
                                 Map<String, String> tags) {
        return createDbProxy(dbProxyName, engineFamily, requireTls, iamAuth, roleArn,
                vpcSubnetIds, vpcSecurityGroupIds, auth, 1800, false, tags,
                regionResolver.getDefaultRegion());
    }

    public synchronized DbProxy createDbProxy(String dbProxyName, String engineFamily, boolean requireTls,
                                               boolean iamAuth, String roleArn, List<String> vpcSubnetIds,
                                               List<String> vpcSecurityGroupIds, List<DbProxyAuth> auth,
                                               int idleClientTimeout, boolean debugLogging,
                                               Map<String, String> tags, String region) {
        validateDbProxyCreate(dbProxyName, engineFamily, roleArn, vpcSubnetIds, idleClientTimeout);
        if (proxies.get(dbProxyName).isPresent()) {
            throw new AwsException("DBProxyAlreadyExistsFault",
                    "DB proxy " + dbProxyName + " already exists.", 400);
        }

        boolean mock = config.services().rds().mock();
        // RDS Proxy exposes a bare hostname on the engine's default port. Floci currently models
        // that contract directly; a separate endpoint-routing design is required before multiple
        // same-engine proxies can be made externally reachable through one Docker host.
        int proxyPort = reserveOrAllocateProxyPort(defaultPortForEngineFamily(engineFamily));
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName(dbProxyName);
        proxy.setEngineFamily(engineFamily.toUpperCase());
        proxy.setRequireTls(requireTls);
        proxy.setIamAuth(iamAuth);
        proxy.setRoleArn(roleArn);
        proxy.setVpcSubnetIds(vpcSubnetIds);
        proxy.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        proxy.setAuth(auth);
        proxy.setIdleClientTimeout(idleClientTimeout);
        proxy.setDebugLogging(debugLogging);
        proxy.setTags(tags);
        proxy.setProxyPort(proxyPort);
        proxy.setEndpointHost(mock ? "localhost" : proxyEndpointHost());
        proxy.setStatus("available");
        Instant now = Instant.now();
        proxy.setCreatedAt(now);
        String resourceId = "prx-" + randomResourceSuffix();
        proxy.setDbProxyResourceId(resourceId);
        String effectiveRegion = effectiveRegion(region);
        proxy.setDbProxyArn(regionResolver.buildArn("rds", effectiveRegion, "db-proxy:" + resourceId));

        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName(dbProxyName);
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn(regionResolver.buildArn("rds", effectiveRegion,
                "target-group:prx-tg-" + randomResourceSuffix()));
        targetGroup.setDefaultTargetGroup(true);
        if ("SQLSERVER".equalsIgnoreCase(engineFamily)) {
            targetGroup.setMaxConnectionsPercent(10);
            targetGroup.setMaxIdleConnectionsPercent(5);
        }
        targetGroup.setCreatedAt(now);
        targetGroup.setUpdatedAt(now);

        try {
            proxies.put(dbProxyName, proxy);
            proxyTargetGroups.put(dbProxyName, targetGroup);
        } catch (RuntimeException e) {
            proxies.delete(dbProxyName);
            proxyTargetGroups.delete(dbProxyName);
            releaseProxyPort(proxyPort);
            throw e;
        }
        LOG.infov("DB proxy {0} created (mock={1}), endpoint={2}",
                dbProxyName, String.valueOf(mock), proxy.getEndpoint());
        return proxy;
    }

    /**
     * Registers the target cluster/instance for a proxy's (single, "default") target group and — in
     * real mode — starts the auth relay forwarding the proxy endpoint to the target's backend
     * container. This is the point where the backend host/port become known.
     */
    public synchronized DbProxyTargetGroup registerDbProxyTargets(String dbProxyName, String targetGroupName,
                                                                  List<String> dbClusterIdentifiers,
                                                                  List<String> dbInstanceIdentifiers,
                                                                  int maxConnectionsPercent,
                                                                  int maxIdleConnectionsPercent) {
        DbProxy proxy = proxies.get(dbProxyName).orElseThrow(() ->
                new AwsException("DBProxyNotFoundFault", "DB proxy " + dbProxyName + " not found.", 404));
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(dbProxyName, targetGroupName);

        int clusterCount = dbClusterIdentifiers == null ? 0 : dbClusterIdentifiers.size();
        int instanceCount = dbInstanceIdentifiers == null ? 0 : dbInstanceIdentifiers.size();
        if (clusterCount + instanceCount == 0) {
            throw new AwsException("InvalidParameterValue",
                    "RegisterDBProxyTargets requires a DBClusterIdentifier or DBInstanceIdentifier.", 400);
        }
        if (clusterCount + instanceCount != 1) {
            throw new AwsException("InvalidParameterCombination",
                    "Floci currently supports exactly one DB cluster or DB instance per proxy target group.", 400);
        }
        Integer configuredMaxConnections = maxConnectionsPercent > 0 ? maxConnectionsPercent : null;
        Integer configuredMaxIdle = maxIdleConnectionsPercent > 0 ? maxIdleConnectionsPercent : null;
        validatePoolConfiguration(configuredMaxConnections, configuredMaxIdle);

        String backendHost;
        int backendPort;
        DatabaseEngine engine;
        String masterUser;
        String masterPassword;
        String dbName;
        DbProxyTarget target;

        if (clusterCount == 1) {
            String clusterId = dbClusterIdentifiers.get(0);
            DbCluster cluster = clusters.get(clusterId).orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault", "DB cluster " + clusterId + " not found.", 404));
            if (cluster.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBClusterStateFault",
                        "DB cluster " + clusterId + " is not available.", 400);
            }
            backendHost = cluster.getContainerHost();
            backendPort = cluster.getContainerPort();
            engine = cluster.getEngine();
            masterUser = cluster.getMasterUsername();
            masterPassword = cluster.getMasterPassword();
            dbName = cluster.getDatabaseName();
            target = new DbProxyTarget("TRACKED_CLUSTER", clusterId, cluster.getDbClusterArn(),
                    cluster.getContainerHost(), cluster.getContainerPort());
        } else {
            String instanceId = dbInstanceIdentifiers.get(0);
            DbInstance instance = instances.get(instanceId).orElseThrow(() ->
                    new AwsException("DBInstanceNotFound", "DB instance " + instanceId + " not found.", 404));
            if (instance.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBInstanceState",
                        "DB instance " + instanceId + " is not available.", 400);
            }
            backendHost = instance.getContainerHost();
            backendPort = instance.getContainerPort();
            engine = instance.getEngine();
            masterUser = instance.getMasterUsername();
            masterPassword = instance.getMasterPassword();
            dbName = instance.getDbName();
            target = new DbProxyTarget("RDS_INSTANCE", instanceId, instance.getDbInstanceArn(),
                    instance.getContainerHost(), instance.getContainerPort());
        }

        validateTargetEngineFamily(proxy, engine);
        if (targetGroup.getTargets().stream().anyMatch(existing ->
                existing.getType().equals(target.getType())
                        && existing.getRdsResourceId().equals(target.getRdsResourceId()))) {
            throw new AwsException("DBProxyTargetAlreadyRegisteredFault",
                    "The target is already registered with proxy " + dbProxyName + ".", 400);
        }
        if (!targetGroup.getTargets().isEmpty()) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "The default target group already has a registered target.", 400);
        }

        if (!config.services().rds().mock()) {
            if (backendHost == null || backendPort <= 0) {
                throw new AwsException("InvalidDBProxyStateFault",
                        "Target backend for proxy " + dbProxyName + " is not available.", 400);
            }
            String effectiveMasterUser = masterUser != null ? masterUser : "root";
            final String targetId = target.getRdsResourceId();
            final boolean isCluster = "TRACKED_CLUSTER".equals(target.getType());
            final String accountId = accountIdFromArn(proxy.getDbProxyArn());
            proxyManager.startProxy(dbProxyRelayKey(proxy), engine, proxy.isIamAuth(), proxy.getProxyPort(),
                    backendHost, backendPort, effectiveMasterUser, masterPassword, dbName,
                    (user, pw) -> isCluster
                            ? validateDbClusterPasswordForAccount(accountId, targetId, user, pw)
                            : validateDbPasswordForAccount(accountId, targetId, user, pw));
        }

        DbProxyTargetGroup updatedTargetGroup = copyProxyTargetGroup(targetGroup);
        if (configuredMaxConnections != null) {
            updatedTargetGroup.setMaxConnectionsPercent(configuredMaxConnections);
        }
        if (configuredMaxIdle != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(configuredMaxIdle);
        } else if (configuredMaxConnections != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(configuredMaxConnections / 2);
        }
        updatedTargetGroup.getTargets().add(target);
        updatedTargetGroup.setUpdatedAt(Instant.now());
        try {
            proxyTargetGroups.put(dbProxyName, updatedTargetGroup);
        } catch (RuntimeException e) {
            if (!config.services().rds().mock()) {
                proxyManager.stopProxy(dbProxyRelayKey(proxy));
            }
            throw e;
        }
        LOG.infov("DB proxy {0} target group default registered target {1}",
                dbProxyName, target.getRdsResourceId());
        return updatedTargetGroup;
    }

    public synchronized DbProxyTargetGroup configureDbProxyTargetGroup(String dbProxyName,
                                                                        String targetGroupName,
                                                                        Integer maxConnectionsPercent,
                                                                        Integer maxIdleConnectionsPercent) {
        getDbProxy(dbProxyName);
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(dbProxyName, targetGroupName);
        validatePoolConfiguration(maxConnectionsPercent, maxIdleConnectionsPercent);
        DbProxyTargetGroup updatedTargetGroup = copyProxyTargetGroup(targetGroup);
        if (maxConnectionsPercent != null) {
            updatedTargetGroup.setMaxConnectionsPercent(maxConnectionsPercent);
        }
        if (maxIdleConnectionsPercent != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(maxIdleConnectionsPercent);
        } else if (maxConnectionsPercent != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(maxConnectionsPercent / 2);
        }
        updatedTargetGroup.setUpdatedAt(Instant.now());
        proxyTargetGroups.put(dbProxyName, updatedTargetGroup);
        return updatedTargetGroup;
    }

    public synchronized void deregisterDbProxyTargets(String dbProxyName, String targetGroupName,
                                                       List<String> dbClusterIdentifiers,
                                                       List<String> dbInstanceIdentifiers) {
        DbProxy proxy = getDbProxy(dbProxyName);
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(dbProxyName, targetGroupName);
        List<String> clusterIds = dbClusterIdentifiers == null ? List.of() : dbClusterIdentifiers;
        List<String> instanceIds = dbInstanceIdentifiers == null ? List.of() : dbInstanceIdentifiers;
        if (clusterIds.isEmpty() && instanceIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "DeregisterDBProxyTargets requires a DBClusterIdentifier or DBInstanceIdentifier.", 400);
        }

        DbProxyTargetGroup updatedTargetGroup = copyProxyTargetGroup(targetGroup);
        int before = updatedTargetGroup.getTargets().size();
        updatedTargetGroup.getTargets().removeIf(target ->
                ("TRACKED_CLUSTER".equals(target.getType()) && clusterIds.contains(target.getRdsResourceId()))
                        || ("RDS_INSTANCE".equals(target.getType())
                        && instanceIds.contains(target.getRdsResourceId())));
        if (updatedTargetGroup.getTargets().size() == before) {
            throw new AwsException("DBProxyTargetNotFoundFault",
                    "The specified target is not registered with proxy " + dbProxyName + ".", 404);
        }
        updatedTargetGroup.setUpdatedAt(Instant.now());
        proxyTargetGroups.put(dbProxyName, updatedTargetGroup);
        if (!config.services().rds().mock()) {
            proxyManager.stopProxy(dbProxyRelayKey(proxy));
        }
    }

    public DbProxy getDbProxy(String name) {
        return proxies.get(name).orElseThrow(() ->
                new AwsException("DBProxyNotFoundFault", "DB proxy " + name + " not found.", 404));
    }

    public Collection<DbProxy> listDbProxies(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getDbProxy(filterName));
        }
        return proxies.scan(k -> true);
    }

    public Collection<DbProxyTargetGroup> describeDbProxyTargetGroups(String dbProxyName) {
        return describeDbProxyTargetGroups(dbProxyName, null);
    }

    public Collection<DbProxyTargetGroup> describeDbProxyTargetGroups(String dbProxyName,
                                                                       String targetGroupName) {
        getDbProxy(dbProxyName);
        return List.of(getDefaultProxyTargetGroup(dbProxyName, targetGroupName));
    }

    public Collection<DbProxyTarget> describeDbProxyTargets(String dbProxyName, String targetGroupName) {
        getDbProxy(dbProxyName);
        return new ArrayList<>(getDefaultProxyTargetGroup(dbProxyName, targetGroupName).getTargets());
    }

    public synchronized void clearDbProxyTargetGroupByArn(String targetGroupArn) {
        DbProxyTargetGroup targetGroup = proxyTargetGroups.scan(k -> true).stream()
                .filter(candidate -> Objects.equals(targetGroupArn, candidate.getTargetGroupArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("DBProxyTargetGroupNotFoundFault",
                        "DB proxy target group " + targetGroupArn + " not found.", 404));
        DbProxyTargetGroup clearedTargetGroup = copyProxyTargetGroup(targetGroup);
        clearedTargetGroup.getTargets().clear();
        DbProxy proxy = getDbProxy(targetGroup.getDbProxyName());
        boolean sqlServer = "SQLSERVER".equals(proxy.getEngineFamily());
        clearedTargetGroup.setMaxConnectionsPercent(sqlServer ? 10 : 100);
        clearedTargetGroup.setMaxIdleConnectionsPercent(sqlServer ? 5 : 50);
        clearedTargetGroup.setUpdatedAt(Instant.now());
        proxyTargetGroups.put(clearedTargetGroup.getDbProxyName(), clearedTargetGroup);
        if (!targetGroup.getTargets().isEmpty() && !config.services().rds().mock()) {
            proxyManager.stopProxy(dbProxyRelayKey(proxy));
        }
    }

    public synchronized void deleteDbProxy(String name) {
        DbProxy proxy = proxies.get(name).orElseThrow(() ->
                new AwsException("DBProxyNotFoundFault", "DB proxy " + name + " not found.", 404));
        if (!config.services().rds().mock()) {
            proxyManager.stopProxy(dbProxyRelayKey(proxy));
        }
        releaseProxyPort(proxy.getProxyPort());
        proxyTargetGroups.delete(name);
        proxies.delete(name);
        LOG.infov("DB proxy {0} deleted", name);
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
        return validateDbPasswordForAccount(null, instanceId, clientUser, password);
    }

    private boolean validateDbPasswordForAccount(String accountId, String instanceId,
                                                  String clientUser, String password) {
        DbInstance instance = findInstanceForAccount(accountId, instanceId);
        if (instance == null) {
            return false;
        }
        if (!instance.getMasterUsername().equals(clientUser)) {
            return true; // non-master user: backend is the authority
        }
        return password != null && password.equals(instance.getMasterPassword());
    }

    public boolean validateDbClusterPassword(String clusterId, String clientUser, String password) {
        return validateDbClusterPasswordForAccount(null, clusterId, clientUser, password);
    }

    private boolean validateDbClusterPasswordForAccount(String accountId, String clusterId,
                                                         String clientUser, String password) {
        DbCluster cluster = findClusterForAccount(accountId, clusterId);
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

    private void validateDbProxyCreate(String name, String engineFamily, String roleArn,
                                       List<String> subnetIds, int idleClientTimeout) {
        if (name == null || name.isBlank() || name.length() > 63
                || !DB_PROXY_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "DBProxyName must be 1-63 characters, begin with a letter, and contain only letters, digits, and single hyphens.",
                    400);
        }
        if (engineFamily == null || !("MYSQL".equalsIgnoreCase(engineFamily)
                || "POSTGRESQL".equalsIgnoreCase(engineFamily)
                || "SQLSERVER".equalsIgnoreCase(engineFamily))) {
            throw new AwsException("InvalidParameterValue",
                    "EngineFamily must be MYSQL, POSTGRESQL, or SQLSERVER.", 400);
        }
        if (roleArn == null || roleArn.length() < 20 || roleArn.length() > 2048) {
            throw new AwsException("InvalidParameterValue", "RoleArn is required.", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "VpcSubnetIds is required.", 400);
        }
        if (idleClientTimeout < 1 || idleClientTimeout > 28_800) {
            throw new AwsException("InvalidParameterValue",
                    "IdleClientTimeout must be between 1 and 28800 seconds.", 400);
        }
    }

    private DbProxyTargetGroup getDefaultProxyTargetGroup(String dbProxyName, String targetGroupName) {
        String effectiveName = targetGroupName == null || targetGroupName.isBlank()
                ? "default" : targetGroupName;
        if (!"default".equals(effectiveName)) {
            throw new AwsException("DBProxyTargetGroupNotFoundFault",
                    "DB proxy target group " + effectiveName + " not found for proxy " + dbProxyName + ".", 404);
        }
        return proxyTargetGroups.get(dbProxyName).orElseThrow(() ->
                new AwsException("DBProxyTargetGroupNotFoundFault",
                        "DB proxy target group default not found for proxy " + dbProxyName + ".", 404));
    }

    private void validateTargetEngineFamily(DbProxy proxy, DatabaseEngine targetEngine) {
        boolean compatible = switch (proxy.getEngineFamily()) {
            case "POSTGRESQL" -> targetEngine == DatabaseEngine.POSTGRES;
            case "MYSQL" -> targetEngine == DatabaseEngine.MYSQL || targetEngine == DatabaseEngine.MARIADB;
            default -> false;
        };
        if (!compatible) {
            throw new AwsException("InvalidParameterValue",
                    "Target engine " + targetEngine + " is incompatible with proxy engine family "
                            + proxy.getEngineFamily() + ".", 400);
        }
    }

    private void validatePercent(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be between " + minimum + " and " + maximum + ".", 400);
        }
    }

    private void validatePoolConfiguration(Integer maxConnectionsPercent,
                                           Integer maxIdleConnectionsPercent) {
        if (maxConnectionsPercent != null) {
            validatePercent("MaxConnectionsPercent", maxConnectionsPercent, 1, 100);
        }
        if (maxIdleConnectionsPercent != null) {
            if (maxConnectionsPercent == null) {
                throw new AwsException("InvalidParameterValue",
                        "MaxConnectionsPercent is required when MaxIdleConnectionsPercent is specified.", 400);
            }
            validatePercent("MaxIdleConnectionsPercent", maxIdleConnectionsPercent,
                    0, maxConnectionsPercent);
        }
    }

    private boolean isRegisteredProxyTarget(String type, String resourceId) {
        return proxyTargetGroups.scan(k -> true).stream()
                .flatMap(targetGroup -> targetGroup.getTargets().stream())
                .anyMatch(target -> type.equals(target.getType())
                        && resourceId.equals(target.getRdsResourceId()));
    }

    private DbProxyTargetGroup copyProxyTargetGroup(DbProxyTargetGroup source) {
        DbProxyTargetGroup copy = new DbProxyTargetGroup();
        copy.setDbProxyName(source.getDbProxyName());
        copy.setTargetGroupName(source.getTargetGroupName());
        copy.setTargetGroupArn(source.getTargetGroupArn());
        copy.setStatus(source.getStatus());
        copy.setDefaultTargetGroup(source.isDefaultTargetGroup());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setMaxConnectionsPercent(source.getMaxConnectionsPercent());
        copy.setMaxIdleConnectionsPercent(source.getMaxIdleConnectionsPercent());
        copy.setTargets(source.getTargets());
        return copy;
    }

    private String randomResourceSuffix() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    /** The engine's default listener port — an RDS Proxy endpoint is a bare host reached on this port. */
    private int defaultPortForEngineFamily(String engineFamily) {
        if (engineFamily == null) {
            return 5432;
        }
        return switch (engineFamily.toUpperCase()) {
            case "MYSQL" -> 3306;
            case "SQLSERVER" -> 1433;
            default -> 5432;   // POSTGRESQL
        };
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
                final String accountId = accountIdFromArn(cluster.getDbClusterArn());
                proxyManager.startProxy(cluster.getDbClusterIdentifier(), cluster.getEngine(),
                        cluster.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        handle.getHost(), handle.getPort(), effectiveMasterUser,
                        cluster.getMasterPassword(), cluster.getDatabaseName(),
                        (user, pw) -> validateDbClusterPasswordForAccount(accountId,
                                cluster.getDbClusterIdentifier(), user, pw));
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
                    DbCluster cluster = findClusterForAccount(
                            accountIdFromArn(instance.getDbInstanceArn()), clusterId);
                    if (cluster == null) {
                        throw new AwsException("DBClusterNotFoundFault",
                                "DB cluster " + clusterId + " not found.", 404);
                    }
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
                final String accountId = accountIdFromArn(instance.getDbInstanceArn());
                proxyManager.startProxy(instance.getDbInstanceIdentifier(), instance.getEngine(),
                        instance.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        backendHost, backendPort, effectiveMasterUser,
                        instance.getMasterPassword(), instance.getDbName(),
                        (user, pw) -> validateDbPasswordForAccount(accountId,
                                instance.getDbInstanceIdentifier(), user, pw));
                instance.setStatus(DbInstanceStatus.AVAILABLE);
            } catch (Exception e) {
                releaseProxyPort(proxyPort);
                LOG.warnv(e, "Failed to restore RDS instance {0}", instance.getDbInstanceIdentifier());
            }
        }
    }

    /** Re-arms each persisted DB proxy's relay after a restart (clusters/instances restored first). */
    private void restoreProxies() {
        for (DbProxy proxy : allProxies()) {
            int proxyPort = reserveOrAllocateProxyPort(proxy.getProxyPort());
            proxy.setProxyPort(proxyPort);
            if (config.services().rds().mock()) {
                proxy.setEndpointHost("localhost");
                proxy.setStatus("available");
                persistRestoredProxy(proxy);
                ensureRestoredDefaultTargetGroup(proxy);
                continue;
            }
            proxy.setEndpointHost(proxyEndpointHost());
            persistRestoredProxy(proxy);
            DbProxyTargetGroup tg = ensureRestoredDefaultTargetGroup(proxy);
            if (tg.getTargets().isEmpty()) {
                continue;   // no target registered yet; nothing to relay to
            }
            DbProxyTarget target = tg.getTargets().get(0);
            try {
                String backendHost;
                int backendPort;
                DatabaseEngine engine;
                String masterUser;
                String masterPassword;
                String dbName;
                boolean isCluster = "TRACKED_CLUSTER".equals(target.getType());
                if (isCluster) {
                    DbCluster cluster = getClusterForRestore(proxy, target.getRdsResourceId());
                    backendHost = cluster.getContainerHost();
                    backendPort = cluster.getContainerPort();
                    engine = cluster.getEngine();
                    masterUser = cluster.getMasterUsername();
                    masterPassword = cluster.getMasterPassword();
                    dbName = cluster.getDatabaseName();
                } else {
                    DbInstance instance = getInstanceForRestore(proxy, target.getRdsResourceId());
                    backendHost = instance.getContainerHost();
                    backendPort = instance.getContainerPort();
                    engine = instance.getEngine();
                    masterUser = instance.getMasterUsername();
                    masterPassword = instance.getMasterPassword();
                    dbName = instance.getDbName();
                }
                if (backendHost == null || backendPort <= 0) {
                    throw new AwsException("InvalidDBProxyStateFault",
                            "Target backend for proxy " + proxy.getDbProxyName() + " is not available.", 400);
                }
                String effectiveMasterUser = masterUser != null ? masterUser : "root";
                final String targetId = target.getRdsResourceId();
                final boolean cluster = isCluster;
                final String accountId = accountIdFromArn(proxy.getDbProxyArn());
                proxyManager.startProxy(dbProxyRelayKey(proxy), engine, proxy.isIamAuth(), proxyPort,
                        backendHost, backendPort, effectiveMasterUser, masterPassword, dbName,
                        (user, pw) -> cluster
                                ? validateDbClusterPasswordForAccount(accountId, targetId, user, pw)
                                : validateDbPasswordForAccount(accountId, targetId, user, pw));
                proxy.setStatus("available");
                persistRestoredProxy(proxy);
            } catch (Exception e) {
                releaseProxyPort(proxyPort);
                proxy.setStatus("insufficient-resource-limits");
                persistRestoredProxy(proxy);
                LOG.warnv(e, "Failed to restore RDS proxy {0}", proxy.getDbProxyName());
            }
        }
    }

    private void persistRestoredProxy(DbProxy proxy) {
        String defaultAccountId = defaultAccountId();
        String accountId = AwsArnUtils.accountOrDefault(proxy.getDbProxyArn(), defaultAccountId);
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            if (Objects.equals(accountId, defaultAccountId)) {
                // Use the regular path for the default account so legacy un-prefixed entries are
                // migrated according to AccountAwareStorageBackend's compatibility contract.
                aware.get(proxy.getDbProxyName());
                aware.put(proxy.getDbProxyName(), proxy);
            } else {
                aware.putForAccount(accountId, proxy.getDbProxyName(), proxy);
            }
        } else {
            proxies.put(proxy.getDbProxyName(), proxy);
        }
    }

    private DbProxyTargetGroup ensureRestoredDefaultTargetGroup(DbProxy proxy) {
        String defaultAccountId = defaultAccountId();
        String accountId = AwsArnUtils.accountOrDefault(proxy.getDbProxyArn(), defaultAccountId);
        DbProxyTargetGroup targetGroup;
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            targetGroup = Objects.equals(accountId, defaultAccountId)
                    ? aware.get(proxy.getDbProxyName()).orElse(null)
                    : aware.getForAccount(accountId, proxy.getDbProxyName()).orElse(null);
        } else {
            targetGroup = proxyTargetGroups.get(proxy.getDbProxyName()).orElse(null);
        }
        if (targetGroup != null) {
            return targetGroup;
        }

        Instant now = proxy.getCreatedAt() != null ? proxy.getCreatedAt() : Instant.now();
        targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName(proxy.getDbProxyName());
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn(AwsArnUtils.Arn.of("rds",
                AwsArnUtils.regionOrDefault(proxy.getDbProxyArn(), regionResolver.getDefaultRegion()),
                accountId, "target-group:prx-tg-" + randomResourceSuffix()).toString());
        targetGroup.setCreatedAt(now);
        targetGroup.setUpdatedAt(now);
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            if (Objects.equals(accountId, defaultAccountId)) {
                aware.put(proxy.getDbProxyName(), targetGroup);
            } else {
                aware.putForAccount(accountId, proxy.getDbProxyName(), targetGroup);
            }
        } else {
            proxyTargetGroups.put(proxy.getDbProxyName(), targetGroup);
        }
        return targetGroup;
    }

    private DbCluster getClusterForRestore(DbProxy proxy, String clusterId) {
        DbCluster cluster = findClusterForAccount(accountIdFromArn(proxy.getDbProxyArn()), clusterId);
        if (cluster == null) {
            throw new AwsException("DBClusterNotFoundFault",
                    "DB cluster " + clusterId + " not found.", 404);
        }
        return cluster;
    }

    private DbInstance getInstanceForRestore(DbProxy proxy, String instanceId) {
        DbInstance instance = findInstanceForAccount(accountIdFromArn(proxy.getDbProxyArn()), instanceId);
        if (instance == null) {
            throw new AwsException("DBInstanceNotFoundFault",
                    "DB instance " + instanceId + " not found.", 404);
        }
        return instance;
    }

    private DbCluster findClusterForAccount(String accountId, String clusterId) {
        if (accountId != null && clusters instanceof AccountAwareStorageBackend<DbCluster> aware) {
            return Objects.equals(accountId, defaultAccountId())
                    ? aware.get(clusterId).orElse(null)
                    : aware.getForAccount(accountId, clusterId).orElse(null);
        }
        return clusters.get(clusterId).orElse(null);
    }

    private DbInstance findInstanceForAccount(String accountId, String instanceId) {
        if (accountId != null && instances instanceof AccountAwareStorageBackend<DbInstance> aware) {
            return Objects.equals(accountId, defaultAccountId())
                    ? aware.get(instanceId).orElse(null)
                    : aware.getForAccount(accountId, instanceId).orElse(null);
        }
        return instances.get(instanceId).orElse(null);
    }

    private String accountIdFromArn(String arn) {
        return AwsArnUtils.accountOrDefault(arn, defaultAccountId());
    }

    private String defaultAccountId() {
        String configured = config.defaultAccountId();
        return configured == null || configured.isBlank() ? regionResolver.getAccountId() : configured;
    }

    private String dbProxyRelayKey(DbProxy proxy) {
        String identity = proxy.getDbProxyArn();
        if (identity == null || identity.isBlank()) {
            identity = proxy.getDbProxyName();
        }
        return "db-proxy:" + identity;
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

    private Collection<DbProxy> allProxies() {
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            return aware.scanAllAccounts();
        }
        return proxies.scan(k -> true);
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
