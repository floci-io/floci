package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testutil.AppSyncRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AuthMode;
import software.amazon.awssdk.services.appsync.model.AuthProvider;
import software.amazon.awssdk.services.appsync.model.AuthenticationType;
import software.amazon.awssdk.services.appsync.model.EventConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Events data plane end to end without Docker: SigV4 and API key publishes over HTTP, on the
 * path form and on the API's own host, and a realtime WebSocket that connects, subscribes, receives,
 * unsubscribes and publishes. The API mirrors the shape app services uses, IAM publishing to
 * {@code /appservices-v7-6-0/protocol/<accountId>/<runId>}, with an API key standing in for the
 * Lambda authorizer, which needs a Lambda runtime and is covered by {@code EventsLambdaAuthorizerTest}.
 */
@QuarkusTest
class AppSyncEventsDataPlaneIntegrationTest {

    private static final String ACCOUNT = "741648700019";
    private static final String NAMESPACE = "appservices-v7-6-0";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    @TestHTTPResource("/")
    URI baseUri;

    private AppSyncClient appSync;
    private String apiId;
    private String apiKey;
    private final List<WebSocket> sockets = new ArrayList<>();

    private static AuthMode mode(AuthenticationType type) {
        return AuthMode.builder().authType(type).build();
    }

    @BeforeEach
    void createApi() {
        appSync = AwsTestClients.create(AppSyncClient.builder(), ACCOUNT, Region.US_EAST_1);
        apiId = appSync.createApi(request -> request.name("events-dp-" + UUID.randomUUID().toString().substring(0, 8))
                .eventConfig(EventConfig.builder()
                        .authProviders(AuthProvider.builder().authType(AuthenticationType.AWS_IAM).build(),
                                AuthProvider.builder().authType(AuthenticationType.API_KEY).build())
                        .connectionAuthModes(mode(AuthenticationType.API_KEY))
                        .defaultPublishAuthModes(mode(AuthenticationType.AWS_IAM))
                        .defaultSubscribeAuthModes(mode(AuthenticationType.API_KEY))
                        .build())).api().apiId();
        appSync.createChannelNamespace(request -> request.apiId(apiId).name(NAMESPACE));
        apiKey = appSync.createApiKey(request -> request.apiId(apiId)).apiKey().id();
    }

    @AfterEach
    void deleteApi() {
        sockets.forEach(WebSocket::abort);
        appSync.deleteApi(request -> request.apiId(apiId));
        appSync.close();
    }

    @Test
    void aSigV4PublishOnThePathFormReachesAWildcardSubscriberAndStopsAfterUnsubscribe() throws Exception {
        Listener listener = connect(Map.of("host", host(), "x-api-key", apiKey));
        assertEquals("aws-appsync-event-ws", listener.socket.getSubprotocol());
        listener.send(Map.of("type", "connection_init"));
        Map<String, Object> ack = listener.next();
        assertEquals("connection_ack", ack.get("type"));
        assertEquals(300000, ack.get("connectionTimeoutMs"));

        listener.send(Map.of("type", "subscribe", "id", "sub-1",
                "channel", "/" + NAMESPACE + "/protocol/" + ACCOUNT + "/*",
                "authorization", Map.of("x-api-key", apiKey, "host", host())));
        assertEquals(Map.of("type", "subscribe_success", "id", "sub-1"), listener.next());

        String channel = "/" + NAMESPACE + "/protocol/" + ACCOUNT + "/run-1";
        signedPublish(pathForm(), host(), channel, List.of("{\"step\":1}", "{\"step\":2}"))
                .statusCode(200)
                .body("successful", hasSize(2))
                .body("successful[1].index", equalTo(1))
                .body("failed", hasSize(0));

        assertEquals(Map.of("type", "data", "id", "sub-1", "event", "{\"step\":1}"), listener.next());
        assertEquals(Map.of("type", "data", "id", "sub-1", "event", "{\"step\":2}"), listener.next());

        listener.send(Map.of("type", "unsubscribe", "id", "sub-1"));
        assertEquals(Map.of("type", "unsubscribe_success", "id", "sub-1"), listener.next());
        signedPublish(pathForm(), host(), channel, List.of("{\"step\":3}")).statusCode(200);
        assertNull(listener.poll(500), "no event after unsubscribe");
    }

    /** On the API's own host the caller signs {@code /event}, the path it sent. */
    @Test
    void aSigV4PublishOnTheApisHostIsServed() throws Exception {
        String host = apiId + ".appsync-api." + REGION + ".localhost:" + baseUri.getPort();

        signedPublish("/event", host, "/" + NAMESPACE + "/a", List.of("{}"))
                .statusCode(200)
                .body("successful", hasSize(1));
    }

