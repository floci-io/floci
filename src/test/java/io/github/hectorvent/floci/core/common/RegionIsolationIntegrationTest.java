package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test verifying that data is isolated between regions.
 * Uses different Authorization headers to simulate requests from different regions.
 */
@QuarkusTest
class RegionIsolationIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    private static final String AUTH_US_EAST_1 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/ssm/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_US_WEST_2 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-west-2/ssm/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_EU_WEST_1 =
            "AWS4-HMAC-SHA256 Credential=AKID/20260215/eu-west-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void ssmParametersAreIsolatedByRegion() {
        // Put parameter in us-east-1
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", AUTH_US_EAST_1)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/region-test/key", "Value": "east-value", "Type": "String"}
                """)
        .when().post("/")
        .then().statusCode(200);

        // Put same parameter name in us-west-2 with different value
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", AUTH_US_WEST_2)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/region-test/key", "Value": "west-value", "Type": "String"}
                """)
        .when().post("/")
        .then().statusCode(200);

        // Get from us-east-1 — should return east-value
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", AUTH_US_EAST_1)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/region-test/key"}
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("east-value"))
            .body("Parameter.ARN", containsString("us-east-1"));

        // Get from us-west-2 — should return west-value
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", AUTH_US_WEST_2)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/region-test/key"}
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("west-value"))
            .body("Parameter.ARN", containsString("us-west-2"));
    }

    @Test
    void dynamoDbTablesAreIsolatedByRegion() {
        // Create table in us-east-1
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .header("Authorization", AUTH_US_EAST_1)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "RegionTestTable",
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}
                }
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableArn", containsString("us-east-1"));

        // Create same table name in eu-west-1 — should succeed (different region)
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .header("Authorization", AUTH_EU_WEST_1)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {
                    "TableName": "RegionTestTable",
                    "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}
                }
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableArn", containsString("eu-west-1"));

        // List tables in us-east-1 — should see RegionTestTable
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .header("Authorization", AUTH_US_EAST_1)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TableNames", hasItem("RegionTestTable"));

        // List tables in us-west-2 (no tables created there) — should NOT see RegionTestTable
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .header("Authorization", AUTH_US_WEST_2)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TableNames", not(hasItem("RegionTestTable")));
    }

    @Test
    void sqsQueuesAreIsolatedByRegion() {
        String eastOnly = "region-east-" + UUID.randomUUID().toString().substring(0, 8);
        String westOnly = "region-west-" + UUID.randomUUID().toString().substring(0, 8);
        String shared = "region-shared-" + UUID.randomUUID().toString().substring(0, 8);
        String eastOnlyUrl = createQueue("us-east-1", eastOnly);
        String westOnlyUrl = createQueue("us-west-2", westOnly);
        // The same name in both regions: each region gets its own queue.
        String eastSharedUrl = createQueue("us-east-1", shared);
        String westSharedUrl = createQueue("us-west-2", shared);

        try {
            given()
                .header("Authorization", sqsAuth("us-east-1"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListQueues")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString(eastOnly), containsString(shared), not(containsString(westOnly)));
            given()
                .header("Authorization", sqsAuth("us-west-2"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListQueues")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString(westOnly), containsString(shared), not(containsString(eastOnly)));
        } finally {
            deleteQueue("us-east-1", eastOnlyUrl);
            deleteQueue("us-west-2", westOnlyUrl);
            deleteQueue("us-east-1", eastSharedUrl);
            deleteQueue("us-west-2", westSharedUrl);
        }
    }

    private static String sqsAuth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                + "/sqs/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String createQueue(String region, String name) {
        return given()
            .header("Authorization", sqsAuth(region))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", name)
        .when().post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void deleteQueue(String region, String queueUrl) {
        given()
            .header("Authorization", sqsAuth(region))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", queueUrl)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void defaultRegionUsedWhenNoAuthHeader() {
        // Request without Authorization header falls back to default (us-east-1)
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/default-region-test", "Value": "default", "Type": "String"}
                """)
        .when().post("/")
        .then().statusCode(200);

        // Can retrieve with explicit us-east-1 auth
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", AUTH_US_EAST_1)
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/default-region-test"}
                """)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("default"));
    }
}
