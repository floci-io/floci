# AWS IAM Identity Center OIDC

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci emulates the IAM Identity Center OIDC registration and token endpoints used by public OAuth 2.0 clients.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RegisterClient` | Registers a public OIDC client, persists its generated client credentials, and returns local authorization and token endpoints. |
| `StartDeviceAuthorization` | Validates a registered public client and creates a persisted short-lived device authorization challenge. |
| `CreateToken` | Exchanges device, PKCE authorization-code, or refresh-token grants for persisted bearer and refresh tokens. |
| `CreateTokenWithIAM` | Uses SigV4 and an application's IAM ActorPolicy to issue downscoped tokens for authorization-code, refresh-token, JWT-bearer, and token-exchange grants. |
<!-- floci:actions:end -->

`RegisterClient` supports the authorization-code, device-code, and refresh-token grant identifiers documented by AWS. Client registrations are persisted so later device authorization and token operations can authenticate the generated client ID and secret.

`StartDeviceAuthorization` validates the client credentials and stores the generated device and user codes for later token polling. Floci uses a 10-minute device-code lifetime and a 5-second polling interval as local emulator defaults.

`CreateToken` supports all three AWS-documented public-client grants. Device authorization can be completed locally by visiting the returned `/device?user_code=...` URL. Authorization Code uses the local `/authorize` endpoint with PKCE S256 and registered redirect URIs. Portal-capable local authorization never trusts a caller-selected identity: configure `FLOCI_SERVICES_SSOOIDC_LOCAL_PRINCIPAL_ID` to bind these local authorization helpers to one Identity Store principal. A mismatched `principal_id` is rejected, and when no local principal is configured the resulting public OIDC session is not associated with a Portal identity. Floci issues one-hour access tokens and 30-day refresh tokens as emulator defaults; the public API documentation does not define fixed lifetimes for these values. The `scope` request is intentionally ignored because AWS states that this operation always grants the scopes configured during client registration.

`CreateTokenWithIAM` requires SigV4 and reads the application's IAM authentication method, OAuth grants, and access scopes from the SSO Admin service. ActorPolicy evaluation honors explicit deny and supports wildcard or account/root principals that Floci can resolve from the signing credential. Role- and user-specific principal ARNs are denied because the current request context retains account and Region but not the exact signing principal ARN. JWT Bearer validates JWT structure, expiration, issuer, and audience against the configured trusted token issuer and grant; it does not perform external JWKS signature verification in the local emulator. Token Exchange requires a token issued to a different IAM application and an authorized target on one of the subject token's scopes.

OIDC failures use the AWS response shape with `error` and `error_description`. The local emulator assigns a 90-day client-secret lifetime; AWS documents the expiration timestamp but does not publish a fixed lifetime for this operation.

See the [IAM Identity Center OIDC API Reference](https://docs.aws.amazon.com/singlesignon/latest/OIDCAPIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SSOOIDC_ENABLED` | `true` | Enable or disable IAM Identity Center OIDC |
