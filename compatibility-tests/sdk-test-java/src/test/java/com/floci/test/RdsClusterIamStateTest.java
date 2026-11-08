package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;

import static org.assertj.core.api.Assertions.assertThat;

class RdsClusterIamStateTest {

    @Test
    @DisplayName("Aurora IAM authentication changes reach existing members")
    void clusterIamAuthenticationUpdatesMembers() {
        String clusterId = TestFixtures.uniqueName("sdk-iam-cluster");
        String memberId = TestFixtures.uniqueName("sdk-iam-member");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            boolean clusterCreated = false;
            boolean memberCreated = false;
            try {
                rds.createDBCluster(request -> request.dbClusterIdentifier(clusterId)
                        .engine("aurora-postgresql").masterUsername("admin")
                        .masterUserPassword("Secret12345").enableIAMDatabaseAuthentication(false));
                clusterCreated = true;
                rds.createDBInstance(request -> request.dbInstanceIdentifier(memberId)
                        .dbClusterIdentifier(clusterId).dbInstanceClass("db.r5.large")
                        .engine("aurora-postgresql"));
                memberCreated = true;
                for (boolean enabled : new boolean[] {true, false}) {
                    assertThat(rds.modifyDBCluster(request -> request.dbClusterIdentifier(clusterId)
                            .enableIAMDatabaseAuthentication(enabled).applyImmediately(true))
                            .dbCluster().iamDatabaseAuthenticationEnabled()).isEqualTo(enabled);
                    assertThat(rds.describeDBInstances(request -> request.dbInstanceIdentifier(memberId))
                            .dbInstances().get(0).iamDatabaseAuthenticationEnabled()).isEqualTo(enabled);
                }
            } finally {
                try {
                    if (memberCreated) {
                        rds.deleteDBInstance(request -> request.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                    }
                } finally {
                    if (clusterCreated) {
                        rds.deleteDBCluster(request -> request.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                    }
                }
            }
        }
    }
}
