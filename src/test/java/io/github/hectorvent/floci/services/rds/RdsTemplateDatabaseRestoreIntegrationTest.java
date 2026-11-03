package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static io.github.hectorvent.floci.testing.RdsRequests.deleteInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A snapshot restores on an image that creates a template database at startup, such as PostGIS's
 * {@code template_postgis}, which the snapshot's dump creates again.
 */
@QuarkusTest
@Tag("docker")
@TestProfile(RdsTemplateDatabaseRestoreIntegrationTest.PostgisImageProfile.class)
class RdsTemplateDatabaseRestoreIntegrationTest {

    public static class PostgisImageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.default-postgres-image", "postgis/postgis:17-3.5-alpine");
        }
    }

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @Test
    void aSnapshotOfAnImageWithATemplateDatabaseRestores() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String dbId = "postgis-src-" + suffix;
        String snapshotId = "postgis-snap-" + suffix;
        String restoreId = "postgis-restore-" + suffix;

        try {
            rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret123")
                .formParam("AllocatedStorage", "5")
                .formParam("DBInstanceClass", "db.t3.micro")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("available", waitForDb(dbId));

            rds("CreateDBSnapshot")
                .formParam("DBInstanceIdentifier", dbId)
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(snapshotId));

            rds("RestoreDBInstanceFromDBSnapshot")
                .formParam("DBInstanceIdentifier", restoreId)
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(restoreId));
            assertEquals("available", waitForDb(restoreId));
        } finally {
            deleteInstance(restoreId);
            deleteInstance(dbId);
            rds("DeleteDBSnapshot")
                .formParam("DBSnapshotIdentifier", snapshotId)
            .when()
                .post("/");
        }
    }

    private String waitForDb(String dbId) throws Exception {
        String status = null;
        for (int i = 0; i < 120; i++) {
            status = rds("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", dbId)
            .when()
                .post("/")
            .then()
                .extract().xmlPath().getString(
                    "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.DBInstanceStatus");
            if ("available".equals(status)) {
                return status;
            }
            Thread.sleep(1000);
        }
        return status;
    }
}
