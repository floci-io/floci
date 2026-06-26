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
| `GetIdentityDkimAttributes`         | Return DKIM status for identities                         |
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
| `PUT` | `/v2/email/identities/{emailIdentity}/feedback` | `PutEmailIdentityFeedbackAttributes` |
| `PUT` | `/v2/email/identities/{emailIdentity}/mail-from` | `PutEmailIdentityMailFromAttributes` |
| `POST` | `/v2/email/outbound-emails` | `SendEmail` (simple / raw / templated) |
| `POST` | `/v2/email/outbound-bulk-emails` | `SendBulkEmail` (templated, multiple destinations) |
| `GET` | `/v2/email/account` | `GetAccount` |
| `PUT` | `/v2/email/account/sending` | `PutAccountSendingAttributes` |
| `PUT` | `/v2/email/account/suppression` | `PutAccountSuppressionAttributes` |
| `POST` | `/v2/email/templates` | `CreateEmailTemplate` |
| `GET` | `/v2/email/templates` | `ListEmailTemplates` |
| `GET` | `/v2/email/templates/{templateName}` | `GetEmailTemplate` |
| `PUT` | `/v2/email/templates/{templateName}` | `UpdateEmailTemplate` |
| `DELETE` | `/v2/email/templates/{templateName}` | `DeleteEmailTemplate` |
| `POST` | `/v2/email/templates/{templateName}/render` | `TestRenderEmailTemplate` |
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

A successful send without a simulator-address recipient emits only the `Send` event.

Suppression list entries are stored per region with `Reason` ∈ {`BOUNCE`, `COMPLAINT`}. At send time, a recipient is suppressed when it appears on the suppression list AND its stored `Reason` is contained in the **effective** `SuppressedReasons` for the send. The effective list is the configuration set's `SuppressionOptions.SuppressedReasons` (set via `PutConfigurationSetSuppressionOptions`) when present — an **empty list is preserved as an explicit "no suppression filtering for this configuration set"** — otherwise it falls back to the account-level `AccountSuppressionAttributes.SuppressedReasons` (set via `PutAccountSuppressionAttributes`, default `[BOUNCE, COMPLAINT]`). Following the AWS V2 contract, there is no dedicated `GetConfigurationSetSuppressionOptions` action; once set, the block is read back through `GetConfigurationSet`'s response (the field is omitted when the configuration set has no override).

Suppressed recipients are filtered out of the SMTP relay step (non-suppressed recipients on the same send still reach the relay normally), and the configuration set's event destinations receive a synthetic `Bounce` or `Complaint` event alongside the always-emitted `Send` event. The `SendEmail` API response (`200` + `MessageId`), the stored `SentEmail` visible at `GET /_aws/ses`, and the published event's `mail.destination` all retain the original recipient list — matching the AWS contract that the message is "accepted, just not sent" for suppressed addresses.

Tag operations support these ARN forms: `arn:aws:ses:<region>:<account>:configuration-set/<name>`, `arn:aws:ses:<region>:<account>:template/<name>`, and `arn:aws:ses:<region>:<account>:identity/<email-or-domain>`. Tags supplied to `CreateConfigurationSet`, `CreateEmailTemplate`, and `CreateEmailIdentity` are reachable through `ListTagsForResource`; `UpdateEmailTemplate` does not modify tags. Other resource types return `NotFoundException`.

Identity, template, configuration-set, and sent-message state is shared between the v1 Query API and the v2 REST JSON API, so a template created with `CreateTemplate` resolves through `SendEmail` on v2 (and vice versa), a configuration set created with `CreateConfigurationSet` is visible to both `DescribeConfigurationSet` (v1) and `GetConfigurationSet` (v2), and every send appears in the same `GET /_aws/ses` inspection mailbox.
