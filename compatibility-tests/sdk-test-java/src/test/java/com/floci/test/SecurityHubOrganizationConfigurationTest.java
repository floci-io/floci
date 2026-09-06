package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.Policy;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Security Hub organization configuration")
class SecurityHubOrganizationConfigurationTest {
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";
    private static final String MEMBER_ACCOUNT = "333333333333";

    @Test
    void organizationConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Security Hub organization settings in real AWS");

        try (SecurityHubClient management = TestFixtures.securityHubClient(MANAGEMENT_ACCOUNT);
             SecurityHubClient administrator = TestFixtures.securityHubClient(ADMIN_ACCOUNT)) {
            assertThat(management.listOrganizationAdminAccounts(request -> {}).adminAccounts()).isEmpty();

            var enabledAdmin = management.enableOrganizationAdminAccount(request -> request
                    .adminAccountId(ADMIN_ACCOUNT));
            assertThat(enabledAdmin.adminAccountId()).isEqualTo(ADMIN_ACCOUNT);
            assertThat(enabledAdmin.featureAsString()).isEqualTo("SecurityHub");

            var administrators = management.listOrganizationAdminAccounts(request -> {});
            assertThat(administrators.featureAsString()).isEqualTo("SecurityHub");
            assertThat(administrators.adminAccounts())
                    .singleElement()
                    .satisfies(account -> {
                        assertThat(account.accountId()).isEqualTo(ADMIN_ACCOUNT);
                        assertThat(account.statusAsString()).isEqualTo("ENABLED");
                    });

            assertThat(administrator.describeHub(request -> {}).hubArn()).isNotBlank();
            administrator.updateSecurityHubConfiguration(request -> request
                    .autoEnableControls(false)
                    .controlFindingGenerator("STANDARD_CONTROL"));
            var hub = administrator.describeHub(request -> {});
            assertThat(hub.autoEnableControls()).isFalse();
            assertThat(hub.controlFindingGeneratorAsString()).isEqualTo("STANDARD_CONTROL");

            administrator.updateOrganizationConfiguration(request -> request
                    .autoEnable(false)
                    .autoEnableStandards("NONE")
                    .organizationConfiguration(configuration -> configuration.configurationType("CENTRAL")));

            var organization = administrator.describeOrganizationConfiguration(request -> {});
            assertThat(organization.autoEnable()).isFalse();
            assertThat(organization.autoEnableStandardsAsString()).isEqualTo("NONE");
            assertThat(organization.organizationConfiguration().configurationTypeAsString()).isEqualTo("CENTRAL");

            var aggregator = administrator.createFindingAggregator(request -> request.regionLinkingMode("ALL_REGIONS"));
            assertThat(aggregator.findingAggregatorArn()).isNotBlank();

            var policy = administrator.createConfigurationPolicy(request -> request
                    .name(TestFixtures.uniqueName("securityhub-policy"))
                    .configurationPolicy(Policy.fromSecurityHub(securityHub -> securityHub.serviceEnabled(true)))
                    .tags(Map.of("env", "test")));
            assertThat(policy.id()).isNotBlank();
            assertThat(policy.configurationPolicy().securityHub().serviceEnabled()).isTrue();

            assertThat(administrator.listConfigurationPolicies(request -> {}).configurationPolicySummaries())
                    .anySatisfy(summary -> {
                        assertThat(summary.id()).isEqualTo(policy.id());
                        assertThat(summary.serviceEnabled()).isTrue();
                    });

            var association = administrator.startConfigurationPolicyAssociation(request -> request
                    .configurationPolicyIdentifier(policy.id())
                    .target(target -> target.accountId(MEMBER_ACCOUNT)));
            assertThat(association.targetId()).isEqualTo(MEMBER_ACCOUNT);
            assertThat(association.configurationPolicyId()).isEqualTo(policy.id());

            administrator.getConfigurationPolicyAssociation(request -> request
                    .target(target -> target.accountId(MEMBER_ACCOUNT)));
            var converged = administrator.getConfigurationPolicyAssociation(request -> request
                    .target(target -> target.accountId(MEMBER_ACCOUNT)));
            assertThat(converged.associationStatusAsString()).isEqualTo("SUCCESS");

            assertThat(administrator.listTagsForResource(request -> request.resourceArn(policy.arn())).tags())
                    .containsEntry("env", "test");
        }
    }
}
