package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Retains a Maven package version from the real public repository a repository's {@code
 * public:maven-*} external connection names, the way real CodeArtifact does when a {@code mvn}/
 * Gradle client requests a GAV that is not present locally yet. Confirmed against the User
 * Guide's own "Connect a CodeArtifact repository to a public repository" and "Requesting Maven
 * packages from upstreams and external connections" sections, not inferred:
 *
 * <ul>
 *   <li><b>Release versions only.</b> "AWS CodeArtifact only supports importing Maven release
 *   versions" through an external connection; a {@code -SNAPSHOT} version is never attempted.</li>
 *   <li><b>Standard assets, imported together.</b> When the requested filename matches one of the
 *   4 names CodeArtifact's own docs enumerate ({@code <artifactId>-<version>.pom/.jar/
 *   -javadoc.jar/-sources.jar}), every reachable one of the 4 is imported in the same attempt,
 *   best-effort (a missing javadoc/sources jar is normal, not a failure).</li>
 *   <li><b>Non-standard assets, imported on demand only.</b> A filename that does not match the
 *   standard shape (the docs' own {@code aapt2-7.3.1-8691043-windows.jar} example) is checked and
 *   imported individually, exactly as requested, never guessing at siblings this emulator has no
 *   way to list from the real public repository.</li>
 *   <li><b>Checking asset origins.</b> A package version's retention origin, once confirmed, never
 *   changes (see {@link CodeArtifactService#claimMavenRetainedVersionOrigin}'s own javadoc); an
 *   import attempt under a different connection than what is already claimed for that exact
 *   version is refused outright, "prevent[ing] creating a 'mixed' package version where
 *   different assets originate from different public repositories", refused before any asset is
 *   ever fetched or written, and the claim stays reserved for as long as any concurrent attempt
 *   under the original connection is still in flight, even one that ultimately writes nothing;
 *   the claim's own episode identity, not just the connection name, is what a finished attempt
 *   checks before touching it, so a stray finish from an old, long-torn-down episode can never
 *   be mistaken for one belonging to a brand new episode claimed later under the same name. The
 *   same episode identity is checked again, under the same lock, right before each asset is
 *   actually written, so a claim torn down and reclaimed under a different connection while this
 *   attempt was still fetching cannot have its stale write land under the new claim's name.</li>
 *   <li><b>Coordinated with this emulator's own deletion and disposal.</b> A fetch from the
 *   public source can take long enough for a concurrent {@code DeletePackageVersions}/{@code
 *   DeletePackage}/{@code DeleteRepository}/{@code DisposePackageVersions} on this exact version
 *   to finish first; each candidate's own write, via {@link
 *   CodeArtifactService#deployMavenAssetUnderVersionFence}, happens inside the exact same lock
 *   those four operations already hold for their entire duration, so the two can never
 *   interleave: whichever gets there first finishes completely before the other even starts,
 *   closing the race outright rather than narrowing the window it can land in. That same call
 *   also checks the version's current status directly, not just its fence, so a request already
 *   admitted before a disposal that finished before this attempt's own snapshot was even taken
 *   can never write real bytes back for a version already marked unusable.</li>
 *   <li><b>Resolving version ranges and dynamic versions.</b> A bare group/artifact-level
 *   {@code maven-metadata.xml} request is resolved by merging in whichever release versions the
 *   public repository's own metadata names, not by importing a single fixed version; see
 *   {@link #tryImportMetadata}'s own javadoc. Without this, a Maven version range or a Gradle
 *   dynamic version could never resolve against a connected repository that has not already
 *   retained some other fixed version of this exact package some other way.</li>
 * </ul>
 *
 * <p>Deliberately not modeled here, because it cannot apply without an actual upstream
 * <em>repository</em> chain: AWS's documented rule that an Archived/Disposed status on an
 * upstream repository's own copy of a version blocks retaining it further downstream. A direct
 * external connection has no such upstream repository in the picture at all, and the real public
 * source has no CodeArtifact-shaped status to check.
 */
@ApplicationScoped
public class CodeArtifactMavenExternalConnectionImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactMavenExternalConnectionImporter.class);

    private final CodeArtifactService service;
    private final ReposiliteSidecarClient reposilite;
    private final MavenExternalConnectionClient externalConnectionClient;

    @Inject
    public CodeArtifactMavenExternalConnectionImporter(CodeArtifactService service,
            ReposiliteSidecarClient reposilite, MavenExternalConnectionClient externalConnectionClient) {
        this.service = service;
        this.reposilite = reposilite;
        this.externalConnectionClient = externalConnectionClient;
    }

    /**
     * Attempts to retain {@code gav} (and, for a standard asset name, its 3 standard siblings)
     * into {@code repoId} from {@code repository}'s own maven external connection. Returns
     * {@code true} only when the originally-requested asset itself was imported: a caller that
     * gets {@code false} back should fall through to its own existing not-found response exactly
     * as if this method had never been called, the same way AWS's own "the only workaround is to
     * try again later" applies when nothing here can help (no connection configured, the version
     * is a SNAPSHOT, retention is blocked, a mixed-origin conflict, or the real public repository
     * genuinely doesn't have it).
     *
     * <p>A bare group/artifact-level {@code maven-metadata.xml} (no version segment in {@code
     * gav}) is resolved differently: see {@link #tryImportMetadata}. This only ever merges in
     * which releases the public repository has; the real bytes of whichever version a resolver
     * settles on afterward still come through this same method's ordinary per-asset path, on
     * the request that follows, the same two-step resolution a real {@code mvn}/Gradle client
     * already does against a connected upstream.
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
            return gav.endsWith("/maven-metadata.xml") && tryImportMetadata(region, domain, owner, repository,
                    repoId, namespace, packageName);
        }
        if (version.endsWith("-SNAPSHOT")) {
            return false;
        }

        String externalConnectionName = mavenExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "maven", namespace,
                packageName)) {
            return false;
        }

        // Snapshotted before the claim and the real writes below, not after, the same ordering
        // CodeArtifactMavenController#deploy already relies on: a DeletePackage landing mid-import
        // must not have its removal silently undone by the claim, the byte accounting, or the
        // default-config bookkeeping this retention attempt is about to record.
        long fenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository, "maven",
                namespace, packageName);
        // Snapshotted here, before the claim below, not after it: a DisposePackageVersions that
        // completes in the gap between the claim succeeding and a later snapshot read would
        // already have bumped the fence by the time that later read ran, so the read would
        // capture the post-disposal value as though it were the normal starting point, never
        // noticing anything was amiss. Taken first, any disposal landing anywhere after this
        // line, including during the claim call itself, is guaranteed to bump the fence to a
        // value strictly higher than what is captured here, which the later locked check inside
        // deployMavenAssetUnderVersionFence can then actually detect.
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "maven", namespace, packageName, version);
        // Claims the version's origin atomically, before any network fetch happens at all: see
        // claimMavenRetainedVersionOrigin's own javadoc for the race this closes (two concurrent
        // imports under two different connections could otherwise both pass a read-then-write
        // check and both write real bytes before either recorded anything), and for why its own
        // episodeId, not just the connection name, is what finally identifies this exact claim.
        CodeArtifactService.MavenClaimOutcome claim = service.claimMavenRetainedVersionOrigin(region, domain, owner,
                repository, namespace, packageName, version, externalConnectionName, fenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        int lastSlash = gav.lastIndexOf('/');
        String directory = gav.substring(0, lastSlash + 1);
        String filename = gav.substring(lastSlash + 1);
        List<String> standardNames = standardAssetFilenames(packageName, version);
        List<String> candidates = standardNames.contains(filename) ? standardNames : List.of(filename);

        boolean importedRequestedAsset = false;
        boolean importedAny = false;
        try {
            for (String candidateFilename : candidates) {
                String candidateGav = directory + candidateFilename;
                Optional<MavenExternalConnectionClient.FetchedAsset> fetched;
                try {
                    fetched = externalConnectionClient.fetchAsset(externalConnectionName, candidateGav);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not fetch {0} from {1} while retaining a Maven release through an "
                            + "external connection; skipping this candidate.", candidateGav, externalConnectionName);
                    continue;
                }
                if (fetched.isEmpty()) {
                    continue;
                }
                byte[] content = fetched.get().content();
                // The fence check and the real write happen inside the very same synchronized
                // call DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions
                // already hold across their own entire operation, so a write here and one of
                // those four operations can never interleave: whichever gets there first finishes
                // completely before the other even starts. See deployMavenAssetUnderVersionFence's
                // own javadoc for why this has to be a method on the service itself rather than a
                // block synchronized on the injected reference here: only a lock actually taken
                // inside that class's own synchronized method is guaranteed to be the exact same
                // lock its other synchronized methods already hold.
                // packageFenceSnapshot is passed through so byte accounting (when this write
                // genuinely adds new content) runs inside this exact same locked call too,
                // rather than in a separate call after this one already released the lock, which
                // would let a delete or disposal subtract this asset's bytes as part of its own
                // normal cleanup before that separate call added them, permanently over-counting
                // DescribeDomain's total for an asset already gone.
                CodeArtifactService.MavenDeployAttemptResult result = service.deployMavenAssetUnderVersionFence(
                        region, domain, owner, repository, namespace, packageName, version, versionFenceSnapshot,
                        fenceSnapshot, claim.episodeId(), reposilite, repoId, candidateGav, content);
                if (!result.fenceMatched()) {
                    // The version itself is gone: every later candidate in this same attempt
                    // would fail the exact same way, so none of them are even attempted.
                    break;
                }
                ReposiliteSidecarClient.DeployOutcome outcome = result.outcome();
                if (outcome == null) {
                    continue;
                }
                // The asset is genuinely present either way a 200/201 status is reached: a fresh
                // write (201, or 200 with wroteNewContent()) or an idempotent match against bytes
                // already there (200, wroteNewContent() false). Only a real conflict or sidecar
                // error leaves nothing to record.
                if (outcome.status() != 200 && outcome.status() != 201) {
                    continue;
                }
                importedAny = true;
                if (candidateFilename.equals(filename)) {
                    importedRequestedAsset = true;
                }
            }
        } finally {
            // Finalized here, not just after a clean fall-through of the loop above, so an
            // unexpected exception from one candidate still lets this bookkeeping run instead of
            // leaving the claim held (or released) without ever recording what the candidates
            // before it had already genuinely written.
            if (importedAny) {
                service.ensureRetainedPackageOriginDefault(region, domain, owner, repository, "maven", namespace,
                        packageName, fenceSnapshot);
            }
            // Matches the claim taken above, exactly once, regardless of what this attempt
            // actually wrote: see finishMavenRetainedVersionOriginAttempt's own javadoc for why
            // this must run even when importedAny is true, not only to release an empty claim.
            // Passes the claim's own episodeId back, not the connection name: see that same
            // javadoc for why a stray finish keyed only on connection name could otherwise affect
            // a same-named replacement claim this attempt was never actually part of.
            service.finishMavenRetainedVersionOriginAttempt(region, domain, owner, repository, namespace,
                    packageName, version, claim.episodeId(), importedAny);
        }
        return importedRequestedAsset;
    }

    /**
     * Whether {@code gav}'s own package <em>version</em> is already present in {@code
     * repository}'s own maven external connection (if any): used to enforce AWS's documented
     * "CodeArtifact will not permit publishing a package version that is already present in the
     * external repository." Checks the version itself, not the specific filename being
     * published: checking only the literal requested filename would let a non-standard asset
     * name (one the real public repository never serves, e.g. a custom
     * classifier) dodge this check entirely for a version that, under its standard name, already
     * exists there. A version already claimed locally under this exact connection is treated as
     * present without a further network call; otherwise the version's own standard POM (every
     * real published Maven version has one) is checked as the authoritative "does this version
     * exist" signal. A SNAPSHOT is never checked: external connections only ever import release
     * versions, so one can never genuinely collide with what the real public repository holds.
     */
    public boolean existsInExternalConnection(String region, String domain, String owner, String repository,
            String gav) {
        Optional<String[]> coordinate = CodeArtifactMavenController.parseNamespaceAndPackageName(gav);
        if (coordinate.isEmpty() || coordinate.get()[2] == null || coordinate.get()[2].endsWith("-SNAPSHOT")) {
            return false;
        }
        String namespace = coordinate.get()[0];
        String packageName = coordinate.get()[1];
        String version = coordinate.get()[2];
        String externalConnectionName = mavenExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.mavenRetainedVersionOriginConnection(region, domain, owner, repository, namespace, packageName,
                version).filter(externalConnectionName::equals).isPresent()) {
            return true;
        }
        int lastSlash = gav.lastIndexOf('/');
        String directory = gav.substring(0, lastSlash + 1);
        String pomGav = directory + packageName + "-" + version + ".pom";
        return externalConnectionClient.exists(externalConnectionName, pomGav);
    }

    /**
     * Resolves {@code namespace}/{@code packageName}'s own group/artifact-level {@code
     * maven-metadata.xml} through {@code repository}'s maven external connection: without this, a
     * Maven version range or a Gradle dynamic version could never resolve against a connected
     * repository that has not already retained a fixed version of this exact package some other
     * way, since nothing else here ever asks the real public repository what versions it
     * actually has. Every candidate version this repository already knows is {@code Archived}/
     * {@code Disposed} is kept out of the merge, the same existence check a per-version download
     * itself already enforces through {@link
     * CodeArtifactService#requireContainerBackedVersionDownloadable}, so a disposed version's
     * metadata entry can never come back from the public repository's own still-unaware listing.
     * Returns {@code true} only when the public repository's own metadata named at least one
     * allowed release version this repository did not already have listed; see {@link
     * ReposiliteSidecarClient#mergeExternalConnectionReleaseVersions}'s own javadoc for exactly
     * how a merge with any locally-published versions is handled.
     *
     * <p>Raised by Greptile's review: the real fetch from the public repository below can take
     * long enough for a concurrent {@code DeletePackage} to finish while it is still in flight.
     * Without a fence, the merge that follows would write {@code maven-metadata.xml} straight
     * back once that fetch completes, resurrecting the version list {@code DeletePackage} had
     * already told its caller was gone. {@code packageFenceSnapshot} is taken here, before that
     * fetch, for {@link CodeArtifactService#mergeMavenMetadataUnderPackageFence} to check right
     * before the write, inside the same lock {@code DeletePackage} itself holds for its own entire
     * operation; see that method's own javadoc for the full race this closes.
     */
    private boolean tryImportMetadata(String region, String domain, String owner, String repository, String repoId,
            String namespace, String packageName) {
        String externalConnectionName = mavenExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "maven", namespace,
                packageName)) {
            return false;
        }
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "maven", namespace, packageName);
        String metadataGav = namespace.replace('.', '/') + "/" + packageName + "/maven-metadata.xml";
        Optional<MavenExternalConnectionClient.FetchedAsset> fetched;
        try {
            fetched = externalConnectionClient.fetchAsset(externalConnectionName, metadataGav);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not fetch {0}/{1}''s maven-metadata.xml from {2} while resolving its versions "
                    + "through an external connection; leaving it unresolved.", namespace, packageName,
                    externalConnectionName);
            return false;
        }
        if (fetched.isEmpty()) {
            return false;
        }
        Predicate<String> versionAllowed = version -> {
            try {
                service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "maven",
                        namespace, packageName, version, repoId);
                return true;
            } catch (AwsException e) {
                return false;
            }
        };
        return service.mergeMavenMetadataUnderPackageFence(region, domain, owner, repository, namespace,
                packageName, packageFenceSnapshot, reposilite, repoId, fetched.get().content(), versionAllowed);
    }

    private String mavenExternalConnectionName(String region, String domain, String owner, String repository) {
        return service.describeRepository(region, domain, owner, repository).getExternalConnections().stream()
                .filter(ec -> "maven".equals(ec.getPackageFormat()))
                .map(ExternalConnection::getExternalConnectionName)
                .findFirst()
                .orElse(null);
    }

    private static List<String> standardAssetFilenames(String artifactId, String version) {
        String base = artifactId + "-" + version;
        return List.of(base + ".pom", base + ".jar", base + "-javadoc.jar", base + "-sources.jar");
    }
}
