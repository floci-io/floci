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
| `TranslateKeyMaterial` | Move a key from a key the two parties agreed to another key, on `POST /keymaterial/translate` |
| `GenerateMac` | A MAC of a message under a key, on `POST /mac/generate` |
| `VerifyMac` | Verify a MAC over a message, on `POST /mac/verify` |
| `GeneratePinData` | Generate a PIN block and the verification value that goes with it, on `POST /pindata/generate` |
| `VerifyPinData` | Verify a PIN block against the verification value the issuer holds, on `POST /pindata/verify` |
| `TranslatePinData` | Re-encipher a PIN block from one key to another, on `POST /pindata/translate` |
| `GenerateCardValidationData` | Generate the card verification value of a card account, on `POST /cardvalidationdata/generate` |
| `VerifyCardValidationData` | Verify a card verification value against the one the issuer holds, on `POST /cardvalidationdata/verify` |
| `GenerateAuthRequestCryptogram` | Generate the cryptogram an authorization request carries, on `POST /cryptogram/generate` |
| `VerifyAuthRequestCryptogram` | Verify the cryptogram an authorization request carries, on `POST /cryptogram/verify` |
| `GenerateAs2805KekValidation` | Make or answer the AS2805 KEK validation of a key encryption key, on `POST /as2805kekvalidation/generate` |
<!-- floci:actions:end -->

## Emulation Behavior

- **The encryption is AES or Triple DES without padding**, so the caller supplies whole
  blocks: sixteen bytes under an AES key and eight under a Triple DES one, as the reference
  says. A plaintext that does not fill them is refused. A key of another family is refused rather
  than read as a key of one of these. The ciphertext is the encrypted bytes alone and is as long as
  the plaintext it holds: the initialization vector travels in the request's attributes, and a
  request that names none gets a zero vector. The blobs of the data plane are hexadecimal, which is
  what the reference says they are.
- **Every mode the reference names is made.** The model names eight block cipher modes for the
  symmetric encryption, and the page for the attributes names them as the valid values: the
  ECB, the CBC, the CFB, the CFB1, the CFB8, the CFB64, the CFB128 and the OFB. The CFB with
  no number is the one whose feedback is the cipher's own block, so it is the CFB128 under an
  AES key and the CFB64 under a Triple DES one. A mode whose feedback is wider than the
  cipher's own block is not one that cipher takes, so a Triple DES key is refused for the
  CFB128. The electronic code book mode takes no vector, so the vector of a request that
  names one is not read. The padding of the symmetric attributes belongs to the RSA cipher,
  so a request that names one is refused as well.
- **The symmetric cipher is the only shape of the attributes that is made.** The reference's
  model names four shapes of the encryption attributes: the symmetric one, the asymmetric one,
  the DUKPT one and the EMV one. This service makes the symmetric one, so a request that names
  the asymmetric attributes, the DUKPT attributes or the EMV attributes is refused with
  `ValidationException`. The RSA padding types the asymmetric attributes carry, PKCS1, OAEP
  SHA-1, OAEP SHA-256 and OAEP SHA-512, are therefore never reached, and neither are the DUKPT
  encryption mode or the EMV encryption mode, which the model names as ECB and CBC.
- **The MAC is the one the request asks for.** The ISO 9797-1 algorithms 1 and 3 are the ones
  defined over Triple DES, the CMAC is the AES-CMAC of RFC 4493, which is a whole block
  wide, and the keyed hash is the hash of RFC 2104. The service fails the request when the
  MAC is not the one over the message, because the answer of a verification has no verdict in
  it.
- **The keyed hash takes the hash the key carries.** The reference's guide settles the two
  forms the request takes: a key whose algorithm is one of the `HMAC_SHA` ones carries its
  hash, and the request names the family, so the key sets the hash; and a key of the
  `TR31_M7_HMAC_KEY` usage that is a Triple DES or an AES key is asked for by the hash the
  request names, so the request carries the hash of SHA-224, SHA-256, SHA-384 or SHA-512. A
  request that names a hash the key does not carry is refused.
- **The MAC algorithms this service does not make are refused.** The EMV MAC, the DUKPT CMAC
  and the AS 2805 4.1 MAC are not made, so a request that names one of them is refused with
  `ValidationException` rather than answered with a mac of another kind.
- **The length of a MAC is a number of hexadecimal characters.** The reference counts it that
  way: "a length of 8 refers to 8 hex characters (4 bytes)". The lengths the reference names
  for each algorithm are the ones this service takes: eight or sixteen characters for the
  Triple DES macs, and eight, sixteen or thirty-two for the CMAC of an AES key. A length that
  is not a whole number of bytes, or one the algorithm cannot give, is refused. The keyed
  hash ignores the length: "The mac-length parameter has no effect for HMAC. The output MAC
  is always the full hash output", so the mac of a keyed hash is the whole hash.
- **The MAC attributes are the algorithm or a DUKPT derivation.** The reference's model names
  five shapes for them: the algorithm, the EMV MAC, and the DUKPT derivations of the ISO 9797-1
  algorithm 1, of the algorithm 3 and of the CMAC. This service makes the algorithm and the two
  DUKPT derivations of the ISO 9797-1 algorithms, so a request that names the EMV MAC or the
  DUKPT CMAC is refused with `ValidationException`.
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
- **The PIN block formats this service makes are the three the model names beside the format 1.**
  The reference's model names four formats for the PIN data: the format 0, the format 1, the
  format 3 and the format 4. This service makes the format 0, the format 3 and the format 4, so
  a request that names the format 1, whether it generates a block, verifies one or translates
  one, is refused with `ValidationException`.
