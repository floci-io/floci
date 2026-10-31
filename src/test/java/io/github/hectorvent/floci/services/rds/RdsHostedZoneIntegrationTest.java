package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbClusterResponse;

import static io.github.hectorvent.floci.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsHostedZoneIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-1"})
    void clusterAndInstanceHostedZonesRoundTripThroughSdk(String region) {
        String cluster = "hosted-zone-cluster";
        String instance = "hosted-zone-instance";
        try (RdsClient client = rdsClient(region, "123456789012")) {
            boolean clusterCreated = false;
            boolean instanceCreated = false;
            try {
                CreateDbClusterResponse created = client.createDBCluster(b -> b.dbClusterIdentifier(cluster)
                        .engine("aurora-postgresql").masterUsername("admin").masterUserPassword("password123"));
                clusterCreated = true;
                assertEquals("Z2R2ITUGPM61AM", created.dbCluster().hostedZoneId());
                assertEquals("Z2R2ITUGPM61AM", client.describeDBClusters(b -> b.dbClusterIdentifier(cluster))
                        .dbClusters().getFirst().hostedZoneId());
                client.createDBInstance(b -> b.dbInstanceIdentifier(instance).engine("postgres")
                        .dbInstanceClass("db.t3.micro").masterUsername("admin").masterUserPassword("password123")
                        .allocatedStorage(20));
                instanceCreated = true;
                assertEquals("Z2R2ITUGPM61AM", client.describeDBInstances(b -> b.dbInstanceIdentifier(instance))
                        .dbInstances().getFirst().endpoint().hostedZoneId());
            } finally {
                try {
                    if (instanceCreated) {
                        client.deleteDBInstance(b -> b.dbInstanceIdentifier(instance).skipFinalSnapshot(true));
                    }
                } finally {
                    if (clusterCreated) {
                        client.deleteDBCluster(b -> b.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
                    }
                }
            }
        }
    }
}
