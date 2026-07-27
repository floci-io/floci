package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation provisions AWS::EC2::VPCEndpoint for
 * real (issue #1994): the endpoint lands in Ec2Service with its route-table
 * association, and DeleteStack removes it. Metadata-only — Docker-free.
 */
@QuarkusTest
class CloudFormationVpcEndpointIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createStackProvisionsGatewayEndpointAndDeleteRemovesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-vpce-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.60.0.0/16"}},
                    "Rt": {"Type": "AWS::EC2::RouteTable", "Properties": {"VpcId": {"Ref": "Vpc"}}},
                    "S3Endpoint": {
                      "Type": "AWS::EC2::VPCEndpoint",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "ServiceName": "com.amazonaws.us-east-1.s3",
                        "VpcEndpointType": "Gateway",
                        "RouteTableIds": [{"Ref": "Rt"}]
                      }
                    }
                  },
                  "Outputs": {
                    "EndpointId": {"Value": {"Ref": "S3Endpoint"}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>vpce-"));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("com.amazonaws.us-east-1.s3"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("com.amazonaws.us-east-1.s3")));
    }
}
