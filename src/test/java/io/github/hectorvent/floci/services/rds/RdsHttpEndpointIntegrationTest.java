package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * The Data API switch: {@code EnableHttpEndpoint} and {@code DisableHttpEndpoint} by cluster ARN,
 * {@code EnableHttpEndpoint} on {@code CreateDBCluster} and {@code ModifyDBCluster}, and
 * {@code HttpEndpointEnabled} on {@code DBCluster}.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsHttpEndpointIntegrationTest {

    private static final String CLUSTER = "data-api-cluster";
    private static final String ARN = "arn:aws:rds:us-east-1:000000000000:cluster:" + CLUSTER;
    private static final String DESCRIBED =
            "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.HttpEndpointEnabled";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification createCluster(String id, String engine) {
        return query("CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", engine)
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123");
    }

    private static void expectFlag(String clusterId, String value) {
        query("DescribeDBClusters").formParam("DBClusterIdentifier", clusterId)
        .when().post("/").then().statusCode(200)
                .body(DESCRIBED, equalTo(value));
    }

    @Test
    @Order(1)
    void aNewClusterHasTheEndpointOff() {
        createCluster(CLUSTER, "aurora-postgresql")
        .when().post("/").then().statusCode(200)
                .body(containsString("<HttpEndpointEnabled>false</HttpEndpointEnabled>"));
    }

    @Test
    @Order(2)
    void enableAndDisableByClusterArn() {
        query("EnableHttpEndpoint").formParam("ResourceArn", ARN)
        .when().post("/").then().statusCode(200)
                .body("EnableHttpEndpointResponse.EnableHttpEndpointResult.ResourceArn", equalTo(ARN))
                .body("EnableHttpEndpointResponse.EnableHttpEndpointResult.HttpEndpointEnabled", equalTo("true"));
        expectFlag(CLUSTER, "true");

        query("DisableHttpEndpoint").formParam("ResourceArn", ARN)
        .when().post("/").then().statusCode(200)
                .body("DisableHttpEndpointResponse.DisableHttpEndpointResult.HttpEndpointEnabled", equalTo("false"));
        expectFlag(CLUSTER, "false");
    }

    @Test
    @Order(3)
    void createAndModifyTakeEnableHttpEndpoint() {
        createCluster("data-api-at-create", "aurora-mysql").formParam("EnableHttpEndpoint", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<HttpEndpointEnabled>true</HttpEndpointEnabled>"));
        expectFlag("data-api-at-create", "true");

        query("ModifyDBCluster").formParam("DBClusterIdentifier", "data-api-at-create")
                .formParam("EnableHttpEndpoint", "false")
        .when().post("/").then().statusCode(200);
        expectFlag("data-api-at-create", "false");
        // A modify that leaves the parameter out leaves the flag as it is.
        query("ModifyDBCluster").formParam("DBClusterIdentifier", CLUSTER)
                .formParam("EnableHttpEndpoint", "true")
        .when().post("/").then().statusCode(200);
        query("ModifyDBCluster").formParam("DBClusterIdentifier", CLUSTER)
                .formParam("EnableIAMDatabaseAuthentication", "true")
        .when().post("/").then().statusCode(200);
        expectFlag(CLUSTER, "true");
    }

    @Test
    @Order(4)
    void anArnThatNamesNoClusterHereIsNotFound() {
        for (String arn : new String[] {
                "arn:aws:rds:us-east-1:000000000000:cluster:no-such-cluster",
                "arn:aws:rds:us-east-1:000000000000:db:" + CLUSTER,
                "arn:aws:rds:eu-west-1:000000000000:cluster:" + CLUSTER,
                "arn:aws:rds:us-east-1:111111111111:cluster:" + CLUSTER,
                "not-an-arn"}) {
            query("EnableHttpEndpoint").formParam("ResourceArn", arn)
            .when().post("/").then().statusCode(404)
                    .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundFault"));
        }
        query("EnableHttpEndpoint")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(5)
    void aStoppedClusterIsInTheWrongState() {
        query("StopDBCluster").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200);
        query("DisableHttpEndpoint").formParam("ResourceArn", ARN)
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidResourceStateFault"));
        query("StartDBCluster").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(6)
    void onlyAuroraClustersTakeTheDataApi() {
        createCluster("data-api-postgres", "postgres").formParam("EnableHttpEndpoint", "true")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
        query("DescribeDBClusters").formParam("DBClusterIdentifier", "data-api-postgres")
        .when().post("/").then().statusCode(404);

        createCluster("data-api-postgres", "postgres")
        .when().post("/").then().statusCode(200);
        query("EnableHttpEndpoint")
                .formParam("ResourceArn", "arn:aws:rds:us-east-1:000000000000:cluster:data-api-postgres")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
        query("ModifyDBCluster").formParam("DBClusterIdentifier", "data-api-postgres")
                .formParam("EnableHttpEndpoint", "true")
        .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameterCombination"));
        // Turning it off is never refused.
        query("DisableHttpEndpoint")
                .formParam("ResourceArn", "arn:aws:rds:us-east-1:000000000000:cluster:data-api-postgres")
        .when().post("/").then().statusCode(200);
    }
}
