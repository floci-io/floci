package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.AlreadyInOrganizationException;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.Policy;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Security Hub organization configuration")
class SecurityHubOrganizationConfigurationTest {
    private static final Logger LOG = Logger.getLogger(SecurityHubOrganizationConfigurationTest.class);
    private static final String MANAGEMENT_ACCOUNT = "test";

    @Test
    void organizationConfigurationUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids changing Security Hub organization settings in real AWS");

        try (OrganizationsClient organizations = TestFixtures.organizationsClient()) {
            boolean createdOrganization = ensureOrganization(organizations);
            String adminAccount = createMemberAccount(organizations, "securityhub-admin");
            String memberAccount = createMemberAccount(organizations, "securityhub-member");

            try (SecurityHubClient management = TestFixtures.securityHubClient(MANAGEMENT_ACCOUNT);
                 SecurityHubClient administrator = TestFixtures.securityHubClient(adminAccount)) {
                assertThat(management.listOrganizationAdminAccounts(request -> {}).adminAccounts()).isEmpty();

                var enabledAdmin = management.enableOrganizationAdminAccount(request -> request
                        .adminAccountId(adminAccount));
                assertThat(enabledAdmin.adminAccountId()).isEqualTo(adminAccount);
                assertThat(enabledAdmin.featureAsString()).isEqualTo("SecurityHub");

                var administrators = management.listOrganizationAdminAccounts(request -> {});
                assertThat(administrators.featureAsString()).isEqualTo("SecurityHub");
                assertThat(administrators.adminAccounts())
                    .singleElement()
                    .satisfies(account -> {
                        assertThat(account.accountId()).isEqualTo(adminAccount);
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
                    .target(target -> target.accountId(memberAccount)));
                assertThat(association.targetId()).isEqualTo(memberAccount);
                assertThat(association.configurationPolicyId()).isEqualTo(policy.id());

                administrator.getConfigurationPolicyAssociation(request -> request
                    .target(target -> target.accountId(memberAccount)));
                var converged = administrator.getConfigurationPolicyAssociation(request -> request
                    .target(target -> target.accountId(memberAccount)));
                assertThat(converged.associationStatusAsString()).isEqualTo("SUCCESS");

                assertThat(administrator.listTagsForResource(request -> request.resourceArn(policy.arn())).tags())
                        .containsEntry("env", "test");
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
            LOG.warnf(e, "Security Hub compatibility cleanup failed: %s", action);
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
        var response = organizations.createAccount(request -> request
                .accountName(suffix)
                .email(suffix + "@example.com"));
        return response.createAccountStatus().accountId();
    }
}
