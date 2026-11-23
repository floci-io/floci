package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
              {"metadata": {"namespace": "shop", "name": "web",
                            "annotations": {"service.beta.kubernetes.io/aws-load-balancer-scheme": "internet-facing"}},
               "spec": {"type": "LoadBalancer", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "ports": [{"protocol": "TCP", "port": 80, "nodePort": 31080},
                                  {"protocol": "UDP", "port": 53, "nodePort": 31053}]}},
              {"metadata": {"namespace": "shop", "name": "klipper"},
               "spec": {"type": "LoadBalancer", "ports": [{"protocol": "TCP", "port": 80, "nodePort": 32080}]}},
              {"metadata": {"namespace": "shop", "name": "internal"},
               "spec": {"type": "ClusterIP", "loadBalancerClass": "eks.amazonaws.com/nlb",
                        "ports": [{"protocol": "TCP", "port": 80}]}}
            ]}""";

    private final EksService eksService = mock(EksService.class);
    private final EksClusterManager clusterManager = mock(EksClusterManager.class);
    private final ElbV2Service elb = mock(ElbV2Service.class);
    private final EksAutoModeLoadBalancers loadBalancers = new EksAutoModeLoadBalancers(eksService, clusterManager, elb,
            mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));

    @Test
    void onlyLoadBalancerServicesOfTheAutoModeClassGetOneWithTheirNodePortsAndScheme() throws Exception {
        List<EksAutoModeLoadBalancers.NlbService> services =
                EksAutoModeLoadBalancers.nlbServices(new ObjectMapper().readTree(SERVICES));

        assertEquals(List.of(new EksAutoModeLoadBalancers.NlbService("shop", "web", "internet-facing", List.of(
                new EksAutoModeLoadBalancers.ServicePort("TCP", 80, 31080),
                new EksAutoModeLoadBalancers.ServicePort("UDP", 53, 31053)))), services);
    }

    @Test
    void aServiceWithoutASchemeAnnotationGetsAnInternalLoadBalancer() throws Exception {
        List<EksAutoModeLoadBalancers.NlbService> services = EksAutoModeLoadBalancers.nlbServices(new ObjectMapper()
                .readTree(SERVICES.replace(EksAutoModeLoadBalancers.SCHEME_ANNOTATION, "example.com/other")));

        assertEquals("internal", services.getFirst().scheme());
    }

    @Test
    void namesFitElbV2AndDifferBetweenClustersAndPorts() {
        EksAutoModeLoadBalancers.NlbService service = new EksAutoModeLoadBalancers.NlbService(
                "a-very-long-namespace", "a-very-long-service-name", "internal", List.of());

        String name = EksAutoModeLoadBalancers.resourceName(cluster("prod"), service, "");
        String port = EksAutoModeLoadBalancers.resourceName(cluster("prod"), service, "-8080");
        String other = EksAutoModeLoadBalancers.resourceName(cluster("dev"), service, "");

        for (String each : List.of(name, port, other)) {
            assertTrue(each.length() <= 32 && each.matches("[A-Za-z0-9-]+") && !each.endsWith("-"), each);
        }
        assertNotEquals(name, port);
        assertNotEquals(name, other);
    }

    @Test
    void aNewServiceGetsALoadBalancerATargetGroupAndListenerPerPortAndItsDnsName() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        Instance node = new Instance();
        node.setInstanceId("i-0node");
        when(clusterManager.getRegisteredClusterNodeInstance(cluster)).thenReturn(node);
        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn("arn:lb");
        lb.setDnsName("web.elb.test");
        when(elb.createLoadBalancer(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList(),
                any())).thenReturn(lb);
        when(elb.createTargetGroup(anyString(), anyString(), anyString(), isNull(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), anyString(), any()))
                .thenAnswer(inv -> group("arn:tg-" + inv.getArgument(4)));

        loadBalancers.reconcile(cluster, REGION);

        Map<String, String> tags = Map.of(EksAutoModeLoadBalancers.CLUSTER_TAG, "prod",
                EksAutoModeLoadBalancers.STACK_TAG, "shop/web");
        verify(elb).createLoadBalancer(eq(REGION), anyString(), eq("internet-facing"), eq("network"), eq("ipv4"),
                eq(List.of("subnet-a", "subnet-b")), eq(List.of()), eq(tags));
        verify(elb).createTargetGroup(eq(REGION), anyString(), eq("TCP"), isNull(), eq(31080), eq("vpc-1"),
                eq("instance"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ipv4"), eq(tags));
        verify(elb).createTargetGroup(eq(REGION), anyString(), eq("UDP"), isNull(), eq(31053), eq("vpc-1"),
                eq("instance"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ipv4"), eq(tags));
        verify(elb).registerTargets(eq(REGION), eq("arn:tg-31080"), argThat(targets -> targets.size() == 1
                && "i-0node".equals(targets.getFirst().getId())));
        ArgumentCaptor<List<Action>> forward = ArgumentCaptor.forClass(List.class);
        verify(elb).createListener(eq(REGION), eq("arn:lb"), eq("TCP"), eq(80), isNull(), isNull(), forward.capture(),
                isNull(), eq(tags));
        assertEquals("arn:tg-31080", forward.getValue().getFirst().getTargetGroupArn());
        verify(elb).createListener(eq(REGION), eq("arn:lb"), eq("UDP"), eq(53), isNull(), isNull(), anyList(),
                isNull(), eq(tags));
        verify(clusterManager).execInContainerForResult(eq("k3s-prod"), argThat(cmd -> List.of(cmd).equals(List.of(
                "kubectl", "patch", "service", "web", "-n", "shop", "--subresource=status", "--type=merge", "-p",
                "{\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"web.elb.test\"}]}}}"))), anyInt());
    }

    @Test
    void aServiceThatAlreadyHasItsLoadBalancerGetsNoSecond() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, SERVICES);
        LoadBalancer lb = existing("arn:lb", "web.elb.test", "prod", "shop/web");

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).createLoadBalancer(any(), any(), any(), any(), any(), any(), any(), any());
        verify(clusterManager).execInContainerForResult(eq("k3s-prod"), argThat(cmd -> List.of(cmd).contains(
                "{\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"" + lb.getDnsName() + "\"}]}}}")), anyInt());
    }

    @Test
    void theLoadBalancerOfAGoneServiceIsDeletedWithItsListenersAndTargetGroups() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, "{\"items\": []}");
        existing("arn:gone", "gone.elb.test", "prod", "shop/gone");
        Listener listener = new Listener();
        listener.setListenerArn("arn:listener");
        when(elb.describeListeners(REGION, "arn:gone", null)).thenReturn(List.of(listener));
        when(elb.describeTargetGroups(REGION, "arn:gone", null, null)).thenReturn(List.of(group("arn:tg-gone")));

        loadBalancers.reconcile(cluster, REGION);

        verify(elb).deleteListener(REGION, "arn:listener");
        verify(elb).deleteLoadBalancer(REGION, "arn:gone");
        verify(elb).deleteTargetGroup(REGION, "arn:tg-gone");
    }

    @Test
    void anotherClustersLoadBalancerIsLeftAlone() {
        Cluster cluster = cluster("prod");
        servicesAre(cluster, "{\"items\": []}");
        existing("arn:dev", "dev.elb.test", "dev", "shop/web");

        loadBalancers.reconcile(cluster, REGION);

        verify(elb, never()).deleteLoadBalancer(any(), any());
    }

    private LoadBalancer existing(String arn, String dnsName, String clusterName, String stack) {
        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setDnsName(dnsName);
        when(elb.describeLoadBalancers(REGION, null, null, null, null)).thenReturn(List.of(lb));
        when(elb.describeTags(List.of(arn))).thenReturn(Map.of(arn, Map.of(
                EksAutoModeLoadBalancers.CLUSTER_TAG, clusterName, EksAutoModeLoadBalancers.STACK_TAG, stack)));
        return lb;
    }

    private void servicesAre(Cluster cluster, String json) {
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "get".equals(cmd[1])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, json, "", false));
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "patch".equals(cmd[1])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, "patched", "", false));
    }

    private static TargetGroup group(String arn) {
        TargetGroup group = new TargetGroup();
        group.setTargetGroupArn(arn);
        return group;
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        cluster.setContainerId("k3s-" + name);
        ResourcesVpcConfig vpc = new ResourcesVpcConfig();
        vpc.setSubnetIds(List.of("subnet-a", "subnet-b"));
        vpc.setVpcId("vpc-1");
        cluster.setResourcesVpcConfig(vpc);
        return cluster;
    }
}
