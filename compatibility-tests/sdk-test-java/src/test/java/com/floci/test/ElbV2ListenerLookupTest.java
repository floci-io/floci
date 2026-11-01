package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ListenerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ELBv2 listener lookup errors")
class ElbV2ListenerLookupTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Deleting a listener or its balancer makes explicit lookup fail without losing another listener")
    void deletedListenerIsNotAnEmptyOrPartialSuccess(boolean deleteBalancer) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses the emulator's default VPC fixtures");
        try (ElasticLoadBalancingV2Client client = TestFixtures.elbV2Client()) {
            String first = null;
            String second = null;
            try {
                first = createBalancer(client, "sdk-listener-a");
                second = createBalancer(client, "sdk-listener-b");
                String firstArn = first;
                String secondArn = second;
                assertThat(client.describeListeners(r -> r.loadBalancerArn(firstArn)).listeners()).isEmpty();
                String removed = createListener(client, first);
                String retained = createListener(client, second);
                assertThat(client.describeListeners(r -> r.listenerArns(removed, retained)).listeners())
                        .extracting(listener -> listener.listenerArn()).containsExactlyInAnyOrder(removed, retained);

                if (deleteBalancer) {
                    client.deleteLoadBalancer(r -> r.loadBalancerArn(firstArn));
                } else {
                    client.deleteListener(r -> r.listenerArn(removed));
                }
                for (List<String> requested : List.of(List.of(removed),
                        List.of(retained, removed), List.of(removed, retained))) {
                    ListenerNotFoundException error = assertThrows(ListenerNotFoundException.class,
                            () -> client.describeListeners(r -> r.listenerArns(requested)));
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ListenerNotFound");
                    assertThat(error.requestId()).isNotBlank();
                }
                if (deleteBalancer) {
                    LoadBalancerNotFoundException error = assertThrows(LoadBalancerNotFoundException.class,
                            () -> client.describeListeners(r -> r.loadBalancerArn(firstArn)));
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("LoadBalancerNotFound");
                } else {
                    assertThat(client.describeListeners(r -> r.loadBalancerArn(firstArn)).listeners()).isEmpty();
                }
                assertThat(client.describeListeners(r -> r.loadBalancerArn(secondArn)).listeners())
                        .singleElement().satisfies(listener -> assertThat(listener.listenerArn()).isEqualTo(retained));
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
        return client.createLoadBalancer(r -> r.name(TestFixtures.uniqueName(prefix))
                .type("application").subnets(TestFixtures.defaultVpcSubnets()))
                .loadBalancers().get(0).loadBalancerArn();
    }

    private static String createListener(ElasticLoadBalancingV2Client client, String balancer) {
        return client.createListener(r -> r.loadBalancerArn(balancer).port(8080).protocol("HTTP")
                .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                        f.statusCode("200").contentType("text/plain").messageBody("listener"))))
                .listeners().get(0).listenerArn();
    }
}
