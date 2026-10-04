# STS

**Protocol:** Query (XML), `POST http://localhost:4566/` with `Action=` parameter

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

`AssumeRole` and `AssumeRoleWithWebIdentity` require the target role to exist in IAM, returning `AccessDenied` (403)
if it does not.

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, `AssumeRole` also evaluates the target role's trust policy
(`AssumeRolePolicyDocument`) against the caller and returns `AccessDenied` if it is not permitted. AWS principal
forms are matched: `"*"`, an account id, an account-root ARN (`arn:aws:iam::<acct>:root`), and exact principal ARNs;
and an explicit `Deny` always wins. Both `Action` and `NotAction` elements are honored when matching `sts:AssumeRole`.

The caller is the account that owns the request's credentials, whether they sign the
`Authorization` header or a presigned query string: an IAM user's or a role session's access key
belongs to its own account, not to the default one. A role session matches a `Principal` that
names its session ARN or its role's ARN, path included.

`Condition` blocks are evaluated with the same operators as identity and resource policies. The
request context holds `sts:RoleSessionName`, `sts:ExternalId` when the caller passes `ExternalId`,
`aws:PrincipalArn` (for a caller using assumed-role credentials, the role's ARN with its path, as on AWS),
`aws:PrincipalAccount`, `aws:ResourceAccount`, `aws:RequestedRegion` and
`aws:PrincipalIsAWSService`. A trust policy that requires `sts:ExternalId`, the confused-deputy
guard, refuses a call that omits it or passes another value, and a conditional `Deny` applies only
when its condition matches.

### Known limitations

- **Session tags and source identity are not modelled.** `aws:RequestTag/*`, `aws:TagKeys`,
  `sts:TransitiveTagKeys` and `sts:SourceIdentity` are not in the request context, so a condition
  on them does not match, and `sts:TagSession` and `sts:SetSourceIdentity` are not checked.
- **The caller's own permission is checked against `*`.** The caller's identity policy must allow
  `sts:AssumeRole`, but it is evaluated for resource `*` rather than the role's ARN, so a policy
  that names the role in `Resource` does not grant it.

## Web Identity Validation (IRSA)

`AssumeRoleWithWebIdentity` validates tokens minted by an OIDC issuer Floci hosts - currently an EKS cluster's IRSA provider.

When the token's `iss` names a known Floci issuer, all of the following are enforced:

- the RS256 signature, against the issuer's public key
- `iss` matches that issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy: `Principal.Federated` plus the `Condition` block, comparing `<oidcProvider>:sub` and `<oidcProvider>:aud` with exact, **case-sensitive** equality (`StringEquals`, `StringNotEquals`, `StringLike`, and `StringNotLike` are supported)

The response carries the token's real claims in `SubjectFromWebIdentityToken`, `Provider`, and `Audience`. A bad token returns `InvalidIdentityToken` (400), an expired one returns `ExpiredTokenException` (400), and a role that does not exist or a trust policy that does not permit the subject returns `AccessDenied` (403).

By default, tokens from an issuer Floci does not host, or tokens that are not parseable JWTs, are treated as opaque and accepted for compatibility with existing workflows, provided the target role exists in IAM. When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, those tokens return `InvalidIdentityToken` because Floci cannot verify a third-party provider's signature. See [EKS](eks.md) for the full IRSA walkthrough and the token-minting endpoint.

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
For temporary credentials returned by an assumed-role action, its `Arn` and `UserId` match
the `AssumedRoleUser.Arn` and `AssumedRoleUser.AssumedRoleId` returned when the session was created.

When `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true`, requests signed with the seeded `floci` access key return `arn:aws:iam::000000000000:user/floci-deployer`. Other unknown local credentials continue to return the account root ARN for backward compatibility.
