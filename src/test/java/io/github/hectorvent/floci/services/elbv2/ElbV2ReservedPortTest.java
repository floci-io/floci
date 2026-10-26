package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.testing.InlineMocks;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SSLOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.servedCertificate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

        assertDoesNotThrow(() -> publishRuntime(dataPlane, listener, "us-west-2", listener.getPort(), List.of()),
                "the released-port replay re-entered the server map for the key it was computing");
        verify(vertx, times(1)).createHttpServer(any(HttpServerOptions.class));
    }

    @Test
    void leavesOrdinaryListenerPortsAlone() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, true, 443);
        assertFalse(dataPlane.isReservedByFloci(80));
        assertFalse(dataPlane.isReservedByFloci(8080));
    }
    @Test
    void identicalRestartRetriesAFailedTlsRefresh() throws Exception {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elbv2 = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        when(elbv2.mock()).thenReturn(false);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.elbv2()).thenReturn(elbv2);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        dataPlane.certificateResolver = mock(CertificateReferenceResolver.class);
        HttpServer server = mock(HttpServer.class);
        when(server.requestHandler(any())).thenReturn(server);
        when(server.listen()).thenReturn(Future.succeededFuture(server));
        when(server.close()).thenReturn(Future.succeededFuture());
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(server);
        String region = "us-west-2";
        String owner = "arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/lb/1/2";
        String certificateArn = "arn:aws:acm:us-west-2:000000000000:certificate/current";
        Listener listener = new Listener();
        listener.setListenerArn(owner);
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/lb/1");
        listener.setPort(8443);
        listener.setProtocol("HTTPS");
        listener.setCertificates(List.of(certificateArn));
        LoadBalancer loadBalancer = new LoadBalancer();
        loadBalancer.setDnsName("tls.example.test");
        when(dataPlane.elbV2Service.getLoadBalancer(region, listener.getLoadBalancerArn())).thenReturn(loadBalancer);
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate old = generator.generateSelfSignedCertificate(
                "tls.example.test", List.of(), KeyAlgorithm.RSA_2048);
        CertificateGenerator.GeneratedCertificate replacement = generator.generateSelfSignedCertificate(
                "tls.example.test", List.of(), KeyAlgorithm.RSA_2048);
        CertificateMaterial first = new CertificateMaterial(certificateArn, old.certificatePem(), old.privateKeyPem(), null);
        CertificateMaterial next = new CertificateMaterial(certificateArn,
                replacement.certificatePem(), replacement.privateKeyPem(), null);
        CertificateMaterial invalid = InlineMocks.inlineSpy(next);
        // Let pair validation succeed, then fail the key-manager load.
        AtomicInteger keyReads = new AtomicInteger();
        doAnswer(ignored -> keyReads.incrementAndGet() == 1 ? replacement.privateKeyPem() : "not a PEM private key")
                .when(invalid).privateKey();
        when(dataPlane.certificateResolver.publishedMaterial(owner, region, certificateArn)).thenReturn(first, first, invalid, invalid, next, next);
        try {
            publishRuntime(dataPlane, listener, region, listener.getPort(), List.of());
            ArgumentCaptor<HttpServerOptions> options = ArgumentCaptor.forClass(HttpServerOptions.class);
            verify(dataPlane.vertx).createHttpServer(options.capture());
            publishRuntime(dataPlane, listener, region, listener.getPort(), List.of());
            assertEquals(Pem.parseCertificate(old.certificatePem()),
                    servedCertificate(options.getValue(), "tls.example.test"));
            publishRuntime(dataPlane, listener, region, listener.getPort(), List.of());
            assertEquals(Pem.parseCertificate(replacement.certificatePem()),
                    servedCertificate(options.getValue(), "tls.example.test"));
            verify(server, never()).updateSSLOptions(any(SSLOptions.class), eq(true));
        } finally {
            dataPlane.shutdown();
        }
    }

    @Test
    void movingSecureListenerRefreshesTheSurvivingOldPort() throws Exception {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elbv2 = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        when(elbv2.mock()).thenReturn(false);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.elbv2()).thenReturn(elbv2);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        dataPlane.certificateResolver = mock(CertificateReferenceResolver.class);
        HttpServer oldServer = mock(HttpServer.class);
        HttpServer newServer = mock(HttpServer.class);
        for (HttpServer server : List.of(oldServer, newServer)) {
            when(server.requestHandler(any())).thenReturn(server);
            when(server.listen()).thenReturn(Future.succeededFuture(server));
            when(server.close()).thenReturn(Future.succeededFuture());
        }
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class)))
                .thenAnswer(invocation -> ((HttpServerOptions) invocation.getArgument(0)).getPort() == 8443
                        ? oldServer : newServer);
        CertificateGenerator generator = new CertificateGenerator();
        Listener survivor = new Listener();
        Listener moved = new Listener();
        CertificateGenerator.GeneratedCertificate survivorCertificate = null;
        for (Listener listener : List.of(survivor, moved)) {
            String name = listener == survivor ? "survivor" : "moved";
            String certificateArn = "arn:aws:acm:us-west-2:000000000000:certificate/" + name;
            listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/" + name + "/1/2");
            listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/" + name + "/1");
            listener.setPort(8443);
            listener.setProtocol("HTTPS");
            listener.setCertificates(List.of(certificateArn));
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setDnsName(name + ".example.test");
            when(dataPlane.elbV2Service.getLoadBalancer("us-west-2", listener.getLoadBalancerArn()))
                    .thenReturn(loadBalancer);
            CertificateGenerator.GeneratedCertificate certificate = generator.generateSelfSignedCertificate(
                    name + ".example.test", List.of(), KeyAlgorithm.RSA_2048);
            if (listener == survivor) {
                survivorCertificate = certificate;
            }
            when(dataPlane.certificateResolver.publishedMaterial(listener.getListenerArn(), "us-west-2", certificateArn))
                    .thenReturn(new CertificateMaterial(certificateArn,
                            certificate.certificatePem(), certificate.privateKeyPem(), null));
        }
        try {
            publishRuntime(dataPlane, survivor, "us-west-2", survivor.getPort(), List.of());
            publishRuntime(dataPlane, moved, "us-west-2", moved.getPort(), List.of());
            ArgumentCaptor<HttpServerOptions> initial = ArgumentCaptor.forClass(HttpServerOptions.class);
            verify(dataPlane.vertx).createHttpServer(initial.capture());
            assertEquals(Pem.parseCertificate(survivorCertificate.certificatePem()),
                    servedCertificate(initial.getValue(), "survivor.example.test"));
            assertFalse(servedCertificate(initial.getValue(), "moved.example.test")
                    .equals(Pem.parseCertificate(survivorCertificate.certificatePem())));

            moved.setPort(9443);
            publishRuntime(dataPlane, moved, "us-west-2", moved.getPort(), List.of());

            assertEquals(Pem.parseCertificate(survivorCertificate.certificatePem()),
                    servedCertificate(initial.getValue(), "survivor.example.test"));
            assertEquals(Pem.parseCertificate(survivorCertificate.certificatePem()),
                    servedCertificate(initial.getValue(), "moved.example.test"),
                    "the old port must fall back to its survivor after the other certificate moves away");
            verify(newServer).listen();
            verify(oldServer, never()).close();
        } finally {
            dataPlane.shutdown();
        }
    }

    @Test
    void lastListenerCloseCompletesBeforeItsPortIsReused() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elb = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(elb.mock()).thenReturn(false);
        when(services.elbv2()).thenReturn(elb);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        HttpServer oldServer = mock(HttpServer.class);
        HttpServer replacement = mock(HttpServer.class);
        Promise<Void> close = Promise.promise();
        when(oldServer.close()).thenReturn(close.future());
        when(replacement.close()).thenReturn(Future.succeededFuture());
        for (HttpServer server : List.of(oldServer, replacement)) {
            when(server.requestHandler(any())).thenReturn(server);
            when(server.listen()).thenReturn(Future.succeededFuture(server));
        }
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(oldServer, replacement);
        Listener first = new Listener();
        Listener second = new Listener();
        for (Listener listener : List.of(first, second)) {
            String name = listener == first ? "first" : "second";
            listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/" + name + "/1/2");
            listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/" + name + "/1");
            listener.setPort(8443);
            listener.setProtocol("HTTP");
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setDnsName(name + ".example.test");
            when(dataPlane.elbV2Service.getLoadBalancer("us-west-2", listener.getLoadBalancerArn()))
                    .thenReturn(loadBalancer);
        }
        try {
            publishRuntime(dataPlane, first, "us-west-2", first.getPort(), List.of());
            verify(oldServer).listen();
            removeRuntime(dataPlane, first.getListenerArn(), first.getPort());
            verify(oldServer).close();
            publishRuntime(dataPlane, second, "us-west-2", second.getPort(), List.of());
            verify(replacement, never()).listen();
            close.complete();
            verify(replacement).listen();
            removeRuntime(dataPlane, second.getListenerArn(), second.getPort());
            verify(replacement).close();
        } finally {
            close.tryComplete();
            dataPlane.shutdown();
        }
    }

    @Test
    void pendingOldPortClosePreservesAMovedListenerAndLaterPortReuse() {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elb = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(elb.mock()).thenReturn(false);
        when(services.elbv2()).thenReturn(elb);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        HttpServer oldServer = mock(HttpServer.class);
        HttpServer movedServer = mock(HttpServer.class);
        HttpServer reusedServer = mock(HttpServer.class);
        Promise<Void> oldClose = Promise.promise();
        for (HttpServer server : List.of(oldServer, movedServer, reusedServer)) {
            when(server.requestHandler(any())).thenReturn(server);
            when(server.listen()).thenReturn(Future.succeededFuture(server));
            when(server.close()).thenReturn(Future.succeededFuture());
        }
        when(oldServer.close()).thenReturn(oldClose.future());
        when(dataPlane.vertx.createHttpServer(argThat(options -> options != null && options.getPort() == 8443)))
                .thenReturn(oldServer, reusedServer);
        when(dataPlane.vertx.createHttpServer(argThat(options -> options != null && options.getPort() == 9443))).thenReturn(movedServer);
        Listener old = new Listener();
        Listener moved = new Listener();
        Listener reused = new Listener();
        for (Listener listener : List.of(old, moved, reused)) {
            String name = listener == old ? "old" : listener == moved ? "moved" : "reused";
            listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/" + name + "/1/2");
            listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/" + name + "/1");
            listener.setPort(8443);
            listener.setProtocol("HTTP");
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setDnsName(name + ".example.test");
            when(dataPlane.elbV2Service.getLoadBalancer("us-west-2", listener.getLoadBalancerArn()))
                    .thenReturn(loadBalancer);
        }
        try {
            publishRuntime(dataPlane, old, "us-west-2", old.getPort(), List.of(fixedRule("old")));
            assertFixedResponse(oldServer, "old.example.test", "old");
            removeRuntime(dataPlane, old.getListenerArn(), old.getPort());
            verify(oldServer).close();
            publishRuntime(dataPlane, moved, "us-west-2", moved.getPort(), List.of(fixedRule("pending")));
            verify(reusedServer, never()).listen();
            moved.setPort(9443);
            publishRuntime(dataPlane, moved, "us-west-2", moved.getPort(), List.of(fixedRule("moved")));
            verify(movedServer).listen();
            oldClose.complete();
            verify(reusedServer, never()).listen();
            verify(movedServer, never()).close();
            assertFixedResponse(movedServer, "moved.example.test", "moved");
            publishRuntime(dataPlane, reused, "us-west-2", reused.getPort(), List.of(fixedRule("reused")));
            verify(reusedServer).listen();
            assertFixedResponse(reusedServer, "reused.example.test", "reused");
            removeRuntime(dataPlane, reused.getListenerArn(), reused.getPort());
            verify(reusedServer).close();
            verify(movedServer, never()).close();
            removeRuntime(dataPlane, moved.getListenerArn(), moved.getPort());
            verify(movedServer).close();
        } finally {
            oldClose.tryComplete();
            dataPlane.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory", "handler", "listen", "failed-future"})
    void identicalStartRecoversFromServerAcquisitionFailure(String stage) {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elb = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(elb.mock()).thenReturn(false);
        when(services.elbv2()).thenReturn(elb);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        HttpServer failed = mock(HttpServer.class);
        HttpServer replacement = mock(HttpServer.class);
        for (HttpServer server : List.of(failed, replacement)) {
            when(server.requestHandler(any())).thenReturn(server);
            when(server.listen()).thenReturn(Future.succeededFuture(server));
            when(server.close()).thenReturn(Future.succeededFuture());
        }
        IllegalStateException failure = new IllegalStateException("controlled " + stage + " failure");
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(failed, replacement);
        switch (stage) {
            case "factory" -> when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class)))
                    .thenThrow(failure).thenReturn(replacement);
            case "handler" -> when(failed.requestHandler(any())).thenThrow(failure);
            case "listen" -> when(failed.listen()).thenThrow(failure);
            case "failed-future" -> when(failed.listen()).thenReturn(Future.failedFuture(failure));
            default -> throw new IllegalArgumentException(stage);
        }
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/retry/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/retry/1");
        listener.setPort(8443);
        listener.setProtocol("HTTP");
        LoadBalancer loadBalancer = new LoadBalancer();
        loadBalancer.setDnsName("retry.example.test");
        when(dataPlane.elbV2Service.getLoadBalancer("us-west-2", listener.getLoadBalancerArn()))
                .thenReturn(loadBalancer);
        try {
            if ("failed-future".equals(stage)) {
                assertDoesNotThrow(() -> publishRuntime(dataPlane, listener, "us-west-2", listener.getPort(), List.of(fixedRule("recovered"))));
            } else {
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> publishRuntime(dataPlane, listener, "us-west-2", listener.getPort(), List.of(fixedRule("recovered")))));
            }
            if (!"factory".equals(stage)) {
                verify(failed).close();
            }
            publishRuntime(dataPlane, listener, "us-west-2", listener.getPort(), List.of(fixedRule("recovered")));
            verify(replacement).listen();
            assertFixedResponse(replacement, "retry.example.test", "recovered");
            removeRuntime(dataPlane, listener.getListenerArn(), listener.getPort());
            verify(replacement).close();
            verify(dataPlane.vertx, times(2)).createHttpServer(any(HttpServerOptions.class));
        } finally {
            dataPlane.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void obsoleteListenFailureCannotRemoveReplacement(boolean deferClose) {
        ElbV2DataPlane dataPlane = dataPlaneWith(4566, false, 0);
        EmulatorConfig.ElbV2ServiceConfig elb = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(elb.mock()).thenReturn(false);
        when(services.elbv2()).thenReturn(elb);
        when(dataPlane.config.services()).thenReturn(services);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        HttpServer oldServer = mock(HttpServer.class);
        HttpServer replacement = mock(HttpServer.class);
        Promise<HttpServer> oldListen = Promise.promise();
        Promise<Void> oldClose = Promise.promise();
        when(oldServer.requestHandler(any())).thenReturn(oldServer);
        when(oldServer.listen()).thenReturn(oldListen.future());
        when(oldServer.close()).thenReturn(deferClose ? oldClose.future() : Future.succeededFuture());
        when(replacement.requestHandler(any())).thenReturn(replacement);
        when(replacement.listen()).thenReturn(Future.succeededFuture(replacement));
        when(replacement.close()).thenReturn(Future.succeededFuture());
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class))).thenReturn(oldServer, replacement);
        Listener first = new Listener();
        Listener second = new Listener();
        for (Listener listener : List.of(first, second)) {
            String name = listener == first ? "old" : "replacement";
            listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/" + name + "/1/2");
            listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/" + name + "/1");
            listener.setPort(8443);
            listener.setProtocol("HTTP");
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setDnsName(name + ".example.test");
            when(dataPlane.elbV2Service.getLoadBalancer("us-west-2", listener.getLoadBalancerArn()))
                    .thenReturn(loadBalancer);
        }
        try {
            publishRuntime(dataPlane, first, "us-west-2", first.getPort(), List.of());
            verify(oldServer).listen();
            removeRuntime(dataPlane, first.getListenerArn(), first.getPort());
            verify(oldServer).close();
            publishRuntime(dataPlane, second, "us-west-2", second.getPort(), List.of(fixedRule("replacement")));
            if (deferClose) {
                verify(replacement, never()).listen();
                oldClose.complete();
            }
            verify(replacement).listen();
            oldListen.fail(new IllegalStateException("obsolete listen failure"));
            verify(replacement, never()).close();
            assertFixedResponse(replacement, "replacement.example.test", "replacement");
            publishRuntime(dataPlane, second, "us-west-2", second.getPort(), List.of(fixedRule("replacement")));
            verify(dataPlane.vertx, times(2)).createHttpServer(any(HttpServerOptions.class));
            removeRuntime(dataPlane, second.getListenerArn(), second.getPort());
            verify(replacement).close();
        } finally {
            oldClose.tryComplete();
            oldListen.tryFail(new IllegalStateException("test cleanup"));
            dataPlane.shutdown();
        }
    }

    private static Rule fixedRule(String body) {
        Action action = new Action();
        action.setType("fixed-response");
        action.setFixedResponseStatusCode("200");
        action.setFixedResponseMessageBody(body);
        Rule rule = new Rule();
        rule.setDefault(true);
        rule.setActions(List.of(action));
        return rule;
    }

    private static void assertFixedResponse(HttpServer server, String host, String body) {
        ArgumentCaptor<Handler<HttpServerRequest>> handler = ArgumentCaptor.captor();
        verify(server).requestHandler(handler.capture());
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(request.host()).thenReturn(host);
        when(request.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        handler.getValue().handle(request);
        verify(response).setStatusCode(200);
        verify(response).end(body);
    }

}
