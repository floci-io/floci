package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A create that fails rolls its resources back and leaves the stack in ROLLBACK_COMPLETE, which
 * AWS treats as terminal: the stack holds the name and nothing else, and the only way forward is
 * DeleteStack. UpdateStack and an UPDATE change set are both refused there, with the message real
 * CloudFormation emits, while DeleteStack and an update over UPDATE_ROLLBACK_COMPLETE keep working.
 */
@QuarkusTest
class CloudFormationRollbackCompleteUpdateIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateStackIsRefusedOnARollbackCompleteStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "rollback-complete-update-" + suffix;
        String topicName = "rollback-complete-update-t-" + suffix;

        createRolledBackStack(stackName, suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", topicTemplate(topicName))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(
                    "Stack:arn:aws:cloudformation:us-east-1:000000000000:stack/" + stackName + "/"))
            .body(containsString(" is in ROLLBACK_COMPLETE state and can not be updated."));

        // The refusal changes nothing: the stack keeps its status and gains no resource.
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(topicName)));

        deleteStack(stackName);
    }

    /**
     * CreateChangeSet with ChangeSetType=UPDATE is refused on the same terms as UpdateStack: both
     * reach the same guard, so a client cannot route around the refusal by building the change set
     * itself.
     */
    @Test
    void aChangeSetIsRefusedOnARollbackCompleteStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "rollback-complete-changeset-" + suffix;
        String topicName = "rollback-complete-changeset-t-" + suffix;

        createRolledBackStack(stackName, suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "rollback-complete-cs-" + suffix)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", topicTemplate(topicName))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(" is in ROLLBACK_COMPLETE state and can not be updated."));

        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
        deleteStack(stackName);
    }

    /**
     * DeleteStack is the way out of ROLLBACK_COMPLETE, so refusing the update must not touch it:
     * the stack goes away and its name is free for a fresh CreateStack.
     */
    @Test
    void deleteStackStillClearsARollbackCompleteStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "rollback-complete-delete-" + suffix;
        String topicName = "rollback-complete-delete-t-" + suffix;

        createRolledBackStack(stackName, suffix);
        deleteStack(stackName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", topicTemplate(topicName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertStackStatus(stackName, "CREATE_COMPLETE");
        deleteStack(stackName);
    }

    /**
     * UPDATE_ROLLBACK_COMPLETE ends in the same eleven characters and means the opposite: an update
     * failed over a stack that is still live and intact, and retrying the update is how it is
     * repaired. Refusing it would strand every stack whose update ever failed.
     */
    @Test
    void updateStaysAllowedAfterAnUpdateRolledBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "update-rollback-complete-" + suffix;
        String topicName = "update-rollback-complete-t-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", topicTemplate(topicName))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertStackStatus(stackName, "CREATE_COMPLETE");

        // An update whose added resource cannot provision rolls back over a live stack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", topicTemplate(topicName, unprovisionableNestedStack()))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertStackStatus(stackName, "UPDATE_ROLLBACK_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", topicTemplate(topicName))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertStackStatus(stackName, "UPDATE_COMPLETE");

        deleteStack(stackName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a stack whose only resource fails, so it settles in ROLLBACK_COMPLETE. */
    private static void createRolledBackStack(String stackName, String suffix) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", "{\"Resources\":{" + unprovisionableNestedStack() + "}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        assertStackStatus(stackName, "ROLLBACK_COMPLETE");
    }

    /** A nested stack with no TemplateURL cannot provision, and its rollback deletes nothing. */
    private static String unprovisionableNestedStack() {
        return "\"Nested\":{\"Type\":\"AWS::CloudFormation::Stack\",\"Properties\":{}}";
    }

    private static String topicTemplate(String topicName) {
        return topicTemplate(topicName, null);
    }

    /**
     * The topic depends on the added resource, so an update that adds a resource which cannot
     * provision fails before the topic is reached. That keeps the rollback to the one resource the
     * failed update created and lands the stack in UPDATE_ROLLBACK_COMPLETE, rather than in
     * UPDATE_ROLLBACK_FAILED over a topic no provisioner knows how to restore.
     */
    private static String topicTemplate(String topicName, String extraResource) {
        return "{\"Resources\":{"
                + "\"Topic\":{\"Type\":\"AWS::SNS::Topic\""
                + (extraResource == null ? "" : ",\"DependsOn\":\"Nested\"")
                + ",\"Properties\":{\"TopicName\":\"" + topicName + "\"}}"
                + (extraResource == null ? "" : "," + extraResource)
                + "}}";
    }

    private static void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
