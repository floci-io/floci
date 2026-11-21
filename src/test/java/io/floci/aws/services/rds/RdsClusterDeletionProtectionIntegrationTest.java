package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.RdsRequests.createAuroraPostgresCluster;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterDeletionProtectionIntegrationTest {

    private static final String ID = "dp-cluster";
    private static final String RESTORED = "dp-restored";
    private static final String SNAPSHOT = "dp-snapshot";

    @AfterEach
    void cleanUp() {
        for (String id : new String[]{RESTORED, ID}) {
            rds("ModifyDBCluster").formParam("DBClusterIdentifier", id)
                    .formParam("DeletionProtection", "false").when().post("/");
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
        rds("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", SNAPSHOT).when().post("/");
    }

    private static Response createCluster(String deletionProtection) {
        RequestSpecification req = createAuroraPostgresCluster(ID);
        if (deletionProtection != null) {
            req = req.formParam("DeletionProtection", deletionProtection);
        }
        return req.when().post("/");
    }

    private static void describeReports(String expected) {
        describeReports(ID, expected);
    }

    private static void describeReports(String id, String expected) {
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", id)
                .when().post("/").then().statusCode(200)
                .body(containsString("<DeletionProtection>" + expected + "</DeletionProtection>"));
    }

    private static void restoredIsProtected(RequestSpecification restore) {
        restore.formParam("DBClusterIdentifier", RESTORED)
                .formParam("DeletionProtection", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DeletionProtection>true</DeletionProtection>"));
        describeReports(RESTORED, "true");

        rds("DeleteDBCluster").formParam("DBClusterIdentifier", RESTORED)
                .formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));
        describeReports(RESTORED, "true");
    }

    @Test
    void deletionProtectionDefaultsToFalse() {
        createCluster(null).then().statusCode(200)
                .body(containsString("<DeletionProtection>false</DeletionProtection>"));

        describeReports("false");
    }

    @Test
    void deletionProtectionSetOnCreateIsReadBack() {
        createCluster("true").then().statusCode(200)
                .body(containsString("<DeletionProtection>true</DeletionProtection>"));

        describeReports("true");
    }

    @Test
    void modifyChangesDeletionProtection() {
        createCluster(null).then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DeletionProtection>true</DeletionProtection>"));
        describeReports("true");

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "false")
                .when().post("/").then().statusCode(200);
        describeReports("false");
    }

    @Test
    void protectedClusterIsNotDeletedUntilProtectionIsDisabled() {
        createCluster("true").then().statusCode(200);

        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));
        describeReports("true");

        rds("ModifyDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("DeletionProtection", "false")
                .when().post("/").then().statusCode(200);
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void modifyWithoutDeletionProtectionKeepsIt() {
        createCluster("true").then().statusCode(200);

        rds("ModifyDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("ApplyImmediately", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DeletionProtection>true</DeletionProtection>"));
        describeReports("true");
    }

    @Test
    void restoreFromSnapshotTakesDeletionProtection() {
        createCluster(null).then().statusCode(200);
        rds("CreateDBClusterSnapshot").formParam("DBClusterIdentifier", ID)
                .formParam("DBClusterSnapshotIdentifier", SNAPSHOT).when().post("/").then().statusCode(200);

        restoredIsProtected(rds("RestoreDBClusterFromSnapshot")
                .formParam("SnapshotIdentifier", SNAPSHOT)
                .formParam("Engine", "aurora-postgresql"));
    }

    @Test
    void restoreToPointInTimeTakesDeletionProtection() {
        createCluster(null).then().statusCode(200);

        restoredIsProtected(rds("RestoreDBClusterToPointInTime")
                .formParam("SourceDBClusterIdentifier", ID)
                .formParam("UseLatestRestorableTime", "true"));
    }
}
