package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactMavenExternalConnectionImporter}'s own decision logic: when it attempts a
 * real external-connection fetch at all, which asset names it tries, and what it writes back.
 * {@link ReposiliteSidecarClient} and {@link MavenExternalConnectionClient} are both mocked, so
 * no real network or Reposilite call ever happens here; their own wire contracts have their own
 * tests.
 */
class CodeArtifactMavenExternalConnectionImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";

    private CodeArtifactService service;
    private ReposiliteSidecarClient reposilite;
    private MavenExternalConnectionClient externalConnectionClient;
    private CodeArtifactMavenExternalConnectionImporter importer;

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        reposilite = mock(ReposiliteSidecarClient.class);
        externalConnectionClient = mock(MavenExternalConnectionClient.class);
        importer = new CodeArtifactMavenExternalConnectionImporter(service, reposilite, externalConnectionClient);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(
                List.of(new ExternalConnection("public:maven-central", "maven", "AVAILABLE")));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.mavenRetainedVersionOriginConnection(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        // Unstubbed by default: the claim succeeds, matching the common case of a first or
        // same-connection retry. Tests for a conflicting claim override this per call.
        when(service.claimMavenRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new CodeArtifactService.MavenClaimOutcome(true, EPISODE_ID));
        // Unstubbed by default: a constant snapshot, so a version-fence mismatch only ever
        // happens where a test deliberately stubs deployMavenAssetUnderVersionFence to report
        // one. The dispose-race test below overrides this per call.
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        // Unstubbed by default: the fence matched and the write landed (201), matching the
        // common case. The real deploy/fence check this stands in for is inside
        // deployMavenAssetUnderVersionFence itself, a synchronized CodeArtifactService method
        // (see its own javadoc for why), so this mock is what every test configures instead.
        // Tests for the idempotent-match, conflict, fence-mismatch, and error cases override
        // this per call via stubDeploy.
        when(service.deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), any())).thenReturn(new CodeArtifactService.MavenDeployAttemptResult(true,
                        new ReposiliteSidecarClient.DeployOutcome(201, true)));
    }

    /**
     * Stubs the single candidate named by {@code gav}/{@code content}; every other candidate
     * keeps the default stub from {@link #setUp}.
     */
    private void stubDeploy(String gav, byte[] content, CodeArtifactService.MavenDeployAttemptResult result) {
        when(service.deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), eq(reposilite), eq(REPO_ID),
                eq(gav), eq(content))).thenReturn(result);
    }

    /** Verifies the call for {@code gav}/{@code content} also passed the package fence through. */
    private void verifyDeployPassedPackageFence(String gav, byte[] content, long packageFenceSnapshot) {
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                anyString(), anyString(), anyString(), anyLong(), eq(packageFenceSnapshot), anyLong(), eq(reposilite),
                eq(REPO_ID), eq(gav), eq(content));
    }

    @Test
    void resolvesMetadataThroughTheExternalConnectionWhenNotFoundLocally() {
        byte[] metadataBytes = "<metadata/>".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/maven-metadata.xml"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(metadataBytes)));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq("com.example"), eq("widget"), anyLong(), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any()))
                .thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml");

        assertTrue(imported);
    }

    /**
     * Raised by Greptile's review: the real fetch from the public repository can take long enough
     * for a concurrent {@code DeletePackage} to finish while it is still in flight, so the fence
     * snapshot passed to {@link CodeArtifactService#mergeMavenMetadataUnderPackageFence} must be
     * taken before that fetch, not after, or a delete landing in between would never be caught by
     * the check that runs inside that same call.
     */
    @Test
    void takesThePackageFenceSnapshotBeforeFetchingFromThePublicRepository() {
        byte[] metadataBytes = "<metadata/>".getBytes();
        when(service.currentContainerBackedPackageFence(REGION, DOMAIN, OWNER, REPOSITORY, "maven", "com.example",
                "widget")).thenReturn(42L);
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/maven-metadata.xml"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(metadataBytes)));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq("com.example"), eq("widget"), eq(42L), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any()))
                .thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml");

        assertTrue(imported);
        verify(service).mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq("com.example"), eq("widget"), eq(42L), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any());
    }

    /**
     * {@link CodeArtifactMavenExternalConnectionImporter#tryImportMetadata} builds its own
     * {@code versionAllowed} predicate from {@link
     * CodeArtifactService#requireContainerBackedVersionDownloadable}, the exact same existence
     * check a per-version download itself already enforces: a version this repository knows is
     * {@code Archived}/{@code Disposed} throws from that check and must come back {@code false},
     * while a version with no override at all (the common case: nothing here has ever recorded
     * one) must come back {@code true}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void versionAllowedPredicateRejectsOnlyVersionsTheServiceReportsNotDownloadable() {
        byte[] metadataBytes = "<metadata/>".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/maven-metadata.xml"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(metadataBytes)));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq("com.example"), eq("widget"), anyLong(), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any()))
                .thenReturn(true);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, REPOSITORY, "maven",
                        "com.example", "widget", "1.0-disposed", REPO_ID);

        importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "com/example/widget/maven-metadata.xml");

        ArgumentCaptor<Predicate<String>> captor = ArgumentCaptor.forClass(Predicate.class);
        verify(service).mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq("com.example"), eq("widget"), anyLong(), eq(reposilite), eq(REPO_ID), eq(metadataBytes),
                captor.capture());
        Predicate<String> versionAllowed = captor.getValue();
        assertFalse(versionAllowed.test("1.0-disposed"));
        assertTrue(versionAllowed.test("1.0"));
    }

    @Test
    void doesNothingForABareMavenMetadataPathWhenTheExternalSourceHasNone() {
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/maven-metadata.xml"))
                .thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml");

        assertFalse(imported);
        verify(service, never()).mergeMavenMetadataUnderPackageFence(any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any(), any(), any());
    }

    @Test
    void doesNothingForAMavenMetadataChecksumSiblingPath() {
        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml.sha1");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingForMavenMetadataWhenTheRepositoryHasNoMavenExternalConnection() {
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(new CodeArtifactRepository());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingForMavenMetadataWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "maven", "com.example",
                "widget")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/maven-metadata.xml");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingForASnapshotVersion() {
        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0-SNAPSHOT/widget-1.0-20231001.123456-1.jar");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingWhenTheRepositoryHasNoMavenExternalConnection() {
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(new CodeArtifactRepository());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "maven", "com.example",
                "widget")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void doesNothingWhenTheVersionWasAlreadyRetainedFromADifferentConnection() {
        // The real service refuses the claim outright when a different connection already holds
        // it (see claimMavenRetainedVersionOrigin's own javadoc); the mock stands in for that here.
        when(service.claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "com.example", "widget",
                "1.0", "public:maven-central", 42L))
                .thenReturn(new CodeArtifactService.MavenClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchAsset(anyString(), anyString());
    }

    @Test
    void aStandardAssetNameImportsAllFourStandardAssetsBestEffort() {
        byte[] pomBytes = "pom-content".getBytes();
        byte[] jarBytes = "jar".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.pom"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(pomBytes)));
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(jarBytes)));
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-javadoc.jar")).thenReturn(Optional.empty());
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-sources.jar")).thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertTrue(imported);
        // packageFenceSnapshot (42L) is passed through so deployMavenAssetUnderVersionFence can
        // record bytes atomically with the write itself; see its own javadoc, and
        // CodeArtifactServiceTest for the byte-accounting behavior this stands in for here.
        verifyDeployPassedPackageFence("com/example/widget/1.0/widget-1.0.pom", pomBytes, 42L);
        verifyDeployPassedPackageFence("com/example/widget/1.0/widget-1.0.jar", jarBytes, 42L);
        verify(service, never()).deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), eq("com/example/widget/1.0/widget-1.0-javadoc.jar"), any());
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "com.example", "widget",
                "1.0", "public:maven-central", 42L);
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "maven", "com.example",
                "widget", 42L);
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, true);
    }

    /**
     * Taking the version-fence snapshot after the claim instead of before it would leave a gap
     * where a DisposePackageVersions completing in between would already have bumped the fence by
     * the time the snapshot read ran, so that read would capture the post-disposal value as
     * though it were the normal starting point, and the later locked write would never notice
     * anything was amiss. The snapshot must be taken first, so any disposal landing anywhere after
     * it, including during the claim call itself, is guaranteed to bump the fence to a value the
     * later check can actually detect as different.
     */
    @Test
    void takesTheVersionFenceSnapshotBeforeClaimingNotAfter() {
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset("jar".getBytes())));

        importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "com/example/widget/1.0/widget-1.0.jar");

        InOrder order = inOrder(service);
        order.verify(service).currentContainerBackedVersionFence(REGION, DOMAIN, OWNER, REPOSITORY, "maven",
                "com.example", "widget", "1.0");
        order.verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", "public:maven-central", 42L);
    }

    @Test
    void anIdempotentMatchCountsAsImportedButIsNotDoubleCountedInByteAccounting() {
        byte[] jarBytes = "jar".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(jarBytes)));
        // deployArtifact's own idempotent-match short-circuit: status 200, wroteNewContent false,
        // because the exact same bytes were already there (e.g. a sibling asset imported it moments
        // earlier in a race with another request for the same version).
        stubDeploy("com/example/widget/1.0/widget-1.0.jar", jarBytes,
                new CodeArtifactService.MavenDeployAttemptResult(true,
                        new ReposiliteSidecarClient.DeployOutcome(200, false)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertTrue(imported);
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "com.example", "widget",
                "1.0", "public:maven-central", 42L);
        // Something was genuinely retained (the idempotent match is still a real asset present
        // either way), so the claim is finished as confirmed, not released. Whether the
        // idempotent match itself double-counts bytes is deployMavenAssetUnderVersionFence's own
        // concern now, covered in CodeArtifactServiceTest.
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, true);
    }

    @Test
    void aRealDeployConflictOnTheRequestedAssetCountsAsNotImported() {
        byte[] jarBytes = "jar".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(jarBytes)));
        stubDeploy("com/example/widget/1.0/widget-1.0.jar", jarBytes,
                new CodeArtifactService.MavenDeployAttemptResult(true,
                        new ReposiliteSidecarClient.DeployOutcome(409, false)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        // Nothing was actually retained (the only candidate's deploy hit a real conflict), so the
        // claim this attempt took is finished as unconfirmed, never left dangling to block a
        // later attempt.
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, false);
    }

    @Test
    void aLaterCandidateThatThrowsStillFinalizesWhatAnEarlierCandidateGenuinelyWrote() {
        byte[] pomBytes = "pom-content".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.pom"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(pomBytes)));
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset("jar".getBytes())));
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-javadoc.jar")).thenReturn(Optional.empty());
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-sources.jar")).thenReturn(Optional.empty());
        // The POM deploy succeeds, but the JAR deploy (the requested asset) fails inside the
        // sidecar call itself, e.g. it going unreachable mid-import: deployMavenAssetUnderVersionFence
        // catches that on the real path and reports a matched fence with no outcome, exactly as
        // stubbed here.
        stubDeploy("com/example/widget/1.0/widget-1.0.jar", "jar".getBytes(),
                new CodeArtifactService.MavenDeployAttemptResult(true, null));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        // The requested asset itself never landed, but what the POM candidate genuinely wrote
        // before the JAR candidate threw is still finalized, not silently dropped.
        assertFalse(imported);
        verifyDeployPassedPackageFence("com/example/widget/1.0/widget-1.0.pom", pomBytes, 42L);
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "maven", "com.example",
                "widget", 42L);
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, true);
    }

    @Test
    void aCandidateFetchThatThrowsIsTreatedLikeAMissingCandidateRatherThanAbortingTheWholeAttempt() {
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.pom"))
                .thenThrow(new IllegalStateException("public repository unreachable"));
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.jar"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset("jar".getBytes())));
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-javadoc.jar")).thenReturn(Optional.empty());
        when(externalConnectionClient.fetchAsset("public:maven-central",
                "com/example/widget/1.0/widget-1.0-sources.jar")).thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertTrue(imported);
        verify(service, never()).deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), eq("com/example/widget/1.0/widget-1.0.pom"), any());
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), eq(reposilite), eq(REPO_ID),
                eq("com/example/widget/1.0/widget-1.0.jar"), eq("jar".getBytes()));
    }

    @Test
    void aDisposalLandingWhileAFetchIsInFlightStopsEveryLaterWriteForThisAttempt() {
        byte[] pomBytes = "pom-content".getBytes();
        when(externalConnectionClient.fetchAsset("public:maven-central", "com/example/widget/1.0/widget-1.0.pom"))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset(pomBytes)));
        // A DisposePackageVersions landed on this exact version while the first candidate's
        // (the pom's) fetch was in flight: deployMavenAssetUnderVersionFence detects the fence
        // mismatch itself (inside its own lock) and reports it rather than writing anything, so
        // every later candidate, including the requested jar, is never even fetched.
        stubDeploy("com/example/widget/1.0/widget-1.0.pom", pomBytes,
                new CodeArtifactService.MavenDeployAttemptResult(false, null));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(service, never()).deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), eq("com/example/widget/1.0/widget-1.0.jar"), any());
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, false);
    }

    @Test
    void aNonStandardAssetNameOnlyImportsTheExactRequestedAsset() {
        String nonStandardGav = "com/example/widget/1.0/widget-1.0-windows.jar";
        when(externalConnectionClient.fetchAsset("public:maven-central", nonStandardGav))
                .thenReturn(Optional.of(new MavenExternalConnectionClient.FetchedAsset("windows-jar".getBytes())));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, nonStandardGav);

        assertTrue(imported);
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), eq(reposilite), eq(REPO_ID),
                eq(nonStandardGav), eq("windows-jar".getBytes()));
        verify(externalConnectionClient, never()).fetchAsset(eq("public:maven-central"),
                eq("com/example/widget/1.0/widget-1.0.pom"));
    }

    @Test
    void returnsFalseWhenTheRequestedAssetItselfIsNotFoundEvenIfItWouldHaveBeenAStandardImport() {
        when(externalConnectionClient.fetchAsset(anyString(), anyString())).thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "com/example/widget/1.0/widget-1.0.jar");

        assertFalse(imported);
        verify(service, never()).deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), anyString(), any());
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "com.example",
                "widget", "1.0", EPISODE_ID, false);
    }

    @Test
    void existsInExternalConnectionChecksTheRealPublicRepositoryForAReleaseVersion() {
        // Checks the version's own standard POM, not the literal requested filename: every real
        // published Maven version has one, while a non-standard classifier asset may not.
        when(externalConnectionClient.exists("public:maven-central", "com/example/widget/1.0/widget-1.0.pom"))
                .thenReturn(true);

        assertTrue(importer.existsInExternalConnection(REGION, DOMAIN, OWNER, REPOSITORY,
                "com/example/widget/1.0/widget-1.0.jar"));
    }

    @Test
    void existsInExternalConnectionIsTrueWhenTheVersionIsAlreadyClaimedUnderThisConnectionLocally() {
        when(service.mavenRetainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "com.example", "widget",
                "1.0")).thenReturn(Optional.of("public:maven-central"));

        assertTrue(importer.existsInExternalConnection(REGION, DOMAIN, OWNER, REPOSITORY,
                "com/example/widget/1.0/widget-1.0-windows.jar"));
        verify(externalConnectionClient, never()).exists(anyString(), anyString());
    }

    @Test
    void existsInExternalConnectionNeverChecksASnapshotVersion() {
        assertFalse(importer.existsInExternalConnection(REGION, DOMAIN, OWNER, REPOSITORY,
                "com/example/widget/1.0-SNAPSHOT/widget-1.0-20231001.123456-1.jar"));
        verify(externalConnectionClient, never()).exists(anyString(), anyString());
    }
}
