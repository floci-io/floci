package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.inspector2.Inspector2Client;
import software.amazon.awssdk.services.inspector2.model.AutoEnable;
import software.amazon.awssdk.services.inspector2.model.ResourceScanType;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.AlreadyInOrganizationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Amazon Inspector organization configuration")
class Inspector2OrganizationConfigurationTest {
    private static final String MANAGEMENT_ACCOUNT = "test";

    @Test
    void organizationConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Amazon Inspector organization settings in real AWS");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient()) {
            ensureOrganization(organizations);
            String adminAccount = createMemberAccount(organizations, "inspector-admin");
            String memberAccount = createMemberAccount(organizations, "inspector-member");

            try (Inspector2Client management = TestFixtures.inspector2Client(MANAGEMENT_ACCOUNT);
                 Inspector2Client administrator = TestFixtures.inspector2Client(adminAccount)) {
                assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts()).isEmpty();

                var enabled = management.enableDelegatedAdminAccount(request -> request
                        .delegatedAdminAccountId(adminAccount));
                assertThat(enabled.delegatedAdminAccountId()).isEqualTo(adminAccount);

                assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts())
                        .singleElement()
                        .satisfies(account -> {
                            assertThat(account.accountId()).isEqualTo(adminAccount);
                            assertThat(account.statusAsString()).isEqualTo("ENABLED");
                        });

                var enableMember = administrator.enable(request -> request
                        .accountIds(memberAccount)
                        .resourceTypes(ResourceScanType.EC2));
                assertThat(enableMember.accounts()).singleElement().satisfies(account -> {
                    assertThat(account.accountId()).isEqualTo(memberAccount);
                    assertThat(account.resourceStatus().ec2AsString()).isEqualTo("ENABLING");
                    assertThat(account.resourceStatus().ecrAsString()).isEqualTo("DISABLED");
                });

                var firstStatus = administrator.batchGetAccountStatus(request -> request.accountIds(memberAccount));
                assertThat(firstStatus.accounts()).singleElement().satisfies(account -> {
                    assertThat(account.accountId()).isEqualTo(memberAccount);
                    assertThat(account.state().statusAsString()).isEqualTo("ENABLING");
                    assertThat(account.resourceState().ec2().statusAsString()).isEqualTo("ENABLING");
                    assertThat(account.resourceState().ecr().statusAsString()).isEqualTo("DISABLED");
                });
                var converged = administrator.batchGetAccountStatus(request -> request.accountIds(memberAccount));
                assertThat(converged.accounts()).singleElement().satisfies(account -> {
                    assertThat(account.state().statusAsString()).isEqualTo("ENABLED");
                    assertThat(account.resourceState().ec2().statusAsString()).isEqualTo("ENABLED");
                    assertThat(account.resourceState().ecr().statusAsString()).isEqualTo("DISABLED");
                });

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
                        .delegatedAdminAccountId(adminAccount));
                assertThat(disabled.delegatedAdminAccountId()).isEqualTo(adminAccount);
            }
        }
    }

    private static void ensureOrganization(OrganizationsClient organizations) {
        try {
            organizations.createOrganization(request -> request.featureSet("ALL"));
        } catch (AlreadyInOrganizationException ignored) {
            // The compatibility suite may already have created the default-account organization.
        }
    }

    private static String createMemberAccount(OrganizationsClient organizations, String prefix) {
        String suffix = TestFixtures.uniqueName(prefix);
        var response = organizations.createAccount(request -> request
                .accountName(suffix)
                .email(suffix + "@example.com"));
        return response.createAccountStatus().accountId();
    }
}
