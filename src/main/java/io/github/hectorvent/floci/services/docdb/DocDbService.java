package io.github.hectorvent.floci.services.docdb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * One monitor per stored record, taken by everything that writes it. A tag update is a
     * read-modify-write, so without a lock shared with delete it can put a deleted cluster back.
     */
    private final ConcurrentHashMap<String, Object> writeLocks = new ConcurrentHashMap<>();

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
        synchronized (lockFor("cluster:" + id)) {
            if (clusters.get(id).isPresent()) {
                throw new AwsException("DBClusterAlreadyExistsFault",
                        "DocDB cluster " + id + " already exists.", 400);
            }

            // The caller's region, not the configured default: the ARN this create answers with is
            // the one the caller tags by, and a tag call is checked against the region it is made
            // from — an ARN naming somewhere else is one its own creator cannot use.
            String region = regionResolver.getRegion();

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
                // A cluster record is metadata: its identifier, ARN and tags need no Docker, so the
                // cluster is created and reaches 'available' even when no daemon is reachable. Only
                // connecting to the database needs the container.
                DocDbContainerHandle handle = containerManager.tryStart(id, image, masterUsername, masterPassword);
                if (handle != null) {
                    cluster.setEndpoint(handle.getHost());
                    cluster.setReaderEndpoint(handle.getHost());
                    cluster.setPort(handle.getPort());
                    cluster.setContainerId(handle.getContainerId());
                    cluster.setContainerHost(handle.getHost());
                    cluster.setContainerPort(handle.getPort());
                } else {
                    cluster.setEndpoint(resolveEndpointHost());
                    cluster.setReaderEndpoint(resolveEndpointHost());
                    cluster.setPort(MONGO_PORT);
                    LOG.warnv("DocDB cluster {0} created without a backing MongoDB container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cluster do not until a daemon appears.", id);
                }
            }

            clusters.put(id, cluster);
            LOG.infov("DocDB cluster {0} created, endpoint={1}:{2}",
                    id, cluster.getEndpoint(), String.valueOf(cluster.getPort()));
            return cluster;
        }
    }

    public DocDbCluster getDbCluster(String id) {
        return clusters.get(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "DocDB cluster " + id + " not found.", 404));
    }

    public boolean hasCluster(String id) {
        return hasCluster(id, regionResolver.getRegion());
    }

    /**
     * Whether DocumentDB holds this cluster <em>in this region</em>.
     *
     * <p>The records are keyed by identifier alone, so the region has to come from the one place
     * that carries it — the stored ARN. Without that, an RDS request naming a cluster DocumentDB
     * holds somewhere else is answered from here, and a name RDS is free to reuse in another
     * region stops being usable.
     */
    public boolean hasCluster(String id, String region) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return clusters.get(id).filter(c -> regionOf(c.getDbClusterArn()).equals(region)).isPresent();
    }

    /** Refuses a record whose own ARN is not the one that was asked for. */
    private static void requireArnNamesRecord(String requested, String stored,
                                              String errorCode, String message) {
        if (stored != null && !stored.equalsIgnoreCase(requested)) {
            throw new AwsException(errorCode, message, 404);
        }
    }

    /** The region a record was created in, taken from its ARN; older records carry the default. */
    private String regionOf(String arn) {
        return AwsArnUtils.regionOrDefault(arn, regionResolver.getDefaultRegion());
    }

    /**
     * Whether an ARN names a DocumentDB cluster or instance, matched against the stored ARN.
     *
     * <p>RDS and DocumentDB share the {@code arn:aws:rds:...} space, so the trailing identifier
     * alone does not identify a service: an RDS resource whose name a DocumentDB record happens to
     * share would be answered from the wrong store. The full ARN settles region, account, type and
     * name in one comparison, as the db-cluster-id filter already does.
     */
    public boolean hasResourceWithArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            return false;
        }
        return clusters.scan(k -> true).stream()
                        .anyMatch(c -> arn.equalsIgnoreCase(c.getDbClusterArn()))
                || instances.scan(k -> true).stream()
                        .anyMatch(i -> arn.equalsIgnoreCase(i.getDbInstanceArn()));
    }

    public boolean hasInstance(String id) {
        return hasInstance(id, regionResolver.getRegion());
    }

    public boolean hasInstance(String id, String region) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return instances.get(id).filter(i -> regionOf(i.getDbInstanceArn()).equals(region)).isPresent();
    }

    public Collection<DocDbCluster> listDbClusters(String filterId) {
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

    public DocDbCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        synchronized (lockFor("cluster:" + id)) {
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
    }

    public void deleteDbCluster(String id) {
        synchronized (lockFor("cluster:" + id)) {
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
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public DocDbInstance createDbInstance(String id, String dbClusterIdentifier,
                                          String dbInstanceClass, String engineVersion,
                                          boolean iamEnabled) {
        // Instance monitor before cluster monitor, the one order every path that holds both uses.
        synchronized (lockFor("instance:" + id)) {
            synchronized (lockFor("cluster:" + dbClusterIdentifier)) {
                if (instances.get(id).isPresent()) {
                    throw new AwsException("DBInstanceAlreadyExists",
                            "DocDB instance " + id + " already exists.", 400);
                }

                DocDbCluster cluster = getDbCluster(dbClusterIdentifier);
                String region = regionResolver.getRegion();

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
        }
    }

    public DocDbInstance getDbInstance(String id) {
        return instances.get(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "DocDB instance " + id + " not found.", 404));
    }

    public Collection<DocDbInstance> listDbInstances(String filterId) {
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

    public DocDbInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        synchronized (lockFor("instance:" + id)) {
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
    }

    public void deleteDbInstance(String id) {
        synchronized (lockFor("instance:" + id)) {
            DocDbInstance instance = instances.get(id).orElseThrow(() ->
                    new AwsException("DBInstanceNotFound",
                            "DocDB instance " + id + " not found.", 404));

            String clusterId = instance.getDbClusterIdentifier();
            synchronized (lockFor("cluster:" + clusterId)) {
                DocDbCluster cluster = clusters.get(clusterId).orElse(null);
                if (cluster != null) {
                    cluster.getDbClusterMembers().remove(id);
                    clusters.put(clusterId, cluster);
                }
            }

            instances.delete(id);
            LOG.infov("DocDB instance {0} deleted", id);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    /** A resolved tag target: the record's key, its tags, and a sink that persists an update. */
    private record TagTarget(String lockKey, Map<String, String> tags,
                             java.util.function.Consumer<Map<String, String>> save) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagTarget(resourceName).tags());
    }

    public void addTagsToResource(String resourceName, Map<String, String> tags) {
        updateTags(resourceName, current -> current.putAll(tags));
    }

    public void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        // A key that is not present is not an error on a live account; the ones that are get removed.
        updateTags(resourceName, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceName, java.util.function.Consumer<Map<String, String>> change) {
        // Resolve once to learn which record is meant, then do the read-modify-write under that
        // record's monitor and resolve again inside it: the record can be deleted in between, and
        // saving what the first read returned would put it back.
        String lockKey = resolveTagTarget(resourceName).lockKey();
        synchronized (lockFor(lockKey)) {
            TagTarget target = resolveTagTarget(resourceName);
            Map<String, String> updated = new LinkedHashMap<>(target.tags());
            change.accept(updated);
            target.save().accept(updated);
        }
    }

    private Object lockFor(String key) {
        return writeLocks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Resolves a tagging {@code ResourceName} to the DocumentDB record it names.
     *
     * <p>Only ARNs reach here: the router picks this service by looking the ARN's identifier up in
     * DocumentDB storage, so a bare name stays with RDS. The region and account are checked before
     * the identifier, as a live account checks them — storage is keyed by identifier alone, so an
     * ARN naming another region would otherwise resolve this caller's cluster.
     */
    private TagTarget resolveTagTarget(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name:  " + resourceName, 400);
        }
        if (!"rds".equals(arn.service())
                || !regionResolver.getRegion().equals(arn.region())
                || !Objects.equals(regionResolver.getAccountId(), arn.accountId())) {
            // One message for both, which is what a live account answers.
            throw new AwsException("InvalidParameterValue",
                    "The specified resource name does not match an RDS resource in this region.", 400);
        }

        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (separator < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name:  " + resourceName, 400);
        }
        String type = resource.substring(0, separator);
        String id = resource.substring(separator + 1);

        // The record has to be the one this ARN names, not merely one of that identifier: records
        // are keyed by identifier alone, so an ARN whose region matches the caller but not the
        // stored record would otherwise be answered — and mutated — from another region's
        // resource. Reachable on the docdb credential scope, which dispatches here directly
        // rather than through the routing that matches the whole ARN.
        return switch (type) {
            case "cluster" -> {
                DocDbCluster cluster = getDbCluster(id);
                requireArnNamesRecord(resourceName, cluster.getDbClusterArn(),
                        "DBClusterNotFoundFault", "DocDB cluster " + id + " not found.");
                yield new TagTarget("cluster:" + id, cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(id, cluster);
                });
            }
            case "db" -> {
                DocDbInstance instance = getDbInstance(id);
                requireArnNamesRecord(resourceName, instance.getDbInstanceArn(),
                        "DBInstanceNotFound", "DocDB instance " + id + " not found.");
                yield new TagTarget("instance:" + id, instance.getTags(), updated -> {
                    instance.setTags(updated);
                    instances.put(id, instance);
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not yet implemented by Floci: " + resourceName, 400);
        };
    }
}
