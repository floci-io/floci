package io.github.hectorvent.floci.services.mediapackage;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaPackageIntegrationTest {

    private static String channelArn;

    @Test
    @Order(1)
    void createChannel() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"id": "integration-channel", "description": "test channel", "tags": {"team": "video"}}
                """)
        .when()
            .post("/channels")
        .then()
            .statusCode(200)
            .body("arn", containsString(":mediapackage:"))
            .body("arn", containsString(":channels/"))
            .body("id", equalTo("integration-channel"))
            .body("description", equalTo("test channel"))
            .body("createdAt", containsString("T"))
            .body("hlsIngest.ingestEndpoints", hasSize(2))
            .body("hlsIngest.ingestEndpoints[0].url", notNullValue())
            .body("tags.team", equalTo("video"))
            .extract();
        channelArn = response.path("arn");
    }

    @Test
    @Order(2)
    void describeChannel() {
        given()
        .when()
            .get("/channels/integration-channel")
        .then()
            .statusCode(200)
            .body("arn", equalTo(channelArn))
            .body("id", equalTo("integration-channel"));
    }

    @Test
    @Order(3)
    void duplicateIdRejected() {
        given()
            .contentType("application/json")
            .body("{\"id\": \"integration-channel\"}")
        .when()
            .post("/channels")
        .then()
            .statusCode(422)
            .body("__type", equalTo("UnprocessableEntityException"));
    }

    @Test
    @Order(4)
    void listTagsForChannel() {
        given()
        .when()
            .get("/tags/" + channelArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("video"));
    }

    @Test
    @Order(5)
    void updateChannel() {
        given()
            .contentType("application/json")
            .body("{\"description\": \"updated\"}")
        .when()
            .put("/channels/integration-channel")
        .then()
            .statusCode(200)
            .body("description", equalTo("updated"));
    }

    @Test
    @Order(6)
    void deleteChannel() {
        given()
        .when()
            .delete("/channels/integration-channel")
        .then()
            .statusCode(202);

        given()
        .when()
            .get("/channels/integration-channel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }
}
