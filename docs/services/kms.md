# KMS

**Protocol:** JSON 1.1 (`X-Amz-Target: TrentService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateKey` |
| `GetPublicKey` |
| `DescribeKey` |
| `ListKeys` |
| `Encrypt` |
| `Decrypt` |
| `ReEncrypt` |
| `GenerateDataKey` |
| `GenerateDataKeyWithoutPlaintext` |
| `Sign` |
| `Verify` |
| `CreateAlias` |
| `DeleteAlias` |
| `ListAliases` |
| `ScheduleKeyDeletion` |
| `CancelKeyDeletion` |
| `TagResource` |
| `UntagResource` |
| `ListResourceTags` |
| `GetKeyPolicy` |
| `PutKeyPolicy` |
| `GetKeyRotationStatus` |
| `EnableKeyRotation` |
| `DisableKeyRotation` |
<!-- floci:actions:end -->

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a symmetric key
KEY_ID=$(aws kms create-key \
  --description "My encryption key" \
  --query KeyMetadata.KeyId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create an alias
aws kms create-alias \
  --alias-name alias/my-key \
  --target-key-id $KEY_ID \
  --endpoint-url $AWS_ENDPOINT_URL

# Encrypt
CIPHER=$(aws kms encrypt \
  --key-id alias/my-key \
  --plaintext "Hello, World!" \
  --query CiphertextBlob --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Decrypt
aws kms decrypt \
  --ciphertext-blob $CIPHER \
  --query Plaintext --output text \
  --endpoint-url $AWS_ENDPOINT_URL | base64 --decode

# Generate a data key (envelope encryption)
aws kms generate-data-key \
  --key-id alias/my-key \
  --key-spec AES_256 \
  --endpoint-url $AWS_ENDPOINT_URL
```
`CreateKey` also accepts a reserved creation-time tag key, `floci:override-id`, when tests need a deterministic `KeyId`. Floci uses the tag value as the created key id, strips the reserved tag from stored resource tags, and rejects attempts to add `floci:*` tags later via `TagResource`.
