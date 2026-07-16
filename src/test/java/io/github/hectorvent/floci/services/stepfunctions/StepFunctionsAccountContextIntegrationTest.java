package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a Step Functions execution started under a specific account runs its
 * service integrations (and execution-store writes) under that same account — not the
 * configured default — by exercising the optimized {@code dynamodb:putItem} integration
 * against a table that exists only in the execution's account.
 *
 * <p>Without account propagation on the executor's worker thread, the integration would
 * resolve to the default account, fail to find the table, and the execution would FAIL.
 */
@QuarkusTest
class StepFunctionsAccountContextIntegrationTest {

    private static final String SFN_CT = "application/x-amz-json-1.0";
    private static final String DDB_CT = "application/x-amz-json-1.0";
    private static final String ACCOUNT = "000000000111";
    private static final String OTHER_ACCOUNT = "000000000222";
    private static final String TABLE = "sfn-acct-ctx-table";
    private static final String PARALLEL_TABLE = "sfn-acct-ctx-parallel-table";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260101/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    void executionRunsUnderItsOwnAccount() throws Exception {
        // 1. Account ACCOUNT creates a DynamoDB table (exists only in this account).
        given()
                .header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DDB_CT)
                .body("""
                        {
                            "TableName": "%s",
                            "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                            "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                            "BillingMode": "PAY_PER_REQUEST"
                        }
                        """.formatted(TABLE))
                .when().post("/")
                .then().statusCode(200);

        // 2. Account ACCOUNT creates a state machine that writes to that table.
        String definition = """
                {
                    "StartAt": "Put",
                    "States": {
                        "Put": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::dynamodb:putItem",
                            "Parameters": {
                                "TableName": "%s",
                                "Item": { "id": {"S": "k1"}, "v": {"S": "hello"} }
                            },
                            "End": true
                        }
                    }
                }
                """.formatted(TABLE);

