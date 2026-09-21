package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Hot-reload enabled with no allow-list, as in the test configuration: directories that can hold
 * the Docker socket are refused over the wire, and ordinary code directories are not.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaHotReloadDockerSocketIntegrationTest {

    private static int counter;

    /** This test shares the default profile's emulator state with every other test on it. */
    private static final List<String> CREATED = new ArrayList<>();

    private static ValidatableResponse createFunction(String s3Key) {
        return createFunction("hot-reload-socket-guard-" + (++counter), s3Key);
    }

    private static ValidatableResponse createFunction(String name, String s3Key) {
        return given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "python3.12",
                            "Role": "arn:aws:iam::000000000000:role/r",
                            "Handler": "handler.handler",
                            "Code": { "S3Bucket": "hot-reload", "S3Key": "%s" }
                        }
                        """.formatted(name, s3Key))
                .when()
                .post("/2015-03-31/functions")
                .then();
    }

    @Order(1)
    @ParameterizedTest
    @ValueSource(strings = {"/", "/var/run", "/run", "/var/run/docker.sock", "/proc/1/root/var/run"})
    void createFunctionRejectsDirectoriesThatCanHoldTheDockerSocket(String s3Key) {
        createFunction(s3Key)
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("Docker socket"));
    }

    @Order(1)
    @ParameterizedTest
    @ValueSource(strings = {"/tmp/floci-hot-reload", "/home/ci/code"})
    void createFunctionAcceptsOrdinaryCodeDirectories(String s3Key) {
        String name = "hot-reload-socket-guard-accepted-" + (++counter);
        CREATED.add(name);

        createFunction(name, s3Key).statusCode(201);
    }

    /**
     * The functions the accept cases created, deleted as an ordered test rather than in
     * {@code @AfterAll}: Quarkus has already stopped the app by then, so the call cannot be made.
     */
    @Test
    @Order(2)
    void cleanup_deleteCreatedFunctions() {
        for (String name : CREATED) {
            given()
                    .when()
                    .delete("/2015-03-31/functions/" + name)
                    .then()
                    .statusCode(204);
        }
        CREATED.clear();
    }
}
