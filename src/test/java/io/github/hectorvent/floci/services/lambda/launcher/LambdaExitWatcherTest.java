package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.WaitResponse;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaExitWatcherTest {

    private static final String CONTAINER = "lambda-container";

    private final List<WaitContainerResultCallback> armed = new ArrayList<>();
    private final List<Integer> exits = new ArrayList<>();
    private DockerClient docker;
    private InspectContainerCmd inspect;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        WaitContainerCmd wait = mock(WaitContainerCmd.class);
        when(docker.waitContainerCmd(CONTAINER)).thenReturn(wait);
        when(wait.exec(any())).thenAnswer(invocation -> {
            WaitContainerResultCallback callback = invocation.getArgument(0);
            armed.add(callback);
            return callback;
        });
        inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(CONTAINER)).thenReturn(inspect);
    }

    private void containerState(Boolean running, Long exitCode) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(state.getExitCodeLong()).thenReturn(exitCode);
        when(response.getState()).thenReturn(state);
        when(inspect.exec()).thenReturn(response);
    }

    private static WaitResponse exited(int status) {
        WaitResponse response = mock(WaitResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return response;
    }

    @Test
    void aStreamThatTimesOutOnARunningContainerIsReArmedAndStillReportsTheExit() {
        containerState(true, null);
        LambdaExitWatcher.watch(docker, CONTAINER, exits::add);

        armed.get(0).onError(new SocketTimeoutException("Read timed out"));
        assertEquals(2, armed.size());
        assertEquals(List.of(), exits);

        armed.get(1).onNext(exited(137));
        assertEquals(List.of(137), exits);
    }

    @Test
    void aStreamThatFailsAfterTheContainerExitedReportsTheInspectedExitCode() {
        containerState(false, 2L);
        LambdaExitWatcher.watch(docker, CONTAINER, exits::add);

        armed.get(0).onError(new SocketTimeoutException("Read timed out"));

        assertEquals(1, armed.size());
        assertEquals(List.of(2), exits);
    }

    @Test
    void aStreamThatFailsAfterTheContainerWasRemovedReportsNothing() {
        when(inspect.exec()).thenThrow(new NotFoundException("No such container"));
        LambdaExitWatcher.watch(docker, CONTAINER, exits::add);

        armed.get(0).onError(new SocketTimeoutException("Read timed out"));

        assertEquals(1, armed.size());
        assertEquals(List.of(), exits);
    }

    @Test
    void repeatedImmediateFailuresStopReArming() {
        containerState(true, null);
        LambdaExitWatcher.watch(docker, CONTAINER, exits::add);

        for (int i = 0; i <= LambdaExitWatcher.MAX_QUICK_FAILURES; i++) {
            armed.get(armed.size() - 1).onError(new SocketTimeoutException("Connection reset"));
        }

        assertEquals(LambdaExitWatcher.MAX_QUICK_FAILURES + 1, armed.size());
        assertEquals(List.of(), exits);
    }
}
