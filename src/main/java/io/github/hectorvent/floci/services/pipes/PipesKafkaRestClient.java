package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Thin REST client for the subset of the Kafka REST Proxy v2 API (Karapace, Redpanda's
 * Pandaproxy) that Pipes needs to consume a Kafka topic: create a consumer instance, subscribe,
 * poll for records, commit offsets, close. Uses the JDK {@link HttpClient}, the same approach
 * Floci already uses for other REST-shaped backends (e.g. {@code KubernetesApiClient}), instead
 * of embedding {@code kafka-clients} directly: see #2916, where kafka-clients (and the
 * zstd-jni/lz4-java it pulls in) was reachable in every native image regardless of whether a Pipe
 * ever used a Kafka source.
 *
 * <p>Every consumer instance lives on exactly one REST Proxy process for its whole lifetime (the
 * proxy holds the actual Kafka client state), so all calls for a given {@link KafkaConsumerHandle}
 * must keep going to the {@code base_uri} that instance's creation call returned.
 */
@ApplicationScoped
class PipesKafkaRestClient {

    private static final String CONSUMER_CONTENT_TYPE = "application/vnd.kafka.v2+json";
    private static final String BINARY_RECORDS_ACCEPT = "application/vnd.kafka.binary.v2+json";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    PipesKafkaRestClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build());
    }

    /** Test-only: injects an explicit client, e.g. one pointed at a fake REST Proxy server. */
    PipesKafkaRestClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    KafkaConsumerHandle createConsumer(URI restBaseUri, String groupId, String offsetReset) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("format", "binary");
        body.put("auto.offset.reset", offsetReset);
        body.put("auto.commit.enable", "false");

        JsonNode response = send("POST", restBaseUri.resolve("consumers/" + urlEncode(groupId)), body);
        // Karapace returns base_uri as a path relative to the proxy root, not an absolute URL,
        // unlike the Confluent REST Proxy reference spec; resolve() handles both correctly.
        return new KafkaConsumerHandle(
                response.path("instance_id").asText(),
                restBaseUri.resolve(response.path("base_uri").asText()));
    }

    void subscribe(KafkaConsumerHandle consumer, List<String> topics) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode topicsNode = body.putArray("topics");
        topics.forEach(topicsNode::add);
        send("POST", instanceUri(consumer, "subscription"), body);
    }

    List<KafkaRecordDto> poll(KafkaConsumerHandle consumer, Duration timeout) {
        URI uri = URI.create(instanceUri(consumer, "records") + "?timeout=" + timeout.toMillis());
        JsonNode response = sendWithAccept("GET", uri, null, BINARY_RECORDS_ACCEPT);

        List<KafkaRecordDto> records = new ArrayList<>();
        response.forEach(node -> records.add(toRecord(node)));
        return records;
    }

    /**
     * Commits the given offsets. Karapace requires an explicit offsets list (unlike the Confluent
     * REST Proxy reference spec, an empty body is rejected with 400 rather than committing
     * everything consumed), so callers always compute what to commit themselves.
     */
    void commit(KafkaConsumerHandle consumer, List<KafkaOffsetDto> offsets) {
        if (offsets.isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode offsetsNode = body.putArray("offsets");
        for (KafkaOffsetDto offset : offsets) {
            ObjectNode offsetNode = offsetsNode.addObject();
            offsetNode.put("topic", offset.topic());
            offsetNode.put("partition", offset.partition());
            offsetNode.put("offset", offset.offset());
        }
        send("POST", instanceUri(consumer, "offsets"), body);
    }

    /** No-op if the consumer instance is already gone (e.g. the REST Proxy container restarted). */
    void close(KafkaConsumerHandle consumer) {
        try {
            send("DELETE", consumer.baseUri(), null);
        } catch (PipesKafkaRestClientException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
        }
    }

    private KafkaRecordDto toRecord(JsonNode node) {
        List<KafkaHeaderDto> headers = new ArrayList<>();
        for (JsonNode headerNode : node.path("headers")) {
            headers.add(new KafkaHeaderDto(
                    headerNode.path("name").asText(),
                    base64Decode(headerNode.path("value"))));
        }
        return new KafkaRecordDto(
                node.path("topic").asText(),
                node.path("partition").asInt(),
                node.path("offset").asLong(),
                node.path("timestamp").asLong(0),
                KafkaRecordDto.DEFAULT_TIMESTAMP_TYPE,
                base64Decode(node.path("key")),
                base64Decode(node.path("value")),
                headers);
    }

    private static byte[] base64Decode(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return Base64.getDecoder().decode(value.asText());
    }

    private URI instanceUri(KafkaConsumerHandle consumer, String segment) {
        String base = consumer.baseUri().toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + segment);
    }

    private JsonNode send(String method, URI uri, JsonNode body) {
        return sendWithAccept(method, uri, body, CONSUMER_CONTENT_TYPE);
    }

    private JsonNode sendWithAccept(String method, URI uri, JsonNode body, String accept) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", accept);
            if (body != null) {
                builder.header("Content-Type", CONSUMER_CONTENT_TYPE)
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new PipesKafkaRestClientException(method, uri.toString(), response.statusCode(),
                        new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body().length == 0 ? NullNode.getInstance() : objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Kafka REST Proxy " + method + " " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Kafka REST Proxy " + method + " " + uri, e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
