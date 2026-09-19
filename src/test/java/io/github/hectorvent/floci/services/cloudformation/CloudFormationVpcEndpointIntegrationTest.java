package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        String stackDescription = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertTrue(stackDescription.contains("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        String endpointId = XmlParser.extractFirst(stackDescription, "OutputValue", null);
        assertNotNull(endpointId);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String endpointsAfterDelete = given()
            .formParam("Action", "DescribeVpcEndpoints")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertFalse(endpointsAfterDelete.contains(endpointId));
    }

    @Test
    void createStackPublishesTheDnsEntriesAttribute() {
        String stackName = "cfn-vpce-dns-" + Long.toString(System.nanoTime(), 36);

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.62.0.0/16"}},
                    "Subnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "CidrBlock": "10.62.1.0/24",
                        "AvailabilityZone": "us-east-1a"
                      }
                    },
                    "Endpoint": {
                      "Type": "AWS::EC2::VPCEndpoint",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "ServiceName": "com.amazonaws.us-east-1.ecr.api",
                        "VpcEndpointType": "Interface",
                        "SubnetIds": [{"Ref": "Subnet"}]
                      }
                    }
                  },
                  "Outputs": {
                    "Entries": {"Value": {"Fn::GetAtt": ["Endpoint", "DnsEntries"]}}
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

        String stackDescription = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String entries = XmlParser.extractFirst(stackDescription, "OutputValue", null);
        assertNotNull(entries);
        String[] pairs = entries.split(",");
        assertEquals(3, pairs.length, "regional, one zone, then private DNS, got " + entries);
        assertTrue(pairs[0].matches("Z[0-9A-Z]+:vpce-[0-9a-f]+-[0-9a-f]+\\.api\\.ecr\\.us-east-1\\.vpce\\.amazonaws\\.com"),
                "first entry should be hostedZoneId:regionalName, got " + pairs[0]);
        assertTrue(pairs[1].contains("-us-east-1a."),
                "second entry should be the zonal name, got " + pairs[1]);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
