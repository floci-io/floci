package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.macie2.Macie2Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Macie organization administration")
class MacieOrganizationAdministrationTest {

    @Test
    @DisplayName("uses AWS SDK models for delegated administration and organization configuration")
    void organizationAdministrationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Macie organization settings in real AWS");

        try (Macie2Client macie = TestFixtures.macie2Client()) {
            assertThat(macie.listOrganizationAdminAccounts(request -> {}).adminAccounts()).isEmpty();

            macie.enableOrganizationAdminAccount(request -> request.adminAccountId("111111111111"));

            assertThat(macie.listOrganizationAdminAccounts(request -> {}).adminAccounts())
                    .anySatisfy(account -> {
                        assertThat(account.accountId()).isEqualTo("111111111111");
                        assertThat(account.statusAsString()).isEqualTo("ENABLED");
                    });

            macie.enableMacie(request -> {});
            macie.updateOrganizationConfiguration(request -> request.autoEnable(true));

            assertThat(macie.describeOrganizationConfiguration(request -> {}).autoEnable()).isTrue();
        }
    }
}
