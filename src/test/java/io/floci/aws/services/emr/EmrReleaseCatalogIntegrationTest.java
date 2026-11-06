package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EMR release catalogue over the JSON protocol: ListReleaseLabels with its filters and paging,
 * DescribeReleaseLabel, ListSupportedInstanceTypes, and InvalidRequestException for bad input.
 */
@QuarkusTest
class EmrReleaseCatalogIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listReleaseLabelsNewestFirstWithFiltersAndPaging() {
        emr("ListReleaseLabels", "{}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-7.5.0", "emr-7.4.0", "emr-6.15.0", "emr-6.14.0", "emr-5.36.2"))
                .body("NextToken", nullValue());
        emr("ListReleaseLabels", "{\"Filters\":{\"Prefix\":\"emr-6\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));
        emr("ListReleaseLabels", "{\"Filters\":{\"Application\":\"Spark@3.4.1\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));
        // The version matches without regard to case too: the catalogue holds 4.14.3-HBase-1.4.
        emr("ListReleaseLabels", "{\"Filters\":{\"Application\":\"phoenix@4.14.3-hbase-1.4\"}}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-5.36.2"));
        emr("ListReleaseLabels", "{\"Filters\":{\"Application\":\"delta\"}}").then().statusCode(200)
                .body("ReleaseLabels", everyItem(startsWith("emr-")))
                .body("ReleaseLabels", hasSize(4));

        String token = emr("ListReleaseLabels", "{\"MaxResults\":2}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-7.5.0", "emr-7.4.0"))
                .extract().path("NextToken");
        emr("ListReleaseLabels", "{\"MaxResults\":2,\"NextToken\":\"" + token + "\"}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.15.0", "emr-6.14.0"));

        // A token belongs to the filters it was issued for.
        String filtered = emr("ListReleaseLabels", "{\"MaxResults\":1,\"Filters\":{\"Prefix\":\"emr-6\"}}")
                .then().statusCode(200).body("ReleaseLabels", contains("emr-6.15.0"))
                .extract().path("NextToken");
        emr("ListReleaseLabels", "{\"MaxResults\":1,\"Filters\":{\"Prefix\":\"emr-6\"},\"NextToken\":\""
                + filtered + "\"}").then().statusCode(200)
                .body("ReleaseLabels", contains("emr-6.14.0"))
                .body("NextToken", nullValue());
        emr("ListReleaseLabels", "{\"MaxResults\":1,\"Filters\":{\"Prefix\":\"emr-7\"},\"NextToken\":\""
                + filtered + "\"}").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void describeReleaseLabelReportsApplicationsAndOsReleases() {
        emr("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-7.5.0\"}").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.5.0"))
                .body("Applications.find { it.Name == 'spark' }.Version", equalTo("3.5.2"))
                .body("Applications.find { it.Name == 'hadoop' }.Version", equalTo("3.4.0"))
                .body("AvailableOSReleases.Label", hasItem(startsWith("2023.")));
        // No label: the latest release.
        emr("DescribeReleaseLabel", "{}").then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.5.0"));
        // MaxResults is reserved for future use: every application comes back, with no token.
        int all = emr("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-5.36.2\"}").then().statusCode(200)
                .extract().path("Applications.size()");
        emr("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-5.36.2\",\"MaxResults\":5}").then().statusCode(200)
                .body("Applications", hasSize(all))
                .body("NextToken", nullValue());
        assertTrue(all > 5);
    }

    @Test
    void listSupportedInstanceTypesForARelease() {
        emr("ListSupportedInstanceTypes", "{\"ReleaseLabel\":\"emr-7.5.0\"}").then().statusCode(200)
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
                {"ListReleaseLabels", "{\"NextToken\":\"-1\"}"},
                {"ListSupportedInstanceTypes", "{\"ReleaseLabel\":\"emr-7.5.0\",\"Marker\":\"2\"}"}}) {
            emr(bad[0], bad[1]).then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"));
        }
    }
}
