package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsManagedSecretIntegrationTest {

    @Inject
    KmsService kmsService;

    @Inject
    RdsService rdsService;

    @ParameterizedTest
    @CsvSource({"us-east-1,000000000000", "cn-north-1,000000000000",
            "us-east-1,222222222222", "cn-north-1,222222222222"})
    void sdkReportsTheActualDefaultManagedKeyForInstancesAndClusters(String region, String account) {
        String id = "managed-key-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = RdsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account, "test-secret-key")))
                .build()) {
            DBInstance instance = rds.createDBInstance(request -> request
                    .dbInstanceIdentifier(id)
                    .dbInstanceClass("db.t4g.small")
                    .engine("postgres")
                    .engineVersion("16.14")
                    .masterUsername("admin")
                    .allocatedStorage(20)
                    .manageMasterUserPassword(true)).dbInstance();
            try {
                String keyArn = kmsService.describeKeyForAccount("alias/aws/secretsmanager", region, account).getArn();
                assertEquals(keyArn, instance.masterUserSecret().kmsKeyId());
                clearSavedKey(account, () -> rdsService.getDbInstance(id, region).setMasterUserSecretKmsKeyId(null));
                rdsService.backfillManagedSecretOwnership();
                assertEquals(keyArn, rds.describeDBInstances(request -> request.dbInstanceIdentifier(id))
                        .dbInstances().getFirst().masterUserSecret().kmsKeyId());

                DBCluster cluster = rds.createDBCluster(request -> request
                        .dbClusterIdentifier(id)
                        .engine("aurora-postgresql")
                        .engineVersion("16.4")
                        .masterUsername("admin")
                        .manageMasterUserPassword(true)).dbCluster();
                try {
                    assertEquals(keyArn, cluster.masterUserSecret().kmsKeyId());
                    clearSavedKey(account, () -> rdsService.getDbCluster(id, region).setMasterUserSecretKmsKeyId(null));
                    rdsService.backfillManagedSecretOwnership();
                    assertEquals(keyArn, rds.describeDBClusters(request -> request.dbClusterIdentifier(id))
                            .dbClusters().getFirst().masterUserSecret().kmsKeyId());
                } finally {
                    rds.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
                }
            } finally {
                rds.deleteDBInstance(request -> request.dbInstanceIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    private void clearSavedKey(String account, Runnable clear) {
        ManagedContext context = Arc.container().requestContext();
        boolean active = context.isActive();
        if (!active) {
            context.activate();
        }
        RequestContext request = Arc.container().instance(RequestContext.class).get();
        String previousAccount = request.getAccountId();
        try {
            request.setAccountId(account);
            clear.run();
        } finally {
            request.setAccountId(previousAccount);
            if (!active) {
                context.terminate();
            }
        }
    }
}
