package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        lenient().when(config.defaultRegion()).thenReturn("us-east-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());

        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        ContainerReachableEndpoint reachableEndpoint =
                new ContainerReachableEndpoint(config, dockerHostResolver, embeddedDnsServer);
        launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager,
                mock(io.github.hectorvent.floci.services.lambda.LambdaLayerService.class), reachableEndpoint);

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
     * <p>Code is now served from a read-only named volume that is populated once per code version.
     * Populating spins up a throwaway helper container (also via {@code create}), so on a
     * first-populate launch {@code create} is called twice: the helper first, then the real
     * container. The real container is the one that mounts {@code /var/task} read-only from the
     * volume, so we identify it by that mount rather than by call order.
     */
    private ContainerSpec captureRealContainerSpec() {
        ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).create(specCaptor.capture());
        List<ContainerSpec> specs = specCaptor.getAllValues();
        // The real container mounts /var/task read-only; the helper mounts it read-write.
        return specs.stream()
                .filter(s -> s.mounts() != null && s.mounts().stream()
                        .anyMatch(m -> "/var/task".equals(m.getTarget()) && Boolean.TRUE.equals(m.getReadOnly())))
                .reduce((first, second) -> second)   // last match, defensively
                // Fall back to the last create() (the real container) for functions with no code volume.
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
    void launchFunction_createsWithoutBindMounts() throws Exception {
        Path codePath = Files.createDirectory(tempDir.resolve("code"));

        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("standard-fn");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setCodeLocalPath(codePath.toString());

        launcher.launch(fn);

        ContainerSpec spec = captureRealContainerSpec();
        // Code is no longer bind-mounted; the real container still has NO binds...
        assertTrue(spec.binds().isEmpty(), "Function should NOT have bind mounts");
        // ...but it DOES mount /var/task read-only from the code volume.
        Mount codeMount = varTaskVolumeMount(spec);
        assertNotNull(codeMount, "/var/task should be a named-volume mount");
        assertEquals(MountType.VOLUME, codeMount.getType(), "/var/task should be a volume mount");
        assertTrue(Boolean.TRUE.equals(codeMount.getReadOnly()), "/var/task volume should be read-only");
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

        // The code volume is populated by a helper container BEFORE the real container is created:
        // create(helper) -> start(helper) -> copy code to /var/task (helper) -> stopAndRemove(helper)
        // -> create(real) -> start(real). No per-container /var/task copy on the real container.
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        // Helper container: created, started, code tar-copied to /var/task, then discarded.
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).stopAndRemove(eq("container-123"), any());
        // Real container: created, then started (no /var/task copy — it mounts the volume).
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // The only tar-copy in this launch is the one-time /var/task populate into the helper.
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "code should be tar-copied to /var/task while populating the code volume");
        assertEquals(1, capturedRemotePaths.stream().filter("/var/task"::equals).count(),
                "/var/task should be copied exactly once (the one-time volume populate)");

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

        // The critical invariant preserved from #466: the REAL container must be created
        // before its bootstrap is copied, and started only after. The /var/task code copy now
        // happens once during the volume populate (into a helper) rather than per container.
        //
        // Ordering:
        //   helper: create -> start -> copy /var/task -> stopAndRemove
        //   real:   create -> copy bootstrap to /var/runtime -> start
        InOrder inOrder = inOrder(lifecycleManager, dockerClient);
        // Helper populate: code copied to /var/task, then helper discarded.
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).stopAndRemove(eq("container-123"), any());
        // Real container: created, bootstrap copied to /var/runtime, then started.
        inOrder.verify(lifecycleManager).create(any());
        inOrder.verify(dockerClient).copyArchiveToContainerCmd("container-123");
        inOrder.verify(lifecycleManager).startCreated(eq("container-123"), any());

        // /var/task is populated once (into the helper); bootstrap is copied to /var/runtime
        // on the real container.
        assertTrue(capturedRemotePaths.contains("/var/task"),
                "code should be tar-copied to /var/task while populating the code volume");
        assertTrue(capturedRemotePaths.contains("/var/runtime"),
                "bootstrap should be copied to /var/runtime on the real container");

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
}
