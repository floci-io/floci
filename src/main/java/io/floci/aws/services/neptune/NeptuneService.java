package io.floci.aws.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.neptune.model.NeptuneClusterSettings;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.services.neptune.model.NeptuneInstance;
import io.floci.aws.services.neptune.model.NeptuneInstanceSettings;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    /** The proxy ports handed out, each to the identity of the cluster that holds it. */
    private final ConcurrentHashMap<Integer, String> portOwners = new ConcurrentHashMap<>();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();
    /**
     * One monitor per cluster and per instance, by identity: a cluster's is taken by its delete, its instances'
     * creation and deletion and its restore, and an instance's by its creation, its deletion and the restore's
     * status writes, always before its cluster's.
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    /** The identities of the clusters whose containers the startup restore is bringing back. */
    private final Set<String> restoringClusters = ConcurrentHashMap.newKeySet();

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
        String identity = resourceIdentity(currentAccount(), currentRegion(), id);
        if (!provisioningClusterNames.add(identity)) {
            throw clusterAlreadyExists(id);
        }
        try {
            return provisionDbCluster(id, engineVersion, iamEnabled, settings, tags);
        } finally {
            provisioningClusterNames.remove(identity);
        }
    }

    private static AwsException clusterAlreadyExists(String id) {
        return new AwsException("DBClusterAlreadyExistsFault",
                "Neptune cluster " + id + " already exists.", 400);
    }

    private NeptuneCluster provisionDbCluster(String id, String engineVersion, boolean iamEnabled,
                                             NeptuneClusterSettings settings, Map<String, String> tags) {
        if (getStoredCluster(id).isPresent()) {
            throw clusterAlreadyExists(id);
        }

        // Open the try immediately after reserving the port so config reads below can't leak it.
        int proxyPort = allocateProxyPort(settings.port(), resourceIdentity(currentAccount(), currentRegion(), id));
        NeptuneContainerHandle handle = null;
        String volumeId = null;
        boolean provisioned = false;
        try {
            volumeId = keepsClusters() ? UUID.randomUUID().toString() : null;
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
            String region = currentRegion();
            String accountId = currentAccount();
            String identity = resourceIdentity(accountId, region, id);
            // graph database needs the container.
            handle = containerManager.tryStart(id, image, dbType, accountId, region, volumeId);
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
            cluster.setVolumeId(volumeId);
            cluster.setDbType(dbType);
            settings.applyTo(cluster);
            if (tags != null && !tags.isEmpty()) {
                cluster.setTags(tags);
            }

            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                proxyManager.startProxy(identity, proxyPort, handle.getHost(), handle.getPort());
            } else {
                LOG.warnv("Neptune cluster {0} created without a backing graph database container: "
                        + "no Docker daemon is reachable. Metadata operations work; connections to "
                        + "the cluster do not until a daemon appears.", id);
            }

            putCluster(cluster);
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
                rollbackDbCluster(resourceIdentity(currentAccount(), currentRegion(), id), handle, proxyPort,
                        volumeId);
            }
        }
    }

    private void rollbackDbCluster(String identity, NeptuneContainerHandle handle, int proxyPort, String volumeId) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(identity);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for Neptune cluster {0}: {1}", identity, e.getMessage());
            }
            try {
                // Stop by id, not handle: a readiness timeout in containerManager.start() throws
                // after the container was created and registered but before the handle is returned,
                // so cleaning up by handle here would miss (and orphan) it. stopByClusterId is
                // idempotent, so it's safe when the container never started.
                containerManager.stopByClusterId(identity);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for Neptune cluster {0}: {1}", identity, e.getMessage());
            }
            // The cluster never reached the caller, so the volume made for it goes too.
            containerManager.discardStorage(volumeId);
        } finally {
            // Always release the port — even if a cleanup step throws a non-RuntimeException
            // (e.g. an Error) — since leaking the port is the exact failure this rollback prevents.
            releaseProxyPort(proxyPort, identity);
        }
    }

    public NeptuneCluster getDbCluster(String id) {
        return getStoredCluster(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));
    }

    public boolean hasCluster(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return getStoredCluster(id).isPresent();
    }

    public boolean hasInstance(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return getStoredInstance(id).isPresent();
    }

    public boolean hasResourceWithArn(String arn) {
        if (!AwsArnUtils.looksLikeArn(arn)) {
            return false;
        }
        return scanClusters().stream()
                        .anyMatch(c -> arn.equalsIgnoreCase(c.getDbClusterArn()))
                || scanInstances().stream()
                        .anyMatch(i -> arn.equalsIgnoreCase(i.getDbInstanceArn()));
    }

    public Collection<NeptuneCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-cluster-id filter accepts ARNs as well as identifiers. Match the
            // full ARN against each cluster's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local cluster.
            if (AwsArnUtils.looksLikeArn(filterId)) {
                return scanClusters().stream()
                        .filter(c -> filterId.equalsIgnoreCase(c.getDbClusterArn()))
                        .toList();
            }
            return scanClusters().stream().filter(c -> filterId.equalsIgnoreCase(c.getDbClusterIdentifier())).toList();
        }
        return scanClusters();
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
        putCluster(cluster);
        LOG.infov("Neptune cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        String identity = resourceIdentity(currentAccount(), currentRegion(), id);
        synchronized (clusterLock(identity)) {
            NeptuneCluster cluster = getStoredCluster(id).orElseThrow(() ->
                    new AwsException("DBClusterNotFoundFault",
                            "Neptune cluster " + id + " not found.", 404));

            if (cluster.isDeletionProtection()) {
                throw new AwsException("InvalidParameterCombination",
                        "Cannot delete protected Cluster, please disable deletion protection and try again.", 400);
            }
            if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
                throw new AwsException("InvalidDBClusterStateFault",
                        "Cannot delete Neptune cluster " + id + ": it still has DB instances.", 400);
            }
            if (restoringClusters.contains(identity)) {
                // Its container is starting again after a restart: the restore removes the cluster once it has.
                cluster.setStatus("deleting");
                putCluster(cluster);
                LOG.infov("Neptune cluster {0} is deleted once its container has started", id);
                return;
            }

            removeCluster(cluster);
        }
    }

    /**
     * Stops a cluster's proxy and container and removes its record and port, and its volume as the storage policy
     * says; the caller holds the cluster's lock.
     */
    private void removeCluster(NeptuneCluster cluster) {
        cluster.setStatus("deleting");
        putCluster(cluster);

        String identity = resourceIdentity(cluster);
        proxyManager.stopProxy(identity);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new NeptuneContainerHandle(
                    cluster.getContainerId(), identity,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        containerManager.removeStorage(cluster.getVolumeId());
        releaseProxyPort(cluster.getProxyPort(), identity);
        deleteCluster(cluster);
        LOG.infov("Neptune cluster {0} deleted", cluster.getDbClusterIdentifier());
    }

    // ── Restart ───────────────────────────────────────────────────────────────

    /**
     * Brings the stored clusters' graph database containers back after Floci restarts. Shutdown removes them and
     * their proxies and keeps their volumes, so a cluster read back from storage reported itself available with
     * nothing behind its port. Each cluster reports {@code maintenance}, and its instances {@code starting}, at once,
     * with its proxy port held for it, and its container comes back in the background on its volume, in the engine
     * its graph was kept in, so Floci does not wait for Docker or an image pull. Its proxy then listens on its port
     * again, and the cluster and its instances report {@code available}. With no Docker daemon reachable they report
     * {@code available} with no container, as a cluster created then does.
     *
     * <p>A cluster whose port another cluster already holds gets a new one. A cluster recorded before its graph was
     * kept in a volume gets a volume now, and one recorded before its engine was kept is taken to be in the
     * configured one. A cluster whose container or proxy does not start is left with none and its instances
     * {@code failed}, and is tried again at the next start; one still {@code deleting} at startup is removed then. A
     * cluster deleted during its restore is {@code deleting} until its container has started, and goes then. Every
     * cluster is tried, and the ones that could not be restored fail the returned future together. A shutdown during
     * the restore leaves the clusters not started yet as they are, for the next start.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        if (!(clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware)) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, RestoringCluster> stored = new LinkedHashMap<>();
        for (AccountEntry<NeptuneCluster> entry : aware.scanAllAccountEntries(storedKey -> true)) {
            NeptuneCluster cluster = entry.value();
            String account = arnAccount(cluster.getDbClusterArn());
            String region = arnRegion(cluster.getDbClusterArn());
            if (account.isEmpty() || region.isEmpty()) {
                account = entry.accountId();
                region = regionResolver.getDefaultRegion();
            }
            String id = cluster.getDbClusterIdentifier();
            stored.putIfAbsent(resourceIdentity(account, region, id),
                    new RestoringCluster(account, region, id, null, false));
        }
        List<RestoringCluster> pending = new ArrayList<>();
        List<RuntimeException> prepareFailures = new ArrayList<>();
        // Marking calls no Docker, so it runs before startup goes on; the Docker work is all in the background.
        for (RestoringCluster candidate : stored.values()) {
            try {
                RestoringCluster restoring = RequestScopes.callAs(candidate.accountId(), candidate.region(),
                        () -> prepareRestore(candidate));
                if (restoring != null) {
                    pending.add(restoring);
                }
            } catch (RuntimeException e) {
                prepareFailures.add(restoreFailure(candidate, e));
            }
        }
        return CompletableFuture.runAsync(() -> {
            List<RuntimeException> failures = new ArrayList<>(prepareFailures);
            for (RestoringCluster restoring : pending) {
                if (containerManager.isShuttingDown()) {
                    // Clusters not reached yet stay as they are, which the next start restores; a delete meanwhile
                    // removes one at once, as no container of it is starting.
                    pending.forEach(left -> restoringClusters.remove(
                            resourceIdentity(left.accountId(), left.region(), left.id())));
                    break;
                }
                try {
                    RequestScopes.runAs(restoring.accountId(), restoring.region(), () -> {
                        if (restoring.deleting()) {
                            finishDelete(restoring);
                        } else {
                            restoreCluster(restoring);
                        }
                    });
                } catch (RuntimeException e) {
                    failures.add(restoreFailure(restoring, e));
                }
            }
            if (!failures.isEmpty()) {
                RuntimeException failure = failures.getFirst();
                failures.subList(1, failures.size()).forEach(failure::addSuppressed);
                throw failure;
            }
        });
    }

    private static RuntimeException restoreFailure(RestoringCluster restoring, RuntimeException cause) {
        return new IllegalStateException("Could not restore Neptune cluster " + restoring.id() + " of account "
                + restoring.accountId() + " in " + restoring.region(), cause);
    }

    /**
     * Marks a stored cluster as being restored, under its lock, holding its proxy port for it, or, for a delete
     * Floci did not live to finish, as one to remove; null for one gone meanwhile. A cluster recorded before its
     * graph was kept in a volume is given one here, and one recorded before its engine was kept the configured
     * one, both kept before any Docker call.
     */
    private RestoringCluster prepareRestore(RestoringCluster candidate) {
        String identity = resourceIdentity(candidate.accountId(), candidate.region(), candidate.id());
        RestoringCluster restoring;
        List<String> members;
        int port;
        synchronized (clusterLock(identity)) {
            // Read as DescribeDBClusters reads it, which moves a record stored under a legacy key onto its key.
            NeptuneCluster cluster = getStoredCluster(candidate.id()).orElse(null);
            if (cluster == null) {
                return null;
            }
            // Held at once, whatever happens next: a cluster created now must not be handed this cluster's port.
            String owner = portOwners.putIfAbsent(cluster.getProxyPort(), identity);
            if (owner != null && !owner.equals(identity)) {
                int taken = cluster.getProxyPort();
                cluster.setProxyPort(allocateProxyPort(null, identity));
                cluster.setPort(cluster.getProxyPort());
                LOG.warnv("Neptune cluster {0} moves from port {1}, which another cluster holds, to {2}",
                        candidate.id(), String.valueOf(taken), String.valueOf(cluster.getProxyPort()));
            }
            boolean deleting = "deleting".equals(cluster.getStatus());
            restoring = new RestoringCluster(candidate.accountId(), candidate.region(), candidate.id(),
                    cluster.getDbClusterResourceId(), deleting);
            if (deleting) {
                return restoring;
            }
            members = List.copyOf(cluster.getDbClusterMembers());
            if (cluster.getVolumeId() == null && keepsClusters()) {
                cluster.setVolumeId(UUID.randomUUID().toString());
            }
            if (cluster.getDbType() == null) {
                cluster.setDbType(configuredDbType());
            }
            cluster.setStatus("maintenance");
            putCluster(cluster);
            port = cluster.getProxyPort();
        }
        updateInstances(members, candidate.id(), "starting", port);
        // Owned by the restore only once nothing here can fail, so one that did leaves no delete waiting on a restore
        // that never runs. Nothing is started before the restore runs, so a delete before this removes the cluster.
        restoringClusters.add(identity);
        return restoring;
    }

    /** Removes a cluster a delete Floci did not live to finish left behind, its container and volume with it. */
    private void finishDelete(RestoringCluster restoring) {
        synchronized (clusterLock(resourceIdentity(restoring.accountId(), restoring.region(), restoring.id()))) {
            NeptuneCluster cluster = restoreTarget(restoring);
            if (cluster != null) {
                removeCluster(cluster);
            }
        }
    }

    private void restoreCluster(RestoringCluster restoring) {
        String identity = resourceIdentity(restoring.accountId(), restoring.region(), restoring.id());
        NeptuneContainerHandle handle = null;
        try {
            NeptuneCluster cluster = restoreTarget(restoring);
            if (cluster == null) {
                restoringClusters.remove(identity);
                return;
            }
            NeptuneDbType dbType = cluster.getDbType();
            String image = switch (dbType) {
                case GREMLIN -> config.services().neptune().defaultImage();
                case NEO4J -> config.services().neptune().defaultNeo4jImage();
            };
            handle = containerManager.tryStart(restoring.id(), image, dbType, restoring.accountId(),
                    restoring.region(), cluster.getVolumeId());
            finishRestore(restoring, handle);
        } catch (RuntimeException e) {
            try {
                proxyManager.stopProxy(identity);
                // By identity, not handle: a start that fails after its container exists returns no handle.
                containerManager.stopByClusterId(identity);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (containerManager.isShuttingDown()) {
                // Left as it is, which the next start restores.
                restoringClusters.remove(identity);
                LOG.debugv("Neptune cluster {0} not restored: {1}", restoring.id(), e.getMessage());
                return;
            }
            try {
                failRestore(restoring);
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            throw e;
        }
    }

    /**
     * Puts the cluster's proxy in front of the container started for it, or leaves it with none when no Docker
     * daemon is reachable, and makes it and its instances available; a cluster deleted while it started is
     * removed now.
     */
    private void finishRestore(RestoringCluster restoring, NeptuneContainerHandle handle) {
        String identity = resourceIdentity(restoring.accountId(), restoring.region(), restoring.id());
        NeptuneCluster cluster;
        List<String> members;
        synchronized (clusterLock(identity)) {
            // The restore ends here: from now on a delete removes the cluster itself.
            restoringClusters.remove(identity);
            cluster = restoreTarget(restoring);
            if (cluster == null) {
                if (handle != null) {
                    containerManager.stop(handle);
                }
                return;
            }
            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());
                if (!"deleting".equals(cluster.getStatus())) {
                    proxyManager.startProxy(identity, cluster.getProxyPort(), handle.getHost(), handle.getPort());
                }
            } else {
                clearContainer(cluster);
            }
            if ("deleting".equals(cluster.getStatus())) {
                removeCluster(cluster);
                return;
            }
            cluster.setStatus("available");
            putCluster(cluster);
            members = List.copyOf(cluster.getDbClusterMembers());
        }
        updateInstances(members, restoring.id(), "available", cluster.getProxyPort());
    }

    /** Leaves a cluster whose container or proxy did not start with none, its instances failed, for the next start. */
    private void failRestore(RestoringCluster restoring) {
        NeptuneCluster cluster;
        List<String> members;
        String identity = resourceIdentity(restoring.accountId(), restoring.region(), restoring.id());
        synchronized (clusterLock(identity)) {
            restoringClusters.remove(identity);
            cluster = restoreTarget(restoring);
            if (cluster == null) {
                return;
            }
            if ("deleting".equals(cluster.getStatus())) {
                removeCluster(cluster);
                return;
            }
            clearContainer(cluster);
            cluster.setStatus("available");
            putCluster(cluster);
            members = List.copyOf(cluster.getDbClusterMembers());
        }
        updateInstances(members, restoring.id(), "failed", cluster.getProxyPort());
    }

    /** The cluster the restore is for, or null when it is gone or another cluster has taken its identifier. */
    private NeptuneCluster restoreTarget(RestoringCluster restoring) {
        return getStoredCluster(restoring.id())
                .filter(cluster -> Objects.equals(cluster.getDbClusterResourceId(), restoring.resourceId()))
                .orElse(null);
    }

    private static void clearContainer(NeptuneCluster cluster) {
        cluster.setContainerId(null);
        cluster.setContainerHost(null);
        cluster.setContainerPort(0);
    }

    /** Sets the status of a cluster's instances, and the port they report, which is the cluster's proxy port. */
    private void updateInstances(List<String> members, String clusterId, String status, int port) {
        for (String instanceId : members) {
            // Under the instance's lock, which its deletion and creation take, so a write never puts back an
            // instance deleted since it was read, nor overwrites one created again under its name.
            synchronized (instanceLock(resourceIdentity(currentAccount(), currentRegion(), instanceId))) {
                getStoredInstance(instanceId)
                        .filter(instance -> clusterId.equals(instance.getDbClusterIdentifier()))
                        .ifPresent(instance -> {
                            instance.setStatus(status);
                            instance.setPort(port);
                            putInstance(instance);
                        });
            }
        }
    }

    private NeptuneDbType configuredDbType() {
        return NeptuneDbType.fromConfig(config.services().neptune().dbType()).orElse(NeptuneDbType.GREMLIN);
    }

    private Object clusterLock(String identity) {
        return locks.computeIfAbsent("cluster:" + identity, ignored -> new Object());
    }

    private Object instanceLock(String identity) {
        return locks.computeIfAbsent("instance:" + identity, ignored -> new Object());
    }

    /**
     * A cluster the startup restore brings back, or removes when {@code deleting}, by the account and Region it is
     * stored in and the resource ID it had when marked.
     */
    private record RestoringCluster(String accountId, String region, String id, String resourceId,
                                    boolean deleting) {
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
        putCluster(cluster);
        LOG.infov("Role {0} added to Neptune cluster {1}", roleArn, id);
        return cluster;
    }

    public NeptuneCluster removeRoleFromDbCluster(String id, String roleArn) {
        NeptuneCluster cluster = getDbCluster(id);
        if (roleArn == null || !cluster.getAssociatedRoleArns().remove(roleArn)) {
            throw new AwsException("DBClusterRoleNotFound",
                    "Role ARN " + roleArn + " is not associated with Neptune cluster " + id + ".", 404);
        }
        putCluster(cluster);
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
        String region = currentRegion();
        // The instance's lock before its cluster's, the one order every path that holds both uses.
        synchronized (instanceLock(resourceIdentity(currentAccount(), region, id))) {
            if (getStoredInstance(id).isPresent()) {
                throw new AwsException("DBInstanceAlreadyExists",
                        "Neptune instance " + id + " already exists.", 400);
            }

            synchronized (clusterLock(resourceIdentity(currentAccount(), region, dbClusterIdentifier))) {
                NeptuneCluster cluster = getDbCluster(dbClusterIdentifier);
                if (cluster.getStatus() != null && !"available".equals(cluster.getStatus())) {
                    throw new AwsException("InvalidDBClusterStateFault",
                            "DB cluster is not available for member creation.", 400);
                }

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
                putCluster(cluster);

                putInstance(instance);
                LOG.infov("Neptune instance {0} created in cluster {1}", id, dbClusterIdentifier);
                return instance;
            }
        }
    }

    public Optional<NeptuneCluster> findDbCluster(String id) {
        return id == null ? Optional.empty() : getStoredCluster(id);
    }

    public NeptuneInstance getDbInstance(String id) {
        return getStoredInstance(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));
    }

    public Collection<NeptuneInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-instance-id filter accepts ARNs as well as identifiers; see
            // listDbClusters for why the match is against the stored ARN.
            if (AwsArnUtils.looksLikeArn(filterId)) {
                return scanInstances().stream()
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return scanInstances().stream().filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceIdentifier())).toList();
        }
        return scanInstances();
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
        putInstance(instance);
        LOG.infov("Neptune instance {0} modified", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        String region = currentRegion();
        synchronized (instanceLock(resourceIdentity(currentAccount(), region, id))) {
            NeptuneInstance instance = getStoredInstance(id).orElseThrow(() ->
                    new AwsException("DBInstanceNotFound",
                            "Neptune instance " + id + " not found.", 404));

            String clusterId = instance.getDbClusterIdentifier();
            synchronized (clusterLock(resourceIdentity(currentAccount(), region, clusterId))) {
                NeptuneCluster cluster = getStoredCluster(clusterId).orElse(null);
                if (cluster != null) {
                    cluster.getDbClusterMembers().remove(id);
                    putCluster(cluster);
                }
            }

            deleteInstance(instance);
            LOG.infov("Neptune instance {0} deleted", id);
        }
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
                NeptuneCluster cluster = scanClusters().stream()
                        .filter(c -> resourceName.equalsIgnoreCase(c.getDbClusterArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBClusterNotFoundFault",
                                "Neptune cluster " + id + " not found.", 404));
                yield new TagTarget(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    putCluster(cluster);
                });
            }
            case "db" -> {
                NeptuneInstance instance = scanInstances().stream()
                        .filter(i -> resourceName.equalsIgnoreCase(i.getDbInstanceArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBInstanceNotFound",
                                "Neptune instance " + id + " not found.", 404));
                yield new TagTarget(instance.getTags(), updated -> {
                    instance.setTags(updated);
                    putInstance(instance);
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported by Neptune in Floci: " + resourceName, 400);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String currentAccount() {
        String account = regionResolver.getAccountId();
        return account != null ? account : regionResolver.getDefaultAccountId();
    }

    private String currentRegion() {
        String region = regionResolver.getRegion();
        return region != null ? region : regionResolver.getDefaultRegion();
    }

    private String clusterKey(String region, String id) {
        return region + "/" + id;
    }

    private String instanceKey(String region, String id) {
        return region + "/" + id;
    }

    private Optional<NeptuneCluster> getStoredCluster(String id) {
        String account = currentAccount();
        String region = currentRegion();
        String key = clusterKey(region, id);
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            return aware.getForAccountMigratingLegacyKeys(account, key, List.of(id),
                    value -> belongsTo(value.getDbClusterArn(), account, region));
        }
        return clusters.get(key);
    }

    private Optional<NeptuneInstance> getStoredInstance(String id) {
        String account = currentAccount();
        String region = currentRegion();
        String key = instanceKey(region, id);
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            return aware.getForAccountMigratingLegacyKeys(account, key, List.of(id),
                    value -> belongsTo(value.getDbInstanceArn(), account, region));
        }
        return instances.get(key);
    }

    private Collection<NeptuneCluster> scanClusters() {
        String account = currentAccount();
        String region = currentRegion();
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            migrateLegacyClusters(aware, account, region);
            return aware.scanForAccount(account, key -> key.startsWith(region + "/"));
        }
        return clusters.scan(key -> key.startsWith(region + "/"));
    }

    private Collection<NeptuneInstance> scanInstances() {
        String account = currentAccount();
        String region = currentRegion();
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            migrateLegacyInstances(aware, account, region);
            return aware.scanForAccount(account, key -> key.startsWith(region + "/"));
        }
        return instances.scan(key -> key.startsWith(region + "/"));
    }

    private void migrateLegacyClusters(AccountAwareStorageBackend<NeptuneCluster> aware,
                                       String account, String region) {
        if (account.equals(regionResolver.getDefaultAccountId())) {
            for (NeptuneCluster cluster : aware.scanUnscopedLegacy(value -> belongsTo(
                    value.getDbClusterArn(), account, region))) {
                migrateLegacyCluster(aware, account, region, cluster);
            }
        }
        for (String legacyKey : aware.keysForAccount(account)) {
            if (legacyKey.contains("/")) {
                continue;
            }
            aware.getForAccount(account, legacyKey)
                    .filter(value -> belongsTo(value.getDbClusterArn(), account, region))
                    .ifPresent(value -> migrateLegacyCluster(aware, account, region, value));
        }
    }

    private void migrateLegacyInstances(AccountAwareStorageBackend<NeptuneInstance> aware,
                                        String account, String region) {
        if (account.equals(regionResolver.getDefaultAccountId())) {
            for (NeptuneInstance instance : aware.scanUnscopedLegacy(value -> belongsTo(
                    value.getDbInstanceArn(), account, region))) {
                migrateLegacyInstance(aware, account, region, instance);
            }
        }
        for (String legacyKey : aware.keysForAccount(account)) {
            if (legacyKey.contains("/")) {
                continue;
            }
            aware.getForAccount(account, legacyKey)
                    .filter(value -> belongsTo(value.getDbInstanceArn(), account, region))
                    .ifPresent(value -> migrateLegacyInstance(aware, account, region, value));
        }
    }

    private void migrateLegacyCluster(AccountAwareStorageBackend<NeptuneCluster> aware,
                                      String account, String region, NeptuneCluster cluster) {
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        aware.getForAccountMigratingLegacyKeys(account, key,
                List.of(cluster.getDbClusterIdentifier()),
                value -> belongsTo(value.getDbClusterArn(), account, region))
                .ifPresent(value -> aware.putForAccount(account, key, value));
    }

    private void migrateLegacyInstance(AccountAwareStorageBackend<NeptuneInstance> aware,
                                       String account, String region, NeptuneInstance instance) {
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        aware.getForAccountMigratingLegacyKeys(account, key,
                List.of(instance.getDbInstanceIdentifier()),
                value -> belongsTo(value.getDbInstanceArn(), account, region))
                .ifPresent(value -> aware.putForAccount(account, key, value));
    }

    private void putCluster(NeptuneCluster cluster) {
        String account = arnAccount(cluster.getDbClusterArn());
        String region = arnRegion(cluster.getDbClusterArn());
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            aware.putForAccount(account, key, cluster);
        } else {
            clusters.put(key, cluster);
        }
    }

    private void deleteCluster(NeptuneCluster cluster) {
        String account = arnAccount(cluster.getDbClusterArn());
        String region = arnRegion(cluster.getDbClusterArn());
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            aware.deleteForAccount(account, key);
        } else {
            clusters.delete(key);
        }
    }

    private void putInstance(NeptuneInstance instance) {
        String account = arnAccount(instance.getDbInstanceArn());
        String region = arnRegion(instance.getDbInstanceArn());
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            aware.putForAccount(account, key, instance);
        } else {
            instances.put(key, instance);
        }
    }

    private void deleteInstance(NeptuneInstance instance) {
        String account = arnAccount(instance.getDbInstanceArn());
        String region = arnRegion(instance.getDbInstanceArn());
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            aware.deleteForAccount(account, key);
        } else {
            instances.delete(key);
        }
    }

    private static boolean belongsTo(String arn, String account, String region) {
        return account.equals(arnAccount(arn)) && region.equals(arnRegion(arn));
    }

    private static String arnAccount(String arn) {
        try {
            return AwsArnUtils.parse(arn).accountId();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String arnRegion(String arn) {
        try {
            return AwsArnUtils.parse(arn).region();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String resourceIdentity(String account, String region, String id) {
        return account + "-" + region + "-" + id;
    }

    private static String resourceIdentity(NeptuneCluster cluster) {
        return resourceIdentity(arnAccount(cluster.getDbClusterArn()), arnRegion(cluster.getDbClusterArn()),
                cluster.getDbClusterIdentifier());
    }

    /**
     * Whether Neptune's records outlive Floci, under its own storage mode or the global one, so that a cluster's
     * data is kept in a volume of its own as well.
     */
    private boolean keepsClusters() {
        return !"memory".equals(NeptuneContainerManager.storageMode(config));
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort(Integer requested, String owner) {
        int base = config.services().neptune().proxyBasePort();
        int max = config.services().neptune().proxyMaxPort();
        if (requested != null && requested >= base && requested <= max
                && portOwners.putIfAbsent(requested, owner) == null) {
            return requested;
        }
        if (requested != null) {
            LOG.infov("Requested Neptune port {0} is outside the proxy range {1}-{2} or already in use; "
                    + "allocating the next free proxy port instead",
                    String.valueOf(requested), String.valueOf(base), String.valueOf(max));
        }
        for (int port = base; port <= max; port++) {
            if (portOwners.putIfAbsent(port, owner) == null) {
                return port;
            }
        }
        // Wire code the SDK maps to InsufficientStorageClusterCapacityFault (the only
        // capacity fault CreateDBCluster declares); "InsufficientNeptuneCapacity" isn't a
        // real Neptune code, so callers got an unmapped generic NeptuneException.
        throw new AwsException("InsufficientStorageClusterCapacity",
                "No available proxy ports in range " + base + "-" + max, 400);
    }

    private void releaseProxyPort(int port, String owner) {
        portOwners.remove(port, owner);
    }
}
