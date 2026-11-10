package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsStorageTypeIntegrationTest {

    private static RequestSpecification query(String action) {
        return query(action, "us-east-1", "000000000000");
    }

    private static RequestSpecification query(String action, String region, String account) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20261020/"
                + region + "/rds/aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31");
    }

    private static RequestSpecification create(String id) {
        return create(id, "postgres");
    }

    private static RequestSpecification create(String id, String engine) {
        return query("CreateDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("Engine", engine).formParam("DBInstanceClass", "db.t3.micro")
                .formParam("MasterUsername", "admin").formParam("MasterUserPassword", "password123")
                .formParam("AllocatedStorage", "20");
    }

    private static String type(Response response, String action) {
        return response.xmlPath().getString(action + "Response." + action + "Result.DBInstance.StorageType");
    }

    private static void delete(String id) {
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                .formParam("SkipFinalSnapshot", "true").post("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"io1", "io2"})
    void provisionedTypesRoundTripOnCreateAndModifyWithoutIops(String storageType) {
        String createdId = "storage-provisioned-new-" + storageType;
        String changedId = "storage-provisioned-change-" + storageType;
        try {
            create(changedId).formParam("StorageType", "gp2").post("/").then().statusCode(200);
            Response created = create(createdId).formParam("StorageType", storageType).post("/");
            Response changed = query("ModifyDBInstance").formParam("DBInstanceIdentifier", changedId)
                    .formParam("StorageType", storageType).post("/");
            assertAll(() -> assertEquals(200, created.statusCode(), created.asString()),
                    () -> assertEquals(storageType, type(created, "CreateDBInstance")),
                    () -> assertEquals(200, changed.statusCode(), changed.asString()),
                    () -> assertEquals(storageType, type(changed, "ModifyDBInstance")));
            Response omitted = query("ModifyDBInstance").formParam("DBInstanceIdentifier", changedId)
                    .formParam("BackupRetentionPeriod", "3").post("/");
            assertEquals(storageType, type(omitted, "ModifyDBInstance"));
            for (String id : List.of(createdId, changedId)) {
                Response read = query("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/");
                String path = "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";
                assertEquals(200, read.statusCode());
                assertEquals(storageType, read.xmlPath().getString(path + "StorageType"));
                assertTrue(read.xmlPath().getList(path + "Iops").isEmpty());
            }
        } finally {
            delete(createdId);
            delete(changedId);
        }
    }

    @Test
    void createRetainsTheRequestedTypeInItsResponseAndDescribe() {
        String id = "storage-type-create";
        try {
            Response created = create(id).formParam("StorageType", "gp3").post("/");
            Response described = query("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/");
            assertAll(() -> assertEquals(200, created.statusCode()),
                    () -> assertEquals("gp3", type(created, "CreateDBInstance")),
                    () -> assertEquals("gp3", described.xmlPath().getString(
                            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.StorageType")));
        } finally {
            delete(id);
        }
    }

    @Test
    void modifyChangesTheTypeAndOmissionKeepsIt() {
        String id = "storage-type-modify";
        try {
            create(id).formParam("StorageType", "gp2").post("/").then().statusCode(200);
            Response changed = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("StorageType", "gp3").formParam("BackupRetentionPeriod", "5").post("/");
            Response omitted = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("BackupRetentionPeriod", "6").post("/");
            assertAll(() -> assertEquals(200, changed.statusCode()),
                    () -> assertEquals("gp3", type(changed, "ModifyDBInstance")),
                    () -> assertEquals("gp3", type(omitted, "ModifyDBInstance")));
        } finally {
            delete(id);
        }
    }

    @Test
    void anUnsupportedTypeCannotPublishOrPartiallyModifyAnInstance() {
        String absent = "storage-type-refused";
        String existing = "storage-type-atomic";
        try {
            Response refusedCreate = create(absent).formParam("StorageType", "not-a-storage-type").post("/");
            create(existing).formParam("StorageType", "gp2").formParam("BackupRetentionPeriod", "2")
                    .post("/").then().statusCode(200);
            Response refusedModify = query("ModifyDBInstance").formParam("DBInstanceIdentifier", existing)
                    .formParam("StorageType", "not-a-storage-type").formParam("BackupRetentionPeriod", "7").post("/");
            Response read = query("DescribeDBInstances").formParam("DBInstanceIdentifier", existing).post("/");
            Response absentRead = query("DescribeDBInstances").formParam("DBInstanceIdentifier", absent).post("/");
            assertAll(() -> assertEquals(400, refusedCreate.statusCode()),
                    () -> assertEquals(400, refusedModify.statusCode()),
                    () -> assertEquals(404, absentRead.statusCode()),
                    () -> assertEquals("2", read.xmlPath().getString(
                            "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.BackupRetentionPeriod")));
            create(absent).formParam("StorageType", "gp3").post("/").then().statusCode(200);
            query("ModifyDBInstance").formParam("DBInstanceIdentifier", existing).formParam("StorageType", "gp3")
                    .post("/").then().statusCode(200);
        } finally {
            delete(absent);
            delete(existing);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"gp3", "io1", "io2"})
    void snapshotsCopiesAndDerivedInstancesKeepTheirStorageMetadata(String storageType) {
        String source = "storage-type-source";
        String snapshot = "storage-type-snapshot";
        String copied = "storage-type-snapshot-copy";
        String restored = "storage-type-restored";
        String replica = "storage-type-replica";
        String pitr = "storage-type-pitr";
        try {
            create(source).formParam("StorageType", storageType).formParam("BackupRetentionPeriod", "2")
                    .post("/").then().statusCode(200);
            Response taken = query("CreateDBSnapshot").formParam("DBSnapshotIdentifier", snapshot)
                    .formParam("DBInstanceIdentifier", source).post("/");
            assertEquals(storageType, taken.xmlPath().getString(
                    "CreateDBSnapshotResponse.CreateDBSnapshotResult.DBSnapshot.StorageType"));
            Response copy = query("CopyDBSnapshot").formParam("SourceDBSnapshotIdentifier", snapshot)
                    .formParam("TargetDBSnapshotIdentifier", copied).post("/");
            assertEquals(storageType, copy.xmlPath().getString(
                    "CopyDBSnapshotResponse.CopyDBSnapshotResult.DBSnapshot.StorageType"));
            Response restore = query("RestoreDBInstanceFromDBSnapshot").formParam("DBSnapshotIdentifier", copied)
                    .formParam("DBInstanceIdentifier", restored).post("/");
            assertEquals(200, restore.statusCode());
            assertEquals(storageType, type(restore, "RestoreDBInstanceFromDBSnapshot"));
            Response readReplica = query("CreateDBInstanceReadReplica").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("DBInstanceIdentifier", replica).post("/");
            assertEquals(200, readReplica.statusCode());
            assertEquals(storageType, type(readReplica, "CreateDBInstanceReadReplica"));
            Response point = query("RestoreDBInstanceToPointInTime").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("TargetDBInstanceIdentifier", pitr).formParam("UseLatestRestorableTime", "true").post("/");
            assertEquals(200, point.statusCode());
            assertEquals(storageType, type(point, "RestoreDBInstanceToPointInTime"));
            Response options = query("DescribeValidDBInstanceModifications")
                    .formParam("DBInstanceIdentifier", source).post("/");
            assertEquals(List.of("gp2", "gp3", "io1", "io2"), options.xmlPath().getList(
                    "DescribeValidDBInstanceModificationsResponse.DescribeValidDBInstanceModificationsResult."
                    + "ValidDBInstanceModificationsMessage.Storage.ValidStorageOptions.StorageType"));
        } finally {
            delete(pitr);
            delete(replica);
            delete(restored);
            query("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", copied).post("/");
            query("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", snapshot).post("/");
            delete(source);
        }
    }

    @Test
    void omittedCreateUsesTheExistingFallbackAndInvalidTypesLeaveTheLegacyValueAlone() {
        String id = "storage-type-default";
        try {
            Response created = create(id).post("/");
            assertEquals("gp2", type(created, "CreateDBInstance"));
            for (String invalid : List.of("", "GP3", "standard")) {
                Response refused = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                        .formParam("StorageType", invalid).formParam("BackupRetentionPeriod", "9").post("/");
                assertEquals(400, refused.statusCode(), invalid);
                assertEquals("StorageTypeNotSupported", refused.xmlPath().getString("ErrorResponse.Error.Code"));
            }
            Response modified = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("StorageType", "gp3").post("/");
            assertEquals("gp3", type(modified, "ModifyDBInstance"));
        } finally {
            delete(id);
        }
    }

    @Test
    void aStorageRefusalNamesTheStoredTypeAndKeepsTheEntireRequestUnapplied() {
        String id = "storage-type-shrink";
        try {
            create(id).formParam("StorageType", "gp3").formParam("BackupRetentionPeriod", "2")
                    .post("/").then().statusCode(200);
            Response rejected = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("AllocatedStorage", "19").formParam("StorageType", "gp2")
                    .formParam("BackupRetentionPeriod", "9").post("/");
            Response described = query("DescribeDBInstances").formParam("DBInstanceIdentifier", id).post("/");
            String path = "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.";
            assertAll(() -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("Invalid storage size for engine name postgres and storage type gp3: 19",
                            rejected.xmlPath().getString("ErrorResponse.Error.Message")),
                    () -> assertEquals("gp3", described.xmlPath().getString(path + "StorageType")),
                    () -> assertEquals("20", described.xmlPath().getString(path + "AllocatedStorage")),
                    () -> assertEquals("2", described.xmlPath().getString(path + "BackupRetentionPeriod")));
        } finally {
            delete(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gp2", "io1", "io2"})
    void explicitTypesOverrideDerivedInstanceDefaults(String storageType) {
        String source = "storage-override-source";
        String snapshot = "storage-override-snapshot";
        String restore = "storage-override-restored";
        String replica = "storage-override-replica";
        String pitr = "storage-override-pitr";
        try {
            create(source).formParam("StorageType", "gp3").formParam("BackupRetentionPeriod", "2")
                    .post("/").then().statusCode(200);
            query("CreateDBSnapshot").formParam("DBSnapshotIdentifier", snapshot)
                    .formParam("DBInstanceIdentifier", source).post("/").then().statusCode(200);
            Response restored = query("RestoreDBInstanceFromDBSnapshot").formParam("DBSnapshotIdentifier", snapshot)
                    .formParam("DBInstanceIdentifier", restore).formParam("StorageType", storageType).post("/");
            Response replicated = query("CreateDBInstanceReadReplica").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("DBInstanceIdentifier", replica).formParam("StorageType", storageType).post("/");
            Response point = query("RestoreDBInstanceToPointInTime").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("TargetDBInstanceIdentifier", pitr).formParam("UseLatestRestorableTime", "true")
                    .formParam("StorageType", storageType).post("/");
            assertAll(() -> assertEquals(200, restored.statusCode()),
                    () -> assertEquals(storageType, type(restored, "RestoreDBInstanceFromDBSnapshot")),
                    () -> assertEquals(200, replicated.statusCode()),
                    () -> assertEquals(storageType, type(replicated, "CreateDBInstanceReadReplica")),
                    () -> assertEquals(200, point.statusCode()),
                    () -> assertEquals(storageType, type(point, "RestoreDBInstanceToPointInTime")));
        } finally {
            delete(pitr);
            delete(replica);
            delete(restore);
            query("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", snapshot).post("/");
            delete(source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "mariadb", "sqlserver-se"})
    void theOtherSupportedEnginesRetainGeneralPurposeTypes(String engine) {
        String id = "storage-engine-" + engine;
        try {
            Response created = create(id, engine).formParam("StorageType", "gp3").post("/");
            assertEquals(200, created.statusCode());
            assertEquals("gp3", type(created, "CreateDBInstance"));
            assertEquals(engine, created.xmlPath().getString("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Engine"));
            Response modified = query("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("StorageType", "gp2").post("/");
            assertEquals(200, modified.statusCode());
            assertEquals("gp2", type(modified, "ModifyDBInstance"));
        } finally {
            delete(id);
        }
    }

    @Test
    void theSameIdentifierKeepsIndependentOwnerAndRegionSettings() {
        String id = "storage-scoped";
        List<String[]> scopes = List.of(new String[]{"us-east-1", "111111111111", "gp3"},
                new String[]{"us-east-1", "222222222222", "gp2"},
                new String[]{"eu-west-1", "111111111111", "gp2"});
        try {
            for (String[] scope : scopes) {
                query("CreateDBInstance", scope[0], scope[1]).formParam("DBInstanceIdentifier", id)
                        .formParam("Engine", "postgres").formParam("DBInstanceClass", "db.t3.micro")
                        .formParam("MasterUsername", "admin").formParam("MasterUserPassword", "password123")
                        .formParam("AllocatedStorage", "20").formParam("StorageType", scope[2])
                        .post("/").then().statusCode(200);
            }
            query("ModifyDBInstance", "us-east-1", "222222222222").formParam("DBInstanceIdentifier", id)
                    .formParam("StorageType", "gp3").post("/").then().statusCode(200);
            query("ModifyDBInstance", "us-east-1", "111111111111").formParam("DBInstanceIdentifier", id)
                    .formParam("StorageType", "gp2").post("/").then().statusCode(200);
            List<String> expected = List.of("gp2", "gp3", "gp2");
            for (int index = 0; index < scopes.size(); index++) {
                String[] scope = scopes.get(index);
                Response described = query("DescribeDBInstances", scope[0], scope[1])
                        .formParam("DBInstanceIdentifier", id).post("/");
                assertEquals(expected.get(index), described.xmlPath().getString(
                        "DescribeDBInstancesResponse.DescribeDBInstancesResult.DBInstances.DBInstance.StorageType"));
            }
        } finally {
            for (String[] scope : scopes) {
                query("DeleteDBInstance", scope[0], scope[1]).formParam("DBInstanceIdentifier", id)
                        .formParam("SkipFinalSnapshot", "true").post("/");
            }
        }
    }

    @Test
    void unsupportedDerivedOverridesCannotPublishATarget() {
        String source = "storage-invalid-source";
        String snapshot = "storage-invalid-snapshot";
        List<String> targets = List.of("storage-invalid-restore", "storage-invalid-pitr", "storage-invalid-replica");
        try {
            create(source).formParam("StorageType", "gp3").formParam("BackupRetentionPeriod", "2")
                    .post("/").then().statusCode(200);
            query("CreateDBSnapshot").formParam("DBSnapshotIdentifier", snapshot)
                    .formParam("DBInstanceIdentifier", source).post("/").then().statusCode(200);
            Response restore = query("RestoreDBInstanceFromDBSnapshot").formParam("DBSnapshotIdentifier", snapshot)
                    .formParam("DBInstanceIdentifier", targets.get(0)).formParam("StorageType", "unsupported").post("/");
            Response pitr = query("RestoreDBInstanceToPointInTime").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("TargetDBInstanceIdentifier", targets.get(1)).formParam("UseLatestRestorableTime", "true")
                    .formParam("StorageType", "unsupported").post("/");
            Response replica = query("CreateDBInstanceReadReplica").formParam("SourceDBInstanceIdentifier", source)
                    .formParam("DBInstanceIdentifier", targets.get(2)).formParam("StorageType", "unsupported").post("/");
            for (Response response : List.of(restore, pitr, replica)) {
                assertEquals(400, response.statusCode());
                assertEquals("StorageTypeNotSupported", response.xmlPath().getString("ErrorResponse.Error.Code"));
            }
            for (String target : targets) {
                query("DescribeDBInstances").formParam("DBInstanceIdentifier", target).post("/").then().statusCode(404);
            }
        } finally {
            for (String target : targets) {
                delete(target);
            }
            query("DeleteDBSnapshot").formParam("DBSnapshotIdentifier", snapshot).post("/");
            delete(source);
        }
    }

    @Test
    void clusterOwnedStorageDoesNotAcquireAnInstanceTypeOverride() {
        String cluster = "storage-cluster-owned";
        String member = "storage-cluster-member";
        try {
            query("CreateDBCluster").formParam("DBClusterIdentifier", cluster)
                    .formParam("Engine", "aurora-postgresql").formParam("MasterUsername", "admin")
                    .formParam("MasterUserPassword", "password123").post("/").then().statusCode(200);
            Response created = query("CreateDBInstance").formParam("DBInstanceIdentifier", member)
                    .formParam("Engine", "aurora-postgresql").formParam("DBInstanceClass", "db.t3.micro")
                    .formParam("DBClusterIdentifier", cluster).formParam("StorageType", "unsupported")
                    .post("/");
            assertEquals(200, created.statusCode());
            assertEquals("gp2", type(created, "CreateDBInstance"));
            Response changed = query("ModifyDBInstance").formParam("DBInstanceIdentifier", member)
                    .formParam("StorageType", "unsupported").post("/");
            assertEquals(200, changed.statusCode());
            assertEquals("gp2", type(changed, "ModifyDBInstance"));
            Response options = query("DescribeValidDBInstanceModifications")
                    .formParam("DBInstanceIdentifier", member).post("/");
            assertEquals(0, options.xmlPath().getList("DescribeValidDBInstanceModificationsResponse."
                    + "DescribeValidDBInstanceModificationsResult.ValidDBInstanceModificationsMessage."
                    + "Storage.ValidStorageOptions").size());
        } finally {
            delete(member);
            query("DeleteDBCluster").formParam("DBClusterIdentifier", cluster)
                    .formParam("SkipFinalSnapshot", "true").post("/");
        }
    }

}
