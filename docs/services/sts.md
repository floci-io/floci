# STS

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `AssumeRole` |
| `GetCallerIdentity` |
| `GetSessionToken` |
| `AssumeRoleWithWebIdentity` |
| `AssumeRoleWithSAML` |
| `GetFederationToken` |
| `DecodeAuthorizationMessage` |
<!-- floci:actions:end -->

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
