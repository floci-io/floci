package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacieServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";

    private MacieService service;

    @BeforeEach
    void setUp() {
        service = new MacieService(AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));
    }

    @Test
    void delegationEnablesMacieForAdministratorAccount() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        MacieState delegated = service.requireAdministratorSession(REGION, ADMIN_ACCOUNT);
        assertTrue(delegated.isEnabled());
        assertEquals(ADMIN_ACCOUNT, delegated.getAdminAccountId());
    }

    @Test
    void managementAccountCannotUpdateAdministratorConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.enableMacie(REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT, true));
        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void delegatedAdministratorCanUpdateConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT, true);

        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isAutoEnable());
    }

    @Test
    void conflictingAdministratorDesignationIsRejected() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "333333333333"));
        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void clearRemovesMacieState() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
        AwsException error = assertThrows(AwsException.class,
                () -> service.requireAdministratorSession(REGION, ADMIN_ACCOUNT));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }
}
