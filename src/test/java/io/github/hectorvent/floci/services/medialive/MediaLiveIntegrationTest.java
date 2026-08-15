package io.github.hectorvent.floci.services.medialive;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaLiveIntegrationTest {

    private static String multiplexId;
    private static String multiplexArn;

    @Test
    @Order(1)
    void createMultiplexIsIdleImmediately() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"name": "integration-multiplex",
                 "availabilityZones": ["us-east-1a", "us-east-1b"],
                 "multiplexSettings": {"transportStreamBitrate": 1000000, "transportStreamId": 1},
                 "requestId": "integration-1",
                 "tags": {"team": "video"}}
                """)
        .when()
            .post("/prod/multiplexes")
        .then()
            .statusCode(201)
            .body("multiplex.arn", containsString(":medialive:"))
            .body("multiplex.arn", containsString(":multiplex:"))
            .body("multiplex.state", equalTo("IDLE"))
            .body("multiplex.name", equalTo("integration-multiplex"))
            .body("multiplex.availabilityZones", hasSize(2))
            .body("multiplex.multiplexSettings.transportStreamBitrate", equalTo(1000000))
            .body("multiplex.multiplexSettings.transportStreamId", equalTo(1))
            .body("multiplex.tags.team", equalTo("video"))
            .extract();
        multiplexId = response.path("multiplex.id");
        multiplexArn = response.path("multiplex.arn");
    }

    @Test
    @Order(2)
    void describeMultiplex() {
        given()
        .when()
            .get("/prod/multiplexes/" + multiplexId)
        .then()
            .statusCode(200)
            .body("arn", equalTo(multiplexArn))
            .body("state", equalTo("IDLE"))
            .body("programCount", equalTo(0));
    }

    @Test
    @Order(3)
    void tagRoundTripOnProdPath() {
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"extra\": \"yes\"}}")
        .when()
            .post("/prod/tags/" + multiplexArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/prod/tags/" + multiplexArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("video"))
            .body("tags.extra", equalTo("yes"));

        given()
        .when()
            .delete("/prod/tags/" + multiplexArn + "?tagKeys=extra")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(4)
    void programRoundTrip() {
        given()
            .contentType("application/json")
            .body("""
                {"programName": "integration-program",
                 "requestId": "integration-2",
                 "multiplexProgramSettings": {"programNumber": 1}}
                """)
        .when()
            .post("/prod/multiplexes/" + multiplexId + "/programs")
        .then()
            .statusCode(201)
            .body("multiplexProgram.programName", equalTo("integration-program"))
            .body("multiplexProgram.multiplexProgramSettings.programNumber", equalTo(1));

        given()
        .when()
            .get("/prod/multiplexes/" + multiplexId + "/programs/integration-program")
        .then()
            .statusCode(200)
            .body("programName", equalTo("integration-program"));

        given()
        .when()
            .get("/prod/multiplexes/" + multiplexId)
        .then()
            .statusCode(200)
            .body("programCount", equalTo(1));

        given()
        .when()
            .delete("/prod/multiplexes/" + multiplexId + "/programs/integration-program")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void deletedMultiplexStaysReadableAsDeleted() {
        given()
        .when()
            .delete("/prod/multiplexes/" + multiplexId)
        .then()
            .statusCode(202)
            .body("state", equalTo("DELETED"));

        given()
        .when()
            .get("/prod/multiplexes/" + multiplexId)
        .then()
            .statusCode(200)
            .body("state", equalTo("DELETED"));
    }

    @Test
    @Order(6)
    void unknownMultiplexIs404() {
        given()
        .when()
            .get("/prod/multiplexes/0000000")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }
}
