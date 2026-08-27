package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Wire-level tests for the RAM lifecycle operations LZA's TGW share flow needs beyond
 * CreateResourceShare/GetResourceShares/ListResources: UpdateResourceShare, DeleteResourceShare,
 * AssociateResourceShare, DisassociateResourceShare, ListPrincipals, TagResource, UntagResource.
 *
 * <p>New class, not appended to {@code RamIntegrationTest} (CS-001), so falsifiability isolates
 * per operation.
 */
@QuarkusTest
class RamResourceShareLifecycleConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ram/aws4_request";
    private static final String TGW_ARN = "arn:aws:ec2:us-east-1:000000000000:transit-gateway/tgw-0lifecycle";
    private static final String OU_PRINCIPAL = "arn:aws:organizations::000000000000:ou/o-abc/ou-lifecycle";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private String createShare(String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", AUTH_HEADER)
                .body("""
                    { "name": "%s", "resourceArns": ["%s"] }
                    """.formatted(name, TGW_ARN))
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
            .extract().path("resourceShare.resourceShareArn");
    }

    @Test
    void updateResourceShare_renamesAndTogglesAllowExternalPrincipals() {
        String arn = createShare("lifecycle-update");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "name": "renamed-share", "allowExternalPrincipals": true }
                """.formatted(arn))
        .when()
            .post("/updateresourceshare")
        .then()
            .statusCode(200)
            .body("resourceShare.name", equalTo("renamed-share"))
            .body("resourceShare.allowExternalPrincipals", equalTo(true));
    }

    @Test
    void updateResourceShare_unknownArn_returns400() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "arn:aws:ram:us-east-1:000000000000:resource-share/does-not-exist" }
                """)
        .when()
            .post("/updateresourceshare")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownResourceException"));
    }

    @Test
    void deleteResourceShare_marksStatusDeleted() {
        String arn = createShare("lifecycle-delete");

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("resourceShareArn", arn)
        .when()
            .delete("/deleteresourceshare")
        .then()
            .statusCode(200)
            .body("returnValue", equalTo(true));

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
            .body("resourceShares.find { it.resourceShareArn == '%s' }.status".formatted(arn), equalTo("DELETED"));
    }

    @Test
    void associateThenDisassociateResourceShare_addsAndRemovesPrincipal() {
        String arn = createShare("lifecycle-associate");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "principals": ["%s"] }
                """.formatted(arn, OU_PRINCIPAL))
        .when()
            .post("/associateresourceshare")
        .then()
            .statusCode(200)
            .body("resourceShareAssociations.size()", equalTo(1))
            .body("resourceShareAssociations[0].associatedEntity", equalTo(OU_PRINCIPAL))
            .body("resourceShareAssociations[0].associationType", equalTo("PRINCIPAL"))
            .body("resourceShareAssociations[0].status", equalTo("ASSOCIATED"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": "SELF" }
                """)
        .when()
            .post("/listprincipals")
        .then()
            .statusCode(200)
            .body("principals.id", hasItem(OU_PRINCIPAL));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "principals": ["%s"] }
                """.formatted(arn, OU_PRINCIPAL))
        .when()
            .post("/disassociateresourceshare")
        .then()
            .statusCode(200)
            .body("resourceShareAssociations[0].status", equalTo("DISASSOCIATED"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceOwner": "SELF" }
                """)
        .when()
            .post("/listprincipals")
        .then()
            .statusCode(200)
            .body("principals.id", not(hasItem(OU_PRINCIPAL)));
    }

    @Test
    void tagThenUntagResource_addsAndRemovesTag() {
        String arn = createShare("lifecycle-tags");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "tags": [{ "key": "env", "value": "prod" }] }
                """.formatted(arn))
        .when()
            .post("/tagresource")
        .then()
            .statusCode(200);

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
            .body("resourceShares.find { it.resourceShareArn == '%s' }.tags[0].key".formatted(arn), equalTo("env"))
            .body("resourceShares.find { it.resourceShareArn == '%s' }.tags[0].value".formatted(arn), equalTo("prod"));

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                { "resourceShareArn": "%s", "tagKeys": ["env"] }
                """.formatted(arn))
        .when()
            .post("/untagresource")
        .then()
            .statusCode(200);

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
            .body("resourceShares.find { it.resourceShareArn == '%s' }.tags.size()".formatted(arn), equalTo(0));
    }
}
