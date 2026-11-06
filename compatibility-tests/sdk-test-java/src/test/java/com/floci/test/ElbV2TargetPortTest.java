package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ELB v2 target port validation")
class ElbV2TargetPortTest {
    private static ElasticLoadBalancingV2Client client;

    @BeforeAll
    static void setup() {
        client = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536})
    @DisplayName("Out-of-range target-group ports return a modeled validation error")
    void invalidGroupPort(int port) {
        ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> {
                    String unexpectedArn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("port-invalid"))
                            .protocol("HTTP").port(port).targetType("ip").vpcId("vpc-test"))
                            .targetGroups().get(0).targetGroupArn();
                    client.deleteTargetGroup(r -> r.targetGroupArn(unexpectedArn));
                });
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
        assertThat(error.statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"register,0", "register,65536", "deregister,0", "deregister,65536", "health,0", "health,65536"})
    @DisplayName("Every target operation validates port ranges before resolving the group")
    void invalidTargetPort(String operation, int port) {
        List<TargetDescription> targets = List.of(TargetDescription.builder().id("10.0.0.5").port(port).build());
        ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> targetOperation(operation, "missing-group", targets));
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
        assertThat(error.statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    @DisplayName("Boundary ports register and deregister through omitted-port normalization")
    void boundaryPort(int port) {
        String arn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("port-valid"))
                .protocol("HTTP").port(port).targetType("ip").vpcId("vpc-test"))
                .targetGroups().get(0).targetGroupArn();
        try {
            client.registerTargets(r -> r.targetGroupArn(arn).targets(
                    TargetDescription.builder().id("10.0.0.5").build(),
                    TargetDescription.builder().id("10.0.0.5").port(port).build()));
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions())
                    .singleElement().satisfies(t -> assertThat(t.target().port()).isEqualTo(port));
            client.deregisterTargets(r -> r.targetGroupArn(arn)
                    .targets(TargetDescription.builder().id("10.0.0.5").build()));
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions()).isEmpty();
        } finally {
            client.deleteTargetGroup(r -> r.targetGroupArn(arn));
        }
    }

    @ParameterizedTest
    @CsvSource({"register,1", "register,65535", "register,65536", "deregister,1", "deregister,65535",
            "deregister,65536", "health,1", "health,65535", "health,65536"})
    @DisplayName("Lambda target ports are rejected after numeric range validation")
    void lambdaTargetPort(String operation, int port) {
        String arn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("lambda-port"))
                .targetType("lambda")).targetGroups().get(0).targetGroupArn();
        try {
            List<TargetDescription> targets = List.of(TargetDescription.builder()
                    .id("arn:aws:lambda:us-east-1:000000000000:function:absent").port(port).build());
            ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                    () -> targetOperation(operation, arn, targets));
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
            assertThat(error.statusCode()).isEqualTo(400);
            if (port > 65535) {
                assertThat(error.awsErrorDetails().errorMessage()).contains("targets.1.member.port");
            } else {
                assertThat(error.awsErrorDetails().errorMessage())
                        .isEqualTo("Port cannot be specified for Lambda function targets");
            }
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions()).isEmpty();
        } finally {
            client.deleteTargetGroup(r -> r.targetGroupArn(arn));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    @DisplayName("Lambda target round trips omit a supplied group port")
    void lambdaTargetDoesNotInheritGroupPort(int port) {
        String arn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("lambda-group-port"))
                .targetType("lambda").port(port)).targetGroups().get(0).targetGroupArn();
        try {
            client.registerTargets(r -> r.targetGroupArn(arn).targets(TargetDescription.builder()
                    .id("arn:aws:lambda:us-east-1:000000000000:function:absent").build()));
            List<TargetDescription> described = client.describeTargetHealth(r -> r.targetGroupArn(arn))
                    .targetHealthDescriptions().stream().map(t -> t.target()).toList();
            assertThat(described).singleElement().satisfies(t -> assertThat(t.port()).isNull());
            client.deregisterTargets(r -> r.targetGroupArn(arn).targets(described));
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions()).isEmpty();
        } finally {
            client.deleteTargetGroup(r -> r.targetGroupArn(arn));
        }
    }

    private void targetOperation(String operation, String arn, List<TargetDescription> targets) {
        Runnable request = switch (operation) {
            case "register" -> () -> client.registerTargets(r -> r.targetGroupArn(arn).targets(targets));
            case "deregister" -> () -> client.deregisterTargets(r -> r.targetGroupArn(arn).targets(targets));
            case "health" -> () -> client.describeTargetHealth(r -> r.targetGroupArn(arn).targets(targets));
            default -> throw new IllegalArgumentException(operation);
        };
        request.run();
    }

}
