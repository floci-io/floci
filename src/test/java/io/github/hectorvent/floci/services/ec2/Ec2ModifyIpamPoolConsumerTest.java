package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Wire-level tests for {@code ModifyIpamPool}.
 *
 * <p>New class, not appended to {@code Ec2IntegrationTest} (which uses ordered fixtures this
 * operation doesn't need), so falsifiability isolates per operation (CS-001).
 */
@QuarkusTest
class Ec2ModifyIpamPoolConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";

    private String createIpamPool() {
        String ipamId = given()
            .formParam("Action", "CreateIpam")
            .formParam("Description", "modify-pool-test-ipam")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateIpamResponse.ipam.ipamId");

        String scopeId = given()
            .formParam("Action", "DescribeIpams")
            .formParam("IpamId.1", ipamId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeIpamsResponse.ipamSet.item.privateDefaultScopeId");

        return given()
            .formParam("Action", "CreateIpamPool")
            .formParam("IpamScopeId", scopeId)
            .formParam("AddressFamily", "ipv4")
            .formParam("Description", "initial-pool")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateIpamPoolResponse.ipamPool.ipamPoolId");
    }

    @Test
    void modifyIpamPool_updatesDescriptionAutoImportAndNetmaskBounds() {
        String poolId = createIpamPool();

        given()
            .formParam("Action", "ModifyIpamPool")
            .formParam("IpamPoolId", poolId)
            .formParam("Description", "updated-pool")
            .formParam("AutoImport", "true")
            .formParam("AllocationMinNetmaskLength", "24")
            .formParam("AllocationMaxNetmaskLength", "28")
            .formParam("AllocationDefaultNetmaskLength", "26")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyIpamPoolResponse.ipamPool.ipamPoolId", equalTo(poolId))
            .body("ModifyIpamPoolResponse.ipamPool.description", equalTo("updated-pool"))
            .body("ModifyIpamPoolResponse.ipamPool.autoImport", equalTo("true"))
            .body("ModifyIpamPoolResponse.ipamPool.allocationMinNetmaskLength", equalTo("24"))
            .body("ModifyIpamPoolResponse.ipamPool.allocationMaxNetmaskLength", equalTo("28"))
            .body("ModifyIpamPoolResponse.ipamPool.allocationDefaultNetmaskLength", equalTo("26"));

        given()
            .formParam("Action", "DescribeIpamPools")
            .formParam("IpamPoolId.1", poolId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeIpamPoolsResponse.ipamPoolSet.item.description", equalTo("updated-pool"));
    }

    @Test
    void modifyIpamPool_clearAllocationDefaultNetmaskLength_removesIt() {
        String poolId = createIpamPool();

        given()
            .formParam("Action", "ModifyIpamPool")
            .formParam("IpamPoolId", poolId)
            .formParam("AllocationDefaultNetmaskLength", "26")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyIpamPoolResponse.ipamPool.allocationDefaultNetmaskLength", equalTo("26"));

        given()
            .formParam("Action", "ModifyIpamPool")
            .formParam("IpamPoolId", poolId)
            .formParam("ClearAllocationDefaultNetmaskLength", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("allocationDefaultNetmaskLength")));
    }

    @Test
    void modifyIpamPool_unknownId_returnsAwsErrorShape() {
        given()
            .formParam("Action", "ModifyIpamPool")
            .formParam("IpamPoolId", "ipam-pool-doesnotexist")
            .formParam("Description", "irrelevant")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidIpamPoolId.NotFound"));
    }
}
