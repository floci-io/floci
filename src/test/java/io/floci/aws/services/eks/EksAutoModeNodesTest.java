package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.eks.EksAutoModeNodes.Launch;
import io.floci.aws.services.eks.EksAutoModeNodes.NodePool;
import io.floci.aws.services.eks.EksAutoModeNodes.PendingPod;
import io.floci.aws.services.eks.EksAutoModeNodes.Requirement;
import io.floci.aws.services.eks.EksAutoModeNodes.Toleration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksAutoModeNodesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The built-in pools as Floci installs them, with only the fields the planner reads. */
    private static final String POOLS = """
            {"items": [
              {"metadata": {"name": "general-purpose"}, "spec": {"template": {"spec": {
                "nodeClassRef": {"name": "default"},
                "requirements": [
                  {"key": "karpenter.sh/capacity-type", "operator": "In", "values": ["on-demand"]},
                  {"key": "eks.amazonaws.com/instance-category", "operator": "In", "values": ["c", "m", "r"]},
                  {"key": "eks.amazonaws.com/instance-generation", "operator": "Gt", "values": ["4"]},
                  {"key": "kubernetes.io/arch", "operator": "In", "values": ["amd64"]},
                  {"key": "kubernetes.io/os", "operator": "In", "values": ["linux"]}]}}}},
              {"metadata": {"name": "system"}, "spec": {"template": {"spec": {
                "nodeClassRef": {"name": "default"},
                "requirements": [
                  {"key": "eks.amazonaws.com/instance-category", "operator": "In", "values": ["c", "m", "r"]},
                  {"key": "eks.amazonaws.com/instance-generation", "operator": "Gt", "values": ["4"]},
                  {"key": "kubernetes.io/arch", "operator": "In", "values": ["amd64", "arm64"]}],
                "taints": [{"key": "CriticalAddonsOnly", "effect": "NoSchedule"}]}}}}
            ]}""";
    private static final List<CatalogInstanceType> TYPES = List.of(
            type("t3.medium", 2, 4096, "x86_64"),
            type("c7a.medium", 1, 2048, "x86_64"),
            type("c7a.large", 2, 4096, "x86_64"),
            type("c7a.xlarge", 4, 8192, "x86_64"),
            type("m7a.large", 2, 8192, "x86_64"),
            type("c7g.large", 2, 4096, "arm64"),
            type("m4.large", 2, 8192, "x86_64"));

    @Test
    void aSmallPodGetsTheSmallestTwoVcpuTypeOfTheGeneralPurposePool() throws Exception {
        List<Launch> launches = EksAutoModeNodes.plan(pools(), List.of(pod("web", "100m", "64Mi")), TYPES, Map.of());

        assertEquals(1, launches.size());
        assertEquals("general-purpose", launches.getFirst().pool().name());
        // c7a.medium has 1 vCPU, t3 is not of category c, m or r, and m4 is of generation 4.
        assertEquals("c7a.large", launches.getFirst().instanceType().instanceType);
        Map<String, String> expected = Map.of("kubernetes.io/arch", "amd64", "kubernetes.io/os", "linux",
                "node.kubernetes.io/instance-type", "c7a.large", "karpenter.sh/capacity-type", "on-demand",
                "eks.amazonaws.com/instance-category", "c", "eks.amazonaws.com/instance-generation", "7",
                "eks.amazonaws.com/instance-family", "c7a", "eks.amazonaws.com/instance-size", "large",
                "karpenter.sh/nodepool", "general-purpose", "eks.amazonaws.com/compute-type", "auto");
        expected.forEach((key, value) -> assertEquals(value, launches.getFirst().labels().get(key), key));
        assertEquals("2", launches.getFirst().labels().get("eks.amazonaws.com/instance-cpu"));
        assertEquals("4096", launches.getFirst().labels().get("eks.amazonaws.com/instance-memory"));
    }

    @Test
    void podsOfOnePoolShareANodeSizedForAllOfThem() throws Exception {
        List<Launch> launches = EksAutoModeNodes.plan(pools(),
                List.of(pod("a", "1500m", "1Gi"), pod("b", "1", "1Gi")), TYPES, Map.of());

        assertEquals(1, launches.size());
        assertEquals("c7a.xlarge", launches.getFirst().instanceType().instanceType);
        assertEquals(2, launches.getFirst().pods().size());
    }

    @Test
    void podsThatDontFitOneNodeTogetherGetANodeForTheLargestNow() throws Exception {
        List<Launch> launches = EksAutoModeNodes.plan(pools(),
                List.of(pod("a", "3", "1Gi"), pod("b", "3", "1Gi")), TYPES, Map.of());

        assertEquals("c7a.xlarge", launches.getFirst().instanceType().instanceType);
    }

    @Test
    void onlyAPodToleratingTheSystemTaintGoesToTheSystemPoolAndItMayRunOnArm() throws Exception {
        PendingPod critical = new PendingPod("kube-system", "addon", 100, 64L << 20,
                Map.of("karpenter.sh/nodepool", "system", "kubernetes.io/arch", "arm64"), List.of(),
                List.of(new Toleration("CriticalAddonsOnly", "Exists", "", "")));
        PendingPod plain = new PendingPod("default", "plain", 100, 64L << 20,
                Map.of("karpenter.sh/nodepool", "system"), List.of(), List.of());

        List<Launch> launches = EksAutoModeNodes.plan(pools(), List.of(critical, plain), TYPES, Map.of());

        assertEquals(1, launches.size());
        assertEquals("system", launches.getFirst().pool().name());
        assertEquals("c7g.large", launches.getFirst().instanceType().instanceType);
        assertEquals(List.of("addon"), launches.getFirst().pods().stream().map(PendingPod::name).toList());
    }

    @Test
    void aHeavierPoolIsTriedFirst() throws Exception {
        String weighted = POOLS.replace("{\"metadata\": {\"name\": \"system\"}, \"spec\": {",
                "{\"metadata\": {\"name\": \"system\"}, \"spec\": {\"weight\": 10, ");
        PendingPod tolerant = new PendingPod("default", "any", 100, 64L << 20, Map.of(), List.of(),
                List.of(new Toleration("", "Exists", "", "")));

        List<Launch> launches = EksAutoModeNodes.plan(EksAutoModeNodes.nodePools(json(weighted)),
                List.of(tolerant), TYPES, Map.of());

        assertEquals("system", launches.getFirst().pool().name());
    }

    @Test
    void aPodNoPoolCanTakeGetsNoNode() throws Exception {
        PendingPod huge = pod("huge", "64", "1Gi");
        PendingPod gpu = new PendingPod("default", "gpu", 100, 0, Map.of("eks.amazonaws.com/instance-gpu-count", "1"),
                List.of(), List.of());

        assertEquals(List.of(), EksAutoModeNodes.plan(pools(), List.of(huge, gpu), TYPES, Map.of()));
    }

    /** As Karpenter counts its nodes: a pod a pool's node has room for gets no other node. */
    @Test
    void aPodANodeOfItsPoolHasRoomForGetsNoOther() throws Exception {
        NodePool general = pools().getFirst();
        CatalogInstanceType large = TYPES.get(2);
        PendingPod small = pod("small", "500m", "512Mi");
        PendingPod big = pod("big", "1500m", "512Mi");

        // 1 vCPU of 2 in use: room for the small pod, not for the big one.
        EksAutoModeNodes.Room room = EksAutoModeNodes.withoutRoom(List.of(small, big),
                List.of(new EksAutoModeNodes.PoolNode("i-1", general, large, null, 1000, 1L << 30)));
        // A system pool node doesn't take pods that don't tolerate its taint, however much room it has.
        EksAutoModeNodes.Room untolerated = EksAutoModeNodes.withoutRoom(List.of(small),
                List.of(new EksAutoModeNodes.PoolNode("i-2", pools().get(1), TYPES.get(3), null, 0, 0)));

        assertEquals(List.of(big), room.left());
        // The node the small pod counts on isn't empty, whatever runs on it.
        assertEquals(Set.of("i-1"), room.counted());
        assertEquals(List.of(small), untolerated.left());
        assertEquals(Set.of(), untolerated.counted());
    }

    /** A cordoned node, or one with a taint of its own the pod doesn't tolerate, has no room for it. */
    @Test
    void aCordonedOrForeignTaintedNodeHasNoRoom() throws Exception {
        NodePool general = pools().getFirst();
        PendingPod small = pod("small", "100m", "64Mi");

        List<PendingPod> cordoned = EksAutoModeNodes.withoutRoom(List.of(small), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000, 1L << 40, 110, false,
                        List.of()))).left();
        List<PendingPod> tainted = EksAutoModeNodes.withoutRoom(List.of(small), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000, 1L << 40, 110, true,
                        List.of(new EksAutoModeNodes.Taint("maintenance", "", "NoSchedule"))))).left();
        List<PendingPod> preferOnly = EksAutoModeNodes.withoutRoom(List.of(small), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000, 1L << 40, 110, true,
                        List.of(new EksAutoModeNodes.Taint("busy", "", "PreferNoSchedule"))))).left();

        assertEquals(List.of(small), cordoned);
        assertEquals(List.of(small), tainted);
        assertEquals(List.of(), preferOnly);
    }

    /** A node whose pod slots are all taken has no room, however much CPU and memory it has left. */
    @Test
    void aNodeWithNoPodSlotLeftHasNoRoom() throws Exception {
        NodePool general = pools().getFirst();
        PendingPod first = pod("first", "100m", "64Mi");
        PendingPod second = pod("second", "100m", "64Mi");

        List<PendingPod> full = EksAutoModeNodes.withoutRoom(List.of(first), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000, 1L << 40, 0, true,
                        List.of()))).left();
        List<PendingPod> oneSlot = EksAutoModeNodes.withoutRoom(List.of(first, second), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000, 1L << 40, 1, true,
                        List.of()))).left();

        assertEquals(List.of(first), full);
        assertEquals(List.of(second), oneSlot);
    }

    /**
     * A replica whose required anti-affinity on {@code kubernetes.io/hostname} keeps it off its siblings' nodes has
     * no room on the node of one, whether that one runs there or was counted on it in the same pass; either pod's
     * anti-affinity counts. A term on another topology key isn't modelled, and one for another namespace doesn't
     * match.
     */
    @Test
    void requiredPodAntiAffinityOnTheHostnameKeepsReplicasOnNodesOfTheirOwn() throws Exception {
        NodePool general = pools().getFirst();
        String pods = """
                {"items": [
                  {"metadata": {"namespace": "shop", "name": "web-1", "labels": {"app": "web"}},
                   "spec": {"nodeName": "n1", "affinity": {"podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [
                     {"labelSelector": {"matchLabels": {"app": "web"}}, "topologyKey": "kubernetes.io/hostname"}]}}},
                   "status": {"phase": "Running"}},
                  {"metadata": {"namespace": "shop", "name": "web-2", "labels": {"app": "web"}},
                   "spec": {"affinity": {"podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [
                     {"labelSelector": {"matchExpressions": [{"key": "app", "operator": "In", "values": ["web"]}]},
                      "topologyKey": "kubernetes.io/hostname"}]}}},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}},
                  {"metadata": {"namespace": "shop", "name": "zonal", "labels": {"app": "zonal"}},
                   "spec": {"affinity": {"podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [
                     {"labelSelector": {"matchLabels": {"app": "other"}}, "topologyKey": "topology.kubernetes.io/zone"}]}}},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}},
                  {"metadata": {"namespace": "elsewhere", "name": "free", "labels": {"app": "api"}},
                   "spec": {"affinity": {"podAntiAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": [
                     {"labelSelector": {"matchLabels": {"app": "web"}}, "topologyKey": "kubernetes.io/hostname"}]}}},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}}
                ]}""";
        JsonNode list = json(pods);
        Map<String, PendingPod> pending = new LinkedHashMap<>();
        EksAutoModeNodes.unschedulablePods(list).forEach(p -> pending.put(p.name(), p));
        EksAutoModeNodes.PoolNode withWeb1 = new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000,
                1L << 40, 110, true, List.of(), EksAutoModeNodes.boundPodsByNode(list).get("n1"));
        EksAutoModeNodes.PoolNode empty = new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 64_000,
                1L << 40, 110, true, List.of());

        // web-2 can't join web-1, by its own anti-affinity; zonal's term isn't on the hostname, and web-1's doesn't
        // match it; free is in another namespace than web-1's term reads, and its own term reads its namespace, which
        // has no web pod.
        assertEquals(List.of(pending.get("web-2")), EksAutoModeNodes.withoutRoom(List.copyOf(pending.values()),
                List.of(withWeb1)).left());
        // Two replicas counted on one empty node in the same pass: the second has no room there.
        PendingPod web3 = new PendingPod("shop", "web-3", 100, 64L << 20, Map.of(), List.of(), List.of(),
                Map.of("app", "web"), pending.get("web-2").antiAffinity());
        // A pod with no anti-affinity of its own is still kept off the node of one whose anti-affinity matches it.
        PendingPod plain = new PendingPod("shop", "plain", 100, 64L << 20, Map.of(), List.of(), List.of(),
                Map.of("app", "web"), List.of());
        assertEquals(List.of(plain), EksAutoModeNodes.withoutRoom(List.of(plain), List.of(withWeb1)).left());
        assertEquals(List.of(web3), EksAutoModeNodes.withoutRoom(List.of(pending.get("web-2"), web3), List.of(empty)).left());
    }

    /** A joined node's room is what it offers, its allocatable CPU and memory less its pods', not its type's size. */
    @Test
    void aNodeHasOnlyTheRoomItOffers() throws Exception {
        NodePool general = pools().getFirst();
        PendingPod pod = pod("web", "500m", "64Mi");

        List<PendingPod> tooLittleCpu = EksAutoModeNodes.withoutRoom(List.of(pod), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 400, 1L << 30, 110, true,
                        List.of()))).left();
        List<PendingPod> tooLittleMemory = EksAutoModeNodes.withoutRoom(List.of(pod), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 1000, 32L << 20, 110, true,
                        List.of()))).left();
        List<PendingPod> enough = EksAutoModeNodes.withoutRoom(List.of(pod), List.of(
                new EksAutoModeNodes.PoolNode("i-3", general, TYPES.get(2), null, 500, 64L << 20, 110, true,
                        List.of()))).left();

        assertEquals(List.of(pod), tooLittleCpu);
        assertEquals(List.of(pod), tooLittleMemory);
        assertEquals(List.of(), enough);
    }

    @Test
    void theRequestsOfThePodsBoundToEachNodeAreSummedThoseThatFinishedLeftOut() throws Exception {
        String pods = """
                {"items": [
                  {"spec": {"nodeName": "n1", "containers": [{"resources": {"requests": {"cpu": "200m", "memory": "64Mi"}}}]},
                   "status": {"phase": "Running"}},
                  {"spec": {"nodeName": "n1", "containers": [{"resources": {"requests": {"cpu": "300m"}}}]},
                   "status": {"phase": "Pending"}},
                  {"spec": {"nodeName": "n1", "containers": [{"resources": {"requests": {"cpu": "1"}}}]},
                   "status": {"phase": "Succeeded"}},
                  {"spec": {"containers": [{"resources": {"requests": {"cpu": "1"}}}]}, "status": {"phase": "Pending"}}
                ]}""";

        Map<String, long[]> byNode = EksAutoModeNodes.requestsByNode(json(pods));

        assertEquals(List.of("n1"), List.copyOf(byNode.keySet()));
        assertEquals(500, byNode.get("n1")[0]);
        assertEquals(64L << 20, byNode.get("n1")[1]);
        assertEquals(2, byNode.get("n1")[2]);
    }

    /** A pod constrained to a zone gets a node in it, and the node carries the zone's label. */
    @Test
    void aPodAskingForAZoneGetsANodeThere() throws Exception {
        PendingPod zonal = new PendingPod("default", "zonal", 100, 64L << 20,
                Map.of("karpenter.sh/nodepool", "general-purpose", "topology.kubernetes.io/zone", "us-east-1b"),
                List.of(), List.of());

        List<Launch> launches = EksAutoModeNodes.plan(pools(), List.of(zonal), TYPES,
                Map.of("default", List.of("us-east-1a", "us-east-1b")));
        // Another NodeClass's zone is no use to this pool, whose NodeClass has a subnet in us-east-1a only.
        List<Launch> none = EksAutoModeNodes.plan(pools(), List.of(zonal), TYPES,
                Map.of("default", List.of("us-east-1a"), "other", List.of("us-east-1b")));

        assertEquals("us-east-1b", launches.getFirst().zone());
        assertEquals("us-east-1b", launches.getFirst().labels().get("topology.kubernetes.io/zone"));
        assertEquals(List.of(), none);
    }

    /** A pool requirement on an attribute Floci doesn't model is left out rather than blocking the pool. */
    @Test
    void aRequirementOnAnUnmodelledInstanceAttributeDoesntBlockThePool() throws Exception {
        String strict = POOLS.replace(
                "{\"key\": \"kubernetes.io/os\", \"operator\": \"In\", \"values\": [\"linux\"]}",
                "{\"key\": \"kubernetes.io/os\", \"operator\": \"In\", \"values\": [\"linux\"]},"
                        + "{\"key\": \"eks.amazonaws.com/instance-cpu-manufacturer\", \"operator\": \"In\", \"values\": [\"amd\"]}");

        List<Launch> launches = EksAutoModeNodes.plan(EksAutoModeNodes.nodePools(json(strict)),
                List.of(pod("web", "100m", "64Mi")), TYPES, Map.of());

        assertEquals("c7a.large", launches.getFirst().instanceType().instanceType);
    }

    /** Percentages round up, as AWS removed the only node of a pool budgeted at 10%; the least budget wins. */
    @ParameterizedTest
    @CsvSource({"'10%',1,1", "'10%',10,1", "'10%',11,2", "'0',5,0", "'20%;1',10,1", "'',3,1", "'x',3,0"})
    void disruptionBudgets(String budgets, int nodes, int disruptable) {
        NodePool pool = new NodePool("p", "default", List.of(), List.of(), Map.of(), 0, Duration.ofSeconds(30),
                budgets.isEmpty() ? List.of() : List.of(budgets.split(";")));

        assertEquals(disruptable, pool.disruptable(nodes));
    }

    @Test
    void consolidateAfterAndBudgetsAreReadFromThePoolScheduledBudgetsLeftOut() throws Exception {
        String pools = """
                {"items": [{"metadata": {"name": "batch"}, "spec": {
                  "disruption": {"consolidateAfter": "1m30s",
                                 "budgets": [{"nodes": "50%"}, {"nodes": "0", "schedule": "@daily", "duration": "1h"}]},
                  "template": {"spec": {}}}},
                 {"metadata": {"name": "pinned"}, "spec": {"disruption": {"consolidateAfter": "Never"},
                  "template": {"spec": {}}}}]}""";

        List<NodePool> read = EksAutoModeNodes.nodePools(json(pools));

        assertEquals(Duration.ofSeconds(90), read.getFirst().consolidateAfter());
        assertEquals(List.of("50%"), read.getFirst().budgets());
        assertNull(read.get(1).consolidateAfter());
    }

    /** DaemonSet pods and static pods' mirrors don't keep a node busy; finished pods don't either. */
    @Test
    void onlyAWorkloadKeepsANodeBusy() throws Exception {
        String pods = """
                {"items": [
                  {"metadata": {"ownerReferences": [{"kind": "ReplicaSet"}]}, "spec": {"nodeName": "busy"},
                   "status": {"phase": "Running"}},
                  {"metadata": {"ownerReferences": [{"kind": "DaemonSet"}]}, "spec": {"nodeName": "daemons"},
                   "status": {"phase": "Running"}},
                  {"metadata": {"ownerReferences": [{"kind": "Node"}]}, "spec": {"nodeName": "daemons"},
                   "status": {"phase": "Running"}},
                  {"metadata": {}, "spec": {"nodeName": "finished"}, "status": {"phase": "Succeeded"}}
                ]}""";

        assertEquals(Set.of("busy"), EksAutoModeNodes.nodesWithWorkloads(json(pods)));
    }

    @Test
    void requiredNodeAffinityTermsAreAlternatives() {
        PendingPod pod = new PendingPod("default", "p", 0, 0, Map.of(), List.of(
                List.of(new Requirement("kubernetes.io/arch", "In", List.of("arm64"))),
                List.of(new Requirement("eks.amazonaws.com/instance-cpu", "Gt", List.of("3")),
                        new Requirement("eks.amazonaws.com/instance-family", "NotIn", List.of("m7a")))), List.of());

        assertTrue(pod.fits(Map.of("kubernetes.io/arch", "arm64"), List.of()));
        assertTrue(pod.fits(Map.of("kubernetes.io/arch", "amd64", "eks.amazonaws.com/instance-cpu", "4",
                "eks.amazonaws.com/instance-family", "c7a"), List.of()));
        assertFalse(pod.fits(Map.of("kubernetes.io/arch", "amd64", "eks.amazonaws.com/instance-cpu", "2"), List.of()));
    }

    @ParameterizedTest
    @CsvSource({"In,a,true", "In,c,false", "NotIn,c,true", "NotIn,a,false", "Exists,a,true", "DoesNotExist,a,false",
            "Gt,5,true", "Gt,1,false", "Lt,1,true"})
    void requirementOperatorsMatchAsKubernetesDoes(String operator, String value, boolean matches) {
        List<String> values = switch (operator) {
            case "Gt", "Lt" -> List.of("2");
            case "Exists", "DoesNotExist" -> List.of();
            default -> List.of("a", "b");
        };

        assertEquals(matches, new Requirement("k", operator, values).matches(Map.of("k", value)));
    }

    @ParameterizedTest
    @CsvSource({"100m,100", "1,1000", "0.5,500", "2500m,2500", "2e3,2000000", "0.1m,1", ",0", "x,0"})
    void cpuQuantities(String quantity, long millis) {
        assertEquals(millis, EksAutoModeNodes.cpuMillis(quantity == null ? "" : quantity));
    }

    @ParameterizedTest
    @CsvSource({"64Mi,67108864", "1Gi,1073741824", "1.5Gi,1610612736", "1G,1000000000", "1e9,1000000000",
            "128974848,128974848", "500M,500000000", "1Pi,1125899906842624", "1500m,2", ",0"})
    void memoryQuantities(String quantity, long bytes) {
        assertEquals(bytes, EksAutoModeNodes.bytes(quantity == null ? "" : quantity));
    }

    /** The largest init container counts when it asks for more than the containers together, and overhead adds. */
    @Test
    void initContainersAndOverheadCountAsTheSchedulerCountsThem() throws Exception {
        String pods = """
                {"items": [{"metadata": {"namespace": "default", "name": "p"},
                  "spec": {"containers": [{"resources": {"requests": {"cpu": "100m", "memory": "64Mi"}}},
                                          {"resources": {"requests": {"cpu": "100m", "memory": "64Mi"}}}],
                           "initContainers": [{"resources": {"requests": {"cpu": "1", "memory": "32Mi"}}}],
                           "overhead": {"cpu": "50m", "memory": "16Mi"}},
                  "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                             "reason": "Unschedulable"}]}}]}""";

        PendingPod pod = EksAutoModeNodes.unschedulablePods(json(pods)).getFirst();

        assertEquals(1050, pod.cpuMillis());
        assertEquals(144L << 20, pod.memoryBytes());
    }

    /**
     * A sidecar, an init container with {@code restartPolicy: Always}, keeps running: its requests add to the app's,
     * and to those of each init container after it, as the scheduler counts them.
     */
    @Test
    void sidecarsAddToTheContainersAndToTheInitContainersAfterThem() throws Exception {
        String pods = """
                {"items": [{"metadata": {"namespace": "default", "name": "p"},
                  "spec": {"containers": [{"resources": {"requests": {"cpu": "1500m", "memory": "64Mi"}}}],
                           "initContainers": [
                             {"restartPolicy": "Always", "resources": {"requests": {"cpu": "1", "memory": "32Mi"}}},
                             {"resources": {"requests": {"cpu": "100m", "memory": "512Mi"}}}]},
                  "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                             "reason": "Unschedulable"}]}}]}""";

        PendingPod pod = EksAutoModeNodes.unschedulablePods(json(pods)).getFirst();

        // CPU: the app and the sidecar, 1500m + 1. Memory: the second init container beside the sidecar, 512Mi + 32Mi.
        assertEquals(2500, pod.cpuMillis());
        assertEquals(544L << 20, pod.memoryBytes());
    }

    /**
     * Only pods the scheduler marked unschedulable and a new node could take: not a DaemonSet's, not one pinned to
     * a named node, not one whose only affinity term is empty, which matches no node.
     */
    @Test
    void onlyPendingPodsTheSchedulerMarkedUnschedulableAreRead() throws Exception {
        String pods = """
                {"items": [
                  {"metadata": {"namespace": "default", "name": "waiting"},
                   "spec": {"nodeSelector": {"karpenter.sh/nodepool": "general-purpose"},
                            "containers": [{"resources": {"requests": {"cpu": "250m", "memory": "128Mi"}}},
                                           {"resources": {"requests": {"cpu": "250m"}}}],
                            "tolerations": [{"key": "CriticalAddonsOnly", "operator": "Exists"}]},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}},
                  {"metadata": {"namespace": "default", "name": "pulling"}, "spec": {"nodeName": "n1"},
                   "status": {"phase": "Pending"}},
                  {"metadata": {"namespace": "default", "name": "fresh"}, "spec": {},
                   "status": {"phase": "Pending"}},
                  {"metadata": {"namespace": "default", "name": "running"}, "spec": {"nodeName": "n1"},
                   "status": {"phase": "Running"}},
                  {"metadata": {"namespace": "kube-system", "name": "agent",
                                "ownerReferences": [{"kind": "DaemonSet", "name": "agent"}]}, "spec": {},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}},
                  {"metadata": {"namespace": "default", "name": "pinned"}, "spec": {"affinity": {"nodeAffinity": {
                     "requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [{"matchFields": [
                       {"key": "metadata.name", "operator": "In", "values": ["n1"]}]}]}}}},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}},
                  {"metadata": {"namespace": "default", "name": "nowhere"}, "spec": {"affinity": {"nodeAffinity": {
                     "requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [{}]}}}},
                   "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                              "reason": "Unschedulable"}]}}
                ]}""";

        List<PendingPod> pending = EksAutoModeNodes.unschedulablePods(json(pods));

        assertEquals(1, pending.size());
        assertEquals("waiting", pending.getFirst().name());
        assertEquals(500, pending.getFirst().cpuMillis());
        assertEquals(128L << 20, pending.getFirst().memoryBytes());
        assertEquals(Map.of("karpenter.sh/nodepool", "general-purpose"), pending.getFirst().nodeSelector());
        assertEquals(List.of(new Toleration("CriticalAddonsOnly", "Exists", "", "")), pending.getFirst().tolerations());
    }

    private static List<NodePool> pools() throws Exception {
        return EksAutoModeNodes.nodePools(json(POOLS));
    }

    private static PendingPod pod(String name, String cpu, String memory) {
        return new PendingPod("default", name, EksAutoModeNodes.cpuMillis(cpu), EksAutoModeNodes.bytes(memory),
                Map.of("karpenter.sh/nodepool", "general-purpose"), List.of(), List.of());
    }

    private static CatalogInstanceType type(String name, int vcpu, int memoryMib, String architecture) {
        CatalogInstanceType type = new CatalogInstanceType();
        type.instanceType = name;
        type.vcpu = vcpu;
        type.memoryMib = memoryMib;
        type.supportedArchitectures = List.of(architecture);
        return type;
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }
}
