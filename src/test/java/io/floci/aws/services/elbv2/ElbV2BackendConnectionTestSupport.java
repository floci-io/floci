package io.floci.aws.services.elbv2;

import io.floci.aws.testutil.FreePorts;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.createTargetGroup;
import static io.floci.aws.testing.AlbFixtures.deleteListener;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class ElbV2BackendConnectionTestSupport {

    @Inject
    Vertx vertx;

    int listenerPort;
    HttpServer backend;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;

    void startBackend(Handler<HttpServerRequest> handler) throws Exception {
        backend = vertx.createHttpServer().requestHandler(handler)
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("backend-connection-lb");
        targetGroupArn = createTargetGroup("backend-connection-tg", "ip", backend.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
        listenerArn = createListener(loadBalancerArn, targetGroupArn, listenerPort);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Socket socket = new Socket("127.0.0.1", listenerPort)) {
                // Probe only the listener socket, without sending a request to the target.
                socket.setSoTimeout(1000);
            }
        });
    }

    @Test
    void returnsBadGatewayWhenTargetClosesOnItsFirstRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startBackend(request -> {
            requests.incrementAndGet();
            request.connection().close();
        });

        assertEquals(502, given().baseUri("http://127.0.0.1").port(listenerPort).get("/close").statusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void neverReplaysPostAfterTheTargetConsumesItsBodyAndCloses() throws Exception {
        List<String> consumed = new CopyOnWriteArrayList<>();
        startBackend(request -> request.bodyHandler(body -> {
            consumed.add(body.toString());
            request.connection().close();
        }));

        assertEquals(502, given().baseUri("http://127.0.0.1").port(listenerPort)
                .body("charge-once").post("/charge").statusCode());
        assertEquals(List.of("charge-once"), consumed);
    }

    @AfterEach
    void stopBackend() {
        assertAll("Clean up every backend connection fixture resource after any failure",
                () -> deleteListener(listenerArn),
                () -> deleteLoadBalancer(loadBalancerArn),
                () -> deleteTargetGroup(targetGroupArn),
                () -> {
                    if (backend != null) {
                        backend.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                });
    }
}
