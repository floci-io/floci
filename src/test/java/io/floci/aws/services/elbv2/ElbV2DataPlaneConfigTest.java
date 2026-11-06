package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2DataPlaneConfigTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeBackendKeepAliveTimeoutNamesTheInvalidSetting(int seconds) {
        ElbV2DataPlane plane = plane(seconds);
        plane.vertx = mock(Vertx.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, plane::init);

        assertEquals("floci.services.elbv2.backend-keep-alive-timeout-seconds must be non-negative",
                error.getMessage());
        verifyNoInteractions(plane.vertx);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 60})
    void backendClientUsesConfiguredKeepAliveTimeout(int seconds) {
        ElbV2DataPlane plane = plane(seconds);
        plane.vertx = mock(Vertx.class);

        plane.init();

        ArgumentCaptor<HttpClientOptions> options = ArgumentCaptor.forClass(HttpClientOptions.class);
        verify(plane.vertx).createHttpClient(options.capture());
        assertEquals(seconds, options.getValue().getKeepAliveTimeout());
    }

    private static ElbV2DataPlane plane(int seconds) {
        ElbV2DataPlane plane = new ElbV2DataPlane();
        plane.config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(plane.config.services().elbv2().backendKeepAliveTimeoutSeconds()).thenReturn(seconds);
        return plane;
    }
}
