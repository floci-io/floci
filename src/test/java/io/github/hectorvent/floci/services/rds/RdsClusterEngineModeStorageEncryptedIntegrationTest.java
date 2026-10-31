package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

/**
 * EngineMode and StorageEncrypted round-trip from CreateDBCluster to DescribeDBClusters for an
 * Aurora-shaped DBCluster, so a caller no longer sees drift on these two fields (issue #2559).
 * The "Aurora DB clusters only" restriction on EngineMode is scoped to the CreateDBCluster
 * request parameter, not the DBCluster response member, so a Multi-AZ, non-Aurora cluster still
 * reports EngineMode (defaulting to provisioned) in DescribeDBClusters, same as StorageEncrypted.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterEngineModeStorageEncryptedIntegrationTest {

    private static final String ID = "engine-mode-cluster";
    private static final String NON_AURORA_ID = "engine-mode-non-aurora-cluster";

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", NON_AURORA_ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void engineModeAndStorageEncryptedRoundTripThroughDescribe() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("EngineMode", "serverless")
                .formParam("StorageEncrypted", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>serverless</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>serverless</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
    }

    @Test
    void engineModeDefaultsToProvisionedAndStorageEncryptedDefaultsToFalseWhenOmitted() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>false</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>false</StorageEncrypted>"));
    }

    @Test
    void engineModeStillEmittedForNonAuroraMultiAzCluster() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", NON_AURORA_ID)
                .formParam("Engine", "mysql")
                .formParam("EngineVersion", "8.0.36")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("MultiAZ", "true")
                .formParam("StorageEncrypted", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", NON_AURORA_ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
    }

    @Test
    void backupRetentionPeriodAndCreateTagsPersistAndSurviveModify() {
        String id = "backup-retention-tags-cluster";
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "7")
                .formParam("Tags.Tag.1.Key", "Environment")
                .formParam("Tags.Tag.1.Value", "test")
                .formParam("Tags.Tag.2.Key", "Owner")
                .formParam("Tags.Tag.2.Value", "team-a")
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"))
                .body(containsString("<TagList>"))
                .body(containsString("<Key>Environment</Key>"))
                .body(containsString("<Value>test</Value>"))
                .body(containsString("<Key>Owner</Key>"))
                .body(containsString("<Value>team-a</Value>"));

        // Verify via ListTagsForResource
        rds("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:" + id)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>Environment</Key>"))
                .body(containsString("<Value>test</Value>"));

        // Modify without BackupRetentionPeriod - it should keep the original value
        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("MasterUserPassword", "newsecret99password")
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"));

        // Verify tags still present after modify
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", id)
                .when().post("/").then().statusCode(200)
                .body(containsString("<BackupRetentionPeriod>7</BackupRetentionPeriod>"))
                .body(containsString("<Key>Environment</Key>"))
                .body(containsString("<Value>team-a</Value>"));

        rds("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void createDbClusterRejectsNegativeBackupRetentionPeriod() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", "bad-retention")
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("BackupRetentionPeriod", "-1")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void modifyDbClusterRejectsNegativeBackupRetentionPeriod() {
        String id = "bad-modify-retention";
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .when().post("/").then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("BackupRetentionPeriod", "-5")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        rds("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }
}
