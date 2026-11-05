package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2HealthCheckerTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void slowProbeDoesNotOccupyTheSharedVertxWorker() throws Exception {
        Vertx isolated = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
        HttpServer server = null;
        ElbV2HealthChecker checker = null;
        CompletableFuture<HttpServerResponse> response = new CompletableFuture<>();
        try {
            server = isolated.createHttpServer().requestHandler(request -> response.complete(request.response()))
                    .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().elbv2().mock()).thenReturn(false);
            checker = new ElbV2HealthChecker(isolated, config, mock(Ec2Service.class));
            TargetGroup group = targetGroup(server.actualPort(), "200", 1, 1);
            group.setHealthCheckTimeoutSeconds(10);
            TargetDescription target = target("127.0.0.1", server.actualPort());
            checker.addTargets(group.getTargetGroupArn(), List.of(target), group);
            HttpServerResponse pendingResponse = response.get(2, TimeUnit.SECONDS);

            assertEquals("unrelated", assertDoesNotThrow(() -> isolated.executeBlocking(() -> "unrelated", false)
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS)),
                    "A probe awaiting its response must not occupy the shared worker");
            pendingResponse.end().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            ElbV2HealthChecker observed = checker;
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals("healthy",
                    observed.getState(group.getTargetGroupArn(), target.getId(), target.getPort())));
        } finally {
            try {
                if (checker != null) {
                    checker.destroy();
                }
                if (response.isDone() && !response.join().ended()) {
                    response.join().end();
                }
                if (server != null) {
                    server.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
                }
            } finally {
                isolated.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void successfulProbeTransitionsTargetToHealthy() throws Exception {
        HttpServer server = startServer(200);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 1, 1);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("healthy", health.state());
                assertNull(health.reason());
                assertNull(health.description());
            });
        } finally {
            close(server);
        }
    }

    @Test
    void responseCodeMismatchTransitionsTargetToUnhealthyWithAwsReason() throws Exception {
        HttpServer server = startServer(503);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 1, 1);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("unhealthy", health.state());
                assertEquals("Target.ResponseCodeMismatch", health.reason());
                assertEquals("Health checks failed with these codes: [503]", health.description());
            });
        } finally {
            close(server);
        }
    }

    @Test
    void timedOutProbeTransitionsTargetToUnhealthyWithAwsReason() throws Exception {
        HttpServer server = startHangingServer();
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 1, 1);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("unhealthy", health.state());
                assertEquals("Target.Timeout", health.reason());
                assertEquals("Request timed out", health.description());
            });
        } finally {
            close(server);
        }
    }

    @Test
    void timedOutLookupKeepsOneProbeUntilTheLookupActuallyCompletes() throws Exception {
        HttpServer server = startServer(200);
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger lookups = new AtomicInteger();
        Ec2Service ec2 = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("127.0.0.1");
        when(ec2.findInstanceById("000000000000", "i-blocked")).thenAnswer(ignored -> {
            lookups.incrementAndGet();
            entered.complete(null);
            // Like a native hostname lookup, join does not stop when its thread is interrupted.
            release.join();
            return instance;
        });
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ElbV2HealthChecker checker = new ElbV2HealthChecker(vertx, config, ec2);
        TargetGroup group = targetGroup(server.actualPort(), "200", 1, 1);
        group.setTargetType("instance");
        TargetDescription blocked = target("i-blocked", server.actualPort());
        try {
            checker.addTargets(group.getTargetGroupArn(), List.of(blocked), group);
            entered.get(2, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals("Target.Timeout",
                    checker.getHealth(group.getTargetGroupArn(), blocked.getId(), blocked.getPort()).reason()));
            checker.addTargets(group.getTargetGroupArn(), List.of(target("127.0.0.1", server.actualPort())), group);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals("healthy",
                    checker.getState(group.getTargetGroupArn(), "127.0.0.1", server.actualPort())));
            await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                checker.probeAll(group.getTargetGroupArn(), group);
                assertEquals(1, lookups.get(), "A timed-out lookup still owns its target's probe");
            });
            release.complete(null);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                checker.probeAll(group.getTargetGroupArn(), group);
                assertEquals("healthy",
                        checker.getState(group.getTargetGroupArn(), blocked.getId(), blocked.getPort()));
            });
        } finally {
            release.complete(null);
            close(server);
        }
    }

    @Test
    void timedOutLookupsRetainTheirSharedProbeSlotsUntilTheyFinish() throws Exception {
        HttpServer server = startServer(200);
        CompletableFuture<Void> release = new CompletableFuture<>();
        AtomicInteger lookups = new AtomicInteger();
        Ec2Service ec2 = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setContainerBridgeIp("127.0.0.1");
        when(ec2.findInstanceById(eq("000000000000"), any())).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(1).startsWith("i-held-")) {
                lookups.incrementAndGet();
                release.join();
            }
            return instance;
        });
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ElbV2HealthChecker checker = new ElbV2HealthChecker(vertx, config, ec2);
        TargetGroup group = targetGroup(server.actualPort(), "200", 1, 1);
        group.setTargetType("instance");
        List<TargetDescription> held = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            held.add(target("i-held-" + i, server.actualPort()));
        }
        try {
            checker.addTargets(group.getTargetGroupArn(), held, group);
            await().atMost(Duration.ofSeconds(3)).until(() -> lookups.get() == 32);
            await().atMost(Duration.ofSeconds(3)).until(() -> held.stream().allMatch(target ->
                    "Target.Timeout".equals(checker.getHealth(
                            group.getTargetGroupArn(), target.getId(), target.getPort()).reason())));
            checker.addTargets(group.getTargetGroupArn(), List.of(target("127.0.0.1", server.actualPort())), group);
            await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                checker.probeAll(group.getTargetGroupArn(), group);
                assertEquals(32, lookups.get());
                assertEquals("initial", checker.getState(
                        group.getTargetGroupArn(), "127.0.0.1", server.actualPort()),
                        "An expired caller deadline must not release a still-running worker's slot");
            });
            release.complete(null);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                checker.probeAll(group.getTargetGroupArn(), group);
                assertEquals("healthy",
                        checker.getState(group.getTargetGroupArn(), "127.0.0.1", server.actualPort()));
            });
        } finally {
            release.complete(null);
            close(server);
        }
    }

    @Test
    void firstSuccessfulProbeMakesNewTargetHealthyRegardlessOfRecoveryThreshold() throws Exception {
        HttpServer server = startServer(200);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 10, 2);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("healthy", health.state());
                assertNull(health.reason());
                assertNull(health.description());
            });
        } finally {
            close(server);
        }
    }

    @Test
    void recoveringTargetRemainsUnhealthyUntilHealthyThresholdIsMet() throws Exception {
        AtomicInteger statusCode = new AtomicInteger(503);
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = vertx.createHttpServer()
                .requestHandler(request -> {
                    requestCount.incrementAndGet();
                    request.response().setStatusCode(statusCode.get()).end();
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 2, 1);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals(
                    "unhealthy",
                    checker.getHealth(targetGroup.getTargetGroupArn(), target.getId(), target.getPort()).state()));

            statusCode.set(200);
            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);
            await().atMost(Duration.ofSeconds(2)).until(() -> requestCount.get() >= 2);

            await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("unhealthy", health.state());
                assertEquals("Target.ResponseCodeMismatch", health.reason());
            });

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("healthy", health.state());
                assertNull(health.reason());
                assertNull(health.description());
            });
        } finally {
            close(server);
        }
    }

    @Test
    void numericHealthCheckPortIsProbedInsteadOfTrafficPort() throws Exception {
        HttpServer traffic = startServer(404);
        HttpServer health = startServer(200);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(traffic.actualPort(), "200", 1, 1);
            targetGroup.setHealthCheckPort(String.valueOf(health.actualPort()));
            TargetDescription target = target("127.0.0.1", traffic.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals(
                    "healthy",
                    checker.getHealth(targetGroup.getTargetGroupArn(), target.getId(), traffic.actualPort()).state()));
        } finally {
            close(traffic);
            close(health);
        }
    }

    @Test
    void trafficPortKeywordOrInvalidPortProbesTargetPort() {
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setHealthCheckPort("traffic-port");
        assertEquals(50051, ElbV2HealthChecker.healthCheckPort(targetGroup, 50051));
        targetGroup.setHealthCheckPort(null);
        assertEquals(50051, ElbV2HealthChecker.healthCheckPort(targetGroup, 50051));
        targetGroup.setHealthCheckPort("8080");
        assertEquals(8080, ElbV2HealthChecker.healthCheckPort(targetGroup, 50051));
        targetGroup.setHealthCheckPort("70000");
        assertEquals(50051, ElbV2HealthChecker.healthCheckPort(targetGroup, 50051));
        targetGroup.setHealthCheckPort("not-a-port");
        assertEquals(50051, ElbV2HealthChecker.healthCheckPort(targetGroup, 50051));
    }

    @Test
    void registrationStateCarriesAwsReasonUntilFirstProbeCompletes() {
        ElbV2HealthChecker checker = healthChecker();

        ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/1234",
                "i-1234567890abcdef0",
                8080);

        assertEquals("initial", health.state());
        assertEquals("Elb.RegistrationInProgress", health.reason());
        assertEquals("Target registration is in progress", health.description());
    }

    @ParameterizedTest
    @CsvSource({"HTTP,1", "HTTPS,1", "LAMBDA,1", "TCP,2", "TLS,2", "UDP,2", "TCP_UDP,2", "GENEVE,2"})
    @SuppressWarnings("unchecked")
    void initialAdmissionRespectsTargetGroupKind(String protocol, int requiredSuccesses) {
        Vertx immediate = mock(Vertx.class);
        WorkerExecutor workers = mock(WorkerExecutor.class);
        when(immediate.createSharedWorkerExecutor("floci-elbv2-health-probes", 32)).thenReturn(workers);
        when(workers.executeBlocking(any(Callable.class), eq(false))).thenReturn(Future.succeededFuture(200));
        doAnswer(invocation -> {
            Handler<Long> callback = invocation.getArgument(1);
            callback.handle(1L);
            return 1L;
        }).when(immediate).setTimer(anyLong(), any());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elbv2().mock()).thenReturn(false);
        ElbV2HealthChecker checker = new ElbV2HealthChecker(immediate, config, mock(Ec2Service.class));
        TargetGroup group = targetGroup(8080, "200", 2, 1);
        group.setProtocol("LAMBDA".equals(protocol) ? null : protocol);
        group.setTargetType("LAMBDA".equals(protocol) ? "lambda" : "ip");
        group.setHealthCheckProtocol("HTTP");
        TargetDescription target = target("127.0.0.1", 8080);

        checker.addTargets(group.getTargetGroupArn(), List.of(target), group);

        ElbV2HealthChecker.TargetHealthStatus first = checker.getHealth(
                group.getTargetGroupArn(), target.getId(), target.getPort());
        assertEquals(requiredSuccesses == 1 ? "healthy" : "initial", first.state());
        assertEquals(requiredSuccesses == 1 ? null : "Elb.InitialHealthChecking", first.reason());
        assertEquals(requiredSuccesses == 1 ? null : "Initial health checks in progress", first.description());
        checker.addTargets(group.getTargetGroupArn(), List.of(target), group);
        ElbV2HealthChecker.TargetHealthStatus healthy = checker.getHealth(
                group.getTargetGroupArn(), target.getId(), target.getPort());
        assertEquals("healthy", healthy.state());
        assertNull(healthy.reason());
        assertNull(healthy.description());
    }

    @Test
    @SuppressWarnings("unchecked")
    void periodicProbeRetainsIntervalAndNetworkAdmissionThreshold() {
        Vertx scheduled = mock(Vertx.class);
        WorkerExecutor workers = mock(WorkerExecutor.class);
        when(scheduled.createSharedWorkerExecutor("floci-elbv2-health-probes", 32)).thenReturn(workers);
        when(workers.executeBlocking(any(Callable.class), eq(false))).thenReturn(Future.succeededFuture(200));
        AtomicReference<Handler<Long>> periodic = new AtomicReference<>();
        doAnswer(invocation -> {
            Handler<Long> callback = invocation.getArgument(1);
            callback.handle(1L);
            return 1L;
        }).when(scheduled).setTimer(anyLong(), any());
        doAnswer(invocation -> {
            assertEquals(5000L, invocation.<Long>getArgument(0));
            periodic.set(invocation.getArgument(1));
            return 2L;
        }).when(scheduled).setPeriodic(anyLong(), any());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elbv2().mock()).thenReturn(false);
        ElbV2HealthChecker checker = new ElbV2HealthChecker(scheduled, config, mock(Ec2Service.class));
        TargetGroup group = targetGroup(8080, "200", 2, 1);
        group.setProtocol("TCP");
        group.setHealthCheckIntervalSeconds(5);
        TargetDescription target = target("127.0.0.1", 8080);

        checker.startMonitoring(group);
        checker.addTargets(group.getTargetGroupArn(), List.of(target), group);
        assertEquals("initial", checker.getState(group.getTargetGroupArn(), target.getId(), target.getPort()));
        assertEquals("Elb.InitialHealthChecking",
                checker.getHealth(group.getTargetGroupArn(), target.getId(), target.getPort()).reason());
        periodic.get().handle(2L);
        assertEquals("healthy", checker.getState(group.getTargetGroupArn(), target.getId(), target.getPort()));
        checker.stopMonitoring(group.getTargetGroupArn());
        verify(scheduled).cancelTimer(2L);
        assertEquals("initial", checker.getState(group.getTargetGroupArn(), target.getId(), target.getPort()));
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    @SuppressWarnings("unchecked")
    void queuedProbeRetainsItsHealthProtocol(boolean changeProtocol) throws Exception {
        HttpServer server = startServer(200);
        try {
            Vertx queued = mock(Vertx.class);
            WorkerExecutor workers = mock(WorkerExecutor.class);
            when(queued.createSharedWorkerExecutor("floci-elbv2-health-probes", 32)).thenReturn(workers);
            AtomicReference<Callable<Integer>> work = new AtomicReference<>();
            Promise<Integer> result = Promise.promise();
            when(workers.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
                work.set(invocation.getArgument(0));
                return result.future();
            });
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().elbv2().mock()).thenReturn(false);
            ElbV2HealthChecker checker = new ElbV2HealthChecker(queued, config, mock(Ec2Service.class));
            TargetGroup group = targetGroup(server.actualPort(), "200", 1, 1);
            group.setHealthCheckProtocol("HTTP");
            TargetDescription target = target("127.0.0.1", server.actualPort());
            checker.addTargets(group.getTargetGroupArn(), List.of(target), group);
            checker.probeAll(group.getTargetGroupArn(), group);
            if (changeProtocol) {
                group.setHealthCheckProtocol("HTTPS");
            }

            int status = assertDoesNotThrow(() -> work.get().call(),
                    "A queued HTTP probe must keep its captured protocol");
            result.complete(status);
            assertEquals(200, status);
            assertEquals("healthy", checker.getState(group.getTargetGroupArn(), target.getId(), target.getPort()));
        } finally {
            close(server);
        }
    }

    private ElbV2HealthChecker healthChecker() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elbv2().mock()).thenReturn(false);
        return new ElbV2HealthChecker(vertx, config, mock(Ec2Service.class));
    }

    private HttpServer startServer(int statusCode) throws Exception {
        HttpServer server = vertx.createHttpServer()
                .requestHandler(request -> request.response().setStatusCode(statusCode).end());
        return server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
    }

    private HttpServer startHangingServer() throws Exception {
        HttpServer server = vertx.createHttpServer()
                .requestHandler(request -> {});
        return server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
    }

    private static void close(HttpServer server) throws Exception {
        CompletableFuture<Void> closed = server.close().toCompletionStage().toCompletableFuture();
        closed.get(2, TimeUnit.SECONDS);
    }

    private static TargetGroup targetGroup(int port, String matcher, int healthyThreshold, int unhealthyThreshold) {
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/1234");
        targetGroup.setTargetType("ip");
        targetGroup.setProtocol("HTTP");
        targetGroup.setPort(port);
        targetGroup.setHealthCheckEnabled(true);
        targetGroup.setHealthCheckPath("/");
        targetGroup.setMatcher(matcher);
        targetGroup.setHealthCheckTimeoutSeconds(1);
        targetGroup.setHealthyThresholdCount(healthyThreshold);
        targetGroup.setUnhealthyThresholdCount(unhealthyThreshold);
        return targetGroup;
    }

    private static TargetDescription target(String id, int port) {
        TargetDescription target = new TargetDescription();
        target.setId(id);
        target.setPort(port);
        return target;
    }
}
