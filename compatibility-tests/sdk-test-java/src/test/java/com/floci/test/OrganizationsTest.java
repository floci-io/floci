package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.AccessDeniedException;
import software.amazon.awssdk.services.organizations.model.AwsOrganizationsNotInUseException;
import software.amazon.awssdk.services.organizations.model.ChildType;
import software.amazon.awssdk.services.organizations.model.ConstraintViolationException;
import software.amazon.awssdk.services.organizations.model.CreateAccountState;
import software.amazon.awssdk.services.organizations.model.CreateAccountStatus;
import software.amazon.awssdk.services.organizations.model.Handshake;
import software.amazon.awssdk.services.organizations.model.HandshakeState;
import software.amazon.awssdk.services.organizations.model.Organization;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;
import software.amazon.awssdk.services.organizations.model.OrganizationalUnit;
import software.amazon.awssdk.services.organizations.model.OrganizationalUnitNotEmptyException;
import software.amazon.awssdk.services.organizations.model.Policy;
import software.amazon.awssdk.services.organizations.model.PolicyInUseException;
import software.amazon.awssdk.services.organizations.model.PolicyType;
import software.amazon.awssdk.services.organizations.model.Root;
import software.amazon.awssdk.services.organizations.model.TargetType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the whole Organizations surface through the real AWS SDK, which is the check that
 * matters for wire compatibility: the SDK does its own request marshalling, response parsing,
 * id/ARN validation and typed-exception mapping.
 *
 * <p>The test tears the organization down at the end so it can be re-run against the same
 * Floci instance.
 */
public class OrganizationsTest {

    private static final String SCP_CONTENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"ec2:*\",\"Resource\":\"*\"}]}";
    private static final String TAG_POLICY_CONTENT =
            "{\"tags\":{\"CostCenter\":{\"tag_key\":{\"@@assign\":\"CostCenter\"}}}}";

    private static final String INVITED_ACCOUNT = "987654321098";

    private final OrganizationsClient client = TestFixtures.organizationsClient();

