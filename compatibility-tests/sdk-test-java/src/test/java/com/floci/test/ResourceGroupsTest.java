package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CapacityReservation;
import software.amazon.awssdk.services.resourcegroups.ResourceGroupsClient;
import software.amazon.awssdk.services.resourcegroups.model.GroupConfigurationItem;
import software.amazon.awssdk.services.resourcegroups.model.GroupConfigurationParameter;
import software.amazon.awssdk.services.resourcegroups.model.GroupResourcesResponse;
import software.amazon.awssdk.services.resourcegroups.model.ListGroupResourcesResponse;
import software.amazon.awssdk.services.resourcegroups.model.NotFoundException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Resource Groups capacity reservation pools through the AWS SDK")
class ResourceGroupsTest {
    @Test
    @DisplayName("Store verified membership and delete only the group")
    void capacityPoolLifecycle() {
        try (ResourceGroupsClient groups = TestFixtures.resourceGroupsClient();
                Ec2Client ec2 = TestFixtures.ec2Client()) {
            String name = TestFixtures.uniqueName("capacity-pool");
            CapacityReservation reservation = ec2.createCapacityReservation(request -> request
                    .instanceType("t3.micro").instancePlatform("Linux/UNIX")
                    .availabilityZone(TestFixtures.region().id() + "a").instanceCount(1))
                    .capacityReservation();
            try {
                String arn = groups.createGroup(request -> request.name(name).tags(Map.of("purpose", "compat"))
                        .configuration(List.of(
                                GroupConfigurationItem.builder().type("AWS::EC2::CapacityReservationPool").build(),
                                GroupConfigurationItem.builder().type("AWS::ResourceGroups::Generic").parameters(
                                        GroupConfigurationParameter.builder().name("allowed-resource-types")
                                                .values("AWS::EC2::CapacityReservation").build()).build())))
                        .group().groupArn();
                try {
                    assertThat(groups.getGroup(request -> request.groupName(name)).group().groupArn()).isEqualTo(arn);
                    GroupResourcesResponse added = groups.groupResources(request -> request
                            .group(arn).resourceArns(reservation.capacityReservationArn()));
                    assertThat(added.succeeded()).containsExactly(reservation.capacityReservationArn());
                    assertThat(added.failed()).isEmpty();
                    assertThat(added.pending()).isEmpty();
                    ListGroupResourcesResponse listed = groups.listGroupResources(request -> request.group(arn));
                    assertThat(listed.resourceIdentifiers()).singleElement().satisfies(identifier -> {
                        assertThat(identifier.resourceArn()).isEqualTo(reservation.capacityReservationArn());
                        assertThat(identifier.resourceType()).isEqualTo("AWS::EC2::CapacityReservation");
                    });
                    assertThat(listed.resources()).singleElement().satisfies(resource ->
                            assertThat(resource.identifier().resourceArn()).isEqualTo(reservation.capacityReservationArn()));
                } finally {
                    groups.deleteGroup(request -> request.groupName(name));
                }
                assertThatThrownBy(() -> groups.getGroup(request -> request.groupName(name)))
                        .isInstanceOf(NotFoundException.class);
                assertThat(ec2.describeCapacityReservations(request -> request
                        .capacityReservationIds(reservation.capacityReservationId())).capacityReservations()).hasSize(1);
            } finally {
                ec2.cancelCapacityReservation(request -> request.capacityReservationId(reservation.capacityReservationId()));
            }
        }
    }
}
