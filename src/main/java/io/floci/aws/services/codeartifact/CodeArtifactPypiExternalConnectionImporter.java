package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Inject
    public CodeArtifactPypiExternalConnectionImporter(CodeArtifactService service, PypiserverSidecarClient pypiserver,
            PypiExternalConnectionClient externalConnectionClient, ObjectMapper mapper) {
        this.service = service;
        this.pypiserver = pypiserver;
        this.externalConnectionClient = externalConnectionClient;
        this.mapper = mapper;
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
    public List<String> additionalExternalFilenames(String region, String domain, String owner, String repository,
            String repoId, String packageName, Set<String> alreadyKnownFilenames) {
        String externalConnectionName = pypiExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return List.of();
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName)) {
            return List.of();
        }
        JsonNode index = fetchSimpleIndexJson(externalConnectionName, packageName).orElse(null);
        if (index == null) {
            return List.of();
        }
        List<String> versions = versionsOf(index);
        List<String> additional = new ArrayList<>();
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
            additional.add(filename);
        }
        return additional;
    }

    /**
     * Attempts to retain every real file of the version {@code filename} itself belongs to, into
     * {@code repoId}, from {@code repository}'s own pypi external connection. Returns {@code
     * true} only when {@code filename} itself is genuinely present under this connection's claim
     * by the time this returns: a caller that gets {@code false} back should fall through to its
     * own existing not-found response exactly as if this method had never been called.
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
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "pypi", null, packageName.get())) {
            return false;
        }
        JsonNode index = fetchSimpleIndexJson(externalConnectionName, packageName.get()).orElse(null);
        if (index == null) {
            return false;
        }
        List<String> versions = versionsOf(index);
        Optional<String> version = resolveVersion(versions, packageName.get(), filename);
        if (version.isEmpty()) {
            return false;
        }

        // Snapshotted before the claim and the real writes below, not after, the same ordering
        // every other external-connection importer in this package already relies on: a
        // DeletePackage landing mid-import must not have its removal silently undone by the
        // claim, the byte accounting, or the default-config bookkeeping this attempt is about to
        // record.
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "pypi", null, packageName.get());
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "pypi", null, packageName.get(), version.get());
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "pypi", null, packageName.get(), version.get(), externalConnectionName,
                packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        // What this version already has locally, so a file already retained (by an earlier
        // attempt, or a concurrent one under this exact same claim episode) is never re-fetched
        // from the real registry just to write back bytes already there.
        Set<String> alreadyPresent = Set.copyOf(pypiserver.listPackageVersionAssetNames(repoId, domain, repository,
                null, packageName.get(), version.get()));

        boolean importedRequested = false;
        boolean importedAny = false;
        try {
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
                Optional<PypiExternalConnectionClient.FetchedAsset> fetched;
                try {
                    fetched = externalConnectionClient.fetchFile(externalConnectionName, url);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not fetch {0} from {1} while retaining a pypi package through an "
                            + "external connection; skipping this file.", url, externalConnectionName);
                    continue;
                }
                if (fetched.isEmpty()) {
                    continue;
                }
                // Re-checked right here, immediately before the write, not just against
                // alreadyPresent's own snapshot taken before this whole loop started: unlike
                // npm's/NuGet's own single-asset attempt, where that snapshot's own age is just
                // the one fetch above, this loop can run for as long as the real registry lists
                // siblings (AWS: "some contain over 100 assets"), long enough for a concurrent
                // sibling attempt under this exact same claim episode to have already written
                // candidateFilename by the time this one gets here. retainAssetUnderVersionFence's
                // own write has no way to tell a fresh write from a re-write of identical content
                // (see AssetRetentionAttemptResult's own javadoc), so skipping here, not there, is
                // what actually keeps DescribeDomain's assetSizeBytes from double-counting it.
                if (pypiserver.listPackageVersionAssetNames(repoId, domain, repository, null, packageName.get(),
                        version.get()).contains(candidateFilename)) {
                    if (candidateFilename.equals(filename)) {
                        // Mirrors the attempt-start check above: existence alone does not prove
                        // this connection wrote it.
                        boolean confirmed = service.retainedVersionOriginConnection(region, domain, owner,
                                repository, "pypi", null, packageName.get(), version.get())
                                .filter(externalConnectionName::equals).isPresent();
                        importedAny = importedAny || confirmed;
                        importedRequested = confirmed;
                    }
                    continue;
                }
                byte[] content = fetched.get().content();
                // The fence check and the real write happen inside the very same synchronized
                // call DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions
                // already hold across their own entire operation, so a write here and one of
                // those four operations can never interleave.
                CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                        domain, owner, repository, "pypi", null, packageName.get(), version.get(),
                        versionFenceSnapshot, packageFenceSnapshot, claim.episodeId(), repoId, candidateFilename,
                        content,
                        () -> pypiserver.storePackageVersionAsset(repoId, domain, repository, null,
                                packageName.get(), version.get(), candidateFilename, content));
                if (!result.fenceMatched()) {
                    // The version itself is gone: every later candidate in this same attempt
                    // would fail the exact same way, so none of them are even attempted.
                    break;
                }
                if (!result.wroteNewContent()) {
                    continue;
                }
                importedAny = true;
                if (candidateFilename.equals(filename)) {
                    importedRequested = true;
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
