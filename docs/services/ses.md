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
| `SetIdentityNotificationTopic`      | Store SNS notification topic ARNs for an identity         |
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
- `SetIdentityNotificationTopic` stores SNS topic ARNs and returns them via `GetIdentityNotificationAttributes`.
- Notification topics are configuration metadata only; SES delivery, bounce, or complaint events are not emitted automatically.
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
| `PUT` | `/v2/email/suppression/addresses` | `PutSuppressedDestination` |
| `GET` | `/v2/email/suppression/addresses/{EmailAddress}` | `GetSuppressedDestination` |
| `DELETE` | `/v2/email/suppression/addresses/{EmailAddress}` | `DeleteSuppressedDestination` |
| `GET` | `/v2/email/suppression/addresses` | `ListSuppressedDestinations` (optional `Reason` query filter) |
| `POST` | `/v2/email/tags` | `TagResource` |
| `DELETE` | `/v2/email/tags?ResourceArn=...&TagKeys=...` | `UntagResource` |
| `GET` | `/v2/email/tags?ResourceArn=...` | `ListTagsForResource` |

Suppression list entries are stored per region. `Reason` is `BOUNCE` or `COMPLAINT`. `SendEmail` is not yet integrated with the suppression list, so suppressed addresses are still delivered locally.

Tag operations support these ARN forms: `arn:aws:ses:<region>:<account>:configuration-set/<name>`, `arn:aws:ses:<region>:<account>:template/<name>`, and `arn:aws:ses:<region>:<account>:identity/<email-or-domain>`. Tags supplied to `CreateConfigurationSet`, `CreateEmailTemplate`, and `CreateEmailIdentity` are reachable through `ListTagsForResource`; `UpdateEmailTemplate` does not modify tags. Other resource types return `NotFoundException`.

Identity, template, configuration-set, and sent-message state is shared between the v1 Query API and the v2 REST JSON API, so a template created with `CreateTemplate` resolves through `SendEmail` on v2 (and vice versa), a configuration set created with `CreateConfigurationSet` is visible to both `DescribeConfigurationSet` (v1) and `GetConfigurationSet` (v2), and every send appears in the same `GET /_aws/ses` inspection mailbox.
