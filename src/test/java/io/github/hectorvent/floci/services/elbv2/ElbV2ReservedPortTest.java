package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A load balancer listener must not bind a port Floci needs for itself, or the two race and
 * whichever starts first wins - see {@link ElbV2DataPlane#isReservedByFloci(int)}.
 */
class ElbV2ReservedPortTest {

    private static ElbV2DataPlane dataPlaneWith(int flociPort, boolean tlsEnabled, int awsHttpsPort) {
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.awsHttpsPort()).thenReturn(awsHttpsPort);

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.port()).thenReturn(flociPort);
        when(config.tls()).thenReturn(tls);

        // Mirrors TlsProxyServer.reservesPort for the configured case; the bind-outcome half of
        // that contract is covered by TlsProxyServerReservedPortTest.
        TlsProxyServer proxy = mock(TlsProxyServer.class);
        when(proxy.reservesPort(anyInt())).thenAnswer(inv ->
                tlsEnabled && awsHttpsPort > 0 && (int) inv.getArgument(0) == awsHttpsPort);

        ElbV2DataPlane dataPlane = new ElbV2DataPlane();
        dataPlane.config = config;
        dataPlane.tlsProxyServer = proxy;
        return dataPlane;
    }

    @Test
    void reservesTheEmulatorsOwnPortEvenWithTlsOff() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 443);
        assertTrue(dataPlane.isReservedByFloci(4566));
    }

    @Test
    void reservesTheAwsHttpsPortWhenTlsIsOn() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, true, 443);
        assertTrue(dataPlane.isReservedByFloci(443),
                "with TLS on, 443 carries CDK's cfn-response callback");
    }

    @Test
    void leaves443ToLoadBalancersWhenTlsIsOff() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 443);
        assertFalse(dataPlane.isReservedByFloci(443));
    }

    @Test
    void leaves443ToLoadBalancersWhenTheAwsHttpsPortIsDisabled() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, true, 0);
        assertFalse(dataPlane.isReservedByFloci(443),
                "aws-https-port=0 is the documented escape hatch");
    }

    @Test
    void leavesOrdinaryListenerPortsAlone() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, true, 443);
        assertFalse(dataPlane.isReservedByFloci(80));
        assertFalse(dataPlane.isReservedByFloci(8080));
    }
}
