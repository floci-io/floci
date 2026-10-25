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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.servedCertificate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2TlsCohortTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMaterialPublicationKeepsSharedAliasesAmbiguous(boolean pauseOptionsCapture) throws Exception {
        String region = "us-west-2";
        String certificateArn = "arn:aws:acm:us-west-2:000000000000:certificate/shared";
        String alias = "replacement.example.test";
        ElbV2DataPlane dataPlane = new ElbV2DataPlane();
        dataPlane.config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElbV2ServiceConfig elb = mock(EmulatorConfig.ElbV2ServiceConfig.class);
        when(dataPlane.config.services()).thenReturn(services);
        when(services.elbv2()).thenReturn(elb);
        when(elb.mock()).thenReturn(false);
        when(dataPlane.config.port()).thenReturn(4566);
        dataPlane.tlsProxyServer = mock(TlsProxyServer.class);
        when(dataPlane.tlsProxyServer.reservesPort(anyInt())).thenReturn(false);
        dataPlane.vertx = mock(Vertx.class);
        dataPlane.elbV2Service = mock(ElbV2Service.class);
        dataPlane.certificateResolver = mock(CertificateReferenceResolver.class);
        HttpServer server = mock(HttpServer.class);
        HttpServer otherServer = mock(HttpServer.class);
        when(otherServer.requestHandler(any())).thenReturn(otherServer);
        when(otherServer.listen()).thenReturn(Future.succeededFuture(otherServer));
        when(otherServer.close()).thenReturn(Future.succeededFuture());
        when(dataPlane.vertx.createHttpServer(any(HttpServerOptions.class))).thenAnswer(call ->
                ((HttpServerOptions) call.getArgument(0)).getPort() == 9443 ? otherServer : server);
        when(server.requestHandler(any())).thenReturn(server);
        when(server.listen()).thenReturn(Future.succeededFuture(server));
        when(server.close()).thenReturn(Future.succeededFuture());
        CertificateGenerator generator = new CertificateGenerator();
        CertificateMaterial seed = material(generator, certificateArn, "seed.example.test");
        CertificateMaterial first = InlineMocks.inlineSpy(material(generator, certificateArn, "old.example.test"));
        CertificateMaterial replacement = material(generator, certificateArn, alias);
        Listener left = listener("left", certificateArn);
        Listener right = listener("right", certificateArn);
        Listener unrelated = listener("unrelated", certificateArn);
        unrelated.setPort(9443);
        AtomicReference<CertificateMaterial> leftMaterial = new AtomicReference<>(seed);
        AtomicReference<CertificateMaterial> rightMaterial = new AtomicReference<>(seed);
        when(dataPlane.certificateResolver.publishedMaterial(left.getListenerArn(), region, certificateArn))
                .thenAnswer(ignored -> leftMaterial.get());
        when(dataPlane.certificateResolver.publishedMaterial(right.getListenerArn(), region, certificateArn))
                .thenAnswer(ignored -> rightMaterial.get());
        when(dataPlane.certificateResolver.publishedMaterial(unrelated.getListenerArn(), region, certificateArn))
                .thenReturn(seed);
        for (Listener listener : List.of(left, right, unrelated)) {
            LoadBalancer loadBalancer = new LoadBalancer();
            loadBalancer.setDnsName(listener == left ? "left.example.test" : "right.example.test");
            when(dataPlane.elbV2Service.getLoadBalancer(region, listener.getLoadBalancerArn())).thenReturn(loadBalancer);
        }
        CountDownLatch insideSecondPeer = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondResolved = new CountDownLatch(1);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger keyReads = new AtomicInteger();
        AtomicBoolean paused = new AtomicBoolean();
        // Pair validation reads key/body once before the coordinated cohort/SSL-options reads.
        if (pauseOptionsCapture) {
            doAnswer(invocation -> {
                if (Thread.currentThread() == firstWorker.get() && keyReads.incrementAndGet() == 2
                        && paused.compareAndSet(false, true)) {
                    insideSecondPeer.countDown();
                    assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "options capture was not released");
                }
                return invocation.callRealMethod();
            }).when(first).privateKey();
        } else {
            doAnswer(invocation -> {
                if (Thread.currentThread() == firstWorker.get() && reads.incrementAndGet() == 4
                        && paused.compareAndSet(false, true)) {
                    insideSecondPeer.countDown();
                    assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "first publisher was not released");
                }
                return invocation.callRealMethod();
            }).when(first).certificateBody();
        }
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            publishRuntime(dataPlane, left, region, left.getPort(), List.of(fixedRule("left")));
            publishRuntime(dataPlane, right, region, right.getPort(), List.of(fixedRule("right")));
            ArgumentCaptor<HttpServerOptions> options = ArgumentCaptor.forClass(HttpServerOptions.class);
            verify(dataPlane.vertx).createHttpServer(options.capture());
            ArgumentCaptor<Handler<HttpServerRequest>> handler = ArgumentCaptor.captor();
            verify(server).requestHandler(handler.capture());
            leftMaterial.set(first);
            rightMaterial.set(replacement);
            when(dataPlane.certificateResolver.publishedMaterial(right.getListenerArn(), region, certificateArn))
                    .thenAnswer(ignored -> {
                        secondResolved.countDown();
                        return rightMaterial.get();
                    });
            // Qualify the JDK Future to distinguish it from Vert.x Future.
            java.util.concurrent.Future<?> firstPublication = workers.submit(() -> {
                firstWorker.set(Thread.currentThread());
                publishRuntime(dataPlane, left, region, left.getPort(), List.of(fixedRule("left")));
            });
            assertTrue(insideSecondPeer.await(5, TimeUnit.SECONDS), "publisher did not reach the controlled cohort boundary");
            java.util.concurrent.Future<?> secondPublication = workers.submit(() ->
                    publishRuntime(dataPlane, right, region, right.getPort(), List.of(fixedRule("right"))));
            assertTrue(secondResolved.await(5, TimeUnit.SECONDS), "second publisher did not resolve its material");
            workers.submit(() -> publishRuntime(dataPlane, unrelated, region, unrelated.getPort(), List.of(fixedRule("unrelated"))))
                    .get(1, TimeUnit.SECONDS);
            verify(otherServer).listen();
            try {
                secondPublication.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException expected) {
                // A correct port-local coordinator may hold this publisher until the first is released.
            } finally {
                releaseFirst.countDown();
            }
            firstPublication.get(5, TimeUnit.SECONDS);
            secondPublication.get(5, TimeUnit.SECONDS);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(request.host()).thenReturn(alias);
            when(request.response()).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            handler.getValue().handle(request);
            verify(response).setStatusCode(502);
            verify(response).end("No listener for host");
            assertEquals(Pem.parseCertificate(replacement.certificateBody()),
                    servedCertificate(options.getValue(), alias));
            removeRuntime(dataPlane, right.getListenerArn(), right.getPort());
            HttpServerRequest survivingRequest = mock(HttpServerRequest.class);
            HttpServerResponse survivingResponse = mock(HttpServerResponse.class);
            when(survivingRequest.host()).thenReturn(alias);
            when(survivingRequest.response()).thenReturn(survivingResponse);
            when(survivingResponse.setStatusCode(anyInt())).thenReturn(survivingResponse);
            handler.getValue().handle(survivingRequest);
            verify(survivingResponse).setStatusCode(200);
            verify(survivingResponse).end("left");
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                dataPlane.shutdown();
            }
        }
    }

    private static CertificateMaterial material(CertificateGenerator generator, String arn, String host) {
        CertificateGenerator.GeneratedCertificate pair = generator.generateSelfSignedCertificate(
                host, List.of(), KeyAlgorithm.RSA_2048);
        return new CertificateMaterial(arn, pair.certificatePem(), pair.privateKeyPem(), null);
    }

    private static Listener listener(String name, String certificateArn) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/" + name + "/1/2");
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/" + name + "/1");
        listener.setPort(8443);
        listener.setProtocol("HTTPS");
        listener.setCertificates(List.of(certificateArn));
        return listener;
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
}
