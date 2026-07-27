package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation provisions AWS::EC2::VPCGatewayAttachment for real
 * (issue #1970): the internet gateway ends up attached to the VPC in Ec2Service, and
 * DeleteStack detaches it. Metadata-only resources, so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationVpcGatewayAttachmentIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createStackAttachesInternetGatewayToVpc() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-vpcgw-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {"CidrBlock": "10.42.0.0/16"}
                    },
                    "Igw": {"Type": "AWS::EC2::InternetGateway"},
                    "Attachment": {
                      "Type": "AWS::EC2::VPCGatewayAttachment",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "InternetGatewayId": {"Ref": "Igw"}
                      }
                    }
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
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String vpcId = physicalIdByLogicalId(resourcesXml, "Vpc");
        String igwId = physicalIdByLogicalId(resourcesXml, "Igw");

        // The gateway reports an attachment to the stack's VPC.
        describeIgw(igwId)
            .then()
            .statusCode(200)
            .body(containsString(vpcId));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Gateway (and with it the attachment) is gone after stack deletion.
        String afterDelete = describeIgw(igwId).then().statusCode(200).extract().asString();
        assertThat(afterDelete, not(containsString(vpcId)));
    }

    private static io.restassured.response.Response describeIgw(String igwId) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeInternetGateways")
            .formParam("InternetGatewayId.1", igwId)
            .formParam("Version", "2016-11-15")
        .when()
            .post("/");
    }

    private static String physicalIdByLogicalId(String xml, String logicalId) {
        String logicalMarker = "<LogicalResourceId>" + logicalId + "</LogicalResourceId>";
        int logicalIdx = xml.indexOf(logicalMarker);
        assertThat("logical id '" + logicalId + "' present in DescribeStackResources output",
                logicalIdx, not(equalTo(-1)));
        int memberStart = xml.lastIndexOf("<member>", logicalIdx);
        int memberEnd = xml.indexOf("</member>", logicalIdx);
        String member = xml.substring(memberStart, memberEnd);
        String physicalOpen = "<PhysicalResourceId>";
        int pStart = member.indexOf(physicalOpen) + physicalOpen.length();
        int pEnd = member.indexOf("</PhysicalResourceId>");
        return member.substring(pStart, pEnd);
    }
}
