package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.RdsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RDS storage autoscaling minimum")
class RdsAutoscalingMinimumTest {
    @ParameterizedTest
    @CsvSource({"20,21,22", "21,23,24"})
    void enabledThresholdRequiresTenPercentHeadroom(int allocated, int invalid, int valid) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses a disposable emulator DB instance");
        String id = TestFixtures.uniqueName("autoscaling-min");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                create(rds, id, allocated);
                assertThatThrownBy(() -> rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id)
                        .maxAllocatedStorage(invalid).backupRetentionPeriod(2)))
                        .isInstanceOfSatisfying(RdsException.class, e -> {
                            assertThat(e.statusCode()).isEqualTo(400);
                            assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                            assertThat(e.awsErrorDetails().errorMessage())
                                    .isEqualTo("Invalid max storage size for postgres");
                        });
                assertState(rds, id, allocated, 100, 7);
                DBInstance modified = rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id)
                        .maxAllocatedStorage(valid).backupRetentionPeriod(2)).dbInstance();
                assertThat(modified.maxAllocatedStorage()).isEqualTo(valid);
                assertThat(modified.backupRetentionPeriod()).isEqualTo(2);
                assertState(rds, id, allocated, valid, 2);
            } finally {
                remove(rds, id);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"30,30,32,false", "30,30,32,true", "21,22,24,false"})
    void simultaneousGrowthDoesNotAddAnUnverifiedMinimum(int requested, int resolved, int maximum,
                                                         boolean immediately) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses a disposable emulator DB instance");
        String id = TestFixtures.uniqueName("autoscaling-min");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                create(rds, id, 20);
                DBInstance modified = rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id)
                        .allocatedStorage(requested).maxAllocatedStorage(maximum).backupRetentionPeriod(2)
                        .applyImmediately(immediately)).dbInstance();
                assertThat(modified.allocatedStorage()).isEqualTo(resolved);
                assertThat(modified.maxAllocatedStorage()).isEqualTo(maximum);
                assertState(rds, id, resolved, maximum, 2);
            } finally {
                remove(rds, id);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"19", "0", "-1"})
    void lowerMaximumRejectsWithoutMutation(int maximum) {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses a disposable emulator DB instance");
        String id = TestFixtures.uniqueName("autoscaling-lower");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                create(rds, id, 20);
                assertThatThrownBy(() -> rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id)
                        .maxAllocatedStorage(maximum).backupRetentionPeriod(2)))
                        .isInstanceOfSatisfying(RdsException.class, error -> {
                            assertThat(error.statusCode()).isEqualTo(400);
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                            assertThat(error.awsErrorDetails().errorMessage())
                                    .isEqualTo("Invalid max storage size for postgres");
                        });
                assertState(rds, id, 20, 100, 7);
                rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).maxAllocatedStorage(22));
                assertState(rds, id, 20, 22, 7);
            } finally {
                remove(rds, id);
            }
        }
    }

    @Test
    void omissionAndDisableRequestsRemainAccepted() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Uses a disposable emulator DB instance");
        String id = TestFixtures.uniqueName("autoscaling-min");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                create(rds, id, 20);
                rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).backupRetentionPeriod(2));
                assertState(rds, id, 20, 100, 2);
                rds.modifyDBInstance(r -> r.dbInstanceIdentifier(id).maxAllocatedStorage(20)
                        .backupRetentionPeriod(3));
                DBInstance instance = rds.describeDBInstances(r -> r.dbInstanceIdentifier(id))
                        .dbInstances().get(0);
                assertThat(instance.allocatedStorage()).isEqualTo(20);
                assertThat(instance.maxAllocatedStorage()).isEqualTo(20);
                assertThat(instance.backupRetentionPeriod()).isEqualTo(3);
            } finally {
                remove(rds, id);
            }
        }
    }

    private static void create(RdsClient rds, String id, int allocated) {
        rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres").dbInstanceClass("db.t3.micro")
                .masterUsername("adminuser").masterUserPassword("secret123").allocatedStorage(allocated)
                .maxAllocatedStorage(100).backupRetentionPeriod(7));
        assertState(rds, id, allocated, 100, 7);
    }

    private static void assertState(RdsClient rds, String id, int allocated, int maximum, int retention) {
        DBInstance instance = rds.describeDBInstances(r -> r.dbInstanceIdentifier(id)).dbInstances().get(0);
        assertThat(instance.allocatedStorage()).isEqualTo(allocated);
        assertThat(instance.maxAllocatedStorage()).isEqualTo(maximum);
        assertThat(instance.backupRetentionPeriod()).isEqualTo(retention);
    }

    private static void remove(RdsClient rds, String id) {
        try {
            rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
        } catch (DbInstanceNotFoundException ignored) {
            // Cleanup is registered before creation, so setup failure may leave no instance.
        }
    }
}
