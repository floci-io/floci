package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    /**
     * The proxy gave up the port before any listener existed, so the data plane subscribes and is
     * replayed that port in the same breath. The replay rebinds listeners waiting on the port,
     * which reaches back into the same server map: doing the subscribing from inside the map's
     * own mapping function re-entered computeIfAbsent for the key it was still computing, and
     * ConcurrentHashMap answers that with IllegalStateException: Recursive update.
     */
    @Test
    void aListenerStartsOnAPortTheProxyGaveUpBeforeItSubscribed() {
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(443);
        EmulatorConfig.ElbV2ServiceConfig elbv2 = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        when(elbv2.mock()).thenReturn(false);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.elbv2()).thenReturn(elbv2);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(config.services()).thenReturn(services);

        // 443 is already in failedPorts, so subscribing replays it synchronously, exactly as
        // TlsProxyServer.onPortReleased does.
        TlsProxyServer proxy = mock(TlsProxyServer.class);
        when(proxy.reservesPort(anyInt())).thenReturn(false);
        doAnswer(inv -> {
            inv.getArgument(0, IntConsumer.class).accept(443);
            return null;
        }).when(proxy).onPortReleased(any());

        HttpServer server = mock(HttpServer.class);
        when(server.requestHandler(any())).thenReturn(server);
        // Left uncompleted: the listen outcome is not what this test is about.
        when(server.listen()).thenReturn(Promise.<HttpServer>promise().future());
        Vertx vertx = mock(Vertx.class);
        when(vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(server);

        ElbV2DataPlane dataPlane = new ElbV2DataPlane();
        dataPlane.config = config;
        dataPlane.tlsProxyServer = proxy;
        dataPlane.vertx = vertx;
        dataPlane.elbV2Service = mock(ElbV2Service.class);

        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/lb/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/lb/1");
        listener.setPort(443);

        assertDoesNotThrow(() -> dataPlane.startListener(listener, "us-west-2", List.of()),
                "the released-port replay re-entered the server map for the key it was computing");
        verify(vertx, times(1)).createHttpServer(any(HttpServerOptions.class));
    }

    @Test
    void leavesOrdinaryListenerPortsAlone() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, true, 443);
        assertFalse(dataPlane.isReservedByFloci(80));
        assertFalse(dataPlane.isReservedByFloci(8080));
    }
}
