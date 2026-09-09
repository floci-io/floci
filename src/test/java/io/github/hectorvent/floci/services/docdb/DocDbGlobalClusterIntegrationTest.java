package io.github.hectorvent.floci.services.docdb;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

/**
 * DescribeGlobalClusters on the path the DocumentDB SDK actually takes.
 *
 * <p>DocumentDB's signing name is {@code rds}, and this action carries no engine or cluster
 * identifier to route on, so the request reaches the RDS handler however the caller thinks of it.
 * The provider reads it as part of every {@code aws_docdb_cluster} read: without an answer the
 * cluster is created and then cannot be read back, which fails the apply after the fact.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocDbGlobalClusterIntegrationTest {

    private static final String CLUSTER_ID = "gc-read-back-cluster";

    /** What the DocumentDB SDK signs with. */
    private static final String RDS_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";
    /** The scope floci also accepts for DocumentDB. */
    private static final String DOCDB_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/docdb/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static io.restassured.specification.RequestSpecification query(String scope, String action) {
        return given().header("Authorization", scope)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void createTheClusterTheProviderWouldCreate() {
        query(RDS_SCOPE, "CreateDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER_ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID));
    }

    @Test
    @Order(2)
    void theReadThatFollowsTheCreateReportsNoGlobalClusters() {
        // No engine or cluster id on this request, so it lands on the RDS handler — the same
        // place the provider's call lands.
        query(RDS_SCOPE, "DescribeGlobalClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusters></GlobalClusters>"))
            .body(containsString("rds.amazonaws.com/doc/2014-10-31"));
    }

    @Test
    @Order(2)
    void theDocDbScopeAnswersTheSameWay() {
        query(DOCDB_SCOPE, "DescribeGlobalClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<GlobalClusters></GlobalClusters>"));

        query(DOCDB_SCOPE, "DescribeGlobalClusters")
                .formParam("GlobalClusterIdentifier", "no-such-gc")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("GlobalClusterNotFoundFault"))
            .body(containsString("Global cluster &apos;no-such-gc&apos; not found"));
    }

    @Test
    @Order(3)
    void theClusterIsStillReadableAfterwards() {
        query(RDS_SCOPE, "DescribeDBClusters")
                .formParam("DBClusterIdentifier", CLUSTER_ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(CLUSTER_ID))
            .body(containsString("docdb"));
    }

    @Test
    @Order(4)
    void cleanUp() {
        query(RDS_SCOPE, "DeleteDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER_ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/")
        .then()
            .statusCode(200);
    }
}
