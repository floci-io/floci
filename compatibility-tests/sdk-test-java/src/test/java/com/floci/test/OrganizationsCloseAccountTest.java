package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.AccountAlreadyClosedException;
import software.amazon.awssdk.services.organizations.model.AccountState;
import software.amazon.awssdk.services.organizations.model.AccountStatus;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Organizations CloseAccount")
class OrganizationsCloseAccountTest {
    private static final String MANAGEMENT_ACCOUNT = "731000840101";

    @Test
    @DisplayName("closes a member account and refuses its credentials")
    @ResourceLock("organizations")
    void closeAccountUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Closes an account, which AWS cannot reopen through the API");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient(MANAGEMENT_ACCOUNT);
             AccountClient account = TestFixtures.accountClient(MANAGEMENT_ACCOUNT)) {
            organizations.createOrganization(request -> request.featureSet(OrganizationFeatureSet.ALL));
            String member = null;
            try {
                organizations.enableAWSServiceAccess(request -> request.servicePrincipal("account.amazonaws.com"));
                String created = organizations.createAccount(request -> request
                                .email("close-account@example.com").accountName("close-account"))
                        .createAccountStatus().accountId();
                member = created;

                try (StsClient memberSts = TestFixtures.stsClient(created)) {
                    assertThat(memberSts.getCallerIdentity().account()).isEqualTo(created);

                    organizations.closeAccount(request -> request.accountId(created));

                    Account closed = organizations.describeAccount(request -> request.accountId(created)).account();
                    assertThat(closed.state()).isEqualTo(AccountState.CLOSED);
                    assertThat(closed.status()).isEqualTo(AccountStatus.SUSPENDED);
                    assertThat(account.getAccountInformation(request -> request.accountId(created))
                            .accountStateAsString()).isEqualTo("CLOSED");
                    assertThatThrownBy(() -> organizations.closeAccount(request -> request.accountId(created)))
                            .isInstanceOf(AccountAlreadyClosedException.class);
                    assertThatThrownBy(memberSts::getCallerIdentity)
                            .isInstanceOfSatisfying(StsException.class, refused -> {
                                assertThat(refused.statusCode()).isEqualTo(403);
                                assertThat(refused.awsErrorDetails().errorCode()).isEqualTo("InvalidClientTokenId");
                            });
                }
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
