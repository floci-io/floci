package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactPypiExternalConnectionImporter}'s own decision logic: when it attempts a
 * real external-connection fetch at all, which real files it imports together for a resolved
 * version, and what it writes back. {@link PypiserverSidecarClient} and {@link
 * PypiExternalConnectionClient} are both mocked, so no real network or pypiserver call ever
 * happens here; their own wire contracts have their own tests.
 */
class CodeArtifactPypiExternalConnectionImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String PACKAGE_NAME = "widget";
    private static final String VERSION = "1.0.0";
    private static final String SDIST_FILENAME = "widget-1.0.0.tar.gz";
    private static final String WHEEL_FILENAME = "widget-1.0.0-py3-none-any.whl";
    private static final byte[] SDIST_CONTENT = "sdist-content".getBytes();
    private static final byte[] WHEEL_CONTENT = "wheel-content".getBytes();

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper();

    private CodeArtifactService service;
    private PypiserverSidecarClient pypiserver;
    private PypiExternalConnectionClient externalConnectionClient;
    private CodeArtifactPypiExternalConnectionImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        pypiserver = mock(PypiserverSidecarClient.class);
        externalConnectionClient = mock(PypiExternalConnectionClient.class);
        importer = new CodeArtifactPypiExternalConnectionImporter(service, pypiserver, externalConnectionClient,
                mapper);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of(new ExternalConnection("public:pypi", "pypi", "AVAILABLE")));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(),
                eq("EXTERNAL_UPSTREAM"))).thenReturn(false);
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(pypiserver.listPackageVersionAssetNames(anyString(), anyString(), anyString(), any(), anyString(),
                anyString())).thenReturn(List.of());
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(
                        simpleIndexJson(List.of(VERSION), SDIST_FILENAME, WHEEL_FILENAME))));
        stubFetchFile(urlFor(SDIST_FILENAME), SDIST_CONTENT);
        stubFetchFile(urlFor(WHEEL_FILENAME), WHEEL_CONTENT);
    }

    /**
     * {@code fetchFile}'s real, streamed signature takes the destination {@link Path} to write
     * to, generated fresh by the importer itself for every real call, so a test fixture can't
     * name it in advance the way it could the old {@code byte[]}-returning method: this answers
     * whatever path the importer actually passes by writing {@code content} straight to it, the
     * same real effect the real method has.
     */
    private void stubFetchFile(String url, byte[] content) {
        when(externalConnectionClient.fetchFile(eq("public:pypi"), eq(url), any(Path.class))).thenAnswer(
                invocation -> {
                    Path destination = invocation.getArgument(2);
                    Files.write(destination, content);
                    return Optional.of(new PypiExternalConnectionClient.FetchedFile(content.length));
                });
    }

    @Test
    void importsTheRequestedFileFromTheExternalConnection() {
        // No wheel sibling in this version's own index, so nothing is handed off to the
        // background executor here: sibling handoff has its own dedicated test below.
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(
                        simpleIndexJson(List.of(VERSION), SDIST_FILENAME))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID),
                eq(SDIST_FILENAME), eq((long) SDIST_CONTENT.length), any());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "pypi", null, PACKAGE_NAME, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void importsEveryRealSiblingOfTheResolvedVersionNotJustTheRequestedFile() {
        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, WHEEL_FILENAME);

        assertTrue(imported);
        // The requested file (the wheel) is written synchronously, so it is already there the
        // moment tryImportAsset returns.
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(WHEEL_FILENAME), eq((long) WHEEL_CONTENT.length), any());
        // AWS: "CodeArtifact will import all the assets in that package version" -- the sdist is
        // still imported, just handed off to run in the background (raised in review: hectorvent,
        // #7741), so this waits for it rather than asserting it happened synchronously.
        verify(service, timeout(2000)).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER),
                eq(REPOSITORY), eq("pypi"), isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(),
                eq(EPISODE_ID), eq(REPO_ID), eq(SDIST_FILENAME), eq((long) SDIST_CONTENT.length), any());
    }

    @Test
    void skipsAFileAlreadyPresentLocallyWithoutRefetchingIt() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(SDIST_FILENAME));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, WHEEL_FILENAME);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchFile(eq("public:pypi"), eq(urlFor(SDIST_FILENAME)), any());
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                eq(SDIST_FILENAME), anyLong(), any());
    }

    @Test
    void returnsFalseWhenNoPypiExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION), anyLong());
    }

    @Test
    void returnsFalseWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                "EXTERNAL_UPSTREAM"))
                .thenReturn(true);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchSimpleIndex(anyString(), anyString());
    }

    @Test
    void returnsFalseWhenTheFilenameCannotBeParsedIntoAPackageName() {
        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "not-a-real-file");

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchSimpleIndex(anyString(), anyString());
    }

    @Test
    void returnsFalseWhenTheRealIndexNamesNoVersionTheFilenameActuallyMatches() {
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(
                        simpleIndexJson(List.of("2.0.0"), "widget-2.0.0.tar.gz"))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), eq(RetentionOriginKind.EXTERNAL_CONNECTION), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, "public:pypi", RetentionOriginKind.EXTERNAL_CONNECTION, 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchFile(anyString(), anyString(), any());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedTheExactFile() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(SDIST_FILENAME));
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of("public:pypi"));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchFile(anyString(), anyString(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * Unlike npm's/NuGet's own single-asset attempt, this one can loop over as many real sibling
     * files as the registry lists (AWS: "some contain over 100 assets"), long enough for a
     * concurrent sibling attempt under this exact same claim episode to have already written a
     * later candidate by the time this one's own slow fetch for it finishes. Simulated here by
     * having {@code listPackageVersionAssetNames} answer empty on the attempt-start snapshot, then
     * list the sdist from the second call onward, as if a sibling attempt wrote it in that gap.
     */
    @Test
    void skipsWritingASiblingThatAConcurrentAttemptAlreadyWroteWhileThisOneWasStillFetching() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(), List.of(SDIST_FILENAME));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, WHEEL_FILENAME);

        assertTrue(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                eq(SDIST_FILENAME), anyLong(), any());
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(WHEEL_FILENAME), eq((long) WHEEL_CONTENT.length), any());
    }

    /**
     * The same race as above, but landing on the specifically requested file rather than a
     * sibling: this attempt's own return value must still reflect it as imported, confirmed under
     * this connection exactly the way the attempt-start check already does, without writing it a
     * second time.
     */
    @Test
    void confirmsTheRequestedFileWithoutRewritingItWhenAConcurrentAttemptWroteItWhileThisOneWasStillFetching() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(), List.of(SDIST_FILENAME));
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of("public:pypi"));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertTrue(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                eq(SDIST_FILENAME), anyLong(), any());
    }

    /**
     * A direct {@code twine} publish can land between this attempt's own claim and the local
     * existence check below; existence alone proves nothing about who actually wrote it. Mirrors
     * {@code CodeArtifactNuGetExternalConnectionImporterTest}'s identical case for nuget.
     */
    @Test
    void doesNotMislabelAFileADirectPublishWroteDuringTheRaceAsExternallyRetained() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(SDIST_FILENAME));
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.empty());

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefaultIfCurrentRepository(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void additionalExternalFilenamesMergesFilesNotAlreadyKnownLocally() {
        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of(SDIST_FILENAME));

        assertEquals(List.of(new CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile(WHEEL_FILENAME, false,
                null)), additional);
    }

    /**
     * The fix for a real finding: without these, a file this repository has not retained yet
     * would look unconditionally eligible to a real pip client evaluating the merged index, even
     * though the real registry's own response already said it is yanked or needs a different
     * interpreter.
     */
    @Test
    void additionalExternalFilenamesCarriesYankedAndRequiresPythonMetadataThrough() {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", PACKAGE_NAME);
        root.putArray("versions").add(VERSION);
        ObjectNode file = root.putArray("files").addObject();
        file.put("filename", WHEEL_FILENAME);
        file.put("url", urlFor(WHEEL_FILENAME));
        file.put("yanked", true);
        file.put("requires-python", ">=3.9");
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(toBytes(root))));

        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of());

        assertEquals(List.of(new CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile(WHEEL_FILENAME, true,
                ">=3.9")), additional);
    }

    @Test
    void additionalExternalFilenamesExcludesAFileForAnArchivedVersion() {
        doThrow(new AwsException("ResourceNotFoundException", "Package version '1.0.0' is Archived.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, REPOSITORY, "pypi",
                        null, PACKAGE_NAME, VERSION, REPO_ID);

        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of());

        assertEquals(List.of(), additional);
    }

    @Test
    void additionalExternalFilenamesReturnsEmptyWhenNoConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of());

        assertEquals(List.of(), additional);
    }

    @Test
    void additionalExternalFilenamesReturnsEmptyWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                "EXTERNAL_UPSTREAM"))
                .thenReturn(true);

        List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_NAME,
                        Set.of());

        assertEquals(List.of(), additional);
    }

    /**
     * The fix for a real finding: claimRetainedVersionOrigin's own javadoc documents its
     * packageFenceSnapshot parameter as captured "atomically and before any real network fetch."
     * Capturing it only after fetchSimpleIndex returns would let a DeletePackage racing that real
     * outbound call go completely undetected, since the snapshot would already read the
     * post-deletion fence as its own baseline.
     */
    @Test
    void snapshotsThePackageFenceBeforeFetchingTheRealIndexSoARacingDeleteIsNotMissed() {
        InOrder inOrder = inOrder(service, externalConnectionClient);

        importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        inOrder.verify(service).currentContainerBackedPackageFence(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME);
        inOrder.verify(externalConnectionClient).fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME));
    }

    /**
     * The fix for a real finding: the sidecar listing that decides what is already present used
     * to run as a bare statement between the claim and the try/finally that finishes it. A
     * transient sidecar failure there used to propagate straight out of this method, skipping
     * finishRetainedVersionOriginAttempt entirely and stranding the claim this attempt just took.
     */
    @Test
    void aSidecarFailureListingExistingAssetsStillFinishesTheClaimInsteadOfStrandingIt() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenThrow(new IllegalStateException("sidecar unreachable"));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    /**
     * The fix for a real finding: a file whose real bytes do not match the sha256 the trusted
     * index response itself named for it must never be written or counted, the same as if it had
     * never been fetched at all.
     */
    @Test
    void skipsAFileWhoseFetchedBytesDoNotMatchTheIndexsOwnSha256() {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", PACKAGE_NAME);
        root.putArray("versions").add(VERSION);
        ObjectNode file = root.putArray("files").addObject();
        file.put("filename", SDIST_FILENAME);
        file.put("url", urlFor(SDIST_FILENAME));
        file.putObject("hashes").put("sha256", "0".repeat(64));
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(toBytes(root))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(),
                any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong(), any());
    }

    /** A file whose real bytes genuinely match the index's own sha256 is still imported normally. */
    @Test
    void importsAFileWhoseFetchedBytesMatchTheIndexsOwnSha256() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", PACKAGE_NAME);
        root.putArray("versions").add(VERSION);
        ObjectNode file = root.putArray("files").addObject();
        file.put("filename", SDIST_FILENAME);
        file.put("url", urlFor(SDIST_FILENAME));
        file.putObject("hashes").put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(SDIST_CONTENT)));
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(toBytes(root))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(SDIST_FILENAME), eq((long) SDIST_CONTENT.length), any());
    }

    /**
     * The fix for a real finding: two concurrent calls into tryImportAsset for the exact same
     * requested file -- the way two different pip requests for the exact same file of the same
     * version actually would race -- must never both write it, even though each one's own
     * attempt-start snapshot is taken independently. Both calls process their requested file
     * (now the same file for both) synchronously, on their own calling thread, so this exercises
     * the lock exactly where it used to; the sibling each call also backgrounds (the wheel) is
     * deliberately not asserted on here, since it is covered by {@link
     * #importsEveryRealSiblingOfTheResolvedVersionNotJustTheRequestedFile}. The first call's own
     * write of the requested file is held open by a latch while the second call is started,
     * proving the second cannot even reach its own recheck for that same file until the first
     * releases the lock the fix adds, keyed to this exact version.
     */
    @Test
    void serializesConcurrentAttemptsWritingTheSameVersionsSiblingFiles() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicBoolean sdistWritten = new AtomicBoolean(false);
        AtomicInteger sdistWriteAttempts = new AtomicInteger();

        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenAnswer(invocation -> sdistWritten.get() ? List.of(SDIST_FILENAME) : List.<String>of());
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of("public:pypi"));
        when(service.retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(),
                anyLong(), anyLong(), any(), eq(SDIST_FILENAME), anyLong(), any())).thenAnswer(invocation -> {
            if (sdistWriteAttempts.getAndIncrement() == 0) {
                firstWriteStarted.countDown();
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS), "test never released the first write");
            }
            sdistWritten.set(true);
            return new CodeArtifactService.AssetRetentionAttemptResult(true, true);
        });
        when(service.retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(),
                anyLong(), anyLong(), any(), eq(WHEEL_FILENAME), anyLong(), any()))
                .thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(
                    () -> importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME));
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS), "first attempt never started writing");

            Future<Boolean> second = pool.submit(
                    () -> importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME));
            assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS),
                    "second attempt must not reach its own recheck while the first still holds the lock");

            releaseFirstWrite.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        verify(service, times(1)).retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(),
                any(), anyLong(), anyLong(), anyLong(), any(), eq(SDIST_FILENAME), anyLong(), any());
    }

    @Test
    void hyphenatedSdistAndUnderscoredWheelShareTheRetentionLock() throws Exception {
        String sdist = "widget-name-1.0.0.tar.gz";
        String wheel = "widget_name-1.0.0-py3-none-any.whl";
        CountDownLatch firstWheelWrite = new CountDownLatch(1);
        CountDownLatch releaseWheelWrite = new CountDownLatch(1);
        AtomicBoolean sdistWritten = new AtomicBoolean(false);
        AtomicBoolean wheelWritten = new AtomicBoolean(false);
        AtomicInteger wheelWrites = new AtomicInteger();
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), anyString()))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(
                        simpleIndexJson(List.of(VERSION), sdist, wheel))));
        stubFetchFile(urlFor(sdist), SDIST_CONTENT);
        stubFetchFile(urlFor(wheel), WHEEL_CONTENT);
        when(pypiserver.listPackageVersionAssetNames(anyString(), anyString(), anyString(), any(), anyString(),
                anyString())).thenAnswer(invocation -> wheelWritten.get() ? List.of(sdist, wheel)
                        : sdistWritten.get() ? List.of(sdist) : List.of());
        when(service.retainedVersionOriginConnection(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(Optional.of("public:pypi"));
        when(service.retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any(), eq(sdist), anyLong(), any())).thenAnswer(invocation -> {
            sdistWritten.set(true);
            return new CodeArtifactService.AssetRetentionAttemptResult(true, true);
        });
        when(service.retainAssetUnderVersionFence(any(), any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any(), eq(wheel), anyLong(), any())).thenAnswer(invocation -> {
            if (wheelWrites.incrementAndGet() == 1) {
                firstWheelWrite.countDown();
                assertTrue(releaseWheelWrite.await(5, TimeUnit.SECONDS), "wheel write was never released");
            }
            wheelWritten.set(true);
            return new CodeArtifactService.AssetRetentionAttemptResult(true, true);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, sdist));
            assertTrue(firstWheelWrite.await(5, TimeUnit.SECONDS), "background wheel write never started");
            AtomicReference<Thread> requestThread = new AtomicReference<>();
            Future<Boolean> requestedWheel = pool.submit(() -> {
                requestThread.set(Thread.currentThread());
                return importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, wheel);
            });
            ThreadStates.awaitBlockedOrDone(requestThread, requestedWheel);
            assertFalse(requestedWheel.isDone(), "the wheel request must wait for the background sibling");
            assertEquals(Thread.State.BLOCKED, requestThread.get().getState());
            releaseWheelWrite.countDown();
            assertTrue(requestedWheel.get(5, TimeUnit.SECONDS));
            assertEquals(1, wheelWrites.get());
        } finally {
            releaseWheelWrite.countDown();
            pool.shutdownNow();
        }
    }

    private static String urlFor(String filename) {
        return "https://files.pythonhosted.org/packages/aa/bb/" + filename;
    }

    private byte[] toBytes(ObjectNode root) {
        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] simpleIndexJson(List<String> versions, String... filenames) {
        ObjectNode root = mapper.createObjectNode();
        root.put("name", PACKAGE_NAME);
        ArrayNode versionsNode = root.putArray("versions");
        versions.forEach(versionsNode::add);
        ArrayNode files = root.putArray("files");
        for (String filename : filenames) {
            ObjectNode file = files.addObject();
            file.put("filename", filename);
            file.put("url", urlFor(filename));
            file.put("yanked", false);
        }
        return toBytes(root);
    }
}
