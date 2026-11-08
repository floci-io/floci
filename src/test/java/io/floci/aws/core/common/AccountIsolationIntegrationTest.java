package io.floci.aws.core.common;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that resources are isolated between accounts.
 * Uses 12-digit numeric access key IDs so they are used directly as account IDs.
 */
@QuarkusTest
class AccountIsolationIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // 12-digit numeric keys → used directly as account IDs
    private static final String AUTH_ACCOUNT_1 =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_2 =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_1_SSM =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_2_SSM =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=abc";

    @Test
    void sqsQueuesAreIsolatedBetweenAccounts() {
        // Account 1 creates a queue
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "account-isolation-queue")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("000000000001"))
            .body(containsString("account-isolation-queue"));

        // Account 2 lists queues — should NOT see account 1's queue
        given()
            .header("Authorization", AUTH_ACCOUNT_2)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
            .formParam("QueueNamePrefix", "account-isolation-queue")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("account-isolation-queue")));

        // Account 1 lists queues — should see its queue
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
            .formParam("QueueNamePrefix", "account-isolation-queue")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("account-isolation-queue"));
    }

    @Test
    void sqsQueuesWithSameNameInDifferentAccountsAreIndependent() {
        // Both accounts create a queue with the same name
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "shared-name-queue")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("000000000001/shared-name-queue"));

        given()
            .header("Authorization", AUTH_ACCOUNT_2)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "shared-name-queue")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("000000000002/shared-name-queue"));

        // Account 1 sends a message to its queue
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:8081/000000000001/shared-name-queue")
            .formParam("MessageBody", "message-for-account-1")
        .when().post("/")
        .then()
            .statusCode(200);

        // Account 2 receives from its own queue — should get nothing (empty queue)
        given()
            .header("Authorization", AUTH_ACCOUNT_2)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", "http://localhost:8081/000000000002/shared-name-queue")
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("message-for-account-1")));
    }

    @Test
    void ssmParametersAreIsolatedBetweenAccounts() {
        // Account 1 puts a parameter
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", AUTH_ACCOUNT_1_SSM)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/account-isolation/key", "Value": "value-for-account-1", "Type": "String"}
                """)
        .when().post("/")
        .then()
            .statusCode(200);

        // Account 2 tries to get the same parameter — should not find it
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", AUTH_ACCOUNT_2_SSM)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/account-isolation/key"}
                """)
        .when().post("/")
        .then()
            .statusCode(400);

        // Account 1 can retrieve its parameter, ARN contains its account ID
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", AUTH_ACCOUNT_1_SSM)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/account-isolation/key"}
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("value-for-account-1"))
            .body("Parameter.ARN", containsString("000000000001"));
    }

    @Test
    void sqsQueueArnContainsCorrectAccountId() {
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "arn-account-check-queue")
        .when().post("/")
        .then().statusCode(200);

        // GetQueueAttributes — QueueArn should embed account 1's ID
        given()
            .header("Authorization", AUTH_ACCOUNT_1)
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:8081/000000000001/arn-account-check-queue")
            .formParam("AttributeName.1", "QueueArn")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("arn:aws:sqs:us-east-1:000000000001:arn-account-check-queue"));
    }
}
