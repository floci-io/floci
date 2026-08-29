package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codebuild.BuildspecParser.ParsedBuildspec;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When floci.dns.spoof-aws-endpoints routes an explicit AWS endpoint to Floci and self-signed
 * TLS is on, a CodeBuild container that hits that endpoint must trust Floci's cert the same way
 * launched Lambda containers already do ({@link ContainerLauncher#flociCaEnv}) — otherwise the
 * handshake fails before the request reaches the emulator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CodeBuildRunner — Floci CA trust for spoofed AWS endpoints")
class CodeBuildRunnerCaTrustTest {

    @Mock DockerClient dockerClient;
    @Mock ContainerBuilder containerBuilder;
    @Mock ContainerLifecycleManager lifecycleManager;
    @Mock ContainerLogStreamer logStreamer;
    @Mock S3Service s3Service;
    @Mock SsmService ssmService;
    @Mock SecretsManagerService secretsManagerService;
    @Mock EmulatorConfig config;
    @Mock EmulatorConfig.TlsConfig tlsConfig;
    @Mock EmulatorConfig.StorageConfig storageConfig;
    @Mock ContainerDetector containerDetector;
    @Mock RegionResolver regionResolver;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(config.storage()).thenReturn(storageConfig);
        lenient().when(storageConfig.persistentPath()).thenReturn(tempDir.toString());
    }

    @Test
    void includesFlociCaTrustEnvWhenTlsEnabledAndCertReadable() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Path certFile = tlsDir.resolve("floci-selfsigned.crt");
        Files.writeString(certFile, "fake-cert-pem");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        List<String> env = runner().buildEnvList("us-east-1", build(), project(), buildspec(), "log-stream");

        assertTrue(env.contains("NODE_EXTRA_CA_CERTS=" + ContainerLauncher.FLOCI_CA_CONTAINER_PATH),
                () -> "expected NODE_EXTRA_CA_CERTS in " + env);
        assertTrue(env.contains("AWS_CA_BUNDLE=" + ContainerLauncher.FLOCI_CA_CONTAINER_PATH),
                () -> "expected AWS_CA_BUNDLE in " + env);
    }

    @Test
    void omitsFlociCaTrustEnvWhenTlsDisabled() {
        when(tlsConfig.enabled()).thenReturn(false);

        List<String> env = runner().buildEnvList("us-east-1", build(), project(), buildspec(), "log-stream");

        assertFalse(env.stream().anyMatch(e -> e.startsWith("NODE_EXTRA_CA_CERTS=")));
        assertFalse(env.stream().anyMatch(e -> e.startsWith("AWS_CA_BUNDLE=")));
    }

    /**
     * A buildspec, project, build override, parameter-store value, or secret can define its own
     * {@code NODE_EXTRA_CA_CERTS}/{@code AWS_CA_BUNDLE} (e.g. for an unrelated corporate CA). The
     * env-merge order in {@code buildEnvList} put the Floci CA vars first and then layered every
     * other source's env on top with {@code Map.put}, so a user-supplied value for either var
     * silently clobbers the staged Floci CA path — the build then trusts a CA bundle that never
     * includes Floci's cert, and every spoofed HTTPS AWS call fails certificate verification.
     * Floci's CA vars must win regardless of merge order.
     */
    @Test
    void flociCaTrustEnvSurvivesUserSuppliedOverride() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "fake-cert-pem");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        ParsedBuildspec buildspecWithUserCaOverride = new ParsedBuildspec(
                Map.of("NODE_EXTRA_CA_CERTS", "/etc/corporate-ca.pem",
                        "AWS_CA_BUNDLE", "/etc/corporate-ca.pem"),
                Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), null);

        List<String> env = runner().buildEnvList(
                "us-east-1", build(), project(), buildspecWithUserCaOverride, "log-stream");

        assertTrue(env.contains("NODE_EXTRA_CA_CERTS=" + ContainerLauncher.FLOCI_CA_CONTAINER_PATH),
                () -> "expected Floci's NODE_EXTRA_CA_CERTS to win over the buildspec override in " + env);
        assertTrue(env.contains("AWS_CA_BUNDLE=" + ContainerLauncher.FLOCI_CA_CONTAINER_PATH),
                () -> "expected Floci's AWS_CA_BUNDLE to win over the buildspec override in " + env);
    }

    /**
     * If staging the Floci CA cert into the container fails, the build must not continue
     * silently: {@code buildEnvList} still points NODE_EXTRA_CA_CERTS/AWS_CA_BUNDLE at
     * {@link ContainerLauncher#FLOCI_CA_DIR}, so a build that swallows this failure runs with a
     * trust anchor that was never actually written, and every spoofed HTTPS AWS call fails
     * certificate verification instead of reaching Floci.
     */
    @Test
    void stagingFailurePropagatesInsteadOfBeingSwallowed() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "fake-cert-pem");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        when(copyCmd.exec()).thenThrow(new RuntimeException("docker daemon unreachable"));

        assertThrows(InvocationTargetException.class, () -> invokeStageFlociCaCertIfNeeded("container-id"));
    }

    private void invokeStageFlociCaCertIfNeeded(String containerId) throws Exception {
        Method method = CodeBuildRunner.class.getDeclaredMethod("stageFlociCaCertIfNeeded", String.class);
        method.setAccessible(true);
        method.invoke(runner(), containerId);
    }

    private CodeBuildRunner runner() {
        return new CodeBuildRunner(dockerClient, containerBuilder, lifecycleManager, logStreamer,
                s3Service, ssmService, secretsManagerService, config, containerDetector, regionResolver);
    }

    private static Build build() {
        return new Build();
    }

    private static Project project() {
        Project project = new Project();
        ProjectEnvironment env = new ProjectEnvironment();
        env.setImage("aws/codebuild/standard:7.0");
        project.setEnvironment(env);
        return project;
    }

    private static ParsedBuildspec buildspec() {
        return new ParsedBuildspec(Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), null);
    }
}
