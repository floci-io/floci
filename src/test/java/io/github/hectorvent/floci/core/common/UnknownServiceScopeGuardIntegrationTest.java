package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * REST requests signed for a service Floci does not implement must be rejected with the
 * clean UnknownOperationException shape instead of falling through JAX-RS matching into
 * S3's path-style wildcard routes (issue #1754). The guard requires positive identification
 * via the SigV4 credential scope: unsigned, presigned, and Bearer traffic stays untouched.
 */
@QuarkusTest
class UnknownServiceScopeGuardIntegrationTest {

    private static String authorization(String service) {
        return "AWS4-HMAC-SHA256 Credential=test/20260707/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef";
    }

    @Test
    void accountScopedRestRequestGetsUnknownOperation() {
        given()
            .header("Authorization", authorization("account"))
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/listRegions")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .header("x-amzn-query-error", "UnknownOperationException;Sender")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void securityhubScopedRestRequestGetsUnknownOperation() {
        given()
            .header("Authorization", authorization("securityhub"))
        .when()
            .get("/accounts")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void s3ScopedRequestKeepsS3Behavior() {
        given()
            .header("Authorization", authorization("s3"))
        .when()
            .get("/no-such-bucket-1754?list-type=2")
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
            .get("/no-such-bucket-1754?list-type=2")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void iotJobsDataPlaneScopeReachesItsIotRoutes() {
        // The IoT Jobs Data Plane signs as iot-jobs-data while IotController serves its
        // /things/{thing}/jobs routes. The scope has to be enumerated or the guard rejects a
        // route Floci does implement — the SDK compat suites caught exactly this on
        // GetPendingJobExecutions.
        given()
            .header("Authorization", authorization("iot-jobs-data"))
        .when()
            .get("/things/guard-test-thing/jobs")
        .then()
            // IoT's own "thing not found", which only IotController can produce: the request
            // reached the route. The guard's rejection would be UnknownOperationException instead.
            .statusCode(404)
            .body("message", containsString("guard-test-thing"))
            .body("__type", not(equalTo("UnknownOperationException")));
    }

    @Test
    void unsignedRequestIsUntouched() {
        given()
        .when()
            .get("/no-such-bucket-1754?list-type=2")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void presignedStyleQueryCredentialIsUntouched() {
        // No Authorization header: query-string credentials are presigned-URL territory,
        // out of the guard's positive-identification scope even with a bogus service.
        given()
            .queryParam("X-Amz-Credential", "test/20260707/us-east-1/account/aws4_request")
        .when()
            .get("/no-such-bucket-1754")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void bearerAuthorizationIsUntouched() {
        given()
            .header("Authorization", "Bearer some-token")
        .when()
            .get("/no-such-bucket-1754?list-type=2")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    @Test
    void rootFormPostSignedForAbsentServiceKeepsQueryProtocolPath() {
        // Form-encoded POST / claims AWS_QUERY, not REST — the guard must not intercept it.
        given()
            .header("Authorization", authorization("account"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListRegions")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", (String) null);
    }
}
