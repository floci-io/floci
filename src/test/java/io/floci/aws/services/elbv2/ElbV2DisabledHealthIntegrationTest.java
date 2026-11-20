package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbV2DisabledHealthIntegrationTest {

    @Test
    void lambdaDefaultsAndDisableUpdateAreVisibleThroughSdk() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String name = "disabled-" + UUID.randomUUID().toString().substring(0, 8);
            TargetGroup group = client.createTargetGroup(r -> r.name(name).targetType("lambda"))
                    .targetGroups().getFirst();
            String arn = group.targetGroupArn();
            try {
                assertFalse(group.healthCheckEnabled());
                client.registerTargets(r -> r.targetGroupArn(arn).targets(t -> t
                        .id("arn:aws:lambda:us-east-1:000000000000:function:disabled-health")));
                assertDisabled(client, arn);
                client.modifyTargetGroup(r -> r.targetGroupArn(arn).healthCheckEnabled(true));
                assertTrue(client.describeTargetGroups(r -> r.targetGroupArns(arn))
                        .targetGroups().getFirst().healthCheckEnabled());
                client.modifyTargetGroup(r -> r.targetGroupArn(arn).healthCheckEnabled(false));
                assertDisabled(client, arn);
            } finally {
                client.deleteTargetGroup(r -> r.targetGroupArn(arn));
            }
        }
    }

    @Test
    void networkDisableRejectsBeforeOtherUpdateFieldsChange() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            String name = "network-" + UUID.randomUUID().toString().substring(0, 8);
            TargetGroup group = client.createTargetGroup(r -> r.name(name).targetType("ip")
                    .protocol("HTTP").port(80).vpcId("vpc-health")).targetGroups().getFirst();
            String arn = group.targetGroupArn();
            try {
                ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyTargetGroup(r -> r.targetGroupArn(arn).healthCheckEnabled(false)
                                .healthCheckPath("/changed").healthCheckIntervalSeconds(60)));
                assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                TargetGroup retained = client.describeTargetGroups(r -> r.targetGroupArns(arn))
                        .targetGroups().getFirst();
                assertTrue(retained.healthCheckEnabled());
                assertEquals(group.healthCheckPath(), retained.healthCheckPath());
                assertEquals(group.healthCheckIntervalSeconds(), retained.healthCheckIntervalSeconds());
            } finally {
                client.deleteTargetGroup(r -> r.targetGroupArn(arn));
            }
        }
    }

    private static void assertDisabled(ElasticLoadBalancingV2Client client, String arn) {
        TargetHealthDescription health = client.describeTargetHealth(r -> r.targetGroupArn(arn))
                .targetHealthDescriptions().getFirst();
        assertEquals("unavailable", health.targetHealth().stateAsString());
        assertEquals("Target.HealthCheckDisabled", health.targetHealth().reasonAsString());
    }
}
