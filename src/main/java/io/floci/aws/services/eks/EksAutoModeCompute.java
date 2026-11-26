package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.eks.EksAutoModeNodes.Launch;
import io.floci.aws.services.eks.EksAutoModeNodes.NodePool;
import io.floci.aws.services.eks.EksAutoModeNodes.Taint;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EKS Auto Mode's compute capability: while a cluster has it on, pods the scheduler can't place on any node get one,
 * as Auto Mode launches nodes for them on AWS. A pod goes to the first NodePool, by weight then name, whose
 * requirements and taints allow a node it fits on; the pool's node is an EC2 instance of the smallest catalog type
 * that fits its pods, launched in a subnet of the pool's NodeClass with its security groups and the node role's
 * instance profile, and tagged as Auto Mode tags its instances (measured). It joins the cluster as any instance with
 * EKS bootstrap user data does, then carries its pool's labels and taints. As Karpenter does, a pod that one of the
 * pool's nodes has room for, joined or still joining, waits for the scheduler rather than getting another node.
 *
 * <p>The instance runs the EKS optimized Amazon Linux 2023 image of the cluster's Kubernetes version and the
 * node's architecture, the one a managed node group of that version launches. When the image catalog maps that
 * image to one whose kubelet registers its own node, pods really run on it; otherwise the instance joins with the
 * Ready node Floci registers for every joined instance.
 *
 * <p>Like the load balancers, this reconciles on an interval and keeps no state of its own: the instances it
 * launched are found again by their tags. A node that hasn't registered {@link #REGISTRATION_WAIT} after its launch
 * is terminated. Passes and cleanups run one at a time, so a cluster's deletion waits for a pass that is launching
 * for it, and the pass, checking again before and after each launch, makes nothing that outlives the cluster.
 * Turning compute off terminates the cluster's Auto Mode
 * instances, and deleting the cluster terminates them and deletes the instance profiles made for it.
 */
@ApplicationScoped
public class EksAutoModeCompute implements EksAutoModeCleanup {

