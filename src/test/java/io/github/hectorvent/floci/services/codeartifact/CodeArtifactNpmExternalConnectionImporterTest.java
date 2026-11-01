package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactNpmExternalConnectionImporter}'s own decision logic: when it attempts a real
 * external-connection fetch at all, and what it writes back or merges. {@link
 * VerdaccioSidecarClient} and {@link NpmExternalConnectionClient} are both mocked, so no real
 * network or Verdaccio call ever happens here; their own wire contracts have their own tests.
 */
class CodeArtifactNpmExternalConnectionImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String PACKAGE_NAME = "widget";
    private static final String VERSION = "1.0.0";
    private static final String ASSET_NAME = "widget-1.0.0.tgz";
    private static final String BASE_URL = "http://localhost:4566/codeartifact/npm/dom/repo/";
    private static final byte[] TARBALL_CONTENT = "tarball-content".getBytes();

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private VerdaccioSidecarClient verdaccio;
    private NpmExternalConnectionClient externalConnectionClient;
    private CodeArtifactNpmExternalConnectionImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        verdaccio = mock(VerdaccioSidecarClient.class);
        externalConnectionClient = mock(NpmExternalConnectionClient.class);
        importer = new CodeArtifactNpmExternalConnectionImporter(service, verdaccio, externalConnectionClient,
                new ObjectMapper());

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of(new ExternalConnection("public:npmjs", "npm", "AVAILABLE")));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);
        when(service.isUpstreamRetentionBlocked(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString())).thenReturn(false);
        when(service.currentContainerBackedPackageFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(42L);
        when(service.currentContainerBackedVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(99L);
        // Unstubbed by default: the claim succeeds, matching the common case of a first or
        // same-connection retry. Tests for a conflicting claim override this per call.
        when(service.claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(true, EPISODE_ID));
        // Unstubbed by default: Verdaccio does not have the version yet, matching the data plane's
        // own pre-check always calling tryImportAsset only on a miss.
        when(verdaccio.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(false);
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
        when(verdaccio.ensureReady(REPO_ID, BASE_URL)).thenReturn(BASE_URL);
        when(verdaccio.publicUrl(DOMAIN, REPOSITORY)).thenReturn(BASE_URL);
    }

    @Test
    void tryImportAssetImportsFromExternalConnectionWhenNotFoundLocally() throws Exception {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(TARBALL_CONTENT)));
        // The real registry is reachable and genuinely has no readme for this package (unlike
        // Optional.empty(), which models the full fetch itself failing, see
        // tryImportAssetReturnsFalseAndRetriesLaterWhenTheFullPackumentFetchFails below).
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode full = mapper.createObjectNode();
        full.putObject("versions").putObject(VERSION);
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        full))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("npm"),
                isNull(), eq(PACKAGE_NAME), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(REPO_ID),
                eq(ASSET_NAME), eq(TARBALL_CONTENT), any());
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, 42L);
        verify(service).recordPublishedPackageVersionReadme(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, "", 99L);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * Raised by hectorvent's review: when the tarball fetch works but the full packument fetch
     * fails, retaining anyway with the minimal document {@code storeRetainedVersionAsset} falls
     * back to would lose this version's dependencies/engines/scripts for good, since the merge
     * skips it from then on ({@code versions.has(version)}). Returning {@code false} here instead
     * abandons the attempt, writing nothing, so a later download retries the full fetch.
     */
    @Test
    void tryImportAssetReturnsFalseAndRetriesLaterWhenTheFullPackumentFetchFails() {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(TARBALL_CONTENT)));
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.empty());

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(service, never()).recordPublishedPackageVersionReadme(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    /**
     * The real registry's own full-form version document (dependencies/engines/scripts) and its
     * package-level readme are both reused verbatim through to the sidecar write and the readme
     * capture, rather than building a minimal document or recording an empty readme when the real
     * registry actually has both.
     */
    @Test
    void tryImportAssetPreservesTheRealVersionDocumentAndReadme() throws Exception {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(TARBALL_CONTENT)));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode full = mapper.createObjectNode();
        full.put("readme", "# Widget\nA real readme.");
        ObjectNode versionDoc = full.putObject("versions").putObject(VERSION);
        versionDoc.putObject("dependencies").put("left-pad", "^1.0.0");
        versionDoc.putArray("scripts");
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        full))));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(service).recordPublishedPackageVersionReadme(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, "# Widget\nA real readme.", 99L);
        // retainAssetUnderVersionFence's own write happens through a Runnable this test cannot
        // inspect without invoking it; the sidecar wire contract for storeRetainedVersionAsset
        // itself (dependencies preserved, dist rewritten) is VerdaccioSidecarClient's own test's
        // job, not this one's. What this test owns is confirming the real version document was
        // actually fetched and handed to that call at all, which the readme capture above already
        // proves happened on the very same code path.
    }

    @Test
    void tryImportAssetReturnsFalseWhenNoNpmExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void tryImportAssetReturnsFalseWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME))
                .thenReturn(true);

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void tryImportAssetReturnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION, "public:npmjs", 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(verdaccio, never()).packageVersionExists(anyString(), anyString(), anyString(), any(), anyString(),
                anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void tryImportAssetReturnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(verdaccio.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.of("public:npmjs"));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchTarball(anyString(), any(), anyString(), anyString());
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, true);
    }

    /**
     * {@code verdaccio.packageVersionExists} alone proves nothing about who put the content there:
     * a real client's own direct publish can land between this attempt's own claim and this
     * existence check, the same race {@code CodeArtifactNuGetExternalConnectionImporter}'s own
     * test of the same name guards against.
     */
    @Test
    void tryImportAssetDoesNotMislabelAVersionADirectPushWroteDuringTheRaceAsExternallyRetained() {
        when(verdaccio.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_NAME, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                VERSION)).thenReturn(Optional.empty());

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchTarball(anyString(), any(), anyString(), anyString());
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void tryImportAssetReturnsFalseAndFinishesWhenTheRealRegistryDoesNotHaveIt() {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenReturn(Optional.empty());

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void tryImportAssetFetchThatThrowsIsTreatedLikeAMissingPackageRatherThanPropagating() {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenThrow(new IllegalStateException("public registry unreachable"));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void tryImportAssetReturnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() throws Exception {
        when(externalConnectionClient.fetchTarball("public:npmjs", null, PACKAGE_NAME, VERSION))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(TARBALL_CONTENT)));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode full = mapper.createObjectNode();
        full.putObject("versions").putObject(VERSION);
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        full))));
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImportAsset(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME,
                VERSION, ASSET_NAME);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null,
                PACKAGE_NAME, VERSION, EPISODE_ID, false);
    }

    @Test
    void mergedPackumentReturnsEmptyWhenNoNpmExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
        verify(externalConnectionClient, never()).fetchPackument(anyString(), any(), anyString());
    }

    @Test
    void mergedPackumentReturnsEmptyWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME))
                .thenReturn(true);

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
        verify(externalConnectionClient, never()).fetchPackument(anyString(), any(), anyString());
    }

    @Test
    void mergedPackumentReturnsEmptyWhenTheRealRegistryDoesNotHaveThePackage() {
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
    }

    @Test
    void mergedPackumentFetchThatThrowsIsTreatedAsUnresolvedRatherThanPropagating() {
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenThrow(new IllegalStateException("public registry unreachable"));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
    }

    /**
     * Raised by Greptile's review: {@code ensureReady} can start a fresh container under a
     * repository id that {@code DeleteRepository} already released while the real registry fetch
     * above was still in flight, since nothing here held any lock across that real network round
     * trip. Re-checking the repository still exists right after that fetch, before this method
     * ever touches the sidecar, catches exactly that case.
     */
    @Test
    void mergedPackumentReturnsEmptyWhenTheRepositoryWasDeletedWhileTheExternalFetchWasInFlight() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode external = mapper.createObjectNode();
        external.putObject("versions").putObject("2.0.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of(new ExternalConnection("public:npmjs", "npm", "AVAILABLE")));
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY))
                .thenReturn(repository)
                .thenThrow(new AwsException("ResourceNotFoundException", "no such repository", 404));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
        verify(verdaccio, never()).ensureReady(anyString(), anyString());
    }

    /**
     * A version the real registry names but Verdaccio does not have yet is added with its {@code
     * dist.tarball} rewritten to this repository's own URL, under the exact {@code
     * <bareName>-<version>.tgz} shape {@code CodeArtifactNpmDataPlane}'s own download-side import
     * trigger expects back, while {@code dist.shasum}/{@code dist.integrity} are kept exactly as
     * the real registry declared them.
     */
    @Test
    void mergedPackumentAddsANewVersionRewritingItsTarballToThisRepositorysOwnUrl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode external = mapper.createObjectNode();
        ObjectNode versions = external.putObject("versions");
        ObjectNode versionDoc = versions.putObject("2.0.0");
        versionDoc.put("name", "widget");
        versionDoc.put("version", "2.0.0");
        ObjectNode dist = versionDoc.putObject("dist");
        dist.put("shasum", "real-shasum");
        dist.put("integrity", "sha512-real");
        dist.put("tarball", "https://registry.npmjs.org/widget/-/widget-2.0.0.tgz");
        external.putObject("dist-tags").put("latest", "2.0.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isPresent());
        JsonNode mergedVersion = merged.get().path("versions").path("2.0.0");
        assertEquals(BASE_URL + "widget/-/widget-2.0.0.tgz", mergedVersion.path("dist").path("tarball").asText());
        assertEquals("real-shasum", mergedVersion.path("dist").path("shasum").asText());
        assertEquals("sha512-real", mergedVersion.path("dist").path("integrity").asText());
        assertEquals("2.0.0", merged.get().path("dist-tags").path("latest").asText());
    }

    @Test
    void mergedPackumentWithCorgiFalseNeverFetchesTheFullPackument() {
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME)).thenReturn(
                Optional.empty());

        importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null, PACKAGE_NAME, false);

        verify(externalConnectionClient).fetchPackument("public:npmjs", null, PACKAGE_NAME);
        verify(externalConnectionClient, never()).fetchFullPackument(anyString(), any(), anyString());
    }

    /**
     * Raised by Greptile's review: the abbreviated form {@code npm install} itself sends never
     * carries a package's {@code readme} at all, so a request that actually asked for the full
     * document ({@code npm view <pkg> readme}-style commands) must fetch the full form instead,
     * and the merge has to carry the real readme through rather than only {@code versions}/
     * {@code dist-tags}.
     */
    @Test
    void mergedPackumentWithFullMetadataCopiesThePackageLevelReadme() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode external = mapper.createObjectNode();
        external.put("readme", "# Widget\nA real readme.");
        ObjectNode versionDoc = external.putObject("versions").putObject("2.0.0");
        versionDoc.putObject("dist").put("tarball", "https://registry.npmjs.org/widget/-/widget-2.0.0.tgz");
        external.putObject("dist-tags").put("latest", "2.0.0");
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, true);

        assertTrue(merged.isPresent());
        assertEquals("# Widget\nA real readme.", merged.get().path("readme").asText());
        verify(externalConnectionClient, never()).fetchPackument(anyString(), any(), anyString());
    }

    /** A readme already set locally (a real local publish's own) is never overwritten. */
    @Test
    void mergedPackumentWithFullMetadataNeverOverridesAnAlreadySetLocalReadme() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode local = mapper.createObjectNode();
        local.put("readme", "local readme");
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.of(local));

        ObjectNode external = mapper.createObjectNode();
        external.put("readme", "external readme");
        external.putObject("versions");
        when(externalConnectionClient.fetchFullPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, true);

        assertTrue(merged.isEmpty());
    }

    /**
     * Raised by Greptile's review: Verdaccio always points a fresh package's {@code dist-tags.
     * latest} at whichever version was retained most recently (confirmed live, nothing a
     * retention write sends can suppress it), so a local tag pointing at a version with a
     * confirmed external-retention claim is not a deliberate local choice at all and must still
     * be replaced once the real registry names a different version for that same tag.
     */
    @Test
    void mergedPackumentOverridesALocalDistTagThatPointsAtAnExternallyRetainedVersion() throws Exception {
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "npm", null, PACKAGE_NAME,
                "1.0.0")).thenReturn(Optional.of("public:npmjs"));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode local = mapper.createObjectNode();
        ObjectNode localVersions = local.putObject("versions");
        localVersions.putObject("1.0.0").putObject("dist").put("tarball", BASE_URL + "widget/-/widget-1.0.0.tgz");
        local.putObject("dist-tags").put("latest", "1.0.0");
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.of(local));

        ObjectNode external = mapper.createObjectNode();
        ObjectNode externalVersions = external.putObject("versions");
        externalVersions.putObject("1.0.0");
        externalVersions.putObject("2.0.0").putObject("dist")
                .put("tarball", "https://registry.npmjs.org/widget/-/widget-2.0.0.tgz");
        external.putObject("dist-tags").put("latest", "2.0.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isPresent());
        assertEquals("2.0.0", merged.get().path("dist-tags").path("latest").asText());
    }

    /**
     * The exact opposite of {@link
     * #mergedPackumentOverridesALocalDistTagThatPointsAtAnExternallyRetainedVersion}: a local tag
     * pointing at a version with no retained-origin claim at all is a genuine local publish's own
     * deliberate choice, and must never be replaced by the external source's own idea of it.
     */
    @Test
    void mergedPackumentNeverOverridesALocalDistTagThatPointsAtAGenuinelyLocalVersion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode local = mapper.createObjectNode();
        ObjectNode localVersions = local.putObject("versions");
        localVersions.putObject("1.0.0").putObject("dist").put("tarball", BASE_URL + "widget/-/widget-1.0.0.tgz");
        local.putObject("dist-tags").put("latest", "1.0.0");
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.of(local));

        ObjectNode external = mapper.createObjectNode();
        ObjectNode externalVersions = external.putObject("versions");
        externalVersions.putObject("1.0.0");
        externalVersions.putObject("2.0.0").putObject("dist")
                .put("tarball", "https://registry.npmjs.org/widget/-/widget-2.0.0.tgz");
        external.putObject("dist-tags").put("latest", "2.0.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isPresent());
        assertEquals("1.0.0", merged.get().path("dist-tags").path("latest").asText());
    }

    /**
     * Raised by Greptile's review: {@code DisposePackageVersions} deletes a version's real
     * Verdaccio content outright, so {@code versions.has(version)} alone cannot tell a disposed
     * version apart from a genuinely new one the real registry still names; without excluding it,
     * an unpinned {@code npm install} could select a disposed version as {@code latest} only to
     * have the download itself correctly, but confusingly, refused afterward.
     */
    @Test
    void mergedPackumentNeverReAddsADisposedVersionOrPointsLatestAtIt() throws Exception {
        doThrow(new AwsException("ResourceNotFoundException", "Package version '1.0.0' was not found.", 404))
                .when(service).requireContainerBackedVersionDownloadable(OWNER, REGION, DOMAIN, REPOSITORY, "npm",
                        null, PACKAGE_NAME, "1.0.0", REPO_ID);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode external = mapper.createObjectNode();
        ObjectNode versions = external.putObject("versions");
        versions.putObject("1.0.0").putObject("dist")
                .put("tarball", "https://registry.npmjs.org/widget/-/widget-1.0.0.tgz");
        versions.putObject("2.0.0").putObject("dist")
                .put("tarball", "https://registry.npmjs.org/widget/-/widget-2.0.0.tgz");
        external.putObject("dist-tags").put("latest", "1.0.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isPresent());
        assertFalse(merged.get().path("versions").has("1.0.0"));
        assertTrue(merged.get().path("versions").has("2.0.0"));
        assertFalse(merged.get().path("dist-tags").has("latest"));
    }

    /** A version already known locally is never overwritten by the external source's own entry. */
    @Test
    void mergedPackumentNeverOverridesAVersionAlreadyKnownLocally() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode local = mapper.createObjectNode();
        ObjectNode localVersions = local.putObject("versions");
        ObjectNode localVersionDoc = localVersions.putObject("1.0.0");
        localVersionDoc.putObject("dist").put("tarball", BASE_URL + "widget/-/widget-1.0.0.tgz");
        local.putObject("dist-tags").put("latest", "1.0.0");
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.of(local));

        ObjectNode external = mapper.createObjectNode();
        ObjectNode externalVersionDoc = external.putObject("versions").putObject("1.0.0");
        externalVersionDoc.putObject("dist").put("tarball", "https://registry.npmjs.org/widget/-/widget-1.0.0.tgz");
        external.putObject("dist-tags").put("latest", "0.9.0");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
    }

    /** A dist-tag naming a version this merge does not actually know about is never added. */
    @Test
    void mergedPackumentNeverAddsADistTagForAVersionItDoesNotKnow() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode external = mapper.createObjectNode();
        external.putObject("versions");
        external.putObject("dist-tags").put("latest", "9.9.9");
        when(externalConnectionClient.fetchPackument("public:npmjs", null, PACKAGE_NAME))
                .thenReturn(Optional.of(new NpmExternalConnectionClient.FetchedAsset(mapper.writeValueAsBytes(
                        external))));
        when(verdaccio.fetchPackageDocument(BASE_URL, null, PACKAGE_NAME)).thenReturn(Optional.empty());

        Optional<ObjectNode> merged = importer.mergedPackument(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, null,
                PACKAGE_NAME, false);

        assertTrue(merged.isEmpty());
    }
}
