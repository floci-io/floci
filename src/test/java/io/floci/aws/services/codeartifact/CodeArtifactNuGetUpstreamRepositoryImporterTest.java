package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactNuGetUpstreamRepositoryImporter}'s own decision logic: when it attempts a
 * same-domain upstream-repository fetch at all, which candidate it resolves from, and what it
 * writes back. {@link BagetterSidecarClient} is mocked, so no real BaGetter call ever happens
 * here; its own wire contract has its own tests.
 */
class CodeArtifactNuGetUpstreamRepositoryImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String UPSTREAM = "upstream";
    private static final String UPSTREAM_REPO_ID = "upstream-repo-id";
    private static final String PACKAGE_ID = "Widget";
    private static final String VERSION = "1.0.0";
    private static final String ASSET_NAME = "widget.1.0.0.nupkg";
    private static final byte[] NUPKG_CONTENT = "nupkg-content".getBytes();

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private BagetterSidecarClient bagetter;
    private CodeArtifactNuGetUpstreamRepositoryImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        bagetter = mock(BagetterSidecarClient.class);
        importer = new CodeArtifactNuGetUpstreamRepositoryImporter(service, bagetter);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of(UPSTREAM));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("INTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(List.of(UPSTREAM));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, UPSTREAM, bagetter)).thenReturn(UPSTREAM_REPO_ID);
        when(bagetter.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        // Unstubbed by default: requireContainerBackedVersionDownloadable is a void method a mock
        // never throws from unless told to, matching "the candidate's version is downloadable."
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.UPSTREAM_REPOSITORY), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_ID, VERSION))
                .thenReturn(false);
        when(bagetter.listPackageVersionAssetNames(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_ID, VERSION))
                .thenReturn(List.of(ASSET_NAME));
        when(bagetter.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_ID, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(NUPKG_CONTENT), NUPKG_CONTENT.length)));
        when(service.stageStreamedAsset(any())).thenAnswer(invocation -> {
            RepositorySidecarManager.StreamedAsset streamed = invocation.getArgument(0);
            Path staged = Files.createTempFile("test-nuget-upstream-", ".nupkg");
            Files.write(staged, streamed.content().readAllBytes());
            return staged;
        });
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
    }

    @Test
    void importsFromTheDirectUpstreamWhenNotFoundLocally() {
        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("nuget"),
                isNull(), eq(PACKAGE_ID), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID),
                eq(ASSET_NAME), eq((long) NUPKG_CONTENT.length), any());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "nuget", null, PACKAGE_ID, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, true);
    }

    /**
     * {@code src}'s own direct upstreams are {@code [near, far]}; only {@code far} (two hops via
     * {@code near}'s own upstream chain, per {@link CodeArtifactService#resolveUpstreamSearchOrder})
     * has the version. Confirms the walk is genuinely followed, not limited to the first miss.
     */
    @Test
    void resolvesFromADeeperCandidateWhenCloserOnesDoNotHaveIt() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("near", "far"));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "near", bagetter)).thenReturn("near-id");
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "far", bagetter)).thenReturn("far-id");
        when(bagetter.packageVersionExists("near-id", DOMAIN, "near", null, PACKAGE_ID, VERSION)).thenReturn(false);
        when(bagetter.packageVersionExists("far-id", DOMAIN, "far", null, PACKAGE_ID, VERSION)).thenReturn(true);
        when(bagetter.listPackageVersionAssetNames("far-id", DOMAIN, "far", null, PACKAGE_ID, VERSION))
                .thenReturn(List.of(ASSET_NAME));
        when(bagetter.fetchPackageVersionAssetStreamed("far-id", DOMAIN, "far", null, PACKAGE_ID, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(NUPKG_CONTENT), NUPKG_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, "far", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        // The rejected candidate's lease is released as soon as it's rejected; the eventual
        // winner's is held until the whole attempt (claim/fetch/write) finishes with it, but both
        // are released exactly once by the time this call returns.
        verify(bagetter).endUse("near-id");
        verify(bagetter).endUse("far-id");
    }

    /**
     * A candidate being concurrently deleted/released can throw from {@code
     * resolveContainerIdAndBeginUse} itself (its own registerRepository/beginUse): treated exactly
     * like "not found" there, same as an Archived/Disposed candidate, not propagated.
     */
    @Test
    void aCandidateBeingConcurrentlyDeletedIsSkippedRatherThanPropagatingTheFailure() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("vanishing", "healthy"));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "vanishing", bagetter))
                .thenThrow(new IllegalStateException("repository is being deleted"));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "healthy", bagetter))
                .thenReturn("healthy-id");
        when(bagetter.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        when(bagetter.listPackageVersionAssetNames("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION))
                .thenReturn(List.of(ASSET_NAME));
        when(bagetter.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(NUPKG_CONTENT), NUPKG_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
    }

    /**
     * An Archived candidate's own version must not be resolved; the walk continues to the next
     * candidate in priority order instead of stopping there (real AWS's own documented Archived/
     * Disposed-at-the-source propagation).
     */
    @Test
    void skipsAnArchivedCandidateAndContinuesToTheNextOne() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("archived", "healthy"));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "archived", bagetter)).thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, "healthy", bagetter)).thenReturn("healthy-id");
        when(bagetter.packageVersionExists("archived-id", DOMAIN, "archived", null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "nuget",
                        null, PACKAGE_ID, VERSION, "archived-id");
        when(bagetter.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        when(bagetter.listPackageVersionAssetNames("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION))
                .thenReturn(List.of(ASSET_NAME));
        when(bagetter.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_ID, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(NUPKG_CONTENT), NUPKG_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(bagetter).endUse("archived-id");
        verify(bagetter).endUse("healthy-id");
    }

    @Test
    void returnsFalseWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                "INTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenNoCandidateHasTheVersionEither() {
        when(bagetter.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_ID, VERSION))
                .thenReturn(false);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(bagetter, never()).packageVersionExists(eq(REPO_ID), anyString(), anyString(), any(), anyString(),
                anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        when(service.retainedUpstreamRepositoryOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION)).thenReturn(Optional.of(UPSTREAM));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(bagetter, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefaultIfCurrentRepository(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, false);
    }
}
