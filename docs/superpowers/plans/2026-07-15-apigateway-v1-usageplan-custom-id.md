# API Gateway v1 — UsagePlan Custom-ID and Tag Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `_custom_id_` tag support and full tag persistence to `CreateUsagePlan`, mirroring the existing `createRestApi` pattern exactly.

**Architecture:** Three-file change — add `tags` to the `UsagePlan` model, update `createUsagePlan` in the service to read tags and use `_custom_id_` as the ID, and update `toUsagePlanNode` in the controller to emit tags in responses. A new integration test class covers the behaviour. No new abstractions.

**Tech Stack:** Java 17, Quarkus, JAX-RS (Jakarta WS), Jackson, REST-assured + JUnit 5 (QuarkusTest)

## Global Constraints

- `_custom_id_` tag overrides the generated ID only when present and non-blank; otherwise `shortId(10)` is used.
- Tags are persisted on the `UsagePlan` entity and returned in all responses (`POST /usageplans`, `GET /usageplans`).
- Tags map defaults to an empty `HashMap` when absent from the request — never null.
- Implementation must be character-for-character identical in pattern to `createRestApi` (lines 171–183 of `ApiGatewayService.java`) and `toApiKeyNode` (controller).
- Run tests with: `./mvnw test -Dtest=ApiGatewayUsagePlanIntegrationTest -q`

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/model/UsagePlan.java` |
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java:710-727` (`createUsagePlan`) |
| Modify | `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java:1701-1706` (`toUsagePlanNode`) |
| Create | `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayUsagePlanIntegrationTest.java` |
| Modify | `docs/services/api-gateway.md` |

---

## Task 1: Add tags + `_custom_id_` support to UsagePlan (model, service, controller, tests)

**Files:**
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/model/UsagePlan.java`
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java:710-727`
- Modify: `src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java:1701-1706`
- Create: `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayUsagePlanIntegrationTest.java`

**Interfaces:**
- Produces: `UsagePlan.getTags()` / `UsagePlan.setTags(Map<String,String>)` — used by serialiser in controller

- [ ] **Step 1: Write the failing test class**

  Create `src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayUsagePlanIntegrationTest.java`:

  ```java
  package io.github.hectorvent.floci.services.apigateway;

  import io.quarkus.test.junit.QuarkusTest;
  import io.restassured.http.ContentType;
  import org.junit.jupiter.api.MethodOrderer;
  import org.junit.jupiter.api.Order;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.TestMethodOrder;

  import static io.restassured.RestAssured.given;
  import static org.hamcrest.Matchers.*;

  @QuarkusTest
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  class ApiGatewayUsagePlanIntegrationTest {

      @Test @Order(1)
      void createUsagePlan_customIdTag_usesTagValueAsPlanId() {
          String body = """
                  {"name":"my-plan","tags":{"_custom_id_":"my-plan-id","env":"test"}}
                  """;
          given()
                  .contentType(ContentType.JSON)
                  .body(body)
                  .when().post("/usageplans")
                  .then()
                  .statusCode(201)
                  .body("id", equalTo("my-plan-id"))
                  .body("name", equalTo("my-plan"))
                  .body("tags._custom_id_", equalTo("my-plan-id"))
                  .body("tags.env", equalTo("test"));
      }

      @Test @Order(2)
      void getUsagePlans_returnsTags() {
          given()
                  .when().get("/usageplans")
                  .then()
                  .statusCode(200)
                  .body("item.find { it.id == 'my-plan-id' }.tags._custom_id_", equalTo("my-plan-id"))
                  .body("item.find { it.id == 'my-plan-id' }.tags.env", equalTo("test"));
      }

      @Test @Order(3)
      void createUsagePlan_noCustomId_generatesRandomId() {
          String body = """
                  {"name":"random-plan"}
                  """;
          given()
                  .contentType(ContentType.JSON)
                  .body(body)
                  .when().post("/usageplans")
                  .then()
                  .statusCode(201)
                  .body("id", notNullValue())
                  .body("id", not(emptyString()));
      }
  }
  ```

- [ ] **Step 2: Run tests to verify they fail**

  ```bash
  ./mvnw test -Dtest=ApiGatewayUsagePlanIntegrationTest -q
  ```

  Expected: `BUILD FAILURE` — Order 1 fails because `id` is a random value (not `"my-plan-id"`) and `tags` is absent from the response.

- [ ] **Step 3: Replace `UsagePlan.java` with tags support**

  Full file replacement (`src/main/java/io/github/hectorvent/floci/services/apigateway/model/UsagePlan.java`):

  ```java
  package io.github.hectorvent.floci.services.apigateway.model;

  import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
  import io.quarkus.runtime.annotations.RegisterForReflection;

  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;

  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public class UsagePlan {
      private String id;
      private String name;
      private String description;
      private List<ApiStage> apiStages = new ArrayList<>();
      private Map<String, String> tags = new HashMap<>();

      public UsagePlan() {}

      public String getId() { return id; }
      public void setId(String id) { this.id = id; }

      public String getName() { return name; }
      public void setName(String name) { this.name = name; }

      public String getDescription() { return description; }
      public void setDescription(String description) { this.description = description; }

      public List<ApiStage> getApiStages() { return apiStages; }
      public void setApiStages(List<ApiStage> apiStages) { this.apiStages = apiStages; }

      public Map<String, String> getTags() { return tags; }
      public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }

      @RegisterForReflection
      public record ApiStage(String apiId, String stage) {}
  }
  ```

