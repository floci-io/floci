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
import software.amazon.awssdk.services.organizations.model.ConstraintViolationException;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * As on AWS, a closed account stays in its organization until its post-closure period ends, so neither can be
 * removed afterwards. Each run therefore uses a management account of its own, which lets it run again against the
 * same Floci.
 */
@DisplayName("Organizations CloseAccount")
class OrganizationsCloseAccountTest {

    @Test
    @DisplayName("closes a member account and refuses its credentials")
    @ResourceLock("organizations")
    void closeAccountUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Closes an account, which AWS cannot reopen through the API");

        String managementAccount = "7310009" + ThreadLocalRandom.current().nextInt(10_000, 100_000);
        try (OrganizationsClient organizations = TestFixtures.organizationsClient(managementAccount);
             AccountClient account = TestFixtures.accountClient(managementAccount)) {
            organizations.createOrganization(request -> request.featureSet(OrganizationFeatureSet.ALL));
            organizations.enableAWSServiceAccess(request -> request.servicePrincipal("account.amazonaws.com"));
            String member = organizations.createAccount(request -> request
                            .email("close-account@example.com").accountName("close-account"))
                    .createAccountStatus().accountId();

            try (StsClient memberSts = TestFixtures.stsClient(member)) {
                assertThat(memberSts.getCallerIdentity().account()).isEqualTo(member);

                organizations.closeAccount(request -> request.accountId(member));

                Account closed = organizations.describeAccount(request -> request.accountId(member)).account();
                assertThat(closed.state()).isEqualTo(AccountState.CLOSED);
                assertThat(closed.status()).isEqualTo(AccountStatus.SUSPENDED);
                assertThat(account.getAccountInformation(request -> request.accountId(member))
                        .accountStateAsString()).isEqualTo("CLOSED");
                assertThatThrownBy(() -> organizations.closeAccount(request -> request.accountId(member)))
                        .isInstanceOf(AccountAlreadyClosedException.class);
                assertThatThrownBy(() -> organizations.removeAccountFromOrganization(request -> request
                        .accountId(member)))
                        .isInstanceOf(ConstraintViolationException.class);
                assertThatThrownBy(memberSts::getCallerIdentity)
                        .isInstanceOfSatisfying(StsException.class, refused -> {
                            assertThat(refused.statusCode()).isEqualTo(403);
                            assertThat(refused.awsErrorDetails().errorCode()).isEqualTo("InvalidClientTokenId");
                        });
            }
        }
    }
}
