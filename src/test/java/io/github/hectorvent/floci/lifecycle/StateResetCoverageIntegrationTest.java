package io.github.hectorvent.floci.lifecycle;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code /_floci/state/reset} clears every {@link io.github.hectorvent.floci.core.common.Resettable}
 * plus every {@code StorageBackend}. A service that keeps its state in a plain map and does not
 * implement {@code Resettable} is therefore missed, and the reset silently leaves data behind —
 * which is worse than not resetting, because surviving resources reference cleared ones.
 *
 * <p>{@link EmulatorInfoControllerIntegrationTest} already covers a purely storage-backed service
 * (SSM). These cases cover services whose live working copy is an in-memory map.
 */
@QuarkusTest
class StateResetCoverageIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    @BeforeAll
    static void registerAwsJsonParsers() {
        RestAssured.registerParser(JSON_1_0, Parser.JSON);
        RestAssured.registerParser(JSON_1_1, Parser.JSON);
    }

    private void reset() {
        given().when().post("/_floci/state/reset")
                .then().statusCode(200).body("status", equalTo("OK"));
    }

    /** RestAssured has no built-in serializer for the x-amz-json content types; send raw text. */
    private static RequestSpecification rpc(String contentType, String target, String body) {
        return given()
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(JSON_1_0, ContentType.TEXT)
                        .encodeContentTypeAs(JSON_1_1, ContentType.TEXT)))
                .header("X-Amz-Target", target)
                .contentType(contentType)
                .body(body);
    }

    private static final String CREATE_TABLE = """
            {"TableName":"reset-items",
             "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
             "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
             "BillingMode":"PAY_PER_REQUEST"}
            """;

    @Test
    void reset_clearsDynamoDbItems() {
        rpc(JSON_1_0, "DynamoDB_20120810.CreateTable", CREATE_TABLE)
                .when().post("/").then().statusCode(200);

        rpc(JSON_1_0, "DynamoDB_20120810.PutItem", """
                {"TableName":"reset-items","Item":{"pk":{"S":"survivor"}}}
                """).when().post("/").then().statusCode(200);

        reset();

        // The table itself is storage-backed and does go away, so re-create it and confirm the
        // item did not come back with it. Items live in a plain map, which is the actual subject.
        rpc(JSON_1_0, "DynamoDB_20120810.CreateTable", CREATE_TABLE)
                .when().post("/").then().statusCode(200);

        rpc(JSON_1_0, "DynamoDB_20120810.Scan", """
                {"TableName":"reset-items"}
                """).when().post("/")
                .then().statusCode(200)
                .body("Count", equalTo(0));
    }

    @Test
    void reset_clearsCloudFormationStacks() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("Version", "2010-05-15")
                .formParam("StackName", "reset-stack")
                .formParam("TemplateBody", """
                        {"Resources":{"Q":{"Type":"AWS::SQS::Queue",
                          "Properties":{"QueueName":"reset-stack-queue"}}}}
                        """)
                .when().post("/").then().statusCode(200);

        reset();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("Version", "2010-05-15")
                .when().post("/")
                .then().statusCode(200)
                // An empty <Stacks/> deserializes to "" rather than a collection, so assert on the
                // stack itself being absent instead of on the container's emptiness.
                .body(not(containsString("reset-stack")));
    }

    @Test
    void reset_clearsEcsTasks() {
        String ecs = "AmazonEC2ContainerServiceV20141113.";

        rpc(JSON_1_1, ecs + "CreateCluster", """
                {"clusterName":"reset-cluster"}
                """).when().post("/").then().statusCode(200);

        rpc(JSON_1_1, ecs + "RegisterTaskDefinition", """
                {"family":"reset-task",
                 "containerDefinitions":[{"name":"c","image":"busybox","memory":64}]}
                """).when().post("/").then().statusCode(200);

        rpc(JSON_1_1, ecs + "RunTask", """
                {"cluster":"reset-cluster","taskDefinition":"reset-task"}
                """).when().post("/").then().statusCode(200);

        reset();

        // The cluster is storage-backed and does go away; re-create it so ListTasks resolves,
        // then confirm the task did not survive alongside it in EcsService's plain maps.
        rpc(JSON_1_1, ecs + "CreateCluster", """
                {"clusterName":"reset-cluster"}
                """).when().post("/").then().statusCode(200);

        rpc(JSON_1_1, ecs + "ListTasks", """
                {"cluster":"reset-cluster"}
                """).when().post("/")
                .then().statusCode(200)
                .body("taskArns", hasSize(0));
    }
}
