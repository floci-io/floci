package io.floci.aws.services.rds;

import io.floci.aws.core.common.BackupWindows;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VpcSecurityGroupIds, PreferredBackupWindow, PreferredMaintenanceWindow and DeletionProtection given
 * to CreateDBCluster and ModifyDBCluster are stored on the cluster and read back through
 * DescribeDBClusters, instead of a fixed sg-00000000 membership, fixed windows and no protection flag.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterVpcSecurityGroupsIntegrationTest {

    private static final String ID = "vpc-sg-cluster";

    private static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

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

    private static RequestSpecification create() {
        return rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password");
    }

    @AfterEach
    void cleanUp() {
        // a protected cluster would survive the delete below and leak into the next test
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "false").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void createStoresRequestedGroupsAndModifyReplacesThem() {
        String created = create()
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-abcdef01")
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(List.of("sg-abcdef01"), groupIds(created));
        assertEquals(List.of("sg-abcdef01"), describedGroupIds());

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-11111111")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.2", "sg-22222222")
                .when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-11111111", "sg-22222222"), describedGroupIds());

        // A modify that does not name VpcSecurityGroupIds leaves them alone.
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("EnableIAMDatabaseAuthentication", "true")
                .when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-11111111", "sg-22222222"), describedGroupIds());
    }

    @Test
    void omittedGroupsFallBackToTheInstanceDefault() {
        create().when().post("/").then().statusCode(200);
        assertEquals(List.of("sg-00000000"), describedGroupIds());
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
    void windowsAndDeletionProtectionAreStoredAndDefaulted() {
        create().when().post("/").then().statusCode(200);
        assertEquals("mon:00:00-mon:03:00", described("PreferredMaintenanceWindow"));
        assertEquals("04:00-06:00", described("PreferredBackupWindow"));
        assertEquals("false", described("DeletionProtection"));
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        String created = create()
                .formParam("PreferredBackupWindow", "05:00-06:00")
                .formParam("PreferredMaintenanceWindow", "tue:07:00-tue:08:00")
                .formParam("DeletionProtection", "true")
                .when().post("/").then().statusCode(200).extract().asString();
        assertEquals(true, created.contains("<PreferredBackupWindow>05:00-06:00</PreferredBackupWindow>"));
        assertEquals("05:00-06:00", described("PreferredBackupWindow"));
        assertEquals("tue:07:00-tue:08:00", described("PreferredMaintenanceWindow"));
        assertEquals("true", described("DeletionProtection"));

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "03:00-04:00")
                .formParam("DeletionProtection", "false")
                .when().post("/").then().statusCode(200);
        assertEquals("03:00-04:00", described("PreferredBackupWindow"));
        assertEquals("tue:07:00-tue:08:00", described("PreferredMaintenanceWindow"));
        assertEquals("false", described("DeletionProtection"));
    }

    private static int clusterCount() {
        return rds("DescribeDBClusters").when().post("/").then().statusCode(200).extract().asString()
                .split("<DBClusterIdentifier>" + ID + "</DBClusterIdentifier>", -1).length - 1;
    }

    @Test
    void protectedClusterIsNotDeletedUntilProtectionIsLifted() {
        create().formParam("DeletionProtection", "true").when().post("/").then().statusCode(200);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"))
                .body(containsString(
                        "Cannot delete protected Cluster, please disable deletion protection and try again."));
        assertEquals(1, clusterCount());

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "false").when().post("/").then().statusCode(200);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID).formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
        assertEquals(0, clusterCount());
    }

    @Test
    void invalidWindowsAreRefusedAndLeaveNoClusterBehind() {
        create().formParam("PreferredBackupWindow", "garbage").when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());
        create().formParam("PreferredBackupWindow", "05:00-06:00")
                .formParam("PreferredMaintenanceWindow", "mon:05:30-mon:06:30")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        assertEquals(0, clusterCount());

        create().formParam("PreferredBackupWindow", "05:00-06:00").when().post("/").then().statusCode(200);
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
        create().formParam("PreferredBackupWindow", "01:00-02:00").when().post("/").then().statusCode(200);
        assertEquals("01:00-02:00", described("PreferredBackupWindow"));
        String maint = described("PreferredMaintenanceWindow");
        assertEquals(false, BackupWindows.overlap("01:00-02:00", maint), maint);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        // default backup 04:00-06:00 overlaps mon:04:30-mon:05:30
        create().formParam("PreferredMaintenanceWindow", "mon:04:30-mon:05:30")
                .when().post("/").then().statusCode(200);
        String backup = described("PreferredBackupWindow");
        assertEquals(false, BackupWindows.overlap(backup, "mon:04:30-mon:05:30"), backup);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");

        // never-set windows count as the defaults on modify
        create().when().post("/").then().statusCode(200);
        assertOverlapRefused(rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredBackupWindow", "01:00-02:00").when().post("/").then());
        assertOverlapRefused(rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("PreferredMaintenanceWindow", "mon:04:30-mon:05:30").when().post("/").then());
        assertEquals("04:00-06:00", described("PreferredBackupWindow"));
    }

    @Test
    void modifyWithABadWindowChangesNothing() {
        create().when().post("/").then().statusCode(200);
        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "9").formParam("PreferredBackupWindow", "garbage")
                .when().post("/").then().statusCode(400);
        assertEquals("1", described("BackupRetentionPeriod"));
    }
}
