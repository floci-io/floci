# SES

**Protocol:** Query (XML) with `Action=` parameter
**Endpoint:** `POST http://localhost:4566/`

Floci exposes the classic Amazon SES Query API used by `aws ses ...` commands and SDKs targeting SES v1.

## Supported Actions

| Action                              | Description                                               |
|-------------------------------------|-----------------------------------------------------------|
| `VerifyEmailIdentity`               | Mark an email address as verified                         |
| `VerifyEmailAddress`                | Legacy alias for email verification                       |
| `VerifyDomainIdentity`              | Mark a domain as verified and return a verification token |
| `DeleteIdentity`                    | Delete an email or domain identity                        |
| `ListIdentities`                    | List verified identities                                  |
| `GetIdentityVerificationAttributes` | Get verification status for one or more identities        |
| `SendEmail`                         | Send a structured email with text or HTML body            |
| `SendRawEmail`                      | Send a raw MIME payload                                   |
| `SendTemplatedEmail`                | Send an email by resolving a stored template             |
| `SendBulkTemplatedEmail`            | Send a templated email to multiple destinations          |
| `CreateTemplate`                    | Create an email template with subject / text / html parts |
| `GetTemplate`                       | Read a stored template                                    |
| `UpdateTemplate`                    | Replace the content of a stored template                  |
| `DeleteTemplate`                    | Remove a stored template                                  |
| `ListTemplates`                     | List stored templates                                     |
| `TestRenderTemplate`                | Render a stored template against supplied data, returning the MIME message |
| `CreateCustomVerificationEmailTemplate` | Create a custom verification email template               |
| `GetCustomVerificationEmailTemplate`    | Return a custom verification email template               |
| `ListCustomVerificationEmailTemplates`  | List custom verification email templates (no content)     |
| `UpdateCustomVerificationEmailTemplate` | Replace a custom verification email template              |
| `DeleteCustomVerificationEmailTemplate` | Delete a custom verification email template               |
| `SendCustomVerificationEmail`           | Send a custom verification email and register the recipient as a pending identity |
| `GetSendQuota`                      | Return local send quota counters                          |
| `GetSendStatistics`                 | Return aggregate delivery stats for sent messages         |
| `GetAccountSendingEnabled`          | Report whether sending is enabled                         |
| `UpdateAccountSendingEnabled`       | Enable or disable account-wide sending                    |
| `ListVerifiedEmailAddresses`        | List verified email identities                            |
| `DeleteVerifiedEmailAddress`        | Delete a verified email identity                          |
| `SetIdentityNotificationTopic`      | Set the SNS topic for an identity's bounce/complaint/delivery notifications |
| `GetIdentityNotificationAttributes` | Read stored notification topic settings                   |
| `SetIdentityFeedbackForwardingEnabled`     | Toggle feedback forwarding for an identity        |
| `SetIdentityHeadersInNotificationsEnabled` | Toggle headers-in-notifications per notification type |
| `SetIdentityMailFromDomain`         | Set or clear the MAIL FROM domain for an identity         |
| `GetIdentityMailFromDomainAttributes` | Read MAIL FROM domain settings                          |
| `GetIdentityDkimAttributes`         | Return DKIM status for identities (an email inherits its domain's DKIM) |
| `SetIdentityDkimEnabled`            | Enable or disable DKIM signing for an identity            |
| `VerifyDomainDkim`                  | Return a domain's (stable) DKIM CNAME tokens              |
| `PutIdentityPolicy`                 | Create or replace a sending-authorization policy on an identity |
| `GetIdentityPolicies`               | Return the requested policies for an identity             |
| `ListIdentityPolicies`              | List an identity's policy names                           |
| `DeleteIdentityPolicy`              | Delete a policy from an identity                          |
| `CreateConfigurationSet`            | Create a configuration set                                |
| `DescribeConfigurationSet`          | Read a configuration set                                  |
| `ListConfigurationSets`             | List configuration sets                                   |
| `DeleteConfigurationSet`            | Delete a configuration set                                |
| `CreateConfigurationSetEventDestination` | Attach an event destination to a configuration set        |
| `UpdateConfigurationSetEventDestination` | Update an existing event destination on a configuration set |
| `DeleteConfigurationSetEventDestination` | Remove an event destination from a configuration set      |
| `UpdateConfigurationSetSendingEnabled`   | Enable or disable email sending through a configuration set |
| `CreateConfigurationSetTrackingOptions`  | Set the custom open/click tracking redirect domain |
| `UpdateConfigurationSetTrackingOptions`  | Change the custom tracking redirect domain |
| `DeleteConfigurationSetTrackingOptions`  | Remove the custom tracking redirect domain |
| `UpdateConfigurationSetReputationMetricsEnabled` | Enable or disable reputation metrics for a configuration set |
| `PutConfigurationSetDeliveryOptions` | Set the TLS policy (delivery options) for a configuration set |
| `CreateReceiptRuleSet`              | Create a receipt rule set (stored inertly)                |
| `DescribeReceiptRuleSet`            | Read a receipt rule set and its rules                     |
| `ListReceiptRuleSets`               | List receipt rule sets                                    |
| `DeleteReceiptRuleSet`              | Delete a receipt rule set (idempotent, cascades rules)    |
| `SetActiveReceiptRuleSet`           | Mark a rule set active, or clear the active one           |
| `DescribeActiveReceiptRuleSet`      | Read the active receipt rule set                          |
| `ReorderReceiptRuleSet`             | Reposition every rule in a set (exact permutation required) |
| `CloneReceiptRuleSet`               | Copy a rule set and its rules under a new name            |
| `CreateReceiptRule`                 | Add a receipt rule (stored inertly, action targets validated) |
| `DescribeReceiptRule`               | Read a receipt rule                                       |
| `UpdateReceiptRule`                 | Replace a receipt rule in place                           |
| `DeleteReceiptRule`                 | Delete a receipt rule (idempotent)                        |
| `SetReceiptRulePosition`            | Reorder a receipt rule within its set                     |
| `CreateReceiptFilter`               | Create an IP address filter (stored inertly)              |
| `ListReceiptFilters`                | List IP address filters                                   |
| `DeleteReceiptFilter`               | Delete an IP address filter (idempotent)                  |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SES_ENABLED` | `true` | Enable or disable the SES service |
| `FLOCI_SERVICES_SES_SMTP_HOST` | *(unset)* | SMTP server host for email relay (empty = store only) |
| `FLOCI_SERVICES_SES_SMTP_PORT` | `25` | SMTP server port |
| `FLOCI_SERVICES_SES_SMTP_USER` | *(unset)* | SMTP authentication username |
| `FLOCI_SERVICES_SES_SMTP_PASS` | *(unset)* | SMTP authentication password |
| `FLOCI_SERVICES_SES_SMTP_STARTTLS` | `DISABLED` | STARTTLS mode: `DISABLED`, `OPTIONAL`, or `REQUIRED` |

### SMTP Relay

When `smtp-host` is configured, every send operation forwards the email
to the specified SMTP server in addition to storing it in the local
inspection endpoint: `SendEmail` (simple, raw, and templated content),
`SendRawEmail`, `SendTemplatedEmail`, `SendBulkTemplatedEmail`,
`SendBulkEmail`, and `SendCustomVerificationEmail`. This enables
integration testing with tools like
[Mailpit](https://mailpit.axllent.org/) or any standard SMTP server.

```yaml
# docker-compose.yml
services:
  floci:
    image: floci/floci:latest
    ports: ["4566:4566"]
    environment:
      FLOCI_SERVICES_SES_SMTP_HOST: mailpit
      FLOCI_SERVICES_SES_SMTP_PORT: 1025
    networks: [floci]

  mailpit:
    image: axllent/mailpit
    ports:
      - "8025:8025"   # Web UI
      - "1025:1025"   # SMTP
    networks: [floci]

networks:
  floci:
```

- Emails are always stored locally regardless of relay: the
  `/_aws/ses` inspection endpoint works with or without SMTP.
- Relay failures are logged but do not affect the API response.
- Recipients on the suppression list are filtered out before the relay.
  A send whose recipients are all suppressed is still stored, but never
  reaches the SMTP server.

#### Raw message fidelity

Raw MIME messages are parsed with Apache Mime4j and relayed as a
re-encoded message that preserves the original content:

- The first `text/plain` and `text/html` parts become the message body.
- Attachments are preserved with their filename, content type,
  disposition, and description. Parts carrying a `Content-ID` are
  relayed as inline attachments, so `cid:` references in an HTML body
  still resolve. An embedded `message/rfc822` part is relayed whole.
- Custom top-level headers are copied through. The MIME structural
  headers (`MIME-Version`, `Content-Type`, `Content-Transfer-Encoding`)
  are regenerated for the re-encoded message, and the address, subject,
  and `Return-Path` headers are applied to the outgoing message
  directly rather than copied.
- `Date` and `Message-ID` are replaced rather than copied, matching the
  [SES header fields reference](https://docs.aws.amazon.com/ses/latest/dg/header-fields.html):
  AWS overrides a caller's `Date` with the time it accepted the message,
  and a caller's `Message-ID` with the id it assigned.
- Display names on addresses are kept, so
  `Alice <alice@example.com>` is delivered as written. The bare
  addresses are what the SMTP envelope, the suppression checks, and the
  published events use.
- `RawMessage.Data` / `Content.Raw.Data` is accepted either
  base64-encoded (what the AWS SDKs send) or as plain RFC 5322 text.

#### Envelope sender

AWS routes bounce and complaint notifications to the address in the
`Return-Path` header. Floci resolves that address in the order:

1. The `Return-Path` header on a raw MIME message
2. The `ReturnPath` request field (v1) or
   `FeedbackForwardingEmailAddress` (v2)
3. The source address

The resolved value is used as the SMTP `MAIL FROM` address, which is
where a receiving mail server routes bounces, and is recorded as
`ReturnPath` on the stored message so the inspection endpoint shows it.

!!! note "Deviations"
    On AWS the delivered message carries a `Return-Path` that differs
    from the one you supplied, because AWS routes bounces through its
    own address and forwards them on. Floci has no bounce-handling
    pipeline, so it puts the resolved address directly in `MAIL FROM`
    instead, which is the closest local equivalent.

    Floci also does not deliver bounce and complaint notifications to
    the return path at all. Notification targets are resolved from the
    identity (`SetIdentityNotificationTopic`) and from the configuration
    set's event destinations instead.

#### Message-ID

AWS overrides any caller-supplied `Message-ID` with the id it assigned,
which is the `MessageId` returned by the API. Floci does the same,
stamping `<{MessageId}@email.amazonses.com>`.

!!! note
    The local part is the returned `MessageId`, which is the documented
    part of this behaviour. The `email.amazonses.com` domain is not
    specified in the AWS documentation and follows observed SES output.

#### X-SES control headers

On a raw send, Floci honours the SES control headers and strips them
from the relayed message rather than forwarding them:

| Header | Effect |
|---|---|
| `X-SES-CONFIGURATION-SET` | Names the configuration set to apply when the request does not specify one |
| `X-SES-MESSAGE-TAGS` | Comma-separated `name=value` message tags, used for event publishing |

Per
[Step 3: Specify your configuration set](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-send-email.html),
when message tags are supplied both as a request field and as a header,
AWS uses only the request field's tags and does not join the two sets.
Floci matches this: header tags apply only when the request passes none.

The sending-authorization headers `X-SES-SOURCE-ARN`, `X-SES-FROM-ARN`, and
`X-SES-RETURN-PATH-ARN` are read for the verified-sender check (see
[Current Behavior](#current-behavior)): the first instance of each counts, and the
matching `SourceArn`, `FromArn` or `ReturnPathArn` request parameter wins over
it, as on AWS. All `X-SES-*` headers are stripped from the relayed message;
the ones not listed here are not acted on.

## Local Inspection Endpoint

For test assertions and debugging, Floci exposes a LocalStack-compatible mailbox endpoint:

- `GET /_aws/ses` lists captured messages, oldest first by `Timestamp`
- `GET /_aws/ses?id=<message-id>` returns a specific captured message
- `GET /_aws/ses?email=<address>` returns the messages whose `Source` equals that value exactly, so a sender given as `Name <a@example.com>` is matched in that form; with `id` as well, both filters apply
- `DELETE /_aws/ses` clears the captured mailbox
- `DELETE /_aws/ses?id=<message-id>` deletes only that message; an unknown id deletes nothing

The `email` filter matches the sender, as on [LocalStack](https://docs.localstack.cloud/aws/services/ses/), not a recipient. Earlier Floci versions matched it against `ToAddresses`; to find the messages sent to an address, list them all and filter on `Destination.ToAddresses`.

Messages are stored locally by Floci and can be persisted when SES storage is backed by persistent or hybrid storage.

Alongside the LocalStack fields, each captured message carries a
`ReturnPath` holding the resolved envelope sender described under
[SMTP Relay](#smtp-relay). A message the content scan rejected carries
`RejectReason` and none of its content: no `Subject`, `Body`, `Headers`,
`ReplyToAddresses` or `RawData`, in neither the Simple nor the raw shape.

A v2 `SendEmail` with `Content.Simple.Attachments` keeps the Simple shape and
adds two fields: `Attachments`, one entry per attachment with `FileName`,
`ContentType`, `ContentDisposition` (`ATTACHMENT` or `INLINE`), `ContentId`
(angle-bracketed, when given), `ContentDescription` (when given) and `Size`
in decoded bytes; and `RawData`, the assembled MIME message, base64-encoded,
as the request addressed it: every `To` and `Cc` recipient the request named,
including any suppression later kept from delivery, and no `Bcc` header. The content scan runs over
that MIME message as it does for a raw send. An `INLINE` attachment with a
`ContentId` is placed in a `multipart/related` part next to the HTML body.
A `ContentType` that is not given is guessed from the file name, falling back
to `application/octet-stream`.
`ContentTransferEncoding` is validated but not applied: every attachment part
is base64-encoded, whatever the request asks for.

A message carrying an attachment whose file name ends in one of the
[unsupported attachment types](https://docs.aws.amazon.com/ses/latest/dg/attachments.html#mime-types)
(`.exe`, `.bat`, `.js`, `.vbs` and the rest of that list, in any case) is
refused with `MessageRejected` and nothing is recorded, for a Simple send with
attachments and a raw send alike. The name is read from `Content-Disposition`,
or from the `name` parameter of `Content-Type`, in every MIME part, including
a forwarded message. AWS does not document the error, so the message text is
Floci's own.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Verify sender and recipient identities
aws ses verify-email-identity \
  --email-address sender@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

aws ses verify-email-identity \
  --email-address recipient@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# Verify a domain
aws ses verify-domain-identity \
  --domain example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# List all identities
aws ses list-identities \
  --endpoint-url $AWS_ENDPOINT_URL

# Send a plain-text email
aws ses send-email \
  --from sender@example.com \
  --destination ToAddresses=recipient@example.com \
  --message "Subject={Data=Hello},Body={Text={Data=Sent from Floci SES}}" \
  --endpoint-url $AWS_ENDPOINT_URL

# Send a raw MIME email
aws ses send-raw-email \
  --raw-message Data="$(printf 'Subject: Raw test\r\n\r\nHello from raw SES')" \
  --source sender@example.com \
  --destinations recipient@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# Inspect locally captured messages
curl $AWS_ENDPOINT_URL/_aws/ses
```

## Current Behavior

- An email address identity is verified as soon as it is created; no inbox verification flow is required. A domain identity stays `Pending` until its DKIM CNAME records exist in a Route 53 hosted zone in Floci, then becomes `Success`.
- `SendEmail`, `SendRawEmail`, `SendTemplatedEmail` and `SendBulkTemplatedEmail`, and v2 `SendEmail` and `SendBulkEmail`, require verified senders, as on AWS ([SendEmail](https://docs.aws.amazon.com/ses/latest/APIReference/API_SendEmail.html), [Email sending errors](https://docs.aws.amazon.com/ses/latest/dg/troubleshoot-error-messages.html)): each address used as a "From", "Source", "Sender" or "Return-Path" address must be a verified email address identity or an address in a verified domain identity or one of its subdomains. That is the `Source` or `FromEmailAddress` and the `ReturnPath` or `FeedbackForwardingEmailAddress`, plus, on a raw message, every mailbox of its `From` header, its `Sender` and its `Return-Path` header. A v2 raw send reads the `From` header only in place of a missing `FromEmailAddress` and accepts any `From` header when one is given (probe-confirmed), so the header is checked only then. Every other address present is checked; whether AWS still checks a `Return-Path` header that the request's return path overrides has not been probed. One `MessageRejected` lists every unverified address ("Email address is not verified. The following identities failed the check in region US-EAST-1: a@example.com, b@example.com") and nothing is captured or relayed. An address covered by a sending-authorization ARN of another account (`SourceArn`, `FromArn`, `ReturnPathArn`, their `X-SES-*-ARN` headers, or v2 `FromEmailAddressIdentityArn` and `FeedbackForwardingEmailAddressIdentityArn`) is not checked, since it is the owner's identity; on a raw send the source ARN also covers the `From` and return-path addresses that have no ARN of their own. A bulk send checks its sender and return path once and rejects the whole call; when no entry has a recipient it checks neither and every entry fails on its own (probe-confirmed on v2 `SendBulkEmail` and v1 `SendBulkTemplatedEmail`). On v2 `SendEmail` and `SendBulkEmail`, and v1 `SendTemplatedEmail` and `SendBulkTemplatedEmail`, an unknown configuration set is reported before the check and an unknown stored template only after it (probe-confirmed). The verification codes and invitations Cognito sends are not affected.
- `SendBulkTemplatedEmail` reads every `Destinations.member.N` up to the highest N. A missing `Template` (with no `TemplateArn`), missing `Destinations`, and an entry without a `Destination` or a skipped N are model violations, answered together as one 400 `ValidationError` ("2 validation errors detected: Value at 'template' failed to satisfy constraint: Member must not be null; Value at 'destinations.2.member.destination' failed to satisfy constraint: Member must not be null") ahead of every other check of the request, the address syntax and the destination count included (probe-confirmed). Floci lists the skipped entries of the first 1000 indexes only; a higher index fails the destination count. On both bulk sends an entry whose address lists are empty answers "Missing required header 'To'." on its own when another entry has a recipient, and when none has one every entry answers "Destination cannot be empty" (probe-confirmed). A `DefaultTags` or `ReplacementTags` tag without a `Name` or a `Value` is a model violation too, a skipped tag index listing both, and the top-level members are listed ahead of the nested ones ('template' before 'defaultTags.1.member.name'). A missing or empty `DefaultTemplateData` is a 400 `InvalidParameterValue` ("The default template data does not exist"), answered after the `Source` syntax and the destination count and before the template lookup and the sender verification (probe-confirmed). On `SendBulkTemplatedEmail` an entry with more than 50 recipients ("Recipient count exceeds 50."), template data that is not a JSON object ("The template data is invalid") or a tag with an empty name ("The tag name must be specified.") fails on its own with `InvalidParameterValue`. Invalid `DefaultTemplateData` or an empty `DefaultTags` name fails every entry, and within one entry an address syntax error wins over an empty tag name, which wins over invalid template data, which wins over the recipient count (probe-confirmed). v2 `SendBulkEmail` still rejects the whole call for more than 50 recipients in a destination (not probed).
- `SendEmail` stores the text body or the HTML body as the captured message body.
- `SetIdentityNotificationTopic` publishes to the configured topic on a Bounce/Complaint/Delivery event (triggered via the mailbox simulator addresses or the suppression list), independent of any configuration set. The payload uses the legacy format (`notificationType`, no `mail.tags`, headers only when `SetIdentityHeadersInNotificationsEnabled` is on).
- Identity (sending authorization) policies are stored and returned as metadata: the policy document, the per-identity limit of 20, and the create/update/delete error shapes match AWS, but Floci does not evaluate policy authorization (Principal-account existence, Resource-ARN match) or gate sending on it. A send naming another account's identity ARN skips the verified-sender check for the addresses it covers, without looking that identity up or reading its policy.
- Receipt rule sets and receipt rules are stored inertly: Floci has no inbound-mail endpoint, so stored rules route no mail and their actions never execute, but the management API round-trips with AWS ordering, validation, and error semantics (probe-verified). Action targets are validated against the local emulator with the AWS error codes: an SNS `TopicArn`, S3 `BucketName`, or Lambda `FunctionArn` must reference an existing local resource, and a `BounceAction` `Sender` must be a verified identity. Deviations: S3 bucket write permissions, KMS keys, and `ConnectAction` role assumability are not verified (AWS checks these); `WorkmailAction` gets no resource validation (neither does AWS). Receipt IP filters are stored the same way, with the probed AWS validation and the 100-filter limit, but no inbound connection is ever filtered. `ReorderReceiptRuleSet` requires an exact permutation of the set's rules; `CloneReceiptRuleSet` copies them under a new creation timestamp, leaving the active flag behind.
- Custom verification email templates are stored and returned; `Create`/`Update` require the `FromEmailAddress` to be a verified identity (or a verified domain) and reject an invalid redirection URL, matching AWS. `SendCustomVerificationEmail` renders the template into the `/_aws/ses` inspection mailbox (and the SMTP relay, when configured) and registers the recipient as a pending-verification identity, matching AWS. The template body has no placeholder that AWS substitutes, so it is passed through verbatim with the same fixed disclaimer AWS always appends; the unique verification link AWS appends is not reproduced because Floci has no verification-click flow. The `SuccessRedirectionURL` / `FailureRedirectionURL` are stored and returned by Get/List but are the post-click redirect targets, so they are not used at send time.
- For the REST JSON API see [SES v2](#v2) below.

## SES v2 (REST JSON) {#v2}

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/v2/email/...`

Alongside the classic Query API, Floci implements a subset of the SES v2 REST JSON API used by `aws sesv2 ...` commands and SDK v2 clients that target the modern SES surface.

### Supported Operations

| Method | Path | Action |
|---|---|---|
| `POST` | `/v2/email/identities` | `CreateEmailIdentity` |
| `GET` | `/v2/email/identities` | `ListEmailIdentities` (SDKs before the `Filter` member; no `Filter`) |
| `POST` | `/v2/email/list-identities` | `ListEmailIdentities` (with `Filter`) |
| `GET` | `/v2/email/identities/{emailIdentity}` | `GetEmailIdentity` |
| `DELETE` | `/v2/email/identities/{emailIdentity}` | `DeleteEmailIdentity` |
| `PUT` | `/v2/email/identities/{emailIdentity}/dkim` | `PutEmailIdentityDkimAttributes` |
| `PUT` | `/v2/email/identities/{emailIdentity}/dkim/signing` | `PutEmailIdentityDkimSigningAttributes` (Easy DKIM / BYODKIM) |
| `PUT` | `/v2/email/identities/{emailIdentity}/feedback` | `PutEmailIdentityFeedbackAttributes` |
| `PUT` | `/v2/email/identities/{emailIdentity}/mail-from` | `PutEmailIdentityMailFromAttributes` |
| `PUT` | `/v2/email/identities/{emailIdentity}/configuration-set` | `PutEmailIdentityConfigurationSetAttributes` |
| `POST` | `/v2/email/identities/{emailIdentity}/policies/{policyName}` | `CreateEmailIdentityPolicy` |
| `GET` | `/v2/email/identities/{emailIdentity}/policies` | `GetEmailIdentityPolicies` |
| `PUT` | `/v2/email/identities/{emailIdentity}/policies/{policyName}` | `UpdateEmailIdentityPolicy` |
| `DELETE` | `/v2/email/identities/{emailIdentity}/policies/{policyName}` | `DeleteEmailIdentityPolicy` |
| `POST` | `/v2/email/outbound-emails` | `SendEmail` (simple / raw / templated) |
| `POST` | `/v2/email/outbound-bulk-emails` | `SendBulkEmail` (templated, multiple destinations) |
| `GET` | `/v2/email/account` | `GetAccount` |
| `PUT` | `/v2/email/account/sending` | `PutAccountSendingAttributes` |
| `PUT` | `/v2/email/account/suppression` | `PutAccountSuppressionAttributes` |
| `PUT` | `/v2/email/account/vdm` | `PutAccountVdmAttributes` |
| `POST` | `/v2/email/account/details` | `PutAccountDetails` |
| `POST` | `/v2/email/tenants` | `CreateTenant` |
| `POST` | `/v2/email/tenants/get` | `GetTenant` |
| `POST` | `/v2/email/tenants/list` | `ListTenants` |
| `POST` | `/v2/email/tenants/delete` | `DeleteTenant` |
| `POST` | `/v2/email/tenants/resources` | `CreateTenantResourceAssociation` |
| `POST` | `/v2/email/tenants/resources/delete` | `DeleteTenantResourceAssociation` |
| `POST` | `/v2/email/tenants/resources/list` | `ListTenantResources` |
| `POST` | `/v2/email/resources/tenants/list` | `ListResourceTenants` |
| `POST` | `/v2/email/tenant/suppression` | `PutTenantSuppressionAttributes` |
| `POST` | `/v2/email/templates` | `CreateEmailTemplate` |
| `GET` | `/v2/email/templates` | `ListEmailTemplates` |
| `GET` | `/v2/email/templates/{templateName}` | `GetEmailTemplate` |
| `PUT` | `/v2/email/templates/{templateName}` | `UpdateEmailTemplate` |
| `DELETE` | `/v2/email/templates/{templateName}` | `DeleteEmailTemplate` |
| `POST` | `/v2/email/templates/{templateName}/render` | `TestRenderEmailTemplate` |
| `POST` | `/v2/email/custom-verification-email-templates` | `CreateCustomVerificationEmailTemplate` |
| `GET` | `/v2/email/custom-verification-email-templates` | `ListCustomVerificationEmailTemplates` |
| `GET` | `/v2/email/custom-verification-email-templates/{templateName}` | `GetCustomVerificationEmailTemplate` |
| `PUT` | `/v2/email/custom-verification-email-templates/{templateName}` | `UpdateCustomVerificationEmailTemplate` |
| `DELETE` | `/v2/email/custom-verification-email-templates/{templateName}` | `DeleteCustomVerificationEmailTemplate` |
| `POST` | `/v2/email/outbound-custom-verification-emails` | `SendCustomVerificationEmail` |
| `POST` | `/v2/email/configuration-sets` | `CreateConfigurationSet` |
| `GET` | `/v2/email/configuration-sets` | `ListConfigurationSets` (SDKs before the `Filter` member; no `Filter`) |
| `POST` | `/v2/email/list-configuration-sets` | `ListConfigurationSets` (with `Filter`) |
| `GET` | `/v2/email/configuration-sets/{name}` | `GetConfigurationSet` |
| `DELETE` | `/v2/email/configuration-sets/{name}` | `DeleteConfigurationSet` |
| `POST` | `/v2/email/configuration-sets/{name}/event-destinations` | `CreateConfigurationSetEventDestination` |
| `GET` | `/v2/email/configuration-sets/{name}/event-destinations` | `GetConfigurationSetEventDestinations` |
| `PUT` | `/v2/email/configuration-sets/{name}/event-destinations/{eventDestinationName}` | `UpdateConfigurationSetEventDestination` |
| `DELETE` | `/v2/email/configuration-sets/{name}/event-destinations/{eventDestinationName}` | `DeleteConfigurationSetEventDestination` |
| `PUT` | `/v2/email/configuration-sets/{name}/suppression-options` | `PutConfigurationSetSuppressionOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/sending` | `PutConfigurationSetSendingOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/reputation-options` | `PutConfigurationSetReputationOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/tracking-options` | `PutConfigurationSetTrackingOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/delivery-options` | `PutConfigurationSetDeliveryOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/archiving-options` | `PutConfigurationSetArchivingOptions` |
| `PUT` | `/v2/email/configuration-sets/{name}/vdm-options` | `PutConfigurationSetVdmOptions` |
| `POST` | `/v2/email/dedicated-ip-pools` | `CreateDedicatedIpPool` |
| `GET` | `/v2/email/dedicated-ip-pools` | `ListDedicatedIpPools` |
| `GET` | `/v2/email/dedicated-ip-pools/{PoolName}` | `GetDedicatedIpPool` |
| `DELETE` | `/v2/email/dedicated-ip-pools/{PoolName}` | `DeleteDedicatedIpPool` |
| `PUT` | `/v2/email/dedicated-ip-pools/{PoolName}/scaling` | `PutDedicatedIpPoolScalingAttributes` |
| `GET` | `/v2/email/dedicated-ips` | `GetDedicatedIps` |
| `GET` | `/v2/email/dedicated-ips/{IP}` | `GetDedicatedIp` |
| `PUT` | `/v2/email/dedicated-ips/{IP}/pool` | `PutDedicatedIpInPool` |
| `PUT` | `/v2/email/dedicated-ips/{IP}/warmup` | `PutDedicatedIpWarmupAttributes` |
| `PUT` | `/v2/email/account/dedicated-ips/warmup` | `PutAccountDedicatedIpWarmupAttributes` |
| `POST` | `/v2/email/contact-lists` | `CreateContactList` |
| `GET` | `/v2/email/contact-lists` | `ListContactLists` |
| `GET` | `/v2/email/contact-lists/{ContactListName}` | `GetContactList` |
| `PUT` | `/v2/email/contact-lists/{ContactListName}` | `UpdateContactList` |
| `DELETE` | `/v2/email/contact-lists/{ContactListName}` | `DeleteContactList` |
| `POST` | `/v2/email/contact-lists/{ContactListName}/contacts` | `CreateContact` |
| `POST` | `/v2/email/contact-lists/{ContactListName}/contacts/list` | `ListContacts` |
| `GET` | `/v2/email/contact-lists/{ContactListName}/contacts/{EmailAddress}` | `GetContact` |
| `PUT` | `/v2/email/contact-lists/{ContactListName}/contacts/{EmailAddress}` | `UpdateContact` |
| `DELETE` | `/v2/email/contact-lists/{ContactListName}/contacts/{EmailAddress}` | `DeleteContact` |
| `PUT` | `/v2/email/suppression/addresses` | `PutSuppressedDestination` |
| `GET` | `/v2/email/suppression/addresses/{EmailAddress}` | `GetSuppressedDestination` |
| `DELETE` | `/v2/email/suppression/addresses/{EmailAddress}` | `DeleteSuppressedDestination` |
| `GET` | `/v2/email/suppression/addresses` | `ListSuppressedDestinations` (optional `Reason` query filter) |
| `POST` | `/v2/email/tags` | `TagResource` |
| `DELETE` | `/v2/email/tags?ResourceArn=...&TagKeys=...` | `UntagResource` |
| `GET` | `/v2/email/tags?ResourceArn=...` | `ListTagsForResource` |
| `POST` | `/v2/email/import-jobs` | `CreateImportJob` |
| `GET` | `/v2/email/import-jobs/{JobId}` | `GetImportJob` |
| `POST` | `/v2/email/import-jobs/list` | `ListImportJobs` |
| `GET` | `/v2/email/insights/{MessageId}` | `GetMessageInsights` |
| `POST` | `/v2/email/metrics/batch` | `BatchGetMetricData` |
| `POST` | `/v2/email/export-jobs` | `CreateExportJob` |
| `GET` | `/v2/email/export-jobs/{JobId}` | `GetExportJob` |
| `POST` | `/v2/email/list-export-jobs` | `ListExportJobs` |
| `PUT` | `/v2/email/export-jobs/{JobId}/cancel` | `CancelExportJob` |
| `POST` | `/v2/email/update-configuration-sets` | `UpdateConfigurationSet` |
| `POST` | `/v2/email/identity/certificates` | `AssociateEmailIdentityCertificate` |
| `POST` | `/v2/email/identity/certificates/delete` | `DisassociateEmailIdentityCertificate` |
| `POST` | `/v2/email/identity/certificates/list` | `ListEmailIdentityCertificates` |
| `POST` | `/v2/email/reputation/entities` | `ListReputationEntities` |
| `GET` | `/v2/email/reputation/entities/{ReputationEntityType}/{ReputationEntityReference}` | `GetReputationEntity` |
| `PUT` | `/v2/email/reputation/entities/{ReputationEntityType}/{ReputationEntityReference}/policy` | `UpdateReputationEntityPolicy` |
| `PUT` | `/v2/email/reputation/entities/{ReputationEntityType}/{ReputationEntityReference}/customer-managed-status` | `UpdateReputationEntityCustomerManagedStatus` |
| `POST` | `/v2/email/email-address-insights/` | `GetEmailAddressInsights` |

Floci models no leased dedicated IPs: `GetDedicatedIps` is empty and IP-targeted operations return `NotFoundException`, as real AWS does for an account with no leased IPs, with required request members validated first (`BadRequestException`). `PutDedicatedIpPoolScalingAttributes` rejects downgrading a `MANAGED` pool to `STANDARD`, and `PutAccountDedicatedIpWarmupAttributes` stores the flag behind `GetAccount.DedicatedIpAutoWarmupEnabled` (default `true`).

Configuration set event destinations are stored as configuration. The target is not validated for existence; missing targets cause Floci to log a warning and skip that destination. Each event destination must specify exactly one destination type and at least one matching event type. A CloudWatch destination requires a non-empty dimension configuration list, and a Pinpoint destination requires an application ARN.

A configuration set's `MessageSecurityOptions`, set with `CreateConfigurationSet` or the partial update [`UpdateConfigurationSet`](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_UpdateConfigurationSet.html) and read back by `GetConfigurationSet`, is stored as configuration only: Floci never adds an S/MIME signature to a message. Where the API reference is silent, Floci follows what real SES answered (probed 2026-10-04): an empty `SigningScheme` resets the set to `DefaultScheme`, a request naming both union members stores `SmimeScheme`, and an `SmimeScheme` without a `SignatureFormat` stores `DETACHED`.

Floci publishes SES events to `SnsDestination`, `KinesisFirehoseDestination`, `EventBridgeDestination`, and `CloudWatchDestination`. `PinpointDestination` logs a warning and skips. The published payload follows the [AWS SES SNS notification format](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-retrieving-sns-contents.html) with an outer `eventType` plus `mail` and event-type-specific blocks. Events fire whenever a configuration set has at least one event destination matching the event type — disable per-destination via `EventDestination.Enabled=false`, or remove the destination entirely.

Floci recognises the AWS [mailbox simulator addresses](https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html#send-email-simulator) for deterministic event-type emission:

| Recipient address | Events emitted (in addition to `Send`) |
|---|---|
| `success@simulator.amazonses.com` | `Delivery` |
| `bounce@simulator.amazonses.com`, `suppressionlist@simulator.amazonses.com` | `Bounce` |
| `complaint@simulator.amazonses.com` | `Delivery`, then `Complaint` |

A `+label` subaddress is supported on any of these, so `bounce+order-123@simulator.amazonses.com` triggers a `Bounce` just like the bare address — the label lets senders distinguish test messages. Only `+` separates the label; `bounce-label@...` is not a simulator address.

A successful send without a simulator-address recipient emits only the `Send` event.

A recipient on the [account-level suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html) produces a `Bounce` with `bounceSubType: OnAccountSuppressionList` or a `Complaint` with `complaintSubType: OnAccountSuppressionList`, following the stored reason, and never a `Delivery`. Events are split by cause: a message that reaches both `bounce@simulator` and a suppressed address publishes two `Bounce` events, each listing only its own recipients, with `mail.destination` carrying the full envelope on both. These shapes were verified against real SES on 2026-09-21.

`Reject` is emitted the way AWS documents it: a message carrying the [EICAR test file](https://www.eicar.org/download-anti-malware-testfile/) in its subject, a header, any text body or MIME part, including a base64 attachment or a forwarded message, is accepted (the send returns a `MessageId`) and then rejected with a `Reject` event whose `reason` is `Bad content`. The message is not relayed, the stored record keeps only its source, its envelope and `RejectReason`, and its `Send` and `Reject` events carry the envelope but no subject or headers in `mail`, where SES would include them, so the scanned content reaches neither the mailbox store nor an event destination. What the request itself supplied, its envelope and its tags, is kept. The test string itself is deliberately not reproduced here.

Account-level VDM (Virtual Deliverability Manager) attributes are stored per region. `PutAccountVdmAttributes` sets `VdmEnabled` (opt-in, defaults `DISABLED`) plus the optional `DashboardAttributes.EngagementMetrics` and `GuardianAttributes.OptimizedSharedDelivery`. `GetAccount` omits `VdmAttributes` until VDM has been configured for the region, then returns `VdmEnabled`, adding the `DashboardAttributes`/`GuardianAttributes` sub-objects only while `VdmEnabled` is `ENABLED`. Floci stores the settings but does not run VDM analytics.

Account provisioning details are stored per region. `PutAccountDetails` sets `MailType` (required, ∈ {`MARKETING`, `TRANSACTIONAL`}) and `WebsiteURL` (required, 1–1000 chars) plus the optional `ContactLanguage` (∈ {`EN`, `JA`}), `UseCaseDescription` (≤5000 chars), `AdditionalContactEmailAddresses` (1–4 entries, each 6–254 chars matching `^(.+)@(.+)$`), and `ProductionAccessEnabled`. Floci stores `ProductionAccessEnabled` as given but stays production-enabled regardless (it has no sandbox), so the flag has no functional effect. Like `VdmAttributes`, `GetAccount` omits `Details` until it has been configured for the region, then echoes the stored fields under `Details` with a `ReviewDetails` block. Matching AWS, all modeled-constraint violations (null / enum / length / list) are aggregated into a single `BadRequestException` reporting every failure; a malformed (but in-range) `WebsiteURL` is reported separately as `Url contains invalid format`. Floci has no sandbox and does not run a production-access review, so `ReviewDetails.Status` is always `GRANTED`.

Tenants (multi-tenancy) are stored per region. `CreateTenant` requires a `TenantName` (1–64 chars, `[A-Za-z0-9_-]`) and accepts optional `Tags`; it returns the tenant with a generated `TenantId` (`tn-` + 30 hex), a `TenantArn` (`arn:aws:ses:<region>:<account>:tenant/<name>/<id>`), a `CreatedTimestamp`, and `SendingStatus` `ENABLED`. The operations use RPC-style POST subpaths (`/tenants/get`, `/tenants/list`, `/tenants/delete`); `ListTenants` returns the `TenantInfo` subset (no `Tags`/`SendingStatus`). A duplicate name is `AlreadyExistsException`; an unknown tenant on get/delete is `NotFoundException`.

Tenant resource associations link a tenant to email identities, configuration sets, and email templates. Matching real AWS, the wire values for `ResourceType` (in responses and as the `Filter` `RESOURCE_TYPE` value) are the ARN segments `identity` / `configuration-set` / `template`; the SDK's `EMAIL_IDENTITY`-style enum constants are rejected, as AWS itself does. The resource must exist in the same account and region; a duplicate association is `AlreadyExistsException`, and deleting a missing one succeeds silently. Deleting a resource that still has associations is rejected, while `DeleteTenant` cascades its associations away.

A `TenantName` on `SendEmail`/`SendBulkEmail` makes the send tenant-scoped. Every resource it uses must be associated with the tenant: the From identity (the exact address identity, else its domain identity), the effective configuration set (an omitted name resolves to the identity's default), and a stored template. Otherwise the send is refused with a 403 `AccessDeniedException` listing the missing ARNs. That 403 is the last check of the send: an unverified sender or return path, a missing configuration set and a missing stored template are reported first (probe-confirmed). The send path has its own tenant-not-found wording ("Tenant \<name\> for AwsAccountId \<account\> not found."), reached only after the request shape validates. A tenant whose sending status is `DISABLED` is refused with a 400 `MessageRejected` before identity verification, the association check and a raw message's missing recipients. As on SES, a malformed address, a Simple or Template send with no recipient, and an unknown configuration set named by a `SendEmail` request are reported first; `SendBulkEmail` refuses the tenant before it looks up the configuration set. `SendBulkEmail` looks the tenant up before an unknown stored template, and skips the tenant checks altogether when no entry names a recipient: every entry then answers `Destination cannot be empty`, as it does without a tenant. Not emulated: tenant-scoped suppression filtering of sends (the account-level semantics apply).

`PutTenantSuppressionAttributes` sets a tenant's `SuppressedReasons`/`SuppressionScope` pair on AWS's own singular `/v2/email/tenant/suppression` route. The pair is all-or-nothing: both members set the block (an empty reason list is valid), half a pair is rejected, and a bare `TenantName` clears it. A `TenantName` on the suppression-list operations (body member on put, query parameter on get/delete/list) routes them to the tenant's own list — separate from the account list, independent of `SuppressionScope`, cascaded away by `DeleteTenant`, and with a non-idempotent delete.

Every tenant is a [reputation entity](https://docs.aws.amazon.com/ses/latest/dg/tenants.html#configuring-reputation-policies) of type `RESOURCE`, referenced by its full ARN (`tenant/<name>/<id>`). As on SES, the `tenant/<id>` form shown in the AWS CLI examples names no entity. Floci keeps no reputation findings, so `ReputationImpact` is always `NONE` (a value the SDK enums do not list, as on SES) and `AwsSesManagedStatus` is always null: the customer-managed status set by `UpdateReputationEntityCustomerManagedStatus` is the `SendingStatusAggregate`, which `GetTenant` reports as the tenant's `SendingStatus` and the `ListTenants` `SENDING_STATUS` filter matches at once, where the SES filter catches up after several seconds. A new tenant has the Standard policy, and nothing pauses a tenant automatically. `UpdateReputationEntityPolicy` accepts the `standard`, `strict` and `none` SES-managed policy ARNs and stores the one given; as on SES, their partition and region are not checked. Every error is a 400 `BadRequestException`, an unknown entity included, matching real SES (probed 2026-10-23). `ListReputationEntities` lists entities by ARN, takes one `Filter` key at a time and binds its token to that filter; without a `PageSize` it serves up to 1000, since no default could be measured.

Suppression list entries are stored per region with `Reason` ∈ {`BOUNCE`, `COMPLAINT`}. At send time, a recipient is suppressed when it appears on the suppression list AND its stored `Reason` is contained in the **effective** `SuppressedReasons` for the send. The effective list is the configuration set's `SuppressionOptions.SuppressedReasons` (set via `PutConfigurationSetSuppressionOptions`) when present — an **empty list is preserved as an explicit "no suppression filtering for this configuration set"** — otherwise it falls back to the account-level `AccountSuppressionAttributes.SuppressedReasons` (set via `PutAccountSuppressionAttributes`, default `[BOUNCE, COMPLAINT]`). Following the AWS V2 contract, there is no dedicated `GetConfigurationSetSuppressionOptions` action; once set, the block is read back through `GetConfigurationSet`'s response (the field is omitted when the configuration set has no override).

Suppressed recipients are filtered out of the SMTP relay step (non-suppressed recipients on the same send still reach the relay normally), and the configuration set's event destinations receive a synthetic `Bounce` or `Complaint` event alongside the always-emitted `Send` event. The `SendEmail` API response (`200` + `MessageId`), the stored `SentEmail` visible at `GET /_aws/ses`, and the published event's `mail.destination` all retain the original recipient list — matching the AWS contract that the message is "accepted, just not sent" for suppressed addresses.

`GetMessageInsights` derives its per-recipient timeline at send time from the same classification as event publishing, adding `DELIVERY` for an ordinary recipient, and spells the suppression subtype `ON_ACCOUNT_SUPPRESSION_LIST` where the published events say `OnAccountSuppressionList` (bounce probed 2026-09-21, complaint by analogy). It is gated on Virtual Deliverability Manager as on AWS: 404 `NotFoundException`, checked before the message id. Deviations from AWS: Floci records the mailbox-simulator and multi-recipient sends that VDM drops (otherwise insights would stay empty here), serves a message immediately, returns only the caller's `EmailTags` without the injected `ses:*` entries, applies no 30-day retention cutoff, and derives a timeline whether or not VDM was enabled at send time. `Isp` is always `UNKNOWN_ISP`, AWS's own value for a provider it cannot identify. A message the content scan rejected reports `SEND` then `REJECT` and carries nothing read off it: no subject, and only the tags the request itself supplied.

`BatchGetMetricData` reads those same stored timelines, so it and `GetMessageInsights` cannot disagree, and it shares the VDM gate. Counts are per recipient, in whole UTC days, and sparse: a day with no data is absent, not zero. `OPEN`, `CLICK` and their delivery variants stay empty because they need a real mailbox, and `TRANSIENT_BOUNCE` because every bounce Floci derives is permanent. Dimensions filter the send: `EMAIL_IDENTITY` on the address or its domain, `CONFIGURATION_SET` on the set it resolved to, `TENANT_NAME` on the tenant it named, and `ISP` only ever on `UNKNOWN_ISP`. Each value is validated with AWS's own rule and message: the configuration set, the tenant and the ISP take ASCII letters, digits, `_` and `-`, and an identity is a bare name or `local@domain`. Where AWS's validation order could not be observed, Floci ranks the member constraints below a duplicate `Id` and the interval cap below the start-before-end check, an absent date fails with a null-constraint message, and a query with two invalid dimension values reports the first in `EMAIL_IDENTITY`, `ISP`, `CONFIGURATION_SET`, `TENANT_NAME` order.

Export jobs render those same timelines as CSV or JSON. Floci writes the file into its own S3 emulation, under a bucket named `floci-<account>-<region>-ses-export-files`, so the presigned URL on a finished job resolves against Floci. The metrics export emits one row per dimension combination Floci has seen, where real SES emits its whole ISP catalogue with zeros, and a `RATE` is the metric's volume over the send volume for that combination. `LastEngagementEvent` filters select nothing, since Floci derives no opens or clicks. An export finishes in milliseconds, so `CancelExportJob` rarely finds a running job to catch.

Import jobs read their source from Floci's own S3 emulation, so the object has to be uploaded there rather than to real S3; the CSV and newline-delimited JSON record formats are AWS's own ([suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html#sending-email-suppression-list-manual-add-bulk), [contact list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-list-management.html#configuring-list-management-bulk-import)). A job still running when the emulator restarts is marked `FAILED` on the next start. Not emulated: `FailureInfo.FailedRecordsS3Url` (Floci writes no failed-record file), the per-file record limits, and the same-region bucket rule.

`SendEmail` honors `ListManagementOptions` (`ContactListName`, optional `TopicName`). When present, each recipient is matched against the named contact list and suppressed as a `Bounce` when opted out — reusing the same relay-exclusion and Bounce-event path as suppression-list filtering, matching AWS ("SES will issue a bounce event for a message that is sent to an unsubscribed contact"). A contact is opted out when `UnsubscribeAll` is set; with a `TopicName`, an explicit `OPT_OUT` preference for that topic (or, absent an explicit preference, the topic's `DefaultSubscriptionStatus` being `OPT_OUT`) suppresses; without a `TopicName`, only `UnsubscribeAll` contacts are suppressed. A recipient that is not yet a contact is created on the list automatically (as on AWS) and then evaluated. Referencing a contact list that does not exist fails the send with `NotFoundException`. The topic-default fallback at send time is an intentional deviation — it is not documented by AWS and mirrors the effective-status model AWS uses for `ListContacts`.

For a **single-recipient** list-managed send, Floci injects a functional unsubscribe link (matching AWS, which only does this for one recipient): the `{{amazonSESUnsubscribeUrl}}` body placeholder is replaced (up to twice) and a `List-Unsubscribe` header plus `List-Unsubscribe-Post: List-Unsubscribe=One-Click` are added (applied to the relayed message and shown under `Headers` at `GET /_aws/ses`; a caller-supplied `List-Unsubscribe` is overridden, matching AWS). The `{{amazonSESUnsubscribeUrl}}` placeholder is also preserved through template rendering so a templated body can carry it. On a send without `ListManagementOptions`, the placeholder is left in the body verbatim (neither replaced nor stripped) — this specific behavior is Floci's choice and is not verified against real SES. Unlike AWS's opaque hosted URL, the link points at Floci's own `/_aws/ses/unsubscribe?region=…&contactList=…&address=…&topic=…` endpoint. `GET` (a browser click) only renders a confirmation page and changes nothing — matching AWS's landing-page behavior and avoiding the RFC 8058 hazard where a client or bot that prefetches the link would silently unsubscribe the contact — while `POST` (the one-click request the confirmation form submits) applies the opt-out (a topic → `OPT_OUT` for that topic; no topic → `UnsubscribeAll`), auto-creating the contact if needed. Two deviations from AWS here: the link is carried as readable query parameters rather than an opaque token (so, like the rest of Floci, the endpoint is unauthenticated and only safe on a trusted dev/test network — a `POST` can opt out any contact), and only the one-click URL entry is emitted — its scheme follows Floci's base URL (`http` unless TLS is enabled) — whereas AWS also includes a `mailto:` entry, which Floci can't service since it has no inbound mail endpoint. Raw (MIME) sends do not get link injection yet.

`SendEmail` and `SendBulkEmail` accept [`ConfigurationOverrides`](https://docs.aws.amazon.com/ses/latest/dg/configuration-overrides.html) and validate its `Tracking` values the way real SES does (probed 2026-10-06). Floci has no open or click tracking, so a valid override has no effect, and `ses:ApplyTrackingConfigurationOverrides` is not enforced. An invalid value is reported on its own even when the request has another model violation, such as a missing `Content`, which SES reports together with it in one message.

Tag operations accept `arn:aws:ses:<region>:<account>:<type>/<name>` ARNs for seven resource types: `configuration-set`, `template`, `identity`, `contact-list`, `custom-verification-email-template`, `dedicated-ip-pool`, and `tenant`. Any other type, or an ARN missing its partition, service, account, or resource name, fails with `BadRequestException` ("ResourceArn is not the expected format"); the partition and service are required but not compared, so any value addresses the same resource. Tags supplied to `CreateConfigurationSet`, `CreateEmailTemplate`, `CreateEmailIdentity`, `CreateContactList`, `CreateCustomVerificationEmailTemplate`, `CreateDedicatedIpPool`, and `CreateTenant` are reachable through `ListTagsForResource`; `UpdateEmailTemplate` and `UpdateCustomVerificationEmailTemplate` do not modify tags. A tenant ARN has two path segments (`tenant/<name>/<tenantId>`) and resolves by the TenantId alone (the name is never matched, so tags are TenantId-scoped across delete/recreate), with the list ordered by key and the not-found wording reproducing AWS's own missing space ("No Tenant present with name: \<name\>with tenantId: \<id\>"). Tenant tags live on the tenant record (shared with `CreateTenant`/`GetTenant`).

ARN handling is otherwise uniform across the types (probe-confirmed against real AWS). A foreign account id fails first with `BadRequestException` ("Operations on a resource created in a different account is not allowed"). An invalid configuration-set name in the ARN fails next with a message-less `ValidationException`. A region differing from the signing region fails `TagResource` / `UntagResource` with `BadRequestException` ("Failed to tag resource" / "Failed to untag resource"), checked after the resource is found in the signing region; `ListTagsForResource` instead checks existence in the signing region (`NotFoundException`, "No \<Shape\> present with name: \<name\>") and keys tags by the literal ARN, so a mismatched-region ARN returns `200` with an empty tag set. An empty `Tags` array on `TagResource` is not an error (the account, region, and existence checks still run, then the empty merge no-ops), while a missing/empty `TagKeys` on `UntagResource` fails with a message-less `ValidationException` (AWS sends only the error-type header with an empty body; Floci's standard error body carries `"message": null`). An omitted `ResourceArn` is a message-less `ValidationException` on `TagResource` / `UntagResource`; on `ListTagsForResource` AWS answers it with a `500 InternalFailure`, which Floci replaces with `BadRequestException` ("ResourceArn is required."). A non-string tag `Key`/`Value` or a non-object `Tags` element is rejected with `SerializationException` rather than coerced, matching AWS's restJson1 deserializer.

The same AWS-compatible validation applies wherever tags are set (including `CreateContactList` and `TagResource`): at most 50 tags, unique keys, keys 1–128 / values 0–256 code points, characters limited to letters, numbers, Unicode whitespace and `_ . : / = + - @`, and the reserved `aws:` key prefix. `TagResource` applies the 50-tag limit to the merged (existing + incoming) set. Violations return `BadRequestException` with AWS's messages.

Boolean request members (such as `UnsubscribeAll`, `SendingEnabled`, `SigningEnabled` or an event destination's `Enabled`) follow AWS's restJson1 deserializer: a JSON string is accepted when it reads, case-insensitively, as `true`, `yes`, `y`, `t` or `1`, or as `false`, `no`, `n`, `f` or `0`, and any other string is a `SerializationException`. An omitted member stores `false` (omitting a whole `SendingOptions` or `ReputationOptions` block on `CreateConfigurationSet` keeps the default `true`), and an explicit `null` is a `SerializationException`, except on `PutAccountDetails`, where a `null` `ProductionAccessEnabled` is stored as `false`.

Identity, identity-policy, template, custom-verification-template, configuration-set, and sent-message state is shared between the v1 Query API and the v2 REST JSON API, so a template created with `CreateTemplate` resolves through `SendEmail` on v2 (and vice versa), a policy written with `PutIdentityPolicy` (v1) is returned by `GetEmailIdentityPolicies` (v2), a custom verification email template created with `CreateCustomVerificationEmailTemplate` (v1) is returned by `GetCustomVerificationEmailTemplate` (v2), a configuration set created with `CreateConfigurationSet` is visible to both `DescribeConfigurationSet` (v1) and `GetConfigurationSet` (v2), delivery options written with either `PutConfigurationSetDeliveryOptions` read back through both (v1 spells the policy `Require`/`Optional`, v2 `REQUIRE`/`OPTIONAL`, matching each API), and every send appears in the same `GET /_aws/ses` inspection mailbox.

The template, identity, configuration-set, custom-verification-template and export-job lists page with `PageSize` (`MaxItems` on v1, `MaxResults` for v1 custom verification templates) and `NextToken`, with the page-size bounds and error messages real SES answers (probed 2026-09-25 to 2026-09-27). Templates and export jobs are listed newest first; identities, configuration sets and custom verification templates by name, since SES's own identity order varies between calls and its custom verification template order could not be observed. A token belongs to the kind of resource listed, as on AWS: a v1 token continues the v2 list of the same resources and the other way round, while any other list, and the same list in another region, refuses it. Without a page size the template lists serve 10, `ListEmailIdentities` 25, and the configuration-set and custom-verification-template lists 50, and a v1 page size outside the bound is served 100 on `ListTemplates` and 50 on `ListConfigurationSets` and `ListCustomVerificationEmailTemplates` rather than refused, all as on AWS. Floci's own choices, where AWS documents no default and none could be measured: v1 `ListIdentities` without `MaxItems` serves up to 1000, and `ListExportJobs` without a `PageSize` serves 100. `ListEmailIdentities` and `ListConfigurationSets` answer on both bindings: the `GET` older SDKs send, and the `POST` that SDKs send since the `Filter` member was added (AWS SDK for Java 2.55.8, botocore 1.43.105). The filter follows real SES (probed 2026-10-03) in what it matches, its messages and their precedence, and is applied before the page is cut; as on SES, a token continues only the filter it was taken with.

The tenant lists (`ListTenants`, `ListTenantResources`, `ListResourceTenants`) and `ListImportJobs` page the same way, within 1 to 100. The tenant lists follow real SES (probed 2026-09-25 and 2026-10-02): tenants are listed by name, a tenant's resources by ARN, and a resource's tenants in association order; the tenant or resource is looked up and the `ListTenantResources` filter checked before the page size and token; a page size outside the bound and an empty `NextToken` are reported together in one Smithy validation message; and a token continues only the request it came from, so another tenant, resource or filter refuses it. `ListTenants` applies its `Filter` before the page is cut, matching real SES (probed 2026-10-03) in what it matches and its messages; the page size and an empty token are checked before the filter's values. Floci's own choices, where nothing could be measured: without a `PageSize` the four lists serve up to 100, and `ListImportJobs`, which refuses sandbox accounts and could not be probed at all, is modelled on the tenant lists with jobs listed oldest first.

The contact lists (`ListContactLists`, `ListContacts`), the dedicated-IP lists (`ListDedicatedIpPools`, `GetDedicatedIps`) and `ListSuppressedDestinations` page the same way, within 1 to 1000. They follow real SES (probed 2026-09-25 and 2026-10-01) in their messages and in the treatment of an empty `NextToken`, refused on the contact and suppressed-destination lists and treated as absent on the dedicated-IP lists. `ListContacts` serves 50 without a `PageSize` and reads the page size and token before it looks the contact list up. A `ListSuppressedDestinations` token continues only the `Reason` filter and tenant it was taken with, and the filter is applied before the page is cut. Floci's own choices: where the SES order is not stable (the pool order changes with the page size, the suppressed-destination order between two identical calls, and contacts follow no recognisable order), Floci lists contacts by address, pools by name and suppressed destinations oldest first; a `ListContacts` page holds exactly `PageSize` contacts, where SES treats the size as a hint and may return a few more or fewer; and without a `PageSize` the contact-list, pool, dedicated-IP and suppressed-destination lists serve up to 1000, since no default could be measured. `ListContacts` applies its `Filter` before the page is cut, so a filtered page is exact too, where SES cuts the page first and may return it short or empty; as on SES, a token is not tied to the filter it was taken with. v1 `ListReceiptRuleSets` has no size member and serves 100 rule sets a page, the figure the AWS model documents; an empty `NextToken` is treated as absent, as on SES. SES lists rule sets by name, while Floci keeps the creation order its unpaged list already had. SES allows 40 rule sets per region, so a second page could not be observed there.

DKIM follows AWS's domain-centric model. A **domain** identity carries DKIM tokens (generated at verification, stable across `VerifyDomainDkim` calls); its `DkimVerificationStatus` tracks **DNS record detection** — it transitions to `Success` when the expected `<token>._domainkey.<domain>` CNAMEs are present in the Route53 emulation, not when DKIM is enabled. An **email** identity has no DKIM of its own: its `DkimAttributes` (`SigningEnabled`, `Status`, `Tokens`) are inherited from its parent domain identity when one is registered. The parent is the exact domain after the `@` (verified against AWS): a verified `example.com` covers `user@example.com` but not `user@mail.example.com` unless `mail.example.com` is itself a registered identity. `SetIdentityDkimEnabled` / `PutEmailIdentityDkimAttributes` only toggle the signing flag (they no longer force the verification status); `PutEmailIdentityDkimSigningAttributes` sets the signing origin (`AWS_SES` Easy DKIM — regenerating tokens when the key length changes — or `EXTERNAL` BYODKIM).

SES v2 `CreateEmailIdentity` and `GetEmailIdentity` report `DkimAttributes.SigningHostedZone` for
Easy DKIM identities. Floci reports the fixed emulator value `dkim.amazonses.com`, and
Route53-backed DKIM detection uses that same constant to construct `<token>.<SigningHostedZone>`.
The zone is not identity state or configurable; Floci does not reproduce AWS's region-
and identity-specific hosted-zone assignment. Email identities inherit their registered domain's
DKIM attributes. BYODKIM identities and email identities without a
registered Easy DKIM domain do not report this SES-hosted zone.

S/MIME certificate associations are stored per region and never used: Floci does not sign mail. Associating validates the request as SES does (a domain identity needs a `FromAddress` in the domain or one of its subdomains, one certificate per address regardless of case) but, like SES, does not inspect the certificate. `ListEmailIdentityCertificates` reports `ACTIVE` while the association meets the [S/MIME certificate considerations](https://docs.aws.amazon.com/ses/latest/dg/send-email-authentication-smime.html#send-email-authentication-smime-certificate-considerations), plus the requirements a probe found SES also enforces (an end-entity certificate whose key usage includes `digitalSignature` and whose extended key usage includes `emailProtection`), checked against Floci's ACM and identity verification when the list is read, with the certificate's expiry as `CertificateExpiryTime`, and `FAILED` otherwise. There is no `PROVISIONING` phase, `DisassociateEmailIdentityCertificate` removes an association at once, and the certificate is not listed as in use by ACM. As on SES, `DeleteEmailIdentity` refuses an identity that still has a certificate associated, whatever its status. The v1 `DeleteIdentity` and `DeleteVerifiedEmailAddress` share the identities and refuse it too, with the same `BadRequestException` the tenant-association guard returns there; SES's v1 behaviour was not probed.

[`GetEmailAddressInsights`](https://docs.aws.amazon.com/ses/latest/dg/email-validation-api.html) is answered offline: Floci performs no DNS lookup and no mailbox probe, and charges nothing. As on AWS (probed 2026-10-25), it answers with Virtual Deliverability Manager disabled, a malformed or over-long address returns 200 with every verdict `LOW` rather than an error, an empty `EmailAddress` is a `BadRequestException` (an absent one, which the AWS CLI refuses to send, is assumed to answer the same), and `IsValid` is `LOW` whenever `HasValidSyntax`, `HasValidDnsRecords` or `MailboxExists` is `LOW`. The verdicts follow fixed rules:

| Evaluation | Floci's verdict |
|---|---|
| `HasValidSyntax` | `LOW` for an address a send would reject; `MEDIUM` when the address carries a display name, a comment or angle brackets, or when the domain has a single label or ends in `.example`, `.invalid`, `.localhost` or `.test`; otherwise `HIGH` |
| `HasValidDnsRecords` | `LOW` for those top-level domains and for `example.com`, `example.net`, `example.org` and their subdomains; otherwise `HIGH`, including `simulator.amazonses.com` |
| `MailboxExists` | `LOW` for `suppressionlist@simulator.amazonses.com` (with or without a `+label`); otherwise `MEDIUM`. The other simulator addresses are not special, as on AWS |
| `IsRoleAddress` | `HIGH` when the local part, without a `+label`, is a common role name such as `admin`, `info`, `noreply`, `postmaster` or `support`; otherwise `LOW` |
| `IsDisposable`, `IsRandomInput` | Always `LOW` |

Deviations from AWS: `IsValid` is `HIGH` whenever it is not `LOW` and never `MEDIUM`, although AWS documents a `MEDIUM` overall verdict too (no probed address returned one), every domain that is not reserved is taken to have valid DNS records, Floci keeps no disposable-domain list and detects no random input, and an address with a non-ASCII character in its domain (an internationalized domain name included) or in an unquoted local part is rated `LOW` everywhere, as a send would reject it, while a quoted local part, which a send does not inspect, is rated like any other; AWS's verdicts for such addresses were not probed.

### Request member types

The operations whose body nests a structure in a structure, or holds a list of structures, check the request body against the SES v2 service model before anything else is validated, as AWS's restJson1 deserializer does: the send operations (`SendEmail`, `SendBulkEmail`, `SendCustomVerificationEmail`), `CreateConfigurationSet`, `UpdateConfigurationSet`, `PutConfigurationSetSuppressionOptions`, `PutConfigurationSetVdmOptions`, `CreateConfigurationSetEventDestination`, `UpdateConfigurationSetEventDestination`, `CreateContactList`, `UpdateContactList`, `CreateContact`, `UpdateContact`, `ListContacts`, `PutAccountSuppressionAttributes`, `PutAccountVdmAttributes`, `BatchGetMetricData`, `CreateImportJob` and `CreateExportJob`. `ListReputationEntities`, `UpdateReputationEntityPolicy`, `UpdateReputationEntityCustomerManagedStatus` and `GetEmailAddressInsights` run the same check although their bodies are flat. In those operations, the first body member of the wrong JSON type, in body order, is a `SerializationException` with AWS's deserializer message:

| Member type | Value sent | Message |
|---|---|---|
| string, blob | number or boolean | `NUMBER_VALUE can not be converted to a String` (`TRUE_VALUE` / `FALSE_VALUE`; `a Blob` for a blob) |
| string, blob | object / array | `Start of structure or map found where not expected.` / `Start of list found where not expected` |
| structure | array / any other non-object | `Start of list found where not expected` / `Expected null` |
| map | array / any other non-object | `Start of list found where not expected` / `Expected map or null` |
| list | object / any other non-array | `Start of structure or map found where not expected.` / `Expected list or null` |

A JSON `null` member counts as absent. A `null` address list element passes this check and fails later, as on AWS: `SendEmail` answers `Invalid email address null.` after its content and sender checks and before the tenant lookup, and `SendBulkEmail` reports it as the `INVALID_PARAMETER` status of the entry it belongs to (of every entry for a `null` Reply-To), or as `Destination cannot be empty` when no entry of the request has a non-null recipient, the answer every entry gets then, whatever its lists hold. A `null` element of `Topics` (`CreateContactList`, `UpdateContactList`) or `TopicPreferences` (`CreateContact`, `UpdateContact`) also passes this check, but there AWS answers it with a `500 InternalFailure`, which Floci replaces with `BadRequestException` naming the element ("1 validation error detected: Value at 'topics.1.member.topicName' failed to satisfy constraint: Member must not be null"). The account-level sending switch is still checked first, so a paused account answers `SendingPausedException` even for a malformed body. Likewise `BatchGetMetricData` checks that Virtual Deliverability Manager is enabled before it reads the body.

The other operations check their members one at a time, and some of them still answer a wrong JSON type with `BadRequestException`. The list operations' map `Filter` answers as in the table. Their integer `PageSize`, like `CreateExportJob`'s `MaxResults`, answers a string with `STRING_VALUE can not be converted to an Integer` (`TRUE_VALUE` / `FALSE_VALUE` for a boolean, and as in the table for an object or an array), while a number is read as an int first, as on AWS: `1.5` is accepted, and a value above the int range is checked against the page-size bounds as `2147483647`.

An empty body is read as an object without members. A body that is not a JSON object is a `SerializationException`, before any member is read: `Start of list found where not expected` for an array, and no message for a string, a number, `null`, a whitespace-only body, malformed JSON or anything but whitespace after the object (`{} x`, `{}{}`). `CreateContactList`, `CreateContact`, `CreateDedicatedIpPool`, `CreateEmailIdentityPolicy`, `UpdateEmailIdentityPolicy`, `PutEmailIdentityMailFromAttributes`, `CreateImportJob`, `PutSuppressedDestination`, `TestRenderEmailTemplate` and `TagResource` answer an empty body with `BadRequestException` `Request body is required.` instead of reporting the missing members.

The body shapes are vendored in `src/main/resources/aws/sesv2-request-shapes.json`, generated by `tools/aws/regen_sesv2_shapes.py` from the botocore release pinned in `tools/aws/requirements.txt`.

### IAM enforcement

With `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, every SES v2 operation is authorized as the `ses:`
action AWS's service reference lists for it, which is always `ses:<Operation>`, in the `ses` namespace
v1 and v2 share. The v1 Query API resolves the same way from its `Action`. Both are evaluated with
resource `*`, so a policy that limits `ses:` actions to an identity, configuration set or template ARN
does not grant them yet. The other actions the service reference lists for some operations are not
checked: `ses:TagResource` for the create operations, and `iam:PassRole` (with `iam:PassedToService`
`ses.amazonaws.com`) for creating or updating an event destination. A v2 `SendEmail` is authorized as
`ses:SendEmail` whatever its content: `ses:SendRawEmail` is a v1 action.

SES publishes to an SNS topic as `ses.amazonaws.com`, so the topic's policy decides whether it may,
whatever the sender's own SNS permissions: an event destination's events with the configuration set's
ARN as `aws:SourceArn`, and an identity's bounce, complaint and delivery notifications with the ARN of
the identity that names the topic, the sender's own or its domain's. Both carry the sending account as
`aws:SourceAccount` and `aws:SourceOwner`. The default policy of a topic in the sending account admits
SES; a topic whose policy was replaced, or a topic in another account, needs a statement for SES like
the one in the [SES Developer Guide](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-add-event-destination-sns.html).
Floci checks the topic when SES publishes, not when an event destination is created or a notification
topic is set. A refused event is not delivered. When a notification cannot be published because the
topic is gone or its policy refuses SES, Floci does what SES does: it takes the topic out of the
identity's bounce and complaint settings that name it and turns email feedback forwarding back on,
while a delivery setting stays. A topic that is gone counts with enforcement off too. SES also emails the account about
the change, which Floci does not. Floci delivers only to topics in the sending account, so a
notification another account's topic allows is not delivered either.

`CreateReceiptRule` and `UpdateReceiptRule` check the same way: an action whose `TopicArn` names a
topic whose policy does not let SES publish for that rule is refused with `InvalidSnsTopic`, as a
topic that does not exist is, the rule's ARN being the `aws:SourceArn`. The rules still route no
mail.
