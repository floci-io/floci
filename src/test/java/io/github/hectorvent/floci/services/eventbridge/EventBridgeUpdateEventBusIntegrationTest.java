package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EventBridgeUpdateEventBusIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String UPDATE = "AWSEvents.UpdateEventBus";
    private static final String DESCRIBE = "AWSEvents.DescribeEventBus";
    private static final String CREATE = "AWSEvents.CreateEventBus";
    private static final String LIST = "AWSEvents.ListEventBuses";
    private static final String PUT_PERM = "AWSEvents.PutPermission";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void createBus(String name) {
        given().contentType(CT).header("X-Amz-Target", CREATE)
                .body("{\"Name\":\"" + name + "\"}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    void updateDescriptionOnCustomBus() {
        String bus = "update-test-bus-1";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\",\"Description\":\"hello world\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo(bus))
                .body("Description", equalTo("hello world"))
                .body("Arn", notNullValue());

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("hello world"));
    }

    @Test
    void updateDefaultBus_nameOmitted() {
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Description\":\"default updated 1\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo("default"))
                .body("Description", equalTo("default updated 1"));

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"default\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("default updated 1"));
    }

    @Test
    void updateDefaultBus_nameExplicit() {
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"default\",\"Description\":\"default updated 2\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo("default"))
                .body("Description", equalTo("default updated 2"));
    }

    @Test
    void updateDefaultBus_autoMaterializes() {
        // Send UpdateEventBus against default in a region/account state we haven't
        // touched in this test. Should auto-materialize via getOrCreateDefaultBus.
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"default\",\"Description\":\"auto-materialized\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Name", equalTo("default"));
    }

    @Test
    void updateNonExistentCustomBus_returns404() {
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"this-bus-was-never-created\",\"Description\":\"x\"}")
                .when().post("/")
                .then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void updateKmsKeyIdentifier() {
        String bus = "update-test-bus-kms";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"KmsKeyIdentifier\":\"arn:aws:kms:us-east-1:000000000000:key/test\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KmsKeyIdentifier", equalTo("arn:aws:kms:us-east-1:000000000000:key/test"));

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("KmsKeyIdentifier", equalTo("arn:aws:kms:us-east-1:000000000000:key/test"));
    }

    @Test
    void updateDeadLetterConfig_roundTripsAsObject() {
        // Catches "emitted as string" bug: DeadLetterConfig must be a JSON object,
        // not a stringified one. body("DeadLetterConfig.Arn", equalTo(...)) only
        // resolves if the response field is a real object.
        String bus = "update-test-bus-dlq";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"DeadLetterConfig\":{\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:dlq\"}}")
                .when().post("/")
                .then().statusCode(200)
                .body("DeadLetterConfig.Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:dlq"));

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("DeadLetterConfig.Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:dlq"));
    }

    @Test
    void updateLogConfig_roundTripsAsObject() {
        String bus = "update-test-bus-log";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"LogConfig\":{\"IncludeDetail\":\"FULL\",\"Level\":\"INFO\"}}")
                .when().post("/")
                .then().statusCode(200)
                .body("LogConfig.IncludeDetail", equalTo("FULL"))
                .body("LogConfig.Level", equalTo("INFO"));

        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("LogConfig.IncludeDetail", equalTo("FULL"))
                .body("LogConfig.Level", equalTo("INFO"));
    }

    @Test
    void partialUpdateDoesNotClearOtherFields() {
        String bus = "update-test-bus-partial";
        createBus(bus);

        // First update: set Description
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\",\"Description\":\"original desc\"}")
                .when().post("/")
                .then().statusCode(200);

        // Second update: only KmsKeyIdentifier — must not clear Description
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"KmsKeyIdentifier\":\"arn:aws:kms:us-east-1:000000000000:key/test\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("original desc"))
                .body("KmsKeyIdentifier", equalTo("arn:aws:kms:us-east-1:000000000000:key/test"));
    }

    @Test
    void allFieldsAbsent_isNoOp() {
        String bus = "update-test-bus-noop";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\",\"Description\":\"keep me\"}")
                .when().post("/")
                .then().statusCode(200);

        // Update with only Name → returns 200, state unchanged
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("keep me"));
    }

    @Test
    void policyUntouchedByUpdate() {
        String bus = "update-test-bus-policy";
        createBus(bus);

        // Add a policy via PutPermission
        given().contentType(CT).header("X-Amz-Target", PUT_PERM)
                .body("{\"EventBusName\":\"" + bus + "\","
                        + "\"Action\":\"events:PutEvents\","
                        + "\"Principal\":\"222222222222\","
                        + "\"StatementId\":\"AllowOtherAccount\"}")
                .when().post("/")
                .then().statusCode(200);

        // Now UpdateEventBus with only Description
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\",\"Description\":\"policy preserved\"}")
                .when().post("/")
                .then().statusCode(200);

        // Confirm policy is still present
        given().contentType(CT).header("X-Amz-Target", DESCRIBE)
                .body("{\"Name\":\"" + bus + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("policy preserved"))
                .body("Policy", containsString("AllowOtherAccount"));
    }

    @Test
    void listEventBusesReflectsUpdates() {
        String bus = "update-test-bus-list";
        createBus(bus);

        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"Description\":\"listed\","
                        + "\"KmsKeyIdentifier\":\"arn:aws:kms:us-east-1:000000000000:key/list-test\"}")
                .when().post("/")
                .then().statusCode(200);

        given().contentType(CT).header("X-Amz-Target", LIST)
                .body("{\"NamePrefix\":\"update-test-bus-list\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventBuses.find { it.Name == '" + bus + "' }.Description",
                        equalTo("listed"))
                .body("EventBuses.find { it.Name == '" + bus + "' }.KmsKeyIdentifier",
                        equalTo("arn:aws:kms:us-east-1:000000000000:key/list-test"));
    }

    @Test
    void emptyNestedObjectIsNoOp() {
        String bus = "update-test-bus-empty-obj";
        createBus(bus);

        // Set a DLQ config first
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"DeadLetterConfig\":{\"Arn\":\"arn:aws:sqs:us-east-1:000000000000:original-dlq\"}}")
                .when().post("/")
                .then().statusCode(200);

        // Send empty {} for DeadLetterConfig and LogConfig — should be no-op
        given().contentType(CT).header("X-Amz-Target", UPDATE)
                .body("{\"Name\":\"" + bus + "\","
                        + "\"DeadLetterConfig\":{},"
                        + "\"LogConfig\":{}}")
                .when().post("/")
                .then().statusCode(200)
                .body("DeadLetterConfig.Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:original-dlq"))
                .body("LogConfig", nullValue());
    }
}
