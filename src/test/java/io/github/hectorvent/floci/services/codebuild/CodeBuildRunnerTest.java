package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeBuildRunnerTest {

    @Test
    void phaseWithMoreOutputThanOneBufferCanHoldStillReportsItsLastOutput() {
        byte[] chunk = new byte[8 * 1024 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        // 2.5 GiB in total: more than a single Java byte array can hold.
        int chunkCount = 320;
        String lastLine = "error: build step failed\n";
        DockerClient dockerClient = dockerClientStreaming(chunk, chunkCount, lastLine, 2L);
        CodeBuildRunner runner = new CodeBuildRunner(dockerClient, null, null, null, null, null, null, null, null, null);

        CodeBuildRunner.PhaseResult result = runner.runPhase("container-1", "/src", List.of(),
                List.of("./build.sh"), 60, new AtomicBoolean(false));

        assertTrue(result.failed());
        String expectedTail = "x".repeat(512 - "error: build step failed".length()) + "error: build step failed";
        assertEquals("Exit code 2: " + expectedTail, result.errorMessage());
    }

    @Test
    void failureMessageIgnoresLongTrailingWhitespace() {
        DockerClient dockerClient = dockerClientStreaming(new byte[0], 0, "tests failed" + "\n".repeat(10_000), 1L);
        CodeBuildRunner runner = new CodeBuildRunner(dockerClient, null, null, null, null, null, null, null, null, null);

        CodeBuildRunner.PhaseResult result = runner.runPhase("container-1", "/src", List.of(),
                List.of("./test.sh"), 60, new AtomicBoolean(false));

        assertEquals("Exit code 1: tests failed", result.errorMessage());
    }

    private static DockerClient dockerClientStreaming(byte[] chunk, int chunkCount, String lastLine, long exitCode) {
        DockerClient dockerClient = mock(DockerClient.class);

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-1");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            for (int i = 0; i < chunkCount; i++) {
                callback.onNext(new Frame(StreamType.STDOUT, chunk));
            }
            callback.onNext(new Frame(StreamType.STDERR, lastLine.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(exitCode);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectCmd);
        return dockerClient;
    }
}
