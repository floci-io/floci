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
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final Set<String> DB_PROXY_AUTH_SCHEMES = Set.of("SECRETS");
    private static final Set<String> DB_PROXY_IAM_AUTH_MODES =
            Set.of("DISABLED", "REQUIRED", "ENABLED");
    private static final Set<String> DB_PROXY_CLIENT_PASSWORD_AUTH_TYPES = Set.of(
            "MYSQL_NATIVE_PASSWORD", "MYSQL_CACHING_SHA2_PASSWORD",
            "POSTGRES_SCRAM_SHA_256", "POSTGRES_MD5", "SQL_SERVER_AUTHENTICATION");

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
        String dbiResourceId = "db-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        String dbInstanceArn = regionResolver.buildArn("rds", effectiveRegion, "db:" + id);
        if (findInstanceForScope(currentAccountId(), effectiveRegion, id) != null
                || scopedKeyExists(instances, currentAccountId(), dbResourceKey(effectiveRegion, id))) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "DB instance " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank() && !"default".equalsIgnoreCase(dbSubnetGroupName)) {
            getDbSubnetGroup(dbSubnetGroupName, effectiveRegion);
        }
        validateInstanceParameterGroup(
                paramGroupName, engineParam, engineVersion, effectiveRegion);
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
        String instanceStorageResourceId = dbiResourceId;
        PlacementResolution placement;

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            // Cluster member — share the cluster's container (none exists in mock mode)
            DbCluster cluster = Optional.ofNullable(
                            findClusterForScope(currentAccountId(), effectiveRegion,
                                    dbClusterIdentifier))
                    .orElseThrow(() ->
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
                        : volumeName(cluster.getVolumeId(),
                        resolvedClusterStorageResourceId(cluster));
            }
            instanceStorageResourceId = resolvedClusterStorageResourceId(cluster);
            placement = PlacementResolution.fromCluster(cluster);
        } else {
            placement = resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, effectiveRegion);
            if (!mock) {
                // Standalone instance — start its own container
                String image = imageForEngine(engine, engineVersion);
                instanceVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
                instanceDockerVolumeName = newVolumeName(
                        instanceVolumeId, instanceStorageResourceId);
                RdsContainerHandle handle = containerManager.start(
                        dbInstanceArn, id, instanceStorageResourceId,
                        instanceDockerVolumeName, engine, image,
                        masterUsername, masterPassword, dbName);
                backendHost = handle.getHost();
                backendPort = handle.getPort();
                containerId = handle.getContainerId();
                containerHost = handle.getHost();
                containerPort = handle.getPort();
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
        instance.setContainerStorageResourceId(instanceStorageResourceId);
        instance.setTags(tags);
        instance.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        instance.setDbSubnetGroupName(placement.dbSubnetGroupName());
        instance.setVpcId(placement.vpcId());
        instance.setAvailabilityZone(placement.availabilityZone());
        instance.setMultiAz(placement.multiAz());
        instance.setSubnetAvailabilityZones(placement.subnetAvailabilityZones());

        instance.setDbiResourceId(dbiResourceId);
        instance.setDbInstanceArn(dbInstanceArn);
        if (manageMasterUserPassword) {
            attachManagedMasterUserSecret(instance, effectiveRegion, masterUserSecretKmsKeyId);
        }

        if (!mock) {
            final String accountId = accountIdFromArn(instance.getDbInstanceArn());
            final String instanceRegion = regionFromArn(instance.getDbInstanceArn());
            proxyManager.startProxy(rdsResourceRelayKey(instance.getDbInstanceArn(), id),
                    engine, iamEnabled, proxyPort, backendHost, backendPort,
                    masterUsername, masterPassword, dbName,
                    (user, pw) -> validateDbPasswordForScope(
                            accountId, instanceRegion, id, user, pw));
        }

        if (dbClusterIdentifier != null && !dbClusterIdentifier.isBlank()) {
            DbCluster cluster = findClusterForScope(
                    currentAccountId(), effectiveRegion, dbClusterIdentifier);
            if (cluster != null) {
                cluster.getDbClusterMembers().add(id);
                putClusterForScope(currentAccountId(), effectiveRegion,
                        dbClusterIdentifier, cluster);
            }
        }

        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);
        LOG.infov("DB instance {0} created, engine={1}, endpoint=localhost:{2}", id, engine, String.valueOf(proxyPort));
        return instance;
    }

    public Map<String, String> listTagsForResource(String resourceName) {
        return listTagsForResource(resourceName, resourceRegionOrDefault(resourceName));
    }

    public Map<String, String> listTagsForResource(String resourceName, String region) {
        return Map.copyOf(resolveTagHandle(resourceName, region).tags());
    }

    public void addTagsToResource(String resourceName, Map<String, String> tags) {
        addTagsToResource(resourceName, tags, resourceRegionOrDefault(resourceName));
    }

    public synchronized void addTagsToResource(
            String resourceName, Map<String, String> tags, String region) {
        TagHandle handle = resolveTagHandle(resourceName, region);
        Map<String, String> updated = new java.util.LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        removeTagsFromResource(resourceName, tagKeys, resourceRegionOrDefault(resourceName));
    }

    public synchronized void removeTagsFromResource(
            String resourceName, Collection<String> tagKeys, String region) {
        TagHandle handle = resolveTagHandle(resourceName, region);
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
    private TagHandle resolveTagHandle(String resourceName, String region) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }

        String effectiveRegion = effectiveRegion(region);
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
            if (!effectiveRegion.equals(arn.region())) {
                throw new AwsException("InvalidParameterValue",
                        "ResourceName is not in region " + effectiveRegion + ": " + resourceName, 400);
            }
            if (!Objects.equals(regionResolver.getAccountId(), arn.accountId())) {
                throw new AwsException("InvalidParameterValue",
                        "ResourceName is not in the current account: " + resourceName, 400);
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
                DbInstance instance = getDbInstance(resourceId, effectiveRegion);
                yield new TagHandle(instance.getTags(), updated -> {
                    instance.setTags(updated);
                    putInstanceForScope(currentAccountId(), effectiveRegion,
                            resourceId, instance);
                });
            }
            case "cluster" -> {
                DbCluster cluster = getDbCluster(resourceId, effectiveRegion);
                yield new TagHandle(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    putClusterForScope(currentAccountId(), effectiveRegion,
                            resourceId, cluster);
                });
            }
            case "subgrp" -> {
                DbSubnetGroup group = getDbSubnetGroup(resourceId, effectiveRegion);
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    putSubnetGroupForScope(
                            currentAccountId(), effectiveRegion, resourceId, group);
                });
            }
            case "db-proxy" -> {
                DbProxy proxy = proxies.scan(k -> true).stream()
                        .filter(candidate -> effectiveRegion.equals(regionFromArn(candidate.getDbProxyArn())))
                        .filter(candidate -> resourceName.equals(candidate.getDbProxyArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBProxyNotFoundFault",
                                "DB proxy " + resourceId + " not found.", 404));
                proxy = findDbProxy(proxy.getDbProxyName(), effectiveRegion)
                        .filter(candidate -> resourceName.equals(candidate.getDbProxyArn()))
                        .orElseThrow(() -> new AwsException("DBProxyNotFoundFault",
                                "DB proxy " + resourceId + " not found.", 404));
                DbProxy resolvedProxy = proxy;
                yield new TagHandle(proxy.getTags(), updated -> {
                    DbProxy updatedProxy = copyDbProxy(resolvedProxy);
                    updatedProxy.setTags(updated);
                    proxies.put(dbProxyKey(effectiveRegion, resolvedProxy.getDbProxyName()), updatedProxy);
                });
            }
            // Valid RDS resource types Floci does not model yet (og, pg, snapshot, ...) — taggable
            // on real AWS, so the message states the Floci limitation rather than AWS semantics.
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not yet implemented by Floci: " + resourceName, 400);
        };
    }

    private String resourceRegionOrDefault(String resourceName) {
        return resourceName != null && resourceName.startsWith("arn:")
                ? regionFromArn(resourceName) : regionResolver.getDefaultRegion();
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
        return getDbInstance(id, regionResolver.getDefaultRegion());
    }

    public DbInstance getDbInstance(String id, String region) {
        String effectiveRegion = effectiveRegion(region);
        return Optional.ofNullable(findInstanceForScope(
                currentAccountId(), effectiveRegion, id)).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DB instance " + id + " not found.", 404));
    }

    public Collection<DbInstance> listDbInstances(String filterId) {
        return listDbInstances(filterId, regionResolver.getDefaultRegion());
    }

    public Collection<DbInstance> listDbInstances(String filterId, String region) {
        String accountId = currentAccountId();
        String effectiveRegion = effectiveRegion(region);
        Map<String, DbInstance> unique = new LinkedHashMap<>();
        for (DbInstance instance : instances.scan(k -> true)) {
            if ((filterId == null || filterId.isBlank()
                    || instance.getDbInstanceIdentifier().equalsIgnoreCase(filterId))
                    && hasRdsResourceIdentity(
                    instance.getDbInstanceArn(), accountId, effectiveRegion, "db",
                    instance.getDbInstanceIdentifier())) {
                DbInstance canonical = findInstanceForScope(
                        accountId, effectiveRegion, instance.getDbInstanceIdentifier());
                if (canonical != null) {
                    unique.put(canonical.getDbInstanceArn(), canonical);
                }
            }
        }
        return unique.values();
    }

    public DbInstance modifyDbInstance(String id, String newPassword, Boolean iamEnabled,
                                       String dbSubnetGroupName) {
        return modifyDbInstance(id, newPassword, iamEnabled, dbSubnetGroupName, null);
    }

    public DbInstance modifyDbInstance(String id, String newPassword, Boolean iamEnabled,
                                       String dbSubnetGroupName, List<String> vpcSecurityGroupIds) {
        return modifyDbInstance(id, newPassword, iamEnabled, dbSubnetGroupName,
                vpcSecurityGroupIds, regionResolver.getDefaultRegion());
    }

    public DbInstance modifyDbInstance(
            String id, String newPassword, Boolean iamEnabled,
            String dbSubnetGroupName, List<String> vpcSecurityGroupIds, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbInstance instance = getDbInstance(id, effectiveRegion);
        instance.setStatus(DbInstanceStatus.AVAILABLE);
        if (newPassword != null && !newPassword.isBlank()) {
            instance.setMasterPassword(newPassword);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        if (dbSubnetGroupName != null && !dbSubnetGroupName.isBlank()) {
            getDbSubnetGroup(dbSubnetGroupName, effectiveRegion);
            instance.setDbSubnetGroupName(dbSubnetGroupName);
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            instance.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);
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
        return rebootDbInstance(id, regionResolver.getDefaultRegion());
    }

    public DbInstance rebootDbInstance(String id, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbInstance instance = getDbInstance(id, effectiveRegion);

        instance.setStatus(DbInstanceStatus.REBOOTING);
        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);

        boolean mock = config.services().rds().mock();
        if (!mock) {
            // Stop proxy during reboot
            proxyManager.stopProxy(rdsResourceRelayKey(instance.getDbInstanceArn(), id));

            // Restart container if it's a standalone instance
            if (instance.getDbClusterIdentifier() == null && instance.getContainerId() != null) {
                try {
                    containerManager.stop(buildHandle(instance));
                } catch (RuntimeException | Error e) {
                    instance.setStatus(DbInstanceStatus.FAILED);
                    try {
                        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);
                    } catch (RuntimeException persistFailure) {
                        e.addSuppressed(persistFailure);
                    }
                    throw e;
                }
                String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
                String storageResourceId = resolvedInstanceStorageResourceId(instance);
                String dockerVolumeName = resolvedInstanceDockerVolumeName(instance);
                RdsContainerHandle handle = containerManager.start(
                        instance.getDbInstanceArn(), id, storageResourceId,
                        dockerVolumeName, instance.getEngine(), image, instance.getMasterUsername(),
                        instance.getMasterPassword(), instance.getDbName());
                instance.setContainerStorageResourceId(storageResourceId);
                instance.setDockerVolumeName(dockerVolumeName);
                instance.setContainerId(handle.getContainerId());
                instance.setContainerHost(handle.getHost());
                instance.setContainerPort(handle.getPort());
            }
        }

        instance.setStatus(DbInstanceStatus.AVAILABLE);
        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);

        if (!mock) {
            String effectiveMasterUser = instance.getMasterUsername() != null
                    ? instance.getMasterUsername() : "root";
            final String accountId = accountIdFromArn(instance.getDbInstanceArn());
            final String instanceRegion = regionFromArn(instance.getDbInstanceArn());
            proxyManager.startProxy(rdsResourceRelayKey(instance.getDbInstanceArn(), id),
                    instance.getEngine(),
                    instance.isIamDatabaseAuthenticationEnabled(),
                    instance.getProxyPort(), instance.getContainerHost(), instance.getContainerPort(),
                    effectiveMasterUser, instance.getMasterPassword(), instance.getDbName(),
                    (user, pw) -> validateDbPasswordForScope(
                            accountId, instanceRegion, id, user, pw));
        }

        LOG.infov("DB instance {0} rebooted", id);
        return instance;
    }

    public synchronized void deleteDbInstance(String id) {
        deleteDbInstance(id, regionResolver.getDefaultRegion());
    }

    public synchronized void deleteDbInstance(String id, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbInstance instance = Optional.ofNullable(
                        findInstanceForScope(currentAccountId(), effectiveRegion, id))
                .orElseThrow(() ->
                new AwsException("DBInstanceNotFound", "DB instance " + id + " not found.", 404));

        if (isRegisteredProxyTarget(
                "RDS_INSTANCE", id, regionFromArn(instance.getDbInstanceArn()))) {
            throw new AwsException("InvalidDBInstanceState",
                    "DB instance " + id + " is registered with a DB proxy target group.", 400);
        }

        instance.setStatus(DbInstanceStatus.DELETING);
        putInstanceForScope(currentAccountId(), effectiveRegion, id, instance);

        boolean mock = config.services().rds().mock();
        if (!mock) {
            proxyManager.stopProxy(rdsResourceRelayKey(instance.getDbInstanceArn(), id));
        }

        String clusterId = instance.getDbClusterIdentifier();
        if (clusterId == null || clusterId.isBlank()) {
            // Standalone — stop its container and clean up its Docker volume (neither exists in mock mode)
            if (!mock) {
                if (instance.getContainerId() != null) {
                    containerManager.stop(buildHandle(instance));
                } else {
                    containerManager.stopByRuntimeId(instance.getDbInstanceArn());
                }
                containerManager.removeVolume(
                        instance.getDbInstanceArn(),
                        resolvedInstanceStorageResourceId(instance),
                        resolvedInstanceDockerVolumeName(instance));
            }
        } else {
            // Cluster member — remove from cluster's member list
            DbCluster cluster = findClusterForScope(
                    currentAccountId(), effectiveRegion, clusterId);
            if (cluster != null) {
                cluster.getDbClusterMembers().remove(id);
                putClusterForScope(currentAccountId(), effectiveRegion, clusterId, cluster);
            }
        }

        releaseProxyPort(instance.getProxyPort());
        deleteInstanceForScope(currentAccountId(), effectiveRegion, id);
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
        String clusterResourceId = "cluster-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        String clusterArn = regionResolver.buildArn("rds", effectiveRegion, "cluster:" + id);
        if (findClusterForScope(currentAccountId(), effectiveRegion, id) != null
                || scopedKeyExists(clusters, currentAccountId(), dbResourceKey(effectiveRegion, id))) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "DB cluster " + id + " already exists.", 400);
        }

        DatabaseEngine engine = resolveEngine(engineParam);
        validateClusterParameterGroup(
                paramGroupName, engineParam, engineVersion, effectiveRegion);
        PlacementResolution placement = resolvePlacement(dbSubnetGroupName, availabilityZone, multiAz, effectiveRegion);

        boolean mock = config.services().rds().mock();
        // Always reserve a unique port (even in mock) so endpoints stay distinct and usedPorts
        // is consistent; mock mode only skips starting the container and auth proxy.
        int proxyPort = allocateProxyPort();
        DbEndpoint endpoint = new DbEndpoint(mock ? "localhost" : proxyEndpointHost(), proxyPort);
        DbCluster cluster = new DbCluster(id, engine, engineVersion, masterUsername, masterPassword,
                databaseName, DbInstanceStatus.AVAILABLE, endpoint, endpoint,
                iamEnabled, new ArrayList<>(), paramGroupName, Instant.now(), proxyPort);
        cluster.setContainerStorageResourceId(clusterResourceId);
        if (!mock) {
            String image = imageForEngine(engine, engineVersion);
            String clusterVolumeId = String.format("%06x", new SecureRandom().nextInt(0xFFFFFF));
            String clusterDockerVolumeName = newVolumeName(
                    clusterVolumeId, clusterResourceId);
            RdsContainerHandle handle = containerManager.start(
                    clusterArn, id, clusterResourceId, clusterDockerVolumeName,
                    engine, image,
                    masterUsername, masterPassword, databaseName);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setVolumeId(clusterVolumeId);
            cluster.setDockerVolumeName(clusterDockerVolumeName);
        }
        cluster.setDbSubnetGroupName(placement.dbSubnetGroupName());
        cluster.setVpcId(placement.vpcId());
        cluster.setAvailabilityZone(placement.availabilityZone());
        cluster.setMultiAz(placement.multiAz());
        cluster.setSubnetAvailabilityZones(placement.subnetAvailabilityZones());

        cluster.setDbClusterResourceId(clusterResourceId);
        cluster.setDbClusterArn(clusterArn);

        if (!mock) {
            String effectiveMasterUser = masterUsername != null ? masterUsername : "root";
            final String accountId = accountIdFromArn(cluster.getDbClusterArn());
            final String clusterRegion = regionFromArn(cluster.getDbClusterArn());
            proxyManager.startProxy(rdsResourceRelayKey(cluster.getDbClusterArn(), id),
                    engine, iamEnabled, proxyPort,
                    cluster.getContainerHost(), cluster.getContainerPort(),
                    effectiveMasterUser, masterPassword, databaseName,
                    (user, pw) -> validateDbClusterPasswordForScope(
                            accountId, clusterRegion, id, user, pw));
        }

        putClusterForScope(currentAccountId(), effectiveRegion, id, cluster);
        LOG.infov("DB cluster {0} created (mock={1}), engine={2}, endpoint=localhost:{3}",
                id, String.valueOf(mock), engine, String.valueOf(proxyPort));
        return cluster;
    }

    public DbCluster getDbCluster(String id) {
        return getDbCluster(id, regionResolver.getDefaultRegion());
    }

    public DbCluster getDbCluster(String id, String region) {
        String effectiveRegion = effectiveRegion(region);
        return Optional.ofNullable(findClusterForScope(
                currentAccountId(), effectiveRegion, id)).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));
    }

    public Collection<DbCluster> listDbClusters(String filterId) {
        return listDbClusters(filterId, regionResolver.getDefaultRegion());
    }

    public Collection<DbCluster> listDbClusters(String filterId, String region) {
        String accountId = currentAccountId();
        String effectiveRegion = effectiveRegion(region);
        Map<String, DbCluster> unique = new LinkedHashMap<>();
        for (DbCluster cluster : clusters.scan(k -> true)) {
            if ((filterId == null || filterId.isBlank()
                    || cluster.getDbClusterIdentifier().equalsIgnoreCase(filterId))
                    && hasRdsResourceIdentity(
                    cluster.getDbClusterArn(), accountId, effectiveRegion, "cluster",
                    cluster.getDbClusterIdentifier())) {
                DbCluster canonical = findClusterForScope(
                        accountId, effectiveRegion, cluster.getDbClusterIdentifier());
                if (canonical != null) {
                    unique.put(canonical.getDbClusterArn(), canonical);
                }
            }
        }
        return unique.values();
    }

    public DbCluster modifyDbCluster(String id, String newPassword, Boolean iamEnabled) {
        return modifyDbCluster(id, newPassword, iamEnabled,
                regionResolver.getDefaultRegion());
    }

    public DbCluster modifyDbCluster(
            String id, String newPassword, Boolean iamEnabled, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbCluster cluster = getDbCluster(id, effectiveRegion);
        if (newPassword != null && !newPassword.isBlank()) {
            cluster.setMasterPassword(newPassword);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        putClusterForScope(currentAccountId(), effectiveRegion, id, cluster);
        LOG.infov("DB cluster {0} modified", id);
        return cluster;
    }

    public synchronized void deleteDbCluster(String id) {
        deleteDbCluster(id, regionResolver.getDefaultRegion());
    }

    public synchronized void deleteDbCluster(String id, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbCluster cluster = Optional.ofNullable(
                        findClusterForScope(currentAccountId(), effectiveRegion, id))
                .orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + id + " not found.", 404));

        if (!cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "DB cluster " + id + " still has DB instances.", 400);
        }
        if (isRegisteredProxyTarget(
                "TRACKED_CLUSTER", id, regionFromArn(cluster.getDbClusterArn()))) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "DB cluster " + id + " is registered with a DB proxy target group.", 400);
        }

        cluster.setStatus(DbInstanceStatus.DELETING);
        putClusterForScope(currentAccountId(), effectiveRegion, id, cluster);

        if (!config.services().rds().mock()) {
            proxyManager.stopProxy(rdsResourceRelayKey(cluster.getDbClusterArn(), id));
            if (cluster.getContainerId() != null) {
                containerManager.stop(buildClusterHandle(cluster));
            } else {
                containerManager.stopByRuntimeId(cluster.getDbClusterArn());
            }
            containerManager.removeVolume(
                    cluster.getDbClusterArn(),
                    resolvedClusterStorageResourceId(cluster),
                    resolvedClusterDockerVolumeName(cluster));
        }

        releaseProxyPort(cluster.getProxyPort());
        deleteClusterForScope(currentAccountId(), effectiveRegion, id);
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

    public DbProxy createDbProxy(String dbProxyName, String engineFamily, boolean requireTls,
                                 boolean iamAuth, String roleArn, List<String> vpcSubnetIds,
                                 List<String> vpcSecurityGroupIds, List<DbProxyAuth> auth,
                                 int idleClientTimeout, boolean debugLogging,
                                 Map<String, String> tags, String region) {
        return createDbProxy(dbProxyName, engineFamily, requireTls, iamAuth, "NONE", roleArn,
                vpcSubnetIds, vpcSecurityGroupIds, auth, idleClientTimeout, debugLogging, tags, region);
    }

    public synchronized DbProxy createDbProxy(String dbProxyName, String engineFamily, boolean requireTls,
                                               boolean iamAuth, String defaultAuthScheme, String roleArn,
                                               List<String> vpcSubnetIds,
                                               List<String> vpcSecurityGroupIds, List<DbProxyAuth> auth,
                                               int idleClientTimeout, boolean debugLogging,
                                               Map<String, String> tags, String region) {
        String effectiveDefaultAuthScheme = normalizeDefaultAuthScheme(defaultAuthScheme);
        validateDbProxyCreate(dbProxyName, engineFamily, roleArn, vpcSubnetIds, auth,
                effectiveDefaultAuthScheme, idleClientTimeout);
        String effectiveRegion = effectiveRegion(region);
        String vpcId = resolveDbProxyVpc(vpcSubnetIds, effectiveRegion);
        String proxyKey = dbProxyKey(effectiveRegion, dbProxyName);
        String accountId = currentAccountId();
        if (findDbProxy(dbProxyName, effectiveRegion).isPresent()
                || scopedKeyExists(proxies, accountId, proxyKey)) {
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
        proxy.setIamAuth(iamAuth || "IAM_AUTH".equals(effectiveDefaultAuthScheme)
                || (auth != null && auth.stream()
                .anyMatch(entry -> isIamEnabledAuthMode(entry.getIamAuth()))));
        proxy.setDefaultAuthScheme(effectiveDefaultAuthScheme);
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
        proxy.setUpdatedAt(now);
        proxy.setVpcId(vpcId);
        String resourceId = "prx-" + randomResourceSuffix();
        proxy.setDbProxyResourceId(resourceId);
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
            putProxyForAccount(accountId, proxyKey, proxy);
            putTargetGroupForAccount(accountId, proxyKey, targetGroup);
        } catch (RuntimeException createFailure) {
            attemptRollback(createFailure,
                    () -> deleteTargetGroupKey(accountId, proxyKey));
            attemptRollback(createFailure,
                    () -> deleteProxyKey(accountId, proxyKey));
            boolean stateRemoved = false;
            try {
                stateRemoved = getProxyForAccount(accountId, proxyKey).isEmpty()
                        && getTargetGroupForAccount(accountId, proxyKey).isEmpty();
            } catch (RuntimeException verificationFailure) {
                createFailure.addSuppressed(verificationFailure);
            }
            if (stateRemoved) {
                releaseProxyPort(proxyPort);
                throw createFailure;
            }
            attemptRollback(createFailure,
                    () -> putProxyForAccount(accountId, proxyKey, proxy));
            attemptRollback(createFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, targetGroup));
            throw createFailure;
        }
        LOG.infov("DB proxy {0} created (mock={1}), endpoint={2}",
                dbProxyName, String.valueOf(mock), proxy.getEndpoint());
        return proxy;
    }

    public synchronized DbProxy modifyDbProxy(
            String dbProxyName, String defaultAuthScheme, List<DbProxyAuth> auth,
            Boolean requireTls, Integer idleClientTimeout, Boolean debugLogging,
            String roleArn, List<String> vpcSecurityGroupIds, Map<String, String> tags,
            String region) {
        String effectiveRegion = effectiveRegion(region);
        String proxyKey = dbProxyKey(effectiveRegion, dbProxyName);
        DbProxy existing = getDbProxy(dbProxyName, effectiveRegion);
        DbProxy updated = copyDbProxy(existing);

        if (defaultAuthScheme != null) {
            updated.setDefaultAuthScheme(normalizeDefaultAuthScheme(defaultAuthScheme));
        } else if (updated.getDefaultAuthScheme() == null) {
            updated.setDefaultAuthScheme("NONE");
        }
        if (auth != null) {
            updated.setAuth(auth);
        }
        if (requireTls != null) {
            updated.setRequireTls(requireTls);
        }
        if (idleClientTimeout != null) {
            updated.setIdleClientTimeout(idleClientTimeout);
        }
        if (debugLogging != null) {
            updated.setDebugLogging(debugLogging);
        }
        if (roleArn != null) {
            updated.setRoleArn(roleArn);
        }
        if (vpcSecurityGroupIds != null) {
            updated.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }
        if (tags != null) {
            updated.setTags(tags);
        }
        boolean effectiveIamAuth = "IAM_AUTH".equals(updated.getDefaultAuthScheme())
                || updated.getAuth().stream()
                .anyMatch(entry -> isIamEnabledAuthMode(entry.getIamAuth()));
        updated.setIamAuth(effectiveIamAuth);

        validateDbProxyCreate(updated.getDbProxyName(), updated.getEngineFamily(),
                updated.getRoleArn(), updated.getVpcSubnetIds(), updated.getAuth(),
                normalizeDefaultAuthScheme(updated.getDefaultAuthScheme()),
                updated.getIdleClientTimeout());
        if (sameDbProxyState(existing, updated)) {
            return existing;
        }

        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(
                dbProxyName, "default", effectiveRegion);
        DbProxyTarget registeredTarget = targetGroup.getTargets().isEmpty()
                ? null : targetGroup.getTargets().get(0);
        if (registeredTarget != null && !config.services().rds().mock()
                && "IAM_AUTH".equals(updated.getDefaultAuthScheme())) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "DefaultAuthScheme IAM_AUTH is available for control-plane emulation only; "
                            + "the real-mode relay does not support end-to-end IAM authentication.",
                    400);
        }

        updated.setUpdatedAt(Instant.now());
        String accountId = accountIdFromArn(existing.getDbProxyArn());
        if (registeredTarget == null || config.services().rds().mock()
                || existing.isIamAuth() == updated.isIamAuth()) {
            try {
                putProxyForAccount(accountId, proxyKey, updated);
                return updated;
            } catch (RuntimeException updateFailure) {
                attemptRollback(updateFailure,
                        () -> putProxyForAccount(accountId, proxyKey, existing));
                throw updateFailure;
            }
        }

        try {
            proxyManager.stopProxy(dbProxyRelayKey(existing));
        } catch (RuntimeException stopFailure) {
            attemptRollback(stopFailure,
                    () -> startDbProxyRelay(existing, registeredTarget));
            throw stopFailure;
        }
        try {
            startDbProxyRelay(updated, registeredTarget);
            putProxyForAccount(accountId, proxyKey, updated);
            return updated;
        } catch (RuntimeException updateFailure) {
            attemptRollback(updateFailure,
                    () -> proxyManager.stopProxy(dbProxyRelayKey(updated)));
            attemptRollback(updateFailure,
                    () -> putProxyForAccount(accountId, proxyKey, existing));
            attemptRollback(updateFailure,
                    () -> startDbProxyRelay(existing, registeredTarget));
            throw updateFailure;
        }
    }

    /**
     * Registers the target cluster/instance for a proxy's (single, "default") target group and — in
     * real mode — starts the auth relay forwarding the proxy endpoint to the target's backend
     * container. This is the point where the backend host/port become known.
     */
    public DbProxyTargetGroup registerDbProxyTargets(String dbProxyName, String targetGroupName,
                                                      List<String> dbClusterIdentifiers,
                                                      List<String> dbInstanceIdentifiers,
                                                      int maxConnectionsPercent,
                                                      int maxIdleConnectionsPercent) {
        return registerDbProxyTargets(dbProxyName, targetGroupName, dbClusterIdentifiers,
                dbInstanceIdentifiers, maxConnectionsPercent, maxIdleConnectionsPercent,
                regionResolver.getDefaultRegion());
    }

    public synchronized DbProxyTargetGroup registerDbProxyTargets(
            String dbProxyName, String targetGroupName,
            List<String> dbClusterIdentifiers, List<String> dbInstanceIdentifiers,
            int maxConnectionsPercent, int maxIdleConnectionsPercent, String region) {
        String effectiveRegion = effectiveRegion(region);
        String proxyKey = dbProxyKey(effectiveRegion, dbProxyName);
        DbProxy proxy = getDbProxy(dbProxyName, effectiveRegion);
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, effectiveRegion);
        if (!config.services().rds().mock()
                && "IAM_AUTH".equals(proxy.getDefaultAuthScheme())) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "DefaultAuthScheme IAM_AUTH is available for control-plane emulation only; "
                            + "the real-mode relay does not support end-to-end IAM authentication.",
                    400);
        }

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
        String proxyAccountId = accountIdFromArn(proxy.getDbProxyArn());

        if (clusterCount == 1) {
            String clusterId = dbClusterIdentifiers.get(0);
            DbCluster cluster = Optional.ofNullable(findClusterForScope(
                            proxyAccountId, effectiveRegion, clusterId))
                    .orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault", "DB cluster " + clusterId + " not found.", 404));
            if (cluster.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBClusterStateFault",
                        "DB cluster " + clusterId + " is not available.", 400);
            }
            validateProxyTargetScope(cluster.getDbClusterArn(), proxyAccountId,
                    effectiveRegion, "DB cluster", clusterId);
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
            DbInstance instance = Optional.ofNullable(findInstanceForScope(
                            proxyAccountId, effectiveRegion, instanceId))
                    .orElseThrow(() ->
                    new AwsException("DBInstanceNotFound", "DB instance " + instanceId + " not found.", 404));
            if (instance.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBInstanceState",
                        "DB instance " + instanceId + " is not available.", 400);
            }
            validateProxyTargetScope(instance.getDbInstanceArn(), proxyAccountId,
                    effectiveRegion, "DB instance", instanceId);
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

        boolean realMode = !config.services().rds().mock();
        if (realMode) {
            if (backendHost == null || backendPort <= 0) {
                throw new AwsException("InvalidDBProxyStateFault",
                        "Target backend for proxy " + dbProxyName + " is not available.", 400);
            }
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
        DbProxy updatedProxy = null;
        if (!"available".equals(proxy.getStatus())) {
            updatedProxy = copyDbProxy(proxy);
            updatedProxy.setStatus("available");
            updatedProxy.setUpdatedAt(Instant.now());
        }
        try {
            if (realMode) {
                String effectiveMasterUser = masterUser != null ? masterUser : "root";
                final String targetId = target.getRdsResourceId();
                final boolean isCluster = "TRACKED_CLUSTER".equals(target.getType());
                final String targetRegion = regionFromArn(proxy.getDbProxyArn());
                proxyManager.startProxy(dbProxyRelayKey(proxy), engine, proxy.isIamAuth(),
                        proxy.getProxyPort(), backendHost, backendPort, effectiveMasterUser,
                        masterPassword, dbName,
                        (user, pw) -> isCluster
                                ? validateDbClusterPasswordForScope(
                                        proxyAccountId, targetRegion, targetId, user, pw)
                                : validateDbPasswordForScope(
                                        proxyAccountId, targetRegion, targetId, user, pw));
            }
            putTargetGroupForAccount(proxyAccountId, proxyKey, updatedTargetGroup);
            if (updatedProxy != null) {
                putProxyForAccount(proxyAccountId, proxyKey, updatedProxy);
            }
        } catch (RuntimeException e) {
            if (realMode) {
                attemptRollback(e,
                        () -> proxyManager.stopProxy(dbProxyRelayKey(proxy)));
            }
            attemptRollback(e,
                    () -> putTargetGroupForAccount(proxyAccountId, proxyKey, targetGroup));
            if (updatedProxy != null) {
                attemptRollback(e,
                        () -> putProxyForAccount(proxyAccountId, proxyKey, proxy));
            }
            throw e;
        }
        LOG.infov("DB proxy {0} target group default registered target {1}",
                dbProxyName, target.getRdsResourceId());
        return updatedTargetGroup;
    }

    public DbProxyTargetGroup configureDbProxyTargetGroup(String dbProxyName,
                                                           String targetGroupName,
                                                           Integer maxConnectionsPercent,
                                                           Integer maxIdleConnectionsPercent) {
        return configureDbProxyTargetGroup(dbProxyName, targetGroupName, maxConnectionsPercent,
                maxIdleConnectionsPercent, null, null, null, regionResolver.getDefaultRegion());
    }

    public DbProxyTargetGroup configureDbProxyTargetGroup(
            String dbProxyName, String targetGroupName,
            Integer maxConnectionsPercent, Integer maxIdleConnectionsPercent, String region) {
        return configureDbProxyTargetGroup(dbProxyName, targetGroupName, maxConnectionsPercent,
                maxIdleConnectionsPercent, null, null, null, region);
    }

    public synchronized DbProxyTargetGroup configureDbProxyTargetGroup(
            String dbProxyName, String targetGroupName,
            Integer maxConnectionsPercent, Integer maxIdleConnectionsPercent,
            Integer connectionBorrowTimeout, String initQuery,
            List<String> sessionPinningFilters, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbProxy proxy = getDbProxy(dbProxyName, effectiveRegion);
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, effectiveRegion);
        validatePoolConfiguration(proxy, maxConnectionsPercent, maxIdleConnectionsPercent,
                connectionBorrowTimeout, sessionPinningFilters);
        DbProxyTargetGroup updatedTargetGroup = copyProxyTargetGroup(targetGroup);
        if (maxConnectionsPercent != null) {
            updatedTargetGroup.setMaxConnectionsPercent(maxConnectionsPercent);
        }
        if (maxIdleConnectionsPercent != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(maxIdleConnectionsPercent);
        } else if (maxConnectionsPercent != null) {
            updatedTargetGroup.setMaxIdleConnectionsPercent(maxConnectionsPercent / 2);
        }
        if (connectionBorrowTimeout != null) {
            updatedTargetGroup.setConnectionBorrowTimeout(connectionBorrowTimeout);
        }
        if (initQuery != null) {
            updatedTargetGroup.setInitQuery(initQuery.isBlank() ? null : initQuery);
        }
        if (sessionPinningFilters != null) {
            updatedTargetGroup.setSessionPinningFilters(sessionPinningFilters);
        }
        if (sameProxyTargetGroupConfiguration(targetGroup, updatedTargetGroup)) {
            return targetGroup;
        }
        updatedTargetGroup.setUpdatedAt(Instant.now());
        String proxyKey = dbProxyKey(effectiveRegion, dbProxyName);
        String accountId = accountIdFromArn(proxy.getDbProxyArn());
        try {
            putTargetGroupForAccount(accountId, proxyKey, updatedTargetGroup);
            return updatedTargetGroup;
        } catch (RuntimeException updateFailure) {
            attemptRollback(updateFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, targetGroup));
            throw updateFailure;
        }
    }

    public synchronized DbProxyTargetGroup reconcileDbProxyTargetGroup(
            String dbProxyName, String targetGroupName,
            List<String> dbClusterIdentifiers, List<String> dbInstanceIdentifiers,
            int maxConnectionsPercent, int maxIdleConnectionsPercent,
            int connectionBorrowTimeout, String initQuery,
            List<String> sessionPinningFilters, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbProxy proxy = getDbProxy(dbProxyName, effectiveRegion);
        DbProxyTargetGroup original = getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, effectiveRegion);
        List<String> clusterIds = dbClusterIdentifiers != null
                ? dbClusterIdentifiers : List.of();
        List<String> instanceIds = dbInstanceIdentifiers != null
                ? dbInstanceIdentifiers : List.of();
        validateProxyTargetSelection(proxy, clusterIds, instanceIds, effectiveRegion);
        validatePoolConfiguration(proxy, maxConnectionsPercent, maxIdleConnectionsPercent,
                connectionBorrowTimeout, sessionPinningFilters);

        DbProxyTarget currentTarget = original.getTargets().isEmpty()
                ? null : original.getTargets().get(0);
        String desiredType = !clusterIds.isEmpty() ? "TRACKED_CLUSTER"
                : (!instanceIds.isEmpty() ? "RDS_INSTANCE" : null);
        String desiredId = !clusterIds.isEmpty() ? clusterIds.get(0)
                : (!instanceIds.isEmpty() ? instanceIds.get(0) : null);
        boolean targetUnchanged = currentTarget == null
                ? desiredType == null
                : Objects.equals(currentTarget.getType(), desiredType)
                && Objects.equals(currentTarget.getRdsResourceId(), desiredId);

        if (targetUnchanged) {
            return applyExactProxyTargetGroupConfiguration(proxy, original,
                    maxConnectionsPercent, maxIdleConnectionsPercent,
                    connectionBorrowTimeout, initQuery, sessionPinningFilters, effectiveRegion);
        }

        try {
            if (currentTarget != null) {
                deregisterDbProxyTargets(dbProxyName, targetGroupName,
                        "TRACKED_CLUSTER".equals(currentTarget.getType())
                                ? List.of(currentTarget.getRdsResourceId()) : List.of(),
                        "RDS_INSTANCE".equals(currentTarget.getType())
                                ? List.of(currentTarget.getRdsResourceId()) : List.of(),
                        effectiveRegion);
            }
            DbProxyTargetGroup reconciled = desiredType == null
                    ? getDefaultProxyTargetGroup(dbProxyName, targetGroupName, effectiveRegion)
                    : registerDbProxyTargets(dbProxyName, targetGroupName,
                    clusterIds, instanceIds, 0, 0, effectiveRegion);
            return applyExactProxyTargetGroupConfiguration(proxy, reconciled,
                    maxConnectionsPercent, maxIdleConnectionsPercent,
                    connectionBorrowTimeout, initQuery, sessionPinningFilters, effectiveRegion);
        } catch (RuntimeException reconciliationFailure) {
            if (!config.services().rds().mock()) {
                attemptRollback(reconciliationFailure,
                        () -> proxyManager.stopProxy(dbProxyRelayKey(proxy)));
            }
            String proxyKey = dbProxyKey(effectiveRegion, dbProxyName);
            String accountId = accountIdFromArn(proxy.getDbProxyArn());
            attemptRollback(reconciliationFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, original));
            restartDbProxyRelayAfterFailure(proxy, original, reconciliationFailure);
            throw reconciliationFailure;
        }
    }

    private DbProxyTargetGroup applyExactProxyTargetGroupConfiguration(
            DbProxy proxy, DbProxyTargetGroup targetGroup,
            int maxConnectionsPercent, int maxIdleConnectionsPercent,
            int connectionBorrowTimeout, String initQuery,
            List<String> sessionPinningFilters, String region) {
        DbProxyTargetGroup updated = copyProxyTargetGroup(targetGroup);
        updated.setMaxConnectionsPercent(maxConnectionsPercent);
        updated.setMaxIdleConnectionsPercent(maxIdleConnectionsPercent);
        updated.setConnectionBorrowTimeout(connectionBorrowTimeout);
        updated.setInitQuery(initQuery == null || initQuery.isBlank() ? null : initQuery);
        updated.setSessionPinningFilters(sessionPinningFilters);
        if (sameProxyTargetGroupConfiguration(targetGroup, updated)) {
            return targetGroup;
        }
        updated.setUpdatedAt(Instant.now());
        String proxyKey = dbProxyKey(region, proxy.getDbProxyName());
        String accountId = accountIdFromArn(proxy.getDbProxyArn());
        try {
            putTargetGroupForAccount(accountId, proxyKey, updated);
            return updated;
        } catch (RuntimeException updateFailure) {
            attemptRollback(updateFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, targetGroup));
            throw updateFailure;
        }
    }

    public void deregisterDbProxyTargets(String dbProxyName, String targetGroupName,
                                         List<String> dbClusterIdentifiers,
                                         List<String> dbInstanceIdentifiers) {
        deregisterDbProxyTargets(dbProxyName, targetGroupName, dbClusterIdentifiers,
                dbInstanceIdentifiers, regionResolver.getDefaultRegion());
    }

    public synchronized void deregisterDbProxyTargets(
            String dbProxyName, String targetGroupName,
            List<String> dbClusterIdentifiers, List<String> dbInstanceIdentifiers, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbProxy proxy = getDbProxy(dbProxyName, effectiveRegion);
        DbProxyTargetGroup targetGroup = getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, effectiveRegion);
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
        persistTargetGroupAfterRelayStop(proxy, targetGroup, updatedTargetGroup, effectiveRegion);
    }

    public DbProxy getDbProxy(String name) {
        return getDbProxy(name, regionResolver.getDefaultRegion());
    }

    public DbProxy getDbProxy(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "DBProxyName is required.", 400);
        }
        String effectiveRegion = effectiveRegion(region);
        return findDbProxy(name, effectiveRegion).orElseThrow(() ->
                new AwsException("DBProxyNotFoundFault", "DB proxy " + name + " not found.", 404));
    }

    public Collection<DbProxy> listDbProxies(String filterName) {
        return listDbProxies(filterName, regionResolver.getDefaultRegion());
    }

    public Collection<DbProxy> listDbProxies(String filterName, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getDbProxy(filterName, effectiveRegion));
        }
        String accountId = currentAccountId();
        Map<String, DbProxy> unique = new LinkedHashMap<>();
        proxies.scan(k -> true).stream()
                .filter(proxy -> proxyBelongsTo(proxy, accountId, effectiveRegion))
                .forEach(proxy -> findDbProxy(proxy.getDbProxyName(), effectiveRegion)
                        .ifPresent(canonical -> unique.put(
                                canonical.getDbProxyArn(), canonical)));
        return List.copyOf(unique.values());
    }

    public Collection<DbProxyTargetGroup> describeDbProxyTargetGroups(String dbProxyName) {
        return describeDbProxyTargetGroups(dbProxyName, null);
    }

    public Collection<DbProxyTargetGroup> describeDbProxyTargetGroups(String dbProxyName,
                                                                       String targetGroupName) {
        return describeDbProxyTargetGroups(dbProxyName, targetGroupName,
                regionResolver.getDefaultRegion());
    }

    public Collection<DbProxyTargetGroup> describeDbProxyTargetGroups(
            String dbProxyName, String targetGroupName, String region) {
        String effectiveRegion = effectiveRegion(region);
        getDbProxy(dbProxyName, effectiveRegion);
        return List.of(getDefaultProxyTargetGroup(dbProxyName, targetGroupName, effectiveRegion));
    }

    public Collection<DbProxyTarget> describeDbProxyTargets(String dbProxyName, String targetGroupName) {
        return describeDbProxyTargets(dbProxyName, targetGroupName,
                regionResolver.getDefaultRegion());
    }

    public Collection<DbProxyTarget> describeDbProxyTargets(
            String dbProxyName, String targetGroupName, String region) {
        String effectiveRegion = effectiveRegion(region);
        getDbProxy(dbProxyName, effectiveRegion);
        return new ArrayList<>(getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, effectiveRegion).getTargets());
    }

    public synchronized void clearDbProxyTargetGroupByArn(String targetGroupArn) {
        clearDbProxyTargetGroupByArn(targetGroupArn, regionFromArn(targetGroupArn));
    }

    public synchronized void clearDbProxyTargetGroupByArn(String targetGroupArn, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbProxyTargetGroup targetGroup = getDbProxyTargetGroupByArn(targetGroupArn, effectiveRegion);
        DbProxyTargetGroup clearedTargetGroup = copyProxyTargetGroup(targetGroup);
        clearedTargetGroup.getTargets().clear();
        DbProxy proxy = getDbProxy(targetGroup.getDbProxyName(), effectiveRegion);
        boolean sqlServer = "SQLSERVER".equals(proxy.getEngineFamily());
        clearedTargetGroup.setMaxConnectionsPercent(sqlServer ? 10 : 100);
        clearedTargetGroup.setMaxIdleConnectionsPercent(sqlServer ? 5 : 50);
        clearedTargetGroup.setConnectionBorrowTimeout(120);
        clearedTargetGroup.setInitQuery(null);
        clearedTargetGroup.setSessionPinningFilters(List.of());
        clearedTargetGroup.setUpdatedAt(Instant.now());
        persistTargetGroupAfterRelayStop(proxy, targetGroup, clearedTargetGroup, effectiveRegion);
    }

    public synchronized DbProxyTargetGroup getDbProxyTargetGroupByArn(
            String targetGroupArn, String region) {
        String effectiveRegion = effectiveRegion(region);
        validateResourceRegion(targetGroupArn, effectiveRegion, "DB proxy target group");
        DbProxyTargetGroup targetGroup = proxyTargetGroups.scan(k -> true).stream()
                .filter(candidate -> effectiveRegion.equals(regionFromArn(candidate.getTargetGroupArn())))
                .filter(candidate -> Objects.equals(targetGroupArn, candidate.getTargetGroupArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("DBProxyTargetGroupNotFoundFault",
                        "DB proxy target group " + targetGroupArn + " not found.", 404));
        return findProxyTargetGroup(targetGroup.getDbProxyName(), effectiveRegion)
                .filter(candidate -> Objects.equals(targetGroupArn, candidate.getTargetGroupArn()))
                .orElseThrow(() -> new AwsException("DBProxyTargetGroupNotFoundFault",
                        "DB proxy target group " + targetGroupArn + " not found.", 404));
    }

    public synchronized void deleteDbProxy(String name) {
        deleteDbProxy(name, regionResolver.getDefaultRegion());
    }

    public synchronized void deleteDbProxy(String name, String region) {
        String effectiveRegion = effectiveRegion(region);
        String proxyKey = dbProxyKey(effectiveRegion, name);
        DbProxy proxy = getDbProxy(name, effectiveRegion);
        String accountId = accountIdFromArn(proxy.getDbProxyArn());
        Optional<DbProxyTargetGroup> canonicalTargetGroup =
                getTargetGroupForAccount(accountId, proxyKey);
        Optional<DbProxyTargetGroup> legacyTargetGroup =
                getTargetGroupForAccount(accountId, name)
                        .filter(candidate -> targetGroupBelongsTo(
                                candidate, accountId, effectiveRegion)
                                && Objects.equals(name, candidate.getDbProxyName()));
        Optional<DbProxy> legacyProxy = getProxyForAccount(accountId, name)
                .filter(candidate -> proxyBelongsTo(candidate, accountId, effectiveRegion)
                        && Objects.equals(name, candidate.getDbProxyName()));
        DbProxyTargetGroup relayTargetGroup = canonicalTargetGroup
                .filter(candidate -> targetGroupMatchesProxy(candidate, proxy))
                .or(() -> legacyTargetGroup
                        .filter(candidate -> targetGroupMatchesProxy(candidate, proxy)))
                .orElse(null);
        boolean realMode = !config.services().rds().mock();
        if (realMode) {
            try {
                proxyManager.stopProxy(dbProxyRelayKey(proxy));
            } catch (RuntimeException stopFailure) {
                restartDbProxyRelayAfterFailure(proxy, relayTargetGroup, stopFailure);
                throw stopFailure;
            }
        }
        try {
            deleteTargetGroupKey(accountId, proxyKey);
            if (legacyTargetGroup.isPresent()) {
                deleteTargetGroupKey(accountId, name);
            }
            if (legacyProxy.isPresent()) {
                deleteProxyKey(accountId, name);
            }
            deleteProxyKey(accountId, proxyKey);
        } catch (RuntimeException deleteFailure) {
            attemptRollback(deleteFailure,
                    () -> putProxyForAccount(accountId, proxyKey, proxy));
            canonicalTargetGroup.ifPresent(targetGroup -> attemptRollback(deleteFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, targetGroup)));
            legacyProxy.ifPresent(candidate -> attemptRollback(deleteFailure,
                    () -> putProxyForAccount(accountId, name, candidate)));
            legacyTargetGroup.ifPresent(targetGroup -> attemptRollback(deleteFailure,
                    () -> putTargetGroupForAccount(accountId, name, targetGroup)));
            restartDbProxyRelayAfterFailure(proxy, relayTargetGroup, deleteFailure);
            throw deleteFailure;
        }
        releaseProxyPort(proxy.getProxyPort());
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
        String effectiveRegion = effectiveRegion(region);
        if (findSubnetGroupForScope(currentAccountId(), effectiveRegion, name) != null
                || scopedKeyExists(subnetGroups, currentAccountId(),
                dbResourceKey(effectiveRegion, name))
                || "default".equalsIgnoreCase(name)) {
            throw new AwsException("DBSubnetGroupAlreadyExists",
                    "DB subnet group " + name + " already exists.", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter SubnetIds.", 400);
        }

        DbSubnetGroup group = buildSubnetGroup(name, description, subnetIds, effectiveRegion);
        putSubnetGroupForScope(currentAccountId(), effectiveRegion, name, group);
        return group;
    }

    public Collection<DbSubnetGroup> listDbSubnetGroups(String filterName) {
        return listDbSubnetGroups(filterName, regionResolver.getDefaultRegion());
    }

    public Collection<DbSubnetGroup> listDbSubnetGroups(String filterName, String region) {
        String accountId = currentAccountId();
        String effectiveRegion = effectiveRegion(region);
        List<DbSubnetGroup> groups = new ArrayList<>();
        if (filterName == null || filterName.isBlank() || "default".equalsIgnoreCase(filterName)) {
            groups.add(buildDefaultSubnetGroup(effectiveRegion));
        }
        if (filterName != null && !filterName.isBlank()) {
            if (!"default".equalsIgnoreCase(filterName)) {
                // Specific name: AWS DescribeDBSubnetGroups faults when absent (not empty 200).
                groups.add(resolveDbSubnetGroupView(filterName, effectiveRegion));
            }
            return groups;
        }
        Map<String, DbSubnetGroup> unique = new LinkedHashMap<>();
        List<DbSubnetGroup> storedGroups;
        if (subnetGroups instanceof AccountAwareStorageBackend<DbSubnetGroup> aware) {
            storedGroups = new ArrayList<>(aware.scanForAccount(accountId, k -> true));
            storedGroups.addAll(aware.scanUnscopedLegacy(group ->
                    group != null
                            && hasRdsResourceIdentity(
                            group.getDbSubnetGroupArn(), accountId, effectiveRegion,
                            "subgrp", group.getDbSubnetGroupName())));
        } else {
            storedGroups = subnetGroups.scan(k -> true);
        }
        for (DbSubnetGroup group : storedGroups) {
            if (hasRdsResourceIdentity(
                    group.getDbSubnetGroupArn(), accountId, effectiveRegion,
                    "subgrp", group.getDbSubnetGroupName())) {
                DbSubnetGroup canonical = findSubnetGroupForScope(
                        accountId, effectiveRegion, group.getDbSubnetGroupName());
                if (canonical != null) {
                    unique.put(canonical.getDbSubnetGroupArn(), canonical);
                }
            }
        }
        groups.addAll(unique.values());
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
        DbSubnetGroup group = findSubnetGroupForScope(
                currentAccountId(), effectiveRegion(region), effectiveName);
        if (group == null) {
            throw new AwsException("DBSubnetGroupNotFoundFault",
                    "DB subnet group " + effectiveName + " not found.", 404);
        }
        return group;
    }

    // ── Parameter Groups ──────────────────────────────────────────────────────

    public DbParameterGroup createDbParameterGroup(String name, String family, String description) {
        return createDbParameterGroup(
                name, family, description, regionResolver.getDefaultRegion());
    }

    public DbParameterGroup createDbParameterGroup(
            String name, String family, String description, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (findParameterGroupForRegion(name, effectiveRegion) != null
                || scopedKeyExists(parameterGroups, currentAccountId(),
                dbResourceKey(effectiveRegion, name))) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB parameter group " + name + " already exists.", 400);
        }
        DbParameterGroup group = new DbParameterGroup(name, family, description);
        group.setRegion(effectiveRegion);
        putParameterGroupForRegion(name, effectiveRegion, group);
        return group;
    }

    public DbParameterGroup getDbParameterGroup(String name) {
        return getDbParameterGroup(name, regionResolver.getDefaultRegion());
    }

    public DbParameterGroup getDbParameterGroup(String name, String region) {
        DbParameterGroup group = findParameterGroupForRegion(name, effectiveRegion(region));
        if (group == null) {
            throw new AwsException("DBParameterGroupNotFound",
                    "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404);
        }
        return group;
    }

    public Collection<DbParameterGroup> listDbParameterGroups(String filterName) {
        return listDbParameterGroups(filterName, regionResolver.getDefaultRegion());
    }

    public Collection<DbParameterGroup> listDbParameterGroups(
            String filterName, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (filterName != null && !filterName.isBlank()) {
            DbParameterGroup group = findParameterGroupForRegion(filterName, effectiveRegion);
            return group != null ? List.of(group) : List.of();
        }
        Map<String, DbParameterGroup> unique = new LinkedHashMap<>();
        List<DbParameterGroup> storedGroups;
        if (parameterGroups instanceof AccountAwareStorageBackend<DbParameterGroup> aware) {
            storedGroups = new ArrayList<>(aware.scanForAccount(currentAccountId(), k -> true));
            if (Objects.equals(currentAccountId(), defaultAccountId())) {
                storedGroups.addAll(aware.scanUnscopedLegacy(group ->
                        parameterGroupBelongsToRegion(group, effectiveRegion)));
            }
        } else {
            storedGroups = parameterGroups.scan(k -> true);
        }
        for (DbParameterGroup group : storedGroups) {
            if (parameterGroupBelongsToRegion(group, effectiveRegion)) {
                DbParameterGroup canonical = findParameterGroupForRegion(
                        group.getDbParameterGroupName(), effectiveRegion);
                if (canonical != null) {
                    unique.put(canonical.getDbParameterGroupName(), canonical);
                }
            }
        }
        return unique.values();
    }

    public void deleteDbParameterGroup(String name) {
        deleteDbParameterGroup(name, regionResolver.getDefaultRegion());
    }

    public void deleteDbParameterGroup(String name, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (findParameterGroupForRegion(name, effectiveRegion) == null) {
            throw new AwsException("DBParameterGroupNotFound",
                    "DBParameterGroupName doesn't refer to an existing DB parameter group.", 404);
        }
        deleteParameterGroupForRegion(name, effectiveRegion);
    }

    public DbParameterGroup modifyDbParameterGroup(String name,
                                                    java.util.Map<String, String> parameters) {
        return modifyDbParameterGroup(
                name, parameters, regionResolver.getDefaultRegion());
    }

    public DbParameterGroup modifyDbParameterGroup(
            String name, java.util.Map<String, String> parameters, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbParameterGroup group = getDbParameterGroup(name, effectiveRegion);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        putParameterGroupForRegion(name, effectiveRegion, group);
        return group;
    }

    public DbSubnetGroup getDbSubnetGroup(String name) {
        return getDbSubnetGroup(name, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup getDbSubnetGroup(String name, String region) {
        if ("default".equalsIgnoreCase(name)) {
            return buildDefaultSubnetGroup(effectiveRegion(region));
        }
        DbSubnetGroup group = findSubnetGroupForScope(
                currentAccountId(), effectiveRegion(region), name);
        if (group == null) {
            throw new AwsException("DBSubnetGroupNotFoundFault",
                    "DB subnet group " + name + " not found.", 404);
        }
        return group;
    }

    public DbSubnetGroup modifyDbSubnetGroup(String name, List<String> subnetIds) {
        return modifyDbSubnetGroup(name, subnetIds, regionResolver.getDefaultRegion());
    }

    public DbSubnetGroup modifyDbSubnetGroup(String name, List<String> subnetIds, String region) {
        DbSubnetGroup existing = getDbSubnetGroup(name, region);
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "SubnetIds must contain at least one subnet.", 400);
        }
        DbSubnetGroup group = buildSubnetGroup(name, existing.getDescription(), subnetIds, effectiveRegion(region));
        group.setTags(existing.getTags());
        putSubnetGroupForScope(
                currentAccountId(), effectiveRegion(region), name, group);
        return group;
    }

    public void deleteDbSubnetGroup(String name) {
        deleteDbSubnetGroup(name, regionResolver.getDefaultRegion());
    }

    public void deleteDbSubnetGroup(String name, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = currentAccountId();
        if (findSubnetGroupForScope(accountId, effectiveRegion, name) == null) {
            throw new AwsException("DBSubnetGroupNotFoundFault",
                    "DB subnet group " + name + " not found.", 404);
        }
        deleteSubnetGroupForScope(accountId, effectiveRegion, name);
    }

    // ── Cluster Parameter Groups ──────────────────────────────────────────────

    public DbClusterParameterGroup createDbClusterParameterGroup(String name, String family, String description) {
        return createDbClusterParameterGroup(
                name, family, description, regionResolver.getDefaultRegion());
    }

    public DbClusterParameterGroup createDbClusterParameterGroup(
            String name, String family, String description, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (findClusterParameterGroupForRegion(name, effectiveRegion) != null
                || scopedKeyExists(clusterParameterGroups, currentAccountId(),
                dbResourceKey(effectiveRegion, name))) {
            throw new AwsException("DBParameterGroupAlreadyExists",
                    "DB cluster parameter group " + name + " already exists.", 400);
        }
        DbClusterParameterGroup group = new DbClusterParameterGroup(name, family, description);
        group.setRegion(effectiveRegion);
        putClusterParameterGroupForRegion(name, effectiveRegion, group);
        return group;
    }

    public DbClusterParameterGroup getDbClusterParameterGroup(String name) {
        return getDbClusterParameterGroup(name, regionResolver.getDefaultRegion());
    }

    public DbClusterParameterGroup getDbClusterParameterGroup(String name, String region) {
        DbClusterParameterGroup group = findClusterParameterGroupForRegion(
                name, effectiveRegion(region));
        if (group == null) {
            throw new AwsException("DBClusterParameterGroupNotFound",
                    "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404);
        }
        return group;
    }

    public Collection<DbClusterParameterGroup> listDbClusterParameterGroups(String filterName) {
        return listDbClusterParameterGroups(filterName, regionResolver.getDefaultRegion());
    }

    public Collection<DbClusterParameterGroup> listDbClusterParameterGroups(
            String filterName, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (filterName != null && !filterName.isBlank()) {
            DbClusterParameterGroup group = findClusterParameterGroupForRegion(
                    filterName, effectiveRegion);
            return group != null ? List.of(group) : List.of();
        }
        Map<String, DbClusterParameterGroup> unique = new LinkedHashMap<>();
        List<DbClusterParameterGroup> storedGroups;
        if (clusterParameterGroups
                instanceof AccountAwareStorageBackend<DbClusterParameterGroup> aware) {
            storedGroups = new ArrayList<>(aware.scanForAccount(currentAccountId(), k -> true));
            if (Objects.equals(currentAccountId(), defaultAccountId())) {
                storedGroups.addAll(aware.scanUnscopedLegacy(group ->
                        clusterParameterGroupBelongsToRegion(group, effectiveRegion)));
            }
        } else {
            storedGroups = clusterParameterGroups.scan(k -> true);
        }
        for (DbClusterParameterGroup group : storedGroups) {
            if (clusterParameterGroupBelongsToRegion(group, effectiveRegion)) {
                DbClusterParameterGroup canonical = findClusterParameterGroupForRegion(
                        group.getDbClusterParameterGroupName(), effectiveRegion);
                if (canonical != null) {
                    unique.put(canonical.getDbClusterParameterGroupName(), canonical);
                }
            }
        }
        return unique.values();
    }

    public void deleteDbClusterParameterGroup(String name) {
        deleteDbClusterParameterGroup(name, regionResolver.getDefaultRegion());
    }

    public void deleteDbClusterParameterGroup(String name, String region) {
        String effectiveRegion = effectiveRegion(region);
        if (findClusterParameterGroupForRegion(name, effectiveRegion) == null) {
            throw new AwsException("DBClusterParameterGroupNotFound",
                    "DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", 404);
        }
        deleteClusterParameterGroupForRegion(name, effectiveRegion);
    }

    public DbClusterParameterGroup modifyDbClusterParameterGroup(String name,
                                                                  java.util.Map<String, String> parameters) {
        return modifyDbClusterParameterGroup(
                name, parameters, regionResolver.getDefaultRegion());
    }

    public DbClusterParameterGroup modifyDbClusterParameterGroup(
            String name, java.util.Map<String, String> parameters, String region) {
        String effectiveRegion = effectiveRegion(region);
        DbClusterParameterGroup group = getDbClusterParameterGroup(name, effectiveRegion);
        if (parameters != null) {
            group.getParameters().putAll(parameters);
        }
        putClusterParameterGroupForRegion(name, effectiveRegion, group);
        return group;
    }

    // ── Password validation callbacks ─────────────────────────────────────────

    public boolean validateDbPassword(String instanceId, String clientUser, String password) {
        return validateDbPasswordForScope(
                null, regionResolver.getDefaultRegion(), instanceId, clientUser, password);
    }

    private boolean validateDbPasswordForScope(
            String accountId, String region, String instanceId,
            String clientUser, String password) {
        DbInstance instance = findInstanceForScope(accountId, effectiveRegion(region), instanceId);
        if (instance == null) {
            return false;
        }
        if (!instance.getMasterUsername().equals(clientUser)) {
            return true; // non-master user: backend is the authority
        }
        return password != null && password.equals(instance.getMasterPassword());
    }

    public boolean validateDbClusterPassword(String clusterId, String clientUser, String password) {
        return validateDbClusterPasswordForScope(
                null, regionResolver.getDefaultRegion(), clusterId, clientUser, password);
    }

    private boolean validateDbClusterPasswordForScope(
            String accountId, String region, String clusterId,
            String clientUser, String password) {
        DbCluster cluster = findClusterForScope(accountId, effectiveRegion(region), clusterId);
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

    private void validateInstanceParameterGroup(
            String paramGroupName, String engineParam, String engineVersion, String region) {
        if (paramGroupName == null || paramGroupName.isBlank()) {
            return;
        }
        DbParameterGroup group = getDbParameterGroup(paramGroupName, region);
        validateParameterGroupFamily(paramGroupName, group.getDbParameterGroupFamily(), engineParam, engineVersion);
    }

    private void validateClusterParameterGroup(
            String paramGroupName, String engineParam, String engineVersion, String region) {
        if (paramGroupName == null || paramGroupName.isBlank()) {
            return;
        }
        DbClusterParameterGroup group = getDbClusterParameterGroup(paramGroupName, region);
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
                                       List<String> subnetIds, List<DbProxyAuth> auth,
                                       String defaultAuthScheme, int idleClientTimeout) {
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
        if (subnetIds == null || subnetIds.stream()
                .filter(subnetId -> subnetId != null && !subnetId.isBlank())
                .distinct()
                .count() < 2) {
            throw new AwsException("InvalidParameterValue",
                    "VpcSubnetIds must contain at least two distinct subnet IDs.", 400);
        }
        if ("NONE".equals(defaultAuthScheme) && (auth == null || auth.isEmpty())) {
            throw new AwsException("InvalidParameterValue",
                    "Auth is required when DefaultAuthScheme is NONE.", 400);
        }
        if ("SQLSERVER".equalsIgnoreCase(engineFamily)
                && "IAM_AUTH".equals(defaultAuthScheme)) {
            throw new AwsException("InvalidParameterValue",
                    "DefaultAuthScheme IAM_AUTH is not supported for SQLSERVER DB proxies.", 400);
        }
        validateDbProxyAuth(engineFamily, auth);
        if (idleClientTimeout < 1 || idleClientTimeout > 28_800) {
            throw new AwsException("InvalidParameterValue",
                    "IdleClientTimeout must be between 1 and 28800 seconds.", 400);
        }
    }

    private String normalizeDefaultAuthScheme(String defaultAuthScheme) {
        if (defaultAuthScheme == null || "NONE".equalsIgnoreCase(defaultAuthScheme)) {
            return "NONE";
        }
        if ("IAM_AUTH".equalsIgnoreCase(defaultAuthScheme)) {
            return "IAM_AUTH";
        }
        throw new AwsException("InvalidParameterValue",
                "DefaultAuthScheme must be NONE or IAM_AUTH.", 400);
    }

    private static boolean isIamEnabledAuthMode(String iamAuth) {
        return "REQUIRED".equalsIgnoreCase(iamAuth)
                || "ENABLED".equalsIgnoreCase(iamAuth);
    }

    private void validateDbProxyAuth(String engineFamily, List<DbProxyAuth> auth) {
        if (auth == null) {
            return;
        }
        if (auth.size() > 200) {
            throw new AwsException("InvalidParameterValue",
                    "Auth cannot contain more than 200 entries.", 400);
        }
        for (DbProxyAuth entry : auth) {
            if (entry == null) {
                throw new AwsException("InvalidParameterValue",
                        "Auth entries must not be null.", 400);
            }
            validateOptionalEnum("AuthScheme", entry.getAuthScheme(), DB_PROXY_AUTH_SCHEMES);
            validateOptionalEnum("IAMAuth", entry.getIamAuth(), DB_PROXY_IAM_AUTH_MODES);
            validateOptionalEnum("ClientPasswordAuthType", entry.getClientPasswordAuthType(),
                    DB_PROXY_CLIENT_PASSWORD_AUTH_TYPES);
            if ("ENABLED".equals(entry.getIamAuth())
                    && !"SQLSERVER".equalsIgnoreCase(engineFamily)) {
                throw new AwsException("InvalidParameterValue",
                        "IAMAuth ENABLED is supported only for SQLSERVER DB proxies.", 400);
            }
            validateOptionalLength("Description", entry.getDescription(), 1, 1_000);
            validateOptionalLength("SecretArn", entry.getSecretArn(), 20, 2_048);
            validateOptionalLength("UserName", entry.getUserName(), 1, 128);
        }
    }

    private void validateOptionalEnum(String name, String value, Set<String> validValues) {
        if (value != null && !validValues.contains(value)) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be one of " + String.join(", ", validValues) + ".", 400);
        }
    }

    private void validateOptionalLength(String name, String value, int minimum, int maximum) {
        if (value != null && (value.length() < minimum || value.length() > maximum)) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be between " + minimum + " and " + maximum + " characters.",
                    400);
        }
    }

    private DbProxyTargetGroup getDefaultProxyTargetGroup(String dbProxyName, String targetGroupName) {
        return getDefaultProxyTargetGroup(
                dbProxyName, targetGroupName, regionResolver.getDefaultRegion());
    }

    private DbProxyTargetGroup getDefaultProxyTargetGroup(
            String dbProxyName, String targetGroupName, String region) {
        String effectiveName = targetGroupName == null || targetGroupName.isBlank()
                ? "default" : targetGroupName;
        if (!"default".equals(effectiveName)) {
            throw new AwsException("DBProxyTargetGroupNotFoundFault",
                    "DB proxy target group " + effectiveName + " not found for proxy " + dbProxyName + ".", 404);
        }
        return findProxyTargetGroup(dbProxyName, effectiveRegion(region)).orElseThrow(() ->
                new AwsException("DBProxyTargetGroupNotFoundFault",
                        "DB proxy target group default not found for proxy " + dbProxyName + ".", 404));
    }

    private synchronized Optional<DbProxy> findDbProxy(String dbProxyName, String region) {
        String key = dbProxyKey(region, dbProxyName);
        String accountId = currentAccountId();
        Optional<DbProxy> proxy;
        Optional<DbProxy> legacy;
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            proxy = aware.getForAccount(accountId, key);
            legacy = aware.getForAccount(accountId, dbProxyName);
        } else {
            proxy = proxies.get(key);
            legacy = proxies.get(dbProxyName);
        }
        if (proxy.isPresent()) {
            if (!proxyBelongsTo(proxy.get(), accountId, region)
                    || !Objects.equals(dbProxyName, proxy.get().getDbProxyName())) {
                return Optional.empty();
            }
            if (legacy.filter(candidate -> sameProxyScope(candidate, proxy.get())).isPresent()) {
                deleteProxyKey(accountId, dbProxyName);
            }
            return proxy;
        }
        if (legacy.filter(candidate -> proxyBelongsTo(candidate, accountId, region)
                && Objects.equals(dbProxyName, candidate.getDbProxyName())).isPresent()) {
            putProxyForAccount(accountId, key, legacy.get());
            deleteProxyKey(accountId, dbProxyName);
            return legacy;
        }
        return Optional.empty();
    }

    private synchronized Optional<DbProxyTargetGroup> findProxyTargetGroup(
            String dbProxyName, String region) {
        String key = dbProxyKey(region, dbProxyName);
        String accountId = currentAccountId();
        Optional<DbProxy> ownerProxy = findDbProxy(dbProxyName, region);
        java.util.function.Predicate<DbProxyTargetGroup> owner = candidate ->
                ownerProxy.isPresent()
                        && targetGroupBelongsTo(candidate, accountId, region)
                        && targetGroupMatchesProxy(candidate, ownerProxy.get());
        Optional<DbProxyTargetGroup> targetGroup;
        Optional<DbProxyTargetGroup> legacy;
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            targetGroup = aware.getForAccount(accountId, key);
            legacy = aware.getForAccount(accountId, dbProxyName);
        } else {
            targetGroup = proxyTargetGroups.get(key);
            legacy = proxyTargetGroups.get(dbProxyName);
        }
        if (targetGroup.isPresent()) {
            if (!owner.test(targetGroup.get())) {
                return Optional.empty();
            }
            if (legacy.filter(candidate -> sameTargetGroupScope(
                    candidate, targetGroup.get())).isPresent()) {
                deleteTargetGroupKey(accountId, dbProxyName);
            }
            return targetGroup;
        }
        if (legacy.filter(owner).isPresent()) {
            putTargetGroupForAccount(accountId, key, legacy.get());
            deleteTargetGroupKey(accountId, dbProxyName);
            return legacy;
        }
        return Optional.empty();
    }

    private String currentAccountId() {
        String accountId = regionResolver.getAccountId();
        return accountId == null || accountId.isBlank() ? defaultAccountId() : accountId;
    }

    private boolean proxyBelongsTo(DbProxy proxy, String accountId, String region) {
        if (proxy == null || proxy.getDbProxyResourceId() == null
                || proxy.getDbProxyResourceId().isBlank()) {
            return false;
        }
        return hasRdsResourceIdentity(
                proxy.getDbProxyArn(), accountId, region, "db-proxy",
                proxy.getDbProxyResourceId());
    }

    private boolean targetGroupBelongsTo(
            DbProxyTargetGroup targetGroup, String accountId, String region) {
        if (targetGroup == null || targetGroup.getTargetGroupArn() == null) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(targetGroup.getTargetGroupArn());
            return "aws".equals(arn.partition())
                    && "rds".equals(arn.service())
                    && Objects.equals(accountId, arn.accountId())
                    && Objects.equals(region, arn.region())
                    && arn.resource().matches("target-group:prx-tg-[A-Za-z0-9-]+");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void putProxyForAccount(String accountId, String key, DbProxy proxy) {
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            aware.putForAccount(accountId, key, proxy);
        } else {
            proxies.put(key, proxy);
        }
    }

    private Optional<DbProxy> getProxyForAccount(String accountId, String key) {
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            return aware.getForAccount(accountId, key);
        }
        return proxies.get(key);
    }

    private void deleteProxyKey(String accountId, String key) {
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            proxies.delete(key);
        }
    }

    private void putTargetGroupForAccount(
            String accountId, String key, DbProxyTargetGroup targetGroup) {
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            aware.putForAccount(accountId, key, targetGroup);
        } else {
            proxyTargetGroups.put(key, targetGroup);
        }
    }

    private Optional<DbProxyTargetGroup> getTargetGroupForAccount(
            String accountId, String key) {
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            return aware.getForAccount(accountId, key);
        }
        return proxyTargetGroups.get(key);
    }

    private void deleteTargetGroupKey(String accountId, String key) {
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            proxyTargetGroups.delete(key);
        }
    }

    private void attemptRollback(RuntimeException failure, Runnable rollback) {
        try {
            rollback.run();
        } catch (RuntimeException rollbackFailure) {
            if (rollbackFailure != failure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void restartDbProxyRelayAfterFailure(
            DbProxy proxy, DbProxyTargetGroup targetGroup, RuntimeException failure) {
        if (config.services().rds().mock()
                || targetGroup == null || targetGroup.getTargets().isEmpty()
                || !targetGroupMatchesProxy(targetGroup, proxy)
                || !"available".equals(proxy.getStatus())
                || "IAM_AUTH".equals(proxy.getDefaultAuthScheme())) {
            return;
        }
        attemptRollback(failure,
                () -> startDbProxyRelay(proxy, targetGroup.getTargets().getFirst()));
    }

    private void persistTargetGroupAfterRelayStop(
            DbProxy proxy, DbProxyTargetGroup original,
            DbProxyTargetGroup updated, String region) {
        String accountId = accountIdFromArn(proxy.getDbProxyArn());
        String proxyKey = dbProxyKey(region, proxy.getDbProxyName());
        boolean realMode = !config.services().rds().mock();
        DbProxyTarget desiredTarget = updated.getTargets().isEmpty()
                ? null : updated.getTargets().getFirst();
        boolean relayTransition = realMode
                && (!original.getTargets().isEmpty() || desiredTarget != null);
        try {
            if (relayTransition && !original.getTargets().isEmpty()) {
                proxyManager.stopProxy(dbProxyRelayKey(proxy));
            }
            putTargetGroupForAccount(accountId, proxyKey, updated);
            if (relayTransition && desiredTarget != null) {
                startDbProxyRelay(proxy, desiredTarget);
            }
        } catch (RuntimeException transitionFailure) {
            if (relayTransition) {
                attemptRollback(transitionFailure,
                        () -> proxyManager.stopProxy(dbProxyRelayKey(proxy)));
            }
            attemptRollback(transitionFailure,
                    () -> putTargetGroupForAccount(accountId, proxyKey, original));
            restartDbProxyRelayAfterFailure(proxy, original, transitionFailure);
            throw transitionFailure;
        }
    }

    private String dbProxyKey(String region, String dbProxyName) {
        return effectiveRegion(region) + "::" + dbProxyName;
    }

    private String regionFromArn(String arn) {
        return AwsArnUtils.regionOrDefault(arn, regionResolver.getDefaultRegion());
    }

    private void validateProxyTargetScope(
            String targetArn, String proxyAccountId, String proxyRegion,
            String resourceType, String resourceId) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(targetArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue",
                    resourceType + " " + resourceId + " has an invalid ARN.", 400);
        }
        if (!"rds".equals(parsed.service())
                || !Objects.equals(proxyAccountId, parsed.accountId())
                || !Objects.equals(proxyRegion, parsed.region())) {
            throw new AwsException("InvalidParameterValue",
                    resourceType + " " + resourceId
                            + " is not in the proxy account and region.", 400);
        }
    }

    private void validateResourceRegion(String arn, String expectedRegion, String resourceType) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"rds".equals(parsed.service())
                    || !expectedRegion.equals(parsed.region())
                    || !Objects.equals(currentAccountId(), parsed.accountId())) {
                throw new AwsException("InvalidParameterValue",
                        resourceType + " is not in the current account and region "
                                + expectedRegion + ".", 400);
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid " + resourceType + " ARN: " + arn, 400);
        }
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

    private void validateProxyTargetSelection(
            DbProxy proxy, List<String> clusterIds, List<String> instanceIds, String region) {
        if (clusterIds.size() + instanceIds.size() > 1) {
            throw new AwsException("InvalidParameterCombination",
                    "Floci currently supports at most one DB cluster or DB instance per proxy target group.",
                    400);
        }
        if (!clusterIds.isEmpty()) {
            String clusterId = clusterIds.get(0);
            String proxyAccountId = accountIdFromArn(proxy.getDbProxyArn());
            DbCluster cluster = Optional.ofNullable(findClusterForScope(
                            proxyAccountId, region, clusterId))
                    .orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault",
                            "DB cluster " + clusterId + " not found.", 404));
            if (cluster.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBClusterStateFault",
                        "DB cluster " + clusterId + " is not available.", 400);
            }
            validateProxyTargetScope(cluster.getDbClusterArn(), proxyAccountId, region,
                    "DB cluster", clusterId);
            validateTargetEngineFamily(proxy, cluster.getEngine());
        } else if (!instanceIds.isEmpty()) {
            String instanceId = instanceIds.get(0);
            String proxyAccountId = accountIdFromArn(proxy.getDbProxyArn());
            DbInstance instance = Optional.ofNullable(findInstanceForScope(
                            proxyAccountId, region, instanceId))
                    .orElseThrow(() ->
                    new AwsException("DBInstanceNotFoundFault",
                            "DB instance " + instanceId + " not found.", 404));
            if (instance.getStatus() != DbInstanceStatus.AVAILABLE) {
                throw new AwsException("InvalidDBInstanceState",
                        "DB instance " + instanceId + " is not available.", 400);
            }
            validateProxyTargetScope(instance.getDbInstanceArn(), proxyAccountId, region,
                    "DB instance", instanceId);
            validateTargetEngineFamily(proxy, instance.getEngine());
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
        validatePoolConfiguration(null, maxConnectionsPercent, maxIdleConnectionsPercent,
                null, null);
    }

    private void validatePoolConfiguration(
            DbProxy proxy, Integer maxConnectionsPercent, Integer maxIdleConnectionsPercent,
            Integer connectionBorrowTimeout, List<String> sessionPinningFilters) {
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
        if (connectionBorrowTimeout != null) {
            validatePercent("ConnectionBorrowTimeout", connectionBorrowTimeout, 0, 300);
        }
        if (sessionPinningFilters != null && !sessionPinningFilters.isEmpty()) {
            if (proxy == null || !"MYSQL".equals(proxy.getEngineFamily())) {
                throw new AwsException("InvalidParameterValue",
                        "SessionPinningFilters are supported only for MYSQL DB proxies.", 400);
            }
            if (sessionPinningFilters.stream()
                    .anyMatch(filter -> !"EXCLUDE_VARIABLE_SETS".equals(filter))) {
                throw new AwsException("InvalidParameterValue",
                        "SessionPinningFilters supports only EXCLUDE_VARIABLE_SETS.", 400);
            }
        }
    }

    private boolean sameProxyTargetGroupConfiguration(
            DbProxyTargetGroup left, DbProxyTargetGroup right) {
        return left.getMaxConnectionsPercent() == right.getMaxConnectionsPercent()
                && left.getMaxIdleConnectionsPercent() == right.getMaxIdleConnectionsPercent()
                && left.getConnectionBorrowTimeout() == right.getConnectionBorrowTimeout()
                && Objects.equals(left.getInitQuery(), right.getInitQuery())
                && Objects.equals(left.getSessionPinningFilters(), right.getSessionPinningFilters());
    }

    private boolean isRegisteredProxyTarget(String type, String resourceId, String region) {
        String effectiveRegion = effectiveRegion(region);
        return proxyTargetGroups.scan(k -> true).stream()
                .filter(targetGroup -> targetGroupBelongsTo(
                        targetGroup, currentAccountId(), effectiveRegion))
                .filter(targetGroup -> findDbProxy(
                        targetGroup.getDbProxyName(), effectiveRegion)
                        .filter(proxy -> targetGroupMatchesProxy(targetGroup, proxy))
                        .isPresent())
                .flatMap(targetGroup -> targetGroup.getTargets().stream())
                .anyMatch(target -> type.equals(target.getType())
                        && resourceId.equals(target.getRdsResourceId()));
    }

    private DbProxy copyDbProxy(DbProxy source) {
        DbProxy copy = new DbProxy();
        copy.setDbProxyName(source.getDbProxyName());
        copy.setDbProxyArn(source.getDbProxyArn());
        copy.setDbProxyResourceId(source.getDbProxyResourceId());
        copy.setEndpointHost(source.getEndpointHost());
        copy.setProxyPort(source.getProxyPort());
        copy.setEngineFamily(source.getEngineFamily());
        copy.setRequireTls(source.isRequireTls());
        copy.setIamAuth(source.isIamAuth());
        copy.setDefaultAuthScheme(source.getDefaultAuthScheme() != null
                ? source.getDefaultAuthScheme() : "NONE");
        copy.setRoleArn(source.getRoleArn());
        copy.setIdleClientTimeout(source.getIdleClientTimeout());
        copy.setDebugLogging(source.isDebugLogging());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setVpcId(source.getVpcId());
        copy.setEndpointNetworkType(source.getEndpointNetworkType() != null
                ? source.getEndpointNetworkType() : "IPV4");
        copy.setTargetConnectionNetworkType(source.getTargetConnectionNetworkType() != null
                ? source.getTargetConnectionNetworkType() : "IPV4");
        copy.setVpcSubnetIds(source.getVpcSubnetIds());
        copy.setVpcSecurityGroupIds(source.getVpcSecurityGroupIds());
        copy.setAuth(source.getAuth().stream().map(this::copyDbProxyAuth).toList());
        copy.setTags(source.getTags());
        return copy;
    }

    private DbProxyAuth copyDbProxyAuth(DbProxyAuth source) {
        DbProxyAuth copy = new DbProxyAuth(
                source.getAuthScheme(), source.getSecretArn(), source.getIamAuth(),
                source.getClientPasswordAuthType(), source.getDescription());
        copy.setUserName(source.getUserName());
        return copy;
    }

    private boolean sameDbProxyState(DbProxy left, DbProxy right) {
        return left.isRequireTls() == right.isRequireTls()
                && left.isIamAuth() == right.isIamAuth()
                && left.getIdleClientTimeout() == right.getIdleClientTimeout()
                && left.isDebugLogging() == right.isDebugLogging()
                && Objects.equals(normalizeDefaultAuthScheme(left.getDefaultAuthScheme()),
                        normalizeDefaultAuthScheme(right.getDefaultAuthScheme()))
                && Objects.equals(left.getRoleArn(), right.getRoleArn())
                && Objects.equals(left.getVpcSecurityGroupIds(), right.getVpcSecurityGroupIds())
                && sameDbProxyAuth(left.getAuth(), right.getAuth())
                && Objects.equals(left.getTags(), right.getTags());
    }

    private boolean sameDbProxyAuth(List<DbProxyAuth> left, List<DbProxyAuth> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            DbProxyAuth a = left.get(index);
            DbProxyAuth b = right.get(index);
            if (!Objects.equals(a.getAuthScheme(), b.getAuthScheme())
                    || !Objects.equals(a.getSecretArn(), b.getSecretArn())
                    || !Objects.equals(a.getIamAuth(), b.getIamAuth())
                    || !Objects.equals(a.getClientPasswordAuthType(), b.getClientPasswordAuthType())
                    || !Objects.equals(a.getDescription(), b.getDescription())
                    || !Objects.equals(a.getUserName(), b.getUserName())) {
                return false;
            }
        }
        return true;
    }

    private void startDbProxyRelay(DbProxy proxy, DbProxyTarget target) {
        if ("IAM_AUTH".equals(proxy.getDefaultAuthScheme())) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "DefaultAuthScheme IAM_AUTH is available for control-plane emulation only; "
                            + "the real-mode relay does not support end-to-end IAM authentication.",
                    400);
        }
        String accountId = accountIdFromArn(proxy.getDbProxyArn());
        String proxyRegion = regionFromArn(proxy.getDbProxyArn());
        if (!"TRACKED_CLUSTER".equals(target.getType())
                && !"RDS_INSTANCE".equals(target.getType())) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "Unsupported persisted DB proxy target type " + target.getType() + ".", 400);
        }
        boolean clusterTarget = "TRACKED_CLUSTER".equals(target.getType());
        DatabaseEngine engine;
        String backendHost;
        int backendPort;
        String masterUser;
        String masterPassword;
        String dbName;
        if (clusterTarget) {
            DbCluster cluster = findClusterForScope(
                    accountId, proxyRegion, target.getRdsResourceId());
            if (cluster == null) {
                throw new AwsException("DBClusterNotFoundFault",
                        "DB cluster " + target.getRdsResourceId() + " not found.", 404);
            }
            validateProxyTargetScope(cluster.getDbClusterArn(), accountId, proxyRegion,
                    "DB cluster", target.getRdsResourceId());
            engine = cluster.getEngine();
            backendHost = cluster.getContainerHost();
            backendPort = cluster.getContainerPort();
            masterUser = cluster.getMasterUsername();
            masterPassword = cluster.getMasterPassword();
            dbName = cluster.getDatabaseName();
        } else {
            DbInstance instance = findInstanceForScope(
                    accountId, proxyRegion, target.getRdsResourceId());
            if (instance == null) {
                throw new AwsException("DBInstanceNotFoundFault",
                        "DB instance " + target.getRdsResourceId() + " not found.", 404);
            }
            validateProxyTargetScope(instance.getDbInstanceArn(), accountId, proxyRegion,
                    "DB instance", target.getRdsResourceId());
            engine = instance.getEngine();
            backendHost = instance.getContainerHost();
            backendPort = instance.getContainerPort();
            masterUser = instance.getMasterUsername();
            masterPassword = instance.getMasterPassword();
            dbName = instance.getDbName();
        }
        if (backendHost == null || backendPort <= 0) {
            throw new AwsException("InvalidDBProxyStateFault",
                    "Target backend for proxy " + proxy.getDbProxyName() + " is not available.", 400);
        }
        validateTargetEngineFamily(proxy, engine);
        String effectiveMasterUser = masterUser != null ? masterUser : "root";
        String targetId = target.getRdsResourceId();
        proxyManager.startProxy(dbProxyRelayKey(proxy), engine, proxy.isIamAuth(), proxy.getProxyPort(),
                backendHost, backendPort, effectiveMasterUser, masterPassword, dbName,
                (user, password) -> clusterTarget
                        ? validateDbClusterPasswordForScope(
                                accountId, proxyRegion, targetId, user, password)
                        : validateDbPasswordForScope(
                                accountId, proxyRegion, targetId, user, password));
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
        copy.setConnectionBorrowTimeout(source.getConnectionBorrowTimeout());
        copy.setInitQuery(source.getInitQuery());
        copy.setSessionPinningFilters(source.getSessionPinningFilters());
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
            String accountId = accountIdFromArn(cluster.getDbClusterArn());
            String clusterRegion = regionFromArn(cluster.getDbClusterArn());
            String storageResourceId = resolvedClusterStorageResourceId(cluster);
            String dockerVolumeName = resolvedClusterDockerVolumeName(cluster);
            cluster.setContainerStorageResourceId(storageResourceId);
            cluster.setDockerVolumeName(dockerVolumeName);
            String persistedContainerId = cluster.getContainerId();
            RdsContainerHandle restoredHandle = null;
            int proxyPort = 0;
            boolean portReserved = false;
            try {
                proxyPort = reserveOrAllocateProxyPort(cluster.getProxyPort());
                portReserved = true;
                cluster.setProxyPort(proxyPort);
                if (config.services().rds().mock()) {
                    cluster.setEndpoint(new DbEndpoint("localhost", proxyPort));
                    cluster.setReaderEndpoint(new DbEndpoint("localhost", proxyPort));
                    cluster.setStatus(DbInstanceStatus.AVAILABLE);
                    putClusterForScope(accountId, clusterRegion,
                            cluster.getDbClusterIdentifier(), cluster);
                    continue;
                }
                cluster.setEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
                cluster.setReaderEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
                String image = imageForEngine(cluster.getEngine(), cluster.getEngineVersion());
                restoredHandle = containerManager.start(
                        cluster.getDbClusterArn(), cluster.getDbClusterIdentifier(),
                        storageResourceId, dockerVolumeName, cluster.getEngine(), image,
                        cluster.getMasterUsername(), cluster.getMasterPassword(), cluster.getDatabaseName());
                cluster.setContainerId(restoredHandle.getContainerId());
                cluster.setContainerHost(restoredHandle.getHost());
                cluster.setContainerPort(restoredHandle.getPort());

                String effectiveMasterUser = cluster.getMasterUsername() != null
                        ? cluster.getMasterUsername() : "root";
                proxyManager.startProxy(rdsResourceRelayKey(
                                cluster.getDbClusterArn(), cluster.getDbClusterIdentifier()),
                        cluster.getEngine(),
                        cluster.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        restoredHandle.getHost(), restoredHandle.getPort(), effectiveMasterUser,
                        cluster.getMasterPassword(), cluster.getDatabaseName(),
                        (user, pw) -> validateDbClusterPasswordForScope(
                                accountId, clusterRegion,
                                cluster.getDbClusterIdentifier(), user, pw));
                cluster.setStatus(DbInstanceStatus.AVAILABLE);
                putClusterForScope(accountId, clusterRegion,
                        cluster.getDbClusterIdentifier(), cluster);
            } catch (Exception e) {
                if (!config.services().rds().mock()) {
                    try {
                        proxyManager.stopProxy(rdsResourceRelayKey(
                                cluster.getDbClusterArn(), cluster.getDbClusterIdentifier()));
                    } catch (RuntimeException stopFailure) {
                        e.addSuppressed(stopFailure);
                    }
                }
                RdsContainerHandle cleanupHandle = restoredHandle != null
                        ? restoredHandle
                        : containerManager.getActiveHandle(cluster.getDbClusterArn());
                boolean containerCleaned = stopRestoredContainer(cleanupHandle, e, "cluster",
                        cluster.getDbClusterIdentifier());
                if (portReserved) {
                    releaseProxyPort(proxyPort);
                }
                cluster.setProxyPort(0);
                cluster.setStatus(DbInstanceStatus.FAILED);
                cluster.setEndpoint(null);
                cluster.setReaderEndpoint(null);
                String retainedContainerId = !containerCleaned && cleanupHandle != null
                        ? cleanupHandle.getContainerId()
                        : restoredHandle == null ? persistedContainerId : null;
                cluster.setContainerId(retainedContainerId);
                cluster.setContainerHost(null);
                cluster.setContainerPort(0);
                try {
                    putClusterForScope(accountId, clusterRegion,
                            cluster.getDbClusterIdentifier(), cluster);
                } catch (RuntimeException persistFailure) {
                    e.addSuppressed(persistFailure);
                }
                LOG.warnv(e, "Failed to restore RDS cluster {0}", cluster.getDbClusterIdentifier());
            }
        }
    }

    private void restoreInstances() {
        for (DbInstance instance : allInstances()) {
            if (instance.getStatus() == DbInstanceStatus.DELETING) {
                continue;
            }
            String accountId = accountIdFromArn(instance.getDbInstanceArn());
            String instanceRegion = regionFromArn(instance.getDbInstanceArn());
            String clusterId = instance.getDbClusterIdentifier();
            if (clusterId != null && !clusterId.isBlank()) {
                DbCluster owningCluster = findClusterForScope(accountId, instanceRegion, clusterId);
                if (owningCluster != null) {
                    instance.setContainerStorageResourceId(
                            resolvedClusterStorageResourceId(owningCluster));
                    instance.setDockerVolumeName(resolvedClusterDockerVolumeName(owningCluster));
                }
            } else {
                instance.setContainerStorageResourceId(
                        resolvedInstanceStorageResourceId(instance));
                instance.setDockerVolumeName(resolvedInstanceDockerVolumeName(instance));
            }
            String persistedContainerId = instance.getContainerId();
            RdsContainerHandle restoredHandle = null;
            int proxyPort = 0;
            boolean portReserved = false;
            try {
                proxyPort = reserveOrAllocateProxyPort(instance.getProxyPort());
                portReserved = true;
                instance.setProxyPort(proxyPort);
                if (config.services().rds().mock()) {
                    instance.setEndpoint(new DbEndpoint("localhost", proxyPort));
                    instance.setStatus(DbInstanceStatus.AVAILABLE);
                    putInstanceForScope(accountId, instanceRegion,
                            instance.getDbInstanceIdentifier(), instance);
                    continue;
                }
                instance.setEndpoint(new DbEndpoint(proxyEndpointHost(), proxyPort));
                String backendHost;
                int backendPort;
                if (clusterId != null && !clusterId.isBlank()) {
                    DbCluster cluster = findClusterForScope(
                            accountId,
                            instanceRegion, clusterId);
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
                } else {
                    String image = imageForEngine(instance.getEngine(), instance.getEngineVersion());
                    restoredHandle = containerManager.start(
                            instance.getDbInstanceArn(), instance.getDbInstanceIdentifier(),
                            instance.getContainerStorageResourceId(),
                            instance.getDockerVolumeName(), instance.getEngine(), image,
                            instance.getMasterUsername(), instance.getMasterPassword(), instance.getDbName());
                    backendHost = restoredHandle.getHost();
                    backendPort = restoredHandle.getPort();
                    instance.setContainerId(restoredHandle.getContainerId());
                    instance.setContainerHost(restoredHandle.getHost());
                    instance.setContainerPort(restoredHandle.getPort());
                }

                String effectiveMasterUser = instance.getMasterUsername() != null
                        ? instance.getMasterUsername() : "root";
                proxyManager.startProxy(rdsResourceRelayKey(
                                instance.getDbInstanceArn(), instance.getDbInstanceIdentifier()),
                        instance.getEngine(),
                        instance.isIamDatabaseAuthenticationEnabled(), proxyPort,
                        backendHost, backendPort, effectiveMasterUser,
                        instance.getMasterPassword(), instance.getDbName(),
                        (user, pw) -> validateDbPasswordForScope(
                                accountId, instanceRegion,
                                instance.getDbInstanceIdentifier(), user, pw));
                instance.setStatus(DbInstanceStatus.AVAILABLE);
                putInstanceForScope(accountId, instanceRegion,
                        instance.getDbInstanceIdentifier(), instance);
            } catch (Exception e) {
                if (!config.services().rds().mock()) {
                    try {
                        proxyManager.stopProxy(rdsResourceRelayKey(
                                instance.getDbInstanceArn(), instance.getDbInstanceIdentifier()));
                    } catch (RuntimeException stopFailure) {
                        e.addSuppressed(stopFailure);
                    }
                }
                RdsContainerHandle cleanupHandle = restoredHandle != null
                        ? restoredHandle
                        : containerManager.getActiveHandle(instance.getDbInstanceArn());
                boolean containerCleaned = stopRestoredContainer(cleanupHandle, e, "instance",
                        instance.getDbInstanceIdentifier());
                if (portReserved) {
                    releaseProxyPort(proxyPort);
                }
                instance.setProxyPort(0);
                instance.setStatus(DbInstanceStatus.FAILED);
                instance.setEndpoint(null);
                String retainedContainerId = !containerCleaned && cleanupHandle != null
                        ? cleanupHandle.getContainerId()
                        : restoredHandle == null ? persistedContainerId : null;
                instance.setContainerId(retainedContainerId);
                instance.setContainerHost(null);
                instance.setContainerPort(0);
                try {
                    putInstanceForScope(accountId, instanceRegion,
                            instance.getDbInstanceIdentifier(), instance);
                } catch (RuntimeException persistFailure) {
                    e.addSuppressed(persistFailure);
                }
                LOG.warnv(e, "Failed to restore RDS instance {0}", instance.getDbInstanceIdentifier());
            }
        }
    }

    private boolean stopRestoredContainer(
            RdsContainerHandle handle, Exception restoreFailure,
            String resourceType, String resourceId) {
        if (handle == null) {
            return true;
        }
        try {
            containerManager.stop(handle);
            return true;
        } catch (RuntimeException | Error cleanupFailure) {
            restoreFailure.addSuppressed(cleanupFailure);
            LOG.errorv(cleanupFailure,
                    "Failed to clean up container after restoring RDS {0} {1}",
                    resourceType, resourceId);
            return false;
        }
    }

    /** Re-arms each persisted DB proxy's relay after a restart (clusters/instances restored first). */
    private void restoreProxies() {
        for (DbProxy proxy : allProxies()) {
            if (proxy.getDefaultAuthScheme() == null || proxy.getDefaultAuthScheme().isBlank()) {
                proxy.setDefaultAuthScheme("NONE");
            }
            if (proxy.getEndpointNetworkType() == null
                    || proxy.getEndpointNetworkType().isBlank()) {
                proxy.setEndpointNetworkType("IPV4");
            }
            if (proxy.getTargetConnectionNetworkType() == null
                    || proxy.getTargetConnectionNetworkType().isBlank()) {
                proxy.setTargetConnectionNetworkType("IPV4");
            }
            if (proxy.getCreatedAt() == null && proxy.getUpdatedAt() != null) {
                proxy.setCreatedAt(proxy.getUpdatedAt());
            }
            if (proxy.getUpdatedAt() == null && proxy.getCreatedAt() != null) {
                proxy.setUpdatedAt(proxy.getCreatedAt());
            }
            String proxyAccountId = accountIdFromArn(proxy.getDbProxyArn());
            if (proxy.getVpcId() == null && proxy.getVpcSubnetIds().size() >= 2
                    && Objects.equals(proxyAccountId, defaultAccountId())) {
                try {
                    proxy.setVpcId(resolveDbProxyVpc(
                            proxy.getVpcSubnetIds(), regionFromArn(proxy.getDbProxyArn())));
                } catch (AwsException migrationFailure) {
                    LOG.warnv(migrationFailure,
                            "Could not derive VPC for persisted RDS proxy {0}; preserving existing state",
                            proxy.getDbProxyName());
                }
            }
            boolean portReserved = false;
            try {
                int proxyPort = reserveOrAllocateProxyPort(proxy.getProxyPort());
                portReserved = true;
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
                DbProxyTargetGroup targetGroup = ensureRestoredDefaultTargetGroup(proxy);
                if (targetGroup.getTargets().isEmpty()) {
                    proxy.setStatus("available");
                    persistRestoredProxy(proxy);
                    continue;   // no target registered yet; nothing to relay to
                }
                if ("IAM_AUTH".equals(proxy.getDefaultAuthScheme())) {
                    throw new AwsException("InvalidDBProxyStateFault",
                            "DefaultAuthScheme IAM_AUTH is available for control-plane emulation only; "
                                    + "the real-mode relay does not support end-to-end IAM authentication.",
                            400);
                }
                startDbProxyRelay(proxy, targetGroup.getTargets().get(0));
                proxy.setStatus("available");
                persistRestoredProxy(proxy);
            } catch (Exception e) {
                if (!config.services().rds().mock()) {
                    try {
                        proxyManager.stopProxy(dbProxyRelayKey(proxy));
                    } catch (RuntimeException stopFailure) {
                        e.addSuppressed(stopFailure);
                    }
                }
                if (!portReserved) {
                    proxy.setProxyPort(0);
                    proxy.setEndpointHost(null);
                }
                proxy.setStatus("insufficient-resource-limits");
                try {
                    persistRestoredProxy(proxy);
                } catch (RuntimeException persistFailure) {
                    e.addSuppressed(persistFailure);
                }
                LOG.warnv(e, "Failed to restore RDS proxy {0}", proxy.getDbProxyName());
            }
        }
    }

    private void persistRestoredProxy(DbProxy proxy) {
        String accountId = AwsArnUtils.accountOrDefault(proxy.getDbProxyArn(), defaultAccountId());
        String proxyKey = dbProxyKey(regionFromArn(proxy.getDbProxyArn()), proxy.getDbProxyName());
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            Optional<DbProxy> canonical = aware.getForAccount(accountId, proxyKey);
            if (canonical.isPresent() && !sameProxyScope(canonical.get(), proxy)) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical RDS proxy "
                                + proxy.getDbProxyName());
            }
            aware.getForAccountMigratingLegacyKeys(
                    accountId, proxyKey, List.of(proxy.getDbProxyName()),
                    candidate -> sameProxyScope(candidate, proxy));
            aware.putForAccount(accountId, proxyKey, proxy);
        } else {
            Optional<DbProxy> canonical = proxies.get(proxyKey);
            if (canonical.isPresent() && !sameProxyScope(canonical.get(), proxy)) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical RDS proxy "
                                + proxy.getDbProxyName());
            }
            Optional<DbProxy> legacy = proxies.get(proxy.getDbProxyName());
            proxies.put(proxyKey, proxy);
            if (legacy.filter(candidate -> sameProxyScope(candidate, proxy)).isPresent()) {
                proxies.delete(proxy.getDbProxyName());
            }
        }
    }

    private boolean sameProxyScope(DbProxy left, DbProxy right) {
        if (left == null || right == null
                || !Objects.equals(left.getDbProxyName(), right.getDbProxyName())
                || !Objects.equals(left.getDbProxyArn(), right.getDbProxyArn())) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(left.getDbProxyArn());
            return proxyBelongsTo(left, arn.accountId(), arn.region())
                    && proxyBelongsTo(right, arn.accountId(), arn.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private DbProxyTargetGroup ensureRestoredDefaultTargetGroup(DbProxy proxy) {
        String defaultAccountId = defaultAccountId();
        String accountId = AwsArnUtils.accountOrDefault(proxy.getDbProxyArn(), defaultAccountId);
        String region = regionFromArn(proxy.getDbProxyArn());
        String proxyKey = dbProxyKey(region, proxy.getDbProxyName());
        java.util.function.Predicate<DbProxyTargetGroup> legacyOwner = candidate ->
                targetGroupCanMigrateToProxy(candidate, proxy);
        DbProxyTargetGroup targetGroup;
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            Optional<DbProxyTargetGroup> canonical = aware.getForAccount(accountId, proxyKey);
            if (canonical.isPresent() && canonical.filter(legacyOwner).isEmpty()) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical target group for RDS proxy "
                                + proxy.getDbProxyName());
            }
            targetGroup = canonical.orElse(null);
            if (canonical.isEmpty()) {
                targetGroup = aware.getForAccountMigratingLegacyKeys(
                                accountId, proxyKey, List.of(proxy.getDbProxyName()), legacyOwner)
                        .filter(legacyOwner)
                        .orElse(null);
            }
        } else {
            Optional<DbProxyTargetGroup> canonical = proxyTargetGroups.get(proxyKey);
            if (canonical.isPresent() && canonical.filter(legacyOwner).isEmpty()) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical target group for RDS proxy "
                                + proxy.getDbProxyName());
            }
            targetGroup = canonical.orElseGet(() -> proxyTargetGroups
                    .get(proxy.getDbProxyName()).filter(legacyOwner).orElse(null));
        }
        if (targetGroup != null) {
            Instant generation = proxy.getCreatedAt() != null
                    ? proxy.getCreatedAt()
                    : targetGroup.getCreatedAt() != null
                    ? targetGroup.getCreatedAt()
                    : Instant.now();
            proxy.setCreatedAt(generation);
            if (proxy.getUpdatedAt() == null) {
                proxy.setUpdatedAt(generation);
            }
            targetGroup.setDbProxyName(proxy.getDbProxyName());
            targetGroup.setTargetGroupName("default");
            targetGroup.setDefaultTargetGroup(true);
            targetGroup.setCreatedAt(generation);
            if (targetGroup.getUpdatedAt() == null) {
                targetGroup.setUpdatedAt(generation);
            }
            persistRestoredProxy(proxy);
            persistRestoredTargetGroup(accountId, proxyKey, proxy.getDbProxyName(), targetGroup);
            return targetGroup;
        }

        Instant now = proxy.getCreatedAt() != null ? proxy.getCreatedAt() : Instant.now();
        proxy.setCreatedAt(now);
        if (proxy.getUpdatedAt() == null) {
            proxy.setUpdatedAt(now);
        }
        persistRestoredProxy(proxy);
        targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName(proxy.getDbProxyName());
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn(AwsArnUtils.Arn.of("rds",
                region,
                accountId, "target-group:prx-tg-" + randomResourceSuffix()).toString());
        targetGroup.setDefaultTargetGroup(true);
        targetGroup.setCreatedAt(now);
        targetGroup.setUpdatedAt(now);
        persistRestoredTargetGroup(accountId, proxyKey, proxy.getDbProxyName(), targetGroup);
        return targetGroup;
    }

    private boolean targetGroupCanMigrateToProxy(
            DbProxyTargetGroup targetGroup, DbProxy proxy) {
        if (targetGroup == null || proxy == null
                || !Objects.equals(proxy.getDbProxyName(), targetGroup.getDbProxyName())
                || (targetGroup.getTargetGroupName() != null
                && !targetGroup.getTargetGroupName().isBlank()
                && !"default".equals(targetGroup.getTargetGroupName()))) {
            return false;
        }
        try {
            AwsArnUtils.Arn proxyArn = AwsArnUtils.parse(proxy.getDbProxyArn());
            if (!proxyBelongsTo(proxy, proxyArn.accountId(), proxyArn.region())
                    || !targetGroupBelongsTo(
                    targetGroup, proxyArn.accountId(), proxyArn.region())) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (proxy.getCreatedAt() != null && targetGroup.getCreatedAt() == null) {
            return false;
        }
        return proxy.getCreatedAt() == null
                || Objects.equals(proxy.getCreatedAt(), targetGroup.getCreatedAt());
    }

    private void persistRestoredTargetGroup(
            String accountId, String proxyKey, String legacyKey, DbProxyTargetGroup targetGroup) {
        if (proxyTargetGroups instanceof AccountAwareStorageBackend<DbProxyTargetGroup> aware) {
            Optional<DbProxyTargetGroup> canonical = aware.getForAccount(accountId, proxyKey);
            if (canonical.isPresent()
                    && !sameTargetGroupScope(canonical.get(), targetGroup)) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical RDS proxy target group "
                                + targetGroup.getDbProxyName());
            }
            aware.getForAccountMigratingLegacyKeys(
                    accountId, proxyKey, List.of(legacyKey),
                    candidate -> sameTargetGroupScope(candidate, targetGroup));
            aware.putForAccount(accountId, proxyKey, targetGroup);
        } else {
            Optional<DbProxyTargetGroup> canonical = proxyTargetGroups.get(proxyKey);
            if (canonical.isPresent()
                    && !sameTargetGroupScope(canonical.get(), targetGroup)) {
                throw new IllegalStateException(
                        "Refusing to overwrite corrupt canonical RDS proxy target group "
                                + targetGroup.getDbProxyName());
            }
            Optional<DbProxyTargetGroup> legacy = proxyTargetGroups.get(legacyKey);
            proxyTargetGroups.put(proxyKey, targetGroup);
            if (legacy.filter(candidate -> sameTargetGroupScope(candidate, targetGroup)).isPresent()) {
                proxyTargetGroups.delete(legacyKey);
            }
        }
    }

    private boolean targetGroupMatchesProxy(DbProxyTargetGroup targetGroup, DbProxy proxy) {
        if (proxy == null || targetGroup == null
                || !Objects.equals(proxy.getDbProxyName(), targetGroup.getDbProxyName())
                || !"default".equals(targetGroup.getTargetGroupName())
                || !targetGroup.isDefaultTargetGroup()
                || proxy.getCreatedAt() == null
                || !Objects.equals(proxy.getCreatedAt(), targetGroup.getCreatedAt())) {
            return false;
        }
        try {
            AwsArnUtils.Arn proxyArn = AwsArnUtils.parse(proxy.getDbProxyArn());
            return proxyBelongsTo(proxy, proxyArn.accountId(), proxyArn.region())
                    && targetGroupBelongsTo(
                    targetGroup, proxyArn.accountId(), proxyArn.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean sameTargetGroupScope(
            DbProxyTargetGroup left, DbProxyTargetGroup right) {
        if (left == null || right == null
                || !Objects.equals(left.getDbProxyName(), right.getDbProxyName())
                || !Objects.equals(left.getTargetGroupArn(), right.getTargetGroupArn())) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(left.getTargetGroupArn());
            return targetGroupBelongsTo(left, arn.accountId(), arn.region())
                    && targetGroupBelongsTo(right, arn.accountId(), arn.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private DbCluster getClusterForRestore(DbProxy proxy, String clusterId) {
        DbCluster cluster = findClusterForScope(
                accountIdFromArn(proxy.getDbProxyArn()),
                regionFromArn(proxy.getDbProxyArn()), clusterId);
        if (cluster == null) {
            throw new AwsException("DBClusterNotFoundFault",
                    "DB cluster " + clusterId + " not found.", 404);
        }
        return cluster;
    }

    private DbInstance getInstanceForRestore(DbProxy proxy, String instanceId) {
        DbInstance instance = findInstanceForScope(
                accountIdFromArn(proxy.getDbProxyArn()),
                regionFromArn(proxy.getDbProxyArn()), instanceId);
        if (instance == null) {
            throw new AwsException("DBInstanceNotFoundFault",
                    "DB instance " + instanceId + " not found.", 404);
        }
        return instance;
    }

    private synchronized DbCluster findClusterForScope(
            String accountId, String region, String clusterId) {
        String effectiveAccountId = accountId != null ? accountId : currentAccountId();
        String effectiveRegion = effectiveRegion(region);
        String key = dbResourceKey(effectiveRegion, clusterId);
        java.util.function.Predicate<DbCluster> owner = cluster -> hasRdsResourceIdentity(
                cluster.getDbClusterArn(), effectiveAccountId, effectiveRegion,
                "cluster", clusterId);
        if (clusters instanceof AccountAwareStorageBackend<DbCluster> aware) {
            return aware.getForAccountMigratingLegacyKeys(
                            effectiveAccountId, key, List.of(clusterId), owner)
                    .filter(owner)
                    .orElse(null);
        }

        Optional<DbCluster> canonical = clusters.get(key).filter(owner);
        if (canonical.isPresent()) {
            clusters.get(clusterId).filter(owner).ifPresent(ignored -> clusters.delete(clusterId));
            return canonical.get();
        }
        Optional<DbCluster> legacy = clusters.get(clusterId).filter(owner);
        if (legacy.isPresent()) {
            clusters.put(key, legacy.get());
            clusters.delete(clusterId);
        }
        return legacy.orElse(null);
    }

    private synchronized DbInstance findInstanceForScope(
            String accountId, String region, String instanceId) {
        String effectiveAccountId = accountId != null ? accountId : currentAccountId();
        String effectiveRegion = effectiveRegion(region);
        String key = dbResourceKey(effectiveRegion, instanceId);
        java.util.function.Predicate<DbInstance> owner = instance -> hasRdsResourceIdentity(
                instance.getDbInstanceArn(), effectiveAccountId, effectiveRegion,
                "db", instanceId);
        if (instances instanceof AccountAwareStorageBackend<DbInstance> aware) {
            return aware.getForAccountMigratingLegacyKeys(
                            effectiveAccountId, key, List.of(instanceId), owner)
                    .filter(owner)
                    .orElse(null);
        }

        Optional<DbInstance> canonical = instances.get(key).filter(owner);
        if (canonical.isPresent()) {
            instances.get(instanceId).filter(owner).ifPresent(ignored -> instances.delete(instanceId));
            return canonical.get();
        }
        Optional<DbInstance> legacy = instances.get(instanceId).filter(owner);
        if (legacy.isPresent()) {
            instances.put(key, legacy.get());
            instances.delete(instanceId);
        }
        return legacy.orElse(null);
    }

    private void putClusterForScope(
            String accountId, String region, String clusterId, DbCluster cluster) {
        String key = dbResourceKey(region, clusterId);
        if (clusters instanceof AccountAwareStorageBackend<DbCluster> aware) {
            aware.putForAccount(accountId, key, cluster);
        } else {
            clusters.put(key, cluster);
        }
    }

    private void deleteClusterForScope(String accountId, String region, String clusterId) {
        String key = dbResourceKey(region, clusterId);
        if (clusters instanceof AccountAwareStorageBackend<DbCluster> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            clusters.delete(key);
        }
    }

    private void putInstanceForScope(
            String accountId, String region, String instanceId, DbInstance instance) {
        String key = dbResourceKey(region, instanceId);
        if (instances instanceof AccountAwareStorageBackend<DbInstance> aware) {
            aware.putForAccount(accountId, key, instance);
        } else {
            instances.put(key, instance);
        }
    }

    private void deleteInstanceForScope(String accountId, String region, String instanceId) {
        String key = dbResourceKey(region, instanceId);
        if (instances instanceof AccountAwareStorageBackend<DbInstance> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            instances.delete(key);
        }
    }

    private synchronized DbSubnetGroup findSubnetGroupForScope(
            String accountId, String region, String groupName) {
        String effectiveRegion = effectiveRegion(region);
        String key = dbResourceKey(effectiveRegion, groupName);
        java.util.function.Predicate<DbSubnetGroup> owner = group ->
                Objects.equals(groupName, group.getDbSubnetGroupName())
                        && hasRdsResourceIdentity(
                        group.getDbSubnetGroupArn(), accountId, effectiveRegion,
                        "subgrp", groupName);
        if (subnetGroups instanceof AccountAwareStorageBackend<DbSubnetGroup> aware) {
            return aware.getForAccountMigratingLegacyKeys(
                            accountId, key, List.of(groupName), owner)
                    .filter(owner)
                    .orElse(null);
        }

        Optional<DbSubnetGroup> canonicalValue = subnetGroups.get(key);
        Optional<DbSubnetGroup> canonical = canonicalValue.filter(owner);
        if (canonical.isPresent()) {
            subnetGroups.get(groupName).filter(owner)
                    .ifPresent(ignored -> subnetGroups.delete(groupName));
            return canonical.get();
        }
        if (canonicalValue.isPresent()) {
            return null;
        }
        Optional<DbSubnetGroup> legacy = subnetGroups.get(groupName).filter(owner);
        if (legacy.isPresent()) {
            subnetGroups.put(key, legacy.get());
            subnetGroups.delete(groupName);
        }
        return legacy.orElse(null);
    }

    private synchronized DbParameterGroup findParameterGroupForRegion(
            String groupName, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = currentAccountId();
        String key = dbResourceKey(effectiveRegion, groupName);
        java.util.function.Predicate<DbParameterGroup> owner = group ->
                Objects.equals(groupName, group.getDbParameterGroupName())
                        && parameterGroupBelongsToRegion(group, effectiveRegion);
        Optional<DbParameterGroup> resolved;
        if (parameterGroups instanceof AccountAwareStorageBackend<DbParameterGroup> aware) {
            resolved = aware.getForAccountMigratingLegacyKeys(
                    accountId, key, List.of(groupName), owner,
                    Objects.equals(accountId, defaultAccountId()));
        } else {
            Optional<DbParameterGroup> canonicalValue = parameterGroups.get(key);
            Optional<DbParameterGroup> canonical = canonicalValue.filter(owner);
            if (canonical.isPresent()) {
                parameterGroups.get(groupName).filter(owner)
                        .ifPresent(ignored -> parameterGroups.delete(groupName));
                resolved = canonical;
            } else if (canonicalValue.isPresent()) {
                resolved = Optional.empty();
            } else {
                Optional<DbParameterGroup> legacy = parameterGroups.get(groupName).filter(owner);
                if (legacy.isPresent()) {
                    parameterGroups.put(key, legacy.get());
                    parameterGroups.delete(groupName);
                }
                resolved = legacy;
            }
        }
        resolved.ifPresent(group -> {
            if (group.getRegion() == null || group.getRegion().isBlank()) {
                group.setRegion(effectiveRegion);
                putParameterGroupForRegion(groupName, effectiveRegion, group);
            }
        });
        return resolved.orElse(null);
    }

    private boolean parameterGroupBelongsToRegion(
            DbParameterGroup group, String region) {
        if (group == null) {
            return false;
        }
        String storedRegion = group.getRegion();
        return storedRegion == null || storedRegion.isBlank()
                ? Objects.equals(regionResolver.getDefaultRegion(), region)
                : Objects.equals(storedRegion, region);
    }

    private void putParameterGroupForRegion(
            String groupName, String region, DbParameterGroup group) {
        String key = dbResourceKey(region, groupName);
        if (parameterGroups instanceof AccountAwareStorageBackend<DbParameterGroup> aware) {
            aware.putForAccount(currentAccountId(), key, group);
        } else {
            parameterGroups.put(key, group);
        }
    }

    private void deleteParameterGroupForRegion(String groupName, String region) {
        String key = dbResourceKey(region, groupName);
        if (parameterGroups instanceof AccountAwareStorageBackend<DbParameterGroup> aware) {
            aware.deleteForAccount(currentAccountId(), key);
        } else {
            parameterGroups.delete(key);
        }
    }

    private synchronized DbClusterParameterGroup findClusterParameterGroupForRegion(
            String groupName, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = currentAccountId();
        String key = dbResourceKey(effectiveRegion, groupName);
        java.util.function.Predicate<DbClusterParameterGroup> owner = group ->
                Objects.equals(groupName, group.getDbClusterParameterGroupName())
                        && clusterParameterGroupBelongsToRegion(group, effectiveRegion);
        Optional<DbClusterParameterGroup> resolved;
        if (clusterParameterGroups
                instanceof AccountAwareStorageBackend<DbClusterParameterGroup> aware) {
            resolved = aware.getForAccountMigratingLegacyKeys(
                    accountId, key, List.of(groupName), owner,
                    Objects.equals(accountId, defaultAccountId()));
        } else {
            Optional<DbClusterParameterGroup> canonicalValue =
                    clusterParameterGroups.get(key);
            Optional<DbClusterParameterGroup> canonical = canonicalValue.filter(owner);
            if (canonical.isPresent()) {
                clusterParameterGroups.get(groupName).filter(owner)
                        .ifPresent(ignored -> clusterParameterGroups.delete(groupName));
                resolved = canonical;
            } else if (canonicalValue.isPresent()) {
                resolved = Optional.empty();
            } else {
                Optional<DbClusterParameterGroup> legacy =
                        clusterParameterGroups.get(groupName).filter(owner);
                if (legacy.isPresent()) {
                    clusterParameterGroups.put(key, legacy.get());
                    clusterParameterGroups.delete(groupName);
                }
                resolved = legacy;
            }
        }
        resolved.ifPresent(group -> {
            if (group.getRegion() == null || group.getRegion().isBlank()) {
                group.setRegion(effectiveRegion);
                putClusterParameterGroupForRegion(groupName, effectiveRegion, group);
            }
        });
        return resolved.orElse(null);
    }

    private boolean clusterParameterGroupBelongsToRegion(
            DbClusterParameterGroup group, String region) {
        if (group == null) {
            return false;
        }
        String storedRegion = group.getRegion();
        return storedRegion == null || storedRegion.isBlank()
                ? Objects.equals(regionResolver.getDefaultRegion(), region)
                : Objects.equals(storedRegion, region);
    }

    private void putClusterParameterGroupForRegion(
            String groupName, String region, DbClusterParameterGroup group) {
        String key = dbResourceKey(region, groupName);
        if (clusterParameterGroups
                instanceof AccountAwareStorageBackend<DbClusterParameterGroup> aware) {
            aware.putForAccount(currentAccountId(), key, group);
        } else {
            clusterParameterGroups.put(key, group);
        }
    }

    private void deleteClusterParameterGroupForRegion(String groupName, String region) {
        String key = dbResourceKey(region, groupName);
        if (clusterParameterGroups
                instanceof AccountAwareStorageBackend<DbClusterParameterGroup> aware) {
            aware.deleteForAccount(currentAccountId(), key);
        } else {
            clusterParameterGroups.delete(key);
        }
    }

    private void putSubnetGroupForScope(
            String accountId, String region, String groupName, DbSubnetGroup group) {
        String key = dbResourceKey(region, groupName);
        if (subnetGroups instanceof AccountAwareStorageBackend<DbSubnetGroup> aware) {
            aware.putForAccount(accountId, key, group);
        } else {
            subnetGroups.put(key, group);
        }
    }

    private void deleteSubnetGroupForScope(
            String accountId, String region, String groupName) {
        String key = dbResourceKey(region, groupName);
        if (subnetGroups instanceof AccountAwareStorageBackend<DbSubnetGroup> aware) {
            aware.deleteForAccount(accountId, key);
        } else {
            subnetGroups.delete(key);
        }
    }

    private String dbResourceKey(String region, String resourceId) {
        return effectiveRegion(region) + "::" + resourceId;
    }

    private <T> boolean scopedKeyExists(
            StorageBackend<String, T> storage, String accountId, String key) {
        if (storage instanceof AccountAwareStorageBackend<T> aware) {
            return aware.getForAccount(accountId, key).isPresent();
        }
        return storage.get(key).isPresent();
    }

    private boolean hasRdsResourceIdentity(
            String arn, String accountId, String region,
            String resourceType, String resourceId) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return "aws".equals(parsed.partition())
                    && "rds".equals(parsed.service())
                    && Objects.equals(accountId, parsed.accountId())
                    && Objects.equals(region, parsed.region())
                    && Objects.equals(resourceType + ":" + resourceId, parsed.resource());
        } catch (IllegalArgumentException e) {
            return false;
        }
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

    private String rdsResourceRelayKey(String resourceArn, String resourceId) {
        String identity = resourceArn;
        if (identity == null || identity.isBlank()) {
            identity = currentAccountId() + ":" + resourceId;
        }
        return "rds-resource:" + identity;
    }

    private Collection<DbCluster> allClusters() {
        Collection<DbCluster> stored = clusters instanceof AccountAwareStorageBackend<DbCluster> aware
                ? aware.scanAllAccounts() : clusters.scan(k -> true);
        Map<String, DbCluster> unique = new LinkedHashMap<>();
        for (DbCluster cluster : stored) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(cluster.getDbClusterArn());
                if (!"rds".equals(arn.service())
                        || !Objects.equals("cluster:" + cluster.getDbClusterIdentifier(), arn.resource())) {
                    continue;
                }
                DbCluster canonical = findClusterForScope(
                        arn.accountId(), arn.region(), cluster.getDbClusterIdentifier());
                if (canonical != null) {
                    unique.put(canonical.getDbClusterArn(), canonical);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnv("Skipping persisted RDS cluster with invalid ARN: {0}",
                        cluster.getDbClusterIdentifier());
            }
        }
        return unique.values();
    }

    private Collection<DbInstance> allInstances() {
        Collection<DbInstance> stored = instances instanceof AccountAwareStorageBackend<DbInstance> aware
                ? aware.scanAllAccounts() : instances.scan(k -> true);
        Map<String, DbInstance> unique = new LinkedHashMap<>();
        for (DbInstance instance : stored) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(instance.getDbInstanceArn());
                if (!"rds".equals(arn.service())
                        || !Objects.equals("db:" + instance.getDbInstanceIdentifier(), arn.resource())) {
                    continue;
                }
                DbInstance canonical = findInstanceForScope(
                        arn.accountId(), arn.region(), instance.getDbInstanceIdentifier());
                if (canonical != null) {
                    unique.put(canonical.getDbInstanceArn(), canonical);
                }
            } catch (IllegalArgumentException e) {
                LOG.warnv("Skipping persisted RDS instance with invalid ARN: {0}",
                        instance.getDbInstanceIdentifier());
            }
        }
        return unique.values();
    }

    private Collection<DbProxy> allProxies() {
        boolean accountAware = proxies instanceof AccountAwareStorageBackend<DbProxy>;
        Map<String, DbProxy> stored = new LinkedHashMap<>();
        if (proxies instanceof AccountAwareStorageBackend<DbProxy> aware) {
            stored.putAll(aware.scanAllAccountsWithRawKeys());
        } else {
            for (String rawKey : proxies.keys()) {
                proxies.get(rawKey).ifPresent(proxy -> stored.put(rawKey, proxy));
            }
        }
        List<Map.Entry<String, DbProxy>> candidates = new ArrayList<>(stored.entrySet());
        candidates.sort(Comparator.comparingInt(entry -> {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(entry.getValue().getDbProxyArn());
                return proxyStorageKeyPriority(
                        entry.getKey(), entry.getValue(), arn, accountAware);
            } catch (IllegalArgumentException e) {
                return Integer.MAX_VALUE;
            }
        }));
        Map<String, DbProxy> unique = new LinkedHashMap<>();
        for (Map.Entry<String, DbProxy> entry : candidates) {
            String rawKey = entry.getKey();
            DbProxy proxy = entry.getValue();
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(proxy.getDbProxyArn());
                if (!validPersistedProxyIdentity(proxy, arn)
                        || !proxyBelongsTo(proxy, arn.accountId(), arn.region())
                        || proxyStorageKeyPriority(rawKey, proxy, arn, accountAware)
                        == Integer.MAX_VALUE) {
                    LOG.warnv("Skipping persisted RDS proxy with invalid ARN identity: {0}",
                            proxy.getDbProxyName());
                    continue;
                }
                String key = dbProxyKey(arn.region(), proxy.getDbProxyName());
                DbProxy canonical = proxies instanceof AccountAwareStorageBackend<DbProxy> aware
                        ? aware.getForAccount(arn.accountId(), key).orElse(proxy)
                        : proxies.get(key).orElse(proxy);
                if (!proxyBelongsTo(canonical, arn.accountId(), arn.region())
                        || !sameProxyScope(canonical, proxy)) {
                    LOG.warnv("Skipping persisted RDS proxy with corrupt canonical state: {0}",
                            proxy.getDbProxyName());
                    continue;
                }
                String identity = arn.accountId() + "/" + arn.region()
                        + "/" + proxy.getDbProxyName();
                unique.putIfAbsent(identity, canonical);
            } catch (IllegalArgumentException e) {
                LOG.warnv("Skipping persisted RDS proxy with malformed ARN: {0}",
                        proxy.getDbProxyName());
            }
        }
        return unique.values();
    }

    private boolean validPersistedProxyIdentity(DbProxy proxy, AwsArnUtils.Arn arn) {
        String name = proxy.getDbProxyName();
        return !arn.accountId().isBlank()
                && !arn.region().isBlank()
                && name != null
                && !name.isBlank()
                && name.length() <= 63
                && DB_PROXY_NAME_PATTERN.matcher(name).matches();
    }

    private int proxyStorageKeyPriority(
            String rawKey, DbProxy proxy, AwsArnUtils.Arn arn, boolean accountAware) {
        if (rawKey == null || proxy.getDbProxyName() == null) {
            return Integer.MAX_VALUE;
        }
        String canonicalKey = dbProxyKey(arn.region(), proxy.getDbProxyName());
        if (!accountAware) {
            if (rawKey.equals(canonicalKey)) {
                return 0;
            }
            return rawKey.equals(proxy.getDbProxyName()) ? 1 : Integer.MAX_VALUE;
        }
        if (rawKey.equals(arn.accountId() + "/" + canonicalKey)) {
            return 0;
        }
        if (rawKey.equals(canonicalKey)) {
            return 1;
        }
        if (rawKey.equals(arn.accountId() + "/" + proxy.getDbProxyName())) {
            return 2;
        }
        return rawKey.equals(proxy.getDbProxyName()) ? 3 : Integer.MAX_VALUE;
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
                : getDbSubnetGroup(effectiveSubnetGroupName, region);

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

    private String resolveDbProxyVpc(List<String> subnetIds, String region) {
        List<Subnet> resolvedSubnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        Set<String> requestedIds = Set.copyOf(subnetIds);
        Set<String> resolvedIds = resolvedSubnets.stream()
                .map(Subnet::getSubnetId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (!resolvedIds.equals(requestedIds)) {
            throw new AwsException("InvalidSubnet",
                    "One or more VpcSubnetIds do not exist in region " + region + ".", 400);
        }

        Set<String> vpcIds = resolvedSubnets.stream()
                .map(Subnet::getVpcId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (vpcIds.size() != 1) {
            throw new AwsException("InvalidSubnet",
                    "VpcSubnetIds must belong to one VPC.", 400);
        }

        long availabilityZones = resolvedSubnets.stream()
                .map(Subnet::getAvailabilityZone)
                .filter(zone -> zone != null && !zone.isBlank())
                .distinct()
                .count();
        if (availabilityZones < 2) {
            throw new AwsException("InvalidSubnet",
                    "VpcSubnetIds must span at least two Availability Zones.", 400);
        }
        return vpcIds.iterator().next();
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

    private String newVolumeName(String volumeId, String storageResourceId) {
        String qualifiedVolumeId = volumeId == null || volumeId.isBlank()
                ? null : storageResourceId + "-" + volumeId;
        return volumeName(qualifiedVolumeId, storageResourceId);
    }

    private String resolvedInstanceStorageResourceId(DbInstance instance) {
        return firstNonBlank(
                instance.getContainerStorageResourceId(),
                instance.getDbInstanceIdentifier());
    }

    private String resolvedInstanceDockerVolumeName(DbInstance instance) {
        if (instance.getDockerVolumeName() != null
                && !instance.getDockerVolumeName().isBlank()) {
            return instance.getDockerVolumeName();
        }
        if (instance.getContainerStorageResourceId() != null
                && !instance.getContainerStorageResourceId().isBlank()) {
            return newVolumeName(
                    instance.getVolumeId(), instance.getContainerStorageResourceId());
        }
        return volumeName(instance.getVolumeId(), instance.getDbInstanceIdentifier());
    }

    private String resolvedClusterStorageResourceId(DbCluster cluster) {
        return firstNonBlank(
                cluster.getContainerStorageResourceId(),
                cluster.getDbClusterIdentifier());
    }

    private String resolvedClusterDockerVolumeName(DbCluster cluster) {
        if (cluster.getDockerVolumeName() != null
                && !cluster.getDockerVolumeName().isBlank()) {
            return cluster.getDockerVolumeName();
        }
        if (cluster.getContainerStorageResourceId() != null
                && !cluster.getContainerStorageResourceId().isBlank()) {
            return newVolumeName(
                    cluster.getVolumeId(), cluster.getContainerStorageResourceId());
        }
        return volumeName(cluster.getVolumeId(), cluster.getDbClusterIdentifier());
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private RdsContainerHandle buildHandle(DbInstance instance) {
        return new RdsContainerHandle(
                instance.getContainerId(), instance.getDbInstanceArn(),
                instance.getDbInstanceIdentifier(),
                instance.getContainerHost(), instance.getContainerPort());
    }

    private RdsContainerHandle buildClusterHandle(DbCluster cluster) {
        return new RdsContainerHandle(
                cluster.getContainerId(), cluster.getDbClusterArn(),
                cluster.getDbClusterIdentifier(),
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
