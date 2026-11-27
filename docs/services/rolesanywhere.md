# IAM Roles Anywhere

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/trustanchors` (SigV4 service `rolesanywhere`)

Floci emulates the IAM Roles Anywhere control plane: trust anchors, profiles, certificate revocation
lists (CRLs), subjects, the notification settings of a trust anchor, and tags. Every resource is
persisted and isolated by account and region. The credential exchange itself (`CreateSession`, which a
workload calls with its X.509 certificate through the `aws_signing_helper`) arrives in a later release.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateTrustAnchor` | Creates a trust anchor from a PEM certificate bundle or a self-signed repository. |
| `ListTrustAnchors` | Lists trust anchors in creation order, with `pageSize` and `nextToken`. |
| `GetTrustAnchor` | Returns one trust anchor, named by its id, its ARN or its alias ARN. |
| `UpdateTrustAnchor` | Changes a trust anchor's name, source, CRL distribution point flag or alias. |
| `DeleteTrustAnchor` | Deletes a trust anchor and the CRLs imported against it, returning the anchor. |
| `EnableTrustAnchor` | Enables a trust anchor. |
| `DisableTrustAnchor` | Disables a trust anchor. |
| `PutNotificationSettings` | Replaces the notification settings of the same event and channel. |
| `ResetNotificationSettings` | Restores the default notification setting for each key named. |
| `CreateProfile` | Creates a profile with its roles, managed policies, session policy and duration. |
| `ListProfiles` | Lists profiles in creation order, with `pageSize` and `nextToken`. |
| `GetProfile` | Returns one profile, named by its id, its ARN or its alias ARN. |
| `UpdateProfile` | Changes the members of a profile the request names. |
| `DeleteProfile` | Deletes a profile, returning it. |
| `EnableProfile` | Enables a profile. |
| `DisableProfile` | Disables a profile. |
| `PutAttributeMapping` | Replaces the mapping rules of one certificate field. |
| `DeleteAttributeMapping` | Removes the named specifiers of a certificate field, or the whole field. |
| `ImportCrl` | Imports a DER or PEM CRL against a trust anchor. |
| `ListCrls` | Lists CRLs in creation order, with `pageSize` and `nextToken`. |
| `GetCrl` | Returns one CRL, named by its id, its ARN or its alias ARN. |
| `UpdateCrl` | Replaces a CRL's data, name or alias. |
| `DeleteCrl` | Deletes a CRL, returning it. |
| `EnableCrl` | Enables a CRL. |
| `DisableCrl` | Disables a CRL. |
| `ListSubjects` | Lists the subjects that have requested credentials. |
| `GetSubject` | Returns one subject with its credentials and instance properties. |
| `TagResource` | Adds or replaces tags on a trust anchor, a profile or a CRL. |
| `UntagResource` | Removes tags from a trust anchor, a profile or a CRL. |
| `ListTagsForResource` | Lists the tags of a trust anchor, a profile or a CRL. |
<!-- floci:actions:end -->

## Behaviour

Resources are regional and named by a UUID. ARNs follow the AWS formats in the request's partition:
`arn:<partition>:rolesanywhere:<region>:<account>:trust-anchor/<uuid>`, `profile/<uuid>`, `crl/<uuid>`
and `subject/<uuid>`. Every `{trustAnchorId}`, `{profileId}` and `{crlId}` path label accepts the UUID,
the resource ARN or the alias ARN (`alias/trust-anchor/<aliasName>`, `alias/profile/<aliasName>`,
`alias/crl/<aliasName>`). An ARN of another account, region or partition names nothing here and is
answered with `ResourceNotFoundException`.

Requests are checked against the constraints of the AWS model (names, alias names, tag keys and
values, `durationSeconds` 900 to 43200, at most 250 `roleArns` and 50 `managedPolicyArns`, at most 50
notification settings with a `threshold` of 1 to 360, the `sourceType` and `certificateField` enums,
`x509CertificateData` up to 204800 characters, `crlData` up to 300000 bytes) and refused with a
`ValidationException` carrying the Smithy message the SDKs expect, every violation listed. A tag set
that would hold more than 200 tags is refused with `TooManyTagsException`. Errors are the REST JSON
shape Floci uses for every service, `{"__type": "<code>", "message": "..."}`.

Every timestamp is ISO 8601, to the second, as the model declares.

