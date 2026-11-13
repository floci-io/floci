# Spec Delta

## Purpose

Defines the key-material forms the Payment Cryptography key exchange serves on export
and import, and the parameters and certificate operations that back them, including the
TR-34 key block and the AS2805 key cryptogram forms carried over from part 6.

## ADDED Requirements

### Requirement: TR-34 key block export
The service SHALL export a key in the `Tr34KeyBlock` form: a TR-31 key block over the
key material, signed by the service's signing key and wrapped under the receiver's
wrapping key certificate, in the `X9_TR34_2012` format.

#### Scenario: A key leaves under a TR-34 block
- **WHEN** `ExportKey` names a `Tr34KeyBlock` with a registered certificate authority
  identifier, a valid wrapping key certificate, the `X9_TR34_2012` block format, and the
  token from `GetParametersForExport`
- **THEN** the answer carries the wrapped key with format `TR34_KEY_BLOCK` and
  hexadecimal key material that a counterparty can recover

#### Scenario: A block signed by another authority does not open
- **WHEN** `ImportKey` receives a `Tr34KeyBlock` whose signature does not verify against
  the certificate authority the request names
- **THEN** the service refuses the import with a validation error

### Requirement: TR-34 key block import
The service SHALL import a key from a `Tr34KeyBlock`, verifying the signature against
the named certificate authority, checking the embedded digest, and unwrapping the block
with the import key named by the request's token.

#### Scenario: A key that leaves under a TR-34 block comes back
- **WHEN** a key is exported in the `Tr34KeyBlock` form and the material is presented to
  `ImportKey` with the parameters token
- **THEN** the imported key has the same algorithm and key usage as the exported key and
  its check value matches

### Requirement: AS2805 key cryptogram export
The service SHALL export a key in the `As2805KeyCryptogram` form: the key material
bound to a named AS2805 variant and encrypted under a key encryption key, for the
variants `TERMINAL_MAJOR_KEY_VARIANT_00`, `DATA_ENCRYPTION_KEY_VARIANT_22`,
`MESSAGE_AUTHENTICATION_KEY_VARIANT_24`, and `PIN_ENCRYPTION_KEY_VARIANT_28`.

#### Scenario: A key leaves under an AS2805 cryptogram
- **WHEN** `ExportKey` names an `As2805KeyCryptogram` with a supported variant and a
  wrapping key identifier that holds a key encryption key with the wrap permission
- **THEN** the answer carries the cryptogram with format `KEY_CRYPTOGRAM` under that
  wrapping key

#### Scenario: A variant the service does not support is refused
- **WHEN** `ExportKey` names an `As2805KeyCryptogram` with a variant outside the four
  supported ones
- **THEN** the service refuses the request with a validation error that names the
  supported variants

#### Scenario: A wrapping key that is not a key encryption key is refused
- **WHEN** `ExportKey` names an `As2805KeyCryptogram` with a wrapping key whose usage is
  not a key encryption key usage
- **THEN** the service refuses the request with a validation error

### Requirement: AS2805 key cryptogram import
The service SHALL import a key from an `As2805KeyCryptogram`, unbinding the variant and
decrypting the material under the wrapping key, and SHALL give the imported key the
usage the variant carries.

#### Scenario: A key that leaves under an AS2805 cryptogram comes back
- **WHEN** a key is exported in the `As2805KeyCryptogram` form and the cryptogram is
  presented to `ImportKey` with the same variant and wrapping key
- **THEN** the imported key material equals the exported key material

#### Scenario: A cryptogram wrapped under the wrong variant does not open
- **WHEN** `ImportKey` receives an `As2805KeyCryptogram` with a variant other than the
  one the cryptogram was wrapped under
- **THEN** the recovered key material does not equal the exported material and the import
  fails its integrity checks

### Requirement: Key material forms are served as one named form per request
The service SHALL accept exactly one key-material form per export or import request and
SHALL refuse a form it does not serve with a validation error that names the forms it
does serve, now including `Tr34KeyBlock` and `As2805KeyCryptogram` in that list.

#### Scenario: A form the service does not serve is refused
- **WHEN** `ExportKey` names a form outside the served set
- **THEN** the service answers with a validation error that names the served forms,
  including `Tr34KeyBlock` and `As2805KeyCryptogram`

### Requirement: Export and import parameter operations
The service SHALL serve `GetParametersForExport` for the `TR34_KEY_BLOCK` material type
and `GetParametersForImport` for the `KEY_CRYPTOGRAM` and `TR34_KEY_BLOCK` material
types, each answering with an operation-named token, a self-signed certificate whose
chain is itself, the algorithm, and a validity timestamp.

#### Scenario: The export parameters carry a token and a certificate
- **WHEN** `GetParametersForExport` is asked for the `TR34_KEY_BLOCK` type
- **THEN** the answer carries an export token, a signing key certificate, the algorithm,
  and a validity timestamp, and the token is good for exports until that moment

#### Scenario: An import token is refused for another kind of material
- **WHEN** `ImportKey` quotes a token issued for a different material type than the form
  it carries
- **THEN** the service refuses the import

### Requirement: Public key certificate operation
The service SHALL serve `GetPublicKeyCertificate`, answering for an asymmetric key with
the base64 of the PEM certificate over the key's public half.

#### Scenario: A key pair has a certificate
- **WHEN** `GetPublicKeyCertificate` is asked for a key pair
- **THEN** the answer carries a certificate that parses and whose public key matches the
  key pair's public half

### Requirement: Pre-existing key exchange forms are preserved
The migration SHALL not change the behavior of the forms already served before it: the
TR-31 key block under a wrapping key, the key cryptogram under a counterparty
certificate, the Diffie-Hellman TR-31 key block under an agreed secret, and the root and
trusted public key imports.

#### Scenario: A key leaves in a TR-31 block and comes back
- **WHEN** a key is exported in the `Tr31KeyBlock` form and the block is presented to
  `ImportKey` with the same wrapping key
- **THEN** the imported key has the same attributes as the exported key

#### Scenario: A public key import verifies against the named authority
- **WHEN** `ImportKey` names a `TrustedCertificatePublicKey` whose certificate was not
  signed by the named certificate authority
- **THEN** the service refuses the import
