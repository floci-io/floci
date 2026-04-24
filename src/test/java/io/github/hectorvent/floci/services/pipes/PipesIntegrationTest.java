package io.github.hectorvent.floci.services.pipes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipesIntegrationTest {

    @Test
    @Order(1)
    void createPipe() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:source-queue",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:target-queue",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "Description": "Integration test pipe",
                    "DesiredState": "RUNNING"
                }
                """)
        .when()
            .post("/v1/pipes/integration-pipe")
        .then()
            .statusCode(200)
            .body("Name", equalTo("integration-pipe"))
            .body("Arn", containsString("pipe/integration-pipe"))
            .body("Source", equalTo("arn:aws:sqs:us-east-1:000000000000:source-queue"))
            .body("Target", equalTo("arn:aws:sqs:us-east-1:000000000000:target-queue"))
            .body("CurrentState", equalTo("RUNNING"))
            .body("DesiredState", equalTo("RUNNING"));
    }

    @Test
    @Order(2)
    void createDuplicatePipeReturnsConflict() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:source-queue",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:target-queue",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role"
                }
                """)
        .when()
            .post("/v1/pipes/integration-pipe")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(3)
    void describePipe() {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/integration-pipe")
        .then()
            .statusCode(200)
            .body("Name", equalTo("integration-pipe"))
            .body("Source", equalTo("arn:aws:sqs:us-east-1:000000000000:source-queue"))
            .body("Description", equalTo("Integration test pipe"));
    }

    @Test
    @Order(4)
    void describeNonexistentPipeReturns404() {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/does-not-exist")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(5)
    void listPipes() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:other-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:other-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "DesiredState": "STOPPED"
                }
                """)
        .when()
            .post("/v1/pipes/second-pipe")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes")
        .then()
            .statusCode(200)
            .body("Pipes.size()", equalTo(2));
    }

    @Test
    @Order(6)
    void listPipesWithNamePrefixFilter() {
        given()
            .contentType("application/json")
            .queryParam("NamePrefix", "integration")
        .when()
            .get("/v1/pipes")
        .then()
            .statusCode(200)
            .body("Pipes.size()", equalTo(1))
            .body("Pipes[0].Name", equalTo("integration-pipe"));
    }

    @Test
    @Order(7)
    void updatePipe() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Target": "arn:aws:sqs:us-east-1:000000000000:updated-target",
                    "Description": "Updated description",
                    "DesiredState": "STOPPED"
                }
                """)
        .when()
            .put("/v1/pipes/integration-pipe")
        .then()
            .statusCode(200)
            .body("Target", equalTo("arn:aws:sqs:us-east-1:000000000000:updated-target"))
            .body("CurrentState", equalTo("STOPPED"))
            .body("DesiredState", equalTo("STOPPED"));
    }

    @Test
    @Order(8)
    void startPipe() {
        given()
            .contentType("application/json")
        .when()
            .post("/v1/pipes/integration-pipe/start")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("RUNNING"))
            .body("DesiredState", equalTo("RUNNING"));
    }

    @Test
    @Order(9)
    void stopPipe() {
        given()
            .contentType("application/json")
        .when()
            .post("/v1/pipes/integration-pipe/stop")
        .then()
            .statusCode(200)
            .body("CurrentState", equalTo("STOPPED"))
            .body("DesiredState", equalTo("STOPPED"));
    }

    @Test
    @Order(10)
    void deletePipe() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/integration-pipe")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/integration-pipe")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(11)
    void deleteNonexistentPipeReturns404() {
        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/ghost-pipe")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(12)
    void createPipeReturnsParametersInResponse() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Source": "arn:aws:sqs:us-east-1:000000000000:params-source",
                    "Target": "arn:aws:sqs:us-east-1:000000000000:params-target",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "SourceParameters": {
                        "FilterCriteria": {
                            "Filters": [{"Pattern": "{\\"body\\":{\\"status\\":[\\"active\\"]}}"}]
                        }
                    },
                    "TargetParameters": {
                        "InputTemplate": "{\\"id\\": <$.messageId>}"
                    },
                    "Tags": {"env": "test", "team": "platform"}
                }
                """)
        .when()
            .post("/v1/pipes/params-pipe")
        .then()
            .statusCode(200)
            .body("Name", equalTo("params-pipe"))
            .body("SourceParameters.FilterCriteria.Filters[0].Pattern",
                    equalTo("{\"body\":{\"status\":[\"active\"]}}"))
            .body("TargetParameters.InputTemplate", equalTo("{\"id\": <$.messageId>}"))
            .body("Tags.env", equalTo("test"))
            .body("Tags.team", equalTo("platform"));

        given()
            .contentType("application/json")
        .when()
            .delete("/v1/pipes/params-pipe")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void createPipeMissingSourceReturns400() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Target": "arn:aws:sqs:us-east-1:000000000000:target-queue",
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role"
                }
                """)
        .when()
            .post("/v1/pipes/bad-pipe")
        .then()
            .statusCode(400);
    }
}
