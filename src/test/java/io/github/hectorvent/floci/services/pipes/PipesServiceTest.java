package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
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
import static org.mockito.Mockito.when;

class PipesServiceTest {

    private PipesService pipesService;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");

        pipesService = new PipesService(storageFactory, config);
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
}
