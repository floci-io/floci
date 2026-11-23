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
| `GetDelegatedAccessToken` | Exchange a delegation request's trade-in token for temporary credentials |
| `GetAccessKeyInfo` | Returns the account an access key ID belongs to |
| `AssumeRoot` | Opens a privileged session on a member account of the organization |
| `GetWebIdentityToken` | Returns a signed JWT representing the calling identity |

## Account-root token lifetime

`GetSessionToken` and `GetFederationToken` sessions issued to Floci's account-root
credential default to one hour and are capped at one hour, including larger valid
`DurationSeconds` requests. IAM users retain the requested duration and the
12-hour default. Existing input validation and temporary-caller rejection run
before credentials are registered.

## Request Constraints

Every operation that models a constraint is held to the one its model declares rather than to a
check written by hand, which is all ten that carry a member: `tools/aws/regen_sts_shapes.py`
vendors them and the walk is the one IAM reads its own file with. `GetCallerIdentity` models no
member at all, so there is nothing to read for it. One word of the
message differs between the two: STS quotes the offending value, `Value 'x' at 'serialNumber'`,
where IAM names the member and leaves it out, which is what each service's own captures carry (see
the [IAM service doc](iam.md)). Every violation
a request carries is reported in one `ValidationError`, in the order the model lists the members,
and a member that breaks two constraints is reported for both. A member sent empty is a member the
request carries, so it breaks its own length rather than reading as absent, and only a member the
request names nowhere is answered as null. A list arrives numbered under its own name,
`Audience.member.1` or the bare `Audience.1`, a member of a list of structures carries its fields
under that prefix as `Tags.member.1.Key` does, and each is named the way this service already
names one, `audience.1.member` and `tags.1.member.key`, under the index the request spelled.

What that adds is on `GetSessionToken`, whose `SerialNumber`, `TokenCode` and
`MinimumSessionTokenSize` were accepted and ignored: each is now held to its shape, so a serial
number under 9 characters, a token code that is not six digits or a token size past 4096 answers
400 where it used to answer credentials. The size asked for is the size issued, on every operation
that models the member rather than on `AssumeRoot` alone: each mints its token through one writer,
which holds it to the 200 characters this emulator mints when a request asks for less, since that
is what a minimum asks for.
`GetAccessKeyInfo` and `GetWebIdentityToken` answer the messages they already answered, from the
same bounds and the same patterns, and what moves there is that an access key ID breaking both its
length and its pattern is answered for both where the check it replaces stopped at the first.

A pattern is read in the dialect the model writes it in rather than in Java's, which is one
thing on one shape. `arnType`'s pattern ends in a five-digit escape, the U+10000 to U+10FFFF range,
where Java reads a backslash-u escape as exactly four digits and takes the rest as literals:
compiled as written, that range became the character U+1000, a literal zero, a hyphen, U+10FF and
two literal Fs, so the class gained a range from `0` to U+10FF. It refused every supplementary
character the model admits and admitted the C1 controls it excludes, U+0081 among them, on every
member the shape resolves to: `RoleArn` on the three assume-role operations, `PrincipalArn` on the
SAML one, each entry of `PolicyArns` on those three and on `GetFederationToken`, `TaskPolicyArn` on
`AssumeRoot` and the provided-context ARN on `AssumeRole`. Each such escape is read as the code
point it spells now, for every vendored pattern on both protocols, and `arnType` is the only shape
in any vendored file that carries one.

The model says nothing about what counts as a number, so a `DurationSeconds` or a
`MinimumSessionTokenSize` that is no number at all stays `InvalidParameterValue`, answered before
the constraints, while a digit string is held to the range whatever width it overflows: a duration
of `99999999999` breaks the maximum rather than being called no integer. The whitespace around a
number is not part of it, both where the range is read and where the duration is then used, so a
`DurationSeconds` of ` 3600 ` is an hour rather than the default a raw parse would have fallen back
to, which is how IAM reads its own `maxItems`.

