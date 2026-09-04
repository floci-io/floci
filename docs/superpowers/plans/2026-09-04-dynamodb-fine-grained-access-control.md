# DynamoDB Fine-Grained Access Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DynamoDB item-level (fine-grained) IAM access control work in the floci AWS emulator. A policy scoped with `ForAllValues:StringLike` on `dynamodb:LeadingKeys` must allow `GetItem` on `USER_alice` and deny `GetItem` on `USER_bob` for the same scoped session (issue [floci-io/floci#2926](https://github.com/floci-io/floci/issues/2926)).

**Architecture:** Three layered changes.
1. `IamPolicyEvaluator`'s condition context becomes multi-valued (`Map<String,String>` → `Map<String,List<String>>`) end to end, and gains the `ForAllValues:` / `ForAnyValue:` set-operator quantifiers with AWS empty-set semantics.
2. Two new static helpers in `services.dynamodb` — `DynamoDbKeyConditionParser` (partition-key equality value out of a `KeyConditionExpression`, reusing the existing `ExpressionEvaluator`) and `DynamoDbConditionKeys` (extracts `LeadingKeys` / `Attributes` / `Select` from a request body plus a `TableDefinition`).
3. `IamConditionContextResolver` gains a `dynamodb` branch that reads the already-buffered JSON body (`floci.bufferedJsonBody`, populated by `ResourceArnBuilder` earlier in the same filter pass), resolves the table's key schema through a lazily-injected `DynamoDbService`, and emits the three condition keys.

**Tech Stack:** Java 25 (`maven.compiler.release=25`), Quarkus 3.37.4, Maven (wrapper), Jackson `JsonNode`/`ObjectMapper`, CDI (`@ApplicationScoped`, `jakarta.enterprise.inject.Instance`), JAX-RS `ContainerRequestFilter`. Tests: **JUnit 5** (`org.junit.jupiter.api.Test`), **Mockito** (`quarkus-junit-mockito`), **RestAssured** + **Hamcrest** for HTTP tests, `@QuarkusTest` / `@TestProfile` for in-container tests. AssertJ is on the classpath but the IAM/DynamoDB tests in scope use plain JUnit 5 `Assertions.*` — match that.

**Spec:** docs/superpowers/specs/2026-09-04-dynamodb-fine-grained-access-control-design.md

## Global Constraints

- **floci is English-only**: all code comments, Javadoc, commit messages, PR text, test names and log messages in English. (This overrides any global Vietnamese-comment preference.)
- **Do NOT use git worktree.** The branch `feat/2926-dynamodb-fine-grained-access-control` already exists and is based on `second/main`. Work on it directly.
- **No new dependencies** without asking. Everything needed (Jackson, Mockito, RestAssured, Hamcrest, JUnit 5) is already in `pom.xml`.
- **Commit trailers:** every `git commit` in this plan MUST end its message with exactly:

  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  ```

  (The executing session's harness mandates this and it overrides repo history — `second/main` itself carries only human `Co-authored-by:` lines, but that does not apply here.) Commit subjects follow conventional commits (`fix(dynamodb): …`, `feat(iam): …`), which the history uses consistently. Do not add a `🤖 Generated with Claude Code` line to commits (that line is for PR descriptions only).
- **Version floors / build:** `maven.compiler.release` = **25** (native profile drops to 24). Quarkus platform **3.37.4**. Surefire 3.5.6 with `-Xmx6g`.
- **Build commands** (Windows host; both shells available):
  - Git Bash: `./mvnw test -Dtest=IamPolicyEvaluatorTest`
  - PowerShell / cmd: `.\mvnw.cmd test -Dtest=IamPolicyEvaluatorTest`
  - Compile only (fast feedback for signature changes): `./mvnw -q -DskipTests compile` and `./mvnw -q -DskipTests test-compile`
  - Several tests at once: `./mvnw test -Dtest='IamPolicyEvaluatorTest,DynamoDbConditionKeysTest,IamConditionContextResolverTest,IamEnforcementFilterTest'`
  - Full suite (slow, `@QuarkusTest`-heavy): `./mvnw test`
  - There is **no** faster module/profile: this is a single-module Maven project.
- Do not `git add` this plan file; leave it untracked.

---

## File Structure

### Production — modified

| File | Responsibility after the change |
|---|---|
| `src/main/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluator.java` | Condition context is `Map<String,List<String>>` throughout; parses `ForAllValues:` / `ForAnyValue:` prefixes and evaluates them with AWS empty-set semantics. |
| `src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java` | Returns `Map<String,List<String>>`; S3 branch wraps values in singleton lists; new `dynamodb` branch populates `dynamodb:LeadingKeys` / `dynamodb:Attributes` / `dynamodb:Select`. |
| `src/main/java/io/github/hectorvent/floci/core/common/IamEnforcementFilter.java` | Local `conditionContext` variable is `Map<String,List<String>>`; `aws:PrincipalArn` is put as a singleton list. |
| `src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java` | `extractContextEntries` returns `Map<String,List<String>>` and reads **every** `ContextKeyValues.member.M`, not only `.member.1`. |
| `docs/services/iam.md` | Documents the two set operators and the three new DynamoDB condition keys, plus the fail-closed consequence. |

### Production — new

| File | Responsibility |
|---|---|
| `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParser.java` | One job: given a `KeyConditionExpression` + `ExpressionAttributeNames` + `ExpressionAttributeValues` + the partition-key name, return the scalar value the partition key is pinned to by an equality condition, or `null`. |
| `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeys.java` | One job: given the IAM action, the parsed request body, and the table's `TableDefinition`, return the `dynamodb:LeadingKeys` values, the `dynamodb:Attributes` names, and the `dynamodb:Select` value. |

### Tests — modified

| File | What is added |
|---|---|
| `src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java` | Multi-valued context regression + all set-operator unit cases. |
| `src/test/java/io/github/hectorvent/floci/services/iam/IamEnforcementIntegrationTest.java` | The issue's `LeadingKeys` scenario at evaluator level + `Action*/Resource*` control. |
| `src/test/java/io/github/hectorvent/floci/core/common/IamConditionContextResolverTest.java` | `dynamodb` branch cases; S3 regression under the new return type. |
| `src/test/java/io/github/hectorvent/floci/core/common/IamEnforcementFilterTest.java` | The S3 condition-context pass-through test updated to the multi-valued map. |
| `src/test/java/io/github/hectorvent/floci/services/iam/IamIntegrationTest.java` | SimulatePrincipalPolicy with two `ContextKeyValues.member.N` values. |

### Tests — new

| File | Responsibility |
|---|---|
| `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeysTest.java` | Per-action extraction unit tests with a stub `TableDefinition`. |
| `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParserTest.java` | `KeyConditionExpression` variants. |
| `src/test/java/io/github/hectorvent/floci/services/iam/DynamoDbFgacEnforcementIntegrationTest.java` | End-to-end HTTP: enforcement on, scoped session, `GetItem USER_alice` → 200, `GetItem USER_bob` → 403 `AccessDeniedException`. |

---

## Tasks

### Task 1: Make the condition context multi-valued in `IamPolicyEvaluator`

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluator.java` (lines 78-82, 139-150, 185-186, 211-221, 223-248, 292-353)
- Modify: `src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java` (whole file — return types only in this task)
- Modify: `src/main/java/io/github/hectorvent/floci/core/common/IamEnforcementFilter.java` (lines 174, 185-188)
- Modify: `src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java` (line 1177 call site only; the method body changes in Task 3)
- Modify: `src/test/java/io/github/hectorvent/floci/core/common/IamEnforcementFilterTest.java` (line 259)
- Test: `src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java`

**Interfaces:**

*Consumes:*
- `io.github.hectorvent.floci.services.iam.model.CallerContext` — `CallerContext.of(List<String>)`, `withScpLevels(List<List<String>>)`, `identityPolicies()`, `sessionPolicyDocument()`, `boundaryPolicyDocument()`, `scpLevels()`
- `io.github.hectorvent.floci.services.iam.model.PolicyStatement` — `Map<String, Map<String, List<String>>> getConditions()`

*Produces (changed signatures):*
```java
public Decision evaluate(CallerContext caller, List<String> resourcePolicies, String action,
                         String resource, Map<String, List<String>> conditionCtx);
public Decision simulateCustomPolicy(List<String> policyDocuments, String action,
                                     String resource, Map<String, List<String>> conditionCtx);
public SimulationDecision simulatePrincipalPolicy(CallerContext caller, String action,
                                                  String resource, Map<String, List<String>> conditionCtx);
public Map<String, List<String>> IamConditionContextResolver.resolve(
        String credentialScope, String action, ContainerRequestContext ctx);
Map<String, List<String>> IamConditionContextResolver.s3BucketListConditionContext(
        MultivaluedMap<String, String> queryParameters);
```

*Unchanged:* `public Decision evaluate(List<String> policyDocuments, String action, String resource)` (3-arg overload, passes `null`); `public static boolean globMatches(String pattern, String value)`.

**Steps:**

- [ ] Add a failing test to `src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java`. Add `import java.util.Map;` to the imports, then append this method before the closing brace:

```java
    @Test
    void singleValuedConditionKeyStillMatchesUnderTheMultiValuedContext() {
        // A plain (non-set) operator against a one-element list keeps today's semantics:
        // the first value is compared against the OR of the policy's condition values.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
               "Condition":{"StringEquals":{"aws:PrincipalArn":"arn:aws:iam::111122223333:user/alice"}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "s3:GetObject", "arn:aws:s3:::bucket/key",
                Map.of("aws:PrincipalArn", List.of("arn:aws:iam::111122223333:user/alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "s3:GetObject", "arn:aws:s3:::bucket/key",
                Map.of("aws:PrincipalArn", List.of("arn:aws:iam::111122223333:user/bob"))));
    }
```

- [ ] Run `./mvnw test -Dtest=IamPolicyEvaluatorTest`. **Expect a compilation failure**, not a test failure: `incompatible types: java.util.Map<java.lang.String,java.util.List<java.lang.String>> cannot be converted to java.util.Map<java.lang.String,java.lang.String>` at the two `simulateCustomPolicy` calls.

- [ ] In `IamPolicyEvaluator.java`, change the signature of `evaluate` (line 78) and its local, and update the Javadoc `@param conditionCtx` line to say "condition context key → values; may be null or empty":

```java
    public Decision evaluate(CallerContext caller,
                             List<String> resourcePolicies,
                             String action,
                             String resource,
                             Map<String, List<String>> conditionCtx) {
        Map<String, List<String>> ctx = normalizeConditionContext(conditionCtx);
```

- [ ] Change `simulateCustomPolicy` and `simulatePrincipalPolicy`:

