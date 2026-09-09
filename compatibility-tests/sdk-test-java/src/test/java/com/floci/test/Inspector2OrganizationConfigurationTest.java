package com.floci.test;

import org.jboss.logging.Logger;
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
    private static final Logger LOG = Logger.getLogger(Inspector2OrganizationConfigurationTest.class);
    private static final String MANAGEMENT_ACCOUNT = "test";

    @Test
    void organizationConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Amazon Inspector organization settings in real AWS");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient()) {
            boolean createdOrganization = ensureOrganization(organizations);
            String adminAccount = null;
            String memberAccount = null;
            boolean delegatedAdminEnabled = false;

            try {
                adminAccount = createMemberAccount(organizations, "inspector-admin");
                memberAccount = createMemberAccount(organizations, "inspector-member");
                String delegatedAdminAccount = adminAccount;
                String inspectedMemberAccount = memberAccount;

                try (Inspector2Client management = TestFixtures.inspector2Client(MANAGEMENT_ACCOUNT);
                     Inspector2Client administrator = TestFixtures.inspector2Client(delegatedAdminAccount)) {
                    assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts()).isEmpty();

                    var enabled = management.enableDelegatedAdminAccount(request -> request
                            .delegatedAdminAccountId(delegatedAdminAccount));
                    delegatedAdminEnabled = true;
                    assertThat(enabled.delegatedAdminAccountId()).isEqualTo(delegatedAdminAccount);

                    assertThat(management.listDelegatedAdminAccounts(request -> {}).delegatedAdminAccounts())
                            .singleElement()
                            .satisfies(account -> {
                                assertThat(account.accountId()).isEqualTo(delegatedAdminAccount);
                                assertThat(account.statusAsString()).isEqualTo("ENABLED");
                            });

                    var enableMember = administrator.enable(request -> request
                            .accountIds(inspectedMemberAccount)
                            .resourceTypes(ResourceScanType.EC2));
                    assertThat(enableMember.accounts()).singleElement().satisfies(account -> {
                        assertThat(account.accountId()).isEqualTo(inspectedMemberAccount);
                        assertThat(account.resourceStatus().ec2AsString()).isEqualTo("ENABLING");
                        assertThat(account.resourceStatus().ecrAsString()).isEqualTo("DISABLED");
                    });

                    var firstStatus = administrator.batchGetAccountStatus(
                            request -> request.accountIds(inspectedMemberAccount));
                    assertThat(firstStatus.accounts()).singleElement().satisfies(account -> {
                        assertThat(account.accountId()).isEqualTo(inspectedMemberAccount);
                        assertThat(account.state().statusAsString()).isEqualTo("ENABLING");
                        assertThat(account.resourceState().ec2().statusAsString()).isEqualTo("ENABLING");
                        assertThat(account.resourceState().ecr().statusAsString()).isEqualTo("DISABLED");
                    });
                    var converged = administrator.batchGetAccountStatus(
                            request -> request.accountIds(inspectedMemberAccount));
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
                            .delegatedAdminAccountId(delegatedAdminAccount));
                    delegatedAdminEnabled = false;
                    assertThat(disabled.delegatedAdminAccountId()).isEqualTo(delegatedAdminAccount);
                }
            } finally {
                if (delegatedAdminEnabled) {
                    disableDelegatedAdminBestEffort(adminAccount);
                }
                removeAccountBestEffort(organizations, memberAccount);
                removeAccountBestEffort(organizations, adminAccount);
                if (createdOrganization) {
                    deleteOrganizationBestEffort(organizations);
                }
            }
        }
    }

    private static boolean ensureOrganization(OrganizationsClient organizations) {
        try {
            organizations.createOrganization(request -> request.featureSet("ALL"));
            return true;
        } catch (AlreadyInOrganizationException e) {
            LOG.debugf(e, "Default compatibility account already belongs to an AWS organization");
            return false;
        }
    }

    private static void disableDelegatedAdminBestEffort(String accountId) {
        if (accountId == null) {
            return;
        }
        try (Inspector2Client management = TestFixtures.inspector2Client(MANAGEMENT_ACCOUNT)) {
            management.disableDelegatedAdminAccount(request -> request.delegatedAdminAccountId(accountId));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inspector2 compatibility cleanup could not disable delegated administrator %s", accountId);
        }
    }

    private static void removeAccountBestEffort(OrganizationsClient organizations, String accountId) {
        if (accountId == null) {
            return;
        }
        try {
            organizations.removeAccountFromOrganization(request -> request.accountId(accountId));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inspector2 compatibility cleanup could not remove account %s", accountId);
        }
    }

    private static void deleteOrganizationBestEffort(OrganizationsClient organizations) {
        try {
            organizations.deleteOrganization();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inspector2 compatibility cleanup could not delete the temporary organization");
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
