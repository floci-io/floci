package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The signed HTTP client that forwards DynamoDB wire calls to an external DynamoDB Local. */
class DynamoDbLocalClientTest {

    private static final Scope SCOPE = new Scope("123456789012", "eu-west-1");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/dynamodb/aws4_request, "
                    + "SignedHeaders=content-type;host;x-amz-date;x-amz-target, Signature=([0-9a-f]{64})");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Received> received = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer server;
    private DynamoDbLocalClient client;

    private record Received(String path, Headers headers, byte[] body) {
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws Exception;
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsAndForwardsExactBody() throws Exception {
        start(exchange -> reply(exchange, 200, "{}"));
        ObjectNode body = (ObjectNode) mapper.readTree("""
                {"TableName":"orders","Item":{
                  "pk":{"S":"a"},
                  "blob":{"B":"AAEC"},
                  "blobs":{"BS":["AAEC","AwQF"]},
                  "numbers":{"NS":["1","2.5"]},
                  "big":{"N":"12345678901234567890123456789012345678"},
                  "nested":{"M":{"inner":{"B":"BgcI"}}}}}
                """);

        Reply result = client.send(SCOPE, Api.DYNAMODB, "PutItem", body);

        assertEquals(200, result.status());
        Received request = received.get();
        assertArrayEquals(mapper.writeValueAsBytes(body), request.body());
        assertEquals("DynamoDB_20120810.PutItem", request.headers().getFirst("X-Amz-Target"));
        assertEquals("application/x-amz-json-1.0", request.headers().getFirst("Content-Type"));
        String amzDate = request.headers().getFirst("X-Amz-Date");
        assertNotNull(amzDate);
        String date = amzDate.substring(0, 8);
        String authorization = request.headers().getFirst("Authorization");
        assertTrue(authorization.contains(
                        "Credential=floci123456789012/" + date + "/eu-west-1/dynamodb/aws4_request"),
                authorization);
        Matcher matcher = AUTHORIZATION.matcher(authorization);
        assertTrue(matcher.matches(), authorization);
        assertEquals(expectedSignature(request, date), matcher.group(4));
    }

    @Test
    void streamsTargetPrefix() throws Exception {
        start(exchange -> reply(exchange, 200, "{}"));

        client.send(SCOPE, Api.DYNAMODB_STREAMS, "GetRecords", mapper.createObjectNode());

        assertEquals("DynamoDBStreams_20120810.GetRecords", received.get().headers().getFirst("X-Amz-Target"));
    }

    @Test
    void errorBodyPreserved() throws Exception {
        String error = "{\"__type\":\"com.amazonaws.dynamodb.v20120810#TransactionCanceledException\","
                + "\"message\":\"x\",\"CancellationReasons\":[{\"Code\":\"ConditionalCheckFailed\"}]}";
        start(exchange -> reply(exchange, 400, error));

        Reply result = client.send(SCOPE, Api.DYNAMODB, "TransactWriteItems", mapper.createObjectNode());

        assertEquals(400, result.status());
        assertEquals(mapper.readTree(error), result.body());
    }

    @Test
    void unknownOperationPassesThrough() throws Exception {
        start(exchange -> reply(exchange, 200, "{}"));

        client.send(SCOPE, Api.DYNAMODB, "SomeFutureAction", mapper.createObjectNode());

        assertEquals("DynamoDB_20120810.SomeFutureAction", received.get().headers().getFirst("X-Amz-Target"));
    }

    @Test
    void nullBodySendsEmptyObject() throws Exception {
        start(exchange -> reply(exchange, 200, "{}"));

        client.send(SCOPE, Api.DYNAMODB, "ListTables", null);

        assertEquals("{}", new String(received.get().body(), StandardCharsets.UTF_8));
    }

    @Test
    void emptyResponseBodyIsNull() throws Exception {
        start(exchange -> reply(exchange, 200, ""));

        Reply result = client.send(SCOPE, Api.DYNAMODB, "ListTables", mapper.createObjectNode());

        assertEquals(200, result.status());
        assertNull(result.body());
    }

    @Test
    void connectionRefused() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        client = new DynamoDbLocalClient(URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2), Duration.ofSeconds(2), mapper);

        assertThrows(ConnectException.class,
                () -> client.send(SCOPE, Api.DYNAMODB, "ListTables", mapper.createObjectNode()));
    }

    @Test
    void timedOutRequestIsSentOnce() throws Exception {
        start(exchange -> {
            hits.incrementAndGet();
            Thread.sleep(2000);
            reply(exchange, 200, "{}");
        }, Duration.ofMillis(200));

        assertThrows(HttpTimeoutException.class,
                () -> client.send(SCOPE, Api.DYNAMODB, "ListTables", mapper.createObjectNode()));
        Thread.sleep(500);
        assertEquals(1, hits.get());
    }

    @Test
    void aShorterBudgetCutsTheRequestTimeout() throws Exception {
        start(exchange -> {
            Thread.sleep(2000);
            reply(exchange, 200, "{}");
        }, Duration.ofSeconds(5));

        assertThrows(HttpTimeoutException.class, () -> client.send(SCOPE, Api.DYNAMODB, "ListTables",
                mapper.createObjectNode(), Duration.ofMillis(200)));
    }

    @Test
    void aLongerBudgetKeepsTheRequestTimeout() throws Exception {
        start(exchange -> {
            Thread.sleep(2000);
            reply(exchange, 200, "{}");
        }, Duration.ofMillis(200));

        assertThrows(HttpTimeoutException.class, () -> client.send(SCOPE, Api.DYNAMODB, "ListTables",
                mapper.createObjectNode(), Duration.ofSeconds(5)));
    }

    private void start(Responder responder) throws IOException {
        start(responder, Duration.ofSeconds(5));
    }

    private void start(Responder responder, Duration requestTimeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                received.set(new Received(exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
                responder.respond(exchange);
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new DynamoDbLocalClient(endpoint, Duration.ofSeconds(2), requestTimeout, mapper);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private String expectedSignature(Received request, String date) throws Exception {
        Headers headers = request.headers();
        String amzDate = headers.getFirst("X-Amz-Date");
        String canonical = "POST\n" + request.path() + "\n\n"
                + "content-type:" + headers.getFirst("Content-Type") + "\n"
                + "host:" + headers.getFirst("Host") + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "x-amz-target:" + headers.getFirst("X-Amz-Target") + "\n\n"
                + "content-type;host;x-amz-date;x-amz-target\n"
                + SigV4RequestValidator.sha256Hex(request.body());
        String credentialScope = date + "/eu-west-1/dynamodb/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + SigV4RequestValidator.sha256Hex(canonical);
        byte[] key = SigV4RequestValidator.deriveSigningKey("floci", date, "eu-west-1", "dynamodb");
        return SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(key, toSign));
    }
}
