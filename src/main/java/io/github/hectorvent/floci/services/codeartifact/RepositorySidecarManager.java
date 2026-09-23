package io.github.hectorvent.floci.services.codeartifact;

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
}
