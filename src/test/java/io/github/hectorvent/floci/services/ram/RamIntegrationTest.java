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
    void ownerCanTagAndDeleteItsOwnShareOverHttp() {
        // Mutations resolve the share within the caller's account, so the identity stamped at
        // create time has to be the identity resolved at tag/delete time — otherwise LZA's
        // AWS::RAM::ResourceShare teardown would fail against a share it had just created.
        String shareArn =
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    {
                        "name": "owner-mutation-share",
                        "principals": ["arn:aws:organizations::000000000000:ou/o-abc/ou-infra"],
                        "resourceArns": ["arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0own"]
                    }
                    """)
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "tags": [{"key": "Owner", "value": "network"}] }
                """.formatted(shareArn))
        .when()
            .post("/tagresource")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/deleteresourceshare?resourceShareArn=" + shareArn)
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));
    }

    @Test
    void malformedBodyIsRejectedAsSerializationException() {
        // A body that is not JSON is a client error; without an explicit rejection the parse
        // failure escapes as UncheckedIOException and the SDK sees a 500 InternalFailure.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("not json at all")
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("SerializationException"));
    }

    /**
     * The controller substitutes "SELF" only when resourceOwner is absent or null; a present-but-
     * unmodelled value — including a non-string that {@code asText} coerces — has to reach the
     * service check and come back as InvalidParameterException on the wire, which is the path LZA
     * actually takes.
     */
    @Test
    void unmodelledResourceOwnerIsRejectedOnTheWire() {
        for (String path : new String[] {"/getresourceshares", "/listprincipals", "/listresources"}) {
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    { "resourceOwner": "self" }
                    """)
            .when()
                .post(path)
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        }

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": 5 }
                """)
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        // Absent resourceOwner keeps the existing SELF default — unchanged by this fix.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(200);
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
            // Selected by name: the store is shared across the tests in this class.
            .body("resourceShares.findAll { it.name == 'us-east-1-tgw-share' }.size()", equalTo(1))
            .body("resourceShares.find { it.name == 'us-east-1-tgw-share' }.owningAccountId",
                    equalTo("000000000000"));

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
