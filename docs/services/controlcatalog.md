# AWS Control Catalog

Floci emulates the AWS Control Catalog REST JSON surface used by governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `GetControl` | Returns catalog metadata for a supported control (`POST /get-control`) |
| `ListControls` | Lists global controls with filtering and pagination (`POST /list-controls`) |
<!-- floci:actions:end -->

`GetControl` validates the control ARN and returns `ResourceNotFoundException` for a well-formed control ARN that is not in the local catalog. The local catalog includes the preventive RCP controls and legacy Control Tower controls required by Cloud Launchpad Enterprise governance recovery.

The implementation distinguishes service control policies, resource control policies, and AWS Config rule controls because callers use `Implementation.Type` to choose valid recovery operations. In particular, AWS Control Tower does not support `ResetEnabledControl` for controls implemented with SCPs.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONTROLCATALOG_ENABLED` | `true` | Enable or disable Control Catalog |

See the [AWS Control Catalog API Reference](https://docs.aws.amazon.com/controlcatalog/latest/APIReference/Welcome.html).
