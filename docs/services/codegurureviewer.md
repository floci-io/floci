# CodeGuru Reviewer

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the CodeGuru Reviewer repository-association lifecycle for local SDK,
CLI, and Terraform workflows. Associations are isolated by region and use the configured
Floci storage mode.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `AssociateRepository` | Associates a CodeCommit, Bitbucket, GitHub Enterprise Server, or S3 repository (`POST /associations`) |
| `DescribeRepositoryAssociation` | Returns the association, its state, and its tags (`GET /associations/{associationArn}`) |
| `DisassociateRepository` | Removes the association (`DELETE /associations/{associationArn}`) |
| `ListRepositoryAssociations` | Lists association summaries with filters and pagination (`GET /associations`) |
| `ListTagsForResource` | Lists association tags (`GET /tags/{resourceArn}`) |
| `TagResource` | Adds tags to an association (`POST /tags/{resourceArn}`) |
| `UntagResource` | Removes tag keys from an association (`DELETE /tags/{resourceArn}`) |
<!-- floci:actions:end -->

`ListRepositoryAssociations` accepts the documented filters (`ProviderType`,
`State`, `Name`, `Owner`) plus `MaxResults` and `NextToken` pagination.

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
