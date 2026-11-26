package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What EKS Auto Mode decides for a node, without Kubernetes or EC2: the labels a node of an instance type carries,
 * whether a pool's requirements and a pod's constraints allow it, and which pool and instance type a set of
 * unschedulable pods gets. The reconciler reads the objects with {@code kubectl} and launches what this picks.
 */
final class EksAutoModeNodes {

    static final String NODEPOOL_LABEL = "karpenter.sh/nodepool";
    static final String COMPUTE_TYPE_LABEL = "eks.amazonaws.com/compute-type"; // partition-literal: a label
    static final String LABEL_PREFIX = "eks.amazonaws.com/instance-"; // partition-literal: a label prefix
    /** The smallest instance Floci picks, as AWS picked a 2-vCPU instance for a pod of 100m CPU (measured). */
    static final int MIN_VCPUS = 2;
    /** The pods a node takes when it doesn't say: the kubelet's default, which Floci's own Ready node advertises. */
    static final int MAX_PODS = 110;
    /** The topology key of the pod anti-affinity a node's own pods decide. */
    static final String HOSTNAME_LABEL = "kubernetes.io/hostname";
    /** The region label a joined node carries, which DaemonSets may select on. */
    static final String REGION_LABEL = "topology.kubernetes.io/region";
    /** Instance family names: category letters, generation digits, then attribute letters, as in {@code c7gd}. */
    private static final Pattern FAMILY = Pattern.compile("^([a-z]+)(\\d+)([a-z-]*)$");
    private static final Pattern QUANTITY = Pattern.compile("^([0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)([a-zA-Z]*)$");
    static final String ZONE_LABEL = "topology.kubernetes.io/zone";
    /** The labels Floci gives its nodes: a pool requirement on any other label is left out. */
    /**
     * The taints a node carries while it starts, which Kubernetes or the cloud provider take off once it is ready:
     * Karpenter counts a starting node's room without them, so a pod waits for it rather than get another. Floci
     * leaves them out within the registration wait of the node's launch only, so a node that stays unhealthy after
     * it has no room.
     */
    static final Set<String> STARTUP_TAINTS = Set.of("node.kubernetes.io/not-ready", "node.kubernetes.io/unreachable",
            "node.cloudprovider.kubernetes.io/uninitialized", "karpenter.sh/unregistered");
    private static final Set<String> MODELLED_LABELS = Set.of("kubernetes.io/arch", "kubernetes.io/os",
            "node.kubernetes.io/instance-type", "karpenter.sh/capacity-type", NODEPOOL_LABEL, COMPUTE_TYPE_LABEL,
            ZONE_LABEL, LABEL_PREFIX + "category", LABEL_PREFIX + "generation", LABEL_PREFIX + "family",
            LABEL_PREFIX + "size", LABEL_PREFIX + "cpu", LABEL_PREFIX + "memory");

    private EksAutoModeNodes() {
    }

    /** A requirement of a pool or a pod's node affinity: a label key, an operator and its values. */
    record Requirement(String key, String operator, List<String> values) {

        /** Whether a node with {@code labels} meets it, as Kubernetes evaluates a node selector requirement. */
        boolean matches(Map<String, String> labels) {
            String value = labels.get(key);
            return switch (operator) {
                case "In" -> value != null && values.contains(value);
                case "NotIn" -> value == null || !values.contains(value);
                case "Exists" -> value != null;
                case "DoesNotExist" -> value == null;
                case "Gt" -> compare(value) > 0;
                case "Lt" -> compare(value) < 0;
                default -> false;
            };
        }

        private int compare(String value) {
            if (value == null || values.size() != 1) {
                return 0;
            }
            try {
                return Long.compare(Long.parseLong(value), Long.parseLong(values.getFirst()));
            } catch (NumberFormatException expected) {
                // Gt and Lt compare integers; Kubernetes matches no node when either side isn't one, which 0 gives.
                return 0;
            }
        }
    }

    /** A taint, or a pod's toleration of taints. */
    record Taint(String key, String value, String effect) {
    }

    record Toleration(String key, String operator, String value, String effect) {

        boolean tolerates(Taint taint) {
            boolean effectMatches = effect == null || effect.isEmpty() || effect.equals(taint.effect());
            if (!effectMatches) {
                return false;
            }
            if (key == null || key.isEmpty()) {
                return "Exists".equals(operator);
            }
            return key.equals(taint.key())
                    && ("Exists".equals(operator) || Objects.equals(value == null ? "" : value,
                    taint.value() == null ? "" : taint.value()));
        }
    }

