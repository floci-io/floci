package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ListenerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class ElbV2ListenerLookupIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedListenerIsNotAnEmptyOrPartialSuccess(boolean deleteBalancer) {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String first = null;
            String second = null;
            try {
                first = createBalancer(client, "sdk-listener-a");
                second = createBalancer(client, "sdk-listener-b");
                String firstArn = first;
                String secondArn = second;
                assertThat(client.describeListeners(r -> r.loadBalancerArn(firstArn)).listeners(), empty());
                String removed = createListener(client, first);
                String retained = createListener(client, second);
                assertThat(client.describeListeners(r -> r.listenerArns(removed, retained)).listeners().stream()
                        .map(listener -> listener.listenerArn()).toList(), containsInAnyOrder(removed, retained));

                if (deleteBalancer) {
                    client.deleteLoadBalancer(r -> r.loadBalancerArn(firstArn));
                } else {
                    client.deleteListener(r -> r.listenerArn(removed));
                }
                for (List<String> requested : List.of(List.of(removed),
                        List.of(retained, removed), List.of(removed, retained))) {
                    ListenerNotFoundException error = assertThrows(ListenerNotFoundException.class,
                            () -> client.describeListeners(r -> r.listenerArns(requested)));
                    assertEquals(400, error.statusCode());
                    assertEquals("ListenerNotFound", error.awsErrorDetails().errorCode());
                    assertNotNull(error.requestId());
                    assertFalse(error.requestId().isBlank());
                }
                if (deleteBalancer) {
                    LoadBalancerNotFoundException error = assertThrows(LoadBalancerNotFoundException.class,
                            () -> client.describeListeners(r -> r.loadBalancerArn(firstArn)));
                    assertEquals(400, error.statusCode());
                    assertEquals("LoadBalancerNotFound", error.awsErrorDetails().errorCode());
                } else {
                    assertThat(client.describeListeners(r -> r.loadBalancerArn(firstArn)).listeners(), empty());
                }
                List<String> retainedArns = client.describeListeners(r -> r.loadBalancerArn(secondArn)).listeners()
                        .stream().map(listener -> listener.listenerArn()).toList();
                assertEquals(List.of(retained), retainedArns);
            } finally {
                String ownedFirst = first;
                String ownedSecond = second;
                assertAll("Remove both owned balancers and their listeners",
                        () -> {
                            if (ownedFirst != null) {
                                client.deleteLoadBalancer(r -> r.loadBalancerArn(ownedFirst));
                            }
                        },
                        () -> {
                            if (ownedSecond != null) {
                                client.deleteLoadBalancer(r -> r.loadBalancerArn(ownedSecond));
                            }
                        });
            }
        }
    }

    private static String createBalancer(ElasticLoadBalancingV2Client client, String prefix) {
        return client.createLoadBalancer(r -> r.name(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .type("application").subnets(List.of("subnet-default-us-east-1-a", "subnet-default-us-east-1-b")))
                .loadBalancers().get(0).loadBalancerArn();
    }

    private static String createListener(ElasticLoadBalancingV2Client client, String balancer) {
        return client.createListener(r -> r.loadBalancerArn(balancer).port(8080).protocol("HTTP")
                .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                        f.statusCode("200").contentType("text/plain").messageBody("listener"))))
                .listeners().get(0).listenerArn();
    }
}
