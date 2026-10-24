package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.ec2.model.CapacityReservationTarget;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Subnet;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Ec2TargetedCapacityErrorIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"open", "none"})
    void untargetedInstancesRetainTheirPreference(String preference) {
        try (Ec2Client client = ec2Client("741623400003", Region.US_EAST_1)) {
            String id = client.runInstances(request -> request.imageId("ami-test").instanceType("t3.micro")
                    .minCount(1).maxCount(1).capacityReservationSpecification(specification ->
                            specification.capacityReservationPreference(preference)))
                    .instances().getFirst().instanceId();
            try {
                assertEquals(preference, client.describeInstances(request -> request.instanceIds(id))
                        .reservations().getFirst().instances().getFirst().capacityReservationSpecification()
                        .capacityReservationPreferenceAsString());
            } finally {
                client.terminateInstances(request -> request.instanceIds(id));
            }
        }
    }

    @Test
    void sdkReceivesCapacityErrorAndCanRetryAfterRelease() {
        try (Ec2Client client = ec2Client("741623400002", Region.US_EAST_1)) {
            Subnet subnet = client.describeSubnets().subnets().getFirst();
            String capacityId = client.createCapacityReservation(request -> request.instanceType("t3.micro")
                    .instancePlatform("Linux/UNIX").availabilityZone(subnet.availabilityZone())
                    .instanceMatchCriteria("targeted").instanceCount(1))
                    .capacityReservation().capacityReservationId();
            List<String> instances = new ArrayList<>();
            try {
                RunInstancesRequest request = RunInstancesRequest.builder().imageId("ami-test")
                        .instanceType("t3.micro").minCount(1).maxCount(1)
                        .subnetId(subnet.subnetId())
                        .capacityReservationSpecification(CapacityReservationSpecification.builder()
                                .capacityReservationTarget(CapacityReservationTarget.builder()
                                        .capacityReservationId(capacityId).build()).build()).build();
                instances.add(client.runInstances(request).instances().getFirst().instanceId());
                assertNull(client.describeInstances(describe -> describe.instanceIds(instances)).reservations()
                        .getFirst().instances().getFirst().capacityReservationSpecification()
                        .capacityReservationPreferenceAsString());
                Ec2Exception failure = assertThrows(Ec2Exception.class, () -> client.runInstances(request));
                assertEquals("ReservationCapacityExceeded", failure.awsErrorDetails().errorCode());
                assertEquals(400, failure.statusCode());
                assertEquals(0, client.describeCapacityReservations(describe ->
                        describe.capacityReservationIds(capacityId)).capacityReservations()
                        .getFirst().availableInstanceCount());
                client.terminateInstances(terminate -> terminate.instanceIds(instances));
                instances.add(client.runInstances(request).instances().getFirst().instanceId());
                assertEquals(2, instances.size());
            } finally {
                if (!instances.isEmpty()) {
                    client.terminateInstances(terminate -> terminate.instanceIds(instances));
                }
                client.cancelCapacityReservation(cancel -> cancel.capacityReservationId(capacityId));
            }
        }
    }

    private Ec2Client ec2Client(String account, Region region) {
        return Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account, "test"))).build();
    }
}
