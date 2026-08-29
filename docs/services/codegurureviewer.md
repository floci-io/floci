# CodeGuru Reviewer

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the CodeGuru Reviewer repository-association lifecycle for local SDK,
CLI, and Terraform workflows. Associations are isolated by region and use the configured
Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `AssociateRepository` | `POST /associations` | Associate a CodeCommit, Bitbucket, GitHub Enterprise Server, or S3 repository |
| `DescribeRepositoryAssociation` | `GET /associations/{associationArn}` | Return the association, its state, and its tags |
| `DisassociateRepository` | `DELETE /associations/{associationArn}` | Remove the association |
| `ListRepositoryAssociations` | `GET /associations` | List association summaries, filtered by `ProviderType`, `State`, `Name`, `Owner` |
| `TagResource` | `POST /tags/{resourceArn}` | Add tags to an association |
| `UntagResource` | `DELETE /tags/{resourceArn}` | Remove tags from an association |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List association tags |

An association reaches the terminal `Associated` state as soon as `AssociateRepository`
returns, so a provider waiter polling `DescribeRepositoryAssociation` completes on its
first read instead of spinning through an `Associating` state the emulator would never
leave. The `Repository` union is validated the way AWS validates it: exactly one of
`CodeCommit`, `Bitbucket`, `GitHubEnterpriseServer`, or `S3Bucket` must be set, third-party
providers require `Owner` and `ConnectionArn`, and `CUSTOMER_MANAGED_CMK` encryption
requires a `KMSKeyId`. Associating the same repository twice reports `ConflictException`.

Code reviews and recommendations are not implemented.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEGURUREVIEWER_ENABLED` | `true` | Enable or disable CodeGuru Reviewer |