```java
    public Decision simulateCustomPolicy(List<String> policyDocuments,
                                          String action,
                                          String resource,
                                          Map<String, List<String>> conditionCtx) {
        return evaluate(CallerContext.of(policyDocuments), null, action, resource, conditionCtx);
    }

    public SimulationDecision simulatePrincipalPolicy(CallerContext caller,
                                                      String action,
                                                      String resource,
                                                      Map<String, List<String>> conditionCtx) {
        Map<String, List<String>> ctx = normalizeConditionContext(conditionCtx);
```

- [ ] Change the four private helpers that thread `ctx` through — `scpAllows`, `anyExplicitDeny`, `anyExplicitAllow`, `matchesStatement`, `matchesConditions` — replacing every `Map<String, String> ctx` parameter with `Map<String, List<String>> ctx`. Exact new parameter lists:

```java
    private boolean scpAllows(List<List<String>> scpLevels, String action, String resource,
                              Map<String, List<String>> ctx) {
    private boolean anyExplicitDeny(List<PolicyStatement> stmts, String action, String resource,
                                     Map<String, List<String>> ctx) {
    private boolean anyExplicitAllow(List<PolicyStatement> stmts, String action, String resource,
                                      Map<String, List<String>> ctx) {
    private boolean matchesStatement(PolicyStatement stmt, String action, String resource,
                                      Map<String, List<String>> ctx) {
    private boolean matchesConditions(Map<String, Map<String, List<String>>> conditions,
                                       Map<String, List<String>> ctx) {
```

- [ ] Replace `normalizeConditionContext` (lines 211-221) wholesale. It now lower-cases keys, drops null keys and null value-lists, drops null members inside a list, and — importantly — **keeps an empty list as an empty list** (that is the AWS empty-set case, distinct from an absent key):

```java
    /**
     * Lower-cases keys and copies the value lists, dropping null keys, null lists and null
     * members. An empty list is preserved: an empty set is not the same thing as an absent
     * key — AWS's set operators give it its own semantics (ForAllValues matches vacuously,
     * ForAnyValue does not match).
     */
    private Map<String, List<String>> normalizeConditionContext(Map<String, List<String>> conditionCtx) {
        if (conditionCtx == null || conditionCtx.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : conditionCtx.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            List<String> values = entry.getValue().stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();
            normalized.putIfAbsent(entry.getKey().toLowerCase(java.util.Locale.ROOT), values);
        }
        return normalized;
    }
```

- [ ] Replace `evaluateConditionBlock` (lines 305-353) with the multi-valued version. The set-operator quantifier arrives in Task 2; for now the block reads a list and uses the first element for non-set operators, exactly preserving today's behaviour:

```java
    private boolean evaluateConditionBlock(String operator,
                                            Map<String, List<String>> keyValueMap,
                                            Map<String, List<String>> ctx) {
        boolean ifExists = operator.endsWith("IfExists");
        String baseOp = ifExists ? operator.substring(0, operator.length() - "IfExists".length()) : operator;

        for (Map.Entry<String, List<String>> entry : keyValueMap.entrySet()) {
            String condKey = entry.getKey().toLowerCase();
            List<String> condValues = entry.getValue();
            List<String> ctxValues = ctx.get(condKey);

            if (ctxValues == null) {
                if ("Null".equalsIgnoreCase(baseOp)) {
                    // Null: {key: "true"} → key must be absent → pass when any condValue is "true"
                    boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                    if (!expectAbsent) {
                        return false;
                    }
                    continue;
                }
                if (ifExists) {
                    continue; // key missing + IfExists → pass this key
                }
                return false; // key missing, no IfExists → fail entire block
            }

            if ("Null".equalsIgnoreCase(baseOp)) {
                // Key is present — Null:{key:"true"} should fail, Null:{key:"false"} should pass
                boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                if (expectAbsent) {
                    return false; // expected absent but key has value
                }
                continue;
            }

            // A bare operator against a multi-valued key is a policy authoring error in AWS;
            // mirror that by comparing only the first value, which is behaviour-preserving for
            // every key that is single-valued today.
            if (ctxValues.isEmpty()) {
                return false;
            }
            String ctxValue = ctxValues.getFirst();

            // OR across condValues for this key
            boolean keyMatch = false;
            for (String condValue : condValues) {
                if (evaluateSingleCondition(baseOp, ctxValue, condValue)) {
                    keyMatch = true;
                    break;
                }
            }
            if (!keyMatch) {
                return false;
            }
        }
        return true;
    }
```

- [ ] Update `IamConditionContextResolver.java` return types (no `dynamodb` branch yet — that is Task 6). Replace lines 13-33 with:

```java
    public Map<String, List<String>> resolve(String credentialScope, String action, ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            default -> null;
        };
    }

    private Map<String, List<String>> s3ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            default -> null;
        };
    }

    Map<String, List<String>> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, "s3:prefix", queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    private static void addQueryCondition(Map<String, List<String>> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, List.of(value));
        }
    }
```
and add `import java.util.List;` to the imports.

- [ ] Update `IamEnforcementFilter.java` line 174 and the `aws:PrincipalArn` put at lines 185-188:

```java
        Map<String, List<String>> conditionContext = conditionContextResolver.resolve(credentialScope, action, ctx);
```
```java
        if (principalArn.isPresent()) {
            conditionContext = conditionContext == null ? new HashMap<>() : new HashMap<>(conditionContext);
            conditionContext.put("aws:PrincipalArn", List.of(principalArn.get()));
        }
```
(`java.util.List` is already imported in that file.)

- [ ] Update `IamQueryHandler.java` line 1177 to the new local type — the method body is rewritten in Task 3, so for now change only the declaration and the map type in `extractContextEntries` so the file compiles:

```java
        Map<String, List<String>> context = extractContextEntries(params);
```
```java
    private Map<String, List<String>> extractContextEntries(MultivaluedMap<String, String> params) {
        Map<String, List<String>> context = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("ContextEntries.member." + i + ".ContextKeyName");
            if (name == null) break;
            String value = params.getFirst("ContextEntries.member." + i + ".ContextKeyValues.member.1");
            if (value != null) {
                context.put(name, List.of(value));
            }
        }
        return context;
    }
```

- [ ] Update `IamEnforcementFilterTest.java` line 259 so the mocked resolver return type matches:

```java
        Map<String, List<String>> conditions = Map.of("s3:prefix", List.of("my_namespace/table/"));
```

- [ ] Update the one existing multi-valued-context caller in `IamEnforcementIntegrationTest.java` (line 151), which currently passes a `Map<String,String>`:

```java
                        Map.of("AWS:SourceIP", List.of("127.0.0.1"))));
```

- [ ] Confirm `IamAuthValidator` (appsync) needs no change: it calls `evaluate(caller, null, "appsync:GraphQL", resource, null)` at lines 37-38 and 58 — a bare `null` still binds to the new parameter type. Do not edit it.

- [ ] Confirm `S3PublicAccessEvaluator` needs no change: it has its own private `conditionsMatch(JsonNode, Map<String,String>)` (line 177) and only uses `IamPolicyEvaluator.globMatches` statically. Do not edit it.

- [ ] Run `./mvnw -q -DskipTests test-compile`. Expect a clean compile. If any other call site surfaces, fix it by wrapping the value in `List.of(...)` — do not add overloads.

- [ ] Run `./mvnw test -Dtest='IamPolicyEvaluatorTest,IamEnforcementIntegrationTest,IamEnforcementFilterTest,IamConditionContextResolverTest'`. Expect **all green**, including the new `singleValuedConditionKeyStillMatchesUnderTheMultiValuedContext`.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluator.java src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java src/main/java/io/github/hectorvent/floci/core/common/IamEnforcementFilter.java src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java src/test/java/io/github/hectorvent/floci/services/iam/IamEnforcementIntegrationTest.java src/test/java/io/github/hectorvent/floci/core/common/IamEnforcementFilterTest.java
git commit -m "refactor(iam): make the policy condition context multi-valued

Condition keys such as dynamodb:LeadingKeys and dynamodb:Attributes are
set-typed by definition, but the evaluator carried the request context as
Map<String,String> from the filter all the way into the condition block, so
a set could not be represented at all.

Change the type to Map<String,List<String>> across evaluate,
simulateCustomPolicy, simulatePrincipalPolicy and the condition helpers, and
update the three in-tree call sites. Non-set operators keep today's semantics
by comparing only the first value, which is behaviour-preserving for every key
that is single-valued today. normalizeConditionContext now preserves an empty
list, which the set operators need to distinguish an empty set from an absent
key."
```

---

### Task 2: `ForAllValues:` / `ForAnyValue:` set operators

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluator.java` (`evaluateConditionBlock`, plus a new private enum, record and two helpers)
- Test: `src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java`

**Interfaces:**

*Consumes:* `private boolean evaluateSingleCondition(String operator, String ctxValue, String condValue)` — unchanged, reused verbatim for every quantified comparison.

*Produces (private to the class):*
```java
private enum SetQuantifier { NONE, FOR_ALL_VALUES, FOR_ANY_VALUE }
private record ParsedOperator(SetQuantifier quantifier, String baseOp, boolean ifExists) {}
private static ParsedOperator parseOperator(String operator);
private boolean matchesAnyCondValue(String baseOp, String ctxValue, List<String> condValues);
```

**Steps:**

- [ ] Add the failing tests to `IamPolicyEvaluatorTest.java`. Append these methods before the closing brace:

```java
    private static final String LEADING_KEYS_FOR_ALL = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
           "Condition":{"ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
        ]}""";

    @Test
    void forAllValuesRequiresEveryContextValueToMatch() {
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice", "USER_alice_2"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice", "USER_bob"))));
    }

    @Test
    void forAnyValueRequiresAtLeastOneContextValueToMatch() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["USER_alice"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob", "USER_alice"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob", "USER_carol"))));
    }

    @Test
    void emptySetMatchesForAllValuesAndNotForAnyValue() {
        // AWS: ForAllValues over an empty set is vacuously true; ForAnyValue is false.
        String anyValue = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.<String>of())));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(anyValue), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.<String>of())));
    }

    @Test
    void setOperatorWithoutIfExistsFailsClosedWhenTheKeyIsAbsent() {
        // The key is absent from the context entirely — not an empty set. A request that
        // cannot be proven in scope is treated as out of scope.
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_FOR_ALL), "dynamodb:GetItem", "*", Map.of()));
    }

    @Test
    void setOperatorComposesWithIfExists() {
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"ForAnyValue:StringEqualsIfExists":{"dynamodb:LeadingKeys":["USER_alice"]}}}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*", Map.of()));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
    }

    @Test
    void nullGuardBlocksTheVacuousForAllValuesAllowWhenTheKeyIsAbsent() {
        // The idiomatic AWS pairing: ForAllValues plus Null:false so the vacuous
        // empty-set truth cannot grant access when the key was never populated.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{
                 "ForAllValues:StringLikeIfExists":{"dynamodb:LeadingKeys":["USER_alice*"]},
                 "Null":{"dynamodb:LeadingKeys":"false"}}}
            ]}""";

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*", Map.of()));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
    }

    @Test
    void setOperatorPrefixIsMatchedCaseSensitively() {
        // AWS spells these exactly "ForAllValues:" / "ForAnyValue:". A different spelling is
        // an unknown operator and must not silently behave like the real thing.
        String policy = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
               "Condition":{"forallvalues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
            ]}""";

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(policy), "dynamodb:GetItem", "*",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));
    }
```

