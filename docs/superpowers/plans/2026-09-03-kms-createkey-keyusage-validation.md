# KMS CreateKey KeyUsage Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `CreateKey` reject `KeyUsage` values that real AWS KMS rejects for the given `KeySpec`.

**Architecture:** Add a derived `allowedKeyUsages()` method to the `KmsKeySpec` enum that returns the AWS-valid `KmsKeyUsage` set for each spec (computed from `KeyType`, with `ECC_SECG_P256K1` special-cased). Reduce `KmsService.validateKeyUsageForSpec` to a single membership check that throws `ValidationException` on a miss. Update the existing `CreateKey` combination test table and add focused rejection tests.

**Tech Stack:** Java 21, Quarkus, JUnit 5 (`@ParameterizedTest` / `@CsvSource`), REST Assured, Maven wrapper (`./mvnw`). Python compat suite: pytest + boto3.

**Spec:** `docs/superpowers/specs/2026-09-03-kms-createkey-keyusage-validation-design.md`

## Global Constraints

- Branch: `fix/kms-createkey-keyusage-validation`, based on `second/main`. No git worktree.
- English only for all code, comments, commit messages, PR text (repo convention).
- Conventional Commits. Final feature commit subject: `fix(kms): validate KeyUsage against KeySpec on CreateKey (#2787)`.
- Error type is **provisional**: throw `AwsException("ValidationException", <msg>, 400)`. Exact AWS `__type`/message for this `CreateKey` path is unverified against a live account — the PR description must say so.
- Do not change behaviour for `SM2` / `ML_DSA_*` (their key specs are unimplemented; `CreateKey` rejects them before `validateKeyUsageForSpec` runs).
- `ECC_NIST_P256/P384/P521` + `KEY_AGREEMENT` must keep returning **200** (AWS accepts it at `CreateKey`; the missing `DeriveSharedSecret` op is out of scope).

---

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `src/main/java/io/github/hectorvent/floci/services/kms/model/KmsKeySpec.java` | Key-spec enum; now also the source of truth for valid key usages per spec | Add `allowedKeyUsages()` method (no constructor changes) |
| `src/main/java/io/github/hectorvent/floci/services/kms/KmsService.java` | Service logic; `validateKeyUsageForSpec` is the single `CreateKey` gate | Rewrite `validateKeyUsageForSpec` body to a membership check |
| `src/test/java/io/github/hectorvent/floci/services/kms/KmsIntegrationTest.java` | Integration coverage for `CreateKey` | Flip 12 CSV rows `200`→`400`; add rejection + positive-guard tests |
| `compatibility-tests/sdk-test-python/tests/test_kms.py` | boto3 cross-SDK coverage | Add one rejection test |

---

## Task 1: `KmsKeySpec.allowedKeyUsages()` + `CreateKey` gate + Java integration tests

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/kms/model/KmsKeySpec.java`
- Modify: `src/main/java/io/github/hectorvent/floci/services/kms/KmsService.java` (`validateKeyUsageForSpec`, currently at `:250`; sole caller `createKey` at `:148`)
- Test: `src/test/java/io/github/hectorvent/floci/services/kms/KmsIntegrationTest.java`

**Interfaces:**
- Produces: `Set<KmsKeyUsage> KmsKeySpec.allowedKeyUsages()` — the AWS-valid usage set for the receiver spec. Never empty for implemented specs.
- Consumes: existing `KmsKeySpec.getKeyType()` returning `KmsKeySpec.KeyType` (`RSA, ECC, ED25519, SYMMETRIC, HMAC, ML_DSA, SM2`); existing enum `KmsKeyUsage` (`SIGN_VERIFY, ENCRYPT_DECRYPT, GENERATE_VERIFY_MAC, KEY_AGREEMENT`) in the same `model` package.

---

- [ ] **Step 1: Update the existing combination table (failing test)**

In `KmsIntegrationTest.java`, method `createKeyWithCombinations`, `@CsvSource` block (currently `KmsIntegrationTest.java:1042-1127`). Change exactly these 12 rows from `200` to `400`, leaving every other row untouched:

```
            "SYMMETRIC_DEFAULT, SIGN_VERIFY, 400",
            ...
            "SYMMETRIC_DEFAULT, KEY_AGREEMENT, 400",

            "RSA_2048, KEY_AGREEMENT, 400",
            ...
            "RSA_3072, KEY_AGREEMENT, 400",
            ...
            "RSA_4096, KEY_AGREEMENT, 400",

            "ECC_NIST_P256, ENCRYPT_DECRYPT, 400",
            ...
            "ECC_NIST_P384, ENCRYPT_DECRYPT, 400",
            ...
            "ECC_NIST_P521, ENCRYPT_DECRYPT, 400",
            ...
            "ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT, 400",
            "ECC_NIST_EDWARDS25519, SIGN_VERIFY, 200",
            "ECC_NIST_EDWARDS25519, GENERATE_VERIFY_MAC, 400",
            "ECC_NIST_EDWARDS25519, KEY_AGREEMENT, 400",

            "ECC_SECG_P256K1, ENCRYPT_DECRYPT, 400",
            "ECC_SECG_P256K1, SIGN_VERIFY, 200",
            "ECC_SECG_P256K1, GENERATE_VERIFY_MAC, 400",
            "ECC_SECG_P256K1, KEY_AGREEMENT, 400",
