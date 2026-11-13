# Design

## Context

See proposal.md for motivation. The working facts that shape the approach:

- `feature/part-6-certificates` = 6 commits on base `eaa553cf7` (pre-rename
  `io.github.hectorvent.floci`). Its tip is `18323d5a8`.
- `feature/part-5-key-exchange` = 18 commits on base `9f77c736e` (post-rename
  `io.floci.aws`), tip `3443ec139` when the migration started and `0709b00f8` after the
  key exchange's second review. It already contains the package rename and a
  reviewed refactor of the key exchange: `Certificates` + ACM `CertificateGenerator`
  for minting, `PaymentCryptographyKeyPairs`, `PaymentCryptographyKeyRules`,
  `PaymentCryptographyKeyStrength`, and a `HexFormat`-based hex discipline.
- Part 6's first five commits are superseded one-for-one by part 5's reviewed versions
  of the same work. Only `18323d5a8` (certificates, TR-34, AS2805) is unique.
- The migrated commit's new classes: `Tr34` (self-contained, `java.*` only),
  `PaymentCryptographyAs2805Cryptogram` (depends on part 5's existing
  `PaymentCryptographyCrypto.fromHex` and `PaymentCryptographyKeyAlgorithm`),
  `PaymentCryptographyAs2805Variant`, plus a `Tr34` class. Part 6's original `Tr34` is
  built on hand-rolled `Asn1`, `X509`, and `Hex` helpers; per a maintainer decision the
  migrated `Tr34` is implemented on BouncyCastle's CMS API (already a Floci dependency,
  used by part 5's `Certificates`) instead, so the hand-rolled helpers are not ported.
- Part 5's `PaymentCryptographyKeyExchange` already documents the TR-34 / AS2805 forms
  in its class javadoc but does not implement them; part 5's `Service` and
  `JsonHandler` already serve `GetPublicKeyCertificate`, `GetParametersForImport`, and
  `GetParametersForExport`.
- The vendored `payment-cryptography` request shapes already name `Tr34KeyBlock` and
  `As2805KeyCryptogram`, so no vendored-data regeneration is expected.

## Goals / Non-Goals

**Goals:**
- Land the TR-34 key block and AS2805 key cryptogram export/import forms on the tip of
  `feature/part-5-key-exchange`, under `io.floci.aws`, built and green.
- Reuse part 5's existing shared helpers; introduce no parallel duplicate of what part 5
  already provides (certificate minting, key-pair generation, key rules, strength).
- Keep the pre-existing key-exchange forms (TR-31, key cryptogram, ECDH, public-key
  import) behaviorally unchanged.
- Preserve the old tip for rollback and keep the commit message / sign-off of
  `18323d5a8`.

**Non-Goals:**
- Re-doing or re-reviewing part 5's already-merged work.
- Pushing anything, opening a PR, or merging into `main`.
- Regenerating vendored AWS data (not expected to be needed; verify with the gate).
- Any change to wire shapes, endpoints, or configuration.

## Decisions

### Decision 1: Migrate only `18323d5a8`, do not rebase all six commits

Take part 6's unique commit and re-land it on part 5's tip; drop the first five commits.

- **Why:** the first five commits duplicate work part 5 has already redone in a
  reviewed, refactored form. A straight `git rebase --onto part-5` of all six would
  replay superseded code and produce a large, noisy conflict set that resolves "in favor
  of part 6" for the wrong reason. Migrating only the unique commit keeps the diff to
  exactly the new behavior (TR-34 / AS2805 / certificates).
- **Alternatives considered:**
  - Full rebase of all six commits: rejected (replays superseded code, large conflicts).
  - `tools/rename/rebase_across_rename.sh <branch>`: rejected. That script applies the
    package rename and replays the branch onto `main`, collapsing the commits. Here the
    rename is already present in part 5, and the target is the part 5 tip, not `main`;
    the script's target and squash behavior do not match what is wanted.
  - Manual re-authoring from scratch: rejected (loses the original commit's exact
    behavior and its sign-off).

### Decision 2: Cherry-pick with conflict resolution in favor of part 5's structure

Create a new branch at the part 5 tip, save the old tip as
`feature/part-6-certificates-before-migrate`, then `git cherry-pick 18323d5a8` and
resolve every conflict against part 5's refactored code.

- **Why:** cherry-pick preserves the commit message and sign-off and surfaces each
  conflict explicitly. Resolution rule: where part 5 already provides the behavior
  (certificate minting via `Certificates`/`CertificateGenerator`, key-pair generation,
  key rules, strength checks, `GetParametersFor*` / `GetPublicKeyCertificate` handlers),
  keep part 5's implementation and fold part 6's additions around it. Where part 6 adds
  genuinely new code (TR-34 / AS2805 forms and their crypto), land it under
  `io.floci.aws`.
