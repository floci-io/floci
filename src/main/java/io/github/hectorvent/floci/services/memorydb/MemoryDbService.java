package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.StripedLocks;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.Endpoint;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Core MemoryDB business logic — clusters, ACLs and users.
 *
 * <p>Authentication follows the real MemoryDB model: a {@link User} is created with a
 * password or IAM auth mode, attached to an {@link Acl}, and a cluster references that
 * ACL via {@code ACLName}. A cluster therefore has no auth mode of its own — its
 * effective authentication is resolved from the users of the ACL it references.
 *
 * <p>The built-in {@code open-access} ACL and {@code default} user (which AWS provides
 * out of the box and which cannot be created or deleted) are synthesized rather than
 * stored, so they always exist for every account and map to the no-auth path.
 */
@ApplicationScoped
public class MemoryDbService {

    private static final Logger LOG = Logger.getLogger(MemoryDbService.class);
    private static final String DEFAULT_ENGINE = "redis";
    private static final String DEFAULT_ENGINE_VERSION = "7.1";
    private static final String DEFAULT_ACL = "open-access";
    private static final String DEFAULT_USER = "default";
    private static final String ACTIVE = "active";
    private static final String LEGACY_CLUSTER_BACKUP_PREFIX = "legacy-clusters/";
    private static final int REDIS_PORT = 6379;