- [ ] Run `./mvnw test -Dtest=IamPolicyEvaluatorTest`. **Expect these failures** (the prefixed operator reaches `evaluateSingleCondition` as an unknown operator and yields `false`):
  - `forAllValuesRequiresEveryContextValueToMatch` — `expected: <ALLOW> but was: <DENY>` on the first assertion
  - `forAnyValueRequiresAtLeastOneContextValueToMatch` — `expected: <ALLOW> but was: <DENY>`
  - `emptySetMatchesForAllValuesAndNotForAnyValue` — `expected: <ALLOW> but was: <DENY>`
  - `setOperatorComposesWithIfExists` — `expected: <ALLOW> but was: <DENY>` (the `IfExists` suffix is not reached because the whole string is treated as the operator)
  - `nullGuardBlocksTheVacuousForAllValuesAllowWhenTheKeyIsAbsent` — `expected: <ALLOW> but was: <DENY>` on the second assertion
  - `setOperatorWithoutIfExistsFailsClosedWhenTheKeyIsAbsent` and `setOperatorPrefixIsMatchedCaseSensitively` already pass (both expect DENY); keep them, they lock the behaviour in.

- [ ] Add the quantifier types and the operator parser to `IamPolicyEvaluator.java`, immediately above `evaluateConditionBlock`:

```java
    /**
     * AWS set-operator quantifier. {@code ForAllValues:} requires every value the request
     * carries for the key to match the policy; {@code ForAnyValue:} requires at least one.
     */
    private enum SetQuantifier { NONE, FOR_ALL_VALUES, FOR_ANY_VALUE }

    private record ParsedOperator(SetQuantifier quantifier, String baseOp, boolean ifExists) {}

    /**
     * Splits a condition operator into its set quantifier, base operator and IfExists flag.
     * The prefix match is case-sensitive on exactly AWS's own spelling, so a mis-cased
     * "forallvalues:" stays an unknown operator instead of silently behaving like the
     * real quantifier. The IfExists strip runs on what is left, so
     * "ForAnyValue:StringEqualsIfExists" composes correctly.
     */
    private static ParsedOperator parseOperator(String operator) {
        SetQuantifier quantifier = SetQuantifier.NONE;
        String rest = operator;
        if (rest.startsWith("ForAllValues:")) {
            quantifier = SetQuantifier.FOR_ALL_VALUES;
            rest = rest.substring("ForAllValues:".length());
        } else if (rest.startsWith("ForAnyValue:")) {
            quantifier = SetQuantifier.FOR_ANY_VALUE;
            rest = rest.substring("ForAnyValue:".length());
        }
        boolean ifExists = rest.endsWith("IfExists");
        String baseOp = ifExists ? rest.substring(0, rest.length() - "IfExists".length()) : rest;
        return new ParsedOperator(quantifier, baseOp, ifExists);
    }

    /** OR across the policy's condition values for one request value. */
    private boolean matchesAnyCondValue(String baseOp, String ctxValue, List<String> condValues) {
        for (String condValue : condValues) {
            if (evaluateSingleCondition(baseOp, ctxValue, condValue)) {
                return true;
            }
        }
        return false;
    }
```

- [ ] Replace `evaluateConditionBlock` with the quantified version:

```java
    private boolean evaluateConditionBlock(String operator,
                                            Map<String, List<String>> keyValueMap,
                                            Map<String, List<String>> ctx) {
        ParsedOperator parsed = parseOperator(operator);
        String baseOp = parsed.baseOp();
        boolean ifExists = parsed.ifExists();

        for (Map.Entry<String, List<String>> entry : keyValueMap.entrySet()) {
            String condKey = entry.getKey().toLowerCase();
            List<String> condValues = entry.getValue();
            List<String> ctxValues = ctx.get(condKey);

            if (ctxValues == null) {
                if ("Null".equalsIgnoreCase(baseOp)) {
                    // Null: {key: "true"} → key must be absent → pass when any condValue is "true"
                    boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                    if (!expectAbsent) {
                        return false;
                    }
                    continue;
                }
                if (ifExists) {
                    continue; // key missing + IfExists → pass this key
                }
                return false; // key missing, no IfExists → fail entire block
            }

            if ("Null".equalsIgnoreCase(baseOp)) {
                // Key is present — Null:{key:"true"} should fail, Null:{key:"false"} should pass
                boolean expectAbsent = condValues.stream().anyMatch("true"::equalsIgnoreCase);
                if (expectAbsent) {
                    return false; // expected absent but key has value
                }
                continue;
            }

            boolean keyMatch = switch (parsed.quantifier()) {
                // Every request value must match at least one policy value. An empty set
                // matches vacuously, which is why real policies pair ForAllValues with a
                // Null:{key:"false"} guard.
                case FOR_ALL_VALUES -> ctxValues.stream()
                        .allMatch(ctxValue -> matchesAnyCondValue(baseOp, ctxValue, condValues));
                // At least one request value must match. An empty set never matches.
                case FOR_ANY_VALUE -> ctxValues.stream()
                        .anyMatch(ctxValue -> matchesAnyCondValue(baseOp, ctxValue, condValues));
                // A bare operator against a multi-valued key is a policy authoring error in
                // AWS; mirror that by comparing only the first value.
                case NONE -> !ctxValues.isEmpty()
                        && matchesAnyCondValue(baseOp, ctxValues.getFirst(), condValues);
            };
            if (!keyMatch) {
                return false;
            }
        }
        return true;
    }
```

- [ ] Run `./mvnw test -Dtest=IamPolicyEvaluatorTest`. Expect all tests green, including the six pre-existing SCP tests and `singleValuedConditionKeyStillMatchesUnderTheMultiValuedContext` from Task 1.

- [ ] Run `./mvnw test -Dtest='IamEnforcementIntegrationTest,IamEnforcementFilterTest'` as a regression check. Expect green.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluator.java src/test/java/io/github/hectorvent/floci/services/iam/IamPolicyEvaluatorTest.java
git commit -m "feat(iam): support ForAllValues and ForAnyValue set operators

ForAllValues: and ForAnyValue: appeared nowhere in the IAM package, so
evaluateConditionBlock stripped only an IfExists suffix and the whole
prefixed string reached the operator switch as an unknown operator, which
yields false. Every policy scoping DynamoDB access through
ForAllValues:StringLike therefore denied the requests it was written to allow.

Parse the quantifier prefix ahead of the IfExists strip so
ForAnyValue:StringEqualsIfExists composes, and evaluate the quantifier over
the request's value list, reusing evaluateSingleCondition for each member so
every existing operator works under a quantifier with no new operator code.
Empty-set semantics follow AWS: ForAllValues matches vacuously, ForAnyValue
does not. An absent key still fails closed without IfExists."
```

---

### Task 3: `IamQueryHandler.extractContextEntries` reads every context value

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java` (`extractContextEntries`, ~line 1405)
- Test: `src/test/java/io/github/hectorvent/floci/services/iam/IamIntegrationTest.java`

**Interfaces:**

*Consumes:* `jakarta.ws.rs.core.MultivaluedMap<String,String> params` — the AWS Query form parameters, flattened as `ContextEntries.member.<N>.ContextKeyName` and `ContextEntries.member.<N>.ContextKeyValues.member.<M>`.

*Produces:*
```java
private Map<String, List<String>> extractContextEntries(MultivaluedMap<String, String> params);
```

**Steps:**

- [ ] Add the failing test to `src/test/java/io/github/hectorvent/floci/services/iam/IamIntegrationTest.java`. Append this method before the closing brace (the file already has `@QuarkusTest`, `given()` and `equalTo` imported):

```java
    @Test
    void simulatePrincipalPolicyReadsEveryContextKeyValue() {
        // A ForAnyValue: condition is satisfied only by the SECOND supplied context value,
        // so a handler that reads only ContextKeyValues.member.1 returns implicitDeny.
        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", "multi-context-user")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260904/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "PutUserPolicy")
            .formParam("UserName", "multi-context-user")
            .formParam("PolicyName", "AllowAliceLeadingKeys")
            .formParam("PolicyDocument", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"ForAnyValue:StringEquals":{"dynamodb:LeadingKeys":["USER_alice"]}}}
                ]}""")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260904/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "SimulatePrincipalPolicy")
            .formParam("PolicySourceArn", "arn:aws:iam::000000000000:user/multi-context-user")
            .formParam("ActionNames.member.1", "dynamodb:GetItem")
            .formParam("ResourceArns.member.1", "*")
            .formParam("ContextEntries.member.1.ContextKeyName", "dynamodb:LeadingKeys")
            .formParam("ContextEntries.member.1.ContextKeyValues.member.1", "USER_bob")
            .formParam("ContextEntries.member.1.ContextKeyValues.member.2", "USER_alice")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260904/us-east-1/iam/aws4_request")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SimulatePrincipalPolicyResponse.SimulatePrincipalPolicyResult.EvaluationResults"
                            + ".member.find { it.EvalActionName == 'dynamodb:GetItem' }.EvalDecision",
                    equalTo("allowed"));
    }
```

- [ ] Run `./mvnw test -Dtest=IamIntegrationTest#simulatePrincipalPolicyReadsEveryContextKeyValue`. **Expect a failure**: `expected: allowed but was: implicitDeny` — only `USER_bob` reached the context, so `ForAnyValue:StringEquals` found no match.

- [ ] Replace `extractContextEntries` in `IamQueryHandler.java`:

```java
    /**
     * Reads every {@code ContextEntries.member.N.ContextKeyValues.member.M}. AWS context keys
     * are set-typed, and the evaluator's set operators quantify over the whole set, so reading
     * only {@code member.1} silently dropped the values a ForAnyValue:/ForAllValues: condition
     * has to see. An entry that names a key but supplies no value is skipped.
     */
    private Map<String, List<String>> extractContextEntries(MultivaluedMap<String, String> params) {
        Map<String, List<String>> context = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("ContextEntries.member." + i + ".ContextKeyName");
            if (name == null) break;
            List<String> values = extractIndexedValues(
                    params, "ContextEntries.member." + i + ".ContextKeyValues.member");
            if (!values.isEmpty()) {
                context.put(name, values);
            }
        }
        return context;
    }
```
(`extractIndexedValues(params, prefix)` already exists at line 1395 and walks `prefix + "." + i` until the first gap — exactly the shape needed here.)

