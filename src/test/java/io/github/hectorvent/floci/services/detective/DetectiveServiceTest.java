package io.github.hectorvent.floci.services.detective;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectiveServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = MANAGEMENT_ACCOUNT;
    private static final String MEMBER_ACCOUNT = "333333333333";

    private RegionResolver regionResolver;
    private OrganizationsService organizationsService;
    private DetectiveService service;

    @BeforeEach
    void setUp() {
        regionResolver = mock(RegionResolver.class);
        organizationsService = mock(OrganizationsService.class);
        when(regionResolver.getAccountId()).thenReturn(MANAGEMENT_ACCOUNT);

        Organization organization = new Organization();
        organization.setMasterAccountId(MANAGEMENT_ACCOUNT);
        OrganizationAccount management = new OrganizationAccount();
        management.setId(MANAGEMENT_ACCOUNT);
        OrganizationAccount member = new OrganizationAccount();
        member.setId(MEMBER_ACCOUNT);
        when(organizationsService.describeOrganization(MANAGEMENT_ACCOUNT)).thenReturn(organization);
        when(organizationsService.listAccounts(MANAGEMENT_ACCOUNT)).thenReturn(java.util.List.of(management, member));

        service = new DetectiveService(
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT), regionResolver, organizationsService);
    }

    @Test
    void delegationCreatesGraphForAdministratorAccount() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        assertTrue(service.requireGraph(REGION).isGraph());
        assertEquals(ADMIN_ACCOUNT, service.requireGraph(REGION).getAdminAccountId());
    }

    @Test
    void organizationMemberDoesNotRequireEmailAddress() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);

        var member = service.createMember(REGION, graphArn, MEMBER_ACCOUNT, null);

        assertEquals(MEMBER_ACCOUNT, member.getAccountId());
        assertNull(member.getEmailAddress());
        assertEquals("ACCEPTED_BUT_DISABLED", member.getStatus());
    }

    @Test
    void omittedAutoEnableLeavesConfigurationUnchanged() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);
        service.updateOrganizationConfiguration(REGION, graphArn, true);

        service.updateOrganizationConfiguration(REGION, graphArn, null);

        assertTrue(service.requireGraph(REGION).isAutoEnable());
    }

    @Test
    void startMonitoringRequiresAcceptedButDisabledMember() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        when(regionResolver.getAccountId()).thenReturn(ADMIN_ACCOUNT);
        String graphArn = service.graphArn(REGION);
        service.createMember(REGION, graphArn, MEMBER_ACCOUNT, null);

        assertEquals("ENABLED", service.startMonitoring(REGION, MEMBER_ACCOUNT, graphArn).getStatus());
        AwsException error = assertThrows(AwsException.class,
                () -> service.startMonitoring(REGION, MEMBER_ACCOUNT, graphArn));
        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void nonManagementAccountCannotDesignateAdministrator() {
        String memberCaller = MEMBER_ACCOUNT;
        Organization organization = new Organization();
        organization.setMasterAccountId(MANAGEMENT_ACCOUNT);
        when(organizationsService.describeOrganization(memberCaller)).thenReturn(organization);

        AwsException error = assertThrows(AwsException.class,
                () -> service.enableAdmin(REGION, memberCaller, MANAGEMENT_ACCOUNT));

        assertEquals("AccessDeniedException", error.getErrorCode());
        assertNull(service.state(REGION).getAdminAccountId());
    }

    @Test
    void administratorMustBelongToOrganization() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, "999999999999"));

        assertEquals("ValidationException", error.getErrorCode());
        assertNull(service.state(REGION).getAdminAccountId());
    }

    @Test
    void clearRemovesAllDetectiveState() {
        service.enableAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        assertEquals(ADMIN_ACCOUNT, service.state(REGION).getAdminAccountId());

        service.clear();

        assertNull(service.state(REGION).getAdminAccountId());
        assertFalse(service.state(REGION).isGraph());
    }
}
