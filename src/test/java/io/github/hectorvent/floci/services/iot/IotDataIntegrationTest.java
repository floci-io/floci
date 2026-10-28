package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
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
            .body("state.desired", nullValue())
            .body("state.reported.color", equalTo("green"))
            .body("version", equalTo(2));

        given()
        .when()
            .get("/things/phase-five-thing/shadow")
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

    /** Measured on AWS IoT: a QoS other than 0 or 1 is refused before the message is retained. */
    @ParameterizedTest
    @CsvSource({"2,QoS 2 is currently not supported", "-1,Invalid QoS value: -1", "7,Invalid QoS value: 7"})
    void publishRefusesAQosOtherThanZeroOrOne(int qos, String message) {
        given()
            .contentType("text/plain")
            .queryParam("retain", true)
            .queryParam("qos", qos)
            .body("refused")
        .when()
            .post("/topics/devices/qos/refused")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo(message));

        given()
        .when()
            .get("/retainedMessage/devices/qos/refused")
        .then()
            .statusCode(404);
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
            .body("version", equalTo(2));

        given()
        .when()
            .get("/things/mvp1-shadow/shadow")
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
            .header("x-amzn-ErrorType", equalTo("ConflictException"))
            .body("__type", equalTo("ConflictException"))
            .body("message", equalTo("Version conflict"));

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

    @Test
    void namedShadowVersionConflictUsesIotDataErrorCode() {
        given()
            .contentType("application/json")
            .queryParam("name", "settings")
            .body("{\"state\":{\"desired\":{\"mode\":\"auto\"}}}")
        .when()
            .post("/things/mvp1-versioned-named/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(1));

        given()
            .contentType("application/json")
            .queryParam("name", "settings")
            .body("{\"version\":0,\"state\":{\"desired\":{\"mode\":\"manual\"}}}")
        .when()
            .post("/things/mvp1-versioned-named/shadow")
        .then()
            .statusCode(409)
            .header("x-amzn-ErrorType", equalTo("ConflictException"))
            .body("__type", equalTo("ConflictException"))
            .body("message", equalTo("Version conflict"));

        given()
            .queryParam("name", "settings")
        .when()
            .get("/things/mvp1-versioned-named/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(1))
            .body("state.desired.mode", equalTo("auto"));
    }

    @Test
    void nestedShadowUpdateMergesKeyByKey() {
        int first = given()
            .contentType("application/json")
            .body("{\"state\":{\"reported\":{\"sensors\":{\"IDT_O1\":22.3,\"ODT\":-14.1},\"cfg\":{\"modes\":[\"eco\",\"boost\"]}}}}")
        .when()
            .post("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(1))
            .extract().path("timestamp");

        int second = given()
            .contentType("application/json")
            .body("{\"state\":{\"reported\":{\"sensors\":{\"IDT_O1\":26.3},\"tz\":\"Europe/Stockholm\"}}}")
        .when()
            .post("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(200)
            .body("state.reported.sensors", not(hasKey("ODT")))
            .body("version", equalTo(2))
            .extract().path("timestamp");

        given()
        .when()
            .get("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(200)
            .body("state.reported.sensors.ODT", equalTo(-14.1f))
            .body("state.reported.sensors.IDT_O1", equalTo(26.3f))
            .body("state.reported.tz", equalTo("Europe/Stockholm"))
            .body("state.reported.cfg.modes", equalTo(List.of("eco", "boost")))
            .body("metadata.reported.sensors.ODT.timestamp", equalTo(first))
            .body("metadata.reported.sensors.IDT_O1.timestamp", equalTo(second))
            .body("metadata.reported.tz.timestamp", equalTo(second))
            .body("version", equalTo(2));

        given()
            .contentType("application/json")
            .body("{\"version\":2,\"state\":{\"reported\":{\"sensors\":{\"ODT\":null},\"cfg\":{\"modes\":[\"off\"]}}}}")
        .when()
            .post("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(200)
            .body("version", equalTo(3));

        given()
            .contentType("application/json")
            .body("{\"version\":2,\"state\":{\"reported\":{\"tz\":null}}}")
        .when()
            .post("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(409)
            .header("x-amzn-ErrorType", equalTo("ConflictException"))
            .body("__type", equalTo("ConflictException"));

        given()
        .when()
            .get("/things/teos-merge-shadow/shadow")
        .then()
            .statusCode(200)
            .body("state.reported.sensors", not(hasKey("ODT")))
            .body("metadata.reported.sensors", not(hasKey("ODT")))
            .body("state.reported.sensors.IDT_O1", equalTo(26.3f))
            .body("state.reported.cfg.modes", equalTo(List.of("off")))
            .body("metadata.reported.cfg.modes.size()", equalTo(1))
            .body("state.reported.tz", equalTo("Europe/Stockholm"))
            .body("version", equalTo(3));
    }
}
