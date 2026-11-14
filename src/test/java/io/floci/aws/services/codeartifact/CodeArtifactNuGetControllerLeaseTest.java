package io.floci.aws.services.codeartifact;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeArtifactNuGetControllerLeaseTest {
    private static final String REGION = "us-east-1";
    private static final String OWNER = "123456789012";
    private static final String DOMAIN = "dom";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "nuget-repo-id";

    private CodeArtifactService service;
    private BagetterSidecarClient bagetter;
    private CodeArtifactNuGetController controller;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        bagetter = mock(BagetterSidecarClient.class);
        HttpClient http = mock(HttpClient.class);
        controller = new CodeArtifactNuGetController(service, bagetter,
                mock(CodeArtifactNuGetExternalConnectionImporter.class),
                mock(CodeArtifactNuGetUpstreamRepositoryImporter.class), http);
        headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token");
        when(service.resolveAuthorizationToken("token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope(OWNER, REGION)));
        when(service.resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, REPOSITORY, bagetter))
                .thenReturn(REPO_ID);
        when(bagetter.ensureReady(REPO_ID, null)).thenReturn("http://127.0.0.1:1234");
        when(service.getRepositoryEndpoint(REGION, DOMAIN, OWNER, REPOSITORY, "nuget", null))
                .thenReturn("http://localhost:4566/codeartifact/nuget/dom/repo/");
    }

    @Test
    void serviceIndexReleasesItsLease() {
        assertEquals(200, controller.serviceIndex(headers, DOMAIN, REPOSITORY).getStatus());
        verify(service).resolveContainerIdAndBeginUse("nuget", REGION, DOMAIN, OWNER, REPOSITORY, bagetter);
        verify(bagetter).endUse(REPO_ID);
    }

    @Test
    void packageVersionsReleasesItsLeaseAfterProxying() throws Exception {
        HttpClient http = mock(HttpClient.class);
        controller = new CodeArtifactNuGetController(service, bagetter,
                mock(CodeArtifactNuGetExternalConnectionImporter.class),
                mock(CodeArtifactNuGetUpstreamRepositoryImporter.class), http);
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(404);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(reply);

        assertEquals(404, controller.packageVersions(headers, DOMAIN, REPOSITORY, "pkg").getStatus());
        verify(bagetter).endUse(REPO_ID);
    }

    @Test
    void packageContentReleasesItsLeaseOnAnEarlyNotFound() {
        doThrow(mock(AwsException.class)).when(service).requireContainerBackedVersionDownloadable(
                OWNER, REGION, DOMAIN, REPOSITORY, "nuget", null, "pkg", "1.0.0", REPO_ID);

        assertEquals(404, controller.packageContent(headers, DOMAIN, REPOSITORY, "pkg", "1.0.0", "pkg.nupkg")
                .getStatus());
        verify(bagetter).endUse(REPO_ID);
    }

    /**
     * The {@code .nupkg} branch is the one real asset download in this controller (a readme or
     * {@code .nuspec} stays small): this proves it streams the sidecar's response straight through
     * rather than buffering it first, and still releases its lease afterward.
     */
    @Test
    void packageContentStreamsTheNupkgAndReleasesItsLease() throws Exception {
        HttpClient http = mock(HttpClient.class);
        controller = new CodeArtifactNuGetController(service, bagetter,
                mock(CodeArtifactNuGetExternalConnectionImporter.class),
                mock(CodeArtifactNuGetUpstreamRepositoryImporter.class), http);
        when(bagetter.packageVersionExists(REPO_ID, DOMAIN, REPOSITORY, null, "pkg", "1.0.0")).thenReturn(true);
        byte[] nupkgBytes = "nupkg-bytes".getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> reply = mock(HttpResponse.class);
        when(reply.statusCode()).thenReturn(200);
        when(reply.body()).thenReturn(new ByteArrayInputStream(nupkgBytes));
        when(reply.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of("Content-Type", List.of("application/octet-stream")), (name, value) -> true));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(reply);

        Response response = controller.packageContent(headers, DOMAIN, REPOSITORY, "pkg", "1.0.0", "pkg.nupkg");

        // The lease must still be held once packageContent itself has returned: a concurrent
        // DeleteRepository must not be able to tear down the BaGetter container before the entity
        // stream below is actually drained.
        verify(bagetter, never()).endUse(REPO_ID);
        assertEquals(200, response.getStatus());
        assertEquals("nupkg-bytes", new String(readEntity(response), StandardCharsets.UTF_8));
        verify(bagetter).endUse(REPO_ID);
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
    void pushReleasesItsLeaseOnInvalidMultipartInput() {
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn("application/octet-stream");

        Response response = controller.push(headers, DOMAIN, REPOSITORY, new ByteArrayInputStream(new byte[] {1}));

        assertEquals(400, response.getStatus());
        verify(bagetter).endUse(REPO_ID);
    }

    @Test
    void pushDeletesTheExtractedStagedNupkgFileEvenWhenTheFilePartIsTruncatedBeforeItsClosingBoundary()
            throws Exception {
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE))
                .thenReturn("multipart/form-data; boundary=boundary123");
        AtomicReference<Path> stagedWholeBody = new AtomicReference<>();
        when(service.stageAssetBody(any())).thenAnswer(invocation -> {
            InputStream in = invocation.getArgument(0);
            Path staged = Files.createTempFile("nuget-controller-test-", ".bin");
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            stagedWholeBody.set(staged);
            return staged;
        });
        doAnswer(invocation -> {
            Files.deleteIfExists(invocation.getArgument(0));
            return null;
        }).when(service).deleteStagedAssetBody(any());
        // No closing "--boundary123--": extractFilePart has already created the destination file
        // and started writing into it by the time copyUntilDelimiter runs off the end of the
        // staged body and throws, so this exercises the 400 response's own cleanup path.
        String truncated = "--boundary123\r\n"
                + "Content-Disposition: form-data; name=\"package\"; filename=\"my.pkg.1.0.0.nupkg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "partial, never-terminated file content";

        Response response = controller.push(headers, DOMAIN, REPOSITORY,
                new ByteArrayInputStream(truncated.getBytes(StandardCharsets.UTF_8)));

        assertEquals(400, response.getStatus());
        Path stagedNupkg = stagedWholeBody.get().resolveSibling(stagedWholeBody.get().getFileName() + ".nupkg");
        assertFalse(Files.exists(stagedNupkg), "a staged nupkg file from a failed extraction must not leak: " + stagedNupkg);
    }

    @Test
    void failedSidecarStartupReleasesTheLease() {
        when(bagetter.ensureReady(REPO_ID, null)).thenThrow(new IllegalStateException("Docker unavailable"));

        assertThrows(IllegalStateException.class, () -> controller.serviceIndex(headers, DOMAIN, REPOSITORY));
        verify(bagetter).endUse(REPO_ID);
    }

    @Test
    void invalidLeaseCompletionIsNotHidden() {
        IllegalStateException invalidLease = new IllegalStateException("No active BaGetter sidecar use");
        doThrow(invalidLease).when(bagetter).endUse(REPO_ID);

        assertSame(invalidLease,
                assertThrows(IllegalStateException.class, () -> controller.serviceIndex(headers, DOMAIN, REPOSITORY)));
        verify(bagetter).endUse(REPO_ID);
    }
}
