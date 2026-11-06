package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.StorageTypeNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class RdsStorageTypeTest {
    @Test
    void requestedTypesRoundTripAndUnsupportedTypesLeaveTheInstanceUnchanged() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator storage metadata fixtures");
        String id = TestFixtures.uniqueName("rds-storage");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                DBInstance created = rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(20).storageType("gp3").backupRetentionPeriod(2)).dbInstance();
                assertThat(created.storageType()).isEqualTo("gp3");
                assertThat(rds.describeDBInstances(r -> r.dbInstanceIdentifier(id)).dbInstances().get(0).storageType())
                        .isEqualTo("gp3");
                assertThatThrownBy(() -> rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id)
                        .storageType("not-a-storage-type").backupRetentionPeriod(7)))
                        .isInstanceOfSatisfying(StorageTypeNotSupportedException.class,
                                error -> assertThat(error.statusCode()).isEqualTo(400));
                DBInstance retained = rds.describeDBInstances(r -> r.dbInstanceIdentifier(id)).dbInstances().get(0);
                assertThat(retained.storageType()).isEqualTo("gp3");
                assertThat(retained.backupRetentionPeriod()).isEqualTo(2);
                assertThat(rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).storageType("gp2"))
                        .dbInstance().storageType()).isEqualTo("gp2");
                assertThat(rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).backupRetentionPeriod(3))
                        .dbInstance().storageType()).isEqualTo("gp2");
                assertThat(rds.describeValidDBInstanceModifications(r -> r.dbInstanceIdentifier(id))
                        .validDBInstanceModificationsMessage().storage())
                        .extracting(option -> option.storageType()).containsExactly("gp2", "gp3", "io1", "io2");
            } finally {
                remove(rds, id);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io1", "io2"})
    void provisionedTypesRoundTripWithoutIops(String storageType) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator storage metadata fixtures");
        String id = TestFixtures.uniqueName("rds-provisioned");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                DBInstance created = rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(100).storageType(storageType)).dbInstance();
                assertThat(created.storageType()).isEqualTo(storageType);
                assertThat(created.iops()).isNull();
                assertThat(rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).storageType("gp3"))
                        .dbInstance().storageType()).isEqualTo("gp3");
                assertThat(rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).storageType(storageType))
                        .dbInstance().storageType()).isEqualTo(storageType);
                DBInstance retained = rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).backupRetentionPeriod(2))
                        .dbInstance();
                assertThat(retained.storageType()).isEqualTo(storageType);
                assertThat(retained.iops()).isNull();
            } finally {
                remove(rds, id);
            }
        }
    }

    @Test
    void aRefusedCreateCanBeRetriedWithASupportedType() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator storage metadata fixtures");
        String id = TestFixtures.uniqueName("rds-storage-retry");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                assertThatThrownBy(() -> rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(20).storageType("not-a-storage-type")))
                        .isInstanceOf(StorageTypeNotSupportedException.class);
                assertThatThrownBy(() -> rds.describeDBInstances(r -> r.dbInstanceIdentifier(id)))
                        .isInstanceOf(DbInstanceNotFoundException.class);
                assertThat(rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(20).storageType("gp3")).dbInstance().storageType()).isEqualTo("gp3");
            } finally {
                remove(rds, id);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "pitr", "replica"})
    void explicitDerivedTypesOverrideTheSourcesType(String operation) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Disposable emulator storage metadata fixtures");
        String source = TestFixtures.uniqueName("rds-storage-source");
        String target = TestFixtures.uniqueName("rds-storage-target");
        String snapshot = TestFixtures.uniqueName("rds-storage-snapshot");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            boolean snapshotCreated = false;
            try {
                rds.createDBInstance(r -> r.dbInstanceIdentifier(source).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(20).storageType("gp3").backupRetentionPeriod(2));
                DBInstance derived;
                if ("snapshot".equals(operation)) {
                    assertThat(rds.createDBSnapshot(r -> r.dbInstanceIdentifier(source).dbSnapshotIdentifier(snapshot))
                            .dbSnapshot().storageType()).isEqualTo("gp3");
                    snapshotCreated = true;
                    derived = rds.restoreDBInstanceFromDBSnapshot(r -> r.dbInstanceIdentifier(target)
                            .dbSnapshotIdentifier(snapshot).storageType("gp2")).dbInstance();
                } else if ("pitr".equals(operation)) {
                    derived = rds.restoreDBInstanceToPointInTime(r -> r.targetDBInstanceIdentifier(target)
                            .sourceDBInstanceIdentifier(source).useLatestRestorableTime(true).storageType("gp2"))
                            .dbInstance();
                } else {
                    derived = rds.createDBInstanceReadReplica(r -> r.dbInstanceIdentifier(target)
                            .sourceDBInstanceIdentifier(source).storageType("gp2")).dbInstance();
                }
                assertThat(derived.storageType()).isEqualTo("gp2");
                assertThat(rds.describeDBInstances(r -> r.dbInstanceIdentifier(target))
                        .dbInstances().get(0).storageType()).isEqualTo("gp2");
                assertThat(rds.describeDBInstances(r -> r.dbInstanceIdentifier(source))
                        .dbInstances().get(0).storageType()).isEqualTo("gp3");
            } finally {
                boolean removeSnapshot = snapshotCreated;
                assertAll(() -> remove(rds, target),
                        () -> {
                            if (removeSnapshot) {
                                rds.deleteDBSnapshot(r -> r.dbSnapshotIdentifier(snapshot));
                            }
                        },
                        () -> remove(rds, source));
            }
        }
    }

    private static void remove(RdsClient rds, String id) {
        try {
            rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
        } catch (DbInstanceNotFoundException ignored) {
            // A refused create deliberately leaves nothing to remove.
        }
    }
}
