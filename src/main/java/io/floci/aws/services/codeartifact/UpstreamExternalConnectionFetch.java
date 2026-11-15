package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The last leg of the upstream walk the four formats' upstream importers share: a version no
 * upstream holds is fetched through the first upstream, in the search order, with an external
 * connection, and retained there before it is retained in the requested repository, as the User
 * Guide's chain example has it.
 */
final class UpstreamExternalConnectionFetch {

    /**
     * The upstream that fetched the version and the version it now holds there. Its sidecar is
     * still in use for the caller, which ends that use once it has copied the version.
     */
    record Fetched(String repository, String repoId, String version) {
    }

    /**
     * Fetches through {@code candidate}'s external connection into its sidecar {@code
     * candidateRepoId}: the version {@code candidate} now holds, or empty when the connection
     * has nothing for the request.
     */
    @FunctionalInterface
    interface Fetch {
        Optional<String> fetch(String candidate, String candidateRepoId);
    }

    private UpstreamExternalConnectionFetch() {
    }

    /**
     * The first upstream of {@code repository}, in {@link
     * CodeArtifactService#resolveUpstreamSearchOrder}'s order, that {@code hasExternalConnection}
     * admits and that {@code fetch} gets the version through, still downloadable there: a version
     * an upstream holds Archived or Disposed is skipped here as the walk over what the upstreams
     * hold skips it, even when its external connection answers for it.
     */
    static Optional<Fetched> firstFetched(CodeArtifactService service, RepositorySidecarManager sidecar,
            String format, String region, String domain, String owner, String repository, String namespace,
            String packageName, Predicate<String> hasExternalConnection, Fetch fetch) {
        for (String candidate : service.resolveUpstreamSearchOrder(region, domain, owner, repository)) {
            if (!hasExternalConnection.test(candidate)) {
                continue;
            }
            String candidateRepoId;
            try {
                candidateRepoId = service.resolveContainerIdAndBeginUse(format, region, domain, owner, candidate,
                        sidecar);
            } catch (AwsException e) {
                // The upstream was deleted since the search order was read: nothing to fetch
                // through it.
                continue;
            }
            Optional<Fetched> fetched = Optional.empty();
            try {
                fetched = fetch.fetch(candidate, candidateRepoId)
                        .filter(version -> downloadable(service, owner, region, domain, candidate, format,
                                namespace, packageName, version, candidateRepoId))
                        .map(version -> new Fetched(candidate, candidateRepoId, version));
            } finally {
                if (fetched.isEmpty()) {
                    sidecar.endUse(candidateRepoId);
                }
            }
            if (fetched.isPresent()) {
                return fetched;
            }
        }
        return Optional.empty();
    }

    private static boolean downloadable(CodeArtifactService service, String owner, String region, String domain,
            String repository, String format, String namespace, String packageName, String version, String repoId) {
        try {
            service.requireContainerBackedVersionDownloadable(owner, region, domain, repository, format, namespace,
                    packageName, version, repoId);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }
}
