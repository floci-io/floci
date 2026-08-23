package io.github.hectorvent.floci.services.bedrock;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockIntegrationTest {

    private static String guardrailId;
    private static String guardrailArn;
    private static String guardrailVersion;

    @Test
    @Order(1)
    void createGuardrail() {
        var response = given()
            .contentType("application/json")
            .body("""
                {
                  "name": "integration-guardrail",
                  "description": "blocks the loud stuff",
                  "blockedInputMessaging": "input blocked",
                  "blockedOutputsMessaging": "output blocked",
                  "topicPolicyConfig": {
                    "topicsConfig": [
                      {"name": "Investments", "definition": "Investment advice", "type": "DENY"}
                    ]
                  },
                  "contentPolicyConfig": {
                    "filtersConfig": [
                      {"type": "HATE", "inputStrength": "HIGH", "outputStrength": "HIGH"}
                    ]
                  },
                  "wordPolicyConfig": {
                    "wordsConfig": [{"text": "forbidden"}],
                    "managedWordListsConfig": [{"type": "PROFANITY"}]
                  },
                  "sensitiveInformationPolicyConfig": {
                    "piiEntitiesConfig": [{"type": "EMAIL", "action": "BLOCK"}]
                  },
                  "tags": [{"key": "team", "value": "ai"}]
                }
                """)
        .when()
            .post("/guardrails")
        .then()
            .statusCode(202)
            .body("guardrailId", notNullValue())
            .body("guardrailArn", containsString(":bedrock:"))
            .body("guardrailArn", containsString(":guardrail/"))
            .body("version", equalTo("DRAFT"))
            .body("createdAt", notNullValue())
            .extract();
        guardrailId = response.path("guardrailId");
        guardrailArn = response.path("guardrailArn");
    }

    @Test
    @Order(2)
    void getGuardrailReturnsReadyAndEchoesRequest() {
        given()
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(200)
            .body("guardrailId", equalTo(guardrailId))
            .body("guardrailArn", equalTo(guardrailArn))
            .body("name", equalTo("integration-guardrail"))
            .body("description", equalTo("blocks the loud stuff"))
            .body("version", equalTo("DRAFT"))
            .body("status", equalTo("READY"))
            .body("blockedInputMessaging", equalTo("input blocked"))
            .body("blockedOutputsMessaging", equalTo("output blocked"))
            .body("topicPolicy.topics[0].name", equalTo("Investments"))
            .body("topicPolicy.topics[0].type", equalTo("DENY"))
            .body("contentPolicy.filters[0].type", equalTo("HATE"))
            .body("contentPolicy.filters[0].inputStrength", equalTo("HIGH"))
            .body("wordPolicy.words[0].text", equalTo("forbidden"))
            .body("wordPolicy.managedWordLists[0].type", equalTo("PROFANITY"))
            .body("sensitiveInformationPolicy.piiEntities[0].type", equalTo("EMAIL"))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    @Order(3)
    void getGuardrailByArnResolvesTheSameResource() {
        String encodedArn = java.net.URLEncoder.encode(guardrailArn, java.nio.charset.StandardCharsets.UTF_8);
        given()
            .urlEncodingEnabled(false)
        .when()
            .get("/guardrails/" + encodedArn)
        .then()
            .statusCode(200)
            .body("guardrailId", equalTo(guardrailId))
            .body("status", equalTo("READY"));
    }

    @Test
    @Order(4)
    void listGuardrailsIncludesTheDraft() {
        given()
        .when()
            .get("/guardrails")
        .then()
            .statusCode(200)
            .body("guardrails.id", hasItem(guardrailId))
            .body("guardrails.find { it.id == '" + guardrailId + "' }.status", equalTo("READY"))
            .body("guardrails.find { it.id == '" + guardrailId + "' }.version", equalTo("DRAFT"))
            .body("guardrails.find { it.id == '" + guardrailId + "' }.arn", equalTo(guardrailArn));
    }

    @Test
    @Order(5)
    void listTagsForGuardrail() {
        given()
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + guardrailArn + "\"}")
        .when()
            .post("/listTagsForResource")
        .then()
            .statusCode(200)
            .body("tags.find { it.key == 'team' }.value", equalTo("ai"));
    }

    @Test
    @Order(6)
    void tagAndUntagGuardrail() {
        given()
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + guardrailArn + "\", \"tags\": [{\"key\": \"env\", \"value\": \"test\"}]}")
        .when()
            .post("/tagResource")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + guardrailArn + "\"}")
        .when()
            .post("/listTagsForResource")
        .then()
            .statusCode(200)
            .body("tags.find { it.key == 'env' }.value", equalTo("test"));

        given()
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + guardrailArn + "\", \"tagKeys\": [\"env\"]}")
        .when()
            .post("/untagResource")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + guardrailArn + "\"}")
        .when()
            .post("/listTagsForResource")
        .then()
            .statusCode(200)
            .body("tags.find { it.key == 'env' }", nullValue())
            .body("tags.find { it.key == 'team' }.value", equalTo("ai"));
    }

    @Test
    @Order(7)
    void updateGuardrail() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "name": "integration-guardrail",
                  "description": "now with fewer topics",
                  "blockedInputMessaging": "input blocked v2",
                  "blockedOutputsMessaging": "output blocked v2",
                  "contentPolicyConfig": {
                    "filtersConfig": [
                      {"type": "VIOLENCE", "inputStrength": "MEDIUM", "outputStrength": "LOW"}
                    ]
                  }
                }
                """)
        .when()
            .put("/guardrails/" + guardrailId)
        .then()
            .statusCode(202)
            .body("guardrailId", equalTo(guardrailId))
            .body("guardrailArn", equalTo(guardrailArn))
            .body("version", equalTo("DRAFT"))
            .body("updatedAt", notNullValue());

        given()
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(200)
            .body("description", equalTo("now with fewer topics"))
            .body("blockedInputMessaging", equalTo("input blocked v2"))
            .body("contentPolicy.filters[0].type", equalTo("VIOLENCE"))
            .body("topicPolicy", nullValue())
            .body("status", equalTo("READY"));
    }

    @Test
    @Order(8)
    void createGuardrailVersion() {
        guardrailVersion = given()
            .contentType("application/json")
            .body("{\"description\": \"first cut\"}")
        .when()
            .post("/guardrails/" + guardrailId)
        .then()
            .statusCode(202)
            .body("guardrailId", equalTo(guardrailId))
            .body("version", equalTo("1"))
            .extract().path("version");

        given()
            .queryParam("guardrailVersion", guardrailVersion)
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(200)
            .body("version", equalTo("1"))
            .body("status", equalTo("READY"))
            .body("description", equalTo("first cut"))
            .body("contentPolicy.filters[0].type", equalTo("VIOLENCE"));
    }

    @Test
    @Order(9)
    void listGuardrailsByIdentifierReturnsEveryVersion() {
        given()
            .queryParam("guardrailIdentifier", guardrailId)
        .when()
            .get("/guardrails")
        .then()
            .statusCode(200)
            .body("guardrails.version", hasItem("DRAFT"))
            .body("guardrails.version", hasItem("1"));
    }

    @Test
    @Order(10)
    void getMissingGuardrailReturnsResourceNotFound() {
        given()
        .when()
            .get("/guardrails/doesnotexist1")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
    void duplicateNameReturnsConflict() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "name": "integration-guardrail",
                  "blockedInputMessaging": "input blocked",
                  "blockedOutputsMessaging": "output blocked"
                }
                """)
        .when()
            .post("/guardrails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(12)
    void createWithoutRequiredMessagingReturnsValidationException() {
        given()
            .contentType("application/json")
            .body("{\"name\": \"missing-messaging\"}")
        .when()
            .post("/guardrails")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(13)
    void deleteSingleVersionLeavesTheDraft() {
        given()
            .queryParam("guardrailVersion", "1")
        .when()
            .delete("/guardrails/" + guardrailId)
        .then()
            .statusCode(202);

        given()
            .queryParam("guardrailVersion", "1")
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(200)
            .body("version", equalTo("DRAFT"));
    }

    @Test
    @Order(14)
    void deleteGuardrail() {
        given()
        .when()
            .delete("/guardrails/" + guardrailId)
        .then()
            .statusCode(202);

        given()
        .when()
            .get("/guardrails/" + guardrailId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void deleteMissingGuardrailReturnsResourceNotFound() {
        given()
        .when()
            .delete("/guardrails/" + guardrailId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
