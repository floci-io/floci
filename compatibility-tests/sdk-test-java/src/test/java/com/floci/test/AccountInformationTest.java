package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AccountState;
import software.amazon.awssdk.services.account.model.GetAccountInformationResponse;
import software.amazon.awssdk.services.account.model.ValidationException;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;

import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("AWS Account Management account information")
class AccountInformationTest {
    private static final String STANDALONE_ACCOUNT = "731000830101";
    private static final String MANAGEMENT_ACCOUNT = "731000830102";

    @Test
    @DisplayName("names an account in no organization")
    void putAndGetAccountNameUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Writes emulator account state");

        try (AccountClient account = TestFixtures.accountClient(STANDALONE_ACCOUNT)) {
            account.putAccountName(request -> request.accountName("Dev Sandbox"));

            GetAccountInformationResponse information = account.getAccountInformation(request -> { });
            assertThat(information.accountId()).isEqualTo(STANDALONE_ACCOUNT);
            assertThat(information.accountName()).isEqualTo("Dev Sandbox");
            assertThat(information.accountState()).isEqualTo(AccountState.ACTIVE);
            assertThat(information.accountCreatedDate()).isNull();

            assertThatThrownBy(() -> account.putAccountName(request -> request.accountName("<Dev Sandbox>")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("AccountName");
        }
    }

    @Test
    @DisplayName("renames a member account as Organizations describes it")
    @ResourceLock("organizations")
    void putAccountNameRenamesAMemberAccountUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates an organization and a member account");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient(MANAGEMENT_ACCOUNT);
             AccountClient account = TestFixtures.accountClient(MANAGEMENT_ACCOUNT)) {
            organizations.createOrganization(request -> request.featureSet(OrganizationFeatureSet.ALL));
            String member = null;
            try {
                organizations.enableAWSServiceAccess(request -> request.servicePrincipal("account.amazonaws.com"));
                String created = organizations.createAccount(request -> request
                                .email("account-information@example.com").accountName("account-information"))
                        .createAccountStatus().accountId();
                member = created;

                account.putAccountName(request -> request.accountId(created).accountName("Renamed Member"));

                Account described = organizations.describeAccount(request -> request.accountId(created)).account();
                assertThat(described.name()).isEqualTo("Renamed Member");
                GetAccountInformationResponse information =
                        account.getAccountInformation(request -> request.accountId(created));
                assertThat(information.accountId()).isEqualTo(created);
                assertThat(information.accountName()).isEqualTo("Renamed Member");
                assertThat(information.accountState()).isEqualTo(AccountState.ACTIVE);
                assertThat(information.accountCreatedDate())
                        .isEqualTo(described.joinedTimestamp().truncatedTo(ChronoUnit.SECONDS));
            } finally {
                removeOrganization(organizations, member);
            }
        }
    }

    /** Best effort, so a failed assertion above stays the reported failure. */
    private static void removeOrganization(OrganizationsClient organizations, String member) {
        try {
            if (member != null) {
                organizations.removeAccountFromOrganization(request -> request.accountId(member));
            }
            organizations.deleteOrganization();
        } catch (RuntimeException e) {
            System.err.println("[WARN] Organizations cleanup failed, leaving the organization in place: "
                    + e.getMessage());
        }
    }
}
