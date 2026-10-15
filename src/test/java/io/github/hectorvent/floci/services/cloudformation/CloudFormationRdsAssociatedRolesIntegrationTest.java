package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AssociatedRoles} on {@code AWS::RDS::DBCluster} and {@code AWS::RDS::DBInstance}: the
 * roles are associated on create, an update adds the ones the template gained and removes the
 * ones it dropped (leaving roles associated outside the stack alone), and a list RDS refuses fails
 * the resource without leaving the database behind.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class CloudFormationRdsAssociatedRolesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/cloudformation/aws4_request";
    private static final String RDS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    private static final String EXPORT_ROLE = "arn:aws:iam::000000000000:role/cfn-rds-export";
    private static final String LAMBDA_ROLE = "arn:aws:iam::000000000000:role/cfn-rds-lambda";
    private static final String MANUAL_ROLE = "arn:aws:iam::000000000000:role/cfn-rds-manual";

    @Test
    void rolesFollowTheTemplateAcrossCreateAndUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-" + suffix;
        String clusterId = "cfn-roles-cluster-" + suffix;
        String instanceId = "cfn-roles-db-" + suffix;
        try {
            stack("CreateStack", stackName, template(clusterId, instanceId, """
                    [{"RoleArn": {"Fn::GetAtt": ["ImportRole", "Arn"]}, "FeatureName": "s3Import"}]
                    """));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            String importRole = clusterRoles(clusterId).get(0);
            assertTrue(importRole.endsWith(":role/cfn-rds-import-" + suffix), importRole);
            assertEquals(List.of(LAMBDA_ROLE), instanceRoles(instanceId));

            // A role associated outside the stack is not the stack's to remove.
            rds("AddRoleToDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("RoleArn", MANUAL_ROLE)
            .when().post("/").then().statusCode(200);

            stack("UpdateStack", stackName, template(clusterId, instanceId, """
                    [{"RoleArn": "%s", "FeatureName": "s3Export"}]
                    """.formatted(EXPORT_ROLE)));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertThat(clusterRoles(clusterId), containsInAnyOrder(MANUAL_ROLE, EXPORT_ROLE));
            assertEquals(List.of(LAMBDA_ROLE), instanceRoles(instanceId));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void aRefusedRoleListLeavesNoClusterBehind() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-twice-" + suffix;
        String clusterId = "cfn-roles-twice-" + suffix;
        try {
            stack("CreateStack", stackName, """
                    {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                      "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                      "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                      "AssociatedRoles": [{"RoleArn": "%s"}, {"RoleArn": "%s"}]}}}}
                    """.formatted(clusterId, EXPORT_ROLE, EXPORT_ROLE));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertTrue(state.status().startsWith("ROLLBACK") || state.status().equals("CREATE_FAILED"),
                    state.status());
            rds("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(404);
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void anAuroraMemberCannotCarryItsOwnRoles() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-member-" + suffix;
        String clusterId = "cfn-roles-member-cluster-" + suffix;
        try {
            stack("CreateStack", stackName, """
                    {"Resources": {
                      "Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                        "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                        "MasterUsername": "dbadmin", "MasterUserPassword": "password123"}},
                      "Member": {"Type": "AWS::RDS::DBInstance", "Properties": {
                        "DBClusterIdentifier": {"Ref": "Cluster"}, "Engine": "aurora-postgresql",
                        "DBInstanceClass": "db.r6g.large",
                        "AssociatedRoles": [{"RoleArn": "%s", "FeatureName": "s3Import"}]}}}}
                    """.formatted(clusterId, EXPORT_ROLE));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertTrue(state.status().startsWith("ROLLBACK") || state.status().equals("CREATE_FAILED"),
                    state.status());
            String events = stack("DescribeStackEvents", stackName, null);
            assertTrue(events.contains("AssociatedRoles is not applicable"), events);
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void conditionsAroundTheListAndItsEntriesAreResolved() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-if-" + suffix;
        String listCluster = "cfn-roles-if-list-" + suffix;
        String entryCluster = "cfn-roles-if-entry-" + suffix;
        try {
            stack("CreateStack", stackName, """
                    {"Conditions": {"Yes": {"Fn::Equals": ["a", "a"]}, "No": {"Fn::Equals": ["a", "b"]}},
                     "Resources": {
                      "ListCluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                        "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                        "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                        "AssociatedRoles": {"Fn::If": ["Yes", [{"RoleArn": "%s", "FeatureName": "s3Export"}],
                                                       {"Ref": "AWS::NoValue"}]}}},
                      "EntryCluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                        "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                        "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                        "AssociatedRoles": [
                          {"Fn::If": ["Yes", {"RoleArn": "%s", "FeatureName": "Lambda"}, {"Ref": "AWS::NoValue"}]},
                          {"Fn::If": ["No", {"RoleArn": "%s"}, {"Ref": "AWS::NoValue"}]}]}}}}
                    """.formatted(listCluster, EXPORT_ROLE, entryCluster, LAMBDA_ROLE, MANUAL_ROLE));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(List.of(EXPORT_ROLE), clusterRoles(listCluster));
            assertEquals(List.of(LAMBDA_ROLE), clusterRoles(entryCluster));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void anUpdateWithAMalformedOrRepeatedListKeepsTheRoles() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-bad-update-" + suffix;
        String clusterId = "cfn-roles-bad-update-" + suffix;
        String cluster = """
                {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                  "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                  "AssociatedRoles": %s}}}}
                """;
        try {
            stack("CreateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\", \"FeatureName\": \"s3Export\"}]".formatted(EXPORT_ROLE)));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // A value that is not a list is refused, not read as "no roles".
            stack("UpdateStack", stackName, cluster.formatted(clusterId, "\"%s\"".formatted(LAMBDA_ROLE)));
            assertTrue(CfnStackWaits.awaitTerminal(stackName).status().startsWith("UPDATE_ROLLBACK"));
            assertEquals(List.of(EXPORT_ROLE), clusterRoles(clusterId));

            // A role named twice is refused on an update as on a create, before anything changes.
            stack("UpdateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}, {\"RoleArn\": \"%s\"}]".formatted(LAMBDA_ROLE, LAMBDA_ROLE)));
            assertTrue(CfnStackWaits.awaitTerminal(stackName).status().startsWith("UPDATE_ROLLBACK"));
            assertEquals(List.of(EXPORT_ROLE), clusterRoles(clusterId));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void aRepeatedInstanceRoleIsSkippedAsAwsDoes() {
        // AWS's DBInstance handler ignores DBInstanceRoleAlreadyExists, so a repeated role or
        // feature does not fail the resource.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-instance-twice-" + suffix;
        String instanceId = "cfn-roles-instance-twice-" + suffix;
        try {
            stack("CreateStack", stackName, """
                    {"Resources": {"Database": {"Type": "AWS::RDS::DBInstance", "Properties": {
                      "DBInstanceIdentifier": "%s", "Engine": "postgres", "DBInstanceClass": "db.t3.micro",
                      "MasterUsername": "dbadmin", "MasterUserPassword": "password123", "AllocatedStorage": 20,
                      "AssociatedRoles": [{"RoleArn": "%s", "FeatureName": "Lambda"},
                                          {"RoleArn": "%s", "FeatureName": "Lambda"},
                                          {"RoleArn": "%s", "FeatureName": "Lambda"}]}}}}
                    """.formatted(instanceId, LAMBDA_ROLE, LAMBDA_ROLE, EXPORT_ROLE));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals(List.of(LAMBDA_ROLE), instanceRoles(instanceId));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void declaringARoleAttachedByHandFailsTheClusterUpdate() {
        // On AWS a cluster update that newly declares a role already attached outside the stack
        // fails with DBClusterRoleAlreadyExists.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-hand-" + suffix;
        String clusterId = "cfn-roles-hand-" + suffix;
        String cluster = """
                {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                  "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                  "AssociatedRoles": %s}}}}
                """;
        try {
            stack("CreateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}]".formatted(EXPORT_ROLE)));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            rds("AddRoleToDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("RoleArn", MANUAL_ROLE)
            .when().post("/").then().statusCode(200);

            stack("UpdateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}, {\"RoleArn\": \"%s\"}]".formatted(EXPORT_ROLE, MANUAL_ROLE)));
            assertTrue(CfnStackWaits.awaitTerminal(stackName).status().startsWith("UPDATE_ROLLBACK"));
            String events = stack("DescribeStackEvents", stackName, null);
            assertTrue(events.contains("already associated"), events);
            assertThat(clusterRoles(clusterId), containsInAnyOrder(EXPORT_ROLE, MANUAL_ROLE));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void aFailedUpdateUndoesItsOwnRoleChanges() {
        // The update removes EXPORT_ROLE and adds LAMBDA_ROLE before MANUAL_ROLE, attached by hand,
        // is refused. Both changes are undone, so the cluster matches the record the stack keeps
        // and a retry without MANUAL_ROLE goes through.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-rds-roles-undo-" + suffix;
        String clusterId = "cfn-roles-undo-" + suffix;
        String cluster = """
                {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                  "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                  "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                  "AssociatedRoles": %s}}}}
                """;
        try {
            stack("CreateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}]".formatted(EXPORT_ROLE)));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            rds("AddRoleToDBCluster").formParam("DBClusterIdentifier", clusterId)
                    .formParam("RoleArn", MANUAL_ROLE)
            .when().post("/").then().statusCode(200);

            stack("UpdateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}, {\"RoleArn\": \"%s\"}]".formatted(LAMBDA_ROLE, MANUAL_ROLE)));
            assertTrue(CfnStackWaits.awaitTerminal(stackName).status().startsWith("UPDATE_ROLLBACK"));
            assertThat(clusterRoles(clusterId), containsInAnyOrder(EXPORT_ROLE, MANUAL_ROLE));

            stack("UpdateStack", stackName, cluster.formatted(clusterId,
                    "[{\"RoleArn\": \"%s\"}, {\"RoleArn\": \"%s\"}]".formatted(EXPORT_ROLE, LAMBDA_ROLE)));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertThat(clusterRoles(clusterId), containsInAnyOrder(EXPORT_ROLE, LAMBDA_ROLE, MANUAL_ROLE));
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private static String template(String clusterId, String instanceId, String clusterRoles) {
        return """
                {"Resources": {
                  "ImportRole": {"Type": "AWS::IAM::Role", "Properties": {
                    "RoleName": "cfn-rds-import-%s",
                    "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                      "Principal": {"Service": "rds.amazonaws.com"}, "Action": "sts:AssumeRole"}]}}},
                  "Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {
                    "DBClusterIdentifier": "%s", "Engine": "aurora-postgresql",
                    "MasterUsername": "dbadmin", "MasterUserPassword": "password123",
                    "AssociatedRoles": %s}},
                  "Database": {"Type": "AWS::RDS::DBInstance", "Properties": {
                    "DBInstanceIdentifier": "%s", "Engine": "postgres", "DBInstanceClass": "db.t3.micro",
                    "MasterUsername": "dbadmin", "MasterUserPassword": "password123", "AllocatedStorage": 20,
                    "AssociatedRoles": [{"RoleArn": "%s", "FeatureName": "Lambda"}]}}}}
                """.formatted(clusterId.substring(clusterId.lastIndexOf('-') + 1), clusterId, clusterRoles.strip(),
                instanceId, LAMBDA_ROLE);
    }

    private static List<String> clusterRoles(String clusterId) {
        XmlPath xml = rds("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        return xml.getList("**.findAll { it.name() == 'DBClusterRole' }.RoleArn", String.class);
    }

    private static List<String> instanceRoles(String instanceId) {
        XmlPath xml = rds("DescribeDBInstances").formParam("DBInstanceIdentifier", instanceId)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        return xml.getList("**.findAll { it.name() == 'DBInstanceRole' }.RoleArn", String.class);
    }

    private static RequestSpecification rds(String action) {
        return given().header("Authorization", RDS_AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static String stack(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.when().post("/").then().statusCode(200).extract().asString();
    }
}