    @Test
    void aPublishIsRefusedWithTheDataPlaneErrorEnvelope() throws Exception {
        given().header("x-api-key", apiKey).contentType("application/json")
                .body(body("/" + NAMESPACE + "/a", List.of("{}")))
                .when().post(pathForm())
                .then().statusCode(401)
                .body("errors[0].errorType", equalTo("UnauthorizedException"));
        signedPublish(pathForm(), host(), "/missing/a", List.of("{}"))
                .statusCode(400).body("errors[0].errorType", equalTo("BadRequestException"));
        signedPublish(pathForm(), host(), "/" + NAMESPACE + "/*", List.of("{}")).statusCode(400);
        signedPublish(pathForm(), host(), "/" + NAMESPACE + "/a", List.of("{}", "{}", "{}", "{}", "{}", "{}"))
                .statusCode(400);
        signedPublish(pathForm(), host(), "/" + NAMESPACE + "/a", List.of("not json")).statusCode(400);
        given().header("x-api-key", apiKey).contentType("application/json").body("{}")
                .when().post("/_aws/appsync/events/missing00000000000000000000/event")
                .then().statusCode(404).body("errors[0].errorType", equalTo("NotFoundException"));
    }

    @Test
    void aConnectionWithoutAValidAuthorizationIsRefused() throws Exception {
        Listener missing = connect(null);
        Map<String, Object> error = missing.next();
        assertEquals("connection_error", error.get("type"));
        assertNotNull(missing.closed.get(5, TimeUnit.SECONDS));

        Listener wrongKey = connect(Map.of("host", host(), "x-api-key", "da2-notakey000000000000000000"));
        assertEquals("connection_error", wrongKey.next().get("type"));
    }

    /** A namespace's own subscribe modes replace the API default, so an API key no longer subscribes. */
    @Test
    void aSubscribeInAModeTheNamespaceDoesNotAcceptIsRefused() throws Exception {
        appSync.createChannelNamespace(request -> request.apiId(apiId).name("iam-only")
                .subscribeAuthModes(mode(AuthenticationType.AWS_IAM)));
        Listener listener = connect(Map.of("host", host(), "x-api-key", apiKey));
        listener.send(Map.of("type", "connection_init"));
        listener.next();

        listener.send(Map.of("type", "subscribe", "id", "sub-2", "channel", "/iam-only/a",
                "authorization", Map.of("x-api-key", apiKey)));

        Map<String, Object> error = listener.next();
        assertEquals("subscribe_error", error.get("type"));
        assertEquals("sub-2", error.get("id"));
        assertTrue(String.valueOf(error.get("errors")).contains("UnauthorizedException"), String.valueOf(error));
    }

    @Test
    void aPublishOverTheWebSocketReachesTheConnectionsOwnSubscription() throws Exception {
        appSync.createChannelNamespace(request -> request.apiId(apiId).name("keyed")
                .publishAuthModes(mode(AuthenticationType.API_KEY)));
        Listener listener = connect(Map.of("host", host(), "x-api-key", apiKey));
        listener.send(Map.of("type", "connection_init"));
        listener.next();
        listener.send(Map.of("type", "subscribe", "id", "sub-3", "channel", "/keyed/a",
                "authorization", Map.of("x-api-key", apiKey)));
        listener.next();

        listener.send(Map.of("type", "publish", "id", "pub-1", "channel", "/keyed/a",
                "events", List.of("{\"hello\":\"world\"}"), "authorization", Map.of("x-api-key", apiKey)));

        Map<String, Object> first = listener.next();
        Map<String, Object> second = listener.next();
        Map<String, Object> published = "publish_success".equals(first.get("type")) ? first : second;
        Map<String, Object> data = published == first ? second : first;
        assertEquals("pub-1", published.get("id"));
        assertEquals(List.of(), published.get("failed"));
        assertEquals(Map.of("type", "data", "id", "sub-3", "event", "{\"hello\":\"world\"}"), data);
    }

    private String host() {
        return baseUri.getHost() + ":" + baseUri.getPort();
    }

    private String pathForm() {
        return "/_aws/appsync/events/" + apiId + "/event";
    }

    private static String body(String channel, List<String> events) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("events", events);
        return MAPPER.writeValueAsString(body);
    }

    private ValidatableResponse signedPublish(String path, String host, String channel, List<String> events)
            throws Exception {
        String body = body(channel, events);
        Map<String, String> signed = AppSyncRequestSigner.signedHeadersForPath(path, host, body, "test", "test",
                REGION, Instant.now());
        RequestSpecification request = given().contentType("application/json").body(body);
        signed.forEach(request::header);
        return request.when().post(baseUri.resolve(path)).then();
    }

    private Listener connect(Map<String, String> authorization) throws Exception {
        URI uri = URI.create("ws://" + host() + "/_aws/appsync/events/" + apiId + "/event/realtime");
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        Listener listener = new Listener();
        if (authorization == null) {
            builder.subprotocols("aws-appsync-event-ws");
        } else {
            builder.subprotocols("aws-appsync-event-ws",
                    "header-" + Base64Url.encode(MAPPER.writeValueAsString(authorization)));
        }
        listener.socket = builder.buildAsync(uri, listener).get(10, TimeUnit.SECONDS);
        sockets.add(listener.socket);
        return listener;
    }

    /** Collects every text message a connection receives, in order. */
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        void send(Map<String, Object> message) throws Exception {
            socket.sendText(MAPPER.writeValueAsString(message), true).get(5, TimeUnit.SECONDS);
        }

        Map<String, Object> next() throws Exception {
            Map<String, Object> message = poll(5000);
            assertNotNull(message, "expected a message");
            return message;
        }

        Map<String, Object> poll(long millis) throws Exception {
            String text = messages.poll(millis, TimeUnit.MILLISECONDS);
            return text == null ? null : MAPPER.readValue(text, OBJECT_MAP);
        }
    }
}
