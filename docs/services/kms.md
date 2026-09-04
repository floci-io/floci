# KMS

**Protocol:** JSON 1.1 (`X-Amz-Target: TrentService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateKey` | Create a new KMS key |
| `GenerateRandom` | Generate random bytes |
| `GetPublicKey` | Get public key material for asymmetric keys |
| `DescribeKey` | Get key metadata |
| `ListKeys` | List all keys |
| `CreateGrant` | Create a grant for a KMS key |
| `ListGrants` | List grants for a KMS key |
| `ListRetirableGrants` | List grants retirable by a principal |
| `RevokeGrant` | Revoke (administratively delete) a grant |
| `RetireGrant` | Retire a grant (token- or key+grant-based) |
| `Encrypt` | Encrypt plaintext with a key |
| `Decrypt` | Decrypt ciphertext |
| `ReEncrypt` | Re-encrypt under a different key |
| `GenerateDataKey` | Generate a data key (plaintext + encrypted) |
| `GenerateDataKeyWithoutPlaintext` | Generate only the encrypted data key |
| `Sign` | Sign a message with an asymmetric key |
| `Verify` | Verify a signature |
| `GenerateMac` | Generate a MAC with an HMAC key |
| `VerifyMac` | Verify a MAC with an HMAC key |
| `CreateAlias` | Create a friendly name for a key |
| `UpdateAlias` | Repoint an alias at a different key |
| `DeleteAlias` | Remove an alias |
| `ListAliases` | List all aliases |
| `ScheduleKeyDeletion` | Mark a key for deletion |
| `CancelKeyDeletion` | Cancel pending deletion |
| `TagResource` | Tag a key |
| `UntagResource` | Remove tags |
| `ListResourceTags` | List tags |
| `GetKeyPolicy` | Get a key's resource policy |
| `PutKeyPolicy` | Update a key's resource policy |
| `ListKeyPolicies` | List a key's policy names (always the single `default` policy) |
| `UpdateKeyDescription` | Update a key's description |
| `GetKeyRotationStatus` | Check if automatic key rotation is enabled |
| `EnableKeyRotation` | Enable automatic key rotation (symmetric keys only) |
| `DisableKeyRotation` | Disable automatic key rotation |
| `EnableKey` | Enable a key |
| `DisableKey` | Disable a key |
| `RotateKeyOnDemand` | Rotate key material on demand (symmetric keys only) |
| `GetParametersForImport` | Get the wrapping key and import token for an `EXTERNAL` key |
| `ImportKeyMaterial` | Import key material into an `EXTERNAL` key |
| `DeleteImportedKeyMaterial` | Delete imported key material, returning the key to `PendingImport` |
<!-- floci:actions:end -->

## Asymmetric Encryption

`Encrypt`, `Decrypt`, and `ReEncrypt` apply real RSAES-OAEP for RSA keys (`RSA_2048`, `RSA_3072`, `RSA_4096`) when `EncryptionAlgorithm` is `RSAES_OAEP_SHA_1` or `RSAES_OAEP_SHA_256`. The ciphertext is raw RSA output of the modulus length, for example exactly 256 bytes for `RSA_2048`. A ciphertext produced locally with the public key from `GetPublicKey` decrypts the same way it does on real AWS, which makes the usual envelope pattern work. Only the encrypting side needs the public key. As on real AWS, asymmetric `Decrypt` requires `KeyId`, an `EncryptionContext` is rejected for asymmetric keys, and plaintext larger than the OAEP capacity of the key fails validation.

Symmetric keys keep the emulator's internal ciphertext format, which is not compatible with ciphertexts from real AWS KMS.

## Imported Key Material

`CreateKey` accepts `Origin=EXTERNAL`, which creates a key with no key material in state
`PendingImport`. `GetParametersForImport` returns a real RSA public key and an import token;
material wrapped with that public key by a standard client is unwrapped by `ImportKeyMaterial`,
which puts the key in state `Enabled`. Wrapping material against the wrong key, or with a
different algorithm than the one requested, fails with `InvalidCiphertextException` the same way
it does on AWS.

Supported `WrappingAlgorithm` values are `RSAES_OAEP_SHA_256` and `RSAES_OAEP_SHA_1`, over
`WrappingKeySpec` `RSA_2048`, `RSA_3072` or `RSA_4096`. `RSAES_PKCS1_V1_5` is rejected, matching
AWS, which stopped supporting it on October 10, 2023. The `RSA_AES_KEY_WRAP_*` variants exist for
material longer than an RSA modulus can hold and are also rejected: no importable key spec here
carries more than 64 bytes.

An import token is scoped to one key and spent by the import that uses it, and a second
`GetParametersForImport` call invalidates the token the previous one returned. Tokens expire 24
hours after they are issued.

`ExpirationModel=KEY_MATERIAL_EXPIRES` (the default) requires `ValidTo`, which must be in the
future and no more than 365 days out. Once `ValidTo` passes, the material is dropped and the key
returns to `PendingImport`, as does `DeleteImportedKeyMaterial`. Expiry is evaluated when the key
is next read rather than on a timer, which is not observable through the API. Deleting the
material of a key that is already in `PendingDeletion` leaves that state in place.

A key in `PendingImport` rejects cryptographic operations, `EnableKey` and `DisableKey` with
`KMSInvalidStateException`. `CancelKeyDeletion` on a key whose material was never imported, or was
deleted or expired while it was pending deletion, returns it to `PendingImport` rather than to a
usable state it could not serve. `DeleteImportedKeyMaterial` on a key that holds no material
succeeds, as it does on AWS. `ImportKeyMaterial` and `DeleteImportedKeyMaterial` return a
`KeyMaterialId`, derived from the key id and the material as AWS derives it. Re-importing
requires the same material the key was first given; different material is rejected with
`IncorrectKeyMaterialException`.

Automatic key rotation is rejected for keys with imported material, matching AWS: KMS does not
own the material and cannot rotate it.

**Deviations:**

- `Origin=EXTERNAL` is supported only for `SYMMETRIC_DEFAULT` and the `HMAC_*` key specs, whose
  material is a raw byte string. Real AWS KMS also imports asymmetric material as a DER-encoded
  key pair; here an asymmetric spec with `Origin=EXTERNAL` is rejected at `CreateKey` with
  `UnsupportedOperationException` rather than creating a key that could never sign or decrypt.
- Holding several imported key materials on one symmetric key, which real KMS uses for on-demand
  rotation of imported material, is not emulated. `ImportType=NEW_KEY_MATERIAL` on a key that
  already has key material is rejected with `UnsupportedOperationException`, and
  `ListKeyRotations` is not implemented.

## Grant Support Scope

Grant lifecycle operations (`CreateGrant`, `ListGrants`, `ListRetirableGrants`, `RevokeGrant`, `RetireGrant`) are supported. However, grant lifecycle support **does not** imply grant-based authorization enforcement on cryptographic operations (`Encrypt`, `Decrypt`, `Sign`, `Verify`, `GenerateDataKey`, etc.). Grants are stored and queryable but are not evaluated during crypto operations.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_KMS_ENABLED` | `true` | Enable or disable the service |

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
