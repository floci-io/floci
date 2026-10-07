# Cognito

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSCognitoIdentityProviderService.*`)
**Endpoint:** `POST http://localhost:4566/`

Floci serves pool-specific discovery and JWKS endpoints, plus a relaxed OAuth token endpoint, so local clients can mint and validate Cognito-like access tokens against RS256 signing keys.

When configured, `PostAuthentication` and `PreTokenGeneration` Lambda triggers must succeed
before authentication or token issuance completes. Function errors and malformed responses
return Cognito Lambda errors instead of issuing tokens without the trigger's claims.

`CreateUserPool` supports overriding several values using user-pool tags **only** at creation time:
* `floci:override-id`, to pin the resulting `UserPool.Id`. Because a pinned id is caller-chosen it can be reused, which AWS never does. `DeleteUserPool` therefore deletes everything the pool owns (users, groups, app clients, resource servers, revoked token records and outstanding verification codes) so a pool recreated on the same id starts empty rather than inheriting the deleted pool's password hashes and client secrets.
* `floci:override-cognito-client-id`
  * set to `use-name` to use the client name as client ID.
  * set to `append-to-name:-somestring` to append a string to the client name to be used as client ID.
  * set to `prepend-to-name:somestring-` to prepend a string to the client name to be used as client ID.
* `floci:override-cognito-client-secret`, to set the secret for all clients created in this userpool.  

Floci strips reserved `floci:*` tags from stored and returned `UserPoolTags` on both create and update paths, so the tag namespace acts as an input-only control channel and is never persisted as user-visible metadata.

Standalone `TagResource` rejects reserved `floci:*` keys. `ListTagsForResource` and `UntagResource` operate on the persisted user-pool tag map.

An action given a user pool ID that does not resolve returns `ResourceNotFoundException` with the live service's wording, `User pool <poolId> does not exist.`, so tooling that matches Cognito error text behaves the same way locally.

`CreateUserPoolClient` and `UpdateUserPoolClient` store `AuthSessionValidity` in minutes,
and `DescribeUserPoolClient` returns it. Values must be integers from 3 through 15.
New clients default to 3 minutes; an update that omits the field retains its stored value.
An explicit JSON `null` behaves as an omitted field. Wrong JSON types or numbers
outside the 32-bit integer range return `SerializationException`. Integer durations
outside 3 through 15 return `InvalidParameterException` with Cognito's constraint-error
format. Validation runs before pool or client lookup.

## Supported Actions

### User Pools

| Action | Description |
|--------|-------------|
| CreateUserPool | Creates a local user pool, applying supported `floci:*` creation-time overrides from tags. |
| DescribeUserPool | Returns the stored user pool configuration. |
| ListUserPools | Lists local user pools visible in the request region. |
| UpdateUserPool | Updates mutable user pool settings and persisted user-pool tags. |
| DeleteUserPool | Deletes a local user pool and everything it owns: users, groups, app clients, resource servers, identity providers, revoked tokens and verification codes. Refused with `InvalidParameterException` while `DeletionProtection` is `ACTIVE` (switch it to `INACTIVE` with `UpdateUserPool` first, as on AWS) or while a domain is still configured. |
| GetUserPoolMfaConfig | Returns the pool's MFA mode and, once configured, its software-token setting. |
| AddCustomAttributes | Adds 1 to 25 attributes to a user pool's schema, prefixing each name with `custom:`, or `dev:` for a `DeveloperOnlyAttribute`, and rejecting a name the schema already has. |
| SetUserPoolMfaConfig | Sets `MfaConfiguration` (`OFF`/`ON`/`OPTIONAL`) and `SoftwareTokenMfaConfiguration`. An absent `MfaConfiguration` means `OFF`, and turning MFA off drops the factor configuration with it. Validation follows the live service: `OFF` alongside a software-token, email or SMS factor is rejected, and `ON`/`OPTIONAL` with none of those three is rejected, in both cases on the member being present, not on its `Enabled` value. `WebAuthnConfiguration` sits outside both rules, as it does in AWS. SMS, email and WebAuthn configurations are validated and not stored: Floci cannot deliver those factors, so keeping the config would imply a capability it does not have. |

Email that Cognito sends to users, such as verification codes, goes through Floci's SES (readable
at `/_aws/ses`) from the sender the pool's `EmailConfiguration` names:

- With `EmailSendingAccount` `DEVELOPER`, `From` when it is set, as an address or a sender name
  with an address, otherwise the address of the `SourceArn` identity.
- With `COGNITO_DEFAULT`, the address of the `SourceArn` identity. AWS offers a sender name in
  `From` only with `DEVELOPER`.
- When the `SourceArn` identity is a domain, `From` gives the address.
- Otherwise `no-reply@verificationemail.com`.

