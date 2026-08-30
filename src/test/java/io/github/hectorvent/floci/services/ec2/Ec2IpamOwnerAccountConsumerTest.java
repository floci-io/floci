package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level test that IPAM resources created by a non-default account report that account —
 * not the emulator's default — as their owner. Account-aware storage already partitions by the
 * caller, so an owner id taken from {@code config.defaultAccountId()} would disagree with the
 * partition the resource actually lives in.
 */
@QuarkusTest
class Ec2IpamOwnerAccountConsumerTest {

    private static final String CALLER = "222222222222";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + CALLER + "/20260101/us-east-1/ec2/aws4_request";

    @Test
    void createIpamAndPoolReportTheCallingAccountAsOwner() {
        String ipamId = given()
            .formParam("Action", "CreateIpam")
            .formParam("Description", "owner-account-test-ipam")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateIpamResponse.ipam.ownerId", equalTo(CALLER))
            .body("CreateIpamResponse.ipam.ipamArn",
                    startsWith("arn:aws:ec2::" + CALLER + ":ipam/"))
            .extract().path("CreateIpamResponse.ipam.ipamId");

        String scopeId = given()
            .formParam("Action", "DescribeIpams")
            .formParam("IpamId.1", ipamId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeIpamsResponse.ipamSet.item.ownerId", equalTo(CALLER))
            .extract().path("DescribeIpamsResponse.ipamSet.item.privateDefaultScopeId");

        given()
            .formParam("Action", "CreateIpamPool")
            .formParam("IpamScopeId", scopeId)
            .formParam("AddressFamily", "ipv4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateIpamPoolResponse.ipamPool.ownerId", equalTo(CALLER))
            .body("CreateIpamPoolResponse.ipamPool.ipamPoolArn",
                    startsWith("arn:aws:ec2::" + CALLER + ":ipam-pool/"));
    }
}
