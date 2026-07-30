package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
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

    @Test
    void rejectsServerlessV2ScalingForNonAuroraDbCluster() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-serverless-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Cluster": {
                      "Type": "AWS::RDS::DBCluster",
                      "Properties": {
                        "DBClusterIdentifier": "standard-cluster-%s",
                        "Engine": "postgres",
                        "ServerlessV2ScalingConfiguration": {
                          "MinCapacity": 0.5,
                          "MaxCapacity": 16
                        }
                      }
                    }
                  }
                }
                """.formatted(suffix);

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

        try {
            String stack = awaitStackStatus(stackName, "ROLLBACK_COMPLETE");
            assertThat(stack, containsString(
                    "Parameters that must not be used together were used together"));
            String events = given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStackEvents")
                    .formParam("StackName", stackName)
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            assertThat(events, containsString("<ResourceStatus>CREATE_FAILED</ResourceStatus>"));
            assertThat(events, containsString(
                    "Parameters that must not be used together were used together"));
        } finally {
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

    private String awaitStackStatus(String stackName, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String response = "";
        while (System.currentTimeMillis() < deadline) {
            response = given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH)
                    .formParam("Action", "DescribeStacks")
                    .formParam("StackName", stackName)
                    .when()
                    .post("/")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            if (response.contains("<StackStatus>" + expectedStatus + "</StackStatus>")) {
                return response;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "Stack did not reach " + expectedStatus + ": " + response);
    }
}
