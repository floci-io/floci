package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Mappings written before base paths were canonicalised on write.
 *
 * <p>Such a record sits under the key {@code ""} while reporting {@code (none)} from its own field,
 * because {@link BasePathMapping} normalises in its constructor. No endpoint can create one now, so
 * the state is seeded through the same storage backend the service uses — the factory hands out one
 * backend per file.
 */
@QuarkusTest
class LegacyBasePathMappingIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "legacy-mapping.example.com";

    @Inject
    StorageFactory storageFactory;

    @Inject
    ApiGatewayService service;

    private StorageBackend<String, BasePathMapping> mappings() {
        return storageFactory.create("apigateway", "apigateway-mappings.json",
                new TypeReference<Map<String, BasePathMapping>>() {});
    }

    private void seedLegacyState() {
        service.createDomainName(REGION, Map.of("domainName", DOMAIN));
        // The canonical root, as writes produce it today.
        service.createBasePathMapping(REGION, DOMAIN, Map.of("restApiId", "api-canonical", "stage", "prod"));
        // And one the old write path left under the empty key; its field still reads "(none)".
        mappings().put(REGION + "::" + DOMAIN + "::", new BasePathMapping("", "api-legacy", "prod"));
    }

    @Test
    void eachRecordKeepsItsOwnIdAndDeletesItself() {
        seedLegacyState();

        String canonicalId = ApiGatewayController.apiMappingId("(none)");
        String legacyId = ApiGatewayController.apiMappingId("");
        assertNotEquals(canonicalId, legacyId);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings")
        .then()
            .statusCode(200)
            .body("items.apiMappingId", hasItem(canonicalId))
            .body("items.apiMappingId", hasItem(legacyId));

        // Each id resolves to its own record rather than to whichever matched first.
        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + legacyId)
        .then()
            .statusCode(200)
            .body("apiId", is("api-legacy"));

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + canonicalId)
        .then()
            .statusCode(200)
            .body("apiId", is("api-canonical"));

        // Deleting the legacy record removes that record, not the canonical root beside it.
        given()
        .when()
            .delete("/v2/domainnames/" + DOMAIN + "/apimappings/" + legacyId)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + legacyId)
        .then()
            .statusCode(404);

        given()
        .when()
            .get("/v2/domainnames/" + DOMAIN + "/apimappings/" + canonicalId)
        .then()
            .statusCode(200)
            .body("apiId", is("api-canonical"));
    }
}
