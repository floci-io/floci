# Payment Cryptography data plane

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`

The data plane of AWS Payment Cryptography: the operations that use a key. It is a service of
its own, with its own host at `dataplane.payment-cryptography` and its own sign name, so a
client that talks to the control plane does not reach it by accident.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `EncryptData` | Encrypt plaintext under a key, on `POST /keys/{KeyIdentifier}/encrypt` |
| `DecryptData` | Decrypt a ciphertext under a key, on `POST /keys/{KeyIdentifier}/decrypt` |
| `ReEncryptData` | Decrypt under one key and encrypt under another, on `POST /keys/{IncomingKeyIdentifier}/reencrypt` |
| `GenerateMac` | The CMAC of a message under a key, on `POST /mac/generate` |
| `VerifyMac` | Verify a MAC over a message, on `POST /mac/verify` |
| `GeneratePinData` | The PIN data of a card account, on `POST /pindata/generate` |
| `VerifyPinData` | Verify the PIN data of a card account, on `POST /pindata/verify` |
| `TranslatePinData` | Translate PIN data from one scheme into another, on `POST /pindata/translate` |
| `GenerateCardValidationData` | The card validation value of a card account, on `POST /cardvalidationdata/generate` |
| `VerifyCardValidationData` | Verify a card validation value, on `POST /cardvalidationdata/verify` |
| `VerifyAuthRequestCryptogram` | Verify the cryptogram of an authorization request, on `POST /cryptogram/verify` |
| `GenerateAuthRequestCryptogram` | The cryptogram of an authorization request, on `POST /cryptogram/generate` |
<!-- floci:actions:end -->

## Emulation Behavior

- **The encryption is AES in CBC mode** with the padding the service calls PKCS#5. The answer
  carries the initialization vector and the ciphertext in one blob, and the blobs of the data
  plane are hexadecimal, which is what the reference says they are.
- **The MAC is the CMAC** of the message under the key, cut to the length the caller asks
  for. The service fails the request when the MAC is not the one over the message, because
  the answer of a verification has no verdict in it.
- **The modes of use of a key decide what it may be used for.** A key without the `Encrypt`
  mode is refused for an encryption, and a key with `NoRestrictions` is refused for nothing.
- **The payment schemes' own algorithms are not served.** The card validation values, the
  cryptograms and the PIN block formats belong to the schemes, so those operations are
  refused rather than answered with a value this service made up.

The two-segment paths are moved to an internal prefix for the requests signed for this
service, so a path-style request for a bucket of that name still reaches S3.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PAYMENTCRYPTOGRAPHY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

KEY_ARN=$(aws payment-cryptography create-key \
  --cli-input-json file://key.json --query Key.KeyArn --output text)

aws payment-cryptography-data encrypt-data \
  --key-identifier "$KEY_ARN" \
  --plain-text "$(printf 'hello' | xxd -p -c 32)" \
  --encryption-attributes '{"Symmetric":{"Mode":"CBC"}}'
```
