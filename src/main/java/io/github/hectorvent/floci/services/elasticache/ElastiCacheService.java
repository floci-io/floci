package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ElastiCacheUser;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Core ElastiCache business logic — replication groups and users.
 * Creates Valkey containers and auth proxies on group creation.
 */
@ApplicationScoped
public class ElastiCacheService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(ElastiCacheService.class);

    private final StorageBackend<String, ReplicationGroup> groups;
    private final StorageBackend<String, ElastiCacheUser> users;
    private final AccountAwareStorageBackend<CacheParameterGroup> parameterGroups;
    private final StorageBackend<String, CacheSubnetGroup> subnetGroups;
    private final ElastiCacheContainerManager containerManager;
    private final ElastiCacheProxyManager proxyManager;
    private final ValkeyClusterFormation clusterFormation;
    private final EmulatorConfig config;
    private final KmsService kmsService;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningGroupIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> parameterGroupLocks = new ConcurrentHashMap<>();
    /**
     * Parameter groups claimed by in-flight creates, keyed by account and name (see
     * {@link #parameterGroupReservationKey}) with the number of creates holding each. A
     * replication group is only persisted once provisioning finishes, so the stored-groups scan
     * alone would let a delete slip in during that window and leave the new group pointing at a
     * parameter group that no longer exists.
     */
    private final ConcurrentHashMap<String, Integer> reservedParameterGroups = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheService(ElastiCacheContainerManager containerManager,
                              ElastiCacheProxyManager proxyManager,
                              ValkeyClusterFormation clusterFormation,
                              StorageFactory storageFactory,
                              EmulatorConfig config,
                              Ec2Service ec2Service,
                              RegionResolver regionResolver,
                              KmsService kmsService) {
        this.kmsService = kmsService;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.clusterFormation = clusterFormation;
        this.config = config;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.groups = storageFactory.create("elasticache", "elasticache-groups.json",
                new TypeReference<Map<String, ReplicationGroup>>() {});
        this.users = storageFactory.create("elasticache", "elasticache-users.json",
                new TypeReference<Map<String, ElastiCacheUser>>() {});
        this.parameterGroups = storageFactory.create("elasticache", "elasticache-parameter-groups.json",
                new TypeReference<Map<String, CacheParameterGroup>>() {});
        this.subnetGroups = storageFactory.create("elasticache", "elasticache-subnet-groups.json",
                new TypeReference<Map<String, CacheSubnetGroup>>() {});
    }

    /**
     * The CreateReplicationGroup parameters floci models. Anything unset falls back to the
     * defaults a bare create has always had: one non-cluster node behind one proxy port.
     */
    public record CreateReplicationGroupRequest(
            String replicationGroupId,
            String description,
            AuthMode authMode,
            String authToken,
            String region,
            String engine,
            String engineVersion,
            String cacheNodeType,
            String cacheParameterGroupName,
            String cacheSubnetGroupName,
            String clusterMode,
            Integer numNodeGroups,
            Integer replicasPerNodeGroup,
            Integer numCacheClusters,
            Boolean automaticFailoverEnabled,
            Boolean multiAzEnabled,
            ReplicationGroupSettings settings,
            Map<String, String> tags) {
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken, String region) {
        return createReplicationGroup(groupId, description, authMode, authToken, region,
                ReplicationGroupSettings.defaults(), Map.of());
    }

    public ReplicationGroup createReplicationGroup(String groupId, String description,
                                                   AuthMode authMode, String authToken, String region,
                                                   ReplicationGroupSettings settings,
                                                   Map<String, String> tags) {
        return createReplicationGroup(new CreateReplicationGroupRequest(groupId, description,
                authMode, authToken, region, null, null, null, null, null, null,
                null, null, null, null, null, settings, tags));
    }

    public ReplicationGroup createReplicationGroup(CreateReplicationGroupRequest request) {
        String groupId = request.replicationGroupId();
        ReplicationGroupSettings settings = request.settings() != null
                ? request.settings() : ReplicationGroupSettings.defaults();
        settings.validate();
        // resolved with the other validations, before a port is taken or a container started
        ReplicationGroupSettings resolvedSettings =
                settings.withKmsKeyId(resolveKmsKeyArn(settings.kmsKeyId(), request.region()));
        // Held until the group is persisted (or the attempt fails) so a concurrent
        // DeleteCacheParameterGroup sees the dependency before the stored-groups scan can.
        String parameterGroupReservation = reserveParameterGroup(request.cacheParameterGroupName());
        try {
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
                if (resolveClusterEnabled(request)) {
                    return provisionClusterModeGroup(request, resolvedSettings);
                }
                return provisionSingleNodeGroup(request, resolvedSettings);
            } finally {
                provisioningGroupIds.remove(groupId);
            }
        } finally {
            releaseParameterGroup(parameterGroupReservation);
        }
    }

    /**
     * Checks the parameter group exists and marks it in use for the calling create, under the
     * same monitor {@link #deleteCacheParameterGroup} takes, so the existence check and the claim
     * can't straddle a delete. Returns the reservation key, or {@code null} when the request
     * names no parameter group, for the matching {@link #releaseParameterGroup} call.
     */
    private String reserveParameterGroup(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return null;
        }
        synchronized (lockFor(requestedName)) {
            requireParameterGroup(requestedName);
            String reservation = parameterGroupReservationKey(requestedName);
            reservedParameterGroups.merge(reservation, 1, Integer::sum);
            return reservation;
        }
    }

    private void releaseParameterGroup(String reservation) {
        if (reservation == null) {
            return;
        }
        reservedParameterGroups.computeIfPresent(reservation,
                (key, holders) -> holders > 1 ? holders - 1 : null);
    }

    /**
     * Parameter groups are stored per account, so a reservation is scoped the same way: the
     * account the store is currently prefixing keys with, then the name. Without that, one
     * account's in-flight create would block another account's delete of its own group of the
     * same name.
     */
    private String parameterGroupReservationKey(String name) {
        return parameterGroups.accountId() + "/" + name;
    }

    private ReplicationGroup provisionSingleNodeGroup(CreateReplicationGroupRequest request,
                                                      ReplicationGroupSettings resolvedSettings) {
        String groupId = request.replicationGroupId();
        AuthMode authMode = request.authMode();
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
                    groupId, request.description(), ReplicationGroupStatus.AVAILABLE,
                    authMode, endpoint, Instant.now(), proxyPort);
            group.setContainerId(handle.getContainerId());
            group.setContainerHost(handle.getHost());
            group.setContainerPort(handle.getPort());
            group.setAuthToken(request.authToken());
            group.setArn(regionResolver.buildArn("elasticache", request.region(),
                    "replicationgroup:" + groupId));
            group.setRegion(request.region());
            group.setNumCacheClusters(request.numCacheClusters() != null
                    ? validateRange("NumCacheClusters", request.numCacheClusters(), 1, 6)
                    : 1);
            applyCommonAttributes(group, request, resolvedSettings);

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
    }

    private ReplicationGroup provisionClusterModeGroup(CreateReplicationGroupRequest request,
                                                       ReplicationGroupSettings resolvedSettings) {
        String groupId = request.replicationGroupId();
        AuthMode authMode = request.authMode();
        int numNodeGroups = request.numNodeGroups() != null
                ? validateNumNodeGroups(request.numNodeGroups())
                : 1;
        int replicasPerNodeGroup = request.replicasPerNodeGroup() != null
                ? validateRange("ReplicasPerNodeGroup", request.replicasPerNodeGroup(), 0, 5)
                : 0;
        String image = config.services().elasticache().defaultImage();
        String endpointHost = resolveClusterAnnounceHost();
        String announceIp = resolveAnnounceIp(endpointHost);

        LOG.infov("Creating cluster-mode replication group {0} with {1} node group(s), {2} replica(s) each",
                groupId, String.valueOf(numNodeGroups), String.valueOf(replicasPerNodeGroup));

        List<ClusterNode> nodes = new ArrayList<>();
        List<ElastiCacheContainerHandle> handles = new ArrayList<>();
        List<String> startedProxyKeys = new ArrayList<>();
        String inFlightMemberId = null;
        try {
            for (int shard = 0; shard < numNodeGroups; shard++) {
                String nodeGroupId = String.format("%04d", shard + 1);
                int[] slots = ValkeyClusterFormation.slotRange(shard, numNodeGroups);
                for (int member = 0; member <= replicasPerNodeGroup; member++) {
                    String memberId = groupId + "-" + nodeGroupId + "-" + String.format("%03d", member + 1);
                    nodes.add(new ClusterNode(memberId, nodeGroupId, member == 0,
                            allocateProxyPort(), slots[0] + "-" + slots[1]));
                }
            }

            List<ValkeyClusterFormation.Node> formationNodes = new ArrayList<>(nodes.size());
            for (ClusterNode node : nodes) {
                inFlightMemberId = node.getMemberClusterId();
                ElastiCacheContainerHandle handle = containerManager.start(
                        node.getMemberClusterId(), image,
                        clusterNodeFlags(endpointHost, announceIp, node.getProxyPort()));
                inFlightMemberId = null;
                handles.add(handle);
                node.setContainerId(handle.getContainerId());
                node.setContainerHost(handle.getHost());
                node.setContainerPort(handle.getPort());
                String networkIp = handle.getNetworkIp() != null ? handle.getNetworkIp() : handle.getHost();
                formationNodes.add(new ValkeyClusterFormation.Node(
                        handle.getHost(), handle.getPort(), networkIp,
                        Integer.parseInt(node.getNodeGroupId()) - 1, node.isPrimary()));
            }

            clusterFormation.form(groupId, formationNodes, numNodeGroups);

            for (int i = 0; i < nodes.size(); i++) {
                ClusterNode node = nodes.get(i);
                ElastiCacheContainerHandle handle = handles.get(i);
                proxyManager.startProxy(node.getMemberClusterId(), authMode, node.getProxyPort(),
                        handle.getHost(), handle.getPort(),
                        (username, password) -> validatePassword(groupId, username, password));
                startedProxyKeys.add(node.getMemberClusterId());
            }

            Endpoint configurationEndpoint = new Endpoint(endpointHost, nodes.getFirst().getProxyPort());
            ReplicationGroup group = new ReplicationGroup(
                    groupId, request.description(), ReplicationGroupStatus.AVAILABLE,
                    authMode, configurationEndpoint, Instant.now(), nodes.getFirst().getProxyPort());
            group.setAuthToken(request.authToken());
            group.setArn(regionResolver.buildArn("elasticache", request.region(),
                    "replicationgroup:" + groupId));
            group.setRegion(request.region());
            group.setClusterEnabled(true);
            group.setNumNodeGroups(numNodeGroups);
            group.setReplicasPerNodeGroup(replicasPerNodeGroup);
            group.setNumCacheClusters(nodes.size());
            group.setClusterNodes(nodes);
            applyCommonAttributes(group, request, resolvedSettings);

            groups.put(groupId, group);
            LOG.infov("Cluster-mode replication group {0} created, configuration endpoint={1}:{2}",
                    groupId, endpointHost, String.valueOf(configurationEndpoint.port()));
            return group;
        } catch (RuntimeException e) {
            LOG.warnv("Cluster-mode replication group {0} provisioning failed, rolling back: {1}",
                    groupId, e.getMessage());
            rollbackClusterModeGroup(groupId, startedProxyKeys, handles, inFlightMemberId,
                    nodes.stream().map(ClusterNode::getProxyPort).toList());
            throw e;
        }
    }

    private void applyCommonAttributes(ReplicationGroup group, CreateReplicationGroupRequest request,
                                       ReplicationGroupSettings resolvedSettings) {
        group.setEngine(request.engine() != null && !request.engine().isBlank()
                ? normalizeEngine(request.engine())
                : defaultEngineForImage());
        group.setEngineVersion(request.engineVersion() != null && !request.engineVersion().isBlank()
                ? request.engineVersion()
                : ("valkey".equals(group.getEngine()) ? "8.1" : "7.1"));
        group.setCacheNodeType(request.cacheNodeType() != null && !request.cacheNodeType().isBlank()
                ? request.cacheNodeType()
                : "cache.t4g.micro");
        group.setCacheParameterGroupName(request.cacheParameterGroupName());
        group.setCacheSubnetGroupName(request.cacheSubnetGroupName());
        group.setAutomaticFailoverEnabled(Boolean.TRUE.equals(request.automaticFailoverEnabled()));
        group.setMultiAzEnabled(Boolean.TRUE.equals(request.multiAzEnabled()));
        group.setSnapshotWindow(ReplicationGroupSettings.DEFAULT_SNAPSHOT_WINDOW);
        resolvedSettings.applyTo(group);
        if (request.tags() != null && !request.tags().isEmpty()) {
            group.setTags(new LinkedHashMap<>(request.tags()));
        }
    }

    private String defaultEngineForImage() {
        String image = config.services().elasticache().defaultImage();
        return image != null && image.contains("valkey") ? "valkey" : "redis";
    }

    /**
     * Cluster mode follows AWS's signal — the parameter group — plus the request shapes that
     * imply it: an explicit ClusterMode, or more than one node group. ClusterMode
     * {@code compatible} — the mid-migration state — is deliberately not modelled; it enables
     * cluster mode only when one of the other signals also does.
     */
    private boolean resolveClusterEnabled(CreateReplicationGroupRequest request) {
        if ("enabled".equalsIgnoreCase(request.clusterMode())) {
            return true;
        }
        String parameterGroupName = request.cacheParameterGroupName();
        if (parameterGroupName != null) {
            if (parameterGroupName.endsWith(".cluster.on")) {
                return true;
            }
            boolean customClusterGroup = findParameterGroup(parameterGroupName)
                    .map(group -> "yes".equalsIgnoreCase(group.getParameters().get("cluster-enabled")))
                    .orElse(false);
            if (customClusterGroup) {
                return true;
            }
        }
        return request.numNodeGroups() != null && request.numNodeGroups() > 1;
    }

    /**
     * Nodes announce the cluster announce hostname as their preferred endpoint, so MOVED/ASK
     * redirects and CLUSTER SLOTS hand clients the exact name floci reports as the configuration
     * endpoint — how (or whether) that name resolves inside floci's own container is irrelevant.
     * The best-effort IPv4 stays announced for consumers that read the address fields instead.
     */
    private static List<String> clusterNodeFlags(String endpointHost, String announceIp, int proxyPort) {
        return List.of(
                "--cluster-enabled", "yes",
                "--cluster-announce-hostname", endpointHost,
                "--cluster-preferred-endpoint-type", "hostname",
                "--cluster-announce-client-ipv4", announceIp,
                "--cluster-announce-port", String.valueOf(proxyPort),
                "--cluster-announce-bus-port", "16379");
    }

    private static String resolveAnnounceIp(String endpointHost) {
        try {
            for (InetAddress address : InetAddress.getAllByName(endpointHost)) {
                if (address instanceof Inet4Address) {
                    return address.getHostAddress();
                }
            }
        } catch (UnknownHostException e) {
            LOG.warnv("Could not resolve {0} to an IPv4 for cluster announce, using 127.0.0.1: {1}",
                    endpointHost, e.getMessage());
        }
        return "127.0.0.1";
    }

    private static int validateRange(String parameterName, int value, int min, int max) {
        if (value < min || value > max) {
            throw new AwsException("InvalidParameterValue",
                    parameterName + " must be between " + min + " and " + max + ".", 400);
        }
        return value;
    }

    /** 500 is AWS's raised NumNodeGroups quota (the default is 90; 500 with a limit increase). */
    private static final int MAX_NODE_GROUPS = 500;

    private static int validateNumNodeGroups(int value) {
        if (value > MAX_NODE_GROUPS) {
            throw new AwsException("NodeGroupsPerReplicationGroupQuotaExceeded",
                    "The request cannot be processed because it would exceed the maximum allowed"
                            + " number of node groups (shards) in a single replication group."
                            + " The maximum is " + MAX_NODE_GROUPS + ".", 400);
        }
        return validateRange("NumNodeGroups", value, 1, MAX_NODE_GROUPS);
    }

    private void rollbackClusterModeGroup(String groupId, List<String> startedProxyKeys,
                                          List<ElastiCacheContainerHandle> handles,
                                          String inFlightMemberId, Collection<Integer> proxyPorts) {
        for (String proxyKey : startedProxyKeys) {
            try {
                proxyManager.stopProxy(proxyKey);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy {0} during rollback of group {1}: {2}",
                        proxyKey, groupId, e.getMessage());
            }
        }
        for (ElastiCacheContainerHandle handle : handles) {
            try {
                containerManager.stop(handle);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container {0} during rollback of group {1}: {2}",
                        handle.getContainerId(), groupId, e.getMessage());
            }
        }
        if (inFlightMemberId != null) {
            try {
                containerManager.stopByGroupId(inFlightMemberId);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping in-flight container {0} during rollback of group {1}: {2}",
                        inFlightMemberId, groupId, e.getMessage());
            }
        }
        for (int port : proxyPorts) {
            releaseProxyPort(port);
        }
    }

    /**
     * Brings the data plane back up for cluster-mode groups restored from disk. Invoked from
     * {@code EmulatorLifecycle} after {@code storageFactory.loadAll()}, alongside the other
     * services that restore persisted runtime. Only topology survives a restart — containers,
     * proxies and port reservations are process-local — so each group is re-provisioned from
     * its persisted node list, and a group whose data plane cannot come back is reported
     * {@code create-failed} instead of available. Cluster-mode-disabled groups are deliberately
     * out of scope: they keep their pre-existing behaviour of reporting available after a
     * restart without a restored runtime.
     *
     * <p>Container restarts and cluster formation can take a readiness timeout and a formation
     * timeout per group, so the data-plane work runs in the background and must not delay
     * emulator readiness. Groups are marked {@code creating} synchronously — each node's proxy
     * port is reserved at the same time, before any request can claim it — and flip to
     * {@code available} or {@code create-failed} as their restoration finishes.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        List<ReplicationGroup> toRestore = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            if (!group.isClusterEnabled() || group.getClusterNodes().isEmpty()
                    || group.getStatus() == ReplicationGroupStatus.DELETING) {
                continue;
            }
            List<Integer> reserved = new ArrayList<>();
            try {
                for (ClusterNode node : group.getClusterNodes()) {
                    node.setProxyPort(reserveOrAllocateProxyPort(node.getProxyPort()));
                    reserved.add(node.getProxyPort());
                }
                group.setStatus(ReplicationGroupStatus.CREATING);
                toRestore.add(group);
            } catch (RuntimeException e) {
                reserved.forEach(this::releaseProxyPort);
                group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
                group.setConfigurationEndpoint(null);
                LOG.warnv(e, "Failed to restore cluster-mode replication group {0}",
                        group.getReplicationGroupId());
            }
            groups.put(group.getReplicationGroupId(), group);
        }
        if (toRestore.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOG.infov("Restoring {0} cluster-mode replication group(s) in the background",
                String.valueOf(toRestore.size()));
        return CompletableFuture.runAsync(() -> toRestore.forEach(this::restoreClusterModeGroup));
    }

    private void restoreClusterModeGroup(ReplicationGroup group) {
        String groupId = group.getReplicationGroupId();
        String image = config.services().elasticache().defaultImage();
        String endpointHost = resolveClusterAnnounceHost();
        String announceIp = resolveAnnounceIp(endpointHost);
        List<ClusterNode> nodes = group.getClusterNodes();

        List<ElastiCacheContainerHandle> handles = new ArrayList<>();
        List<String> startedProxyKeys = new ArrayList<>();
        List<Integer> reservedPorts = nodes.stream().map(ClusterNode::getProxyPort).toList();
        String inFlightMemberId = null;
        try {
            List<ValkeyClusterFormation.Node> formationNodes = new ArrayList<>(nodes.size());
            for (ClusterNode node : nodes) {
                inFlightMemberId = node.getMemberClusterId();
                ElastiCacheContainerHandle handle = containerManager.start(
                        node.getMemberClusterId(), image,
                        clusterNodeFlags(endpointHost, announceIp, node.getProxyPort()));
                inFlightMemberId = null;
                handles.add(handle);
                node.setContainerId(handle.getContainerId());
                node.setContainerHost(handle.getHost());
                node.setContainerPort(handle.getPort());
                String networkIp = handle.getNetworkIp() != null ? handle.getNetworkIp() : handle.getHost();
                formationNodes.add(new ValkeyClusterFormation.Node(
                        handle.getHost(), handle.getPort(), networkIp,
                        Integer.parseInt(node.getNodeGroupId()) - 1, node.isPrimary()));
            }

            clusterFormation.form(groupId, formationNodes, group.getNumNodeGroups());

            for (int i = 0; i < nodes.size(); i++) {
                ClusterNode node = nodes.get(i);
                ElastiCacheContainerHandle handle = handles.get(i);
                proxyManager.startProxy(node.getMemberClusterId(), group.getAuthMode(), node.getProxyPort(),
                        handle.getHost(), handle.getPort(),
                        (username, password) -> validatePassword(groupId, username, password));
                startedProxyKeys.add(node.getMemberClusterId());
            }

            group.setConfigurationEndpoint(new Endpoint(endpointHost, nodes.getFirst().getProxyPort()));
            group.setStatus(ReplicationGroupStatus.AVAILABLE);
            groups.put(groupId, group);
            LOG.infov("Restored cluster-mode replication group {0}: {1} node(s), configuration endpoint={2}:{3}",
                    groupId, String.valueOf(nodes.size()), endpointHost,
                    String.valueOf(group.getConfigurationEndpoint().port()));
        } catch (RuntimeException e) {
            rollbackClusterModeGroup(groupId, startedProxyKeys, handles, inFlightMemberId, reservedPorts);
            for (ClusterNode node : nodes) {
                node.setContainerId(null);
                node.setContainerHost(null);
                node.setContainerPort(0);
            }
            group.setStatus(ReplicationGroupStatus.CREATE_FAILED);
            group.setConfigurationEndpoint(null);
            try {
                groups.put(groupId, group);
            } catch (RuntimeException persistFailure) {
                e.addSuppressed(persistFailure);
            }
            LOG.warnv(e, "Failed to restore cluster-mode replication group {0}", groupId);
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
        // one monitor per group for delete and modify: a modify's read-modify-write could
        // otherwise write the group back after this removed it
        synchronized (lockFor("rg:" + groupId)) {
            ReplicationGroup group = groups.get(groupId).orElseThrow(() ->
                    new AwsException("ReplicationGroupNotFoundFault",
                            "Replication group " + groupId + " not found.", 404));

            group.setStatus(ReplicationGroupStatus.DELETING);
            groups.put(groupId, group);

            if (group.isClusterEnabled() && !group.getClusterNodes().isEmpty()) {
                for (ClusterNode node : group.getClusterNodes()) {
                    proxyManager.stopProxy(node.getMemberClusterId());
                    if (node.getContainerId() != null) {
                        containerManager.stop(new ElastiCacheContainerHandle(
                                node.getContainerId(), node.getMemberClusterId(),
                                node.getContainerHost(), node.getContainerPort()));
                    } else {
                        // Transient container fields are lost across a Floci restart; the
                        // deterministic container name still finds the node.
                        containerManager.stopByGroupId(node.getMemberClusterId());
                    }
                    releaseProxyPort(node.getProxyPort());
                }
            } else {
                proxyManager.stopProxy(groupId);

                if (group.getContainerId() != null) {
                    containerManager.stop(new ElastiCacheContainerHandle(
                            group.getContainerId(), groupId, group.getContainerHost(), group.getContainerPort()));
                }

                releaseProxyPort(group.getProxyPort());
            }
            groups.delete(groupId);
            LOG.infov("Replication group {0} deleted", groupId);
        }
    }

    /**
     * A replication-group member viewed as the cache cluster AWS reports it as. The terraform
     * provider follows {@code MemberClusters} into DescribeCacheClusters, so every member name
     * a describe hands out must answer there.
     */
    public record MemberCacheCluster(ReplicationGroup group, String cacheClusterId,
                                     int port, boolean primary) {}

    public List<MemberCacheCluster> listMemberCacheClusters(String filterClusterId) {
        List<MemberCacheCluster> members = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            for (MemberCacheCluster member : memberCacheClusters(group)) {
                if (filterClusterId == null || filterClusterId.isBlank()
                        || filterClusterId.equals(member.cacheClusterId())) {
                    members.add(member);
                }
            }
        }
        return members;
    }

    public List<MemberCacheCluster> memberCacheClusters(ReplicationGroup group) {
        List<MemberCacheCluster> members = new ArrayList<>();
        if (group.isClusterEnabled() && !group.getClusterNodes().isEmpty()) {
            for (ClusterNode node : group.getClusterNodes()) {
                members.add(new MemberCacheCluster(group, node.getMemberClusterId(),
                        node.getProxyPort(), node.isPrimary()));
            }
            return members;
        }
        for (int i = 1; i <= group.getNumCacheClusters(); i++) {
            members.add(new MemberCacheCluster(group,
                    group.getReplicationGroupId() + "-" + String.format("%03d", i),
                    group.getProxyPort(), i == 1));
        }
        return members;
    }

    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userIdsToAdd,
                                                    List<String> userIdsToRemove) {
        return modifyReplicationGroup(groupId, userIdsToAdd, userIdsToRemove,
                ReplicationGroupSettings.unchanged());
    }

    public ReplicationGroup modifyReplicationGroup(String groupId, List<String> userIdsToAdd,
                                                    List<String> userIdsToRemove,
                                                    ReplicationGroupSettings settings) {
        settings.validate();
        synchronized (lockFor("rg:" + groupId)) {
            ReplicationGroup group = getReplicationGroup(groupId);
            // every check before any change: the store hands out its own object, so a mutation
            // made before a later refusal would stay visible
            if (userIdsToAdd != null) {
                for (String userId : userIdsToAdd) {
                    getUser(userId);
                }
            }
            settings.applyTo(group);
            if (userIdsToAdd != null) {
                group.getAssociatedUserIds().addAll(userIdsToAdd);
            }
            if (userIdsToRemove != null) {
                group.getAssociatedUserIds().removeAll(userIdsToRemove);
            }

            groups.put(groupId, group);
            return group;
        }
    }

    /**
     * A live account takes the key as a key id, key ARN or alias and reports the key ARN on the
     * group; a key it cannot use is one fault whatever the reason.
     */
    private String resolveKmsKeyArn(String kmsKeyId, String region) {
        if (kmsKeyId == null || kmsKeyId.isBlank()) {
            return null;
        }
        if (kmsService == null) {
            throw new IllegalStateException("ElastiCacheService was built without a KmsService; "
                    + "a KmsKeyId cannot be resolved");
        }
        KmsKey key;
        try {
            key = kmsService.describeKey(kmsKeyId, region);
        } catch (AwsException e) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        if (!key.isEnabled() || "PendingDeletion".equals(key.getKeyState())) {
            throw kmsKeyNotAccessible(kmsKeyId);
        }
        return key.getArn();
    }

    private static AwsException kmsKeyNotAccessible(String kmsKeyId) {
        return new AwsException("InvalidParameterValue",
                "KMS key does not exist with key id: " + kmsKeyId, 400);
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

    /**
     * Cluster-mode groups bake this name into MOVED/ASK redirects and topology responses that
     * every client must resolve, unlike the plain endpoint host that clients may override —
     * so a hostname resolvable only inside Floci's Docker network (e.g. a Compose service
     * name) needs the cluster-announce-hostname override.
     */
    private String resolveClusterAnnounceHost() {
        return config.services().elasticache().clusterAnnounceHostname()
                .filter(host -> !host.isBlank())
                .orElseGet(this::resolveEndpointHost);
    }

    private int allocateProxyPort() {
        int base = config.services().elasticache().proxyBasePort();
        int max = config.services().elasticache().proxyMaxPort();
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        // Proxy-port exhaustion is a Floci-side limit, but on the wire it must look like the
        // modeled capacity fault. botocore/smithy declare InsufficientCacheClusterCapacityFault
        // for CreateReplicationGroup: awsQueryError code InsufficientCacheClusterCapacity,
        // HTTP 400, Sender fault. The old InsufficientReplicationGroupCapacity/503 is invented —
        // no such shape exists, so SDK clients cannot map it. Keep the real cause in the log.
        LOG.warnv("ElastiCache proxy port range {0}-{1} exhausted; returning InsufficientCacheClusterCapacity", base, max);
        throw new AwsException("InsufficientCacheClusterCapacity",
                "The requested cache node type is not available in the specified Availability Zone.", 400);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }

    private int reserveOrAllocateProxyPort(int persistedPort) {
        if (persistedPort > 0 && usedPorts.add(persistedPort)) {
            return persistedPort;
        }
        return allocateProxyPort();
    }

    // ── Cache Subnet Groups ───────────────────────────────────────────────────

    /**
     * Creates a subnet group. The VPC and each subnet's availability zone are read from the
     * subnets rather than the request, because that is where AWS takes them from — which makes
     * resolving them against EC2 part of answering, not an optional check.
     */
    public CacheSubnetGroup createCacheSubnetGroup(String name, String description,
                                                   List<String> subnetIds, Map<String, String> tags) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            if (subnetGroups.get(name).isPresent()) {
                throw new AwsException("CacheSubnetGroupAlreadyExists",
                        "Cache subnet group " + name + " already exists.", 400);
            }
            CacheSubnetGroup group = buildSubnetGroup(name, description, subnetIds);
            if (tags != null && !tags.isEmpty()) {
                group.setTags(tags);
            }
            subnetGroups.put(name, group);
            LOG.infov("Created cache subnet group {0} in {1}", name, group.getVpcId());
            return group;
        }
    }

    public List<CacheSubnetGroup> describeCacheSubnetGroups(String name) {
        if (name == null || name.isBlank()) {
            return subnetGroups.scan(key -> true);
        }
        return List.of(subnetGroups.get(name)
                .orElseThrow(() -> new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache subnet group " + name + " not found.", 400)));
    }

    /** Replaces the description and, when subnets are given, the whole subnet set. */
    public CacheSubnetGroup modifyCacheSubnetGroup(String name, String description, List<String> subnetIds) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            CacheSubnetGroup existing = subnetGroups.get(name)
                    .orElseThrow(() -> new AwsException("CacheSubnetGroupNotFoundFault",
                            "Cache subnet group " + name + " not found.", 400));
            String effectiveDescription = description == null ? existing.getDescription() : description;
            CacheSubnetGroup updated;
            if (subnetIds == null || subnetIds.isEmpty()) {
                // No subnets given means none change, so the stored ones are kept as they are:
                // re-resolving them would fail a description-only change if a subnet had since
                // been deleted from EC2.
                updated = new CacheSubnetGroup(name, effectiveDescription, existing.getVpcId(),
                        existing.getSubnetAvailabilityZones());
            } else {
                updated = buildSubnetGroup(name, effectiveDescription, subnetIds);
            }
            updated.setTags(existing.getTags());
            subnetGroups.put(name, updated);
            return updated;
        }
    }

    public void deleteCacheSubnetGroup(String name) {
        validateSubnetGroupName(name);
        synchronized (lockFor("sng:" + name)) {
            if (subnetGroups.get(name).isEmpty()) {
                throw new AwsException("CacheSubnetGroupNotFoundFault",
                        "Cache Subnet Group " + name + " does not exist.", 400);
            }
            subnetGroups.delete(name);
        }
        LOG.infov("Deleted cache subnet group {0}", name);
    }

    private CacheSubnetGroup buildSubnetGroup(String name, String description, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter SubnetIds must be provided.", 400);
        }
        // The caller's region, not the configured default: subnets exist in the region they were
        // created in, and the ARN this group is reported under is built from that same region.
        String region = regionResolver.getRegion();
        List<Subnet> resolved = ec2Service.describeSubnets(region, subnetIds, Map.of());
        if (resolved.size() != subnetIds.size()) {
            throw new AwsException("InvalidParameterValue",
                    "Some input subnets in :[" + String.join(", ", subnetIds) + "] are invalid.", 400);
        }

        String vpcId = resolved.getFirst().getVpcId();
        List<String> foreign = resolved.stream()
                .filter(subnet -> subnet.getVpcId() != null && !vpcId.equals(subnet.getVpcId()))
                .map(Subnet::getSubnetId)
                .toList();
        if (!foreign.isEmpty()) {
            throw new AwsException("InvalidSubnet", "Subnets " + resolved.getFirst().getSubnetId()
                    + " and " + foreign.getFirst() + " are not in the same VPC.", 400);
        }

        // In the order the caller gave them: describeSubnets answers in the store's scan order,
        // which would make the reported order of a group's subnets arbitrary between calls.
        Map<String, String> zoneBySubnet = new LinkedHashMap<>();
        resolved.forEach(subnet -> zoneBySubnet.put(subnet.getSubnetId(), subnet.getAvailabilityZone()));
        Map<String, String> availabilityZones = new LinkedHashMap<>();
        subnetIds.forEach(id -> availabilityZones.put(id, zoneBySubnet.get(id)));
        return new CacheSubnetGroup(name, description, vpcId, availabilityZones);
    }

    /**
     * Subnet group names take the same identifier rule as parameter group names.
     *
     * <p>The not-found faults do not match, though, and the difference is AWS's: the subnet-group
     * fault keeps its {@code Fault} suffix on the wire and is a 400, while the parameter-group one
     * drops the suffix and is a 404. Both are declared that way in the service model.
     */
    private static void validateSubnetGroupName(String name) {
        if (name == null || name.isBlank() || !PARAMETER_GROUP_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue",
                    "The parameter CacheSubnetGroupName is not a valid identifier. Identifiers must "
                            + "begin with a letter; must contain only ASCII letters, digits, and hyphens; "
                            + "and must not end with a hyphen or contain two consecutive hyphens.", 400);
        }
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
            if (isParameterGroupInUse(name)) {
                throw new AwsException("InvalidCacheParameterGroupState",
                        "One or more cache clusters are still members of this parameter group "
                                + name + ", so the group cannot be deleted.", 400);
            }
            parameterGroups.delete(name);
        }
        LOG.infov("Deleted cache parameter group {0}", name);
    }

    /**
     * Whether a stored replication group still references the parameter group by that name, or a
     * create that named it is still provisioning and about to store one.
     */
    private boolean isParameterGroupInUse(String name) {
        return reservedParameterGroups.containsKey(parameterGroupReservationKey(name))
                || groups.scan(key -> true).stream()
                        .anyMatch(group -> name.equals(group.getCacheParameterGroupName()));
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (ReplicationGroup group : groups.scan(k -> true)) {
            if (group.getArn() == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(group.getArn());
            resources.add(new ExplorerResource(
                    group.getArn(), "elasticache:cluster", "elasticache",
                    parsed.region(), parsed.accountId(),
                    group.getCreatedAt() != null ? group.getCreatedAt() : Instant.now(),
                    Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("elasticache:cluster", "elasticache", true));
    }
}
