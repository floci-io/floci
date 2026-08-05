package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies deleting a stack whose underlying resource was already removed out-of-band completes
 * (DELETE_COMPLETE) rather than failing. AWS CloudFormation treats deleting an absent resource as an
 * idempotent no-op — this matters because a rolled-back stack has already deleted some of its
 * resources, so tearing that stack down re-issues DeleteTable/DeleteFunction against resources that
 * no longer exist. Without the {@code *Safe} wrappers those "not found" errors flipped the stack to
 * DELETE_FAILED, wedging the stack name against a later create.
 *
 * <p>Exercised end-to-end through the DynamoDB path (metadata-only, so Docker-free). Hermetic unit
 * coverage verifies both the DynamoDB and Lambda wrappers, including propagation of other errors.
 */
@QuarkusTest
class CloudFormationDeleteIdempotencyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";
    private static final String DDB_CT = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void deletingStackWhoseTableWasRemovedOutOfBandStillCompletes() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String tableName = "idem-delete-table-" + suffix;
        String stackName = "idem-delete-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "T": {
                      "Type": "AWS::DynamoDB::Table",
                      "Properties": {
                        "TableName": "%s",
                        "AttributeDefinitions": [{"AttributeName":"pk","AttributeType":"S"}],
                        "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                        "BillingMode": "PAY_PER_REQUEST"
                      }
                    }
                  }
                }
                """.formatted(tableName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);

        // CloudFormationQueryHandler awaits executeChangeSet before CreateStack returns, so this
        // one-shot assertion verifies the handler's synchronous completion contract.
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // Remove the table out-of-band — mimics a rollback having already deleted it.
        given()
            .contentType(DDB_CT)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(200);

        // Deleting the stack must still succeed (was DELETE_FAILED: "Requested resource not found").
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);

        // DeleteStack runs asynchronously: poll until the stack is gone (a successful delete removes it
        // entirely). A failed delete would instead leave it queryable as DELETE_FAILED — fail fast on that.
        awaitStackDeleted(stackName);
    }

    private void awaitStackDeleted(String stackName) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when().post("/").then().extract().asString();

            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed instead of treating the already-removed table as deleted: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + stackName + " was not deleted within the timeout");
    }

    private void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }
}
