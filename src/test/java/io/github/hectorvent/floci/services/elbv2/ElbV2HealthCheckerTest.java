package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2HealthCheckerTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
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
    void successfulProbeBelowThresholdReturnsInitialHealthCheckingReason() throws Exception {
        HttpServer server = startServer(200);
        try {
            ElbV2HealthChecker checker = healthChecker();
            TargetGroup targetGroup = targetGroup(server.actualPort(), "200", 2, 1);
            TargetDescription target = target("127.0.0.1", server.actualPort());

            checker.addTargets(targetGroup.getTargetGroupArn(), List.of(target), targetGroup);

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                ElbV2HealthChecker.TargetHealthStatus health = checker.getHealth(
                        targetGroup.getTargetGroupArn(), target.getId(), target.getPort());
                assertEquals("initial", health.state());
                assertEquals("Elb.InitialHealthChecking", health.reason());
                assertEquals("Initial health checks in progress", health.description());
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
        } finally {
            close(server);
        }
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
