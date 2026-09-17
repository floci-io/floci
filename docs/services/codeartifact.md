# CodeArtifact

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the CodeArtifact control plane: domains, repositories, resource policies, tags,
and public upstream (external) connections. Package publish/fetch endpoints for each format
(npm, PyPI, Maven, NuGet, generic, etc.) are not implemented yet.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Creates a domain, optionally with a KMS encryption key and initial tags. |
| `DeleteDomain` | Deletes a domain; fails with `ConflictException` while it still contains repositories. |
| `DescribeDomain` | Returns a domain's full description, including its repository count. |
| `ListDomains` | Lists domain summaries for the account and Region, paginated. |
| `PutDomainPermissionsPolicy` | Attaches or replaces a domain's resource policy, versioned by `policyRevision`. |
| `GetDomainPermissionsPolicy` | Returns a domain's current resource policy and revision. |
| `DeleteDomainPermissionsPolicy` | Removes a domain's resource policy, optionally checked against `policyRevision`. |
| `CreateRepository` | Creates a repository with optional description, upstreams (max 10), and tags. |
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
| `TagResource` | Adds or updates tags on a domain or repository ARN. |
| `UntagResource` | Removes tags by key from a domain or repository ARN. |
| `ListTagsForResource` | Lists the tags on a domain or repository ARN. |
<!-- floci:actions:end -->

Domains and repositories are account and Region scoped and persisted through `StorageFactory`.
`DeleteDomain` fails with `ConflictException` while the domain still contains repositories, matching
AWS. `PutDomainPermissionsPolicy`/`PutRepositoryPermissionsPolicy` use the returned `policyRevision`
for optimistic locking on subsequent updates, also matching AWS.

`AssociateExternalConnection` accepts the same fixed set of AWS-hosted public upstreams
documented for real CodeArtifact (`public:npmjs`, `public:pypi`, `public:maven-central`, etc.) and
enforces the one-external-connection-per-repository limit AWS enforces. A repository can have
upstream repositories or an external connection, but not both, matching AWS; `CreateRepository`
and `UpdateRepository` also cap direct upstreams at 10, AWS's own repository limit.

## AWS-compatible failures

Domain and repository names, tags, pagination, duplicate names, missing upstreams, policy-revision
mismatches, and non-empty-domain deletes are validated. Floci returns `ValidationException`,
`ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException` (tag limits)
for deterministic conditions represented by local state.

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

See the [CodeArtifact API Reference](https://docs.aws.amazon.com/codeartifact/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEARTIFACT_ENABLED` | `true` | Enable or disable CodeArtifact |
