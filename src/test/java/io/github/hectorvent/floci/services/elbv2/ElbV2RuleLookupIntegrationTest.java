package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleNotFoundException;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ElbV2RuleLookupIntegrationTest {

    @Test
    void unknownRuleArnIsNotAnEmptyOrPartialSuccess() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String balancer = client.createLoadBalancer(r -> r
                    .name("sdk-rule-" + UUID.randomUUID().toString().substring(0, 8))
                    .type("application").subnets(List.of("subnet-default-us-east-1-a", "subnet-default-us-east-1-b")))
                    .loadBalancers().get(0).loadBalancerArn();
            try {
                String listener = client.createListener(r -> r.loadBalancerArn(balancer).port(8080).protocol("HTTP")
                        .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                f.statusCode("200").contentType("text/plain").messageBody("listener"))))
                        .listeners().get(0).listenerArn();
                String retained = createRule(client, listener, "/a/*", 100);
                String removed = createRule(client, listener, "/b/*", 110);
                client.deleteRule(r -> r.ruleArn(removed));

                assertThat(client.describeRules(r -> r.ruleArns(retained)).rules().stream()
                        .map(rule -> rule.ruleArn()).toList(), contains(retained));
                for (List<String> requested : List.of(List.of(removed), List.of(retained, removed))) {
                    RuleNotFoundException error = assertThrows(RuleNotFoundException.class,
                            () -> client.describeRules(r -> r.ruleArns(requested)));
                    assertEquals(400, error.statusCode());
                    assertEquals("RuleNotFound", error.awsErrorDetails().errorCode());
                    assertNotNull(error.requestId());
                    assertFalse(error.requestId().isBlank());
                }
                assertEquals(2, client.describeRules(r -> r.listenerArn(listener)).rules().size());
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
