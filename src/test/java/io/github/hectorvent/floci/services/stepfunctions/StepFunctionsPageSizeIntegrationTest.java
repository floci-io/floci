package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.StepFunctionsRequests.sfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ListActivities and GetExecutionHistory bound maxResults and nextToken the same way, with the
 * same length messages and in the same order, and page by 100 when maxResults is omitted or 0.
 * The history's default page is pinned by StepFunctionsExecutionHistoryPaginationIntegrationTest.
 */
@QuarkusTest
class StepFunctionsPageSizeIntegrationTest {

    // Validation comes before the execution is looked up, so no execution needs to exist.
    private static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:000000000000:execution:page-size:" + UUID.randomUUID();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListActivities", "GetExecutionHistory"})
    void maxResultsOutsideZeroToOneThousandIsRefused(String action) {
        for (int maxResults : List.of(-1, 1001)) {
            assertRefused(action, Map.of("maxResults", maxResults), "maxResults must be between 0 and 1000.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListActivities", "GetExecutionHistory"})
    void nextTokenOutsideOneToOneThousandTwentyFourCharactersIsRefused(String action) {
        for (String nextToken : List.of("", "x".repeat(1025))) {
            assertRefused(action, Map.of("nextToken", nextToken), "nextToken length must be between 1 and 1024.");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListActivities", "GetExecutionHistory"})
    void theBoundsThemselvesAreAccepted(String action) {
        // A token of 1 or 1,024 characters passes the length check and is then refused as unknown.
        for (String nextToken : List.of("x", "x".repeat(1024))) {
            assertEquals("InvalidToken", errorType(action, Map.of("nextToken", nextToken)));
        }
        if ("ListActivities".equals(action)) {
            sfn(action, Map.of("maxResults", 1000)).then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListActivities", "GetExecutionHistory"})
    void bothFieldsAreReadBeforeEitherIsBounded(String action) {
        assertRefused(action, Map.of("maxResults", 1001, "nextToken", 5), "nextToken must be a string.");
    }

    @Test
    void listActivitiesPagesByOneHundredWhenMaxResultsIsOmittedOrZero() {
        List<String> arns = new ArrayList<>();
        try {
            for (int index = 0; index < 101; index++) {
                arns.add(sfn("CreateActivity", Map.of("name", "page-size-" + UUID.randomUUID()))
                        .then().statusCode(200).extract().path("activityArn"));
            }
            for (Map<String, Object> request : List.of(Map.<String, Object>of(), Map.<String, Object>of("maxResults", 0))) {
                Response page = sfn("ListActivities", request).then().statusCode(200).extract().response();
                assertEquals(100, page.jsonPath().getList("activities").size());
                assertNotNull(page.path("nextToken"));
            }
        } finally {
            // Best effort, unasserted, so a failed delete can neither hide the test's own failure
            // nor stop the remaining deletes.
            for (String arn : arns) {
                sfn("DeleteActivity", Map.of("activityArn", arn));
            }
        }
    }

    private static void assertRefused(String action, Map<String, Object> parameters, String message) {
        Response response = refused(action, parameters);
        assertEquals("ValidationException", response.path("__type"));
        assertEquals(message, response.path("message"));
    }

    private static String errorType(String action, Map<String, Object> parameters) {
        return refused(action, parameters).path("__type");
    }

    private static Response refused(String action, Map<String, Object> parameters) {
        Map<String, Object> body = new HashMap<>(parameters);
        if ("GetExecutionHistory".equals(action)) {
            body.put("executionArn", EXECUTION_ARN);
        }
        return sfn(action, body).then().statusCode(400).extract().response();
    }
}
