package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private String describeStackResources(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private String describeStackEvents(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private String getTemplate(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    private static String eventBusOnlyTemplate(String busName, String description) {
        String nameProperty = busName == null ? "" : "\"Name\": \"" + busName + "\",";
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        %s
                        "Description": "%s"
                      }
                    }
                  }
                }
                """.formatted(nameProperty, description);
    }

    private static String eventBusWithTagsTemplate(String busName, String tags) {
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        "Name": "%s",
                        "Description": "tagged bus",
                        "Tags": %s
                      }
                    }
                  }
                }
                """.formatted(busName, tags);
    }

    private static String eventBusWithPolicyTemplate(String busName, String statementId) {
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        "Name": "%s",
                        "Policy": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Sid": "%s",
                            "Effect": "Allow",
                            "Principal": "*",
                            "Action": "events:PutEvents",
                            "Resource": "*"
                          }]
                        }
                      }
                    }
                  }
                }
                """.formatted(busName, statementId);
    }

    private void callEventBridge(String target, String body, int expectedStatus) {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", target)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(expectedStatus);
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

    private static String eventBusAndNamedRuleTemplate(String busName, String description) {
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        "Name": "%s",
                        "Description": "%s"
                      }
                    },
                    "Rule": {
                      "Type": "AWS::Events::Rule",
                      "Properties": {
                        "Name": "%s-rule",
                        "EventBusName": { "Ref": "Bus" },
                        "EventPattern": { "source": ["com.example.orders"] },
                        "State": "ENABLED"
                      }
                    }
                  }
                }
                """.formatted(busName, description, busName);
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
            .statusCode(400)
            .body(containsString("ResourceNotFoundException"));
    }

    private void awaitStackDeleted(String stackName) throws InterruptedException {
        String lastBody = null;
        for (int i = 0; i < 100; i++) {
            lastBody = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when().post("/").then().extract().asString();
            if (lastBody.contains("does not exist")
                    || lastBody.contains("<StackStatus>DELETE_COMPLETE</StackStatus>")) {
                return;
            }
            if (lastBody.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                throw new AssertionError("Stack " + stackName + " deletion failed: " + lastBody);
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
                "Timed out waiting for stack " + stackName + " to be deleted. Last response: " + lastBody);
    }

    private void awaitStackStatus(String stackName, String expectedStatus) throws InterruptedException {
        String lastBody = null;
        for (int i = 0; i < 100; i++) {
            lastBody = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when().post("/").then().extract().asString();
            if (lastBody.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for stack " + stackName + " to reach "
                + expectedStatus + ". Last response: " + lastBody);
    }

    @Test
    void eventBusWithExistingNameInAnotherStackFailsWithoutAdoption() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "collision-bus-" + suffix;
        String stackA = "eventbus-collision-a-" + suffix;
        String stackB = "eventbus-collision-b-" + suffix;

        createStack(stackA, eventBusAndRuleTemplate(busName));
        assertStackStatus(stackA, "CREATE_COMPLETE");

        // A second stack must not adopt a bus owned by the first stack.
        createStack(stackB, eventBusAndRuleTemplate(busName));
        assertStackStatus(stackB, "ROLLBACK_COMPLETE");
        String resources = describeStackResources(stackB);
        if (!resources.contains("<ResourceStatus>CREATE_FAILED</ResourceStatus>")) {
            throw new AssertionError("collision failure was not preserved: " + resources);
        }
        String events = describeStackEvents(stackB);
        if (!events.contains("EventBus already exists: " + busName)) {
            throw new AssertionError("collision reason was not preserved: " + events);
        }

        deleteStack(stackB);
        awaitStackDeleted(stackB);
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + busName + "\"}", 200);

        deleteStack(stackA);
        awaitStackDeleted(stackA);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ResourceNotFoundException"));
    }

    @Test
    void sameStackNoOpUpdateReusesOwnedBusAndMutableDescriptionIsRejected()
            throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "update-bus-" + suffix;
        String stackName = "eventbus-update-" + suffix;
        String originalTemplate = eventBusAndNamedRuleTemplate(busName, "before");

        createStack(stackName, originalTemplate);
        assertStackStatus(stackName, "CREATE_COMPLETE");

        updateStack(stackName, originalTemplate);
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        updateStack(stackName, eventBusAndNamedRuleTemplate(busName, "after"));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("\"Description\":\"before\""));

        String activeTemplate = getTemplate(stackName);
        if (!activeTemplate.contains("before") || activeTemplate.contains("after")) {
            throw new AssertionError("failed update replaced the active template: " + activeTemplate);
        }

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
            .statusCode(400)
            .body(containsString("ResourceNotFoundException"));
    }

    @Test
    void explicitBusNameChangeFailsWithoutReplacingOwnedBus() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String originalName = "original-bus-" + suffix;
        String replacementName = "replacement-bus-" + suffix;
        String stackName = "eventbus-rename-" + suffix;

        createStack(stackName, eventBusOnlyTemplate(originalName, "original"));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        updateStack(stackName, eventBusOnlyTemplate(replacementName, "replacement"));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + originalName + "\"}", 200);
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + replacementName + "\"}", 400);
        String activeTemplate = getTemplate(stackName);
        if (!activeTemplate.contains(originalName) || activeTemplate.contains(replacementName)) {
            throw new AssertionError("failed update replaced the active template: " + activeTemplate);
        }

        updateStack(stackName, eventBusOnlyTemplate(null, "missing"));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + originalName + "\"}", 200);

        deleteStack(stackName);
        awaitStackDeleted(stackName);
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + originalName + "\"}", 400);
    }

    @Test
    void eventBusNameIsRequired() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "eventbus-missing-name-" + suffix;

        createStack(stackName, eventBusOnlyTemplate(null, "missing"));
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
        String events = describeStackEvents(stackName);
        if (!events.contains("Name is required for AWS::Events::EventBus")) {
            throw new AssertionError("missing-name failure was not preserved: " + events);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "contains/slash", "contains space", "contains*star"})
    void invalidCustomEventBusNamesFailValidation(String busName) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "eventbus-invalid-name-" + suffix;

        createStack(stackName, eventBusOnlyTemplate(busName, "invalid"));
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
        String events = describeStackEvents(stackName);
        if (!events.contains("Invalid custom event bus Name")) {
            throw new AssertionError("invalid-name failure was not preserved: " + events);
        }
    }

    @Test
    void eventBusNameAndDescriptionLengthLimitsAreValidated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String longNameStack = "eventbus-long-name-" + suffix;
        createStack(longNameStack, eventBusOnlyTemplate("n".repeat(257), "invalid"));
        assertStackStatus(longNameStack, "ROLLBACK_COMPLETE");
        if (!describeStackEvents(longNameStack).contains("Invalid custom event bus Name")) {
            throw new AssertionError("long-name validation failure was not preserved");
        }

        String longDescriptionStack = "eventbus-long-description-" + suffix;
        createStack(longDescriptionStack,
                eventBusOnlyTemplate("valid-bus-" + suffix, "d".repeat(513)));
        assertStackStatus(longDescriptionStack, "ROLLBACK_COMPLETE");
        if (!describeStackEvents(longDescriptionStack).contains(
                "Description must not exceed 512 characters")) {
            throw new AssertionError("long-description validation failure was not preserved");
        }
    }

    // "Policy" is deliberately absent: an inline resource policy is supported (see
    // provisionEventBridgeEventBus), so rejecting it would regress templates main supports today.
    @ParameterizedTest
    @ValueSource(strings = {
            "EventSourceName", "KmsKeyIdentifier", "DeadLetterConfig", "LogConfig"
    })
    void unsupportedEventBusPropertiesFailInsteadOfBeingIgnored(String propertyName) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "eventbus-unsupported-property-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        "Name": "unsupported-property-%s",
                        "%s": "unsupported"
                      }
                    }
                  }
                }
                """.formatted(suffix, propertyName);

        createStack(stackName, template);
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
        String events = describeStackEvents(stackName);
        if (!events.contains("Unsupported AWS::Events::EventBus properties: " + propertyName)) {
            throw new AssertionError("unsupported-property failure was not preserved: " + events);
        }
    }

    @Test
    void mutableEventBusTagUpdateRollsBackWithoutChangingTags() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "tagged-bus-" + suffix;
        String stackName = "eventbus-tags-" + suffix;

        createStack(stackName, eventBusWithTagsTemplate(busName, """
                [{"Key":"keep","Value":"before"},{"Key":"remove","Value":"old"}]
                """));
        assertStackStatus(stackName, "CREATE_COMPLETE");

        String busArn = "arn:aws:events:us-east-1:000000000000:event-bus/" + busName;
        callEventBridge("AWSEvents.TagResource", """
                {"ResourceARN":"%s","Tags":[{"Key":"external","Value":"preserve"}]}
                """.formatted(busArn), 200);

        updateStack(stackName, eventBusWithTagsTemplate(busName, """
                [{"Key":"keep","Value":"after"},{"Key":"add","Value":"new"}]
                """));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        String tags = given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.ListTagsForResource")
            .body("{\"ResourceARN\":\"" + busArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        if (!tags.contains("\"Key\":\"keep\",\"Value\":\"before\"")
                || !tags.contains("\"Key\":\"remove\",\"Value\":\"old\"")
                || !tags.contains("\"Key\":\"external\",\"Value\":\"preserve\"")
                || tags.contains("\"Key\":\"add\"")
                || tags.contains("\"Value\":\"after\"")) {
            throw new AssertionError("failed update changed event bus tags: " + tags);
        }

        deleteStack(stackName);
        awaitStackDeleted(stackName);
    }

    @Test
    void mutableEventBusPolicyUpdateRollsBackWithoutChangingPolicy() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "policy-bus-" + suffix;
        String stackName = "eventbus-policy-" + suffix;
        String originalTemplate = eventBusWithPolicyTemplate(busName, "Before");

        createStack(stackName, originalTemplate);
        assertStackStatus(stackName, "CREATE_COMPLETE");

        updateStack(stackName, originalTemplate);
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        updateStack(stackName, eventBusWithPolicyTemplate(busName, "After"));
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", EVENTS_AUTH)
            .header("X-Amz-Target", "AWSEvents.DescribeEventBus")
            .body("{\"Name\":\"" + busName + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", containsString("Before"));

        String activeTemplate = getTemplate(stackName);
        if (!activeTemplate.contains("Before") || activeTemplate.contains("After")) {
            throw new AssertionError("failed policy update replaced the active template: " + activeTemplate);
        }

        deleteStack(stackName);
        awaitStackDeleted(stackName);
    }

    @Test
    void invalidEventBusTagsFailValidation() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String[][] cases = {
                {"reserved-lower", """
                        [{"Key":"aws:reserved","Value":"value"}]
                        """, "reserved aws: prefix"},
                {"reserved-upper", """
                        [{"Key":"AWS:reserved","Value":"value"}]
                        """, "reserved aws: prefix"},
                {"invalid-key", """
                        [{"Key":"bad*key","Value":"value"}]
                        """, "Key contains unsupported characters"},
                {"invalid-value", """
                        [{"Key":"key","Value":"bad*value"}]
                        """, "Value contains unsupported characters"},
                {"duplicate-key", """
                        [{"Key":"duplicate","Value":"one"},{"Key":"duplicate","Value":"two"}]
                        """, "Duplicate event bus tag Key"}
        };

        for (String[] testCase : cases) {
            String stackName = "eventbus-invalid-tag-" + testCase[0] + "-" + suffix;
            createStack(stackName,
                    eventBusWithTagsTemplate("invalid-tag-" + testCase[0] + "-" + suffix,
                            testCase[1]));
            assertStackStatus(stackName, "ROLLBACK_COMPLETE");
            String events = describeStackEvents(stackName);
            if (!events.contains(testCase[2])) {
                throw new AssertionError(
                        testCase[0] + " tag failure was not preserved: " + events);
            }
        }
    }

    @Test
    void eventBusTagCountAndLengthLimitsAreValidated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        StringBuilder tooManyTags = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                tooManyTags.append(',');
            }
            tooManyTags.append("""
                    {"Key":"key-%d","Value":"value"}
                    """.formatted(i).strip());
        }
        tooManyTags.append(']');

        String[][] cases = {
                {"too-many", tooManyTags.toString(), "supports at most 50 tags"},
                {"long-key", """
                        [{"Key":"%s","Value":"value"}]
                        """.formatted("k".repeat(129)), "Key must contain 1 to 128 characters"},
                {"long-value", """
                        [{"Key":"key","Value":"%s"}]
                        """.formatted("v".repeat(257)), "Value must contain at most 256 characters"}
        };

        for (String[] testCase : cases) {
            String stackName = "eventbus-tag-limit-" + testCase[0] + "-" + suffix;
            createStack(stackName,
                    eventBusWithTagsTemplate("tag-limit-" + testCase[0] + "-" + suffix,
                            testCase[1]));
            assertStackStatus(stackName, "ROLLBACK_COMPLETE");
            String events = describeStackEvents(stackName);
            if (!events.contains(testCase[2])) {
                throw new AssertionError(
                        testCase[0] + " tag failure was not preserved: " + events);
            }
        }
    }

    @Test
    void updateDoesNotAdoptBusRecreatedUnderSameName() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "recreated-bus-" + suffix;
        String stackName = "eventbus-recreated-" + suffix;
        String template = eventBusOnlyTemplate(busName, "owned");

        createStack(stackName, template);
        assertStackStatus(stackName, "CREATE_COMPLETE");
        callEventBridge("AWSEvents.DeleteEventBus", "{\"Name\":\"" + busName + "\"}", 200);
        Thread.sleep(2);
        callEventBridge("AWSEvents.CreateEventBus",
                "{\"Name\":\"" + busName + "\",\"Description\":\"external\"}", 200);

        updateStack(stackName, template);
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + busName + "\"}", 200);

        deleteStack(stackName);
        awaitStackStatus(stackName, "DELETE_FAILED");
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + busName + "\"}", 200);

        callEventBridge("AWSEvents.DeleteEventBus", "{\"Name\":\"" + busName + "\"}", 200);
        deleteStack(stackName);
        awaitStackDeleted(stackName);
    }

    @Test
    void eventBusDeleteFailurePropagatesAndRetrySucceeds() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "blocked-delete-bus-" + suffix;
        String ruleName = "external-rule-" + suffix;
        String stackName = "eventbus-blocked-delete-" + suffix;

        createStack(stackName, eventBusOnlyTemplate(busName, "blocked"));
        assertStackStatus(stackName, "CREATE_COMPLETE");
        callEventBridge("AWSEvents.PutRule", """
                {"Name":"%s","EventBusName":"%s","EventPattern":"{\\\"source\\\":[\\\"external\\\"]}"}
                """.formatted(ruleName, busName), 200);

        deleteStack(stackName);
        awaitStackStatus(stackName, "DELETE_FAILED");
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + busName + "\"}", 200);

        callEventBridge("AWSEvents.DeleteRule",
                "{\"Name\":\"" + ruleName + "\",\"EventBusName\":\"" + busName + "\"}", 200);
        deleteStack(stackName);
        awaitStackDeleted(stackName);
        callEventBridge("AWSEvents.DescribeEventBus", "{\"Name\":\"" + busName + "\"}", 400);
    }

    @Test
    void deletingStackTreatsAlreadyAbsentBusAsSuccess() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String busName = "already-gone-bus-" + suffix;
        String stackName = "eventbus-already-gone-" + suffix;

        createStack(stackName, eventBusOnlyTemplate(busName, "gone"));
        assertStackStatus(stackName, "CREATE_COMPLETE");
        callEventBridge("AWSEvents.DeleteEventBus", "{\"Name\":\"" + busName + "\"}", 200);

        deleteStack(stackName);
        awaitStackDeleted(stackName);
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
            .statusCode(400)
            .body(containsString("ResourceNotFoundException"));
    }
}
