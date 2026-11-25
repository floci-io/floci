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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksAutoModeComputeTest {

    private static final String REGION = "us-east-1";
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
    private final EksAutoModeCompute compute = new EksAutoModeCompute(eksService, clusterManager, ec2,
            iam, instanceTypes, mock(EmulatorConfig.class));
    private final Map<String, String> lists = new HashMap<>(Map.of("pods", PENDING_POD,
            "nodepools", POOLS, "nodeclasses", NODE_CLASSES, "nodes", "{\"items\": []}"));

    @BeforeEach
    void cluster() {
        when(clusterManager.execInContainerForResult(eq("k3s-1"), argThat(cmd -> cmd.length > 2 && "get".equals(cmd[1])),
                anyInt())).thenAnswer(inv -> {
                    String[] cmd = inv.getArgument(1);
                    return new ContainerExec.Result(0, lists.get(cmd[2]), "", false);
                });
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
        stuck.setLaunchTime(Instant.now().minus(EksAutoModeCompute.REGISTRATION_WAIT).minusSeconds(1));
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

    @Test
    void aDeletedClustersInstancesAreTerminatedAndItsProfilesDeleted() {
        Instance node = new Instance();
        node.setInstanceId("i-node");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(node));
        when(ec2.describeInstances(eq(REGION), anyList(), any())).thenReturn(List.of(reservation));
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
        joined.setLaunchTime(Instant.now());
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
     * Floci's own Ready node does between its creation and its status: that doesn't take its room, as Karpenter
     * counts it, so its pending pod waits for it rather than get another node. A taint of its own does.
     */
    @Test
    void aStartingNodesNotReadyTaintDoesntTakeItsRoom() {
        Instance joined = new Instance();
        joined.setInstanceId("i-joined");
        joined.setInstanceType("c7a.large");
        joined.setLaunchTime(Instant.now());
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

        lists.put("nodes", node.formatted("maintenance"));
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
