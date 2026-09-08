# AWS Control Tower

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the Control Tower landing-zone and baseline operations needed by local Cloud Launchpad and governance workflows.

## Landing-zone state

The normal runtime starts with no landing zone. `ListLandingZones` returns an empty list until `CreateLandingZone` is called. The test profile can enable `floci.services.controltower.seed-landing-zone` to preserve deterministic seeded fixtures for older Control Tower tests.

Landing zones are isolated by caller account and Region. Create, update, reset, and delete operations create operation identifiers that are recorded per account and Region. Unknown or evicted operation identifiers return `ResourceNotFoundException`; Floci does not fabricate successful operation results for identifiers it never issued.

Landing-zone versions must match the AWS version shape `digit.digit`, with one or more digits on each side of the period. Duplicate create state returns `ConflictException`, and reads or mutations against an unknown landing-zone ARN return `ResourceNotFoundException`.

## Baselines

`ListBaselines` exposes the local baseline catalog, including `ConfigBaseline`, the Control Tower baseline, Identity Center baseline, audit baseline, and log archive baseline entries required by supported workflows.

`EnableBaseline` validates the baseline ARN, version, and target ARN. When Organizations state is available, an OU target must refer to a real OU in the caller's organization. Enabling a baseline that is already enabled for the same target returns `ConflictException`; callers must use `UpdateEnabledBaseline` to change an existing enablement.

Enabled-baseline operations are recorded with AWS operation names such as `ENABLE_BASELINE`, `RESET_ENABLED_BASELINE`, and `UPDATE_ENABLED_BASELINE`. `GetBaselineOperation` returns `ResourceNotFoundException` for an unknown operation identifier.

## Supported operations

| Operation | Method and path | Behavior |
|---|---|---|
| `ListLandingZones` | `POST /list-landingzones` | Lists the caller's landing zone, or an empty list when none exists |
| `GetLandingZone` | `POST /get-landingzone` | Returns the landing zone by ARN |
| `CreateLandingZone` | `POST /create-landingzone` | Creates a landing zone and operation identifier |
| `UpdateLandingZone` | `POST /update-landingzone` | Updates manifest, version, and remediation settings |
| `DeleteLandingZone` | `POST /delete-landingzone` | Deletes the landing zone and records a delete operation |
| `ResetLandingZone` | `POST /reset-landingzone` | Validates the landing zone and records a reset operation |
| `GetLandingZoneOperation` | `POST /get-landingzone-operation` | Reads a previously issued operation |
| `ListLandingZoneOperations` | `POST /list-landingzone-operations` | Lists recorded operations with filtering and pagination |
| `ListBaselines` | `POST /list-baselines` | Lists the supported baseline catalog |
| `ListEnabledBaselines` | `POST /list-enabled-baselines` | Lists enabled baselines with filtering and pagination |
| `GetEnabledBaseline` | `POST /get-enabled-baseline` | Returns an enabled baseline by ARN |
| `EnableBaseline` | `POST /enable-baseline` | Enables a baseline on a supported target |
| `ResetEnabledBaseline` | `POST /reset-enabled-baseline` | Records a reset for an enabled baseline |
| `UpdateEnabledBaseline` | `POST /update-enabled-baseline` | Updates version and parameters |
| `GetBaselineOperation` | `POST /get-baseline-operation` | Reads a previously issued baseline operation |
| `EnableControl` | `POST /enable-control` | Enables a control on a target and returns an enabled-control ARN plus operation ID |
| `ListEnabledControls` | `POST /list-enabled-controls` | Lists enabled controls with target/filter pagination |
| `GetEnabledControl` | `POST /get-enabled-control` | Returns enabled-control details and parameters |
| `UpdateEnabledControl` | `POST /update-enabled-control` | Updates parameters when they differ from the current configuration |
| `ResetEnabledControl` | `POST /reset-enabled-control` | Repairs non-SCP enabled controls and records a reset operation |
| `GetControlOperation` | `POST /get-control-operation` | Reads a previously issued control operation |

## Operation behavior

Floci completes Control Tower operations locally rather than waiting on an external control plane, so successfully accepted operations currently reach terminal `SUCCEEDED` state immediately. The AWS state contract is still preserved for issued operation identifiers: identifiers are scoped, recorded, validated, and missing identifiers fail instead of returning invented success.

The operation ledger keeps the most recent 250 operations per account and Region. An identifier that has been evicted behaves like any other unknown operation and returns `ResourceNotFoundException`.

## Errors and provider-side failures

Deterministic request and state failures use the Control Tower modeled errors such as `ValidationException`, `ConflictException`, and `ResourceNotFoundException`. The AWS service model also includes provider-side failures such as `InternalServerException`, throttling, and service-quota failures for operations where AWS can reject work for environmental reasons. Floci does not synthesize those failures without a local condition that can faithfully cause them.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONTROLTOWER_ENABLED` | `true` | Enable or disable Control Tower |
| `FLOCI_SERVICES_CONTROLTOWER_SEED_LANDING_ZONE` | `false` | Seed a deterministic landing zone for fixture-oriented environments |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_MODE` | *(inherits global)* | Optional storage-mode override |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

See the [AWS Control Tower API Reference](https://docs.aws.amazon.com/controltower/latest/APIReference/Welcome.html).
