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
