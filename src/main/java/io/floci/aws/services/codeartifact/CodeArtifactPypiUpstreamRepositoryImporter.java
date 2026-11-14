package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code 16f-UPSTREAM-REPO}: retains pypi package version assets from another repository in the
 * same domain, named in {@code repository}'s own {@code upstreams} list (distinct from, and
 * mutually exclusive with, {@link CodeArtifactPypiExternalConnectionImporter}'s real public
 * registry). Reuses the exact claim/fence mechanism {@link
 * CodeArtifactService#claimRetainedVersionOrigin} already provides, with real differences from the
 * external-connection case:
 *
 * <ul>
 *   <li><b>No PEP 691 index, no sha256 re-verification, no background thread.</b> The external-
 *   connection importer's own complexity (a real registry's JSON Simple API response, a second
 *   hash pass over fetched bytes, and splitting the requested file from its siblings across a
 *   background task) exists entirely to cope with a real, slow, untrusted network call to
 *   pypi.org. A same-domain upstream is another repository this emulator itself already backs:
 *   {@code pypiserver.listPackageVersions}/{@code listPackageVersionAssetNames} already answer
 *   "which versions/files does it have" directly and fast, with nothing to re-verify against a
 *   trusted index that does not exist here, so every file of the resolved version is imported
 *   synchronously, in one pass, the same shape {@code CopyPackageVersions} already uses for a
 *   multi-asset copy.</li>
 *   <li><b>Transitive search, in priority order.</b> {@link
 *   CodeArtifactService#resolveUpstreamSearchOrder} walks {@code repository}'s own direct
 *   upstreams first, then theirs, breadth-first. The first candidate whose own version list names
 *   a version {@code filename} belongs to wins; it may be several hops deep.</li>
 *   <li><b>Archived/Disposed propagation.</b> A candidate's own copy of the version must still be
 *   downloadable there (checked via {@link
 *   CodeArtifactService#requireContainerBackedVersionDownloadable}): the walk skips an Archived or
 *   Disposed candidate and continues to the next one, exactly as AWS documents a disposed/archived
 *   upstream copy being treated as not found by a downstream request.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeArtifactPypiUpstreamRepositoryImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactPypiUpstreamRepositoryImporter.class);

    private final CodeArtifactService service;
    private final PypiserverSidecarClient pypiserver;

    // Keyed by repoId+packageName+version: see tryImportAsset's own javadoc for the double-count
    // race this closes, the same reason CodeArtifactPypiExternalConnectionImporter keeps its own
    // (separate, importer-scoped) lock map. The two never contend on the same key in practice,
    // since a repository has either an external connection or upstreams, never both.
    private final ConcurrentHashMap<String, Object> assetWriteLocks = new ConcurrentHashMap<>();

    @Inject
    public CodeArtifactPypiUpstreamRepositoryImporter(CodeArtifactService service,
            PypiserverSidecarClient pypiserver) {
        this.service = service;
        this.pypiserver = pypiserver;
    }

    /**
     * {@link CodeArtifactPypiExternalConnectionImporter#additionalExternalFilenames}'s own
     * counterpart, sourced from the first eligible upstream candidate's own version listing instead
     * of a real registry's Simple API response. Every entry reports {@code yanked=false} and
     * {@code requiresPythonOrNull=null}: neither PEP 592/700 concept exists for a same-domain
     * repository.
     */
    public List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additionalInternalFilenames(
            String region, String domain, String owner, String repository, String repoId, String packageName,
            Set<String> alreadyKnownFilenames) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return List.of();
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName,
                "INTERNAL_UPSTREAM")) {
            return List.of();
        }
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("pypi", region, domain, owner, candidate,
                        pypiserver);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like
                // "nothing listed there" below.
                continue;
            }
            try {
                List<String> versions = packageVersionsAt(candidateRepoId, domain, candidate, packageName);
                if (versions.isEmpty()) {
                    continue;
                }
                List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional = new ArrayList<>();
                for (String version : versions) {
                    try {
                        service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "pypi",
                                null, packageName, version, repoId);
                        service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "pypi",
                                null, packageName, version, candidateRepoId);
                    } catch (AwsException e) {
                        continue;
                    }
                    for (String filename : assetNamesAt(candidateRepoId, domain, candidate, packageName, version)) {
                        if (!alreadyKnownFilenames.contains(filename)) {
                            additional.add(new CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile(filename,
                                    false, null));
                        }
                    }
                }
                if (!additional.isEmpty()) {
                    return additional;
                }
            } finally {
                // Nothing escapes this iteration (the returned list is plain filename strings, not
                // this candidate's own id), so the lease is always released before moving on,
                // whether this candidate won or not.
                pypiserver.endUse(candidateRepoId);
            }
        }
        return List.of();
    }

    /**
     * Mirrors {@link CodeArtifactPypiExternalConnectionImporter#tryImportAsset}'s own contract:
     * returns {@code true} only when {@code filename} itself is genuinely present under this
     * claim by the time this returns. Every other real file of the resolved version is imported in
     * the same call, synchronously (see this class's own javadoc for why no background split is
     * needed here).
     */
    public boolean tryImportAsset(String region, String domain, String owner, String repository, String repoId,
            String filename) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        Optional<String> packageName = PypiserverSidecarClient.guessPackageName(filename);
        if (packageName.isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName.get(),
                "INTERNAL_UPSTREAM")) {
            return false;
        }

        String upstreamRepository = null;
        String upstreamRepoId = null;
        String version = null;
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("pypi", region, domain, owner, candidate,
                        pypiserver);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like "not
                // found" there, the same way an Archived/Disposed candidate below is.
                continue;
            }
            // Held past this loop, for the winning candidate only, until the claim/fetch/write
            // below are entirely done with it (see the try/finally right after this loop).
            boolean eligible = false;
            try {
                Optional<String> matchedVersion = packageVersionsAt(candidateRepoId, domain, candidate,
                        packageName.get()).stream()
                        .filter(v -> PypiserverSidecarClient.filenameEmbedsVersion(filename, packageName.get(), v))
                        .filter(v -> assetNamesAt(candidateRepoId, domain, candidate, packageName.get(), v)
                                .contains(filename))
                        .findFirst();
                if (matchedVersion.isEmpty()) {
                    continue;
                }
                try {
                    service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "pypi", null,
                            packageName.get(), matchedVersion.get(), candidateRepoId);
                } catch (AwsException e) {
                    // Archived or Disposed at this candidate: treated exactly like "not found"
                    // there, so the walk continues to the next candidate rather than stopping.
                    continue;
                }
                eligible = true;
                upstreamRepository = candidate;
                upstreamRepoId = candidateRepoId;
                version = matchedVersion.get();
                break;
            } finally {
                if (!eligible) {
                    pypiserver.endUse(candidateRepoId);
                }
            }
        }
        if (upstreamRepository == null) {
            return false;
        }

        try {
            return tryImportFromClaimedCandidate(region, domain, owner, repository, repoId, filename,
                    packageName.get(), version, upstreamRepository, upstreamRepoId);
        } finally {
            pypiserver.endUse(upstreamRepoId);
        }
    }

    private boolean tryImportFromClaimedCandidate(String region, String domain, String owner, String repository,
            String repoId, String filename, String packageName, String version, String upstreamRepository,
            String upstreamRepoId) {
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "pypi", null, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "pypi", null, packageName, version);
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "pypi", null, packageName, version, upstreamRepository,
                RetentionOriginKind.UPSTREAM_REPOSITORY, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean importedRequested = false;
        boolean importedAny = false;
        try {
            Set<String> alreadyPresent;
            try {
                alreadyPresent = Set.copyOf(pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null,
                        packageName, version));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not list {0} {1}''s existing assets on the pypiserver sidecar while attempting "
                        + "a same-domain upstream retention; treating it as not retained.", packageName,
                        version);
                return false;
            }
            if (alreadyPresent.contains(filename)) {
                // A concurrent attempt under this exact same claim episode may already have
                // written this exact file by the time this attempt gets here.
                importedAny = service.retainedUpstreamRepositoryOrigin(region, domain, owner, repository, "pypi",
                        null, packageName, version).filter(upstreamRepository::equals).isPresent();
                importedRequested = importedAny;
                return importedRequested;
            }
            for (String candidateFilename : assetNamesAt(upstreamRepoId, domain, upstreamRepository,
                    packageName, version)) {
                if (alreadyPresent.contains(candidateFilename)) {
                    continue;
                }
                FileImportStatus status = importOneAsset(region, domain, owner, repository, repoId,
                        packageName, version, upstreamRepository, upstreamRepoId, versionFenceSnapshot,
                        packageFenceSnapshot, claim.episodeId(), candidateFilename);
                if (status == FileImportStatus.FENCE_GONE) {
                    // The version itself is gone: every later candidate in this same attempt
                    // would fail the exact same way, so none of them are even attempted.
                    break;
                }
                if (status == FileImportStatus.WROTE) {
                    importedAny = true;
                    if (candidateFilename.equals(filename)) {
                        importedRequested = true;
                    }
                }
            }
            return importedRequested;
        } finally {
            if (importedAny) {
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "pypi", null, packageName, packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "pypi", null,
                    packageName, version, claim.episodeId(), importedAny);
        }
    }

    private enum FileImportStatus {
        WROTE, SKIPPED, FENCE_GONE
    }

    /**
     * Fetches and writes one asset from the resolved upstream candidate's own sidecar storage,
     * under a lock keyed to this exact destination version: closes the same double-count race
     * {@link CodeArtifactPypiExternalConnectionImporter#importOneCandidateFile}'s own javadoc
     * documents (two concurrent attempts for the same version could otherwise both see a
     * candidate filename missing and both write it, double-counting its bytes in
     * {@code DescribeDomain}'s {@code assetSizeBytes}).
     */
    private FileImportStatus importOneAsset(String region, String domain, String owner, String repository,
            String repoId, String packageName, String version, String upstreamRepository, String upstreamRepoId,
            long versionFenceSnapshot, long packageFenceSnapshot, long episodeId, String candidateFilename) {
        Optional<RepositorySidecarManager.StreamedAsset> fetched;
        try {
            fetched = pypiserver.fetchPackageVersionAssetStreamed(upstreamRepoId, domain, upstreamRepository, null,
                    packageName, version, candidateFilename);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not fetch {0} from upstream repository {1} while retaining a pypi package through "
                    + "a same-domain upstream; skipping this file.", candidateFilename, upstreamRepository);
            return FileImportStatus.SKIPPED;
        }
        if (fetched.isEmpty()) {
            return FileImportStatus.SKIPPED;
        }
        Path staged = service.stageStreamedAsset(fetched.get());
        // Not fetched.get().contentLength(): that's -1 whenever the upstream sidecar's own
        // response carried no Content-Length header, and forwarding -1 into byte accounting would
        // shrink DescribeDomain's assetSizeBytes instead of growing it. The staged file's own real
        // size is always correct, the same way the existing copyContainerBackedPackageVersions
        // write loop already sources it.
        long contentLength;
        try {
            contentLength = Files.size(staged);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            String lockKey = repoId + "|" + PypiserverSidecarClient.normalizePackageName(packageName) + "|"
                    + version;
            synchronized (assetWriteLocks.computeIfAbsent(lockKey, key -> new Object())) {
                if (pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null, packageName, version)
                        .contains(candidateFilename)) {
                    return FileImportStatus.SKIPPED;
                }
                CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                        domain, owner, repository, "pypi", null, packageName, version, versionFenceSnapshot,
                        packageFenceSnapshot, episodeId, repoId, candidateFilename, contentLength,
                        () -> pypiserver.storePackageVersionAsset(repoId, domain, repository, null, packageName,
                                version, candidateFilename, staged, contentLength));
                if (!result.fenceMatched()) {
                    return FileImportStatus.FENCE_GONE;
                }
                return result.wroteNewContent() ? FileImportStatus.WROTE : FileImportStatus.SKIPPED;
            }
        } finally {
            service.deleteStagedAssetBody(staged);
        }
    }

    private List<String> packageVersionsAt(String repoId, String domain, String repository, String packageName) {
        try {
            return pypiserver.listPackageVersions(repoId, domain, repository, null, packageName).stream()
                    .map(RepositorySidecarManager.PackageVersionTimestamp::version)
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not list {0}''s versions on upstream repository {1}''s pypiserver sidecar while "
                    + "resolving a same-domain upstream retention; treating it as having none.", packageName,
                    repository);
            return List.of();
        }
    }

    private List<String> assetNamesAt(String repoId, String domain, String repository, String packageName,
            String version) {
        try {
            return pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null, packageName, version);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not list {0} {1}''s assets on upstream repository {2}''s pypiserver sidecar while "
                    + "resolving a same-domain upstream retention; treating it as having none.", packageName,
                    version, repository);
            return List.of();
        }
    }
}
