package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ELBv2 rule lookup errors")
class ElbV2RuleLookupTest {

    @Test
    @DisplayName("DescribeRules with a deleted rule ARN fails with RuleNotFound instead of returning a partial list")
    void deletedRuleIsNotAnEmptyOrPartialSuccess() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses the emulator's default VPC fixtures");
        try (ElasticLoadBalancingV2Client client = TestFixtures.elbV2Client()) {
            String balancer = client.createLoadBalancer(r -> r.name(TestFixtures.uniqueName("sdk-rule"))
                    .type("application").subnets(TestFixtures.defaultVpcSubnets()))
                    .loadBalancers().get(0).loadBalancerArn();
            try {
                String listener = client.createListener(r -> r.loadBalancerArn(balancer).port(8080).protocol("HTTP")
                        .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                f.statusCode("200").contentType("text/plain").messageBody("listener"))))
                        .listeners().get(0).listenerArn();
                String retained = createRule(client, listener, "/a/*", 100);
                String removed = createRule(client, listener, "/b/*", 110);
                client.deleteRule(r -> r.ruleArn(removed));

                assertThat(client.describeRules(r -> r.ruleArns(retained)).rules())
                        .extracting(rule -> rule.ruleArn()).containsExactly(retained);
                for (List<String> requested : List.of(List.of(removed), List.of(retained, removed))) {
                    RuleNotFoundException error = assertThrows(RuleNotFoundException.class,
                            () -> client.describeRules(r -> r.ruleArns(requested)));
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("RuleNotFound");
                    assertThat(error.requestId()).isNotBlank();
                }
            } finally {
                client.deleteLoadBalancer(r -> r.loadBalancerArn(balancer));
            }
        }
    }

    private static String createRule(ElasticLoadBalancingV2Client client, String listener, String path, int priority) {
        return client.createRule(r -> r.listenerArn(listener).priority(priority)
                .conditions(c -> c.field("path-pattern").values(path))
                .actions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                        f.statusCode("200").contentType("text/plain").messageBody("rule"))))
                .rules().get(0).ruleArn();
    }
}
