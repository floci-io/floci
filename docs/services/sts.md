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

- **`Condition` blocks are not evaluated for `AssumeRole`.** A trust policy that requires
  `sts:ExternalId` (the confused-deputy guard) is matched on its principal alone, so the role is
  assumable without passing `ExternalId`, and the `ExternalId` request parameter is ignored. This
  matches moto/LocalStack. Conditions *are* evaluated on the `AssumeRoleWithWebIdentity` path (see below).
- **Only the trust policy is checked.** Cross-account `AssumeRole` in AWS also requires the caller's
  own identity policy to allow `sts:AssumeRole`; that side is not enforced.

## Web Identity Validation (IRSA)

`AssumeRoleWithWebIdentity` validates tokens minted by an OIDC issuer Floci hosts - currently an EKS cluster's IRSA provider. This needs no configuration flag and is independent of `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED`.

When the token's `iss` names a known Floci issuer, all of the following are enforced:

- the RS256 signature, against the issuer's public key
- `iss` matches that issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy — `Principal.Federated` plus the `Condition` block, comparing `<oidcProvider>:sub` and `<oidcProvider>:aud` with exact, **case-sensitive** equality (`StringEquals`, `StringNotEquals`, `StringLike`, and `StringNotLike` are supported)

The response carries the token's real claims in `SubjectFromWebIdentityToken`, `Provider`, and `Audience`. A bad token returns `InvalidIdentityToken` (400); a trust policy that does not permit the subject returns `AccessDenied` (403).

Tokens from an issuer Floci does not host are treated as opaque and accepted, since Floci cannot verify a third-party provider's signature. See [EKS](eks.md) for the full IRSA walkthrough and the token-minting endpoint.

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
