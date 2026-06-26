package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int REDIS_PORT = 6379;

    // Per the MemoryDB API: a user name must start with a letter and contain only
    // letters, digits and hyphens.
    private static final java.util.regex.Pattern USER_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9\\-]*");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, User> users;
    private final StorageBackend<String, Acl> acls;
    private final MemoryDbContainerManager containerManager;
    private final MemoryDbProxyManager proxyManager;
    private final SigV4Validator sigV4Validator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

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
        if (clusters.get(name).isPresent()) {
            throw new AwsException("ClusterAlreadyExistsFault",
                    "Cluster with specified name already exists.", 400);
        }

        String aclName = spec.getAclName();
        if (aclName == null || aclName.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        requireAclExists(aclName);
        boolean authRequired = isAuthRequired(aclName);

        Cluster cluster = new Cluster();
        cluster.setName(name);
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

        if (config.services().memorydb().mock()) {
            LOG.infov("Creating MemoryDB cluster {0} in mock mode (no container)", name);
            cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), REDIS_PORT));
        } else {
            startBackend(cluster, authRequired);
        }

        clusters.put(name, cluster);
        LOG.infov("MemoryDB cluster {0} created (acl={1}, authRequired={2}), endpoint={3}:{4}",
                name, aclName, String.valueOf(authRequired), cluster.getClusterEndpoint().address(),
                String.valueOf(cluster.getClusterEndpoint().port()));
        return cluster;
    }

    public Cluster getCluster(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ClusterName is required.", 400);
        }
        return clusters.get(name).orElseThrow(() ->
                new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    public Collection<Cluster> describeClusters(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return clusters.get(filterName)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("ClusterNotFoundFault",
                            "Cluster not found.", 404));
        }
        return clusters.scan(k -> true);
    }

    public Cluster updateCluster(String name, String description) {
        Cluster cluster = getCluster(name);
        if (description != null) {
            cluster.setDescription(description);
        }
        clusters.put(name, cluster);
        return cluster;
    }

    public Cluster deleteCluster(String name) {
        Cluster cluster = getCluster(name);
        cluster.setStatus(ClusterStatus.DELETING);

        proxyManager.stopProxy(name);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new MemoryDbContainerHandle(
                    cluster.getContainerId(), name, cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        clusters.delete(name);
        LOG.infov("MemoryDB cluster {0} deleted", name);
        return cluster;
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
        if (DEFAULT_USER.equals(name) || users.get(name).isPresent()) {
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
        user.setStatus(ACTIVE);
        user.setAuthMode(spec.getAuthMode());
        user.setPasswords(spec.getPasswords());
        user.setAccessString(spec.getAccessString());
        user.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        user.setArn(buildArn(region, "user", name));
        user.setCreatedAt(Instant.now());

        users.put(name, user);
        LOG.infov("MemoryDB user {0} created with authMode={1}", name, user.getAuthMode());
        return user;
    }

    public Collection<User> describeUsers(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return users.get(filterName)
                    .map(List::of)
                    .or(() -> DEFAULT_USER.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinDefaultUser(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("UserNotFoundFault", "User not found.", 404));
        }
        List<User> all = new ArrayList<>();
        all.add(builtinDefaultUser(region));
        all.addAll(users.scan(k -> true));
        return all;
    }

    public User deleteUser(String name) {
        if (DEFAULT_USER.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The default user cannot be deleted.", 400);
        }
        User user = users.get(name).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User not found.", 404));
        users.delete(name);
        LOG.infov("MemoryDB user {0} deleted", name);
        return user;
    }

    // ──────────────────────────── ACLs ────────────────────────────

    public Acl createAcl(Acl spec, String region) {
        String name = spec.getName();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValueException", "ACLName is required.", 400);
        }
        if (DEFAULT_ACL.equals(name) || acls.get(name).isPresent()) {
            throw new AwsException("ACLAlreadyExistsFault",
                    "ACL with specified name already exists.", 400);
        }
        if (!spec.getUserNames().contains(DEFAULT_USER)) {
            throw new AwsException("DefaultUserRequired",
                    "A default user is required and must be specified.", 400);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String userName : spec.getUserNames()) {
            if (!seen.add(userName)) {
                throw new AwsException("DuplicateUserNameFault",
                        "Duplicate user name " + userName + " in ACL.", 400);
            }
            if (!userExists(userName)) {
                throw new AwsException("UserNotFoundFault", "User " + userName + " not found.", 404);
            }
        }

        Acl acl = new Acl();
        acl.setName(name);
        acl.setStatus(ACTIVE);
        acl.setUserNames(new ArrayList<>(spec.getUserNames()));
        acl.setMinimumEngineVersion(DEFAULT_ENGINE_VERSION);
        acl.setArn(buildArn(region, "acl", name));
        acl.setCreatedAt(Instant.now());

        acls.put(name, acl);
        LOG.infov("MemoryDB ACL {0} created with users={1}", name, acl.getUserNames());
        return acl;
    }

    public Collection<Acl> describeAcls(String filterName, String region) {
        if (filterName != null && !filterName.isBlank()) {
            return acls.get(filterName)
                    .map(List::of)
                    .or(() -> DEFAULT_ACL.equals(filterName)
                            ? java.util.Optional.of(List.of(builtinOpenAccessAcl(region)))
                            : java.util.Optional.empty())
                    .orElseThrow(() -> new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        }
        List<Acl> all = new ArrayList<>();
        all.add(builtinOpenAccessAcl(region));
        all.addAll(acls.scan(k -> true));
        return all;
    }

    public Acl deleteAcl(String name) {
        if (DEFAULT_ACL.equals(name)) {
            throw new AwsException("InvalidParameterValueException",
                    "The open-access ACL cannot be deleted.", 400);
        }
        Acl acl = acls.get(name).orElseThrow(() ->
                new AwsException("ACLNotFoundFault", "ACL not found.", 404));
        if (!clustersUsingAcl(name).isEmpty()) {
            throw new AwsException("InvalidACLStateFault",
                    "ACL " + name + " is associated with one or more clusters.", 400);
        }
        acls.delete(name);
        LOG.infov("MemoryDB ACL {0} deleted", name);
        return acl;
    }

    /** Names of ACLs that include the given user; used to populate the user response. */
    public List<String> aclNamesForUser(String userName) {
        List<String> result = new ArrayList<>();
        if (DEFAULT_USER.equals(userName)) {
            result.add(DEFAULT_ACL);
        }
        acls.scan(k -> true).stream()
                .filter(a -> a.getUserNames().contains(userName))
                .map(Acl::getName)
                .forEach(result::add);
        return result;
    }

    /** Names of clusters currently referencing the given ACL; used to populate the ACL response. */
    public List<String> clustersUsingAcl(String aclName) {
        return clusters.scan(k -> true).stream()
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
        clusters.put(cluster.getName(), cluster);
        return cluster.getTags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        Cluster cluster = clusterByArn(resourceArn);
        tagKeys.forEach(cluster.getTags()::remove);
        clusters.put(cluster.getName(), cluster);
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
        Cluster cluster = clusters.get(clusterName).orElse(null);
        if (cluster == null) {
            return false;
        }
        String aclName = cluster.getAclName();
        if (DEFAULT_ACL.equals(aclName)) {
            return true;
        }
        Acl acl = acls.get(aclName).orElse(null);
        if (acl == null) {
            return false;
        }
        String target = (username == null || username.isEmpty()) ? DEFAULT_USER : username;
        if (!acl.getUserNames().contains(target)) {
            return false;
        }
        User user = resolveUser(target);
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
    private boolean isAuthRequired(String aclName) {
        if (DEFAULT_ACL.equals(aclName)) {
            return false;
        }
        Acl acl = acls.get(aclName).orElse(null);
        if (acl == null) {
            return false;
        }
        return acl.getUserNames().stream()
                .map(this::resolveUser)
                .filter(java.util.Objects::nonNull)
                .anyMatch(u -> u.getAuthMode() != AuthMode.NO_PASSWORD);
    }

    private void requireAclExists(String aclName) {
        if (!DEFAULT_ACL.equals(aclName) && acls.get(aclName).isEmpty()) {
            throw new AwsException("ACLNotFoundFault", "ACL " + aclName + " not found.", 404);
        }
    }

    private boolean userExists(String name) {
        return DEFAULT_USER.equals(name) || users.get(name).isPresent();
    }

    private User resolveUser(String name) {
        return users.get(name).orElseGet(() -> DEFAULT_USER.equals(name) ? builtinDefaultUser(null) : null);
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
        return clusters.scan(k -> true).stream()
                .filter(c -> resourceArn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ClusterNotFoundFault", "Cluster not found.", 404));
    }

    private void startBackend(Cluster cluster, boolean authRequired) {
        String name = cluster.getName();
        int proxyPort = allocateProxyPort();
        String image = config.services().memorydb().defaultImage();
        LOG.infov("Creating MemoryDB cluster {0} with authRequired={1} on proxy port {2}",
                name, String.valueOf(authRequired), String.valueOf(proxyPort));

        MemoryDbContainerHandle handle = null;
        try {
            handle = containerManager.start(name, image);
            cluster.setClusterEndpoint(new Endpoint(resolveEndpointHost(), proxyPort));
            cluster.setProxyPort(proxyPort);
            cluster.setContainerId(handle.getContainerId());
            cluster.setContainerHost(handle.getHost());
            cluster.setContainerPort(handle.getPort());

            proxyManager.startProxy(name, authRequired, proxyPort,
                    handle.getHost(), handle.getPort(),
                    (username, secret) -> authenticate(name, username, secret));
        } catch (RuntimeException e) {
            LOG.warnv("MemoryDB cluster {0} provisioning failed, rolling back: {1}", name, e.getMessage());
            rollbackBackend(name, handle, proxyPort);
            throw e;
        }
    }

    private void rollbackBackend(String name, MemoryDbContainerHandle handle, int proxyPort) {
        try {
            proxyManager.stopProxy(name);
            if (handle != null) {
                containerManager.stop(handle);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Error rolling back MemoryDB cluster {0}: {1}", name, e.getMessage());
        } finally {
            releaseProxyPort(proxyPort);
        }
    }

    private String buildArn(String region, String resourceType, String name) {
        return "arn:aws:memorydb:" + region + ":" + regionResolver.getAccountId()
                + ":" + resourceType + "/" + name;
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
}
