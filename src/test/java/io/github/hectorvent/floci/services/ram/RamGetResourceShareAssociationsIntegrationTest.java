package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class RamGetResourceShareAssociationsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=777777777777/20260101/us-east-1/ram/aws4_request";
    private static final String EMPTY_ACCOUNT_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=777777777778/20260101/us-east-1/ram/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourceShareAssociationsReturnsAnEmptyJsonArrayBeforeAnySharesExist() {
        given()
            .contentType("application/json")
            .header("Authorization", EMPTY_ACCOUNT_AUTH_HEADER)
            .body("{\"associationType\":\"RESOURCE\"}")
        .when()
            .post("/getresourceshareassociations")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("resourceShareAssociations.size()", equalTo(0));
    }

    @Test
    void getResourceShareAssociationsReturnsTheRequestedResourceAndPrincipal() {
        String resourceArn = "arn:aws:ec2:us-east-1:777777777777:transit-gateway/tgw-association";
        String shareArn = given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"name\":\"association-test\",\"principals\":[\"888888888888\"],"
                    + "\"resourceArns\":[\"" + resourceArn + "\"]}")
        .when()
            .post("/createresourceshare")
        .then()
            .statusCode(200)
        .extract().path("resourceShare.resourceShareArn");

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"associationType\":\"RESOURCE\",\"resourceShareArns\":[\"" + shareArn
                    + "\"],\"resourceArn\":\"" + resourceArn + "\"}")
        .when()
            .post("/getresourceshareassociations")
        .then()
            .statusCode(200)
            .body("resourceShareAssociations.size()", equalTo(1))
            .body("resourceShareAssociations[0].resourceShareArn", equalTo(shareArn))
            .body("resourceShareAssociations[0].associatedEntity", equalTo(resourceArn))
            .body("resourceShareAssociations[0].associationType", equalTo("RESOURCE"))
            .body("resourceShareAssociations[0].status", equalTo("ASSOCIATED"))
            .body("resourceShareAssociations[0].creationTime", notNullValue());

        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"associationType\":\"PRINCIPAL\",\"resourceShareArns\":[\"" + shareArn
                    + "\"],\"principal\":\"888888888888\"}")
        .when()
            .post("/getresourceshareassociations")
        .then()
            .statusCode(200)
            .body("resourceShareAssociations.size()", equalTo(1))
            .body("resourceShareAssociations[0].associatedEntity", equalTo("888888888888"))
            .body("resourceShareAssociations[0].associationType", equalTo("PRINCIPAL"));
    }
}
