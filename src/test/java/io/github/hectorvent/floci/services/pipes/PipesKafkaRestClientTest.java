package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link PipesKafkaRestClient} against a fake server speaking Karapace's REST Proxy API. */
class PipesKafkaRestClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer fakeServer;
    private PipesKafkaRestClient client;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        fakeServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        fakeServer.createContext("/consumers/orders-group", exchange -> {
            record(exchange);
            // Karapace returns base_uri as a path relative to the proxy root (not an absolute URL
            // as the Confluent REST Proxy reference spec shows) - this fixture matches that.
            String response = "{\"instance_id\":\"rest-consumer-1\","
                    + "\"base_uri\":\"consumers/orders-group/instances/rest-consumer-1\"}";
            respond(exchange, 200, response);
        });

        fakeServer.createContext("/consumers/orders-group/instances/rest-consumer-1/subscription", exchange -> {
            record(exchange);
            respond(exchange, 204, "");
        });

        fakeServer.createContext("/consumers/orders-group/instances/rest-consumer-1/records", exchange -> {
            record(exchange);
            byte[] key = "customer-1".getBytes(StandardCharsets.UTF_8);
            byte[] value = "{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8);
            byte[] headerValue = "trace-1".getBytes(StandardCharsets.UTF_8);
            String response = """
                    [
                      {
                        "topic": "orders",
                        "partition": 0,
                        "offset": 42,
                        "timestamp": 1700000000000,
                        "key": "%s",
                        "value": "%s",
                        "headers": [{"name": "traceId", "value": "%s"}]
                      }
                    ]
                    """.formatted(base64(key), base64(value), base64(headerValue));
            respond(exchange, 200, response);
        });

        fakeServer.createContext("/consumers/orders-group/instances/rest-consumer-1/offsets", exchange -> {
            record(exchange);
            respond(exchange, 200, "");
        });

        fakeServer.createContext("/consumers/orders-group/instances/rest-consumer-1", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                record(exchange);
                respond(exchange, 204, "");
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });

        fakeServer.start();
        client = new PipesKafkaRestClient(MAPPER, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    @AfterEach
    void tearDown() {
        fakeServer.stop(0);
    }

    private URI restBaseUri() {
        return URI.create("http://localhost:" + fakeServer.getAddress().getPort() + "/");
    }

    private void record(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        lastRequestMethod.set(exchange.getRequestMethod());
        lastRequestPath.set(exchange.getRequestURI().toString());
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @Test
    void createConsumerParsesInstanceIdAndBaseUri() {
        KafkaConsumerHandle consumer = client.createConsumer(restBaseUri(), "orders-group", "latest");

        assertEquals("rest-consumer-1", consumer.instanceId());
        assertEquals("POST", lastRequestMethod.get());
        assertTrue(consumer.baseUri().toString().endsWith("/consumers/orders-group/instances/rest-consumer-1"));
    }

    @Test
    void subscribeSendsTopicList() throws Exception {
        KafkaConsumerHandle consumer = client.createConsumer(restBaseUri(), "orders-group", "earliest");

        client.subscribe(consumer, List.of("orders"));

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertEquals("orders", body.path("topics").get(0).asText());
    }

    @Test
    void pollDecodesBinaryKeyValueAndHeaders() {
        KafkaConsumerHandle consumer = client.createConsumer(restBaseUri(), "orders-group", "latest");

        List<KafkaRecordDto> records = client.poll(consumer, Duration.ofMillis(100));

        assertEquals(1, records.size());
        KafkaRecordDto record = records.get(0);
        assertEquals("orders", record.topic());
        assertEquals(0, record.partition());
        assertEquals(42L, record.offset());
        assertEquals(1700000000000L, record.timestamp());
        assertArrayEquals("customer-1".getBytes(StandardCharsets.UTF_8), record.key());
        assertArrayEquals("{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8), record.value());
        assertEquals(1, record.headers().size());
        assertEquals("traceId", record.headers().get(0).key());
        assertArrayEquals("trace-1".getBytes(StandardCharsets.UTF_8), record.headers().get(0).value());
    }

    @Test
    void commitSpecificOffsetsSendsOffsetsArray() throws Exception {
        KafkaConsumerHandle consumer = client.createConsumer(restBaseUri(), "orders-group", "latest");

        client.commit(consumer, List.of(new KafkaOffsetDto("orders", 0, 43L)));

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        JsonNode offset = body.path("offsets").get(0);
        assertEquals("orders", offset.path("topic").asText());
        assertEquals(0, offset.path("partition").asInt());
        assertEquals(43L, offset.path("offset").asLong());
    }

    @Test
    void closeDeletesTheConsumerInstance() {
        KafkaConsumerHandle consumer = client.createConsumer(restBaseUri(), "orders-group", "latest");

        client.close(consumer);

        assertEquals("DELETE", lastRequestMethod.get());
    }
}
