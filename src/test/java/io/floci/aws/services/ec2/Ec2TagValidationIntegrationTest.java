package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** CreateTags and DeleteTags reject what AWS rejects, with the EC2 Query error shape. */
@QuarkusTest
class Ec2TagValidationIntegrationTest {

    private static final String EAST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String WEST_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request";

    private RequestSpecification ec2(String auth, String action, String... formParams) {
        RequestSpecification req = given().formParam("Action", action).header("Authorization", auth);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req;
    }

    @Test
    void createTagsAnswersEachRejectionWithItsCode() {
        String vpcId = ec2(EAST_AUTH, "CreateVpc", "CidrBlock", "10.94.0.0/16")
                .when().post("/").then().statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        try {
            ec2(WEST_AUTH, "CreateTags", "ResourceId.1", vpcId, "Tag.1.Key", "Team", "Tag.1.Value", "x")
                    .when().post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
            ec2(EAST_AUTH, "CreateTags", "ResourceId.1", "not-an-id", "Tag.1.Key", "Team", "Tag.1.Value", "x")
                    .when().post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidID"))
                    .body("Response.Errors.Error.Message", equalTo("The ID 'not-an-id' is not valid"));
            ec2(EAST_AUTH, "DeleteTags", "Tag.1.Key", "Team")
                    .when().post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
        } finally {
            ec2(EAST_AUTH, "DeleteVpc", "VpcId", vpcId).when().post("/").then().statusCode(200);
        }
    }
}
