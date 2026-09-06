package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Inspector2ServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Inspector2Service service;

    @BeforeEach
    void setUp() {
        service = new Inspector2Service(AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));
    }

    @Test
    void delegatedAdministratorOwnsOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, ADMIN_ACCOUNT);

        InspectorState state = service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT,
                objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"codeRepository\":true}}"));

        assertTrue(state.isAutoEnableEc2());
        assertTrue(state.isAutoEnableEcr());
        assertTrue(state.isAutoEnableCodeRepository());
    }

    @Test
    void managementAccountCannotUpdateOrganizationConfiguration() throws Exception {
        service.enableDelegatedAdmin(REGION, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT,
                        objectMapper.readTree("{\"autoEnable\":{\"ec2\":true,\"ecr\":true}}")));

        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void disableRequiresCurrentAdministrator() {
        service.enableDelegatedAdmin(REGION, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.disableDelegatedAdmin(REGION, "333333333333"));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void clearRemovesState() {
        service.enableDelegatedAdmin(REGION, ADMIN_ACCOUNT);
        service.clear();

        assertNull(service.state(REGION).getAdminAccountId());
    }
}
