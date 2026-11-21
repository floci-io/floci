package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.services.lambda.model.ContainerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvocationLogTailClockSkewTest {

    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "2099-01-01T00:00:00Z"})
    void warmInvocationUsesTheDaemonsBoundaryEvenWhenItsClockDrifts(String sourceTime) {
        Instant previous = Instant.parse(sourceTime);
        String earlier = previous + " previous invocation\n";
        String current = previous.plusNanos(1) + " current invocation\n";
        DockerClient docker = mock(DockerClient.class);
        when(docker.logContainerCmd("worker")).thenAnswer(call -> snapshotCommand(earlier, current));
        ContainerHandle handle = new ContainerHandle("worker", "function", null, ContainerState.WARM);
        handle.setLogTailReader(initialization -> InvocationLogTail.captureDocker(docker, "worker", initialization));
        handle.beginInvocation(false);

        String logs = handle.beginInvocation(true).get();

        assertEquals("current invocation\n", new String(Base64.getDecoder().decode(logs), StandardCharsets.UTF_8));
    }

    private static LogContainerCmd snapshotCommand(String earlier, String current) {
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        AtomicInteger lines = new AtomicInteger();
        doAnswer(call -> {
            lines.set(call.getArgument(0));
            return command;
        }).when(command).withTail(anyInt());
        doAnswer(call -> {
            ResultCallback<Frame> callback = call.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, earlier.getBytes(StandardCharsets.UTF_8)));
            if (lines.get() != 1) {
                callback.onNext(new Frame(StreamType.STDOUT, current.getBytes(StandardCharsets.UTF_8)));
            }
            callback.onComplete();
            return callback;
        }).when(command).exec(any());
        return command;
    }

    @Test
    void eachDockerRecordDropsItsPrefixEvenWhenThePreviousRecordHasNoNewline() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.logContainerCmd("worker")).thenAnswer(call -> snapshotCommand(
                "2026-11-16T19:00:00.001Z partial ", "2026-11-16T19:00:00.002Z continuation\n"));

        String logs = InvocationLogTail.readDocker(docker, "worker", Instant.EPOCH);

        assertEquals("partial continuation\n", new String(Base64.getDecoder().decode(logs), StandardCharsets.UTF_8));
    }

    @Test
    void aDockerTimestampSplitAcrossCallbacksIsReassembled() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.logContainerCmd("worker")).thenAnswer(call -> snapshotCommand(
                "2026-11-16T19:", "00:00.001Z handler output\n"));

        String logs = InvocationLogTail.readDocker(docker, "worker", Instant.EPOCH);

        assertEquals("handler output\n", new String(Base64.getDecoder().decode(logs), StandardCharsets.UTF_8));
    }
}
