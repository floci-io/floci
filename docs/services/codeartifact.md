# CodeArtifact

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the CodeArtifact control plane: domains, repositories, resource policies, tags,
and public upstream (external) connections. Package publish/fetch is implemented for the
`generic` format only, matching AWS's own restriction that `PublishPackageVersion` accepts only
`generic`; the other formats (npm, PyPI, Maven, NuGet, etc.) have no real package-manager-protocol
proxy behind them yet.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Creates a domain (max 10 per account per Region), optionally with a KMS encryption key and initial tags. |
| `DeleteDomain` | Deletes a domain; fails with `ConflictException` while it still contains repositories, and with `ResourceNotFoundException` for a missing domain (as AWS does, although the API reference does not list it). |
| `DescribeDomain` | Returns a domain's full description, including its repository count. |
| `ListDomains` | Lists domain summaries for the account and Region, paginated. |
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
- **The 5 GB asset size quota is nominal.** `PublishPackageVersion` receives the request body as
  a single byte array before Floci ever checks its length, so a request already large enough to
  exhaust available heap fails before the quota check runs. The `ServiceQuotaExceededException`
  behavior is correct for anything that does fit in memory; it is not itself a streaming size
  limit.

See the [CodeArtifact API Reference](https://docs.aws.amazon.com/codeartifact/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEARTIFACT_ENABLED` | `true` | Enable or disable CodeArtifact |
