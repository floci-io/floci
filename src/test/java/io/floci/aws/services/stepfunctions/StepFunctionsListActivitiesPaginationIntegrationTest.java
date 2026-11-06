package io.floci.aws.services.stepfunctions;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StepFunctionsListActivitiesPaginationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void maxResultsCapsThePageAndReturnsAContinuationToken() {
        String prefix = "page-" + UUID.randomUUID() + "-";
        List<String> arns = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                Response created = given()
                        .header("X-Amz-Target", "AWSStepFunctions.CreateActivity")
                        .contentType(CONTENT_TYPE)
                        .body(Map.of("name", prefix + index))
                        .post("/")
                        .then().statusCode(200).extract().response();
                arns.add(created.jsonPath().getString("activityArn"));
            }

            Set<String> listedArns = new HashSet<>();
            Set<String> issuedTokens = new HashSet<>();
            String nextToken = null;
            do {
                Response page = given()
                        .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                        .contentType(CONTENT_TYPE)
                        .body(nextToken == null ? Map.of("maxResults", 1)
                                : Map.of("maxResults", 1, "nextToken", nextToken))
                        .post("/")
                        .then().statusCode(200).extract().response();
                List<String> pageArns = page.jsonPath().getList("activities.activityArn");
                assertEquals(1, pageArns.size(), "maxResults=1 must cap every page");
                assertTrue(listedArns.add(pageArns.getFirst()), "pages must not repeat an activity");
                nextToken = page.jsonPath().getString("nextToken");
                if (nextToken != null) {
                    assertTrue(issuedTokens.add(nextToken), "pagination must not repeat a token");
                }
            } while (nextToken != null);
            assertTrue(listedArns.containsAll(arns), "both created activities must be listed");
            assertTrue(!issuedTokens.isEmpty(), "more than one activity needs a continuation token");

            Response defaultPage = given()
                    .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("maxResults", 0))
                    .post("/")
                    .then().statusCode(200).extract().response();
            List<String> defaultArns = defaultPage.jsonPath().getList("activities.activityArn");
            assertEquals(Math.min(100, listedArns.size()), defaultArns.size(),
                    "maxResults=0 uses the default page size");
        } finally {
            for (String arn : arns) {
                given()
                        .header("X-Amz-Target", "AWSStepFunctions.DeleteActivity")
                        .contentType(CONTENT_TYPE)
                        .body(Map.of("activityArn", arn))
                        .post("/")
                        .then().statusCode(200);
            }
        }
    }

    @Test
    void rejectsPageSizeThatCannotFitInInteger() {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                .contentType(CONTENT_TYPE)
                .body(Map.of("maxResults", 4294967296L))
                .post("/")
                .then().statusCode(400).extract().response();
        assertEquals("ValidationException", response.jsonPath().getString("__type"));
    }

    @Test
    void rejectsInvalidPageSizeAndToken() {
        for (Object maxResults : List.of(-1, 1001, "1")) {
            Response response = given()
                    .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("maxResults", maxResults))
                    .post("/")
                    .then().statusCode(400).extract().response();
            assertEquals("ValidationException", response.jsonPath().getString("__type"));
        }

        for (String nextToken : List.of("", "x".repeat(1025))) {
            Response response = given()
                    .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("nextToken", nextToken))
                    .post("/")
                    .then().statusCode(400).extract().response();
            assertEquals("ValidationException", response.jsonPath().getString("__type"));
        }

        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.ListActivities")
                .contentType(CONTENT_TYPE)
                .body(Map.of("nextToken", "not-a-valid-token"))
                .post("/")
                .then().statusCode(400).extract().response();
        assertEquals("InvalidToken", response.jsonPath().getString("__type"));
    }
}