    private static final Logger LOG = Logger.getLogger(EksAutoModeCompute.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The tags Auto Mode gives its instances (measured). */
    static final String CLUSTER_TAG = "eks:eks-cluster-name";
    static final String POOL_TAG = "eks:kubernetes-node-pool-name";
    /** The taint Karpenter puts on a node it is removing, so nothing more is scheduled there. */
    static final String DISRUPTED_KEY = "karpenter.sh/disrupted";
    static final String DISRUPTED_TAINT = DISRUPTED_KEY + ":NoSchedule";
    static final String NODE_CLASS_TAG = "eks:kubernetes-node-class-name";
    static final String MANAGED_LAUNCH_TAG = "aws:ec2:managed-launch";
    static final String MANAGED_LAUNCH = "eks-auto";
    /** How long a launched node may take to register before its pool may get another. */
    static final Duration REGISTRATION_WAIT = Duration.ofMinutes(3);
    /** How long a deleted cluster waits for its Auto Mode instances to finish terminating, which EC2 does after. */
    static final Duration TERMINATION_WAIT = Duration.ofMinutes(1);

    private final EksService eksService;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2;
    private final IamService iam;
    private final Ec2InstanceTypeCatalog instanceTypes;
    private final EmulatorConfig config;
    private Duration terminationWait = TERMINATION_WAIT;
    private final Clock clock;
    /**
     * By cluster ARN, then instance ID: since when each Auto Mode node has run no workload and been counted on by
     * no pending pod. Kept in memory, so a restart starts each node's wait again.
     */
    private final Map<String, Map<String, Instant>> emptySince = new HashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "eks-auto-mode-compute"));

    @Inject
    public EksAutoModeCompute(EksService eksService, EksClusterManager clusterManager, Ec2Service ec2,
                              IamService iam, Ec2InstanceTypeCatalog instanceTypes, EmulatorConfig config) {
        this(eksService, clusterManager, ec2, iam, instanceTypes, config, Clock.systemUTC());
    }

    /** As above; with the {@code clock} empty nodes are timed by. */
    EksAutoModeCompute(EksService eksService, EksClusterManager clusterManager, Ec2Service ec2, IamService iam,
                       Ec2InstanceTypeCatalog instanceTypes, EmulatorConfig config, Clock clock) {
        this.eksService = eksService;
        this.clusterManager = clusterManager;
        this.ec2 = ec2;
        this.iam = iam;
        this.instanceTypes = instanceTypes;
        this.config = config;
        this.clock = clock;
    }

    /** For tests: how long a deleted cluster waits for its instances to terminate. */
    void terminationWait(Duration wait) {
        this.terminationWait = wait;
    }

    void onStart(@Observes StartupEvent event) {
        // Observing the startup event creates the bean, which starts the reconciler.
    }

    @PostConstruct
    void start() {
        if (!config.services().eks().mock()) {
            scheduler.scheduleAtFixedRate(this::reconcileAll, 10, 10, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void reconcileAll() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : eksService.runningClusters()) {
            Cluster cluster = entry.value();
            String region = eksService.clusterRegion(cluster);
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    if (EksClusterManager.computeOn(cluster)) {
                        reconcile(cluster, region);
                    } else {
                        removeNodes(cluster, region);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "EKS Auto Mode compute of cluster {0} was not reconciled", cluster.getName());
            }
        }
    }

    /**
     * Gives the nodes launched before their pool's labels and taints, terminates those that never registered, then
     * launches what the unschedulable pods no pool node has room for need.
     */
    synchronized void reconcile(Cluster cluster, String region) {
        List<Instance> launched = instancesOf(cluster, region, "pending", "running");
        JsonNode podList = read(cluster, "pods");
        List<EksAutoModeNodes.PendingPod> pods = EksAutoModeNodes.unschedulablePods(podList);
        if (launched.isEmpty()) {
            emptySince.remove(cluster.getArn());
        }
        Map<String, JsonNode> nodesByName = nodesByName(cluster);
        removeStrayNodes(cluster, region, nodesByName);
        if (launched.isEmpty() && pods.isEmpty()) {
            return;
        }
        Map<String, NodePool> pools = new HashMap<>();
        EksAutoModeNodes.nodePools(read(cluster, "nodepools")).forEach(pool -> pools.put(pool.name(), pool));
        // The DaemonSet pods a node runs besides its workload, which room and sizing count; read only for pods to place.
        List<EksAutoModeNodes.PendingPod> daemons = pods.isEmpty() ? List.of()
                : EksAutoModeNodes.daemonPods(read(cluster, "daemonsets"));
        Map<String, long[]> requests = EksAutoModeNodes.requestsByNode(podList);
        Map<String, List<EksAutoModeNodes.BoundPod>> boundPods = EksAutoModeNodes.boundPodsByNode(podList);
        Set<String> busy = EksAutoModeNodes.nodesWithWorkloads(podList);
        List<EksAutoModeNodes.PoolNode> poolNodes = new ArrayList<>();
        Map<String, Instance> idle = new HashMap<>();
        List<Instance> neverRegistered = new ArrayList<>();
        List<Instance> poolGone = new ArrayList<>();
        for (Instance instance : launched) {
            NodePool pool = pools.get(tag(instance, POOL_TAG));
            Optional<Ec2InstanceTypeCatalog.CatalogInstanceType> type = instanceTypes.find(instance.getInstanceType());
            String nodeName = clusterManager.deriveInstanceNodeName(instance, region);
            JsonNode node = nodesByName.get(nodeName);
            boolean starting = instance.getLaunchTime() == null
                    || instance.getLaunchTime().plus(REGISTRATION_WAIT).isAfter(clock.instant());
            boolean joining = node == null && starting;
            if (node == null && !joining) {
                neverRegistered.add(instance);
                continue;
            }
            if (pool == null) {
                // Karpenter removes the nodes of a NodePool that was deleted, or that UpdateClusterConfig dropped.
                poolGone.add(instance);
                continue;
            }
            if (type.isEmpty()) {
                continue;
            }
            if (node != null) {
                try {
                    markNode(cluster, nodeName, node, type.get(), pool);
                } catch (RuntimeException e) {
                    LOG.warnv("EKS Auto Mode node {0} of cluster {1} doesn''t carry its pool''s labels and taints "
                            + "yet: {2}", nodeName, cluster.getName(), e.getMessage());
                }
            }
            long[] used = requests.getOrDefault(nodeName, new long[3]);
            List<Taint> nodeTaints = new ArrayList<>();
            if (node != null) {
                // A node just registered carries not-ready until its status says Ready; while it starts, within the
                // registration wait, that doesn't take its room. One still not ready after it has none.
                node.path("spec").path("taints").forEach(t -> {
                    if (!starting || !EksAutoModeNodes.STARTUP_TAINTS.contains(t.path("key").asText())) {
                        nodeTaints.add(new Taint(t.path("key").asText(), t.path("value").asText(""),
                                t.path("effect").asText()));
                    }
                });
            }
            boolean schedulable = node == null || !node.path("spec").path("unschedulable").asBoolean(false);
            // A joined node offers what its kubelet says is allocatable, less what it reserves; one joining, its
            // instance type's whole size.
            JsonNode allocatable = node == null ? MAPPER.createObjectNode() : node.path("status").path("allocatable");
            long cpu = allocatable.hasNonNull("cpu") ? EksAutoModeNodes.cpuMillis(allocatable.path("cpu").asText())
                    : type.get().vcpu * 1000L;
            long memory = allocatable.hasNonNull("memory") ? EksAutoModeNodes.bytes(allocatable.path("memory").asText())
                    : type.get().memoryMib * 1024L * 1024L;
            int podSlots = allocatable.path("pods").asInt(EksAutoModeNodes.MAX_PODS);
            if (node == null) {
                // A node still joining runs none of its DaemonSet pods yet; they take its room once it does.
                long[] daemon = EksAutoModeNodes.daemonRequests(daemons,
                        EksAutoModeNodes.labels(type.get(), pool, zone(instance)), pool.taints());
                cpu -= daemon[0];
                memory -= daemon[1];
            }
            poolNodes.add(new EksAutoModeNodes.PoolNode(instance.getInstanceId(), pool, type.get(), zone(instance),
                    cpu - used[0], memory - used[1], (int) Math.max(0, podSlots - used[2]), schedulable,
                    List.copyOf(nodeTaints), boundPods.getOrDefault(nodeName, List.of())));
            if (node != null && !busy.contains(nodeName)) {
                idle.put(instance.getInstanceId(), instance);
            }
        }
        if (!neverRegistered.isEmpty()) {
            // A node that never joins would hold its pods' room for good; it goes, and they get another.
            ec2.terminateInstances(region, neverRegistered.stream().map(Instance::getInstanceId).toList());
            LOG.warnv("EKS Auto Mode nodes {0} of cluster {1} didn''t register within {2}: terminated",
                    neverRegistered.stream().map(Instance::getInstanceId).toList(), cluster.getName(),
                    REGISTRATION_WAIT);
        }
        EksAutoModeNodes.Room room = EksAutoModeNodes.withoutRoom(pods, poolNodes);
        removeEmptyNodes(cluster, region, launched, nodesByName, idle, room.counted(), pools, poolGone);
        for (Instance instance : poolGone) {
            String nodeName = clusterManager.deriveInstanceNodeName(instance, region);
            boolean joined = nodesByName.containsKey(nodeName);
            try {
                if (joined) {
                    exec(cluster, List.of("kubectl", "taint", "node", nodeName, DISRUPTED_TAINT, "--overwrite"),
                            "taint node " + nodeName);
                }
                retire(cluster, region, instance, nodeName, joined);
                LOG.infov("EKS Auto Mode removed node {0} ({1}) of cluster {2}, whose pool {3} is gone", nodeName,
                        instance.getInstanceId(), cluster.getName(), tag(instance, POOL_TAG));
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode node {0} of cluster {1}, whose pool is gone, wasn''t removed: {2}", nodeName,
                        cluster.getName(), e.getMessage());
            }
        }
        List<EksAutoModeNodes.PendingPod> withoutRoom = room.left();
        if (withoutRoom.isEmpty()) {
            return;
        }
        Map<String, JsonNode> nodeClasses = new HashMap<>();
        Set<String> subnetIds = new LinkedHashSet<>();
        for (JsonNode nodeClass : read(cluster, "nodeclasses").path("items")) {
            nodeClasses.put(nodeClass.path("metadata").path("name").asText(), nodeClass.path("spec"));
            subnetIds.addAll(ids(nodeClass.path("spec").path("subnetSelectorTerms")));
        }
        Map<String, String> subnetZones = subnetZones(region, subnetIds);
        // Each pool places its nodes in the zones of its own NodeClass's subnets.
        Map<String, List<String>> zonesByNodeClass = new HashMap<>();
        nodeClasses.forEach((name, spec) -> zonesByNodeClass.put(name, ids(spec.path("subnetSelectorTerms")).stream()
                .map(subnetZones::get).filter(Objects::nonNull).distinct().toList()));
        for (Launch launch : EksAutoModeNodes.plan(List.copyOf(pools.values()), withoutRoom,
                instanceTypes.instanceTypes(), zonesByNodeClass, daemons)) {
            try {
                launch(cluster, region, launch, nodeClasses.get(launch.pool().nodeClass()), subnetZones);
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode pool {0} of cluster {1} launched no node for {2}: {3}",
                        launch.pool().name(), cluster.getName(), podNames(launch), e.getMessage());
            }
        }
    }

    /**
     * Removes the pools' nodes that have been empty, running no workload and counted on by no pending pod, for
     * their pool's {@code consolidateAfter}, as Auto Mode's consolidation does, within each pool's disruption
     * budget: each is tainted {@code karpenter.sh/disrupted:NoSchedule}, as Karpenter marks a node it is removing,
     * its node taken out of the cluster and its instance terminated. A pool whose {@code consolidateAfter} is
     * {@code Never} keeps its nodes. A node left with the taint by a removal that was called off or failed, and that
     * isn't being removed now, has it taken off again.
     */
    private void removeEmptyNodes(Cluster cluster, String region, List<Instance> launched,
                                  Map<String, JsonNode> nodesByName, Map<String, Instance> empty, Set<String> counted,
                                  Map<String, NodePool> pools, List<Instance> poolGone) {
        Instant now = clock.instant();
        Map<String, Instant> since = emptySince.computeIfAbsent(cluster.getArn(), arn -> new HashMap<>());
        // A node that runs a workload again, or that a pending pod counts on, waits from the start next time.
        since.keySet().removeIf(id -> !empty.containsKey(id) || counted.contains(id));
        Map<String, Integer> poolSizes = new HashMap<>();
        launched.forEach(instance -> poolSizes.merge(String.valueOf(tag(instance, POOL_TAG)), 1, Integer::sum));
        Map<String, Integer> removed = new HashMap<>();
        List<Instance> due = new ArrayList<>();
        empty.values().stream()
                .filter(instance -> !counted.contains(instance.getInstanceId()))
                .sorted(Comparator.comparing(instance -> since.getOrDefault(instance.getInstanceId(), now)))
                .forEach(instance -> {
                    Instant emptyAt = since.computeIfAbsent(instance.getInstanceId(), id -> now);
                    NodePool pool = pools.get(tag(instance, POOL_TAG));
                    if (pool == null || pool.consolidateAfter() == null
                            || emptyAt.plus(pool.consolidateAfter()).isAfter(now)) {
                        return;
                    }
                    int done = removed.getOrDefault(pool.name(), 0);
                    if (done < pool.disruptable(poolSizes.getOrDefault(pool.name(), 0))) {
                        removed.put(pool.name(), done + 1);
                        due.add(instance);
                    }
                });
        for (Instance instance : launched) {
            String nodeName = clusterManager.deriveInstanceNodeName(instance, region);
            JsonNode node = nodesByName.get(nodeName);
            if (node != null && !due.contains(instance) && !poolGone.contains(instance) && disrupted(node)) {
                try {
                    // A drain that failed left it cordoned too; the taint comes off last, so it marks the node
                    // until both are undone.
                    if (node.path("spec").path("unschedulable").asBoolean(false)) {
                        exec(cluster, List.of("kubectl", "uncordon", nodeName), "uncordon node " + nodeName);
                    }
                    exec(cluster, List.of("kubectl", "taint", "node", nodeName, DISRUPTED_TAINT + "-"),
                            "untaint node " + nodeName);
                } catch (RuntimeException e) {
                    LOG.warnv("EKS Auto Mode node {0} of cluster {1} keeps its disrupted taint until the next "
                            + "pass: {2}", nodeName, cluster.getName(), e.getMessage());
                }
            }
        }
        for (Instance instance : due) {
            String nodeName = clusterManager.deriveInstanceNodeName(instance, region);
            try {
                exec(cluster, List.of("kubectl", "taint", "node", nodeName, DISRUPTED_TAINT, "--overwrite"),
                        "taint node " + nodeName);
                // The scheduler may have bound a pod there since the pass read the pods; tainted, the node gets no
                // more but those tolerating every taint, which the drain evicts. A workload there keeps the node.
                JsonNode bound = readJson(cluster, List.of("kubectl", "get", "pods", "-A", "--field-selector",
                        "spec.nodeName=" + nodeName, "-o", "json"), "get pods of node " + nodeName);
                if (EksAutoModeNodes.nodesWithWorkloads(bound).contains(nodeName)) {
                    since.remove(instance.getInstanceId());
                    exec(cluster, List.of("kubectl", "taint", "node", nodeName, DISRUPTED_TAINT + "-"),
                            "untaint node " + nodeName);
                    continue;
                }
                retire(cluster, region, instance, nodeName, true);
                since.remove(instance.getInstanceId());
                LOG.infov("EKS Auto Mode removed empty node {0} ({1}) of pool {2} of cluster {3}", nodeName,
                        instance.getInstanceId(), tag(instance, POOL_TAG), cluster.getName());
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode empty node {0} of cluster {1} wasn''t removed: {2}", nodeName,
                        cluster.getName(), e.getMessage());
            }
        }
    }

    private void launch(Cluster cluster, String region, Launch launch, JsonNode nodeClass,
                        Map<String, String> subnetZones) {
        if (nodeClass == null) {
            throw new IllegalStateException("its NodeClass " + launch.pool().nodeClass() + " isn't there");
        }
        String role = nodeClass.path("role").asText("");
        List<String> subnets = ids(nodeClass.path("subnetSelectorTerms"));
        if (role.isEmpty() || subnets.isEmpty()) {
            throw new IllegalStateException("its NodeClass names no role or no subnet");
        }
        String subnet = subnets.stream().filter(id -> launch.zone() == null || launch.zone().equals(subnetZones.get(id)))
                .findFirst().orElseThrow(() -> new IllegalStateException("its NodeClass has no subnet in "
                        + launch.zone()));
        String architecture = EksAutoModeNodes.architecture(launch.instanceType());
        String imageId = EksAmiReleaseVersions.recommendedImage(clusterManager.imageCatalog(), cluster.getVersion(),
                        "arm64".equals(architecture) ? "AL2023_ARM_64_STANDARD" : "AL2023_x86_64_STANDARD")
                .map(image -> image.imageId)
                .orElseThrow(() -> new IllegalStateException("the image catalog has no EKS optimized Amazon Linux "
                        + "2023 " + architecture + " image for Kubernetes " + cluster.getVersion()));
        // A cluster deleted since the pass began keeps nothing this pass would make.
        if (!stillRunning(cluster)) {
            return;
        }
        String profileArn = instanceProfile(cluster, region, launch.pool().nodeClass(), role).getArn();
        String userData = nodeConfig(cluster, launch);
        Reservation reservation = ec2.runInstances(region, imageId, launch.instanceType().instanceType, 1, 1, null,
                ids(nodeClass.path("securityGroupSelectorTerms")), subnet, UUID.randomUUID().toString(),
                tags(cluster, launch.pool()), userData, profileArn, null, null, 0, null, null, null,
                Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)), false, null, null,
                null, false, null, null);
        String instanceId = reservation.getInstances().getFirst().getInstanceId();
        if (!stillRunning(cluster)) {
            // Deleted while this launch ran: what the deletion missed goes now. Its security group is gone already,
            // so nothing waits for the instance to end.
            removeAll(cluster, region);
            return;
        }
        LOG.infov("EKS Auto Mode pool {0} of cluster {1} launched {2} ({3}) for {4}", launch.pool().name(),
                cluster.getName(), instanceId, launch.instanceType().instanceType, podNames(launch));
    }

    /** Whether the cluster is still among the running ones, not deleted or being deleted. */
    private boolean stillRunning(Cluster cluster) {
        return eksService.runningClusters().stream().anyMatch(entry -> entry.value() != null
                && cluster.getArn().equals(entry.value().getArn()));
    }

    private Map<String, String> subnetZones(String region, Set<String> subnetIds) {
        Map<String, String> zones = new LinkedHashMap<>();
        if (!subnetIds.isEmpty()) {
            for (Subnet subnet : ec2.describeSubnets(region, List.copyOf(subnetIds), Map.of())) {
                zones.put(subnet.getSubnetId(), subnet.getAvailabilityZone());
            }
        }
        return zones;
    }

    private static String zone(Instance instance) {
        return instance.getPlacement() == null ? null : instance.getPlacement().getAvailabilityZone();
    }

    /**
     * Gives a launched node its pool's labels and taints, the instance type's {@code eks.amazonaws.com/instance-*}
     * labels among them, unless it has them.
     */
    private void markNode(Cluster cluster, String nodeName, JsonNode node,
                          Ec2InstanceTypeCatalog.CatalogInstanceType type, NodePool pool) {
        JsonNode current = node.path("metadata").path("labels");
        List<String> labelArgs = new ArrayList<>();
        EksAutoModeNodes.labels(type, pool, null).forEach((key, value) -> {
            if (!value.equals(current.path(key).asText(null))) {
                labelArgs.add(key + "=" + value);
            }
        });
        if (!labelArgs.isEmpty()) {
            List<String> command = new ArrayList<>(List.of("kubectl", "label", "node", nodeName, "--overwrite"));
            command.addAll(labelArgs);
            exec(cluster, command, "label node " + nodeName);
        }
        Set<String> taints = new HashSet<>();
        node.path("spec").path("taints").forEach(t -> taints.add(taint(t.path("key").asText(),
                t.path("value").asText(""), t.path("effect").asText())));
        for (Taint taint : pool.taints()) {
            String spec = taint(taint.key(), taint.value(), taint.effect());
            if (!taints.contains(spec)) {
                exec(cluster, List.of("kubectl", "taint", "node", nodeName, spec, "--overwrite"),
                        "taint node " + nodeName);
            }
        }
    }

    /**
     * Deletes the nodes of the cluster's Auto Mode instances that are shutting down or terminated: one that
     * registered after a pass read the nodes, while that pass terminated its instance, or whose deletion failed
     * after it. Only a node named after such an instance and whose provider ID is that instance's is touched, and
     * not one a live Auto Mode instance's node shares the name of, so the cluster's own node and other workers stay
     * whatever their labels, a worker that joined later under the same name included. A pass retries
     * while EC2 keeps the terminated instance, an hour as on AWS.
     */
    private void removeStrayNodes(Cluster cluster, String region, Map<String, JsonNode> nodesByName) {
        if (nodesByName.isEmpty()) {
            return;
        }
        Set<String> stray = new LinkedHashSet<>();
        for (Instance instance : instancesOf(cluster, region, "shutting-down", "terminated")) {
            String name = clusterManager.deriveInstanceNodeName(instance, region);
            JsonNode node = nodesByName.get(name);
            // Only the node that is this instance's, by its provider ID (aws:///<zone>/<instance>, as a kubelet and
            // Floci's own Ready node set it): another worker may have joined since under the same name.
            if (node != null && node.path("spec").path("providerID").asText("")
                    .endsWith("/" + instance.getInstanceId())) {
                stray.add(name);
            }
        }
        if (stray.isEmpty()) {
            return;
        }
        for (Instance instance : instancesOf(cluster, region, "pending", "running", "stopping", "stopped")) {
            stray.remove(clusterManager.deriveInstanceNodeName(instance, region));
        }
        for (String name : stray) {
            try {
                exec(cluster, List.of("kubectl", "delete", "node", name, "--ignore-not-found=true", "--wait=false"),
                        "delete node " + name);
                nodesByName.remove(name);
                LOG.infov("EKS Auto Mode deleted node {0} of cluster {1}, whose instance is gone", name,
                        cluster.getName());
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode node {0} of cluster {1}, whose instance is gone, wasn''t deleted: {2}", name,
                        cluster.getName(), e.getMessage());
            }
        }
    }

    /**
     * Takes a node already tainted as disrupted out as Karpenter's termination does: drained (its pods evicted, so
     * one that tolerates the taint and arrived late goes elsewhere, its disruption budget kept, DaemonSet pods
     * left), then deleted, and its instance terminated. A drain that fails leaves the node for the next pass.
     */
    private void retire(Cluster cluster, String region, Instance instance, String nodeName, boolean joined) {
        if (joined) {
            List<String> drain = new ArrayList<>(List.of("kubectl", "drain", nodeName, "--ignore-daemonsets",
                    "--delete-emptydir-data", "--force", "--timeout=10s"));
            if (!clusterManager.kubeletRegistersNode(instance)) {
                // Floci's own Ready node has no kubelet to finish a pod's graceful shutdown, so its evicted pods
                // would stay terminating for good: they go at once, their disruption budgets still kept.
                drain.add("--grace-period=0");
            }
            exec(cluster, drain, "drain node " + nodeName);
            clusterManager.removeJoinedNode(cluster, instance);
        }
        ec2.terminateInstances(region, List.of(instance.getInstanceId()));
    }

    private static boolean disrupted(JsonNode node) {
        for (JsonNode taint : node.path("spec").path("taints")) {
            if (DISRUPTED_KEY.equals(taint.path("key").asText()) && "NoSchedule".equals(taint.path("effect").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String taint(String key, String value, String effect) {
        return key + (value == null || value.isEmpty() ? "" : "=" + value) + ":" + effect;
    }

    /**
     * Takes the cluster's Auto Mode nodes out of it and terminates their instances, as turning compute off does. An
     * instance whose node can't be deleted is kept, so the next pass tries it again; the node of one that registered
     * while it terminated is deleted by a later pass.
     */
    synchronized void removeNodes(Cluster cluster, String region) {
        emptySince.remove(cluster.getArn());
        List<Instance> launched = instancesOf(cluster, region, "pending", "running", "stopping", "stopped");
        List<String> removed = new ArrayList<>();
        for (Instance instance : launched) {
            try {
                clusterManager.removeJoinedNode(cluster, instance);
                removed.add(instance.getInstanceId());
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode node of instance {0} of cluster {1} wasn''t deleted: {2}",
                        instance.getInstanceId(), cluster.getName(), e.getMessage());
            }
        }
        if (!removed.isEmpty()) {
            ec2.terminateInstances(region, removed);
            LOG.infov("EKS Auto Mode compute of cluster {0} is off: terminated {1}", cluster.getName(), removed);
        }
        // A worker that was joining can register while it terminates; its node goes on a later pass, as with compute
        // on. The nodes are read only while EC2 still keeps such an instance.
        if (!instancesOf(cluster, region, "shutting-down", "terminated").isEmpty()) {
            removeStrayNodes(cluster, region, nodesByName(cluster));
        }
    }

    /** The cluster's Auto Mode instances and the instance profiles made for it go with it, as on AWS. */
    @Override
    public synchronized void clusterDeleted(Cluster cluster, String region) {
        emptySince.remove(cluster.getArn());
        removeAll(cluster, region);
        // EC2 ends an instance after TerminateInstances answers, and one shutting down still holds the cluster
        // security group, which EKS deletes next: wait until none is. One that doesn't end in time fails the
        // delete, which keeps the cluster for a retry.
        Instant deadline = Instant.now().plus(terminationWait);
        while (!instancesOf(cluster, region, "pending", "running", "stopping", "stopped", "shutting-down").isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("EKS Auto Mode instances of cluster " + cluster.getName()
                        + " didn't finish terminating within " + terminationWait);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the Auto Mode instances of cluster "
                        + cluster.getName() + " to terminate", e);
            }
        }
    }

    /** Terminates the cluster's Auto Mode instances and deletes the instance profiles made for it. */
    private void removeAll(Cluster cluster, String region) {
        List<Instance> launched = instancesOf(cluster, region, "pending", "running", "stopping", "stopped");
        if (!launched.isEmpty()) {
            ec2.terminateInstances(region, launched.stream().map(Instance::getInstanceId).toList());
        }
        for (InstanceProfile profile : iam.listInstanceProfiles(profilePath(cluster, region))) {
            for (String role : List.copyOf(profile.getRoleNames())) {
                iam.removeRoleFromInstanceProfile(profile.getInstanceProfileName(), role);
            }
            iam.deleteInstanceProfile(profile.getInstanceProfileName());
        }
    }

    /** The cluster's Auto Mode instances in one of {@code states}, found by the tags they were launched with. */
    List<Instance> instancesOf(Cluster cluster, String region, String... states) {
        Map<String, List<String>> filters = Map.of(
                "tag:" + CLUSTER_TAG, List.of(cluster.getName()),
                "tag:" + MANAGED_LAUNCH_TAG, List.of(MANAGED_LAUNCH),
                "instance-state-name", List.of(states));
        return ec2.describeInstances(region, List.of(), filters).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .toList();
    }

    /**
     * The instance profile of a NodeClass's nodes, holding its role: {@code eks-<region>-<cluster>-<number>} on the
     * path {@code /eks/<region>/<cluster>/}, as Auto Mode names it (measured), made on first use.
     */
    private InstanceProfile instanceProfile(Cluster cluster, String region, String nodeClass, String role) {
        String number = Long.toUnsignedString(Long.parseUnsignedLong(
                Digests.sha256Hex(cluster.getArn() + "/" + nodeClass).substring(0, 15), 16));
        String name = "eks-" + region + "-" + cluster.getName() + "-" + number;
        InstanceProfile profile = iam.listInstanceProfiles(profilePath(cluster, region)).stream()
                .filter(p -> name.equals(p.getInstanceProfileName())).findFirst()
                .orElseGet(() -> iam.createInstanceProfile(name, profilePath(cluster, region)));
        if (!profile.getRoleNames().contains(role)) {
            for (String other : List.copyOf(profile.getRoleNames())) {
                iam.removeRoleFromInstanceProfile(name, other);
            }
            iam.addRoleToInstanceProfile(name, role);
        }
        return profile;
    }

    private static String profilePath(Cluster cluster, String region) {
        return "/eks/" + region + "/" + cluster.getName() + "/";
    }

    private static List<Tag> tags(Cluster cluster, NodePool pool) {
        return List.of(new Tag(CLUSTER_TAG, cluster.getName()), new Tag(POOL_TAG, pool.name()),
                new Tag(NODE_CLASS_TAG, pool.nodeClass()), new Tag("kubernetes.io/cluster/" + cluster.getName(), "owned"),
                new Tag(MANAGED_LAUNCH_TAG, MANAGED_LAUNCH));
    }

    /**
     * nodeadm's {@code NodeConfig} for the cluster, the user data an instance joins it with, its kubelet registering
     * the node with its pool's labels and taints, so a pod the pool's taints keep off never lands there first.
     */
    static String nodeConfig(Cluster cluster, Launch launch) {
        StringBuilder yaml = new StringBuilder("""
                apiVersion: node.eks.aws/v1alpha1
                kind: NodeConfig
                spec:
                  cluster:
                """);
        yaml.append("    name: ").append(cluster.getName()).append('\n');
        if (cluster.getEndpoint() != null) {
            yaml.append("    apiServerEndpoint: ").append(cluster.getEndpoint()).append('\n');
        }
        if (cluster.getCertificateAuthority() != null && cluster.getCertificateAuthority().getData() != null) {
            yaml.append("    certificateAuthority: ").append(cluster.getCertificateAuthority().getData()).append('\n');
        }
        if (cluster.getKubernetesNetworkConfig() != null && cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr() != null) {
            yaml.append("    cidr: ").append(cluster.getKubernetesNetworkConfig().getServiceIpv4Cidr()).append('\n');
        }
        yaml.append("  kubelet:\n    flags:\n");
        yaml.append("    - --node-labels=").append(String.join(",", launch.labels().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).toList())).append('\n');
        if (!launch.pool().taints().isEmpty()) {
            yaml.append("    - --register-with-taints=").append(String.join(",", launch.pool().taints().stream()
                    .map(t -> taint(t.key(), t.value(), t.effect())).toList())).append('\n');
        }
        return yaml.toString();
    }

    private static List<String> ids(JsonNode terms) {
        List<String> ids = new ArrayList<>();
        terms.forEach(term -> {
            if (!term.path("id").asText("").isEmpty()) {
                ids.add(term.path("id").asText());
            }
        });
        return ids;
    }

    private static String tag(Instance instance, String key) {
        return instance.getTags() == null ? null : instance.getTags().stream()
                .filter(t -> key.equals(t.getKey())).map(Tag::getValue).findFirst().orElse(null);
    }

    private static List<String> podNames(Launch launch) {
        return launch.pods().stream().map(p -> p.namespace() + "/" + p.name()).toList();
    }

    /** The cluster's nodes by name. */
    private Map<String, JsonNode> nodesByName(Cluster cluster) {
        Map<String, JsonNode> nodesByName = new HashMap<>();
        for (JsonNode node : read(cluster, "nodes").path("items")) {
            nodesByName.put(node.path("metadata").path("name").asText(), node);
        }
        return nodesByName;
    }

    private JsonNode read(Cluster cluster, String kind) {
        List<String> command = "nodes".equals(kind) || "nodepools".equals(kind) || "nodeclasses".equals(kind)
                ? List.of("kubectl", "get", kind, "-o", "json") : List.of("kubectl", "get", kind, "-A", "-o", "json");
        return readJson(cluster, command, "get " + kind);
    }

    private JsonNode readJson(Cluster cluster, List<String> command, String what) {
        ContainerExec.Result result = exec(cluster, command, what);
        try {
            return MAPPER.readTree(result.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("kubectl " + what + " returned no JSON: " + e.getMessage(), e);
        }
    }

    private ContainerExec.Result exec(Cluster cluster, List<String> command, String what) {
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                command.toArray(String[]::new), 15);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + what + " failed: " + result.summary());
        }
        return result;
    }
}