```

Do **not** change: `ECC_NIST_P256/P384/P521, KEY_AGREEMENT, 200`; any `ENCRYPT_DECRYPT, 200` for `SYMMETRIC_DEFAULT`/`RSA_*`; any `SIGN_VERIFY, 200` for `RSA_*`; all `HMAC_*`, `SM2`, `ML_DSA_*` rows.

- [ ] **Step 2: Run the table test, verify it fails**

Run: `./mvnw test -Dtest=KmsIntegrationTest#createKeyWithCombinations`
Expected: FAIL — the 12 flipped rows still return `200` (assertion expected `400`).

- [ ] **Step 3: Add `allowedKeyUsages()` to `KmsKeySpec`**

In `KmsKeySpec.java`, add this method next to `curveName()` (before the `KeyType` enum declaration). `EnumSet` is already imported; `KmsKeyUsage` needs no import (same package).

```java
    /**
     * The KeyUsage values AWS KMS accepts for this key spec at CreateKey.
     *
     * <p>Source: CreateKey API reference, KeyUsage parameter. NIST-standard ECC
     * pairs allow SIGN_VERIFY or KEY_AGREEMENT; ECC_SECG_P256K1 and Edwards25519
     * are signing only; RSA allows ENCRYPT_DECRYPT or SIGN_VERIFY; symmetric is
     * ENCRYPT_DECRYPT only; HMAC is GENERATE_VERIFY_MAC only.
     */
    public java.util.Set<KmsKeyUsage> allowedKeyUsages() {
        return switch (keyType) {
            case SYMMETRIC -> EnumSet.of(KmsKeyUsage.ENCRYPT_DECRYPT);
            case HMAC -> EnumSet.of(KmsKeyUsage.GENERATE_VERIFY_MAC);
            case RSA -> EnumSet.of(KmsKeyUsage.ENCRYPT_DECRYPT, KmsKeyUsage.SIGN_VERIFY);
            case ED25519, ML_DSA -> EnumSet.of(KmsKeyUsage.SIGN_VERIFY);
            case SM2 -> EnumSet.of(KmsKeyUsage.ENCRYPT_DECRYPT, KmsKeyUsage.SIGN_VERIFY, KmsKeyUsage.KEY_AGREEMENT);
            case ECC -> this == ECC_SECG_P256K1
                    ? EnumSet.of(KmsKeyUsage.SIGN_VERIFY)
                    : EnumSet.of(KmsKeyUsage.SIGN_VERIFY, KmsKeyUsage.KEY_AGREEMENT);
        };
    }
```

(If the file already imports `java.util.Set`, use the bare `Set` return type instead of the fully-qualified name and skip adding an import.)

