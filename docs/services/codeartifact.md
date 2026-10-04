# CodeArtifact

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the CodeArtifact control plane: domains, repositories, resource policies, tags,
and public upstream (external) connections. Package publish/fetch through the CodeArtifact API
itself is implemented for the `generic` format only, matching AWS's own restriction that
`PublishPackageVersion` accepts only `generic`. The `maven`, `npm`, and `pypi` formats are each
served through their own real package-manager-protocol proxy (`mvn`/Gradle, `npm`/`yarn`/`pnpm`,
and `pip`/`twine` publish and resolve all work against the URL `GetRepositoryEndpoint` returns);
the remaining formats (NuGet, etc.) have no real proxy behind them yet.

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
flag is set, and `Published` otherwise. While still `Unfinished`, publishing a new asset name is
always accepted; once `Published`, a new asset name always conflicts, since real CodeArtifact never
lets a `Published` generic version grow beyond the assets it already has. Republishing an asset name
that already exists is only accepted when the content is byte-identical to what's already stored
(AWS's own "Overwriting package assets" rule: idempotent on a matching retry, `ConflictException` on
genuinely different content), and that rule applies the same way regardless of the version's status,
since a client is just as likely to retry the terminal publish call (the one that leaves a version
`Published`) as any earlier one. The comparison itself is against each asset's persisted SHA-256,
not its raw bytes, so deciding whether content matches costs no disk read and needs no special case
for a backing file that happens to be missing (e.g. a partial restore); each asset's hashes (`MD5`,
`SHA-1`, `SHA-256`, `SHA-512`) are computed from the bytes Floci actually received, not echoed from
the request. An idempotent retry still separately verifies the backing file's actual bytes (streamed
in fixed-size chunks, not loaded whole) before deciding there's nothing to do: an intact file stays
a true no-op needing no write capacity at all, while a file that's missing or has been corrupted
independently of Floci gets rewritten, so that kind of gap doesn't survive indefinitely just because
its recorded checksum still matched. A publish that adds or changes something returns a fresh
`versionRevision`; an idempotent no-op retry returns the version's existing one unchanged. Floci
enforces AWS's own published quotas for this action: a 5 GB max asset file size and a 350-asset cap
per package version, both returning
`ServiceQuotaExceededException`.

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

Each Reposilite repository is provisioned with `redeployment: false`, so redeploying an existing
GAV path with different content is rejected with a real `409`, matching AWS's documented asset
immutability. Reposilite's own setting has no content-aware mode, though, and would reject a
byte-identical redeploy too; Floci fetches the existing artifact first and short-circuits to `200`
on an exact match, matching AWS's own documented exception ("Overwriting package assets" in
CodeArtifact's packages overview) that a republish is idempotent when the content hasn't actually
changed.

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

## The pypi repository endpoint

