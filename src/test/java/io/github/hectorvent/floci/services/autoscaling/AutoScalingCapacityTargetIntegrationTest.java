package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.autoscaling.model.CapacityReservationTarget;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingCapacityTargetIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({
            "ids, capacity-reservations-first",
            "duplicate-id, capacity-reservations-first",
            "groups, capacity-reservations-first",
            "duplicate-group, capacity-reservations-first",
            "mixed, capacity-reservations-first",
            "ids, capacity-reservations-only",
            "duplicate-id, capacity-reservations-only",
            "groups, capacity-reservations-only",
            "duplicate-group, capacity-reservations-only",
            "mixed, capacity-reservations-only"
    })
    void sdkReceivesAwsTargetErrorsWithoutCreatingOrUpdatingAGroup(String kind, String preference) {
        String name = "capacity-target-" + UUID.randomUUID();
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("454545454545", "test"));
        CapacityReservationTarget target = switch (kind) {
            case "ids" -> CapacityReservationTarget.builder()
                    .capacityReservationIds("cr-0123456789abcdef0", "cr-11111111111111111").build();
            case "duplicate-id" -> CapacityReservationTarget.builder()
                    .capacityReservationIds("cr-0123456789abcdef0", "cr-0123456789abcdef0").build();
            case "groups" -> CapacityReservationTarget.builder().capacityReservationResourceGroupArns(
                    "arn:aws:resource-groups:us-east-1:454545454545:group/one",
                    "arn:aws:resource-groups:us-east-1:454545454545:group/two").build();
            case "duplicate-group" -> CapacityReservationTarget.builder().capacityReservationResourceGroupArns(
                    "arn:aws:resource-groups:us-east-1:454545454545:group/one",
                    "arn:aws:resource-groups:us-east-1:454545454545:group/one").build();
            default -> CapacityReservationTarget.builder().capacityReservationIds("cr-0123456789abcdef0")
                    .capacityReservationResourceGroupArns(
                            "arn:aws:resource-groups:us-east-1:454545454545:group/one").build();
        };
        CapacityReservationSpecification specification = CapacityReservationSpecification.builder()
                .capacityReservationPreference(preference).capacityReservationTarget(target).build();
        // AWS returns "Resoure" verbatim; callers may classify the service error by this message.
        String expected = "mixed".equals(kind)
                ? "You can't specify CapacityReservationIds and CapacityReservationResourceGroupArns in the same request. "
                        + "Remove either CapacityReservationIds or CapacityReservationResourceGroupArns."
                : "Specifying more than one target CapacityReservationIds or CapacityReservationResoureGroupArns "
                        + "is not supported.";
        try (AutoScalingClient client = AutoScalingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
             Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint)
                     .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            ec2.createLaunchTemplate(r -> r.launchTemplateName(name)
                    .launchTemplateData(t -> t.imageId("ami-amazonlinux2023").instanceType("t3.micro")));
            try {
                AutoScalingException create = assertThrows(AutoScalingException.class,
                        () -> client.createAutoScalingGroup(r -> r.autoScalingGroupName(name + "-invalid")
                                .launchTemplate(t -> t.launchTemplateName(name)).availabilityZones("us-east-1a")
                                .minSize(0).maxSize(1).desiredCapacity(0)
                                .capacityReservationSpecification(specification)));
                assertError(expected, create);
                assertTrue(client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name + "-invalid"))
                        .autoScalingGroups().isEmpty());
                client.createAutoScalingGroup(r -> r.autoScalingGroupName(name)
                        .launchTemplate(t -> t.launchTemplateName(name)).availabilityZones("us-east-1a")
                        .minSize(0).maxSize(1).desiredCapacity(0));
                AutoScalingGroup before = client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name))
                        .autoScalingGroups().getFirst();
                AutoScalingException update = assertThrows(AutoScalingException.class,
                        () -> client.updateAutoScalingGroup(r -> r.autoScalingGroupName(name).maxSize(2)
                                .capacityReservationSpecification(specification)));
                assertError(expected, update);
                AutoScalingGroup after = client.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name))
                        .autoScalingGroups().getFirst();
                assertEquals(before.maxSize(), after.maxSize());
                assertEquals(before.capacityReservationSpecification(), after.capacityReservationSpecification());
                assertEquals(0, after.desiredCapacity());
                assertTrue(after.instances().isEmpty());
            } finally {
                for (AutoScalingGroup group : client.describeAutoScalingGroups(
                        r -> r.autoScalingGroupNames(name, name + "-invalid")).autoScalingGroups()) {
                    client.deleteAutoScalingGroup(r -> r.autoScalingGroupName(group.autoScalingGroupName()));
                }
                ec2.deleteLaunchTemplate(r -> r.launchTemplateName(name));
            }
        }
    }

    private static void assertError(String expected, AutoScalingException error) {
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertEquals(expected, error.awsErrorDetails().errorMessage());
    }
}
