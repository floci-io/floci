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
| `DescribeReceiptRuleSet`            | Read a receipt rule set (Rules always empty)              |
| `ListReceiptRuleSets`               | List receipt rule sets                                    |
| `DeleteReceiptRuleSet`              | Delete a receipt rule set (idempotent)                    |
| `SetActiveReceiptRuleSet`           | Mark a rule set active, or clear the active one           |
| `DescribeActiveReceiptRuleSet`      | Read the active receipt rule set                          |

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

When `smtp-host` is configured, `SendEmail` and `SendRawEmail` forward
emails to the specified SMTP server in addition to storing them in the
local inspection endpoint. This enables integration testing with tools
like [Mailpit](https://mailpit.axllent.org/) or any standard SMTP server.

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

- Emails are always stored locally regardless of relay — the
  `/_aws/ses` inspection endpoint works with or without SMTP.
- Relay failures are logged but do not affect the API response.
- Raw MIME messages are parsed with Apache Mime4j to extract common
  fields (From, To, Cc, Subject, text/plain and text/html parts) and
  relayed as a reconstructed message. Arbitrary headers, attachments,
  and complex multipart structures are not preserved in the relay.

## Local Inspection Endpoint

For test assertions and debugging, Floci exposes a LocalStack-compatible mailbox endpoint:

- `GET /_aws/ses` lists captured messages
- `GET /_aws/ses?id=<message-id>` returns a specific captured message
- `DELETE /_aws/ses` clears the captured mailbox

Messages are stored locally by Floci and can be persisted when SES storage is backed by persistent or hybrid storage.

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

- Identity verification succeeds immediately; no real DNS or inbox verification flow is required.
- `SendEmail` stores the text body or the HTML body as the captured message body.
- `SetIdentityNotificationTopic` publishes to the configured topic on a Bounce/Complaint/Delivery event (triggered via the mailbox simulator addresses or the suppression list), independent of any configuration set. The payload uses the legacy format (`notificationType`, no `mail.tags`, headers only when `SetIdentityHeadersInNotificationsEnabled` is on).
- Identity (sending authorization) policies are stored and returned as metadata: the policy document, the per-identity limit of 20, and the create/update/delete error shapes match AWS, but Floci does not evaluate policy authorization (Principal-account existence, Resource-ARN match) or gate sending on it.
- Receipt rule sets are stored inertly: Floci has no inbound-mail endpoint, so a rule set never holds any receipt rules and routes no mail. `CreateReceiptRuleSet` / `DescribeReceiptRuleSet` (Rules always empty) / `ListReceiptRuleSets` / `DeleteReceiptRuleSet` (idempotent) and `SetActiveReceiptRuleSet` / `DescribeActiveReceiptRuleSet` round-trip so tools like Terraform (`aws_ses_receipt_rule_set`, `aws_ses_active_receipt_rule_set`) can declare a rule set during bootstrap. Individual receipt rules and receipt filters are not implemented.
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
| `GET` | `/v2/email/identities` | `ListEmailIdentities` |
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
| `GET` | `/v2/email/configuration-sets` | `ListConfigurationSets` |
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

Configuration set event destinations are stored as configuration. The target is not validated for existence; missing targets cause Floci to log a warning and skip that destination. Each event destination must specify exactly one destination type and at least one matching event type. A CloudWatch destination requires a non-empty dimension configuration list, and a Pinpoint destination requires an application ARN.

Floci publishes SES events to `SnsDestination`, `KinesisFirehoseDestination`, `EventBridgeDestination`, and `CloudWatchDestination`. `PinpointDestination` logs a warning and skips. The published payload follows the [AWS SES SNS notification format](https://docs.aws.amazon.com/ses/latest/dg/event-publishing-retrieving-sns-contents.html) with an outer `eventType` plus `mail` and event-type-specific blocks. Events fire whenever a configuration set has at least one event destination matching the event type — disable per-destination via `EventDestination.Enabled=false`, or remove the destination entirely.

Floci recognises the AWS [mailbox simulator addresses](https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html#send-email-simulator) for deterministic event-type emission:

| Recipient address | Events emitted (in addition to `Send`) |
|---|---|
| `success@simulator.amazonses.com` | `Delivery` |
| `bounce@simulator.amazonses.com` | `Bounce` |
| `complaint@simulator.amazonses.com` | `Complaint` |
| `suppressionlist@simulator.amazonses.com` | `Reject` |

A `+label` subaddress is supported on any of these, so `bounce+order-123@simulator.amazonses.com` triggers a `Bounce` just like the bare address — the label lets senders distinguish test messages. Only `+` separates the label; `bounce-label@...` is not a simulator address.

A successful send without a simulator-address recipient emits only the `Send` event.

Account-level VDM (Virtual Deliverability Manager) attributes are stored per region. `PutAccountVdmAttributes` sets `VdmEnabled` (opt-in, defaults `DISABLED`) plus the optional `DashboardAttributes.EngagementMetrics` and `GuardianAttributes.OptimizedSharedDelivery`. `GetAccount` omits `VdmAttributes` until VDM has been configured for the region, then returns `VdmEnabled`, adding the `DashboardAttributes`/`GuardianAttributes` sub-objects only while `VdmEnabled` is `ENABLED`. Floci stores the settings but does not run VDM analytics.

Account provisioning details are stored per region. `PutAccountDetails` sets `MailType` (required, ∈ {`MARKETING`, `TRANSACTIONAL`}) and `WebsiteURL` (required, 1–1000 chars) plus the optional `ContactLanguage` (∈ {`EN`, `JA`}), `UseCaseDescription` (≤5000 chars), `AdditionalContactEmailAddresses` (1–4 entries, each 6–254 chars matching `^(.+)@(.+)$`), and `ProductionAccessEnabled`. Floci stores `ProductionAccessEnabled` as given but stays production-enabled regardless (it has no sandbox), so the flag has no functional effect. Like `VdmAttributes`, `GetAccount` omits `Details` until it has been configured for the region, then echoes the stored fields under `Details` with a `ReviewDetails` block. Matching AWS, all modeled-constraint violations (null / enum / length / list) are aggregated into a single `BadRequestException` reporting every failure; a malformed (but in-range) `WebsiteURL` is reported separately as `Url contains invalid format`. Floci has no sandbox and does not run a production-access review, so `ReviewDetails.Status` is always `GRANTED`.

Tenants (multi-tenancy) are stored per region. `CreateTenant` requires a `TenantName` (1–64 chars, `[A-Za-z0-9_-]`) and accepts optional `Tags`; it returns the tenant with a generated `TenantId` (`tn-` + 30 hex), a `TenantArn` (`arn:aws:ses:<region>:<account>:tenant/<name>/<id>`), a `CreatedTimestamp`, and `SendingStatus` `ENABLED`. The operations use RPC-style POST subpaths (`/tenants/get`, `/tenants/list`, `/tenants/delete`); `ListTenants` returns the `TenantInfo` subset (no `Tags`/`SendingStatus`) and currently returns every tenant in a single page — `PageSize`/`NextToken` pagination is not yet implemented. A duplicate name is `AlreadyExistsException`; an unknown tenant on get/delete is `NotFoundException`.

Tenant resource associations link a tenant to email identities, configuration sets, and email templates. Matching real AWS, the wire values for `ResourceType` — in responses and as the `Filter` `RESOURCE_TYPE` value — are the ARN segments `identity` / `configuration-set` / `template`; the SDK's `EMAIL_IDENTITY`-style enum constants are rejected, as AWS itself does. The resource must exist in the same account and region; a duplicate association is `AlreadyExistsException`, and deleting a missing one succeeds silently. Deleting a resource that still has associations is rejected, while `DeleteTenant` cascades its associations away. The list operations return a single page (no `PageSize`/`NextToken` pagination yet, like `ListTenants`).

A `TenantName` on `SendEmail`/`SendBulkEmail` makes the send tenant-scoped: the resources it uses — the From identity (the exact address identity, else its domain identity), the effective configuration set (an omitted name resolves to the identity's default), and a stored template — must all be associated with the tenant, or the send is refused with a 403 `AccessDeniedException` listing the missing ARNs. The send path has its own tenant-not-found wording ("Tenant \<name\> for AwsAccountId \<account\> not found."), reached only after the request shape validates. Not emulated: the `SendingStatus` gate (nothing can move the status off `ENABLED`) and tenant-scoped suppression filtering of sends (the account-level semantics apply).

`PutTenantSuppressionAttributes` sets a tenant's `SuppressedReasons`/`SuppressionScope` pair on AWS's own singular `/v2/email/tenant/suppression` route. The pair is all-or-nothing: both members set the block (an empty reason list is valid), half a pair is rejected, and a bare `TenantName` clears it. A `TenantName` on the suppression-list operations (body member on put, query parameter on get/delete/list) routes them to the tenant's own list — separate from the account list, independent of `SuppressionScope`, cascaded away by `DeleteTenant`, and with a non-idempotent delete.

Suppression list entries are stored per region with `Reason` ∈ {`BOUNCE`, `COMPLAINT`}. At send time, a recipient is suppressed when it appears on the suppression list AND its stored `Reason` is contained in the **effective** `SuppressedReasons` for the send. The effective list is the configuration set's `SuppressionOptions.SuppressedReasons` (set via `PutConfigurationSetSuppressionOptions`) when present — an **empty list is preserved as an explicit "no suppression filtering for this configuration set"** — otherwise it falls back to the account-level `AccountSuppressionAttributes.SuppressedReasons` (set via `PutAccountSuppressionAttributes`, default `[BOUNCE, COMPLAINT]`). Following the AWS V2 contract, there is no dedicated `GetConfigurationSetSuppressionOptions` action; once set, the block is read back through `GetConfigurationSet`'s response (the field is omitted when the configuration set has no override).

Suppressed recipients are filtered out of the SMTP relay step (non-suppressed recipients on the same send still reach the relay normally), and the configuration set's event destinations receive a synthetic `Bounce` or `Complaint` event alongside the always-emitted `Send` event. The `SendEmail` API response (`200` + `MessageId`), the stored `SentEmail` visible at `GET /_aws/ses`, and the published event's `mail.destination` all retain the original recipient list — matching the AWS contract that the message is "accepted, just not sent" for suppressed addresses.

`SendEmail` honors `ListManagementOptions` (`ContactListName`, optional `TopicName`). When present, each recipient is matched against the named contact list and suppressed as a `Bounce` when opted out — reusing the same relay-exclusion and Bounce-event path as suppression-list filtering, matching AWS ("SES will issue a bounce event for a message that is sent to an unsubscribed contact"). A contact is opted out when `UnsubscribeAll` is set; with a `TopicName`, an explicit `OPT_OUT` preference for that topic (or, absent an explicit preference, the topic's `DefaultSubscriptionStatus` being `OPT_OUT`) suppresses; without a `TopicName`, only `UnsubscribeAll` contacts are suppressed. A recipient that is not yet a contact is created on the list automatically (as on AWS) and then evaluated. Referencing a contact list that does not exist fails the send with `NotFoundException`. The topic-default fallback at send time is an intentional deviation — it is not documented by AWS and mirrors the effective-status model AWS uses for `ListContacts`.

For a **single-recipient** list-managed send, Floci injects a functional unsubscribe link (matching AWS, which only does this for one recipient): the `{{amazonSESUnsubscribeUrl}}` body placeholder is replaced (up to twice) and a `List-Unsubscribe` header plus `List-Unsubscribe-Post: List-Unsubscribe=One-Click` are added (applied to the relayed message and shown under `Headers` at `GET /_aws/ses`; a caller-supplied `List-Unsubscribe` is overridden, matching AWS). The `{{amazonSESUnsubscribeUrl}}` placeholder is also preserved through template rendering so a templated body can carry it. On a send without `ListManagementOptions`, the placeholder is left in the body verbatim (neither replaced nor stripped) — this specific behavior is Floci's choice and is not verified against real SES. Unlike AWS's opaque hosted URL, the link points at Floci's own `/_aws/ses/unsubscribe?region=…&contactList=…&address=…&topic=…` endpoint. `GET` (a browser click) only renders a confirmation page and changes nothing — matching AWS's landing-page behavior and avoiding the RFC 8058 hazard where a client or bot that prefetches the link would silently unsubscribe the contact — while `POST` (the one-click request the confirmation form submits) applies the opt-out (a topic → `OPT_OUT` for that topic; no topic → `UnsubscribeAll`), auto-creating the contact if needed. Two deviations from AWS here: the link is carried as readable query parameters rather than an opaque token (so, like the rest of Floci, the endpoint is unauthenticated and only safe on a trusted dev/test network — a `POST` can opt out any contact), and only the one-click URL entry is emitted — its scheme follows Floci's base URL (`http` unless TLS is enabled) — whereas AWS also includes a `mailto:` entry, which Floci can't service since it has no inbound mail endpoint. Raw (MIME) sends do not get link injection yet.

Tag operations accept `arn:aws:ses:<region>:<account>:<type>/<name>` ARNs for seven resource types: `configuration-set`, `template`, `identity`, `contact-list`, `custom-verification-email-template`, `dedicated-ip-pool`, and `tenant`; other types return `NotFoundException`. Tags supplied to `CreateConfigurationSet`, `CreateEmailTemplate`, `CreateEmailIdentity`, `CreateContactList`, `CreateCustomVerificationEmailTemplate`, `CreateDedicatedIpPool`, and `CreateTenant` are reachable through `ListTagsForResource`; `UpdateEmailTemplate` and `UpdateCustomVerificationEmailTemplate` do not modify tags. A tenant ARN has two path segments (`tenant/<name>/<tenantId>`) and resolves by the TenantId alone (the name is never matched, so tags are TenantId-scoped across delete/recreate), with the list ordered by key and the not-found wording reproducing AWS's own missing space ("No Tenant present with name: \<name\>with tenantId: \<id\>"). Tenant tags live on the tenant record (shared with `CreateTenant`/`GetTenant`).

ARN handling is otherwise uniform across the types (probe-confirmed against real AWS). A foreign account id fails first with `BadRequestException` ("Operations on a resource created in a different account is not allowed"). A region differing from the signing region fails `TagResource` / `UntagResource` with `BadRequestException` ("Failed to tag resource" / "Failed to untag resource"); `ListTagsForResource` instead checks existence in the signing region (`NotFoundException`, "No \<Shape\> present with name: \<name\>") and keys tags by the literal ARN, so a mismatched-region ARN returns `200` with an empty tag set. An empty `Tags` array on `TagResource` is not an error (the account, region, and existence checks still run, then the empty merge no-ops), while a missing/empty `TagKeys` on `UntagResource` fails with a message-less `ValidationException` (AWS sends only the error-type header with an empty body; Floci's standard error body carries `"message": null`). A non-string tag `Key`/`Value` or a non-object `Tags` element is rejected with `SerializationException` rather than coerced, matching AWS's restJson1 deserializer.

The same AWS-compatible validation applies wherever tags are set (including `CreateContactList` and `TagResource`): at most 50 tags, unique keys, keys 1–128 / values 0–256 code points, characters limited to letters, numbers, Unicode whitespace and `_ . : / = + - @`, and the reserved `aws:` key prefix. `TagResource` applies the 50-tag limit to the merged (existing + incoming) set. Violations return `BadRequestException` with AWS's messages.

Identity, identity-policy, template, custom-verification-template, configuration-set, and sent-message state is shared between the v1 Query API and the v2 REST JSON API, so a template created with `CreateTemplate` resolves through `SendEmail` on v2 (and vice versa), a policy written with `PutIdentityPolicy` (v1) is returned by `GetEmailIdentityPolicies` (v2), a custom verification email template created with `CreateCustomVerificationEmailTemplate` (v1) is returned by `GetCustomVerificationEmailTemplate` (v2), a configuration set created with `CreateConfigurationSet` is visible to both `DescribeConfigurationSet` (v1) and `GetConfigurationSet` (v2), delivery options written with either `PutConfigurationSetDeliveryOptions` read back through both (v1 spells the policy `Require`/`Optional`, v2 `REQUIRE`/`OPTIONAL`, matching each API), and every send appears in the same `GET /_aws/ses` inspection mailbox.

DKIM follows AWS's domain-centric model. A **domain** identity carries DKIM tokens (generated at verification, stable across `VerifyDomainDkim` calls); its `DkimVerificationStatus` tracks **DNS record detection** — it transitions to `Success` when the expected `<token>._domainkey.<domain>` CNAMEs are present in the Route53 emulation, not when DKIM is enabled. An **email** identity has no DKIM of its own: its `DkimAttributes` (`SigningEnabled`, `Status`, `Tokens`) are inherited from its parent domain identity when one is registered. The parent is the exact domain after the `@` (verified against AWS): a verified `example.com` covers `user@example.com` but not `user@mail.example.com` unless `mail.example.com` is itself a registered identity. `SetIdentityDkimEnabled` / `PutEmailIdentityDkimAttributes` only toggle the signing flag (they no longer force the verification status); `PutEmailIdentityDkimSigningAttributes` sets the signing origin (`AWS_SES` Easy DKIM — regenerating tokens when the key length changes — or `EXTERNAL` BYODKIM).
