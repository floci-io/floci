package io.github.hectorvent.floci.services.codeartifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Common contract for a CodeArtifact sidecar backing one package format: get one repository ready
 * to serve that format, and release whatever backing storage it holds once the repository is
 * deleted. Implementations are discovered via CDI by {@link CodeArtifactSidecarRegistry}, so
 * adding the next format's sidecar (npm, pypi, nuget) needs no change to
 * {@link CodeArtifactService} or any other format's manager.
 *
 * <p>Named around the effect ("release this repository's backing storage"), not the mechanism,
 * because the mechanism is expected to differ per sidecar. {@code ReposiliteSidecarClient}, the
 * first implementation, deletes files and removes one entry from a settings list shared by every
 * maven repository in a single Reposilite instance; a future format whose sidecar runs one
 * container per repository would instead stop and remove that container. Both are legitimate
 * implementations of the same contract even though neither necessarily "stops a container" the
 * way the other might.
 */
public interface RepositorySidecarManager {

    /** The CodeArtifact package format this sidecar backs, e.g. {@code "maven"}. */
    String format();

    /**
     * Base URL of a ready backend for {@code repositoryContainerId}, provisioning it if this is
     * the first use.
     *
     * @param publicUrl the externally reachable proxy URL for this specific repository, passed
     *                   through so any self-referential URLs the sidecar returns in its own
     *                   protocol responses point back through Floci instead of an internal,
     *                   client-unreachable address. Not every sidecar's protocol has this problem
     *                   (Maven's does not); an implementation that doesn't need it ignores it.
     */
    String ensureReady(String repositoryContainerId, String publicUrl);

    /**
     * Releases the backing storage for one repository (deletes its files, edits a shared settings
     * list, stops a container, whatever this sidecar's own shape requires) so a later repository
     * created under the same name never inherits it. A repository that never actually used this
     * format is a no-op, not an error.
     */
    void release(String repositoryContainerId);

