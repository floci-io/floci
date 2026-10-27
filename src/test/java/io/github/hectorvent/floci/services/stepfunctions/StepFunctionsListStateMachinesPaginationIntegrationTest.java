package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StepFunctionsListStateMachinesPaginationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String REGION = "me-central-1";
    private static final String AUTHORIZATION = "AWS4-HMAC-SHA256 Credential=000000000000/20261026/"
            + REGION + "/states/aws4_request, SignedHeaders=host, Signature=abc";
    @Inject StepFunctionsService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void maxResultsCapsThePage() throws Exception {
        List<String> arns = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                arns.add(service.createStateMachine(
                        "issue-page-" + UUID.randomUUID(),
                        "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}",
                        "arn:aws:iam::000000000000:role/test-role", "STANDARD", REGION, null)
                        .getStateMachineArn());
            }
            Response firstPage = list(Map.of("maxResults", 1)).then().statusCode(200)
                    .body("stateMachines", hasSize(1))
                    .body("nextToken", notNullValue()).extract().response();
            String nextToken = firstPage.jsonPath().getString("nextToken");
            List<String> firstArns = firstPage.jsonPath().getList("stateMachines.stateMachineArn");
            Response lastPage = list(Map.of("maxResults", 1, "nextToken", nextToken))
                    .then().statusCode(200).extract().response();
            List<String> lastArns = lastPage.jsonPath().getList("stateMachines.stateMachineArn");
            assertEquals(1, lastArns.size());
            assertNull(lastPage.jsonPath().getString("nextToken"));
            Set<String> listed = new HashSet<>(firstArns);
            assertTrue(listed.addAll(lastArns), "the continuation must not repeat the first state machine");
            assertEquals(new HashSet<>(arns), listed);

            Response foreignRegion = given().contentType(CONTENT_TYPE)
                    .header("X-Amz-Target", "AWSStepFunctions.ListStateMachines")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20261026/"
                            + "us-west-2/states/aws4_request, SignedHeaders=host, Signature=abc")
                    .body(Map.of("maxResults", 1, "nextToken", nextToken))
                    .post("/").then().statusCode(400).extract().response();
            assertEquals("InvalidToken", foreignRegion.jsonPath().getString("__type"));

            Response foreignAccount = given().contentType(CONTENT_TYPE)
                    .header("X-Amz-Target", "AWSStepFunctions.ListStateMachines")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000111/20261026/"
                            + REGION + "/states/aws4_request, SignedHeaders=host, Signature=abc")
                    .body(Map.of("maxResults", 1, "nextToken", nextToken))
                    .post("/").then().statusCode(400).extract().response();
            assertEquals("InvalidToken", foreignAccount.jsonPath().getString("__type"));
        } finally {
            for (String arn : arns) {
                service.deleteStateMachine(arn);
            }
        }
    }

    @Test
    void zeroUsesTheDefaultPageSizeOfOneHundred() throws Exception {
        List<String> arns = new ArrayList<>();
        try {
            for (int index = 0; index < 101; index++) {
                arns.add(service.createStateMachine(
                        "issue-default-page-" + UUID.randomUUID(),
                        "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}",
                        "arn:aws:iam::000000000000:role/test-role", "STANDARD", REGION, null)
                        .getStateMachineArn());
            }
            Response firstPage = list(Map.of("maxResults", 0)).then().statusCode(200).extract().response();
            List<String> firstArns = firstPage.jsonPath().getList("stateMachines.stateMachineArn");
            assertEquals(100, firstArns.size());
            String nextToken = firstPage.jsonPath().getString("nextToken");
            assertFalse(nextToken == null || nextToken.isEmpty());

            Response lastPage = list(Map.of("maxResults", 0, "nextToken", nextToken))
                    .then().statusCode(200).extract().response();
            List<String> lastArns = lastPage.jsonPath().getList("stateMachines.stateMachineArn");
            assertEquals(1, lastArns.size());
            assertNull(lastPage.jsonPath().getString("nextToken"));
            Set<String> listed = new HashSet<>(firstArns);
            assertTrue(listed.addAll(lastArns));
            assertEquals(new HashSet<>(arns), listed);
        } finally {
            for (String arn : arns) {
                service.deleteStateMachine(arn);
            }
        }
    }

    @Test
    void rejectsInvalidPageSizeAndToken() {
        for (Object maxResults : List.of(-1, 1001, "1", 4294967296L)) {
            Response response = list(Map.of("maxResults", maxResults))
                    .then().statusCode(400).extract().response();
            assertEquals("ValidationException", response.jsonPath().getString("__type"));
        }
        for (String nextToken : List.of("", "x".repeat(1025))) {
            Response response = list(Map.of("nextToken", nextToken))
                    .then().statusCode(400).extract().response();
            assertEquals("ValidationException", response.jsonPath().getString("__type"));
        }
        Response response = list(Map.of("nextToken", "not-a-valid-token"))
                .then().statusCode(400).extract().response();
        assertEquals("InvalidToken", response.jsonPath().getString("__type"));
    }

    private static Response list(Map<String, ?> request) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSStepFunctions.ListStateMachines")
                .header("Authorization", AUTHORIZATION)
                .body(request).post("/");
    }
}
