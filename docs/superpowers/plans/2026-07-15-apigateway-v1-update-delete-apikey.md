# API Gateway v1 — UpdateApiKey & DeleteApiKey Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `UpdateApiKey` (PATCH) and `DeleteApiKey` (DELETE) endpoints to the API Gateway v1 emulator, plus a `description` field on `ApiKey`, mirroring the AWS REST API exactly.

**Architecture:** Follow the established patchOperations pattern used by `updateRestApi`, `updateMethod`, etc. — the controller parses a `patchOperations` JSON array from the request body, passes it to a service method that applies `replace` ops field-by-field, then serialises the updated entity back. `deleteApiKey` already exists in the service layer; only the controller endpoint is missing.

**Tech Stack:** Java 17, Quarkus, JAX-RS (Jakarta WS), Jackson, REST-assured + JUnit 5 (QuarkusTest)

## Global Constraints

- All endpoints must mirror the AWS API Gateway v1 REST API contract exactly (HTTP verbs, paths, status codes, error shapes).
- `DELETE /apikeys/{apiKeyId}` → 202 Accepted (empty body); 404 NotFoundException if key not found.
- `PATCH /apikeys/{apiKeyId}` → 200 OK with full ApiKey JSON; 404 NotFoundException if key not found.
- Error shape must be `{"__type":"NotFoundException","message":"Invalid API Key identifier specified"}`.
- Patchable fields for UpdateApiKey: `/name`, `/description`, `/enabled` only (not `/value`).
- `description` is optional; omit the JSON field entirely when null (do not emit `"description": null`).
- Tests use `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` — new tests must use `@Order` values that maintain the correct execution sequence (update tests before delete tests).
- Run tests with: `./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q` from the repo root.

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/model/ApiKey.java` |
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java` |
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java` |
| Modify | `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayApiKeyIntegrationTest.java` |
| Modify | `docs/services/api-gateway.md` |

---

## Task 1: Add `description` to ApiKey model and wire into create/read

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/model/ApiKey.java`
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java:655-672` (createApiKey)
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java:1659-1670` (toApiKeyNode)

**Interfaces:**
- Produces: `ApiKey.getDescription()` / `ApiKey.setDescription(String)` — used by Tasks 2 and 3

- [ ] **Step 1: Add `description` field to `ApiKey.java`**

  The current `ApiKey.java` ends at line 42. Add the field and its getter/setter after `lastUpdatedDate`:

  ```java
  // in ApiKey.java — add after the lastUpdatedDate getter/setter (after line 38):
  private String description;

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  ```

  Full resulting class (replace entire file content):

  ```java
  package io.github.hectorvent.floci.services.apigateway.model;

  import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
  import io.quarkus.runtime.annotations.RegisterForReflection;

  import java.util.HashMap;
  import java.util.Map;

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public class ApiKey {
      private String id;
      private String name;
      private String value;
      private boolean enabled;
      private long createdDate;
      private long lastUpdatedDate;
      private String description;
      private Map<String, String> tags = new HashMap<>();

      public ApiKey() {}

      public String getId() { return id; }
      public void setId(String id) { this.id = id; }

      public String getName() { return name; }
      public void setName(String name) { this.name = name; }

      public String getValue() { return value; }
      public void setValue(String value) { this.value = value; }

      public boolean isEnabled() { return enabled; }
      public void setEnabled(boolean enabled) { this.enabled = enabled; }

      public long getCreatedDate() { return createdDate; }
      public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

      public long getLastUpdatedDate() { return lastUpdatedDate; }
      public void setLastUpdatedDate(long lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

      public String getDescription() { return description; }
      public void setDescription(String description) { this.description = description; }

      public Map<String, String> getTags() { return tags; }
      public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
  }
  ```

- [ ] **Step 2: Read `description` in `ApiGatewayService.createApiKey`**

  In `ApiGatewayService.java`, `createApiKey` starts at line 655. Add one line after `setLastUpdatedDate` (currently line 662):

  ```java
  // Add after: apiKey.setLastUpdatedDate(apiKey.getCreatedDate());
  apiKey.setDescription((String) request.get("description"));
  ```

- [ ] **Step 3: Emit `description` in `ApiGatewayController.toApiKeyNode`**

  The method currently lives at line 1659. Replace its body so description is emitted when non-null:

  ```java
  private ObjectNode toApiKeyNode(ApiKey k) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("id", k.getId());
      node.put("name", k.getName());
      node.put("value", k.getValue());
      node.put("enabled", k.isEnabled());
      if (k.getDescription() != null) {
          node.put("description", k.getDescription());
      }
      if (k.getTags() != null && !k.getTags().isEmpty()) {
          ObjectNode tags = node.putObject("tags");
          k.getTags().forEach(tags::put);
      }
      return node;
  }
  ```

