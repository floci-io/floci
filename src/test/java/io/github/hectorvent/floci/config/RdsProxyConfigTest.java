package io.github.hectorvent.floci.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RdsProxyConfigTest {

    @Inject
    EmulatorConfig config;

    @Test
    void rdsProxyPortRangeHasDefaults() {
        assertEquals(7001, config.services().rds().proxyBasePort());
        assertEquals(7099, config.services().rds().proxyMaxPort());
    }

    @Test
    void rdsProxyBoundsHaveDefaults() {
        assertEquals(10000, config.services().rds().proxyHandshakeTimeoutMillis());
        assertEquals(5000, config.services().rds().proxyBackendConnectTimeoutMillis());
        assertEquals(100, config.services().rds().proxyMaxConnections());
    }
}
