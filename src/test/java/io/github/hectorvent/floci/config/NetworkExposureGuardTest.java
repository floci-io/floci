package io.github.hectorvent.floci.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkExposureGuardTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
    }

    private static EmulatorConfig.SecurityConfig security(boolean allowExposure) {
        EmulatorConfig.SecurityConfig security = mock(EmulatorConfig.SecurityConfig.class);
        when(security.allowUnsafeNetworkExposure()).thenReturn(allowExposure);
        return security;
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.10.0.3", "::1", "[::1]", "localhost", "LOCALHOST"})
    void loopbackHostNeedsNoConsent(String host) {
        assertDoesNotThrow(() -> NetworkExposureGuard.requireConsent(host, security(false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.168.1.20", "172.17.0.1", "floci.internal"})
    void nonLoopbackHostWithoutConsentNamesTheSettingToChange(String host) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NetworkExposureGuard.requireConsent(host, security(false)));

        assertTrue(e.getMessage().contains(host), e.getMessage());
        assertTrue(e.getMessage().contains("floci.security.allow-unsafe-network-exposure"), e.getMessage());
        assertTrue(e.getMessage().contains("FLOCI_SECURITY_ALLOW_UNSAFE_NETWORK_EXPOSURE"), e.getMessage());
    }

    @Test
    void nonLoopbackHostWithConsentIsAllowed() {
        assertDoesNotThrow(() -> NetworkExposureGuard.requireConsent("0.0.0.0", security(true)));
    }

    @Test
    void startupRefusesMalformedHostEvenWithNetworkExposureConsent() {
        Config config = mock(Config.class);
        ConfigValue host = mock(ConfigValue.class);
        when(host.getValue()).thenReturn("999.999.999.999");
        when(config.getConfigValue("quarkus.http.host")).thenReturn(host);
        EmulatorConfig emulatorConfig = mock(EmulatorConfig.class);
        EmulatorConfig.SecurityConfig security = security(true);
        when(emulatorConfig.security()).thenReturn(security);

        NetworkExposureGuard guard = new NetworkExposureGuard(emulatorConfig, config);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard.onStart(null));

        assertTrue(exception.getMessage().contains("malformed"), exception.getMessage());
    }

    @Test
    void publicHostIsTheConfiguredHost() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("quarkus.http.host", "127.0.0.1"), "yml", 255))
                .withSources(new PropertiesConfigSource(Map.of("quarkus.http.host", "0.0.0.0"), "sysprops", 400))
                .build();

        assertEquals("0.0.0.0", NetworkExposureGuard.publicHost(config));
    }

    @Test
    void publicHostIgnoresTheLoopbackPinTlsModeAppliesToQuarkus() {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new TlsConfigSource())
                .withSources(new PropertiesConfigSource(Map.of("quarkus.http.host", "0.0.0.0"), "yml", 255))
                .build();

        assertEquals("127.0.0.1", config.getValue("quarkus.http.host", String.class),
                "precondition: TLS mode keeps Quarkus itself on loopback behind the proxy");
        assertEquals("0.0.0.0", NetworkExposureGuard.publicHost(config),
                "the proxy must listen where the user asked, not on the internal pin");
    }
}
