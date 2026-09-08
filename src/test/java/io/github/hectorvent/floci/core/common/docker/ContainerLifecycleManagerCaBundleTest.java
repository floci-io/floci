package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every emulator-created container passes through {@link ContainerLifecycleManager#create}, so
 * asserting the CA trust here covers Lambda, ECS, MWAA, Flink, RDS and the rest by construction.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerCaBundleTest {

    private static final String BUNDLE_PEM = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";

    @Mock DockerClient dockerClient;
    @Mock ImageCacheService imageCacheService;
    @Mock ContainerDetector containerDetector;
    @Mock PortAllocator portAllocator;
    @Mock EmulatorConfig config;
    @Mock EmulatorConfig.DockerConfig dockerConfig;
    @Mock EmulatorConfig.TlsConfig tlsConfig;
    @Mock EmulatorConfig.StorageConfig storageConfig;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        forgetBootstrapTlsDir();
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(config.storage()).thenReturn(storageConfig);
        lenient().when(storageConfig.persistentPath()).thenReturn(tempDir.toString());
        lenient().when(imageCacheService.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void forgetBootstrapTlsDir() {
        // TlsConfigSource.resolvedTlsDir is static: a TLS-on bootstrap run by another test class
        // in this JVM would otherwise steer the bundle lookup at that test's directory.
        System.setProperty("floci.tls.enabled", "false");
        new TlsConfigSource();
        System.clearProperty("floci.tls.enabled");
    }

    @Test
    void tlsOnCopiesTheBundleAndAppendsTheTrustEnvAfterTheSpecEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        Path bundle = writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);

        assertEquals("container-id", manager().create(specWithEnv(List.of("FOO=bar"))));

        List<String> env = capturedEnv(createCmd);
        assertEquals(List.of("FOO=bar",
                "SSL_CERT_FILE=/etc/floci-ca-bundle.pem",
                "CURL_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "REQUESTS_CA_BUNDLE=/etc/floci-ca-bundle.pem",
                "NODE_EXTRA_CA_CERTS=/etc/floci-ca-bundle.pem",
                "AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"), env);

        verify(copyCmd).withRemotePath("/etc");
        ArgumentCaptor<InputStream> tar = ArgumentCaptor.forClass(InputStream.class);
        verify(copyCmd).withTarInputStream(tar.capture());
        verify(copyCmd).exec();
        try (TarArchiveInputStream in = new TarArchiveInputStream(tar.getValue())) {
            TarArchiveEntry entry = in.getNextEntry();
            assertEquals("floci-ca-bundle.pem", entry.getName());
            assertEquals(0644, entry.getMode() & 0777, "world-readable, so a non-root USER can read it");
            assertEquals(Files.readString(bundle), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertNull(in.getNextEntry(), "the archive holds only the bundle");
        }
    }

    @Test
    void specValuesWinOverTheInjectedEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        when(dockerClient.copyArchiveToContainerCmd("container-id"))
                .thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));

        manager().create(specWithEnv(List.of("SSL_CERT_FILE=/my/own.pem", "NODE_EXTRA_CA_CERTS=")));

        List<String> env = capturedEnv(createCmd);
        assertEquals(1, env.stream().filter(e -> e.startsWith("SSL_CERT_FILE=")).count());
        assertEquals(1, env.stream().filter(e -> e.startsWith("NODE_EXTRA_CA_CERTS=")).count());
        assertTrue(env.contains("SSL_CERT_FILE=/my/own.pem"));
        assertTrue(env.contains("NODE_EXTRA_CA_CERTS="));
        assertTrue(env.contains("AWS_CA_BUNDLE=/etc/floci-ca-bundle.pem"));
    }

    @Test
    void anEmptySpecEnvStillGetsTheTrustEnv() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = stubCreateContainer();
        when(dockerClient.copyArchiveToContainerCmd("container-id"))
                .thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));

        manager().create(new ContainerSpec("busybox:stable"));

        assertEquals(5, capturedEnv(createCmd).size());
    }

    @Test
    void tlsOffLeavesTheContainerAlone() {
        when(tlsConfig.enabled()).thenReturn(false);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(specWithEnv(List.of("FOO=bar")));

        assertEquals(List.of("FOO=bar"), capturedEnv(createCmd));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void tlsOffWithNoEnvSetsNoEnvAtAll() {
        when(tlsConfig.enabled()).thenReturn(false);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        verify(createCmd, never()).withEnv(any(List.class));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void aMissingBundleMeansNoEnvAndNoCopy() {
        when(tlsConfig.enabled()).thenReturn(true);
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(specWithEnv(List.of("FOO=bar")));

        assertEquals(List.of("FOO=bar"), capturedEnv(createCmd));
        verify(dockerClient, never()).copyArchiveToContainerCmd(anyString());
    }

    @Test
    void aFailedCopyRecreatesTheContainerWithoutTheTrustVariables() throws Exception {
        // SSL_CERT_FILE and friends replace the image's trust store, so they must never name a
        // file that is not there: an image without /etc keeps its own trust instead.
        when(tlsConfig.enabled()).thenReturn(true);
        writeBundle();
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse first = mock(CreateContainerResponse.class);
        when(first.getId()).thenReturn("container-id");
        CreateContainerResponse second = mock(CreateContainerResponse.class);
        when(second.getId()).thenReturn("container-id-2");
        when(createCmd.exec()).thenReturn(first, second);
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        when(copyCmd.exec()).thenThrow(new NotFoundException("Could not find the file /etc in container"));
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("container-id")).thenReturn(removeCmd);

        assertEquals("container-id-2", manager().create(specWithEnv(List.of("FOO=bar"))));

        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd, times(2)).withEnv(env.capture());
        assertEquals(6, env.getAllValues().get(0).size(), "the first attempt carried the trust variables");
        assertEquals(List.of("FOO=bar"), env.getAllValues().get(1), "the retry carries only the spec's own");
        verify(dockerClient, times(1)).copyArchiveToContainerCmd(anyString());
        verify(portAllocator, never()).allocateAny();
    }

    private Path writeBundle() throws Exception {
        Path tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        return Files.writeString(tlsDir.resolve("floci-ca-bundle.pem"), BUNDLE_PEM);
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }

    private static ContainerSpec specWithEnv(List<String> env) {
        return new ContainerSpec("busybox:stable", null, env, null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of());
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    @SuppressWarnings("unchecked")
    private static List<String> capturedEnv(CreateContainerCmd createCmd) {
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(createCmd).withEnv(env.capture());
        return env.getValue();
    }
}
