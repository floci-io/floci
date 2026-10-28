package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationToken;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.DomainView;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.ResourcePolicy;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.PackageOriginConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeArtifactServiceTest {
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String ACCOUNT_ID = "123456789012";

    private CodeArtifactService service;
    private RegionResolver regionResolver;
    private AccountAwareStorageBackend<CodeArtifactRepository> repoStore;
    private AccountAwareStorageBackend<PackageOriginConfig> packageOriginConfigStore;
    private VerdaccioSidecarClient verdaccioClient;
    private ReposiliteSidecarClient reposiliteClient;
    private PypiserverSidecarClient pypiserverClient;
    private BagetterSidecarClient bagetterClient;

    @BeforeEach
    void setUp() {
        AccountAwareStorageBackend<CodeArtifactDomain> domainStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        repoStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersionStore =
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        packageOriginConfigStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);

        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        verdaccioClient = mock(VerdaccioSidecarClient.class);
        when(verdaccioClient.format()).thenReturn("npm");
        reposiliteClient = mock(ReposiliteSidecarClient.class);
        when(reposiliteClient.format()).thenReturn("maven");
        pypiserverClient = mock(PypiserverSidecarClient.class);
        when(pypiserverClient.format()).thenReturn("pypi");
        bagetterClient = mock(BagetterSidecarClient.class);
        when(bagetterClient.format()).thenReturn("nuget");
        service = new CodeArtifactService(domainStore, repoStore, packageVersionStore, packageOriginConfigStore,
                regionResolver, config, true, null, new CodeArtifactSidecarRegistry(
                        List.of(verdaccioClient, reposiliteClient, pypiserverClient, bagetterClient)));
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
        assertEquals("dup", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
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
    void createDomainRejectsMoreThanTenDomainsPerAccount() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom-overflow", null, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals(402, e.getHttpStatus());
        assertEquals("dom-overflow", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createDomainAtCapStillReturnsConflictForDuplicateName() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom-0", null, Map.of()));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void deletingDomainFreesRoomForANewOne() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        service.deleteDomain(REGION, "dom-0", null);
        DomainView view = service.createDomain(REGION, "dom-new", null, Map.of());
        assertEquals("dom-new", view.domain().getName());
    }

    @Test
    void domainQuotaIsScopedPerRegion() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        DomainView otherRegion = service.createDomain(OTHER_REGION, "dom-0", null, Map.of());
        assertEquals("dom-0", otherRegion.domain().getName());
    }

    @Test
    void domainQuotaIsScopedPerAccount() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        String otherAccount = "999999999999";
        when(regionResolver.getAccountId()).thenReturn(otherAccount);
        DomainView view = service.createDomain(REGION, "dom-0", null, Map.of());
        assertEquals(otherAccount, view.domain().getOwner());
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
    void deleteDomainOfMissingDomainReturnsResourceNotFound() {
        // AWS returns this even though the API reference does not list it on DeleteDomain.
        AwsException e = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "never-created", null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());

        service.createDomain(REGION, "once", null, Map.of());
        service.deleteDomain(REGION, "once", null);
        AwsException again = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "once", null));
        assertEquals("ResourceNotFoundException", again.getErrorCode());
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
    void createAndDeleteDomainReportZeroAssetSizeBytes() {
        DomainView created = service.createDomain(REGION, "fresh", null, Map.of());
        assertEquals(0L, created.assetSizeBytes());
        DomainView deleted = service.deleteDomain(REGION, "fresh", null);
        assertEquals(0L, deleted.assetSizeBytes());
    }

    @Test
    void describeDomainAssetSizeBytesSumsGenericAssetsAcrossRepositoriesAndVersions() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-b", null, null, Map.of());
        byte[] first = "first-package".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-package-longer".getBytes(StandardCharsets.UTF_8);
        byte[] third = "third".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo-a", "generic", "ns", "pkg-a", "1.0.0", "a.txt",
                Digests.sha256Hex(first), "false", first);
        service.publishPackageVersion(REGION, "dom", null, "repo-a", "generic", "ns", "pkg-a", "2.0.0", "a.txt",
                Digests.sha256Hex(second), "false", second);
        service.publishPackageVersion(REGION, "dom", null, "repo-b", "generic", "ns", "pkg-b", "1.0.0", "a.txt",
                Digests.sha256Hex(third), "false", third);

        long expected = first.length + second.length + third.length;
        assertEquals(expected, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    @Test
    void describeDomainAssetSizeBytesDropsByExactlyWhatDeletePackageRemoves() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] kept = "kept-package".getBytes(StandardCharsets.UTF_8);
        byte[] removed = "removed-package-content".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "kept", "1.0.0", "a.txt",
                Digests.sha256Hex(kept), "false", kept);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "removed", "1.0.0", "a.txt",
                Digests.sha256Hex(removed), "false", removed);
        assertEquals(kept.length + removed.length, service.describeDomain(REGION, "dom", null).assetSizeBytes());

        service.deletePackage(REGION, "dom", null, "repo", "generic", "ns", "removed");

        assertEquals(kept.length, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * {@code deleteRepository} does not clean up a generic package's {@code packageVersions}
     * records or asset bytes today, so they are only unreachable through the normal API, not
     * actually gone. Without scoping the sum to each still-live repository's own key prefix, the
     * domain total would keep counting a deleted repository's orphaned bytes forever.
     */
    @Test
    void describeDomainAssetSizeBytesExcludesADeletedRepositorysOrphanedBytes() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-b", null, null, Map.of());
        byte[] inDeletedRepo = "goes-away-with-repo-a".getBytes(StandardCharsets.UTF_8);
        byte[] inLiveRepo = "stays-in-repo-b".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo-a", "generic", "ns", "pkg-a", "1.0.0", "a.txt",
                Digests.sha256Hex(inDeletedRepo), "false", inDeletedRepo);
        service.publishPackageVersion(REGION, "dom", null, "repo-b", "generic", "ns", "pkg-b", "1.0.0", "a.txt",
                Digests.sha256Hex(inLiveRepo), "false", inLiveRepo);

        service.deleteRepository(REGION, "dom", null, "repo-a");

        assertEquals(inLiveRepo.length, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * A Maven/npm/pypi package merely existing on its sidecar (confirmed here via a stubbed
     * {@code packageExists}) contributes nothing on its own: {@code CodeArtifactService} never
     * queries a sidecar for size, only the running total each format's own proxy explicitly
     * reports through {@link CodeArtifactService#recordContainerBackedAssetBytes}, which nothing
     * in this test ever calls. The next tests below exercise that path directly.
     */
    @Test
    void aContainerBackedPackageExistingWithNoRecordedBytesContributesNothing() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] generic = "generic-package".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "generic-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(generic), "false", generic);
        when(reposiliteClient.packageExists(anyString(), eq("dom"), eq("repo"), eq("com.example"), eq("maven-pkg")))
                .thenReturn(true);

        assertEquals(generic.length, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Mirrors exactly what each real proxy does: snapshot {@code currentContainerBackedPackageFence}
     * before the (here, simulated) real write, then report it alongside the byte count, the way
     * {@code CodeArtifactMavenController}/the npm data plane/{@code CodeArtifactPypiController}
     * all do for real.
     */
    private void recordBytes(String domain, String repository, String format, String namespace, String packageName,
            long bytes) {
        long fence = service.currentContainerBackedPackageFence(REGION, domain, null, repository, format, namespace,
                packageName);
        service.recordContainerBackedAssetBytes(REGION, domain, null, repository, format, namespace, packageName,
                bytes, fence);
    }

    /** Same snapshot-then-report shape as {@link #recordBytes}, for readme capture instead. */
    private void recordReadme(String domain, String repository, String format, String namespace, String packageName,
            String version, String readme) {
        long fence = service.currentContainerBackedVersionFence(REGION, domain, null, repository, format, namespace,
                packageName, version);
        service.recordPublishedPackageVersionReadme(REGION, domain, null, repository, format, namespace, packageName,
                version, readme, fence);
    }

    /**
     * {@code recordContainerBackedAssetBytes} is what each of the three sidecar-backed proxies
     * calls after a real (non-idempotent, non-rejected) write; this is the unit-level proof that
     * it feeds {@code assetSizeBytes} correctly across all three formats plus generic at once,
     * accumulating repeated calls for the same package the way a multi-file Maven deploy
     * (jar + pom as separate PUTs) needs.
     */
    @Test
    void describeDomainAssetSizeBytesSumsContainerBackedBytesAcrossEveryFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] generic = "generic".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "generic-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(generic), "false", generic);
        recordBytes("dom", "repo", "maven", "com.example", "maven-pkg", 100L);
        recordBytes("dom", "repo", "maven", "com.example", "maven-pkg", 50L);
        recordBytes("dom", "repo", "npm", null, "npm-pkg", 200L);
        recordBytes("dom", "repo", "pypi", null, "pypi-pkg", 300L);

        long expected = generic.length + 100L + 50L + 200L + 300L;
        assertEquals(expected, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Unlike every JSON-API package operation's own namespace/packageName, the wire-protocol
     * proxies (Maven's GAV path, npm's scope/name, twine's form field) never validate theirs
     * before calling this: a crafted namespace spelling out another namespace's already-escaped
     * form (here, raw {@code "a#cb"} vs. legitimate {@code "a:b"}, see
     * {@code newEscapedKeysNeverCollideWithAPreExistingRawKeyThatHappenedToContainABackslash} for
     * the same invariant proven on the JSON API side) must not be allowed to land in
     * {@code containerBackedAssetBytes} under the legitimate package's own key at all.
     */
    @Test
    void recordContainerBackedAssetBytesIgnoresANamespaceContainingTheEscapeCharacter() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordBytes("dom", "repo", "maven", "a:b", "legit", 10L);

        recordBytes("dom", "repo", "maven", "a#cb", "legit", 999L);

        assertEquals(10L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    @Test
    void recordContainerBackedAssetBytesIgnoresAPackageNameContainingTheEscapeCharacter() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        recordBytes("dom", "repo", "pypi", null, "bad#name", 999L);

        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    @Test
    void describeDomainAssetSizeBytesDropsWhenDeletePackageRemovesAContainerBackedPackage() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(reposiliteClient.packageExists(anyString(), eq("dom"), eq("repo"), eq("com.example"), eq("removed")))
                .thenReturn(true);
        recordBytes("dom", "repo", "maven", "com.example", "kept", 40L);
        recordBytes("dom", "repo", "maven", "com.example", "removed", 60L);
        assertEquals(100L, service.describeDomain(REGION, "dom", null).assetSizeBytes());

        service.deletePackage(REGION, "dom", null, "repo", "maven", "com.example", "removed");

        assertEquals(40L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Same orphan-exclusion property as generic (see the backslash/collision test above), proven
     * here for the sidecar ledger: {@code deleteRepository} actually does clean this one up (unlike
     * {@code packageVersions}), but the sum is scoped per live repository regardless, so this holds
     * either way.
     */
    @Test
    void describeDomainAssetSizeBytesExcludesADeletedRepositorysContainerBackedBytes() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-b", null, null, Map.of());
        recordBytes("dom", "repo-a", "npm", null, "pkg-a", 70L);
        recordBytes("dom", "repo-b", "npm", null, "pkg-b", 30L);

        service.deleteRepository(REGION, "dom", null, "repo-a");

        assertEquals(30L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * {@code assetSizeBytesForDomain} scans generic {@code packageVersions} by each live
     * repository's name-based prefix, not by a freshly minted id, so a repository recreated under
     * this same name would otherwise sum right back into any orphaned record
     * {@code deleteRepository} failed to clean up: unlike {@code containerBackedAssetBytes}, there
     * was never any cleanup of {@code packageVersions} on {@code deleteRepository} before this,
     * since nothing before {@code assetSizeBytes} existed ever looked an old repository's
     * generic-format records up by name alone the way this domain-wide sum does.
     */
    @Test
    void describeDomainAssetSizeBytesDoesNotResurrectAGenericPackagesBytesAfterRepositoryRecreation() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "generic content".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "generic-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        assertEquals(content.length, service.describeDomain(REGION, "dom", null).assetSizeBytes());

        service.deleteRepository(REGION, "dom", null, "repo");
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Reproduces the one window where a real publish and a real {@code DeleteRepository} could
     * interleave: the sidecar-backed proxies check the repository exists in one {@code
     * synchronized} call ({@code ensureFormatContainerId}), then do the real HTTP write to the
     * sidecar with no lock held at all, and only then call this method in a second, separate
     * {@code synchronized} call. A {@code DeleteRepository} landing in that gap would otherwise
     * resurrect a key it just cleaned up. Calling {@code recordContainerBackedAssetBytes} after
     * {@code deleteRepository} reproduces exactly that interleaving deterministically (both methods
     * are themselves {@code synchronized}, so there is no actual race to simulate, only the order
     * the two calls land in): the resurrected entry must not survive, and in particular must not
     * be inherited by a same-named repository created afterward, the same invariant
     * {@code deleteRepository}'s own cleanup already protects against for every ordering but this
     * one.
     */
    @Test
    void recordContainerBackedAssetBytesAfterTheRepositoryIsAlreadyDeletedIsIgnored() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        long fence = service.currentContainerBackedPackageFence(REGION, "dom", null, "repo", "maven", "com.example",
                "late");
        service.deleteRepository(REGION, "dom", null, "repo");

        service.recordContainerBackedAssetBytes(REGION, "dom", null, "repo", "maven", "com.example", "late", 999L,
                fence);

        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * The same window, but for {@code DeletePackage} instead of {@code DeleteRepository}: the
     * repository stays alive, only the package underneath it is gone, so a resurrected entry
     * would be counted by {@code DescribeDomain} immediately rather than only after a repository
     * is recreated. Snapshotting the fence before the delete, exactly like a real proxy snapshots
     * it before its real write, reproduces exactly the interleaving a delayed accounting callback
     * racing a concurrent {@code DeletePackage} would see.
     */
    @Test
    void recordContainerBackedAssetBytesAfterAConcurrentDeletePackageIsIgnored() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        long fence = service.currentContainerBackedPackageFence(REGION, "dom", null, "repo", "maven", "com.example",
                "late");
        when(reposiliteClient.packageExists(anyString(), eq("dom"), eq("repo"), eq("com.example"), eq("late")))
                .thenReturn(true);
        service.deletePackage(REGION, "dom", null, "repo", "maven", "com.example", "late");

        service.recordContainerBackedAssetBytes(REGION, "dom", null, "repo", "maven", "com.example", "late", 999L,
                fence);

        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * The harder version of the same race: the package is deleted {@code and} a different publish
     * recreates it under the same name, both completing before the first publish's own delayed
     * accounting call runs. A sidecar existence check alone could not tell these two packages
     * apart (the name exists either way); the fence can, since only an actual deletion bumps it.
     */
    @Test
    void recordContainerBackedAssetBytesAfterDeleteAndRepublishUnderTheSameNameIsIgnored() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        long fence = service.currentContainerBackedPackageFence(REGION, "dom", null, "repo", "maven", "com.example",
                "late");
        when(reposiliteClient.packageExists(anyString(), eq("dom"), eq("repo"), eq("com.example"), eq("late")))
                .thenReturn(true);
        service.deletePackage(REGION, "dom", null, "repo", "maven", "com.example", "late");
        // The republish: a different publish, same coordinates, after the first one's snapshot.
        recordBytes("dom", "repo", "maven", "com.example", "late", 10L);

        service.recordContainerBackedAssetBytes(REGION, "dom", null, "repo", "maven", "com.example", "late", 999L,
                fence);

        assertEquals(10L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * {@code currentContainerBackedPackageFence}/{@code recordContainerBackedAssetBytes} never
     * touch a sidecar at all, so there is no failure mode left to fail open on: the fence
     * comparison is a plain in-memory read, always available, closing the same race the sidecar
     * round trip this replaced used to close, at the cost of a thrown exception whenever it
     * couldn't reach the sidecar.
     */
    @Test
    void recordContainerBackedAssetBytesNeverNeedsTheSidecarAtAll() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(reposiliteClient.packageExists(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("sidecar unreachable"));

        recordBytes("dom", "repo", "maven", "com.example", "never-asked", 55L);

        assertEquals(55L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
        verify(reposiliteClient, never()).packageExists(any(), any(), any(), any(), any());
    }

    /**
     * {@code packageOriginConfigs} keeps the caller's raw spelling (pre-existing, unrelated to byte
     * accounting); {@code containerBackedAssetBytes}/the fence use pypiserver's own PEP 503
     * normalization instead, so an upload spelled one way and a {@code DeletePackage} spelled a
     * case/punctuation-equivalent way still address the one byte count pypiserver itself would
     * treat as a single package.
     */
    @Test
    void pypiAccountingNormalizesThePackageNameLikePypiserverItself() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordBytes("dom", "repo", "pypi", null, "My.Package", 42L);
        assertEquals(42L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my_package")))
                .thenReturn(true);

        service.deletePackage(REGION, "dom", null, "repo", "pypi", null, "my_package");

        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * AWS's own NuGet normalization doc: package names are lowercased only, no further collapsing
     * the way pypi's PEP 503 normalization does, so an upload spelled with mixed case and a {@code
     * DeletePackage} spelled already-lowercase still address the one byte count BaGetter itself would
     * treat as a single package.
     */
    @Test
    void nugetAccountingNormalizesThePackageNameToLowercase() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordBytes("dom", "repo", "nuget", null, "My.Package", 42L);
        assertEquals(42L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
        when(bagetterClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my.package")))
                .thenReturn(true);

        service.deletePackage(REGION, "dom", null, "repo", "nuget", null, "my.package");

        assertEquals(0L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
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

    @Test
    void requiredDomainFieldRejectsNullInsteadOfCrashing() {
        AwsException describe = assertThrows(AwsException.class, () -> service.describeDomain(REGION, null, null));
        assertEquals("ValidationException", describe.getErrorCode());

        AwsException delete = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, null, null));
        assertEquals("ValidationException", delete.getErrorCode());

        AwsException getPolicy = assertThrows(AwsException.class,
                () -> service.getDomainPermissionsPolicy(REGION, null, null));
        assertEquals("ValidationException", getPolicy.getErrorCode());
    }

    // ---------------------------------------------------------- repositories

    @Test
    void requiredRepositoryFieldsRejectNullInsteadOfCrashing() {
        service.createDomain(REGION, "dom", null, Map.of());

        AwsException missingDomain = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, null, null, "repo"));
        assertEquals("ValidationException", missingDomain.getErrorCode());
        assertEquals("domain is required.", missingDomain.getMessage());

        AwsException missingRepository = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, null));
        assertEquals("ValidationException", missingRepository.getErrorCode());
        assertEquals("repository is required.", missingRepository.getMessage());

        AwsException missingRepositoryEndpoint = assertThrows(AwsException.class,
                () -> service.getRepositoryEndpoint(REGION, null, null, "repo", "npm", null));
        assertEquals("ValidationException", missingRepositoryEndpoint.getErrorCode());
    }

    @Test
    void createRepositoryRequiresExistingDomain() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "missing-domain", null, "repo", null, null, Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("missing-domain", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryValidatesUpstreamsExistInSameDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("ghost"), Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("ghost", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryRejectsSelfAsUpstream() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("repo"), Map.of()));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createRepositoryAssignsFreshContainerIdsForEveryContainerBackedFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        assertEquals(Set.of("maven", "npm", "pypi", "nuget"), created.getSidecarContainerIds().keySet());
        created.getSidecarContainerIds().values()
                .forEach(id -> assertTrue(id != null && !id.isBlank()));
    }

    @Test
    void createRepositoryAssignsFreshContainerIdsEvenAfterDeleteAndRecreate() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository first = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.deleteRepository(REGION, "dom", null, "repo");
        CodeArtifactRepository recreated = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        for (String format : Set.of("maven", "npm", "pypi")) {
            assertTrue(
                    !first.getSidecarContainerIds().get(format).equals(recreated.getSidecarContainerIds().get(format)),
                    "a recreated repository must never reuse the previous one's container id for a format, "
                            + "or it would inherit its leftover artifacts");
        }
    }

    @Test
    void ensureFormatContainerIdBackfillsALegacyRepositoryMissingOneFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        // Simulates a repository persisted before the maven format's proxy existed: Jackson would
        // deserialize a missing map entry exactly like this.
        created.setSidecarContainerIds(new HashMap<>());
        repoStore.putForAccount(ACCOUNT_ID, REGION + "::dom::repo", created);

        String backfilled = service.ensureFormatContainerId("maven", REGION, "dom", null, "repo");

        assertTrue(backfilled != null && !backfilled.isBlank());
        assertEquals(backfilled,
                service.describeRepository(REGION, "dom", null, "repo").getSidecarContainerIds().get("maven"));
    }

    @Test
    void ensureFormatContainerIdBackfillsALegacyRepositoryMissingTheNpmFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        // Simulates a repository persisted before the npm format's proxy existed: Jackson would
        // deserialize a missing map entry exactly like this.
        Map<String, String> withoutNpm = new HashMap<>(created.getSidecarContainerIds());
        withoutNpm.remove("npm");
        created.setSidecarContainerIds(withoutNpm);
        repoStore.putForAccount(ACCOUNT_ID, REGION + "::dom::repo", created);

        String backfilled = service.ensureFormatContainerId("npm", REGION, "dom", null, "repo");

        assertTrue(backfilled != null && !backfilled.isBlank());
        assertEquals(backfilled,
                service.describeRepository(REGION, "dom", null, "repo").getSidecarContainerIds().get("npm"));
    }

    @Test
    void ensureFormatContainerIdIsIdempotentForAnAlreadyAssignedFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        String result = service.ensureFormatContainerId("maven", REGION, "dom", null, "repo");

        assertEquals(created.getSidecarContainerIds().get("maven"), result);
    }

    @Test
    void ensureFormatContainerIdIsIdempotentForAnAlreadyAssignedNpmFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        String result = service.ensureFormatContainerId("npm", REGION, "dom", null, "repo");

        assertEquals(created.getSidecarContainerIds().get("npm"), result);
    }

    @Test
    void ensureFormatContainerIdRequiresExistingRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.ensureFormatContainerId("maven", REGION, "dom", null, "missing-repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteRepositoryReleasesEveryFormatsSidecarStorage() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.deleteRepository(REGION, "dom", null, "repo");

        verify(verdaccioClient).release(created.getSidecarContainerIds().get("npm"));
        verify(reposiliteClient).release(created.getSidecarContainerIds().get("maven"));
    }

    @Test
    void deleteRepositorySucceedsEvenWhenOneFormatsSidecarReleaseFails() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        doThrow(new IllegalStateException("Reposilite unreachable")).when(reposiliteClient).release(anyString());

        // The metadata delete already committed; a sidecar failure must not turn that into a
        // caller-visible error, and must not leave the caller unable to ever get a clean response
        // for this repository (a retry would just 404, since the record is already gone). npm's
        // release still runs for the same repository even though maven's threw first.
        CodeArtifactRepository deleted = service.deleteRepository(REGION, "dom", null, "repo");

        assertEquals("repo", deleted.getName());
        verify(verdaccioClient).release(deleted.getSidecarContainerIds().get("npm"));
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * createRepository already guarantees this for container-backed sidecar content, by design
     * (a fresh sidecarContainerIds id on every creation, never derived from the repository's
     * name). packageOriginConfigs is keyed by name instead, so deleteRepository has to clean it
     * up explicitly, or a repository recreated under the same name would inherit a prior
     * package's restrictions, including a publish:BLOCK the new repository's owner never set and
     * has no way to discover.
     */
    @Test
    void deleteRepositoryRemovesOriginConfigsSoARecreatedRepositoryDoesNotInheritThem() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "BLOCK",
                "BLOCK");

        service.deleteRepository(REGION, "dom", null, "repo");
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException gone = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                "ns", "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);
        assertEquals("Published", published.packageVersion().getStatus());
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
        assertEquals("consumer", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryRejectsMoreThanOneThousandRepositoriesPerDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        for (int i = 0; i < 1000; i++) {
            service.createRepository(REGION, "dom", null, "repo-" + i, null, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "overflow", null, null, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals(402, e.getHttpStatus());
        assertEquals("overflow", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo-0", null, null, Map.of()));
        assertEquals("ConflictException", duplicate.getErrorCode());

        service.deleteRepository(REGION, "dom", null, "repo-0");
        CodeArtifactRepository freed = service.createRepository(REGION, "dom", null, "repo-new", null, null,
                Map.of());
        assertEquals("repo-new", freed.getName());
    }

    @Test
    void repositoryQuotaIsScopedPerDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        for (int i = 0; i < 1000; i++) {
            service.createRepository(REGION, "dom", null, "repo-" + i, null, null, Map.of());
        }
        service.createDomain(REGION, "dom-2", null, Map.of());
        CodeArtifactRepository r = service.createRepository(REGION, "dom-2", null, "repo-0", null, null, Map.of());
        assertEquals("repo-0", r.getName());
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

        String pypiEndpoint = service.getRepositoryEndpoint(REGION, "dom", null, "repo", "pypi", null);
        assertEquals("http://localhost:4566/codeartifact/pypi/dom/repo/", pypiEndpoint);

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
        assertEquals("repo", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));

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
        assertEquals("ghost", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createDomainRejectsMoreThanTwoHundredTags() {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < 201; i++) {
            tags.put("key-" + i, "value-" + i);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom", null, tags));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("dom", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void tagResourceRejectsMoreThanTwoHundredTagsOnRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository repo = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < 201; i++) {
            tags.put("key-" + i, "value-" + i);
        }
        AwsException e = assertThrows(AwsException.class, () -> service.tagResource(repo.getArn(), tags));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("repo", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void clearRemovesAllPersistedState() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        // An origin-config-only package (no versions, see putPackageOriginConfiguration) is its
        // own persisted record distinct from a domain, repository, or package version; clear()
        // missing it would leave describePackage still finding a package that should be gone.
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "never-published",
                "BLOCK", "BLOCK");
        service.clear();
        assertTrue(service.listDomains(REGION, null, null).items().isEmpty());
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());

        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        AwsException packageGone = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "ns", "never-published"));
        assertEquals("ResourceNotFoundException", packageGone.getErrorCode());
    }

    // ---------------------------------------------------- authorization tokens

    @Test
    void getAuthorizationTokenRequiresExistingDomain() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "missing-domain", null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getAuthorizationTokenDefaultsToTwelveHoursAndValidatesForItsDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        AuthorizationToken token = service.getAuthorizationToken(REGION, "dom", null, null);

        Optional<AuthorizationTokenScope> scope = service.resolveAuthorizationToken(token.token(), "dom");
        assertTrue(scope.isPresent());
        assertEquals(REGION, scope.get().region());
        assertEquals(ACCOUNT_ID, scope.get().owner());
        assertTrue(service.resolveAuthorizationToken(token.token(), "other-dom").isEmpty());
        assertTrue(service.resolveAuthorizationToken("not-a-real-token", "dom").isEmpty());
        assertTrue(service.resolveAuthorizationToken(null, "dom").isEmpty());
    }

    @Test
    void getAuthorizationTokenAcceptsZeroAndTheDocumentedRange() {
        service.createDomain(REGION, "dom", null, Map.of());

        service.getAuthorizationToken(REGION, "dom", null, 0L);
        service.getAuthorizationToken(REGION, "dom", null, 900L);
        service.getAuthorizationToken(REGION, "dom", null, 43200L);
    }

    @Test
    void getAuthorizationTokenRejectsDurationsOutsideTheDocumentedRange() {
        service.createDomain(REGION, "dom", null, Map.of());

        AwsException tooShort = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "dom", null, 899L));
        assertEquals("ValidationException", tooShort.getErrorCode());

        AwsException tooLong = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "dom", null, 43201L));
        assertEquals("ValidationException", tooLong.getErrorCode());
    }

    @Test
    void clearInvalidatesOutstandingAuthorizationTokens() {
        service.createDomain(REGION, "dom", null, Map.of());
        AuthorizationToken token = service.getAuthorizationToken(REGION, "dom", null, null);
        service.clear();
        assertTrue(service.resolveAuthorizationToken(token.token(), "dom").isEmpty());
    }

    // -------------------------------------------------------- package versions

    @Test
    void publishPackageVersionComputesRealHashesAndPublishesByDefault() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String sha256 = Digests.sha256Hex(content);

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                "my-ns", "my-pkg", "1.0.0", "asset.txt", sha256, "false", content);

        assertEquals("Published", result.packageVersion().getStatus());
        assertEquals("asset.txt", result.asset().getName());
        assertEquals(content.length, result.asset().getSize());
        assertEquals(sha256, result.asset().getHashes().get("SHA-256"));
        assertTrue(result.asset().getHashes().containsKey("MD5"));
        assertTrue(result.asset().getHashes().containsKey("SHA-1"));
        assertTrue(result.asset().getHashes().containsKey("SHA-512"));
    }

    @Test
    void publishPackageVersionRejectsMismatchedSha256() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", "0".repeat(64), "false", content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRequiresAssetSha256() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException missing = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", null, "false", content));
        assertEquals("ValidationException", missing.getErrorCode());

        AwsException malformed = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", "not-hex", "false", content));
        assertEquals("ValidationException", malformed.getErrorCode());
    }

    @Test
    void publishPackageVersionRejectsAssetNameWithControlCharacters() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String sha256 = Digests.sha256Hex(content);

        // A newline here would land verbatim in the X-AssetName response header on GetPackageVersionAsset.
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt\r\nX-Injected: true", sha256, "false",
                content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRejectsMalformedUnfinishedFlag() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", Digests.sha256Hex(content), "yes", content));
        assertEquals("ValidationException", e.getErrorCode());

        PublishPackageVersionResult omitted = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "asset.txt", Digests.sha256Hex(content), null, content);
        assertEquals("Published", omitted.packageVersion().getStatus());
    }

    @Test
    void publishPackageVersionRejectsNonGenericFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0", "asset.txt", Digests.sha256Hex(content), "false", content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRequiresExistingRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "no-such-repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", Digests.sha256Hex(content), "false", content));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void requiredPackageVersionFieldsRejectNullInsteadOfCrashing() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException publishMissingDomain = assertThrows(AwsException.class, () -> service.publishPackageVersion(
                REGION, null, null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", Digests.sha256Hex(content),
                "false", content));
        assertEquals("ValidationException", publishMissingDomain.getErrorCode());
        assertEquals("domain is required.", publishMissingDomain.getMessage());

        AwsException describeMissingRepository = assertThrows(AwsException.class,
                () -> service.describePackageVersion(REGION, "dom", null, null, "generic", null, "my-pkg", "1.0.0"));
        assertEquals("ValidationException", describeMissingRepository.getErrorCode());
        assertEquals("repository is required.", describeMissingRepository.getMessage());

        AwsException getAssetMissingVersion = assertThrows(AwsException.class,
                () -> service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic", null, "my-pkg", null,
                        "asset.txt", null));
        assertEquals("ValidationException", getAssetMissingVersion.getErrorCode());
        assertEquals("packageVersion is required.", getAssetMissingVersion.getMessage());
    }

    @Test
    void unfinishedPublishAllowsMultipleAssetsThenFinalizes() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);

        PublishPackageVersionResult r1 = service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(first), "true", first);
        assertEquals("Unfinished", r1.packageVersion().getStatus());

        PublishPackageVersionResult r2 = service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0", "b.txt", Digests.sha256Hex(second), "false", second);
        assertEquals("Published", r2.packageVersion().getStatus());
        assertEquals(2, r2.packageVersion().getAssets().size());
        assertTrue(r2.packageVersion().getAssets().containsKey("a.txt"));
        assertTrue(r2.packageVersion().getAssets().containsKey("b.txt"));
    }

    @Test
    void republishingAnUnfinishedAssetWithIdenticalContentSucceedsIdempotently() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "same-bytes".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult first = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);
        // pv is the same mutable object across calls in this storage backend, so the revision has
        // to be captured now, before the retry below has a chance to change it in place.
        String firstRevision = first.packageVersion().getRevision();

        PublishPackageVersionResult retry = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);

        assertEquals("Unfinished", retry.packageVersion().getStatus());
        assertEquals(1, retry.packageVersion().getAssets().size());
        assertEquals(firstRevision, retry.packageVersion().getRevision());
    }

    /**
     * A no-op-looking retry (same asset name, same content) must not be treated as a true no-op
     * when it also flips {@code unfinished} to {@code false}: that is a real status transition to
     * Published, and skipping it just because the one asset it touches happens to be unchanged
     * would leave the version stuck Unfinished forever.
     */
    @Test
    void finalizingWithTheSameAssetContentStillTransitionsToPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "same-bytes".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult unfinished = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "true", content);
        // pv is the same mutable object across calls in this storage backend, so this must be
        // checked before the second call mutates it, not read back afterward.
        assertEquals("Unfinished", unfinished.packageVersion().getStatus());

        PublishPackageVersionResult finalized = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);

        assertEquals("Published", finalized.packageVersion().getStatus());
        assertTrue(finalized.packageVersion().getPublishedTime() != null);
    }

    @Test
    void republishingAnUnfinishedAssetWithDifferentContentConflicts() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] first = "first-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] different = "different-bytes".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(first), "true", first);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(different), "true", different));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("a.txt", e.getExtendedData().get("resourceId"));
        assertEquals("asset", e.getExtendedData().get("resourceType"));
    }

    @Test
    void publishingToAnAlreadyPublishedVersionConflicts() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "b.txt", Digests.sha256Hex(content), "false", content));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("1.0.0", e.getExtendedData().get("resourceId"));
        assertEquals("package-version", e.getExtendedData().get("resourceType"));
    }

    @Test
    void republishingTheExactSameAssetToAnAlreadyPublishedVersionSucceedsIdempotently() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult first = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);
        // pv is the same mutable object across calls in this storage backend, so the revision has
        // to be captured now, before the retry below has a chance to change it in place.
        String firstRevision = first.packageVersion().getRevision();

        PublishPackageVersionResult retry = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);

        assertEquals("Published", retry.packageVersion().getStatus());
        assertEquals(firstRevision, retry.packageVersion().getRevision());
    }

    @Test
    void republishingAnAlreadyPublishedAssetWithDifferentContentConflicts() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        byte[] different = "y".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(different), "false", different));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("a.txt", e.getExtendedData().get("resourceId"));
        assertEquals("asset", e.getExtendedData().get("resourceType"));
    }

    @Test
    void publishPackageVersionCapsAssetsPerVersionAtThreeHundredFifty() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        for (int i = 0; i < 350; i++) {
            byte[] content = ("content-" + i).getBytes(StandardCharsets.UTF_8);
            service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0",
                    "asset-" + i + ".txt", Digests.sha256Hex(content), "true", content);
        }
        byte[] oneMore = "one-more".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset-350.txt", Digests.sha256Hex(oneMore), "true", oneMore));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("1.0.0", e.getExtendedData().get("resourceId"));
        assertEquals("package-version", e.getExtendedData().get("resourceType"));

        // Re-publishing an asset name already on the version must not itself trip the cap.
        byte[] resend = "content-0".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "asset-0.txt",
                Digests.sha256Hex(resend), "true", resend);
    }

    @Test
    void describePackageVersionRoundTripsAndReportsNotFound() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0");
        assertEquals("Published", pv.getStatus());
        assertEquals("ns", pv.getNamespace());

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", "2.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void describePackageVersionBridgesToReposiliteForMavenSinceNoGenericRecordCanExist() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created =
                service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageVersionExists(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("my-artifact"), eq("1.0.0"))).thenReturn(true);

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "maven",
                "com.example", "my-artifact", "1.0.0");

        assertEquals("maven", pv.getFormat());
        assertEquals("com.example", pv.getNamespace());
        assertEquals("my-artifact", pv.getPackageName());
        assertEquals("1.0.0", pv.getVersion());
        assertEquals("Published", pv.getStatus());
        assertEquals(Digests.sha256Hex((repoId + "/com.example/my-artifact/1.0.0").getBytes(StandardCharsets.UTF_8))
                .substring(0, 50), pv.getRevision());
    }

    @Test
    void describePackageVersionSynthesizesTheSameRevisionGetPackageVersionAssetWouldFor() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created =
                service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.packageVersionExists(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);
        when(verdaccioClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"), eq("my-pkg-1.0.0.tgz")))
                .thenReturn(Optional.of("tarball bytes".getBytes(StandardCharsets.UTF_8)));

        CodeArtifactPackageVersion described = service.describePackageVersion(REGION, "dom", null, "repo", "npm",
                null, "my-pkg", "1.0.0");
        PackageVersionAssetResult asset = service.getPackageVersionAsset(REGION, "dom", null, "repo", "npm", null,
                "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", null);

        assertEquals(described.getRevision(), asset.packageVersionRevision());
    }

    @Test
    void describePackageVersionBridgesToPypiserver() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.packageVersionExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "pypi", null,
                "my-pkg", "1.0.0");

        assertEquals("Published", pv.getStatus());
        assertEquals("my-pkg", pv.getPackageName());
    }

    @Test
    void describePackageVersionReturns404WhenTheSidecarHasNoSuchVersionForAContainerBackedFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.packageVersionExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("2.0.0"))).thenReturn(false);

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "2.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("2.0.0", e.getExtendedData().get("resourceId"));
        assertEquals("package-version", e.getExtendedData().get("resourceType"));
    }

    @Test
    void describePackageVersionRequiresNamespaceForMaven() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "maven", null, "my-artifact", "1.0.0"));
        assertEquals("ValidationException", e.getErrorCode());
        verify(reposiliteClient, never()).packageVersionExists(any(), any(), any(), any(), any(), any());
    }

    @Test
    void describePackageVersionRejectsANamespaceForPypi() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "pypi", "ns", "my-pkg", "1.0.0"));
        assertEquals("ValidationException", e.getErrorCode());
        verify(pypiserverClient, never()).packageVersionExists(any(), any(), any(), any(), any(), any());
    }

    @Test
    void describePackageVersionBridgesToTheNugetSidecar() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(bagetterClient.packageVersionExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "nuget", null,
                "my-pkg", "1.0.0");

        assertEquals("Published", pv.getStatus());
        assertEquals("my-pkg", pv.getPackageName());
    }

    @Test
    void describePackageVersionRejectsANamespaceForNuget() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "nuget", "ns", "my-pkg", "1.0.0"));
        assertEquals("ValidationException", e.getErrorCode());
        verify(bagetterClient, never()).packageVersionExists(any(), any(), any(), any(), any(), any());
    }

    @Test
    void describePackageReportsTheDirectPublishDefaultsAndMatchesExactly() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my::pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        CodeArtifactService.PackageDescription described = service.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg");
        assertEquals("generic", described.format());
        assertEquals("ns", described.namespace());
        assertEquals("my-pkg", described.packageName());
        assertEquals("ALLOW", described.publishRestriction());
        assertEquals("BLOCK", described.upstreamRestriction());

        AwsException prefixOfAnotherPackage = assertThrows(AwsException.class, () -> service.describePackage(REGION,
                "dom", null, "repo", "generic", "ns", "my"));
        assertEquals("ResourceNotFoundException", prefixOfAnotherPackage.getErrorCode());

        AwsException wrongNamespace = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "other", "my-pkg"));
        assertEquals("ResourceNotFoundException", wrongNamespace.getErrorCode());
    }

    @Test
    void describePackageValidatesPackageAndNamespaceShapeBeforeTouchingRepositoryState() {
        AwsException badPackageName = assertThrows(AwsException.class, () -> service.describePackage(REGION,
                "no-such-domain", null, "repo", "generic", "ns", "has/slash"));
        assertEquals("ValidationException", badPackageName.getErrorCode());

        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException badNamespace = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "has#hash", "my-pkg"));
        assertEquals("ValidationException", badNamespace.getErrorCode());

        AwsException missingNamespaceForGeneric = assertThrows(AwsException.class, () -> service.describePackage(
                REGION, "dom", null, "repo", "generic", null, "my-pkg"));
        assertEquals("ValidationException", missingNamespaceForGeneric.getErrorCode());

        AwsException missingNamespaceForMaven = assertThrows(AwsException.class, () -> service.describePackage(
                REGION, "dom", null, "repo", "maven", null, "my-artifact"));
        assertEquals("ValidationException", missingNamespaceForMaven.getErrorCode());
    }

    /**
     * {@code domain}/{@code repository}/{@code package} being required, and an unrecognized format
     * being rejected, are not new behavior (every other action in this file already enforces them),
     * but {@code describePackage} had no regression test proving any of them, and none of these
     * rejections may start a sidecar or otherwise touch state before failing.
     */
    @Test
    void describePackageRejectsMissingRequiredFieldsAndUnknownFormatsWithoutTouchingAnyState() {
        AwsException missingDomain = assertThrows(AwsException.class, () -> service.describePackage(REGION, null,
                null, "repo", "generic", "ns", "my-pkg"));
        assertEquals("ValidationException", missingDomain.getErrorCode());

        AwsException missingRepository = assertThrows(AwsException.class, () -> service.describePackage(REGION,
                "dom", null, null, "generic", "ns", "my-pkg"));
        assertEquals("ValidationException", missingRepository.getErrorCode());

        AwsException missingPackageName = assertThrows(AwsException.class, () -> service.describePackage(REGION,
                "dom", null, "repo", "generic", "ns", null));
        assertEquals("ValidationException", missingPackageName.getErrorCode());

        AwsException unknownFormat = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "not-a-real-format", "ns", "my-pkg"));
        assertEquals("ValidationException", unknownFormat.getErrorCode());

        verify(verdaccioClient, never()).packageExists(any(), any(), any(), any(), any());
        verify(reposiliteClient, never()).packageExists(any(), any(), any(), any(), any());
        verify(pypiserverClient, never()).packageExists(any(), any(), any(), any(), any());
    }

    /**
     * pypi packages have no namespace at all (confirmed against the API reference): the sidecar
     * check genuinely ignores whatever namespace is supplied, so without this rejection the response
     * would echo a namespace back as if it were real, describing a package that does not exist the
     * way the caller asked.
     */
    @Test
    void describePackageRejectsANamespaceSuppliedForPypiRatherThanEchoingAFabricatedOne() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("real-pkg")))
                .thenReturn(true);

        AwsException e = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom", null, "repo",
                "pypi", "bogus-namespace", "real-pkg"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    /**
     * The one package-level operation that works before the package exists at all: AWS documents
     * that calling it creates the package with no versions and applies the requested restrictions,
     * so a caller can lock a package down before anything is ever published to it.
     */
    @Test
    void putPackageOriginConfigurationCreatesAPackageWithNoVersionsAndDescribePackageReflectsIt() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException notYetFound = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "ns", "never-published"));
        assertEquals("ResourceNotFoundException", notYetFound.getErrorCode());

        CodeArtifactService.PackageDescription configured = service.putPackageOriginConfiguration(REGION, "dom",
                null, "repo", "generic", "ns", "never-published", "BLOCK", "BLOCK");
        assertEquals("BLOCK", configured.publishRestriction());
        assertEquals("BLOCK", configured.upstreamRestriction());

        CodeArtifactService.PackageDescription described = service.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "never-published");
        assertEquals("BLOCK", described.publishRestriction());
        assertEquals("BLOCK", described.upstreamRestriction());
    }

    /**
     * The same no-versions-needed existence proof applies uniformly to a container-backed format,
     * not just generic: the pypi sidecar never heard of this package (its mock answers false by
     * default, standing in for a real sidecar that was never asked to provision anything for it),
     * yet the stored origin configuration alone must still be enough for describePackage to find
     * the package rather than 404ing because the sidecar-exists check came back empty.
     */
    @Test
    void putPackageOriginConfigurationCreatesAContainerBackedPackageWithNoVersionsToo() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "pypi", null, "never-published", "BLOCK",
                "ALLOW");

        CodeArtifactService.PackageDescription described = service.describePackage(REGION, "dom", null, "repo",
                "pypi", null, "never-published");
        assertEquals("BLOCK", described.publishRestriction());
        assertEquals("ALLOW", described.upstreamRestriction());
        verify(pypiserverClient, never()).packageExists(any(), any(), any(), any(), any());
    }

    @Test
    void putPackageOriginConfigurationOverridesTheDirectPublishDefaultsOfAnExistingPackage() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "BLOCK",
                "ALLOW");

        CodeArtifactService.PackageDescription described = service.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg");
        assertEquals("BLOCK", described.publishRestriction());
        assertEquals("ALLOW", described.upstreamRestriction());
    }

    @Test
    void putPackageOriginConfigurationValidatesRestrictionsAndNamespaceShape() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException missingRestrictions = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", null, null));
        assertEquals("ValidationException", missingRestrictions.getErrorCode());

        AwsException missingUpstreamOnly = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "ALLOW",
                        null));
        assertEquals("ValidationException", missingUpstreamOnly.getErrorCode());

        AwsException badRestrictionValue = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg",
                        "ALLOW_SPECIFIC_REPOSITORIES", "BLOCK"));
        assertEquals("ValidationException", badRestrictionValue.getErrorCode());

        AwsException badUpstreamValue = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "ALLOW",
                        "ALLOW_SPECIFIC_REPOSITORIES"));
        assertEquals("ValidationException", badUpstreamValue.getErrorCode());

        AwsException missingNamespaceForGeneric = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", null, "my-pkg", "ALLOW",
                        "BLOCK"));
        assertEquals("ValidationException", missingNamespaceForGeneric.getErrorCode());

        AwsException namespaceForPypi = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "pypi", "bogus-namespace", "my-pkg",
                        "ALLOW", "BLOCK"));
        assertEquals("ValidationException", namespaceForPypi.getErrorCode());

        AwsException namespaceForNuget = assertThrows(AwsException.class, () -> service
                .putPackageOriginConfiguration(REGION, "dom", null, "repo", "nuget", "bogus-namespace", "my-pkg",
                        "ALLOW", "BLOCK"));
        assertEquals("ValidationException", namespaceForNuget.getErrorCode());

        // None of the rejections above may have created the package or changed anything about it:
        // every one of them must still 404 just like before any of these calls were attempted.
        AwsException stillAbsent = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg"));
        assertEquals("ResourceNotFoundException", stillAbsent.getErrorCode());
    }

    /**
     * Same rejections as above, but against a package that already carries a stored
     * configuration: a rejected update must leave that prior configuration exactly as it was,
     * not half-apply the new, invalid restrictions.
     */
    @Test
    void putPackageOriginConfigurationLeavesAnExistingConfigurationUnchangedOnRejection() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "ALLOW",
                "BLOCK");

        assertThrows(AwsException.class, () -> service.putPackageOriginConfiguration(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg", "ALLOW", "ALLOW_SPECIFIC_REPOSITORIES"));

        CodeArtifactService.PackageDescription unchanged = service.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg");
        assertEquals("ALLOW", unchanged.publishRestriction());
        assertEquals("BLOCK", unchanged.upstreamRestriction());
    }

    /**
     * Mirrors the AWS user guide's own worked example: once a package's origin configuration sets
     * Publish to Block, a direct PublishPackageVersion for that package must be rejected, even for
     * an otherwise brand-new version, and restoring Publish to Allow lifts the rejection again.
     */
    @Test
    void publishPackageVersionIsRejectedOnceOriginConfigurationBlocksPublish() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "BLOCK",
                "ALLOW");

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        AwsException blocked = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content));
        assertEquals("ConflictException", blocked.getErrorCode());

        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "ALLOW",
                "ALLOW");
        PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                "ns", "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);
        assertEquals("Published", published.packageVersion().getStatus());
    }

    /**
     * Namespace {@code "a::b"} + package {@code "c"} and namespace {@code "a"} + package
     * {@code "b::c"} are two different, independently valid package coordinates, {@code ::} is not
     * one of the characters namespace/package names reject, so a storage key that joined them with
     * a plain {@code ::} delimiter without escaping would see the two as the same package and let
     * one's origin configuration answer for the other's.
     */
    @Test
    void putPackageOriginConfigurationDoesNotCollideAcrossTheNamespacePackageBoundary() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "a::b", "c", "BLOCK", "ALLOW");
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "a", "b::c", "ALLOW", "BLOCK");

        CodeArtifactService.PackageDescription first = service.describePackage(REGION, "dom", null, "repo",
                "generic", "a::b", "c");
        CodeArtifactService.PackageDescription second = service.describePackage(REGION, "dom", null, "repo",
                "generic", "a", "b::c");
        assertEquals("BLOCK", first.publishRestriction());
        assertEquals("ALLOW", first.upstreamRestriction());
        assertEquals("ALLOW", second.publishRestriction());
        assertEquals("BLOCK", second.upstreamRestriction());
    }

    /**
     * Same coordinate-boundary collision as above, but for published version content rather than
     * origin configuration: without escaping, the second publish's bytes would be stored under the
     * same key as the first's, so one package's asset would overwrite or answer for the other's.
     */
    @Test
    void publishPackageVersionDoesNotCollideAcrossTheNamespacePackageBoundary() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] firstContent = "first-package".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second-package".getBytes(StandardCharsets.UTF_8);

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "a::b", "c", "1.0.0", "a.txt",
                Digests.sha256Hex(firstContent), "false", firstContent);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "a", "b::c", "1.0.0", "a.txt",
                Digests.sha256Hex(secondContent), "false", secondContent);

        PackageVersionAssetResult first = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                "a::b", "c", "1.0.0", "a.txt", null);
        PackageVersionAssetResult second = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                "a", "b::c", "1.0.0", "a.txt", null);
        assertEquals("first-package", new String(first.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals("second-package", new String(second.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * A build before escaping existed stored namespace/package segments raw, so one could already
     * contain a literal backslash followed by {@code c}, the exact two characters escaping a colon
     * now produces. If escaping used {@code \} as its own introducer, that old raw record would
     * become indistinguishable from the new escaped key of a completely different namespace
     * ("a:b" instead of "a\cb"). Escaping uses {@code #} instead, a character namespace/package
     * tokens can never contain (AWS's own wire pattern forbids it the same as Floci's), so the new
     * coordinate's escaped key can never equal that old raw one.
     *
     * <p>Asserting only the new coordinate's own read-back is not enough: that passes whether or
     * not the two keys actually collided, since the put and the read-back always agree with
     * whatever was last written under the key they both compute. The legacy record has to be read
     * back too, straight from the store under its own raw key, to prove the put never touched it.
     */
    @Test
    void newEscapedKeysNeverCollideWithAPreExistingRawKeyThatHappenedToContainABackslash() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String legacyRawKey = REGION + "::dom::repo::generic::a\\cb::x";
        packageOriginConfigStore.putForAccount(ACCOUNT_ID, legacyRawKey,
                new PackageOriginConfig("BLOCK", "BLOCK", null, null, null));

        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "a:b", "x", "ALLOW", "ALLOW");

        CodeArtifactService.PackageDescription described = service.describePackage(REGION, "dom", null, "repo",
                "generic", "a:b", "x");
        assertEquals("ALLOW", described.publishRestriction());
        assertEquals("ALLOW", described.upstreamRestriction());

        PackageOriginConfig legacy = packageOriginConfigStore.getForAccount(ACCOUNT_ID, legacyRawKey).orElseThrow();
        assertEquals("BLOCK", legacy.getPublishRestriction());
        assertEquals("BLOCK", legacy.getUpstreamRestriction());
    }

    /**
     * {@code #} is the escape introducer, so a namespace that already contains it reaches
     * {@link CodeArtifactService#escapeKeySegment} unchanged (there's no {@code :} or {@code \} in
     * it to escape) and can spell out another coordinate's already-escaped key directly: namespace
     * {@code "a#cb"} builds the identical key as the escaped form of namespace {@code "a:b"}.
     * {@code describePackage}/{@code putPackageOriginConfiguration} already reject {@code #} via
     * {@code validatePackageCoordinates}, but {@code describePackageVersion} and
     * {@code getPackageVersionAsset} used to only check for blank, reaching the shared key-building
     * code with an unvalidated namespace. Both now validate the same token rule before building a
     * key, so this is rejected rather than silently answering for a different package.
     */
    @Test
    void readPathsRejectANamespaceThatSpellsOutAnotherCoordinatesEscapedKey() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "victim".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "a:b", "x", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        AwsException describeVersion = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION,
                "dom", null, "repo", "generic", "a#cb", "x", "1.0.0"));
        assertEquals("ValidationException", describeVersion.getErrorCode());

        AwsException getAsset = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom",
                null, "repo", "generic", "a#cb", "x", "1.0.0", "a.txt", null));
        assertEquals("ValidationException", getAsset.getErrorCode());
    }

    @Test
    void deletePackageRemovesEveryVersionAndAssetButLeavesOtherPackagesAlone() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "2.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "other-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        CodeArtifactService.PackageDescription deleted = service.deletePackage(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg");
        assertEquals("generic", deleted.format());
        assertEquals("ns", deleted.namespace());
        assertEquals("my-pkg", deleted.packageName());

        AwsException goneAsPackage = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg"));
        assertEquals("ResourceNotFoundException", goneAsPackage.getErrorCode());
        AwsException goneVersionOne = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt", null));
        assertEquals("ResourceNotFoundException", goneVersionOne.getErrorCode());
        AwsException goneVersionTwo = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", "2.0.0", "a.txt", null));
        assertEquals("ResourceNotFoundException", goneVersionTwo.getErrorCode());

        CodeArtifactService.PackageDescription stillThere = service.describePackage(REGION, "dom", null, "repo",
                "generic", "ns", "other-pkg");
        assertEquals("other-pkg", stillThere.packageName());

        AwsException alreadyDeleted = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg"));
        assertEquals("ResourceNotFoundException", alreadyDeleted.getErrorCode());

        AwsException unknown = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom", null,
                "repo", "generic", "ns", "no-such-package"));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
    }

    /**
     * The real DeletePackage response's deletedPackage.originConfiguration reports whatever
     * restrictions were actually in effect, not CodeArtifact's bare ALLOW/BLOCK defaults.
     */
    @Test
    void deletePackageReportsTheOriginConfigurationThatWasActuallyInEffect() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "ALLOW",
                "ALLOW");

        CodeArtifactService.PackageDescription deleted = service.deletePackage(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg");
        assertEquals("ALLOW", deleted.publishRestriction());
        assertEquals("ALLOW", deleted.upstreamRestriction());
    }

    /**
     * DeletePackage removes the package entity, origin configuration included (AWS's own doc:
     * "Deletes a package and all associated package versions"). A leftover origin-config record
     * must not resurrect the deleted package as a zero-version one on the next DescribePackage.
     */
    @Test
    void deletePackageClearsTheOriginConfigurationSoDescribePackageStays404Afterward() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "BLOCK",
                "BLOCK");

        service.deletePackage(REGION, "dom", null, "repo", "generic", "ns", "my-pkg");

        AwsException gone = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * An origin-config-only package (no version ever published) is still a package
     * describePackage finds; deletePackage must agree rather than 404ing on the same coordinates
     * describePackage just reported as existing.
     */
    @Test
    void deletePackageRemovesAnOriginConfigOnlyPackageThatWasNeverPublishedTo() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "never-published",
                "BLOCK", "BLOCK");

        CodeArtifactService.PackageDescription deleted = service.deletePackage(REGION, "dom", null, "repo",
                "generic", "ns", "never-published");
        assertEquals("BLOCK", deleted.publishRestriction());
        assertEquals("BLOCK", deleted.upstreamRestriction());

        AwsException gone = assertThrows(AwsException.class, () -> service.describePackage(REGION, "dom", null,
                "repo", "generic", "ns", "never-published"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * Same case for a container-backed format: the sidecar still gets asked (unlike
     * describePackage's short-circuit, deletePackage cannot skip this: a package set up through
     * PutPackageOriginConfiguration could also have real content published to it directly through
     * the native protocol later, which deletePackage still has to find and remove), but it reports
     * nothing there, and the stored origin configuration alone must still let deletePackage
     * succeed rather than 404 on a package describePackage itself finds.
     */
    @Test
    void deletePackageRemovesAnOriginConfigOnlyContainerBackedPackageThatTheSidecarNeverHeardOf() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "pypi", null, "never-published", "ALLOW",
                "BLOCK");
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("never-published")))
                .thenReturn(false);

        CodeArtifactService.PackageDescription deleted = service.deletePackage(REGION, "dom", null, "repo", "pypi",
                null, "never-published");
        assertEquals("ALLOW", deleted.publishRestriction());
        verify(pypiserverClient, never()).deletePackage(any(), any(), any(), any(), any());
    }

    @Test
    void deletePackageBridgesToReposiliteForMavenAfterConfirmingItExists() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created =
                service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageExists(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("my-artifact"))).thenReturn(true);

        CodeArtifactService.PackageDescription deleted = service.deletePackage(REGION, "dom", null, "repo", "maven",
                "com.example", "my-artifact");

        assertEquals("maven", deleted.format());
        verify(reposiliteClient).deletePackage(repoId, "dom", "repo", "com.example", "my-artifact");
    }

    @Test
    void deletePackageReportsNotFoundForAContainerBackedPackageThatDoesNotExistRatherThanDeletingAnything() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(verdaccioClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(false);

        AwsException e = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom", null, "repo",
                "npm", null, "my-pkg"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        verify(verdaccioClient, never()).deletePackage(any(), any(), any(), any(), any());
    }

    /**
     * {@link RepositorySidecarManager#deletePackage}'s default throws
     * {@link UnsupportedOperationException} for a sidecar that genuinely cannot delete (every sidecar
     * Floci actually backs can, but the service must still handle one that can't, present or future);
     * this stubs that default's own throw straight onto the mock to exercise it without needing a
     * real sidecar that refuses. The service must not let it propagate as an unhandled 500: it
     * becomes a real, if deliberately blunt, documented AWS error instead.
     */
    @Test
    void deletePackageTranslatesAnUnsupportedSidecarDeleteIntoAnInternalServerException() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(true);
        doThrow(new UnsupportedOperationException("pypi packages cannot be deleted through this sidecar"))
                .when(pypiserverClient).deletePackage(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"));

        AwsException e = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom", null, "repo",
                "pypi", null, "my-pkg"));
        assertEquals("InternalServerException", e.getErrorCode());
    }

    /**
     * Mirrors describePackage's own boundary-validation test: deletePackage must reject the same
     * malformed coordinates before touching repository state, not just the single maven-namespace
     * check it had before this test existed.
     */
    @Test
    void deletePackageValidatesPackageAndNamespaceShapeBeforeTouchingRepositoryState() {
        AwsException badPackageName = assertThrows(AwsException.class, () -> service.deletePackage(REGION,
                "no-such-domain", null, "repo", "generic", "ns", "has/slash"));
        assertEquals("ValidationException", badPackageName.getErrorCode());

        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException badNamespace = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom",
                null, "repo", "generic", "has#hash", "my-pkg"));
        assertEquals("ValidationException", badNamespace.getErrorCode());

        AwsException missingNamespaceForGeneric = assertThrows(AwsException.class, () -> service.deletePackage(
                REGION, "dom", null, "repo", "generic", null, "my-pkg"));
        assertEquals("ValidationException", missingNamespaceForGeneric.getErrorCode());

        AwsException missingNamespaceForMaven = assertThrows(AwsException.class, () -> service.deletePackage(
                REGION, "dom", null, "repo", "maven", null, "my-artifact"));
        assertEquals("ValidationException", missingNamespaceForMaven.getErrorCode());
    }

    /**
     * pypi packages have no namespace at all (same API-reference fact describePackage's own test
     * documents). Without this rejection, a caller-supplied namespace would be silently ignored by
     * the sidecar's existence check below and the real package would be deleted anyway, as if the
     * namespace the caller specified had actually been honored.
     */
    @Test
    void deletePackageRejectsANamespaceSuppliedForPypiRatherThanDeletingTheRealPackageAnyway() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("real-pkg")))
                .thenReturn(true);

        AwsException e = assertThrows(AwsException.class, () -> service.deletePackage(REGION, "dom", null, "repo",
                "pypi", "bogus-namespace", "real-pkg"));
        assertEquals("ValidationException", e.getErrorCode());
        verify(pypiserverClient, never()).deletePackage(any(), any(), any(), any(), any());
    }

    // ----------------------------------------------------- copyPackageVersions

    private void publishGeneric(String repository, String packageName, String version, byte[] content) {
        service.publishPackageVersion(REGION, "dom", null, repository, "generic", "ns", packageName, version,
                "a.txt", Digests.sha256Hex(content), "false", content);
    }

    @Test
    void copyPackageVersionsCopiesEveryRequestedVersionAndTheyDescribeIdenticallyAtTheDestination() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        byte[] contentA = "version-a".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "version-b".getBytes(StandardCharsets.UTF_8);
        publishGeneric("src", "my-pkg", "1.0.0", contentA);
        publishGeneric("src", "my-pkg", "2.0.0", contentB);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0", "2.0.0"), null, false, false);

        assertEquals(2, result.successfulVersions().size());
        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0.0").status());
        assertTrue(result.successfulVersions().get("1.0.0").revision() != null
                && !result.successfulVersions().get("1.0.0").revision().isBlank());

        PackageVersionAssetResult copied = service.getPackageVersionAsset(REGION, "dom", null, "dst", "generic",
                "ns", "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("version-a", new String(copied.asset().getContent(), StandardCharsets.UTF_8));
        // The destination's revision is its own, never the source's: see copyPackageVersions's
        // own javadoc for why.
        CodeArtifactPackageVersion sourcePv = service.describePackageVersion(REGION, "dom", null, "src", "generic",
                "ns", "my-pkg", "1.0.0");
        assertTrue(!sourcePv.getRevision().equals(result.successfulVersions().get("1.0.0").revision()));
    }

    @Test
    void copyPackageVersionsRejectsBothVersionsAndVersionRevisionsTogether() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), Map.of("1.0.0", "rev"), false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRejectsNeitherVersionsNorVersionRevisions() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of(), Map.of(), false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRejectsAFormatThatIsNeitherGenericNorContainerBacked() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "swift", null, "my-pkg", List.of("1.0.0"), null, false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    /**
     * npm, pypi, and nuget are now bridged the same as maven (each sidecar's own real wire
     * protocol, confirmed against a live container in the respective {@code
     * CodeArtifact*DockerIntegrationTest} class); the shared orchestration this test file already
     * exercises thoroughly against the maven mock (NOT_FOUND, MISMATCHED_REVISION, the
     * identical-content no-op, ALREADY_EXISTS, overwrite, rollback, byte accounting) is format-
     * agnostic, so it is not re-duplicated here per format. This just confirms the format gate
     * itself routes a container-backed format to the bridge rather than rejecting it outright.
     */
    @Test
    void copyPackageVersionsRoutesEveryContainerBackedFormatToTheSidecarBridge() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        for (String format : List.of("npm", "pypi", "nuget")) {
            CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                    "src", "dst", format, null, "my-pkg", List.of("1.0.0"), null, false, false);
            assertEquals("NOT_FOUND", result.failedVersions().get("1.0.0").errorCode());
        }
    }

    /**
     * The real-world gap this closes: {@code GetPackageVersionReadme} answers npm and pypi only
     * from a record captured at publish time (see its own javadoc for why neither sidecar can
     * answer for a specific version after the fact), which this bridge's own asset copy has no
     * other way to reach. Without propagating it, a copied npm version's assets would land
     * correctly while {@code GetPackageVersionReadme} 404s on it at the destination, even though
     * the source genuinely has one.
     */
    @Test
    void copyPackageVersionsPropagatesTheSourceVersionsCapturedReadmeForNpm() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("npm");
        String dstRepoId = dst.getSidecarContainerIds().get("npm");
        byte[] tarball = "tarball-bytes".getBytes(StandardCharsets.UTF_8);
        when(verdaccioClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);
        when(verdaccioClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(false);
        when(verdaccioClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));
        when(verdaccioClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), isNull(), eq("my-pkg"),
                eq("1.0.0"), eq("my-pkg-1.0.0.tgz"))).thenReturn(Optional.of(tarball));
        recordReadme("dom", "src", "npm", null, "my-pkg", "1.0.0", "# Real Readme");

        service.copyPackageVersions(REGION, "dom", null, "src", "dst", "npm", null, "my-pkg", List.of("1.0.0"),
                null, false, false);

        CodeArtifactService.PackageVersionReadmeResult readme = service.getPackageVersionReadme(REGION, "dom",
                null, "dst", "npm", null, "my-pkg", "1.0.0");
        assertEquals("# Real Readme", readme.readme());
    }

    /** Same propagation as npm's own, confirmed separately since pypi captures readmes independently. */
    @Test
    void copyPackageVersionsPropagatesTheSourceVersionsCapturedReadmeForPypi() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("pypi");
        String dstRepoId = dst.getSidecarContainerIds().get("pypi");
        byte[] sdist = "sdist-bytes".getBytes(StandardCharsets.UTF_8);
        when(pypiserverClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);
        when(pypiserverClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(false);
        when(pypiserverClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my_pkg-1.0.0.tar.gz"));
        when(pypiserverClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), isNull(), eq("my-pkg"),
                eq("1.0.0"), eq("my_pkg-1.0.0.tar.gz"))).thenReturn(Optional.of(sdist));
        recordReadme("dom", "src", "pypi", null, "my-pkg", "1.0.0", "A pypi description");

        service.copyPackageVersions(REGION, "dom", null, "src", "dst", "pypi", null, "my-pkg", List.of("1.0.0"),
                null, false, false);

        CodeArtifactService.PackageVersionReadmeResult readme = service.getPackageVersionReadme(REGION, "dom",
                null, "dst", "pypi", null, "my-pkg", "1.0.0");
        assertEquals("A pypi description", readme.readme());
    }

    @Test
    void copyPackageVersionsBridgesToReposiliteForMavenCopyingEveryListedAsset() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "pom-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(false);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(pomBytes));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, false, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0").status());
        assertFalse(result.successfulVersions().get("1.0").revision().isBlank());
        verify(reposiliteClient).copyPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId),
                eq("dst"), eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"), eq(jarBytes));
        verify(reposiliteClient).copyPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId),
                eq("dst"), eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.pom"), eq(pomBytes));
        verify(reposiliteClient, never()).deletePackageVersionAsset(any(), any(), any(), any(), any(), any(),
                any());
    }

    /**
     * Raised by Greptile's review: a Maven dispose failing partway through its own asset-deletion
     * loop leaves the source override reading {@code Disposed} while real files genuinely still
     * remain in the version's own directory, so {@code packageVersionExists} alone cannot tell a
     * healthy source apart from a partly-disposed one. With {@code allowOverwrite=true}, copying
     * that partly-disposed source over a healthy destination must still be refused, not replace
     * the destination's real content with the incomplete release and report {@code Published}.
     */
    @Test
    void copyPackageVersionsFailsNotAllowedWhenTheMavenSourceIsPartlyDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(reposiliteClient).deletePackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"),
                        eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.pom"));
        assertThrows(IllegalStateException.class, () -> service.disposePackageVersions(REGION, "dom", null, "src",
                "maven", "com.example", "a", List.of("1.0"), null, null));
        // The pom's delete never actually succeeded, so it is still genuinely there.
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, true, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_ALLOWED", result.failedVersions().get("1.0").errorCode());
        verify(reposiliteClient, never()).copyPackageVersionAsset(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void copyPackageVersionsFailsNotFoundForAMavenVersionMissingFromTheSource() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, false, false);

        assertEquals("NOT_FOUND", result.failedVersions().get("1.0").errorCode());
        assertTrue(result.successfulVersions().isEmpty());
    }

    @Test
    void copyPackageVersionsSkipsAMavenVersionThatWouldHaveSucceededWhenASiblingIsNotFound() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("2.0"))).thenReturn(false);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0", "2.0"), null, false, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("SKIPPED", result.failedVersions().get("1.0").errorCode());
        assertEquals("NOT_FOUND", result.failedVersions().get("2.0").errorCode());
        verify(reposiliteClient, never()).copyPackageVersionAsset(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void copyPackageVersionsTreatsAByteIdenticalMavenVersionAsANoOpNotListedEitherWay() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes.clone()));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, false, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertTrue(result.failedVersions().isEmpty());
        verify(reposiliteClient, never()).copyPackageVersionAsset(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void copyPackageVersionsFailsAlreadyExistsForADifferentMavenVersionWithoutAllowOverwrite() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar")))
                .thenReturn(Optional.of("source-bytes".getBytes(StandardCharsets.UTF_8)));
        when(reposiliteClient.fetchPackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar")))
                .thenReturn(Optional.of("different-bytes".getBytes(StandardCharsets.UTF_8)));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, false, false);

        assertEquals("ALREADY_EXISTS", result.failedVersions().get("1.0").errorCode());
    }

    @Test
    void copyPackageVersionsOverwritesADifferentMavenVersionAndRemovesAStaleAssetWhenAllowOverwriteIsTrue() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] newJarBytes = "new-jar-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "stale-readme.txt"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(newJarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar")))
                .thenReturn(Optional.of("old-jar-bytes".getBytes(StandardCharsets.UTF_8)));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, true, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0").status());
        // a-1.0.jar already exists at the destination with different content: Reposilite's own
        // redeployment:false setting would reject a plain PUT over it (confirmed against a live
        // container), so the old one must be deleted immediately before the new one is stored,
        // never written straight over the existing path.
        InOrder order = inOrder(reposiliteClient);
        order.verify(reposiliteClient).deletePackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"));
        order.verify(reposiliteClient).copyPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId),
                eq("dst"), eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"), eq(newJarBytes));
        verify(reposiliteClient).deletePackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("stale-readme.txt"));
    }

    /**
     * A later asset in the same version failing to write must not leave an earlier, otherwise
     * successful asset of that same version sitting in a brand-new, half-copied state at the
     * destination: the jar never existed there before this call, so the best-effort rollback
     * deletes it outright rather than attempting to restore nonexistent prior content. The
     * original {@code IllegalStateException} from the sidecar propagates unchanged, not masked
     * by a rollback-time error.
     */
    @Test
    void copyPackageVersionsForMavenRollsBackANewlyWrittenAssetWhenALaterAssetInTheSameVersionFailsToWrite() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "pom-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(false);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(pomBytes));
        doThrow(new IllegalStateException("sidecar unreachable")).when(reposiliteClient).copyPackageVersionAsset(
                eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId), eq("dst"), eq("com.example"), eq("a"), eq("1.0"),
                eq("a-1.0.pom"), eq(pomBytes));

        assertThrows(IllegalStateException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, false, false));

        verify(reposiliteClient).copyPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId),
                eq("dst"), eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"), eq(jarBytes));
        verify(reposiliteClient).deletePackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"));
    }

    /**
     * An overwritten asset that already existed at the destination must come back as it was, not
     * get deleted outright, when a later asset in the same version fails: rollback distinguishes
     * "newly created, so delete it" from "previously existed, so restore it" per asset.
     */
    @Test
    void copyPackageVersionsForMavenRestoresAnOverwrittenAssetsOriginalContentWhenALaterAssetFailsToWrite() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] newJarBytes = "new-jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] oldJarBytes = "old-jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] newPomBytes = "new-pom-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(newJarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(newPomBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(oldJarBytes));
        doThrow(new IllegalStateException("sidecar unreachable")).when(reposiliteClient).copyPackageVersionAsset(
                eq(srcRepoId), eq("dom"), eq("src"), eq(dstRepoId), eq("dst"), eq("com.example"), eq("a"), eq("1.0"),
                eq("a-1.0.pom"), eq(newPomBytes));

        assertThrows(IllegalStateException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, true, false));

        verify(reposiliteClient).storePackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"), eq(oldJarBytes));
    }

    @Test
    void copyPackageVersionsForMavenRecordsTheNewVersionInTheDestinationsArtifactMetadata() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(false);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));

        service.copyPackageVersions(REGION, "dom", null, "src", "dst", "maven", "com.example", "a",
                List.of("1.0"), null, false, false);

        verify(reposiliteClient).recordVersionAvailable(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"));
    }

    @Test
    void copyPackageVersionsForMavenIncreasesDescribeDomainsAssetSizeBytesByWhatWasCopied() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "pom-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(false);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(pomBytes));

        long before = service.describeDomain(REGION, "dom", null).assetSizeBytes();
        service.copyPackageVersions(REGION, "dom", null, "src", "dst", "maven", "com.example", "a",
                List.of("1.0"), null, false, false);
        long after = service.describeDomain(REGION, "dom", null).assetSizeBytes();

        assertEquals(before + jarBytes.length + pomBytes.length, after);
    }

    @Test
    void copyPackageVersionsFailsMismatchedRevisionForMavenWhenTheVersionRevisionsEntryDoesNotMatch() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", null, Map.of("1.0", "wrong-revision"), false, false);

        assertEquals("MISMATCHED_REVISION", result.failedVersions().get("1.0").errorCode());
    }

    @Test
    void copyPackageVersionsFailsMismatchedRevisionForMavenOnAShapeValidButWrongValue() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", null, Map.of("1.0", "a-shape-valid-revision"), false,
                false);

        assertEquals("MISMATCHED_REVISION", result.failedVersions().get("1.0").errorCode());
    }

    /**
     * {@link CodeArtifactService#synthesizeContainerBackedRevision} is truncated to 50 hex
     * characters specifically so a revision a caller obtained from {@code DescribePackageVersion}
     * can be handed straight back into {@code versionRevisions} and actually match: confirms a
     * real round trip through both APIs, not just that the two happen to be the same length.
     */
    @Test
    void copyPackageVersionsSucceedsForMavenWhenTheVersionRevisionsEntryIsTheOneDescribePackageVersionReturned() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(false);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));

        String describedRevision = service.describePackageVersion(REGION, "dom", null, "src", "maven",
                "com.example", "a", "1.0").getRevision();
        assertTrue(describedRevision.length() <= 50);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", null, Map.of("1.0", describedRevision), false, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0").status());
    }

    @Test
    void copyPackageVersionsFailsNotFoundForAVersionMissingFromTheSourceRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("9.9.9"), null, false, false);
        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_FOUND", result.failedVersions().get("9.9.9").errorCode());
    }

    /**
     * AWS's own documented example (array-unique 0.2.0/5.6.7): one requested version missing from
     * the source repository fails the whole call, and every other requested version that would
     * otherwise have copied cleanly is reported SKIPPED instead of actually being copied.
     */
    @Test
    void copyPackageVersionsSkipsAVersionThatWouldHaveSucceededWhenASiblingVersionIsNotFound() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0", "9.9.9"), null, false, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_FOUND", result.failedVersions().get("9.9.9").errorCode());
        assertEquals("SKIPPED", result.failedVersions().get("1.0.0").errorCode());
        AwsException notCopied = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION,
                "dom", null, "dst", "generic", "ns", "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", notCopied.getErrorCode());
    }

    /**
     * AWS's own documented example (array-unique 0.2.1 already identical in both repositories):
     * a version that is already byte- and metadata-identical at the destination is a successful
     * no-op, not copied again and not listed in either response map.
     */
    @Test
    void copyPackageVersionsTreatsAByteIdenticalAlreadyPresentVersionAsANoOpNotListedEitherWay() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        byte[] content = "same-bytes".getBytes(StandardCharsets.UTF_8);
        publishGeneric("src", "my-pkg", "1.0.0", content);
        publishGeneric("dst", "my-pkg", "1.0.0", content);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertTrue(result.failedVersions().isEmpty());
    }

    /**
     * AWS's own documented example (array-unique 0.3.2 present in both but different content):
     * fails ALREADY_EXISTS without allowOverwrite.
     */
    @Test
    void copyPackageVersionsFailsAlreadyExistsForADifferentVersionAlreadyAtTheDestinationWithoutAllowOverwrite() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "source-bytes".getBytes(StandardCharsets.UTF_8));
        publishGeneric("dst", "my-pkg", "1.0.0", "different-bytes".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("ALREADY_EXISTS", result.failedVersions().get("1.0.0").errorCode());
    }

    /**
     * Raised by hectorvent's review of this PR, matching copyContainerBackedPackageVersions's own
     * Disposed-destination guard: disposePackageVersions empties a generic version's asset map but
     * keeps its record (so DescribePackageVersion keeps answering), so without a dedicated check an
     * allowOverwrite copy would have nothing to compare against, fall through as though the
     * destination were simply a different version, and resurrect a coordinate the User Guide says
     * "can no longer be used."
     */
    @Test
    void copyPackageVersionsFailsNotAllowedWhenTheDestinationIsAlreadyDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "source-bytes".getBytes(StandardCharsets.UTF_8));
        publishGeneric("dst", "my-pkg", "1.0.0", "different-bytes".getBytes(StandardCharsets.UTF_8));
        service.disposePackageVersions(REGION, "dom", null, "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null,
                null);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, true, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_ALLOWED", result.failedVersions().get("1.0.0").errorCode());
    }

    /**
     * Raised by Greptile's review: {@code disposePackageVersions} empties a generic version's
     * asset map but keeps its record around, the same reason the destination-side guard above
     * exists. Without a matching guard on the source, copying a disposed source with
     * {@code allowOverwrite=true} over a healthy destination would overwrite that destination's
     * real content and status with the disposed source's empty one while reporting success,
     * destroying assets the disposal never touched.
     */
    @Test
    void copyPackageVersionsFailsNotAllowedWhenTheSourceIsAlreadyDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "source-bytes".getBytes(StandardCharsets.UTF_8));
        publishGeneric("dst", "my-pkg", "1.0.0", "healthy-bytes".getBytes(StandardCharsets.UTF_8));
        service.disposePackageVersions(REGION, "dom", null, "src", "generic", "ns", "my-pkg", List.of("1.0.0"), null,
                null);

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, true, false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_ALLOWED", result.failedVersions().get("1.0.0").errorCode());
        PackageVersionAssetResult stillHealthy = service.getPackageVersionAsset(REGION, "dom", null, "dst",
                "generic", "ns", "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("healthy-bytes", new String(stillHealthy.asset().getContent(), StandardCharsets.UTF_8));
    }

    @Test
    void copyPackageVersionsOverwritesADifferentExistingVersionWhenAllowOverwriteIsTrue() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "source-bytes".getBytes(StandardCharsets.UTF_8));
        publishGeneric("dst", "my-pkg", "1.0.0", "different-bytes".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, true, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0.0").status());
        PackageVersionAssetResult overwritten = service.getPackageVersionAsset(REGION, "dom", null, "dst", "generic",
                "ns", "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("source-bytes", new String(overwritten.asset().getContent(), StandardCharsets.UTF_8));
    }

    /**
     * An allowOverwrite copy makes the destination's asset set exactly the source's: an asset the
     * old destination version had that the source version does not is removed, not left behind
     * unreferenced.
     */
    @Test
    void copyPackageVersionsOverwriteRemovesAnAssetTheDestinationHadThatTheSourceDoesNot() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        byte[] sourceContent = "source-bytes".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "src", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(sourceContent), "false", sourceContent);
        byte[] destContent = "dest-only".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "dst", "generic", "ns", "my-pkg", "1.0.0", "b.txt",
                Digests.sha256Hex(destContent), "false", destContent);

        service.copyPackageVersions(REGION, "dom", null, "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"),
                null, true, false);

        AwsException goneAsset = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "dst", "generic", "ns", "my-pkg", "1.0.0", "b.txt", null));
        assertEquals("ResourceNotFoundException", goneAsset.getErrorCode());
        PackageVersionAssetResult kept = service.getPackageVersionAsset(REGION, "dom", null, "dst", "generic", "ns",
                "my-pkg", "1.0.0", "a.txt", null);
        assertEquals("source-bytes", new String(kept.asset().getContent(), StandardCharsets.UTF_8));
    }

    @Test
    void copyPackageVersionsFailsMismatchedRevisionWhenTheVersionRevisionsEntryDoesNotMatchTheSource() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", null, Map.of("1.0.0", "not-the-real-revision"), false,
                false);

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("MISMATCHED_REVISION", result.failedVersions().get("1.0.0").errorCode());
    }

    @Test
    void copyPackageVersionsSucceedsWhenTheVersionRevisionsEntryMatchesTheSource() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom", null, "src", "generic",
                "ns", "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex("x".getBytes(StandardCharsets.UTF_8)), "false",
                "x".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", null,
                Map.of("1.0.0", published.packageVersion().getRevision()), false, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0.0").status());
    }

    @Test
    void copyPackageVersionsRequiresNamespaceForGeneric() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", null, "my-pkg", List.of("1.0.0"), null, false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRequiresTheSourceRepositoryToExist() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "no-such-repo", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRequiresTheDestinationRepositoryToExist() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "no-such-repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRejectsMoreThanOneHundredRequestedVersions() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add("1.0." + i);
        }

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", tooMany, null, false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    /**
     * The 100-member cap AWS documents is on the request's own array, not on how many distinct
     * versions remain after collapsing repeats: 101 entries that happen to repeat the same two
     * versions must still be rejected, not quietly accepted because deduplication made the count
     * look small.
     */
    @Test
    void copyPackageVersionsRejectsMoreThanOneHundredRequestedVersionsEvenWhenMostAreDuplicates() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        List<String> tooManyWithDuplicates = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooManyWithDuplicates.add(i % 2 == 0 ? "1.0.0" : "2.0.0");
        }

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", tooManyWithDuplicates, null, false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRejectsAVersionRevisionsValueContainingWhitespace() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", null, Map.of("1.0.0", "rev with space"), false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void copyPackageVersionsRejectsAVersionRevisionsValueOverFiftyCharacters() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", null, Map.of("1.0.0", "r".repeat(51)), false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    /**
     * A caller-configured publish:BLOCK on the destination package does NOT gate
     * CopyPackageVersions: the User Guide's own definition of the "Publish" origin control scopes
     * it to direct publish "using package managers or similar tools," a mechanism Copy is not,
     * and nothing in the API reference or the "Editing package origin controls" chapter ties the
     * two together. A regression guard against re-adding that check without real evidence for it.
     */
    @Test
    void copyPackageVersionsIgnoresADestinationBlockOnDirectPublishSinceCopyIsNotDirectPublish() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));
        service.putPackageOriginConfiguration(REGION, "dom", null, "dst", "generic", "ns", "my-pkg", "BLOCK",
                "ALLOW");

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false);
        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0.0").status());
    }

    @Test
    void copyPackageVersionsAllowsTheCallWhenTheDestinationPackageHasNoBlockingOriginConfiguration() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));
        service.putPackageOriginConfiguration(REGION, "dom", null, "dst", "generic", "ns", "my-pkg", "ALLOW",
                "ALLOW");

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0"), null, false, false);
        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0.0").status());
    }

    @Test
    void copyPackageVersionsRejectsABlankVersionRevisionsValueAsAValidationErrorRatherThanAMismatch() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", null, Map.of("1.0.0", " "), false, false));
        assertEquals("ValidationException", e.getErrorCode());
    }

    /**
     * A caller-supplied duplicate requested version must be copied (and counted) exactly once,
     * not reported or processed as though two separate versions were requested.
     */
    @Test
    void copyPackageVersionsDeduplicatesARepeatedRequestedVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        publishGeneric("src", "my-pkg", "1.0.0", "x".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "generic", "ns", "my-pkg", List.of("1.0.0", "1.0.0"), null, false, false);

        assertEquals(1, result.successfulVersions().size());
        assertTrue(result.failedVersions().isEmpty());
    }

    @Test
    void getPackageVersionAssetReturnsExactBytesAndValidatesRevision() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false", content);

        PackageVersionAssetResult result = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals(content.length, result.asset().getContent().length);
        assertEquals("hello world", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(published.packageVersion().getRevision(), result.packageVersionRevision());

        AwsException wrongAsset = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "missing.txt", null));
        assertEquals("ResourceNotFoundException", wrongAsset.getErrorCode());
        assertEquals("missing.txt", wrongAsset.getExtendedData().get("resourceId"));
        assertEquals("asset", wrongAsset.getExtendedData().get("resourceType"));

        AwsException wrongRevision = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", "not-the-current-revision"));
        assertEquals("ResourceNotFoundException", wrongRevision.getErrorCode());
        assertEquals("1.0.0", wrongRevision.getExtendedData().get("resourceId"));
        assertEquals("package-version", wrongRevision.getExtendedData().get("resourceType"));
    }

    @Test
    void getPackageVersionAssetBridgesToReposiliteForMavenSinceNoGenericRecordCanExist() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created =
                service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        byte[] content = "jar bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("my-artifact"), eq("1.0.0"), eq("my-artifact-1.0.0.jar"))).thenReturn(Optional.of(content));

        PackageVersionAssetResult result = service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "my-artifact", "1.0.0", "my-artifact-1.0.0.jar", null);

        assertEquals("jar bytes", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(Digests.sha256Hex((repoId + "/com.example/my-artifact/1.0.0").getBytes(StandardCharsets.UTF_8))
                .substring(0, 50), result.packageVersionRevision());
    }

    @Test
    void getPackageVersionAssetSynthesizesADifferentRevisionForTheSameCoordinatesInADifferentRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo-b", null, null, Map.of());
        byte[] content = "jar bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.fetchPackageVersionAsset(anyString(), eq("dom"), anyString(), eq("com.example"),
                eq("my-artifact"), eq("1.0.0"), eq("my-artifact-1.0.0.jar"))).thenReturn(Optional.of(content));

        PackageVersionAssetResult fromA = service.getPackageVersionAsset(REGION, "dom", null, "repo-a", "maven",
                "com.example", "my-artifact", "1.0.0", "my-artifact-1.0.0.jar", null);
        PackageVersionAssetResult fromB = service.getPackageVersionAsset(REGION, "dom", null, "repo-b", "maven",
                "com.example", "my-artifact", "1.0.0", "my-artifact-1.0.0.jar", null);

        assertTrue(!fromA.packageVersionRevision().equals(fromB.packageVersionRevision()),
                "identical coordinates in two different repositories must not synthesize the same revision");
    }

    @Test
    void getPackageVersionAssetSynthesizesTheSameRevisionForEveryAssetOfOneMavenVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(reposiliteClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), eq("com.example"),
                eq("my-artifact"), eq("1.0.0"), eq("my-artifact-1.0.0.jar")))
                .thenReturn(Optional.of("jar bytes".getBytes(StandardCharsets.UTF_8)));
        when(reposiliteClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), eq("com.example"),
                eq("my-artifact"), eq("1.0.0"), eq("my-artifact-1.0.0.pom")))
                .thenReturn(Optional.of("pom bytes".getBytes(StandardCharsets.UTF_8)));

        PackageVersionAssetResult jar = service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "my-artifact", "1.0.0", "my-artifact-1.0.0.jar", null);
        String jarRevision = jar.packageVersionRevision();

        // A client that described the version once and now fetches a second asset pinned to that
        // same revision must not get a spurious not-found just because this asset's own bytes
        // differ from the first one's.
        PackageVersionAssetResult pom = service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "my-artifact", "1.0.0", "my-artifact-1.0.0.pom", jarRevision);

        assertEquals(jarRevision, pom.packageVersionRevision());
    }

    @Test
    void getPackageVersionAssetRequiresNamespaceForMaven() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null,
                "repo", "maven", null, "my-artifact", "1.0.0", "my-artifact-1.0.0.jar", null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void getPackageVersionAssetBridgesToVerdaccioForNpm() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        when(verdaccioClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), eq("myscope"),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"))).thenReturn(Optional.of(content));

        PackageVersionAssetResult result = service.getPackageVersionAsset(REGION, "dom", null, "repo", "npm",
                "myscope", "my-pkg", "1.0.0", "my-pkg-1.0.0.tgz", null);

        assertEquals("tarball bytes", new String(result.asset().getContent(), StandardCharsets.UTF_8));
    }

    @Test
    void getPackageVersionAssetBridgesToPypiserver() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "wheel bytes".getBytes(StandardCharsets.UTF_8);
        when(pypiserverClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my_pkg-1.0.0-py3-none-any.whl"))).thenReturn(Optional.of(content));

        PackageVersionAssetResult result = service.getPackageVersionAsset(REGION, "dom", null, "repo", "pypi",
                null, "my-pkg", "1.0.0", "my_pkg-1.0.0-py3-none-any.whl", null);

        assertEquals("wheel bytes", new String(result.asset().getContent(), StandardCharsets.UTF_8));
    }

    @Test
    void getPackageVersionAssetReturns404WhenTheSidecarHasNoSuchAssetForAContainerBackedFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("missing.whl"))).thenReturn(Optional.empty());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "1.0.0", "missing.whl", null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("missing.whl", e.getExtendedData().get("resourceId"));
        assertEquals("asset", e.getExtendedData().get("resourceType"));
    }

    @Test
    void getPackageVersionAssetValidatesTheCallerProvidedRevisionForAContainerBackedFormatToo() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "wheel bytes".getBytes(StandardCharsets.UTF_8);
        when(pypiserverClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my_pkg-1.0.0.whl"))).thenReturn(Optional.of(content));
        String realRevision = service.getPackageVersionAsset(REGION, "dom", null, "repo", "pypi", null, "my-pkg",
                "1.0.0", "my_pkg-1.0.0.whl", null).packageVersionRevision();

        PackageVersionAssetResult matching = service.getPackageVersionAsset(REGION, "dom", null, "repo", "pypi",
                null, "my-pkg", "1.0.0", "my_pkg-1.0.0.whl", realRevision);
        assertEquals("wheel bytes", new String(matching.asset().getContent(), StandardCharsets.UTF_8));

        AwsException wrongRevision = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "pypi", null, "my-pkg", "1.0.0", "my_pkg-1.0.0.whl", "not-the-real-revision"));
        assertEquals("ResourceNotFoundException", wrongRevision.getErrorCode());
        assertEquals("package-version", wrongRevision.getExtendedData().get("resourceType"));
    }

    // ------------------------------------------------------ ListPackageVersionAssets

    @Test
    void listPackageVersionAssetsReturnsEveryAssetForAGenericVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "generic-asset-bytes".getBytes(StandardCharsets.UTF_8);
        publishGeneric("repo", "my-pkg", "1.0.0", content);

        CodeArtifactService.PackageVersionAssetsListResult result = service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", null, null);

        assertEquals(1, result.assets().size());
        assertEquals("a.txt", result.assets().get(0).name());
        assertEquals(content.length, result.assets().get(0).size());
        assertEquals(Digests.sha256Hex(content), result.assets().get(0).hashes().get("SHA-256"));
        assertTrue(result.versionRevision() != null && !result.versionRevision().isBlank());
    }

    @Test
    void listPackageVersionAssetsFailsNotFoundForAGenericVersionThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listPackageVersionAssetsPaginatesGenericAssets() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex("a".getBytes(StandardCharsets.UTF_8)), "true",
                "a".getBytes(StandardCharsets.UTF_8));
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "b.txt",
                Digests.sha256Hex("b".getBytes(StandardCharsets.UTF_8)), "true",
                "b".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.PackageVersionAssetsListResult page1 = service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", 1, null);
        assertEquals(1, page1.assets().size());
        assertTrue(page1.nextToken() != null);

        CodeArtifactService.PackageVersionAssetsListResult page2 = service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", 1, page1.nextToken());
        assertEquals(1, page2.assets().size());
        assertTrue(page2.nextToken() == null);
        assertTrue(!page1.assets().get(0).name().equals(page2.assets().get(0).name()));
    }

    @Test
    void listPackageVersionAssetsBridgesToReposiliteForMaven() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "pom-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(jarBytes));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(pomBytes));

        CodeArtifactService.PackageVersionAssetsListResult result = service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "maven", "com.example", "a", "1.0", null, null);

        assertEquals(2, result.assets().size());
        assertEquals("a-1.0.jar", result.assets().get(0).name());
        assertEquals(jarBytes.length, result.assets().get(0).size());
        assertEquals(Digests.sha256Hex(jarBytes), result.assets().get(0).hashes().get("SHA-256"));
    }

    @Test
    void listPackageVersionAssetsFailsNotFoundForAMavenVersionThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "maven", "com.example", "a", "1.0", null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listPackageVersionAssetsRoutesEveryContainerBackedFormatToTheSidecarBridge() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        for (String format : List.of("npm", "pypi", "nuget")) {
            AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                    null, "repo", format, null, "my-pkg", "1.0.0", null, null));
            assertEquals("ResourceNotFoundException", e.getErrorCode());
        }
    }

    @Test
    void listPackageVersionAssetsRejectsAFormatAwsDoesNotSupport() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "not-a-real-format", null, "my-pkg", "1.0.0", null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void listPackageVersionAssetsRequiresANamespaceForMaven() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "maven", null, "a", "1.0", null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void listPackageVersionAssetsRejectsANamespaceForPypiAndNuGet() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        for (String format : List.of("pypi", "nuget")) {
            AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION, "dom",
                    null, "repo", format, "a-namespace", "my-pkg", "1.0.0", null, null));
            assertEquals("ValidationException", e.getErrorCode());
        }
    }

    @Test
    void listPackageVersionAssetsRequiresDomainRepositoryAndVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException missingDomain = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION,
                null, null, "repo", "generic", "ns", "my-pkg", "1.0.0", null, null));
        assertEquals("ValidationException", missingDomain.getErrorCode());

        AwsException missingRepository = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(
                REGION, "dom", null, null, "generic", "ns", "my-pkg", "1.0.0", null, null));
        assertEquals("ValidationException", missingRepository.getErrorCode());

        AwsException missingVersion = assertThrows(AwsException.class, () -> service.listPackageVersionAssets(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", null, null, null));
        assertEquals("ValidationException", missingVersion.getErrorCode());
    }

    // ----------------------------------------------------- ListPackageVersions

    @Test
    void listPackageVersionsReturnsEveryPublishedVersionForAGenericPackage() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        publishGeneric("repo", "my-pkg", "2.0.0", "b".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", null, null, null, null, null);

        assertEquals(2, result.versions().size());
        Set<String> versions = result.versions().stream().map(CodeArtifactService.PackageVersionSummary::version)
                .collect(Collectors.toSet());
        assertEquals(Set.of("1.0.0", "2.0.0"), versions);
        result.versions().forEach(v -> assertEquals("Published", v.status()));
    }

    @Test
    void listPackageVersionsIncludesUnfinishedByDefaultButAnExplicitStatusFiltersExactly() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "2.0.0", "b.txt",
                Digests.sha256Hex("b".getBytes(StandardCharsets.UTF_8)), "true", "b".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.ListPackageVersionsResult all = service.listPackageVersions(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg", null, null, null, null, null);
        assertEquals(2, all.versions().size());

        CodeArtifactService.ListPackageVersionsResult publishedOnly = service.listPackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "Published", null, null, null, null);
        assertEquals(1, publishedOnly.versions().size());
        assertEquals("1.0.0", publishedOnly.versions().get(0).version());

        CodeArtifactService.ListPackageVersionsResult unfinishedOnly = service.listPackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "Unfinished", null, null, null, null);
        assertEquals(1, unfinishedOnly.versions().size());
        assertEquals("2.0.0", unfinishedOnly.versions().get(0).version());
    }

    @Test
    void listPackageVersionsFailsNotFoundForAGenericPackageThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", null, null, null, null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listPackageVersionsReturnsAnEmptyListForAnOriginConfigOnlyPackage() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "BLOCK",
                "BLOCK");

        CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", null, null, null, null, null);

        assertTrue(result.versions().isEmpty());
        assertEquals(null, result.defaultDisplayVersion());
    }

    @Test
    void listPackageVersionsBridgesToReposiliteForMavenOrderedMostRecentFirstWithPagination() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0", Instant.ofEpochSecond(2000))));

        CodeArtifactService.ListPackageVersionsResult page1 = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", null, null, 1, null, null);
        assertEquals(1, page1.versions().size());
        assertEquals("2.0", page1.versions().get(0).version());
        assertEquals("Published", page1.versions().get(0).status());
        assertTrue(page1.versions().get(0).revision() != null && !page1.versions().get(0).revision().isBlank());
        assertEquals("2.0", page1.defaultDisplayVersion());
        assertTrue(page1.nextToken() != null);

        CodeArtifactService.ListPackageVersionsResult page2 = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", null, null, 1, page1.nextToken(), null);
        assertEquals(1, page2.versions().size());
        assertEquals("1.0", page2.versions().get(0).version());
        assertTrue(page2.nextToken() == null);
    }

    /**
     * A sidecar that could not supply a timestamp for any version (every {@code
     * PackageVersionTimestamp.publishedTime} is {@code null}) must still report a deterministic
     * default rather than omitting {@code defaultDisplayVersion} entirely: it should be whichever
     * {@code Published} version the list itself puts first.
     */
    @Test
    void listPackageVersionsPicksADeterministicDefaultWhenNoVersionHasAPublishedTime() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", null),
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0", null)));

        CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", null, null, null, null, null);

        assertEquals("1.0", result.versions().get(0).version());
        assertEquals("1.0", result.defaultDisplayVersion());
    }

    /**
     * {@code packageVersionSortCursor} and {@code defaultDisplayVersion} must break a same-second
     * publish tie the same direction: the first {@code Published} version the list returns is
     * always the default, never a different one a separate lexicographic comparison would have
     * picked (which, for versions like {@code 9.0} and {@code 10.0}, disagrees with list order).
     */
    @Test
    void listPackageVersionsDefaultDisplayVersionAgreesWithListOrderOnASameSecondTie() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("9.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("10.0", Instant.ofEpochSecond(1000))));

        CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", null, null, null, null, null);

        assertEquals(result.versions().get(0).version(), result.defaultDisplayVersion());
    }

    @Test
    void listPackageVersionsRoutesEveryContainerBackedFormatToTheSidecarBridge() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(verdaccioClient.listPackageVersions(eq(created.getSidecarContainerIds().get("npm")), eq("dom"),
                eq("repo"), isNull(), eq("my-pkg"))).thenReturn(
                        List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", null)));
        when(pypiserverClient.listPackageVersions(eq(created.getSidecarContainerIds().get("pypi")), eq("dom"),
                eq("repo"), isNull(), eq("my-pkg"))).thenReturn(
                        List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", null)));
        when(bagetterClient.listPackageVersions(eq(created.getSidecarContainerIds().get("nuget")), eq("dom"),
                eq("repo"), isNull(), eq("my-pkg"))).thenReturn(
                        List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", null)));

        for (String format : List.of("npm", "pypi", "nuget")) {
            CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                    "repo", format, null, "my-pkg", null, null, null, null, null);
            assertEquals(1, result.versions().size());
            assertEquals("1.0.0", result.versions().get(0).version());
            assertEquals("Published", result.versions().get(0).status());
        }
    }

    /**
     * {@code ensureFormatContainerId} resolves {@code repoId} and {@code listPackageVersions}
     * itself is called in two separate statements, with a concurrent repository delete's cleanup
     * able to run in between them; {@code beginUse}/{@code endUse} must bracket that gap, not
     * just the sidecar call itself, or a delete racing exactly there would leave a permit cached
     * for a container that is already gone with nothing left to ever clean it up.
     */
    @Test
    void listPackageVersionsBracketsThePypiserverCallWithBeginUseAndEndUse() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", null)));

        service.listPackageVersions(REGION, "dom", null, "repo", "pypi", null, "my-pkg", null, null, null, null,
                null);

        InOrder order = inOrder(pypiserverClient);
        order.verify(pypiserverClient).beginUse(repoId);
        order.verify(pypiserverClient).listPackageVersions(repoId, "dom", "repo", null, "my-pkg");
        order.verify(pypiserverClient).endUse(repoId);
    }

    @Test
    void listPackageVersionsCallsEndUseEvenWhenTheSidecarCallFails() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        doThrow(new IllegalStateException("sidecar unreachable")).when(pypiserverClient)
                .listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"));

        assertThrows(IllegalStateException.class, () -> service.listPackageVersions(REGION, "dom", null, "repo",
                "pypi", null, "my-pkg", null, null, null, null, null));

        verify(pypiserverClient).endUse(repoId);
    }

    /**
     * {@code listPackageVersions} and {@code deleteRepository} can never interleave: resolving
     * {@code repoId} and calling {@code beginUse} happen under the same lock {@code
     * deleteRepository} holds for its own sidecar cleanup. Parks {@code beginUse} while it still
     * holds that lock, confirms a concurrently-started delete cannot even begin until it is
     * released, then confirms the delete's {@code release} call never runs until {@code beginUse}
     * has returned.
     */
    @Test
    void listPackageVersionsNeverRegistersUseWhileADeleteIsStillCleaningUpTheSameRepository()
            throws InterruptedException {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch beginUseStarted = new CountDownLatch(1);
        CountDownLatch beginUseMayFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            events.add("beginUse-start");
            beginUseStarted.countDown();
            beginUseMayFinish.await(5, TimeUnit.SECONDS);
            events.add("beginUse-end");
            return null;
        }).when(pypiserverClient).beginUse(anyString());
        doAnswer(invocation -> {
            events.add("release");
            return null;
        }).when(pypiserverClient).release(anyString());

        AtomicReference<Throwable> listingFailure = new AtomicReference<>();
        Thread lister = new Thread(() -> {
            try {
                service.listPackageVersions(REGION, "dom", null, "repo", "pypi", null, "my-pkg", null, null, null,
                        null, null);
            } catch (Throwable t) {
                listingFailure.set(t);
            }
        });
        lister.start();
        assertTrue(beginUseStarted.await(5, TimeUnit.SECONDS), "expected listPackageVersions to reach beginUse "
                + "while still holding the service's lock");

        Thread deleter = new Thread(() -> service.deleteRepository(REGION, "dom", null, "repo"));
        deleter.start();
        deleter.join(200);
        assertTrue(deleter.isAlive(), "expected deleteRepository to block trying to acquire the service's lock "
                + "while listPackageVersions still holds it inside beginUse");
        assertFalse(events.contains("release"), "expected deleteRepository to not have reached its sidecar "
                + "release call while listPackageVersions still holds the lock");

        beginUseMayFinish.countDown();
        lister.join(5_000);
        deleter.join(5_000);

        assertTrue(listingFailure.get() == null || listingFailure.get() instanceof AwsException,
                "expected listPackageVersions to either succeed or fail with a real error, got "
                        + listingFailure.get());
        int beginUseEndIndex = events.indexOf("beginUse-end");
        int releaseIndex = events.indexOf("release");
        assertTrue(beginUseEndIndex != -1 && releaseIndex != -1 && releaseIndex > beginUseEndIndex,
                "expected the delete's release call to only ever run after beginUse had fully finished; recorded "
                        + "order was " + events);
    }

    @Test
    void listPackageVersionsFailsNotFoundForAContainerBackedPackageThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", null, null, null, null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listPackageVersionsOriginTypeExternalOrUnknownReturnsEmptyButKeepsDefaultDisplayVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        for (String originType : List.of("EXTERNAL", "UNKNOWN")) {
            CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                    "repo", "generic", "ns", "my-pkg", null, null, null, null, originType);
            assertTrue(result.versions().isEmpty());
            assertEquals("1.0.0", result.defaultDisplayVersion());
        }
    }

    @Test
    void listPackageVersionsDefaultDisplayVersionPrefersNpmDistTagLatestOverMostRecentlyPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0.0", Instant.ofEpochSecond(2000))));
        when(verdaccioClient.preferredDisplayVersion(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"))).thenReturn(Optional.of("1.0.0"));

        CodeArtifactService.ListPackageVersionsResult result = service.listPackageVersions(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", null, null, null, null, null);

        assertEquals("1.0.0", result.defaultDisplayVersion());
    }

    @Test
    void listPackageVersionsRejectsAFormatAwsDoesNotSupport() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom", null,
                "repo", "not-a-real-format", null, "my-pkg", null, null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void listPackageVersionsRequiresANamespaceForMaven() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", null, "a", null, null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void listPackageVersionsRejectsANamespaceForPypiAndNuGet() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        for (String format : List.of("pypi", "nuget")) {
            AwsException e = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom", null,
                    "repo", format, "a-namespace", "my-pkg", null, null, null, null, null));
            assertEquals("ValidationException", e.getErrorCode());
        }
    }

    @Test
    void listPackageVersionsRequiresDomainAndRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException missingDomain = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION,
                null, null, "repo", "generic", "ns", "my-pkg", null, null, null, null, null));
        assertEquals("ValidationException", missingDomain.getErrorCode());

        AwsException missingRepository = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION,
                "dom", null, null, "generic", "ns", "my-pkg", null, null, null, null, null));
        assertEquals("ValidationException", missingRepository.getErrorCode());
    }

    @Test
    void listPackageVersionsRejectsAnInvalidStatusSortByOrOriginType() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        AwsException badStatus = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "NotAStatus", null, null, null, null));
        assertEquals("ValidationException", badStatus.getErrorCode());

        AwsException badSortBy = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", null, "NOT_A_SORT", null, null, null));
        assertEquals("ValidationException", badSortBy.getErrorCode());

        AwsException badOriginType = assertThrows(AwsException.class, () -> service.listPackageVersions(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", null, null, null, null, "NOT_AN_ORIGIN"));
        assertEquals("ValidationException", badOriginType.getErrorCode());
    }

    // ------------------------------------- updatePackageVersionsStatus / disposePackageVersions

    @Test
    void updatePackageVersionsStatusArchivesAGenericVersionAndChangesItsRevision() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        // Captured as a plain string, not the live CodeArtifactPackageVersion object: the test
        // storage backend hands back the same mutable instance on every read, so holding onto the
        // object itself would see it change underneath this assertion once the update below
        // mutates it in place.
        String revisionBeforeUpdate = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0").getRevision();

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, null, "Archived");

        assertEquals(1, result.successfulVersions().size());
        assertEquals("Archived", result.successfulVersions().get("1.0.0").status());
        assertNotEquals(revisionBeforeUpdate, result.successfulVersions().get("1.0.0").revision());
        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Archived", service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0").getStatus());
    }

    /**
     * Raised by Greptile's review: {@code UPDATE_TARGET_STATUSES} allows targeting {@code
     * Published} from {@code Unfinished} (nothing in {@code checkStatusTransitionAllowed} blocks
     * it), the real way a version can finalize through this action instead of through {@code
     * publishPackageVersion}'s own normal path, which already sets {@code publishedTime} on the
     * identical condition. Left unset, the version would sort as though never published at all.
     */
    @Test
    void updatePackageVersionsStatusSetsPublishedTimeWhenFinalizingAnUnfinishedGenericVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "a".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "true", content);
        assertEquals("Unfinished", service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0").getStatus());
        assertNull(service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0")
                .getPublishedTime());

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "generic", "ns", "my-pkg",
                List.of("1.0.0"), null, null, "Published");

        assertNotNull(service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg",
                "1.0.0").getPublishedTime());
    }

    @Test
    void updatePackageVersionsStatusSkipsEveryOtherwiseEligibleVersionWhenOneFails() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0", "9.9.9"), null, null, "Archived");

        assertTrue(result.successfulVersions().isEmpty());
        assertEquals("NOT_FOUND", result.failedVersions().get("9.9.9").errorCode());
        assertEquals("SKIPPED", result.failedVersions().get("1.0.0").errorCode());
        // Confirms the all-or-nothing rule actually held: 1.0.0 would have succeeded on its own.
        assertEquals("Published", service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0").getStatus());
    }

    @Test
    void updatePackageVersionsStatusFailsMismatchedRevisionAndMismatchedStatus() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.PackageVersionStatusChangeResult revisionMismatch = service.updatePackageVersionsStatus(
                REGION, "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"),
                Map.of("1.0.0", "not-the-real-revision"), null, "Archived");
        assertEquals("MISMATCHED_REVISION", revisionMismatch.failedVersions().get("1.0.0").errorCode());

        CodeArtifactService.PackageVersionStatusChangeResult statusMismatch = service.updatePackageVersionsStatus(
                REGION, "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, "Unlisted",
                "Archived");
        assertEquals("MISMATCHED_STATUS", statusMismatch.failedVersions().get("1.0.0").errorCode());
        assertTrue(statusMismatch.failedVersions().get("1.0.0").errorMessage().contains("current status: Published"));
    }

    @Test
    void updatePackageVersionsStatusCannotMoveAwayFromDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        service.disposePackageVersions(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"),
                null, null);

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, null, "Unlisted");

        assertEquals("NOT_ALLOWED", result.failedVersions().get("1.0.0").errorCode());
    }

    /**
     * Raised by Greptile's review: the batch actions' own existence/override matching used the
     * raw requested version string, so archiving a NuGet version and then calling
     * {@code UpdatePackageVersionsStatus} again with an equivalent spelling (AWS's own worked
     * example, {@code 1.00} and {@code 1.0.0}) found the version real but missed its override,
     * silently defaulting to {@code Published} and failing {@code MISMATCHED_STATUS} against an
     * {@code expectedStatus=Archived} that was, in reality, already true.
     */
    @Test
    void updatePackageVersionsStatusMatchesAnEquivalentNuGetVersionSpellingAgainstAnExistingOverride() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("nuget");
        when(bagetterClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0",
                        Instant.ofEpochSecond(1000))));

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "nuget", null, "my-pkg",
                List.of("1.0.0"), null, null, "Archived");

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "nuget", null, "my-pkg", List.of("1.00"), null, "Archived", "Unlisted");

        assertEquals("Unlisted", result.successfulVersions().get("1.00").status());
    }

    @Test
    void updatePackageVersionsStatusRejectsUnfinishedDisposedOrDeletedAsTargetStatus() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        for (String badTarget : List.of("Unfinished", "Disposed", "Deleted", "NotAStatus")) {
            AwsException e = assertThrows(AwsException.class, () -> service.updatePackageVersionsStatus(REGION,
                    "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, null, badTarget));
            assertEquals("ValidationException", e.getErrorCode());
        }
    }

    @Test
    void updatePackageVersionsStatusRejectsDisposedAsExpectedStatusButDisposeAcceptsIt() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.updatePackageVersionsStatus(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, "Disposed", "Archived"));
        assertEquals("ValidationException", e.getErrorCode());

        // DisposePackageVersionsRequest.expectedStatus's own wider valid set accepts "Disposed"
        // itself (confirmed against the API reference): the version isn't actually Disposed yet,
        // so this still fails, but on the per-version MISMATCHED_STATUS check, not request
        // validation.
        CodeArtifactService.PackageVersionStatusChangeResult result = service.disposePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, "Disposed");
        assertEquals("MISMATCHED_STATUS", result.failedVersions().get("1.0.0").errorCode());
    }

    @Test
    void updatePackageVersionsStatusRequiresVersionsAndCapsAtOneHundred() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException missing = assertThrows(AwsException.class, () -> service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", List.of(), null, null, "Archived"));
        assertEquals("ValidationException", missing.getErrorCode());

        List<String> tooMany = IntStream.range(0, 101).mapToObj(i -> "1.0." + i).toList();
        AwsException overLimit = assertThrows(AwsException.class, () -> service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "generic", "ns", "my-pkg", tooMany, null, null, "Archived"));
        assertEquals("ValidationException", overLimit.getErrorCode());
    }

    @Test
    void disposePackageVersionsDeletesGenericAssetBytesAndBlocksFurtherDownload() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.disposePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, null);

        assertEquals("Disposed", result.successfulVersions().get("1.0.0").status());
        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt", null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        // DescribePackageVersion still answers for a disposed version (per the User Guide: "To
        // view information about a disposed package version, use DescribePackageVersion"), it
        // just reports no assets left.
        CodeArtifactPackageVersion described = service.describePackageVersion(REGION, "dom", null, "repo", "generic",
                "ns", "my-pkg", "1.0.0");
        assertEquals("Disposed", described.getStatus());
        assertTrue(described.getAssets().isEmpty());
    }

    /**
     * A sidecar that cannot supply a timestamp for one of its versions reports it as a {@code
     * null} {@code publishedTime} (the same possibility {@code listPackageVersions} itself
     * already handles); building the per-call publish-time lookup with {@code Collectors.toMap}
     * would throw a {@code NullPointerException} on that null value (it uses {@code Map.merge}
     * internally, which rejects one), so this must still succeed.
     */
    @Test
    void updatePackageVersionsStatusSucceedsWhenTheSidecarHasNoTimestampForTheVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0", null)));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"), null, null, "Archived");

        assertEquals("Archived", result.successfulVersions().get("1.0").status());
        assertTrue(result.failedVersions().isEmpty());
    }

    @Test
    void updatePackageVersionsStatusArchivesAMavenVersionThroughTheSidecarOverrideAndGetAssetIsBlocked() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a.jar"))).thenReturn(Optional.of("jar-bytes".getBytes(StandardCharsets.UTF_8)));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"), null, null, "Archived");
        assertEquals("Archived", result.successfulVersions().get("1.0").status());

        CodeArtifactService.ListPackageVersionsResult listed = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "Archived", null, null, null, null);
        assertEquals(1, listed.versions().size());
        assertEquals("Archived", listed.versions().get(0).status());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "1.0", "a.jar", null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        verify(reposiliteClient, never()).fetchPackageVersionAsset(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void containerBackedOverriddenVersionStatusesByRepositoryReturnsEveryOverriddenPackageInOneFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("other-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0.0", Instant.ofEpochSecond(2000))));

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "pypi", null, "my-pkg", List.of("1.0.0"),
                null, null, "Archived");
        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "pypi", null, "other-pkg", List.of("2.0.0"),
                null, null, "Unlisted");

        assertEquals(Map.of("my-pkg", Map.of("1.0.0", "Archived"), "other-pkg", Map.of("2.0.0", "Unlisted")),
                service.containerBackedOverriddenVersionStatusesByRepository(REGION, "dom", null, "repo", "pypi"));
        assertEquals(Map.of(),
                service.containerBackedOverriddenVersionStatusesByRepository(REGION, "dom", null, "repo", "npm"));
    }

    @Test
    void disposePackageVersionsDeletesEveryRealAssetAndKeepsListingTheVersionAsDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.disposePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null, null);
        assertEquals("Disposed", result.successfulVersions().get("1.0").status());
        verify(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"));
        verify(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"));

        // The sidecar no longer lists the disposed version at all (its content is gone), yet
        // ListPackageVersions still reports it, by status=Disposed, from the override alone.
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of());
        CodeArtifactService.ListPackageVersionsResult listed = service.listPackageVersions(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "Disposed", null, null, null, null);
        assertEquals(1, listed.versions().size());
        assertEquals("1.0", listed.versions().get(0).version());
        assertEquals("Disposed", listed.versions().get(0).status());
    }

    /**
     * Raised by Greptile's review: {@code applyContainerBackedStatusChange} writes the override
     * {@code Disposed} before deleting any real assets, so a sidecar failure partway through that
     * deletion loop left the version Disposed with some assets still real, and the unconditional
     * {@code NOT_ALLOWED} on an already-{@code Disposed} version meant no further
     * {@code DisposePackageVersions} call could ever finish that cleanup. A retry targeting
     * {@code Disposed} again must now succeed and delete whatever the sidecar still lists.
     *
     * <p>Also proves Greptile's own follow-up finding on that same fix: the jar's bytes, freed by
     * the first (overall failed) attempt, must still count against {@code
     * DescribeDomain.assetSizeBytes} once the retry finishes the pom, not just the pom's own bytes
     * freed by the retry itself; {@link #deleteContainerBackedVersionAssets} subtracts each
     * asset's bytes right after that asset's own delete, not batched at the end, specifically so a
     * failure between two deletes never loses the first one's already-freed bytes.
     */
    @Test
    void disposePackageVersionsRetriesAfterAPartialAssetDeletionFailureAndFinishesCleanup() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        recordBytes("dom", "repo", "maven", "com.example", "a", 100L);
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar", "a-1.0.pom"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(new byte[40]));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.pom"))).thenReturn(Optional.of(new byte[25]));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                        eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.pom"));

        assertThrows(IllegalStateException.class, () -> service.disposePackageVersions(REGION, "dom", null, "repo",
                "maven", "com.example", "a", List.of("1.0"), null, null));
        // The override already reads Disposed (written before the failed delete), so downloads
        // are already blocked even though the pom never actually got deleted.
        assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null));
        verify(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"));
        assertEquals(60L, service.describeDomain(REGION, "dom", null).assetSizeBytes());

        // The sidecar recovers; its own listing now only names the pom (the jar's delete already
        // completed on the first attempt), the same self-correcting signal a real retry relies on.
        doNothing().when(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.pom"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.pom"));

        CodeArtifactService.PackageVersionStatusChangeResult retry = service.disposePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null, null);

        assertEquals("Disposed", retry.successfulVersions().get("1.0").status());
        // Twice in total across both attempts: the first one threw (counted on the mock
        // regardless), the retry's own call is the one that actually finished the delete.
        verify(reposiliteClient, times(2)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.pom"));
        assertEquals(35L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Raised by Greptile's review: unlike Maven, an npm disposal retry cannot rely on a fresh
     * {@code listPackageVersionAssetNames} call to tell it what is still left to delete.
     * {@code VerdaccioSidecarClient#deletePackageVersionAsset} removes the version from the
     * packument before the tarball delete even runs (Verdaccio's own real two-step unpublish,
     * confirmed live, see that method's own javadoc), so once that update lands, a fresh listing
     * can never again answer "what is left:" it already reads empty, exactly as simulated here by
     * re-stubbing it to return nothing after the first (failed) attempt. The retry must still
     * finish deleting the tarball, which it can only do by reading the override's own {@code
     * pendingAssetNames} instead of asking the sidecar again.
     */
    @Test
    void disposePackageVersionsRetriesAnNpmTarballDeleteEvenAfterTheSidecarsOwnListingGoesEmpty() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                        eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));

        assertThrows(IllegalStateException.class, () -> service.disposePackageVersions(REGION, "dom", null, "repo",
                "npm", null, "my-pkg", List.of("1.0.0"), null, null));

        // Simulates exactly what Greptile's review describes: Verdaccio's own packument update
        // already landed, so a fresh listing now reads empty even though the tarball itself
        // never actually got deleted.
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of());
        doNothing().when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));

        CodeArtifactService.PackageVersionStatusChangeResult retry = service.disposePackageVersions(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", List.of("1.0.0"), null, null);

        assertEquals("Disposed", retry.successfulVersions().get("1.0.0").status());
        // Twice in total across both attempts: the first one threw (counted on the mock
        // regardless), the retry's own call is the one that actually finished the delete.
        verify(verdaccioClient, times(2)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));
    }

    @Test
    void disposePackageVersionsRejectsAnExpectedStatusUpdateDoesNotAllow() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.disposePackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null, "NotAStatus"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    // --------------------------------------------- DeletePackageVersions

    @Test
    void deletePackageVersionsRemovesTheGenericRecordAndAssetsAndAllowsFreeRepublish() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        CodeArtifactService.PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content), "false",
                content);
        String revisionBeforeDelete = published.packageVersion().getRevision();

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0.0").status());
        assertEquals(revisionBeforeDelete, result.successfulVersions().get("1.0.0").revision());
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());

        // Unlike Archived/Disposed, a deleted coordinate can be freely republished.
        byte[] newContent = "v2".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(newContent), "false", newContent);
        CodeArtifactPackageVersion republished = service.describePackageVersion(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg", "1.0.0");
        assertEquals("Published", republished.getStatus());
    }

    @Test
    void deletePackageVersionsFailsNotFoundForAVersionThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null);

        assertEquals("NOT_FOUND", result.failedVersions().get("1.0.0").errorCode());
        assertTrue(result.successfulVersions().isEmpty());
    }

    @Test
    void deletePackageVersionsSkipsEveryOtherwiseEligibleVersionWhenOneFails() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0", "2.0.0"), null);

        assertEquals("SKIPPED", result.failedVersions().get("1.0.0").errorCode());
        assertEquals("NOT_FOUND", result.failedVersions().get("2.0.0").errorCode());
        assertTrue(result.successfulVersions().isEmpty());
        CodeArtifactPackageVersion stillThere = service.describePackageVersion(REGION, "dom", null, "repo",
                "generic", "ns", "my-pkg", "1.0.0");
        assertEquals("Published", stillThere.getStatus());
    }

    @Test
    void deletePackageVersionsFailsMismatchedStatus() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), "Archived");

        assertEquals("MISMATCHED_STATUS", result.failedVersions().get("1.0.0").errorCode());
    }

    /**
     * The User Guide's one documented exception to Disposed's own immutability: "the only action
     * that can be taken on a package version that has been disposed is for it to be deleted."
     * Deliberately does not reuse {@code checkStatusTransitionAllowed}'s Disposed-blocks-further
     * -change rule, which would otherwise wrongly reject exactly this case.
     */
    @Test
    void deletePackageVersionsSucceedsOnAnAlreadyDisposedGenericVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.disposePackageVersions(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"),
                null, null);

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0.0").status());
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "generic", "ns", "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void deletePackageVersionsRejectsAnInvalidExpectedStatus() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.deletePackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", List.of("1.0.0"), "NotAStatus"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void deletePackageVersionsRequiresAtLeastOneVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.deletePackageVersions(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", List.of(), null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void deletePackageVersionsDeletesEveryRealMavenAssetClearsTheOverrideAndReducesDomainBytes() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        recordBytes("dom", "repo", "maven", "com.example", "a", 100L);
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(new byte[40]));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0").status());
        verify(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"));
        verify(reposiliteClient).recordVersionDisposed(eq(repoId), eq("com.example"), eq("a"), eq("1.0"));
        assertEquals(60L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
        // The sidecar no longer lists it and the override is gone too, so it reads as never having
        // existed, unlike disposePackageVersions' own tombstone.
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of());
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "maven", "com.example", "a", "1.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * Raised by Greptile's review of the npm fix above: gating the call to {@link
     * #deleteContainerBackedVersionAssets} on a non-empty asset list broke the case where every
     * real asset is already gone but that method's own trailing {@code recordVersionDisposed}
     * call, Maven's {@code maven-metadata.xml}/checksum cleanup, is what actually failed. A retry
     * whose fresh listing now reads empty (the real file is gone) must still finish that metadata
     * update rather than skip it just because there is nothing left to delete.
     */
    @Test
    void deletePackageVersionsRetriesMavenMetadataCleanupEvenWhenNoRealAssetIsLeftToDelete() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        doThrow(new IllegalStateException("metadata rewrite failed"))
                .when(reposiliteClient).recordVersionDisposed(eq(repoId), eq("com.example"), eq("a"), eq("1.0"));

        assertThrows(IllegalStateException.class, () -> service.deletePackageVersions(REGION, "dom", null, "repo",
                "maven", "com.example", "a", List.of("1.0"), null));

        // The real jar is already gone (its own delete call succeeded before the metadata update
        // threw), so a fresh listing now reads empty, but the sidecar's own listPackageVersions
        // still names the version since the metadata rewrite that would drop it never finished.
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of());
        doNothing().when(reposiliteClient).recordVersionDisposed(eq(repoId), eq("com.example"), eq("a"), eq("1.0"));

        CodeArtifactService.PackageVersionStatusChangeResult retry = service.deletePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null);

        assertEquals("Deleted", retry.successfulVersions().get("1.0").status());
        // The real asset delete never needed a second attempt, but the metadata cleanup does:
        // once from the failed first attempt, once from the retry that actually finishes it.
        verify(reposiliteClient, times(1)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"));
        verify(reposiliteClient, times(2)).recordVersionDisposed(eq(repoId), eq("com.example"), eq("a"), eq("1.0"));
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of());
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "maven", "com.example", "a", "1.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * The container-backed counterpart to {@link #deletePackageVersionsSucceedsOnAnAlreadyDisposedGenericVersion}:
     * a Disposed version's real sidecar content is already gone, so this must clear the override
     * without ever calling back into the sidecar for assets that no longer exist.
     */
    @Test
    void deletePackageVersionsSucceedsOnAnAlreadyDisposedMavenVersionWithoutTouchingTheSidecarAssets() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"),
                null, null);
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of());
        clearInvocations(reposiliteClient);

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0").status());
        verify(reposiliteClient, never()).deletePackageVersionAsset(any(), any(), any(), any(), any(), eq("1.0"),
                any());
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "maven", "com.example", "a", "1.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * Raised by Greptile's review: this used to reach the branch below at all only when the
     * sidecar still listed the version with real content. Verdaccio's own real npm unpublish
     * (packument update before the tarball delete, confirmed live, see {@code
     * VerdaccioSidecarClient#deletePackageVersionAsset}'s own javadoc) already leaves a fresh
     * listing empty once a prior dispose attempt's packument update landed, even though its own
     * tarball delete then failed. That used to fall straight through to clearing the override,
     * silently reporting {@code Deleted} with the real tarball still on disk. {@code
     * DeletePackageVersions} is documented as the one thing still allowed on an already Disposed
     * version, so it must finish the job by reading the override's own {@code pendingAssetNames}
     * instead, exactly like {@link #disposePackageVersionsRetriesAnNpmTarballDeleteEvenAfterTheSidecarsOwnListingGoesEmpty}'s
     * own retry does for a second dispose.
     */
    @Test
    void deletePackageVersionsFinishesAPreviouslyPartiallyFailedNpmDisposeInsteadOfOrphaningTheTarball() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                        eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));

        assertThrows(IllegalStateException.class, () -> service.disposePackageVersions(REGION, "dom", null, "repo",
                "npm", null, "my-pkg", List.of("1.0.0"), null, null));

        // Simulates exactly what Greptile's review describes: Verdaccio's own packument update
        // already landed, so a fresh listing now reads empty even though the tarball itself
        // never actually got deleted.
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of());
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of());
        doNothing().when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", List.of("1.0.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0.0").status());
        // Twice in total across both attempts: the first (from the failed dispose) threw, the
        // delete's own retry call is the one that actually finished removing the tarball.
        verify(verdaccioClient, times(2)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));
        AwsException gone = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    /**
     * The delete counterpart to {@link
     * #updatePackageVersionsStatusMatchesAnEquivalentNuGetVersionSpellingAgainstAnExistingOverride}.
     * Raised by Greptile's review: {@code DeletePackageVersions} compared the raw requested
     * version against both BaGetter's own listing and the override index without normalizing
     * either side first, unlike every other container-backed lookup, so an equivalent spelling
     * (AWS's own worked example, {@code 1.00} and {@code 1.0.0}) found neither the real sidecar
     * content nor an override for the version it actually names, turning one such entry in a
     * batch into a {@code SKIPPED} failure for every other, otherwise valid, version too.
     */
    @Test
    void deletePackageVersionsMatchesAnEquivalentNuGetVersionSpellingAgainstRealSidecarContent() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("nuget");
        when(bagetterClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp("1.0.0",
                        Instant.ofEpochSecond(1000))));
        when(bagetterClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.00"))).thenReturn(List.of("my-pkg.1.00.nupkg"));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "nuget", null, "my-pkg", List.of("1.00"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.00").status());
        verify(bagetterClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.00"), eq("my-pkg.1.00.nupkg"));
    }

    @Test
    void deletePackageVersionsFailsNotFoundForAContainerBackedVersionThatWasNeverPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of());

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null);

        assertEquals("NOT_FOUND", result.failedVersions().get("1.0").errorCode());
    }

    /**
     * Greptile's review: unlike nuget, which answers {@code GetPackageVersionReadme} live against
     * the sidecar, npm's readme is captured separately at publish time into this service's own
     * store and never consulted again by anything else, so deleting the version must clear it
     * there too or the readme keeps answering for a version that no longer exists.
     */
    @Test
    void deletePackageVersionsClearsTheCapturedNpmReadmeSoGetPackageVersionReadmeStops404ing() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        recordReadme("dom", "repo", "npm", null, "my-pkg", "1.0.0", "# Real Readme");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", List.of("1.0.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0.0").status());
        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /** Same captured-readme cleanup as npm's own, confirmed separately since pypi captures readmes independently. */
    @Test
    void deletePackageVersionsClearsTheCapturedPypiReadmeSoGetPackageVersionReadmeStops404ing() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        recordReadme("dom", "repo", "pypi", null, "my-pkg", "1.0.0", "A pypi description");
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(pypiserverClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my_pkg-1.0.0.tar.gz"));

        CodeArtifactService.PackageVersionStatusChangeResult result = service.deletePackageVersions(REGION, "dom",
                null, "repo", "pypi", null, "my-pkg", List.of("1.0.0"), null);

        assertEquals("Deleted", result.successfulVersions().get("1.0.0").status());
        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom",
                null, "repo", "pypi", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * Greptile's review of the readme-clearing fix above: a delayed npm publish callback for this
     * exact version, its own fence snapshot taken before {@code DeletePackageVersions} ran, must
     * not resurrect the readme that delete just removed once the callback finally completes.
     */
    @Test
    void deletePackageVersionsInvalidatesAStaleInFlightNpmReadmeCaptureForThatSameVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        // Simulates the proxy's own snapshot, taken right before issuing the real publish write,
        // long before Verdaccio's delayed response ever lets the callback below run.
        long staleFenceSnapshot = service.currentContainerBackedVersionFence(REGION, "dom", null, "repo", "npm",
                null, "my-pkg", "1.0.0");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));

        service.deletePackageVersions(REGION, "dom", null, "repo", "npm", null, "my-pkg", List.of("1.0.0"), null);
        // The delayed callback finally runs, well after the delete above, using the snapshot it
        // took before either of them.
        service.recordPublishedPackageVersionReadme(REGION, "dom", null, "repo", "npm", null, "my-pkg", "1.0.0",
                "resurrected text", staleFenceSnapshot);

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * Qodo review finding on this PR: {@code DescribeDomain.assetSizeBytes} kept counting a
     * container-backed version's bytes forever after disposal, since the per-package running
     * total was never reduced when its real sidecar assets were deleted.
     */
    @Test
    void disposePackageVersionsReducesTheDomainAssetSizeByTheRealDeletedBytes() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        recordBytes("dom", "repo", "maven", "com.example", "a", 100L);
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(new byte[40]));

        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"),
                null, null);

        assertEquals(60L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Raised by Greptile's review: {@code PypiserverSidecarClient#deletePackageVersionAsset}
     * removes every file sharing the same raw package name and version in one call (confirmed
     * live, the same quirk {@code copyContainerBackedPackageVersions}'s own write path already
     * works around), so a release's second file could already be gone by the time the old
     * interleaved fetch-then-delete loop got to measuring it, undercounting the bytes {@code
     * DescribeDomain.assetSizeBytes} should have freed. Both assets are now measured before
     * either is deleted, so the collateral deletion below (simulated by having the sdist's own
     * delete flip the wheel's fetch to empty, exactly like the real sidecar's own side effect)
     * no longer loses its byte count.
     */
    @Test
    void disposePackageVersionsMeasuresEveryAssetBeforeDeletingAnyOfThemSoACollateralDeleteDoesNotLoseItsBytes() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        recordBytes("dom", "repo", "pypi", null, "my-pkg", 100L);
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(pypiserverClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0"))).thenReturn(List.of("my-pkg-1.0.tar.gz", "my_pkg-1.0-py3-none-any.whl"));
        AtomicBoolean sdistDeleted = new AtomicBoolean();
        when(pypiserverClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0"), eq("my-pkg-1.0.tar.gz"))).thenReturn(Optional.of(new byte[40]));
        when(pypiserverClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0"), eq("my_pkg-1.0-py3-none-any.whl"))).thenAnswer(invocation ->
                        sdistDeleted.get() ? Optional.empty() : Optional.of(new byte[25]));
        doAnswer(invocation -> {
            sdistDeleted.set(true);
            return null;
        }).when(pypiserverClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0"), eq("my-pkg-1.0.tar.gz"));

        service.disposePackageVersions(REGION, "dom", null, "repo", "pypi", null, "my-pkg", List.of("1.0"), null,
                null);

        assertEquals(35L, service.describeDomain(REGION, "dom", null).assetSizeBytes());
    }

    /**
     * Qodo review finding on this PR: {@code ListPackageVersionAssets} and {@code
     * GetPackageVersionReadme} still computed a container-backed version's revision with the raw,
     * coordinate-derived {@code synthesizeContainerBackedRevision} formula, disagreeing with the
     * fresh revision {@code updatePackageVersionsStatus}/{@code DescribePackageVersion}/{@code
     * ListPackageVersions} report once a status override exists for that version.
     */
    @Test
    void listPackageVersionAssetsAndGetPackageVersionReadmeAgreeWithTheOverrideRevisionAfterArchiving() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(verdaccioClient.packageVersionExists(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));
        when(verdaccioClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"), eq("my-pkg-1.0.0.tgz"))).thenReturn(Optional.of("tgz-bytes".getBytes(
                        StandardCharsets.UTF_8)));
        recordReadme("dom", "repo", "npm", null, "my-pkg", "1.0.0", "hello");

        CodeArtifactService.PackageVersionStatusChangeResult changed = service.updatePackageVersionsStatus(REGION,
                "dom", null, "repo", "npm", null, "my-pkg", List.of("1.0.0"), null, null, "Archived");
        String overrideRevision = changed.successfulVersions().get("1.0.0").revision();

        CodeArtifactService.PackageVersionAssetsListResult assets = service.listPackageVersionAssets(REGION, "dom",
                null, "repo", "npm", null, "my-pkg", "1.0.0", null, null);
        assertEquals(overrideRevision, assets.versionRevision());

        CodeArtifactService.PackageVersionReadmeResult readme = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0");
        assertEquals(overrideRevision, readme.versionRevision());
    }

    /**
     * Qodo review finding on this PR: copying new content over a destination version that an
     * earlier {@code updatePackageVersionsStatus} had archived left the old override in place,
     * so the fresh content this copy just wrote would still read back as Archived and {@code
     * GetPackageVersionAsset} would keep 404ing on it, contradicting the copy's own response
     * (which reports Published).
     */
    @Test
    void copyPackageVersionsClearsAStaleArchivedOverrideAtTheDestinationAndUnblocksDownload() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository src = service.createRepository(REGION, "dom", null, "src", null, null, Map.of());
        CodeArtifactRepository dst = service.createRepository(REGION, "dom", null, "dst", null, null, Map.of());
        String srcRepoId = src.getSidecarContainerIds().get("maven");
        String dstRepoId = dst.getSidecarContainerIds().get("maven");
        byte[] newJar = "new-jar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] oldJar = "old-jar-bytes".getBytes(StandardCharsets.UTF_8);
        when(reposiliteClient.packageVersionExists(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.packageVersionExists(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(true);
        when(reposiliteClient.listPackageVersionAssetNames(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.listPackageVersionAssetNames(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(srcRepoId), eq("dom"), eq("src"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(newJar));
        when(reposiliteClient.fetchPackageVersionAsset(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(oldJar));
        when(reposiliteClient.listPackageVersions(eq(dstRepoId), eq("dom"), eq("dst"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        service.updatePackageVersionsStatus(REGION, "dom", null, "dst", "maven", "com.example", "a", List.of("1.0"),
                null, null, "Archived");
        assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null, "dst", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null));

        CodeArtifactService.CopyPackageVersionsResult result = service.copyPackageVersions(REGION, "dom", null,
                "src", "dst", "maven", "com.example", "a", List.of("1.0"), null, true, false);

        assertTrue(result.failedVersions().isEmpty());
        assertEquals("Published", result.successfulVersions().get("1.0").status());
        PackageVersionAssetResult downloaded = service.getPackageVersionAsset(REGION, "dom", null, "dst", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null);
        assertEquals(oldJar.length, downloaded.asset().getSize());
    }

    /**
     * Qodo review's remaining half of the same finding: {@code copyContainerBackedPackageVersions}
     * clears a stale override on its own overwrite path, but nothing cleared one for a native
     * publish: every container-backed proxy controller (Maven, npm, pypi, nuget) now calls
     * {@code forgetContainerBackedStatusOverride} right alongside its existing byte-accounting
     * call once a native publish actually writes something. This proves the shared primitive those
     * four call sites all delegate to.
     */
    @Test
    void forgetContainerBackedStatusOverrideClearsItAndUnblocksDownload() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of("jar-bytes".getBytes(
                        StandardCharsets.UTF_8)));
        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "maven", "com.example", "a",
                List.of("1.0"), null, null, "Archived");
        assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null));

        service.forgetContainerBackedStatusOverride(REGION, "dom", null, "repo", "maven", "com.example", "a", "1.0");

        PackageVersionAssetResult downloaded = service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null);
        assertEquals("jar-bytes".getBytes(StandardCharsets.UTF_8).length, downloaded.asset().getSize());
    }

    /**
     * Raised by hectorvent's review of this PR: {@code forgetContainerBackedStatusOverride} above
     * only ever clears an Archived/Disposed override after a native proxy controller's real
     * sidecar write already succeeded, which would let a package-manager republish silently
     * resurrect either status. Every native proxy controller now calls this guard first, before
     * issuing that write, and must see it refuse exactly the two statuses the User Guide blocks
     * unconditionally: Published, Unlisted, and a coordinate with no override at all must all
     * sail through untouched.
     */
    @Test
    void requireContainerBackedVersionNotArchivedOrDisposedBlocksOnlyThoseTwoStatuses() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("3.0", Instant.ofEpochSecond(1000))));

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "maven", "com.example", "a",
                List.of("1.0"), null, null, "Archived");
        AwsException archivedError = assertThrows(AwsException.class,
                () -> service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null, "repo",
                        "maven", "com.example", "a", "1.0"));
        assertEquals("ConflictException", archivedError.getErrorCode());

        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("2.0"),
                null, null);
        AwsException disposedError = assertThrows(AwsException.class,
                () -> service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null, "repo",
                        "maven", "com.example", "a", "2.0"));
        assertEquals("ConflictException", disposedError.getErrorCode());

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "maven", "com.example", "a",
                List.of("3.0"), null, null, "Unlisted");
        assertDoesNotThrow(() -> service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "3.0"));

        assertDoesNotThrow(() -> service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "4.0-never-touched"));
    }

    /**
     * The download-side counterpart: every native per-format proxy controller (Maven, npm, nuget,
     * pypi) calls this before serving bytes straight from its own sidecar, the mechanism that
     * closes 10-NATIVE-STATUS. Must block only {@code Archived} (the status whose real sidecar
     * bytes are deliberately left in place, unlike {@code Disposed}, which already deletes them)
     * and let every other status, including a version never touched at all, through unchanged,
     * the exact same four-way split {@link #requireContainerBackedVersionNotArchivedOrDisposedBlocksOnlyThoseTwoStatuses}
     * already proves for the upload side, with {@code Disposed} included here too even though a
     * real native download of a disposed version would already 404 against the sidecar itself for
     * an unrelated reason (its bytes are gone), since this method's own contract blocks both
     * statuses regardless of whether the sidecar would have caught it anyway.
     */
    @Test
    void requireContainerBackedVersionDownloadableBlocksOnlyArchivedAndDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("2.0", Instant.ofEpochSecond(1000)),
                        new RepositorySidecarManager.PackageVersionTimestamp("3.0", Instant.ofEpochSecond(1000))));

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "maven", "com.example", "a",
                List.of("1.0"), null, null, "Archived");
        AwsException archivedError = assertThrows(AwsException.class,
                () -> service.requireContainerBackedVersionDownloadable(ACCOUNT_ID, REGION, "dom", "repo", "maven",
                        "com.example", "a", "1.0", repoId));
        assertEquals("ResourceNotFoundException", archivedError.getErrorCode());

        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("2.0"),
                null, null);
        AwsException disposedError = assertThrows(AwsException.class,
                () -> service.requireContainerBackedVersionDownloadable(ACCOUNT_ID, REGION, "dom", "repo", "maven",
                        "com.example", "a", "2.0", repoId));
        assertEquals("ResourceNotFoundException", disposedError.getErrorCode());

        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "maven", "com.example", "a",
                List.of("3.0"), null, null, "Unlisted");
        assertDoesNotThrow(() -> service.requireContainerBackedVersionDownloadable(ACCOUNT_ID, REGION, "dom", "repo",
                "maven", "com.example", "a", "3.0", repoId));

        assertDoesNotThrow(() -> service.requireContainerBackedVersionDownloadable(ACCOUNT_ID, REGION, "dom", "repo",
                "maven", "com.example", "a", "4.0-never-touched", repoId));
    }

    /**
     * Raised by Greptile's review: {@code requireContainerBackedVersionNotArchivedOrDisposed}'s
     * guard, the real sidecar write, and this call are necessarily three separate steps (the
     * write sits in between, with no lock held across it), so a concurrent
     * {@code disposePackageVersions} landing in that gap already deleted the real content before
     * the publish's own write restored it. Leaving the override alone (it correctly already does)
     * is not enough on its own: a native per-format download route never consults the override at
     * all, so the restored bytes stayed live even with the override still reading {@code
     * Disposed}. This must now also undo that restoration by re-running the real asset deletion.
     */
    @Test
    void forgetContainerBackedStatusOverrideIfUnchangedRedeletesRestoredAssetsWhenAConcurrentDisposeWonTheRace() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        // The publish's own guard ran first, before anything (the real write or the race) has
        // happened yet: no override exists, so this is the snapshot it carries into its own
        // later forgetContainerBackedStatusOverrideIfUnchanged call.
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        String beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "1.0");
        assertNull(beforeRevision);

        // A concurrent dispose lands in the gap between that guard and the publish's own real
        // write landing. Nothing is stubbed to list any assets yet, so this dispose finds (and
        // deletes) nothing real, the same as if the publish's write genuinely had not landed yet.
        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"),
                null, null);

        // The publish's own write now lands after the dispose already finished, restoring real
        // content the sidecar reports back from here on.
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        when(reposiliteClient.fetchPackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"))).thenReturn(Optional.of(new byte[10]));

        service.forgetContainerBackedStatusOverrideIfUnchanged(REGION, "dom", null, "repo", "maven",
                "com.example", "a", "1.0", beforeRevision);

        verify(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"), eq("a-1.0.jar"));
        assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION, "dom", null, "repo", "maven",
                "com.example", "a", "1.0", "a-1.0.jar", null));
    }

    /**
     * Raised by Greptile's review of the fix above: the restored asset names were only ever
     * recorded into the override's own {@code pendingAssetNames} as each one's own delete
     * succeeded, never upfront before the first attempt. A failure on the very first restored
     * asset's delete therefore left {@code pendingAssetNames} exactly as the already-completed
     * disposal before it left it, almost always empty, so a later {@code DisposePackageVersions}
     * retry saw nothing pending and reported success with the restored file still real.
     */
    @Test
    void forgetContainerBackedStatusOverrideIfUnchangedSavesRestoredAssetNamesBeforeDeletingSoARetryCanFinish() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("maven");
        when(reposiliteClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0", Instant.ofEpochSecond(1000))));
        String beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null,
                "repo", "maven", "com.example", "a", "1.0");
        service.disposePackageVersions(REGION, "dom", null, "repo", "maven", "com.example", "a", List.of("1.0"),
                null, null);

        // The publish's own write restores real content after the dispose above already
        // finished, but this attempt to undo it fails on the restored asset's own delete.
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of("a-1.0.jar"));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                        eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"));
        assertThrows(IllegalStateException.class, () -> service.forgetContainerBackedStatusOverrideIfUnchanged(
                REGION, "dom", null, "repo", "maven", "com.example", "a", "1.0", beforeRevision));

        // The sidecar recovers; a fresh listing now reads empty (the same ambiguous signal a
        // real Reposilite instance could give here), so only the override's own saved
        // pendingAssetNames can tell a retry the restored file is still real.
        when(reposiliteClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), eq("com.example"),
                eq("a"), eq("1.0"))).thenReturn(List.of());
        doNothing().when(reposiliteClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"));

        CodeArtifactService.PackageVersionStatusChangeResult retry = service.disposePackageVersions(REGION, "dom",
                null, "repo", "maven", "com.example", "a", List.of("1.0"), null, null);

        assertEquals("Disposed", retry.successfulVersions().get("1.0").status());
        verify(reposiliteClient, times(2)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"),
                eq("com.example"), eq("a"), eq("1.0"), eq("a-1.0.jar"));
    }

    /**
     * Raised by Greptile's review of the fix above, for npm specifically: once a concurrent
     * disposal's own packument-removal half has already landed (Verdaccio's real two-step
     * unpublish, see {@code VerdaccioSidecarClient#deletePackageVersionAsset}'s own javadoc), a
     * fresh {@code listPackageVersionAssetNames} call goes blind to the tarball even though that
     * disposal's own {@code pendingAssetNames} still correctly names it (its own tarball delete
     * is what actually failed). Overwriting {@code pendingAssetNames} with only the fresh
     * (now-empty) list here would lose that name outright; the union of both must still name the
     * tarball for cleanup.
     */
    @Test
    void forgetContainerBackedStatusOverrideIfUnchangedMergesTheFreshListingWithWhatTheOverrideAlreadyKnows() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("npm");
        when(verdaccioClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        String beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0");
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my-pkg-1.0.0.tgz"));
        doThrow(new IllegalStateException("sidecar unreachable"))
                .when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                        eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));
        assertThrows(IllegalStateException.class, () -> service.disposePackageVersions(REGION, "dom", null, "repo",
                "npm", null, "my-pkg", List.of("1.0.0"), null, null));

        // Verdaccio's own packument no longer lists the version at all (its removal half of the
        // two-step unpublish already landed, even though the tarball delete above failed), so a
        // fresh listing now reads empty, exactly the ambiguous signal Greptile's review describes.
        when(verdaccioClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of());
        doNothing().when(verdaccioClient).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));

        service.forgetContainerBackedStatusOverrideIfUnchanged(REGION, "dom", null, "repo", "npm", null, "my-pkg",
                "1.0.0", beforeRevision);

        // Twice in total: the setup dispose above threw (counted on the mock regardless), this
        // call's own redispose is the one that actually finishes deleting the tarball.
        verify(verdaccioClient, times(2)).deletePackageVersionAsset(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"), eq("my-pkg-1.0.0.tgz"));
    }

    /**
     * Qodo review finding on this PR: a generic {@code publishPackageVersion} treated every
     * existing version whose status was not Published (including Disposed and Archived) exactly
     * like a brand new or Unfinished one, letting a publish silently resurrect a version the
     * User Guide says can never move back to Published once either status applies.
     */
    @Test
    void publishPackageVersionRejectsRepublishOfADisposedOrArchivedGenericVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "archived-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "generic", "ns", "archived-pkg",
                List.of("1.0.0"), null, null, "Archived");
        AwsException archivedError = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION,
                "dom", null, "repo", "generic", "ns", "archived-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content),
                "false", content));
        assertEquals("ConflictException", archivedError.getErrorCode());

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "disposed-pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.disposePackageVersions(REGION, "dom", null, "repo", "generic", "ns", "disposed-pkg",
                List.of("1.0.0"), null, null);
        AwsException disposedError = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION,
                "dom", null, "repo", "generic", "ns", "disposed-pkg", "1.0.0", "a.txt", Digests.sha256Hex(content),
                "false", content));
        assertEquals("ConflictException", disposedError.getErrorCode());
    }

    /**
     * Unlisted only hides a version from listings, and the User Guide never says republishing it
     * is blocked the way it does for Archived/Disposed, so this folds into the same
     * idempotent-replay rule Published already has: an exact byte-identical retry is a no-op, but
     * genuinely different content is still rejected, rather than silently rebuilding it as though
     * no version existed there at all (which the Unfinished fallthrough would otherwise do).
     */
    @Test
    void publishPackageVersionReplaysAnUnlistedVersionIdempotentlyButRejectsDifferentContent() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "v1".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        service.updatePackageVersionsStatus(REGION, "dom", null, "repo", "generic", "ns", "pkg", List.of("1.0.0"),
                null, null, "Unlisted");

        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "pkg", "1.0.0", "a.txt",
                Digests.sha256Hex(content), "false", content);
        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "pkg", "1.0.0");
        assertEquals("Unlisted", pv.getStatus());

        byte[] different = "v2".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", "ns", "pkg", "1.0.0", "a.txt", Digests.sha256Hex(different), "false", different));
        assertEquals("ConflictException", e.getErrorCode());
    }

    // --------------------------------------------- NuGet revision normalization

    @Test
    void describePackageVersionReturnsTheSameRevisionForEquivalentNuGetVersionSpellings() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(bagetterClient.packageVersionExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.00.0"))).thenReturn(true);
        when(bagetterClient.packageVersionExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(true);

        CodeArtifactPackageVersion fromPadded = service.describePackageVersion(REGION, "dom", null, "repo", "nuget",
                null, "my-pkg", "1.00.0");
        CodeArtifactPackageVersion fromNormalized = service.describePackageVersion(REGION, "dom", null, "repo",
                "nuget", null, "my-pkg", "1.0.0");

        assertEquals(fromNormalized.getRevision(), fromPadded.getRevision());
    }

    @Test
    void getPackageVersionAssetAcceptsARevisionObtainedFromAnEquivalentNuGetVersionSpelling() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "nupkg-bytes".getBytes(StandardCharsets.UTF_8);
        when(bagetterClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.00.0"), eq("my-pkg.1.0.0.nupkg"))).thenReturn(Optional.of(content));
        when(bagetterClient.fetchPackageVersionAsset(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"), eq("my-pkg.1.0.0.nupkg"))).thenReturn(Optional.of(content));

        PackageVersionAssetResult fromPadded = service.getPackageVersionAsset(REGION, "dom", null, "repo", "nuget",
                null, "my-pkg", "1.00.0", "my-pkg.1.0.0.nupkg", null);

        PackageVersionAssetResult pinned = service.getPackageVersionAsset(REGION, "dom", null, "repo", "nuget", null,
                "my-pkg", "1.0.0", "my-pkg.1.0.0.nupkg", fromPadded.packageVersionRevision());

        assertEquals(content.length, pinned.asset().getSize());
    }

    // ----------------------------------------------------------------- ListPackages

    @Test
    void listPackagesReturnsEmptyForARepositoryWithNothingPublished() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, null, null);

        assertTrue(result.packages().isEmpty());
        assertNull(result.nextToken());
    }

    @Test
    void listPackagesReturnsEveryGenericPackageInTheRepositoryWithDefaultOriginRestrictions() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        publishGeneric("repo", "other-pkg", "1.0.0", "b".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, null, null);

        Set<String> names = result.packages().stream().map(CodeArtifactService.PackageSummaryEntry::packageName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("my-pkg", "other-pkg"), names);
        result.packages().forEach(p -> {
            assertEquals("generic", p.format());
            assertEquals("ns", p.namespace());
            assertEquals("ALLOW", p.publishRestriction());
            assertEquals("BLOCK", p.upstreamRestriction());
        });
    }

    @Test
    void listPackagesIncludesAnOriginConfigOnlyPackageWithNoRealVersion() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "never-published",
                "BLOCK", "ALLOW");

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, null, null);

        assertEquals(1, result.packages().size());
        CodeArtifactService.PackageSummaryEntry entry = result.packages().get(0);
        assertEquals("never-published", entry.packageName());
        assertEquals("BLOCK", entry.publishRestriction());
        assertEquals("ALLOW", entry.upstreamRestriction());
    }

    @Test
    void listPackagesRoutesEveryContainerBackedFormatToTheSidecarBridge() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(reposiliteClient.listPackages(eq(created.getSidecarContainerIds().get("maven")), eq("dom"),
                eq("repo"))).thenReturn(List.of(
                        new RepositorySidecarManager.PackageIdentifier("com.example", "a")));
        when(verdaccioClient.listPackages(eq(created.getSidecarContainerIds().get("npm")), eq("dom"), eq("repo")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageIdentifier(null, "my-pkg")));
        when(pypiserverClient.listPackages(eq(created.getSidecarContainerIds().get("pypi")), eq("dom"), eq("repo")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageIdentifier(null, "my-pkg")));
        when(bagetterClient.listPackages(eq(created.getSidecarContainerIds().get("nuget")), eq("dom"), eq("repo")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageIdentifier(null, "my-pkg")));

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, null, null);

        assertEquals(4, result.packages().size());
        assertTrue(result.packages().stream().anyMatch(p -> "maven".equals(p.format()) && "a".equals(p.packageName())
                && "com.example".equals(p.namespace())));
        assertEquals(3, result.packages().stream().filter(p -> "my-pkg".equals(p.packageName())).count());
    }

    @Test
    void listPackagesFormatFilterOnlyTouchesThatOneFormatsSidecar() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.listPackages(eq(created.getSidecarContainerIds().get("pypi")), eq("dom"), eq("repo")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageIdentifier(null, "my-pkg")));

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", "pypi",
                null, null, null, null, null, null);

        assertEquals(1, result.packages().size());
        assertEquals("pypi", result.packages().get(0).format());
        verify(verdaccioClient, never()).listPackages(anyString(), anyString(), anyString());
        verify(bagetterClient, never()).listPackages(anyString(), anyString(), anyString());
        verify(reposiliteClient, never()).listPackages(anyString(), anyString(), anyString());
    }

    @Test
    void listPackagesNamespacePrefixFiltersAndRequiresFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "my-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));

        AwsException e = assertThrows(AwsException.class, () -> service.listPackages(REGION, "dom", null, "repo",
                null, "n", null, null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());

        CodeArtifactService.ListPackagesResult matches = service.listPackages(REGION, "dom", null, "repo", "generic",
                "n", null, null, null, null, null);
        assertEquals(1, matches.packages().size());

        CodeArtifactService.ListPackagesResult noMatches = service.listPackages(REGION, "dom", null, "repo",
                "generic", "other", null, null, null, null, null);
        assertTrue(noMatches.packages().isEmpty());
    }

    @Test
    void listPackagesPackagePrefixFiltersByName() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "pat-test", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        publishGeneric("repo", "other", "1.0.0", "b".getBytes(StandardCharsets.UTF_8));

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null, null,
                "pat", null, null, null, null);

        assertEquals(1, result.packages().size());
        assertEquals("pat-test", result.packages().get(0).packageName());
    }

    @Test
    void listPackagesPublishAndUpstreamFilterByResolvedRestriction() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "default-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        publishGeneric("repo", "blocked-pkg", "1.0.0", "b".getBytes(StandardCharsets.UTF_8));
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "ns", "blocked-pkg", "BLOCK",
                "ALLOW");

        CodeArtifactService.ListPackagesResult allowPublish = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, "ALLOW", null);
        assertEquals(1, allowPublish.packages().size());
        assertEquals("default-pkg", allowPublish.packages().get(0).packageName());

        CodeArtifactService.ListPackagesResult allowUpstream = service.listPackages(REGION, "dom", null, "repo",
                null, null, null, null, null, null, "ALLOW");
        assertEquals(1, allowUpstream.packages().size());
        assertEquals("blocked-pkg", allowUpstream.packages().get(0).packageName());
    }

    /**
     * Raised by Greptile's review: a pypi package's origin configuration is stored under whatever
     * spelling the caller set it with, but the real sidecar always lists the package under its own
     * PEP 503-normalized spelling. Without normalizing both sides to the same form before union-ing
     * them, the two would appear as two distinct packages, and the normalized one's own
     * restrictions would never resolve to what was actually configured.
     */
    @Test
    void listPackagesNormalizesAPypiPackageSoAnOriginConfigSetUnderADifferentSpellingIsNotDuplicatedOrLost() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(pypiserverClient.listPackages(eq(created.getSidecarContainerIds().get("pypi")), eq("dom"), eq("repo")))
                .thenReturn(List.of(new RepositorySidecarManager.PackageIdentifier(null, "my-proj")));
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "pypi", null, "My_Proj", "BLOCK", "ALLOW");

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", "pypi",
                null, null, null, null, null, null);

        assertEquals(1, result.packages().size());
        assertEquals("my-proj", result.packages().get(0).packageName());
        assertEquals("BLOCK", result.packages().get(0).publishRestriction());
        assertEquals("ALLOW", result.packages().get(0).upstreamRestriction());
    }

    /**
     * Raised by Greptile's review: {@code packageSortCursor} used to join its three fields with a
     * plain {@code ::}, which namespace/package names are allowed to contain themselves, so two
     * different coordinates could collide on the exact same cursor and {@code Pagination}'s
     * strictly-greater-cursor resume would then skip whichever one sorted second.
     */
    @Test
    void listPackagesPagesPastACursorCollisionWithoutSkippingEitherPackage() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "a::b", "c", "ALLOW", "BLOCK");
        service.putPackageOriginConfiguration(REGION, "dom", null, "repo", "generic", "a", "b::c", "ALLOW", "BLOCK");

        CodeArtifactService.ListPackagesResult firstPage = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, 1, null, null, null);
        assertEquals(1, firstPage.packages().size());
        assertNotNull(firstPage.nextToken());

        CodeArtifactService.ListPackagesResult secondPage = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, 1, firstPage.nextToken(), null, null);
        assertEquals(1, secondPage.packages().size());

        assertNotEquals(firstPage.packages().get(0).namespace() + "/" + firstPage.packages().get(0).packageName(),
                secondPage.packages().get(0).namespace() + "/" + secondPage.packages().get(0).packageName());
    }

    /**
     * Raised by Greptile's review: a {@link PackageOriginConfig} persisted before {@code format}/
     * {@code namespace}/{@code packageName} existed on it has none of the three, so {@code
     * allPackagesInRepository}'s own normalized map can never resolve it. The package it configures
     * is still found here through its real {@code generic} version record, so its restriction must
     * still resolve to what was actually saved, not silently fall back to the ALLOW/BLOCK default.
     */
    @Test
    void listPackagesFallsBackToTheRawKeyLookupForAnOriginConfigPersistedBeforeItCarriedItsOwnCoordinate() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        publishGeneric("repo", "legacy-pkg", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        String legacyRawKey = REGION + "::dom::repo::generic::ns::legacy-pkg";
        packageOriginConfigStore.putForAccount(ACCOUNT_ID, legacyRawKey,
                new PackageOriginConfig("BLOCK", "ALLOW", null, null, null));

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, null, null);

        assertEquals(1, result.packages().size());
        assertEquals("BLOCK", result.packages().get(0).publishRestriction());
        assertEquals("ALLOW", result.packages().get(0).upstreamRestriction());

        CodeArtifactService.ListPackagesResult blockedOnly = service.listPackages(REGION, "dom", null, "repo", null,
                null, null, null, null, "BLOCK", null);
        assertEquals(1, blockedOnly.packages().size());
    }

    /**
     * Raised by Greptile's review: disposing a pypi package's only version deletes its real
     * sidecar content, so the sidecar's own listing no longer names it, but {@code
     * disposePackageVersions} leaves a status override behind precisely so {@code
     * ListPackageVersions} can keep reporting it as {@code Disposed}. With no origin configuration
     * either, nothing else in the union would otherwise keep this package in {@code ListPackages}.
     */
    @Test
    void listPackagesIncludesAPypiPackageWhoseOnlyVersionWasDisposed() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String repoId = created.getSidecarContainerIds().get("pypi");
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of(
                        new RepositorySidecarManager.PackageVersionTimestamp("1.0.0", Instant.ofEpochSecond(1000))));
        when(pypiserverClient.listPackageVersionAssetNames(eq(repoId), eq("dom"), eq("repo"), isNull(),
                eq("my-pkg"), eq("1.0.0"))).thenReturn(List.of("my_pkg-1.0.0.tar.gz"));
        service.disposePackageVersions(REGION, "dom", null, "repo", "pypi", null, "my-pkg", List.of("1.0.0"), null,
                null);
        when(pypiserverClient.listPackageVersions(eq(repoId), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(List.of());

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", "pypi",
                null, null, null, null, null, null);

        assertEquals(1, result.packages().size());
        assertEquals("my-pkg", result.packages().get(0).packageName());
    }

    @Test
    void listPackagesDefaultPageSizeIsOneHundredNotFifty() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        for (int i = 0; i < 120; i++) {
            publishGeneric("repo", String.format("pkg-%03d", i), "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        }

        CodeArtifactService.ListPackagesResult result = service.listPackages(REGION, "dom", null, "repo", null, null,
                null, null, null, null, null);

        assertEquals(100, result.packages().size());
        assertNotNull(result.nextToken());
    }

    @Test
    void listPackagesRejectsAnInvalidFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.listPackages(REGION, "dom", null, "repo",
                "not-a-format", null, null, null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    // ---------------------------------------------------------- GetPackageVersionReadme

    @Test
    void getPackageVersionReadmeRejectsAFormatAwsDoesNotSupportForThisAction() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        // AWS's own API reference restricts this one action's format parameter to npm/pypi/nuget,
        // narrower than every other package-version action here, which accepts the full 8-value
        // set: maven, swift, generic, ruby, and cargo package versions carry no readme in real
        // CodeArtifact at all, so every one of them is checked here, not just maven.
        for (String format : new String[] {"maven", "swift", "generic", "ruby", "cargo"}) {
            AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom",
                    null, "repo", format, "com.example", "my-artifact", "1.0.0"), format);
            assertEquals("ValidationException", e.getErrorCode(), format);
        }
    }

    /**
     * npm, like pypi, can only ever be answered from what was captured at publish time: confirmed
     * directly against a live Verdaccio container that it hoists {@code readme} to the top level
     * only (the single most-recently-published version), dropping the per-version field a real
     * npm client's own publish envelope actually sends. See
     * {@link CodeArtifactNpmDataPlane#forwardSpooled} for where this is captured for real.
     */
    @Test
    void getPackageVersionReadmeReturnsWhatWasCapturedAtNpmPublishTime() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "npm", "myscope", "my-pkg", "1.0.0", "# My Package");

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", "myscope", "my-pkg", "1.0.0");

        assertEquals("# My Package", result.readme());
        assertEquals("my-pkg", result.packageName());
        assertEquals("1.0.0", result.version());
    }

    @Test
    void getPackageVersionReadme404sForNpmWhenNeverCaptured() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "9.9.9"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("package-version", e.getExtendedData().get("resourceType"));
    }

    @Test
    void getPackageVersionReadmeReturnsWhatWasCapturedAtPypiUploadTime() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "pypi", null, "my-pkg", "1.0.0", "Long description in reStructuredText.");

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "1.0.0");

        assertEquals("Long description in reStructuredText.", result.readme());
    }

    @Test
    void getPackageVersionReadme404sForPypiWhenNeverCaptured() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "never-published", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * Unlike npm and pypi, nuget is answered live against its sidecar (see {@code
     * BagetterSidecarClient#fetchPackageVersionReadme}'s own javadoc for why BaGetter can support this
     * where Verdaccio and pypiserver can't), so this never goes through {@code
     * recordPublishedPackageVersionReadme}/{@code packageVersionReadmes} the way npm/pypi do.
     */
    @Test
    void getPackageVersionReadmeReturnsWhatTheNugetSidecarAnswersLive() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(bagetterClient.fetchPackageVersionReadme(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(Optional.of("# My Package"));

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "nuget", null, "my-pkg", "1.0.0");

        assertEquals("# My Package", result.readme());
        assertEquals("my-pkg", result.packageName());
        assertEquals("1.0.0", result.version());
    }

    /** A published nuget version with no embedded readme or description answers "", not 404. */
    @Test
    void getPackageVersionReadmeReturnsEmptyStringForANugetVersionWithNoReadmeOrDescription() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(bagetterClient.fetchPackageVersionReadme(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(Optional.of(""));

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "nuget", null, "my-pkg", "1.0.0");

        assertEquals("", result.readme());
    }

    @Test
    void getPackageVersionReadme404sForNugetWhenTheSidecarHasNeverPublishedIt() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        when(bagetterClient.fetchPackageVersionReadme(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg"),
                eq("1.0.0"))).thenReturn(Optional.empty());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "nuget", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * Found by Qodo review: a caller-supplied namespace for pypi or nuget, neither of which has
     * one, used to pass this method's own validation (it only checked namespace's token syntax)
     * and reach the sidecar lookup, which ignores the namespace entirely and would echo it back
     * in the response as though it belonged to the package.
     */
    @Test
    void getPackageVersionReadmeRejectsANamespaceForNuget() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "nuget", "ns", "my-pkg", "1.0.0"));
        assertEquals("ValidationException", e.getErrorCode());
        verify(bagetterClient, never()).fetchPackageVersionReadme(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getPackageVersionReadmeRejectsANamespaceForPypi() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", "ns", "my-pkg", "1.0.0"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void recordPublishedPackageVersionReadmeSkipsAPackageNameContainingTheEscapeCharacterWithoutCorruptingOthers() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        // The escape character itself can never reach escapeKeySegment's own key building, the
        // same way validatePackageToken already keeps it out of every JSON-API coordinate: this
        // call is just dropped, logged, and never throws back into the sidecar upload it reports.
        recordReadme("dom", "repo", "pypi", null, "bad#name", "1.0.0", "bad text");
        recordReadme("dom", "repo", "pypi", null, "legit", "1.0.0", "legit text");

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "legit", "1.0.0");
        assertEquals("legit text", result.readme());
    }

    @Test
    void recordPublishedPackageVersionReadmeAfterTheRepositoryIsAlreadyDeletedIsIgnored() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.deleteRepository(REGION, "dom", null, "repo");

        recordReadme("dom", "repo", "pypi", null, "my-pkg", "1.0.0", "late text");

        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteRepositoryRemovesItsCapturedPypiReadmesSoARecreatedRepositoryDoesNotInheritThem() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "pypi", null, "my-pkg", "1.0.0", "old text");

        service.deleteRepository(REGION, "dom", null, "repo");
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deletePackageRemovesItsCapturedPypiReadmesSoARepublishedPackageDoesNotInheritThem() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "pypi", null, "my-pkg", "1.0.0", "old text");
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-pkg")))
                .thenReturn(true);

        service.deletePackage(REGION, "dom", null, "repo", "pypi", null, "my-pkg");

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void forgetContainerBackedPackageRemovesItsCapturedNpmReadmeTooSoAReplacedPackageDoesNotInheritIt() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "npm", null, "my-pkg", "1.0.0", "old text");

        service.forgetContainerBackedPackage(REGION, "dom", null, "repo", "npm", null, "my-pkg");

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * {@code getPackageVersionReadmeRejectsAFormatAwsDoesNotSupportForThisAction} above only
     * covers the format check; a Qodo review found this method's other required-field validation
     * (domain/repository/package/version) had no regression coverage of its own, nor any proof
     * that a rejected request leaves a previously captured readme untouched.
     */
    @Test
    void getPackageVersionReadmeRejectsMissingRequiredFieldsWithoutChangingStoredState() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "npm", null, "my-pkg", "1.0.0", "untouched text");

        AwsException missingDomain = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION,
                null, null, "repo", "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ValidationException", missingDomain.getErrorCode());
        AwsException missingRepository = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(
                REGION, "dom", null, null, "npm", null, "my-pkg", "1.0.0"));
        assertEquals("ValidationException", missingRepository.getErrorCode());
        AwsException missingPackage = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION,
                "dom", null, "repo", "npm", null, null, "1.0.0"));
        assertEquals("ValidationException", missingPackage.getErrorCode());
        AwsException missingVersion = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION,
                "dom", null, "repo", "npm", null, "my-pkg", null));
        assertEquals("ValidationException", missingVersion.getErrorCode());

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0");
        assertEquals("untouched text", result.readme());
    }

    /**
     * Same race {@code recordContainerBackedAssetBytesAfterAConcurrentDeletePackageIsIgnored}
     * already closes for byte accounting, found again by Qodo review for readme capture: a delayed
     * npm callback's readme capture must not resurrect a readme for a package a concurrent
     * {@code DeletePackage} already removed.
     */
    @Test
    void recordPublishedPackageVersionReadmeAfterAConcurrentDeletePackageIsIgnored() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        long fence = service.currentContainerBackedPackageFence(REGION, "dom", null, "repo", "npm", null, "late");
        when(verdaccioClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("late")))
                .thenReturn(true);
        service.deletePackage(REGION, "dom", null, "repo", "npm", null, "late");

        service.recordPublishedPackageVersionReadme(REGION, "dom", null, "repo", "npm", null, "late", "1.0.0",
                "too late", fence);

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "npm", null, "late", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    /**
     * Found by Qodo review: pypi readme capture/lookup/deletion all used the package name exactly
     * as the caller spelled it, while {@code accountingPackageName} (and so byte accounting, the
     * fence, and sidecar resolution itself) treats case- and {@code -_.}-equivalent spellings as
     * the same pypi package. A readme captured under one spelling has to be answerable, and
     * deletable, under any PEP 503-equivalent spelling of the same name.
     */
    @Test
    void getPackageVersionReadmeResolvesPypiSpellingVariantsToTheSameReadme() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "pypi", null, "My_Package", "1.0.0", "the readme");

        CodeArtifactService.PackageVersionReadmeResult result = service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "my-package", "1.0.0");

        assertEquals("the readme", result.readme());
        // Echoes the caller's own spelling back, not the normalized one used for the lookup.
        assertEquals("my-package", result.packageName());
    }

    @Test
    void deletePackageRemovesAPypiReadmeCapturedUnderADifferentSpelling() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        recordReadme("dom", "repo", "pypi", null, "My.Package", "1.0.0", "the readme");
        when(pypiserverClient.packageExists(anyString(), eq("dom"), eq("repo"), isNull(), eq("my-package")))
                .thenReturn(true);

        service.deletePackage(REGION, "dom", null, "repo", "pypi", null, "my-package");

        AwsException e = assertThrows(AwsException.class, () -> service.getPackageVersionReadme(REGION, "dom", null,
                "repo", "pypi", null, "My_Package", "1.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void concurrentUnfinishedPublishesOfDifferentAssetsBothPersist() throws InterruptedException {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        int assetCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(assetCount);
        CountDownLatch ready = new CountDownLatch(assetCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < assetCount; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        byte[] content = ("content-" + index).getBytes(StandardCharsets.UTF_8);
                        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg",
                                "1.0.0", "asset-" + index + ".txt", Digests.sha256Hex(content), "true", content);
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
        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0");
        assertEquals(assetCount, pv.getAssets().size());
    }
}
