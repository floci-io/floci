package io.github.hectorvent.floci.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSettings;
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstanceSettings;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
        return createDbCluster(id, engineVersion, iamEnabled, NeptuneClusterSettings.defaults(), Map.of());
    }

    public NeptuneCluster createDbCluster(String id, String engineVersion, boolean iamEnabled,
                                          NeptuneClusterSettings settings, Map<String, String> tags) {
        settings.validate();
        if (clusters.get(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "Neptune cluster " + id + " already exists.", 400);
        }

        // Open the try immediately after reserving the port so config reads below can't leak it.
        int proxyPort = allocateProxyPort(settings.port());
        NeptuneContainerHandle handle = null;
        boolean provisioned = false;
        try {
            String configuredDbType = config.services().neptune().dbType();
            NeptuneDbType dbType = NeptuneDbType.fromConfig(configuredDbType).orElseGet(() -> {
                LOG.warnv("Unsupported Neptune db-type ''{0}'', falling back to {1}. Supported: gremlin, neo4j.",
                        configuredDbType, NeptuneDbType.GREMLIN);
                return NeptuneDbType.GREMLIN;
            });
            String image = switch (dbType) {
                case GREMLIN -> config.services().neptune().defaultImage();
                case NEO4J -> config.services().neptune().defaultNeo4jImage();
            };

            LOG.infov("Creating Neptune cluster {0} on proxy port {1}, dbType={2}, image={3}",
                    id, String.valueOf(proxyPort), dbType, image);

            // A cluster record is metadata: its identifier, ARN, endpoint host and proxy port
            // are derived from configuration and need no Docker, so the cluster is created and
            // reaches 'available' even when no daemon is reachable. Only connecting to the
            // graph database needs the container.
            handle = containerManager.tryStart(id, image, dbType);

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
            cluster.setProxyPort(proxyPort);
            settings.applyTo(cluster);
            if (tags != null && !tags.isEmpty()) {
                cluster.setTags(tags);
            }

            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                proxyManager.startProxy(id, proxyPort, handle.getHost(), handle.getPort());
            } else {
                LOG.warnv("Neptune cluster {0} created without a backing graph database container: "
                        + "no Docker daemon is reachable. Metadata operations work; connections to "
                        + "the cluster do not until a daemon appears.", id);
            }

            clusters.put(id, cluster);
            provisioned = true;
            LOG.infov("Neptune cluster {0} created ({1}), endpoint={2}:{3}",
                    id, dbType, endpointHost, String.valueOf(proxyPort));
            return cluster;
        } catch (RuntimeException e) {
            LOG.warnv("Neptune cluster {0} provisioning failed, rolling back: {1}", id, e.getMessage());
            throw e;
        } finally {
            // Roll back on ANY non-success exit — including a JVM Error, which a
            // catch (RuntimeException) would miss — so a failed create never leaks the
            // reserved port or leaves a container behind. Idempotent and a no-op on success.
            if (!provisioned) {
                rollbackDbCluster(id, handle, proxyPort);
            }
        }
    }

    private void rollbackDbCluster(String id, NeptuneContainerHandle handle, int proxyPort) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(id);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for Neptune cluster {0}: {1}", id, e.getMessage());
            }
            try {
                // Stop by id, not handle: a readiness timeout in containerManager.start() throws
                // after the container was created and registered but before the handle is returned,
                // so cleaning up by handle here would miss (and orphan) it. stopByClusterId is
                // idempotent, so it's safe when the container never started.
                containerManager.stopByClusterId(id);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for Neptune cluster {0}: {1}", id, e.getMessage());
            }
        } finally {
            // Always release the port — even if a cleanup step throws a non-RuntimeException
            // (e.g. an Error) — since leaking the port is the exact failure this rollback prevents.
            releaseProxyPort(proxyPort);
        }
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

    public boolean hasResourceWithArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            return false;
        }
        return clusters.scan(k -> true).stream()
                        .anyMatch(c -> arn.equalsIgnoreCase(c.getDbClusterArn()))
                || instances.scan(k -> true).stream()
                        .anyMatch(i -> arn.equalsIgnoreCase(i.getDbInstanceArn()));
    }

    public Collection<NeptuneCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-cluster-id filter accepts ARNs as well as identifiers. Match the
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

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        return modifyDbCluster(id, engineVersion, iamEnabled, NeptuneClusterSettings.unchanged());
    }

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled,
                                          NeptuneClusterSettings settings) {
        settings.validate();
        NeptuneCluster cluster = getDbCluster(id);
        if (engineVersion != null && !engineVersion.isBlank()) {
            cluster.setEngineVersion(engineVersion);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        settings.applyTo(cluster);
        clusters.put(id, cluster);
        LOG.infov("Neptune cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        NeptuneCluster cluster = clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));

        if (cluster.isDeletionProtection()) {
            throw new AwsException("InvalidParameterCombination",
                    "Cannot delete protected Cluster, please disable deletion protection and try again.", 400);
        }
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

    public NeptuneCluster addRoleToDbCluster(String id, String roleArn) {
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RoleArn is required.", 400);
        }
        NeptuneCluster cluster = getDbCluster(id);
        if (cluster.getAssociatedRoleArns().contains(roleArn)) {
            throw new AwsException("DBClusterRoleAlreadyExists",
                    "Role ARN " + roleArn + " is already associated with Neptune cluster " + id + ".", 400);
        }
        cluster.getAssociatedRoleArns().add(roleArn);
        clusters.put(id, cluster);
        LOG.infov("Role {0} added to Neptune cluster {1}", roleArn, id);
        return cluster;
    }

    public NeptuneCluster removeRoleFromDbCluster(String id, String roleArn) {
        NeptuneCluster cluster = getDbCluster(id);
        if (roleArn == null || !cluster.getAssociatedRoleArns().remove(roleArn)) {
            throw new AwsException("DBClusterRoleNotFound",
                    "Role ARN " + roleArn + " is not associated with Neptune cluster " + id + ".", 404);
        }
        clusters.put(id, cluster);
        LOG.infov("Role {0} removed from Neptune cluster {1}", roleArn, id);
        return cluster;
    }

    public static String parameterGroupFamily(String engineVersion) {
        String version = engineVersion == null || engineVersion.isBlank() ? ENGINE_VERSION_DEFAULT : engineVersion.trim();
        String[] parts = version.split("\\.");
        int major;
        int minor;
        try {
            major = Integer.parseInt(parts[0]);
            minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return parameterGroupFamily(ENGINE_VERSION_DEFAULT);
        }
        if (major == 1 && minor < 2) {
            return "neptune1";
        }
        return "neptune" + major + "." + minor;
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled) {
        return createDbInstance(id, dbClusterIdentifier, dbInstanceClass, engineVersion, iamEnabled, Map.of());
    }

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled, Map<String, String> tags) {
        return createDbInstance(id, dbClusterIdentifier, dbInstanceClass, engineVersion, iamEnabled,
                NeptuneInstanceSettings.defaults(), tags);
    }

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled, NeptuneInstanceSettings settings,
                                            Map<String, String> tags) {
        settings.validate();
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
        settings.applyTo(instance);
        if (tags != null && !tags.isEmpty()) {
            instance.setTags(tags);
        }

        cluster.getDbClusterMembers().add(id);
        clusters.put(dbClusterIdentifier, cluster);

        instances.put(id, instance);
        LOG.infov("Neptune instance {0} created in cluster {1}", id, dbClusterIdentifier);
        return instance;
    }

    public Optional<NeptuneCluster> findDbCluster(String id) {
        return id == null ? Optional.empty() : clusters.get(id);
    }

    public NeptuneInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));
    }

    public Collection<NeptuneInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-instance-id filter accepts ARNs as well as identifiers; see
            // listDbClusters for why the match is against the stored ARN.
            if (filterId.startsWith("arn:")) {
                return instances.scan(k -> true).stream()
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return instances.scan(k -> k.equalsIgnoreCase(filterId));
        }
        return instances.scan(k -> true);
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        return modifyDbInstance(id, dbInstanceClass, iamEnabled, NeptuneInstanceSettings.unchanged());
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled,
                                            NeptuneInstanceSettings settings) {
        settings.validate();
        NeptuneInstance instance = getDbInstance(id);
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        settings.applyTo(instance);
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

    // ── Tags ──────────────────────────────────────────────────────────────────

    private record TagTarget(Map<String, String> tags, Consumer<Map<String, String>> save) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolveTagTarget(resourceName).tags()));
    }

    public synchronized void addTagsToResource(String resourceName, Map<String, String> tags) {
        updateTags(resourceName, current -> current.putAll(tags));
    }

    public synchronized void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        updateTags(resourceName, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceName, Consumer<Map<String, String>> change) {
        TagTarget target = resolveTagTarget(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(target.tags());
        change.accept(updated);
        target.save().accept(updated);
    }

    private TagTarget resolveTagTarget(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (separator < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String type = resource.substring(0, separator);
        String id = resource.substring(separator + 1);
        return switch (type) {
            case "cluster" -> {
                NeptuneCluster cluster = clusters.scan(k -> true).stream()
                        .filter(c -> resourceName.equalsIgnoreCase(c.getDbClusterArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBClusterNotFoundFault",
                                "Neptune cluster " + id + " not found.", 404));
                yield new TagTarget(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(cluster.getDbClusterIdentifier(), cluster);
                });
            }
            case "db" -> {
                NeptuneInstance instance = instances.scan(k -> true).stream()
                        .filter(i -> resourceName.equalsIgnoreCase(i.getDbInstanceArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBInstanceNotFound",
                                "Neptune instance " + id + " not found.", 404));
                yield new TagTarget(instance.getTags(), updated -> {
                    instance.setTags(updated);
                    instances.put(instance.getDbInstanceIdentifier(), instance);
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported by Neptune in Floci: " + resourceName, 400);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort(Integer requested) {
        int base = config.services().neptune().proxyBasePort();
        int max = config.services().neptune().proxyMaxPort();
        if (requested != null && requested >= base && requested <= max && usedPorts.add(requested)) {
            return requested;
        }
        if (requested != null) {
            LOG.infov("Requested Neptune port {0} is outside the proxy range {1}-{2} or already in use; "
                    + "allocating the next free proxy port instead",
                    String.valueOf(requested), String.valueOf(base), String.valueOf(max));
        }
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        // Wire code the SDK maps to InsufficientStorageClusterCapacityFault (the only
        // capacity fault CreateDBCluster declares); "InsufficientNeptuneCapacity" isn't a
        // real Neptune code, so callers got an unmapped generic NeptuneException.
        throw new AwsException("InsufficientStorageClusterCapacity",
                "No available proxy ports in range " + base + "-" + max, 400);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
