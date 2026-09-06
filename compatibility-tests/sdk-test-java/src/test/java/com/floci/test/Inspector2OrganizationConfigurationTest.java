package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.inspector2.Inspector2Client;
import software.amazon.awssdk.services.inspector2.model.AutoEnable;
import software.amazon.awssdk.services.inspector2.model.ResourceScanType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Amazon Inspector organization configuration")
class Inspector2OrganizationConfigurationTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";

    @Test
    void organizationConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Amazon Inspector organization settings in real AWS");

        try (Inspector2Client management = TestFixtures.inspector2Client(MANAGEMENT_ACCOUNT);
             Inspector2Client administrator = TestFixtures.inspector2Client(ADMIN_ACCOUNT)) {
            assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts()).isEmpty();

            var enabled = management.enableDelegatedAdminAccount(request -> request
                    .delegatedAdminAccountId(ADMIN_ACCOUNT));
            assertThat(enabled.delegatedAdminAccountId()).isEqualTo(ADMIN_ACCOUNT);

            assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts())
                    .singleElement()
                    .satisfies(account -> {
                        assertThat(account.accountId()).isEqualTo(ADMIN_ACCOUNT);
                        assertThat(account.statusAsString()).isEqualTo("ENABLED");
                    });

            administrator.enable(request -> request
                    .accountIds(ADMIN_ACCOUNT)
                    .resourceTypes(ResourceScanType.EC2, ResourceScanType.ECR, ResourceScanType.CODE_REPOSITORY));

            var status = administrator.batchGetAccountStatus(request -> {});
            assertThat(status.accounts()).singleElement()
                    .satisfies(account -> assertThat(account.accountId()).isEqualTo(ADMIN_ACCOUNT));

            var updated = administrator.updateOrganizationConfiguration(request -> request
                    .autoEnable(AutoEnable.builder()
                            .ec2(true)
                            .ecr(true)
                            .lambda(false)
                            .lambdaCode(false)
                            .codeRepository(true)
                            .build()));
            assertThat(updated.autoEnable().ec2()).isTrue();
            assertThat(updated.autoEnable().ecr()).isTrue();
            assertThat(updated.autoEnable().codeRepository()).isTrue();

            var described = administrator.describeOrganizationConfiguration(request -> {});
            assertThat(described.autoEnable().codeRepository()).isTrue();

            var disabled = management.disableDelegatedAdminAccount(request -> request
                    .delegatedAdminAccountId(ADMIN_ACCOUNT));
            assertThat(disabled.delegatedAdminAccountId()).isEqualTo(ADMIN_ACCOUNT);
        }
    }
}
