package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
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

    private static String authFor(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/ram/aws4_request";
    }

    private static RequestSpecification organizations(String accountId, String action, String body) {
        return given()
                .header("Authorization", authFor(accountId))
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

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
        // LZA's Custom::GetResourceShare Lambda pages this first, before any share has been
        // created in this test's account. The response must be JSON (restJson1): an XML
        // fallthrough here is exactly the SyntaxError seen in run b81f0999.
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
        // create time has to be the identity resolved at tag/delete time, otherwise LZA's
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
    void readOperationsOnlyExposeAResourceShareToItsOwnerOrInvitedAccount() {
        String shareArn =
            given()
                .contentType("application/json")
                .header("Authorization", authFor("111111111111"))
                .body("""
                    {
                        "name": "account-visible-share",
                        "principals": ["222222222222"],
                        "resourceArns": ["arn:aws:ec2:us-east-1:111111111111:transit-gateway/tgw-visibility"]
                    }
                    """)
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");

        given()
            .contentType("application/json")
            .header("Authorization", authFor("111111111111"))
            .body("{ \"resourceOwner\": \"SELF\", \"resourceShareArns\": [\"%s\"] }".formatted(shareArn))
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(200)
            .body("resourceShares.size()", equalTo(1));

        for (String path : new String[] {"/getresourceshares", "/listresources", "/listprincipals"}) {
            given()
                .contentType("application/json")
                .header("Authorization", authFor("222222222222"))
                .body("{ \"resourceOwner\": \"OTHER-ACCOUNTS\", \"resourceShareArns\": [\"%s\"] }".formatted(shareArn))
            .when()
                .post(path)
            .then()
                .statusCode(200)
                .body(path.equals("/getresourceshares") ? "resourceShares.size()"
                        : path.equals("/listresources") ? "resources.size()" : "principals.size()", equalTo(1));

            given()
                .contentType("application/json")
                .header("Authorization", authFor("333333333333"))
                .body("{ \"resourceOwner\": \"OTHER-ACCOUNTS\", \"resourceShareArns\": [\"%s\"] }".formatted(shareArn))
            .when()
                .post(path)
            .then()
                .statusCode(200)
                .body(path.equals("/getresourceshares") ? "resourceShares.size()"
                        : path.equals("/listresources") ? "resources.size()" : "principals.size()", equalTo(0));
        }
    }

    @Test
    void organizationAndOuSharesOnlyReachMembers() {
        String owner = "444444444444";
        String organizationId =
            organizations(owner, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract().path("Organization.Id");
        String rootId =
            organizations(owner, "ListRoots", "{}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract().path("Roots[0].Id");
        String ouArn =
            organizations(owner, "CreateOrganizationalUnit",
                    "{\"ParentId\":\"%s\",\"Name\":\"RamVisibility\"}".formatted(rootId))
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract().path("OrganizationalUnit.Arn");
        String member =
            organizations(owner, "CreateAccount",
                    "{\"Email\":\"ram-visibility-%s@example.com\",\"AccountName\":\"RamMember\"}"
                            .formatted(organizationId))
            .when()
                .post("/")
            .then()
                .statusCode(200)
            .extract().path("CreateAccountStatus.AccountId");
        String memberRoot = rootId;
        organizations(owner, "MoveAccount",
                "{\"AccountId\":\"%s\",\"SourceParentId\":\"%s\",\"DestinationParentId\":\"%s\"}"
                        .formatted(member, memberRoot, ouArn.substring(ouArn.lastIndexOf('/') + 1)))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        organizations(member, "DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", equalTo(organizationId));

        String organizationArn = "arn:aws:organizations::" + owner + ":organization/" + organizationId;
        String shareArn =
            given()
                .contentType("application/json")
                .header("Authorization", authFor(owner))
                .body(("{\"name\":\"organization-visible-share\",\"principals\":[\"%s\"],"
                        + "\"resourceArns\":[\"arn:aws:ec2:us-east-1:%s:transit-gateway/tgw-org\"]}")
                        .formatted(organizationArn, owner))
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");

        assertShareVisibility(shareArn, member, true);
        assertShareVisibility(shareArn, "666666666666", false);

        given()
            .header("Authorization", authFor(owner))
        .when()
            .post("/enablesharingwithawsorganization")
        .then()
            .statusCode(200);
        organizations("666666666666", "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String accountShareArn =
            given()
                .contentType("application/json")
                .header("Authorization", authFor(owner))
                .body(("{\"name\":\"organization-account-visible-share\",\"principals\":[\"%s\"],"
                        + "\"resourceArns\":[\"arn:aws:ec2:us-east-1:%s:transit-gateway/tgw-account\"]}")
                        .formatted(member, owner))
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");
        assertShareVisibility(accountShareArn, member, true);
        assertShareVisibility(accountShareArn, "666666666666", false);

        String ouShareArn =
            given()
                .contentType("application/json")
                .header("Authorization", authFor(owner))
                .body(("{\"name\":\"ou-visible-share\",\"principals\":[\"%s\"],"
                        + "\"resourceArns\":[\"arn:aws:ec2:us-east-1:%s:transit-gateway/tgw-ou\"]}")
                        .formatted(ouArn, owner))
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");

        assertShareVisibility(ouShareArn, member, true);
        assertShareVisibility(ouShareArn, "666666666666", false);
    }

    private static void assertShareVisibility(String shareArn, String caller, boolean visible) {
        given()
            .contentType("application/json")
            .header("Authorization", authFor(caller))
            .body("{\"resourceOwner\":\"OTHER-ACCOUNTS\",\"resourceShareArns\":[\"%s\"]}"
                    .formatted(shareArn))
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(200)
            .body("resourceShares.size()", equalTo(visible ? 1 : 0));
    }

    @Test
    void getResourceSharesHonoursTheNameFilterOverHttp() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "name": "http-filtered-share",
                    "resourceArns": ["arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0flt"]
                }
                """)
        .when()
            .post("/createresourceshare")
        .then()
            .statusCode(200);

        // The filter has to be read off the body, not ignored: without it this returns every
        // share the other tests in this class created.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": "SELF", "name": "http-filtered-share" }
                """)
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(200)
            .body("resourceShares.size()", equalTo(1))
            .body("resourceShares[0].name", equalTo("http-filtered-share"));
    }

    @Test
    void deleteEchoesTheClientTokenAndSharesReportFeatureSet() {
        String shareArn =
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    {
                        "name": "token-echo-share",
                        "resourceArns": ["arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0tok"]
                    }
                    """)
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
                // Shares created through the API are STANDARD, not policy-derived.
                .body("resourceShare.featureSet", equalTo("STANDARD"))
            .extract().path("resourceShare.resourceShareArn");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
        .when()
            .delete("/deleteresourceshare?resourceShareArn=" + shareArn + "&clientToken=idem-1")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true))
            .body("clientToken", equalTo("idem-1"));
    }

    /**
     * Jackson's default readTree stops at the first complete value, so a body like
     * {@code {} not-json} parsed as an empty object and the request executed. The JSON 1.0/1.1
     * controllers and the FIS REST controller all read with FAIL_ON_TRAILING_TOKENS; RAM was the
     * outlier.
     */
    @Test
    void trailingContentAfterValidJsonIsSerializationException() {
        for (String body : new String[] {
                "{} not-json",
                "{\"resourceOwner\":\"SELF\"}{\"second\":\"document\"}"}) {
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/getresourceshares")
            .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"));
        }
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

    @Test
    void malformedInvitationsBodyIsRejectedAsSerializationException() {
        // GetResourceShareInvitations ignores its body semantically (invitations are
        // always empty under organization sharing), but a malformed body is still a
        // client error, not a 200.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("not json at all")
        .when()
            .post("/getresourceshareinvitations")
        .then()
            .statusCode(400)
            .contentType("application/json")
            .body("__type", equalTo("SerializationException"));
    }

    /**
     * A present-but-unmodelled resourceOwner, including a non-string that {@code asText}
     * coerces, has to reach the service check and come back as InvalidParameterException on the
     * wire, which is the path LZA actually takes.
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

    }

    /**
     * resourceOwner is a required member on all three read operations. Substituting SELF for an
     * absent one answered a question the caller never asked, and answered it with the caller's
     * own shares, which is the more dangerous of the two branches to guess at.
     */
    @Test
    void missingResourceOwnerIsRejectedOnEveryReadPath() {
        for (String path : new String[] {"/getresourceshares", "/listprincipals", "/listresources"}) {
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("{}")
            .when()
                .post(path)
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        }

        // An explicit null is absent too, not a third state.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": null }
                """)
        .when()
            .post("/getresourceshares")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
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

    @Test
    void acceptResourceShareInvitationOverHttp() {
        // A bare account-id principal (not an OU/organization ARN) gets a real PENDING
        // invitation; targeting this test's own account keeps the whole flow within one
        // Authorization header, same as the rest of this class.
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {
                    "name": "http-invitation-share",
                    "principals": ["000000000000"],
                    "resourceArns": ["arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0inv"]
                }
                """)
        .when()
            .post("/createresourceshare")
        .then()
            .statusCode(200);

        String invitationArn =
            given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("{}")
            .when()
                .post("/getresourceshareinvitations")
            .then()
                .statusCode(200)
                .body("resourceShareInvitations.find { it.resourceShareName == 'http-invitation-share' }.status",
                        equalTo("PENDING"))
            .extract()
                .path("resourceShareInvitations.find { it.resourceShareName == 'http-invitation-share' }" +
                        ".resourceShareInvitationArn");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareInvitationArn": "%s", "clientToken": "test-token" }
                """.formatted(invitationArn))
        .when()
            .post("/acceptresourceshareinvitation")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("resourceShareInvitation.status", equalTo("ACCEPTED"))
            .body("resourceShareInvitation.resourceShareInvitationArn", equalTo(invitationArn))
            .body("clientToken", equalTo("test-token"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareInvitationArn": "%s" }
                """.formatted(invitationArn))
        .when()
            .post("/acceptresourceshareinvitation")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceShareInvitationAlreadyAcceptedException"));
    }
}
