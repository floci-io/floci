package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ram.RamClient;
import software.amazon.awssdk.services.ram.model.GetResourceShareAssociationsRequest;
import software.amazon.awssdk.services.ram.model.ResourceShareAssociation;
import software.amazon.awssdk.services.ram.model.ResourceShareAssociationType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("RAM resource share associations")
class RamResourceShareAssociationsTest {
    private static final String ACCOUNT = "777777777779";

    @Test
    @DisplayName("lists resource and principal associations through the AWS SDK")
    void listsAssociationsWithSdkPagination() {
        assumeFalse(TestFixtures.isRealAws(), "Uses synthetic resource ARNs");

        String suffix = UUID.randomUUID().toString();
        String resourceOne = "arn:aws:ec2:us-east-1:" + ACCOUNT + ":transit-gateway/tgw-" + suffix;
        String resourceTwo = "arn:aws:ec2:us-east-1:" + ACCOUNT + ":transit-gateway/tgw-second-" + suffix;
        String principal = "888888888888";
        try (RamClient ram = TestFixtures.ramClient(ACCOUNT)) {
            String shareArn = ram.createResourceShare(request -> request.name("sdk-associations-" + suffix)
                    .resourceArns(resourceOne, resourceTwo).principals(principal))
                    .resourceShare().resourceShareArn();
            try {
                List<ResourceShareAssociation> resources = ram.getResourceShareAssociationsPaginator(
                        GetResourceShareAssociationsRequest.builder()
                                .associationType(ResourceShareAssociationType.RESOURCE)
                                .resourceShareArns(shareArn).maxResults(1).build())
                        .stream().flatMap(page -> page.resourceShareAssociations().stream()).toList();

                assertThat(resources).extracting(ResourceShareAssociation::associatedEntity)
                        .containsExactlyInAnyOrder(resourceOne, resourceTwo);
                assertThat(resources).allSatisfy(association -> {
                    assertThat(association.resourceShareArn()).isEqualTo(shareArn);
                    assertThat(association.statusAsString()).isEqualTo("ASSOCIATED");
                    assertThat(association.creationTime()).isNotNull();
                });

                assertThat(ram.getResourceShareAssociations(request -> request
                        .associationType(ResourceShareAssociationType.PRINCIPAL)
                        .resourceShareArns(shareArn).principal(principal))
                        .resourceShareAssociations())
                        .extracting(ResourceShareAssociation::associatedEntity)
                        .containsExactly(principal);
            } finally {
                ram.deleteResourceShare(request -> request.resourceShareArn(shareArn));
            }
        }
    }
}
