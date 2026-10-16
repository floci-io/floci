package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The EMR release catalogue over the JSON protocol: ListReleaseLabels with its filters and paging,
 * DescribeReleaseLabel, ListSupportedInstanceTypes, and InvalidRequestException for bad input.
 */
@QuarkusTest
class EmrReleaseCatalogIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20261015/us-east-1"
                        + "/elasticmapreduce/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "ElasticMapReduce." + action)
                .body(body).when().post("/");
    }

    @Test
    void listReleaseLabelsNewestFirstWithFiltersAndPaging() {
        call("ListReleaseLabels", "{}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-7.5.0", "emr-7.4.0", "emr-6.15.0", "emr-6.14.0", "emr-5.36.2"))
                .body("NextToken", nullValue());
        call("ListReleaseLabels", "{\"Filters\":{\"Prefix\":\"emr-6\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));
        call("ListReleaseLabels", "{\"Filters\":{\"Application\":\"Spark@3.4.1\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));
        // The version matches without regard to case too: the catalogue holds 4.14.3-HBase-1.4.
        call("ListReleaseLabels", "{\"Filters\":{\"Application\":\"phoenix@4.14.3-hbase-1.4\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-5.36.2"));
        call("ListReleaseLabels", "{\"Filters\":{\"Application\":\"delta\"}}").then().statusCode(200)
                .body("ReleaseLabels", everyItem(startsWith("emr-")))
                .body("ReleaseLabels", hasSize(4));

        String token = call("ListReleaseLabels", "{\"MaxResults\":2}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-7.5.0", "emr-7.4.0"))
                .extract().path("NextToken");
        call("ListReleaseLabels", "{\"MaxResults\":2,\"NextToken\":\"" + token + "\"}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));
    }

    @Test
    void describeReleaseLabelReportsApplicationsAndOsReleases() {
        call("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-7.5.0\"}").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.5.0"))
                .body("Applications.find { it.Name == 'spark' }.Version", equalTo("3.5.2"))
                .body("Applications.find { it.Name == 'hadoop' }.Version", equalTo("3.4.0"))
                .body("AvailableOSReleases.Label", hasItem(startsWith("2023.")));
        // No label: the latest release.
        call("DescribeReleaseLabel", "{}").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.5.0"));
        List<String> firstPage = call("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-5.36.2\",\"MaxResults\":5}")
                .then().statusCode(200).body("Applications", hasSize(5)).extract().path("Applications.Name");
        assertEquals(5, firstPage.size());
    }

    @Test
    void listSupportedInstanceTypesForARelease() {
        call("ListSupportedInstanceTypes", "{\"ReleaseLabel\":\"emr-7.5.0\"}").then().statusCode(200)
                .body("SupportedInstanceTypes.find { it.Type == 'm5.xlarge' }.VCPU", equalTo(4))
                .body("SupportedInstanceTypes.find { it.Type == 'm5.xlarge' }.EbsStorageOnly", equalTo(true))
                .body("SupportedInstanceTypes.find { it.Type == 'm6g.xlarge' }.Architecture", equalTo("AARCH64"))
                .body("SupportedInstanceTypes.find { it.Type == 'm5.xlarge' }.Architecture", equalTo("X86_64"))
                .body("SupportedInstanceTypes.find { it.Type == 'm5d.xlarge' }.StorageGB", equalTo(150));
    }

    @Test
    void badInputIsAnInvalidRequest() {
        for (String[] bad : new String[][] {
                {"DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-1.0.0\"}"},
                {"ListSupportedInstanceTypes", "{\"ReleaseLabel\":\"emr-1.0.0\"}"},
                {"ListSupportedInstanceTypes", "{}"},
                {"ListReleaseLabels", "{\"MaxResults\":0}"},
                {"ListReleaseLabels", "{\"MaxResults\":101}"},
                {"ListReleaseLabels", "{\"NextToken\":\"next\"}"},
                {"ListReleaseLabels", "{\"NextToken\":\"-1\"}"}}) {
            call(bad[0], bad[1]).then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"));
        }
    }
}
