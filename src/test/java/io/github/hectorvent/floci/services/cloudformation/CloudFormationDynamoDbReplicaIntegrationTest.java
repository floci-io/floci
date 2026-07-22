package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies global-table v2 replica support: the DynamoDB API path (UpdateTable ReplicaUpdates +
 * DescribeTable Replicas/GlobalTableVersion) and the CloudFormation {@code Custom::DynamoDBReplica}
 * resource the CDK legacy global-table pattern ({@code dynamodb.Table.replicationRegions}) emits.
 *
 * <p>This single-process emulator serves every region from the same table, so a replica is tracked
 * as metadata and reported as an ACTIVE Replica, matching what real DynamoDB returns for a
 * {@code 2019.11.21} global table — with no provider Lambda required. All resources are
 * metadata-only (no container), so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationDynamoDbReplicaIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String DDB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/dynamodb/aws4_request";
    private static final String DDB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String REPLICA_REGION = "us-west-2";
    private static final Duration STACK_DELETE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STACK_DELETE_POLL_INTERVAL = Duration.ofMillis(50);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private String ddb(String target, String body) {
        return given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810." + target)
            .body(body)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private void createTable(String tableName) {
        ddb("CreateTable", "{\"TableName\":\"" + tableName + "\","
                + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"}],"
                + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}],"
                + "\"BillingMode\":\"PAY_PER_REQUEST\"}");
    }

    @Test
    void updateTableReplicaUpdatesAddAndRemoveReplica() {
        String tableName = "gt-api-table-" + Long.toString(System.nanoTime(), 36);
        createTable(tableName);

        // Add a replica via UpdateTable ReplicaUpdates -> Create.
        String added = ddb("UpdateTable", "{\"TableName\":\"" + tableName + "\","
                + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"" + REPLICA_REGION + "\"}}]}");
        org.junit.jupiter.api.Assertions.assertTrue(added.contains("\"RegionName\":\"" + REPLICA_REGION + "\""),
                "UpdateTable response should report the new replica");

        // DescribeTable reports it as an ACTIVE replica on a 2019.11.21 global table.
        String afterAdd = ddb("DescribeTable", "{\"TableName\":\"" + tableName + "\"}");
        org.junit.jupiter.api.Assertions.assertTrue(
                afterAdd.contains("\"RegionName\":\"" + REPLICA_REGION + "\"")
                        && afterAdd.contains("\"ReplicaStatus\":\"ACTIVE\"")
                        && afterAdd.contains("\"GlobalTableVersion\":\"2019.11.21\""),
                "DescribeTable should show the ACTIVE replica + global-table version, was: " + afterAdd);

        // Remove it via UpdateTable ReplicaUpdates -> Delete; DescribeTable no longer lists it.
        ddb("UpdateTable", "{\"TableName\":\"" + tableName + "\","
                + "\"ReplicaUpdates\":[{\"Delete\":{\"RegionName\":\"" + REPLICA_REGION + "\"}}]}");
        String afterRemove = ddb("DescribeTable", "{\"TableName\":\"" + tableName + "\"}");
        org.junit.jupiter.api.Assertions.assertFalse(afterRemove.contains(REPLICA_REGION),
                "DescribeTable should no longer list the removed replica, was: " + afterRemove);
    }

    @Test
    void updateTableRejectsBlankReplicaRegion() {
        String tableName = "gt-invalid-region-" + Long.toString(System.nanoTime(), 36);
        createTable(tableName);

        given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .body("{\"TableName\":\"" + tableName + "\","
                    + "\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"\"}}]}")
        .when().post("/").then().statusCode(400)
            .body("__type", containsString("ValidationException"));
    }

    @Test
    void updateTableRejectsUnsupportedReplicaUpdateBeforeApplyingOtherChanges() {
        String tableName = "gt-update-replica-" + Long.toString(System.nanoTime(), 36);
        createTable(tableName);

        given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.UpdateTable")
            .body("{\"TableName\":\"" + tableName + "\","
                    + "\"DeletionProtectionEnabled\":true,"
                    + "\"ReplicaUpdates\":[{\"Update\":{\"RegionName\":\"us-west-2\"}}]}")
        .when().post("/").then().statusCode(400)
            .body("__type", containsString("ValidationException"))
            .body("message", containsString("ReplicaUpdates.Update is not supported"));

        String table = ddb("DescribeTable", "{\"TableName\":\"" + tableName + "\"}");
        assertTrue(table.contains("\"DeletionProtectionEnabled\":false"),
                "rejected update must not partially mutate the table: " + table);
        assertFalse(table.contains("\"Replicas\""),
                "rejected update must not add replicas: " + table);
    }

    /** Mirrors the CDK legacy global-table synth: a table plus a Custom::DynamoDBReplica for a peer region. */
    private static String tableAndReplicaTemplate(String tableName) {
        return """
                {
                  "Resources": {
                    "Table": {
                      "Type": "AWS::DynamoDB::Table",
                      "Properties": {
                        "TableName": "%s",
                        "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                        "BillingMode": "PAY_PER_REQUEST"
                      }
                    },
                    "Replica": {
                      "Type": "Custom::DynamoDBReplica",
                      "DependsOn": "Table",
                      "Properties": {
                        "ServiceToken": "arn:aws:lambda:us-east-1:000000000000:function:replica-provider",
                        "TableName": {"Ref": "Table"},
                        "Region": "%s"
                      }
                    }
                  }
                }
                """.formatted(tableName, REPLICA_REGION);
    }

    @Test
    void dynamoDbReplicaCustomResourceAddsReplicaVisibleInDescribeTable() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String tableName = "gt-replica-table-" + suffix;
        String stackName = "gt-replica-stack-" + suffix;

        createStack(stackName, tableAndReplicaTemplate(tableName));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // DescribeTable (same JVM => same DynamoDbService store) reports the replica as an ACTIVE
        // Replica and the table as a 2019.11.21 global table. Read from the stack region (us-east-1).
        given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(200)
            .body(containsString("\"RegionName\":\"" + REPLICA_REGION + "\""))
            .body(containsString("\"ReplicaStatus\":\"ACTIVE\""))
            .body(containsString("\"GlobalTableVersion\":\"2019.11.21\""));

        deleteStack(stackName);
    }

    @Test
    void deletingStackDetachesReplicaFromSurvivingTable() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String tableName = "gt-ext-table-" + suffix;
        String stackName = "gt-ext-replica-stack-" + suffix;

        // Table owned OUTSIDE the stack, so it survives DeleteStack — isolates the replica detach
        // (deleteDynamoDbReplicaSafe) from a cascade table delete.
        createTable(tableName);

        String template = """
                {
                  "Resources": {
                    "Replica": {
                      "Type": "Custom::DynamoDBReplica",
                      "Properties": {
                        "ServiceToken": "arn:aws:lambda:us-east-1:000000000000:function:replica-provider",
                        "TableName": "%s",
                        "Region": "%s"
                      }
                    }
                  }
                }
                """.formatted(tableName, REPLICA_REGION);

        createStack(stackName, template);
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // The replica is attached to the pre-existing table.
        given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(200)
            .body(containsString("\"RegionName\":\"" + REPLICA_REGION + "\""));

        // Deleting the stack must detach the replica from the surviving table (async delete).
        deleteStack(stackName);
        awaitStackGone(stackName);

        // The table still exists, but its replica is gone.
        given()
            .contentType(DDB_CONTENT_TYPE)
            .header("Authorization", DDB_AUTH)
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .body("{\"TableName\":\"" + tableName + "\"}")
        .when().post("/").then().statusCode(200)
            .body(containsString("\"TableName\":\"" + tableName + "\""))
            .body(not(containsString(REPLICA_REGION)));
    }

    private void awaitStackGone(String stackName) {
        await()
            .atMost(STACK_DELETE_TIMEOUT)
            .pollInterval(STACK_DELETE_POLL_INTERVAL)
            .untilAsserted(() -> {
                String body = given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStacks")
                    .formParam("StackName", stackName)
                .when().post("/").then().extract().asString();
                if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                    fail("stack delete failed: " + body);
                }
                assertTrue(body.contains("does not exist"), "stack still exists: " + body);
            });
    }
}
