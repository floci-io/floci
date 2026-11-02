package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

/**
 * BackupRetentionPeriod round-trip from CreateDBCluster/ModifyDBCluster to DescribeDBClusters.
 * Validates the 1-35 range enforcement on create and modify, and that retained automated
 * backups inherit the cluster's period instead of the fixed constant.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterBackupRetentionPeriodIntegrationTest {

    private static final String ID = "br-cluster";
    private static final String INVALID_ID = "br-invalid";

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", INVALID_ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void backupRetentionPeriodDefaultsToOneOnCreate() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .when().post("/").then().statusCode(200);

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>1</BackupRetentionPeriod>"));
    }

    @Test
    void backupRetentionPeriodEchoedOnCreateWhenProvided() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "7")
                .when().post("/").then().statusCode(200);

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenZero() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", INVALID_ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "0")
                .when().post("/").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenAbove35() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", INVALID_ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "36")
                .when().post("/").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodModifiedAndValidated() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "3")
                .when().post("/").then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "14")
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>14</BackupRetentionPeriod>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>14</BackupRetentionPeriod>"));
    }

    @Test
    void backupRetentionPeriodModifyRejectedWhenInvalid() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "5")
                .when().post("/").then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "40")
                .when().post("/").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenNegative() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", INVALID_ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "-1")
                .when().post("/").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void retainedAutomatedBackupsInheritClusterPeriod() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "7")
                .when().post("/").then().statusCode(200);

        // Delete cluster but retain automated backups
        rds("DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
                .formParam("DeleteAutomatedBackups", "false")
                .when().post("/").then().statusCode(200);

        // Retained backup should have the cluster's period (7), not the default (1)
        rds("DescribeDBClusterAutomatedBackups")
                .formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));
    }

    @Test
    void modifyWithoutBackupRetentionPeriodKeepsExistingValue() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "7")
                .when().post("/").then().statusCode(200);

        // Modify cluster without specifying BackupRetentionPeriod
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("ApplyImmediately", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));
    }
}
