# CodeArtifact

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the CodeArtifact control plane: domains, repositories, resource policies, tags,
and public upstream (external) connections. Package publish/fetch through the CodeArtifact API
itself is implemented for the `generic` format only, matching AWS's own restriction that
`PublishPackageVersion` accepts only `generic`. The `maven` and `npm` formats are each served
through their own real package-manager-protocol proxy (`mvn`/Gradle and `npm`/`yarn`/`pnpm`
publish and resolve all work against the URL `GetRepositoryEndpoint` returns); the remaining
formats (PyPI, NuGet, etc.) have no real proxy behind them yet.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Creates a domain (max 10 per account per Region), optionally with a KMS encryption key and initial tags. |
| `DeleteDomain` | Deletes a domain; fails with `ConflictException` while it still contains repositories, and with `ResourceNotFoundException` for a missing domain (as AWS does, although the API reference does not list it). |
| `DescribeDomain` | Returns a domain's full description, including its repository count. |
| `ListDomains` | Lists domain summaries for the account and Region, paginated. |
| `GetAuthorizationToken` | Issues a bearer token scoped to a domain, valid for 0 (12 hours) or 900-43200 seconds, required by the `maven` and `npm` repository endpoints. |
| `PutDomainPermissionsPolicy` | Attaches or replaces a domain's resource policy, versioned by `policyRevision`. |
| `GetDomainPermissionsPolicy` | Returns a domain's current resource policy and revision. |
| `DeleteDomainPermissionsPolicy` | Removes a domain's resource policy, optionally checked against `policyRevision`. |
| `CreateRepository` | Creates a repository (max 1,000 per domain) with optional description, upstreams (max 10), and tags. |
| `DeleteRepository` | Deletes a repository. |
| `DescribeRepository` | Returns a repository's full description, including upstreams and external connections. |
| `UpdateRepository` | Updates a repository's description and/or upstream list. |
| `ListRepositories` | Lists repository summaries across all domains, optionally filtered by name prefix. |
| `ListRepositoriesInDomain` | Lists repository summaries within one domain, optionally filtered by name prefix. |
| `GetRepositoryEndpoint` | Returns the package-format-specific endpoint URL for a repository. |
| `PutRepositoryPermissionsPolicy` | Attaches or replaces a repository's resource policy, versioned by `policyRevision`. |
| `GetRepositoryPermissionsPolicy` | Returns a repository's current resource policy and revision. |
| `DeleteRepositoryPermissionsPolicy` | Removes a repository's resource policy, optionally checked against `policyRevision`. |
| `AssociateExternalConnection` | Attaches a fixed-catalog public upstream (e.g. `public:npmjs`) to a repository; mutually exclusive with repository upstreams. |
| `DisassociateExternalConnection` | Removes a repository's external connection. |
| `PublishPackageVersion` | Uploads a generic-format asset, creating or extending a package version; requires `x-amz-content-sha256` and verifies it against the real hash of the bytes received. |
| `DescribePackageVersion` | Returns a package version's status, revision, and origin. |
| `GetPackageVersionAsset` | Downloads one asset from a package version by name, optionally pinned to a specific revision. |
| `TagResource` | Adds or updates tags on a domain or repository ARN. |
| `UntagResource` | Removes tags by key from a domain or repository ARN. |
| `ListTagsForResource` | Lists the tags on a domain or repository ARN. |
<!-- floci:actions:end -->

Domains and repositories are account and Region scoped and persisted through `StorageFactory`.
`DeleteDomain` fails with `ConflictException` while the domain still contains repositories, matching
AWS. `PutDomainPermissionsPolicy`/`PutRepositoryPermissionsPolicy` use the returned `policyRevision`
for optimistic locking on subsequent updates, also matching AWS. Floci enforces AWS's own account
and domain quotas: `CreateDomain` caps a single account at 10 domains per Region, and
`CreateRepository` caps a single domain at 1,000 repositories, both returning
`ServiceQuotaExceededException` with the offending `resourceId`/`resourceType` once reached.

`AssociateExternalConnection` accepts the same fixed set of AWS-hosted public upstreams
documented for real CodeArtifact (`public:npmjs`, `public:pypi`, `public:maven-central`, etc.) and
enforces the one-external-connection-per-repository limit AWS enforces. A repository can have
upstream repositories or an external connection, but not both, matching AWS; `CreateRepository`
and `UpdateRepository` also cap direct upstreams at 10, AWS's own repository limit.

