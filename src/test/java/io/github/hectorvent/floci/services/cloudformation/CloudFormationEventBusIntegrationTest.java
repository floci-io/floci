package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies CloudFormation provisions {@code AWS::Events::EventBus} as a real custom EventBridge bus.
 *
 * <p>Previously the type had no provisioner case, so it fell through to the generic stub: the resource
 * reported CREATE_COMPLETE with a physical id but the bus was never registered with the EventBridge
 * service. Any {@code AWS::Events::Rule} referencing it via {@code {"Ref": bus}} then failed
 * "EventBus not found", rolling the whole stack back. Per the AWS spec {@code Ref} returns the bus
 * <em>name</em> and {@code Fn::GetAtt "Arn"} the ARN.
 *
 * <p>All resources are metadata-only (no container), so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationEventBusIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EVENTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/events/aws4_request";

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
        .when()
            .post("/")
        .then()
            .statusCode(200);
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

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String eventBusAndRuleTemplate(String busName) {
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": { "Name": "%s" }
                    },
                    "Rule": {
                      "Type": "AWS::Events::Rule",
                      "Properties": {
                        "EventBusName": { "Ref": "Bus" },
                        "EventPattern": { "source": ["com.example.orders"] },
                        "State": "ENABLED"
                      }
                    }
                  },
                  "Outputs": {
                    "BusRef": { "Value": { "Ref": "Bus" } },
                    "BusArn": { "Value": { "Fn::GetAtt": ["Bus", "Arn"] } }
                  }
                }
                """.formatted(busName);
    }

    @Test
    void customEventBusIsRegisteredSoRuleReferencingItSucceeds() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "domain-events-" + suffix;
        String stackName = "eventbus-stack-" + suffix;

        // The stub-only behaviour failed the Rule with "EventBus not found" -> ROLLBACK.
        createStack(stackName, eventBusAndRuleTemplate(busName));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // Ref returns the bus NAME (not the ARN) per the AWS spec.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<OutputKey>BusRef</OutputKey>"))
            .body(containsString("<OutputValue>" + busName + "</OutputValue>"))
            .body(containsString("event-bus/" + busName));

        // The bus is really registered with the EventBridge service (not just a stub).
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(busName));

        // Deleting the stack must remove the bus (and its rule) from EventBridge, not leak it: the
        // rule lives on the custom bus, so its cleanup must target that bus or the bus delete fails.
        deleteStack(stackName);
        awaitStackDeleted(stackName);
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ResourceNotFoundException"));
    }

    private void awaitStackDeleted(String stackName) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")
                    || body.contains("<StackStatus>DELETE_COMPLETE</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    void eventBusWithExistingNameIsAdoptedNotRecreated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "adopt-bus-" + suffix;
        String stackA = "eventbus-adopt-a-" + suffix;
        String stackB = "eventbus-adopt-b-" + suffix;

        createStack(stackA, eventBusAndRuleTemplate(busName));
        assertStackStatus(stackA, "CREATE_COMPLETE");

        // Re-declaring an already-existing bus name — as an idempotent re-deploy / stack UPDATE does —
        // must ADOPT the existing bus rather than fail with ResourceAlreadyExistsException.
        createStack(stackB, eventBusAndRuleTemplate(busName));
        assertStackStatus(stackB, "CREATE_COMPLETE");

        deleteStack(stackA);
        deleteStack(stackB);
    }

    @Test
    void failedStackCreateRollsBackRuleAndCustomEventBus() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "rollback-bus-" + suffix;
        String stackName = "eventbus-rollback-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": { "Name": "%s" }
                    },
                    "Rule": {
                      "Type": "AWS::Events::Rule",
                      "DependsOn": "Bus",
                      "Properties": {
                        "EventBusName": { "Ref": "Bus" },
                        "EventPattern": { "source": ["com.example.rollback"] },
                        "State": "ENABLED"
                      }
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "DependsOn": "Rule",
                      "Properties": {
                        "Name": "rollback-secret-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": { "PasswordLength": 32 }
                      }
                    }
                  }
                }
                """.formatted(busName, suffix);

        createStack(stackName, template);
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");

        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("ResourceNotFoundException"));
    }
}