- [ ] Run `./mvnw test -Dtest=IamIntegrationTest`. Expect all green, including the pre-existing `SimulatePrincipalPolicy` test at line 357.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java src/test/java/io/github/hectorvent/floci/services/iam/IamIntegrationTest.java
git commit -m "fix(iam): read every SimulatePrincipalPolicy context key value

extractContextEntries read only ContextKeyValues.member.1 and dropped the
rest, which was invisible while the condition context was single-valued but
silently breaks set operators: a ForAnyValue: condition satisfied by the
second supplied value evaluated as implicitDeny. Reuse extractIndexedValues
to collect the whole member list per context entry."
```

---

### Task 4: `DynamoDbKeyConditionParser`

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParser.java`
- Test: `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParserTest.java` (new)

**Interfaces:**

*Consumes (all package-private in `services.dynamodb`, verified by reading `ExpressionEvaluator.java`):*
```java
static ExpressionEvaluator.Expr ExpressionEvaluator.parse(String expression);   // line 369, throws IllegalArgumentException on syntax errors, returns null for a blank expression
record ExpressionEvaluator.AndExpr(List<Expr> operands)                        // line 176
record ExpressionEvaluator.CompareExpr(Operand left, TokenType op, Operand right) // line 179
record ExpressionEvaluator.PathOperand(List<String> segments)                  // line 185
record ExpressionEvaluator.PlaceholderOperand(String name)                     // line 186
enum ExpressionEvaluator.TokenType { ..., EQ, ... }                            // line 24
```

*Produces:*
```java
final class DynamoDbKeyConditionParser {
    static String partitionKeyEqualityValue(String keyConditionExpression,
                                            JsonNode exprAttrNames,
                                            JsonNode exprAttrValues,
                                            String pkName);
}
```

**Steps:**

- [ ] Create the failing test `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParserTest.java`:

```java
package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamoDbKeyConditionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesSimplePartitionKeyEquality() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"S\":\"USER_alice\"}}"), "pk"));
    }

    @Test
    void resolvesPartitionKeyEqualityAlongsideASortKeyCondition() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v AND sk > :s", null,
                json("{\":v\":{\"S\":\"USER_alice\"},\":s\":{\"S\":\"2020\"}}"), "pk"));
    }

    @Test
    void resolvesAnAliasedPartitionKeyThroughExpressionAttributeNames() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "#p = :v", json("{\"#p\":\"pk\"}"),
                json("{\":v\":{\"S\":\"USER_alice\"}}"), "pk"));
    }

    @Test
    void resolvesNumericAndBinaryPartitionKeys() {
        assertEquals("42", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"N\":\"42\"}}"), "pk"));
        assertEquals("YWJj", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"B\":\"YWJj\"}}"), "pk"));
    }

    @Test
    void returnsNullWhenThePartitionKeyIsNotPinnedByEquality() {
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "sk > :s", null, json("{\":s\":{\"S\":\"2020\"}}"), "pk"));
    }

    @Test
    void returnsNullOnUnparseableOrMissingInput() {
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = = :v", null, json("{\":v\":{\"S\":\"x\"}}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                null, null, json("{}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, null, "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":other\":{\"S\":\"x\"}}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"S\":\"x\"}}"), null));
    }
}
```

- [ ] Run `./mvnw test -Dtest=DynamoDbKeyConditionParserTest`. **Expect a compilation failure**: `cannot find symbol: class DynamoDbKeyConditionParser`.

- [ ] Create `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParser.java`:

```java
package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.AndExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.CompareExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.Expr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PathOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PlaceholderOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.TokenType;

import java.util.List;

/**
 * Narrow read-only view over a Query {@code KeyConditionExpression}: the scalar value the
 * partition key is pinned to by an equality condition.
 *
 * <p>Exists so IAM condition-key extraction can reuse the expression parser without making
 * the whole {@link DynamoDbAccessPathValidator} public. Everything here is best effort: a
 * malformed or unsupported expression yields {@code null}, and the caller treats that as
 * "the leading key could not be determined", which fails closed at the policy layer.
 */
final class DynamoDbKeyConditionParser {

    private DynamoDbKeyConditionParser() {}

    /**
     * @param keyConditionExpression the raw Query KeyConditionExpression
     * @param exprAttrNames          ExpressionAttributeNames, or {@code null}
     * @param exprAttrValues         ExpressionAttributeValues, or {@code null}
     * @param pkName                 the table's HASH key attribute name
     * @return the S / N / B scalar the partition key equals, or {@code null} when it cannot
     *         be resolved
     */
    static String partitionKeyEqualityValue(String keyConditionExpression,
                                            JsonNode exprAttrNames,
                                            JsonNode exprAttrValues,
                                            String pkName) {
        if (keyConditionExpression == null || keyConditionExpression.isBlank()
                || pkName == null || exprAttrValues == null) {
            return null;
        }
        Expr root;
        try {
            root = ExpressionEvaluator.parse(keyConditionExpression);
        } catch (RuntimeException e) {
            return null; // unparseable expression → leading key unknown → fail closed upstream
        }
        if (root == null) {
            return null;
        }
        List<Expr> conditions = root instanceof AndExpr and ? and.operands() : List.of(root);
        for (Expr condition : conditions) {
            if (!(condition instanceof CompareExpr compare)
                    || compare.op() != TokenType.EQ
                    || !(compare.right() instanceof PlaceholderOperand placeholder)) {
                continue;
            }
            if (!pkName.equals(topLevelAttribute(compare.left(), exprAttrNames))) {
                continue;
            }
            return scalarValue(exprAttrValues.get(placeholder.name()));
        }
        return null;
    }

    /** Resolves a single-segment path operand, following a {@code #alias} when one is used. */
    private static String topLevelAttribute(Object operand, JsonNode exprAttrNames) {
        if (!(operand instanceof PathOperand path) || path.segments().size() != 1) {
            return null;
        }
        String segment = path.segments().getFirst();
        if (segment.startsWith("#") && exprAttrNames != null && exprAttrNames.has(segment)) {
            return exprAttrNames.get(segment).asText();
        }
        return segment;
    }

    /** Unwraps an AttributeValue to its scalar text. Only S, N and B can be key values. */
    private static String scalarValue(JsonNode attributeValue) {
        if (attributeValue == null || !attributeValue.isObject()) {
            return null;
        }
        for (String type : List.of("S", "N", "B")) {
            JsonNode payload = attributeValue.get(type);
            if (payload != null && payload.isTextual()) {
                return payload.asText();
            }
        }
        return null;
    }
}
```

- [ ] Run `./mvnw test -Dtest=DynamoDbKeyConditionParserTest`. Expect all six tests green.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParser.java src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbKeyConditionParserTest.java
git commit -m "feat(dynamodb): add a KeyConditionExpression partition-key value reader

IAM condition-key extraction needs the value a Query pins its partition key
to, which today only DynamoDbAccessPathValidator can compute and only as a
side effect of validation. Add a narrow package-internal reader over the
existing ExpressionEvaluator instead of widening the validator's visibility.
Anything it cannot resolve returns null, which the policy layer treats as an
unknown leading key and fails closed on."
```

---

### Task 5: `DynamoDbConditionKeys`

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeys.java`
- Test: `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeysTest.java` (new)

**Interfaces:**

*Consumes:*
```java
static String DynamoDbKeyConditionParser.partitionKeyEqualityValue(String, JsonNode, JsonNode, String);  // Task 4
static Set<String> ProjectionEvaluator.topLevelAttributes(String projectionExpression, JsonNode exprAttrNames); // ProjectionEvaluator.java:55, package-private
public List<KeySchemaElement> TableDefinition.getKeySchema();
public String TableDefinition.getTableName();
public String KeySchemaElement.getAttributeName();
public String KeySchemaElement.getKeyType();   // "HASH" or "RANGE"
```

*Produces:*
```java
public final class DynamoDbConditionKeys {
    public static Result extract(String action, JsonNode body, TableDefinition table);
    public record Result(List<String> leadingKeys, List<String> attributes, String select) {}
}
```

**Steps:**

- [ ] Create the failing test `src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeysTest.java`:

```java
package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbConditionKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private TableDefinition table;

    @BeforeEach
    void setUp() {
        table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(
                new KeySchemaElement("PK", "HASH"),
                new KeySchemaElement("SK", "RANGE")));
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getItemExposesTheKeyValueAndTheKeyAttributeNames() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals(List.of("PK", "SK"), result.attributes());
        assertNull(result.select());
    }

    @Test
    void putItemExposesTheItemPartitionKeyAndEveryItemAttribute() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:PutItem",
                json("""
                    {"TableName":"FgacTable",
                     "Item":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"},"email":{"S":"a@b.c"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals(List.of("PK", "SK", "email"), result.attributes());
    }

    @Test
    void updateItemExposesTheKeyValueAndTheUpdateExpressionTargets() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:UpdateItem",
                json("""
                    {"TableName":"FgacTable",
                     "Key":{"PK":{"S":"USER_alice"},"SK":{"S":"profile"}},
                     "UpdateExpression":"SET #e = :e REMOVE nickname",
                     "ExpressionAttributeNames":{"#e":"email"},
                     "ExpressionAttributeValues":{":e":{"S":"a@b.c"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertTrue(result.attributes().contains("PK"), result.attributes().toString());
        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }

    @Test
    void deleteItemExposesTheKeyValue() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:DeleteItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_bob"},"SK":{"S":"profile"}}}"""),
                table);

        assertEquals(List.of("USER_bob"), result.leadingKeys());
    }

    @Test
    void queryResolvesTheLeadingKeyFromTheKeyConditionExpressionAndCarriesSelect() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditionExpression":"PK = :v AND SK > :s",
                     "ExpressionAttributeValues":{":v":{"S":"USER_alice"},":s":{"S":"2020"}},
                     "ProjectionExpression":"email, #n",
                     "ExpressionAttributeNames":{"#n":"nickname"},
                     "Select":"SPECIFIC_ATTRIBUTES"}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
        assertEquals("SPECIFIC_ATTRIBUTES", result.select());
        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }

    @Test
    void queryResolvesAnAliasedPartitionKey() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:Query",
                json("""
                    {"TableName":"FgacTable",
                     "KeyConditionExpression":"#p = :v",
                     "ExpressionAttributeNames":{"#p":"PK"},
                     "ExpressionAttributeValues":{":v":{"S":"USER_alice"}}}"""),
                table);

        assertEquals(List.of("USER_alice"), result.leadingKeys());
    }

    @Test
    void batchGetItemExposesEveryRequestedKey() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:BatchGetItem",
                json("""
                    {"RequestItems":{"FgacTable":{"Keys":[
                       {"PK":{"S":"USER_alice"},"SK":{"S":"a"}},
                       {"PK":{"S":"USER_alice_2"},"SK":{"S":"b"}},
                       {"PK":{"S":"USER_bob"},"SK":{"S":"c"}}]}}}"""),
                table);

        assertEquals(List.of("USER_alice", "USER_alice_2", "USER_bob"), result.leadingKeys());
    }

    @Test
    void batchWriteItemExposesPutAndDeleteKeys() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:BatchWriteItem",
                json("""
                    {"RequestItems":{"FgacTable":[
                       {"PutRequest":{"Item":{"PK":{"S":"USER_alice"},"SK":{"S":"a"}}}},
                       {"DeleteRequest":{"Key":{"PK":{"S":"USER_bob"},"SK":{"S":"b"}}}}]}}"""),
                table);

        assertEquals(List.of("USER_alice", "USER_bob"), result.leadingKeys());
    }

    @Test
    void nullTableYieldsNoLeadingKeysAndDoesNotThrow() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""),
                null);

        assertTrue(result.leadingKeys().isEmpty());
        assertEquals(List.of("PK"), result.attributes());
    }

    @Test
    void aKeyMissingThePartitionAttributeYieldsNoLeadingKeys() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"SK":{"S":"profile"}}}"""),
                table);

        assertTrue(result.leadingKeys().isEmpty());
    }

    @Test
    void nullBodyYieldsAnEmptyResult() {
        DynamoDbConditionKeys.Result result =
                DynamoDbConditionKeys.extract("dynamodb:GetItem", null, table);

        assertTrue(result.leadingKeys().isEmpty());
        assertTrue(result.attributes().isEmpty());
        assertNull(result.select());
    }

    @Test
    void attributesToGetAreExposed() {
        DynamoDbConditionKeys.Result result = DynamoDbConditionKeys.extract(
                "dynamodb:GetItem",
                json("""
                    {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}},
                     "AttributesToGet":["email","nickname"]}"""),
                table);

        assertTrue(result.attributes().contains("email"), result.attributes().toString());
        assertTrue(result.attributes().contains("nickname"), result.attributes().toString());
    }
}
```

