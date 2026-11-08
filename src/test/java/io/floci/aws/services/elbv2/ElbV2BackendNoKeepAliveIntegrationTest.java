package io.floci.aws.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.AlbFixtures.exchange;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@TestProfile(ElbV2BackendNoKeepAliveIntegrationTest.NoKeepAliveProfile.class)
class ElbV2BackendNoKeepAliveIntegrationTest extends ElbV2BackendConnectionTestSupport {

    public static class NoKeepAliveProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false",
                    "floci.services.elbv2.backend-keep-alive", "false");
        }
    }

    @Test
    void disablingKeepAliveUsesFreshConnectionsForSequentialRequests() throws Exception {
        Map<HttpConnection, AtomicInteger> requests = new ConcurrentHashMap<>();
        startBackend(request -> {
            int count = requests.computeIfAbsent(request.connection(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (count > 1) {
                request.connection().close();
            } else {
                request.response().putHeader("X-Backend-Connection",
                        Integer.toString(request.remoteAddress().port())).end("ok");
            }
        });

        Response first = given().baseUri("http://127.0.0.1").port(listenerPort).get("/first");
        assertEquals(200, first.statusCode());
        Response second = given().baseUri("http://127.0.0.1").port(listenerPort).get("/second");
        assertEquals(200, second.statusCode());
        assertEquals("ok", second.asString());
        assertNotEquals(first.header("X-Backend-Connection"), second.header("X-Backend-Connection"));
        assertEquals(2, requests.size());
        assertEquals(2, requests.values().stream().mapToInt(AtomicInteger::get).sum());
    }

    @Test
    void disablingKeepAlivePreservesWebSocketUpgrades() throws Exception {
        startBackend(request -> request.toWebSocket().onSuccess(socket ->
                socket.textMessageHandler(socket::writeTextMessage)));

        HttpClient client = vertx.createHttpClient();
        try {
            WebSocket socket = client.webSocket(new WebSocketConnectOptions()
                    .setHost("127.0.0.1").setPort(listenerPort).setURI("/socket"))
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("first", exchange(socket, "first"));
            assertEquals("second", exchange(socket, "second"));
            socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