- [ ] **Step 4: Rewrite `validateKeyUsageForSpec` in `KmsService`**

Replace the whole method body (the two `if` blocks for HMAC) with:

```java
    private static void validateKeyUsageForSpec(KmsKeyUsage keyUsage, KmsKeySpec spec) {
        if (!spec.allowedKeyUsages().contains(keyUsage)) {
            throw new AwsException("ValidationException",
                    "KeyUsage " + keyUsage + " is not compatible with KeySpec " + spec + ".", 400);
        }
    }
```

Leave the call site at `createKey` (`KmsService.java:148`) unchanged. If `isHmac(...)` becomes unused after this edit, leave it — it is still used elsewhere (`KmsService.java:1066`).

- [ ] **Step 5: Run the table test, verify it passes**

Run: `./mvnw test -Dtest=KmsIntegrationTest#createKeyWithCombinations`
Expected: PASS — all rows, including the 12 flipped ones and the unchanged `ECC_NIST_P*` + `KEY_AGREEMENT` `200` rows.

- [ ] **Step 6: Add a focused rejection test + positive guard**

Append two methods to `KmsIntegrationTest` (after `createKeyWithCombinations`). Imports needed are already present (`ParameterizedTest`, `CsvSource`, `Test`, `given`, `equalTo`).

```java
    @ParameterizedTest
    @CsvSource({
            "SYMMETRIC_DEFAULT, SIGN_VERIFY",
            "RSA_2048, KEY_AGREEMENT",
            "ECC_NIST_P256, ENCRYPT_DECRYPT",
            "ECC_NIST_EDWARDS25519, ENCRYPT_DECRYPT",
            "ECC_NIST_EDWARDS25519, KEY_AGREEMENT",
            "ECC_SECG_P256K1, KEY_AGREEMENT"
    })
    void createKeyRejectsIncompatibleKeyUsage(String keySpec, String keyUsage) {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"%s\",\"KeySpec\":\"%s\"}".formatted(keyUsage, keySpec))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(
                        "KeyUsage " + keyUsage + " is not compatible with KeySpec " + keySpec + "."));
    }

    /**
     * AWS accepts KEY_AGREEMENT for NIST-standard ECC key specs at CreateKey, even though
     * Floci has no DeriveSharedSecret operation yet. Matching AWS at the CreateKey boundary
     * is deliberate; this guards against an over-broad tightening.
     */
    @Test
    void createKeyAllowsKeyAgreementForNistEccSpecs() {
        given()
                .header("X-Amz-Target", "TrentService.CreateKey")
                .contentType(KMS_CONTENT_TYPE)
                .body("{\"KeyUsage\":\"KEY_AGREEMENT\",\"KeySpec\":\"ECC_NIST_P256\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("KeyMetadata.KeyUsage", equalTo("KEY_AGREEMENT"))
                .body("KeyMetadata.KeySpec", equalTo("ECC_NIST_P256"));
    }
```

If review wants the descriptive HMAC message kept, that is a follow-up tweak — the generic message is intentional here for one uniform error string.

- [ ] **Step 7: Run the full KMS integration suite, verify green**

Run: `./mvnw test -Dtest=KmsIntegrationTest`
Expected: PASS, no regressions. Pay attention that `createKeyWithAllImplementedCombinations` (valid-combos-only table) still passes untouched.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/hectorvent/floci/services/kms/model/KmsKeySpec.java \
        src/main/java/io/github/hectorvent/floci/services/kms/KmsService.java \
        src/test/java/io/github/hectorvent/floci/services/kms/KmsIntegrationTest.java
