package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
                mock(CodeArtifactNuGetExternalConnectionImporter.class), http);
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
                mock(CodeArtifactNuGetExternalConnectionImporter.class), http);
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

    @Test
    void pushReleasesItsLeaseOnInvalidMultipartInput() {
        when(headers.getHeaderString(HttpHeaders.CONTENT_TYPE)).thenReturn("application/octet-stream");

        Response response = controller.push(headers, DOMAIN, REPOSITORY, new byte[] {1});

        assertEquals(400, response.getStatus());
        verify(bagetter).endUse(REPO_ID);
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
