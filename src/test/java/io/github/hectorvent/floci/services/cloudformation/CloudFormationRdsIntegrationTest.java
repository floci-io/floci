package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void updateStackReconcilesExistingDbSubnetGroupInsteadOfFailing() {
        // Regression for lex00/floci#16: provision() re-runs on every UpdateStack for every resource
        // regardless of whether its properties changed, so a fixed-name DBSubnetGroup left unchanged
        // between deploys used to call CreateDBSubnetGroup again and roll back with
        // "DB subnet group ... already exists".
        String suffix = Long.toString(System.nanoTime(), 36);
        String groupName = "cfn-rds-subnets-update-" + suffix;
        String stackName = "cfn-rds-update-stack-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", dbSubnetGroupTemplate(groupName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String beforeXml = given()
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
        String groupNameBefore = XmlParser.extractPairs(beforeXml, "Outputs", "OutputKey", "OutputValue")
                .get("GroupName");

        // Redeploy the identical template: on real AWS this is a no-op update.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", dbSubnetGroupTemplate(groupName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String afterXml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")))
            .extract().asString();
        String groupNameAfter = XmlParser.extractPairs(afterXml, "Outputs", "OutputKey", "OutputValue")
                .get("GroupName");

        // The physical id (Ref, which for a subnet group is its name) is unchanged across the update.
        assertEquals(groupNameBefore, groupNameAfter);

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

    private static String dbSubnetGroupTemplate(String groupName) {
        return """
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
    }
}
