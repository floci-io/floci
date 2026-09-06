package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Inspector2ServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";
    private static final String MEMBER_ACCOUNT = "333333333333";
    private static final String OUTSIDE_ACCOUNT = "444444444444";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Inspector2Service service;

    @BeforeEach
    void setUp() {
        OrganizationsService organizations = mock(OrganizationsService.class);
        when(organizations.findManagementAccountForResource(MANAGEMENT_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(ADMIN_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(MEMBER_ACCOUNT))
                .thenReturn(Optional.of(MANAGEMENT_ACCOUNT));
        when(organizations.findManagementAccountForResource(OUTSIDE_ACCOUNT))
                .thenReturn(Optional.of(OUTSIDE_ACCOUNT));
        service = new Inspector2Service(
                AccountAwareStorageBackend.<InspectorState>inMemory(MANAGEMENT_ACCOUNT), organizations);
    }

    @Test
    void onlyManagementAccountCanDesignateDelegatedAdministrator() {
        AwsException denied = assertThrows(AwsException.class,
                () -> service.enableDelegatedAdmin(REGION, MEMBER_ACCOUNT, ADMIN_ACCOUNT));
        assertEquals("AccessDeniedException", denied.getErrorCode());

        AwsException outsider = assertThrows(AwsException.class,
                () -> service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, OUTSIDE_ACCOUNT));
        assertEquals("ResourceNotFoundException", outsider.getErrorCode());
    }

    @Test
    void delegatedAdministratorOwnsOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        InspectorState state = service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"codeRepository\":true}}"));

        assertTrue(state.isAutoEnableEc2());
        assertTrue(state.isAutoEnableEcr());
        assertTrue(state.isAutoEnableCodeRepository());
    }

    @Test
    void failedOrganizationConfigurationUpdateIsAtomic() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                objectMapper.readTree("{\"autoEnable\":{\"ec2\":false,\"ecr\":false}}"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                        objectMapper.readTree("{\"autoEnable\":{\"ec2\":true}}")));

        assertEquals("ValidationException", error.getErrorCode());
        InspectorState current = service.organizationConfiguration(REGION, ADMIN_ACCOUNT);
        assertFalse(current.isAutoEnableEc2());
        assertFalse(current.isAutoEnableEcr());
    }

    @Test
    void delegatedAdministratorEnablesMemberAndOnlyRequestedResourceTypes() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        service.enable(REGION, ADMIN_ACCOUNT, objectMapper.readTree(
                "{\"accountIds\":[\"" + MEMBER_ACCOUNT + "\"],\"resourceTypes\":[\"EC2\"]}"));

        InspectorState first = service.accountStatus(REGION, ADMIN_ACCOUNT, MEMBER_ACCOUNT);
        assertEquals("ENABLING", first.getStatus());
        assertEquals("ENABLING", first.getEc2Status());
        assertEquals("DISABLED", first.getEcrStatus());
        assertEquals("DISABLED", first.getLambdaStatus());
        InspectorState converged = service.accountStatus(REGION, ADMIN_ACCOUNT, MEMBER_ACCOUNT);
        assertEquals("ENABLED", converged.getStatus());
        assertEquals("ENABLED", converged.getEc2Status());
        assertEquals("DISABLED", converged.getEcrStatus());
    }

    @Test
    void memberMayEnableItselfButCannotManageAnotherAccount() throws Exception {
        service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree(
                "{\"resourceTypes\":[\"ECR\"]}"));
        assertEquals("ENABLING", service.accountStatus(REGION, MEMBER_ACCOUNT, MEMBER_ACCOUNT).getEcrStatus());

        AwsException denied = assertThrows(AwsException.class,
                () -> service.enable(REGION, MEMBER_ACCOUNT, objectMapper.readTree(
                        "{\"accountIds\":[\"" + ADMIN_ACCOUNT + "\"],\"resourceTypes\":[\"EC2\"]}")));
        assertEquals("AccessDeniedException", denied.getErrorCode());
    }

    @Test
    void managementAccountCannotUpdateOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT,
                        objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true}}")));

        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void disableRequiresManagementAndCurrentAdministrator() {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);

        AwsException denied = assertThrows(AwsException.class,
                () -> service.disableDelegatedAdmin(REGION, ADMIN_ACCOUNT, ADMIN_ACCOUNT));
        assertEquals("AccessDeniedException", denied.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> service.disableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, MEMBER_ACCOUNT));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void clearRemovesState() {
        service.enableDelegatedAdmin(REGION, MANAGEMENT_ACCOUNT, ADMIN_ACCOUNT);
        service.clear();

        assertNull(service.state(REGION).getAdminAccountId());
    }
}
