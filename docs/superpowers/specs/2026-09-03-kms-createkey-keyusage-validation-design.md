# KMS `CreateKey` — validate `KeyUsage` against `KeySpec`

**Issue:** [floci-io/floci#2787](https://github.com/floci-io/floci/issues/2787) (second defect)
**Branch:** `fix/kms-createkey-keyusage-validation` (off `second/main`)
**Date:** 2026-09-03

## Background

Issue #2787 has two parts:

1. **Primary bug** — `ECC_NIST_EDWARDS25519` generated a NIST P-521 key, so `Sign`
   failed. **Already fixed** on `second/main` by PR #2930 (commit `146753b09`,
   merged 2026-09-02), which gave the spec its own `KeyType.ED25519`, a real
   Ed25519 generator, key-factory dispatch, and Ed25519 / Ed25519ph
   sign+verify with message-type and digest-length validation.

2. **Second defect (this spec)** — `CreateKey` does not validate `KeyUsage`
   against `KeySpec`. `validateKeyUsageForSpec`
   (`KmsService.java:250`) enforces only the HMAC &harr; `GENERATE_VERIFY_MAC`
   pairing; every other `KeySpec` + `KeyUsage` combination returns HTTP 200.
   This diverges from real AWS KMS for six implemented key-spec families.

## Authoritative AWS matrix

