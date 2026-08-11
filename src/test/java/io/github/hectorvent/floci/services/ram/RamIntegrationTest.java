package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the RAM restJson1 organization-sharing opt-in:
 * {@code POST /enablesharingwithawsorganization} succeeds with or without a request body.
 */
@QuarkusTest
class RamIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ram/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void enableSharingWithAwsOrganization_returnsTrue() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/enablesharingwithawsorganization")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));
    }

    @Test
    void enableSharingWithAwsOrganization_withoutBody_returnsTrue() {
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/enablesharingwithawsorganization")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));
    }

    @Test
    void getResourceShareInvitations_returnsEmptyJson() {
        // LZA's Custom::GetResourceShare Lambda pages this first; under organization
        // sharing there are never invitations. The response must be JSON (restJson1) —
        // an XML fallthrough here is exactly the SyntaxError seen in run b81f0999.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/getresourceshareinvitations")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("resourceShareInvitations.size()", equalTo(0));
    }

    @Test
    void createThenGetResourceSharesAndListResources() {
        String tgwArn = "arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0abc";
        String shareArn =
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    {
                        "name": "us-east-1-tgw-share",
                        "principals": ["arn:aws:organizations::000000000000:ou/o-abc/ou-infra"],
                        "resourceArns": ["%s"]
                    }
                    """.formatted(tgwArn))
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
                .body("resourceShare.name", equalTo("us-east-1-tgw-share"))
                .body("resourceShare.status", equalTo("ACTIVE"))
            .extract().path("resourceShare.resourceShareArn");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": "SELF" }
                """)
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(200)
            .body("resourceShares.size()", equalTo(1))
            .body("resourceShares[0].name", equalTo("us-east-1-tgw-share"))
            .body("resourceShares[0].owningAccountId", equalTo("000000000000"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": "SELF", "resourceShareArns": ["%s"] }
                """.formatted(shareArn))
        .when()
            .post("/listresources")
        .then()
            .statusCode(200)
            .body("resources.size()", equalTo(1))
            .body("resources[0].arn", equalTo(tgwArn))
            .body("resources[0].type", equalTo("ec2:TransitGateway"))
            .body("resources[0].resourceShareArn", equalTo(shareArn));
    }
}
