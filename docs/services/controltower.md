# AWS Control Tower

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the minimal AWS Control Tower surface required to unblock Landing Zone Accelerator's (LZA) `AWSAccelerator-Pipeline` **Prepare** stage when the universal config sets `controlTower.enable: true`. It is not a general-purpose Control Tower emulation; supported operations are listed below.

## Pre-seed / reconciliation-sink model

Floci pre-seeds exactly **one active landing zone** per account+region the first time it is read (`ListLandingZones`, `GetLandingZone`, etc.). This matters because LZA's `setup-landing-zone` module treats an *empty* `ListLandingZones` result as its create-landing-zone trigger — an empty list is not a no-op, it cascades into an entire prerequisite chain (IAM roles, KMS keys, `Organization.EnableAllFeatures`, account moves) that Floci does not implement. By never returning an empty list on a fresh account+region, that branch stays unreachable. `CreateLandingZone` remains available for an account+region with no stored landing zone, while `DeleteLandingZone` removes the stored instance after validating its identifier.

Because Floci cannot predict every config value LZA's `landingZoneUpdateOrResetRequired` check will compare against the seed, `UpdateLandingZone` is implemented as a **reconciliation sink**: it accepts whatever manifest LZA sends, stores it verbatim, and reports the operation as `SUCCEEDED`. Any mismatch between the seed and LZA's computed configuration self-heals on the first `UpdateLandingZone` call of a pipeline run rather than failing.

The landing zone `version` and `latestAvailableVersion` are pinned to `"4.0"`. LZA's `validateLandingZoneVersion` throws whenever an update or reset is required and `latestAvailableVersion` doesn't match the configured version, so this pin is load-bearing, not cosmetic.

The seeded manifest always includes a `securityRoles` object. LZA's `makeManifestDocument` (update branch) dereferences `existingManifest.securityRoles.accountId` with no optional chaining — an LZ without `securityRoles` crashes the first `UpdateLandingZone` call with a `TypeError`.

## IdentityCenter auto-enable

`register-organizational-unit` throws when the landing zone's `enableIdentityCenterAccess` is true (universal config sets `security.enableIdentityCenterAccess: true`) but no `IdentityCenterBaseline` is enabled. Rather than implementing `sso-admin`/`identitystore`, Floci derives a synthetic `IdentityCenterBaseline` entry in `ListEnabledBaselines` at read time whenever the current landing-zone manifest has `accessManagement.enabled: true` — mirroring what real Control Tower does when IAM Identity Center access is turned on. Because it's derived at read time rather than materialized once, it tracks manifest changes (e.g. `UpdateLandingZone` flipping `accessManagement.enabled` to `false`) with no dual-write.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `ListLandingZones` | `POST /list-landingzones` | Always returns the single seeded/reconciled landing zone |
| `GetLandingZone` | `POST /get-landingzone` | Returns the stored landing zone (arn, version, status, drift status, manifest, remediation types); requires `landingZoneIdentifier`, and an ARN that isn't the stored one returns `ResourceNotFoundException` |
| `CreateLandingZone` | `POST /create-landingzone` | Creates a landing zone from a manifest and version; returns its ARN and operation identifier |
| `UpdateLandingZone` | `POST /update-landingzone` | Reconciliation sink — stores the supplied manifest/version/remediation types and returns an operation id; requires a `landingZoneIdentifier` matching the stored landing zone |
| `DeleteLandingZone` | `POST /delete-landingzone` | Removes the stored landing zone and returns a delete operation id |
| `ResetLandingZone` | `POST /reset-landingzone` | Validates the landing zone identifier and returns a reset operation id |
| `GetLandingZoneOperation` | `POST /get-landingzone-operation` | Reports `SUCCEEDED` for any operation id, including ones not in the in-memory ledger (restart-safe) |
| `ListLandingZoneOperations` | `POST /list-landingzone-operations` | Lists the caller's landing-zone operations newest-first, with `types`/`statuses` filters and `maxResults`/`nextToken` pagination |
| `ListBaselines` | `POST /list-baselines` | Static 4-entry catalog: `AWSControlTowerBaseline`, `IdentityCenterBaseline`, `AuditBaseline`, `LogArchiveBaseline`, with region-stamped arns |
| `ListEnabledBaselines` | `POST /list-enabled-baselines` | Supports packet-defined baseline/target/status filters and `maxResults`/`nextToken` pagination. Child resources are not materialized; `includeChildren` returns configured parent resources only. |
| `GetEnabledBaseline` | `POST /get-enabled-baseline` | Returns an enabled baseline by ARN, including status, parameters, and optional parent/drift details; unknown ARNs return `ResourceNotFoundException` |
| `EnableBaseline` | `POST /enable-baseline` | Stores an enabled baseline keyed by target (OU or landing zone); re-enabling a target replaces rather than duplicates |
| `ResetEnabledBaseline` | `POST /reset-enabled-baseline` | Re-applies an enabled baseline by ARN; updates the `lastOperationIdentifier` and returns an operation id |
| `UpdateEnabledBaseline` | `POST /update-enabled-baseline` | Updates an enabled baseline's version and optional parameters; returns an operation id |
| `GetBaselineOperation` | `POST /get-baseline-operation` | Reports `SUCCEEDED` for any operation id, including ones not in the ledger |

Every landing-zone URI spells "landingzone" as one word — `/list-landingzones`, `/get-landingzone`, `/create-landingzone`, `/update-landingzone`, `/delete-landingzone`, `/reset-landingzone`, `/get-landingzone-operation`, `/list-landingzone-operations` — matching the AWS Control Tower service model.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONTROLTOWER_ENABLED` | `true` | Enable or disable Control Tower |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_MODE` | *(inherits global)* | Optional Control Tower storage-mode override |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

Landing zones and enabled baselines are isolated by account (via the account-aware storage wrapper) and by region (landing zone store key is the region; enabled-baseline store key is `region::targetIdentifier`).

The operation ledger that backs `GetLandingZoneOperation`, `ListLandingZoneOperations`, and `GetBaselineOperation` is in-memory and scoped the same way, one ledger per account+region. A caller only ever sees operations issued under its own account and region; an identifier from another scope is treated as unknown and answers with the default operation type and `SUCCEEDED`, exactly as an identifier issued before a restart does. Each ledger keeps the most recent 250 operations and evicts the oldest, so a long-running emulator does not grow it without bound.

## Current Scope

This service exists to unblock LZA's Prepare stage, not to model Control Tower generally. Two kinds of gap follow from that, and they are different things.

**Answered, but without the underlying effect.** These operations validate their input and return a well-formed success response, so a caller that reaches them is not blocked — but nothing behind Control Tower's API actually changes:

- `ResetLandingZone` — validates the landing zone identifier and returns a `RESET` operation id, leaving the stored manifest untouched.
- `ResetEnabledBaseline` — validates the enabled-baseline ARN, refreshes its `lastOperationIdentifier`, and returns an operation id. It is unreachable on the Prepare-stage path (`reregisterOu` and enroll-accounts trigger it) and exists for later flows.

**Not implemented at all.** Nothing in Floci serves these, and nothing on the Prepare-stage path asks for them:

- Any IAM/KMS/Organizations create-path prerequisite (`CreateRole`, `CreateKey`, `EnableAllFeatures`, etc.) — reachable only from the create-landing-zone branch that the pre-seed keeps unreachable.
- `sso-admin`/`identitystore` — sidestepped by the synthetic IdentityCenter auto-enable derivation described above.
- Org-wide CloudTrail and StackSets — manifest metadata only on the Prepare-stage path; not modeled as separate service calls.
