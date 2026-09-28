package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DescribeRegions follows the request's partition and AWS's opt-in model: by default only the
 * regions enabled for the account (every non-opt-in region), all published regions with
 * {@code AllRegions}, and the endpoints carry the partition's DNS suffix.
 */
@QuarkusTest
class Ec2DescribeRegionsIntegrationTest {

    private Response describeRegions(String region, String... params) {
        RequestSpecification request = given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "ec2"))
                .formParam("Action", "DescribeRegions")
                .formParam("Version", "2016-11-15");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().statusCode(200).contentType("application/xml")
                .extract().response();
    }

    @Test
    void commercialDefaultListsOnlyEnabledRegionsWithCommercialEndpoints() {
        Response response = describeRegions("us-east-1");
        List<String> names = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName");
        List<String> statuses = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.optInStatus");
        List<String> endpoints = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionEndpoint");

        assertEquals(17, names.size(), "the commercial regions that need no opt-in: " + names);
        assertTrue(names.contains("us-east-1"));
        assertTrue(names.contains("eu-north-1"));
        assertFalse(names.contains("ap-east-1"), "opt-in regions are hidden until AllRegions is set");
        assertFalse(names.contains("cn-north-1"));
        assertTrue(statuses.stream().allMatch("opt-in-not-required"::equals), statuses.toString());
        assertTrue(endpoints.contains("ec2.us-east-1.amazonaws.com"), endpoints.toString());
    }

    @Test
    void allRegionsIncludesOptInRegionsAsNotOptedIn() {
        Response response = describeRegions("us-east-1", "AllRegions", "true");
        List<String> names = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName");
        assertEquals(34, names.size(), names.toString());
        int hongKong = names.indexOf("ap-east-1");
        assertTrue(hongKong >= 0, names.toString());
        assertEquals("not-opted-in",
                response.xmlPath().getString("DescribeRegionsResponse.regionInfo.item[" + hongKong + "].optInStatus"));
        int virginia = names.indexOf("us-east-1");
        assertEquals("opt-in-not-required",
                response.xmlPath().getString("DescribeRegionsResponse.regionInfo.item[" + virginia + "].optInStatus"));
    }

    @Test
    void regionNamesFilterReturnsTheNamedRegionsWhetherOrNotEnabled() {
        Response response = describeRegions("us-east-1", "RegionName.1", "ap-east-1", "RegionName.2", "us-west-2");
        List<String> names = response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName");
        assertEquals(List.of("ap-east-1", "us-west-2"), names);
    }

    @Test
    void aChinaSignedRequestListsTheChinaPartition() {
        Response response = describeRegions("cn-north-1");
        assertEquals(List.of("cn-north-1", "cn-northwest-1"),
                response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName"));
        assertEquals(List.of("ec2.cn-north-1.amazonaws.com.cn", "ec2.cn-northwest-1.amazonaws.com.cn"),
                response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionEndpoint"));
    }

    @Test
    void aGovCloudSignedRequestListsGovCloudWithTheCommercialSuffix() {
        Response response = describeRegions("us-gov-west-1");
        assertEquals(List.of("us-gov-east-1", "us-gov-west-1"),
                response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionName"));
        assertEquals(List.of("ec2.us-gov-east-1.amazonaws.com", "ec2.us-gov-west-1.amazonaws.com"),
                response.xmlPath().getList("DescribeRegionsResponse.regionInfo.item.regionEndpoint"));
    }
}
