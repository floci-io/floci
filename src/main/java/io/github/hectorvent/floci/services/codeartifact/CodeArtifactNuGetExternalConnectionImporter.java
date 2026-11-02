package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Optional;

/**
 * Retains a NuGet package version from the real public repository a repository's {@code
 * public:nuget-org} external connection names, the way real CodeArtifact does when a {@code
 * nuget}/{@code dotnet} client requests a package version that is not present locally yet.
 * Confirmed against the User Guide's own "Connect a CodeArtifact repository to a public
 * repository" section, not inferred:
 *
 * <ul>
 *   <li><b>Exactly one public connection, one asset per version.</b> AWS names {@code
 *   public:nuget-org} as NuGet's only public connection, and states NuGet packages "only have
 *   one asset per package version", unlike Maven's standard-sibling-asset bundle: the real {@code
 *   .nupkg} itself is the only thing this ever imports.</li>
 *   <li><b>No release-only restriction.</b> Unlike Maven's own external connections, AWS's docs
 *   name no equivalent carve-out for NuGet prerelease versions, so every version this emulator is
 *   asked for is eligible, prerelease included.</li>
 *   <li><b>Checking asset origins.</b> A package version's retention origin, once confirmed,
 *   never changes (see {@link CodeArtifactService#claimRetainedVersionOrigin}'s own javadoc,
 *   generalized from Maven's own first use of this mechanism); an import attempt under a
 *   different connection than what is already claimed for that exact version is refused
 *   outright, and the claim stays reserved for as long as any concurrent attempt under the
 *   original connection is still in flight.</li>
 *   <li><b>Coordinated with this emulator's own deletion and disposal.</b> The real write, via
 *   {@link CodeArtifactService#retainAssetUnderVersionFence}, happens inside the exact same lock
 *   {@code DeletePackageVersions}/{@code DeletePackage}/{@code DeleteRepository}/{@code
 *   DisposePackageVersions} already hold for their entire duration, so the two can never
 *   interleave.</li>
 * </ul>
 *
 * <p>Deliberately not modeled here, matching {@code CodeArtifactMavenExternalConnectionImporter}'s
 * own documented scope: resolving a floating/omitted version by merging in the public
 * repository's own version listing ({@code v3/package/{id}/index.json}). A client must request
 * this exact, fixed version for retention to trigger, the same baseline scope Maven's own first
 * external-connection work shipped before its own later metadata-merge follow-up.
 */
@ApplicationScoped
public class CodeArtifactNuGetExternalConnectionImporter {

    private static final Logger LOG = Logger.getLogger(CodeArtifactNuGetExternalConnectionImporter.class);

    private final CodeArtifactService service;
    private final BagetterSidecarClient bagetter;
    private final NuGetExternalConnectionClient externalConnectionClient;

    @Inject
    public CodeArtifactNuGetExternalConnectionImporter(CodeArtifactService service, BagetterSidecarClient bagetter,
            NuGetExternalConnectionClient externalConnectionClient) {
        this.service = service;
        this.bagetter = bagetter;
        this.externalConnectionClient = externalConnectionClient;
    }

