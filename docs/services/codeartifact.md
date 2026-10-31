# CodeArtifact

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the CodeArtifact control plane: domains, repositories, resource policies, tags,
and public upstream (external) connections. Package publish/fetch through the CodeArtifact API
itself is implemented for the `generic` format only, matching AWS's own restriction that
`PublishPackageVersion` accepts only `generic`. The `maven`, `npm`, `pypi`, and `nuget` formats are each
served through their own real package-manager-protocol proxy (`mvn`/Gradle, `npm`/`yarn`/`pnpm`,
`pip`/`twine`, and `nuget`/`dotnet` publish and resolve all work against the URL
`GetRepositoryEndpoint` returns); the remaining formats (Ruby, Swift, Cargo) have no real proxy
behind them yet.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Creates a domain (max 10 per account per Region), optionally with a KMS encryption key and initial tags. |
| `DeleteDomain` | Deletes a domain; fails with `ConflictException` while it still contains repositories, and with `ResourceNotFoundException` for a missing domain (as AWS does, although the API reference does not list it). |
| `DescribeDomain` | Returns a domain's full description, including its repository count and the total size of its assets (generic, Maven, npm, pypi, and NuGet; see Known limitations). |
| `ListDomains` | Lists domain summaries for the account and Region, paginated. |
| `GetAuthorizationToken` | Issues a bearer token scoped to a domain, valid for 0 (12 hours) or 900-43200 seconds, required by the `maven`, `npm`, `pypi`, and `nuget` repository endpoints. |
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
| `DescribePackage` | Returns a package's format, namespace, name, and origin controls (`publish` and `upstream` restrictions). |
| `DeletePackage` | Deletes a package and every one of its versions; `ResourceNotFoundException` for one that does not exist. |
| `PutPackageOriginConfiguration` | Sets a package's `publish`/`upstream` origin-control restrictions; can create the package (with no versions) if it doesn't exist yet. |
| `DescribePackageVersion` | Returns a package version's status, revision, and origin. |
| `GetPackageVersionAsset` | Downloads one asset from a package version by name, optionally pinned to a specific revision. |
| `ListPackageVersionAssets` | Lists the assets of a package version, with size and hashes. |
| `ListPackageVersions` | Lists a package's versions with status, revision, and origin, paginated and sorted most recently published first; `defaultDisplayVersion` is `dist-tags.latest` for npm, the most recently published version otherwise. |
| `ListPackages` | Lists package names across every format in a repository, with each package's origin controls; paginated, optionally filtered by format, namespace prefix, package-name prefix, or publish/upstream restriction. |
| `GetPackageVersionReadme` | Returns the readme text for a package version; `npm`, `pypi`, and `nuget` only, matching AWS's own restriction for this action. |
| `CopyPackageVersions` | Copies one or more versions of a package from one repository to another in the same domain. |
| `UpdatePackageVersionsStatus` | Moves one or more versions of a package to `Published`, `Unlisted`, or `Archived`; all-or-nothing per call, with `versionRevisions`/`expectedStatus` as optional preconditions. |
| `DisposePackageVersions` | Permanently deletes a package version's assets and sets its status to `Disposed`; irreversible, and shares `UpdatePackageVersionsStatus`'s all-or-nothing batch semantics. |
| `DeletePackageVersions` | Permanently removes one or more versions of a package, real assets included; unlike `DisposePackageVersions`, leaves no trace, the freed coordinates can be freely republished, and it is the one action allowed on an already-`Disposed` version. |
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

