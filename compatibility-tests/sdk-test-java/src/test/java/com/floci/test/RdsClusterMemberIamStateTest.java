package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;

import static org.assertj.core.api.Assertions.assertThat;

class RdsClusterMemberIamStateTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("New Aurora members inherit cluster IAM authentication")
    void newMembersInheritClusterIamAuthentication(boolean enabled) {
        String clusterId = TestFixtures.uniqueName("sdk-iam-cluster");
        try (RdsClient rds = TestFixtures.rdsClient()) {
            boolean clusterCreated = false;
            try {
                rds.createDBCluster(request -> request.dbClusterIdentifier(clusterId)
                        .engine("aurora-postgresql").masterUsername("admin")
                        .masterUserPassword("Secret12345").enableIAMDatabaseAuthentication(enabled));
                clusterCreated = true;
                for (Boolean requested : new Boolean[]{null, false, true}) {
                    String memberId = TestFixtures.uniqueName("sdk-iam-member");
                    boolean memberCreated = false;
                    try {
                        DBInstance member = rds.createDBInstance(request -> request.dbInstanceIdentifier(memberId)
                                .dbClusterIdentifier(clusterId).dbInstanceClass("db.r5.large")
                                .engine("aurora-postgresql").enableIAMDatabaseAuthentication(requested)).dbInstance();
                        memberCreated = true;
                        assertThat(member.iamDatabaseAuthenticationEnabled()).isEqualTo(enabled);
                        assertThat(rds.describeDBInstances(request -> request.dbInstanceIdentifier(memberId))
                                .dbInstances().get(0).iamDatabaseAuthenticationEnabled()).isEqualTo(enabled);
                    } finally {
                        if (memberCreated) {
                            rds.deleteDBInstance(request -> request.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                        }
                    }
                }
            } finally {
                if (clusterCreated) {
                    rds.deleteDBCluster(request -> request.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                }
            }
        }
    }
}
