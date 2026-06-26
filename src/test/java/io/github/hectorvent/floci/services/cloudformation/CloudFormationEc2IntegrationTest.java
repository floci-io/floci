package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for issue #1297: CloudFormation must provision EC2 VPC/Subnet resources for
 * real (into Ec2Service) so that the exported subnet ids exist in EC2 and can be used by
 * describe-subnets and ELBv2 create-load-balancer. Before the fix the provisioner stubbed these
 * resources with a fake physical id, so the exports referenced subnets that did not exist.
 */
@QuarkusTest
class CloudFormationEc2IntegrationTest {

    // Isolated to ap-southeast-2 so the VPC/subnet/security-group resources this test creates do
    // not pollute the shared in-memory Ec2Service state asserted by ap-southeast-2 EC2 tests.
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/ec2/aws4_request";
    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-2/elasticloadbalancing/aws4_request";

    @Test
    void vpcStackSubnetsAreUsableByDescribeSubnetsAndElbv2() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-vpc-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {"CidrBlock": "10.0.0.0/16"}
                    },
                    "Subnet1": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "CidrBlock": "10.0.1.0/24",
                        "AvailabilityZone": "ap-southeast-2a"
                      }
                    },
                    "Subnet2": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "CidrBlock": "10.0.2.0/24",
                        "AvailabilityZone": "ap-southeast-2b"
                      }
                    }
                  },
                  "Outputs": {
                    "Subnet1Id": {"Value": {"Ref": "Subnet1"}},
                    "Subnet2Id": {"Value": {"Ref": "Subnet2"}}
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        // The exported subnet ids must be real subnet-xxxx ids (not the old stub shape).
        String describeStacks = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        List<String> exportedSubnetIds = extractSubnetIds(describeStacks);
        assertEquals(2, exportedSubnetIds.size(),
                "expected two exported subnet ids, got: " + exportedSubnetIds);

        // describe-subnets must return the exact subnet ids CloudFormation exported.
        String describeSubnets = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", exportedSubnetIds.get(0))
            .formParam("SubnetId.2", exportedSubnetIds.get(1))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        for (String subnetId : exportedSubnetIds) {
            assertTrue(describeSubnets.contains(subnetId),
                    "describe-subnets did not return CloudFormation subnet " + subnetId);
        }

        // ELBv2 create-load-balancer must accept the imported subnet ids (no SubnetNotFound).
        given()
            .header("Authorization", ELB_AUTH)
            .formParam("Action", "CreateLoadBalancer")
            .formParam("Name", "cfn-ec2-alb-" + suffix)
            .formParam("Type", "application")
            .formParam("Scheme", "internal")
            .formParam("Subnets.member.1", exportedSubnetIds.get(0))
            .formParam("Subnets.member.2", exportedSubnetIds.get(1))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LoadBalancerArn>"));
    }

    @Test
    void subnetAzAndCidrResolveFromGetAzsAndCidr() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-ec2-fn-" + suffix;

        // Subnet AZ comes from Fn::Select(Fn::GetAZs("")) and its CIDR from Fn::Cidr — the
        // pattern CDK emits. Both must resolve to real values on the provisioned subnet.
        String template = """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {"CidrBlock": "10.30.0.0/16"}
                    },
                    "Subnet1": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {
                        "VpcId": {"Ref": "Vpc"},
                        "CidrBlock": {"Fn::Select": [1, {"Fn::Cidr": ["10.30.0.0/16", 4, 8]}]},
                        "AvailabilityZone": {"Fn::Select": [0, {"Fn::GetAZs": ""}]}
                      }
                    }
                  },
                  "Outputs": {
                    "Subnet1Id": {"Value": {"Ref": "Subnet1"}}
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

        String describeStacks = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        List<String> subnetIds = extractSubnetIds(describeStacks);
        assertEquals(1, subnetIds.size(), "expected one exported subnet id, got: " + subnetIds);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetIds.get(0))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // AZ resolved from Fn::Select(0, Fn::GetAZs("")) in the stack's region.
            .body(containsString("ap-southeast-2a"))
            // CIDR resolved from Fn::Select(1, Fn::Cidr(["10.30.0.0/16", 4, 8])).
            .body(containsString("10.30.1.0/24"));
    }

    private static List<String> extractSubnetIds(String xml) {
        Matcher m = Pattern.compile("<OutputValue>(subnet-[0-9a-fA-F]+)</OutputValue>").matcher(xml);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }
}
