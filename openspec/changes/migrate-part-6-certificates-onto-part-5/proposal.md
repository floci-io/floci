# Proposal

## Why

The `feature/part-6-certificates` branch (certificates, TR-34, AS2805 for Payment
Cryptography) sits on a stale base: it is rooted at `eaa553cf7`, which predates the
repository-wide package rename from `io.github.hectorvent.floci` to `io.floci.aws`
(#7689) and predates the refactor of the key-exchange code. Its sibling
`feature/part-5-key-exchange` is already rebased onto the new base: it uses the
`io.floci.aws` package, and it has reworked the key exchange into shared helpers
(`Certificates`, `PaymentCryptographyKeyPairs`, `PaymentCryptographyKeyRules`,
`PaymentCryptographyKeyStrength`, a `Hex`/`HexFormat` discipline, and the reviewed
`PaymentCryptographyKeyExchange`). Part 6's first five commits duplicate work that
part 5 has since redone in a reviewed, refactored form, so a straight rebase of all six
commits would replay superseded code and fight the rename. The branch cannot be built or
tested against the current tree until it is migrated onto part 5.

## What Changes

- Migrate part 6's unique work onto the tip of `feature/part-5-key-exchange`
  (`3443ec139`). The unique work is the single commit
  `18323d5a8 feat(paymentcryptography): the certificates, TR-34 and AS2805`.
- Drop part 6's first five commits (`57922a20c`, `32bce331d`, `803d772db`, `ac32e54c7`,
  `07d16602a`). They are superseded one-for-one by part 5's reviewed, refactored
  equivalents (control-plane algorithms, data plane, PIN operations, card values /
  auth cryptograms, and the key exchange). Part 5's versions are the source of truth.
- Re-land the migrated commit under the `io.floci.aws` package and adapt it to part 5's
  refactored key exchange. Concretely this means:
  - **New crypto helpers** (`Tr34`, `Asn1`, `Hex`, `X509`, `PaymentCryptographyAs2805Cryptogram`,
    `PaymentCryptographyAs2805Variant`) move into
    `io.floci.aws.services.paymentcryptography.crypto` /
    `io.floci.aws.services.paymentcryptography`, reusing part 5's existing
    `Hex`/`Certificates` helpers where part 6 hand-rolled them.
  - **`PaymentCryptographyKeyExchange`**: add the TR-34 and AS2805 export/import forms on
    top of part 5's current class (part 5 already mints certificates via `Certificates`
    and `CertificateGenerator`; part 6's standalone `X509`/`Hex` minting is folded into
    part 5's path rather than duplicated).
  - **`PaymentCryptographyService` / `PaymentCryptographyJsonHandler`**: part 5 already
    exposes `GetPublicKeyCertificate`, `GetParametersForImport`, and
    `GetParametersForExport`; the migrated commit's additions to those are reconciled
    against the existing part 5 handlers, not re-added.
- **Behavior on the branch becomes:** the key exchange serves the TR-34 key block and the
  AS2805 key cryptogram forms (previously refused on part 5), in addition to the
  TR-31 / key cryptogram / ECDH forms.
- No new AWS endpoint, no wire-format change beyond adding the two forms the AWS model
  already defines. The vendored request shapes for `payment-cryptography` already name
  `Tr34KeyBlock` and `As2805KeyCryptogram`, so no data regeneration is expected.

## Capabilities

### New Capabilities

- `payment-cryptography-key-exchange`: the set of key-material forms the Payment
  Cryptography key exchange serves on export and import (TR-31 block, key cryptogram,
  ECDH block, and, added by this change, TR-34 key block and AS2805 key cryptogram), and
  the parameters/certificate operations that back them.

### Modified Capabilities

<!-- None: openspec/specs is empty; there is no existing capability to modify. -->

## Impact

- Affected code: `io.floci.aws.services.paymentcryptography` (service, JSON handler,
  key exchange) and `...paymentcryptography.crypto` (new TR-34/AS2805/ASN.1/X.509
  helpers). Tests under the same package in `src/test` and the SDK / CLI compatibility
  suites in `compatibility-tests/`.
- Affected behavior: `ExportKey` / `ImportKey` accept two additional `KeyMaterial` forms
  that part 5 currently refuses.
- Branch topology: the result is `feature/part-6-certificates` re-based onto
  `feature/part-5-key-exchange`; the old tip is preserved as
  `feature/part-6-certificates-before-migrate`. Nothing is pushed.
- Gated by the Floci verification workflow: clean build after the rebase, scoped tests
  derived from the diff, the compatibility suites against a freshly built jar, and the
  checkstyle / docs / partition / aws-data gates.
