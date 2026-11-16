package io.floci.aws.services.elbv2;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupTuple;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ElbV2ForwardConfigIntegrationTest {

    private ElasticLoadBalancingV2Client elb;
    private String loadBalancerArn;
    private String targetGroupArn;

    @BeforeEach
    void createResources() {
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        loadBalancerArn = elb.createLoadBalancer(request -> request.name("forward-" + suffix)
                .type("application").subnets(List.of(
                        Ec2Service.defaultSubnetId("us-east-1", "a"),
                        Ec2Service.defaultSubnetId("us-east-1", "b"))))
                .loadBalancers().getFirst().loadBalancerArn();
        targetGroupArn = elb.createTargetGroup(request -> request.name("forward-" + suffix)
                .protocol("HTTP").port(80).targetType("ip"))
                .targetGroups().getFirst().targetGroupArn();
    }

    @AfterEach
    void removeResources() {
        if (elb == null) {
            return;
        }
        try {
            if (loadBalancerArn != null) {
                elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(loadBalancerArn).build());
            }
        } finally {
            try {
                if (targetGroupArn != null) {
                    elb.deleteTargetGroup(DeleteTargetGroupRequest.builder().targetGroupArn(targetGroupArn).build());
                }
            } finally {
                elb.close();
            }
        }
    }

    @Test
    void shortFormForwardActionHasForwardConfigAcrossRuleAndListenerResponses() throws IOException {
        int port = FreePorts.anyInterfacePort();
        Listener listener = elb.createListener(request -> request.loadBalancerArn(loadBalancerArn)
                .protocol("HTTP").port(port)
                .defaultActions(action -> action.type("forward").targetGroupArn(targetGroupArn)))
                .listeners().getFirst();
        String listenerArn = listener.listenerArn();
        Rule rule = elb.createRule(request -> request.listenerArn(listenerArn).priority(10)
                .conditions(condition -> condition.field("path-pattern").values("/forward/*"))
                .actions(action -> action.type("forward").targetGroupArn(targetGroupArn)))
                .rules().getFirst();
        String ruleArn = rule.ruleArn();

        assertShortForm(elb.describeRules(request -> request.ruleArns(ruleArn))
                .rules().getFirst().actions().getFirst());
        assertShortForm(rule.actions().getFirst());
        assertShortForm(listener.defaultActions().getFirst());
        assertShortForm(elb.describeListeners(request -> request.listenerArns(listenerArn))
                .listeners().getFirst().defaultActions().getFirst());
        assertShortForm(elb.describeRules(request -> request.listenerArn(listenerArn)).rules().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.isDefault()))
                .findFirst().orElseThrow().actions().getFirst());

        assertShortForm(elb.modifyRule(request -> request.ruleArn(ruleArn)
                .actions(action -> action.type("forward").targetGroupArn(targetGroupArn)))
                .rules().getFirst().actions().getFirst());
        assertShortForm(elb.describeRules(request -> request.ruleArns(ruleArn))
                .rules().getFirst().actions().getFirst());
        assertShortForm(elb.modifyListener(request -> request.listenerArn(listenerArn)
                .defaultActions(action -> action.type("forward").targetGroupArn(targetGroupArn)))
                .listeners().getFirst().defaultActions().getFirst());
        assertShortForm(elb.describeListeners(request -> request.listenerArns(listenerArn))
                .listeners().getFirst().defaultActions().getFirst());
    }

    @Test
    void explicitWeightedForwardConfigKeepsItsWeightAndStickiness() throws IOException {
        int port = FreePorts.anyInterfacePort();
        String listenerArn = elb.createListener(request -> request.loadBalancerArn(loadBalancerArn)
                .protocol("HTTP").port(port)
                .defaultActions(action -> action.type("fixed-response")
                        .fixedResponseConfig(response -> response.statusCode("404"))))
                .listeners().getFirst().listenerArn();
        Rule created = elb.createRule(request -> request.listenerArn(listenerArn).priority(10)
                .conditions(condition -> condition.field("path-pattern").values("/weighted/*"))
                .actions(action -> action.type("forward").forwardConfig(forward -> forward
                        .targetGroups(group -> group.targetGroupArn(targetGroupArn).weight(7))
                        .targetGroupStickinessConfig(stickiness -> stickiness.enabled(true).durationSeconds(120)))))
                .rules().getFirst();

        assertWeighted(created.actions().getFirst());
        assertWeighted(elb.describeRules(request -> request.ruleArns(created.ruleArn()))
                .rules().getFirst().actions().getFirst());
    }

    private void assertShortForm(Action action) {
        assertEquals(targetGroupArn, action.targetGroupArn());
        assertNotNull(action.forwardConfig());
        assertEquals(1, action.forwardConfig().targetGroups().size());
        TargetGroupTuple group = action.forwardConfig().targetGroups().getFirst();
        assertEquals(targetGroupArn, group.targetGroupArn());
        assertEquals(1, group.weight());
        assertNotNull(action.forwardConfig().targetGroupStickinessConfig());
        assertEquals(false, action.forwardConfig().targetGroupStickinessConfig().enabled());
    }

    private void assertWeighted(Action action) {
        assertNull(action.targetGroupArn());
        assertNotNull(action.forwardConfig());
        assertEquals(1, action.forwardConfig().targetGroups().size());
        TargetGroupTuple group = action.forwardConfig().targetGroups().getFirst();
        assertEquals(targetGroupArn, group.targetGroupArn());
        assertEquals(7, group.weight());
        assertEquals(true, action.forwardConfig().targetGroupStickinessConfig().enabled());
        assertEquals(120, action.forwardConfig().targetGroupStickinessConfig().durationSeconds());
    }
}
