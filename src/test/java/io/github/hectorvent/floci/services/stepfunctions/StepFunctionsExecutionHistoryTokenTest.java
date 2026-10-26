package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsExecutionHistoryTokenTest {

    private static final String ARN = "arn:aws:states:us-east-1:000000000000:execution:tokens:run";
    private static final long ISSUED = 1_790_000_000_000L;
    private static final long DAY = Duration.ofHours(24).toMillis();

    private final ObjectMapper mapper = new ObjectMapper();
    private final StepFunctionsService service = mock(StepFunctionsService.class);
    private final Clock clock = mock(Clock.class);
    private StepFunctionsJsonHandler handler;

    @BeforeEach
    void setUp() {
        when(service.getExecutionHistory(ARN)).thenReturn(events(5));
        when(clock.millis()).thenReturn(ISSUED);
        handler = new StepFunctionsJsonHandler(service, mapper, clock);
    }

    @Test
    void aTokenExpiresTwentyFourHoursAfterItWasIssued() {
        String token = firstPageToken();

        when(clock.millis()).thenReturn(ISSUED + DAY);
        assertEquals(2, page(token).path("events").get(0).path("id").asLong());
        when(clock.millis()).thenReturn(ISSUED + DAY + 1);
        assertInvalid(handler, token);
    }

    @Test
    void eachTokenCountsItsLifetimeFromItsOwnPage() {
        String first = firstPageToken();
        when(clock.millis()).thenReturn(ISSUED + Duration.ofHours(20).toMillis());
        String second = page(first).path("nextToken").asText();

        when(clock.millis()).thenReturn(ISSUED + Duration.ofHours(30).toMillis());
        assertInvalid(handler, first);
        assertEquals(3, page(second).path("events").get(0).path("id").asLong());
    }

    @Test
    void aTokenIssuedBeforeARestartIsRefused() {
        String token = firstPageToken();

        assertInvalid(new StepFunctionsJsonHandler(service, mapper, clock), token);
    }

    @Test
    void aTokenWhoseIssueTimeWasMovedForwardIsRefused() {
        String token = firstPageToken();
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String refreshed = Base64Url.encode(decoded.replaceFirst("^" + ISSUED, String.valueOf(ISSUED + DAY)));

        when(clock.millis()).thenReturn(ISSUED + DAY + 1);
        assertInvalid(handler, refreshed);
    }

    @Test
    void pagesResumeAfterTheirLastEventWhileTheExecutionRecordsMore() {
        List<HistoryEvent> recording = new ArrayList<>(events(5));
        when(service.getExecutionHistory(ARN)).thenReturn(recording);
        String forward = history(handler, request().put("maxResults", 2)).path("nextToken").asText();
        String reverse = history(handler, request().put("maxResults", 2).put("reverseOrder", true))
                .path("nextToken").asText();
        recording.addAll(events(7).subList(5, 7));

        JsonNode forwardRest = history(handler, request().put("maxResults", 10).put("nextToken", forward));
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), ids(forwardRest));
        assertFalse(forwardRest.has("nextToken"));
        JsonNode reverseRest = history(handler, request().put("maxResults", 10).put("reverseOrder", true)
                .put("nextToken", reverse));
        assertEquals(List.of(3L, 2L, 1L), ids(reverseRest));
        assertFalse(reverseRest.has("nextToken"));
    }

    @Test
    void aTokenStaysShortForTheLongestNames() {
        // A state machine or an execution name may be 80 characters long, and each of these takes
        // three bytes in UTF-8.
        String arn = "arn:aws:states:us-east-1:000000000000:execution:" + "\u3042".repeat(80) + ":"
                + "\u3044".repeat(80);
        when(service.getExecutionHistory(arn)).thenReturn(events(5));
        ObjectNode request = mapper.createObjectNode().put("executionArn", arn).put("maxResults", 1);
        String token = history(handler, request).path("nextToken").asText();

        assertTrue(token.length() < 256, "token of " + token.length() + " characters");
        assertEquals(List.of(2L), ids(history(handler, request.put("nextToken", token))));
    }

    private static List<HistoryEvent> events(int size) {
        List<HistoryEvent> events = new ArrayList<>();
        for (long id = 1; id <= size; id++) {
            HistoryEvent event = new HistoryEvent();
            event.setId(id);
            event.setType("PassStateEntered");
            events.add(event);
        }
        return events;
    }

    private String firstPageToken() {
        return history(handler, request().put("maxResults", 1)).path("nextToken").asText();
    }

    private JsonNode page(String token) {
        return history(handler, request().put("maxResults", 1).put("nextToken", token));
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("executionArn", ARN);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("events").forEach(event -> ids.add(event.path("id").asLong()));
        return ids;
    }

    private static JsonNode history(StepFunctionsJsonHandler target, ObjectNode request) {
        return (JsonNode) target.handle("GetExecutionHistory", request, "us-east-1").getEntity();
    }

    private void assertInvalid(StepFunctionsJsonHandler target, String token) {
        AwsException error = assertThrows(AwsException.class,
                () -> history(target, request().put("nextToken", token)));
        assertEquals("InvalidToken", error.getErrorCode());
    }
}