    /**
     * A NodePool: its NodeClass, its requirements, the taints and labels its nodes get, its weight, how long a node
     * stays empty before it goes (null for {@code Never}), and its disruption budgets' {@code nodes} values.
     */
    record NodePool(String name, String nodeClass, List<Requirement> requirements, List<Taint> taints,
                    Map<String, String> labels, int weight, Duration consolidateAfter, List<String> budgets) {

        /**
         * How many of the pool's {@code nodes} may be disrupted at once: the least its budgets allow, a percentage
         * of them rounded up, as Karpenter counts it (AWS removed the only node of a pool budgeted at 10%). Without
         * a budget Karpenter allows 10%.
         */
        int disruptable(int nodes) {
            List<String> values = budgets.isEmpty() ? List.of("10%") : budgets;
            int allowed = Integer.MAX_VALUE;
            for (String value : values) {
                try {
                    allowed = Math.min(allowed, value.endsWith("%")
                            ? (int) Math.ceil(nodes * Integer.parseInt(value.substring(0, value.length() - 1)) / 100.0)
                            : Integer.parseInt(value));
                } catch (NumberFormatException expected) {
                    // The NodePool schema refuses such a value; one that got through allows no disruption, the safe
                    // reading.
                    allowed = 0;
                }
            }
            return Math.max(0, allowed);
        }
    }

