package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The caller checks the reference models on the five centralized root features. Each refusal is a
 * shape botocore declares on those operations and no other, and the order they are asked in is
 * the order below: whether there is an organization, what it has enabled, then who is asking.
 */
class CentralRootAccessTest {

    private static final String ORGANIZATION = "o-rootaccess1";
    private static final String MANAGEMENT = "100000000001";
    private static final String DELEGATED_ADMIN = "100000000002";
    private static final String MEMBER = "100000000003";

    private IamService iamService;
    private RootAccessOrganization organizations;
    private CentralRootAccess rootAccess;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        organizations = mock(RootAccessOrganization.class);
        Instance<RootAccessOrganization> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(organizations);
        rootAccess = new CentralRootAccess(iamService, instance);
    }

    private void organizationOf(String caller, boolean allFeatures, boolean trustedAccess) {
        when(organizations.membershipOf(caller)).thenReturn(Optional.of(
                new RootAccessOrganization.Membership(ORGANIZATION, MANAGEMENT, allFeatures, trustedAccess,
                        Set.of(DELEGATED_ADMIN), Set.of(MANAGEMENT, DELEGATED_ADMIN, MEMBER))));
    }

    private static String codeOf(Executable call) {
        return assertThrows(AwsException.class, call).getErrorCode();
    }

    @Test
    void anAccountInNoOrganizationIsRefused() {
        when(organizations.membershipOf(MANAGEMENT)).thenReturn(Optional.empty());

        assertEquals("OrganizationNotFoundException", codeOf(() -> rootAccess.list(MANAGEMENT)));
        verify(iamService, never()).listOrganizationsFeatures();
    }

    @Test
    void anOrganizationWithoutAllFeaturesIsRefused() {
        organizationOf(MANAGEMENT, false, true);

        assertEquals("OrganizationNotInAllFeaturesModeException", codeOf(() -> rootAccess.list(MANAGEMENT)));
    }

    @Test
    void iamNeedsTrustedAccessInOrganizations() {
        organizationOf(MANAGEMENT, true, false);

        assertEquals("ServiceAccessNotEnabledException", codeOf(() -> rootAccess.list(MANAGEMENT)));
    }

    @Test
    void anOrdinaryMemberAccountCannotSeeOrMoveTheFeatures() {
        organizationOf(MEMBER, true, true);

        assertEquals("AccountNotManagementOrDelegatedAdministratorException",
                codeOf(() -> rootAccess.list(MEMBER)));
        assertEquals("AccountNotManagementOrDelegatedAdministratorException",
                codeOf(() -> rootAccess.enableRootSessions(MEMBER)));
        verify(iamService, never()).enableOrganizationsRootSessions();
    }

    /**
     * {@code CallerIsNotManagementAccount} is modeled on the two enable operations and on neither
     * disable one, so a delegated administrator may read the features and turn them off but not on.
     */
    @Test
    void aDelegatedAdministratorMayListAndDisableButNotEnable() {
        organizationOf(DELEGATED_ADMIN, true, true);
        when(iamService.listOrganizationsFeatures()).thenReturn(List.of(IamService.FEATURE_ROOT_SESSIONS));
        when(iamService.disableOrganizationsRootSessions()).thenReturn(List.of());

        assertEquals(List.of(IamService.FEATURE_ROOT_SESSIONS), rootAccess.list(DELEGATED_ADMIN).enabledFeatures());
        assertEquals(List.of(), rootAccess.disableRootSessions(DELEGATED_ADMIN).enabledFeatures());
        assertEquals("CallerIsNotManagementAccountException",
                codeOf(() -> rootAccess.enableRootSessions(DELEGATED_ADMIN)));
        assertEquals("CallerIsNotManagementAccountException",
                codeOf(() -> rootAccess.enableRootCredentialsManagement(DELEGATED_ADMIN)));
        verify(iamService, never()).enableOrganizationsRootSessions();
    }

    @Test
    void theFeatureSetIsAnsweredWithTheOrganizationItBelongsTo() {
        organizationOf(MANAGEMENT, true, true);
        when(iamService.enableOrganizationsRootSessions())
                .thenReturn(List.of(IamService.FEATURE_ROOT_SESSIONS));

        CentralRootAccess.Features features = rootAccess.enableRootSessions(MANAGEMENT);

        assertEquals(ORGANIZATION, features.organizationId());
        assertEquals(List.of(IamService.FEATURE_ROOT_SESSIONS), features.enabledFeatures());
    }

    /**
     * A privileged session needs the feature the guide asks for: "You have enabled centralized
     * root access in your organization", which is {@code EnableOrganizationsRootSessions}.
     */
    @Test
    void aPrivilegedSessionNeedsRootSessionsEnabled() {
        organizationOf(MANAGEMENT, true, true);
        when(iamService.listOrganizationsFeatures())
                .thenReturn(List.of(IamService.FEATURE_ROOT_CREDENTIALS));

        AwsException refused = assertThrows(AwsException.class,
                () -> rootAccess.privilegedSessionOrganization(MANAGEMENT));

        assertEquals("AccessDenied", refused.getErrorCode());
        assertEquals(403, refused.getHttpStatus());
    }

    @Test
    void aPrivilegedSessionAnswersTheOrganizationOnceItIsEnabled() {
        organizationOf(DELEGATED_ADMIN, true, true);
        when(iamService.listOrganizationsFeatures()).thenReturn(List.of(IamService.FEATURE_ROOT_SESSIONS));

        RootAccessOrganization.Membership organization =
                rootAccess.privilegedSessionOrganization(DELEGATED_ADMIN);

        assertEquals(ORGANIZATION, organization.organizationId());
        assertEquals(MANAGEMENT, organization.managementAccountId());
    }

    @Test
    void anAccountOutsideTheOrganizationCannotOpenAPrivilegedSession() {
        when(organizations.membershipOf(MEMBER)).thenReturn(Optional.empty());

        AwsException refused = assertThrows(AwsException.class,
                () -> rootAccess.privilegedSessionOrganization(MEMBER));

        assertEquals("AccessDenied", refused.getErrorCode());
        assertEquals(403, refused.getHttpStatus());
    }
}
