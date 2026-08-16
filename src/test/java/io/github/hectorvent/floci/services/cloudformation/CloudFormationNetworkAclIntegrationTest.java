package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation provisions the network ACL family
 * (issue #2000): a custom ACL with an entry, and a subnet moved onto it via
 * SubnetNetworkAclAssociation. Metadata-only — Docker-free.
 */
@QuarkusTest
class CloudFormationNetworkAclIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void aclEntryAndSubnetAssociationProvision() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-nacl-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.70.0.0/16"}},
                    "Subnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.70.1.0/24"}
                    },
                    "Acl": {"Type": "AWS::EC2::NetworkAcl", "Properties": {"VpcId": {"Ref": "Vpc"}}},
                    "DenyAllInbound": {
                      "Type": "AWS::EC2::NetworkAclEntry",
                      "Properties": {
                        "NetworkAclId": {"Ref": "Acl"},
                        "RuleNumber": 100,
                        "Protocol": "6",
                        "RuleAction": "deny",
                        "CidrBlock": "0.0.0.0/0",
                        "PortRange": {"From": 22, "To": 22}
                      }
                    },
                    "Assoc": {
                      "Type": "AWS::EC2::SubnetNetworkAclAssociation",
                      "Properties": {
                        "SubnetId": {"Ref": "Subnet"},
                        "NetworkAclId": {"Ref": "Acl"}
                      }
                    }
                  },
                  "Outputs": {
                    "AclId": {"Value": {"Ref": "Acl"}},
                    "SubnetId": {"Value": {"Ref": "Subnet"}}
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

        String describe = given()
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

        String aclId = between(describe, "<OutputKey>AclId</OutputKey>", "</member>");
        aclId = between(aclId, "<OutputValue>", "</OutputValue>");
        String subnetId = between(describe, "<OutputKey>SubnetId</OutputKey>", "</member>");
        subnetId = between(subnetId, "<OutputValue>", "</OutputValue>");

        // The custom ACL carries the deny entry and the subnet's association.
        given()
            .formParam("Action", "DescribeNetworkAcls")
            .formParam("NetworkAclId.1", aclId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ruleNumber>100</ruleNumber>"))
            .body(containsString("deny"))
            .body(containsString(subnetId));

        // Deleting the stack reverts the subnet to the default ACL and removes the custom one.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        awaitNetworkAclGone(aclId);
    }

    /** DeleteStack runs asynchronously; poll until the custom ACL disappears. */
    private static void awaitNetworkAclGone(String aclId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String acls = given()
                .formParam("Action", "DescribeNetworkAcls")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();
            if (!acls.contains(aclId)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for network ACL " + aclId + " to be deleted");
    }

    private static String between(String haystack, String open, String close) {
        int i = haystack.indexOf(open);
        int j = haystack.indexOf(close, i + open.length());
        return haystack.substring(i + open.length(), j);
    }
}
