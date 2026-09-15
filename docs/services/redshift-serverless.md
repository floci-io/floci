# Redshift Serverless

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftServerless.<Operation>` and `Content-Type: application/x-amz-json-1.1`

Floci emulates the namespace lifecycle of Amazon Redshift Serverless: the account and Region scoped container that holds a serverless database, its admin credentials, and its IAM roles. This is the surface Terraform's `aws_redshiftserverless_namespace` drives.

For the upstream API shape, see the [Amazon Redshift Serverless API Reference](https://docs.aws.amazon.com/redshift-serverless/latest/APIReference/Welcome.html).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateNamespace` | Create a namespace, applying AWS defaults for `dbName`, `kmsKeyId`, and `logExports` |
| `GetNamespace` | Return a namespace by name |
| `ListNamespaces` | Page through the namespaces in the account and Region |
| `UpdateNamespace` | Apply the supplied fields to an existing namespace and return it |
| `DeleteNamespace` | Remove a namespace and return it with `status` `DELETING` |
| `ListTagsForResource` | Return the tags on the namespace named by `resourceArn` |
| `TagResource` | Merge tags into the namespace named by `resourceArn` |
| `UntagResource` | Remove tags by key from the namespace named by `resourceArn` |
<!-- floci:actions:end -->

Namespace state is account and Region scoped and persisted through `StorageFactory`.

## Compatibility Notes

- **Namespaces only.** Workgroups, snapshots, recovery points, usage limits, and endpoint access are not emulated. A namespace has no compute attached and no endpoint to connect to, so the Redshift Data API still rejects `WorkgroupName`.
- **`adminUserPassword` is accepted and never returned**, matching AWS. No secret is created for `manageAdminPassword`.
- **Defaults follow AWS.** `dbName` defaults to `dev`, `kmsKeyId` to `AWS_OWNED_KMS_KEY`, `logExports` to an empty list, and `status` to `AVAILABLE` immediately: there is no `MODIFYING` or `CREATING` phase to poll through.
- **`creationDate` is an ISO-8601 string**, for example `2026-09-11T18:12:55.433Z`, not the
  epoch-seconds number that awsJson1.1 uses by default. `Namespace.creationDate` carries
  `TimestampFormatTrait(ISO_8601)` in the API model, and strict SDKs reject a number here even
  though the AWS CLI accepts one. (Not a model-wide rule: roughly half the model's timestamp
  members carry no format trait and use the epoch default, so check each member when extending
  this service.)
- **`namespaceId` is a generated UUID** and `namespaceArn` is `arn:aws:redshift-serverless:<region>:<account>:namespace/<namespaceId>`.
- **`DeleteNamespace` returns the deleted namespace with `status` `DELETING`** and removes it in the same call, so the next `GetNamespace` returns `ResourceNotFoundException`.
- **`UpdateNamespace` applies only the fields present in the request.** An omitted field keeps its stored value; an explicitly empty `iamRoles` or `logExports` array clears it.
- **Tagging is keyed by `resourceArn`, not by namespace name**, and the namespace is the only taggable Redshift Serverless resource in Floci. `TagResource` merges into the existing tags rather than replacing them, `UntagResource` removes by key, and any ARN that does not name a known namespace in the caller's Region returns `ResourceNotFoundException`. Tags supplied to `CreateNamespace` are readable through `ListTagsForResource` and survive an `UpdateNamespace`.

## AWS-compatible failures

Namespace names, database names, and log export values are validated. Floci returns `ValidationException` for a malformed request, `ConflictException` when the namespace name is already taken, and `ResourceNotFoundException` for an unknown namespace.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not inject provider-side failures that cannot be derived from the request or emulator state.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REDSHIFT_SERVERLESS_ENABLED` | `true` | Enable or disable Redshift Serverless |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws redshift-serverless create-namespace \
  --namespace-name analytics \
  --admin-username admin \
  --admin-user-password Secret123! \
  --db-name dev

aws redshift-serverless get-namespace --namespace-name analytics
aws redshift-serverless list-namespaces
aws redshift-serverless update-namespace --namespace-name analytics --log-exports userlog
aws redshift-serverless delete-namespace --namespace-name analytics
```