- [ ] Run `./mvnw test -Dtest=DynamoDbConditionKeysTest`. **Expect a compilation failure**: `cannot find symbol: class DynamoDbConditionKeys`.

- [ ] Create `src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeys.java`:

```java
package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts the DynamoDB IAM condition keys — {@code dynamodb:LeadingKeys},
 * {@code dynamodb:Attributes} and {@code dynamodb:Select} — from a request body.
 *
 * <p>Public and static, following the precedent of {@link DynamoDbPartiQLParser}, which the
 * IAM package already calls across the package boundary.
 *
 * <p>Everything is best effort. Whatever cannot be determined is simply absent from the
 * result: an unresolvable leading key produces an empty list, the condition key is then
 * omitted from the request context, and a policy that scopes access through it fails closed.
 */
public final class DynamoDbConditionKeys {

    private DynamoDbConditionKeys() {}

    /**
     * @param action the IAM action, e.g. {@code dynamodb:GetItem}
     * @param body   the parsed request body; may be {@code null}
     * @param table  the target table's definition, used only for the HASH key name; may be
     *               {@code null}, in which case no leading keys are produced
     */
    public static Result extract(String action, JsonNode body, TableDefinition table) {
        if (body == null || !body.isObject()) {
            return new Result(List.of(), List.of(), null);
        }
        String pkName = partitionKeyName(table);
        List<String> leadingKeys = new ArrayList<>();
        Set<String> attributes = new LinkedHashSet<>();

        switch (action == null ? "" : action) {
            case "dynamodb:GetItem", "dynamodb:DeleteItem", "dynamodb:UpdateItem" -> {
                JsonNode key = body.get("Key");
                addAttributeNames(attributes, key);
                addLeadingKey(leadingKeys, key, pkName);
            }
            case "dynamodb:PutItem" -> {
                JsonNode item = body.get("Item");
                addAttributeNames(attributes, item);
                addLeadingKey(leadingKeys, item, pkName);
            }
            case "dynamodb:Query" -> {
                String value = DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                        textOrNull(body.get("KeyConditionExpression")),
                        body.get("ExpressionAttributeNames"),
                        body.get("ExpressionAttributeValues"),
                        pkName);
                if (value != null) {
                    leadingKeys.add(value);
                }
            }
            case "dynamodb:BatchGetItem" -> {
                for (JsonNode tableRequest : requestItemsFor(body, table)) {
                    JsonNode keys = tableRequest.get("Keys");
                    if (keys != null && keys.isArray()) {
                        for (JsonNode key : keys) {
                            addAttributeNames(attributes, key);
                            addLeadingKey(leadingKeys, key, pkName);
                        }
                    }
                    addAttributesToGet(attributes, tableRequest.get("AttributesToGet"));
                    addProjectionAttributes(attributes,
                            textOrNull(tableRequest.get("ProjectionExpression")),
                            tableRequest.get("ExpressionAttributeNames"));
                }
            }
            case "dynamodb:BatchWriteItem" -> {
                for (JsonNode tableRequest : requestItemsFor(body, table)) {
                    if (!tableRequest.isArray()) {
                        continue;
                    }
                    for (JsonNode write : tableRequest) {
                        JsonNode put = write.path("PutRequest").get("Item");
                        addAttributeNames(attributes, put);
                        addLeadingKey(leadingKeys, put, pkName);
                        JsonNode delete = write.path("DeleteRequest").get("Key");
                        addAttributeNames(attributes, delete);
                        addLeadingKey(leadingKeys, delete, pkName);
                    }
                }
            }
            default -> {
                // Scan and everything else: no leading keys. Attributes and Select below
                // still apply where the body carries them.
            }
        }

        addAttributesToGet(attributes, body.get("AttributesToGet"));
        addProjectionAttributes(attributes, textOrNull(body.get("ProjectionExpression")),
                body.get("ExpressionAttributeNames"));
        addUpdateExpressionTargets(attributes, textOrNull(body.get("UpdateExpression")),
                body.get("ExpressionAttributeNames"));
        addExpressionAttributeNameValues(attributes, body.get("ExpressionAttributeNames"));

        return new Result(List.copyOf(leadingKeys), List.copyOf(attributes),
                textOrNull(body.get("Select")));
    }

    /**
     * The extracted keys. {@code leadingKeys} holds partition-key values in request order
     * (duplicates preserved), {@code attributes} holds attribute names in first-seen order
     * with duplicates removed, {@code select} is the raw Select value or {@code null}.
     */
    public record Result(List<String> leadingKeys, List<String> attributes, String select) {}

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String partitionKeyName(TableDefinition table) {
        if (table == null || table.getKeySchema() == null) {
            return null;
        }
        return table.getKeySchema().stream()
                .filter(key -> "HASH".equals(key.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElse(null);
    }

    /**
     * The RequestItems entries this request touches. Prefers the entry named by the resolved
     * table, because only that table's HASH key name is known; falls back to every entry when
     * nothing matches, so a stub or an aliased name still yields something.
     */
    private static List<JsonNode> requestItemsFor(JsonNode body, TableDefinition table) {
        JsonNode requestItems = body.get("RequestItems");
        if (requestItems == null || !requestItems.isObject()) {
            return List.of();
        }
        String tableName = table == null ? null : table.getTableName();
        if (tableName != null && requestItems.has(tableName)) {
            return List.of(requestItems.get(tableName));
        }
        List<JsonNode> all = new ArrayList<>();
        requestItems.elements().forEachRemaining(all::add);
        return all;
    }

    private static void addLeadingKey(List<String> leadingKeys, JsonNode attributeMap, String pkName) {
        if (attributeMap == null || !attributeMap.isObject() || pkName == null) {
            return;
        }
        String value = scalarValue(attributeMap.get(pkName));
        if (value != null) {
            leadingKeys.add(value);
        }
    }

    private static void addAttributeNames(Set<String> attributes, JsonNode attributeMap) {
        if (attributeMap == null || !attributeMap.isObject()) {
            return;
        }
        Iterator<String> names = attributeMap.fieldNames();
        while (names.hasNext()) {
            attributes.add(names.next());
        }
    }

    private static void addAttributesToGet(Set<String> attributes, JsonNode attributesToGet) {
        if (attributesToGet == null || !attributesToGet.isArray()) {
            return;
        }
        for (JsonNode attribute : attributesToGet) {
            if (attribute.isTextual()) {
                attributes.add(attribute.asText());
            }
        }
    }

    private static void addProjectionAttributes(Set<String> attributes, String projectionExpression,
                                                JsonNode exprAttrNames) {
        if (projectionExpression == null || projectionExpression.isBlank()) {
            return;
        }
        try {
            attributes.addAll(ProjectionEvaluator.topLevelAttributes(projectionExpression, exprAttrNames));
        } catch (RuntimeException e) {
            // A malformed projection is the request handler's problem to report; for condition
            // keys it just means those attribute names stay unknown.
        }
    }

    private static void addExpressionAttributeNameValues(Set<String> attributes, JsonNode exprAttrNames) {
        if (exprAttrNames == null || !exprAttrNames.isObject()) {
            return;
        }
        exprAttrNames.elements().forEachRemaining(value -> {
            if (value.isTextual()) {
                attributes.add(value.asText());
            }
        });
    }

    /**
     * Top-level attribute names an UpdateExpression writes. Splits on the four clause
     * keywords, then on commas, and keeps the first path segment of each target. Aliases are
     * resolved through ExpressionAttributeNames.
     */
    private static void addUpdateExpressionTargets(Set<String> attributes, String updateExpression,
                                                   JsonNode exprAttrNames) {
        if (updateExpression == null || updateExpression.isBlank()) {
            return;
        }
        String[] clauses = updateExpression.split("(?i)\\b(SET|REMOVE|ADD|DELETE)\\b");
        for (String clause : clauses) {
            for (String assignment : clause.split(",")) {
                String target = assignment.trim();
                if (target.isEmpty()) {
                    continue;
                }
                int equals = target.indexOf('=');
                if (equals >= 0) {
                    target = target.substring(0, equals).trim();
                }
                // ADD / DELETE take "path value"; keep only the path.
                int space = target.indexOf(' ');
                if (space > 0) {
                    target = target.substring(0, space);
                }
                String name = firstPathSegment(target);
                if (name == null) {
                    continue;
                }
                if (name.startsWith("#") && exprAttrNames != null && exprAttrNames.has(name)) {
                    attributes.add(exprAttrNames.get(name).asText());
                } else if (!name.startsWith("#") && !name.startsWith(":")) {
                    attributes.add(name);
                }
            }
        }
    }

    private static String firstPathSegment(String path) {
        int cut = path.length();
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        if (dot >= 0) {
            cut = Math.min(cut, dot);
        }
        if (bracket >= 0) {
            cut = Math.min(cut, bracket);
        }
        String segment = path.substring(0, cut).trim();
        return segment.isEmpty() ? null : segment;
    }

    /** Unwraps an AttributeValue to its scalar text. Only S, N and B can be key values. */
    private static String scalarValue(JsonNode attributeValue) {
        if (attributeValue == null || !attributeValue.isObject()) {
            return null;
        }
        for (String type : List.of("S", "N", "B")) {
            JsonNode payload = attributeValue.get(type);
            if (payload != null && payload.isTextual()) {
                return payload.asText();
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    /** Reserved for callers that need a canonical action suffix; kept private and unused-safe. */
    private static String actionSuffix(String action) {
        int colon = action.indexOf(':');
        return colon < 0 ? action : action.substring(colon + 1).toLowerCase(Locale.ROOT);
    }
}
```
Note: delete the trailing `actionSuffix` / the `Locale` import if the compiler flags them as unused — they are only there if you decide to normalise the action; the switch above matches the full `dynamodb:X` action string, so **remove `actionSuffix` and the `java.util.Locale` import before committing**.

