package io.floci.aws.services.ec2;

import io.floci.aws.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A China deployment that allows unknown regions serves a region no partition publishes in its
 * own partition, so DescribeRegions lists the China regions for it, not the commercial ones.
 */
@QuarkusTest
@TestProfile(Ec2DescribeRegionsUnknownRegionIntegrationTest.ChinaAllowUnknownRegionsProfile.class)
class Ec2DescribeRegionsUnknownRegionIntegrationTest {

    private static final String UNKNOWN = "polygondwanaland-west-1";

    private Response describeRegions(String region) {
        return given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "ec2"))
                .formParam("Action", "DescribeRegions")
                .formParam("Version", "2016-11-15")
                .when().post("/").then().statusCode(200).contentType("application/xml")
                .extract().response();
    }

    @Test
    void anUnknownRegionListsTheDeploymentPartitionsRegions() {
        Response response = describeRegions(UNKNOWN);
        List<String> names = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName");
        List<String> endpoints = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionEndpoint");

        assertTrue(names.contains("cn-north-1"), names.toString());
        assertFalse(names.contains("us-east-1"), names.toString());
        assertTrue(endpoints.contains("ec2.cn-north-1.amazonaws.com.cn"), endpoints.toString());
    }

    @Test
    void aPublishedRegionStillListsItsOwnPartition() {
        Response response = describeRegions("us-east-1");
        List<String> names = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName");

        assertTrue(names.contains("us-east-1"), names.toString());
        assertFalse(names.contains("cn-north-1"), names.toString());
    }

    public static final class ChinaAllowUnknownRegionsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.default-region", "cn-north-1",
                    "floci.partitions.allow-unknown-regions", "true");
        }
    }
}