- **A verification reads the two Triple DES formats.** The reference's page for `VerifyPinData`
  says "AWS Payment Cryptography supports `ISO_Format_0` and `ISO_Format_3`", while the
  generation reads the format 4 as well, so a verification that names the format 4 or the
  format 1 is refused with `ValidationException`. The format 1 is a format ISO 9564-1 defines
  that this service does not make anywhere, so a request that asks for one is refused.
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
- **The key material of a key may be translated:** `TranslateKeyMaterial` takes a key block
  that came under a key two parties agreed with ECDH, and answers with the same key wrapped
  under the key the request names. The key material never leaves the service: the block is
  opened under the agreed key and wrapped again under the other one, and the block that goes
  out carries the attributes of the key it holds, its version and its optional blocks with
  them. The reference has it for the keys that
  are rotated for every transaction, such as a PEK wrapped under a key that was agreed
  with ECDH, so the key is moved to another wrapping key without being imported.
- **A translation is refused when the key that protects it is too weak.** The agreed key
  opens the block that comes in and the wrapping key closes the one that goes out, so both
  have to be at least as strong as the key they protect, the rule the export and the import
  enforce. A key that may not leave the service is refused as well: a block that carries the
  exportability `N` holds a key that may not be exported, and the block that goes out is
  handed to the caller. The usage the block carries has to be one the algorithm serves, the
  rule the import and the creation enforce, so a block that names a signature key and an AES
  key at once is refused. The check value algorithm the request names has to be the one the
  key has, the CMAC for an AES key and the ANSI X9.24 one for a Triple DES key, and a request
  that names another one is refused rather than answered with a value of another kind.
- **The PIN operations are not the IBM 3624 ones, and the AS2805 translation is not made.** A
  request that asks for one of them is refused with the error that says so, rather than
  answered with something of another kind. The EMV PIN change MAC is refused as well: it needs
  the session key the EMV secure messaging rules define, which this service does not make. It
  is not one of the actions the service lists as supported. The wrapped-key form of a PIN
  encryption key is refused too, because the unwrapping is part of the key exchange.
- **The UnionPay session key derivation is not made.** The reference's model names six ways
  of deriving the session key of an authorization request, and this service makes three of
  them: the EMV common session key, the Mastercard one and the Visa one, which derives none.
  The EMV 2000 one and the American Express one are refused, and so is the UnionPay one, each
  with the error that says so.
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
- **The card validation values this service makes are the two card verification values and the
  dynamic value.** The reference's model names seven shapes for the generation and eight for the
  verification: the American Express card security codes of the two versions, the card
  verification values of the two versions, the cardholder verification value, the dynamic card
  verification code, the dynamic card verification value, and the Discover dynamic card
  verification code on the verification side. This service makes the card verification value of
  the two versions and the dynamic card verification value, so a request that names an American
  Express card security code, the cardholder verification value or the Discover dynamic card
  verification code is refused with `ValidationException`.
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
- **The KEK validation is the AS2805 ceremony two nodes use to prove they hold the same
  key.** The node that starts the exchange makes a random number of eight, sixteen or
  twenty-four bytes, gives every byte the odd parity the standard's Single DES asks for,
  and enciphers it under a variant of the key it holds: the key exclusive-ored with the
  mask the request names, repeated over the key's length. The node that answers deciphers
  that number, inverts every bit of it, and enciphers the inverted value under the other
  variant of its own key. The first node compares the answer with the one it computed, so
  two nodes whose keys differ do not agree. The random number is enciphered a block at a
  time, in Triple DES, the cipher AS2805's cryptography is made of, and it is sixteen bytes
  long when the request does not name a length.
- **The key of a KEK validation is a Triple DES key encryption key.** It is the one AS2805
  calls the terminal major key, the usage `TR31_K0_KEY_ENCRYPTION_KEY`. The reference does
  not name the usage the key must have for this operation, so the one the service's own
  AS2805 import gives that key is the one it asks for. AS2805's cryptography is all TDES,
  so a key of another family is refused. The request form enciphers, so the key must allow
  `Encrypt`; the response form deciphers and enciphers the inverted number, so it must
  allow `Decrypt` and `Encrypt`.
- **The two variant masks are the standard's two forms of the ceremony.** The `82` form is
  the one an independent implementation of the ceremony confirms, byte for byte. The `82C0`
  form is the same ceremony under the longer of the two masks the reference names, and the
  mask of its inverted number is that form's `84C0`: two nodes validating under it agree
  with each other, but not with the reference.
- **The requests the ceremony does not fit are refused.** The derived key is the variant of
  the key the request names, so the algorithm the request names has to be the algorithm of
  that key, and a request that names another one is refused. The random number is
  enciphered in whole Triple DES blocks, and the three lengths the model names are whole
  numbers of them.
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
- **The quotas of the reference are not enforced.** The reference holds five hundred
  symmetric data plane requests a second and twenty asymmetric ones, and it throttles the
  request that passes the limit. The emulator holds no such limit, and answers every request
  it is given.

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
