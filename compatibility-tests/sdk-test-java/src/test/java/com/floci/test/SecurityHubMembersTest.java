package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.AlreadyInOrganizationException;
import software.amazon.awssdk.services.organizations.model.CreateAccountResponse;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.AdminAccount;
import software.amazon.awssdk.services.securityhub.model.CreateMembersResponse;
import software.amazon.awssdk.services.securityhub.model.DeleteMembersResponse;
import software.amazon.awssdk.services.securityhub.model.GetMembersResponse;
import software.amazon.awssdk.services.securityhub.model.Member;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Security Hub member accounts")
@ResourceLock("organizations")
class SecurityHubMembersTest {
    private static final Logger LOG = Logger.getLogger(SecurityHubMembersTest.class);
    private static final String MANAGEMENT_ACCOUNT = "test";

    @Test
    void delegatedAdministratorManagesOrganizationMembersUsingAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Security Hub organization settings in real AWS");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient()) {
            boolean createdOrganization = ensureOrganization(organizations);
            String adminAccount = createMemberAccount(organizations, "securityhub-members-admin");
            String memberAccount = createMemberAccount(organizations, "securityhub-members-member");

            try (SecurityHubClient management = TestFixtures.securityHubClient(MANAGEMENT_ACCOUNT);
                 SecurityHubClient administrator = TestFixtures.securityHubClient(adminAccount);
                 SecurityHubClient member = TestFixtures.securityHubClient(memberAccount)) {
                // Another test may have left its own administrator designated; an organization has only one.
                for (AdminAccount existing : management.listOrganizationAdminAccounts(request -> {}).adminAccounts()) {
                    management.disableOrganizationAdminAccount(request -> request.adminAccountId(existing.accountId()));
                }
                management.enableOrganizationAdminAccount(request -> request.adminAccountId(adminAccount));

                CreateMembersResponse created = administrator.createMembers(request -> request
                    .accountDetails(details -> details.accountId(memberAccount)));
                assertThat(created.unprocessedAccounts()).isEmpty();
                assertThat(member.describeHub(request -> {}).hubArn()).contains(memberAccount);

                assertThat(administrator.listMembers(request -> {}).members())
                    .singleElement()
                    .satisfies(listed -> {
                        assertThat(listed.accountId()).isEqualTo(memberAccount);
                        assertThat(listed.administratorId()).isEqualTo(adminAccount);
                        assertThat(listed.memberStatus()).isEqualTo("Enabled");
                        assertThat(listed.invitedAt()).isNotNull();
                        assertThat(listed.updatedAt()).isNotNull();
                    });

                GetMembersResponse fetched = administrator.getMembers(request -> request.accountIds(memberAccount));
                assertThat(fetched.members()).extracting(Member::accountId).containsExactly(memberAccount);
                assertThat(fetched.unprocessedAccounts()).isEmpty();

                administrator.disassociateMembers(request -> request.accountIds(memberAccount));
                DeleteMembersResponse deleted = administrator.deleteMembers(request -> request.accountIds(memberAccount));
                assertThat(deleted.unprocessedAccounts())
                    .singleElement()
                    .satisfies(result -> assertThat(result.accountId()).isEqualTo(memberAccount));
                assertThat(administrator.listMembers(request -> {}).members()).isEmpty();
                List<Member> all = administrator.listMembers(request -> request.onlyAssociated(false)).members();
                assertThat(all).extracting(Member::memberStatus).containsExactly("Removed");

                management.disableOrganizationAdminAccount(request -> request.adminAccountId(adminAccount));
                assertThat(management.listOrganizationAdminAccounts(request -> {}).adminAccounts()).isEmpty();
            } finally {
                cleanup("remove member account", () ->
                        organizations.removeAccountFromOrganization(request -> request.accountId(memberAccount)));
                cleanup("remove administrator account", () ->
                        organizations.removeAccountFromOrganization(request -> request.accountId(adminAccount)));
                if (createdOrganization) {
                    cleanup("delete test organization", organizations::deleteOrganization);
                }
            }
        }
    }

    private static void cleanup(String action, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Security Hub members compatibility cleanup failed: %s", action);
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

    private static String createMemberAccount(OrganizationsClient organizations, String prefix) {
        String suffix = TestFixtures.uniqueName(prefix);
        CreateAccountResponse response = organizations.createAccount(request -> request
                .accountName(suffix)
                .email(suffix + "@example.com"));
        return response.createAccountStatus().accountId();
    }
}
