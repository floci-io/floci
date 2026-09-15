package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.macie2.Macie2Client;
import software.amazon.awssdk.services.macie2.model.CreateMemberResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Macie organization administration")
class MacieOrganizationAdministrationTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";
    private static final String MEMBER_ACCOUNT = "333333333333";

    @Test
    @DisplayName("uses AWS SDK models for delegated administration and organization configuration")
    void organizationAdministrationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Macie organization settings in real AWS");

        try (Macie2Client management = TestFixtures.macie2Client(MANAGEMENT_ACCOUNT);
             Macie2Client administrator = TestFixtures.macie2Client(ADMIN_ACCOUNT)) {
            assertThat(management.listOrganizationAdminAccounts(request -> {}).adminAccounts()).isEmpty();

            management.enableOrganizationAdminAccount(request -> request.adminAccountId(ADMIN_ACCOUNT));

            assertThat(management.listOrganizationAdminAccounts(request -> {}).adminAccounts())
                    .anySatisfy(account -> {
                        assertThat(account.accountId()).isEqualTo(ADMIN_ACCOUNT);
                        assertThat(account.statusAsString()).isEqualTo("ENABLED");
                    });

            assertThat(administrator.getMacieSession(request -> {}).statusAsString()).isEqualTo("ENABLED");
            administrator.updateOrganizationConfiguration(request -> request.autoEnable(true));

            assertThat(administrator.describeOrganizationConfiguration(request -> {}).autoEnable()).isTrue();

            CreateMemberResponse member = administrator.createMember(request -> request
                    .account(account -> account.accountId(MEMBER_ACCOUNT).email("member@example.com"))
                    .tags(java.util.Map.of("team", "security")));
            assertThat(member.arn())
                    .isEqualTo("arn:aws:macie2:us-east-1:" + ADMIN_ACCOUNT + ":member/" + MEMBER_ACCOUNT);
            assertThat(administrator.listMembers(request -> request.onlyAssociated("true")).members())
                    .anySatisfy(account -> {
                        assertThat(account.accountId()).isEqualTo(MEMBER_ACCOUNT);
                        assertThat(account.administratorAccountId()).isEqualTo(ADMIN_ACCOUNT);
                        assertThat(account.masterAccountId()).isEqualTo(ADMIN_ACCOUNT);
                        assertThat(account.relationshipStatusAsString()).isEqualTo("Enabled");
                    });
        }
    }
}