- [ ] **Step 4: Run existing tests to verify no regressions**

  ```bash
  ./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q
  ```

  Expected: `BUILD SUCCESS` — all 5 existing tests pass. The description field is nullable; existing tests do not send it, so it is omitted from responses and assertions remain valid.

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/io/github/hectorvent/floci/services/apigateway/model/ApiKey.java \
          src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java \
          src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java
  git commit -m "feat(apigateway): add description field to ApiKey model"
  ```

---

## Task 2: Implement UpdateApiKey (service + controller + tests)

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java` (add `updateApiKey` after `deleteApiKey` at line 688)
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java` (add `PATCH /apikeys/{apiKeyId}` after `GET /apikeys/{apiKeyId}` at line 643)
- Modify: `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayApiKeyIntegrationTest.java` (add Orders 6–9)

**Interfaces:**
- Consumes: `ApiKey.setDescription(String)`, `ApiKey.setName(String)`, `ApiKey.setEnabled(boolean)` from Task 1
- Produces: `ApiGatewayService.updateApiKey(String region, String apiKeyId, List<Map<String,String>> patchOperations)` — returns `ApiKey`

- [ ] **Step 1: Write four failing tests (Orders 6–9)**

  Append these four methods to `ApiGatewayApiKeyIntegrationTest.java` before the closing `}`:

  ```java
  @Test @Order(6)
  void updateApiKeyName() {
      String body = """
              {"patchOperations":[{"op":"replace","path":"/name","value":"updated-name"}]}
              """;
      given()
              .contentType(ContentType.JSON)
              .body(body)
              .when().patch("/apikeys/" + apiKeyId)
              .then()
              .statusCode(200)
              .body("id", equalTo(apiKeyId))
              .body("name", equalTo("updated-name"));
  }

  @Test @Order(7)
  void updateApiKeyDescription() {
      String body = """
              {"patchOperations":[{"op":"replace","path":"/description","value":"a test key"}]}
              """;
      given()
              .contentType(ContentType.JSON)
              .body(body)
              .when().patch("/apikeys/" + apiKeyId)
              .then()
              .statusCode(200)
              .body("description", equalTo("a test key"));
  }

  @Test @Order(8)
  void updateApiKeyEnabled() {
      String body = """
              {"patchOperations":[{"op":"replace","path":"/enabled","value":"false"}]}
              """;
      given()
              .contentType(ContentType.JSON)
              .body(body)
              .when().patch("/apikeys/" + apiKeyId)
              .then()
              .statusCode(200)
              .body("enabled", equalTo(false));
  }

  @Test @Order(9)
  void updateApiKeyNotFound() {
      String body = """
              {"patchOperations":[{"op":"replace","path":"/name","value":"x"}]}
              """;
      given()
              .contentType(ContentType.JSON)
              .body(body)
              .when().patch("/apikeys/doesnotexist")
              .then()
              .statusCode(404)
              .contentType(ContentType.JSON)
              .body("__type", equalTo("NotFoundException"))
              .body("message", equalTo("Invalid API Key identifier specified"));
  }
  ```

- [ ] **Step 2: Run tests to verify Orders 6–9 fail**

  ```bash
  ./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q
  ```

  Expected: `BUILD FAILURE` — Orders 1–5 pass, Orders 6–9 fail (405 Method Not Allowed or 404 — no PATCH handler exists yet).

- [ ] **Step 3: Add `updateApiKey` service method**

  In `ApiGatewayService.java`, insert this method after `deleteApiKey` (after line 688):

  ```java
  public ApiKey updateApiKey(String region, String apiKeyId, List<Map<String, String>> patchOperations) {
      ApiKey key = getApiKey(region, apiKeyId);
      if (patchOperations != null) {
          for (Map<String, String> op : patchOperations) {
              if (!"replace".equals(op.get("op"))) continue;
              switch (op.getOrDefault("path", "")) {
                  case "/name"        -> key.setName(op.get("value"));
                  case "/description" -> key.setDescription(op.get("value"));
                  case "/enabled"     -> key.setEnabled(Boolean.parseBoolean(op.get("value")));
              }
          }
      }
      key.setLastUpdatedDate(System.currentTimeMillis() / 1000L);
      apiKeyStore.put(apiKeyGlobalKey(region, apiKeyId), key);
      return key;
  }
  ```

- [ ] **Step 4: Add `PATCH /apikeys/{apiKeyId}` controller endpoint**

  In `ApiGatewayController.java`, insert this block immediately after the `GET /apikeys/{apiKeyId}` handler (after line 643, before the `@POST /usageplans` block):

  ```java
  @PATCH
  @Path("/apikeys/{apiKeyId}")
  public Response updateApiKey(@Context HttpHeaders headers,
                               @PathParam("apiKeyId") String apiKeyId,
                               String body) {
      String region = regionResolver.resolveRegion(headers);
      try {
          com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body).path("patchOperations");
          @SuppressWarnings("unchecked")
          List<Map<String, String>> patchOperations = objectMapper.convertValue(node, List.class);
          ApiKey key = service.updateApiKey(region, apiKeyId, patchOperations);
          return Response.ok(toApiKeyNode(key).toString()).type(MediaType.APPLICATION_JSON).build();
      } catch (IOException e) {
          throw new AwsException("BadRequestException", e.getMessage(), 400);
      }
  }
  ```

- [ ] **Step 5: Run tests to verify Orders 1–9 all pass**

  ```bash
  ./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q
  ```

  Expected: `BUILD SUCCESS` — all 9 tests pass.

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java \
          src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java \
          src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayApiKeyIntegrationTest.java
  git commit -m "feat(apigateway): implement UpdateApiKey (PATCH /apikeys/{apiKeyId})"
  ```

