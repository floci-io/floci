package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.resourcegroups.ResourceGroupsService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class AutoScalingCapacityGroupIntegrationTest {
    private static final String ACCOUNT = "753190420019";
    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service ec2;

    @Inject
    ResourceGroupsService groups;

    @Inject
    AutoScalingService service;

    @Inject
    AutoScalingReconciler reconciler;

    @Inject
    ObjectMapper mapper;

    @Test
    void templateGroupMembersAreConsumedInTheirMatchingZones() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            String name = "capacity-group-" + UUID.randomUUID();
            List<CapacityReservation> capacities = new ArrayList<>();
            String groupArn = null;
            LaunchTemplate template = null;
            boolean asgCreated = false;
            try {
                for (String zone : List.of(REGION + "a", REGION + "b")) {
                    capacities.add(ec2.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", zone,
                            null, 1, null, null, null, null, null, "targeted", null, null));
                }
                groupArn = groups.create(REGION, ACCOUNT, mapper.valueToTree(Map.of(
                        "Name", name, "Configuration", List.of(
                                Map.of("Type", "AWS::EC2::CapacityReservationPool"),
                                Map.of("Type", "AWS::ResourceGroups::Generic", "Parameters", List.of(
                                        Map.of("Name", "allowed-resource-types",
                                                "Values", List.of("AWS::EC2::CapacityReservation")))))))).arn();
                groups.add(REGION, ACCOUNT, mapper.valueToTree(Map.of("Group", groupArn, "ResourceArns",
                        capacities.stream().map(CapacityReservation::getCapacityReservationArn).toList())));
                LaunchTemplateData data = new LaunchTemplateData();
                data.setImageId("ami-test");
                data.setInstanceType("t3.micro");
                LaunchTemplateData.CapacityReservationSpecification specification =
                        new LaunchTemplateData.CapacityReservationSpecification();
                LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
                target.setCapacityReservationResourceGroupArn(groupArn);
                specification.setCapacityReservationTarget(target);
                data.setCapacityReservationSpecification(specification);
                template = ec2.createLaunchTemplate(REGION, name, data, List.of());
                AutoScalingGroup asg = service.createAutoScalingGroup(REGION, name, null,
                        template.getLaunchTemplateId(), null, "$Latest", null, 0, 2, 2, 300,
                        List.of(REGION + "a", REGION + "b"), List.of(), List.of(), List.of(), "EC2", 0,
                        List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
                asgCreated = true;
                reconciler.reconcile(asg);
                // The next pass promotes the newly launched mock instances to InService.
                reconciler.reconcile(asg);
                AutoScalingGroup current = service.describeAutoScalingGroups(REGION, List.of(name)).getFirst();
                assertEquals(2, current.getInstances().size());
                assertEquals(Set.of("InService"), current.getInstances().stream()
                        .map(instance -> instance.getLifecycleState()).collect(Collectors.toSet()));
                List<Instance> instances = ec2.describeInstances(REGION, current.getInstances().stream()
                        .map(instance -> instance.getInstanceId()).toList(), Map.of()).stream()
                        .flatMap(reservation -> reservation.getInstances().stream()).toList();
                assertEquals(Set.of(REGION + "a", REGION + "b"), instances.stream()
                        .map(instance -> instance.getPlacement().getAvailabilityZone()).collect(Collectors.toSet()));
                assertEquals(capacities.stream().map(CapacityReservation::getCapacityReservationId)
                        .collect(Collectors.toSet()), instances.stream()
                        .map(ec2::instanceCapacityReservationId).collect(Collectors.toSet()));
                try (Ec2Client client = Ec2Client.builder()
                        .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(ACCOUNT, "test"))).build()) {
                    DescribeInstancesResponse response = client.describeInstances(request -> request.instanceIds(
                            instances.stream().map(Instance::getInstanceId).toList()));
                    assertEquals(2, response.reservations().stream().mapToInt(item -> item.instances().size()).sum());
                    String expectedGroup = groupArn;
                    response.reservations().forEach(reservation -> reservation.instances().forEach(instance -> {
                        assertNull(instance.capacityReservationSpecification().capacityReservationPreferenceAsString());
                        assertEquals(expectedGroup, instance.capacityReservationSpecification()
                                .capacityReservationTarget().capacityReservationResourceGroupArn());
                    }));
                }
            } finally {
                if (asgCreated) {
                    service.deleteAutoScalingGroup(REGION, name, true);
                }
                if (template != null) {
                    ec2.deleteLaunchTemplate(REGION, template.getLaunchTemplateId(), null);
                }
                if (groupArn != null) {
                    groups.delete(REGION, ACCOUNT, mapper.valueToTree(Map.of("Group", groupArn)));
                }
                for (CapacityReservation capacity : capacities) {
                    ec2.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                }
            }
        });
    }
}
