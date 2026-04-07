package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @BeforeEach
    void setUp() {
        registryManager = Mockito.mock(EcrRegistryManager.class);
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + ".dkr.ecr." + inv.getArgument(1)
                        + ".localhost:5000/" + inv.getArgument(2));
        when(registryManager.getProxyEndpoint()).thenReturn("http://localhost:5000");
        when(registryManager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1) + "/" + inv.getArgument(2));
        // ensureStarted() is a no-op on the mock — no Docker calls in any test below.

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);

        service = new EcrService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                registryManager,
                config);
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
        Mockito.verify(registryManager).ensureStarted();
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
        Mockito.verify(registryManager).ensureStarted();
    }

    // ------------------------------------------------------------
    // PutImageTagMutability
    // ------------------------------------------------------------

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
                "malformed-no-slashes"));
        List<Repository> repos = service.describeRepositories(null, null, REGION);
        assertEquals(2, repos.size());
        assertTrue(repos.stream().anyMatch(r -> "recovered/one".equals(r.getRepositoryName())));
        assertTrue(repos.stream().anyMatch(r -> "recovered/two".equals(r.getRepositoryName())));
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
}
