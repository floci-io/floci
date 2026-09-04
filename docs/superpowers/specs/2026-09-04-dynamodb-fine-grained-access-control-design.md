# DynamoDB fine-grained access control — design

**Issue:** [floci-io/floci#2926](https://github.com/floci-io/floci/issues/2926)
**Prerequisite:** [#2925](https://github.com/floci-io/floci/issues/2925) — done (PR #2928, merged 2026-09-02). `ResourceArnBuilder.buildDynamoDbArns` already parses the JSON body and derives the table ARN; the parsed body is cached at `ctx.getProperty("floci.bufferedJsonBody")`.
**Branch:** `feat/2926-dynamodb-fine-grained-access-control` (from `second/main`)

## Problem

IAM enforcement cannot evaluate DynamoDB item-level access control. Three gaps compound:

1. **Condition keys never populated.** `IamConditionContextResolver` supplies `s3:*` keys only; `default -> null` for every other service.
2. **Set operators missing.** `ForAllValues:` / `ForAnyValue:` appear nowhere in the IAM package. `IamPolicyEvaluator.evaluateConditionBlock` strips only an `IfExists` suffix, so `ForAllValues:StringLike` reaches the operator switch as a literal unknown operator and yields `false`.
3. **Condition context is single-valued.** `Map<String,String>` end to end, while `dynamodb:LeadingKeys` and `dynamodb:Attributes` are set-typed by definition.

Combined effect is fail-closed but wrong: a policy scoping DynamoDB access through `ForAllValues:StringLike` on `dynamodb:LeadingKeys` denies every request it was meant to allow, so it cannot distinguish in-scope from out-of-scope items.

## Goals

- Populate `dynamodb:LeadingKeys`, `dynamodb:Attributes`, `dynamodb:Select` from the request.
- Implement `ForAllValues:` / `ForAnyValue:` over a multi-valued condition context, with AWS empty-set semantics.
- Make the multi-tenant `LeadingKeys` scenario from the issue observable: `GetItem USER_alice` allowed, `GetItem USER_bob` denied, under one scoped session.

## Non-goals (deferred to a follow-up issue)

- **Scan** `LeadingKeys` — a Scan names no key; AWS treats this as the empty set, which needs the fail-open-on-empty-set path and its own discussion.
- **TransactWriteItems / TransactGetItems**, **PartiQL** (`Statement` / `Statements` / `TransactStatements`), deep **`RequestItems[].Keys`** extraction beyond the batch item ops listed below.
- Any change to S3 condition-key resolution.
- Any refactor of `IamPolicyEvaluator`'s operator switch beyond what the set operators require.

## Scope — actions covered

`dynamodb:LeadingKeys` and `dynamodb:Attributes` are populated for:

- **GetItem, PutItem, UpdateItem, DeleteItem, Query, BatchGetItem, BatchWriteItem**

`dynamodb:Select` is populated for **Query** and **Scan** (from the `Select` field).

## Design

### 1. Condition context becomes multi-valued

Change the type directly to `Map<String, List<String>>`. No parallel overload, no wrapper class.

- `IamPolicyEvaluator.evaluate(...)`, `simulatePrincipalPolicy(...)`, `simulateCustomPolicy(...)` — param `Map<String,String> conditionCtx` → `Map<String,List<String>>`.
- `normalizeConditionContext` — lower-cases keys, drops null keys/values, now over lists.
- `evaluateConditionBlock` — reads `List<String> ctxValues` for the key instead of a scalar `String`.
- Non-set operators keep today's semantics by acting on `ctxValues.get(0)` (the first value). AWS's rule is that a bare operator against a multi-valued key is a policy authoring error; mirroring that by using only the first value is behaviour-preserving for every key that is single-valued today.

**Call sites updated (4):**

| Site | Change |
|---|---|
| `IamEnforcementFilter` ([~L174-188](../../../src/main/java/io/github/hectorvent/floci/core/common/IamEnforcementFilter.java)) | `conditionContext` map type; `aws:PrincipalArn` put becomes `List.of(arn)` |
| `IamQueryHandler.extractContextEntries` ([~L1405](../../../src/main/java/io/github/hectorvent/floci/services/iam/IamQueryHandler.java)) | Reads all `ContextEntries.member.N.ContextKeyValues.member.M` instead of only `.member.1` — fixes a latent drop of extra context values in SimulatePrincipalPolicy |
| `IamAuthValidator` (appsync) | Passes `null` today; stays `null`. Compile-check only |
| `IamConditionContextResolver.resolve(...)` | Return type → `Map<String,List<String>>`; S3 branch wraps its three values in singleton lists |

**Left untouched:**

- 3-arg `evaluate(List<String>, action, resource)` convenience overload — passes `null`.
- `S3PublicAccessEvaluator` — has its own private `conditionsMatch`, does not call the evaluator's condition path.

### 2. `ForAllValues:` / `ForAnyValue:` set operators

**Parsing** — in `evaluateConditionBlock`, before the `IfExists` strip, detect a set-operator prefix:

```
"ForAllValues:StringLike"            -> quantifier = FOR_ALL_VALUES, rest = "StringLike"
"ForAnyValue:StringEqualsIfExists"   -> quantifier = FOR_ANY_VALUE, rest = "StringEqualsIfExists"
"StringLike"                         -> quantifier = NONE,           rest = "StringLike"
```

Prefix match is case-sensitive on exactly `ForAllValues:` and `ForAnyValue:` (AWS's own spelling). The existing `IfExists` strip then runs on `rest`, so `ForAnyValue:StringEqualsIfExists` composes correctly.

**Evaluation** — for each policy key in the block, `ctxValues` = the request's list for that key (the set being quantified), `condValues` = the values written in the policy.

| Quantifier | Rule | Empty `ctxValues` |
|---|---|---|
| `NONE` | unchanged — first ctx value vs OR-of-`condValues` | key-missing path as today |
| `ForAllValues:` | **every** member of `ctxValues` matches at least one `condValue` | **matches (true)** — vacuous truth, per AWS |
| `ForAnyValue:` | **at least one** member of `ctxValues` matches at least one `condValue` | **no match (false)** — per AWS |

"Matches a `condValue`" reuses `evaluateSingleCondition(baseOp, member, condValue)`, so `ForAllValues:StringLike`, `ForAllValues:StringEquals`, `ForAllValues:ArnLike`, etc. all work with no new operator code.

**Interaction with key-missing / `IfExists` / `Null`:**

- Key absent from context + `ForAllValues:` + no `IfExists` → existing `return false` (block fails, fail-closed). Deliberate — see §4.
- `ForAllValues:...IfExists` + key absent → pass (the `IfExists` continue path).
- `Null` operator keeps its own branch. A set operator is not combined with `Null` in practice; if it is, `Null` handling wins as it does now.

**Empty-set guard.** `ForAllValues:` true-on-empty is why real policies pair it with `"Null":{"dynamodb:LeadingKeys":"false"}`. Both halves are implemented so the guard works: the `Null:false` block fails when the key is absent, blocking the vacuous allow.

### 3. Populating the DynamoDB condition keys

**New public helper** `io.github.hectorvent.floci.services.dynamodb.DynamoDbConditionKeys` (static, following the precedent of `DynamoDbPartiQLParser`, which `ResourceArnBuilder` already calls across the package boundary):

```java
public final class DynamoDbConditionKeys {
    public static Result extract(String action, JsonNode body, TableDefinition table);
    public record Result(List<String> leadingKeys, List<String> attributes, String select) {}
}
```

- **`leadingKeys`** — partition-key *values* the request names:
  - GetItem / DeleteItem / UpdateItem: `Key[<pkName>]` scalar (`S` / `N` / `B`)
  - PutItem: `Item[<pkName>]`
  - Query: `KeyConditionExpression` partition-key equality value, resolved through `ExpressionAttributeValues` (and `ExpressionAttributeNames` for `#alias` partition keys)
  - BatchGetItem: every `RequestItems.<table>.Keys[].<pkName>`
  - BatchWriteItem: every `RequestItems.<table>[].PutRequest.Item[<pkName>]` / `.DeleteRequest.Key[<pkName>]`
  - `<pkName>` = the `HASH` element of `table.getKeySchema()` (the `DynamoDbAccessPath.partitionKeyName()` logic)
- **`attributes`** — attribute *names* touched: `Item` keys, `Key` keys, `AttributesToGet[]`, `ProjectionExpression` (split on `,`, `#alias` resolved via `ExpressionAttributeNames`), `UpdateExpression` targets, `ExpressionAttributeNames` values.
- **`select`** — `body.get("Select")` verbatim (`ALL_ATTRIBUTES`, `SPECIFIC_ATTRIBUTES`, `COUNT`, `ALL_PROJECTED_ATTRIBUTES`), or `null`.

**Query `KeyConditionExpression` parsing.** The existing expression parser lives in the package-private `DynamoDbAccessPathValidator`. Expose a narrow package-internal entry point — `DynamoDbKeyConditionParser.partitionKeyEqualityValue(keyConditionExpression, exprAttrNames, exprAttrValues, pkName)` returning the resolved scalar or `null` — rather than making the whole validator public. `DynamoDbConditionKeys` (same package) calls it.

**Wiring — `IamConditionContextResolver` gains a `dynamodb` branch:**

- Constructor injects `Instance<DynamoDbService>` (lazy, mirroring `IamEnforcementFilter`'s `Instance<ScpProvider>` — avoids a hard `core.common -> services.dynamodb` dependency), plus `ObjectMapper` and `RequestContext` (region).
- `resolve("dynamodb", action, ctx)`:
  1. Read buffered body: `ctx.getProperty("floci.bufferedJsonBody")` (already populated by `ResourceArnBuilder` earlier in the same filter pass).
  2. `TableName` from body → `dynamoDbService.describeTable(name, region)` for the key schema. Table absent or lookup throws → `table = null`.
  3. `DynamoDbConditionKeys.extract(action, body, table)`.
  4. Emit `dynamodb:LeadingKeys`, `dynamodb:Attributes` (omit when empty), `dynamodb:Select` (omit when null) into the `Map<String,List<String>>`.
- `IamConditionContextResolver.resolve` signature is unchanged — it already receives `credentialScope`, `action`, `ctx`.

**Ordering.** `IamEnforcementFilter` calls `arnBuilder.buildResources(...)` before `conditionContextResolver.resolve(...)`, so the body is buffered and parsed by the time the resolver runs. No reordering.

### 4. Behaviour when `LeadingKeys` can't be resolved — fail-closed

Cases: table doesn't exist, `Key` / `Item` missing the PK attribute, `KeyConditionExpression` doesn't parse, unhandled body shape.

- The key is omitted from the context map.
- A statement with `ForAllValues:StringLike` on `dynamodb:LeadingKeys` and no `IfExists` then hits the existing `return false` → statement does not allow → request denied.
- Matches the direction the codebase already took and is the safe direction for a security boundary: a request that can't be proven in-scope is treated as out-of-scope.

**Not doing:** injecting an empty list to trigger `ForAllValues:` vacuous-true. That only makes sense for an operation that genuinely names no key (Scan), deferred with the empty-set discussion.

**Documented consequence.** With enforcement on and a policy scoping `GetItem` purely through `dynamodb:LeadingKeys`, a `GetItem` whose `Key` omits the partition attribute is denied (`AccessDeniedException`) rather than passed to DynamoDB (which would return `ValidationException`). A malformed request failing closed in scope-enforced mode is correct.

## Testing

### Unit — `IamPolicyEvaluatorTest` (extend)

- `ForAllValues:StringLike` on `LeadingKeys`: `[USER_alice]` vs `USER_alice*` → allow; `[USER_bob]` → deny; `[USER_alice, USER_alice_2]` → allow; `[USER_alice, USER_bob]` → deny.
- `ForAnyValue:StringEquals`: one-of matches → allow; none match → deny.
- Empty set: `ForAllValues:` → match; `ForAnyValue:` → no match.
- `ForAnyValue:StringEqualsIfExists` + key absent → pass.
- `ForAllValues:` + `Null:{key:"false"}` guard, key absent → overall deny.
- Regression: existing single-valued key tests still green after the `Map<String,List<String>>` switch.

### Unit — `DynamoDbConditionKeysTest` (new)

- Each of the 7 actions → correct `leadingKeys` / `attributes` / `select`, with a stub `TableDefinition` (pk = `PK`).
- `KeyConditionExpression` variants: `PK = :v`; `PK = :v AND SK > :s`; `#p = :v` with `ExpressionAttributeNames`.
- `table == null` → `leadingKeys` empty, no crash.
- BatchGetItem with 3 keys → 3 leading-key values.

### Unit — `IamConditionContextResolverTest` (extend)

- `dynamodb` + `GetItem` with buffered body + mocked `DynamoDbService` → map has `dynamodb:LeadingKeys`.
- Unknown table → key omitted.
- `s3` path unchanged (regression).

### Integration — `IamEnforcementIntegrationTest` (extend)

Enforcement on, role with `dynamodb:GetItem` + `ForAllValues:StringLike` on `dynamodb:LeadingKeys` of `USER_alice*`:

- `GetItem USER_alice` → 200
- `GetItem USER_bob` → `AccessDeniedException`
- Control: `Action * / Resource *` still ALLOWED; policy naming the real table ARN with the condition → in-scope key allowed (confirms #2925 + this together).

### Commands

```
./mvnw test -Dtest=IamPolicyEvaluatorTest,DynamoDbConditionKeysTest,IamConditionContextResolverTest,IamEnforcementFilterTest
./mvnw test -Dtest=IamEnforcementIntegrationTest
./mvnw test        # full suite before PR
```

## Files touched

### Production

| File | Change |
|---|---|
| `services/iam/IamPolicyEvaluator.java` | condition ctx → `Map<String,List<String>>`; set-operator parse + eval; empty-set semantics |
| `core/common/IamConditionContextResolver.java` | return type multi-valued; new `dynamodb` branch; inject `Instance<DynamoDbService>` |
| `core/common/IamEnforcementFilter.java` | ctx map type; `aws:PrincipalArn` → singleton list |
| `services/iam/IamQueryHandler.java` | `extractContextEntries` → multi-valued, reads all `.member.N` |
| `services/dynamodb/DynamoDbConditionKeys.java` | **new** — static extractor |
| `services/dynamodb/DynamoDbKeyConditionParser.java` | **new** (or a narrow public method on existing code) — partition-key equality value from `KeyConditionExpression` |
| `services/appsync/graphql/auth/IamAuthValidator.java` | none expected (passes `null`) — compile-check only |

### Tests

`IamPolicyEvaluatorTest`, `IamConditionContextResolverTest`, `IamEnforcementFilterTest`, `IamEnforcementIntegrationTest` (extend); `DynamoDbConditionKeysTest` (new).

### Docs

- IAM condition-key support doc / matrix, if one exists (confirm during implementation).
- Draft a follow-up issue for Scan `LeadingKeys` + Transacts + PartiQL.
