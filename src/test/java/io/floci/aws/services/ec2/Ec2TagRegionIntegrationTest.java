package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EC2 tags are regional: a tag set in one region is listed only there, over the Query API. */
@QuarkusTest
class Ec2TagRegionIntegrationTest {

    private static final String EAST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String WEST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request";

    private XmlPath ec2(String auth, String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", auth);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    @Test
    void describeTagsListsOnlyTheRequestRegionsTags() {
        String east = ec2(EAST_AUTH, "CreateVpc", "CidrBlock", "10.90.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String west = ec2(WEST_AUTH, "CreateVpc", "CidrBlock", "10.91.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        try {
            ec2(EAST_AUTH, "CreateTags", "ResourceId.1", east, "Tag.1.Key", "RegionProbe", "Tag.1.Value", "east");
            ec2(WEST_AUTH, "CreateTags", "ResourceId.1", west, "Tag.1.Key", "RegionProbe", "Tag.1.Value", "west");

            assertEquals(List.of(east), probedIds(EAST_AUTH));
            assertEquals(List.of(west), probedIds(WEST_AUTH));
            XmlPath otherRegion = ec2(WEST_AUTH, "DescribeTags",
                    "Filter.1.Name", "resource-id", "Filter.1.Value.1", east);
            assertTrue(otherRegion.getList("DescribeTagsResponse.tagSet.item.resourceId").isEmpty());
        } finally {
            ec2(EAST_AUTH, "DeleteVpc", "VpcId", east);
            ec2(WEST_AUTH, "DeleteVpc", "VpcId", west);
        }
    }

    private List<String> probedIds(String auth) {
        return ec2(auth, "DescribeTags", "Filter.1.Name", "key", "Filter.1.Value.1", "RegionProbe")
                .getList("DescribeTagsResponse.tagSet.item.resourceId", String.class);
    }
}
