package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the contract every container manager now shares: how frames are routed, what a timeout and
 * a missing exit code turn into, and that an attach-stream error or an interrupt is not mistaken
 * for a finished command. None of it needs a daemon, so the docker-java calls are mocked.
 */
class ContainerExecTest {

    private static final String CONTAINER_ID = "container-1";
    private static final String EXEC_ID = "exec-1";
    private static final String[] CMD = {"sh", "-c", "true"};

    private DockerClient dockerClient;
    private ExecStartCmd execStartCmd;
    private InspectExecResponse inspectResponse;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn(EXEC_ID);
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(createCmd);

        execStartCmd = mock(ExecStartCmd.class);
        when(dockerClient.execStartCmd(EXEC_ID)).thenReturn(execStartCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd(EXEC_ID)).thenReturn(inspectCmd);
    }

    @Test
    void runKeepsStdoutAndStderrApart() {
        completeWith(List.of(frame(StreamType.STDOUT, "out-1 "), frame(StreamType.STDERR, "err"),
                frame(StreamType.STDOUT, "out-2")));
        when(inspectResponse.getExitCodeLong()).thenReturn(3L);

        ContainerExec.Result result = ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 5);

        assertEquals(3L, result.exitCode());
        assertEquals("out-1 out-2", result.stdout());
        assertEquals("err", result.stderr());
        assertFalse(result.timedOut());
    }

    @Test
    void runMergedInterleavesBothStreamsInArrivalOrder() {
        completeWith(List.of(frame(StreamType.STDOUT, "a\n"), frame(StreamType.STDERR, "b\n"),
                frame(StreamType.STDOUT, "c\n")));

        ContainerExec.Result result = ContainerExec.runMerged(dockerClient, CONTAINER_ID, CMD, 5);

        assertEquals("a\nb\nc\n", result.stdout());
        assertEquals("", result.stderr());
        assertEquals("a\nb\nc", result.summary());
    }

    @Test
    void aMultiByteCharacterSplitAcrossFramesSurvives() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        completeWith(List.of(new Frame(StreamType.STDOUT, new byte[]{euro[0]}),
                new Frame(StreamType.STDOUT, new byte[]{euro[1], euro[2]})));

        assertEquals("€", ContainerExec.runMerged(dockerClient, CONTAINER_ID, CMD, 5).stdout());
    }

    @Test
    void aMissingExitCodeIsReportedAsMinusOne() {
        completeWith(List.of());
        when(inspectResponse.getExitCodeLong()).thenReturn(null);

        assertEquals(-1L, ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 5).exitCode());
    }

    @Test
    void aCommandThatOutlivesItsTimeoutIsReportedWithWhatItPrintedSoFar() throws IOException {
        Closeable attach = mock(Closeable.class);
        startWith(callback -> {
            callback.onStart(attach);
            callback.onNext(frame(StreamType.STDOUT, "partial"));
            callback.onNext(frame(StreamType.STDERR, "warning"));
        });

        ContainerExec.Result result = ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 0);

        assertTrue(result.timedOut());
        assertEquals(-1L, result.exitCode());
        assertEquals("partial", result.stdout());
        assertEquals("warning\nTimed out after 0s", result.stderr());
        verify(attach).close();
        verify(dockerClient, never()).inspectExecCmd(EXEC_ID);
    }

    @Test
    void aTimedOutMergedRunSummarisesAsTheTimeout() {
        startWith(callback -> {
        });

        ContainerExec.Result result = ContainerExec.runMerged(dockerClient, CONTAINER_ID, CMD, 0);

        assertTrue(result.timedOut());
        assertEquals("Timed out after 0s", result.summary());
    }

    @Test
    void throwIfTimedOutNamesTheContainer() {
        startWith(callback -> {
        });

        ContainerExec.Result result = ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 0);

        RuntimeException error = assertThrows(RuntimeException.class, () -> result.throwIfTimedOut(CONTAINER_ID));
        assertEquals("exec timed out in container " + CONTAINER_ID, error.getMessage());
    }

    @Test
    void throwIfTimedOutPassesAFinishedResultThrough() {
        completeWith(List.of());
        when(inspectResponse.getExitCodeLong()).thenReturn(1L);

        ContainerExec.Result result = ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 5);

        assertSame(result, result.throwIfTimedOut(CONTAINER_ID));
    }

    @Test
    void anEmptyRunSummarisesAsNoOutput() {
        completeWith(List.of());

        assertEquals("(no output)", ContainerExec.runMerged(dockerClient, CONTAINER_ID, CMD, 5).summary());
    }

    @Test
    void anAttachStreamErrorPropagatesInsteadOfReadingAnExitCode() {
        startWith(callback -> callback.onError(new IllegalStateException("attach stream broke")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 5));

        assertEquals("attach stream broke", error.getMessage());
        verify(dockerClient, never()).inspectExecCmd(EXEC_ID);
    }

    @Test
    void anInterruptIsRaisedAndTheInterruptFlagKept() throws IOException {
        Closeable attach = mock(Closeable.class);
        startWith(callback -> callback.onStart(attach));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> ContainerExec.run(dockerClient, CONTAINER_ID, CMD, 5));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(attach).close();
        } finally {
            Thread.interrupted();
        }
    }

    private void completeWith(List<Frame> frames) {
        startWith(callback -> {
            frames.forEach(callback::onNext);
            callback.onComplete();
        });
    }

    private void startWith(Consumer<ResultCallback.Adapter<Frame>> behaviour) {
        when(execStartCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            behaviour.accept(callback);
            return callback;
        });
    }

    private static Frame frame(StreamType type, String payload) {
        return new Frame(type, payload.getBytes(StandardCharsets.UTF_8));
    }
}
