package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void addressTypeResolvesParameterOnCreateAndUpdate() {
        String stack = "cfn-vpce-ip-" + Long.toString(System.nanoTime(), 36);
        String vpc = XmlParser.extractFirst(given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.63.0.0/16")
                .post("/").then().statusCode(200).extract().asString(), "vpcId", null);
        String template = """
                {"Parameters":{"AddressType":{"Type":"String"}},"Resources":{
                  "Endpoint":{"Type":"AWS::EC2::VPCEndpoint","Properties":{
                    "VpcId":"%s","ServiceName":"com.amazonaws.us-east-1.s3",
                    "IpAddressType":{"Ref":"AddressType"}}}},
                  "Outputs":{"EndpointId":{"Value":{"Ref":"Endpoint"}}}}
                """.formatted(vpc);
        try {
            String action = "CreateStack";
            for (String type : new String[]{"ipv6", "dualstack", "not-specified"}) {
                given().header("Authorization", CFN_AUTH).formParam("Action", action)
                        .formParam("StackName", stack).formParam("TemplateBody", template)
                        .formParam("Parameters.member.1.ParameterKey", "AddressType")
                        .formParam("Parameters.member.1.ParameterValue", type)
                        .post("/").then().statusCode(200);
                assertEquals("CreateStack".equals(action) ? "CREATE_COMPLETE" : "UPDATE_COMPLETE",
                        CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
                String described = given().header("Authorization", CFN_AUTH)
                        .formParam("Action", "DescribeStacks").formParam("StackName", stack)
                        .post("/").then().statusCode(200).extract().asString();
                String id = XmlParser.extractFirst(described, "OutputValue", null);
                String endpoint = given().header("Authorization", EC2_AUTH)
                        .formParam("Action", "DescribeVpcEndpoints").formParam("VpcEndpointId.1", id)
                        .post("/").then().statusCode(200).extract().asString();
                assertEquals("not-specified".equals(type) ? "ipv4" : type,
                        XmlParser.extractFirst(endpoint, "ipAddressType", null));
                action = "UpdateStack";
            }
        } finally {
            given().header("Authorization", CFN_AUTH).formParam("Action", "DeleteStack")
                    .formParam("StackName", stack).post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteVpc")
                    .formParam("VpcId", vpc).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"omit", "dualstack", ""})
    void conditionalAddressTypeOmitsOnlyNoValue(String initialType) {
        String stack = "cfn-vpce-conditional-ip-" + Long.toString(System.nanoTime(), 36);
        String vpc = XmlParser.extractFirst(given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.64.0.0/16")
                .post("/").then().statusCode(200).extract().asString(), "vpcId", null);
        String template = """
                {"Parameters":{"AddressType":{"Type":"String"}},
                  "Conditions":{"UseType":{"Fn::Not":[{"Fn::Equals":[{"Ref":"AddressType"},"omit"]}]}},
                  "Resources":{
                  "Endpoint":{"Type":"AWS::EC2::VPCEndpoint","Properties":{
                    "VpcId":"%s","ServiceName":"com.amazonaws.us-east-1.s3",
                    "IpAddressType":{"Fn::If":["UseType",{"Ref":"AddressType"},{"Ref":"AWS::NoValue"}]}}}},
                  "Outputs":{"EndpointId":{"Value":{"Ref":"Endpoint"}}}}
                """.formatted(vpc);
        try {
            String action = "CreateStack";
            for (String type : new String[]{initialType, "omit".equals(initialType) ? "dualstack" : "omit", initialType}) {
                given().header("Authorization", CFN_AUTH).formParam("Action", action)
                        .formParam("StackName", stack).formParam("TemplateBody", template)
                        .formParam("Parameters.member.1.ParameterKey", "AddressType")
                        .formParam("Parameters.member.1.ParameterValue", type)
                        .post("/").then().statusCode(200);
                if (type.isEmpty()) {
                    CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
                    assertEquals("ROLLBACK_COMPLETE", failed.status(), failed.reason());
                    String failure = CfnStackWaits.failedResourceReason(stack, CFN_AUTH);
                    assertTrue(failure.contains("IpAddressType"), failure);
                    break;
                }
                assertEquals("CreateStack".equals(action) ? "CREATE_COMPLETE" : "UPDATE_COMPLETE",
                        CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
                String described = given().header("Authorization", CFN_AUTH)
                        .formParam("Action", "DescribeStacks").formParam("StackName", stack)
                        .post("/").then().statusCode(200).extract().asString();
                String id = XmlParser.extractFirst(described, "OutputValue", null);
                String endpoint = given().header("Authorization", EC2_AUTH)
                        .formParam("Action", "DescribeVpcEndpoints").formParam("VpcEndpointId.1", id)
                        .post("/").then().statusCode(200).extract().asString();
                assertEquals("omit".equals(type) ? "ipv4" : type,
                        XmlParser.extractFirst(endpoint, "ipAddressType", null));
                action = "UpdateStack";
            }
        } finally {
            given().header("Authorization", CFN_AUTH).formParam("Action", "DeleteStack")
                    .formParam("StackName", stack).post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteVpc")
                    .formParam("VpcId", vpc).post("/").then().statusCode(200);
        }
    }

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
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
        CfnStackWaits.awaitStackDeleted(stackName);

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
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
