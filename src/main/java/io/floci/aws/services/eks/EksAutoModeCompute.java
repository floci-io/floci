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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
    static final String NODE_CLASS_TAG = "eks:kubernetes-node-class-name";
    static final String MANAGED_LAUNCH_TAG = "aws:ec2:managed-launch";
    static final String MANAGED_LAUNCH = "eks-auto";
    /** How long a launched node may take to register before its pool may get another. */
    static final Duration REGISTRATION_WAIT = Duration.ofMinutes(3);

    private final EksService eksService;
    private final EksClusterManager clusterManager;
    private final Ec2Service ec2;
    private final IamService iam;
    private final Ec2InstanceTypeCatalog instanceTypes;
    private final EmulatorConfig config;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "eks-auto-mode-compute"));

    @Inject
    public EksAutoModeCompute(EksService eksService, EksClusterManager clusterManager, Ec2Service ec2,
                              IamService iam, Ec2InstanceTypeCatalog instanceTypes, EmulatorConfig config) {
        this.eksService = eksService;
        this.clusterManager = clusterManager;
        this.ec2 = ec2;
        this.iam = iam;
        this.instanceTypes = instanceTypes;
        this.config = config;
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
        if (launched.isEmpty() && pods.isEmpty()) {
            return;
        }
        Map<String, NodePool> pools = new HashMap<>();
        EksAutoModeNodes.nodePools(read(cluster, "nodepools")).forEach(pool -> pools.put(pool.name(), pool));
        Map<String, JsonNode> nodesByName = new HashMap<>();
        for (JsonNode node : read(cluster, "nodes").path("items")) {
            nodesByName.put(node.path("metadata").path("name").asText(), node);
        }
        Map<String, long[]> requests = EksAutoModeNodes.requestsByNode(podList);
        List<EksAutoModeNodes.PoolNode> poolNodes = new ArrayList<>();
        List<Instance> neverRegistered = new ArrayList<>();
        for (Instance instance : launched) {
            NodePool pool = pools.get(tag(instance, POOL_TAG));
            Optional<Ec2InstanceTypeCatalog.CatalogInstanceType> type = instanceTypes.find(instance.getInstanceType());
            String nodeName = clusterManager.deriveInstanceNodeName(instance, region);
            JsonNode node = nodesByName.get(nodeName);
            boolean joining = node == null && (instance.getLaunchTime() == null
                    || instance.getLaunchTime().plus(REGISTRATION_WAIT).isAfter(Instant.now()));
            if (node == null && !joining) {
                neverRegistered.add(instance);
                continue;
            }
            if (pool == null || type.isEmpty()) {
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
            long[] used = requests.getOrDefault(nodeName, new long[2]);
            List<Taint> nodeTaints = new ArrayList<>();
            if (node != null) {
                node.path("spec").path("taints").forEach(t -> nodeTaints.add(new Taint(t.path("key").asText(),
                        t.path("value").asText(""), t.path("effect").asText())));
            }
            boolean schedulable = node == null || !node.path("spec").path("unschedulable").asBoolean(false);
            poolNodes.add(new EksAutoModeNodes.PoolNode(pool, type.get(), zone(instance), used[0], used[1],
                    schedulable, List.copyOf(nodeTaints)));
        }
        if (!neverRegistered.isEmpty()) {
            // A node that never joins would hold its pods' room for good; it goes, and they get another.
            ec2.terminateInstances(region, neverRegistered.stream().map(Instance::getInstanceId).toList());
            LOG.warnv("EKS Auto Mode nodes {0} of cluster {1} didn''t register within {2}: terminated",
                    neverRegistered.stream().map(Instance::getInstanceId).toList(), cluster.getName(),
                    REGISTRATION_WAIT);
        }
        List<EksAutoModeNodes.PendingPod> withoutRoom = EksAutoModeNodes.withoutRoom(pods, poolNodes);
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
                instanceTypes.instanceTypes(), zonesByNodeClass)) {
            try {
                launch(cluster, region, launch, nodeClasses.get(launch.pool().nodeClass()), subnetZones);
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode pool {0} of cluster {1} launched no node for {2}: {3}",
                        launch.pool().name(), cluster.getName(), podNames(launch), e.getMessage());
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
            // Deleted while this launch ran: what the deletion missed goes now.
            clusterDeleted(cluster, region);
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

    private static String taint(String key, String value, String effect) {
        return key + (value == null || value.isEmpty() ? "" : "=" + value) + ":" + effect;
    }

    /** Terminates the cluster's Auto Mode instances and takes their nodes out of it, as turning compute off does. */
    synchronized void removeNodes(Cluster cluster, String region) {
        List<Instance> launched = instancesOf(cluster, region, "pending", "running", "stopping", "stopped");
        if (launched.isEmpty()) {
            return;
        }
        ec2.terminateInstances(region, launched.stream().map(Instance::getInstanceId).toList());
        for (Instance instance : launched) {
            clusterManager.removeJoinedNode(cluster, instance);
        }
        LOG.infov("EKS Auto Mode compute of cluster {0} is off: terminated {1}", cluster.getName(),
                launched.stream().map(Instance::getInstanceId).toList());
    }

    /** The cluster's Auto Mode instances and the instance profiles made for it go with it, as on AWS. */
    @Override
    public synchronized void clusterDeleted(Cluster cluster, String region) {
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

    private JsonNode read(Cluster cluster, String kind) {
        List<String> command = "nodes".equals(kind) || "nodepools".equals(kind) || "nodeclasses".equals(kind)
                ? List.of("kubectl", "get", kind, "-o", "json") : List.of("kubectl", "get", kind, "-A", "-o", "json");
        ContainerExec.Result result = exec(cluster, command, "get " + kind);
        try {
            return MAPPER.readTree(result.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("kubectl get " + kind + " returned no JSON: " + e.getMessage(), e);
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
