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
| `GetNetworkConnector` | Connector detail |
| `ListNetworkConnectors` | List connectors — a six-member summary per item |
| `UpdateNetworkConnector` | Replace the connector configuration |
| `DeleteNetworkConnector` | Asynchronous delete, answers `DELETING` |

## Emulation Behavior

- **Instant convergence:** builds, image state and connector state settle immediately rather than on a timer. Where that would produce an inconsistent body it is handled explicitly — a create or update reports the transitional state and does **not** name the version it is minting as active, because a client reading otherwise would launch a MicroVM off a version that does not exist yet.
- **Per-operation projections:** an image is returned in four different shapes and a connector in four. Create and Update carry full detail, Get is a smaller summary, and only the list summaries carry `Type`. Absent members are absent rather than null — a connector that has never been updated has no `LastUpdateStatus` at all.
- **Members the model marks optional but the service enforces:** a connector requires `ClientToken`, `OperatorRole`, `NetworkProtocol` and `AssociatedComputeResourceTypes`. A client written from the Smithy model alone receives four validation errors in turn.
- **ARN or bare identifier:** images, MicroVMs and connectors resolve by either in URI paths.
- **Builds per generation:** each version is built once per Graviton generation the service targets, so a version has more than one build.
- **Not emulated:** auth tokens, the per-VM endpoint, idle and suspend timers, and suspend/resume. Those belong to a full-fidelity MicroVMs emulator rather than to CloudFormation provisioning; see [m80](https://github.com/INTENTIUS/m80).

## CloudFormation

`AWS::Lambda::MicrovmImage` and `AWS::Lambda::NetworkConnector` provision and delete through the resource registry, so a template declaring either is handled rather than skipped.

## Verification

Response shapes come from the vendored AWS service models and, where those are silent or disagree with the service, from responses recorded against live AWS. The [m80 conformance suite](https://github.com/INTENTIUS/m80) replays those recordings against an endpoint: 26 checks across the image, MicroVM and connector lifecycles, 0 failures.