The seven operations read last answered an absent member from a check of their own, which named the
parameter as the request writes it, `RoleArn` rather than `roleArn`, and read a blank value as an
absent one. Both of those move onto the form above, so a request missing `RoleSessionName` is
answered `Value null at 'roleSessionName' failed to satisfy constraint: Member must not be null`,
and one sending it empty breaks its minimum length instead, which is the reading the model gives
and the one the rest of this package already answers. What those seven gain is every bound the
model declares beside the required members: `RoleArn` at 20 to 2048 characters,
`RoleSessionName` at 2 to 64 of `[\w+=,.@-]`, `ExternalId`, `SourceIdentity`, `Policy`,
`PolicyArns`, `Tags`, `TransitiveTagKeys` and `ProvidedContexts` on `AssumeRole`, `SAMLAssertion`
at 4 to 100000 on `AssumeRoleWithSAML`, `WebIdentityToken` at 4 to 20000 and `ProviderId` at 4 to
2048 on `AssumeRoleWithWebIdentity`, `Name` at 2 to 32 on `GetFederationToken`, `EncodedMessage` at
1 to 10240 on `DecodeAuthorizationMessage`, and `TargetPrincipal` at 12 to 2048 on `AssumeRoot`,
whose own length check said the same thing by hand. Each operation keeps the refusals it answers
ahead of the constraints: `GetFederationToken` and `GetSessionToken` still turn away session
credentials first, and `AssumeRoot` still refuses the global endpoint first.

One reading is Floci's own. `TradeInToken` on `GetDelegatedAccessToken` is required and carries no
length at all, so a request sending it empty is a request that carries it: it reaches the lookup
and is answered `ExpiredTradeInTokenException`, the refusal any token that cannot be redeemed gets,
where the check it replaces called it absent. Nothing published says what AWS answers an empty
trade-in token, and the model is what is in hand, so the blank is read as the value it is.

SAML role sessions reject `DurationSeconds` above the target role maximum. Accepted requests retain the shorter assertion expiration.

A SAML role session is named after the assertion's `https://aws.amazon.com/SAML/Attributes/RoleSessionName`
attribute, which is also the `sts:RoleSessionName` the role's trust policy reads. A value outside
`[a-zA-Z_0-9+=,.@-]{2,64}` is refused with `InvalidIdentityToken` and the message
`RoleSessionName in AuthnResponse must match [a-zA-Z_0-9+=,.@-]{2,64}`. AWS refuses an assertion
without the attribute; Floci accepts it, names the session after the assertion's subject, and leaves
`sts:RoleSessionName` out of the request context, so a trust policy that requires a session name
refuses it.

## Privileged Root Sessions

`AssumeRoot` returns "short term credentials you can use to perform privileged tasks on a member
account in your organization". The session acts as that member account's root user, which no
identity policy grants or denies, scoped by the `TaskPolicyArn` the request names: the five AWS
managed policies the model names for it, under `arn:aws:iam::aws:policy/root-task/`, each deny
everything they do not name, so the policy is the whole of the session's bound. Whose policy it is
counts as much as where it sits: a customer-managed policy can carry the same path, and installing
one would bound the session by whatever that document allows, so only AWS's own are accepted and
anything else is refused.

The caller has to be able to manage root access: the management account or a delegated
administrator for IAM, in an organization with All features and trusted access for IAM enabled, and
with `RootSessions` on. The target has to be a member account other than the management account,
privileged tasks being what the management account performs on members. `DurationSeconds` is 0 to
900 and defaults to 900, "the maximum session duration of 900 seconds (15 minutes)".

Three things are deviations rather than readings, since the operation models only
`RegionDisabledException` and `ExpiredTokenException`:

- Every caller, target and feature refusal is `AccessDenied` 403, and a task policy outside the
  root-task path is a `ValidationError`, which is the shape every other member violation answers.
- "You cannot use root user credentials to call sts:AssumeRoot" is the guide's rule; what is
  decided here is which credential that means. Both the one resolving to a root ARN, a privileged
  session opening another among them, and the bare 12-digit account-id key are refused, the latter
  because the enforcement filter already reads it as "floci's account-root principal, never an IAM
  user's key". A privileged session is therefore opened by an IAM user or role of the management
  account, as on AWS, rather than by the account itself.
- "The global endpoint is not supported for AssumeRoot" is refused when the request arrives on the
  partition's global STS host. A request to a local host carries no such name, and the signing
  region cannot tell the two apart, so there is nothing to refuse there.

`MinimumSessionTokenSize` pads the token to at least the size asked for, held to the 0 to 4096 the
model declares, "The value must not exceed 4,096 bytes", since the size asked for is the size
materialized. Out of that range is a `ValidationError` and a value that is no integer an
`InvalidParameterValue`, the pair `DurationSeconds` already answers. This was the first operation to
materialize the member and is no longer the only one, the five others that model it reading it
through the same writer.

