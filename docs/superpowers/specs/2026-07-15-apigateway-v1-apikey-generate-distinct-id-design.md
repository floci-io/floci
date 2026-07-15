# Design: API Gateway v1 — Respect `generateDistinctId` in CreateApiKey

**Date:** 2026-07-15
**Branch:** `feat/apigateway-v1-key-management`

## Summary

Fix `CreateApiKey` to honour the `generateDistinctId` request parameter. When `false`, the key's `id` and `value` must be identical. Currently the parameter is silently ignored and `id` / `value` are always distinct.

## AWS Behaviour

The `generateDistinctId` boolean controls whether the key identifier (`id`) is distinct from the key value (`value`):

| `generateDistinctId` | `id` | `value` |
|---|---|---|
| `true` (default, when absent) | opaque short token (`shortId(10)`) | separate UUID-derived secret |
| `false` | same as `value` | caller-supplied value OR generated UUID-derived string |

When `false` and a `value` is explicitly provided: use that value for both `id` and `value`.
When `false` and no `value` is provided: generate a UUID-derived string and use it for both.

## Current State

`ApiGatewayService.createApiKey` (line 655):
- `id` always set to `shortId(10)`
- `value` always set to caller-provided value or random UUID
- `generateDistinctId` is never read from the request map

## Change

### `ApiGatewayService.createApiKey`

Replace the current unconditional id/value assignment with a conditional block:

```java
boolean generateDistinctId = !Boolean.FALSE.equals(request.get("generateDistinctId"));
String suppliedValue = (String) request.get("value");

if (!generateDistinctId) {
    String sharedValue = (suppliedValue != null && !suppliedValue.isBlank())
            ? suppliedValue
            : UUID.randomUUID().toString().replace("-", "");
    apiKey.setId(sharedValue);
    apiKey.setValue(sharedValue);
} else {
    apiKey.setId(shortId(10));
    apiKey.setValue(suppliedValue != null ? suppliedValue : UUID.randomUUID().toString().replace("-", ""));
}
```

No other fields change. No new model fields required.

### Test — `ApiGatewayApiKeyIntegrationTest`

One new two-step test appended after the existing Order 11. The POST 201 response always includes `value` (via `toApiKeyNode`), but the clearest assertion pattern is to extract `id` from the create response and then re-fetch with `includeValue=true` to verify `value == id`:

```java
@Test @Order(12)
void createApiKey_generateDistinctIdFalse_idEqualsValue() {
    String body = """
            {"name":"shared-id-key","enabled":true,"generateDistinctId":false}
            """;
    String sharedId = given()
            .contentType(ContentType.JSON)
            .body(body)
            .when().post("/apikeys")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .extract().path("id");

    given()
            .when().get("/apikeys/" + sharedId + "?includeValue=true")
            .then()
            .statusCode(200)
            .body("id", equalTo(sharedId))
            .body("value", equalTo(sharedId));
}
```

### Docs — `docs/services/api-gateway.md`

Add a one-line note in the API Keys section (or as a sub-note under the supported operations table) confirming `generateDistinctId` is respected:

> `generateDistinctId` is honoured: when `false`, the key's `id` and `value` are set to the same generated (or caller-supplied) string.

## What Does Not Change

- `generateDistinctId=true` (or absent) path is unchanged — existing tests continue to pass
- No model changes
- No controller changes beyond the existing `toApiKeyNode` value-stripping behaviour
