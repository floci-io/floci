package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.lambda.LambdaExecutorService;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SqsTestQueue;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class StepFunctionsTaskCredentialsIntegrationTest {
    private static final String SOURCE = "000000000331";
    private static final String TARGET = "000000000332";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASS = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """;

    @TestHTTPResource
    URI baseUri;

    @InjectMock
    LambdaExecutorService lambdaExecutor;

    @InjectSpy
    JsonataEvaluator jsonataEvaluator;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync",
            "states:startExecution.sync:2", "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void nestedTaskUsesRoleAccountAndRestoresParentAccount(String resource) throws Exception {
        String child = machine(TARGET, PASS, resource.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD");
        String ownChild = machine(SOURCE, PASS, "STANDARD");
        ObjectNode definition = definition(child, resource, role());
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.remove("End");
        task.put("Next", "OwnChild");
        task.put("ResultPath", "$.child");
        ((ObjectNode) definition.path("States")).set("OwnChild", MAPPER.readTree("""
                {"Type":"Task","Resource":"arn:aws:states:::states:startExecution.sync:2",
                 "Parameters":{"StateMachineArn":"%s","Input":{"restored":true}},"End":true}
                """.formatted(ownChild)));
        String parent = machine(SOURCE, definition.toString(), "STANDARD");
        String execution = start(parent, "{}");
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        JsonNode output = MAPPER.readTree(done.jsonPath().getString("output"));
        assertEquals(true, output.path("Output").path("restored").asBoolean());
        request(TARGET, "DescribeExecution", Map.of("executionArn", execution)).then().statusCode(400);
        Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
        history.then().statusCode(200);
        assertTrue(history.asString().contains(child), history.asString());
        assertTrue(history.asString().contains("ExecutionSucceeded"), history.asString());
    }

    @Test
    void dynamicRoleUsesInputPathBeforeParameters() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        String taskRole = role();
        ObjectNode definition = definition(child, "states:startExecution.sync:2", taskRole);
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.put("InputPath", "$.selected");
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn.$", "$.role")));
        String input = MAPPER.writeValueAsString(Map.of("selected", Map.of("role", taskRole)));
        String execution = start(machine(SOURCE, definition.toString(), "STANDARD"), input);
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
    }

    @Test
    void jsonataRoleUsesOriginalStateInput() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        definition.put("QueryLanguage", "JSONata");
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.set("Arguments", task.remove("Parameters"));
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn", "{% $states.input.role %}")));
        String parent = machine(SOURCE, definition.toString(), "STANDARD");
        clearInvocations(jsonataEvaluator);
        Response done = terminal(SOURCE, start(parent,
                MAPPER.writeValueAsString(Map.of("role", role()))));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        verify(jsonataEvaluator, times(1)).resolveTemplate(any(), eq("Credentials"), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2",
            "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void foreignArnAloneDoesNotGrantAccountAccess(String resource) throws Exception {
        String child = machine(TARGET, PASS, resource.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD");
        ObjectNode definition = definition(child, resource, role());
        ((ObjectNode) definition.path("States").path("Child")).remove("Credentials");
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals(resource.startsWith("aws-sdk:") ? "Sfn.SfnException" : "StepFunctions.AWSStepFunctionsException",
                done.jsonPath().getString("error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-path", "foreign-partition", "invalid-arn"})
    void invalidCredentialsCannotStartChild(String invalid) throws Exception {
        String existing = role();
        String value = switch (invalid) {
            case "missing" -> existing + "-missing";
            case "wrong-path" -> existing.replace(":role/", ":role/different/");
            case "foreign-partition" -> existing.replace("arn:aws:", "arn:aws-cn:");
            default -> "not-an-arn";
        };
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", value);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        if ("missing".equals(invalid) || "wrong-path".equals(invalid)) {
            assertEquals("States.TaskFailed", done.jsonPath().getString("error"));
        } else {
            assertEquals("States.Runtime", done.jsonPath().getString("error"));
        }
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", child)).then()
                .statusCode(200).body("executions.size()", Matchers.equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-role", "extra-member", "non-object", "null-role", "numeric-role"})
    void malformedCredentialsCannotStartChild(String invalid) throws Exception {
        String taskRole = role();
        JsonNode credentials = switch (invalid) {
            case "missing-role" -> MAPPER.createObjectNode();
            case "extra-member" -> MAPPER.valueToTree(Map.of("RoleArn", taskRole, "Extra", true));
            case "non-object" -> MAPPER.createArrayNode().add(taskRole);
            case "null-role" -> MAPPER.readTree("{\"RoleArn\":null}");
            default -> MAPPER.valueToTree(Map.of("RoleArn", 42));
        };
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", taskRole);
        ((ObjectNode) definition.path("States").path("Child")).set("Credentials", credentials);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("States.Runtime", done.jsonPath().getString("error"));
        assertEquals("Task Credentials must resolve to an object containing RoleArn",
                done.jsonPath().getString("cause"));
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", child)).then()
                .statusCode(200).body("executions.size()", Matchers.equalTo(0));
    }

    /** With enforcement off a Map's role is not looked at, so one that does not exist still reads and exports. */
    @Test
    void mapS3CallsRetainDefaultIamDisabledBehavior() throws Exception {
        String bucket = "credentials-map-" + UUID.randomUUID();
        String s3 = auth(SOURCE, "s3");
        given().port(baseUri.getPort()).header("Authorization", s3).put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), s3, bucket));
        given().port(baseUri.getPort()).header("Authorization", s3).body("[1,2]")
                .put("/" + bucket + "/items.json").then().statusCode(200);
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Run", "States", Map.of("Run", Map.of(
                "Type", "Map", "End", true,
                "ItemReader", Map.of("Resource", "arn:aws:states:::s3:getObject",
                        "ReaderConfig", Map.of("InputType", "JSON"),
                        "Parameters", Map.of("Bucket", bucket, "Key", "items.json")),
                "ResultWriter", Map.of("Resource", "arn:aws:states:::s3:putObject",
                        "Parameters", Map.of("Bucket", bucket, "Prefix", "out")),
                "ItemProcessor", Map.of("ProcessorConfig", Map.of("Mode", "DISTRIBUTED", "ExecutionType", "STANDARD"),
                        "StartAt", "Item", "States", Map.of("Item", Map.of("Type", "Pass", "End", true)))))));

        Response done = terminal(SOURCE, start(machine(SOURCE, definition, "STANDARD"), "{}"));

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        String manifest = MAPPER.readTree(done.jsonPath().getString("output")).path("ResultWriterDetails").path("Key").asText();
        given().port(baseUri.getPort()).header("Authorization", s3).get("/" + bucket + "/" + manifest).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"direct", "optimized"})
    void lambdaCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        String name = "credentials-worker-" + UUID.randomUUID();
        String function = "arn:aws:lambda:us-east-1:" + SOURCE + ":function:" + name;
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(SOURCE, "lambda"))
                .delete("/2015-03-31/functions/{name}", name).then().statusCode(204));
        given().port(baseUri.getPort()).header("Authorization", auth(SOURCE, "lambda"))
                .contentType("application/json").body(Map.of(
                        "FunctionName", name, "Runtime", "nodejs20.x", "Handler", "index.handler",
                        "Role", "arn:aws:iam::" + SOURCE + ":role/lambda-role"))
                .post("/2015-03-31/functions").then().statusCode(201);
        when(lambdaExecutor.invoke(any(), any(), any())).thenReturn(
                new InvokeResult(200, null, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req"));
        ObjectNode definition = definition(function, "lambda:invoke", role());
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        if ("direct".equals(integration)) {
            task.put("Resource", function);
            task.set("Parameters", MAPPER.createObjectNode());
        } else {
            task.set("Parameters", MAPPER.valueToTree(Map.of("FunctionName", function)));
        }
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(done.jsonPath().getString("output").contains("\"ok\":true"), done.asString());
        verify(lambdaExecutor).invoke(argThat(fn -> fn != null && function.equals(fn.getFunctionArn())), any(), any());
    }

    /** The execution role does not exist, and neither does the Credentials role: nothing evaluates either. */
    @ParameterizedTest
    @ValueSource(strings = {"sqs:sendMessage", "aws-sdk:sqs:sendMessage"})
    void sqsCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, SOURCE, "us-east-1", "credentials-");
        cleanup.register(queue::delete);
        String taskRole = "arn:aws:iam::" + TARGET + ":role/no-such-role";
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Credentials", Map.of("RoleArn", taskRole),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertEquals("hello", queue.receive().getFirst().getBody());
        assertEquals(taskRole, taskScheduled(execution).path("taskCredentials").path("roleArn").asText());
    }

    /** Nothing evaluates a role that does not exist, nor a publisher no policy allows. */
    @ParameterizedTest
    @ValueSource(strings = {"sns:publish", "aws-sdk:sns:publish", "events:putEvents"})
    void messagingCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        Map<String, Object> arguments = integration.contains("sns")
                ? Map.of("TopicArn", EnforcementFixtures.createTopic(cleanup, baseUri.getPort(), auth(SOURCE, "sns"),
                        "credentials-" + UUID.randomUUID()), "Message", "hello")
                : Map.of("Entries", List.of(Map.of("Source", "credentials", "DetailType", "t", "Detail", "{}")));
        String taskRole = "arn:aws:iam::" + TARGET + ":role/no-such-role";
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Credentials", Map.of("RoleArn", taskRole), "Parameters", arguments, "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertEquals(taskRole, taskScheduled(execution).path("taskCredentials").path("roleArn").asText());
    }

    /** Nothing evaluates a role that does not exist, nor a writer no policy allows. */
    @ParameterizedTest
    @ValueSource(strings = {"dynamodb:putItem", "aws-sdk:dynamodb:putItem", "s3:putObject", "aws-sdk:s3:putObject"})
    void dataCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        String name = "credentials-" + UUID.randomUUID();
        Map<String, Object> arguments;
        if (integration.contains("dynamodb")) {
            dynamoDb("CreateTable", Map.of("TableName", name, "BillingMode", "PAY_PER_REQUEST",
                    "AttributeDefinitions", List.of(Map.of("AttributeName", "pk", "AttributeType", "S")),
                    "KeySchema", List.of(Map.of("AttributeName", "pk", "KeyType", "HASH"))));
            cleanup.register(() -> dynamoDb("DeleteTable", Map.of("TableName", name)));
            arguments = Map.of("TableName", name, "Item", Map.of("pk", Map.of("S", "a")));
        } else {
            given().port(baseUri.getPort()).header("Authorization", auth(SOURCE, "s3")).put("/" + name)
                    .then().statusCode(200);
            cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth(SOURCE, "s3"), name));
            arguments = Map.of("Bucket", name, "Key", "a", "Body", "hello");
        }
        assertDataTaskIsNotEvaluated(integration, arguments, "SUCCEEDED");
    }

    /** Past authorization, the service answers: these name a database nothing has, so they fail with its own error. */
    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:rdsdata:executeStatement", "aws-sdk:redshiftdata:executeStatement"})
    void dataApiCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        Map<String, Object> arguments = integration.contains("rdsdata")
                ? Map.of("ResourceArn", "arn:aws:rds:us-east-1:" + SOURCE + ":cluster:no-such-cluster",
                        "SecretArn", "arn:aws:secretsmanager:us-east-1:" + SOURCE + ":secret:no-such-secret",
                        "Sql", "select 1")
                : Map.of("ClusterIdentifier", "no-such-cluster", "Database", "dev", "Sql", "select 1");
        assertDataTaskIsNotEvaluated(integration, arguments, "FAILED");
    }

    /** Past authorization, ECS answers: nothing here reaches anything, and no role is evaluated. */
    @ParameterizedTest
    @ValueSource(strings = {"ecs:runTask", "ecs:runTask.sync", "aws-sdk:ecs:updateService", "aws-sdk:ecs:describeServices"})
    void ecsCredentialsRetainDefaultIamDisabledBehavior(String integration) throws Exception {
        Map<String, Object> arguments = integration.startsWith("ecs:")
                ? Map.of("TaskDefinition", "no-such-family-" + UUID.randomUUID())
                : Map.of("Cluster", "no-such-cluster", "Service", "any", "Services", List.of("any"));
        assertDataTaskIsNotEvaluated(integration, arguments, "FAILED");
    }

    @Test
    void ecsListServicesCredentialsRetainDefaultIamDisabledBehavior() throws Exception {
        assertDataTaskIsNotEvaluated("aws-sdk:ecs:listServices", Map.of(), "SUCCEEDED");
    }

    /** Past authorization, the service answers: these name what it does not have. */
    @ParameterizedTest
    @CsvSource({"elasticmapreduce:addStep,ClusterId,j-NOSUCHCLUSTER,FAILED",
            "emr-serverless:startApplication,ApplicationId,00nosuchapp00000,FAILED"})
    void emrCredentialsRetainDefaultIamDisabledBehavior(String integration, String member, String value, String status)
            throws Exception {
        Map<String, Object> arguments = integration.contains("addStep")
                ? Map.of(member, value, "Step", Map.of("Name", "job", "ActionOnFailure", "CONTINUE",
                        "HadoopJarStep", Map.of("Jar", "command-runner.jar")))
                : Map.of(member, value);
        assertDataTaskIsNotEvaluated(integration, arguments, status);
    }

    /** Past authorization, the service answers: these name what it does not have, or nothing it needs. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"aws-sdk:scheduler:deleteSchedule|{\"Name\":\"no-such-schedule\"}|FAILED",
            "aws-sdk:cloudformation:listStacks|{}|SUCCEEDED",
            "aws-sdk:cloudformation:describeStacks|{\"StackName\":\"no-such-stack\"}|FAILED",
            "http:invoke|{\"Method\":\"GET\"}|FAILED",
            "aws-sdk:ec2:describeRegions|{}|SUCCEEDED",
            "aws-sdk:lambda:listEventSourceMappings|{}|SUCCEEDED",
            "aws-sdk:secretsmanager:describeSecret|{\"SecretId\":\"no-such-secret\"}|FAILED",
            "aws-sdk:sfn:describeMapRun|{\"MapRunArn\":\"arn:aws:states:us-east-1:000000000001:mapRun:m/l:i\"}|FAILED",
            "aws-sdk:sfn:sendTaskSuccess|{\"TaskToken\":\"no-such-token\"}|FAILED"})
    void controlCredentialsRetainDefaultIamDisabledBehavior(String integration, String arguments, String status)
            throws Exception {
        assertDataTaskIsNotEvaluated(integration, MAPPER.readValue(arguments, Map.class), status);
    }

    private void assertDataTaskIsNotEvaluated(String integration, Map<String, Object> arguments, String status)
            throws Exception {
        String taskRole = "arn:aws:iam::" + TARGET + ":role/no-such-role";
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Credentials", Map.of("RoleArn", taskRole), "Parameters", arguments, "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals(status, done.jsonPath().getString("status"), done.asString());
        String error = String.valueOf(done.jsonPath().getString("error"));
        assertFalse(error.endsWith("AccessDeniedException") || "States.Permissions".equals(error), done.asString());
        assertEquals(taskRole, taskScheduled(execution).path("taskCredentials").path("roleArn").asText());
    }

    private void dynamoDb(String action, Map<String, ?> input) {
        sfn().json(SOURCE, "dynamodb", "DynamoDB_20120810." + action, "application/x-amz-json-1.0", input)
                .then().statusCode(200);
    }

    @Test
    void credentialsThatDoNotResolveStillPassWithEnforcementOff() throws Exception {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, SOURCE, "us-east-1", "credentials-");
        cleanup.register(queue::delete);
        String definition = MAPPER.writeValueAsString(Map.of("StartAt", "Send", "States", Map.of("Send", Map.of(
                "Type", "Task", "Resource", "arn:aws:states:::sqs:sendMessage",
                "Credentials", Map.of("RoleArn.$", "$.missing"),
                "Parameters", Map.of("QueueUrl", queue.url(), "MessageBody", "hello"), "End", true))));

        String execution = start(machine(SOURCE, definition, "STANDARD"), "{}");
        Response done = terminal(SOURCE, execution);

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(taskScheduled(execution).path("taskCredentials").isMissingNode(), done.asString());
    }

    private JsonNode taskScheduled(String execution) throws Exception {
        return sfn().eventsOfType(SOURCE, execution, "TaskScheduled").getFirst().path("taskScheduledEventDetails");
    }

    @Test
    void childFailureIsCaughtInParentAccount() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Fail","States":{"Fail":{"Type":"Fail","Error":"ChildFailure"}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).set("Catch",
                MAPPER.readTree("[{\"ErrorEquals\":[\"States.TaskFailed\"],\"Next\":\"Recovered\"}]"));
        ((ObjectNode) definition.path("States")).set("Recovered",
                MAPPER.readTree("{\"Type\":\"Pass\",\"End\":true}"));
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(done.jsonPath().getString("output").contains("ChildFailure"), done.asString());
    }

    @Test
    void stoppingParentStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        String parent = machine(SOURCE, definition(child, "states:startExecution.sync:2", role()).toString(),
                "STANDARD");
        String execution = start(parent, "{}");
        String childExecution = null;
        for (int i = 0; i < 100; i++) {
            Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
            history.then().statusCode(200);
            for (JsonNode event : MAPPER.readTree(history.asString()).path("events")) {
                if ("TaskSubmitted".equals(event.path("type").asText())) {
                    childExecution = MAPPER.readTree(event.path("taskSubmittedEventDetails").path("output").asText())
                            .path("ExecutionArn").asText();
                }
            }
            if (childExecution != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(childExecution != null && childExecution.contains(TARGET));
        request(SOURCE, "StopExecution", Map.of("executionArn", execution)).then().statusCode(200);
        assertEquals("ABORTED", terminal(TARGET, childExecution).jsonPath().getString("status"));
        assertEquals("ABORTED", terminal(SOURCE, execution).jsonPath().getString("status"));
    }

    @Test
    void taskTimeoutStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).put("TimeoutSeconds", 1);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("States.Timeout", done.jsonPath().getString("error"));
        Response children = request(TARGET, "ListExecutions", Map.of("stateMachineArn", child));
        children.then().statusCode(200).body("executions.size()", Matchers.equalTo(1));
        String execution = children.jsonPath().getString("executions[0].executionArn");
        assertEquals("ABORTED", terminal(TARGET, execution).jsonPath().getString("status"));
    }

    private static ObjectNode definition(String child, String resource, String role) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"StartAt":"Child","States":{"Child":{"Type":"Task","Resource":"arn:aws:states:::%s",
                "Credentials":{"RoleArn":"%s"},"Parameters":{"StateMachineArn":"%s","Input":{}},"End":true}}}
                """.formatted(resource, role, child));
    }

    private String role() {
        String name = "nested-" + UUID.randomUUID();
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), TARGET, "/", name, """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                        "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}
                        """.formatted(SOURCE), """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"states:StartExecution","Resource":"*"}]}
                        """);
    }

    private String machine(String account, String definition, String type) throws Exception {
        return sfn().createMachine(account, "credentials-", definition, type,
                "arn:aws:iam::" + account + ":role/execution");
    }

    private String start(String machine, String input) throws Exception {
        return sfn().start(SOURCE, machine, input);
    }

    private Response terminal(String account, String execution) throws Exception {
        return sfn().terminal(account, execution, 5);
    }

    private Response request(String account, String action, Map<String, ?> input) {
        return sfn().request(account, action, input);
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }
}
