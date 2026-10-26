package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static io.github.hectorvent.floci.testing.StepFunctionsRequests.sfn;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class StepFunctionsExecutionHistoryPaginationIntegrationTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Sixty Pass states: ExecutionStarted, an Entered and an Exited event per state, and
    // ExecutionSucceeded, so the history is longer than one default page of 100.
    private static final int PASS_STATES = 60;
    private static final int EVENTS = 2 * PASS_STATES + 2;

    private static String executionArn;
    private static String otherExecutionArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // RestAssured is pointed at the test server per test, so the executions are started from the
    // first test that runs rather than from @BeforeAll.
    @BeforeEach
    void runTwoLongExecutionsOnce() {
        if (executionArn != null) {
            return;
        }
        ObjectNode definition = MAPPER.createObjectNode().put("StartAt", step(0));
        ObjectNode states = definition.putObject("States");
        for (int index = 0; index < PASS_STATES; index++) {
            ObjectNode state = states.putObject(step(index)).put("Type", "Pass");
            if (index + 1 < PASS_STATES) {
                state.put("Next", step(index + 1));
            } else {
                state.put("End", true);
            }
        }
        String stateMachineArn = sfn("CreateStateMachine", Map.of(
                "name", "history-pages-" + UUID.randomUUID(),
                "definition", definition.toString(),
                "roleArn", ROLE_ARN))
                .then().statusCode(200).extract().path("stateMachineArn");
        executionArn = runToCompletion(stateMachineArn);
        otherExecutionArn = runToCompletion(stateMachineArn);
    }

    @Test
    void defaultPagesCoverTheWholeHistoryOnceInOrder() {
        List<Long> ids = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String nextToken = null;
        do {
            Response page = history(nextToken == null ? Map.of() : Map.of("nextToken", nextToken));
            List<Long> pageIds = ids(page);
            pageSizes.add(pageIds.size());
            ids.addAll(pageIds);
            nextToken = page.path("nextToken");
        } while (nextToken != null);

        assertEquals(List.of(100, EVENTS - 100), pageSizes, "an omitted maxResults pages by 100");
        assertEquals(LongStream.rangeClosed(1, EVENTS).boxed().toList(), ids);
    }

    @Test
    void reverseOrderPagesFromTheNewestEvent() {
        List<Long> ids = new ArrayList<>();
        String nextToken = null;
        do {
            Map<String, Object> request = new HashMap<>(Map.of("reverseOrder", true, "maxResults", 50));
            if (nextToken != null) {
                request.put("nextToken", nextToken);
            }
            Response page = history(request);
            if (ids.isEmpty()) {
                assertEquals("ExecutionSucceeded", page.path("events[0].type"));
            }
            ids.addAll(ids(page));
            nextToken = page.path("nextToken");
        } while (nextToken != null);

        assertEquals(LongStream.rangeClosed(1, EVENTS).map(id -> EVENTS + 1 - id).boxed().toList(), ids);
    }

    @Test
    void maxResultsOfZeroUsesTheDefaultAndOneThousandIsTheMaximum() {
        assertEquals(100, ids(history(Map.of("maxResults", 0))).size());
        Response everything = history(Map.of("maxResults", 1000));
        assertEquals(EVENTS, ids(everything).size());
        assertNull(everything.path("nextToken"));
        for (Object maxResults : List.of(-1, 1001, 4294967296L, "1")) {
            assertEquals("ValidationException", error(Map.of("maxResults", maxResults)));
        }
    }

    @Test
    void aTokenKeepsItsPlaceWhenThePageSizeOrExecutionDataChanges() {
        String nextToken = history(Map.of("maxResults", 1)).path("nextToken");
        assertNotNull(nextToken, "a page shorter than the history carries a token");

        Response next = history(Map.of("nextToken", nextToken, "maxResults", 3, "includeExecutionData", false));
        assertEquals(List.of(2L, 3L, 4L), ids(next));
        assertEquals("PassStateEntered", next.path("events[0].type"));
        assertFalse(next.jsonPath().getMap("events[0].stateEnteredEventDetails").containsKey("input"));
        assertEquals(step(0), next.path("events[0].stateEnteredEventDetails.name"));
    }

    @Test
    void aTokenIsBoundToItsExecutionAndDirection() {
        String nextToken = history(Map.of("maxResults", 1)).path("nextToken");
        assertNotNull(nextToken, "a page shorter than the history carries a token");

        assertEquals("InvalidToken", error(Map.of("nextToken", nextToken, "reverseOrder", true)));
        assertEquals("InvalidToken", sfn("GetExecutionHistory",
                Map.of("executionArn", otherExecutionArn, "nextToken", nextToken))
                .then().statusCode(400).extract().path("__type"));
    }

    @Test
    void malformedTokensAreRejected() {
        assertEquals("ValidationException", error(Map.of("nextToken", "")));
        assertEquals("ValidationException", error(Map.of("nextToken", "x".repeat(1025))));
        assertEquals("InvalidToken", error(Map.of("nextToken", "not-a-valid-token")));
        String nextToken = history(Map.of("maxResults", 1)).path("nextToken");
        // A middle character: the last one may carry only padding bits, which decoding ignores.
        int middle = nextToken.length() / 2;
        String edited = nextToken.substring(0, middle) + (nextToken.charAt(middle) == 'A' ? 'B' : 'A')
                + nextToken.substring(middle + 1);
        assertEquals("InvalidToken", error(Map.of("nextToken", edited)));
    }

    private static String runToCompletion(String stateMachineArn) {
        String arn = sfn("StartExecution", Map.of(
                "stateMachineArn", stateMachineArn, "input", "{\"payload\":\"visible\"}"))
                .then().statusCode(200).extract().path("executionArn");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() ->
                "SUCCEEDED".equals(sfn("DescribeExecution", Map.of("executionArn", arn))
                        .then().statusCode(200).extract().path("status")));
        return arn;
    }

    private static String step(int index) {
        return "Step%02d".formatted(index);
    }

    private static Response history(Map<String, Object> parameters) {
        Map<String, Object> request = new HashMap<>(parameters);
        request.put("executionArn", executionArn);
        return sfn("GetExecutionHistory", request).then().statusCode(200).extract().response();
    }

    private static String error(Map<String, Object> parameters) {
        Map<String, Object> request = new HashMap<>(parameters);
        request.put("executionArn", executionArn);
        return sfn("GetExecutionHistory", request).then().statusCode(400).extract().path("__type");
    }

    private static List<Long> ids(Response page) {
        return page.jsonPath().getList("events.id", Long.class);
    }
}
