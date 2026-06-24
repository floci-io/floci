package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.command.InspectExecResponse;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SsmDirectCommandExecutorTest {

    @Test
    void executesRunShellScriptInsideEc2Container() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = instance("i-container", "container-1");
        when(ec2Service.findInstanceById("i-container")).thenReturn(instance);
        when(ec2Service.isInstanceContainerRunning("i-container")).thenReturn(true);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execCreateResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreate);

        ExecStartCmd execStart = mock(ExecStartCmd.class);
        AtomicReference<ResultCallback<Frame>> callback = new AtomicReference<>();
        when(execStart.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> resultCallback = invocation.getArgument(0);
            callback.set(resultCallback);
            resultCallback.onNext(new Frame(StreamType.STDOUT, "stdout\n".getBytes()));
            resultCallback.onNext(new Frame(StreamType.STDERR, "stderr\n".getBytes()));
            resultCallback.onComplete();
            return resultCallback;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(execStart);

        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectExec);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunShellScript",
                Map.of("commands", List.of("echo stdout"), "workingDirectory", List.of("/tmp")),
                30);

        assertTrue(result.isPresent());
        assertEquals("Success", result.get().status());
        assertEquals("stdout\n", result.get().standardOutput());
        assertEquals("stderr\n", result.get().standardError());
        assertEquals(0, result.get().responseCode());
        assertTrue(callback.get() != null);
        verify(execCreate).withCmd(
                eq("sh"),
                eq("-c"),
                argThat(command -> command.contains("timeout --kill-after=1s '30s' sh -c")
                        && command.contains("'echo stdout'")));
    }

    @Test
    void returnsEmptyWhenInstanceIsNotContainerBacked() {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.findInstanceById("i-agent")).thenReturn(null);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-agent",
                "AWS-RunShellScript",
                Map.of("commands", List.of("echo agent")),
                30);

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForUnsupportedDocument() {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunPowerShellScript",
                Map.of("commands", List.of("Write-Host nope")),
                30);

        assertTrue(result.isEmpty());
    }

    @Test
    void blankRunShellScriptCompletesWithoutDockerExec() {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = instance("i-container", "container-1");
        when(ec2Service.findInstanceById("i-container")).thenReturn(instance);
        when(ec2Service.isInstanceContainerRunning("i-container")).thenReturn(true);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunShellScript",
                Map.of("commands", List.of("")),
                30);

        assertTrue(result.isPresent());
        assertEquals("Success", result.get().status());
        assertEquals("", result.get().standardOutput());
        assertEquals(0, result.get().responseCode());
        verifyNoInteractions(dockerClient);
    }

    @Test
    void nonZeroExitCodeMapsToFailedResult() {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = instance("i-container", "container-1");
        when(ec2Service.findInstanceById("i-container")).thenReturn(instance);
        when(ec2Service.isInstanceContainerRunning("i-container")).thenReturn(true);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execCreateResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreate);

        ExecStartCmd execStart = mock(ExecStartCmd.class);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> resultCallback = invocation.getArgument(0);
            resultCallback.onNext(new Frame(StreamType.STDERR, "nope\n".getBytes()));
            resultCallback.onComplete();
            return resultCallback;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(execStart);

        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(7L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectExec);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunShellScript",
                Map.of("commands", List.of("exit 7")),
                30);

        assertTrue(result.isPresent());
        assertEquals("Failed", result.get().status());
        assertEquals("nope\n", result.get().standardError());
        assertEquals(7, result.get().responseCode());
    }

    @Test
    void timeoutClosesDockerExecCallback() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = instance("i-container", "container-1");
        when(ec2Service.findInstanceById("i-container")).thenReturn(instance);
        when(ec2Service.isInstanceContainerRunning("i-container")).thenReturn(true);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execCreateResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreate);

        ExecStartCmd execStart = mock(ExecStartCmd.class);
        @SuppressWarnings("unchecked")
        ResultCallback<Frame> callback = mock(ResultCallback.class);
        when(execStart.exec(any())).thenReturn(callback);
        when(dockerClient.execStartCmd("exec-1")).thenReturn(execStart);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunShellScript",
                Map.of("commands", List.of("sleep 100")),
                0);

        assertTrue(result.isPresent());
        assertEquals("TimedOut", result.get().status());
        assertEquals(-1, result.get().responseCode());
        verify(callback).close();
    }

    @Test
    void timeoutExitCodeMapsToTimedOutResult() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = instance("i-container", "container-1");
        when(ec2Service.findInstanceById("i-container")).thenReturn(instance);
        when(ec2Service.isInstanceContainerRunning("i-container")).thenReturn(true);

        ExecCreateCmd execCreate = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
        when(execCreateResponse.getId()).thenReturn("exec-1");
        when(execCreate.exec()).thenReturn(execCreateResponse);
        when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreate);

        ExecStartCmd execStart = mock(ExecStartCmd.class);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback<Frame> resultCallback = invocation.getArgument(0);
            resultCallback.onNext(new Frame(StreamType.STDERR, "terminated\n".getBytes()));
            resultCallback.onComplete();
            return resultCallback;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(execStart);

        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse inspectExecResponse = mock(InspectExecResponse.class);
        when(inspectExecResponse.getExitCodeLong()).thenReturn(124L);
        when(inspectExec.exec()).thenReturn(inspectExecResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectExec);

        SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(dockerClient, ec2Service);
        Optional<SsmDirectCommandExecutor.ExecutionResult> result = executor.executeIfSupported(
                "i-container",
                "AWS-RunShellScript",
                Map.of("commands", List.of("sleep 100")),
                1);

        assertTrue(result.isPresent());
        assertEquals("TimedOut", result.get().status());
        assertEquals(-1, result.get().responseCode());
        assertEquals("terminated\n", result.get().standardError());
    }

    private static Instance instance(String instanceId, String containerId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setDockerContainerId(containerId);
        instance.setState(InstanceState.running());
        return instance;
    }
}
