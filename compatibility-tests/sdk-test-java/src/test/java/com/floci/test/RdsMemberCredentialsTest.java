package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Aurora member master credentials")
class RdsMemberCredentialsTest {
    private static final Logger LOG = Logger.getLogger(RdsMemberCredentialsTest.class.getName());

    @ParameterizedTest
    @CsvSource({"aurora-postgresql, false", "aurora-postgresql, true",
            "aurora-mysql, false", "aurora-mysql, true"})
    @DisplayName("Members inherit cluster credentials after explicit credentials are rejected")
    void memberMasterUserComesFromCluster(String engine, boolean conflicting) {
        String clusterId = TestFixtures.uniqueName("member-credentials");
        String memberId = clusterId + "-member";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                rds.createDBCluster(b -> b.dbClusterIdentifier(clusterId).engine(engine)
                        .masterUsername("clusteradmin").masterUserPassword("ClusterPassword!"));
                CreateDbInstanceRequest.Builder request = CreateDbInstanceRequest.builder()
                        .dbInstanceIdentifier(memberId).dbClusterIdentifier(clusterId)
                        .engine(engine).dbInstanceClass("db.serverless");
                if (conflicting) {
                    request.masterUsername("differentuser").masterUserPassword("DifferentPassword!");
                    assertThatThrownBy(() -> rds.createDBInstance(request.build()))
                            .isInstanceOfSatisfying(RdsException.class, error -> {
                                assertThat(error.statusCode()).isEqualTo(400);
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterCombination");
                            });
                    request.masterUsername(null).masterUserPassword(null);
                }
                DBInstance created = rds.createDBInstance(request.build()).dbInstance();
                assertThat(created.masterUsername()).isEqualTo("clusteradmin");
                assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(memberId))
                        .dbInstances().get(0).masterUsername()).isEqualTo("clusteradmin");
            } finally {
                try {
                    rds.deleteDBInstance(b -> b.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up member " + memberId, e);
                }
                try {
                    rds.deleteDBCluster(b -> b.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                } catch (RdsException e) {
                    LOG.log(Level.WARNING, "Could not clean up cluster " + clusterId, e);
                }
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"aurora-postgresql", "aurora-mysql"})
    @DisplayName("Managed Aurora credentials remain owned by the cluster")
    void memberDoesNotCreateASeparateManagedSecret(String engine) {
        String clusterId = TestFixtures.uniqueName("cluster-secret-owner");
        String memberId = clusterId + "-member";
        try (RdsClient rds = TestFixtures.rdsClient();
             SecretsManagerClient secrets = TestFixtures.secretsManagerClient()) {
            try {
                String clusterArn = rds.createDBCluster(request -> request.dbClusterIdentifier(clusterId)
                        .engine(engine).masterUsername("clusteradmin").manageMasterUserPassword(true))
                        .dbCluster().masterUserSecret().secretArn();
                String original = secrets.getSecretValue(request -> request.secretId(clusterArn)).secretString();
                DBInstance member = rds.createDBInstance(request -> request.dbInstanceIdentifier(memberId)
                        .dbClusterIdentifier(clusterId).engine(engine).dbInstanceClass("db.serverless")
                        .manageMasterUserPassword(true)).dbInstance();
                assertThat(member.masterUsername()).isEqualTo("clusteradmin");
                assertThat(member.masterUserSecret()).isNull();
                assertThat(rds.describeDBInstances(request -> request.dbInstanceIdentifier(memberId))
                        .dbInstances().get(0).masterUserSecret()).isNull();
                assertThatThrownBy(() -> secrets.getSecretValue(request -> request.secretId("rds!" + member.dbiResourceId())))
                        .isInstanceOfSatisfying(ResourceNotFoundException.class,
                                error -> assertThat(error.statusCode()).isEqualTo(400));
                rds.deleteDBInstance(request -> request.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                assertThat(secrets.getSecretValue(request -> request.secretId(clusterArn)).secretString()).isEqualTo(original);
            } finally {
                try {
                    try {
                        rds.deleteDBInstance(request -> request.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                    } catch (RdsException error) {
                        if (!"DBInstanceNotFound".equals(error.awsErrorDetails().errorCode())) {
                            throw error;
                        }
                    }
                } finally {
                    rds.deleteDBCluster(request -> request.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aurora-postgresql", "aurora-mysql"})
    @DisplayName("Member-only password settings preserve other instance modifications")
    void memberPasswordSettingDoesNotPreventOtherChangesOrReboot(String engine) {
        String cluster = TestFixtures.uniqueName("member-modify");
        String member = cluster + "-member";
        try (RdsClient rds = TestFixtures.rdsClient()) {
            try {
                rds.createDBCluster(request -> request.dbClusterIdentifier(cluster).engine(engine)
                        .masterUsername("clusteradmin").masterUserPassword("ClusterPassword!"));
                rds.createDBInstance(request -> request.dbInstanceIdentifier(member).dbClusterIdentifier(cluster)
                        .engine(engine).dbInstanceClass("db.serverless"));
                DBInstance modified = rds.modifyDBInstance(request -> request.dbInstanceIdentifier(member)
                        .masterUserPassword("MemberOnlyPassword!").publiclyAccessible(true)).dbInstance();
                assertThat(modified.masterUsername()).isEqualTo("clusteradmin");
                assertThat(modified.publiclyAccessible()).isTrue();
                assertThat(rds.rebootDBInstance(request -> request.dbInstanceIdentifier(member))
                        .dbInstance().masterUsername()).isEqualTo("clusteradmin");
                assertThat(rds.describeDBInstances(request -> request.dbInstanceIdentifier(member))
                        .dbInstances().get(0).publiclyAccessible()).isTrue();
            } finally {
                try {
                    rds.deleteDBInstance(request -> request.dbInstanceIdentifier(member).skipFinalSnapshot(true));
                } finally {
                    rds.deleteDBCluster(request -> request.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
                }
            }
        }
    }

}
