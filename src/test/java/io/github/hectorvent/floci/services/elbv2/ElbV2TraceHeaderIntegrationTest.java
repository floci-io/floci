package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.createTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;

/** What a target receives through a real listener socket, against the ALB request tracing guide. */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2TraceHeaderIntegrationTest {

    private static final String ID = "1-[0-9a-f]{8}-[0-9a-f]{24}";
    private static final String ROOT = "Root=1-67891233-abcdef012345678912345678";

    @Inject
    Vertx vertx;

    private final CompletableFuture<String> handshake = new CompletableFuture<>();
    private HttpServer backend;
    private int listenerPort;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;

    @BeforeEach
    void startBackend() throws Exception {
        backend = vertx.createHttpServer()
                .requestHandler(request -> {
                    String trace = String.valueOf(request.getHeader(ElbV2TraceHeader.NAME));
                    if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                        handshake.complete(trace);
                        request.toWebSocket();
                        return;
                    }
                    request.response().end(trace);
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("trace-header-lb");
        targetGroupArn = createTargetGroup("trace-header-tg", "ip", backend.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
        listenerArn = createListener(loadBalancerArn, targetGroupArn, listenerPort);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> given()
                .baseUri("http://127.0.0.1").port(listenerPort)
                .get("/").then().statusCode(200));
    }

    @AfterEach
    void stopBackend() throws Exception {
        deleteListener(listenerArn);
        deleteTargetGroup(targetGroupArn);
        deleteLoadBalancer(loadBalancerArn);
        backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void targetReceivesAGeneratedRootField() {
        String seen = given().baseUri("http://127.0.0.1").port(listenerPort).get("/").asString();

        assertThat(seen, matchesPattern("Root=" + ID));
    }

    @Test
    void targetReceivesASelfFieldForACallersRoot() {
        String seen = given().baseUri("http://127.0.0.1").port(listenerPort)
                .header(ElbV2TraceHeader.NAME, ROOT + ";CalledFrom=app")
                .get("/").asString();

        assertThat(seen, matchesPattern("Self=" + ID + ";" + ROOT + ";CalledFrom=app"));
    }

    @Test
    void webSocketHandshakeCarriesTheHeader() throws Exception {
        HttpClient client = vertx.createHttpClient();
        try {
            WebSocket socket = client.webSocket(new WebSocketConnectOptions()
                            .setHost("127.0.0.1")
                            .setPort(listenerPort)
                            .setURI("/socket"))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            String seen = handshake.get(5, TimeUnit.SECONDS);
            socket.close();

            assertThat(seen, matchesPattern("Root=" + ID));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
}