A configured sender is used only when SES has verified it for the pool. The `SourceArn` must name
an identity in the user pool's partition and account that SES has verified in the `SourceArn`
Region (the pool's Region when the ARN has `*` there), and a `From` that gives the sender must be a
single mailbox at that email address (matched case-sensitively, as SES matches email address
identities) or in that domain or one of its subdomains. Otherwise the email goes from
`no-reply@verificationemail.com` and Floci logs a warning. Verify the identity first with
`VerifyEmailIdentity` or `CreateEmailIdentity`, or as a domain whose DKIM records are in Route 53.
When `From` has a sender name, the captured message keeps it in `Source`, and its `ReturnPath`,
which the SMTP relay uses as the envelope sender, is the bare address.

Differences from AWS: Floci accepts an unverified `SourceArn` when a pool is created or updated,
where AWS can fail with `InvalidEmailRoleAccessPolicyException`. It does not check the sending
authorization policy that a custom FROM address needs with `COGNITO_DEFAULT`, sends in the user
pool's Region rather than the `SourceArn` Region, and does not apply `ReplyToEmailAddress` or
`ConfigurationSet`.

### User Pool Tags

| Action | Description |
|--------|-------------|
| TagResource | Adds user-visible tags to a user pool and rejects reserved `floci:*` tag keys. |
| UntagResource | Removes tags from a user pool's persisted tag map. |
| ListTagsForResource | Returns the persisted user-pool tags. |

### User Pool Clients

| Action | Description |
|--------|-------------|
| CreateUserPoolClient | Creates an app client for a user pool, including optional generated secret handling. |
| DescribeUserPoolClient | Returns the stored app client configuration. |
| ListUserPoolClients | Lists app clients for a user pool. |
| UpdateUserPoolClient | Updates an app client's settings. A field the request omits keeps its stored value, where AWS resets it to its default. |
| DeleteUserPoolClient | Deletes an app client from a user pool. |
| AddUserPoolClientSecret | Adds a client secret to an app client, up to 2. A supplied `ClientSecret` must be 24 to 64 word characters; without one Floci generates the secret and returns its value. |
| ListUserPoolClientSecrets | Lists an app client's client secrets, without their values. |
| DeleteUserPoolClientSecret | Deletes one of an app client's client secrets. The client's only secret cannot be deleted. |

### Resource Servers

| Action | Description |
|--------|-------------|
| CreateResourceServer | Registers a resource server and scopes for a user pool. |
| DescribeResourceServer | Returns a registered resource server. |
| ListResourceServers | Lists resource servers for a user pool. |
| UpdateResourceServer | Updates a resource server's name and scopes. |
| DeleteResourceServer | Deletes a resource server from a user pool. |

### Identity Providers

| Action | Description |
|--------|-------------|
| CreateIdentityProvider | Registers a federated identity provider on a user pool. |
| DescribeIdentityProvider | Returns a registered identity provider. |
| ListIdentityProviders | Lists a user pool's providers as name/type/date summaries. |
| UpdateIdentityProvider | Updates a provider's details, attribute mapping or identifiers. |
| DeleteIdentityProvider | Deletes an identity provider from a user pool. |

Providers can be used for generic OIDC authorization-code sign-in. Floci routes the
AWS-shaped `/oauth2/authorize` and `/oauth2/idpresponse` endpoints to the configured local
OIDC provider, exchanges the returned code, provisions or reconciles the federated user,
and issues a Cognito authorization code for the registered callback.

Only `ProviderType=OIDC` is supported by this flow. Floci does not implement social providers
such as Google, Facebook, Login with Amazon or SignInWithApple. The pool's own users sign in
through managed login instead (see [Managed login](#managed-login)).

Two deliberate divergences from AWS, both consequences of not calling out to a third
party:

- **No create-time validation of `ProviderDetails`.** AWS resolves an OIDC provider's
  `oidc_issuer` discovery document while handling `CreateIdentityProvider`, and rejects
  the call when it is unreachable. Floci stores `ProviderDetails` opaquely and makes no
  outbound request, so it also does not enforce the per-provider-type required keys.
- **No injected provider defaults.** AWS adds keys such as
  `attributes_url_add_attributes` to an OIDC provider's stored details; Floci returns
  only what was supplied.

`AttributeMapping` and `IdpIdentifiers` follow AWS's update semantics: a member the
request omits is left unchanged, and an explicitly empty map or list is what clears it.
`IdpIdentifiers` is echoed by `CreateIdentityProvider` and `UpdateIdentityProvider` only
when the request supplied it, whatever the stored value, while `DescribeIdentityProvider`
always returns it.

### User Pool Domains

| Action | Description |
|--------|-------------|
| CreateUserPoolDomain | Creates a Cognito prefix domain, or a custom domain when `CustomDomainConfig.CertificateArn` is given. As on AWS, the certificate must exist in ACM in us-east-1 with status `ISSUED`; the domain is listed under the certificate's `InUseBy` until it is deleted. |
| DescribeUserPoolDomain | Returns a domain's description, including `CloudFrontDistribution` for custom domains. |
| UpdateUserPoolDomain | Replaces a custom domain's certificate or changes the managed login version in place. The domain keeps its `CloudFrontDistribution`, and its `InUseBy` entry moves to the new certificate. |
| DeleteUserPoolDomain | Deletes a domain from its user pool. |

#### Custom domains

A domain created with `CustomDomainConfig` answers the OAuth endpoints on that host, as on AWS:

```
GET  https://auth.example.localhost.floci.io/oauth2/authorize
POST https://auth.example.localhost.floci.io/oauth2/token
GET  https://auth.example.localhost.floci.io/oauth2/userInfo
GET  https://auth.example.localhost.floci.io/login
GET  https://auth.example.localhost.floci.io/logout
```

Requests are matched on the `Host` header, so the name must resolve to Floci (any
`*.localhost.floci.io` does) and, for `https`, TLS must be enabled. The domain pins its user pool and
the account that created it, since these requests carry no AWS credential: a `client_id` from
another pool is refused with `invalid_client`, and an access token issued by another pool with
`invalid_token`. Domain names are unique across all accounts, as on AWS. The pool's `openid-configuration` advertises the custom-domain
URLs when one exists. Prefix domains (`<prefix>.auth.<region>.amazoncognito.com`) are stored but not
routed, since that hostname never reaches Floci. `/login` and `/logout` are served at the root only
on a custom-domain host; on Floci's own host they are `/cognito-idp/login` and `/cognito-idp/logout`.

With TLS enabled, a custom domain (`CustomDomainConfig` set) is added to Floci's server
certificate as soon as it is created, so `https://<domain>` verifies without a restart; see
[TLS](../configuration/tls.md) for the accepted suffixes. A prefix domain is served under
`amazoncognito.com` on AWS, not by Floci, and is left alone.

### Log Delivery

| Action | Description |
|--------|-------------|
| SetLogDeliveryConfiguration | Replaces a user pool's log delivery configuration. |
| GetLogDeliveryConfiguration | Returns a user pool's log delivery configuration. |

`LogLevel` accepts `ERROR` or `INFO`, and `EventSource` accepts `userNotification` or
`userAuthEvents`. `LogConfigurations` holds at most 2 entries, and an event source may
appear only once across them. Each entry must name a destination:
`CloudWatchLogsConfiguration`, `FirehoseConfiguration` or `S3Configuration`, and a request
that omits one is rejected the way AWS rejects it. `Set` replaces the whole list rather
than merging, so an empty `LogConfigurations` is what clears it, and `Get` always returns
the member, as `[]` when nothing is configured.

The length and enum checks run before the pool is looked up, so an oversized or malformed
request naming a pool that does not exist reports the request problem rather than
`ResourceNotFoundException`, and every violation of them is reported in one message.

Floci stores the configuration and never delivers anything to the destination: the log
group, delivery stream or bucket is not written to, and is not required to exist. Two
further divergences, both deliberate:

- **No pricing-tier gate.** AWS refuses `userAuthEvents` on a pool in the `ESSENTIALS`
  tier with `FeatureUnavailableInTierException`; Floci accepts either event source
  whatever `UserPoolTier` says.
- **No destination validation.** AWS checks the ARN it is handed; Floci stores it as
  given.

### Admin User Management

| Action | Description |
|--------|-------------|
| AdminCreateUser | Creates or resends setup for a user in a user pool. Creating a user invokes the pre sign-up trigger with triggerSource `PreSignUp_AdminCreateUser`, the request's `ValidationData` and `ClientMetadata`, and `callerContext.clientId` `CLIENT_ID_NOT_APPLICABLE`. A trigger error refuses the user with `UserLambdaValidationException`, and its `autoConfirmUser`, `autoVerifyEmail` and `autoVerifyPhone` are ignored, as on AWS. |
| AdminGetUser | Returns a user's stored attributes and status, and the MFA settings (`UserMFASettingList`, `PreferredMfaSetting`) once any is turned on. |
| AdminDeleteUser | Deletes a user from a user pool. An API Gateway Cognito authorizer rejects the user's existing tokens afterwards. |
| AdminSetUserPassword | Sets a user's password and permanent-password status. |
| AdminUpdateUserAttributes | Updates attributes for a user in a user pool. |
| AdminDeleteUserAttributes | Deletes the named attributes from a user, along with any pending verification of them. |
| AdminConfirmSignUp | Confirms a user's sign-up without a confirmation code. |
| AdminDisableUser | Disables a user, who can no longer sign in. Tokens already issued keep working, where AWS revokes the user's access tokens. |
| AdminEnableUser | Re-enables a disabled user. |
| AdminResetUserPassword | Clears a user's password and sets the status to `RESET_REQUIRED`, so sign-in fails with `PasswordResetRequiredException`. Floci sends no reset code: finish with `ForgotPassword` and `ConfirmForgotPassword`, or `AdminSetUserPassword`. Refused when the pool's account recovery is `admin_only`. |
| AdminSetUserMFAPreference | Sets a user's email and software-token MFA preferences from `EmailMfaSettings` and `SoftwareTokenMfaSettings`. SMS settings are accepted but not stored. See [MFA preferences](#mfa-preferences). |
| AdminUserGlobalSignOut | Revokes the access, ID and refresh tokens issued to a user. |
| AdminLinkProviderForUser | Links an external IdP identity to an existing user's `identities` attribute. |

### User Operations

| Action | Description |
|--------|-------------|
| SignUp | Creates a self-service user for an app client. |
| ConfirmSignUp | Confirms a pending self-service signup. |
| ResendConfirmationCode | Issues a new sign-up confirmation code to an unconfirmed user, replacing the previous one, and returns where it was sent. |
| GetUser | Returns attributes for the authenticated access-token user, and the MFA settings (`UserMFASettingList`, `PreferredMfaSetting`) once any is turned on. |
| GetUserAuthFactors | Returns the authenticated access-token user's sign-in factors: `PASSWORD` when the user has a password, `EMAIL_OTP` and `SMS_OTP` when the email or phone number is verified, whatever the pool's `AllowedFirstAuthFactors` allows, as on AWS, and `SOFTWARE_TOKEN` once `VerifySoftwareToken` has confirmed an authenticator. The access token must carry the `aws.cognito.signin.user.admin` scope. `UserMFASettingList` and `PreferredMfaSetting` report the email and software-token MFA preferences set with `SetUserMFAPreference`. `WEB_AUTHN` and SMS MFA settings are not reported. |
| GetUserAttributeVerificationCode | Issues a verification code for the authenticated user's email or phone_number attribute. |
| VerifyUserAttribute | Verifies an email or phone_number attribute with its issued verification code. |
| UpdateUserAttributes | Updates attributes for the authenticated access-token user. |
| DeleteUserAttributes | Deletes the named attributes from the authenticated access-token user. |
| DeleteUser | Deletes the authenticated access-token user and removes them from their groups. An API Gateway Cognito authorizer rejects the user's existing tokens afterwards. |
| ChangePassword | Changes the authenticated user's password. |
| SetUserMFAPreference | Sets the authenticated access-token user's email and software-token MFA preferences from `EmailMfaSettings` and `SoftwareTokenMfaSettings`. SMS settings are accepted but not stored. See [MFA preferences](#mfa-preferences). |
| GlobalSignOut | Revokes the access, ID and refresh tokens issued to the authenticated access-token user. |
| ForgotPassword | Starts the local forgot-password flow for a user. |
| ConfirmForgotPassword | Completes the forgot-password flow by setting a replacement password. |

As on AWS, every operation authorized by the user's access token (`GetUser`,
`GetUserAuthFactors`, `UpdateUserAttributes`, `DeleteUserAttributes`, `DeleteUser`,
`ChangePassword`, `GetUserAttributeVerificationCode`, `VerifyUserAttribute`,
`SetUserMFAPreference`, `GlobalSignOut`, and `AssociateSoftwareToken` and
`VerifySoftwareToken` with an `AccessToken`)
requires the token's `scope` to include `aws.cognito.signin.user.admin`. Tokens from
`InitiateAuth` and the other API sign-in flows always carry it; a token from the OAuth token
endpoint carries it only when the authorization request asked for it, or asked for no scope and
the client allows it. Without it the call fails with `NotAuthorizedException:
Access Token does not have required scopes`, as it does for a token with no `scope` claim at all,
which a pre token generation trigger leaves when it suppresses every scope.

### Authentication

| Action | Description |
|--------|-------------|
| InitiateAuth | Authenticates app-client users through supported user-password and SRP-style flows. |
| AdminInitiateAuth | Starts an admin authentication flow for a user pool user. |
| RespondToAuthChallenge | Responds to supported Cognito auth challenges, including TOTP setup and software-token MFA. |
| AdminRespondToAuthChallenge | Responds to a challenge from `AdminInitiateAuth`, with the same challenges `RespondToAuthChallenge` supports. |
| GetTokensFromRefreshToken | Issues new access and ID tokens from a refresh token. A client's `RefreshTokenRotation` setting is stored but not applied, so no new refresh token is issued. |
| RevokeToken | Revokes a refresh token. The client must have token revocation enabled and, when it has a secret, present it. Only refresh tokens can be revoked. |
| AssociateSoftwareToken | Creates a TOTP secret for a user identified by an MFA setup session or access token. |
| VerifySoftwareToken | Verifies the TOTP code and registers the user's authenticator. |

With `MfaConfiguration=ON` and software-token MFA enabled, a successful password or SRP
first factor returns `MFA_SETUP` instead of tokens for a user without a verified token.
Call `AssociateSoftwareToken` with that session, then `VerifySoftwareToken` with the
returned session and a six-digit TOTP code. Finish with `RespondToAuthChallenge`
(`MFA_SETUP`) to receive tokens. Later sign-ins return `SOFTWARE_TOKEN_MFA`, which
requires a fresh code in `SOFTWARE_TOKEN_MFA_CODE`. Both token-management actions
also accept an access token for an already authenticated user. Sessions expire with
the app client's `AuthSessionValidity` and cannot be replayed after completion.

Completing `MFA_SETUP` turns software-token MFA on for the user and, when no other factor is
preferred, makes it the preferred one, so `GetUser` and `AdminGetUser` report it.

#### MFA preferences

With `MfaConfiguration=OPTIONAL`, a verified authenticator alone does not change sign-in: as on
AWS, the user is asked for a code only once software-token MFA is turned on with
`SetUserMFAPreference` or `AdminSetUserMFAPreference` (`SoftwareTokenMfaSettings.Enabled`). From
then on a successful password or SRP first factor, through `USER_PASSWORD_AUTH`,
`ADMIN_USER_PASSWORD_AUTH`, `USER_SRP_AUTH` or the `PASSWORD` and `PASSWORD_SRP` challenges of
`USER_AUTH`, returns `SOFTWARE_TOKEN_MFA`, answered with `RespondToAuthChallenge` or
`AdminRespondToAuthChallenge`. Turning it off again signs the user in without a challenge. The
challenge follows the user's registered authenticator even when the pool later stops offering
software-token MFA, which is what AWS documents.

- Turning software-token MFA on for a user without a verified authenticator fails with
  `InvalidParameterException` (`User does not have delivery config set to turn on
  SOFTWARE_TOKEN_MFA`). Turning a factor off is always accepted and drops its preference.
- Only one factor is preferred: preferring one clears the preference of the other, and preferring
  both in one request, or preferring a factor that is off, fails with `InvalidParameterException`.
- `UserMFASettingList` lists the factors turned on (`EMAIL_OTP`, `SOFTWARE_TOKEN_MFA`), and
  `PreferredMfaSetting` the preferred one. Both are omitted when empty. In a pool that requires
  software-token MFA, a registered authenticator is listed whether or not it was turned on, since
  every sign-in asks for it.
- In `USER_AUTH`, a user with software-token MFA turned on in an optional-MFA pool is offered only
  `PASSWORD` and `PASSWORD_SRP`, as AWS documents for users with MFA, so the second factor still
  follows. Email MFA does not restrict the choice, because Floci does not send an email MFA code
  after a password.

SMS and email MFA challenges, and managed-login MFA, are not emulated.

### User Listing

| Action | Description |
|--------|-------------|
| ListUsers | Lists users stored in a user pool. |

## Supported AuthFlow Values

`InitiateAuth` accepts `USER_PASSWORD_AUTH`, `USER_SRP_AUTH`, `CUSTOM_AUTH`, `USER_AUTH`,
`REFRESH_TOKEN_AUTH` and `REFRESH_TOKEN`. `AdminInitiateAuth` accepts `ADMIN_USER_PASSWORD_AUTH`,
`ADMIN_NO_SRP_AUTH`, `USER_SRP_AUTH`, `USER_PASSWORD_AUTH`, `CUSTOM_AUTH`, `USER_AUTH`,
`REFRESH_TOKEN_AUTH` and `REFRESH_TOKEN`.

`USER_AUTH` is the choice-based flow: with no `PREFERRED_CHALLENGE` it returns
`ChallengeName=SELECT_CHALLENGE` and an `AvailableChallenges` list drawn from what the user has
configured (`PASSWORD`, `PASSWORD_SRP`, `EMAIL_OTP`, `SMS_OTP`); with one, it goes straight to that
challenge. The list keeps only the first factors the pool's
`Policies.SignInPolicy.AllowedFirstAuthFactors` allows: its `PASSWORD` covers both `PASSWORD` and
`PASSWORD_SRP`, so a pool that allows only `EMAIL_OTP` offers `["EMAIL_OTP"]`, and a user with no
password is never offered a password challenge. A pool created without a `SignInPolicy` gets
`{"AllowedFirstAuthFactors": ["PASSWORD"]}`, on any tier, as `DescribeUserPool` reports on AWS, so it
offers password challenges alone. `UpdateUserPool` keeps the policy when the request omits `Policies`,
and puts the default back when its `Policies` has no `SignInPolicy`. A `PREFERRED_CHALLENGE` outside
the list, because the policy leaves it out or the user has not set it up, gets `SELECT_CHALLENGE` and
the list, as on AWS; one that names no challenge Cognito supports fails with
`InvalidParameterException`. A `SELECT_CHALLENGE` answer outside the list fails with
`InvalidParameterException`. It requires the user pool's tier to be Essentials or higher. `WEB_AUTHN` and the `ConfirmSignUp` session as a first-factor
shortcut are not implemented yet.

Any other `AuthFlow` value is rejected with `InvalidParameterException` and no tokens are issued.

The challenge `Session` of a `USER_SRP_AUTH`, `CUSTOM_AUTH` or `USER_AUTH` sign-in is valid for 3 minutes
from when it was issued, the AWS default for the client's `AuthSessionValidity`. Answering the challenge with
an older session fails with `NotAuthorizedException` (`Invalid session for the user, session is expired.`)
and the sign-in has to start again. A per-client `AuthSessionValidity` is not supported yet, and the
`NEW_PASSWORD_REQUIRED` challenge does not check its session.

An app client only accepts the flows in its `ExplicitAuthFlows`: `ALLOW_USER_PASSWORD_AUTH`,
`ALLOW_USER_SRP_AUTH`, `ALLOW_CUSTOM_AUTH`, `ALLOW_USER_AUTH`, `ALLOW_ADMIN_USER_PASSWORD_AUTH` and
`ALLOW_REFRESH_TOKEN_AUTH`, or the legacy `USER_PASSWORD_AUTH`, `ADMIN_NO_SRP_AUTH` and
`CUSTOM_AUTH_FLOW_ONLY`. Any other flow fails with `InvalidParameterException`.

A client created without `ExplicitAuthFlows`, or with it cleared to an empty list, stores and describes an
empty list, matching AWS. It is enforced as if it had `ALLOW_REFRESH_TOKEN_AUTH`, `ALLOW_USER_SRP_AUTH` and
`ALLOW_CUSTOM_AUTH`, the default AWS documents for such a client: only the enforcement uses that default, not
the stored or returned value. A client that signs in with `USER_PASSWORD_AUTH`, `ADMIN_USER_PASSWORD_AUTH` or
`USER_AUTH` must list the matching `ALLOW_` value.

## User Attribute Update Verification

`CreateUserPool`, `UpdateUserPool`, and `DescribeUserPool` support
`UserAttributeUpdateSettings.AttributesRequireVerificationBeforeUpdate` for
`email` and `phone_number`.

For attributes listed in this setting, `UpdateUserAttributes` keeps the existing
verified value and sign-in alias active while the new value is pending. It sends
a verification code to the pending destination and returns the corresponding
entry in `CodeDeliveryDetailsList`. A successful `VerifyUserAttribute` promotes
the pending value, switches the alias, and sets the matching `*_verified`
attribute to `true`.

Without the setting, `UpdateUserAttributes` replaces the value immediately and
sets the matching `*_verified` attribute to `false` until verification succeeds.
A verification code is still sent to the replacement value, but Cognito no
longer retains or exposes the old value. For alias attributes, the old alias is
removed immediately and the replacement becomes usable for sign-in only after
successful verification. Incorrect or expired codes don't promote a pending
value or change its verified state.

### Groups

| Action | Description |
|--------|-------------|
| CreateGroup | Creates a group in a user pool. |
| GetGroup | Returns a user-pool group. |
| UpdateGroup | Updates a user-pool group's stored settings. |
| ListGroups | Lists groups in a user pool. |
| ListUsersInGroup | Lists users assigned to a group. |
| DeleteGroup | Deletes a group from a user pool. |
| AdminAddUserToGroup | Adds a user to a group. |
| AdminRemoveUserFromGroup | Removes a user from a group. |
| AdminListGroupsForUser | Lists the groups assigned to a user. |

### Managed Login Branding

| Action | Description |
|--------|-------------|
| CreateManagedLoginBranding | Creates the branding for an app client. |
| DescribeManagedLoginBranding | Returns a branding by its id. |
| DescribeManagedLoginBrandingByClient | Returns the branding attached to an app client. |
| UpdateManagedLoginBranding | Updates a branding's settings, assets or provided-values flag. |
| DeleteManagedLoginBranding | Deletes a branding from its app client. |

`CreateManagedLoginBranding` must name either `UseCognitoProvidedValues` or `Settings`; a
request with neither is rejected. One branding per app client: a second
`CreateManagedLoginBranding` for the same client is rejected with
`ManagedLoginBrandingExistsException`. `ManagedLoginBrandingId` must be a
version 4 UUID, and a malformed one is rejected before the lookup, as AWS does.
`Assets` holds at most 40 entries on create and on update.
`Settings` is omitted from the response when the caller supplied none, while `Assets` is
always returned. Members an update omits are left unchanged.

The asset-count and branding-id checks run before the pool, client or branding is looked
up, so an oversized request naming something that does not exist reports the request
problem rather than `ResourceNotFoundException`, and an update violating both reports them
in one message with the asset list first.

Branding is presentation for the managed login pages. Floci's sign-in page is deliberately
plain, so branding is stored and returned rather than rendered. Two divergences follow from that:

- **`Settings` is stored opaquely.** AWS validates it against a deep schema, rejecting
  unknown properties with `Invalid settings provided. Validation errors: [{property:
  $.components...., errorType: UnknownProperty}]`. That schema is not published, so Floci
  accepts any object.
- **A wrongly typed `Settings` returns a client error.** AWS answers that particular input
  with `InternalErrorException` and a 500; Floci returns
  `SerializationException: Unexpected field type`, which is what AWS returns for a wrongly
  typed `Assets`. Reproducing someone else's 500 seemed worse than being consistent.
- **`ReturnMergedResources` is not honoured.** Against AWS it merges Cognito's own default
  settings and assets into the response: on a pool with 8 configured assets it returns 38.
  Reproducing that needs Cognito's default corpus, so Floci returns the stored branding
  either way.

## Well-Known And OAuth Endpoints

| Endpoint                                             | Description                                                      |
|------------------------------------------------------|------------------------------------------------------------------|
| `GET /{userPoolId}/.well-known/openid-configuration` | OpenID discovery document                                        |
| `GET /{userPoolId}/.well-known/jwks.json`            | JSON Web Key Set for JWT validation                              |
| `GET /cognito-idp/oauth2/authorize`                  | Authorization-code start endpoint, for managed login and OIDC    |
| `GET /cognito-idp/oauth2/idpresponse`                | OIDC provider callback endpoint                                  |
| `GET`, `POST /cognito-idp/login`                     | Managed login sign-in form                                       |
| `GET /cognito-idp/logout`                            | Managed login sign-out                                           |
| `POST /cognito-idp/oauth2/token`                     | OAuth authorization-code and client-credentials token endpoint   |

The OAuth endpoints support browser-style authorization-code sign-in, for the pool's own
users and through a federated OIDC provider, as well as the emulator-friendly
client-credentials flow:

- `GET /cognito-idp/oauth2/authorize` validates the app client and callback. With no
  `identity_provider`, or `identity_provider=COGNITO`, it starts managed login (below);
  with any other provider name it redirects to that OIDC provider with an opaque state and nonce.
- `GET /cognito-idp/oauth2/idpresponse` consumes the provider state, exchanges the provider
  code and redirects to the registered callback with a one-time Cognito authorization code.
- `POST /cognito-idp/oauth2/token` redeems that authorization code once, checking its PKCE
  `code_verifier` when the authorization request sent a `code_challenge`, or issues a machine
  token for `grant_type=client_credentials`.

### Managed login

Managed login signs in the pool's own users with authorization code and, optionally, PKCE.
The client needs `COGNITO` in `SupportedIdentityProviders`, `AllowedOAuthFlows=["code"]` and
the callback in `CallbackURLs`. No domain is needed; on a custom domain the same flow runs at
`/oauth2/authorize`, `/login` and `/logout`.

1. `GET /cognito-idp/oauth2/authorize` redirects to `/cognito-idp/login` with the request's
   parameters, `login_hint` included. If the browser already has a managed login session in the
   pool, it skips the form and redirects straight to the callback with a code, as AWS does. A
   `scope` the client's `AllowedOAuthScopes` does not include is refused first, as on AWS, with a
   redirect to the callback carrying `error=invalid_request`, `error_description=invalid_scope`
   and the `state`.
2. `GET /cognito-idp/login` renders a plain username and password form, with the username filled
   in from `login_hint` when the request has one. The form carries the request in hidden fields and
   a CSRF token that must match the `XSRF-TOKEN` cookie set with it. With choice-based sign-in
   (below), it asks for the username alone.
3. `POST /cognito-idp/login` checks the password as `USER_PASSWORD_AUTH` does, including
   sign-in aliases and the pre and post authentication and user migration triggers, but
   without the client's `ExplicitAuthFlows`. On success it sets a `cognito` session cookie
   (one hour) and redirects to the callback with `code` and `state`. A wrong password shows
   the form again with `Incorrect username or password.`; an unknown user reads the same.
4. `POST /cognito-idp/oauth2/token` redeems the code. It invokes the pre token generation
   trigger with triggerSource `TokenGeneration_HostedAuth`, as AWS does for a hosted-UI
   sign-in, so a pool that customises its claims gets the same tokens here as from
   `InitiateAuth`. The access token's `scope` is the scopes the request asked for, or every
   scope in the client's `AllowedOAuthScopes` when it asked for none, as on AWS, and the
   trigger is told the same scopes. The code keeps the scopes granted when it was issued: a
   scope the client is allowed only afterwards is not added, and one it no longer allows is
   dropped. A V2 trigger's `scopesToAdd` and `scopesToSuppress` apply on top of them. The ID token is issued only when the scopes include `openid`, and it carries
   the request's `nonce`, which the trigger cannot override.
5. `GET /cognito-idp/logout?client_id=...&logout_uri=...` ends the session and redirects to
   `logout_uri`, which must be one of the client's `LogoutURLs`. With `redirect_uri` and
   `response_type=code` instead of `logout_uri`, it ends the session and redirects to the
   sign-in form for that request.

Choice-based sign-in applies when the pool's `SignInPolicy.AllowedFirstAuthFactors` includes
`EMAIL_OTP` and the client's `ExplicitAuthFlows` includes `ALLOW_USER_AUTH` (on a pool above the
Lite tier). The page then takes one step per `POST /cognito-idp/login`. Up to the password or the
code, every username gets the same pages, so they do not tell who has an account, which factors
they have, or whether they can sign in:

1. The username.
2. The factor, as buttons that post `challenge=PASSWORD` or `challenge=EMAIL_OTP`, when the policy
   allows both. When it allows only `EMAIL_OTP`, every username goes straight to the code.
3. For `PASSWORD`, the username and password form, where a user without a password fails as a
   wrong password does. For `EMAIL_OTP`, the page sends a code through the `USER_AUTH` `EMAIL_OTP`
   challenge, so it arrives in Floci's SES (readable at `/_aws/ses`), and shows a code field. The
   username and the challenge's `Session` travel in hidden fields. A correct code signs the user
   in as the password does: the session cookie, and a redirect to the callback with `code` and
   `state`. A wrong code shows the code field again with
   `Invalid verification code provided, please try again.` and the same session, so the user can
   try again until the session (the client's `AuthSessionValidity`) or the code runs out.

Only a user who can sign in and has a verified email is sent a code. Anyone else, an unknown user
included, gets the same code field, but no message is sent and every code is wrong. Asking again
within 30 seconds, before Floci sends another code, shows the code field for the code already
sent. Why a user cannot sign in (disabled, unconfirmed, a password reset or a new password
required) shows only after a correct code. A correct code uses up the session, so when the user
cannot sign in or the post authentication trigger fails, the page goes back to the username with
the reason. A code signs in once: of several requests that answer with it at once, from one session
or several, one signs in and the others are told the code is wrong, or go back to the username if
their session is already spent. A password posted to a pool whose policy leaves out `PASSWORD` is
refused. Without choice-based sign-in, the page is the username and password form above, whatever
the policy says.

PKCE follows AWS: `code_challenge_method` must be `S256`, and discovery advertises
`code_challenge_methods_supported: ["S256"]`. A code issued with a `code_challenge` is
redeemed only with the matching `code_verifier`, so a public client (no secret) can use it
alone. A code issued without one is refused if a `code_verifier` is sent, as RFC 9700
recommends. A failed PKCE check spends the code; a request naming the wrong client or
`redirect_uri` does not. PKCE applies to federated OIDC sign-in too.

Differences from AWS:

- **No challenge pages.** A user who must change or reset their password, or who is not
  confirmed, sees an error on the form instead. Sign-up, forgot-password, MFA and passkey
  pages are not served, choice-based sign-in offers no `SMS_OTP` or `WEB_AUTHN` factor, and
  `prompt`, `lang` and `idp_identifier` are ignored.
- **Errors are JSON.** An authorization request error returns `400` with an OAuth error body,
  even after `redirect_uri` is validated, where AWS redirects the error to the callback. The
  exception is an unallowed `scope`, which is redirected as on AWS.
- **Relative redirect.** The redirect from `/oauth2/authorize` to the sign-in form has a
  relative `Location`, where AWS's is absolute.
- **One session cookie per host.** Floci's own host serves every pool, so signing in to a
  second pool there replaces the first pool's session. Custom domains keep separate sessions,
  as on AWS. Sessions are held in memory and are lost on restart.

```bash
EP=http://localhost:4566
POOL_ID=$(aws --endpoint-url $EP cognito-idp create-user-pool --pool-name web \
  --query UserPool.Id --output text)
CLIENT_ID=$(aws --endpoint-url $EP cognito-idp create-user-pool-client --user-pool-id $POOL_ID \
  --client-name spa --supported-identity-providers COGNITO \
  --allowed-o-auth-flows-user-pool-client --allowed-o-auth-flows code \
  --allowed-o-auth-scopes openid email --callback-urls https://app.example.com/cb \
  --logout-urls https://app.example.com/ --query UserPoolClient.ClientId --output text)
aws --endpoint-url $EP cognito-idp admin-create-user --user-pool-id $POOL_ID --username alice
aws --endpoint-url $EP cognito-idp admin-set-user-password --user-pool-id $POOL_ID \
  --username alice --password 'Perm1234!' --permanent

# PKCE pair: verifier, and its unpadded base64url SHA-256 challenge
VERIFIER=$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n')
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=')

# Open the sign-in form, then post the credentials with its CSRF token
Q="response_type=code&client_id=$CLIENT_ID&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcb&scope=openid&state=s1&code_challenge=$CHALLENGE&code_challenge_method=S256"
CSRF=$(curl -s -c jar "$EP/cognito-idp/login?$Q" | sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p')
CODE=$(curl -s -b jar -c jar -o /dev/null -w '%{redirect_url}' "$EP/cognito-idp/login?$Q" \
  --data-urlencode "_csrf=$CSRF" --data-urlencode username=alice --data-urlencode 'password=Perm1234!' \
  | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')

# Redeem the code with the verifier
curl -s -X POST "$EP/cognito-idp/oauth2/token" \
  --data-urlencode grant_type=authorization_code --data-urlencode client_id=$CLIENT_ID \
  --data-urlencode code=$CODE --data-urlencode redirect_uri=https://app.example.com/cb \
  --data-urlencode code_verifier=$VERIFIER

# Sign out
curl -s -b jar -o /dev/null -w '%{http_code} %{redirect_url}\n' \
  "$EP/cognito-idp/logout?client_id=$CLIENT_ID&logout_uri=https%3A%2F%2Fapp.example.com%2F"
```

`POST /cognito-idp/oauth2/token` is intentionally emulator-friendly rather than full Cognito parity:

- It requires an existing `client_id`.
- It accepts `client_id` and `client_secret` from the form body or Basic auth.
- Client-credentials requires a confidential app client created with `GenerateSecret=true`.
- Authorization-code redemption validates the client, callback URI and one-time code binding.
- It requires `AllowedOAuthFlowsUserPoolClient=true` and `AllowedOAuthFlows=["client_credentials"]`.
- It doesn't require a Cognito domain.
- Client-credentials returns only `access_token`, `token_type`, and `expires_in`; authorization-code
  redemption returns the Cognito access and refresh tokens, and an ID token when the granted scopes
  include `openid`.
- It validates requested OAuth scopes against the app client's `AllowedOAuthScopes` and the pool's registered resource-server scopes.
- It advertises the prefixed token endpoint in `/{userPoolId}/.well-known/openid-configuration`, or
  `https://<domain>/oauth2/token` when the pool has a custom domain (see Custom domains above).

## Sign-in Identifiers (`UsernameAttributes`)

Floci follows real Cognito semantics for pools created with `UsernameAttributes` (e.g.
`--username-attributes email`):

- `AdminCreateUser`/`SignUp` accept the email (or phone number) as the sign-in value, but the
  **canonical `Username` is an auto-generated, immutable UUID equal to `sub`**. The supplied email is
  stored as a mutable alias attribute.
- `ListUsers`, `AdminGetUser` and Lambda trigger `event.userName` all report the **UUID**, never the
  email; the email lives in the `email` attribute and in `event.request.userAttributes.email`.
- Sign-in resolves by the **current** email alias **or** the UUID. `SECRET_HASH` is validated against
  the exact `USERNAME` value sent: `Base64(HMAC-SHA256(USERNAME + clientId, clientSecret))`.
- In `CUSTOM_AUTH` / SRP flows, `ChallengeParameters.USERNAME` echoes the **UUID** (as real AWS does);
  the `RespondToAuthChallenge` `SECRET_HASH` is validated against the `USERNAME` sent that round. That
  `USERNAME` must still resolve to the session's user — the UUID or any of its current aliases — and a
  value naming a different user is rejected with `NotAuthorizedException`.
- `AdminUpdateUserAttributes` can change the email; afterwards sign-in works with the new email and
  fails with the old one, while `Username`/`sub` stay fixed.
- Duplicate email/phone on `AdminCreateUser`: `UsernameExistsException` when the incoming alias is
  unverified, `AliasExistsException` when it is verified (`email_verified=true`); pass
  `ForceAliasCreation=true` to migrate a verified alias off the previous owner. On
  `AdminUpdateUserAttributes`, changing to an in-use alias throws `AliasExistsException`.

Pools **without** `UsernameAttributes` (classic pools, and pools using `AliasAttributes`) keep the
literal `Username` you supply, unchanged.

### Token claims

Floci mirrors AWS's access-token / ID-token split:

- **Access token:** `sub`, `username` (the UUID), `scope` (`aws.cognito.signin.user.admin` for API
  sign-in, the granted OAuth scopes for an authorization code), `client_id`, `cognito:groups`,
  `jti`/`origin_jti`. It does **not** carry `cognito:username` or user attributes like `email`.
- **ID token:** `sub`, `cognito:username`, `aud`, and readable user attributes (`email`,
  `email_verified`, `phone_number`, `custom:*`, ...). Attribute claims are filtered by the app client's
  `ReadAttributes` (an unset/empty list means all attributes are readable).

As on AWS, a pre token generation trigger cannot suppress or override `sub`, the access token's
`username` or the ID token's `cognito:username`; Floci ignores those entries in its response.

Not-found errors (`ResourceNotFoundException`, `UserNotFoundException`) return HTTP `400`, matching the
Cognito JSON protocol.

## Configuration

| Variable                         | Default | Description                   |
|----------------------------------|---------|-------------------------------|
| `FLOCI_SERVICES_COGNITO_ENABLED` | `true`  | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a user pool
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name MyApp \
  --query UserPool.Id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create an app client
CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-name my-client \
  --generate-secret \
  --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-flows client_credentials \
  --allowed-o-auth-scopes notes/read notes/write \
  --query UserPoolClient.ClientId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Retrieve the generated client secret
CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-id $CLIENT_ID \
  --query UserPoolClient.ClientSecret --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Register a resource server and scopes
aws cognito-idp create-resource-server \
  --user-pool-id $POOL_ID \
  --identifier notes \
  --name "Notes API" \
  --scopes ScopeName=read,ScopeDescription="Read notes" ScopeName=write,ScopeDescription="Write notes" \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a user
aws cognito-idp admin-create-user \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --temporary-password Temp1234! \
  --endpoint-url $AWS_ENDPOINT_URL

# Set a permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --password Perm1234! \
  --permanent \
  --endpoint-url $AWS_ENDPOINT_URL

# Authenticate
aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id $CLIENT_ID \
  --auth-parameters USERNAME=alice@example.com,PASSWORD=Perm1234! \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a group
aws cognito-idp create-group \
  --user-pool-id $POOL_ID \
  --group-name admin \
  --description "Admin group" \
  --endpoint-url $AWS_ENDPOINT_URL

# Add user to group
aws cognito-idp admin-add-user-to-group \
  --user-pool-id $POOL_ID \
  --group-name admin \
  --username alice@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# List groups for user
aws cognito-idp admin-list-groups-for-user \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# Fetch the pool discovery document
curl -s "$AWS_ENDPOINT_URL/$POOL_ID/.well-known/openid-configuration"

# Get a machine access token from the OAuth endpoint
curl -s \
  -X POST "$AWS_ENDPOINT_URL/cognito-idp/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=notes/read notes/write"
```

## JWT Validation

Tokens issued by Floci can be validated using the discovery and JWKS endpoints:

```
http://localhost:4566/$POOL_ID/.well-known/openid-configuration
```

```
http://localhost:4566/$POOL_ID/.well-known/jwks.json
```

Tokens include the `cognito:groups` claim as a JSON array when the authenticated user belongs to one or more groups.

Tokens issued by Cognito auth flows and the OAuth token endpoint use the emulator base URL plus the pool id:

```
http://localhost:4566/$POOL_ID
```

This keeps the issuer, discovery document, JWKS URL, and token endpoint internally consistent for local JWT validation while supporting LocalStack-style confidential clients and resource-server-backed scopes.
