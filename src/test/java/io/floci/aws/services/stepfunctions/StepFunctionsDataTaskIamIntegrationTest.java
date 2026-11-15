package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.rdsdata.RdsDataService;
import io.floci.aws.services.redshiftdata.RedshiftDataService;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StepFunctionsTestClient.LAMBDA_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.STATES_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.trusting;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DynamoDB, S3, RDS Data and Redshift Data tasks under IAM enforcement. DynamoDB and S3 run for real, so the tests
 * read what a task wrote. The Data APIs need database containers, so their services are mocked, as
 * {@code StepFunctionsRdsDataIntegrationTest} and {@code StepFunctionsRedshiftDataIntegrationTest} mock them, and
 * reaching the service is what is observed.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsDataTaskIamIntegrationTest {
    private static final String ACCOUNT = "000000000461";
    private static final String OTHER_ACCOUNT = "000000000462";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_ONLY = allow("*", "sns:ListTopics");
    private static final String ASSUME = allow("*", "sts:AssumeRole");
    private static final String SECRET = "arn:aws:secretsmanager:" + REGION + ":" + ACCOUNT + ":secret:db-AbCdEf";
    private static final String CLUSTER = "arn:aws:rds:" + REGION + ":" + ACCOUNT + ":cluster:orders";
    private static final String REDSHIFT_CLUSTER = "arn:aws:redshift:" + REGION + ":" + ACCOUNT + ":cluster:orders";

    @TestHTTPResource
    URI baseUri;

    @InjectMock
    RdsDataService rdsDataService;

    @InjectMock
    RedshiftDataService redshiftDataService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubDataApis() {
        when(rdsDataService.executeStatement(any(JsonNode.class), anyString()))
                .thenAnswer(call -> MAPPER.createObjectNode());
        when(redshiftDataService.executeStatement(any(JsonNode.class), anyString()))
                .thenAnswer(call -> MAPPER.createObjectNode().put("Id", "statement"));
        when(redshiftDataService.describeStatement(any(JsonNode.class)))
                .thenAnswer(call -> MAPPER.createObjectNode().put("Id", "statement"));
    }

    static Stream<String> integrations() {
        return Stream.of("dynamodb:putItem", "aws-sdk:dynamodb:putItem", "s3:putObject", "aws-sdk:s3:putObject",
                "aws-sdk:rdsdata:executeStatement", "aws-sdk:redshiftdata:executeStatement");
    }

    static Stream<Arguments> integrationsInBothLanguages() {
        return integrations().flatMap(integration -> Stream.of(Arguments.of(integration, "jsonpath"),
                Arguments.of(integration, "jsonata")));
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void anExecutionRoleAllowedTheActionOnTheResourceReachesTheService(String integration) throws Exception {
        Target target = target(integration);

        Response completed = terminal(start(machine(jsonPathTask(target, null), role(STATES_TRUST, target.grant())), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
    }

    @ParameterizedTest
    @MethodSource("integrationsInBothLanguages")
    void anExecutionRoleNotAllowedTheActionIsRefused(String integration, String form) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = "jsonata".equals(form) ? jsonataTask(target, null) : jsonPathTask(target, null);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, target.error());
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains(target.action()), cause);
        assertTrue(cause.contains(target.resourceArn()), cause);
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void theActionOnAnotherResourceDoesNotCount(String integration) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, allow(target.resourceArn() + "-elsewhere", target.action()));

        assertFailed(terminal(start(machine(jsonPathTask(target, null), role), "{}")), target.error());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void anExecutionRoleThatDoesNotTrustStepFunctionsIsRefusedWhateverItsPolicies(String integration) throws Exception {
        Target target = target(integration);
        String role = role(LAMBDA_TRUST, target.grant());

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void anExecutionRoleThatDoesNotExistIsRefused(String integration) throws Exception {
        Target target = target(integration);
        String role = "arn:aws:iam::" + ACCOUNT + ":role/no-such-role-" + UUID.randomUUID();

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrationsInBothLanguages")
    void credentialsReachTheServiceAsTheirRoleWithoutTheExecutionRolesPermission(String integration, String form)
            throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, ASSUME);
        String taskRole = role(trusting(executionRole), target.grant());
        Map<String, Object> task = "jsonata".equals(form) ? jsonataTask(target, "{% $states.input.role %}")
                : jsonPathTask(target, taskRole);

        String execution = start(machine(task, executionRole), MAPPER.writeValueAsString(Map.of("role", taskRole)));
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
        assertEquals(taskRole, eventsOfType(execution, "TaskScheduled").getFirst()
                .path("taskScheduledEventDetails").path("taskCredentials").path("roleArn").asText());
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void aRoleTheExecutionRoleCannotAssumeFailsInTheNestedForm(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, target.grant());
        String taskRole = role(STATES_TRUST, target.grant());

        Response completed = terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}"));

        assertFailed(completed, "States.TaskFailed");
        assertTrue(completed.jsonPath().getString("cause").contains("is not authorized to assume"), completed.asString());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void aCredentialsRoleNotAllowedTheActionIsRefusedEvenIfTheExecutionRoleIs(String integration) throws Exception {
        Target target = target(integration);
        String executionRole = role(STATES_TRUST, allowAll());
        String taskRole = role(trusting(executionRole), READ_ONLY);

        assertFailed(terminal(start(machine(jsonPathTask(target, taskRole), executionRole), "{}")), target.error());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void waitForTaskTokenRefusedDoesNotWaitAndReachesNothing(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = new LinkedHashMap<>(jsonPathTask(target, null));
        task.put("Resource", "arn:aws:states:::" + integration + ".waitForTaskToken");
        task.put("TimeoutSeconds", 60);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), target.error());
        target.notReached().check();
    }

    @ParameterizedTest
    @MethodSource("integrations")
    void waitForTaskTokenAllowedReachesTheServiceAndWaits(String integration) throws Exception {
        Target target = target(integration);
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + integration + ".waitForTaskToken",
                "Parameters", target.tokenArguments(), "TimeoutSeconds", 60, "End", true));

        String execution = start(machine(task, role(STATES_TRUST, target.grant())), "{}");
        String token = target.token().read();
        request("SendTaskSuccess", Map.of("taskToken", token, "output", "{\"done\":true}")).then().statusCode(200);
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @ParameterizedTest
    @MethodSource("integrations")
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

    /** Each optimized API needs its own action: the same role with every other one is refused. */
    @ParameterizedTest
    @CsvSource({"getItem,GetItem", "deleteItem,DeleteItem", "updateItem,UpdateItem", "scan,Scan"})
    void anOptimizedOperationNeedsItsOwnAction(String api, String operation) throws Exception {
        String table = table();
        Map<String, Object> arguments = "scan".equals(api) ? Map.of("TableName", table)
                : Map.of("TableName", table, "Key", Map.of("pk", Map.of("S", "a")));
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::dynamodb:" + api,
                "Parameters", arguments, "End", true);

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:" + operation))), "{}"));
        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:PutItem"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertFailed(refused, "DynamoDB.AccessDeniedException");
    }

    @Test
    void anOptimizedApiFlociDoesNotHaveStaysAnUnsupportedResource() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::dynamodb:query",
                "Parameters", Map.of("TableName", "any"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "States.TaskFailed");
    }

    @ParameterizedTest
    @CsvSource({"getItem,GetItem", "deleteItem,DeleteItem", "updateItem,UpdateItem", "scan,Scan", "query,Query"})
    void anSdkOperationNeedsItsOwnAction(String api, String operation) throws Exception {
        String table = table();
        Map<String, Object> arguments = switch (api) {
            case "scan" -> Map.of("TableName", table);
            case "query" -> Map.of("TableName", table, "KeyConditionExpression", "pk = :k",
                    "ExpressionAttributeValues", Map.of(":k", Map.of("S", "a")));
            default -> Map.of("TableName", table, "Key", Map.of("pk", Map.of("S", "a")));
        };
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:dynamodb:" + api,
                "Parameters", arguments, "End", true);

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:" + operation))), "{}"));
        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:PutItem"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertFailed(refused, "DynamoDb.AccessDeniedException");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBatchWriteOnTwoTablesIsRefusedWhenOneIsDeniedAndWritesNothing(boolean firstDenied) throws Exception {
        String first = table();
        String second = table();
        Map<String, Object> task = sdkTask("batchWriteItem", Map.of("RequestItems", Map.of(
                first, List.of(Map.of("PutRequest", Map.of("Item", Map.of("pk", Map.of("S", "batch"))))),
                second, List.of(Map.of("PutRequest", Map.of("Item", Map.of("pk", Map.of("S", "batch"))))))));
        String allowedTable = firstDenied ? second : first;

        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow(tableArn(allowedTable), "dynamodb:BatchWriteItem"))), "{}"));

        assertFailed(refused, "DynamoDb.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(tableArn(firstDenied ? first : second)), refused.asString());
        assertFalse(hasItem(first, "batch"));
        assertFalse(hasItem(second, "batch"));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allowEach(List.of(tableArn(first), tableArn(second)), "dynamodb:BatchWriteItem"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertTrue(hasItem(first, "batch"));
        assertTrue(hasItem(second, "batch"));
    }

    @Test
    void aBatchGetNeedsItsActionOnEachTable() throws Exception {
        String first = table();
        String second = table();
        Map<String, Object> keys = Map.of("Keys", List.of(Map.of("pk", Map.of("S", "a"))));
        Map<String, Object> task = sdkTask("batchGetItem", Map.of("RequestItems", Map.of(first, keys, second, keys)));

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(first), "dynamodb:BatchGetItem"))), "{}")),
                "DynamoDb.AccessDeniedException");
        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allowEach(List.of(tableArn(first), tableArn(second)), "dynamodb:BatchGetItem"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** Authorized as each item's own action, never as the transaction's. */
    @Test
    void aTransactWriteNeedsEachItemsActionOnItsTableAndWritesNothingWhenOneIsDenied() throws Exception {
        String first = table();
        String second = table();
        putItem(second, "gone");
        Map<String, Object> task = sdkTask("transactWriteItems", Map.of("TransactItems", List.of(
                Map.of("Put", Map.of("TableName", first, "Item", Map.of("pk", Map.of("S", "written")))),
                Map.of("Delete", Map.of("TableName", second, "Key", Map.of("pk", Map.of("S", "gone")))))));

        Response refused = terminal(start(machine(task, role(STATES_TRUST, allowEachAction(Map.of(
                "dynamodb:PutItem", tableArn(first), "dynamodb:DeleteItem", tableArn(first))))), "{}"));
        assertFailed(refused, "DynamoDb.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains("dynamodb:DeleteItem"), refused.asString());
        assertTrue(refused.jsonPath().getString("cause").contains(tableArn(second)), refused.asString());
        assertFalse(hasItem(first, "written"));
        assertTrue(hasItem(second, "gone"));

        Response transactVerbOnly = terminal(start(machine(task, role(STATES_TRUST,
                allow("*", "dynamodb:TransactWriteItems"))), "{}"));
        assertFailed(transactVerbOnly, "DynamoDb.AccessDeniedException");

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allowEachAction(Map.of(
                "dynamodb:PutItem", tableArn(first), "dynamodb:DeleteItem", tableArn(second))))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertTrue(hasItem(first, "written"));
        assertFalse(hasItem(second, "gone"));
    }

    @Test
    void aTransactGetNeedsGetItemOnEachTable() throws Exception {
        String first = table();
        String second = table();
        Map<String, Object> key = Map.of("pk", Map.of("S", "a"));
        Map<String, Object> task = sdkTask("transactGetItems", Map.of("TransactItems", List.of(
                Map.of("Get", Map.of("TableName", first, "Key", key)),
                Map.of("Get", Map.of("TableName", second, "Key", key)))));

        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(first), "dynamodb:GetItem"))), "{}"));
        assertFailed(refused, "DynamoDb.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(tableArn(second)), refused.asString());

        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allowEach(List.of(tableArn(first), tableArn(second)), "dynamodb:GetItem"))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** {@code dynamodb:EnclosingOperation} reaches the policy, as it does for a direct transaction. */
    @Test
    void aTransactionsEnclosingOperationReachesThePolicy() throws Exception {
        String table = table();
        Map<String, Object> task = sdkTask("transactWriteItems", Map.of("TransactItems", List.of(
                Map.of("Put", Map.of("TableName", table, "Item", Map.of("pk", Map.of("S", "conditioned")))))));
        String policy = MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                Map.of("Effect", "Allow", "Action", "dynamodb:PutItem", "Resource", tableArn(table), "Condition",
                        Map.of("StringEquals", Map.of("dynamodb:EnclosingOperation", "TransactWriteItems"))))));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, policy)), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    @Test
    void listTablesNeedsItsRegionalCollection() throws Exception {
        String collection = "arn:aws:dynamodb:" + REGION + ":" + ACCOUNT + ":table/*";
        Map<String, Object> task = sdkTask("listTables", Map.of());

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(collection, "dynamodb:ListTables"))), "{}"));
        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow("arn:aws:dynamodb:" + REGION + ":" + ACCOUNT + ":table/one", "dynamodb:ListTables"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertFailed(refused, "DynamoDb.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains(collection), refused.asString());
    }

    @Test
    void aStatementIsAuthorizedByWhatItDoes() throws Exception {
        String table = table();
        Map<String, Object> task = sdkTask("executeStatement", Map.of("Statement", "SELECT * FROM \"" + table + "\""));

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:PartiQLSelect"))), "{}"));
        Response refused = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:ExecuteStatement"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertFailed(refused, "DynamoDb.AccessDeniedException");
    }

    @ParameterizedTest
    @CsvSource({"dynamodb:putItem,DynamoDB.", "aws-sdk:dynamodb:putItem,DynamoDb."})
    void aTableNamedByItsArnNeedsThePermissionOnThatArn(String integration, String prefix) throws Exception {
        String table = table();
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Parameters", Map.of("TableName", tableArn(table), "Item", Map.of("pk", Map.of("S", "by-arn"))), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table) + "x", "dynamodb:PutItem"))), "{}")),
                prefix + "AccessDeniedException");
        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allow(tableArn(table), "dynamodb:PutItem"))), "{}"));

        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** No table policy can grant it, so the role's own policy on that ARN is not enough. */
    @Test
    void aTableArnOfAnotherAccountIsRefused() throws Exception {
        String arn = "arn:aws:dynamodb:" + REGION + ":" + OTHER_ACCOUNT + ":table/" + table();
        Map<String, Object> task = sdkTask("putItem", Map.of("TableName", arn, "Item", Map.of("pk", Map.of("S", "a"))));

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow(arn, "dynamodb:PutItem"))), "{}")),
                "DynamoDb.AccessDeniedException");
    }

    @Test
    void anOptimizedRequestNamingNoTableIsAnsweredByDynamoDb() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::dynamodb:putItem",
                "Parameters", Map.of("Item", Map.of("pk", Map.of("S", "a"))), "End", true);

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertFalse(completed.jsonPath().getString("error").endsWith("AccessDeniedException"), completed.asString());
    }

    @Test
    void aTransactionWithNoItemsIsAnsweredByDynamoDb() throws Exception {
        Map<String, Object> task = sdkTask("transactWriteItems", Map.of("TransactItems", "not-a-list"));

        Response completed = terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertFalse(completed.jsonPath().getString("error").endsWith("AccessDeniedException"), completed.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3:putObject", "aws-sdk:s3:putObject"})
    void aRequestNamingNoBucketIsAnsweredByTheIntegration(String integration) throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::" + integration,
                "Parameters", Map.of("Key", "a"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "S3.InvalidRequest");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3:putObject", "aws-sdk:s3:putObject"})
    void aBucketPolicyGrantsARoleWithNoIdentityPermission(String integration) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, READ_ONLY);
        setBucketPolicy(target.bucket(), bucketStatement("Allow", role, target.bucket()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        target.reached().check();
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3:putObject", "aws-sdk:s3:putObject"})
    void aBucketPolicyDenyRefusesARoleItsIdentityPolicyAllows(String integration) throws Exception {
        Target target = target(integration);
        String role = role(STATES_TRUST, target.grant());
        setBucketPolicy(target.bucket(), bucketStatement("Deny", role, target.bucket()));

        Response completed = terminal(start(machine(jsonPathTask(target, null), role), "{}"));

        assertFailed(completed, "S3.AccessDeniedException");
        assertTrue(completed.jsonPath().getString("cause").contains("resource-based policy"), completed.asString());
        target.notReached().check();
    }

    /**
     * Buckets are per account unless the global namespace is on, so another account's is not one the task reaches.
     * With it on, {@code LambdaTaskAuthorizerTest} pins that the bucket's owner makes the call a cross-account one.
     */
    @Test
    void aBucketOfAnotherAccountIsAnsweredByS3OnceTheRoleIsAllowed() throws Exception {
        String bucket = bucket(OTHER_ACCOUNT, REGION);
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket", bucket, "Key", "a", "Body", "hello"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "S3.AccessDeniedException");
        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/a", "s3:PutObject"))), "{}")),
                "S3.NoSuchBucket");
    }

    /** S3's one namespace reaches a bucket of another partition, whose ARN is the one a direct request is checked on. */
    @Test
    void aBucketInAnotherPartitionIsEvaluatedInItsOwn() throws Exception {
        String bucket = bucket(ACCOUNT, "cn-north-1");
        String key = "partitioned";
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket", bucket, "Key", key, "Body", "hello"), "End", true);

        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow("arn:aws:s3:::" + bucket + "/" + key, "s3:PutObject"))), "{}"));
        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allow("arn:aws-cn:s3:::" + bucket + "/" + key, "s3:PutObject"))), "{}"));

        assertFailed(refused, "S3.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains("arn:aws-cn:s3:::" + bucket), refused.asString());
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    @Test
    void aMissingBucketIsAnsweredByS3OnceTheRoleIsAllowed() throws Exception {
        String bucket = "sfn-iam-missing-" + UUID.randomUUID();
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket", bucket, "Key", "a"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/a", "s3:PutObject"))), "{}")),
                "S3.NoSuchBucket");
    }

    /** The secret is the caller's to read, as AWS's own {@code AmazonRDSDataFullAccess} policy has it. */
    @Test
    void anRdsDataStatementNeedsTheSecretToo() throws Exception {
        Target target = target("aws-sdk:rdsdata:executeStatement");

        Response refused = terminal(start(machine(jsonPathTask(target, null), role(STATES_TRUST,
                allow(CLUSTER, "rds-data:ExecuteStatement"))), "{}"));

        assertFailed(refused, "RdsData.AccessDeniedException");
        String cause = refused.jsonPath().getString("cause");
        assertTrue(cause.contains("secretsmanager:GetSecretValue"), cause);
        assertTrue(cause.contains(SECRET), cause);
        target.notReached().check();
    }

    @ParameterizedTest
    @CsvSource({"resourceArn,secretArn", "ResourceArn,SecretArn"})
    void anRdsDataRequestIsReadInEitherCasing(String resourceMember, String secretMember) throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:rdsdata:executeStatement",
                "Parameters", Map.of(resourceMember, CLUSTER, secretMember, SECRET, "Sql", "select 1"), "End", true);

        assertFailed(terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}")), "RdsData.AccessDeniedException");
    }

    @Test
    void anRdsDataRequestNamingNoClusterIsAnsweredByTheService() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:rdsdata:executeStatement",
                "Parameters", Map.of("ResourceArn", "not-an-arn", "SecretArn", SECRET, "Sql", "select 1"), "End", true);

        terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        verify(rdsDataService, timeout(5000)).executeStatement(any(JsonNode.class), anyString());
    }

    /** The Data API's statement operations act on no resource in the service reference. */
    @Test
    void aRedshiftDataStatementOperationIsCheckedOnAnyResource() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:redshiftdata:describeStatement",
                "Parameters", Map.of("Id", "statement"), "End", true);

        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow(REDSHIFT_CLUSTER, "redshift-data:DescribeStatement"))), "{}"));
        Response allowed = terminal(start(machine(task, role(STATES_TRUST,
                allow("*", "redshift-data:DescribeStatement"))), "{}"));

        assertFailed(refused, "RedshiftData.AccessDeniedException");
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** The credentials a cluster call authenticates with are the role's to get, as an EventBridge target's are. */
    @Test
    void aRedshiftDataCallAsADatabaseUserNeedsItsClusterCredentials() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:redshiftdata:executeStatement",
                "Parameters", Map.of("ClusterIdentifier", "orders", "Database", "dev", "DbUser", "admin", "Sql", "select 1"),
                "End", true);

        Response refused = terminal(start(machine(task, role(STATES_TRUST,
                allow(REDSHIFT_CLUSTER, "redshift-data:ExecuteStatement"))), "{}"));

        assertFailed(refused, "RedshiftData.AccessDeniedException");
        assertTrue(refused.jsonPath().getString("cause").contains("redshift:GetClusterCredentials"), refused.asString());
        verify(redshiftDataService, never()).executeStatement(any(JsonNode.class), anyString());

        Response allowed = terminal(start(machine(task, role(STATES_TRUST, allowEachAction(Map.of(
                "redshift-data:ExecuteStatement", REDSHIFT_CLUSTER, "redshift:GetClusterCredentials", "*")))), "{}"));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    @Test
    void aRedshiftDataRequestNamingNoTargetIsAnsweredByTheService() throws Exception {
        Map<String, Object> task = Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:redshiftdata:executeStatement",
                "Parameters", Map.of("Sql", "select 1"), "End", true);

        terminal(start(machine(task, role(STATES_TRUST, READ_ONLY)), "{}"));

        verify(redshiftDataService, timeout(5000)).executeStatement(any(JsonNode.class), anyString());
    }

    private static String allow(String resource, String... actions) {
        return allowEach(List.of(resource), actions);
    }

    private static String allowEach(List<String> resources, String... actions) {
        return policy(List.of(Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", resources)));
    }

    private static String allowEachAction(Map<String, String> resourceOfAction) {
        List<Map<String, Object>> statements = new ArrayList<>();
        resourceOfAction.forEach((action, resource) -> statements.add(
                Map.of("Effect", "Allow", "Action", action, "Resource", resource)));
        return policy(statements);
    }

    private static String allowAll() {
        return allow("*", "dynamodb:*", "s3:*", "rds-data:*", "redshift-data:*", "secretsmanager:*", "sts:AssumeRole");
    }

    private static String policy(List<Map<String, Object>> statements) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private static String bucketStatement(String effect, String principal, String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(effect, principal, bucket);
    }

    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    @FunctionalInterface
    private interface Check {
        void check() throws Exception;
    }

    @FunctionalInterface
    private interface TokenRead {
        String read() throws Exception;
    }

    /**
     * What a task acts on and how to observe it.
     *
     * @param grant the policy that allows everything the call needs
     */
    private record Target(String integration, String error, String action, String resourceArn, String grant,
                          Map<String, Object> arguments, Map<String, Object> tokenArguments, Check reached,
                          Check notReached, TokenRead token, String bucket) {
    }

    private Target target(String integration) {
        return switch (integration) {
            case "dynamodb:putItem" -> tableTarget(integration, "DynamoDB.");
            case "aws-sdk:dynamodb:putItem" -> tableTarget(integration, "DynamoDb.");
            case "s3:putObject", "aws-sdk:s3:putObject" -> objectTarget(integration);
            case "aws-sdk:rdsdata:executeStatement" -> rdsDataTarget(integration);
            case "aws-sdk:redshiftdata:executeStatement" -> redshiftDataTarget(integration);
            default -> throw new IllegalArgumentException(integration);
        };
    }

    private Target tableTarget(String integration, String prefix) {
        String table = table();
        return new Target(integration, prefix + "AccessDeniedException", "dynamodb:PutItem", tableArn(table),
                allow(tableArn(table), "dynamodb:PutItem"),
                Map.of("TableName", table, "Item", Map.of("pk", Map.of("S", "hello"))),
                Map.of("TableName", table, "Item", Map.of("pk", Map.of("S.$", "$$.Task.Token"))),
                () -> assertTrue(hasItem(table, "hello")),
                () -> assertFalse(hasItem(table, "hello")),
                () -> awaitScannedKey(table), null);
    }

    private Target objectTarget(String integration) {
        String bucket = bucket(ACCOUNT, REGION);
        String key = "orders/a+b:c.txt";
        String objectArn = "arn:aws:s3:::" + bucket + "/" + key;
        return new Target(integration, "S3.AccessDeniedException", "s3:PutObject", objectArn,
                allow(objectArn, "s3:PutObject"),
                Map.of("Bucket", bucket, "Key", key, "Body", "hello"),
                Map.of("Bucket", bucket, "Key", key, "Body.$", "$$.Task.Token"),
                () -> assertEquals("hello", object(bucket, key).asString()),
                () -> assertEquals(404, object(bucket, key).statusCode()),
                () -> awaitObject(bucket, key), bucket);
    }

    private Target rdsDataTarget(String integration) {
        return new Target(integration, "RdsData.AccessDeniedException", "rds-data:ExecuteStatement", CLUSTER,
                allowEachAction(Map.of("rds-data:ExecuteStatement", CLUSTER, "secretsmanager:GetSecretValue", SECRET)),
                Map.of("ResourceArn", CLUSTER, "SecretArn", SECRET, "Sql", "select 1"),
                Map.of("ResourceArn", CLUSTER, "SecretArn", SECRET, "Sql.$", "$$.Task.Token"),
                () -> verify(rdsDataService, timeout(5000)).executeStatement(any(JsonNode.class), anyString()),
                () -> verify(rdsDataService, never()).executeStatement(any(JsonNode.class), anyString()),
                () -> {
                    ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
                    verify(rdsDataService, timeout(5000)).executeStatement(sent.capture(), anyString());
                    return sent.getValue().path("sql").asText();
                }, null);
    }

    private Target redshiftDataTarget(String integration) {
        return new Target(integration, "RedshiftData.AccessDeniedException", "redshift-data:ExecuteStatement",
                REDSHIFT_CLUSTER, allowEachAction(Map.of("redshift-data:ExecuteStatement", REDSHIFT_CLUSTER,
                        "secretsmanager:GetSecretValue", SECRET)),
                Map.of("ClusterIdentifier", "orders", "SecretArn", SECRET, "Database", "dev", "Sql", "select 1"),
                Map.of("ClusterIdentifier", "orders", "SecretArn", SECRET, "Database", "dev", "Sql.$", "$$.Task.Token"),
                () -> verify(redshiftDataService, timeout(5000)).executeStatement(any(JsonNode.class), anyString()),
                () -> verify(redshiftDataService, never()).executeStatement(any(JsonNode.class), anyString()),
                () -> {
                    ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
                    verify(redshiftDataService, timeout(5000)).executeStatement(sent.capture(), anyString());
                    return sent.getValue().path("Sql").asText();
                }, null);
    }

    private String table() {
        String name = "sfn-iam-" + UUID.randomUUID();
        dynamoDb("CreateTable", Map.of("TableName", name, "BillingMode", "PAY_PER_REQUEST",
                "AttributeDefinitions", List.of(Map.of("AttributeName", "pk", "AttributeType", "S")),
                "KeySchema", List.of(Map.of("AttributeName", "pk", "KeyType", "HASH"))));
        cleanup.register(() -> dynamoDb("DeleteTable", Map.of("TableName", name)));
        return name;
    }

    private static String tableArn(String table) {
        return "arn:aws:dynamodb:" + REGION + ":" + ACCOUNT + ":table/" + table;
    }

    private void putItem(String table, String key) {
        dynamoDb("PutItem", Map.of("TableName", table, "Item", Map.of("pk", Map.of("S", key))));
    }

    private boolean hasItem(String table, String key) throws Exception {
        Response found = dynamoDb("GetItem", Map.of("TableName", table, "Key", Map.of("pk", Map.of("S", key))));
        return MAPPER.readTree(found.asString()).has("Item");
    }

    private String awaitScannedKey(String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            JsonNode items = MAPPER.readTree(dynamoDb("Scan", Map.of("TableName", table)).asString()).path("Items");
            if (!items.isEmpty()) {
                return items.get(0).path("pk").path("S").asText();
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Nothing reached " + table);
        return null;
    }

    private Response dynamoDb(String action, Map<String, ?> input) {
        Response response = sfn().json(ACCOUNT, "dynamodb", "DynamoDB_20120810." + action, "application/x-amz-json-1.0", input);
        response.then().statusCode(200);
        return response;
    }

    private String bucket(String account, String region) {
        String name = "sfn-iam-" + UUID.randomUUID();
        String authorization = auth(account, region, "s3");
        given().port(baseUri.getPort()).header("Authorization", authorization)
                .body(REGION.equals(region) ? "" : "<CreateBucketConfiguration><LocationConstraint>" + region
                        + "</LocationConstraint></CreateBucketConfiguration>")
                .put("/" + name).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), authorization, name));
        return name;
    }

    private void setBucketPolicy(String bucket, String policy) {
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3")).body(policy)
                .queryParam("policy", "").put("/" + bucket).then().statusCode(200);
    }

    private Response object(String bucket, String key) {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3"))
                .urlEncodingEnabled(false).get("/" + bucket + "/" + key.replace("+", "%2B").replace(":", "%3A"));
    }

    private String awaitObject(String bucket, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Response found = object(bucket, key);
            if (found.statusCode() == 200) {
                return found.asString();
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Nothing reached " + bucket + "/" + key);
        return null;
    }

    private static Map<String, Object> sdkTask(String api, Map<String, ?> arguments) {
        return Map.of("Type", "Task", "Resource", "arn:aws:states:::aws-sdk:dynamodb:" + api,
                "Parameters", arguments, "End", true);
    }

    private Map<String, Object> jsonPathTask(Target target, String taskRole) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task",
                "Resource", "arn:aws:states:::" + target.integration(),
                "Parameters", target.arguments(), "End", true));
        if (taskRole != null) {
            task.put("Credentials", Map.of("RoleArn", taskRole));
        }
        return task;
    }

    private Map<String, Object> jsonataTask(Target target, String roleExpression) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("Type", "Task", "QueryLanguage", "JSONata",
                "Resource", "arn:aws:states:::" + target.integration(),
                "Arguments", target.arguments(), "End", true));
        if (roleExpression != null) {
            task.put("Credentials", Map.of("RoleArn", roleExpression));
        }
        return task;
    }

    private String role(String trust, String policy) {
        return sfn().role(ACCOUNT, "sfn-data-", trust, policy);
    }

    private String machine(Map<String, Object> task, String role) throws Exception {
        return sfn().machine(ACCOUNT, "sfn-data-iam-", task, role);
    }

    private String machineWithDefinition(String definition, String role) {
        return sfn().machineWithDefinition(ACCOUNT, "sfn-data-iam-", definition, role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return sfn().terminal(ACCOUNT, execution, 10);
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
