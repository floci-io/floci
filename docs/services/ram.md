# AWS RAM

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`
**Signing name:** `ram`

Floci implements the AWS Resource Access Manager calls AWS Landing Zone Accelerator makes
while bootstrapping shared Transit Gateway attachments and other cross-account resources:
the organization-sharing opt-in, resource-share CRUD, association management, principal
listing, and tagging. Sharing is modeled as a flat in-memory store, not full RAM semantics —
see Current Scope below for what's simplified.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `EnableSharingWithAwsOrganization` | Enables resource sharing within the organization; returns `{"returnValue": true}`. Idempotent, no disable counterpart, matching real AWS (`POST /enablesharingwithawsorganization`) |
| `CreateResourceShare` | Creates a resource share with the given name, principals, and resource ARNs (`POST /createresourceshare`) |
| `GetResourceShares` | Lists resource shares visible to the caller, `SELF` owned or `OTHER-ACCOUNTS` shared in (`POST /getresourceshares`) |
| `DeleteResourceShare` | Marks a share `DELETED`, a soft delete matching real AWS's async-then-terminal status (`DELETE /deleteresourceshare`) |
| `UpdateResourceShare` | Renames a share and/or toggles `allowExternalPrincipals` (`POST /updateresourceshare`) |
| `AssociateResourceShare` | Adds resource ARNs and/or principals to an existing share (`POST /associateresourceshare`) |
| `DisassociateResourceShare` | Removes resource ARNs and/or principals from a share (`POST /disassociateresourceshare`) |
| `ListPrincipals` | Lists principals associated with visible shares (`POST /listprincipals`) |
| `TagResource` | Adds/overwrites tags on a resource share (`POST /tagresource`) |
| `UntagResource` | Removes tags by key from a resource share (`POST /untagresource`) |
| `GetResourceShareInvitations` | Always returns an empty list — organization sharing auto-accepts, so invitations never exist here (`POST /getresourceshareinvitations`) |
| `ListResources` | Lists shared resource ARNs, with the RAM resource type derived from the ARN, e.g. `ec2:TransitGateway` (`POST /listresources`) |
<!-- floci:actions:end -->

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
- Mutations are owner-only: a caller that is not the share's owning account gets
  `UnknownResourceException`, the same error an unknown ARN gets, since AWS resolves a share
  ARN within the caller's own account. Visibility above is unaffected.
- `resourceOwner` is required on `GetResourceShares`, `ListPrincipals` and `ListResources`: a
  missing or unmodelled value is rejected with `InvalidParameterException` rather than defaulted
  to `SELF`.
- `GetResourceShares` applies the `name`, `resourceShareArns` and `resourceShareStatus` filters;
  `tagFilters`, `permissionArn`, `permissionVersion` and pagination (`nextToken`/`maxResults`)
  are accepted but ignored — every matching share is returned in one page.
- Operations outside the table above are not routed. RAM's paths are matched literally, so an
  unimplemented operation falls through to S3's `/{bucket}` route and comes back as an S3
  `NoSuchBucket` XML body where the SDK expects JSON — not a modeled `UnknownOperationException`.
  If a client reports an XML parse error against a RAM call, that is the cause, not credentials.
  `GetResourceShareAssociations` is the one most likely to be hit: Terraform's
  `aws_ram_resource_association` and `aws_ram_principal_association` read it on every refresh.
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
