package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core ElastiCache business logic — replication groups and users.
 * Creates Valkey containers and auth proxies on group creation.
 */
@ApplicationScoped
public class ElastiCacheService {

    private static final Logger LOG = Logger.getLogger(ElastiCacheService.class);

    private final StorageBackend<String, ReplicationGroup> groups;
    private final StorageBackend<String, ElastiCacheUser> users;
    private final StorageBackend<String, CacheSubnetGroup> subnetGroups;
    private final ElastiCacheContainerManager containerManager;
    private final ElastiCacheProxyManager proxyManager;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningGroupIds = ConcurrentHashMap.newKeySet();

    @Inject
    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              Ec2Service ec2Service,
                              RegionResolver regionResolver,
                              StorageFactory storageFactory,
                              EmulatorConfig config) {
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.config = config;
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
        this.users = storageFactory.create("elasticache", "elasticache-users.json",
                new TypeReference<Map<String, ElastiCacheUser>>() {});
        this.subnetGroups = storageFactory.create("elasticache", "elasticache-subnet-groups.json",
                new TypeReference<Map<String, CacheSubnetGroup>>() {});
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
                // A replication group record is metadata: its id, endpoint host and proxy port
                // are derived from configuration and need no Docker, so the group is created and
                // reaches 'available' even when no daemon is reachable. Only connecting to the
                // cache needs the container.
                handle = containerManager.tryStart(groupId, image);

                String endpointHost = resolveEndpointHost();
                Endpoint endpoint = new Endpoint(endpointHost, proxyPort);
                ReplicationGroup group = new ReplicationGroup(
                        groupId, description, ReplicationGroupStatus.AVAILABLE,
                        authMode, endpoint, Instant.now(), proxyPort);
                group.setAuthToken(authToken);

                if (handle != null) {
                    group.setContainerId(handle.getContainerId());
                    group.setContainerHost(handle.getHost());
                    group.setContainerPort(handle.getPort());

                    proxyManager.startProxy(groupId, authMode, proxyPort,
                            handle.getHost(), handle.getPort(),
                            (username, password) -> validatePassword(groupId, username, password));
                } else {
                    LOG.warnv("Replication group {0} created without a backing cache container: no "
                            + "Docker daemon is reachable. Metadata operations work; connections to "
                            + "the cache do not until a daemon appears.", groupId);
                }

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
                                      List<String> passwords, String accessString) {
        if (users.get(userId).isPresent()) {
            throw new AwsException("UserAlreadyExistsFault",
                    "User " + userId + " already exists.", 400);
        }

        ElastiCacheUser user = new ElastiCacheUser(
                userId, userName, authMode,
                passwords != null ? passwords : List.of(),
                accessString != null ? accessString : "on ~* +@all",
                "active", Instant.now());

        users.put(userId, user);
        LOG.infov("ElastiCache user {0} created with authMode={1}", userId, authMode);
        return user;
    }

    public ElastiCacheUser getUser(String userId) {
        return users.get(userId).orElseThrow(() ->
                new AwsException("UserNotFoundFault", "User " + userId + " not found.", 404));
    }

    public Collection<ElastiCacheUser> listUsers(String filterUserId) {
        if (filterUserId != null && !filterUserId.isBlank()) {
            return users.get(filterUserId)
                    .map(List::of)
                    .orElseThrow(() -> new AwsException("UserNotFoundFault",
                            "User " + filterUserId + " not found.", 404));
        }
        return users.scan(k -> true);
    }

    public ElastiCacheUser modifyUser(String userId, List<String> passwords) {
        ElastiCacheUser user = getUser(userId);
        if (passwords != null) {
            user.setPasswords(passwords);
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

    // ── Cache Subnet Groups ─────────────────────────────────────────────────────

    public CacheSubnetGroup createCacheSubnetGroup(String name, String description, List<String> subnetIds) {
        return createCacheSubnetGroup(name, description, subnetIds, regionResolver.getDefaultRegion());
    }

    public CacheSubnetGroup createCacheSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        return createCacheSubnetGroup(name, description, subnetIds, region, Map.of());
    }

    public CacheSubnetGroup createCacheSubnetGroup(String name, String description, List<String> subnetIds,
                                                   String region, Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter CacheSubnetGroupName.", 400);
        }
        if (subnetGroups.get(name).isPresent()) {
            throw new AwsException("CacheSubnetGroupAlreadyExists",
                    "Cache subnet group " + name + " already exists.", 400);
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter SubnetIds.", 400);
        }

        CacheSubnetGroup group = buildCacheSubnetGroup(name, description, subnetIds, effectiveRegion(region));
        group.setTags(tags);
        subnetGroups.put(name, group);
        LOG.infov("Cache subnet group {0} created with {1} subnets", name, String.valueOf(subnetIds.size()));
        return group;
    }

    public Collection<CacheSubnetGroup> listCacheSubnetGroups(String filterName) {
        if (filterName != null && !filterName.isBlank()) {
            return List.of(getCacheSubnetGroup(filterName));
        }
        return subnetGroups.scan(k -> true);
    }

    public CacheSubnetGroup getCacheSubnetGroup(String name) {
        return subnetGroups.get(name).orElseThrow(() ->
                new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache subnet group " + name + " not found.", 404));
    }

    public CacheSubnetGroup modifyCacheSubnetGroup(String name, String description, List<String> subnetIds) {
        return modifyCacheSubnetGroup(name, description, subnetIds, regionResolver.getDefaultRegion());
    }

    public CacheSubnetGroup modifyCacheSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        CacheSubnetGroup existing = getCacheSubnetGroup(name);
        String effectiveDescription = description != null && !description.isBlank()
                ? description
                : existing.getDescription();
        List<String> effectiveSubnetIds = subnetIds != null && !subnetIds.isEmpty()
                ? subnetIds
                : existing.getSubnetIds();

        CacheSubnetGroup group = buildCacheSubnetGroup(name, effectiveDescription, effectiveSubnetIds, effectiveRegion(region));
        // buildCacheSubnetGroup returns a fresh object, so anything Modify does not itself take
        // has to be carried over explicitly. Tags are not a ModifyCacheSubnetGroup parameter at
        // all - AWS changes them only through Add/RemoveTagsToResource - so dropping them here
        // would silently untag a group on every unrelated modify.
        group.setTags(existing.getTags());
        subnetGroups.put(name, group);
        return group;
    }

    public void deleteCacheSubnetGroup(String name) {
        if (subnetGroups.get(name).isEmpty()) {
            throw new AwsException("CacheSubnetGroupNotFoundFault",
                    "Cache subnet group " + name + " not found.", 404);
        }
        subnetGroups.delete(name);
        LOG.infov("Cache subnet group {0} deleted", name);
    }

    private CacheSubnetGroup buildCacheSubnetGroup(String name, String description, List<String> subnetIds, String region) {
        List<Subnet> resolvedSubnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        if (resolvedSubnets.size() != subnetIds.size()) {
            throw new AwsException("InvalidSubnet",
                    "One or more subnets for cache subnet group " + name + " do not exist.", 400);
        }

        String vpcId = resolvedSubnets.getFirst().getVpcId();
        boolean sameVpc = resolvedSubnets.stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidParameterValue",
                    "Cache subnet group " + name + " contains subnets in multiple VPCs.", 400);
        }

        Map<String, String> subnetAvailabilityZones = new LinkedHashMap<>();
        for (Subnet subnet : resolvedSubnets) {
            subnetAvailabilityZones.put(subnet.getSubnetId(), subnet.getAvailabilityZone());
        }

        CacheSubnetGroup group = new CacheSubnetGroup(name, description, vpcId, subnetIds, subnetAvailabilityZones);
        group.setArn(regionResolver.buildArn("elasticache", region, "subnetgroup:" + name));
        return group;
    }

    // ── Tags ────────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String resourceName) {
        return Map.copyOf(resolveTagHandle(resourceName).tags());
    }

    public Map<String, String> addTagsToResource(String resourceName, Map<String, String> tags) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        if (tags != null) {
            updated.putAll(tags);
        }
        handle.save().accept(updated);
        return Map.copyOf(updated);
    }

    public Map<String, String> removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        TagHandle handle = resolveTagHandle(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(handle.tags());
        if (tagKeys != null) {
            tagKeys.forEach(updated::remove);
        }
        handle.save().accept(updated);
        return Map.copyOf(updated);
    }

    /** A resolved tag target: its current tags plus a sink that persists an updated map. */
    private record TagHandle(Map<String, String> tags, java.util.function.Consumer<Map<String, String>> save) {}

    /**
     * Resolves an ElastiCache tagging {@code ResourceName} to the resource it names.
     *
     * <p>ElastiCache ARNs are {@code arn:aws:elasticache:<region>:<account>:<type>:<name>}, so the
     * type is read off the ARN rather than guessed: a new taggable ElastiCache resource is one
     * more branch here plus a {@code tags} field on its model, and needs no change anywhere else
     * (the query handler and the estate-wide tag scanner are both type-agnostic).
     *
     * <p>Only {@code subnetgroup} is resolvable today, because it is the only ElastiCache model
     * floci stores that carries an ARN of its own; replication groups, users and cache clusters
     * have no ARN field yet, so there is nothing for a tagging call to name.
     */
    private TagHandle resolveTagHandle(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "The request must contain the parameter ResourceName.", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidARN",
                    "ARN " + resourceName + " is malformed.", 400);
        }
        if (!"elasticache".equals(arn.service())) {
            throw new AwsException("InvalidARN",
                    "ARN " + resourceName + " does not refer to an ElastiCache resource.", 400);
        }
        String[] resource = arn.resource().split(":", 2);
        if (resource.length != 2 || resource[1].isBlank()) {
            throw new AwsException("InvalidARN",
                    "ARN " + resourceName + " is malformed.", 400);
        }
        String type = resource[0];
        String name = resource[1];
        if ("subnetgroup".equals(type)) {
            CacheSubnetGroup group = getCacheSubnetGroup(name);
            return new TagHandle(group.getTags(), updated -> {
                CacheSubnetGroup current = getCacheSubnetGroup(name);
                current.setTags(updated);
                subnetGroups.put(name, current);
            });
        }
        throw new AwsException("InvalidARN",
                "ElastiCache resource type " + type + " does not support tagging in this emulator.", 400);
    }

    private String effectiveRegion(String region) {
        return region == null || region.isBlank() ? regionResolver.getDefaultRegion() : region;
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
}
