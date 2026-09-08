package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EmulatorConfig#iotEndpointAddress()}: the configured address when there is one,
 * otherwise the host and port of the effective base URL.
 */
class EmulatorConfigIotEndpointAddressTest {

    private static EmulatorConfig config(String effectiveBaseUrl, Optional<String> endpointAddress) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn(effectiveBaseUrl);
        when(config.services().iot().endpointAddress()).thenReturn(endpointAddress);
        when(config.iotEndpointAddress()).thenCallRealMethod();
        return config;
    }

    @Test
    void unsetFallsBackToTheHostAndPortOfTheBaseUrl() {
        assertEquals("localhost:4566", config("http://localhost:4566", Optional.empty()).iotEndpointAddress());
    }

    @Test
    void fallbackDropsSchemeAndPath() {
        assertEquals("floci:4566", config("https://floci:4566/", Optional.empty()).iotEndpointAddress());
    }

    @Test
    void configuredAddressIsReturnedVerbatim() {
        assertEquals("iot.example.localhost.floci.io",
                config("http://localhost:4566", Optional.of("iot.example.localhost.floci.io")).iotEndpointAddress());
    }

    @Test
    void configuredAddressKeepsAPortTheOperatorAdded() {
        assertEquals("iot.example.localhost.floci.io:8443",
                config("http://localhost:4566", Optional.of("iot.example.localhost.floci.io:8443")).iotEndpointAddress());
    }

    @Test
    void surroundingWhitespaceIsDropped() {
        assertEquals("iot.example.localhost.floci.io",
                config("http://localhost:4566", Optional.of("  iot.example.localhost.floci.io\n")).iotEndpointAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void blankAddressCountsAsUnset(String blank) {
        assertEquals("localhost:4566", config("http://localhost:4566", Optional.of(blank)).iotEndpointAddress());
    }
}
