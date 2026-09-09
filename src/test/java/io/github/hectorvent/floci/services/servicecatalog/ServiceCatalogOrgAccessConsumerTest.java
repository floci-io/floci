package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Wire-level tests for {@code EnableAWSOrganizationsAccess},
 * {@code DisableAWSOrganizationsAccess} and {@code GetAWSOrganizationsAccessStatus}.
 *
 * <p>New class, not appended to an existing one: the falsifiability check requires
 * that removing one operation's dispatch case fails only that operation's own tests
 * (CS-001). All three operations here return 200 with no error body on success; the
 * handler's {@code default} arm returns 400, so a bare status-code assertion is
 * already sufficient to falsify — no message-collision risk to guard against.
 */
@QuarkusTest
class ServiceCatalogOrgAccessConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    @Test
    void enableAwsOrganizationsAccess_returnsEmptyBody() {
        call("EnableAWSOrganizationsAccess", "{}")
        .then()
            .statusCode(200);
    }

    @Test
    void disableAwsOrganizationsAccess_returnsEmptyBody() {
        call("DisableAWSOrganizationsAccess", "{}")
        .then()
            .statusCode(200);
    }

    @Test
    void getAwsOrganizationsAccessStatus_returnsEnabled() {
        call("GetAWSOrganizationsAccessStatus", "{}")
        .then()
            .statusCode(200)
            .body("AccessStatus", org.hamcrest.Matchers.equalTo("ENABLED"));
    }
}
