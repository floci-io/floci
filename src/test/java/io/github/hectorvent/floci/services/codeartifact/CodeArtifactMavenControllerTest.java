package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient.FetchedArtifact;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactMavenController#fetch}'s own decision of when to ask {@link
 * CodeArtifactMavenExternalConnectionImporter} to attempt an external-connection import at all,
 * not the importer's or {@link ReposiliteSidecarClient}'s own logic, which each have their own
 * tests. Both collaborators are mocked, so no real sidecar or network call ever happens here.
 */
class CodeArtifactMavenControllerTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "123456789012";
    private static final String DOMAIN = "dom";
    private static final String REPOSITORY = "repo";
    private static final String REPO_ID = "repo-id";
    private static final String METADATA_GAV = "com/example/widget/maven-metadata.xml";

    private CodeArtifactService service;
    private ReposiliteSidecarClient reposilite;
    private CodeArtifactMavenExternalConnectionImporter externalConnectionImporter;
    private CodeArtifactMavenController controller;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        service = mock(CodeArtifactService.class);
        reposilite = mock(ReposiliteSidecarClient.class);
        externalConnectionImporter = mock(CodeArtifactMavenExternalConnectionImporter.class);
        controller = new CodeArtifactMavenController(service, reposilite, externalConnectionImporter);
        headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer some-token");
        when(service.resolveAuthorizationToken("some-token", DOMAIN))
                .thenReturn(Optional.of(new AuthorizationTokenScope(OWNER, REGION)));
        when(service.ensureFormatContainerId("maven", REGION, DOMAIN, OWNER, REPOSITORY)).thenReturn(REPO_ID);
    }

    /**
     * Raised by Greptile's review: once a bare group/artifact-level {@code maven-metadata.xml}
     * import ever succeeds, a local copy exists from then on, so the old miss-only gate
     * ({@code artifact.isEmpty()}) would never let a later request try the importer again, and a
     * public release published after that first import would stay invisible to every version
     * range or dynamic version indefinitely. This exact path is now always retried, so a second
     * request still picks up whatever the importer merges in afterward.
     */
    @Test
    void aSecondRequestForBareMetadataStillAttemptsAFreshImportEvenWhenALocalCopyAlreadyExists() {
        FetchedArtifact stale = new FetchedArtifact("<metadata/>".getBytes(StandardCharsets.UTF_8),
                "application/xml");
        FetchedArtifact refreshed = new FetchedArtifact("<metadata><refreshed/></metadata>"
                .getBytes(StandardCharsets.UTF_8), "application/xml");
        when(reposilite.fetchArtifact(REPO_ID, METADATA_GAV)).thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(refreshed));
        when(externalConnectionImporter.tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, METADATA_GAV))
                .thenReturn(true);

        Response response = controller.fetch(headers, DOMAIN, REPOSITORY, METADATA_GAV);

        assertEquals(200, response.getStatus());
        assertEquals("<metadata><refreshed/></metadata>",
                new String((byte[]) response.getEntity(), StandardCharsets.UTF_8));
        verify(externalConnectionImporter).tryImport(REGION, DOMAIN, OWNER, REPOSITORY, REPO_ID, METADATA_GAV);
    }

    /**
     * The counterpart that keeps the always-retry behavior scoped to bare metadata: a fixed
     * version's own asset never changes once retained, so re-asking the importer for it on every
     * request a local copy already answers would just be wasted external-connection traffic.
     */
    @Test
    void aFixedVersionAssetIsNotReimportedOnceALocalCopyAlreadyExists() {
        String versionGav = "com/example/widget/1.0/widget-1.0.jar";
        FetchedArtifact existing = new FetchedArtifact("real-bytes".getBytes(StandardCharsets.UTF_8),
                "application/java-archive");
        when(reposilite.fetchArtifact(REPO_ID, versionGav)).thenReturn(Optional.of(existing));

        Response response = controller.fetch(headers, DOMAIN, REPOSITORY, versionGav);

        assertEquals(200, response.getStatus());
        verify(externalConnectionImporter, never()).tryImport(any(), any(), any(), any(), any(), any());
    }
}
