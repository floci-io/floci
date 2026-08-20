package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for the region-level EC2 instance metadata defaults
 * ({@code aws_ec2_instance_metadata_defaults}) over the EC2 Query Protocol.
 *
 * <p>Covers {@code ModifyInstanceMetadataDefaults} / {@code GetInstanceMetadataDefaults}
 * (lex00/floci#76) - previously entirely unimplemented, failing every such resource's create with
 * {@code UnsupportedOperation}.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2InstanceMetadataDefaultsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";
    private static final String OTHER_REGION_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request";

    @Test
    @Order(1)
    void getOnAFreshRegionReadsAwsOwnNoPreferenceDefaults() {
        given()
            .formParam("Action", "GetInstanceMetadataDefaults")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpTokens", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpPutResponseHopLimit", equalTo("-1"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpEndpoint", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.instanceMetadataTags", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.managedBy", equalTo("none"));
    }

    @Test
    @Order(2)
    void modifyEnforcesImdsv2() {
        given()
            .formParam("Action", "ModifyInstanceMetadataDefaults")
            .formParam("HttpTokens", "required")
            .formParam("HttpPutResponseHopLimit", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("ModifyInstanceMetadataDefaultsResponse.return", equalTo("true"));
    }

    @Test
    @Order(3)
    void getAfterModifyReadsBackExactlyWhatWasSetAndLeavesTheRestAlone() {
        given()
            .formParam("Action", "GetInstanceMetadataDefaults")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpTokens", equalTo("required"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpPutResponseHopLimit", equalTo("1"))
            // Never touched by the modify call above - must still read the untouched default.
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpEndpoint", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.instanceMetadataTags", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.managedBy", equalTo("account"));
    }

    @Test
    @Order(4)
    void anotherRegionIsUnaffected() {
        given()
            .formParam("Action", "GetInstanceMetadataDefaults")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpTokens", equalTo("no-preference"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.managedBy", equalTo("none"));
    }

    @Test
    @Order(5)
    void aSecondModifyOnlyChangesTheFieldsItSupplies() {
        given()
            .formParam("Action", "ModifyInstanceMetadataDefaults")
            .formParam("InstanceMetadataTags", "enabled")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetInstanceMetadataDefaults")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.instanceMetadataTags", equalTo("enabled"))
            // Set in @Order(2), must survive a modify call that does not mention it.
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpTokens", equalTo("required"))
            .body("GetInstanceMetadataDefaultsResponse.accountLevel.httpPutResponseHopLimit", equalTo("1"));
    }
}
