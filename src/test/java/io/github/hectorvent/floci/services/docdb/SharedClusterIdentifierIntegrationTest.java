package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.services.rds.RdsService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One identifier space covers the RDS family.
 *
 * <p>A live account refuses to create an Aurora cluster named like an existing DocumentDB one with
 * {@code DBClusterAlreadyExistsFault}, and the reverse has to be refused too: two clusters of that
 * name in different stores would share one ARN, and no tag call could say which was meant.
 */
@QuarkusTest
@TestProfile(SharedClusterIdentifierIntegrationTest.NoContainersProfile.class)
class SharedClusterIdentifierIntegrationTest {

    /** Neither engine's container is what this is about, and starting one costs the test 30s. */
    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true",
                          "floci.services.docdb.mock", "true");
        }
    }

    @Inject
    DocDbService docDbService;

    @Inject
    RdsService rdsService;

    private static final String ID = "shared-identifier";
    private static final String ARN = "arn:aws:rds:us-east-1:000000000000:cluster:" + ID;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return queryIn("us-east-1", action);
    }

    private static io.restassured.specification.RequestSpecification queryIn(String region, String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/" + region + "/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    void docDbCannotTakeAnIdentifierRdsAlreadyHolds() {
        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then().statusCode(200);

        query("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("AlreadyExists"));

        // The RDS cluster owns the ARN, and tagging reaches it rather than a DocumentDB record.
        query("AddTagsToResource")
                .formParam("ResourceName", ARN)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "rds")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>rds</Value>"));

        query("DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }

    @Test
    void aPersistedCollisionAnswersFromOneStoreForEveryAction() {
        // State written before creates shared one identifier space can still hold the name twice.
        // Both records are seeded through the services, as a restart would load them, since the
        // endpoint now refuses to create the second one.
        String id = "persisted-collision";
        String arn = "arn:aws:rds:us-east-1:000000000000:cluster:" + id;
        rdsService.createDbCluster(id, "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);
        docDbService.createDbCluster(id, "5.0.0", "docdbadmin", "secret99password", false);

        // Describe already answers from DocumentDB for such a record, and has since long before
        // tags existed. Tagging has to agree with it: one identifier, one answer.
        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("docdb"));

        query("AddTagsToResource")
                .formParam("ResourceName", arn)
                .formParam("Tags.Tag.1.Key", "answered-by")
                .formParam("Tags.Tag.1.Value", "docdb")
        .when().post("/")
        .then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", arn)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>docdb</Value>"));

        // The write went to the record that answers for the identifier, not to the other one.
        assertEquals(java.util.Map.of("answered-by", "docdb"),
                docDbService.listTagsForResource(arn));
        assertTrue(rdsService.listTagsForResource(arn, "us-east-1").isEmpty());

        docDbService.deleteDbCluster(id);
        rdsService.deleteDbCluster(id, "us-east-1");
    }

    @Test
    void anotherRegionIsAnotherResource() {
        // RDS scopes these names by region, so the same name elsewhere is a different resource —
        // the SDK compatibility suite pins that for instances. Since each ARN carries the region
        // it was created in, the two never share one ARN and no tag call has to choose.
        String id = "cross-region-identifier";
        rdsService.createDbCluster(id, "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);

        String docDbArn = queryIn("eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then().statusCode(200)
        .extract().path("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");

        assertEquals("arn:aws:rds:eu-west-1:000000000000:cluster:" + id, docDbArn);
        assertEquals("arn:aws:rds:us-east-1:000000000000:cluster:" + id,
                rdsService.getDbCluster(id, "us-east-1").getDbClusterArn());

        // Each ARN answers from its own service, so tagging one leaves the other alone.
        queryIn("eu-west-1", "AddTagsToResource")
                .formParam("ResourceName", docDbArn)
                .formParam("Tags.Tag.1.Key", "owner")
                .formParam("Tags.Tag.1.Value", "docdb")
        .when().post("/")
        .then().statusCode(200);

        // Read back over the endpoint: the service checks the ARN against the caller's region, and
        // a direct call from the test thread has no request to take one from.
        queryIn("eu-west-1", "ListTagsForResource")
                .formParam("ResourceName", docDbArn)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>docdb</Value>"));

        assertTrue(rdsService.listTagsForResource(
                "arn:aws:rds:us-east-1:000000000000:cluster:" + id, "us-east-1").isEmpty());

        docDbService.deleteDbCluster(id);
        rdsService.deleteDbCluster(id, "us-east-1");
    }

    @Test
    void aClusterCreatedOutsideTheDefaultRegionIsTaggableFromThere() {
        // The ARN a create answers with is the one the caller tags by, and a tag call is checked
        // against the region it is made from — an ARN naming the configured default region would
        // be one its own creator could not use.
        String id = "eu-west-cluster";
        String arn = queryIn("eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then().statusCode(200)
        .extract().path("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.DBClusterArn");

        assertTrue(arn.contains(":eu-west-1:"), "ARN should name the caller's region: " + arn);

        queryIn("eu-west-1", "AddTagsToResource")
                .formParam("ResourceName", arn)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "eu")
        .when().post("/")
        .then().statusCode(200);

        queryIn("eu-west-1", "ListTagsForResource")
                .formParam("ResourceName", arn)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>eu</Value>"));

        queryIn("eu-west-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }

    @Test
    void anRdsRequestIsNotAnsweredByADocumentDbClusterInAnotherRegion() {
        // DocumentDB records are keyed by identifier alone, so the region has to be read from the
        // record. Otherwise a name DocumentDB holds in one region is taken away from RDS in every
        // other region: the create is refused as existing, and describes answer the wrong record.
        String id = "region-routing-clash";

        queryIn("eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then().statusCode(200);

        // Same name, different region, ordinary RDS engine: RDS has to answer this.
        queryIn("us-east-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(200)
            // Floci reports the aurora-postgresql request as its postgres engine; what matters
            // here is that RDS answered at all rather than DocumentDB refusing it as existing.
            .body(containsString("<Engine>postgres</Engine>"));

        // And each region's describe answers from the service that owns the record there.
        queryIn("us-east-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Engine>postgres</Engine>"))
            .body(not(containsString("docdb")));

        queryIn("eu-west-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("docdb"));

        queryIn("eu-west-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", id)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
        rdsService.deleteDbCluster(id, "us-east-1");
    }

    @Test
    void theDocDbScopeIsCheckedTheSameWayAsTheRdsScope() {
        // DocumentDB is reachable under either credential scope. A create signed for the docdb
        // scope dispatches straight to its handler, which only knows its own store — so without
        // the same check there, this is the way to put two records under one ARN.
        String id = "docdb-scope-clash";
        rdsService.createDbCluster(id, "aurora-postgresql", null, "admin", "secret99password",
                null, false, null);

        given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/docdb/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", "CreateDBCluster")
                .formParam("Version", "2014-10-31")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("DBClusterAlreadyExistsFault"));

        // The RDS record is still the only one under that ARN, and still RDS's.
        query("DescribeDBClusters")
                .formParam("DBClusterIdentifier", id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Engine>postgres</Engine>"));

        rdsService.deleteDbCluster(id, "us-east-1");
    }

    @Test
    void theDocDbScopeCannotTagAnotherRegionsRecordThroughAMatchingArn() {
        // On the docdb scope the request goes straight to the service, skipping the routing that
        // matches the whole ARN. An ARN whose region matches the caller but names a record stored
        // in another region must not resolve: the identifier alone is not the resource.
        String id = "arn-identity-clash";
        String docdbAuth = "AWS4-HMAC-SHA256 Credential=test/20260615/%s/docdb/aws4_request, "
                + "SignedHeaders=content-type;host, Signature=test";

        given().header("Authorization", docdbAuth.formatted("us-east-1"))
                .contentType(URLENC)
                .formParam("Action", "CreateDBCluster")
                .formParam("Version", "2014-10-31")
                .formParam("DBClusterIdentifier", id)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("Tags.Tag.1.Key", "region")
                .formParam("Tags.Tag.1.Value", "us-east-1")
        .when().post("/")
        .then().statusCode(200);

        // Same identifier, eu-west-1 ARN, signed for eu-west-1: the region check passes, and only
        // comparing the stored ARN stops this reaching the us-east-1 record.
        given().header("Authorization", docdbAuth.formatted("eu-west-1"))
                .contentType(URLENC)
                .formParam("Action", "ListTagsForResource")
                .formParam("Version", "2014-10-31")
                .formParam("ResourceName", "arn:aws:rds:eu-west-1:000000000000:cluster:" + id)
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));

        given().header("Authorization", docdbAuth.formatted("eu-west-1"))
                .contentType(URLENC)
                .formParam("Action", "AddTagsToResource")
                .formParam("Version", "2014-10-31")
                .formParam("ResourceName", "arn:aws:rds:eu-west-1:000000000000:cluster:" + id)
                .formParam("Tags.Tag.1.Key", "region")
                .formParam("Tags.Tag.1.Value", "eu-west-1")
        .when().post("/")
        .then().statusCode(404);

        // The us-east-1 record still carries only its own tag.
        given().header("Authorization", docdbAuth.formatted("us-east-1"))
                .contentType(URLENC)
                .formParam("Action", "ListTagsForResource")
                .formParam("Version", "2014-10-31")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:" + id)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>us-east-1</Value>"))
            .body(not(containsString("<Value>eu-west-1</Value>")));

        given().header("Authorization", docdbAuth.formatted("us-east-1"))
                .contentType(URLENC)
                .formParam("Action", "DeleteDBCluster")
                .formParam("Version", "2014-10-31")
                .formParam("DBClusterIdentifier", id)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }
}
