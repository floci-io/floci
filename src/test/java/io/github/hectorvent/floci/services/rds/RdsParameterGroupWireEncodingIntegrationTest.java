package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** The provider skips parameters without Source and hashes ApplyMethod, so both must round-trip. */
    private static void assertSourceAndApplyMethod(String describeAction, String nameKey, String group,
                                                   String immediateParam, String pendingParam) {
        XmlPath xml = query(describeAction).formParam(nameKey, group)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        for (String[] p : new String[][] {{immediateParam, "immediate"}, {pendingParam, "pending-reboot"}}) {
            String find = "**.find { it.ParameterName == '" + p[0] + "' }.";
            assertEquals("user", xml.getString(find + "Source"), p[0]);
            assertEquals(p[1], xml.getString(find + "ApplyMethod"), p[0]);
        }
    }

    @Test
    @Order(3)
    void describeDbParametersReturnsSourceAndApplyMethod() {
        assertSourceAndApplyMethod("DescribeDBParameters", "DBParameterGroupName", PG,
                "max_connections", "work_mem");
    }

    @Test
    @Order(4)
    void describeDbClusterParametersReturnsSourceAndApplyMethod() {
        query("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("Parameters.Parameter.1.ParameterName", "shared_preload_libraries")
                .formParam("Parameters.Parameter.1.ParameterValue", "pg_stat_statements")
                .formParam("Parameters.Parameter.1.ApplyMethod", "pending-reboot")
        .when().post("/").then().statusCode(200);
        assertSourceAndApplyMethod("DescribeDBClusterParameters", "DBClusterParameterGroupName", CLUSTER_PG,
                "rds.force_ssl", "shared_preload_libraries");
    }

    private static String applyMethodOf(String describeAction, String nameKey, String group, String param) {
        return query(describeAction).formParam(nameKey, group)
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("**.find { it.ParameterName == '" + param + "' }.ApplyMethod");
    }

    /**
     * Copy carries a pending-reboot ApplyMethod to the copy; a selected reset and ResetAllParameters
     * each leave no stale method behind, so a re-modify without ApplyMethod reads back immediate.
     * Everything is read back through Describe, not the mutating call's own response.
     */
    private static void assertApplyMethodLifecycle(String kind, String family, String param, String value) {
        String groupKey = "DB" + kind + "ParameterGroupName";
        String describe = "DescribeDB" + kind + "Parameters";
        String src = "lifecycle-src" + kind.toLowerCase();
        String copy = "lifecycle-copy" + kind.toLowerCase();
        query("CreateDB" + kind + "ParameterGroup").formParam(groupKey, src)
                .formParam("DBParameterGroupFamily", family).formParam("Description", "lifecycle")
                .when().post("/").then().statusCode(200);
        query("ModifyDB" + kind + "ParameterGroup").formParam(groupKey, src)
                .formParam("Parameters.Parameter.1.ParameterName", param)
                .formParam("Parameters.Parameter.1.ParameterValue", value)
                .formParam("Parameters.Parameter.1.ApplyMethod", "pending-reboot")
                .when().post("/").then().statusCode(200);

        query("CopyDB" + kind + "ParameterGroup")
                .formParam("SourceDB" + kind + "ParameterGroupIdentifier", src)
                .formParam("TargetDB" + kind + "ParameterGroupIdentifier", copy)
                .formParam("TargetDB" + kind + "ParameterGroupDescription", "lifecycle copy")
                .when().post("/").then().statusCode(200);
        assertEquals("pending-reboot", applyMethodOf(describe, groupKey, copy, param), "copy");

        // Selected reset, then re-modify with no ApplyMethod.
        query("ResetDB" + kind + "ParameterGroup").formParam(groupKey, copy)
                .formParam("Parameters.Parameter.1.ParameterName", param)
                .formParam("Parameters.Parameter.1.ApplyMethod", "pending-reboot")
                .when().post("/").then().statusCode(200);
        query("ModifyDB" + kind + "ParameterGroup").formParam(groupKey, copy)
                .formParam("Parameters.Parameter.1.ParameterName", param)
                .formParam("Parameters.Parameter.1.ParameterValue", value)
                .when().post("/").then().statusCode(200);
        assertEquals("immediate", applyMethodOf(describe, groupKey, copy, param), "after selected reset");

        // ResetAllParameters from a pending-reboot state, then re-modify with no ApplyMethod.
        query("ModifyDB" + kind + "ParameterGroup").formParam(groupKey, copy)
                .formParam("Parameters.Parameter.1.ParameterName", param)
                .formParam("Parameters.Parameter.1.ParameterValue", value)
                .formParam("Parameters.Parameter.1.ApplyMethod", "pending-reboot")
                .when().post("/").then().statusCode(200);
        query("ResetDB" + kind + "ParameterGroup").formParam(groupKey, copy)
                .formParam("ResetAllParameters", "true")
                .when().post("/").then().statusCode(200);
        query("ModifyDB" + kind + "ParameterGroup").formParam(groupKey, copy)
                .formParam("Parameters.Parameter.1.ParameterName", param)
                .formParam("Parameters.Parameter.1.ParameterValue", value)
                .when().post("/").then().statusCode(200);
        assertEquals("immediate", applyMethodOf(describe, groupKey, copy, param), "after reset all");

        for (String g : new String[] {src, copy}) {
            query("DeleteDB" + kind + "ParameterGroup").formParam(groupKey, g).when().post("/");
        }
    }

    @Test
    @Order(5)
    void dbParameterGroupApplyMethodSurvivesCopyAndIsClearedByReset() {
        assertApplyMethodLifecycle("", "postgres15", "work_mem", "65536");
    }

    @Test
    @Order(6)
    void dbClusterParameterGroupApplyMethodSurvivesCopyAndIsClearedByReset() {
        assertApplyMethodLifecycle("Cluster", "aurora-postgresql15", "shared_preload_libraries", "pg_stat_statements");
    }

    /**
     * The RDS model's ApplyMethod enum is {@code immediate | pending-reboot}; anything else, including
     * a different case, is refused with InvalidParameterValue and leaves the stored parameter untouched.
     */
    private static void assertInvalidApplyMethodRejected(String kind, String group, String prefix,
                                                         String param, String storedValue, String storedMethod) {
        String groupKey = "DB" + kind + "ParameterGroupName";
        String describe = "DescribeDB" + kind + "Parameters";
        for (String bad : new String[] {"later", "Immediate", "PENDING-REBOOT", ""}) {
            query("ModifyDB" + kind + "ParameterGroup").formParam(groupKey, group)
                    .formParam(prefix + ".1.ParameterName", param)
                    .formParam(prefix + ".1.ParameterValue", "999")
                    .formParam(prefix + ".1.ApplyMethod", bad)
            .when().post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        }
        XmlPath xml = query(describe).formParam(groupKey, group)
                .when().post("/").then().statusCode(200).extract().xmlPath();
        String find = "**.find { it.ParameterName == '" + param + "' }.";
        assertEquals(storedValue, xml.getString(find + "ParameterValue"), param);
        assertEquals(storedMethod, xml.getString(find + "ApplyMethod"), param);
    }

    @Test
    @Order(7)
    void modifyDbParameterGroupRejectsUnknownApplyMethod() {
        assertInvalidApplyMethodRejected("", PG, "Parameters.Parameter", "work_mem", "65536", "pending-reboot");
    }

    @Test
    @Order(8)
    void modifyDbClusterParameterGroupRejectsUnknownApplyMethod() {
        assertInvalidApplyMethodRejected("Cluster", CLUSTER_PG, "Parameters.member", "rds.force_ssl", "1", "immediate");
    }

    @Test
    @Order(9)
    void cleanUp() {
        query("DeleteDBParameterGroup").formParam("DBParameterGroupName", PG).when().post("/");
        query("DeleteDBClusterParameterGroup").formParam("DBClusterParameterGroupName", CLUSTER_PG).when().post("/");
    }
}
