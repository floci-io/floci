# Amazon Cognito Identity

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSCognitoIdentityService.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `cognito-identity`)

Cognito Identity is the federated identity pool API, distinct from the Cognito user pool
API (`cognito-idp`, documented in [Cognito](cognito.md)). Floci emulates the identity pool
management plane; the identity and credential-vending data plane is not emulated.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateIdentityPool` | Create a pool; returns the whole pool including tags |
| `DescribeIdentityPool` | Read a pool by id |
| `UpdateIdentityPool` | Replace a pool's configuration wholesale |
| `DeleteIdentityPool` | Delete a pool |
| `ListIdentityPools` | Page through pools, ordered by pool id |
| `SetIdentityPoolRoles` | Set the authenticated/unauthenticated roles and role mappings |
| `GetIdentityPoolRoles` | Read the roles and role mappings |
| `TagResource` | Add tags to a pool by ARN |
| `UntagResource` | Remove tags from a pool by ARN |
| `ListTagsForResource` | List a pool's tags |
| `SetPrincipalTagAttributeMap` | Map principal tags to user attributes for one provider |
| `GetPrincipalTagAttributeMap` | Read a provider's principal tag attribute map |
<!-- floci:actions:end -->

Pool ids use the AWS `<region>:<uuid>` format (`us-east-1:0f4a...`), and pool ARNs the
documented `arn:aws:cognito-identity:<region>:<account>:identitypool/<poolId>` form, so
callers that parse either get the same structure they would from AWS.

`UpdateIdentityPool` takes the full `IdentityPool` shape and replaces the stored pool with
it: a member the caller omits is reset to its default rather than carried over, matching
the AWS behaviour ("if you don't provide a value for a parameter, Amazon Cognito sets it to
its default value"). Roles, role mappings and principal tag attribute maps are set by their
own operations, are not part of the `IdentityPool` shape, and survive the replace.

`TagResource` writes into the same store as `IdentityPoolTags`, so a tag added by ARN is
visible on the next `DescribeIdentityPool` and vice versa.

`GetIdentityPoolRoles` returns `Roles` and `RoleMappings` as empty maps before
`SetIdentityPoolRoles` has run, never as null, so a provider read that dereferences them
does not crash.

## Not implemented

The identity and credential-vending operations are absent and return
`UnknownOperationException`:

`GetId`, `GetCredentialsForIdentity`, `GetOpenIdToken`, `GetOpenIdTokenForDeveloperIdentity`,
`DescribeIdentity`, `ListIdentities`, `DeleteIdentities`, `UnlinkIdentity`,
`UnlinkDeveloperIdentity`, `LookupDeveloperIdentity`, `MergeDeveloperIdentities`.

These mint real STS session credentials and signed OIDC tokens against a pool's configured
providers. An identity id handed out by `GetId` is only usable as input to
`GetCredentialsForIdentity` or `GetOpenIdToken`, so serving it alone would move the failure
one call later instead of removing it.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_COGNITOIDENTITY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws cognito-identity create-identity-pool \
  --identity-pool-name my-pool \
  --allow-unauthenticated-identities

aws cognito-identity set-identity-pool-roles \
  --identity-pool-id us-east-1:... \
  --roles authenticated=arn:aws:iam::000000000000:role/authenticated

aws cognito-identity describe-identity-pool --identity-pool-id us-east-1:...

aws cognito-identity delete-identity-pool --identity-pool-id us-east-1:...
```