    /** A pod the scheduler couldn't place, with what it asks of a node and the resources it requests. */
    record PendingPod(String namespace, String name, long cpuMillis, long memoryBytes,
                      Map<String, String> nodeSelector, List<List<Requirement>> affinityTerms,
                      List<Toleration> tolerations, Map<String, String> labels, List<PodTerm> antiAffinity) {

        /** A pod with no labels and no pod anti-affinity. */
        PendingPod(String namespace, String name, long cpuMillis, long memoryBytes, Map<String, String> nodeSelector,
                   List<List<Requirement>> affinityTerms, List<Toleration> tolerations) {
            this(namespace, name, cpuMillis, memoryBytes, nodeSelector, affinityTerms, tolerations, Map.of(), List.of());
        }

        /** The pod as one placed on a node, for the anti-affinity of the pods after it. */
        BoundPod bound() {
            return new BoundPod(namespace, labels, antiAffinity);
        }

        /** Whether a node with {@code labels} and {@code taints} could take it. */
        boolean fits(Map<String, String> labels, List<Taint> taints) {
            for (Map.Entry<String, String> wanted : nodeSelector.entrySet()) {
                if (!wanted.getValue().equals(labels.get(wanted.getKey()))) {
                    return false;
                }
            }
            if (!affinityTerms.isEmpty()
                    && affinityTerms.stream().noneMatch(term -> term.stream().allMatch(r -> r.matches(labels)))) {
                return false;
            }
            for (Taint taint : taints) {
                boolean scheduling = "NoSchedule".equals(taint.effect()) || "NoExecute".equals(taint.effect());
                if (scheduling && tolerations.stream().noneMatch(t -> t.tolerates(taint))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A required pod anti-affinity term on {@code kubernetes.io/hostname}: the pods it keeps a pod off the node of,
     * by label selector and namespaces ({@code null} for every namespace, as a {@code namespaceSelector} reads).
     */
    record PodTerm(Map<String, String> matchLabels, List<Requirement> matchExpressions, Set<String> namespaces) {

        boolean matches(String namespace, Map<String, String> labels) {
            if (namespaces != null && !namespaces.contains(namespace)) {
                return false;
            }
            for (Map.Entry<String, String> wanted : matchLabels.entrySet()) {
                if (!wanted.getValue().equals(labels.get(wanted.getKey()))) {
                    return false;
                }
            }
            return matchExpressions.stream().allMatch(r -> r.matches(labels));
        }
    }

    /** A pod on a node, with what its own anti-affinity keeps off that node. */
    record BoundPod(String namespace, Map<String, String> labels, List<PodTerm> antiAffinity) {

        /** Whether the pod and this one can't share a node, by either one's required anti-affinity. */
        boolean conflicts(PendingPod pod) {
            return pod.antiAffinity().stream().anyMatch(t -> t.matches(namespace, labels))
                    || antiAffinity.stream().anyMatch(t -> t.matches(pod.namespace(), pod.labels()));
        }
    }

    /**
     * A node Auto Mode launched for a pool, joined or still joining, with the CPU and memory its pods request: a
     * pending pod it has room for is left to the scheduler rather than given another node.
     */
    record PoolNode(String instanceId, NodePool pool, CatalogInstanceType instanceType, String zone,
                    long freeCpuMillis, long freeMemoryBytes, int freePods, boolean schedulable,
                    List<Taint> nodeTaints, List<BoundPod> pods) {

        /** A node whose pods carry no labels and no anti-affinity that could keep a pending pod off it. */
        PoolNode(String instanceId, NodePool pool, CatalogInstanceType instanceType, String zone, long freeCpuMillis,
                 long freeMemoryBytes, int freePods, boolean schedulable, List<Taint> nodeTaints) {
            this(instanceId, pool, instanceType, zone, freeCpuMillis, freeMemoryBytes, freePods, schedulable,
                    nodeTaints, List.of());
        }

        /**
         * A node still joining, with its pods' requests: it has its instance type's whole CPU and memory, as no
         * kubelet has said what it reserves yet, all its pod slots, and no taints but its pool's.
         */
        PoolNode(String instanceId, NodePool pool, CatalogInstanceType instanceType, String zone, long usedCpuMillis,
                 long usedMemoryBytes) {
            this(instanceId, pool, instanceType, zone, instanceType.vcpu * 1000L - usedCpuMillis,
                    instanceType.memoryMib * 1024L * 1024L - usedMemoryBytes, MAX_PODS, true, List.of());
        }

        /** The taints a pod must tolerate there: the pool's and any the node carries besides. */
        List<Taint> taints() {
            List<Taint> all = new ArrayList<>(pool.taints());
            nodeTaints.stream().filter(t -> !all.contains(t)).forEach(all::add);
            return all;
        }
    }

    /** The pending pods no pool node has room for, and the nodes whose room the others are counting on. */
    record Room(List<PendingPod> left, Set<String> counted) {
    }

    /** A node to launch: its pool, instance type, zone (null when no subnet's zone is known) and labels, and its pods. */
    record Launch(NodePool pool, CatalogInstanceType instanceType, String zone, Map<String, String> labels,
                  List<PendingPod> pods) {
    }

    /** An instance type in a zone, the two things a launch picks for its pool. */
    private record Placement(CatalogInstanceType type, String zone) {
    }

    /**
     * The labels a node of {@code type} in {@code pool} carries, as Auto Mode's nodes do (measured): architecture,
     * OS, instance type, capacity type, the {@code eks.amazonaws.com/instance-*} attributes, its pool, the compute
     * type, its zone when known, and the pool's own labels.
     */
    static Map<String, String> labels(CatalogInstanceType type, NodePool pool, String zone) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("kubernetes.io/arch", architecture(type));
        labels.put("kubernetes.io/os", "linux");
        labels.put("node.kubernetes.io/instance-type", type.instanceType);
        labels.put("karpenter.sh/capacity-type", "on-demand");
        String family = type.instanceType.substring(0, type.instanceType.indexOf('.'));
        Matcher parts = FAMILY.matcher(family);
        if (parts.matches()) {
            labels.put(LABEL_PREFIX + "category", parts.group(1));
            labels.put(LABEL_PREFIX + "generation", parts.group(2));
        }
        labels.put(LABEL_PREFIX + "family", family);
        labels.put(LABEL_PREFIX + "size", type.instanceType.substring(type.instanceType.indexOf('.') + 1));
        labels.put(LABEL_PREFIX + "cpu", String.valueOf(type.vcpu));
        labels.put(LABEL_PREFIX + "memory", String.valueOf(type.memoryMib));
        labels.put(NODEPOOL_LABEL, pool.name());
        labels.put(COMPUTE_TYPE_LABEL, "auto");
        if (zone != null) {
            labels.put(ZONE_LABEL, zone);
        }
        labels.putAll(pool.labels());
        return labels;
    }

    static String architecture(CatalogInstanceType type) {
        return type.supportedArchitectures.contains("arm64") ? "arm64" : "amd64";
    }

    /**
     * Whether a node with {@code labels} meets every requirement of {@code pool} that names a label Floci gives
     * its nodes; a requirement on another label (an attribute Floci doesn't model, such as a CPU manufacturer or
     * GPU count) is left out.
     */
    static boolean poolAllows(NodePool pool, Map<String, String> labels) {
        return pool.requirements().stream()
                .filter(r -> labels.containsKey(r.key()) || MODELLED_LABELS.contains(r.key()))
                .allMatch(r -> r.matches(labels));
    }

    /**
     * The nodes to launch for {@code pods}: each pod goes to the first pool, by weight then name, that has an
     * instance type, in a zone of its NodeClass's subnets ({@code zonesByNodeClass}), that its requirements allow,
     * the pod's constraints accept and its requests fit. Each pool gets one node, of the smallest such type that
     * fits all of its pods, or else the largest pod; those left over get one on a later pass. Pods no pool can take
     * are left out. A pool whose NodeClass's zones aren't known launches nodes with no zone label.
     */
    static List<Launch> plan(List<NodePool> pools, List<PendingPod> pods, List<CatalogInstanceType> types,
                             Map<String, List<String>> zonesByNodeClass) {
        return plan(pools, pods, types, zonesByNodeClass, List.of());
    }

    /**
     * As above; with the cluster's DaemonSet pods, as {@link #daemonPods} reads them: a node is sized for its
     * waiting pods and the DaemonSet pods that would run on it too, as Karpenter sizes it.
     */
    static List<Launch> plan(List<NodePool> pools, List<PendingPod> pods, List<CatalogInstanceType> types,
                             Map<String, List<String>> zonesByNodeClass, List<PendingPod> daemons) {
        return plan(pools, pods, types, zonesByNodeClass, daemons, null);
    }

    /** As above; with the region the nodes join in, whose label DaemonSets may select on. */
    static List<Launch> plan(List<NodePool> pools, List<PendingPod> pods, List<CatalogInstanceType> types,
                             Map<String, List<String>> zonesByNodeClass, List<PendingPod> daemons, String region) {
        List<NodePool> ordered = pools.stream()
                .sorted(Comparator.comparingInt(NodePool::weight).reversed().thenComparing(NodePool::name))
                .toList();
        List<CatalogInstanceType> sized = types.stream()
                .filter(t -> t.vcpu >= MIN_VCPUS && t.instanceType.contains("."))
                .sorted(Comparator.comparingInt((CatalogInstanceType t) -> t.vcpu)
                        .thenComparingInt(t -> t.memoryMib).thenComparing(t -> t.instanceType))
                .toList();
        Map<String, List<PendingPod>> byPool = new LinkedHashMap<>();
        for (PendingPod pod : pods) {
            for (NodePool pool : ordered) {
                if (candidate(pool, sized, zonesOf(pool, zonesByNodeClass), pod, pod.cpuMillis(), pod.memoryBytes(),
                        daemons, region)
                        .isPresent()) {
                    byPool.computeIfAbsent(pool.name(), k -> new ArrayList<>()).add(pod);
                    break;
                }
            }
        }
        List<Launch> launches = new ArrayList<>();
        for (NodePool pool : ordered) {
            List<PendingPod> group = byPool.get(pool.name());
            if (group == null) {
                continue;
            }
            long cpu = group.stream().mapToLong(PendingPod::cpuMillis).sum();
            long memory = group.stream().mapToLong(PendingPod::memoryBytes).sum();
            PendingPod largest = group.stream().max(Comparator.comparingLong(PendingPod::cpuMillis)
                    .thenComparingLong(PendingPod::memoryBytes)).orElseThrow();
            List<String> placesIn = zonesOf(pool, zonesByNodeClass);
            Optional<Placement> placement = candidate(pool, sized, placesIn, largest, cpu, memory, daemons, region)
                    .filter(p -> group.stream().allMatch(pod -> pod.fits(labels(p.type(), pool, p.zone()), pool.taints())))
                    .or(() -> candidate(pool, sized, placesIn, largest, largest.cpuMillis(), largest.memoryBytes(),
                            daemons, region));
            placement.ifPresent(p -> launches.add(new Launch(pool, p.type(), p.zone(),
                    labels(p.type(), pool, p.zone()), List.copyOf(group))));
        }
        return launches;
    }

    /**
     * The pods none of the pools' nodes has room for: each pod takes room on the first schedulable node whose
     * labels and taints, the pool's and the node's own, it accepts, that has a pod slot left, whose free CPU and
     * memory fit its requests, and where no pod, one there or one counted on it before, shares it against either's
     * required anti-affinity on {@code kubernetes.io/hostname},
     * as Karpenter counts its nodes, those still joining among them, before it launches another. A cordoned node
     * has no room.
     */
    static Room withoutRoom(List<PendingPod> pods, List<PoolNode> nodes) {
        long[] freeCpu = new long[nodes.size()];
        long[] freeMemory = new long[nodes.size()];
        int[] freePods = new int[nodes.size()];
        List<List<BoundPod>> onNode = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            PoolNode node = nodes.get(i);
            onNode.add(new ArrayList<>(node.pods()));
            freeCpu[i] = node.freeCpuMillis();
            freeMemory[i] = node.freeMemoryBytes();
            freePods[i] = node.freePods();
        }
        List<PendingPod> left = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (PendingPod pod : pods) {
            boolean placed = false;
            for (int i = 0; i < nodes.size() && !placed; i++) {
                PoolNode node = nodes.get(i);
                if (node.schedulable() && freePods[i] > 0 && freeCpu[i] >= pod.cpuMillis()
                        && freeMemory[i] >= pod.memoryBytes()
                        && pod.fits(labels(node.instanceType(), node.pool(), node.zone()), node.taints())
                        && onNode.get(i).stream().noneMatch(other -> other.conflicts(pod))) {
                    freeCpu[i] -= pod.cpuMillis();
                    freeMemory[i] -= pod.memoryBytes();
                    freePods[i]--;
                    onNode.get(i).add(pod.bound());
                    counted.add(node.instanceId());
                    placed = true;
                }
            }
            if (!placed) {
                left.add(pod);
            }
        }
        return new Room(left, counted);
    }

    private static List<String> zonesOf(NodePool pool, Map<String, List<String>> zonesByNodeClass) {
        List<String> zones = zonesByNodeClass.getOrDefault(pool.nodeClass(), List.of());
        return zones.isEmpty() ? Collections.singletonList(null) : zones;
    }

    private static Optional<Placement> candidate(NodePool pool, List<CatalogInstanceType> sized, List<String> zones,
                                                 PendingPod pod, long cpuMillis, long memoryBytes,
                                                 List<PendingPod> daemons, String region) {
        for (CatalogInstanceType type : sized) {
            for (String zone : zones) {
                Map<String, String> labels = labels(type, pool, zone);
                long[] daemon = daemonRequests(daemons, withRegion(labels, region), pool.taints());
                if (type.vcpu * 1000L < cpuMillis + daemon[0]
                        || type.memoryMib * 1024L * 1024L < memoryBytes + daemon[1]) {
                    continue;
                }
                if (poolAllows(pool, labels) && pod.fits(labels, pool.taints())) {
                    return Optional.of(new Placement(type, zone));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The CPU (millicores) and memory (bytes) the DaemonSet pods that would run on a node of {@code labels} and
     * {@code taints} request: those whose node selector, required node affinity and tolerations accept it.
     */
    /** A node's labels with the {@code topology.kubernetes.io/region} it joins with, when the region is known. */
    static Map<String, String> withRegion(Map<String, String> labels, String region) {
        if (region == null) {
            return labels;
        }
        Map<String, String> all = new LinkedHashMap<>(labels);
        all.put(REGION_LABEL, region);
        return all;
    }

    static long[] daemonRequests(List<PendingPod> daemons, Map<String, String> labels, List<Taint> taints) {
        long[] total = new long[2];
        for (PendingPod daemon : daemons) {
            if (daemon.fits(labels, taints)) {
                total[0] += daemon.cpuMillis();
                total[1] += daemon.memoryBytes();
            }
        }
        return total;
    }

    /**
     * The pod each DaemonSet of {@code kubectl get daemonsets -A -o json} runs on a node, with its template's
     * requests, node selector, required node affinity and tolerations, read as {@link #unschedulablePods} reads a
     * pod's; one none of whose affinity terms a new node could match is left out. The DaemonSet controller lets its pods onto
     * an unschedulable node, which Floci's nodes don't need.
     */
    static List<PendingPod> daemonPods(JsonNode list) {
        List<PendingPod> daemons = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            JsonNode spec = item.path("spec").path("template").path("spec");
            Optional<NodeAsk> ask = nodeAsk(spec);
            if (ask.isEmpty()) {
                continue;
            }
            long[] requests = requests(spec);
            daemons.add(new PendingPod(item.path("metadata").path("namespace").asText(),
                    item.path("metadata").path("name").asText(), requests[0], requests[1], ask.get().nodeSelector(),
                    ask.get().affinityTerms(), ask.get().tolerations()));
        }
        return daemons;
    }

    /** What a pod spec asks of its node: its node selector, required node affinity terms and tolerations. */
    private record NodeAsk(Map<String, String> nodeSelector, List<List<Requirement>> affinityTerms,
                           List<Toleration> tolerations) {
    }

    /**
     * What a pod spec asks of a node Auto Mode launches, or empty when no such node could take it. The required node
     * affinity terms are alternatives: one with {@code matchFields}, which names nodes and so none Auto Mode
     * launches, or with no expressions, which Kubernetes matches with no node, is no alternative for a new node, and
     * a spec all of whose terms are such asks for none.
     */
    private static Optional<NodeAsk> nodeAsk(JsonNode spec) {
        JsonNode termList = spec.path("affinity").path("nodeAffinity")
                .path("requiredDuringSchedulingIgnoredDuringExecution").path("nodeSelectorTerms");
        List<List<Requirement>> terms = new ArrayList<>();
        for (JsonNode term : termList) {
            if (term.path("matchFields").isEmpty() && !term.path("matchExpressions").isEmpty()) {
                terms.add(requirements(term.path("matchExpressions")));
            }
        }
        if (!termList.isEmpty() && terms.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> selector = new LinkedHashMap<>();
        spec.path("nodeSelector").properties().forEach(e -> selector.put(e.getKey(), e.getValue().asText()));
        List<Toleration> tolerations = new ArrayList<>();
        spec.path("tolerations").forEach(t -> tolerations.add(new Toleration(t.path("key").asText(""),
                t.path("operator").asText("Equal"), t.path("value").asText(""), t.path("effect").asText(""))));
        return Optional.of(new NodeAsk(selector, List.copyOf(terms), List.copyOf(tolerations)));
    }

    /** The NodePools of {@code kubectl get nodepools -o json}. */
    static List<NodePool> nodePools(JsonNode list) {
        List<NodePool> pools = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            JsonNode template = item.path("spec").path("template");
            Map<String, String> labels = new LinkedHashMap<>();
            template.path("metadata").path("labels").properties()
                    .forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
            List<Taint> taints = new ArrayList<>();
            template.path("spec").path("taints").forEach(t -> taints.add(new Taint(t.path("key").asText(),
                    t.path("value").asText(""), t.path("effect").asText())));
            JsonNode disruption = item.path("spec").path("disruption");
            List<String> budgets = new ArrayList<>();
            // A budget with a schedule applies only in its window, which Floci doesn't model; the others always do.
            // A budget naming reasons covers only those; one without covers every reason, emptiness among them.
            disruption.path("budgets").forEach(b -> {
                boolean coversEmpty = !b.has("reasons") || b.path("reasons").isEmpty();
                for (JsonNode reason : b.path("reasons")) {
                    coversEmpty |= "Empty".equals(reason.asText());
                }
                if (coversEmpty && b.path("schedule").asText("").isEmpty() && !b.path("nodes").asText("").isEmpty()) {
                    budgets.add(b.path("nodes").asText());
                }
            });
            pools.add(new NodePool(item.path("metadata").path("name").asText(),
                    template.path("spec").path("nodeClassRef").path("name").asText("default"),
                    requirements(template.path("spec").path("requirements")), List.copyOf(taints), labels,
                    item.path("spec").path("weight").asInt(0), duration(disruption.path("consolidateAfter").asText("0s")),
                    List.copyOf(budgets)));
        }
        return pools;
    }

    /**
     * The pods of {@code kubectl get pods -A -o json} the scheduler marked unschedulable and a new node could take.
     * As Karpenter does, a DaemonSet's pods are left out, as are pods none of whose required node affinity terms a
     * new node could match: each term pins named nodes ({@code matchFields}) or is empty (see {@link #nodeAsk}).
     */
    static List<PendingPod> unschedulablePods(JsonNode list) {
        List<PendingPod> pods = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            if (!"Pending".equals(item.path("status").path("phase").asText())
                    || !item.path("spec").path("nodeName").asText("").isEmpty()) {
                continue;
            }
            boolean unschedulable = false;
            for (JsonNode condition : item.path("status").path("conditions")) {
                unschedulable |= "PodScheduled".equals(condition.path("type").asText())
                        && "False".equals(condition.path("status").asText())
                        && "Unschedulable".equals(condition.path("reason").asText());
            }
            if (!unschedulable) {
                continue;
            }
            boolean daemonSet = false;
            for (JsonNode owner : item.path("metadata").path("ownerReferences")) {
                daemonSet |= "DaemonSet".equals(owner.path("kind").asText());
            }
            JsonNode spec = item.path("spec");
            Optional<NodeAsk> ask = nodeAsk(spec);
            if (daemonSet || ask.isEmpty()) {
                continue;
            }
            long[] requests = requests(spec);
            String namespace = item.path("metadata").path("namespace").asText();
            pods.add(new PendingPod(namespace, item.path("metadata").path("name").asText(), requests[0], requests[1],
                    ask.get().nodeSelector(), ask.get().affinityTerms(), ask.get().tolerations(), podLabels(item),
                    antiAffinity(item)));
        }
        return pods;
    }

    /** The pods of {@code kubectl get pods -A -o json} bound to each node that haven't finished. */
    static Map<String, List<BoundPod>> boundPodsByNode(JsonNode list) {
        Map<String, List<BoundPod>> byNode = new LinkedHashMap<>();
        for (JsonNode item : list.path("items")) {
            String node = item.path("spec").path("nodeName").asText("");
            String phase = item.path("status").path("phase").asText();
            if (node.isEmpty() || "Succeeded".equals(phase) || "Failed".equals(phase)) {
                continue;
            }
            byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(new BoundPod(
                    item.path("metadata").path("namespace").asText(), podLabels(item), antiAffinity(item)));
        }
        return byNode;
    }

    private static Map<String, String> podLabels(JsonNode item) {
        Map<String, String> labels = new LinkedHashMap<>();
        item.path("metadata").path("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
        return labels;
    }

    /**
     * A pod's required anti-affinity terms on {@code kubernetes.io/hostname}, the ones a node's pods decide; those on
     * another topology key, a zone's say, aren't modelled. A term without a label selector matches no pod.
     */
    private static List<PodTerm> antiAffinity(JsonNode item) {
        List<PodTerm> terms = new ArrayList<>();
        String own = item.path("metadata").path("namespace").asText();
        for (JsonNode term : item.path("spec").path("affinity").path("podAntiAffinity")
                .path("requiredDuringSchedulingIgnoredDuringExecution")) {
            JsonNode selector = term.path("labelSelector");
            if (!HOSTNAME_LABEL.equals(term.path("topologyKey").asText()) || selector.isMissingNode()
                    || selector.isNull()) {
                continue;
            }
            Map<String, String> matchLabels = new LinkedHashMap<>();
            selector.path("matchLabels").properties().forEach(e -> matchLabels.put(e.getKey(), e.getValue().asText()));
            Set<String> named = new HashSet<>();
            term.path("namespaces").forEach(n -> named.add(n.asText()));
            if (named.isEmpty()) {
                named.add(own);
            }
            // A namespaceSelector, which Floci can't evaluate without the namespaces' labels, is read as all of them.
            Set<String> namespaces = term.has("namespaceSelector") ? null : Set.copyOf(named);
            terms.add(new PodTerm(matchLabels, requirements(selector.path("matchExpressions")), namespaces));
        }
        return List.copyOf(terms);
    }

    /**
     * The nodes of {@code kubectl get pods -A -o json} that run a workload: a pod bound to them that hasn't finished
     * and that isn't a DaemonSet's or a static pod's mirror, which Karpenter doesn't count either.
     */
    static Set<String> nodesWithWorkloads(JsonNode list) {
        Set<String> nodes = new HashSet<>();
        for (JsonNode item : list.path("items")) {
            String node = item.path("spec").path("nodeName").asText("");
            String phase = item.path("status").path("phase").asText();
            if (node.isEmpty() || "Succeeded".equals(phase) || "Failed".equals(phase)) {
                continue;
            }
            boolean daemon = false;
            for (JsonNode owner : item.path("metadata").path("ownerReferences")) {
                daemon |= "DaemonSet".equals(owner.path("kind").asText()) || "Node".equals(owner.path("kind").asText());
            }
            if (!daemon) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /**
     * The CPU (millicores) and memory (bytes) the pods of {@code kubectl get pods -A -o json} bound to each node
     * request, and how many they are, those that finished left out.
     */
    static Map<String, long[]> requestsByNode(JsonNode list) {
        Map<String, long[]> byNode = new LinkedHashMap<>();
        for (JsonNode item : list.path("items")) {
            String node = item.path("spec").path("nodeName").asText("");
            String phase = item.path("status").path("phase").asText();
            if (node.isEmpty() || "Succeeded".equals(phase) || "Failed".equals(phase)) {
                continue;
            }
            long[] requests = requests(item.path("spec"));
            long[] total = byNode.computeIfAbsent(node, k -> new long[3]);
            total[0] += requests[0];
            total[1] += requests[1];
            total[2]++;
        }
        return byNode;
    }

    /**
     * A pod spec's CPU (millicores) and memory (bytes) requests as the scheduler counts them: its containers' and
     * sidecars' sum, or what its init containers need at once when that is larger, plus the pod's overhead.
     */
    private static long[] requests(JsonNode spec) {
        long[] total = new long[2];
        for (JsonNode container : spec.path("containers")) {
            JsonNode requests = container.path("resources").path("requests");
            total[0] += cpuMillis(requests.path("cpu").asText(""));
            total[1] += bytes(requests.path("memory").asText(""));
        }
        // Init containers run in order. A sidecar (restartPolicy Always) keeps running from its start, alongside the
        // init containers after it and the app's; any other one runs alone with the sidecars started before it.
        long[] sidecars = new long[2];
        long[] init = new long[2];
        for (JsonNode container : spec.path("initContainers")) {
            JsonNode requests = container.path("resources").path("requests");
            long cpu = cpuMillis(requests.path("cpu").asText(""));
            long memory = bytes(requests.path("memory").asText(""));
            boolean sidecar = "Always".equals(container.path("restartPolicy").asText());
            init[0] = Math.max(init[0], sidecars[0] + cpu);
            init[1] = Math.max(init[1], sidecars[1] + memory);
            if (sidecar) {
                sidecars[0] += cpu;
                sidecars[1] += memory;
            }
        }
        total[0] = Math.max(total[0] + sidecars[0], init[0]) + cpuMillis(spec.path("overhead").path("cpu").asText(""));
        total[1] = Math.max(total[1] + sidecars[1], init[1]) + bytes(spec.path("overhead").path("memory").asText(""));
        return total;
    }

    private static List<Requirement> requirements(JsonNode list) {
        List<Requirement> requirements = new ArrayList<>();
        for (JsonNode r : list) {
            List<String> values = new ArrayList<>();
            r.path("values").forEach(v -> values.add(v.asText()));
            requirements.add(new Requirement(r.path("key").asText(), r.path("operator").asText(), List.copyOf(values)));
        }
        return List.copyOf(requirements);
    }

    /**
     * A CPU quantity in millicores: {@code 100m}, {@code 1}, {@code 0.5}, {@code 2e3}; rounded up, as Kubernetes
     * does; 0 for none or one Floci can't read.
     */
    static long cpuMillis(String quantity) {
        Matcher m = QUANTITY.matcher(quantity.trim());
        if (!m.matches()) {
            return 0;
        }
        BigDecimal number = new BigDecimal(m.group(1));
        BigDecimal millis = switch (m.group(2)) {
            case "m" -> number;
            case "" -> number.movePointRight(3);
            case "k" -> number.movePointRight(6);
            default -> BigDecimal.ZERO;
        };
        return millis.setScale(0, RoundingMode.CEILING).longValue();
    }

    /**
     * A memory quantity in bytes: {@code 64Mi}, {@code 1G}, {@code 128974848}, {@code 1e9}, {@code 1.5Gi}; rounded
     * up; 0 for none or one Floci can't read.
     */
    static long bytes(String quantity) {
        Matcher m = QUANTITY.matcher(quantity.trim());
        if (!m.matches()) {
            return 0;
        }
        BigDecimal number = new BigDecimal(m.group(1));
        BigDecimal value = switch (m.group(2)) {
            case "" -> number;
            case "m" -> number.movePointLeft(3);
            case "k" -> number.movePointRight(3);
            case "M" -> number.movePointRight(6);
            case "G" -> number.movePointRight(9);
            case "T" -> number.movePointRight(12);
            case "P" -> number.movePointRight(15);
            case "E" -> number.movePointRight(18);
            case "Ki" -> number.multiply(BigDecimal.valueOf(1L << 10));
            case "Mi" -> number.multiply(BigDecimal.valueOf(1L << 20));
            case "Gi" -> number.multiply(BigDecimal.valueOf(1L << 30));
            case "Ti" -> number.multiply(BigDecimal.valueOf(1L << 40));
            case "Pi" -> number.multiply(BigDecimal.valueOf(1L << 50));
            case "Ei" -> number.multiply(BigDecimal.valueOf(1L << 60));
            default -> BigDecimal.ZERO;
        };
        return value.setScale(0, RoundingMode.CEILING).longValue();
    }

    /** A Kubernetes duration such as {@code 30s}, {@code 5m} or {@code 1h30m}; null for {@code Never}. */
    static Duration duration(String value) {
        if ("Never".equals(value)) {
            return null;
        }
        try {
            return Duration.parse("PT" + value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException expected) {
            // The NodePool schema refuses such a value (500ms, say, which the parse above doesn't read); one that got
            // through waits the 30 seconds of Auto Mode's built-in pools.
            return Duration.ofSeconds(30);
        }
    }
}
