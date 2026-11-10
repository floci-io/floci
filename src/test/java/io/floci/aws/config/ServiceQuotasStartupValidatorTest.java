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

class ServiceQuotasStartupValidatorTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeQuotaIsRejectedByStartupObserver(int value) {
        ServiceQuotasStartupValidator validator = validator(value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.onStart(new StartupEvent()));
        assertEquals("floci.services.servicequotas.standard-on-demand-vcpus must be nonnegative",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 7, Integer.MAX_VALUE})
    void nonnegativeQuotaIsAcceptedByStartupObserver(int value) {
        ServiceQuotasStartupValidator validator = validator(value);
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    private static ServiceQuotasStartupValidator validator(int value) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(value);
        return new ServiceQuotasStartupValidator(config);
    }
}
