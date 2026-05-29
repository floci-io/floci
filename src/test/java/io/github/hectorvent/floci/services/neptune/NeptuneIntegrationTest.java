package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeptuneIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "my-neptune-cluster";
    private static final String INSTANCE_ID = "my-neptune-instance";

    // Fake SigV4 header — service name "neptune" is what AwsQueryController extracts
    // to route to NeptuneQueryHandler instead of RdsQueryHandler.
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void createCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "neptune")
            .formParam("EngineVersion", "1.3.2.1")
            .formParam("EnableIAMDatabaseAuthentication", "true")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("neptune"))
            .body(containsString("8182"))
            .body(containsString("true"));
    }

    @Test
    @Order(2)
    void createClusterDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "neptune")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterAlreadyExistsFault"));
    }

    @Test
    @Order(3)
    void describeClusters() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(4)
    void describeClusterById() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(5)
    void describeClusterNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", "nonexistent-cluster")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    @Order(6)
    void modifyCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "ModifyDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("EngineVersion", "1.3.3.0")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("1.3.3.0"));
    }

    @Test
    @Order(7)
    void createInstance() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBInstance")
            .formParam("DBInstanceIdentifier", INSTANCE_ID)
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("DBInstanceClass", "db.r5.large")
            .formParam("Engine", "neptune")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(INSTANCE_ID))
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("db.r5.large"))
            .body(containsString("8182"));
    }

    @Test
    @Order(8)
    void clusterHasInstanceMember() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(INSTANCE_ID));
    }

    @Test
    @Order(9)
    void describeInstances() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBInstances")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(INSTANCE_ID));
    }

    @Test
    @Order(10)
    void createInstanceRequiresCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBInstance")
            .formParam("DBInstanceIdentifier", "orphan-instance")
            .formParam("DBInstanceClass", "db.r5.large")
            .formParam("Engine", "neptune")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterIdentifier is required"));
    }

    @Test
    @Order(11)
    void deleteClusterWithInstanceFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidDBClusterStateFault"));
    }

    @Test
    @Order(12)
    void deleteInstance() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteDBInstance")
            .formParam("DBInstanceIdentifier", INSTANCE_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(INSTANCE_ID));
    }

    @Test
    @Order(13)
    void clusterHasNoMembersAfterInstanceDelete() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(INSTANCE_ID)));
    }

    @Test
    @Order(14)
    void deleteCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DeleteDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(15)
    void describeAfterDeleteReturnsEmpty() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));
    }

    @Test
    @Order(16)
    void unsupportedAction() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "RestoreDBClusterFromSnapshot")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("UnsupportedOperation"));
    }
}
