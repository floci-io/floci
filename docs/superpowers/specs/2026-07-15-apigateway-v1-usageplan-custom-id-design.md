# Design: API Gateway v1 — UsagePlan `_custom_id_` Tag and Tag Persistence

**Date:** 2026-07-15
**Branch:** `feat/apigateway-v1-key-management`

## Summary

Add `_custom_id_` tag support to `CreateUsagePlan` so callers can control the usage plan's ID. Also add full tag persistence and return tags in all usage plan responses — mirroring exactly what `createRestApi` already does.

## Motivation

`createRestApi` supports a `_custom_id_` tag that lets callers set a deterministic ID at creation time. This is essential for reproducible local-dev and test environments where resource IDs are referenced in configuration. `createUsagePlan` currently generates an opaque random ID with no way to control it. Users also want to attach arbitrary tags to usage plans for identification and filtering.

## Current State

- `UsagePlan` model: `id`, `name`, `description`, `apiStages` — no `tags` field
- `createUsagePlan`: calls `shortId(10)` unconditionally; never reads `tags` from the request
- `toUsagePlanNode`: emits only `id` and `name`

## Changes

### 1. Model — `UsagePlan.java`

Add `tags` field with getter/setter, default empty `HashMap`. Mirrors `ApiKey` and `RestApi`.

```java
private Map<String, String> tags = new HashMap<>();

public Map<String, String> getTags() { return tags; }
public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
```

Add `import java.util.HashMap` and `import java.util.Map`.

### 2. Service — `ApiGatewayService.createUsagePlan`

Replace `plan.setId(shortId(10))` with the same pattern used in `createRestApi` (lines 171–176):

```java
@SuppressWarnings("unchecked")
Map<String, String> tags = request.get("tags") instanceof Map<?, ?> m
        ? (Map<String, String>) m : new HashMap<>();

String customId = tags.get("_custom_id_");
String planId = (customId != null && !customId.isBlank()) ? customId : shortId(10);

plan.setId(planId);
// ... existing name/description/apiStages lines ...
plan.setTags(tags);
```

### 3. Controller — `ApiGatewayController.toUsagePlanNode`

Emit `tags` when non-empty. Mirrors `toApiKeyNode`:

```java
private ObjectNode toUsagePlanNode(UsagePlan p) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("id", p.getId());
    node.put("name", p.getName());
    if (p.getTags() != null && !p.getTags().isEmpty()) {
        ObjectNode tags = node.putObject("tags");
        p.getTags().forEach(tags::put);
    }
    return node;
}
```

### 4. Tests

New integration test class `ApiGatewayUsagePlanIntegrationTest` (or extend existing usage plan coverage in `ApiGatewayIntegrationTest`). Key cases:

- **`createUsagePlan_customIdTag_usesTagValueAsPlanId`**: POST `/usageplans` with `{"name":"p","tags":{"_custom_id_":"my-plan-id"}}` → 201, `id == "my-plan-id"`, `tags._custom_id_ == "my-plan-id"`
- **`getUsagePlans_returnsTags`**: GET `/usageplans` → response includes `tags` for the plan created above
- **`createUsagePlan_noCustomId_generatesRandomId`**: POST without `_custom_id_` → 201, `id` is non-null and non-empty (random)

### 5. Docs — `docs/services/api-gateway.md`

Add a note under the Usage Plans section (or in the existing `_custom_id_` documentation area) that usage plans support the `_custom_id_` tag and full tag persistence, same as REST APIs.

## AWS API Compatibility

The AWS `CreateUsagePlan` API accepts a `tags` map in the request body and returns it in the response. This change brings the emulator into alignment with that contract. `_custom_id_` is a floci-specific extension (not an AWS concept) and is stored alongside other tags.

## What Does Not Change

- `deleteUsagePlan`, `getUsagePlans` — no changes needed (service methods already work; `toUsagePlanNode` update covers serialisation)
- `createUsagePlanKey`, `UsagePlanKey` model — out of scope
- No changes to the `_custom_id_` pattern itself — this is an exact copy, not a generalisation
