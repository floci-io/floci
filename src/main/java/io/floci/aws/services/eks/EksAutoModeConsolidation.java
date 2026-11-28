package io.floci.aws.services.eks;

import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.EksAutoModeNodes.NodePool;
import io.floci.aws.services.eks.EksAutoModeNodes.PendingPod;
import io.floci.aws.services.eks.EksAutoModeNodes.PodTerm;
import io.floci.aws.services.eks.EksAutoModeNodes.PoolNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Auto Mode's consolidation of underutilized nodes, as Karpenter does it for a pool whose
 * {@code consolidationPolicy} is {@code WhenEmptyOrUnderutilized} (measured on an Auto Mode cluster in us-east-1): a
 * node whose workload pods have stayed the same for the pool's {@code consolidateAfter} goes when its pods fit on the
 * pool's other nodes, or is replaced by a smaller node that holds them, launched first and Ready before the old node
 * goes. A node a pod annotated {@code karpenter.sh/do-not-disrupt} is on, or one with a pod a PodDisruptionBudget
 * allows no disruption of, stays. What it keeps (since when each node's pods stayed the same, and the replacements
 * under way) is in memory, so a restart starts each wait again.
 */
final class EksAutoModeConsolidation {

    /** A joined, Ready node of a pool that consolidates underutilized nodes, with its workload pods. */
    record Candidate(Instance instance, String nodeName, NodePool pool, CatalogInstanceType type,
                     List<PendingPod> pods, boolean kept) {
    }

    /** By cluster ARN, then instance ID: the names of a node's workload pods, and since when they've been these. */
    private final Map<String, Map<String, Unchanged>> unchanged = new HashMap<>();
    /** By cluster ARN, then the instance ID of a node being replaced: the instance ID of its replacement. */
    private final Map<String, Map<String, String>> replacing = new HashMap<>();

    private record Unchanged(Set<String> pods, Instant since) {
    }

    /** Forgets what a cluster's nodes waited for, as when it has no node left. */
    void forget(String clusterArn) {
        unchanged.remove(clusterArn);
        replacing.remove(clusterArn);
    }

    /** The replacements under way in a cluster, by the instance ID of the node each replaces; the map is live. */
    Map<String, String> replacing(String clusterArn) {
        return replacing.computeIfAbsent(clusterArn, arn -> new LinkedHashMap<>());
    }

    /**
     * The candidates that may go now, in the order Karpenter would try them, fewest pods and least requested first:
     * each one's pods have stayed the same for its pool's {@code consolidateAfter}, no pod keeps it or is covered by
     * one of the {@code blocking} disruption budgets (read only once a node is otherwise due), its pool isn't among
     * {@code poolsReplacing}, and its pool's budgets that cover underutilized nodes allow one more, counting the
     * {@code disrupted} ones of each pool among its {@code joined}.
     * Updates each node's wait; a node not among the candidates waits from the start next time.
     */
    List<Candidate> due(String clusterArn, List<Candidate> candidates, Supplier<List<PodTerm>> blocking,
                        Map<String, Integer> joined, Map<String, Integer> disrupted, Set<String> poolsReplacing,
                        Instant now) {
        Map<String, Unchanged> waits = unchanged.computeIfAbsent(clusterArn, arn -> new HashMap<>());
        Set<String> present = new HashSet<>();
        List<PodTerm> budgets = null;
        List<Candidate> due = new ArrayList<>();
        for (Candidate candidate : candidates) {
            String id = candidate.instance().getInstanceId();
            present.add(id);
            Set<String> names = new TreeSet<>();
            candidate.pods().forEach(pod -> names.add(pod.namespace() + "/" + pod.name()));
            Unchanged wait = waits.get(id);
            if (wait == null || !wait.pods().equals(names)) {
                wait = new Unchanged(names, now);
                waits.put(id, wait);
            }
            NodePool pool = candidate.pool();
            if (!pool.consolidatesUnderutilized() || pool.consolidateAfter() == null || candidate.kept()
                    || poolsReplacing.contains(pool.name()) || wait.since().plus(pool.consolidateAfter()).isAfter(now)
                    || disrupted.getOrDefault(pool.name(), 0)
                            >= pool.disruptableUnderutilized(joined.getOrDefault(pool.name(), 0))) {
                continue;
            }
            // The disruption budgets are read only once a node is otherwise due.
            if (budgets == null) {
                budgets = blocking.get();
            }
            List<PodTerm> covering = budgets;
            if (candidate.pods().stream().noneMatch(pod -> covering.stream()
                    .anyMatch(term -> term.matches(pod.namespace(), pod.labels())))) {
                due.add(candidate);
            }
        }
        waits.keySet().retainAll(present);
        due.sort(Comparator.comparingInt((Candidate c) -> c.pods().size())
                .thenComparingLong(c -> c.pods().stream().mapToLong(PendingPod::cpuMillis).sum()));
        return due;
    }

    /** Starts a node's wait again, as when its consolidation was called off. */
    void restart(String clusterArn, String instanceId) {
        unchanged.getOrDefault(clusterArn, Map.of()).remove(instanceId);
    }

    /**
     * Whether every pod of {@code candidate} has room on its pool's {@code others}, the joined and Ready nodes that
     * stay, as the scheduler would place them.
     */
    static boolean fitsElsewhere(Candidate candidate, List<PoolNode> others) {
        List<PoolNode> stay = others.stream()
                .filter(node -> node.pool().name().equals(candidate.pool().name())
                        && !node.instanceId().equals(candidate.instance().getInstanceId()))
                .toList();
        return !stay.isEmpty() && EksAutoModeNodes.withoutRoom(candidate.pods(), stay).left().isEmpty();
    }

    /** Whether {@code replacement} is smaller than {@code current}: fewer vCPUs, or as many and less memory. */
    static boolean smaller(CatalogInstanceType replacement, CatalogInstanceType current) {
        return replacement.vcpu < current.vcpu
                || (replacement.vcpu == current.vcpu && replacement.memoryMib < current.memoryMib);
    }
}
