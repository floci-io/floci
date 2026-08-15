package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Complement to {@link UnknownServiceScopeGuardIntegrationTest}: a request signed for a
 * service Floci DOES implement can still fall through JAX-RS matching into S3's
 * path-style wildcard routes when its specific operation path has no handler yet
 * (Backup's audit framework routes, IoT authorizers, ...). Those must be rejected with
 * the same clean UnknownOperationException shape instead of an S3 XML error the caller's
 * rest-json SDK cannot deserialize.
 */
@QuarkusTest
class S3FallthroughGuardIntegrationTest {

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20260814/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef";
    }

    @Test
    void backupScopedUnimplementedRouteGetsUnknownOperation() {
        // Backup CreateFramework: POST /audit/frameworks has no BackupController route and
        // would otherwise match S3's POST /{bucket}/{key} wildcard.
        given()
            .header("Authorization", authorization("backup"))
            .contentType("application/json")
            .body("{\"FrameworkName\": \"guard-test\"}")
        .when()
            .post("/audit/frameworks")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .header("x-amzn-query-error", "UnknownOperationException;Sender")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void iotScopedUnimplementedRouteGetsUnknownOperation() {
        // IoT CreateAuthorizer: POST /authorizer/{authorizerName} is not an IotController
        // route, so JAX-RS matches S3's wildcard instead.
        given()
            .header("Authorization", authorization("iot"))
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/authorizer/guard-test-authorizer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void lambdaScopedUnimplementedRouteGetsUnknownOperation() {
        // Lambda CreateCodeSigningConfig: POST /2020-04-22/code-signing-configs/ has no
        // LambdaController route.
        given()
            .header("Authorization", authorization("lambda"))
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/2020-04-22/code-signing-configs/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void s3ScopedRequestKeepsS3Behavior() {
        given()
            .header("Authorization", authorization("s3"))
        .when()
            .get("/no-such-bucket-fallthrough?list-type=2")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void s3expressScopedRequestKeepsS3Behavior() {
        given()
            .header("Authorization", authorization("s3express"))
        .when()
            .get("/no-such-bucket-fallthrough?list-type=2")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void unsignedRequestIsUntouched() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/audit/frameworks")
        .then()
            // No positive identification, no rejection: S3's wildcard answers.
            .contentType(containsString("xml"));
    }

    @Test
    void knownScopeOnItsOwnRoutesIsUntouched() {
        // A backup-scoped request to a route BackupController does implement must reach it:
        // the guard fires only on requests that actually matched S3Controller.
        given()
            .header("Authorization", authorization("backup"))
        .when()
            .get("/backup-vaults/")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
}
