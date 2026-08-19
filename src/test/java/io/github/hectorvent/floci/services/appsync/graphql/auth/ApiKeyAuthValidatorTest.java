package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthValidatorTest {

    @Mock
    AppSyncService appSyncService;

    @Test
    void validKeyReturnsStoredKey() {
        ApiKey stored = new ApiKey();
        stored.setApiKey("da2-abc");
        when(appSyncService.validateApiKey("api-1", "da2-abc")).thenReturn(Optional.of(stored));

        ApiKey result = new ApiKeyAuthValidator(appSyncService).validate("api-1", "da2-abc");

        assertEquals("da2-abc", result.getApiKey());
    }

    @Test
    void unknownKeyThrows401() {
        when(appSyncService.validateApiKey("api-1", "da2-missing")).thenReturn(Optional.empty());

        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> new ApiKeyAuthValidator(appSyncService).validate("api-1", "da2-missing"));
        assertEquals(401, ex.getHttpStatus());
        assertEquals("UnauthorizedException", ex.getErrorType());
        assertEquals("You are not authorized to make this call.", ex.getMessage());
    }
}
