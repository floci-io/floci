package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Instance family names: category letters, generation digits, then attribute letters, as in {@code c7gd}. */
    private static final Pattern FAMILY = Pattern.compile("^([a-z]+)(\\d+)([a-z-]*)$");
    private static final Pattern QUANTITY = Pattern.compile("^([0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)([a-zA-Z]*)$");
    static final String ZONE_LABEL = "topology.kubernetes.io/zone";
    /** The labels Floci gives its nodes: a pool requirement on any other label is left out. */
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
            } catch (NumberFormatException e) {
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

    /** A NodePool: its NodeClass, its requirements, the taints and labels its nodes get, and its weight. */
    record NodePool(String name, String nodeClass, List<Requirement> requirements, List<Taint> taints,
                    Map<String, String> labels, int weight) {
    }

    /** A pod the scheduler couldn't place, with what it asks of a node and the resources it requests. */
    record PendingPod(String namespace, String name, long cpuMillis, long memoryBytes,
                      Map<String, String> nodeSelector, List<List<Requirement>> affinityTerms,
                      List<Toleration> tolerations) {

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
     * A node Auto Mode launched for a pool, joined or still joining, with the CPU and memory its pods request: a
     * pending pod it has room for is left to the scheduler rather than given another node.
     */
    record PoolNode(NodePool pool, CatalogInstanceType instanceType, String zone, long usedCpuMillis,
                    long usedMemoryBytes, boolean schedulable, List<Taint> nodeTaints) {

        /** A node still joining: schedulable, with no taints but its pool's. */
        PoolNode(NodePool pool, CatalogInstanceType instanceType, String zone, long usedCpuMillis, long usedMemoryBytes) {
            this(pool, instanceType, zone, usedCpuMillis, usedMemoryBytes, true, List.of());
        }

        /** The taints a pod must tolerate there: the pool's and any the node carries besides. */
        List<Taint> taints() {
            List<Taint> all = new ArrayList<>(pool.taints());
            nodeTaints.stream().filter(t -> !all.contains(t)).forEach(all::add);
            return all;
        }
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
                if (candidate(pool, sized, zonesOf(pool, zonesByNodeClass), pod, pod.cpuMillis(), pod.memoryBytes())
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
            Optional<Placement> placement = candidate(pool, sized, placesIn, largest, cpu, memory)
                    .filter(p -> group.stream().allMatch(pod -> pod.fits(labels(p.type(), pool, p.zone()), pool.taints())))
                    .or(() -> candidate(pool, sized, placesIn, largest, largest.cpuMillis(), largest.memoryBytes()));
            placement.ifPresent(p -> launches.add(new Launch(pool, p.type(), p.zone(),
                    labels(p.type(), pool, p.zone()), List.copyOf(group))));
        }
        return launches;
    }

    /**
     * The pods none of the pools' nodes has room for: each pod takes room on the first schedulable node whose
     * labels and taints, the pool's and the node's own, it accepts and whose free CPU and memory fit its requests,
     * as Karpenter counts its nodes, those still joining among them, before it launches another. A cordoned node
     * has no room.
     */
    static List<PendingPod> withoutRoom(List<PendingPod> pods, List<PoolNode> nodes) {
        long[] freeCpu = new long[nodes.size()];
        long[] freeMemory = new long[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            PoolNode node = nodes.get(i);
            freeCpu[i] = node.instanceType().vcpu * 1000L - node.usedCpuMillis();
            freeMemory[i] = node.instanceType().memoryMib * 1024L * 1024L - node.usedMemoryBytes();
        }
        List<PendingPod> left = new ArrayList<>();
        for (PendingPod pod : pods) {
            boolean placed = false;
            for (int i = 0; i < nodes.size() && !placed; i++) {
                PoolNode node = nodes.get(i);
                if (node.schedulable() && freeCpu[i] >= pod.cpuMillis() && freeMemory[i] >= pod.memoryBytes()
                        && pod.fits(labels(node.instanceType(), node.pool(), node.zone()), node.taints())) {
                    freeCpu[i] -= pod.cpuMillis();
                    freeMemory[i] -= pod.memoryBytes();
                    placed = true;
                }
            }
            if (!placed) {
                left.add(pod);
            }
        }
        return left;
    }

    private static List<String> zonesOf(NodePool pool, Map<String, List<String>> zonesByNodeClass) {
        List<String> zones = zonesByNodeClass.getOrDefault(pool.nodeClass(), List.of());
        return zones.isEmpty() ? Collections.singletonList(null) : zones;
    }

    private static Optional<Placement> candidate(NodePool pool, List<CatalogInstanceType> sized, List<String> zones,
                                                 PendingPod pod, long cpuMillis, long memoryBytes) {
        for (CatalogInstanceType type : sized) {
            if (type.vcpu * 1000L < cpuMillis || type.memoryMib * 1024L * 1024L < memoryBytes) {
                continue;
            }
            for (String zone : zones) {
                Map<String, String> labels = labels(type, pool, zone);
                if (poolAllows(pool, labels) && pod.fits(labels, pool.taints())) {
                    return Optional.of(new Placement(type, zone));
                }
            }
        }
        return Optional.empty();
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
            pools.add(new NodePool(item.path("metadata").path("name").asText(),
                    template.path("spec").path("nodeClassRef").path("name").asText("default"),
                    requirements(template.path("spec").path("requirements")), List.copyOf(taints), labels,
                    item.path("spec").path("weight").asInt(0)));
        }
        return pools;
    }

    /**
     * The pods of {@code kubectl get pods -A -o json} the scheduler marked unschedulable and a new node could take.
     * As Karpenter does, a DaemonSet's pods and pods pinned to a named node ({@code matchFields}) are left out, as
     * are pods whose required node affinity can match no node (only empty terms).
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
            JsonNode termList = spec.path("affinity").path("nodeAffinity")
                    .path("requiredDuringSchedulingIgnoredDuringExecution").path("nodeSelectorTerms");
            boolean pinned = false;
            List<List<Requirement>> terms = new ArrayList<>();
            for (JsonNode term : termList) {
                pinned |= !term.path("matchFields").isEmpty();
                // An empty term matches no node, as Kubernetes reads it, so it adds no alternative.
                if (!term.path("matchExpressions").isEmpty()) {
                    terms.add(requirements(term.path("matchExpressions")));
                }
            }
            if (daemonSet || pinned || (!termList.isEmpty() && terms.isEmpty())) {
                continue;
            }
            long[] requests = requests(spec);
            Map<String, String> selector = new LinkedHashMap<>();
            spec.path("nodeSelector").properties().forEach(e -> selector.put(e.getKey(), e.getValue().asText()));
            List<Toleration> tolerations = new ArrayList<>();
            spec.path("tolerations").forEach(t -> tolerations.add(new Toleration(t.path("key").asText(""),
                    t.path("operator").asText("Equal"), t.path("value").asText(""), t.path("effect").asText(""))));
            pods.add(new PendingPod(item.path("metadata").path("namespace").asText(),
                    item.path("metadata").path("name").asText(), requests[0], requests[1], selector, List.copyOf(terms),
                    List.copyOf(tolerations)));
        }
        return pods;
    }

    /**
     * The CPU (millicores) and memory (bytes) the pods of {@code kubectl get pods -A -o json} bound to each node
     * request, those that finished left out.
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
            long[] total = byNode.computeIfAbsent(node, k -> new long[2]);
            total[0] += requests[0];
            total[1] += requests[1];
        }
        return byNode;
    }

    /**
     * A pod spec's CPU (millicores) and memory (bytes) requests as the scheduler counts them: its containers' sum,
     * or its largest init container's when that is larger, plus the pod's overhead.
     */
    private static long[] requests(JsonNode spec) {
        long[] total = new long[2];
        for (JsonNode container : spec.path("containers")) {
            JsonNode requests = container.path("resources").path("requests");
            total[0] += cpuMillis(requests.path("cpu").asText(""));
            total[1] += bytes(requests.path("memory").asText(""));
        }
        for (JsonNode container : spec.path("initContainers")) {
            JsonNode requests = container.path("resources").path("requests");
            total[0] = Math.max(total[0], cpuMillis(requests.path("cpu").asText("")));
            total[1] = Math.max(total[1], bytes(requests.path("memory").asText("")));
        }
        total[0] += cpuMillis(spec.path("overhead").path("cpu").asText(""));
        total[1] += bytes(spec.path("overhead").path("memory").asText(""));
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
}
