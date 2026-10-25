package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.RdsRequests.rdsClient;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsManagedSecretIntegrationTest {

    @Inject
    KmsService kmsService;

    @Inject
    RdsService rdsService;

    @ParameterizedTest
    @CsvSource({"aurora-postgresql, username, true", "aurora-postgresql, username, false",
            "aurora-postgresql, password, true", "aurora-postgresql, password, false",
            "aurora-mysql, username, true", "aurora-mysql, username, false",
            "aurora-mysql, password, true", "aurora-mysql, password, false"})
    void sdkRejectsMemberCredentialParametersAndAllowsOmittedRetry(String engine, String field, boolean matching) {
        String id = "member-input-" + UUID.randomUUID().toString().substring(0, 8);
        String memberId = id + "-member";
        try (RdsClient rds = rdsClient("us-east-1", "000000000000")) {
            rds.createDBCluster(request -> request.dbClusterIdentifier(id).engine(engine)
                    .masterUsername("clusteradmin").masterUserPassword("ClusterPassword!"));
            try {
                CreateDbInstanceRequest.Builder request = CreateDbInstanceRequest.builder()
                        .dbInstanceIdentifier(memberId).dbClusterIdentifier(id).engine(engine)
                        .dbInstanceClass("db.serverless");
                if ("username".equals(field)) {
                    request.masterUsername(matching ? "clusteradmin" : "differentadmin");
                } else {
                    request.masterUserPassword(matching ? "ClusterPassword!" : "DifferentPassword!");
                }
                RdsException error = assertThrows(RdsException.class, () -> rds.createDBInstance(request.build()));
                assertEquals("InvalidParameterCombination", error.awsErrorDetails().errorCode());
                assertEquals(400, error.statusCode());
                assertEquals("The requested DB Instance will be a member of a DB Cluster. "
                        + ("password".equals(field) ? "Set master user password for the DB Cluster."
                        : "Set master user name for the DB Cluster."), error.awsErrorDetails().errorMessage());
                assertEquals("DBInstanceNotFound", assertThrows(RdsException.class,
                        () -> rds.describeDBInstances(value -> value.dbInstanceIdentifier(memberId)))
                        .awsErrorDetails().errorCode());
                assertTrue(rds.describeDBClusters(value -> value.dbClusterIdentifier(id))
                        .dbClusters().getFirst().dbClusterMembers().isEmpty());
                request.masterUsername(null).masterUserPassword(null);
                DBInstance member = rds.createDBInstance(request.build()).dbInstance();
                assertEquals("clusteradmin", member.masterUsername());
            } finally {
                try {
                    try {
                        rds.deleteDBInstance(value -> value.dbInstanceIdentifier(memberId).skipFinalSnapshot(true));
                    } catch (RdsException error) {
                        if (!"DBInstanceNotFound".equals(error.awsErrorDetails().errorCode())) {
                            throw error;
                        }
                    }
                } finally {
                    rds.deleteDBCluster(value -> value.dbClusterIdentifier(id).skipFinalSnapshot(true));
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,000000000000", "cn-north-1,000000000000",
            "us-east-1,222222222222", "cn-north-1,222222222222"})
    void sdkReportsTheActualDefaultManagedKeyForInstancesAndClusters(String region, String account) {
        String id = "managed-key-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient(region, account)) {
            DBInstance instance = rds.createDBInstance(request -> request
                    .dbInstanceIdentifier(id)
                    .dbInstanceClass("db.t4g.small")
                    .engine("postgres")
                    .engineVersion("16.14")
                    .masterUsername("admin")
                    .allocatedStorage(20)
                    .manageMasterUserPassword(true)).dbInstance();
            try {
                String keyArn = RequestScopes.callAs(account, region,
                        () -> kmsService.describeKey("alias/aws/secretsmanager", region).getArn());
                assertEquals(keyArn, instance.masterUserSecret().kmsKeyId());
                RequestScopes.runAs(account, region,
                        () -> rdsService.getDbInstance(id, region).setMasterUserSecretKmsKeyId(null));
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
                    RequestScopes.runAs(account, region,
                            () -> rdsService.getDbCluster(id, region).setMasterUserSecretKmsKeyId(null));
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
    @ParameterizedTest
    @CsvSource({"aurora-postgresql,us-east-1,000000000000", "aurora-mysql,us-east-1,000000000000",
            "aurora-postgresql,cn-north-1,000000000000", "aurora-mysql,cn-north-1,000000000000",
            "aurora-postgresql,us-east-1,222222222222", "aurora-mysql,us-east-1,222222222222",
            "aurora-postgresql,cn-north-1,222222222222", "aurora-mysql,cn-north-1,222222222222"})
    void sdkClusterMemberLeavesTheManagedSecretOnTheCluster(String engine, String region, String account)
            throws Exception {
        String id = "cluster-managed-" + UUID.randomUUID().toString().substring(0, 8);
        try (RdsClient rds = rdsClient(region, account)) {
            DBCluster cluster = rds.createDBCluster(request -> request.dbClusterIdentifier(id).engine(engine)
                    .masterUsername("clusteradmin").manageMasterUserPassword(true)).dbCluster();
            try {
                String clusterArn = cluster.masterUserSecret().secretArn();
                String original = secretValue(region, account, clusterArn);
                DBInstance member = rds.createDBInstance(request -> request.dbInstanceIdentifier(id + "-member")
                        .dbClusterIdentifier(id).engine(engine).dbInstanceClass("db.r5.large")
                        .manageMasterUserPassword(true)).dbInstance();
                try {
                    assertEquals("clusteradmin", member.masterUsername());
                    assertNull(member.masterUserSecret());
                    assertNull(rds.describeDBInstances(request -> request.dbInstanceIdentifier(id + "-member"))
                            .dbInstances().getFirst().masterUserSecret());
                    assertEquals("ResourceNotFoundException", given()
                            .header("Authorization", secretAuthorization(region, account))
                            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
                            .contentType("application/x-amz-json-1.1")
                            .body(new ObjectMapper().writeValueAsBytes(Map.of("SecretId", "rds!" + member.dbiResourceId())))
                            .post("/").then().statusCode(400).extract().path("__type"));
                    assertEquals(original, secretValue(region, account, clusterArn));
                } finally {
                    rds.deleteDBInstance(request -> request.dbInstanceIdentifier(id + "-member").skipFinalSnapshot(true));
                }
                assertEquals(original, secretValue(region, account, clusterArn));
                assertEquals(clusterArn, rds.describeDBClusters(request -> request.dbClusterIdentifier(id))
                        .dbClusters().getFirst().masterUserSecret().secretArn());
            } finally {
                rds.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
            }
        }
    }

    private static String secretValue(String region, String account, String arn) throws Exception {
        return given().header("Authorization", secretAuthorization(region, account))
                .header("X-Amz-Target", "secretsmanager.GetSecretValue").contentType("application/x-amz-json-1.1")
                .body(new ObjectMapper().writeValueAsBytes(Map.of("SecretId", arn))).post("/")
                .then().statusCode(200).extract().path("SecretString");
    }

    private static String secretAuthorization(String region, String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + region
                + "/secretsmanager/aws4_request, SignedHeaders=host, Signature=abc";
    }

}
