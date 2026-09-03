package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.rds.proxy.RdsAuthProxy;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private final RedshiftProxyManager proxyManager;
    private final DockerHostResolver dockerHostResolver;
    // Proxy ports currently handed out, so allocateProxyPort never double-assigns within this JVM.
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    public RedshiftService(StorageFactory storageFactory, RedshiftContainerManager containerManager,
                            EmulatorConfig config, RegionResolver regionResolver,
                            RedshiftProxyManager proxyManager, DockerHostResolver dockerHostResolver) {
        this.clusters = storageFactory.create("redshift", "redshift-clusters.json", new TypeReference<Map<String, Cluster>>() {});
        this.snapshots = storageFactory.create("redshift", "redshift-snapshots.json", new TypeReference<Map<String, Snapshot>>() {});
        this.parameterGroups = storageFactory.create("redshift", "redshift-parameter-groups.json", new TypeReference<Map<String, ClusterParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("redshift", "redshift-subnet-groups.json", new TypeReference<Map<String, ClusterSubnetGroup>>() {});
        this.containerManager = containerManager;
        this.config = config;
        this.regionResolver = regionResolver;
        this.proxyManager = proxyManager;
        this.dockerHostResolver = dockerHostResolver;
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

                // A cluster persisted before the auth proxy existed has proxyPort == 0. Allocate one
                // now; its endpoint changes exactly once after this upgrade. Existing clusters keep
                // their stored port so the endpoint is stable across restarts.
                int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : allocateProxyPort();
                usedPorts.add(proxyPort);
                Endpoint endpoint = proxyEndpoint(proxyPort);
                cluster.setProxyPort(proxyPort);
                proxyManager.startProxy(
                        relayKey(entry.accountId(), cluster.getClusterIdentifier()), proxyPort,
                        handle.getHost(), handle.getPort(), endpoint.getAddress(),
                        cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                        passwordValidatorFor(entry.accountId(), cluster.getClusterIdentifier()));
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                cluster.setEndpoint(endpoint);
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to recover container for cluster {0}, marking as unavailable",
                        cluster.getClusterIdentifier());
                try {
                    proxyManager.stopProxy(relayKey(entry.accountId(), cluster.getClusterIdentifier()));
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop proxy during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                try {
                    containerManager.stop(entry.accountId(), cluster.getClusterIdentifier());
                } catch (Exception ex) {
                    LOG.warnv(ex, "Failed to stop container during recovery rollback for cluster {0}", cluster.getClusterIdentifier());
                }
                cluster.setClusterStatus("unavailable");
                clusters.putForAccount(entry.accountId(), entry.key(), cluster);
            }
        }
        clusters.flush();
    }

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        return createCluster(identifier, nodeType, username, password, null, List.of());
    }

    // synchronized like modify/reboot: the container + proxy + port steps must not
    // interleave with another admin call on the same cluster.
    public synchronized Cluster createCluster(String identifier, String nodeType, String username, String password,
                                  String clusterSubnetGroupName, List<String> vpcSecurityGroupIds) {
        if (clusters.get(identifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + identifier + " already exists", 400);
        }

        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setMasterPassword(password);
        cluster.setClusterSubnetGroupName(clusterSubnetGroupName);
        cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds != null ? vpcSecurityGroupIds : List.of());
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        clusters.flush();

        // Start container, then front it with an auth proxy so the advertised endpoint is
        // reachable from outside the Docker network.
        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(accountId, identifier, username, password);
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, identifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, identifier));
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);
            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(identifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), identifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", identifier); }
            if (proxyStopped) {
                clusters.delete(identifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(identifier, cluster);
            }
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

    public synchronized Cluster deleteCluster(String identifier) {
        Optional<Cluster> clusterOpt = clusters.get(identifier);
        if (clusterOpt.isEmpty()) {
            throw new AwsException("ClusterNotFound", "Cluster " + identifier + " not found", 404);
        }
        Cluster cluster = clusterOpt.get();

        // Tear down the auth proxy and return its port before stopping the container.
        // If it fails, abort deletion so the metadata remains and the user can retry.
        if (!stopProxyAndReleasePortSafely(identifier, cluster.getProxyPort())) {
            throw new AwsException("InternalFailure",
                    "Failed to stop auth proxy; cluster " + identifier + " was not deleted", 500);
        }

        containerManager.stop(clusters.accountId(), identifier);
        clusters.delete(identifier);
        clusters.flush();
        
        cluster.setClusterStatus("deleting");
        return cluster;
    }

    public synchronized Cluster modifyCluster(String clusterIdentifier, String nodeType, Integer numberOfNodes,
                                               String masterUserPassword, String clusterParameterGroupName,
                                               List<String> vpcSecurityGroupIds) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // alterUserPassword runs before any mutation of the cluster object (a live reference
        // from HybridStorage, not a copy) — if it throws, no metadata has been changed yet.
        if (masterUserPassword != null && !masterUserPassword.isBlank()) {
            containerManager.alterUserPassword(clusters.accountId(), clusterIdentifier,
                    cluster.getMasterUsername(), masterUserPassword);
            cluster.setMasterPassword(masterUserPassword);
            // Keep the proxy's password check in sync so new connections use the new secret.
            proxyManager.updateMasterPassword(
                    relayKey(clusters.accountId(), clusterIdentifier), masterUserPassword);
        }

        // NodeType only updates metadata — it does not resize the underlying Postgres container
        // (Redshift node-count has no equivalent here). NumberOfNodes is accepted for API-shape
        // compatibility but is not modelled or stored anywhere — known gap, see plan Task 9.
        if (nodeType != null && !nodeType.isBlank()) {
            cluster.setNodeType(nodeType);
        }
        if (clusterParameterGroupName != null && !clusterParameterGroupName.isBlank()) {
            cluster.setClusterParameterGroupName(clusterParameterGroupName);
        }
        if (vpcSecurityGroupIds != null && !vpcSecurityGroupIds.isEmpty()) {
            cluster.setVpcSecurityGroupIds(vpcSecurityGroupIds);
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    public synchronized Cluster rebootCluster(String clusterIdentifier) {
        Cluster cluster = clusters.get(clusterIdentifier)
                .orElseThrow(() -> new AwsException("ClusterNotFound", "Cluster " + clusterIdentifier + " not found", 404));

        // The container backing a cluster has no persistent volume (see RedshiftContainerManager),
        // so a plain stop+recreate would silently drop the cluster's data. Dump before stopping and
        // restore immediately after starting, using a throwaway temp file — no Snapshot resource is
        // created or exposed to the caller.
        Path tempDump;
        try {
            tempDump = Files.createTempFile("redshift-reboot-" + clusterIdentifier, ".sql");
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to prepare reboot dump file: " + e.getMessage(), 500);
        }

        // Hoisted so a failure after the proxy is (re)started still tears it down and
        // returns the port, matching createCluster/restoreFromClusterSnapshot rollback.
        int proxyPort = cluster.getProxyPort() > 0 ? cluster.getProxyPort() : -1;
        boolean originalTornDown = false; // original proxy + container already stopped
        boolean rebooted = false;
        try {
            String accountId = clusters.accountId();
            String key = relayKey(accountId, clusterIdentifier);

            containerManager.takeSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            proxyManager.stopProxy(key);
            containerManager.stop(accountId, clusterIdentifier);
            originalTornDown = true;

            String password = cluster.getMasterPassword() != null ? cluster.getMasterPassword() : "admin";
            RedshiftContainerHandle handle = containerManager.start(
                    accountId, clusterIdentifier, cluster.getMasterUsername(), password);

            // Reuse the stored proxy port so the advertised endpoint is unchanged by a reboot.
            if (proxyPort < 0) {
                proxyPort = allocateProxyPort();
            }
            usedPorts.add(proxyPort);
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(key, proxyPort, handle.getHost(), handle.getPort(),
                    endpoint.getAddress(), cluster.getMasterUsername(), password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier));
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            containerManager.restoreSnapshot(accountId, clusterIdentifier, cluster.getMasterUsername(), tempDump);
            cluster.setClusterStatus("available");
            rebooted = true;
        } catch (AwsException e) {
            rollbackReboot(clusterIdentifier, originalTornDown);
            if (originalTornDown) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            rollbackReboot(clusterIdentifier, originalTornDown);
            if (originalTornDown) {
                cluster.setClusterStatus("failed");
            }
            clusters.flush();
            throw new AwsException("InternalFailure", "Failed to reboot cluster " + clusterIdentifier + ": " + e.getMessage(), 500);
        } finally {
            if (rebooted) {
                try {
                    Files.deleteIfExists(tempDump);
                } catch (IOException ex) {
                    LOG.warnv(ex, "Failed to clean up temporary dump file {0} after rebooting cluster {1}", tempDump, clusterIdentifier);
                }
            } else {
                if (originalTornDown) {
                    // Once the original container is torn down it holds no volume, so this dump can
                    // be the only surviving copy of the cluster's data — keep it for manual recovery.
                    LOG.warnv("Reboot of cluster {0} did not complete; retained pre-reboot data dump at {1}",
                            clusterIdentifier, tempDump);
                } else {
                    try {
                        Files.deleteIfExists(tempDump);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        clusters.put(clusterIdentifier, cluster);
        clusters.flush();
        return cluster;
    }

    // ── Snapshot Operations ──────────────────────────────────────────────────

    // AWS constrains a snapshot identifier to 1-255 characters, first a letter, then
    // letters, digits or single hyphens (no trailing or doubled hyphen). Enforcing that
    // here also keeps the value safe to splice into the dump file path below: '.', '/'
    // and '\' can never appear, so it cannot escape the account's dump directory.
    private static void validateSnapshotIdentifier(String id) {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9-]{0,254}")
                || id.contains("--") || id.endsWith("-")) {
            throw new AwsException("InvalidParameterValue",
                    "SnapshotIdentifier must be 1-255 characters, start with a letter, and contain "
                    + "only letters, digits and non-consecutive hyphens", 400);
        }
    }

    /** Absolute, normalised {@code <persistentPath>/redshift-dumps/<accountId>} for this request's account. */
    private Path accountDumpDir() {
        return Paths.get(config.storage().persistentPath())
                .resolve("redshift-dumps")
                .resolve(clusters.accountId())
                .toAbsolutePath()
                .normalize();
    }

    /**
     * A stored {@code sqlDump} path is trusted only if it still resolves inside this account's
     * dump directory. Guards restore/delete against a path persisted by an older, unvalidated
     * createSnapshot (or by a different account).
     */
    private boolean isTrustedDumpPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        Path dir = accountDumpDir();
        return Paths.get(storedPath).toAbsolutePath().normalize().startsWith(dir);
    }

    public Snapshot createSnapshot(String snapshotIdentifier, String clusterIdentifier) {
        validateSnapshotIdentifier(snapshotIdentifier);
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

        Path dumpDir = accountDumpDir();
        // Defence in depth: validateSnapshotIdentifier already rejects path separators,
        // but keep the containment check so the dump can never land outside the account dir.
        Path dumpFile = dumpDir.resolve(snapshotIdentifier + ".sql").normalize();
        if (!dumpFile.startsWith(dumpDir)) {
            throw new AwsException("InvalidParameterValue", "Invalid snapshot identifier", 400);
        }
        try {
            Files.createDirectories(dumpDir);
            containerManager.takeSnapshot(clusters.accountId(), clusterIdentifier, cluster.getMasterUsername(), dumpFile);
            snapshot.setSqlDump(dumpFile.toString());
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
        if (isTrustedDumpPath(snapshot.getSqlDump())) {
            try {
                Files.deleteIfExists(Paths.get(snapshot.getSqlDump()));
            } catch (IOException e) {
                // ignore
            }
        }
        snapshot.setStatus("deleted");
        return snapshot;
    }

    public Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier) {
        return restoreFromClusterSnapshot(clusterIdentifier, snapshotIdentifier, null);
    }

    public synchronized Cluster restoreFromClusterSnapshot(String clusterIdentifier, String snapshotIdentifier, String nodeType) {
        if (clusters.get(clusterIdentifier).isPresent()) {
            throw new AwsException("ClusterAlreadyExists", "Cluster " + clusterIdentifier + " already exists", 400);
        }

        Optional<Snapshot> snapshotOpt = snapshots.get(snapshotIdentifier);
        if (snapshotOpt.isEmpty()) {
            throw new AwsException("ClusterSnapshotNotFound", "Snapshot " + snapshotIdentifier + " not found", 404);
        }

        Snapshot snapshot = snapshotOpt.get();

        // Validate the stored dump location up front, before any provisioning. A snapshot whose
        // sqlDump was written by pre-validation code could point outside this account's dump dir;
        // rejecting it here (rather than mid-restore) avoids leaving a half-created cluster record
        // and an orphaned container behind.
        String sqlDump = snapshot.getSqlDump();
        boolean hasDump = sqlDump != null && !sqlDump.isBlank();
        if (hasDump && !isTrustedDumpPath(sqlDump)) {
            throw new AwsException("InvalidParameterValue",
                    "Snapshot " + snapshotIdentifier + " has an unusable dump location", 400);
        }

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

        // Hoisted out of the try so a failure after allocateProxyPort() still returns the port.
        int proxyPort = -1;
        try {
            String accountId = clusters.accountId();
            RedshiftContainerHandle handle = containerManager.start(accountId, clusterIdentifier, username, password);
            proxyPort = allocateProxyPort();
            Endpoint endpoint = proxyEndpoint(proxyPort);
            cluster.setProxyPort(proxyPort);
            proxyManager.startProxy(relayKey(accountId, clusterIdentifier), proxyPort,
                    handle.getHost(), handle.getPort(), endpoint.getAddress(),
                    username, password, CLUSTER_DB_NAME,
                    passwordValidatorFor(accountId, clusterIdentifier));
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());
            cluster.setEndpoint(endpoint);

            if (hasDump) {
                containerManager.restoreSnapshot(clusters.accountId(), clusterIdentifier, username, Paths.get(sqlDump));
            }

            cluster.setClusterStatus("available");
        } catch (AwsException e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(clusterIdentifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), clusterIdentifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", clusterIdentifier); }
            if (proxyStopped) {
                clusters.delete(clusterIdentifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(clusterIdentifier, cluster);
            }
            clusters.flush();
            throw e;
        } catch (Exception e) {
            boolean proxyStopped = stopProxyAndReleasePortSafely(clusterIdentifier, proxyPort);
            try { containerManager.stop(clusters.accountId(), clusterIdentifier); } catch (Exception ex) { LOG.warnv(ex, "Failed to stop container during rollback of cluster {0}", clusterIdentifier); }
            if (proxyStopped) {
                clusters.delete(clusterIdentifier);
            } else {
                cluster.setClusterStatus("failed");
                clusters.put(clusterIdentifier, cluster);
            }
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
                    .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFound", "Cluster subnet group " + name + " not found", 404));
            return List.of(group);
        }
        return subnetGroups.scan(k -> true);
    }

    public synchronized ClusterSubnetGroup modifyClusterSubnetGroup(String name, String description, List<String> subnetIds) {
        ClusterSubnetGroup group = subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFound", "Cluster subnet group " + name + " not found", 404));
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
                .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFound", "Cluster subnet group " + name + " not found", 404));
        subnetGroups.delete(name);
        subnetGroups.flush();
        return group;
    }

    // ── Tagging Operations ───────────────────────────────────────────────────

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, Consumer<Map<String, String>> save) {}

    public record TaggedResource(String resourceName, String resourceType, String tagKey, String tagValue) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public synchronized void createTags(String resourceName, Map<String, String> tags) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        updated.putAll(tags);
        handle.save().accept(updated);
    }

    public synchronized void deleteTags(String resourceName, Collection<String> tagKeys) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        tagKeys.forEach(updated::remove);
        handle.save().accept(updated);
    }

    public List<TaggedResource> describeTags(String resourceName, String resourceType, List<String> tagKeysFilter) {
        List<TaggedResource> result = new ArrayList<>();
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
                        .orElseThrow(() -> new AwsException("ClusterSubnetGroupNotFound", "Cluster subnet group " + id + " not found", 404));
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

    // ── Proxy Helpers (shared with modify/reboot/restore) ────────────────────

    private static final String CLUSTER_DB_NAME = "dev";

    private int allocateProxyPort() {
        int base = config.services().redshift().proxyBasePort();
        int max = config.services().redshift().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacity",
                "No available Redshift proxy ports in range " + base + "-" + max, 503);
    }

    private boolean stopProxyAndReleasePortSafely(String identifier, int proxyPort) {
        boolean proxyStopped = false;
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
            proxyStopped = true;
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy for cluster {0}; leaking proxy port {1} to prevent reallocation", identifier, proxyPort);
        }
        if (proxyStopped) {
            releaseProxyPort(proxyPort);
        }
        return proxyStopped;
    }

    /**
     * Undo a failed reboot. If {@code originalTornDown} is false the reboot failed before
     * the original proxy + container were stopped, so the original data-bearing container
     * is still running and nothing must be touched. Once it is true the original is gone:
     * tear down the (replacement's) proxy and return its port, and remove any container
     * running under the cluster's name — {@code containerManager.stop} works by name, so
     * this also cleans a replacement that {@code containerManager.start} created before
     * throwing (e.g. its readiness check timed out). The pre-reboot data dump is kept by
     * the caller.
     */
    private void rollbackReboot(String identifier, boolean originalTornDown) {
        if (!originalTornDown) {
            return;
        }
        try {
            proxyManager.stopProxy(relayKey(clusters.accountId(), identifier));
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop proxy during reboot rollback for cluster {0}", identifier);
        }
        try {
            containerManager.stop(clusters.accountId(), identifier);
        } catch (Exception ex) {
            LOG.warnv(ex, "Failed to stop replacement container during rollback of reboot for cluster {0}", identifier);
        }
    }

    private void releaseProxyPort(int port) {
        if (port > 0) {
            usedPorts.remove(port);
        }
    }

    private Endpoint proxyEndpoint(int proxyPort) {
        String host = config.services().redshift().endpointHost()
                .filter(h -> !h.isBlank())
                .orElseGet(dockerHostResolver::resolve);
        return new Endpoint(host, proxyPort);
    }

    private String relayKey(String accountId, String clusterIdentifier) {
        return accountId + ":" + clusterIdentifier;
    }

    // Validates the master password at the proxy against current cluster state, so a
    // ModifyCluster password change is reflected for new connections without a proxy restart.
    private RdsAuthProxy.PasswordValidator passwordValidatorFor(String accountId, String clusterIdentifier) {
        return (user, password) -> clusters.getForAccount(accountId, clusterIdentifier)
                .map(c -> user.equals(c.getMasterUsername()) && password.equals(c.getMasterPassword()))
                .orElse(false);
    }
}
