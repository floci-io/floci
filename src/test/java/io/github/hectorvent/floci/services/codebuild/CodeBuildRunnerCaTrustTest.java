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

import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
     * {@code NODE_EXTRA_CA_CERTS}/{@code AWS_CA_BUNDLE} for another CA it genuinely needs (e.g. a
     * corporate proxy CA unrelated to AWS traffic). Pointing both vars straight at Floci's own
     * cert file — the first fix for this thread — makes Floci's spoofed endpoints reachable but
     * silently drops that other CA, since {@code NODE_EXTRA_CA_CERTS} normally *extends* Node's
     * trust store rather than replacing it wholesale. The env vars must instead point at a
     * Floci-owned combined-bundle path so {@link #stageFlociCaCertIfNeeded} can write both CAs
     * into one file.
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

        assertTrue(env.contains("NODE_EXTRA_CA_CERTS=" + CodeBuildRunner.COMBINED_CA_BUNDLE_CONTAINER_PATH),
                () -> "expected NODE_EXTRA_CA_CERTS to point at the combined bundle in " + env);
        assertTrue(env.contains("AWS_CA_BUNDLE=" + CodeBuildRunner.COMBINED_CA_BUNDLE_CONTAINER_PATH),
                () -> "expected AWS_CA_BUNDLE to point at the combined bundle in " + env);
    }

    /**
     * When a build supplies genuinely <em>different</em> paths for NODE_EXTRA_CA_CERTS and
     * AWS_CA_BUNDLE (e.g. a Node-specific CA and a separate AWS SDK CA bundle), collapsing both
     * onto a single "preexisting" value silently drops whichever one wasn't picked — the previous
     * fix kept only NODE_EXTRA_CA_CERTS via {@code Optional.or(...)}. Both must be preserved, each
     * merged with Floci's cert into its own bundle.
     */
    @Test
    void bothDistinctCaOverridesSurviveIndependently() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "fake-cert-pem");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        ParsedBuildspec buildspecWithDistinctCaOverrides = new ParsedBuildspec(
                Map.of("NODE_EXTRA_CA_CERTS", "/etc/node-ca.pem",
                        "AWS_CA_BUNDLE", "/etc/aws-ca.pem"),
                Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), null);

        List<String> env = runner().buildEnvList(
                "us-east-1", build(), project(), buildspecWithDistinctCaOverrides, "log-stream");

        assertFalse(env.contains("NODE_EXTRA_CA_CERTS=" + CodeBuildRunner.COMBINED_CA_BUNDLE_CONTAINER_PATH),
                () -> "NODE_EXTRA_CA_CERTS and AWS_CA_BUNDLE differ, so they must not share one bundle: " + env);
        assertFalse(env.contains("AWS_CA_BUNDLE=" + CodeBuildRunner.COMBINED_CA_BUNDLE_CONTAINER_PATH),
                () -> "NODE_EXTRA_CA_CERTS and AWS_CA_BUNDLE differ, so they must not share one bundle: " + env);
        assertFalse(env.stream().anyMatch(e -> e.equals("NODE_EXTRA_CA_CERTS=/etc/node-ca.pem")),
                () -> "NODE_EXTRA_CA_CERTS must still be redirected to a Floci-merged bundle: " + env);
        assertFalse(env.stream().anyMatch(e -> e.equals("AWS_CA_BUNDLE=/etc/aws-ca.pem")),
                () -> "AWS_CA_BUNDLE must still be redirected to a Floci-merged bundle: " + env);
        String nodeTarget = env.stream().filter(e -> e.startsWith("NODE_EXTRA_CA_CERTS=")).findFirst().orElseThrow();
        String awsTarget = env.stream().filter(e -> e.startsWith("AWS_CA_BUNDLE=")).findFirst().orElseThrow();
        assertFalse(nodeTarget.equals("NODE_EXTRA_CA_CERTS=" + awsTarget.substring("AWS_CA_BUNDLE=".length())),
                () -> "distinct source CAs must land in distinct bundles, not share one path: " + env);
    }

    /**
     * When no buildspec/project/build-override/parameter/secret source defines a CA var, staging
     * must still write Floci's cert to the plain {@code FLOCI_CA_CONTAINER_PATH} (not the combined
     * bundle path) — there is no existing CA to preserve, so the simpler single-file path from the
     * original fix stays correct in the common case.
     */
    @Test
    void stagesPlainFlociCertWhenNoUserCaOverrideExists() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "fake-cert-pem-content");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        org.mockito.ArgumentCaptor<java.io.InputStream> tarCaptor =
                org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        when(copyCmd.withTarInputStream(tarCaptor.capture())).thenReturn(copyCmd);

        stageFlociCaCert("container-id", Optional.empty());

        Map<String, byte[]> entries = readTarEntries(tarCaptor.getValue());
        assertTrue(entries.containsKey(ContainerLauncher.FLOCI_CA_FILE_NAME),
                () -> "expected a plain " + ContainerLauncher.FLOCI_CA_FILE_NAME + " entry, got " + entries.keySet());
        assertFalse(entries.containsKey("floci-ca-bundle.crt"));
        assertArrayEquals("fake-cert-pem-content".getBytes(),
                entries.get(ContainerLauncher.FLOCI_CA_FILE_NAME));
    }

    /**
     * When a buildspec/project/build-override source already points NODE_EXTRA_CA_CERTS or
     * AWS_CA_BUNDLE at another CA bundle, staging must fetch that existing file from the container
     * (via {@code copyArchiveFromContainerCmd}, the same Docker archive API
     * {@code copyArtifactsFromContainer} already uses) and write a combined bundle containing both
     * the user's existing CA content and Floci's cert — dropping neither.
     */
    @Test
    void mergesExistingCaBundleWithFlociCertWhenUserOverrideExists() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "floci-cert-content");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        CopyArchiveFromContainerCmd fromCmd = mock(CopyArchiveFromContainerCmd.class);
        when(fromCmd.exec()).thenReturn(new java.io.ByteArrayInputStream(
                tarOfSingleFile("corporate-ca.pem", "corporate-ca-content")));
        when(dockerClient.copyArchiveFromContainerCmd("container-id", "/etc/corporate-ca.pem"))
                .thenReturn(fromCmd);

        CopyArchiveToContainerCmd toCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(toCmd);
        org.mockito.ArgumentCaptor<java.io.InputStream> tarCaptor =
                org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        when(toCmd.withTarInputStream(tarCaptor.capture())).thenReturn(toCmd);

        stageFlociCaCert("container-id", Optional.of("/etc/corporate-ca.pem"));

        Map<String, byte[]> entries = readTarEntries(tarCaptor.getValue());
        assertTrue(entries.containsKey("floci-ca-bundle.crt"),
                () -> "expected a combined floci-ca-bundle.crt entry, got " + entries.keySet());
        String combined = new String(entries.get("floci-ca-bundle.crt"));
        assertTrue(combined.contains("corporate-ca-content"),
                () -> "expected the existing corporate CA content to survive the merge: " + combined);
        assertTrue(combined.contains("floci-cert-content"),
                () -> "expected Floci's cert content to be included in the merge: " + combined);
    }

    /**
     * If the user-configured CA path never materializes inside the build image (e.g. it is
     * created later by a buildspec install command, not baked in), fetching it fails. Staging must
     * fall back to a Floci-only bundle rather than propagating that failure — the build still
     * needs to be able to reach Floci's spoofed endpoints even though the user's own CA couldn't
     * be located at container-creation time.
     */
    @Test
    void fallsBackToFlociOnlyBundleWhenUserCaPathIsUnreadable() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        Files.writeString(tlsDir.resolve("floci-selfsigned.crt"), "floci-cert-content");

        when(tlsConfig.enabled()).thenReturn(true);
        when(tlsConfig.certPath()).thenReturn(Optional.empty());

        CopyArchiveFromContainerCmd fromCmd = mock(CopyArchiveFromContainerCmd.class);
        when(fromCmd.exec()).thenThrow(new RuntimeException("no such file"));
        when(dockerClient.copyArchiveFromContainerCmd("container-id", "/etc/not-yet-created.pem"))
                .thenReturn(fromCmd);

        CopyArchiveToContainerCmd toCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(toCmd);
        org.mockito.ArgumentCaptor<java.io.InputStream> tarCaptor =
                org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        when(toCmd.withTarInputStream(tarCaptor.capture())).thenReturn(toCmd);

        stageFlociCaCert("container-id", Optional.of("/etc/not-yet-created.pem"));

        Map<String, byte[]> entries = readTarEntries(tarCaptor.getValue());
        assertTrue(entries.containsKey("floci-ca-bundle.crt"));
        assertArrayEquals("floci-cert-content".getBytes(), entries.get("floci-ca-bundle.crt"));
    }

    private static byte[] tarOfSingleFile(String name, String content) throws IOException {
        var bos = new java.io.ByteArrayOutputStream();
        try (org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar =
                     new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bos)) {
            byte[] bytes = content.getBytes();
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
                    new org.apache.commons.compress.archivers.tar.TarArchiveEntry(name);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    private static Map<String, byte[]> readTarEntries(java.io.InputStream tarStream) throws IOException {
        Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        try (org.apache.commons.compress.archivers.tar.TarArchiveInputStream tar =
                     new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(tarStream)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                result.put(entry.getName(), tar.readAllBytes());
            }
        }
        return result;
    }

    private void stageFlociCaCert(String containerId, Optional<String> preexistingCaPath) throws Exception {
        Class<?> targetClass = Class.forName(
                "io.github.hectorvent.floci.services.codebuild.CodeBuildRunner$CaBundleTarget");
        var targetCtor = targetClass.getDeclaredConstructor(String.class, Optional.class);
        targetCtor.setAccessible(true);
        String fileName = preexistingCaPath.isPresent()
                ? CodeBuildRunner.COMBINED_CA_BUNDLE_FILE_NAME
                : ContainerLauncher.FLOCI_CA_FILE_NAME;
        Object target = targetCtor.newInstance(fileName, preexistingCaPath);
        List<Object> targetList = List.of(target);

        Method method = CodeBuildRunner.class.getDeclaredMethod(
                "stageFlociCaCertIfNeeded", String.class, List.class);
        method.setAccessible(true);
        method.invoke(runner(), containerId, targetList);
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

        assertThrows(InvocationTargetException.class,
                () -> stageFlociCaCert("container-id", Optional.empty()));
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
