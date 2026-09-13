package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

/**
 * EngineMode and StorageEncrypted round-trip from CreateDBCluster to DescribeDBClusters for an
 * Aurora-shaped DBCluster, so a caller no longer sees drift on these two fields (issue #2559).
 * The "Aurora DB clusters only" restriction on EngineMode is scoped to the CreateDBCluster
 * request parameter, not the DBCluster response member, so a Multi-AZ, non-Aurora cluster still
 * reports EngineMode (defaulting to provisioned) in DescribeDBClusters, same as StorageEncrypted.
 */
@QuarkusTest
@TestProfile(RdsClusterEngineModeStorageEncryptedIntegrationTest.NoContainersProfile.class)
class RdsClusterEngineModeStorageEncryptedIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true");
        }
    }

    private static final String ID = "engine-mode-cluster";
    private static final String NON_AURORA_ID = "engine-mode-non-aurora-cluster";

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", NON_AURORA_ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void engineModeAndStorageEncryptedRoundTripThroughDescribe() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("EngineMode", "serverless")
                .formParam("StorageEncrypted", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>serverless</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>serverless</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
    }

    @Test
    void engineModeDefaultsToProvisionedAndStorageEncryptedDefaultsToFalseWhenOmitted() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>false</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>false</StorageEncrypted>"));
    }

    @Test
    void engineModeStillEmittedForNonAuroraMultiAzCluster() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", NON_AURORA_ID)
                .formParam("Engine", "mysql")
                .formParam("EngineVersion", "8.0.36")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("MultiAZ", "true")
                .formParam("StorageEncrypted", "true")
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));

        rds("DescribeDBClusters").formParam("DBClusterIdentifier", NON_AURORA_ID)
                .when().post("/").then().statusCode(200)
                .body(containsString("<EngineMode>provisioned</EngineMode>"))
                .body(containsString("<StorageEncrypted>true</StorageEncrypted>"));
    }
}
