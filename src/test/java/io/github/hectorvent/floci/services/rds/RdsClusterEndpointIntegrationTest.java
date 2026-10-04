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
import static org.hamcrest.Matchers.not;

/**
 * Aurora custom cluster endpoints over the Query protocol: create with tags, describe next to the
 * built-in WRITER and READER endpoints, modify, tag through the endpoint ARN, the faults, and
 * the cleanup that follows a deleted instance and a deleted cluster.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsClusterEndpointIntegrationTest {

    private static final String CLUSTER = "cep-cluster";
    private static final String WRITER = "cep-writer";
    private static final String READER = "cep-reader";
    private static final String ENDPOINT = "cep-analytics";
    private static final String ENDPOINT_ARN = "arn:aws:rds:us-east-1:000000000000:cluster-endpoint:" + ENDPOINT;

    private static RequestSpecification query(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void createCustomEndpointOnAClusterWithTwoInstances() {
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

        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", "CEP-Analytics")
                .formParam("EndpointType", "reader")
                .formParam("StaticMembers.member.1", READER)
                .formParam("Tags.Tag.1.Key", "team")
                .formParam("Tags.Tag.1.Value", "data")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<CreateDBClusterEndpointResult>"))
            .body(containsString("<DBClusterEndpointIdentifier>" + ENDPOINT + "</DBClusterEndpointIdentifier>"))
            .body(containsString("<DBClusterIdentifier>" + CLUSTER + "</DBClusterIdentifier>"))
            .body(containsString("<Status>creating</Status>"))
            .body(containsString("<EndpointType>CUSTOM</EndpointType>"))
            .body(containsString("<CustomEndpointType>READER</CustomEndpointType>"))
            .body(containsString("<StaticMembers><member>" + READER + "</member></StaticMembers>"))
            .body(containsString("<ExcludedMembers></ExcludedMembers>"))
            .body(containsString("<DBClusterEndpointArn>" + ENDPOINT_ARN + "</DBClusterEndpointArn>"));
    }

    @Test
    @Order(2)
    void describeListsBuiltInAndCustomEndpoints() {
        query("DescribeDBClusterEndpoints")
                .formParam("DBClusterIdentifier", CLUSTER)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<EndpointType>WRITER</EndpointType>"))
            .body(containsString("<EndpointType>READER</EndpointType>"))
            .body(containsString("<EndpointType>CUSTOM</EndpointType>"))
            .body(containsString("<DBClusterEndpointIdentifier>" + ENDPOINT + "</DBClusterEndpointIdentifier>"))
            .body(containsString("<Status>available</Status>"));

        query("DescribeDBClusterEndpoints")
                .formParam("Filters.Filter.1.Name", "db-cluster-endpoint-type")
                .formParam("Filters.Filter.1.Values.Value.1", "custom")
                .formParam("Filters.Filter.2.Name", "db-cluster-endpoint-id")
                .formParam("Filters.Filter.2.Values.Value.1", ENDPOINT)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<DBClusterEndpointIdentifier>" + ENDPOINT + "</DBClusterEndpointIdentifier>"))
            .body(not(containsString("<EndpointType>WRITER</EndpointType>")));

        query("DescribeDBClusterEndpoints")
                .formParam("DBClusterIdentifier", "cep-missing")
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBClusterNotFoundFault</Code>"));
    }

    @Test
    @Order(3)
    void tagsRoundTripThroughTheEndpointArn() {
        query("ListTagsForResource").formParam("ResourceName", ENDPOINT_ARN)
        .when().post("/").then().statusCode(200)
            .body(containsString("<Key>team</Key>"))
            .body(containsString("<Value>data</Value>"));

        query("AddTagsToResource")
                .formParam("ResourceName", ENDPOINT_ARN)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "dev")
        .when().post("/").then().statusCode(200);
        query("RemoveTagsFromResource")
                .formParam("ResourceName", ENDPOINT_ARN)
                .formParam("TagKeys.member.1", "team")
        .when().post("/").then().statusCode(200);

        query("ListTagsForResource").formParam("ResourceName", ENDPOINT_ARN)
        .when().post("/").then().statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(not(containsString("<Key>team</Key>")));
    }

    @Test
    @Order(4)
    void faultsCarryTheDocumentedCodesAndStatuses() {
        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>DBClusterEndpointAlreadyExistsFault</Code>"));

        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", "cep-both")
                .formParam("EndpointType", "ANY")
                .formParam("StaticMembers.member.1", READER)
                .formParam("ExcludedMembers.member.1", WRITER)
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));

        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", "cep-ghost")
                .formParam("EndpointType", "ANY")
                .formParam("StaticMembers.member.1", "cep-ghost-instance")
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBInstanceNotFound</Code>"));

        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", "cep-missing")
                .formParam("DBClusterEndpointIdentifier", "cep-orphan")
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBClusterNotFoundFault</Code>"));

        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", "cep-missing")
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBClusterEndpointNotFoundFault</Code>"));

        for (int i = 1; i <= 4; i++) {
            query("CreateDBClusterEndpoint")
                    .formParam("DBClusterIdentifier", CLUSTER)
                    .formParam("DBClusterEndpointIdentifier", "cep-fill-" + i)
                    .formParam("EndpointType", "ANY")
            .when().post("/").then().statusCode(200);
        }
        query("CreateDBClusterEndpoint")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("DBClusterEndpointIdentifier", "cep-sixth")
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(403)
            .body(containsString("<Code>DBClusterEndpointQuotaExceededFault</Code>"));
    }

    @Test
    @Order(5)
    void modifySwitchesTheMemberList() {
        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("EndpointType", "ANY")
                .formParam("ExcludedMembers.member.1", WRITER)
        .when().post("/").then().statusCode(200)
            .body(containsString("<ModifyDBClusterEndpointResult>"))
            .body(containsString("<Status>modifying</Status>"))
            .body(containsString("<CustomEndpointType>ANY</CustomEndpointType>"))
            .body(containsString("<StaticMembers></StaticMembers>"))
            .body(containsString("<ExcludedMembers><member>" + WRITER + "</member></ExcludedMembers>"));
    }

    @Test
    @Order(6)
    void bareEmptyMemberListClearsItAndAbsentOneKeepsIt() {
        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("StaticMembers.member.1", READER)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StaticMembers><member>" + READER + "</member></StaticMembers>"));

        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("EndpointType", "ANY")
        .when().post("/").then().statusCode(200)
            .body(containsString("<StaticMembers><member>" + READER + "</member></StaticMembers>"));

        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("StaticMembers", "")
        .when().post("/").then().statusCode(200)
            .body(containsString("<StaticMembers></StaticMembers>"))
            .body(containsString("<ExcludedMembers></ExcludedMembers>"));

        query("ModifyDBClusterEndpoint")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
                .formParam("ExcludedMembers.member.1", WRITER)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(7)
    void deletedInstanceLeavesTheListsAndDeletedClusterTakesEndpointsAlong() {
        query("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", WRITER)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
        query("DescribeDBClusterEndpoints")
                .formParam("DBClusterEndpointIdentifier", ENDPOINT)
        .when().post("/").then().statusCode(200)
            .body(containsString("<ExcludedMembers></ExcludedMembers>"));

        query("DeleteDBClusterEndpoint").formParam("DBClusterEndpointIdentifier", "cep-fill-1")
        .when().post("/").then().statusCode(200)
            .body(containsString("<Status>deleting</Status>"));

        query("DeleteDBInstance")
                .formParam("DBInstanceIdentifier", READER)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        query("DeleteDBClusterEndpoint").formParam("DBClusterEndpointIdentifier", ENDPOINT)
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBClusterEndpointNotFoundFault</Code>"));
        query("ListTagsForResource").formParam("ResourceName", ENDPOINT_ARN)
        .when().post("/").then().statusCode(404)
            .body(containsString("<Code>DBClusterEndpointNotFoundFault</Code>"));
    }
}
