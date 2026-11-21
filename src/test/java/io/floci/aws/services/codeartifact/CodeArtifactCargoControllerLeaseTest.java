package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lease and dispatch correctness for {@link CodeArtifactCargoController}, mirroring {@link
 * CodeArtifactNuGetControllerLeaseTest}'s own shape. {@link KellnrSidecarClient} is mocked, so no
 * real Kellnr call ever happens here; its own wire contract has its own tests.
 */
class CodeArtifactCargoControllerLeaseTest {
    private static final String REGION = "us-east-1";
    private static final String OWNER = "123456789012";
    private static final String DOMAIN = "dom";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "cargo-repo-id";

    private CodeArtifactService service;
    private KellnrSidecarClient kellnr;
    private CodeArtifactCargoExternalConnectionImporter externalConnectionImporter;
    private CodeArtifactCargoUpstreamRepositoryImporter upstreamRepositoryImporter;
    private CodeArtifactCargoController controller;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        kellnr = mock(KellnrSidecarClient.class);
        externalConnectionImporter = mock(CodeArtifactCargoExternalConnectionImporter.class);
        upstreamRepositoryImporter = mock(CodeArtifactCargoUpstreamRepositoryImporter.class);
        controller = new CodeArtifactCargoController(service, kellnr, externalConnectionImporter,
                upstreamRepositoryImporter, new ObjectMapper());
        // Default: no external connection configured, so the merge is a pure pass-through of
        // whatever Kellnr's own local lookup returned, exactly what the real importer itself does
        // when cargoExternalConnectionName resolves to null. Individual tests override this to
        // exercise the merge itself.
        when(externalConnectionImporter.mergeExternalVersions(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(5));
        // Same default for the upstream-repository merge: no upstreams configured, pass whatever
        // the external-connection merge already produced straight through unchanged.
        when(upstreamRepositoryImporter.mergeUpstreamRepositoryVersions(any(), any(), any(), any(), any(), any(),
                any())).thenAnswer(invocation -> invocation.getArgument(6));
        headers = mock(HttpHeaders.class);
        // A bare token, no scheme prefix: the real shape a cargo client sends (confirmed from the
        // Cargo Book's own registry-authentication page).
        when(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("token");
        when(service.resolveAuthorizationToken("token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope(OWNER, REGION)));
        when(service.resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, REPOSITORY, kellnr))
                .thenReturn(REPO_ID);
        when(service.getRepositoryEndpoint(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null))
                .thenReturn("http://localhost:4566/codeartifact/cargo/dom/repo/");
        when(service.stageAssetBody(any())).thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            Path staged = Files.createTempFile("cargo-controller-test-", ".bin");
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            return staged;
        });
        doAnswer(invocation -> {
            Files.deleteIfExists(invocation.getArgument(0));
            return null;
        }).when(service).deleteStagedAssetBody(any());
        when(kellnr.lockFor(any())).thenReturn(new Object());
        when(kellnr.publishRawWireBody(any(), any(), anyLong()))
                .thenReturn(new KellnrSidecarClient.KellnrPublishResult(200, "{\"warnings\":null}"));
    }