- [ ] **Step 4: Update `createUsagePlan` in `ApiGatewayService.java`**

  The current method body (lines 710–727) reads:
  ```java
  public UsagePlan createUsagePlan(String region, Map<String, Object> request) {
      UsagePlan plan = new UsagePlan();
      plan.setId(shortId(10));
      plan.setName((String) request.get("name"));
      plan.setDescription((String) request.get("description"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> apiStages = (List<Map<String, Object>>) request.get("apiStages");
      if (apiStages != null) {
          for (Map<String, Object> as : apiStages) {
              plan.getApiStages().add(new UsagePlan.ApiStage((String) as.get("apiId"), (String) as.get("stage")));
          }
      }

      usagePlanStore.put(usagePlanKey(region, plan.getId()), plan);
      LOG.infov("Created Usage Plan {0}", plan.getId());
      return plan;
  }
  ```

  Replace with:
  ```java
  public UsagePlan createUsagePlan(String region, Map<String, Object> request) {
      @SuppressWarnings("unchecked")
      Map<String, String> tags = request.get("tags") instanceof Map<?, ?> m
              ? (Map<String, String>) m : new HashMap<>();

      String customId = tags.get("_custom_id_");
      String planId = (customId != null && !customId.isBlank()) ? customId : shortId(10);

      UsagePlan plan = new UsagePlan();
      plan.setId(planId);
      plan.setName((String) request.get("name"));
      plan.setDescription((String) request.get("description"));
      plan.setTags(tags);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> apiStages = (List<Map<String, Object>>) request.get("apiStages");
      if (apiStages != null) {
          for (Map<String, Object> as : apiStages) {
              plan.getApiStages().add(new UsagePlan.ApiStage((String) as.get("apiId"), (String) as.get("stage")));
          }
      }

      usagePlanStore.put(usagePlanKey(region, plan.getId()), plan);
      LOG.infov("Created Usage Plan {0}", plan.getId());
      return plan;
  }
  ```

  Note: `HashMap` is already imported in `ApiGatewayService.java` (used elsewhere).

- [ ] **Step 5: Update `toUsagePlanNode` in `ApiGatewayController.java`**

  The current method body (lines 1701–1706) reads:
  ```java
  private ObjectNode toUsagePlanNode(UsagePlan p) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("id", p.getId());
      node.put("name", p.getName());
      return node;
  }
  ```

  Replace with:
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

- [ ] **Step 6: Run tests to verify all 3 pass**

  ```bash
  ./mvnw test -Dtest=ApiGatewayUsagePlanIntegrationTest -q
  ```

  Expected: `BUILD SUCCESS` — Tests run: 3, Failures: 0, Errors: 0.

- [ ] **Step 7: Commit**

  ```bash
  git add src/main/java/io/github/hectorvent/floci/services/apigateway/model/UsagePlan.java \
          src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayService.java \
          src/main/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayController.java \
          src/test/java/io/github/hectorvent/floci/services/apigateway/ApiGatewayUsagePlanIntegrationTest.java
  git commit -m "feat(apigateway): add _custom_id_ tag and tag persistence to CreateUsagePlan"
  ```

---

## Task 2: Update service documentation

**Files:**
- Modify: `docs/services/api-gateway.md`

- [ ] **Step 1: Add `_custom_id_` and tags note to the Usage Plans section**

  In `docs/services/api-gateway.md`, locate the `---` separator on line 103 (immediately before `## Configuration`). Insert the following block of text on the line immediately before that separator (i.e. after the closing ` ``` ` of the bash examples block):

  ```
  ### Usage Plan Tags and Custom IDs

  Usage plans support arbitrary tags and the `_custom_id_` tag for deterministic IDs:

  (bash code block start)
  # Create a usage plan with a custom ID and additional tags
  aws apigateway create-usage-plan \
    --name "my-plan" \
    --tags '{"_custom_id_":"my-plan-id","env":"staging"}' \
    --endpoint-url $AWS_ENDPOINT_URL

  # The plan is now accessible at its custom ID
  aws apigateway get-usage-plans --endpoint-url $AWS_ENDPOINT_URL
  (bash code block end)

  When `_custom_id_` is present in the `tags` map, it is used as the usage plan's `id`.
  Tags are persisted and returned in all usage plan responses.
  ```

  Replace `(bash code block start)` / `(bash code block end)` with triple backtick + `bash` and triple backtick respectively when editing the file.

- [ ] **Step 2: Commit**

  ```bash
  git add docs/services/api-gateway.md
  git commit -m "docs(apigateway): document UsagePlan _custom_id_ tag and tag persistence"
  ```
