package io.github.hectorvent.floci.services.docdb;

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
class DocDbIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "my-docdb-cluster";
    private static final String INSTANCE_ID = "my-docdb-instance";
    private static final String MASTER_USERNAME = "docdbadmin";
    private static final String MASTER_PASSWORD = "secret99password";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/docdb/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    private static final String AUTH_RDS_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    @Test
    @Order(1)
    void createCluster() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "docdb")
            .formParam("MasterUsername", MASTER_USERNAME)
            .formParam("MasterUserPassword", MASTER_PASSWORD)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("docdb"))
            .body(containsString("5.0.0"))
            .body(containsString(MASTER_USERNAME))
            .body(not(containsString(MASTER_PASSWORD)));
    }

    @Test
    @Order(2)
    void createClusterDuplicateFails() {
        given()
            .header("Authorization", AUTH)
            .contentType(FORM)
            .formParam("Action", "CreateDBCluster")
            .formParam("DBClusterIdentifier", CLUSTER_ID)
            .formParam("Engine", "docdb")
            .formParam("MasterUsername", MASTER_USERNAME)
            .formParam("MasterUserPassword", MASTER_PASSWORD)
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
            .body(containsString(CLUSTER_ID))
            .body(containsString("docdb"));
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
            .formParam("EngineVersion", "6.0.0")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("6.0.0"));
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
            .formParam("Engine", "docdb")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(INSTANCE_ID))
            .body(containsString(CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("db.r5.large"));
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
            .formParam("Engine", "docdb")
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
    void describeAfterDeleteReturnsNotFound() {
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

    private static final String RDS_SCOPE_CLUSTER_ID = "rds-scope-docdb-cluster";

    @Test
    @Order(17)
    void realSdkPathCreateRoutesToDocDbViaEngine() {
        given()
            .header("Authorization", AUTH_RDS_SCOPE)
            .contentType(FORM)
            .formParam("Action", "CreateDBCluster")
            .formParam("DBClusterIdentifier", RDS_SCOPE_CLUSTER_ID)
            .formParam("Engine", "docdb")
            .formParam("MasterUsername", MASTER_USERNAME)
            .formParam("MasterUserPassword", MASTER_PASSWORD)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(RDS_SCOPE_CLUSTER_ID))
            .body(containsString("available"))
            .body(containsString("docdb"));
    }

    @Test
    @Order(18)
    void realSdkPathDescribeByIdRoutesToDocDbViaExistingCluster() {
        given()
            .header("Authorization", AUTH_RDS_SCOPE)
            .contentType(FORM)
            .formParam("Action", "DescribeDBClusters")
            .formParam("DBClusterIdentifier", RDS_SCOPE_CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(RDS_SCOPE_CLUSTER_ID))
            .body(containsString("docdb"));
    }

    @Test
    @Order(19)
    void realSdkPathDeleteCleansUp() {
        given()
            .header("Authorization", AUTH_RDS_SCOPE)
            .contentType(FORM)
            .formParam("Action", "DeleteDBCluster")
            .formParam("DBClusterIdentifier", RDS_SCOPE_CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(RDS_SCOPE_CLUSTER_ID));
    }
}
