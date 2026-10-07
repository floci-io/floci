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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Aurora custom endpoints over the Query protocol: create, describe alongside the built-in
 * writer and reader endpoints, modify, tag and delete, with the API reference's and the Aurora
 * user guide's rules on types, member lists, identifiers and the per-cluster limit.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsClusterEndpointIntegrationTest {

    private static final String CLUSTER = "cep-cluster";
    private static final String WRITER = "cep-writer";
    private static final String READER = "cep-reader";
    private static final String DESCRIBE_LIST =
            "DescribeDBClusterEndpointsResponse.DescribeDBClusterEndpointsResult.DBClusterEndpoints.DBClusterEndpointList";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261004/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static RequestSpecification createEndpoint(String id, String type) {
        return query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", id)
                .formParam("EndpointType", type);
    }

    @Test
    @Order(1)
    void createsACustomEndpointOnAClusterWithAWriterAndAReader() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Engine", "aurora-postgresql")
                .formParam("EngineVersion", "16.3")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
        .when().post("/").then().statusCode(200);
        for (String instance : new String[] {WRITER, READER}) {
            query("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instance)
                    .formParam("DBClusterIdentifier", CLUSTER)
                    .formParam("Engine", "aurora-postgresql")
                    .formParam("DBInstanceClass", "db.r6g.large")
            .when().post("/").then().statusCode(200);
        }

        // The CLI sends the type in lower case, and the identifier is stored lower case.
        createEndpoint("Reports-EP", "reader")
                .formParam("StaticMembers.member.1", READER)
                .formParam("Tags.Tag.1.Key", "team")
                .formParam("Tags.Tag.1.Value", "analytics")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<CreateDBClusterEndpointResult>"))
                .body(containsString("<DBClusterEndpointIdentifier>reports-ep</DBClusterEndpointIdentifier>"))
                .body(containsString("<DBClusterIdentifier>" + CLUSTER + "</DBClusterIdentifier>"))
                .body(containsString("<DBClusterEndpointResourceIdentifier>cluster-endpoint-"))
                .body(containsString("<Endpoint>"))
                .body(containsString("<Status>available</Status>"))
                .body(containsString("<EndpointType>CUSTOM</EndpointType>"))
                .body(containsString("<CustomEndpointType>READER</CustomEndpointType>"))
                .body(containsString("<StaticMembers><member>" + READER + "</member></StaticMembers>"))
                .body(containsString("<ExcludedMembers></ExcludedMembers>"))
                .body(containsString(":cluster-endpoint:reports-ep</DBClusterEndpointArn>"));
    }

    @Test
    @Order(2)
    void describeListsTheBuiltInEndpointsWithTheCustomOneAndFilters() {
        query("DescribeDBClusterEndpoints").formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then()
                .statusCode(200)
                .body(DESCRIBE_LIST + ".size()", equalTo(3))
                .body(DESCRIBE_LIST + "[0].EndpointType", equalTo("WRITER"))
                .body(DESCRIBE_LIST + "[1].EndpointType", equalTo("READER"))
                .body(DESCRIBE_LIST + "[2].DBClusterEndpointIdentifier", equalTo("reports-ep"));

        query("DescribeDBClusterEndpoints")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Filters.Filter.1.Name", "db-cluster-endpoint-type")
                .formParam("Filters.Filter.1.Values.Value.1", "custom")
        .when().post("/").then().statusCode(200)
                .body(DESCRIBE_LIST + ".DBClusterEndpointIdentifier", equalTo("reports-ep"));

        query("DescribeDBClusterEndpoints")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("Filters.Filter.1.Name", "db-cluster-endpoint-custom-type")
                .formParam("Filters.Filter.1.Values.Value.1", "any")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<DBClusterEndpointList>")));

        query("DescribeDBClusterEndpoints").formParam("DBClusterEndpointIdentifier", "REPORTS-EP")
        .when().post("/").then().statusCode(200)
                .body(DESCRIBE_LIST + ".DBClusterEndpointIdentifier", equalTo("reports-ep"));

        query("DescribeDBClusterEndpoints").formParam("DBClusterIdentifier", "cep-missing")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterNotFoundFault</Code>"));
    }

    @Test
    @Order(3)
    void createRefusesWhatTheApiAndUserGuideRuleOut() {
        createEndpoint("reports-ep", "READER")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>DBClusterEndpointAlreadyExistsFault</Code>"));
        createEndpoint("writer-in-reader", "READER")
                .formParam("StaticMembers.member.1", WRITER)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        createEndpoint("both-lists", "ANY")
                .formParam("StaticMembers.member.1", READER)
                .formParam("ExcludedMembers.member.1", WRITER)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterCombination</Code>"));
        createEndpoint("stranger", "ANY")
                .formParam("StaticMembers.member.1", "cep-not-a-member")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBInstanceNotFound</Code>"));
        createEndpoint("writer-type", "WRITER")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        createEndpoint("-bad-name", "ANY")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));
        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", "cep-missing")
                .formParam("DBClusterEndpointIdentifier", "orphan")
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(404)
                .body(containsString("<Code>DBClusterNotFoundFault</Code>"));
    }

    @Test
    @Order(4)
    void aClusterHoldsAtMostFiveCustomEndpoints() {
        for (int i = 2; i <= 5; i++) {
            createEndpoint("extra-" + i, "ANY").when().post("/").then().statusCode(200);
        }
        createEndpoint("extra-6", "ANY")
        .when().post("/").then().statusCode(403)
                .body(containsString("<Code>DBClusterEndpointQuotaExceededFault</Code>"));
        for (int i = 2; i <= 5; i++) {
            query("DeleteDBClusterEndpoint").formParam("DBClusterEndpointIdentifier", "extra-" + i)
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(5)
    void modifySwitchesTheTypeAndListAndTagsFollowTheArn() {
        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", "reports-ep")
                .formParam("EndpointType", "ANY")
                .formParam("ExcludedMembers.member.1", READER)
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<ModifyDBClusterEndpointResult>"))
                .body(containsString("<CustomEndpointType>ANY</CustomEndpointType>"))
                .body(containsString("<StaticMembers></StaticMembers>"))
                .body(containsString("<ExcludedMembers><member>" + READER + "</member></ExcludedMembers>"));

        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", "cep-missing-ep")
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>DBClusterEndpointNotFoundFault</Code>"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster-endpoint:reports-ep")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Value>analytics</Value>"));
    }

    @Test
    @Order(6)
    void aDeletedInstanceLeavesTheListsAndDeleteRemovesTheEndpoint() {
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", READER)
        .when().post("/").then().statusCode(200);
        query("DescribeDBClusterEndpoints").formParam("DBClusterEndpointIdentifier", "reports-ep")
        .when().post("/").then().statusCode(200)
                .body(containsString("<ExcludedMembers></ExcludedMembers>"));

        query("DeleteDBClusterEndpoint").formParam("DBClusterEndpointIdentifier", "reports-ep")
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<DeleteDBClusterEndpointResult>"))
                .body(containsString("<Status>deleting</Status>"));
        query("DescribeDBClusterEndpoints").formParam("DBClusterEndpointIdentifier", "reports-ep")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<DBClusterEndpointList>")));
        query("DeleteDBClusterEndpoint").formParam("DBClusterEndpointIdentifier", "reports-ep")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>DBClusterEndpointNotFoundFault</Code>"));
    }

    @Test
    @Order(7)
    void deletingTheClusterDeletesItsCustomEndpoints() {
        createEndpoint("last-ep", "ANY").when().post("/").then().statusCode(200);
        query("DeleteDBInstance").formParam("DBInstanceIdentifier", WRITER)
        .when().post("/").then().statusCode(200);
        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
        query("DescribeDBClusterEndpoints").formParam("DBClusterEndpointIdentifier", "last-ep")
        .when().post("/").then().statusCode(200)
                .body(not(containsString("<DBClusterEndpointList>")));
    }
}
