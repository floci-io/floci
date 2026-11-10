package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DescribeTags pages with MaxResults and NextToken and honours every documented filter. */
@QuarkusTest
class Ec2DescribeTagsFilterIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private RequestSpecification ec2(String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", AUTH);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req;
    }

    private XmlPath ok(String action, String... formParams) {
        return ec2(action, formParams).when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private String createVpc(String cidr) {
        return ok("CreateVpc", "CidrBlock", cidr).getString("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    void maxResultsPagesThroughEveryTagUntilTheTokenIsGone() {
        String vpcId = createVpc("10.95.0.0/16");
        try {
            List<String> create = new ArrayList<>(List.of("ResourceId.1", vpcId));
            for (int i = 1; i <= 12; i++) {
                create.addAll(List.of("Tag." + i + ".Key", String.format("Page%02d", i), "Tag." + i + ".Value", "v"));
            }
            ok("CreateTags", create.toArray(String[]::new));

            List<String> keys = new ArrayList<>();
            List<Integer> pageSizes = new ArrayList<>();
            String token = null;
            do {
                List<String> params = new ArrayList<>(List.of(
                        "Filter.1.Name", "resource-id", "Filter.1.Value.1", vpcId, "MaxResults", "5"));
                if (token != null) {
                    params.addAll(List.of("NextToken", token));
                }
                XmlPath page = ok("DescribeTags", params.toArray(String[]::new));
                List<String> pageKeys = page.getList("DescribeTagsResponse.tagSet.item.key", String.class);
                keys.addAll(pageKeys);
                pageSizes.add(pageKeys.size());
                token = page.getString("DescribeTagsResponse.nextToken");
                token = token == null || token.isEmpty() ? null : token;
            } while (token != null);

            assertEquals(List.of(5, 5, 2), pageSizes);
            assertEquals(12, keys.stream().distinct().count());
            String unpaged = ec2("DescribeTags", "Filter.1.Name", "resource-id", "Filter.1.Value.1", vpcId)
                    .when().post("/").then().statusCode(200).extract().asString();
            assertEquals(12, new XmlPath(unpaged).getList("DescribeTagsResponse.tagSet.item.key").size());
            assertFalse(unpaged.contains("<nextToken>"), "an unpaged response carries no nextToken");
        } finally {
            ok("DeleteVpc", "VpcId", vpcId);
        }
    }

    @Test
    void maxResultsOutsideFiveToAThousandIsRejected() {
        for (String maxResults : List.of("0", "-1", "4", "1001")) {
            ec2("DescribeTags", "MaxResults", maxResults).when().post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidMaxResults"));
        }
    }

    @Test
    void filtersTakeTagKeyWildcardsAndSeveralValues() {
        String a = createVpc("10.96.0.0/16");
        String b = createVpc("10.97.0.0/16");
        try {
            ok("CreateTags", "ResourceId.1", a, "Tag.1.Key", "Owner", "Tag.1.Value", "TeamA",
                    "Tag.2.Key", "Role", "Tag.2.Value", "webserver");
            ok("CreateTags", "ResourceId.1", b, "Tag.1.Key", "Owner", "Tag.1.Value", "TeamB",
                    "Tag.2.Key", "Role", "Tag.2.Value", "Webserver");

            assertEquals(sorted(a + " Owner=TeamA"), listed(a, b,
                    "Filter.2.Name", "tag:Owner", "Filter.2.Value.1", "TeamA"));
            assertEquals(sorted(a + " Role=webserver", b + " Role=Webserver"), listed(a, b,
                    "Filter.2.Name", "value", "Filter.2.Value.1", "?ebserver"));
            assertEquals(sorted(a + " Owner=TeamA", b + " Owner=TeamB"), listed(a, b,
                    "Filter.2.Name", "key", "Filter.2.Value.1", "Ow*"));
            assertEquals(sorted(a + " Owner=TeamA", b + " Owner=TeamB"), listed(a, b,
                    "Filter.2.Name", "value", "Filter.2.Value.1", "TeamA", "Filter.2.Value.2", "TeamB"));
            assertTrue(listed(a, b, "Filter.2.Name", "resource-id", "Filter.2.Value.1", a,
                    "Filter.3.Name", "resource-id", "Filter.3.Value.1", b).isEmpty());
        } finally {
            ok("DeleteVpc", "VpcId", a);
            ok("DeleteVpc", "VpcId", b);
        }
    }

    /** The tags of {@code a} and {@code b} that also match {@code extraFilters}, as sorted "id key=value". */
    private List<String> listed(String a, String b, String... extraFilters) {
        List<String> params = new ArrayList<>(List.of(
                "Filter.1.Name", "resource-id", "Filter.1.Value.1", a, "Filter.1.Value.2", b));
        params.addAll(List.of(extraFilters));
        XmlPath xml = ok("DescribeTags", params.toArray(String[]::new));
        List<String> ids = xml.getList("DescribeTagsResponse.tagSet.item.resourceId", String.class);
        List<String> keys = xml.getList("DescribeTagsResponse.tagSet.item.key", String.class);
        List<String> values = xml.getList("DescribeTagsResponse.tagSet.item.value", String.class);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            result.add(ids.get(i) + " " + keys.get(i) + "=" + values.get(i));
        }
        Collections.sort(result);
        return result;
    }

    private static List<String> sorted(String... items) {
        List<String> result = new ArrayList<>(List.of(items));
        Collections.sort(result);
        return result;
    }
}
