# IAM

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## AWS Sign-In login credentials

Floci also implements the AWS Sign-In data-plane flow used by the AWS CLI login credentials
provider. The local `GET /v1/authorize` endpoint performs the emulator's local sign-in and returns
a one-time PKCE authorization code. `POST /v1/token` exchanges that code (or a refresh token) for
15-minute temporary SigV4 credentials registered with the local IAM account.

```bash
aws login --endpoint-url http://localhost:4566 --region us-east-1
aws sts get-caller-identity --endpoint-url http://localhost:4566
```

The authorize endpoint first presents a local consent page, then redirects back to the callback
listener owned by the AWS CLI. Floci does not contact AWS or require a real AWS account. This
follows the AWS Sign-In `AuthorizeOAuth2Access` and `CreateOAuth2Token` wire shapes, including
one-time codes, PKCE verification, refresh-token expiry, and the `aws_sigv4` temporary credential
type.

The cross-device flow works too, for a CLI that cannot open a browser on the same machine:

```bash
aws login --remote --endpoint-url http://localhost:4566 --region us-east-1
```

The CLI prints the authorize URL and waits for a code. After the consent page, Floci lands on
`/v1/sessions/confirmation` on the same endpoint, the path AWS Sign-In uses, and shows the
verification code to paste back. The redirect must point at the endpoint the request arrived on
or at the AWS Sign-In host of a region in the deployment's partition; any other origin is refused.

## Supported Actions

### Users

| Action | Description |
|--------|-------------|
| CreateUser | Creates an IAM user in the local account. |
| GetUser | Returns a stored IAM user. |
| DeleteUser | Deletes an IAM user from the local IAM store. |
| ListUsers | Lists IAM users in the local account. |
| UpdateUser | Updates mutable IAM user fields. |
| TagUser | Adds tags to an IAM user. |
| UntagUser | Removes tags from an IAM user. |
| ListUserTags | Lists tags stored for an IAM user. |

### Groups

| Action | Description |
|--------|-------------|
| CreateGroup | Creates an IAM group. |
| GetGroup | Returns an IAM group and its users. |
| UpdateGroup | Renames a group and/or changes its path; ARN and stored users move with it. |
| DeleteGroup | Deletes an IAM group from the local IAM store. |
| ListGroups | Lists IAM groups in the local account. |
| AddUserToGroup | Adds a user to an IAM group. |
| RemoveUserFromGroup | Removes a user from an IAM group. |
| ListGroupsForUser | Lists groups that contain a user. |

### Roles

| Action | Description |
|--------|-------------|
| CreateRole | Creates an IAM role with an assume-role policy. |
| GetRole | Returns a stored IAM role. |
| DeleteRole | Deletes an IAM role from the local IAM store. |
| ListRoles | Lists IAM roles in the local account, with `Marker` and `MaxItems` paging. |
| UpdateRole | Updates mutable IAM role fields. |
| UpdateRoleDescription | Modifies only a role's description and returns the modified role. |
| CreateServiceLinkedRole | Creates a role under /aws-service-role/ for a service principal. |
| DeleteServiceLinkedRole | Deletes a service-linked role and returns a deletion task id. |
| GetServiceLinkedRoleDeletionStatus | Returns the status of a service-linked role deletion. |
| UpdateAssumeRolePolicy | Replaces a role's assume-role policy document. |
| TagRole | Adds tags to an IAM role. |
| UntagRole | Removes tags from an IAM role. |
| ListRoleTags | Lists tags stored for an IAM role. |

`CreateRole` and every action that takes a `RoleName` (`GetRole`, `DeleteRole`, `UpdateRole`, `TagRole`, `AttachRolePolicy`, `PutRolePolicy` and the rest) check it as AWS does: 1-64 characters of `[\w+=,.@-]`, otherwise `ValidationError`. A colon is never valid, so no role can take the name of the EC2 instance identity role, `aws:ec2-instance`.

`UpdateRoleDescription` is the description half of `UpdateRole`, which is what AWS's own reference tells callers to use instead. Both of its members are required, so a request without `Description` answers `ValidationError` rather than leaving the role as it is, an empty `Description` is a value that clears the description, and the modified role comes back in the response, which `UpdateRole` does not answer at all.

A role `Description` is held to the shape AWS publishes for it on every action that takes one, `CreateRole`, `UpdateRole`, `UpdateRoleDescription` and `CreateServiceLinkedRole`: at most 1000 characters of `[\u0009\u000A\u000D\u0020-\u007E\u00A1-\u00FF]`, which admits tab, newline and carriage return and no other control character, otherwise `ValidationError`. The check runs before the role is looked up or created, so a refused description never reaches a stored role, and the `Role` these actions and `GetRole` answer always stays parsable XML.

### Role Templates

| Action | Description |
|--------|-------------|
| AcquireRole | Creates a role from a role template. Answers `NoSuchEntity` for every template ARN, since no account holds a template. |
| GetRoleTemplateVersion | Reads one version of a role template. Answers `NoSuchEntity` for the same reason. |

Role templates are AWS's own rather than an account's. The IAM User Guide has role manager using
"AWS-managed role templates", publishes a directory of template ARNs with the parameters each one
takes, and says versions are immutable, AWS publishing a new version rather than changing one. The
API agrees from the other side: `GetRoleTemplateVersion` is the only operation in the model that
names a template, and there is no create, list, update or delete, so nothing a client can call
puts a template into an account.

Floci therefore holds no templates, and both operations answer `NoSuchEntity` 404 naming the ARN
they were given. They are dispatched rather than left out because the alternative is worse: an
action Floci does not dispatch answers `UnsupportedOperation` 400, which neither operation models,
so a client receives an error its SDK cannot map, where the 404 is modeled by both.

This is Floci's reading of an API whose data AWS has not published, and these are the parts that
would change if it did. The guide names the template ARNs but not their bodies, so there is
nothing to answer `GetRoleTemplateVersion` with even for the ARNs in that directory; a published
body would turn those into answers and leave the 404 for every other ARN. `AcquireRole` also
models `RoleTemplateDisabled`, which Floci never answers: that error reads "the specified role
template is disabled", a statement about a template that exists, so an ARN naming none is the 404
instead. Role manager's own switch, the `RoleManager/Enabled` account property, is a separate
question and is not read here either.

The declared constraints are read before the template is looked for, which is the order AWS
validates in: `TemplateArn` is held to `arnType`'s 20 to 2048 characters, `MinorVersion` and
`TemplateMinorVersion` are held to being integers and answer `InvalidInput` for a value that is
not a number, and `ReplacementValues` is held to the 30 entries it declares and each of those
entries to the `Values` it is required to carry, between 1 and 20 of them. A request malformed in
any of those is a 400 rather than the 404, and one malformed in several is answered with every
violation at once, the map's own length ahead of its entries. An entry's values are counted by the
member being present rather than by what it holds, the list's members publishing no constraint of
their own, so a replacement value sent empty is a value.

The message for a broken entry names it by its key, as `replacementValues.Department.values`, which
is how IAM reports a bad value in its account properties map. No recorded AWS violation on a map
member is on hand to confirm that spelling, so it is Floci's reading of it: a capture would either
keep it or replace it with the nested form, where the path is the map itself and the constraint
reads "Map value must satisfy constraint".

### Policies

| Action | Description |
|--------|-------------|
| CreatePolicy | Creates a customer-managed IAM policy. |
| GetPolicy | Returns metadata for a managed IAM policy. |
| DeletePolicy | Deletes a managed IAM policy. |
| ListPolicies | Lists managed IAM policies, including seeded AWS managed policies, with `Marker` and `MaxItems` paging. |
| ListEntitiesForPolicy | Lists roles, users, and groups with a direct managed-policy attachment. |
| CreatePolicyVersion | Creates a new version of a managed policy. |
| GetPolicyVersion | Returns a managed policy version document. |
| DeletePolicyVersion | Deletes a non-default managed policy version. |
| ListPolicyVersions | Lists versions for a managed policy. |
| SetDefaultPolicyVersion | Sets the default version for a managed policy. |
| TagPolicy | Adds tags to a managed policy. |
| UntagPolicy | Removes tags from a managed policy. |
| ListPolicyTags | Lists tags stored for a managed policy. |

`ListEntitiesForPolicy` currently returns direct permissions-policy attachments. `EntityFilter`,
`PathPrefix`, `PolicyUsageFilter`, and pagination are not yet applied; responses return
`IsTruncated=false`.

`ListRoles` and `ListPolicies` return 100 items when `MaxItems` is omitted and reject a value
outside 1 to 1000. `PathPrefix` and `Scope` filter before paging, and roles and customer managed
policies are listed in name order so a `Marker` resumes where the previous page stopped.
`ListPolicies` does not yet apply `OnlyAttached` or `PolicyUsageFilter`.

The declared constraints on the managed-policy family are read from the model rather than from a
hand-written check, `tools/aws/regen_iam_shapes.py` vendoring them for `GetPolicy`, `DeletePolicy`,
`CreatePolicyVersion`, `GetPolicyVersion`, `DeletePolicyVersion`, `SetDefaultPolicyVersion` and the
six attach and detach operations. A `PolicyArn` is held to `arnType`'s 20 to 2048 characters, a
`VersionId` to `policyVersionIdType`'s pattern, a `PolicyDocument` to its 1 to 131072 characters and
its pattern, and each principal name to its own bound, which for a role is the 1 to 64 characters of
`[\w+=,.@-]` this service already answered. The check runs ahead of the operation, so a value that
breaks a constraint is a 400 `ValidationError` where it used to reach the store and answer
`NoSuchEntity` 404, the ordering the rest of this package already follows. A member sent empty is a
member the request carries, so it breaks its own constraints rather than reading as absent, and a
member breaking two of them is reported twice, as a `ServiceSpecificCredentialId` breaking both of
its own already is. That is the one place the wording moves rather than only the status: a name sent
empty breaks its minimum and its pattern at once, so these operations report both, where the
hand-written check the rest of the service still uses stops at the first and reports the minimum
alone. A boolean member publishes no constraint to hold it to, so a `SetAsDefault` that is neither
`true` nor `false` reaches the operation, which reads anything but `true` as false.

### Permission Boundaries

| Action | Description |
|--------|-------------|
| PutUserPermissionsBoundary | Sets a managed policy as a user's permissions boundary. |
| DeleteUserPermissionsBoundary | Removes a user's permissions boundary. |
| PutRolePermissionsBoundary | Sets a managed policy as a role's permissions boundary. |
| DeleteRolePermissionsBoundary | Removes a role's permissions boundary. |

### Policy Attachments

| Action | Description |
|--------|-------------|
| AttachUserPolicy | Attaches a managed policy to a user. |
| DetachUserPolicy | Detaches a managed policy from a user. |
| ListAttachedUserPolicies | Lists managed policies attached to a user. |
| AttachGroupPolicy | Attaches a managed policy to a group. |
| DetachGroupPolicy | Detaches a managed policy from a group. |
| ListAttachedGroupPolicies | Lists managed policies attached to a group. |
| AttachRolePolicy | Attaches a managed policy to a role. |
| DetachRolePolicy | Detaches a managed policy from a role. |
| ListAttachedRolePolicies | Lists managed policies attached to a role. |

### Inline Policies

| Action | Description |
|--------|-------------|
| PutUserPolicy | Stores or replaces an inline policy on a user. |
| GetUserPolicy | Returns an inline policy stored on a user. |
| DeleteUserPolicy | Deletes an inline policy from a user. |
| ListUserPolicies | Lists inline policy names stored on a user. |
| PutGroupPolicy | Stores or replaces an inline policy on a group. |
| GetGroupPolicy | Returns an inline policy stored on a group. |
| DeleteGroupPolicy | Deletes an inline policy from a group. |
| ListGroupPolicies | Lists inline policy names stored on a group. |
| PutRolePolicy | Stores or replaces an inline policy on a role. |
| GetRolePolicy | Returns an inline policy stored on a role. |
| DeleteRolePolicy | Deletes an inline policy from a role. |
| ListRolePolicies | Lists inline policy names stored on a role. |

Inline policies share an aggregate size limit per principal: 2,048 characters for a user,
5,120 for a group, and 10,240 for a role. Whitespace does not count. A replacement counts
instead of the previous document with the same policy name; exceeding the limit returns
`LimitExceeded` (HTTP 409) and preserves the stored policies. These limits match the
[AWS IAM quotas](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html).

When CloudFormation renames an inline policy, Floci releases its tracked old name on
retained principals before writing the replacement. Failed updates restore the captured
previous policies, including historical documents saved before these limits were enforced.
Compensation checks the current principal, document and mutation ownership before restoration.
A later write to the same policy prevents restoration even when it writes the same document;
nested compensations can still unwind in reverse order. Mutation ownership lasts only within
the current process and active rollback handles. Historical
oversized documents can be restored only when the other policies still match their captured
state. A failed compensation is reported as a rollback failure.

### Instance Profiles

| Action | Description |
|--------|-------------|
| CreateInstanceProfile | Creates an IAM instance profile, applying any `Tags` given at creation. |
| GetInstanceProfile | Returns an instance profile and its roles. |
| DeleteInstanceProfile | Deletes an instance profile from the local IAM store. |
| ListInstanceProfiles | Lists IAM instance profiles. |
| AddRoleToInstanceProfile | Adds a role to an instance profile. |
| RemoveRoleFromInstanceProfile | Removes a role from an instance profile. |
| ListInstanceProfilesForRole | Lists instance profiles associated with a role. |
| TagInstanceProfile | Adds tags to an instance profile. |
| UntagInstanceProfile | Removes tags from an instance profile. |
| ListInstanceProfileTags | Lists tags stored for an instance profile. |

`CreateInstanceProfile`, `GetInstanceProfile` and `ListInstanceProfilesForRole` include an instance
profile's own tags inline. `ListInstanceProfiles` omits them: like `ListRoles`, its own operation
documentation says "this operation does not return tags, even though they are an attribute of the
returned object", even though the `InstanceProfile` shape itself carries no such exclusion note.
Tags on a role embedded in `InstanceProfileList` are always omitted, matching `GetInstanceProfile`'s
own documented role subset.

### Access Keys

| Action | Description |
|--------|-------------|
| CreateAccessKey | Creates access-key credentials for a user. |
| GetAccessKeyLastUsed | Returns the stored last-used metadata for an access key. |
| ListAccessKeys | Lists access keys for a user. |
| UpdateAccessKey | Updates an access key's status. |
| DeleteAccessKey | Deletes an access key from a user. |

`UserName` is optional on `UpdateAccessKey`, `ListAccessKeys` and `DeleteAccessKey`: it defaults to
the user resolved from the signing access key, the same fallback `GetUser` uses.

`Status` takes any of the three values `statusType` publishes, `Active`, `Inactive` and `Expired`,
which is what the reference's Valid Values line on `UpdateAccessKey` lists, and it is held to that
set the same way `UpdateSigningCertificate`, `UpdateSSHPublicKey` and
`UpdateServiceSpecificCredential` hold theirs. It is read before the key is looked up, so a status
outside the set answers `ValidationError` whether or not the key exists. The `AWS::IAM::AccessKey`
CloudFormation resource takes the same three, its Allowed values line reading "Active | Inactive |
Expired" as well.

### Account Aliases

| Action | Description |
|--------|-------------|
| ListAccountAliases | Lists the alias set for the account, or an empty list when none is set. |
| CreateAccountAlias | Sets the account alias. An account can hold only one. |
| DeleteAccountAlias | Removes the account alias. |

An account holds one alias, and AWS enforces that by replacement rather than rejection:
`CreateAccountAlias` with a new value silently swaps the current one. `EntityAlreadyExists` means
the requested name is taken — on AWS that includes names held by other accounts, since aliases are
globally unique, but the store here is per-account so only "you already hold this one" arises.

`DeleteAccountAlias` must name the current alias; a mismatch returns `NoSuchEntity`. Both verbs
apply the same pattern constraint, so a malformed value returns `ValidationError` on either.
Aliases are 3–63 characters of lowercase letters, digits and hyphens, may not start or end with a
hyphen, and may not contain two hyphens in a row — AWS's documented
`^[a-z0-9]([a-z0-9]|-(?!-)){1,61}[a-z0-9]$`. The `ValidationError` message is reproduced from AWS
verbatim and does not itself mention the consecutive-hyphen rule.

Set `FLOCI_SERVICES_IAM_ACCOUNT_ALIAS` to seed an alias at startup, for callers that expect to
read one without creating it first. It seeds the **default account** only, so a caller signing
with a credential that resolves to a different account still reads an empty list. Seeding is
skipped when an alias is already stored, so under `storage.mode: persistent` a changed value has
no effect on later starts — the skip is logged at debug with both values. `/_floci/state/reset`
clears the alias without re-seeding it, as it does the optional deployer principal; the seed
returns on restart.

### Account Password Policy

| Action | Description |
|--------|-------------|
| GetAccountPasswordPolicy | Returns the account's password policy. |
| UpdateAccountPasswordPolicy | Replaces the account's password policy wholesale. |
| DeleteAccountPasswordPolicy | Removes the account's password policy. |

An account holds one password policy. `UpdateAccountPasswordPolicy` replaces it wholesale rather
than merging — a field the caller omits resets to its AWS-documented default (`false` for the
boolean requirements, `AllowUsersToChangePassword` and `HardExpiry`; `6` for
`MinimumPasswordLength`; unset for the optional `MaxPasswordAge` and `PasswordReusePrevention`)
rather than carrying over the previous value. Unlike the two optional integer fields,
`HardExpiry` is never absent from the response — AWS documents it as a boolean that always
defaults to `false`, so `GetAccountPasswordPolicy` always echoes it back. `ExpirePasswords` is
derived, not stored: it reports `true` exactly when `MaxPasswordAge` is set.

`GetAccountPasswordPolicy` and `DeleteAccountPasswordPolicy` both return `NoSuchEntity` when no
policy has ever been set — a documented, expected result the Terraform provider's
`aws_iam_account_password_policy` resource branches on. `MinimumPasswordLength` must be 6–128,
`MaxPasswordAge` 1–1095, and `PasswordReusePrevention` 1–24; a value outside those ranges is
rejected with `ValidationError`. The integer parameters (`MinimumPasswordLength`, `MaxPasswordAge`,
`PasswordReusePrevention`) and the boolean parameters both reject anything that isn't parseable —
a malformed integer or a value other than `true`/`false` (case-insensitive) returns
`ValidationError` rather than silently falling back to a default.

### OIDC Identity Providers

| Action | Description |
|--------|-------------|
| CreateOpenIDConnectProvider | Creates an OIDC identity provider from an https URL. |
| GetOpenIDConnectProvider | Returns a provider's URL, client IDs, thumbprints and tags. |
| ListOpenIDConnectProviders | Lists the ARNs of stored OIDC providers. |
| DeleteOpenIDConnectProvider | Deletes an OIDC identity provider. |
| AddClientIDToOpenIDConnectProvider | Adds a client ID (audience) to a provider. |
| RemoveClientIDFromOpenIDConnectProvider | Removes a client ID from a provider. |
| UpdateOpenIDConnectProviderThumbprint | Replaces a provider's thumbprint list. |
| TagOpenIDConnectProvider | Adds tags to a provider. |
| UntagOpenIDConnectProvider | Removes tags from a provider. |
| ListOpenIDConnectProviderTags | Lists tags stored for a provider. |

A provider is identified by its URL, so the ARN is derived from it rather than from a generated
id: `https://oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE` becomes
`arn:aws:iam::<account>:oidc-provider/oidc.eks.eu-central-1.amazonaws.com/id/EXAMPLE`. Creating
the same URL twice returns `EntityAlreadyExists`. As on AWS, `GetOpenIDConnectProvider` reports
the URL **without** its scheme.

The URL must begin with `https://` and is at most 255 characters. It is not normalized, matching
AWS: a trailing slash or a difference in case produces a separate provider rather than a
duplicate.

A provider holds at most 100 client IDs (`LimitExceeded` beyond that) and 5 thumbprints
(`InvalidInput` beyond that). Adding a client ID that is already present, and removing one that
was never added, both succeed and change nothing, as they do on AWS.

Thumbprints are stored and echoed back but never validated against the remote endpoint, since
nothing here performs the TLS handshake they describe.

### SAML Identity Providers

| Action | Description |
|--------|-------------|
| CreateSAMLProvider | Creates a SAML identity provider from a metadata document, with optional tags echoed back in the response. |
| GetSAMLProvider | Returns a provider's creation date, tags, and a metadata document rebuilt from its stored entity ID and signing certificate. |
| ListSAMLProviders | Lists the stored SAML providers of the calling account. |
| UpdateSAMLProvider | Replaces a provider's metadata document. |
| DeleteSAMLProvider | Deletes a SAML identity provider. |
| TagSAMLProvider | Adds tags to a SAML identity provider. |
| UntagSAMLProvider | Removes tags from a SAML identity provider. |
| ListSAMLProviderTags | Lists tags stored for a SAML identity provider. |

A provider is identified by name, giving an ARN of the form `arn:aws:iam::<account>:saml-provider/<name>`.
The name must match `[A-Za-z0-9+=,.@_-]{1,128}`, and creating the same name twice returns
`EntityAlreadyExists`. An empty or unparseable metadata document returns `InvalidInput`. `Tags` on the
create request are validated before anything is stored, so a request carrying more than 50 of them
fails without leaving a provider behind, as AWS documents.

