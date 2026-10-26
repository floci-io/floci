package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisListStreamsTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"));
    private final KinesisJsonHandler handler = new KinesisJsonHandler(service, mapper, 300_000);

    @Test
    void pagesSortedNamesUsingAnExclusiveStartName() throws Exception {
        create("zeta", REGION);
        create("alpha", REGION);
        create("mu", REGION);
        create("other-region", "eu-west-1");

        ObjectNode first = list(Map.of("Limit", 1));
        assertEquals(List.of("alpha"), names(first));
        assertTrue(first.path("HasMoreStreams").asBoolean());
        ObjectNode second = list(Map.of("Limit", 1, "ExclusiveStartStreamName", "alpha"));
        assertEquals(List.of("mu"), names(second));
        assertTrue(second.path("HasMoreStreams").asBoolean());
        ObjectNode last = list(Map.of("Limit", 1, "ExclusiveStartStreamName", "mu"));
        assertEquals(List.of("zeta"), names(last));
        assertFalse(last.path("HasMoreStreams").asBoolean());
        ObjectNode empty = list(Map.of("ExclusiveStartStreamName", "zeta"));
        assertEquals(List.of(), names(empty));
        assertFalse(empty.path("HasMoreStreams").asBoolean());
        assertEquals(List.of("alpha", "mu", "zeta"), names(list(Map.of("Limit", 3))));
        assertFalse(list(Map.of("Limit", 3)).path("HasMoreStreams").asBoolean());
    }

    @Test
    void returnsAStreamSummaryForEveryNameOnThePage() {
        create("alpha", REGION);
        create("mu", REGION);

        ObjectNode page = list(Map.of("Limit", 1));

        assertEquals(1, page.path("StreamSummaries").size());
        JsonNode summary = page.path("StreamSummaries").get(0);
        assertEquals("alpha", summary.path("StreamName").asText());
        assertEquals("arn:aws:kinesis:us-east-1:000000000000:stream/alpha", summary.path("StreamARN").asText());
        assertTrue(summary.path("StreamStatus").isTextual());
        assertEquals("PROVISIONED", summary.path("StreamModeDetails").path("StreamMode").asText());
        assertTrue(summary.path("StreamCreationTimestamp").isNumber());
    }

    // AWS sends StreamSummaries even when there are no streams. The CLI's text output writes a
    // None line for each paginator result key missing from the page.
    @Test
    void returnsEmptyStreamSummariesForAnEmptyRegion() {
        ObjectNode page = list(Map.of());

        assertTrue(page.path("StreamSummaries").isArray());
        assertTrue(page.path("StreamSummaries").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Limit\":101}", "{\"Limit\":10000}"})
    void defaultsAndLargeLimitsStillCapEachPageAtOneHundred(String request) throws Exception {
        for (int i = 0; i < 101; i++) {
            create(String.format("stream-%03d", i), REGION);
        }
        ObjectNode first = list(mapper.readTree(request));
        assertEquals(100, names(first).size());
        assertTrue(first.path("HasMoreStreams").asBoolean());
        ObjectNode last = list(Map.of("ExclusiveStartStreamName", "stream-099"));
        assertEquals(List.of("stream-100"), names(last));
        assertFalse(last.path("HasMoreStreams").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10001", "1.5", "\"one\"", "999999999999999999999"})
    void rejectsInvalidLimitsEvenForAnEmptyRegion(String limit) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("{\"Limit\":" + limit + "}");
        AwsException error = assertThrows(AwsException.class, () -> list(request));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\"invalid/name\"", "123"})
    void rejectsInvalidExclusiveStartNames(String name) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("{\"ExclusiveStartStreamName\":" + name + "}");
        AwsException error = assertThrows(AwsException.class, () -> list(request));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private void create(String name, String region) {
        try (Response response = handler.handle("CreateStream",
                mapper.valueToTree(Map.of("StreamName", name, "ShardCount", 1)), region)) {
            assertEquals(200, response.getStatus());
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
