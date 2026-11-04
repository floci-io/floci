package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.BindException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.publishRuntime;
import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.removeRuntime;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2ListenerOwnershipTest {
    private static final String REGION = "us-west-2";
    private static final String HOST = "shared.example.test";
    private static final String CERTIFICATE = "arn:aws:acm:us-west-2:000000000000:certificate/shared";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unusableSavedMaterialCannotClaimAPortOrPublishControlState(boolean emptyDefault) {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        Listener listener = listener("unusable", 8443, true);
        CertificateMaterial valid = fixture.plane.certificateResolver.publishedMaterial(listener.getListenerArn(), REGION, CERTIFICATE);
        CertificateGenerator.GeneratedCertificate other = new CertificateGenerator().generateSelfSignedCertificate(
                HOST, List.of(), KeyAlgorithm.RSA_2048);
        CertificateMaterial broken = new CertificateMaterial(CERTIFICATE, valid.certificateBody(), other.privateKeyPem(), null);
        when(fixture.plane.certificateResolver.publishedMaterial(anyString(), eq(REGION), eq(CERTIFICATE))).thenReturn(broken);
        if (emptyDefault) {
            listener.setCertificates(List.of());
        }
        AtomicInteger publications = new AtomicInteger();
        try {
            assertThrows(RuntimeException.class, () -> fixture.plane.publishListener(listener, REGION, 8443,
                    () -> { publications.incrementAndGet(); return true; }, () -> List.of(rule("unpublished")), admitted -> admitted.get()));
            assertEquals(0, publications.get());
            assertTrue(registry.isEmpty());
            assertEquals(0, fixture.creations(8443));
            listener.setCertificates(List.of(CERTIFICATE));
            when(fixture.plane.certificateResolver.publishedMaterial(anyString(), eq(REGION), eq(CERTIFICATE))).thenReturn(valid);
            assertTrue(fixture.plane.publishListener(listener, REGION, 8443,
                    () -> { publications.incrementAndGet(); return true; }, () -> List.of(rule("retry")), admitted -> admitted.get()));
            assertEquals(1, publications.get());
            fixture.serves(8443, "retry");
        } finally {
            fixture.plane.shutdown();
        }
    }

    @Test
    void materialChangedBeforeAdmissionCannotPublishAnOlderPreparedSnapshot() {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        Listener listener = listener("overtaken", 8443, true);
        CertificateGenerator.GeneratedCertificate newer = new CertificateGenerator().generateSelfSignedCertificate(
                HOST, List.of(), KeyAlgorithm.RSA_2048);
        CertificateMaterial current = new CertificateMaterial(CERTIFICATE, newer.certificatePem(), newer.privateKeyPem(), null);
        AtomicInteger publications = new AtomicInteger();
        try {
            assertFalse(fixture.plane.publishListener(listener, REGION, 8443,
                    () -> { publications.incrementAndGet(); return true; }, () -> List.of(rule("stale")), operation -> {
                        when(fixture.plane.certificateResolver.publishedMaterial(anyString(), eq(REGION), eq(CERTIFICATE)))
                                .thenReturn(current);
                        return operation.get();
                    }));
            assertEquals(0, publications.get());
            assertTrue(registry.isEmpty());
            assertEquals(0, fixture.creations(8443));
            assertTrue(fixture.plane.publishListener(listener, REGION, 8443,
                    () -> { publications.incrementAndGet(); return true; }, () -> List.of(rule("current")), operation -> operation.get()));
            assertEquals(List.of(current), registry.get(listener.getListenerArn()).certificates());
            assertEquals(1, publications.get());
            fixture.serves(8443, "current");
        } finally {
            fixture.plane.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void refusedControlPublicationRestoresTheExactRuntimeReservation(boolean existing, boolean throwsFailure) {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        Listener first = listener("publication", 8443, false);
        Listener next = listener("publication", 9443, false);
        try {
            if (existing) {
                publishRuntime(fixture.plane, first, REGION, first.getPort(), List.of(rule("old")));
            }
            ElbV2DataPlane.ListenerBinding expected = registry.get(first.getListenerArn());
            AtomicInteger callbacks = new AtomicInteger();
            if (throwsFailure) {
                assertThrows(IllegalArgumentException.class, () -> fixture.plane.publishListener(next, REGION, 8443,
                        () -> {
                            callbacks.incrementAndGet();
                            throw new IllegalArgumentException("refused control publication");
                        }, () -> List.of(rule("unpublished")), admitted -> admitted.get()));
            } else {
                assertFalse(fixture.plane.publishListener(next, REGION, 8443, () -> {
                    callbacks.incrementAndGet();
                    return false;
                }, () -> List.of(rule("unpublished")), admitted -> admitted.get()));
            }
            assertEquals(1, callbacks.get());
            assertSame(expected, registry.get(first.getListenerArn()));
            assertEquals(0, fixture.creations(9443));
            assertEquals(0, fixture.closes(8443));
            if (existing) {
                fixture.serves(8443, "old");
                fixture.plane.recompileRules(first.getListenerArn(), () -> List.of(rule("latest")));
            } else {
                assertTrue(registry.isEmpty());
            }
            assertTrue(fixture.plane.publishListener(next, REGION, 8443, () -> true,
                    () -> List.of(rule("initial")), admitted -> admitted.get()));
            fixture.serves(9443, existing ? "latest" : "initial");
            assertEquals(1, fixture.creations(9443));
        } finally {
            fixture.plane.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noPortControlCallbacksRunWithoutCreatingAGeneveSocket(boolean mockMode) {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        when(fixture.plane.config.services().elbv2().mock()).thenReturn(mockMode);
        Listener gateway = listener("gateway", 8443, false);
        gateway.setPort(null);
        gateway.setProtocol("GENEVE");
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(fixture.plane.publishListener(gateway, REGION, null, () -> {
                callbacks.incrementAndGet();
                return true;
            }, () -> {
                throw new AssertionError("control-only publication must not initialize socket rules");
            }, admitted -> admitted.get()));
            assertTrue(fixture.plane.updateListener(null, () -> {
                callbacks.incrementAndGet();
                return true;
            }));
            assertTrue(fixture.plane.removeListener(gateway.getListenerArn(), null, () -> {
                callbacks.incrementAndGet();
                return true;
            }));
            assertEquals(3, callbacks.get());
            assertTrue(registry.isEmpty());
            assertTrue(fixture.created.isEmpty());
        } finally {
            fixture.plane.shutdown();
        }
    }

    @Test
    void absentRuntimeAndStoppedRuntimeStillApplyControlCallbacks() {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        Listener listener = listener("stopped", 8443, false);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(fixture.plane.removeListener(listener.getListenerArn(), 8443, () -> {
                callbacks.incrementAndGet();
                return true;
            }));
            fixture.plane.shutdown();
            assertTrue(fixture.plane.publishListener(listener, REGION, 8443, () -> {
                callbacks.incrementAndGet();
                return true;
            }, () -> {
                throw new AssertionError("stopped runtime must not compile socket rules");
            }, admitted -> admitted.get()));
            assertEquals(2, callbacks.get());
            assertTrue(registry.isEmpty());
            assertTrue(fixture.created.isEmpty());
        } finally {
            fixture.plane.shutdown();
        }
    }

    @Test
    void delayedRuleRefreshReadsCurrentMembershipInsideThePortGate() throws Exception {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> owner = new AtomicReference<>();
        AtomicReference<Thread> refresher = new AtomicReference<>();
        AtomicReference<List<Rule>> current = new AtomicReference<>(List.of(rule("old")));
        AtomicInteger reads = new AtomicInteger();
        Listener listener = listener("rules", 8443, false);
        try {
            publishRuntime(fixture.plane, listener, REGION, listener.getPort(), current.get());
            // Qualify the JDK Future to distinguish it from Vert.x Future.
            java.util.concurrent.Future<?> held = workers.submit(() -> fixture.plane.updateListener(8443, () -> {
                owner.set(Thread.currentThread());
                holding.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return true;
            }));
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            java.util.concurrent.Future<?> refreshed = workers.submit(() -> {
                refresher.set(Thread.currentThread());
                fixture.plane.recompileRules(listener.getListenerArn(), () -> {
                    reads.incrementAndGet();
                    return current.get();
                });
            });
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertNotNull(refresher.get());
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(refresher.get().threadId(), 30);
                assertNotNull(info);
                assertEquals(Thread.State.BLOCKED, info.getThreadState());
                assertEquals(owner.get().threadId(), info.getLockOwnerId());
                assertTrue(Arrays.stream(info.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("onPort")));
            });
            assertEquals(0, reads.get(), "rule membership must not be read before acquiring the gate");
            current.set(List.of(rule("added"), rule("old")));
            publishRuntime(fixture.plane, listener("unrelated", 10443, false), REGION, null, List.of(rule("progress")));
            fixture.serves(10443, "progress");
            release.countDown();
            held.get(5, TimeUnit.SECONDS);
            refreshed.get(5, TimeUnit.SECONDS);
            assertEquals(1, reads.get());
            fixture.serves(8443, "added");
        } finally {
            release.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                fixture.plane.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void competingInitialClaimsRetryAgainstThePublishedBinding(boolean secure) throws Exception {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Listener first = listener("same", 8443, secure);
        Listener second = listener("same", 9443, secure);
        registry.arn = first.getListenerArn();
        try {
            // Prime release-handler registration before pausing a port gate.
            publishRuntime(fixture.plane, listener("prime", 10443, false), REGION, null, List.of(rule("prime")));
            // Qualify the JDK Future to distinguish it from Vert.x Future.
            java.util.concurrent.Future<?> initial = workers.submit(() -> {
                registry.worker = Thread.currentThread();
                registry.pauseInitial = true;
                publishRuntime(fixture.plane, first, REGION, first.getPort(), List.of(rule("first")));
            });
            assertTrue(registry.entered.await(5, TimeUnit.SECONDS));
            workers.submit(() -> publishRuntime(fixture.plane, second, REGION, second.getPort(), List.of(rule("second"))))
                    .get(5, TimeUnit.SECONDS);
            fixture.serves(9443, "second");
            registry.release.countDown();
            initial.get(5, TimeUnit.SECONDS);
            assertEquals(1, registry.failedClaims.get(), "the actual putIfAbsent must lose before retrying");
            fixture.serves(8443, "first");
            assertEquals(1, fixture.closes(9443));
            publishRuntime(fixture.plane, listener("other", 9443, secure), REGION, null, List.of(rule("reused")));
            fixture.serves(9443, "reused");
            verify(fixture.plane.tlsProxyServer, times(1)).onPortReleased(any());
        } finally {
            registry.release.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                fixture.plane.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopCannotEraseSameArnPublishedOnAnotherPort(boolean secure) throws Exception {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Listener first = listener("same", 8443, secure);
        Listener second = listener("same", 9443, secure);
        registry.arn = first.getListenerArn();
        try {
            publishRuntime(fixture.plane, first, REGION, first.getPort(), List.of(rule("first")));
            // Qualify the JDK Future to distinguish it from Vert.x Future.
            java.util.concurrent.Future<?> stopping = workers.submit(() -> {
                registry.worker = Thread.currentThread();
                registry.pauseRemoval = true;
                removeRuntime(fixture.plane, first.getListenerArn(), first.getPort());
            });
            assertTrue(registry.entered.await(5, TimeUnit.SECONDS));
            workers.submit(() -> publishRuntime(fixture.plane, second, REGION, second.getPort(), List.of(rule("second"))))
                    .get(5, TimeUnit.SECONDS);
            fixture.serves(9443, "second");
            registry.release.countDown();
            stopping.get(5, TimeUnit.SECONDS);
            fixture.serves(9443, "second");
            assertEquals(1, fixture.closes(8443));
            assertEquals(0, fixture.closes(9443));
            int creations = fixture.creations(9443);
            publishRuntime(fixture.plane, second, REGION, second.getPort(), List.of(rule("second")));
            assertEquals(creations, fixture.creations(9443));
            fixture.serves(9443, "second");
            removeRuntime(fixture.plane, second.getListenerArn(), second.getPort());
            assertEquals(1, fixture.closes(9443));
            publishRuntime(fixture.plane, listener("other", 8443, secure), REGION, null, List.of(rule("reused")));
            fixture.serves(8443, "reused");
        } finally {
            registry.release.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                fixture.plane.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPublisherQueuedOnARetiringPortStateRetriesItsIdentity(boolean failedClose) throws Exception {
        Fixture fixture = new Fixture(new Registry());
        CountDownLatch insideClose = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicReference<Throwable> stoppingFailure = new AtomicReference<>();
        AtomicReference<Throwable> publisherFailure = new AtomicReference<>();
        Listener first = listener("first", 8443, false);
        Listener second = listener("second", 8443, false);
        Thread stopping = Thread.ofPlatform().unstarted(() -> {
            try {
                removeRuntime(fixture.plane, first.getListenerArn(), first.getPort());
            } catch (Throwable failure) {
                stoppingFailure.set(failure);
            }
        });
        Thread publishing = Thread.ofPlatform().unstarted(() -> {
            try {
                publishRuntime(fixture.plane, second, REGION, second.getPort(), List.of(rule("second")));
            } catch (Throwable failure) {
                publisherFailure.set(failure);
            }
        });
        try {
            publishRuntime(fixture.plane, first, REGION, first.getPort(), List.of(rule("first")));
            doAnswer(ignored -> {
                insideClose.countDown();
                assertTrue(releaseClose.await(10, TimeUnit.SECONDS));
                return failedClose ? Future.failedFuture(new IllegalStateException("controlled close failure"))
                        : Future.succeededFuture();
            }).when(fixture.servers.get(8443)).close();
            stopping.start();
            assertTrue(insideClose.await(5, TimeUnit.SECONDS));
            publishing.start();
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(publishing.threadId(), 30);
                return info != null && info.getThreadState() == Thread.State.BLOCKED
                        && info.getLockOwnerId() == stopping.threadId()
                        && Arrays.stream(info.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(ElbV2DataPlane.class.getName())
                                        && frame.getMethodName().equals("onPort"));
            });
            publishRuntime(fixture.plane, listener("unrelated", 9443, false), REGION, null, List.of(rule("unrelated")));
            fixture.serves(9443, "unrelated");
            releaseClose.countDown();
            stopping.join(5_000);
            publishing.join(5_000);
            assertFalse(stopping.isAlive());
            assertFalse(publishing.isAlive());
            assertNull(stoppingFailure.get());
            assertNull(publisherFailure.get());
            assertEquals(2, fixture.creations(8443), "queued publisher must bind using the current port state");
            fixture.serves(8443, "second");
            fixture.serves(9443, "unrelated");
        } finally {
            releaseClose.countDown();
            stopping.interrupt();
            publishing.interrupt();
            stopping.join(5_000);
            publishing.join(5_000);
            fixture.plane.shutdown();
        }
    }

    @Test
    void failedCloseRetainsListenersUntilBackgroundBindRetry() {
        Fixture fixture = new Fixture(new Registry());
        Promise<Void> close = Promise.promise();
        Listener first = listener("first", 8443, false);
        Listener second = listener("second", 8443, false);
        try {
            publishRuntime(fixture.plane, first, REGION, first.getPort(), List.of(rule("first")));
            doReturn(close.future()).when(fixture.servers.get(8443)).close();
            removeRuntime(fixture.plane, first.getListenerArn(), first.getPort());
            publishRuntime(fixture.plane, second, REGION, second.getPort(), List.of(rule("second")));
            assertEquals(1, fixture.creations(8443), "pending close must defer the next bind");
            publishRuntime(fixture.plane, listener("unrelated", 9443, false), REGION, null, List.of(rule("unrelated")));
            fixture.serves(9443, "unrelated");
            // A failed close does not establish that the old physical socket is free.
            fixture.failNextListenPort = 8443;
            close.fail(new IllegalStateException("controlled close failure"));
            assertEquals(2, fixture.creations(8443));
            assertEquals(1, fixture.closes(8443), "failed replacement must be closed");
            fixture.runNextTimer();
            assertEquals(3, fixture.creations(8443));
            fixture.serves(8443, "second");
            fixture.serves(9443, "unrelated");
        } finally {
            close.tryComplete();
            fixture.plane.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindRetryWaitsForBothTimerAndFailedServerClose(boolean timerFirst) {
        Registry registry = new Registry();
        Fixture fixture = new Fixture(registry);
        Promise<Void> close = Promise.promise();
        Listener listener = listener("delayed-close", 8443, false);
        try {
            fixture.nextClose = close;
            fixture.failNextListenPort = 8443;
            publishRuntime(fixture.plane, listener, REGION, null, List.of(rule("recovered")));
            assertEquals(1, fixture.creations(8443));
            assertEquals(1, fixture.closes(8443));
            assertTrue(registry.containsKey(listener.getListenerArn()));
            if (timerFirst) {
                fixture.runNextTimer();
                assertEquals(1, fixture.creations(8443), "timer must not bind while close is pending");
                close.complete();
            } else {
                close.complete();
                assertEquals(1, fixture.creations(8443), "close must not bypass the retry timer");
                fixture.runNextTimer();
            }
            assertEquals(2, fixture.creations(8443));
            assertTrue(fixture.timers.isEmpty());
            fixture.serves(8443, "recovered");
        } finally {
            close.tryComplete();
            fixture.plane.shutdown();
        }
    }

    @Test
    void repeatedBindFailuresBackOffWithoutDuplicatingPublication() {
        Fixture fixture = new Fixture(new Registry());
        Listener listener = listener("retry", 8443, false);
        try {
            fixture.failNextListenPort = 8443;
            publishRuntime(fixture.plane, listener, REGION, null, List.of(rule("retry")));
            for (long delay : List.of(100L, 200L, 400L, 800L, 1600L, 3200L, 5000L, 5000L)) {
                int creations = fixture.creations(8443);
                publishRuntime(fixture.plane, listener, REGION, listener.getPort(), List.of(rule("retry")));
                assertEquals(creations, fixture.creations(8443), "publication must not bypass an outstanding retry");
                assertEquals(1, fixture.timers.size());
                assertEquals(delay, fixture.nextTimerDelay);
                fixture.failNextListenPort = 8443;
                fixture.runNextTimer();
            }
            fixture.runNextTimer();
            fixture.serves(8443, "retry");
            assertTrue(fixture.timers.isEmpty());
            fixture.failNextListenPort = 8443;
            publishRuntime(fixture.plane, listener("retry", 8443, true), REGION, 8443, List.of(rule("retry")));
            assertEquals(100, fixture.nextTimerDelay, "a successful bind resets the next recovery delay");
            fixture.runNextTimer();
            fixture.serves(8443, "retry");
        } finally {
            fixture.plane.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retirementCancelsBindRetryAndItsQueuedCallbackCannotReviveTheListener(boolean shutdown) {
        Fixture fixture = new Fixture(new Registry());
        Listener listener = listener("retiring", 8443, false);
        try {
            fixture.failNextListenPort = 8443;
            publishRuntime(fixture.plane, listener, REGION, null, List.of(rule("retiring")));
            Map.Entry<Long, Handler<Long>> pending = fixture.timers.entrySet().iterator().next();
            if (shutdown) {
                fixture.plane.shutdown();
            } else {
                removeRuntime(fixture.plane, listener.getListenerArn(), listener.getPort());
            }
            assertTrue(fixture.timers.isEmpty());
            pending.getValue().handle(pending.getKey());
            assertEquals(1, fixture.creations(8443));
            if (!shutdown) {
                publishRuntime(fixture.plane, listener("replacement", 8443, false), REGION, null, List.of(rule("replacement")));
                pending.getValue().handle(pending.getKey());
                assertEquals(2, fixture.creations(8443));
                fixture.serves(8443, "replacement");
            }
        } finally {
            fixture.plane.shutdown();
        }
    }

    private static final class Registry extends ConcurrentHashMap<String, ElbV2DataPlane.ListenerBinding> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger failedClaims = new AtomicInteger();
        private final AtomicBoolean paused = new AtomicBoolean();
        private volatile Thread worker;
        private String arn;
        private volatile boolean pauseInitial;
        private volatile boolean pauseRemoval;

        @Override
        public ElbV2DataPlane.ListenerBinding get(Object key) {
            ElbV2DataPlane.ListenerBinding actual = super.get(key);
            // Shared publication first checks its expected binding, then install checks the claim.
            if (Thread.currentThread() == worker && arn.equals(key) && pauseInitial
                    && reads.incrementAndGet() == 3 && actual == null) {
                pause();
            }
            return actual;
        }

        @Override
        public ElbV2DataPlane.ListenerBinding putIfAbsent(String key, ElbV2DataPlane.ListenerBinding value) {
            ElbV2DataPlane.ListenerBinding previous = super.putIfAbsent(key, value);
            if (Thread.currentThread() == worker && arn.equals(key) && previous != null) {
                failedClaims.incrementAndGet();
            }
            return previous;
        }

        @Override
        public boolean remove(Object key, Object value) {
            boolean removed = super.remove(key, value);
            if (Thread.currentThread() == worker && arn.equals(key) && pauseRemoval && removed) {
                pause();
            }
            return removed;
        }

        private void pause() {
            if (paused.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "registry boundary was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private static final class Fixture {
        private final ElbV2DataPlane plane;
        private final Map<Integer, Handler<HttpServerRequest>> handlers = new ConcurrentHashMap<>();
        private final Map<Integer, AtomicInteger> created = new ConcurrentHashMap<>();
        private final Map<Integer, AtomicInteger> closed = new ConcurrentHashMap<>();
        private final Map<Integer, HttpServer> servers = new ConcurrentHashMap<>();
        private final Map<Long, Handler<Long>> timers = new ConcurrentHashMap<>();
        private final AtomicLong timerSequence = new AtomicLong();
        private long nextTimerDelay;
        private Promise<Void> nextClose;
        private volatile int failNextListenPort = -1;

        private Fixture(Registry registry) {
            plane = new ElbV2DataPlane(registry);
            plane.config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.ElbV2ServiceConfig config = mock(EmulatorConfig.ElbV2ServiceConfig.class);
            when(plane.config.services()).thenReturn(services);
            when(services.elbv2()).thenReturn(config);
            when(config.mock()).thenReturn(false);
            when(plane.config.port()).thenReturn(4566);
            plane.tlsProxyServer = mock(TlsProxyServer.class);
            when(plane.tlsProxyServer.reservesPort(anyInt())).thenReturn(false);
            plane.elbV2Service = mock(ElbV2Service.class);
            LoadBalancer balancer = new LoadBalancer();
            balancer.setDnsName(HOST);
            when(plane.elbV2Service.getLoadBalancer(eq(REGION), anyString())).thenReturn(balancer);
            plane.certificateResolver = mock(CertificateReferenceResolver.class);
            CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator().generateSelfSignedCertificate(
                    HOST, List.of(), KeyAlgorithm.RSA_2048);
            CertificateMaterial material = new CertificateMaterial(CERTIFICATE, pair.certificatePem(), pair.privateKeyPem(), null);
            when(plane.certificateResolver.publishedMaterial(anyString(), eq(REGION), eq(CERTIFICATE))).thenReturn(material);
            plane.vertx = mock(Vertx.class);
            when(plane.vertx.setTimer(anyLong(), any())).thenAnswer(call -> {
                long timer = timerSequence.incrementAndGet();
                nextTimerDelay = call.getArgument(0);
                timers.put(timer, call.getArgument(1));
                return timer;
            });
            when(plane.vertx.cancelTimer(anyLong())).thenAnswer(call ->
                    timers.remove(call.<Long>getArgument(0)) != null);
            when(plane.vertx.createHttpServer(any(HttpServerOptions.class))).thenAnswer(call -> {
                int port = ((HttpServerOptions) call.getArgument(0)).getPort();
                created.computeIfAbsent(port, ignored -> new AtomicInteger()).incrementAndGet();
                HttpServer server = mock(HttpServer.class);
                servers.put(port, server);
                when(server.requestHandler(any())).thenAnswer(handler -> {
                    handlers.put(port, handler.getArgument(0));
                    return server;
                });
                when(server.listen()).thenAnswer(ignored -> {
                    if (failNextListenPort == port) {
                        failNextListenPort = -1;
                        return Future.failedFuture(new BindException("controlled occupied port"));
                    }
                    return Future.succeededFuture(server);
                });
                when(server.close()).thenAnswer(ignored -> {
                    closed.computeIfAbsent(port, key -> new AtomicInteger()).incrementAndGet();
                    Promise<Void> pending = nextClose;
                    nextClose = null;
                    return pending == null ? Future.succeededFuture() : pending.future();
                });
                when(server.updateSSLOptions(any(SSLOptions.class), eq(true))).thenReturn(Future.succeededFuture(true));
                return server;
            });
        }

        private int creations(int port) {
            AtomicInteger count = created.get(port);
            return count == null ? 0 : count.get();
        }

        private void runNextTimer() {
            assertEquals(1, timers.size());
            long timer = timers.keySet().iterator().next();
            timers.remove(timer).handle(timer);
        }

        private int closes(int port) {
            AtomicInteger count = closed.get(port);
            return count == null ? 0 : count.get();
        }

        private void serves(int port, String body) {
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(request.host()).thenReturn(HOST);
            when(request.response()).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            handlers.get(port).handle(request);
            verify(response).setStatusCode(200);
            verify(response).end(body);
        }
    }

    private static Listener listener(String name, int port, boolean secure) {
        Listener listener = new Listener();
        listener.setListenerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:listener/app/shared/1/" + name);
        listener.setLoadBalancerArn("arn:aws:elasticloadbalancing:us-west-2:000000000000:loadbalancer/app/shared/1");
        listener.setPort(port);
        listener.setProtocol(secure ? "HTTPS" : "HTTP");
        listener.setCertificates(secure ? List.of(CERTIFICATE) : List.of());
        return listener;
    }

    private static Rule rule(String body) {
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
