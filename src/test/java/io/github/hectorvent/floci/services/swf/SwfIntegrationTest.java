package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end coverage of the SWF wire protocol: registration, the decider/worker
 * handshake, and the resulting history.
 *
 * <p>Expected shapes and error strings here were captured from the live service, so a
 * change that breaks SDK compatibility fails these tests rather than surfacing later as
 * a decider that silently stalls.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class SwfIntegrationTest {

    private static final String SWF_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────────── Domains ────────────────────────────────

    @Test
    void registerDomain_thenDescribe_returnsDomainInfoAndConfiguration() {
        String domain = uniqueName("describe-domain");
        registerDomain(domain);

        call("DescribeDomain", """
                {"name": "%s"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("domainInfo.name", equalTo(domain))
                .body("domainInfo.status", equalTo("REGISTERED"))
                .body("domainInfo.description", equalTo("floci test domain"))
                .body("domainInfo.arn", startsWith("arn:aws:swf:us-east-1:"))
                .body("domainInfo.arn", org.hamcrest.Matchers.endsWith(":/domain/" + domain))
                .body("configuration.workflowExecutionRetentionPeriodInDays", equalTo("7"));
    }

    @Test
    void registerDomain_duplicate_returnsDomainAlreadyExistsFaultNamedByDomain() {
        String domain = uniqueName("dup-domain");
        registerDomain(domain);

        // The live service reports the bare domain name as the message, not a sentence.
        call("RegisterDomain", """
                {"name": "%s", "workflowExecutionRetentionPeriodInDays": "7"}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#DomainAlreadyExistsFault"))
                .body("message", equalTo(domain));
    }

    @Test
    void describeDomain_unknown_returnsUnknownResourceFaultWithUnknownDomainMessage() {
        call("DescribeDomain", """
                {"name": "no-such-domain-floci"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"))
                .body("message", equalTo("Unknown domain: no-such-domain-floci"));
    }

    @Test
    void listDomains_badRegistrationStatus_returnsCoralValidationException() {
        call("ListDomains", """
                {"registrationStatus": "BOGUS"}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazon.coral.validate#ValidationException"))
                .body("message", org.hamcrest.Matchers.containsString(
                        "Member must satisfy enum value set: [REGISTERED, DEPRECATED]"));
    }

    @Test
    void deprecateDomain_thenListDeprecated_movesDomainBetweenStatuses() {
        String domain = uniqueName("deprecate-domain");
        registerDomain(domain);

        call("DeprecateDomain", """
                {"name": "%s"}
                """.formatted(domain)).then().statusCode(200);

        call("ListDomains", """
                {"registrationStatus": "DEPRECATED"}
                """)
                .then()
                .statusCode(200)
                .body("domainInfos.name", hasItem(domain));

        call("ListDomains", """
                {"registrationStatus": "REGISTERED"}
                """)
                .then()
                .statusCode(200)
                .body("domainInfos.name", org.hamcrest.Matchers.not(hasItem(domain)));

        // Deprecating twice is an error; undeprecating restores REGISTERED.
        call("DeprecateDomain", """
                {"name": "%s"}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#DomainDeprecatedFault"));

        call("UndeprecateDomain", """
                {"name": "%s"}
                """.formatted(domain)).then().statusCode(200);

        call("DescribeDomain", """
                {"name": "%s"}
                """.formatted(domain))
                .then()
                .body("domainInfo.status", equalTo("REGISTERED"));
    }

    // ────────────────────────────────- Types ─────────────────────────────────

    @Test
    void registerWorkflowType_thenDescribe_echoesRegistrationDefaults() {
        String domain = uniqueName("wf-type");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");

        call("DescribeWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfo.workflowType.name", equalTo("TestWf"))
                .body("typeInfo.workflowType.version", equalTo("1.0"))
                .body("typeInfo.status", equalTo("REGISTERED"))
                // creationDate is an epoch-second number, never an ISO string.
                .body("typeInfo.creationDate", greaterThan(1.0e9f))
                .body("configuration.defaultTaskStartToCloseTimeout", equalTo("60"))
                .body("configuration.defaultExecutionStartToCloseTimeout", equalTo("3600"))
                .body("configuration.defaultTaskList.name", equalTo("floci-tl"))
                .body("configuration.defaultChildPolicy", equalTo("TERMINATE"));
    }

    @Test
    void registerWorkflowType_duplicate_returnsTypeAlreadyExistsFaultWithTypeDescriptor() {
        String domain = uniqueName("wf-type-dup");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");

        call("RegisterWorkflowType", """
                {"domain": "%s", "name": "TestWf", "version": "1.0"}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeAlreadyExistsFault"))
                .body("message", equalTo("WorkflowType=[name=TestWf, version=1.0]"));
    }

    @Test
    void describeWorkflowType_unknown_namesTheTypeInTheMessage() {
        String domain = uniqueName("wf-type-unknown");
        registerDomain(domain);

        call("DescribeWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "NoSuchWf", "version": "1"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"))
                .body("message", equalTo("Unknown type: WorkflowType=[name=NoSuchWf, version=1]"));
    }

    @Test
    void registerActivityType_thenListAndDescribe_returnsTimeoutConfiguration() {
        String domain = uniqueName("act-type");
        registerDomain(domain);
        registerActivityType(domain, "TestAct", "1.0");

        call("DescribeActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfo.activityType.name", equalTo("TestAct"))
                .body("typeInfo.status", equalTo("REGISTERED"))
                .body("configuration.defaultTaskStartToCloseTimeout", equalTo("60"))
                .body("configuration.defaultTaskScheduleToStartTimeout", equalTo("60"))
                .body("configuration.defaultTaskList.name", equalTo("floci-tl"));

        call("ListActivityTypes", """
                {"domain": "%s", "registrationStatus": "REGISTERED"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfos.activityType.name", hasItem("TestAct"));
    }

    @Test
    void deleteWorkflowType_beforeDeprecation_returnsTypeNotDeprecatedFault() {
        String domain = uniqueName("wf-delete");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");

        call("DeleteWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeNotDeprecatedFault"));

        call("DeprecateWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("DeleteWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("DescribeWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"));
    }

    // ───────────────────────────── Execution start ───────────────────────────

    @Test
    void startWorkflowExecution_seedsHistoryWithStartedAndDecisionTaskScheduled() {
        String domain = uniqueName("start-exec");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-1", "{\"hello\":\"world\"}");

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-1", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .statusCode(200)
                .body("events", hasSize(2))
                .body("events[0].eventId", equalTo(1))
                .body("events[0].eventType", equalTo("WorkflowExecutionStarted"))
                .body("events[0].eventTimestamp", greaterThan(1.0e9f))
                .body("events[0].workflowExecutionStartedEventAttributes.input",
                        equalTo("{\"hello\":\"world\"}"))
                .body("events[0].workflowExecutionStartedEventAttributes.childPolicy",
                        equalTo("TERMINATE"))
                .body("events[0].workflowExecutionStartedEventAttributes.taskList.name",
                        equalTo("floci-tl"))
                .body("events[0].workflowExecutionStartedEventAttributes.workflowType.name",
                        equalTo("TestWf"))
                // Root executions report 0 rather than omitting the member.
                .body("events[0].workflowExecutionStartedEventAttributes.parentInitiatedEventId",
                        equalTo(0))
                .body("events[1].eventId", equalTo(2))
                .body("events[1].eventType", equalTo("DecisionTaskScheduled"))
                .body("events[1].decisionTaskScheduledEventAttributes.startToCloseTimeout",
                        equalTo("60"))
                .body("events[1].decisionTaskScheduledEventAttributes.scheduleToStartTimeout",
                        equalTo("NONE"));
    }

    @Test
    void startWorkflowExecution_whileOneIsOpen_returnsAlreadyStartedFaultWithEmptyMessage() {
        String domain = uniqueName("start-dup");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        startExecution(domain, "wf-dup", null);

        call("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-dup",
                 "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#WorkflowExecutionAlreadyStartedFault"))
                .body("message", equalTo(""));
    }

    @Test
    void startWorkflowExecution_withoutTypeDefault_returnsDefaultUndefinedFault() {
        String domain = uniqueName("start-nodefault");
        registerDomain(domain);
        // Registered with no defaults at all, so every required field must come from the request.
        call("RegisterWorkflowType", """
                {"domain": "%s", "name": "Bare", "version": "1.0"}
                """.formatted(domain)).then().statusCode(200);

        call("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-bare",
                 "workflowType": {"name": "Bare", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#DefaultUndefinedFault"));
    }

    @Test
    void startWorkflowExecution_onDeprecatedType_returnsTypeDeprecatedFault() {
        String domain = uniqueName("start-deprecated");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        call("DeprecateWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-dep",
                 "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeDeprecatedFault"));
    }

    // ──────────────────────── Decider and worker handshake ───────────────────

    @Test
    void fullActivityLifecycle_producesTheExpectedHistoryAndClosesTheExecution() {
        String domain = uniqueName("lifecycle");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerActivityType(domain, "TestAct", "1.0");
        String runId = startExecution(domain, "wf-life", "{\"n\":1}");

        // 1. Decider claims the task and sees the two seeded events.
        Response decisionTask = call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "decider-1"}
                """.formatted(domain));
        decisionTask.then()
                .statusCode(200)
                .body("taskToken", notNullValue())
                .body("workflowExecution.workflowId", equalTo("wf-life"))
                .body("workflowExecution.runId", equalTo(runId))
                .body("workflowType.name", equalTo("TestWf"))
                .body("events.eventType", hasItem("DecisionTaskStarted"))
                .body("previousStartedEventId", equalTo(0));
        String decisionToken = decisionTask.path("taskToken");

        // 2. Decider schedules an activity.
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "executionContext": "step-1",
                 "decisions": [{
                   "decisionType": "ScheduleActivityTask",
                   "scheduleActivityTaskDecisionAttributes": {
                     "activityId": "act-1",
                     "activityType": {"name": "TestAct", "version": "1.0"},
                     "input": "{\\"work\\":true}"
                   }}]}
                """.formatted(decisionToken)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-life", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("DecisionTaskCompleted"))
                .body("events.eventType", hasItem("ActivityTaskScheduled"))
                .body("events.find { it.eventType == 'ActivityTaskScheduled' }"
                        + ".activityTaskScheduledEventAttributes.activityId", equalTo("act-1"))
                .body("events.find { it.eventType == 'ActivityTaskScheduled' }"
                        + ".activityTaskScheduledEventAttributes.taskList.name", equalTo("floci-tl"))
                // Timeouts resolve from the activity type's registration defaults.
                .body("events.find { it.eventType == 'ActivityTaskScheduled' }"
                        + ".activityTaskScheduledEventAttributes.startToCloseTimeout", equalTo("60"));

        // 3. Worker claims the activity, heartbeats, and completes it.
        Response activityTask = call("PollForActivityTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "worker-1"}
                """.formatted(domain));
        activityTask.then()
                .statusCode(200)
                .body("taskToken", notNullValue())
                .body("activityId", equalTo("act-1"))
                .body("activityType.name", equalTo("TestAct"))
                .body("input", equalTo("{\"work\":true}"))
                .body("workflowExecution.runId", equalTo(runId));
        String activityToken = activityTask.path("taskToken");

        call("RecordActivityTaskHeartbeat", """
                {"taskToken": "%s", "details": "50%%"}
                """.formatted(activityToken))
                .then()
                .statusCode(200)
                .body("cancelRequested", is(false));

        call("RespondActivityTaskCompleted", """
                {"taskToken": "%s", "result": "{\\"done\\":true}"}
                """.formatted(activityToken)).then().statusCode(200);

        // 4. Activity completion schedules a fresh decision task.
        Response secondDecision = call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "decider-1"}
                """.formatted(domain));
        secondDecision.then()
                .statusCode(200)
                .body("taskToken", notNullValue())
                .body("events.eventType", hasItem("ActivityTaskCompleted"))
                .body("previousStartedEventId", greaterThan(0));

        // 5. Decider closes the execution.
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "CompleteWorkflowExecution",
                   "completeWorkflowExecutionDecisionAttributes": {"result": "{\\"ok\\":true}"}
                 }]}
                """.formatted(secondDecision.path("taskToken").toString())).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-life", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .statusCode(200)
                .body("executionInfo.executionStatus", equalTo("CLOSED"))
                .body("executionInfo.closeStatus", equalTo("COMPLETED"))
                .body("executionInfo.closeTimestamp", notNullValue())
                .body("openCounts.openActivityTasks", equalTo(0))
                .body("openCounts.openDecisionTasks", equalTo(0))
                .body("latestExecutionContext", equalTo("step-1"));

        call("ListClosedWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("executionInfos.execution.workflowId", hasItem("wf-life"))
                .body("executionInfos.find { it.execution.workflowId == 'wf-life' }.closeStatus",
                        equalTo("COMPLETED"));
    }

    @Test
    void pollForDecisionTask_withNoWork_returnsEmptyTaskTokenRatherThanOmittingIt() {
        String domain = uniqueName("empty-poll");
        registerDomain(domain);

        // An empty token is how the SDKs recognise "no task"; omitting the member makes
        // them fail on a missing required field.
        call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "idle-tl"}, "identity": "decider-1"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("taskToken", equalTo(""))
                .body("startedEventId", equalTo(0));

        call("PollForActivityTask", """
                {"domain": "%s", "taskList": {"name": "idle-tl"}, "identity": "worker-1"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("taskToken", equalTo(""));
    }

    @Test
    void onlyOneDecisionTaskIsOutstandingAtATime() {
        String domain = uniqueName("single-decision");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        startExecution(domain, "wf-single", null);

        Response first = call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "d1"}
                """.formatted(domain));
        first.then().statusCode(200).body("taskToken", notNullValue());

        // A second poll while the first is outstanding must not hand out another task.
        call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "d2"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("taskToken", equalTo(""));

        // A signal arriving mid-decision is recorded but does not create a second task.
        call("SignalWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-single", "signalName": "poke"}
                """.formatted(domain)).then().statusCode(200);

        call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "d3"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("taskToken", equalTo(""));

        // Completing the outstanding task releases the deferred one.
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": []}
                """.formatted(first.path("taskToken").toString())).then().statusCode(200);

        call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "d4"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("taskToken", org.hamcrest.Matchers.not(equalTo("")))
                .body("events.eventType", hasItem("WorkflowExecutionSignaled"));
    }

    @Test
    void respondDecisionTaskCompleted_withStaleToken_returnsUnknownResourceFault() {
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "bogus-token", "decisions": []}
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"));
    }

    @Test
    void respondDecisionTaskCompleted_closeNotLast_returnsCoralValidationException() {
        String domain = uniqueName("close-order");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerActivityType(domain, "TestAct", "1.0");
        String runId = startExecution(domain, "wf-close-order", null);
        String token = pollDecisionToken(domain);

        // The live service rejects the batch outright instead of applying the prefix, and
        // this fault is namespaced com.amazon.coral.validate, not com.amazonaws.swf.base.model.
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "CompleteWorkflowExecution",
                    "completeWorkflowExecutionDecisionAttributes": {"result": "early"}},
                   {"decisionType": "ScheduleActivityTask",
                    "scheduleActivityTaskDecisionAttributes": {
                      "activityId": "after-close",
                      "activityType": {"name": "TestAct", "version": "1.0"}
                    }}]}
                """.formatted(token))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazon.coral.validate#ValidationException"))
                .body("message", equalTo("Close must be last decision in list"));

        // The execution is untouched and the same token still works for a valid batch.
        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-close-order"}}
                """.formatted(domain))
                .then()
                .body("executionInfo.executionStatus", equalTo("OPEN"));

        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "CompleteWorkflowExecution",
                    "completeWorkflowExecutionDecisionAttributes": {"result": "ok"}}]}
                """.formatted(token))
                .then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-close-order", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("executionInfo.closeStatus", equalTo("COMPLETED"));
    }

    @Test
    void closingDecisionWithOpenActivity_failsWithUnhandledDecision() {
        String domain = uniqueName("unhandled");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerActivityType(domain, "TestAct", "1.0");
        String runId = startExecution(domain, "wf-unhandled", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "ScheduleActivityTask",
                   "scheduleActivityTaskDecisionAttributes": {
                     "activityId": "act-open",
                     "activityType": {"name": "TestAct", "version": "1.0"}
                   }}]}
                """.formatted(token)).then().statusCode(200);

        // A successful ScheduleActivityTask does not itself schedule another decision task —
        // the decider waits for the activity to close. A signal is what gets it a task while
        // the activity is still open.
        call("SignalWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-unhandled", "signalName": "poke"}
                """.formatted(domain)).then().statusCode(200);

        // The scheduled activity is still open, so completing the execution is rejected.
        String second = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "CompleteWorkflowExecution",
                   "completeWorkflowExecutionDecisionAttributes": {}
                 }]}
                """.formatted(second)).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-unhandled", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("executionInfo.executionStatus", equalTo("OPEN"));

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-unhandled", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("CompleteWorkflowExecutionFailed"))
                .body("events.find { it.eventType == 'CompleteWorkflowExecutionFailed' }"
                        + ".completeWorkflowExecutionFailedEventAttributes.cause",
                        equalTo("UNHANDLED_DECISION"));
    }

    @Test
    void scheduleActivityTask_withUnknownType_recordsScheduleActivityTaskFailed() {
        String domain = uniqueName("bad-activity");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-bad-act", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "ScheduleActivityTask",
                   "scheduleActivityTaskDecisionAttributes": {
                     "activityId": "nope",
                     "activityType": {"name": "NoSuchAct", "version": "9.9"}
                   }}]}
                """.formatted(token)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-bad-act", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("ScheduleActivityTaskFailed"))
                .body("events.find { it.eventType == 'ScheduleActivityTaskFailed' }"
                        + ".scheduleActivityTaskFailedEventAttributes.cause",
                        equalTo("ACTIVITY_TYPE_DOES_NOT_EXIST"))
                .body("events.find { it.eventType == 'ScheduleActivityTaskFailed' }"
                        + ".scheduleActivityTaskFailedEventAttributes.activityId", equalTo("nope"));
    }

    @Test
    void markerAndTimerDecisions_appendTheirOwnEvents() {
        String domain = uniqueName("marker-timer");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-marker", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                  {"decisionType": "RecordMarker",
                   "recordMarkerDecisionAttributes": {"markerName": "checkpoint", "details": "n=1"}},
                  {"decisionType": "StartTimer",
                   "startTimerDecisionAttributes": {"timerId": "t-1", "startToFireTimeout": "3600"}}
                ]}
                """.formatted(token)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-marker", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("MarkerRecorded"))
                .body("events.find { it.eventType == 'MarkerRecorded' }"
                        + ".markerRecordedEventAttributes.markerName", equalTo("checkpoint"))
                .body("events.eventType", hasItem("TimerStarted"))
                .body("events.find { it.eventType == 'TimerStarted' }"
                        + ".timerStartedEventAttributes.startToFireTimeout", equalTo("3600"));

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-marker", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("openCounts.openTimers", equalTo(1));

        // The timer is still pending, so no decision task is scheduled on its own; a signal
        // is what brings the decider back while the timer runs.
        call("SignalWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-marker", "signalName": "poke"}
                """.formatted(domain)).then().statusCode(200);

        // Cancelling an unknown timer is reported in history rather than faulting.
        String second = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "CancelTimer",
                   "cancelTimerDecisionAttributes": {"timerId": "no-such-timer"}}]}
                """.formatted(second)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-marker", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.find { it.eventType == 'CancelTimerFailed' }"
                        + ".cancelTimerFailedEventAttributes.cause", equalTo("TIMER_ID_UNKNOWN"));
    }

    // ─────────────────────────── External control ────────────────────────────

    @Test
    void signalWorkflowExecution_appendsSignalAndSchedulesADecision() {
        String domain = uniqueName("signal");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-signal", null);

        // Drain the initial decision task so the signal's own task is observable.
        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": []}
                """.formatted(token)).then().statusCode(200);
        drainDecisionTasks(domain);

        call("SignalWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-signal", "runId": "%s",
                 "signalName": "resume", "input": "{\\"go\\":1}"}
                """.formatted(domain, runId)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-signal", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("WorkflowExecutionSignaled"))
                .body("events.find { it.eventType == 'WorkflowExecutionSignaled' }"
                        + ".workflowExecutionSignaledEventAttributes.signalName", equalTo("resume"))
                .body("events.find { it.eventType == 'WorkflowExecutionSignaled' }"
                        + ".workflowExecutionSignaledEventAttributes.input", equalTo("{\"go\":1}"));

        call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "d"}
                """.formatted(domain))
                .then()
                .body("taskToken", org.hamcrest.Matchers.not(equalTo("")));
    }

    @Test
    void requestCancelWorkflowExecution_setsCancelRequestedAndRecordsTheEvent() {
        String domain = uniqueName("cancel-request");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-cancel", null);

        call("RequestCancelWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-cancel", "runId": "%s"}
                """.formatted(domain, runId)).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-cancel", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("executionInfo.cancelRequested", is(true))
                .body("executionInfo.executionStatus", equalTo("OPEN"));

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-cancel", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("WorkflowExecutionCancelRequested"));
    }

    @Test
    void terminateWorkflowExecution_closesWithTerminatedStatus() {
        String domain = uniqueName("terminate");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-terminate", null);

        call("TerminateWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-terminate", "runId": "%s",
                 "reason": "operator", "details": "manual stop"}
                """.formatted(domain, runId)).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-terminate", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("executionInfo.executionStatus", equalTo("CLOSED"))
                .body("executionInfo.closeStatus", equalTo("TERMINATED"));

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-terminate", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.find { it.eventType == 'WorkflowExecutionTerminated' }"
                        + ".workflowExecutionTerminatedEventAttributes.reason", equalTo("operator"))
                .body("events.find { it.eventType == 'WorkflowExecutionTerminated' }"
                        + ".workflowExecutionTerminatedEventAttributes.childPolicy", equalTo("TERMINATE"));

        // A closed execution no longer accepts control operations.
        call("SignalWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-terminate", "runId": "%s", "signalName": "x"}
                """.formatted(domain, runId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"));
    }

    @Test
    void continueAsNew_closesTheRunAndStartsASuccessor() {
        String domain = uniqueName("continue-as-new");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-can", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "ContinueAsNewWorkflowExecution",
                   "continueAsNewWorkflowExecutionDecisionAttributes": {"input": "{\\"gen\\":2}"}
                 }]}
                """.formatted(token)).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-can", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("executionInfo.executionStatus", equalTo("CLOSED"))
                .body("executionInfo.closeStatus", equalTo("CONTINUED_AS_NEW"));

        Response history = call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-can", "runId": "%s"}}
                """.formatted(domain, runId));
        history.then()
                .body("events.eventType", hasItem("WorkflowExecutionContinuedAsNew"))
                .body("events.find { it.eventType == 'WorkflowExecutionContinuedAsNew' }"
                        + ".workflowExecutionContinuedAsNewEventAttributes.newExecutionRunId",
                        notNullValue());

        String newRunId = history.path(
                "events.find { it.eventType == 'WorkflowExecutionContinuedAsNew' }"
                        + ".workflowExecutionContinuedAsNewEventAttributes.newExecutionRunId");

        // The successor is open, carries the new input, and links back to the prior run.
        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-can", "runId": "%s"}}
                """.formatted(domain, newRunId))
                .then()
                .body("executionInfo.executionStatus", equalTo("OPEN"));

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-can", "runId": "%s"}}
                """.formatted(domain, newRunId))
                .then()
                .body("events[0].workflowExecutionStartedEventAttributes.input", equalTo("{\"gen\":2}"))
                .body("events[0].workflowExecutionStartedEventAttributes.continuedExecutionRunId",
                        equalTo(runId));
    }

    @Test
    void startChildWorkflowExecution_linksParentAndReportsCompletionToTheParent() {
        String domain = uniqueName("child-wf");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String parentRunId = startExecution(domain, "wf-parent", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "StartChildWorkflowExecution",
                   "startChildWorkflowExecutionDecisionAttributes": {
                     "workflowId": "wf-child",
                     "workflowType": {"name": "TestWf", "version": "1.0"},
                     "input": "{\\"child\\":true}"
                   }}]}
                """.formatted(token)).then().statusCode(200);

        Response parentHistory = call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-parent", "runId": "%s"}}
                """.formatted(domain, parentRunId));
        parentHistory.then()
                .body("events.eventType", hasItem("StartChildWorkflowExecutionInitiated"))
                .body("events.eventType", hasItem("ChildWorkflowExecutionStarted"));

        int initiatedEventId = parentHistory.path(
                "events.find { it.eventType == 'StartChildWorkflowExecutionInitiated' }.eventId");
        int childStartedEventId = parentHistory.path(
                "events.find { it.eventType == 'ChildWorkflowExecutionStarted' }.eventId");

        // The child carries the parent link in its own start event.
        Response childHistory = call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-child"}}
                """.formatted(domain));
        childHistory.then()
                .statusCode(200)
                .body("events[0].workflowExecutionStartedEventAttributes.parentWorkflowExecution.workflowId",
                        equalTo("wf-parent"))
                .body("events[0].workflowExecutionStartedEventAttributes.parentWorkflowExecution.runId",
                        equalTo(parentRunId))
                // The child's parentInitiatedEventId is the parent's
                // StartChildWorkflowExecutionInitiated id, not its ChildWorkflowExecutionStarted id.
                .body("events[0].workflowExecutionStartedEventAttributes.parentInitiatedEventId",
                        equalTo(initiatedEventId));

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-parent", "runId": "%s"}}
                """.formatted(domain, parentRunId))
                .then()
                .body("openCounts.openChildWorkflowExecutions", equalTo(1));

        // Completing the child reports ChildWorkflowExecutionCompleted to the parent.
        String childToken = pollDecisionTokenFor(domain, "wf-child");
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [{
                   "decisionType": "CompleteWorkflowExecution",
                   "completeWorkflowExecutionDecisionAttributes": {"result": "child-done"}
                 }]}
                """.formatted(childToken)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-parent", "runId": "%s"}}
                """.formatted(domain, parentRunId))
                .then()
                .body("events.eventType", hasItem("ChildWorkflowExecutionCompleted"))
                .body("events.find { it.eventType == 'ChildWorkflowExecutionCompleted' }"
                        + ".childWorkflowExecutionCompletedEventAttributes.result", equalTo("child-done"))
                // initiatedEventId points at StartChildWorkflowExecutionInitiated and
                // startedEventId at ChildWorkflowExecutionStarted — two different events.
                // Reporting the initiated id for both mismatches any decider correlating
                // on startedEventId.
                .body("events.find { it.eventType == 'ChildWorkflowExecutionCompleted' }"
                        + ".childWorkflowExecutionCompletedEventAttributes.initiatedEventId",
                        equalTo(initiatedEventId))
                .body("events.find { it.eventType == 'ChildWorkflowExecutionCompleted' }"
                        + ".childWorkflowExecutionCompletedEventAttributes.startedEventId",
                        equalTo(childStartedEventId));
    }

    @Test
    void activityTypeDeprecationLifecycle_blocksSchedulingAndGatesDeletion() {
        String domain = uniqueName("act-lifecycle");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerActivityType(domain, "TestAct", "1.0");

        call("DeprecateActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        // Deprecating twice reports the type descriptor, not prose.
        call("DeprecateActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeDeprecatedFault"))
                .body("message", equalTo("ActivityType=[name=TestAct, version=1.0]"));

        // A deprecated type stays describable and is listed under DEPRECATED.
        call("DescribeActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfo.status", equalTo("DEPRECATED"))
                .body("typeInfo.deprecationDate", notNullValue());

        call("ListActivityTypes", """
                {"domain": "%s", "registrationStatus": "DEPRECATED"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfos.activityType.name", hasItem("TestAct"));

        // Scheduling a deprecated type fails the decision rather than the request.
        startExecution(domain, "wf-dep-act", null);
        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "ScheduleActivityTask",
                    "scheduleActivityTaskDecisionAttributes": {
                      "activityId": "a1",
                      "activityType": {"name": "TestAct", "version": "1.0"}
                    }}]}
                """.formatted(token)).then().statusCode(200);

        call("DescribeWorkflowExecution", """
                {"domain": "%s", "execution": {"workflowId": "wf-dep-act"}}
                """.formatted(domain))
                .then()
                .body("executionInfo.executionStatus", equalTo("OPEN"));

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-dep-act"}}
                """.formatted(domain))
                .then()
                .body("events.find { it.eventType == 'ScheduleActivityTaskFailed' }"
                        + ".scheduleActivityTaskFailedEventAttributes.cause",
                        equalTo("ACTIVITY_TYPE_DEPRECATED"));

        // Delete is gated on deprecation, and reports prose rather than the descriptor.
        call("UndeprecateActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("UndeprecateActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeAlreadyExistsFault"));

        call("DeleteActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeNotDeprecatedFault"))
                .body("message",
                        equalTo("The type is currently registered and cannot be deleted in its current state"));

        call("DeprecateActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);
        call("DeleteActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("DescribeActivityType", """
                {"domain": "%s", "activityType": {"name": "TestAct", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"))
                .body("message", equalTo("Unknown type: ActivityType=[name=TestAct, version=1.0]"));
    }

    @Test
    void deleteWorkflowType_isGatedOnDeprecationAndReportsProse() {
        String domain = uniqueName("wf-delete");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");

        call("DeleteWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeNotDeprecatedFault"))
                .body("message",
                        equalTo("The type is currently registered and cannot be deleted in its current state"));

        call("DeprecateWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);
        call("DeleteWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("DeleteWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "TestWf", "version": "1.0"}}
                """.formatted(domain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"))
                .body("message", equalTo("Unknown type: WorkflowType=[name=TestWf, version=1.0]"));
    }

    @Test
    void listWorkflowTypes_filtersByRegistrationStatusAndName() {
        String domain = uniqueName("wf-list");
        registerDomain(domain);
        registerWorkflowType(domain, "Alpha", "1.0");
        registerWorkflowType(domain, "Beta", "1.0");

        call("ListWorkflowTypes", """
                {"domain": "%s", "registrationStatus": "REGISTERED"}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("typeInfos.workflowType.name", hasItem("Alpha"))
                .body("typeInfos.workflowType.name", hasItem("Beta"))
                .body("typeInfos[0].status", equalTo("REGISTERED"))
                .body("typeInfos[0].creationDate", notNullValue());

        call("ListWorkflowTypes", """
                {"domain": "%s", "registrationStatus": "REGISTERED", "name": "Alpha"}
                """.formatted(domain))
                .then()
                .body("typeInfos.size()", equalTo(1))
                .body("typeInfos[0].workflowType.name", equalTo("Alpha"));

        call("DeprecateWorkflowType", """
                {"domain": "%s", "workflowType": {"name": "Alpha", "version": "1.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("ListWorkflowTypes", """
                {"domain": "%s", "registrationStatus": "DEPRECATED"}
                """.formatted(domain))
                .then()
                .body("typeInfos.size()", equalTo(1))
                .body("typeInfos[0].workflowType.name", equalTo("Alpha"));

        call("ListWorkflowTypes", """
                {"domain": "%s", "registrationStatus": "REGISTERED"}
                """.formatted(domain))
                .then()
                .body("typeInfos.workflowType.name", not(hasItem("Alpha")))
                .body("typeInfos.workflowType.name", hasItem("Beta"));
    }

    @Test
    void respondActivityTaskFailed_recordsReasonAndDetailsThenClosesTheToken() {
        String domain = uniqueName("act-fail");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerActivityType(domain, "TestAct", "1.0");
        String runId = startExecution(domain, "wf-act-fail", null);

        String token = pollDecisionToken(domain);
        call("RespondDecisionTaskCompleted", """
                {"taskToken": "%s", "decisions": [
                   {"decisionType": "ScheduleActivityTask",
                    "scheduleActivityTaskDecisionAttributes": {
                      "activityId": "a-fail",
                      "activityType": {"name": "TestAct", "version": "1.0"}
                    }}]}
                """.formatted(token)).then().statusCode(200);

        String activityToken = call("PollForActivityTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "w"}
                """.formatted(domain))
                .then().statusCode(200)
                .extract().path("taskToken");

        call("RespondActivityTaskFailed", """
                {"taskToken": "%s", "reason": "boom", "details": "stack trace here"}
                """.formatted(activityToken)).then().statusCode(200);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-act-fail", "runId": "%s"}}
                """.formatted(domain, runId))
                .then()
                .body("events.eventType", hasItem("ActivityTaskFailed"))
                .body("events.find { it.eventType == 'ActivityTaskFailed' }"
                        + ".activityTaskFailedEventAttributes.reason", equalTo("boom"))
                .body("events.find { it.eventType == 'ActivityTaskFailed' }"
                        + ".activityTaskFailedEventAttributes.details", equalTo("stack trace here"))
                // A failed activity schedules a decision task so the decider can retry or fail.
                .body("events[-1].eventType", equalTo("DecisionTaskScheduled"));

        // The token is genuine but its task has closed, so the fault names the scheduled event.
        call("RespondActivityTaskFailed", """
                {"taskToken": "%s", "reason": "boom", "details": "again"}
                """.formatted(activityToken))
                .then()
                .statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#UnknownResourceFault"))
                .body("message", equalTo("Unknown activity, scheduledEventId = 5"));
    }

    // ───────────────────────────── Counting and tags ─────────────────────────

    @Test
    void countOperations_reportCountAndTruncatedFlag() {
        String domain = uniqueName("counts");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        startExecution(domain, "wf-count", null);

        call("CountOpenWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("count", equalTo(1))
                .body("truncated", is(false));

        call("CountClosedWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("count", equalTo(0));

        call("CountPendingDecisionTasks", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("count", equalTo(1))
                .body("truncated", is(false));

        call("CountPendingActivityTasks", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("count", equalTo(0));
    }

    @Test
    void listOpenWorkflowExecutions_appliesTagAndTypeFilters() {
        String domain = uniqueName("filters");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        registerWorkflowType(domain, "OtherWf", "2.0");

        call("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-tagged",
                 "workflowType": {"name": "TestWf", "version": "1.0"},
                 "tagList": ["alpha", "beta"]}
                """.formatted(domain)).then().statusCode(200);
        call("StartWorkflowExecution", """
                {"domain": "%s", "workflowId": "wf-untagged",
                 "workflowType": {"name": "OtherWf", "version": "2.0"}}
                """.formatted(domain)).then().statusCode(200);

        call("ListOpenWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1},
                 "tagFilter": {"tag": "alpha"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("executionInfos", hasSize(1))
                .body("executionInfos[0].execution.workflowId", equalTo("wf-tagged"))
                .body("executionInfos[0].tagList", hasItem("beta"));

        call("ListOpenWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1},
                 "typeFilter": {"name": "OtherWf"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("executionInfos", hasSize(1))
                .body("executionInfos[0].execution.workflowId", equalTo("wf-untagged"));

        call("ListOpenWorkflowExecutions", """
                {"domain": "%s", "startTimeFilter": {"oldestDate": 1},
                 "executionFilter": {"workflowId": "wf-tagged"}}
                """.formatted(domain))
                .then()
                .statusCode(200)
                .body("executionInfos", hasSize(1))
                .body("executionInfos[0].execution.workflowId", equalTo("wf-tagged"));
    }

    @Test
    void tagResource_thenListAndUntag_roundTripsDomainTags() {
        String domain = uniqueName("tags");
        registerDomain(domain);
        String arn = call("DescribeDomain", """
                {"name": "%s"}
                """.formatted(domain)).path("domainInfo.arn");

        call("TagResource", """
                {"resourceArn": "%s", "tags": [{"key": "env", "value": "test"},
                                               {"key": "team", "value": "floci"}]}
                """.formatted(arn)).then().statusCode(200);

        call("ListTagsForResource", """
                {"resourceArn": "%s"}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("env"))
                .body("tags.find { it.key == 'env' }.value", equalTo("test"));

        call("UntagResource", """
                {"resourceArn": "%s", "tagKeys": ["env"]}
                """.formatted(arn)).then().statusCode(200);

        call("ListTagsForResource", """
                {"resourceArn": "%s"}
                """.formatted(arn))
                .then()
                .statusCode(200)
                .body("tags.key", org.hamcrest.Matchers.not(hasItem("env")))
                .body("tags.key", hasItem("team"));
    }

    @Test
    void getWorkflowExecutionHistory_paginatesWithAnOpaqueToken() {
        String domain = uniqueName("history-page");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-page", null);

        Response firstPage = call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-page", "runId": "%s"},
                 "maximumPageSize": 1}
                """.formatted(domain, runId));
        firstPage.then()
                .statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].eventId", equalTo(1))
                .body("nextPageToken", notNullValue());

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-page", "runId": "%s"},
                 "maximumPageSize": 1, "nextPageToken": "%s"}
                """.formatted(domain, runId, firstPage.path("nextPageToken").toString()))
                .then()
                .statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].eventId", equalTo(2))
                .body("nextPageToken", nullValue());
    }

    @Test
    void getWorkflowExecutionHistory_reverseOrder_returnsNewestFirst() {
        String domain = uniqueName("history-reverse");
        registerDomain(domain);
        registerWorkflowType(domain, "TestWf", "1.0");
        String runId = startExecution(domain, "wf-reverse", null);

        call("GetWorkflowExecutionHistory", """
                {"domain": "%s", "execution": {"workflowId": "wf-reverse", "runId": "%s"},
                 "reverseOrder": true}
                """.formatted(domain, runId))
                .then()
                .statusCode(200)
                .body("events[0].eventType", equalTo("DecisionTaskScheduled"))
                .body("events[1].eventType", equalTo("WorkflowExecutionStarted"));
    }

    @Test
    void unknownOperation_underSwfTargetPrefix_isRejected() {
        given()
                .header("X-Amz-Target", "SimpleWorkflowService.NotARealOperation")
                .contentType(SWF_CONTENT_TYPE)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    // ───────────────────────────────- helpers ────────────────────────────────

    private static String uniqueName(String prefix) {
        return "floci-" + prefix + "-" + System.nanoTime();
    }

    private static Response call(String action, String body) {
        return given()
                .header("X-Amz-Target", "SimpleWorkflowService." + action)
                .contentType(SWF_CONTENT_TYPE)
                .body(body)
                .when()
                .post("/");
    }

    private static void registerDomain(String domain) {
        call("RegisterDomain", """
                {"name": "%s", "description": "floci test domain",
                 "workflowExecutionRetentionPeriodInDays": "7"}
                """.formatted(domain)).then().statusCode(200);
    }

    private static void registerWorkflowType(String domain, String name, String version) {
        call("RegisterWorkflowType", """
                {"domain": "%s", "name": "%s", "version": "%s",
                 "defaultTaskStartToCloseTimeout": "60",
                 "defaultExecutionStartToCloseTimeout": "3600",
                 "defaultTaskList": {"name": "floci-tl"},
                 "defaultChildPolicy": "TERMINATE"}
                """.formatted(domain, name, version)).then().statusCode(200);
    }

    private static void registerActivityType(String domain, String name, String version) {
        call("RegisterActivityType", """
                {"domain": "%s", "name": "%s", "version": "%s",
                 "defaultTaskStartToCloseTimeout": "60",
                 "defaultTaskScheduleToStartTimeout": "60",
                 "defaultTaskScheduleToCloseTimeout": "120",
                 "defaultTaskHeartbeatTimeout": "NONE",
                 "defaultTaskList": {"name": "floci-tl"}}
                """.formatted(domain, name, version)).then().statusCode(200);
    }

    private static String startExecution(String domain, String workflowId, String input) {
        String body = input == null
                ? """
                  {"domain": "%s", "workflowId": "%s",
                   "workflowType": {"name": "TestWf", "version": "1.0"}}
                  """.formatted(domain, workflowId)
                : """
                  {"domain": "%s", "workflowId": "%s",
                   "workflowType": {"name": "TestWf", "version": "1.0"},
                   "input": "%s"}
                  """.formatted(domain, workflowId, input.replace("\"", "\\\""));
        Response response = call("StartWorkflowExecution", body);
        response.then().statusCode(200).body("runId", notNullValue());
        return response.path("runId");
    }

    private static String pollDecisionToken(String domain) {
        Response response = call("PollForDecisionTask", """
                {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "decider"}
                """.formatted(domain));
        response.then().statusCode(200);
        String token = response.path("taskToken");
        org.junit.jupiter.api.Assertions.assertNotNull(token, "expected a decision task");
        org.junit.jupiter.api.Assertions.assertFalse(token.isEmpty(), "expected a decision task");
        return token;
    }

    /**
     * Claims decision tasks until one belongs to {@code workflowId}. Several executions in
     * one domain share a task list, so a plain poll may hand back a sibling's task.
     */
    private static String pollDecisionTokenFor(String domain, String workflowId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            Response response = call("PollForDecisionTask", """
                    {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "decider"}
                    """.formatted(domain));
            response.then().statusCode(200);
            String token = response.path("taskToken");
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (workflowId.equals(response.path("workflowExecution.workflowId"))) {
                return token;
            }
            // Release the sibling's task with an empty decision list so it does not block.
            call("RespondDecisionTaskCompleted", """
                    {"taskToken": "%s", "decisions": []}
                    """.formatted(token)).then().statusCode(200);
        }
        return org.junit.jupiter.api.Assertions.fail("no decision task for " + workflowId);
    }

    private static void drainDecisionTasks(String domain) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Response response = call("PollForDecisionTask", """
                    {"domain": "%s", "taskList": {"name": "floci-tl"}, "identity": "drain"}
                    """.formatted(domain));
            String token = response.path("taskToken");
            if (token == null || token.isEmpty()) {
                return;
            }
            call("RespondDecisionTaskCompleted", """
                    {"taskToken": "%s", "decisions": []}
                    """.formatted(token)).then().statusCode(200);
        }
    }
}
