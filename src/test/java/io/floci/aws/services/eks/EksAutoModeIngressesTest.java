package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.AvailabilityZone;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.services.elbv2.model.RuleCondition;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksAutoModeIngressesTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLASSES = """
            {"items": [
              {"metadata": {"name": "alb"}, "spec": {"controller": "eks.amazonaws.com/alb",
                 "parameters": {"apiGroup": "eks.amazonaws.com", "kind": "IngressClassParams", "name": "public"}}},
              {"metadata": {"name": "alb-default", "annotations": {"ingressclass.kubernetes.io/is-default-class": "true"}},
               "spec": {"controller": "eks.amazonaws.com/alb"}},
              {"metadata": {"name": "nginx"}, "spec": {"controller": "k8s.io/ingress-nginx"}}
            ]}""";
    private static final String PARAMS = """
            {"items": [{"metadata": {"name": "public"},
                        "spec": {"scheme": "internet-facing", "subnets": {"ids": ["subnet-pub1", "subnet-pub2"]}}}]}""";
    private static final String SERVICES = """
            {"items": [
              {"metadata": {"namespace": "shop", "name": "web"},
               "spec": {"type": "NodePort", "ports": [{"name": "http", "port": 80, "nodePort": 30080}]}},
              {"metadata": {"namespace": "shop", "name": "api"},
               "spec": {"type": "NodePort", "ports": [{"port": 8080, "nodePort": 30081}]}},
              {"metadata": {"namespace": "shop", "name": "internal"},
               "spec": {"type": "ClusterIP", "ports": [{"port": 80}]}}
            ]}""";
    private static final String INGRESSES = """
            {"items": [
              {"metadata": {"namespace": "shop", "name": "front", "uid": "uid-front",
                            "annotations": {"alb.ingress.kubernetes.io/healthcheck-path": "/healthz"}},
               "spec": {"ingressClassName": "alb", "rules": [
                 {"host": "shop.example.com", "http": {"paths": [
                   {"path": "/api", "pathType": "Prefix", "backend": {"service": {"name": "api", "port": {"number": 8080}}}},
                   {"path": "/", "pathType": "Prefix", "backend": {"service": {"name": "web", "port": {"name": "http"}}}},
                   {"path": "/hidden", "pathType": "Exact",
                    "backend": {"service": {"name": "internal", "port": {"number": 80}}}}]}}]}},
              {"metadata": {"namespace": "shop", "name": "plain"},
               "spec": {"defaultBackend": {"service": {"name": "web", "port": {"number": 80}}}}},
              {"metadata": {"namespace": "shop", "name": "pods",
                            "annotations": {"alb.ingress.kubernetes.io/target-type": "ip"}},
               "spec": {"ingressClassName": "alb-default", "defaultBackend":
                 {"service": {"name": "web", "port": {"number": 80}}}}},
              {"metadata": {"namespace": "shop", "name": "other"},
               "spec": {"ingressClassName": "nginx", "defaultBackend":
                 {"service": {"name": "web", "port": {"number": 80}}}}}
            ]}""";

    private final EksClusterManager clusterManager = mock(EksClusterManager.class);
    private final ElbV2Service elb = mock(ElbV2Service.class);
    private final EksAutoModeIngresses ingresses = new EksAutoModeIngresses(elb, new EksAutoModeElb(elb),
            new EksAutoModeKubectl(clusterManager));
    private final List<LoadBalancer> lbs = new ArrayList<>();
    private final List<TargetGroup> groups = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Map<String, String>> tags = new HashMap<>();

    @Test
    void ingressesOfAnAlbClassGetOneAsTheirClassParamsAndAnnotationsSay() throws Exception {
        List<EksAutoModeIngresses.AlbIngress> found = EksAutoModeIngresses.albIngresses(json(INGRESSES),
                json(CLASSES), json(PARAMS), json(SERVICES));

        EksAutoModeIngresses.Backend api = new EksAutoModeIngresses.Backend("api", 8080, 30081);
        EksAutoModeIngresses.Backend web = new EksAutoModeIngresses.Backend("web", 80, 30080);
        // The class's IngressClassParams give the scheme and subnets; the ClusterIP backend has no NodePort to
        // serve; the class-less Ingress takes the default ALB class; target type ip and another controller get none.
        assertEquals(List.of(
                new EksAutoModeIngresses.AlbIngress("shop", "front", "uid-front", "internet-facing",
                        List.of("subnet-pub1", "subnet-pub2"), "/healthz", List.of(
                        new EksAutoModeIngresses.Route("shop.example.com", List.of("/api", "/api/*"), api),
                        new EksAutoModeIngresses.Route("shop.example.com", List.of("/*"), web)), null),
                new EksAutoModeIngresses.AlbIngress("shop", "plain", "", "internal", List.of(), "/", List.of(), web)),
                found);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"/|Prefix|/*", "/api|Prefix|/api,/api/*", "/api/|Prefix|/api,/api/*",
        "/exact|Exact|/exact", "/x|ImplementationSpecific|/x"})
    void pathsBecomeAlbPathPatterns(String path, String type, String patterns) throws Exception {
        assertEquals(List.of(patterns.split(",")), EksAutoModeIngresses.pathPatterns(
                json("{\"path\": \"" + path + "\", \"pathType\": \"" + type + "\"}")));
    }

    @Test
    void anIngressGetsAnAlbWithAListenerRulesTargetGroupsOnNodePortsAndItsDnsName() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();

        ingresses.reconcile(cluster, REGION, node());

        Map<String, String> lbTags = Map.of(EksAutoModeElb.CLUSTER_TAG, "prod",
                EksAutoModeIngresses.STACK_TAG, "shop/front", EksAutoModeIngresses.RESOURCE_TAG, "LoadBalancer");
        verify(elb).createLoadBalancer(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND, "shop/front", "")),
                eq("internet-facing"), eq("application"), eq("ipv4"), eq(List.of("subnet-pub1", "subnet-pub2")),
                eq(List.of()), eq(lbTags));
        verify(elb).createTargetGroup(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND, "shop/front", "-30081")),
                eq("HTTP"), isNull(), eq(30081), eq("vpc-1"), eq("instance"), eq("HTTP"), eq("traffic-port"), eq(true),
                eq("/healthz"), eq(15), eq(5), isNull(), isNull(), isNull(), eq("ipv4"),
                eq(Map.of(EksAutoModeElb.CLUSTER_TAG, "prod", EksAutoModeIngresses.STACK_TAG, "shop/front",
                        EksAutoModeIngresses.RESOURCE_TAG, "shop/front-api:8080")));
        // front forwards to api and web, plain to web: three target groups, each with the node.
        verify(elb, times(3)).registerTargets(eq(REGION), anyString(),
                argThat(targets -> "i-0node".equals(targets.getFirst().getId())));
        ArgumentCaptor<List<Action>> defaults = ArgumentCaptor.forClass(List.class);
        verify(elb, times(2)).createListener(eq(REGION), anyString(), eq("HTTP"), eq(80), isNull(), isNull(),
                defaults.capture(), isNull(), any());
        Action notFound = defaults.getAllValues().getFirst().getFirst();
        assertEquals(List.of("fixed-response", "404", "text/plain"), List.of(notFound.getType(),
                notFound.getFixedResponseStatusCode(), notFound.getFixedResponseContentType()));
        ArgumentCaptor<List<RuleCondition>> conditions = ArgumentCaptor.forClass(List.class);
        verify(elb).createRule(eq(REGION), anyString(), conditions.capture(), eq(1), anyList(), any());
        assertEquals(List.of("host-header", "path-pattern"),
                conditions.getValue().stream().map(RuleCondition::getField).toList());
        assertEquals(List.of("/api", "/api/*"), conditions.getValue().get(1).getValues());
        verify(elb).createRule(eq(REGION), anyString(), anyList(), eq(2), anyList(), any());
        verify(clusterManager).execInContainerForResult(eq("k3s-prod"), argThat(cmd -> List.of(cmd).equals(List.of(
                "kubectl", "patch", "ingress", "front", "-n", "shop", "--subresource=status", "--type=merge", "-p",
                "{\"metadata\":{\"uid\":\"uid-front\"},\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\""
                        + "lb-1.elb.test\"}]}}}"))), anyInt());
    }

    @Test
    void aPassThatFindsTheRulesAsTheIngressWantsLeavesThem() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();
        ingresses.reconcile(cluster, REGION, node());
        verify(elb, times(2)).createRule(any(), any(), any(), anyInt(), any(), any());

        ingresses.reconcile(cluster, REGION, node());

        verify(elb, times(2)).createRule(any(), any(), any(), anyInt(), any(), any());
        verify(elb, never()).deleteRule(any(), any());
    }

    @Test
    void aGoneIngressLosesItsLoadBalancerAndWithoutAnAlbClassNothingElseIsRead() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();
        ingresses.reconcile(cluster, REGION, node());
        String frontLb = lbs.getFirst().getLoadBalancerArn();
        objects(cluster, "{\"items\": []}");
        when(clusterManager.execInContainerForResult(eq("k3s-prod"),
                argThat(cmd -> cmd.length > 2 && "ingressclasses".equals(cmd[2])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, "{\"items\": []}", "", false));

        ingresses.reconcile(cluster, REGION, node());

        verify(elb).deleteLoadBalancer(REGION, frontLb);
        verify(clusterManager, times(1)).execInContainerForResult(eq("k3s-prod"),
                argThat(cmd -> cmd.length > 2 && "ingresses".equals(cmd[2])), anyInt());
    }

    @Test
    void anIngressElbV2RefusesLeavesTheOthersServed() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();
        when(elb.createLoadBalancer(anyString(), eq(EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND, "shop/front", "")), anyString(),
                anyString(), anyString(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("ValidationError", "At least two subnets in two different Availability "
                        + "Zones must be specified", 400));

        ingresses.reconcile(cluster, REGION, node());

        verify(elb).createLoadBalancer(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND, "shop/plain", "")),
                anyString(), eq("application"), anyString(), anyList(), anyList(), any());
    }

    @Test
    void aServiceAndAnIngressOfOneNameGetTwoNames() {
        Cluster cluster = cluster();
        assertNotEquals(EksAutoModeElb.resourceName(cluster, "shop/web", ""),
                EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND, "shop/web", ""));
    }

    /** As the Load Balancer Controller orders paths: Exact, then Prefix longest first, then ImplementationSpecific. */
    @Test
    void rulesFollowTheLoadBalancerControllersPathOrder() throws Exception {
        List<JsonNode> paths = List.of(json("{\"path\": \"/long/implementation\", \"pathType\": \"ImplementationSpecific\"}"),
                json("{\"path\": \"/\", \"pathType\": \"Prefix\"}"),
                json("{\"path\": \"/api\", \"pathType\": \"Prefix\"}"),
                json("{\"path\": \"/x\", \"pathType\": \"ImplementationSpecific\"}"),
                json("{\"path\": \"/api/health\", \"pathType\": \"Exact\"}"),
                json("{\"path\": \"/static\", \"pathType\": \"Prefix\"}"));

        assertEquals(List.of(4, 5, 2, 1, 0, 3), EksAutoModeIngresses.precedence(paths));
    }

    @Test
    void aBackendPortServedOverUdpAndTcpTakesTheTcpNodePort() throws Exception {
        String services = """
                {"items": [{"metadata": {"namespace": "shop", "name": "dns"},
                  "spec": {"type": "NodePort", "ports": [{"protocol": "UDP", "port": 53, "nodePort": 30053},
                                                         {"protocol": "TCP", "port": 53, "nodePort": 31053}]}}]}""";
        String ingress = """
                {"items": [{"metadata": {"namespace": "shop", "name": "dns"},
                  "spec": {"ingressClassName": "alb", "defaultBackend": {"service": {"name": "dns", "port": {"number": 53}}}}}]}""";

        List<EksAutoModeIngresses.AlbIngress> found = EksAutoModeIngresses.albIngresses(json(ingress), json(CLASSES),
                json(PARAMS), json(services));

        assertEquals(31053, found.getFirst().defaultBackend().nodePort());
    }

    @Test
    void changedSettingsReachTheLoadBalancerItsListenerAndTargetGroups() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();
        ingresses.reconcile(cluster, REGION, node());
        LoadBalancer plain = lbs.get(1);

        // plain loses its default backend, front's health check path changes, and front turns internal.
        objects(cluster, INGRESSES.replace(
                "\"spec\": {\"defaultBackend\": {\"service\": {\"name\": \"web\", \"port\": {\"number\": 80}}}}}",
                "\"spec\": {\"rules\": []}}")
                .replace("/healthz", "/ready"));
        when(clusterManager.execInContainerForResult(eq("k3s-prod"),
                argThat(cmd -> cmd.length > 2 && "ingressclassparams".equals(cmd[2])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, PARAMS.replace("internet-facing", "internal"), "", false));
        String frontLb = lbs.getFirst().getLoadBalancerArn();

        ingresses.reconcile(cluster, REGION, node());

        verify(elb).modifyListener(eq(REGION), eq("arn:listener-" + plain.getLoadBalancerArn()), isNull(), isNull(),
                isNull(), isNull(), argThat(actions -> "fixed-response".equals(actions.getFirst().getType())), isNull());
        // front's two target groups, api and web, both take the new health check path.
        verify(elb, times(2)).modifyTargetGroup(eq(REGION), anyString(), isNull(), isNull(), isNull(), eq("/ready"), isNull(),
                isNull(), isNull(), isNull(), isNull());
        verify(elb).deleteLoadBalancer(REGION, frontLb);
        verify(elb).createLoadBalancer(eq(REGION), eq(EksAutoModeElb.resourceName(cluster, EksAutoModeIngresses.KIND,
                "shop/front", "")), eq("internal"), eq("application"), anyString(), anyList(), anyList(), any());
    }

    @Test
    void aFailedParamsReadOtherThanAMissingKindStopsThePass() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();
        when(clusterManager.execInContainerForResult(eq("k3s-prod"),
                argThat(cmd -> cmd.length > 2 && "ingressclassparams".equals(cmd[2])), anyInt()))
                .thenReturn(new ContainerExec.Result(1, "", "Unable to connect to the server", false));

        assertThrows(IllegalStateException.class, () -> ingresses.reconcile(cluster, REGION, node()));
        verify(elb, never()).createLoadBalancer(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void noAddressIsWrittenUntilTheNodeIsRegistered() throws Exception {
        Cluster cluster = cluster();
        objects(cluster, INGRESSES);
        holdElb();

        ingresses.reconcile(cluster, REGION, null);

        verify(elb, never()).registerTargets(any(), any(), any());
        verify(clusterManager, never()).execInContainerForResult(any(), argThat(cmd -> "patch".equals(cmd[1])), anyInt());
    }

    /** The cluster's objects, by the kind {@code kubectl get} asks for. */
    private void objects(Cluster cluster, String ingressList) {
        Map<String, String> lists = Map.of("ingresses", ingressList, "ingressclasses", CLASSES,
                "ingressclassparams", PARAMS, "services", SERVICES);
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "get".equals(cmd[1])), anyInt())).thenAnswer(inv -> {
                    String[] cmd = inv.getArgument(1);
                    return new ContainerExec.Result(0, lists.get(cmd[2]), "", false);
                });
        when(clusterManager.execInContainerForResult(eq(cluster.getContainerId()),
                argThat(cmd -> cmd.length > 2 && "patch".equals(cmd[1])), anyInt()))
                .thenReturn(new ContainerExec.Result(0, "patched", "", false));
    }

    /** The mocked ELBv2 lists and tags what the reconciler puts in it. */
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
                    LoadBalancer lb = new LoadBalancer();
                    lb.setLoadBalancerArn("arn:lb-" + (lbs.size() + 1));
                    lb.setLoadBalancerName(inv.getArgument(1));
                    lb.setType(inv.getArgument(3));
                    lb.setScheme(inv.getArgument(2));
                    List<AvailabilityZone> zones = new ArrayList<>();
                    for (String subnet : inv.<List<String>>getArgument(5)) {
                        AvailabilityZone zone = new AvailabilityZone();
                        zone.setSubnetId(subnet);
                        zones.add(zone);
                    }
                    lb.setAvailabilityZones(zones);
                    lb.setDnsName("lb-" + (lbs.size() + 1) + ".elb.test");
                    lbs.add(lb);
                    tags.put(lb.getLoadBalancerArn(), inv.getArgument(7));
                    return lb;
                });
        when(elb.createTargetGroup(anyString(), anyString(), anyString(), any(), anyInt(), any(), anyString(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                    TargetGroup group = new TargetGroup();
                    group.setTargetGroupArn("arn:tg-" + (groups.size() + 1));
                    group.setTargetGroupName(inv.getArgument(1));
                    group.setPort(inv.getArgument(4));
                    group.setHealthCheckPath(inv.getArgument(10));
                    group.setTargets(new ArrayList<TargetDescription>());
                    groups.add(group);
                    tags.put(group.getTargetGroupArn(), inv.getArgument(17));
                    return group;
                });
        Map<String, List<Listener>> listeners = new HashMap<>();
        when(elb.createListener(anyString(), anyString(), anyString(), anyInt(), any(), any(), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    Listener listener = new Listener();
                    listener.setListenerArn("arn:listener-" + inv.getArgument(1));
                    listener.setPort(inv.getArgument(3));
                    listener.setDefaultActions(inv.getArgument(6));
                    listeners.computeIfAbsent(inv.getArgument(1), k -> new ArrayList<>()).add(listener);
                    return listener;
                });
        when(elb.describeListeners(anyString(), anyString(), isNull()))
                .thenAnswer(inv -> List.copyOf(listeners.getOrDefault(inv.getArgument(1), List.of())));
        when(elb.createRule(anyString(), anyString(), anyList(), anyInt(), anyList(), any())).thenAnswer(inv -> {
            Rule rule = new Rule();
            rule.setRuleArn("arn:rule-" + (rules.size() + 1));
            rule.setListenerArn(inv.getArgument(1));
            rule.setPriority(String.valueOf((int) inv.getArgument(3)));
            rule.setConditions(inv.getArgument(2));
            rule.setActions(inv.getArgument(4));
            rules.add(rule);
            return rule;
        });
        when(elb.describeRules(anyString(), anyString(), isNull())).thenAnswer(inv -> rules.stream()
                .filter(rule -> rule.getListenerArn().equals(inv.getArgument(1))).toList());
    }

    private static Instance node() {
        Instance node = new Instance();
        node.setInstanceId("i-0node");
        return node;
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setName("prod");
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        cluster.setContainerId("k3s-prod");
        ResourcesVpcConfig vpc = new ResourcesVpcConfig();
        vpc.setSubnetIds(List.of("subnet-a", "subnet-b"));
        vpc.setVpcId("vpc-1");
        cluster.setResourcesVpcConfig(vpc);
        return cluster;
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }
}
