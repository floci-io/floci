package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code 16f-UPSTREAM-REPO}: retains a NuGet package version from another repository in the same
 * domain, named in {@code repository}'s own {@code upstreams} list (distinct from, and mutually
 * exclusive with, {@link CodeArtifactNuGetExternalConnectionImporter}'s real public registry).
 * Reuses that importer's exact claim/fence mechanism (see its own javadoc for the full race this
 * closes), with two differences real AWS documents for a same-domain upstream rather than an
 * external connection:
 *
 * <ul>
 *   <li><b>Transitive search, in priority order.</b> {@link
 *   CodeArtifactService#resolveUpstreamSearchOrder} walks {@code repository}'s own direct
 *   upstreams first, then theirs, breadth-first, matching AWS's own documented "Upstream
 *   repository priority order." The first candidate with the requested version wins; it may be
 *   several hops deep, not necessarily a direct upstream of {@code repository} itself.</li>
 *   <li><b>Archived/Disposed propagation.</b> A candidate's own copy of the version must still be
 *   downloadable there (checked via {@link
 *   CodeArtifactService#requireContainerBackedVersionDownloadable}): an Archived or Disposed
 *   version at a candidate is skipped, the walk continuing to the next one in order, exactly as
 *   AWS documents a disposed/archived upstream copy being treated as not found by a downstream
 *   request. The real public-registry importer has no equivalent of this, since "the real public
 *   source has no CodeArtifact-shaped status to check" (see its own javadoc): a same-domain
 *   upstream repository very much does.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeArtifactNuGetUpstreamRepositoryImporter {

    private final CodeArtifactService service;
    private final BagetterSidecarClient bagetter;

    @Inject
    public CodeArtifactNuGetUpstreamRepositoryImporter(CodeArtifactService service, BagetterSidecarClient bagetter) {
        this.service = service;
        this.bagetter = bagetter;
    }

    /**
     * Attempts to retain {@code packageId}/{@code version}'s one real asset into {@code repoId}
     * from the first eligible repository in {@code repository}'s own upstream chain. Returns
     * {@code true} only when the asset is genuinely present under this claim by the time this
     * returns; a caller that gets {@code false} back falls through to its own existing not-found
     * response exactly as if this method had never been called, the same contract {@link
     * CodeArtifactNuGetExternalConnectionImporter#tryImport} already has.
     */
    public boolean tryImport(String region, String domain, String owner, String repository, String repoId,
            String packageId, String version) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "nuget", null, packageId,
                "INTERNAL_UPSTREAM")) {
            return false;
        }

        String upstreamRepository = null;
        String upstreamRepoId = null;
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("nuget", region, domain, owner, candidate,
                        bagetter);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like "not
                // found" there, the same way an Archived/Disposed candidate below is.
                continue;
            }
            // Registers this candidate's own use with the sidecar manager, under the service's
            // lock, for the exact same reason resolveContainerIdAndBeginUse's own javadoc
            // documents for a repository this call is actively reading from: a DeleteRepository
            // of this candidate racing in between resolving its id and reading its sidecar must
            // not be able to run that candidate's own cleanup until this call releases it below.
            // Held past this loop, for the winning candidate only, until the claim/fetch/write
            // below are entirely done with it (see the try/finally right after this loop).
            boolean eligible = false;
            try {
                if (!bagetter.packageVersionExists(candidateRepoId, domain, candidate, null, packageId, version)) {
                    continue;
                }
                try {
                    service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "nuget",
                            null, packageId, version, candidateRepoId);
                } catch (AwsException e) {
                    // Archived or Disposed at this candidate: AWS treats this exactly like "not
                    // found" there, so the walk continues to the next candidate rather than
                    // stopping.
                    continue;
                }
                eligible = true;
                upstreamRepository = candidate;
                upstreamRepoId = candidateRepoId;
                break;
            } finally {
                if (!eligible) {
                    bagetter.endUse(candidateRepoId);
                }
            }
        }
        if (upstreamRepository == null) {
            return false;
        }

        try {
            return tryImportFromClaimedCandidate(region, domain, owner, repository, repoId, packageId, version,
                    upstreamRepository, upstreamRepoId);
        } finally {
            bagetter.endUse(upstreamRepoId);
        }
    }

    private boolean tryImportFromClaimedCandidate(String region, String domain, String owner, String repository,
            String repoId, String packageId, String version, String upstreamRepository, String upstreamRepoId) {
        // Snapshotted before the claim and the real write below, not after, the same ordering
        // CodeArtifactNuGetExternalConnectionImporter#tryImport already relies on.
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "nuget", null, packageId);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "nuget", null, packageId, version);
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "nuget", null, packageId, version, upstreamRepository,
                RetentionOriginKind.UPSTREAM_REPOSITORY, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        try {
            // Same race this closes for the external-connection importer: a concurrent attempt
            // under this exact claim episode, or a real client's own direct push, may have
            // already landed these bytes by the time this attempt gets here.
            if (bagetter.packageVersionExists(repoId, domain, repository, null, packageId, version)) {
                imported = service.retainedUpstreamRepositoryOrigin(region, domain, owner, repository, "nuget",
                        null, packageId, version).filter(upstreamRepository::equals).isPresent();
                return imported;
            }
            List<String> assetNames = bagetter.listPackageVersionAssetNames(upstreamRepoId, domain,
                    upstreamRepository, null, packageId, version);
            if (assetNames.isEmpty()) {
                return false;
            }
            // NuGet packages have exactly one asset per version (the same real AWS fact the
            // external-connection importer's own javadoc cites), so the first (and only) listed
            // name is the real one, not a guess at the deterministic id.version.nupkg convention.
            String assetName = assetNames.get(0);
            Optional<RepositorySidecarManager.StreamedAsset> streamed = bagetter.fetchPackageVersionAssetStreamed(
                    upstreamRepoId, domain, upstreamRepository, null, packageId, version, assetName);
            if (streamed.isEmpty()) {
                return false;
            }
            Path staged = service.stageStreamedAsset(streamed.get());
            // Not streamed.get().contentLength(): that's -1 whenever the upstream sidecar's own
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
                // DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions already
                // hold across their own entire operation, so a write here and one of those four
                // operations can never interleave.
                CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                        domain, owner, repository, "nuget", null, packageId, version, versionFenceSnapshot,
                        packageFenceSnapshot, claim.episodeId(), repoId, assetName, contentLength,
                        () -> bagetter.storePackageVersionAsset(repoId, domain, repository, null, packageId, version,
                                assetName, staged, contentLength));
                imported = result.fenceMatched() && result.wroteNewContent();
                return imported;
            } finally {
                service.deleteStagedAssetBody(staged);
            }
        } finally {
            if (imported) {
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "nuget", null, packageId, packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "nuget", null, packageId,
                    version, claim.episodeId(), imported);
        }
    }
}
