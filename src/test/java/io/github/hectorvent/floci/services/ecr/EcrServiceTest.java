package io.github.hectorvent.floci.services.ecr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.PullThroughCacheRule;
import io.github.hectorvent.floci.services.ecr.model.RegistrySettings;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.model.RepositoryCreationTemplate;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcrService}. Uses an in-memory storage backend and a
 * mocked {@link EcrRegistryManager} so the test never touches Docker.
 */
class EcrServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String REPO = "floci-it/svc-test";

    private EcrService service;
    private EcrRegistryManager registryManager;
    private InMemoryStorage<String, PullThroughCacheRule> pullThroughCacheRuleStore;

    @BeforeEach
    void setUp() {
        registryManager = Mockito.mock(EcrRegistryManager.class);
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + ".dkr.ecr." + inv.getArgument(1)
                        + ".localhost:4566/" + inv.getArgument(2));
        when(registryManager.getProxyEndpoint()).thenReturn("http://localhost:4566");
        when(registryManager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1) + "/" + inv.getArgument(2));
        // The mock never talks to Docker; report the backing registry as available so the
        // data-plane operations run. Registry-unavailable behaviour has its own tests below.
        when(registryManager.tryEnsureStarted()).thenReturn(true);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        pullThroughCacheRuleStore = new InMemoryStorage<>();

        service = new EcrService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                pullThroughCacheRuleStore,
                registryManager,
                config,
                regionResolver);
    }

    // ------------------------------------------------------------
    // Pull through cache rules
    // ------------------------------------------------------------

    @Test
    void createPullThroughCacheRule_roundTripsDefaults() {
        PullThroughCacheRule rule = service.createPullThroughCacheRule(
                "docker-hub/", "registry-1.docker.io", null, null,
                null, null, null, REGION);

        assertEquals("docker-hub", rule.getEcrRepositoryPrefix());
        assertEquals("registry-1.docker.io", rule.getUpstreamRegistryUrl());
        assertEquals("docker-hub", rule.getUpstreamRegistry());
        assertEquals("ROOT", rule.getUpstreamRepositoryPrefix());
        assertEquals(ACCOUNT, rule.getRegistryId());
        assertNotNull(rule.getCreatedAt());
        assertEquals(rule.getCreatedAt(), rule.getUpdatedAt());
    }

    @Test
    void createPullThroughCacheRule_duplicate_throwsAlreadyExists() {
        service.createPullThroughCacheRule("docker-hub", "registry-1.docker.io", null,
                null, null, null, null, REGION);

        AwsException exception = assertThrows(AwsException.class,
                () -> service.createPullThroughCacheRule("docker-hub/", "registry-1.docker.io", null,
                        null, null, null, null, REGION));

        assertEquals("PullThroughCacheRuleAlreadyExistsException", exception.getErrorCode());
    }

    @Test
    void describePullThroughCacheRules_filtersAndPaginates() {
        service.createPullThroughCacheRule("cache/alpha", "registry-1.docker.io", null,
                "docker-hub", null, null, "library", REGION);
        service.createPullThroughCacheRule("cache/beta", "quay.io", null,
                null, null, null, null, REGION);
        service.createPullThroughCacheRule("cache/gamma", "registry.k8s.io", null,
                null, null, null, null, REGION);

        PaginatedResult<PullThroughCacheRule> first = service.describePullThroughCacheRules(
                null, null, 1, null, REGION);
        PaginatedResult<PullThroughCacheRule> second = service.describePullThroughCacheRules(
                null, null, 1, first.nextToken(), REGION);
        PaginatedResult<PullThroughCacheRule> filtered = service.describePullThroughCacheRules(
                null, List.of("cache/gamma/"), null, null, REGION);

        assertEquals("cache/alpha", first.items().getFirst().getEcrRepositoryPrefix());
        assertNotNull(first.nextToken());
        assertEquals("cache/beta", second.items().getFirst().getEcrRepositoryPrefix());
        assertEquals("cache/gamma", filtered.items().getFirst().getEcrRepositoryPrefix());
    }

    @Test
    void pullThroughCacheRules_areIsolatedByAccountAndRegion() {
        String otherAccount = "111111111111";
        service.createPullThroughCacheRule("shared/cache", "quay.io", null,
                null, null, null, null, REGION);
        service.createPullThroughCacheRule("shared/cache", "registry.k8s.io", otherAccount,
                null, null, null, null, REGION);
        service.createPullThroughCacheRule("shared/cache", "ghcr.io", null,
                null, null, null, null, "eu-west-1");

        assertEquals("quay.io", service.describePullThroughCacheRules(
                null, null, null, null, REGION).items().getFirst().getUpstreamRegistryUrl());
        assertEquals("registry.k8s.io", service.describePullThroughCacheRules(
                otherAccount, null, null, null, REGION).items().getFirst().getUpstreamRegistryUrl());
        assertEquals("ghcr.io", service.describePullThroughCacheRules(
                null, null, null, null, "eu-west-1").items().getFirst().getUpstreamRegistryUrl());
    }

    @Test
    void pullThroughCacheRule_persistsAcrossServiceInstances() {
        service.createPullThroughCacheRule("persisted/cache", "quay.io", null,
                null, null, null, null, REGION);
        EcrService restored = new EcrService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                pullThroughCacheRuleStore,
                registryManager,
                Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));

        PullThroughCacheRule rule = restored.describePullThroughCacheRules(
                null, List.of("persisted/cache"), null, null, REGION).items().getFirst();

        assertEquals("quay.io", rule.getUpstreamRegistryUrl());
    }

    @Test
    void deletePullThroughCacheRule_removesRuleAndMissingFails() {
        service.createPullThroughCacheRule("delete/cache", "quay.io", null,
                null, null, null, null, REGION);

        PullThroughCacheRule deleted = service.deletePullThroughCacheRule("delete/cache/", null, REGION);

        assertEquals("delete/cache", deleted.getEcrRepositoryPrefix());
        AwsException exception = assertThrows(AwsException.class,
                () -> service.deletePullThroughCacheRule("delete/cache", null, REGION));
        assertEquals("PullThroughCacheRuleNotFoundException", exception.getErrorCode());
    }

    @Test
    void updatePullThroughCacheRule_updatesProvidedFieldsAndPreservesTheRest() {
        String originalCredential =
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:ecr-pullthroughcache/original";
        String updatedCredential =
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:ecr-pullthroughcache/updated";
        PullThroughCacheRule created = service.createPullThroughCacheRule(
                "update/cache", "registry-1.docker.io", null, null,
                originalCredential, null, "library", REGION);
        Instant createdAt = created.getCreatedAt();

        PullThroughCacheRule updated = service.updatePullThroughCacheRule(
                "update/cache/", null, updatedCredential,
                "arn:aws:iam::000000000000:role/EcrPullThroughCache", REGION);

        assertEquals(updatedCredential, updated.getCredentialArn());
        assertEquals("arn:aws:iam::000000000000:role/EcrPullThroughCache", updated.getCustomRoleArn());
        assertEquals("registry-1.docker.io", updated.getUpstreamRegistryUrl());
        assertEquals("library", updated.getUpstreamRepositoryPrefix());
        assertEquals(createdAt, updated.getCreatedAt());
        assertFalse(updated.getUpdatedAt().isBefore(createdAt));

        PullThroughCacheRule roleOnlyUpdate = service.updatePullThroughCacheRule(
                "update/cache", null, null,
                "arn:aws:iam::000000000000:role/ReplacedRole", REGION);
        assertEquals(updatedCredential, roleOnlyUpdate.getCredentialArn());
        assertEquals("arn:aws:iam::000000000000:role/ReplacedRole", roleOnlyUpdate.getCustomRoleArn());
    }

    @Test
    void validatePullThroughCacheRule_returnsStoredRuleAndMissingRulesFail() {
        PullThroughCacheRule created = service.createPullThroughCacheRule(
                "validate/cache", "quay.io", null, null,
                null, null, null, REGION);

        PullThroughCacheRule validated = service.validatePullThroughCacheRule(
                "validate/cache/", null, REGION);

        assertSame(created, validated);
        assertEquals("quay.io", validated.getUpstreamRegistryUrl());
        assertEquals("PullThroughCacheRuleNotFoundException", assertThrows(AwsException.class,
                () -> service.validatePullThroughCacheRule("missing/cache", null, REGION)).getErrorCode());
        assertEquals("PullThroughCacheRuleNotFoundException", assertThrows(AwsException.class,
                () -> service.updatePullThroughCacheRule(
                        "missing/cache", null, null, null, REGION)).getErrorCode());
    }

    /**
     * An ECR upstream is recognised in every partition, as Floci's own registry recognises an ECR
     * image URI: the host pattern once accepted only the commercial and China suffixes.
     */
    @Test
    void pullThroughCacheRule_acceptsAnEcrUpstreamInEveryPartition() {
        String[] hosts = {
            "123456789012.dkr.ecr.us-east-1.amazonaws.com",
            "123456789012.dkr.ecr.cn-north-1.amazonaws.com.cn",
            "123456789012.dkr.ecr.us-gov-west-1.amazonaws.com",
            "123456789012.dkr.ecr.us-iso-east-1.c2s.ic.gov",
            "123456789012.dkr.ecr.eusc-de-east-1.amazonaws.eu",
        };
        for (int i = 0; i < hosts.length; i++) {
            PullThroughCacheRule rule = service.createPullThroughCacheRule("ecr-up-" + i, hosts[i], null,
                    null, null, null, null, REGION);
            assertEquals("ecr", rule.getUpstreamRegistry(), hosts[i]);
        }
        assertEquals("UnsupportedUpstreamRegistryException", assertThrows(AwsException.class,
                () -> service.createPullThroughCacheRule("ecr-up-bad", "123456789012.dkr.ecr.us-east-1.example.com",
                        null, null, null, null, null, REGION)).getErrorCode());
    }

    @Test
    void pullThroughCacheRule_rejectsInvalidInputs() {
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.createPullThroughCacheRule("A", "quay.io", null,
                        null, null, null, null, REGION)).getErrorCode());
        assertEquals("UnsupportedUpstreamRegistryException", assertThrows(AwsException.class,
                () -> service.createPullThroughCacheRule("invalid/upstream", "example.com", null,
                        null, null, null, null, REGION)).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.createPullThroughCacheRule("ROOT", "quay.io", null,
                        null, null, null, "team", REGION)).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.describePullThroughCacheRules(
                        null, List.of(), null, null, REGION)).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.describePullThroughCacheRules(
                        null, Collections.nCopies(101, "cache"), null, null, REGION)).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.describePullThroughCacheRules(null, null, 0, null, REGION)).getErrorCode());
    }

    // ------------------------------------------------------------
    // CreateRepository
    // ------------------------------------------------------------

    @Test
    void createRepository_returnsLoopbackUri() {
        Repository repo = service.createRepository(REPO, null, null, null, null, null, null, REGION);
        assertEquals(REPO, repo.getRepositoryName());
        assertEquals(ACCOUNT, repo.getRegistryId());
        assertTrue(repo.getRepositoryArn().startsWith("arn:aws:ecr:us-east-1:000000000000:repository/"));
        assertTrue(repo.getRepositoryUri().contains("localhost:"));
        assertEquals("MUTABLE", repo.getImageTagMutability());
    }

    @Test
    void createRepository_duplicate_throwsAlreadyExists() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRepository(REPO, null, null, null, null, null, null, REGION));
        assertEquals("RepositoryAlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void createRepository_invalidName_throwsInvalidParameter() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRepository("Invalid_Caps", null, null, null, null, null, null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void createRepository_emptyName_throwsInvalidParameter() {
        assertThrows(AwsException.class,
                () -> service.createRepository("", null, null, null, null, null, null, REGION));
        assertThrows(AwsException.class,
                () -> service.createRepository(null, null, null, null, null, null, null, REGION));
    }

    @Test
    void createRepository_persistsTagsAndMutability() {
        Repository repo = service.createRepository(REPO, null, "IMMUTABLE", true, null, null,
                Map.of("env", "dev", "team", "platform"), REGION);
        assertEquals("IMMUTABLE", repo.getImageTagMutability());
        assertTrue(repo.isScanOnPush());
        assertEquals("dev", repo.getTags().get("env"));
        assertEquals("platform", repo.getTags().get("team"));
    }

    @Test
    void immutableRepositoryIsMarkedForDataPlaneTagProtection() {
        service.createRepository(REPO, null, "IMMUTABLE", null, null, null, null, REGION);

        assertTrue(service.isImageTagImmutable(REPO, ACCOUNT, REGION));
    }

    @Test
    void mutableRepositoryIsNotMarkedForDataPlaneTagProtection() {
        service.createRepository(REPO, null, "MUTABLE", null, null, null, null, REGION);

        assertFalse(service.isImageTagImmutable(REPO, ACCOUNT, REGION));
    }

    @Test
    void reconcilePreProxyRegistryNamespacePreservesItsStoragePath() {
        String repositoryName = "legacy/service";

        service.reconcileFromCatalog(List.of(repositoryName));

        Repository repository = service.describeRepositories(List.of(repositoryName), null, REGION).getFirst();
        assertEquals(repositoryName, repository.getRepositoryName());
        assertEquals(repositoryName, service.registryRepositoryName(repositoryName, ACCOUNT, REGION));
    }

    @Test
    void reconcilePreProxyRepositoryWithNamespaceShapedNamePreservesItsIdentity() {
        String repositoryName = ACCOUNT + "/" + REGION + "/legacy";
        service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

        service.reconcileFromCatalog(List.of(repositoryName));

        assertEquals(repositoryName,
                service.describeRepositories(List.of(repositoryName), null, REGION).getFirst().getRepositoryName());
        assertEquals(repositoryName, service.registryRepositoryName(repositoryName, ACCOUNT, REGION));
        assertThrows(AwsException.class, () -> service.describeRepositories(List.of("legacy"), null, REGION));
    }

    // ------------------------------------------------------------
    // Registry unavailable (Floci inside Docker with no Docker daemon)
    // ------------------------------------------------------------

    @Test
    void createRepository_succeedsAsMetadataWhenRegistryIsUnavailable() {
        when(registryManager.tryEnsureStarted()).thenReturn(false);

        Repository repo = service.createRepository(REPO, null, "IMMUTABLE", true, null, null,
                Map.of("Project", "platform"), REGION);

        assertEquals(REPO, repo.getRepositoryName());
        assertEquals(ACCOUNT, repo.getRegistryId());
        assertEquals("arn:aws:ecr:" + REGION + ":" + ACCOUNT + ":repository/" + REPO,
                repo.getRepositoryArn());
        assertTrue(repo.getRepositoryUri().endsWith("/" + REPO), repo.getRepositoryUri());
        assertEquals("IMMUTABLE", repo.getImageTagMutability());
        assertEquals("platform", repo.getTags().get("Project"));
    }

    @Test
    void repositoryMetadataCrudWorksWhenRegistryIsUnavailable() {
        when(registryManager.tryEnsureStarted()).thenReturn(false);

        service.createRepository(REPO, null, null, null, null, null, Map.of("env", "dev"), REGION);

        assertEquals(1, service.describeRepositories(List.of(REPO), null, REGION).size());
        assertEquals("dev", service.listTagsForResource(REPO, null, REGION).get("env"));

        service.tagResource(REPO, null, Map.of("team", "platform"), REGION);
        assertEquals("platform", service.listTagsForResource(REPO, null, REGION).get("team"));

        service.deleteRepository(REPO, null, false, REGION);
        assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of(REPO), null, REGION));
    }

    @Test
    void dataPlaneOperationsFailWithServerExceptionWhenRegistryIsUnavailable() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        when(registryManager.tryEnsureStarted()).thenReturn(false);

        assertEquals("ServerException",
                assertThrows(AwsException.class, () -> service.listImages(REPO, null, REGION, null, null)).getErrorCode());
        assertEquals("ServerException",
                assertThrows(AwsException.class,
                        () -> service.describeImages(REPO, null, null, REGION)).getErrorCode());
        assertEquals("ServerException",
                assertThrows(AwsException.class,
                        () -> service.batchGetImage(REPO, List.of(), null, null, REGION)).getErrorCode());
        assertEquals("ServerException",
                assertThrows(AwsException.class,
                        () -> service.batchDeleteImage(REPO, List.of(), null, REGION)).getErrorCode());
    }

    @Test
    void getAuthorizationToken_succeedsWhenRegistryIsUnavailable() {
        when(registryManager.tryEnsureStarted()).thenReturn(false);

        AuthorizationData data = service.getAuthorizationToken();

        String decoded = new String(Base64.getDecoder().decode(data.getAuthorizationToken()));
        assertTrue(decoded.startsWith("AWS:"), "decoded token should start with AWS: but was: " + decoded);
        assertEquals("http://localhost:4566", data.getProxyEndpoint());
        assertNotNull(data.getExpiresAt());
        verify(registryManager, never()).tryEnsureStarted();
    }

    // ------------------------------------------------------------
    // DescribeRepositories
    // ------------------------------------------------------------

    @Test
    void describeRepositories_byName() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        List<Repository> repos = service.describeRepositories(List.of(REPO), null, REGION);
        assertEquals(1, repos.size());
        assertEquals(REPO, repos.get(0).getRepositoryName());
    }

    @Test
    void describeRepositoriesRefreshesStoredUrisForTheCurrentConfiguration() {
        String namedRepository = REPO + "-named";
        String listedRepository = REPO + "-listed";
        service.createRepository(namedRepository, null, null, null, null, null, null, REGION);
        service.createRepository(listedRepository, null, null, null, null, null, null, REGION);
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + ".dkr.ecr." + invocation.getArgument(1)
                        + ".localhost.floci.io:4566/" + invocation.getArgument(2));

        Repository named = service.describeRepositories(List.of(namedRepository), null, REGION).getFirst();
        assertEquals(ACCOUNT + ".dkr.ecr." + REGION + ".localhost.floci.io:4566/" + namedRepository,
                named.getRepositoryUri());
        List<Repository> listed = service.describeRepositories(null, null, REGION);
        assertEquals(2, listed.size());
        assertTrue(listed.stream().allMatch(repository -> repository.getRepositoryUri()
                .equals(ACCOUNT + ".dkr.ecr." + REGION + ".localhost.floci.io:4566/"
                        + repository.getRepositoryName())));
    }

    @Test
    void deleteRepositoryRefreshesItsResponseWithoutRecreatingMetadata() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        String tlsUri = ACCOUNT + ".dkr.ecr." + REGION + ".localhost.floci.io:4566/" + REPO;
        when(registryManager.getRepositoryUri(ACCOUNT, REGION, REPO)).thenReturn(tlsUri);
        when(registryManager.tryEnsureStarted()).thenReturn(false);

        Repository deleted = service.deleteRepository(REPO, null, false, REGION);

        assertEquals(tlsUri, deleted.getRepositoryUri());
        assertThrows(AwsException.class, () -> service.describeRepositories(List.of(REPO), null, REGION));
        assertTrue(service.describeRepositories(null, null, REGION).isEmpty());
    }

    @Test
    void describeRepositories_emptyList_returnsAllInRegion() {
        service.createRepository("a/one", null, null, null, null, null, null, REGION);
        service.createRepository("a/two", null, null, null, null, null, null, REGION);
        service.createRepository("a/three", null, null, null, null, null, null, "eu-west-1");
        List<Repository> repos = service.describeRepositories(null, null, REGION);
        assertEquals(2, repos.size());
    }

    @Test
    void describeRepositories_missing_throwsNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of("does-not-exist"), null, REGION));
        assertEquals("RepositoryNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // DeleteRepository
    // ------------------------------------------------------------

    @Test
    void deleteRepository_force_removesEntry() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        Repository deleted = service.deleteRepository(REPO, null, true, REGION);
        assertEquals(REPO, deleted.getRepositoryName());
        assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of(REPO), null, REGION));
    }

    @Test
    void deleteRepository_missing_throwsNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteRepository(REPO, null, false, REGION));
        assertEquals("RepositoryNotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteRepository_forceRemovesRepositoryStorageAndPrunesFinalRepositoryStorage() throws Exception {
        // The fake catalog carries the bare name (hostname-style push), so the
        // storage delete must target it rather than the empty namespaced form.
        String digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;
        try (FakeRegistryServer registry = new FakeRegistryServer(REPO, "v1", digest, manifest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(REPO, null, null, null, null, null, null, REGION);

            service.deleteRepository(REPO, null, true, REGION);

            verify(registryManager).deleteRepositoryStorageByInternalName(REPO);
            verify(registryManager).pruneStorage();
        }
    }

    @Test
    void deleteRepository_keepsSharedRegistryStorageForRemainingRepositories() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.createRepository("other", null, null, null, null, null, null, REGION);

        service.deleteRepository(REPO, null, true, REGION);

        verify(registryManager, never()).pruneStorage();
    }

    @Test
    void deleteRepository_forceRetainsMetadataWhenRegistryCleanupFails() throws Exception {
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;
        try (FakeRegistryServer registry = new FakeRegistryServer(REPO, "v1",
                "sha256:2222222222222222222222222222222222222222222222222222222222222222", manifest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            Mockito.doThrow(new IllegalStateException("registry unavailable"))
                    .when(registryManager).deleteRepositoryStorageByInternalName(anyString());
            service.createRepository(REPO, null, null, null, null, null, null, REGION);

            AwsException ex = assertThrows(AwsException.class,
                    () -> service.deleteRepository(REPO, null, true, REGION));

            assertEquals("ServerException", ex.getErrorCode());
            assertEquals(REPO, service.describeRepositories(List.of(REPO), null, REGION).getFirst().getRepositoryName());
            verify(registryManager, never()).pruneStorage();
        }
    }

    @Test
    void deleteRepository_keepsSharedStorageForRepositoriesOwnedByAnotherAccount() {
        AccountAwareStorageBackend<Repository> repositories = AccountAwareStorageBackend.inMemory(ACCOUNT);
        EcrService accountAwareService = new EcrService(
                repositories,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                registryManager,
                Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
        accountAwareService.createRepository(REPO, null, null, null, null, null, null, REGION);
        repositories.putForAccount("111111111111", REGION + "::111111111111::other", new Repository());

        accountAwareService.deleteRepository(REPO, null, true, REGION);

        verify(registryManager, never()).pruneStorage();
    }

    // ------------------------------------------------------------
    // GetAuthorizationToken
    // ------------------------------------------------------------

    @Test
    void getAuthorizationToken_decodesToAwsPrefix() {
        AuthorizationData data = service.getAuthorizationToken();
        assertNotNull(data.getAuthorizationToken());
        assertTrue(data.getProxyEndpoint().startsWith("http"));
        assertNotNull(data.getExpiresAt());
        String decoded = new String(Base64.getDecoder().decode(data.getAuthorizationToken()));
        assertTrue(decoded.startsWith("AWS:"), "decoded token should start with AWS: but was: " + decoded);
        verify(registryManager, never()).tryEnsureStarted();
    }

    @Test
    void pathStyleSeededRegistryEntriesAreVisibleViaListAndDescribeImages() throws Exception {
        String repositoryName = "backend-user";
        String internalRepository = ACCOUNT + "/" + REGION + "/" + repositoryName;
        String tag = "1";
        String digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 123,
                    "digest": "sha256:config"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": 456,
                      "digest": "sha256:layer"
                    }
                  ]
                }
                """;

        try (FakeRegistryServer registry = new FakeRegistryServer(internalRepository, tag, digest, manifest)) {
            when(registryManager.getRepositoryUri(ACCOUNT, REGION, repositoryName))
                    .thenReturn("localhost:" + registry.port() + "/" + internalRepository);
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION, null, null).items();
            assertEquals(1, imageIds.size());
            assertEquals(tag, imageIds.get(0).getImageTag());
            assertEquals(digest, imageIds.get(0).getImageDigest());

            EcrService.DescribeImagesResult described = service.describeImages(repositoryName, null, null, REGION);
            assertTrue(described.failures().isEmpty());
            assertEquals(1, described.imageDetails().size());

            ImageDetail detail = described.imageDetails().get(0);
            assertEquals(ACCOUNT, detail.getRegistryId());
            assertEquals(repositoryName, detail.getRepositoryName());
            assertEquals(digest, detail.getImageDigest());
            assertEquals(List.of(tag), detail.getImageTags());
            assertEquals(579L, detail.getImageSizeInBytes());
            assertEquals("application/vnd.docker.distribution.manifest.v2+json", detail.getImageManifestMediaType());
            assertEquals("application/vnd.docker.container.image.v1+json", detail.getArtifactMediaType());
            assertNotNull(detail.getImagePushedAt());
        }
    }

    // ------------------------------------------------------------
    // PutImageTagMutability
    // ------------------------------------------------------------

    @Test
    void hostnameStylePushesUnderBareRepoNameAreVisibleViaListAndDescribeImages() throws Exception {
        // A docker push to <account>.dkr.ecr.<region>.localhost:<port>/<repo>
        // reaches the raw registry, so the image is stored under the bare repo
        // name with no account/region prefix (issue #2444).
        String repositoryName = "probe/roundtrip";
        String tag = "v1";
        String digest = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 100,
                    "digest": "sha256:config"
                  },
                  "layers": []
                }
                """;

        try (FakeRegistryServer registry = new FakeRegistryServer(repositoryName, tag, digest, manifest)) {
            when(registryManager.getRepositoryUri(ACCOUNT, REGION, repositoryName))
                    .thenReturn(ACCOUNT + ".dkr.ecr." + REGION + ".localhost:" + registry.port() + "/" + repositoryName);
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION, null, null).items();
            assertEquals(1, imageIds.size());
            assertEquals(tag, imageIds.get(0).getImageTag());
            assertEquals(digest, imageIds.get(0).getImageDigest());

            EcrService.DescribeImagesResult described = service.describeImages(repositoryName, null, null, REGION);
            assertTrue(described.failures().isEmpty());
            assertEquals(1, described.imageDetails().size());
            assertEquals(digest, described.imageDetails().get(0).getImageDigest());
        }
    }

    @Test
    void deleteRepositoryForceDeletesResolvedStorageEntry() throws Exception {
        // Hostname-style push landed under the bare name; force-delete must
        // remove that entry (not the empty namespaced one) through the single
        // #2982 storage mechanism, then drop the metadata row.
        String repositoryName = "probe/deleteme";
        String tag = "v1";
        String digest = "sha256:3333333333333333333333333333333333333333333333333333333333333333";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;

        try (FakeRegistryServer registry = new FakeRegistryServer(repositoryName, tag, digest, manifest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
            service.deleteRepository(repositoryName, null, true, REGION);

            verify(registryManager).deleteRepositoryStorageByInternalName(repositoryName);
            assertThrows(AwsException.class,
                    () -> service.deleteRepository(repositoryName, null, true, REGION),
                    "metadata row must be gone after force-delete");
        }
    }

    @Test
    void bareEntryOnSecondCatalogPageStillResolves() throws Exception {
        // registry:2 caps _catalog pages at 100 entries; a bare entry beyond the
        // first page must still be found (Greptile P1: catalog pagination).
        String repositoryName = "probe/paged";
        String tag = "v1";
        String digest = "sha256:7777777777777777777777777777777777777777777777777777777777777777";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;

        try (PaginatedCatalogRegistryServer registry =
                new PaginatedCatalogRegistryServer(repositoryName, 150)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
            List<ImageIdentifier> ids = service.listImages(repositoryName, null, REGION, null, null).items();
            assertEquals(1, ids.size());
            assertEquals(tag, ids.get(0).getImageTag());
            assertEquals(digest, ids.get(0).getImageDigest());
            org.junit.jupiter.api.Assertions.assertTrue(registry.sawSecondPageRequest(),
                    "resolver must follow the Link header to page 2");
        }
    }

    @Test
    void untaggedDigestOnlyRepositoryResolvesAndDescribes() throws Exception {
        // A bare-name repo whose only manifest is untagged (digest-addressable):
        // tags/list returns null tags, but the catalog still lists the repo, so
        // resolution must not misroute it (Greptile P1: untagged images).
        String repositoryName = "probe/untagged";
        String digest = "sha256:4444444444444444444444444444444444444444444444444444444444444444";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;

        try (UntaggedRegistryServer registry = new UntaggedRegistryServer(repositoryName, digest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            // BatchGetImage by digest resolves the bare-name entry via catalog membership.
            EcrService.BatchGetImageResult got = service.batchGetImage(
                    repositoryName,
                    List.of(new ImageIdentifier(null, digest)),
                    null, null, REGION);
            assertTrue(got.failures().isEmpty(), () -> "unexpected failures: " + got.failures());
            assertEquals(1, got.images().size());
        }
    }

    // ------------------------------------------------------------
    // BatchDeleteImage
    // ------------------------------------------------------------

    private static final String DELETE_MANIFEST = """
            {
              "schemaVersion": 2,
              "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
              "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
              "layers": []
            }
            """;
    private static final String DELETE_DIGEST = "sha256:"
            + Digests.sha256Hex(DELETE_MANIFEST.getBytes(StandardCharsets.UTF_8));

    @Test
    void batchDeleteImageByTagKeepsTheSharedManifestForTheSurvivingTags() throws Exception {
        String repositoryName = "batch-delete/shared-manifest";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        try (MultiTagRegistryServer registry = new MultiTagRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, "release", "stable")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            EcrService.BatchDeleteImageResult result = service.batchDeleteImage(
                    repositoryName, List.of(new ImageIdentifier("release", null)), null, REGION);

            assertTrue(result.failures().isEmpty(), () -> "unexpected failures: " + result.failures());
            assertEquals(1, result.imageIds().size());
            assertEquals("release", result.imageIds().get(0).getImageTag());
            assertEquals(DELETE_DIGEST, result.imageIds().get(0).getImageDigest());
            assertEquals(List.of(DELETE_DIGEST), registry.deletedManifests());
            assertEquals(List.of("stable"), registry.putTags());

            List<ImageIdentifier> remaining = service.listImages(repositoryName, null, REGION, null, null).items();
            assertEquals(1, remaining.size());
            assertEquals("stable", remaining.get(0).getImageTag());
            assertEquals(DELETE_DIGEST, remaining.get(0).getImageDigest());

            EcrService.BatchGetImageResult got = service.batchGetImage(
                    repositoryName, List.of(new ImageIdentifier("stable", null)), null, null, REGION);
            assertTrue(got.failures().isEmpty(), () -> "unexpected failures: " + got.failures());
            assertEquals(1, got.images().size());
            assertEquals(DELETE_DIGEST, got.images().get(0).getImageId().getImageDigest());

            EcrService.DescribeImagesResult described = service.describeImages(
                    repositoryName, List.of(new ImageIdentifier("stable", null)), null, REGION);
            assertTrue(described.failures().isEmpty(), () -> "unexpected failures: " + described.failures());
            assertEquals(1, described.imageDetails().size());
            assertEquals(DELETE_DIGEST, described.imageDetails().get(0).getImageDigest());
            assertEquals(List.of("stable"), described.imageDetails().get(0).getImageTags());
        }
    }

    @Test
    void batchDeleteImageByTagKeepsTheImagePushedAtOfTheSurvivingTag() throws Exception {
        String repositoryName = "batch-delete/pushed-at";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        InMemoryStorage<String, ImageMetadata> metaStore = new InMemoryStorage<>();
        EcrService withMeta = new EcrService(new InMemoryStorage<>(), metaStore, pullThroughCacheRuleStore,
                registryManager, Mockito.mock(EmulatorConfig.class), new RegionResolver(REGION, ACCOUNT));
        try (MultiTagRegistryServer registry = new MultiTagRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, "release", "stable")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            withMeta.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            withMeta.describeImages(repositoryName, List.of(new ImageIdentifier("stable", null)), null, REGION);
            ImageMetadata before = metaStore.scan(k -> k.endsWith(DELETE_DIGEST)).get(0);

            withMeta.batchDeleteImage(repositoryName, List.of(new ImageIdentifier("release", null)), null, REGION);

            List<ImageMetadata> after = metaStore.scan(k -> k.endsWith(DELETE_DIGEST));
            assertEquals(1, after.size());
            assertEquals(before.getPushedAt(), after.get(0).getPushedAt());
        }
    }

    @Test
    void batchDeleteImageByTagDeletesTheManifestWhenTheLastTagGoes() throws Exception {
        String repositoryName = "batch-delete/last-tag";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        try (MultiTagRegistryServer registry = new MultiTagRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, "v1")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            EcrService.BatchDeleteImageResult result = service.batchDeleteImage(
                    repositoryName, List.of(new ImageIdentifier("v1", null)), null, REGION);

            assertTrue(result.failures().isEmpty(), () -> "unexpected failures: " + result.failures());
            assertEquals(1, result.imageIds().size());
            assertEquals("v1", result.imageIds().get(0).getImageTag());
            assertEquals(DELETE_DIGEST, result.imageIds().get(0).getImageDigest());
            assertEquals(List.of(DELETE_DIGEST), registry.deletedManifests());
            assertTrue(registry.putTags().isEmpty());
            assertTrue(service.listImages(repositoryName, null, REGION, null, null).items().isEmpty());
        }
    }

    @Test
    void batchDeleteImageByDigestDeletesTheManifestAndAllItsTags() throws Exception {
        String repositoryName = "batch-delete/by-digest";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        try (MultiTagRegistryServer registry = new MultiTagRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, "release", "stable")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            EcrService.BatchDeleteImageResult result = service.batchDeleteImage(
                    repositoryName, List.of(new ImageIdentifier(null, DELETE_DIGEST)), null, REGION);

            assertTrue(result.failures().isEmpty(), () -> "unexpected failures: " + result.failures());
            assertEquals(2, result.imageIds().size());
            List<String> reportedTags = result.imageIds().stream().map(ImageIdentifier::getImageTag).toList();
            assertTrue(reportedTags.contains("release"), "imageIds must report tag release");
            assertTrue(reportedTags.contains("stable"), "imageIds must report tag stable");
            for (ImageIdentifier id : result.imageIds()) {
                assertEquals(DELETE_DIGEST, id.getImageDigest());
            }
            assertEquals(List.of(DELETE_DIGEST), registry.deletedManifests());
            assertTrue(registry.tags().isEmpty());
            assertTrue(service.listImages(repositoryName, null, REGION, null, null).items().isEmpty());
        }
    }

    @Test
    void batchDeleteImageReportsAnUnknownTagAsAnImageFailure() throws Exception {
        String repositoryName = "batch-delete/unknown-tag";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        try (MultiTagRegistryServer registry = new MultiTagRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, "release", "stable")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            EcrService.BatchDeleteImageResult result = service.batchDeleteImage(repositoryName,
                    List.of(new ImageIdentifier("nope", null), new ImageIdentifier("release", null)),
                    null, REGION);

            assertEquals(1, result.imageIds().size());
            assertEquals("release", result.imageIds().get(0).getImageTag());
            assertEquals(1, result.failures().size());
            assertEquals("nope", result.failures().get(0).getImageId().getImageTag());
            assertEquals("ImageNotFound", result.failures().get(0).getFailureCode());
            assertEquals(List.of("stable"), registry.tags());
        }
    }

    @Test
    void batchDeleteImageRefusesTheDeleteWhenTheTagListingFails() throws Exception {
        String repositoryName = "batch-delete/listing-fails";
        RegistryHttpClient http = Mockito.mock(RegistryHttpClient.class);
        when(registryManager.httpClient()).thenReturn(http);
        when(http.headManifestDigest(anyString(), anyString(), any())).thenReturn(DELETE_DIGEST);
        when(http.listTagsStrict(anyString())).thenThrow(new ConnectException("Connection refused"));
        service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

        EcrService.BatchDeleteImageResult result = service.batchDeleteImage(
                repositoryName, List.of(new ImageIdentifier("release", null)), null, REGION);

        assertTrue(result.imageIds().isEmpty());
        assertEquals(1, result.failures().size());
        assertEquals("ServerException", result.failures().get(0).getFailureCode());
        verify(http, never()).deleteManifest(anyString(), anyString());
    }

    @Test
    void batchDeleteByTagHoldsTheRepositoryWriteLockFromTheDeleteThroughTheRestore() throws Exception {
        String repositoryName = "batch-delete/lock-held";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        CountDownLatch deleteArrived = new CountDownLatch(1);
        CountDownLatch deleteMayProceed = new CountDownLatch(1);
        CountDownLatch restoreArrived = new CountDownLatch(1);
        CountDownLatch restoreMayProceed = new CountDownLatch(1);
        try (PausableRegistryServer registry = new PausableRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST,
                deleteArrived, deleteMayProceed, restoreArrived, restoreMayProceed, "release", "stable")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<EcrService.BatchDeleteImageResult> batch = executor.submit(
                        () -> service.batchDeleteImage(
                                repositoryName, List.of(new ImageIdentifier("release", null)), null, REGION));

                assertTrue(deleteArrived.await(5, TimeUnit.SECONDS),
                        "the shared manifest delete reached the registry");
                assertTrue(repositoryWriteLockHeld(internal),
                        "the delete must hold the repository write lock while the manifest is dropped");
                deleteMayProceed.countDown();

                assertTrue(restoreArrived.await(5, TimeUnit.SECONDS),
                        "the surviving tag's manifest reached the registry again");
                assertTrue(repositoryWriteLockHeld(internal),
                        "the delete must hold the repository write lock while surviving tags are restored");
                restoreMayProceed.countDown();

                EcrService.BatchDeleteImageResult result = batch.get(5, TimeUnit.SECONDS);
                assertTrue(result.failures().isEmpty(), () -> "unexpected failures: " + result.failures());
                assertEquals(1, result.imageIds().size());
                assertEquals("release", result.imageIds().get(0).getImageTag());
                assertEquals(List.of("stable"), registry.putTags());
                assertEquals(DELETE_DIGEST, registry.digestOf("stable"));

                assertTrue(service.tryLockRepositoryWrites(internal),
                        "the repository write lock must be free after the delete");
                service.unlockRepositoryWrites(internal);
            } finally {
                deleteMayProceed.countDown();
                restoreMayProceed.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void putImageHoldsTheRepositoryWriteLockForItsManifestWrite() throws Exception {
        String repositoryName = "put-image/lock-held";
        String internal = ACCOUNT + "/" + REGION + "/" + repositoryName;
        String replacement = DELETE_MANIFEST.replace("\"size\": 1", "\"size\": 2");
        String replacementDigest = "sha256:" + Digests.sha256Hex(replacement.getBytes(StandardCharsets.UTF_8));
        CountDownLatch putArrived = new CountDownLatch(1);
        CountDownLatch putMayProceed = new CountDownLatch(1);
        try (PausableRegistryServer registry = new PausableRegistryServer(
                internal, DELETE_DIGEST, DELETE_MANIFEST, null, null, putArrived, putMayProceed, "v1")) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Image> push = executor.submit(
                        () -> service.putImage(repositoryName, replacement, null, "v1", null, null, REGION));

                assertTrue(putArrived.await(5, TimeUnit.SECONDS),
                        "the replacement manifest reached the registry");
                assertTrue(repositoryWriteLockHeld(internal),
                        "PutImage must hold the repository write lock while it writes the manifest");
                putMayProceed.countDown();

                Image image = push.get(5, TimeUnit.SECONDS);
                assertEquals(replacementDigest, image.getImageId().getImageDigest());
                assertEquals(replacementDigest, registry.digestOf("v1"));

                assertTrue(service.tryLockRepositoryWrites(internal),
                        "the repository write lock must be free after the push");
                service.unlockRepositoryWrites(internal);
            } finally {
                putMayProceed.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void batchDeleteByTagHoldsTheRegistryManagerMonitorAcrossTheSwap() throws Exception {
        String repositoryName = "batch-delete/monitor-held";
        RegistryHttpClient http = Mockito.mock(RegistryHttpClient.class);
        when(registryManager.httpClient()).thenReturn(http);
        when(http.headManifestDigest(anyString(), anyString(), any())).thenReturn(DELETE_DIGEST);
        when(http.headManifestDigestStrict(anyString(), anyString(), any())).thenReturn(DELETE_DIGEST);
        when(http.listTagsStrict(anyString())).thenReturn(List.of("stable"));
        when(http.getManifest(anyString(), anyString(), any())).thenReturn(new RegistryHttpClient.ManifestResult(
                DELETE_DIGEST, DELETE_MANIFEST, "application/vnd.docker.distribution.manifest.v2+json"));
        AtomicBoolean holdsAtDrop = new AtomicBoolean();
        AtomicBoolean holdsAtRestore = new AtomicBoolean();
        when(http.deleteManifest(anyString(), anyString())).thenAnswer(inv -> {
            holdsAtDrop.set(Thread.holdsLock(registryManager));
            return true;
        });
        when(http.putManifest(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            holdsAtRestore.set(Thread.holdsLock(registryManager));
            return DELETE_DIGEST;
        });
        service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

        EcrService.BatchDeleteImageResult result = service.batchDeleteImage(
                repositoryName, List.of(new ImageIdentifier("release", null)), null, REGION);

        assertTrue(result.failures().isEmpty(), () -> "unexpected failures: " + result.failures());
        assertEquals(1, result.imageIds().size());
        assertTrue(holdsAtDrop.get(),
                "the drop must run under the registry manager's monitor, which /_floci/ecr/gc holds");
        assertTrue(holdsAtRestore.get(),
                "the restore must run under the same monitor hold the drop opened");
    }

    /** Probes the repository write lock without taking it: true when another writer holds it. */    private boolean repositoryWriteLockHeld(String storageRepositoryName) {
        boolean acquired = service.tryLockRepositoryWrites(storageRepositoryName);
        if (acquired) {
            service.unlockRepositoryWrites(storageRepositoryName);
        }
        return !acquired;
    }

    @Test
    void repositoryWriteLockEntriesAreCollectedOnceTheirWritersAreDone() {
        String internal = ACCOUNT + "/" + REGION + "/write-locks/collected";
        assertTrue(service.tryLockRepositoryWrites(internal));
        assertEquals(1, service.liveRepositoryWriteLocks(), "a held lock keeps its entry");
        service.unlockRepositoryWrites(internal);
        assertEquals(0, service.liveRepositoryWriteLocks(),
                "the entry is collected once its writer is done");

        String missing = ACCOUNT + "/" + REGION + "/no-such/repository";
        assertTrue(service.tryLockRepositoryWrites(missing),
                "a registry write to a repository that does not exist takes the lock all the same");
        service.unlockRepositoryWrites(missing);
        assertEquals(0, service.liveRepositoryWriteLocks(),
                "a missing repository's lock entry must not stay in memory");
    }

    /**
     * A writer waiting on a held lock keeps the entry in the map, so a writer arriving later
     * meets the same lock instance and stays mutual-excluded with it: were the entry removed
     * at the release, the later writer would take a fresh instance while the waiter still
     * queues on the old one, and both would believe they hold the repository's write lock.
     */
    @Test
    void aWaitingWriterKeepsTheLockEntrySharedWithLaterArrivals() throws Exception {
        String internal = ACCOUNT + "/" + REGION + "/write-locks/waiting";
        assertTrue(service.tryLockRepositoryWrites(internal), "the test holds the lock first");

        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterHeld = new CountDownLatch(1);
        CountDownLatch waiterMayRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                waiterStarted.countDown();
                service.lockRepositoryWrites(internal);
                waiterHeld.countDown();
                waiterMayRelease.await();
                service.unlockRepositoryWrites(internal);
                return null;
            });
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
            awaitParkedOnTheLock(waiterThread.get());

            service.unlockRepositoryWrites(internal);
            assertTrue(waiterHeld.await(5, TimeUnit.SECONDS),
                    "the waiting writer takes the lock once the holder releases it");
            assertFalse(service.tryLockRepositoryWrites(internal),
                    "a later writer must be mutual-excluded by the writer waiting on the same entry");

            waiterMayRelease.countDown();
            waiter.get(5, TimeUnit.SECONDS);
            assertTrue(service.tryLockRepositoryWrites(internal),
                    "the lock is free again once its last writer is done");
            service.unlockRepositoryWrites(internal);
            assertEquals(0, service.liveRepositoryWriteLocks(),
                    "the entry is collected once its last writer is done");
        } finally {
            waiterMayRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void anInterruptedLockWaiterLeavesNoEntryBehind() throws Exception {
        String internal = ACCOUNT + "/" + REGION + "/write-locks/interrupted";
        service.lockRepositoryWrites(internal);

        AtomicReference<Thread> waiterThread = new AtomicReference<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                waiterThread.set(Thread.currentThread());
                waiterStarted.countDown();
                service.lockRepositoryWrites(internal);
                return null;
            });
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));
            awaitParkedOnTheLock(waiterThread.get());

            waiterThread.get().interrupt();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> waiter.get(5, TimeUnit.SECONDS),
                    "the interrupted waiter fails instead of taking the lock");
            assertEquals("ServerException", ((AwsException) failed.getCause()).getErrorCode());

            service.unlockRepositoryWrites(internal);
            assertEquals(0, service.liveRepositoryWriteLocks(),
                    "the interrupted waiter's entry is collected once the holder is done");
        } finally {
            executor.shutdownNow();
        }
    }

    /** Polls until the waiter is parked on the lock's semaphore, so it holds the entry. */
    private static void awaitParkedOnTheLock(Thread waiter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (waiter.getState() != Thread.State.WAITING) {
            assertTrue(System.currentTimeMillis() < deadline,
                    "the waiting writer never parked on the lock");
            Thread.sleep(5);
        }
    }

    @Test
    void bareEntryClaimedByOtherAccountIsNotResolvedForThisScope() throws Exception {
        // Both accounts have metadata for the same repo name in the same region;
        // only ONE bare registry entry exists. Account A's lookups must resolve
        // to its own (namespaced, empty) entry, never to the shared bare entry
        // holding account B's images (Greptile P1: scope isolation).
        String repositoryName = "probe/shared-name";
        String tag = "v1";
        String digest = "sha256:6666666666666666666666666666666666666666666666666666666666666666";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;

        try (FakeRegistryServer registry =
                new FakeRegistryServer(repositoryName, tag, digest, manifest)) {
            // The fake serves the BARE name in its catalog and tags, simulating
            // a hostname-style push that bypassed the namespacing.
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            // Account B created metadata for the same name.
            service.createRepository(repositoryName, "111111111111", null, null, null, null, null, REGION);
            // Account A creates its own repository; its namespaced entry does not
            // exist in this registry, but a bare entry with that name DOES, so the
            // ambiguity guard must keep resolution on A's namespaced form.
            service.createRepository(repositoryName, ACCOUNT, null, null, null, null, null, REGION);

            List<ImageIdentifier> ids = service.listImages(repositoryName, ACCOUNT, REGION, null, null).items();
            assertTrue(ids.isEmpty(),
                    "bare entry owned by another account must not resolve for this scope");
        }
    }

    @Test
    void deleteRepositoryForceFailsWhenRegistryCleanupFails() throws Exception {
        // Storage deletion throws: force-delete must surface a 500 and keep
        // the metadata row instead of reporting success (Greptile P1).
        String repositoryName = "probe/failing-delete";
        String tag = "v1";
        String digest = "sha256:5555555555555555555555555555555555555555555555555555555555555555";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {"mediaType": "application/vnd.docker.container.image.v1+json", "size": 1, "digest": "sha256:c"},
                  "layers": []
                }
                """;
        try (FakeRegistryServer registry = new FakeRegistryServer(repositoryName, tag, digest, manifest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));
            doThrow(new RuntimeException("rm failed"))
                    .when(registryManager).deleteRepositoryStorageByInternalName(anyString());

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.deleteRepository(repositoryName, null, true, REGION));
            assertEquals("ServerException", ex.getErrorCode());

            // Metadata survives so the operator can retry after fixing the registry.
            assertEquals(1, service.describeRepositories(List.of(repositoryName), null, REGION).size());
        }
    }

    @Test
    void deleteRepositoryForceAbortsOnNullPointerDuringDiscovery() throws Exception {
        // An NPE (bug or null body element) must abort the delete, never
        // masquerade as a registry outage (Greptile P1).
        String repositoryName = "probe/npe-discovery";
        RegistryHttpClient http = Mockito.mock(RegistryHttpClient.class);
        when(registryManager.httpClient()).thenReturn(http);
        when(http.listTagsStrict(anyString()))
                .thenThrow(new NullPointerException("registry endpoint is null"));

        service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteRepository(repositoryName, null, true, REGION));
        assertEquals("ServerException", ex.getErrorCode());

        // Metadata survives so the operator can retry.
        assertEquals(1, service.describeRepositories(List.of(repositoryName), null, REGION).size());
    }

    @Test
    void deleteRepositoryAbortsWhenRegistryUnreachable() throws Exception {
        // A refused connection (registry down) must abort the delete, never
        // assume the repo empty: otherwise non-force bypasses
        // RepositoryNotEmptyException and force orphans pullable images
        // behind deleted metadata (Greptile P1).
        String repositoryName = "probe/unreachable-registry";
        RegistryHttpClient http = Mockito.mock(RegistryHttpClient.class);
        when(registryManager.httpClient()).thenReturn(http);
        when(http.listTagsStrict(anyString()))
                .thenThrow(new java.net.ConnectException("Connection refused"));

        service.createRepository(repositoryName, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteRepository(repositoryName, null, true, REGION));
        assertEquals("ServerException", ex.getErrorCode());

        // Metadata survives so the operator can retry after recovery.
        assertEquals(1, service.describeRepositories(List.of(repositoryName), null, REGION).size());
    }

    @Test
    void putImage_retagKeepsTheFirstPushTime() {
        InMemoryStorage<String, ImageMetadata> metaStore = new InMemoryStorage<>();
        EcrService withMeta = new EcrService(new InMemoryStorage<>(), metaStore, pullThroughCacheRuleStore,
                registryManager, Mockito.mock(EmulatorConfig.class), new RegionResolver(REGION, ACCOUNT));
        when(registryManager.httpClient()).thenReturn(Mockito.mock(RegistryHttpClient.class));
        withMeta.createRepository(REPO, null, null, null, null, null, null, REGION);
        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\"}";

        withMeta.putImage(REPO, manifest, null, "v1", null, null, REGION);
        Instant firstPush = metaStore.scan(k -> true).get(0).getPushedAt();
        withMeta.putImage(REPO, manifest, null, "release", null, null, REGION);

        assertEquals(1, metaStore.scan(k -> true).size());
        assertEquals(firstPush, metaStore.scan(k -> true).get(0).getPushedAt());
    }

    @Test
    void putImageTagMutability_roundTrips() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        Repository updated = service.putImageTagMutability(REPO, null, "IMMUTABLE", REGION);
        assertEquals("IMMUTABLE", updated.getImageTagMutability());
        Repository fetched = service.describeRepositories(List.of(REPO), null, REGION).get(0);
        assertEquals("IMMUTABLE", fetched.getImageTagMutability());
    }

    @Test
    void putImageTagMutability_invalid_throws() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        assertThrows(AwsException.class,
                () -> service.putImageTagMutability(REPO, null, "WHATEVER", REGION));
    }

    // ------------------------------------------------------------
    // Resource tags
    // ------------------------------------------------------------

    @Test
    void tagResource_addsTags_listReturnsThem() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.tagResource(REPO, null, Map.of("env", "prod"), REGION);
        Map<String, String> tags = service.listTagsForResource(REPO, null, REGION);
        assertEquals("prod", tags.get("env"));
    }

    @Test
    void untagResource_removesTags() {
        service.createRepository(REPO, null, null, null, null, null,
                Map.of("env", "prod", "team", "platform"), REGION);
        service.untagResource(REPO, null, List.of("env"), REGION);
        Map<String, String> tags = service.listTagsForResource(REPO, null, REGION);
        assertNull(tags.get("env"));
        assertEquals("platform", tags.get("team"));
    }

    // ------------------------------------------------------------
    // Lifecycle policy
    // ------------------------------------------------------------

    @Test
    void lifecyclePolicy_roundTrip() {
        String policy = "{\"rules\":[]}";
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.putLifecyclePolicy(REPO, null, policy, REGION);
        Repository fetched = service.getLifecyclePolicy(REPO, null, REGION);
        assertEquals(policy, fetched.getLifecyclePolicyText());
        service.deleteLifecyclePolicy(REPO, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(REPO, null, REGION));
        assertEquals("LifecyclePolicyNotFoundException", ex.getErrorCode());
    }

    @Test
    void getLifecyclePolicy_unset_throws() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(REPO, null, REGION));
        assertEquals("LifecyclePolicyNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // Repository policy
    // ------------------------------------------------------------

    @Test
    void repositoryPolicy_roundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.setRepositoryPolicy(REPO, null, policy, REGION);
        Repository fetched = service.getRepositoryPolicy(REPO, null, REGION);
        assertEquals(policy, fetched.getRepositoryPolicyText());
        service.deleteRepositoryPolicy(REPO, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getRepositoryPolicy(REPO, null, REGION));
        assertEquals("RepositoryPolicyNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // Reconcile
    // ------------------------------------------------------------

    @Test
    void reconcileFromCatalog_recreatesMissingMetadata() {
        // Internal namespace pattern: <account>/<region>/<repoName>
        service.reconcileFromCatalog(List.of(
                ACCOUNT + "/" + REGION + "/recovered/one",
                ACCOUNT + "/" + REGION + "/recovered/two",
                "legacy/repository"));
        List<Repository> repos = service.describeRepositories(null, null, REGION);
        assertEquals(3, repos.size());
        assertTrue(repos.stream().anyMatch(r -> "recovered/one".equals(r.getRepositoryName())));
        assertTrue(repos.stream().anyMatch(r -> "recovered/two".equals(r.getRepositoryName())));
        assertTrue(repos.stream().anyMatch(r -> "legacy/repository".equals(r.getRepositoryName())));
    }

    @Test
    void reconcileFromCatalog_skipsExistingEntries() {
        service.createRepository(REPO, null, null, null, null, null,
                Map.of("preserved", "yes"), REGION);
        service.reconcileFromCatalog(List.of(ACCOUNT + "/" + REGION + "/" + REPO));
        Repository existing = service.describeRepositories(List.of(REPO), null, REGION).get(0);
        // Tag is still present → existing entry was NOT overwritten by reconcile
        assertEquals("yes", existing.getTags().get("preserved"));
    }

    private static final class FakeRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final String repository;
        private final String tag;
        private final String digest;
        private final String manifest;
        private final List<String> deletedDigests = new java.util.ArrayList<>();

        private FakeRegistryServer(String repository, String tag, String digest, String manifest) throws IOException {
            this.repository = repository;
            this.tag = tag;
            this.digest = digest;
            this.manifest = manifest;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private boolean sawDelete() {
            return !deletedDigests.isEmpty();
        }

        private String lastDeletedDigest() {
            return deletedDigests.isEmpty() ? null : deletedDigests.get(deletedDigests.size() - 1);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"repositories\":[\"" + repository + "\"]}");
                return;
            }
            if (path.equals("/v2/" + repository + "/manifests/" + digest) && "DELETE".equals(method)) {
                deletedDigests.add(digest);
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if (("/v2/" + repository + "/tags/list").equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":[\"" + tag + "\"]}");
                return;
            }
            if (("/v2/" + repository + "/manifests/" + tag).equals(path) && "HEAD".equals(method)) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ((("/v2/" + repository + "/manifests/" + tag).equals(path)
                    || ("/v2/" + repository + "/manifests/" + digest).equals(path))
                    && "GET".equals(method)) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                exchange.getResponseHeaders().add("Content-Type",
                        "application/vnd.docker.distribution.manifest.v2+json");
                send(exchange, 200, manifest);
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Registry whose _catalog spans two pages (Link header); repo lives on page 2. */
    private static final class PaginatedCatalogRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final String repository;
        private final String tag;
        private final String digest;
        private final List<String> filler;
        private volatile boolean secondPageRequested;

        private PaginatedCatalogRegistryServer(String repository, int fillerCount) throws IOException {
            this.repository = repository;
            this.tag = "v1";
            this.digest = "sha256:7777777777777777777777777777777777777777777777777777777777777777";
            List<String> f = new java.util.ArrayList<>();
            for (int i = 0; i < fillerCount; i++) {
                f.add(String.format("filler/%03d", i));
            }
            this.filler = f;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private boolean sawSecondPageRequest() {
            return secondPageRequested;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                boolean pageTwo = query != null && query.contains("last=");
                if (pageTwo) {
                    secondPageRequested = true;
                    sendJson(exchange, 200, "{\"repositories\":[\"" + repository + "\"]}");
                } else {
                    String joined = filler.stream()
                            .map(r -> "\"" + r + "\"")
                            .reduce((a, b) -> a + "," + b)
                            .orElse("");
                    exchange.getResponseHeaders().add("Link",
                            "</v2/_catalog?n=100&last=" + filler.get(filler.size() - 1).replace("/", "%2F") + ">; rel=\"next\"");
                    sendJson(exchange, 200, "{\"repositories\":[" + joined + "]}");
                }
                return;
            }
            if (path.equals("/v2/" + repository + "/tags/list") && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":[\"" + tag + "\"]}");
                return;
            }
            if (path.equals("/v2/" + repository + "/manifests/" + tag) && "HEAD".equals(method)) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Registry serving a catalog entry whose repository has no tags (digest-only). */
    private static final class UntaggedRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final String repository;
        private final String digest;

        private UntaggedRegistryServer(String repository, String digest) throws IOException {
            this.repository = repository;
            this.digest = digest;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"repositories\":[\"" + repository + "\"]}");
                return;
            }
            if (path.equals("/v2/" + repository + "/tags/list") && "GET".equals(method)) {
                // registry:2 returns tags: null when only untagged manifests exist.
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":null}");
                return;
            }
            if (path.equals("/v2/" + repository + "/manifests/" + digest)
                    && ("GET".equals(method) || "HEAD".equals(method))) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                if ("HEAD".equals(method)) {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type",
                        "application/vnd.docker.distribution.manifest.v2+json");
                send(exchange, 200, "{\"schemaVersion\":2,\"config\":{\"size\":1},\"layers\":[]}");
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Registry that reports a fixed catalog; nothing else resolves. */
    private static final class CatalogOnlyRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> repositories;

        private CatalogOnlyRegistryServer(List<String> repositories) throws IOException {
            this.repositories = repositories;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                String joined = repositories.stream()
                        .map(r -> "\"" + r + "\"")
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                sendJson(exchange, 200, "{\"repositories\":[" + joined + "]}");
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Registry fake holding several tags over one shared manifest, following registry:2's answers. */
    private static final class MultiTagRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final String repository;
        private final String digest;
        private final String manifest;
        private final List<String> tags = new ArrayList<>();
        private final List<String> deletedManifests = new ArrayList<>();
        private final List<String> putTags = new ArrayList<>();
        private boolean manifestGone;

        private MultiTagRegistryServer(String repository, String digest, String manifest, String... tags)
                throws IOException {
            this.repository = repository;
            this.digest = digest;
            this.manifest = manifest;
            this.tags.addAll(List.of(tags));
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private List<String> tags() {
            return List.copyOf(tags);
        }

        private List<String> deletedManifests() {
            return List.copyOf(deletedManifests);
        }

        private List<String> putTags() {
            return List.copyOf(putTags);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"repositories\":[\"" + repository + "\"]}");
                return;
            }
            String manifestsPrefix = "/v2/" + repository + "/manifests/";
            if ((path.equals("/v2/" + repository + "/tags/list")) && "GET".equals(method)) {
                String listed = tags.isEmpty()
                        ? "null"
                        : "[" + tags.stream().map(t -> "\"" + t + "\"")
                                .reduce((a, b) -> a + "," + b).orElse("") + "]";
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":" + listed + "}");
                return;
            }
            if (path.startsWith(manifestsPrefix)) {
                String reference = path.substring(manifestsPrefix.length());
                if ("DELETE".equals(method)) {
                    if (digest.equals(reference) && !manifestGone) {
                        deletedManifests.add(reference);
                        manifestGone = true;
                        tags.clear();
                        exchange.sendResponseHeaders(202, -1);
                        exchange.close();
                        return;
                    }
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                if ("PUT".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (manifest.equals(body)) {
                        if (manifestGone) {
                            manifestGone = false;
                        }
                        if (!tags.contains(reference)) {
                            tags.add(reference);
                        }
                        putTags.add(reference);
                        exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                        exchange.sendResponseHeaders(201, -1);
                        exchange.close();
                        return;
                    }
                    sendJson(exchange, 400, "{\"errors\":[{\"code\":\"DIGEST_INVALID\"}]}");
                    return;
                }
                boolean byDigest = digest.equals(reference);
                boolean byTag = !byDigest && tags.contains(reference);
                if ((byDigest || byTag) && !manifestGone && ("GET".equals(method) || "HEAD".equals(method))) {
                    exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                    if ("HEAD".equals(method)) {
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type",
                            "application/vnd.docker.distribution.manifest.v2+json");
                    send(exchange, 200, manifest);
                    return;
                }
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * Registry fake over tags and manifests the way registry:2 holds them, which parks the
     * digest delete and the manifest PUT of a test's choosing until the test lets them continue,
     * so the test can observe what the service holds while a write is in flight.
     */
    private static final class PausableRegistryServer implements AutoCloseable {
        private static final String MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json";

        private final HttpServer server;
        private final String repository;
        private final Map<String, String> manifests = new ConcurrentHashMap<>();
        private final Map<String, String> mediaTypes = new ConcurrentHashMap<>();
        private final Map<String, String> tags = new ConcurrentHashMap<>();
        private final List<String> putTags = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch deleteArrived;
        private final CountDownLatch deleteMayProceed;
        private final CountDownLatch putArrived;
        private final CountDownLatch putMayProceed;

        private PausableRegistryServer(String repository, String digest, String manifest,
                                       CountDownLatch deleteArrived, CountDownLatch deleteMayProceed,
                                       CountDownLatch putArrived, CountDownLatch putMayProceed,
                                       String... tags) throws IOException {
            this.repository = repository;
            this.deleteArrived = deleteArrived;
            this.deleteMayProceed = deleteMayProceed;
            this.putArrived = putArrived;
            this.putMayProceed = putMayProceed;
            this.manifests.put(digest, manifest);
            this.mediaTypes.put(digest, MANIFEST_MEDIA_TYPE);
            for (String tag : tags) {
                this.tags.put(tag, digest);
            }
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private List<String> putTags() {
            synchronized (putTags) {
                return List.copyOf(putTags);
            }
        }

        private String digestOf(String tag) {
            return tags.get(tag);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if ("/v2/_catalog".equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"repositories\":[\"" + repository + "\"]}");
                return;
            }
            String manifestsPrefix = "/v2/" + repository + "/manifests/";
            if (path.equals("/v2/" + repository + "/tags/list") && "GET".equals(method)) {
                String listed = tags.isEmpty()
                        ? "null"
                        : "[" + tags.keySet().stream().map(t -> "\"" + t + "\"")
                                .reduce((a, b) -> a + "," + b).orElse("") + "]";
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":" + listed + "}");
                return;
            }
            if (path.startsWith(manifestsPrefix)) {
                String reference = path.substring(manifestsPrefix.length());
                if ("DELETE".equals(method)) {
                    park(deleteArrived, deleteMayProceed);
                    if (manifests.remove(reference) == null) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    tags.values().removeAll(List.of(reference));
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                if ("PUT".equals(method)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    park(putArrived, putMayProceed);
                    String digest = "sha256:" + Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
                    manifests.put(digest, body);
                    String mediaType = exchange.getRequestHeaders().getFirst("Content-Type");
                    mediaTypes.put(digest, mediaType == null ? MANIFEST_MEDIA_TYPE : mediaType);
                    if (!digest.equals(reference)) {
                        tags.put(reference, digest);
                        putTags.add(reference);
                    }
                    exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                    exchange.sendResponseHeaders(201, -1);
                    exchange.close();
                    return;
                }
                String digest = tags.containsKey(reference) ? tags.get(reference) : reference;
                if (manifests.containsKey(digest) && ("GET".equals(method) || "HEAD".equals(method))) {
                    exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                    if ("HEAD".equals(method)) {
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type",
                            mediaTypes.getOrDefault(digest, MANIFEST_MEDIA_TYPE));
                    send(exchange, 200, manifests.get(digest));
                    return;
                }
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private void park(CountDownLatch arrived, CountDownLatch mayProceed) throws IOException {
            if (arrived == null || mayProceed == null) {
                return;
            }
            arrived.countDown();
            try {
                if (!mayProceed.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("parked registry write was never continued");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("parked registry write was interrupted", e);
            }
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------
    // Registry settings
    // ------------------------------------------------------------

    private static final String REGISTRY_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private static RegistrySettings.ReplicationRule replicateTo(String region, String registryId, String prefix) {
        return new RegistrySettings.ReplicationRule(
                List.of(new RegistrySettings.ReplicationDestination(region, registryId)),
                prefix == null ? null : List.of(new RegistrySettings.RepositoryFilter(prefix, "PREFIX_MATCH")));
    }

    private static RegistrySettings.ScanningRule scan(String frequency, String filter) {
        return new RegistrySettings.ScanningRule(frequency,
                List.of(new RegistrySettings.RepositoryFilter(filter, "WILDCARD")));
    }

    private static void assertInvalid(Runnable call) {
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, call::run).getErrorCode());
    }

    @Test
    void registryPolicy_roundTripsAndIsNotFoundOnceDeleted() {
        AwsException missing = assertThrows(AwsException.class, () -> service.getRegistryPolicy(REGION));
        assertEquals("RegistryPolicyNotFoundException", missing.getErrorCode());

        service.putRegistryPolicy(REGISTRY_POLICY, REGION);
        assertEquals(REGISTRY_POLICY, service.getRegistryPolicy(REGION));
        assertEquals(REGISTRY_POLICY, service.deleteRegistryPolicy(REGION));

        assertEquals("RegistryPolicyNotFoundException",
                assertThrows(AwsException.class, () -> service.getRegistryPolicy(REGION)).getErrorCode());
        assertEquals("RegistryPolicyNotFoundException",
                assertThrows(AwsException.class, () -> service.deleteRegistryPolicy(REGION)).getErrorCode());
    }

    @Test
    void registryPolicy_mustBeAJsonObject() {
        assertInvalid(() -> service.putRegistryPolicy("not json", REGION));
        assertInvalid(() -> service.putRegistryPolicy("[]", REGION));
        assertInvalid(() -> service.putRegistryPolicy("", REGION));
        assertInvalid(() -> service.putRegistryPolicy("{} x", REGION));
    }

    @Test
    void replication_roundTripsAndAnEmptyListClearsIt() {
        assertTrue(service.replicationRules(REGION).isEmpty());

        List<RegistrySettings.ReplicationRule> rules = List.of(
                replicateTo("us-west-2", ACCOUNT, "prod"),
                replicateTo("eu-west-1", "111111111111", null));
        assertEquals(rules, service.putReplicationConfiguration(rules, REGION));
        assertEquals(rules, service.replicationRules(REGION));

        assertTrue(service.putReplicationConfiguration(List.of(), REGION).isEmpty());
        assertTrue(service.replicationRules(REGION).isEmpty());
    }

    @Test
    void scanning_defaultsToBasicAndRoundTrips() {
        RegistrySettings initial = service.registryScanningConfiguration(REGION);
        assertEquals("BASIC", initial.getScanType());
        assertTrue(initial.getScanningRules().isEmpty());

        List<RegistrySettings.ScanningRule> rules = List.of(scan("CONTINUOUS_SCAN", "prod-*"), scan("SCAN_ON_PUSH", "*"));
        RegistrySettings saved = service.putRegistryScanningConfiguration("ENHANCED", rules, REGION);
        assertEquals("ENHANCED", saved.getScanType());
        assertEquals(rules, service.registryScanningConfiguration(REGION).getScanningRules());
    }

    @Test
    void scanning_refusesContinuousScanWithBasic() {
        assertInvalid(() -> service.putRegistryScanningConfiguration(
                "BASIC", List.of(scan("CONTINUOUS_SCAN", "*")), REGION));
        assertInvalid(() -> service.putRegistryScanningConfiguration(
                null, List.of(scan("CONTINUOUS_SCAN", "*")), REGION));
        assertDoesNotThrow(() -> service.putRegistryScanningConfiguration(
                "ENHANCED", List.of(scan("CONTINUOUS_SCAN", "*")), REGION));
    }

    @Test
    void registrySettings_areKeptPerAccountAndRegion() {
        InMemoryStorage<String, RegistrySettings> store = new InMemoryStorage<>();
        EcrService first = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                store, new InMemoryStorage<>(), registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
        EcrService other = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                store, new InMemoryStorage<>(), registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, "111111111111"));

        first.putRegistryPolicy(REGISTRY_POLICY, REGION);
        assertEquals(REGISTRY_POLICY, first.getRegistryPolicy(REGION));
        assertThrows(AwsException.class, () -> first.getRegistryPolicy("us-west-2"));
        assertThrows(AwsException.class, () -> other.getRegistryPolicy(REGION));
        assertEquals("111111111111", other.registryId());
    }

    /**
     * Terraform puts the policy, replication and scanning configuration in parallel. Each round
     * starts the three puts together on a registry with no settings yet, and all three must stay.
     */
    @Test
    void registrySettings_parallelPutsAllSurvive() throws Exception {
        EcrService parallel = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
        List<RegistrySettings.ReplicationRule> rules = List.of(replicateTo("eu-west-1", ACCOUNT, null));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 300; round++) {
                String region = "round-" + round;
                CyclicBarrier start = new CyclicBarrier(3);
                List<Runnable> puts = List.of(
                        () -> parallel.putRegistryPolicy(REGISTRY_POLICY, region),
                        () -> parallel.putReplicationConfiguration(rules, region),
                        () -> parallel.putRegistryScanningConfiguration("ENHANCED", List.of(), region));
                CompletableFuture.allOf(puts.stream().map(put -> CompletableFuture.runAsync(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    put.run();
                }, pool)).toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

                assertEquals(REGISTRY_POLICY, parallel.getRegistryPolicy(region), region);
                assertEquals(rules, parallel.replicationRules(region), region);
                assertEquals("ENHANCED", parallel.registryScanningConfiguration(region).getScanType(), region);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------
    // Repository creation templates
    // ------------------------------------------------------------

    private static RepositoryCreationTemplate template(String prefix, String... appliedFor) {
        RepositoryCreationTemplate t = new RepositoryCreationTemplate();
        t.setPrefix(prefix);
        t.setAppliedFor(appliedFor.length == 0 ? null : List.of(appliedFor));
        return t;
    }

    private static void assertInvalidTemplate(Executable call) {
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, call).getErrorCode());
    }

    @Test
    void creationTemplate_createsWithDefaultsAndRefusesADuplicate() {
        RepositoryCreationTemplate created = service.createRepositoryCreationTemplate(
                template("prod", "PULL_THROUGH_CACHE", "REPLICATION"), REGION);

        assertEquals("prod", created.getPrefix());
        assertEquals("AES256", created.getEncryptionConfiguration().encryptionType());
        assertEquals("MUTABLE", created.getImageTagMutability());
        assertEquals(List.of("PULL_THROUGH_CACHE", "REPLICATION"), created.getAppliedFor());
        assertNotNull(created.getCreatedAt());
        assertEquals(created.getCreatedAt(), created.getUpdatedAt());

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createRepositoryCreationTemplate(template("prod", "REPLICATION"), REGION));
        assertEquals("TemplateAlreadyExistsException", duplicate.getErrorCode());
    }

    /** The rules the model can't state; the model's own are in {@code EcrRequestShapesTest}. */
    @Test
    void creationTemplate_checksWhatTheModelCannotState() {
        RepositoryCreationTemplate noAppliedFor = template("prod");
        noAppliedFor.setAppliedFor(List.of());
        assertInvalidTemplate(() -> service.createRepositoryCreationTemplate(noAppliedFor, REGION));
        RepositoryCreationTemplate badPolicy = template("prod", "REPLICATION");
        badPolicy.setRepositoryPolicy("[]");
        assertInvalidTemplate(() -> service.createRepositoryCreationTemplate(badPolicy, REGION));
        RepositoryCreationTemplate badLifecycle = template("prod", "REPLICATION");
        badLifecycle.setLifecyclePolicy("not json");
        assertInvalidTemplate(() -> service.createRepositoryCreationTemplate(badLifecycle, REGION));
        // A JSON object followed by more text is still not a JSON object.
        RepositoryCreationTemplate trailingPolicy = template("prod", "REPLICATION");
        trailingPolicy.setRepositoryPolicy("{}{}");
        assertInvalidTemplate(() -> service.createRepositoryCreationTemplate(trailingPolicy, REGION));
        RepositoryCreationTemplate trailingLifecycle = template("prod", "REPLICATION");
        trailingLifecycle.setLifecyclePolicy("{\"rules\":[]} []");
        assertInvalidTemplate(() -> service.createRepositoryCreationTemplate(trailingLifecycle, REGION));

        RepositoryCreationTemplate full = template("ROOT", "CREATE_ON_PUSH");
        full.setEncryptionConfiguration(new RepositoryCreationTemplate.EncryptionConfiguration("KMS_DSSE",
                "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab"));
        full.setImageTagMutability("IMMUTABLE_WITH_EXCLUSION");
        full.setImageTagMutabilityExclusionFilters(
                List.of(new RepositoryCreationTemplate.ExclusionFilter("WILDCARD", "latest*")));
        full.setRepositoryPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        full.setLifecyclePolicy("{\"rules\":[]}");
        assertEquals("KMS_DSSE", service.createRepositoryCreationTemplate(full, REGION)
                .getEncryptionConfiguration().encryptionType());
    }

    @Test
    void creationTemplate_updateReplacesWhatIsSentAndKeepsTheRest() {
        RepositoryCreationTemplate created = template("team/app", "REPLICATION");
        created.setDescription("first");
        created.setRepositoryPolicy("{\"Version\":\"2012-10-17\"}");
        service.createRepositoryCreationTemplate(created, REGION);

        RepositoryCreationTemplate change = template("team/app");
        change.setImageTagMutability("IMMUTABLE");
        change.setRepositoryPolicy("");
        RepositoryCreationTemplate updated = service.updateRepositoryCreationTemplate(change, REGION);

        assertEquals("first", updated.getDescription());
        assertEquals(List.of("REPLICATION"), updated.getAppliedFor());
        assertEquals("IMMUTABLE", updated.getImageTagMutability());
        assertNull(updated.getRepositoryPolicy());

        RepositoryCreationTemplate badChange = template("team/app");
        badChange.setAppliedFor(List.of());
        assertInvalidTemplate(() -> service.updateRepositoryCreationTemplate(badChange, REGION));
        assertEquals("TemplateNotFoundException", assertThrows(AwsException.class,
                () -> service.updateRepositoryCreationTemplate(template("other"), REGION)).getErrorCode());
    }

    @Test
    void creationTemplate_filtersGoWhenAnUpdateLeavesAnExclusionMode() {
        RepositoryCreationTemplate created = template("team/app", "REPLICATION");
        created.setImageTagMutability("IMMUTABLE_WITH_EXCLUSION");
        created.setImageTagMutabilityExclusionFilters(
                List.of(new RepositoryCreationTemplate.ExclusionFilter("WILDCARD", "latest*")));
        service.createRepositoryCreationTemplate(created, REGION);

        RepositoryCreationTemplate otherMode = template("team/app");
        otherMode.setImageTagMutability("MUTABLE_WITH_EXCLUSION");
        assertEquals(1, service.updateRepositoryCreationTemplate(otherMode, REGION)
                .getImageTagMutabilityExclusionFilters().size(), "an exclusion mode keeps them");

        RepositoryCreationTemplate plain = template("team/app");
        plain.setImageTagMutability("MUTABLE");
        assertNull(service.updateRepositoryCreationTemplate(plain, REGION).getImageTagMutabilityExclusionFilters());
    }

    @Test
    void creationTemplate_describeFiltersAndPagesAndDeleteRemoves() {
        for (String prefix : List.of("c", "a", "b")) {
            service.createRepositoryCreationTemplate(template(prefix, "REPLICATION"), REGION);
        }
        PaginatedResult<RepositoryCreationTemplate> first =
                service.describeRepositoryCreationTemplates(null, 2, null, REGION);
        assertEquals(List.of("a", "b"), first.items().stream().map(RepositoryCreationTemplate::getPrefix).toList());
        PaginatedResult<RepositoryCreationTemplate> second =
                service.describeRepositoryCreationTemplates(null, 2, first.nextToken(), REGION);
        assertEquals(List.of("c"), second.items().stream().map(RepositoryCreationTemplate::getPrefix).toList());
        assertNull(second.nextToken());

        // A prefix with no template is left out rather than refused.
        assertEquals(List.of("b"), service.describeRepositoryCreationTemplates(List.of("b", "missing"), null, null, REGION)
                .items().stream().map(RepositoryCreationTemplate::getPrefix).toList());
        assertInvalidTemplate(() -> service.describeRepositoryCreationTemplates(null, 1001, null, REGION));

        assertEquals("b", service.deleteRepositoryCreationTemplate("b", REGION).getPrefix());
        assertEquals("TemplateNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteRepositoryCreationTemplate("b", REGION)).getErrorCode());
        assertEquals(2, service.describeRepositoryCreationTemplates(null, null, null, REGION).items().size());
    }

    @Test
    void creationTemplate_isKeptPerAccountAndRegion() {
        InMemoryStorage<String, RepositoryCreationTemplate> shared = new InMemoryStorage<>();
        EcrService home = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), shared, registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
        EcrService other = new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), shared, registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, "111111111111"));
        home.createRepositoryCreationTemplate(template("prod", "REPLICATION"), REGION);

        assertTrue(other.describeRepositoryCreationTemplates(null, null, null, REGION).items().isEmpty());
        assertTrue(home.describeRepositoryCreationTemplates(null, null, null, "eu-west-1").items().isEmpty());
        other.createRepositoryCreationTemplate(template("prod", "REPLICATION"), REGION);
        assertEquals("TemplateNotFoundException", assertThrows(AwsException.class,
                () -> home.deleteRepositoryCreationTemplate("prod", "eu-west-1")).getErrorCode());
    }

    /** ECR assumes a {@code /} after every prefix, so a trailing one names the same template. */
    @Test
    void creationTemplate_aTrailingSlashNamesTheSameTemplate() {
        service.createRepositoryCreationTemplate(template("prod/", "REPLICATION"), REGION);

        assertEquals("TemplateAlreadyExistsException", assertThrows(AwsException.class,
                () -> service.createRepositoryCreationTemplate(template("prod", "REPLICATION"), REGION)).getErrorCode());
        assertEquals(1, service.describeRepositoryCreationTemplates(List.of("prod"), null, null, REGION)
                .items().size());
        PaginatedResult<RepositoryCreationTemplate> both =
                service.describeRepositoryCreationTemplates(List.of("prod", "prod/"), 1, null, REGION);
        assertEquals(1, both.items().size());
        assertNull(both.nextToken());
        RepositoryCreationTemplate change = template("prod");
        change.setDescription("changed");
        assertEquals("changed", service.updateRepositoryCreationTemplate(change, REGION).getDescription());
        service.deleteRepositoryCreationTemplate("prod", REGION);
        assertTrue(service.describeRepositoryCreationTemplates(null, null, null, REGION).items().isEmpty());
    }

    @Test
    void creationTemplate_aSecondCreateOfThePrefixWaitsAndIsRefused() throws Exception {
        ParkingStore<RepositoryCreationTemplate> store = new ParkingStore<>();
        EcrService templates = templateService(store);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            store.parkNextRead();
            Future<?> first = pool.submit(() -> templates.createRepositoryCreationTemplate(
                    template("prod", "REPLICATION"), REGION));
            assertTrue(store.awaitParked());
            Future<?> second = pool.submit(() -> templates.createRepositoryCreationTemplate(
                    template("prod", "REPLICATION"), REGION));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS),
                    "the second create must wait for the first");

            store.release();
            first.get(5, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertEquals("TemplateAlreadyExistsException", ((AwsException) refused.getCause()).getErrorCode());
        } finally {
            store.release();
            pool.shutdownNow();
        }
    }

    @Test
    void creationTemplate_aDeleteDuringAnUpdateStaysDeleted() throws Exception {
        ParkingStore<RepositoryCreationTemplate> store = new ParkingStore<>();
        EcrService templates = templateService(store);
        templates.createRepositoryCreationTemplate(template("prod", "REPLICATION"), REGION);
        RepositoryCreationTemplate change = template("prod");
        change.setDescription("changed");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            store.parkNextRead();
            Future<?> update = pool.submit(() -> templates.updateRepositoryCreationTemplate(change, REGION));
            assertTrue(store.awaitParked());
            Future<?> delete = pool.submit(() -> templates.deleteRepositoryCreationTemplate("prod", REGION));
            assertThrows(TimeoutException.class, () -> delete.get(200, TimeUnit.MILLISECONDS),
                    "the delete must wait for the update");

            store.release();
            update.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
        } finally {
            store.release();
            pool.shutdownNow();
        }

        assertTrue(templates.describeRepositoryCreationTemplates(null, null, null, REGION).items().isEmpty());
    }

    private EcrService templateService(InMemoryStorage<String, RepositoryCreationTemplate> store) {
        return new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), store, registryManager, Mockito.mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
    }
}