git commit -m "fix(kms): validate KeyUsage against KeySpec on CreateKey (#2787)"
```

---

## Task 2: boto3 compatibility test

**Files:**
- Modify: `compatibility-tests/sdk-test-python/tests/test_kms.py` (class `TestKMSKey`, currently at `:10`)

**Interfaces:**
- Consumes: `kms_client` pytest fixture (already used throughout the file); `ClientError` (already imported at top of file).

---

- [ ] **Step 1: Add the rejection test**

Add this method inside `class TestKMSKey` (e.g. after `test_schedule_key_deletion`):

```python
    def test_create_key_rejects_incompatible_key_usage(self, kms_client):
        """CreateKey rejects a KeyUsage that AWS KMS does not allow for the KeySpec."""
        with pytest.raises(ClientError) as excinfo:
            kms_client.create_key(
                KeySpec="ECC_NIST_EDWARDS25519",
                KeyUsage="ENCRYPT_DECRYPT",
            )
        assert excinfo.value.response["Error"]["Code"] == "ValidationException"
```

- [ ] **Step 2: Run the compat test**

The Python suite runs against a live Floci container. Run whichever the repo's `compatibility-tests/sdk-test-python/README` documents; typically:

```bash
cd compatibility-tests/sdk-test-python
pytest tests/test_kms.py::TestKMSKey::test_create_key_rejects_incompatible_key_usage -v
```

Expected: PASS. If no container runtime is available locally, note that in the PR and rely on CI for this file.

- [ ] **Step 3: Commit**

```bash
git add compatibility-tests/sdk-test-python/tests/test_kms.py
git commit -m "test(kms): boto3 coverage for CreateKey KeyUsage rejection (#2787)"
```

---

## Task 3: PR

- [ ] **Step 1: Push and open the PR**

```bash
git push -u origin fix/kms-createkey-keyusage-validation
gh pr create --repo floci-io/floci --base main \
  --title "fix(kms): validate KeyUsage against KeySpec on CreateKey (#2787)" \
  --body "<see body below>"
```

- [ ] **Step 2: PR body must include**
  - What: `CreateKey` now rejects `KeyUsage`/`KeySpec` combinations that real AWS KMS rejects (second defect in #2787; the P-521 key bug was fixed in #2930).
  - The AWS matrix table from the spec.
  - **Caveat:** error `__type` is `ValidationException`, chosen for consistency with the existing in-method check; the exact type/message real AWS returns for this `CreateKey` path is **not** verified against a live account. Happy to switch to `UnsupportedOperationException` if a maintainer confirms via `ap-northeast-1`.
  - Note that `ECC_NIST_P*` + `KEY_AGREEMENT` deliberately stays `200` (matches AWS `CreateKey`; `DeriveSharedSecret` is a separate gap).
  - Checklist: `./mvnw test` passes locally; integration + compat tests added.
  - Trailer: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`

---

## Self-Review

**1. Spec coverage:**
- AWS matrix → Task 1 Step 3 (`allowedKeyUsages()`) encodes every row. ✅
- 12 flipped status rows → Task 1 Step 1 lists them explicitly. ✅
- `ValidationException` provisional + PR flag → Global Constraints + Task 1 Step 4 + Task 3 Step 2. ✅
- Rewrite `validateKeyUsageForSpec`, single caller → Task 1 Step 4. ✅
- New rejection `@ParameterizedTest` + positive guard → Task 1 Step 6. ✅
- `createKeyWithAllImplementedCombinations` untouched → Task 1 Step 7 note. ✅
- Python compat test → Task 2. ✅
- No `docs/services/kms.md` change → spec says none needed; no task, correct. ✅
- Out of scope (DeriveSharedSecret, SM2/ML_DSA impl, InternalFailure retry) → not in any task, correct. ✅

**2. Placeholder scan:** No TBD/TODO; all code blocks are literal; test bodies are complete. The only conditional ("if file imports `java.util.Set`") gives both concrete branches. ✅

**3. Type consistency:** `allowedKeyUsages()` returns `Set<KmsKeyUsage>`, consumed via `.contains(keyUsage)` where `keyUsage` is `KmsKeyUsage` — matches. `KeyType` cases (`SYMMETRIC, HMAC, RSA, ED25519, ML_DSA, SM2, ECC`) are exactly the enum's 7 values, so the `switch` is exhaustive with no `default`. Error message string in Task 1 Step 4 matches the assertion in Step 6 verbatim (`"KeyUsage " + X + " is not compatible with KeySpec " + Y + "."`). ✅
