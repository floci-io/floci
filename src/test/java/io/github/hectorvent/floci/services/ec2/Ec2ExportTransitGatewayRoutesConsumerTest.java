package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level tests for {@code ExportTransitGatewayRoutes}: no real S3 write happens — the
 * operation returns an {@code s3://} location string (with a unique random suffix) matching
 * the real operation's naming convention.
 */
@QuarkusTest
class Ec2ExportTransitGatewayRoutesConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";

    private String createTransitGateway() {
        return given()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Description", "export-routes-test-tgw")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayResponse.transitGateway.transitGatewayId", startsWith("tgw-"))
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
    }

    private String createRouteTable(String tgwId) {
        return given()
            .formParam("Action", "CreateTransitGatewayRouteTable")
            .formParam("TransitGatewayId", tgwId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.transitGatewayRouteTableId");
    }

    @Test
    void exportTransitGatewayRoutes_returnsS3Location() {
        String tgwId = createTransitGateway();
        String routeTableId = createRouteTable(tgwId);

        given()
            .formParam("Action", "ExportTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("S3Bucket", "my-export-bucket")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ExportTransitGatewayRoutesResponse.s3Location", startsWith("s3://my-export-bucket/"));
    }

    @Test
    void exportTransitGatewayRoutes_missingBucket_returnsAwsErrorShape() {
        String tgwId = createTransitGateway();
        String routeTableId = createRouteTable(tgwId);

        given()
            .formParam("Action", "ExportTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }
}
