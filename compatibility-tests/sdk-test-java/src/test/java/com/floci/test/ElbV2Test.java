package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.AddTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateRuleRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateRuleResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteRuleRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeAccountLimitsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeAccountLimitsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancerAttributesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancerAttributesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeRulesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeSslPoliciesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeSslPoliciesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupAttributesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupAttributesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateListenerException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateLoadBalancerNameException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateTargetGroupNameException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.IpAddressType;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerAttribute;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ModifyLoadBalancerAttributesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ModifyTargetGroupAttributesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.OperationNotPermittedException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.PriorityInUseException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RemoveTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleCondition;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RulePriorityPair;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.SetRulePrioritiesRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupAttribute;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ELB v2 (Application Load Balancer)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2Test {

    private static ElasticLoadBalancingV2Client elb;

    private static String lbArn;
    private static String lbDnsName;
    private static String tgArn;
    private static String listenerArn;
    private static String ruleArn;

    private static final String LB_NAME  = TestFixtures.uniqueName("sdk-lb");
    private static final String TG_NAME  = TestFixtures.uniqueName("sdk-tg");

    @BeforeAll
    static void setup() {
        elb = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (elb == null) {
            return;
        }
        try {
            if (ruleArn != null) {
                elb.deleteRule(DeleteRuleRequest.builder().ruleArn(ruleArn).build());
            }
        } catch (Exception ignored) {}
        try {
            if (listenerArn != null) {
                elb.deleteListener(DeleteListenerRequest.builder().listenerArn(listenerArn).build());
            }
        } catch (Exception ignored) {}
        try {
            if (lbArn != null) {
                elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(lbArn).build());
            }
        } catch (Exception ignored) {}
        try {
            if (tgArn != null) {
                elb.deleteTargetGroup(DeleteTargetGroupRequest.builder().targetGroupArn(tgArn).build());
            }
        } catch (Exception ignored) {}
        elb.close();
    }

    // ─── Load Balancers ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("CreateLoadBalancer - returns ARN and DNS name")
    void createLoadBalancer() {
        CreateLoadBalancerResponse resp = elb.createLoadBalancer(CreateLoadBalancerRequest.builder()
                .name(LB_NAME)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .scheme(LoadBalancerSchemeEnum.INTERNET_FACING)
                .ipAddressType(IpAddressType.IPV4)
                .build());

        assertThat(resp.loadBalancers()).hasSize(1);
        LoadBalancer lb = resp.loadBalancers().get(0);
        assertThat(lb.loadBalancerName()).isEqualTo(LB_NAME);
        assertThat(lb.loadBalancerArn()).contains("elasticloadbalancing");
        assertThat(lb.dnsName()).endsWith(expectedElbDnsSuffix());
        assertThat(lb.type()).isEqualTo(LoadBalancerTypeEnum.APPLICATION);
        assertThat(lb.scheme()).isEqualTo(LoadBalancerSchemeEnum.INTERNET_FACING);
        assertThat(lb.state().code()).isEqualTo(LoadBalancerStateEnum.PROVISIONING);
        lbArn = lb.loadBalancerArn();
        lbDnsName = lb.dnsName();
    }

    private static String expectedElbDnsSuffix() {
        String hostname = System.getenv("FLOCI_HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = TestFixtures.endpoint().getHost();
        }
        if (hostname == null || hostname.isBlank()
                || "localhost".equals(hostname) || "127.0.0.1".equals(hostname)) {
            hostname = "localhost.floci.io";
        }
        return ".elb." + hostname;
    }

    @Test
    @Order(2)
    @DisplayName("DescribeLoadBalancers - by ARN finds the LB in active state")
    void describeLoadBalancerByArn() {
        DescribeLoadBalancersResponse resp = elb.describeLoadBalancers(
                DescribeLoadBalancersRequest.builder().loadBalancerArns(lbArn).build());

        assertThat(resp.loadBalancers()).hasSize(1);
        LoadBalancer lb = resp.loadBalancers().get(0);
        assertThat(lb.loadBalancerArn()).isEqualTo(lbArn);
        assertThat(lb.loadBalancerName()).isEqualTo(LB_NAME);
        assertThat(lb.dnsName()).isEqualTo(lbDnsName);
        assertThat(lb.state().code()).isEqualTo(LoadBalancerStateEnum.ACTIVE);
    }

    @Test
    @Order(3)
    @DisplayName("DescribeLoadBalancers - by name finds the LB")
    void describeLoadBalancerByName() {
        DescribeLoadBalancersResponse resp = elb.describeLoadBalancers(
                DescribeLoadBalancersRequest.builder().names(LB_NAME).build());

        assertThat(resp.loadBalancers()).hasSize(1);
        assertThat(resp.loadBalancers().get(0).loadBalancerArn()).isEqualTo(lbArn);
        assertThat(resp.loadBalancers().get(0).dnsName()).isEqualTo(lbDnsName);
    }

    @Test
    @Order(4)
    @DisplayName("CreateLoadBalancer - duplicate name throws DuplicateLoadBalancerNameException")
    void createLoadBalancerDuplicateName() {
        assertThatThrownBy(() -> elb.createLoadBalancer(CreateLoadBalancerRequest.builder()
                .name(LB_NAME)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .build()))
                .isInstanceOf(DuplicateLoadBalancerNameException.class);
    }

    @Test
    @Order(5)
    @DisplayName("ModifyLoadBalancerAttributes - set deletion_protection.enabled")
    void modifyLoadBalancerAttributes() {
        elb.modifyLoadBalancerAttributes(ModifyLoadBalancerAttributesRequest.builder()
                .loadBalancerArn(lbArn)
                .attributes(LoadBalancerAttribute.builder()
                        .key("deletion_protection.enabled")
                        .value("true")
                        .build())
                .build());

        DescribeLoadBalancerAttributesResponse resp = elb.describeLoadBalancerAttributes(
                DescribeLoadBalancerAttributesRequest.builder().loadBalancerArn(lbArn).build());

        boolean found = resp.attributes().stream()
                .anyMatch(a -> "deletion_protection.enabled".equals(a.key()) && "true".equals(a.value()));
        assertThat(found).isTrue();
    }

    // ─── Target Groups ───────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("CreateTargetGroup - returns ARN with health-check defaults")
    void createTargetGroup() {
        CreateTargetGroupResponse resp = elb.createTargetGroup(CreateTargetGroupRequest.builder()
                .name(TG_NAME)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .targetType(TargetTypeEnum.INSTANCE)
                .build());

        assertThat(resp.targetGroups()).hasSize(1);
        TargetGroup tg = resp.targetGroups().get(0);
        assertThat(tg.targetGroupName()).isEqualTo(TG_NAME);
        assertThat(tg.targetGroupArn()).contains("targetgroup");
        assertThat(tg.protocol()).isEqualTo(ProtocolEnum.HTTP);
        assertThat(tg.port()).isEqualTo(80);
        assertThat(tg.healthCheckEnabled()).isTrue();
        assertThat(tg.healthCheckPath()).isEqualTo("/");
        tgArn = tg.targetGroupArn();
    }

    @Test
    @Order(11)
    @DisplayName("DescribeTargetGroups - by ARN returns correct group")
    void describeTargetGroupByArn() {
        DescribeTargetGroupsResponse resp = elb.describeTargetGroups(
                DescribeTargetGroupsRequest.builder().targetGroupArns(tgArn).build());

        assertThat(resp.targetGroups()).hasSize(1);
        assertThat(resp.targetGroups().get(0).targetGroupName()).isEqualTo(TG_NAME);
    }

    @Test
    @Order(12)
    @DisplayName("CreateTargetGroup - duplicate name throws DuplicateTargetGroupNameException")
    void createTargetGroupDuplicateName() {
        assertThatThrownBy(() -> elb.createTargetGroup(CreateTargetGroupRequest.builder()
                .name(TG_NAME)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .build()))
                .isInstanceOf(DuplicateTargetGroupNameException.class);
    }

    @Test
    @Order(13)
    @DisplayName("ModifyTargetGroupAttributes - set deregistration_delay")
    void modifyTargetGroupAttributes() {
        elb.modifyTargetGroupAttributes(ModifyTargetGroupAttributesRequest.builder()
                .targetGroupArn(tgArn)
                .attributes(TargetGroupAttribute.builder()
                        .key("deregistration_delay.timeout_seconds")
                        .value("60")
                        .build())
                .build());

        DescribeTargetGroupAttributesResponse resp = elb.describeTargetGroupAttributes(
                DescribeTargetGroupAttributesRequest.builder().targetGroupArn(tgArn).build());

        boolean found = resp.attributes().stream()
                .anyMatch(a -> "deregistration_delay.timeout_seconds".equals(a.key()) && "60".equals(a.value()));
        assertThat(found).isTrue();
    }

    // ─── Targets ─────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("RegisterTargets - registers two instance IDs")
    void registerTargets() {
        elb.registerTargets(RegisterTargetsRequest.builder()
                .targetGroupArn(tgArn)
                .targets(
                        TargetDescription.builder().id("i-00000000001").port(8080).build(),
                        TargetDescription.builder().id("i-00000000002").port(8080).build()
                )
                .build());
    }

    @Test
    @Order(15)
    @DisplayName("DescribeTargetHealth - returns initial state for registered targets")
    void describeTargetHealth() {
        DescribeTargetHealthResponse resp = elb.describeTargetHealth(
                DescribeTargetHealthRequest.builder().targetGroupArn(tgArn).build());

        assertThat(resp.targetHealthDescriptions()).hasSize(2);
        for (TargetHealthDescription thd : resp.targetHealthDescriptions()) {
            assertThat(thd.targetHealth().state()).isEqualTo(TargetHealthStateEnum.INITIAL);
        }
    }

    // ─── Listeners ───────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("CreateListener - creates with default forward action and auto-creates default rule")
    void createListener() {
        CreateListenerResponse resp = elb.createListener(CreateListenerRequest.builder()
                .loadBalancerArn(lbArn)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .defaultActions(Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(tgArn)
                        .build())
                .build());

        assertThat(resp.listeners()).hasSize(1);
        Listener listener = resp.listeners().get(0);
        assertThat(listener.loadBalancerArn()).isEqualTo(lbArn);
        assertThat(listener.port()).isEqualTo(80);
        assertThat(listener.protocol()).isEqualTo(ProtocolEnum.HTTP);
        assertThat(listener.defaultActions()).hasSize(1);
        assertThat(listener.defaultActions().get(0).type()).isEqualTo(ActionTypeEnum.FORWARD);
        listenerArn = listener.listenerArn();
    }

    @Test
    @Order(21)
    @DisplayName("DescribeListeners - by LB ARN returns the created listener")
    void describeListeners() {
        DescribeListenersResponse resp = elb.describeListeners(
                DescribeListenersRequest.builder().loadBalancerArn(lbArn).build());

        assertThat(resp.listeners()).hasSize(1);
        assertThat(resp.listeners().get(0).listenerArn()).isEqualTo(listenerArn);
    }

    @Test
    @Order(22)
    @DisplayName("CreateListener - duplicate port throws DuplicateListenerException")
    void createListenerDuplicatePort() {
        assertThatThrownBy(() -> elb.createListener(CreateListenerRequest.builder()
                .loadBalancerArn(lbArn)
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .defaultActions(Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(tgArn)
                        .build())
                .build()))
                .isInstanceOf(DuplicateListenerException.class);
    }

    // ─── Rules ───────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("DescribeRules - default rule auto-created and is default")
    void describeRulesDefaultExists() {
        DescribeRulesResponse resp = elb.describeRules(
                DescribeRulesRequest.builder().listenerArn(listenerArn).build());

        assertThat(resp.rules()).isNotEmpty();
        boolean hasDefault = resp.rules().stream().anyMatch(Rule::isDefault);
        assertThat(hasDefault).isTrue();

        Rule defaultRule = resp.rules().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .orElseThrow();
        assertThat(defaultRule.priority()).isEqualTo("default");
        assertThat(defaultRule.actions()).isNotEmpty();
    }

    @Test
    @Order(31)
    @DisplayName("CreateRule - path-pattern condition at priority 10")
    void createRulePathPattern() {
        CreateRuleResponse resp = elb.createRule(CreateRuleRequest.builder()
                .listenerArn(listenerArn)
                .priority(10)
                .conditions(RuleCondition.builder()
                        .field("path-pattern")
                        .values("/api/*")
                        .build())
                .actions(Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(tgArn)
                        .build())
                .build());

        assertThat(resp.rules()).hasSize(1);
        Rule rule = resp.rules().get(0);
        assertThat(rule.priority()).isEqualTo("10");
        assertThat(rule.isDefault()).isFalse();
        assertThat(rule.conditions()).hasSize(1);
        assertThat(rule.conditions().get(0).field()).isEqualTo("path-pattern");
        ruleArn = rule.ruleArn();
    }

    @Test
    @Order(32)
    @DisplayName("CreateRule - priority already in use throws PriorityInUseException")
    void createRulePriorityInUse() {
        assertThatThrownBy(() -> elb.createRule(CreateRuleRequest.builder()
                .listenerArn(listenerArn)
                .priority(10)
                .conditions(RuleCondition.builder()
                        .field("path-pattern")
                        .values("/other/*")
                        .build())
                .actions(Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(tgArn)
                        .build())
                .build()))
                .isInstanceOf(PriorityInUseException.class);
    }

    @Test
    @Order(33)
    @DisplayName("DescribeRules - listener has default rule + 1 user rule")
    void describeRulesCount() {
        DescribeRulesResponse resp = elb.describeRules(
                DescribeRulesRequest.builder().listenerArn(listenerArn).build());
        assertThat(resp.rules()).hasSize(2);
    }

    @Test
    @Order(34)
    @DisplayName("SetRulePriorities - changes rule priority")
    void setRulePriorities() {
        elb.setRulePriorities(SetRulePrioritiesRequest.builder()
                .rulePriorities(RulePriorityPair.builder()
                        .ruleArn(ruleArn)
                        .priority(20)
                        .build())
                .build());

        DescribeRulesResponse resp = elb.describeRules(
                DescribeRulesRequest.builder().ruleArns(ruleArn).build());
        assertThat(resp.rules()).hasSize(1);
        assertThat(resp.rules().get(0).priority()).isEqualTo("20");
    }

    @Test
    @Order(35)
    @DisplayName("DeleteRule - cannot delete default rule")
    void deleteDefaultRuleForbidden() {
        DescribeRulesResponse resp = elb.describeRules(
                DescribeRulesRequest.builder().listenerArn(listenerArn).build());

        String defaultRuleArn = resp.rules().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .map(Rule::ruleArn)
                .orElseThrow();

        assertThatThrownBy(() -> elb.deleteRule(
                DeleteRuleRequest.builder().ruleArn(defaultRuleArn).build()))
                .isInstanceOf(OperationNotPermittedException.class);
    }

    // ─── Tags ────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("AddTags / DescribeTags / RemoveTags roundtrip on LB")
    void tagsRoundtrip() {
        elb.addTags(AddTagsRequest.builder()
                .resourceArns(lbArn)
                .tags(
                        Tag.builder().key("env").value("test").build(),
                        Tag.builder().key("team").value("platform").build()
                )
                .build());

        DescribeTagsResponse desc = elb.describeTags(
                DescribeTagsRequest.builder().resourceArns(lbArn).build());
        assertThat(desc.tagDescriptions()).hasSize(1);
        List<Tag> tags = desc.tagDescriptions().get(0).tags();
        assertThat(tags).extracting(Tag::key).contains("env", "team");
        assertThat(tags).extracting(Tag::value).contains("test", "platform");

        elb.removeTags(RemoveTagsRequest.builder()
                .resourceArns(lbArn)
                .tagKeys("env")
                .build());

        DescribeTagsResponse afterRemove = elb.describeTags(
                DescribeTagsRequest.builder().resourceArns(lbArn).build());
        List<Tag> remaining = afterRemove.tagDescriptions().get(0).tags();
        assertThat(remaining).extracting(Tag::key).containsOnly("team");
    }

    // ─── SSL Policies / Account Limits ───────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("DescribeSSLPolicies - returns pre-seeded policies")
    void describeSSLPolicies() {
        DescribeSslPoliciesResponse resp = elb.describeSSLPolicies(
                DescribeSslPoliciesRequest.builder().build());

        assertThat(resp.sslPolicies()).isNotEmpty();
        boolean hasDefault = resp.sslPolicies().stream()
                .anyMatch(p -> p.name().startsWith("ELBSecurityPolicy-"));
        assertThat(hasDefault).isTrue();
        assertThat(resp.nextMarker()).isNull();
    }

    @Test
    @Order(51)
    @DisplayName("DescribeAccountLimits - returns non-empty limits")
    void describeAccountLimits() {
        DescribeAccountLimitsResponse resp = elb.describeAccountLimits(
                DescribeAccountLimitsRequest.builder().build());

        assertThat(resp.limits()).isNotEmpty();
        boolean hasLoadBalancers = resp.limits().stream()
                .anyMatch(l -> "application-load-balancers".equals(l.name()));
        assertThat(hasLoadBalancers).isTrue();
    }

    // ─── Delete cascade ──────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("DeleteTargetGroup - in-use LB throws TargetGroupAssociationLimitException")
    void deleteTargetGroupInUseFails() {
        assertThatThrownBy(() -> elb.deleteTargetGroup(
                DeleteTargetGroupRequest.builder().targetGroupArn(tgArn).build()))
                .isInstanceOf(ElasticLoadBalancingV2Exception.class);
    }

    @Test
    @Order(61)
    @DisplayName("DeleteListener - removes listener and its rules")
    void deleteListener() {
        elb.deleteListener(DeleteListenerRequest.builder().listenerArn(listenerArn).build());
        listenerArn = null;
        ruleArn = null;

        DescribeListenersResponse resp = elb.describeListeners(
                DescribeListenersRequest.builder().loadBalancerArn(lbArn).build());
        assertThat(resp.listeners()).isEmpty();
    }

    @Test
    @Order(62)
    @DisplayName("DeleteLoadBalancer - removes the load balancer")
    void deleteLoadBalancer() {
        elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(lbArn).build());
        lbArn = null;

        DescribeLoadBalancersResponse resp = elb.describeLoadBalancers(
                DescribeLoadBalancersRequest.builder().build());
        boolean found = resp.loadBalancers().stream()
                .anyMatch(lb -> LB_NAME.equals(lb.loadBalancerName()));
        assertThat(found).isFalse();
    }

    @Test
    @Order(63)
    @DisplayName("DeleteTargetGroup - succeeds after LB deleted")
    void deleteTargetGroupAfterLb() {
        elb.deleteTargetGroup(DeleteTargetGroupRequest.builder().targetGroupArn(tgArn).build());
        tgArn = null;

        DescribeTargetGroupsResponse resp = elb.describeTargetGroups(
                DescribeTargetGroupsRequest.builder().build());
        boolean found = resp.targetGroups().stream()
                .anyMatch(tg -> TG_NAME.equals(tg.targetGroupName()));
        assertThat(found).isFalse();
    }
}
