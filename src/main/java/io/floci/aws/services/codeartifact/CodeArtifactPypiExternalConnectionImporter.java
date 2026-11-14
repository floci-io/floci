package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Retains pypi package versions from the real public repository a repository's {@code
 * public:pypi} external connection names, the way real CodeArtifact does when a {@code pip}/
 * {@code twine} client requests a package version that is not present locally yet. Confirmed
 * against the User Guide's own "Requesting Python packages from upstreams and external
 * connections" section, not inferred:
 *
 * <ul>
 *   <li><b>Every asset of a version, imported together.</b> "When importing a Python package
 *   version from pypi.org, CodeArtifact will import all the assets in that package version,"
 *   explicitly unlike npm/NuGet's "only have one asset per package version": a popular package's
 *   version can have "over 100 assets," so {@link #tryImportAsset} always imports every real file
 *   the public registry lists for the resolved version, the same best-effort, all-reachable-
 *   siblings shape {@code CodeArtifactMavenExternalConnectionImporter} already uses for Maven's
 *   own (much smaller, fixed) 4-name standard bundle.</li>
 *   <li><b>No release-only restriction.</b> Unlike Maven's own external connections, AWS's docs
 *   name no equivalent carve-out for pypi prereleases, and no exclusion for a <i>yanked</i>
 *   version either (yanked only changes what a real {@code pip} client itself does with a
 *   version once it is fetched, never whether CodeArtifact imports it): every version and file
 *   this emulator's own real public repository lists is eligible.</li>
 *   <li><b>Checking asset origins.</b> A package version's retention origin, once confirmed,
 *   never changes (see {@link CodeArtifactService#claimRetainedVersionOrigin}'s own javadoc,
 *   generalized from Maven's own first use of this mechanism); an import attempt under a
 *   different connection than what is already claimed for that exact version is refused
 *   outright.</li>
 *   <li><b>Coordinated with this emulator's own deletion and disposal.</b> The real write, via
 *   {@link CodeArtifactService#retainAssetUnderVersionFence}, happens inside the exact same lock
 *   {@code DeletePackageVersions}/{@code DeletePackage}/{@code DeleteRepository}/{@code
 *   DisposePackageVersions} already hold for their entire duration, so the two can never
 *   interleave.</li>
 * </ul>
 *
 * <p>Deliberately not modeled here: refreshing a retained version's <i>yanked</i> metadata on
 * every later install, which the same User Guide section also documents. {@code pypiserver} has
 * no concept of a yanked flag anywhere in its own wire protocol, so surfacing it to a real
 * {@code pip} client would need a new field on this emulator's own package-version model with no
 * connection to the actual asset-retention gap this class closes; tracked separately as a
 * follow-up rather than widening this scope. For the same reason, this never re-fetches every
 * byte of an already-fully-retained version on a later {@code pip install}: {@link
 * CodeArtifactPypiController#packageIndex}'s own merge (see {@link
 * #additionalExternalFilenames}) re-lists whichever new files the real registry has added since,
 * cheaply, on every request, and {@link #tryImportAsset} only ever fetches real bytes for a file
 * that is not already present locally, the same lazy-write/eager-list split every other format's
 * own external-connection importer in this package already uses.
 */
@ApplicationScoped
public class CodeArtifactPypiExternalConnectionImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactPypiExternalConnectionImporter.class);

    private final CodeArtifactService service;
    private final PypiserverSidecarClient pypiserver;
    private final PypiExternalConnectionClient externalConnectionClient;
    private final ObjectMapper mapper;

    // Keyed by repoId+packageName+version: serializes the "is this sibling already written"
    // recheck together with the write that follows it, closing the gap a bare in-memory snapshot
    // taken once before the whole sibling loop cannot (see tryImportAsset's own loop for the race
    // this closes). In-process only, like every other sidecar bookkeeping map in this package: it
    // only needs to order concurrent attempts within this one Floci instance, the same scope
    // SidecarConcurrency's own per-container locks already have.
    private final ConcurrentHashMap<String, Object> siblingWriteLocks = new ConcurrentHashMap<>();

    // Fire-and-forget home for the siblings tryImportAsset itself does not wait on (see that
    // method's own javadoc). Matches CodePipelineService's own instance-field executor, the only
    // precedent anywhere in this codebase for this shape of background work; no CDI
    // ManagedExecutor/@Asynchronous/Mutiny usage exists here for it.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public CodeArtifactPypiExternalConnectionImporter(CodeArtifactService service, PypiserverSidecarClient pypiserver,
            PypiExternalConnectionClient externalConnectionClient, ObjectMapper mapper) {
        this.service = service;
        this.pypiserver = pypiserver;
        this.externalConnectionClient = externalConnectionClient;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("Pypi sibling import workers did not stop within the shutdown timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while stopping pypi sibling import workers", e);
        }
    }

    /**
     * One real file the external connection's own Simple API names, not yet known locally:
     * {@code yanked} and {@code requiresPythonOrNull} are read straight off that same response
     * (PEP 592/PEP 700's {@code yanked} and {@code requires-python} fields) so {@link
     * CodeArtifactPypiController#packageIndex} can carry them into the merged index as the real
     * {@code data-yanked}/{@code data-requires-python} attributes a real {@code pip} client
     * actually looks for when choosing a candidate (raised in review: leaving them off would let
     * pip pick a yanked release, or attempt to build an sdist its own interpreter cannot satisfy,
     * for a file this emulator has not retained yet, a gap on the very first install, separate
     * from this class's own already-documented choice not to refresh retained metadata later).
     */
    public record ExternalIndexFile(String filename, boolean yanked, String requiresPythonOrNull) {
    }

    /**
     * The real public repository's own files for {@code packageName} that are not already in
     * {@code alreadyKnownFilenames}, for {@link CodeArtifactPypiController#packageIndex} to
     * advertise alongside {@code pypiserver}'s own local listing (rewritten to this repository's
     * own download route, the same as every locally-known file already is). Advertising a file
     * here never fetches or stores its real bytes: that only happens once a real client actually
     * requests it, through {@link #tryImportAsset}, the same promise-now/fetch-later split {@code
     * CodeArtifactNpmExternalConnectionImporter#mergedPackument} already documents for npm.
     *
     * <p>A file whose own version resolves to one this repository already marked {@code
     * Archived}/{@code Disposed} is left out, the same existence check a per-version download
     * itself already enforces through {@link
     * CodeArtifactService#requireContainerBackedVersionDownloadable}: the real registry's own
     * listing has no idea this repository ever marked it that way, so without this, a disposed
     * version could be advertised right back, only to have {@link #tryImportAsset} correctly, but
     * confusingly, refuse to write it once actually requested.
     */
    public List<ExternalIndexFile> additionalExternalFilenames(String region, String domain, String owner,
            String repository, String repoId, String packageName, Set<String> alreadyKnownFilenames) {
        String externalConnectionName = pypiExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return List.of();
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName,
                "EXTERNAL_UPSTREAM")) {
            return List.of();
        }
        JsonNode index = fetchSimpleIndexJson(externalConnectionName, packageName).orElse(null);
        if (index == null) {
            return List.of();
        }
        List<String> versions = versionsOf(index);
        List<ExternalIndexFile> additional = new ArrayList<>();
        for (JsonNode file : index.path("files")) {
            String filename = file.path("filename").asText(null);
            if (filename == null || alreadyKnownFilenames.contains(filename)) {
                continue;
            }
            Optional<String> version = resolveVersion(versions, packageName, filename);
            if (version.isEmpty()) {
                continue;
            }
            try {
                service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "pypi", null,
                        packageName, version.get(), repoId);
            } catch (AwsException e) {
                continue;
            }
            additional.add(new ExternalIndexFile(filename, file.path("yanked").asBoolean(false),
                    file.path("requires-python").asText(null)));
        }
        return additional;
    }

    /**
     * Attempts to retain {@code filename} itself, into {@code repoId}, from {@code repository}'s
     * own pypi external connection. Returns {@code true} only when {@code filename} itself is
     * genuinely present under this connection's claim by the time this returns: a caller that
     * gets {@code false} back should fall through to its own existing not-found response exactly
     * as if this method had never been called.
     *
     * <p>Every other real file of the same resolved version is still retained, matching AWS's own
     * documented "CodeArtifact will import all the assets in that package version" behavior, but
     * never inline here: this fetches and writes {@code filename} itself synchronously, then hands
     * the remaining siblings to a background task under this same claim episode (a second {@link
     * CodeArtifactService#claimRetainedVersionOrigin} call, bumping {@code inFlightAttempts} the
     * exact way a second concurrent caller already would) and returns as soon as {@code filename}
     * is settled. A version can have "over 100 assets," so fetching every one of them inline, in
     * order, before answering the one file {@link CodeArtifactPypiController#downloadFile} is
     * actually waiting on, could easily outlast a real {@code pip} client's own default 15s read
     * timeout on the very first install of a popular package (raised in review: hectorvent, #7741).
     *
     * <p>{@code filename} is the only identity {@link
     * CodeArtifactPypiController#downloadFile} has to work with (pypi's real download route, like
     * this emulator's own, names no package or version anywhere in the URL): the package name is
     * recovered from it via {@link PypiserverSidecarClient#guessPackageName}, the same filename
     * parser the publish-side {@code publish:BLOCK} check already relies on for an identical
     * reason, and the version is then recovered by checking {@code filename} against every
     * version the real registry's own response names ({@link #resolveVersion}), not guessed
     * independently: a version string can appear anywhere inside a real pypi filename (build
     * tags, local-version segments), so only checking it against the registry's own actual
     * version list, the same way {@link PypiserverSidecarClient#filenameEmbedsVersion} already
     * verifies a <em>claimed</em> version against a filename, avoids mismatching whichever version
     * happens to parse out syntactically.
     */
    public boolean tryImportAsset(String region, String domain, String owner, String repository, String repoId,
            String filename) {
        String externalConnectionName = pypiExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        Optional<String> packageName = PypiserverSidecarClient.guessPackageName(filename);
        if (packageName.isEmpty()) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName.get(),
                "EXTERNAL_UPSTREAM")) {
            return false;
        }

        // Snapshotted here, before fetchSimpleIndexJson below issues its own real outbound
        // fetch, not after it returns: claimRetainedVersionOrigin's own javadoc documents this
        // package-fence snapshot as taken "atomically and before any real network fetch" for
        // every format that calls it. Capturing it only after that fetch, as a version's filename
        // resolution once did here, would let a DeletePackage racing the fetch go completely
        // undetected: the snapshot would already read the post-deletion fence as its own
        // baseline, so the claim below would see no change at all and happily reclaim (and
        // re-populate) a package this repository just deleted. The real registry's own response
        // time is exactly the slow part this snapshot needs to precede (raised in review).
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "pypi", null, packageName.get());

        JsonNode index = fetchSimpleIndexJson(externalConnectionName, packageName.get()).orElse(null);
        if (index == null) {
            return false;
        }
        List<String> versions = versionsOf(index);
        Optional<String> version = resolveVersion(versions, packageName.get(), filename);
        if (version.isEmpty()) {
            return false;
        }

        // The version itself can only be resolved from the real registry's own response above
        // (pypi's download route, unlike NuGet's, names no version anywhere in the URL), so
        // unlike packageFenceSnapshot this cannot be captured any earlier than right here; nothing
        // but fast, in-memory work runs between this and the claim immediately below.
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "pypi", null, packageName.get(), version.get());
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "pypi", null, packageName.get(), version.get(), externalConnectionName,
                RetentionOriginKind.EXTERNAL_CONNECTION, packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean importedRequested = false;
        boolean importedAny = false;
        try {
            // What this version already has locally, so a file already retained (by an earlier
            // attempt, or a concurrent one under this exact same claim episode) is never
            // re-fetched from the real registry just to write back bytes already there. Computed
            // inside this try, not before it as a bare statement once did here: a transient
            // sidecar failure from this call must still reach the finally below so the claim this
            // attempt just took is never left stranded (raised in review) -- a stuck claim would
            // make every later attempt for this exact version see it as still externally
            // originated even though nothing was ever actually retained.
            Set<String> alreadyPresent;
            try {
                alreadyPresent = Set.copyOf(pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null,
                        packageName.get(), version.get()));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not list {0} {1}''s existing assets on the pypiserver sidecar while attempting "
                        + "a pypi retention through an external connection; treating it as not retained.",
                        packageName.get(), version.get());
                return false;
            }
            if (alreadyPresent.contains(filename)) {
                // Raised the same way npm's own tryImportAsset already handles it: a concurrent
                // attempt under this exact same claim episode (see claimRetainedVersionOrigin's
                // own javadoc on inFlightAttempts) may already have written this exact file by
                // the time this attempt gets here. Only confirming it under this connection, not
                // existence alone, tells that apart from some other origin having it.
                importedAny = service.retainedVersionOriginConnection(region, domain, owner, repository, "pypi",
                        null, packageName.get(), version.get()).filter(externalConnectionName::equals).isPresent();
                importedRequested = importedAny;
                return importedRequested;
            }
            String importLockKey = repoId + "|" + PypiserverSidecarClient.normalizePackageName(packageName.get())
                    + "|" + version.get();

            // Splits the version's own files into the one filename actually requested (imported
            // synchronously below, so downloadFile's own proxy retry has real bytes to serve the
            // moment this call returns) and every other sibling, handed off to importRemainingSiblingsInBackground
            // rather than fetched inline here (see this method's own javadoc).
            JsonNode requestedCandidate = null;
            List<JsonNode> remainingSiblings = new ArrayList<>();
            for (JsonNode file : index.path("files")) {
                String candidateFilename = file.path("filename").asText(null);
                String url = file.path("url").asText(null);
                if (candidateFilename == null || url == null || alreadyPresent.contains(candidateFilename)) {
                    continue;
                }
                if (resolveVersion(versions, packageName.get(), candidateFilename)
                        .filter(version.get()::equals).isEmpty()) {
                    continue;
                }
                if (candidateFilename.equals(filename)) {
                    requestedCandidate = file;
                } else {
                    remainingSiblings.add(file);
                }
            }

            boolean fenceStillMatched = true;
            if (requestedCandidate != null) {
                FileImportStatus status = importOneCandidateFile(region, domain, owner, repository, repoId,
                        packageName.get(), version.get(), externalConnectionName, versionFenceSnapshot,
                        packageFenceSnapshot, claim.episodeId(), importLockKey, requestedCandidate, filename);
                switch (status) {
                    case WROTE -> {
                        importedAny = true;
                        importedRequested = true;
                    }
                    case ALREADY_PRESENT -> {
                        // Mirrors the attempt-start check above: existence alone does not prove
                        // this connection wrote it. A concurrent attempt under this exact same
                        // episode may have written it between the outer alreadyPresent snapshot
                        // and this lock.
                        importedRequested = service.retainedVersionOriginConnection(region, domain, owner,
                                repository, "pypi", null, packageName.get(), version.get())
                                .filter(externalConnectionName::equals).isPresent();
                        importedAny = importedAny || importedRequested;
                    }
                    case FENCE_GONE -> fenceStillMatched = false;
                    case SKIPPED -> {
                        // Fetch/size/hash failure: filename genuinely is not present, importedRequested
                        // stays false.
                    }
                }
            }

            if (fenceStillMatched && !remainingSiblings.isEmpty()) {
                CodeArtifactService.RetainedVersionOriginClaimOutcome backgroundClaim = service
                        .claimRetainedVersionOrigin(region, domain, owner, repository, "pypi", null,
                                packageName.get(), version.get(), externalConnectionName,
                                RetentionOriginKind.EXTERNAL_CONNECTION, packageFenceSnapshot);
                if (backgroundClaim.claimed()) {
                    List<JsonNode> siblingsToImport = remainingSiblings;
                    String resolvedPackageName = packageName.get();
                    String resolvedVersion = version.get();
                    executor.submit(() -> importRemainingSiblingsInBackground(region, domain, owner, repository,
                            repoId, resolvedPackageName, resolvedVersion, externalConnectionName,
                            versionFenceSnapshot, packageFenceSnapshot, backgroundClaim.episodeId(), importLockKey,
                            siblingsToImport));
                }
            }
            return importedRequested;
        } finally {
            if (importedAny) {
                // Checks the repository's own current sidecar container id, not just a package-
                // level deletion fence, matching CodeArtifactNuGetExternalConnectionImporter#
                // tryImport rather than npm's/Maven's own plain call: an existence check alone
                // cannot tell apart a DeleteRepository followed by a same-name recreate from the
                // original repository this attempt's own repoId still names (see
                // ensureRetainedPackageOriginDefaultIfCurrentRepository's own javadoc).
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "pypi", null, packageName.get(), packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "pypi", null,
                    packageName.get(), version.get(), claim.episodeId(), importedAny);
        }
    }

    /**
     * Finishes importing {@code siblings} on a background thread, under {@code episodeId} (its
     * own claim, taken separately from {@link #tryImportAsset}'s own attempt so each has its own
     * {@code inFlightAttempts} slot per {@link CodeArtifactService#claimRetainedVersionOrigin}'s
     * own javadoc) -- the part of {@link #tryImportAsset}'s own work the caller never waits on.
     * Any fetch/size/hash failure for one sibling only skips that sibling; a version-gone fence
     * mismatch stops the whole remaining batch, the same as {@link #tryImportAsset}'s own
     * now-synchronous single-file path does.
     */
    private void importRemainingSiblingsInBackground(String region, String domain, String owner, String repository,
            String repoId, String packageName, String version, String externalConnectionName,
            long versionFenceSnapshot, long packageFenceSnapshot, long episodeId, String importLockKey,
            List<JsonNode> siblings) {
        boolean importedAny = false;
        try {
            for (JsonNode file : siblings) {
                String candidateFilename = file.path("filename").asText(null);
                FileImportStatus status = importOneCandidateFile(region, domain, owner, repository, repoId,
                        packageName, version, externalConnectionName, versionFenceSnapshot, packageFenceSnapshot,
                        episodeId, importLockKey, file, candidateFilename);
                if (status == FileImportStatus.FENCE_GONE) {
                    break;
                }
                if (status == FileImportStatus.WROTE) {
                    importedAny = true;
                }
            }
        } finally {
            if (importedAny) {
                service.ensureRetainedPackageOriginDefaultIfCurrentRepository(region, domain, owner, repository,
                        "pypi", null, packageName, packageFenceSnapshot, repoId);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "pypi", null, packageName,
                    version, episodeId, importedAny);
        }
    }

    private enum FileImportStatus {
        WROTE, ALREADY_PRESENT, SKIPPED, FENCE_GONE
    }

    /**
     * Fetches, size/hash-verifies, and writes one real file the registry's own index names, under
     * the lock/recheck/write sequence that closes the double-count race two concurrent attempts
     * (the synchronous requested-file path and a background sibling batch, or two separate {@code
     * pip} requests) could otherwise hit on the exact same {@code candidateFilename} (raised in
     * review: a prior fix here narrowed this window but did not close it).
     */
    private FileImportStatus importOneCandidateFile(String region, String domain, String owner, String repository,
            String repoId, String packageName, String version, String externalConnectionName,
            long versionFenceSnapshot, long packageFenceSnapshot, long episodeId, String importLockKey,
            JsonNode file, String candidateFilename) {
        String url = file.path("url").asText(null);
        Path staged;
        try {
            staged = Files.createTempFile("codeartifact-pypi-import-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a staging file for a pypi external-connection "
                    + "import", e);
        }
        try {
            Optional<PypiExternalConnectionClient.FetchedFile> fetched;
            try {
                fetched = externalConnectionClient.fetchFile(externalConnectionName, url, staged);
            } catch (AssetDigestingInputStream.AssetTooLargeException e) {
                // The same 5 GB AWS quota CodeArtifactPypiController#upload already enforces on a
                // direct publish, now checked by fetchFile(Path) itself during the download
                // (via AssetDigestingInputStream.copyCapped) rather than in a second pass over
                // the staged file afterward: a file this large is rejected before it is ever
                // fully pulled across the network and written to local disk.
                LOG.warnv("Skipping {0}: the real registry''s own file is larger than this emulator''s "
                        + "{1}-byte asset quota.", candidateFilename, CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
                return FileImportStatus.SKIPPED;
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not fetch {0} from {1} while retaining a pypi package through an "
                        + "external connection; skipping this file.", url, externalConnectionName);
                return FileImportStatus.SKIPPED;
            }
            if (fetched.isEmpty()) {
                return FileImportStatus.SKIPPED;
            }
            long contentLength;
            String actualSha256;
            // A second streaming pass over the now-staged, already-size-checked file, purely to
            // compute the PEP 691/700 sha256 check below: fetchFile(Path) only reports the real
            // byte count back, not its own digests, so this reuses AssetDigestingInputStream for
            // that hash alone. The cap passed here can never actually trip (the file is already
            // guaranteed no larger than it by the pass above); it is just the same bounded
            // reader, not a second enforcement point.
            try (InputStream in = Files.newInputStream(staged)) {
                AssetDigestingInputStream digesting = new AssetDigestingInputStream(in,
                        CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
                digesting.transferTo(OutputStream.nullOutputStream());
                contentLength = digesting.size();
                actualSha256 = digesting.hashesHex().get("SHA-256");
            } catch (IOException e) {
                LOG.warnv(e, "Could not read the staged copy of {0} while retaining a pypi package through an "
                        + "external connection; skipping this file.", candidateFilename);
                return FileImportStatus.SKIPPED;
            }
            // PEP 691/700's own hashes.sha256, verified against the real fetched bytes before they
            // are ever written: fetchFile already restricts which host a file's bytes can come
            // from, but a host passing that check is not the same as the bytes genuinely matching
            // what the trusted index response itself said this exact file is (a truncated
            // transfer, or a compromised/misbehaving file host, would otherwise be retained and
            // served as if it were correct). Verified only when the index actually names a hash
            // (PEP 691 makes the field optional, though pypi.org's own real responses always
            // include it): its absence is not itself proof of tampering, so this does not refuse a
            // file over a missing hash alone, only a mismatched one.
            String expectedSha256 = file.path("hashes").path("sha256").asText(null);
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
                LOG.warnv("Skipping {0}: its real bytes do not match the sha256 the registry''s own index "
                        + "names for it.", candidateFilename);
                return FileImportStatus.SKIPPED;
            }
            // The lock below, the recheck inside it, and the write together close a gap the bare
            // in-memory alreadyPresent snapshot taken once before this whole import started cannot
            // on its own: a second call into this exact version -- a different pip request racing
            // this one, or the background sibling batch racing the synchronous requested-file path
            // -- could reach the very same candidate. retainAssetUnderVersionFence's own write has
            // no way to tell a fresh write from a re-write of identical content (see
            // AssetRetentionAttemptResult's own javadoc), so without serializing the recheck and
            // the write together on a lock keyed to this exact version, two such calls could both
            // see candidateFilename missing and both write it, double-counting its bytes in
            // DescribeDomain's assetSizeBytes.
            synchronized (siblingWriteLocks.computeIfAbsent(importLockKey, key -> new Object())) {
                if (pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null, packageName, version)
                        .contains(candidateFilename)) {
                    return FileImportStatus.ALREADY_PRESENT;
                }
                // The fence check and the real write happen inside the very same synchronized call
                // DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions
                // already hold across their own entire operation, so a write here and one of those
                // four operations can never interleave.
                CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                        domain, owner, repository, "pypi", null, packageName, version, versionFenceSnapshot,
                        packageFenceSnapshot, episodeId, repoId, candidateFilename, contentLength,
                        () -> pypiserver.storePackageVersionAsset(repoId, domain, repository, null, packageName,
                                version, candidateFilename, staged, contentLength));
                if (!result.fenceMatched()) {
                    // The version itself is gone: every later candidate in this same attempt would
                    // fail the exact same way.
                    return FileImportStatus.FENCE_GONE;
                }
                return result.wroteNewContent() ? FileImportStatus.WROTE : FileImportStatus.SKIPPED;
            }
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException e) {
                LOG.warnv(e, "Could not delete a staged pypi external-connection import file at {0}", staged);
            }
        }
    }

    private Optional<JsonNode> fetchSimpleIndexJson(String externalConnectionName, String packageName) {
        Optional<PypiExternalConnectionClient.FetchedAsset> fetched;
        try {
            fetched = externalConnectionClient.fetchSimpleIndex(externalConnectionName, packageName);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not fetch {0}''s simple index from {1} while resolving its versions through an "
                    + "external connection; leaving it unresolved.", packageName, externalConnectionName);
            return Optional.empty();
        }
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readTree(fetched.get().content()));
        } catch (IOException e) {
            LOG.warnv(e, "Could not parse {0}''s simple index fetched from {1}; leaving it unresolved.", packageName,
                    externalConnectionName);
            return Optional.empty();
        }
    }

    private static List<String> versionsOf(JsonNode index) {
        List<String> versions = new ArrayList<>();
        for (JsonNode version : index.path("versions")) {
            String text = version.asText(null);
            if (text != null) {
                versions.add(text);
            }
        }
        return versions;
    }

    /**
     * Which of {@code candidateVersions} {@code filename} actually belongs to, checked with
     * {@link PypiserverSidecarClient#filenameEmbedsVersion} rather than parsed independently from
     * the filename alone: a version string can appear more than once inside a real filename (a
     * local-version segment, a build tag), so only testing it against the registry's own real,
     * already-known version list tells a genuine match from a coincidental one.
     */
    private static Optional<String> resolveVersion(List<String> candidateVersions, String packageName,
            String filename) {
        return candidateVersions.stream()
                .filter(version -> PypiserverSidecarClient.filenameEmbedsVersion(filename, packageName, version))
                .findFirst();
    }

    private String pypiExternalConnectionName(String region, String domain, String owner, String repository) {
        return service.describeRepository(region, domain, owner, repository).getExternalConnections().stream()
                .filter(ec -> "pypi".equals(ec.getPackageFormat()))
                .map(ExternalConnection::getExternalConnectionName)
                .findFirst()
                .orElse(null);
    }
}
