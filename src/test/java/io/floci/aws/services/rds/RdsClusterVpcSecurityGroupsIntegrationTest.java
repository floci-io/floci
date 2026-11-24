package io.floci.aws.services.rds;

import io.floci.aws.core.common.BackupWindows;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.floci.aws.testing.Ec2Requests.createDefaultVpcSecurityGroup;
import static io.floci.aws.testing.Ec2Requests.ec2;
import static io.floci.aws.testing.RdsRequests.createAuroraPostgresCluster;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VpcSecurityGroupIds, PreferredBackupWindow and PreferredMaintenanceWindow given to CreateDBCluster
 * and ModifyDBCluster are stored on the cluster and read back through DescribeDBClusters, instead of a
 * fixed sg-00000000 membership and fixed windows, and a request refused for a malformed value changes
 * nothing.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterVpcSecurityGroupsIntegrationTest {

    private static final String ID = "vpc-sg-cluster-" + System.nanoTime();

    private static List<String> groupIds(String xml) {
        try {
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName("VpcSecurityGroupId");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                ids.add(nodes.item(i).getTextContent());
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("unparseable RDS response: " + xml, e);
        }
    }

    private static List<String> describedGroupIds() {
        return groupIds(rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200).extract().asString());
    }

    private final List<String> createdGroups = new ArrayList<>();

    /**
     * A new security group of the default VPC, which a cluster created without a DB subnet group is
     * placed in: CreateDBCluster and ModifyDBCluster refuse a group EC2 does not know.
     */
    private String securityGroup(String name) {
        String groupId = createDefaultVpcSecurityGroup("test", "us-east-1", name + "-" + System.nanoTime());
        createdGroups.add(groupId);
        return groupId;
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        for (String groupId : createdGroups) {
            ec2("DeleteSecurityGroup", "test", "us-east-1").formParam("GroupId", groupId).when().post("/");
        }
        // after the cluster, which may reference it; absent in most tests
        rds("DeleteDBClusterParameterGroup").formParam("DBClusterParameterGroupName", "pg-rejected")
                .when().post("/");
    }

    @Test
    void createStoresRequestedGroupsAndModifyReplacesThem() {
        String first = securityGroup("first");
        String second = securityGroup("second");
        String third = securityGroup("third");
        String created = createAuroraPostgresCluster(ID)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", first)
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(List.of(first), groupIds(created));
        assertEquals(List.of(first), describedGroupIds());

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", second)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", third)
                .when().post("/").then().statusCode(200);
        assertEquals(List.of(second, third), describedGroupIds());

        // A modify that does not name VpcSecurityGroupIds leaves them alone.
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("EnableIAMDatabaseAuthentication", "true")
                .when().post("/").then().statusCode(200);
        assertEquals(List.of(second, third), describedGroupIds());
    }

    @Test
    void omittedGroupsDefaultToTheVpcsDefaultSecurityGroup() {
        String created = createAuroraPostgresCluster(ID).when().post("/").then().statusCode(200).extract().asString();
        List<String> groups = describedGroupIds();
        assertEquals(1, groups.size());
        assertNotEquals("sg-00000000", groups.get(0));
        assertTrue(groups.get(0).startsWith("sg-"));
        assertEquals(groups, groupIds(created));
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "pitr"})
    void restoredClusterHoldsRequestedOrDefaultGroupsAndModifyLeavesThem(String path) {
        String restored = ID + "-restored";
        String snapshot = ID + "-snap";
        try {
            createAuroraPostgresCluster(ID).when().post("/").then().statusCode(200);
            rds("CreateDBClusterSnapshot").formParam("DBClusterIdentifier", ID)
                    .formParam("DBClusterSnapshotIdentifier", snapshot).post("/").then().statusCode(200);
            for (String group : new String[] {null, "sg-abcdef01"}) {
                RequestSpecification restore = "snapshot".equals(path)
                        ? rds("RestoreDBClusterFromSnapshot").formParam("SnapshotIdentifier", snapshot)
                                .formParam("Engine", "aurora-postgresql")
                        : rds("RestoreDBClusterToPointInTime").formParam("SourceDBClusterIdentifier", ID)
                                .formParam("UseLatestRestorableTime", "true");
                restore.formParam("DBClusterIdentifier", restored);
                if (group != null) {
                    restore.formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", group);
                }
                List<String> reported = groupIds(restore.post("/").then().statusCode(200).extract().asString());
                if (group != null) {
                    assertEquals(List.of(group), reported);
                } else {
                    assertEquals(1, reported.size());
                    assertNotEquals("sg-00000000", reported.get(0));
                }
                rds("ModifyDBCluster").formParam("DBClusterIdentifier", restored)
                        .formParam("EnableIAMDatabaseAuthentication", "true").post("/").then().statusCode(200);
                assertEquals(reported, groupIds(rds("DescribeDBClusters")
                        .formParam("DBClusterIdentifier", restored).post("/").then().statusCode(200)
                        .extract().asString()));
                rds("DeleteDBCluster").formParam("DBClusterIdentifier", restored)
                        .formParam("SkipFinalSnapshot", "true").post("/");
            }
        } finally {
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", restored)
                    .formParam("SkipFinalSnapshot", "true").post("/");
            rds("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", snapshot).post("/");
        }
    }

    @Test
    void maintenanceWindowDayNamesReadBackLowerCase() {
        String created = createAuroraPostgresCluster(ID)
                .formParam("PreferredMaintenanceWindow", "Mon:00:00-Mon:03:00")
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(true, created.contains("<PreferredMaintenanceWindow>mon:00:00-mon:03:00<"));
        assertEquals("mon:00:00-mon:03:00", described("PreferredMaintenanceWindow"));

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredMaintenanceWindow", "TUE:07:00-Tue:08:00")
                .when().post("/").then().statusCode(200);
        assertEquals("tue:07:00-tue:08:00", described("PreferredMaintenanceWindow"));
    }

    @Test
    void modifyOfAMissingClusterIsDbClusterNotFound() {
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", "no-such-cluster")
                .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterNotFoundFault</Code>"))
                .body(containsString("<Message>DBCluster not found</Message>"));
    }

    private static String described(String tag) {
        String xml = rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200).extract().asString();
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName(tag).item(0).getTextContent();
        } catch (Exception e) {
            throw new IllegalStateException("no " + tag + " in RDS response: " + xml, e);
        }
    }

    @Test
    void windowsAreStoredAndDefaulted() {
        createAuroraPostgresCluster(ID).when().post("/").then().statusCode(200);
        assertEquals("mon:00:00-mon:03:00", described("PreferredMaintenanceWindow"));
        assertEquals("04:00-06:00", described("PreferredBackupWindow"));
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        String created = createAuroraPostgresCluster(ID)
                .formParam("PreferredBackupWindow", "05:00-06:00")
                .formParam("PreferredMaintenanceWindow", "tue:07:00-tue:08:00")
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(true, created.contains("<PreferredBackupWindow>05:00-06:00</PreferredBackupWindow>"));
        assertEquals("05:00-06:00", described("PreferredBackupWindow"));
        assertEquals("tue:07:00-tue:08:00", described("PreferredMaintenanceWindow"));

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "03:00-04:00")
                .when().post("/").then().statusCode(200);
        assertEquals("03:00-04:00", described("PreferredBackupWindow"));
        assertEquals("tue:07:00-tue:08:00", described("PreferredMaintenanceWindow"));
    }

    private static int clusterCount() {
        return rds("DescribeDBClusters").when().post("/").then().statusCode(200).extract().asString()
                .split("<DBClusterIdentifier>" + ID + "</DBClusterIdentifier>", -1).length - 1;
    }

    @Test
    void invalidWindowsAreRefusedAndLeaveNoClusterBehind() {
        createAuroraPostgresCluster(ID).formParam("PreferredBackupWindow", "garbage").when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());
        createAuroraPostgresCluster(ID).formParam("PreferredBackupWindow", "05:00-06:00")
                .formParam("PreferredMaintenanceWindow", "mon:05:30-mon:06:30")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());

        createAuroraPostgresCluster(ID).formParam("PreferredBackupWindow", "05:00-06:00").when().post("/").then().statusCode(200);
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "garbage").when().post("/").then().statusCode(400);
        assertEquals("05:00-06:00", described("PreferredBackupWindow"));
    }

    private static void assertOverlapRefused(ValidatableResponse r) {
        r.statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void windowGivenAloneIsPairedWithAClearOneOnCreateAndRefusedOnModify() {
        // default maintenance mon:00:00-mon:03:00 overlaps 01:00-02:00, so a clear one is derived
        createAuroraPostgresCluster(ID).formParam("PreferredBackupWindow", "01:00-02:00").when().post("/").then().statusCode(200);
        assertEquals("01:00-02:00", described("PreferredBackupWindow"));
        String maint = described("PreferredMaintenanceWindow");
        assertEquals(false, BackupWindows.overlap("01:00-02:00", maint), maint);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        // default backup 04:00-06:00 overlaps mon:04:30-mon:05:30
        createAuroraPostgresCluster(ID).formParam("PreferredMaintenanceWindow", "mon:04:30-mon:05:30")
                .when().post("/").then().statusCode(200);
        String backup = described("PreferredBackupWindow");
        assertEquals(false, BackupWindows.overlap(backup, "mon:04:30-mon:05:30"), backup);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        // never-set windows count as the defaults on modify
        createAuroraPostgresCluster(ID).when().post("/").then().statusCode(200);
        assertOverlapRefused(rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "01:00-02:00").when().post("/").then());
        assertOverlapRefused(rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredMaintenanceWindow", "mon:04:30-mon:05:30").when().post("/").then());
        assertEquals("04:00-06:00", described("PreferredBackupWindow"));
    }

    @Test
    void modifyWithABadWindowChangesNothing() {
        createAuroraPostgresCluster(ID).when().post("/").then().statusCode(200);
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "9").formParam("PreferredBackupWindow", "garbage")
                .when().post("/").then().statusCode(400);
        assertEquals("1", described("BackupRetentionPeriod"));
    }

    @Test
    void createWithMalformedDeletionProtectionLeavesNoClusterBehind() {
        createAuroraPostgresCluster(ID).formParam("DeletionProtection", "garbage").when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());
    }

    @Test
    void modifyWithMalformedDeletionProtectionChangesNothing() {
        rds("CreateDBClusterParameterGroup").formParam("DBClusterParameterGroupName", "pg-rejected")
                .formParam("DBParameterGroupFamily", "aurora-postgresql15").formParam("Description", "d")
                .when().post("/").then().statusCode(200);
        String old = securityGroup("old");
        createAuroraPostgresCluster(ID).formParam("MasterUserPassword", "original-password1")
                .formParam("VpcSecurityGroupIds.member.1", old).when().post("/").then().statusCode(200);
        String groupBefore = described("DBClusterParameterGroup");
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "garbage").formParam("BackupRetentionPeriod", "9")
                .formParam("EnableIAMDatabaseAuthentication", "true")
                .formParam("MasterUserPassword", "changed-password2")
                .formParam("VpcSecurityGroupIds.member.1", securityGroup("new"))
                .formParam("DBClusterParameterGroupName", "pg-rejected")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals("1", described("BackupRetentionPeriod"));
        assertEquals("false", described("IAMDatabaseAuthenticationEnabled"));
        assertEquals("false", described("DeletionProtection"));
        assertEquals(List.of(old), describedGroupIds());
        assertEquals(groupBefore, described("DBClusterParameterGroup"));
    }

    @Test
    void createWithEmptySecurityGroupMemberLeavesNoClusterBehind() {
        createAuroraPostgresCluster(ID).formParam("VpcSecurityGroupIds.member.1", "").when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());
    }

    @Test
    void modifyWithEmptySecurityGroupMemberChangesNothing() {
        String old = securityGroup("old");
        createAuroraPostgresCluster(ID).formParam("VpcSecurityGroupIds.member.1", old).when().post("/").then().statusCode(200);
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("VpcSecurityGroupIds.member.1", "").formParam("BackupRetentionPeriod", "9")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals("1", described("BackupRetentionPeriod"));
        assertEquals(List.of(old), describedGroupIds());
    }
}
