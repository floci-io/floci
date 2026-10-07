package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotShadow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rules engine behaviour of {@link IotService} with in-memory stores and mocked action targets:
 * one failing action never fails the publish or the other actions, the error action receives the
 * failure document, and the {@code firehose} and {@code cloudwatchLogs} actions deliver the payload.
 * Also the five-version cap on a policy, including under racing creates, and the device shadow
 * events that reach MQTT subscribers and topic rules on the AWS response topics.
 */
class IotServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TOPIC = "devices/d1/metrics";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/metrics";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:handler";
    private static final String ERROR_FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:errors";
    private static final String SHADOW_QUEUE_URL = "http://localhost:4566/000000000000/shadow-events";
    private static final String CLASSIC_QUEUE_URL = "http://localhost:4566/000000000000/classic-shadow-events";
    private static final String BUILDING_RULE_SQL = "SELECT *, topic() AS topic, clientid() AS cid "
            + "FROM '$aws/things/+/shadow/name/building/update/accepted' WHERE endswith(clientToken, 'inbound')";
    private static final String BUILDING_UPDATE = "$aws/things/sensor-1/shadow/name/building/update";
    private static final String NESTED_SHADOW_UPDATE = """
        {"state": {"desired": {"lights": {"color": {"r": 255, "g": 255, "b": 255}, "on": true}, "arr": [1, 2], "same": {"x": 1}},
                   "reported": {"lights": {"color": {"r": 255, "g": 0, "b": 255}, "on": true}, "arr": [1, 3], "same": {"x": 1}}}}
        """;
    private static final String NESTED_DELTA = "{\"lights\":{\"color\":{\"g\":255}},\"arr\":[1,2]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SqsService sqs = mock(SqsService.class);
    private final LambdaService lambda = mock(LambdaService.class);
    private final DynamoDbItemAccess dynamoDb = mock(DynamoDbItemAccess.class);
    private final FirehoseService firehose = mock(FirehoseService.class);
    private final CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
    private final IotPublishEventRecorder recorder = new IotPublishEventRecorder();
    private final IotMqttBrokerService broker = mock(IotMqttBrokerService.class);
    private final AccountAwareStorageBackend<IotShadow> shadows = AccountAwareStorageBackend.inMemory(ACCOUNT);
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
                shadows,
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
                recorder,
                broker,
                sqs,
                mock(SnsService.class),
                mock(S3Service.class),
                mock(KinesisService.class),
                new DynamoDbFacade(dynamoDb, mock(DynamoDbTableAccess.class), new RegionResolver(REGION, ACCOUNT)),
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
        service.handlePublish(TOPIC, payload.getBytes(StandardCharsets.UTF_8), true, REGION, null, Runnable::run);
    }

    private JsonNode capturedInvocationPayload(String functionArn) throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambda).invoke(eq(REGION), eq(functionArn), payload.capture(), eq(InvocationType.Event));
        return mapper.readTree(payload.getValue());
    }

    @Test
    void publishRecordsAndStoresTheRetainedMessageAtOnceButRunsTheRulesOnlyOnTheRuleRunner() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(null));
        List<Runnable> deferred = new ArrayList<>();
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);

        service.publish(TOPIC, payload, true, 1, null, "sensor-1", deferred::add);

        assertEquals(TOPIC, recorder.recentEvents().get(0).topic());
        assertEquals(Base64.getEncoder().encodeToString(payload), service.getRetainedMessage(TOPIC).getPayload());
        verifyNoInteractions(sqs, lambda);
        assertEquals(1, deferred.size());

        deferred.get(0).run();

        verify(sqs).sendMessage(eq(QUEUE_URL), eq("{\"v\":1}"), eq(0), eq(REGION));
        verify(lambda).invoke(eq(REGION), eq(FUNCTION_ARN), any(), eq(InvocationType.Event));
    }

    @Test
    void publishWithoutARuleRunnerRunsTheRuleActionsBeforeItReturns() throws Exception {
        createRule("metricsRule", sqsThenLambdaRule(null));

        service.publish(TOPIC, "{\"v\":1}".getBytes(StandardCharsets.UTF_8), false, 0, null, null);

        verify(sqs).sendMessage(eq(QUEUE_URL), eq("{\"v\":1}"), eq(0), eq(REGION));
        verify(lambda).invoke(eq(REGION), eq(FUNCTION_ARN), any(), eq(InvocationType.Event));
    }

    /** MQTT 3.1.1 section 4.7.2, which AWS applies to rule topic filters too: a wildcard first level does not match {@code $}. */
    @Test
    void aRuleWhoseTopicFilterStartsWithAWildcardDoesNotFireForADollarTopic() throws Exception {
        String everything = "http://localhost:4566/000000000000/everything";
        String anyPresence = "http://localhost:4566/000000000000/any-presence";
        String presence = "http://localhost:4566/000000000000/presence";
        createRule("everything", sqsRule("SELECT * FROM '#'", everything));
        createRule("anyPresence", sqsRule("SELECT * FROM '+/events/presence/+/+'", anyPresence));
        createRule("presence", sqsRule("SELECT * FROM '$aws/events/presence/connected/+'", presence));

        service.handlePublish("$aws/events/presence/connected/x", "{\"clientId\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                true, REGION, "x", Runnable::run);

        verify(sqs, never()).sendMessage(eq(everything), anyString(), anyInt(), anyString());
        verify(sqs, never()).sendMessage(eq(anyPresence), anyString(), anyInt(), anyString());
        verify(sqs).sendMessage(eq(presence), eq("{\"clientId\":\"x\"}"), eq(0), eq(REGION));

        service.handlePublish("a/b", "{\"v\":1}".getBytes(StandardCharsets.UTF_8), true, REGION, null, Runnable::run);

        verify(sqs).sendMessage(eq(everything), eq("{\"v\":1}"), eq(0), eq(REGION));
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
        verify(dynamoDb).putItem(eq(new Scope(ACCOUNT, REGION)), eq(tableName), item.capture(),
                isNull(), isNull(), isNull());
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

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    /** The node as it reads back from its JSON text, so a long and an int of the same value compare equal. */
    private JsonNode reparse(JsonNode node) throws Exception {
        return mapper.readTree(node.toString());
    }

    private static String leaf(long timestamp) {
        return "{\"timestamp\":" + timestamp + "}";
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> recordedTopics() {
        return recorder.recentEvents().stream().map(IotPublishEvent::topic).toList();
    }

    private JsonNode recordedPayload(String topic) throws Exception {
        List<IotPublishEvent> matching = recorder.recentEvents().stream()
                .filter(event -> topic.equals(event.topic()))
                .toList();
        assertEquals(1, matching.size(), "events recorded on " + topic);
        return mapper.readTree(matching.get(0).payload());
    }

    private static String sqsRule(String sql, String queueUrl) {
        return """
            {"sql": "%s", "actions": [{"sqs": {"queueUrl": "%s", "roleArn": "arn:aws:iam::000000000000:role/rule"}}]}
            """.formatted(sql, queueUrl);
    }

    private void storeShadow(String thingName, String document) throws Exception {
        IotShadow shadow = new IotShadow();
        shadow.setThingName(thingName);
        shadow.setDocument(document);
        shadow.setVersion(json(document).path("version").asLong());
        shadows.put("shadow:" + REGION + ":" + thingName + ":", shadow);
    }

    @Test
    void shadowUpdateReturnsTheRequestStateAsSentWithItsMetadataVersionTimestampAndClientToken() throws Exception {
        JsonNode request = json("""
            {"state": {"desired": {"color": "blue", "modes": ["eco", "boost"], "schedule": {"start": 8}, "legacy": null},
                       "reported": null},
             "clientToken": "tok-1"}
            """);

        JsonNode accepted = service.updateThingShadow("sensor-1", null, request, REGION);

        long timestamp = accepted.get("timestamp").asLong();
        assertEquals(Set.of("state", "metadata", "version", "timestamp", "clientToken"), fieldNames(accepted));
        assertEquals(request.get("state"), accepted.get("state"));
        assertEquals(json("""
            {"desired": {"color": %1$s, "modes": [%1$s, %1$s], "schedule": {"start": %1$s}, "legacy": %1$s},
             "reported": %1$s}
            """.formatted(leaf(timestamp))), reparse(accepted.get("metadata")));
        assertEquals(1, accepted.get("version").asLong());
        assertEquals("tok-1", accepted.get("clientToken").asText());
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - timestamp) <= 5, "timestamp is in epoch seconds");

        JsonNode second = service.updateThingShadow("sensor-1", null, json("{\"state\":{\"reported\":{\"color\":\"blue\"}}}"), REGION);

        assertEquals(Set.of("state", "metadata", "version", "timestamp"), fieldNames(second));
        assertEquals(2, second.get("version").asLong());
    }

    @Test
    void shadowUpdatePublishesAcceptedDocumentsAndDeltaInThatOrderOnceEach() throws Exception {
        JsonNode accepted = service.updateThingShadow("sensor-1", "building",
                json("{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"tok-1\"}"), REGION);
        long first = accepted.get("timestamp").asLong();

        assertEquals(List.of(BUILDING_UPDATE + "/accepted", BUILDING_UPDATE + "/documents", BUILDING_UPDATE + "/delta"),
                recordedTopics());
        assertEquals(reparse(accepted), recordedPayload(BUILDING_UPDATE + "/accepted"));
        JsonNode currentAfterFirst = json("""
            {"state": {"desired": {"color": "blue"}}, "metadata": {"desired": {"color": %s}}, "version": 1}
            """.formatted(leaf(first)));
        JsonNode documents = recordedPayload(BUILDING_UPDATE + "/documents");
        assertEquals(Set.of("previous", "current", "timestamp", "clientToken"), fieldNames(documents));
        assertTrue(documents.get("previous").isNull(), "previous is JSON null on the first update");
        assertEquals(currentAfterFirst, documents.get("current"));
        assertEquals(first, documents.get("timestamp").asLong());
        assertEquals("tok-1", documents.get("clientToken").asText());
        assertEquals(json("""
            {"version": 1, "timestamp": %d, "state": {"color": "blue"}, "metadata": {"color": %s}, "clientToken": "tok-1"}
            """.formatted(first, leaf(first))), recordedPayload(BUILDING_UPDATE + "/delta"));
        for (String topic : recordedTopics()) {
            ArgumentCaptor<byte[]> fannedOut = ArgumentCaptor.forClass(byte[].class);
            verify(broker, times(1)).publish(eq(topic), fannedOut.capture());
            assertEquals(recordedPayload(topic), mapper.readTree(fannedOut.getValue()));
        }

        recorder.clear();
        JsonNode reported = service.updateThingShadow("sensor-1", "building",
                json("{\"state\":{\"reported\":{\"color\":\"blue\"}}}"), REGION);
        long second = reported.get("timestamp").asLong();

        assertEquals(List.of(BUILDING_UPDATE + "/accepted", BUILDING_UPDATE + "/documents"), recordedTopics(),
                "no delta once reported matches desired");
        JsonNode secondDocuments = recordedPayload(BUILDING_UPDATE + "/documents");
        assertEquals(Set.of("previous", "current", "timestamp"), fieldNames(secondDocuments));
        assertEquals(currentAfterFirst, secondDocuments.get("previous"));
        assertEquals(json("""
            {"state": {"desired": {"color": "blue"}, "reported": {"color": "blue"}},
             "metadata": {"desired": {"color": %s}, "reported": {"color": %s}},
             "version": 2}
            """.formatted(leaf(first), leaf(second))), secondDocuments.get("current"));
    }

    @Test
    void storedMetadataKeepsTheTimestampOfEveryKeyTheUpdateDidNotTouch() throws Exception {
        storeShadow("sensor-2", """
            {"state": {"desired": {"color": "red", "mode": "auto"}, "reported": {"color": "red"}},
             "metadata": {"desired": {"color": {"timestamp": 1000}, "mode": {"timestamp": 1000}},
                          "reported": {"color": {"timestamp": 1000}}},
             "version": 4, "timestamp": 1000}
            """);

        JsonNode accepted = service.updateThingShadow("sensor-2", null,
                json("{\"version\":4,\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);

        long timestamp = accepted.get("timestamp").asLong();
        JsonNode documents = recordedPayload("$aws/things/sensor-2/shadow/update/documents");
        assertEquals(json("""
            {"state": {"desired": {"color": "red", "mode": "auto"}, "reported": {"color": "red"}},
             "metadata": {"desired": {"color": {"timestamp": 1000}, "mode": {"timestamp": 1000}},
                          "reported": {"color": {"timestamp": 1000}}},
             "version": 4}
            """), documents.get("previous"));
        assertEquals(json("""
            {"state": {"desired": {"color": "blue", "mode": "auto"}, "reported": {"color": "red"}},
             "metadata": {"desired": {"color": %s, "mode": {"timestamp": 1000}}, "reported": {"color": {"timestamp": 1000}}},
             "version": 5}
            """.formatted(leaf(timestamp))), documents.get("current"));
        JsonNode delta = recordedPayload("$aws/things/sensor-2/shadow/update/delta");
        assertEquals(json("{\"color\":\"blue\",\"mode\":\"auto\"}"), delta.get("state"));
        assertEquals(json("{\"color\":" + leaf(timestamp) + ",\"mode\":{\"timestamp\":1000}}"), delta.get("metadata"));
    }

    @Test
    void aNullKeyOrSectionRemovesItFromStateAndMetadata() throws Exception {
        storeShadow("sensor-2", """
            {"state": {"desired": {"color": "red", "mode": "auto"}, "reported": {"color": "red"}},
             "metadata": {"desired": {"color": {"timestamp": 1000}, "mode": {"timestamp": 1000}},
                          "reported": {"color": {"timestamp": 1000}}},
             "version": 4, "timestamp": 1000}
            """);

        service.updateThingShadow("sensor-2", null, json("{\"state\":{\"desired\":{\"mode\":null},\"reported\":null}}"), REGION);

        JsonNode current = recordedPayload("$aws/things/sensor-2/shadow/update/documents").get("current");
        assertEquals(json("{\"desired\":{\"color\":\"red\"}}"), current.get("state"));
        assertEquals(json("{\"desired\":{\"color\":{\"timestamp\":1000}}}"), current.get("metadata"));
        JsonNode stored = service.getThingShadow("sensor-2", null, REGION);
        assertEquals(json("{\"desired\":{\"color\":{\"timestamp\":1000}}}"), reparse(stored.get("metadata")));
    }

    @Test
    void aStoredShadowWithoutMetadataIsUpdatedAsIfItsMetadataWereEmpty() throws Exception {
        storeShadow("sensor-3", "{\"state\":{\"desired\":{\"color\":\"red\"}},\"version\":2,\"timestamp\":1000}");

        JsonNode accepted = service.updateThingShadow("sensor-3", null,
                json("{\"state\":{\"reported\":{\"color\":\"red\"}}}"), REGION);

        long timestamp = accepted.get("timestamp").asLong();
        JsonNode documents = recordedPayload("$aws/things/sensor-3/shadow/update/documents");
        assertEquals(json("{\"state\":{\"desired\":{\"color\":\"red\"}},\"metadata\":{},\"version\":2}"),
                documents.get("previous"));
        assertEquals(json("""
            {"state": {"desired": {"color": "red"}, "reported": {"color": "red"}},
             "metadata": {"reported": {"color": %s}}, "version": 3}
            """.formatted(leaf(timestamp))), documents.get("current"));
    }

    @Test
    void aBlankShadowNameIsTheClassicShadow() throws Exception {
        service.updateThingShadow("sensor-6", " ", json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);

        assertEquals("$aws/things/sensor-6/shadow/update/accepted", recordedTopics().get(0));
        assertEquals("blue", service.getThingShadow("sensor-6", null, REGION).at("/state/desired/color").asText());
    }

    @Test
    void aRuleOnTheNamedShadowAcceptedTopicReceivesTheProjectionWithNoMqttClient() throws Exception {
        createRule("buildingRule", sqsRule(BUILDING_RULE_SQL, SHADOW_QUEUE_URL));

        JsonNode accepted = service.updateThingShadow("sensor-1", "building",
                json("{\"state\":{\"desired\":{\"temp\":21}},\"clientToken\":\"job:inbound\"}"), REGION);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqs).sendMessage(eq(SHADOW_QUEUE_URL), body.capture(), eq(0), eq(REGION));
        JsonNode message = mapper.readTree(body.getValue());
        assertEquals(BUILDING_UPDATE + "/accepted", message.get("topic").asText());
        assertEquals("N/A", message.get("cid").asText());
        assertEquals("job:inbound", message.get("clientToken").asText());
        assertEquals(21, message.at("/state/desired/temp").asInt());
        assertEquals(accepted.get("version").asLong(), message.get("version").asLong());
        assertEquals(accepted.get("timestamp").asLong(), message.get("timestamp").asLong());
        assertEquals(reparse(accepted.get("metadata")), message.get("metadata"));
    }

    @Test
    void theNamedShadowRuleSkipsAnotherTokenTheClassicShadowAndAnotherShadowName() throws Exception {
        createRule("buildingRule", sqsRule(BUILDING_RULE_SQL, SHADOW_QUEUE_URL));
        String inbound = "{\"state\":{\"desired\":{\"temp\":21}},\"clientToken\":\"job:inbound\"}";

        service.updateThingShadow("sensor-1", "building",
                json("{\"state\":{\"desired\":{\"temp\":21}},\"clientToken\":\"job:outbound\"}"), REGION);
        service.updateThingShadow("sensor-1", null, json(inbound), REGION);
        service.updateThingShadow("sensor-1", "other", json(inbound), REGION);

        verify(sqs, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void aRuleOnTheClassicShadowAcceptedTopicFiresForTheClassicShadowOnly() throws Exception {
        createRule("classicRule", sqsRule("SELECT * FROM '$aws/things/+/shadow/update/accepted'", CLASSIC_QUEUE_URL));
        String update = "{\"state\":{\"desired\":{\"temp\":21}}}";

        service.updateThingShadow("sensor-1", "building", json(update), REGION);
        verify(sqs, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        service.updateThingShadow("sensor-1", null, json(update), REGION);
        verify(sqs, times(1)).sendMessage(eq(CLASSIC_QUEUE_URL), anyString(), eq(0), eq(REGION));
    }

    @Test
    void shadowEventsReachOnlyTheRulesOfTheShadowsRegion() throws Exception {
        service.createTopicRule("buildingRule", json(sqsRule(BUILDING_RULE_SQL, SHADOW_QUEUE_URL)), "eu-west-1");
        String inbound = "{\"state\":{\"desired\":{\"temp\":21}},\"clientToken\":\"job:inbound\"}";

        service.updateThingShadow("sensor-1", "building", json(inbound), REGION);
        verify(sqs, never()).sendMessage(anyString(), anyString(), anyInt(), anyString());

        service.updateThingShadow("sensor-1", "building", json(inbound), "eu-west-1");
        verify(sqs, times(1)).sendMessage(eq(SHADOW_QUEUE_URL), anyString(), eq(0), eq("eu-west-1"));
    }

    @Test
    void deleteReturnsTheDeletedVersionAndPublishesItOnDeleteAccepted() throws Exception {
        service.updateThingShadow("sensor-4", null, json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);
        service.updateThingShadow("sensor-4", null, json("{\"state\":{\"desired\":{\"color\":\"red\"}}}"), REGION);
        recorder.clear();

        JsonNode deleted = service.deleteThingShadow("sensor-4", null, REGION);

        assertEquals(Set.of("version", "timestamp"), fieldNames(deleted));
        assertEquals(2, deleted.get("version").asLong());
        assertEquals(List.of("$aws/things/sensor-4/shadow/delete/accepted"), recordedTopics());
        assertEquals(reparse(deleted), recordedPayload("$aws/things/sensor-4/shadow/delete/accepted"));
        verify(broker, times(1)).publish(eq("$aws/things/sensor-4/shadow/delete/accepted"), any());
        AwsException e = assertThrows(AwsException.class, () -> service.getThingShadow("sensor-4", null, REGION));
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void getReturnsStateWithDeltaTheStoredMetadataVersionAndTimestampAndPublishesNothing() throws Exception {
        JsonNode accepted = service.updateThingShadow("sensor-5", null, json("""
            {"state": {"desired": {"color": "blue", "mode": "auto"}, "reported": {"color": "red", "mode": "auto"}}}
            """), REGION);
        long timestamp = accepted.get("timestamp").asLong();
        recorder.clear();

        JsonNode shadow = service.getThingShadow("sensor-5", null, REGION);

        assertEquals(Set.of("state", "metadata", "version", "timestamp"), fieldNames(shadow));
        assertEquals(json("""
            {"desired": {"color": "blue", "mode": "auto"}, "reported": {"color": "red", "mode": "auto"},
             "delta": {"color": "blue"}}
            """), reparse(shadow.get("state")));
        assertEquals(json("""
            {"desired": {"color": %1$s, "mode": %1$s}, "reported": {"color": %1$s, "mode": %1$s}}
            """.formatted(leaf(timestamp))), reparse(shadow.get("metadata")));
        assertEquals(1, shadow.get("version").asLong());
        assertTrue(shadow.get("timestamp").asLong() >= timestamp);
        assertTrue(recorder.recentEvents().isEmpty(), "GetThingShadow publishes nothing");
    }

    @Test
    void getLeavesOutTheDeltaWhenDesiredAndReportedAgree() throws Exception {
        service.updateThingShadow("sensor-5", null,
                json("{\"state\":{\"desired\":{\"color\":\"blue\"},\"reported\":{\"color\":\"blue\"}}}"), REGION);

        assertFalse(service.getThingShadow("sensor-5", null, REGION).get("state").has("delta"));
    }

    @Test
    void getComputesTheDeltaThroughNestedObjectsAndComparesArraysWhole() throws Exception {
        service.updateThingShadow("sensor-7", null, json(NESTED_SHADOW_UPDATE), REGION);

        JsonNode shadow = service.getThingShadow("sensor-7", null, REGION);

        assertEquals(json(NESTED_DELTA), reparse(shadow.get("state").get("delta")));
    }

    @Test
    void deltaEventCarriesOnlyTheNestedDifferencesAndTheirStoredMetadata() throws Exception {
        JsonNode accepted = service.updateThingShadow("sensor-7", null, json(NESTED_SHADOW_UPDATE), REGION);
        long timestamp = accepted.get("timestamp").asLong();

        JsonNode delta = recordedPayload("$aws/things/sensor-7/shadow/update/delta");

        assertEquals(json(NESTED_DELTA), delta.get("state"));
        assertEquals(json("""
            {"lights": {"color": {"g": %1$s}}, "arr": [%1$s, %1$s]}
            """.formatted(leaf(timestamp))), delta.get("metadata"));
    }

    @Test
    void racingShadowUpdatesEachGetTheirOwnConsecutiveVersion() throws Exception {
        int writers = 8;
        int updatesEach = 25;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<?>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                String key = "k" + i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < updatesEach; n++) {
                        service.updateThingShadow("raced", null,
                                json("{\"state\":{\"reported\":{\"" + key + "\":" + n + "}}}"), REGION);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(writers * updatesEach, service.getThingShadow("raced", null, REGION).get("version").asLong());
        Set<Long> versions = new LinkedHashSet<>();
        for (IotPublishEvent event : recorder.recentEvents()) {
            if (event.topic().endsWith("/documents")) {
                JsonNode documents = mapper.readTree(event.payload());
                long current = documents.at("/current/version").asLong();
                versions.add(current);
                assertEquals(current - 1, documents.get("previous").isNull() ? 0 : documents.at("/previous/version").asLong());
            }
        }
        assertEquals(writers * updatesEach, versions.size());
    }

    private void mqttShadowRequest(String topic, String payload) {
        Executor inline = Runnable::run;
        service.handleReservedMqttPublish(topic, payload.getBytes(StandardCharsets.UTF_8), inline);
    }

    /** The payload fanned out on the topic, verifying the broker received exactly one publish on it. */
    private JsonNode fannedOut(String topic) throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(broker, times(1)).publish(eq(topic), payload.capture());
        return mapper.readTree(payload.getValue());
    }

    @Test
    void anMqttShadowUpdateFansOutRecordsAndAnswersEachEventOnce() throws Exception {
        String update = "$aws/things/sensor-7/shadow/name/building/update";

        mqttShadowRequest(update, "{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"c-1\"}");

        List<String> topics = List.of(update + "/accepted", update + "/documents", update + "/delta");
        assertEquals(topics, recordedTopics());
        for (String topic : topics) {
            assertEquals(recordedPayload(topic), fannedOut(topic));
            assertEquals("c-1", recordedPayload(topic).get("clientToken").asText());
        }
        verify(broker, times(3)).publish(anyString(), any());
        assertEquals("blue", service.getThingShadow("sensor-7", "building", REGION).at("/state/desired/color").asText());
    }

    @Test
    void anMqttShadowUpdateRunsTheRulesOnTheGivenRuleRunnerWithNoMqttClient() throws Exception {
        createRule("classicRule", sqsRule(
                "SELECT clientid() AS cid, topic() AS topic FROM '$aws/things/+/shadow/update/accepted'", CLASSIC_QUEUE_URL));
        List<Runnable> deferred = new ArrayList<>();

        service.handleReservedMqttPublish("$aws/things/sensor-10/shadow/update",
                "{\"state\":{\"desired\":{\"color\":\"blue\"}}}".getBytes(StandardCharsets.UTF_8), deferred::add);

        verifyNoInteractions(sqs);
        assertEquals(3, deferred.size(), "one rule evaluation per event");
        deferred.forEach(Runnable::run);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqs).sendMessage(eq(CLASSIC_QUEUE_URL), body.capture(), eq(0), eq(REGION));
        assertEquals(json("{\"cid\":\"N/A\",\"topic\":\"$aws/things/sensor-10/shadow/update/accepted\"}"),
                mapper.readTree(body.getValue()));
    }

    @Test
    void anMqttShadowGetPublishesGetAcceptedWithTheRequestClientToken() throws Exception {
        service.updateThingShadow("sensor-8", null, json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);
        recorder.clear();
        clearInvocations(broker);

        mqttShadowRequest("$aws/things/sensor-8/shadow/get", "{\"clientToken\":\"g-1\"}");

        JsonNode accepted = fannedOut("$aws/things/sensor-8/shadow/get/accepted");
        assertEquals(Set.of("state", "metadata", "version", "timestamp", "clientToken"), fieldNames(accepted));
        assertEquals("blue", accepted.at("/state/delta/color").asText());
        assertEquals("g-1", accepted.get("clientToken").asText());
        assertEquals(List.of("$aws/things/sensor-8/shadow/get/accepted"), recordedTopics());
    }

    @Test
    void anMqttShadowDeletePublishesDeleteAcceptedWithTheVersionAndClientToken() throws Exception {
        service.updateThingShadow("sensor-8", null, json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);
        service.updateThingShadow("sensor-8", null, json("{\"state\":{\"desired\":{\"color\":\"red\"}}}"), REGION);
        recorder.clear();
        clearInvocations(broker);

        mqttShadowRequest("$aws/things/sensor-8/shadow/delete", "{\"clientToken\":\"d-1\"}");

        JsonNode accepted = fannedOut("$aws/things/sensor-8/shadow/delete/accepted");
        assertEquals(Set.of("version", "timestamp", "clientToken"), fieldNames(accepted));
        assertEquals(2, accepted.get("version").asLong());
        assertEquals("d-1", accepted.get("clientToken").asText());
        assertEquals(List.of("$aws/things/sensor-8/shadow/delete/accepted"), recordedTopics());
    }

    @Test
    void anMqttVersionConflictPublishesRejectedWithTheHttpStatusAndClientToken() throws Exception {
        service.updateThingShadow("sensor-9", null, json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);
        recorder.clear();
        clearInvocations(broker);

        mqttShadowRequest("$aws/things/sensor-9/shadow/update",
                "{\"version\":7,\"state\":{\"desired\":{\"color\":\"red\"}},\"clientToken\":\"c-9\"}");

        assertEquals(json("{\"code\":409,\"message\":\"Version conflict\",\"clientToken\":\"c-9\"}"),
                fannedOut("$aws/things/sensor-9/shadow/update/rejected"));
        assertEquals(List.of("$aws/things/sensor-9/shadow/update/rejected"), recordedTopics());
        verify(broker, times(1)).publish(anyString(), any());
    }

    @Test
    void restVersionConflictIsVersionConflictExceptionWithStatus409() throws Exception {
        service.updateThingShadow("sensor-9", null, json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"), REGION);
        recorder.clear();

        AwsException e = assertThrows(AwsException.class, () -> service.updateThingShadow("sensor-9", null,
                json("{\"version\":7,\"state\":{\"desired\":{\"color\":\"red\"}}}"), REGION));

        assertEquals("VersionConflictException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Version conflict", e.getMessage());
        assertTrue(recorder.recentEvents().isEmpty(), "a REST error publishes nothing");
    }

    @Test
    void malformedMqttShadowJsonPublishesRejectedInvalidJsonWithoutAClientToken() throws Exception {
        mqttShadowRequest("$aws/things/sensor-9/shadow/update", "{\"clientToken\":\"c-9\",");

        assertEquals(json("{\"code\":400,\"message\":\"Invalid JSON\"}"),
                fannedOut("$aws/things/sensor-9/shadow/update/rejected"));
    }

    @Test
    void anMqttGetOfAMissingShadowPublishesRejected404WithTheClientToken() throws Exception {
        mqttShadowRequest("$aws/things/nobody/shadow/name/building/get", "{\"clientToken\":\"g-2\"}");

        JsonNode rejected = fannedOut("$aws/things/nobody/shadow/name/building/get/rejected");
        assertEquals(Set.of("code", "message", "clientToken"), fieldNames(rejected));
        assertEquals(404, rejected.get("code").asInt());
        assertEquals("g-2", rejected.get("clientToken").asText());
    }

    @Test
    void anUnsupportedMqttShadowOperationPublishesRejected400WithTheClientToken() throws Exception {
        mqttShadowRequest("$aws/things/sensor-9/shadow/name/building/list", "{\"clientToken\":\"u-1\"}");

        assertEquals(json("{\"code\":400,\"message\":\"Unsupported shadow operation: list\",\"clientToken\":\"u-1\"}"),
                fannedOut("$aws/things/sensor-9/shadow/name/building/list/rejected"));
    }

    @Test
    void anMqttPublishOnAShadowResponseTopicIsNotProcessedAsARequest() throws Exception {
        mqttShadowRequest("$aws/things/sensor-11/shadow/update/accepted", "{\"state\":{\"desired\":{\"color\":\"blue\"}}}");
        mqttShadowRequest("$aws/things/sensor-11/shadow/name/building/update/delta", "{\"state\":{\"color\":\"blue\"}}");

        verifyNoInteractions(broker);
        assertTrue(recorder.recentEvents().isEmpty());
        assertThrows(AwsException.class, () -> service.getThingShadow("sensor-11", null, REGION));
    }
}
