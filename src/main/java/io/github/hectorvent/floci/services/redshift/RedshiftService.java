package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class RedshiftService {
    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<Snapshot> snapshots;
    private final AccountAwareStorageBackend<String> snapshotDumps;
    private final AccountAwareStorageBackend<ClusterParameterGroup> parameterGroups;
    private final RedshiftContainerManager containerManager;

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() {});
        this.snapshotDumps = storageFactory.create("redshift", "redshift-snapshot-dumps.json", new TypeReference<Map<String, String>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json", new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.containerManager = containerManager;
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container
        try {
            RedshiftContainerHandle handle = containerManager.start(identifier, username, password);
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
        
        containerManager.stop(identifier);
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
        if (cluster.getEndpoint() != null) {
            snapshot.setPort(cluster.getEndpoint().getPort());
        } else {
            snapshot.setPort(5439);
        }

        try {
            String sqlDump = containerManager.takeSnapshot(clusterIdentifier, cluster.getMasterUsername(), "dev");
            snapshot.setSqlDump(sqlDump);
            if (sqlDump != null) {
                snapshotDumps.put(snapshotIdentifier, sqlDump);
                snapshotDumps.flush();
            }
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
        snapshotDumps.delete(snapshotIdentifier);
        snapshotDumps.flush();
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
        String password = "password123";

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterIdentifier);
        cluster.setNodeType(effectiveNodeType);
        cluster.setMasterUsername(username);
        cluster.setClusterStatus("creating");
        clusters.put(clusterIdentifier, cluster);
        clusters.flush();

        try {
            RedshiftContainerHandle handle = containerManager.start(clusterIdentifier, username, password);
            Endpoint endpoint = new Endpoint();
            endpoint.setAddress(handle.getHost());
            endpoint.setPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            String sqlDump = snapshotDumps.get(snapshotIdentifier).orElse(snapshot.getSqlDump());
            if (sqlDump != null && !sqlDump.isBlank()) {
                containerManager.restoreSnapshot(clusterIdentifier, username, "dev", sqlDump);
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
}
