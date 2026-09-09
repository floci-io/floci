package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.ram.RamClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("RAM organization sharing")
class RamOrganizationSharingTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";

    @Test
    @DisplayName("enables Organizations trusted access and creates the RAM service-linked role")
    void organizationSharingUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing RAM organization sharing in real AWS");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient(MANAGEMENT_ACCOUNT);
             RamClient ram = TestFixtures.ramClient(MANAGEMENT_ACCOUNT);
             IamClient iam = TestFixtures.iamClient(MANAGEMENT_ACCOUNT)) {
            try {
                organizations.describeOrganization();
            } catch (software.amazon.awssdk.services.organizations.model.AwsOrganizationsNotInUseException e) {
                organizations.createOrganization(request -> request.featureSet("ALL"));
            }

            assertThat(ram.enableSharingWithAwsOrganization().returnValue()).isTrue();

            assertThat(organizations.listAWSServiceAccessForOrganization().enabledServicePrincipals())
                    .extracting(principal -> principal.servicePrincipal())
                    .contains("ram.amazonaws.com");

            assertThat(iam.getRole(request -> request.roleName("AWSServiceRoleForResourceAccessManager"))
                    .role().roleName()).isEqualTo("AWSServiceRoleForResourceAccessManager");
        }
    }
}
