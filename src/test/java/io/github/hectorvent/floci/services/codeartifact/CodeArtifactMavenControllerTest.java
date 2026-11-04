package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient.DeployOutcome;
import io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient.FetchedArtifact;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CodeArtifactMavenController#fetch}'s own decision of when to ask {@link
 * CodeArtifactMavenExternalConnectionImporter} to attempt an external-connection import at all,
 * not the importer's or {@link ReposiliteSidecarClient}'s own logic, which each have their own
 * tests, and {@link CodeArtifactMavenController#deploy}'s refusal of a package whose origin
 * configuration blocks publish. Both collaborators are mocked, so no real sidecar or network call
 * ever happens here.
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

    /**
     * Issue #7116: a package whose origin configuration sets publish:BLOCK must refuse a real mvn
     * deploy the way PublishPackageVersion already refuses it for the generic format, before
     * anything reaches Reposilite, so not even a byte-identical redeploy gets through.
     */
    @Test
    void aDeployToAPackageWhoseOriginConfigurationBlocksPublishIsRefusedWithoutReachingReposilite() {
        blockPublish("com.example", "widget");

        Response response = controller.deploy(headers, DOMAIN, REPOSITORY, "com/example/widget/1.0/widget-1.0.jar",
                "jar-bytes".getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        verify(reposilite, never()).deployArtifact(any(), any(), any());
        verify(service, never()).recordContainerBackedAssetBytes(any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong());
    }

    /**
     * The restriction is per package, not per version, so the bare maven-metadata.xml PUT every
     * mvn deploy also sends (no version segment, so the Archived/Disposed guard skips it) is
     * refused too.
     */
    @Test
    void aBareMetadataDeployToABlockedPackageIsRefusedToo() {
        blockPublish("com.example", "widget");

        Response response = controller.deploy(headers, DOMAIN, REPOSITORY, METADATA_GAV,
                "<metadata/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        verify(reposilite, never()).deployArtifact(any(), any(), any());
    }

    /**
     * A snapshot's version-level maven-metadata.xml (and each checksum sibling) belongs to the
     * artifact above its version directory, so a block on that artifact refuses it as well. Raised
     * by Greptile and pgermosen's review: this path used to resolve to package {@code 1.0-SNAPSHOT}
     * in namespace {@code com.example.widget}, which no block ever names.
     */
    @Test
    void aSnapshotVersionMetadataDeployToABlockedPackageIsRefusedToo() {
        blockPublish("com.example", "widget");

        for (String gav : List.of("com/example/widget/1.0-SNAPSHOT/maven-metadata.xml",
                "com/example/widget/1.0-SNAPSHOT/maven-metadata.xml.sha1")) {
            Response response = controller.deploy(headers, DOMAIN, REPOSITORY, gav,
                    "<metadata/>".getBytes(StandardCharsets.UTF_8));

            assertEquals(409, response.getStatus(), gav);
        }
        verify(reposilite, never()).deployArtifact(any(), any(), any());
    }

    @Test
    void parseNamespaceAndPackageNameResolvesSnapshotVersionMetadataToItsArtifact() {
        String[] snapshot = CodeArtifactMavenController
                .parseNamespaceAndPackageName("com/example/widget/1.0-SNAPSHOT/maven-metadata.xml").orElseThrow();
        String[] bare = CodeArtifactMavenController
                .parseNamespaceAndPackageName("com/example/widget/maven-metadata.xml").orElseThrow();

        assertArrayEquals(new String[] {"com.example", "widget", "1.0-SNAPSHOT"}, snapshot);
        assertArrayEquals(new String[] {"com.example", "widget", null}, bare);
    }

    /**
     * Greptile and pgermosen's review: {@code com/example/widget-SNAPSHOT/maven-metadata.xml} is
     * also the bare metadata of an artifact named {@code widget-SNAPSHOT}, so a block on that
     * artifact refuses it even though the path parses as a snapshot's version metadata.
     */
    @Test
    void bareMetadataOfAnArtifactWhoseNameEndsInSnapshotIsRefusedWhenThatArtifactIsBlocked() {
        blockPublish("com.example", "widget-SNAPSHOT");

        Response response = controller.deploy(headers, DOMAIN, REPOSITORY,
                "com/example/widget-SNAPSHOT/maven-metadata.xml", "<metadata/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        verify(reposilite, never()).deployArtifact(any(), any(), any());
    }

    @Test
    void publishCheckedPackagesNamesBothReadingsOnlyForAmbiguousMetadata() {
        assertEquals(List.of("com.example:widget", "com.example.widget:1.0-SNAPSHOT"),
                readings("com/example/widget/1.0-SNAPSHOT/maven-metadata.xml.sha1"));
        assertEquals(List.of("com.example:widget"), readings("com/example/widget/maven-metadata.xml"));
        assertEquals(List.of("com.example:widget"),
                readings("com/example/widget/1.0-SNAPSHOT/widget-1.0-SNAPSHOT.jar"));
    }

    private static List<String> readings(String gav) {
        return CodeArtifactMavenController.publishCheckedPackages(gav).stream()
                .map(pair -> pair[0] + ":" + pair[1])
                .toList();
    }

    private void blockPublish(String namespace, String packageName) {
        // What Reposilite would answer if the deploy got through, so a missing refusal fails on
        // the status assertion rather than on a null outcome.
        when(reposilite.deployArtifact(any(), any(), any()))
                .thenReturn(new DeployOutcome(201, true));
        doThrow(new AwsException("ConflictException", "Package '" + packageName + "' cannot be published because "
                + "its origin configuration blocks direct publish.", 409))
                .when(service).requireContainerBackedPackagePublishAllowed(eq(REGION), eq(DOMAIN), eq(OWNER),
                        eq(REPOSITORY), eq("maven"), eq(namespace), eq(packageName));
    }
}
