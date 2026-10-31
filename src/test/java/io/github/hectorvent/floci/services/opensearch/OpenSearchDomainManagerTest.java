package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.VolumeOwnership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class OpenSearchDomainManagerTest {

    private EmulatorConfig config;
    private EmulatorConfig.DockerConfig docker;
    private EmulatorConfig.StorageConfig storage;
    private EmulatorConfig.OpenSearchServiceConfig opensearch;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerBuilder.Builder builder;
    private ContainerDetector containerDetector;
    private PortAllocator portAllocator;
    private OpenSearchDomainManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        docker = mock(EmulatorConfig.DockerConfig.class);
        storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        opensearch = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(config.services()).thenReturn(services);
        when(services.opensearch()).thenReturn(opensearch);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        when(storage.hostPersistentPath()).thenReturn("floci-data");
        when(storage.persistentPath()).thenReturn("/data");
        when(storage.mode()).thenReturn("memory");
        when(opensearch.proxyBasePort()).thenReturn(9400);
        when(opensearch.proxyMaxPort()).thenReturn(9499);
        when(opensearch.defaultImage()).thenReturn(Optional.of("opensearchproject/opensearch:2.11.0"));
        when(opensearch.dataVolume()).thenReturn(Optional.empty());

        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(9400, 9499)).thenReturn(9400);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        9200, new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 9200))));
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        containerDetector = mock(ContainerDetector.class);
        manager = new OpenSearchDomainManager(containerBuilder, lifecycleManager,
                containerDetector, portAllocator, config);
    }

    @Test
    void startDomainRunsTheContainerUnderAnInitProcess() {
        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        verify(builder).withInit();
    }

    @Test
    void startDomainLabelsContainerWithResourceIdentity() {
        Domain domain = domain("111111111111", "eu-west-1");

        manager.startDomain(domain);

        assertEquals(VolumeOwnership.FLOCI_MANAGED, domain.getVolumeOwnership());
        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "opensearch",
                "io.floci.resource-id", "my-domain",
                "io.floci.account", "111111111111",
                "io.floci.region", "eu-west-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "team-a"})
    void startDomainSeparatesContainerNamesAndCleanupAcrossAccountsAndRegions(String namespace) {
        when(docker.resourceNamespace()).thenReturn(Optional.of(namespace));
        List<Domain> domains = List.of(domain("000000000000", "us-east-1"),
                domain("111111111111", "us-east-1"), domain("000000000000", "eu-west-1"));

        for (Domain domain : domains) {
            manager.startDomain(domain);
        }

        String prefix = namespace.isEmpty() ? "floci-aws-opensearch-" : "floci-aws-team-a-opensearch-";
        List<String> expectedNames = List.of(prefix + "000000000000-us-east-1-my-domain",
                prefix + "111111111111-us-east-1-my-domain", prefix + "000000000000-eu-west-1-my-domain");
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(builder, times(3)).withName(names.capture());
        assertEquals(expectedNames, names.getAllValues());
        ArgumentCaptor<String> cleanup = ArgumentCaptor.forClass(String.class);
        verify(lifecycleManager, times(6)).removeIfExists(cleanup.capture());
        String legacyPrefix = namespace.isEmpty() ? "floci-opensearch-" : "floci-team-a-opensearch-";
        assertEquals(List.of(expectedNames.get(0), legacyPrefix + "000000000000-us-east-1-my-domain",
                expectedNames.get(1), legacyPrefix + "111111111111-us-east-1-my-domain",
                expectedNames.get(2), legacyPrefix + "000000000000-eu-west-1-my-domain"), cleanup.getAllValues());
    }

    @Test
    void startDomainMigratesLegacyContainerByPersistedIdAndKeepsItsVolume() {
        Domain domain = domain("111111111111", "eu-west-1");
        domain.setContainerId("legacy-container-id");
        domain.setHostPort(9401);
        domain.setDockerVolumeName("floci-opensearch-abc123");
        when(docker.resourceNamespace()).thenReturn(Optional.of("new-ns"));

        manager.startDomain(domain);

        InOrder order = inOrder(lifecycleManager, portAllocator);
        order.verify(lifecycleManager).removeIfExists("legacy-container-id");
        order.verify(portAllocator).release(9401);
        order.verify(portAllocator).allocate(9400, 9499);
        verify(builder).withName("floci-aws-new-ns-opensearch-111111111111-eu-west-1-my-domain");
        verify(builder).withNamedVolume("floci-opensearch-abc123", "/usr/share/opensearch/data");
        verify(lifecycleManager, never()).removeIfExists("floci-aws-new-ns-opensearch-my-domain");
        verify(lifecycleManager, never()).removeIfExists("floci-new-ns-opensearch-my-domain");
        assertEquals("floci-opensearch-abc123", domain.getDockerVolumeName());
        assertEquals("container-id", domain.getContainerId());
    }

    /**
     * OpenSearch has no floci-internal proxy fronting the backend, so the Docker
     * host-port binding is the only path to the domain for a client outside the
     * Docker network. It must be published even when floci itself runs inside a
     * container, while the stored endpoint stays the container-IP one floci and
     * same-network clients use.
     */
    @Test
    void startDomainPublishesAHostPortEvenWhenFlociRunsInAContainer() {
        when(portAllocator.allocate(9400, 9499)).thenReturn(9401);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setDomainName("host-reach");
        domain.setEngineVersion("OpenSearch_2.11");

        manager.startDomain(domain);

        verify(portAllocator).allocate(9400, 9499);
        verify(builder).withPortBinding(9200, 9401);
        assertEquals("http://172.17.0.5:9200", domain.getEndpoint());
        assertEquals(Integer.valueOf(9401), domain.getHostPort());
    }

    /**
     * The reservation must die with the container: without the release, repeated
     * create/delete cycles exhaust the configured port range and later domains
     * can never start.
     */
    @Test
    void stopDomainReleasesTheHostPort() {
        when(opensearch.keepRunningOnShutdown()).thenReturn(false);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setDomainName("teardown");
        domain.setContainerId("container-id");
        domain.setHostPort(9401);

        manager.stopDomain(domain);

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(9401);
        assertNull(domain.getHostPort());
    }

    /**
     * Explicit DeleteDomain tears down through {@code stopDomain}. The shutdown retention
     * option is decided by {@link OpenSearchService#shutdown()}, so it must not leave a deleted
     * domain's container running or its port reserved.
     */
    @Test
    void stopDomainRemovesTheContainerEvenWhenShutdownRetentionIsEnabled() {
        when(opensearch.keepRunningOnShutdown()).thenReturn(true);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setDomainName("deleted-domain");
        domain.setContainerId("container-id");
        domain.setHostPort(9401);

        manager.stopDomain(domain);

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(9401);
    }

    /**
     * A configured Docker volume name gives every domain its own
     * {@code <name>-<account-id>-<region>-<domain-name>} volume, so no two domains share a data directory;
     * the volume is created, when absent, without Floci labels so no sweep of
     * Floci-labelled volumes collects operator storage; and the domain record is
     * stamped with that name and the operator ownership marker so delete can tell
     * operator storage from Floci-derived storage.
     */
    @Test
    void startDomainMountsConfiguredNamedDataVolume() {
        when(opensearch.dataVolume()).thenReturn(Optional.of("opensearch-data"));

        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        verify(lifecycleManager).ensureUnmanagedVolume("opensearch-data-000000000000-us-east-1-my-domain");
        verify(builder).withNamedVolume("opensearch-data-000000000000-us-east-1-my-domain", "/usr/share/opensearch/data");
        assertEquals("opensearch-data-000000000000-us-east-1-my-domain", domain.getDockerVolumeName());
        assertEquals(VolumeOwnership.OPERATOR_NAMED, domain.getVolumeOwnership());
    }

    /**
     * A namespaced deployment composes the configured volume name with its namespace,
     * so two namespaced deployments sharing a Docker daemon and the same configured
     * base never converge on one volume for the same domain name.
     */
    @Test
    void startDomainMountsConfiguredNamedDataVolumeUnderTheResourceNamespace() {
        when(opensearch.dataVolume()).thenReturn(Optional.of("opensearch-data"));
        when(docker.resourceNamespace()).thenReturn(Optional.of("team-a"));

        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        verify(lifecycleManager).ensureUnmanagedVolume("opensearch-data-team-a-000000000000-us-east-1-my-domain");
        verify(builder).withNamedVolume("opensearch-data-team-a-000000000000-us-east-1-my-domain",
                "/usr/share/opensearch/data");
        assertEquals("opensearch-data-team-a-000000000000-us-east-1-my-domain", domain.getDockerVolumeName());
        assertEquals(VolumeOwnership.OPERATOR_NAMED, domain.getVolumeOwnership());
    }

    /**
     * A configured absolute path bind-mounts {@code <path>/<account-id>/<region>/<domain-name>}: per-domain
     * subdirectory, no named volume on the domain record, and the bind ownership
     * marker in place of a volume name.
     */
    @Test
    void startDomainBindsConfiguredHostDataPathPerDomain(@TempDir Path dataRoot) {
        when(opensearch.dataVolume()).thenReturn(Optional.of(dataRoot.toString()));

        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        String expectedBind = dataRoot.resolve("000000000000").resolve("us-east-1").resolve("my-domain")
                .toAbsolutePath().normalize().toString();
        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).ensureBindDirectory(expectedBind,
                "opensearchproject/opensearch:2.11.0", "/usr/share/opensearch/data");
        order.verify(lifecycleManager).createAndStart(any());
        verify(builder).withBind(expectedBind, "/usr/share/opensearch/data");
        verify(builder, never()).withNamedVolume(anyString(), anyString());
        assertNull(domain.getDockerVolumeName());
        assertEquals(VolumeOwnership.OPERATOR_BIND, domain.getVolumeOwnership());
    }

    /**
     * A namespaced deployment nests the configured bind path under a namespace
     * subdirectory, so two namespaced deployments sharing a Docker daemon host and
     * the same configured base never converge on one data directory for the same
     * domain name.
     */
    @Test
    void startDomainBindsConfiguredHostDataPathUnderTheResourceNamespace(@TempDir Path dataRoot) {
        when(opensearch.dataVolume()).thenReturn(Optional.of(dataRoot.toString()));
        when(docker.resourceNamespace()).thenReturn(Optional.of("team-a"));

        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        String expectedBind = dataRoot.resolve("team-a").resolve("000000000000").resolve("us-east-1").resolve("my-domain")
                .toAbsolutePath().normalize().toString();
        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).ensureBindDirectory(expectedBind,
                "opensearchproject/opensearch:2.11.0", "/usr/share/opensearch/data");
        order.verify(lifecycleManager).createAndStart(any());
        verify(builder).withBind(expectedBind, "/usr/share/opensearch/data");
        verify(builder, never()).withNamedVolume(anyString(), anyString());
        assertNull(domain.getDockerVolumeName());
        assertEquals(VolumeOwnership.OPERATOR_BIND, domain.getVolumeOwnership());
    }

    @ParameterizedTest
    @CsvSource({
            "/srv/floci/opensearch, '', /srv/floci/opensearch/",
            "/srv/floci/opensearch, team-a, /srv/floci/opensearch/team-a/",
            "/srv/floci/opensearch/, '', /srv/floci/opensearch/",
            "/srv/floci/opensearch/, team-a, /srv/floci/opensearch/team-a/",
            "/, '', /",
            "/, team-a, /team-a/"
    })
    void startDomainPreservesLinuxDaemonBindPaths(String dataVolume, String namespace, String expectedPrefix) {
        when(opensearch.dataVolume()).thenReturn(Optional.of(dataVolume));
        when(docker.resourceNamespace()).thenReturn(Optional.of(namespace));
        Domain domain = domain("000000000000", "us-east-1");
        String expectedBind = expectedPrefix + "000000000000/us-east-1/my-domain";
        try (MockedStatic<ContainerStorageHelper> storageHelper =
                     mockStatic(ContainerStorageHelper.class,
                             withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            storageHelper.when(() -> ContainerStorageHelper.ensureHostDir(anyString())).thenAnswer(invocation -> null);

            manager.startDomain(domain);

            if (Path.of("/").isAbsolute()) {
                storageHelper.verify(() -> ContainerStorageHelper.ensureHostDir(expectedBind));
            } else {
                storageHelper.verify(() -> ContainerStorageHelper.ensureHostDir(anyString()), never());
            }
        }

        verify(lifecycleManager).ensureBindDirectory(expectedBind,
                "opensearchproject/opensearch:2.11.0", "/usr/share/opensearch/data");
        verify(builder).withBind(expectedBind, "/usr/share/opensearch/data");
        verify(builder, never()).withNamedVolume(anyString(), anyString());
        verify(lifecycleManager, never()).ensureUnmanagedVolume(anyString());
        assertNull(domain.getDockerVolumeName());
        assertEquals(VolumeOwnership.OPERATOR_BIND, domain.getVolumeOwnership());
    }

    /** An operator-named volume is never deleted with the domain, whatever the storage policy. */
    @Test
    void removeDomainStorageRetainsOperatorNamedVolume() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setVolumeId("abc123");
        domain.setDockerVolumeName("opensearch-data-000000000000-us-east-1-my-domain");
        domain.setVolumeOwnership(VolumeOwnership.OPERATOR_NAMED);

        manager.removeDomainStorage(domain);

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    /** A volume Floci derived itself, current prefix, is still removed as before. */
    @Test
    void removeDomainStorageRemovesFlociDerivedVolume() {
        when(storage.pruneVolumesOnDelete()).thenReturn(false);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setVolumeId("abc123");
        domain.setDockerVolumeName(
                ContainerStorageHelper.resourceName(config, "opensearch", "abc123", "my-domain"));

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-aws-opensearch-abc123");
    }

    /** A pre-migration record's legacy-named volume is as deletable as a current-prefix one. */
    @Test
    void removeDomainStorageRemovesLegacyDerivedVolume() {
        when(storage.pruneVolumesOnDelete()).thenReturn(false);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setVolumeId("abc123");
        domain.setDockerVolumeName(
                ContainerStorageHelper.legacyResourceName(config, "opensearch", "abc123", "my-domain"));

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-opensearch-abc123");
    }

    /**
     * A bind-backed domain mounts no named volume, so delete removes no Docker volume at
     * all, whatever the storage policy: a stale managed name on the record must not send
     * it after a volume the domain never mounted.
     */
    @Test
    void removeDomainStorageRemovesNoDockerVolumeForBindBackedDomain() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setVolumeOwnership(VolumeOwnership.OPERATOR_BIND);

        manager.removeDomainStorage(domain);

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @Test
    void removeDomainStorageRemovesLegacyRecordRegardlessOfConfiguredDataVolume() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);
        when(opensearch.dataVolume()).thenReturn(Optional.of("floci-opensearch"));

        Domain domain = domain("000000000000", "us-east-1");

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-opensearch-my-domain");
    }

    /**
     * The marker, not the spelling, decides: an operator-named volume is retained even
     * when its name is spelled exactly like the legacy name Floci derives for the same
     * domain.
     */
    @Test
    void removeDomainStorageRetainsOperatorNamedVolumeEvenWhenItMatchesTheLegacyDerivedName() {
        when(storage.pruneVolumesOnDelete()).thenReturn(true);
        when(opensearch.dataVolume()).thenReturn(Optional.of("floci-opensearch"));

        Domain domain = domain("000000000000", "us-east-1");
        domain.setDockerVolumeName("floci-opensearch-my-domain");
        domain.setVolumeOwnership(VolumeOwnership.OPERATOR_NAMED);

        manager.removeDomainStorage(domain);

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    /**
     * A managed volume is removed by its persisted name, not by names recomputed from
     * the configuration held at delete time: a resource-namespace change between
     * creation and deletion must not strand the volume under the prune policy.
     */
    @Test
    void removeDomainStorageRemovesManagedVolumeByPersistedNameWhenTheNamespaceChanged() {
        when(docker.resourceNamespace()).thenReturn(Optional.of("new-ns"));
        when(storage.pruneVolumesOnDelete()).thenReturn(false);

        Domain domain = domain("000000000000", "us-east-1");
        domain.setVolumeId("abc123");
        domain.setDockerVolumeName("floci-aws-opensearch-abc123");
        domain.setVolumeOwnership(VolumeOwnership.FLOCI_MANAGED);

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-aws-opensearch-abc123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "team-a"})
    void configuredNamedStorageSeparatesAccountsAndRegionsAndReusesDomainIdentity(String namespace) {
        when(docker.resourceNamespace()).thenReturn(Optional.of(namespace));
        when(opensearch.dataVolume()).thenReturn(Optional.of("opensearch-data"));
        List<Domain> domains = List.of(domain("000000000000", "us-east-1"),
                domain("111111111111", "us-east-1"), domain("000000000000", "eu-west-1"),
                domain("000000000000", "us-east-1"));

        for (Domain domain : domains) {
            manager.startDomain(domain);
        }

        String prefix = namespace.isEmpty() ? "opensearch-data-" : "opensearch-data-team-a-";
        List<String> expectedNames = List.of(prefix + "000000000000-us-east-1-my-domain",
                prefix + "111111111111-us-east-1-my-domain", prefix + "000000000000-eu-west-1-my-domain",
                prefix + "000000000000-us-east-1-my-domain");
        assertEquals(expectedNames, domains.stream().map(Domain::getDockerVolumeName).toList());
        ArgumentCaptor<String> mounts = ArgumentCaptor.forClass(String.class);
        verify(builder, times(4)).withNamedVolume(mounts.capture(), anyString());
        assertEquals(expectedNames, mounts.getAllValues());
        verify(lifecycleManager, times(2)).ensureUnmanagedVolume(expectedNames.getFirst());
        verify(lifecycleManager).ensureUnmanagedVolume(expectedNames.get(1));
        verify(lifecycleManager).ensureUnmanagedVolume(expectedNames.get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "team-a"})
    void configuredBindStorageSeparatesAccountsAndRegionsAndReusesDomainIdentity(
            String namespace, @TempDir Path dataRoot) {
        when(docker.resourceNamespace()).thenReturn(Optional.of(namespace));
        when(opensearch.dataVolume()).thenReturn(Optional.of(dataRoot.toString()));
        List<Domain> domains = List.of(domain("000000000000", "us-east-1"),
                domain("111111111111", "us-east-1"), domain("000000000000", "eu-west-1"),
                domain("000000000000", "us-east-1"));

        for (Domain domain : domains) {
            manager.startDomain(domain);
        }

        Path root = dataRoot.resolve(namespace);
        List<Path> expectedPaths = List.of(root.resolve("000000000000/us-east-1/my-domain"),
                root.resolve("111111111111/us-east-1/my-domain"), root.resolve("000000000000/eu-west-1/my-domain"),
                root.resolve("000000000000/us-east-1/my-domain"));
        ArgumentCaptor<String> mounts = ArgumentCaptor.forClass(String.class);
        verify(builder, times(4)).withBind(mounts.capture(), anyString());
        assertEquals(expectedPaths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList(),
                mounts.getAllValues());
        for (Path path : expectedPaths) {
            assertTrue(Files.isDirectory(path));
        }
        verify(builder, never()).withNamedVolume(anyString(), anyString());
    }

    @Test
    void removeDomainStorageRemovesUnmarkedVolumeByPersistedNameWhenTheNamespaceChanged() {
        when(docker.resourceNamespace()).thenReturn(Optional.of("new-ns"));
        Domain domain = domain("000000000000", "us-east-1");
        domain.setDockerVolumeName("floci-opensearch-old-ns-abc123");

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-opensearch-old-ns-abc123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void removeDomainStorageRetainsUnmarkedVolumeWhenPruningIsDisabled(String mode) {
        when(storage.mode()).thenReturn(mode);
        Domain domain = domain("000000000000", "us-east-1");
        domain.setDockerVolumeName("floci-opensearch-abc123");

        manager.removeDomainStorage(domain);

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void removeDomainStorageRemovesUnmarkedVolumeWhenPruningIsEnabled(String mode) {
        when(storage.mode()).thenReturn(mode);
        when(storage.pruneVolumesOnDelete()).thenReturn(true);
        Domain domain = domain("000000000000", "us-east-1");
        domain.setDockerVolumeName("floci-opensearch-abc123");

        manager.removeDomainStorage(domain);

        verify(lifecycleManager).removeVolume("floci-opensearch-abc123");
    }

    @Test
    void startDomainReleasesPortAndDoesNotStartWhenBindPreparationFails(@TempDir Path dataRoot) {
        when(opensearch.dataVolume()).thenReturn(Optional.of(dataRoot.toString()));
        doThrow(new IllegalStateException("init failed")).when(lifecycleManager)
                .ensureBindDirectory(anyString(), anyString(), anyString());
        Domain domain = domain("000000000000", "us-east-1");

        assertThrows(IllegalStateException.class, () -> manager.startDomain(domain));

        verify(portAllocator).release(9400);
        verify(lifecycleManager, never()).createAndStart(any());
        assertNull(domain.getHostPort());
    }

    /**
     * Legacy host-path mode binds one directory per account and region, so two domains sharing
     * a name across regions (or accounts) never share one data directory, which would corrupt
     * both clusters.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "team-a"})
    void hostPathStorageSeparatesAccountsAndRegions(String namespace, @TempDir Path hostRoot) {
        when(storage.hostPersistentPath()).thenReturn(hostRoot.toString());
        when(docker.resourceNamespace()).thenReturn(Optional.of(namespace));
        List<Domain> domains = List.of(domain("000000000000", "us-east-1"),
                domain("111111111111", "us-east-1"), domain("000000000000", "eu-west-1"));
        for (Domain domain : domains) {
            domain.setRegion(AwsArnUtils.parse(domain.getArn()).region());
        }

        for (Domain domain : domains) {
            manager.startDomain(domain);
        }

        String prefix = namespace.isEmpty() ? "floci-aws-opensearch-" : "floci-aws-team-a-opensearch-";
        Path serviceRoot = namespace.isEmpty()
                ? hostRoot.resolve("opensearch")
                : hostRoot.resolve("team-a").resolve("opensearch");
        List<String> expectedBinds = List.of(
                serviceRoot.resolve(prefix + "000000000000-us-east-1-my-domain"),
                serviceRoot.resolve(prefix + "111111111111-us-east-1-my-domain"),
                serviceRoot.resolve(prefix + "000000000000-eu-west-1-my-domain"))
                .stream().map(path -> path.toAbsolutePath().normalize().toString()).toList();
        ArgumentCaptor<String> binds = ArgumentCaptor.forClass(String.class);
        verify(builder, times(3)).withBind(binds.capture(), anyString());
        assertEquals(expectedBinds, binds.getAllValues());
    }

    /**
     * A record persisted before the region marker keeps the bare-name directory while that
     * directory still exists, since that is where its data lives.
     */
    @Test
    void hostPathKeepsTheBareDirectoryForARecordPredatingTheRegionMarker(
            @TempDir Path hostRoot) throws IOException {
        when(storage.hostPersistentPath()).thenReturn(hostRoot.toString());
        Path legacyDir = hostRoot.resolve("opensearch").resolve("my-domain");
        Files.createDirectories(legacyDir);
        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        verify(builder).withBind(legacyDir.toAbsolutePath().normalize().toString(), "/usr/share/opensearch/data");
    }

    /** With no bare-name directory on disk, even a marker-less record starts on the scoped one. */
    @Test
    void hostPathUsesTheScopedDirectoryWhenNoBareDirectoryExists(@TempDir Path hostRoot) {
        when(storage.hostPersistentPath()).thenReturn(hostRoot.toString());
        Domain domain = domain("000000000000", "us-east-1");

        manager.startDomain(domain);

        verify(builder).withBind(hostRoot.resolve("opensearch")
                .resolve("floci-aws-opensearch-000000000000-us-east-1-my-domain")
                .toAbsolutePath().normalize().toString(), "/usr/share/opensearch/data");
    }

    private Domain domain(String accountId, String region) {
        Domain domain = new Domain();
        domain.setDomainName("my-domain");
        domain.setEngineVersion("OpenSearch_2.11");
        domain.setArn(AwsArnUtils.Arn.of("es", region, accountId, "domain/my-domain").toString());
        return domain;
    }
}
