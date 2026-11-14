package io.floci.aws.services.codeartifact;

/**
 * Which of the two real sources a retained package version's claim (see {@code
 * CodeArtifactService#claimRetainedVersionOrigin}) was taken under: a real public registry
 * reached through a repository's {@code externalConnections} ({@code public:npmjs}, {@code
 * public:maven-central}, and so on), or another repository in the same domain reached through a
 * repository's own {@code upstreams} list. A version can only ever be retained under one of the
 * two (a repository has either an external connection or upstreams, never both, enforced at {@code
 * associateExternalConnection}), but the claim/fence machinery is shared between them, so a claim
 * needs this tag to tell its own source kind apart once confirmed.
 */
enum RetentionOriginKind {
    EXTERNAL_CONNECTION,
    UPSTREAM_REPOSITORY
}
