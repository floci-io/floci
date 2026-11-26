package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PassedRoleLookups;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.PassedRoleLookup;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.services.stepfunctions.LambdaTaskAuthorizer.Permission;
import io.floci.aws.services.stepfunctions.model.StateMachine;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
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

@ResourceLock(ParallelLocks.PORTS)
class TaskPermissionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String TOPIC = "arn:aws:sns:eu-west-1:111122223333:orders";
    private static final String REDSHIFT_CLUSTER = "arn:aws:redshift:eu-west-1:111122223333:cluster:orders";
    private static final String ENDPOINT = "arn:aws:sns:eu-west-1:111122223333:endpoint/GCM/app/id";
    private static final String TASK_DEFINITION = "arn:aws:ecs:eu-west-1:111122223333:task-definition/orders:7";
    private static final String SERVICE = "arn:aws:ecs:eu-west-1:111122223333:service/c/s";
    private static final String ANY_REVISION = "arn:aws:ecs:eu-west-1:111122223333:task-definition/*:*";
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

    /** A table named by its ARN is checked as given and as the state machine's own, the table execution reaches. */
    @Test
    void aTableNamedByItsArnIsEvaluatedAsGivenAndAsTheMachinesOwn() throws Exception {
        String arn = "arn:aws:dynamodb:eu-west-1:444455556666:table/orders";
        List<Permission> both = List.of(new Permission("dynamodb:PutItem", arn),
                new Permission("dynamodb:PutItem", table("orders")));
        assertEquals(both, sdkPermissions("putItem", "{\"TableName\":\"" + arn + "\"}"));
        assertEquals(both, TaskPermissions.of(integration("dynamodb:putItem"), json("{\"TableName\":\"" + arn + "\"}"),
                machine("aws")).orElseThrow().permissions());
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
        assertEquals(List.of(new Permission("dynamodb:ConditionCheckItem", table("ccc"), enclosing),
                new Permission("dynamodb:PutItem", table("aaa"), enclosing),
                new Permission("dynamodb:DeleteItem", table("bbb"), enclosing),
                new Permission("dynamodb:UpdateItem", table("aaa"), enclosing)),
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

    /** Execution resolves an ARN within the request's account, so the item is checked on that table too. */
    @Test
    void aTransactItemNamedByAnArnIsAlsoTheTableItsExecutionReaches() throws Exception {
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of("TransactWriteItems"));
        assertEquals(List.of(new Permission("dynamodb:PutItem", "arn:aws:dynamodb:eu-west-1:444455556666:table/aaa",
                        enclosing), new Permission("dynamodb:PutItem", table("aaa"), enclosing)),
                sdkPermissions("transactWriteItems", """
                        {"TransactItems":[{"Put":{"TableName":"arn:aws:dynamodb:eu-west-1:444455556666:table/aaa"}}]}"""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TransactItems\":\"x\"}", "{\"TransactItems\":[{\"Put\":{}}]}",
            "{\"TransactItems\":[{\"Get\":{\"TableName\":\"aaa\"}}]}"})
    void aTransactionNamingNoTableNeedsNoPermission(String request) throws Exception {
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

    /** Each statement of a batch or a transaction is checked apart, a transaction's as enclosed by it. */
    @Test
    void eachStatementOfABatchIsAuthorizedByWhatItDoesOnWhatItNames() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:PartiQLSelect", table("a") + "/index/byName"),
                new Permission("dynamodb:PartiQLDelete", table("b"))),
                sdkPermissions("batchExecuteStatement", """
                        {"Statements":[{"Statement":"DELETE FROM \\"b\\" WHERE pk = 'x'"},
                         {"Statement":"SELECT * FROM \\"a\\".\\"byName\\" WHERE pk = 'x'"}]}"""));
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of("ExecuteTransaction"));
        assertEquals(List.of(new Permission("dynamodb:PartiQLInsert", table("a"), enclosing),
                new Permission("dynamodb:PartiQLDelete", table("b"), enclosing)),
                sdkPermissions("executeTransaction", """
                        {"TransactStatements":[{"Statement":"DELETE FROM \\"b\\" WHERE pk = 'x'"},
                         {"Statement":"INSERT INTO \\"a\\" VALUE {'pk':'x'}"}]}"""));
    }

    /** An EXISTS statement runs the SELECT it evaluates, so it needs PartiQLSelect on that SELECT's table. */
    @Test
    void anExistsStatementIsAuthorizedAsTheSelectItEvaluates() throws Exception {
        Map<String, List<String>> enclosing = Map.of("dynamodb:EnclosingOperation", List.of("ExecuteTransaction"));
        assertEquals(List.of(new Permission("dynamodb:PartiQLSelect", table("b"), enclosing),
                new Permission("dynamodb:PartiQLInsert", table("a"), enclosing)),
                sdkPermissions("executeTransaction", """
                        {"TransactStatements":[{"Statement":"INSERT INTO \\"a\\" VALUE {'pk':'x'}"},
                         {"Statement":"EXISTS(SELECT * FROM \\"b\\" WHERE pk = 'x' AND v = 1)"}]}"""));
        assertEquals(List.of(),
                sdkPermissions("executeTransaction", "{\"TransactStatements\":[{\"Statement\":\"-- nothing\"}]}"));
    }

    /** A value naming no resource of the action's types names none, so the call is checked on any resource. */
    @Test
    void aResourceThatIsNotAnArnIsCheckedOnAnyResource() throws Exception {
        assertEquals(List.of(new Permission("dynamodb:TagResource", "*")),
                sdkPermissions("tagResource", "{\"ResourceArn\":\"not-an-arn\"}"));
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
        // The reader asks with the members' paths too, which a service finding by the values alone leaves to them.
        when(stored.findByMembers(any(), any(), any(), any())).thenCallRealMethod();
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

    /** Whatever names it, the check is on the revision ECS resolves it to, which is the one it would run. */
    @ParameterizedTest
    @ValueSource(strings = {"ecs:runTask", "ecs:runTask.sync"})
    void runTaskIsCheckedOnTheRevisionEcsResolves(String tail) throws Exception {
        for (String reference : List.of("orders", "orders:7", TASK_DEFINITION)) {
            assertEquals(TaskPermissions.accessDenied("ECS.", List.of(new Permission("ecs:RunTask", TASK_DEFINITION))),
                    ecsPermissions(tail, "{\"TaskDefinition\":\"" + reference + "\"}", ecs()), reference);
        }
    }

    /** A form the integration does not serve is left to its unsupported-mode answer, whatever the role. */
    @Test
    void aRunTaskFormEcsDoesNotServeStaysUnchecked() throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration("ecs:runTask.sync:2"),
                json("{\"TaskDefinition\":\"orders\"}"), machine("aws"), null, ecs(), false));
    }

    /** {@code executeTaskState} strips the suffix, so this is a {@code .waitForTaskToken} as the dispatch sees it. */
    @Test
    void runTaskWithATokenIsChecked() throws Exception {
        assertEquals(List.of(new Permission("ecs:RunTask", TASK_DEFINITION)),
                TaskPermissions.of(integration("ecs:runTask"), json("{\"TaskDefinition\":\"orders\"}"), machine("aws"),
                        null, ecs(), true).orElseThrow().permissions());
    }

    /** A request naming no definition is answered by ECS; one naming a definition ECS does not have, any revision. */
    @Test
    void aTaskDefinitionEcsDoesNotHaveIsCheckedWithAnyRevision() throws Exception {
        for (String request : List.of("{}", "{\"TaskDefinition\":null}")) {
            assertEquals(TaskPermissions.accessDenied("ECS.", List.of()), ecsPermissions("ecs:runTask", request, ecs()),
                    request);
        }
        assertEquals(List.of(new Permission("ecs:RunTask", ANY_REVISION)),
                ecsPermissions("ecs:runTask", "{\"TaskDefinition\":\"no-such\"}", ecs()).permissions());
        assertEquals(List.of(new Permission("ecs:RunTask", ANY_REVISION)), TaskPermissions.of(integration("ecs:runTask"),
                json("{\"TaskDefinition\":\"orders\"}"), machine("aws")).orElseThrow().permissions());
    }

    /** The members are read in either casing, as the SDK form recases them for the handler. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"Cluster\":\"c\",\"Service\":\"s\"}", "{\"cluster\":\"c\",\"service\":\"s\"}",
            "{\"Cluster\":\"arn:aws:ecs:eu-west-1:111122223333:cluster/c\",\"Service\":\"s\"}"})
    void updateServiceIsCheckedOnTheServiceItNames(String request) throws Exception {
        assertEquals(TaskPermissions.accessDenied("Ecs.", List.of(new Permission("ecs:UpdateService", SERVICE))),
                ecsPermissions("aws-sdk:ecs:updateService", request, ecs()));
    }

    @Test
    void updateServiceWithNoClusterIsOnTheDefaultCluster() throws Exception {
        assertEquals(List.of(new Permission("ecs:UpdateService", "arn:aws:ecs:eu-west-1:111122223333:service/default/s")),
                ecsPermissions("aws-sdk:ecs:updateService", "{\"Service\":\"s\"}", ecs()).permissions());
    }

    /** Each service named, a name in the request's cluster and an ARN as given, whether ECS has it or not. */
    @Test
    void describeServicesIsCheckedOnEachServiceItNames() throws Exception {
        String inC = "arn:aws:ecs:eu-west-1:111122223333:service/c/";
        assertEquals(List.of(new Permission("ecs:DescribeServices", SERVICE),
                        new Permission("ecs:DescribeServices", inC + "a"),
                        new Permission("ecs:DescribeServices", inC + "missing")),
                ecsPermissions("aws-sdk:ecs:describeServices",
                        "{\"Cluster\":\"c\",\"Services\":[\"a\",\"" + SERVICE + "\",\"missing\",\"a\"]}", ecs())
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

    /** A job run passes its execution role to EMR Serverless, as a direct {@code StartJobRun} does. */
    @Test
    void aJobRunNeedsPassRoleOnItsExecutionRole() throws Exception {
        String role = "arn:aws:iam::111122223333:role/job";
        assertEquals(List.of(new Permission("emr-serverless:StartJobRun", APPLICATION),
                        new Permission("iam:PassRole", role,
                                Map.of("iam:PassedToService", List.of("emr-serverless.amazonaws.com")))),
                TaskPermissions.of(integration("emr-serverless:startJobRun"),
                        json("{\"ApplicationId\":\"a\",\"ExecutionRoleArn\":\"" + role + "\"}"), machine("aws"))
                        .orElseThrow().permissions());
    }

    /**
     * Tags a create or a job run sends need {@code emr-serverless:TagResource}, on the application a create makes or
     * the one a job run is checked on, as a direct request's do, and the call itself is checked with them.
     */
    @Test
    void tagsACreateOrAJobRunSendsNeedTagResource() throws Exception {
        Map<String, List<String>> tagged = Map.of("aws:RequestTag/team", List.of("data"), "aws:TagKeys", List.of("team"));
        assertEquals(List.of(new Permission("emr-serverless:CreateApplication", "*", tagged),
                        new Permission("emr-serverless:TagResource",
                                "arn:aws:emr-serverless:eu-west-1:111122223333:/applications/*", tagged)),
                TaskPermissions.of(integration("emr-serverless:createApplication"),
                        json("{\"Tags\":{\"team\":\"data\"}}"), machine("aws")).orElseThrow().permissions());
        assertEquals(List.of(new Permission("emr-serverless:StartJobRun", APPLICATION, tagged),
                        new Permission("emr-serverless:TagResource", APPLICATION, tagged)),
                TaskPermissions.of(integration("emr-serverless:startJobRun"),
                        json("{\"ApplicationId\":\"a\",\"Tags\":{\"team\":\"data\"}}"), machine("aws"))
                        .orElseThrow().permissions());
    }

    private static final String TASK_ROLE = "arn:aws:iam::111122223333:role/task";
    private static final String EXECUTION_ROLE = "arn:aws:iam::111122223333:role/execution";
    private static final Map<String, List<String>> TO_ECS_TASKS =
            Map.of("iam:PassedToService", List.of("ecs-tasks.amazonaws.com"));

    /**
     * RunTask passes its task definition's task role and execution role and a role override, as a direct RunTask
     * does, a role given by name being the account's of that name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ecs:runTask", "ecs:runTask.sync"})
    void runTaskNeedsPassRoleOnTheTaskDefinitionsRolesAndAnOverride(String tail) throws Exception {
        assertEquals(List.of(new Permission("ecs:RunTask", TASK_DEFINITION),
                        new Permission("iam:PassRole", TASK_ROLE, TO_ECS_TASKS),
                        new Permission("iam:PassRole", EXECUTION_ROLE, TO_ECS_TASKS),
                        new Permission("iam:PassRole", "arn:aws:iam::111122223333:role/override", TO_ECS_TASKS)),
                TaskPermissions.of(integration(tail),
                        json("{\"TaskDefinition\":\"orders\",\"Overrides\":{\"TaskRoleArn\":\"override\"}}"),
                        machine("aws"), null, ecs(), false, new TaskPermissions.ControlServices(null, null, null, null),
                        roles()).orElseThrow().permissions());
    }

    /** A request ECS answers needs nothing, whatever roles it names. */
    @Test
    void aRunTaskNamingNoDefinitionNeedsNoPassRole() throws Exception {
        assertEquals(List.of(), TaskPermissions.of(integration("ecs:runTask"),
                json("{\"Overrides\":{\"TaskRoleArn\":\"override\"}}"), machine("aws"), null, ecs(), false,
                new TaskPermissions.ControlServices(null, null, null, null), roles()).orElseThrow().permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"createSchedule", "updateSchedule"})
    void aScheduleNeedsPassRoleOnItsTargetsRole(String api) throws Exception {
        String role = "arn:aws:iam::111122223333:role/scheduler";
        List<Permission> permissions = control("aws-sdk:scheduler:" + api,
                "{\"Name\":\"n\",\"Target\":{\"Arn\":\"q\",\"RoleArn\":\"" + role + "\"}}").permissions();

        assertEquals(new Permission("iam:PassRole", role, Map.of("iam:PassedToService",
                List.of("scheduler.amazonaws.com"))), permissions.getLast());
        assertEquals(2, permissions.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"createStack", "updateStack", "deleteStack", "createChangeSet", "continueUpdateRollback"})
    void aStackOperationNeedsPassRoleOnTheServiceRoleItNames(String api) throws Exception {
        String role = "arn:aws:iam::111122223333:role/deployer";
        assertEquals(List.of(new Permission("iam:PassRole", role,
                        Map.of("iam:PassedToService", List.of("cloudformation.amazonaws.com")))),
                passedRoles(control("aws-sdk:cloudformation:" + api,
                        "{\"StackName\":\"s\",\"ChangeSetName\":\"c\",\"RoleARN\":\"" + role + "\"}",
                        stacks(), null, null, false)));
    }

    /** A self-managed StackSet naming no administration role passes the default one, as a direct create does. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"StackSetName\":\"n\"}|arn:aws:iam::111122223333:role/AWSCloudFormationStackSetAdministrationRole",
            "{\"StackSetName\":\"n\",\"AdministrationRoleARN\":\"arn:aws:iam::111122223333:role/admin\"}"
                    + "|arn:aws:iam::111122223333:role/admin",
            "{\"StackSetName\":\"n\",\"PermissionModel\":\"SERVICE_MANAGED\"}|"})
    void aStackSetPassesItsAdministrationRole(String request, String role) throws Exception {
        assertEquals(role == null ? List.of() : List.of(new Permission("iam:PassRole", role,
                        Map.of("iam:PassedToService", List.of("cloudformation.amazonaws.com")))),
                passedRoles(control("aws-sdk:cloudformation:createStackSet", request)));
    }

    /**
     * A cluster passes its service role, its instance profile's role and its auto scaling role, each to the service
     * AWS's managed EMR policy passes it to, a role given by name being the one IAM holds under it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"elasticmapreduce:createCluster", "elasticmapreduce:createCluster.sync"})
    void createClusterNeedsPassRoleOnTheClustersRoles(String tail) throws Exception {
        assertEquals(List.of(new Permission("elasticmapreduce:RunJobFlow", "*"),
                        new Permission("iam:PassRole", "arn:aws:iam::111122223333:role/emr/service",
                                Map.of("iam:PassedToService", List.of("elasticmapreduce.amazonaws.com"))),
                        new Permission("iam:PassRole", "arn:aws:iam::111122223333:role/cluster",
                                Map.of("iam:PassedToService", List.of("ec2.amazonaws.com"))),
                        new Permission("iam:PassRole", "arn:aws:iam::111122223333:role/scaling",
                                Map.of("iam:PassedToService", List.of("application-autoscaling.amazonaws.com")))),
                TaskPermissions.of(integration(tail), json("""
                                {"Name":"c","ServiceRole":"service","JobFlowRole":"cluster-profile",
                                 "AutoScalingRole":"arn:aws:iam::111122223333:role/scaling"}"""), machine("aws"), null,
                        null, false, new TaskPermissions.ControlServices(null, null, null, null), roles())
                        .orElseThrow().permissions());
    }

    /** IAM's role {@code service}, under a path, and the profile holding {@code cluster}; ECS's roles of {@code orders}. */
    private static TaskPermissions.RequestLookups roles() {
        IamService iam = mock(IamService.class);
        IamRole service = new IamRole();
        service.setArn("arn:aws:iam::111122223333:role/emr/service");
        when(iam.findRole(ACCOUNT, "service")).thenReturn(Optional.of(service));
        IamRole cluster = new IamRole();
        cluster.setArn("arn:aws:iam::111122223333:role/cluster");
        when(iam.findRole(ACCOUNT, "cluster")).thenReturn(Optional.of(cluster));
        InstanceProfile profile = new InstanceProfile();
        profile.setRoleNames(List.of("cluster"));
        when(iam.findInstanceProfile(ACCOUNT, "cluster-profile")).thenReturn(Optional.of(profile));
        PassedRoleLookup taskDefinitions = new PassedRoleLookup() {
            @Override
            public List<String> taskDefinitionRoles(String reference, String region) {
                return "orders".equals(reference) && "eu-west-1".equals(region)
                        ? List.of(TASK_ROLE, EXECUTION_ROLE) : List.of();
            }
        };
        return new TaskPermissions.RequestLookups(new PassedRoleLookups(iam, List.of(taskDefinitions)));
    }

    private static List<Permission> passedRoles(TaskPermissions permissions) {
        return permissions.permissions().stream().filter(permission -> "iam:PassRole".equals(permission.action()))
                .toList();
    }

    private static final String STACK = "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/s/id";
    private static final String STACK_SET = "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stackset/ss:id";

    private static TaskPermissions control(String tail, String request, StoredResourceArns cloudFormation,
                                           StoredResourceArns secrets, StoredResourceArns states,
                                           boolean callback) throws Exception {
        return TaskPermissions.of(integration(tail), json(request), machine("aws"), null, null, callback,
                new TaskPermissions.ControlServices(cloudFormation, secrets, states, schedules())).orElseThrow();
    }

    private static TaskPermissions control(String tail, String request) throws Exception {
        return control(tail, request, null, null, null, false);
    }

    /** Scheduler as IAM asks it: its default schedule group, the one a request naming none acts on. */
    private static StoredResourceArns schedules() {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return "scheduler";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return Optional.empty();
            }

            @Override
            public Optional<String> findDefault(String type, String region) {
                return "schedule-group".equals(type)
                        ? Optional.of("arn:aws:scheduler:" + region + ":" + ACCOUNT + ":schedule-group/default")
                        : Optional.empty();
            }
        };
    }

    /** CloudFormation as IAM asks it: the stack {@code s} and the stack set {@code ss}, under the ids it minted. */
    private static StoredResourceArns stacks() {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return "cloudformation";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return switch (type) {
                    case "stack" -> names.equals(List.of("s")) ? Optional.of(STACK) : Optional.empty();
                    case "stackset" -> names.equals(List.of("ss")) ? Optional.of(STACK_SET) : Optional.empty();
                    default -> Optional.empty();
                };
            }
        };
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
            "aws-sdk:sfn:describeExecution", "aws-sdk:sfn:stopExecution",
            "aws-sdk:ec2:describeInstances", "aws-sdk:lambda:invoke", "aws-sdk:secretsmanager:getSecretValue",
            "aws-sdk:s3:getObject", "s3:getObject", "aws-sdk:s3:listObjectsV2", "dynamodb:query",
            "aws-sdk:rdsdata:batchExecuteStatement", "http:get", "elasticmapreduce:describeCluster"})
    void anApiNoIntegrationServesIsNotChecked(String tail) throws Exception {
        assertEquals(Optional.empty(), TaskPermissions.of(integration(tail), json("{}"), machine("aws"), null, null, false,
                new TaskPermissions.ControlServices(null, null, null, null)));
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
                    null, false, new TaskPermissions.ControlServices(null, null, null, null)).isPresent(),
                action.group(1));
        }
        assertEquals(36, actions);
    }

    /** An API the handler does not serve is checked as the direct request is, and the handler answers it. */
    @Test
    void aCloudFormationApiTheHandlerDoesNotServeIsCheckedAsTheTableGivesIt() throws Exception {
        assertEquals(List.of(new Permission("cloudformation:DescribeTypeRegistration", "*")),
                control("aws-sdk:cloudformation:describeTypeRegistration", "{}").permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"validateTemplate", "listStacks", "listExports", "describeOrganizationsAccess",
            "activateOrganizationsAccess", "createStackSet", "listStackSets"})
    void aCloudFormationActionWithNoResourceTypeIsOnAnyResource(String api) throws Exception {
        assertEquals(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), "*"),
                control("aws-sdk:cloudformation:" + api, "{}").permissions().getFirst());
        assertEquals(List.of(), passedRoles(control("aws-sdk:cloudformation:" + api,
                "{\"PermissionModel\":\"SERVICE_MANAGED\"}")));
    }

    /** A task whose input is no object still needs its action, on any resource, as a direct request naming none. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"listStacks|null", "listStacks|[]", "describeStacks|\"s\"",
            "executeChangeSet|7"})
    void aCloudFormationTaskWhoseInputIsNoObjectIsOnAnyResource(String api, String input) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), "*")),
                control("aws-sdk:cloudformation:" + api, input).permissions());
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

    /** A stack CloudFormation does not hold, one a create makes among them, is its ARN with the id open. */
    @ParameterizedTest
    @ValueSource(strings = {"createStack", "createChangeSet", "updateStack", "deleteStack", "describeStacks",
            "describeChangeSet", "getTemplateSummary"})
    void aStackCloudFormationDoesNotHoldHasItsIdOpen(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api),
                        "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/new/*")),
                control("aws-sdk:cloudformation:" + api, "{\"StackName\":\"new\"}", stacks(), null, null, false)
                        .permissions());
    }

    @Test
    void aStackIdIsCheckedAsGiven() throws Exception {
        String unknown = "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stack/none/id";
        assertEquals(List.of(new Permission("cloudformation:CreateStack", unknown)),
                control("aws-sdk:cloudformation:createStack", "{\"StackName\":\"" + unknown + "\"}", stacks(), null,
                        null, false).permissions());
    }

    /**
     * A request naming its stack by no {@code StackName}, a change set by its ARN alone or a physical resource id
     * alone among them, is checked on any resource, as the direct request is: the vendored request resources fill
     * the stack from {@code StackName} only.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "describeStacks|{}",
            "getTemplateSummary|{}",
            "describeChangeSet|{\"ChangeSetName\":\"arn:aws:cloudformation:eu-west-1:000000000000:changeSet/c/1\"}",
            "executeChangeSet|{\"ChangeSetName\":\"arn:aws:cloudformation:eu-west-1:000000000000:changeSet/c/1\"}",
            "deleteChangeSet|{\"ChangeSetName\":\"c\"}",
            "describeStackResources|{\"PhysicalResourceId\":\"r-1\"}",
            "listStackResources|{\"PhysicalResourceId\":\"r-1\"}"})
    void aRequestNamingNoStackIsOnAnyResource(String api, String request) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), "*")),
                control("aws-sdk:cloudformation:" + api, request, stacks(), null, null, false).permissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"describeStackSet", "updateStackSet", "deleteStackSet", "createStackInstances",
            "listStackInstances", "describeStackInstance", "deleteStackInstances", "listStackSetOperations",
            "describeStackSetOperation", "listStackSetAutoDeploymentTargets"})
    void aStackSetActionIsOnTheStackSetAsCloudFormationHoldsIt(String api) throws Exception {
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api), STACK_SET)),
                control("aws-sdk:cloudformation:" + api, "{\"StackSetName\":\"ss\"}", stacks(), null, null, false)
                        .permissions());
        assertEquals(List.of(new Permission("cloudformation:" + AslExecutor.capitalizeFirst(api),
                        "arn:aws:cloudformation:eu-west-1:" + ACCOUNT + ":stackset/none:*")),
                control("aws-sdk:cloudformation:" + api, "{\"StackSetName\":\"none\"}", stacks(), null, null, false)
                        .permissions());
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

    /**
     * The check a direct {@code DescribeSecret} gets: the secret Secrets Manager stores a name under, random suffix
     * included; a name it stores nothing under, with the suffix open; and another account's ARN as given and as the
     * caller's own of that name.
     */
    @Test
    void aSecretIsCheckedAsADirectRequestChecksIt() throws Exception {
        String stored = "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:s-AbCdEf";
        String foreign = "arn:aws:secretsmanager:eu-west-1:444455556666:secret:o-AbCdEf";
        StoredResourceArns secrets = new StoredResourceArns() {
            @Override
            public String namespace() {
                return "secretsmanager";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return List.of("s").equals(names) ? Optional.of(stored) : Optional.empty();
            }
        };

        TaskPermissions named = control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"s\"}", null, secrets,
                null, false);
        assertEquals("SecretsManager.AccessDeniedException", named.deniedError());
        assertEquals(List.of(new Permission("secretsmanager:DescribeSecret", stored)), named.permissions());
        assertEquals(List.of(new Permission("secretsmanager:DescribeSecret", foreign),
                        new Permission("secretsmanager:DescribeSecret",
                                "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:o-AbCdEf")),
                control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"" + foreign + "\"}", null, secrets,
                        null, false).permissions());
        assertEquals(List.of(new Permission("secretsmanager:DescribeSecret",
                        "arn:aws:secretsmanager:eu-west-1:" + ACCOUNT + ":secret:none-??????")),
                control("aws-sdk:secretsmanager:describeSecret", "{\"SecretId\":\"none\"}", null, secrets, null, false)
                        .permissions());
        assertEquals(List.of(),
                control("aws-sdk:secretsmanager:describeSecret", "{}", null, secrets, null, false).permissions());
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

    private static TaskPermissions ecsPermissions(String tail, String request, StoredResourceArns ecs) throws Exception {
        return TaskPermissions.of(integration(tail), json(request), machine("aws"), null, ecs, false).orElseThrow();
    }

    /** What ECS answers IAM: the revision {@code orders} and {@code orders:7} resolve to, and its default cluster. */
    private static StoredResourceArns ecs() {
        return new StoredResourceArns() {
            @Override
            public String namespace() {
                return "ecs";
            }

            @Override
            public Optional<String> find(String type, List<String> names, String region) {
                return "task-definition".equals(type) && List.of(List.of("orders"), List.of("orders:7"),
                        List.of(TASK_DEFINITION)).contains(names) ? Optional.of(TASK_DEFINITION) : Optional.empty();
            }

            @Override
            public Optional<String> findDefault(String type, String region) {
                return "cluster".equals(type) ? Optional.of("arn:aws:ecs:" + region + ":111122223333:cluster/default")
                        : Optional.empty();
            }
        };
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
