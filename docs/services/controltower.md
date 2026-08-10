# AWS Control Tower

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

Floci implements the minimal AWS Control Tower surface required to unblock Landing Zone Accelerator's (LZA) `AWSAccelerator-Pipeline` **Prepare** stage when the universal config sets `controlTower.enable: true`. It is not a general-purpose Control Tower emulation — the create-landing-zone path is deliberately not implemented (see `issues/controltower/01-gap-analysis.md`).

## Pre-seed / reconciliation-sink model

Rather than modeling Control Tower's `CreateLandingZone` workflow, Floci pre-seeds exactly **one active landing zone** per account+region the first time it is read (`ListLandingZones`, `GetLandingZone`, etc.). This matters because LZA's `setup-landing-zone` module treats an *empty* `ListLandingZones` result as its create-landing-zone trigger — an empty list is not a no-op, it cascades into an entire prerequisite chain (IAM roles, KMS keys, `Organization.EnableAllFeatures`, account moves) that Floci does not implement. By never returning an empty list, that branch stays unreachable.

Because Floci cannot predict every config value LZA's `landingZoneUpdateOrResetRequired` check will compare against the seed, `UpdateLandingZone` is implemented as a **reconciliation sink**: it accepts whatever manifest LZA sends, stores it verbatim, and reports the operation as `SUCCEEDED`. Any mismatch between the seed and LZA's computed configuration self-heals on the first `UpdateLandingZone` call of a pipeline run rather than failing.

The landing zone `version` and `latestAvailableVersion` are pinned to `"4.0"`. LZA's `validateLandingZoneVersion` throws whenever an update or reset is required and `latestAvailableVersion` doesn't match the configured version, so this pin is load-bearing, not cosmetic.

The seeded manifest always includes a `securityRoles` object. LZA's `makeManifestDocument` (update branch) dereferences `existingManifest.securityRoles.accountId` with no optional chaining — an LZ without `securityRoles` crashes the first `UpdateLandingZone` call with a `TypeError`.

## IdentityCenter auto-enable

`register-organizational-unit` throws when the landing zone's `enableIdentityCenterAccess` is true (universal config sets `security.enableIdentityCenterAccess: true`) but no `IdentityCenterBaseline` is enabled. Rather than implementing `sso-admin`/`identitystore`, Floci derives a synthetic `IdentityCenterBaseline` entry in `ListEnabledBaselines` at read time whenever the current landing-zone manifest has `accessManagement.enabled: true` — mirroring what real Control Tower does when IAM Identity Center access is turned on. Because it's derived at read time rather than materialized once, it tracks manifest changes (e.g. `UpdateLandingZone` flipping `accessManagement.enabled` to `false`) with no dual-write.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `ListLandingZones` | `POST /list-landingzones` | Always returns the single seeded/reconciled landing zone |
| `GetLandingZone` | `POST /get-landingzone` | Returns the stored landing zone (arn, version, status, drift status, manifest, remediation types) |
| `UpdateLandingZone` | `POST /update-landingzone` | Reconciliation sink — stores the supplied manifest/version/remediation types and returns an operation id |
| `GetLandingZoneOperation` | `POST /get-landingzone-operation` | Reports `SUCCEEDED` for any operation id, including ones not in the in-memory ledger (restart-safe) |
| `ListBaselines` | `POST /list-baselines` | Static 4-entry catalog: `AWSControlTowerBaseline`, `IdentityCenterBaseline`, `AuditBaseline`, `LogArchiveBaseline`, with region-stamped arns |
| `ListEnabledBaselines` | `POST /list-enabled-baselines` | Stored enabled baselines plus the synthetic IdentityCenter auto-enable entry (see above) |
| `EnableBaseline` | `POST /enable-baseline` | Stores an enabled baseline keyed by target (OU or landing zone); re-enabling a target replaces rather than duplicates |
| `GetBaselineOperation` | `POST /get-baseline-operation` | Reports `SUCCEEDED` for any operation id, including ones not in the ledger |

Note the landing-zone URIs spell "landingzone" as one word (`/list-landingzones`, `/get-landingzone`, `/update-landingzone`, `/get-landingzone-operation`) — this matches the exact literals LZA's SDK sends.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONTROLTOWER_ENABLED` | `true` | Enable or disable Control Tower |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_MODE` | *(inherits global)* | Optional Control Tower storage-mode override |
| `FLOCI_STORAGE_SERVICES_CONTROLTOWER_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

Landing zones and enabled baselines are isolated by account (via the account-aware storage wrapper) and by region (landing zone store key is the region; enabled-baseline store key is `region::targetIdentifier`).

## Current Scope

This service exists to unblock LZA's Prepare stage, not to model Control Tower generally. The following are deliberately **not implemented**, per the gap analysis:

- `CreateLandingZone`, `ResetLandingZone`, `DeleteLandingZone` — the create-landing-zone path is unreachable because `ListLandingZones` never returns empty.
- `UpdateEnabledBaseline`, `ResetEnabledBaseline` — unreachable in the Prepare-stage flow (version-mismatch and `reregisterOu`/enroll-accounts triggers, respectively). If enroll-accounts flows are exercised later, both are small additions to the existing operation-sink pattern.
- Any IAM/KMS/Organizations create-path prerequisite (`CreateRole`, `CreateKey`, `EnableAllFeatures`, etc.) — dead code on the pre-seed path.
- `sso-admin`/`identitystore` — sidestepped by the synthetic IdentityCenter auto-enable derivation described above.
- Org-wide CloudTrail and StackSets — manifest metadata only on the Prepare-stage path; not modeled as separate service calls.
