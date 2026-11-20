package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactCargoExternalConnectionImporter}'s own decision logic: when it attempts a
 * real external-connection fetch at all, and what it writes back, mirroring {@link
 * CodeArtifactNuGetExternalConnectionImporterTest}'s own shape for {@code tryImport}, plus
 * dedicated cases for {@code mergeExternalVersions}, the one genuinely new piece this format
 * needs (see this importer's own javadoc for why). {@link KellnrSidecarClient} and {@link
 * CargoExternalConnectionClient} are both mocked, so no real network or Kellnr call ever happens
 * here; their own wire contracts have their own tests.
 */
class CodeArtifactCargoExternalConnectionImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String PACKAGE_NAME = "democrate";
    private static final String VERSION = "1.0.0";
    private static final byte[] CRATE_CONTENT = "crate-content".getBytes(StandardCharsets.UTF_8);

    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private KellnrSidecarClient kellnr;
    private CargoExternalConnectionClient externalConnectionClient;
    private CodeArtifactCargoExternalConnectionImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        kellnr = mock(KellnrSidecarClient.class);
        externalConnectionClient = mock(CargoExternalConnectionClient.class);
        importer = new CodeArtifactCargoExternalConnectionImporter(service, kellnr, externalConnectionClient,
                new ObjectMapper());

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(
                List.of(new ExternalConnection("public:crates-io", "cargo", "AVAILABLE")));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), eq("EXTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
    }

    @Test
    void importsFromExternalConnectionWhenNotFoundLocally() {
        stubRealIndexAndCrate(VERSION, CRATE_CONTENT);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("cargo"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID),
                eq(PACKAGE_NAME + "-" + VERSION + ".crate"), eq((long) CRATE_CONTENT.length), any());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "cargo", null, PACKAGE_NAME, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseWhenNoCargoExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION),
                anyLong());
    }

    @Test
    void returnsFalseWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                "EXTERNAL_UPSTREAM")).thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION),
                anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION, "public:crates-io", RetentionOriginKind.EXTERNAL_CONNECTION, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(kellnr, never()).packageVersionExists(anyString(), anyString(), anyString(), any(), anyString(),
                anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of("public:crates-io"));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchSparseIndex(anyString(), anyString());
        verify(externalConnectionClient, never()).fetchCrate(anyString(), anyString(), anyString(), any());
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void doesNotMislabelAVersionADirectPushWroteDuringTheRaceAsExternallyRetained() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchSparseIndex(anyString(), anyString());
        verify(service, never()).ensureRetainedPackageOriginDefaultIfCurrentRepository(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void returnsFalseAndFinishesWhenTheRealIndexIsUnreachable() {
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void returnsFalseWhenTheRealIndexNamesNoMatchingVersion() {
        byte[] index = indexLine("2.0.0", sha256Hex(CRATE_CONTENT)).getBytes(StandardCharsets.UTF_8);
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(index));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchCrate(anyString(), anyString(), anyString(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void aFetchThatThrowsPropagatesRatherThanBeingTreatedLikeAMissingCrate() {
        byte[] index = indexLine(VERSION, sha256Hex(CRATE_CONTENT)).getBytes(StandardCharsets.UTF_8);
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(index));
        when(externalConnectionClient.fetchCrate(eq("public:crates-io"), eq(PACKAGE_NAME), eq(VERSION),
                any(Path.class))).thenThrow(new IllegalStateException("public registry unreachable"));

        assertThrows(IllegalStateException.class, () -> importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY,
                REPO_ID, PACKAGE_NAME, VERSION));

        // Still runs its own bookkeeping on the way out, via the outer finally, rather than ever
        // leaving a claimed-but-never-finished retention attempt behind.
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void skipsAnImportWhoseRealBytesDoNotMatchTheIndexsOwnCksum() {
        byte[] index = indexLine(VERSION, "0".repeat(64)).getBytes(StandardCharsets.UTF_8);
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(index));
        stubFetchCrate(VERSION, CRATE_CONTENT);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME, VERSION);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        stubRealIndexAndCrate(VERSION, CRATE_CONTENT);
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
    void mergeExternalVersionsReturnsLocalBytesUnchangedWhenNoConnectionConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        Optional<byte[]> local = Optional.of("local-bytes".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> merged = importer.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, PACKAGE_NAME,
                local);

        assertEquals(local, merged);
        verify(externalConnectionClient, never()).fetchSparseIndex(anyString(), anyString());
    }

    @Test
    void mergeExternalVersionsReturnsLocalBytesUnchangedWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null, PACKAGE_NAME,
                "EXTERNAL_UPSTREAM")).thenReturn(true);
        Optional<byte[]> local = Optional.of("local-bytes".getBytes(StandardCharsets.UTF_8));

        Optional<byte[]> merged = importer.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, PACKAGE_NAME,
                local);

        assertEquals(local, merged);
        verify(externalConnectionClient, never()).fetchSparseIndex(anyString(), anyString());
    }

    @Test
    void mergeExternalVersionsAddsUpstreamOnlyVersionLinesNotAlreadyLocal() {
        String localLine = indexLine("1.0.0", "a".repeat(64));
        String upstreamOnlyLine = indexLine("2.0.0", "b".repeat(64));
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of((localLine + "\n" + upstreamOnlyLine).getBytes(StandardCharsets.UTF_8)));

        Optional<byte[]> merged = importer.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, PACKAGE_NAME,
                Optional.of((localLine + "\n").getBytes(StandardCharsets.UTF_8)));

        assertTrue(merged.isPresent());
        String mergedText = new String(merged.get(), StandardCharsets.UTF_8);
        assertTrue(mergedText.contains("\"1.0.0\""));
        assertTrue(mergedText.contains("\"2.0.0\""));
    }

    @Test
    void mergeExternalVersionsKeepsTheLocalLineForAVersionPresentInBoth() {
        String localLine = indexLine("1.0.0", "a".repeat(64));
        String upstreamLineSameVersion = indexLine("1.0.0", "different-cksum-should-never-win".repeat(2));
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(upstreamLineSameVersion.getBytes(StandardCharsets.UTF_8)));

        Optional<byte[]> merged = importer.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, PACKAGE_NAME,
                Optional.of(localLine.getBytes(StandardCharsets.UTF_8)));

        assertTrue(merged.isPresent());
        assertEquals(localLine, new String(merged.get(), StandardCharsets.UTF_8));
    }

    @Test
    void mergeExternalVersionsReturnsEmptyWhenBothLocalAndUpstreamAreEmpty() {
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(new byte[0]));

        Optional<byte[]> merged = importer.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, PACKAGE_NAME,
                Optional.empty());

        assertEquals(Optional.empty(), merged);
    }

    private static String indexLine(String version, String cksum) {
        return "{\"name\":\"" + PACKAGE_NAME + "\",\"vers\":\"" + version + "\",\"deps\":[],\"cksum\":\"" + cksum
                + "\",\"features\":{},\"yanked\":false}";
    }

    private void stubRealIndexAndCrate(String version, byte[] content) {
        byte[] index = indexLine(version, sha256Hex(content)).getBytes(StandardCharsets.UTF_8);
        when(externalConnectionClient.fetchSparseIndex("public:crates-io", PACKAGE_NAME))
                .thenReturn(Optional.of(index));
        stubFetchCrate(version, content);
    }

    /**
     * {@code fetchCrate}'s real, streamed signature takes the destination {@link Path} to write
     * to, generated fresh by the importer itself for every real call, so a test fixture can't
     * name it in advance the way it could a plain {@code byte[]}-returning method: this answers
     * whatever path the importer actually passes by writing {@code content} straight to it, the
     * same real effect the real method has.
     */
    private void stubFetchCrate(String version, byte[] content) {
        when(externalConnectionClient.fetchCrate(eq("public:crates-io"), eq(PACKAGE_NAME), eq(version),
                any(Path.class))).thenAnswer(invocation -> {
                    Path destination = invocation.getArgument(3);
                    Files.write(destination, content);
                    return Optional.of(new CargoExternalConnectionClient.FetchedFile(content.length,
                            sha256Hex(content)));
                });
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
