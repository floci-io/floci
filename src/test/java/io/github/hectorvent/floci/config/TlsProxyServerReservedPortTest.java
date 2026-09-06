package io.github.hectorvent.floci.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The proxy only reserves a port it holds or still expects to hold. Binding the AWS-HTTPS port is
 * privileged and its failure is non-fatal, so a port the proxy never acquired has to stay
 * available: anything that yields to the proxy on configuration alone would leave it unserved.
 */
class TlsProxyServerReservedPortTest {

    private static TlsProxyServer proxyWith(int flociPort, boolean tlsEnabled, int awsHttpsPort) {
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.awsHttpsPort()).thenReturn(awsHttpsPort);

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.port()).thenReturn(flociPort);
        when(config.tls()).thenReturn(tls);

        // The listen() future is left uncompleted on purpose: that is the "bind outcome not yet
        // known" state, which must still reserve the port. Tests that need a resolved outcome
        // stage it directly on failedPorts.
        NetServer server = mock(NetServer.class);
        when(server.connectHandler(any())).thenReturn(server);
        when(server.listen()).thenReturn(Promise.<NetServer>promise().future());

        Vertx vertx = mock(Vertx.class);
        when(vertx.createNetClient()).thenReturn(mock(NetClient.class));
        when(vertx.createNetServer(any())).thenReturn(server);

        return new TlsProxyServer(vertx, config, 4510, 4511);
    }

    /**
     * Variant that resolves listen() to {@code outcome} and lets the caller register a handler
     * before construction, since the bind callback fires during the constructor.
     */
    private static TlsProxyServer proxyWith(int flociPort, boolean tlsEnabled, int awsHttpsPort,
                                            Future<NetServer> outcome,
                                            java.util.function.Consumer<TlsProxyServer> beforeStart) {
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.awsHttpsPort()).thenReturn(awsHttpsPort);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.port()).thenReturn(flociPort);
        when(config.tls()).thenReturn(tls);

        NetServer server = mock(NetServer.class);
        when(server.connectHandler(any())).thenReturn(server);
        when(server.listen()).thenReturn(outcome);

        Vertx vertx = mock(Vertx.class);
        when(vertx.createNetClient()).thenReturn(mock(NetClient.class));
        when(vertx.createNetServer(any())).thenReturn(server);

        TlsProxyServer proxy = new TlsProxyServer(vertx, config, 4510, 4511);
        beforeStart.accept(proxy);
        return proxy;
    }

    @Test
    void reservesTheAwsHttpsPortBeforeTheBindOutcomeIsKnown() {
        TlsProxyServer proxy = proxyWith(4566, true, 443);
        assertTrue(proxy.reservesPort(443),
                "an unresolved bind must still hold the port, or a listener races the proxy for it");
    }

    @Test
    void releasesTheAwsHttpsPortOnceItsBindHasFailed() {
        TlsProxyServer proxy = proxyWith(4566, true, 443);
        proxy.failedPorts.add(443);
        assertFalse(proxy.reservesPort(443),
                "a port the proxy failed to bind must stay available to load balancer listeners");
    }

    @Test
    void reservesNothingWhenTlsIsDisabled() {
        TlsProxyServer proxy = proxyWith(4566, false, 443);
        assertFalse(proxy.reservesPort(443));
    }

    @Test
    void doesNotReserveTheAwsHttpsPortWhenItIsDisabled() {
        TlsProxyServer proxy = proxyWith(4566, true, 0);
        assertFalse(proxy.reservesPort(443));
    }

    @Test
    void notifiesHandlersWhenABindFails() {
        List<Integer> released = new ArrayList<>();
        // listen() resolving to a failure is what happens when the privileged port is refused.
        TlsProxyServer proxy = proxyWith(4566, true, 443,
                Future.failedFuture(new RuntimeException("permission denied")),
                p -> p.onPortReleased(released::add));

        assertTrue(released.contains(443),
                "a listener that yielded 443 has no other way to learn the bind failed");
        assertFalse(proxy.reservesPort(443));
    }

    @Test
    void doesNotReserveAnUnrelatedPort() {
        TlsProxyServer proxy = proxyWith(4566, true, 443);
        assertFalse(proxy.reservesPort(8080));
    }
}
