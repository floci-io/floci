package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            "sns:publishBatch", "aws-sdk:eventbridge:putEvents", "events:listRules", "states:startExecution",
            "dynamodb:query", "http:invoke"})
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
