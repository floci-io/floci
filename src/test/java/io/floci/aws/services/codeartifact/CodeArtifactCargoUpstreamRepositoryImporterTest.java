package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactCargoUpstreamRepositoryImporter}'s own decision logic, mirroring {@link
 * CodeArtifactNuGetUpstreamRepositoryImporterTest}'s own shape for {@code tryImport} plus
 * dedicated cases for {@code mergeUpstreamRepositoryVersions}. {@link KellnrSidecarClient} is
 * mocked, so no real Kellnr call ever happens here; its own wire contract has its own tests.
 */
class CodeArtifactCargoUpstreamRepositoryImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String UPSTREAM = "upstream";
    private static final String UPSTREAM_REPO_ID = "upstream-repo-id";
    private static final String PACKAGE_NAME = "democrate";
    private static final String VERSION = "1.0.0";
    private static final String ASSET_NAME = "democrate-1.0.0.crate";
    private static final byte[] CRATE_CONTENT = "crate-content".getBytes(StandardCharsets.UTF_8);

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper();

    private CodeArtifactService service;
    private KellnrSidecarClient kellnr;
    private CodeArtifactCargoExternalConnectionImporter externalConnectionImporter;
    private CodeArtifactCargoUpstreamRepositoryImporter importer;

    private JsonNode indexLine;
    private byte[] upstreamIndexBytes;
    private byte[] publishMetadata;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(CodeArtifactService.class);
        kellnr = mock(KellnrSidecarClient.class);
        externalConnectionImporter = mock(CodeArtifactCargoExternalConnectionImporter.class);
        importer = new CodeArtifactCargoUpstreamRepositoryImporter(service, kellnr, externalConnectionImporter);

        indexLine = mapper.readTree("{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"" + VERSION
                + "\",\"deps\":[],\"cksum\":\"abc\",\"features\":{},\"yanked\":false}");
        upstreamIndexBytes = indexLine.toString().getBytes(StandardCharsets.UTF_8);
        publishMetadata = ("{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"" + VERSION + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of(UPSTREAM));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("INTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(List.of(UPSTREAM));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, UPSTREAM, kellnr))
                .thenReturn(UPSTREAM_REPO_ID);
        when(kellnr.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
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
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);
        when(kellnr.fetchSparseIndexRaw(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamIndexBytes));
        when(externalConnectionImporter.findIndexLine(upstreamIndexBytes, VERSION)).thenReturn(Optional.of(indexLine));
        when(externalConnectionImporter.metadataFromIndexLine(indexLine)).thenReturn(publishMetadata);
        when(kellnr.fetchPackageVersionAssetStreamed(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(CRATE_CONTENT), CRATE_CONTENT.length)));
        when(service.stageStreamedAsset(any())).thenAnswer(invocation -> {
            RepositorySidecarManager.StreamedAsset streamed = invocation.getArgument(0);
            Path staged = Files.createTempFile("test-cargo-upstream-", ".crate");
            Files.write(staged, streamed.content().readAllBytes());
            return staged;
        });
        when(kellnr.publishRawWireBody(any(), any(), anyLong()))
                .thenReturn(new KellnrSidecarClient.KellnrPublishResult(200, "{\"warnings\":null}"));
        // Plain thenReturn, not an answer that invokes the write Runnable: re-stubbing this exact
        // call with the same matchers in an individual test (as one test below does) would invoke
        // whatever is currently stubbed as part of registering the new stub, running this one's
        // own side effect with a matcher object standing in for the real Runnable and throwing.
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
    }

    @Test
    void importsFromTheDirectUpstreamWhenNotFoundLocally() {
        // This one test cares that the real write actually ran (kellnr.publishRawWireBody),
        // unlike the others below that only care what retainAssetUnderVersionFence itself
        // reported: re-stubbed locally, not in setUp, since re-stubbing an exact-matcher stub a
        // second time invokes whatever is currently stubbed as part of registering the new one.
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenAnswer(invocation -> {
                    Runnable write = invocation.getArgument(14);
                    write.run();
                    return new CodeArtifactService.AssetRetentionAttemptResult(true, true);
                });

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(kellnr).publishRawWireBody(eq(REPO_ID), any(), anyLong());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "cargo", null, PACKAGE_NAME, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void aVersionNoUpstreamHoldsIsFetchedThroughAnUpstreamsExternalConnection() {
        when(kellnr.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);
        when(externalConnectionImporter.hasExternalConnection(REGION, DOMAIN, OWNER, UPSTREAM)).thenReturn(true);
        when(externalConnectionImporter.tryImport(REGION, DOMAIN, OWNER, UPSTREAM, UPSTREAM_REPO_ID, PACKAGE_NAME,
                VERSION)).thenReturn(true);

        assertTrue(importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION));
        InOrder order = inOrder(service, externalConnectionImporter);
        order.verify(service).currentContainerBackedPackageFence(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME);
        order.verify(service).currentContainerBackedVersionFence(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION);
        order.verify(externalConnectionImporter).tryImport(REGION, DOMAIN, OWNER, UPSTREAM, UPSTREAM_REPO_ID,
                PACKAGE_NAME, VERSION);
        order.verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(service, times(2)).resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, UPSTREAM, kellnr);
        verify(kellnr, times(2)).endUse(UPSTREAM_REPO_ID);
    }

    @Test
    void aVersionArchivedAtTheUpstreamIsNotCopiedThroughItsExternalConnection() {
        doThrow(new AwsException("ResourceNotFoundException", "archived", 404)).when(service)
                .requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, UPSTREAM, "cargo", null,
                        PACKAGE_NAME, VERSION, UPSTREAM_REPO_ID);
        when(externalConnectionImporter.hasExternalConnection(REGION, DOMAIN, OWNER, UPSTREAM)).thenReturn(true);
        when(externalConnectionImporter.tryImport(REGION, DOMAIN, OWNER, UPSTREAM, UPSTREAM_REPO_ID, PACKAGE_NAME,
                VERSION)).thenReturn(true);

        assertFalse(importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION));
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
        verify(kellnr, times(2)).endUse(UPSTREAM_REPO_ID);
    }

    @Test
    void anUpstreamWithoutAnExternalConnectionIsNotAskedToFetch() {
        when(kellnr.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);

        assertFalse(importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION));
        verify(externalConnectionImporter, never()).tryImport(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void resolvesFromADeeperCandidateWhenCloserOnesDoNotHaveIt() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("near", "far"));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "near", kellnr))
                .thenReturn("near-id");
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "far", kellnr))
                .thenReturn("far-id");
        when(kellnr.packageVersionExists("near-id", DOMAIN, "near", null, PACKAGE_NAME, VERSION)).thenReturn(false);
        when(kellnr.packageVersionExists("far-id", DOMAIN, "far", null, PACKAGE_NAME, VERSION)).thenReturn(true);
        when(kellnr.fetchSparseIndexRaw("far-id", DOMAIN, "far", PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamIndexBytes));
        when(kellnr.fetchPackageVersionAssetStreamed("far-id", DOMAIN, "far", null, PACKAGE_NAME, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(CRATE_CONTENT), CRATE_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, "far", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(kellnr).endUse("near-id");
        verify(kellnr).endUse("far-id");
    }

    @Test
    void aCandidateBeingConcurrentlyDeletedIsSkippedRatherThanPropagatingTheFailure() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("vanishing", "healthy"));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "vanishing", kellnr))
                .thenThrow(new IllegalStateException("repository is being deleted"));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "healthy", kellnr))
                .thenReturn("healthy-id");
        when(kellnr.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(kellnr.fetchSparseIndexRaw("healthy-id", DOMAIN, "healthy", PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamIndexBytes));
        when(kellnr.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(CRATE_CONTENT), CRATE_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
    }

    @Test
    void skipsAnArchivedCandidateAndContinuesToTheNextOne() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("archived", "healthy"));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "archived", kellnr))
                .thenReturn("archived-id");
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "healthy", kellnr))
                .thenReturn("healthy-id");
        when(kellnr.packageVersionExists("archived-id", DOMAIN, "archived", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        doThrow(new AwsException("ResourceNotFoundException", "Package version was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, "archived", "cargo",
                        null, PACKAGE_NAME, VERSION, "archived-id");
        when(kellnr.packageVersionExists("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(kellnr.fetchSparseIndexRaw("healthy-id", DOMAIN, "healthy", PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamIndexBytes));
        when(kellnr.fetchPackageVersionAssetStreamed("healthy-id", DOMAIN, "healthy", null, PACKAGE_NAME, VERSION,
                ASSET_NAME)).thenAnswer(invocation -> Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(CRATE_CONTENT), CRATE_CONTENT.length)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(service).claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, "healthy", RetentionOriginKind.UPSTREAM_REPOSITORY, 42L);
        verify(kellnr).endUse("archived-id");
        verify(kellnr).endUse("healthy-id");
    }

    @Test
    void returnsFalseWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                "INTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWhenNoCandidateHasTheVersionEither() {
        when(kellnr.packageVersionExists(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, UPSTREAM, RetentionOriginKind.UPSTREAM_REPOSITORY, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(kellnr, never()).fetchSparseIndexRaw(eq(UPSTREAM_REPO_ID), anyString(), anyString(), anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedUpstreamRepositoryOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION)).thenReturn(Optional.of(UPSTREAM));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(kellnr, never()).fetchPackageVersionAssetStreamed(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefaultIfCurrentRepository(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void mergeReturnsUnchangedWhenNoUpstreamsAreConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setUpstreams(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        Optional<byte[]> alreadyMerged = Optional.of("existing".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, alreadyMerged);

        assertEquals(alreadyMerged, result);
    }

    @Test
    void mergeReturnsUnchangedWhenInternalUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                "INTERNAL_UPSTREAM")).thenReturn(true);
        Optional<byte[]> alreadyMerged = Optional.of("existing".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, alreadyMerged);

        assertEquals(alreadyMerged, result);
    }

    @Test
    void mergeAddsUpstreamOnlyVersionLinesNotAlreadyKnown() {
        String alreadyKnownLine = "{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"0.9.0\"}";
        Optional<byte[]> alreadyMerged = Optional.of(alreadyKnownLine.getBytes(StandardCharsets.UTF_8));
        when(externalConnectionImporter.indexLineVersion(alreadyKnownLine)).thenReturn(Optional.of("0.9.0"));
        when(externalConnectionImporter.indexLineVersion(indexLine.toString())).thenReturn(Optional.of(VERSION));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, alreadyMerged);

        assertTrue(result.isPresent());
        String merged = new String(result.get(), StandardCharsets.UTF_8);
        assertTrue(merged.contains("0.9.0"));
        assertTrue(merged.contains(VERSION));
    }

    @Test
    void mergeSkipsAVersionAlreadyKnownRatherThanDuplicatingIt() {
        when(externalConnectionImporter.indexLineVersion(indexLine.toString())).thenReturn(Optional.of(VERSION));
        Optional<byte[]> alreadyMerged = Optional.of(indexLine.toString().getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, alreadyMerged);

        assertTrue(result.isPresent());
        String merged = new String(result.get(), StandardCharsets.UTF_8);
        assertEquals(1, merged.split("\n").length);
    }

    @Test
    void mergeExcludesAVersionArchivedAtTheCandidate() {
        when(externalConnectionImporter.indexLineVersion(indexLine.toString())).thenReturn(Optional.of(VERSION));
        doThrow(new AwsException("ResourceNotFoundException", "archived", 404)).when(service)
                .requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, UPSTREAM, "cargo", null,
                        PACKAGE_NAME, VERSION, UPSTREAM_REPO_ID);

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void mergeReturnsEmptyWhenNothingIsKnownAnywhere() {
        when(kellnr.fetchSparseIndexRaw(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, Optional.empty());

        assertTrue(result.isEmpty());
    }

    @Test
    void mergeChainsThroughACandidatesOwnExternalConnection() {
        String connectionOnlyLine = "{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"2.0.0\"}";
        when(externalConnectionImporter.indexLineVersion(indexLine.toString())).thenReturn(Optional.of(VERSION));
        when(externalConnectionImporter.indexLineVersion(connectionOnlyLine)).thenReturn(Optional.of("2.0.0"));
        when(externalConnectionImporter.hasExternalConnection(REGION, DOMAIN, OWNER, UPSTREAM)).thenReturn(true);
        byte[] withConnection = (indexLine.toString() + "\n" + connectionOnlyLine)
                .getBytes(StandardCharsets.UTF_8);
        when(externalConnectionImporter.mergeExternalVersions(eq(REGION), eq(DOMAIN), eq(OWNER), eq(UPSTREAM),
                eq(PACKAGE_NAME), any())).thenReturn(Optional.of(withConnection));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, Optional.empty());

        assertTrue(result.isPresent());
        String merged = new String(result.get(), StandardCharsets.UTF_8);
        assertTrue(merged.contains(VERSION));
        assertTrue(merged.contains("2.0.0"));
    }

    /**
     * {@code near} (first in search order) has no local copy but an external connection
     * advertising the same version with a different {@code cksum}; {@code far} (second) has its
     * own real local copy, with yet another {@code cksum}. {@code tryImport}'s own real walk
     * tries every candidate's own local copy, across the whole chain, before ever falling through
     * to any candidate's external connection, so a real download retains {@code far}'s own local
     * bytes, not {@code near}'s own external ones: the merge must advertise the same line a
     * download would actually retain, not whichever one it reaches first per candidate.
     */
    @Test
    void mergeKeepsALaterCandidatesOwnLocalLineOverAnEarlierCandidatesOwnExternalConnectionLine() {
        when(service.resolveUpstreamSearchOrder(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(List.of("near", "far"));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "near", kellnr))
                .thenReturn("near-id");
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, "far", kellnr))
                .thenReturn("far-id");
        when(kellnr.fetchSparseIndexRaw("near-id", DOMAIN, "near", PACKAGE_NAME)).thenReturn(Optional.empty());
        String farLocalLine = "{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"" + VERSION + "\",\"cksum\":\"far\"}";
        when(kellnr.fetchSparseIndexRaw("far-id", DOMAIN, "far", PACKAGE_NAME))
                .thenReturn(Optional.of(farLocalLine.getBytes(StandardCharsets.UTF_8)));
        when(externalConnectionImporter.indexLineVersion(farLocalLine)).thenReturn(Optional.of(VERSION));
        when(externalConnectionImporter.hasExternalConnection(REGION, DOMAIN, OWNER, "near")).thenReturn(true);
        String nearConnectionLine = "{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"" + VERSION
                + "\",\"cksum\":\"near\"}";
        when(externalConnectionImporter.indexLineVersion(nearConnectionLine)).thenReturn(Optional.of(VERSION));
        when(externalConnectionImporter.mergeExternalVersions(eq(REGION), eq(DOMAIN), eq(OWNER), eq("near"),
                eq(PACKAGE_NAME), any())).thenReturn(Optional.of(nearConnectionLine.getBytes(StandardCharsets.UTF_8)));

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, Optional.empty());

        assertTrue(result.isPresent());
        String merged = new String(result.get(), StandardCharsets.UTF_8);
        assertTrue(merged.contains("\"cksum\":\"far\""));
        assertFalse(merged.contains("\"cksum\":\"near\""));
    }

    /**
     * A version only a candidate's own external connection offers, Archived there, must stay out
     * of the merge the same way a locally-contributed line already does: a real download through
     * that connection ({@link UpstreamExternalConnectionFetch}) already refuses an Archived
     * version at the candidate, so advertising it in the index here would offer a client
     * something a download could never actually retain.
     */
    @Test
    void mergeExcludesAVersionArchivedAtTheCandidateEvenWhenOnlyItsExternalConnectionOffersIt() {
        when(kellnr.fetchSparseIndexRaw(UPSTREAM_REPO_ID, DOMAIN, UPSTREAM, PACKAGE_NAME))
                .thenReturn(Optional.empty());
        when(externalConnectionImporter.hasExternalConnection(REGION, DOMAIN, OWNER, UPSTREAM)).thenReturn(true);
        when(externalConnectionImporter.indexLineVersion(indexLine.toString())).thenReturn(Optional.of(VERSION));
        when(externalConnectionImporter.mergeExternalVersions(eq(REGION), eq(DOMAIN), eq(OWNER), eq(UPSTREAM),
                eq(PACKAGE_NAME), any())).thenReturn(Optional.of(upstreamIndexBytes));
        doThrow(new AwsException("ResourceNotFoundException", "archived", 404)).when(service)
                .requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, UPSTREAM, "cargo", null,
                        PACKAGE_NAME, VERSION, UPSTREAM_REPO_ID);

        Optional<byte[]> result = importer.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, Optional.empty());

        assertTrue(result.isEmpty());
    }
}
