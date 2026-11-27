package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.Digests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The event a webhook request becomes: its body read as JSON, the headers copied into {@code Metadata}, the
 * system metadata and the hash {@code CONTENT_BASED} deduplication compares.
 */
final class WebhookEvents {
    static final int MAX_BODY_BYTES = 1_048_576;
    static final String CONTENT_BASED = "CONTENT_BASED";

    private static final int MAX_METADATA_VALUE_BYTES = 1024;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    static final DateTimeFormatter INGESTION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private WebhookEvents() {
    }

    /**
     * The body as one JSON value of any type, or empty when it is not one. A duplicate key at any depth, a second
     * value, text after the value, a byte order mark and text that is not UTF-8 make it not one.
     */
    static Optional<JsonNode> parseBody(byte[] body) {
        if (startsWithBom(body)) {
            return Optional.empty();
        }
        try {
            // Jackson reads UTF-16 and UTF-32 from bytes by itself, so it is given the text.
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JsonNode value = STRICT.readTree(text);
            return value == null || value.isMissingNode() ? Optional.empty() : Optional.of(value);
        } catch (IOException ignored) {
            // Not UTF-8 or not one JSON value.
            return Optional.empty();
        }
    }

    private static boolean startsWithBom(byte[] body) {
        return body.length >= UTF8_BOM.length && body[0] == UTF8_BOM[0] && body[1] == UTF8_BOM[1]
                && body[2] == UTF8_BOM[2];
    }

    /** The request's headers by lower-case name, a repeated header's values joined with {@code ", "}, trimmed. */
    static Map<String, String> headerValues(Iterable<Map.Entry<String, String>> headers) {
        Map<String, String> joined = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers) {
            joined.merge(header.getKey().toLowerCase(Locale.ROOT), header.getValue(), (a, b) -> a + ", " + b);
        }
        joined.replaceAll((name, value) -> trim(value));
        return joined;
    }

    /**
     * The headers the source lists, under lower-case names. A header that is absent or empty, or whose value is
     * longer than 1024 UTF-8 bytes, is left out. AWS delivers them in the order of a hash map sized for the list,
     * not in the order of the list.
     */
    static Map<String, String> metadata(JsonNode listed, Map<String, String> headers) {
        Map<String, String> copied = new HashMap<>(listed.size());
        for (JsonNode listedName : listed) {
            String name = listedName.asText().toLowerCase(Locale.ROOT);
            String value = headers.get(name);
            if (value == null) {
                continue;
            }
            value = trim(value);
            if (!value.isEmpty() && value.getBytes(StandardCharsets.UTF_8).length <= MAX_METADATA_VALUE_BYTES) {
                copied.put(name, value);
            }
        }
        return copied;
    }

    /**
     * The hash of the body bytes and the copied metadata, each part with its length so no two requests share an
     * encoding. The headers are hashed by name order, so the hash does not follow the order they are delivered in.
     * It holds nothing of the source, so two sources give the same hash for the same request.
     */
    static String contentHash(byte[] body, Map<String, String> metadata) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        field(encoded, body);
        encoded.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(metadata.size()).array());
        for (Map.Entry<String, String> header : new TreeMap<>(metadata).entrySet()) {
            field(encoded, header.getKey().getBytes(StandardCharsets.UTF_8));
            field(encoded, header.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return Digests.sha256Hex(encoded.toByteArray());
    }

    private static void field(ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    static PublishedEvent event(JsonNode data, byte[] rawBody, Map<String, String> metadata, String groupId,
                                Instant ingested, String eventId, String sequenceNumber) {
        ObjectNode copied = JsonNodeFactory.instance.objectNode();
        metadata.forEach(copied::put);
        ObjectNode system = JsonNodeFactory.instance.objectNode();
        system.put("EventGroupId", groupId);
        system.put("DeduplicationId", groupId);
        system.put("ContentType", "application/json");
        system.put("aws:IngestionTime", INGESTION_TIME.format(ingested));
        system.put("aws:EventId", eventId);
        system.put("aws:SequenceNumber", sequenceNumber);
        system.put("aws:DeliveryType", "LIVE");
        return new PublishedEvent(data, copied, system, new String(rawBody, StandardCharsets.UTF_8));
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlank(value.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}
