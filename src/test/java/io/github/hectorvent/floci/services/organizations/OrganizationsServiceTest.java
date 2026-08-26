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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void anAccountOutsideAnyOrganizationGetsNotInUse() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.describeOrganization(OUTSIDER_ACCOUNT));
        assertEquals("AWSOrganizationsNotInUseException", error.getErrorCode());
    }
}
