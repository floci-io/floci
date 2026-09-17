package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.DomainView;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.ResourcePolicy;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeArtifactServiceTest {
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String ACCOUNT_ID = "123456789012";

    private CodeArtifactService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<CodeArtifactDomain> domainStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        AccountAwareStorageBackend<CodeArtifactRepository> repoStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("codeartifact"), eq("codeartifact-domains.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) domainStore);
        when(storageFactory.create(eq("codeartifact"), eq("codeartifact-repositories.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) repoStore);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        service = new CodeArtifactService(storageFactory, regionResolver, config);
    }

    // -------------------------------------------------------------- domains

    @Test
    void createDomainAssignsArnAndDefaultEncryptionKey() {
        DomainView view = service.createDomain(REGION, "my-domain", null, Map.of());
        assertEquals("arn:aws:codeartifact:" + REGION + ":" + ACCOUNT_ID + ":domain/my-domain", view.domain().getArn());
        assertTrue(view.domain().getEncryptionKey().contains("alias/aws/codeartifact"));
        assertEquals(0, view.repositoryCount());
    }

    @Test
    void createDomainRejectsDuplicateName() {
        service.createDomain(REGION, "dup", null, Map.of());
        AwsException e = assertThrows(AwsException.class, () -> service.createDomain(REGION, "dup", null, Map.of()));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void createDomainRejectsInvalidName() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "Not-Valid-Upper", null, Map.of()));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void domainsAreScopedPerRegion() {
        service.createDomain(REGION, "shared-name", null, Map.of());
        DomainView otherRegion = service.createDomain(OTHER_REGION, "shared-name", null, Map.of());
        assertTrue(otherRegion.domain().getArn().contains(OTHER_REGION));
        assertEquals(1, service.listDomains(REGION, null, null).items().size());
        assertEquals(1, service.listDomains(OTHER_REGION, null, null).items().size());
    }

    @Test
    void deleteDomainFailsWhileRepositoriesExist() {
        service.createDomain(REGION, "with-repo", null, Map.of());
        service.createRepository(REGION, "with-repo", null, "repo-a", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "with-repo", null));
        assertEquals("ConflictException", e.getErrorCode());

        service.deleteRepository(REGION, "with-repo", null, "repo-a");
        DomainView deleted = service.deleteDomain(REGION, "with-repo", null);
        assertEquals("with-repo", deleted.domain().getName());
    }

    @Test
    void describeDomainReflectsLiveRepositoryCount() {
        service.createDomain(REGION, "counted", null, Map.of());
        assertEquals(0, service.describeDomain(REGION, "counted", null).repositoryCount());
        service.createRepository(REGION, "counted", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "counted", null, "repo-b", null, null, Map.of());
        assertEquals(2, service.describeDomain(REGION, "counted", null).repositoryCount());
    }

    @Test
    void concurrentCreateDomainWithSameNameOnlyOneWins() throws InterruptedException {
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        service.createDomain(REGION, "race-domain", null, Map.of());
                        successes.incrementAndGet();
                    } catch (AwsException e) {
                        if ("ConflictException".equals(e.getErrorCode())) {
                            conflicts.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, successes.get());
        assertEquals(attempts - 1, conflicts.get());
    }

    // ---------------------------------------------------------- repositories

    @Test
    void createRepositoryRequiresExistingDomain() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "missing-domain", null, "repo", null, null, Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void createRepositoryValidatesUpstreamsExistInSameDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("ghost"), Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void createRepositoryRejectsSelfAsUpstream() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("repo"), Map.of()));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createRepositoryAcceptsExistingUpstream() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        CodeArtifactRepository r = service.createRepository(REGION, "dom", null, "consumer", null,
                List.of("store"), Map.of());
        assertEquals(List.of("store"), r.getUpstreams());
    }

    @Test
    void updateRepositoryChangesDescriptionAndUpstreams() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", "old", null, Map.of());

        CodeArtifactRepository updated = service.updateRepository(REGION, "dom", null, "repo", "new",
                List.of("store"));
        assertEquals("new", updated.getDescription());
        assertEquals(List.of("store"), updated.getUpstreams());
    }

    @Test
    void createRepositoryRejectsMoreThanTenUpstreams() {
        service.createDomain(REGION, "dom", null, Map.of());
        List<String> upstreams = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String name = "store-" + i;
            service.createRepository(REGION, "dom", null, name, null, null, Map.of());
            upstreams.add(name);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "consumer", null, upstreams, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
    }

    @Test
    void listRepositoriesInDomainFiltersByPrefixAndDomain() {
        service.createDomain(REGION, "dom-a", null, Map.of());
        service.createDomain(REGION, "dom-b", null, Map.of());
        service.createRepository(REGION, "dom-a", null, "npm-repo", null, null, Map.of());
        service.createRepository(REGION, "dom-a", null, "pypi-repo", null, null, Map.of());
        service.createRepository(REGION, "dom-b", null, "npm-repo", null, null, Map.of());

        PaginatedResult<CodeArtifactRepository> page = service.listRepositoriesInDomain(REGION, "dom-a", null, null,
                "npm", null, null);
        assertEquals(1, page.items().size());
        assertEquals("npm-repo", page.items().get(0).getName());
    }

    @Test
    void getRepositoryEndpointValidatesFormatAndReturnsStableUrl() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        String endpoint = service.getRepositoryEndpoint(REGION, "dom", null, "repo", "npm", null);
        assertEquals("http://localhost:4566/codeartifact/npm/dom/repo/", endpoint);

        AwsException e = assertThrows(AwsException.class,
                () -> service.getRepositoryEndpoint(REGION, "dom", null, "repo", "not-a-format", null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    // ------------------------------------------------------ permissions policy

    @Test
    void putRepositoryPermissionsPolicyEnforcesOptimisticLocking() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        ResourcePolicy first = service.putRepositoryPermissionsPolicy(REGION, "dom", null, "repo", "{}", null);

        AwsException stale = assertThrows(AwsException.class, () -> service.putRepositoryPermissionsPolicy(
                REGION, "dom", null, "repo", "{}", "not-the-current-revision"));
        assertEquals("ConflictException", stale.getErrorCode());

        ResourcePolicy second = service.putRepositoryPermissionsPolicy(REGION, "dom", null, "repo", "{\"v\":2}",
                first.revision());
        assertEquals("{\"v\":2}", second.document());

        ResourcePolicy fetched = service.getRepositoryPermissionsPolicy(REGION, "dom", null, "repo");
        assertEquals(second.revision(), fetched.revision());

        service.deleteRepositoryPermissionsPolicy(REGION, "dom", null, "repo", second.revision());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.getRepositoryPermissionsPolicy(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    // ------------------------------------------------------- external connections

    @Test
    void associateExternalConnectionRejectsUnknownName() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.associateExternalConnection(REGION, "dom", null, "repo", "public:not-real"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void repositoryCanOnlyHaveOneExternalConnection() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        CodeArtifactRepository r = service.associateExternalConnection(REGION, "dom", null, "repo", "public:npmjs");
        assertEquals("npm", r.getExternalConnections().get(0).getPackageFormat());
        assertEquals("Available", r.getExternalConnections().get(0).getStatus());

        AwsException e = assertThrows(AwsException.class,
                () -> service.associateExternalConnection(REGION, "dom", null, "repo", "public:pypi"));
        assertEquals("ConflictException", e.getErrorCode());

        CodeArtifactRepository disassociated = service.disassociateExternalConnection(REGION, "dom", null, "repo",
                "public:npmjs");
        assertTrue(disassociated.getExternalConnections().isEmpty());
    }

    @Test
    void externalConnectionAndUpstreamsAreMutuallyExclusive() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "with-upstream", null, List.of("store"), Map.of());

        AwsException viaExternalConnection = assertThrows(AwsException.class, () -> service
                .associateExternalConnection(REGION, "dom", null, "with-upstream", "public:npmjs"));
        assertEquals("ConflictException", viaExternalConnection.getErrorCode());

        service.createRepository(REGION, "dom", null, "with-connection", null, null, Map.of());
        service.associateExternalConnection(REGION, "dom", null, "with-connection", "public:npmjs");
        AwsException viaUpstream = assertThrows(AwsException.class, () -> service
                .updateRepository(REGION, "dom", null, "with-connection", null, List.of("store")));
        assertEquals("ConflictException", viaUpstream.getErrorCode());
    }

    // ------------------------------------------------------------------- tags

    @Test
    void tagAndUntagResourceRoundTripForDomainAndRepository() {
        DomainView domain = service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository repo = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.tagResource(domain.domain().getArn(), Map.of("owner", "platform"));
        assertEquals(Map.of("owner", "platform"), service.listTagsForResource(domain.domain().getArn()));
        service.untagResource(domain.domain().getArn(), List.of("owner"));
        assertTrue(service.listTagsForResource(domain.domain().getArn()).isEmpty());

        service.tagResource(repo.getArn(), Map.of("team", "data"));
        assertEquals(Map.of("team", "data"), service.listTagsForResource(repo.getArn()));
    }

    @Test
    void tagResourceRejectsAwsReservedPrefix() {
        DomainView domain = service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.tagResource(domain.domain().getArn(), Map.of("aws:reserved", "x")));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void tagResourceRejectsUnknownResourceArn() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.tagResource("arn:aws:codeartifact:" + REGION + ":" + ACCOUNT_ID + ":domain/ghost",
                        Map.of("k", "v")));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void clearRemovesAllPersistedState() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.clear();
        assertTrue(service.listDomains(REGION, null, null).items().isEmpty());
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
