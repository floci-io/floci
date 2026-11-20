package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.floci.aws.testing.CfnRequests.deploy;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsDeletionProtectionIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261119/us-east-1/cloudformation/aws4_request";

    private static String template(String clusterId, String deletionProtection) {
        return """
                {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                  "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                  "DeletionProtection": "%s"}}}}
                """.formatted(clusterId, deletionProtection);
    }

    private static void describeReports(String clusterId, String expected) {
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
                .when().post("/").then().statusCode(200)
                .body(containsString("<DeletionProtection>" + expected + "</DeletionProtection>"));
    }

    @Test
    void stackClusterFollowsDeletionProtectionAndRefusesStackDeleteWhileProtected() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-dp-" + suffix;
        String clusterId = "cfn-dp-cluster-" + suffix;
        try {
            deploy("CreateStack", stackName, template(clusterId, "true"), CFN_AUTH, "CREATE_COMPLETE");
            describeReports(clusterId, "true");

            deploy("UpdateStack", stackName, template(clusterId, "false"), CFN_AUTH, "UPDATE_COMPLETE");
            describeReports(clusterId, "false");
            deploy("UpdateStack", stackName, template(clusterId, "true"), CFN_AUTH, "UPDATE_COMPLETE");
            describeReports(clusterId, "true");

            cfn("DeleteStack", stackName).header("Authorization", CFN_AUTH)
                    .when().post("/").then().statusCode(200);
            assertEquals("DELETE_FAILED", CfnStackWaits.awaitTerminal(stackName, CFN_AUTH).status());
            describeReports(clusterId, "true");
        } finally {
            rds("ModifyDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("DeletionProtection", "false").when().post("/");
            CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        }
    }
}
