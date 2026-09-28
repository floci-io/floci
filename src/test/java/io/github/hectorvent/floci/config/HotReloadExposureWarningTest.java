package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotReloadExposureWarningTest {

    private static EmulatorConfig configWith(boolean enabled, List<String> allowedPaths) {
        EmulatorConfig.LambdaServiceConfig.HotReload hotReload =
                mock(EmulatorConfig.LambdaServiceConfig.HotReload.class);
        when(hotReload.enabled()).thenReturn(enabled);
        when(hotReload.allowedPaths()).thenReturn(Optional.ofNullable(allowedPaths));
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(lambda.hotReload()).thenReturn(hotReload);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.lambda()).thenReturn(lambda);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(services);
        return config;
    }

    @Test
    void warnsWhenHotReloadIsEnabledWithNoAllowList() {
        assertTrue(HotReloadExposureWarning.hotReloadAcceptsAnyPath(configWith(true, null)));
    }

    @Test
    void staysQuietWhenAnAllowListIsSet() {
        assertFalse(HotReloadExposureWarning.hotReloadAcceptsAnyPath(configWith(true, List.of("/home/ci/code"))));
    }

    @Test
    void staysQuietWhenHotReloadIsDisabled() {
        assertFalse(HotReloadExposureWarning.hotReloadAcceptsAnyPath(configWith(false, null)));
    }
}
