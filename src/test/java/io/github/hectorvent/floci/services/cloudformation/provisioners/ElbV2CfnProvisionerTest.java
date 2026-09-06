package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final ElbV2Service elb = mock(ElbV2Service.class);
    private final ElbV2CfnProvisioner provisioner = new ElbV2CfnProvisioner(elb);
    private final ObjectMapper mapper = new ObjectMapper();

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
        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
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
                .thenReturn(loadBalancer("web"));
        ObjectNode props = mapper.createObjectNode().put("Name", "web").put("Scheme", "internet-facing")
                .put("Type", "application");
        props.putArray("Subnets").add("subnet-1").add("subnet-2");
        props.putArray("SecurityGroups").add("sg-1");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        provisioner.provision(r, props, ctx());

        assertEquals(LB_ARN, r.getPhysicalId());
        assertEquals(Set.of("LoadBalancerArn", "DNSName", "CanonicalHostedZoneID", "LoadBalancerName",
                "LoadBalancerFullName"), r.getAttributes().keySet());
        assertEquals("app/web/50dc6c495c0c9188", r.getAttributes().get("LoadBalancerFullName"));
        assertEquals("web", r.getAttributes().get("LoadBalancerName"));
        assertEquals("Z35SXDOTRQ7X7K", r.getAttributes().get("CanonicalHostedZoneID"));
    }

    @Test
    void anUnnamedLoadBalancerGetsAnElbSafeNameAndKeepsItOnUpdate() {
        when(elb.createLoadBalancer(eq(REGION), anyString(), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenAnswer(inv -> loadBalancer(inv.getArgument(1)));
        StackResource created = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb_With_Underscores");
        provisioner.provision(created, mapper.createObjectNode(), ctx());
        String generated = created.getAttributes().get("LoadBalancerName");
        // 32 characters at most, [A-Za-z0-9-] only, so the underscores are stripped and the
        // descriptive part is cut to make room for the 8-character suffix.
        assertTrue(generated.matches("[A-Za-z0-9-]{1,32}"), generated);
        assertTrue(generated.startsWith("my-stack-AlbWithUndersc-"), generated);

        StackResource updated = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb_With_Underscores");
        updated.getAttributes().put("LoadBalancerName", generated);
        provisioner.provision(updated, mapper.createObjectNode(), ctx(LB_ARN));

        verify(elb, org.mockito.Mockito.times(2)).createLoadBalancer(eq(REGION), eq(generated), isNull(), isNull(),
                isNull(), anyList(), anyList(), any());
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

        StackResource r = resource("AWS::ElasticLoadBalancingV2::TargetGroup", "Tg");
        provisioner.provision(r, props, ctx());

        assertEquals(TG_ARN, r.getPhysicalId());
        assertEquals(Set.of("TargetGroupArn", "TargetGroupName", "TargetGroupFullName", "LoadBalancerArns"),
                r.getAttributes().keySet());
        assertEquals("targetgroup/web-tg/73e2d6bc24d8a067", r.getAttributes().get("TargetGroupFullName"));
        assertEquals(LB_ARN, r.getAttributes().get("LoadBalancerArns"));
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
        var conditions = props.putArray("Conditions");
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
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenReturn(renamed);

        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "web-v2"), ctx(LB_ARN));
        assertEquals(renamed.getLoadBalancerArn(), r.getPhysicalId());
        assertEquals(LB_ARN, provisioner.updateCleanupPhysicalId(r));
        assertEquals(new UpdateCleanupResult(true, true, LB_ARN, 0, null), provisioner.completeUpdate(r));
        verify(elb).deleteLoadBalancer(REGION, LB_ARN);

        StackResource retained = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        retained.setUpdateReplacePolicy("Retain");
        retained.getAttributes().put("LoadBalancerName", "web");
        provisioner.provision(retained, mapper.createObjectNode().put("Name", "web-v2"), ctx(LB_ARN));
        assertNull(provisioner.updateCleanupPhysicalId(retained));
        assertEquals(new UpdateCleanupResult(true, true, LB_ARN, 0, null), provisioner.completeUpdate(retained));
        verify(elb, org.mockito.Mockito.times(1)).deleteLoadBalancer(REGION, LB_ARN);
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
        verify(elb, org.mockito.Mockito.times(3)).deleteListener(REGION, LISTENER_ARN);
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

        // Every property is create-only and the update only described the existing balancer, so a
        // rollback is complete with nothing to do; the engine must not report it as unimplemented.
        assertTrue(provisioner.rollbackUpdate(lb));
        assertEquals(LB_ARN, lb.getPhysicalId());
        verify(elb, never()).deleteLoadBalancer(anyString(), anyString());
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
        when(elb.createLoadBalancer(eq(REGION), eq("web-v2"), isNull(), isNull(), isNull(), anyList(), anyList(), any()))
                .thenReturn(renamed);
        doThrow(new AwsException("ResourceInUse", "listeners attached", 400))
                .when(elb).deleteLoadBalancer(REGION, renamed.getLoadBalancerArn());
        StackResource r = resource("AWS::ElasticLoadBalancingV2::LoadBalancer", "Alb");
        r.getAttributes().put("LoadBalancerName", "web");
        r.getAttributes().put("DNSName", "web-123.us-east-1.elb.amazonaws.com");
        provisioner.provision(r, mapper.createObjectNode().put("Name", "web-v2"), ctx(LB_ARN));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals("ResourceInUse", e.getErrorCode());
        assertEquals(LB_ARN, r.getPhysicalId());
        assertEquals("web", r.getAttributes().get("LoadBalancerName"));
        assertEquals("web-123.us-east-1.elb.amazonaws.com", r.getAttributes().get("DNSName"));
        assertFalse(provisioner.hasReplacementUpdate(r), "the record is spent so the prior is never put on a cleanup list");
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

        provisioner.delete("AWS::ElasticLoadBalancingV2::LoadBalancer", null, REGION);
        verify(elb, never()).deleteLoadBalancer(eq(REGION), isNull());
    }

    @Test
    void rejectsAResourceTypeItDoesNotOwn() {
        StackResource r = resource("AWS::ElasticLoadBalancingV2::TrustStore", "Ts");
        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }
}