From the [`CreateKey` API reference](https://docs.aws.amazon.com/kms/latest/APIReference/API_CreateKey.html)
(`KeyUsage` parameter), fetched 2026-09-03:

| KeySpec | Valid `KeyUsage` |
| --- | --- |
| `SYMMETRIC_DEFAULT` | `ENCRYPT_DECRYPT` only |
| `HMAC_224` / `HMAC_256` / `HMAC_384` / `HMAC_512` | `GENERATE_VERIFY_MAC` only |
| `RSA_2048` / `RSA_3072` / `RSA_4096` | `ENCRYPT_DECRYPT` or `SIGN_VERIFY` |
| `ECC_NIST_P256` / `ECC_NIST_P384` / `ECC_NIST_P521` | `SIGN_VERIFY` or `KEY_AGREEMENT` |
| `ECC_NIST_EDWARDS25519` | `SIGN_VERIFY` only |
| `ECC_SECG_P256K1` | `SIGN_VERIFY` only |
| `ML_DSA_44` / `ML_DSA_65` / `ML_DSA_87` | `SIGN_VERIFY` only |
| `SM2` | `ENCRYPT_DECRYPT` / `SIGN_VERIFY` / `KEY_AGREEMENT` |

## Current floci behaviour vs. target

Status codes from `KmsIntegrationTest.createKeyWithCombinations`
(`KmsIntegrationTest.java:1042-1127`). Only rows that change are listed;
all others already match.

| KeySpec | KeyUsage | Now | Target |
| --- | --- | --- | --- |
| `SYMMETRIC_DEFAULT` | `SIGN_VERIFY` | 200 | 400 |
| `SYMMETRIC_DEFAULT` | `KEY_AGREEMENT` | 200 | 400 |
| `RSA_2048` / `RSA_3072` / `RSA_4096` | `KEY_AGREEMENT` | 200 | 400 |
| `ECC_NIST_P256` / `ECC_NIST_P384` / `ECC_NIST_P521` | `ENCRYPT_DECRYPT` | 200 | 400 |
| `ECC_NIST_EDWARDS25519` | `ENCRYPT_DECRYPT` | 200 | 400 |
| `ECC_NIST_EDWARDS25519` | `KEY_AGREEMENT` | 200 | 400 |
| `ECC_SECG_P256K1` | `ENCRYPT_DECRYPT` | 200 | 400 |
| `ECC_SECG_P256K1` | `KEY_AGREEMENT` | 200 | 400 |

Unchanged and intentional:

- `ECC_NIST_P256/384/521` + `KEY_AGREEMENT` stays **200**. AWS accepts it at
  `CreateKey`. floci has no `DeriveSharedSecret` operation yet, so the key is
  currently unusable, but that is a separate feature gap, not part of #2787.
  We match AWS at the `CreateKey` boundary.
- `SM2` and `ML_DSA_*` stay **400** for every usage. Their key specs are not
  implemented (`CreateKey` rejects them earlier with
  `InvalidCustomerMasterKeySpecException`), so `validateKeyUsageForSpec` is
  never reached. No row changes.
- All `HMAC_*` rows already match.

## Design

### Approach: data-driven allow-list on `KmsKeySpec`

Add the set of permitted `KmsKeyUsage` values to each `KmsKeySpec` enum
constant, and reduce `validateKeyUsageForSpec` to a single membership check.
The compatibility truth table then lives next to the spec definitions rather
than being scattered across `switch` arms in the service.

#### `KmsKeySpec.java`

- Add field `private final Set<KmsKeyUsage> allowedKeyUsages;` and a public
  accessor `allowedKeyUsages()`.
- Extend the constructors. Most specs derive their allowed set from `KeyType`
  via a small private helper:
  - `SYMMETRIC` &rarr; `{ENCRYPT_DECRYPT}`
  - `HMAC` &rarr; `{GENERATE_VERIFY_MAC}`
  - `RSA` &rarr; `{ENCRYPT_DECRYPT, SIGN_VERIFY}`
  - `ML_DSA` &rarr; `{SIGN_VERIFY}`
  - `SM2` &rarr; `{ENCRYPT_DECRYPT, SIGN_VERIFY, KEY_AGREEMENT}`
  - `ED25519` &rarr; `{SIGN_VERIFY}`
  - `ECC` &rarr; no single answer (see below)
- The three `ECC` specs are not uniform, so they pass an explicit set:
  - `ECC_NIST_P256` / `P384` / `P521` &rarr; `{SIGN_VERIFY, KEY_AGREEMENT}`
  - `ECC_SECG_P256K1` &rarr; `{SIGN_VERIFY}`
- Use `EnumSet` (or `Set.of`) and keep the field unmodifiable.
- `KmsKeyUsage` is in the same `model` package — no import cycle
  (`KmsKeySpec` already references `KmsKeyUsage` indirectly through
  `Algorithm`, and `KmsKeyUsage` does not reference `KmsKeySpec`).

#### `KmsService.validateKeyUsageForSpec`

Replace the body with:

```java
private static void validateKeyUsageForSpec(KmsKeyUsage keyUsage, KmsKeySpec spec) {
    if (!spec.allowedKeyUsages().contains(keyUsage)) {
        throw new AwsException("ValidationException",
                "KeyUsage " + keyUsage + " is not compatible with KeySpec " + spec + ".", 400);
    }
}
```

The existing HMAC-specific messages
(`"... HMAC key specs require KeyUsage GENERATE_VERIFY_MAC."` and
`"KeyUsage GENERATE_VERIFY_MAC requires an HMAC KeySpec ..."`) are replaced by
the single uniform message. If review prefers to keep the more descriptive
HMAC text, add one `if (isHmac(spec))` special case before the generic check;
default is the uniform message for simplicity.

Sole caller is `createKey` (`KmsService.java:148`); no other code path is
affected. Cryptographic operations already guard usage independently
(`Encrypt`/`Decrypt` at `KmsService.java:646` and `:707`,
MAC at `:1066`, Ed25519 sign/verify at `:1204`), so tightening `CreateKey`
introduces no downstream regression.

### Error type — provisional, flag in PR

AWS's documented `CreateKey` error for an unsupported parameter combination is
`UnsupportedOperationException` (HTTP 400). This spec uses
**`ValidationException`** (HTTP 400) instead, to stay consistent with the
existing HMAC check in the same method and with the `GENERATE_VERIFY_MAC`
rejection already asserted for `ECC_NIST_EDWARDS25519` in the test table.

The exact `__type` and message real AWS returns for e.g.
`RSA_2048 + KEY_AGREEMENT` at `CreateKey` has **not** been verified against a
live account. The PR description must call this out; a maintainer may request
an `ap-northeast-1` probe and a switch to `UnsupportedOperationException`.
Changing the thrown type later is a one-line edit plus a test-string update.

## Testing

### `KmsIntegrationTest.java`

1. **`createKeyWithCombinations` CSV** — flip the 8 distinct rows above
   (12 CSV lines: 2 symmetric + 3 RSA + 3 P-curve + 2 Edwards + 2 secg-k1)
   from `200` to `400`.
2. **New `@ParameterizedTest`** — `createKeyRejectsIncompatibleKeyUsage`,
   asserting HTTP 400, `__type == "ValidationException"`, and the exact
   message for a representative slice:
   - `ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT`
   - `ECC_NIST_EDWARDS25519, KEY_AGREEMENT`
   - `RSA_2048, KEY_AGREEMENT`
   - `ECC_NIST_P256, ENCRYPT_DECRYPT`
   - `ECC_SECG_P256K1, KEY_AGREEMENT`
   - `SYMMETRIC_DEFAULT, SIGN_VERIFY`
3. **Positive guard** — one assertion that
   `ECC_NIST_P256 + KEY_AGREEMENT` still returns 200 (documents the
   deliberate AWS-matching exception).
4. `createKeyWithAllImplementedCombinations` — no change (already only
   valid combos).

### `compatibility-tests/sdk-test-python/tests/test_kms.py`

Add `test_create_key_rejects_incompatible_key_usage`: assert
`kms_client.create_key(KeySpec="ECC_NIST_EDWARDS25519", KeyUsage="ENCRYPT_DECRYPT")`
raises `botocore.exceptions.ClientError` with error code `ValidationException`.

### Commands

- `./mvnw test -Dtest=KmsIntegrationTest`
- Full `./mvnw test` before PR.

## Files touched

| File | Change |
| --- | --- |
| `src/main/java/io/github/hectorvent/floci/services/kms/model/KmsKeySpec.java` | Add `allowedKeyUsages` field + accessor; populate per constant |
| `src/main/java/io/github/hectorvent/floci/services/kms/KmsService.java` | Rewrite `validateKeyUsageForSpec` to a membership check |
| `src/test/java/io/github/hectorvent/floci/services/kms/KmsIntegrationTest.java` | Flip 8 CSV rows; add rejection + positive-guard tests |
| `compatibility-tests/sdk-test-python/tests/test_kms.py` | Add one rejection test |

No production doc updates: `docs/services/kms.md` does not document key-usage
compatibility rules.

## Out of scope

- `DeriveSharedSecret` operation for `KEY_AGREEMENT` keys (separate feature).
- `SM2` / `ML_DSA_*` key-spec implementation.
- Retry-classification of `InternalFailure` on crypto ops (noted in #2787
  comments; not `CreateKey`-related).
- Switching the error `__type` to `UnsupportedOperationException` pending a
  live AWS probe.

## Commit

```
fix(kms): validate KeyUsage against KeySpec on CreateKey (#2787)
```
