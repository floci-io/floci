package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerLauncherTest {

    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerLogStreamer logStreamer;
    @Mock ImageResolver imageResolver;
    @Mock RuntimeApiServerFactory runtimeApiServerFactory;
    @Mock DockerHostResolver dockerHostResolver;
    @Mock EmulatorConfig config;
    @Mock EcrRegistryManager ecrRegistryManager;
    @Mock EmbeddedDnsServer embeddedDnsServer;
    @Mock RuntimeApiServer runtimeApiServer;
    @Mock DockerClient dockerClient;

    @TempDir
    Path tempDir;

    ContainerLauncher launcher;
    /** Collects remote paths passed to withRemotePath across all copy mocks. */
    final java.util.List<String> capturedRemotePaths = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);

        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        lenient().when(config.hostname()).thenReturn(Optional.empty());

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager, embeddedDnsServer);

        when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        when(runtimeApiServer.getPort()).thenReturn(9000);
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");

        when(lifecycleManager.create(any())).thenReturn("container-123");
        ContainerLifecycleManager.ContainerInfo info =
                new ContainerLifecycleManager.ContainerInfo("container-123", Map.of());
        when(lifecycleManager.startCreated(eq("container-123"), any())).thenReturn(info);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        // Stub the Docker copy chain so copyDirToContainer / copyFileToContainer
        // don't throw when the mock DockerClient is used. Each invocation
        // returns a fresh mock that drains the tar InputStream on exec() to
        // prevent the background PipedOutputStream writer thread from blocking
        // when the pipe buffer fills.
        capturedRemotePaths.clear();
        when(dockerClient.copyArchiveToContainerCmd(any())).thenAnswer(inv -> {
            CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class);
            final java.io.InputStream[] captured = {null};
            when(cmd.withRemotePath(any())).thenAnswer(pathInv -> {
                capturedRemotePaths.add(pathInv.getArgument(0));
                return cmd;
            });
            when(cmd.withTarInputStream(any())).thenAnswer(streamInv -> {
                captured[0] = streamInv.getArgument(0);
                return cmd;
            });
            doAnswer(execInv -> {
                if (captured[0] != null) {
                    try { captured[0].transferTo(java.io.OutputStream.nullOutputStream()); }
                    catch (Exception ignored) {}
                }
                return null;
            }).when(cmd).exec();
            return cmd;
        });
    }

    @Test
    void launchFunction_createsWithoutBindMounts() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("standard-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(specCaptor.capture());

        ContainerSpec spec = specCaptor.getValue();
        assertTrue(spec.binds().isEmpty(), "Function should NOT have bind mounts");
    }

    @Test
    void launchFunction_createsBeforeCopyAndStartsAfter() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("order-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        // Verify ordering: create → getDockerClient → Docker copy (to /var/task) → startCreated
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(lifecycleManager).getDockerClient();
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // createAndStart must NOT be called — Lambda uses the split path
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void launchFunction_injectsDefaultAwsCredentials() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("creds-defaults"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("creds-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(specCaptor.capture());

        List<String> env = specCaptor.getValue().env();
        assertTrue(env.contains("AWS_ACCESS_KEY_ID=test"),
                "default AWS_ACCESS_KEY_ID should be injected");
        assertTrue(env.contains("AWS_SECRET_ACCESS_KEY=test"),
                "default AWS_SECRET_ACCESS_KEY should be injected");
        assertTrue(env.contains("AWS_SESSION_TOKEN=test"),
                "default AWS_SESSION_TOKEN should be injected");
    }

    @Test
    void launchFunction_userEnvironmentOverridesDefaultCredentials() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("creds-override"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("override-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setEnvironment(Map.of(
                "AWS_ACCESS_KEY_ID", "user-key",
                "AWS_SECRET_ACCESS_KEY", "user-secret"));

        launcher.launch(fn);

        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(specCaptor.capture());

        List<String> env = specCaptor.getValue().env();
        // Docker honours the last occurrence of a duplicate Env entry, so user
        // overrides must appear after the Floci defaults.
        int defaultKeyIdx = env.indexOf("AWS_ACCESS_KEY_ID=test");
        int userKeyIdx = env.indexOf("AWS_ACCESS_KEY_ID=user-key");
        assertTrue(defaultKeyIdx >= 0, "default AWS_ACCESS_KEY_ID still present");
        assertTrue(userKeyIdx > defaultKeyIdx,
                "user AWS_ACCESS_KEY_ID must appear after the default");

        int defaultSecretIdx = env.indexOf("AWS_SECRET_ACCESS_KEY=test");
        int userSecretIdx = env.indexOf("AWS_SECRET_ACCESS_KEY=user-secret");
        assertTrue(defaultSecretIdx >= 0, "default AWS_SECRET_ACCESS_KEY still present");
        assertTrue(userSecretIdx > defaultSecretIdx,
                "user AWS_SECRET_ACCESS_KEY must appear after the default");

        // AWS_SESSION_TOKEN was not overridden so the default remains.
        assertEquals(1, env.stream().filter(e -> e.startsWith("AWS_SESSION_TOKEN=")).count(),
                "AWS_SESSION_TOKEN should retain its default exactly once");
    }

    @Test
    void launchProvidedRuntime_copiesBootstrapBeforeStart() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("provided-code"));
        Files.writeString(codePath.resolve("bootstrap"), "#!/bin/sh\necho hello");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("provided-fn");
        fn.setRuntime("provided.al2023");
        fn.setHandler("bootstrap");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        // The critical invariant: create must happen before any Docker copy,
        // and start must happen after. This is the exact regression from #466.
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(lifecycleManager).getDockerClient();
        // Two copies: code to /var/task + bootstrap to /var/runtime
        inOrder.verify(dockerClient, times(2)).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // Verify both /var/task and /var/runtime were targeted
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "code should be copied to /var/task");
        assertTrue(capturedRemotePaths.contains("/var/runtime"),
                "bootstrap should be copied to /var/runtime");

        verify(lifecycleManager, never()).createAndStart(any());
    }
}
