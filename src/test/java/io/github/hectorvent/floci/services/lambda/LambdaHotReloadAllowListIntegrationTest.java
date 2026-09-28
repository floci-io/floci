package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Hot-reload enabled with an allow-list: a path that only reaches the allowed directory through
 * {@code ..}, or that merely shares its prefix, must be refused over the wire.
 */
@QuarkusTest
@TestProfile(LambdaHotReloadAllowListIntegrationTest.HotReloadAllowListProfile.class)
class LambdaHotReloadAllowListIntegrationTest {

    private static int counter;

    private static ValidatableResponse createFunction(String s3Key) {
        String name = "hot-reload-allow-list-" + (++counter);
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

    @ParameterizedTest
    @ValueSource(strings = {
            "/home/ci/code/../../",
            "/home/ci/code/../../../etc",
            "/home/ci/code-evil",
            "/home/ci/code:/etc"
    })
    void createFunctionRejectsPathsOutsideTheAllowedDirectory(String s3Key) {
        createFunction(s3Key)
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/home/ci/code", "/home/ci/code/app"})
    void createFunctionAcceptsTheAllowedDirectoryAndItsChildren(String s3Key) {
        createFunction(s3Key).statusCode(201);
    }

    public static final class HotReloadAllowListProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.lambda.hot-reload.allowed-paths", "/home/ci/code",
                    "quarkus.http.test-port", "4589",
                    "floci.port", "4589",
                    "floci.base-url", "http://localhost:4589");
        }
    }
}