    @Test
    void configJsonReleasesItsLeaseWithoutEverStartingTheSidecar() {
        Response response = controller.get(headers, DOMAIN, REPOSITORY, "config.json");

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("\"dl\": "
                + "\"http://localhost:4566/codeartifact/cargo/dom/repo/api/v1/crates\""));
        verify(service).resolveContainerIdAndBeginUse("cargo", REGION, DOMAIN, OWNER, REPOSITORY, kellnr);
        verify(kellnr).endUse(REPO_ID);
        // config.json is authored directly from GetRepositoryEndpoint, never from the sidecar
        // itself, so there is no reason to ever start a container just to answer it.
        verify(kellnr, never()).ensureReady(any(), any());
    }

    @Test
    void sparseIndexLookupReleasesItsLeaseOnAMiss() {
        when(kellnr.fetchSparseIndexRaw(REPO_ID, DOMAIN, REPOSITORY, "democrate")).thenReturn(Optional.empty());

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "de/mo/democrate");

        assertEquals(404, response.getStatus());
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void sparseIndexLookupProxiesTheRealBytesVerbatim() {
        byte[] indexBytes = "{\"name\":\"democrate\",\"vers\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
        when(kellnr.fetchSparseIndexRaw(REPO_ID, DOMAIN, REPOSITORY, "democrate"))
                .thenReturn(Optional.of(indexBytes));

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "de/mo/democrate");

        assertEquals(200, response.getStatus());
        assertEquals(new String(indexBytes, StandardCharsets.UTF_8),
                new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void sparseIndexLookupServesTheMergedResultFromTheExternalConnectionImporter() {
        Optional<byte[]> local = Optional.empty();
        when(kellnr.fetchSparseIndexRaw(REPO_ID, DOMAIN, REPOSITORY, "democrate")).thenReturn(local);
        byte[] merged = "{\"name\":\"democrate\",\"vers\":\"2.0.0\"}".getBytes(StandardCharsets.UTF_8);
        when(externalConnectionImporter.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, "democrate", local))
                .thenReturn(Optional.of(merged));

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "de/mo/democrate");

        assertEquals(200, response.getStatus());
        assertEquals(new String(merged, StandardCharsets.UTF_8),
                new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
    }

    /**
     * The upstream-repository merge chains off whatever the external-connection merge already
     * produced, not off Kellnr's own raw local bytes directly: this is what lets a version only
     * the upstream chain has show up even when an external-connection merge already added
     * something else first.
     */
    @Test
    void sparseIndexLookupServesTheMergedResultFromTheUpstreamRepositoryImporter() {
        Optional<byte[]> local = Optional.empty();
        when(kellnr.fetchSparseIndexRaw(REPO_ID, DOMAIN, REPOSITORY, "democrate")).thenReturn(local);
        byte[] afterExternal = "{\"name\":\"democrate\",\"vers\":\"2.0.0\"}".getBytes(StandardCharsets.UTF_8);
        when(externalConnectionImporter.mergeExternalVersions(REGION, DOMAIN, OWNER, REPOSITORY, "democrate", local))
                .thenReturn(Optional.of(afterExternal));
        byte[] afterUpstream = ("{\"name\":\"democrate\",\"vers\":\"2.0.0\"}\n"
                + "{\"name\":\"democrate\",\"vers\":\"3.0.0\"}").getBytes(StandardCharsets.UTF_8);
        when(upstreamRepositoryImporter.mergeUpstreamRepositoryVersions(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID,
                "democrate", Optional.of(afterExternal))).thenReturn(Optional.of(afterUpstream));

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "de/mo/democrate");

        assertEquals(200, response.getStatus());
        assertEquals(new String(afterUpstream, StandardCharsets.UTF_8),
                new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
    }

    @Test
    void downloadReleasesItsLeaseOnAnEarlyNotFound() {
        doThrow(mock(AwsException.class)).when(service).requireContainerBackedVersionDownloadable(
                OWNER, REGION, DOMAIN, REPOSITORY, "cargo", null, "democrate", "1.0.0", REPO_ID);

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        assertEquals(404, response.getStatus());
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void downloadAttemptsAnExternalConnectionImportOnlyOnALocalMiss() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0"))
                .thenReturn(true);

        controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        verify(externalConnectionImporter, never()).tryImport(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void downloadAttemptsAnExternalConnectionImportOnALocalMissThenStreamsWhatItImported() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0"))
                .thenReturn(false);
        byte[] crateBytes = "imported-crate-bytes".getBytes(StandardCharsets.UTF_8);
        when(kellnr.fetchPackageVersionAssetStreamed(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0",
                "democrate-1.0.0.crate")).thenReturn(Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(crateBytes), crateBytes.length)));

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        verify(externalConnectionImporter).tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "democrate",
                "1.0.0");
        assertEquals(200, response.getStatus());
    }

    @Test
    void downloadFallsThroughToUpstreamRepositoryImportWhenExternalConnectionFindsNothing() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0"))
                .thenReturn(false);
        when(externalConnectionImporter.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "democrate", "1.0.0"))
                .thenReturn(false);

        controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        verify(upstreamRepositoryImporter).tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "democrate",
                "1.0.0");
    }

    /**
     * A repository has either an external connection or upstreams, never both: a successful
     * external-connection import must never fall through to an upstream-repository attempt too.
     */
    @Test
    void downloadNeverAttemptsUpstreamRepositoryImportWhenExternalConnectionAlreadyImported() {
        when(kellnr.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0"))
                .thenReturn(false);
        when(externalConnectionImporter.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, "democrate", "1.0.0"))
                .thenReturn(true);

        controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        verify(upstreamRepositoryImporter, never()).tryImport(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The one real asset download in this controller: proves it streams the sidecar's response
     * straight through rather than buffering it first, and still releases its lease afterward.
     */
    @Test
    void downloadStreamsTheCrateAndReleasesItsLease() throws Exception {
        byte[] crateBytes = "crate-bytes".getBytes(StandardCharsets.UTF_8);
        when(kellnr.fetchPackageVersionAssetStreamed(REPO_ID, DOMAIN, REPOSITORY, null, "democrate", "1.0.0",
                "democrate-1.0.0.crate")).thenReturn(Optional.of(new RepositorySidecarManager.StreamedAsset(
                        new ByteArrayInputStream(crateBytes), crateBytes.length)));

        Response response = controller.get(headers, DOMAIN, REPOSITORY, "api/v1/crates/democrate/1.0.0/download");

        // The lease must still be held once the method itself has returned: a concurrent
        // DeleteRepository must not be able to tear down the Kellnr container before the entity
        // stream below is actually drained.
        verify(kellnr, never()).endUse(REPO_ID);
        assertEquals(200, response.getStatus());
        assertEquals("crate-bytes", new String(readEntity(response), StandardCharsets.UTF_8));
        verify(kellnr).endUse(REPO_ID);
    }

    private static byte[] readEntity(Response response) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ((StreamingOutput) response.getEntity()).write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Test
    void publishParsesTheMetadataPrefixAndForwardsTheWholeBodyVerbatim() throws Exception {
        byte[] body = publishBody("democrate", "1.0.0", "crate-bytes");

        Response response = controller.publish(headers, DOMAIN, REPOSITORY, "api/v1/crates/new",
                new ByteArrayInputStream(body));

        assertEquals(200, response.getStatus());
        verify(service).requireContainerBackedPackagePublishAllowed(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                "democrate");
        verify(service).requireContainerBackedVersionNotArchivedOrDisposed(REGION, DOMAIN, OWNER, REPOSITORY,
                "cargo", null, "democrate", "1.0.0");
        verify(kellnr).publishRawWireBody(eq(REPO_ID), any(Path.class), eq((long) body.length));
        verify(service).completeContainerBackedPublishIfCurrentRepository(eq(REGION), eq(DOMAIN), eq(OWNER),
                eq(REPOSITORY), eq(REPO_ID), eq("cargo"), eq("democrate"), eq("1.0.0"),
                eq((long) "crate-bytes".getBytes(StandardCharsets.UTF_8).length), any(Long.class), isNull(),
                isNull(), eq(0L));
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void publishRefusesAPublishBlockedByOriginConfiguration() throws Exception {
        doThrow(new AwsException("ConflictException", "blocked", 409)).when(service)
                .requireContainerBackedPackagePublishAllowed(REGION, DOMAIN, OWNER, REPOSITORY, "cargo", null,
                        "democrate");

        Response response = controller.publish(headers, DOMAIN, REPOSITORY, "api/v1/crates/new",
                new ByteArrayInputStream(publishBody("democrate", "1.0.0", "crate-bytes")));

        assertEquals(409, response.getStatus());
        verify(kellnr, never()).publishRawWireBody(any(), any(), anyLong());
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void publishForwardsKellnrsOwnRejectionVerbatimWithoutCompletingTheBookkeeping() throws Exception {
        when(kellnr.publishRawWireBody(eq(REPO_ID), any(Path.class), anyLong()))
                .thenReturn(new KellnrSidecarClient.KellnrPublishResult(400,
                        "{\"errors\":[{\"detail\":\"ERROR: Crate with version already exists: democrate-1.0.0\"}]}"));

        Response response = controller.publish(headers, DOMAIN, REPOSITORY, "api/v1/crates/new",
                new ByteArrayInputStream(publishBody("democrate", "1.0.0", "crate-bytes")));

        assertEquals(400, response.getStatus());
        assertEquals("{\"errors\":[{\"detail\":\"ERROR: Crate with version already exists: democrate-1.0.0\"}]}",
                response.getEntity());
        verify(service, never()).completeContainerBackedPublishIfCurrentRepository(any(), any(), any(), any(),
                any(), any(), any(), any(), anyLong(), anyLong(), any(), any(), anyLong());
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void publishReleasesItsLeaseOnATruncatedMetadataPrefix() {
        byte[] truncated = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(100).array();

        Response response = controller.publish(headers, DOMAIN, REPOSITORY, "api/v1/crates/new",
                new ByteArrayInputStream(truncated));

        assertEquals(400, response.getStatus());
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void publishDeletesItsStagedFileEvenOnFailure() {
        byte[] truncated = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(100).array();

        Response response = controller.publish(headers, DOMAIN, REPOSITORY, "api/v1/crates/new",
                new ByteArrayInputStream(truncated));

        assertEquals(400, response.getStatus());
        // The shared setUp() stub for deleteStagedAssetBody actually deletes the real temp file
        // it's handed, so a real leaked file (not just a missed call) would survive to fail a
        // later assertion that reads it; verifying the call happened is the meaningful check here.
        verify(service).deleteStagedAssetBody(any());
    }

    @Test
    void failedSidecarStartupReleasesTheLease() {
        when(kellnr.fetchSparseIndexRaw(REPO_ID, DOMAIN, REPOSITORY, "democrate"))
                .thenThrow(new IllegalStateException("Docker unavailable"));

        assertThrows(IllegalStateException.class,
                () -> controller.get(headers, DOMAIN, REPOSITORY, "de/mo/democrate"));
        verify(kellnr).endUse(REPO_ID);
    }

    @Test
    void invalidLeaseCompletionIsNotHidden() {
        IllegalStateException invalidLease = new IllegalStateException("No active Kellnr sidecar use");
        doThrow(invalidLease).when(kellnr).endUse(REPO_ID);

        assertSame(invalidLease,
                assertThrows(IllegalStateException.class,
                        () -> controller.get(headers, DOMAIN, REPOSITORY, "config.json")));
        verify(kellnr).endUse(REPO_ID);
    }

    private static byte[] publishBody(String name, String version, String crateContent) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] metadata = mapper.writeValueAsBytes(Map.of("name", name, "vers", version));
        byte[] crate = crateContent.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + metadata.length + 4 + crate.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(metadata.length);
        buffer.put(metadata);
        buffer.putInt(crate.length);
        buffer.put(crate);
        return buffer.array();
    }
}
