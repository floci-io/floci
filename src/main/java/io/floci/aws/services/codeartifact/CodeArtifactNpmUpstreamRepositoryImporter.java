package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 16f-UPSTREAM-REPO}: retains npm packages and package versions from another repository in
 * the same domain, named in {@code repository}'s own {@code upstreams} list (distinct from, and
 * mutually exclusive with, {@link CodeArtifactNpmExternalConnectionImporter}'s real public
 * registry). Reuses the exact claim/fence mechanism {@link
 * CodeArtifactService#claimRetainedVersionOrigin} already provides, with real differences from the
 * external-connection case:
 *
 * <ul>
 *   <li><b>One read, not two, and no abbreviated/full distinction.</b> The external-connection
 *   importer fetches the real registry's packument twice, in two different shapes (abbreviated
 *   for the hot {@code npm install} path, full only when a client's own {@code Accept} header asks
 *   for it, plus a second full fetch inside {@link
 *   CodeArtifactNpmExternalConnectionImporter#tryImportAsset} purely to recover a version's
 *   dependencies/engines/scripts and the package's readme), because a real network call to
 *   npmjs.org is the expensive part being minimized. A same-domain upstream is another repository
 *   this emulator itself already backs: {@link VerdaccioSidecarClient#fetchPackageDocument} always
 *   returns the one real document Verdaccio actually stores, package-level readme and every
 *   version's own dependencies/engines/scripts included, so one read of the resolved candidate's
 *   own document is both the merge source and the asset-write's own {@code versionDoc}/readme
 *   source.</li>
 *   <li><b>Transitive search, in priority order.</b> {@link
 *   CodeArtifactService#resolveUpstreamSearchOrder} walks {@code repository}'s own direct
 *   upstreams first, then theirs, breadth-first. The first candidate whose own packument names the
 *   requested version wins; it may be several hops deep.</li>
 *   <li><b>Archived/Disposed propagation.</b> A candidate's own copy of the version must still be
 *   downloadable there (checked via {@link
 *   CodeArtifactService#requireContainerBackedVersionDownloadable}): the walk skips an Archived or
 *   Disposed candidate and continues to the next one.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeArtifactNpmUpstreamRepositoryImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactNpmUpstreamRepositoryImporter.class);

    /** Mirrors {@code CodeArtifactNpmExternalConnectionImporter}'s own identical list. */
    private static final List<String> PACKAGE_LEVEL_DESCRIPTIVE_FIELDS = List.of("description", "readme",
            "readmeFilename", "homepage", "license", "keywords", "author", "repository", "bugs", "maintainers");

    private final CodeArtifactService service;
    private final VerdaccioSidecarClient verdaccio;
    private final ObjectMapper mapper;

    @Inject
    public CodeArtifactNpmUpstreamRepositoryImporter(CodeArtifactService service, VerdaccioSidecarClient verdaccio,
            ObjectMapper mapper) {
        this.service = service;
        this.verdaccio = verdaccio;
        this.mapper = mapper;
    }

    /**
     * Mirrors {@link CodeArtifactNpmExternalConnectionImporter#mergedPackument}'s own contract,
     * sourced from the first eligible upstream candidate's own stored packument instead of a real
     * registry fetch. Always merges every package-level descriptive field the candidate's document
     * has (there is no abbreviated-vs-full distinction for a same-domain read, see this class's own
     * javadoc).
     */
    public Optional<ObjectNode> mergedPackument(String region, String domain, String owner, String repository,
            String repoId, String namespace, String packageName) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return Optional.empty();
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "npm", namespace, packageName,
                "INTERNAL_UPSTREAM")) {
            return Optional.empty();
        }

        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("npm", region, domain, owner, candidate,
                        verdaccio);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like
                // "nothing there" below.
                continue;
            }
            try {
                String candidateBaseUrl = verdaccio.ensureReady(candidateRepoId,
                        verdaccio.publicUrl(domain, candidate));
                JsonNode external = verdaccio.fetchPackageDocument(candidateBaseUrl, namespace, packageName)
                        .orElse(null);
                if (external == null) {
                    continue;
                }
                Optional<ObjectNode> merged = mergeFromCandidate(region, domain, owner, repository, repoId,
                        namespace, packageName, candidate, candidateRepoId, external);
                if (merged.isPresent()) {
                    return merged;
                }
            } finally {
                // Nothing escapes this iteration (the returned node is a plain JSON merge result,
                // not this candidate's own id), so the lease is always released before moving on,
                // whether this candidate won or not.
                verdaccio.endUse(candidateRepoId);
            }
        }
        return Optional.empty();
    }

    private Optional<ObjectNode> mergeFromCandidate(String region, String domain, String owner, String repository,
            String repoId, String namespace, String packageName, String candidate, String candidateRepoId,
            JsonNode external) {
        String publicBaseUrl = verdaccio.publicUrl(domain, repository);
        String containerBaseUrl = verdaccio.ensureReady(repoId, publicBaseUrl);
        JsonNode local = verdaccio.fetchPackageDocument(containerBaseUrl, namespace, packageName).orElse(null);
        ObjectNode merged = local != null ? ((ObjectNode) local).deepCopy() : mapper.createObjectNode();
        String npmName = namespace == null ? packageName : "@" + namespace + "/" + packageName;
        merged.put("name", npmName);
        if (!merged.has("_id")) {
            merged.put("_id", npmName);
        }
        boolean addedAnything = false;
        for (String field : PACKAGE_LEVEL_DESCRIPTIVE_FIELDS) {
            if (!merged.has(field) && external.has(field)) {
                merged.set(field, external.get(field));
                addedAnything = true;
            }
        }
        if (!merged.has("description")) {
            merged.put("description", "");
        }
        ObjectNode versions = merged.has("versions") && merged.get("versions").isObject()
                ? (ObjectNode) merged.get("versions") : merged.putObject("versions");
        ObjectNode distTags = merged.has("dist-tags") && merged.get("dist-tags").isObject()
                ? (ObjectNode) merged.get("dist-tags") : merged.putObject("dist-tags");

        for (Map.Entry<String, JsonNode> entry : external.path("versions").properties()) {
            String version = entry.getKey();
            if (versions.has(version)) {
                continue;
            }
            try {
                service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "npm",
                        namespace, packageName, version, repoId);
                service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "npm",
                        namespace, packageName, version, candidateRepoId);
            } catch (AwsException e) {
                continue;
            }
            ObjectNode versionDoc = ((ObjectNode) entry.getValue()).deepCopy();
            versionDoc.put("name", npmName);
            versionDoc.put("version", version);
            ObjectNode dist = versionDoc.has("dist") && versionDoc.get("dist").isObject()
                    ? (ObjectNode) versionDoc.get("dist") : versionDoc.putObject("dist");
            dist.put("tarball", publicBaseUrl + packagePath(namespace, packageName) + "/-/"
                    + tarballFilename(packageName, version));
            versions.set(version, versionDoc);
            addedAnything = true;
        }
        for (Map.Entry<String, JsonNode> tag : external.path("dist-tags").properties()) {
            String tagVersion = tag.getValue().asText(null);
            if (tagVersion == null || !versions.has(tagVersion)) {
                continue;
            }
            String currentLocalVersion = distTags.path(tag.getKey()).asText(null);
            boolean currentlyUnset = currentLocalVersion == null;
            boolean currentlyPointsAtARetainedVersion = currentLocalVersion != null
                    && (service.retainedVersionOriginConnection(region, domain, owner, repository, "npm", namespace,
                            packageName, currentLocalVersion).isPresent()
                        || service.retainedUpstreamRepositoryOrigin(region, domain, owner, repository, "npm",
                            namespace, packageName, currentLocalVersion).isPresent());
            if (currentlyUnset || currentlyPointsAtARetainedVersion) {
                distTags.set(tag.getKey(), tag.getValue());
                addedAnything = true;
            }
        }
        return addedAnything ? Optional.of(merged) : Optional.empty();
    }

    /**
     * Mirrors {@link CodeArtifactNpmExternalConnectionImporter#tryImportAsset}'s own contract.
     * {@code version} is already resolved by the caller (npm's download route names it directly,
     * unlike pypi's), so no candidate-version resolution is needed here, only which candidate has
     * it.
     */
    public boolean tryImportAsset(String region, String domain, String owner, String repository, String repoId,
            String namespace, String packageName, String version, String assetName) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "npm", namespace, packageName,
                "INTERNAL_UPSTREAM")) {
            return false;
        }

        String upstreamRepository = null;
        String upstreamRepoId = null;
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("npm", region, domain, owner, candidate,
                        verdaccio);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like "not
                // found" there, the same way an Archived/Disposed candidate below is.
                continue;
            }
            // Held past this loop, for the winning candidate only, until the claim/fetch/write
            // below are entirely done with it (see the try/finally right after this loop).
            boolean eligible = false;
            try {
                if (!verdaccio.packageVersionExists(candidateRepoId, domain, candidate, namespace, packageName,
                        version)) {
                    continue;
                }
                try {
                    service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "npm",
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
                    verdaccio.endUse(candidateRepoId);
                }
            }
        }
        if (upstreamRepository == null) {
            return false;
        }

        try {
            return tryImportFromClaimedCandidate(region, domain, owner, repository, repoId, namespace, packageName,
                    version, assetName, upstreamRepository, upstreamRepoId);
        } finally {
            verdaccio.endUse(upstreamRepoId);
        }
    }

    private boolean tryImportFromClaimedCandidate(String region, String domain, String owner, String repository,
            String repoId, String namespace, String packageName, String version, String assetName,
            String upstreamRepository, String upstreamRepoId) {
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "npm", namespace, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "npm", namespace, packageName, version);
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "npm", namespace, packageName, version, upstreamRepository,
                RetentionOriginKind.UPSTREAM_REPOSITORY, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        try {
            if (verdaccio.packageVersionExists(repoId, domain, repository, namespace, packageName, version)) {
                imported = service.retainedUpstreamRepositoryOrigin(region, domain, owner, repository, "npm",
                        namespace, packageName, version).filter(upstreamRepository::equals).isPresent();
                return imported;
            }
            String candidateBaseUrl = verdaccio.ensureReady(upstreamRepoId,
                    verdaccio.publicUrl(domain, upstreamRepository));
            JsonNode candidateDocument = verdaccio.fetchPackageDocument(candidateBaseUrl, namespace, packageName)
                    .orElse(null);
            JsonNode versionDoc = candidateDocument == null ? null
                    : candidateDocument.path("versions").path(version);
            if (versionDoc == null || versionDoc.isMissingNode() || versionDoc.isNull()) {
                LOG.warnv("Not retaining {0}/{1}@{2} from upstream repository {3}: its own version document "
                        + "disappeared between resolution and write.", namespace, packageName, version,
                        upstreamRepository);
                return false;
            }
            String readme = candidateDocument.path("readme").asText("");
            Optional<RepositorySidecarManager.StreamedAsset> fetched = verdaccio.fetchPackageVersionAssetStreamed(
                    upstreamRepoId, domain, upstreamRepository, namespace, packageName, version, assetName);
            if (fetched.isEmpty()) {
                return false;
            }
            Path staged = service.stageStreamedAsset(fetched.get());
            // Not fetched.get().contentLength(): that's -1 whenever the upstream sidecar's own
            // response carried no Content-Length header, and forwarding -1 into byte accounting
            // would shrink DescribeDomain's assetSizeBytes instead of growing it. The staged
            // file's own real size is always correct, the same way the existing
            // copyContainerBackedPackageVersions write loop already sources it.
            long contentLength;
            try {
                contentLength = Files.size(staged);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                // The fence check and the real write happen inside the very same synchronized call
                // DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions
                // already hold across their own entire operation, so a write here and one of
                // those four operations can never interleave.
                CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                        domain, owner, repository, "npm", namespace, packageName, version, versionFenceSnapshot,
                        packageFenceSnapshot, claim.episodeId(), repoId, assetName, contentLength,
                        () -> verdaccio.storeRetainedVersionAsset(repoId, domain, repository, namespace, packageName,
                                version, assetName, staged, contentLength, versionDoc));
                imported = result.fenceMatched() && result.wroteNewContent();
                if (imported) {
                    service.recordPublishedPackageVersionReadme(region, domain, owner, repository, "npm", namespace,
                            packageName, version, readme, versionFenceSnapshot);
                }
                return imported;
            } finally {
                service.deleteStagedAssetBody(staged);
            }
        } finally {
            if (imported) {
                service.ensureRetainedPackageOriginDefault(region, domain, owner, repository, "npm", namespace,
                        packageName, packageFenceSnapshot);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "npm", namespace,
                    packageName, version, claim.episodeId(), imported);
        }
    }

    private static String packagePath(String namespace, String packageName) {
        return namespace == null
                ? SidecarUriUtils.encodeSegment(packageName)
                : "@" + SidecarUriUtils.encodeSegment(namespace) + "/" + SidecarUriUtils.encodeSegment(packageName);
    }

    private static String tarballFilename(String packageName, String version) {
        return packageName + "-" + version + ".tgz";
    }
}
