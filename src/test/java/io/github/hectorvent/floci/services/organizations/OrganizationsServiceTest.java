package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Handshake;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the parts of {@link OrganizationsService} that are awkward to pin down over
 * the wire: generated id formats, effective-policy merging down the OU chain, and the handshake
 * state machine including expiry.
 */
class OrganizationsServiceTest {

    private static final String MANAGEMENT_ACCOUNT = "100000000001";
    private static final String OUTSIDER_ACCOUNT = "100000000009";

    private OrganizationsService service;
    private AccountAwareStorageBackend<Handshake> handshakes;

    @BeforeEach
    void setUp() {
        handshakes = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        service = new OrganizationsService(
                new ObjectMapper(),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                handshakes);
    }

    @Test
    void generatedIdsMatchTheAwsFormats() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");

        assertTrue(organization.getId().matches("o-[a-z0-9]{10}"), organization.getId());
        assertTrue(organization.getRoot().getId().matches("r-[a-z0-9]{4}"), organization.getRoot().getId());

        String rootSuffix = organization.getRoot().getId().substring(2);
        OrganizationalUnit unit =
                service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, organization.getRoot().getId(), "Unit", null);
        assertTrue(unit.getId().matches("ou-" + rootSuffix + "-[a-z0-9]{8}"), unit.getId());

        OrganizationPolicy policy = service.createPolicy(MANAGEMENT_ACCOUNT, "{}", null, "Policy",
                "SERVICE_CONTROL_POLICY", null);
        assertTrue(policy.getId().matches("p-[a-z0-9]{8}"), policy.getId());

        CreateAccountStatus status =
                service.createAccount(MANAGEMENT_ACCOUNT, "dev@example.com", "Dev", null, false);
        assertTrue(status.getId().matches("car-[a-z0-9]{8}"), status.getId());
        assertTrue(status.getAccountId().matches("\\d{12}"), status.getAccountId());
    }

    @Test
    void organizationsArnsCarryNoRegion() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");

        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":organization/" + organization.getId(),
                organization.getArn());
        assertEquals("arn:aws:organizations::" + MANAGEMENT_ACCOUNT + ":root/" + organization.getId()
                + "/" + organization.getRoot().getId(), organization.getRoot().getArn());
    }

    @Test
    void fullAwsAccessIsAttachedToEveryNewTargetAndCannotBeDetachedAlone() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        OrganizationalUnit unit = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Unit", null);

        List<OrganizationPolicy> attached = service.listPoliciesForTarget(
                MANAGEMENT_ACCOUNT, unit.getId(), "SERVICE_CONTROL_POLICY");
        assertEquals(1, attached.size());
        assertEquals(OrganizationsService.FULL_AWS_ACCESS_POLICY_ID, attached.get(0).getId());

        AwsException error = assertThrows(AwsException.class, () -> service.detachPolicy(
                MANAGEMENT_ACCOUNT, OrganizationsService.FULL_AWS_ACCESS_POLICY_ID, unit.getId()));
        assertEquals("ConstraintViolationException", error.getErrorCode());
    }

    @Test
    void effectivePolicyMergesDownTheChainWithTheClosestAncestorWinning() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        service.enablePolicyType(MANAGEMENT_ACCOUNT, rootId, "TAG_POLICY");

        OrganizationalUnit unit = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Unit", null);
        String accountId =
                service.createAccount(MANAGEMENT_ACCOUNT, "dev@example.com", "Dev", null, false).getAccountId();
        service.moveAccount(MANAGEMENT_ACCOUNT, accountId, rootId, unit.getId());

        OrganizationPolicy rootPolicy = service.createPolicy(MANAGEMENT_ACCOUNT,
                "{\"tags\":{\"cost\":{\"tag_key\":{\"@@assign\":\"from-root\"}},"
                        + "\"owner\":{\"tag_key\":{\"@@assign\":\"platform\"}}}}",
                null, "RootTags", "TAG_POLICY", null);
        OrganizationPolicy unitPolicy = service.createPolicy(MANAGEMENT_ACCOUNT,
                "{\"tags\":{\"cost\":{\"tag_key\":{\"@@assign\":\"from-ou\"}}}}",
                null, "UnitTags", "TAG_POLICY", null);
        service.attachPolicy(MANAGEMENT_ACCOUNT, rootPolicy.getId(), rootId);
        service.attachPolicy(MANAGEMENT_ACCOUNT, unitPolicy.getId(), unit.getId());

        OrganizationsService.EffectivePolicy effective =
                service.describeEffectivePolicy(MANAGEMENT_ACCOUNT, "TAG_POLICY", accountId);

        assertEquals(accountId, effective.targetId());
        assertTrue(effective.policyContent().contains("from-ou"), effective.policyContent());
        assertFalse(effective.policyContent().contains("from-root"), effective.policyContent());
        assertTrue(effective.policyContent().contains("platform"), effective.policyContent());
    }

    @Test
    void organizationPathsRunFromTheOrganizationDownToTheResourceItself() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        OrganizationalUnit parent =
                service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Parent", null);
        OrganizationalUnit child =
                service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, parent.getId(), "Child", null);
        String accountId =
                service.createAccount(MANAGEMENT_ACCOUNT, "dev@example.com", "Dev", null, false).getAccountId();
        service.moveAccount(MANAGEMENT_ACCOUNT, accountId, rootId, child.getId());

        String root = organization.getId() + "/" + rootId + "/";
        assertEquals(root, service.organizationPath(MANAGEMENT_ACCOUNT, rootId));
        assertEquals(root + parent.getId() + "/",
                service.organizationPath(MANAGEMENT_ACCOUNT, parent.getId()));
        assertEquals(root + parent.getId() + "/" + child.getId() + "/",
                service.organizationPath(MANAGEMENT_ACCOUNT, child.getId()));

        // An account's path names every OU above it and ends with the account, the way ListAccounts
        // reports Paths — and it tracks the move rather than the parent the account was created in.
        String accountPath = root + parent.getId() + "/" + child.getId() + "/" + accountId + "/";
        assertEquals(accountPath, service.organizationPath(MANAGEMENT_ACCOUNT, accountId));
        // The Path shape AWS publishes for the Organizations API and the registry schemas.
        assertTrue(accountPath.matches(
                        "^(o-[a-z0-9]{10,32}/r-[0-9a-z]{4,32}(/ou-[0-9a-z]{4,32}-[a-z0-9]{8,32})*(/\\d{12})*)/"),
                accountPath);
    }

    @Test
    void effectivePolicyRejectsAccessControlPolicyTypes() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");

        AwsException error = assertThrows(AwsException.class, () -> service.describeEffectivePolicy(
                MANAGEMENT_ACCOUNT, "SERVICE_CONTROL_POLICY", organization.getRoot().getId()));
        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void anExpiredHandshakeReportsExpiredAndCannotBeAccepted() {
        service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        Handshake handshake = service.inviteAccountToOrganization(
                MANAGEMENT_ACCOUNT, OUTSIDER_ACCOUNT, "ACCOUNT", null);

        handshake.setExpirationTimestamp(Instant.now().minus(1, ChronoUnit.DAYS));
        handshakes.putForAccount(MANAGEMENT_ACCOUNT, handshake.getId(), handshake);

        assertEquals("EXPIRED", service.describeHandshake(MANAGEMENT_ACCOUNT, handshake.getId()).getState());

        AwsException error = assertThrows(AwsException.class,
                () -> service.acceptHandshake(OUTSIDER_ACCOUNT, handshake.getId()));
        assertEquals("InvalidHandshakeTransitionException", error.getErrorCode());
    }

    @Test
    void acceptingAnInvitationJoinsTheOrganizationAsAnInvitedMember() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        Handshake handshake = service.inviteAccountToOrganization(
                MANAGEMENT_ACCOUNT, OUTSIDER_ACCOUNT, "ACCOUNT", null);

        Handshake accepted = service.acceptHandshake(OUTSIDER_ACCOUNT, handshake.getId());
        assertEquals("ACCEPTED", accepted.getState());

        OrganizationAccount joined = service.describeAccount(OUTSIDER_ACCOUNT, OUTSIDER_ACCOUNT);
        assertEquals("INVITED", joined.getJoinedMethod());
        assertEquals(organization.getRoot().getId(), joined.getParentId());
        assertEquals(organization.getId(), service.describeOrganization(OUTSIDER_ACCOUNT).getId());
    }

    @Test
    void memberAccountsCannotPerformManagementActions() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        Handshake handshake = service.inviteAccountToOrganization(
                MANAGEMENT_ACCOUNT, OUTSIDER_ACCOUNT, "ACCOUNT", null);
        service.acceptHandshake(OUTSIDER_ACCOUNT, handshake.getId());

        AwsException error = assertThrows(AwsException.class, () -> service.createOrganizationalUnit(
                OUTSIDER_ACCOUNT, organization.getRoot().getId(), "Unit", null));
        assertEquals("AccessDeniedException", error.getErrorCode());
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void tagsRoundTripAcrossEveryTaggableResourceType() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        OrganizationalUnit unit = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Unit", null);
        OrganizationPolicy policy = service.createPolicy(MANAGEMENT_ACCOUNT, "{}", null, "Policy",
                "SERVICE_CONTROL_POLICY", null);

        for (String resourceId : List.of(rootId, unit.getId(), policy.getId(), MANAGEMENT_ACCOUNT)) {
            service.tagResource(MANAGEMENT_ACCOUNT, resourceId, Map.of("env", "test"));
            assertEquals(Map.of("env", "test"), service.listTagsForResource(MANAGEMENT_ACCOUNT, resourceId));
            service.untagResource(MANAGEMENT_ACCOUNT, resourceId, List.of("env"));
            assertTrue(service.listTagsForResource(MANAGEMENT_ACCOUNT, resourceId).isEmpty());
        }
    }

    @Test
    void putResourcePolicyReplacesTagsRatherThanMergingThem() {
        service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String content = "{\"Version\":\"2012-10-17\"}";

        service.putResourcePolicy(MANAGEMENT_ACCOUNT, content, Map.of("env", "test", "owner", "platform"));
        assertEquals(Map.of("env", "test", "owner", "platform"),
                service.describeResourcePolicy(MANAGEMENT_ACCOUNT).tags());

        // The resource policy is not addressable by TagResource/UntagResource, so Put is the only
        // way a dropped key can be removed — CloudFormation relies on this to converge on update.
        service.putResourcePolicy(MANAGEMENT_ACCOUNT, content, Map.of("env", "prod"));
        assertEquals(Map.of("env", "prod"), service.describeResourcePolicy(MANAGEMENT_ACCOUNT).tags());

        // Omitting Tags entirely is "don't touch", matching how the provisioner treats an absent
        // Tags property on every other Organizations type.
        service.putResourcePolicy(MANAGEMENT_ACCOUNT, content, null);
        assertEquals(Map.of("env", "prod"), service.describeResourcePolicy(MANAGEMENT_ACCOUNT).tags());
    }

    @Test
    void anAccountOutsideAnyOrganizationGetsNotInUse() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeOrganization(OUTSIDER_ACCOUNT));
        assertEquals("AWSOrganizationsNotInUseException", error.getErrorCode());
    }

    @Test
    void controlTowerGuardrailsReconcileRegisteredAndSecurityOusIdempotently() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        String registered = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Infrastructure", null).getId();
        String security = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Security", null).getId();
        String unrelated = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "Suspended", null).getId();

        service.ensureControlTowerGuardrails(MANAGEMENT_ACCOUNT, Set.of(registered));
        service.ensureControlTowerGuardrails(MANAGEMENT_ACCOUNT, Set.of(registered));

        OrganizationPolicy guardrail =
                service.describePolicy(MANAGEMENT_ACCOUNT, OrganizationsService.CONTROL_TOWER_GUARDRAIL_ID);
        assertEquals(OrganizationsService.CONTROL_TOWER_GUARDRAIL_NAME, guardrail.getName());
        assertFalse(guardrail.isAwsManaged());
        assertTrue(guardrail.getTargets().containsAll(Set.of(registered, security)));
        assertFalse(guardrail.getTargets().contains(unrelated));
        assertTrue(service.listPoliciesForTarget(MANAGEMENT_ACCOUNT, registered, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
        assertTrue(service.listPoliciesForTarget(MANAGEMENT_ACCOUNT, security, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
        assertFalse(service.listPoliciesForTarget(MANAGEMENT_ACCOUNT, unrelated, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
    }

    // ──────────────────────────── effective SCP levels ────────────────────────────

    private static final String DENY_S3 =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                    + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";

    @Test
    void effectiveScpLevelsWalkRootOuAccountChain() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String rootId = organization.getRoot().getId();
        String ouId = service.createOrganizationalUnit(MANAGEMENT_ACCOUNT, rootId, "workloads", null).getId();
        String member = service.createAccount(MANAGEMENT_ACCOUNT, "member@example.com", "Member", null, false)
                .getAccountId();
        service.moveAccount(MANAGEMENT_ACCOUNT, member, rootId, ouId);

        // FullAWSAccess sits on root, OU, and account: three levels.
        List<List<String>> levels = service.effectiveScpLevels(member);
        assertEquals(3, levels.size());
        assertTrue(levels.get(0).get(0).contains("\"Allow\""));

        // A deny-s3 SCP attached to the OU shows up on the middle level.
        String policyId = service.createPolicy(MANAGEMENT_ACCOUNT, DENY_S3, null,
                "deny-s3", "SERVICE_CONTROL_POLICY", null).getId();
        service.attachPolicy(MANAGEMENT_ACCOUNT, policyId, ouId);
        levels = service.effectiveScpLevels(member);
        assertEquals(2, levels.get(1).size());
    }

    @Test
    void theManagementAccountIsExemptFromScps() {
        service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String member = service.createAccount(MANAGEMENT_ACCOUNT, "member@example.com", "Member", null, false)
                .getAccountId();

        // The member is under the same root and does get a ceiling, so the exemption is what
        // separates the two — not an organization-wide absence of SCPs.
        assertNotNull(service.effectiveScpLevels(member));
        assertNull(service.effectiveScpLevels(MANAGEMENT_ACCOUNT));
    }

    @Test
    void anAccountOutsideAnyOrganizationHasNoScpCeiling() {
        service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        assertNull(service.effectiveScpLevels(OUTSIDER_ACCOUNT));
    }

    @Test
    void disablingTheScpPolicyTypeOnTheRootRemovesTheCeiling() {
        Organization organization = service.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String member = service.createAccount(MANAGEMENT_ACCOUNT, "member@example.com", "Member", null, false)
                .getAccountId();
        assertNotNull(service.effectiveScpLevels(member));

        service.disablePolicyType(MANAGEMENT_ACCOUNT, organization.getRoot().getId(), "SERVICE_CONTROL_POLICY");
        assertNull(service.effectiveScpLevels(member));
    }

    @Test
    void effectiveScpLevelsAreNullWhenEnforcementDisabled() {
        OrganizationsService disabled = new OrganizationsService(
                new ObjectMapper(),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                false);
        disabled.createOrganization(MANAGEMENT_ACCOUNT, "ALL");
        String member = disabled.createAccount(MANAGEMENT_ACCOUNT, "member@example.com", "Member", null, false)
                .getAccountId();
        assertNull(disabled.effectiveScpLevels(member));
    }
}
