package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class IotDataIntegrationTest {

    @Test
    void classicShadowLifecycle() {
        given()
        .when()
            .get("/things/phase-five-thing/shadow")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType("application/json")
            .body("{\"state\":{\"desired\":{\"color\":\"blue\"}}}")
        .when()
            .post("/things/phase-five-thing/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.color", equalTo("blue"))
            .body("version", equalTo(1));

        given()
            .contentType("application/json")
            .body("{\"state\":{\"reported\":{\"color\":\"green\"}}}")
        .when()
            .post("/things/phase-five-thing/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.color", equalTo("blue"))
            .body("state.reported.color", equalTo("green"))
            .body("version", equalTo(2));

        given()
        .when()
            .delete("/things/phase-five-thing/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(2));

        given()
        .when()
            .get("/things/phase-five-thing/shadow")
        .then()
            .statusCode(404);
    }

    @Test
    void namedShadowLifecycleAndPublish() {
        given()
            .contentType("application/json")
            .queryParam("name", "settings")
            .body("{\"state\":{\"desired\":{\"mode\":\"auto\"}}}")
        .when()
            .post("/things/phase-five-named/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.mode", equalTo("auto"));

        given()
        .when()
            .get("/api/things/shadow/ListNamedShadowsForThing/phase-five-named")
        .then()
            .statusCode(200)
            .body("results", hasItem("settings"));

        given()
            .queryParam("name", "settings")
        .when()
            .get("/things/phase-five-named/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.mode", equalTo("auto"));

        given()
            .contentType("text/plain")
            .body("payload")
        .when()
            .post("/topics/devices/phase-five-named/events")
        .then()
            .statusCode(200);

        given()
            .queryParam("name", "settings")
        .when()
            .delete("/things/phase-five-named/shadow")
        .then()
            .statusCode(200);
    }

    @Test
    void retainedMessagesRoundTripAndDeleteWithEmptyRetainedPublish() {
        given()
            .contentType("text/plain")
            .queryParam("retain", true)
            .queryParam("qos", 1)
            .body("retained-payload")
        .when()
            .post("/topics/devices/mvp1/retained")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/retainedMessage/devices/mvp1/retained")
        .then()
            .statusCode(200)
            .body("topic", equalTo("devices/mvp1/retained"))
            .body("payload", equalTo("cmV0YWluZWQtcGF5bG9hZA=="))
            .body("qos", equalTo(1));

        given()
            .contentType("text/plain")
            .queryParam("retain", true)
            .body("other")
        .when()
            .post("/topics/devices/mvp1/retained-other")
        .then()
            .statusCode(200);

        String nextToken = given()
            .queryParam("maxResults", 1)
        .when()
            .get("/retainedMessage")
        .then()
            .statusCode(200)
            .body("retainedTopics.size()", equalTo(1))
            .extract()
            .path("nextToken");

        given()
            .queryParam("maxResults", 1)
            .queryParam("nextToken", nextToken)
        .when()
            .get("/retainedMessage")
        .then()
            .statusCode(200)
            .body("retainedTopics.size()", equalTo(1));

        given()
            .contentType("application/octet-stream")
            .queryParam("retain", true)
            .body(new byte[0])
        .when()
            .post("/topics/devices/mvp1/retained")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/retainedMessage/devices/mvp1/retained")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void shadowNullDeletesPropertiesAndVersionConflicts() {
        given()
            .contentType("application/json")
            .body("{\"state\":{\"desired\":{\"color\":\"blue\",\"mode\":\"auto\"}}}")
        .when()
            .post("/things/mvp1-shadow/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(1));

        given()
            .contentType("application/json")
            .body("{\"version\":1,\"state\":{\"desired\":{\"color\":null}}}")
        .when()
            .post("/things/mvp1-shadow/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.color", nullValue())
            .body("state.desired.mode", equalTo("auto"))
            .body("version", equalTo(2));

        given()
            .contentType("application/json")
            .body("{\"version\":1,\"state\":{\"desired\":{\"mode\":\"manual\"}}}")
        .when()
            .post("/things/mvp1-shadow/shadow")
        .then()
            .statusCode(409)
            .body("__type", equalTo("VersionConflictException"));

        given()
            .contentType("application/json")
            .body("{\"version\":2,\"state\":{\"desired\":null}}")
        .when()
            .post("/things/mvp1-shadow/shadow")
        .then()
            .statusCode(200)
            .body("state.desired", nullValue())
            .body("version", equalTo(3));
    }
}
