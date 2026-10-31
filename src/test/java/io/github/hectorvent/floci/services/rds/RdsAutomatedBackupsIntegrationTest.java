package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instance and cluster automated backups over the Query protocol: the active backup a live instance
 * has, the retained one a delete with {@code DeleteAutomatedBackups=false} leaves and a point in time
 * restore can use, and replication to another Region. The requests sign for Regions no other test
 * uses, so the Region-wide listings hold only what these tests create.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsAutomatedBackupsIntegrationTest {

    private static final String SOURCE_REGION = "ap-southeast-2";
    private static final String DESTINATION_REGION = "ap-northeast-3";

    private static RequestSpecification query(String action) {
        return query(action, SOURCE_REGION);
    }

    private static RequestSpecification query(String action, String region) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261028/" + region
                        + "/rds/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static void createInstance(String id) {
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20")
                .formParam("BackupRetentionPeriod", "3")
        .when().post("/").then().statusCode(200);
    }

    private static String dbiResourceId(String id) {
        return query("DescribeDBInstances").formParam("DBInstanceIdentifier", id)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("**.find { it.name() == 'DbiResourceId' }");
    }

    private static XmlPath backupsOf(String dbiResourceId, String region) {
        return query("DescribeDBInstanceAutomatedBackups", region).formParam("DbiResourceId", dbiResourceId)
                .when().post("/").then().statusCode(200).extract().xmlPath();
    }

    /** Instances hold ports the other tests in this Quarkus app expect to find free. */
    @AfterEach
    void cleanUp() {
        for (String id : new String[] {"ab-live", "ab-kept", "ab-gone", "ab-restored", "ab-by-arn", "ab-src"}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
        for (String id : new String[] {"abc-kept", "abc-restored"}) {
            query("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @Test
    void aLiveInstanceHasAnActiveBackupThatCannotBeDeleted() {
        createInstance("ab-live");
        String dbi = dbiResourceId("ab-live");

        XmlPath backup = backupsOf(dbi, SOURCE_REGION);
        String prefix = "DescribeDBInstanceAutomatedBackupsResponse.DescribeDBInstanceAutomatedBackupsResult"
                + ".DBInstanceAutomatedBackups.DBInstanceAutomatedBackup.";
        assertEquals("active", backup.getString(prefix + "Status"));
        assertEquals("ab-live", backup.getString(prefix + "DBInstanceIdentifier"));
        assertEquals(SOURCE_REGION, backup.getString(prefix + "Region"));
        assertEquals("3", backup.getString(prefix + "BackupRetentionPeriod"));
        assertEquals("arn:aws:rds:" + SOURCE_REGION + ":000000000000:auto-backup:ab-" + dbi.substring(3),
                backup.getString(prefix + "DBInstanceAutomatedBackupsArn"));
        Instant earliest = Instant.parse(backup.getString(prefix + "RestoreWindow.EarliestTime"));
        Instant latest = Instant.parse(backup.getString(prefix + "RestoreWindow.LatestTime"));
        assertTrue(!earliest.isAfter(latest), "the window must open before it closes");

        query("DeleteDBInstanceAutomatedBackup").formParam("DbiResourceId", dbi)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceAutomatedBackupState</Code>"));
        query("DescribeDBInstanceAutomatedBackups").formParam("DbiResourceId", "db-NOSUCHRESOURCE")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceAutomatedBackupNotFound</Code>"));
    }

    @Test
    void aDeleteThatKeepsBackupsLeavesARetainedBackupToRestoreFrom() {
        createInstance("ab-kept");
        String dbi = dbiResourceId("ab-kept");
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", "ab-kept")
                .formParam("SkipFinalSnapshot", "true").formParam("DeleteAutomatedBackups", "false")
        .when().post("/").then().statusCode(200);

        query("DescribeDBInstanceAutomatedBackups")
                .formParam("Filters.Filter.1.Name", "status")
                .formParam("Filters.Filter.1.Values.Value.1", "retained")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DbiResourceId>" + dbi + "</DbiResourceId>"))
                .body(containsString("<Status>retained</Status>"))
                .body(not(containsString("<Status>active</Status>")));
        String arn = backupsOf(dbi, SOURCE_REGION).getString("**.find { it.name() == 'DBInstanceAutomatedBackupsArn' }");

        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDbiResourceId", dbi)
                .formParam("TargetDBInstanceIdentifier", "ab-restored")
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceIdentifier>ab-restored</DBInstanceIdentifier>"));
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceAutomatedBackupsArn", arn)
                .formParam("TargetDBInstanceIdentifier", "ab-by-arn")
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(200);
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDbiResourceId", dbi)
                .formParam("TargetDBInstanceIdentifier", "ab-too-late")
                .formParam("RestoreTime", Instant.now().plusSeconds(3600).toString())
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidRestoreFault</Code>"));

        query("DeleteDBInstanceAutomatedBackup").formParam("DBInstanceAutomatedBackupsArn", arn)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>deleting</Status>"))
                .body(containsString("<DbiResourceId>" + dbi + "</DbiResourceId>"));
        query("DescribeDBInstanceAutomatedBackups").formParam("DbiResourceId", dbi)
        .when().post("/").then().statusCode(404);
    }

    @Test
    void aPlainDeleteRemovesTheBackups() {
        createInstance("ab-gone");
        String dbi = dbiResourceId("ab-gone");
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", "ab-gone")
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        query("DescribeDBInstanceAutomatedBackups").formParam("DbiResourceId", dbi)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceAutomatedBackupNotFound</Code>"));
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDbiResourceId", dbi)
                .formParam("TargetDBInstanceIdentifier", "ab-restored")
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(404);
    }

    @Test
    void replicationListsAReplicaInTheDestinationUntilItStops() {
        createInstance("ab-src");
        String dbi = dbiResourceId("ab-src");
        String sourceArn = "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src";
        String replicaArn = "arn:aws:rds:" + DESTINATION_REGION + ":000000000000:auto-backup:ab-" + dbi.substring(3);

        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
                .formParam("BackupRetentionPeriod", "10")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>replicating</Status>"))
                .body(containsString("<DBInstanceAutomatedBackupsArn>" + replicaArn + "</DBInstanceAutomatedBackupsArn>"))
                .body(containsString("<BackupRetentionPeriod>10</BackupRetentionPeriod>"))
                .body(containsString("<Region>" + SOURCE_REGION + "</Region>"));
        query("DescribeDBInstances").formParam("DBInstanceIdentifier", "ab-src")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceAutomatedBackupsReplications><DBInstanceAutomatedBackupsReplication>"
                        + "<DBInstanceAutomatedBackupsArn>" + replicaArn + "</DBInstanceAutomatedBackupsArn>"));
        query("DescribeDBInstanceAutomatedBackups", DESTINATION_REGION)
                .formParam("DBInstanceAutomatedBackupsArn", replicaArn)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>replicating</Status>"));
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceAutomatedBackupState</Code>"));
        query("DeleteDBInstanceAutomatedBackup", DESTINATION_REGION).formParam("DBInstanceAutomatedBackupsArn", replicaArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceAutomatedBackupState</Code>"));

        query("StopDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>retained</Status>"));
        query("DescribeDBInstances").formParam("DBInstanceIdentifier", "ab-src")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceAutomatedBackupsReplications></DBInstanceAutomatedBackupsReplications>"));
        query("StopDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
        .when().post("/").then().statusCode(400)
                .body(containsString("not replicating to the current region"));

        // A stopped replica does not stand in the way of starting again.
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>replicating</Status>"));
        query("StopDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", sourceArn)
        .when().post("/").then().statusCode(200);
        query("DeleteDBInstanceAutomatedBackup", DESTINATION_REGION).formParam("DBInstanceAutomatedBackupsArn", replicaArn)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void aRestoreFromAReplicaIsCheckedAgainstTheReplicasWindow() {
        createInstance("ab-src");
        String dbi = dbiResourceId("ab-src");
        Instant sourceEarliest = Instant.parse(backupsOf(dbi, SOURCE_REGION)
                .getString("**.find { it.name() == 'EarliestTime' }"));
        String replicaArn = query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("**.find { it.name() == 'DBInstanceAutomatedBackupsArn' }");
        Instant replicaStart = Instant.parse(backupsOf(dbi, DESTINATION_REGION)
                .getString("**.find { it.name() == 'EarliestTime' }"));
        Instant beforeReplica = replicaStart.minusNanos(1);
        assertTrue(beforeReplica.isAfter(sourceEarliest),
                "the source must have been restorable before the replica started");

        // Inside the source's window, but before the replica's.
        query("RestoreDBInstanceToPointInTime", DESTINATION_REGION)
                .formParam("SourceDBInstanceAutomatedBackupsArn", replicaArn)
                .formParam("TargetDBInstanceIdentifier", "ab-restored")
                .formParam("RestoreTime", beforeReplica.toString())
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidRestoreFault</Code>"));
        query("RestoreDBInstanceToPointInTime", DESTINATION_REGION)
                .formParam("SourceDBInstanceAutomatedBackupsArn", replicaArn)
                .formParam("TargetDBInstanceIdentifier", "ab-restored")
                .formParam("RestoreTime", replicaStart.toString())
        .when().post("/").then().statusCode(200);

        query("DeleteDBInstance", DESTINATION_REGION).formParam("DBInstanceIdentifier", "ab-restored")
                .formParam("SkipFinalSnapshot", "true").when().post("/").then().statusCode(200);
        query("StopDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src")
        .when().post("/").then().statusCode(200);
        query("DeleteDBInstanceAutomatedBackup", DESTINATION_REGION).formParam("DBInstanceAutomatedBackupsArn", replicaArn)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void replicationNeedsALiveSourceInAnotherRegion() {
        createInstance("ab-src");
        query("StartDBInstanceAutomatedBackupsReplication", SOURCE_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-nosuch")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));
        // The same identifier in another account's ARN is not this account's instance.
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":111111111111:db:ab-src")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:docdb:" + SOURCE_REGION + ":000000000000:db:ab-src")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));
        query("StopDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBInstanceState</Code>"));
    }

    @Test
    void aDeletedSourceKeepsItsReplicaAsRetained() {
        createInstance("ab-src");
        String dbi = dbiResourceId("ab-src");
        query("StartDBInstanceAutomatedBackupsReplication", DESTINATION_REGION)
                .formParam("SourceDBInstanceArn", "arn:aws:rds:" + SOURCE_REGION + ":000000000000:db:ab-src")
        .when().post("/").then().statusCode(200);
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", "ab-src")
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        assertEquals("retained", backupsOf(dbi, DESTINATION_REGION)
                .getString("**.find { it.name() == 'Status' }"));
        query("DeleteDBInstanceAutomatedBackup", DESTINATION_REGION).formParam("DbiResourceId", dbi)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void aClusterDeleteThatKeepsBackupsLeavesARetainedBackupToRestoreFrom() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", "abc-kept")
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
        .when().post("/").then().statusCode(200);
        String resourceId = query("DescribeDBClusters").formParam("DBClusterIdentifier", "abc-kept")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("**.find { it.name() == 'DbClusterResourceId' }");
        query("DescribeDBClusterAutomatedBackups").formParam("DbClusterResourceId", resourceId)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterAutomatedBackupNotFoundFault</Code>"));
        query("DeleteDBClusterAutomatedBackup").formParam("DbClusterResourceId", resourceId)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidDBClusterAutomatedBackupStateFault</Code>"));

        query("DeleteDBCluster").formParam("DBClusterIdentifier", "abc-kept")
                .formParam("SkipFinalSnapshot", "true").formParam("DeleteAutomatedBackups", "false")
        .when().post("/").then().statusCode(200);
        query("DescribeDBClusterAutomatedBackups").formParam("DBClusterIdentifier", "abc-kept")
        .when().post("/").then().statusCode(200)
                .body("DescribeDBClusterAutomatedBackupsResponse.DescribeDBClusterAutomatedBackupsResult"
                        + ".DBClusterAutomatedBackups.DBClusterAutomatedBackup.Status", equalTo("retained"))
                .body(containsString("<DbClusterResourceId>" + resourceId + "</DbClusterResourceId>"))
                .body(containsString("<DBClusterAutomatedBackupsArn>arn:aws:rds:" + SOURCE_REGION
                        + ":000000000000:cluster-auto-backup:" + resourceId + "</DBClusterAutomatedBackupsArn>"));

        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", "abc-restored")
                .formParam("SourceDbClusterResourceId", resourceId)
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterIdentifier>abc-restored</DBClusterIdentifier>"));

        query("DeleteDBClusterAutomatedBackup").formParam("DbClusterResourceId", resourceId)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Status>deleting</Status>"));
        query("DeleteDBClusterAutomatedBackup").formParam("DbClusterResourceId", resourceId)
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterAutomatedBackupNotFoundFault</Code>"));
    }
}
