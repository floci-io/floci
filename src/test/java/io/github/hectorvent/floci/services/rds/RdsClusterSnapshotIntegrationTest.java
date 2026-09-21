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

@QuarkusTest
@TestProfile(RdsClusterSnapshotIntegrationTest.NoContainersProfile.class)
class RdsClusterSnapshotIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/rds/aws4_request, "
                    + "SignedHeaders=content-type;host, Signature=test";
    private static final String SOURCE_CLUSTER = "snapshot-source-cluster";
    private static final String RESTORED_CLUSTER = "snapshot-restored-cluster";
    private static final String SOURCE_SNAPSHOT = "source-cluster-snapshot";
    private static final String COPY_SNAPSHOT = "copy-cluster-snapshot";
    private static final String SOURCE_SNAPSHOT_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster-snapshot:" + SOURCE_SNAPSHOT;
    private static final String RESTORED_CLUSTER_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster:" + RESTORED_CLUSTER;

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @AfterEach
    void cleanUp() {
        deleteSnapshot(COPY_SNAPSHOT);
        deleteSnapshot(SOURCE_SNAPSHOT);
        deleteCluster(RESTORED_CLUSTER);
        deleteCluster(SOURCE_CLUSTER);
    }

    @Test
    void clusterSnapshotLifecycleRoundTripsThroughTheQueryApi() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", SOURCE_CLUSTER)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("DatabaseName", "source_db")
                .when().post("/").then().statusCode(200);

        rds("CreateDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", SOURCE_SNAPSHOT)
                .formParam("DBClusterIdentifier", SOURCE_CLUSTER)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "platform")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterSnapshotIdentifier>" + SOURCE_SNAPSHOT
                        + "</DBClusterSnapshotIdentifier>"))
                .body(containsString("<Status>available</Status>"))
                .body(containsString("<SnapshotType>manual</SnapshotType>"))
                .body(containsString("<PercentProgress>100</PercentProgress>"));

        rds("AddTagsToResource")
                .formParam("ResourceName", SOURCE_SNAPSHOT_ARN)
                .formParam("Tags.Tag.1.Key", "environment")
                .formParam("Tags.Tag.1.Value", "test")
                .when().post("/").then().statusCode(200);

        rds("ListTagsForResource")
                .formParam("ResourceName", SOURCE_SNAPSHOT_ARN)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>owner</Key><Value>platform</Value>"))
                .body(containsString("<Key>environment</Key><Value>test</Value>"));

        rds("CopyDBClusterSnapshot")
                .formParam("SourceDBClusterSnapshotIdentifier", SOURCE_SNAPSHOT)
                .formParam("TargetDBClusterSnapshotIdentifier", COPY_SNAPSHOT)
                .formParam("CopyTags", "true")
                .formParam("Tags.Tag.1.Key", "Name")
                .formParam("Tags.Tag.1.Value", "copy")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterSnapshotIdentifier>" + COPY_SNAPSHOT
                        + "</DBClusterSnapshotIdentifier>"))
                .body(containsString("<SourceDBClusterSnapshotArn>" + SOURCE_SNAPSHOT_ARN
                        + "</SourceDBClusterSnapshotArn>"));

        rds("DescribeDBClusterSnapshots")
                .formParam("DBClusterSnapshotIdentifier", COPY_SNAPSHOT)
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterSnapshotIdentifier>" + COPY_SNAPSHOT
                        + "</DBClusterSnapshotIdentifier>"));

        rds("DescribeDBClusterSnapshots")
                .formParam("DBClusterIdentifier", SOURCE_CLUSTER)
                .formParam("SnapshotType", "manual")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterSnapshotIdentifier>" + COPY_SNAPSHOT
                        + "</DBClusterSnapshotIdentifier>"));

        rds("ModifyDBClusterSnapshotAttribute")
                .formParam("DBClusterSnapshotIdentifier", COPY_SNAPSHOT)
                .formParam("AttributeName", "restore")
                .formParam("ValuesToAdd.AttributeValue.1", "all")
                .when().post("/").then().statusCode(200)
                .body(containsString("<AttributeValue>all</AttributeValue>"));

        rds("DescribeDBClusterSnapshotAttributes")
                .formParam("DBClusterSnapshotIdentifier", COPY_SNAPSHOT)
                .when().post("/").then().statusCode(200)
                .body(containsString("<AttributeName>restore</AttributeName>"))
                .body(containsString("<AttributeValue>all</AttributeValue>"));

        rds("RestoreDBClusterFromSnapshot")
                .formParam("DBClusterIdentifier", RESTORED_CLUSTER)
                .formParam("SnapshotIdentifier", COPY_SNAPSHOT)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.4")
                .formParam("Port", "5432")
                .formParam("DatabaseName", "restored_db")
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-12345678")
                .formParam("EnableIAMDatabaseAuthentication", "true")
                .formParam("Tags.Tag.1.Key", "environment")
                .formParam("Tags.Tag.1.Value", "staging")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBClusterIdentifier>" + RESTORED_CLUSTER
                        + "</DBClusterIdentifier>"))
                .body(containsString("<EngineVersion>16.4</EngineVersion>"))
                .body(containsString("<DatabaseName>restored_db</DatabaseName>"))
                .body(containsString("<VpcSecurityGroupId>sg-12345678</VpcSecurityGroupId>"))
                .body(containsString("<IAMDatabaseAuthenticationEnabled>true"
                        + "</IAMDatabaseAuthenticationEnabled>"));

        rds("ListTagsForResource")
                .formParam("ResourceName", RESTORED_CLUSTER_ARN)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Key>environment</Key><Value>staging</Value>"));

        rds("DeleteDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", COPY_SNAPSHOT)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Status>deleted</Status>"));

        rds("DeleteDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", SOURCE_SNAPSHOT)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Status>deleted</Status>"));

        rds("DescribeDBClusterSnapshots")
                .formParam("DBClusterSnapshotIdentifier", SOURCE_SNAPSHOT)
                .when().post("/").then().statusCode(404)
                .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    private static void deleteSnapshot(String snapshotId) {
        rds("DeleteDBClusterSnapshot")
                .formParam("DBClusterSnapshotIdentifier", snapshotId)
                .when().post("/");
    }

    private static void deleteCluster(String clusterId) {
        rds("DeleteDBCluster")
                .formParam("DBClusterIdentifier", clusterId)
                .formParam("SkipFinalSnapshot", "true")
                .when().post("/");
    }
}
