package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisListStreamsTokenTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"));
    private final KinesisJsonHandler handler = new KinesisJsonHandler(service, mapper, 300_000);

    @Test
    void collectsAllOneHundredFiftyStreamsUsingOnlyNextToken() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String name = String.format("stream-%03d", i);
            service.createStream(name, 1, REGION);
            expected.add(name);
        }
        ObjectNode first = list(Map.of());
        List<String> seen = new ArrayList<>(names(first));
        assertEquals(100, seen.size());
        assertTrue(first.path("HasMoreStreams").asBoolean());
        assertTrue(first.hasNonNull("NextToken"));

        ObjectNode last = list(Map.of("NextToken", first.path("NextToken").asText()));
        assertEquals(50, names(last).size());
        seen.addAll(names(last));
        assertEquals(expected, seen);
        assertFalse(last.path("HasMoreStreams").asBoolean());
        assertFalse(last.has("NextToken"));
    }

    @Test
    void resumesAfterADeletedCursorAndAllowsADifferentPageSize() {
        createThreeStreams();
        ObjectNode first = list(Map.of("Limit", 1));
        assertEquals(List.of("a"), names(first));
        assertTrue(first.hasNonNull("NextToken"));
        service.deleteStream("a", REGION);

        ObjectNode last = list(Map.of("NextToken", first.path("NextToken").asText(), "Limit", 2));
        assertEquals(List.of("b", "c"), names(last));
        assertFalse(last.path("HasMoreStreams").asBoolean());
        assertFalse(last.has("NextToken"));
        assertFalse(list(Map.of("ExclusiveStartStreamName", "z")).has("NextToken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"!\"", "\"YQ\""})
    void rejectsMalformedStringTokens(String token) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("{\"NextToken\":" + token + "}");
        AwsException error = assertThrows(AwsException.class, () -> list(request));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "{}", "[]", "false"})
    void rejectsNonStringTokensThroughTheModeledShape(String token) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("{\"NextToken\":" + token + "}");
        AwsException error = assertThrows(AwsException.class, () -> list(request));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void rejectsATokenFromAnotherRegionOrOperation() {
        createThreeStreams();
        ObjectNode first = list(Map.of("Limit", 1));
        assertTrue(first.hasNonNull("NextToken"));
        AwsException regionError = assertThrows(AwsException.class,
                () -> handler.handle("ListStreams", mapper.valueToTree(Map.of(
                        "NextToken", first.path("NextToken").asText())), "eu-west-1"));
        assertEquals("InvalidArgumentException", regionError.getErrorCode());
        AwsException operationError = assertThrows(AwsException.class,
                () -> list(Map.of("NextToken", KinesisJsonHandler.encodeNextToken("a", "shard-0",
                        System.currentTimeMillis()))));
        assertEquals("InvalidArgumentException", operationError.getErrorCode());
        AwsException reverseOperationError = assertThrows(AwsException.class,
                () -> handler.handle("ListShards", mapper.valueToTree(Map.of(
                        "NextToken", first.path("NextToken").asText())), REGION));
        assertEquals("InvalidArgumentException", reverseOperationError.getErrorCode());
    }

    @Test
    void rejectsFutureTokens() {
        String token = KinesisJsonHandler.encodeNextToken("ListStreams|" + REGION,
                Base64Url.encode("a"), System.currentTimeMillis() + 60_000);
        AwsException error = assertThrows(AwsException.class, () -> list(Map.of("NextToken", token)));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListStreams", "ListShards"})
    void usesConfiguredExpiryForBothListOperations(String operation) {
        service.createStream("a", 3, REGION);
        service.createStream("b", 1, REGION);
        KinesisJsonHandler expiringHandler = new KinesisJsonHandler(service, mapper, 0);
        Map<String, ?> firstRequest = "ListStreams".equals(operation)
                ? Map.of("Limit", 1) : Map.of("StreamName", "a", "MaxResults", 1);
        String token;
        try (Response first = expiringHandler.handle(operation, mapper.valueToTree(firstRequest), REGION)) {
            ObjectNode page = (ObjectNode) first.getEntity();
            assertTrue(page.hasNonNull("NextToken"));
            token = page.path("NextToken").asText();
        }

        AwsException error = assertThrows(AwsException.class,
                () -> expiringHandler.handle(operation, mapper.valueToTree(Map.of("NextToken", token)), REGION));
        assertEquals("ExpiredNextTokenException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListStreams", "ListShards"})
    void acceptsTokensMintedByThePreviousCodec(String operation) {
        service.createStream("a", 3, REGION);
        service.createStream("b", 1, REGION);
        String shardId = service.describeStream("a", REGION).getShards().getFirst().getShardId();
        String oldPayload = "ListStreams".equals(operation)
                ? "ListStreams|" + REGION + "|" + Base64Url.encode("a") : "a|" + shardId;
        String token = Base64Url.encode(oldPayload + "|" + System.currentTimeMillis());
        try (Response response = handler.handle(operation, mapper.valueToTree(Map.of("NextToken", token)), REGION)) {
            assertEquals(200, response.getStatus());
            ObjectNode page = (ObjectNode) response.getEntity();
            if ("ListStreams".equals(operation)) {
                assertEquals(List.of("b"), names(page));
            } else {
                assertEquals(2, page.path("Shards").size());
                assertEquals(service.describeStream("a", REGION).getShards().get(1).getShardId(),
                        page.path("Shards").get(0).path("ShardId").asText());
            }
            assertFalse(page.has("NextToken"));
        }
    }

    private void createThreeStreams() {
        for (String name : List.of("a", "b", "c")) {
            service.createStream(name, 1, REGION);
        }
    }

    private ObjectNode list(Object request) {
        try (Response response = handler.handle("ListStreams", mapper.valueToTree(request), REGION)) {
            assertEquals(200, response.getStatus());
            return (ObjectNode) response.getEntity();
        }
    }

    private List<String> names(ObjectNode response) {
        return mapper.convertValue(response.path("StreamNames"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