- [ ] Run `./mvnw test -Dtest=DynamoDbConditionKeysTest`. Expect all twelve tests green. If `updateItemExposesTheKeyValueAndTheUpdateExpressionTargets` fails, print the actual attribute list from the assertion message and fix `addUpdateExpressionTargets` — do not weaken the assertion.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeys.java src/test/java/io/github/hectorvent/floci/services/dynamodb/DynamoDbConditionKeysTest.java
git commit -m "feat(dynamodb): extract LeadingKeys, Attributes and Select from a request

Add a static extractor covering GetItem, PutItem, UpdateItem, DeleteItem,
Query, BatchGetItem and BatchWriteItem. Partition-key values come from Key,
Item, the Query KeyConditionExpression or the per-table RequestItems entries;
attribute names come from Key/Item fields, AttributesToGet,
ProjectionExpression, UpdateExpression targets and ExpressionAttributeNames.

Whatever cannot be determined is absent rather than guessed, so an
unresolvable leading key yields an empty list and the policy layer fails
closed on it."
```

---

### Task 6: Wire the `dynamodb` branch into `IamConditionContextResolver`

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java` (whole file)
- Test: `src/test/java/io/github/hectorvent/floci/core/common/IamConditionContextResolverTest.java`

**Interfaces:**

*Consumes:*
```java
public TableDefinition DynamoDbService.describeTable(String tableName, String region);
        // DynamoDbService.java:379 — throws AwsException("ResourceNotFoundException", …, 400) when absent
public String RequestContext.getRegion();                       // RequestContext.java:24
public String EmulatorConfig.defaultRegion();                   // used identically in IamEnforcementFilter:138
Object ContainerRequestContext.getProperty(String name);        // "floci.bufferedJsonBody" → JsonNode, set by ResourceArnBuilder.readJsonBody
public static DynamoDbConditionKeys.Result DynamoDbConditionKeys.extract(String, JsonNode, TableDefinition);
```

*Produces:*
```java
@Inject public IamConditionContextResolver(Instance<DynamoDbService> dynamoDbService,
                                           ObjectMapper objectMapper,
                                           RequestContext requestContext,
                                           EmulatorConfig config);
public Map<String, List<String>> resolve(String credentialScope, String action, ContainerRequestContext ctx);
Map<String, List<String>> dynamoDbConditionContext(String action, ContainerRequestContext ctx);  // package-private for tests
```

**Steps:**

- [ ] Add the failing tests to `src/test/java/io/github/hectorvent/floci/core/common/IamConditionContextResolverTest.java`. Replace the whole file with:

```java
package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private Instance<DynamoDbService> dynamoDbServiceInstance;
    private RequestContext requestContext;
    private EmulatorConfig config;
    private IamConditionContextResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        dynamoDbServiceInstance = mock(Instance.class);
        when(dynamoDbServiceInstance.isResolvable()).thenReturn(true);
        when(dynamoDbServiceInstance.get()).thenReturn(dynamoDbService);
        requestContext = new RequestContext();
        requestContext.setRegion("us-east-1");
        config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        resolver = new IamConditionContextResolver(
                dynamoDbServiceInstance, mapper, requestContext, config);
    }

    private TableDefinition fgacTable() {
        TableDefinition table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH")));
        return table;
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals(List.of("my_namespace/table/"), conditions.get("s3:prefix"));
        assertEquals(List.of("/"), conditions.get("s3:delimiter"));
        assertEquals(List.of("100"), conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }

    @Test
    void resolvesDynamoDbLeadingKeysForGetItem() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.describeTable("FgacTable", "us-east-1")).thenReturn(fgacTable());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
        assertFalse(conditions.containsKey("dynamodb:Select"));
    }

    @Test
    void omitsLeadingKeysWhenTheTableIsUnknown() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"MissingTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.describeTable(eq("MissingTable"), anyString()))
                .thenThrow(new AwsException("ResourceNotFoundException",
                        "Requested resource not found: Table: MissingTable not found", 400));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
        // Attribute names do not depend on the key schema, so they are still populated.
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
    }

    @Test
    void carriesSelectForQuery() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable",
                 "KeyConditionExpression":"PK = :v",
                 "ExpressionAttributeValues":{":v":{"S":"USER_alice"}},
                 "Select":"COUNT"}"""));
        when(dynamoDbService.describeTable("FgacTable", "us-east-1")).thenReturn(fgacTable());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:Query", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("COUNT"), conditions.get("dynamodb:Select"));
    }

    @Test
    void returnsNullWhenNoBodyWasBuffered() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(null);

        assertNull(resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest));
    }

    @Test
    void resolvesBatchGetItemAcrossEveryRequestedKey() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{"FgacTable":{"Keys":[
                   {"PK":{"S":"USER_alice"}},{"PK":{"S":"USER_bob"}}]}}}"""));
        when(dynamoDbService.describeTable("FgacTable", "us-east-1")).thenReturn(fgacTable());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertEquals(List.of("USER_alice", "USER_bob"), conditions.get("dynamodb:LeadingKeys"));
    }
}
```

- [ ] Run `./mvnw test -Dtest=IamConditionContextResolverTest`. **Expect a compilation failure**: `constructor IamConditionContextResolver in class IamConditionContextResolver cannot be applied to given types` (the class still has the implicit no-arg constructor).

- [ ] Rewrite `src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java`:

```java
package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbConditionKeys;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the IAM request context — the condition keys a policy's Condition block can match —
 * for the request currently being enforced.
 */
@ApplicationScoped
public class IamConditionContextResolver {

    private static final Logger LOG = Logger.getLogger(IamConditionContextResolver.class);

    /** Set by {@code ResourceArnBuilder.readJsonBody}, which runs earlier in the same filter pass. */
    private static final String BUFFERED_JSON_BODY = "floci.bufferedJsonBody";

    private final Instance<DynamoDbService> dynamoDbService;
    private final ObjectMapper objectMapper;
    private final RequestContext requestContext;
    private final EmulatorConfig config;

    @Inject
    public IamConditionContextResolver(Instance<DynamoDbService> dynamoDbService,
                                       ObjectMapper objectMapper,
                                       RequestContext requestContext,
                                       EmulatorConfig config) {
        this.dynamoDbService = dynamoDbService;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
        this.config = config;
    }

    public Map<String, List<String>> resolve(String credentialScope, String action,
                                             ContainerRequestContext ctx) {
        return switch (credentialScope) {
            case "s3" -> s3ConditionContext(action, ctx);
            case "dynamodb" -> dynamoDbConditionContext(action, ctx);
            default -> null;
        };
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> s3ConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            default -> null;
        };
    }

    Map<String, List<String>> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, "s3:prefix", queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    private static void addQueryCondition(Map<String, List<String>> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, List.of(value));
        }
    }

    // ── DynamoDB ────────────────────────────────────────────────────────────────

    /**
     * Populates {@code dynamodb:LeadingKeys}, {@code dynamodb:Attributes} and
     * {@code dynamodb:Select} from the buffered request body.
     *
     * <p>A key that cannot be resolved — unknown table, a Key that omits the partition
     * attribute, an unparseable KeyConditionExpression — is omitted rather than guessed. A
     * policy scoping access through the missing key then does not match, so the request is
     * denied: a request that cannot be proven in scope is treated as out of scope.
     */
    Map<String, List<String>> dynamoDbConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        TableDefinition table = describeTargetTable(body);
        DynamoDbConditionKeys.Result keys = DynamoDbConditionKeys.extract(action, body, table);

        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (!keys.leadingKeys().isEmpty()) {
            conditions.put("dynamodb:LeadingKeys", keys.leadingKeys());
        }
        if (!keys.attributes().isEmpty()) {
            conditions.put("dynamodb:Attributes", keys.attributes());
        }
        if (keys.select() != null) {
            conditions.put("dynamodb:Select", List.of(keys.select()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private JsonNode bufferedJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_JSON_BODY);
        return cached instanceof JsonNode node ? node : null;
    }

    /**
     * Looks up the table whose key schema names the partition key. Resolved lazily through
     * Instance so core.common keeps no hard dependency on the DynamoDB service.
     */
    private TableDefinition describeTargetTable(JsonNode body) {
        String tableName = targetTableName(body);
        if (tableName == null || !dynamoDbService.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null
                ? config.defaultRegion() : requestContext.getRegion();
        try {
            return dynamoDbService.get().describeTable(tableName, region);
        } catch (RuntimeException e) {
            LOG.debugv("DynamoDB condition keys: table {0} not resolvable in {1}: {2}",
                    tableName, region, e.getMessage());
            return null;
        }
    }

    /**
     * The single table this request targets: the TableName field, or the sole RequestItems
     * entry for the batch operations. A multi-table batch has no single key schema, so it
     * gets no leading keys and fails closed.
     */
    private String targetTableName(JsonNode body) {
        if (body.hasNonNull("TableName")) {
            String tableName = body.get("TableName").asText().trim();
            if (!tableName.isEmpty()) {
                return tableName;
            }
        }
        JsonNode requestItems = body.get("RequestItems");
        if (requestItems != null && requestItems.isObject() && requestItems.size() == 1) {
            return requestItems.fieldNames().next();
        }
        return null;
    }
}
```
The injected `ObjectMapper` is not read by any branch today; keep the parameter (the spec calls for it and a later branch that has to parse a raw body will need it) but if the build runs with `-Werror` on unused fields, drop the field and the constructor parameter, and update the test's constructor call accordingly.

- [ ] Verify the `ResourceArnBuilder` → resolver ordering has not changed: `IamEnforcementFilter.filter` calls `arnBuilder.buildResources(...)` at line 172 and `conditionContextResolver.resolve(...)` at line 174. The body is buffered and cached by the first call. **Do not reorder.**

- [ ] Run `./mvnw test -Dtest=IamConditionContextResolverTest`. Expect all eight tests green.

- [ ] Run `./mvnw test -Dtest='IamEnforcementFilterTest,IamEnforcementIntegrationTest,ResourceArnBuilderTest'`. Expect green — the filter mocks the resolver, so its construction is unaffected.

