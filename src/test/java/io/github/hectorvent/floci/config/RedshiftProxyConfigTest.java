package io.github.hectorvent.floci.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RedshiftProxyConfigTest {

    @Inject
    EmulatorConfig config;

    @Test
    void redshiftProxyPortRangeHasDefaults() {
        assertEquals(7100, config.services().redshift().proxyBasePort());
        assertEquals(7199, config.services().redshift().proxyMaxPort());
    }

    @Test
    void redshiftEndpointHostDefaultsToEmpty() {
        assertEquals(Optional.empty(), config.services().redshift().endpointHost());
    }

    @Test
    void redshiftProxyBoundsHaveDefaults() {
        assertEquals(10000, config.services().redshift().proxyHandshakeTimeoutMillis());
        assertEquals(5000, config.services().redshift().proxyBackendConnectTimeoutMillis());
        assertEquals(100, config.services().redshift().proxyMaxConnections());
    }
}
