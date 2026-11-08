package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class RdsInstanceSnapshotIamTest {
    private static final Logger LOG = Logger.getLogger(RdsInstanceSnapshotIamTest.class.getName());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoredIamUsesExplicitFlagOrDefaultsFalse(boolean sourceEnabled) {
        String source = TestFixtures.uniqueName("snapshot-iam");
        String snapshot = source + "-snap";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                rds.createDBInstance(b -> b.dbInstanceIdentifier(source).engine("postgres")
                        .dbInstanceClass("db.t3.micro").allocatedStorage(20)
                        .masterUsername("admin").masterUserPassword("Password123!")
                        .enableIAMDatabaseAuthentication(sourceEnabled));
                rds.createDBSnapshot(b -> b.dbInstanceIdentifier(source).dbSnapshotIdentifier(snapshot));
                for (Boolean flag : new Boolean[]{null, false, true}) {
                    String target = source + "-" + (flag == null ? "omitted" : flag.toString());
                    try {
                        DBInstance restored = rds.restoreDBInstanceFromDBSnapshot(b -> {
                            b.dbInstanceIdentifier(target).dbSnapshotIdentifier(snapshot);
                            if (flag != null) {
                                b.enableIAMDatabaseAuthentication(flag);
                            }
                            if (Boolean.TRUE.equals(flag)) {
                                b.port(5433);
                            }
                        }).dbInstance();
                        boolean expected = Boolean.TRUE.equals(flag);
                        assertThat(restored.iamDatabaseAuthenticationEnabled()).isEqualTo(expected);
                        assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(target)).dbInstances()
                                .get(0).iamDatabaseAuthenticationEnabled()).isEqualTo(expected);
                        assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(source)).dbInstances()
                                .get(0).iamDatabaseAuthenticationEnabled()).isEqualTo(sourceEnabled);
                    } finally {
                        deleteInstance(rds, target);
                    }
                }
            } finally {
                try {
                    rds.deleteDBSnapshot(b -> b.dbSnapshotIdentifier(snapshot));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up snapshot " + snapshot, e);
                }
                deleteInstance(rds, source);
            }
        }
    }

    private static void deleteInstance(RdsClient rds, String identifier) {
        try {
            rds.deleteDBInstance(b -> b.dbInstanceIdentifier(identifier).skipFinalSnapshot(true));
        } catch (RdsException e) {
            LOG.log(Level.WARNING, "Could not clean up instance " + identifier, e);
        }
    }
}