---

## Task 3: Implement DeleteApiKey controller endpoint + tests

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java` (add `DELETE /apikeys/{apiKeyId}` after the PATCH endpoint added in Task 2)
- Modify: `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayApiKeyIntegrationTest.java` (add Orders 10–11)

**Interfaces:**
- Consumes: `ApiGatewayService.deleteApiKey(String region, String apiKeyId)` — already exists, throws `NotFoundException` (404) if key absent

- [ ] **Step 1: Write two failing tests (Orders 10–11)**

  Append these two methods to `ApiGatewayApiKeyIntegrationTest.java` before the closing `}`:

  ```java
  @Test @Order(10)
  void deleteApiKey() {
      given()
              .when().delete("/apikeys/" + apiKeyId)
              .then()
              .statusCode(202);
  }

  @Test @Order(11)
  void deleteApiKeyAlreadyGone() {
      given()
              .when().delete("/apikeys/" + apiKeyId)
              .then()
              .statusCode(404)
              .contentType(ContentType.JSON)
              .body("__type", equalTo("NotFoundException"))
              .body("message", equalTo("Invalid API Key identifier specified"));
  }
  ```

- [ ] **Step 2: Run tests to verify Orders 10–11 fail**

  ```bash
  ./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q
  ```

  Expected: `BUILD FAILURE` — Orders 1–9 pass, Orders 10–11 fail (no DELETE handler exists yet).

- [ ] **Step 3: Add `DELETE /apikeys/{apiKeyId}` controller endpoint**

  In `ApiGatewayController.java`, insert this block immediately after the `PATCH /apikeys/{apiKeyId}` handler added in Task 2 (before the `@POST /usageplans` block):

  ```java
  @DELETE
  @Path("/apikeys/{apiKeyId}")
  public Response deleteApiKey(@Context HttpHeaders headers,
                               @PathParam("apiKeyId") String apiKeyId) {
      String region = regionResolver.resolveRegion(headers);
      service.deleteApiKey(region, apiKeyId);
      return Response.accepted().build();
  }
  ```

- [ ] **Step 4: Run tests to verify all 11 pass**

  ```bash
  ./mvnw test -Dtest=ApiGatewayApiKeyIntegrationTest -q
  ```

  Expected: `BUILD SUCCESS` — all 11 tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java \
          src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayApiKeyIntegrationTest.java
  git commit -m "feat(apigateway): implement DeleteApiKey (DELETE /apikeys/{apiKeyId})"
  ```

---

## Task 4: Update service documentation

**Files:**
- Modify: `docs/services/api-gateway.md:23` (Supported Operations — API Keys row)
- Modify: `docs/services/api-gateway.md:39` (Not Implemented list — API key detail line)

- [ ] **Step 1: Update the Supported Operations table row for API Keys**

  Find line 23 in `docs/services/api-gateway.md`:

  ```markdown
  | **API Keys** | CreateApiKey, GetApiKeys |
  ```

  Replace with:

  ```markdown
  | **API Keys** | CreateApiKey, GetApiKey, GetApiKeys, UpdateApiKey, DeleteApiKey |
  ```

- [ ] **Step 2: Update the Not Implemented list**

  Find line 39 in `docs/services/api-gateway.md`:

  ```markdown
  - API key detail: `GetApiKey`, `UpdateApiKey`, `DeleteApiKey`, `ImportApiKeys`
  ```

  Replace with:

  ```markdown
  - API key detail: `ImportApiKeys`
  ```

- [ ] **Step 3: Commit**

  ```bash
  git add docs/services/api-gateway.md
  git commit -m "docs(apigateway): reflect UpdateApiKey and DeleteApiKey as implemented"
  ```