    /**
     * Attempts to retain {@code packageId}/{@code version}'s one real asset into {@code repoId}
     * from {@code repository}'s own nuget external connection. Returns {@code true} only when the
     * asset is genuinely present under this connection's claim by the time this returns, whether
     * because this attempt imported it or a concurrent one already had: a caller that gets {@code
     * false} back should fall through to its own existing not-found response exactly as if this
     * method had never been called, the same way AWS's own "the only workaround is to try again
     * later" applies when nothing here can help (no connection configured, retention is blocked, a
     * mixed-origin conflict, or the real public repository genuinely doesn't have it).
     */
    public boolean tryImport(String region, String domain, String owner, String repository, String repoId,
            String packageId, String version) {
        String externalConnectionName = nugetExternalConnectionName(region, domain, owner, repository);
        if (externalConnectionName == null) {
            return false;
        }
        if (service.isUpstreamRetentionBlocked(region, domain, owner, repository, "nuget", null, packageId)) {
            return false;
        }

        // Snapshotted before the claim and the real write below, not after, the same ordering
        // CodeArtifactMavenExternalConnectionImporter#tryImport already relies on: a DeletePackage
        // landing mid-import must not have its removal silently undone by the claim, the byte
        // accounting, or the default-config bookkeeping this retention attempt is about to record.
        long packageFenceSnapshot = service.currentContainerBackedPackageFence(region, domain, owner, repository,
                "nuget", null, packageId);
        long versionFenceSnapshot = service.currentContainerBackedVersionFence(region, domain, owner, repository,
                "nuget", null, packageId, version);
        // Claims the version's origin atomically, before any network fetch happens at all: see
        // claimRetainedVersionOrigin's own javadoc for the race this closes.
        CodeArtifactService.RetainedVersionOriginClaimOutcome claim = service.claimRetainedVersionOrigin(region,
                domain, owner, repository, "nuget", null, packageId, version, externalConnectionName,
                packageFenceSnapshot);
        if (!claim.claimed()) {
            return false;
        }

        boolean imported = false;
        try {
            // BaGetter may already have these exact bytes by the time this attempt gets here, via
            // a concurrent attempt under this exact same claim episode (see
            // claimRetainedVersionOrigin's own javadoc on inFlightAttempts), or via a real client's
            // own direct push landing in the gap between this attempt's own claim above and this
            // check. Only the former genuinely proves this version arrived through
            // externalConnectionName (found by Greptile's review): existence alone does not, and
            // treating a direct push as "a sibling import already retained it" would wrongly mark
            // it EXTERNAL and overwrite its origin defaults in the finally block below. If this
            // isn't confirmed under this exact connection, the real write attempt further down
            // safely absorbs BaGetter's own AllowPackageOverwrites: false rejection (unlike
            // Reposilite's idempotent PUT) rather than this method ever assuming success from
            // existence alone.
            if (bagetter.packageVersionExists(repoId, domain, repository, null, packageId, version)) {
                imported = service.retainedVersionOriginConnection(region, domain, owner, repository, "nuget", null,
                        packageId, version).filter(externalConnectionName::equals).isPresent();
                return imported;
            }
            Optional<NuGetExternalConnectionClient.FetchedAsset> fetched;
            try {
                fetched = externalConnectionClient.fetchPackage(externalConnectionName, packageId, version);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not fetch {0} {1} from {2} while retaining a NuGet package through an "
                        + "external connection.", packageId, version, externalConnectionName);
                return false;
            }
            if (fetched.isEmpty()) {
                return false;
            }
            byte[] content = fetched.get().content();
            String assetName = packageId.toLowerCase(Locale.ROOT) + "." + version.toLowerCase(Locale.ROOT)
                    + ".nupkg";
            // The fence check and the real write happen inside the very same synchronized call
            // DeletePackageVersions/DeletePackage/DeleteRepository/DisposePackageVersions already
            // hold across their own entire operation, so a write here and one of those four
            // operations can never interleave. See retainAssetUnderVersionFence's own javadoc for
            // why this has to be a method on the service itself rather than a block synchronized on
            // an injected reference here: only a lock actually taken inside that class's own
            // synchronized method is guaranteed to be the exact same lock its other synchronized
            // methods already hold.
            CodeArtifactService.AssetRetentionAttemptResult result = service.retainAssetUnderVersionFence(region,
                    domain, owner, repository, "nuget", null, packageId, version, versionFenceSnapshot,
                    packageFenceSnapshot, claim.episodeId(), repoId, assetName, content,
                    () -> bagetter.storePackageVersionAsset(repoId, domain, repository, null, packageId, version,
                            assetName, content));
            imported = result.fenceMatched() && result.wroteNewContent();
            return imported;
        } finally {
            // Finalized here, not just after a clean return, so an unexpected exception still lets
            // this bookkeeping run instead of leaving the claim held (or released) without ever
            // recording what this attempt actually wrote.
            if (imported) {
                service.ensureRetainedPackageOriginDefault(region, domain, owner, repository, "nuget", null,
                        packageId, packageFenceSnapshot);
            }
            // Matches the claim taken above, exactly once, regardless of what this attempt actually
            // wrote: see finishRetainedVersionOriginAttempt's own javadoc for why this must run even
            // when imported is true, not only to release an empty claim.
            service.finishRetainedVersionOriginAttempt(region, domain, owner, repository, "nuget", null, packageId,
                    version, claim.episodeId(), imported);
        }
    }

    private String nugetExternalConnectionName(String region, String domain, String owner, String repository) {
        return service.describeRepository(region, domain, owner, repository).getExternalConnections().stream()
                .filter(ec -> "nuget".equals(ec.getPackageFormat()))
                .map(ExternalConnection::getExternalConnectionName)
                .findFirst()
                .orElse(null);
    }
}
