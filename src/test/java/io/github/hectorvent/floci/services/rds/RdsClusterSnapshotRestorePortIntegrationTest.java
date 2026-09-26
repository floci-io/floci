package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

/** RDS creation and restore responses must expose usable proxy ports on the wire. */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterSnapshotRestorePortIntegrationTest {

    private static final String SOURCE = "restore-port-source";
    private static final String SNAPSHOT = "restore-port-snapshot";
    private static final String RESTORED = "restore-port-target";
    private static final String COLLISION = "restore-port-collision";
    private static final String INSTANCE = "restore-port-instance";
    private static final String INSTANCE_SNAPSHOT = "restore-port-instance-snapshot";
    private static final String RESTORED_INSTANCE = "restore-port-instance-target";

    private static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260924/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @AfterEach
    void cleanUp() {
        for (String id : new String[]{RESTORED_INSTANCE, INSTANCE}) {
            rds("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
        rds("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", INSTANCE_SNAPSHOT)
                .when().post("/");
        for (String id : new String[]{COLLISION, RESTORED, SOURCE}) {
            rds("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
        rds("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", SNAPSHOT)
                .when().post("/");
    }

    @Test
    void requestedPortIsUsedInRestoreResponseAndDescribe() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", SOURCE)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .when().post("/").then().statusCode(200);
        rds("CreateDBClusterSnapshot")
                .formParam("DBClusterIdentifier", SOURCE)
                .formParam("DBClusterSnapshotIdentifier", SNAPSHOT)
                .when().post("/").then().statusCode(200);

        rds("RestoreDBClusterFromSnapshot")
                .formParam("DBClusterIdentifier", RESTORED)
                .formParam("SnapshotIdentifier", SNAPSHOT)
                .formParam("Engine", "aurora-postgresql")
                .formParam("Port", "7005")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7005</Port>"));

        rds("DescribeDBClusters")
                .formParam("DBClusterIdentifier", RESTORED)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7005</Port>"));

        rds("RestoreDBClusterFromSnapshot")
                .formParam("DBClusterIdentifier", COLLISION)
                .formParam("SnapshotIdentifier", SNAPSHOT)
                .formParam("Engine", "aurora-postgresql")
                .formParam("Port", "7005")
                .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
    }

    @Test
    void portOutsidePublishedRangeFallsBackOnCreateAndRestore() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", SOURCE)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("Port", "5432")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7001</Port>"));
        rds("CreateDBClusterSnapshot")
                .formParam("DBClusterIdentifier", SOURCE)
                .formParam("DBClusterSnapshotIdentifier", SNAPSHOT)
                .when().post("/").then().statusCode(200);

        rds("RestoreDBClusterFromSnapshot")
                .formParam("DBClusterIdentifier", RESTORED)
                .formParam("SnapshotIdentifier", SNAPSHOT)
                .formParam("Engine", "aurora-postgresql")
                .formParam("Port", "5432")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7002</Port>"));
    }

    @Test
    void encryptedInstanceRestorePreservesEncryptionAndRequestedPort() {
        rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("StorageEncrypted", "true")
                .formParam("Port", "7005")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7005</Port>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
        rds("CreateDBSnapshot")
                .formParam("DBInstanceIdentifier", INSTANCE)
                .formParam("DBSnapshotIdentifier", INSTANCE_SNAPSHOT)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Encrypted>true</Encrypted>"));
        rds("RestoreDBInstanceFromDBSnapshot")
                .formParam("DBInstanceIdentifier", RESTORED_INSTANCE)
                .formParam("DBSnapshotIdentifier", INSTANCE_SNAPSHOT)
                .formParam("Port", "7006")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7006</Port>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("DescribeDBInstances")
                .formParam("DBInstanceIdentifier", RESTORED_INSTANCE)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>7006</Port>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
    }
}