`GetRepositoryEndpoint` for `format=pypi` returns `http://localhost:4566/codeartifact/pypi/<domain>/<repository>/`.
Real pip and twine can install from and publish to that URL directly; it speaks the real PyPI
simple-repository protocol (simple index, package download, twine's multipart upload), proxied
straight through to a real [pypiserver](https://pypi.org/project/pypiserver/) instance rather than
reimplemented. Every request needs a token from `GetAuthorizationToken` scoped to the domain being
accessed, sent as HTTP Basic with the token as the password (any username) - AWS documents `pip`'s
index URL embedding `aws:$CODEARTIFACT_AUTH_TOKEN@` and twine's `.pypirc`/environment variables as
`username=aws, password=<token>`
([configure pip](https://docs.aws.amazon.com/codeartifact/latest/ug/python-configure-pip.html),
[configure twine](https://docs.aws.amazon.com/codeartifact/latest/ug/python-configure-twine.html)),
the same Basic convention Maven's HTTP wagon uses, not npm's Bearer-only `_authToken`; a Bearer
token is also accepted, matching npm, since accepting both costs nothing. A missing, invalid,
expired, or wrong-domain token gets a 401 challenging `Basic`.

Like Verdaccio, pypiserver has no native concept of multiple named indexes inside one instance, so
each CodeArtifact repository gets its own pypiserver container instead of sharing one, started
lazily on first use and identified internally by a fresh id generated at `CreateRepository` time,
so a repository deleted and recreated under the same name never inherits the previous one's
packages. `DeleteRepository` stops and removes that repository's container immediately, the same
as npm. Unlike Verdaccio, no public-URL environment variable is needed: pypiserver's simple-index
responses link to package files with a root-relative path (`/packages/<file>`), which pip and
twine resolve against whatever host they actually connected to, not an address the container
returns itself.

Unlike Reposilite (`redeployment: false`) and Verdaccio (which rejects a duplicate publish
natively, matching real npm), pypiserver has no overwrite protection of its own: publishing the
same package name, version, and filename twice with different content silently replaces the file
(confirmed by hand against a live instance with both twine and a raw multipart upload). Floci's
proxy closes this gap itself, matching AWS's own documented behavior for every format
("Overwriting package assets" in CodeArtifact's packages overview): before forwarding an upload, it
checks the target repository's own simple index for the exact filename being uploaded; if the
filename doesn't exist yet, the upload proceeds, if it exists with byte-identical content the
upload succeeds without touching pypiserver again (idempotent, so a client's retry after a dropped
response is never a spurious conflict), and only a name collision with genuinely different content
gets rejected with 409. One config knob, `FLOCI_SERVICES_CODEARTIFACT_PYPI_IMAGE`, pins the image
version.

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
- **The 5 GB asset size quota is nominal.** `PublishPackageVersion` and the Maven and pypi
  repository endpoints all receive the request body as a single byte array before Floci ever
  checks its length, so a request already large enough to exhaust available heap fails before the
  quota check runs. The rejection (`ServiceQuotaExceededException` from `PublishPackageVersion`,
  HTTP 413 from the Maven and pypi endpoints) is correct for anything that does fit in memory; it
  is not itself a streaming size limit.
- **Maven artifacts, npm packages, and pypi packages do not survive a Floci restart, even under
  persistent storage.** The Reposilite instance and every per-repository Verdaccio and pypiserver
  container have no volume attached and are removed on shutdown along with everything published to
  them. CodeArtifact repository/domain metadata (including each format's stored sidecar container
  id) survives a restart the same way any other Floci state does under persistent storage mode; the
  artifacts and packages themselves do not, so the first request after a restart re-provisions
  empty storage and returns 404 for anything published before the restart.
- **`GetAuthorizationToken` tokens are not revocable and are not tied to any IAM identity.** Real
  CodeArtifact tokens are scoped to the calling principal's permissions; Floci's are scoped only to
  the domain named in the request; anyone who obtains one keeps the same domain-scoped access for
  its full lifetime.
- **The Maven, npm, and pypi sidecar containers have no authentication of their own, and anything
  else sharing their Docker network can reach them directly on their container port.** Real
  CodeArtifact's token check happens once, at Floci's own proxy layer (`CodeArtifactMavenController`/
  the npm data plane/`CodeArtifactPypiController`), before a request ever reaches Reposilite,
  Verdaccio, or pypiserver; those containers' own auth is deliberately disabled since the proxy is
  meant to be the only path in. Binding the published host port to loopback only keeps an outside
  host off that port; it does not stop another container already on the same configured Docker
  network (for example a Lambda, Batch, or ECS workload container) from reaching the sidecar on its
  container-internal address, bypassing the proxy's token check entirely. Closing this for real
  would mean isolating each sidecar on its own Docker network reachable only from Floci's own
  process, a change that would need to apply uniformly across all three sidecar-backed formats, not
  a one-off fix to any single proxy.
- **Neither the Maven, npm, nor pypi repository endpoint resolves through upstream repositories or
  external connections.** On real CodeArtifact, a repository with another repository configured as
  an upstream (`UpdateRepository`'s `upstreams`) or with an `AssociateExternalConnection` to a
  public repository (`public:maven-central`, `public:npmjs`, `public:pypi`, etc.) serves packages
  from those sources too, not just its own. Floci's proxies only ever look up the repository's own
  backing storage: a package that exists solely in an upstream, or only through an external
  connection, returns 404 through a repository that has it configured as one; the pypi proxy's
  pypiserver container is also started with `--disable-fallback` specifically so it never silently
  redirects a missing package to the real, public PyPI.
- **The npm repository endpoint does not enforce the 5 GB asset size quota.** Unlike
  `PublishPackageVersion` and the Maven endpoint, it streams the request straight through to the
  backing Verdaccio container rather than buffering it first, so there is nowhere in the request
  path to check a byte count against the quota before forwarding it.
- **The pypi repository endpoint only serves the HTML Simple Repository API, not the PEP 691/700
  JSON variant.** AWS documents both: pip/uv can request
  `application/vnd.pypi.simple.v1+json` from `/simple/<project>/` and get a JSON index with
  per-file hashes, `requires-python`, and `upload-time`. Floci's proxy only ever forwards
  pypiserver's native HTML response regardless of the client's `Accept` header, since pypiserver
  itself has no JSON index support to pass through. Low practical risk in itself, since PEP 691
  requires clients to accept HTML as a fallback and pip/uv both do, but it is a real gap against
  AWS's documented surface, not just a quirk of the sidecar.
- **`DescribePackageVersion` can't see Maven-, npm-, or pypi-uploaded package versions.** Those
  three formats' repository endpoints are deliberately metadata-free passthroughs straight to
  their sidecar container (Reposilite/Verdaccio/pypiserver) and never create the generic-format
  package-version record this action depends on (`CodeArtifactService.describePackageVersion`
  only ever looks up `packageVersions.getForAccount`, the record only the `generic` format's
  `PublishPackageVersion` flow creates). `GetPackageVersionAsset` no longer has this gap: it
  bridges to each format's own sidecar (via a new `RepositorySidecarManager.fetchPackageVersionAsset`
  method each sidecar client implements) when there's no generic-format record, so it now works
  uniformly across formats the way real CodeArtifact's does. `DescribePackageVersion` is a harder
  problem for the same bridge: it describes every asset and the status of a whole version, not one
  named file, and none of these sidecars expose that shape cheaply (Reposilite has no per-GAV
  metadata beyond file listings; Verdaccio's own registry API could answer it for npm but Maven and
  pypi have nothing equivalent), so it remains unbridged and still 404s for these three formats.

See the [CodeArtifact API Reference](https://docs.aws.amazon.com/codeartifact/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEARTIFACT_ENABLED` | `true` | Enable or disable CodeArtifact |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_IMAGE` | `dzikoysk/reposilite:3.6.3` | Reposilite image used to serve the `maven` format |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL` | unset | When set, use this URL and skip Reposilite container management |
| `FLOCI_SERVICES_CODEARTIFACT_MAVEN_TOKEN` | unset | `name:secret` access token for a pre-configured `MAVEN_URL` |
| `FLOCI_SERVICES_CODEARTIFACT_NPM_IMAGE` | `verdaccio/verdaccio:6.10.4` | Verdaccio image used to serve the `npm` format |
| `FLOCI_SERVICES_CODEARTIFACT_PYPI_IMAGE` | `pypiserver/pypiserver:v2.4.2` | pypiserver image used to serve the `pypi` format |
