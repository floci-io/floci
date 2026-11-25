package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.eventbridgev2.model.SubscriberV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEventsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────── Body ────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\":1}", "[1,2,3]", "\"str\"", "12.50", "null", "true", "  [1, 2 ,3]  ", "{}", "[]", "0", "-0.0",
            "{\"a\":{\"x\":1},\"b\":{\"x\":2}}", "{\"raw\":\"é😀\"}"})
    void anyJsonValueIsAcceptedWithSurroundingWhitespace(String body) {
        assertTrue(WebhookEvents.parseBody(body.getBytes(StandardCharsets.UTF_8)).isPresent(), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "{\"a\":{\"x\":1,\"x\":2}}", "{\"x\":1,\"x\":2}", "[{\"x\":1,\"x\":2}]", "{\"a\":1}\n{\"b\":2}",
            "{\"a\":1} trailing", "{\"a\":1}{\"b\":2}", "[1] [2]", "1 2", "{\"nan\":NaN}", "{'single':1}",
            "{\"c\":1,/*x*/\"d\":2}", "{\"c\":1,//x\n\"d\":2}", "{\"a\":1,}", "[1,]", "01", "{\"a\":01}", "{\"a\":",
            "{bad"})
    void aBodyThatIsNotOneStrictJsonValueIsRefused(String body) {
        assertTrue(WebhookEvents.parseBody(body.getBytes(StandardCharsets.UTF_8)).isEmpty(), body);
    }

    @Test
    void aByteOrderMarkIsRefused() {
        byte[] json = "{\"bom\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);

        assertTrue(WebhookEvents.parseBody(json).isPresent());
        assertTrue(WebhookEvents.parseBody(withBom).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE", "ISO-8859-1"})
    void aBodyInAnotherEncodingThanUtf8IsRefused(String charset) {
        assertTrue(WebhookEvents.parseBody("{\"a\":\"é\"}".getBytes(Charset.forName(charset))).isEmpty());
    }

    @Test
    void anOverlongUtf8SequenceIsRefused() {
        assertTrue(WebhookEvents.parseBody(new byte[] {'"', (byte) 0xC0, (byte) 0x80, '"'}).isEmpty());
    }

    @Test
    void numbersAreWrittenAsJacksonWritesDoubles() throws Exception {
        String body = "{\"n\":1.0,\"e\":1e2,\"big\":12345678901234567890123,\"f\":0.1000,\"neg\":-0.0,\"exp\":1E-7,"
                + "\"twelve\":12.50}";

        JsonNode data = WebhookEvents.parseBody(body.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertEquals("{\"n\":1.0,\"e\":100.0,\"big\":12345678901234567890123,\"f\":0.1,\"neg\":-0.0,"
                + "\"exp\":1.0E-7,\"twelve\":12.5}", data.toString());
    }

    @Test
    void escapesAreDecodedAndTheOrderOfKeysIsKept() {
        String body = "{ \"b\" : 1 ,\n \"a\" : [ 1 , 2 ] , \"u\":\"\\u00e9\\u3042\\ud83d\\ude00\", \"s\":\"\\/\","
                + "\"t\":\"\\u0041\\n\" }";

        JsonNode data = WebhookEvents.parseBody(body.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertEquals("{\"b\":1,\"a\":[1,2],\"u\":\"éあ😀\",\"s\":\"/\",\"t\":\"A\\n\"}",
                data.toString());
    }

    // ──────────────────────────── Metadata ────────────────────────────

    @Test
    void repeatedHeadersAreJoinedWithACommaAndASpaceAndTheNameIsLowerCased() {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry("X-Meta", "a"));
        headers.add(Map.entry("x-meta", "b"));
        headers.add(Map.entry("X-Other", "  padded \t"));

        Map<String, String> values = WebhookEvents.headerValues(headers);

        assertEquals("a, b", values.get("x-meta"));
        assertEquals("padded", values.get("x-other"));
    }

    @Test
    void metadataHoldsTheListedHeadersUnderLowerCaseNames() throws Exception {
        Map<String, String> headers = Map.of("x-b", "2", "x-a", "1", "x-unlisted", "3");

        Map<String, String> metadata = WebhookEvents.metadata(MAPPER.readTree("[\"X-A\",\"X-B\",\"X-Missing\"]"),
                headers);

        assertEquals(Map.of("x-a", "1", "x-b", "2"), metadata);
    }

    @Test
    void metadataIsInTheOrderOfAHashMapSizedForTheListAndNotInTheOrderOfTheList() throws Exception {
        JsonNode three = MAPPER.readTree("[\"X-Event-Type\",\"User-Agent\",\"Content-Type\"]");
        JsonNode four = MAPPER.readTree("[\"webhook-id\",\"X-Multi\",\"X-Empty\",\"X-Long\"]");

        assertEquals(List.of("x-event-type", "content-type", "user-agent"), List.copyOf(WebhookEvents.metadata(three,
                Map.of("user-agent", "probe/1.0", "content-type", "application/json", "x-event-type", "order.created"))
                .keySet()));
        assertEquals(List.of("x-multi", "webhook-id"), List.copyOf(WebhookEvents.metadata(four,
                Map.of("webhook-id", "same", "x-multi", "v1")).keySet()));
        assertEquals(List.of("x-multi", "webhook-id", "x-long"), List.copyOf(WebhookEvents.metadata(four,
                Map.of("webhook-id", "msg_l", "x-multi", "padded", "x-long", "L".repeat(1024))).keySet()));
        assertEquals(List.of("x-meta", "x-two"), List.copyOf(WebhookEvents.metadata(
                MAPPER.readTree("[\"X-Meta\",\"X-Two\"]"), Map.of("x-two", "t", "x-meta", "m1")).keySet()));
    }

    @Test
    void anEmptyValueIsDroppedAndAValueOver1024BytesIsDroppedButOneOf1024IsKept() throws Exception {
        Map<String, String> headers = Map.of("x-empty", "  ", "x-max", "a".repeat(1024), "x-over", "a".repeat(1025),
                "x-wide", "é".repeat(513));

        Map<String, String> metadata = WebhookEvents.metadata(
                MAPPER.readTree("[\"x-empty\",\"x-max\",\"x-over\",\"x-wide\"]"), headers);

        assertEquals(List.of("x-max"), List.copyOf(metadata.keySet()));
    }

    @Test
    void aSourceThatListsNoHeadersCopiesNone() throws Exception {
        assertTrue(WebhookEvents.metadata(MAPPER.readTree("[]"), Map.of("x-a", "1")).isEmpty());
        assertTrue(WebhookEvents.metadata(MAPPER.missingNode(), Map.of("x-a", "1")).isEmpty());
    }

    // ──────────────────────────── The event ────────────────────────────

    @Test
    void systemMetadataHasExactlyTheMeasuredKeysInOrder() throws Exception {
        PublishedEvent event = event("{\"k\":1}", Map.of("x-meta", "m"), "group-1",
                Instant.parse("2026-11-25T05:59:02.982123456Z"), "10000000000000003");

        assertEquals("{\"EventGroupId\":\"group-1\",\"DeduplicationId\":\"group-1\","
                + "\"ContentType\":\"application/json\",\"aws:IngestionTime\":\"2026-11-25T05:59:02.982Z\","
                + "\"aws:EventId\":\"event-1\",\"aws:SequenceNumber\":\"10000000000000003\","
                + "\"aws:DeliveryType\":\"LIVE\"}", event.systemMetadata().toString());
        assertEquals("{\"x-meta\":\"m\"}", event.metadata().toString());
    }

    @Test
    void theIngestionTimeAlwaysHasThreeFractionDigits() throws Exception {
        assertEquals("2026-11-25T05:59:02.000Z", event("1", Map.of(), "g", Instant.parse("2026-11-25T05:59:02Z"), "1")
                .systemMetadata().get("aws:IngestionTime").asText());
        assertEquals("2026-11-25T05:59:02.500Z", event("1", Map.of(), "g", Instant.parse("2026-11-25T05:59:02.5Z"),
                "1").systemMetadata().get("aws:IngestionTime").asText());
    }

    @Test
    void aWithMetadataDeliveryIsTheThreePartsAndARawOneIsTheBodyAsSent() throws Exception {
        String body = "{ \"e\" : 1e2,\n \"s\":\"\\/\" }";
        PublishedEvent event = event(body, Map.of("x-meta", "m"), "g", Instant.parse("2026-11-25T05:59:02.982Z"), "7");

        assertEquals("{\"Data\":{\"e\":100.0,\"s\":\"/\"},\"Metadata\":{\"x-meta\":\"m\"},\"SystemMetadata\":"
                + event.systemMetadata() + "}", EventBridgeV2Delivery.body(subscriber("WITH_METADATA"), event));
        assertEquals(body, EventBridgeV2Delivery.body(subscriber("RAW"), event));
        assertEquals(body, EventBridgeV2Delivery.body(subscriber(null), event));
    }

    @Test
    void aRawDeliveryOfAPutEventsEventIsStillItsData() {
        PublishedEvent event = new PublishedEvent(MAPPER.createObjectNode().put("a", 1),
                MAPPER.createObjectNode(), MAPPER.createObjectNode());

        assertEquals("{\"a\":1}", EventBridgeV2Delivery.body(subscriber("RAW"), event));
    }

    // ──────────────────────────── Deduplication hash ────────────────────────────

    @Test
    void theHashIsA64CharacterLowerCaseHexDigest() {
        String hash = WebhookEvents.contentHash("{}".getBytes(StandardCharsets.UTF_8), Map.of());

        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
    }

    @Test
    void theHashFollowsTheBodyBytesAndNotTheValueTheyHold() {
        byte[] compact = "{\"dd\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] spaced = "{ \"dd\" : 1 }".getBytes(StandardCharsets.UTF_8);

        assertEquals(WebhookEvents.contentHash(compact, Map.of()), WebhookEvents.contentHash(compact.clone(), Map.of()));
        assertNotEquals(WebhookEvents.contentHash(compact, Map.of()), WebhookEvents.contentHash(spaced, Map.of()));
    }

    @Test
    void theHashFollowsTheCopiedMetadata() {
        byte[] body = "{\"dd\":2}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> one = Map.of("x-meta", "m1");
        Map<String, String> two = new LinkedHashMap<>();
        two.put("x-meta", "m1");
        two.put("x-two", "t");

        assertNotEquals(WebhookEvents.contentHash(body, Map.of()), WebhookEvents.contentHash(body, one));
        assertNotEquals(WebhookEvents.contentHash(body, one), WebhookEvents.contentHash(body, two));
        assertEquals(WebhookEvents.contentHash(body, two), WebhookEvents.contentHash(body, new LinkedHashMap<>(two)));
        assertNotEquals(WebhookEvents.contentHash(body, Map.of("x-meta", "m2")), WebhookEvents.contentHash(body, one));
    }

    @Test
    void theHashDoesNotFollowTheOrderTheHeadersAreCopiedIn() {
        byte[] body = "{\"dd\":2}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("c", "1");
        forward.put("d", "2");
        Map<String, String> backward = new LinkedHashMap<>();
        backward.put("d", "2");
        backward.put("c", "1");

        assertEquals(WebhookEvents.contentHash(body, forward), WebhookEvents.contentHash(body, backward));
    }

    @Test
    void theHashEncodingCannotBeConfusedByMovingBytesBetweenParts() {
        byte[] body = "ab".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(WebhookEvents.contentHash("a".getBytes(StandardCharsets.UTF_8), Map.of("b", "c")),
                WebhookEvents.contentHash(body, Map.of("", "c")));
        assertNotEquals(WebhookEvents.contentHash(body, Map.of("a", "bc")),
                WebhookEvents.contentHash(body, Map.of("ab", "c")));
        assertFalse(WebhookEvents.contentHash(body, Map.of()).isEmpty());
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static PublishedEvent event(String body, Map<String, String> metadata, String groupId, Instant ingested,
                                        String sequenceNumber) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Optional<JsonNode> data = WebhookEvents.parseBody(bytes);
        return WebhookEvents.event(data.orElseThrow(), bytes, metadata, groupId, ingested, "event-1", sequenceNumber);
    }

    private static SubscriberV2 subscriber(String transformerType) {
        SubscriberV2 subscriber = new SubscriberV2();
        if (transformerType != null) {
            subscriber.setTransformer(MAPPER.createObjectNode().put("Type", transformerType));
        }
        return subscriber;
    }
}
