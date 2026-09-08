package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rules engine behaviour of {@link IotService} with in-memory stores and mocked action targets:
 * one failing action never fails the publish or the other actions, the error action receives the
 * failure document, and the {@code firehose} and {@code cloudwatchLogs} actions deliver the payload.
 * Also the five-version cap on a policy, including under racing creates.
 */
class IotServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TOPIC = "devices/d1/metrics";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/metrics";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:handler";
    private static final String ERROR_FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:errors";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SqsService sqs = mock(SqsService.class);
    private final LambdaService lambda = mock(LambdaService.class);
    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final FirehoseService firehose = mock(FirehoseService.class);
    private final CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
    private IotService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn(REGION);
        when(config.services().iot().ruleSqlStrict()).thenReturn(false);
        service = new IotService(
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT),
                config,
                new RegionResolver(REGION, ACCOUNT),
                mapper,
                new IotPublishEventRecorder(),
                mock(IotMqttBrokerService.class),
                sqs,
                mock(SnsService.class),
                mock(S3Service.class),
                mock(KinesisService.class),
                dynamoDb,
                lambda,
                firehose,
                logs,
                mock(io.github.hectorvent.floci.config.FlociCertificateAuthority.class),
                new io.github.hectorvent.floci.services.iam.IamPolicyEvaluator(mapper));
    }

    private IotTopicRule createRule(String name, String payloadJson) throws Exception {
        return service.createTopicRule(name, mapper.readTree(payloadJson), REGION);
    }

    /** A rule whose SQS action targets a queue that does not exist and whose Lambda action works. */
    private static String sqsThenLambdaRule(String errorActionJson) {
        String errorAction = errorActionJson == null ? "" : ", \"errorAction\": " + errorActionJson;
        return """
            {
              "sql": "SELECT * FROM 'devices/+/metrics'",
              "actions": [
                {"sqs": {"queueUrl": "%s", "roleArn": "arn:aws:iam::000000000000:role/rule"}},
                {"lambda": {"functionArn": "%s"}}
              ]%s
            }
            """.formatted(QUEUE_URL, FUNCTION_ARN, errorAction);
    }

    private static String lambdaErrorAction() {
        return "{\"lambda\": {\"functionArn\": \"" + ERROR_FUNCTION_ARN + "\"}}";
    }

    private void queueIsMissing() {
        when(sqs.sendMessage(eq(QUEUE_URL), anyString(), anyInt(), anyString()))
                .thenThrow(new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist for this wsdl version.", 400));
    }

    private void publish(String payload) {
        service.handlePublish(TOPIC, payload.getBytes(StandardCharsets.UTF_8), true, REGION, null);
    }

    private JsonNode capturedInvocationPayload(String functionArn) throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambda).invoke(eq(REGION), eq(functionArn), payload.capture(), eq(InvocationType.Event));
        return mapper.readTree(payload.getValue());
    }

    @Test
    void aFailingActionDoesNotFailThePublishOrStopTheOtherActions() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(null));
        queueIsMissing();

        assertDoesNotThrow(() -> publish("{\"v\":1}"));

        assertEquals("{\"v\":1}", capturedInvocationPayload(FUNCTION_ARN).toString());
    }

    @Test
    void theErrorActionReceivesTheFailureDocumentWhenAnActionFails() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(lambdaErrorAction()));
        queueIsMissing();

        publish("{\"v\":1}");

        JsonNode document = capturedInvocationPayload(ERROR_FUNCTION_ARN);
        assertEquals("metricsRule", document.get("ruleName").asText());
        assertEquals(TOPIC, document.get("topic").asText());
        assertEquals(Base64.getEncoder().encodeToString("{\"v\":1}".getBytes(StandardCharsets.UTF_8)),
                document.get("base64OriginalPayload").asText());
        assertEquals(1, document.get("failures").size());
        JsonNode failure = document.get("failures").get(0);
        assertEquals("SqsAction", failure.get("failedAction").asText());
        assertEquals(QUEUE_URL, failure.get("failedResource").asText());
        assertTrue(failure.get("errorMessage").asText().contains("does not exist"), failure.toString());
    }

    @Test
    void theErrorActionDoesNotRunWhenEveryActionSucceeds() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(lambdaErrorAction()));

        publish("{\"v\":1}");

        verify(lambda).invoke(eq(REGION), eq(FUNCTION_ARN), any(), eq(InvocationType.Event));
        verify(lambda, never()).invoke(eq(REGION), eq(ERROR_FUNCTION_ARN), any(), any());
    }

    @Test
    void aFailingErrorActionIsLoggedNotThrown() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(lambdaErrorAction()));
        queueIsMissing();
        when(lambda.invoke(eq(REGION), eq(ERROR_FUNCTION_ARN), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found: errors", 404));

        assertDoesNotThrow(() -> publish("{\"v\":1}"));

        verify(lambda, times(1)).invoke(eq(REGION), eq(ERROR_FUNCTION_ARN), any(), eq(InvocationType.Event));
    }

    @Test
    void everyFailingActionIsListedInTheFailureDocument() throws Exception {
        createRule("metricsRule", """
            {
              "sql": "SELECT * FROM 'devices/+/metrics'",
              "actions": [
                {"sqs": {"queueUrl": "%s", "roleArn": "arn:aws:iam::000000000000:role/rule"}},
                {"dynamoDBv2": {"putItem": {"tableName": "metrics"}, "roleArn": "arn:aws:iam::000000000000:role/rule"}}
              ],
              "errorAction": %s
            }
            """.formatted(QUEUE_URL, lambdaErrorAction()));
        queueIsMissing();

        publish("not json");

        JsonNode failures = capturedInvocationPayload(ERROR_FUNCTION_ARN).get("failures");
        assertEquals(2, failures.size());
        assertEquals("SqsAction", failures.get(0).get("failedAction").asText());
        assertEquals("DynamoDBv2Action", failures.get(1).get("failedAction").asText());
        assertEquals("metrics", failures.get(1).get("failedResource").asText());
    }

    private static String firehoseRule(String separator, boolean batchMode) {
        String separatorMember = separator == null ? "" : ", \"separator\": \"" + separator + "\"";
        return """
            {
              "sql": "SELECT * FROM 'devices/+/metrics'",
              "actions": [{"firehose": {"deliveryStreamName": "metrics", "roleArn": "arn:aws:iam::000000000000:role/rule"%s, "batchMode": %s}}]
            }
            """.formatted(separatorMember, batchMode);
    }

    private static String text(Record record) {
        return new String(record.getData(), StandardCharsets.UTF_8);
    }

    @Test
    void firehoseActionPutsThePayloadWithTheSeparatorAppended() throws Exception {
        createRule("metricsRule", firehoseRule("\\n", false));

        publish("{\"v\":1}");

        ArgumentCaptor<Record> record = ArgumentCaptor.forClass(Record.class);
        verify(firehose).putRecord(eq("metrics"), record.capture());
        assertEquals("{\"v\":1}\n", text(record.getValue()));
    }

    @Test
    void firehoseActionWithoutASeparatorPutsThePayloadAsIs() throws Exception {
        createRule("metricsRule", firehoseRule(null, false));

        publish("{\"v\":1}");

        ArgumentCaptor<Record> record = ArgumentCaptor.forClass(Record.class);
        verify(firehose).putRecord(eq("metrics"), record.capture());
        assertEquals("{\"v\":1}", text(record.getValue()));
    }

    @Test
    void firehoseActionInBatchModeDeliversEachElementOfAJsonArrayAsOneRecord() throws Exception {
        createRule("metricsRule", firehoseRule("\\n", true));

        publish("[{\"v\":1},{\"v\":2}]");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Record>> records = ArgumentCaptor.forClass(List.class);
        verify(firehose).putRecordBatch(eq("metrics"), records.capture());
        assertEquals(List.of("{\"v\":1}\n", "{\"v\":2}\n"), records.getValue().stream().map(IotServiceTest::text).toList());
        verify(firehose, never()).putRecord(anyString(), any());
    }

    @Test
    void firehoseActionInBatchModeDeliversAnythingElseAsOneRecord() throws Exception {
        createRule("metricsRule", firehoseRule(null, true));

        publish("plain text");

        ArgumentCaptor<Record> record = ArgumentCaptor.forClass(Record.class);
        verify(firehose).putRecord(eq("metrics"), record.capture());
        assertEquals("plain text", text(record.getValue()));
    }

    @Test
    void firehoseActionAcceptsEverySeparatorTheApiAllows() throws Exception {
        List<String> separators = List.of("\\n", "\\t", "\\r\\n", ",");
        for (int i = 0; i < separators.size(); i++) {
            createRule("rule" + i, firehoseRule(separators.get(i), false));
        }
    }

    @Test
    void firehoseActionRejectsAnySeparatorTheApiDoesNotAllow() {
        for (String separator : List.of("|", ";", "", " ", "\\n\\n")) {
            AwsException e = assertThrows(AwsException.class, () -> createRule("metricsRule", firehoseRule(separator, false)));
            assertEquals("InvalidRequestException", e.getErrorCode(), separator);
            assertEquals(400, e.getHttpStatus());
        }
        assertThrows(AwsException.class, () -> createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'", "actions": [],
             "errorAction": {"firehose": {"deliveryStreamName": "errors", "roleArn": "arn:aws:iam::000000000000:role/rule", "separator": "|"}}}
            """));
    }

    @Test
    void firehoseActionFailureIsReportedWithTheDeliveryStreamName() throws Exception {
        createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'",
             "actions": [{"firehose": {"deliveryStreamName": "metrics", "roleArn": "arn:aws:iam::000000000000:role/rule"}}],
             "errorAction": %s}
            """.formatted(lambdaErrorAction()));
        doThrow(new AwsException("ResourceNotFoundException", "Delivery stream not found: metrics", 400))
                .when(firehose).putRecord(eq("metrics"), any());

        publish("{\"v\":1}");

        JsonNode failure = capturedInvocationPayload(ERROR_FUNCTION_ARN).get("failures").get(0);
        assertEquals("FirehoseAction", failure.get("failedAction").asText());
        assertEquals("metrics", failure.get("failedResource").asText());
    }

    private static String cloudwatchLogsRule(boolean batchMode) {
        return """
            {
              "sql": "SELECT * FROM 'devices/+/metrics'",
              "actions": [{"cloudwatchLogs": {"logGroupName": "/iot/metrics", "roleArn": "arn:aws:iam::000000000000:role/rule", "batchMode": %s}}]
            }
            """.formatted(batchMode);
    }

    private List<Map<String, Object>> capturedLogEvents() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(logs).putLogEvents(eq("/iot/metrics"), eq("metricsRule"), events.capture(), eq(REGION));
        return events.getValue();
    }

    @Test
    void cloudwatchLogsActionWritesThePayloadToAStreamNamedAfterTheRule() throws Exception {
        createRule("metricsRule", cloudwatchLogsRule(false));

        publish("{\"v\":1}");

        verify(logs).createLogStream("/iot/metrics", "metricsRule", REGION);
        List<Map<String, Object>> events = capturedLogEvents();
        assertEquals(1, events.size());
        assertEquals("{\"v\":1}", events.get(0).get("message"));
        assertTrue(events.get(0).get("timestamp") instanceof Long);
    }

    @Test
    void cloudwatchLogsActionReusesAnExistingStream() throws Exception {
        createRule("metricsRule", cloudwatchLogsRule(false));
        doThrow(new AwsException("ResourceAlreadyExistsException", "The specified log stream already exists", 400))
                .when(logs).createLogStream("/iot/metrics", "metricsRule", REGION);

        assertDoesNotThrow(() -> publish("{\"v\":1}"));

        assertEquals("{\"v\":1}", capturedLogEvents().get(0).get("message"));
    }

    @Test
    void cloudwatchLogsActionInBatchModeTakesTimestampAndMessageFromEachArrayElement() throws Exception {
        createRule("metricsRule", cloudwatchLogsRule(true));

        publish("""
            [
              {"timestamp": 1673520691093, "message": "Test message 1"},
              {"timestamp": 1673520692879, "message": "Test message 2"},
              {"timestamp": 1673520693442, "message": "Test message 3"}
            ]
            """);

        List<Map<String, Object>> events = capturedLogEvents();
        assertEquals(List.of("Test message 1", "Test message 2", "Test message 3"),
                events.stream().map(event -> event.get("message")).toList());
        assertEquals(List.of(1673520691093L, 1673520692879L, 1673520693442L),
                events.stream().map(event -> event.get("timestamp")).toList());
    }

    @Test
    void cloudwatchLogsActionInBatchModeFallsBackToThePublishTimeAndTheElementText() throws Exception {
        createRule("metricsRule", cloudwatchLogsRule(true));
        long before = System.currentTimeMillis();

        publish("[{\"v\":1}, {\"timestamp\": \"1673520691093\", \"message\": {\"nested\": true}}, \"plain\"]");

        List<Map<String, Object>> events = capturedLogEvents();
        assertEquals(List.of("{\"v\":1}", "{\"nested\":true}", "\"plain\""),
                events.stream().map(event -> event.get("message")).toList());
        events.forEach(event -> assertTrue((Long) event.get("timestamp") >= before));
    }

    @Test
    void cloudwatchLogsActionFailsWhenTheLogGroupDoesNotExist() throws Exception {
        createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'",
             "actions": [{"cloudwatchLogs": {"logGroupName": "/iot/missing", "roleArn": "arn:aws:iam::000000000000:role/rule"}}],
             "errorAction": %s}
            """.formatted(lambdaErrorAction()));
        doThrow(new AwsException("ResourceNotFoundException", "The specified log group does not exist: /iot/missing", 400))
                .when(logs).createLogStream("/iot/missing", "metricsRule", REGION);

        assertDoesNotThrow(() -> publish("{\"v\":1}"));

        JsonNode failure = capturedInvocationPayload(ERROR_FUNCTION_ARN).get("failures").get(0);
        assertEquals("CloudwatchLogsAction", failure.get("failedAction").asText());
        assertEquals("/iot/missing", failure.get("failedResource").asText());
        verify(logs, never()).putLogEvents(anyString(), anyString(), any(), anyString());
    }

    @Test
    void topicRuleKeepsTheSqlVersionAndTheErrorAction() throws Exception {
        createRule("versionedRule", """
            {
              "sql": "SELECT * FROM 'devices/+/metrics'",
              "awsIotSqlVersion": "2016-03-23",
              "actions": [{"lambda": {"functionArn": "%s"}}],
              "errorAction": {"sqs": {"queueUrl": "%s", "roleArn": "arn:aws:iam::000000000000:role/rule"}}
            }
            """.formatted(FUNCTION_ARN, QUEUE_URL));

        IotTopicRule rule = service.getTopicRule("versionedRule", REGION);

        assertEquals("2016-03-23", rule.getAwsIotSqlVersion());
        assertEquals(QUEUE_URL, mapper.readTree(rule.getErrorActionJson()).at("/sqs/queueUrl").asText());
    }

    @Test
    void topicRuleWithoutTheOptionalMembersStoresNothingForThem() throws Exception {
        createRule("plainRule", sqsThenLambdaRule(null));

        IotTopicRule rule = service.getTopicRule("plainRule", REGION);

        assertNull(rule.getAwsIotSqlVersion());
        assertNull(rule.getErrorActionJson());
    }

    @Test
    void replacingATopicRuleReplacesTheOptionalMembersToo() throws Exception {
        createRule("versionedRule", """
            {"sql": "SELECT * FROM 'a'", "awsIotSqlVersion": "2016-03-23", "actions": [],
             "errorAction": {"sqs": {"queueUrl": "http://dlq", "roleArn": "r"}}}
            """);

        service.replaceTopicRule("versionedRule", mapper.readTree("{\"sql\": \"SELECT * FROM 'b'\", \"actions\": []}"), REGION);

        IotTopicRule rule = service.getTopicRule("versionedRule", REGION);
        assertNull(rule.getAwsIotSqlVersion());
        assertNull(rule.getErrorActionJson());
    }

    private JsonNode capturedDynamoDbItem(String tableName) {
        ArgumentCaptor<ObjectNode> item = ArgumentCaptor.forClass(ObjectNode.class);
        verify(dynamoDb).putItem(eq(tableName), item.capture(), eq(REGION));
        return item.getValue();
    }

    @Test
    void dynamoDBv2ActionMapsNestedValuesToDynamoDbMapsAndLists() throws Exception {
        createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'",
             "actions": [{"dynamoDBv2": {"putItem": {"tableName": "metrics"}, "roleArn": "arn:aws:iam::000000000000:role/rule"}}]}
            """);

        publish("{\"device\": {\"id\": \"d1\", \"ok\": true}, \"readings\": [1.5, \"x\", null]}");

        JsonNode item = capturedDynamoDbItem("metrics");
        assertEquals("d1", item.at("/device/M/id/S").asText());
        assertTrue(item.at("/device/M/ok/BOOL").asBoolean());
        assertEquals("1.5", item.at("/readings/L/0/N").asText());
        assertEquals("x", item.at("/readings/L/1/S").asText());
        assertTrue(item.at("/readings/L/2/NULL").asBoolean());
    }

    @Test
    void aDynamoDbErrorActionKeepsEveryFailureInTheItem() throws Exception {
        createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'",
             "actions": [{"sqs": {"queueUrl": "%s", "roleArn": "arn:aws:iam::000000000000:role/rule"}}],
             "errorAction": {"dynamoDBv2": {"putItem": {"tableName": "rule-errors"}, "roleArn": "arn:aws:iam::000000000000:role/rule"}}}
            """.formatted(QUEUE_URL));
        queueIsMissing();

        publish("{\"v\":1}");

        JsonNode item = capturedDynamoDbItem("rule-errors");
        assertEquals("metricsRule", item.at("/ruleName/S").asText());
        assertEquals(TOPIC, item.at("/topic/S").asText());
        assertEquals("SqsAction", item.at("/failures/L/0/M/failedAction/S").asText());
        assertEquals(QUEUE_URL, item.at("/failures/L/0/M/failedResource/S").asText());
        assertTrue(item.at("/failures/L/0/M/errorMessage/S").asText().contains("does not exist"));
    }

    @Test
    void anActionWithoutATypeOrOfAnUnsupportedTypeIsSkippedAndTheOthersStillRun() throws Exception {
        createRule("metricsRule", """
            {"sql": "SELECT * FROM 'devices/+/metrics'",
             "actions": [
               {"comment": "not an action"},
               {"kafka": {"destinationArn": "arn:aws:iot:us-east-1:000000000000:ruledestination/kafka/x", "topic": "t"}},
               {"lambda": {"functionArn": "%s"}}
             ],
             "errorAction": {"note": "no type either"}}
            """.formatted(FUNCTION_ARN));

        assertDoesNotThrow(() -> publish("{\"v\":1}"));

        verify(lambda).invoke(eq(REGION), eq(FUNCTION_ARN), any(), eq(InvocationType.Event));
        verify(lambda, never()).invoke(eq(REGION), eq(ERROR_FUNCTION_ARN), any(), any());
    }

    @Test
    void aPolicyHoldsAtMostFiveVersionsUntilOneIsDeleted() {
        service.createPolicy("capped", "{\"v\":1}", REGION);
        for (int v = 2; v <= 5; v++) {
            assertEquals(Integer.toString(v),
                    service.createPolicyVersion("capped", "{\"v\":" + v + "}", true, REGION).getVersionId());
        }

        AwsException e = assertThrows(AwsException.class,
                () -> service.createPolicyVersion("capped", "{\"v\":6}", true, REGION));

        assertEquals("VersionsLimitExceededException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals(5, service.listPolicyVersions("capped", REGION).size());
        assertEquals("5", service.getPolicy("capped", REGION).getDefaultVersionId());
        service.deletePolicyVersion("capped", "2", REGION);
        assertEquals("6", service.createPolicyVersion("capped", "{\"v\":6}", true, REGION).getVersionId());
        assertEquals("6", service.getPolicy("capped", REGION).getDefaultVersionId());
    }

    @Test
    void racingVersionCreatesNeverPushAPolicyPastFiveVersions() throws Exception {
        service.createPolicy("raced", "{\"v\":1}", REGION);
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.createPolicyVersion("raced", "{\"v\":true}", false, REGION);
                        return true;
                    } catch (AwsException e) {
                        assertEquals("VersionsLimitExceededException", e.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get()) {
                    created++;
                }
            }
            assertEquals(4, created, "exactly four of eight racing creates fit under the cap");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(5, service.listPolicyVersions("raced", REGION).size());
    }

    @Test
    void deletingTheOldestVersionRemovesTheNumericallySmallestIdAndKeepsTheDefault() {
        service.createPolicy("pruned", "{\"v\":1}", REGION);
        for (int v = 2; v <= 10; v++) {
            if (service.listPolicyVersions("pruned", REGION).size() == IotService.MAX_POLICY_VERSIONS) {
                service.makeRoomForPolicyVersion("pruned", REGION);
            }
            service.createPolicyVersion("pruned", "{\"v\":" + v + "}", true, REGION);
        }
        assertEquals(List.of(6, 7, 8, 9, 10), versionIds("pruned"));

        // Sorted as text, "10" would come before "6"; the oldest version is the numerically smallest id.
        service.makeRoomForPolicyVersion("pruned", REGION);

        assertEquals(List.of(7, 8, 9, 10), versionIds("pruned"));
        assertEquals("10", service.getPolicy("pruned", REGION).getDefaultVersionId());
    }

    @Test
    void deletingTheOldestVersionMovesTheDefaultToTheNewestWhenTheOldestIsTheDefault() {
        service.createPolicy("pinned", "{\"v\":1}", REGION);
        for (int v = 2; v <= 5; v++) {
            service.createPolicyVersion("pinned", "{\"v\":" + v + "}", false, REGION);
        }
        assertEquals("1", service.getPolicy("pinned", REGION).getDefaultVersionId());

        service.makeRoomForPolicyVersion("pinned", REGION);

        assertEquals(List.of(2, 3, 4, 5), versionIds("pinned"));
        assertEquals("5", service.getPolicy("pinned", REGION).getDefaultVersionId());
        assertEquals("{\"v\":5}", service.getPolicy("pinned", REGION).getPolicyDocument());
    }

    @Test
    void makingRoomOnAPolicyStoredWithMoreThanFiveVersionsDeletesDownToFourInOneStep() {
        // A policy persisted before the cap existed can hold more than five versions; the stores
        // hand out the live object, so adding to it is the same as having persisted it that way.
        service.createPolicy("legacy", "{\"v\":1}", REGION);
        for (int v = 2; v <= 5; v++) {
            service.createPolicyVersion("legacy", "{\"v\":" + v + "}", true, REGION);
        }
        IotPolicy stored = service.getPolicy("legacy", REGION);
        List<IotPolicy.PolicyVersion> versions = new ArrayList<>(stored.getVersions());
        for (int v = 6; v <= 8; v++) {
            IotPolicy.PolicyVersion version = new IotPolicy.PolicyVersion();
            version.setVersionId(Integer.toString(v));
            version.setDocument("{\"v\":" + v + "}");
            versions.add(version);
        }
        stored.setVersions(versions);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), versionIds("legacy"));

        service.makeRoomForPolicyVersion("legacy", REGION);

        assertEquals(List.of(5, 6, 7, 8), versionIds("legacy"));
        assertEquals("5", service.getPolicy("legacy", REGION).getDefaultVersionId());
        assertEquals("9", service.createPolicyVersion("legacy", "{\"v\":9}", true, REGION).getVersionId());
    }

    @Test
    void aPolicyDeletedWhileVersionsAreBeingCreatedStaysDeleted() throws Exception {
        for (int round = 0; round < 20; round++) {
            String name = "vanishing-" + round;
            service.createPolicy(name, "{\"v\":1}", REGION);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<?>> outcomes = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        try {
                            service.createPolicyVersion(name, "{\"v\":2}", false, REGION);
                        } catch (AwsException e) {
                            assertEquals("ResourceNotFoundException", e.getErrorCode());
                        }
                        return null;
                    }));
                }
                outcomes.add(pool.submit(() -> {
                    start.await();
                    service.deletePolicy(name, REGION);
                    return null;
                }));
                start.countDown();
                for (Future<?> outcome : outcomes) {
                    outcome.get();
                }
            } finally {
                pool.shutdownNow();
            }
            AwsException e = assertThrows(AwsException.class, () -> service.getPolicy(name, REGION));
            assertEquals("ResourceNotFoundException", e.getErrorCode(), "round " + round + " brought the policy back");
        }
    }

    private List<Integer> versionIds(String policyName) {
        return service.listPolicyVersions(policyName, REGION).stream()
                .map(version -> Integer.parseInt(version.getVersionId()))
                .sorted()
                .toList();
    }
}
