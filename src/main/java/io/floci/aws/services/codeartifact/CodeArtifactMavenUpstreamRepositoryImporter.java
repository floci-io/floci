package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@code 16f-UPSTREAM-REPO}: retains a Maven package version from another repository in the same
 * domain, named in {@code repository}'s own {@code upstreams} list (distinct from, and mutually
 * exclusive with, {@link CodeArtifactMavenExternalConnectionImporter}'s real public registries).
 * Reuses the exact claim/fence mechanism {@link CodeArtifactService#claimMavenRetainedVersionOrigin}
 * already provides (see its own javadoc for the full race this closes), with real differences from
 * the external-connection case:
 *
 * <ul>
 *   <li><b>Transitive search, in priority order.</b> {@link
 *   CodeArtifactService#resolveUpstreamSearchOrder} walks {@code repository}'s own direct
 *   upstreams first, then theirs, breadth-first, matching AWS's own documented "Upstream
 *   repository priority order." The first candidate with the requested asset wins; it may be
 *   several hops deep, not necessarily a direct upstream of {@code repository} itself. Reposilite
 *   scopes entirely by its own {@code repoId}, never by path, so the same {@code gav} string is
 *   checked against each candidate's own id in turn.</li>
 *   <li><b>No SNAPSHOT exclusion.</b> The external-connection importer's "release versions only"
 *   rule is AWS's own documented restriction for a real public registry, which never has a
 *   Floci-internal SNAPSHOT concept at all; nothing says the same for a same-domain upstream, and
 *   a SNAPSHOT already published into one is a perfectly real, already-retained artifact.</li>
 *   <li><b>Archived/Disposed propagation.</b> A candidate's own copy of the version must still be
 *   downloadable there (checked via {@link
 *   CodeArtifactService#requireContainerBackedVersionDownloadable}): the walk skips an Archived or
 *   Disposed candidate and continues to the next one, exactly as AWS documents a disposed/archived
 *   upstream copy being treated as not found by a downstream request. The real public-registry
 *   importer has no equivalent, since "the real public source has no CodeArtifact-shaped status to
 *   check" (see its own javadoc): a same-domain upstream repository very much does, for the
 *   metadata merge below too.</li>
 * </ul>
 *
 * <p>Standard-sibling-asset grouping (pom/jar/javadoc/sources imported together for a standard
 * asset name) carries over verbatim from the external-connection importer: it is Maven-protocol
 * behavior, not specific to where the bytes came from.
 */
@ApplicationScoped
public class CodeArtifactMavenUpstreamRepositoryImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactMavenUpstreamRepositoryImporter.class);

    private final CodeArtifactService service;
    private final ReposiliteSidecarClient reposilite;

    @Inject
    public CodeArtifactMavenUpstreamRepositoryImporter(CodeArtifactService service,
            ReposiliteSidecarClient reposilite) {
        this.service = service;
        this.reposilite = reposilite;
    }

    /**
     * Mirrors {@link CodeArtifactMavenExternalConnectionImporter#tryImport}'s own contract exactly,
     * sourced from the first eligible repository in {@code repository}'s own upstream chain instead
     * of a real public registry. A bare group/artifact-level {@code maven-metadata.xml} (no version
     * segment in {@code gav}) is resolved by {@link #tryImportUpstreamMetadata} instead, the same
     * split that importer's own javadoc documents.
     */
    public boolean tryImport(String region, String domain, String owner, String repository, String repoId,
            String gav) {
        Optional<String[]> coordinate = CodeArtifactMavenController.parseNamespaceAndPackageName(gav);
        if (coordinate.isEmpty()) {
            return false;
        }
        String namespace = coordinate.get()[0];
        String packageName = coordinate.get()[1];
        String version = coordinate.get()[2];
        if (version == null) {
            return gav.endsWith("/maven-metadata.xml") && tryImportUpstreamMetadata(region, domain, owner,
                    repository, repoId, namespace, packageName);
        }

        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "maven", namespace, packageName,
                "INTERNAL_UPSTREAM")) {
            return false;
        }

        String upstreamRepository = null;
        String upstreamRepoId = null;
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("maven", region, domain, owner, candidate,
                        reposilite);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like "not
                // found" there, the same way an Archived/Disposed candidate below is.
                continue;
            }
            // Held past this loop, for the winning candidate only, until the claim/fetch/write
            // below are entirely done with it (see the try/finally right after this loop).
            boolean eligible = false;
            try {
                if (!reposilite.artifactExists(candidateRepoId, gav)) {
                    continue;
                }
                try {
                    service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "maven",
                            namespace, packageName, version, candidateRepoId);
                } catch (AwsException e) {
                    // Archived or Disposed at this candidate: treated exactly like "not found"
                    // there, so the walk continues to the next candidate rather than stopping.
                    continue;
                }
                eligible = true;
                upstreamRepository = candidate;
                upstreamRepoId = candidateRepoId;
                break;
            } finally {
                if (!eligible) {
                    reposilite.endUse(candidateRepoId);
                }
            }
        }
        if (upstreamRepository == null) {
            return false;
        }

        try {
            return tryImportFromClaimedCandidate(region, domain, owner, repository, repoId, gav, namespace,
                    packageName, version, upstreamRepository, upstreamRepoId);
        } finally {
            reposilite.endUse(upstreamRepoId);
        }
    }

    private boolean tryImportFromClaimedCandidate(String region, String domain, String owner, String repository,
            String repoId, String gav, String namespace, String packageName, String version,
            String upstreamRepository, String upstreamRepoId) {
        // Snapshotted before the claim and the real writes below, not after, the same ordering
        // CodeArtifactMavenExternalConnectionImporter#tryImport already relies on.
        long fenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository, "maven",
                namespace, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "maven", namespace, packageName, version);
        CodeArtifactService.MavenClaimOutcome claim = service.claimMavenRetainedVersionOrigin(region, domain, owner,
                repository, namespace, packageName, version, upstreamRepository,
                RetentionOriginKind.UPSTREAM_REPOSITORY, fenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        int lastSlash = gav.lastIndexOf('/');
        String directory = gav.substring(0, lastSlash + 1);
        String filename = gav.substring(lastSlash + 1);
        List<String> standardNames = standardAssetFilenames(packageName, version);
        List<String> candidateFilenames = standardNames.contains(filename) ? standardNames : List.of(filename);

        boolean importedRequestedAsset = false;
        boolean importedAny = false;
        try {
            for (String candidateFilename : candidateFilenames) {
                String candidateGav = directory + candidateFilename;
                Optional<ReposiliteSidecarClient.StreamedArtifact> fetched;
                try {
                    fetched = reposilite.fetchArtifactStreamed(upstreamRepoId, candidateGav);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not fetch {0} from upstream repository {1} while retaining a Maven "
                            + "release through a same-domain upstream; skipping this candidate.", candidateGav,
                            upstreamRepository);
                    continue;
                }
                if (fetched.isEmpty()) {
                    continue;
                }
                // ReposiliteSidecarClient.StreamedArtifact, not RepositorySidecarManager
                // .StreamedAsset: Maven never routes through that shared interface (it speaks
                // directly to ReposiliteSidecarClient everywhere else too), so this stages and
                // closes the stream itself instead of reusing CodeArtifactService's own
                // stageStreamedAsset, which only accepts the other record type.
                Path staged;
                try {
                    staged = service.stageAssetBody(fetched.get().content());
                } finally {
                    StreamingResponses.closeQuietly(fetched.get().content());
                }
                // Not fetched.get().contentLength(): that's -1 whenever the upstream sidecar's own
                // response carried no Content-Length header (see that record's own javadoc), and
                // forwarding -1 into byte accounting would shrink DescribeDomain's assetSizeBytes
                // instead of growing it. The staged file's own real size is always correct.
                long contentLength;
                try {
                    contentLength = Files.size(staged);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                try {
                    // The fence check and the real write happen inside the very same synchronized
                    // call DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions
                    // already hold across their own entire operation, so a write here and one of
                    // those four operations can never interleave.
                    CodeArtifactService.MavenDeployAttemptResult result = service.deployMavenAssetUnderVersionFence(
                            region, domain, owner, repository, namespace, packageName, version,
                            versionFenceSnapshot, fenceSnapshot, claim.episodeId(), reposilite, repoId, candidateGav,
                            staged, contentLength);
                    if (!result.fenceMatched()) {
                        // The version itself is gone: every later candidate filename in this same
                        // attempt would fail the exact same way, so none of them are attempted.
                        break;
                    }
                    ReposiliteSidecarClient.DeployOutcome outcome = result.outcome();
                    if (outcome == null || (outcome.status() != 200 && outcome.status() != 201)) {
                        continue;
                    }
                    importedAny = true;
                    if (candidateFilename.equals(filename)) {
                        importedRequestedAsset = true;
                    }
                } finally {
                    service.deleteStagedAssetBody(staged);
                }
            }
        } finally {
            if (importedAny) {
                service.ensureRetainedPackageOriginDefault(region, domain, owner, repository, "maven", namespace,
                        packageName, fenceSnapshot);
            }
            service.finishMavenRetainedVersionOriginAttempt(region, domain, owner, repository, namespace,
                    packageName, version, claim.episodeId(), importedAny);
        }
        return importedRequestedAsset;
    }

    /**
     * Mirrors {@link CodeArtifactMavenExternalConnectionImporter#tryImportMetadata}'s own contract:
     * merges in whichever release versions the first eligible upstream candidate's own {@code
     * maven-metadata.xml} names, so a version range or a Gradle dynamic version can resolve against
     * an upstream-configured repository too. The first candidate that responds with any metadata at
     * all wins (parallel to the asset case's "first match wins"); every version it names is still
     * individually checked for Archived/Disposed status, at both {@code repository} itself (the
     * existing protection the external-connection importer's own javadoc explains) and the winning
     * candidate (new: a real upstream repository has a real status to check, unlike a public
     * registry).
     */
    private boolean tryImportUpstreamMetadata(String region, String domain, String owner, String repository,
            String repoId, String namespace, String packageName) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "maven", namespace, packageName,
                "INTERNAL_UPSTREAM")) {
            return false;
        }
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "maven", namespace, packageName);
        String metadataGav = namespace.replace('.', '/') + "/" + packageName + "/maven-metadata.xml";

        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("maven", region, domain, owner, candidate,
                        reposilite);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like
                // "nothing there" below.
                continue;
            }
            // Nothing escapes this iteration (the merge below both reads and writes entirely
            // within this same candidate's lease), so it is always released before moving on,
            // whether this candidate won or not.
            try {
                Optional<ReposiliteSidecarClient.StreamedArtifact> fetched;
                try {
                    fetched = reposilite.fetchArtifactStreamed(candidateRepoId, metadataGav);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not fetch {0}/{1}''s maven-metadata.xml from upstream repository {2} while "
                            + "resolving its versions; trying the next candidate.", namespace, packageName,
                            candidate);
                    continue;
                }
                if (fetched.isEmpty()) {
                    continue;
                }
                byte[] metadataXml;
                try (InputStream in = fetched.get().content()) {
                    metadataXml = in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Predicate<String> versionAllowed = version -> {
                    try {
                        service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "maven",
                                namespace, packageName, version, repoId);
                        service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "maven",
                                namespace, packageName, version, candidateRepoId);
                        return true;
                    } catch (AwsException e) {
                        return false;
                    }
                };
                // If this candidate's own metadata names nothing eligible (every version Archived
                // or Disposed at either side), it is exactly like the asset case's Archived/
                // Disposed candidate: move on to the next one instead of stopping the search here.
                if (ReposiliteSidecarClient.eligibleReleaseVersions(metadataXml, versionAllowed, true).isEmpty()) {
                    continue;
                }
                return service.mergeMavenMetadataUnderPackageFence(region, domain, owner, repository, namespace,
                        packageName, packageFenceSnapshot, reposilite, repoId, metadataXml, versionAllowed, true);
            } finally {
                reposilite.endUse(candidateRepoId);
            }
        }
        return false;
    }

    private static List<String> standardAssetFilenames(String artifactId, String version) {
        String base = artifactId + "-" + version;
        return List.of(base + ".pom", base + ".jar", base + "-javadoc.jar", base + "-sources.jar");
    }
}
