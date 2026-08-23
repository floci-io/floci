# AWS CodeArtifact

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/v1/...` (SigV4 service `codeartifact`)

CodeArtifact uses real REST paths with query parameters rather than RPC-style paths: the
`domain`, `domain-owner` and `repository` values travel in the query string
(`GET /v1/repository?domain=my-domain&repository=my-repo`), not in the request body.

## Supported Actions

| Action | Description |
|---|---|
| `CreateDomain` | Create a domain; `status` is `Active` on return |
| `DescribeDomain` | Read a domain, including `repositoryCount` and `s3BucketArn` |
| `DeleteDomain` | Delete a domain that holds no repositories |
| `ListDomains` | List domains, ordered by name |
| `PutDomainPermissionsPolicy` | Attach a resource policy to a domain |
| `GetDomainPermissionsPolicy` | Read a domain's resource policy |
| `DeleteDomainPermissionsPolicy` | Remove a domain's resource policy |
| `CreateRepository` | Create a repository, with upstreams and tags |
| `DescribeRepository` | Read a repository, its upstreams and external connections |
| `UpdateRepository` | Change a repository's description or upstreams |
| `DeleteRepository` | Delete a repository |
| `ListRepositories` | List repositories across every domain |
| `ListRepositoriesInDomain` | List repositories in one domain |
| `PutRepositoryPermissionsPolicy` | Attach a resource policy to a repository |
| `GetRepositoryPermissionsPolicy` | Read a repository's resource policy |
| `DeleteRepositoryPermissionsPolicy` | Remove a repository's resource policy |
| `AssociateExternalConnection` | Connect a repository to a public upstream |
| `DisassociateExternalConnection` | Remove a repository's external connection |
| `GetRepositoryEndpoint` | Return the package endpoint for a format |
| `TagResource` | Tag a domain or repository by ARN |
| `UntagResource` | Remove tags from a domain or repository |
| `ListTagsForResource` | List a domain's or repository's tags |

A domain reports `status: Active` from the first read, so nothing polls for a transition.
Domain ARNs are `arn:aws:codeartifact:<region>:<account>:domain/<name>` and repository ARNs
`arn:aws:codeartifact:<region>:<account>:repository/<domain>/<repository>`.

`DeleteDomain` refuses a domain that still contains repositories with `ConflictException`,
matching AWS. `AssociateExternalConnection` refuses a second connection on the same
repository, also with `ConflictException`, because AWS allows only one. The package format
of an external connection is derived from its name (`public:npmjs` gives `npm`,
`public:maven-central` gives `maven`, and so on); an unrecognised name is a
`ValidationException` rather than a silently accepted connection.

Resource policies carry a revision. Passing a `policyRevision` that does not match the
stored one fails with `ConflictException`, which is how AWS guards concurrent policy writes.

`GetRepositoryEndpoint` returns a URL on the emulator host whose path matches the AWS shape
(`/<format>/<repository>/`). The package data plane behind it is not emulated.

## Not implemented

The package operations return the emulator's not-found handling rather than a stub success:

`PublishPackageVersion`, `CopyPackageVersions`, `DeletePackageVersions`,
`DisposePackageVersions`, `UpdatePackageVersionsStatus`, `ListPackages`,
`ListPackageVersions`, `DescribePackage`, `DescribePackageVersion`, `DeletePackage`,
`ListPackageVersionAssets`, `GetPackageVersionAsset`, `GetPackageVersionReadme`,
`ListPackageVersionDependencies`, `PutPackageOriginConfiguration`, and the package group
operations (`CreatePackageGroup`, `UpdatePackageGroup`, `DeletePackageGroup`,
`DescribePackageGroup`, `ListPackageGroups`, `ListAllowedRepositoriesForGroup`,
`ListAssociatedPackages`, `ListSubPackageGroups`, `UpdatePackageGroupOriginConfiguration`).

These are a data plane over real artifact bytes — checksums, asset storage, dependency
graphs and upstream fetches from npm, PyPI and Maven Central. Serving them without the
artifacts would hand callers package versions that no asset request could satisfy.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEARTIFACT_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws codeartifact create-domain --domain my-domain

aws codeartifact create-repository \
  --domain my-domain --repository my-repo --description "team packages"

aws codeartifact associate-external-connection \
  --domain my-domain --repository my-repo --external-connection public:npmjs

aws codeartifact get-repository-endpoint \
  --domain my-domain --repository my-repo --format npm

aws codeartifact delete-repository --domain my-domain --repository my-repo
aws codeartifact delete-domain --domain my-domain
```