- **Adaptation detail:** part 6's key exchange uses a `crypto.Hex` helper in ~10 places
  and a `crypto.X509` minting path; part 5 uses `java.util.HexFormat` and
  `Certificates`/`CertificateGenerator`. During resolution, prefer part 5's helpers and
  adapt the migrated call sites to them rather than dragging in a second hex/cert path.
  `Tr34` is re-implemented on BouncyCastle's CMS API (`CMSSignedDataGenerator` /
  `CMSEnvelopedDataGenerator` / `CMSSignedData` + `JceKeyTransRecipientInfo`) keeping the
  same public build/recover surface and the same TR-34 2012 wire structure (SignedData
  with content-type, digest, KDH-certificate, and nonce signed attributes wrapping
  EnvelopedData with RSA-OAEP key transport and 3DES-CBC); the hand-rolled `Asn1`,
  `X509`, and `Hex` helpers are dropped.

### Decision 3: Reconcile, do not re-add, the shared service/handler surface

Part 5 already serves `GetPublicKeyCertificate`, `GetParametersForImport`, and
`GetParametersForExport`. The migrated commit's edits to `PaymentCryptographyService`
and `PaymentCryptographyJsonHandler` are reconciled against the existing part 5 handlers
(merge the TR-34 / AS2805 handling into the existing methods) rather than adding
duplicate methods.

- **Why:** avoids two handlers for the same action and keeps the JSON 1.1 dispatch in
  one place, per the repo's "keep controllers thin / reuse existing patterns" rules.

### Decision 4: The TR-34 header is written by the TR-34 code, on part 5's readers

Part 5's "one helper for each" refactor removed the TR-31 header builder and the block
length helper, on the grounds that each concern has one home. The TR-34 header therefore
belongs to the TR-34 code: the header is written there, with the length placeholder the
reference writes, because the field is ignored in a TR-34 block. Reading it back uses
part 5's TR-31 readers (`usageOf`, `modeOf`, `exportabilityOf`, `algorithmInHeader`), which
read the positions the standard fixes and work on a bare header, so the migrated code adds
no second reader of its own.

- **Why:** duplicating part 5's readers would undo the refactor; adding the header builder
  back to `PaymentCryptographyTr31` would put a concern there that belongs to the TR-34
  code.
- **Alternatives considered:** keeping the migrated `headerField` and
  `algorithmOf(String, int)` helpers (rejected: they duplicate part 5's readers), and
  re-adding `headerFor` to `PaymentCryptographyTr31` (rejected: part 5 removed it on
  purpose).

### Decision 5: Verification follows the Floci verification workflow

Per the `floci-verification` skill, after the rebase: run a clean build (the rebase
invalidates the incremental compiler), derive the scoped test set from the diff with
`bin/scoped-tests.sh` against the part 5 base, run the compatibility suites (SDK and CLI)
against a freshly built jar on a dedicated port, run the model-coverage report for
`payment-cryptography`, and run the gates (checkstyle, docs-check, partition-check,
aws-data-check, iam-data-verify over the changed paths). Treat local green as evidence
for the developer, not the reviewer.

## Risks / Trade-offs

- [Silent behavior drift when folding part 6 code onto part 5's refactored helpers] →
  Mitigate with the scoped test set plus the TR-34 / AS2805 round-trip tests and the
  compatibility suites, which exercise the wire contract end to end.
- [A straight cherry-pick resolving a conflict "in favor of part 6" reintroduces
  superseded code] → Mitigate with the explicit resolution rule (Decision 2): part 5's
  shared helpers win; only genuinely new TR-34 / AS2805 code is landed.
- [Re-implementing TR-34 on BouncyCastle's CMS API could produce a structurally
  different block than part 6's hand-rolled one] → Mitigate by keeping the same TR-34
  2012 ASN.1 structure (same OIDs, same signed-attribute set, PKCS#7 padding) and
  proving symmetry with the ported `Tr34RoundTripTest` (build then recover) plus the
  key-exchange round-trip tests through the wire.
- [Stale build state after the rebase gives a false green/red] → Mitigate by running
  `./mvnw clean` and rebuilding before trusting any result, and by checking the jar
  timestamp before the compatibility suite.
- [A port conflict on the compatibility suite answers with an old emulator] → Mitigate
  by killing any server on the chosen port by PID before the suite and pointing it at
  the freshly built jar.
- [Vendored shapes or docs drift after the change] → Mitigate with `aws-data-check` and
  `docs-check`; update `docs/services/payment-cryptography.md` if the served forms
  change the documented behavior.
- [The old part 6 tip is needed later] → Mitigate by tagging the old tip as
  `feature/part-6-certificates-before-migrate` before rewriting the branch.

## Migration Plan

1. Tag the current tip: `git branch feature/part-6-certificates-before-migrate 18323d5a8`.
2. Create the migration branch at part 5 tip:
   `git checkout -B feature/part-6-certificates-migrated 3443ec139`, rebased onto part 5's
   later tip when part 5 moved.
3. `git cherry-pick 18323d5a8`; resolve conflicts per Decision 2.
4. `./mvnw clean`, then build; run scoped tests, compatibility suites, and gates
   (Decision 4).
5. On green, move the branch: the migrated branch becomes `feature/part-6-certificates`
   (old tip preserved by the tag from step 1). Nothing is pushed.

## Open Questions

- None. The scope (migrate the unique commit onto part 5), the resolution rule, and the
  verification workflow are fixed; remaining choices (e.g. `Hex` helper vs `HexFormat`
  at individual call sites) are local to the cherry-pick and do not change the specs,
  the approach, or the task breakdown.
