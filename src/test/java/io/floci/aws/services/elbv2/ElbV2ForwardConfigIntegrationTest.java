package io.floci.aws.services.elbv2;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AlbFixtures;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupTuple;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ElbV2ForwardConfigIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    private ElasticLoadBalancingV2Client elb;
    private String loadBalancerArn;
    private String targetGroupArn;

    @BeforeEach
    void createResources() {
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), ACCOUNT, Region.US_EAST_1);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        loadBalancerArn = AlbFixtures.createLoadBalancer(ACCOUNT, "forward-" + suffix);
        targetGroupArn = AlbFixtures.createTargetGroup(ACCOUNT, "forward-" + suffix, "ip", 80);
    }

    @AfterEach
    void removeResources() {
        if (elb == null) {
            return;
        }
        try {
            if (loadBalancerArn != null) {
                AlbFixtures.deleteLoadBalancer(ACCOUNT, loadBalancerArn);
            }
        } finally {
            try {
                if (targetGroupArn != null) {
                    AlbFixtures.deleteTargetGroup(ACCOUNT, targetGroupArn);
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

    @Test
    void gatewayShortFormForwardOmitsWeightAndStickiness() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String gatewayArn = elb.createLoadBalancer(request -> request.name("forward-gwy-" + suffix)
                .type("gateway").subnets(Ec2Service.defaultSubnetId("us-east-1", "a", "000000000000")))
                .loadBalancers().getFirst().loadBalancerArn();
        String gatewayGroupArn = null;
        try {
            gatewayGroupArn = elb.createTargetGroup(request -> request.name("forward-gwy-" + suffix)
                    .protocol("GENEVE").port(6081).targetType("ip")
                    .vpcId(Ec2Service.defaultVpcId("us-east-1", "000000000000")))
                    .targetGroups().getFirst().targetGroupArn();
            String groupArn = gatewayGroupArn;
            Listener listener = elb.createListener(request -> request.loadBalancerArn(gatewayArn)
                    .defaultActions(action -> action.type("forward").targetGroupArn(groupArn)))
                    .listeners().getFirst();
            assertEquals("GENEVE", listener.protocolAsString());
            assertGatewayShortForm(listener.defaultActions().getFirst(), groupArn);
            assertGatewayShortForm(elb.describeListeners(request -> request.listenerArns(listener.listenerArn()))
                    .listeners().getFirst().defaultActions().getFirst(), groupArn);
            assertGatewayShortForm(elb.describeRules(request -> request.listenerArn(listener.listenerArn()))
                    .rules().getFirst().actions().getFirst(), groupArn);
            assertGatewayShortForm(elb.modifyListener(request -> request.listenerArn(listener.listenerArn())
                    .defaultActions(action -> action.type("forward").targetGroupArn(groupArn)))
                    .listeners().getFirst().defaultActions().getFirst(), groupArn);
        } finally {
            try {
                AlbFixtures.deleteLoadBalancer(ACCOUNT, gatewayArn);
            } finally {
                if (gatewayGroupArn != null) {
                    AlbFixtures.deleteTargetGroup(ACCOUNT, gatewayGroupArn);
                }
            }
        }
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

    private void assertGatewayShortForm(Action action, String groupArn) {
        assertEquals(groupArn, action.targetGroupArn());
        assertNotNull(action.forwardConfig());
        assertEquals(1, action.forwardConfig().targetGroups().size());
        TargetGroupTuple group = action.forwardConfig().targetGroups().getFirst();
        assertEquals(groupArn, group.targetGroupArn());
        assertNull(group.weight());
        assertNull(action.forwardConfig().targetGroupStickinessConfig());
    }
}