- **Trust anchors.** A `CERTIFICATE_BUNDLE` or `SELF_SIGNED_REPOSITORY` source must hold one or more PEM
  X.509 certificates; anything that does not parse is refused. `enabled` defaults to `true` and
  `crlDpRevocationEnabled` to `false`; both are stored and echoed. A new trust anchor carries the two
  default notification settings, `CA_CERTIFICATE_EXPIRY` and `END_ENTITY_CERTIFICATE_EXPIRY`, each
  enabled with a threshold of 45 days on channel `ALL` and configured by the service principal.
  `PutNotificationSettings` replaces the setting of the same event and channel and records the
  caller's account as `configuredBy`; `ResetNotificationSettings` restores the default. Deleting a
  trust anchor deletes the CRLs imported against it.
- **Profiles.** `durationSeconds` defaults to 3600, `enabled` to `true`, `requireInstanceProperties`
  and `acceptRoleSessionName` to `false`. `createdBy` is the ARN of the caller, the account's root ARN
  when the key belongs to no IAM user or role. `sessionPolicy` must be a JSON document.
  `tipConfiguration` is stored and echoed as given. A new profile starts with three attribute
  mappings: `x509Subject` and `x509Issuer` with the specifiers `CN`, `OU`, `O`, `C`, `ST` and `L`, and
  `x509SAN` with `Name/DNS` and `Name/URI`. That default list could not be checked against a live
  account; it is kept in one constant so a correction is a one-line change.
- **CRLs.** `crlData` is base64 in JSON, as the protocol carries a blob, and the content may be DER or
  PEM; data that does not parse as an X.509 CRL is refused. `trustAnchorArn` must name an existing
  trust anchor of the caller, by its ARN or its alias ARN (the model's pattern accepts both); the CRL
  records the anchor's resource ARN. `enabled` defaults to `true`.
- **Aliases.** An alias name is unique per resource type within an account and region; a clash is a
  `ValidationException`. An update that changes the alias records the one it replaced as
  `previousAliasName`, and an empty `aliasName` clears it.
- **Lists.** `pageSize` defaults to 50 and must be at least 1; `nextToken` is opaque and names the last
  item returned, so a page stays resumable when resources are added between requests.
- **Subjects** are written only by the credential exchange, so in this release `ListSubjects` is empty
  and `GetSubject` answers `ResourceNotFoundException`.
- **Tags** use the body shape of the model (`POST /TagResource`, `POST /UntagResource`,
  `GET /ListTagsForResource?resourceArn=`) with a list of `{key, value}` pairs. Trust anchors, profiles
  and CRLs are taggable; a subject is not. The tags are what an `aws:ResourceTag/<key>` condition reads
  under IAM enforcement.

IAM treats Roles Anywhere as any other service: `rolesanywhere` is in the vendored request-resource
table, so an action is checked against the trust anchor, profile, CRL or subject the call names rather
than against `*`, and a tag-conditioned policy reads the tags this service holds. A policy scoped to
a resource ARN matches whether the request names the resource by its UUID, its ARN or its alias ARN.

## Not emulated

- **Credential exchange.** `CreateSession` (`POST /sessions`) is not served yet: no workload can obtain
  credentials, and no subject is recorded.
- **AWS Private CA.** Floci does not emulate AWS Private Certificate Authority, so an `AWS_ACM_PCA` trust
  anchor is refused with a `ValidationException` saying the `acmPcaArn` cannot be resolved. Use a
  `CERTIFICATE_BUNDLE` source with the CA certificate instead.
- **Notifications.** Notification settings are stored and reported, but nothing is ever emitted to
  EventBridge, CloudWatch or the Health Dashboard.
- **CRL distribution points.** `crlDpRevocationEnabled` is stored and echoed; no distribution point is
  fetched.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ROLESANYWHERE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

anchor_arn=$(aws rolesanywhere create-trust-anchor \
  --name corp-ca \
  --source "sourceType=CERTIFICATE_BUNDLE,sourceData={x509CertificateData=$(cat ca.pem)}" \
  --query trustAnchor.trustAnchorArn --output text)

aws rolesanywhere create-profile \
  --name workloads \
  --role-arns arn:aws:iam::000000000000:role/workload \
  --duration-seconds 3600

aws rolesanywhere import-crl \
  --name corp-crl \
  --trust-anchor-arn "$anchor_arn" \
  --crl-data fileb://crl.pem

aws rolesanywhere get-trust-anchor --trust-anchor-id "$anchor_arn"
aws rolesanywhere list-tags-for-resource --resource-arn "$anchor_arn"
```
