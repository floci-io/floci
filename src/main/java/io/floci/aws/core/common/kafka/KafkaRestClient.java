package io.floci.aws.core.common.kafka;

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
 * Pandaproxy) that Floci services need to consume a Kafka topic: discover a topic's partitions,
 * create a consumer instance, subscribe or assign partitions manually, seek, poll for records,
 * commit offsets, close. Uses the JDK {@link HttpClient}, the same approach
 * Floci already uses for other REST-shaped backends (e.g. {@code KubernetesApiClient}), instead
 * of embedding {@code kafka-clients} directly: see #2916, where kafka-clients (and the
 * zstd-jni/lz4-java it pulls in) was reachable in every native image regardless of whether a Kafka
 * consumer was ever used.
 *
 * <p>Every consumer instance lives on exactly one REST Proxy process for its whole lifetime (the
 * proxy holds the actual Kafka client state), so all calls for a given {@link KafkaConsumerHandle}
 * must keep going to the {@code base_uri} that instance's creation call returned.
 */
@ApplicationScoped
public class KafkaRestClient {

    private static final String CONSUMER_CONTENT_TYPE = "application/vnd.kafka.v2+json";
    private static final String BINARY_RECORDS_ACCEPT = "application/vnd.kafka.binary.v2+json";
    private static final int ERROR_CODE_TOPIC_NOT_FOUND = 40401;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    KafkaRestClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build());
    }

    /** Test-only: injects an explicit client, e.g. one pointed at a fake REST Proxy server. */
    KafkaRestClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public KafkaConsumerHandle createConsumer(URI restBaseUri, String groupId, String offsetReset) {
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

    public void subscribe(KafkaConsumerHandle consumer, List<String> topics) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode topicsNode = body.putArray("topics");
        topics.forEach(topicsNode::add);
        send("POST", instanceUri(consumer, "subscription"), body);
    }

    /**
     * Lists the partition ids of a topic ({@code GET /topics/{topic}/partitions}). A topic the broker
     * does not know raises {@link KafkaTopicNotFoundException}, distinct from the
     * {@link KafkaRestClientException} / {@link UncheckedIOException} a broker or proxy failure raises,
     * so a caller can tell a missing topic from an unreachable cluster.
     *
     * <p>{@code /topics/{topic}/partitions} is used rather than {@code /topics/{topic}} because Karapace
     * answers a missing topic on the latter with 40403 (from the admin metadata lookup), while the
     * former maps it to 40401 and returns the partition array directly.
     */
    public List<Integer> listPartitions(URI restBaseUri, String topic) {
        JsonNode response;
        try {
            response = send("GET", restBaseUri.resolve("topics/" + urlEncode(topic) + "/partitions"), null);
        } catch (KafkaRestClientException e) {
            if (e.getStatusCode() == 404 && isTopicNotFoundError(e.getResponseBody())) {
                throw new KafkaTopicNotFoundException(topic, e);
            }
            throw e;
        }
        List<Integer> partitions = new ArrayList<>();
        for (JsonNode partitionNode : response) {
            partitions.add(partitionNode.path("partition").asInt());
        }
        partitions.sort(Integer::compare);
        return partitions;
    }

    private boolean isTopicNotFoundError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null) {
                return false;
            }
            JsonNode errorCode = root.path("error_code");
            return errorCode.isIntegralNumber() && errorCode.asInt() == ERROR_CODE_TOPIC_NOT_FOUND;
        } catch (IOException ignored) {
            // Non-JSON 404 body (e.g. wrong base path or reverse proxy page) is not a Karapace topic-not-found error.
            return false;
        }
    }

    /**
     * Assigns partitions manually instead of {@link #subscribe}, so a poll covers exactly these
     * partitions with no group rebalance deciding which ones this consumer owns.
     */
    public void assign(KafkaConsumerHandle consumer, List<KafkaTopicPartition> partitions) {
        send("POST", instanceUri(consumer, "assignments"), partitionsBody(partitions));
    }

    /**
     * Positions each partition at the given offset, which is the next offset to read, so the caller
     * controls where a poll resumes instead of relying on broker-side committed group offsets.
     */
    public void seek(KafkaConsumerHandle consumer, List<KafkaOffsetDto> offsets) {
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
        send("POST", instanceUri(consumer, "positions"), body);
    }

    public void seekToBeginning(KafkaConsumerHandle consumer, List<KafkaTopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return;
        }
        send("POST", instanceUri(consumer, "positions/beginning"), partitionsBody(partitions));
    }

    private ObjectNode partitionsBody(List<KafkaTopicPartition> partitions) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode partitionsNode = body.putArray("partitions");
        for (KafkaTopicPartition partition : partitions) {
            ObjectNode partitionNode = partitionsNode.addObject();
            partitionNode.put("topic", partition.topic());
            partitionNode.put("partition", partition.partition());
        }
        return body;
    }

    public List<KafkaRecordDto> poll(KafkaConsumerHandle consumer, Duration timeout) {
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
    public void commit(KafkaConsumerHandle consumer, List<KafkaOffsetDto> offsets) {
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
    public void close(KafkaConsumerHandle consumer) {
        try {
            send("DELETE", consumer.baseUri(), null);
        } catch (KafkaRestClientException e) {
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
                throw new KafkaRestClientException(method, uri.toString(), response.statusCode(),
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
