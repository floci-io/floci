package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.CopyArchiveToContainerCmdImpl;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterIdentity;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import io.floci.aws.services.eks.model.LogSetup;
import io.floci.aws.services.eks.model.Logging;
import io.floci.aws.services.eks.model.OidcIdentity;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static io.floci.aws.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksClusterManagerArchiveRetryTest {

    private static final String CONTAINER_ID = "container-1";
    private static final String CA_PEM = "-----BEGIN CERTIFICATE-----\ntest-only-ca\n-----END CERTIFICATE-----\n";
    private static final byte[] PRIVATE_KEY = "test-only generated private key\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PUBLIC_KEY = "test-only generated public key\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private final List<ArchiveUpload> uploads = new ArrayList<>();
    private Function<ArchiveUpload, RuntimeException> failure = upload -> null;
    private EmulatorConfig.EksServiceConfig eks;
    private ContainerLifecycleManager lifecycleManager;
    private EksOidcService oidcService;
    private EksClusterManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        eks = mock(EmulatorConfig.EksServiceConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.services()).thenReturn(services);
        when(services.eks()).thenReturn(eks);
        when(config.tls()).thenReturn(tls);
        when(config.port()).thenReturn(4566);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(tls.enabled()).thenReturn(true);
        when(eks.podIdentityWebhook()).thenReturn(true);
        when(eks.dataPath()).thenReturn(tempDir.toString());
        when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
        when(eks.imageTemplate()).thenReturn(Optional.empty());
        when(eks.dockerNetwork()).thenReturn(Optional.empty());
        when(eks.endpointMode()).thenReturn("host");

        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd.Exec copyExec = mock(CopyArchiveToContainerCmd.Exec.class);
        when(copyExec.exec(any())).thenAnswer(invocation -> {
            CopyArchiveToContainerCmd command = invocation.getArgument(0);
            ArchiveUpload upload = consumeArchive(command);
            uploads.add(upload);
            RuntimeException copyFailure = failure.apply(upload);
            if (copyFailure != null) {
                throw copyFailure;
            }
            return null;
        });
        when(docker.copyArchiveToContainerCmd(anyString())).thenAnswer(invocation ->
                new CopyArchiveToContainerCmdImpl(copyExec, invocation.getArgument(0)));
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        when(lifecycleManager.create(any())).thenReturn(CONTAINER_ID);
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(CONTAINER_ID, Map.of()));

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        FlociCertificateAuthority certificateAuthority = inlineMock(FlociCertificateAuthority.class);
        when(certificateAuthority.caPem()).thenReturn(CA_PEM);
        oidcService = mock(EksOidcService.class);

        manager = new EksClusterManager(containerBuilder, lifecycleManager,
                mock(ContainerDetector.class), mock(PortAllocator.class), dockerHostResolver,
                mock(EcrRegistryManager.class), config, regionResolver, null, oidcService, certificateAuthority,
                null, null, 0L);
    }

    @Test
    void auditPolicyRetriesAfterTheFirstArchiveIsConsumed() throws IOException {
        Path policy = tempDir.resolve("audit-policy.yaml");
        byte[] content = EksClusterManager.buildAuditPolicy().getBytes(StandardCharsets.UTF_8);
        Files.write(policy, content);
        failFirstUploadOf("audit-policy.yaml");

        manager.copyAuditPolicyIntoContainer(CONTAINER_ID, policy.toString(), "demo");

        assertEquals(2, uploads.size(), "a consumed audit policy archive must be rebuilt after a broken pipe");
        for (ArchiveUpload upload : uploads) {
            assertHostFileUpload(upload, policy, content);
        }
    }

    @Test
    void signingPrivateKeyRetriesBeforeUploadingThePublicKey() throws IOException {
        EksClusterManager.SigningKeyFiles keys = signingKeyFiles();
        failFirstUploadOf("sa-signing-key.pem");

        manager.copySigningKeysIntoContainer(CONTAINER_ID, keys, "demo");

        assertEquals(3, uploads.size(), "the private key must recover before the public key is uploaded");
        assertHostFileUpload(uploads.get(0), keys.signingKeyPath(), PRIVATE_KEY);
        assertHostFileUpload(uploads.get(1), keys.signingKeyPath(), PRIVATE_KEY);
        assertHostFileUpload(uploads.get(2), keys.publicKeyPath(), PUBLIC_KEY);
        assertSigningKeySourcesRemainPrivate(keys);
    }

    @Test
    void signingPublicKeyRetriesWithoutUploadingTheSuccessfulPrivateKeyAgain() throws IOException {
        EksClusterManager.SigningKeyFiles keys = signingKeyFiles();
        failFirstUploadOf("sa-public-key.pem");

        manager.copySigningKeysIntoContainer(CONTAINER_ID, keys, "demo");

        assertEquals(3, uploads.size(), "only the failed public key upload must be retried");
        assertHostFileUpload(uploads.get(0), keys.signingKeyPath(), PRIVATE_KEY);
        assertHostFileUpload(uploads.get(1), keys.publicKeyPath(), PUBLIC_KEY);
        assertHostFileUpload(uploads.get(2), keys.publicKeyPath(), PUBLIC_KEY);
        assertSigningKeySourcesRemainPrivate(keys);
    }

    @Test
    void dockerJavaDirectHostResourceCopyUses0644ForPrivateSourceFiles() throws IOException {
        EksClusterManager.SigningKeyFiles keys = signingKeyFiles();

        lifecycleManager.getDockerClient().copyArchiveToContainerCmd(CONTAINER_ID)
                .withHostResource(keys.signingKeyPath().toString())
                .withRemotePath("/etc")
                .exec();

        assertEquals(1, uploads.size());
        assertHostFileUpload(uploads.getFirst(), keys.signingKeyPath(), PRIVATE_KEY);
        assertSigningKeySourcesRemainPrivate(keys);
    }

    @Test
    void containerdLinkRetriesWithEveryDirectoryAndTheSymlinkIntact() {
        failFirstUploadOf("etc/containerd/certs.d");

        manager.linkContainerdCertsDir(CONTAINER_ID, "demo");

        assertEquals(2, uploads.size(), "the entire multi-entry archive must survive the retry");
        for (ArchiveUpload upload : uploads) {
            assertEquals(CONTAINER_ID, upload.containerId());
            assertEquals("/", upload.remotePath());
            assertEquals(3, upload.entries().size());
            assertDirectory(upload.entries().get(0), "var/lib/rancher/k3s/agent/etc/containerd/certs.d/");
            assertDirectory(upload.entries().get(1), "etc/containerd/");
            ArchiveEntry link = upload.entries().get(2);
            assertEquals("etc/containerd/certs.d", link.name());
            assertTrue(link.symbolicLink());
            assertFalse(link.directory());
            assertEquals(0777, link.mode());
            assertEquals("/var/lib/rancher/k3s/agent/etc/containerd/certs.d", link.linkName());
            assertArrayEquals(new byte[0], link.content());
        }
    }

    @Test
    void podIdentityManifestRetriesWithItsFullContentAndMode() {
        failFirstUploadOf("server/manifests/floci-eks-pod-identity.yaml");
        byte[] expected = EksClusterManager.buildPodIdentityWebhookConfiguration(
                "https://host.docker.internal:4566/_floci/eks/clusters/demo/pod-identity-webhook/scope/000000000000/us-east-1",
                CA_PEM).getBytes(StandardCharsets.UTF_8);

        manager.registerPodIdentityWebhook(CONTAINER_ID, cluster());

        assertEquals(2, uploads.size(), "the pod identity manifest must be rebuilt after the consumed upload fails");
        for (ArchiveUpload upload : uploads) {
            assertFileUpload(upload, "/var/lib/rancher/k3s", "server/manifests/floci-eks-pod-identity.yaml", expected);
        }
    }

    @Test
    void publicKeyDaemonRejectionDoesNotRetryEitherKeyOrPropagate() throws IOException {
        EksClusterManager.SigningKeyFiles keys = signingKeyFiles();
        failure = upload -> containsEntry(upload, "sa-public-key.pem")
                ? new NotFoundException("No such container: " + CONTAINER_ID) : null;

        assertDoesNotThrow(() -> manager.copySigningKeysIntoContainer(CONTAINER_ID, keys, "demo"));

        assertEquals(2, uploads.size());
        assertHostFileUpload(uploads.get(0), keys.signingKeyPath(), PRIVATE_KEY);
        assertHostFileUpload(uploads.get(1), keys.publicKeyPath(), PUBLIC_KEY);
        assertSigningKeySourcesRemainPrivate(keys);
    }

    @Test
    void archiveDaemonRejectionsAreNotRetriedAndLeaveTheClusterRunning() {
        failure = upload -> new NotFoundException("No such container: " + CONTAINER_ID);
        when(eks.irsaSigningKey()).thenReturn(true);
        Cluster cluster = cluster();
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));
        ClusterOidcKey key = new ClusterOidcKey("https://test.invalid/issuer", "fixture", "public", "private");
        when(oidcService.ensureKeyForAccount("000000000000", "us-east-1::demo", key.getIssuer())).thenReturn(key);
        when(oidcService.exportSigningKeyPem(key)).thenReturn(new String(PRIVATE_KEY, StandardCharsets.UTF_8));
        when(oidcService.exportPublicKeyPem(key)).thenReturn(new String(PUBLIC_KEY, StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> manager.startCluster(cluster));

        assertEquals(List.of("audit-policy.yaml", "etc/containerd/certs.d",
                        "server/manifests/floci-eks-pod-identity.yaml", "sa-signing-key.pem"),
                uploads.stream().map(upload -> upload.entries().getLast().name()).toList(),
                "each rejected feature is attempted once and startup continues with reduced functionality");
        verify(lifecycleManager).startCreated(any(), any());
        assertEquals(CONTAINER_ID, cluster.getContainerId());
    }

    private EksClusterManager.SigningKeyFiles signingKeyFiles() throws IOException {
        Path signingKey = tempDir.resolve("sa-signing-key.pem");
        Path publicKey = tempDir.resolve("sa-public-key.pem");
        Files.write(signingKey, PRIVATE_KEY);
        Files.write(publicKey, PUBLIC_KEY);
        if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(signingKey, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(publicKey, PosixFilePermissions.fromString("rw-------"));
        }
        return new EksClusterManager.SigningKeyFiles(signingKey, publicKey);
    }

    private static void assertSigningKeySourcesRemainPrivate(EksClusterManager.SigningKeyFiles keys) throws IOException {
        if (Files.getFileStore(keys.signingKeyPath()).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keys.signingKeyPath()));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keys.publicKeyPath()));
        }
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setName("demo");
        cluster.setAccountId("000000000000");
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity("https://test.invalid/issuer")));
        return cluster;
    }

    private void failFirstUploadOf(String name) {
        AtomicInteger attempts = new AtomicInteger();
        failure = upload -> containsEntry(upload, name) && attempts.incrementAndGet() == 1
                ? new RuntimeException(new IOException("Broken pipe")) : null;
    }

    private static boolean containsEntry(ArchiveUpload upload, String name) {
        return upload.entries().stream().anyMatch(entry -> entry.name().equals(name));
    }

    private static ArchiveUpload consumeArchive(CopyArchiveToContainerCmd command) throws IOException {
        InputStream body = command.getTarInputStream();
        if (command.getHostResource() != null) {
            body = new GZIPInputStream(body);
        }
        List<ArchiveEntry> entries = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(body)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries.add(new ArchiveEntry(entry.getName(), entry.getMode() & 07777,
                        entry.isDirectory(), entry.isSymbolicLink(), entry.getLinkName(), tar.readAllBytes()));
            }
            body.transferTo(OutputStream.nullOutputStream());
        }
        return new ArchiveUpload(command.getContainerId(), command.getRemotePath(), command.getHostResource(), entries);
    }

    private static void assertHostFileUpload(ArchiveUpload upload, Path source, byte[] content) {
        assertEquals(source.toString(), upload.hostResource());
        // docker-java's File-based tar entries default to 0644 even when the source file is protected with 0600.
        assertFileUpload(upload, "/etc", source.getFileName().toString(), content);
    }

    private static void assertFileUpload(ArchiveUpload upload, String remotePath, String name, byte[] content) {
        assertEquals(CONTAINER_ID, upload.containerId());
        assertEquals(remotePath, upload.remotePath());
        assertEquals(1, upload.entries().size());
        ArchiveEntry entry = upload.entries().getFirst();
        assertEquals(name, entry.name());
        assertEquals(0644, entry.mode());
        assertFalse(entry.directory());
        assertFalse(entry.symbolicLink());
        assertArrayEquals(content, entry.content());
    }

    private static void assertDirectory(ArchiveEntry entry, String name) {
        assertEquals(name, entry.name());
        assertTrue(entry.directory());
        assertFalse(entry.symbolicLink());
        assertEquals(0755, entry.mode());
        assertArrayEquals(new byte[0], entry.content());
    }

    private record ArchiveUpload(String containerId, String remotePath, String hostResource, List<ArchiveEntry> entries) {}

    private record ArchiveEntry(String name, int mode, boolean directory, boolean symbolicLink,
                                String linkName, byte[] content) {}
}
