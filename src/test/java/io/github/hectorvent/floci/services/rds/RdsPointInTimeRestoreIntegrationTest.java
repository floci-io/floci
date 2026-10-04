package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RestoreDBInstanceToPointInTime and RestoreDBClusterToPointInTime against real Postgres
 * containers: a row in the source comes back from the restored target, the describe calls report
 * the restorable window, and each refusal carries the API reference's error.
 */
@QuarkusTest
@Tag("docker")
class RdsPointInTimeRestoreIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void instanceRestoresToPointInTimeWithTheSourcesData() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String source = "pitr-src-" + suffix;
        String latest = "pitr-latest-" + suffix;
        String timed = "pitr-timed-" + suffix;

        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", source)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBName", "appdb")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
        .when().post("/").then().statusCode(200);
        waitForDb(source);
        DbInstance sourceInstance = rdsService.getDbInstance(source);
        psql(sourceInstance.getContainerId(), "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('source-row');");

        XmlPath described = query("DescribeDBInstances").formParam("DBInstanceIdentifier", source)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        String instancePath = "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";
        Instant latestRestorable = Instant.parse(described.getString(instancePath + "LatestRestorableTime"));
        assertTrue(!latestRestorable.isBefore(sourceInstance.getCreatedAt().minusSeconds(1)));
        String dbiResourceId = described.getString(instancePath + "DbiResourceId");

        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", latest)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("DBInstanceClass", "db.t3.small")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "restore")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<RestoreDBInstanceToPointInTimeResult>"))
                .body(containsString("<DBInstanceIdentifier>" + latest + "</DBInstanceIdentifier>"))
                .body(containsString("<Engine>postgres</Engine>"))
                .body(containsString("<DBName>appdb</DBName>"))
                .body(containsString("<MasterUsername>admin</MasterUsername>"))
                .body(containsString("<DBInstanceClass>db.t3.small</DBInstanceClass>"));
        waitForDb(latest);
        assertEquals("source-row", psql(rdsService.getDbInstance(latest).getContainerId(),
                "SELECT v FROM marker;").trim());

        // A time inside the window, named through the source's DbiResourceId. The copied database
        // takes the requested DBName, and protection comes on once the copy is done.
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDbiResourceId", dbiResourceId)
                .formParam("TargetDBInstanceIdentifier", timed)
                .formParam("RestoreTime", Instant.now().minusSeconds(1).toString())
                .formParam("DBName", "otherdb")
                .formParam("DeletionProtection", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceClass>db.t3.micro</DBInstanceClass>"))
                .body(containsString("<DBName>otherdb</DBName>"))
                .body(containsString("<DeletionProtection>true</DeletionProtection>"));
        waitForDb(timed);
        assertEquals("source-row", psql(rdsService.getDbInstance(timed).getContainerId(), "otherdb",
                "SELECT v FROM marker;").trim());
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", "pitr-x-" + suffix)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("DBName", "1bad-name")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        expectInstanceError(source, latest, "UseLatestRestorableTime", "true", "DBInstanceAlreadyExists");
        expectInstanceError(source, "pitr-x-" + suffix, "RestoreTime",
                Instant.now().minus(Duration.ofDays(2)).toString(), "InvalidRestoreFault");
        expectInstanceError(source, "pitr-x-" + suffix, "RestoreTime",
                Instant.now().plus(Duration.ofHours(1)).toString(), "InvalidRestoreFault");
        expectInstanceError("pitr-missing-" + suffix, "pitr-x-" + suffix, "UseLatestRestorableTime", "true",
                "DBInstanceNotFound");
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", "pitr-x-" + suffix)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("RestoreTime", Instant.now().toString())
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", "pitr-x-" + suffix)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceAutomatedBackupsArn",
                        "arn:aws:rds:us-east-1:000000000000:auto-backup:ab-missing")
                .formParam("TargetDBInstanceIdentifier", "pitr-x-" + suffix)
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceAutomatedBackupNotFound</Code>"));

        // With backups off there is no restorable window, and no LatestRestorableTime.
        query("ModifyDBInstance")
                .formParam("DBInstanceIdentifier", source)
                .formParam("BackupRetentionPeriod", "0")
                .formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200);
        waitForDb(source);
        String withoutBackups = query("DescribeDBInstances").formParam("DBInstanceIdentifier", source)
                .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(!withoutBackups.contains("<LatestRestorableTime>"));
        expectInstanceError(source, "pitr-x-" + suffix, "UseLatestRestorableTime", "true",
                "PointInTimeRestoreNotEnabled");

        query("ModifyDBInstance")
                .formParam("DBInstanceIdentifier", timed)
                .formParam("DeletionProtection", "false")
                .formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200);
        for (String id : new String[] {timed, latest, source}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void sourceWithoutDbNameRestoresItsDefaultDatabaseUnderTheRequestedName() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String source = "pitr-nodb-" + suffix;
        String restored = "pitr-named-" + suffix;

        // Without DBName the image names the default database after the master user.
        query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", source)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
        .when().post("/").then().statusCode(200);
        waitForDb(source);
        psql(rdsService.getDbInstance(source).getContainerId(), "admin",
                "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('default-db-row');");

        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", restored)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("DBName", "named")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBName>named</DBName>"));
        waitForDb(restored);
        assertEquals("default-db-row", psql(rdsService.getDbInstance(restored).getContainerId(), "named",
                "SELECT v FROM marker;").trim());

        for (String id : new String[] {restored, source}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void clusterRestoresToPointInTimeWithTheSourcesData() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String source = "pitr-csrc-" + suffix;
        String restored = "pitr-crest-" + suffix;
        String byResourceId = "pitr-cres-" + suffix;

        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", source)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DatabaseName", "appdb")
        .when().post("/").then().statusCode(200);
        waitForCluster(source);
        DbCluster sourceCluster = rdsService.getDbCluster(source);
        psql(sourceCluster.getContainerId(), "CREATE TABLE marker(v text); INSERT INTO marker VALUES ('cluster-row');");

        XmlPath described = query("DescribeDBClusters").formParam("DBClusterIdentifier", source)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        String clusterPath = "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.";
        Instant earliest = Instant.parse(described.getString(clusterPath + "EarliestRestorableTime"));
        Instant latest = Instant.parse(described.getString(clusterPath + "LatestRestorableTime"));
        assertEquals(sourceCluster.getCreatedAt(), earliest);
        assertTrue(!latest.isBefore(earliest.minusSeconds(1)));
        String clusterArn = described.getString(clusterPath + "DBClusterArn");
        String resourceId = described.getString(clusterPath + "DbClusterResourceId");

        // The window's earliest time, with the source named by ARN.
        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", restored)
                .formParam("SourceDBClusterIdentifier", clusterArn)
                .formParam("RestoreToTime", earliest.toString())
                .formParam("RestoreType", "copy-on-write")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<RestoreDBClusterToPointInTimeResult>"))
                .body(containsString("<DBClusterIdentifier>" + restored + "</DBClusterIdentifier>"))
                .body(containsString("<Engine>aurora-postgresql</Engine>"))
                .body(containsString("<DatabaseName>appdb</DatabaseName>"));
        waitForCluster(restored);
        assertEquals("cluster-row", psql(rdsService.getDbCluster(restored).getContainerId(),
                "SELECT v FROM marker;").trim());

        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", byResourceId)
                .formParam("SourceDbClusterResourceId", resourceId)
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(200);
        waitForCluster(byResourceId);

        expectClusterError(source, restored, "UseLatestRestorableTime", "true", "DBClusterAlreadyExistsFault");
        expectClusterError(source, "pitr-cx-" + suffix, "RestoreToTime",
                earliest.minusSeconds(60).toString(), "InvalidRestoreFault");
        expectClusterError("pitr-cmissing-" + suffix, "pitr-cx-" + suffix, "UseLatestRestorableTime", "true",
                "DBClusterNotFoundFault");
        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", "pitr-cx-" + suffix)
                .formParam("SourceDBClusterIdentifier", source)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("RestoreType", "snapshot")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", "pitr-cx-" + suffix)
                .formParam("SourceDBClusterIdentifier", source)
                .formParam("SourceDbClusterResourceId", resourceId)
                .formParam("UseLatestRestorableTime", "true")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));

        for (String id : new String[] {byResourceId, restored, source}) {
            query("DeleteDBCluster").formParam("DBClusterIdentifier", id).formParam("SkipFinalSnapshot", "true")
                    .when().post("/").then().statusCode(200);
        }
    }

    private void expectInstanceError(String source, String target, String timeParam, String timeValue, String code) {
        query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", source)
                .formParam("TargetDBInstanceIdentifier", target)
                .formParam(timeParam, timeValue)
        .when().post("/").then()
                .body(containsString("<Code>" + code + "</Code>"));
    }

    private void expectClusterError(String source, String target, String timeParam, String timeValue, String code) {
        query("RestoreDBClusterToPointInTime")
                .formParam("DBClusterIdentifier", target)
                .formParam("SourceDBClusterIdentifier", source)
                .formParam(timeParam, timeValue)
        .when().post("/").then()
                .body(containsString("<Code>" + code + "</Code>"));
    }

    private void waitForDb(String dbId) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBInstances")
                    .formParam("DBInstanceIdentifier", dbId)
            .when().post("/").then()
                    .extract().xmlPath().getString(
                            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            if ("available".equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB instance " + dbId + " did not become available.");
    }

    private void waitForCluster(String clusterId) throws Exception {
        for (int i = 0; i < 60; i++) {
            String status = query("DescribeDBClusters")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then()
                    .extract().xmlPath().getString(
                            "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.Status");
            if ("available".equals(status)) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("DB cluster " + clusterId + " did not become available.");
    }

    private String psql(String containerId, String sql) throws Exception {
        return psql(containerId, "appdb", sql);
    }

    private String psql(String containerId, String database, String sql) throws Exception {
        String[] command = {"psql", "-U", "admin", "-d", database, "-tAc", sql};
        String execId = dockerClient.execCreateCmd(containerId)
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);
        Closeable callback = dockerClient.execStartCmd(execId).exec(new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                try {
                    if (frame.getStreamType() == StreamType.STDOUT) {
                        output.write(frame.getPayload());
                    } else if (frame.getStreamType() == StreamType.STDERR) {
                        errors.write(frame.getPayload());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to capture psql output", e);
                }
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }
        });
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out running psql");
            long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            assertTrue(exitCode == 0, "psql failed with exit code " + exitCode + ": " + errors.toString(StandardCharsets.UTF_8));
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            callback.close();
        }
    }
}
