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
| `GeneratePinData` | Generate a PIN block and the verification value that goes with it, on `POST /pindata/generate` |
| `VerifyPinData` | Verify a PIN block against the verification value the issuer holds, on `POST /pindata/verify` |
| `TranslatePinData` | Re-encipher a PIN block from one key to another, on `POST /pindata/translate` |
| `GenerateCardValidationData` | Generate the card verification value of a card account, on `POST /cardvalidationdata/generate` |
| `VerifyCardValidationData` | Verify a card verification value against the one the issuer holds, on `POST /cardvalidationdata/verify` |
| `GenerateAuthRequestCryptogram` | Generate the cryptogram an authorization request carries, on `POST /cryptogram/generate` |
| `VerifyAuthRequestCryptogram` | Verify the cryptogram an authorization request carries, on `POST /cryptogram/verify` |
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
- **The PIN operations are the Visa path.** The PIN block is the one of the ISO 9564-1 format
  the request names: the formats 0 and 3 are eight-byte Triple DES blocks, and the format 4 is
  a sixteen-byte AES block that binds the PIN to the whole account number rather than to the
  twelve-digit account field. The blocks are enciphered in ECB mode, which is what the standard
  defines, and they are not padded. The verification value is the Visa PIN verification value,
  computed under a key of the `TR31_V2_VISA_PIN_VERIFICATION_KEY` usage, and it is a four-digit
  verification value. The algorithm's transformed security parameter is sixteen digits: eleven
  of account number, one of verification key index and four of PIN, so the PIN it is computed
  over is a four-digit one and a longer PIN has no verification value. A PIN that does not
  verify fails the request with `VerificationFailedException`, because the answer of a
  verification has no verdict in it.
- **A PIN block that is not one the service can read is a different failure.** A block that
  does not decipher into a PIN field, because the key is another one, the account number is
  another one, or the block is corrupted, is refused with `ValidationException` and the
  message the reference uses. A PIN is not translated from one account number to another:
  `TranslatePinData` refuses a request whose incoming and outgoing `PrimaryAccountNumber`
  differ, for PCI compliance.
- **A DUKPT key serial number is the one the Triple DES derivation takes.** The model lets a
  key serial number be eight, ten or twelve bytes, and the twelve byte one belongs to the AES
  derivation, which this service does not make; a key serial number of more than ten bytes is
  refused. The model's DUKPT attributes come in two shapes: the PIN operations take the key
  serial number and the derivation type, and the translation takes the key serial number, an
  optional derivation type and an optional key variant. The derivation this path makes is the
  Triple DES one, so a request that asks for another of the five the model allows is refused,
  and the PIN key it derives both encrypts and decrypts, so a key variant other than the
  bidirectional one is refused.
- **The PIN operations are not the IBM 3624 ones, and the AS2805 translation is not made.** A
  request that asks for one of them is refused with the error that says so, rather than
  answered with something of another kind. The EMV PIN change MAC is refused as well: it needs
  the session key the EMV secure messaging rules define, which this service does not make. It
  is not one of the actions the service lists as supported. The wrapped-key form of a PIN
  encryption key is refused too, because the unwrapping is part of the key exchange.
- **The wrapped key is refused on every operation that carries one.** The encrypt, decrypt
  and re-encrypt requests hold a wrapped key for the key exchange, and the unwrapping is
  part of that exchange rather than part of the cipher, so a request that carries one is
  refused rather than answered as if the key were a fixed one.
- **The card verification value is the three digit value of the X9 algorithm.** It is computed
  from the account number, the expiry and the service code, enciphered under the card
  verification key, and the value is the decimalized left-most digits of the enciphered block.
  The static values take a key of the `TR31_C0_CARD_VERIFICATION_KEY` usage. The value
  carries the number of digits the request asks for, which the model states as three to
  five.
- **The dynamic value is the one Visa describes for the magstripe data path.** The leftmost
  four digits of the account number are replaced by the transaction counter, and the result,
  the expiry and the service code are enciphered under the card's unique derived key, which
  the issuer derives from the master key with the account number and the PAN sequence
  number. The value is bound to the transaction, and the counter diversifies it through the
  data it is inserted into rather than through the key. It takes a key of the
  `TR31_E6_EMV_MKEY_OTHER` usage. The dynamic card verification code, which is the
  Mastercard counterpart over the track data, is refused rather than approximated: it is a
  proprietary construction and there is nothing published to check a derivation against.
- **The authorization cryptogram is the ARQC of the transaction.** It is the ISO 9797-1
  algorithm 3 retail MAC over the transaction data, cut to eight bytes, under a session key
  the request's derivation produces. Three derivations are served: the EMV common session key,
  the Mastercard one, which also diversifies with the terminal's unpredictable number, and
  the Visa one, which derives no session key at all and enciphers the transaction data under
  the ICC master key.
- **The transaction data is enciphered exactly as it is given.** It reaches the service
  already padded, as the user guide tells the caller to pad it, because the card padded
  it the same way before it enciphered it. The service therefore pads nothing: padding
  it again would encipher a block the card never enciphered.
- **The transaction data has to be a whole number of eight-byte blocks.** The ISO 9797-1
  MAC runs over eight-byte blocks, and the guide tells the caller to pad the data to a
  multiple of the block size before sending it, so a length that is not a whole number
  of blocks is a request the cipher cannot read and is refused.
- **The authorization cryptogram is made under a key of the application cryptogram
  usage.** The key table names `TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS` with a `TDES_2KEY`
  algorithm and the `DeriveKey` mode of use for it, so a key of another usage, another
  algorithm, or without the mode of use is refused.
- **A key that is disabled does no work, and a key that is not there is a 404.** The
  service reads a disabled key and tells the caller about it, but it does not compute a card
  value or a cryptogram under it, and a request that names a key the service does not hold
  is answered with the not-found error and the status the data plane answers it with.
- **The card operations and the cryptogram operations read the key through one helper.**
  The usage, the algorithm, the mode of use, whether the key is enabled and whether it
  still holds its material are asked in one place, so the two cannot drift apart on
  what they accept.
- **The response value of the second method is made of the card status update.** The first
  method takes the response code, and the second takes the card status update and the
  proprietary data, as EMV Book 2 section 8.2.2 has it.
- **The American Express cryptogram is refused rather than approximated.** The AEIPS session
  key derivation is proprietary and there is nothing to verify it against, and a plausible
  wrong cryptogram is the one outcome worse than an error. The EMV 4.1 tree derivation, the
  one the `Emv2000` attributes ask for, is refused as well: it is over the master key and
  the ATC, and this service does not implement that construction.
- **The card requests are checked against the model.** The account number is twelve to
  nineteen digits, the expiry is four of them, the service code is three, and the PAN
  sequence number is two: the EMV ones, as the model states them. A formatted account
  number and a longer sequence number are refused rather than read as the digits they
  carry, because the derivation reads the account number and the sequence number as they
  are given.
- **The master key is derived over the rightmost sixteen digits.** The account number and
  the PAN sequence number are padded on the left with zeros when they are shorter, which
  is the rule EMV 4.4 Book 2 gives and the Visa Integrated Circuit Card Specification
  states the same way. So an account number of twelve digits is read and derived from,
  rather than refused.
- **A hexadecimal member of an odd length is refused.** The model states the length of a
  hexadecimal member and does not always state an even one, so an odd number of
  hexadecimal characters is a request the model allows and the cipher cannot read. The
  track data, the transaction data and the proprietary authentication data are the members
  it applies to, and the refusal names the member.

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
