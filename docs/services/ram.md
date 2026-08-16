# AWS RAM

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`
**Signing name:** `ram`

Floci implements the AWS Resource Access Manager calls AWS Landing Zone Accelerator makes
while bootstrapping shared Transit Gateway attachments and other cross-account resources:
the organization-sharing opt-in, resource-share CRUD, association management, principal
listing, and tagging. Sharing is modeled as a flat in-memory store, not full RAM semantics —
see Current Scope below for what's simplified.

## Supported Operations

| Operation | Method and path | Description |
|---|---|---|
| `EnableSharingWithAwsOrganization` | `POST /enablesharingwithawsorganization` | Enable resource sharing within the organization; returns `{"returnValue": true}`. Idempotent, no disable counterpart, matching real AWS. |
| `CreateResourceShare` | `POST /createresourceshare` | Creates a resource share with the given name, principals, and resource ARNs. |
| `GetResourceShares` | `POST /getresourceshares` | Lists resource shares visible to the caller (`SELF` owned, or `OTHER-ACCOUNTS` shared in). |
| `UpdateResourceShare` | `POST /updateresourceshare` | Renames a share and/or toggles `allowExternalPrincipals`. |
| `DeleteResourceShare` | `DELETE /deleteresourceshare` | Marks a share `DELETED` (soft delete, matching real AWS's async-then-terminal status). |
| `AssociateResourceShare` | `POST /associateresourceshare` | Adds resource ARNs and/or principals to an existing share. |
| `DisassociateResourceShare` | `POST /disassociateresourceshare` | Removes resource ARNs and/or principals from a share. |
| `ListPrincipals` | `POST /listprincipals` | Lists principals associated with visible shares. |
| `ListResources` | `POST /listresources` | Lists shared resource ARNs, with the RAM resource type derived from the ARN (`ec2:TransitGateway`, etc). |
| `TagResource` | `POST /tagresource` | Adds/overwrites tags on a resource share. |
| `UntagResource` | `POST /untagresource` | Removes tags by key from a resource share. |
| `GetResourceShareInvitations` | `POST /getresourceshareinvitations` | Always returns an empty list — organization sharing auto-accepts, so invitations never exist here. |

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_RAM_ENABLED` | `true` | Enables the service |

## Example

```bash
aws --endpoint-url http://localhost:4566 ram enable-sharing-with-aws-organization

aws --endpoint-url http://localhost:4566 ram create-resource-share \
  --name my-share \
  --resource-arns arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0abc \
  --principals arn:aws:organizations::000000000000:ou/o-abc/ou-infra

aws --endpoint-url http://localhost:4566 ram delete-resource-share \
  --resource-share-arn arn:aws:ram:us-east-1:000000000000:resource-share/...
```

## Current Scope

- Visibility is simplified: `OTHER-ACCOUNTS` shows every non-owned share regardless of
  principal targeting, since launched-container credentials all resolve to the same default
  account and a principal-based check would hide shares from consumers that should see them.
  LZA's `Custom::GetResourceShare` Lambda filters client-side by `owningAccountId` + `name`
  anyway, so this doesn't affect that flow.
- `AssociateResourceShare`/`DisassociateResourceShare` responses synthesize one
  `resourceShareAssociation` row per requested ARN/principal rather than tracking real
  per-association status transitions (e.g. no `ASSOCIATING`/`DISASSOCIATING` intermediate
  state — everything completes synchronously).
- `TagResource`/`UntagResource` only support tagging by `resourceShareArn`; tagging an
  individual shared resource via `resourceArn` is not modeled (RAM's `TagResource` accepts
  either, but LZA only tags shares).
- Permission-related operations are not implemented: `CreatePermission`,
  `AssociateResourceSharePermission`, `ListPermissions`, and the rest of the permission-version
  family. Shares created here always use RAM's default managed permission implicitly — there
  is no explicit permission model to associate or version.
- `AcceptResourceShareInvitation` / `RejectResourceShareInvitation` are not implemented —
  moot under organization sharing, which never produces invitations to begin with.
