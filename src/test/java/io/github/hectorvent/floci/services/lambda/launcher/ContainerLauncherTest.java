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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        ContainerReachableEndpoint reachableEndpoint =
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer);
        LaunchedContainerAwsEnv awsEnv = new LaunchedContainerAwsEnv(reachableEndpoint);
        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager,
                mock(io.github.hectorvent.floci.services.lambda.LambdaLayerService.class), awsEnv);

        when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
        when(runtimeApiServer.getPort()).thenReturn(9000);
        when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");

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

        List<String> env = captureRealContainerSpec().env();
        // Docker honours the last occurrence of a duplicate Env entry, so user
        // overrides must appear after the Floci defaults.
        int defaultKeyIdx = -1;
        int userKeyIdx = -1;
        int defaultSecretIdx = -1;
        int userSecretIdx = -1;
        for (int i = 0; i < env.size(); i++) {
            if (env.get(i).startsWith("AWS_ACCESS_KEY_ID=") && userKeyIdx < 0 && !env.get(i).equals("AWS_ACCESS_KEY_ID=user-key")) {
                defaultKeyIdx = i;
            }
            if (env.get(i).equals("AWS_ACCESS_KEY_ID=user-key")) userKeyIdx = i;
            if (env.get(i).startsWith("AWS_SECRET_ACCESS_KEY=") && userSecretIdx < 0 && !env.get(i).equals("AWS_SECRET_ACCESS_KEY=user-secret")) {
                defaultSecretIdx = i;
            }
            if (env.get(i).equals("AWS_SECRET_ACCESS_KEY=user-secret")) userSecretIdx = i;
        }
        assertTrue(defaultKeyIdx >= 0, "default AWS_ACCESS_KEY_ID still present");
        assertTrue(userKeyIdx > defaultKeyIdx,
                "user AWS_ACCESS_KEY_ID must appear after the default");
        assertTrue(defaultSecretIdx >= 0, "default AWS_SECRET_ACCESS_KEY still present");
        assertTrue(userSecretIdx > defaultSecretIdx,
                "user AWS_SECRET_ACCESS_KEY must appear after the default");

        // AWS_SESSION_TOKEN was not overridden so the default remains.
        assertEquals(1, env.stream().filter(e -> e.startsWith("AWS_SESSION_TOKEN=")).count(),
                "AWS_SESSION_TOKEN should retain its default exactly once");
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
        // Old code-version volumes are NOT eagerly deleted (race fix); they are label-pruned instead.
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
