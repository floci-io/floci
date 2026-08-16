package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.model.LambdaFileSystemConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock LambdaExecutionRoleCredentials executionRoleCredentials;

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
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.EfsSharingConfig efs = mock(EmulatorConfig.EfsSharingConfig.class);

        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(lambda.extraHosts()).thenReturn(Optional.empty());
        lenient().when(lambda.awsConfigPath()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.tls()).thenReturn(tls);
        lenient().when(tls.enabled()).thenReturn(false);
        lenient().when(config.defaultRegion()).thenReturn("us-east-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        // The large-code path resolves a code-volume completion marker under the storage persistent path.
        lenient().when(config.storage()).thenReturn(storage);
        lenient().when(storage.persistentPath()).thenReturn(tempDir.toString());
        lenient().when(storage.efs()).thenReturn(efs);
        lenient().when(efs.ownerUid()).thenReturn(OptionalInt.empty());
        lenient().when(efs.ownerGid()).thenReturn(OptionalInt.empty());
        lenient().when(efs.rootPermissions()).thenReturn(Optional.empty());
        lenient().when(efs.initImage()).thenReturn("busybox:stable");
        lenient().when(efs.mountUser()).thenReturn(Optional.empty());
        lenient().when(efs.mountGroupAdd()).thenReturn(OptionalInt.empty());

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        ContainerReachableEndpoint reachableEndpoint =
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer);
        LaunchedContainerAwsEnv awsEnv = new LaunchedContainerAwsEnv(reachableEndpoint);
        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager,
                mock(io.github.hectorvent.floci.services.lambda.LambdaLayerService.class), awsEnv,
                executionRoleCredentials);

        when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        when(runtimeApiServer.getPort()).thenReturn(9000);
        lenient().when(runtimeApiServer.stop()).thenReturn(CompletableFuture.completedFuture(null));
        // stop() quiesces then close()s; the unregister assertions below run past that call.
        lenient().when(runtimeApiServer.close()).thenReturn(CompletableFuture.completedFuture(null));
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");
        lenient().when(executionRoleCredentials.forFunction(any())).thenReturn(Optional.empty());

        // lenient: the failure-path test (populate fails before any container is created) never
        // reaches these, but every success-path test does — they must not trip strict-stubs.
        lenient().when(lifecycleManager.create(any())).thenReturn("container-123");
        ContainerLifecycleManager.ContainerInfo info =
                new ContainerLifecycleManager.ContainerInfo("container-123", Map.of());
        lenient().when(lifecycleManager.startCreated(eq("container-123"), any())).thenReturn(info);
        lenient().when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        // Stub the Docker copy chain so copyDirToContainer / copyFileToContainer
        // don't throw when the mock DockerClient is used. Each invocation
        // returns a fresh mock that drains the tar InputStream on exec() to
        // prevent the background PipedOutputStream writer thread from blocking
        // when the pipe buffer fills.
        capturedRemotePaths.clear();
        lenient().when(dockerClient.copyArchiveToContainerCmd(any())).thenAnswer(inv -> {
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

    /**
     * Captures every {@link ContainerSpec} passed to {@code lifecycleManager.create(...)} and
     * returns the REAL Lambda container's spec.
     *
     * <p>Small code (below {@link ContainerLauncher#CODE_VOLUME_MIN_BYTES}, the case for these
     * tempdir-backed tests) is copied directly into {@code /var/task} on the real container, so
     * {@code create} is called exactly once. Large code is instead served from a read-only named
     * volume populated by a throwaway helper container (also via {@code create}), so {@code create}
     * is called twice: the helper first, then the real container. The real container is the one that
     * mounts {@code /var/task} read-only from the volume, so we identify it by that mount when
     * present, otherwise fall back to the last (only) {@code create}.
     */
    private ContainerSpec captureRealContainerSpec() {
        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).create(specCaptor.capture());
        List<ContainerSpec> specs = specCaptor.getAllValues();
        // The real container (volume path) mounts /var/task read-only; the helper mounts it read-write.
        return specs.stream()
                .filter(s -> s.mounts() != null && s.mounts().stream()
                        .anyMatch(m -> "/var/task".equals(m.getTarget()) && Boolean.TRUE.equals(m.getReadOnly())))
                .reduce((first, second) -> second)   // last match, defensively
                // Fall back to the last create() (the real container) for the direct-copy path.
                .orElseGet(() -> specs.get(specs.size() - 1));
    }

    /** Returns the read-only {@code /var/task} volume mount on the spec, or null if absent. */
    private static Mount varTaskVolumeMount(ContainerSpec spec) {
        if (spec.mounts() == null) {
            return null;
        }
        return spec.mounts().stream()
                .filter(m -> m.getType() == MountType.VOLUME && "/var/task".equals(m.getTarget()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void launchFunction_createsWithoutBindMountsOrVolume_forSmallCode() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("standard-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ContainerSpec spec = captureRealContainerSpec();
        // Small code is copied directly into /var/task (the fast path), NOT bind-mounted...
        assertTrue(spec.binds().isEmpty(), "Function should NOT have bind mounts");
        // ...and NOT served from a named volume (that's reserved for large code).
        assertNull(varTaskVolumeMount(spec), "small code should NOT mount /var/task from a volume");
        // The code is tar-copied straight into /var/task on the real container.
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "small code should be copied directly into /var/task");
    }

    @Test
    void launchFunction_mountsConfiguredFileSystemVolume() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("efs-code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("efs-fn");
        fn.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:efs-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setFileSystemConfigs(List.of(new LambdaFileSystemConfig(
                "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                "/mnt/shared")));

        launcher.launch(fn);

        String expectedVolumeName = "floci-efs-fsap-0123456789abcdef0-"
                + "9d6eafd2aec94d4518a004f005725b4b3c673c1506436bb7368cfd5450fc0810";
        verify(lifecycleManager).ensureSharedVolume(expectedVolumeName,
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), "busybox:stable");
        Mount mount = captureRealContainerSpec().mounts().stream()
                .filter(candidate -> "/mnt/shared".equals(candidate.getTarget()))
                .findFirst()
                .orElseThrow();
        assertEquals(MountType.VOLUME, mount.getType());
        assertEquals(expectedVolumeName, mount.getSource());
        assertTrue(!Boolean.TRUE.equals(mount.getReadOnly()));
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

        // Small code takes the direct-copy path: the real container is created, its code is
        // tar-copied straight into /var/task, and only then is it started. No populate helper.
        //   create(real) -> copy /var/task (real) -> start(real).
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // The code is copied to /var/task on the real container (no helper populate for small code).
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "small code should be tar-copied directly to /var/task");
        assertEquals(1, capturedRemotePaths.stream().filter("/var/task"::equals).count(),
                "/var/task should be copied exactly once (the direct per-container copy)");

        // No populate helper is created/discarded for small code.
        verify(lifecycleManager, times(1)).create(any());
        verify(lifecycleManager, never()).stopAndRemove(any(), any());

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

        List<String> env = captureRealContainerSpec().env();
        assertTrue(env.stream().anyMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")),
                "AWS_ACCESS_KEY_ID should be injected when awsConfigPath is absent");
        assertTrue(env.stream().anyMatch(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")),
                "AWS_SECRET_ACCESS_KEY should be injected when awsConfigPath is absent");
        assertTrue(env.stream().anyMatch(e -> e.startsWith("AWS_SESSION_TOKEN=")),
                "AWS_SESSION_TOKEN should be injected when awsConfigPath is absent");
    }

    @Test
    void launchFunction_fallsBackToTestCredentialsWhenEnvUnset() throws Exception {
        // When System.getenv returns null for AWS vars, credentials should be test/test/test.
        // Since we can't control System.getenv in unit tests, we verify the values are either
        // from the environment or the "test" fallback — both are valid.
        Path codePath = Files.createDirectory(tempDir.resolve("creds-fallback"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("fallback-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        List<String> env = captureRealContainerSpec().env();
        String accessKey = env.stream().filter(e -> e.startsWith("AWS_ACCESS_KEY_ID=")).findFirst().orElse("");
        String secretKey = env.stream().filter(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")).findFirst().orElse("");
        String sessionToken = env.stream().filter(e -> e.startsWith("AWS_SESSION_TOKEN=")).findFirst().orElse("");

        // Value should be either the host env var or "test" fallback
        String expectedAk = System.getenv("AWS_ACCESS_KEY_ID") != null ? System.getenv("AWS_ACCESS_KEY_ID") : "test";
        String expectedSk = System.getenv("AWS_SECRET_ACCESS_KEY") != null ? System.getenv("AWS_SECRET_ACCESS_KEY") : "test";
        String expectedSt = System.getenv("AWS_SESSION_TOKEN") != null ? System.getenv("AWS_SESSION_TOKEN") : "test";

        assertEquals("AWS_ACCESS_KEY_ID=" + expectedAk, accessKey);
        assertEquals("AWS_SECRET_ACCESS_KEY=" + expectedSk, secretKey);
        assertEquals("AWS_SESSION_TOKEN=" + expectedSt, sessionToken);
    }

    @Test
    void launchFunction_injectsConfiguredDefaultRegionWhenArnMissing() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("region-default"));
        when(config.defaultRegion()).thenReturn("eu-central-1");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("region-default-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        List<String> env = captureRealContainerSpec().env();
        assertTrue(env.contains("AWS_DEFAULT_REGION=eu-central-1"));
        assertTrue(env.contains("AWS_REGION=eu-central-1"));
    }

    @Test
    void launchFunction_injectsFunctionArnRegionForAwsSdkSigning() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("region-arn"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("region-arn-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setFunctionArn("arn:aws:lambda:eu-west-2:000000000000:function:region-arn-fn");

        launcher.launch(fn);

        List<String> env = captureRealContainerSpec().env();
        assertTrue(env.contains("AWS_DEFAULT_REGION=eu-west-2"));
        assertTrue(env.contains("AWS_REGION=eu-west-2"));
        verify(logStreamer).attach(
                eq("container-123"), any(), any(), eq("eu-west-2"), eq("lambda:region-arn-fn"));
    }

    @Test
    void launchFunction_executionRoleCredentialsOverrideUserCredentialEnvironment() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("creds-override"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("override-fn");
        fn.setAccountId("000000000000");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setEnvironment(Map.of(
                "AWS_ACCESS_KEY_ID", "user-key",
                "AWS_SECRET_ACCESS_KEY", "user-secret",
                "AWS_SESSION_TOKEN", "user-token"));
        when(executionRoleCredentials.forFunction(fn)).thenReturn(Optional.of(
                new SessionCreds("ASIAROLEKEY", "role-secret", "role-token")));

        launcher.launch(fn);

        List<String> env = captureRealContainerSpec().env();
        assertTrue(env.contains("AWS_ACCESS_KEY_ID=ASIAROLEKEY"));
        assertTrue(env.contains("AWS_SECRET_ACCESS_KEY=role-secret"));
        assertTrue(env.contains("AWS_SESSION_TOKEN=role-token"));
        assertTrue(env.stream().noneMatch("AWS_ACCESS_KEY_ID=user-key"::equals));
        assertTrue(env.stream().noneMatch("AWS_SECRET_ACCESS_KEY=user-secret"::equals));
        assertTrue(env.stream().noneMatch("AWS_SESSION_TOKEN=user-token"::equals));
        assertEquals(1, env.stream().filter(e -> e.startsWith("AWS_ACCESS_KEY_ID=")).count());
        assertEquals(1, env.stream().filter(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")).count());
        assertEquals(1, env.stream().filter(e -> e.startsWith("AWS_SESSION_TOKEN=")).count(),
                "execution-role session token should appear exactly once");
    }

    @Test
    void launchImageFunction_rewritesAwsEcrUriUsingRegistryManagerHostnameStyle() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("image-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1");

        when(ecrRegistryManager.getRepositoryUri("123456789012", "us-east-1", "backend-user:1"))
                .thenReturn("123456789012.dkr.ecr.us-east-1.localhost:5100/backend-user:1");

        launcher.launch(fn);

        ContainerSpec spec = captureRealContainerSpec();
        verify(ecrRegistryManager).ensureStarted();
        verify(ecrRegistryManager).getRepositoryUri("123456789012", "us-east-1", "backend-user:1");
        assertEquals("123456789012.dkr.ecr.us-east-1.localhost:5100/backend-user:1",
                spec.image());
    }

    @Test
    void launchImageFunction_rewritesAwsEcrUriUsingRegistryManagerPathStyle() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("image-path-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/backend-user:1");

        when(ecrRegistryManager.getRepositoryUri("123456789012", "us-east-1", "backend-user:1"))
                .thenReturn("localhost:5100/123456789012/us-east-1/backend-user:1");

        launcher.launch(fn);

        ContainerSpec spec = captureRealContainerSpec();
        verify(ecrRegistryManager).ensureStarted();
        verify(ecrRegistryManager).getRepositoryUri("123456789012", "us-east-1", "backend-user:1");
        assertEquals("localhost:5100/123456789012/us-east-1/backend-user:1",
                spec.image());
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

        // The critical invariant preserved from #466: the REAL container must be created before its
        // code and bootstrap are copied, and started only after. Small code takes the direct-copy
        // path, so everything happens on the one real container (no populate helper).
        //
        // Ordering:
        //   real: create -> copy /var/task + copy bootstrap to /var/runtime -> start
        // (two copyArchiveToContainerCmd calls on the real container: code, then bootstrap).
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(dockerClient, atLeastOnce()).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // Small code is copied directly to /var/task; bootstrap is copied to /var/runtime — both on
        // the one real container.
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "small code should be tar-copied directly to /var/task");
        assertTrue(capturedRemotePaths.contains("/var/runtime"),
                "bootstrap should be copied to /var/runtime on the real container");

        // No populate helper for small code.
        verify(lifecycleManager, times(1)).create(any());
        verify(lifecycleManager, never()).stopAndRemove(any(), any());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void launchFunction_awsConfigPath_bindsAndSkipsCredentials() throws Exception {
        EmulatorConfig.LambdaServiceConfig lambda = config.services().lambda();
        when(lambda.awsConfigPath()).thenReturn(Optional.of("/home/user/.aws"));

        Path codePath = Files.createDirectory(tempDir.resolve("creds-mount"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("mount-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ContainerSpec spec = captureRealContainerSpec();

        // Should bind-mount to /opt/aws-config (read-only)
        assertTrue(spec.binds().stream()
                        .anyMatch(b -> b.getPath().equals("/home/user/.aws")
                                && b.getVolume().getPath().equals("/opt/aws-config")
                                && b.getAccessMode() == com.github.dockerjava.api.model.AccessMode.ro),
                "awsConfigPath should be bind-mounted read-only to /opt/aws-config");

        // Should set explicit file paths for SDK discovery
        List<String> env = spec.env();
        assertTrue(env.contains("AWS_SHARED_CREDENTIALS_FILE=/opt/aws-config/credentials"),
                "AWS_SHARED_CREDENTIALS_FILE should point to mounted path");
        assertTrue(env.contains("AWS_CONFIG_FILE=/opt/aws-config/config"),
                "AWS_CONFIG_FILE should point to mounted path");

        // Should NOT inject credential env vars
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")),
                "AWS_ACCESS_KEY_ID should not be injected when awsConfigPath is set");
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")),
                "AWS_SECRET_ACCESS_KEY should not be injected when awsConfigPath is set");
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SESSION_TOKEN=")),
                "AWS_SESSION_TOKEN should not be injected when awsConfigPath is set");
        verify(executionRoleCredentials, never()).forFunction(any());
    }

    @Test
    void stopUnregistersExecutionRoleSession() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("role-session-stop"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("role-session-stop-fn");
        fn.setAccountId("222233334444");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        when(executionRoleCredentials.forFunction(fn)).thenReturn(Optional.of(
                new SessionCreds("ASIASTOPSESSION", "role-secret", "role-token")));

        ContainerHandle handle = launcher.launch(fn);
        launcher.stop(handle);

        verify(executionRoleCredentials).unregister("222233334444", "ASIASTOPSESSION");
    }

    @Test
    void launchFailureUnregistersExecutionRoleSession() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("role-session-failure"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("role-session-failure-fn");
        fn.setAccountId("222233334444");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        when(executionRoleCredentials.forFunction(fn)).thenReturn(Optional.of(
                new SessionCreds("ASIAFAILEDSESSION", "role-secret", "role-token")));
        doThrow(new RuntimeException("create failed")).when(lifecycleManager).create(any());

        assertThrows(RuntimeException.class, () -> launcher.launch(fn));

        verify(executionRoleCredentials).unregister("222233334444", "ASIAFAILEDSESSION");
    }

    @Test
    void publishedVersionUnregistersUnderTheAccountItRegisteredWith() throws Exception {
        // A published version has no accountId, so both the handle stamp and the revoke must take
        // the account from the function ARN. Reading the field directly revokes under null and
        // leaks a live, non-expiring session for the life of the process.
        Path codePath = Files.createDirectory(tempDir.resolve("role-session-version"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("role-session-version-fn");
        fn.setVersion("1");
        fn.setFunctionArn(
                "arn:aws:lambda:us-east-1:222233334444:function:role-session-version-fn:1");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        when(executionRoleCredentials.forFunction(fn)).thenReturn(Optional.of(
                new SessionCreds("ASIAVERSIONSESSION", "role-secret", "role-token")));

        ContainerHandle handle = launcher.launch(fn);
        assertEquals("222233334444", handle.getExecutionRoleSessionAccountId());

        launcher.stop(handle);

        verify(executionRoleCredentials).unregister("222233334444", "ASIAVERSIONSESSION");
    }

    @Test
    void publishedVersionLaunchFailureUnregistersUnderTheArnAccount() throws Exception {
        // The launch-failure path recomputes the account rather than reading it back off a handle
        // (there is no handle yet), so it needs the same ARN fallback. Reading the field here
        // revokes under null and leaks the session that forFunction just registered.
        Path codePath = Files.createDirectory(tempDir.resolve("role-session-version-failure"));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("role-session-version-failure-fn");
        fn.setVersion("2");
        fn.setFunctionArn(
                "arn:aws:lambda:us-east-1:222233334444:function:role-session-version-failure-fn:2");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        when(executionRoleCredentials.forFunction(fn)).thenReturn(Optional.of(
                new SessionCreds("ASIAVERSIONFAILURE", "role-secret", "role-token")));
        doThrow(new RuntimeException("create failed")).when(lifecycleManager).create(any());

        assertThrows(RuntimeException.class, () -> launcher.launch(fn));

        verify(executionRoleCredentials).unregister("222233334444", "ASIAVERSIONFAILURE");
    }

    @Test
    void launchFunction_appliesConfiguredExtraHosts_skippingMalformedEntries() throws Exception {
        EmulatorConfig.LambdaServiceConfig lambda = config.services().lambda();
        when(lambda.extraHosts()).thenReturn(Optional.of(List.of(
                "localhost:host-gateway", "db.internal:10.0.0.5",
                "v6.internal:2001:db8::1", "v6end.internal:fd00::",
                "malformed", ":9.9.9.9", "trailing:")));

        Path codePath = Files.createDirectory(tempDir.resolve("extra-hosts"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("extra-hosts-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        List<String> extraHosts = captureRealContainerSpec().extraHosts();
        assertTrue(extraHosts.contains("localhost:host-gateway"),
                "configured hostname:host-gateway entry should be applied");
        assertTrue(extraHosts.contains("db.internal:10.0.0.5"),
                "configured hostname:ip entry should be applied");
        assertTrue(extraHosts.contains("v6.internal:2001:db8::1"),
                "IPv6 addresses (containing colons) must survive the hostname/ip split");
        assertTrue(extraHosts.contains("v6end.internal:fd00::"),
                "IPv6 addresses ending in :: must not be classified as missing an ip");
        assertEquals(4, extraHosts.size(),
                "entries without a hostname and an ip must be skipped, not passed to Docker");
    }

    @Test
    void launchFunction_noExtraHostsByDefault() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("no-extra-hosts"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("no-extra-hosts-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        assertTrue(captureRealContainerSpec().extraHosts().isEmpty(),
                "no extra hosts when the config is unset (non-Linux host in this test)");
    }

    @Test
    void launchFunction_noAwsConfigPath_noBindMount() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("no-aws-config"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("no-mount-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        assertTrue(captureRealContainerSpec().binds().stream()
                        .noneMatch(b -> b.getVolume().getPath().equals("/opt/aws-config")),
                "no .aws bind mount when awsConfigPath is absent");
    }

    @Test
    void launchFunction_usesReadOnlyVolume_forLargeCode() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("large-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("large-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setCodeSha256("large-fn-sha-v1");

        long original = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        try {
            // Force the volume path without writing a 32 MiB file.
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024; // 4 KiB
            launcher.launch(fn);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = original;
        }

        ContainerSpec spec = captureRealContainerSpec();
        // The real container mounts /var/task read-only from the code volume...
        Mount codeMount = varTaskVolumeMount(spec);
        assertNotNull(codeMount, "large code: /var/task should be a named-volume mount");
        assertEquals(MountType.VOLUME, codeMount.getType(), "/var/task should be a volume mount");
        assertTrue(Boolean.TRUE.equals(codeMount.getReadOnly()), "/var/task volume should be read-only");
        assertTrue(spec.binds().isEmpty(), "large code: real container should have NO bind mounts");

        // ...and /var/task is populated ONCE via a helper container (create -> start -> copy ->
        // stopAndRemove), not copied onto the real container. The volume is populated exactly once.
        assertEquals(1, capturedRemotePaths.stream().filter("/var/task"::equals).count(),
                "/var/task should be copied exactly once (into the populate helper)");
        // Two creates: the helper + the real container. The helper is discarded.
        verify(lifecycleManager, times(2)).create(any());
        verify(lifecycleManager, atLeastOnce()).ensureVolume(any());
        verify(lifecycleManager, times(1)).stopAndRemove(any(), any()); // the helper only
        // A superseded code-version volume is never deleted synchronously within a single launch
        // (see the cleanupSupersededVolumes tests below for the deferred sweep; this fn has no
        // prior volume to supersede, since it's the fn's first deploy here).
        verify(lifecycleManager, never()).removeVolume(any());
    }

    @Test
    void launchFunction_releasesRuntimeApiServer_whenCodeVolumePopulateFails() throws Exception {
        // Regression for the runtime-api port leak: the volume path allocates a runtime-api server
        // up front (before the code-volume populate). If the populate then fails — the exact
        // cold-start-burst scenario this PR targets, where the Docker daemon rejects the helper work
        // under load — the launch must STILL release that port. Otherwise every failed attempt leaks
        // one port and the pool eventually exhausts, so launches keep failing after the daemon recovers.
        Path codePath = Files.createDirectory(tempDir.resolve("leak-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("leak-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setCodeSha256("leak-fn-sha-v1");

        // The code-volume populate fails (daemon under load), before any container is created.
        doThrow(new RuntimeException("docker daemon busy")).when(lifecycleManager).ensureVolume(any());

        long original = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024; // force the volume path without a 32 MiB file
            assertThrows(RuntimeException.class, () -> launcher.launch(fn));
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = original;
        }

        // The runtime-api port allocated before the populate is released on this failure path...
        verify(runtimeApiServerFactory).release(runtimeApiServer);
        // ...and we bailed before creating or starting any container (nothing to reap).
        verify(lifecycleManager, never()).create(any());
        verify(lifecycleManager, never()).startCreated(any(), any());
    }

    @Test
    void launchFunction_reprovisionsCodeVolume_whenDockerHasNoRecordOfIt() throws Exception {
        // Regression for #2164: the "populated" bookkeeping is only ever an in-memory cache of
        // Docker's state, so a volume removed out of band (manual `docker volume rm`, or a stale
        // in-memory flag left over from a process restart racing a prune) must not be trusted.
        Path codePath = Files.createDirectory(tempDir.resolve("revalidate-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("revalidate-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());
        fn.setCodeSha256("revalidate-fn-sha-v1");

        // Docker never reports this volume as existing, simulating it being gone every time
        // ensureCodeVolume checks, regardless of what the in-memory flag says.
        when(lifecycleManager.volumeExists(anyString())).thenReturn(false);

        long original = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(fn);
            launcher.launch(fn);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = original;
        }

        // Populated twice, once per launch, because the bookkeeping alone is never trusted.
        assertEquals(2, capturedRemotePaths.stream().filter("/var/task"::equals).count(),
                "each launch should repopulate since Docker never confirms the volume exists");
    }

    @Test
    void cleanupSupersededVolumes_removesPreviousVersionVolume_onceGracePeriodElapses() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("cleanup-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB
        // No volumeExists stub needed: v1 and v2 are each a first-ever population of their own
        // distinct volume name, so populatedCodeVolumes.contains(volName) is false both times
        // ensureCodeVolume checks it, short-circuiting the volumeExists call away entirely.

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("cleanup-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("cleanup-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("cleanup-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("cleanup-fn-sha-v2");
        String volumeV2 = ContainerLauncher.codeVolumeName(v2);

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(v1);
            launcher.launch(v2);

            // The v1 volume is superseded but not yet cleaned up: the grace period hasn't elapsed.
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, never()).removeVolume(volumeV1);

            // Once the grace period has (trivially) elapsed, the sweep removes exactly the
            // superseded v1 volume, not the current v2 one.
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1;
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, times(1)).removeVolume(volumeV1);
            verify(lifecycleManager, never()).removeVolume(volumeV2);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    @Test
    void rollingBackToAPreviousCodeVersion_rescuesItsVolumeFromCleanup() throws Exception {
        // Regression: v1 -> v2 -> back to v1 (e.g. a CloudFormation rollback) resolves v1's volume
        // name again, which is already populated - a fast-path hit in ensureCodeVolume. Without
        // reconciling functionCurrentVolume/volumesPendingCleanup on that path too, v1 would stay
        // queued as superseded from the v1 -> v2 step and the next sweep would delete the volume
        // this function is actively using again.
        Path codePath = Files.createDirectory(tempDir.resolve("rollback-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("rollback-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("rollback-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("rollback-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("rollback-fn-sha-v2");
        String volumeV2 = ContainerLauncher.codeVolumeName(v2);

        // Needed for the rollback launch below: v1's volume is already populated by then, so its
        // fast path actually evaluates volumeExists instead of short-circuiting past it.
        when(lifecycleManager.volumeExists(volumeV1)).thenReturn(true);

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(v1);
            launcher.launch(v2);
            launcher.launch(v1); // roll back

            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1;
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, never()).removeVolume(volumeV1);
            verify(lifecycleManager, times(1)).removeVolume(volumeV2);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    @Test
    void cleanupSupersededVolumes_retriesOnALaterSweep_whenTheVolumeIsStillInUse() throws Exception {
        // Regression: removeVolume() silently no-ops when Docker refuses because the volume is
        // still in use (e.g. a slow-draining in-flight container outliving the grace period). The
        // pending-cleanup entry must not be discarded in that case, or the volume is orphaned until
        // someone manually runs `docker volume prune`- the exact problem this fix exists to avoid.
        Path codePath = Files.createDirectory(tempDir.resolve("retry-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("retry-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("retry-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("retry-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("retry-fn-sha-v2");

        // v1's volume persists (still in use) no matter how many times removal is attempted.
        when(lifecycleManager.removeVolume(volumeV1)).thenReturn(false);

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(v1);
            launcher.launch(v2);

            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1;
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, times(1)).removeVolume(volumeV1);

            // A later sweep must still retry it, not have silently dropped it after the first
            // no-op'd attempt.
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, times(2)).removeVolume(volumeV1);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    @Test
    void cleanupAndAConcurrentRollback_areMutuallyExclusiveForTheSameVolume() throws Exception {
        // Regression: without a shared per-volume lock, a sweep that has already claimed a
        // superseded volume (removed its pending-cleanup entry) could race a concurrent rollback
        // that resolves the same volume name, marks it current, and hands it to a new container -
        // while the sweep proceeds to actually delete it underneath that launch. Proving this
        // requires real concurrency: this test forces cleanupSupersededVolumes to block mid-deletion
        // (still holding the volume's lock) and asserts a concurrent rollback blocks too, rather than
        // racing past it.
        Path codePath = Files.createDirectory(tempDir.resolve("race-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("race-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("race-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("race-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("race-fn-sha-v2");

        when(lifecycleManager.volumeExists(volumeV1)).thenReturn(true);

        java.util.concurrent.CountDownLatch cleanupHoldingLock = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseCleanup = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            cleanupHoldingLock.countDown();
            // Blocks here while still holding volumeV1's per-volume lock, simulating a slow delete.
            assertTrue(releaseCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "test did not release cleanup in time");
            return null;
        }).when(lifecycleManager).removeVolume(volumeV1);

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(v1);
            launcher.launch(v2);
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1;

            Thread cleanupThread = new Thread(launcher::cleanupSupersededVolumes);
            cleanupThread.start();
            assertTrue(cleanupHoldingLock.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "cleanup never reached removeVolume");

            java.util.concurrent.atomic.AtomicBoolean rollbackReturned =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread rollbackThread = new Thread(() -> {
                launcher.launch(v1);
                rollbackReturned.set(true);
            });
            rollbackThread.start();

            // Give the rollback every chance to (wrongly) proceed if the lock weren't shared.
            Thread.sleep(200);
            assertFalse(rollbackReturned.get(),
                    "rollback must block while cleanup holds the volume's lock, not race past it");

            releaseCleanup.countDown();
            cleanupThread.join(5000);
            rollbackThread.join(5000);
            assertTrue(rollbackReturned.get(), "rollback should complete once cleanup releases the lock");
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    @Test
    void cleanupSkipsAVolumeStillInFlight_evenPastItsGracePeriod() throws Exception {
        // Regression: a launch that has resolved its code volume (ensureCodeVolume returned, its
        // per-volume lock released) but hasn't yet reached lifecycleManager.create() has no real
        // Docker container-to-volume reference for removeVolume's own in-use check to protect - and
        // create() itself has no proven upper bound (observed ~80s under daemon load, longer than
        // the default 60s grace period). This forces a second launch of v1 to block right before
        // create() while a redeploy to v2 supersedes its volume and the grace period is made to
        // elapse instantly, then asserts cleanup does NOT delete v1's volume while that launch is
        // still in flight - only once it completes and releases its reference.
        Path codePath = Files.createDirectory(tempDir.resolve("inflight-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("inflight-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("inflight-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("inflight-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("inflight-fn-sha-v2");

        // Needed for the delayed re-launch below: v1's volume is already populated by then, so its
        // fast path actually evaluates volumeExists instead of short-circuiting past it.
        when(lifecycleManager.volumeExists(volumeV1)).thenReturn(true);

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;

            // Pre-populate v1's volume with an ordinary launch, before installing the create()
            // blocking stub below - otherwise that stub would catch populateCodeVolume's own
            // helper-container create() call instead of the real container's.
            launcher.launch(v1);

            java.util.concurrent.CountDownLatch launchReachedCreate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseLaunch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean blockedOnce = new java.util.concurrent.atomic.AtomicBoolean(false);
            doAnswer(inv -> {
                if (blockedOnce.compareAndSet(false, true)) {
                    // Only the delayed re-launch of v1 (the first caller after this stub is
                    // installed) blocks here; v2's later launch below must not, or the test would
                    // deadlock itself waiting on its own main thread to release the latch.
                    launchReachedCreate.countDown();
                    assertTrue(releaseLaunch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                            "test did not release the delayed launch in time");
                }
                return "container-123";
            }).when(lifecycleManager).create(any());

            Thread launchThread = new Thread(() -> launcher.launch(v1));
            launchThread.start();
            assertTrue(launchReachedCreate.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "v1's delayed re-launch never reached create()");

            assertEquals(1, launcher.inFlightCount(volumeV1),
                    "ensureCodeVolume must mark the volume in-flight before create() confirms it");

            // A redeploy resolves v2 and supersedes v1's volume while v1's own launch is still stuck.
            launcher.launch(v2);
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1; // grace period elapses instantly

            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, never()).removeVolume(volumeV1);

            // Let the delayed launch finish; it releases its in-flight reference on success.
            releaseLaunch.countDown();
            launchThread.join(5000);
            assertEquals(0, launcher.inFlightCount(volumeV1),
                    "in-flight count must be released once create() succeeds");

            // Nothing is in flight anymore, so a later sweep is free to actually delete it.
            launcher.cleanupSupersededVolumes();
            verify(lifecycleManager, times(1)).removeVolume(volumeV1);
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    @Test
    void concurrentRedeploysOfTheSameFunction_serializeTheCurrentVolumeTransition() throws Exception {
        // Regression: functionCurrentVolume/volumesPendingCleanup reconciliation ran under the
        // per-volume lock only, but two code versions of the same function resolve to two different
        // volume names and so hold two different per-volume locks - meaning that reconciliation
        // could interleave across a rapid back-to-back redeploy (v2 then v3), leaving the actually-
        // current v3 volume mistakenly queued for cleanup while stale v2 stayed recorded as current.
        // The three statements involved (remove pending-cleanup entry, swap in the new current
        // volume, queue whatever was displaced) are plain map operations with no interception point
        // inside them, so forcing that exact interleaving isn't reliably doable in a test. This
        // instead verifies the fix's actual mechanism directly - a concurrent launch for a second
        // code version of the same function must block on the same per-function lock object while
        // another is still transitioning - the same style cleanupAndAConcurrentRollback... already
        // uses to prove the per-volume lock.
        Path codePath = Files.createDirectory(tempDir.resolve("xfn-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("xfn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("xfn-sha-v1");

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;

            Object functionLock = launcher.functionVolumeTransitionLockFor("xfn");
            java.util.concurrent.CountDownLatch testHoldingLock = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseLock = new java.util.concurrent.CountDownLatch(1);
            Thread holderThread = new Thread(() -> {
                synchronized (functionLock) {
                    testHoldingLock.countDown();
                    try {
                        assertTrue(releaseLock.await(5, java.util.concurrent.TimeUnit.SECONDS),
                                "test did not release the function lock in time");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holderThread.start();
            assertTrue(testHoldingLock.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "test thread never acquired the function lock");

            AtomicBoolean launchReturned = new AtomicBoolean(false);
            Thread launchThread = new Thread(() -> {
                launcher.launch(v1);
                launchReturned.set(true);
            });
            launchThread.start();

            // Give the launch every chance to (wrongly) proceed if the transition weren't guarded
            // by this same lock.
            Thread.sleep(200);
            assertFalse(launchReturned.get(),
                    "launch must block on the function lock while another holder has it, not race past it");

            releaseLock.countDown();
            holderThread.join(5000);
            launchThread.join(5000);
            assertTrue(launchReturned.get(), "launch should complete once the function lock is released");
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
        }
    }

    @Test
    void codeVolumeLocks_isNeverPruned_evenAfterCleanupDeletesTheVolume() throws Exception {
        // Regression: pruning a volume's lock entry while a waiter still held a reference to the
        // removed entry's lock object let a third caller's computeIfAbsent create a *different* lock
        // object for the same volume name, so the waiter (once granted the old lock) and that third
        // caller (holding the new one) could run their supposedly mutually-exclusive sections
        // concurrently - defeating the whole point. The actual JVM interleaving needed to trigger
        // that is timing-dependent and not reliably forceable in a test, so this instead verifies the
        // fix's real invariant directly: the lock entry must survive a full populate-then-delete
        // cycle unchanged, which is what actually guarantees computeIfAbsent always returns the same
        // object for a given volume name for the life of the process.
        Path codePath = Files.createDirectory(tempDir.resolve("lock-persist-code"));
        Files.write(codePath.resolve("bundle.bin"), new byte[8 * 1024]); // 8 KiB

        LambdaFunction v1 = new LambdaFunction();
        v1.setFunctionName("lock-persist-fn");
        v1.setRuntime("nodejs20.x");
        v1.setHandler("index.handler");
        v1.setCodeLocalPath(codePath.toString());
        v1.setCodeSha256("lock-persist-fn-sha-v1");
        String volumeV1 = ContainerLauncher.codeVolumeName(v1);

        LambdaFunction v2 = new LambdaFunction();
        v2.setFunctionName("lock-persist-fn");
        v2.setRuntime("nodejs20.x");
        v2.setHandler("index.handler");
        v2.setCodeLocalPath(codePath.toString());
        v2.setCodeSha256("lock-persist-fn-sha-v2");

        long originalBytes = ContainerLauncher.CODE_VOLUME_MIN_BYTES;
        long originalGrace = ContainerLauncher.VOLUME_CLEANUP_GRACE_MS;
        try {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = 4 * 1024;
            launcher.launch(v1);
            assertTrue(launcher.hasCodeVolumeLock(volumeV1), "lock must exist right after populate");

            launcher.launch(v2); // supersedes v1's volume, queues it for cleanup
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = -1;
            launcher.cleanupSupersededVolumes(); // deletes v1's volume for real

            assertTrue(launcher.hasCodeVolumeLock(volumeV1),
                    "lock must still exist even after the volume itself was deleted - "
                            + "pruning it here is exactly the bug this test guards against");
        } finally {
            ContainerLauncher.CODE_VOLUME_MIN_BYTES = originalBytes;
            ContainerLauncher.VOLUME_CLEANUP_GRACE_MS = originalGrace;
        }
    }

    /** Builds a tar archive matching what {@code docker cp}/{@code copyArchiveFromContainerCmd}
     *  returns for a directory: the directory itself as a leading entry, then each name as a direct
     *  child, executable. */
    private static byte[] tarOf(String... binaryNames) throws IOException {
        var entries = new java.util.LinkedHashMap<String, Boolean>();
        for (String name : binaryNames) {
            entries.put(name, true);
        }
        return tarOfRaw(entries);
    }

    /** Like {@link #tarOf}, but lets each entry's executable bit be controlled individually
     *  (name -> executable), so filtering logic (non-executable files, nested paths) can be
     *  exercised. Names containing "/" are written as-is (not prefixed), to model entries nested
     *  more than one level below the extensions directory. */
    private static byte[] tarOfRaw(java.util.Map<String, Boolean> nameToExecutable) throws IOException {
        var bos = new java.io.ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            TarArchiveEntry dir = new TarArchiveEntry("extensions/");
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            for (var e : nameToExecutable.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry("extensions/" + e.getKey());
                entry.setMode(e.getValue() ? 0100755 : 0100644); // executable vs. non-executable regular file
                entry.setSize(0);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Stubs container-123's /opt/extensions listing (via the Docker archive API, not exec) to
     *  return the given binary names, and stubs exec of each resulting /opt/extensions/<name> path
     *  to succeed immediately. */
    private List<ExecCreateCmd> stubExtensionDiscovery(String... binaryNames) throws IOException {
        return stubExtensionDiscoveryFromTar(tarOf(binaryNames));
    }

    /** Same as {@link #stubExtensionDiscovery}, but takes a caller-built tar so tests can exercise
     *  {@code listExtensionBinaries}' entry filtering (non-executable files, nested paths) rather
     *  than only the convenience all-executable-direct-children shape. Returns the {@code ExecCreateCmd}
     *  mock for each launched binary, in launch order — pass it to {@link #capturedLaunchPaths} after
     *  calling {@code launch()} to assert on exactly which discovered names were (and weren't) launched. */
    private List<ExecCreateCmd> stubExtensionDiscoveryFromTar(byte[] tar) {
        CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class);
        when(copyCmd.exec()).thenReturn(new java.io.ByteArrayInputStream(tar));
        when(dockerClient.copyArchiveFromContainerCmd("container-123", "/opt/extensions"))
                .thenReturn(copyCmd);

        List<ExecCreateCmd> launchCmds = new java.util.ArrayList<>();
        lenient().when(dockerClient.execCreateCmd("container-123"))
                .thenAnswer(invocation -> {
                    ExecCreateCmd launchCmd = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
                    ExecCreateCmdResponse launchResponse = mock(ExecCreateCmdResponse.class);
                    when(launchResponse.getId()).thenReturn("exec-launch-" + java.util.UUID.randomUUID());
                    when(launchCmd.exec()).thenReturn(launchResponse);
                    launchCmds.add(launchCmd);
                    return launchCmd;
                });
        lenient().when(dockerClient.execStartCmd(argThat(id -> id != null && id.startsWith("exec-launch-"))))
                .thenAnswer(invocation -> {
                    ExecStartCmd start = mock(ExecStartCmd.class);
                    when(start.exec(any())).thenAnswer(startInvocation -> {
                        @SuppressWarnings("unchecked")
                        ResultCallback<Frame> cb = startInvocation.getArgument(0);
                        cb.onComplete();
                        return cb;
                    });
                    return start;
                });
        // launcher.launch(fn) populates this list after this method returns.
        return launchCmds;
    }

    /** Overrides the default exec-start stub so the callback the launcher supplies is driven with a
     *  real STDOUT {@link Frame} carrying {@code output}, exercising the frame-to-CloudWatch path
     *  rather than only completing the callback. */
    private void stubExecStartEmittingFrame(String output) {
        lenient().when(dockerClient.execStartCmd(argThat(id -> id != null && id.startsWith("exec-launch-"))))
                .thenAnswer(invocation -> {
                    ExecStartCmd start = mock(ExecStartCmd.class);
                    when(start.exec(any())).thenAnswer(startInvocation -> {
                        @SuppressWarnings("unchecked")
                        ResultCallback<Frame> cb = startInvocation.getArgument(0);
                        cb.onNext(new Frame(StreamType.STDOUT, output.getBytes(StandardCharsets.UTF_8)));
                        cb.onComplete();
                        return cb;
                    });
                    return start;
                });
    }

    /** Extracts the single command-line argument each mock in {@code launchCmds} was called with
     *  via {@code withCmd(String...)}, in call order — the {@code /opt/extensions/<name>} path
     *  each discovered extension binary was launched with. */
    private static List<String> capturedLaunchPaths(List<ExecCreateCmd> launchCmds) {
        List<String> paths = new java.util.ArrayList<>();
        for (ExecCreateCmd cmd : launchCmds) {
            ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
            verify(cmd).withCmd(captor.capture());
            paths.add(captor.getValue()[0]);
        }
        return paths;
    }

    @Test
    void launchFunction_discoversAndLaunchesExtensionBinaries() throws Exception {
        stubExtensionDiscovery("lambda-adapter");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("with-extension-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        // The extension binary was discovered via the archive API and execed by its full
        // /opt/extensions path, after the container was started (real AWS starts extensions once
        // the container is up, alongside the runtime).
        verify(dockerClient).copyArchiveFromContainerCmd("container-123", "/opt/extensions");
        verify(dockerClient, atLeastOnce()).execCreateCmd(eq("container-123"));

        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());
        inOrder.verify(dockerClient).copyArchiveFromContainerCmd("container-123", "/opt/extensions");
        inOrder.verify(dockerClient, atLeastOnce()).execCreateCmd("container-123");
    }

    /**
     * Extension stdout/stderr must reach the function's CloudWatch log group. `docker logs` — and
     * therefore ContainerLogStreamer.attach(), which uses logContainerCmd — only covers the
     * container's PID 1 output, so an exec's stream never reaches the container log. Without
     * explicit forwarding an observability extension's output is dropped entirely.
     *
     * <p>Uses a real ContainerLogStreamer over a mocked CloudWatchLogsService so the assertion
     * covers the actual frame-to-log-event path rather than just that a mock was called.
     */
    @Test
    void launchFunction_forwardsExtensionOutputToCloudWatchLogs() throws Exception {
        CloudWatchLogsService cloudWatchLogs = mock(CloudWatchLogsService.class);
        ContainerReachableEndpoint reachableEndpoint =
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer);
        ContainerLauncher launcherWithRealStreamer = new ContainerLauncher(
                new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer),
                lifecycleManager,
                new ContainerLogStreamer(dockerClient, cloudWatchLogs),
                imageResolver, runtimeApiServerFactory, dockerHostResolver, config,
                ecrRegistryManager,
                mock(io.github.hectorvent.floci.services.lambda.LambdaLayerService.class),
                new LaunchedContainerAwsEnv(reachableEndpoint),
                executionRoleCredentials);

        stubExtensionDiscovery("otel-collector");
        // Feed a real stdout frame through whatever callback the launcher hands to execStartCmd.
        stubExecStartEmittingFrame("extension started on :8080\n");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("observability-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcherWithRealStreamer.launch(fn);

        // The frame became a CloudWatch log event in the function's own log group. Forwarding goes
        // through the account-aware overload with a null account id: exec streams have no owning
        // account of their own, so they land in the default account's copy of the log group.
        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, atLeastOnce()).putLogEventsForAccount(
                isNull(), eq("/aws/lambda/observability-fn"), anyString(), events.capture(), anyString());
        assertTrue(events.getAllValues().stream()
                        .flatMap(List::stream)
                        .anyMatch(e -> "extension started on :8080".equals(e.get("message"))),
                "extension stdout must be forwarded to the function's CloudWatch log group");
    }

    /**
     * The log group and stream must exist before extensions are launched: they can log immediately,
     * and putLogEvents against a missing stream is swallowed at debug level — silently losing
     * exactly the early startup output this forwarding exists to capture.
     */
    @Test
    void launchFunction_createsLogGroupBeforeLaunchingExtensions() throws Exception {
        stubExtensionDiscovery("lambda-adapter");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("ordering-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        InOrder inOrder = inOrder(logStreamer, dockerClient);
        inOrder.verify(logStreamer).ensureLogGroupAndStream(
                eq("/aws/lambda/ordering-fn"), anyString(), anyString());
        inOrder.verify(dockerClient, atLeastOnce()).execCreateCmd("container-123");
    }

    /**
     * The init-readiness barrier abanna asked for on PR #1773: extension processes are started as
     * detached execs, so without waiting the caller could enqueue the first invocation before an
     * extension was ready for it and the adapter would silently miss that invoke. The launch must
     * arm the barrier with the discovered binary count *before* starting any exec (so a
     * fast-starting extension can't become ready before there is a latch to count it down), and
     * must not return until the extensions are init-ready.
     */
    @Test
    void launchFunction_waitsForExtensionsToBecomeReadyBeforeReturning() throws Exception {
        stubExtensionDiscovery("lambda-adapter", "otel-collector");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("barrier-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        // Readiness is delayed: awaitExtensionsReady only reports success after a pause, standing
        // in for extension processes that take a moment to start up and poll for their first event.
        AtomicBoolean readinessComplete = new AtomicBoolean(false);
        when(runtimeApiServer.awaitExtensionsReady(anyLong())).thenAnswer(inv -> {
            Thread.sleep(150);
            readinessComplete.set(true);
            return true;
        });

        launcher.launch(fn);

        assertTrue(readinessComplete.get(),
                "launch() must not return before the extensions are init-ready");

        // The barrier is armed with the number of binaries actually discovered, and armed before
        // any extension process is started.
        verify(runtimeApiServer).expectExtensions(2);
        InOrder inOrder = inOrder(runtimeApiServer, dockerClient);
        inOrder.verify(runtimeApiServer).expectExtensions(2);
        inOrder.verify(dockerClient, atLeastOnce()).execCreateCmd("container-123");
        inOrder.verify(runtimeApiServer).awaitExtensionsReady(anyLong());
    }

    /**
     * A slow or crashed extension must degrade to "invocations run without it", not fail the whole
     * function launch — the same outcome as before the barrier existed.
     */
    @Test
    void launchFunction_extensionReadinessTimeout_doesNotFailLaunch() throws Exception {
        stubExtensionDiscovery("never-registers");

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("timeout-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        when(runtimeApiServer.awaitExtensionsReady(anyLong())).thenReturn(false);

        ContainerHandle handle = launcher.launch(fn);

        assertNotNull(handle, "a readiness timeout must not fail the launch");
        assertEquals("container-123", handle.getContainerId());
    }

    /**
     * The common case — no /opt/extensions directory — must not wait at all: the barrier is armed
     * with zero and the launch proceeds immediately.
     */
    @Test
    void launchFunction_noExtensions_armsBarrierWithZeroAndDoesNotBlock() throws Exception {
        CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class);
        when(copyCmd.exec()).thenThrow(new NotFoundException("no such directory"));
        when(dockerClient.copyArchiveFromContainerCmd("container-123", "/opt/extensions"))
                .thenReturn(copyCmd);

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("no-extension-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        verify(runtimeApiServer).expectExtensions(0);
    }

    @Test
    void launchFunction_noExtensionsDirectory_doesNotFailLaunch() throws Exception {
        CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class);
        when(copyCmd.exec()).thenThrow(new NotFoundException("no such directory"));
        when(dockerClient.copyArchiveFromContainerCmd("container-123", "/opt/extensions"))
                .thenReturn(copyCmd);

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("no-extension-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        // The discovery probe still runs (best-effort), but nothing beyond it — no extension
        // binary path is ever execed since the directory doesn't exist.
        verify(dockerClient, never()).execCreateCmd("container-123");
    }

    @Test
    void launchFunction_extensionDiscovery_filtersNonExecutableAndNestedEntries() throws Exception {
        var entries = new java.util.LinkedHashMap<String, Boolean>();
        entries.put("lambda-adapter", true);           // direct child, executable: launched
        entries.put("README.md", false);                // direct child, not executable: skipped
        entries.put("nested/inner-binary", true);        // nested (not a direct child): skipped
        List<ExecCreateCmd> launchCmds = stubExtensionDiscoveryFromTar(tarOfRaw(entries));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("mixed-extensions-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        assertEquals(List.of("/opt/extensions/lambda-adapter"), capturedLaunchPaths(launchCmds),
                "only the direct, executable entry should be launched");
    }

    @Test
    void launchFunction_multipleExtensionBinaries_allDiscoveredAndLaunched() throws Exception {
        List<ExecCreateCmd> launchCmds = stubExtensionDiscoveryFromTar(tarOf("lambda-adapter", "otel-collector"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("multi-extension-fn");
        fn.setPackageType("Image");
        fn.setImageUri("123456789012.dkr.ecr.us-east-1.amazonaws.com/repo:latest");

        launcher.launch(fn);

        assertEquals(List.of("/opt/extensions/lambda-adapter", "/opt/extensions/otel-collector"),
                capturedLaunchPaths(launchCmds));
    }
}
