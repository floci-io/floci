# Design: API Gateway v1 — UpdateApiKey and DeleteApiKey

**Date:** 2026-07-15
**Branch:** `feat/apigateway-v1-key-management`

## Summary

Add `UpdateApiKey` (`PATCH /apikeys/{apiKeyId}`) and `DeleteApiKey` (`DELETE /apikeys/{apiKeyId}`) endpoints to the API Gateway v1 emulator, mirroring the AWS REST API exactly. Also add `description` to the `ApiKey` model.

## Current State

The following already exist:
- `ApiKey` model: `id`, `name`, `value`, `enabled`, `createdDate`, `lastUpdatedDate`, `tags`
- Service: `createApiKey`, `getApiKey`, `getApiKeys`, `deleteApiKey` (no controller endpoint yet)
- Controller: `POST /apikeys`, `GET /apikeys`, `GET /apikeys/{apiKeyId}`

## Changes

### 1. Model — `ApiKey.java`

Add `description` field with getter/setter.

- `createApiKey` reads it from `request.get("description")` (optional, may be null)
- `toApiKeyNode` emits `description` only when non-null, consistent with AWS behavior

### 2. Service — `ApiGatewayService.java`

**`deleteApiKey`** — already implemented. No changes needed.

**`updateApiKey`** — new method:

```java
public ApiKey updateApiKey(String region, String apiKeyId, List<Map<String, String>> patchOperations) {
    ApiKey key = getApiKey(region, apiKeyId);  // throws 404 NotFoundException if missing
    for (Map<String, String> op : patchOperations) {
        if (!"replace".equals(op.get("op"))) continue;
        switch (op.getOrDefault("path", "")) {
            case "/name"        -> key.setName(op.get("value"));
            case "/description" -> key.setDescription(op.get("value"));
            case "/enabled"     -> key.setEnabled(Boolean.parseBoolean(op.get("value")));
        }
    }
    key.setLastUpdatedDate(System.currentTimeMillis() / 1000L);
    apiKeyStore.put(apiKeyGlobalKey(region, apiKeyId), key);
    return key;
}
```

Patchable paths: `/name`, `/description`, `/enabled`.
`/value` is intentionally excluded — changing a key's value silently breaks auth flows in emulator scenarios.

Follows the identical pattern used by `updateRestApi`, `updateMethod`, `updateIntegration`.

### 3. Controller — `ApiGatewayController.java`

Two new endpoints inserted after the `GET /apikeys/{apiKeyId}` block:

**DELETE /apikeys/{apiKeyId}**
- Calls `service.deleteApiKey(region, apiKeyId)`
- Returns `202 Accepted` with empty body
- 404 NotFoundException propagated automatically if key not found
- Matches behavior of `deleteRestApi` and `deleteUsagePlan`

**PATCH /apikeys/{apiKeyId}**
- Parses `patchOperations` array from JSON body
- Calls `service.updateApiKey(region, apiKeyId, patchOperations)`
- Returns `200 OK` with full updated `ApiKey` via `toApiKeyNode()` (value always included)
- 404 NotFoundException propagated automatically if key not found
- Matches pattern of `updateRestApi`

**`toApiKeyNode` update:** emit `description` when non-null.

### 4. Tests — `ApiGatewayApiKeyIntegrationTest.java`

Extend the existing ordered test class (currently orders 1–5) with:

| Order | Test | Assertion |
|-------|------|-----------|
| 6 | `updateApiKeyName` | PATCH `/name` → 200, `name` updated |
| 7 | `updateApiKeyDescription` | PATCH `/description` → 200, `description` present |
| 8 | `updateApiKeyEnabled` | PATCH `/enabled=false` → 200, `enabled` is false |
| 9 | `updateApiKeyNotFound` | PATCH unknown id → 404 NotFoundException |
| 10 | `deleteApiKey` | DELETE known id → 202 Accepted |
| 11 | `deleteApiKeyAlreadyGone` | DELETE same id again → 404 NotFoundException |

## AWS API Contract

| Operation | Method | Path | Success | Error |
|-----------|--------|------|---------|-------|
| UpdateApiKey | PATCH | `/apikeys/{api_Key}` | 200 + ApiKey body | 404 NotFoundException |
| DeleteApiKey | DELETE | `/apikeys/{api_Key}` | 202 (empty) | 404 NotFoundException |

Request body for UpdateApiKey:
```json
{
  "patchOperations": [
    { "op": "replace", "path": "/name", "value": "new-name" }
  ]
}
```
