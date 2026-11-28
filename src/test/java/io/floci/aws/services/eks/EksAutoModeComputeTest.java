package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.eks.model.CertificateAuthority;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ComputeConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksAutoModeComputeTest {

    private static final String REGION = "us-east-1";
    /** The drain of Floci's own Ready node, which has no kubelet to finish a pod's graceful shutdown. */
    private static final String[] DRAIN_NODE_1 = {"kubectl", "drain", "node-1", "--ignore-daemonsets",
        "--delete-emptydir-data", "--force", "--timeout=10s", "--grace-period=0"};
    private static final String PENDING_POD = """
            {"items": [{"metadata": {"namespace": "default", "name": "web"},
              "spec": {"nodeSelector": {"karpenter.sh/nodepool": "general-purpose"},
                       "containers": [{"resources": {"requests": {"cpu": "100m", "memory": "64Mi"}}}]},
              "status": {"phase": "Pending", "conditions": [{"type": "PodScheduled", "status": "False",
                         "reason": "Unschedulable"}]}}]}""";
    private static final String POOLS = """
            {"items": [{"metadata": {"name": "general-purpose"}, "spec": {"template": {"spec": {
              "nodeClassRef": {"name": "default"},
              "requirements": [{"key": "kubernetes.io/arch", "operator": "In", "values": ["amd64"]}]}}}}]}""";
    private static final String NODE_CLASSES = """
            {"items": [{"metadata": {"name": "default"}, "spec": {"role": "auto-node",
              "subnetSelectorTerms": [{"id": "subnet-a"}, {"id": "subnet-b"}],
              "securityGroupSelectorTerms": [{"id": "sg-cluster"}]}}]}""";

    private final EksClusterManager clusterManager = mock(EksClusterManager.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final IamService iam = mock(IamService.class);
    private final Ec2InstanceTypeCatalog instanceTypes = mock(Ec2InstanceTypeCatalog.class);
    private final EksService eksService = mock(EksService.class);
    private final MutableClock clock = new MutableClock();
    private final EksAutoModeCompute compute = new EksAutoModeCompute(eksService, clusterManager, ec2,
            iam, instanceTypes, mock(EmulatorConfig.class), clock);
    private final Map<String, String> lists = new HashMap<>(Map.of("pods", PENDING_POD,
            "nodepools", POOLS, "nodeclasses", NODE_CLASSES, "nodes", "{\"items\": []}", "daemonsets",
            "{\"items\": []}", "nodeclaims", "{\"items\": []}"));

    @BeforeEach
    void cluster() {
        when(clusterManager.execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 2 && "get".equals(cmd[1])),
                anyInt())).thenAnswer(inv -> {
                    String[] cmd = inv.getArgument(1);
                    return new ContainerExec.Result(0, lists.get(cmd[2]), "", false);
                });
        when(clusterManager.execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 1 && !"get".equals(cmd[1])),
                anyInt())).thenReturn(new ContainerExec.Result(0, "", "", false));
        when(clusterManager.imageCatalog()).thenReturn(new Ec2ImageCatalog());
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of());
        when(eksService.runningClusters()).thenReturn(List.of(new AccountAwareStorageBackend.AccountEntry<>(
                "000000000000", "prod", cluster("prod"))));
        when(ec2.describeSubnets(eq(REGION), anyList(), any())).thenReturn(List.of(subnet("subnet-a", "us-east-1a"),
                subnet("subnet-b", "us-east-1b")));
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c7a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        large.supportedArchitectures = List.of("x86_64");
        when(instanceTypes.instanceTypes()).thenReturn(List.of(large));
        when(iam.listInstanceProfiles(anyString())).thenReturn(List.of());
        when(iam.createInstanceProfile(anyString(), anyString())).thenAnswer(inv -> new InstanceProfile("AIPA1",
                inv.getArgument(0), inv.getArgument(1), "arn:aws:iam::000000000000:instance-profile"
                + inv.getArgument(1) + inv.getArgument(0)));
        Instance launched = new Instance();
        launched.setInstanceId("i-1");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(launched));
        when(ec2.runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(), anyString(),
                anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(), any(),
                anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(reservation);
    }

    @Test
    void anUnschedulablePodGetsAnInstanceOfItsPoolInTheNodeClassesSubnetWithItsProfileTagsAndBootstrap() {
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        ArgumentCaptor<List<String>> groups = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> userData = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> profile = ArgumentCaptor.forClass(String.class);
        // The EKS optimized AL2023 x86_64 image of the cluster's version, from the bundled catalog.
        verify(ec2).runInstances(eq(REGION), eq("ami-0abcdef1234567891"), eq("c7a.large"), eq(1), eq(1), isNull(),
                groups.capture(), eq("subnet-a"), anyString(), tags.capture(), userData.capture(), profile.capture(),
                isNull(), isNull(), eq(0), isNull(), isNull(), isNull(), anyString(), eq(false), isNull(), isNull(),
                isNull(), eq(false), isNull(), isNull());
        assertEquals(List.of("sg-cluster"), groups.getValue());
        assertEquals(List.of("eks:eks-cluster-name=prod", "eks:kubernetes-node-pool-name=general-purpose",
                        "eks:kubernetes-node-class-name=default", "kubernetes.io/cluster/prod=owned",
                        "aws:ec2:managed-launch=eks-auto"),
                tags.getValue().stream().map(t -> t.getKey() + "=" + t.getValue()).toList());
        assertEquals("""
                apiVersion: node.eks.aws/v1alpha1
                kind: NodeConfig
                spec:
                  cluster:
                    name: prod
                    apiServerEndpoint: https://prod.example
                    certificateAuthority: Q0E=
                  kubelet:
                    flags:
                    - --node-labels=kubernetes.io/arch=amd64,kubernetes.io/os=linux,node.kubernetes.io/instance-type=c7a.large,karpenter.sh/capacity-type=on-demand,eks.amazonaws.com/instance-category=c,eks.amazonaws.com/instance-generation=7,eks.amazonaws.com/instance-family=c7a,eks.amazonaws.com/instance-size=large,eks.amazonaws.com/instance-cpu=2,eks.amazonaws.com/instance-memory=4096,karpenter.sh/nodepool=general-purpose,eks.amazonaws.com/compute-type=auto,topology.kubernetes.io/zone=us-east-1a
                """, userData.getValue());
        // The node registers with those labels, as a kubelet given the flag does.
        assertEquals("general-purpose", EksBootstrapUserData.nodeLabels(userData.getValue()).get("karpenter.sh/nodepool"));
        assertEquals("prod", EksBootstrapUserData.extractClusterName(userData.getValue()).orElseThrow());
        ArgumentCaptor<String> profileName = ArgumentCaptor.forClass(String.class);
        verify(iam).createInstanceProfile(profileName.capture(), eq("/eks/us-east-1/prod/"));
        assertTrue(profileName.getValue().matches("eks-us-east-1-prod-\\d+"));
        verify(iam).addRoleToInstanceProfile(profileName.getValue(), "auto-node");
        assertEquals("arn:aws:iam::000000000000:instance-profile/eks/us-east-1/prod/" + profileName.getValue(),
                profile.getValue());
        // Its NodeClaim, named in the instance's tag, then the claim's launched status.
        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-1");
        verifyTaggedWithClaim("i-1", claim);
        verify(clusterManager).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length == 3
                && "sh".equals(cmd[0]) && cmd[2].contains("\"kind\":\"NodeClaim\"")
                && cmd[2].contains("\"name\":\"" + claim + "\"")), anyInt());
        verifyClaimMarkedLaunched(claim);
    }

    /**
     * Each pass keeps the NodeClaims Auto Mode manages in step: one whose node has joined is marked registered,
     * initialized and ready; one whose instance is gone loses Karpenter's finalizer, keeping any other, and is deleted.
     * A claim Auto Mode doesn't manage is left alone.
     */
    @Test
    void nodeClaimsFollowTheirInstancesAndNodes() {
        joinedNode("i-node", "node-1");
        String live = EksAutoModeNodeClaims.name("general-purpose", "i-node");
        String gone = EksAutoModeNodeClaims.name("general-purpose", "i-gone");
        aRunningPodOn("node-1", "");
        aReadyNode("node-1");
        lists.put("nodeclaims", "{\"items\": [" + managedClaim(live, "") + ", "
                + managedClaim(gone, ", \"example.com/audit\"") + ", "
                + "{\"metadata\": {\"name\": \"someone-elses\", "
                + "\"finalizers\": [\"karpenter.sh/termination\"]}}]}");
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(clusterManager).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 4
                && "patch".equals(cmd[1]) && live.equals(cmd[3]) && "--subresource=status".equals(cmd[4])
                && cmd[cmd.length - 1].contains("\"nodeName\":\"node-1\"")
                && cmd[cmd.length - 1].contains("\"type\":\"Ready\",\"status\":\"True\"")), anyInt());
        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "patch", "nodeclaim", gone,
                "--type=merge", "-p", "{\"metadata\":{\"finalizers\":[\"example.com/audit\"]}}"}, 15);
        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "nodeclaim", gone,
                "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete",
                "nodeclaim", live, "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 3
                && "someone-elses".equals(cmd[3])), anyInt());
    }

    /**
     * A launched instance whose derived claim name another instance of its pool has already gets another, so two
     * nodes never share one claim.
     */
    @Test
    void aLaunchedInstanceWhoseClaimNameIsTakenGetsAnother() {
        Instance other = new Instance();
        other.setInstanceId("i-other");
        String taken = EksAutoModeNodeClaims.name("general-purpose", "i-1");
        other.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose"),
                new Tag(EksAutoModeCompute.NODECLAIM_TAG, taken)));
        instancesByState(List.of(), List.of(other), List.of());

        compute.reconcile(cluster("prod"), REGION);

        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-1", 1);
        verifyTaggedWithClaim("i-1", claim);
        verifyClaimMarkedLaunched(claim);
    }

    /** A running instance whose claim is missing, as when making it failed, gets it again without a new launch. */
    @Test
    void aMissingClaimIsMadeAgainWithoutANewLaunch() {
        joinedNode("i-node", "node-1");
        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-node");
        aRunningPodOn("node-1", "{\"cpu\": \"250m\", \"memory\": \"64Mi\"}");
        aReadyNode("node-1");

        compute.reconcile(cluster("prod"), REGION);

        // It requests what the pods bound to its node request, not nothing.
        verify(clusterManager).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 2
                && "sh".equals(cmd[0]) && cmd[2].contains("\"name\":\"" + claim + "\"")
                && cmd[2].contains("\"requests\":{\"cpu\":\"250m\",\"memory\":\"" + (64L << 20)
                + "\",\"pods\":\"1\"}") && cmd[2].contains("kubectl apply -f -")), anyInt());
        verifyClaimMarkedLaunched(claim);
        verify(ec2, never()).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /**
     * With compute off, a claim left by an earlier pass whose deletion failed is deleted once its instance is
     * terminating.
     */
    @Test
    void withComputeOffALeftoverClaimOfATerminatedInstanceIsDeleted() {
        Instance late = new Instance();
        late.setInstanceId("i-late");
        late.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        instancesByState(List.of(), List.of(), List.of(late));
        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-late");
        lists.put("nodeclaims", "{\"items\": [" + managedClaim(claim, "") + "]}");
        Cluster cluster = cluster("prod");
        cluster.setComputeConfig(new ComputeConfig(false, List.of(), null));

        compute.removeNodes(cluster, REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "nodeclaim", claim,
                "--ignore-not-found=true", "--wait=false"}, 15);
    }

    /**
     * EC2 keeps {@code live} running, {@code stopped} stopped and {@code ended} terminated: each state filter
     * describes the instances in its states.
     */
    private void instancesByState(List<Instance> live, List<Instance> stopped, List<Instance> ended) {
        Map<String, List<Instance>> byState = Map.of("running", live, "stopped", stopped, "terminated", ended);
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenAnswer(inv -> {
            Map<String, List<String>> filters = inv.getArgument(2);
            List<Instance> found = new ArrayList<>();
            byState.forEach((state, instances) -> {
                if (filters.get("instance-state-name").contains(state)) {
                    found.addAll(instances);
                }
            });
            if (found.isEmpty()) {
                return List.of();
            }
            Reservation reservation = new Reservation();
            reservation.setInstances(found);
            return List.of(reservation);
        });
    }

    /** A running pod bound to {@code nodeName}, requesting {@code requests} (a JSON object, or empty for none). */
    private void aRunningPodOn(String nodeName, String requests) {
        String resources = requests.isEmpty() ? ""
                : ", \"containers\": [{\"resources\": {\"requests\": " + requests + "}}]";
        lists.put("pods", "{\"items\": [{\"metadata\": {}, \"spec\": {\"nodeName\": \"" + nodeName + "\""
                + resources + "}, \"status\": {\"phase\": \"Running\"}}]}");
    }

    /** The cluster's one node, {@code nodeName}, Ready. */
    private void aReadyNode(String nodeName) {
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"" + nodeName + "\", \"labels\": {}}, "
                + "\"status\": {\"conditions\": [{\"type\": \"Ready\", \"status\": \"True\"}]}}]}");
    }

    /** That instance {@code instanceId} was tagged with its NodeClaim's name, {@code claim}. */
    private void verifyTaggedWithClaim(String instanceId, String claim) {
        verify(ec2).createTags(eq(REGION), eq(List.of(instanceId)), argThat(tags -> tags.size() == 1
                && EksAutoModeCompute.NODECLAIM_TAG.equals(tags.getFirst().getKey())
                && claim.equals(tags.getFirst().getValue())));
    }

    /** That {@code claim}'s status was patched to say it was launched. */
    private void verifyClaimMarkedLaunched(String claim) {
        verify(clusterManager).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 4
                && "patch".equals(cmd[1]) && claim.equals(cmd[3]) && "--subresource=status".equals(cmd[4])
                && cmd[cmd.length - 1].contains("\"type\":\"Launched\",\"status\":\"True\"")), anyInt());
    }

    /** A claim Auto Mode manages that is being deleted, held by Karpenter's finalizer. */
    private static String deletedClaim(String name) {
        return managedClaim(name, "").replace("\"metadata\": {",
                "\"metadata\": {\"deletionTimestamp\": \"2026-11-27T00:00:00Z\", ");
    }

    private static String managedClaim(String name, String moreFinalizers) {
        return "{\"metadata\": {\"name\": \"" + name + "\", "
                + "\"labels\": {\"app.kubernetes.io/managed-by\": \"eks\"}, "
                + "\"finalizers\": [\"karpenter.sh/termination\"" + moreFinalizers + "]}, \"status\": {}}";
    }

    /**
     * A pool at its limit launches nothing more for a pending pod, and its status says what its joined nodes have,
     * as AWS shows it: their CPU, memory and pods, and how many they are.
     */
    @Test
    void aPoolAtItsLimitLaunchesNothingAndItsStatusShowsItsNodes() {
        joinedNode("i-node", "node-1");
        // Cordoned, so the pending pod has no room on it and would get a node of its own but for the limit.
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}, "
                + "\"spec\": {\"unschedulable\": true}, "
                + "\"status\": {\"capacity\": {\"cpu\": \"2\", \"memory\": \"4194304Ki\", \"pods\": \"110\"}}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"limits\": {\"cpu\": \"2\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(ec2, never()).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "patch", "nodepool",
                "general-purpose", "--subresource=status", "--type=merge", "-p",
                "{\"status\":{\"resources\":{\"cpu\":\"2\",\"memory\":\"4194304Ki\",\"pods\":\"110\","
                        + "\"nodes\":\"1\"},\"nodes\":1}}"}, 15);
    }

    @Test
    void aPoolWhoseNodeClassIsMissingOrWhoseImageTheCatalogLacksLaunchesNothing() {
        lists.put("nodeclasses", "{\"items\": []}");
        compute.reconcile(cluster("prod"), REGION);

        lists.put("nodeclasses", NODE_CLASSES);
        Cluster unsupported = cluster("prod");
        unsupported.setVersion("1.0");
        compute.reconcile(unsupported, REGION);

        verify(ec2, never()).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void aLaunchWhoseClusterWasDeletedMeanwhileIsUndone() {
        // Running when the launch starts, gone once the instance is up.
        when(eksService.runningClusters()).thenReturn(List.of(new AccountAwareStorageBackend.AccountEntry<>(
                "000000000000", "prod", cluster("prod"))), List.of());
        Instance launched = new Instance();
        launched.setInstanceId("i-1");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(launched));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(), List.of(reservation));

        compute.reconcile(cluster("prod"), REGION);

        verify(ec2).terminateInstances(REGION, List.of("i-1"));
    }

    @Test
    void aNodeThatNeverRegisteredIsTerminatedAndItsPodsGetAnother() {
        Instance stuck = new Instance();
        stuck.setInstanceId("i-stuck");
        stuck.setInstanceType("c7a.large");
        stuck.setLaunchTime(clock.instant().minus(EksAutoModeCompute.REGISTRATION_WAIT).minusSeconds(1));
        stuck.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(stuck));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        when(clusterManager.deriveInstanceNodeName(any(), eq(REGION))).thenReturn("never-joined");

        compute.reconcile(cluster("prod"), REGION);

        verify(ec2).terminateInstances(REGION, List.of("i-stuck"));
        verify(ec2).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /**
     * As Auto Mode consolidates: a node that runs no workload for its pool's consolidateAfter is tainted as
     * disrupted, its node taken out and its instance terminated; a pod running on it again starts the wait over.
     */
    @Test
    void aNodeEmptyForItsPoolsConsolidateAfterIsRemoved() {
        Instance node = joinedNode("i-node", "node-1");
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"30s\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);
        clock.advance(Duration.ofSeconds(20));
        // A pod runs there for a while, which starts the wait over.
        aRunningPodOn("node-1", "");
        compute.reconcile(cluster, REGION);
        lists.put("pods", "{\"items\": []}");
        clock.advance(Duration.ofSeconds(20));
        compute.reconcile(cluster, REGION);
        clock.advance(Duration.ofSeconds(20));
        compute.reconcile(cluster, REGION);
        verify(ec2, never()).terminateInstances(REGION, List.of("i-node"));

        clock.advance(Duration.ofSeconds(11));
        compute.reconcile(cluster, REGION);

        // Tainted as disrupted and drained, then its node goes before its instance does.
        InOrder order = inOrder(clusterManager, ec2);
        order.verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node",
                "node-1", "karpenter.sh/disrupted:NoSchedule", "--overwrite"}, 15);
        order.verify(clusterManager).execInContainerForResult("k3s-1", DRAIN_NODE_1, 15);
        order.verify(clusterManager).removeJoinedNode(cluster, node);
        order.verify(ec2).terminateInstances(REGION, List.of("i-node"));
    }

    /**
     * A pod the scheduler bound to the node after the pass read the pods, but before it was tainted, keeps it: the
     * pass looks again once the node is tainted, and takes the taint off.
     */
    @Test
    void aPodBoundJustBeforeTheTaintKeepsTheNode() {
        Instance node = joinedNode("i-node", "node-1");
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"0s\"}, \"template\""));
        when(clusterManager.execInContainerForResult(eq("k3s-1"),
                argThat(cmd -> List.of(cmd).contains("--field-selector")), anyInt()))
                .thenReturn(new ContainerExec.Result(0, "{\"items\": [{\"metadata\": {}, "
                        + "\"spec\": {\"nodeName\": \"node-1\"}, \"status\": {\"phase\": \"Pending\"}}]}", "", false));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node", "node-1",
                "karpenter.sh/disrupted:NoSchedule", "--overwrite"}, 15);
        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node", "node-1",
                "karpenter.sh/disrupted:NoSchedule-"}, 15);
        verify(clusterManager, never()).removeJoinedNode(cluster, node);
        verify(ec2, never()).terminateInstances(anyString(), anyList());
    }

    /**
     * A node a removal left tainted as disrupted, because it was called off and taking the taint off failed or the
     * pass failed after the taint, has it taken off by the next pass once it runs a workload again.
     */
    @Test
    void aBusyNodeLeftTaintedAsDisruptedHasTheTaintTakenOff() {
        Instance node = joinedNode("i-node", "node-1");
        aRunningPodOn("node-1", "");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}, \"spec\": "
                + "{\"unschedulable\": true, "
                + "\"taints\": [{\"key\": \"karpenter.sh/disrupted\", \"effect\": \"NoSchedule\"}]}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"0s\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        // A drain that failed left it cordoned too: that is undone first, and the taint last.
        InOrder order = inOrder(clusterManager);
        order.verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "uncordon", "node-1"}, 15);
        order.verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node",
                "node-1", "karpenter.sh/disrupted:NoSchedule-"}, 15);
        verify(clusterManager, never()).removeJoinedNode(cluster, node);
        verify(ec2, never()).terminateInstances(anyString(), anyList());
    }

    /**
     * The node of an Auto Mode instance that is terminated, one that registered while a pass terminated its
     * instance, or whose deletion failed after it, is deleted by the next pass. A node no Auto Mode instance is
     * named for stays, though it carries a pool's label and the Auto Mode compute label, as the cluster's own node
     * can; so does one whose name a live instance's node has again.
     */
    @Test
    void theNodeOfATerminatedAutoModeInstanceIsDeleted() {
        Instance live = joinedNode("i-node", "node-1");
        Instance dead = new Instance();
        dead.setInstanceId("i-dead");
        Instance reused = new Instance();
        reused.setInstanceId("i-old");
        Instance replaced = new Instance();
        replaced.setInstanceId("i-gone");
        when(clusterManager.deriveInstanceNodeName(dead, REGION)).thenReturn("stray");
        when(clusterManager.deriveInstanceNodeName(reused, REGION)).thenReturn("node-1");
        when(clusterManager.deriveInstanceNodeName(replaced, REGION)).thenReturn("taken");
        instancesByState(List.of(live), List.of(), List.of(dead, reused, replaced));
        lists.put("pods", "{\"items\": []}");
        String autoLabels = "{\"karpenter.sh/nodepool\": \"general-purpose\", "
                + "\"eks.amazonaws.com/compute-type\": \"auto\"}";
        // A node group's worker joined under the name of a terminated Auto Mode instance's node: its provider ID is
        // its own.
        lists.put("nodes", "{\"items\": ["
                + "{\"metadata\": {\"name\": \"node-1\", \"labels\": " + autoLabels + "}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-node\"}},"
                + "{\"metadata\": {\"name\": \"stray\", \"labels\": " + autoLabels + "}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-dead\"}},"
                + "{\"metadata\": {\"name\": \"taken\", \"labels\": {}}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-worker\"}},"
                + "{\"metadata\": {\"name\": \"cluster-node\", \"labels\": " + autoLabels + "}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"Never\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node", "stray",
                "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "node-1", "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "cluster-node", "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "taken", "--ignore-not-found=true", "--wait=false"}, 15);
    }

    /**
     * A live instance's node left under its private DNS name from before Auto Mode nodes took their instance IDs,
     * as a cluster kept from before this change has, goes on the next pass; its node under the instance ID stays, and
     * so does a node of that DNS name another instance's provider ID names.
     */
    @Test
    void aLiveInstancesNodeLeftUnderItsDnsNameIsDeleted() {
        Instance live = joinedNode("i-node", "i-node");
        when(clusterManager.deriveInstanceDnsName(live, REGION)).thenReturn("ip-10-0-1-10.ec2.internal");
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", "{\"items\": ["
                + "{\"metadata\": {\"name\": \"i-node\", \"labels\": {}}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-node\"}},"
                + "{\"metadata\": {\"name\": \"ip-10-0-1-10.ec2.internal\", \"labels\": {}}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-node\"}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"Never\"}, \"template\""));

        compute.reconcile(cluster("prod"), REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "ip-10-0-1-10.ec2.internal", "--ignore-not-found=true", "--wait=false"}, 15);
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "i-node", "--ignore-not-found=true", "--wait=false"}, 15);

        // Another instance's worker under that name now: its provider ID isn't this instance's.
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"ip-10-0-1-10.ec2.internal\", \"labels\": {}}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-worker\"}}]}");
        compute.reconcile(cluster("prod"), REGION);
        verify(clusterManager, times(1)).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "ip-10-0-1-10.ec2.internal", "--ignore-not-found=true", "--wait=false"}, 15);
    }

    /**
     * With compute off, a worker that was joining when it was terminated and registered while it shut down has its
     * node deleted by the next pass too, as with compute on.
     */
    @Test
    void withComputeOffTheNodeOfAWorkerThatRegisteredWhileTerminatingIsDeleted() {
        Instance late = new Instance();
        late.setInstanceId("i-late");
        when(clusterManager.deriveInstanceNodeName(late, REGION)).thenReturn("late-node");
        instancesByState(List.of(), List.of(), List.of(late));
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"late-node\", \"labels\": {}}, "
                + "\"spec\": {\"providerID\": \"aws:///us-east-1a/i-late\"}}]}");
        Cluster cluster = cluster("prod");
        cluster.setComputeConfig(new ComputeConfig(false, List.of(), null));

        compute.removeNodes(cluster, REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "delete", "node",
                "late-node", "--ignore-not-found=true", "--wait=false"}, 15);
        verify(ec2, never()).terminateInstances(anyString(), anyList());
    }

    /** A node whose kubelet runs keeps its pods' graceful shutdown: the drain gives them their grace period. */
    @Test
    void aKubeletsNodeIsDrainedWithItsPodsGracePeriod() {
        Instance node = joinedNode("i-node", "node-1");
        when(clusterManager.kubeletRegistersNode(node)).thenReturn(true);
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        lists.put("nodepools", "{\"items\": []}");
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "drain", "node-1",
                "--ignore-daemonsets", "--delete-emptydir-data", "--force", "--timeout=10s"}, 15);
        verify(clusterManager).removeJoinedNode(cluster, node);
    }

    /**
     * The nodes of a NodePool that was deleted, or that UpdateClusterConfig dropped, go as Karpenter removes them:
     * tainted as disrupted and drained, whatever they run, then deleted and terminated.
     */
    @Test
    void aDeletedPoolsNodeIsDrainedAndRemoved() {
        Instance node = joinedNode("i-node", "node-1");
        aRunningPodOn("node-1", "");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        lists.put("nodepools", "{\"items\": []}");
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        InOrder order = inOrder(clusterManager, ec2);
        order.verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node",
                "node-1", "karpenter.sh/disrupted:NoSchedule", "--overwrite"}, 15);
        order.verify(clusterManager).execInContainerForResult("k3s-1", DRAIN_NODE_1, 15);
        order.verify(clusterManager).removeJoinedNode(cluster, node);
        order.verify(ec2).terminateInstances(REGION, List.of("i-node"));
    }

    /**
     * A NodeClaim deleted while its instance runs, as {@code kubectl delete nodeclaim} does, has its node removed the
     * way a deleted pool's is; Karpenter's finalizer holds the claim until its instance has gone.
     */
    @Test
    void aDeletedClaimsNodeIsDrainedAndRemoved() {
        Instance node = joinedNode("i-node", "node-1");
        aRunningPodOn("node-1", "");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-node");
        lists.put("nodeclaims", "{\"items\": [" + deletedClaim(claim) + "]}");
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        InOrder order = inOrder(clusterManager, ec2);
        order.verify(clusterManager).execInContainerForResult("k3s-1", new String[]{"kubectl", "taint", "node",
                "node-1", "karpenter.sh/disrupted:NoSchedule", "--overwrite"}, 15);
        order.verify(clusterManager).execInContainerForResult("k3s-1", DRAIN_NODE_1, 15);
        order.verify(clusterManager).removeJoinedNode(cluster, node);
        order.verify(ec2).terminateInstances(REGION, List.of("i-node"));
        // The claim keeps its finalizer this pass; the next one, with the instance gone, takes it off.
        verify(clusterManager, never()).execInContainerForResult("k3s-1", new String[]{"kubectl", "patch",
                "nodeclaim", claim, "--type=merge", "-p", "{\"metadata\":{\"finalizers\":null}}"}, 15);
    }

    /** A deleted claim whose instance was stopped has its node deleted, without a drain, and its instance ended. */
    @Test
    void aDeletedClaimsStoppedInstanceIsRemoved() {
        Instance stopped = new Instance();
        stopped.setInstanceId("i-stopped");
        stopped.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        instancesByState(List.of(), List.of(stopped), List.of());
        String claim = EksAutoModeNodeClaims.name("general-purpose", "i-stopped");
        lists.put("nodeclaims", "{\"items\": [" + deletedClaim(claim) + "]}");
        lists.put("pods", "{\"items\": []}");
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        InOrder order = inOrder(clusterManager, ec2);
        order.verify(clusterManager).removeJoinedNode(cluster, stopped);
        order.verify(ec2).terminateInstances(REGION, List.of("i-stopped"));
        verify(clusterManager, never()).execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 1
                && "drain".equals(cmd[1])), anyInt());
    }

    /**
     * A pool's nodes that are already unavailable use up its disruption budget first, as Karpenter counts it: of two
     * nodes, at 10% one may go, and with one not Ready the empty other stays; once it is Ready again, the empty one
     * goes.
     */
    @Test
    void aNotReadyNodeUsesUpThePoolsBudget() {
        Instance empty = joinedNode("i-empty", "empty-node");
        Instance sick = new Instance();
        sick.setInstanceId("i-sick");
        sick.setInstanceType("c7a.large");
        sick.setLaunchTime(clock.instant());
        sick.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        when(clusterManager.deriveInstanceNodeName(sick, REGION)).thenReturn("sick-node");
        Reservation both = new Reservation();
        both.setInstances(List.of(empty, sick));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(both));
        lists.put("pods", "{\"items\": [{\"metadata\": {}, \"spec\": {\"nodeName\": \"sick-node\"}, "
                + "\"status\": {\"phase\": \"Running\"}}]}");
        String nodes = "{\"items\": [{\"metadata\": {\"name\": \"empty-node\", \"labels\": {}}},"
                + "{\"metadata\": {\"name\": \"sick-node\", \"labels\": {}}, \"status\": {\"conditions\": "
                + "[{\"type\": \"Ready\", \"status\": \"%s\"}]}}]}";
        lists.put("nodes", nodes.formatted("Unknown"));
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"0s\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);
        verify(clusterManager, never()).removeJoinedNode(cluster, empty);

        lists.put("nodes", nodes.formatted("True"));
        compute.reconcile(cluster, REGION);
        verify(clusterManager).removeJoinedNode(cluster, empty);
        verify(clusterManager, never()).removeJoinedNode(cluster, sick);
    }

    /**
     * A budget's percentage is of the pool's joined nodes: one still joining isn't counted, as Karpenter leaves out
     * nodes that aren't initialized. Ten empty joined nodes and one joining, at 10%, lose one node a pass, not two.
     */
    @Test
    void aBudgetsPercentageLeavesOutNodesStillJoining() {
        List<Instance> instances = new ArrayList<>();
        StringBuilder nodes = new StringBuilder("{\"items\": [");
        for (int i = 0; i < 11; i++) {
            Instance instance = new Instance();
            instance.setInstanceId("i-" + i);
            instance.setInstanceType("c7a.large");
            instance.setLaunchTime(clock.instant());
            instance.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
            when(clusterManager.deriveInstanceNodeName(instance, REGION)).thenReturn("node-" + i);
            instances.add(instance);
            if (i < 10) {
                nodes.append(i == 0 ? "" : ",").append("{\"metadata\": {\"name\": \"node-").append(i)
                        .append("\", \"labels\": {}}}");
            }
        }
        Reservation reservation = new Reservation();
        reservation.setInstances(instances);
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c7a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        large.supportedArchitectures = List.of("x86_64");
        when(instanceTypes.find("c7a.large")).thenReturn(Optional.of(large));
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", nodes.append("]}").toString());
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"0s\", \"budgets\": [{\"nodes\": \"10%\"}]}, "
                        + "\"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);

        verify(clusterManager, times(1)).removeJoinedNode(eq(cluster), any());
    }

    @Test
    void aPoolThatNeverConsolidatesKeepsItsEmptyNode() {
        joinedNode("i-node", "node-1");
        lists.put("pods", "{\"items\": []}");
        lists.put("nodes", "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}}]}");
        lists.put("nodepools", POOLS.replace("\"spec\": {\"template\"",
                "\"spec\": {\"disruption\": {\"consolidateAfter\": \"Never\"}, \"template\""));
        Cluster cluster = cluster("prod");

        compute.reconcile(cluster, REGION);
        clock.advance(Duration.ofHours(1));
        compute.reconcile(cluster, REGION);

        verify(ec2, never()).terminateInstances(anyString(), anyList());
    }

    private Instance joinedNode(String id, String nodeName) {
        Instance node = new Instance();
        node.setInstanceId(id);
        node.setInstanceType("c7a.large");
        node.setLaunchTime(clock.instant());
        node.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(node));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        when(clusterManager.deriveInstanceNodeName(node, REGION)).thenReturn(nodeName);
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c7a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        large.supportedArchitectures = List.of("x86_64");
        when(instanceTypes.find("c7a.large")).thenReturn(Optional.of(large));
        return node;
    }

    @Test
    void aDeletedClustersInstancesAreTerminatedAndItsProfilesDeleted() {
        Instance node = new Instance();
        node.setInstanceId("i-node");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(node));
        // Running, then still shutting down once after TerminateInstances, then gone.
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation),
                List.of(reservation), List.of());
        InstanceProfile profile = new InstanceProfile("AIPA1", "eks-us-east-1-prod-1", "/eks/us-east-1/prod/",
                "arn:aws:iam::000000000000:instance-profile/eks/us-east-1/prod/eks-us-east-1-prod-1");
        profile.getRoleNames().add("auto-node");
        when(iam.listInstanceProfiles("/eks/us-east-1/prod/")).thenReturn(new ArrayList<>(List.of(profile)));

        compute.clusterDeleted(cluster("prod"), REGION);

        verify(ec2).terminateInstances(REGION, List.of("i-node"));
        verify(iam).removeRoleFromInstanceProfile("eks-us-east-1-prod-1", "auto-node");
        verify(iam).deleteInstanceProfile("eks-us-east-1-prod-1");
    }

    /**
     * A joined node has the room its kubelet says is allocatable, which is less than its instance type when the
     * kubelet reserves some: a pod that fits the type but not what the node offers gets another node.
     */
    @Test
    void aJoinedNodeHasTheRoomItsKubeletSaysIsAllocatable() {
        Instance joined = new Instance();
        joined.setInstanceId("i-joined");
        joined.setInstanceType("c7a.large");
        joined.setLaunchTime(clock.instant());
        joined.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(joined));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        when(clusterManager.deriveInstanceNodeName(any(), eq(REGION))).thenReturn("node-1");
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c7a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        large.supportedArchitectures = List.of("x86_64");
        when(instanceTypes.find("c7a.large")).thenReturn(Optional.of(large));
        Cluster cluster = cluster("prod");
        String node = "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}, "
                + "\"status\": {\"allocatable\": {\"cpu\": \"%s\", \"memory\": \"3Gi\", \"pods\": \"110\"}}}]}";

        lists.put("nodes", node.formatted("1900m"));
        compute.reconcile(cluster, REGION);
        verify(ec2, never()).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());

        lists.put("nodes", node.formatted("50m"));
        compute.reconcile(cluster, REGION);
        verify(ec2).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /**
     * A node that just registered carries {@code node.kubernetes.io/not-ready} until Kubernetes sees it Ready, as
     * Floci's own Ready node does between its creation and its status: while it starts, that doesn't take its room,
     * as Karpenter counts it, so its pending pod waits for it rather than get another node. A node still not ready
     * after the registration wait has no room, so an unhealthy node doesn't hold pods for good.
     */
    @Test
    void aStartingNodesNotReadyTaintDoesntTakeItsRoom() {
        Instance joined = new Instance();
        joined.setInstanceId("i-joined");
        joined.setInstanceType("c7a.large");
        joined.setLaunchTime(clock.instant());
        joined.setTags(List.of(new Tag(EksAutoModeCompute.POOL_TAG, "general-purpose")));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(joined));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        when(clusterManager.deriveInstanceNodeName(any(), eq(REGION))).thenReturn("node-1");
        CatalogInstanceType large = new CatalogInstanceType();
        large.instanceType = "c7a.large";
        large.vcpu = 2;
        large.memoryMib = 4096;
        large.supportedArchitectures = List.of("x86_64");
        when(instanceTypes.find("c7a.large")).thenReturn(Optional.of(large));
        Cluster cluster = cluster("prod");
        String node = "{\"items\": [{\"metadata\": {\"name\": \"node-1\", \"labels\": {}}, "
                + "\"spec\": {\"taints\": [{\"key\": \"%s\", \"effect\": \"NoSchedule\"}]}}]}";

        lists.put("nodes", node.formatted("node.kubernetes.io/not-ready"));
        compute.reconcile(cluster, REGION);
        verify(ec2, never()).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());

        // Still not ready once the registration wait is over, it has no room.
        joined.setLaunchTime(clock.instant().minus(EksAutoModeCompute.REGISTRATION_WAIT).minusSeconds(1));
        compute.reconcile(cluster, REGION);
        verify(ec2).runInstances(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), anyList(),
                anyString(), anyString(), anyList(), anyString(), anyString(), any(), any(), anyInt(), any(), any(),
                any(), anyString(), anyBoolean(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /**
     * Turning compute off deletes each node before its instance is terminated: an instance whose node can't be
     * deleted is kept running, so the next pass, finding it still there, tries again.
     */
    @Test
    void aNodeThatCantBeDeletedKeepsItsInstanceForTheNextPass() {
        Cluster cluster = cluster("prod");
        Instance stuck = new Instance();
        stuck.setInstanceId("i-stuck");
        Instance gone = new Instance();
        gone.setInstanceId("i-gone");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(stuck, gone));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        doThrow(new IllegalStateException("kubectl delete node failed")).doNothing()
                .when(clusterManager).removeJoinedNode(cluster, stuck);

        compute.removeNodes(cluster, REGION);

        verify(ec2).terminateInstances(REGION, List.of("i-gone"));
        verify(ec2, never()).terminateInstances(REGION, List.of("i-stuck", "i-gone"));

        reservation.setInstances(List.of(stuck));
        compute.removeNodes(cluster, REGION);

        verify(ec2).terminateInstances(REGION, List.of("i-stuck"));
    }

    /**
     * An instance shutting down still holds the cluster security group EKS deletes next, so the cleanup waits for it;
     * one that doesn't finish in time fails the delete, which keeps the cluster for a retry.
     */
    @Test
    void aDeletedClustersInstanceThatDoesntFinishTerminatingFailsTheCleanup() {
        Instance node = new Instance();
        node.setInstanceId("i-node");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(node));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
        compute.terminationWait(Duration.ofMillis(300));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> compute.clusterDeleted(cluster("prod"), REGION));

        assertEquals("EKS Auto Mode instances of cluster prod didn't finish terminating within PT0.3S",
                failure.getMessage());
        verify(ec2).terminateInstances(REGION, List.of("i-node"));
    }

    private static Subnet subnet(String id, String zone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(id);
        subnet.setAvailabilityZone(zone);
        return subnet;
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        cluster.setContainerId("k3s-1");
        cluster.setEndpoint("https://" + name + ".example");
        cluster.setCertificateAuthority(new CertificateAuthority("Q0E="));
        cluster.setComputeConfig(new ComputeConfig(true, List.of("general-purpose"), "arn:aws:iam::000000000000:role/auto-node"));
        return cluster;
    }
}
