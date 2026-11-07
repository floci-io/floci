package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.cloudformation.CloudFormationQueryHandler;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskPermissionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String TOPIC = "arn:aws:sns:eu-west-1:111122223333:orders";
    private static final String REDSHIFT_CLUSTER = "arn:aws:redshift:eu-west-1:111122223333:cluster:orders";
    private static final String ENDPOINT = "arn:aws:sns:eu-west-1:111122223333:endpoint/GCM/app/id";
    private static final String TASK_DEFINITION = "arn:aws:ecs:eu-west-1:111122223333:task-definition/orders:7";
    private static final String SERVICE = "arn:aws:ecs:eu-west-1:111122223333:service/c/s";
    private static final String EMR_CLUSTER = "arn:aws:elasticmapreduce:eu-west-1:111122223333:cluster/j-1";
    private static final String APPLICATION = "arn:aws:emr-serverless:eu-west-1:111122223333:/applications/a";

    /** Each form resolves in the state machine's Region, whichever host it names, as the queue lookup does. */
    @ParameterizedTest
    @CsvSource({
            "http://localhost:4566/111122223333/orders,                 111122223333",
            "https://sqs.ap-south-1.amazonaws.com/111122223333/orders,  111122223333",
            "http://localhost:4566/sqs/444455556666/orders,             444455556666",
            "orders,                                                    111122223333",
            "444455556666/orders,                                       444455556666",
            "http://localhost:4566/not-an-account/orders,               111122223333"})
    void aQueueUrlNamesTheQueueItsLookupReaches(String queueUrl, String account) {
        assertEquals(new TaskPermissions("SQS.AccessDeniedException", List.of(new Permission("sqs:SendMessage",
                        "arn:aws:sqs:eu-west-1:" + account + ":orders"))),
                TaskPermissions.of(integration("sqs:sendMessage"), input(queueUrl), machine("aws")).orElseThrow());
    }

    @Test
    void theQueueIsInTheStateMachinesPartition() {
        assertEquals(List.of(new Permission("sqs:SendMessage", "arn:aws-cn:sqs:eu-west-1:" + ACCOUNT + ":orders")),
                TaskPermissions.of(integration("sqs:sendMessage"), input("orders"), machine("aws-cn"))
                        .orElseThrow().permissions());
    }

    @Test
    void theSdkFormNamesItsOwnErrorPrefix() {
        assertEquals("Sqs.AccessDeniedException", TaskPermissions.of(integration("aws-sdk:sqs:sendMessage"),
                input("orders"), machine("aws")).orElseThrow().deniedError());
    }

    /** SQS answers these with its own validation error once the role has passed. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"QueueUrl\":\"  \"}", "{\"QueueUrl\":7}"})
    void aRequestNamingNoQueueNeedsNoPermission(String request) throws Exception {
        assertEquals(new TaskPermissions("SQS.AccessDeniedException", List.of()),
                TaskPermissions.of(integration("sqs:sendMessage"), MAPPER.readTree(request), machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lambda:invoke", "apigateway:invoke", "aws-sdk:sqs:receiveMessage", "aws-sdk:sns:listTopics",
            "sns:publishBatch", "aws-sdk:eventbridge:putEvents", "events:listRules", "dynamodb:query"})
    void integrationsNotMappedStayUnchecked(String integration) {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(integration), input("orders"), machine("aws")));
    }

    @ParameterizedTest
    @CsvSource({"sns:publish,SNS.AuthorizationErrorException", "aws-sdk:sns:publish,Sns.AuthorizationErrorException"})
    void aPublishNeedsThePermissionOnItsTopicAndIsRefusedAsSnsRefuses(String integration, String deniedError) throws Exception {
        assertEquals(new TaskPermissions(deniedError, List.of(new Permission("sns:Publish", TOPIC))),
                TaskPermissions.of(integration(integration), json("{\"TopicArn\":\"" + TOPIC + "\"}"), machine("aws"))
                        .orElseThrow());
    }

    @Test
    void aPublishToATargetNeedsThePermissionOnTheTarget() throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", ENDPOINT)), snsPermissions("{\"TargetArn\":\"" + ENDPOINT + "\"}"));
    }

    /** {@code SnsService.publish} takes the topic before the target, so that is what it sends to. */
    @Test
    void aPublishNamingBothNeedsThePermissionOnTheTopic() throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", TOPIC)),
                snsPermissions("{\"TargetArn\":\"" + ENDPOINT + "\",\"TopicArn\":\"" + TOPIC + "\"}"));
        assertEquals(List.of(new Permission("sns:Publish", ENDPOINT)),
                snsPermissions("{\"TopicArn\":null,\"TargetArn\":\"" + ENDPOINT + "\"}"));
    }

    /** {@code SnsService.publish} takes a phone number before any ARN, so a topic in the request is not reached. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"PhoneNumber\":\"+15555550100\"}",
            "{\"PhoneNumber\":\"+15555550100\",\"TopicArn\":\"" + TOPIC + "\"}"})
    void anSmsNeedsThePermissionOnAnyResource(String request) throws Exception {
        assertEquals(List.of(new Permission("sns:Publish", "*")), snsPermissions(request));
    }

    /** SNS answers these with its own error once the role has passed: none of them reaches a topic. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TopicArn\":\"orders\"}", "{\"TopicArn\":\"\"}", "{\"TopicArn\":7}",
            "{\"TopicArn\":\"arn:aws:sns\"}", "{\"TopicArn\":\"arn:aws:sqs:eu-west-1:111122223333:orders\"}",
            "{\"TargetArn\":\"orders\"}", "{\"PhoneNumber\":null}",
            "{\"TopicArn\":\"orders\",\"TargetArn\":\"" + ENDPOINT + "\"}"})
    void aPublishNamingNoSnsResourceNeedsNoPermission(String request) throws Exception {
        assertEquals(List.of(), snsPermissions(request));
    }

    @Test
    void anEntryNamingNoBusNeedsThePermissionOnTheDefaultBus() throws Exception {
        assertEquals(new TaskPermissions("EventBridge.AccessDeniedException", List.of(new Permission("events:PutEvents", bus("default")))),
                TaskPermissions.of(integration("events:putEvents"), json("{\"Entries\":[{\"Source\":\"a\"}]}"),
                        machine("aws")).orElseThrow());
    }

    /** Each form reaches the bus of that name where the call runs, whichever account and Region an ARN names. */
    @ParameterizedTest
    @CsvSource({
            "orders,                                                orders",
            "arn:aws:events:eu-west-1:111122223333:event-bus/orders, orders",
            "arn:aws-cn:events:cn-north-1:444455556666:event-bus/orders, orders",
            "arn:aws:events:eu-west-1:111122223333:event-bus/default, default",
            "'',                                                    default",
            "'  ',                                                  default",
            "arn:aws:s3:::orders,                                   arn:aws:s3:::orders"})
    void aBusNameOrArnNamesTheBusItsLookupReaches(String busName, String reached) throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus(reached))),
                eventsPermissions(MAPPER.writeValueAsString(Map.of("Entries", List.of(Map.of("EventBusName", busName))))));
    }

    @Test
    void aNullOrNonTextBusIsReadAsTheHandlerReadsIt() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("default"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":null},{\"EventBusName\":{\"a\":1}}]}"));
        assertEquals(List.of(new Permission("events:PutEvents", bus("7"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":7}]}"));
    }

    @Test
    void entriesNeedOnePermissionForEachDistinctBusInOrder() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("b")), new Permission("events:PutEvents", bus("default")),
                        new Permission("events:PutEvents", bus("a"))),
                eventsPermissions("{\"Entries\":[{\"EventBusName\":\"b\"},{},{\"EventBusName\":\"a\"},"
                        + "{\"EventBusName\":\"" + bus("b") + "\"},{\"EventBusName\":\"default\"}]}"));
    }

    @Test
    void theBusIsInTheStateMachinesPartition() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents",
                        "arn:aws-cn:events:eu-west-1:" + ACCOUNT + ":event-bus/default")),
                TaskPermissions.of(integration("events:putEvents"), json("{\"Entries\":[{}]}"), machine("aws-cn"))
                        .orElseThrow().permissions());
    }

    /** A malformed member names no bus, so EventBridge answers it, and the others are still checked. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Entries\":[]}", "{\"Entries\":{\"EventBusName\":\"a\"}}", "{\"Entries\":{\"x\":{\"EventBusName\":\"a\"}}}", "{\"Entries\":\"a\"}",
            "{\"Entries\":[7]}", "{\"Entries\":[[{\"EventBusName\":\"a\"}]]}",
            "{\"Entries\":[{\"EventBusName\":\"arn:aws:events:\"}]}",
            "{\"Entries\":[{\"EventBusName\":\"arn:aws:events:eu-west-1:111122223333:rule/orders\"}]}"})
    void entriesNamingNoBusNeedNoPermission(String request) throws Exception {
        assertEquals(List.of(), eventsPermissions(request));
    }

    @Test
    void aMalformedEntryLeavesTheOthersChecked() throws Exception {
        assertEquals(List.of(new Permission("events:PutEvents", bus("a"))),
                eventsPermissions("{\"Entries\":[7,{\"EventBusName\":\"arn:aws:events:\"},{\"EventBusName\":\"a\"}]}"));
    }

    @ParameterizedTest
    @CsvSource({"putItem,PutItem", "getItem,GetItem", "deleteItem,DeleteItem", "updateItem,UpdateItem", "scan,Scan"})
    void anOptimizedDynamoDbTaskNeedsItsActionOnTheTable(String api, String operation) throws Exception {
        assertEquals(TaskPermissions.accessDenied("DynamoDB.", List.of(new Permission("dynamodb:" + operation, table("orders")))),
                TaskPermissions.of(integration("dynamodb:" + api), json("{\"TableName\":\"orders\"}"), machine("aws"))
                        .orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TableName\":\" \"}", "{\"TableName\":{}}"})
    void anOptimizedDynamoDbTaskNamingNoTableNeedsNoPermission(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("DynamoDB.", List.of()),
                TaskPermissions.of(integration("dynamodb:putItem"), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void anOptimizedDynamoDbApiNotServedStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("dynamodb:query"), json("{\"TableName\":\"t\"}"), machine("aws")));
    }

    @Test
    void aTableNamedByItsArnIsEvaluatedAsGiven() throws Exception {
        String arn = "arn:aws:dynamodb:eu-west-1:444455556666:table/orders";
        assertEquals(List.of(new Permission("dynamodb:PutItem", arn)), sdkPermissions("putItem", "{\"TableName\":\"" + arn + "\"}"));
        assertEquals(List.of(new Permission("dynamodb:PutItem", arn)),
                TaskPermissions.of(integration("dynamodb:putItem"), json("{\"TableName\":\"" + arn + "\"}"), machine("aws"))
                        .orElseThrow().permissions());
    }

    @Test
    void anSdkDynamoDbTaskNamesItsOwnPrefix() throws Exception {
        assertEquals("DynamoDb.AccessDeniedException", TaskPermissions.of(integration("aws-sdk:dynamodb:getItem"),
                json("{}"), machine("aws")).orElseThrow().deniedError());
    }

    @Test
    void aBatchNeedsItsActionOnEachTable() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:BatchWriteItem", table("a")),
                new Permission("dynamodb:BatchWriteItem", table("b"))),
                sdkPermissions("batchWriteItem", "{\"RequestItems\":{\"a\":[],\"b\":[]}}"));
        assertEquals(List.of(new Permission("dynamodb:BatchGetItem", table("a")),
                new Permission("dynamodb:BatchGetItem", table("b"))),
                sdkPermissions("batchGetItem", "{\"RequestItems\":{\"a\":{},\"b\":{}}}"));
    }

    @Test
    void aTransactWriteNeedsEachItemsOwnActionOnItsTable() throws Exception {
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of("TransactWriteItems"));
        assertEquals(List.of(new Permission("dynamodb:PutItem", table("aaa"), enclosing),
                new Permission("dynamodb:DeleteItem", table("bbb"), enclosing),
                new Permission("dynamodb:UpdateItem", table("aaa"), enclosing),
                new Permission("dynamodb:ConditionCheckItem", table("ccc"), enclosing)),
                sdkPermissions("transactWriteItems", """
                        {"TransactItems":[{"Put":{"TableName":"aaa"}},{"Delete":{"TableName":"bbb"}},
                         {"Update":{"TableName":"aaa"}},{"ConditionCheck":{"TableName":"ccc"}}]}"""));
    }

    @Test
    void aTransactGetNeedsGetItemOnEachTable() throws Exception {
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of("TransactGetItems"));
        assertEquals(List.of(new Permission("dynamodb:GetItem", table("aaa"), enclosing),
                new Permission("dynamodb:GetItem", table("bbb"), enclosing)),
                sdkPermissions("transactGetItems", "{\"TransactItems\":[{\"Get\":{\"TableName\":\"aaa\"}},{\"Get\":{\"TableName\":\"bbb\"}}]}"));
    }

    /** Execution resolves an ARN within the request's account, so that is the table the item is checked on. */
    @Test
    void aTransactItemNamedByAnArnIsTheTableItsExecutionReaches() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:PutItem", table("aaa"),
                Map.of("dynamodb:EnclosingOperation", List.of("TransactWriteItems")))),
                sdkPermissions("transactWriteItems", """
                        {"TransactItems":[{"Put":{"TableName":"arn:aws:dynamodb:eu-west-1:444455556666:table/aaa"}}]}"""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TransactItems\":\"x\"}", "{\"TransactItems\":[{\"Put\":{}}]}",
            "{\"TransactItems\":[{\"Get\":{\"TableName\":\"aaa\"}}]}",
            "{\"TransactItems\":[{\"Put\":{\"TableName\":\"arn:aws:dynamodb:ap-south-1:444455556666:table/aaa\"}}]}",
            "{\"TransactItems\":[{\"Put\":{\"TableName\":\"a\"}}]}",
            "{\"TransactItems\":[{\"Put\":{\"TableName\":\"arn:aws:s3:::aaa\"}}]}"})
    void aTransactionNamingNoTableItCanReachNeedsNoPermission(String request) throws Exception {
        assertEquals(List.of(), sdkPermissions("transactWriteItems", request));
    }

    @Test
    void listTablesNeedsItsRegionalCollection() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:ListTables", table("*"))), sdkPermissions("listTables", "{}"));
    }

    @Test
    void anOperationNamingNoTableIsCheckedOnAnyResource() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:DescribeLimits", "*")), sdkPermissions("describeLimits", "{}"));
    }

    @Test
    void aStatementIsAuthorizedByWhatItDoes() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:PartiQLSelect", table("a"))),
                sdkPermissions("executeStatement", "{\"Statement\":\"SELECT * FROM \\\"a\\\"\"}"));
        assertEquals(List.of(new Permission("dynamodb:PartiQLInsert", table("a"))),
                sdkPermissions("executeStatement", "{\"Statement\":\"INSERT INTO \\\"a\\\" VALUE {'pk':'x'}\"}"));
    }

    @Test
    void aResourceThatIsNotAnArnNeedsNoPermission() throws Exception {
        assertEquals(List.of(), sdkPermissions("tagResource", "{\"ResourceArn\":\"not-an-arn\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3:putObject", "aws-sdk:s3:putObject"})
    void anS3PutNeedsThePermissionOnTheObjectInTheMachinesPartition(String tail) throws Exception {
        String request = "{\"Bucket\":\"orders\",\"Key\":\"a/b:c+d.txt\"}";
        assertEquals(TaskPermissions.accessDenied("S3.", List.of(new Permission("s3:PutObject", "arn:aws:s3:::orders/a/b:c+d.txt"))),
                TaskPermissions.of(integration(tail), json(request), machine("aws")).orElseThrow());
        assertEquals(List.of(new Permission("s3:PutObject", "arn:aws-cn:s3:::orders/a/b:c+d.txt")),
                TaskPermissions.of(integration(tail), json(request), machine("aws-cn")).orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Bucket\":\"orders\"}", "{\"Key\":\"a\"}", "{\"Bucket\":\"\",\"Key\":\"a\"}",
            "{\"Bucket\":null,\"Key\":\"a\"}", "{\"Bucket\":\"orders\",\"Key\":null}"})
    void anS3PutNamingNoObjectNeedsNoPermission(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("S3.", List.of()),
                TaskPermissions.of(integration("s3:putObject"), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void anS3ApiNotServedStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("s3:getObject"), json("{}"), machine("aws")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ResourceArn\":\"%s\",\"SecretArn\":\"%s\"}", "{\"resourceArn\":\"%s\",\"secretArn\":\"%s\"}"})
    void anRdsDataStatementNeedsItsActionOnTheClusterAndTheSecret(String template) throws Exception {
        String cluster = "arn:aws:rds:eu-west-1:" + ACCOUNT + ":cluster:orders";
        String secret = "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:db";
        assertEquals(TaskPermissions.accessDenied("RdsData.", List.of(new Permission("rds-data:ExecuteStatement", cluster),
                new Permission("secretsmanager:GetSecretValue", secret))),
                TaskPermissions.of(integration("aws-sdk:rdsdata:executeStatement"), json(template.formatted(cluster, secret)),
                        machine("aws")).orElseThrow());
    }

    @Test
    void anRdsDataStatementWithNoSecretArnNeedsOnlyItsAction() throws Exception {
        String cluster = "arn:aws:rds:eu-west-1:" + ACCOUNT + ":cluster:orders";
        assertEquals(List.of(new Permission("rds-data:ExecuteStatement", cluster)),
                TaskPermissions.of(integration("aws-sdk:rdsdata:executeStatement"),
                        json("{\"ResourceArn\":\"" + cluster + "\",\"SecretArn\":\"db\"}"), machine("aws"))
                        .orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ResourceArn\":\"orders\",\"SecretArn\":\"arn:aws:secretsmanager:eu-west-1:1:secret:db\"}"})
    void anRdsDataStatementNamingNoClusterNeedsNoPermission(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("RdsData.", List.of()),
                TaskPermissions.of(integration("aws-sdk:rdsdata:executeStatement"), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void anRdsDataApiNotServedStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("aws-sdk:rdsdata:beginTransaction"), json("{}"), machine("aws")));
    }

    @ParameterizedTest
    @CsvSource({"describeStatement,DescribeStatement", "cancelStatement,CancelStatement",
            "getStatementResult,GetStatementResult", "getStatementResultV2,GetStatementResult",
            "listStatements,ListStatements", "listSessions,ListSessions"})
    void aRedshiftDataOperationOnNoResourceIsCheckedOnAnyResource(String api, String action) throws Exception {
        assertEquals(TaskPermissions.accessDenied("RedshiftData.", List.of(new Permission("redshift-data:" + action, "*"))),
                TaskPermissions.of(integration("aws-sdk:redshiftdata:" + api), json("{\"Id\":\"s\"}"), machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"executeStatement,ExecuteStatement", "batchExecuteStatement,BatchExecuteStatement",
            "listDatabases,ListDatabases", "listSchemas,ListSchemas", "listTables,ListTables", "describeTable,DescribeTable"})
    void aRedshiftDataCallOnAClusterNeedsItsActionAndItsSecret(String api, String operation) throws Exception {
        String secret = "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:db";
        assertEquals(List.of(new Permission("redshift-data:" + operation, REDSHIFT_CLUSTER),
                new Permission("secretsmanager:GetSecretValue", secret)),
                redshiftPermissions(api, "{\"ClusterIdentifier\":\"orders\",\"SecretArn\":\"" + secret + "\"}", null));
    }

    @Test
    void aRedshiftDataCallAsADatabaseUserNeedsItsClusterCredentials() throws Exception {
        String prefix = "arn:aws:redshift:eu-west-1:" + ACCOUNT;
        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", REDSHIFT_CLUSTER),
                new Permission("redshift:GetClusterCredentials", prefix + ":dbuser:orders/admin"),
                new Permission("redshift:GetClusterCredentials", prefix + ":dbname:orders/dev")),
                redshiftPermissions("executeStatement",
                        "{\"ClusterIdentifier\":\"orders\",\"DbUser\":\"admin\",\"Database\":\"dev\"}", null));
    }

    @Test
    void aRedshiftDataCallOnAWorkgroupIsEvaluatedOnTheOneItsNameFinds() throws Exception {
        String workgroup = "arn:aws:redshift-serverless:eu-west-1:" + ACCOUNT + ":workgroup/1234";
        StoredResourceArns stored = mock(StoredResourceArns.class);
        when(stored.findByMembers(eq("workgroup"), eq(List.of("analytics")), eq("eu-west-1"))).thenReturn(Optional.of(workgroup));
        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", workgroup),
                new Permission("redshift-serverless:GetCredentials", workgroup)),
                redshiftPermissions("executeStatement", "{\"WorkgroupName\":\"analytics\"}", stored));
    }

    @Test
    void aRedshiftDataCallOnASessionIsEvaluatedOnItsTarget() throws Exception {
        StoredResourceArns stored = mock(StoredResourceArns.class);
        when(stored.through("SessionId", "session", "eu-west-1")).thenReturn(List.of(REDSHIFT_CLUSTER));
        assertEquals(List.of(new Permission("redshift-data:ExecuteStatement", REDSHIFT_CLUSTER)),
                redshiftPermissions("executeStatement", "{\"SessionId\":\"session\"}", stored));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"executeStatement|{}", "executeStatement|[]", "executeSql|{\"ClusterIdentifier\":\"c\"}",
            "noSuchOperation|{}"})
    void aRedshiftDataCallNamingNothingItNeedsNeedsNoPermission(String api, String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("RedshiftData.", List.of()),
                TaskPermissions.of(integration("aws-sdk:redshiftdata:" + api), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void aRedshiftDataSuffixIsNotServedAndStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("aws-sdk:redshiftdata:executeStatement.sync"), json("{}"), machine("aws")));
    }

    /** Whatever names it, the check is on the revision {@code EcsService} finds, which is the one it would run. */
    @ParameterizedTest
    @ValueSource(strings = {"ecs:runTask", "ecs:runTask.sync"})
    void runTaskIsCheckedOnTheRevisionEcsFinds(String tail) throws Exception {
        EcsService ecs = mock(EcsService.class);
        when(ecs.taskDefinitionArn("orders", "eu-west-1")).thenReturn(Optional.of(TASK_DEFINITION));

        assertEquals(TaskPermissions.accessDenied("ECS.", List.of(new Permission("ecs:RunTask", TASK_DEFINITION))),
                ecsPermissions(tail, "{\"TaskDefinition\":\"orders\"}", ecs));
    }

    /** A form the integration does not serve is left to its unsupported-mode answer, whatever the role. */
    @Test
    void aRunTaskFormEcsDoesNotServeStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("ecs:runTask.sync:2"),
                json("{\"TaskDefinition\":\"orders\"}"), machine("aws"), null, mock(EcsService.class), false));
    }

    /** {@code executeTaskState} strips the suffix, so this is a {@code .waitForTaskToken} as the dispatch sees it. */
    @Test
    void runTaskWithATokenIsChecked() throws Exception {
        EcsService ecs = mock(EcsService.class);
        when(ecs.taskDefinitionArn("orders", "eu-west-1")).thenReturn(Optional.of(TASK_DEFINITION));

        assertEquals(List.of(new Permission("ecs:RunTask", TASK_DEFINITION)),
                TaskPermissions.of(integration("ecs:runTask"), json("{\"TaskDefinition\":\"orders\"}"), machine("aws"),
                        null, ecs, true).orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TaskDefinition\":null}", "{\"TaskDefinition\":\"no-such\"}"})
    void aTaskDefinitionEcsDoesNotFindNeedsNoPermission(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("ECS.", List.of()),
                ecsPermissions("ecs:runTask", request, mock(EcsService.class)));
    }

    @Test
    void runTaskWithoutEcsNamesNothing() throws Exception {
        assertEquals(TaskPermissions.accessDenied("ECS.", List.of()), TaskPermissions.of(integration("ecs:runTask"),
                json("{\"TaskDefinition\":\"orders\"}"), machine("aws")).orElseThrow());
    }

    /** The members are read in either casing, as the SDK form recases them for the handler. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"Cluster\":\"c\",\"Service\":\"s\"}", "{\"cluster\":\"c\",\"service\":\"s\"}"})
    void updateServiceIsCheckedOnTheServiceEcsFinds(String request) throws Exception {
        EcsService ecs = mock(EcsService.class);
        when(ecs.updatedServiceArn("c", "s", "eu-west-1")).thenReturn(Optional.of(SERVICE));

        assertEquals(TaskPermissions.accessDenied("Ecs.", List.of(new Permission("ecs:UpdateService", SERVICE))),
                ecsPermissions("aws-sdk:ecs:updateService", request, ecs));
    }

    @Test
    void updateServiceWithNoClusterAsksForTheDefault() throws Exception {
        EcsService ecs = mock(EcsService.class);
        when(ecs.updatedServiceArn(null, "s", "eu-west-1")).thenReturn(Optional.of(SERVICE));

        assertEquals(List.of(new Permission("ecs:UpdateService", SERVICE)),
                ecsPermissions("aws-sdk:ecs:updateService", "{\"Service\":\"s\"}", ecs).permissions());
    }

    @Test
    void describeServicesIsCheckedOnEachDistinctServiceEcsFinds() throws Exception {
        EcsService ecs = mock(EcsService.class);
        when(ecs.describedServiceArn("c", "a", "eu-west-1")).thenReturn(Optional.of(SERVICE));
        when(ecs.describedServiceArn("c", SERVICE, "eu-west-1")).thenReturn(Optional.of(SERVICE));
        when(ecs.describedServiceArn("c", "b", "eu-west-1")).thenReturn(Optional.of(SERVICE + "b"));

        assertEquals(List.of(new Permission("ecs:DescribeServices", SERVICE),
                        new Permission("ecs:DescribeServices", SERVICE + "b")),
                ecsPermissions("aws-sdk:ecs:describeServices",
                        "{\"Cluster\":\"c\",\"Services\":[\"a\",\"" + SERVICE + "\",\"missing\",\"b\"]}", ecs)
                        .permissions());
    }

    @Test
    void listServicesActsOnNoResource() throws Exception {
        assertEquals(TaskPermissions.accessDenied("Ecs.", List.of(new Permission("ecs:ListServices", "*"))),
                TaskPermissions.of(integration("aws-sdk:ecs:listServices"), json("{}"), machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:ecs:runTask", "aws-sdk:ecs:createService", "aws-sdk:ecs:updateService.sync",
            "ecs:updateService", "elasticmapreduce:listClusters", "elasticmapreduce:createCluster.waitForTaskToken",
            "elasticmapreduce:cancelStep.sync", "elasticmapreduce:addStep.sync:2", "aws-sdk:elasticmapreduce:addStep",
            "emr-serverless:listApplications", "emr-serverless:startJobRun.sync:2",
            "emr-serverless:startJobRun.waitForTaskToken", "aws-sdk:emrserverless:startJobRun"})
    void aJobApiNotServedStaysUnchecked(String tail) throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(tail),
                json("{\"ClusterId\":\"j-1\",\"ApplicationId\":\"a\"}"), machine("aws")));
    }

    /** {@code executeTaskState} strips the suffix: the integration alone no longer shows these have no such form. */
    @ParameterizedTest
    @ValueSource(strings = {"elasticmapreduce:addStep", "emr-serverless:startJobRun"})
    void anEmrApiWithATokenStaysUnchecked(String tail) throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(tail),
                json("{\"ClusterId\":\"j-1\",\"ApplicationId\":\"a\"}"), machine("aws"), null, null, true));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "setClusterTerminationProtection|SetTerminationProtection",
            "terminateCluster|TerminateJobFlows",
            "terminateCluster.sync|TerminateJobFlows",
            "addStep|AddJobFlowSteps",
            "addStep.sync|AddJobFlowSteps",
            "cancelStep|CancelSteps"})
    void anEmrApiNeedsItsActionOnTheCluster(String api, String action) throws Exception {
        assertEquals(TaskPermissions.accessDenied("EMR.", List.of(new Permission("elasticmapreduce:" + action, EMR_CLUSTER))),
                TaskPermissions.of(integration("elasticmapreduce:" + api), json("{\"ClusterId\":\"j-1\"}"),
                        machine("aws")).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"modifyInstanceFleetByName|ListInstanceFleets|ModifyInstanceFleet",
            "modifyInstanceGroupByName|ListInstanceGroups|ModifyInstanceGroups"})
    void aByNameApiNeedsTheListAndTheModify(String api, String list, String modify) throws Exception {
        assertEquals(List.of(new Permission("elasticmapreduce:" + list, EMR_CLUSTER),
                        new Permission("elasticmapreduce:" + modify, EMR_CLUSTER)),
                TaskPermissions.of(integration("elasticmapreduce:" + api), json("{\"ClusterId\":\"j-1\"}"),
                        machine("aws")).orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"elasticmapreduce:createCluster", "elasticmapreduce:createCluster.sync"})
    void createClusterActsOnNoResource(String tail) throws Exception {
        assertEquals(List.of(new Permission("elasticmapreduce:RunJobFlow", "*")),
                TaskPermissions.of(integration(tail), json("{}"), machine("aws")).orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ClusterId\":\"  \"}", "{\"ClusterId\":7}", "{\"clusterId\":\"j-1\"}"})
    void anEmrRequestNamingNoClusterNeedsNoPermission(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("EMR.", List.of()),
                TaskPermissions.of(integration("elasticmapreduce:addStep"), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void theClusterIsInTheStateMachinesPartition() throws Exception {
        assertEquals(List.of(new Permission("elasticmapreduce:AddJobFlowSteps",
                        "arn:aws-cn:elasticmapreduce:eu-west-1:" + ACCOUNT + ":cluster/j-1")),
                TaskPermissions.of(integration("elasticmapreduce:addStep"), json("{\"ClusterId\":\"j-1\"}"),
                        machine("aws-cn")).orElseThrow().permissions());
    }

    @ParameterizedTest
    @CsvSource({"startApplication,StartApplication", "startApplication.sync,StartApplication",
            "stopApplication,StopApplication", "deleteApplication,DeleteApplication", "startJobRun,StartJobRun",
            "startJobRun.sync,StartJobRun"})
    void anEmrServerlessApiNeedsItsActionOnTheApplication(String api, String action) throws Exception {
        assertEquals(TaskPermissions.accessDenied("EmrServerless.", List.of(new Permission("emr-serverless:" + action, APPLICATION))),
                TaskPermissions.of(integration("emr-serverless:" + api), json("{\"ApplicationId\":\"a\"}"),
                        machine("aws")).orElseThrow());
    }

    @Test
    void anEmrServerlessIdIsReadInEitherCase() throws Exception {
        assertEquals(List.of(new Permission("emr-serverless:CancelJobRun", APPLICATION + "/jobruns/r")),
                TaskPermissions.of(integration("emr-serverless:cancelJobRun"),
                        json("{\"applicationId\":\"a\",\"jobRunId\":\"r\"}"), machine("aws")).orElseThrow().permissions());
    }

    @Test
    void cancelJobRunIsCheckedOnTheRun() throws Exception {
        assertEquals(List.of(new Permission("emr-serverless:CancelJobRun", APPLICATION + "/jobruns/r")),
                TaskPermissions.of(integration("emr-serverless:cancelJobRun.sync"),
                        json("{\"ApplicationId\":\"a\",\"JobRunId\":\"r\"}"), machine("aws")).orElseThrow().permissions());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"startJobRun|{}", "startJobRun|{\"ApplicationId\":7}",
            "startApplication|{\"ApplicationId\":\" \"}", "cancelJobRun|{\"ApplicationId\":\"a\"}",
            "cancelJobRun|{\"JobRunId\":\"r\"}"})
    void anEmrServerlessRequestMissingAnIdNeedsNoPermission(String api, String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("EmrServerless.", List.of()),
                TaskPermissions.of(integration("emr-serverless:" + api), json(request), machine("aws")).orElseThrow());
    }

    @Test
    void createApplicationActsOnNoResource() throws Exception {
        assertEquals(List.of(new Permission("emr-serverless:CreateApplication", "*")),
                TaskPermissions.of(integration("emr-serverless:createApplication"), json("{}"), machine("aws"))
                        .orElseThrow().permissions());
    }

    private static final String STACK = "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/s/id";

    private static TaskPermissions control(String tail, String request, CloudFormationQueryHandler cloudFormation,
                                           SdkManagementTaskInvoker sdkManagement, StoredResourceArns states,
                                           boolean callback) throws Exception {
        return TaskPermissions.of(integration(tail), json(request), machine("aws"), null, null, callback,
                new TaskPermissions.ControlServices(cloudFormation, sdkManagement, states)).orElseThrow();
    }

    private static TaskPermissions control(String tail, String request) throws Exception {
        return control(tail, request, null, null, null, false);
    }

    private static CloudFormationQueryHandler stacks() {
        CloudFormationQueryHandler handler = mock(CloudFormationQueryHandler.class);
        when(handler.stackId(any(), any())).thenReturn(Optional.empty());
        when(handler.stackId("s", "eu-west-1")).thenReturn(Optional.of(STACK));
        when(handler.stackId(STACK, "eu-west-1")).thenReturn(Optional.of(STACK));
        when(handler.stackIdOfChangeSet(any(), any())).thenReturn(Optional.empty());
        when(handler.stackIdOfResource(any(), any())).thenReturn(Optional.empty());
        when(handler.stackSetId(any())).thenReturn(Optional.empty());
        when(handler.stackSetId("ss")).thenReturn(Optional.of("ss:id"));
        return handler;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "createSchedule|{\"Name\":\"n\"}|scheduler:CreateSchedule|schedule/default/n",
            "updateSchedule|{\"Name\":\"n\",\"GroupName\":\"g\"}|scheduler:UpdateSchedule|schedule/g/n",
            "deleteSchedule|{\"Name\":\"n\",\"GroupName\":\" \"}|scheduler:DeleteSchedule|schedule/default/n"})
    void aScheduleIsCheckedInItsGroup(String api, String request, String action, String resource) throws Exception {
        TaskPermissions permissions = control("aws-sdk:scheduler:" + api, request);

        assertEquals("Scheduler.AccessDeniedException", permissions.deniedError());
        assertEquals(List.of(new Permission(action, "arn:aws:scheduler:eu-west-1:" + ACCOUNT + ":" + resource)),
                permissions.permissions());
    }

    @Test
    void aScheduleRequestNamingNoScheduleNeedsNoPermission() throws Exception {
        assertEquals(List.of(), control("aws-sdk:scheduler:deleteSchedule", "{\"GroupName\":\"g\"}").permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:scheduler:getSchedule", "aws-sdk:scheduler:listSchedules",
            "aws-sdk:cloudformation:describeTypeRegistration", "aws-sdk:sfn:describeExecution", "aws-sdk:sfn:stopExecution",
            "aws-sdk:ec2:describeInstances", "aws-sdk:lambda:invoke", "aws-sdk:secretsmanager:getSecretValue",
            "aws-sdk:s3:getObject", "s3:getObject", "aws-sdk:s3:listObjectsV2", "dynamodb:query",
            "aws-sdk:rdsdata:batchExecuteStatement", "http:get", "elasticmapreduce:describeCluster"})
    void anApiNoIntegrationServesIsNotChecked(String tail) throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(tail), json("{}"), machine("aws"), null, null, false,
                new TaskPermissions.ControlServices(null, null, null)));
    }

    /** A new case in the handler has to name its resource type here, or it would run unchecked under enforcement. */
    @Test
    void everyActionTheCloudFormationHandlerServesIsChecked() throws Exception {
        String handler = Files.readString(Path.of("src/main/java/io/floci/aws/services/cloudformation/"
                + "CloudFormationQueryHandler.java"));
        Matcher action = Pattern.compile("case \"([A-Za-z]+)\" -> ").matcher(handler);
        int actions = 0;
        while (action.find()) {
            actions++;
            String api = Character.toLowerCase(action.group(1).charAt(0)) + action.group(1).substring(1);
            assertTrue(TaskPermissions.of(integration("aws-sdk:cloudformation:" + api), json("{}"), machine("aws"), null,
                    null, false, new TaskPermissions.ControlServices(null, null, null)).isPresent(), action.group(1));
        }
        assertEquals(36, actions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validateTemplate", "listStacks", "listExports", "describeOrganizationsAccess",
            "activateOrganizationsAccess", "createStackSet", "listStackSets"})
    void aCloudFormationActionWithNoResourceTypeIsOnAnyResource(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), "*")),
                control("aws-sdk:cloudformation:" + api, "{}").permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateStack", "deleteStack", "continueUpdateRollback", "updateTerminationProtection",
            "createChangeSet", "describeChangeSet", "executeChangeSet", "deleteChangeSet", "listChangeSets",
            "describeStackEvents", "describeStackResources", "listStackResources", "describeStackResource", "getTemplate",
            "setStackPolicy", "getStackPolicy", "describeStacks", "getTemplateSummary", "createStack"})
    void aCloudFormationStackActionIsOnTheStackAsCloudFormationHoldsIt(String api) throws Exception {
        for (String named : List.of("s", STACK)) {
            TaskPermissions permissions = control("aws-sdk:cloudformation:" + api,
                    "{\"StackName\":\"" + named + "\"}", stacks(), null, null, false);
            assertEquals("CloudFormation.AccessDeniedException", permissions.deniedError());
            assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), STACK)),
                    permissions.permissions(), named);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"createStack", "createChangeSet"})
    void aStackNotThereYetHasItsIdOpenForWhatCreatesIt(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api),
                        "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/new/*")),
                control("aws-sdk:cloudformation:" + api, "{\"StackName\":\"new\"}", stacks(), null, null, false).permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateStack", "deleteStack", "describeStacks", "describeChangeSet", "getTemplateSummary"})
    void aStackCloudFormationDoesNotHaveNamesNothing(String api) throws Exception {
        assertEquals(List.of(), control("aws-sdk:cloudformation:" + api, "{\"StackName\":\"none\"}", stacks(), null, null, false)
                .permissions());
    }

    @Test
    void anUnknownStackIdNeverOpensAnIdEvenToCreate() throws Exception {
        assertEquals(List.of(), control("aws-sdk:cloudformation:createStack",
                "{\"StackName\":\"arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/none/id\"}", stacks(), null, null, false)
                .permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"describeStacks", "getTemplateSummary"})
    void aCloudFormationActionWithAnOptionalStackIsOnAnyResourceWithoutOne(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), "*")),
                control("aws-sdk:cloudformation:" + api, "{}", stacks(), null, null, false).permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"describeChangeSet", "executeChangeSet", "deleteChangeSet"})
    void aChangeSetArnFindsItsStack(String api) throws Exception {
        CloudFormationQueryHandler handler = stacks();
        when(handler.stackIdOfChangeSet("arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":changeSet/c/1", "eu-west-1"))
                .thenReturn(Optional.of(STACK));

        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), STACK)),
                control("aws-sdk:cloudformation:" + api,
                        "{\"ChangeSetName\":\"arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":changeSet/c/1\"}",
                        handler, null, null, false).permissions());
        assertEquals(List.of(), control("aws-sdk:cloudformation:" + api, "{\"ChangeSetName\":\"c\"}",
                handler, null, null, false).permissions());
    }

    @Test
    void describeStackResourcesFindsTheStackOfThePhysicalId() throws Exception {
        CloudFormationQueryHandler handler = stacks();
        when(handler.stackIdOfResource("r-1", "eu-west-1")).thenReturn(Optional.of(STACK));

        assertEquals(List.of(new Permission("cloudformation:DescribeStackResources", STACK)),
                control("aws-sdk:cloudformation:describeStackResources", "{\"PhysicalResourceId\":\"r-1\"}",
                        handler, null, null, false).permissions());
        assertEquals(List.of(), control("aws-sdk:cloudformation:listStackResources", "{\"PhysicalResourceId\":\"r-1\"}",
                handler, null, null, false).permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"describeStackSet", "updateStackSet", "deleteStackSet", "createStackInstances",
            "listStackInstances", "describeStackInstance", "deleteStackInstances", "listStackSetOperations",
            "describeStackSetOperation", "listStackSetAutoDeploymentTargets"})
    void aStackSetActionIsOnTheStackSetAsCloudFormationHoldsIt(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api),
                        "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stackset/ss:id")),
                control("aws-sdk:cloudformation:" + api, "{\"StackSetName\":\"ss\"}", stacks(), null, null, false).permissions());
        assertEquals(List.of(), control("aws-sdk:cloudformation:" + api, "{\"StackSetName\":\"none\"}",
                stacks(), null, null, false).permissions());
    }

    @Test
    void anHttpTaskIsOnTheStateMachineWithItsEndpointAndMethodAsConditionKeys() throws Exception {
        TaskPermissions permissions = control("http:invoke", "{\"ApiEndpoint\":\"https://a.example/x\",\"Method\":\"PUT\"}");

        assertEquals("States.Http.AccessDeniedException", permissions.deniedError());
        assertEquals(List.of(new Permission("states:InvokeHTTPEndpoint", machine("aws").getStateMachineArn(),
                Map.of("states:HTTPEndpoint", List.of("https://a.example/x"), "states:HTTPMethod", List.of("PUT")))),
                permissions.permissions());
    }

    @Test
    void anHttpTaskWithNoMethodHasOnlyTheEndpointKey() throws Exception {
        assertEquals(Map.of("states:HTTPEndpoint", List.of("https://a.example/x")),
                control("http:invoke", "{\"ApiEndpoint\":\"https://a.example/x\"}").permissions().getFirst().conditionKeys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ApiEndpoint\":\" \"}", "{\"ApiEndpoint\":7}", "{\"Method\":\"GET\"}"})
    void anHttpTaskNamingNoEndpointNeedsNoPermission(String request) throws Exception {
        assertEquals(List.of(), control("http:invoke", request).permissions());
    }

    @Test
    void describeRegionsIsOnAnyResource() throws Exception {
        TaskPermissions permissions = control("aws-sdk:ec2:describeRegions", "{}");

        assertEquals("Ec2.AccessDeniedException", permissions.deniedError());
        assertEquals(List.of(new Permission("ec2:DescribeRegions", "*")), permissions.permissions());
    }

    @Test
    void lambdaMappingsAreCheckedAsTheServiceReferenceGivesThem() throws Exception {
        TaskPermissions list = control("aws-sdk:lambda:listEventSourceMappings", "{\"FunctionName\":\"f\"}");
        assertEquals("Lambda.AccessDeniedException", list.deniedError());
        assertEquals(List.of(new Permission("lambda:ListEventSourceMappings", "*")), list.permissions());
        assertEquals(List.of(new Permission("lambda:UpdateEventSourceMapping",
                        "arn:aws:lambda:eu-west-1:" + ACCOUNT + ":event-source-mapping:u-2")),
                control("aws-sdk:lambda:updateEventSourceMapping", "{\"Uuid\":\"u-2\"}").permissions());
        assertEquals(List.of(), control("aws-sdk:lambda:updateEventSourceMapping", "{}").permissions());
        assertEquals(List.of(), control("aws-sdk:lambda:updateEventSourceMapping", "{\"UUID\":\"u-1\"}").permissions());
    }

    @Test
    void aSecretIsCheckedOnItsStoredArn() throws Exception {
        SdkManagementTaskInvoker invoker = mock(SdkManagementTaskInvoker.class);
        String stored = "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:s-AbCdEf";
        String foreign = "arn:aws:secretsmanager:eu-west-1:444455556666:secret:o-AbCdEf";
        when(invoker.secretArn(any(), any(), any())).thenReturn(Optional.empty());
        when(invoker.secretArn(ACCOUNT, "s", "eu-west-1")).thenReturn(Optional.of(stored));

        TaskPermissions named = control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"s\"}", null, invoker, null, false);
        assertEquals("SecretsManager.AccessDeniedException", named.deniedError());
        assertEquals(List.of(new Permission("secretsmanager:DescribeSecret", stored)), named.permissions());
        assertEquals(List.of(new Permission("secretsmanager:DescribeSecret", foreign)),
                control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"" + foreign + "\"}", null, invoker, null, false).permissions());
        assertEquals(List.of(), control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"none\"}", null, invoker, null, false).permissions());
        assertEquals(List.of(), control("aws-sdk:secretsmanager:describeSecret", "{}", null, invoker, null, false).permissions());
    }

    @ParameterizedTest
    @CsvSource({"startExecution,states:StartExecution", "startSyncExecution,states:StartSyncExecution"})
    void anSdkStartIsCheckedOnTheChildWithTheExecutionRoleChecks(String api, String action) throws Exception {
        String child = "arn:aws:states:eu-west-1:" + ACCOUNT + ":stateMachine:c";
        TaskPermissions permissions = control("aws-sdk:sfn:" + api, "{\"StateMachineArn\":\"" + child + "\"}");

        assertEquals("Sfn.AccessDeniedException", permissions.deniedError());
        assertTrue(permissions.checksExecutionRole());
        assertEquals(List.of(new Permission(action, child)), permissions.permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync", "states:startExecution.sync:2"})
    void aNestedStartIsCheckedOnTheChildWithoutTheExecutionRoleChecks(String tail) throws Exception {
        String child = "arn:aws:states:eu-west-1:" + ACCOUNT + ":stateMachine:c";
        TaskPermissions permissions = control(tail, "{\"StateMachineArn\":\"" + child + "\"}");

        assertEquals("StepFunctions.AccessDeniedException", permissions.deniedError());
        assertFalse(permissions.checksExecutionRole());
        assertEquals(List.of(new Permission("states:StartExecution", child)), permissions.permissions());
    }

    @Test
    void aStartNamingAVersionIsCheckedOnTheStateMachineAndOneNamingNothingNeedsNoPermission() throws Exception {
        String child = "arn:aws:states:eu-west-1:" + ACCOUNT + ":stateMachine:c";
        assertEquals(List.of(new Permission("states:StartExecution", child)),
                control("states:startExecution", "{\"StateMachineArn\":\"" + child + ":3\"}").permissions());
        assertEquals(List.of(), control("states:startExecution", "{}").permissions());
        assertEquals(List.of(), control("aws-sdk:sfn:startExecution", "{\"StateMachineArn\":\"not-an-arn\"}").permissions());
    }

    @ParameterizedTest
    @CsvSource({"sendTaskSuccess,states:SendTaskSuccess", "sendTaskFailure,states:SendTaskFailure"})
    void aCallbackIsCheckedOnWhatItsTokenWaitsIn(String api, String action) throws Exception {
        String activity = "arn:aws:states:eu-west-1:" + ACCOUNT + ":activity:a";
        StoredResourceArns states = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "states";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findByMembers(String type, List<String> names, String region) {
                if (!"t-1".equals(names.getFirst())) {
                    throw new AwsException("InvalidToken", "Invalid Token", 400);
                }
                return Optional.of(activity);
            }
        };

        assertEquals(List.of(new Permission(action, activity)),
                control("aws-sdk:sfn:" + api, "{\"TaskToken\":\"t-1\"}", null, null, states, false).permissions());
        assertEquals(List.of(), control("aws-sdk:sfn:" + api, "{\"TaskToken\":\"gone\"}", null, null, states, false).permissions());
    }

    @Test
    void describeMapRunIsOnTheMapRun() throws Exception {
        String mapRun = "arn:aws:states:eu-west-1:" + ACCOUNT + ":mapRun:m/label:id";
        assertEquals(List.of(new Permission("states:DescribeMapRun", mapRun)),
                control("aws-sdk:sfn:describeMapRun", "{\"MapRunArn\":\"" + mapRun + "\"}").permissions());
    }

    @Test
    void itemReaderReadsTheObjectInTheStateMachinesPartition() {
        assertEquals(List.of(new Permission("s3:GetObject", "arn:aws:s3:::b/k/x.json")),
                TaskPermissions.itemReaderGetObject("b", "k/x.json", machine("aws")));
        assertEquals(List.of(new Permission("s3:GetObject", "arn:aws-cn:s3:::b/k")),
                TaskPermissions.itemReaderGetObject("b", "k", machine("aws-cn")));
    }

    @Test
    void itemReaderNamingNoObjectNeedsNoPermission() {
        assertEquals(List.of(), TaskPermissions.itemReaderGetObject(null, "k", machine("aws")));
        assertEquals(List.of(), TaskPermissions.itemReaderGetObject(" ", "k", machine("aws")));
        assertEquals(List.of(), TaskPermissions.itemReaderGetObject("b", null, machine("aws")));
    }

    @Test
    void itemReaderListsTheBucketOfferingThePrefixItHas() {
        assertEquals(List.of(new Permission("s3:ListBucket", "arn:aws:s3:::b")),
                TaskPermissions.itemReaderListObjects("b", null, machine("aws")));
        assertEquals(List.of(new Permission("s3:ListBucket", "arn:aws-us-gov:s3:::b",
                        Map.of("s3:prefix", List.of("data/")))),
                TaskPermissions.itemReaderListObjects("b", "data/", machine("aws-us-gov")));
    }

    @Test
    void itemReaderListingNoBucketNeedsNoPermission() {
        assertEquals(List.of(), TaskPermissions.itemReaderListObjects(null, "p", machine("aws")));
        assertEquals(List.of(), TaskPermissions.itemReaderListObjects("", "p", machine("aws")));
    }

    @ParameterizedTest
    @CsvSource({"out,out/run/", "out/,out/run/", "'',run/", "/a//,/a//run/"})
    void resultWriterExportIsEachObjectItWrites(String prefix, String base) throws Exception {
        JsonNode location = MAPPER.valueToTree(Map.of("Bucket", "b", "Prefix", prefix));

        assertEquals(List.of(new Permission("s3:PutObject", "arn:aws:s3:::b/" + base + "SUCCEEDED_0.json"),
                        new Permission("s3:PutObject", "arn:aws:s3:::b/" + base + "manifest.json")),
                TaskPermissions.resultWriterExport(location, "run", false, machine("aws")));
        assertEquals(List.of(new Permission("s3:PutObject", "arn:aws-cn:s3:::b/" + base + "SUCCEEDED_0.json"),
                        new Permission("s3:PutObject", "arn:aws-cn:s3:::b/" + base + "FAILED_0.json"),
                        new Permission("s3:PutObject", "arn:aws-cn:s3:::b/" + base + "manifest.json")),
                TaskPermissions.resultWriterExport(location, "run", true, machine("aws-cn")));
    }

    @Test
    void resultWriterExportWithNoPrefixIsUnderTheRun() throws Exception {
        assertEquals("arn:aws:s3:::b/run/manifest.json", TaskPermissions.resultWriterExport(
                json("{\"Bucket\":\"b\"}"), "run", false, machine("aws")).getLast().resourceArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Bucket\":\"\"}", "{\"Bucket\":1}", "{\"Bucket\":\"b\",\"Prefix\":1}"})
    void resultWriterNamingNoLocationNeedsNoPermission(String location) throws Exception {
        assertEquals(List.of(), TaskPermissions.resultWriterExport(json(location), "run", true, machine("aws")));
    }

    /** {@code executeTaskState} strips the suffix, so each of these arrives bare, with a token, and is mapped as itself. */
    @ParameterizedTest
    @ValueSource(strings = {"aws-sdk:scheduler:createSchedule", "aws-sdk:cloudformation:listStacks", "http:invoke",
            "aws-sdk:ec2:describeRegions", "aws-sdk:lambda:listEventSourceMappings",
            "aws-sdk:secretsmanager:describeSecret", "aws-sdk:sfn:startExecution", "states:startExecution"})
    void aCallbackFormIsMappedAsTheBareForm(String tail) throws Exception {
        String request = "{\"Name\":\"n\",\"ApiEndpoint\":\"https://a.example/\",\"StateMachineArn\":"
                + "\"arn:aws:states:eu-west-1:" + ACCOUNT + ":stateMachine:c\"}";

        assertEquals(control(tail, request), control(tail, request, null, null, null, true));
    }

    private static TaskPermissions ecsPermissions(String tail, String request, EcsService ecs) throws Exception {
        return TaskPermissions.of(integration(tail), json(request), machine("aws"), null, ecs, false).orElseThrow();
    }

    private static List<Permission> sdkPermissions(String api, String request) throws Exception {
        return TaskPermissions.of(integration("aws-sdk:dynamodb:" + api), json(request), machine("aws")).orElseThrow().permissions();
    }

    private static List<Permission> redshiftPermissions(String api, String request, StoredResourceArns stored) throws Exception {
        return TaskPermissions.of(integration("aws-sdk:redshiftdata:" + api), json(request), machine("aws"), stored)
                .orElseThrow().permissions();
    }

    private static String table(String name) {
        return "arn:aws:dynamodb:eu-west-1:" + ACCOUNT + ":table/" + name;
    }

    private static List<Permission> snsPermissions(String request) throws Exception {
        return TaskPermissions.of(integration("sns:publish"), json(request), machine("aws")).orElseThrow().permissions();
    }

    private static List<Permission> eventsPermissions(String request) throws Exception {
        return TaskPermissions.of(integration("events:putEvents"), json(request), machine("aws")).orElseThrow().permissions();
    }

    private static String bus(String name) {
        return "arn:aws:events:eu-west-1:" + ACCOUNT + ":event-bus/" + name;
    }

    private static JsonNode json(String request) throws Exception {
        return MAPPER.readTree(request);
    }

    private static StatesIntegration integration(String tail) {
        return StatesIntegration.parse("arn:aws:states:::" + tail).orElseThrow();
    }

    private static JsonNode input(String queueUrl) {
        return MAPPER.valueToTree(Map.of("QueueUrl", queueUrl));
    }

    private static StateMachine machine(String partition) {
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn("arn:" + partition + ":states:eu-west-1:" + ACCOUNT + ":stateMachine:m");
        return machine;
    }
}
