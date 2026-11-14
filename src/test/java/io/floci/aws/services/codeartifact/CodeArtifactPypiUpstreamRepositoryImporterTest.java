package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * {@link CodeArtifactPypiUpstreamRepositoryImporter}'s own decision logic: which candidate it
 * resolves a version or a filename merge from, and what it writes back. {@link
 * PypiserverSidecarClient} is mocked, so no real pypiserver call ever happens here; its own wire
 * contract has its own tests.
 */
class CodeArtifactPypiUpstreamRepositoryImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String UPSTREAM = "upstream";
    private static final String UPSTREAM_REPO_ID = "upstream-repo-id";
    private static final String PACKAGE_NAME = "widget";
    private static final String VERSION = "1.0.0";
    private static final String FILENAME = "widget-1.0.0.tar.gz";

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private PypiserverSidecarClient pypiserver;
    private CodeArtifactPypiUpstreamRepositoryImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        pypiserver = mock(PypiserverSidecarClient.class);
        importer = new CodeArtifactPypiUpstreamRepositoryImporter(service, pypiserver);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of(UPSTREAM));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("INTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(List.of(UPSTREAM));
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, UPSTREAM, pypiserver))
                .thenReturn(UPSTREAM_REPO_ID);
        when(pypiserver.listPackageVersions(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp(VERSION,
                        Instant.ofEpochSecond(1000))));
        when(pypiserver.listPackageVersionAssetNames(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION)).thenReturn(List.of(FILENAME));
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.UPSTREAM_REPOSITORY), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of());
        when(pypiserver.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION, FILENAME)).thenAnswer(invocation -> {
                    byte[] content = "tarball-content".getBytes();
                    return Optional.of(new RepositorySidecarManager.StreamedAsset(
                            new ByteArrayInputStream(content), content.length));
                });
        when(service.stageStreamedAsset(any())).thenAnswer(invocation -> {
            RepositorySidecarManager.StreamedAsset streamed = invocation.getArgument(0);
            Path staged = Files.createTempFile("test-pypi-upstream-", ".bin");
            Files.write(staged, streamed.content().readAllBytes());
            return staged;
        });
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
    }

    @Test
    void importsFromTheDirectUpstreamWhenNotFoundLocally() {
        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                any(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID), eq(FILENAME),
                eq(15L), any());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "pypi", null, PACKAGE_NAME, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * pypi imports every real asset of the resolved version together, not just the one requested:
     * both files are written in the same attempt, and the one actually requested is reported.
     */
    @Test
    void importsEveryAssetOfTheResolvedVersionNotJustTheRequestedOne() {
        String sibling = "widget-1.0.0-py3-none-any.whl";
        when(pypiserver.listPackageVersionAssetNames(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION)).thenReturn(List.of(FILENAME, sibling));
        when(pypiserver.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION, sibling)).thenAnswer(invocation -> {
                    byte[] content = "wheel-content".getBytes();
                    return Optional.of(new RepositorySidecarManager.StreamedAsset(
                            new ByteArrayInputStream(content), content.length));
                });

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                any(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), anyLong(), eq(REPO_ID), eq(FILENAME),
                anyLong(), any());
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                any(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), anyLong(), eq(REPO_ID), eq(sibling),
                anyLong(), any());
    }

    /**
     * {@code repo}'s own direct upstreams are {@code [near, far]}; only {@code far} (two hops via
     * {@code near}'s own upstream chain) has the version. Confirms the walk is genuinely followed
     * and the real, deep source is recorded.
     */
    @Test
    void resolvesFromADeeperCandidateWhenCloserOnesDoNotHaveIt() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("near", "far"));
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "near", pypiserver)).thenReturn("near-id");
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "far", pypiserver)).thenReturn("far-id");
        when(pypiserver.listPackageVersions("near-id", DOMAIN, "near", null, PACKAGE_NAME)).thenReturn(List.of());
        when(pypiserver.listPackageVersions("far-id", DOMAIN, "far", null, PACKAGE_NAME))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp(VERSION,
                        Instant.ofEpochSecond(1000))));
        when(pypiserver.listPackageVersionAssetNames("far-id", DOMAIN, "far", null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(FILENAME));
        when(pypiserver.fetchPackageVersionAssetStreamed("far-id", DOMAIN, "far", null, PACKAGE_NAME, VERSION,
                FILENAME)).thenAnswer(invocation -> {
                    byte[] content = "tarball-content".getBytes();
                    return Optional.of(new RepositorySidecarManager.StreamedAsset(
                            new ByteArrayInputStream(content), content.length));
                });

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, "far", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        // The rejected candidate's lease is released as soon as it's rejected; the eventual
        // winner's is held until the whole attempt finishes with it, but both are released
        // exactly once by the time this call returns.
        verify(pypiserver).endUse("near-id");
        verify(pypiserver).endUse("far-id");
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
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "vanishing", pypiserver))
                .thenThrow(new IllegalStateException("repository is being deleted"));
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "healthy", pypiserver))
                .thenReturn("healthy-id");
        when(pypiserver.listPackageVersions("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp(VERSION,
                        Instant.ofEpochSecond(1000))));
        when(pypiserver.listPackageVersionAssetNames("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(FILENAME));
        when(pypiserver.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME,
                VERSION, FILENAME)).thenAnswer(invocation -> {
                    byte[] content = "tarball-content".getBytes();
                    return Optional.of(new RepositorySidecarManager.StreamedAsset(
                            new ByteArrayInputStream(content), content.length));
                });

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
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
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "archived", pypiserver)).thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("pypi", REGION, DOMAIN, OWNER, "healthy", pypiserver)).thenReturn("healthy-id");
        when(pypiserver.listPackageVersions("archived-id", DOMAIN, "archived", null, PACKAGE_NAME))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp(VERSION,
                        Instant.ofEpochSecond(1000))));
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "pypi",
                        null, PACKAGE_NAME, VERSION, "archived-id");
        when(pypiserver.listPackageVersions("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME))
                .thenReturn(List.of(new RepositorySidecarManager.PackageVersionTimestamp(VERSION,
                        Instant.ofEpochSecond(1000))));
        when(pypiserver.listPackageVersionAssetNames("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(FILENAME));
        when(pypiserver.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME,
                VERSION, FILENAME)).thenAnswer(invocation -> {
                    byte[] content = "tarball-content".getBytes();
                    return Optional.of(new RepositorySidecarManager.StreamedAsset(
                            new ByteArrayInputStream(content), content.length));
                });

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(pypiserver).endUse("archived-id");
        verify(pypiserver).endUse("healthy-id");
    }

    @Test
    void returnsFalseWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                "INTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenNoCandidateHasTheVersionEither() {
        when(pypiserver.listPackageVersions(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME))
                .thenReturn(List.of());

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertFalse(imported);
        verify(pypiserver, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(FILENAME));
        when(service.retainedUpstreamRepositoryOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of(UPSTREAM));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertTrue(imported);
        verify(pypiserver, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, FILENAME);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefaultIfCurrentRepository(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void additionalInternalFilenamesListsTheUpstreamsOwnFilesNotAlreadyKnown() {
        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional = importer
                .additionalInternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, Set.of());

        assertTrue(additional.stream().anyMatch(f -> f.filename().equals(FILENAME)));
        assertTrue(additional.stream().allMatch(f -> !f.yanked() && f.requiresPythonOrNull() == null));
    }

    @Test
    void additionalInternalFilenamesOmitsAFilenameAlreadyKnownLocally() {
        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional = importer
                .additionalInternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of(FILENAME));

        assertTrue(additional.isEmpty());
    }

    @Test
    void additionalInternalFilenamesReturnsEmptyWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional = importer
                .additionalInternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, Set.of());

        assertTrue(additional.isEmpty());
    }
}