    /** A client whose 12-digit access key id makes Floci treat the caller as that account. */
    private static OrganizationsClient clientFor(String accountId) {
        return OrganizationsClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accountId, "test")))
                .build();
    }

    @Test
    public void testFullLifecycle() {
        // 1. Create the organization.
        Organization organization = client.createOrganization(r -> r.featureSet(OrganizationFeatureSet.ALL))
                .organization();
        assertThat(organization.id()).matches("o-[a-z0-9]{10}");
        assertThat(organization.featureSet()).isEqualTo(OrganizationFeatureSet.ALL);
        assertThat(organization.arn()).startsWith("arn:aws:organizations::");
        assertThat(organization.masterAccountId()).isNotBlank();

        String managementAccountId = organization.masterAccountId();

        try {
            assertThat(client.describeOrganization().organization().id()).isEqualTo(organization.id());

            // 2. The root exists and carries the AWS-managed FullAWSAccess SCP.
            Root root = client.listRoots().roots().get(0);
            assertThat(root.id()).matches("r-[a-z0-9]{4}");
            assertThat(root.name()).isEqualTo("Root");
            assertThat(root.policyTypes()).anyMatch(p -> p.type() == PolicyType.SERVICE_CONTROL_POLICY);

            assertThat(client.listPoliciesForTarget(r -> r.targetId(root.id())
                            .filter(PolicyType.SERVICE_CONTROL_POLICY)).policies())
                    .anyMatch(p -> p.id().equals("p-FullAWSAccess") && Boolean.TRUE.equals(p.awsManaged()));

            // 3. Build an OU tree.
            OrganizationalUnit workloads = client.createOrganizationalUnit(r -> r
                    .parentId(root.id()).name("Workloads")).organizationalUnit();
            assertThat(workloads.id()).matches("ou-[a-z0-9]{4}-[a-z0-9]{8}");

            OrganizationalUnit production = client.createOrganizationalUnit(r -> r
                    .parentId(workloads.id()).name("Production")).organizationalUnit();

            assertThat(client.listOrganizationalUnitsForParent(r -> r.parentId(root.id()))
                    .organizationalUnits()).extracting(OrganizationalUnit::id).containsExactly(workloads.id());
            assertThat(client.listChildren(r -> r.parentId(workloads.id())
                            .childType(ChildType.ORGANIZATIONAL_UNIT)).children())
                    .singleElement().satisfies(child -> assertThat(child.id()).isEqualTo(production.id()));
            assertThat(client.listParents(r -> r.childId(production.id())).parents())
                    .singleElement().satisfies(parent -> assertThat(parent.id()).isEqualTo(workloads.id()));

            // 4. Create a member account and move it into the OU.
            CreateAccountStatus status = client.createAccount(r -> r
                    .email("compat-dev@example.com").accountName("CompatDev")).createAccountStatus();
            assertThat(status.id()).startsWith("car-");
            assertThat(status.state()).isEqualTo(CreateAccountState.SUCCEEDED);
            assertThat(status.accountId()).matches("\\d{12}");

            String memberAccountId = status.accountId();
            assertThat(client.describeCreateAccountStatus(r -> r.createAccountRequestId(status.id()))
                    .createAccountStatus().accountId()).isEqualTo(memberAccountId);

            Account member = client.describeAccount(r -> r.accountId(memberAccountId)).account();
            assertThat(member.email()).isEqualTo("compat-dev@example.com");
            assertThat(member.name()).isEqualTo("CompatDev");
            assertThat(member.joinedTimestamp()).isNotNull();

            assertThat(client.listAccounts().accounts()).extracting(Account::id)
                    .contains(managementAccountId, memberAccountId);

            client.moveAccount(r -> r.accountId(memberAccountId)
                    .sourceParentId(root.id()).destinationParentId(workloads.id()));
            assertThat(client.listAccountsForParent(r -> r.parentId(workloads.id())).accounts())
                    .extracting(Account::id).containsExactly(memberAccountId);

            assertThatThrownBy(() -> client.deleteOrganizationalUnit(r -> r
                    .organizationalUnitId(workloads.id())))
                    .isInstanceOf(OrganizationalUnitNotEmptyException.class);

            // 5. Service control policies.
            Policy scp = client.createPolicy(r -> r.name("CompatDenyEc2").description("Deny EC2")
                    .type(PolicyType.SERVICE_CONTROL_POLICY).content(SCP_CONTENT)).policy();
            assertThat(scp.policySummary().id()).matches("p-[a-z0-9]{8}");
            assertThat(scp.content()).isEqualTo(SCP_CONTENT);

            String scpId = scp.policySummary().id();
            client.attachPolicy(r -> r.policyId(scpId).targetId(workloads.id()));

            assertThat(client.listTargetsForPolicy(r -> r.policyId(scpId)).targets())
                    .singleElement().satisfies(target -> {
                        assertThat(target.targetId()).isEqualTo(workloads.id());
                        assertThat(target.type()).isEqualTo(TargetType.ORGANIZATIONAL_UNIT);
                        assertThat(target.name()).isEqualTo("Workloads");
                    });

            assertThatThrownBy(() -> client.deletePolicy(r -> r.policyId(scpId)))
                    .isInstanceOf(PolicyInUseException.class);

            client.detachPolicy(r -> r.policyId(scpId).targetId(workloads.id()));
            assertThatThrownBy(() -> client.detachPolicy(r -> r
                    .policyId("p-FullAWSAccess").targetId(workloads.id())))
                    .isInstanceOf(ConstraintViolationException.class);
            client.deletePolicy(r -> r.policyId(scpId));

            // 6. Tag policies and effective-policy inheritance.
            client.enablePolicyType(r -> r.rootId(root.id()).policyType(PolicyType.TAG_POLICY));
            Policy tagPolicy = client.createPolicy(r -> r.name("CompatCostCenter")
                    .type(PolicyType.TAG_POLICY).content(TAG_POLICY_CONTENT)).policy();
            String tagPolicyId = tagPolicy.policySummary().id();
            client.attachPolicy(r -> r.policyId(tagPolicyId).targetId(root.id()));

            assertThat(client.describeEffectivePolicy(r -> r
                            .policyType("TAG_POLICY").targetId(memberAccountId)).effectivePolicy())
                    .satisfies(effective -> {
                        assertThat(effective.policyContent()).isEqualTo(TAG_POLICY_CONTENT);
                        assertThat(effective.targetId()).isEqualTo(memberAccountId);
                        assertThat(effective.lastUpdatedTimestamp()).isNotNull();
                    });

            client.detachPolicy(r -> r.policyId(tagPolicyId).targetId(root.id()));
            client.deletePolicy(r -> r.policyId(tagPolicyId));
            client.disablePolicyType(r -> r.rootId(root.id()).policyType(PolicyType.TAG_POLICY));

            // 7. Tags.
            client.tagResource(r -> r.resourceId(memberAccountId)
                    .tags(t -> t.key("team").value("platform")));
            assertThat(client.listTagsForResource(r -> r.resourceId(memberAccountId)).tags())
                    .singleElement().satisfies(tag -> {
                        assertThat(tag.key()).isEqualTo("team");
                        assertThat(tag.value()).isEqualTo("platform");
                    });
            client.untagResource(r -> r.resourceId(memberAccountId).tagKeys("team"));
            assertThat(client.listTagsForResource(r -> r.resourceId(memberAccountId)).tags()).isEmpty();

            // 8. Trusted access and delegated administrators.
            client.enableAWSServiceAccess(r -> r.servicePrincipal("config.amazonaws.com"));
            assertThat(client.listAWSServiceAccessForOrganization().enabledServicePrincipals())
                    .singleElement().satisfies(principal -> {
                        assertThat(principal.servicePrincipal()).isEqualTo("config.amazonaws.com");
                        assertThat(principal.dateEnabled()).isNotNull();
                    });

            client.registerDelegatedAdministrator(r -> r.accountId(memberAccountId)
                    .servicePrincipal("config.amazonaws.com"));
            assertThat(client.listDelegatedAdministrators(r -> r
                            .servicePrincipal("config.amazonaws.com")).delegatedAdministrators())
                    .singleElement().satisfies(admin -> {
                        assertThat(admin.id()).isEqualTo(memberAccountId);
                        assertThat(admin.delegationEnabledDate()).isNotNull();
                    });
            assertThat(client.listDelegatedServicesForAccount(r -> r.accountId(memberAccountId))
                    .delegatedServices()).singleElement()
                    .satisfies(svc -> assertThat(svc.servicePrincipal()).isEqualTo("config.amazonaws.com"));

            client.deregisterDelegatedAdministrator(r -> r.accountId(memberAccountId)
                    .servicePrincipal("config.amazonaws.com"));
            client.disableAWSServiceAccess(r -> r.servicePrincipal("config.amazonaws.com"));

            // 9. Resource policy.
            String resourcePolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"organizations:Describe*\",\"Resource\":\"*\"}]}";
            assertThat(client.putResourcePolicy(r -> r.content(resourcePolicy)).resourcePolicy())
                    .satisfies(policy -> {
                        assertThat(policy.resourcePolicySummary().id()).startsWith("rp-");
                        assertThat(policy.content()).isEqualTo(resourcePolicy);
                    });
            assertThat(client.describeResourcePolicy(r -> { }).resourcePolicy().content())
                    .isEqualTo(resourcePolicy);
            client.deleteResourcePolicy(r -> { });

            // 10. The invitation handshake, driven from both sides.
            try (OrganizationsClient invitedClient = clientFor(INVITED_ACCOUNT)) {
                assertThatThrownBy(invitedClient::describeOrganization)
                        .isInstanceOf(AwsOrganizationsNotInUseException.class);

                Handshake invitation = client.inviteAccountToOrganization(r -> r
                        .target(t -> t.id(INVITED_ACCOUNT).type("ACCOUNT"))
                        .notes("compat suite")).handshake();
                assertThat(invitation.state()).isEqualTo(HandshakeState.REQUESTED);
                assertThat(invitation.expirationTimestamp()).isNotNull();

                Handshake accepted = invitedClient.acceptHandshake(r -> r
                        .handshakeId(invitation.id())).handshake();
                assertThat(accepted.state()).isEqualTo(HandshakeState.ACCEPTED);

                // The member account can now read the organization it belongs to, but not mutate it.
                assertThat(invitedClient.describeOrganization().organization().id())
                        .isEqualTo(organization.id());
                assertThatThrownBy(() -> invitedClient.createOrganizationalUnit(r -> r
                        .parentId(root.id()).name("Nope")))
                        .isInstanceOf(AccessDeniedException.class);

                assertThat(client.listHandshakesForOrganization().handshakes())
                        .extracting(Handshake::id).contains(invitation.id());

                invitedClient.leaveOrganization();
                assertThat(client.listAccounts().accounts())
                        .extracting(Account::id).doesNotContain(INVITED_ACCOUNT);
            }

            // 11. Tear the organization down.
            client.removeAccountFromOrganization(r -> r.accountId(memberAccountId));
            client.deleteOrganizationalUnit(r -> r.organizationalUnitId(production.id()));
            client.deleteOrganizationalUnit(r -> r.organizationalUnitId(workloads.id()));
        } finally {
            // Best effort: if an assertion above failed, the organization may still hold accounts
            // or OUs and DeleteOrganization will refuse. Swallowing that here keeps the original
            // assertion failure visible instead of replacing it with a cleanup error.
            try {
                client.deleteOrganization();
            } catch (RuntimeException e) {
                System.err.println("[WARN] Organizations cleanup failed, leaving the organization "
                        + "in place: " + e.getMessage());
            }
        }

        assertThatThrownBy(client::describeOrganization)
                .isInstanceOf(AwsOrganizationsNotInUseException.class);
    }
}