Floci stores only the entity ID and signing certificate parsed from the metadata, so `GetSAMLProvider`
returns a minimal rebuilt document rather than the one that was uploaded. `UpdateSAMLProvider` re-parses
a new `SAMLMetadataDocument` the same way and replaces the stored entity ID and certificate; omitting it
leaves the provider unchanged, since it is optional on the request. AWS's current `UpdateSAMLProvider`
and `GetSAMLProvider` also manage an `AssertionEncryptionMode` and a private-key list for decrypting
encrypted assertions; Floci's assertion verifier only checks signatures against a single certificate and
does not model encrypted assertions at all, so neither of those is modeled here either.
`DeleteSAMLProvider` does not check or update any role whose trust policy still references the provider's
ARN, matching AWS's own documented behavior: the delete succeeds regardless, and it is a later
`AssumeRoleWithSAML` against the now-dangling ARN that fails, not this call. `CreateSAMLProvider` and
`GetSAMLProvider` return their tags sorted by key, which is what AWS documents for those two responses;
`ListSAMLProviderTags` is sorted the same way here for consistency, though AWS does not document an
order for it. Providers created here are used by `AssumeRoleWithSAML` for trust-policy and assertion
validation.

### Login Profiles

| Action | Description |
|--------|-------------|
| CreateLoginProfile | Creates a console password login profile for a user. |
| GetLoginProfile | Returns a user's login profile. |
| UpdateLoginProfile | Updates a user's login profile password and/or reset-required flag. |
| DeleteLoginProfile | Deletes a user's login profile. |
| ChangePassword | Changes the console password of the user who is calling. |

`UserName` is optional on `CreateLoginProfile`, `GetLoginProfile` and `DeleteLoginProfile`: it
defaults to the user resolved from the signing access key, the same fallback `GetUser` uses. It is
required on `UpdateLoginProfile`, matching the AWS API.

