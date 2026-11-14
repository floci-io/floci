package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactMavenUpstreamRepositoryImporter}'s own decision logic: which candidate it
 * resolves an asset or a metadata merge from, and what it writes back. {@link
 * ReposiliteSidecarClient} is mocked, so no real Reposilite call ever happens here; its own wire
 * contract has its own tests.
 */
class CodeArtifactMavenUpstreamRepositoryImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String UPSTREAM = "upstream";
    private static final String UPSTREAM_REPO_ID = "upstream-repo-id";
    private static final String NAMESPACE = "com.example";
    private static final String PACKAGE_NAME = "widget";
    private static final String VERSION = "1.0";
    private static final String GAV = "com/example/widget/1.0/widget-1.0.jar";

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private ReposiliteSidecarClient reposilite;
    private CodeArtifactMavenUpstreamRepositoryImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        reposilite = mock(ReposiliteSidecarClient.class);
        importer = new CodeArtifactMavenUpstreamRepositoryImporter(service, reposilite);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of(UPSTREAM));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("INTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(List.of(UPSTREAM));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, UPSTREAM, reposilite))
                .thenReturn(UPSTREAM_REPO_ID);
        when(reposilite.artifactExists(UPSTREAM_REPO_ID, GAV)).thenReturn(true);
        // Unstubbed by default: requireContainerBackedVersionDownloadable is a void method a mock
        // never throws from unless told to, matching "the candidate's version is downloadable."
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimMavenRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), eq(RetentionOriginKind.UPSTREAM_REPOSITORY), anyLong()))
                .thenReturn(new CodeArtifactService.MavenClaimOutcome(true, EPISODE_ID));
        when(service.stageAssetBody(any(InputStream.class))).thenAnswer(invocation -> {
            Path staged = Files.createTempFile("test-maven-upstream-", ".bin");
            InputStream in = invocation.getArgument(0);
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            return staged;
        });
        when(service.deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), any(), anyLong())).thenReturn(new CodeArtifactService.MavenDeployAttemptResult(true,
                        new ReposiliteSidecarClient.DeployOutcome(201, true)));
    }

    private void stubFetchArtifact(String repoId, String gav, byte[] content) {
        when(reposilite.fetchArtifactStreamed(repoId, gav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(content), content.length, "application/octet-stream")));
    }

    @Test
    void importsFromTheDirectUpstreamWhenNotFoundLocally() {
        byte[] jarBytes = "jar-bytes".getBytes();
        stubFetchArtifact(UPSTREAM_REPO_ID, GAV, jarBytes);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertTrue(imported);
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(reposilite),
                eq(REPO_ID), eq(GAV), any(Path.class), eq((long) jarBytes.length));
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "maven", NAMESPACE,
                PACKAGE_NAME, 42L);
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * A standard asset name (the {@code .jar} itself) imports its 3 standard siblings (pom,
     * javadoc, sources) together, carried over verbatim from the external-connection importer's
     * own shape; a missing sibling (javadoc, here) is normal, not a failure.
     */
    @Test
    void importsStandardSiblingsTogetherAndTheMissingOneIsNotAFailure() {
        String pomGav = "com/example/widget/1.0/widget-1.0.pom";
        String jarGav = GAV;
        String sourcesGav = "com/example/widget/1.0/widget-1.0-sources.jar";
        String javadocGav = "com/example/widget/1.0/widget-1.0-javadoc.jar";
        byte[] pomBytes = "pom-bytes".getBytes();
        byte[] jarBytes = "jar-bytes".getBytes();
        byte[] sourcesBytes = "sources-bytes".getBytes();
        stubFetchArtifact(UPSTREAM_REPO_ID, pomGav, pomBytes);
        stubFetchArtifact(UPSTREAM_REPO_ID, jarGav, jarBytes);
        stubFetchArtifact(UPSTREAM_REPO_ID, sourcesGav, sourcesBytes);
        // javadocGav is left unstubbed: fetchArtifactStreamed defaults to Optional.empty(), the
        // real "this real repository just doesn't have this standard sibling" shape.

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, jarGav);

        assertTrue(imported);
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), anyLong(), eq(reposilite),
                eq(REPO_ID), eq(pomGav), any(Path.class), eq((long) pomBytes.length));
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), anyLong(), eq(reposilite),
                eq(REPO_ID), eq(jarGav), any(Path.class), eq((long) jarBytes.length));
        verify(service).deployMavenAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), anyLong(), eq(reposilite),
                eq(REPO_ID), eq(sourcesGav), any(Path.class), eq((long) sourcesBytes.length));
        verify(service, never()).deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), eq(javadocGav), any(), anyLong());
    }

    /**
     * {@code repo}'s own direct upstreams are {@code [near, far]}; only {@code far} (two hops via
     * {@code near}'s own upstream chain) has the artifact. Confirms the walk is genuinely followed
     * and the real, deep source is recorded, not an intermediate hop.
     */
    @Test
    void resolvesFromADeeperCandidateWhenCloserOnesDoNotHaveIt() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("near", "far"));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "near", reposilite)).thenReturn("near-id");
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "far", reposilite)).thenReturn("far-id");
        when(reposilite.artifactExists("near-id", GAV)).thenReturn(false);
        when(reposilite.artifactExists("far-id", GAV)).thenReturn(true);
        byte[] jarBytes = "jar-bytes".getBytes();
        stubFetchArtifact("far-id", GAV, jarBytes);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertTrue(imported);
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE, PACKAGE_NAME,
                VERSION, "far", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        // The rejected candidate's lease is released as soon as it's rejected; the eventual
        // winner's is held until the whole attempt finishes with it, but both are released
        // exactly once by the time this call returns.
        verify(reposilite).endUse("near-id");
        verify(reposilite).endUse("far-id");
    }

    /**
     * A candidate being concurrently deleted/released can throw from {@code
     * resolveContainerIdAndBeginUse} itself: treated exactly like "not found" there, same as an
     * Archived/Disposed candidate, not propagated.
     */
    @Test
    void aCandidateBeingConcurrentlyDeletedIsSkippedRatherThanPropagatingTheFailure() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("vanishing", "healthy"));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "vanishing", reposilite))
                .thenThrow(new IllegalStateException("repository is being deleted"));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "healthy", reposilite))
                .thenReturn("healthy-id");
        when(reposilite.artifactExists("healthy-id", GAV)).thenReturn(true);
        byte[] jarBytes = "jar-bytes".getBytes();
        stubFetchArtifact("healthy-id", GAV, jarBytes);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertTrue(imported);
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
    }

    /**
     * An Archived candidate's own version must not be resolved; the walk continues to the next
     * candidate in priority order instead of stopping there.
     */
    @Test
    void skipsAnArchivedCandidateAndContinuesToTheNextOne() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("archived", "healthy"));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "archived", reposilite)).thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "healthy", reposilite)).thenReturn("healthy-id");
        when(reposilite.artifactExists("archived-id", GAV)).thenReturn(true);
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "maven",
                        NAMESPACE, PACKAGE_NAME, VERSION, "archived-id");
        when(reposilite.artifactExists("healthy-id", GAV)).thenReturn(true);
        byte[] jarBytes = "jar-bytes".getBytes();
        stubFetchArtifact("healthy-id", GAV, jarBytes);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertTrue(imported);
        verify(service).claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(reposilite).endUse("archived-id");
        verify(reposilite).endUse("healthy-id");
    }

    @Test
    void returnsFalseWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertFalse(imported);
        verify(service, never()).claimMavenRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "maven", NAMESPACE, PACKAGE_NAME,
                "INTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertFalse(imported);
        verify(service, never()).claimMavenRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenNoCandidateHasTheAssetEither() {
        when(reposilite.artifactExists(UPSTREAM_REPO_ID, GAV)).thenReturn(false);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertFalse(imported);
        verify(service, never()).claimMavenRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimMavenRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L))
                .thenReturn(new CodeArtifactService.MavenClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertFalse(imported);
        verify(reposilite, never()).fetchArtifactStreamed(anyString(), anyString());
        verify(service, never()).finishMavenRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        stubFetchArtifact(UPSTREAM_REPO_ID, GAV, "jar-bytes".getBytes());
        when(service.deployMavenAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), any(), anyLong())).thenReturn(new CodeArtifactService.MavenDeployAttemptResult(false,
                        null));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, GAV);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(service).finishMavenRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, NAMESPACE,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    /** Names at least one real version, so the eligibility pre-check lets this candidate through. */
    private static final byte[] METADATA_WITH_ONE_VERSION = ("<metadata><versioning><versions>"
            + "<version>1.0</version></versions></versioning></metadata>").getBytes();

    @Test
    void resolvesMetadataFromTheUpstreamWhenNotFoundLocally() {
        byte[] metadataBytes = METADATA_WITH_ONE_VERSION;
        String metadataGav = "com/example/widget/maven-metadata.xml";
        when(reposilite.fetchArtifactStreamed(UPSTREAM_REPO_ID, metadataGav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(metadataBytes), metadataBytes.length, "text/xml")));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(42L), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any(),
                eq(true))).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, metadataGav);

        assertTrue(imported);
    }

    /**
     * A candidate whose own metadata names nothing eligible (every version Archived at that
     * candidate) must not stop the search: the next candidate still gets a chance, exactly like
     * the asset case's own Archived/Disposed skip-and-continue ({@link
     * #skipsAnArchivedCandidateAndContinuesToTheNextOne}).
     */
    @Test
    void continuesPastACandidateWhoseMetadataNamesNoEligibleVersion() {
        String metadataGav = "com/example/widget/maven-metadata.xml";
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("archived", "healthy"));
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "archived", reposilite)).thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("maven", REGION, DOMAIN, OWNER, "healthy", reposilite)).thenReturn("healthy-id");
        byte[] archivedMetadata = ("<metadata><versioning><versions><version>9.9</version></versions>"
                + "</versioning></metadata>").getBytes();
        when(reposilite.fetchArtifactStreamed("archived-id", metadataGav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(archivedMetadata), archivedMetadata.length, "text/xml")));
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "maven",
                        NAMESPACE, PACKAGE_NAME, "9.9", "archived-id");
        byte[] healthyMetadata = METADATA_WITH_ONE_VERSION;
        when(reposilite.fetchArtifactStreamed("healthy-id", metadataGav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(healthyMetadata), healthyMetadata.length, "text/xml")));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(42L), eq(reposilite), eq(REPO_ID), eq(healthyMetadata), any(),
                eq(true))).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, metadataGav);

        assertTrue(imported);
        verify(service, never()).mergeMavenMetadataUnderPackageFence(any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any(), eq(archivedMetadata), any(), anyBoolean());
        verify(reposilite).endUse("archived-id");
        verify(reposilite).endUse("healthy-id");
    }

    /** Holds the one argument captured below, since a lambda cannot assign a captured local. */
    private static final class CapturedPredicate {
        private Predicate<String> value;
    }

    @Test
    void metadataVersionAllowedChecksBothTheLocalRepositoryAndTheUpstreamCandidate() {
        byte[] metadataBytes = METADATA_WITH_ONE_VERSION;
        String metadataGav = "com/example/widget/maven-metadata.xml";
        when(reposilite.fetchArtifactStreamed(UPSTREAM_REPO_ID, metadataGav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(metadataBytes), metadataBytes.length, "text/xml")));
        CapturedPredicate captured = new CapturedPredicate();
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(42L), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any(),
                eq(true))).thenAnswer(invocation -> {
                    captured.value = invocation.getArgument(10);
                    return true;
                });

        importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, metadataGav);

        assertTrue(captured.value.test("2.0"));
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, UPSTREAM, "maven",
                        NAMESPACE, PACKAGE_NAME, "3.0", UPSTREAM_REPO_ID);
        assertFalse(captured.value.test("3.0"));
    }

    /**
     * {@code allowSnapshots} is passed as {@code true} for the same-domain upstream case (unlike
     * the external-connection importer's {@code false}): a SNAPSHOT already published into an
     * upstream repository is a perfectly real, already-retained artifact.
     */
    @Test
    void metadataMergeAllowsSnapshotVersionsFromTheUpstream() {
        byte[] metadataBytes = ("<metadata><versioning><versions><version>1.0-SNAPSHOT</version></versions>"
                + "</versioning></metadata>").getBytes();
        String metadataGav = "com/example/widget/maven-metadata.xml";
        when(reposilite.fetchArtifactStreamed(UPSTREAM_REPO_ID, metadataGav)).thenAnswer(invocation ->
                Optional.of(new ReposiliteSidecarClient.StreamedArtifact(
                        new ByteArrayInputStream(metadataBytes), metadataBytes.length, "text/xml")));
        when(service.mergeMavenMetadataUnderPackageFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY),
                eq(NAMESPACE), eq(PACKAGE_NAME), eq(42L), eq(reposilite), eq(REPO_ID), eq(metadataBytes), any(),
                eq(true))).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, metadataGav);

        assertTrue(imported);
    }
}
