package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

/**
 * The RDS model declares the {@code Parameters} list with {@code locationName: Parameter}, so the
 * CLI and every SDK send {@code Parameters.Parameter.N.*}. A modify sent that way must land in
 * the group; the plain {@code Parameters.member.N.*} encoding stays accepted for callers that
 * build the query by hand.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsParameterGroupWireEncodingIntegrationTest {

    private static final String PG = "wire-encoding-pg";
    private static final String CLUSTER_PG = "wire-encoding-cluster-pg";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void modifyDbParameterGroupStoresTheParametersTheCliSends() {
        query("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("DBParameterGroupFamily", "postgres15")
                .formParam("Description", "wire encoding")
        .when().post("/").then().statusCode(200);

        // Exactly what `aws rds modify-db-parameter-group --parameters ...` puts on the wire.
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("Parameters.Parameter.1.ParameterName", "max_connections")
                .formParam("Parameters.Parameter.1.ParameterValue", "250")
                .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
                .formParam("Parameters.Parameter.2.ParameterName", "work_mem")
                .formParam("Parameters.Parameter.2.ParameterValue", "65536")
                .formParam("Parameters.Parameter.2.ApplyMethod", "pending-reboot")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBParameterGroupName>" + PG + "</DBParameterGroupName>"));

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>max_connections</ParameterName>"))
            .body(containsString("<ParameterValue>250</ParameterValue>"))
            .body(containsString("<ParameterName>work_mem</ParameterName>"))
            .body(containsString("<ParameterValue>65536</ParameterValue>"));

        // The plain Query encoding keeps working alongside it.
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("Parameters.member.1.ParameterName", "shared_buffers")
                .formParam("Parameters.member.1.ParameterValue", "4096")
        .when().post("/").then().statusCode(200);

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>shared_buffers</ParameterName>"))
            .body(containsString("<ParameterName>max_connections</ParameterName>"));
    }

    @Test
    @Order(2)
    void modifyDbClusterParameterGroupStoresTheParametersTheCliSends() {
        query("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("DBParameterGroupFamily", "aurora-postgresql15")
                .formParam("Description", "wire encoding")
        .when().post("/").then().statusCode(200);

        query("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("Parameters.Parameter.1.ParameterName", "rds.force_ssl")
                .formParam("Parameters.Parameter.1.ParameterValue", "1")
                .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterParameterGroupName>" + CLUSTER_PG + "</DBClusterParameterGroupName>"));

        query("DescribeDBClusterParameters")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>rds.force_ssl</ParameterName>"))
            .body(containsString("<ParameterValue>1</ParameterValue>"));
    }

    @Test
    @Order(9)
    void cleanUp() {
        query("DeleteDBParameterGroup").formParam("DBParameterGroupName", PG).when().post("/");
        query("DeleteDBClusterParameterGroup").formParam("DBClusterParameterGroupName", CLUSTER_PG).when().post("/");
    }
}
