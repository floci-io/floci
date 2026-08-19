package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Core ElastiCache business logic — replication groups and users.
 * Creates Valkey containers and auth proxies on group creation.
 */
@ApplicationScoped
public class ElastiCacheService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheService.class);

    private final StorageBackend<String, ReplicationGroup> groups;
    private final StorageBackend<String, ElastiCacheUser> users;
    private final StorageBackend<String, CacheParameterGroup> parameterGroups;
    private final ElastiCacheContainerManager containerManager;
    private final ElastiCacheProxyManager proxyManager;
    private final EmulatorConfig config;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningGroupIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> parameterGroupLocks = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              StorageFactory storageFactory,
                              EmulatorConfig config) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.config = config;
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
        this.users = storageFactory.create("elasticache", "elasticache-users.json",
                new TypeReference<Map<String, ElastiCacheUser>>() {});
        this.parameterGroups = storageFactory.create("elasticache", "elasticache-parameter-groups.json",
                new TypeReference<Map<String, CacheParameterGroup>>() {});
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken) {
        if (groups.get(groupId).isPresent()) {
            throw new AwsException("ReplicationGroupAlreadyExistsFault",
                    "Replication group " + groupId + " already exists.", 400);
        }
        // Claim the id for the whole provisioning attempt so a concurrent create can't race
        // ahead and be stopped by this request's handle-less rollback fallback.
        if (!provisioningGroupIds.add(groupId)) {
            throw new AwsException("ReplicationGroupAlreadyExistsFault",
                    "Replication group " + groupId + " is already being created.", 400);
        }

        try {
            int proxyPort = allocateProxyPort();
            String image = config.services().elasticache().defaultImage();

            LOG.infov("Creating replication group {0} with authMode={1} on proxy port {2}",
                    groupId, authMode, String.valueOf(proxyPort));

            ElastiCacheContainerHandle handle = null;
            try {
                handle = containerManager.start(groupId, image);

                String endpointHost = resolveEndpointHost();
                Endpoint endpoint = new Endpoint(endpointHost, proxyPort);
                ReplicationGroup group = new ReplicationGroup(
                        groupId, description, ReplicationGroupStatus.AVAILABLE,
                        authMode, endpoint, Instant.now(), proxyPort);
                group.setContainerId(handle.getContainerId());
                group.setContainerHost(handle.getHost());
                group.setContainerPort(handle.getPort());
                group.setAuthToken(authToken);

                proxyManager.startProxy(groupId, authMode, proxyPort,
                        handle.getHost(), handle.getPort(),
                        (username, password) -> validatePassword(groupId, username, password));

                groups.put(groupId, group);
                LOG.infov("Replication group {0} created, endpoint={1}:{2}", groupId, endpointHost, String.valueOf(proxyPort));
                return group;
            } catch (RuntimeException e) {
                LOG.warnv("Replication group {0} provisioning failed, rolling back: {1}", groupId, e.getMessage());
                rollbackReplicationGroup(groupId, handle, proxyPort);
                throw e;
            }
        } finally {
            provisioningGroupIds.remove(groupId);
        }
    }

    private void rollbackReplicationGroup(String groupId, ElastiCacheContainerHandle handle, int proxyPort) {
        try {
            if (handle != null) {
                proxyManager.stopProxy(groupId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping proxy for replication group {0}: {1}", groupId, e.getMessage());
        }
        try {
            if (handle != null) {
                containerManager.stop(handle);
            } else {
                // No handle: a readiness timeout throws before start() can return one.
                containerManager.stopByGroupId(groupId);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error stopping container for replication group {0}: {1}", groupId, e.getMessage());
        } finally {
            releaseProxyPort(proxyPort);
        }
    }

    public ReplicationGroup getReplicationGroup(String groupId) {
        return groups.get(groupId).orElseThrow(() ->
                new AwsException("ReplicationGroupNotFoundFault",
                        "Replication group " + groupId + " not found.", 404));
    }

    public Collection<ReplicationGroup> listReplicationGroups(String filterGroupId) {
        if (filterGroupId != null && !filterGroupId.isBlank()) {
            return groups.get(filterGroupId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("ReplicationGroupNotFoundFault",
                            "Replication group " + filterGroupId + " not found.", 404));
        }
        return groups.scan(k -> true);
    }

    public void deleteReplicationGroup(String groupId) {
        ReplicationGroup group = groups.get(groupId).orElseThrow(() ->
                new AwsException("ReplicationGroupNotFoundFault",
                        "Replication group " + groupId + " not found.", 404));

        group.setStatus(ReplicationGroupStatus.DELETING);
        groups.put(groupId, group);

        proxyManager.stopProxy(groupId);

        if (group.getContainerId() != null) {
            containerManager.stop(new ElastiCacheContainerHandle(
                    group.getContainerId(), groupId, group.getContainerHost(), group.getContainerPort()));
        }

        releaseProxyPort(group.getProxyPort());
        groups.delete(groupId);
        LOG.infov("Replication group {0} deleted", groupId);
    }

    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userIdsToAdd,
                                                    List<String> userIdsToRemove) {
        ReplicationGroup group = getReplicationGroup(groupId);

        if (userIdsToAdd != null) {
            for (String userId : userIdsToAdd) {
                getUser(userId); // validate user exists
                group.getAssociatedUserIds().add(userId);
            }
        }
        if (userIdsToRemove != null) {
            group.getAssociatedUserIds().removeAll(userIdsToRemove);
        }

        groups.put(groupId, group);
        return group;
    }

    public ElastiCacheUser createUser(String userId, String userName, AuthMode authMode,
                                      List<String> passwords, String accessString, String engine) {
        if (users.get(userId).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User " + userId + " already exists.", 400);
        }

        // Engine is required on AWS, but Floci's CreateUser accepted requests without
        // it, so a missing value keeps the previous implicit redis.
        String normalizedEngine = (engine == null || engine.isBlank()) ? "redis" : normalizeEngine(engine);
        ElastiCacheUser user = new ElastiCacheUser(
                userId, userName, authMode,
                passwords != null ? passwords : List.of(),
                accessString != null ? accessString : "on ~* +@all",
                normalizedEngine, "active", Instant.now());

        users.put(userId, user);
        LOG.infov("ElastiCache user {0} created with authMode={1}", userId, authMode);
        return user;
    }

    public ElastiCacheUser getUser(String userId) {
        return users.get(userId).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User " + userId + " not found.", 404));
    }

    public Collection<ElastiCacheUser> listUsers(String filterUserId, String filterEngine) {
        // UserId wins when both filters are sent; AWS documents no interaction between them.
        if (filterUserId != null && !filterUserId.isBlank()) {
            return users.get(filterUserId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("UserNotFoundFault",
                            "User " + filterUserId + " not found.", 404));
        }
        if (filterEngine != null && !filterEngine.isBlank()) {
            return users.scan(k -> true).stream()
                    .filter(u -> filterEngine.equalsIgnoreCase(u.getEngine()))
                    .toList();
        }
        return users.scan(k -> true);
    }

    public ElastiCacheUser modifyUser(String userId, List<String> passwords, String engine) {
        ElastiCacheUser user = getUser(userId);
        // Storage backends hand back the live stored object, so validate everything
        // before the first setter — a rejected request must not leave changes behind.
        String normalizedEngine = (engine == null || engine.isBlank()) ? null : normalizeEngine(engine);
        if (passwords != null) {
            user.setPasswords(passwords);
        }
        if (normalizedEngine != null) {
            user.setEngine(normalizedEngine);
        }
        users.put(userId, user);
        return user;
    }

    public void deleteUser(String userId) {
        if (users.get(userId).isEmpty()) {
            throw new AwsException("UserNotFoundFault", "User " + userId + " not found.", 404);
        }
        users.delete(userId);
        LOG.infov("ElastiCache user {0} deleted", userId);
    }

    /**
     * Validates a Redis AUTH password for the given group.
     * Checks the group-level authToken first, then falls back to the "default" user
     * associated with the group (per Redis 6+ ACL spec, single-arg AUTH only
     * authenticates the default user). Only users explicitly added via
     * ModifyReplicationGroup are checked, preventing cross-group credential leakage.
     */
    public boolean validatePassword(String groupId, String username, String password) {
        ReplicationGroup group = groups.get(groupId).orElse(null);
        if (group == null) {
            return false;
        }

        if (username == null || username.isEmpty()) {
            // AUTH password form: check group-level authToken first
            if (group.getAuthToken() != null && password.equals(group.getAuthToken())) {
                return true;
            }
            // Fall back to the "default" PASSWORD user associated with this group
            Set<String> groupUserIds = group.getAssociatedUserIds();
            return groupUserIds.stream()
                    .map(id -> users.get(id).orElse(null))
                    .filter(u -> u != null
                            && "default".equals(u.getUserName())
                            && u.getAuthMode() == AuthMode.PASSWORD)
                    .anyMatch(u -> u.getPasswords() != null && u.getPasswords().contains(password));
        }
        // AUTH username password form: find user by userName, scoped to group
        Set<String> groupUserIds = group.getAssociatedUserIds();
        return groupUserIds.stream()
                .map(id -> users.get(id).orElse(null))
                .filter(u -> u != null && username.equals(u.getUserName()) && u.getAuthMode() == AuthMode.PASSWORD)
                .anyMatch(u -> u.getPasswords() != null && u.getPasswords().contains(password));
    }

    // AWS allows only redis and valkey.
    private static String normalizeEngine(String engine) {
        String normalized = engine.toLowerCase(Locale.ROOT);
        if (!"redis".equals(normalized) && !"valkey".equals(normalized)) {
            throw new AwsException("InvalidParameterValue",
                    "Engine must be 'redis' or 'valkey'.", 400);
        }
        return normalized;
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort() {
        int base = config.services().elasticache().proxyBasePort();
        int max = config.services().elasticache().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        throw new AwsException("InsufficientReplicationGroupCapacity",
                "No available proxy ports in range " + base + "-" + max, 503);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    // ── Cache Parameter Groups ────────────────────────────────────────────────

    /** The families AWS accepts, and validates a create against. */
    private static final List<String> PARAMETER_GROUP_FAMILIES = List.of(
            "memcached1.4", "memcached1.5", "memcached1.6",
            "redis2.6", "redis2.8", "redis3.2", "redis4.0", "redis5.0", "redis6.x", "redis7",
            "valkey7", "valkey8", "valkey9");

    /** The families AWS also publishes a cluster-mode default for. */
    private static final List<String> CLUSTER_MODE_FAMILIES = List.of(
            "redis3.2", "redis4.0", "redis5.0", "redis6.x", "redis7", "valkey7", "valkey8", "valkey9");

    /**
     * Names begin with a letter and hold only letters, digits and single interior hyphens. AWS
     * applies this to deletes as well as creates, which is why a {@code default.*} group cannot be
     * deleted: the dot fails this rule before anything looks the group up.
     */
    private static final Pattern PARAMETER_GROUP_NAME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$");

    /**
     * The {@code default.*} groups AWS publishes. Derived rather than stored: nothing can modify or
     * delete them, so persisting them would only add records to migrate, a writer to race, and an
     * install predating this that needs backfilling.
     */
    private static List<CacheParameterGroup> defaultParameterGroups() {
        List<CacheParameterGroup> defaults = new ArrayList<>();
        for (String family : PARAMETER_GROUP_FAMILIES) {
            defaults.add(new CacheParameterGroup("default." + family, family,
                    "Default parameter group for " + family));
            if (CLUSTER_MODE_FAMILIES.contains(family)) {
                defaults.add(new CacheParameterGroup("default." + family + ".cluster.on", family,
                        "Customized default parameter group for " + family + " with cluster mode on"));
            }
        }
        return defaults;
    }

    private static void validateParameterGroupName(String name) {
        if (name == null || name.isBlank() || !PARAMETER_GROUP_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter CacheParameterGroupName is not a valid identifier. Identifiers must "
                            + "begin with a letter; must contain only ASCII letters, digits, and hyphens; "
                            + "and must not end with a hyphen or contain two consecutive hyphens.", 400);
        }
    }

    /**
     * One monitor per group name, taken by every writer before it reads. Guarding the record itself
     * would be too late: a create has no record yet, so two of them could both pass the existence
     * check, and a modify that resolved its record before locking would write back one a concurrent
     * delete had already removed.
     */
    private Object lockFor(String parameterGroupName) {
        return parameterGroupLocks.computeIfAbsent(parameterGroupName, key -> new Object());
    }

    public CacheParameterGroup createCacheParameterGroup(String name, String family,
                                                         String description, Map<String, String> tags) {
        validateParameterGroupName(name);
        if (family == null || !PARAMETER_GROUP_FAMILIES.contains(family)) {
            throw new AwsException("InvalidParameterValue",
                    "CacheParameterGroupFamily " + family + " is not a valid parameter group family.", 400);
        }

        synchronized (lockFor(name)) {
            if (parameterGroups.get(name).isPresent()) {
                throw new AwsException("CacheParameterGroupAlreadyExists",
                        "Parameter group " + name + " already exists", 400);
            }
            CacheParameterGroup group = new CacheParameterGroup(name, family, description);
            if (tags != null) {
                group.setTags(new LinkedHashMap<>(tags));
            }
            parameterGroups.put(name, group);
            LOG.infov("Created cache parameter group {0} ({1})", name, family);
            return group;
        }
    }

    /** Every group, or the one named. The published defaults are listed, as AWS lists them. */
    public List<CacheParameterGroup> describeCacheParameterGroups(String name) {
        if (name == null || name.isBlank()) {
            List<CacheParameterGroup> all = new ArrayList<>(defaultParameterGroups());
            all.addAll(parameterGroups.scan(key -> true));
            return all;
        }
        return List.of(requireParameterGroup(name));
    }

    private static boolean isDefaultParameterGroup(String name) {
        return defaultParameterGroups().stream().anyMatch(group -> group.getName().equals(name));
    }

    /** The group by that name, whether stored or one of the published defaults. */
    public java.util.Optional<CacheParameterGroup> findParameterGroup(String name) {
        return parameterGroups.get(name)
                .or(() -> defaultParameterGroups().stream()
                        .filter(group -> group.getName().equals(name))
                        .findFirst());
    }

    public CacheParameterGroup requireParameterGroup(String name) {
        return findParameterGroup(name)
                .orElseThrow(() -> new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroup " + name + " not found.", 404));
    }

    /**
     * Records the parameters a caller sets. floci does not carry AWS's per-family catalogue of
     * parameter names, so it cannot tell a real name from a typo and does not try: rejecting names
     * missing from a partial catalogue would refuse configurations AWS accepts.
     */
    public void modifyCacheParameterGroup(String name, Map<String, String> parameters) {
        synchronized (lockFor(name)) {
            // Read inside the lock: a record resolved before it could have been deleted since, and
            // writing it back would restore the group the delete removed.
            CacheParameterGroup group = parameterGroups.get(name).orElse(null);
            if (group == null) {
                // Absent from the store means one of two different things, and they do not share
                // an error: a published default was never stored, whereas anything else is gone.
                if (isDefaultParameterGroup(name)) {
                    throw new AwsException("InvalidParameterValue",
                            "The parameter group " + name + " is a default group and cannot be modified.", 400);
                }
                throw new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroup not found: " + name, 404);
            }
            Map<String, String> updated = new LinkedHashMap<>(group.getParameters());
            updated.putAll(parameters);
            group.setParameters(updated);
            parameterGroups.put(name, group);
        }
        LOG.debugv("Modified {0} parameter(s) on cache parameter group {1}", parameters.size(), name);
    }

    public void deleteCacheParameterGroup(String name) {
        validateParameterGroupName(name);
        synchronized (lockFor(name)) {
            if (parameterGroups.get(name).isEmpty()) {
                // AWS emits this without the space after the type name. Deliberately not
                // corrected: matching it is the point, and each action words this differently —
                // describe says "CacheParameterGroup <name> not found." and modify says
                // "CacheParameterGroup not found: <name>".
                throw new AwsException("CacheParameterGroupNotFound",
                        "CacheParameterGroupnot found: " + name, 404);
            }
            parameterGroups.delete(name);
        }
        LOG.infov("Deleted cache parameter group {0}", name);
    }
}
