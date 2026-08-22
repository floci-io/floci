# Cloud Control API

**Protocol:** JSON 1.1 (`X-Amz-Target: CloudApiService.*`)
**Endpoint:** `POST http://localhost:4566/`

Cloud Control provides a uniform CRUD(L) surface over CloudFormation resource types
(`AWS::S3::Bucket`, `AWS::EC2::Instance`, ...) instead of a bespoke API per service.
Floci's emulator reuses the same resource provisioner CloudFormation stacks call, so a
resource behaves consistently whether it's provisioned by a stack or directly through
Cloud Control.

## Supported Operations

| Operation | Description |
| --- | --- |
| `CreateResource` | Provision a resource of the given `TypeName` from a `DesiredState` JSON document |
| `DeleteResource` | Delete a resource by `TypeName` and `Identifier` |
| `GetResource` | Read a resource's current properties by `TypeName` and `Identifier` |
| `ListResources` | List resources of a `TypeName` |
| `GetResourceRequestStatus` | Poll a `CreateResource`/`DeleteResource` request by its token |

## Behavior

- **Asynchronous create/delete**: `CreateResource` returns an `IN_PROGRESS` `ProgressEvent`
  and a request token immediately; provisioning runs in the background. Poll
  `GetResourceRequestStatus` with the token until the status is `SUCCESS` or `FAILED`.
  `DeleteResource` is synchronous (deletes are fast enough not to need polling) but
  still returns a `ProgressEvent` for API-shape consistency.
- **`ListResources` / `GetResource` type coverage**: the read side lists
  `AWS::S3::Bucket`, `AWS::EC2::VPC`, `AWS::EC2::Subnet`, `AWS::EC2::SecurityGroup`,
  `AWS::EC2::Instance`, `AWS::EC2::LaunchTemplate`, `AWS::IAM::Role`, `AWS::IAM::User`,
  and `AWS::IAM::InstanceProfile`. `CreateResource` accepts any type the underlying
  CloudFormation resource provisioner supports (the same set stacks can provision),
  which is broader than the read side. A resource created through Cloud Control but
  outside the read-side type list is still readable via `GetResource`, from
  create-time state, but will not appear in `ListResources`.
- **Delete needs create-time state**: types whose delete depends on attributes
  captured at create time (currently `AWS::EKS::Nodegroup` and `AWS::IAM::Policy`, plus
  any `Custom::*` / `AWS::CloudFormation::CustomResource`) fail `DeleteResource` with a
  clear error if that state isn't held, rather than reporting `SUCCESS` over a resource
  that's still there.
- **No account context**: Cloud Control requests don't carry an account id; Floci uses
  its default test account (`000000000000`) for all resources.
