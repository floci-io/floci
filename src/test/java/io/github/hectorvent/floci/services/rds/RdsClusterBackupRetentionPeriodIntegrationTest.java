package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
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

    private static Response createCluster(String id, String period) {
        RequestSpecification req = rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password");
        if (period != null) {
            req = req.formParam("BackupRetentionPeriod", period);
        }
        return req.when().post("/");
    }

    @Test
    void backupRetentionPeriodDefaultsToOneOnCreate() {
        createCluster(ID, null).then().statusCode(200);

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>1</BackupRetentionPeriod>"));
    }

    @Test
    void backupRetentionPeriodEchoedOnCreateWhenProvided() {
        createCluster(ID, "7").then().statusCode(200);

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenZero() {
        createCluster(INVALID_ID, "0").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenAbove35() {
        createCluster(INVALID_ID, "36").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodModifiedAndValidated() {
        createCluster(ID, "3").then().statusCode(200);

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
        createCluster(ID, "5").then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("BackupRetentionPeriod", "40")
                .when().post("/").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void backupRetentionPeriodRejectedWhenNegative() {
        createCluster(INVALID_ID, "-1").then().statusCode(400)
                .body(containsString("BackupRetentionPeriod must be between 1 and 35"));
    }

    @Test
    void retainedAutomatedBackupsInheritClusterPeriod() {
        createCluster(ID, "7").then().statusCode(200);

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
        createCluster(ID, "7").then().statusCode(200);

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
