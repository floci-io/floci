package io.github.hectorvent.floci.services.ssoadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * IAM Identity Center (SSO Admin, target prefix {@code SWBExternalService.}).
 *
 * <p>LZA's Custom::GetIdentityCenterInstanceMetadata Lambda calls {@code ListInstances} and
 * requires exactly one instance carrying {@code InstanceArn} and {@code IdentityStoreId}.
 */
@QuarkusTest
class SsoAdminIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listInstances_returnsExactlyOneInstanceWithArnAndIdentityStore() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", AUTH_HEADER)
            .header("X-Amz-Target", "SWBExternalService.ListInstances")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Instances.size()", equalTo(1))
            .body("Instances[0].InstanceArn", matchesPattern("arn:aws:sso:::instance/ssoins-[0-9a-f]{16}"))
            .body("Instances[0].IdentityStoreId", matchesPattern("d-[0-9a-f]{10}"))
            .body("Instances[0].Status", equalTo("ACTIVE"));
    }

    @Test
    void listInstances_isStableAcrossCalls() {
        String firstArn = listInstancesArn();
        String secondArn = listInstancesArn();
        org.junit.jupiter.api.Assertions.assertEquals(firstArn, secondArn);
    }

    @Test
    void unknownAction_returnsUnknownOperationException() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", AUTH_HEADER)
            .header("X-Amz-Target", "SWBExternalService.DescribeInstance")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", org.hamcrest.Matchers.containsString("UnknownOperationException"));
    }

    @Test
    void listInstances_ownerAccountIdTracksTheCaller() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=111122223333/20260101/us-east-1/sso/aws4_request")
            .header("X-Amz-Target", "SWBExternalService.ListInstances")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Instances[0].OwnerAccountId", org.hamcrest.Matchers.equalTo("111122223333"));
    }

    private static String listInstancesArn() {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", AUTH_HEADER)
                .header("X-Amz-Target", "SWBExternalService.ListInstances")
                .body("{}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract().path("Instances[0].InstanceArn");
    }
}
