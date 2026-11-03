package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Retains npm packages and package versions from the real public registry a repository's {@code
 * public:npmjs} external connection names, the way real CodeArtifact does when an {@code npm}
 * client requests a package that is not present locally yet. Confirmed against the User Guide's
 * own "Connect a CodeArtifact repository to a public repository" and "Requesting packages from
 * external connections" sections, not inferred:
 *
 * <ul>
 *   <li><b>One public connection, one asset per version.</b> AWS names {@code public:npmjs} as
 *   npm's only public connection, and states npm packages "only have one asset per package
 *   version", the same as NuGet: the real tarball itself is the only thing {@link
 *   #tryImportAsset} ever imports.</li>
 *   <li><b>No deterministic single-version request.</b> Unlike Maven's GAV layout and NuGet's
 *   {@code {id}/{version}/{filename}} content route, npm's own real protocol has no request
 *   shape naming one version: {@code npm install <pkg>@<version>} always fetches the bare
 *   package's full packument first and only picks the version out of its own {@code versions}
 *   map afterward (confirmed against {@code pacote}'s own source). {@link #mergedPackument} is
 *   this format's counterpart to {@code CodeArtifactMavenExternalConnectionImporter}'s own {@code
 *   maven-metadata.xml} merge: without it, a plain {@code npm install} could never even learn
 *   that an unretained version exists, let alone request its real asset.</li>
 *   <li><b>Checking asset origins.</b> A package version's retention origin, once confirmed,
 *   never changes (see {@link CodeArtifactService#claimRetainedVersionOrigin}'s own javadoc); an
 *   import attempt under a different connection than what is already claimed for that exact
 *   version is refused outright, and the claim stays reserved for as long as any concurrent
 *   attempt under the original connection is still in flight.</li>
 *   <li><b>Coordinated with this emulator's own deletion and disposal.</b> The real write, via
 *   {@link CodeArtifactService#retainAssetUnderVersionFence}, happens inside the exact same lock
 *   {@code DeletePackageVersions}/{@code DeletePackage}/{@code DeleteRepository}/{@code
 *   DisposePackageVersions} already hold for their entire duration, so the two can never
 *   interleave.</li>
 * </ul>
 */
@ApplicationScoped
public class CodeArtifactNpmExternalConnectionImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactNpmExternalConnectionImporter.class);

    /**
     * Package-level fields the real registry's full packument carries that {@code npm view
     * <pkg> <field>}-style commands read directly off the package, not off any one version
     * (found by Greptile's review: {@code readme} specifically, confirmed live to never appear
     * anywhere on the abbreviated form, or on any version's own entry even in the full form).
     * Never includes {@code versions}/{@code dist-tags}/{@code name}/{@code _id}: those are
     * merged by their own dedicated logic right below this list's own use, not by this
     * blanket copy.
     */
    private static final List<String> PACKAGE_LEVEL_DESCRIPTIVE_FIELDS = List.of("description", "readme",
            "readmeFilename", "homepage", "license", "keywords", "author", "repository", "bugs", "maintainers");

    private final CodeArtifactService service;
    private final VerdaccioSidecarClient verdaccio;
    private final NpmExternalConnectionClient externalConnectionClient;
    private final ObjectMapper mapper;

    @Inject
    public CodeArtifactNpmExternalConnectionImporter(CodeArtifactService service, VerdaccioSidecarClient verdaccio,
            NpmExternalConnectionClient externalConnectionClient, ObjectMapper mapper) {
        this.service = service;
        this.verdaccio = verdaccio;
        this.externalConnectionClient = externalConnectionClient;
        this.mapper = mapper;
    }

    /**
     * Resolves {@code namespace}/{@code packageName}'s own packument through {@code repository}'s
     * npm external connection, merging in whichever versions and dist-tags the real public
     * registry names that are not already known locally. Returns {@code Optional#empty()} when
     * there is no connection, retention is blocked, the real registry doesn't have this package at
     * all, or the merge adds nothing beyond what is already known locally: a caller that gets
     * {@code empty()} back should fall through to its own existing behavior exactly as if this
     * method had never been called, since that already correctly serves (or 404s) whatever is
     * known locally.
     *
     * <p>Every newly-merged version's {@code dist.tarball} is rewritten to this repository's own
     * tarball URL, under the exact {@code <bareName>-<version>.tgz} shape {@link #tryImportAsset}
     * itself expects back: the real asset is never fetched here, only promised, the same way
     * {@code CodeArtifactMavenExternalConnectionImporter}'s own {@code maven-metadata.xml} merge
     * only ever advertises release versions without fetching every jar up front. {@code
     * dist.shasum}/{@code dist.integrity} are kept exactly as the real registry declared them,
     * not recomputed here (there is no real content yet to hash): a client that later downloads
     * this version verifies its bytes against these same values, and once {@link
     * #tryImportAsset} actually fetches and stores the real tarball, the stored content is
     * byte-for-byte the real registry's own, so the hashes it independently computes from that
     * content agree with what was already promised here.
     *
     * <p>A dist-tag from the real registry is only ever written when it names a version this
     * merge actually knows about, and only replaces whatever the sidecar already has locally for
     * that same tag when the version that local tag currently names was itself only ever retained
     * through an external connection, never a tag a genuine local publish set: Verdaccio's own
     * first-ever publish to a package always points {@code dist-tags.latest} at that one version
     * (confirmed live; nothing in the retention write below can suppress it), so without this
     * check, retaining an old pinned version would leave {@code latest} stuck on it forever even
     * once a newer version exists on the real registry (found by Greptile's review). Mirrors
     * {@code CodeArtifactService#listPackageVersions}'s own identical check on {@code
     * preferredDisplayVersion} for exactly the same reason, on the AWS-facing side of this same
     * bug.
     *
     * <p>{@code fullMetadata} is the caller's own read of whether the real npm client actually
     * asked for the full document (an {@code Accept} with no {@code vnd.npm.install-v1+json},
     * the shape {@code npm view <pkg> readme}-style commands use) rather than the abbreviated
     * form {@code npm install} itself sends: only then is the real registry's full packument
     * fetched, and only then are its package-level, descriptive fields ({@code readme} among
     * them, found by Greptile's review: the abbreviated form never carries it at all) copied in,
     * never overriding a field already set locally. Honoring the caller's own request shape here,
     * rather than always fetching the (cheaper) abbreviated form regardless of what was asked,
     * keeps {@code npm install}'s own hot path exactly as cheap as before.
     */
    public Optional<ObjectNode> mergedPackument(String region, String domain, String owner, String repository,
            String repoId, String namespace, String packageName, boolean fullMetadata) {
        String externalConnectionName = npmExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return Optional.empty();
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "npm", namespace, packageName)) {
            return Optional.empty();
        }
        Optional<NpmExternalConnectionClient.FetchedAsset> fetched;
        try {
            fetched = fullMetadata
                    ? externalConnectionClient.fetchFullPackument(externalConnectionName, namespace, packageName)
                    : externalConnectionClient.fetchPackument(externalConnectionName, namespace, packageName);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not fetch {0}/{1}''s packument from {2} while resolving its versions through an "
                    + "external connection; leaving it unresolved.", namespace, packageName, externalConnectionName);
            return Optional.empty();
        }
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        JsonNode external;
        try {
            external = mapper.readTree(fetched.get().content());
        } catch (IOException e) {
            LOG.warnv(e, "Could not parse {0}/{1}''s packument fetched from {2}; leaving it unresolved.", namespace,
                    packageName, externalConnectionName);
            return Optional.empty();
        }
        // Re-checked here, right after the slow external fetch above and before this method ever
        // touches the sidecar, not just once at this method's own start (found by Greptile's
        // review): a DeleteRepository can finish its own cleanup while that fetch was still in
        // flight, and calling ensureReady below against an id that cleanup already released would
        // start a fresh container under a repository that no longer exists, one nothing can ever
        // release again. Narrows the window back down to an ordinary, already-accepted
        // check-then-act gap (the same one every other sidecar call here already has) rather than
        // one stretched out by a real network round trip; closing it completely needs the sidecar
        // itself to track in-flight use the way PypiserverSidecarManager's own container pool
        // does, tracked separately as a follow-up since Verdaccio's manager does not.
        try {
            service.describeRepository(region, domain, owner, repository);
        } catch (AwsException e) {
            return Optional.empty();
        }
        // Two different base URLs, deliberately: containerBaseUrl is the sidecar's own reachable
        // address, needed to actually read its stored packument, while publicBaseUrl (already
        // ending in "/", see publicUrl's own javadoc) is what a real client reaches this
        // repository's tarball route through and so the only one safe to embed in dist.tarball
        // below; a client sent containerBaseUrl instead could never actually reach it.
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
        if (fullMetadata) {
            for (String field : PACKAGE_LEVEL_DESCRIPTIVE_FIELDS) {
                if (!merged.has(field) && external.has(field)) {
                    merged.set(field, external.get(field));
                    addedAnything = true;
                }
            }
        }
        // Defaulted after the real external description had its own chance above: an empty
        // string here is a last resort, never preferred over the real registry's own value.
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
            // Raised by Greptile's review: DisposePackageVersions deletes a version's real
            // Verdaccio content outright (see VerdaccioSidecarClient#deletePackageVersionAsset),
            // so versions.has(version) alone cannot tell a genuinely new external version apart
            // from one this repository already disposed of; the real registry's own listing has
            // no idea either happened. Without this, a disposed version could be merged straight
            // back in (and even land on dist-tags.latest below), only to have its own tarball
            // download correctly, but confusingly, refused later by retentionWriteStillAllowed's
            // own status check. Archived is excluded the same way: an archived version is still
            // genuinely present locally, which versions.has already caught, so this only ever
            // actually filters a disposed one in practice.
            try {
                service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, "npm",
                        namespace, packageName, version, repoId);
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
                    && service.retainedVersionOriginConnection(region, domain, owner, repository, "npm", namespace,
                            packageName, currentLocalVersion).isPresent();
            if (currentlyUnset || currentlyPointsAtARetainedVersion) {
                distTags.set(tag.getKey(), tag.getValue());
                addedAnything = true;
            }
        }
        return addedAnything ? Optional.of(merged) : Optional.empty();
    }

    /**
     * Attempts to retain {@code namespace}/{@code packageName}/{@code version}'s one real asset
     * into {@code repoId} from {@code repository}'s own npm external connection. Returns {@code
     * true} only when the asset is genuinely present under this connection's claim by the time
     * this returns, whether because this attempt imported it or a concurrent one already had: a
     * caller that gets {@code false} back should fall through to its own existing not-found
     * response exactly as if this method had never been called.
     */
    public boolean tryImportAsset(String region, String domain, String owner, String repository, String repoId,
            String namespace, String packageName, String version, String assetName) {
        String externalConnectionName = npmExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "npm", namespace, packageName)) {
            return false;
        }

        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "npm", namespace, packageName);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "npm", namespace, packageName, version);
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "npm", namespace, packageName, version, externalConnectionName,
                packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        try {
            if (verdaccio.packageVersionExists(repoId, domain, repository, namespace, packageName, version)) {
                imported = service.retainedVersionOriginConnection(region, domain, owner, repository, "npm",
                        namespace, packageName, version).filter(externalConnectionName::equals).isPresent();
                return imported;
            }
            Optional<NpmExternalConnectionClient.FetchedAsset> fetched;
            try {
                fetched = externalConnectionClient.fetchTarball(externalConnectionName, namespace, packageName,
                        version);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not fetch {0} {1} {2} from {3} while retaining an npm package through an "
                        + "external connection.", namespace, packageName, version, externalConnectionName);
                return false;
            }
            if (fetched.isEmpty()) {
                return false;
            }
            byte[] content = fetched.get().content();
            // Fetched again here, full rather than abbreviated, rather than reusing whatever
            // mergedPackument already saw: that call is per-request and never keeps anything
            // around between one request and the next, and this is the one real network call
            // this whole import attempt pays for retrieving a version's dependencies/engines/
            // scripts and the package's own readme (see ExternalPackumentDetails' own javadoc),
            // paid at most once per version ever actually retained.
            ExternalPackumentDetails details = fetchExternalPackumentDetails(externalConnectionName, namespace,
                    packageName, version);
            if (details.versionDoc() == null) {
                // Raised by hectorvent's review: storing now with no full-form document would
                // retain this version with the minimal one storeRetainedVersionAsset falls back
                // to (no dependencies/engines/scripts), and permanently so, since the merge above
                // skips any version once versions.has(version) is true. Returning false instead
                // abandons this attempt without writing anything, so a later download retries the
                // full fetch rather than settling for a degraded document forever.
                LOG.warnv("Not retaining {0}/{1}@{2} from {3} without its full-form version "
                        + "document; a later download will retry.", namespace, packageName, version,
                        externalConnectionName);
                return false;
            }
            CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                    domain, owner, repository, "npm", namespace, packageName, version, versionFenceSnapshot,
                    packageFenceSnapshot, claim.episodeId(), repoId, assetName, content,
                    () -> verdaccio.storeRetainedVersionAsset(repoId, domain, repository, namespace, packageName,
                            version, assetName, content, details.versionDoc()));
            imported = result.fenceMatched() && result.wroteNewContent();
            if (imported) {
                // Raised by Greptile's review: without this, GetPackageVersionReadme 404s on an
                // otherwise fully retained version, since npm (unlike nuget's BaGetter) answers
                // that call from a capture taken at write time, never live from the sidecar; see
                // CodeArtifactService#recordPublishedPackageVersionReadme's own javadoc for why
                // versionFenceSnapshot, taken before this same write, is what it checks against.
                service.recordPublishedPackageVersionReadme(region, domain, owner, repository, "npm", namespace,
                        packageName, version, details.readme(), versionFenceSnapshot);
            }
            return imported;
        } finally {
            if (imported) {
                service.ensureRetainedPackageOriginDefault(region, domain, owner, repository, "npm", namespace,
                        packageName, packageFenceSnapshot);
            }
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "npm", namespace,
                    packageName, version, claim.episodeId(), imported);
        }
    }

    /**
     * {@code versionDoc} is the real registry's own full-form entry for exactly this version
     * (never missing/null, the shape {@link VerdaccioSidecarClient#storeRetainedVersionAsset}
     * itself already falls back safely without), or {@code null} when the full packument fetch
     * itself failed or no longer names this version; {@code readme} is the package-level readme
     * the full form carries (confirmed live: never present on any one version, only once at the
     * top of the whole document), or {@code ""} in either of those same failure cases, the same
     * empty-string convention pypi's own upload path already uses for a package with no readme at
     * all.
     */
    private record ExternalPackumentDetails(JsonNode versionDoc, String readme) {}

    private ExternalPackumentDetails fetchExternalPackumentDetails(String externalConnectionName, String namespace,
            String packageName, String version) {
        JsonNode full;
        try {
            Optional<NpmExternalConnectionClient.FetchedAsset> fetched = externalConnectionClient.fetchFullPackument(
                    externalConnectionName, namespace, packageName);
            if (fetched.isEmpty()) {
                return new ExternalPackumentDetails(null, "");
            }
            full = mapper.readTree(fetched.get().content());
        } catch (RuntimeException | IOException e) {
            LOG.warnv(e, "Could not fetch {0}/{1}''s full packument from {2} while retaining {3}; its dependencies "
                    + "and readme will be unavailable.", namespace, packageName, externalConnectionName, version);
            return new ExternalPackumentDetails(null, "");
        }
        JsonNode versionDoc = full.path("versions").path(version);
        return new ExternalPackumentDetails(versionDoc.isMissingNode() || versionDoc.isNull() ? null : versionDoc,
                full.path("readme").asText(""));
    }

    private static String packagePath(String namespace, String packageName) {
        return namespace == null
                ? SidecarUriUtils.encodeSegment(packageName)
                : "@" + SidecarUriUtils.encodeSegment(namespace) + "/" + SidecarUriUtils.encodeSegment(packageName);
    }

    private static String tarballFilename(String packageName, String version) {
        return packageName + "-" + version + ".tgz";
    }

    private String npmExternalConnectionName(String region, String domain, String owner, String repository) {
        return service.describeRepository(region, domain, owner, repository).getExternalConnections().stream()
                .filter(ec -> "npm".equals(ec.getPackageFormat()))
                .map(ExternalConnection::getExternalConnectionName)
                .findFirst()
                .orElse(null);
    }
}
