package io.floci.aws.core.common.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the partition discovery, manual assignment and offset seeking calls of
 * {@link KafkaRestClient} against a fake server speaking Karapace's REST Proxy API.
 */
class KafkaRestClientAssignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INSTANCE_PATH = "/consumers/refresh-group/instances/rest-consumer-1";

    private HttpServer fakeServer;
    private KafkaRestClient client;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        fakeServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        fakeServer.createContext("/topics/orders", exchange -> {
            record(exchange);
            respond(exchange, 200, """
                    {"name": "orders", "partitions": [
                      {"partition": 2, "leader": 1, "replicas": []},
                      {"partition": 0, "leader": 1, "replicas": []},
                      {"partition": 1, "leader": 1, "replicas": []}
                    ]}
                    """);
        });
        fakeServer.createContext("/topics/missing", exchange -> {
            record(exchange);
            respond(exchange, 404, "{\"error_code\":40401,\"message\":\"Topic missing not found\"}");
        });
        fakeServer.createContext("/topics/other-404-code", exchange -> {
            record(exchange);
            respond(exchange, 404, "{\"error_code\":40402,\"message\":\"Partition not found\"}");
        });
        fakeServer.createContext("/topics/broken", exchange -> {
            record(exchange);
            respond(exchange, 503, "{\"error_code\":50301,\"message\":\"broker unavailable\"}");
        });
        fakeServer.createContext("/consumers/refresh-group", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"instance_id\":\"rest-consumer-1\","
                    + "\"base_uri\":\"consumers/refresh-group/instances/rest-consumer-1\"}");
        });
        fakeServer.createContext(INSTANCE_PATH + "/assignments", exchange -> {
            record(exchange);
            respond(exchange, 204, "");
        });
        fakeServer.createContext(INSTANCE_PATH + "/positions", exchange -> {
            record(exchange);
            respond(exchange, 204, "");
        });

        fakeServer.start();
        client = new KafkaRestClient(MAPPER, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    @AfterEach
    void tearDown() {
        fakeServer.stop(0);
    }

    private URI restBaseUri() {
        return URI.create("http://localhost:" + fakeServer.getAddress().getPort() + "/");
    }

    private KafkaConsumerHandle newConsumer() {
        return client.createConsumer(restBaseUri(), "refresh-group", "earliest");
    }

    private void record(HttpExchange exchange) throws IOException {
        lastRequestMethod.set(exchange.getRequestMethod());
        lastRequestPath.set(exchange.getRequestURI().toString());
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void listPartitionsReturnsSortedPartitionIds() {
        List<Integer> partitions = client.listPartitions(restBaseUri(), "orders");

        assertEquals(List.of(0, 1, 2), partitions);
        assertEquals("GET", lastRequestMethod.get());
        assertEquals("/topics/orders", lastRequestPath.get());
    }

    @Test
    void listPartitionsOfMissingTopicRaisesTopicNotFound() {
        KafkaTopicNotFoundException thrown = assertThrows(KafkaTopicNotFoundException.class,
                () -> client.listPartitions(restBaseUri(), "missing"));

        assertEquals("missing", thrown.getTopic());
    }

    @Test
    void listPartitionsReportsUnrelated404AsRestClientFailureNotMissingTopic() {
        URI wrongPrefix = URI.create(restBaseUri() + "wrong-prefix/");

        KafkaRestClientException thrown = assertThrows(KafkaRestClientException.class,
                () -> client.listPartitions(wrongPrefix, "orders"));

        assertEquals(404, thrown.getStatusCode());
    }

    @Test
    void listPartitionsReports404WithOtherErrorCodeAsRestClientFailureNotMissingTopic() {
        KafkaRestClientException thrown = assertThrows(KafkaRestClientException.class,
                () -> client.listPartitions(restBaseUri(), "other-404-code"));

        assertEquals(404, thrown.getStatusCode());
    }

    @Test
    void listPartitionsReportsBrokerErrorAsRestClientFailureNotMissingTopic() {
        KafkaRestClientException thrown = assertThrows(KafkaRestClientException.class,
                () -> client.listPartitions(restBaseUri(), "broken"));

        assertEquals(503, thrown.getStatusCode());
    }

    @Test
    void listPartitionsAgainstStoppedProxyRaisesConnectivityError() {
        URI deadUri = restBaseUri();
        fakeServer.stop(0);

        assertThrows(UncheckedIOException.class, () -> client.listPartitions(deadUri, "orders"));
    }

    @Test
    void assignSendsEveryTopicPartition() throws Exception {
        KafkaConsumerHandle consumer = newConsumer();

        client.assign(consumer, List.of(new KafkaTopicPartition("orders", 0), new KafkaTopicPartition("orders", 1)));

        assertEquals("POST", lastRequestMethod.get());
        assertTrue(lastRequestPath.get().endsWith("/assignments"));
        JsonNode partitions = MAPPER.readTree(lastRequestBody.get()).path("partitions");
        assertEquals(2, partitions.size());
        assertEquals("orders", partitions.get(1).path("topic").asText());
        assertEquals(1, partitions.get(1).path("partition").asInt());
    }

    @Test
    void seekSendsExplicitOffsetPerPartition() throws Exception {
        KafkaConsumerHandle consumer = newConsumer();

        client.seek(consumer, List.of(new KafkaOffsetDto("orders", 0, 11L), new KafkaOffsetDto("orders", 1, 5L)));

        assertTrue(lastRequestPath.get().endsWith("/positions"));
        JsonNode offsets = MAPPER.readTree(lastRequestBody.get()).path("offsets");
        assertEquals(2, offsets.size());
        assertEquals(11L, offsets.get(0).path("offset").asLong());
        assertEquals(1, offsets.get(1).path("partition").asInt());
        assertEquals(5L, offsets.get(1).path("offset").asLong());
    }

    @Test
    void seekToBeginningTargetsTheBeginningPositionsResource() throws Exception {
        KafkaConsumerHandle consumer = newConsumer();

        client.seekToBeginning(consumer, List.of(new KafkaTopicPartition("orders", 2)));

        assertTrue(lastRequestPath.get().endsWith("/positions/beginning"));
        JsonNode partition = MAPPER.readTree(lastRequestBody.get()).path("partitions").get(0);
        assertEquals("orders", partition.path("topic").asText());
        assertEquals(2, partition.path("partition").asInt());
    }

    @Test
    void seekWithNothingToPositionSendsNoRequest() {
        KafkaConsumerHandle consumer = newConsumer();
        lastRequestPath.set(null);

        client.seek(consumer, List.of());
        client.seekToBeginning(consumer, List.of());

        assertNull(lastRequestPath.get());
    }
}