A user holds at most one login profile: `CreateLoginProfile` on a user that already has one
returns `EntityAlreadyExists`; `Get`/`Update`/`DeleteLoginProfile` on a user with none return
`NoSuchEntity`. `Password` is required on `CreateLoginProfile` and optional on
`UpdateLoginProfile`; an omitted field on `UpdateLoginProfile` (`Password` or
`PasswordResetRequired`) leaves that field unchanged, unlike `UpdateAccountPasswordPolicy`'s
wholesale replace. A password must be 1–128 characters from AWS's documented password character
class, and when the account has an [account password policy](#account-password-policy) set, it is
also checked against that policy's length and character-class requirements, with
`PasswordPolicyViolation` returned on either action if it doesn't comply. The password itself is never
echoed back by any of these actions, matching AWS.

`ChangePassword` is the self-service half of `UpdateLoginProfile`. It takes no `UserName` and acts
only on the user whose access key signed the request, which is what the API reference means by "the
IAM user who is calling this operation", and the account root password is not affected by it. A
caller with no IAM user behind its key, which is Floci's stand-in for account root credentials, is
refused with `InvalidUserType`, the error the CLI reference documents for that case. `OldPassword`
must match the stored password, and the reference publishes no error for a mismatch, so Floci
answers `InvalidParameterValue`, the common error type for a parameter value the service cannot
accept. It is checked before the new password is held to the account policy, so a caller who cannot
prove the current password learns nothing about what the policy requires. A successful change
clears `PasswordResetRequired`, which is what lets a user whose password an administrator reset
restore their own console access programmatically, and it updates the `password_last_changed` the
credential report reads.

`PasswordReusePrevention` is enforced on `ChangePassword` and on `UpdateLoginProfile`, which share
one history per login profile, so an administrator reset cannot put back a password a user has just
been refused. The reference defines the value as "the number of previous passwords that IAM users
are prevented from reusing", and says that with no value set users "are not prevented from reusing
previous passwords". The password the profile currently holds is refused alongside the superseded
ones, which the reference does not spell out: read the other way, the smallest window the policy
accepts would still allow setting a password to the value it already has, and would not refuse the
change away and straight back that the setting exists for. History is recorded whether or not a
window is set, so switching the policy on has something to compare against, bounded at the 24 the
policy can ask for, and it goes with the profile when `DeleteLoginProfile` removes it.

`AllowUsersToChangePassword` authorizes. The user guide says the option gives all users in the
account "access to the iam:ChangePassword action for only their user and to the
iam:GetAccountPasswordPolicy action", and that it "does not attach a permissions policy to each
user. Rather, IAM applies the permissions at the account-level for all users". So under
[enforcement](#iam-enforcement-mode) an IAM user carrying no policy at all may change its own
password and read the policy while the flag is set, and neither call is authorized once it is
cleared. The grant is assembled for the caller it applies to, naming that user's own ARN, so it
authorizes `iam:ChangePassword` for them and for nobody else, and it reaches no other action: not
`UpdateLoginProfile`, not `GetUser`. The flag that answers for a user is their own account's, so a
`PolicySourceArn` naming another account's user simulates with that account's grant, over that
account's user ARNs.

Being account-level rather than attached, the grant appears in nothing that lists a user's
policies, `ListUserPolicies`, `ListAttachedUserPolicies` and `GetAccountAuthorizationDetails`
included, nor in `ListPoliciesGrantingServiceAccess`, which reports policies by name and type and
has none to report here. A last-accessed report answers a different question, "every AWS service
that the resource could access using permissions policies", so `GenerateServiceLastAccessedDetails`
does list `iam` for a user carrying no policy of their own while the flag is set, and lists nothing
for that same user once it is cleared. `GetContextKeysForPrincipalPolicy` stays with the attached
set, returning "only context keys referenced by the identity-based policies attached to the
specified entity, and any additional policies that you provide", so it does not name the
`${aws:username}` the grant's own resource uses, which a simulation populates anyway. In
authorization it is an identity-based grant like any other: an explicit `Deny` still refuses the
call and a permissions boundary still caps it. It belongs to IAM users: a role session has no
console password to change, and the root user is not an IAM user.

`DeleteUser` returns `DeleteConflict`, as on AWS, while the user still has a login profile, access
keys, inline policies, attached managed policies, group memberships, an
[enabled MFA device](#multi-factor-authentication), a
[signing certificate](#signing-certificates), an [SSH public key](#ssh-public-keys) or a
[service-specific credential](#service-specific-credentials): remove those first. That is every
item on AWS's own list of what to delete before deleting a user programmatically. Renaming a user
with `UpdateUser` carries all of them to the new name, along with its login profile, access keys and
group membership. Unlike AWS, Floci does not rewrite policy documents that name the user's ARN, so a
resource or trust policy that referred to the old name still refers to it after a rename.

### Policy Simulation

| Action | Description |
|--------|-------------|
| SimulatePrincipalPolicy | Evaluates requested actions and resources against the resolved principal's policies. |
| SimulateCustomPolicy | Evaluates requested actions and resources against a standalone set of policy documents, with an optional permissions boundary. |
| GetContextKeysForCustomPolicy | Lists the context keys referenced across a set of policy documents. |
| GetContextKeysForPrincipalPolicy | Lists the context keys referenced across a resolved principal's policies, plus any additional documents supplied. |

`GetContextKeysForCustomPolicy` and `GetContextKeysForPrincipalPolicy` return every Condition
operator's key, and every `${...}` policy variable found in a Resource pattern or a Condition
value, in the order statements are found. A variable's default value (`${key, 'default'}`) is
stripped, and the three single-character escapes (`${*}`, `${?}`, `${$}`) are excluded, since
they substitute a literal character rather than naming a context key. The list is neither sorted
nor de-duplicated, matching AWS's own documented
example response, which repeats a key referenced by more than one statement.

`SimulatePrincipalPolicy` populates the principal keys itself when the policy source is an IAM
user, as AWS's simulator does: the guide lists `aws:PrincipalAccount`, `aws:PrincipalId`,
`aws:PrincipalType`, `aws:Type`, `aws:UserId` and `aws:UserName` as "populated for you", with the
caller providing "the values for all other condition keys", so a policy scoped to
`${aws:username}` simulates the way it is evaluated. What a simulation gets is the principal half
of the request context, the same keys the enforcement filter writes: `aws:PrincipalArn`,
`aws:PrincipalType`, `aws:username`, `aws:userid` and the account. `aws:PrincipalId` and
`aws:Type` are in no request context here, so a simulation does not populate them either; a
statement matching on one matches nothing, as it would on an enforced request. A value passed in
`ContextEntries` wins, being the one the call asked to simulate with, whatever case it spells the
key in. A role named as the policy source is not a session, so there is no session name for an
`aws:userid` and no documented `aws:PrincipalType` for it; those keys stay absent and a statement
matching on one does not match.

`PolicySourceArn` on `SimulatePrincipalPolicy` and `GetContextKeysForPrincipalPolicy` resolves an
IAM user or role only, not a group. It is held to the 20 characters `arnType` declares before it
is resolved, so a value too short to be any ARN is a `ValidationError` with status 400 naming the
length rather than an entity resolved out of whatever follows its last slash. A value that clears
20 characters and still parses as no ARN is resolved as before: the member publishes a length and
no pattern, and no source records what AWS answers for one, so inventing a refusal would be a
guess. The length wording follows the captures of real IAM length violations, naming only the
bound the value broke and leaving the value itself out of the message. `SimulateCustomPolicy`
accepts only one `PermissionsBoundaryPolicyInputList` document, matching AWS's own documented
limit; extra documents beyond the first are ignored. Neither simulation action evaluates a
resource-based policy (`ResourcePolicy`) or `OrderedOrganizationPolicyInputList`, and neither
returns `MatchedStatements`, `ResourceSpecificResults`, or a `PermissionsBoundaryDecisionDetail`:
only the top-level `EvalDecision` is populated. `ContextEntries.member.N.ContextKeyType` is
accepted but not read; the comparison is driven entirely by the policy's own condition operator
(`Bool`, `NumericEquals`, `DateEquals`, and so on), not by the declared type.

### Last-Accessed Reporting

| Action | Description |
|--------|-------------|
| GenerateServiceLastAccessedDetails | Starts an Access Advisor job for a user, group, role or managed policy and returns its job ID. |
| GetServiceLastAccessedDetails | Returns the job's status and its service-access report. |
| GetServiceLastAccessedDetailsWithEntities | Returns the job's status and the entities that used a given service. |
| ListPoliciesGrantingServiceAccess | Lists the policies that let an IAM identity access each requested service. |
| GenerateOrganizationsAccessReport | Starts a report of the services the SCPs along an Organizations entity path leave reachable, and returns its job ID. |
| GetOrganizationsAccessReport | Returns the job's status and its report. |

Floci does not record service access. Nothing populates a principal's usage history, and
`GetAccessKeyLastUsed` already answers with AWS's documented "never used" shape rather than
inventing one. That shape is what these actions reproduce: AWS lists every service an entity could
reach through its permissions policies, and for a service with no access attempt it leaves
`LastAuthenticated` and `TotalAuthenticatedEntities` null rather than dropping the service. So
`GetServiceLastAccessedDetails` returns a real, policy-derived `ServicesLastAccessed` list in which
every entry is that "no attempt" shape. The missing piece is the usage timestamps, not the list.

A grant that names no namespace is expanded against the vendored catalog of IAM service namespaces
in `src/main/resources/aws/iam-service-namespaces.json`: an `Action` of `*` reaches every published
service, a globbed prefix such as `s3*` reaches the ones it matches, and an `Allow` with `NotAction`
reaches everything the list does not carve out entirely. A namespace a policy names outright is
reported whether or not the catalog knows it, so a catalog that has fallen behind an AWS launch
still reports an explicitly named service; only wildcard expansion depends on it being current.

That catalog is generated by `tools/aws/regen_service_namespaces.py` from AWS's Service Reference
Information index, whose per-service `service` field is the IAM namespace itself. botocore is
deliberately not the source: a namespace is not any botocore field, since CloudWatch's
`endpointPrefix` is `monitoring` and its `serviceId` is `CloudWatch` against an IAM namespace of
`cloudwatch`. Because that index is a live endpoint rather than a pinned dependency, the gate is not
byte-equality: `make iam-namespaces-check` validates the file's shape offline, and
`make iam-namespaces-verify` checks online that every namespace in it still exists upstream, so
nothing invented can survive. Services AWS has added since the last `make iam-namespaces-sync` are
reported by that check without failing it, since being behind makes the list incomplete rather than
wrong.

`ServiceName` is a required member that AWS fills with a display name (`Amazon S3`); Floci has no
such mapping and repeats the namespace instead.

`GetServiceLastAccessedDetailsWithEntities` is policy-derived in the same way. AWS reports the
entities that *could have used* the reported permissions to reach a service, so a group report
lists the group's users, a policy report lists the users and roles the policy is attached to plus
the users of any group it is attached to, and a user or role report lists that entity. A user
reachable by more than one of those paths is listed once. Entities are omitted when the reported permissions do not
grant the requested service at all. `LastAuthenticated` is absent on every entry, since that is
the part Floci does not record.

`GenerateServiceLastAccessedDetails` resolves the ARN first and returns `NoSuchEntity` for one that
names nothing, rather than handing out a job ID that could never be meaningful. That resolution
checks the whole ARN: an ARN whose account is not the caller's, or whose path is not the resolved
entity's own, names no entity and is rejected rather than falling back to a same-named local one. A
job belongs to the account that created it, an unknown `JobId` is `NoSuchEntity`, and `Granularity`
is validated against `SERVICE_LEVEL`/`ACTION_LEVEL` and echoed back as `JobType`.

Declared constraints are read before any of those lookups, because the validation AWS runs ahead of
an operation never reaches its store: a request that breaks one is a 400 whether or not the job or
the entity exists, and checking it afterwards would answer `NoSuchEntity` 404 for a request AWS
refuses with a `ValidationError`. So `JobId` is held to the fixed 36 characters `jobIDType`
declares, `MaxItems` and `Marker` are read before the job they page is fetched, and `Granularity` is
read before the ARN it is filed against is resolved. A fixed length reports as one constraint,
"Member must have length equal to 36", which is the form Auto Scaling already uses for the same
shape, and the offending value is left out, which is how the most recent IAM length and pattern
recordings render one. Whether an id that clears the shape names a job is still a 404.

The report is produced when the job is created and stored with it, matching AWS, where
`GenerateServiceLastAccessedDetails` produces a report and the `Get*` operations retrieve that one.
So a completed job keeps answering what it answered first: editing the policies afterwards does not
change it, and deleting the entity it covers does not stop a valid `JobId` from resolving. There is
nothing left to compute asynchronously, so a job is complete when created and its creation and
completion timestamps are the same instant. Jobs are kept for the life of the store: AWS documents
no expiry for a `JobId`, so ageing them out would mean inventing a retention window and failing a
caller holding an ID that AWS would still answer.

Both readers honour `MaxItems` and `Marker` and report `IsTruncated` truthfully, returning a
`Marker` only when a page remains. This differs from the rest of this page, where pagination inputs
are accepted and ignored; these actions apply them rather than inherit that gap.

`ListPoliciesGrantingServiceAccess` is not an Access Advisor job and needs no usage history: AWS
defines it purely over permissions-policy logic, so it is answered from real policy content. It
follows AWS's documented scoping, where a user contributes its own managed and inline policies
plus those of every group it belongs to, while a group or role contributes only its own. Managed
policies are reported with their ARN and their current default version is the one read; inline
policies have no ARN and are identified by the entity holding them. Only `Allow` grants, so a
Deny-only policy is not listed, and an `Allow` with `NotAction` grants every service the list does
not carve out. Permissions boundaries are excluded, as the operation's documentation requires, and
resource-based policies, ACLs, Organizations policies and trust policies are not consulted.
Resources and conditions are not evaluated either: the question is which policies could grant the
service at all, not whether one specific call would be authorized.

The Organizations access report is the same job shape over a different question. Instead of one
entity's permissions policies it reports the services the service control policies along an
Organizations entity path leave reachable, and it is answered from Floci's own Organizations tree:
the entity path names the organization, its root and the OUs or account below, and the SCPs attached
to each of those are read and evaluated. Only SCP logic applies, which the reference is explicit
about, so identity policies, resource policies, ACLs, permissions boundaries and trust policies play
no part. The entity path has to be a path the organization actually has: the segments below the root
name a chain of parent and child, so the path is held to the one Organizations reports for its last
node, and a path assembled from two branches, or one that skips an OU, fails the job rather than
reporting the policies of a chain that does not exist.

Attached SCPs are a union within one level and the levels are intersected, so a service survives only
where every level allows it. That question cannot be answered a namespace at a time, because a
service is reachable only while some action in it survives the whole chain: two levels allowing
single disjoint actions, `s3:GetObject` under `s3:PutObject`, leave no S3 action at all, and a `Deny`
on the one action a level allows leaves none either. What a namespace-wide reading got right stays
right: an SCP denying `s3:DeleteBucket` alone keeps S3 on the list, because every other S3 action is
still reachable, and a `Deny` that carries a `Condition` or names particular resources closes nothing
at all, since the region-restricting deny on `*` that most organizations carry, and a deny scoped to
one bucket, both leave the service reachable by every request they do not cover. The survivors are
found by probing the chain with the actions the policies themselves name plus one stand-in action per
wildcard, which is exact for the patterns policies are written with; a wildcard that is not a
trailing one, as in `s3:*Object`, cannot be probed that way and leaves the service on the list rather
than off it.

The management account is not limited by SCPs, so its report lists every service and an
`OrganizationsPolicyId` passed with it is ignored, as the reference says. Passing that ID otherwise
narrows the report to the one SCP named, and it has to name one: Organizations keeps tag, backup,
chatbot and opt-out policies in the same ID space, none of them carries statements, and evaluating
one as a ceiling would read as a level that constrains nothing and report every service there is.

Every service in the report is one principals are allowed but never reached, since Floci records no
access, and that is the shape the reference's own example gives such a service: a namespace and a
zero count, with no region, entity path or last-authenticated time. `NumberOfServicesNotAccessed`
therefore equals `NumberOfServicesAccessible`. `ServiceName` carries a display name
("Amazon S3") that Floci has no mapping for, so the namespace stands in for it, as on the
last-accessed reader. `SortKey` is validated against its four values; the namespace keys sort the
list and the two time keys leave it in namespace order, because ordering on a timestamp no entry
carries would be ordering on nothing.

A caller who is not the management account gets `AccessDenied` 403. Neither operation models a code
for that, but a per-action error list is additive: the reference's common error types "that this AWS
service may return" apply to every action and include the 403 permission denial, and the IAM user
guide names the code for a denied IAM action outright, in the `AddRoleToInstanceProfile`
troubleshooting entry. The common error page spells it `AccessDeniedException` while the user guide
and the rest of Floci's IAM and STS surface spell it `AccessDenied`; the latter is used here, for
consistency with the surface a caller is already on.

Three things about this pair are inferences, and they are the same inference three times. The
generator models no error for an entity path that is well-formed but resolves to nothing, so a path
naming another organization, an entity outside this one, or a chain the organization does not have,
produces a `FAILED` job carrying `ErrorDetails` instead. Nor does it model one for the prerequisite
the reference states, that SCPs be enabled for the organization root, so an organization without them
produces a `FAILED` job too, carrying `PolicyTypeNotEnabled`. Nor one for an `OrganizationsPolicyId`
that names a policy of some other type, which fails the job carrying Organizations'
`InvalidInputException`. All three are read off the shape of the API rather than off a sentence: AWS
publishes both `FAILED` and `ErrorDetails` on this reader, for failures the generator does not
report synchronously, and the alternatives are all worse. Letting Organizations' own error escape
unchanged would put another service's code on an IAM operation, a `COMPLETED` job with an empty list
cannot be told apart from an organization that reaches no services, and `ValidationError` is for a
shape violation, which a resolvable-looking path, a disabled policy type and an existing policy of
the wrong type are not.

The generator's only modeled error, `ReportGenerationLimitExceeded`, reports concurrent generations
for the account and stays unreachable here: a job is complete the moment it is created, so two can
never overlap.

### Multi-Factor Authentication

| Action | Description |
|--------|-------------|
| CreateVirtualMFADevice | Creates an unassigned virtual MFA device and returns its `SerialNumber` and `Base32StringSeed`. |
| ListVirtualMFADevices | Lists the account's virtual MFA devices, filtered by `AssignmentStatus` (`Assigned`, `Unassigned` or `Any`, defaulting to `Any`). |
| DeleteVirtualMFADevice | Deletes a device. Returns `DeleteConflict` while it is still assigned to a user. |
| EnableMFADevice | Assigns a device to a user, after verifying two consecutive authentication codes. |
| DeactivateMFADevice | Detaches a device from its user, leaving the device itself in place. |
| ResyncMFADevice | Re-synchronizes an assigned device, again against two consecutive codes. |
| ListMFADevices | Lists the devices assigned to a user. |
| GetMFADevice | Reads one device by serial number. Answers `NoSuchEntity` for every serial, since the API accepts FIDO security key ARNs only and Floci mints virtual devices. |
| TagMFADevice / UntagMFADevice / ListMFADeviceTags | Manage a device's tags. |

The seed is real. `CreateVirtualMFADevice` generates a 160-bit secret from `SecureRandom` and
returns it as an RFC 4648 base32 string (base64-wrapped on the wire, as AWS models the member), so
an authenticator app seeded from it produces codes Floci accepts. `EnableMFADevice` and
`ResyncMFADevice` verify those codes as RFC 6238 TOTP (HMAC-SHA1 over 30-second steps, truncated
to six digits) and return `InvalidAuthenticationCode` when they don't match, so a caller that does
not hold the seed cannot enable a device. The two codes must be consecutive, as AWS asks ("a
subsequent authentication code"), so the same code sent twice is rejected. `EnableMFADevice`
allows one 30-second step of drift either side; `ResyncMFADevice` allows ten, since a device
needing resync is by definition one whose clock has wandered.

`SerialNumber` is the device ARN, `arn:aws:iam::<account>:mfa/<name>`, so `Path` and
`VirtualMFADeviceName` together identify a device. A device survives `DeactivateMFADevice` with its
seed intact, so re-enabling it needs no re-provisioning. `DeleteUser` returns `DeleteConflict` while
the user still holds a device, and a user with one reports `mfa_active` as `TRUE` in the credential
report. Renaming a user with `UpdateUser` carries the assignment to the new name, alongside the
login profile and access keys it already moved.

A user may hold up to 8 devices, the per-user limit the IAM User Guide documents, after which
`EnableMFADevice` returns `LimitExceeded`. That is the only MFA quota Floci enforces:
`CreateVirtualMFADevice` models `LimitExceeded` too, but AWS publishes no account-wide figure for
virtual MFA devices, so there is nothing to enforce it against.

Request shapes are checked before the device is resolved, so a `SerialNumber` outside its modeled
9-to-256 range is a `ValidationError` rather than a `NoSuchEntity` for a device that could not have
existed. Note that `VirtualMFADeviceName` is the one IAM name type with no documented maximum
length: a name longer than the 128 characters other IAM names stop at is accepted here, as on AWS.
`ListMFADeviceTags` honors `Marker` and `MaxItems`, sorting by tag key first as AWS documents, so a
client can walk the result a page at a time. IAM's other tag readers in Floci still return every
tag with `IsTruncated=false`.

`VirtualMFADeviceName` has no maximum length, but the serial number it mints does: 256 characters.
A name long enough to overflow that is rejected at creation rather than producing a device whose
serial every other MFA operation would refuse.

`QRCodePNG` is not returned. AWS marks it optional, and rendering a PNG would mean taking on a QR
encoder dependency for a field whose content is derivable: it encodes
`otpauth://totp/<device>@<account>?secret=<Base32String>`, which a caller can build from the
`Base32StringSeed` that *is* returned. `aws iam create-virtual-mfa-device` works against Floci with
`--bootstrap-method Base32StringSeed`, and fails only when asked for the QR code specifically.

`GetMFADevice` is dispatched and answers `NoSuchEntity` for every serial number. AWS states "for
this API, we only accept FIDO security key ARNs", and nothing in the IAM API creates a FIDO key:
`CreateVirtualMFADevice` is the only operation that mints a device here and it mints an `mfa/`
one, so there is never a device for this reader to find. A virtual device's serial is refused the
same way, deliberately, because answering with it would hand back a device AWS would not return
for the same request. That 404 is also the only refusal the operation models, together with
`ServiceFailure`, which is why it is dispatched at all: an action Floci does not dispatch answers
`UnsupportedOperation` 400, and no SDK models that for this operation, so a client gets an error
it cannot map. The declared shapes are read first, so a `SerialNumber` outside 9 to 256 characters
or outside its pattern is a `ValidationError`, and a request naming a user the account does not
hold reports that user instead.

Hardware TOTP tokens and FIDO security keys are not modeled, so `ListMFADevices` returns only
virtual devices where AWS would return every type.

#### What a device does not yet affect

Enabling a device records state and nothing more. It does not change what a request is allowed to
do:

- **Policy evaluation ignores MFA.** `aws:MultiFactorAuthPresent` and `aws:MultiFactorAuthAge` are
  never placed in the request context, so under [enforcement](#iam-enforcement-mode) a statement
  conditioned on either key does not behave as it would on AWS. Both directions fail closed rather
  than open: an `Allow` gated on `Bool: {"aws:MultiFactorAuthPresent": "true"}` never grants,
  because a missing key fails the condition block; and the common
  `Deny` + `BoolIfExists: {"aws:MultiFactorAuthPresent": "false"}` lockout idiom always denies,
  because `IfExists` passes on a missing key. So an MFA-gated policy is stricter than AWS here, not
  laxer, but a device being enabled will not unlock it.
- **No MFA-authenticated credentials.** `GetSessionToken` and `AssumeRole` accept `SerialNumber`
  and `TokenCode` on AWS and return credentials that carry the MFA context keys. Floci's
  [STS](sts.md) implementations ignore both parameters, so there is no way to obtain a session that
  would satisfy an MFA condition even once the keys are populated.

Both are out of scope here: this covers the device lifecycle only, and wiring MFA into
authorization means touching the request context and STS session shape, which is separate work.

### Account

| Action | Description |
|--------|-------------|
| GetAccountSummary | Returns entity counts (users, groups, roles, customer-managed policies, instance profiles, MFA devices) and IAM quota values. `Providers` counts OIDC providers only; SAML providers are not included. Resources Floci does not track (the account password) are reported as zero rather than omitted. |
| GetAccountAuthorizationDetails | Returns every user, group and role in the account, and the policies relevant to them: every local (customer-managed) policy, and every AWS-managed policy actually attached to or used as a permissions boundary by something in the account. |
| GenerateCredentialReport | Generates (or, within 4 hours of the last one, reuses) the account's credential report. |
| GetCredentialReport | Returns the most recently generated credential report as Base64-encoded CSV. |

`Filter`, `MaxItems` and `Marker` are not honored: the response always includes everything, with
`IsTruncated` always `false`. `AttachmentCount` and `PermissionsBoundaryUsageCount` are computed by
scanning the account's own users, groups and roles rather than read off a stored counter, so they
are correctly scoped to the calling account even for an AWS-managed policy (see the note on
`IamService.getAccountAuthorizationDetails` for why that distinction matters). Policy documents are
returned as plain JSON, not URL-encoded as AWS documents them; this matches every other IAM action
that returns a policy document (`GetPolicyVersion`, `GetRolePolicy`, and so on), none of which
URL-encode either.

The credential report holds the 23 columns AWS documents, always led by a `<root_account>` row.
Floci does not model root account credentials at all (`GetAccountSummary`'s
`AccountPasswordPresent`/`AccountAccessKeysPresent` are always zero for the same reason), so that
row is placeholder values throughout, including its `mfa_active`, which reports on root rather
than on any IAM user's device. X.509 signing certificates are not modeled, so every `cert_*` column
is always `FALSE`/`N/A`; access key last-used tracking (date, region, service) is not modeled, so
those three columns are always `N/A` too. `mfa_active` on a user row is real, and is `TRUE` once
the user has a device enabled. `password_last_used` is likewise not tracked, so it is always `no_information`.
`password_last_changed` reflects an `UpdateLoginProfile` password change, not just
`CreateLoginProfile`. `additional_credentials_info` is Floci's own wording, since AWS does not
document the exact text; in practice it is unreachable, since `CreateAccessKey` already enforces
the real 2-key-per-user quota. Generating a report is effectively instant, so `GenerateCredentialReport` never actually
returns `INPROGRESS`, and a `GetCredentialReport` call right after it always finds the report
ready. `GenerateCredentialReport`'s `State`/`Description` for the no-report-exists case match AWS's
own documented example response (`STARTED` / "No report exists. Starting a new report generation
task"); the wording for the report-expired case is Floci's own, since AWS does not document it.

### Account Properties and Outbound Federation

| Action | Description |
|--------|-------------|
| GetAccountProperties | Returns the account's properties as a `Namespace/PropertyName` to value map. |
| PutAccountProperties | Sets account properties, all of which must share one namespace. |
| SetSecurityTokenServicePreferences | Sets the account's global endpoint token version, `v1Token` or `v2Token`. |
| EnableOutboundWebIdentityFederation | Turns on outbound web identity federation and returns the account's issuer URL. |
| DisableOutboundWebIdentityFederation | Turns it off. |
| GetOutboundWebIdentityFederationInfo | Returns the issuer URL and whether JWT vending is on. |

A property key is `Namespace/PropertyName` with exactly one forward slash and neither a leading nor
a trailing one, 1 to 50 characters matching `^[A-Za-z][A-Za-z0-9/_-]*$`, and a value of 1 to 1024.
**Every key in one `PutAccountProperties` request must belong to the same namespace**, which AWS
states directly, so a request mixing namespaces is rejected whole rather than partly applied. Role
Manager is the only namespace AWS names, and any well-formed key is accepted rather than only that
one, since an allowlist of one would refuse keys AWS takes.

Those two rules are not the same kind of failure, and they answer differently. The length and the
pattern are constraints the API Reference publishes on the parameter, and it defines
`ValidationError` as the common error for input that "doesn't meet the required format or
constraints", so a key that is too long or outside the pattern, and a value outside 1 to 1024, are
`ValidationError` with status 400. The `Namespace/PropertyName` rule cannot be expressed in the
published pattern, which admits a key with no slash, with several, and with a trailing one, so it is
a rule applied once the format is already satisfied: that, and a request mixing namespaces, are
`InvalidInput` with status 400, the "invalid or out-of-range value" the operation declares.

Each `ValidationError` here opens with "1 validation error detected: ", which is the envelope the
IAM front end puts on a constraint report rather than anything specific to this operation. Every
such message recorded against AWS carries it, including on map members: SNS's recordings show
`Value null at 'attributes'` and `Value null at 'messageAttributes.attr1.member.dataType'` both
prefixed. What is not recorded anywhere is how AWS spells a map *key* constraint, so the
"Map keys must satisfy constraint" tail is a reasonable shape rather than an attested one.

**Enabling federation twice is an error, and so is disabling it twice**, which is the asymmetry
worth noticing: `EnableOutboundWebIdentityFederation` answers `FeatureEnabled` with status 409,
while `DisableOutboundWebIdentityFederation` answers `FeatureDisabled` with status **404**. AWS
documents both, including the message wording. `GetOutboundWebIdentityFederationInfo` answers
`FeatureDisabled` too when the feature is off, reusing the disable operation's own text, so a getter
reports that the feature cannot be disabled twice. That reads oddly and is matched rather than
improved.

The issuer URL is `https://<uuid>.tokens.sts.global.<dual-stack suffix>`, which the API Reference
shows as `https://a1d2b0fd-1177-4468-9351-2fEXAMPLE723.tokens.sts.global.api.aws`. The suffix comes
from the request's partition rather than the literal, since `api.aws` is only the commercial
partition's. It is minted once per account and kept across a disable, so re-enabling returns the
same URL. AWS does not document which way that goes; the reasoning is that a relying party
verifying tokens will have pinned the URL, so regenerating it would break verification silently.

Two things the feature references are not modeled. The issuer URL is documented as hosting OIDC
discovery endpoints at `/.well-known/openid-configuration` and `/.well-known/jwks.json`, and the
operation's description points at a `GetWebIdentityToken` API for obtaining JWTs. Neither is served
here: these six are the management operations only. `JwtVendingEnabled` is therefore reported as
true whenever the feature is enabled, which is the only reading available, since nothing in the API
toggles it separately.

`SetSecurityTokenServicePreferences` stores the version, and `GetAccountSummary` reports it as
`GlobalEndpointTokenVersion`, which the operation's own description requires. What does not follow
it is STS itself: Floci does not vary its token format by account preference, so the setting is
observable in the summary rather than in a token. `v1Token` is reported as the default for an account
that has never set one.

### Organizations Root Access

| Action | Description |
|--------|-------------|
| ListOrganizationsFeatures | Lists the centralized root access features that are currently enabled. |
| EnableOrganizationsRootCredentialsManagement | Enables the `RootCredentialsManagement` feature. |
| DisableOrganizationsRootCredentialsManagement | Disables the `RootCredentialsManagement` feature. |
| EnableOrganizationsRootSessions | Enables the `RootSessions` feature. |
| DisableOrganizationsRootSessions | Disables the `RootSessions` feature. |

Only the set of enabled features is stored, and enabling a feature twice is idempotent. Floci does
not model root credentials themselves, so `RootCredentialsManagement` changes what
`ListOrganizationsFeatures` returns and nothing else; `RootSessions` is what STS `AssumeRoot` reads
before opening a privileged session.

All five answer for one organization, so all five check the caller, each refusal being a shape the
reference models on them and on nothing else. In the order they are asked: no organization at all
is `OrganizationNotFoundException`, an organization without All features is
`OrganizationNotInAllFeaturesModeException`, one without trusted access for IAM in Organizations is
`ServiceAccessNotEnabledException`, and a caller that is neither the management account nor a
delegated administrator for IAM is `AccountNotManagementOrDelegatedAdministratorException`. The two
enable operations add `CallerIsNotManagementAccountException`, which is modeled on them alone, so a
delegated administrator may read the features and turn them off but not on. Each is 400 and carries
the shape name as its code: botocore declares no `error.code` for any of the five.

The feature set belongs to the organization rather than to whoever asked for it, so it is read and
written in the management account's scope and a delegated administrator sees what the management
account enabled. The order is chosen rather than read, and so is the account the record lives in; a
toggle set before this moved sat in the calling account's scope and is set again once. The
`OrganizationId` the model declares beside `EnabledFeatures` is answered now that the organization
is known either way.

### Server Certificates

| Action | Description |
|--------|-------------|
| UploadServerCertificate | Stores a PEM certificate, its private key and an optional chain under a name unique to the account. |
| GetServerCertificate | Returns a stored certificate and its chain, never the private key. |
| UpdateServerCertificate | Renames a certificate and/or changes its path; the ARN moves with it. |
| DeleteServerCertificate | Deletes a stored certificate. |
| ListServerCertificates | Lists certificate metadata, filtered by `PathPrefix`. |
| TagServerCertificate | Adds tags to a server certificate. |
| UntagServerCertificate | Removes tags from a server certificate. |
| ListServerCertificateTags | Lists tags stored for a server certificate. |

The uploaded material is really parsed, because two of this operation's modeled errors cannot be
answered otherwise. `CertificateBody` (and `CertificateChain`, when given) must be readable PEM or
the upload is `MalformedCertificate`, and the private key must actually match the certificate's
public key or it is `KeyPairMismatch`. The match is a sign-then-verify check, so it holds for RSA
and EC alike. `Expiration` is read from the certificate's own `notAfter` rather than stored
separately, so it cannot drift from the certificate it describes.

The private key is stored and never returned. AWS marks `privateKeyType` sensitive and models it
only on the upload, so neither `GetServerCertificate` nor `ListServerCertificates` echoes it back.
`ListServerCertificates` returns metadata only, as AWS documents: it "does not return the
certificate body, certificate chain, or private key".

`ServerCertificateId` uses AWS's `ASCA` prefix for certificates. `GetAccountSummary`'s
`ServerCertificates` count is backed by this store rather than reporting zero.

`DeleteServerCertificate` returns `DeleteConflict` while the certificate is in use, which is an
intentional deviation rather than a recorded AWS answer. `DeleteConflict` is modeled on the
operation, but the API Reference describes it only as "attached subordinate entities" without saying
what counts as one for a certificate, and the operation's own page warns that if Elastic Load
Balancing "doesn't detect the deletion of bound certificates, it may continue to use the
certificates", which reads as the delete going through. The refusal is kept because a caller is
better served by it than by a dangling reference, not because AWS is known to answer it.
Services that reference a certificate (ELB Classic listeners, ELBv2 listeners, CloudFront
distributions) report it through `ServerCertificateReferenceProvider`, which IAM consults without
depending on them. In the other direction, ELB Classic rejects a listener whose `SSLCertificateId`
names no certificate with `CertificateNotFound`, and CloudFront rejects an unknown
`ViewerCertificate.IAMCertificateId` with `InvalidViewerCertificate`.

Those two directions have to be one step, not two. A referring service checks the certificate and
then saves the resource that names it, so a delete running in between sees no reference, removes the
certificate, and leaves the resource holding something that is gone. CloudFront and ELB Classic
therefore perform that check and the write while holding the lock the delete takes, which leaves
only the two orders that make sense: the delete loses and answers `DeleteConflict`, or it wins and
the create is refused for a certificate that no longer exists. ELBv2 does not validate certificate
references at all, so a listener there can still name a certificate that was never present, even
though `CertificateNotFound` is modeled on `CreateListener`, `ModifyListener` and
`AddListenerCertificates`.

Only a write that names an IAM certificate takes the lock. A listener with no certificate, a
listener served by ACM, a removal, or a distribution without an `IAMCertificateId` cannot leave a
resource holding a deleted certificate, and making those wait on a certificate delete, or it on
them, would buy nothing. The delete reads a snapshot of each load balancer's listeners rather than
the live list, so an unguarded write cannot end its walk in `ConcurrentModificationException`
either, which no mapper covers and which would surface as an unmapped 500.

### SSH Public Keys

| Action | Description |
|--------|-------------|
| UploadSSHPublicKey | Stores an SSH public key against an IAM user and returns its generated `SSHPublicKeyId`. |
| GetSSHPublicKey | Returns a key in the encoding `Encoding` asks for, `SSH` or `PEM`. |
| ListSSHPublicKeys | Lists a user's keys as metadata, with `Marker` and `MaxItems` paging. |
| UpdateSSHPublicKey | Sets a key's status to `Active`, `Inactive` or `Expired`. |
| DeleteSSHPublicKey | Deletes one of a user's SSH public keys. |

AWS accepts the body "encoded in ssh-rsa format or PEM format", so both are read, and the body is
kept exactly as it arrived: a caller that uploaded PEM gets that PEM back rather than a re-encoding
of it. `GetSSHPublicKey` converts, because `Encoding` is required and decides the form of the
response, so a key uploaded as `ssh-rsa` comes back as PEM when PEM is asked for.

`Fingerprint` is the MD5 of the OpenSSH blob, which is what AWS reports here. It is deliberately not
the digest EC2 reports for the same key, which is taken over the DER: both are sixteen bytes of
colon-delimited hex, so the wrong one would look entirely plausible. The value is checked against
the worked example in the IAM API Reference rather than assumed.

Five keys per user, which the IAM service quotas give as "SSH Public keys per user" and mark as not
adjustable. A sixth upload is `LimitExceeded`, counted inside the same lock as the write so
concurrent uploads cannot both see room for the last slot. The minimum bit-length is 2048, as the
model documents.

The two rejection errors are kept apart the way the model separates them. A body in neither accepted
encoding is `UnrecognizedPublicKeyEncoding`; a body in a recognised encoding that will not parse, or
that carries a key type other than `ssh-rsa`, is `InvalidPublicKey`. An OpenSSH line is recognised by
its key-type token rather than by whether part of it happens to base64-decode, since short words
often do.

`DuplicateSSHPublicKey` is per user, not account-wide: AWS describes it as a key "already associated
with the specified IAM user", so two users may hold the same key. The comparison is on the
fingerprint, so the same key uploaded in the other encoding still counts as a duplicate.

`ListSSHPublicKeys` returns metadata only. AWS documents `SSHPublicKeyMetadata` as describing a key
"without the key's body or fingerprint", so neither appears in the list even though both are stored.

An SSH public key blocks `DeleteUser` until it is removed, which is one of the items AWS lists as a
prerequisite for deleting a user programmatically, and it follows the user across an `UpdateUser`
rename: left behind, a key would be stranded on a name that no longer exists, invisible to its owner
because listing goes through the user.

`UserName` is required on every one of these operations except `ListSSHPublicKeys`, where the model
marks it optional and it resolves from the access key that signed the request. That is the opposite
of the signing-certificate operations, where it is optional throughout.

Under [enforcement](#iam-enforcement-mode) these actions are evaluated against the owning user's
ARN, which is the resource type the Service Authorization Reference gives them, so a statement
naming one user binds to that user. `ListSSHPublicKeys` with `UserName` omitted names the user who
signed the request, resolved from the access key in the credential, so a statement naming that user
binds to it as well. The wildcard stands only when the signer is not an IAM user, an account's root
stand-in and a session credential having no user record to name.

### Signing Certificates

| Action | Description |
|--------|-------------|
| UploadSigningCertificate | Stores an X.509 signing certificate against an IAM user and returns its generated `CertificateId`. |
| ListSigningCertificates | Lists a user's signing certificates, with `Marker` and `MaxItems` paging. |
| UpdateSigningCertificate | Sets a certificate's status to `Active`, `Inactive` or `Expired`. |
| DeleteSigningCertificate | Deletes one of a user's signing certificates. |

A signing certificate is not a server certificate: it belongs to a user rather than the account, it
carries no private key, no name and no path, and the generated `CertificateId` is the only handle
to it. The body is parsed on upload, because `MalformedCertificate` cannot be answered without
reading the material, and the status starts as `Active`.

`UserName` is optional on all four operations. Left out, it resolves to the user owning the access
key that signed the request, which is what the model documents.

Two certificates per user, which the User Guide states directly: "Users can have up to two X.509
signing certificates, to make certificate rotation easier". A third upload is `LimitExceeded`. The
count is taken inside the same lock as the write, so concurrent uploads cannot both see room for
the last slot.

`DuplicateCertificate` is account-wide rather than per user: AWS describes it as "the same
certificate is associated with an IAM user in the account", so a second user cannot upload material
the first already holds. The comparison is made on the encoded certificate rather than the PEM
text, so the same certificate re-wrapped or re-indented still counts as the same one.

`UpdateSigningCertificate` accepts `Expired` as well as `Active` and `Inactive`. The parameter's
prose explains only the first two, but the API Reference gives all three as valid values.

A signing certificate blocks `DeleteUser` until it is removed, which is one of the items AWS lists
as a prerequisite for deleting a user programmatically. It also follows the user across an
`UpdateUser` rename: left behind, a certificate would be stranded on a name that no longer exists,
invisible to its owner because listing goes through the user.

The credential report's `cert_1_active` and `cert_2_active` columns are backed by this store
instead of always reporting `FALSE`. The matching `cert_*_last_rotated` columns report the upload
date, and `N/A` when the certificate is not `Active`, which is how the User Guide defines them.
`GetAccountSummary`'s `AccountSigningCertificatesPresent` is unaffected: it reports the account root
user's certificates, and Floci does not model root credentials.

Under [enforcement](#iam-enforcement-mode) these actions are evaluated against the owning user's
ARN. `UserName` is optional throughout, and a request that omits it names the user who signed the
request, resolved from the access key in the credential, so those bind to that user rather than to
`*`.

### Service-Specific Credentials

| Action | Description |
|--------|-------------|
| CreateServiceSpecificCredential | Generates a credential for one service against an IAM user and returns its generated `ServiceSpecificCredentialId`. |
| ListServiceSpecificCredentials | Lists a user's credentials, or every user's with `AllUsers`, optionally filtered by `ServiceName`. |
| UpdateServiceSpecificCredential | Sets a credential's status to `Active`, `Inactive` or `Expired`. |
| ResetServiceSpecificCredential | Replaces the secret half of a credential and returns the new one. |
| DeleteServiceSpecificCredential | Deletes one of a user's service-specific credentials. |

These are what AWS calls Git credentials when they are used with CodeCommit. A service that does not
support them is `NoSuchEntity` with status 404 and the message "No such service `<name>` is
supported for Service Specific Credentials".

That is not what the wire model would suggest. The model declares `ServiceNotSupportedException`,
code `NotSupportedService`, on both `CreateServiceSpecificCredential` and
`ListServiceSpecificCredentials`, and AWS never raises it: recordings validated against AWS answer
`NoSuchEntity` for a nonexistent service, for a name that is not a service principal at all, and for
a real service that simply does not support these credentials. A client written against the model,
catching the modelled exception, would catch nothing.

A bad `ServiceSpecificCredentialId` splits two ways on `UpdateServiceSpecificCredential`,
`ResetServiceSpecificCredential` and `DeleteServiceSpecificCredential`. The id is matched against
the model's `[\w]+` before anything goes looking for it, so one carrying characters outside that
class is a `ValidationError` with status 400 quoting the pattern, while an id that satisfies the
pattern and names no credential of that user is `NoSuchEntity` with status 404 and the message
"No such credential `<id>` exists". The published length of 20 to 128 is enforced the same way, and
an id breaking both constraints is reported as two validation errors rather than one. A length
violation names only the bound the id broke, "Member must have length greater than or equal to 20"
for a short one and "Member must have length less than or equal to 128" for a long one, which is
how every recorded IAM length violation reads; reporting both at once would refuse a short id for a
ceiling it is nowhere near.

A user that still owns a credential cannot be deleted: `DeleteUser` is `DeleteConflict` with status
409. Where access keys, policies and group memberships each get a message naming what is in the
way, this case does not, and AWS answers the generic "Cannot delete entity, must remove referenced
objects first." Deleting the credential first lets the user go.

**A credential has one of two shapes, decided by the service:**

| Service | Shape |
|---------|-------|
| `codecommit.amazonaws.com` | `ServiceUserName` + `ServicePassword` |
| `cassandra.amazonaws.com` | `ServiceUserName` + `ServicePassword` |
| `bedrock.amazonaws.com` | long-term API key: `ServiceCredentialAlias` + `ServiceCredentialSecret` |
| `aws-external-anthropic.amazonaws.com` | long-term API key |
| `cloudwatch.amazonaws.com` | long-term API key |
| `logs.amazonaws.com` | long-term API key |

That list is the subset AWS's documentation actually names. The User Guide's own enumeration falls
on a page boundary and is absent from the published PDF, so these come from two other passages: the
`iam:ServiceSpecificCredentialServiceName` condition-key section, which gives CodeCommit,
Keyspaces and Bedrock "with their
exact value formatting", and the worked `create-service-specific-credential` CLI block, which
creates the four long-term API key services. A service AWS supports that neither passage names would
be refused here. A legacy per-partition form of a supported principal is accepted and folded to the
universal one, so `bedrock.amazonaws.com.cn` works and comes back as `bedrock.amazonaws.com`; a bare
`codecommit` does not, because AWS documents these with their exact formatting.

Only the long-term API key services accept `CredentialAgeDays`, which the model restricts to
"services that support long-term API keys" and which sets the `ExpirationDate`. It is 1 to 36600
days. Without it the credential does not expire and no expiry is reported. A present but
unparseable value is a validation error rather than a silent absence, since answering it with a
credential that never expires is the opposite of what was asked for.

**A key past its `ExpirationDate` reports `Expired`.** The status is derived when it is read rather
than written back, so the value a caller set is left alone and an `UpdateServiceSpecificCredential`
to `Active` cannot make an expired key report as usable. Expiry outranks `Inactive` as well, since a
key past its expiry is finished either way and `Expired` says more.

This one is a judgement call rather than a sourced behaviour, and worth knowing about. AWS does not
document the transition: `Expired` is in the `statusType` enum, but that enum is shared with access
keys, SSH public keys and signing certificates, most of which have no expiry at all, and the API
Reference's prose describes only `Active` and `Inactive`. Of the two available guesses, reporting an
expired key as `Active` is the worse one, because it tells a caller the key works while the same
response carries the date saying it does not. Nothing authenticates with these credentials in Floci
either way, so the status is the only place the expiry can show.

The secret half, whichever shape it takes, is disclosed by `CreateServiceSpecificCredential` and
`ResetServiceSpecificCredential` and never again: `ListServiceSpecificCredentials` returns
`ServiceSpecificCredentialMetadata`, which the model defines without either secret. A reset keeps
the id, the service and the service user name or alias: only what authenticates with the credential
changes. Note that the User Guide calls the long-term key's secret `ServiceApiKeyValue` in prose;
that is not a wire name, and both the API Reference and the model call it
`ServiceCredentialSecret`.

`ServiceUserName` is derived the way AWS derives it, as the IAM user name, the account, and a `+n`
between them for the second credential: `anika-at-123456789012` and then
`anika+1-at-123456789012`, which are the API Reference's own examples. `ServiceCredentialAlias`
plays the same role for a long-term key, and AWS documents it only as including "the IAM user name
and a suffix containing version and creation information" without publishing a format, so the one
here is Floci's: `anika+v1-20261004`.

In both cases the version is the lowest one not currently in use rather than a count of what
exists, because a count repeats a name as soon as a credential is deleted out of order. Holding
only `anika+1`, a count of one would mint `anika+1` again and two live credentials would share the
name the caller authenticates with. It is taken inside the same lock as the write, so two
concurrent creates cannot both claim the same version.

**The quota is two per service, not two per user**: the User Guide gives "a maximum of two sets of
service-specific credentials for each supported service per IAM user", so a user may hold two for
each of the six while a third for any one of them is `LimitExceeded`. Unlike the SSH public key
limit, this one has no row in the IAM service quotas table, matching the User Guide's framing of it
as a fixed design limit rather than an adjustable quota.

`AllUsers` cannot be given together with `UserName`, which the model says in as many words, so
naming both is a validation error rather than one quietly winning.

`UserName` is required on `CreateServiceSpecificCredential` and optional on the other four, where it
resolves from the access key that signed the request. That is a third pattern again: the
signing-certificate operations take it optionally throughout, and the SSH key operations require it
everywhere but the list.

A service-specific credential blocks `DeleteUser` until it is removed, the last of the items AWS
lists as a prerequisite for deleting a user programmatically, and it follows the user across an
`UpdateUser` rename: left behind, a credential would be stranded on a name that no longer exists,
invisible to its owner because listing goes through the user. The `ServiceUserName` is left as it
was minted rather than re-derived from the new name, because it is what the caller authenticates
with and rewriting it would break a working credential; AWS does not document which way it goes, so
this is a choice rather than a sourced behaviour.

Under [enforcement](#iam-enforcement-mode) these actions are evaluated against the owning user's
ARN, which when `UserName` is omitted is the ARN of the user who signed the request, resolved from
the access key in the credential.

### Delegation Requests

IAM temporary delegation lets an AWS Partner ask a customer for scoped, time-limited access to the
customer's account. The partner files a request, the customer claims and answers it, and an accepted
request releases an exchange token the partner trades for credentials.

The deep link `CreateDelegationRequest` returns points at the IAM console on the host of the
request's own partition, so a partner in China or GovCloud sends the customer to a console that
exists there. AWS publishes the field's purpose and its length but no format, and Floci serves no
approval page of its own: the customer side of the workflow is the API.

| Action | Description |
|--------|-------------|
| CreateDelegationRequest | Files a request for the calling account, returning its id and a console deep link. |
| AssociateDelegationRequest | Claims an unassigned request for the calling identity. |
| AcceptDelegationRequest | Approves a claimed request, recording the approver. |
| RejectDelegationRequest | Refuses a request, keeping the explanation as `RejectionReason`. |
| UpdateDelegationRequest | Adds notes and forwards the request for administrator approval. |
| SendDelegationToken | Releases the exchange token on an accepted request, finalizing it. |
| GetDelegationRequest | Returns one request, with an optional permission check. |
| ListDelegationRequests | Lists the requests the calling account owns. |
| GetHumanReadableSummary | Summarizes one delegation request as Markdown, in English. |

A request moves through the states AWS publishes: `UNASSIGNED`, `ASSIGNED`, `PENDING_APPROVAL`,
`ACCEPTED`, `FINALIZED`, `REJECTED` and `EXPIRED`. A new request is `UNASSIGNED` whether or not it
names a target account, claiming it makes it `ASSIGNED`, an update forwards it to
`PENDING_APPROVAL`, and acceptance from either state makes it `ACCEPTED`. Releasing the token moves
an accepted request to `FINALIZED`, which the reference says cannot be rolled back, so it is the
one transition that cannot be repeated. A rejected request cannot be accepted or updated
afterwards.

Expiry follows the two windows AWS documents, and is applied when a request is next read rather than
by a sweeper: a day for an unclaimed request, a week for every other state, including the terminal
ones. A request past its window reads back as `EXPIRED`.

A delegation request is the one IAM record two accounts share, so visibility follows the rules on
`GetDelegationRequest`: a request with no owner can be read by any account, and one naming a target
account only from that account. Floci decides this at account granularity, there being no
per-identity permission on a request to consult. `ListDelegationRequests` leaves out a request that
has no owner even when it names the listing account, as AWS documents, so a partner does not list
its own filings.

`RequestorWorkflowId` is unique per partner: reusing one is `EntityAlreadyExists`, which is the
failure the reference describes for it, and the uniqueness check shares a lock with the write that
follows it so two creates racing one workflow id cannot both pass. Each member is held to the length
and pattern it declares, which means an empty `Description` is a value rather than an omission: the
member is required and its pattern permits an empty string, so only leaving it out altogether is
refused. A member that breaks the format or constraints it declares answers `ValidationError`, which
the reference publishes as a common error for every IAM operation while listing it on none of them,
so these operations answer it the way the rest of IAM does. The `InvalidInput` they model is kept
for a value that cannot be read as the member's type at all, such as a `SessionDuration` that is not
a number or a boolean that is neither `true` nor `false`. An operation asked to move a request out
of a state it is no longer in answers `ConcurrentModification`: no modeled error says "wrong state",
and `AcceptDelegationRequest` models only `NoSuchEntity`, `ServiceFailure` and
`ConcurrentModification`, so the conflict is the closest of the three. The same lock serializes
those transitions, so two callers claiming one request leave a single owner.

Two ceilings apply to a request's length, and only one of them belongs to the request. The first is
the twelve hours `sessionDurationType` declares, which the create holds a `SessionDuration` to. The
second is the approver's: "root users can only approve delegation requests with a duration of 4
hours or less. If a request specifies more than 4 hours, you must use a non-root identity to approve
the request", so a longer request is refused at the approval rather than at the filing, and the same
request is approvable by an IAM user or role of that account. Floci reads root as a caller whose ARN
ends in `:root`, which is also what a key naming no IAM user resolves to, so approving a long request
locally means signing as a user rather than with a bare account id. `AcceptDelegationRequest` models
nothing for this and no source gives the code AWS answers, so `AccessDenied` is chosen, the guide
stating the rule as which identity may approve and the release's own owner rule answering the same
way.

The exchange token is the other half. `SendDelegationToken` releases it, and STS
`GetDelegatedAccessToken` exchanges it for credentials in the customer's account, acting as the
identity that approved the request rather than the one that claimed it: accepting a request records
the identity that called it, and that is the identity mapped to the delegated credential. The two
are the same identity until a request is forwarded for approval, which is also the case
`OnlySendByOwner` is for. The release is the one call on this family decided per identity rather
than per account: the flag holds it to whoever claimed the request, so a request forwarded to an
administrator who accepted it answers `AccessDenied` when that administrator tries to release it,
and the credentials the owner then releases carry that administrator's access. The delegation
period starts at the release rather than at the exchange, so every exchange of one token answers
the same expiry, release plus `SessionDuration`, and exchanging late simply buys a shorter session.
Only the account that filed the request may
exchange its token. A token past that window, and one nothing matches, both answer
`ExpiredTradeInTokenException`: the operation models three errors and none of them says "no such
token", and the remedy the reference gives, requesting a new token, is the same either way. A
token offered by any other account answers `AccessDenied`, which is a refusal rather than an
expiry. `PackedPolicySize` is left out of the response, the model deprecating it and stating it is
not populated here.

IAM tells the partner how the request is going on the SNS topic it named, which is the only channel
this family has outwards. Every state AWS lists notifies, `ASSIGNED`, `PENDING_APPROVAL`,
`ACCEPTED`, `FINALIZED`, `REJECTED` and `EXPIRED`, so the one state nobody is told about is the
`UNASSIGNED` a request starts in, which the partner filed itself. `FINALIZED` sends two: a
`StateChange`, and the `ExchangeToken` notification that carries the token and an `ExpiresAt` of
release plus `SessionDuration`. The payload is a JSON string in the `Message` of an ordinary SNS
notification, with `Type`, `RequestId`, `RequestorWorkflowId`, `State`, `OwnerAccountId` and
`UpdatedAt`, and the owner account left out until the request carries one: a request filed at a
named customer carries it from the start, an open one only once it is claimed. An expiry is
noticed on the read that finds it due, so that read is what announces it, and a call that then
fails on the expired request still sends the notification.

IAM publishes as `iam.amazonaws.com` rather than as the caller, so the topic's own policy decides
and nothing the partner or the customer holds plays any part. Under enforcement the topic has to
allow `SNS:Publish` to that principal, which is the statement the guide has partners add, and the
request's own ARN and the account that filed it are passed as `aws:SourceArn` and
`aws:SourceAccount`. `NotificationChannel` is held to more than its declared shape. That shape, 2 to 400 characters of
`[a-zA-Z0-9:_.-]`, is looser than an ARN, while the member's own documentation says "At this time,
only SNS topic ARNs are accepted for notification", so a value inside the shape that names no
topic is refused on the create with `InvalidInput`, the modeled error for a value that cannot be
read as the member's type. The code is the part no source states; the refusal itself is the
member's own sentence. A notification that cannot be delivered, whether the policy refuses it or the
topic is gone, costs the notification and not the call that produced it: the state has already changed, and AWS rolls nothing back for a message it
could not deliver.

One thing is deliberately not modeled. `PermissionCheckResult` answers `UNSURE` when
`DelegationPermissionCheck` is asked for, which the enum models, because a delegation request
carries no permission for Floci to evaluate. Policy
templates and permission boundaries are registered by AWS out of band, with no API, so
`PolicyTemplateArn` is kept as given and resolved against nothing, and `LimitExceeded` is modeled
with no published quota behind it, so no limit is enforced. Nothing renders a request's
`PermissionPolicy` either, so the credentials the token exchanges for are bounded by the
permissions of the identity that approved the request instead, which holds a delegation to no more
than that identity can do itself.

`GetHumanReadableSummary` answers a summary Floci writes rather than one it generates. AWS produces
that text with a large language model, which an emulator cannot reproduce and should not imitate, so
the format is the one the reference's own example answers, "Human readable summary in Markdown
format", and every line of it is read off the record: what is being asked for, by whom, for how
long, and where the request has got to. The console describes the section it backs as "a high-level
overview generated by AI that can help you understand what categories of AWS services can be
accessed and what types of actions can be performed in each service", and that half is missing for
the same reason nothing renders a `PermissionPolicy`: no API registers the template AWS resolves one
from, so there is no policy to read a service list out of. The template and the parameters the
request carries are named instead.

Who may read a summary is who may read the request, the reference saying the rules "are identical to
GetDelegationRequest API behavior, such that a party who has permissions to call
GetDelegationRequest for a given delegation request will always be able to retrieve the human
readable summary for that request", so the lookup and the `NoSuchEntity` it answers are the same
ones. Under enforcement the action is authorized against the ARN Floci mints for the request the id
names rather than against the `EntityArn` as it arrived, which is the same ARN the five by-id
actions resolve to: a policy naming one request covers that request alone, and a deny on it holds
however the caller respells the ARN around the same id. The default permission AWS grants a
request's owner is not implemented here, the same gap `GetDelegationRequest` has.

Four answers on it are chosen rather than read. An `EntityArn` naming any other type of entity is
`NOT_SUPPORTED`, which is the state the model publishes and the only thing it can mean for a type
AWS says it does not summarize. A `Locale` Floci does not write answers `NOT_AVAILABLE` with no
content, because "summary content is non-empty only if the `SummaryState` is `AVAILABLE`" and
English text under another locale would contradict the response carrying it; the language decides
and the region inside `en-GB` does not. A value that is not an ARN at all is the modeled
`InvalidInput`, `arnType` declaring a length and no pattern, so such a value breaks nothing
published. And a request id inside a well-formed ARN that no id could ever match answers
`NoSuchEntity` rather than a constraint failure, the operation declaring the ARN's own length and
nothing about what sits inside it. `FAILED` is unreachable, reporting a generation that ran and did
not finish. An ARN of another service is read as another kind of entity too, only IAM registering
a request at all, while the ARN's account is not matched against the request: the id is what
resolves across accounts, the way the whole family reads one. Content past the 10000 characters
`summaryContentType` allows is cut at the last whole line that fits, which a request carrying long
policy parameter values can reach.

The summary is Markdown and the request's own text is the requester's, so that text is escaped
rather than passed through. `delegationRequestDescriptionType` and `requestMessageType` admit a
newline and every printable character, so a description could otherwise open a line with a `#` or a
`-` and forge a heading or a bullet in a document a customer reads as Floci's account of the
request; a parameter value carrying a backtick could close a code span early and have the rest of
the line read as prose. Markers are escaped where they would be read, at the start of a line, a
backtick anywhere, and a value is fenced with one backtick more than it carries. A parameter value
the record does not hold is left out rather than written as an empty one.

## AWS Managed Policies

Floci seeds a catalog of commonly-used AWS managed policies at startup. These are attachable immediately without any setup:

**General access**
`AdministratorAccess` · `PowerUserAccess` · `ReadOnlyAccess` · `IAMFullAccess` · `AmazonS3FullAccess` · `AmazonS3ReadOnlyAccess` · `AmazonDynamoDBFullAccess` · `AmazonEC2FullAccess` · `AmazonSQSFullAccess` · `AmazonSNSFullAccess` · `AmazonVPCFullAccess` · `CloudWatchFullAccess` · `AWSLambdaFullAccess`

**Lambda execution roles** (`arn:aws:iam::aws:policy/service-role/...`)
`AWSLambdaBasicExecutionRole` · `AWSLambdaBasicDurableExecutionRolePolicy` · `AWSLambdaDynamoDBExecutionRole` · `AWSLambdaKinesisExecutionRole` · `AWSLambdaMSKExecutionRole` · `AWSLambdaSQSQueueExecutionRole` · `AWSLambdaVPCAccessExecutionRole`

**ECS / EKS execution roles**
`AmazonECSTaskExecutionRolePolicy` · `AmazonEKSFargatePodExecutionRolePolicy`

**EKS cluster & node groups**
`AmazonEKSClusterPolicy` · `AmazonEKSServicePolicy` · `AmazonEKSVPCResourceController` · `AmazonEKSWorkerNodePolicy` · `AmazonEKS_CNI_Policy`

**Other execution roles**
`AmazonS3ObjectLambdaExecutionRolePolicy` · `CloudWatchLambdaInsightsExecutionRolePolicy` · `CloudWatchLambdaApplicationSignalsExecutionRolePolicy` · `AWSConfigRulesExecutionRole` · `AWSMSKReplicatorExecutionRole` · `AWS-SSM-DiagnosisAutomation-ExecutionRolePolicy` · `AWS-SSM-RemediationAutomation-ExecutionRolePolicy` · `AmazonSageMakerGeospatialExecutionRole` · `AmazonSageMakerCanvasEMRServerlessExecutionRolePolicy` · `SageMakerStudioBedrockFunctionExecutionRolePolicy` · `SageMakerStudioDomainExecutionRolePolicy` · `SageMakerStudioQueryExecutionRolePolicy` · `AmazonDataZoneDomainExecutionRolePolicy` · `AmazonBedrockAgentCoreMemoryBedrockModelInferenceExecutionRolePolicy` · `AWSPartnerCentralSellingResourceSnapshotJobExecutionRolePolicy`

Every catalog entry carries the real policy document of its current default version, generated from the public [iam-dataset](https://github.com/iann0036/iam-dataset), so `GetPolicyVersion` returns the same statements a real account would and enforcement mode evaluates them faithfully. The catalog, its documents and its version data are regenerated from that dataset with `make aws-data-sync` (`tools/aws/regen_managed_policies.py`).

### Version numbers

AWS revises its managed policies in place, so their default version is rarely `v1`: `AmazonS3ReadOnlyAccess` is on `v3`, `ReadOnlyAccess` far beyond that, while `AdministratorAccess` has never been revised. Floci reports the version id AWS publishes for each policy, the date the policy was first created as `CreateDate`, and the date of its current default version as `UpdateDate`:

```bash
aws --endpoint-url http://localhost:4566 iam get-policy \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
# -> DefaultVersionId: "v3", CreateDate: 2015-02-06T18:40:00Z, UpdateDate: 2023-08-10T21:31:39Z

aws --endpoint-url http://localhost:4566 iam list-policy-versions \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
# -> a single entry, v3, IsDefaultVersion: true
```

Only the default version's document is bundled. Requesting a superseded version (`v1` or `v2` of `AmazonS3ReadOnlyAccess`) returns `NoSuchEntity`, the same answer AWS gives once it has pruned a managed policy's history, and `ListPolicyVersions` lists only the default. AWS managed policies remain read-only: `CreatePolicyVersion`, `SetDefaultPolicyVersion` and `DeletePolicyVersion` are rejected with `AccessDenied`.

## Optional Local Deployer Principal

Floci can seed a local IAM user for development workflows that expect a concrete caller identity before provisioning starts. This is disabled by default.

Enable it with:

```bash
FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL=true
```

When enabled, Floci creates the `floci-deployer` user if it does not already exist, attaches `arn:aws:iam::aws:policy/AdministratorAccess`, and creates static `floci` / `floci` access-key credentials if that access key does not already exist. Existing users and access keys are preserved.

Requests signed with the seeded access key return the deployer user ARN from `sts:GetCallerIdentity`.

## IAM Enforcement Mode

By default Floci accepts any credentials without enforcing IAM policies — all requests are allowed through regardless of what policies are attached to the calling identity. This preserves backward compatibility and keeps the default setup frictionless.

Setting `enforcement-enabled: true` activates the policy evaluator as a JAX-RS request filter. Every inbound request is then evaluated against the identity-based policies of the calling IAM user or assumed role before it reaches the service handler. This includes IAM's own management actions (`iam:CreateUser`, `iam:CreateGroup`, `iam:AttachUserPolicy`, `iam:DeleteUser`, ...): a user whose policies only grant, say, `s3:*` receives `AccessDenied` when calling them.

The startup banner reports the effective state (`IAM: policy enforcement enabled` / `disabled`). If requests you expect to be denied keep succeeding, check that line first: the flag is only read under the name below, and any other spelling (for example `FLOCI_IAM_STRICT_VALIDATION`, which does not exist) is silently ignored, leaving the permissive default in place.

### Enable enforcement

**Environment variable:**
```bash
FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true
```

Docker Compose:
```yaml
environment:
  FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED: "true"
```

### Evaluation rules

Policy evaluation follows the standard AWS precedence:

1. If [SCP enforcement](#service-control-policies-scps) is active, the action must be allowed at **every** organization level (root, OUs on the path, account) and explicitly denied at none — otherwise the request is denied before identity policies are consulted
2. An explicit **Deny** in any identity, session, or boundary policy denies the request
3. An explicit **Allow** in an identity policy creates the base grant
4. If a session policy is present, it must also explicitly allow the request
5. If a permission boundary is present, it must also explicitly allow the request
6. No matching effective allow results in an implicit deny

IAM authorization denials return HTTP 400 `AccessDeniedException` for AWS JSON 1.0/1.1
requests. REST-JSON requests return HTTP 403 `AccessDeniedException`; AWS Query and S3
requests retain HTTP 403 XML `AccessDenied` responses. The routed protocol determines
the status, not just the request's content type.

A request is checked against the resources it names. Floci builds their ARNs itself for S3, Lambda,
SQS, SNS, DynamoDB, Kinesis, SSM, KMS, IAM, Resource Groups, EventBridge Scheduler, EventBridge
Pipes, Aurora DSQL, EKS, CloudWatch Logs, Secrets Manager and STS. For Elastic Load Balancing, ECR,
Step Functions, EventBridge and Amazon Cognito user pools they come from a table generated from AWS's Service
Reference and botocore, `src/main/resources/aws/iam-request-resources.json`, which gives each
operation's action, the resource types that action acts on with their ARN formats, and the request
members that name them: a member holding a resource's ARN, which counts when it matches the format
of a type the action acts on, or members filling the variables of a type's format. A member holding
an ARN may be one inside a structure or inside each of a list's structures: `SetRulePriorities` is
checked on the rules its `RulePriorities` name, and `CreateStateMachineAlias` on the state machine
its routing configuration's versions are of. A variable the
request does not fill is `*`, as the service mints a new resource's id, so `CreateTargetGroup` is
checked on `targetgroup/<Name>/*`; a v2 `CreateLoadBalancer` is checked on the load balancer of its
`Type`, `loadbalancer/app/<Name>/*` when it gives none, and one of a `Type` outside `application`,
`network` and `gateway` is refused, as AWS refuses it. The members read are those of the
operation the request is dispatched as, the one its action is checked as (the Query `Action`, the
JSON `X-Amz-Target`, or the operation in a Smithy RPC v2 path), so an `X-Amz-Target` an RPC v2
request also carries names nothing, and as its handler reads them: a list only from a list, so
`DescribeRepositories` with a single name for `repositoryNames`, which ECR's handler reads as none
and answers with every repository, names no repository, and a number or a null as its text. A
member whose botocore shape is an account id gives the account the type is in: ECR's `registryId`
selects the registry its handler acts in, so `repository/<name>` is checked in that account, and a
`registryId` that is no account id names no repository, while a null one is the caller's, as ECR
reads it. `StartExecution` and
`StartSyncExecution` take Step Functions Local's `<stateMachineArn>#<test case>` and run the
machine before the `#`, so that machine is the one checked. An EventBridge rule is on the event bus
its request's `EventBusName` names, by name or by ARN, and on the default bus when it names none
or gives a null, whose rules are `rule/<name>`, with no bus; a bus ARN of another account, the
default bus's included, names that account's rule, checked as given and as the caller's own, which
Floci acts on, and one leaving its account and Region empty names the caller's. `DescribeEventBus`
naming no bus is checked on `event-bus/default`. A connection or an API destination, whose ARN
EventBridge follows with an id it mints after the name, `connection/<name>/<id>`, is checked on
the ARN it is stored under when the request names it, and on `connection/<name>/*` when there is
none yet, as for its create. EventBridge V2's API, which Floci does not serve, is not read. A
state machine's version or alias
(`stateMachine:<name>:<qualifier>`) is checked on the state machine's ARN, as Step Functions
authorizes it, so a grant or a deny on `stateMachine:<name>` covers its versions and aliases, and
an execution started through one is the state machine's. An ARN naming another account or
Region than the request's is checked as given and as each resource of that name a handler may act
on instead, in the ARN's account or the caller's and in the ARN's Region or the request's (ECR's
tag handlers keep the ARN's account in the request's Region), and the request is refused if any is
denied. Every other request is checked on `*`, as is an operation whose action acts on no
resource type or whose request names none, where AWS checks `*` too. `make
iam-request-resources-check` re-derives the table from the pinned botocore, `make
iam-request-resources-verify` checks it against AWS, and the namespaces it does not read yet are
listed in it with their reason.

A REST request is authorized as the operation of the route it reached. An `X-Amz-Target` header, a
Query `Action` field or a `smithy-protocol` header on it does not change the action it is checked
as, and its path is matched still percent-encoded, as the router matches it, so an encoded `/`
inside a parameter cannot make the route's rule miss. A trailing `/` is dropped first, as the router
drops it, so `POST /clusters/` is checked as `POST /clusters`. An S3 path keeps it, since an S3 key
may end in a slash, as a folder marker's does, and S3's rules already take a bucket's one trailing
slash.

The REST routes mapped to an action are those of S3, Lambda, API Gateway, SES v2, RDS Data,
Kinesis, EKS, EventBridge Scheduler, EventBridge Pipes and Aurora DSQL. In EKS that is every route,
including those Floci does not serve yet, each checked as the action of its own name, except an
access entry's access policy routes: Floci takes the entry's principal as the rest of the path, so a
`GET` or `DELETE` there reads or deletes an entry and is checked as `eks:DescribeAccessEntry` or
`eks:DeleteAccessEntry`. In Lambda that is every route of the function
API, MicroVMs and network connectors, including those Floci does not serve yet, and the Web
Functions routes, each checked as the action of its own name except `InvokeWithResponseStream`
(checked as `lambda:InvokeFunction`) and `GetLayerVersionByArn` (`lambda:GetLayerVersion`). AWS also
checks `lambda:AddPermission`, `lambda:GetPolicy` or `lambda:RemovePermission` for the resource
policy operations, which Floci does not serve; their routes are checked as their own action alone.
API Gateway's management API, v1 and v2, is checked as `apigateway:` and the HTTP
method, `apigateway:GET`, `apigateway:POST` and so on, except `RejectDomainNameAccessAssociation`
(`apigateway:RejectAccessAssociation`) and `PutRoutingRule` (`apigateway:UpdateRoutingRule`); the
v2 routing rule, portal and portal product operations, which AWS checks as actions of their own
names, are not served.

A Scheduler request is checked against `schedule/<group>/<name>`, the group from the body's
`GroupName` on a create or an update, from `groupName` otherwise, and `default` when neither names
one, or `schedule-group/<name>`. A Pipes request is checked against `pipe/<name>`. A DSQL request
is checked against `cluster/<id>`, `cluster/<id>/stream/<id>` for a stream, or the ARN a tagging
route names; `CreateCluster` and `ListClusters` against `cluster/*`, as DSQL checks them. A MicroVM image
or network connector request is checked against `microvm-image:<name>` or `network-connector:<id>`
in the request's account and Region, an identifier given as an ARN taken for its name, as the
service looks it up. `RunMicrovm` is checked against the image its `imageIdentifier` names, an
action on a running MicroVM against the image that MicroVM runs, and a managed image's versions
against that image, which `aws` owns. A Lambda event source mapping, code signing config or layer
version request is checked against `event-source-mapping:<uuid>`, `code-signing-config:<id>` or
`layer:<name>:<version>` in the request's account and Region, where Lambda looks them up: a config
by the id that ends its ARN, and a layer by its name as given, even a name given as an ARN or
holding an encoded `/`, and its version as the number Lambda parses, so `01` is checked as `1`.
`PublishLayerVersion` is checked against `layer:<name>`, and `GetLayerVersionByArn` against the
layer version its `Arn` names, its number read the same way. A Lambda tagging request, at
`/2017-03-31/tags/<arn>`, is checked against the caller's own function, MicroVM image or network
connector of that ARN's Region and name, whatever account the ARN names, as Lambda looks it up, and
an event source mapping's ARN as written.

An EKS request is checked in the caller's account, where EKS looks everything up. A cluster route is
checked against the ARN EKS holds the cluster under, or `cluster/<name>` in the request's Region for
a cluster it does not hold, and `CreateCluster` against the cluster its body names, as the EKS user
guide's example policy for creating a cluster grants it. A node group, Fargate profile, add-on,
access entry or Pod Identity association route is checked against the ARN EKS holds that resource
under, which ends in an id the request does not carry, so a policy names it with a wildcard, such as
`nodegroup/<cluster>/<name>/*`; listing or creating them, and the other routes under a cluster, are
checked against the cluster. A name holding an encoded `/` is read as one name, as EKS reads it.
`DescribeUpdate` is checked against the add-on the update was made to, since the updates Floci keeps
are its add-ons' and EKS returns one whether or not the request names its add-on, and otherwise
against the cluster. An EKS tagging request is checked against the caller's own cluster named after
the ARN's last `/`, whatever the ARN names before it, as EKS looks it up.

The shared tagging routes, `/tags` and `/v1/tags`, hand a request to the service its ARN names,
and it is checked as that service, against that ARN, or for EKS the cluster it tags, whatever scope
it is signed with. The ARN is
the one after `/tags/` or `/v1/tags/`, the body's `resourceArn` on `POST /tags`, and the
`resourceArn` query parameter on the other bare routes.

A route no rule maps is not checked, as an unsigned REST request is not; the other REST services'
routes are not mapped yet.

An S3 `CreateBucket` is checked as `s3:CreateBucket`, and also needs one action for each header
that configures the new bucket, as the CreateBucket API reference lists them:
`s3:PutBucketAcl` for a canned ACL other than `private` or a grant header,
`s3:PutBucketObjectLockConfiguration` and `s3:PutBucketVersioning` for
`x-amz-bucket-object-lock-enabled: true`, and `s3:PutBucketOwnershipControls` for
`x-amz-object-ownership`. They are checked before the bucket is created, so a denied request
creates nothing.

An S3 object operation is checked as the action the S3 documentation gives it:
- **Multipart uploads:**
  - `CreateMultipartUpload`, `UploadPart` and `CompleteMultipartUpload` are checked as
    `s3:PutObject`.
  - `AbortMultipartUpload` is checked as `s3:AbortMultipartUpload`.
  - `ListParts` is checked as `s3:ListMultipartUploadParts`.
- **Creating an object:** a `PutObject`, `CopyObject` or `CreateMultipartUpload` also needs
  the action for each setting it applies to the new object, as the S3 user guide's permissions
  table marks them conditionally required:
  - `s3:PutObjectTagging` for tags: the `x-amz-tagging` header, which a copy applies only with
    `x-amz-tagging-directive: REPLACE`, or a tagged source's tags, which a copy keeps under the
    default `COPY`;
  - `s3:PutObjectAcl` for a canned ACL or a grant header;
  - `s3:PutObjectRetention` for `x-amz-object-lock-mode` or `x-amz-object-lock-retain-until-date`;
  - `s3:PutObjectLegalHold` for `x-amz-object-lock-legal-hold`.

  S3's own access check under `floci.services.s3.enforce-auth`, which evaluates bucket policies
  and ACLs, checks the same actions, and offers the bucket policy the tag and Object Lock keys the
  request sets.
- **Restore and select:** `RestoreObject` is checked as `s3:RestoreObject`, and
  `SelectObjectContent` as `s3:GetObject`, or `s3:GetObjectVersion` when it names a version.
- **Attributes:** `GetObjectAttributes` needs `s3:GetObject` and `s3:GetObjectAttributes`, as its
  API reference says.
- **Copying:** `CopyObject` and `UploadPartCopy` also need `s3:GetObject` on the source object, or
  `s3:GetObjectVersion` when `x-amz-copy-source` names a version, as their API references say. It
  is checked against the request's credential, a presigned URL's included.
- **Object lock:**
  - Object retention and legal hold use their own actions: `s3:GetObjectRetention`,
    `s3:PutObjectRetention`, `s3:GetObjectLegalHold` and `s3:PutObjectLegalHold`.
  - A request that sends `x-amz-bypass-governance-retention: true` and overrides a governance-mode
    retention also needs `s3:BypassGovernanceRetention`: a `PutObjectRetention` that shortens or
    removes one, or a `DeleteObject` or `DeleteObjects` entry that removes a version under one. The
    S3 user guide's permissions table requires it "if you want to delete an object that's
    protected by governance mode" and to "bypass the governance mode of an Object Lock retention
    configuration", so the header on a request that overrides no lock asks for nothing more. A
    delete that names no version on a versioned bucket only adds a delete marker, and overrides
    none. The request bypasses only a lock the check found: one set after the check is refused. The
    user guide also says the permission does not apply to annotation operations, so deleting an
    annotation does not need it.
- **Annotations:** object annotations use the `s3:*ObjectAnnotation` actions, and
  `s3:ListObjectAnnotations` when no annotation name is given.
- **Versions:** a `versionId` selects the version action AWS checks.
  - Reading or heading a version needs `s3:GetObjectVersion`; `s3:GetObject` is not needed. Its
    attributes need `s3:GetObjectVersion` and `s3:GetObjectVersionAttributes`.
  - Deleting a version needs `s3:DeleteObjectVersion`, so a deny on it stops permanent deletes
    while a plain delete still adds a delete marker.
  - A version's ACL, tags and annotations use `s3:GetObjectVersionAcl`, `s3:PutObjectVersionAcl`,
    the `s3:*ObjectVersionTagging` actions and the `s3:*ObjectVersionAnnotation(s)` actions.
  - S3's own access check under `floci.services.s3.enforce-auth`, which evaluates bucket policies
    and ACLs, checks the same version actions, but offers no `s3:versionid`.
  - Deleting a version with an `If-Match` ETag also needs `s3:GetObject`, as a plain conditional
    delete does: the S3 user guide says the condition is evaluated against the current version.
- **Bucket configurations:**
  - Analytics, inventory and Intelligent-Tiering configurations use
    `s3:Get/PutAnalyticsConfiguration`, `s3:Get/PutInventoryConfiguration` and
    `s3:Get/PutIntelligentTieringConfiguration`.
  - Removing a configuration needs its Put action.
  - `DeleteBucketTagging` is checked as `s3:PutBucketTagging`, since AWS has no
    `s3:DeleteBucketTagging` action.

A request naming several of these sub-resources is checked as the one S3 runs. `GET ?acl&tagging`
returns the tags, so it needs `s3:GetObjectTagging`.

A request that passes a role to a service also needs `iam:PassRole` on that role, as the IAM user
guide requires: "To pass a role (and its permissions) to an AWS service, a user must have
permissions to pass the role to the service." It is checked with `iam:PassedToService` naming the
service the role goes to, the value AWS's Service Reference gives for the operation, so a grant to
pass a role to EC2 does not let the same role be passed to Lambda. The operations checked, and the
role each passes:
- **Lambda:** `CreateFunction` and `UpdateFunctionConfiguration` (`Role`, when the request sets
  it), `CreateWebFunction` and `CreateWebFunctionRevision` (the service configuration's
  `executionRoleArn`), `CreateMicrovmImage` and `UpdateMicrovmImage` (`buildRoleArn`),
  `RunMicrovm` (`executionRoleArn`), and `CreateNetworkConnector` and `UpdateNetworkConnector`
  (`OperatorRole`), passed to `lambda.amazonaws.com`.
- **EC2:** `RunInstances`, `RequestSpotInstances`, `AssociateIamInstanceProfile`,
  `ReplaceIamInstanceProfileAssociation`, `CreateLaunchTemplate` and `CreateLaunchTemplateVersion`
  pass the roles of the instance profile they name, to `ec2.amazonaws.com`. `CreateFlowLogs` passes
  `DeliverLogsPermissionArn` to `vpc-flow-logs.amazonaws.com`. A profile that does not exist
  passes nothing, and EC2 refuses it as without enforcement.
- **IAM:** `AddRoleToInstanceProfile` passes the role it adds to `ec2.amazonaws.com`, since every
  instance running with the profile then gets that role's credentials. `RoleName` names the role
  without its path, so the check uses the ARN IAM holds the role under, or, for a role IAM does not
  hold, the ARN the name gives.
- **ECS:** `RegisterTaskDefinition` passes its `taskRoleArn` and `executionRoleArn` to
  `ecs-tasks.amazonaws.com`. `RunTask` and `StartTask` pass the task definition's task and execution
  roles and any role in `overrides` there too, and `CreateService`, `UpdateService` and
  `CreateTaskSet` pass the roles of the task definition they name. The infrastructure roles go to
  `ecs.amazonaws.com`: a managed EBS volume's `roleArn` (`RunTask`, `StartTask`, `CreateService`,
  `UpdateService`), the service's `role` and the load balancer, lifecycle hook, Service Connect TLS
  and VPC Lattice roles (`CreateService`, `UpdateService`), and a Managed Instances provider's
  `infrastructureRoleArn` (`CreateCapacityProvider`, `UpdateCapacityProvider`). A role given by its
  name is the role of that name in the caller's account, and a task definition that does not exist
  passes nothing.
- **Step Functions:** `CreateStateMachine` and `UpdateStateMachine` pass `roleArn` to
  `states.amazonaws.com`, in a JSON or a CBOR body.
- **EventBridge:** `PutRule` passes `RoleArn`, and `PutTargets` each target's `RoleArn`, to
  `events.amazonaws.com`. **EventBridge Scheduler:** `CreateSchedule` and `UpdateSchedule` pass the
  target's `RoleArn` to `scheduler.amazonaws.com`. **EventBridge Pipes:** `CreatePipe` and
  `UpdatePipe` pass `RoleArn` to `pipes.amazonaws.com`.
- **EKS:** `CreateCluster` passes `roleArn`, `CreateNodegroup` `nodeRole`, `CreateFargateProfile`
  `podExecutionRoleArn`, and `CreateAddon` and `UpdateAddon` `serviceAccountRoleArn` and each of
  their `podIdentityAssociations`' `roleArn`, to `eks.amazonaws.com`. `CreatePodIdentityAssociation`
  and `UpdatePodIdentityAssociation` pass `roleArn` to `pods.eks.amazonaws.com`, but not
  `targetRoleArn`, which the association's role assumes. `CreateAccessEntry` passes `principalArn`
  to `ec2.amazonaws.com` when its `type` is `EC2_LINUX` or `EC2_WINDOWS`: "If you create an access
  entry with type EC2_LINUX or EC2_WINDOWS, the IAM principal creating the access entry must have
  the iam:PassRole permission." `CreateCapability`, `UpdateCapability` and `RegisterCluster`, which
  Floci does not serve, are checked from the table below.
- **CloudFormation:** `CreateStack`, `UpdateStack`, `DeleteStack`, `CreateChangeSet` and
  `ContinueUpdateRollback` pass `RoleARN` to `cloudformation.amazonaws.com` when the request names
  one. `CreateStackSet` and `UpdateStackSet` pass `AdministrationRoleARN`, or, for a self-managed
  StackSet, the default `AWSCloudFormationStackSetAdministrationRole`.
- **EC2 Auto Scaling:** `CreateLaunchConfiguration` passes the roles of its `IamInstanceProfile`,
  given by name or ARN, or else of the profile of the instance it is made from (`InstanceId`), to
  `ec2.amazonaws.com`. `CreateAutoScalingGroup`, `UpdateAutoScalingGroup` and `StartInstanceRefresh`
  (its desired configuration) pass the roles of the instance profile in each launch template they
  name, at the version the group launches from, there too: the group's own, its mixed instances
  policy's and each override's. A refresh's desired configuration counts as the group will have it
  applied: one naming only a version passes that version of the group's template, and one naming a
  template without a version passes the group's version of it. A `ServiceLinkedRoleARN` other than
  the default `AWSServiceRoleForAutoScaling` (`CreateAutoScalingGroup`, `UpdateAutoScalingGroup`)
  and a lifecycle hook's `RoleARN` (`PutLifecycleHook`, and the hooks `CreateAutoScalingGroup`
  names) go to `autoscaling.amazonaws.com`.
- **Application Auto Scaling:** `RegisterScalableTarget` passes `RoleARN` to
  `application-autoscaling.amazonaws.com` when the request names one.
- **Redshift:** `CreateCluster` and `RestoreFromClusterSnapshot` pass the roles in `IamRoles`, and
  `ModifyClusterIamRoles` those in `AddIamRoles`, to `redshift.amazonaws.com`, in any list form the
  Redshift handler reads (`IamRoles.IamRoleArn.N`, `IamRoles.member.N` or `IamRoles.N`).
  **Redshift Serverless:** `CreateNamespace` and `UpdateNamespace` pass the roles in `iamRoles` and
  the one in `defaultIamRoleArn`, and `CreateScheduledAction` and `UpdateScheduledAction` pass
  `roleArn`, to `redshift-serverless.amazonaws.com`.
- **RDS:** `AddRoleToDBCluster`, `AddRoleToDBInstance`, `RemoveRoleFromDBCluster`,
  `RemoveRoleFromDBInstance`, `CreateDBProxy` and `ModifyDBProxy` pass `RoleArn`, and
  `StartExportTask` passes `IamRoleArn`, to `rds.amazonaws.com`. `CreateDBInstance`,
  `CreateDBInstanceReadReplica`, `ModifyDBInstance`, `CreateDBCluster`, `ModifyDBCluster`,
  `RestoreDBClusterFromSnapshot` and `RestoreDBClusterToPointInTime` pass the Enhanced Monitoring
  role, `MonitoringRoleArn`, there too. They, `RestoreDBInstanceFromDBSnapshot` and
  `RestoreDBInstanceToPointInTime` pass the directory role `DomainIAMRoleName` names, checked on the
  ARN IAM holds it under, since the name carries no path. `CreateDBCluster` and the two cluster
  restores pass each role in `AssociatedRoles`, and `ModifyOptionGroup` passes the role each option
  it includes takes in its `IAM_ROLE_ARN` setting, as SQL Server's native backup and audit options
  do. **DMS:** `CreateEndpoint` and `ModifyEndpoint` pass `ServiceAccessRoleArn` and the role
  members the DMS model gives each engine's settings structure (`ServiceAccessRoleArn`,
  `SecretsManagerAccessRoleArn`, `SecretsManagerOracleAsmAccessRoleArn`), and
  `CreateReplicationInstance` and `ModifyReplicationInstance` pass
  `KerberosAuthenticationSettings.KeyCacheSecretIamArn`, to `dms.amazonaws.com`.

Launching an instance from a launch template does not check the template's role, as on AWS: the EC2
user guide warns that a user who can create launch template versions "can potentially update a launch
template to pass an IAM role to an instance even if they don't have the iam:PassRole permission". An
Auto Scaling group does check it: the EC2 Auto Scaling user guide has the permission "needed on the
IAM identity (user or role) that creates or updates an Auto Scaling group using a launch template
that specifies an instance profile", while a group using a launch configuration passes nothing, its
role having been checked when the configuration was created. A
CloudFormation operation that names no role uses the stack's own without checking it, as the
CloudFormation API reference says the service does "even if the users don't have permission to pass
it". The CloudFormation user guide has the default StackSet administration role checked "Even when
you don't specify the AdministrationRoleARN parameter". A Redshift request is checked on the roles
it associates, as the Redshift management guide puts it: "To associate an IAM role with a cluster,
a user must have iam:PassRole permission for that IAM role." A role named only as a cluster's
default, `DefaultIamRoleArn`, is not checked on its own, since the default is one of the roles the
cluster holds. A namespace's `defaultIamRoleArn` is, since Floci keeps it whether or not `iamRoles`
holds it. Redshift takes a role only under the ARN IAM holds it under, path included, so the check
on the ARN as the request names it covers the role `COPY`, `UNLOAD` and Spectrum then use. The RDS
user guide has the Enhanced Monitoring role checked: "The user that enables Enhanced Monitoring must
be granted the PassRole permission." Removing a role from an RDS instance or cluster is checked as
adding one is, since the Service Reference lists `iam:PassRole` for both.

Every other operation the Service Reference lists with `iam:PassRole`, in the services Floci serves
over the JSON and Query protocols and Smithy RPC v2 CBOR and in the REST services whose routes are
authorized as operations, is checked from a table generated from the Service Reference and
botocore's models, `src/main/resources/aws/iam-passed-roles.json`. A request member names a role
when its name ends in `Role`, `RoleArn` or `RoleName` (or a plural, or one of those followed by
`Update` or `Override`) and it holds a string, or a list or map of them; it is read from a JSON or
CBOR body or from Query form parameters, in either list form, and its role is passed to the service
the Service Reference names. That covers Athena, CloudTrail, CloudWatch metric streams (to
`streams.metrics.cloudwatch.amazonaws.com`), CodeBuild, CodeDeploy, CodePipeline, Cognito, Config,
DataSync, ECR, Firehose, Glue, Kinesis Analytics, CloudWatch Logs, SageMaker, Secrets Manager,
Budgets actions, SES, SSM, Service Catalog, SWF, Textract, Transcribe and Transfer Family, among
others. The table lists an operation whether or not Floci serves it, so one Floci serves later is
checked from the start; one it does not serve answers `AccessDenied` rather than its usual error
when the caller may not pass the role.
- A member whose documentation says it takes a role by name, as a Glue job's `Role` and a CodeBuild
  build's `serviceRoleOverride` do, is checked on the ARN IAM holds the role under, path included,
  or, for a role IAM does not hold, the ARN the name gives.
- A role the Service Reference passes to no named service, as it does Cognito's group, user pool and
  identity pool roles, is checked with no `iam:PassedToService`, so a grant conditioned on that key
  does not cover it.
- Members that name no role passed to the service are left out: the roles a Budgets IAM action
  attaches a policy to, a CodePipeline action's `roleArn`, which the pipeline's role assumes, the
  execution role of each SSM target account, the roles a Glue job's code-generated nodes assume with
  STS, a role a Redshift cluster already holds, and Firehose's Snowflake role.
- Members the rule does not see are added: a Cognito user pool's SMS caller roles, a Glue
  connection's `ROLE_ARN` property ("The role to be used for running queries"), the role that
  defines a Glue view, `TableInput.ViewDefinition.Definer` (Lake Formation's view prerequisites ask
  for "The iam:PassRole permission for AWS Glue and Lake Formation"), and the Service Catalog
  `AssumeRole` and `LAUNCH_ROLE` keys of a service action's definition and a provisioned product's
  properties.
- Some operations are read by hand instead. SNS names its roles in attributes: a topic's
  `<ENDPOINT>SuccessFeedbackRoleArn` and `<ENDPOINT>FailureFeedbackRoleArn`, which "are used to give
  Amazon SNS write access to use CloudWatch Logs on your behalf", a platform application's
  `SuccessFeedbackRoleArn` and `FailureFeedbackRoleArn`, a Firehose subscription's
  `SubscriptionRoleArn` and the SMS `DeliveryStatusIAMRole`, read as the SNS handlers read the map.
  A CloudWatch Logs subscription filter policy names its `RoleArn` in its document. A Service
  Catalog launch constraint names its role in its `Parameters` document, by `RoleArn` or by
  `LocalRoleName`, which is checked on the ARN IAM holds the role under, and an update goes by the
  type of the constraint it changes. The type is matched in any case, since Floci keeps it as given.
  An SWF decision is read from the attributes its `decisionType` names.
- Not read: an operation whose request names no role (SSM `CreateDocument`, for one); one whose role
  is a parameter of the runbook it runs (SSM `StartAutomationExecution`, Config
  `PutRemediationConfigurations`); one the Service Reference passes roles from to several services
  without saying which member goes where (EMR `RunJobFlow`, whose `JobFlowRole` also names an
  instance profile, EKS `UpdateClusterConfig`, CloudWatch Logs `CreateImportTask`, SageMaker
  `CreateTrainingSession`); and one whose body is XML (S3 `PutBucketReplication`). EC2's
  `RequestSpotFleet` names its roles in EC2's own protocol, one of them as an instance profile. The
  REST services whose routes no rule maps yet, and API Gateway, which is authorized by HTTP method,
  are not read either.

A request that tags the resource it creates also needs the service's tagging action. The SNS API
reference has "To be able to tag a topic on creation, you must have the sns:CreateTopic and
sns:TagResource permissions.", and the EC2 user guide "If tags are specified in the resource-creating
action, Amazon performs additional authorization on the ec2:CreateTags action to verify if users have
permissions to create tags." Every operation the Service Reference lists with an action that only
tags and adds tags (`TagResource`, `CreateTags`, `AddTags`, `AddTagsToResource`, SQS's `TagQueue`,
IAM's `TagRole`, STS's `TagSession` and the like), in the services Floci serves over the JSON and
Query protocols and Smithy RPC v2 CBOR and in the REST services whose routes are authorized as
operations, is checked from a second table generated the same way,
`src/main/resources/aws/iam-tag-on-create.json`.
When the request carries tags, the tagging action is checked for each resource's tags apart, with
each tag as `aws:RequestTag/<key>` and their keys as `aws:TagKeys`, so a policy that allows tagging
only with some tags or keys holds at creation too. A tag specification's tags are checked on the
resource of the type it names, its name not known yet, as AWS checks them on the resource being
tagged: an instance's on `arn:aws:ec2:<Region>:<account>:instance/*`, from the ARN the Service
Reference gives the type. Other tags are checked on the resources the operation itself is checked
on: Lambda's `CreateFunction` is checked on the function its body names, in the caller's account and
Region, as the Service Reference gives it the `function` resource, so a function's tags are checked
there too, and a create of a service the table of request resources reads is checked on the resource
it creates (a target group on `targetgroup/<Name>/*`, an application load balancer on
`loadbalancer/app/<Name>/*`). A create of a resource under a parent its action acts on tags the new
resource, so its tags are checked on that one, built from the parent's ARN with its own id open:
`CreateListener` acts on a load balancer and its tags are checked on
`listener/app/<load balancer name>/<load balancer id>/*`, and `CreateRule`'s on the new
`listener-rule/...` under its listener. So the AWS Load Balancer Controller's policy, which allows
`AddTags` at creation only on target groups and application and network load balancers, and on
listeners and rules, holds. Where the tagging action takes
its namespace's `CreateAction` key, as `ec2:CreateTags`, `ecs:TagResource`, CloudFormation's
`TagResource` and Elastic Load Balancing's `AddTags` do, the key is the operation's name:
`ec2:CreateAction` is `RunInstances` for an instance launched with tags. The operation's own action
is checked with the same `aws:RequestTag/<key>` and `aws:TagKeys`, once for each resource's tags,
wherever the Service Reference lists those keys for it or for a resource it acts on, which it does for 542 of the operations read, so
"allow `sqs:CreateQueue` only with `aws:RequestTag/team`" and "deny it when `aws:RequestTag/team` is
null" hold as on AWS; keys a service's own condition keys already give, as EC2's `RunInstances`
does, are kept. It is checked once for each reading of the tags and refused if any is denied: the
sets its handler reads, those under any other spelling (below), and none where its handler reads
none, so a deny on a tag holds whichever reading a handler applies, and a create that requires a tag
gets it only where its handler applies it. A request that carries no tags needs only its own action.
As with passed roles, an operation Floci does not serve is in the table too.
- The tags are read from a top-level request member named `Tags`, `TagList`, `TagSpecifications` or
  the like that holds a tag list or map, as the Floci handler that applies them reads it, so the check
  sees the tags the resource gets. A JSON or CBOR body's member is read under its own name, and an
  alarm's in CloudWatch's `PutMetricAlarm` and `PutCompositeAlarm` under `tags` too; a CodeDeploy
  tag's key and value as `Key` and `Value`, or `key` and `value`, as its handler takes either, each as
  text whatever it holds (a key `123` is the tag `123`). Query form
  parameters are read under the spelling the SDK sends, from 1 to the first number with no key, and
  under the further spellings the RDS, DocumentDB, Neptune, ElastiCache and Redshift handlers read.
  EC2's are read from every tag specification, under `TagSpecification` or `TagSpecifications`, as EC2
  applies either, to the first that names no resource type. SQS's are read from `Tag.N.Key` and
  `Tag.N.Value` over the Query protocol. The tagging action is also checked for tags under any other
  spelling a handler might read, a member or a tag's key and value in any case, every element of a
  tag list and an object's structures, a form's list under any spelling and every number, so none a
  handler applies goes unchecked. CodeDeploy's handler iterates its tags member whatever it holds,
  so an object's structures are its tags too.
- CloudWatch Logs `CreateLogGroup`, which takes either of two tagging actions, is checked as described
  under CloudWatch Logs groups below.
- Not read: an operation whose request carries no tags, which the Service Reference lists with a
  tagging action all the same (RDS `DeleteDBInstance` and DynamoDB `UpdateTable`, among others), and
  EC2's `RequestSpotFleet`, whose tags are deeper in its configuration. A session's tags also need
  `sts:TagSession` in the role's trust policy, which is not checked, and service-specific request tag
  keys such as `rds:req-tag/<key>` are not set. S3 checks `s3:PutObjectTagging` for an object it
  creates with tags itself, and the REST services whose routes no rule maps yet are not read.

A request that names its operation, in a Query `Action`, an `X-Amz-Target` header or a Smithy RPC v2
path (`/service/<service>/operation/<operation>`), is authorized as the action AWS checks for that
operation, which is usually the operation's own name. An RPC v2 call is checked as the service and
operation in its path, the ones Floci runs, whatever its `smithy-protocol` header says, and an
`X-Amz-Target` header on it does not change the action it is checked as. A CBOR body, on that path
or on the `x-amz-cbor-1.1` one, names its resource and condition keys (a queue URL, a table name,
`dynamodb:LeadingKeys`) the way a JSON body does, and they are read from it the same way. Where AWS
authorizes an operation as a single action of another name, Floci does the same: `SendMessageBatch`
is checked as `sqs:SendMessage` and `PublishBatch` as `sns:Publish`, so a policy granting the
single-message action covers the batch call, and one naming the batch operation, which is not an
IAM action, grants nothing. Budgets operations other than its budget actions are checked as
`budgets:ModifyBudget` or `budgets:ViewBudget`, which the Service Reference lists for them beside the
`aws-portal` Billing console permissions. Those are not checked: the Billing and Cost Management user
guide, on moving off them, says "Programmatic requests to AWS Cost Explorer, AWS Cost and Usage
Reports, and AWS Budgets remains unaffected". The mapping is taken from AWS's Service Reference and
vendored in `src/main/resources/aws/iam-authorized-actions.json`. DynamoDB `TransactGetItems`, which
AWS checks as `dynamodb:GetItem`, is checked item by item, as described under
[Condition keys floci populates](#condition-keys-floci-populates): each `Get` as
`dynamodb:GetItem` on its own table, with its own `dynamodb:LeadingKeys`.

### Resource-based policies

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` is active, Floci also queries registered `ResourcePolicyProvider` SPI implementations (such as S3 bucket policies) during request authorization:

- Resource policy statements are matched against the caller (`Principal` and `NotPrincipal` clauses), each principal type naming only its own kind of caller, as on AWS: `"*"` matches anyone; `{"AWS": "*"}` matches IAM identities and AWS services; any other `AWS` entry (a user, role, account id or account root) matches IAM identities only, and a role session matches a `Principal` naming its role through the role's own ARN, path included; `{"Service": "<name>"}` matches that service exactly. `{"CanonicalUser": "<id>"}`, which S3 bucket policies accept, names an account by its S3 canonical user ID and matches that account's IAM identities, as an account principal does; Floci derives that ID from the account ID as its SHA-256 hex digest, the 64-hex value its S3 ACLs report, and still accepts the account ID itself for policies written before it derived one. `{"Service": "*"}`, which AWS does not accept, matches nothing, and `Federated` entries never match an IAM caller.
- An explicit **Deny** in a resource policy overrides any allows.
- A Deny statement's `NotPrincipal` spares an IAM caller only when it names the caller's account as well as the caller itself, the user, role or role session (the account alone for the account root), and never spares a caller that has a permissions boundary attached, as the IAM User Guide's "AWS JSON policy elements: NotPrincipal" describes: without the account, AWS "might deny access to the entire account", and with a boundary it "will always deny". An Allow with `NotPrincipal`, which Access Analyzer warns can grant to anonymous callers, grants everyone it does not name.
- In cross-account scenarios or resource-controlled access, an explicit **Allow** in a resource policy grants access to the principal.
- A statement that names the caller only through its account, by its id, its root ARN or its S3 canonical user ID, delegates to the account, as the IAM User Guide's "AWS account principals" describes: across accounts it is the resource owner's half of the grant, and within the account it grants nothing on its own, so the caller's identity policies have to allow the request. A statement naming the user, role or role session itself, or `"*"`, grants on its own within the account.
- For detailed S3 bucket policy behavior and configuration, see [S3 Bucket Policy Enforcement](s3.md#bucket-policy-enforcement).
- Lambda function policies, the statements `AddPermission` adds, take part the same way. An invoke of another account's function by ARN runs it in that account, so its function policy has to allow the caller as well; see [Function policies under IAM enforcement](lambda.md#function-policies-under-iam-enforcement).
- SNS topic policies take part the same way for a request that names the topic in a JSON-protocol body; a topic with no policy set has the default one AWS gives a new topic. See [SNS](sns.md).
- Aurora DSQL cluster policies take part the same way for a control plane call on the cluster, and when a client connects to the cluster's endpoint. See [DSQL](dsql.md#authentication).
- SQS queue and API Gateway REST API policies are stored but not evaluated.
- A call an AWS service makes to another service's resource on its own behalf, such as SES publishing an event or a notification to an SNS topic, has no IAM principal, so the target's resource policy decides it alone and has to allow the calling service, through `{"Service": "<service>.amazonaws.com"}` or `{"AWS": "*"}`. A target with no policy refuses it. Its request context holds `aws:PrincipalIsAWSService` (`true`), `aws:PrincipalServiceName` and `aws:PrincipalServiceNamesList`, `aws:SourceArn` and `aws:SourceAccount` for the resource whose configuration triggered the call, `aws:ResourceAccount` and `aws:RequestedRegion`, and none of the keys that describe an IAM principal. A publish to an SNS topic from SES also carries `aws:SourceOwner`, the key the SNS Developer Guide lists SES among the services setting, which is what the default topic policy checks, so that policy admits SES acting for the topic owner's account and no other. SES and Lambda, when it reads the code of a Web Function revision from S3, are the services checked this way so far; see [SES IAM enforcement](ses.md#iam-enforcement) and [Lambda Web Functions](lambda-web-functions.md#emulation-behavior).

### Service control policies (SCPs)

When the caller's account belongs to an [Organizations](organizations.md) organization,
SCPs attached to the root, the OUs on the account's path, and the account itself can
participate in evaluation. Two flags must both be on:

```yaml
floci:
  services:
    iam:
      enforcement-enabled: true
    organizations:
      scp-enforcement-enabled: true
```

(env: `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED`)

SCP semantics match AWS: SCPs never grant permissions — they cap what identity policies
may allow; the organization's **management account is exempt**; and an account outside
any organization is unaffected. The `test` credential is never SCP-denied.

**The account-root principal is subject to SCPs.** floci's account root is a bare
12-digit account-id access key (the LocalStack multi-account convention). It carries no
registered IAM identity, so it normally takes the unknown-key bypass. But when the access
key equals its own account ID **and** that account has an effective SCP ceiling
(`effectiveScpLevels != null` — i.e. it is a non-management member under a root with the
SCP policy type enabled and at least one attached SCP), floci synthesizes an
allow-everything root identity and evaluates the request against the SCP chain. In that
case **SCPs apply and nothing else does** — no identity policies, permission boundary, or
session policy attaches to the bare account key. If the account has no effective SCP
ceiling (the management account, an account outside any organization, or the SCP type
disabled), the bare key still bypasses enforcement entirely. An `AKIA…` key that exists
nowhere is rejected rather than bypassed.

### Bypass rules

These identities always bypass enforcement (backward-compatible defaults):

| Identity | Behaviour |
|---|---|
| Access key `test` (the default dev credential) | Always allowed — no policy lookup |
| Access key that exists nowhere | **Rejected** with `403`, `sts:GetCallerIdentity` included: `InvalidAccessKeyId` for S3, `InvalidClientTokenId` for Query services, `UnrecognizedClientException` for JSON services. A presigned POST, whose credential is in the form body, is left to S3's own presigned-POST check |
| Inactive access key | **Rejected** the same way, until the key is activated again |
| Expired session credentials | **Rejected** with `403 ExpiredTokenException`, or `400 ExpiredToken` for S3, rather than as a key that exists nowhere, for as long as Floci still holds the session |
| `Authorization` header with no readable credential: no access key (a Bearer token, SigV2, a credential without the SigV4 scheme or without its key), or a scope missing a part such as its `aws4_request` terminator, or an empty header. An empty header next to a presigned URL's `X-Amz-Credential` defers to that credential | **Rejected** with `400`: `AuthorizationHeaderMalformed` for S3 (`AuthorizationQueryParametersError` when the credential is a presigned URL's `X-Amz-Credential`), `IncompleteSignature` for Query services, `IncompleteSignatureException` for JSON services. A REST request is rejected only when the header claims SigV4; otherwise it is treated as unsigned (see [Unsigned requests](#unsigned-requests)) |
| Credential the filter cannot map to policies, such as a session carrying no role ARN | Allowed: it is a real credential, so rejecting it would refuse an authenticated caller |
| No `Authorization` header | **Rejected** for a JSON, CBOR or Query management call, unless AWS serves the operation without credentials. Allowed for a REST request such as a health check (see [Unsigned requests](#unsigned-requests)) |
| Unresolvable IAM action for the request | Allowed — unknown mappings are permissive |

**CloudWatch Logs streams.** `logs:CreateLogStream` and `logs:DeleteLogStream` evaluate the
specific log-stream ARN, so policies can allow or deny one stream within a group. Their
`aws:ResourceTag/<key>` conditions read the owning log group's current tags in the request's
account and region. Both stream operations check the documented group and stream name constraints
before changing storage, preventing delimiter aliases from targeting another stream.
These checks also apply to deletion: legacy streams with names containing `:` or `*` cannot be
deleted through `DeleteLogStream`. Existing stored names are not automatically migrated.

**CloudWatch Logs groups.** `CreateLogGroup`, `DeleteLogGroup`, `PutRetentionPolicy` and
`DeleteRetentionPolicy` accept policy resources naming the selected group with a bare ARN
or its legacy `:*` form. Both forms participate in each statement, including explicit denies
and `NotResource`, without bypassing session, boundary or SCP ceilings. The three operations on
existing groups read that group's resource tags; creation uses the request's `tags` for
`aws:RequestTag/<key>` and `aws:TagKeys`. Tagged creation additionally requires either
`logs:TagResource` on the bare group ARN or `logs:TagLogGroup` on either group ARN form.
The group name constraints apply before mutation, and deletion preserves streams and filters
whose stored group identity belongs to another historical group. These four operations reject `:`
or `*` in a group name. Historical names remain taggable through the ARN tagging APIs below;
they cannot be newly created or deleted through the group lifecycle APIs. Stored names are not migrated.

**CloudWatch Logs ARN tagging.** For log groups, `logs:TagResource`, `logs:UntagResource`
and `logs:ListTagsForResource` evaluate the group's ARN without a trailing `:*`. The ARN must
identify a group in the request's account, region and partition; a different owner or resource
kind returns `ResourceNotFoundException` without reading or changing a local group's tags.
All three actions use the group's existing `aws:ResourceTag/<key>` conditions. `TagResource`
also provides `aws:RequestTag/<key>` and `aws:TagKeys`; `UntagResource` provides `aws:TagKeys`.
An unauthorized request cannot partially add or remove tags. These ownership checks also apply
when IAM enforcement is disabled. Shared tag mutations reject storage aliases whose stored group
name differs from the requested name, without migrating historical records.

This support covers log groups. Tagging destinations and other CloudWatch Logs resource types
remains unsupported. Plain-name inputs are used literally, including incomplete prefixes such as
`arn:archive`; arbitrary `:log-group:` substrings never select a different group. Structurally complete
ARNs take precedence over plain-name interpretation and must identify a local group. A trailing `:*`
is accepted on a canonical local group ARN. These compatibility forms are not a claim that AWS accepts
them for these APIs. Other Logs actions retain their existing wildcard resource evaluation.

**CloudWatch Logs account policies.** `PutAccountPolicy` also needs the action for the type of
policy it creates, checked against `*` like `logs:PutAccountPolicy` itself: `logs:PutDataProtectionPolicy`
for `DATA_PROTECTION_POLICY`, `logs:PutSubscriptionFilter` for `SUBSCRIPTION_FILTER_POLICY`,
`logs:PutTransformer` for `TRANSFORMER_POLICY`, `logs:PutIndexPolicy` for `FIELD_INDEX_POLICY` and
`logs:PutMetricExtractionPolicy` for `METRIC_EXTRACTION_POLICY`. Without it the request is refused with
`AccessDeniedException` and no policy is stored. A subscription filter policy whose document names a
`RoleArn` also needs `iam:PassRole` on it for `logs.amazonaws.com`: "To perform a PutAccountPolicy
subscription filter operation for any destination except a Lambda function, you must also have the
iam:PassRole permission."
`DescribeAccountPolicies` likewise needs the read action for the type it lists:
`logs:GetDataProtectionPolicy`, `logs:DescribeSubscriptionFilters`, `logs:GetTransformer` or
`logs:DescribeIndexPolicies`. Its reference names none for `METRIC_EXTRACTION_POLICY`, which needs only
`logs:DescribeAccountPolicies`.

**Secrets Manager secrets.** A request that names a secret by its name, or by a partial ARN without
the random suffix, is evaluated against the stored secret's full ARN, suffix included, so a
`secret:<name>-??????` resource, the pattern the Secrets Manager User Guide documents, matches it as
it does on AWS, and a `Deny` on the full ARN applies. `CreateSecret` is evaluated against
`secret:<Name>-??????`, standing in for the suffix the secret is assigned when it is created, so a
policy scoped to a name prefix such as `secret:app/*` allows it. A name that matches no secret in the
request's account and region is evaluated as `secret:<name>`, and an ARN that matches none as given.

**KMS keys.** A request that names a key by its id, an alias name, an alias ARN or a key ARN is
evaluated against the stored key's ARN, as the KMS API reference resolves the request's key
identifier, so a policy scoped to one key ARN allows that key and no other, and a `Deny` scoped to
one fires for that key. A `Decrypt` that names no `KeyId` is evaluated against the key that encrypted
its `CiphertextBlob`. `CreateAlias`, `UpdateAlias` and `DeleteAlias` are evaluated against the alias
ARN and the key it points at, `UpdateAlias` also against the key it will point at, the resource
types the Service Authorization Reference lists for them. `CreateKey`, `GenerateRandom`, `ListKeys`,
`ListAliases` and `ListRetirableGrants` name no key resource and are evaluated against `*`, the
resource a policy must name for them on AWS. `ReEncrypt` is evaluated against both keys it acts on
under the single `kms:ReEncrypt` action; AWS authorizes the two halves separately as
`kms:ReEncryptFrom` and `kms:ReEncryptTo`, which Floci does not split yet. A request that names no
key the account holds is evaluated against every key the account holds, so a permissive `key/*`
policy still matches it and the operation fails in the handler with its own error.

**Redshift GetClusterCredentials options.** Besides `redshift:GetClusterCredentials`, a request with
`DbGroups` needs `redshift:JoinGroup` on each group's `dbgroup:<cluster>/<group>` ARN, and a request with
`AutoCreate` set to `true` needs `redshift:CreateClusterUser` on the user's `dbuser:<cluster>/<user>` ARN,
as the GetClusterCredentials API reference lists them. They are checked before the cluster is looked up,
so a denied request gets `AccessDenied` even for a cluster that does not exist. The `dbname` resource
that `DbName` needs is not checked.

**IAM's own resources are named.** When enforcement evaluates a request, the target resource comes
from `ResourceArnBuilder`, which builds an ARN for S3, Lambda, SQS, SNS, DynamoDB, Kinesis, Secrets
Manager, SSM, KMS and IAM. Within IAM the resource type each action is evaluated against is the one
the Service Authorization Reference gives it, so a statement naming a specific user, role, group,
instance profile, managed policy, MFA device, identity provider or server certificate constrains
it: a `Deny` on `arn:aws:iam::123456789012:user/bob` stops `DeleteUser` for Bob and leaves other
users alone, and an `Allow` scoped to one role limits `DeleteRole` to it.

The resource type is not the parameter the action happens to take. `AddUserToGroup` and
`RemoveUserFromGroup` are evaluated against the group, `AddRoleToInstanceProfile` and
`RemoveRoleFromInstanceProfile` against the instance profile, and `EnableMFADevice`,
`DeactivateMFADevice` and `ResyncMFADevice` against the user rather than the device. An action
acting on something that already exists is checked against that resource's stored ARN, path and
partition included; a create is checked against an ARN minted from the request's own `Path`, since
reading a stored path would authorize a create into a path the policy does not name.

Which type each action takes is vendored, not written out in Java:
`src/main/resources/aws/iam-action-resources.json` is generated by
`tools/aws/regen_iam_action_resources.py` from AWS's Service Reference Information for IAM, and
carries every published action with the resource types it is authorized against, plus the ARN
format of each type. `make iam-action-resources-check` gates its shape offline on every pull
request and `make iam-action-resources-verify` checks every claim in it against AWS weekly, so a
type that drifts from what AWS publishes fails in CI rather than authorizing against the wrong
resource. What stays in Java is the half that is Floci's: the request parameter each type's name
arrives in, the creates, the actions whose request carries an ARN, the four whose request names
the resource some other way, and the resource types that belong only to actions Floci does not
dispatch. `GenerateOrganizationsAccessReport` is one of the four: the reference spells its type as
`access-report/${EntityPath}`, so the Organizations entity path goes into the ARN whole, its
slashes being the organization's tree rather than an IAM path. `IamActionResourcesTest` checks the
one against the other, so a regeneration that introduces a resource type nothing resolves fails a
test naming
it rather than quietly putting that action back on `*`.

The nineteen user actions whose `UserName` the model marks optional, `GetUser`, `ListAccessKeys` and
`CreateAccessKey` among them, resolve to the user who signed the request when it is omitted, which
is the user AWS acts on. The access key id in the credential names that identity, the same lookup
`GetAccessKeyLastUsed` makes on a key passed as a parameter, and the user's stored ARN is then the
resource. The wildcard still stands when the signer is not an IAM user: an account's root stand-in
and a session credential have no user record to name.

What resolves to `*` is what the reference gives no resource type, such as `ListUsers`,
`GetAccountSummary` and `SimulateCustomPolicy`. Every other mapped action takes its parameter as a
required member, so it always resolves.

A resource of `*` is matched as that literal string, because the evaluator globs the statement's
pattern against the request's resource and not the other way round. So a statement whose `Resource`
names an ARN applies to such a request in neither direction: a `Deny` on one of those actions does
not refuse it, and an `Allow` does not permit it either.

**A rename names two resources.** `UpdateUser`, `UpdateGroup` and `UpdateServerCertificate` are
evaluated against both the resource's current ARN and the ARN the new name or `NewPath` would
produce, because AWS requires the principal to hold permission on the old name and the new one.
`UpdateUser`'s reference says the requester "must have appropriate permissions on both the source
object and the target object", and `UpdateGroup`'s that a principal allowed to update the old group
but not the new one has the update fail. So a principal allowed to update `ProductionCert` but not
`ProdCert` cannot rename the first into the second. A request naming several resources is
authorized once per resource, so a `Deny` on either name refuses the rename and the resource keeps
its original name and path. An update that changes neither the name nor the path names a single
resource. The destination ARN is built beside the stored one, keeping the resource's own partition
and account, since a rename moves a resource within an account rather than between partitions.

**Exception:** a bare 12-digit account-id key that equals its own account and sits under
an effective SCP ceiling is **not** treated as an unknown key — it is evaluated against
the SCP chain as the account root (see [Service control policies](#service-control-policies-scps)
above). Identity-policy enforcement of a member account still requires an assumable,
account-routable identity such as the `OrganizationAccountAccessRole` session; the bare
account key carries no identity policies of its own.

### Supported policy features

- **Identity-based policies**: inline user/group/role policies and managed attached policies.
- **Session policies**: inline policies passed during `sts:AssumeRole`.
- **Permission boundaries**: managed policies used to cap maximum permissions.
- **Action/Resource patterns**: literal matches, wildcards (`*`, `?`), and `NotAction`/`NotResource` blocks.
  Action names match without regard to case, resource ARNs match case-sensitively, as on AWS.
- **Conditions**: support for `Condition` blocks with multiple operators.
- **Policy variables**: `${...}` placeholders in a `Resource` pattern or a `Condition` value,
  resolved from the request context.
- **Effects**: `Allow` and `Deny`.

#### Policy variables

A `${...}` placeholder in a `Resource` pattern or a `Condition` value is resolved against the
request context before it is matched, so a statement scoped to
`arn:aws:iam::*:user/${aws:username}` grants the caller their own user and nobody else's. That is
how AWS writes its own `IAMUserChangePassword` policy, and 244 of the 1,586
[managed policies](#aws-managed-policies) floci vendors carry at least one variable: of the 1,635
occurrences in them, 1,443 sit in a `Condition` value and 192 in a `Resource`.

Substitution needs `"Version": "2012-10-17"`. Under the older `2008-10-17`, which is also the
default when the element is absent, the user guide says variables "are instead treated as literal
strings in the policy", and floci matches them as written.

The rest of the rules, all from the user guide:

- In a `Resource` or `NotResource` ARN a variable resolves only in the resource portion, the part
  after the fifth colon: a variable "can't ... replace parts of the ARN before the fifth colon,
  such as the service or the account", and one written there stays literal text and matches
  nothing. One vendored managed policy,
  `AmazonTimestreamInfluxDBServiceRolePolicy`, writes `arn:*:kms:*:${aws:PrincipalAccount}:key/*`,
  in the account field, so that statement grants nothing under enforcement. floci follows the
  documented rule rather than the one policy that reads past it.
- In a `Condition` a variable resolves for the string and ARN operators only: `StringEquals`,
  `StringNotEquals`, `StringLike`, `StringNotLike`, their `IgnoreCase` forms, and `ArnEquals`,
  `ArnLike` and their negated forms. A variable is not supported with `Numeric`, `Date`, `Bool`,
  `IpAddress` or `Null`, so there the value is compared as written.
- A variable whose key has no value leaves the whole value effectively null. In `Resource` the
  pattern then "will not match any resource", and in `NotResource` that same rule means it
  excludes nothing, so the statement covers every resource. In a `Condition`, `StringEquals` and
  `StringLike` do not match while the inverted `StringNotEquals` and `StringNotLike` do.
- Key names are case-insensitive and hold no space. The guide allows a space inside a quoted
  default and nowhere else, so `${ aws:username }` is not a variable and stays the text it is,
  as do the other forms the guide's policy checks call errors: an empty `${}`, a missing closing
  brace, and a default without its quotes.
- Only a single-valued key resolves: a multivalued key cannot be used as a variable, having no one
  value to stand in, and a default does not rescue it, a default answering a key with no value
  rather than one with several.
- `${key, 'default'}` falls back to the quoted default when the key has no value.
- `${*}`, `${?}` and `${$}` stand for a literal `*`, `?` and `$`. The first two are matched as
  those characters rather than as wildcards.
- A resolved value is not escaped, so a value carrying a `*` widens the pattern it lands in. That
  is AWS's behaviour too: its own policy checks warn that variables "can be evaluated as effective
  wildcards".

Variables resolve in every document the evaluator reads: identity policies, resource policies,
session policies, permissions boundaries, SCPs, and the `Condition` of a role trust policy. A
variable can only resolve to a key floci populates, which is the list under
[Condition keys floci populates](#condition-keys-floci-populates).

A role trust policy is the narrower case, because `sts:AssumeRole` is authorized before there is a
session to describe: its context carries `aws:PrincipalArn`, `aws:PrincipalAccount`,
`aws:ResourceAccount`, `aws:RequestedRegion`, `aws:PrincipalIsAWSService`, `sts:RoleSessionName`,
and `sts:ExternalId` when the request passes one. `aws:username`, `aws:userid` and
`aws:PrincipalType` are not among them, so a variable naming one of those resolves to nothing in a
trust policy and the value it sits in matches nothing.

#### Supported Condition Operators:
- `StringEquals`, `StringNotEquals`, `StringEqualsIgnoreCase`, `StringNotEqualsIgnoreCase`
- `StringLike`, `StringNotLike`: case-sensitive glob matching with `*` and `?`.
- `ArnEquals`, `ArnLike`, `ArnNotEquals`, `ArnNotLike`: case-sensitive glob matching
  of each of the six ARN components independently. Wildcards cannot cross the first five
  colon separators; colons within the resource component are retained. `ArnEquals` and
  `ArnLike` behave identically, as do their negated forms. Service-principal trust policies
  use the same component-by-component ARN matching for `aws:SourceArn`.
- `NumericEquals`, `NumericNotEquals`, `NumericLessThan`, `NumericGreaterThan` (and Equals variants)
- `DateEquals`, `DateNotEquals`, `DateLessThan`, `DateGreaterThan` (and Equals variants)
- `Bool`, `IpAddress`, `NotIpAddress`, `Null`
- Supports `...IfExists` variants for all operators except `Null`.
- Set operators `ForAllValues:` and `ForAnyValue:` over multi-valued condition keys, in AWS's
  own spelling (the prefix match is case-sensitive). They compose with `IfExists`
  (`ForAnyValue:StringEqualsIfExists`). `ForAllValues:` matches when the context key is absent
  or its value set is empty; `ForAnyValue:` does not match either case. This follows the
  [AWS set-operator rules](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_condition-single-vs-multi-valued-context-keys.html).
  Pair `ForAllValues:` with
  `"Null":{"<key>":"false"}` as you would on AWS: `Null` treats a present-but-empty set as
  absent, so the guard fires either way.
- When a condition lists several values, a positive operator matches if the request value
  equals **any** of them; a negated operator (`StringNotEquals`, `ArnNotLike`, `NotIpAddress`,
  …) matches only if the request value differs from **all** of them. This is what makes
  `ForAllValues:StringNotEquals` on `dynamodb:Attributes` a usable deny-list.

#### Condition keys floci populates

A `Condition` operator can only match a key floci actually places in the request context.
floci populates:

- `iam:PolicyARN`: the managed policy ARN in the form body for `AttachRolePolicy`,
  `DetachRolePolicy`, `AttachUserPolicy`, `DetachUserPolicy`, `AttachGroupPolicy` and
  `DetachGroupPolicy`. The target role, user or group remains the authorized resource.
- `s3:prefix`, `s3:delimiter`, `s3:max-keys`: from the S3 request parameters.
- `aws:RequestTag/<key>`: the tags named in the request itself, before they are applied, for
  `ec2:RunInstances` (`TagSpecification.N`), `ec2:CreateTags` (`Tag.N`) and
  `s3:PutBucketTagging` (the `<Tagging>` body), and for every create in the tag-on-create table
  whose action takes it, as described under the tag-on-create check above.
- `aws:ResourceTag/<key>`: the target resource's current tags, for `ec2:CreateTags`,
  `ec2:DeleteTags`, `ec2:TerminateInstances` and `ec2:DescribeInstances` (the first
  `ResourceId.N` or `InstanceId.N`), and for `s3:GetBucketTagging`, `s3:DeleteBucket` and the
  `s3:PutBucketTagging` that `DeleteBucketTagging` is checked as (the bucket). A request naming several EC2 resources is evaluated
  once per resource and denied when any of them fails the condition, as on AWS.
  `logs:CreateLogStream`, `logs:DeleteLogStream`, `logs:TagResource`, `logs:UntagResource`,
  `logs:ListTagsForResource`, `logs:DeleteLogGroup`, `logs:PutRetentionPolicy` and
  `logs:DeleteRetentionPolicy` use the owning log group's tags.
- `s3:ExistingObjectTag/<key>`: the tags already on the target object version, for
  `s3:GetObject`, `s3:GetObjectTagging`, `s3:GetObjectAcl`, `s3:PutObjectAcl`,
  `s3:DeleteObjectTagging`, `s3:PutObjectTagging` and the annotation actions, and for their
  version actions (`s3:GetObjectVersion`, `s3:GetObjectVersionTagging` and so on). A `versionId` in the request
  selects the version whose tags are read. **`s3:DeleteObject` and `s3:PutObject` do not receive this key**, as measured on AWS.
  An allow conditioned on it denies the delete of a correctly tagged object, and a create cannot
  be gated on tags an object does not have yet.
- `s3:RequestObjectTag/<key>`: a tag the request asks to attach. `s3:PutObject` reads these
  from the `x-amz-tagging` header, or its query parameter when the header is absent,
  and `s3:PutObjectTagging` from the `<Tagging>` body. A `CopyObject` offers them only with
  `x-amz-tagging-directive: REPLACE`, from the same header or query source the copy then applies:
  under the default `COPY` the copy keeps the source's tags and ignores both. Any pair
  that does not decode is dropped, so a policy conditioned on the key denies such a request. Where
  enforcement lets it through, the handler still answers a malformed header with
  `400 InvalidTag`.
- `s3:RequestObjectTagKeys`: the case-sensitive requested object tag keys from the same
  `PutObject` header/query source or `PutObjectTagging` body. Header and query tag keys
  are URL-decoded before matching; a present header takes precedence over the query.
  `ForAllValues:StringEquals` can limit all requested keys without a `Null` guard; an
  omitted or empty tag set still follows the absent-key rules above. Existing object tags
  do not contribute to this requested set. See the [AWS object-tag policy guidance](https://docs.aws.amazon.com/AmazonS3/latest/userguide/tagging-and-policies.html).
- `s3:versionid`: the `versionId` of the request, for the version actions it selects.
- `s3:object-lock-mode`, `s3:object-lock-retain-until-date`,
  `s3:object-lock-remaining-retention-days` and `s3:object-lock-legal-hold`: the Object Lock
  settings a request applies, for `s3:PutObject` (from its `x-amz-object-lock-*` headers),
  `s3:PutObjectRetention` (the `<Retention>` body) and `s3:PutObjectLegalHold` (the `<LegalHold>`
  body). AWS does not document how it counts the remaining retention days; floci counts a part
  day as a whole one, so a Deny on retention longer than N days refuses a date a few hours past
  N days.
- An object creation offers its tag and Object Lock keys to each action it is checked as: its
  `s3:PutObject` and the extra actions its settings need.
- `iam:PassedToService`: the service principal a request passes a role to, for the
  `iam:PassRole` check described above.
- A `PutObject` carrying `If-Match` is authorized as `s3:GetObject` as well, and that second
  check is made without the object's tags in the context, as measured on AWS. `CopyObject` and
  `CompleteMultipartUpload`, the other operations the S3 user guide lists for `If-Match`, get the
  same check; `CreateMultipartUpload` and `UploadPart` ignore the header. `If-None-Match` needs no
  such permission.
- `aws:PrincipalArn`: the caller's ARN, resolved from the signing access key. It is the
  IAM-user ARN for a user access key, and `arn:aws:iam::<account>:root` for the bare account-id
  key (floci's account-root principal), matching the ARN shape AWS itself reports for the account
  root. For an STS role session it is the ARN of the role that was assumed, path included, not the
  `assumed-role` session ARN, as AWS reports it ("For IAM roles, the request context returns the
  ARN of the role"); a condition naming the session ARN does not match. It is **absent** only for
  unknown keys, where nothing about the caller can be resolved.
- `aws:PrincipalType` and `aws:userid`: resolved from the same lookup as `aws:PrincipalArn`, so
  they always describe the same credential. The values follow the user guide's principal key
  values list: `Account` with the account id for the account-root principal and for a session
  token it minted, `User` with the user's own id for an IAM user access key and for a session
  token that user minted, `FederatedUser` with `account:name` for a `GetFederationToken` session,
  and `AssumedRole` with `role-id:session-name` for a role session, which for a role assigned to
  an EC2 instance is `role-id:instance-id`. The guide renders the role-session type both as
  `AssumedRole` and as "Assumed role"; floci uses the unspaced form, which is the one that fits
  the vocabulary of every other value in that list and the one AWS publishes machine-side. The
  guide's fifth value, `Anonymous`, is **not** populated: it applies to unsigned callers on S3,
  SQS and SNS, which reach a resource policy through its own evaluation path rather than through
  the enforcement filter, so a bucket policy conditioned on `aws:PrincipalType` being `Anonymous`
  does not match an unsigned request.
- `aws:username`: the IAM user's name, and **only** for an IAM user, whether it signed with its
  own access key or with a session token it minted. The guide is explicit that it is "always
  included in the request context for IAM users" while "anonymous requests and requests that are
  made using the AWS account root user or IAM roles do not include this key", so for a role
  session, a federated user or the account root it is absent, and a statement that matches on it
  cannot match: an absent key has no equal or like value. That absence is the enforcement rather
  than a gap in it.
- `aws:TagKeys`: the case-sensitive requested tag keys for EC2 `RunInstances` and
  `CreateTags`, S3 `PutBucketTagging`, and every create in the tag-on-create table whose action
  takes it. `ForAllValues:StringEquals` can restrict the
  complete requested set without a `Null` guard; no tags still means absent context.
- `dynamodb:LeadingKeys`, `dynamodb:Attributes`, `dynamodb:Select`: from the DynamoDB request
  body, for `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem` and
  `BatchWriteItem` (`dynamodb:Select` for `Query` and `Scan`). `LeadingKeys` holds the
  partition-key values the request names: from `Key`, `Item`, the `KeyConditionExpression`
  equality, the legacy `KeyConditions` `EQ` entry, or each `RequestItems` entry. `Attributes`
  holds the attribute names the request *names* (item and key fields, `AttributesToGet`,
  projection / update / filter / condition / key-condition expressions, and
  `ExpressionAttributeNames`); a request with no projection returns every attribute while
  reporting only the names it mentions, exactly as on AWS, which is why AWS pairs `Attributes`
  with `dynamodb:Select`. Query equality supports the attribute on either side, including aliases.
  Each batch entry uses its own table schema; authorization evaluates each table's requested
  keys separately, combining name and ARN entries for the same table. Each key is **omitted**
  when it cannot be determined, such as an unknown table, a missing partition attribute, or
  an unparseable expression. A `ForAllValues` condition matches absent context; add
  `Null:false` when the policy must require a resolved key. Malformed requests that pass
  authorization are still checked by the DynamoDB handler.

- `dynamodb:ReturnValues`: standalone `PutItem`, `UpdateItem` and `DeleteItem` authorize each
  distinct non-`NONE` mode requested by `ReturnValues` and `ReturnValuesOnConditionCheckFailure`
  before execution. When neither requests a non-`NONE` mode, authorization uses `NONE`.
  Each mode is checked separately, so a grant must cover both `UPDATED_NEW` and `ALL_OLD`
  when they are requested together; `NONE` plus `ALL_OLD` needs only `ALL_OLD`. Omitted or
  null parameters use the execution default. Read and batch operations do not acquire this
  key from unrelated fields. Transaction failure-image context is described below.

**Any other condition key is absent from the request context.** A positive scalar operator
without `IfExists` on an absent key makes the whole statement *not apply*: it neither matches nor
blocks. A `DenyRootUser`-style guardrail keyed on `aws:PrincipalArn` therefore fires against
the account root the same way it does on real AWS, consistent with the account root already
being bounded by SCPs (below): both forms of root enforcement now agree. A negated operator
(`StringNotEquals`, `ArnNotLike`, `NotIpAddress` and the rest) is the exception, as on AWS: an
absent key cannot equal what the policy names, so the condition holds, and a `Deny` written
that way applies when the key is missing.

The keys that say who is calling are written onto every context a request is evaluated against:
the one the enforcement filter builds, one per resource the request names, one per transaction
item, the one S3 builds when it evaluates a bucket policy itself under `s3.enforce-auth`, and the
trust policy `sts:AssumeRole` reads, which sees the caller through the same writer. A trust policy
keyed on `aws:userid`, `aws:PrincipalType` or `aws:username` therefore matches the calling user or
session, and a value the caller carries wins over one the request supplied. A credential that
resolves to no IAM identity is the account root stand-in, which carries the account id as its
`aws:userid` and `Account` as its `aws:PrincipalType`. One caller carries no `aws:userid`: the role
Step Functions assumes for a task, which is a session and says so with `AssumedRole`, but is minted
without a session name, and a session's `aws:userid` is `role-id:session-name`.

Transactions check each item as `PutItem`, `UpdateItem`, `DeleteItem`, `GetItem`, or
`ConditionCheckItem` before executing the request. Each item uses its own table, leading key
and requested attributes. `dynamodb:EnclosingOperation` identifies `TransactWriteItems` or
`TransactGetItems`; write items expose `ReturnValuesOnConditionCheckFailure` as
`dynamodb:ReturnValues` only when the request supplies a non-null value. An omitted value leaves
the condition key absent. Transaction API names alone do not grant item access.
Transaction item authorization uses the effective local table ARN, matching execution.
A permission on a foreign account table cannot grant access to a same-named local table.

**Not yet supported**: `dynamodb:LeadingKeys` for `Scan` and the PartiQL operations.

### Assumed roles

When a caller uses `sts:AssumeRole` the returned session credentials are registered internally. Subsequent requests signed with those session credentials are evaluated against:
1. The **role's** attached and inline policies.
2. The **session policy** (if provided during `AssumeRole`), acting as an intersection filter.

### Example — minimal enforcement setup

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a user and get credentials
aws iam create-user --user-name alice
KEY=$(aws iam create-access-key --user-name alice --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text)
AKID=$(echo $KEY | awk '{print $1}')
SECRET=$(echo $KEY | awk '{print $2}')

# Create and attach a policy that allows S3 list
POLICY_ARN=$(aws iam create-policy \
  --policy-name allow-s3-list \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:ListAllMyBuckets","Resource":"*"}]}' \
  --query 'Policy.Arn' --output text)

aws iam attach-user-policy --user-name alice --policy-arn $POLICY_ARN

# alice can now list buckets
AWS_ACCESS_KEY_ID=$AKID AWS_SECRET_ACCESS_KEY=$SECRET \
  aws s3 ls
```

## Service Control Policies (SCPs)

When `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED=true`, service control policies attached to
the caller account's organization participate in policy evaluation.

The SCP chain is resolved root → OUs on the path → account, and every level must allow the action
for the request to proceed. SCPs never grant permissions on their own — they are a ceiling applied
before identity policies are consulted, exactly as on AWS. Organizations is resolved lazily, so IAM
does not gain a hard dependency on it: with the Organizations service absent or the flag off, the
chain is skipped entirely and evaluation falls back to identity policies alone.

A member account's own bare 12-digit access key is floci's account-root principal. It is not a
registered IAM identity, but like the AWS account root it is still bounded by SCPs, so an SCP `Deny`
(for example a `DenyLeaveOrganization` guardrail) blocks it. What that key does *not* carry is any
identity policy — it behaves as allow-everything bounded only by the SCP ceiling. To exercise
**identity-policy** enforcement, use account-routable credentials for the member instead, most
naturally the `ASIA…` session from assuming its `OrganizationAccountAccessRole`.

Note that `aws:PrincipalArn` is populated for the account-root principal too, as
`arn:aws:iam::<account>:root`, so a principal-scoped guardrail keyed on `aws:PrincipalArn`
(for example a `DenyRootUser` statement matching `arn:aws:iam::*:root`) fires against it —
consistent with the account root already being bounded by SCPs above.

A level containing an SCP document that fails to parse denies every action at that level. The
ceiling cannot tell what an unreadable guardrail would have said, and every target also carries
`FullAWSAccess`, so dropping the bad document would leave the level allowing everything.

The ceiling is attached by the request filter, which is the only producer of SCP levels. Two
evaluation paths therefore run without one and are **not** SCP-bounded: `SimulatePrincipalPolicy`,
and the field-level authorization check on the AppSync GraphQL IAM-auth path (the coarse AppSync
request itself still passes through the filter). Both are deliberate non-goals — bounding them
would require IAM to resolve the caller's organization directly, which is the dependency the
lazily-resolved `ScpProvider` exists to avoid.

## Unsigned requests

With enforcement on, a request carrying no `Authorization` header is refused with
`403 MissingAuthenticationToken` when its wire shape names a management operation: a JSON, CBOR or
Query request, identified by `X-Amz-Target`, an rpcv2 path or an `Action` parameter. A call routed
to the JSON, CBOR, rpcv2 or Query endpoint is one whatever its `smithy-protocol` header or content
type says: with `FLOCI_PROTOCOLS_STRICT_CLAIMING` off (the default), a request whose protocol
those signals leave unclear is still served, and is judged by the endpoint that serves it.

Two carve-outs, both deliberate.

**Operations AWS serves without credentials are still allowed.** Signing up or signing in to a
Cognito user pool, and `AssumeRoleWithWebIdentity`, happen before the caller has any AWS
credentials, so AWS marks them as needing none and Floci does the same. The list is taken from the
`authtype: none` trait in AWS's own service models and covers the Cognito user-pool and identity
flows and the two web-identity and SAML `AssumeRole` calls. A Cognito `Admin*` operation is not in that set and does require a signature.

The refusal arrives in the encoding the request used: XML for Query, CBOR for a CBOR request, JSON
otherwise.

**REST requests are not checked.** The same filter sees the API Gateway execute path, Lambda
function URLs, CloudFront serving, the Cognito OIDC endpoints and Floci's own health endpoint, all
of which are unsigned by design, and a REST request does not say which service will serve it. An
unsigned REST call therefore still reaches the service, including an unsigned S3 call.

**A header with no readable credential does not get around this.** An `Authorization` header that
names no access key (a Bearer token, SigV2, a credential without the SigV4 scheme or without its
key), or whose SigV4 scope is missing a part, is refused on the same management calls with
`400 IncompleteSignature`, the error AWS gives a header it cannot read. A REST request carrying one
is refused only when the header claims SigV4 and the route maps to an IAM action. Otherwise it is
treated as unsigned, because AppSync, CodeArtifact's package endpoints, Identity Store's SCIM
endpoint and API Gateway's authorizers take Bearer and Basic tokens of their own. An empty header
is refused the same way, unless a presigned URL's `X-Amz-Credential` is there to read instead.

## Bypass rules

Enforcement is deliberately permissive in a few cases, so that enabling it does not break workloads
the emulator cannot reason about:

| Case | Behaviour |
| --- | --- |
| Unresolvable action | Allowed. An action the registry cannot resolve is not evaluated. The credential is still checked on an RPC call, as in the rows below. A REST request is left alone, as an unsigned one is: the filter cannot tell it from the API Gateway execute path, Lambda function URLs and the other paths it sees that are unsigned by design. |
| No `Authorization` header, RPC protocol | **Rejected** with `403 MissingAuthenticationToken`, unless the operation is one AWS itself serves without credentials. |
| No `Authorization` header, REST protocol | Allowed. This filter also sees the API Gateway execute path, Lambda function URLs, CloudFront serving and the Cognito OIDC endpoints, which are unsigned by design. |
| `sts:GetCallerIdentity` | No policy is evaluated: AWS returns caller identity even when a policy denies it. The credential is still checked: a key that exists nowhere or is inactive, an expired session, or a header with no readable credential is refused as below. |
| Access key that exists nowhere | **Rejected** with `403`, in each protocol's own vocabulary: `InvalidAccessKeyId` for S3, `InvalidClientTokenId` for Query services, `UnrecognizedClientException` for JSON services. Allowing it would let any string authorize the request. A presigned POST is the exception: its credential is in the form body, so a key that exists nowhere, or a credential that cannot be read, is left to S3's own presigned-POST check, and uploads as an unsigned request would when that check is off. An inactive key or an expired session is refused there as everywhere else. |
| Inactive access key | **Rejected** the same way: a deactivated key can no longer be used by API calls until it is activated again. |
| Expired session credentials | **Rejected** with `403 ExpiredTokenException`, or `400 ExpiredToken` for S3. Once Floci has removed an expired session it no longer knows the key, and answers as for one that exists nowhere. |
| `Authorization` header with no readable credential | **Rejected** with `400`, in each protocol's own vocabulary: `AuthorizationHeaderMalformed` for S3 (`AuthorizationQueryParametersError` when the credential is a presigned URL's `X-Amz-Credential`), `IncompleteSignature` for Query services, `IncompleteSignatureException` for JSON services. Without a key or a scope there is nothing to check, so allowing it would skip enforcement altogether. As with no header, an operation AWS serves without credentials is left alone, and a REST request is rejected only when the header claims SigV4. |
| Known credential with no mappable caller context | Allowed. A stored session carrying no role ARN is a real credential, so it is not treated as unauthenticated. |
| Bare account-id key with no SCP ceiling | Allowed. With no organization or SCP enforcement off, the account root keeps the historical bypass. |
| Bare account-id key **with** an SCP ceiling | Enforced as the account root, bounded by the SCP chain. |

## Service-linked roles

`CreateServiceLinkedRole` puts a role under `/aws-service-role/<principal>/` and marks it as
service-linked. As on AWS, a role carrying that mark is protected: `AttachRolePolicy`,
`DetachRolePolicy`, `PutRolePolicy`, `DeleteRolePolicy`, `PutRolePermissionsBoundary`,
`DeleteRolePermissionsBoundary`, `UpdateAssumeRolePolicy`, `AddRoleToInstanceProfile`,
`RemoveRoleFromInstanceProfile` and `DeleteRole` all answer `UnmodifiableEntity` and name the
linked service to go through instead. The description is the one field AWS leaves editable, "from
the IAM console, API, or CLI", so `UpdateRoleDescription` goes through on a service-linked role and
so does an `UpdateRole` carrying only a `Description`; `UpdateRole` answers `UnmodifiableEntity`
once it carries `MaxSessionDuration`, and then writes neither field. `TagRole` and `UntagRole` are
allowed, as on AWS. Within the IAM API `DeleteServiceLinkedRole` is the only way to remove such a
role — the emulator's own `/_floci/state/reset` still clears it along with everything else.

Four deviations to be aware of:

- **The role name comes from a table of AWS evidence, and is derived only where that table does
  not reach.** The name is not computable from the service principal: `acm.amazonaws.com` is
  `AWSServiceRoleForCertificateManager` and `lex.amazonaws.com` is `AWSServiceRoleForLexBots`,
  where capitalising the principal would give `AWSServiceRoleForAcm` and `AWSServiceRoleForLex`.
  `src/main/resources/iam/service-linked-roles.tsv` carries the name AWS mints, with the count in
  its own header where the generator keeps it right, generated by
  `tools/slr/service_linked_roles.py` and gated by `make slr-check`, and
  each row records whether it rests on a recorded create, on a role ARN in AWS's own managed policy
  documents, or on a cited page of AWS documentation. A principal outside the table still gets the
  derived name, so reading the name back from the create response is still better than computing
  it, but for a service in the table it now matches AWS.
- **A `CustomSuffix` is refused for the services AWS is recorded refusing it.** The API Reference
  says only that "some services do not support the CustomSuffix parameter" without listing them, so
  the same table carries the recorded answer: 68 services reply `InvalidInput` "Custom suffix is not
  allowed for `<service>`" and three take one, `autoscaling`, `connect` and `lexv2`. Only a recorded
  create can establish this, so a service the recordings miss keeps taking a suffix rather than
  being refused on an inference. Two table names are long enough that a suffix alone breaches the
  64-character `RoleName` limit, which is `InvalidInput` for the length rather than for the suffix.
  Creating the same service-linked role twice is `InvalidInput` naming the role, "Service role name
  `<name>` has been taken in this account, please try a different suffix."
- **Deletion is synchronous.** `DeleteServiceLinkedRole` completes before it returns, so the
  task id it hands back is already finished and `GetServiceLinkedRoleDeletionStatus` always
  reports `SUCCEEDED`. The `IN_PROGRESS`, `NOT_STARTED` and `FAILED` states never occur, and no
  failure `Reason` is ever returned — a poll loop works, but its failure branch is never taken.
- **`CreateRole` accepts the `/aws-service-role/` path, which AWS reserves.** AWS rejects that
  prefix on `CreateRole`; Floci allows it and treats the result as an ordinary role, since the
  service-linked mark comes from the action that minted the role rather than from its path. Such
  a role stays fully modifiable, and `DeleteServiceLinkedRole` answers `NoSuchEntity` for it.

## Partitions

IAM is global, so its ARNs take the partition of the region a request is signed for:
`arn:aws-cn:iam::<account>:role/...` for a China-signed request. AWS managed policies keep the
`aws` owner in every partition (`arn:aws-cn:iam::aws:policy/ReadOnlyAccess`), with the commercial
catalog's documents rewritten for the partition. An assumed-role session stays in the partition
of the role it was issued for, whatever region later calls are signed for. See [AWS Partitions](../configuration/partitions.md).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IAM_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED` | `false` | Enforce IAM policies on all inbound requests |
| `FLOCI_SERVICES_IAM_SEED_DEPLOYER_PRINCIPAL` | `false` | Seed the optional `floci-deployer` user and `floci` / `floci` access key |
| `FLOCI_SERVICES_IAM_ACCOUNT_ALIAS` | _(unset)_ | Seed an account alias at startup; unset means the account has no alias |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a role
aws iam create-role \
  --role-name lambda-execution-role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "lambda.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }' \
  --endpoint-url $AWS_ENDPOINT_URL

# Attach a managed policy
aws iam attach-role-policy \
  --role-name lambda-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a user
aws iam create-user --user-name alice --endpoint-url $AWS_ENDPOINT_URL

# Create an access key
aws iam create-access-key --user-name alice --endpoint-url $AWS_ENDPOINT_URL

# List roles
aws iam list-roles --endpoint-url $AWS_ENDPOINT_URL
```