- [ ] Commit:
```
git add src/main/java/io/github/hectorvent/floci/core/common/IamConditionContextResolver.java src/test/java/io/github/hectorvent/floci/core/common/IamConditionContextResolverTest.java
git commit -m "feat(iam): populate the DynamoDB condition keys from the request

IamConditionContextResolver supplied s3:* keys only and returned null for every
other service, so dynamodb:LeadingKeys, dynamodb:Attributes and dynamodb:Select
were never in the request context and no DynamoDB fine-grained policy could
match. Add a dynamodb branch that reads the body already buffered by
ResourceArnBuilder, resolves the table's key schema through a lazily injected
DynamoDbService, and emits the three keys.

A key that cannot be resolved is omitted rather than guessed, so a policy
scoping access through it denies the request."
```

---

### Task 7: End-to-end enforcement test for the issue's scenario

**Files:**
- Modify: `src/test/java/io/github/hectorvent/floci/services/iam/IamEnforcementIntegrationTest.java` (append two tests)
- Create: `src/test/java/io/github/hectorvent/floci/services/iam/DynamoDbFgacEnforcementIntegrationTest.java`

**Interfaces:**

*Consumes:*
```java
io.github.hectorvent.floci.testing.RestAssuredJsonUtils.configureAwsContentTypes();  // @BeforeAll, required for x-amz-json content types
io.quarkus.test.junit.QuarkusTestProfile#getConfigOverrides()  // {"floci.services.iam.enforcement-enabled": "true"}
```
HTTP shape (from `DynamoDbTableArnIntegrationTest`): `POST /` with header `X-Amz-Target: DynamoDB_20120810.<Op>` and content type `application/x-amz-json-1.0`.
Auth header shape (from `StsSessionPolicyS3EnforcementIntegrationTest`): `AWS4-HMAC-SHA256 Credential=<akid>/20260904/us-east-1/<service>/aws4_request, SignedHeaders=host, Signature=abc`.

*Produces:* two new evaluator-level tests plus one new `@QuarkusTest @TestProfile` class.

**Steps:**

- [ ] Append the evaluator-level scenario tests to `IamEnforcementIntegrationTest.java` (before the closing brace). They need no new imports beyond `java.util.List` and `java.util.Map`, both already present:

```java
    // =========================================================================
    // DynamoDB fine-grained access control (issue #2926)
    // =========================================================================

    private static final String LEADING_KEYS_SCOPED_POLICY = """
        {"Version":"2012-10-17","Statement":[
          {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:Query"],
           "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
           "Condition":{"ForAllValues:StringLike":{"dynamodb:LeadingKeys":["USER_alice*"]}}}
        ]}""";

    @Test
    void leadingKeysConditionAllowsTheInScopeItemAndDeniesTheOutOfScopeOne() {
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_alice"))));

        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));

        // The leading key could not be resolved from the request: fail closed.
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(
                List.of(LEADING_KEYS_SCOPED_POLICY), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of()));
    }

    @Test
    void wildcardActionAndResourcePolicyStillAllowsRegardlessOfLeadingKeys() {
        // Control: the condition is what discriminates above, not the action or the ARN.
        String wildcard = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"*","Resource":"*"}
            ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(
                List.of(wildcard), "dynamodb:GetItem",
                "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable",
                Map.of("dynamodb:LeadingKeys", List.of("USER_bob"))));
    }
```

- [ ] Run `./mvnw test -Dtest=IamEnforcementIntegrationTest`. Expect green — Tasks 1 and 2 already deliver this behaviour; these tests lock the issue's acceptance criteria at the evaluator level.

- [ ] Create the HTTP end-to-end test `src/test/java/io/github/hectorvent/floci/services/iam/DynamoDbFgacEnforcementIntegrationTest.java`. This is the test that proves resolver + filter + evaluator work together; it needs the enforcement profile, so it lives in its own class:

```java
package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end proof of the scenario in issue #2926: one session scoped by
 * ForAllValues:StringLike on dynamodb:LeadingKeys reads its own partition and is denied
 * another tenant's, through the real filter, resolver and evaluator.
 */
@QuarkusTest
@TestProfile(DynamoDbFgacEnforcementIntegrationTest.IamEnforcementProfile.class)
class DynamoDbFgacEnforcementIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void leadingKeysScopedSessionReadsItsOwnPartitionAndIsDeniedAnotherTenants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-" + suffix;
        String roleName = "FgacRole" + suffix;

        createTable(tableName);
        putItem(tableName, "USER_alice");
        putItem(tableName, "USER_bob");
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);

        String accessKeyId = assumeRoleWithLeadingKeysSessionPolicy(roleName);

        // In scope: the session policy's ForAllValues:StringLike matches USER_alice*.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"USER_alice"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Item.PK.S", equalTo("USER_alice"));

        // Out of scope: same session, same table, different partition.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"PK":{"S":"USER_bob"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("AccessDeniedException"));
    }

    @Test
    void requestThatCannotProveItsLeadingKeyIsDenied() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tableName = "fgac-noprove-" + suffix;
        String roleName = "FgacNoProveRole" + suffix;

        createTable(tableName);
        createRole(roleName);
        putBroadDynamoDbRolePolicy(roleName);

        String accessKeyId = assumeRoleWithLeadingKeysSessionPolicy(roleName);

        // The Key omits the partition attribute, so the leading key cannot be resolved. With
        // access scoped purely through dynamodb:LeadingKeys this is denied rather than passed
        // to DynamoDB, which is the correct direction for a security boundary.
        given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Key":{"SK":{"S":"profile"}}}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("AccessDeniedException"));
    }

    private static void createTable(String tableName) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s",
                     "KeySchema":[{"AttributeName":"PK","KeyType":"HASH"}],
                     "AttributeDefinitions":[{"AttributeName":"PK","AttributeType":"S"}],
                     "BillingMode":"PAY_PER_REQUEST"}"""
                        .formatted(tableName))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putItem(String tableName, String partitionKey) {
        given()
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("""
                    {"TableName":"%s","Item":{"PK":{"S":"%s"},"email":{"S":"x@y.z"}}}"""
                        .formatted(tableName, partitionKey))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void createRole(String roleName) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": { "AWS": "*" },
                          "Action": "sts:AssumeRole"
                        }
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putBroadDynamoDbRolePolicy(String roleName) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "AllowDynamoDb")
                .formParam("PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"}
                    ]}""")
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    /**
     * The session policy is the only thing that discriminates: Resource is "*", so an
     * allow or a deny can only come from the LeadingKeys condition.
     */
    private static String assumeRoleWithLeadingKeysSessionPolicy(String roleName) {
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "fgac-leading-keys-test")
                .formParam("Policy", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"dynamodb:*","Resource":"*",
                       "Condition":{"ForAllValues:StringLike":
                         {"dynamodb:LeadingKeys":["USER_alice*"]}}}
                    ]}""")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260904/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
```

- [ ] Run `./mvnw test -Dtest=DynamoDbFgacEnforcementIntegrationTest`. Expect green. **If the allow case returns 403**, the likely cause is the account the assumed-role session resolves to differing from `ROLE_ACCOUNT_ID`, so the table lookup in the resolver misses. Diagnose by temporarily raising `IamConditionContextResolver`'s logger to DEBUG (`-Dquarkus.log.category."io.github.hectorvent.floci.core.common.IamConditionContextResolver".level=DEBUG`) and reading the "table not resolvable" line; fix by creating the table under whichever account the session resolves to. Do not weaken the assertion.

- [ ] Commit:
```
git add src/test/java/io/github/hectorvent/floci/services/iam/IamEnforcementIntegrationTest.java src/test/java/io/github/hectorvent/floci/services/iam/DynamoDbFgacEnforcementIntegrationTest.java
git commit -m "test(iam): cover the DynamoDB LeadingKeys scenario end to end

Add the issue #2926 acceptance criteria at evaluator level, plus an HTTP test
with enforcement enabled where one scoped session reads USER_alice and is
denied USER_bob against the same table, and a request whose Key omits the
partition attribute is denied because its leading key cannot be proven."
```

---

### Task 8: Document the new operators and condition keys

**Files:**
- Modify: `docs/services/iam.md` (lines 386-417)

**Interfaces:** none (documentation only).

**Steps:**

- [ ] In `docs/services/iam.md`, replace line 393 (`- Supports \`...IfExists\` variants for all operators.`) with:

```markdown
- Supports `...IfExists` variants for all operators.
- Set operators `ForAllValues:` and `ForAnyValue:` over multi-valued condition keys, in AWS's
  own spelling (the prefix match is case-sensitive). They compose with `IfExists`
  (`ForAnyValue:StringEqualsIfExists`). `ForAllValues:` over an empty set matches vacuously
  and `ForAnyValue:` over an empty set does not match, so pair `ForAllValues:` with
  `"Null":{"<key>":"false"}` as you would on AWS.
```

- [ ] In the same file, insert this bullet into the "Condition keys floci populates" list, after the `aws:PrincipalArn` bullet that ends on line 405:

```markdown
- `dynamodb:LeadingKeys`, `dynamodb:Attributes`, `dynamodb:Select` — from the DynamoDB request
  body, for `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchGetItem` and
  `BatchWriteItem` (`dynamodb:Select` for `Query` and `Scan`). `LeadingKeys` holds the
  partition-key values the request names — from `Key`, `Item`, the `KeyConditionExpression`
  equality, or each `RequestItems` entry. `Attributes` holds the attribute names the request
  touches. Each key is **omitted** when it cannot be determined (unknown table, a `Key` that
  omits the partition attribute, an unparseable `KeyConditionExpression`, a multi-table batch),
  so a policy scoping access through it denies the request rather than allowing an unproven one.

  **Consequence:** with enforcement on and access scoped purely through `dynamodb:LeadingKeys`,
  a malformed request — a `GetItem` whose `Key` omits the partition attribute — is answered with
  `AccessDeniedException` instead of the `ValidationException` DynamoDB would return. Failing
  closed is the correct direction for a security boundary.
```

- [ ] Update the "Not yet supported" line (line 417) to name the deferred surface:

```markdown
**Not yet supported**: `NotPrincipal`, resource-based policies (S3 bucket policy, Lambda resource
policy), and `dynamodb:LeadingKeys` for `Scan`, `TransactWriteItems` / `TransactGetItems` and the
PartiQL operations.
```

- [ ] Read the edited section back and confirm nothing above or below was disturbed: `./mvnw` is not involved here, so verify with `git diff docs/services/iam.md`.

- [ ] Commit:
```
git add docs/services/iam.md
git commit -m "docs(iam): document the set operators and the DynamoDB condition keys

Record ForAllValues:/ForAnyValue: with their empty-set semantics, the three
dynamodb:* condition keys and the actions they cover, and the fail-closed
consequence for a request whose leading key cannot be determined."
```

---

### Task 9: Full verification and follow-up issue draft

**Files:** none modified (verification only). The follow-up issue text lives in the appendix of this plan.

