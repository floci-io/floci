# SES

**Protocol:** Query (XML) with `Action=` parameter
**Endpoint:** `POST http://localhost:4566/`

Floci exposes the classic Amazon SES Query API used by `aws ses ...` commands and SDKs targeting SES v1.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `VerifyEmailIdentity` |
| `VerifyEmailAddress` |
| `VerifyDomainIdentity` |
| `DeleteIdentity` |
| `ListIdentities` |
| `GetIdentityVerificationAttributes` |
| `SendEmail` |
| `SendRawEmail` |
| `GetSendQuota` |
| `GetSendStatistics` |
| `GetAccountSendingEnabled` |
| `ListVerifiedEmailAddresses` |
| `DeleteVerifiedEmailAddress` |
| `SetIdentityNotificationTopic` |
| `GetIdentityNotificationAttributes` |
| `SetIdentityFeedbackForwardingEnabled` |
| `SetIdentityHeadersInNotificationsEnabled` |
| `SetIdentityMailFromDomain` |
| `GetIdentityMailFromDomainAttributes` |
| `GetIdentityDkimAttributes` |
| `CreateTemplate` |
| `UpdateTemplate` |
| `GetTemplate` |
| `DeleteTemplate` |
| `ListTemplates` |
| `SendTemplatedEmail` |
| `SendBulkTemplatedEmail` |
| `TestRenderTemplate` |
| `CreateConfigurationSet` |
| `DescribeConfigurationSet` |
| `ListConfigurationSets` |
| `DeleteConfigurationSet` |
| `CreateEmailIdentity` |
| `ListEmailIdentities` |
| `GetEmailIdentity` |
| `DeleteEmailIdentity` |
| `PutEmailIdentityDkimAttributes` |
| `PutEmailIdentityMailFromAttributes` |
| `PutEmailIdentityFeedbackAttributes` |
| `SendBulkEmail` |
| `CreateEmailTemplate` |
| `ListEmailTemplates` |
| `GetEmailTemplate` |
| `UpdateEmailTemplate` |
| `DeleteEmailTemplate` |
| `TestRenderEmailTemplate` |
| `GetConfigurationSet` |
| `GetAccount` |
| `PutAccountSendingAttributes` |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    ses:
      enabled: true
      # smtp-host: mailpit        # SMTP server for email relay (empty = store only)
      # smtp-port: 1025
      # smtp-user: ""
      # smtp-pass: ""
      # smtp-starttls: DISABLED   # DISABLED, OPTIONAL, or REQUIRED
```

### Environment Variables

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

