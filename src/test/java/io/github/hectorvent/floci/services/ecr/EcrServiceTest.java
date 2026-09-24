package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.PullThroughCacheRule;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

        AwsException auth = assertThrows(AwsException.class, () -> service.getAuthorizationToken());
        assertEquals("ServerException", auth.getErrorCode());
        assertEquals(500, auth.getHttpStatus());
        assertTrue(auth.getMessage().contains("Docker"), auth.getMessage());

        assertEquals("ServerException",
                assertThrows(AwsException.class, () -> service.listImages(REPO, null, REGION)).getErrorCode());
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
        Mockito.verify(registryManager).tryEnsureStarted();
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

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION);
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

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION);
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
            List<ImageIdentifier> ids = service.listImages(repositoryName, null, REGION);
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

            List<ImageIdentifier> ids = service.listImages(repositoryName, ACCOUNT, REGION);
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

}