**Interfaces:** none.

**Steps:**

- [ ] Run the focused suite: `./mvnw test -Dtest='IamPolicyEvaluatorTest,DynamoDbConditionKeysTest,DynamoDbKeyConditionParserTest,IamConditionContextResolverTest,IamEnforcementFilterTest'`. Expect green.

- [ ] Run the integration suite: `./mvnw test -Dtest='IamEnforcementIntegrationTest,DynamoDbFgacEnforcementIntegrationTest,IamIntegrationTest,StsSessionPolicyS3EnforcementIntegrationTest,LambdaExecutionRoleIamEnforcementIntegrationTest,ScpEnforcementLeaveOrganizationIntegrationTest,DynamoDbIntegrationTest,DynamoDbAccessPathIntegrationTest'`. Expect green.

- [ ] Run the full suite: `./mvnw test` (PowerShell: `.\mvnw.cmd test`). This is slow — it is `@QuarkusTest`-heavy and surefire runs with `-Xmx6g`. Expect zero failures. If anything unrelated is red, check whether it is red on `second/main` too before touching it.

- [ ] Confirm the working tree holds nothing unintended: `git status --short`. The plan file `docs/superpowers/plans/2026-09-04-dynamodb-fine-grained-access-control.md` should still be **untracked** — do not add it.

- [ ] Review the whole diff against `second/main`: `git diff second/main --stat` and then `git diff second/main`. Confirm every file in the File Structure table is present and nothing else is.

- [ ] Copy the follow-up issue text from the appendix below into a new GitHub issue on `floci-io/floci` (or hand it to the maintainer). Reference it from the PR body.

- [ ] Open the PR with a body that states: the three gaps closed, the fail-closed decision and its documented consequence, the deferred surface with a link to the follow-up issue, and the commands run. Per repo convention the PR body carries **no** Claude attribution line — match the existing PRs in `second/main`.

---

## Self-Review

Spec-section → task coverage:

| Spec section | Covered by |
|---|---|
| §1 Condition context becomes multi-valued — `evaluate` / `simulatePrincipalPolicy` / `simulateCustomPolicy` signatures | Task 1 |
| §1 `normalizeConditionContext` over lists | Task 1 |
| §1 `evaluateConditionBlock` reads `List<String>` | Task 1 |
| §1 Non-set operators act on the first value | Task 1 (`case NONE` in Task 2's final form) |
| §1 Call site: `IamEnforcementFilter` map type + `aws:PrincipalArn` singleton list | Task 1 |
| §1 Call site: `IamQueryHandler.extractContextEntries` multi-value | Task 3 |
| §1 Call site: `IamAuthValidator` compile-check only | Task 1 (explicit no-edit step) |
| §1 Call site: `IamConditionContextResolver` return type + S3 singleton lists | Task 1 |
| §1 Left untouched: 3-arg `evaluate`, `S3PublicAccessEvaluator` | Task 1 (explicit no-edit steps) |
| §2 Prefix parsing, case-sensitive, before the `IfExists` strip | Task 2 (`parseOperator`) |
| §2 Quantified evaluation reusing `evaluateSingleCondition` | Task 2 (`matchesAnyCondValue`) |
| §2 Empty-set semantics (ForAll true / ForAny false) | Task 2 (`emptySetMatchesForAllValuesAndNotForAnyValue`) |
| §2 Key-absent fail-closed; `IfExists` composition; `Null` branch wins | Task 2 (`setOperatorWithoutIfExistsFailsClosedWhenTheKeyIsAbsent`, `setOperatorComposesWithIfExists`, `nullGuardBlocks…`) |
| §3 `DynamoDbConditionKeys.extract` + `Result` record | Task 5 |
| §3 `leadingKeys` for the 7 actions | Task 5 |
| §3 `attributes` sources | Task 5 |
| §3 `select` verbatim | Task 5 |
| §3 `DynamoDbKeyConditionParser.partitionKeyEqualityValue` | Task 4 |
| §3 Resolver `dynamodb` branch, lazy `Instance<DynamoDbService>`, buffered body, key omission rules | Task 6 |
| §3 Ordering (`buildResources` before `resolve`) unchanged | Task 6 (explicit verification step) |
| §4 Fail-closed when `LeadingKeys` cannot be resolved | Task 6 (implementation), Task 7 (`requestThatCannotProveItsLeadingKeyIsDenied`) |
| §4 Documented consequence | Task 8 |
| Testing — `IamPolicyEvaluatorTest` set-operator cases + regression | Tasks 1, 2 |
| Testing — `DynamoDbConditionKeysTest` | Task 5 |
| Testing — `IamConditionContextResolverTest` | Task 6 |
| Testing — `IamEnforcementFilterTest` | Task 1 |
| Testing — `IamEnforcementIntegrationTest` scenario + control | Task 7 |
| Docs — condition-key matrix | Task 8 |
| Docs — follow-up issue draft | Appendix + Task 9 |
| Commands — focused, integration, full suite | Task 9 |

Every spec section maps to at least one task; no task exists without a spec section behind it.

## Concerns

1. **The spec's wiring step reads `TableName` only, but lists the batch operations in scope.** `BatchGetItem` / `BatchWriteItem` bodies carry no `TableName` — the table lives in the `RequestItems` object key. Taken literally, spec §3 step 2 would leave `table = null` for every batch request and therefore never populate `LeadingKeys` for two of the seven in-scope actions, contradicting the `leadingKeys` bullet two paragraphs above and the "BatchGetItem with 3 keys → 3 leading-key values" test. Task 6 resolves this by also accepting a **single-entry** `RequestItems` as the target table name. A multi-table batch still yields no leading keys (there is no single key schema), which fails closed. Flag for the reviewer: this is a small addition beyond the spec's literal wording, made in the direction the spec's own scope list requires.

2. **`EmulatorConfig` is injected into the resolver, which the spec's injection list does not name.** Spec §3 says "plus `ObjectMapper` and `RequestContext` (region)". `RequestContext.getRegion()` can be null, and `IamEnforcementFilter` already falls back to `config.defaultRegion()` for exactly that reason (line 138). Without the same fallback a null region would make every table lookup miss and every DynamoDB FGAC request fail closed. `EmulatorConfig` is added purely as that fallback.

3. **`ObjectMapper` is injected into the resolver but unread.** The body is always already parsed and cached by `ResourceArnBuilder`, so no branch needs to parse anything. Task 6 keeps the parameter because the spec calls for it, with a note to drop it if the build objects. A reviewer may reasonably ask for it to go.

4. **The spec's Integration test section demands HTTP status codes (`200` / `AccessDeniedException`) but names `IamEnforcementIntegrationTest`, which does no HTTP** — it is a `@QuarkusTest` that injects the evaluator and never issues a request, and it carries no enforcement `@TestProfile`. Task 7 therefore does both: the acceptance assertions at evaluator level in that file (as the spec's "Files touched" table says), plus a new `DynamoDbFgacEnforcementIntegrationTest` with the enforcement profile for the real HTTP path. The new file is not in the spec's file list.

5. **The spec's "Files touched" table lists `IamEnforcementFilterTest` as extended, but the only change it needs is a type fix** on the mocked S3 condition map (line 259). No new filter test is warranted: the filter mocks the resolver, so there is nothing new to assert there that Task 6's resolver tests do not already cover better.

6. **Commit attribution.** The harness that produced this plan is configured to append `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` to commits. `second/main`'s history contains no such trailer — only human `Co-authored-by:` lines — so the commit messages in this plan omit it, per the instruction to treat the upstream history as the authority. Confirm with the maintainer before pushing if this matters to them.

7. **`addUpdateExpressionTargets` is a lightweight scanner, not a parser.** There is no UpdateExpression parser in the codebase (`ExpressionEvaluator` handles condition/filter/key expressions only), and building one is well outside this issue. The scanner splits on the four clause keywords and commas; a pathological expression (a literal comma inside a function argument list on the left-hand side of a `SET`) could produce a spurious attribute name. `dynamodb:Attributes` over-reporting is the safe direction under `ForAllValues:` — it can only deny, never over-grant — but it is imprecise and worth a follow-up if `Attributes` conditions become load-bearing.

8. **`dynamodb:Select` is documented and populated for `Query`, but `Scan` is a non-goal for `LeadingKeys`.** `DynamoDbConditionKeys.extract` populates `Select` for any action whose body carries the field, including `Scan`, since `Select` needs no key schema. That matches spec §"Scope — actions covered" ("`dynamodb:Select` is populated for **Query** and **Scan**") and does not pull Scan's `LeadingKeys` into scope.

---

## Appendix — follow-up issue draft

**Title:** DynamoDB fine-grained access control: Scan LeadingKeys, transactions and PartiQL

**Body:**

> Follow-up to #2926, which landed `dynamodb:LeadingKeys` / `dynamodb:Attributes` / `dynamodb:Select` for GetItem, PutItem, UpdateItem, DeleteItem, Query, BatchGetItem and BatchWriteItem, together with the `ForAllValues:` / `ForAnyValue:` set operators.
>
> Three areas were deliberately deferred there:
>
> **1. `Scan` and `dynamodb:LeadingKeys`.** A Scan names no partition key. AWS treats this as the *empty set*, which under `ForAllValues:` matches vacuously — meaning a policy scoped to one tenant's partition would let a Scan read the whole table unless it also carries the `"Null":{"dynamodb:LeadingKeys":"false"}` guard. #2926 chose to omit the key entirely (fail closed) rather than inject an empty list, because injecting the empty list makes the fail-open-on-empty-set path reachable and that deserves its own discussion. Decide whether floci should inject the empty set for Scan (faithful to AWS, fail-open without the Null guard) or keep omitting it (safer, diverges from AWS).
>
> **2. Transactions.** `TransactWriteItems` and `TransactGetItems` carry their items under `TransactItems[].{Put,Get,Update,Delete,ConditionCheck}`, each with its own `TableName`. `ResourceArnBuilder.buildDynamoDbArns` already walks that shape for ARNs; `DynamoDbConditionKeys` does not, so a transaction currently produces no leading keys and is denied under a `LeadingKeys`-scoped policy. Each entry may target a different table, so this needs per-entry key-schema resolution.
>
> **3. PartiQL.** `ExecuteStatement` (`Statement`), `BatchExecuteStatement` (`Statements[]`) and `ExecuteTransaction` (`TransactStatements[]`). `DynamoDbPartiQLParser.extractTable` already gets the table name; extracting the partition-key value from a `WHERE PK = 'x'` clause needs the PartiQL parser to expose the predicate.
>
> Also worth revisiting: `dynamodb:Attributes` for `UpdateExpression` targets currently uses a keyword/comma scanner rather than a real UpdateExpression parser (see #2926's implementation notes), which can over-report an attribute name for a pathological expression. Over-reporting only ever denies, never over-grants, but a proper parser would be better if `Attributes` conditions become load-bearing.
