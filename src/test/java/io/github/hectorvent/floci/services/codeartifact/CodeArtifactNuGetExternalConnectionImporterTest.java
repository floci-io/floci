package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactNuGetExternalConnectionImporter}'s own decision logic: when it attempts a
 * real external-connection fetch at all, and what it writes back. {@link BagetterSidecarClient}
 * and {@link NuGetExternalConnectionClient} are both mocked, so no real network or BaGetter call
 * ever happens here; their own wire contracts have their own tests.
 */
class CodeArtifactNuGetExternalConnectionImporterTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "dom";
    private static final String OWNER = "123456789012";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String PACKAGE_ID = "Widget";
    private static final String VERSION = "1.0.0";
    private static final byte[] NUPKG_CONTENT = "nupkg-content".getBytes();

    /** The episode id the default claim stub below hands out; every finish verify reuses it. */
    private static final long EPISODE_ID = 7L;

    private CodeArtifactService service;
    private BagetterSidecarClient bagetter;
    private NuGetExternalConnectionClient externalConnectionClient;
    private CodeArtifactNuGetExternalConnectionImporter importer;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        bagetter = mock(BagetterSidecarClient.class);
        externalConnectionClient = mock(NuGetExternalConnectionClient.class);
        importer = new CodeArtifactNuGetExternalConnectionImporter(service, bagetter, externalConnectionClient);

        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(
                List.of(new ExternalConnection("public:nuget-org", "nuget", "AVAILABLE")));
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
        // Unstubbed by default: BaGetter does not have the version yet, matching the controller's
        // own pre-check always calling tryImport only on a miss. Tests for the already-retained
        // race override this per call.
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_ID, VERSION))
                .thenReturn(false);
        // Unstubbed by default: the fence matched and the write landed. The real check this
        // stands in for is inside retainAssetUnderVersionFence itself, a synchronized
        // CodeArtifactService method (see its own javadoc for why), so this mock is what every
        // test configures instead.
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(), anyString(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(true, true));
    }

    @Test
    void importsFromExternalConnectionWhenNotFoundLocally() {
        when(externalConnectionClient.fetchPackage("public:nuget-org", PACKAGE_ID, VERSION))
                .thenReturn(Optional.of(new NuGetExternalConnectionClient.FetchedAsset(NUPKG_CONTENT)));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(service).retainAssetUnderVersionFence(eq(REGION), eq(DOMAIN), eq(OWNER), eq(REPOSITORY), eq("nuget"),
                isNull(), eq(PACKAGE_ID), eq(VERSION), eq(99L), eq(42L), eq(EPISODE_ID), eq(bagetter), eq(REPO_ID),
                eq("widget.1.0.0.nupkg"), eq(NUPKG_CONTENT));
        verify(service).ensureRetainedPackageOriginDefault(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, 42L);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, true);
    }

    @Test
    void returnsFalseWhenNoNuGetExternalConnectionIsConfigured() {
        CodeArtifactRepository repository = new CodeArtifactRepository();
        repository.setExternalConnections(List.of());
        when(service.describeRepository(REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(repository);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void returnsFalseWhenUpstreamRetentionIsBlocked() {
        when(service.isUpstreamRetentionBlocked(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID))
                .thenReturn(true);

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).claimRetainedVersionOrigin(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void returnsFalseWithoutFinishingWhenTheClaimIsRefused() {
        when(service.claimRetainedVersionOrigin(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION, "public:nuget-org", 42L))
                .thenReturn(new CodeArtifactService.RetainedVersionOriginClaimOutcome(false, 0L));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(bagetter, never()).packageVersionExists(anyString(), anyString(), anyString(), any(), anyString(),
                anyString());
        verify(service, never()).finishRetainedVersionOriginAttempt(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void returnsTrueWithoutFetchingWhenAConcurrentAttemptAlreadyRetainedIt() {
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION)).thenReturn(Optional.of("public:nuget-org"));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertTrue(imported);
        verify(externalConnectionClient, never()).fetchPackage(anyString(), anyString(), anyString());
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, true);
    }

    /**
     * Found by Greptile's review: {@code bagetter.packageVersionExists} alone proves nothing about
     * who put the content there. A real client's own direct push can land between this attempt's
     * own claim and this existence check; treating that as "a sibling import already retained it"
     * would wrongly mark a privately-pushed version EXTERNAL and overwrite its origin defaults.
     */
    @Test
    void doesNotMislabelAVersionADirectPushWroteDuringTheRaceAsExternallyRetained() {
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, PACKAGE_ID, VERSION))
                .thenReturn(true);
        when(service.retainedVersionOriginConnection(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null, PACKAGE_ID,
                VERSION)).thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(externalConnectionClient, never()).fetchPackage(anyString(), anyString(), anyString());
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, false);
    }

    @Test
    void returnsFalseAndFinishesWhenTheRealRepositoryDoesNotHaveIt() {
        when(externalConnectionClient.fetchPackage("public:nuget-org", PACKAGE_ID, VERSION))
                .thenReturn(Optional.empty());

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                anyString(), any());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, false);
    }

    @Test
    void aFetchThatThrowsIsTreatedLikeAMissingPackageRatherThanPropagating() {
        when(externalConnectionClient.fetchPackage("public:nuget-org", PACKAGE_ID, VERSION))
                .thenThrow(new IllegalStateException("public repository unreachable"));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, false);
    }

    @Test
    void returnsFalseAndDoesNotRecordADefaultWhenTheFenceMovedDuringTheWrite() {
        when(externalConnectionClient.fetchPackage("public:nuget-org", PACKAGE_ID, VERSION))
                .thenReturn(Optional.of(new NuGetExternalConnectionClient.FetchedAsset(NUPKG_CONTENT)));
        when(service.retainAssetUnderVersionFence(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), any(), anyString(), anyString(),
                any())).thenReturn(new CodeArtifactService.AssetRetentionAttemptResult(false, false));

        boolean imported = importer.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, PACKAGE_ID, VERSION);

        assertFalse(imported);
        verify(service, never()).ensureRetainedPackageOriginDefault(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(service).finishRetainedVersionOriginAttempt(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null,
                PACKAGE_ID, VERSION, EPISODE_ID, false);
    }
}
