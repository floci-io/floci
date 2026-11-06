package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.CodeArtifactRepository;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                any(), anyString())).thenReturn(false);
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        when(pypiserver.listPackageVersionAssetNames(anyString(), anyString(), anyString(), any(), anyString(),
                anyString())).thenReturn(List.of());
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
        when(externalConnectionClient.fetchSimpleIndex(eq("public:pypi"), eq(PACKAGE_NAME)))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(
                        simpleIndexJson(List.of(VERSION), SDIST_FILENAME, WHEEL_FILENAME))));
        when(externalConnectionClient.fetchFile(eq("public:pypi"), eq(urlFor(SDIST_FILENAME))))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(SDIST_CONTENT)));
        when(externalConnectionClient.fetchFile(eq("public:pypi"), eq(urlFor(WHEEL_FILENAME))))
                .thenReturn(Optional.of(new PypiExternalConnectionClient.FetchedAsset(WHEEL_CONTENT)));
    }

    @Test
    void importsTheRequestedFileFromTheExternalConnection() {
        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID),
                eq(SDIST_FILENAME), eq(SDIST_CONTENT), any());
        verify(service).ensureRetainedPackageOriginDefaultIfCurrentRepository(REGION, DOMAIN, OWNER, REPOSITORY,
                "pypi", null, PACKAGE_NAME, 42L, REPO_ID);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    @Test
    void importsEveryRealSiblingOfTheResolvedVersionNotJustTheRequestedFile() {
        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, WHEEL_FILENAME);

        assertTrue(imported);
        // AWS: "CodeArtifact will import all the assets in that package version" -- the sdist is
        // imported in the same attempt even though only the wheel was actually requested.
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(SDIST_FILENAME), eq(SDIST_CONTENT), any());
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(WHEEL_FILENAME), eq(WHEEL_CONTENT), any());
    }

    @Test
    void skipsAFileAlreadyPresentLocallyWithoutRefetchingIt() {
        when(pypiserver.listPackageVersionAssetNames(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(List.of(SDIST_FILENAME));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, WHEEL_FILENAME);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchFile(eq("public:pypi"), eq(urlFor(SDIST_FILENAME)));
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                eq(SDIST_FILENAME), any(), any());
    }

    @Test
    void returnsFalseWhenNoPypiExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void returnsFalseWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME))
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
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME,
                VERSION, "public:pypi", 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, SDIST_FILENAME);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchFile(anyString(), anyString());
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
        verify(externalConnectionClient, never()).fetchFile(anyString(), anyString());
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
                eq(SDIST_FILENAME), any(), any());
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("pypi"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), anyLong(), anyLong(), eq(EPISODE_ID), eq(REPO_ID),
                eq(WHEEL_FILENAME), eq(WHEEL_CONTENT), any());
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
                eq(SDIST_FILENAME), any(), any());
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
        List<String> additional = importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                PACKAGE_NAME, Set.of(SDIST_FILENAME));

        assertEquals(List.of(WHEEL_FILENAME), additional);
    }

    @Test
    void additionalExternalFilenamesExcludesAFileForAnArchivedVersion() {
        doThrow(new AwsException("ResourceNotFoundException", "Package version '1.0.0' is Archived.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, REPOSITORY, "pypi",
                        null, PACKAGE_NAME, VERSION, REPO_ID);

        List<String> additional = importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                PACKAGE_NAME, Set.of());

        assertEquals(List.of(), additional);
    }

    @Test
    void additionalExternalFilenamesReturnsEmptyWhenNoConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        List<String> additional = importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                PACKAGE_NAME, Set.of());

        assertEquals(List.of(), additional);
    }

    @Test
    void additionalExternalFilenamesReturnsEmptyWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "pypi", null, PACKAGE_NAME))
                .thenReturn(true);

        List<String> additional = importer.additionalExternalFilenames(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                PACKAGE_NAME, Set.of());

        assertEquals(List.of(), additional);
    }

    private static String urlFor(String filename) {
        return "https://files.pythonhosted.org/packages/aa/bb/" + filename;
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
        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
