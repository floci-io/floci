package io.github.hectorvent.floci.services.codeartifact;

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
}
