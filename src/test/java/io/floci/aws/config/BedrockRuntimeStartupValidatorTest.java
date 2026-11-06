package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockRuntimeStartupValidatorTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void nonPositiveErrorBodyTimeoutIsRejectedByStartupObserver(int value) {
        BedrockRuntimeStartupValidator validator = validator(value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.onStart(new StartupEvent()));
        assertEquals("floci.services.bedrock-runtime.proxy.error-body-timeout-millis must be positive",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 1000, Integer.MAX_VALUE})
    void positiveErrorBodyTimeoutIsAcceptedByStartupObserver(int value) {
        BedrockRuntimeStartupValidator validator = validator(value);
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    private static BedrockRuntimeStartupValidator validator(int value) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().bedrockRuntime().proxy().errorBodyTimeoutMillis()).thenReturn(value);
        return new BedrockRuntimeStartupValidator(config);
    }
}
