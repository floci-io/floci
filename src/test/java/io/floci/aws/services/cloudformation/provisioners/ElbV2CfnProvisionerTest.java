package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.AvailabilityZone;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.services.elbv2.model.RuleCondition;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.Stubber;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ELBv2 CFN provisioner in isolation: one mocked service, no Quarkus boot.
 * {@code CloudFormationIntegrationTest} covers the four types end to end.
 */
class ElbV2CfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String LB_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/web/50dc6c495c0c9188";
    private static final String TG_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web-tg/73e2d6bc24d8a067";
    private static final String LISTENER_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/web/50dc6c495c0c9188/f2f7dc8efc522ab2";
    private static final String RULE_ARN = LISTENER_ARN.replace(":listener/", ":listener-rule/") + "/9683b2d02a6cabee";

    private static final List<String> TWO_SUBNETS = List.of("subnet-1", "subnet-2");
    private static final String DECLARED_TARGETS = "__FlociTargetGroupTargets";
    private static final String DECLARED_TARGETS_PRIOR = "__FlociTargetGroupTargetsPrior";
    private static final String TARGETS_SNAPSHOT = CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR;

    private final ElbV2Service elb = mock(ElbV2Service.class);
    private final ElbV2CfnProvisioner provisioner = new ElbV2CfnProvisioner(elb);
    private final ObjectMapper mapper = new ObjectMapper();
    /** The target group the Targets tests provision: on port 80, nothing registered until a test registers targets. */
    private final TargetGroup group = targetGroupOnPort80();
    /** The stack resource the target group tests provision. */
    private final StackResource tgResource = resource("AWS::ElasticLoadBalancingV2::TargetGroup", "Tg");
    /** The previous resource the engine restores when a target group update fails. */
    private final StackResource restored = new StackResource();

    @BeforeEach
    void defaultListenerLoadBalancer() {
        LoadBalancer lb = new LoadBalancer();
        lb.setType("application");
        when(elb.describeLoadBalancers(eq(REGION), anyList(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(lb));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void gatewayListenerLeavesProtocolAndPortUnset(boolean update) {
        LoadBalancer lb = new LoadBalancer();
        lb.setType("gateway");
        when(elb.describeLoadBalancers(REGION, List.of(LB_ARN), null, null, null)).thenReturn(List.of(lb));
        if (update) {
            when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
            when(elb.modifyListener(eq(REGION), eq(LISTENER_ARN), isNull(), isNull(), isNull(),
                    anyList(), anyList(), isNull())).thenReturn(listener(LB_ARN));
        } else {
            when(elb.createListener(eq(REGION), eq(LB_ARN), isNull(), isNull(), isNull(),
                    anyList(), anyList(), isNull(), eq(Map.of()))).thenReturn(listener(LB_ARN));
        }
        StackResource resource = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");

        provisioner.provision(resource, mapper.createObjectNode().put("LoadBalancerArn", LB_ARN),
                ctx(update ? LISTENER_ARN : null));

        assertEquals(LISTENER_ARN, resource.getPhysicalId());
        assertEquals(LISTENER_ARN, resource.getAttributes().get("ListenerArn"));
        if (update) {
            verify(elb).modifyListener(eq(REGION), eq(LISTENER_ARN), isNull(), isNull(), isNull(),
                    anyList(), anyList(), isNull());
        } else {
            verify(elb).createListener(eq(REGION), eq(LB_ARN), isNull(), isNull(), isNull(),
                    anyList(), anyList(), isNull(), eq(Map.of()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", ""})
    void gatewayListenerPassesExplicitProtocolToService(String protocol) {
        LoadBalancer lb = new LoadBalancer();
        lb.setType("gateway");
        when(elb.describeLoadBalancers(REGION, List.of(LB_ARN), null, null, null)).thenReturn(List.of(lb));
        when(elb.createListener(eq(REGION), eq(LB_ARN), eq(protocol), isNull(), isNull(),
                anyList(), anyList(), isNull(), eq(Map.of()))).thenReturn(listener(LB_ARN));
        StackResource resource = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");

        provisioner.provision(resource, mapper.createObjectNode().put("LoadBalancerArn", LB_ARN)
                .put("Protocol", protocol), ctx());

        verify(elb).createListener(eq(REGION), eq(LB_ARN), eq(protocol), isNull(), isNull(),
                anyList(), anyList(), isNull(), eq(Map.of()));
    }

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    /** Load balancer properties given Subnets, which CreateLoadBalancer requires of every type. */
    private static ObjectNode onTwoSubnets(ObjectNode props) {
        ArrayNode subnets = props.putArray("Subnets");
        TWO_SUBNETS.forEach(subnets::add);
        return props;
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private LoadBalancer loadBalancer(String name) {
        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerName(name);
        lb.setLoadBalancerArn(LB_ARN);
        lb.setDnsName(name + "-123.us-east-1.elb.amazonaws.com");
        lb.setCanonicalHostedZoneId("Z35SXDOTRQ7X7K");
        return lb;
    }

    private TargetGroup targetGroup(String name) {
        TargetGroup tg = new TargetGroup();
        tg.setTargetGroupName(name);
        tg.setTargetGroupArn(TG_ARN);
        return tg;
    }

    private Listener listener(String lbArn) {
        Listener l = new Listener();
        // A listener under another balancer is a different listener with its own ARN.
        l.setListenerArn(lbArn.equals(LB_ARN) ? LISTENER_ARN : LISTENER_ARN.replace("/web/", "/other/"));
        l.setLoadBalancerArn(lbArn);
        return l;
    }

    private Rule rule(String listenerArn) {
        Rule rule = new Rule();
        rule.setRuleArn(listenerArn.equals(LISTENER_ARN) ? RULE_ARN : RULE_ARN.replace("/web/", "/other/"));
        rule.setListenerArn(listenerArn);
        rule.setDefault(false);
        return rule;
    }

    @Test
    void declaresTheFourTypes() {
        assertEquals(Set.of("AWS::ElasticLoadBalancingV2::LoadBalancer", "AWS::ElasticLoadBalancingV2::TargetGroup",
                "AWS::ElasticLoadBalancingV2::Listener", "AWS::ElasticLoadBalancingV2::ListenerRule"),
                provisioner.resourceTypes());
    }

    @Test
    void loadBalancerExposesExactlyTheSchemaAttributes() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), eq("internet-facing"), eq("application"), isNull(),
                eq(List.of("subnet-1", "subnet-2")), eq(List.of("sg-1")), eq(Map.of("env", "test"))))
                .thenReturn(loadBalancerOn("web", "subnet-1", "subnet-2"));
        ObjectNode props = mapper.createObjectNode().put("Name", "web").put("Scheme", "internet-facing")
                .put("Type", "application");
        props.putArray("Subnets").add("subnet-1").add("subnet-2");
        props.putArray("SecurityGroups").add("sg-1");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        provisioner.provision(r, props, ctx());

        assertEquals(LB_ARN, r.getPhysicalId());
        // Fn::GetAtt resolves no __Floci key, so those are the hidden bookkeeping, not attributes.
        assertEquals(Set.of("LoadBalancerArn", "DNSName", "CanonicalHostedZoneID", "LoadBalancerName",
                "LoadBalancerFullName"), r.getAttributes().keySet().stream()
                .filter(key -> !key.startsWith("__Floci")).collect(Collectors.toSet()));
        assertEquals("app/web/50dc6c495c0c9188", r.getAttributes().get("LoadBalancerFullName"));
        assertEquals("web", r.getAttributes().get("LoadBalancerName"));
        assertEquals("Z35SXDOTRQ7X7K", r.getAttributes().get("CanonicalHostedZoneID"));
    }

    @Test
    void anUnnamedLoadBalancerGetsAnElbSafeNameAndKeepsItOnUpdate() {
        when(elb.createLoadBalancer(eq(REGION), anyString(), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenAnswer(inv -> loadBalancer(inv.getArgument(1)));
        StackResource created = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb_With_Underscores");
        provisioner.provision(created, onTwoSubnets(mapper.createObjectNode()), ctx());
        String generated = created.getAttributes().get("LoadBalancerName");
        // 32 characters at most, [A-Za-z0-9-] only, so the underscores are stripped and the
        // descriptive part is cut to make room for the 8-character suffix.
        assertTrue(generated.matches("[A-Za-z0-9-]{1,32}"), generated);
        assertTrue(generated.startsWith("my-stack-AlbWithUndersc-"), generated);

        StackResource updated = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb_With_Underscores");
        updated.getAttributes().put("LoadBalancerName", generated);
        provisioner.provision(updated, onTwoSubnets(mapper.createObjectNode()), ctx(LB_ARN));

        verify(elb, Mockito.times(2)).createLoadBalancer(eq(REGION), eq(generated), isNull(), isNull(),
                isNull(), eq(TWO_SUBNETS), anyList(), any());
    }

    @Test
    void aDuplicateLoadBalancerNameIsReusedNotFailed() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null)).thenReturn(List.of(loadBalancer("web")));

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "web"), ctx(LB_ARN));

        assertEquals(LB_ARN, r.getPhysicalId());
    }

    @Test
    void targetGroupParsesHealthCheckAndMatcherAndExposesLoadBalancerArns() {
        TargetGroup tg = targetGroup("web-tg");
        tg.setLoadBalancerArns(List.of(LB_ARN));
        when(elb.createTargetGroup(eq(REGION), eq("web-tg"), eq("HTTP"), eq("HTTP1"), eq(8080), eq("vpc-1"), eq("ip"),
                eq("HTTP"), eq("traffic-port"), eq(true), eq("/health"), eq(15), eq(5), eq(2), eq(3), eq("200-299"),
                isNull(), eq(Map.of()))).thenReturn(tg);
        ObjectNode props = mapper.createObjectNode().put("Name", "web-tg").put("Protocol", "HTTP")
                .put("ProtocolVersion", "HTTP1").put("Port", "8080").put("VpcId", "vpc-1").put("TargetType", "ip")
                .put("HealthCheckProtocol", "HTTP").put("HealthCheckPort", "traffic-port").put("HealthCheckEnabled", "true")
                .put("HealthCheckPath", "/health").put("HealthCheckIntervalSeconds", "15")
                .put("HealthCheckTimeoutSeconds", "5").put("HealthyThresholdCount", "2").put("UnhealthyThresholdCount", "3");
        props.putObject("Matcher").put("HttpCode", "200-299");

        provisioner.provision(tgResource, props, ctx());

        assertEquals(TG_ARN, tgResource.getPhysicalId());
        assertEquals(Set.of("TargetGroupArn", "TargetGroupName", "TargetGroupFullName", "LoadBalancerArns",
                DECLARED_TARGETS), tgResource.getAttributes().keySet());
        assertEquals("targetgroup/web-tg/73e2d6bc24d8a067", tgResource.getAttributes().get("TargetGroupFullName"));
        assertEquals(LB_ARN, tgResource.getAttributes().get("LoadBalancerArns"));
    }

    @Test
    void listenerDefaultsToHttp80AndParsesCertificatesAndForwardAction() {
        when(elb.createListener(eq(REGION), eq(LB_ARN), eq("HTTP"), eq(80), isNull(), eq(List.of("arn:aws:acm:cert")),
                anyList(), isNull(), eq(Map.of()))).thenReturn(listener(LB_ARN));
        ObjectNode props = mapper.createObjectNode().put("LoadBalancerArn", LB_ARN);
        props.putArray("Certificates").addObject().put("CertificateArn", "arn:aws:acm:cert");
        ObjectNode action = props.putArray("DefaultActions").addObject().put("Type", "forward").put("Order", 1);
        action.putObject("ForwardConfig").putArray("TargetGroups").addObject().put("TargetGroupArn", TG_ARN).put("Weight", 7);

        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, props, ctx());

        assertEquals(LISTENER_ARN, r.getPhysicalId());
        assertEquals(Set.of("ListenerArn"), r.getAttributes().keySet());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Action>> actions = ArgumentCaptor.forClass(List.class);
        verify(elb).createListener(eq(REGION), eq(LB_ARN), eq("HTTP"), eq(80), isNull(), anyList(), actions.capture(),
                isNull(), eq(Map.of()));
        Action parsed = actions.getValue().get(0);
        assertEquals("forward", parsed.getType());
        assertEquals(TG_ARN, parsed.getTargetGroups().get(0).getTargetGroupArn());
        assertEquals(7, parsed.getTargetGroups().get(0).getWeight());
    }

    @Test
    void anUnchangedListenerIsModifiedInPlace() {
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
        when(elb.modifyListener(eq(REGION), eq(LISTENER_ARN), eq("HTTPS"), eq(443), eq("ELBSecurityPolicy-2016-08"),
                anyList(), anyList(), isNull())).thenReturn(listener(LB_ARN));

        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", LB_ARN).put("Protocol", "HTTPS")
                .put("Port", "443").put("SslPolicy", "ELBSecurityPolicy-2016-08"), ctx(LISTENER_ARN));

        assertEquals(LISTENER_ARN, r.getPhysicalId());
        verify(elb, never()).createListener(anyString(), anyString(), anyString(), anyInt(), any(), anyList(), anyList(),
                any(), any());
    }

    @Test
    void aListenerMovedToAnotherLoadBalancerIsCreatedAsAReplacement() {
        String otherLb = LB_ARN.replace("/web/", "/other/");
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
        when(elb.createListener(eq(REGION), eq(otherLb), eq("HTTP"), eq(80), isNull(), anyList(), anyList(), isNull(),
                eq(Map.of()))).thenReturn(listener(otherLb));

        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", otherLb), ctx(LISTENER_ARN));

        verify(elb, never()).modifyListener(anyString(), anyString(), anyString(), anyInt(), any(), anyList(), anyList(),
                any());
        // The displaced listener outlives provision and is deleted by the committed-update cleanup.
        verify(elb, never()).deleteListener(anyString(), anyString());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(LISTENER_ARN, provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);

        InOrder order = inOrder(elb);
        order.verify(elb).createListener(eq(REGION), eq(otherLb), eq("HTTP"), eq(80), isNull(), anyList(), anyList(),
                isNull(), eq(Map.of()));
        order.verify(elb).deleteListener(REGION, LISTENER_ARN);
        assertEquals(new UpdateCleanupResult(true, true, LISTENER_ARN, 0, null), cleanup);
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aListenerRemovedOutOfBandIsCreatedAgain() {
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN)))
                .thenThrow(new AwsException("ListenerNotFound", "gone", 400));
        when(elb.createListener(eq(REGION), eq(LB_ARN), eq("HTTP"), eq(80), isNull(), anyList(), anyList(), isNull(),
                eq(Map.of()))).thenReturn(listener(LB_ARN));

        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", LB_ARN), ctx(LISTENER_ARN));

        assertEquals(LISTENER_ARN, r.getPhysicalId());
    }

    @Test
    void ruleParsesConditionsAndExposesRuleArnAndIsDefault() {
        when(elb.createRule(eq(REGION), eq(LISTENER_ARN), anyList(), eq(10), anyList(), eq(Map.of())))
                .thenReturn(rule(LISTENER_ARN));
        ObjectNode props = mapper.createObjectNode().put("ListenerArn", LISTENER_ARN).put("Priority", "10");
        ArrayNode conditions = props.putArray("Conditions");
        conditions.addObject().put("Field", "path-pattern").putObject("PathPatternConfig").putArray("Values").add("/api/*");
        ObjectNode header = conditions.addObject().put("Field", "http-header");
        header.putObject("HttpHeaderConfig").put("HttpHeaderName", "X-Env").putArray("Values").add("prod");
        conditions.addObject().put("Field", "query-string").putObject("QueryStringConfig").putArray("Values")
                .addObject().put("Key", "v").put("Value", "2");
        props.putArray("Actions").addObject().put("Type", "forward").put("TargetGroupArn", TG_ARN);

        StackResource r = resource("AWS::ElasticLoadBalancingV2::ListenerRule", "Rule");
        provisioner.provision(r, props, ctx());

        assertEquals(RULE_ARN, r.getPhysicalId());
        assertEquals(Set.of("RuleArn", "IsDefault"), r.getAttributes().keySet());
        assertEquals("false", r.getAttributes().get("IsDefault"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RuleCondition>> conds = ArgumentCaptor.forClass(List.class);
        verify(elb).createRule(eq(REGION), eq(LISTENER_ARN), conds.capture(), eq(10), anyList(), eq(Map.of()));
        assertEquals(List.of("/api/*"), conds.getValue().get(0).getPathPatternValues());
        assertEquals("X-Env", conds.getValue().get(1).getHttpHeaderName());
        assertEquals("v", conds.getValue().get(2).getQueryStringValues().get(0).getKey());
    }

    @Test
    void anUnchangedRuleIsModifiedInPlaceAndAMovedRuleIsReplaced() {
        when(elb.describeRules(REGION, null, List.of(RULE_ARN))).thenReturn(List.of(rule(LISTENER_ARN)));
        when(elb.modifyRule(eq(REGION), eq(RULE_ARN), anyList(), anyList())).thenReturn(rule(LISTENER_ARN));
        StackResource same = resource("AWS::ElasticLoadBalancingV2::ListenerRule", "Rule");
        provisioner.provision(same, mapper.createObjectNode().put("ListenerArn", LISTENER_ARN), ctx(RULE_ARN));
        verify(elb).modifyRule(eq(REGION), eq(RULE_ARN), anyList(), anyList());

        String otherListener = LISTENER_ARN.replace("/web/", "/other/");
        when(elb.createRule(eq(REGION), eq(otherListener), anyList(), eq(1), anyList(), eq(Map.of())))
                .thenReturn(rule(otherListener));
        StackResource moved = resource("AWS::ElasticLoadBalancingV2::ListenerRule", "Rule");
        provisioner.provision(moved, mapper.createObjectNode().put("ListenerArn", otherListener), ctx(RULE_ARN));
        verify(elb).createRule(eq(REGION), eq(otherListener), anyList(), eq(1), anyList(), eq(Map.of()));
        assertFalse(provisioner.hasReplacementUpdate(same), "an in-place update owes no cleanup");
        assertEquals(RULE_ARN, provisioner.updateCleanupPhysicalId(moved));
        assertEquals(new UpdateCleanupResult(true, true, RULE_ARN, 0, null), provisioner.completeUpdate(moved));
        verify(elb).deleteRule(REGION, RULE_ARN);
    }

    @Test
    void aRenamedLoadBalancerIsReplacedAndTheOldOneCleanedUpUnlessRetained() {
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(LB_ARN.replace("/web/", "/web-v2/"));
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertEquals(renamed.getLoadBalancerArn(), r.getPhysicalId());
        assertEquals(LB_ARN, provisioner.updateCleanupPhysicalId(r));
        assertEquals(new UpdateCleanupResult(true, true, LB_ARN, 0, null), provisioner.completeUpdate(r));
        verify(elb).deleteLoadBalancer(REGION, LB_ARN);

        StackResource retained = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        retained.setUpdateReplacePolicy("Retain");
        retained.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(retained, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertNull(provisioner.updateCleanupPhysicalId(retained));
        assertEquals(new UpdateCleanupResult(true, true, LB_ARN, 0, null), provisioner.completeUpdate(retained));
        verify(elb, Mockito.times(1)).deleteLoadBalancer(REGION, LB_ARN);
    }

    @Test
    void aFailingCleanupDeleteIsRetriedThreeTimesThenReported() {
        String otherLb = LB_ARN.replace("/web/", "/other/");
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
        when(elb.createListener(eq(REGION), eq(otherLb), eq("HTTP"), eq(80), isNull(), anyList(), anyList(), isNull(),
                eq(Map.of()))).thenReturn(listener(otherLb));
        doThrow(new AwsException("ResourceInUse", "still forwarding", 400)).when(elb).deleteListener(REGION, LISTENER_ARN);
        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", otherLb), ctx(LISTENER_ARN));

        UpdateCleanupResult first = provisioner.completeUpdate(r);
        assertFalse(first.complete());
        assertEquals(1, first.attempts());
        assertEquals("still forwarding", first.failureReason());
        provisioner.completeUpdate(r);
        UpdateCleanupResult third = provisioner.completeUpdate(r);
        assertEquals(3, third.attempts());
        assertEquals(3, provisioner.completeUpdate(r).attempts(), "no fourth attempt");
        verify(elb, Mockito.times(3)).deleteListener(REGION, LISTENER_ARN);
    }

    @Test
    void aFailedStackUpdateRollsAListenerMoveBackAndDeletesTheReplacement() {
        String otherLb = LB_ARN.replace("/web/", "/other/");
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
        when(elb.createListener(eq(REGION), eq(otherLb), eq("HTTP"), eq(80), isNull(), anyList(), anyList(), isNull(),
                eq(Map.of()))).thenReturn(listener(otherLb));
        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        r.getAttributes().put("ListenerArn", LISTENER_ARN);
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", otherLb), ctx(LISTENER_ARN));
        String replacementArn = r.getPhysicalId();

        assertTrue(provisioner.rollbackUpdate(r));

        assertEquals(LISTENER_ARN, r.getPhysicalId());
        assertEquals(LISTENER_ARN, r.getAttributes().get("ListenerArn"));
        verify(elb).deleteListener(REGION, replacementArn);
        verify(elb, never()).deleteListener(REGION, LISTENER_ARN);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anUnchangedLoadBalancerOrTargetGroupHasNothingToRollBack() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null)).thenReturn(List.of(loadBalancer("web")));
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        lb.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(lb, mapper.createObjectNode().put("Name", "web"), ctx(LB_ARN));

        // The update declared no subnets, so it only described the existing balancer and a rollback
        // is complete with nothing to do; the engine must not report it as unimplemented.
        assertTrue(provisioner.rollbackUpdate(lb));
        assertEquals(LB_ARN, lb.getPhysicalId());
        verify(elb, never()).deleteLoadBalancer(anyString(), anyString());
    }

    private LoadBalancer loadBalancerOn(String name, String... subnetIds) {
        LoadBalancer lb = loadBalancer(name);
        for (String subnetId : subnetIds) {
            AvailabilityZone zone = new AvailabilityZone();
            zone.setSubnetId(subnetId);
            lb.getAvailabilityZones().add(zone);
        }
        return lb;
    }

    private StackResource keptLoadBalancerUpdatedTo(LoadBalancer existing, String... subnets) {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null)).thenReturn(List.of(existing));
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        ArrayNode subnetList = props.putArray("Subnets");
        for (String subnet : subnets) {
            subnetList.add(subnet);
        }
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        lb.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(lb, props, ctx(LB_ARN));
        return lb;
    }

    @Test
    void anUpdateMovesTheKeptLoadBalancerOntoTheTemplateSubnetsAndARollbackMovesItBack() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-1", "subnet-3");

        verify(elb).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-3"));
        assertEquals(LB_ARN, lb.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));
    }

    /** The subnets a load balancer is on, as its hidden uses attributes record them. */
    private static Set<String> usedSubnets(StackResource lb) {
        Set<String> used = new HashSet<>();
        lb.getAttributes().forEach((key, value) -> {
            if (key.startsWith(CfnRollback.USES_ATTR_PREFIX)) {
                used.add(value);
            }
        });
        return used;
    }

    @Test
    void theLoadBalancerRecordsTheSubnetsItIsOnAcrossAMoveAndAMoveBack() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(),
                eq(List.of("subnet-1", "subnet-2")), anyList(), any()))
                .thenReturn(loadBalancerOn("web", "subnet-1", "subnet-2"));
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        props.putArray("Subnets").add("subnet-1").add("subnet-2");
        StackResource created = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        provisioner.provision(created, props, ctx());
        assertEquals(Set.of("subnet-1", "subnet-2"), usedSubnets(created));

        StackResource moved = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-3", "subnet-4");
        assertEquals(Set.of("subnet-3", "subnet-4"), usedSubnets(moved));

        assertTrue(provisioner.rollbackUpdate(moved));
        assertEquals(Set.of("subnet-1", "subnet-2"), usedSubnets(moved));
    }

    /**
     * An update whose template names neither {@code Subnets} nor {@code SubnetMappings} leaves the kept
     * load balancer on its subnets: it does not call ElbV2Service.setSubnets, which would move the
     * balancer onto none.
     */
    @Test
    void anUpdateThatNamesNoSubnetsLeavesTheKeptLoadBalancerOnItsSubnets() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null))
                .thenReturn(List.of(loadBalancerOn("web", "subnet-1", "subnet-2")));
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        lb.getAttributes().put("LoadBalancerName", "web");

        provisioner.provision(lb, mapper.createObjectNode().put("Name", "web"), ctx(LB_ARN));

        assertEquals(Set.of("subnet-1", "subnet-2"), usedSubnets(lb));
        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb, never()).setSubnets(anyString(), anyString(), anyList());
    }

    /**
     * A load balancer an earlier Floci version saved without subnets is moved back onto none when an
     * update that gave it subnets rolls back: the move back goes through ElbV2Service.setSubnets, which
     * takes an empty list, where the SetSubnets action refuses one.
     */
    @Test
    void aRollbackMovesALoadBalancerSavedWithoutSubnetsBackOntoNone() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web"), "subnet-1", "subnet-2");
        verify(elb).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));

        assertTrue(provisioner.rollbackUpdate(lb));

        verify(elb).setSubnets(REGION, LB_ARN, List.of());
        assertEquals(Set.of(), usedSubnets(lb));
    }

    @Test
    void theSameSubnetsInAnotherOrderLeaveTheKeptLoadBalancerAlone() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-2", "subnet-1");

        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb, never()).setSubnets(anyString(), anyString(), anyList());
    }

    @Test
    void aCommittedUpdateForgetsThePriorSubnets() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-1", "subnet-3");
        lb.setStatus("UPDATE_COMPLETE");

        provisioner.completeUpdate(lb);

        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-3"));
        verify(elb, never()).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));
    }

    @Test
    void aFailedMoveBackPropagatesAndKeepsThePriorSubnetsForAnotherTry() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-1", "subnet-3");
        doThrow(new AwsException("SubnetNotFound", "The subnet ID 'subnet-2' does not exist", 400)).doNothing()
                .when(elb).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(lb));
        assertEquals("SubnetNotFound", failure.getErrorCode());
        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb, times(2)).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));
    }

    @Test
    void aClearedUpdateForgetsThePriorSubnets() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-1", "subnet-3");

        provisioner.clearUpdate(lb);

        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb, never()).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));
    }

    @Test
    void aLaterUpdateThatLeavesTheSubnetsAloneDropsAStaleSnapshot() {
        StackResource lb = keptLoadBalancerUpdatedTo(loadBalancerOn("web", "subnet-1", "subnet-2"),
                "subnet-1", "subnet-3");
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        props.putArray("Subnets").add("subnet-2").add("subnet-1");

        provisioner.provision(lb, props, ctx(LB_ARN));

        assertTrue(provisioner.rollbackUpdate(lb));
        verify(elb, never()).setSubnets(REGION, LB_ARN, List.of("subnet-1", "subnet-2"));
    }

    @Test
    void aCreateThatFindsTheNameTakenLeavesThatLoadBalancerOnItsSubnets() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null))
                .thenReturn(List.of(loadBalancerOn("web", "subnet-1", "subnet-2")));
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        props.putArray("Subnets").add("subnet-3").add("subnet-4");

        provisioner.provision(resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb"), props, ctx());

        verify(elb, never()).setSubnets(anyString(), anyString(), anyList());
    }

    @Test
    void subnetMappingsGiveTheSubnetsWhenSubnetsIsAbsent() {
        when(elb.createLoadBalancer(eq(REGION), eq("nlb"), isNull(), eq("network"), isNull(),
                eq(List.of("subnet-1", "subnet-2")), anyList(), any())).thenReturn(loadBalancer("nlb"));
        ObjectNode props = mapper.createObjectNode().put("Name", "nlb").put("Type", "network");
        ArrayNode mappings = props.putArray("SubnetMappings");
        mappings.addObject().put("SubnetId", "subnet-1").put("AllocationId", "eipalloc-1");
        mappings.addObject().put("SubnetId", "subnet-2");

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Nlb");
        provisioner.provision(r, props, ctx());

        assertEquals(LB_ARN, r.getPhysicalId());
    }

    /**
     * {@code SubnetId} is required in a {@code SubnetMapping}. A mapping without one fails the
     * resource before any load balancer is created, rather than leaving a balancer with no subnets.
     */
    @Test
    void aSubnetMappingWithoutASubnetIdIsRefusedBeforeTheLoadBalancerIsCreated() {
        ObjectNode props = mapper.createObjectNode().put("Name", "nlb").put("Type", "network");
        props.putArray("SubnetMappings").addObject().put("AllocationId", "eipalloc-1");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Nlb"), props, ctx()));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertTrue(e.getMessage().contains("#/SubnetMappings/0: required key [SubnetId] not found"), e.getMessage());
        verify(elb, never()).createLoadBalancer(anyString(), anyString(), any(), any(), any(), anyList(), anyList(), any());
    }

    /** The same mapping on an update of a kept load balancer fails before it is moved anywhere. */
    @Test
    void anUpdateWithASubnetMappingWithoutASubnetIdLeavesTheKeptLoadBalancerAlone() {
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        ArrayNode mappings = props.putArray("SubnetMappings");
        mappings.addObject().put("SubnetId", "subnet-1");
        mappings.addObject().put("PrivateIPv4Address", "10.0.2.10");
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        lb.getAttributes().put("LoadBalancerName", "web");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(lb, props, ctx(LB_ARN)));

        assertTrue(e.getMessage().contains("#/SubnetMappings/1: required key [SubnetId] not found"), e.getMessage());
        verify(elb, never()).createLoadBalancer(anyString(), anyString(), any(), any(), any(), anyList(), anyList(), any());
        verify(elb, never()).setSubnets(anyString(), anyString(), anyList());
    }

    /**
     * A blank {@code SubnetId} is passed on as given, so ELBv2 refuses it as an unknown subnet
     * rather than the mapping being dropped and the balancer created without it.
     */
    @Test
    void aBlankSubnetIdIsPassedOnRatherThanDropped() {
        when(elb.createLoadBalancer(eq(REGION), eq("nlb"), isNull(), eq("network"), isNull(),
                eq(List.of(" ")), anyList(), any()))
                .thenThrow(new AwsException("SubnetNotFound", "The subnet ID ' ' does not exist", 400));
        ObjectNode props = mapper.createObjectNode().put("Name", "nlb").put("Type", "network");
        props.putArray("SubnetMappings").addObject().put("SubnetId", " ");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Nlb"), props, ctx()));

        assertEquals("SubnetNotFound", e.getErrorCode());
    }

    /**
     * On an update of a kept Network Load Balancer, a blank {@code SubnetId} reaches SetSubnets for ELBv2
     * to refuse as an unknown subnet. An Application Load Balancer would get the subnet count error first.
     */
    @Test
    void anUpdateWithABlankSubnetIdIsRefusedByTheMoveAndKeepsNoSnapshot() {
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), eq("network"), isNull(), anyList(), anyList(),
                any())).thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        LoadBalancer kept = loadBalancerOn("web", "subnet-1", "subnet-2");
        kept.setType("network");
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null)).thenReturn(List.of(kept));
        doThrow(new AwsException("SubnetNotFound", "The subnet ID ' ' does not exist", 400))
                .when(elb).setSubnets(REGION, LB_ARN, List.of(" "));
        ObjectNode props = mapper.createObjectNode().put("Name", "web").put("Type", "network");
        props.putArray("SubnetMappings").addObject().put("SubnetId", " ");
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Nlb");
        lb.getAttributes().put("LoadBalancerName", "web");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(lb, props, ctx(LB_ARN)));

        assertEquals("SubnetNotFound", e.getErrorCode());
        assertNull(lb.getAttributes().get(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR));
    }

    /**
     * {@code Subnets} and {@code SubnetMappings} together fail the resource with the error ELBv2's
     * CreateLoadBalancer gives for both, before any load balancer is created.
     */
    @Test
    void subnetsAndSubnetMappingsTogetherAreRefusedBeforeTheLoadBalancerIsCreated() {
        ObjectNode props = mapper.createObjectNode().put("Name", "nlb").put("Type", "network");
        props.putArray("Subnets").add("subnet-1");
        props.putArray("SubnetMappings").addObject().put("SubnetId", "subnet-2");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Nlb"), props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("You can specify either subnets or subnet mappings, not both", e.getMessage());
        verify(elb, never()).createLoadBalancer(anyString(), anyString(), any(), any(), any(), anyList(), anyList(), any());
    }

    /** An empty {@code SubnetMappings} beside {@code Subnets}, as an Fn::If that drops each leaves, is allowed. */
    @Test
    void anEmptySubnetMappingsBesideSubnetsIsAllowed() {
        when(elb.createLoadBalancer(eq(REGION), eq("alb"), isNull(), isNull(), isNull(),
                eq(List.of("subnet-1", "subnet-2")), anyList(), any())).thenReturn(loadBalancer("alb"));
        ObjectNode props = mapper.createObjectNode().put("Name", "alb");
        props.putArray("Subnets").add("subnet-1").add("subnet-2");
        props.putArray("SubnetMappings");

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        provisioner.provision(r, props, ctx());

        assertEquals(LB_ARN, r.getPhysicalId());
    }

    /** The same on an update of a kept load balancer, before it is moved anywhere. */
    @Test
    void anUpdateWithSubnetsAndSubnetMappingsTogetherLeavesTheKeptLoadBalancerAlone() {
        ObjectNode props = mapper.createObjectNode().put("Name", "web");
        props.putArray("Subnets").add("subnet-1").add("subnet-3");
        props.putArray("SubnetMappings").addObject().put("SubnetId", "subnet-3");
        StackResource lb = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        lb.getAttributes().put("LoadBalancerName", "web");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(lb, props, ctx(LB_ARN)));

        assertEquals("ValidationError", e.getErrorCode());
        verify(elb, never()).createLoadBalancer(anyString(), anyString(), any(), any(), any(), anyList(), anyList(), any());
        verify(elb, never()).setSubnets(anyString(), anyString(), anyList());
    }

    @Test
    void anInPlaceListenerUpdateIsNotRolledBackHere() {
        when(elb.describeListeners(REGION, null, List.of(LISTENER_ARN))).thenReturn(List.of(listener(LB_ARN)));
        when(elb.modifyListener(eq(REGION), eq(LISTENER_ARN), eq("HTTP"), eq(8080), isNull(), anyList(), anyList(), isNull()))
                .thenReturn(listener(LB_ARN));
        StackResource r = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        provisioner.provision(r, mapper.createObjectNode().put("LoadBalancerArn", LB_ARN).put("Port", "8080"), ctx(LISTENER_ARN));

        assertFalse(provisioner.rollbackUpdate(r), "no replacement means nothing this helper can undo");
        verify(elb, never()).deleteListener(anyString(), anyString());
    }

    @Test
    void aRollbackWhoseDeleteFailsStillPointsTheResourceAtThePriorAndPropagates() {
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(LB_ARN.replace("/web/", "/web-v2/"));
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400))
                .when(elb).deleteLoadBalancer(REGION, renamed.getLoadBalancerArn());
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        r.getAttributes().put("DNSName", "web-123.us-east-1.elb.amazonaws.com");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals("ResourceInUse", e.getErrorCode());
        assertEquals(LB_ARN, r.getPhysicalId());
        assertEquals("web", r.getAttributes().get("LoadBalancerName"));
        assertEquals("web-123.us-east-1.elb.amazonaws.com", r.getAttributes().get("DNSName"));

        // The replacement the failed update created stays owed a delete; the restored prior does not.
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(renamed.getLoadBalancerArn(), provisioner.updateCleanupPhysicalId(r));
        Mockito.reset(elb);
        // A retried rollback (ContinueUpdateRollback) re-attempts the delete that failed; succeeding,
        // it owes nothing more and never touches the restored prior.
        assertTrue(provisioner.rollbackUpdate(r));
        verify(elb).deleteLoadBalancer(REGION, renamed.getLoadBalancerArn());
        verify(elb, never()).deleteLoadBalancer(REGION, LB_ARN);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anOrphanFromAFailedRollbackSurvivesTheNextProvisionAndIsDeletedWithTheNextReplacement() {
        String orphanArn = LB_ARN.replace("/web/", "/web-v2/");
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(orphanArn);
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400)).when(elb).deleteLoadBalancer(REGION, orphanArn);
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals(LB_ARN, r.getPhysicalId());

        // The next update keeps the name: nothing replaced, but the orphan is still owed.
        when(elb.createLoadBalancer(eq(REGION), eq("web"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "exists", 400));
        when(elb.describeLoadBalancers(REGION, null, List.of("web"), null, null)).thenReturn(List.of(loadBalancer("web")));
        provisioner.provision(r, mapper.createObjectNode().put("Name", "web"), ctx(LB_ARN));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(orphanArn, provisioner.updateCleanupPhysicalId(r));
        provisioner.rollbackUpdate(r);
        assertTrue(provisioner.hasReplacementUpdate(r), "a rollback with no replacement of its own does not forget the orphan");
        assertEquals(LB_ARN, r.getPhysicalId());

        // A later replacement adds the prior to the same list; the committed cleanup deletes both.
        LoadBalancer third = loadBalancer("web-v3");
        third.setLoadBalancerArn(LB_ARN.replace("/web/", "/web-v3/"));
        when(elb.createLoadBalancer(eq(REGION), eq("web-v3"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(third);
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v3")), ctx(LB_ARN));
        Mockito.reset(elb);
        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);
        assertTrue(cleanup.complete(), cleanup.toString());
        verify(elb).deleteLoadBalancer(REGION, orphanArn);
        verify(elb).deleteLoadBalancer(REGION, LB_ARN);
        verify(elb, never()).deleteLoadBalancer(REGION, third.getLoadBalancerArn());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aFailingEntryDoesNotStopTheOtherEntriesFromBeingTried() {
        String orphanArn = LB_ARN.replace("/web/", "/web-v2/");
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(orphanArn);
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400)).when(elb).deleteLoadBalancer(REGION, orphanArn);
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        LoadBalancer third = loadBalancer("web-v3");
        third.setLoadBalancerArn(LB_ARN.replace("/web/", "/web-v3/"));
        when(elb.createLoadBalancer(eq(REGION), eq("web-v3"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(third);
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v3")), ctx(LB_ARN));

        // The orphan keeps failing; the prior is deleted on the first pass all the same, and the
        // failing entry is reported with its own attempt count until the engine gives up.
        UpdateCleanupResult first = provisioner.completeUpdate(r);
        assertFalse(first.complete());
        assertEquals(orphanArn, first.previousPhysicalId());
        assertEquals(1, first.attempts());
        verify(elb).deleteLoadBalancer(REGION, LB_ARN);
        provisioner.completeUpdate(r);
        UpdateCleanupResult third3 = provisioner.completeUpdate(r);
        assertEquals(3, third3.attempts());
        assertEquals("listeners attached", third3.failureReason());
        verify(elb, Mockito.times(1)).deleteLoadBalancer(REGION, LB_ARN);
        verify(elb, Mockito.times(4)).deleteLoadBalancer(REGION, orphanArn);
    }

    @Test
    void givingUpOnOneEntityDoesNotForgetAnotherThatStillHasAttemptsLeft() {
        String orphanArn = LB_ARN.replace("/web/", "/web-v2/");
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(orphanArn);
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400)).when(elb).deleteLoadBalancer(REGION, orphanArn);
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        // The orphan uses up its three attempts in a cleanup where it is the only entry.
        for (int i = 0; i < 3; i++) {
            provisioner.completeUpdate(r);
        }
        assertEquals(3, provisioner.completeUpdate(r).attempts());

        // A later replacement lists the prior beside the exhausted orphan; its delete fails once.
        LoadBalancer third = loadBalancer("web-v3");
        third.setLoadBalancerArn(LB_ARN.replace("/web/", "/web-v3/"));
        when(elb.createLoadBalancer(eq(REGION), eq("web-v3"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(third);
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v3")), ctx(LB_ARN));
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400)).when(elb).deleteLoadBalancer(REGION, LB_ARN);
        UpdateCleanupResult first = provisioner.completeUpdate(r);
        assertFalse(first.complete());
        assertEquals(1, first.attempts(), "the entry with attempts left is what the engine retries for");
        assertEquals(LB_ARN, first.previousPhysicalId());
        assertEquals(first.previousPhysicalId(), provisioner.updateCleanupPhysicalId(r),
                "progress and failure events name the same entity");

        Mockito.doNothing().when(elb).deleteLoadBalancer(REGION, LB_ARN);
        UpdateCleanupResult second = provisioner.completeUpdate(r);
        assertEquals(3, second.attempts(), "only the exhausted orphan remains");
        assertEquals(orphanArn, second.previousPhysicalId());
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r));
        verify(elb, Mockito.times(4)).deleteLoadBalancer(REGION, orphanArn);
    }

    @Test
    void clearingAfterAGiveUpKeepsAnEntryThatStillHasAttemptsLeft() {
        String orphanArn = LB_ARN.replace("/web/", "/web-v2/");
        LoadBalancer renamed = loadBalancer("web-v2");
        renamed.setLoadBalancerArn(orphanArn);
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), eq(TWO_SUBNETS), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400)).when(elb).deleteLoadBalancer(REGION, orphanArn);
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, onTwoSubnets(mapper.createObjectNode().put("Name", "web-v2")), ctx(LB_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        provisioner.completeUpdate(r);

        provisioner.clearUpdate(r);
        assertTrue(provisioner.hasReplacementUpdate(r), "cleared with attempts left, the orphan is not forgotten");
        assertEquals(orphanArn, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void aNonIntegerPortOrPriorityIsAValidationError() {
        StackResource listener = resource("AWS::ElasticLoadBalancingV2::Listener", "Listener");
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(listener,
                mapper.createObjectNode().put("LoadBalancerArn", LB_ARN).put("Port", "eighty"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        assertNull(listener.getPhysicalId());

        StackResource rule = resource("AWS::ElasticLoadBalancingV2::ListenerRule", "Rule");
        assertThrows(AwsException.class, () -> provisioner.provision(rule,
                mapper.createObjectNode().put("ListenerArn", LISTENER_ARN).put("Priority", "high"), ctx()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Port", "HealthCheckIntervalSeconds", "HealthCheckTimeoutSeconds",
            "HealthyThresholdCount", "UnhealthyThresholdCount"})
    void aNonIntegerTargetGroupIntegerIsAValidationError(String property) {
        assertRejected(property, "ten");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Port", "HealthCheckIntervalSeconds", "HealthCheckTimeoutSeconds",
            "HealthyThresholdCount", "UnhealthyThresholdCount"})
    void aLiteralBlankTargetGroupIntegerIsAValidationError(String property) {
        assertRejected(property, "");
        assertRejected(property, "  ");
    }

    private void assertRejected(String property, String value) {
        ObjectNode props = mapper.createObjectNode().put("Name", "web-tg").put(property, value);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(tgResource, props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("Value of property " + property + " must be an integer.", e.getMessage());
        assertNull(tgResource.getPhysicalId());
        anyCreateTargetGroup(verify(elb, never()));
    }

    @Test
    void anIntegerAnIntrinsicResolvesToNothingReachesTheServiceUnset() {
        // Fn::If [cond, 30, AWS::NoValue] drops the property; the engine resolves AWS::NoValue to blank.
        ObjectNode props = lambdaGroup();
        props.putObject("HealthCheckIntervalSeconds").put("Ref", "AWS::NoValue");

        assertCreatedWithEveryOptionalUnset(props);
    }

    @Test
    void absentTargetGroupIntegersReachTheServiceUnset() {
        assertCreatedWithEveryOptionalUnset(lambdaGroup());
    }

    /** Properties of a Lambda target group that name only the group and its type. */
    private ObjectNode lambdaGroup() {
        return mapper.createObjectNode().put("Name", "fn-tg").put("TargetType", "lambda");
    }

    /**
     * Provisions {@code props}, a Lambda group named fn-tg, and asserts the group was created, which the
     * stub allows only when CreateTargetGroup got every optional argument unset.
     */
    private void assertCreatedWithEveryOptionalUnset(ObjectNode props) {
        when(elb.createTargetGroup(eq(REGION), eq("fn-tg"), isNull(), isNull(), isNull(), isNull(), eq("lambda"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(Map.of()))).thenReturn(targetGroup("fn-tg"));

        provisioner.provision(tgResource, props, ctx());

        assertEquals(TG_ARN, tgResource.getPhysicalId());
    }

    /** A target as the group and the record carry it: its Id, port and zone, the last two null when unset. */
    private TargetDescription target(String id, Integer port, String zone) {
        return mapper.convertValue(mapper.createObjectNode().put("id", id).put("port", port)
                .put("availabilityZone", zone), TargetDescription.class);
    }

    private TargetDescription target(String id) {
        return target(id, null, null);
    }

    private static List<String> described(List<TargetDescription> targets) {
        return targets.stream().map(t -> t.getId() + ":" + t.getPort() + ":" + t.getAvailabilityZone()).toList();
    }

    private String recordOf(TargetDescription... targets) {
        return mapper.valueToTree(List.of(targets)).toString();
    }

    /** Target group properties declaring {@code targets}, or no Targets when there are none. */
    private ObjectNode declaring(TargetDescription... targets) {
        ObjectNode props = mapper.createObjectNode().put("Name", "web-tg").put("Port", "80");
        if (targets.length > 0) {
            ArrayNode list = props.putArray("Targets");
            for (TargetDescription target : targets) {
                ObjectNode item = list.addObject().put("Id", target.getId());
                if (target.getPort() != null) {
                    item.put("Port", String.valueOf(target.getPort()));
                }
                if (target.getAvailabilityZone() != null) {
                    item.put("AvailabilityZone", target.getAvailabilityZone());
                }
            }
        }
        return props;
    }

    private TargetGroup targetGroupOnPort80() {
        TargetGroup tg = targetGroup("web-tg");
        tg.setPort(80);
        return tg;
    }

    /** Replaces what is registered on {@link #group}. */
    private void registered(List<TargetDescription> targets) {
        group.getTargets().clear();
        group.getTargets().addAll(targets);
    }

    /** CreateTargetGroup with any arguments, called on {@code service}: the mock, or a verification of it. */
    private static TargetGroup anyCreateTargetGroup(ElbV2Service service) {
        return service.createTargetGroup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** Provisions {@link #tgResource} as {@link #group}, which the provision creates, declaring {@code targets}. */
    private void createdTargetGroupDeclaring(ProvisionContext ctx, TargetDescription... targets) {
        when(anyCreateTargetGroup(elb)).thenReturn(group);
        provisioner.provision(tgResource, declaring(targets), ctx);
    }

    /**
     * Provisions an update of {@link #tgResource}, whose group the stack keeps with {@code onTheGroup}
     * registered, to {@code props}, with {@code record} as the targets the last template declared, or
     * none recorded when it is null.
     */
    private void keptTargetGroupUpdatedTo(List<TargetDescription> onTheGroup, ObjectNode props, String record) {
        registered(onTheGroup);
        when(anyCreateTargetGroup(elb)).thenThrow(new AwsException("DuplicateTargetGroupName", "exists", 400));
        when(elb.describeTargetGroups(REGION, null, null, List.of("web-tg"))).thenReturn(List.of(group));
        tgResource.setPhysicalId(TG_ARN);
        tgResource.getAttributes().put("TargetGroupName", "web-tg");
        if (record != null) {
            tgResource.getAttributes().put(DECLARED_TARGETS, record);
        }
        provisioner.provision(tgResource, props, ctx(TG_ARN));
    }

    /**
     * An update of the kept group from a template declaring 10.0.0.1 to one declaring 10.0.0.2, with
     * 10.0.0.1 and 10.0.0.9, registered outside the stack, on the group.
     */
    private void keptGroupSwitchedToASecondTarget() {
        keptTargetGroupUpdatedTo(List.of(target("10.0.0.1", 80, null), target("10.0.0.9", 80, null)),
                declaring(target("10.0.0.2")), recordOf(target("10.0.0.1")));
    }

    /** {@link #keptGroupSwitchedToASecondTarget}, checked to leave the snapshot and prior record for a later step. */
    private void keptGroupSwitchedLeavingItsRecords() {
        keptGroupSwitchedToASecondTarget();
        assertTrue(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT));
        assertTrue(tgResource.getAttributes().containsKey(DECLARED_TARGETS_PRIOR));
    }

    /** Asserts that 10.0.0.2 was registered and deregistered again, and 10.0.0.1 deregistered and registered again. */
    private void assertSecondTargetSwappedBack() {
        assertEquals(List.of("10.0.0.2:null:null", "10.0.0.1:80:null"), registeredBy(2));
        assertEquals(List.of("10.0.0.1:80:null", "10.0.0.2:null:null"), deregisteredBy(2));
    }

    private static AwsException internalFailure() {
        return new AwsException("InternalFailure", "storage failed", 500);
    }

    /** Makes deleting {@link #group} fail, and returns the failure. */
    private AwsException failingGroupDelete() {
        AwsException deleteFailure = internalFailure();
        doThrow(deleteFailure).when(elb).deleteTargetGroup(REGION, TG_ARN);
        return deleteFailure;
    }

    /** Makes the first RegisterTargets call refuse its targets, as it refuses a port out of range, and returns it. */
    private AwsException refusingRegistration() {
        AwsException refusal = new AwsException("ValidationError", "port out of range", 400);
        doThrow(refusal).doNothing().when(elb).registerTargets(eq(REGION), eq(TG_ARN), anyList());
        return refusal;
    }

    /** Makes DeregisterTargets throw {@code failure} on its first {@code calls} calls, and returns it. */
    private AwsException failingDeregistration(AwsException failure, int calls) {
        Stubber stub = doThrow(failure);
        for (int i = 1; i < calls; i++) {
            stub = stub.doThrow(failure);
        }
        stub.doNothing().when(elb).deregisterTargets(eq(REGION), eq(TG_ARN), anyList());
        return failure;
    }

    private List<String> registeredBy(int calls) {
        return targetsSentBy(calls,
                (service, sent) -> service.registerTargets(eq(REGION), eq(TG_ARN), sent.capture()));
    }

    private List<String> deregisteredBy(int calls) {
        return targetsSentBy(calls,
                (service, sent) -> service.deregisterTargets(eq(REGION), eq(TG_ARN), sent.capture()));
    }

    /** The targets handed to {@code call}, made on a verification of the mock, over its {@code calls} calls. */
    @SuppressWarnings("unchecked")
    private List<String> targetsSentBy(int calls,
                                       BiConsumer<ElbV2Service, ArgumentCaptor<List<TargetDescription>>> call) {
        ArgumentCaptor<List<TargetDescription>> sent = ArgumentCaptor.forClass(List.class);
        call.accept(verify(elb, times(calls)), sent);
        return sent.getAllValues().stream().flatMap(targets -> described(targets).stream()).toList();
    }

    @Test
    void aCreatedTargetGroupRegistersTheDeclaredTargetsAndRecordsThem() {
        createdTargetGroupDeclaring(ctx(), target("i-1"), target("i-1", 8080, null));

        assertEquals(List.of("i-1:null:null", "i-1:8080:null"), registeredBy(1));
        verify(elb, never()).deregisterTargets(any(), any(), any());
        assertEquals(recordOf(target("i-1"), target("i-1", 8080, null)),
                tgResource.getAttributes().get(DECLARED_TARGETS));
        assertFalse(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT), "a create has nothing to roll back");
    }

    @Test
    void aTargetWithoutAnIdFailsBeforeTheGroupIsCreated() {
        ObjectNode props = declaring();
        props.putArray("Targets").addObject().put("Port", "80");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(tgResource, props, ctx()));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Model validation failed (#/Targets/0: required key [Id] not found)", e.getMessage());
        anyCreateTargetGroup(verify(elb, never()));
    }

    @Test
    void refusedTargetsDeleteTheGroupThisProvisionCreated() {
        AwsException refusal = refusingRegistration();
        AwsException deleteFailure = failingGroupDelete();

        AwsException e = assertThrows(AwsException.class,
                () -> createdTargetGroupDeclaring(ctx(), target("10.0.0.5", 70000, null)));

        assertEquals(refusal, e);
        assertEquals(List.of(deleteFailure), List.of(e.getSuppressed()));
        verify(elb).deleteTargetGroup(REGION, TG_ARN);
        // A group whose delete failed stays marked as the stack's, so the stack's rollback deletes it.
        assertEquals(TG_ARN, tgResource.getPhysicalId());
        assertEquals("true", tgResource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void aTargetRefusedOnAKeptGroupIsNotDeregisteredOnTheWayBack() {
        AwsException refusal = refusingRegistration();

        assertEquals(refusal, assertThrows(AwsException.class, this::keptGroupSwitchedToASecondTarget));

        // RegisterTargets registered nothing, so the way back has nothing to deregister.
        verify(elb, never()).deregisterTargets(any(), any(), any());
        verify(elb, never()).deleteTargetGroup(any(), any());
        assertFalse(tgResource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aFailedDeregistrationPutsAKeptGroupBack() {
        AwsException failure = failingDeregistration(internalFailure(), 1);

        assertEquals(failure, assertThrows(AwsException.class, this::keptGroupSwitchedToASecondTarget));

        assertSecondTargetSwappedBack();
        verify(elb, never()).deleteTargetGroup(any(), any());
        assertFalse(tgResource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anUndoThatFailsLeavesItsSnapshotForTheRestoredResource() {
        failingDeregistration(internalFailure(), 2);
        assertThrows(AwsException.class, this::keptGroupSwitchedToASecondTarget);

        provisioner.mergeFailedUpdateResourceTracking(restored, tgResource);

        assertTrue(tgResource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertTrue(restored.getAttributes().containsKey(TARGETS_SNAPSHOT));
        assertEquals(tgResource.getAttributes().get(TARGETS_SNAPSHOT), restored.getAttributes().get(TARGETS_SNAPSHOT));
    }

    @Test
    void aSnapshotWithoutAFailedUndoStaysWithTheFailedAttempt() {
        tgResource.getAttributes().put(TARGETS_SNAPSHOT, "{\"added\":[],\"removed\":[]}");

        provisioner.mergeFailedUpdateResourceTracking(restored, tgResource);

        assertFalse(restored.getAttributes().containsKey(TARGETS_SNAPSHOT));
    }

    @Test
    void aReplacementRegistersOnTheNewGroupAndItsRollbackRestoresTheRecordEvenWhenTheDeleteFails() {
        String priorArn = TG_ARN.replace("web-tg/73e2d6bc24d8a067", "old-tg/0123456789abcdef");
        tgResource.setPhysicalId(priorArn);
        tgResource.getAttributes().put(DECLARED_TARGETS, recordOf(target("10.0.0.1")));

        createdTargetGroupDeclaring(ctx(priorArn), target("10.0.0.2"));

        assertEquals(TG_ARN, tgResource.getPhysicalId());
        assertEquals(List.of("10.0.0.2:null:null"), registeredBy(1));
        verify(elb, never()).deregisterTargets(any(), any(), any());
        assertFalse(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT), "the new group has nothing to put back");
        failingGroupDelete();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(tgResource));

        assertEquals("InternalFailure", e.getErrorCode(), "the replacement's delete failed");
        assertEquals(priorArn, tgResource.getPhysicalId());
        assertEquals(recordOf(target("10.0.0.1")), tgResource.getAttributes().get(DECLARED_TARGETS));
        assertFalse(tgResource.getAttributes().containsKey(DECLARED_TARGETS_PRIOR));
    }

    @Test
    void anUpdateSwapsTheDeclaredTargetsAndARollbackSwapsThemBack() {
        keptGroupSwitchedToASecondTarget();

        assertEquals(List.of("10.0.0.2:null:null"), registeredBy(1));
        assertEquals(List.of("10.0.0.1:80:null"), deregisteredBy(1), "the target registered outside the stack stays");
        InOrder order = inOrder(elb);
        order.verify(elb).registerTargets(eq(REGION), eq(TG_ARN), anyList());
        order.verify(elb).deregisterTargets(eq(REGION), eq(TG_ARN), anyList());
        assertEquals(recordOf(target("10.0.0.2")), tgResource.getAttributes().get(DECLARED_TARGETS));

        assertTrue(provisioner.rollbackUpdate(tgResource));

        assertSecondTargetSwappedBack();
        assertEquals(recordOf(target("10.0.0.1")), tgResource.getAttributes().get(DECLARED_TARGETS));
        assertFalse(tgResource.getAttributes().containsKey(DECLARED_TARGETS_PRIOR));
        assertFalse(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT));
    }

    @Test
    void aTargetDeclaredInAnotherZoneIsRegisteredOverAndARollbackPutsTheZoneBack() {
        keptTargetGroupUpdatedTo(List.of(target("10.0.0.1", 80, null)), declaring(target("10.0.0.1", null, "all")),
                recordOf(target("10.0.0.1")));

        assertEquals(List.of("10.0.0.1:null:all"), registeredBy(1));
        verify(elb, never()).deregisterTargets(any(), any(), any());

        assertTrue(provisioner.rollbackUpdate(tgResource));

        assertEquals(List.of("10.0.0.1:null:all", "10.0.0.1:80:null"), registeredBy(2));
        verify(elb, never()).deregisterTargets(any(), any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "not json")
    void aGroupWithNoReadableRecordRegistersItsTargetsAndDeregistersNothing(String record) {
        // Provisioned before Floci registered targets, or with a record that cannot be read, so nothing
        // registered is known to be the stack's.
        keptTargetGroupUpdatedTo(List.of(target("10.0.0.1", 80, null)), declaring(target("10.0.0.2")), record);

        assertEquals(List.of("10.0.0.2:null:null"), registeredBy(1));
        verify(elb, never()).deregisterTargets(any(), any(), any());
        assertEquals(recordOf(target("10.0.0.2")), tgResource.getAttributes().get(DECLARED_TARGETS));
    }

    @Test
    void droppingTargetsDeregistersOnlyTheDeclaredOnes() {
        keptTargetGroupUpdatedTo(List.of(target("i-1", 80, null), target("i-1", 8080, null), target("i-2", 80, null)),
                declaring(), recordOf(target("i-1"), target("i-2", 80, null)));

        assertEquals(List.of("i-1:80:null", "i-2:80:null"), deregisteredBy(1));
        verify(elb, never()).registerTargets(any(), any(), any());
        assertEquals("[]", tgResource.getAttributes().get(DECLARED_TARGETS));
    }

    @Test
    void anUpdateThatChangesNoRegistrationDropsAnEarlierSnapshotAndHasNothingToRollBack() {
        keptGroupSwitchedLeavingItsRecords();
        registered(List.of(target("10.0.0.2", 80, null), target("10.0.0.9", 80, null)));

        provisioner.provision(tgResource, declaring(target("10.0.0.2", 80, null)), ctx(TG_ARN));

        assertFalse(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT));
        assertTrue(provisioner.rollbackUpdate(tgResource));
        assertEquals(List.of("10.0.0.2:null:null"), registeredBy(1));
        assertEquals(List.of("10.0.0.1:80:null"), deregisteredBy(1));
    }

    @Test
    void aRollbackOfAGroupGoneSinceTheUpdateFailsAndKeepsTheSnapshot() {
        keptGroupSwitchedToASecondTarget();
        failingDeregistration(new AwsException("TargetGroupNotFound", "gone", 400), 1);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(tgResource));

        assertEquals("TargetGroupNotFound", e.getErrorCode());
        assertTrue(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT), "a retried rollback tries again");
    }

    @Test
    void aCommittedUpdateSpendsTheTargetSnapshotAndPriorRecord() {
        keptGroupSwitchedLeavingItsRecords();

        provisioner.completeUpdate(tgResource);

        assertFalse(tgResource.getAttributes().containsKey(TARGETS_SNAPSHOT));
        assertFalse(tgResource.getAttributes().containsKey(DECLARED_TARGETS_PRIOR));
        assertEquals(recordOf(target("10.0.0.2")), tgResource.getAttributes().get(DECLARED_TARGETS));
    }

    @Test
    void deletesCallTheServiceAndPropagateItsRefusals() {
        provisioner.delete("AWS::ElasticLoadBalancingV2::LoadBalancer", LB_ARN, REGION);
        provisioner.delete("AWS::ElasticLoadBalancingV2::Listener", LISTENER_ARN, REGION);
        provisioner.delete("AWS::ElasticLoadBalancingV2::ListenerRule", RULE_ARN, REGION);
        verify(elb).deleteLoadBalancer(REGION, LB_ARN);
        verify(elb).deleteListener(REGION, LISTENER_ARN);
        verify(elb).deleteRule(REGION, RULE_ARN);

        doThrow(new AwsException("ResourceInUse", "in use", 400)).when(elb).deleteTargetGroup(REGION, TG_ARN);
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::ElasticLoadBalancingV2::TargetGroup", TG_ARN, REGION));
        assertEquals("ResourceInUse", e.getErrorCode());

        // Only the type's own not-found code is delete-complete.
        doThrow(new AwsException("LoadBalancerNotFound", "gone", 400)).when(elb).deleteLoadBalancer(REGION, "gone-arn");
        provisioner.delete("AWS::ElasticLoadBalancingV2::LoadBalancer", "gone-arn", REGION);

        provisioner.delete("AWS::ElasticLoadBalancingV2::LoadBalancer", null, REGION);
        verify(elb, never()).deleteLoadBalancer(eq(REGION), isNull());
    }

    @Test
    void rejectsAResourceTypeItDoesNotOwn() {
        StackResource r = resource("AWS::ElasticLoadBalancingV2::TrustStore", "Ts");
        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }
}
