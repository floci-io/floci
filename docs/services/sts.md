# STS

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## Supported Actions

| Action | Description |
|---|---|
| `GetCallerIdentity` | Returns the account ID, user ID, and ARN |
| `AssumeRole` | Assume an IAM role, returns temporary credentials |
| `AssumeRoleWithWebIdentity` | Assume a role using a web identity token (OIDC) |
| `AssumeRoleWithSAML` | Assume a role using a SAML assertion |
| `GetSessionToken` | Get temporary credentials for an IAM user |
| `GetFederationToken` | Get temporary credentials for a federated user |
| `DecodeAuthorizationMessage` | Decode an encoded authorization failure message |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STS_ENABLED` | `true` | Enable or disable the service |

## Trust Policy Enforcement

By default `AssumeRole` succeeds for any caller. When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`,
`AssumeRole` evaluates the target role's trust policy (`AssumeRolePolicyDocument`) against the caller
and returns `AccessDenied` if it is not permitted. AWS principal forms are matched — `"*"`, an
account id, an account-root ARN (`arn:aws:iam::<acct>:root`), and exact principal ARNs — and an
explicit `Deny` always wins. Both `Action` and `NotAction` elements are honored when matching
`sts:AssumeRole`. Roles that Floci has no record of stay permissive, so this only affects roles
created through IAM with a real trust policy.

### Known limitations

- **`Condition` blocks are not evaluated.** A trust policy that requires `sts:ExternalId` (the
  confused-deputy guard) is matched on its principal alone, so the role is assumable without passing
  `ExternalId`, and the `ExternalId` request parameter is ignored. This matches moto/LocalStack.
- **Only the trust policy is checked.** Cross-account `AssumeRole` in AWS also requires the caller's
  own identity policy to allow `sts:AssumeRole`; that side is not enforced.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Get caller identity (always works, useful for smoke testing)
aws sts get-caller-identity --endpoint-url $AWS_ENDPOINT_URL

# Assume a role
aws sts assume-role \
  --role-arn arn:aws:iam::000000000000:role/my-role \
  --role-session-name dev-session \
  --endpoint-url $AWS_ENDPOINT_URL

# Get a session token
aws sts get-session-token --endpoint-url $AWS_ENDPOINT_URL
```

`GetCallerIdentity` is commonly used in CI pipelines and integration tests as a quick connectivity check before running more complex tests.

When `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true`, requests signed with the seeded `floci` access key return `arn:aws:iam::000000000000:user/floci-deployer`. Other unknown local credentials continue to return the account root ARN for backward compatibility.
