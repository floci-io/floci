package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-level tests for {@code ClientToken} idempotency on the IPAM allocation path.
 *
 * <p>LZA's {@code get-ipam-subnet-cidr} custom-resource Lambda retries
 * {@code AllocateIpamPoolCidr} with the same token; before this was honoured each retry burned
 * another distinct CIDR out of the pool. Service-level tests cover the dedupe itself, so what
 * this class falsifies is the handler wiring — that {@code ClientToken} is read off the form
 * post at all.
 */
@QuarkusTest
class Ec2IpamClientTokenConsumerTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";

    private String provisionedPool(String cidr) {
        String ipamId = given()
            .formParam("Action", "CreateIpam")
            .formParam("Description", "client-token-test-ipam")
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

        String poolId = given()
            .formParam("Action", "CreateIpamPool")
            .formParam("IpamScopeId", scopeId)
            .formParam("AddressFamily", "ipv4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateIpamPoolResponse.ipamPool.ipamPoolId");

        given()
            .formParam("Action", "ProvisionIpamPoolCidr")
            .formParam("IpamPoolId", poolId)
            .formParam("Cidr", cidr)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        return poolId;
    }

    private String allocate(String poolId, String clientToken) {
        return given()
            .formParam("Action", "AllocateIpamPoolCidr")
            .formParam("IpamPoolId", poolId)
            .formParam("NetmaskLength", "24")
            .formParam("Description", "lza subnet")
            .formParam("ClientToken", clientToken)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AllocateIpamPoolCidrResponse.ipamPoolAllocation.ipamPoolAllocationId");
    }

    @Test
    void retryingAllocateIpamPoolCidrWithTheSameClientTokenReusesTheAllocation() {
        String poolId = provisionedPool("10.0.0.0/16");

        String first = allocate(poolId, "lza-retry-token");
        String retry = allocate(poolId, "lza-retry-token");
        assertEquals(first, retry);

        given()
            .formParam("Action", "GetIpamPoolAllocations")
            .formParam("IpamPoolId", poolId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetIpamPoolAllocationsResponse.ipamPoolAllocationSet.item.cidr",
                    equalTo("10.0.0.0/24"));
    }

    @Test
    void distinctClientTokensStillAllocateDistinctCidrs() {
        String poolId = provisionedPool("10.1.0.0/16");

        allocate(poolId, "token-one");
        allocate(poolId, "token-two");

        assertEquals(List.of("10.1.0.0/24", "10.1.1.0/24"), allocatedCidrs(poolId));
    }

    private List<String> allocatedCidrs(String poolId) {
        return given()
            .formParam("Action", "GetIpamPoolAllocations")
            .formParam("IpamPoolId", poolId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getList("GetIpamPoolAllocationsResponse.ipamPoolAllocationSet.item.cidr", String.class);
    }

    @Test
    void retryingProvisionIpamPoolCidrWithTheSameClientTokenProvisionsOnce() {
        String poolId = provisionedPool("10.2.0.0/16");

        for (int attempt = 0; attempt < 2; attempt++) {
            given()
                .formParam("Action", "ProvisionIpamPoolCidr")
                .formParam("IpamPoolId", poolId)
                .formParam("Cidr", "10.3.0.0/16")
                .formParam("ClientToken", "provision-retry-token")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ProvisionIpamPoolCidrResponse.ipamPoolCidr.cidr", equalTo("10.3.0.0/16"));
        }

        List<String> provisioned = given()
            .formParam("Action", "GetIpamPoolCidrs")
            .formParam("IpamPoolId", poolId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath()
            .getList("GetIpamPoolCidrsResponse.ipamPoolCidrSet.item.cidr", String.class);
        assertEquals(List.of("10.2.0.0/16", "10.3.0.0/16"), provisioned,
                "a replayed ClientToken must not provision the CIDR a second time");
    }

    @Test
    void allocateIpamPoolCidrWithoutAPoolIdIsAMissingParameter() {
        given()
            .formParam("Action", "AllocateIpamPoolCidr")
            .formParam("NetmaskLength", "24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }
}