    // Per the MemoryDB API: a user name must start with a letter and contain only
    // letters, digits and hyphens.
    private static final Pattern USER_NAME_PATTERN =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]*");

    private final AccountAwareStorageBackend<Cluster> clusters;
    private final AccountAwareStorageBackend<User> users;
    private final AccountAwareStorageBackend<Acl> acls;
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final SigV4Validator sigV4Validator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> ownedProxyPorts = new ConcurrentHashMap<>();
    private final StripedLocks clusterLocks = new StripedLocks(256);

    @Inject
    public MemoryDbService(MemoryDbContainerManager containerManager,
                           MemoryDbProxyManager proxyManager,
                           SigV4Validator sigV4Validator,
                           StorageFactory storageFactory,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.sigV4Validator = sigV4Validator;
        this.config = config;
        this.regionResolver = regionResolver;
        this.clusters = storageFactory.create("memorydb", "memorydb-clusters.json",
                new TypeReference<Map<String, Cluster>>() {});
        this.users = storageFactory.create("memorydb", "memorydb-users.json",
                new TypeReference<Map<String, User>>() {});
        this.acls = storageFactory.create("memorydb", "memorydb-acls.json",
                new TypeReference<Map<String, Acl>>() {});
    }

    // ──────────────────────────── Clusters ────────────────────────────

    public Cluster createCluster(Cluster spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        String resourceKey = key(region, name);
        // Claim the name for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback. The existence
        // check follows the claim, so a create that ended before it is seen.
        String claimKey = regionResolver.getAccountId() + "/" + resourceKey;
        if (!provisioningClusterNames.add(claimKey)) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster " + name + " is already being created.", 400);
        }

        try {
            if (clusters.get(resourceKey).isPresent() || legacyGet(clusters, name, region).isPresent()) {
                throw new AwsException("ClusterAlreadyExistsFault",
                        "Cluster with specified name already exists.", 400);
            }
            String aclName = spec.getAclName();
            if (aclName == null || aclName.isBlank()) {
                throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
            }
            requireAclExists(aclName, region);
            boolean authRequired = isAuthRequired(aclName, region);

            Cluster cluster = new Cluster();
            cluster.setName(name);
            cluster.setAccountId(regionResolver.getAccountId());
            cluster.setRegion(region);
            cluster.setDescription(spec.getDescription());
            cluster.setStatus(ClusterStatus.AVAILABLE);
            cluster.setNodeType(spec.getNodeType() != null ? spec.getNodeType() : "db.t4g.small");
            cluster.setNumberOfShards(spec.getNumberOfShards() > 0 ? spec.getNumberOfShards() : 1);
            cluster.setEngine(spec.getEngine() != null ? spec.getEngine() : DEFAULT_ENGINE);
            cluster.setEngineVersion(spec.getEngineVersion() != null ? spec.getEngineVersion() : DEFAULT_ENGINE_VERSION);
            cluster.setAclName(aclName);
            cluster.setTlsEnabled(spec.isTlsEnabled());
            cluster.setArn(buildArn(region, "cluster", name));
            cluster.setCreatedAt(Instant.now());
            cluster.setTags(spec.getTags());
            if (!config.services().memorydb().mock() && config.storage() != null
                    && !"memory".equals(config.storage().mode())) {
                cluster.setVolumeId(UUID.randomUUID().toString());
            }

            if (config.services().memorydb().mock()) {
                LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
                cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), REDIS_PORT));
                clusters.putAllAtomicallyForAccount(cluster.getAccountId(), Map.of(resourceKey, cluster));
            } else {
                startBackend(cluster, authRequired);
            }

            LOG.infov("MemoryDB cluster {0} created (acl={1}, authRequired={2}), endpoint={3}:{4}",
                    name, aclName, String.valueOf(authRequired), cluster.getClusterEndpoint().address(),
                    String.valueOf(cluster.getClusterEndpoint().port()));
            return cluster;
        } finally {
            provisioningClusterNames.remove(claimKey);
        }
    }

    public Cluster getCluster(String name) {
        return getCluster(name, currentRegion());
    }

    public Cluster getCluster(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return resourceGet(clusters, name, region).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        return describeClusters(filterName, currentRegion());
    }

    public Collection<Cluster> describeClusters(String filterName, String region) {
        migrateLegacyClusters(region);
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getCluster(filterName, region));
        }
        return clusters.scan(k -> k.startsWith(region + ":"));
    }

    public Cluster updateCluster(String name, String description) {
        return updateCluster(name, description, currentRegion());
    }

    public Cluster updateCluster(String name, String description, String region) {
        Cluster cluster = getCluster(name, region);
        if (description != null) {
            cluster.setDescription(description);
        }
        clusters.put(key(region, name), cluster);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        return deleteCluster(name, currentRegion());
    }

    public Cluster deleteCluster(String name, String region) {
        Cluster cluster = getCluster(name, region);
        String ownerKey = cluster.getAccountId() + "/" + key(region, name);
        boolean pendingBackend;
        synchronized (clusterLocks.lockFor(ownerKey)) {
            pendingBackend = cluster.getStatus() == ClusterStatus.CREATING
                    || cluster.getStatus() == ClusterStatus.DELETING;
            cluster.setStatus(ClusterStatus.DELETING);
            clusters.putForAccount(cluster.getAccountId(), key(region, name), cluster);
        }
        String identity = identityName(cluster.getAccountId(), region, name);

        proxyManager.stopProxy(identity);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new MemoryDbContainerHandle(
                    cluster.getContainerId(), identity, cluster.getContainerHost(), cluster.getContainerPort()));
        } else if (pendingBackend && !config.services().memorydb().mock()) {
            containerManager.stopByClusterName(identity);
        }

        containerManager.removeStorage(identity, cluster.getVolumeId());
        synchronized (clusterLocks.lockFor(ownerKey)) {
            releaseOwnedProxyPort(ownerKey, cluster.getProxyPort());
            clusters.deleteForAccount(cluster.getAccountId(), key(region, name));
        }
        LOG.infov("MemoryDB cluster {0} deleted", name);
        return cluster;
    }

    public CompletableFuture<Void> restorePersistedRuntime() {
        if (config.services().memorydb().mock()) {
            return CompletableFuture.completedFuture(null);
        }
        List<RuntimeException> migrationFailures = new ArrayList<>();
        List<AccountEntry<Cluster>> persisted = migratePersistedClusters(migrationFailures);
        List<AccountEntry<Cluster>> pending = new ArrayList<>();
        RuntimeException reservationFailure = null;
        for (RuntimeException failure : migrationFailures) {
            if (reservationFailure == null) {
                reservationFailure = failure;
            } else {
                reservationFailure.addSuppressed(failure);
            }
        }
        for (AccountEntry<Cluster> entry : persisted) {
            Cluster cluster = entry.value();
            String ownerKey = entry.accountId() + "/" + entry.key();
            synchronized (clusterLocks.lockFor(ownerKey)) {
                if (!provisioningClusterNames.add(ownerKey)) {
                    continue;
                }
                try {
                    cluster.setAccountId(entry.accountId());
                    cluster.setRegion(restoredRegion(cluster));
                    if (cluster.getStatus() == ClusterStatus.DELETING) {
                        clusters.putAllAtomicallyForAccount(entry.accountId(), Map.of(entry.key(), cluster));
                        provisioningClusterNames.remove(ownerKey);
                        continue;
                    }
                    Integer ownedPort = ownedProxyPorts.get(ownerKey);
                    int requested = cluster.getProxyPort();
                    int port = ownedPort != null ? ownedPort
                            : requested >= config.services().memorydb().proxyBasePort()
                            && requested <= config.services().memorydb().proxyMaxPort()
                            && usedPorts.add(requested) ? requested : allocateProxyPort();
                    ownedProxyPorts.put(ownerKey, port);
                    cluster.setProxyPort(port);
                    cluster.setClusterEndpoint(null);
                    cluster.setStatus(ClusterStatus.CREATING);
                    if (cluster.getVolumeId() == null && config.storage() != null
                            && !"memory".equals(config.storage().mode())) {
                        cluster.setVolumeId(UUID.randomUUID().toString());
                    }
                    clusters.putAllAtomicallyForAccount(entry.accountId(), Map.of(entry.key(), cluster));
                    pending.add(entry);
                } catch (RuntimeException e) {
                    releaseOwnedProxyPort(ownerKey, cluster.getProxyPort());
                    provisioningClusterNames.remove(ownerKey);
                    if (cluster.getStatus() != ClusterStatus.DELETING) {
                        cluster.setStatus(ClusterStatus.CREATING);
                    }
                    cluster.setClusterEndpoint(null);
                    try {
                        clusters.putForAccount(entry.accountId(), entry.key(), cluster);
                    } catch (RuntimeException persistFailure) {
                        e.addSuppressed(persistFailure);
                    }
                    LOG.warnv(e, "Failed to reserve the MemoryDB runtime for {0}", ownerKey);
                    if (reservationFailure == null) {
                        reservationFailure = e;
                    } else {
                        reservationFailure.addSuppressed(e);
                    }
                }
            }
        }
        RuntimeException initialFailure = reservationFailure;
        return CompletableFuture.runAsync(() -> {
            RuntimeException failure = initialFailure;
            for (AccountEntry<Cluster> entry : pending) {
                try {
                    restoreCluster(entry);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        });
    }

    private List<AccountEntry<Cluster>> migratePersistedClusters(List<RuntimeException> failures) {
        List<AccountEntry<Cluster>> normalized = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (AccountEntry<Cluster> entry : clusters.scanAllAccountEntries(
                storedKey -> !storedKey.startsWith(LEGACY_CLUSTER_BACKUP_PREFIX))) {
            Cluster cluster = entry.value();
            try {
                boolean prefixed = clusters.getForAccount(entry.accountId(), entry.key())
                        .filter(current -> current == cluster).isPresent();
                AwsArnUtils.Arn arn = cluster.getArn() == null || cluster.getArn().isBlank()
                        ? null : AwsArnUtils.parse(cluster.getArn());
                String account = prefixed ? entry.accountId() : cluster.getAccountId();
                if (account == null || account.isBlank()) {
                    account = arn != null ? arn.accountId() : regionResolver.getDefaultAccountId();
                }
                String region = restoredRegion(cluster);
                if ((cluster.getAccountId() != null && !cluster.getAccountId().isBlank()
                        && !account.equals(cluster.getAccountId()))
                        || (arn != null && (!"memorydb".equals(arn.service())
                        || !account.equals(arn.accountId()) || !region.equals(arn.region())))) {
                    throw new IllegalStateException("MemoryDB persisted owner or region contradicts its storage key or ARN");
                }
                String name = cluster.getName();
                if (name == null || name.isBlank()) {
                    throw new IllegalStateException("MemoryDB persisted cluster has no name");
                }
                String logicalKey = key(region, name);
                if (!entry.key().equals(name) && !entry.key().equals(logicalKey)) {
                    throw new IllegalStateException("MemoryDB persisted cluster name contradicts its storage key");
                }
                String sourceKey = prefixed
                        ? AccountAwareStorageBackend.rawKey(entry.accountId(), entry.key()) : entry.key();
                String destinationKey = AccountAwareStorageBackend.rawKey(account, logicalKey);
                Optional<Cluster> existing = clusters.getForAccount(account, logicalKey);
                cluster.setAccountId(account);
                cluster.setRegion(region);
                if (existing.isPresent() && existing.get() != cluster) {
                    String backupKey = LEGACY_CLUSTER_BACKUP_PREFIX + UUID.randomUUID();
                    clusters.putAllAtomicallyForAccount(account, Map.of(backupKey, cluster));
                    clusters.deleteRaw(sourceKey);
                    LOG.warnv("MemoryDB legacy record at {0} conflicts with {1}; retained at {2}/{3} for recovery",
                            sourceKey, destinationKey, account, backupKey);
                    continue;
                }
                if (!sourceKey.equals(destinationKey)) {
                    clusters.putAllAtomicallyForAccount(account, Map.of(logicalKey, cluster));
                    clusters.deleteRaw(sourceKey);
                }
                if (included.add(destinationKey)) {
                    normalized.add(new AccountEntry<>(account, logicalKey, cluster));
                }
            } catch (RuntimeException failure) {
                failures.add(failure);
                LOG.warnv(failure, "Failed to migrate MemoryDB metadata at {0}/{1}",
                        entry.accountId(), entry.key());
            }
        }
        return normalized;
    }

    private void restoreCluster(AccountEntry<Cluster> entry) {
        Cluster cluster = entry.value();
        String ownerKey = entry.accountId() + "/" + entry.key();
        String identity = identityName(entry.accountId(), cluster.getRegion(), cluster.getName());
        MemoryDbContainerHandle handle = null;
        try {
            boolean authRequired = isAuthRequired(cluster.getAclName(), cluster.getRegion(), entry.accountId());
            String image = config.services().memorydb().defaultImage();
            boolean unavailable = false;
            if (cluster.getContainerId() != null) {
                if (!containerManager.isDockerReachable()) {
                    unavailable = true;
                } else {
                    handle = new MemoryDbContainerHandle(cluster.getContainerId(), identity,
                            cluster.getContainerHost(), cluster.getContainerPort());
                    containerManager.stop(handle);
                    clearBackend(cluster);
                    clusters.putAllAtomicallyForAccount(entry.accountId(), Map.of(entry.key(), cluster));
                    handle = null;
                }
            }
            if (!unavailable) {
                handle = cluster.getVolumeId() == null
                        ? containerManager.tryStart(identity, image)
                        : containerManager.tryStart(identity, image, cluster.getVolumeId(),
                                entry.accountId(), cluster.getRegion());
            }
            boolean lost;
            synchronized (clusterLocks.lockFor(ownerKey)) {
                lost = restoreTargetLost(entry);
                if (!lost) {
                    if (handle != null) {
                        cluster.setContainerId(handle.getContainerId());
                        cluster.setContainerHost(handle.getHost());
                        cluster.setContainerPort(handle.getPort());
                        proxyManager.startProxy(identity, authRequired, cluster.getProxyPort(),
                                handle.getHost(), handle.getPort(),
                                (username, secret) -> authenticate(cluster.getName(), username, secret,
                                        cluster.getRegion(), entry.accountId()));
                    } else {
                        LOG.warnv("MemoryDB cluster {0} restored as metadata only: no Docker daemon is reachable",
                                ownerKey);
                    }
                    cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), cluster.getProxyPort()));
                    cluster.setStatus(ClusterStatus.AVAILABLE);
                    clusters.putAllAtomicallyForAccount(entry.accountId(), Map.of(entry.key(), cluster));
                }
            }
            if (lost && handle != null) {
                containerManager.stop(handle);
            }
        } catch (RuntimeException e) {
            boolean cleaned = true;
            try {
                proxyManager.stopProxy(identity);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                cleaned = false;
            }
            try {
                if (handle != null) {
                    containerManager.stop(handle);
                } else {
                    containerManager.stopByClusterName(identity);
                }
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
                cleaned = false;
            }
            synchronized (clusterLocks.lockFor(ownerKey)) {
                Optional<Cluster> current = clusters.getForAccount(entry.accountId(), entry.key());
                if (!restoreTargetLost(entry)
                        || (!cleaned && (current.isEmpty() || current.get() == cluster))) {
                    if (cluster.getStatus() != ClusterStatus.DELETING) {
                        cluster.setStatus(ClusterStatus.CREATING);
                    }
                    cluster.setClusterEndpoint(null);
                    if (cleaned) {
                        clearBackend(cluster);
                        releaseOwnedProxyPort(ownerKey, cluster.getProxyPort());
                    } else if (handle != null) {
                        cluster.setContainerId(handle.getContainerId());
                        cluster.setContainerHost(handle.getHost());
                        cluster.setContainerPort(handle.getPort());
                    }
                    try {
                        clusters.putAllAtomicallyForAccount(entry.accountId(), Map.of(entry.key(), cluster));
                    } catch (RuntimeException persistFailure) {
                        e.addSuppressed(persistFailure);
                    }
                }
            }
            LOG.warnv(e, "Failed to restore MemoryDB runtime for {0}", ownerKey);
            throw e;
        } finally {
            provisioningClusterNames.remove(ownerKey);
        }
    }

    private boolean restoreTargetLost(AccountEntry<Cluster> entry) {
        return clusters.getForAccount(entry.accountId(), entry.key())
                .map(current -> current != entry.value() || current.getStatus() == ClusterStatus.DELETING)
                .orElse(true);
    }

    private String restoredRegion(Cluster cluster) {
        if (cluster.getRegion() != null && !cluster.getRegion().isBlank()) {
            return cluster.getRegion();
        }
        if (AwsArnUtils.isArn(cluster.getArn())) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(cluster.getArn());
            if ("memorydb".equals(arn.service()) && !arn.region().isBlank()) {
                return arn.region();
            }
        }
        return regionResolver.getDefaultRegion();
    }

    private static void clearBackend(Cluster cluster) {
        cluster.setContainerId(null);
        cluster.setContainerHost(null);
        cluster.setContainerPort(0);
    }

    // ──────────────────────────── Users ────────────────────────────

    public User createUser(User spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "UserName is required.", 400);
        }
        if (!USER_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "UserName must start with a letter and contain only letters, digits and hyphens.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_USER.equals(name) || users.get(resourceKey).isPresent()
                || legacyGet(users, name, region).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User with specified name already exists.", 400);
        }
        if (spec.getAuthMode() == null) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode is required.", 400);
        }
        // AuthenticationMode.Type accepts "no-password" in the wire enum, but the service
        // rejects it on create: per the API, all newly-created users must authenticate with
        // a password or IAM. "no-password" is only ever the built-in default user.
        if (spec.getAuthMode() == AuthMode.NO_PASSWORD) {
            throw new AwsException("InvalidParameterValueException",
                    "AuthenticationMode Type must be 'password' or 'iam' for a new user.", 400);
        }
        if (spec.getAuthMode() == AuthMode.PASSWORD
                && (spec.getPasswords() == null || spec.getPasswords().isEmpty())) {
            throw new AwsException("InvalidParameterValueException",
                    "At least one password is required for password authentication.", 400);
        }
        if (spec.getAccessString() == null || spec.getAccessString().isBlank()) {
            throw new AwsException("InvalidParameterValueException", "AccessString is required.", 400);
        }

        User user = new User();
        user.setName(name);
        user.setAccountId(regionResolver.getAccountId());
        user.setRegion(region);
        user.setStatus(ACTIVE);
        user.setAuthMode(spec.getAuthMode());
        user.setPasswords(spec.getPasswords());
        user.setAccessString(spec.getAccessString());
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        user.setArn(buildArn(region, "user", name));
        user.setCreatedAt(Instant.now());

        users.put(resourceKey, user);
        LOG.infov("MemoryDB user {0} created with authMode={1}", name, user.getAuthMode());
        return user;
    }

    public Collection<User> describeUsers(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(users, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_USER.equals(filterName)
                            ? Optional.of(List.of(builtinDefaultUser(region)))
                            : Optional.empty())
                    .orElseThrow(() -> new AwsException("UserNotFoundFault", "User not found.", 404));
        }
        migrateLegacyUsers(region);
        List<User> all = new ArrayList<>();
        all.add(builtinDefaultUser(region));
        all.addAll(users.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public User deleteUser(String name) {
        return deleteUser(name, currentRegion());
    }

    public User deleteUser(String name, String region) {
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be deleted.", 400);
        }
        User user = resourceGet(users, name, region).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        users.delete(key(region, name));
        LOG.infov("MemoryDB user {0} deleted", name);
        return user;
    }

    // ──────────────────────────── ACLs ────────────────────────────

    public Acl createAcl(Acl spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        String resourceKey = key(region, name);
        if (DEFAULT_ACL.equals(name) || acls.get(resourceKey).isPresent()
                || legacyGet(acls, name, region).isPresent()) {
            throw new AwsException("ACLAlreadyExistsFault",
                    "ACL with specified name already exists.", 400);
        }
        if (!spec.getUserNames().contains(DEFAULT_USER)) {
            throw new AwsException("DefaultUserRequired",
                    "A default user is required and must be specified.", 400);
        }
        Set<String> seen = new HashSet<>();
        for (String userName : spec.getUserNames()) {
            if (!seen.add(userName)) {
                throw new AwsException("DuplicateUserNameFault",
                        "Duplicate user name " + userName + " in ACL.", 400);
            }
            if (!userExists(userName, region)) {
                throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
            }
        }

        Acl acl = new Acl();
        acl.setName(name);
        acl.setAccountId(regionResolver.getAccountId());
        acl.setRegion(region);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(spec.getUserNames()));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        acl.setArn(buildArn(region, "acl", name));
        acl.setCreatedAt(Instant.now());

        acls.put(resourceKey, acl);
        LOG.infov("MemoryDB ACL {0} created with users={1}", name, acl.getUserNames());
        return acl;
    }

    public Collection<Acl> describeAcls(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return resourceGet(acls, filterName, region)
                    .map(List::of)
                    .or(() -> DEFAULT_ACL.equals(filterName)
                            ? Optional.of(List.of(builtinOpenAccessAcl(region)))
                            : Optional.empty())
                    .orElseThrow(() -> new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        }
        migrateLegacyAcls(region);
        List<Acl> all = new ArrayList<>();
        all.add(builtinOpenAccessAcl(region));
        all.addAll(acls.scan(k -> k.startsWith(region + ":")));
        return all;
    }

    public Acl deleteAcl(String name) {
        return deleteAcl(name, currentRegion());
    }

    public Acl deleteAcl(String name, String region) {
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be deleted.", 400);
        }
        Acl acl = resourceGet(acls, name, region).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (!clustersUsingAcl(name, region).isEmpty()) {
            throw new AwsException("InvalidACLStateFault",
                    "ACL " + name + " is associated with one or more clusters.", 400);
        }
        acls.delete(key(region, name));
        LOG.infov("MemoryDB ACL {0} deleted", name);
        return acl;
    }

    /** Names of ACLs that include the given user; used to populate the user response. */
    public List<String> aclNamesForUser(String userName) {
        return aclNamesForUser(userName, currentRegion());
    }

    public List<String> aclNamesForUser(String userName, String region) {
        migrateLegacyAcls(region);
        List<String> result = new ArrayList<>();
        if (DEFAULT_USER.equals(userName)) {
            result.add(DEFAULT_ACL);
        }
        acls.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> a.getUserNames().contains(userName))
                .map(Acl::getName)
                .forEach(result::add);
        return result;
    }

    /** Names of clusters currently referencing the given ACL; used to populate the ACL response. */
    public List<String> clustersUsingAcl(String aclName) {
        return clustersUsingAcl(aclName, currentRegion());
    }

    public List<String> clustersUsingAcl(String aclName, String region) {
        migrateLegacyClusters(region);
        return clusters.scan(k -> k.startsWith(region + ":")).stream()
                .filter(c -> aclName.equals(c.getAclName()))
                .map(Cluster::getName)
                .toList();
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return clusterByArn(resourceArn).getTags();
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        Cluster cluster = clusterByArn(resourceArn);
        cluster.getTags().putAll(tags);
        clusters.put(key(cluster.getRegion(), cluster.getName()), cluster);
        return cluster.getTags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        Cluster cluster = clusterByArn(resourceArn);
        tagKeys.forEach(cluster.getTags()::remove);
        clusters.put(key(cluster.getRegion(), cluster.getName()), cluster);
        return cluster.getTags();
    }

    // ──────────────────────────── Authentication ────────────────────────────

    /**
     * Validates a Redis AUTH attempt against the ACL the cluster references. A blank
     * username corresponds to the single-argument {@code AUTH <secret>} form and targets
     * the {@code default} user. The user's own auth mode decides how the secret is
     * checked: a password is compared against the user's passwords, an IAM token is
     * verified as a SigV4 presigned URL.
     */
    public boolean authenticate(String clusterName, String username, String secret) {
        return authenticate(clusterName, username, secret, currentRegion());
    }

    public boolean authenticate(String clusterName, String username, String secret, String region) {
        return authenticate(clusterName, username, secret, region, regionResolver.getAccountId());
    }

    private boolean authenticate(String clusterName, String username, String secret, String region,
                                  String accountId) {
        Cluster cluster = ownedResourceGet(clusters, accountId, clusterName, region).orElse(null);
        if (cluster == null) {
            return false;
        }
        String aclName = cluster.getAclName();
        if (DEFAULT_ACL.equals(aclName)) {
            return true;
        }
        Acl acl = ownedResourceGet(acls, accountId, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        String target = (username == null || username.isEmpty()) ? DEFAULT_USER : username;
        if (!acl.getUserNames().contains(target)) {
            return false;
        }
        User user = resolveUser(target, region, accountId);
        if (user == null) {
            return false;
        }
        return switch (user.getAuthMode()) {
            case IAM -> sigV4Validator.validate(secret, clusterName, user.getName());
            case PASSWORD -> user.getPasswords() != null && user.getPasswords().contains(secret);
            case NO_PASSWORD -> true;
        };
    }

    /** True if the ACL has at least one user that requires a credential (password or IAM). */
    private boolean isAuthRequired(String aclName, String region) {
        return isAuthRequired(aclName, region, regionResolver.getAccountId());
    }

    private boolean isAuthRequired(String aclName, String region, String accountId) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Acl acl = ownedResourceGet(acls, accountId, aclName, region).orElse(null);
        if (acl == null) {
            return false;
        }
        return acl.getUserNames().stream()
                .map(name -> resolveUser(name, region, accountId))
                .filter(Objects::nonNull)
                .anyMatch(u -> u.getAuthMode() != AuthMode.NO_PASSWORD);
    }

    private void requireAclExists(String aclName, String region) {
        if (!DEFAULT_ACL.equals(aclName) && resourceGet(acls, aclName, region).isEmpty()) {
            throw new AwsException("ACLNotFoundFault", "ACL " + aclName + " not found.", 404);
        }
    }

    private boolean userExists(String name, String region) {
        return DEFAULT_USER.equals(name) || resourceGet(users, name, region).isPresent();
    }

    private User resolveUser(String name, String region, String accountId) {
        return ownedResourceGet(users, accountId, name, region)
                .orElseGet(() -> DEFAULT_USER.equals(name) ? builtinDefaultUser(region) : null);
    }

    private <V> Optional<V> ownedResourceGet(AccountAwareStorageBackend<V> store, String accountId,
                                            String name, String region) {
        if (Objects.equals(accountId, regionResolver.getDefaultAccountId())
                && Objects.equals(region, regionResolver.getDefaultRegion())) {
            return store.getForAccountMigratingLegacyKeys(accountId, key(region, name), List.of(name),
                    value -> true);
        }
        return store.getForAccount(accountId, key(region, name));
    }

    private User builtinDefaultUser(String region) {
        User user = new User();
        user.setName(DEFAULT_USER);
        user.setStatus(ACTIVE);
        user.setAuthMode(AuthMode.NO_PASSWORD);
        user.setAccessString("on ~* &* +@all");
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            user.setArn(buildArn(region, "user", DEFAULT_USER));
        }
        return user;
    }

    private Acl builtinOpenAccessAcl(String region) {
        Acl acl = new Acl();
        acl.setName(DEFAULT_ACL);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(List.of(DEFAULT_USER)));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        if (region != null) {
            acl.setArn(buildArn(region, "acl", DEFAULT_ACL));
        }
        return acl;
    }

    // ──────────────────────────── Internals ────────────────────────────

    private Cluster clusterByArn(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidParameterValueException", "ResourceArn is required.", 400);
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
            if (!"memorydb".equals(arn.service()) || !currentRegion().equals(arn.region())
                    || !regionResolver.getAccountId().equals(arn.accountId())) {
                throw new IllegalArgumentException("ARN owner mismatch");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("ClusterNotFoundFault", "Cluster not found.", 404);
        }
        migrateLegacyClusters(currentRegion());
        return clusters.scan(k -> k.startsWith(currentRegion() + ":")).stream()
                .filter(c -> resourceArn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    private void startBackend(Cluster cluster, boolean authRequired) {
        String name = cluster.getName();
        String identity = identityName(cluster.getAccountId(), cluster.getRegion(), name);
        int proxyPort = allocateProxyPort();
        String ownerKey = cluster.getAccountId() + "/" + key(cluster.getRegion(), name);
        ownedProxyPorts.put(ownerKey, proxyPort);
        cluster.setProxyPort(proxyPort);
        cluster.setStatus(ClusterStatus.CREATING);
        try {
            clusters.putAllAtomicallyForAccount(cluster.getAccountId(),
                    Map.of(key(cluster.getRegion(), name), cluster));
        } catch (RuntimeException e) {
            releaseOwnedProxyPort(ownerKey, proxyPort);
            throw e;
        }
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authRequired={1} on proxy port {2}",
                name, String.valueOf(authRequired), String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            // A cluster record is metadata: its name, endpoint host and proxy port are derived
            // from configuration and need no Docker, so the cluster is created and reaches
            // 'available' even when no daemon is reachable. Only connecting to the cache needs
            // the container.
            handle = cluster.getVolumeId() == null
                    ? containerManager.tryStart(identity, image)
                    : containerManager.tryStart(identity, image, cluster.getVolumeId(),
                            cluster.getAccountId(), cluster.getRegion());
            synchronized (clusterLocks.lockFor(ownerKey)) {
                if (clusters.getForAccount(cluster.getAccountId(), key(cluster.getRegion(), name))
                        .filter(current -> current == cluster && current.getStatus() != ClusterStatus.DELETING)
                        .isEmpty()) {
                    throw new IllegalStateException("MemoryDB cluster was deleted while its backend was starting");
                }
                if (handle != null) {
                    cluster.setContainerId(handle.getContainerId());
                    cluster.setContainerHost(handle.getHost());
                    cluster.setContainerPort(handle.getPort());

                    proxyManager.startProxy(identity, authRequired, proxyPort,
                            handle.getHost(), handle.getPort(),
                            (username, secret) -> authenticate(name, username, secret, cluster.getRegion(),
                                    cluster.getAccountId()));
                } else {
                    LOG.warnv("MemoryDB cluster {0} created without a backing container: no Docker "
                            + "daemon is reachable. Metadata operations work; connections to the "
                            + "cluster do not until a daemon appears.", name);
                }
                cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), proxyPort));
                cluster.setStatus(ClusterStatus.AVAILABLE);
                clusters.putAllAtomicallyForAccount(cluster.getAccountId(),
                        Map.of(key(cluster.getRegion(), name), cluster));
            }
        } catch (RuntimeException e) {
            boolean cleaned = rollbackBackend(identity, handle, cluster.getVolumeId(), e);
            synchronized (clusterLocks.lockFor(ownerKey)) {
                try {
                    if (cluster.getStatus() != ClusterStatus.DELETING) {
                        cluster.setStatus(ClusterStatus.CREATING);
                    }
                    cluster.setClusterEndpoint(null);
                    if (cleaned) {
                        clearBackend(cluster);
                        clusters.deleteAllAtomicallyForAccounts(
                                Map.of(cluster.getAccountId(), Set.of(key(cluster.getRegion(), name))));
                        releaseOwnedProxyPort(ownerKey, proxyPort);
                    } else {
                        clusters.putAllAtomicallyForAccount(cluster.getAccountId(),
                                Map.of(key(cluster.getRegion(), name), cluster));
                    }
                } catch (RuntimeException persistenceFailure) {
                    e.addSuppressed(persistenceFailure);
                }
            }
            LOG.warnv(e, "MemoryDB cluster {0} provisioning failed; cleanup complete={1}", ownerKey, cleaned);
            throw e;
        }
    }

    private boolean rollbackBackend(String name, MemoryDbContainerHandle handle, String volumeId,
                                    RuntimeException failure) {
        boolean cleaned = true;
        try {
            if (handle != null) {
                proxyManager.stopProxy(name);
            }
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
            cleaned = false;
        }
        try {
            if (handle != null) {
                containerManager.stop(handle);
            } else {
                containerManager.stopByClusterName(name);
            }
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
            cleaned = false;
        }
        if (cleaned) {
            try {
                containerManager.removeStorage(name, volumeId);
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
                cleaned = false;
            }
        }
        return cleaned;
    }

    private String key(String region, String name) {
        return region + ":" + name;
    }

    private String currentRegion() {
        String region = regionResolver.getRegion();
        if (region != null) {
            return region;
        }
        String defaultRegion = regionResolver.getDefaultRegion();
        return defaultRegion != null ? defaultRegion : "us-east-1"; // partition-literal: reached only when the resolver has no default region (test constructors)
    }

    private String identityName(String accountId, String region, String name) {
        return accountId + "-" + region + "-" + name;
    }

    private <V> Optional<V> legacyGet(StorageBackend<String, V> store, String name, String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        if ((defaultAccount != null && !Objects.equals(defaultAccount, regionResolver.getAccountId()))
                || (defaultRegion != null && !Objects.equals(defaultRegion, region))) {
            return Optional.empty();
        }
        return store.get(name);
    }

    private <V> Optional<V> resourceGet(StorageBackend<String, V> store, String name, String region) {
        Optional<V> result = store.get(key(region, name));
        if (result.isPresent()) {
            return result;
        }
        Optional<V> legacy = legacyGet(store, name, region);
        if (legacy.isPresent()) {
            setOwner(legacy.get(), region);
            store.put(key(region, name), legacy.get());
            store.delete(name);
        }
        return legacy;
    }

    private void migrateLegacyClusters(String region) {
        migrateLegacy(clusters, region, Cluster.class);
    }

    private void migrateLegacyUsers(String region) {
        migrateLegacy(users, region, User.class);
    }

    private void migrateLegacyAcls(String region) {
        migrateLegacy(acls, region, Acl.class);
    }

    private <V> void migrateLegacy(StorageBackend<String, V> store, String region, Class<V> type) {
        if (!isDefaultOwner(region) || !(store instanceof AccountAwareStorageBackend<V> aware)) {
            return;
        }
        String accountId = regionResolver.getAccountId();
        for (V legacy : aware.scanUnscopedLegacy(value -> type.isInstance(value))) {
            String name = resourceName(legacy);
            if (name == null || name.isBlank()) {
                continue;
            }
            aware.getForAccountMigratingLegacyKeys(accountId, name, List.of(), value -> type.isInstance(value))
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
        // A named lookup through AccountAwareStorageBackend may have already moved an old
        // unscoped record to account/name without adding the new region component. Move those
        // intermediate keys as well so unfiltered operations cannot omit them.
        for (String legacyKey : aware.keysForAccount(accountId)) {
            if (legacyKey.contains(":") || legacyKey.startsWith(LEGACY_CLUSTER_BACKUP_PREFIX)) {
                continue;
            }
            aware.getForAccount(accountId, legacyKey)
                    .filter(type::isInstance)
                    .ifPresent(value -> migrateLegacyValue(aware, accountId, value, region));
        }
    }

    private <V> void migrateLegacyValue(AccountAwareStorageBackend<V> aware, String accountId,
                                         V legacy, String region) {
        String name = resourceName(legacy);
        if (name == null || name.isBlank()) {
            return;
        }
        setOwner(legacy, region);
        aware.putForAccount(accountId, key(region, name), legacy);
        aware.deleteForAccount(accountId, name);
    }

    private String resourceName(Object resource) {
        return switch (resource) {
            case Cluster cluster -> cluster.getName();
            case User user -> user.getName();
            case Acl acl -> acl.getName();
            default -> null;
        };
    }

    private boolean isDefaultOwner(String region) {
        String defaultAccount = regionResolver.getDefaultAccountId();
        String defaultRegion = regionResolver.getDefaultRegion();
        return (defaultAccount == null || Objects.equals(defaultAccount, regionResolver.getAccountId()))
                && (defaultRegion == null || Objects.equals(defaultRegion, region));
    }

    private void setOwner(Object resource, String region) {
        String accountId = regionResolver.getAccountId();
        if (resource instanceof Cluster cluster) {
            cluster.setAccountId(accountId);
            cluster.setRegion(region);
        } else if (resource instanceof User user) {
            user.setAccountId(accountId);
            user.setRegion(region);
        } else if (resource instanceof Acl acl) {
            acl.setAccountId(accountId);
            acl.setRegion(region);
        }
    }

    private String buildArn(String region, String resourceType, String name) {
        return regionResolver.buildArn("memorydb", region, resourceType + "/" + name);
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().memorydb().proxyBasePort();
        int max = config.services().memorydb().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientClusterCapacityFault",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    private void releaseOwnedProxyPort(String ownerKey, int port) {
        if (ownedProxyPorts.remove(ownerKey, port)) {
            releaseProxyPort(port);
        }
    }
}
