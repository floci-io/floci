package io.floci.aws.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.pipes.model.DesiredState;
import io.floci.aws.services.pipes.model.Pipe;
import io.floci.aws.services.pipes.model.PipeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PipesServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SQS_SOURCE = "arn:aws:sqs:us-east-1:000000000000:source";
    private static final String STANDARD_TARGET = "arn:aws:sqs:us-east-1:000000000000:target";
    private static final String FIFO_TARGET = "arn:aws:sqs:us-east-1:000000000000:target.fifo";
    private static final String KINESIS_TARGET = "arn:aws:kinesis:us-east-1:000000000000:stream/target-stream";
    private static final String PIPE_ROLE = "arn:aws:iam::000000000000:role/role";

    private AccountAwareStorageBackend<Pipe> storage;
    private PipesService pipesService;

    @BeforeEach
    void setUp() {
        storage = AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.doReturn(storage).when(storageFactory)
                .create(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        PipesPoller poller = Mockito.mock(PipesPoller.class);
        pipesService = new PipesService(storageFactory, regionResolver, poller);
    }

    private static JsonNode sqsParameters(String groupId, String dedupId) {
        ObjectNode tp = MAPPER.createObjectNode();
        ObjectNode sqs = tp.putObject("SqsQueueParameters");
        if (groupId != null) {
            sqs.put("MessageGroupId", groupId);
        }
        if (dedupId != null) {
            sqs.put("MessageDeduplicationId", dedupId);
        }
        return tp;
    }

    private Pipe createSqsPipe(String target, JsonNode targetParameters) {
        return pipesService.createPipe("sqs-pipe", SQS_SOURCE, target, PIPE_ROLE,
                null, null, null, null, targetParameters, null, null, "us-east-1");
    }

    @SuppressWarnings("unchecked")
    private static void assertFields(AwsException ex, String... namesAndMessages) throws Exception {
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        ArrayNode expectedFields = MAPPER.createArrayNode();
        List<Map<String, String>> expectedList = new ArrayList<>();
        for (int i = 0; i < namesAndMessages.length; i += 2) {
            expectedFields.addObject().put("Name", namesAndMessages[i]).put("Message", namesAndMessages[i + 1]);
            Map<String, String> field = new LinkedHashMap<>();
            field.put("name", namesAndMessages[i]);
            field.put("message", namesAndMessages[i + 1]);
            expectedList.add(field);
        }
        ObjectNode expected = MAPPER.createObjectNode();
        expected.set("ValidationErrorFields", expectedFields);
        assertEquals(expected.toString(), ex.getMessage());
        assertEquals(expectedList, ex.getExtendedData().get("fieldList"));
    }

    @Test
    void sqsFifoTargetWithoutSqsQueueParametersIsRefused() throws Exception {
        AwsException ex = assertThrows(AwsException.class, () -> createSqsPipe(FIFO_TARGET, null));
        assertFields(ex, "TargetParameters.SqsQueueParameters", "Missing required target parameter.");

        ex = assertThrows(AwsException.class,
                () -> createSqsPipe(FIFO_TARGET, MAPPER.createObjectNode().put("InputTemplate", "x")));
        assertFields(ex, "TargetParameters.SqsQueueParameters", "Missing required target parameter.");
    }

    @Test
    void sqsFifoTargetWithoutMessageGroupIdIsRefused() throws Exception {
        AwsException ex = assertThrows(AwsException.class, () -> createSqsPipe(FIFO_TARGET, sqsParameters(null, null)));
        assertFields(ex, "MessageGroupId", "MessageGroupId is required for FIFO (first-in-first-out) queues.");
    }

    @Test
    void sqsFifoTargetWithOnlyAnInvalidDeduplicationPathReportsBoth() throws Exception {
        AwsException ex = assertThrows(AwsException.class,
                () -> createSqsPipe(FIFO_TARGET, sqsParameters(null, "$.x[")));
        assertFields(ex,
                "MessageDeduplicationId", "Invalid JSON path $.x[ provided in the service target parameters.",
                "MessageGroupId", "MessageGroupId is required for FIFO (first-in-first-out) queues.");
    }

    @Test
    void sqsStandardTargetWithInvalidPathsReportsGroupDeduplicationAndFifoOnly() throws Exception {
        AwsException ex = assertThrows(AwsException.class,
                () -> createSqsPipe(STANDARD_TARGET, sqsParameters("$.g[", "$.x[")));
        assertFields(ex,
                "MessageGroupId", "Invalid JSON path $.g[ provided in the service target parameters.",
                "MessageDeduplicationId", "Invalid JSON path $.x[ provided in the service target parameters.",
                "MessageDeduplicationId", "Parameter only applies to FIFO (first-in-first-out) queues.");
    }

    @Test
    void sqsStandardTargetWithDeduplicationIdIsRefused() throws Exception {
        AwsException ex = assertThrows(AwsException.class,
                () -> createSqsPipe(STANDARD_TARGET, sqsParameters("g", "d")));
        assertFields(ex, "MessageDeduplicationId", "Parameter only applies to FIFO (first-in-first-out) queues.");
    }

    @Test
    void sqsTargetsAreAcceptedWhenTheirParametersFitTheQueueKind() {
        assertNotNull(createSqsPipe(STANDARD_TARGET, null));
        pipesService.deletePipe("sqs-pipe", "us-east-1");
        assertNotNull(createSqsPipe(STANDARD_TARGET, sqsParameters("g", null)));
        pipesService.deletePipe("sqs-pipe", "us-east-1");
        assertNotNull(createSqsPipe(FIFO_TARGET, sqsParameters("", null)));
        pipesService.deletePipe("sqs-pipe", "us-east-1");
        assertNotNull(createSqsPipe(FIFO_TARGET, sqsParameters("$.g", "$.d")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.body[", "$.a b", "$.", "$..a", "$.a.*", "$.a[-1]"})
    void sqsMessageGroupIdThatStartsWithADollarDotButIsNotAPathIsRefused(String value) throws Exception {
        AwsException ex = assertThrows(AwsException.class, () -> createSqsPipe(FIFO_TARGET, sqsParameters(value, null)));
        assertFields(ex, "MessageGroupId",
                "Invalid JSON path " + value + " provided in the service target parameters.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.a[0]", "$.a[*]", "$.a-b", "$.a/b", "$", "$x", "$[0]", "$['a']", "a$.b"})
    void sqsMessageGroupIdThatIsAPathOrNotPathLikeIsAccepted(String value) {
        assertNotNull(createSqsPipe(FIFO_TARGET, sqsParameters(value, null)));
    }

    @Test
    void sqsUpdateWithOnlyTargetParametersIsCheckedAgainstTheStoredTarget() throws Exception {
        createSqsPipe(FIFO_TARGET, sqsParameters("g", null));

        AwsException ex = assertThrows(AwsException.class, () -> pipesService.updatePipe("sqs-pipe", null, null,
                null, null, null, null, sqsParameters(null, null), null, "us-east-1"));
        assertFields(ex, "MessageGroupId", "MessageGroupId is required for FIFO (first-in-first-out) queues.");
    }

    @Test
    void sqsUpdateWithANewTargetIsCheckedAgainstTheStoredParameters() throws Exception {
        createSqsPipe(STANDARD_TARGET, null);

        AwsException ex = assertThrows(AwsException.class, () -> pipesService.updatePipe("sqs-pipe", FIFO_TARGET,
                null, null, null, null, null, null, null, "us-east-1"));
        assertFields(ex, "TargetParameters.SqsQueueParameters", "Missing required target parameter.");
    }

    private static JsonNode kinesisParameters(String partitionKey) {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("KinesisStreamParameters").put("PartitionKey", partitionKey);
        return tp;
    }

    private Pipe createKinesisPipe(JsonNode targetParameters) {
        return pipesService.createPipe("kinesis-pipe", SQS_SOURCE, KINESIS_TARGET, PIPE_ROLE,
                null, null, null, null, targetParameters, null, null, "us-east-1");
    }

    private static final String MISSING_BLOCK = "Missing required target parameter.";
    private static final String CROSS_ACCOUNT = "Creating cross-account pipe is not permitted.";

    private void assertSchemaError(JsonNode targetParameters, String message) {
        AwsException ex = assertThrows(AwsException.class, () -> createKinesisPipe(targetParameters));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals(message, ex.getMessage());
        assertNull(ex.getExtendedData());
    }

    private static JsonNode partitionKeyOf(JsonNode value) {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("KinesisStreamParameters").set("PartitionKey", value);
        return tp;
    }

    private AwsException createKinesisPipeTo(String target, JsonNode targetParameters) {
        return assertThrows(AwsException.class, () -> pipesService.createPipe("kinesis-pipe", SQS_SOURCE, target,
                PIPE_ROLE, null, null, null, null, targetParameters, null, null, "us-east-1"));
    }

    @Test
    void kinesisTargetWithoutKinesisStreamParametersIsRefused() throws Exception {
        AwsException ex = assertThrows(AwsException.class, () -> createKinesisPipe(null));
        assertFields(ex, "TargetParameters.KinesisStreamParameters", MISSING_BLOCK);

        ex = assertThrows(AwsException.class,
                () -> createKinesisPipe(MAPPER.createObjectNode().put("InputTemplate", "x")));
        assertFields(ex, "TargetParameters.KinesisStreamParameters", MISSING_BLOCK);

        ObjectNode nullBlock = MAPPER.createObjectNode();
        nullBlock.putNull("KinesisStreamParameters");
        ex = assertThrows(AwsException.class, () -> createKinesisPipe(nullBlock));
        assertFields(ex, "TargetParameters.KinesisStreamParameters", MISSING_BLOCK);
    }

    @Test
    void kinesisPartitionKeyAbsentIsASchemaError() {
        assertSchemaError(MAPPER.createObjectNode().set("KinesisStreamParameters", MAPPER.createObjectNode()),
                "[object has missing required properties ([\"PartitionKey\"])]");
    }

    @Test
    void kinesisPartitionKeyOfAnotherJsonTypeIsASchemaError() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        Map<String, JsonNode> byType = new LinkedHashMap<>();
        byType.put("null", f.nullNode());
        byType.put("integer", f.numberNode(5));
        byType.put("number", f.numberNode(1.5));
        byType.put("boolean", f.booleanNode(true));
        byType.put("object", f.objectNode());
        byType.put("array", f.arrayNode());
        for (Map.Entry<String, JsonNode> entry : byType.entrySet()) {
            assertSchemaError(partitionKeyOf(entry.getValue()), "[instance type (" + entry.getKey()
                    + ") does not match any allowed primitive type (allowed: [\"string\"])]");
        }
    }

    @Test
    void kinesisPartitionKeyIsCountedInCodePoints() {
        String tooLong = "a".repeat(257);
        assertSchemaError(kinesisParameters(tooLong),
                "[string \"" + tooLong + "\" is too long (length: 257, maximum allowed: 256)]");

        assertNotNull(createKinesisPipe(kinesisParameters("a".repeat(256))));
    }

    @Test
    void kinesisPartitionKeyOf200EmojiIsAccepted() {
        assertNotNull(createKinesisPipe(kinesisParameters("\uD83D\uDE00".repeat(200))));
    }

    @Test
    void kinesisTargetThatIsNotAPlainStreamArnIsRefused() throws Exception {
        for (String target : List.of(
                "arn:aws:kinesis:us-east-1:000000000000:stream/s/consumer/c:1700000000",
                "arn:aws:kinesis:us-east-1:000000000000:s",
                "arn:aws:kinesis:us-east-1:000000000000:stream/")) {
            assertFields(createKinesisPipeTo(target, kinesisParameters("k")),
                    "Target", "Invalid pipe target ARN");
        }
    }

    @Test
    void kinesisTargetInAnotherAccountIsRefused() throws Exception {
        assertFields(createKinesisPipeTo("arn:aws:kinesis:us-east-1:111111111111:stream/s", kinesisParameters("k")),
                "Target", CROSS_ACCOUNT);
    }

    @Test
    void kinesisTargetErrorsComeBeforeTheMissingBlockAndThePartitionKeyPath() throws Exception {
        String otherAccount = "arn:aws:kinesis:us-east-1:111111111111:stream/s";
        String consumer = "arn:aws:kinesis:us-east-1:000000000000:stream/s/consumer/c:1700000000";

        assertFields(createKinesisPipeTo(otherAccount, null),
                "Target", CROSS_ACCOUNT, "TargetParameters.KinesisStreamParameters", MISSING_BLOCK);
        assertFields(createKinesisPipeTo(consumer, null),
                "Target", "Invalid pipe target ARN", "TargetParameters.KinesisStreamParameters", MISSING_BLOCK);
        assertFields(createKinesisPipeTo(otherAccount, kinesisParameters("$.a b")),
                "Target", CROSS_ACCOUNT, "PartitionKey",
                "Invalid JSON path $.a b provided in the service target parameters.");
    }

    @Test
    void kinesisSchemaErrorIsThrownAloneBeforeTheTargetChecks() {
        AwsException ex = createKinesisPipeTo(
                "arn:aws:kinesis:us-east-1:000000000000:stream/s/consumer/c:1700000000",
                MAPPER.createObjectNode().set("KinesisStreamParameters", MAPPER.createObjectNode()));

        assertEquals("[object has missing required properties ([\"PartitionKey\"])]", ex.getMessage());
        assertNull(ex.getExtendedData());
    }

    @Test
    void kinesisUpdateRunsTheSameChecks() {
        createKinesisPipe(kinesisParameters("k"));

        AwsException ex = assertThrows(AwsException.class, () -> pipesService.updatePipe("kinesis-pipe", null, null,
                null, null, null, null, MAPPER.createObjectNode().set("KinesisStreamParameters",
                        MAPPER.createObjectNode()), null, "us-east-1"));
        assertEquals("[object has missing required properties ([\"PartitionKey\"])]", ex.getMessage());

        AwsException crossAccount = assertThrows(AwsException.class, () -> pipesService.updatePipe("kinesis-pipe",
                "arn:aws:kinesis:us-east-1:111111111111:stream/s", null, null, null, null, null,
                kinesisParameters("k"), null, "us-east-1"));
        assertTrue(crossAccount.getMessage().contains(CROSS_ACCOUNT), crossAccount.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.body[", "$.a b", "$.", "$..a", "$.a.*", "$.a[-1]"})
    void kinesisPartitionKeyThatStartsWithADollarDotButIsNotAPathIsRefused(String value) throws Exception {
        AwsException ex = assertThrows(AwsException.class, () -> createKinesisPipe(kinesisParameters(value)));
        assertFields(ex, "PartitionKey",
                "Invalid JSON path " + value + " provided in the service target parameters.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "", "$.a[0]", "$.a[*]", "$.a-b", "$.a/b", "$", "$x", "$[0]", "$['a']", "a$.b"})
    void kinesisPartitionKeyThatIsAPathOrNotPathLikeIsAccepted(String value) {
        assertNotNull(createKinesisPipe(kinesisParameters(value)));
    }

    @Test
    void kinesisUpdateWithOnlyTargetParametersIsCheckedAgainstTheStoredTarget() throws Exception {
        createKinesisPipe(kinesisParameters("k"));

        AwsException ex = assertThrows(AwsException.class, () -> pipesService.updatePipe("kinesis-pipe", null, null,
                null, null, null, null, kinesisParameters("$.a["), null, "us-east-1"));
        assertFields(ex, "PartitionKey", "Invalid JSON path $.a[ provided in the service target parameters.");

        AwsException missing = assertThrows(AwsException.class, () -> pipesService.updatePipe("kinesis-pipe", null,
                null, null, null, null, null, MAPPER.createObjectNode().put("InputTemplate", "x"), null,
                "us-east-1"));
        assertFields(missing, "TargetParameters.KinesisStreamParameters", "Missing required target parameter.");
    }

    @Test
    void createPipe() {
        Pipe pipe = pipesService.createPipe("test-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source-queue",
                "arn:aws:sqs:us-east-1:000000000000:target-queue",
                "arn:aws:iam::000000000000:role/pipe-role",
                "A test pipe", DesiredState.RUNNING, null,
                null, null, null, Map.of("env", "test"), "us-east-1");

        assertNotNull(pipe);
        assertEquals("test-pipe", pipe.getName());
        assertEquals("arn:aws:pipes:us-east-1:000000000000:pipe/test-pipe", pipe.getArn());
        assertEquals(DesiredState.RUNNING, pipe.getDesiredState());
        assertEquals(PipeState.RUNNING, pipe.getCurrentState());
        assertEquals("A test pipe", pipe.getDescription());
        assertNotNull(pipe.getCreationTime());
        assertNotNull(pipe.getLastModifiedTime());
        assertEquals("test", pipe.getTags().get("env"));
    }

    @Test
    void createPipeDefaultsDesiredStateToRunning() {
        Pipe pipe = pipesService.createPipe("pipe-no-state",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        assertEquals(DesiredState.RUNNING, pipe.getDesiredState());
        assertEquals(PipeState.RUNNING, pipe.getCurrentState());
    }

    @Test
    void createPipeDuplicateNameThrowsConflict() {
        pipesService.createPipe("dup-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("dup-pipe",
                        "arn:aws:sqs:us-east-1:000000000000:source",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null, null, null, null, null, "us-east-1"));
        assertEquals("ConflictException", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void createPipeMissingRequiredFieldsThrowsValidation() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe(null, "source", "target", "role",
                        null, null, null, null, null, null, null, "us-east-1"));
        assertEquals("ValidationException", ex.getErrorCode());

        ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("name", null, "target", "role",
                        null, null, null, null, null, null, null, "us-east-1"));
        assertEquals("ValidationException", ex.getErrorCode());

        ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("name", "source", null, "role",
                        null, null, null, null, null, null, null, "us-east-1"));
        assertEquals("ValidationException", ex.getErrorCode());

        ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("name", "source", "target", null,
                        null, null, null, null, null, null, null, "us-east-1"));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createPipeWithManagedKafkaSourceRequiresTopicParameters() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("msk-pipe",
                        "arn:aws:kafka:us-east-1:000000000000:cluster/test/uuid",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null, null, null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void createPipeWithSelfManagedKafkaSourceRequiresTopicParameters() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("smk-pipe",
                        "smk://localhost:9092",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        mapper.readTree("{\"SelfManagedKafkaParameters\":{}}"),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void describePipe() {
        pipesService.createPipe("my-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        Pipe pipe = pipesService.describePipe("my-pipe", "us-east-1");
        assertEquals("my-pipe", pipe.getName());
    }

    @Test
    void describePipeNotFoundThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.describePipe("nonexistent", "us-east-1"));
        assertEquals("NotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void updatePipe() {
        pipesService.createPipe("update-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                "original", DesiredState.RUNNING, null, null, null, null, null, "us-east-1");

        Pipe updated = pipesService.updatePipe("update-pipe",
                "arn:aws:sqs:us-east-1:000000000000:new-target",
                null, "updated desc", DesiredState.STOPPED, null, null, null, null, "us-east-1");

        assertEquals("arn:aws:sqs:us-east-1:000000000000:new-target", updated.getTarget());
        assertEquals("updated desc", updated.getDescription());
        assertEquals(DesiredState.STOPPED, updated.getDesiredState());
        assertEquals(PipeState.STOPPED, updated.getCurrentState());
    }

    /**
     * The UpdatePipe contract: a property the caller omits keeps the value the pipe holds. It is
     * the contract the CloudFormation update path relies on, and restorePipe is what reads an
     * omitted property as a value to clear.
     */
    @Test
    void updatePipeLeavesAnOmittedPropertyAlone() {
        pipesService.createPipe("partial-update-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/original-role",
                "the original description", DesiredState.RUNNING,
                "arn:aws:lambda:us-east-1:000000000000:function:original-enrichment",
                null, null, null, null, "us-east-1");

        Pipe updated = pipesService.updatePipe("partial-update-pipe",
                "arn:aws:sqs:us-east-1:000000000000:new-target",
                null, null, null, null, null, null, null, "us-east-1");

        assertEquals("arn:aws:sqs:us-east-1:000000000000:new-target", updated.getTarget());
        assertEquals("arn:aws:iam::000000000000:role/original-role", updated.getRoleArn());
        assertEquals("the original description", updated.getDescription());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:original-enrichment",
                updated.getEnrichment());
        assertEquals(DesiredState.RUNNING, updated.getDesiredState());
        assertEquals(PipeState.RUNNING, updated.getCurrentState());
    }

    /**
     * restorePipe is handed a whole configuration, so an omitted property is one the pipe must
     * stop carrying. The desired state is the exception: currentState is read off it, so a null
     * leaves both as they are instead of contradicting each other.
     */
    @Test
    void restorePipeClearsAnOmittedPropertyAndKeepsTheDesiredState() {
        pipesService.createPipe("restore-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/original-role",
                "the original description", DesiredState.STOPPED,
                "arn:aws:lambda:us-east-1:000000000000:function:original-enrichment",
                null, null, null, null, "us-east-1");

        Pipe restored = pipesService.restorePipe("restore-pipe",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/original-role",
                null, null, null, null, null, null, "us-east-1");

        assertNull(restored.getDescription());
        assertNull(restored.getEnrichment());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:target", restored.getTarget());
        assertEquals("arn:aws:iam::000000000000:role/original-role", restored.getRoleArn());
        assertEquals(DesiredState.STOPPED, restored.getDesiredState());
        assertEquals(PipeState.STOPPED, restored.getCurrentState());
    }

    /**
     * A restore puts back a configuration that was accepted when it was written, so it is not run
     * through the source validation again. A persisted pipe can predate a rule added since, and a
     * CloudFormation rollback that re-validated its snapshot would fail on the very pipe it is
     * putting back. The pipe is seeded straight into storage, as one written before the Kafka
     * TopicName check existed would be.
     */
    @Test
    void restorePipeDoesNotRevalidateAnAlreadyAcceptedConfiguration() {
        JsonNode legacyParameters = sourceParameters("""
                {"SelfManagedKafkaParameters":{"BatchSize":10}}""");
        Pipe legacy = new Pipe();
        legacy.setName("legacy-pipe");
        legacy.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/legacy-pipe");
        legacy.setSource("smk://broker:9092");
        legacy.setTarget("arn:aws:sqs:us-east-1:000000000000:target");
        legacy.setRoleArn("arn:aws:iam::000000000000:role/role");
        legacy.setDesiredState(DesiredState.RUNNING);
        legacy.setCurrentState(PipeState.RUNNING);
        legacy.setSourceParameters(legacyParameters);
        storage.put("us-east-1::legacy-pipe", legacy);

        Pipe restored = pipesService.restorePipe("legacy-pipe",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, legacyParameters, null, null, "us-east-1");

        assertEquals(legacyParameters, restored.getSourceParameters());
        assertEquals(legacyParameters,
                pipesService.describePipe("legacy-pipe", "us-east-1").getSourceParameters());
    }

    private static final String REDSHIFT_CLUSTER = "arn:aws:redshift:us-east-1:000000000000:cluster:analytics";

    private Pipe createRedshiftPipe(String name, JsonNode targetParameters) {
        return pipesService.createPipe(name, "arn:aws:sqs:us-east-1:000000000000:source", REDSHIFT_CLUSTER,
                "arn:aws:iam::000000000000:role/role", null, null, null, null, targetParameters, null, null,
                "us-east-1");
    }

    @Test
    void createPipeWithARedshiftTargetKeepsItsSqls() {
        JsonNode parameters = sourceParameters(
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[\"SELECT 1\"]}}");

        Pipe pipe = createRedshiftPipe("redshift-pipe", parameters);

        assertEquals(parameters, pipesService.describePipe("redshift-pipe", "us-east-1").getTargetParameters());
        assertEquals(REDSHIFT_CLUSTER, pipe.getTarget());
    }

    @Test
    void createPipeWithARedshiftTargetRequiresBetweenOneAndFortySqls() {
        for (String parameters : List.of(
                "null",
                "{}",
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\"}}",
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[]}}",
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":["
                        + "\"SELECT 1\",".repeat(40) + "\"SELECT 1\"]}}",
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[1]}}")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> createRedshiftPipe("bad-redshift-pipe", sourceParameters(parameters)),
                    parameters);
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertThrows(AwsException.class, () -> pipesService.describePipe("bad-redshift-pipe", "us-east-1"));
    }

    @Test
    void createPipeWithARedshiftTargetRequiresADatabase() {
        for (String parameters : List.of(
                "{\"RedshiftDataParameters\":{\"Sqls\":[\"SELECT 1\"]}}",
                "{\"RedshiftDataParameters\":{\"Database\":\"\",\"Sqls\":[\"SELECT 1\"]}}",
                "{\"RedshiftDataParameters\":{\"Database\":1,\"Sqls\":[\"SELECT 1\"]}}")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> createRedshiftPipe("no-database-pipe", sourceParameters(parameters)),
                    parameters);
            assertEquals("ValidationException", error.getErrorCode());
            assertTrue(error.getMessage().contains("Database"), parameters);
        }
    }

    @Test
    void createPipeRefusesARedshiftSqlOverTheLengthLimit() {
        JsonNode parameters = sourceParameters("{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[\""
                + "x".repeat(100_001) + "\"]}}");

        AwsException error = assertThrows(AwsException.class, () -> createRedshiftPipe("long-sql-pipe", parameters));

        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void updatePipeValidatesTheRedshiftTargetItWillHave() {
        createRedshiftPipe("update-redshift-pipe", sourceParameters(
                "{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[\"SELECT 1\"]}}"));

        AwsException error = assertThrows(AwsException.class, () -> pipesService.updatePipe("update-redshift-pipe",
                null, null, null, null, null, null,
                sourceParameters("{\"RedshiftDataParameters\":{\"Database\":\"dev\",\"Sqls\":[]}}"),
                null, "us-east-1"));
        assertEquals("ValidationException", error.getErrorCode());

        // Leaving the target parameters out keeps the valid ones.
        pipesService.updatePipe("update-redshift-pipe", null, null, "now described", null, null, null, null, null,
                "us-east-1");
        assertEquals("now described", pipesService.describePipe("update-redshift-pipe", "us-east-1").getDescription());
    }

    @Test
    void deletePipe() {
        pipesService.createPipe("del-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        pipesService.deletePipe("del-pipe", "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.describePipe("del-pipe", "us-east-1"));
        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteNonexistentPipeThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.deletePipe("ghost", "us-east-1"));
        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void listPipes() {
        pipesService.createPipe("pipe-a",
                "arn:aws:sqs:us-east-1:000000000000:source-a",
                "arn:aws:sqs:us-east-1:000000000000:target-a",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.RUNNING, null, null, null, null, null, "us-east-1");
        pipesService.createPipe("pipe-b",
                "arn:aws:sqs:us-east-1:000000000000:source-b",
                "arn:aws:sqs:us-east-1:000000000000:target-b",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.STOPPED, null, null, null, null, null, "us-east-1");

        List<Pipe> all = pipesService.listPipes(null, null, null, null, null, "us-east-1");
        assertEquals(2, all.size());

        List<Pipe> filtered = pipesService.listPipes("pipe-a", null, null, null, null, "us-east-1");
        assertEquals(1, filtered.size());
        assertEquals("pipe-a", filtered.get(0).getName());
    }

    @Test
    void listPipesFilterByDesiredState() {
        pipesService.createPipe("running-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.RUNNING, null, null, null, null, null, "us-east-1");
        pipesService.createPipe("stopped-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.STOPPED, null, null, null, null, null, "us-east-1");

        List<Pipe> running = pipesService.listPipes(null, null, null, DesiredState.RUNNING, null, "us-east-1");
        assertEquals(1, running.size());
        assertEquals("running-pipe", running.get(0).getName());
    }

    @Test
    void startPipe() {
        pipesService.createPipe("start-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.STOPPED, null, null, null, null, null, "us-east-1");

        Pipe pipe = pipesService.startPipe("start-pipe", "us-east-1");
        assertEquals(DesiredState.RUNNING, pipe.getDesiredState());
        assertEquals(PipeState.RUNNING, pipe.getCurrentState());
    }

    @Test
    void stopPipe() {
        pipesService.createPipe("stop-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, DesiredState.RUNNING, null, null, null, null, null, "us-east-1");

        Pipe pipe = pipesService.stopPipe("stop-pipe", "us-east-1");
        assertEquals(DesiredState.STOPPED, pipe.getDesiredState());
        assertEquals(PipeState.STOPPED, pipe.getCurrentState());
    }

    @Test
    void tagResource() {
        Pipe pipe = pipesService.createPipe("tag-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        pipesService.tagResource("us-east-1", pipe.getArn(), Map.of("team", "platform"));
        Map<String, String> tags = pipesService.listTags("us-east-1", pipe.getArn());
        assertEquals("platform", tags.get("team"));
    }

    @Test
    void untagResource() {
        Pipe pipe = pipesService.createPipe("untag-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, Map.of("a", "1", "b", "2"), "us-east-1");

        pipesService.untagResource("us-east-1", pipe.getArn(), List.of("a"));
        Map<String, String> tags = pipesService.listTags("us-east-1", pipe.getArn());
        assertFalse(tags.containsKey("a"));
        assertEquals("2", tags.get("b"));
    }

    @Test
    void regionIsolation() {
        pipesService.createPipe("region-pipe",
                "arn:aws:sqs:us-east-1:000000000000:source",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null, null, null, null, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.describePipe("region-pipe", "eu-west-1"));
        assertEquals("NotFoundException", ex.getErrorCode());
    }

    @Test
    void createPipeAcceptsParallelizationFactorOnKinesisSource() {
        Pipe pipe = pipesService.createPipe("kinesis-pf-pipe",
                "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null,
                sourceParameters("""
                        {"KinesisStreamParameters":{"StartingPosition":"TRIM_HORIZON","ParallelizationFactor":4}}"""),
                null, null, null, "us-east-1");

        assertEquals(4, pipe.getSourceParameters()
                .path("KinesisStreamParameters").path("ParallelizationFactor").asInt());
    }

    @Test
    void createPipeAcceptsParallelizationFactorOnDynamoDbStreamSource() {
        Pipe pipe = pipesService.createPipe("ddb-pf-pipe",
                "arn:aws:dynamodb:us-east-1:000000000000:table/orders/stream/2024-01-01T00:00:00.000",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null,
                sourceParameters("""
                        {"DynamoDBStreamParameters":{"StartingPosition":"LATEST","ParallelizationFactor":10}}"""),
                null, null, null, "us-east-1");

        assertEquals(10, pipe.getSourceParameters()
                .path("DynamoDBStreamParameters").path("ParallelizationFactor").asInt());
    }

    @Test
    void createPipeRejectsParallelizationFactorAboveMaximum() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-too-high",
                        "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":11}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must be between 1 and 10"), ex.getMessage());
    }

    @Test
    void createPipeRejectsParallelizationFactorBelowMinimum() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-too-low",
                        "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":0}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must be between 1 and 10"), ex.getMessage());
    }

    @Test
    void createPipeRejectsFractionalParallelizationFactor() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-fractional",
                        "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":2.5}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must be an integer"), ex.getMessage());
    }

    @Test
    void createPipeRejectsParallelizationFactorWiderThanAnInt() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-oversized",
                        "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":18446744073709551621}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("18446744073709551621"), ex.getMessage());
    }

    @Test
    void createPipeRejectsNonNumericParallelizationFactor() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-non-numeric",
                        "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":"4"}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must be a numeric value"), ex.getMessage());
    }

    @Test
    void createPipeRejectsParallelizationFactorOnSqsSource() {
        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.createPipe("pf-wrong-source",
                        "arn:aws:sqs:us-east-1:000000000000:source",
                        "arn:aws:sqs:us-east-1:000000000000:target",
                        "arn:aws:iam::000000000000:role/role",
                        null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":4}}"""),
                        null, null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("only supported for Kinesis stream sources"), ex.getMessage());
    }

    @Test
    void updatePipeRejectsInvalidParallelizationFactor() {
        pipesService.createPipe("pf-update-pipe",
                "arn:aws:kinesis:us-east-1:000000000000:stream/events",
                "arn:aws:sqs:us-east-1:000000000000:target",
                "arn:aws:iam::000000000000:role/role",
                null, null, null,
                sourceParameters("""
                        {"KinesisStreamParameters":{"ParallelizationFactor":2}}"""),
                null, null, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                pipesService.updatePipe("pf-update-pipe", null, null, null, null, null,
                        sourceParameters("""
                                {"KinesisStreamParameters":{"ParallelizationFactor":99}}"""),
                        null, null, "us-east-1"));

        assertEquals("ValidationException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("must be between 1 and 10"), ex.getMessage());
    }

    @Test
    void dynamoDbStreamRetryOptionsAreAcceptedAndReturned() {
        String created = """
                {"StartingPosition":"TRIM_HORIZON","MaximumRetryAttempts":3,"MaximumRecordAgeInSeconds":60,
                 "OnPartialBatchItemFailure":"AUTOMATIC_BISECT"}""";
        String updated = """
                {"MaximumRetryAttempts":10000,"MaximumRecordAgeInSeconds":604800,
                 "OnPartialBatchItemFailure":"AUTOMATIC_BISECT"}""";

        pipesService.createPipe("ddb-retry-pipe",
                "arn:aws:dynamodb:us-east-1:000000000000:table/orders/stream/2024-01-01T00:00:00.000",
                "arn:aws:sqs:us-east-1:000000000000:target", "arn:aws:iam::000000000000:role/role",
                null, null, null, sourceParameters("{\"DynamoDBStreamParameters\":" + created + "}"),
                null, null, null, "us-east-1");
        assertEquals(sourceParameters(created), pipesService.describePipe("ddb-retry-pipe", "us-east-1")
                .getSourceParameters().path("DynamoDBStreamParameters"));

        pipesService.updatePipe("ddb-retry-pipe", null, null, null, null, null,
                sourceParameters("{\"DynamoDBStreamParameters\":" + updated + "}"), null, null, "us-east-1");
        JsonNode stored = pipesService.describePipe("ddb-retry-pipe", "us-east-1")
                .getSourceParameters().path("DynamoDBStreamParameters");
        assertEquals(10000, stored.path("MaximumRetryAttempts").asInt());
        assertEquals(604800, stored.path("MaximumRecordAgeInSeconds").asInt());
        assertEquals("AUTOMATIC_BISECT", stored.path("OnPartialBatchItemFailure").asText());
    }

    private static JsonNode sourceParameters(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid test JSON: " + json, e);
        }
    }
}
