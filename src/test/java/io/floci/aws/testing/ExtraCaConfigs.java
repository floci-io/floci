package io.floci.aws.testing;

import io.floci.aws.config.EmulatorConfig;

import java.util.Optional;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deep-stubbed {@link EmulatorConfig}s with the TLS settings Floci's outbound trust reads, for the extra-CA
 * trust tests.
 */
public final class ExtraCaConfigs {

    private ExtraCaConfigs() {
    }

    /**
     * A deep-stubbed config with TLS on or off as {@code tlsEnabled} says and {@code extraCaPath} as
     * {@code floci.tls.extra-ca-path}.
     */
    public static EmulatorConfig config(boolean tlsEnabled, Optional<String> extraCaPath) {
        return stub(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), tlsEnabled, extraCaPath);
    }

    /** {@code config}, a deep-stubbed mock such as {@link StorageFactories#config} returns, with those TLS settings. */
    public static EmulatorConfig stub(EmulatorConfig config, boolean tlsEnabled, Optional<String> extraCaPath) {
        when(config.tls().enabled()).thenReturn(tlsEnabled);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        return config;
    }
}
