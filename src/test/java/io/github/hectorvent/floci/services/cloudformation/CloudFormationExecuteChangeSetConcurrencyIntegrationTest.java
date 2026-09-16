package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationExecuteChangeSetConcurrencyIntegrationTest {

    private final List<String> stacksToDelete = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteStacks() {
        for (String stackName : stacksToDelete) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/");
        }
        stacksToDelete.clear();
    }

    @Test
    void executeChangeSet_concurrentCalls_onlyOneRunsAndProvisionsResources() throws Exception {
        String stackName = uniqueStackName("race");
        createChangeSet(stackName, "initial", "CREATE", topicTemplate("Topic"))
                .then().statusCode(200).body(containsString("<Id>"));

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Response> response1 = new AtomicReference<>();
        AtomicReference<Response> response2 = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread caller1 = new Thread(() -> race(start, () -> response1.set(executeChangeSet(stackName, "initial")), unexpected));
        Thread caller2 = new Thread(() -> race(start, () -> response2.set(executeChangeSet(stackName, "initial")), unexpected));
        caller1.start();
        caller2.start();
        start.countDown();
        caller1.join(TimeUnit.SECONDS.toMillis(10));
        caller2.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(caller1.isAlive() || caller2.isAlive(), "a thread never finished");
        assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

        List<Response> responses = List.of(response1.get(), response2.get());
        assertEquals(1, responses.stream().filter(r -> r.getStatusCode() == 200).count(),
                () -> "expected exactly one call accepted: " + bodies(responses));
        assertEquals(1, responses.stream().filter(r -> r.getStatusCode() == 400).count(),
                () -> "expected exactly one call rejected: " + bodies(responses));

        String rejectedBody = responses.get(0).getStatusCode() == 400
                ? responses.get(0).asString() : responses.get(1).asString();
        assertThat(rejectedBody, containsString("<Code>InvalidChangeSetStatus</Code>"));
        assertTrue(rejectedBody.contains("execution status of [EXECUTE_IN_PROGRESS]")
                || rejectedBody.contains("execution status of [EXECUTE_COMPLETE]"), rejectedBody);

        waitForStackStatus(stackName, "CREATE_COMPLETE");
        assertEquals(1, countTopicsWithPrefix(stackName + "-Topic-"), "expected exactly one topic provisioned");
    }

    @Test
    void executeChangeSet_afterCompletion_rejectsSecondExecution() {
        String stackName = uniqueStackName("reexec");
        createChangeSet(stackName, "initial", "CREATE", topicTemplate("Topic")).then().statusCode(200);
        executeChangeSet(stackName, "initial").then().statusCode(200);
        waitForStackStatus(stackName, "CREATE_COMPLETE");

        assertEquals("EXECUTE_COMPLETE", describeChangeSetExecutionStatus(stackName, "initial"));

        Response second = executeChangeSet(stackName, "initial");
        second.then().statusCode(400).body(containsString("<Code>InvalidChangeSetStatus</Code>"));
        assertThat(second.asString(), containsString("execution status of [EXECUTE_COMPLETE]"));

        assertEquals(1, countTopicsWithPrefix(stackName + "-Topic-"));
    }

    @Test
    void executeChangeSet_deletesOtherChangeSetsOnTheStack() {
        String stackName = uniqueStackName("delete-siblings");
        createChangeSet(stackName, "initial", "CREATE", topicTemplate("Base")).then().statusCode(200);
        executeChangeSet(stackName, "initial").then().statusCode(200);
        waitForStackStatus(stackName, "CREATE_COMPLETE");

        createChangeSet(stackName, "update-a", "UPDATE", twoTopicTemplate("Base", "TopicA")).then().statusCode(200);
        createChangeSet(stackName, "update-b", "UPDATE", twoTopicTemplate("Base", "TopicB")).then().statusCode(200);

        executeChangeSet(stackName, "update-a").then().statusCode(200);
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        describeChangeSet(stackName, "update-b").then().statusCode(400)
                .body(containsString("<Code>ChangeSetNotFoundException</Code>"));
        executeChangeSet(stackName, "update-b").then().statusCode(400)
                .body(containsString("<Code>ChangeSetNotFoundException</Code>"));

        assertEquals("EXECUTE_COMPLETE", describeChangeSetExecutionStatus(stackName, "update-a"));

        assertEquals(1, countTopicsWithPrefix(stackName + "-TopicA-"));
        assertEquals(0, countTopicsWithPrefix(stackName + "-TopicB-"));
    }

    @Test
    void executeChangeSet_afterCreateStack_rejectsInitialCreateChangeSet() {
        String stackName = uniqueStackName("internal");
        createStack(stackName, topicTemplate("Topic"));

        Response rejected = executeChangeSet(stackName, "initial-create");
        rejected.then().statusCode(400).body(containsString("<Code>InvalidChangeSetStatus</Code>"));
        assertThat(rejected.asString(), containsString("execution status of [EXECUTE_"));
    }

    @Test
    void updateStack_marksPendingChangeSetsObsolete() {
        String stackName = uniqueStackName("obsolete");
        createStack(stackName, topicTemplate("Base"));
        waitForStackStatus(stackName, "CREATE_COMPLETE");
        createChangeSet(stackName, "pending", "UPDATE", twoTopicTemplate("Base", "TopicA")).then().statusCode(200);

        updateStack(stackName, twoTopicTemplate("Base", "TopicB"));
        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        assertEquals("OBSOLETE", describeChangeSetExecutionStatus(stackName, "pending"));
        Response rejected = executeChangeSet(stackName, "pending");
        rejected.then().statusCode(400).body(containsString("<Code>InvalidChangeSetStatus</Code>"));
        assertThat(rejected.asString(), containsString("execution status of [OBSOLETE]"));
        assertEquals(0, countTopicsWithPrefix(stackName + "-TopicA-"));
    }

    private String uniqueStackName(String prefix) {
        String name = "cfn-execute-" + prefix + "-" + Long.toString(System.nanoTime(), 36);
        stacksToDelete.add(name);
        return name;
    }

    private static String topicTemplate(String logicalId) {
        return """
            Resources:
              %s:
                Type: AWS::SNS::Topic
            """.formatted(logicalId);
    }

    private static String twoTopicTemplate(String logicalId1, String logicalId2) {
        return """
            Resources:
              %s:
                Type: AWS::SNS::Topic
              %s:
                Type: AWS::SNS::Topic
            """.formatted(logicalId1, logicalId2);
    }

    private static Response createChangeSet(String stackName, String changeSetName, String changeSetType, String template) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .formParam("ChangeSetType", changeSetType)
            .formParam("TemplateBody", template)
        .when()
            .post("/");
    }

    private static void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static Response executeChangeSet(String stackName, String changeSetName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
        .when()
            .post("/");
    }

    private static Response describeChangeSet(String stackName, String changeSetName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
        .when()
            .post("/");
    }

    private static String describeChangeSetExecutionStatus(String stackName, String changeSetName) {
        return describeChangeSet(stackName, changeSetName)
            .then()
            .statusCode(200)
            .extract()
            .xmlPath()
            .getString("DescribeChangeSetResponse.DescribeChangeSetResult.ExecutionStatus");
    }

    private static long countTopicsWithPrefix(String prefix) {
        List<String> arns = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListTopics")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .xmlPath()
            .getList("ListTopicsResponse.ListTopicsResult.Topics.member.TopicArn", String.class);
        return arns.stream()
                .filter(arn -> arn.substring(arn.lastIndexOf(':') + 1).startsWith(prefix))
                .count();
    }

    private static void race(CountDownLatch start, Runnable action, AtomicReference<Throwable> unexpected) {
        await(start);
        try {
            action.run();
        } catch (Throwable t) {
            unexpected.set(t);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String bodies(List<Response> responses) {
        return responses.get(0).asString() + " | " + responses.get(1).asString();
    }

    private static void waitForStackStatus(String stackName, String status) {
        String expected = "<StackStatus>" + status + "</StackStatus>";
        String body = "";
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            body = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .asString();
            if (body.contains(expected)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack " + stackName
                        + " to reach " + expected, e);
            }
        }
        assertThat(body, containsString(expected));
    }
}
