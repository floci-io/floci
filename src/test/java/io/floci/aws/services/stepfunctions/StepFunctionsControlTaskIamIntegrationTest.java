package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StepFunctionsTestClient.LAMBDA_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.STATES_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.trusting;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Scheduler, CloudFormation, HTTP, EC2, Lambda and Secrets Manager SDK, Step Functions SDK and nested tasks under IAM
 * enforcement. Each reaches its real service, so a test reads what a task did from that service.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsControlTaskIamIntegrationTest {
    private static final String ACCOUNT = "000000000472";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_ONLY = allow("*", "sns:ListTopics");
    private static final String ASSUME = allow("*", "sts:AssumeRole");
    private static final String ANY_RESOURCE = "*";
    private static final String TEMPLATE =
            "{\"Resources\":{\"Handle\":{\"Type\":\"AWS::CloudFormation::WaitConditionHandle\"}}}";
    private static final String CHILD_DEFINITION = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";
    private static final Check NOTHING_TO_READ = () -> { };
    private static final AtomicInteger HTTP_CALLS = new AtomicInteger();
    private static HttpServer httpServer;

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void startEndpoint() throws IOException {
        RestAssuredJsonUtils.configureAwsContentTypes();
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            HTTP_CALLS.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();
    }

    @AfterAll
    static void stopEndpoint() {
        httpServer.stop(0);
    }

    @BeforeEach
    void resetEndpoint() {
        HTTP_CALLS.set(0);
    }

    static Stream<String> everyApi() {
        return Stream.of("aws-sdk:scheduler:createSchedule", "aws-sdk:scheduler:updateSchedule",
                "aws-sdk:scheduler:deleteSchedule", "aws-sdk:cloudformation:createStack",
                "aws-sdk:cloudformation:describeStacks", "aws-sdk:cloudformation:deleteStack",
                "aws-sdk:cloudformation:listStacks", "aws-sdk:cloudformation:describeStackSet",
                "aws-sdk:cloudformation:createStackSet", "http:invoke", "aws-sdk:ec2:describeRegions",
                "aws-sdk:lambda:listEventSourceMappings", "aws-sdk:lambda:updateEventSourceMapping",
                "aws-sdk:secretsmanager:describeSecret", "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution",
                "aws-sdk:sfn:sendTaskSuccess", "aws-sdk:sfn:sendTaskFailure", "aws-sdk:sfn:describeMapRun",
                "states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2");
    }

    /** What does not differ between an integration's APIs, nested left to its own tests: it is exempt from most of it. */
    static Stream<String> eachIntegration() {
        return Stream.of("aws-sdk:scheduler:deleteSchedule", "aws-sdk:cloudformation:describeStacks", "http:invoke",
                "aws-sdk:ec2:describeRegions", "aws-sdk:lambda:listEventSourceMappings",
                "aws-sdk:secretsmanager:describeSecret", "aws-sdk:sfn:describeMapRun");
    }

    static Stream<Arguments> eachIntegrationInBothLanguages() {
        return eachIntegration().flatMap(integration -> Stream.of(Arguments.of(integration, "jsonpath"),
                Arguments.of(integration, "jsonata")));
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void anExecutionRoleAllowedTheActionOnTheResourceReachesTheService(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(target, null, role(STATES_TRUST, target.grant())), "{}"));

        assertAnsweredByTheService(completed, target);
        target.reached().check();
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void anExecutionRoleNotAllowedTheActionIsRefused(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(target, null, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, target.error());
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains(target.action()), cause);
        assertTrue(cause.contains(target.resourceArn()), cause);
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("everyApi")
    void theActionOnAnotherResourceDoesNotCount(String integration) throws Exception {
        Target target = target(integration);
        assumeFalse(ANY_RESOURCE.equals(target.resourceArn()), "the service reference gives it no resource");
        String role = role(STATES_TRUST, allow(target.resourceArn() + "-elsewhere", target.action()));

        assertFailed(terminal(start(machine(target, null, role), "{}")), target.error());
        target.notReached().check();
    }

    /** {@code *} is what these are evaluated on: a role allowed them only on some ARN is refused. */
    @ParameterizedTest
    @MethodSource("everyApi")
    void anActionWithNoResourceTypeNeedsAGrantOnAnyResource(String integration) throws Exception {
        Target target = target(integration);
        assumeTrue(ANY_RESOURCE.equals(target.resourceArn()), "the service reference gives it a resource");
        String role = role(STATES_TRUST, allow("arn:aws:sns:" + REGION + ":" + ACCOUNT + ":x", target.action()));

        assertFailed(terminal(start(machine(target, null, role), "{}")), target.error());
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void anExecutionRoleThatDoesNotTrustStepFunctionsIsRefusedWhateverItsPolicies(String integration) throws Exception {
        Target target = target(integration);
        String role = role(LAMBDA_TRUST, target.grant());

        Response completed = terminal(start(machine(target, null, role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void anExecutionRoleThatDoesNotExistIsRefused(String integration) throws Exception {
        Target target = target(integration);
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();

        assertFailed(terminal(start(machine(target, null, role), "{}")), "States.Permissions");
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegrationInBothLanguages")
    void credentialsReachTheServiceAsTheirRoleWithoutTheExecutionRolesPermission(String integration, String form)
            throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), target.grant());
        Map<String, Object> task = "jsonata".equals(form)
                ? new LinkedHashMap<>(Map.of("Type", "Task", "QueryLanguage", "JSONata",
                        "Resource", "arn:aws:states:::" + target.integration(), "Arguments", target.arguments(),
                        "Credentials", Map.of("RoleArn", "{% $states.input.role %}"), "End", true))
                : jsonPathTask(target, taskRole);

        String execution = start(machine(target, task, executionRole), MAPPER.writeValueAsString(Map.of("role", taskRole)));

        assertAnsweredByTheService(terminal(execution), target);
        target.reached().check();
        assertEquals(taskRole, eventsOfType(execution, "TaskScheduled").getFirst()
                .path("taskScheduledEventDetails").path("taskCredentials").path("roleArn").asText());
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aRoleTheExecutionRoleCannotAssumeFailsInTheNestedForm(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, target.grant());
        String taskRole = role(STATES_TRUST, target.grant());

        Response completed = terminal(start(machine(target, jsonPathTask(target, taskRole), executionRole), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").contains("is not authorized to assume"), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aCredentialsRoleNotAllowedTheActionIsRefusedEvenIfTheExecutionRoleIs(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, policy(List.of(
                Map.of("Effect", "Allow", "Action", List.of(target.action()), "Resource", List.of(target.resourceArn())),
                Map.of("Effect", "Allow", "Action", List.of("sts:AssumeRole"), "Resource", List.of("*")))));
        String taskRole = role(trusting(executionRole), READ_ONLY);

        assertFailed(terminal(start(machine(target, jsonPathTask(target, taskRole), executionRole), "{}")), target.error());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("eachIntegration")
    void aRefusalCanBeRetriedAndCaught(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = jsonPathTask(target, null);
        task.put("Retry", List.of(Map.of("ErrorEquals", List.of(target.error()), "IntervalSeconds", 1,
                "BackoffRate", 1.0, "MaxAttempts", 1)));
        task.put("Catch", List.of(Map.of("ErrorEquals", List.of(target.error()), "Next", "Recovered")));
        task.remove("End");
        task.put("Next", "Done");
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", task,
                "Done", Map.of("Type", "Pass", "End", true), "Recovered", Map.of("Type", "Pass", "End", true))));
        String execution = start(machineWithDefinition(target.machineName(), definition, role(STATES_TRUST, READ_ONLY)), "{}");

        assertEquals("SUCCEEDED", terminal(execution).jsonPath().getString("status"));
        assertEquals(2, eventsOfType(execution, "TaskFailed").size());
        assertEquals(target.error(), eventsOfType(execution, "TaskFailed").getLast()
                .path("taskFailedEventDetails").path("error").asText());
    }

    /** Every integration mapped to a permission serves its callback form, and refuses it the same way. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "aws-sdk:scheduler:createSchedule|Scheduler.AccessDeniedException|{\"Name\":\"n\"}",
            "aws-sdk:scheduler:deleteSchedule|Scheduler.AccessDeniedException|{\"Name\":\"n\"}",
            "aws-sdk:cloudformation:listStacks|CloudFormation.AccessDeniedException|{}",
            "http:invoke|States.Http.AccessDeniedException|{\"ApiEndpoint\":\"http://localhost:1/\",\"Method\":\"GET\"}",
            "aws-sdk:ec2:describeRegions|Ec2.AccessDeniedException|{}",
            "aws-sdk:lambda:listEventSourceMappings|Lambda.AccessDeniedException|{}",
            "aws-sdk:sfn:describeMapRun|Sfn.AccessDeniedException|{\"MapRunArn\":\"arn:aws:states:us-east-1:000000000472:mapRun:m/l:i\"}",
            "states:startExecution|StepFunctions.AccessDeniedException|{\"StateMachineArn\":\"arn:aws:states:us-east-1:000000000472:stateMachine:child\"}",
            "sqs:sendMessage|SQS.AccessDeniedException|{\"QueueUrl\":\"q\",\"MessageBody\":\"m\"}",
            "aws-sdk:sqs:sendMessage|Sqs.AccessDeniedException|{\"QueueUrl\":\"q\",\"MessageBody\":\"m\"}",
            "sns:publish|SNS.AuthorizationErrorException|{\"TopicArn\":\"arn:aws:sns:us-east-1:000000000472:t\"}",
            "aws-sdk:sns:publish|Sns.AuthorizationErrorException|{\"TopicArn\":\"arn:aws:sns:us-east-1:000000000472:t\"}",
            "events:putEvents|EventBridge.AccessDeniedException|{\"Entries\":[{\"Source\":\"s\"}]}",
            "dynamodb:putItem|DynamoDB.AccessDeniedException|{\"TableName\":\"t\"}",
            "aws-sdk:dynamodb:putItem|DynamoDb.AccessDeniedException|{\"TableName\":\"t\"}",
            "s3:putObject|S3.AccessDeniedException|{\"Bucket\":\"b\",\"Key\":\"k\"}",
            "aws-sdk:s3:putObject|S3.AccessDeniedException|{\"Bucket\":\"b\",\"Key\":\"k\"}",
            "aws-sdk:rdsdata:executeStatement|RdsData.AccessDeniedException|{\"ResourceArn\":\"arn:aws:rds:us-east-1:000000000472:cluster:c\"}",
            "aws-sdk:redshiftdata:listStatements|RedshiftData.AccessDeniedException|{}",
            "aws-sdk:ecs:listServices|Ecs.AccessDeniedException|{}"})
    void waitForTaskTokenIsRefusedWithoutWaiting(String integration, String error, String arguments) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integration + ".waitForTaskToken",
                "Parameters", MAPPER.readValue(arguments, Map.class), "End", true));

        assertFailed(terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}")), error);
    }

    /** With the permission, the callback form calls the service and then waits on its token. */
    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:scheduler:createSchedule", "http:invoke", "aws-sdk:ec2:describeRegions",
            "states:startExecution"})
    void waitForTaskTokenAllowedReachesTheServiceAndWaits(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = jsonPathTask(target, null);
        task.put("Resource", "arn:aws:states:::" + integration + ".waitForTaskToken");

        String execution = start(machine(target, task, role(STATES_TRUST, target.grant())), "{}");

        awaiting("the task to start").until(() -> !eventsOfType(execution, "TaskStarted").isEmpty());
        target.awaitReached().check();
        assertEquals("RUNNING", request("DescribeExecution", Map.of("executionArn", execution))
                .jsonPath().getString("status"));
    }

    /** Not a form EMR serves, whatever the role is allowed. */
    @Test
    void aFormAnIntegrationDoesNotServeKeepsItsAnswer() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::elasticmapreduce:addStep.waitForTaskToken",
                "Parameters", Map.of("ClusterId", "j-X"), "End", true));

        Response completed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").startsWith("Unsupported resource"), completed.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:scheduler:getSchedule", "aws-sdk:sfn:describeExecution", "aws-sdk:s3:getObject",
            "s3:getObject", "dynamodb:query", "aws-sdk:rdsdata:batchExecuteStatement", "aws-sdk:lambda:invoke",
            "aws-sdk:secretsmanager:getSecretValue"})
    void anApiNoIntegrationServesKeepsItsAnswerForARoleWithNoPermissions(String integration) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integration, "Parameters", Map.of("Name", "n"), "End", true));

        Response completed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(LAMBDA_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").startsWith("Unsupported"), completed.asString());
    }

    /** Nested tasks do not refuse an execution role for its trust policy or for not existing as a role: only for the grant. */
    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2"})
    void aNestedTaskWithoutCredentialsNeedsOnlyTheAction(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(target, null, role(LAMBDA_TRUST, target.grant())), "{}"));

        assertAnsweredByTheService(completed, target);
        target.reached().check();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void anSdkStartWithoutCredentialsChecksTrust(String integration) throws Exception {
        Target target = target(integration);

        assertFailed(terminal(start(machine(target, null, role(LAMBDA_TRUST, target.grant())), "{}")), "States.Permissions");
        target.notReached().check();
    }

    @Test
    void aNestedTaskForARoleThatDoesNotExistIsRefusedTheActionAndNotForTheRole() throws Exception {
        Target target = target("states:startExecution");
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();

        assertFailed(terminal(start(machine(target, null, role), "{}")), target.error());
        target.notReached().check();
    }

    /** Decided by {@code NestedTaskCredentials} as before: no trust refusal of the execution role, in either form. */
    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "aws-sdk:sfn:startExecution"})
    void aNestedTaskWithCredentialsKeepsItsOwnDecision(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(LAMBDA_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), target.grant());

        Response completed = terminal(start(machine(target, jsonPathTask(target, taskRole), executionRole), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
    }

    /** {@code NestedTaskCredentials} does not see the callback form, so it is authorized here, on the Credentials role. */
    @ParameterizedTest
    @CsvSource({"true,STARTED", "false,REFUSED"})
    void aNestedCallbackWithCredentialsIsAuthorizedAsTheirRole(boolean taskRoleAllowed, String outcome) throws Exception {
        Target target = target("states:startExecution");
        String executionRole = role(LAMBDA_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), taskRoleAllowed ? target.grant() : READ_ONLY);
        Map<String, Object> task = jsonPathTask(target, taskRole);
        task.put("Resource", "arn:aws:states:::states:startExecution.waitForTaskToken");

        String execution = start(machine(target, task, executionRole), "{}");

        if ("REFUSED".equals(outcome)) {
            assertFailed(terminal(execution), target.error());
            target.notReached().check();
        } else {
            target.awaitReached().check();
        }
    }

    @Test
    void aNestedCallbackWithARoleTheExecutionRoleCannotAssumeFailsInTheNestedForm() throws Exception {
        Target target = target("states:startExecution");
        String executionRole = role(STATES_TRUST, target.grant());
        String taskRole = role(STATES_TRUST, target.grant());
        Map<String, Object> task = jsonPathTask(target, taskRole);
        task.put("Resource", "arn:aws:states:::states:startExecution.waitForTaskToken");

        Response completed = terminal(start(machine(target, task, executionRole), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").contains("is not authorized to assume"), completed.asString());
        target.notReached().check();
    }

    /** A nested {@code .sync} needs the starting call alone, not {@code states:DescribeExecution} for its wait. */
    @Test
    void aNestedSyncTaskNeedsOnlyTheStartingCall() throws Exception {
        Target target = target("states:startExecution.sync");
        String role = role(STATES_TRUST, allow(target.resourceArn(), "states:StartExecution"));

        assertEquals("SUCCEEDED", terminal(start(machine(target, null, role), "{}")).jsonPath().getString("status"));
    }

    /** The CDK's shape: {@code *} under a {@code StringLike} on the endpoint. */
    @ParameterizedTest
    @CsvSource({"true,SUCCEEDED", "false,FAILED"})
    void anHttpGrantOnAnyResourceIsConditionedOnTheEndpoint(boolean matches, String status) throws Exception {
        Target target = target("http:invoke");
        String pattern = matches ? httpEndpoint() + "*" : "https://elsewhere.example/*";
        String role = role(STATES_TRUST, policy(List.of(Map.of("Effect", "Allow", "Action",
                List.of("states:InvokeHTTPEndpoint"), "Resource", List.of("*"),
                "Condition", Map.of("StringLike", Map.of("states:HTTPEndpoint", pattern))))));

        Response completed = terminal(start(machine(target, null, role), "{}"));

        assertEquals(status, completed.jsonPath().getString("status"), completed.asString());
        assertEquals(matches ? 1 : 0, HTTP_CALLS.get());
    }

    /** The Developer Guide's shape: the state machine, under conditions on the endpoint and the method. */
    @ParameterizedTest
    @CsvSource({"GET,SUCCEEDED", "POST,FAILED"})
    void anHttpGrantOnTheStateMachineIsConditionedOnTheMethod(String method, String status) throws Exception {
        Target target = target("http:invoke");
        String role = role(STATES_TRUST, policy(List.of(Map.of("Effect", "Allow", "Action",
                List.of("states:InvokeHTTPEndpoint"), "Resource", List.of(target.resourceArn()),
                "Condition", Map.of("StringEquals", Map.of("states:HTTPMethod", method),
                        "StringLike", Map.of("states:HTTPEndpoint", httpEndpoint() + "*"))))));

        assertEquals(status, terminal(start(machine(target, null, role), "{}")).jsonPath().getString("status"));
    }

    @Test
    void anHttpRequestNamingNoEndpointIsLeftToTheIntegration() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task", "Resource", "arn:aws:states:::http:invoke",
                "Parameters", Map.of("Method", "GET"), "End", true));

        assertFailed(terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}")),
                "States.Runtime");
    }

    /**
     * The task reads its Connection to authorize the request, but the connection's own permissions,
     * {@code events:RetrieveConnectionCredentials} and its secret's, are not evaluated: a role lacking them succeeds
     * here and fails on AWS, whose service reference example grants them.
     */
    @Test
    void anHttpTaskDoesNotNeedTheConnectionsPermissions() throws Exception {
        Target target = target("http:invoke");
        Response completed = terminal(start(machine(target, null,
                role(STATES_TRUST, allow("*", "states:InvokeHTTPEndpoint"))), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(1, HTTP_CALLS.get());
    }

    /** A stack named by name or by id is the one resource, in the id CloudFormation holds for it. */
    @ParameterizedTest
    @ValueSource(strings = {"name", "id"})
    void aStackIsEvaluatedAsItsStoredId(String form) throws Exception {
        String name = "sfn-control-" + UUID.randomUUID();
        String stackId = stack(name);
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:cloudformation:describeStacks",
                "Parameters", Map.of("StackName", "id".equals(form) ? stackId : name), "End", true));

        Response allowed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task,
                role(STATES_TRUST, allow(stackId, "cloudformation:DescribeStacks"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());

        Response refused = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}"));
        assertFailed(refused, "CloudFormation.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(stackId), refused.asString());
    }

    /** {@code DescribeStacks} with nothing to name lists every stack, so it is on any resource. */
    @Test
    void describeStacksNamingNoStackIsOnAnyResource() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:cloudformation:describeStacks", "End", true));

        Response refused = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}"));
        assertFailed(refused, "CloudFormation.AccessDeniedException");

        Response allowed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task,
                role(STATES_TRUST, allow("*", "cloudformation:DescribeStacks"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    @ParameterizedTest
    @CsvSource({"deleteStack,StackName", "describeStackEvents,StackName", "describeStackSet,StackSetName"})
    void aStackCloudFormationDoesNotHaveIsLeftToCloudFormation(String api, String member) throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:cloudformation:" + api,
                "Parameters", Map.of(member, "no-such-" + UUID.randomUUID()), "End", true));

        Response completed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFalse(String.valueOf(completed.jsonPath().getString("error")).endsWith("AccessDeniedException"),
                completed.asString());
    }

    /** A change set's ARN does not hold its stack's name, so the stack is found through it. */
    @Test
    void aChangeSetNamedByItsArnIsEvaluatedOnItsStack() throws Exception {
        String name = "sfn-control-" + UUID.randomUUID();
        String stackId = stack(name);
        Response changeSet = cloudFormation("CreateChangeSet", Map.of("StackName", name, "ChangeSetName", "c",
                "TemplateBody", TEMPLATE));
        changeSet.then().statusCode(200);
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:cloudformation:describeChangeSet",
                "Parameters", Map.of("ChangeSetName", changeSet.xmlPath()
                        .getString("CreateChangeSetResponse.CreateChangeSetResult.Id")), "End", true));

        assertStackActionIsEvaluatedOnTheStack(task, stackId, "cloudformation:DescribeChangeSet");
    }

    @Test
    void describeStackResourcesNamingAPhysicalIdIsEvaluatedOnItsStack() throws Exception {
        String name = "sfn-control-" + UUID.randomUUID();
        String stackId = stack(name);
        String physicalId = cloudFormation("ListStackResources", Map.of("StackName", name)).xmlPath()
                .getString("ListStackResourcesResponse.ListStackResourcesResult.StackResourceSummaries.member.PhysicalResourceId");
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:cloudformation:describeStackResources",
                "Parameters", Map.of("PhysicalResourceId", physicalId), "End", true));

        assertStackActionIsEvaluatedOnTheStack(task, stackId, "cloudformation:DescribeStackResources");
    }

    private void assertStackActionIsEvaluatedOnTheStack(Map<String, Object> task, String stackId, String action)
            throws Exception {
        Response refused = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}"));
        assertFailed(refused, "CloudFormation.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(stackId), refused.asString());

        Response allowed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task,
                role(STATES_TRUST, allow(stackId, action))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** A token that names no task reaches nothing, so Step Functions answers it whatever the role is allowed. */
    @Test
    void aCallbackWhoseTokenNamesNoTaskIsLeftToStepFunctions() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:sfn:sendTaskSuccess",
                "Parameters", Map.of("TaskToken", "no-such-token", "Output", "{}"), "End", true));

        assertFailed(terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}")),
                "Sfn.InvalidTokenException");
    }

    /** A secret named by its name is evaluated on its stored ARN, random suffix included. */
    @Test
    void aSecretNamedByItsNameIsEvaluatedOnItsStoredArn() throws Exception {
        String name = "sfn-control-" + UUID.randomUUID();
        String arn = secret(name);
        assertFalse(arn.endsWith(name), arn);
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:secretsmanager:describeSecret",
                "Parameters", Map.of("SecretId", name), "End", true));

        Response completed = terminal(start(machine("sfn-control-" + UUID.randomUUID(), task,
                role(STATES_TRUST, allow(arn, "secretsmanager:DescribeSecret"))), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @Test
    void aSecretNameThatNamesNoSecretIsLeftToSecretsManager() throws Exception {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::aws-sdk:secretsmanager:describeSecret",
                "Parameters", Map.of("SecretId", "no-such-" + UUID.randomUUID()), "End", true));

        assertFailed(terminal(start(machine("sfn-control-" + UUID.randomUUID(), task, role(STATES_TRUST, READ_ONLY)), "{}")),
                "SecretsManager.ResourceNotFoundException");
    }

    private Target target(String integration) throws Exception {
        String machine = "sfn-control-" + UUID.randomUUID();
        return switch (integration) {
            case "aws-sdk:scheduler:createSchedule" -> {
                String name = schedule();
                cleanup.register(() -> scheduler("DELETE", name, null));
                yield target(integration, machine, scheduleRequest(name, "rate(1 hour)"), "scheduler:CreateSchedule",
                        scheduleArn(name), "Scheduler.AccessDeniedException", null,
                        () -> assertEquals(200, scheduler("GET", name, null).statusCode()),
                        () -> assertEquals(404, scheduler("GET", name, null).statusCode()));
            }
            case "aws-sdk:scheduler:updateSchedule" -> {
                String name = existingSchedule();
                yield target(integration, machine, scheduleRequest(name, "rate(2 hours)"), "scheduler:UpdateSchedule",
                        scheduleArn(name), "Scheduler.AccessDeniedException", null,
                        () -> assertEquals("rate(2 hours)", scheduler("GET", name, null).jsonPath().getString("ScheduleExpression")),
                        () -> assertEquals("rate(1 hour)", scheduler("GET", name, null).jsonPath().getString("ScheduleExpression")));
            }
            case "aws-sdk:scheduler:deleteSchedule" -> {
                String name = existingSchedule();
                yield target(integration, machine, Map.of("Name", name), "scheduler:DeleteSchedule", scheduleArn(name),
                        "Scheduler.AccessDeniedException", null,
                        () -> assertEquals(404, scheduler("GET", name, null).statusCode()),
                        () -> assertEquals(200, scheduler("GET", name, null).statusCode()));
            }
            case "aws-sdk:cloudformation:createStack" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                cleanup.register(() -> cloudFormation("DeleteStack", Map.of("StackName", name)));
                yield target(integration, machine, Map.of("StackName", name, "TemplateBody", TEMPLATE),
                        "cloudformation:CreateStack", "arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stack/" + name + "/*",
                        "CloudFormation.AccessDeniedException", null,
                        () -> assertEquals(200, cloudFormation("DescribeStacks", Map.of("StackName", name)).statusCode()),
                        () -> assertEquals(400, cloudFormation("DescribeStacks", Map.of("StackName", name)).statusCode()));
            }
            case "aws-sdk:cloudformation:describeStacks" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                yield target(integration, machine, Map.of("StackName", name), "cloudformation:DescribeStacks", stack(name),
                        "CloudFormation.AccessDeniedException", null, NOTHING_TO_READ, NOTHING_TO_READ);
            }
            case "aws-sdk:cloudformation:deleteStack" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                String id = stack(name);
                yield target(integration, machine, Map.of("StackName", name), "cloudformation:DeleteStack", id,
                        "CloudFormation.AccessDeniedException", null,
                        () -> awaiting("the stack to go").until(() -> !stackLive(name)), () -> assertTrue(stackLive(name)));
            }
            case "aws-sdk:cloudformation:listStacks" -> target(integration, machine, Map.of(), "cloudformation:ListStacks",
                    ANY_RESOURCE, "CloudFormation.AccessDeniedException", null, NOTHING_TO_READ, NOTHING_TO_READ);
            case "aws-sdk:cloudformation:describeStackSet" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                yield target(integration, machine, Map.of("StackSetName", name), "cloudformation:DescribeStackSet",
                        stackSet(name), "CloudFormation.AccessDeniedException", null, NOTHING_TO_READ, NOTHING_TO_READ);
            }
            case "aws-sdk:cloudformation:createStackSet" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                cleanup.register(() -> cloudFormation("DeleteStackSet", Map.of("StackSetName", name)));
                yield target(integration, machine, Map.of("StackSetName", name, "TemplateBody", TEMPLATE),
                        "cloudformation:CreateStackSet", ANY_RESOURCE, "CloudFormation.AccessDeniedException", null,
                        () -> assertEquals(200, cloudFormation("DescribeStackSet", Map.of("StackSetName", name)).statusCode()),
                        () -> assertEquals(404, cloudFormation("DescribeStackSet", Map.of("StackSetName", name)).statusCode()));
            }
            case "http:invoke" -> target(integration, machine, Map.of("ApiEndpoint", httpEndpoint(), "Method", "GET",
                            "InvocationConfig", Map.of("ConnectionArn", connection())),
                    "states:InvokeHTTPEndpoint", machineArn(machine), "States.Http.AccessDeniedException", null,
                    () -> assertEquals(1, HTTP_CALLS.get()), () -> assertEquals(0, HTTP_CALLS.get()));
            case "aws-sdk:ec2:describeRegions" -> target(integration, machine, Map.of(), "ec2:DescribeRegions", ANY_RESOURCE,
                    "Ec2.AccessDeniedException", null, NOTHING_TO_READ, NOTHING_TO_READ);
            case "aws-sdk:lambda:listEventSourceMappings" -> target(integration, machine, Map.of(),
                    "lambda:ListEventSourceMappings", ANY_RESOURCE, "Lambda.AccessDeniedException", null,
                    NOTHING_TO_READ, NOTHING_TO_READ);
            case "aws-sdk:lambda:updateEventSourceMapping" -> {
                String uuid = UUID.randomUUID().toString();
                yield target(integration, machine, Map.of("Uuid", uuid, "BatchSize", 1), "lambda:UpdateEventSourceMapping",
                        "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":event-source-mapping:" + uuid,
                        "Lambda.AccessDeniedException", "Lambda.ResourceNotFoundException", NOTHING_TO_READ, NOTHING_TO_READ);
            }
            case "aws-sdk:secretsmanager:describeSecret" -> {
                String name = "sfn-control-" + UUID.randomUUID();
                yield target(integration, machine, Map.of("SecretId", name), "secretsmanager:DescribeSecret", secret(name),
                        "SecretsManager.AccessDeniedException", null, NOTHING_TO_READ, NOTHING_TO_READ);
            }
            case "aws-sdk:sfn:startExecution" -> childTarget(integration, machine, "states:StartExecution", "Sfn.", "STANDARD");
            case "aws-sdk:sfn:startSyncExecution" -> childTarget(integration, machine, "states:StartSyncExecution", "Sfn.", "EXPRESS");
            case "states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2" ->
                    childTarget(integration, machine, "states:StartExecution", "StepFunctions.", "STANDARD");
            case "aws-sdk:sfn:sendTaskSuccess" -> callbackTarget(integration, machine, "states:SendTaskSuccess",
                    Map.of("Output", "{}"), "SUCCEEDED");
            case "aws-sdk:sfn:sendTaskFailure" -> callbackTarget(integration, machine, "states:SendTaskFailure",
                    Map.of("Error", "Boom"), "FAILED");
            case "aws-sdk:sfn:describeMapRun" -> {
                String mapRun = "arn:aws:states:" + REGION + ":" + ACCOUNT + ":mapRun:" + machine + "/label:" + UUID.randomUUID();
                yield target(integration, machine, Map.of("MapRunArn", mapRun), "states:DescribeMapRun", mapRun,
                        "Sfn.AccessDeniedException", "Sfn.ResourceNotFoundException", NOTHING_TO_READ, NOTHING_TO_READ);
            }
            default -> throw new IllegalArgumentException(integration);
        };
    }

    private Target childTarget(String integration, String machine, String action, String prefix, String type) {
        String child = "sfn-control-child-" + UUID.randomUUID();
        String arn = machineWithDefinition(child, CHILD_DEFINITION, role(STATES_TRUST, READ_ONLY), type);
        Check notStarted = () -> assertEquals(0, executions(arn));
        Check started = () -> assertEquals(1, executions(arn));
        return "EXPRESS".equals(type)
                ? target(integration, machine, Map.of("StateMachineArn", arn), action, arn, prefix + "AccessDeniedException",
                        null, NOTHING_TO_READ, NOTHING_TO_READ)
                : target(integration, machine, Map.of("StateMachineArn", arn), action, arn, prefix + "AccessDeniedException",
                        null, started, notStarted);
    }

    /** A waiting execution and the token of its activity task, which AWS authorizes a callback on. */
    private Target callbackTarget(String integration, String machine, String action, Map<String, Object> arguments,
                                  String finished) throws Exception {
        String activityName = "sfn-control-" + UUID.randomUUID();
        Response activity = request("CreateActivity", Map.of("name", activityName));
        activity.then().statusCode(200);
        String activityArn = activity.jsonPath().getString("activityArn");
        cleanup.register(() -> request("DeleteActivity", Map.of("activityArn", activityArn)));
        String waiting = start(machine("sfn-control-" + UUID.randomUUID(), new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", activityArn, "End", true)), role(STATES_TRUST, READ_ONLY)), "{}");
        Response task = request("GetActivityTask", Map.of("activityArn", activityArn));
        task.then().statusCode(200);
        Map<String, Object> request = new LinkedHashMap<>(arguments);
        request.put("TaskToken", task.jsonPath().getString("taskToken"));
        return target(integration, machine, request, action, activityArn, "Sfn.AccessDeniedException", null,
                () -> assertEquals(finished, terminal(waiting).jsonPath().getString("status")),
                () -> assertEquals("RUNNING", request("DescribeExecution", Map.of("executionArn", waiting))
                        .jsonPath().getString("status")));
    }

    private static Target target(String integration, String machine, Map<String, ?> arguments, String action,
                                 String resourceArn, String error, String serviceError, Check reached, Check notReached) {
        return new Target(integration, machine, arguments, action, resourceArn, error, serviceError, reached, notReached);
    }

    private record Target(String integration, String machineName, Map<String, ?> arguments, String action,
                          String resourceArn, String error, String serviceError, Check reached, Check notReached) {

        String grant() {
            return allow(resourceArn, action);
        }

        /** {@link #reached}, for a task whose execution has not finished: the call happens after the task starts. */
        Check awaitReached() {
            return () -> await("the task to reach its service").atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(50)).untilAsserted(reached::check);
        }
    }

    @FunctionalInterface
    private interface Check {
        void check() throws Exception;
    }


    private static void assertAnsweredByTheService(Response completed, Target target) {
        if (target.serviceError() == null) {
            assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        } else {
            assertFailed(completed, target.serviceError());
        }
    }

    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    private String httpEndpoint() {
        return "http://localhost:" + httpServer.getAddress().getPort() + "/control";
    }

    private static String schedule() {
        return "sfn-control-" + UUID.randomUUID();
    }

    private String existingSchedule() {
        String name = schedule();
        scheduler("POST", name, scheduleRequest(name, "rate(1 hour)")).then().statusCode(200);
        cleanup.register(() -> scheduler("DELETE", name, null));
        return name;
    }

    private static Map<String, Object> scheduleRequest(String name, String expression) {
        return Map.of("Name", name, "ScheduleExpression", expression, "FlexibleTimeWindow", Map.of("Mode", "OFF"),
                "Target", Map.of("Arn", "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":q",
                        "RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/scheduler"));
    }

    private static String scheduleArn(String name) {
        return "arn:aws:scheduler:" + REGION + ":" + ACCOUNT + ":schedule/default/" + name;
    }

    private Response scheduler(String method, String name, Map<String, ?> body) {
        RequestSpecification spec = given().port(baseUri.getPort())
                .header("Authorization", auth(ACCOUNT, "scheduler")).contentType("application/json");
        if (body != null) {
            spec = spec.body(MAPPER.valueToTree(body).toString());
        }
        return spec.request(method, "/schedules/" + name);
    }

    private String stack(String name) throws Exception {
        Response created = cloudFormation("CreateStack", Map.of("StackName", name, "TemplateBody", TEMPLATE));
        created.then().statusCode(200);
        cleanup.register(() -> cloudFormation("DeleteStack", Map.of("StackName", name)));
        awaiting("stack " + name).until(() -> cloudFormation("DescribeStacks", Map.of("StackName", name)).asString()
                .contains("CREATE_COMPLETE"));
        return created.xmlPath().getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private boolean stackLive(String name) {
        return cloudFormation("DescribeStacks", Map.of("StackName", name)).statusCode() == 200;
    }

    private String stackSet(String name) {
        cloudFormation("CreateStackSet", Map.of("StackSetName", name, "TemplateBody", TEMPLATE)).then().statusCode(200);
        cleanup.register(() -> cloudFormation("DeleteStackSet", Map.of("StackSetName", name)));
        String id = cloudFormation("DescribeStackSet", Map.of("StackSetName", name)).xmlPath()
                .getString("DescribeStackSetResponse.DescribeStackSetResult.StackSet.StackSetId");
        return "arn:aws:cloudformation:" + REGION + ":" + ACCOUNT + ":stackset/" + id;
    }

    private Response cloudFormation(String action, Map<String, String> parameters) {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "cloudformation"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action)
                .formParam("Version", "2010-05-15").formParams(parameters).post("/");
    }

    private String secret(String name) {
        Response created = given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager.CreateSecret").contentType("application/x-amz-json-1.1")
                .body(MAPPER.valueToTree(Map.of("Name", name, "SecretString", "s")).toString()).post("/");
        created.then().statusCode(200);
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager.DeleteSecret").contentType("application/x-amz-json-1.1")
                .body(MAPPER.valueToTree(Map.of("SecretId", name, "ForceDeleteWithoutRecovery", true)).toString()).post("/"));
        return created.jsonPath().getString("ARN");
    }

    private String connection() {
        String name = "sfn-control-" + UUID.randomUUID();
        Response created = given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "events"))
                .header("X-Amz-Target", "AWSEvents.CreateConnection").contentType("application/x-amz-json-1.1")
                .body(MAPPER.valueToTree(Map.of("Name", name, "AuthorizationType", "API_KEY", "AuthParameters",
                        Map.of("ApiKeyAuthParameters", Map.of("ApiKeyName", "x-api-key", "ApiKeyValue", "k")))).toString())
                .post("/");
        created.then().statusCode(200);
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "events"))
                .header("X-Amz-Target", "AWSEvents.DeleteConnection").contentType("application/x-amz-json-1.1")
                .body(MAPPER.valueToTree(Map.of("Name", name)).toString()).post("/"));
        return created.jsonPath().getString("ConnectionArn");
    }

    private int executions(String stateMachine) {
        return request("ListExecutions", Map.of("stateMachineArn", stateMachine)).jsonPath().getList("executions").size();
    }


    private static Map<String, Object> jsonPathTask(Target target, String taskRole) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + target.integration(), "Parameters", target.arguments(), "End", true));
        if (taskRole != null) {
            task.put("Credentials", Map.of("RoleArn", taskRole));
        }
        return task;
    }

    private static String allow(String resource, String... actions) {
        return policy(List.of(Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", List.of(resource))));
    }

    private static String policy(List<Map<String, Object>> statements) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private String role(String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/",
                "sfn-control-" + UUID.randomUUID(), trust, policy);
    }

    private static String machineArn(String name) {
        return "arn:aws:states:" + REGION + ":" + ACCOUNT + ":stateMachine:" + name;
    }

    private String machine(Target target, Map<String, Object> task, String role) throws Exception {
        return machine(target.machineName(), task != null ? task : jsonPathTask(target, null), role);
    }

    private String machine(String name, Map<String, Object> task, String role) throws Exception {
        return machineWithDefinition(name, MAPPER.writeValueAsString(
                Map.of("StartAt", "Send", "States", Map.of("Send", task))), role);
    }

    private String machineWithDefinition(String name, String definition, String role) {
        return machineWithDefinition(name, definition, role, "STANDARD");
    }

    private String machineWithDefinition(String name, String definition, String role, String type) {
        return sfn().createMachineNamed(ACCOUNT, name, definition, type, role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return sfn().terminal(ACCOUNT, execution, 20);
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

    /** Polls for {@code what} as long and as often as the task tests allow a background start. */
    private static ConditionFactory awaiting(String what) {
        return await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50));
    }

}
