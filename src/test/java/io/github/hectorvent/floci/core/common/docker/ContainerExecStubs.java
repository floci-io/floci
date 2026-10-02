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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stubs a mocked {@link DockerClient} so every {@link ContainerExec} run in one container completes
 * at once with a fixed output and exit code, for tests of the managers that call it.
 */
public final class ContainerExecStubs {

    private ContainerExecStubs() {
    }

    /** Returns the commands run in {@code containerId}, in order, as the stubbed execs see them. */
    public static List<List<String>> completeEveryExec(DockerClient dockerClient, String containerId, long exitCode,
                                                       String stdout, String stderr) {
        String execId = "exec-" + containerId;
        List<List<String>> commands = new ArrayList<>();

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        when(createCmd.withCmd(any(String[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            String[] cmd = args.length == 1 && args[0] instanceof String[] array
                    ? array
                    : Arrays.copyOf(args, args.length, String[].class);
            commands.add(List.of(cmd));
            return createCmd;
        });
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn(execId);
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd(containerId)).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, stdout.getBytes(StandardCharsets.UTF_8)));
            callback.onNext(new Frame(StreamType.STDERR, stderr.getBytes(StandardCharsets.UTF_8)));
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd(execId)).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(exitCode);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd(execId)).thenReturn(inspectCmd);
        return commands;
    }
}
