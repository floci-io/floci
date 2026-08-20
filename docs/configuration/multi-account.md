# Multi-Account Isolation

Floci supports full per-account resource isolation out of the box. Resources created by one account are invisible to all others — no configuration flag required.

## How It Works

Every incoming request carries an AWS SigV4 `Authorization` header. Floci reads the **Access Key ID** (AKID) from that header and resolves the account in this order:

1. An exactly 12-digit AKID is used directly as the account ID.
2. An active long-term IAM access key resolves to the account that owns it.
3. A live STS temporary credential resolves to its recorded account.
4. Any other key falls back to `FLOCI_DEFAULT_ACCOUNT_ID`.

```
Authorization: AWS4-HMAC-SHA256 Credential=111111111111/20260510/us-east-1/sqs/aws4_request, ...
                                            ^^^^^^^^^^^^
                                            12-digit AKID → account ID 111111111111
```

Once the account ID is determined, every storage read and write is transparently namespaced under it. An SQS queue named `orders` created by account `111111111111` is stored and retrieved as `111111111111/orders` — completely separate from the same queue name under account `222222222222`.

!!! note "Same convention as LocalStack"
    This 12-digit AKID → account ID rule matches LocalStack's multi-account behavior, so existing multi-account test setups work without changes.

## Long-Term IAM Credentials

Active access keys created through IAM route requests back to the account that owns the key. This lets a client keep using its generated `AKIA…` credentials instead of replacing the access key ID with a synthetic 12-digit account ID.

Account selection is AKID-based and is not proof that the caller possesses the corresponding secret. This extends Floci's existing local-emulator trust model for 12-digit AKIDs; do not treat account namespaces as a security boundary between mutually untrusted clients.

## Temporary Credentials (AssumeRole)

STS temporary credentials are also routed to the correct account. When you call `AssumeRole` (or `AssumeRoleWithWebIdentity`, `AssumeRoleWithSAML`, `GetFederationToken`), Floci issues a temporary `ASIA…` access key and remembers which account it belongs to:

- **`AssumeRole` / web-identity / SAML / federation** → the account in the role (or federated-user) ARN. Assuming `arn:aws:iam::222222222222:role/Deployer` from account `111111111111` yields credentials that resolve to **`222222222222`**, so resources created with them land in account B's namespace.
- **`GetSessionToken`** → the caller's account (these credentials carry no role), so they stay in the same account.

```
Account 111111111111 ──AssumeRole arn:aws:iam::222222222222:role/Deployer──▶ ASIA… temp key
                                                                              │
ASIA… temp key ──CreateTable orders──▶ stored as 222222222222/...::orders  ◀──┘
```

This makes the cross-account `AssumeRole`-then-provision pattern (e.g. CloudFormation deploying into a target account) work locally exactly as it does in AWS.

## Default Behavior (Single Account)

If a credential is not a 12-digit account ID, an active IAM access key, or a live STS session (for example, `test`), requests resolve to the default account ID:

```bash
FLOCI_DEFAULT_ACCOUNT_ID=000000000000   # default
```

All ARNs and URLs use this value:

```
arn:aws:sqs:us-east-1:000000000000:my-queue
http://localhost:4566/000000000000/my-queue
```

You can change the default account ID without enabling per-request isolation:

```bash
FLOCI_DEFAULT_ACCOUNT_ID=123456789012
```

## Enabling Multi-Account Isolation

Use 12-digit numeric access key IDs. The secret access key can be any non-empty string — Floci does not validate signatures by default.

### AWS CLI

```bash
# Configure two named profiles
aws configure --profile account-a
# AWS Access Key ID: 111111111111
# AWS Secret Access Key: test

aws configure --profile account-b
# AWS Access Key ID: 222222222222
# AWS Secret Access Key: test

export AWS_ENDPOINT_URL=http://localhost:4566

# Create the same queue name under both accounts
aws sqs create-queue --queue-name orders --profile account-a
aws sqs create-queue --queue-name orders --profile account-b

# Each account sees only its own queue
aws sqs list-queues --profile account-a   # → .../111111111111/orders
aws sqs list-queues --profile account-b   # → .../222222222222/orders
```

### AWS SDK (Java)

```java
StaticCredentialsProvider accountA = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("111111111111", "test"));

StaticCredentialsProvider accountB = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("222222222222", "test"));

SqsClient clientA = SqsClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(accountA)
    .build();

SqsClient clientB = SqsClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(accountB)
    .build();

// Both calls succeed, resources are fully isolated
clientA.createQueue(r -> r.queueName("orders"));
clientB.createQueue(r -> r.queueName("orders"));
```

### AWS SDK (Python)

```python
import boto3

def client(service, account_id):
    return boto3.client(
        service,
        endpoint_url="http://localhost:4566",
        region_name="us-east-1",
        aws_access_key_id=account_id,      # 12-digit → account ID
        aws_secret_access_key="test",
    )

sqs_a = client("sqs", "111111111111")
sqs_b = client("sqs", "222222222222")

sqs_a.create_queue(QueueName="orders")
sqs_b.create_queue(QueueName="orders")

print(sqs_a.list_queues()["QueueUrls"])  # [".../111111111111/orders"]
print(sqs_b.list_queues()["QueueUrls"])  # [".../222222222222/orders"]
```

## ARNs Include the Correct Account ID

Floci embeds the resolved account ID in every ARN it generates:

```
arn:aws:sqs:us-east-1:111111111111:orders
arn:aws:lambda:us-east-1:222222222222:function:my-fn
arn:aws:s3:::my-bucket                         # S3 ARNs are account-agnostic
```

## Isolation Scope

All services that use `StorageFactory` participate in account isolation automatically. This covers every service in Floci — SQS, SNS, S3, DynamoDB, Lambda, SSM, Secrets Manager, KMS, Kinesis, EventBridge, Cognito, RDS, ElastiCache, OpenSearch, MSK, and more.

Background workers (Lambda event-source pollers, DynamoDB TTL sweeper, MSK readiness poller, OpenSearch readiness poller) iterate across all accounts internally and route writes back to the originating account. No cross-account data leaks through these async paths.

## Signature Validation

Floci does not currently perform general SigV4 validation for `Authorization` headers. Only the access key ID matters for account resolution, so the secret access key can be any non-empty string.

`FLOCI_AUTH_VALIDATE_SIGNATURES` currently applies only to S3 presigned URL validation. It does not authenticate general service requests or protect IAM and STS account routing. To validate S3 presigned URLs:

```bash
FLOCI_AUTH_VALIDATE_SIGNATURES=true
FLOCI_AUTH_PRESIGN_SECRET=your-secret   # for pre-signed URL verification
```

When `validate-signatures` is `false` (the default), S3 presigned URL signatures are not verified. Account routing remains AKID-based regardless of this setting.

## Persistence and Account Isolation

Storage keys are namespaced per account at the persistence layer. When using `persistent`, `hybrid`, or `wal` storage modes, each account's data is stored under its own key prefix. Restarting Floci restores each account's resources independently.

## Configuration Reference

| Variable | Default | Description |
|---|---|---|
| `FLOCI_DEFAULT_ACCOUNT_ID` | `000000000000` | Account ID used when the AKID does not resolve directly or through a stored credential |
| `FLOCI_DEFAULT_REGION` | `us-east-1` | Region used when not derivable from the `Authorization` header |
| `FLOCI_AUTH_VALIDATE_SIGNATURES` | `false` | Verify S3 presigned URL signatures |