What the session cost is published as the two members this response declares, `SessionTokenSize`
carrying the token's size in bytes and `SessionTokenUtilization` that size as the percentage of the
4,096-byte limit it consumes, which is what the reference defines each as. The
[IAM service doc](iam.md#what-a-sessions-token-costs-and-when-it-is-refused) carries the measure
behind both, this operation carrying no state into its token, and the deprecated member the pair
replaces is not declared here at all.

The audit reads the `IAMAuditRootUserCredentials` task policy allows name the member account's root
user when they are called with no `UserName`, which is what that policy's own `arn:aws:iam::*:root`
asks for. Floci keeps no user record for a root user, so those reads answer `NoSuchEntity` rather
than the credentials report AWS returns.

## Access Key Info

`GetAccessKeyInfo` answers the account an access key ID belongs to, and nothing about the key
itself: AWS states that the operation "does not indicate the state of the access key. The key might
be active, inactive, or deleted", so an inactive key answers its account the same as an active one.
A long-term `AKIA` key resolves through the account that holds it and a temporary `ASIA` key through
the session that minted it. The ID is read against its own shape first, the 16 to 128 characters of
`[\w]` the model declares, which answers `ValidationError` before any lookup.

An ID the account never issued answers `ValidationError` with status 400 and "Access key ID is not
valid.", a sentence carrying neither the ID nor a validation-error count. The operation models no
errors at all and the reference says only that "providing a deleted access key might return an
error that the key doesn't exist", so this answer comes from a capture from a real account on
2026-10-31, which answered an `AKIA` and an `ASIA` ID alike that way. It settles a question this
emulator had left open: AWS refuses an ID it never issued rather than reading the account out of
it, so minting IDs that carry their account would buy nothing here.

## Delegated Access Token

`GetDelegatedAccessToken` takes the exchange token an IAM delegation request released and answers
credentials in the customer's account, acting as the identity that approved the request, which is
the `AssumedPrincipal` it returns. Accepting a request records the identity that called it, and
that is the identity mapped to the delegated credential, so a request its owner forwarded to an
administrator for approval hands over the administrator's access rather than the owner's. The
credentials expire at the moment the customer released the token plus the request's
`SessionDuration`, not at the exchange, so the same token can be exchanged repeatedly and every
session it mints ends at the same time.

The credentials carry the permissions of that identity. AWS bounds them by the permission policy it
renders from the partner's registered policy template, which no API registers, so Floci bounds them
by the approving identity's own permissions: a user's policies and boundary, the account's access
where the account root approved the request, and nothing at all where that identity no longer
exists.

Only the account that filed the delegation request may exchange its token; any other account gets
`AccessDenied`. A token past its window, and a token nothing matches, both answer
`ExpiredTradeInTokenException`. `PackedPolicySize` is not returned, the model deprecating it and
stating it is not populated for this operation. See the delegation request section of the
[IAM service doc](iam.md) for the lifecycle that issues the token.

## Web Identity Tokens

`GetWebIdentityToken` answers "a signed JSON Web Token (JWT) that represents the calling AWS
identity", for authenticating to an external service that supports OIDC discovery. The issuer is
IAM's: outbound web identity federation mints one issuer URL per account, and the token names it as
`iss`, so the operation reads the switch behind that URL and answers
`OutboundWebIdentityFederationDisabledException` while the feature is off rather than IAM's own
`FeatureDisabled`. The declared constraints are read ahead of the switch, as everywhere else in
this package: `Audience` is required and holds 1 to 10 audiences of 1 to 1000 characters,
`SigningAlgorithm` is required at a fixed length of 5, `DurationSeconds` is 60 to 3600 and defaults
to 300, and `Tags` is at most 50 tags whose keys and values carry their own lengths and pattern. An
audience sent empty is an audience that is present, so it breaks the minimum length rather than
reading as absent, and an audience numbered but never sent is how an absent list arrives, so a
request naming none of them is the required member reading as null.

A session cannot outlive itself. `SessionDurationEscalationException` is what a caller holding
temporary credentials gets when the requested duration would run past the moment its own session
ends, which is the sentence the reference gives that error: "You cannot use this operation to
extend the lifetime of a session beyond what was granted when the session was originally created."
A long-term key names no session and is bounded only by the 3600-second maximum.

A temporary credential with no live session behind it is refused with `ExpiredTokenException`, the
wording the enforcement filter answers for an expired session, rather than read as a caller Floci
cannot identify. An unresolvable caller is the account root stand-in elsewhere in this package,
which is what `GetCallerIdentity` answers for an unknown key, and a token is not a request: its
subject is signed and outlives the call, so a session that has already gone does not get one
naming the account root. An unknown long-term key keeps the stand-in.

The claim layout is the one the IAM User Guide publishes. The standard OIDC claims sit at the top
level, `iss`, `aud`, `sub`, `iat`, `exp` and `jti`, with the caller's ARN as the subject, and the
custom claims are nested under the `https://sts.amazonaws.com/` namespace: `aws_account` for the
account the token was asked for, `source_region` for the region it was asked from, and the
request's tags under `request_tags`. The guide notes that "all these claims may not be present in
a token at the same time", so the organization, principal tag and session context claims it also
lists are left out rather than invented, and `request_tags` is absent from a token whose request
named no tags. The nesting is what keeps a tag off a claim STS sets, a tag named `exp` being a key
under `request_tags` rather than the claim that chooses the token's lifetime.

Two answers here are Floci's readings. `jwtAlgorithmType` publishes a fixed length of 5 and no enum, so a
five-character value that is neither `RS256` nor `ES384` is answered with the enum violation this
package renders for a value outside a documented set, the two values coming from the reference
rather than from the model. It is reported where the model lists that member, ahead of anything the
tags break, and a value the declared length already refuses is not read against the set as well.
The global endpoint is refused because the reference says "The
GetWebIdentityToken API is not available on the STS Global endpoint" without giving the refusal, so
it borrows the wording AWS publishes for `AssumeRoot`; a request to a local host names no such
endpoint and is not refused. `JWTPayloadSizeExceededException` is modelled and nothing published
says what the maximum payload is, so no size is invented here to refuse a request with.

The signing key is the account's, minted on first use per algorithm and stored, so a restart does
not invalidate a token already handed out. `RS256` signs with a 2048-bit RSA key and `ES384` with
P-384, whose signature is the raw `R || S` pair JWS defines rather than the DER sequence a default
ECDSA signer returns.

### Verifying a token

AWS publishes the verification keys at the issuer itself, under
`/.well-known/openid-configuration` and `/.well-known/jwks.json`. The issuer's host
(`https://<uuid>.tokens.sts.global.api.aws`) does not reach Floci, so the two documents are served
under a Floci route keyed by the issuer's UUID, its first host label:

| Route | Serves |
|---|---|
| `GET /_floci/sts/web-identity/<uuid>/.well-known/openid-configuration` | The OIDC discovery document |
| `GET /_floci/sts/web-identity/<uuid>/.well-known/jwks.json` | The account's verification keys |

A relying party fetches them without AWS credentials, as it would from AWS, and the UUID tells Floci
which account's keys to answer with. The token's `iss` keeps AWS's form, and the discovery document
names it as `issuer`, which is what a verifier compares, while `jwks_uri` points at the key route
above. Point the relying party's JWKS URL (or its discovery URL) at Floci:

```bash
ISSUER=$(aws iam get-outbound-web-identity-federation-info --endpoint-url $AWS_ENDPOINT_URL \
  --query IssuerIdentifier --output text)
ID=$(echo "$ISSUER" | sed -E 's#https://([^.]+)\..*#\1#')
curl -s "$AWS_ENDPOINT_URL/_floci/sts/web-identity/$ID/.well-known/jwks.json"
```

The key set holds one key per algorithm, in the shape of the sample the IAM User Guide gives: the
`ES384` key with `kty` `EC`, `alg` and `crv` `P-384`, and the `RS256` key with `kty` `RSA`, its
modulus and exponent. Both are minted on the first read if no token has used them yet, so a key set
cached before the first token already holds that token's key. AWS does not publish its discovery
document, so Floci's carries the members OpenID Connect Discovery requires and no more: `issuer`,
`jwks_uri`, `response_types_supported`, `subject_types_supported` and
`id_token_signing_alg_values_supported` (`RS256` and `ES384`).

The documents stay served while the feature is disabled. The issuer outlives a disable, and so do
the tokens already minted under it, which a relying party still has to verify until they expire.
AWS does not document this either way, so it is Floci's reading. An issuer no account was given
answers 404.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_STS_ENABLED` | `true` | Enable or disable the service |

## Session Token and Federation Token Permissions

`GetSessionToken` and `GetFederationToken` reject temporary callers with `AccessDenied` before issuing credentials,
including when IAM policy enforcement is disabled. Use a long-term IAM user or account key.
For an authenticated temporary caller, `GetFederationToken` returns
`Cannot call GetFederationToken with session credentials` with either enforcement setting.

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, credentials from `GetSessionToken` and `GetFederationToken`
are evaluated as the identity whose long-term key signed the call, as on AWS:

- `GetSessionToken` credentials have the permissions of that IAM user, or of the account root when its key minted them,
  still bounded by the account's service control policies. Whatever those allow, they reach no IAM operation unless an
  MFA device minted the session, and no STS operation but `AssumeRole` and `GetCallerIdentity`; see [MFA](#mfa).
- `GetFederationToken` credentials have no permissions without a session policy (`Policy`). With one, they have the
  intersection of that policy and the permissions of the IAM user that minted them, never those of a role that shares
  the federated user's name. A resource policy naming the federated user's ARN can grant access on its own, but not
  past an explicit `Deny` in the user's policies or permissions boundary. Whatever their policies allow, they cannot
  call any IAM operation, nor any STS operation except `GetCallerIdentity`.
  Managed session policies (`PolicyArns`) are not supported yet.

A session minted with another session's temporary credentials, which AWS does not accept here, with an inactive access
key or one that belongs to another account, or by an IAM user since deleted and recreated under the same name, is not
a valid credential: it is refused like an unknown access key. A key IAM does not know anywhere mints sessions that act
as the account root, as the key itself does.

## MFA

`GetSessionToken` and `AssumeRole` read `SerialNumber` and `TokenCode`. AWS describes the pair on
both: `SerialNumber` is "the identification number of the MFA device that is associated with the IAM
user who is making the GetSessionToken call", and `TokenCode` is "the value provided by the MFA
device", six digits. Floci holds the code to the seed of the virtual MFA device that serial names,
with the one step of drift `EnableMFADevice` allows, and the device has to be enabled for the
identity that signed the call: a serial naming a device enabled for somebody else authenticates
nobody. The window is the wall clock's, as it is on the IAM actions that take codes, since the code
comes from a device keeping time of its own rather than from the emulator's clock.

A call that names neither parameter claims nothing and keeps whatever its own credentials carry, so
a session minted behind a device assumes a role as an MFA-authenticated caller without naming the
device again: "because temporary credentials returned by `GetSessionToken` include MFA information,
you can check for MFA in individual API operations made by the credentials". A call that names
either one is a claim of MFA and has to make it good, a code naming no device and a device with no
code both proving nothing, and the code is spent once it authenticates: RFC 6238 has a verifier
"MUST NOT accept the second attempt of the OTP generated for the same time step", so one code mints
one session rather than every session its window allows. A key IAM holds nowhere is the one caller
whose claim is taken as made, there being no identity behind it whose devices could be checked,
which is the reading Floci already takes of such a key everywhere else. A claim that fails is
refused with `AccessDenied` (403),
which is the outcome AWS documents ("an incorrect MFA code causes the API to return an access denied
error", and for `AssumeRole` "if the role being assumed requires MFA and if the TokenCode value is
missing or expired, the AssumeRole call returns an access denied error"). The sentence Floci writes
with it is its own, the one the IAM actions already write for a code that does not match, since AWS
publishes no message for this refusal.

What the authentication then buys differs by operation, and AWS draws the line itself. A
`GetSessionToken` session carries it onward: a request signed with it has
`aws:MultiFactorAuthPresent` as `true` and `aws:MultiFactorAuthAge` as the seconds since the device
was used, which [IAM](iam.md#condition-keys-floci-populates) populates, "because temporary
credentials returned by `GetSessionToken` include MFA information, you can check for MFA in
individual API operations made by the credentials". An `AssumeRole` session does not: "the temporary
credentials returned by `AssumeRole` do not include MFA information in the context, so you cannot
check individual API operations for MFA", and "once the user has assumed the role, no further MFA
checks are made". So a role session publishes the key as `false` whatever device got it issued, and
Floci records none on it.

The device a call presents is part of that request's own context, which is how a long-term key
reaches an MFA-protected role at all. A trust policy may require it, AWS's own example being
`"Condition": {"Bool": {"aws:MultiFactorAuthPresent": true}}`, and then "if the caller does not
include valid MFA information, the request to assume the role is denied". The caller's own
permission to make the call is read against that same context, since AWS denies such a request when
"users have not been MFA authenticated, or if they provide an invalid MFA device identifier or
invalid TOTP", so an identity policy conditioned on MFA for `sts:AssumeRole` is satisfied by the
device the call carries.

What a session token then reaches is narrower than its policies, and under
[enforcement](iam.md#iam-enforcement-mode) Floci holds it to that. The reference limits these
credentials twice over (`GetSessionToken`, "Permissions"): "You cannot call any IAM API operations
unless MFA authentication information is included in the request", and "You cannot call any AWS STS
API except AssumeRole or GetCallerIdentity". So a session minted behind a device calls IAM as its
own user may, one minted without a device is refused every IAM operation whatever its policies
allow, and neither reaches an STS operation other than `AssumeRole` and `GetCallerIdentity`. The
refusal is `AccessDenied` with `Cannot call <Operation> with session credentials`, the sentence
`GetSessionToken` and `GetFederationToken` already answer a session caller with, and the IAM half
adds `that include no MFA information` to name what the credentials do not carry. AWS publishes no
sentence for either refusal, so both are Floci's own wording for the case.

Two readings of the rule differ, and Floci follows the API reference. The IAM User Guide's
"Choosing between GetSessionToken and AssumeRole" compresses the pair into one sentence, that such
credentials "can access IAM and AWS STS API operations *only* if you include MFA information in the
request for credentials", which reads as though a device opened the rest of STS as well. The
reference publishes the two bullets apart and conditions only the IAM one on MFA, so here a device
buys IAM and no more of STS. The two readings agree on a session that carries no device.

These limits are read where every other authorization decision is, so they apply under
`FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` and nowhere else, while the two operations that take
long-term credentials only refuse a session caller with either setting, as above. An assumed-role
session carries none of them: AWS keeps `GetFederationToken` and `GetSessionToken` from it and
nothing further, and those two operations turn it away themselves.

## Trust Policy Enforcement

`AssumeRole` and `AssumeRoleWithWebIdentity` require the target role to exist in IAM, returning `AccessDenied` (403)
if it does not.

`AssumeRole` rejects a `DurationSeconds` above the role's `MaxSessionDuration` with
`ValidationError` (400), after trust validation and before registering credentials. The exact
role limit is accepted; an omitted duration defaults to 3600 seconds. This role setting is
checked even when IAM policy enforcement is disabled.

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, `AssumeRole` also evaluates the target role's trust policy
(`AssumeRolePolicyDocument`) against the caller and returns `AccessDenied` if it is not permitted. AWS principal
forms are matched: `"*"`, an account id, an account-root ARN (`arn:aws:iam::<acct>:root`), and exact principal ARNs;
and an explicit `Deny` always wins. Both `Action` and `NotAction` elements are honored when matching `sts:AssumeRole`.
Unless the trust policy grants the call on its own within the role's account (below), the caller's own identity
policy must allow `sts:AssumeRole` too, evaluated against the role's ARN, so a policy can
allow assuming one role and not another. Other STS actions are evaluated against `*`, whatever `RoleArn` they carry.
That evaluation sees the request's `sts:RoleSessionName`, and `sts:ExternalId` when the caller passes `ExternalId`,
as the trust policy does: the [Service Authorization Reference](https://docs.aws.amazon.com/service-authorization/latest/reference/list_awssecuritytokenservice.html)
lists both among the condition keys of `AssumeRole`, so an identity policy can require a session name, such as
`"sts:RoleSessionName": "${aws:username}"`, or an external ID, and a `Deny` conditioned on either applies only when
it matches.

The trust policy is the role's resource-based policy, and IAM enforcement evaluates it as it does a bucket or a
queue policy, with the role as its resource, since a trust policy names no `Resource`. Within the role's account, an
`Allow` that names the caller grants the call without an identity policy of the caller's: the
[AssumeRole API reference](https://docs.aws.amazon.com/STS/latest/APIReference/API_AssumeRole.html) lets a user
assume a role in the same account either through "a policy to the user that allows the user to call `AssumeRole`
(as long as the role's trust policy trusts the account)" or by adding "the user as a principal directly in the
role's trust policy", because "the role's trust policy acts as an IAM resource-based policy. When a resource-based
policy grants access to a principal in the same account, no additional identity-based policy is required". The same
holds for a role session whose role, or session, the trust policy names, and for `"*"`. A grant to an IAM user's
exact ARN is not limited by the user's permissions boundary either
([policy evaluation logic](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic_policy-eval-denyallow.html):
"not limited by an implicit deny in an identity-based policy or permissions boundary"), while a grant to a role's
ARN is ("limited by an implicit deny in a permissions boundary or session policy"). A trust policy that names the
account (its id or its root ARN) still needs the caller's identity policy. For a role in another account, both the
caller's identity policy and the trust policy have to allow the call
([cross-account policy evaluation](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic-cross-account.html):
"The request is allowed only if both evaluations return a decision of `Allow`"), so IAM enforcement refuses an IAM
user or a role session the trust policy does not allow before STS does. An explicit `Deny` in the trust policy, in
any of the caller's policies or in the account's SCPs refuses the call.

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

`aws:PrincipalTag/<key>` contains the stored tags of the calling IAM user or assumed role, with
the session tags its credentials were minted with over them, as [Session Tags](#session-tags)
below describes. The target role's tags do not supply the caller's principal tags. A chained
request reads the current caller role's tags and its own session tags; tags on the user that
originally assumed it are not inherited, and neither are the transitive session tags AWS carries
into a chained session.

A trust policy with a statement whose `Effect` is not exactly `Allow` or `Deny`, or whose
`Condition` is not an object of non-empty operator objects, lets no one assume the role, the same
on `AssumeRole`, `AssumeRoleWithWebIdentity` and `AssumeRoleWithSAML`. IAM refuses such a document
when it is written; Floci stores it as given, so the whole document is refused when it is read.

### Session Tags

A session carries tags of its own, and its requests publish them as `aws:PrincipalTag/<key>`:
"when you request a session, you can specify principal tags as the session tags. The tags apply to
requests that you make using the session's credentials." Each operation takes them where AWS
publishes them for it, which the model says the same way, three of the six declaring no `Tags`
member at all:

| Operation | Where a session tag comes from |
|-----------|-------------------------------|
| `AssumeRole` | the request's `Tags` member |
| `AssumeRoleWithSAML` | the assertion's `https://aws.amazon.com/SAML/Attributes/PrincipalTag:<key>` attributes |
| `AssumeRoleWithWebIdentity` | the token's `https://aws.amazon.com/tags` claims, in either published format |
| `GetFederationToken` | the request's `Tags` member |
| `GetSessionToken`, `AssumeRoot` | nothing: neither declares a way to pass one |

A role's trust policy has to permit the tagging as well as the assume: "when using session tags,
trust policies for all roles connected to the identity provider passing tags must have the
sts:TagSession permission. For roles without this permission in the trust policy, the AssumeRole
operation fails." All three assume operations read their trust policy for `sts:TagSession` when the
request, the assertion or the token carries a tag, with the same principals and conditions as the
assume itself, and answer `AccessDenied` naming that action when it is not allowed. An untagged
request is not held to it. `AssumeRole` reads its trust policy under the enforcement this section
describes, so the permission is read there too; the assertion and the token are read against their
trust policy on every call, as they always have been.

The caller's own policy is read for it as well, and that half was already here: the generated
tagging table has `sts:AssumeRole` and `sts:GetFederationToken` requiring `sts:TagSession` of a
request that carries `Tags`, which [enforcement](iam.md#iam-enforcement-mode) reads before the
operation runs, answering `User is not authorized to perform: sts:TagSession`. So a tagged
`AssumeRole` is held to both permissions, the caller's and the role's, and a tagged
`GetFederationToken` to the caller's alone, having no trust policy to read. The two operations that
take their tags from an assertion or a token carry no `Tags` member, so nothing asks their caller's
policy for the action and the trust policy is the whole of it.

The session's tags sit over the tags of the identity it acts as. For a role session that is the
role's own tags, for a federated user the tags of the user whose key minted it, "the session
principal tags include the user tags and the passed session tags", and a session tag "overrides the
role" tag of the same key. The key is matched without case, as IAM matches a tag key on a user or a
role, so a session passing `Department` replaces a `department` the role carries and the session's
own spelling is the one published. Two tags in one request whose keys differ only in case are one
tag, the later winning, for the same reason: "you cannot have separate Department and department
tag keys", and a second such tag "replaces the first tag".

A tag carries exactly one value, since "you must pass a single value for each session tag" and AWS
STS "does not support multi-valued session tags". The token's nested format writes that value as an
array holding one string and its flattened format as a plain claim, and both are read. Anything
else is refused rather than read as the first value or dropped: a `PrincipalTag:` attribute with
several `AttributeValue` elements or none answers `InvalidIdentityToken` with
`Session tag <key> must carry exactly one AttributeValue: a session tag takes a single value.`, one
whose name ends at the colon answers
`A PrincipalTag attribute must name the tag key after the colon.`, and a token whose tag claim is
neither a string nor a one-element array is refused the same way. Those three sentences are Floci's
own: nothing published says what AWS answers a malformed session tag, and a tag a policy would have
been evaluated with cannot go missing quietly. An assertion's value is stored as it was signed,
spaces included, tag values being allowed them.

Session tags are state the token carries, so they count against the
[4,096-byte limit](iam.md#what-a-sessions-token-costs-and-when-it-is-refused) and are reported in
`SessionTokenSize` and `SessionTokenUtilization`. For the two operations that take them from a
message rather than from a request member, that is measured once the assertion or the token has
been verified, which is the first point the tags exist, rather than before the operation runs where
the declared members are read.

Two published pieces of this are not here yet. The transitive tag keys an assertion or a token
carries beside the tags are not read, so a tag never passes into a chained session: that is AWS's
default, "by default, AWS STS does not pass tags to subsequent role sessions", and the opt-in is
missing. And the plaintext limits a session tag is held to, 50 tags with keys of 128 characters and
values of 256, are read where the model declares the members that carry them, which leaves the
number and the length of the tags inside an assertion or a token unchecked, nothing published
saying what AWS answers when those break; what does hold them is the token size above.

### Known limitations

- **The tag and source-identity condition keys are not in the request context.**
  `aws:RequestTag/*`, `aws:TagKeys`, `sts:TransitiveTagKeys` and `sts:SourceIdentity` are not
  populated, so a condition on them does not match. A trust policy keyed on one of them therefore
  reads as unsatisfied, including on the `sts:TagSession` permission a tagged assume needs, and
  `sts:SetSourceIdentity` is not checked at all.

## Web Identity Validation (IRSA)

`AssumeRoleWithWebIdentity` validates tokens minted by an OIDC issuer Floci hosts - currently an EKS cluster's IRSA provider.

When the token's `iss` names a known Floci issuer, all of the following are enforced:

- the RS256 signature, against the issuer's public key
- `iss` matches that issuer exactly
- `aud` contains `sts.amazonaws.com`
- `exp` / `nbf`, with 60s of clock-skew tolerance
- the role's trust policy: `Principal.Federated` plus the `Condition` block, comparing `<oidcProvider>:sub`, `<oidcProvider>:aud` and `sts:RoleSessionName` (the request's `RoleSessionName`) with exact, **case-sensitive** equality (`StringEquals`, `StringNotEquals`, `StringLike`, and `StringNotLike` are supported)

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
For `GetSessionToken` credentials they are those of the IAM user that minted them, and for `GetFederationToken`
credentials the `FederatedUser.Arn` and `FederatedUser.FederatedUserId` (`<account>:<name>`).
The response resolves both header and query-string credentials, including the query session token,
so a presigned request reports the same caller identity. A nonblank Authorization header retains
precedence over query credentials.

When `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true`, requests signed with the seeded access key (`floci` unless `FLOCI_SERVICES_IAM_SEED_DEPLOYER_ACCESS_KEY_ID` sets another) return `arn:aws:iam::000000000000:user/floci-deployer`. Other unknown local credentials continue to return the account root ARN for backward compatibility.

When `FLOCI_AUTH_VALIDATE_SIGNATURES=true`, the shared signature verifier checks presigned query
credentials for STS operations, including `GetCallerIdentity`, `GetSessionToken`, `GetFederationToken`
and `AssumeRole`. It checks the request signature, credential scope, expiry and session token before
execution. Nonblank Authorization headers retain priority. See [signature checks](../configuration/multi-account.md#signature-validation).

AssumeRoleWithWebIdentity rejects a requested duration above the target role's
`MaxSessionDuration` before issuing credentials. The default remains 3,600 seconds;
existing token and trust-policy errors take precedence over this role-specific check.

Role chaining limits `AssumeRole` to one hour, including when the target role permits a longer
session. A live role credential must carry its matching session token. `GetSessionToken` user
credentials are not role chaining. The cap is checked after trust and role-maximum validation,
before registering the new session.
