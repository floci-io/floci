package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHubServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecurityHubService service;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = new SecurityHubService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), regionResolver);
    }

    @Test
    void enableSecurityHubPersistsConfigurationAndHubTags() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("""
                {
                  "ControlFindingGenerator": "STANDARD_CONTROL",
                  "Tags": {"env": "test"}
                }
                """));

        SecurityHubState state = service.state(REGION);
        assertTrue(state.isEnabled());
        assertEquals("STANDARD_CONTROL", state.getControlFindingGenerator());
        assertEquals(Map.of("env", "test"), service.tagsForResource(REGION, service.hubArn(REGION)));
    }

    @Test
    void invalidReservedTagPrefixIsRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.enableSecurityHub(REGION,
                objectMapper.readTree("{\"Tags\":{\"aws:owner\":\"test\"}}")));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void invalidAdministratorFeatureIsRejected() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "111111111111", "Other"));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void clearRemovesState() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{}"));
        assertTrue(service.state(REGION).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
    }
}
