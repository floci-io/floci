# AWS RAM

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`
**Signing name:** `ram`

Floci implements the AWS Resource Access Manager organization-sharing opt-in, which
organization tooling (for example AWS Landing Zone Accelerator) calls while preparing a
management account. The flag flips and stays enabled, matching the real service where the
operation is idempotent and has no disable counterpart. Resource shares are not modeled yet.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `EnableSharingWithAwsOrganization` | `POST /enablesharingwithawsorganization` | Enable resource sharing within the organization; returns `{"returnValue": true}` |

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_RAM_ENABLED` | `true` | Enables the service |

## Example

```bash
aws --endpoint-url http://localhost:4566 ram enable-sharing-with-aws-organization
```

## Current Scope

- Resource-share APIs are not implemented: `CreateResourceShare`, `GetResourceShares`,
  `AssociateResourceShare`, and the remaining share, principal, and permission operations.
