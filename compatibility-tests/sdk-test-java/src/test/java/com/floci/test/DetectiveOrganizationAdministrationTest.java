package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.detective.DetectiveClient;
import software.amazon.awssdk.services.detective.model.Account;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Detective organization administration")
class DetectiveOrganizationAdministrationTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = MANAGEMENT_ACCOUNT;
    private static final String MEMBER_ACCOUNT = "333333333333";

    @Test
    @DisplayName("uses AWS SDK models for organization graph and member operations")
    void organizationAdministrationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Detective organization settings in real AWS");

        try (var organizations = TestFixtures.organizationsClient(MANAGEMENT_ACCOUNT);
             DetectiveClient management = TestFixtures.detectiveClient(MANAGEMENT_ACCOUNT);
             DetectiveClient administrator = TestFixtures.detectiveClient(ADMIN_ACCOUNT)) {
            try {
                organizations.describeOrganization();
            } catch (software.amazon.awssdk.services.organizations.model.AwsOrganizationsNotInUseException e) {
                organizations.createOrganization();
            }

            assertThat(management.listOrganizationAdminAccounts(request -> request.maxResults(200)).administrators())
                    .isEmpty();

            management.enableOrganizationAdminAccount(request -> request.accountId(ADMIN_ACCOUNT));

            assertThat(management.listOrganizationAdminAccounts(request -> request.maxResults(200)).administrators())
                    .extracting(administratorAccount -> administratorAccount.accountId())
                    .containsExactly(ADMIN_ACCOUNT);

            String graphArn = administrator.listGraphs(request -> request.maxResults(200)).graphList().get(0).arn();
            administrator.updateOrganizationConfiguration(request -> request.graphArn(graphArn).autoEnable(true));
            assertThat(administrator.describeOrganizationConfiguration(request -> request.graphArn(graphArn)).autoEnable())
                    .isTrue();

            var created = administrator.createMembers(request -> request
                    .graphArn(graphArn)
                    .accounts(List.of(Account.builder().accountId(MEMBER_ACCOUNT).build())));
            assertThat(created.members()).extracting(member -> member.accountId()).containsExactly(MEMBER_ACCOUNT);
            assertThat(created.unprocessedAccounts()).isEmpty();

            var duplicate = administrator.createMembers(request -> request
                    .graphArn(graphArn)
                    .accounts(List.of(Account.builder().accountId(MEMBER_ACCOUNT).build())));
            assertThat(duplicate.members()).isEmpty();
            assertThat(duplicate.unprocessedAccounts())
                    .extracting(account -> account.accountId())
                    .containsExactly(MEMBER_ACCOUNT);

            assertThat(administrator.listMembers(request -> request.graphArn(graphArn).maxResults(200)).memberDetails())
                    .extracting(member -> member.accountId())
                    .containsExactly(MEMBER_ACCOUNT);

            administrator.startMonitoringMember(request -> request.graphArn(graphArn).accountId(MEMBER_ACCOUNT));
            assertThat(administrator.listMembers(request -> request.graphArn(graphArn)).memberDetails())
                    .singleElement()
                    .satisfies(member -> assertThat(member.statusAsString()).isEqualTo("ENABLED"));
        }
    }
}
