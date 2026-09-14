package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2RunInstancesDryRunTest {
    @Test
    @DisplayName("RunInstances dry run returns DryRunOperation without reserving an instance or client token")
    void dryRunHasNoLaunchSideEffects() {
        String marker = UUID.randomUUID().toString();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            RunInstancesRequest request = RunInstancesRequest.builder()
                    .imageId("ami-0abcdef1234567890").instanceType(InstanceType.T3_MICRO)
                    .minCount(1).maxCount(1).clientToken(marker)
                    .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key("DryRunTest").value(marker).build()).build())
                    .build();
            try {
                Ec2Exception error = assertThrows(Ec2Exception.class,
                        () -> ec2.runInstances(request.toBuilder().dryRun(true).build()));
                assertThat(error.statusCode()).isEqualTo(412);
                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("DryRunOperation");
                assertThat(ec2.describeInstances(r -> r.filters(Filter.builder()
                                .name("tag:DryRunTest").values(marker).build())).reservations())
                        .flatExtracting(reservation -> reservation.instances()).isEmpty();

                RunInstancesResponse launched = ec2.runInstances(request);
                assertThat(launched.instances()).hasSize(1);
                assertThat(launched.instances().get(0).instanceId()).isNotBlank();
            } finally {
                ec2.describeInstances(r -> r.filters(Filter.builder().name("tag:DryRunTest").values(marker).build()))
                        .reservations().stream().flatMap(reservation -> reservation.instances().stream())
                        .forEach(instance -> ec2.terminateInstances(r -> r.instanceIds(instance.instanceId())));
            }
        }
    }
}
