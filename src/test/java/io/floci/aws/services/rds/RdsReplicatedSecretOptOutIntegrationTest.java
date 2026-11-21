package io.floci.aws.services.rds;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsReplicatedSecretOptOutIntegrationTest {
    @Inject RdsService rdsService;
    @Inject SecretsManagerService secrets;

    @ParameterizedTest
    @CsvSource({"aurora-postgresql,16.4", "aurora-mysql,8.0"})
    void replicatedSecretOptOutReturnsManagedCredentials(String engine, String version) {
        String account = "000000000000";
        String region = "us-east-1";
        String id = "optout-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient(region, account)) {
            DBCluster created = rds.createDBCluster(r -> r.dbClusterIdentifier(id).engine(engine)
                    .engineVersion(version).masterUsername("admin").manageMasterUserPassword(true)).dbCluster();
            String arn = created.masterUserSecret().secretArn();
            try {
                String password = RequestScopes.callAs(account, region,
                        () -> rdsService.getDbCluster(id, region).getMasterPassword());
                RequestScopes.runAs(account, region, () -> secrets.replicateSecretToRegions(arn,
                        List.of(new SecretsManagerService.ReplicaRegion("us-west-2", null)), false, region));
                String secretValue = RequestScopes.callAs(account, region,
                        () -> secrets.getSecretValue(arn, null, null, region).getSecretString());

                DBCluster modified = rds.modifyDBCluster(r -> r.dbClusterIdentifier(id)
                        .manageMasterUserPassword(false).masterUserPassword("replacement-password")).dbCluster();
                DBCluster described = rds.describeDBClusters(r -> r.dbClusterIdentifier(id)).dbClusters().get(0);
                assertAll(
                        () -> assertEquals(created.masterUserSecret(), modified.masterUserSecret()),
                        () -> assertEquals(created.masterUserSecret(), described.masterUserSecret()),
                        () -> assertEquals(password, RequestScopes.callAs(account, region,
                                () -> rdsService.getDbCluster(id, region).getMasterPassword())),
                        () -> assertEquals(secretValue, RequestScopes.callAs(account, region,
                                () -> secrets.getSecretValue(arn, null, null, region).getSecretString())));
                RequestScopes.runAs(account, region,
                        () -> secrets.removeRegionsFromReplication(arn, List.of("us-west-2"), region));
                DBCluster released = rds.modifyDBCluster(r -> r.dbClusterIdentifier(id)
                        .manageMasterUserPassword(false).masterUserPassword("replacement-password")).dbCluster();
                assertNull(released.masterUserSecret());
                assertNull(rds.describeDBClusters(r -> r.dbClusterIdentifier(id)).dbClusters().get(0).masterUserSecret());
                assertEquals("replacement-password", RequestScopes.callAs(account, region,
                        () -> rdsService.getDbCluster(id, region).getMasterPassword()));
                AwsException missing = assertThrows(AwsException.class, () -> RequestScopes.callAs(account, region,
                        () -> secrets.describeSecret(arn, region)));
                assertEquals("ResourceNotFoundException", missing.getErrorCode());
            } finally {
                try {
                    RequestScopes.runAs(account, region, () -> {
                        try {
                            secrets.removeRegionsFromReplication(arn, List.of("us-west-2"), region);
                        } catch (AwsException e) {
                            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                                throw e;
                            }
                        }
                    });
                } finally {
                    try {
                        rds.deleteDBCluster(r -> r.dbClusterIdentifier(id).skipFinalSnapshot(true));
                    } finally {
                        RequestScopes.runAs(account, region, () -> {
                            try {
                                secrets.deleteSecret(arn, null, true, region);
                            } catch (AwsException e) {
                                if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                                    throw e;
                                }
                            }
                        });
                    }
                }
            }
        }
    }
}
