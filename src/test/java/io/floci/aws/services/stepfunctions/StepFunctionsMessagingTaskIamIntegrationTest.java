package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SqsTestQueue;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StepFunctionsTestClient.LAMBDA_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.STATES_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.allow;
import static io.floci.aws.testing.StepFunctionsTestClient.trusting;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SNS and EventBridge tasks under IAM enforcement; each destination forwards to a queue the test reads. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsMessagingTaskIamIntegrationTest {
    private static final String ACCOUNT = "000000000451";
    private static final String OTHER_ACCOUNT = "000000000452";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_ONLY = allow("*", "sns:ListTopics", "events:ListRules");
    private static final String ASSUME = allow("*", "sts:AssumeRole");
    private static final String PUBLISH_ANY = allow("*", "sns:Publish", "events:PutEvents");

    @TestHTTPResource
    URI baseUri;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void anExecutionRoleAllowedTheActionOnTheResourceDelivers(String integration) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, allow(target.resourceArn(), target.action()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(target);
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,jsonpath", "aws-sdk:sns:publish,jsonpath", "events:putEvents,jsonpath",
            "sns:publish,jsonata", "aws-sdk:sns:publish,jsonata", "events:putEvents,jsonata"})
    void anExecutionRoleNotAllowedTheActionIsRefused(String integration, String form) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = "jsonata".equals(form) ? jsonataTask(target, null)
                : jsonPathTask(target, null);
        String machine = machine(task, role(STATES_TRUST, READ_ONLY));

        Response completed = terminal(start(machine, "{}"));

        assertFailed(completed, target.error());
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains(target.action()), cause);
        assertTrue(cause.contains(target.resourceArn()), cause);
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void theActionOnAnotherResourceDoesNotCount(String integration) throws Exception {
        Target target = target(integration);
        String elsewhere = target.resourceArn() + "-elsewhere";
        String machine = machine(jsonPathTask(target, null), role(STATES_TRUST, allow(elsewhere, target.action())));

        assertFailed(terminal(start(machine, "{}")), target.error());
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void anExecutionRoleThatDoesNotTrustStepFunctionsIsRefusedWhateverItsPolicies(String integration) throws Exception {
        Target target = target(integration);
        String role = role(LAMBDA_TRUST, PUBLISH_ANY);

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void anExecutionRoleThatDoesNotExistIsRefused(String integration) throws Exception {
        Target target = target(integration);
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,jsonpath", "aws-sdk:sns:publish,jsonpath", "events:putEvents,jsonpath",
            "sns:publish,jsonata", "aws-sdk:sns:publish,jsonata", "events:putEvents,jsonata"})
    void credentialsDeliverAsTheirRoleWithoutTheExecutionRolesPermission(String integration, String form)
            throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), allow(target.resourceArn(), target.action()));
        Map<String, Object> task = "jsonata".equals(form) ? jsonataTask(target, "{% $states.input.role %}")
                : jsonPathTask(target, taskRole);
        String machine = machine(task, executionRole);

        String execution = start(machine, MAPPER.writeValueAsString(Map.of("role", taskRole)));
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(target);
        JsonNode scheduled = eventsOfType(execution, "TaskScheduled").getFirst();
        assertEquals(taskRole, scheduled.path("taskScheduledEventDetails").path("taskCredentials").path("roleArn").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void aRoleTheExecutionRoleCannotAssumeFailsInTheNestedForm(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, PUBLISH_ANY);
        String taskRole = role(LAMBDA_TRUST, PUBLISH_ANY);

        Response completed = terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertEquals("The role " + executionRole + " is not authorized to assume the task state's role, " + taskRole + ".",
                completed.jsonPath().getString("cause"));
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "events:putEvents"})
    void aCredentialsRoleThatDoesNotExistFailsAsARefusedAssumption(String integration) throws Exception {
        Target target = target(integration);
        String taskRole = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();
        String executionRole = role(STATES_TRUST, PUBLISH_ANY);

        assertFailed(terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}")), "States.TaskFailed");
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void aCredentialsRoleNotAllowedTheActionIsRefusedEvenIfTheExecutionRoleIs(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, allow("*", "sts:AssumeRole", "sns:Publish", "events:PutEvents"));
        String taskRole = role(trusting(executionRole), READ_ONLY);

        Response completed = terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}"));

        assertFailed(completed, target.error());
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains("assumed-role/" + taskRole.substring(taskRole.lastIndexOf('/') + 1)), cause);
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,jsonpath", "aws-sdk:sns:publish,jsonpath", "events:putEvents,jsonpath",
            "sns:publish,jsonata", "events:putEvents,jsonata"})
    void waitForTaskTokenRefusedDoesNotWaitAndDeliversNothing(String integration, String form) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = new LinkedHashMap<>("jsonata".equals(form)
                ? jsonataTask(target, null) : jsonPathTask(target, null));
        task.put("Resource", "arn:aws:states:::" + integration + ".waitForTaskToken");
        task.put("TimeoutSeconds", 60);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, target.error());
        assertNothingDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void waitForTaskTokenAllowedDeliversAndWaits(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), allow(target.resourceArn(), target.action()));
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integration + ".waitForTaskToken",
                "Credentials", Map.of("RoleArn", taskRole),
                "Parameters", target.tokenArguments(), "TimeoutSeconds", 60, "End", true));

        String execution = start(machine(task, executionRole), "{}");
        JsonNode delivered = target.sink().awaitJson(Duration.ofSeconds(5));
        String token = integration.contains("sns")
                ? MAPPER.readTree(delivered.path("Message").asText()).path("token").asText()
                : delivered.path("detail").path("token").asText();
        request("SendTaskSuccess", Map.of("taskToken", token, "output", "{\"done\":true}")).then().statusCode(200);
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void aRefusalCanBeRetriedAndCaught(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask(target, null));
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of(target.error()), "IntervalSeconds", 1,
                "BackoffRate", 1.0, "MaxAttempts", 1)));
        task.put("Catch", List.of(Map.of("ErrorEquals", List.of(target.error()), "Next", "Recovered")));
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", task,
                "Recovered", Map.of("Type", "Pass", "End", true))));
        String execution = start(machineWithDefinition(definition, role(STATES_TRUST, READ_ONLY)), "{}");

        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(2, eventsOfType(execution, "TaskFailed").size());
        assertEquals(target.error(), eventsOfType(execution, "TaskFailed").getLast()
                .path("taskFailedEventDetails").path("error").asText());
    }

    /** The retry decides again, so a role granted the action between attempts then delivers. */
    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "events:putEvents"})
    void aRetryDecidesAgain(String integration) throws Exception {
        Target target = target(integration);
        String roleName = "sfn-msg-" + UUID.randomUUID();
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", roleName,
                STATES_TRUST, READ_ONLY);
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask(target, null));
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of(target.error()), "IntervalSeconds", 2,
                "BackoffRate", 1.0, "MaxAttempts", 3)));
        String execution = start(machine(task, role), "{}");

        await(execution, "TaskFailed");
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "PutRolePolicy").formParam("Version", "2010-05-08")
                .formParam("RoleName", roleName).formParam("PolicyName", "p").formParam("PolicyDocument", PUBLISH_ANY)
                .post("/").then().statusCode(200);
        Response completed = terminal(execution, 20);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish"})
    void aSameAccountTopicPolicyGrantsARoleWithNoIdentityPermission(String integration) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, READ_ONLY);
        setTopicPolicy(target.resourceArn(), topicStatement("Allow", role, target.resourceArn()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(target);
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,SNS.AuthorizationErrorException", "aws-sdk:sns:publish,Sns.AuthorizationErrorException"})
    void aTopicPolicyDenyRefusesARoleItsIdentityPolicyAllows(String integration, String error) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, PUBLISH_ANY);
        setTopicPolicy(target.resourceArn(), topicStatement("Deny", role, target.resourceArn()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, error);
        assertNothingDelivered(target);
    }

    @Test
    void aForeignCredentialsRoleNeedsTheTopicPolicyToo() throws Exception {
        Target target = target("sns:publish");
        String executionRole = role(STATES_TRUST, ASSUME);
        String foreignRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "sfn-msg-" + UUID.randomUUID(), trusting(executionRole), PUBLISH_ANY);
        String machine = machine(jsonPathTask(target, foreignRole), executionRole);

        Response refused = terminal(start(machine, "{}"));

        assertFailed(refused, "SNS.AuthorizationErrorException");
        String cause = refused.jsonPath().getString("cause");
        assertTrue(cause.contains("arn:aws:sts::" + OTHER_ACCOUNT + ":assumed-role/"), cause);
        assertNothingDelivered(target);

        setTopicPolicy(target.resourceArn(), topicStatement("Allow", foreignRole, target.resourceArn()));
        Response allowed = terminal(start(machine, "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertDelivered(target);
    }

    /** Foreign without its identity half: the topic policy alone does not admit another account's role. */
    @Test
    void aForeignCredentialsRoleNeedsItsIdentityPolicyToo() throws Exception {
        Target target = target("sns:publish");
        String executionRole = role(STATES_TRUST, ASSUME);
        String foreignRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "sfn-msg-" + UUID.randomUUID(), trusting(executionRole), READ_ONLY);
        setTopicPolicy(target.resourceArn(), topicStatement("Allow", foreignRole, target.resourceArn()));

        Response completed = terminal(start(machine(jsonPathTask(target, foreignRole), executionRole), "{}"));

        assertFailed(completed, "SNS.AuthorizationErrorException");
        assertNothingDelivered(target);
    }

    @Test
    void aTargetArnNeedsThePermissionOnThatResource() throws Exception {
        Target target = target("sns:publish");
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::sns:publish",
                "Parameters", Map.of("TargetArn", target.resourceArn(), "Message", "hello"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "SNS.AuthorizationErrorException");
        assertNothingDelivered(target);

        Response allowed = terminal(start(machine(task,
                role(STATES_TRUST, allow(target.resourceArn(), "sns:Publish"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertDelivered(target);
    }

    @Test
    void anSmsNeedsThePermissionOnEveryResource() throws Exception {
        Target target = target("sns:publish");
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::sns:publish",
                "Parameters", Map.of("PhoneNumber", "+15555550100", "Message", "hello"), "End", true);

        assertFailed(terminal(start(machine(task,
                role(STATES_TRUST, allow(target.resourceArn(), "sns:Publish"))), "{}")), "SNS.AuthorizationErrorException");

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, PUBLISH_ANY)), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** No permission is checked, so a role with none reaches SNS, which answers with its own errors. */
    @ParameterizedTest
    @CsvSource({"orders,SNS.NotFoundException", "arn:aws:sqs:us-east-1:000000000451:orders,SNS.NotFoundException",
            "'',SNS.NotFoundException"})
    void aTopicArnThatIsNotAnSnsArnIsAnsweredBySns(String topicArn, String error) throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::sns:publish",
                "Parameters", Map.of("TopicArn", topicArn, "Message", "hello"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), error);
    }

    @Test
    void aPublishNamingNothingIsAnsweredBySns() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::sns:publish",
                "Parameters", Map.of("Message", "hello"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")),
                "SNS.InvalidParameterException");
    }

    /** A statement naming the account leaves the decision to the account's IAM policies, as AWS does. */
    @Test
    void anEventBusPolicyNamingTheAccountDoesNotGrantARoleWithNoIdentityPermission() throws Exception {
        Target target = target("events:putEvents");
        String role = role(STATES_TRUST, READ_ONLY);
        events("PutPermission", Map.of("EventBusName", busName(target.resourceArn()), "StatementId", "s",
                "Action", "events:PutEvents", "Principal", ACCOUNT));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), "EventBridge.AccessDeniedException");
        assertNothingDelivered(target);
    }

    @Test
    void aSameAccountBusPolicyNamingTheRoleGrantsItWithNoIdentityPermission() throws Exception {
        Target target = target("events:putEvents");
        String role = role(STATES_TRUST, READ_ONLY);
        putBusPolicy(target.resourceArn(), busStatement("Allow", role, target.resourceArn()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(target);
    }

    @Test
    void aBusPolicyDenyRefusesARoleItsIdentityPolicyAllows() throws Exception {
        Target target = target("events:putEvents");
        String role = role(STATES_TRUST, PUBLISH_ANY);
        putBusPolicy(target.resourceArn(), busStatement("Deny", role, target.resourceArn()));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), "EventBridge.AccessDeniedException");
        assertNothingDelivered(target);
    }

    /** Another account's role needs the bus's grant as well as its own, which {@code PutPermission} gives. */
    @Test
    void aForeignCredentialsRoleNeedsTheBusPolicyToo() throws Exception {
        Target target = target("events:putEvents");
        String executionRole = role(STATES_TRUST, ASSUME);
        String foreignRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "sfn-msg-" + UUID.randomUUID(), trusting(executionRole), PUBLISH_ANY);
        String machine = machine(jsonPathTask(target, foreignRole), executionRole);

        Response refused = terminal(start(machine, "{}"));

        assertFailed(refused, "EventBridge.AccessDeniedException");
        String cause = refused.jsonPath().getString("cause");
        assertTrue(cause.contains("arn:aws:sts::" + OTHER_ACCOUNT + ":assumed-role/"), cause);
        assertNothingDelivered(target);

        events("PutPermission", Map.of("EventBusName", busName(target.resourceArn()), "StatementId", "s",
                "Action", "events:PutEvents", "Principal", OTHER_ACCOUNT));
        Response allowed = terminal(start(machine, "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertDelivered(target);
    }

    @Test
    void aForeignCredentialsRoleNeedsItsIdentityPolicyOnABusToo() throws Exception {
        Target target = target("events:putEvents");
        String executionRole = role(STATES_TRUST, ASSUME);
        String foreignRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), OTHER_ACCOUNT, "/",
                "sfn-msg-" + UUID.randomUUID(), trusting(executionRole), READ_ONLY);
        events("PutPermission", Map.of("EventBusName", busName(target.resourceArn()), "StatementId", "s",
                "Action", "events:PutEvents", "Principal", OTHER_ACCOUNT));

        Response completed = terminal(start(machine(jsonPathTask(target, foreignRole), executionRole), "{}"));

        assertFailed(completed, "EventBridge.AccessDeniedException");
        assertNothingDelivered(target);
    }

    @Test
    void anEntryNamingNoBusNeedsThePermissionOnTheDefaultBus() throws Exception {
        String name = "sfn-iam-default-" + UUID.randomUUID();
        SqsTestQueue sink = eventSink("default", name);
        String defaultBus = "arn:aws:events:" + REGION + ":" + ACCOUNT + ":event-bus/default";
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of(entry(null, name))), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow(defaultBus + "-other", "events:PutEvents"))),
                "{}")), "EventBridge.AccessDeniedException");
        assertEquals(0, sink.receive().size());

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(defaultBus, "events:PutEvents"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(1, sink.receive().size());
    }

    /** The name and each ARN reach the same bus, whichever account the ARN names, so that bus decides. */
    @ParameterizedTest
    @CsvSource({"name", "arn", "foreign-arn"})
    void aBusNamedByItsArnIsTheBusItsLookupReaches(String form) throws Exception {
        Target target = target("events:putEvents");
        String name = busName(target.resourceArn());
        String given = switch (form) {
            case "name" -> name;
            case "arn" -> target.resourceArn();
            default -> "arn:aws:events:" + REGION + ":" + OTHER_ACCOUNT + ":event-bus/" + name;
        };
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of(entry(given, target.source()))), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow(given + "-other", "events:PutEvents"))), "{}")),
                "EventBridge.AccessDeniedException");
        assertEquals(0, target.sink().receive().size());

        Response allowed = terminal(start(machine(task,
                role(STATES_TRUST, allow(target.resourceArn(), "events:PutEvents"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertDelivered(target);
    }

    /** Refused ahead of the call, so the entries for the bus the role may use are not sent either. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void entriesForSeveralBusesAreRefusedWhenOneIsDenied(boolean deniedFirst) throws Exception {
        Target allowedBus = target("events:putEvents");
        Target deniedBus = target("events:putEvents");
        List<Object> entries = deniedFirst
                ? List.of(entry(busName(deniedBus.resourceArn()), deniedBus.source()),
                        entry(busName(allowedBus.resourceArn()), allowedBus.source()))
                : List.of(entry(busName(allowedBus.resourceArn()), allowedBus.source()),
                        entry(busName(deniedBus.resourceArn()), deniedBus.source()));
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", entries), "End", true);
        String role = role(STATES_TRUST, allow(allowedBus.resourceArn(), "events:PutEvents"));

        Response completed = terminal(start(machine(task, role), "{}"));

        assertFailed(completed, "EventBridge.AccessDeniedException");
        assertTrue(completed.jsonPath().getString("cause").contains(deniedBus.resourceArn()), completed.asString());
        assertEquals(0, allowedBus.sink().receive().size());
        assertEquals(0, deniedBus.sink().receive().size());
    }

    @Test
    void anEntryEventBridgeWouldRejectStillNeedsItsBus() throws Exception {
        Target allowedBus = target("events:putEvents");
        Target deniedBus = target("events:putEvents");
        Map<String, Object> rejected = entry(busName(deniedBus.resourceArn()), deniedBus.source());
        rejected.remove("Source");
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of(entry(busName(allowedBus.resourceArn()), allowedBus.source()),
                        rejected)), "End", true);
        String role = role(STATES_TRUST, allow(allowedBus.resourceArn(), "events:PutEvents"));

        Response completed = terminal(start(machine(task, role), "{}"));

        assertFailed(completed, "EventBridge.AccessDeniedException");
        assertTrue(completed.jsonPath().getString("cause").contains(deniedBus.resourceArn()), completed.asString());
        assertEquals(0, allowedBus.sink().receive().size());
    }

    @Test
    void entriesForSeveralBusesEachNeedAndGetTheirPermission() throws Exception {
        Target first = target("events:putEvents");
        Target second = target("events:putEvents");
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of(entry(first.resourceArn(), first.source()),
                        entry(busName(second.resourceArn()), second.source()))), "End", true);
        String role = role(STATES_TRUST, MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                Map.of("Effect", "Allow", "Action", "events:PutEvents",
                        "Resource", List.of(first.resourceArn(), second.resourceArn()))))));

        Response completed = terminal(start(machine(task, role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertDelivered(first);
        assertDelivered(second);
    }

    /** A malformed member names no bus, so a role with no permission gets EventBridge's answer. */
    @Test
    void aMalformedBusArnIsAnsweredByEventBridge() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of(entry("arn:aws:events:", "sfn-iam"))), "End", true);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertNotEquals("EventBridge.AccessDeniedException", completed.jsonPath().getString("error"), completed.asString());
    }

    @Test
    void noEntriesNeedNoPermission() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::events:putEvents",
                "Parameters", Map.of("Entries", List.of()), "End", true);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    private static String topicStatement(String effect, String principal, String topicArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"sns:Publish","Resource":"%s"}]}
                """.formatted(effect, principal, topicArn);
    }

    private static String busStatement(String effect, String principal, String busArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Sid":"s","Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"events:PutEvents","Resource":"%s"}]}
                """.formatted(effect, principal, busArn);
    }

    private void putBusPolicy(String busArn, String policy) {
        events("PutPermission", Map.of("EventBusName", busName(busArn), "Policy", policy));
    }

    private static String busName(String busArn) {
        return busArn.substring(busArn.lastIndexOf('/') + 1);
    }

    private static Map<String, Object> entry(String bus, String source) {
        Map<String, Object> entry = new LinkedHashMap<>(Map.of("Source", source, "DetailType", "t",
                "Detail", Map.of("m", "hello")));
        if (bus != null) {
            entry.put("EventBusName", bus);
        }
        return entry;
    }

    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    private static void assertDelivered(Target target) throws Exception {
        JsonNode delivered = target.sink().awaitJson(Duration.ofSeconds(5));
        assertTrue(delivered.toString().contains("hello"), delivered.toString());
    }

    private static void assertNothingDelivered(Target target) {
        assertEquals(0, target.sink().receive().size());
    }

    /**
     * A topic or an event bus a task sends to, and the queue that receives what reaches it.
     *
     * @param source the event source its rule matches, for a bus
     */
    private record Target(String error, String action, String resourceArn, Map<String, Object> arguments,
                          Map<String, Object> tokenArguments, SqsTestQueue sink, String source) {
    }

    private Target target(String integration) {
        return integration.contains("sns") ? topicTarget(integration.startsWith("aws-sdk") ? "Sns." : "SNS.") : busTarget();
    }

    private Target topicTarget(String errorPrefix) {
        String topicArn = EnforcementFixtures.createTopic(cleanup, baseUri.getPort(), auth(ACCOUNT, "sns"),
                "sfn-iam-" + UUID.randomUUID());
        SqsTestQueue sink = queue();
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "sns"))
                .formParam("Action", "Subscribe").formParam("TopicArn", topicArn)
                .formParam("Protocol", "sqs").formParam("Endpoint", sink.arn())
                .post("/").then().statusCode(200);
        return new Target(errorPrefix + "AuthorizationErrorException", "sns:Publish", topicArn,
                Map.of("TopicArn", topicArn, "Message", "hello"),
                Map.of("TopicArn", topicArn, "Message", Map.of("token.$", "$$.Task.Token")), sink, null);
    }

    private Target busTarget() {
        String name = "sfn-iam-" + UUID.randomUUID();
        Response created = events("CreateEventBus", Map.of("Name", name));
        cleanup.register(() -> events("DeleteEventBus", Map.of("Name", name)));
        String source = "sfn-iam-" + UUID.randomUUID();
        SqsTestQueue sink = eventSink(name, source);
        Map<String, Object> tokenEntry = new LinkedHashMap<>(entry(name, source));
        tokenEntry.put("Detail", Map.of("token.$", "$$.Task.Token"));
        return new Target("EventBridge.AccessDeniedException", "events:PutEvents",
                created.jsonPath().getString("EventBusArn"), Map.of("Entries", List.of(entry(name, source))),
                Map.of("Entries", List.of(tokenEntry)), sink, source);
    }

    /** A queue receiving the events of {@code source} on {@code bus}, through a rule removed with it. */
    private SqsTestQueue eventSink(String bus, String source) {
        SqsTestQueue sink = queue();
        String rule = "sfn-iam-" + UUID.randomUUID();
        events("PutRule", Map.of("Name", rule, "EventBusName", bus,
                "EventPattern", "{\"source\":[\"" + source + "\"]}"));
        cleanup.register(() -> events("DeleteRule", Map.of("Name", rule, "EventBusName", bus)));
        events("PutTargets", Map.of("Rule", rule, "EventBusName", bus,
                "Targets", List.of(Map.of("Id", "1", "Arn", sink.arn()))));
        cleanup.register(() -> events("RemoveTargets", Map.of("Rule", rule, "EventBusName", bus, "Ids", List.of("1"))));
        return sink;
    }

    private Response events(String action, Map<String, ?> input) {
        Response response = given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "events"))
                .header("X-Amz-Target", "AWSEvents." + action)
                .contentType("application/x-amz-json-1.1").body(MAPPER.valueToTree(input).toString()).post("/");
        response.then().statusCode(200);
        return response;
    }

    private void setTopicPolicy(String topicArn, String policy) {
        EnforcementFixtures.setTopicPolicy(baseUri.getPort(), auth(ACCOUNT, "sns"), topicArn, policy);
    }

    private Map<String, Object> jsonPathTask(Target target, String taskRole) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integrationOf(target),
                "Parameters", target.arguments(), "End", true));
        if (taskRole != null) {
            task.put("Credentials", Map.of("RoleArn", taskRole));
        }
        return task;
    }

    private Map<String, Object> jsonataTask(Target target, String roleExpression) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task", "QueryLanguage", "JSONata",
                "Resource", "arn:aws:states:::" + integrationOf(target),
                "Arguments", target.arguments(), "End", true));
        if (roleExpression != null) {
            task.put("Credentials", Map.of("RoleArn", roleExpression));
        }
        return task;
    }

    private static String integrationOf(Target target) {
        return switch (target.error()) {
            case "SNS.AuthorizationErrorException" -> "sns:publish";
            case "Sns.AuthorizationErrorException" -> "aws-sdk:sns:publish";
            default -> "events:putEvents";
        };
    }

    private SqsTestQueue queue() {
        return sfn().queue(sqsService, ACCOUNT, REGION, "sfn-msg-iam-");
    }

    private String role(String trust, String policy) {
        return sfn().role(ACCOUNT, "sfn-msg-", trust, policy);
    }

    private String machine(Map<String, Object> task, String role) throws Exception {
        return sfn().machine(ACCOUNT, "sfn-msg-iam-", task, role);
    }

    private String machineWithDefinition(String definition, String role) {
        return sfn().machineWithDefinition(ACCOUNT, "sfn-msg-iam-", definition, role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return terminal(execution, 5);
    }

    private Response terminal(String execution, int seconds) throws Exception {
        return sfn().terminal(ACCOUNT, execution, seconds);
    }

    private void await(String execution, String eventType) throws Exception {
        sfn().await(ACCOUNT, execution, eventType, 5);
    }

    private List<JsonNode> eventsOfType(String execution, String type) throws Exception {
        return sfn().eventsOfType(ACCOUNT, execution, type);
    }

    private Response request(String action, Map<String, ?> input) {
        return sfn().request(ACCOUNT, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }
}
