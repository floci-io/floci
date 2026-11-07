package io.floci.aws.services.docdb;

import io.floci.aws.testing.DocDbMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Cluster snapshots and the two restores. Requests are signed for {@code rds}, as the AWS CLI and
 * SDKs sign DocumentDB calls, so they reach DocumentDB only through the routing that recognizes a
 * DocumentDB cluster or snapshot named in the request.
 */
@QuarkusTest
@TestProfile(DocDbMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocDbClusterSnapshotIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "snap-source-cluster";
    private static final String SNAPSHOT_ID = "snap-source-cluster-manual";
    private static final String RESTORED_ID = "snap-restored-cluster";
    private static final String POINT_IN_TIME_ID = "snap-point-in-time-cluster";
    private static final String LISTED_SNAPSHOT_ID = "snap-source-cluster-listed";
    private static final String ARN_RESTORED_ID = "snap-arn-restored-cluster";
    private static final String MASTER_USERNAME = "docdbadmin";
    private static final String MASTER_PASSWORD = "secret99password";

    private static final String AUTH_RDS_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static String listedSnapshotArn;

    @Test
    @Order(1)
    void createSourceCluster() {
        request("CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "docdb")
            .formParam("MasterUsername", MASTER_USERNAME)
            .formParam("MasterUserPassword", MASTER_PASSWORD)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(2)
    void createSnapshotIsAvailableAtOnce() {
        request("CreateDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", SNAPSHOT_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CreateDBClusterSnapshotResponse"))
            .body(containsString("<DBClusterSnapshotIdentifier>" + SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"))
            .body(containsString("<DBClusterIdentifier>" + CLUSTER_ID + "</DBClusterIdentifier>"))
            .body(containsString("<Engine>docdb</Engine>"))
            .body(containsString("<Status>available</Status>"))
            .body(containsString("<SnapshotType>manual</SnapshotType>"))
            .body(containsString(":cluster-snapshot:" + SNAPSHOT_ID))
            .body(not(containsString(MASTER_PASSWORD)));
    }

    @Test
    @Order(3)
    void createSnapshotWithATakenIdentifierFails() {
        request("CreateDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", SNAPSHOT_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterSnapshotAlreadyExistsFault"));
    }

    @Test
    @Order(4)
    void describeSnapshotsOfTheCluster() {
        request("DescribeDBClusterSnapshots")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterSnapshotIdentifier>" + SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"));
    }

    @Test
    @Order(5)
    void restoreFromSnapshotCreatesAClusterWithTheSnapshotsMasterUser() {
        request("RestoreDBClusterFromSnapshot")
            .formParam("DBClusterIdentifier", RESTORED_ID)
            .formParam("SnapshotIdentifier", SNAPSHOT_ID)
            .formParam("Engine", "docdb")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<RestoreDBClusterFromSnapshotResponse"))
            .body(containsString("<DBClusterIdentifier>" + RESTORED_ID + "</DBClusterIdentifier>"))
            .body(containsString("<MasterUsername>" + MASTER_USERNAME + "</MasterUsername>"));
    }

    @Test
    @Order(6)
    void restoreToPointInTimeCreatesAClusterFromTheSource() {
        request("RestoreDBClusterToPointInTime")
            .formParam("DBClusterIdentifier", POINT_IN_TIME_ID)
            .formParam("SourceDBClusterIdentifier", CLUSTER_ID)
            .formParam("UseLatestRestorableTime", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<RestoreDBClusterToPointInTimeResponse"))
            .body(containsString("<DBClusterIdentifier>" + POINT_IN_TIME_ID + "</DBClusterIdentifier>"))
            .body(containsString("<MasterUsername>" + MASTER_USERNAME + "</MasterUsername>"));
    }

    @Test
    @Order(7)
    void restoredClustersAreDocDbClusters() {
        request("DescribeDBClusters")
            .formParam("DBClusterIdentifier", RESTORED_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Engine>docdb</Engine>"));
    }

    @Test
    @Order(8)
    void deleteSnapshot() {
        request("DeleteDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", SNAPSHOT_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteDBClusterSnapshotResponse"))
            .body(containsString("<DBClusterSnapshotIdentifier>" + SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"));
    }

    @Test
    @Order(9)
    void aDeletedSnapshotIsGoneFromTheCluster() {
        request("DescribeDBClusterSnapshots")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(SNAPSHOT_ID)));
    }

    @Test
    @Order(10)
    void snapshotOfAnUnknownClusterIsNotFound() {
        docDbRequest("CreateDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", "orphan-snapshot")
            .formParam("DBClusterIdentifier", "no-such-cluster")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    @Order(11)
    void takeASnapshotForTheListingAndArnChecks() {
        String body = request("CreateDBClusterSnapshot")
            .formParam("DBClusterSnapshotIdentifier", LISTED_SNAPSHOT_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        listedSnapshotArn = body.substring(body.indexOf("<DBClusterSnapshotArn>") + "<DBClusterSnapshotArn>".length(),
                body.indexOf("</DBClusterSnapshotArn>"));
    }

    @Test
    @Order(12)
    void theRdsListFormIncludesDocDbSnapshots() {
        request("DescribeDBClusterSnapshots")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterSnapshotIdentifier>" + LISTED_SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"));
    }

    @Test
    @Order(13)
    void theDocDbListFormIncludesDocDbSnapshots() {
        docDbRequest("DescribeDBClusterSnapshots")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterSnapshotIdentifier>" + LISTED_SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"));
    }

    @Test
    @Order(14)
    void theDbClusterIdFilterNarrowsTheRdsListForm() {
        request("DescribeDBClusterSnapshots")
            .formParam("Filters.Filter.1.Name", "db-cluster-id")
            .formParam("Filters.Filter.1.Values.Value.1", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterSnapshotIdentifier>" + LISTED_SNAPSHOT_ID + "</DBClusterSnapshotIdentifier>"));
        request("DescribeDBClusterSnapshots")
            .formParam("Filters.Filter.1.Name", "db-cluster-id")
            .formParam("Filters.Filter.1.Values.Value.1", "another-cluster")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(LISTED_SNAPSHOT_ID)));
    }

    @Test
    @Order(15)
    void anIdentifierStillHonorsTheOtherFilters() {
        request("DescribeDBClusterSnapshots")
            .formParam("DBClusterSnapshotIdentifier", LISTED_SNAPSHOT_ID)
            .formParam("SnapshotType", "automated")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<DBClusterSnapshotIdentifier>")));
    }

    @Test
    @Order(16)
    void restoreFromSnapshotAcceptsTheSnapshotArn() {
        request("RestoreDBClusterFromSnapshot")
            .formParam("DBClusterIdentifier", ARN_RESTORED_ID)
            .formParam("SnapshotIdentifier", listedSnapshotArn)
            .formParam("Engine", "docdb")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterIdentifier>" + ARN_RESTORED_ID + "</DBClusterIdentifier>"));
    }

    @Test
    @Order(17)
    void aSnapshotArnOfAnotherRegionIsNotFound() {
        docDbRequest("RestoreDBClusterFromSnapshot")
            .formParam("DBClusterIdentifier", "arn-other-region-restore")
            .formParam("SnapshotIdentifier", listedSnapshotArn.replace(":us-east-1:", ":eu-west-1:"))
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterSnapshotNotFoundFault"));
    }

    @Test
    @Order(18)
    void aPointInTimeRestoreNeedsTheLatestRestorableTime() {
        request("RestoreDBClusterToPointInTime")
            .formParam("DBClusterIdentifier", "pitr-requested-time")
            .formParam("SourceDBClusterIdentifier", CLUSTER_ID)
            .formParam("RestoreToTime", "2026-11-01T00:00:00Z")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
        request("RestoreDBClusterToPointInTime")
            .formParam("DBClusterIdentifier", "pitr-no-time")
            .formParam("SourceDBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
        request("RestoreDBClusterToPointInTime")
            .formParam("DBClusterIdentifier", "pitr-both-times")
            .formParam("SourceDBClusterIdentifier", CLUSTER_ID)
            .formParam("RestoreToTime", "2026-11-01T00:00:00Z")
            .formParam("UseLatestRestorableTime", "true")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterCombination"));
    }

    private static RequestSpecification request(String action) {
        return given()
            .header("Authorization", AUTH_RDS_SCOPE)
            .contentType(FORM)
            .formParam("Action", action);
    }

    /** As {@link #request}, signed for the docdb endpoint, which dispatches to DocumentDB directly. */
    private static RequestSpecification docDbRequest(String action) {
        return given()
            .header("Authorization", AUTH_RDS_SCOPE.replace("/rds/", "/docdb/"))
            .contentType(FORM)
            .formParam("Action", action);
    }
}