        Response create = given()
                .header("Authorization", auth(ACCOUNT, "states"))
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CT)
                .body("""
                        { "name": "acct-ctx-sm-%s", "definition": %s, "roleArn": "arn:aws:iam::%s:role/sfn" }
                        """.formatted(System.currentTimeMillis(), quote(definition), ACCOUNT))
                .when().post("/");
        create.then().statusCode(200);
        String smArn = create.jsonPath().getString("stateMachineArn");
        // The state machine ARN must carry the caller's account.
        assertTrue(smArn.contains(ACCOUNT), "SM ARN should embed the caller account: " + smArn);

        // 3. Account ACCOUNT starts the execution.
        Response start = given()
                .header("Authorization", auth(ACCOUNT, "states"))
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CT)
                .body("""
                        { "stateMachineArn": "%s", "input": "{}" }
                        """.formatted(smArn))
                .when().post("/");
        start.then().statusCode(200);
        String execArn = start.jsonPath().getString("executionArn");

        // 4. The execution must SUCCEED — the integration found ACCOUNT's table.
        String status = waitForTerminal(execArn);
        assertEquals("SUCCEEDED", status, "execution should run under " + ACCOUNT + " and find the table");

        // 5. The item landed in ACCOUNT's table.
        given()
                .header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CT)
                .body("""
                        { "TableName": "%s", "Key": { "id": {"S": "k1"} } }
                        """.formatted(TABLE))
                .when().post("/")
                .then().statusCode(200)
                .body("Item.v.S", org.hamcrest.Matchers.equalTo("hello"));

        // 6. Isolation: a different account has no such table at all.
        given()
                .header("Authorization", auth(OTHER_ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CT)
                .body("""
                        { "TableName": "%s", "Key": { "id": {"S": "k1"} } }
                        """.formatted(TABLE))
                .when().post("/")
                .then().statusCode(400);
    }

    @Test
    void executionRunsParallelBranchesUnderItsOwnAccount() throws Exception {
        // Parallel branches run on the executor's worker threads, where the request scope is NOT
        // inherited from the parent. Without per-branch account propagation, each branch's putItem
        // would resolve to the default account, miss ACCOUNT's table, and the execution would FAIL.

        // 1. Account ACCOUNT creates a table that exists only in this account.
        given()
                .header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DDB_CT)
                .body("""
                        {
                            "TableName": "%s",
                            "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                            "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                            "BillingMode": "PAY_PER_REQUEST"
                        }
                        """.formatted(PARALLEL_TABLE))
                .when().post("/")
                .then().statusCode(200);

        // 2. A state machine whose Parallel state has two branches, each writing to that table.
        String definition = """
                {
                    "StartAt": "Fan",
                    "States": {
                        "Fan": {
                            "Type": "Parallel",
                            "End": true,
                            "Branches": [
                                {
                                    "StartAt": "PutA",
                                    "States": {
                                        "PutA": {
                                            "Type": "Task",
                                            "Resource": "arn:aws:states:::dynamodb:putItem",
                                            "Parameters": { "TableName": "%1$s", "Item": { "id": {"S": "a"}, "v": {"S": "A"} } },
                                            "End": true
                                        }
                                    }
                                },
                                {
                                    "StartAt": "PutB",
                                    "States": {
                                        "PutB": {
                                            "Type": "Task",
                                            "Resource": "arn:aws:states:::dynamodb:putItem",
                                            "Parameters": { "TableName": "%1$s", "Item": { "id": {"S": "b"}, "v": {"S": "B"} } },
                                            "End": true
                                        }
                                    }
                                }
                            ]
                        }
                    }
                }
                """.formatted(PARALLEL_TABLE);

        Response create = given()
                .header("Authorization", auth(ACCOUNT, "states"))
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CT)
                .body("""
                        { "name": "acct-ctx-par-sm-%s", "definition": %s, "roleArn": "arn:aws:iam::%s:role/sfn" }
                        """.formatted(System.currentTimeMillis(), quote(definition), ACCOUNT))
                .when().post("/");
        create.then().statusCode(200);
        String smArn = create.jsonPath().getString("stateMachineArn");
        assertTrue(smArn.contains(ACCOUNT), "SM ARN should embed the caller account: " + smArn);

        // 3. Start the execution under ACCOUNT.
        Response start = given()
                .header("Authorization", auth(ACCOUNT, "states"))
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CT)
                .body("""
                        { "stateMachineArn": "%s", "input": "{}" }
                        """.formatted(smArn))
                .when().post("/");
        start.then().statusCode(200);
        String execArn = start.jsonPath().getString("executionArn");

        // 4. The execution must SUCCEED — both branches found ACCOUNT's table.
        String status = waitForTerminal(execArn);
        assertEquals("SUCCEEDED", status,
                "parallel branches should run under " + ACCOUNT + " and find the table");

        // 5. Both branch writes landed in ACCOUNT's table.
        given()
                .header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CT)
                .body("""
                        { "TableName": "%s", "Key": { "id": {"S": "a"} } }
                        """.formatted(PARALLEL_TABLE))
                .when().post("/")
                .then().statusCode(200)
                .body("Item.v.S", org.hamcrest.Matchers.equalTo("A"));
        given()
                .header("Authorization", auth(ACCOUNT, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CT)
                .body("""
                        { "TableName": "%s", "Key": { "id": {"S": "b"} } }
                        """.formatted(PARALLEL_TABLE))
                .when().post("/")
                .then().statusCode(200)
                .body("Item.v.S", org.hamcrest.Matchers.equalTo("B"));
    }

    private String waitForTerminal(String execArn) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            Response resp = given()
                    .header("Authorization", auth(ACCOUNT, "states"))
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CT)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when().post("/");
            String status = resp.jsonPath().getString("status");
            if (!"RUNNING".equals(status)) {
                if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                    fail("Execution " + status + ": " + resp.body().asString());
                }
                return status;
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