For the Maven format specifically, an external connection is more than stored metadata: a real
`mvn`/Gradle GET or HEAD miss on a repository with one of the 5 Maven external connections
attached (`public:maven-central`, `public:maven-clojars`, `public:maven-commonsware`,
`public:maven-googleandroid`, `public:maven-gradleplugins`) is retried against the real public
repository and, if found, retained into the repository exactly as a real CodeArtifact repository
would, with the same AWS-documented rules a real one applies: release versions only (AWS never imports a Maven SNAPSHOT through an external
connection), the standard `.pom`/`.jar`/`-javadoc.jar`/`-sources.jar` set imported together on a
standard-named request, a non-standard asset name imported individually and only on demand, a
retained version's own origin checked against any later asset request for the same version
(rejecting one that would mix in a different connection's bytes), and a direct `mvn deploy` of a
version already present in the connection rejected with `ConflictException`. See the
upstream-resolution limitation further down for exactly what this does not yet cover (a
repository-to-repository `upstreams` chain, for any format including Maven; npm/pypi/nuget's own
external connections).

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

## The nuget repository endpoint

`GetRepositoryEndpoint` for `format=nuget` returns `http://localhost:4566/codeartifact/nuget/<domain>/<repository>/`.
Real `nuget`/`dotnet` clients can publish to and resolve from that URL once `v3/index.json` is
appended to it, AWS's own documented requirement ("The source URL must end in /v3/index.json");
Floci authors that index itself rather than proxying one from the backing
[BaGetter](https://github.com/bagetter/bagetter) container, advertising only the `PackageBaseAddress/
3.0.0` (install) and `PackagePublish/2.0.0` (push) resources BaGetter's own flat container and
publish endpoints implement. Every request needs a token from `GetAuthorizationToken` scoped to
the domain being accessed, sent as HTTP Basic with the token as the password (any username),
the way AWS's own NuGet CLI configuration doc has the client authenticate
([configure the nuget or dotnet CLI](https://docs.aws.amazon.com/codeartifact/latest/ug/nuget-cli.html)),
the same Basic convention Maven's HTTP wagon and pip/twine use; a Bearer token is also accepted,
matching npm. A missing, invalid, expired, or wrong-domain token gets a 401 challenging `Basic`.

Like Verdaccio and pypiserver, BaGetter has no native concept of multiple named feeds inside one
instance, so each CodeArtifact repository gets its own BaGetter container instead of sharing one,
started lazily on first use and identified internally by a fresh id generated at
`CreateRepository` time, so a repository deleted and recreated under the same name never inherits
the previous one's packages. `DeleteRepository` stops and removes that repository's container
immediately, the same as npm and pypi.

A `nuget push`/`dotnet nuget push` has no separate `name`/`version` form field the way twine's
upload does; Floci reads both from the uploaded `.nupkg`'s own embedded `.nuspec` manifest (a
`.nupkg` is a plain ZIP, parsed with the JDK's own `java.util.zip`, matching the same no-new-
dependency approach already used elsewhere here). BaGetter's own `AllowPackageOverwrites: false`
default rejects even a byte-identical re-push, so Floci's proxy applies the same idempotent-retry
check as the pypi proxy: a re-push with content identical to what BaGetter already holds succeeds
without forwarding anything, and only a genuine content mismatch is rejected with `409`. One
config knob, `FLOCI_SERVICES_CODEARTIFACT_NUGET_IMAGE`, pins the image version.

`GetPackageVersionReadme` for `nuget` is answered live against the BaGetter container rather than
from a captured record: BaGetter keeps every version's own embedded readme file (if its nuspec
declared one with `<readme>`) addressable by exact version for as long as that version exists,
falling back to the nuspec's own `<description>` field when no readme file was embedded, matching
AWS's documented "the readme file or descriptive text" wording for this action exactly.

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
- **The native per-format download routes don't consult a container-backed version's status
  override.** `GetPackageVersionAsset` correctly blocks an Archived/Disposed version, but a plain
  `mvn`/`npm install`/`pip install`/`nuget` fetch against the Maven GAV, npm tarball, pypi
  filename, or nuget content route proxies straight to the sidecar and never checks it, so that
  same version's bytes are still fetchable through those routes.
- **A container-backed (`maven`/`npm`/`pypi`/`nuget`) version's status override does not survive a
  Floci restart.** It's kept in memory only, for the same reason the byte-count and readme
  bookkeeping those formats already need are: the sidecar content it describes doesn't survive a
  restart either (no volume attached, removed on shutdown), so persisting the override alone would
  leave it answering for a version that, after a restart, no longer exists anywhere real. A
  restart effectively un-disposes/un-archives every container-backed version back to `Published`
  (or, for one `DisposePackageVersions` already deleted the real assets of, back to not existing at
  all once the sidecar itself restarts empty). `generic`'s status has no such gap: it's stored
  directly on the version's own persisted record.
- **`CopyPackageVersions` has no full cross-call rollback on a mid-copy sidecar failure.**
  (`generic`, `maven`, `npm`, `pypi`, and `nuget` are all supported — matching real AWS, which
  places no `format` restriction on this action at all, unlike `PublishPackageVersion`'s own
  restriction to `generic`; `generic` copies through its own `packageVersions` record, while
  `maven`, `npm`, `pypi`, and `nuget` each bridge straight to their own sidecar's real wire
  protocol — `mvn deploy`, `npm publish`, `twine upload`, and `nuget push` respectively, confirmed
  against a live container for each — since none of the four keeps a `packageVersions` record
  this action could otherwise read from or write to.) A container-backed copy retains byte-for-byte
  source assets, and a best-effort compensating cleanup restores or removes whatever one version's
  own write touched if a later step in that same version fails, preserving the original error, but
  a sidecar outage mid-cleanup can still leave a version in an inconsistent state, the same
  residual risk the generic path's own write-level failure handling already accepts. A single call
  also holds every fetched asset in memory until the whole batch's destination writes complete, so
  a batch of many large assets is bounded by available heap, not disk; the same nominal-quota
  tradeoff the 5 GB limitation below already accepts elsewhere. A copied `npm` or `pypi` version's
  readme is propagated to the destination too, from this service's own record captured at publish
  time, since neither sidecar can answer for one after the fact (`GetPackageVersionReadme`'s own
  constraint, described earlier in this document); `nuget` needs no equivalent step, since
  `GetPackageVersionReadme` already answers it live from the `.nupkg` this action already copies
  in full. `includeFromUpstream` is accepted but has no effect: it names a source repository's
  own upstream-repository chain specifically, which this emulator does not resolve for any
  format yet (see the upstream-resolution limitation below). Maven's own external-connection
  retention is a different mechanism and does not change this.
- **The 5 GB asset size quota is nominal.** `PublishPackageVersion` and the Maven, pypi, and
  nuget repository endpoints all receive the request body as a single byte array before Floci ever
  checks its length, so a request already large enough to exhaust available heap fails before the
  quota check runs. The rejection (`ServiceQuotaExceededException` from `PublishPackageVersion`,
  HTTP 413 from the Maven, pypi, and nuget endpoints) is correct for anything that does fit in
  memory; it is not itself a streaming size limit.
- **Maven artifacts, npm packages, pypi packages, and NuGet packages do not survive a Floci
  restart, even under persistent storage.** The Reposilite instance and every per-repository
  Verdaccio, pypiserver, and BaGetter container have no volume attached and are removed on shutdown
  along with everything published to them. CodeArtifact repository/domain metadata (including each format's stored sidecar container
  id) survives a restart the same way any other Floci state does under persistent storage mode; the
  artifacts and packages themselves do not, so the first request after a restart re-provisions
  empty storage and returns 404 for anything published before the restart.
- **`GetAuthorizationToken` tokens are not revocable and are not tied to any IAM identity.** Real
  CodeArtifact tokens are scoped to the calling principal's permissions; Floci's are scoped only to
  the domain named in the request; anyone who obtains one keeps the same domain-scoped access for
  its full lifetime.
- **The Maven, npm, pypi, and nuget sidecar containers have no authentication of their own, and
  anything else sharing their Docker network can reach them directly on their container port.**
  Real CodeArtifact's token check happens once, at Floci's own proxy layer
  (`CodeArtifactMavenController`/the npm data plane/`CodeArtifactPypiController`/
  `CodeArtifactNuGetController`), before a request ever reaches Reposilite, Verdaccio, pypiserver,
  or BaGetter; those containers' own auth is deliberately disabled since the proxy is meant to be the
  only path in. Binding the published host port to loopback only keeps an outside host off that
  port; it does not stop another container already on the same configured Docker network (for
  example a Lambda, Batch, or ECS workload container) from reaching the sidecar on its
  container-internal address, bypassing the proxy's token check entirely. Closing this for real
  would mean isolating each sidecar on its own Docker network reachable only from Floci's own
  process, a change that would need to apply uniformly across all four sidecar-backed formats, not
  a one-off fix to any single proxy.
- **Only the Maven endpoint's own external connection actually resolves; nothing else does.**
  On real CodeArtifact, a repository with another repository configured as an upstream
  (`UpdateRepository`'s `upstreams`) or with an `AssociateExternalConnection` to a public
  repository serves packages from those sources too, not just its own. Floci's Maven endpoint
  now does this for a direct external connection to one of the 5 real public Maven repositories
  (`public:maven-central`, `public:maven-clojars`, `public:maven-commonsware`,
  `public:maven-googleandroid`, `public:maven-gradleplugins`): a GET/HEAD miss fetches and
  retains the package version from the real public repository, following AWS's own documented
  rules (release versions only; the standard 4 assets imported together, a non-standard asset
  name imported only on demand; a retained version's origin checked against any later asset
  fetched for it, rejecting one from a different connection; `upstream: BLOCK` respected). A real
  `mvn deploy` of a version already present in the external connection is also rejected (409),
  matching AWS's documented publish-side behavior. None of this applies to a repository-to-
  repository upstream (`UpdateRepository`'s `upstreams`, for any format including Maven, which is
  a materially different mechanism, not yet built) or to npm, pypi, or nuget's own external
  connections, which still 404 exactly as before: the pypi proxy's pypiserver container is
  started with `--disable-fallback`, and the nuget proxy's BaGetter container with
  `Mirror__Enabled=false`, specifically so neither ever silently redirects a missing package to
  the real, public PyPI or nuget.org.
- **Maven external-connection retention is the one place this emulator makes a real outbound
  call to the public internet**, rather than only ever talking to a container it started itself.
  An offline environment, or a real outage of the public repository being connected to, means a
  miss simply 404s, exactly as AWS documents ("the only workaround is to try again later").
  There is no caching of that failure, so a later retry against a recovered source works
  immediately, but there is also no retry/backoff of its own beyond the client's own.
  Package-group-level `EXTERNAL_UPSTREAM` (once package groups exist in this emulator) is not
  consulted yet by this check, only the plain package-level `upstream` restriction.
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
- **`DescribePackageVersion` reports only format/namespace/packageName/version/revision/status/origin
  for Maven, npm, pypi, and nuget, same as it does for generic.** Real CodeArtifact's `PackageVersionDescription`
  can also carry `displayName`, `summary`, `homePage`, `sourceCodeRepository`, `licenses`, and
  `publishedTime`, extracted from the package's own metadata; none of those are populated for any
  format today, container-backed or generic. For all four, the version's existence is
  confirmed directly against its sidecar (`RepositorySidecarManager.packageVersionExists`) rather
  than through the generic-format-only `packageVersions` record `GetPackageVersionAsset`'s own
  bridge also moved past, and the revision is synthesized the same deterministic way
  `GetPackageVersionAsset`'s bridge already does, so describing a version and then fetching one of
  its assets see the same revision. Every sidecar-backed version reports `Published`: none of
  these four formats' native publish paths (`mvn deploy`, `npm publish`, `twine upload`, `nuget
  push`) can leave a version in any other status the way a generic publish deliberately can with
  `unfinished=true`.
- **The nuget repository endpoint advertises no `RegistrationsBaseUrl` or `SearchQueryService`
  resource.** `nuget install`/`dotnet add package`/`dotnet restore` resolve a package's versions
  and dependencies from `PackageBaseAddress` alone (each version's own `.nuspec`, fetched directly,
  carries its dependency groups), which is what Floci implements, so this doesn't affect the
  install/restore path; it does mean `nuget list` has no resource to work against, which matches
  AWS's own documented restriction that only NuGet's V3 protocol works against CodeArtifact at all
  and that `nuget list` specifically isn't supported.
- **`DescribePackage`'s origin controls default to `publish: ALLOW`/`upstream: BLOCK`** (the
  default for a package whose first version was published directly) **until a caller sets them
  explicitly with `PutPackageOriginConfiguration`, or until the package's first version is
  retained from a Maven external connection, which instead defaults it to `publish: BLOCK`/
  `upstream: ALLOW`**, matching AWS's own documented "ingested from a public source" default. For
  Maven, npm, pypi, and nuget, `DescribePackage` asks the
  repository's sidecar whether the package exists, which starts that sidecar if it is not already
  running; a package that exists only because `PutPackageOriginConfiguration` created it (no
  versions yet) is found without touching any sidecar.
- **`PutPackageOriginConfiguration`'s `publish: BLOCK` is only enforced for the `generic`
  format.** `PublishPackageVersion`, the one path it actually gates, accepts only `generic` to
  begin with; Maven, npm, pypi, and nuget publishes go through their own native wire protocol (`mvn
  deploy`, `npm publish`, `twine upload`, `nuget push`) directly against the repository endpoint,
  which does not yet check this setting. `upstream` is stored and reported faithfully for every
  format; it gates Maven's own external-connection retention (see the upstream-resolution
  limitation above) but is not enforced anywhere else, since Floci has no other upstream
  ingestion path to block yet. `CopyPackageVersions` deliberately does not check `publish: BLOCK`
  either, for any format: the User Guide's own definition of that control scopes it to direct
  publish "using package managers or similar tools," a mechanism Copy is not.
- **`DescribeDomain`'s `assetSizeBytes` only counts what was published after this field started
  being computed.** Generic-format bytes are summed live from the still-existing
  `PublishPackageVersion` records, so they're always current. Maven, npm, pypi, and nuget have no
  such record to sum (the same absent generic-format record `DescribePackageVersion`'s own bridge works
  around by asking the sidecar directly instead), so each proxy reports its own successfully-written
  byte count directly; a package published through one of those four formats
  before this accounting existed was never reported and is not retroactively counted. A genuine
  conflict or an idempotent no-op retry (any of the four formats' own duplicate-publish protection)
  correctly adds nothing either way. Both `DeleteRepository` and `DeletePackage` clean
  up every format's own records and byte counts (and, for `DeleteRepository`, the per-version
  asset content underneath them), so a repository or package recreated under the same name never
  inherits a predecessor's bytes. Maven/npm/pypi/nuget byte counts live only in memory, the same as the
  sidecar content they describe (see the restart limitation below), not through `StorageFactory`
  like every durable store here: the content these bytes count does not survive a restart either,
  so neither does the count.
- **A whole-package `npm unpublish` is accounted for; unpublishing a single version of a
  multi-version package is not.** The pinned Verdaccio image is configured to honor `npm
  unpublish` natively (`unpublish: $all`), bypassing `DeletePackage` entirely; the npm data plane
  detects that one specific wire shape (`DELETE .../-rev/<revision>`) and cleans up this service's
  own byte count and deletion fence once Verdaccio confirms it. Removing a single version from a
  multi-version package is a different, harder-to-detect shape (a `PUT` carrying an updated
  packument, indistinguishable at the wire level from an ordinary republish), and is not detected;
  since this accounting is per-package, not per-version, there is no narrower count a single
  version's removal could subtract from in the first place.
- **A `maven-metadata.xml` upload for a versioned snapshot (`.../<artifact>/<version>/
  maven-metadata.xml`) is counted under a package name built from its version string, not its real
  artifact name.** Every real `mvn deploy` writes a group/artifact-level `maven-metadata.xml`
  too, which this correctly attributes to the real artifact; only the rarer, version-scoped
  snapshot form gets a package name this way. The bytes are still counted toward the domain total
  either way, just under an internal accounting bucket nothing else ever looks up by name.

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
| `FLOCI_SERVICES_CODEARTIFACT_NUGET_IMAGE` | `bagetter/bagetter:1.6.5` | BaGetter image used to serve the `nuget` format |
