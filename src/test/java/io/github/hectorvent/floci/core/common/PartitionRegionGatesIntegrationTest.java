package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour that AWS gates on the partition rather than on a literal region: a WAF CLOUDFRONT
 * scope exists only where CloudFront does and lives in the partition's implicit global region,
 * and Lightsail exists only in the commercial partition.
 */
@QuarkusTest
class PartitionRegionGatesIntegrationTest {

    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response createIpSet(String region, String name) {
        return given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "wafv2"))
            .header("X-Amz-Target", "AWSWAF_20190729.CreateIPSet")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"IPAddressVersion\":\"IPV4\",\"Addresses\":[\"10.0.0.0/8\"]}")
        .when().post("/");
    }

    @Test
    void cloudFrontScopeIsRejectedWhereThePartitionHasNoCloudFront() {
        createIpSet("us-gov-west-1", "gov-" + Long.toString(System.nanoTime(), 36)).then().statusCode(400)
                .body("__type", containsString("WAFInvalidParameterException"))
                .body("Reason", containsString("aws-us-gov"));
    }

    /** The scope is unavailable for every operation in such a partition, not only for creates. */
    @Test
    void cloudFrontScopeListsAreRejectedWhereThePartitionHasNoCloudFront() {
        for (String action : new String[] {"ListIPSets", "ListWebACLs"}) {
            given()
                .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "wafv2"))
                .header("X-Amz-Target", "AWSWAF_20190729." + action)
                .contentType(JSON_1_1)
                .body("{\"Scope\":\"CLOUDFRONT\"}")
            .when().post("/")
            .then().statusCode(400)
                .body("__type", containsString("WAFInvalidParameterException"))
                .body("Reason", containsString("aws-us-gov"));
        }
    }

    @Test
    void cloudFrontScopeLivesInThePartitionsImplicitGlobalRegion() {
        String name = "cn-" + Long.toString(System.nanoTime(), 36);
        Response created = createIpSet("cn-north-1", name);
        created.then().statusCode(200);
        String arn = created.jsonPath().getString("Summary.ARN");
        String id = created.jsonPath().getString("Summary.Id");
        String lockToken = created.jsonPath().getString("Summary.LockToken");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "wafv2"))
            .header("X-Amz-Target", "AWSWAF_20190729.DeleteIPSet")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"Id\":\"" + id + "\",\"LockToken\":\"" + lockToken + "\"}")
        .when().post("/"));
        assertTrue(arn.startsWith("arn:aws-cn:wafv2:cn-northwest-1:000000000000:global/ipset/" + name + "/"), arn);
    }

    @Test
    void lightsailRegionsAreEmptyOutsideTheCommercialPartition() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "lightsail"))
            .header("X-Amz-Target", "Lightsail_20161128.GetRegions")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/").then().statusCode(200)
            .body("regions", hasSize(0));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "lightsail"))
            .header("X-Amz-Target", "Lightsail_20161128.GetRegions")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/").then().statusCode(200)
            .body("regions", hasSize(4));
    }
}
