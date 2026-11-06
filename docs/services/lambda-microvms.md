# Lambda MicroVMs

**Protocol:** REST JSON
**Endpoints:** `http://localhost:4566/2025-09-09/...` (images, MicroVMs) and `http://localhost:4566/2026-04-04/...` (network connectors)

Two AWS service models, not one. `Lambda Microvms` (apiVersion 2025-09-09) carries MicroVM images, versions, builds and MicroVMs. `Lambda Core` (apiVersion 2026-04-30) carries VPC egress network connectors under the `/2026-04-04/` prefix. Both sign as `lambda`, so both are served under Floci's existing lambda service and are enabled by it. Tagging rides the classic `/2017-03-31/tags/` routes.

## Supported Actions

### Images, versions and builds

| Action | Description |
| --- | --- |
| `CreateMicrovmImage` | Create an image from an S3 code artifact; mints version 1.0 and its builds |
| `GetMicrovmImage` | Image summary |
| `ListMicrovmImages` | List images |
| `UpdateMicrovmImage` | Full replace; mints a new version and walks the image to `UPDATED` |
| `DeleteMicrovmImage` | Asynchronous delete, answers `DELETING` |
| `ListMicrovmImageVersions` | List versions of an image |
| `GetMicrovmImageVersion` | Version detail, including the parent image's build spec |
| `UpdateMicrovmImageVersion` | Set a version's status `ACTIVE` / `INACTIVE` |
| `DeleteMicrovmImageVersion` | Asynchronous version delete |
| `ListMicrovmImageBuilds` | Builds for a version — one per Graviton generation |
| `GetMicrovmImageBuild` | Build detail, including the snapshot size breakdown |
| `ListManagedMicrovmImages` | The managed base-image catalog |
| `ListManagedMicrovmImageVersions` | Versions of a managed base image |

### MicroVMs

| Action | Description |
| --- | --- |
| `RunMicrovm` | Launch a MicroVM from an image |
| `GetMicrovm` | MicroVM detail |
| `ListMicrovms` | List MicroVMs — a five-member summary per item |
| `TerminateMicrovm` | Terminate; terminated MicroVMs stay listed |

### Network connectors

| Action | Description |
| --- | --- |
| `CreateNetworkConnector` | Create a VPC egress connector |
| `GetNetworkConnector` | Connector detail, or one version with `:<version>` |
| `ListNetworkConnectors` | List connectors — a six-member summary per item |
| `ListNetworkConnectorVersions` | The versions of a connector, oldest first |
| `UpdateNetworkConnector` | Replace the connector configuration. A change makes the next version |
| `DeleteNetworkConnector` | Asynchronous delete, answers `DELETING`. With `:<version>`, deletes only that version |

## Emulation Behavior

- **Instant convergence:** builds, image state and connector state settle immediately rather than on a timer. Where that would produce an inconsistent body it is handled explicitly — a create or update reports the transitional state and does **not** name the version it is minting as active, because a client reading otherwise would launch a MicroVM off a version that does not exist yet.
- **Per-operation projections:** an image is returned in four different shapes and a connector in four. Create and Update carry full detail, Get is a smaller summary, and only the list summaries carry `Type`. Absent members are absent rather than null — a connector that has never been updated has no `LastUpdateStatus` at all.
- **Members the model marks optional but the service enforces:** a connector requires `ClientToken`, `OperatorRole`, `NetworkProtocol` and `AssociatedComputeResourceTypes`. A client written from the Smithy model alone receives four validation errors in turn.
- **ARN or bare identifier:** images, MicroVMs and connectors resolve by either in URI paths.
- **Passing a connector:** when IAM enforcement is on, `CreateMicrovmImage`, `UpdateMicrovmImage` and `RunMicrovm` need `lambda:PassNetworkConnector` on each connector the request names, and on the Lambda-managed `INTERNET_EGRESS` connector, or `HTTP_INGRESS` for ingress, when it names none, as on AWS (see [IAM](iam.md)).
- **Connector versions:** a connector starts at version 1. As on AWS, an update that changes the subnets, the security groups, the network protocol or the operator role makes the next version, and one that only lists the subnets in another order changes nothing. A connector also resolves by its name, and `:<version>` after an id, a name or an ARN names one version. The latest version cannot be deleted on its own, and `AssociatedComputeResourceTypes` cannot change. Errors use the AWS wording.
- **Builds per generation:** each version is built once per Graviton generation the service targets, so a version has more than one build.
- **Not emulated:** auth tokens, the per-VM endpoint, idle and suspend timers, and suspend/resume. Those belong to a full-fidelity MicroVMs emulator rather than to CloudFormation provisioning; see [m80](https://github.com/INTENTIUS/m80).

## CloudFormation

`AWS::Lambda::MicrovmImage` and `AWS::Lambda::NetworkConnector` provision and delete through the resource registry, so a template declaring either is handled rather than skipped. A connector returns `LatestVersion` and `LatestVersionArn` through `Fn::GetAtt`.

## Verification

Response shapes come from the vendored AWS service models and, where those are silent or disagree with the service, from responses recorded against live AWS. The [m80 conformance suite](https://github.com/INTENTIUS/m80) replays those recordings against an endpoint: 26 checks across the image, MicroVM and connector lifecycles, 0 failures.
