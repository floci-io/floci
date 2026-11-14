package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@link CodeArtifactNpmUpstreamRepositoryImporter}'s own decision logic: which candidate it
 * resolves a merge or an asset from, and what it writes back. {@link VerdaccioSidecarClient} is
 * mocked, so no real Verdaccio call ever happens here; its own wire contract has its own tests.
 */
class CodeArtifactNpmUpstreamRepositoryImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String UPSTREAM = "upstream";
    private static final String UPSTREAM_REPO_ID = "upstream-repo-id";
    private static final String PACKAGE_NAME = "widget";
    private static final String VERSION = "1.0.0";
    private static final String ASSET_NAME = "widget-1.0.0.tgz";
    private static final String BASE_URL = "http://localhost:4566/codeartifact/npm/dom/repo/";
    private static final String UPSTREAM_BASE_URL = "http://localhost:4566/codeartifact/npm/dom/upstream/";

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private VerdaccioSidecarClient verdaccio;
    private CodeArtifactNpmUpstreamRepositoryImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        verdaccio = mock(VerdaccioSidecarClient.class);
        importer = new CodeArtifactNpmUpstreamRepositoryImporter(service, verdaccio, new ObjectMapper());

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of(UPSTREAM));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("INTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(List.of(UPSTREAM));
        when(service.resolveContainerIdAndBeginUse("npm", REGION, DOMAIN, OWNER, UPSTREAM, verdaccio)).thenReturn(UPSTREAM_REPO_ID);
        when(verdaccio.publicUrl(DOMAIN, REPOSITORY)).thenReturn(BASE_URL);
        when(verdaccio.publicUrl(DOMAIN, UPSTREAM)).thenReturn(UPSTREAM_BASE_URL);
        when(verdaccio.ensureReady(REPO_ID, BASE_URL)).thenReturn(BASE_URL);
        when(verdaccio.ensureReady(UPSTREAM_REPO_ID, UPSTREAM_BASE_URL)).thenReturn(UPSTREAM_BASE_URL);
        when(verdaccio.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(verdaccio.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.UPSTREAM_REPOSITORY), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
        when(service.stageStreamedAsset(any())).thenAnswer(invocation -> {
            RepositorySidecarManager.StreamedAsset streamed = invocation.getArgument(0);
            Path staged = Files.createTempFile("test-npm-upstream-", ".bin");
            Files.write(staged, streamed.content().readAllBytes());
            return staged;
        });
    }

    private JsonNode upstreamDocument(String readme) {
        ObjectNode doc = new ObjectMapper().createObjectNode();
        doc.put("name", PACKAGE_NAME);
        doc.put("readme", readme);
        ObjectNode versions = doc.putObject("versions");
        ObjectNode versionDoc = versions.putObject(VERSION);
        versionDoc.put("version", VERSION);
        versionDoc.putObject("dist").put("tarball", UPSTREAM_BASE_URL + PACKAGE_NAME + "/-/" + ASSET_NAME);
        ObjectNode distTags = doc.putObject("dist-tags");
        distTags.put("latest", VERSION);
        return doc;
    }

    @Test
    void importsFromTheDirectUpstreamWhenNotFoundLocally() {
        when(verdaccio.fetchPackageDocument(UPSTREAM_BASE_URL, null, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamDocument("read me please")));
        byte[] tarballBytes = "tarball-content".getBytes();
        when(verdaccio.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION, ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(tarballBytes), tarballBytes.length)));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("npm"),
                any(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID), eq(ASSET_NAME),
                eq((long) tarballBytes.length), any());
        verify(service).recordPublishedPackageVersionReadme(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, "read me please", 99L);
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, 42L);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * An Archived candidate's own version must not be resolved; the walk continues to the next
     * candidate in priority order instead of stopping there.
     */
    @Test
    void skipsAnArchivedCandidateAndContinuesToTheNextOne() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("archived", "healthy"));
        when(service.resolveContainerIdAndBeginUse("npm", REGION, DOMAIN, OWNER, "archived", verdaccio)).thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("npm", REGION, DOMAIN, OWNER, "healthy", verdaccio)).thenReturn("healthy-id");
        when(verdaccio.packageVersionExists("archived-id", DOMAIN, "archived", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "npm",
                        null, PACKAGE_NAME, VERSION, "archived-id");
        when(verdaccio.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(verdaccio.publicUrl(DOMAIN, "healthy")).thenReturn("http://healthy/");
        when(verdaccio.ensureReady("healthy-id", "http://healthy/")).thenReturn("http://healthy/");
        when(verdaccio.fetchPackageDocument("http://healthy/", null, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamDocument("")));
        byte[] tarballBytes = "tarball-content".getBytes();
        when(verdaccio.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME,
                VERSION, ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(tarballBytes), tarballBytes.length)));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        // The rejected candidate's lease is released as soon as it's rejected; the eventual
        // winner's is held until the whole attempt finishes with it, but both are released
        // exactly once by the time this call returns.
        verify(verdaccio).endUse("archived-id");
        verify(verdaccio).endUse("healthy-id");
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
        when(service.resolveContainerIdAndBeginUse("npm", REGION, DOMAIN, OWNER, "vanishing", verdaccio))
                .thenThrow(new IllegalStateException("repository is being deleted"));
        when(service.resolveContainerIdAndBeginUse("npm", REGION, DOMAIN, OWNER, "healthy", verdaccio))
                .thenReturn("healthy-id");
        when(verdaccio.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(verdaccio.publicUrl(DOMAIN, "healthy")).thenReturn("http://healthy/");
        when(verdaccio.ensureReady("healthy-id", "http://healthy/")).thenReturn("http://healthy/");
        when(verdaccio.fetchPackageDocument("http://healthy/", null, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamDocument("")));
        byte[] tarballBytes = "tarball-content".getBytes();
        when(verdaccio.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME,
                VERSION, ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(tarballBytes), tarballBytes.length)));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
    }

    @Test
    void returnsFalseWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                "INTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenNoCandidateHasTheVersionEither() {
        when(verdaccio.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(verdaccio, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(verdaccio.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedUpstreamRepositoryOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of(UPSTREAM));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(verdaccio, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        when(verdaccio.fetchPackageDocument(UPSTREAM_BASE_URL, null, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamDocument("")));
        byte[] tarballBytes = "tarball-content".getBytes();
        when(verdaccio.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME,
                VERSION, ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(tarballBytes), tarballBytes.length)));
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void mergedPackumentMergesTheUpstreamsVersionAndRewritesItsTarballUrl() {
        when(verdaccio.fetchPackageDocument(UPSTREAM_BASE_URL, null, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamDocument("")));
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME);

        assertTrue(merged.isPresent());
        JsonNode versionDoc = merged.get().path("versions").path(VERSION);
        assertFalse(versionDoc.isMissingNode());
        assertEquals(BASE_URL + PACKAGE_NAME + "/-/" + ASSET_NAME, versionDoc.path("dist").path("tarball").asText());
        assertEquals(VERSION, merged.get().path("dist-tags").path("latest").asText());
        verify(verdaccio).endUse(UPSTREAM_REPO_ID);
    }

    @Test
    void mergedPackumentReturnsEmptyWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME);

        assertFalse(merged.isPresent());
    }
}