    /**
     * Fetches the bytes of one named asset already published to this sidecar's own backing
     * storage, for bridging {@code GetPackageVersionAsset} when there is no generic-format record
     * to serve it from: Maven, npm, and pypi are metadata-free passthroughs straight to their
     * sidecar, so nothing in {@code CodeArtifactService}'s own package-version store ever records
     * what they hold. {@code domain}/{@code repository} exist only for a sidecar whose
     * {@link #ensureReady} needs a public URL to self-start correctly the same way a real client's
     * first request would; most implementations ignore them. {@code namespace} is {@code null}
     * for a format or package that has none. Returns empty both when the asset genuinely isn't
     * there and when the repository was never started for this format, since an unstarted sidecar
     * has published nothing either way.
     */
    Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName);

    /**
     * {@code true} when this sidecar holds at least one version of the named package. {@code namespace}
     * follows the same convention as {@link #fetchPackageVersionAsset}: {@code null} for a format that
     * has none. A repository that was never started for this format holds nothing, so it is {@code false}.
     */
    boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName);

    /**
     * {@code true} when this sidecar has written anything at all under this package, even content
     * that would not count as "holding a version" for {@link #packageExists}: a Maven
     * group/artifact directory that only ever got a {@code maven-metadata.xml} merged into it to
     * advertise a public repository's release list, with no version ever actually retained, is
     * real content a {@code DeletePackage} still has to clear, even though {@link #packageExists}
     * correctly answers {@code false} for it. Defaults to exactly {@link #packageExists}, since no
     * other format currently has an equivalent metadata-but-no-version state; {@code
     * ReposiliteSidecarClient} is the only implementation that overrides this, so a package left
     * in that state is still actually deleted rather than {@code DeletePackage} reporting success
     * while leaving it fetchable.
     */
    default boolean hasAnyContentToDelete(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName) {
        return packageExists(repositoryContainerId, domain, repository, namespace, packageName);
    }

    /**
     * {@code true} when this sidecar holds exactly the named version of the named package, for
     * bridging {@code DescribePackageVersion} when there is no generic-format record to describe:
     * Maven, npm, and pypi are metadata-free passthroughs straight to their sidecar, so nothing in
     * {@code CodeArtifactService}'s own package-version store ever records what they hold, the
     * same reason {@link #fetchPackageVersionAsset} exists. Unlike that method, there is no asset
     * name to confirm against: real CodeArtifact's own {@code DescribePackageVersion} response has
     * no asset listing at all, so a cheap existence check is all this needs. {@code namespace}
     * follows the same convention as {@link #fetchPackageVersionAsset}: {@code null} for a format
     * that has none. A repository that was never started for this format holds nothing, so this is
     * {@code false}.
     */
    boolean packageVersionExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName, String version);

    /**
     * Deletes every version of the named package from this sidecar's own backing storage. Every
     * sidecar Floci currently backs (Reposilite, Verdaccio, pypiserver) can do this; the default
     * throws {@link UnsupportedOperationException} rather than silently leaving the package in place
     * or claiming a success it cannot back up, for a future format whose sidecar genuinely cannot. An
     * implementation that can delete overrides this.
     */
    default void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        throw new UnsupportedOperationException(format() + " packages cannot be deleted through this sidecar");
    }

    /**
     * Live readme/descriptive-text lookup for one package version, for a sidecar capable of
     * answering this for a specific, possibly historical version after the fact. {@code empty()}
     * by default: Verdaccio (npm) only ever exposes the most-recently-published version's readme,
     * and pypiserver exposes none at all, so neither can implement this and {@code
     * CodeArtifactService#getPackageVersionReadme} instead answers npm and pypi from a record
     * captured at publish time ({@link CodeArtifactService#recordPublishedPackageVersionReadme}).
     * {@code BagetterSidecarClient} overrides this: BaGetter keeps every version's own readme
     * addressable by exact version for as long as that version exists, so nuget needs no such
     * capture step.
     *
     * <p>Returning {@code empty()} here is read by the caller as "this sidecar cannot answer
     * live", not "this version has no readme": an override distinguishes the two with {@code
     * Optional.of("")} for a published version with nothing to show versus {@code empty()} for a
     * version that was never published, the same convention {@link #fetchPackageVersionAsset}
     * already uses for "found, but empty" versus "not found".
     */
    default Optional<String> fetchPackageVersionReadme(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        return Optional.empty();
    }

    /**
     * Lists the asset name of every file published under one package version, for bridging
     * {@code CopyPackageVersions} when there is no generic-format record to enumerate assets from
     * the way {@code CodeArtifactPackageVersion#getAssets} does. The default throws {@link
     * UnsupportedOperationException}, the same convention {@link #deletePackage} already uses for
     * a capability not every sidecar backs; every container-backed format's own client overrides
     * this today.
     */
    default List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        throw new UnsupportedOperationException(format() + " package versions cannot be listed through this "
                + "sidecar");
    }

    /**
     * Writes one named asset's bytes directly into this sidecar's own backing storage for the
     * given package version, for bridging {@code CopyPackageVersions}'s destination write. The
     * caller is expected to have already removed any conflicting content at this exact path first
     * (see {@link #deletePackageVersionAsset}) when overwriting, since this is a direct admin-level
     * write, not a replay of whatever conflict rule the sidecar's own native publish path enforces.
     * The default throws {@link UnsupportedOperationException}.
     */
    default void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        throw new UnsupportedOperationException(format() + " package versions cannot be copied through this "
                + "sidecar");
    }

    /**
     * {@code CopyPackageVersions}' own destination write, as opposed to a bare {@link
     * #storePackageVersionAsset} call with no notion of a source: the default simply forwards to
     * it, correct for a format whose version-level metadata is entirely recoverable from the
     * asset bytes alone (Maven's POM/jar pair, pypi's sdist/wheel, nuget's one-file nupkg, none of
     * which keep anything outside what {@code content} itself carries). {@code
     * VerdaccioSidecarClient} overrides this: npm's packument carries fields ({@code
     * dependencies}, {@code scripts}, {@code engines}, ...) on the version document itself, so
     * building a fresh one from scratch the way {@link #storePackageVersionAsset} does on its own
     * drops them; this instead reuses the source's own version entry verbatim, with only {@code
     * dist.tarball}/{@code dist.shasum}/{@code dist.integrity} rewritten to point at the copy.
     */
    default void copyPackageVersionAsset(String sourceRepositoryContainerId, String domain, String sourceRepository,
            String repositoryContainerId, String repository, String namespace, String packageName, String version,
            String assetName, byte[] content) {
        storePackageVersionAsset(repositoryContainerId, domain, repository, namespace, packageName, version,
                assetName, content);
    }

    /**
     * Deletes one already-confirmed-present named asset from this sidecar's own backing storage
     * for the given package version, used by {@code CopyPackageVersions}'s overwrite path to drop
     * an asset the destination version held that the source version no longer does, and to clear
     * the way for {@link #storePackageVersionAsset} to replace an asset whose content differs. The
     * default throws {@link UnsupportedOperationException}.
     */
    default void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        throw new UnsupportedOperationException(format() + " package version assets cannot be deleted through "
                + "this sidecar");
    }

    /**
     * Records that {@code version} now exists under this package, for whatever artifact-level
     * version listing this format's sidecar keeps separately from the version directory itself
     * (one {@link #storePackageVersionAsset}/{@link #deletePackageVersionAsset} call already
     * writes to), so a real client resolving "what versions does this artifact have" sees it.
     * The default is a no-op: most formats have no such separate listing, since their package
     * document is derived live from the version directories those two methods already maintain.
     * {@code ReposiliteSidecarClient} overrides this: Maven's group/artifact-level {@code
     * maven-metadata.xml} is a distinct upload a real {@code mvn deploy} always also PUTs,
     * outside the version directory {@link #listPackageVersionAssetNames} enumerates, so nothing
     * else here ever touches it on {@code maven}'s behalf.
     */
    default void recordVersionAvailable(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
    }

    /**
     * One version this sidecar holds for a package, paired with when it was published, for
     * {@link #listPackageVersions}. {@code publishedTime} is {@code null} when this sidecar's own
     * protocol has no timestamp for that version at all; {@code CodeArtifactService} then falls
     * back to a deterministic tie-break (the version string itself) rather than treating it as
     * more or less recent than any other version.
     */
    record PackageVersionTimestamp(String version, Instant publishedTime) {}

    /**
     * Every version this sidecar holds of the named package, for bridging {@code
     * ListPackageVersions} when there is no generic-format record to list versions from, the same
     * reason {@link #listPackageVersionAssetNames} exists for one version's own assets. Unlike
     * that method, there is no existing "list everything under this package" primitive to lean
     * on: each implementation needs its own new, format-specific enumeration. The default throws
     * {@link UnsupportedOperationException}, the same convention {@link #deletePackage} already
     * uses for a capability not every sidecar backs; every container-backed format's own client
     * overrides this.
     */
    default List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        throw new UnsupportedOperationException(format() + " package versions cannot be listed through this "
                + "sidecar");
    }

    /**
     * One package this sidecar holds, identified the same way {@link #packageExists} is:
     * {@code namespace} is {@code null} for a format or package that has none.
     */
    record PackageIdentifier(String namespace, String packageName) {}

    /**
     * Every package this sidecar holds at least one real version of, for bridging {@code
     * ListPackages} when there is no generic-format record to list packages from. Unlike {@link
     * #listPackageVersions}, which already knows which one package to ask about, this has no
     * known starting point at all: each implementation needs its own new, format-specific
     * repository-wide enumeration, confirmed live against each real sidecar protocol rather than
     * assumed (Verdaccio's {@code /-/all}, pypiserver's root simple index, BaGetter's Search
     * resource pressed into service for this admin-only purpose, Reposilite's own directory
     * listing walked recursively since it has no single list-everything endpoint). The default
     * throws {@link UnsupportedOperationException}, the same convention {@link #deletePackage}
     * already uses for a capability not every sidecar backs; every container-backed format's own
     * client overrides this.
     */
    default List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        throw new UnsupportedOperationException(format() + " packages cannot be listed through this sidecar");
    }

    /**
     * Marks that {@code repositoryContainerId} is about to be used for {@link
     * #listPackageVersions}, called by {@code CodeArtifactService} right after it resolves that
     * id and before it does anything else with it. Resolving the id and entering {@link
     * #listPackageVersions} are two separate statements there, with a concurrent repository
     * delete's cleanup able to run in between them; pairing this with {@link #endUse} in a
     * {@code try}/{@code finally} around the whole {@link #listPackageVersions} call closes that
     * window the same way {@code PypiserverSidecarClient} already closes the narrower one inside
     * the call itself. Default is a no-op: only a sidecar whose own concurrency bookkeeping dies
     * with the container (pypiserver) has anything here to protect; one with no such bookkeeping,
     * or (Maven) a container that is never torn down, does not.
     */
    default void beginUse(String repositoryContainerId) {
    }

    /** Marks the end of one {@link #beginUse}. Default is a no-op, for the same reason. */
    default void endUse(String repositoryContainerId) {
    }

    /**
     * Called once per version, after every one of its assets has already been removed through
     * {@link #deletePackageVersionAsset}, for a sidecar whose own native listing can name a
     * version somewhere other than the per-version assets {@link #listPackageVersionAssetNames}
     * already enumerates: Maven's artifact-level {@code maven-metadata.xml}, which {@code
     * ReposiliteSidecarClient} overrides this to rewrite. Default is a no-op: a sidecar with no
     * such separate listing (every format {@code DisposePackageVersions} supports besides Maven)
     * already has nothing left to say the version ever existed once its assets are gone.
     */
    default void recordVersionDisposed(String repositoryContainerId, String namespace, String packageName,
            String version) {
    }

    /**
     * This format's own preferred "default display" version for a package, independent of publish
     * recency, for {@code ListPackageVersions}' {@code defaultDisplayVersion}. {@code empty()} by
     * default: every sidecar Floci currently backs besides npm has no such separate preference of
     * its own (AWS documents Maven and PyPI's default display version as simply "the most recently
     * published package version," which {@code CodeArtifactService} already falls back to on an
     * empty result here). {@code VerdaccioSidecarClient} overrides this: npm's own {@code
     * dist-tags.latest} is exactly this preference, and AWS documents the same most-recently-
     * published fallback for npm only when that tag isn't set.
     */
    default Optional<String> preferredDisplayVersion(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName) {
        return Optional.empty();
    }
}
