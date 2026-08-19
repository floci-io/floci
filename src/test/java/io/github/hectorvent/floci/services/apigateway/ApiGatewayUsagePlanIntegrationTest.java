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
    void createUsagePlan_flociOverrideIdTag_usesTagValueAsPlanId() {
        String body = """
                {"name":"my-plan","tags":{"floci:override-id":"my-plan-id","env":"test"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/usageplans")
                .then()
                .statusCode(201)
                .body("id", equalTo("my-plan-id"))
                .body("name", equalTo("my-plan"))
                .body("tags.'floci:override-id'", nullValue())
                .body("tags._custom_id_", nullValue())
                .body("tags.env", equalTo("test"));
    }

    @Test @Order(2)
    void getUsagePlans_returnsTagsWithoutReservedKeys() {
        given()
                .when().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.find { it.id == 'my-plan-id' }.tags.env", equalTo("test"))
                .body("item.find { it.id == 'my-plan-id' }.tags.'floci:override-id'", nullValue())
                .body("item.find { it.id == 'my-plan-id' }.tags._custom_id_", nullValue());
    }

    @Test @Order(4)
    void createUsagePlan_deprecatedCustomIdTag_stillHonoredButNotPersisted() {
        String body = """
                {"name":"legacy-plan","tags":{"_custom_id_":"legacy-plan-id","env":"legacy"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/usageplans")
                .then()
                .statusCode(201)
                .body("id", equalTo("legacy-plan-id"))
                .body("tags._custom_id_", nullValue())
                .body("tags.env", equalTo("legacy"));
    }

    @Test @Order(5)
    void createUsagePlan_bothOverrideKeys_prefersFlociOverrideId() {
        String body = """
                {"name":"both-keys-plan","tags":{"floci:override-id":"WINNERPLAN","_custom_id_":"LOSERPLAN"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/usageplans")
                .then()
                .statusCode(201)
                .body("id", equalTo("WINNERPLAN"));
    }

    @Test @Order(6)
    void createUsagePlan_blankOverrideId_isRejected() {
        String body = """
                {"name":"blank-override-plan","tags":{"floci:override-id":"   "}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/usageplans")
                .then()
                .statusCode(400)
                .body("message", containsString("must not be blank"));
    }

    @Test @Order(7)
    void createUsagePlan_overrideIdWithPathSeparator_isRejected() {
        String body = """
                {"name":"bad-override-plan","tags":{"floci:override-id":"has/slash"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/usageplans")
                .then()
                .statusCode(400)
                .body("message", containsString("unsupported characters"));
    }

    @Test @Order(8)
    void createUsagePlan_noOverrideId_generatesRandomId() {
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
