package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.testing.RdsMockProfile;
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
import software.amazon.awssdk.services.rds.model.RdsException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsManagedPasswordRotationIntegrationTest {

    @Inject
    RdsService service;

    @ParameterizedTest
    @CsvSource({"aurora-postgresql,us-east-1,771661000001", "aurora-mysql,eu-west-2,771661000002",
            "aurora-postgresql,cn-north-1,771661000003", "aurora-mysql,us-gov-west-1,771661000004"})
    void sdkRotationPublishesMatchingCurrentAndPreviousCredentials(String engine, String region, String account)
            throws Exception {
        String id = "rotate-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient(region, account); SecretsManagerClient secrets = secrets(region, account)) {
            DBCluster created = rds.createDBCluster(request -> request.dbClusterIdentifier(id).engine(engine)
                    .masterUsername("clusteradmin").manageMasterUserPassword(true)).dbCluster();
            String arn = created.masterUserSecret().secretArn();
            try {
                GetSecretValueResponse before = secrets.getSecretValue(request -> request.secretId(arn));
                rds.modifyDBCluster(request -> request.dbClusterIdentifier(id).rotateMasterUserPassword(false));
                assertEquals(before.versionId(), secrets.getSecretValue(request -> request.secretId(arn)).versionId());

                rds.createDBInstance(request -> request.dbInstanceIdentifier(id + "-member").dbClusterIdentifier(id)
                        .engine(engine).dbInstanceClass("db.r5.large"));
                try {
                    DBCluster rotated = rds.modifyDBCluster(request -> request.dbClusterIdentifier(id)
                            .rotateMasterUserPassword(true).applyImmediately(true)).dbCluster();
                    assertEquals(arn, rotated.masterUserSecret().secretArn());
                    assertEquals("active", rotated.masterUserSecret().secretStatus());
                    GetSecretValueResponse after = secrets.getSecretValue(request -> request.secretId(arn));
                    assertNotEquals(before.versionId(), after.versionId());
                    JsonNode oldValue = new ObjectMapper().readTree(before.secretString());
                    JsonNode newValue = new ObjectMapper().readTree(after.secretString());
                    assertTrue(!oldValue.path("password").equals(newValue.path("password")), "password must change");
                    assertEquals("clusteradmin", newValue.path("username").asText());
                    assertEquals(before.versionId(), secrets.getSecretValue(request -> request.secretId(arn)
                            .versionStage("AWSPREVIOUS")).versionId());
                    String password = newValue.path("password").asText();
                    RequestScopes.runAs(account, region, () -> {
                        assertTrue(password.equals(service.getDbCluster(id, region).getMasterPassword()));
                        assertTrue(password.equals(service.getDbInstance(id + "-member", region).getMasterPassword()));
                    });
                    RdsException deferred = assertThrows(RdsException.class, () -> rds.modifyDBCluster(request -> request
                            .dbClusterIdentifier(id).rotateMasterUserPassword(true).applyImmediately(false)));
                    assertEquals(400, deferred.statusCode());
                    assertEquals(after.versionId(), secrets.getSecretValue(request -> request.secretId(arn)).versionId());
                } finally {
                    rds.deleteDBInstance(request -> request.dbInstanceIdentifier(id + "-member").skipFinalSnapshot(true));
                }
            } finally {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void sdkRejectsRotationWithoutAnExistingManagedSecret(boolean enableManagement) {
        String id = "unmanaged-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient("us-east-1", "771661000005")) {
            rds.createDBCluster(request -> request.dbClusterIdentifier(id).engine("aurora-postgresql")
                    .masterUsername("clusteradmin").masterUserPassword("SyntheticPassword!"));
            try {
                RdsException error = assertThrows(RdsException.class, () -> rds.modifyDBCluster(request -> request
                        .dbClusterIdentifier(id).rotateMasterUserPassword(true).applyImmediately(true)
                        .manageMasterUserPassword(enableManagement ? true : null)));
                assertEquals(400, error.statusCode());
            } finally {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    private static SecretsManagerClient secrets(String region, String account) {
        return SecretsManagerClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
