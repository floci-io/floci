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
| `GenerateMac` | A MAC of a message under a key, on `POST /mac/generate` |
| `VerifyMac` | Verify a MAC over a message, on `POST /mac/verify` |
<!-- floci:actions:end -->

## Emulation Behavior

- **The encryption is AES or Triple DES in CBC mode without padding**, so the caller supplies
  whole blocks: sixteen bytes under an AES key and eight under a Triple DES one, as the reference
  says. A plaintext that does not fill them is refused. A key of another family is refused rather
  than read as a key of one of these. The ciphertext is the encrypted bytes alone and is as long as
  the plaintext it holds: the initialization vector travels in the request's attributes, and a
  request that names none gets a zero vector. The blobs of the data plane are hexadecimal, which is
  what the reference says they are.
- **The MAC is the one the request asks for.** The ISO 9797-1 algorithms 1 and 3 are the ones
  defined over Triple DES, and the CMAC is the AES-CMAC of RFC 4493, which is a whole block
  wide, so it is cut to a length the caller asks for. The service fails the request when the
  MAC is not the one over the message, because the answer of a verification has no verdict in
  it.
- **The modes of use of a key decide what it may be used for.** A key without the `Encrypt`
  mode is refused for an encryption, and a key with `NoRestrictions` is refused for nothing.
- **The usage of a key decides what it may be used for.** The modes say whether a key may be
  used at all; the usage says for what. A key of the PIN encryption usage is not used to
  encrypt arbitrary data, and a key of a MAC usage is not used to encrypt either.
- **The key's own algorithm is the cipher that runs.** A key of an algorithm this service does
  not cipher with is refused rather than read as bytes it is not.

The two-segment paths are moved to an internal prefix for the requests signed for this
service, so a path-style request for a bucket of that name still reaches S3.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_PAYMENTCRYPTOGRAPHY_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# A key of the data encryption usage
KEY_ARN=$(aws payment-cryptography create-key \
  --key-attributes 'KeyAlgorithm=AES_256,KeyClass=SYMMETRIC_KEY,KeyUsage=TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY,KeyModesOfUse={Encrypt=true,Decrypt=true}' \
  --exportable \
  --query Key.KeyArn --output text)

# Encrypt under it. The cipher pads no data, so the plaintext is a whole number of
# blocks: sixteen bytes for an AES key.
aws payment-cryptography-data encrypt-data \
  --key-identifier "$KEY_ARN" \
  --plain-text "$(printf 'hello world!!!!!' | xxd -p -c 32)" \
  --encryption-attributes '{"Symmetric":{"Mode":"CBC"}}'
```
