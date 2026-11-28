package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.KubernetesNetworkConfig;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksAutoModeLoadBalancersTest {

    private static final String REGION = "us-east-1";
    private static final String SERVICES = """
            {"items": [
              {"metadata": {"namespace": "shop", "name": "web", "uid": "uid-web",
                            "annotations": {"service.beta.kubernetes.io/aws-load-balancer-scheme": "internet-facing"}},
               "spec": {"type": "LoadBalancer", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "ports": [{"protocol": "TCP", "port": 80, "nodePort": 31080},
                                  {"protocol": "UDP", "port": 53, "nodePort": 31053}]}},
              {"metadata": {"namespace": "shop", "name": "klipper"},
               "spec": {"type": "LoadBalancer", "ports": [{"protocol": "TCP", "port": 80, "nodePort": 32080}]}},
              {"metadata": {"namespace": "shop", "name": "internal"},
               "spec": {"type": "ClusterIP", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "ports": [{"protocol": "TCP", "port": 80}]}},
              {"metadata": {"namespace": "shop", "name": "dns"},
               "spec": {"type": "LoadBalancer", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "ports": [{"protocol": "UDP", "port": 53, "nodePort": 31054}]}},
              {"metadata": {"namespace": "shop", "name": "unallocated"},
               "spec": {"type": "LoadBalancer", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "allocateLoadBalancerNodePorts": false, "ports": [{"protocol": "TCP", "port": 80}]}}
            ]}""";

    private final EksService eksService = mock(EksService.class);
    private final EksClusterManager clusterManager = mock(EksClusterManager.class);
    private final ElbV2Service elb = mock(ElbV2Service.class);
    private final EksAutoModeLoadBalancers loadBalancers = new EksAutoModeLoadBalancers(eksService, clusterManager, elb,
            mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
    /** What the mocked ELBv2 holds, by ARN. */
    private final List<LoadBalancer> lbs = new ArrayList<>();
    private final List<TargetGroup> groups = new ArrayList<>();
    private final Map<String, Map<String, String>> tags = new HashMap<>();

    @Test
    void onlyLoadBalancerServicesOfTheAutoModeClassWithATcpNodePortGetOne() throws Exception {
        List<EksAutoModeLoadBalancers.NlbService> services =
                EksAutoModeLoadBalancers.nlbServices(new ObjectMapper().readTree(SERVICES), false);

        // UDP gets no listener, as Floci's load balancers serve TCP and HTTP only; a Service with no TCP NodePort
        // (only UDP, or allocateLoadBalancerNodePorts false) gets no load balancer.
        assertEquals(List.of(new EksAutoModeLoadBalancers.NlbService("shop", "web", "uid-web", "internet-facing",
                List.of(new EksAutoModeLoadBalancers.ServicePort("TCP", 80, 31080)))), services);
    }

    @ParameterizedTest
    @CsvSource({"true,shop/web shop/plain", "false,shop/web"})
    void aServiceOfNoClassGetsOneOnlyWhileTheNodeRunsWithoutServiceLb(boolean classless, String expected)
            throws Exception {
        String classes = """
                {"items": [
                  {"metadata": {"namespace": "shop", "name": "web"},
                   "spec": {"type": "LoadBalancer", "loadBalancerClass": "eks.amazonaws.com/nlb",
                            "ports": [{"protocol": "TCP", "port": 80, "nodePort": 31080}]}},
                  {"metadata": {"namespace": "shop", "name": "plain"},
                   "spec": {"type": "LoadBalancer", "ports": [{"protocol": "TCP", "port": 80, "nodePort": 32080}]}},
                  {"metadata": {"namespace": "shop", "name": "controller"},
                   "spec": {"type": "LoadBalancer", "loadBalancerClass": "service.k8s.aws/nlb",
                            "ports": [{"protocol": "TCP", "port": 80, "nodePort": 33080}]}}
                ]}""";

        List<EksAutoModeLoadBalancers.NlbService> services =
                EksAutoModeLoadBalancers.nlbServices(new ObjectMapper().readTree(classes), classless);

        assertEquals(List.of(expected.split(" ")),
                services.stream().map(EksAutoModeLoadBalancers.NlbService::stack).toList());
    }

    @Test
    void aServiceWithoutASchemeAnnotationGetsAnInternalLoadBalancer() throws Exception {
        List<EksAutoModeLoadBalancers.NlbService> services = EksAutoModeLoadBalancers.nlbServices(new ObjectMapper()
                .readTree(SERVICES.replace(EksAutoModeLoadBalancers.SCHEME_ANNOTATION, "example.com/other")), false);

        assertEquals("internal", services.getFirst().scheme());
    }

    @Test
    void namesFitElbV2KeepTheirPortAndDifferBetweenClustersAndPorts() {
        String stack = "a-very-long-namespace/a-very-long-service-name";

        String name = EksAutoModeElb.resourceName(cluster("prod"), stack, "");
        String port = EksAutoModeElb.resourceName(cluster("prod"), stack, "-65535");
        String other = EksAutoModeElb.resourceName(cluster("dev"), stack, "");

        for (String each : List.of(name, port, other)) {
            assertTrue(each.length() <= 32 && each.matches("[A-Za-z0-9-]+") && !each.endsWith("-"), each);
        }
        assertTrue(port.matches(".*-65535-[0-9a-f]{10}"), port);
        assertNotEquals(name, port);
        assertNotEquals(name, other);
    }

    @Test
    void aNewServiceGetsALoadBalancerATcpTargetGroupWithItsNodeAListenerAndItsDnsName() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        nodeIs(cluster, "i-0node");
        holdElb();

        loadBalancers.reconcile(cluster, REGION);

        Map<String, String> serviceTags = Map.of(EksAutoModeElb.CLUSTER_TAG, "prod",
                EksAutoModeLoadBalancers.STACK_TAG, "shop/web");
        verify(elb).createLoadBalancer(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, "shop/web", "")),
                eq("internet-facing"), eq("network"), eq("ipv4"), eq(List.of("subnet-a", "subnet-b")), eq(List.of()),
                eq(serviceTags));
        // A TCP health check: ELBv2's default would probe the NodePort with HTTP.
        verify(elb).createTargetGroup(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, "shop/web", "-80")),
                eq("TCP"), isNull(), eq(31080), eq("vpc-1"), eq("instance"), eq("TCP"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("ipv4"), eq(serviceTags));
        verify(elb).registerTargets(eq(REGION), eq(groups.getFirst().getTargetGroupArn()),
                argThat(targets -> targets.size() == 1 && "i-0node".equals(targets.getFirst().getId())));
        ArgumentCaptor<List<Action>> forward = ArgumentCaptor.forClass(List.class);
        verify(elb).createListener(eq(REGION), eq("arn:lb-1"), eq("TCP"), eq(80), isNull(), isNull(),
                forward.capture(), isNull(), eq(serviceTags));
        assertEquals(groups.getFirst().getTargetGroupArn(), forward.getValue().getFirst().getTargetGroupArn());
        verify(elb, never()).createListener(any(), any(), eq("UDP"), anyInt(), any(), any(), any(), any(), any());
        verifyStatus(cluster, "{\"metadata\":{\"uid\":\"uid-web\"},"
                + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"lb-1.elb.test\"}]}}}");
    }

    @Test
    void aPassThatStoppedAfterTheLoadBalancerIsFinishedByTheNext() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        nodeIs(cluster, "i-0node");
        holdElb();
        LoadBalancer lb = ownLoadBalancer(cluster, "shop/web");

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).createLoadBalancer(any(), any(), any(), any(), any(), any(), any(), any());
        verify(elb).createTargetGroup(eq(REGION), anyString(), eq("TCP"), isNull(), eq(31080), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(elb).createListener(eq(REGION), eq(lb.getLoadBalancerArn()), eq("TCP"), eq(80), any(), any(), anyList(),
                any(), any());
        verifyStatus(cluster, "{\"metadata\":{\"uid\":\"uid-web\"},"
                + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"" + lb.getDnsName() + "\"}]}}}");
    }

    @Test
    void aServiceGetsNoAddressUntilTheNodeIsRegisteredAndTheNodeIsRegisteredOnceItIs() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        holdElb();

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).registerTargets(any(), any(), any());
        verify(clusterManager, never()).execInContainerForResult(any(), argThat(cmd -> "patch".equals(cmd[1])), anyInt());

        nodeIs(cluster, "i-0node");
        loadBalancers.reconcile(cluster, REGION);

        verify(elb).registerTargets(eq(REGION), eq(groups.getFirst().getTargetGroupArn()),
                argThat(targets -> "i-0node".equals(targets.getFirst().getId())));
        verify(elb).createLoadBalancer(any(), any(), any(), any(), any(), any(), any(), any());
        verifyStatus(cluster, "{\"metadata\":{\"uid\":\"uid-web\"},"
                + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"lb-1.elb.test\"}]}}}");
    }

    @Test
    void aGoneServicesLoadBalancerGoesWithItsListenersAndEveryTargetGroupMadeForItAttachedOrNot() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, "{\"items\": []}");
        holdElb();
        LoadBalancer lb = ownLoadBalancer(cluster, "shop/gone");
        Listener listener = new Listener();
        listener.setListenerArn("arn:listener");
        when(elb.describeListeners(REGION, lb.getLoadBalancerArn(), null)).thenReturn(List.of(listener));
        // A target group whose listener was never made has no load balancer to be found through.
        TargetGroup orphan = ownTargetGroup(cluster, "shop/gone", 8080);

        loadBalancers.reconcile(cluster, REGION);

        verify(elb).deleteListener(REGION, "arn:listener");
        verify(elb).deleteLoadBalancer(REGION, lb.getLoadBalancerArn());
        verify(elb).deleteTargetGroup(REGION, orphan.getTargetGroupArn());
    }

    @Test
    void aLoadBalancerWithTheTagsButNotTheNameThisGivesIsLeftAlone() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, "{\"items\": []}");
        holdElb();
        LoadBalancer someoneElses = loadBalancer("arn:theirs", "theirs", "network", "prod", "shop/web");
        TargetGroup theirGroup = targetGroup("arn:their-tg", "k8s-shop-web-80-0123456789", 31080, "prod", "shop/web");

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).deleteLoadBalancer(REGION, someoneElses.getLoadBalancerArn());
        verify(elb, never()).deleteTargetGroup(REGION, theirGroup.getTargetGroupArn());
    }

    @Test
    void anotherClustersLoadBalancerIsLeftAlone() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, "{\"items\": []}");
        holdElb();
        ownLoadBalancer(cluster("dev"), "shop/web");

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).deleteLoadBalancer(any(), any());
    }

    @Test
    void aClusterWhoseLoadBalancingIsOffLosesItsLoadBalancers() {
        Cluster cluster = cluster("prod");
        cluster.getKubernetesNetworkConfig().setElasticLoadBalancing(new ElasticLoadBalancing(false));
        when(eksService.runningClusters()).thenReturn(List.of(new AccountAwareStorageBackend.AccountEntry<>(
                "000000000000", "prod", cluster)));
        when(eksService.clusterRegion(cluster)).thenReturn(REGION);
        holdElb();
        LoadBalancer lb = ownLoadBalancer(cluster, "shop/web");

        loadBalancers.reconcileAll();

        verify(elb).deleteLoadBalancer(REGION, lb.getLoadBalancerArn());
        verify(clusterManager, never()).execInContainerForResult(any(), any(), anyInt());
    }

    @Test
    void aTargetGroupWhoseNodePortChangedIsMadeAgainWithItsListener() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        nodeIs(cluster, "i-0node");
        holdElb();
        LoadBalancer lb = ownLoadBalancer(cluster, "shop/web");
        TargetGroup stale = ownTargetGroup(cluster, "shop/web", 80);
        stale.setPort(30999);
        Listener listener = new Listener();
        listener.setListenerArn("arn:listener-80");
        listener.setPort(80);
        when(elb.describeListeners(REGION, lb.getLoadBalancerArn(), null)).thenReturn(List.of(listener));

        loadBalancers.reconcile(cluster, REGION);

        verify(elb).deleteListener(REGION, "arn:listener-80");
        verify(elb).deleteTargetGroup(REGION, stale.getTargetGroupArn());
        verify(elb).createTargetGroup(eq(REGION), anyString(), eq("TCP"), isNull(), eq(31080), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(elb).createListener(eq(REGION), eq(lb.getLoadBalancerArn()), eq("TCP"), eq(80), any(), any(), anyList(),
                any(), any());
    }

    /** The mocked ELBv2 lists and tags what the test and the reconciler put in it. */
    private void holdElb() {
        when(elb.describeLoadBalancers(REGION, null, null, null, null)).thenAnswer(inv -> List.copyOf(lbs));
        when(elb.describeTargetGroups(REGION, null, null, null)).thenAnswer(inv -> List.copyOf(groups));
        when(elb.describeTags(anyList())).thenAnswer(inv -> {
            Map<String, Map<String, String>> found = new HashMap<>();
            for (String arn : inv.<List<String>>getArgument(0)) {
                found.put(arn, tags.getOrDefault(arn, Map.of()));
            }
            return found;
        });
        when(elb.createLoadBalancer(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(),
                anyList(), any())).thenAnswer(inv -> {
                    Map<String, String> made = inv.getArgument(7);
                    return loadBalancer("arn:lb-" + (lbs.size() + 1), inv.getArgument(1), "network",
                            made.get(EksAutoModeElb.CLUSTER_TAG), made.get(EksAutoModeLoadBalancers.STACK_TAG));
                });
        when(elb.createTargetGroup(anyString(), anyString(), anyString(), any(), anyInt(), any(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                    Map<String, String> made = inv.getArgument(17);
                    return targetGroup("arn:tg-" + (groups.size() + 1), inv.getArgument(1), inv.getArgument(4),
                            made.get(EksAutoModeElb.CLUSTER_TAG), made.get(EksAutoModeLoadBalancers.STACK_TAG));
                });
    }

    private LoadBalancer ownLoadBalancer(Cluster cluster, String stack) {
        return loadBalancer("arn:own-" + stack, EksAutoModeElb.resourceName(cluster, stack, ""), "network",
                cluster.getName(), stack);
    }

    private TargetGroup ownTargetGroup(Cluster cluster, String stack, int servicePort) {
        return targetGroup("arn:own-tg-" + stack + "-" + servicePort,
                EksAutoModeElb.resourceName(cluster, stack, "-" + servicePort), 31080, cluster.getName(), stack);
    }

    private LoadBalancer loadBalancer(String arn, String name, String type, String clusterName, String stack) {
        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setLoadBalancerName(name);
        lb.setType(type);
        lb.setDnsName(arn.substring(arn.indexOf(':') + 1) + ".elb.test");
        lbs.add(lb);
        if (clusterName != null) {
            tags.put(arn, Map.of(EksAutoModeElb.CLUSTER_TAG, clusterName, EksAutoModeLoadBalancers.STACK_TAG,
                    stack));
        }
        return lb;
    }

    private TargetGroup targetGroup(String arn, String name, int port, String clusterName, String stack) {
        TargetGroup group = new TargetGroup();
        group.setTargetGroupArn(arn);
        group.setTargetGroupName(name);
        group.setPort(port);
        group.setTargets(new ArrayList<TargetDescription>());
        groups.add(group);
        tags.put(arn, Map.of(EksAutoModeElb.CLUSTER_TAG, clusterName, EksAutoModeLoadBalancers.STACK_TAG,
                stack));
        return group;
    }

    private void nodeIs(Cluster cluster, String instanceId) {
        Instance node = new Instance();
        node.setInstanceId(instanceId);
        when(clusterManager.getRegisteredClusterNodeInstance(cluster)).thenReturn(node);
    }

    private void verifyStatus(Cluster cluster, String patch) {
        verify(clusterManager).execInContainerForResult(eq(cluster.getContainerId()), argThat(cmd -> List.of(cmd).equals(
                List.of("kubectl", "patch", "service", "web", "-n", "shop", "--subresource=status", "--type=merge", "-p",
                        patch))), anyInt());
    }

    private void servicesAre(Cluster cluster, String json) {
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "get".equals(cmd[1])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, json, "", false));
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "patch".equals(cmd[1])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, "patched", "", false));
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        cluster.setContainerId("k3s-" + name);
        cluster.setStatus(ClusterStatus.ACTIVE);
        ResourcesVpcConfig vpc = new ResourcesVpcConfig();
        vpc.setSubnetIds(List.of("subnet-a", "subnet-b"));
        vpc.setVpcId("vpc-1");
        cluster.setResourcesVpcConfig(vpc);
        KubernetesNetworkConfig network = new KubernetesNetworkConfig();
        network.setElasticLoadBalancing(new ElasticLoadBalancing(true));
        cluster.setKubernetesNetworkConfig(network);
        return cluster;
    }
}
