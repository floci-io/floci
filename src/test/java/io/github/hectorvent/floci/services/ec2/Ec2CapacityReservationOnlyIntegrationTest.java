package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CapacityReservationSpecification;
import software.amazon.awssdk.services.ec2.model.CapacityReservationTarget;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.FleetLaunchTemplateConfigRequest;
import software.amazon.awssdk.services.ec2.model.FleetLaunchTemplateSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateCapacityReservationSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateInstanceNetworkInterfaceSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2CapacityReservationOnlyIntegrationTest {
    @ParameterizedTest
    @CsvSource({"direct,false", "direct,true", "template,false", "template,true", "fleet,false", "fleet,true"})
    void reservationOnlyLaunchUsesCapacityWithoutFallback(String path, boolean targeted) {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("753190420008", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String capacityId = client.createCapacityReservation(request -> request.instanceType("t3.small")
                    .instancePlatform("Linux/UNIX").availabilityZone("us-east-1a").instanceCount(1)
                    .instanceMatchCriteria(targeted ? "targeted" : "open"))
                    .capacityReservation().capacityReservationId();
            String templateId = null;
            List<String> instances = new ArrayList<>();
            try {
                String subnet = client.describeSubnets().subnets().stream()
                        .filter(value -> "us-east-1a".equals(value.availabilityZone())).findFirst().orElseThrow().subnetId();
                RequestLaunchTemplateData data = RequestLaunchTemplateData.builder().imageId("ami-test")
                        .instanceType("t3.small").networkInterfaces(LaunchTemplateInstanceNetworkInterfaceSpecificationRequest.builder()
                                .deviceIndex(0).subnetId(subnet).build())
                        .capacityReservationSpecification(LaunchTemplateCapacityReservationSpecificationRequest.builder()
                                .capacityReservationPreference("capacity-reservations-only")
                                .capacityReservationTarget(targeted ? CapacityReservationTarget.builder()
                                        .capacityReservationId(capacityId).build() : null).build()).build();
                templateId = client.createLaunchTemplate(request -> request
                        .launchTemplateName("capacity-only-" + UUID.randomUUID()).launchTemplateData(data))
                        .launchTemplate().launchTemplateId();
                String selectedTemplate = templateId;
                RunInstancesRequest request = "direct".equals(path)
                        ? RunInstancesRequest.builder().imageId("ami-test").instanceType("t3.small").subnetId(subnet)
                                .minCount(1).maxCount(1).capacityReservationSpecification(
                                        CapacityReservationSpecification.builder()
                                                .capacityReservationPreference("capacity-reservations-only")
                                                .capacityReservationTarget(targeted ? CapacityReservationTarget.builder()
                                                        .capacityReservationId(capacityId).build() : null).build()).build()
                        : RunInstancesRequest.builder().minCount(1).maxCount(1)
                                .launchTemplate(value -> value.launchTemplateId(selectedTemplate)).build();
                if ("fleet".equals(path)) {
                    instances.addAll(client.createFleet(value -> value.type("instant")
                            .launchTemplateConfigs(FleetLaunchTemplateConfigRequest.builder()
                                    .launchTemplateSpecification(FleetLaunchTemplateSpecificationRequest.builder()
                                            .launchTemplateId(selectedTemplate).build()).build())
                            .targetCapacitySpecification(value2 -> value2.totalTargetCapacity(1)
                                    .defaultTargetCapacityType("on-demand")))
                            .instances().stream().flatMap(value -> value.instanceIds().stream()).toList());
                } else {
                    instances.addAll(client.runInstances(request).instances().stream().map(Instance::instanceId).toList());
                }
                assertEquals(1, instances.size());
                assertEquals(capacityId, client.describeInstances(value -> value.instanceIds(instances))
                        .reservations().getFirst().instances().getFirst().capacityReservationId());
                assertEquals(0, client.describeCapacityReservations(value -> value.capacityReservationIds(capacityId))
                        .capacityReservations().getFirst().availableInstanceCount());
                assertThrows(Ec2Exception.class, () -> {
                    List<Instance> unexpected = client.runInstances(request.toBuilder()
                            .clientToken(UUID.randomUUID().toString()).build()).instances();
                    instances.addAll(unexpected.stream().map(Instance::instanceId).toList());
                });
                assertEquals(1, client.describeInstances().reservations().stream()
                        .flatMap(value -> value.instances().stream())
                        .filter(value -> !"terminated".equals(value.state().nameAsString())).count());
            } finally {
                if (!instances.isEmpty()) {
                    client.terminateInstances(value -> value.instanceIds(instances));
                }
                if (templateId != null) {
                    String ownedTemplate = templateId;
                    client.deleteLaunchTemplate(value -> value.launchTemplateId(ownedTemplate));
                }
                client.cancelCapacityReservation(value -> value.capacityReservationId(capacityId));
                assertTrue(client.describeCapacityReservations(value -> value.capacityReservationIds(capacityId))
                        .capacityReservations().getFirst().stateAsString().equals("cancelled"));
            }
        }
    }
}