`PublishPackageVersion` creates a package version in the `Unfinished` state when the `unfinished`
flag is set, and `Published` otherwise; once `Published`, a repeat publish to the same
domain/repository/package/version fails with `ConflictException`, matching AWS's real rule that a
published version cannot accept additional assets. Every publish returns a fresh
`versionRevision`, and each asset's hashes (`MD5`, `SHA-1`, `SHA-256`, `SHA-512`) are computed from
the bytes Floci actually received, not echoed from the request. Floci enforces AWS's own published
quotas for this action: a 5 GB max asset file size and a 350-asset cap per package version, both
returning `ServiceQuotaExceededException`.

## The Maven repository endpoint

`GetRepositoryEndpoint` for `format=maven` returns `http://localhost:4566/codeartifact/maven/<domain>/<repository>/`.
Real Maven clients (`mvn deploy`, `mvn dependency:get`, Gradle) can publish to and resolve from
that URL directly, the same way they would against real AWS CodeArtifact; it speaks the raw Maven
repository layout (GET/PUT/HEAD over a group/artifact/version path), not the CodeArtifact JSON API.
Every request needs a token from `GetAuthorizationToken` scoped to the domain being accessed,
either as `Authorization: Bearer <token>` or as HTTP Basic with the token as the password (the
username is ignored). Basic is what a real `settings.xml`, configured the way
[AWS documents for `mvn`](https://docs.aws.amazon.com/codeartifact/latest/ug/maven-mvn.html)
(`<server><username>aws</username><password>${env.CODEARTIFACT_AUTH_TOKEN}</password></server>`),
actually sends: Maven's HTTP wagon authenticates with Basic, not a custom header. A missing,
invalid, expired, or wrong-domain token gets a 401 challenging `Basic`.

It is backed by a shared [Reposilite](https://reposilite.com) container that Floci starts lazily
on first use and reuses for every CodeArtifact repository; a CodeArtifact repository maps to its
own Reposilite repository, provisioned automatically the first time it is published to or fetched
from, and identified internally by a fresh id generated at `CreateRepository` time rather than a
name derived from the domain/repository, so a repository deleted and recreated under the same name
never inherits the previous one's artifacts. `DeleteRepository` also releases that storage:
Reposilite has no bulk-delete endpoint, so this deletes each of the repository's top-level entries
(DELETE recursively removes everything under a path in one call) before removing it from the
shared settings list. Two config knobs,
`FLOCI_SERVICES_CODEARTIFACT_MAVEN_IMAGE` and `FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL`, pin the
image version or point at an already-running instance and skip container management, matching the
pattern used elsewhere in Floci for sidecars.

## The npm repository endpoint

`GetRepositoryEndpoint` for `format=npm` returns `http://localhost:4566/codeartifact/npm/<domain>/<repository>/`.
Real npm clients (`npm publish`, `npm install`, and their `yarn`/`pnpm` equivalents) can publish to
and resolve from that URL directly; it speaks the real npm registry protocol (package metadata,
tarball fetch, publish), proxied straight through to a real [Verdaccio](https://verdaccio.org)
instance rather than reimplemented. Every request needs `Authorization: Bearer <token>`, using a
token from `GetAuthorizationToken` scoped to the domain being accessed; npm's own credential
configuration (`.npmrc`'s `//<registry-host>/<path>/:_authToken=...`) sets this the same way it
would against real AWS, and always as Bearer (unlike Maven's HTTP Basic). A missing, invalid,
expired, or wrong-domain token gets a 401 challenging `Bearer`.

Unlike Reposilite, which has a native concept of multiple named repositories inside one instance,
Verdaccio does not: each CodeArtifact repository gets its own Verdaccio container instead of
sharing one, started lazily on first use and identified internally by a fresh id generated at
`CreateRepository` time, so a repository deleted and recreated under the same name never inherits
the previous one's packages. `DeleteRepository` stops and removes that repository's container
immediately (the Maven proxy releases its own storage the same way, just through Reposilite's
settings API instead of a container stop, since Reposilite is one shared instance). Each container is
started with `VERDACCIO_PUBLIC_URL` set to that repository's own proxy URL, so package metadata it
returns (`dist.tarball`) points back through Floci instead of the container's own internal,
client-unreachable address; without this, `npm install` would try to fetch the tarball directly
from an address it cannot reach. One config knob, `FLOCI_SERVICES_CODEARTIFACT_NPM_IMAGE`, pins
the image version.

## AWS-compatible failures

Domain and repository names, tags, pagination, duplicate names, missing upstreams, policy-revision
mismatches, and non-empty-domain deletes are validated. Every action also validates its required
identifiers (`domain`, `repository`, `package`, `packageVersion`, `asset`) are present, returning
`ValidationException` rather than a misleading `ResourceNotFoundException` for one that's simply
missing from the request. Floci returns `ValidationException`,
`ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException` (tag limits)
for deterministic conditions represented by local state. `ConflictException`,
`ResourceNotFoundException`, and `ServiceQuotaExceededException` all carry the `resourceId`/
`resourceType` fields the wire model declares for them, matching the typed accessors the AWS SDK
exposes on those exceptions.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`.
Floci does not inject provider-side failures that cannot be derived from the request or emulator
state.

## Known limitations

- **Upstream cycles are not rejected.** `CreateRepository`/`UpdateRepository` reject a repository
  naming itself as its own upstream and require each named upstream to already exist, but a longer
  cycle (repository A has B as an upstream, B has A) is not detected.
- **`DeleteRepository` does not check whether other repositories still reference it as an
  upstream.** Deleting a repository leaves any repository that named it as an upstream pointing at
  one that no longer exists.
- **Cross-account `domainOwner` addressing has no authorization check.** Passing a `domainOwner`
  that is not the caller's own account looks up that account's domain/repository with no
  trust-policy or permissions-policy enforcement, consistent with Floci's IAM enforcement being
  opt-in elsewhere, but worth knowing if you rely on domain-sharing semantics.
- **Package management beyond publish/describe/get-asset isn't implemented.** `ListPackages`,
  `ListPackageVersions`, `ListPackageVersionAssets`, `DeletePackageVersions`,
  `DisposePackageVersions`, and `UpdatePackageVersionsStatus` don't exist yet; the only way to
  move a version from `Unfinished` to `Published` today is a follow-up `PublishPackageVersion`
  call that omits the `unfinished` flag.
- **The 5 GB asset size quota is nominal.** `PublishPackageVersion` and the Maven repository
  endpoint both receive the request body as a single byte array before Floci ever checks its
  length, so a request already large enough to exhaust available heap fails before the quota check
  runs. The rejection (`ServiceQuotaExceededException` from `PublishPackageVersion`, HTTP 413 from
  the Maven endpoint) is correct for anything that does fit in memory; it is not itself a streaming
  size limit.
- **Maven artifacts and npm packages do not survive a Floci restart, even under persistent
  storage.** The Reposilite instance and every per-repository Verdaccio container have no volume
  attached and are removed on shutdown along with everything published to them. CodeArtifact
  repository/domain metadata (including each format's stored sidecar container id) survives a
  restart the same way any other Floci state does under persistent storage mode; the artifacts and
  packages themselves do not, so the first request after a restart re-provisions empty storage and
  returns 404 for anything published before the restart.
- **`GetAuthorizationToken` tokens are not revocable and are not tied to any IAM identity.** Real
  CodeArtifact tokens are scoped to the calling principal's permissions; Floci's are scoped only to
  the domain named in the request; anyone who obtains one keeps the same domain-scoped access for
  its full lifetime.
- **Neither the Maven nor the npm repository endpoint resolves through upstream repositories or
  external connections.** On real CodeArtifact, a repository with another repository configured as
  an upstream (`UpdateRepository`'s `upstreams`) or with an `AssociateExternalConnection` to a
  public repository (`public:maven-central`, `public:npmjs`, etc.) serves packages from those
  sources too, not just its own. Floci's proxies only ever look up the repository's own backing
  storage: a package that exists solely in an upstream, or only through an external connection,
  returns 404 through a repository that has it configured as one.
- **The npm repository endpoint does not enforce the 5 GB asset size quota.** Unlike
  `PublishPackageVersion` and the Maven endpoint, it streams the request straight through to the
  backing Verdaccio container rather than buffering it first, so there is nowhere in the request
  path to check a byte count against the quota before forwarding it.

See the [CodeArtifact API Reference](https://docs.aws.amazon.com/codeartifact/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEARTIFACT_ENABLED` | `true` | Enable or disable CodeArtifact |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_IMAGE` | `dzikoysk/reposilite:3.6.3` | Reposilite image used to serve the `maven` format |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL` | unset | When set, use this URL and skip Reposilite container management |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_TOKEN` | unset | `name:secret` access token for a pre-configured `MAVEN_URL` |
| `FLOCI_SERVICES_CODEARTIFACT_NPM_IMAGE` | `verdaccio/verdaccio:6.10.4` | Verdaccio image used to serve the `npm` format |
