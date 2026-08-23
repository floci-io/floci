package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class RedshiftService {
    private static final Logger LOG = Logger.getLogger(RedshiftService.class);

    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<Snapshot> snapshots;
    private final AccountAwareStorageBackend<ClusterParameterGroup> parameterGroups;
    private final AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroups;
    private final RedshiftContainerManager containerManager;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                            EmulatorConfig config, RegionResolver regionResolver) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json", new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("redshift", "redshift-subnet-groups.json", new TypeReference<Map<String, ClusterSubnetGroup>>() {});
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    // Recreate Docker containers for persisted clusters on app restart (across every account, not just default)
    void onStart(@Observes StartupEvent event) {
        List<AccountAwareStorageBackend.AccountEntry<Cluster>> availableClusters =
                clusters.scanAllAccountEntries(k -> true).stream()
                        .filter(entry -> "available".equals(entry.value().getClusterStatus()))
                        .toList();
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : availableClusters) {
            Cluster cluster = entry.value();
            if (containerManager.getContainer(entry.accountId(), cluster.getClusterIdentifier()).isPresent()) {
                continue;
            }
            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            try {
                LOG.infov("Recovering container for persisted cluster: {0}", cluster.getClusterIdentifier());
                RedshiftContainerHandle handle = containerManager.adoptOrStart(
                        entry.accountId(), cluster.getClusterIdentifier(), cluster.getMasterUsername(), password);
                Endpoint endpoint = new Endpoint();
                endpoint.setAddress(handle.getHost());
                endpoint.setPort(handle.getPort());
                cluster.setEndpoint(endpoint);
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to recover container for cluster {0}, marking as unavailable",
                        cluster.getClusterIdentifier());
                cluster.setClusterStatus("unavailable");
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            }
        }
        clusters.flush();
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container
        try {
            RedshiftContainerHandle handle = containerManager.start(clusters.accountId(), identifier, username, password);
            Endpoint endpoint = new Endpoint();
            endpoint.setAddress(handle.getHost());
            endpoint.setPort(handle.getPort());
            cluster.setEndpoint(endpoint);
            cluster.setClusterStatus("available");
        } catch (Exception e) {
            cluster.setClusterStatus("failed");
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to start container: " + e.getMessage(), 500);
        }
        
        clusters.put(identifier, cluster);
        clusters.flush();
        return cluster;
    }

    public List<Cluster> describeClusters(String identifier) {
        if (identifier != null) {
            Optional<Cluster> cluster = clusters.get(identifier);
            if (cluster.isEmpty()) {
                throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
            }
            return List.of(cluster.get());
        }
        return clusters.scan(k -> true);
    }

    public Cluster deleteCluster(String identifier) {
        Optional<Cluster> clusterOpt = clusters.get(identifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
        }
        Cluster cluster = clusterOpt.get();
        
        containerManager.stop(clusters.accountId(), identifier);
        clusters.delete(identifier);
        clusters.flush();
        
        cluster.setClusterStatus("deleting");
        return cluster;
    }

    // ── Snapshot Operations ──────────────────────────────────────────────────

    public Snapshot createSnapshot(String snapshotIdentifier, String clusterIdentifier) {
        Optional<Cluster> clusterOpt = clusters.get(clusterIdentifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404);
        }
        if (snapshots.get(snapshotIdentifier).isPresent()) {
            throw new AwsException("ClusterSnapshotAlreadyExists", "Snapshot " + snapshotIdentifier + " already exists", 400);
        }

        Cluster cluster = clusterOpt.get();
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier(snapshotIdentifier);
        snapshot.setClusterIdentifier(clusterIdentifier);
        snapshot.setStatus("available");
        snapshot.setMasterUsername(cluster.getMasterUsername());
        snapshot.setMasterPassword(cluster.getMasterPassword());
        if (cluster.getEndpoint() != null) {
            snapshot.setPort(cluster.getEndpoint().getPort());
        } else {
            snapshot.setPort(5439);
        }

        java.nio.file.Path dumpDir = java.nio.file.Paths.get(config.storage().persistentPath())
                .resolve("redshift-dumps")
                .resolve(clusters.accountId());
        try {
            java.nio.file.Files.createDirectories(dumpDir);
            java.nio.file.Path dumpFile = dumpDir.resolve(snapshotIdentifier + ".sql");
            containerManager.takeSnapshot(clusters.accountId(), clusterIdentifier, cluster.getMasterUsername(), dumpFile);
            snapshot.setSqlDump(dumpFile.toAbsolutePath().toString());
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to take snapshot for cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        }

        snapshots.put(snapshotIdentifier, snapshot);
        snapshots.flush();
        return snapshot;
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier, String clusterIdentifier) {
        if (snapshotIdentifier != null && !snapshotIdentifier.isBlank()) {
            Optional<Snapshot> snapshot = snapshots.get(snapshotIdentifier);
            if (snapshot.isEmpty()) {
                throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
            }
            return List.of(snapshot.get());
        }
        if (clusterIdentifier != null && !clusterIdentifier.isBlank()) {
            return snapshots.scan(k -> true).stream()
                    .filter(s -> clusterIdentifier.equals(s.getClusterIdentifier()))
                    .toList();
        }
        return snapshots.scan(k -> true);
    }

    public List<Snapshot> describeSnapshots(String snapshotIdentifier) {
        return describeSnapshots(snapshotIdentifier, null);
    }

    public Optional<Snapshot> getSnapshot(String snapshotIdentifier) {
        return snapshots.get(snapshotIdentifier);
    }

    public Snapshot deleteSnapshot(String snapshotIdentifier) {
        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }
        Snapshot snapshot = snapshotOpt.get();
        snapshots.delete(snapshotIdentifier);
        snapshots.flush();
        if (snapshot.getSqlDump() != null) {
            // sqlDump is the absolute path that was stored when the snapshot was created
            java.nio.file.Path dumpFile = java.nio.file.Paths.get(snapshot.getSqlDump());
            try {
                java.nio.file.Files.deleteIfExists(dumpFile);
            } catch (java.io.IOException e) {
                // ignore
            }
        }
        snapshot.setStatus("deleted");
        return snapshot;
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier) {
        return restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, null);
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType) {
        if (clusters.get(clusterIdentifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + clusterIdentifier + " already exists", 400);
        }

        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }

        Snapshot snapshot = snapshotOpt.get();
        String effectiveNodeType = (nodeType != null && !nodeType.isBlank()) ? nodeType : "dc2.large";
        String username = snapshot.getMasterUsername() != null ? snapshot.getMasterUsername() : "admin";
        String sourceCluster = snapshot.getClusterIdentifier();
        String password = Optional.ofNullable(snapshot.getMasterPassword())
                .filter(p -> !p.isBlank())
                .or(() -> clusters.get(sourceCluster).map(Cluster::getMasterPassword))
                .filter(p -> p != null && !p.isBlank())
                .orElse("admin");

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterIdentifier);
        cluster.setNodeType(effectiveNodeType);
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterStatus("creating");
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();

        try {
            RedshiftContainerHandle handle = containerManager.start(clusters.accountId(), clusterIdentifier, username, password);
            Endpoint endpoint = new Endpoint();
            endpoint.setAddress(handle.getHost());
            endpoint.setPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            if (snapshot.getSqlDump() != null && !snapshot.getSqlDump().isBlank()) {
                // sqlDump is the absolute path that was stored when the snapshot was created
                java.nio.file.Path dumpFile = java.nio.file.Paths.get(snapshot.getSqlDump());
                containerManager.restoreSnapshot(clusters.accountId(), clusterIdentifier, username, dumpFile);
            }

            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            cluster.setClusterStatus("failed");
            clusters.flush();
            throw e;
        } catch (Exception e) {
            cluster.setClusterStatus("failed");
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to restore cluster from snapshot: " + e.getMessage(), 500);
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    // ── Parameter Group Operations ───────────────────────────────────────────

    public ClusterParameterGroup createClusterParameterGroup(String parameterGroupName, String parameterGroupFamily, String description) {
        if (parameterGroups.get(parameterGroupName).isPresent()) {
            throw new AwsException("ClusterParameterGroupAlreadyExists", "Cluster parameter group " + parameterGroupName + " already exists", 400);
        }

        ClusterParameterGroup group = new ClusterParameterGroup(parameterGroupName, parameterGroupFamily, description);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        return group;
    }

    public List<ClusterParameterGroup> describeClusterParameterGroups(String parameterGroupName) {
        if (parameterGroupName != null && !parameterGroupName.isBlank()) {
            Optional<ClusterParameterGroup> group = parameterGroups.get(parameterGroupName);
            if (group.isEmpty()) {
                throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
            }
            return List.of(group.get());
        }
        return parameterGroups.scan(k -> true);
    }

    public Optional<ClusterParameterGroup> getClusterParameterGroup(String parameterGroupName) {
        return parameterGroups.get(parameterGroupName);
    }

    public List<Parameter> describeClusterParameters(String parameterGroupName) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));
        return group.getParameters();
    }

    public synchronized ClusterParameterGroup modifyClusterParameterGroup(
            String parameterGroupName, List<Parameter> updates) {
        ClusterParameterGroup group = parameterGroups.get(parameterGroupName)
                .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound",
                        "Cluster parameter group " + parameterGroupName + " not found", 404));

        List<Parameter> current = new ArrayList<>(group.getParameters());
        for (Parameter update : updates) {
            boolean matched = false;
            for (int i = 0; i < current.size(); i++) {
                Parameter existing = current.get(i);
                if (existing.getParameterName().equals(update.getParameterName())) {
                    // Preserve metadata (description, dataType) from existing parameter if not provided in update
                    existing.setParameterValue(update.getParameterValue());
                    if (update.getDescription() != null) {
                        existing.setDescription(update.getDescription());
                    }
                    if (update.getDataType() != null) {
                        existing.setDataType(update.getDataType());
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                current.add(update);
            }
        }
        group.setParameters(current);
        parameterGroups.put(parameterGroupName, group);
        parameterGroups.flush();
        return group;
    }

    public ClusterParameterGroup deleteClusterParameterGroup(String parameterGroupName) {
        Optional<ClusterParameterGroup> groupOpt = parameterGroups.get(parameterGroupName);
        if (groupOpt.isEmpty()) {
            throw new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + parameterGroupName + " not found", 404);
        }
        ClusterParameterGroup group = groupOpt.get();
        parameterGroups.delete(parameterGroupName);
        parameterGroups.flush();
        return group;
    }

    // ── Cluster Subnet Group Operations ──────────────────────────────────────

    public ClusterSubnetGroup createClusterSubnetGroup(String name, String description, String vpcId, List<String> subnetIds) {
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("ClusterSubnetGroupAlreadyExists", "Cluster subnet group " + name + " already exists", 400);
        }
        ClusterSubnetGroup group = new ClusterSubnetGroup(name, description, vpcId, subnetIds);
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    public List<ClusterSubnetGroup> describeClusterSubnetGroups(String name) {
        if (name != null && !name.isBlank()) {
            ClusterSubnetGroup group = subnetGroups.get(name)
                    .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
            return List.of(group);
        }
        return subnetGroups.scan(k -> true);
    }

    public synchronized ClusterSubnetGroup modifyClusterSubnetGroup(String name, String description, List<String> subnetIds) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
        if (description != null) {
            group.setDescription(description);
        }
        if (subnetIds != null && !subnetIds.isEmpty()) {
            group.setSubnetIds(subnetIds);
        }
        subnetGroups.put(name, group);
        subnetGroups.flush();
        return group;
    }

    public ClusterSubnetGroup deleteClusterSubnetGroup(String name) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + name + " not found", 404));
        subnetGroups.delete(name);
        subnetGroups.flush();
        return group;
    }

    // ── Tagging Operations ───────────────────────────────────────────────────

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, java.util.function.Consumer<Map<String, String>> save) {}

    public record TaggedResource(String resourceName, String resourceType, String tagKey, String tagValue) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public synchronized void createTags(String resourceName, Map<String, String> tags) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new java.util.LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public synchronized void deleteTags(String resourceName, java.util.Collection<String> tagKeys) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new java.util.LinkedHashMap<>(handle.tags());
        tagKeys.forEach(updated::remove);
        handle.save().accept(updated);
    }

    public List<TaggedResource> describeTags(String resourceName, String resourceType, List<String> tagKeysFilter) {
        List<TaggedResource> result = new java.util.ArrayList<>();
        if (resourceName != null && !resourceName.isBlank()) {
            TagHandle handle = resolveTagHandle(resourceName);
            String type = arnResourceType(resourceName);
            addTaggedResources(result, resourceName, type, handle.tags(), tagKeysFilter);
            return result;
        }
        if (resourceType == null || "cluster".equalsIgnoreCase(resourceType)) {
            for (Cluster c : clusters.scan(k -> true)) {
                addTaggedResources(result, clusterArn(c.getClusterIdentifier()), "cluster", c.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "snapshot".equalsIgnoreCase(resourceType)) {
            for (Snapshot s : snapshots.scan(k -> true)) {
                addTaggedResources(result, snapshotArn(s.getClusterIdentifier(), s.getSnapshotIdentifier()),
                        "snapshot", s.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "parametergroup".equalsIgnoreCase(resourceType)) {
            for (ClusterParameterGroup g : parameterGroups.scan(k -> true)) {
                addTaggedResources(result, parameterGroupArn(g.getParameterGroupName()),
                        "parametergroup", g.getTags(), tagKeysFilter);
            }
        }
        if (resourceType == null || "subnetgroup".equalsIgnoreCase(resourceType)) {
            for (ClusterSubnetGroup g : subnetGroups.scan(k -> true)) {
                addTaggedResources(result, subnetGroupArn(g.getClusterSubnetGroupName()),
                        "subnetgroup", g.getTags(), tagKeysFilter);
            }
        }
        return result;
    }

    private void addTaggedResources(List<TaggedResource> out, String arn, String type,
                                     Map<String, String> tags, List<String> tagKeysFilter) {
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (tagKeysFilter != null && !tagKeysFilter.isEmpty() && !tagKeysFilter.contains(e.getKey())) {
                continue;
            }
            out.add(new TaggedResource(arn, type, e.getKey(), e.getValue()));
        }
    }

    private String arnResourceType(String resourceName) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceName);
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        return resource.substring(0, sep);
    }

    private String clusterArn(String clusterIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "cluster:" + clusterIdentifier);
    }

    private String snapshotArn(String clusterIdentifier, String snapshotIdentifier) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(),
                "snapshot:" + clusterIdentifier + "/" + snapshotIdentifier);
    }

    private String parameterGroupArn(String parameterGroupName) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "parametergroup:" + parameterGroupName);
    }

    private String subnetGroupArn(String name) {
        return regionResolver.buildArn("redshift", regionResolver.getRegion(), "subnetgroup:" + name);
    }

    /**
     * Resolves a tagging ResourceName to its backing resource.
     *
     * Redshift ARNs have the shape {@code arn:aws:redshift:<region>:<account>:<type>:<id>},
     * where {@code <type>} is one of {@code cluster}, {@code snapshot} (id shape
     * {@code <clusterId>/<snapshotId>}), or {@code parametergroup}. Unlike RDS's tag
     * resolution, there is no bare-name fallback — Redshift tagging is new, so there is no
     * existing caller to stay backward compatible with.
     */
    private TagHandle resolveTagHandle(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        if (!resourceName.startsWith("arn:")) {
            throw new AwsException("InvalidParameterValue", "ResourceName must be a Redshift ARN: " + resourceName, 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        if (!"redshift".equals(arn.service())) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String resource = arn.resource();
        int sep = resource.indexOf(':');
        if (sep < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String type = resource.substring(0, sep);
        String id = resource.substring(sep + 1);

        return switch (type) {
            case "cluster" -> {
                Cluster cluster = clusters.get(id)
                        .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + id + " not found", 404));
                yield new TagHandle(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    clusters.put(id, cluster);
                    clusters.flush();
                });
            }
            case "snapshot" -> {
                String snapshotId = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
                Snapshot snapshot = snapshots.get(snapshotId)
                        .orElseThrow(() -> new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotId + " not found", 404));
                yield new TagHandle(snapshot.getTags(), updated -> {
                    snapshot.setTags(updated);
                    snapshots.put(snapshotId, snapshot);
                    snapshots.flush();
                });
            }
            case "parametergroup" -> {
                ClusterParameterGroup group = parameterGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterParameterGroupNotFound", "Cluster parameter group " + id + " not found", 404));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    parameterGroups.put(id, group);
                    parameterGroups.flush();
                });
            }
            case "subnetgroup" -> {
                ClusterSubnetGroup group = subnetGroups.get(id)
                        .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFoundFault", "Cluster subnet group " + id + " not found", 404));
                yield new TagHandle(group.getTags(), updated -> {
                    group.setTags(updated);
                    subnetGroups.put(id, group);
                    subnetGroups.flush();
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported: " + resourceName, 400);
        };
    }
}
