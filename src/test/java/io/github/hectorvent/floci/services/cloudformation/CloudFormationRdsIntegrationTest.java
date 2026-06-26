package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation provisions RDS resources for real. Uses DBSubnetGroup
 * because it does not start a container, so the test stays Docker-free (DBInstance/DBCluster
 * provisioning is covered by the mocked-service {@code RdsCfnProvisionerTest}).
 */
@QuarkusTest
class CloudFormationRdsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/rds/aws4_request";

    @Test
    void createStackProvisionsDbSubnetGroupVisibleToRds() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String groupName = "cfn-rds-subnets-" + suffix;
        String stackName = "cfn-rds-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "DbSubnets": {
                      "Type": "AWS::RDS::DBSubnetGroup",
                      "Properties": {
                        "DBSubnetGroupName": "%s",
                        "DBSubnetGroupDescription": "managed by cfn",
                        "SubnetIds": ["subnet-default-a", "subnet-default-b"]
                      }
                    }
                  },
                  "Outputs": {
                    "GroupName": {"Value": {"Ref": "DbSubnets"}}
                  }
                }
                """.formatted(groupName);

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

        // Stack reaches CREATE_COMPLETE and Ref(DbSubnets) exports the subnet group name.
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
            .body(containsString(groupName));

        // The subnet group really exists in RDS (provisioned, not stubbed).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", RDS_AUTH)
            .formParam("Action", "DescribeDBSubnetGroups")
            .formParam("DBSubnetGroupName", groupName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(groupName));
    }
}
