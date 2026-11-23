package io.floci.aws.services.dynamodb.backend;

import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The backend selection's startup checks. */
class DynamoDbBackendSelectorTest {

    // Catches: transaction conflicts asked for over DynamoDB Local, which applies its own transactions, starting
    // without them instead of failing at startup.
    @Test
    void transactionConflictsNeedTheNativeBackend() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dynamodb().backend()).thenReturn("Local");
        when(config.services().dynamodb().transactionConflictsEnabled()).thenReturn(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new DynamoDbBackendSelector(config, mock(NativeDynamoDbBackend.class),
                        mock(DynamoDbLocalBackend.class)));

        assertTrue(refused.getMessage().contains("transaction-conflicts-enabled"), refused.getMessage());
    }

    @Test
    void transactionConflictsRunOnTheNativeBackend() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dynamodb().backend()).thenReturn("native");
        when(config.services().dynamodb().transactionConflictsEnabled()).thenReturn(true);

        assertEquals("native", new DynamoDbBackendSelector(config, mock(NativeDynamoDbBackend.class),
                mock(DynamoDbLocalBackend.class)).selected());
    }
}
