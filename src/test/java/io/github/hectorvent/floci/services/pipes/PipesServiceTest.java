package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.pipes.model.PipeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PipesServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
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

    private static JsonNode sourceParameters(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid test JSON: " + json, e);
        }
    }
}
