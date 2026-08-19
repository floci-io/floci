# GuardDuty

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci implements the GuardDuty detector management lifecycle and organization-configuration
readback for local SDK, CLI, and Terraform workflows. Detectors are isolated by account and
region and use the configured Floci storage mode.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `CreateDetector` | `POST /detector` | Create the account's detector (one per account and region) |
| `GetDetector` | `GET /detector/{detectorId}` | Return detector status, frequency, features, and tags |
| `UpdateDetector` | `POST /detector/{detectorId}` | Update status, frequency, and features |
| `DeleteDetector` | `DELETE /detector/{detectorId}` | Delete the detector |
| `ListDetectors` | `GET /detector` | List detector IDs with pagination |
| `DescribeOrganizationConfiguration` | `GET /detector/{detectorId}/admin` | Return organization auto-enablement configuration |
| `UpdateOrganizationConfiguration` | `POST /detector/{detectorId}/admin` | Update organization auto-enablement configuration |
| `EnableOrganizationAdminAccount` | `POST /admin/enable` | Designate the delegated administrator account |
| `DisableOrganizationAdminAccount` | `POST /admin/disable` | Remove the delegated administrator account |
| `ListOrganizationAdminAccounts` | `GET /admin` | List the delegated administrator account |
| `TagResource` | `POST /tags/{resourceArn}` | Add tags to a detector |
| `UntagResource` | `DELETE /tags/{resourceArn}` | Remove tags from a detector |
| `ListTagsForResource` | `GET /tags/{resourceArn}` | List detector tags |

Feature lists and each feature's `additionalConfiguration` list are returned in the order
they were submitted, so Terraform's ordered list blocks re-plan cleanly. A missing detector
is reported as `BadRequestException` with the exact message the Terraform AWS provider
matches for not-found detection, mirroring AWS.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_GUARDDUTY_ENABLED` | `true` | Enable or disable GuardDuty |
| `FLOCI_STORAGE_SERVICES_GUARDDUTY_MODE` | *(inherits global)* | Optional GuardDuty storage-mode override |
| `FLOCI_STORAGE_SERVICES_GUARDDUTY_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

Unless a GuardDuty-specific override is set, detector state follows the global
`FLOCI_STORAGE_MODE` setting. Persistent, hybrid, and write-ahead-log modes restore
detectors across restarts.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

DETECTOR_ID=$(aws guardduty create-detector \
  --enable \
  --finding-publishing-frequency SIX_HOURS \
  --tags env=local \
  --query DetectorId --output text)

aws guardduty get-detector --detector-id "$DETECTOR_ID"
aws guardduty list-detectors

aws guardduty update-organization-configuration \
  --detector-id "$DETECTOR_ID" \
  --auto-enable-organization-members ALL
aws guardduty describe-organization-configuration --detector-id "$DETECTOR_ID"

aws guardduty delete-detector --detector-id "$DETECTOR_ID"
```

## Current Scope

- Detector status, finding-publishing frequency, features (including
  `additionalConfiguration` sub-features), tags, and timestamps are modeled.
- Organization semantics are readback-only. Floci has no Organizations service, so
  `adminAccountId` membership is not validated, delegated-administrator permissions are not
  enforced on the organization endpoints, and auto-enablement is never fanned out to member
  accounts. Organization configuration is stored per calling account and echoed back as
  submitted — sufficient for Terraform's `aws_guardduty_organization_configuration`,
  `aws_guardduty_organization_configuration_feature`, and
  `aws_guardduty_organization_admin_account` resources in a single-account workflow.
- No findings are generated: detection, malware scans, and the findings APIs
  (`ListFindings`, `GetFindings`, filters, and publishing destinations) are not implemented.
- Member, invitation, IP-set, threat-intel, and coverage APIs are not implemented.
- The deprecated `dataSources` request/response structures are not modeled; the Terraform
  provider treats their absence as "not configured".
