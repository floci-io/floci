package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Tags on DB parameter groups and DB cluster parameter groups.
 *
 * <p>Both types are taggable on a live account, and both are read by the terraform provider as
 * part of the group's own read — including for DocumentDB, whose cluster parameter groups are
 * these records. The ARN is what a caller tags by, so the create response has to carry it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsParameterGroupTagIntegrationTest {

    private static final String PG = "tag-test-pg";
    private static final String CLUSTER_PG = "tag-test-cluster-pg";
    private static final String PG_ARN = "arn:aws:rds:us-east-1:000000000000:pg:" + PG;
    private static final String CLUSTER_PG_ARN =
            "arn:aws:rds:us-east-1:000000000000:cluster-pg:" + CLUSTER_PG;

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void createReportsTheArnAndKeepsTheTagsItWasGiven() {
        query("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("DBParameterGroupFamily", "postgres15")
                .formParam("Description", "tag test")
                .formParam("Tags.Tag.1.Key", "team")
                .formParam("Tags.Tag.1.Value", "data")
        .when().post("/")
        .then()
            .statusCode(200)
            // Without the ARN in the response a caller has nothing to tag by.
            .body(containsString("<DBParameterGroupArn>" + PG_ARN + "</DBParameterGroupArn>"));

        query("ListTagsForResource")
                .formParam("ResourceName", PG_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>team</Key>"))
            .body(containsString("<Value>data</Value>"));
    }

    @Test
    @Order(1)
    void aClusterParameterGroupIsTaggableTheSameWay() {
        query("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("DBParameterGroupFamily", "docdb5.0")
                .formParam("Description", "tag test")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "test")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterParameterGroupArn>" + CLUSTER_PG_ARN
                    + "</DBClusterParameterGroupArn>"));

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_PG_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>test</Value>"));
    }

    @Test
    @Order(2)
    void tagsCanBeAddedAndRemovedAfterwards() {
        query("AddTagsToResource")
                .formParam("ResourceName", CLUSTER_PG_ARN)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "changed")
                .formParam("Tags.Tag.2.Key", "novalue")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_PG_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>changed</Value>"))
            // A key given without a value reads back empty, as it does on a live account.
            .body(containsString("<Key>novalue</Key><Value></Value>"));

        query("RemoveTagsFromResource")
                .formParam("ResourceName", CLUSTER_PG_ARN)
                .formParam("TagKeys.member.1", "novalue")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", CLUSTER_PG_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Key>novalue</Key>")))
            .body(containsString("<Key>env</Key>"));
    }

    @Test
    @Order(3)
    void aGroupThatDoesNotExistIsReportedAsMissing() {
        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster-pg:no-such-pg")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("NotFound"));
    }

    @Test
    @Order(4)
    void cleanUp() {
        query("DeleteDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
        .when().post("/").then().statusCode(200);

        query("DeleteDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
        .when().post("/").then().statusCode(200);
    }
}
