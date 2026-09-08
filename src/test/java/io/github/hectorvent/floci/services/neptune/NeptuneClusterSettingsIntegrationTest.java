package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneClusterSettingsIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String ID = "tf-neptune-settings";
    private static final String INSTANCE_ID = "tf-neptune-settings-db";
    private static final String ARN = "arn:aws:neptune:us-east-1:000000000000:cluster:" + ID;
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/neptune-load";

    private static RequestSpecification signedAs(String service, String action) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260903/us-east-1/" + service
                        + "/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(FORM)
                .formParam("Action", action);
    }

    private static RequestSpecification rds(String action) {
        return signedAs("rds", action);
    }

    @Test
    @Order(1)
    void createEchoesEverySettingTerraformSends() {
        rds("CreateDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("Engine", "neptune")
            .formParam("Port", "8200")
            .formParam("StorageEncrypted", "false")
            .formParam("DeletionProtection", "true")
            .formParam("CopyTagsToSnapshot", "false")
            .formParam("BackupRetentionPeriod", "7")
            .formParam("AvailabilityZones.AvailabilityZone.1", "us-east-1a")
            .formParam("AvailabilityZones.AvailabilityZone.2", "us-east-1b")
            .formParam("EnableCloudwatchLogsExports.member.1", "audit")
            .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-11111111")
            .formParam("PreferredBackupWindow", "03:00-04:00")
            .formParam("PreferredMaintenanceWindow", "Sun:05:00-Sun:06:00")
            .formParam("ServerlessV2ScalingConfiguration.MinCapacity", "2.5")
            .formParam("ServerlessV2ScalingConfiguration.MaxCapacity", "16")
            .formParam("Tags.Tag.1.Key", "Name")
            .formParam("Tags.Tag.1.Value", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Port>8200</Port>"))
            .body(containsString("<StorageEncrypted>false</StorageEncrypted>"))
            .body(containsString("<DeletionProtection>true</DeletionProtection>"))
            .body(containsString("<CopyTagsToSnapshot>false</CopyTagsToSnapshot>"))
            .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"))
            .body(containsString("<AvailabilityZones><AvailabilityZone>us-east-1a</AvailabilityZone>"
                    + "<AvailabilityZone>us-east-1b</AvailabilityZone></AvailabilityZones>"))
            .body(containsString("<EnabledCloudwatchLogsExports><member>audit</member></EnabledCloudwatchLogsExports>"))
            .body(containsString("<VpcSecurityGroupMembership><VpcSecurityGroupId>sg-11111111</VpcSecurityGroupId>"))
            .body(containsString("<PreferredBackupWindow>03:00-04:00</PreferredBackupWindow>"))
            .body(containsString("<PreferredMaintenanceWindow>sun:05:00-sun:06:00</PreferredMaintenanceWindow>"))
            .body(containsString("<ServerlessV2ScalingConfiguration><MinCapacity>2.5</MinCapacity>"
                    + "<MaxCapacity>16.0</MaxCapacity></ServerlessV2ScalingConfiguration>"))
            .body(containsString("<DBClusterParameterGroup>default.neptune1.3</DBClusterParameterGroup>"))
            .body(containsString("<DBSubnetGroup>default</DBSubnetGroup>"))
            .body(containsString("<ClusterCreateTime>"))
            .body(containsString("<DBClusterArn>" + ARN + "</DBClusterArn>"));
    }

    @Test
    @Order(2)
    void describeOnTheRdsScopeReadsTheSameSettingsBack() {
        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Port>8200</Port>"))
            .body(containsString("<StorageEncrypted>false</StorageEncrypted>"))
            .body(containsString("<DeletionProtection>true</DeletionProtection>"))
            .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"))
            .body(containsString("<AvailabilityZone>us-east-1b</AvailabilityZone>"))
            .body(containsString("<AssociatedRoles></AssociatedRoles>"));
    }

    @Test
    @Order(3)
    void tagsRoundTripThroughTheRdsScopeByNeptuneArn() {
        rds("ListTagsForResource")
            .formParam("ResourceName", ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<TagList><Tag><Key>Name</Key><Value>" + ID + "</Value></Tag></TagList>"));

        rds("AddTagsToResource")
            .formParam("ResourceName", ARN)
            .formParam("Tags.Tag.1.Key", "team")
            .formParam("Tags.Tag.1.Value", "graph")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AddTagsToResourceResponse"));

        rds("RemoveTagsFromResource")
            .formParam("ResourceName", ARN)
            .formParam("TagKeys.member.1", "Name")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("ListTagsForResource")
            .formParam("ResourceName", ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Tag><Key>team</Key><Value>graph</Value></Tag>"))
            .body(not(containsString("<Key>Name</Key>")));
    }

    @Test
    @Order(4)
    void tagsForAForeignArnAreNotFound() {
        signedAs("neptune", "ListTagsForResource")
            .formParam("ResourceName", ARN.replace("us-east-1", "eu-west-1"))
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    @Order(5)
    void rolesAreAssociatedAndListedOnTheCluster() {
        rds("AddRoleToDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AddRoleToDBClusterResponse"));

        rds("AddRoleToDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterRoleAlreadyExists"));

        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AssociatedRoles><DBClusterRole><RoleArn>" + ROLE_ARN
                    + "</RoleArn><Status>ACTIVE</Status></DBClusterRole></AssociatedRoles>"));

        rds("RemoveRoleFromDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(200);

        rds("RemoveRoleFromDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterRoleNotFound"));
    }

    @Test
    @Order(6)
    void unsignedRoleActionsRouteToNeptune() {
        given().contentType(FORM).formParam("Action", "AddRoleToDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<AddRoleToDBClusterResponse"));

        given().contentType(FORM).formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<RoleArn>" + ROLE_ARN + "</RoleArn>"));

        given().contentType(FORM).formParam("Action", "RemoveRoleFromDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("RoleArn", ROLE_ARN)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void describeGlobalClustersIsEmptyOnBothScopes() {
        signedAs("neptune", "DescribeGlobalClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusters></GlobalClusters>"));

        rds("DescribeGlobalClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusters>"));
    }

    @Test
    @Order(8)
    void deleteIsRefusedWhileDeletionProtectionIsOn() {
        rds("DeleteDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("SkipFinalSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
    }

    @Test
    @Order(9)
    void modifyAppliesTheChangedSettingsOnly() {
        rds("ModifyDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("ApplyImmediately", "true")
            .formParam("AllowMajorVersionUpgrade", "false")
            .formParam("DeletionProtection", "false")
            .formParam("BackupRetentionPeriod", "3")
            .formParam("CloudwatchLogsExportConfiguration.EnableLogTypes.member.1", "slowquery")
            .formParam("CloudwatchLogsExportConfiguration.DisableLogTypes.member.1", "audit")
            .formParam("VpcSecurityGroupIds", "")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeletionProtection>false</DeletionProtection>"))
            .body(containsString("<BackupRetentionPeriod>3</BackupRetentionPeriod>"))
            .body(containsString("<EnabledCloudwatchLogsExports><member>slowquery</member></EnabledCloudwatchLogsExports>"))
            .body(containsString("<VpcSecurityGroups></VpcSecurityGroups>"))
            .body(containsString("<Port>8200</Port>"))
            .body(containsString("<PreferredBackupWindow>03:00-04:00</PreferredBackupWindow>"));
    }

    @Test
    @Order(10)
    void clusterMembersUseTheDbClusterMemberElement() {
        rds("CreateDBInstance")
            .formParam("DBInstanceIdentifier", INSTANCE_ID)
            .formParam("DBClusterIdentifier", ID)
            .formParam("DBInstanceClass", "db.r5.large")
            .formParam("Engine", "neptune")
        .when().post("/")
        .then()
            .statusCode(200);

        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterMembers><DBClusterMember><DBInstanceIdentifier>" + INSTANCE_ID
                    + "</DBInstanceIdentifier><IsClusterWriter>true</IsClusterWriter></DBClusterMember></DBClusterMembers>"));

        rds("DeleteDBInstance")
            .formParam("DBInstanceIdentifier", INSTANCE_ID)
        .when().post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void deleteSucceedsOnceDeletionProtectionIsOff() {
        rds("DeleteDBCluster")
            .formParam("DBClusterIdentifier", ID)
            .formParam("SkipFinalSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(ID));

        rds("DescribeDBClusters")
            .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }
}
