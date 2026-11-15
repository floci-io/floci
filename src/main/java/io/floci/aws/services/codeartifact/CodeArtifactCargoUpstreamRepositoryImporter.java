package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code 17d-CARGO-UPSTREAM-REPO}: retains a Cargo crate version from another repository in the
 * same domain, named in {@code repository}'s own {@code upstreams} list (distinct from, and
 * mutually exclusive with, {@link CodeArtifactCargoExternalConnectionImporter}'s real public
 * registry). Reuses the exact claim/fence mechanism {@link
 * CodeArtifactService#claimRetainedVersionOrigin} already provides, mirroring {@link
 * CodeArtifactNuGetUpstreamRepositoryImporter}'s own shell for the single-asset-per-version walk
 * (transitive search in priority order via {@link CodeArtifactService#resolveUpstreamSearchOrder},
 * Archived/Disposed propagation via {@link
 * CodeArtifactService#requireContainerBackedVersionDownloadable}, falling through to {@link
 * UpstreamExternalConnectionFetch} for the last leg through an upstream's own external
 * connection), and reusing {@link CodeArtifactCargoExternalConnectionImporter}'s own now
 * package-visible index-line/wire-body helpers directly rather than duplicating the index-shape to
 * publish-shape mapping a second time. No {@code cksum} re-verification against the candidate's
 * own index line, the same reasoning {@link CodeArtifactPypiUpstreamRepositoryImporter}'s own
 * javadoc gives for skipping pypi's own PEP 691/sha256 re-check here: a same-domain upstream is
 * another repository this emulator itself already backs, with nothing untrusted or remote to
 * re-verify against, unlike the real public registry the external-connection importer fetches
 * from.
 *
 * <p><b>The same genuinely new piece {@link CodeArtifactCargoExternalConnectionImporter} needed,
 * needed again here</b>: a real {@code cargo} client always resolves the sparse index before a
 * download, and the index for one crate name is a single file covering every version, so this
 * importer also has {@link #mergeUpstreamRepositoryVersions}, a read-only listing merge over
 * {@code repository}'s own upstream chain, the same reason {@code mergeExternalVersions} exists.
 * Unlike {@link CodeArtifactPypiUpstreamRepositoryImporter#additionalInternalFilenames}'s own
 * per-request merge (which stops at the first candidate naming the one requested file, a single-
 * target resolution), this one is a real multi-version index merge, so it walks every reachable
 * candidate in search order rather than stopping at the first one with anything to offer, in two
 * complete passes rather than interleaved per candidate: every candidate's own real local lines
 * first (first-found-per-version wins, matching {@code mergeExternalVersions}'s own "local always
 * wins over upstream" compositional model extended across however many hops the chain has), then
 * every candidate's own external connection, if it has one, each layered in too by calling {@code
 * mergeExternalVersions} directly with that candidate as the repository argument, the same
 * chain-through {@code additionalInternalFilenames} already does for pypi's own sibling-files
 * case. The two full passes, not interleaved, matter: {@link #tryImport}'s own real resolution
 * order tries every candidate's own local copy, across the whole chain, before ever falling
 * through to any candidate's external connection, so a real download can retain a later
 * candidate's own local bytes for a version an earlier candidate's external connection also
 * offers with different bytes; advertising the earlier, external line here while a download would
 * retain the later, local one would hand a real {@code cargo} client a {@code cksum} that doesn't
 * match what it actually downloads. Each external-connection-contributed line also gets the same
 * two-sided Archived/Disposed check a locally-contributed line already does (checked only against
 * genuinely new versions that pass contributed, never against ones already known), since a real
 * download through a candidate's own external connection already refuses one Archived or Disposed
 * there.
 */
@ApplicationScoped
public class CodeArtifactCargoUpstreamRepositoryImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactCargoUpstreamRepositoryImporter.class);

    private final CodeArtifactService service;
    private final KellnrSidecarClient kellnr;
    private final CodeArtifactCargoExternalConnectionImporter externalConnectionImporter;

    @Inject
    public CodeArtifactCargoUpstreamRepositoryImporter(CodeArtifactService service, KellnrSidecarClient kellnr,
            CodeArtifactCargoExternalConnectionImporter externalConnectionImporter) {
        this.service = service;
        this.kellnr = kellnr;
        this.externalConnectionImporter = externalConnectionImporter;
    }

    /**
     * Attempts to retain {@code packageName}/{@code version}'s one real crate asset into {@code
     * repoId} from the first eligible repository in {@code repository}'s own upstream chain.
     * Returns {@code true} only when the crate is genuinely present under this claim by the time
     * this returns, the same contract {@link CodeArtifactCargoExternalConnectionImporter
     * #tryImport}'s own javadoc documents.
     */
    public boolean tryImport(String region, String domain, String owner, String repository, String repoId,
            String packageName, String version) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "cargo", null, packageName,
                "INTERNAL_UPSTREAM")) {
            return false;
        }
        // Snapshotted before the walk, not once a candidate is found, the same ordering
        // CodeArtifactCargoExternalConnectionImporter#tryImport relies on: the walk's last leg can
        // fetch through an upstream's own external connection, and a deletion here during that
        // fetch must still move the fence away from the snapshot.
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "cargo", null, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "cargo", null, packageName, version);

        String upstreamRepository = null;
        String upstreamRepoId = null;
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse("cargo", region, domain, owner, candidate,
                        kellnr);
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: treated exactly like "not
                // found" there, the same way an Archived/Disposed candidate below is.
                continue;
            }
            boolean eligible = false;
            try {
                if (!kellnr.packageVersionExists(candidateRepoId, domain, candidate, null, packageName, version)) {
                    continue;
                }
                try {
                    service.requireContainerBackedVersionDownloadable(owner, region, domain, candidate, "cargo",
                            null, packageName, version, candidateRepoId);
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
                    kellnr.endUse(candidateRepoId);
                }
            }
        }
        if (upstreamRepository == null) {
            Optional<UpstreamExternalConnectionFetch.Fetched> fetched = UpstreamExternalConnectionFetch.firstFetched(
                    service, kellnr, "cargo", region, domain, owner, repository, null, packageName,
                    candidate -> externalConnectionImporter.hasExternalConnection(region, domain, owner, candidate),
                    (candidate, candidateRepoId) -> externalConnectionImporter.tryImport(region, domain, owner,
                            candidate, candidateRepoId, packageName, version) ? Optional.of(version)
                            : Optional.empty());
            if (fetched.isPresent()) {
                upstreamRepository = fetched.get().repository();
                upstreamRepoId = fetched.get().repoId();
            }
        }
        if (upstreamRepository == null) {
            return false;
        }

        try {
            return tryImportFromClaimedCandidate(region, domain, owner, repository, repoId, packageName, version,
                    upstreamRepository, upstreamRepoId, packageFenceSnapshot, versionFenceSnapshot);
        } finally {
            kellnr.endUse(upstreamRepoId);
        }
    }

    private boolean tryImportFromClaimedCandidate(String region, String domain, String owner, String repository,
            String repoId, String packageName, String version, String upstreamRepository, String upstreamRepoId,
            long packageFenceSnapshot, long versionFenceSnapshot) {
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "cargo", null, packageName, version, upstreamRepository,
                RetentionOriginKind.UPSTREAM_REPOSITORY, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        Path crateFile = null;
        Path stagedWireBody = null;
        try {
            // Same race this closes for the external-connection importer: a concurrent attempt
            // under this exact claim episode, or a real client's own direct push, may have already
            // landed these bytes by the time this attempt gets here.
            if (kellnr.packageVersionExists(repoId, domain, repository, null, packageName, version)) {
                imported = service.retainedUpstreamRepositoryOrigin(region, domain, owner, repository, "cargo",
                        null, packageName, version).filter(upstreamRepository::equals).isPresent();
                return imported;
            }
            Optional<byte[]> upstreamIndex = kellnr.fetchSparseIndexRaw(upstreamRepoId, domain, upstreamRepository,
                    packageName);
            if (upstreamIndex.isEmpty()) {
                return false;
            }
            Optional<JsonNode> indexLine = externalConnectionImporter.findIndexLine(upstreamIndex.get(), version);
            if (indexLine.isEmpty()) {
                return false;
            }
            String assetName = packageName + "-" + version + ".crate";
            Optional<RepositorySidecarManager.StreamedAsset> streamed = kellnr.fetchPackageVersionAssetStreamed(
                    upstreamRepoId, domain, upstreamRepository, null, packageName, version, assetName);
            if (streamed.isEmpty()) {
                return false;
            }
            crateFile = service.stageStreamedAsset(streamed.get());
            // Not streamed.get().contentLength(): that's -1 whenever the upstream sidecar's own
            // response carried no Content-Length header, and forwarding -1 into byte accounting
            // would shrink DescribeDomain's assetSizeBytes instead of growing it. The staged
            // file's own real size is always correct.
            long contentLength = fileSizeOf(crateFile);
            byte[] metadata = externalConnectionImporter.metadataFromIndexLine(indexLine.get());
            stagedWireBody = CodeArtifactCargoExternalConnectionImporter.buildStagedWireBody(metadata, crateFile);
            long wireBodyLength = CodeArtifactCargoExternalConnectionImporter.fileSize(stagedWireBody);
            Path finalStagedWireBody = stagedWireBody;
            CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                    domain, owner, repository, "cargo", null, packageName, version, versionFenceSnapshot,
                    packageFenceSnapshot, claim.episodeId(), repoId, assetName, contentLength,
                    () -> {
                        KellnrSidecarClient.KellnrPublishResult publishResult = kellnr.publishRawWireBody(repoId,
                                finalStagedWireBody, wireBodyLength);
                        if (publishResult.statusCode() != 200) {
                            throw new IllegalStateException("Kellnr refused this retained crate: upstream "
                                    + "returned " + publishResult.statusCode());
                        }
                    });
            imported = result.fenceMatched() && result.wroteNewContent();
            return imported;
        } finally {
            if (crateFile != null) {
                service.deleteStagedAssetBody(crateFile);
            }
            if (stagedWireBody != null) {
                try {
                    Files.deleteIfExists(stagedWireBody);
                } catch (IOException e) {
                    LOG.warnv(e, "Could not delete a staged Cargo upstream-repository wire-body file at {0}",
                            stagedWireBody);
                }
            }
            if (imported) {
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "cargo", null, packageName, packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "cargo", null,
                    packageName, version, claim.episodeId(), imported);
        }
    }

    /**
     * {@code alreadyMergedBytes}'s own real version lines (whatever the local sparse index and
     * {@link CodeArtifactCargoExternalConnectionImporter#mergeExternalVersions} already
     * contributed), plus every real version line any repository in {@code repository}'s own
     * upstream chain carries that isn't already known, walked in {@link
     * CodeArtifactService#resolveUpstreamSearchOrder}'s own priority order (an earlier candidate's
     * line for a version always wins over a later one's line for that same version). A version
     * Archived or Disposed, at this repository or at the candidate offering it, is excluded from
     * the merge the same way {@link CodeArtifactPypiUpstreamRepositoryImporter
     * #additionalInternalFilenames} already excludes one. Each candidate's own external connection,
     * if it has one, is layered in too via a direct {@code mergeExternalVersions} call with that
     * candidate as the repository argument, the same chain-through pypi's own sibling method
     * already does. No upstreams configured, or the package {@code BLOCK}ed, returns {@code
     * alreadyMergedBytes} unchanged.
     */
    public Optional<byte[]> mergeUpstreamRepositoryVersions(String region, String domain, String owner,
            String repository, String repoId, String packageName, Optional<byte[]> alreadyMergedBytes) {
        if (service.describeRepository(region, domain, owner, repository).getUpstreams().isEmpty()) {
            return alreadyMergedBytes;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "cargo", null, packageName,
                "INTERNAL_UPSTREAM")) {
            return alreadyMergedBytes;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (String line : CodeArtifactCargoExternalConnectionImporter.splitLines(
                alreadyMergedBytes.orElse(new byte[0]))) {
            externalConnectionImporter.indexLineVersion(line).ifPresent(vers -> merged.put(vers, line));
        }
        List<String> candidates = service.resolveUpstreamSearchOrder(region, domain, owner, repository);
        Map<String, String> candidateRepoIds = new LinkedHashMap<>();
        for (String candidate : candidates) {
            try {
                candidateRepoIds.put(candidate, service.resolveContainerIdAndBeginUse("cargo", region, domain, owner,
                        candidate, kellnr));
            } catch (RuntimeException e) {
                // The candidate is being concurrently deleted/released: nothing listed there.
            }
        }
        try {
            // Pass 1: every candidate's own real local lines, in priority order, before any
            // external connection is considered at all. This has to be its own complete pass, not
            // interleaved per candidate the way an earlier version of this method did: tryImport's
            // own real resolution order tries every candidate's own local copy, across the whole
            // chain, before ever falling through to any candidate's external connection, so a
            // download picks a later candidate's own local bytes over an earlier candidate's
            // external-connection bytes for the same version. Advertising the earlier, external
            // cksum here while a download would retain the later, local bytes would hand a real
            // cargo client a checksum that doesn't match what it actually downloads.
            for (String candidate : candidates) {
                String candidateRepoId = candidateRepoIds.get(candidate);
                if (candidateRepoId != null) {
                    mergeOneCandidate(region, domain, owner, repository, repoId, candidate, candidateRepoId,
                            packageName, merged);
                }
            }
            // Pass 2: each candidate's own external connection, same priority order, only for a
            // version no candidate's own local copy already contributed in pass 1 (mirroring
            // tryImport's own fallback-only-after-every-local-candidate-misses order), and only
            // once it passes the same two-sided Archived/Disposed check pass 1's own local lines
            // already go through: a real download through a candidate's own external connection
            // (UpstreamExternalConnectionFetch) already refuses an Archived/Disposed version at
            // the candidate, and a version disposed here, at the requesting repository, must stay
            // invisible the same way a locally-contributed line already does.
            for (String candidate : candidates) {
                String candidateRepoId = candidateRepoIds.get(candidate);
                if (candidateRepoId == null
                        || !externalConnectionImporter.hasExternalConnection(region, domain, owner, candidate)) {
                    continue;
                }
                Set<String> beforeThisConnection = new HashSet<>(merged.keySet());
                Optional<byte[]> withConnection = externalConnectionImporter.mergeExternalVersions(region, domain,
                        owner, candidate, packageName, Optional.of(serializeMerged(merged)));
                for (String line : CodeArtifactCargoExternalConnectionImporter.splitLines(
                        withConnection.orElse(new byte[0]))) {
                    Optional<String> version = externalConnectionImporter.indexLineVersion(line);
                    if (version.isEmpty() || beforeThisConnection.contains(version.get())) {
                        continue;
                    }
                    if (!downloadable(owner, region, domain, repository, packageName, version.get(), repoId)
                            || !downloadable(owner, region, domain, candidate, packageName, version.get(),
                                    candidateRepoId)) {
                        continue;
                    }
                    merged.put(version.get(), line);
                }
            }
        } finally {
            candidateRepoIds.values().forEach(kellnr::endUse);
        }
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(serializeMerged(merged));
    }

    private void mergeOneCandidate(String region, String domain, String owner, String repository, String repoId,
            String candidate, String candidateRepoId, String packageName, Map<String, String> merged) {
        Optional<byte[]> candidateIndex = kellnr.fetchSparseIndexRaw(candidateRepoId, domain, candidate,
                packageName);
        if (candidateIndex.isEmpty()) {
            return;
        }
        for (String line : CodeArtifactCargoExternalConnectionImporter.splitLines(candidateIndex.get())) {
            Optional<String> version = externalConnectionImporter.indexLineVersion(line);
            if (version.isEmpty() || merged.containsKey(version.get())) {
                continue;
            }
            if (!downloadable(owner, region, domain, repository, packageName, version.get(), repoId)
                    || !downloadable(owner, region, domain, candidate, packageName, version.get(), candidateRepoId)) {
                continue;
            }
            merged.put(version.get(), line);
        }
    }

    private boolean downloadable(String owner, String region, String domain, String repository, String packageName,
            String version, String repoId) {
        try {
            service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "cargo", null,
                    packageName, version, repoId);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private static byte[] serializeMerged(Map<String, String> merged) {
        return String.join("\n", merged.values()).getBytes(StandardCharsets.UTF_8);
    }

    private static long fileSizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
