package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbV2HealthTimeoutIntegrationTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 121})
    void invalidTimeoutRejectsCreateAndPreservesUpdatedGroup(int timeout) {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String name = "timeout-rejected-" + Math.abs(timeout);
            ElasticLoadBalancingV2Exception createError = assertThrows(ElasticLoadBalancingV2Exception.class,
                    () -> client.createTargetGroup(b -> b.name(name).targetType("ip").protocol("HTTP")
                            .port(80).healthCheckTimeoutSeconds(timeout)));
            assertEquals("ValidationError", createError.awsErrorDetails().errorCode());
            assertTrue(client.describeTargetGroups().targetGroups().stream()
                    .noneMatch(group -> name.equals(group.targetGroupName())));
            String arn = client.createTargetGroup(b -> b.name(name).targetType("ip").protocol("HTTP")
                    .port(80).healthCheckPath("/original")).targetGroups().getFirst().targetGroupArn();
            try {
                ElasticLoadBalancingV2Exception modifyError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyTargetGroup(b -> b.targetGroupArn(arn)
                                .healthCheckPath("/changed").healthCheckTimeoutSeconds(timeout)));
                assertEquals("ValidationError", modifyError.awsErrorDetails().errorCode());
                TargetGroup group = client.describeTargetGroups(b -> b.targetGroupArns(arn))
                        .targetGroups().getFirst();
                assertEquals("/original", group.healthCheckPath());
                assertEquals(5, group.healthCheckTimeoutSeconds());
            } finally {
                client.deleteTargetGroup(b -> b.targetGroupArn(arn));
            }
        }
    }

    @Test
    void upperBoundValidationPrecedesMissingGroupLookup() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String arn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                    + "targetgroup/timeout-missing/0000000000000000";
            ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                    () -> client.modifyTargetGroup(b -> b.targetGroupArn(arn).healthCheckTimeoutSeconds(121)));
            assertEquals("ValidationError", error.awsErrorDetails().errorCode());
            assertTrue(error.getMessage().contains("less than or equal to 120"));
            for (int timeout : new int[]{2, 120}) {
                ElasticLoadBalancingV2Exception missing = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyTargetGroup(b -> b.targetGroupArn(arn).healthCheckTimeoutSeconds(timeout)));
                assertEquals("TargetGroupNotFound", missing.awsErrorDetails().errorCode());
            }
        }
    }
}
