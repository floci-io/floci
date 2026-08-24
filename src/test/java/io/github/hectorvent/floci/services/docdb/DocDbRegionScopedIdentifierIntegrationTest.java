package io.github.hectorvent.floci.services.docdb;

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
 * One identifier per region, as AWS scopes them.
 *
 * <p>Checked against a live account: the same name creates in two regions, each with its own ARN,
 * and tagging one leaves the other untouched. Floci refused the second create, because its records
 * were keyed by identifier alone — so the name a caller used in one region was spent everywhere.
 */
@QuarkusTest
@TestProfile(DocDbRegionScopedIdentifierIntegrationTest.NoContainersProfile.class)
class DocDbRegionScopedIdentifierIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.docdb.mock", "true");
        }
    }

    @Inject
    DocDbService service;

    private static final String ID = "shared-across-regions";

    private static io.restassured.specification.RequestSpecification docdb(String region, String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/" + region + "/docdb/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private static void createCluster(String region, String tagValue) {
        docdb(region, "CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
                .formParam("Tags.Tag.1.Key", "where")
                .formParam("Tags.Tag.1.Value", tagValue)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("arn:aws:rds:" + region + ":000000000000:cluster:" + ID));
    }

    @Test
    void oneNameCreatesInEveryRegionAndEachCarriesItsOwnState() {
        createCluster("us-east-1", "east");
        createCluster("eu-west-1", "west");

        // Each region describes its own record and not the other's.
        for (String region : new String[]{"us-east-1", "eu-west-1"}) {
            docdb(region, "DescribeDBClusters")
                    .formParam("DBClusterIdentifier", ID)
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("arn:aws:rds:" + region + ":000000000000:cluster:" + ID));
        }

        // Tags belong to the record, not to the name.
        docdb("us-east-1", "ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:" + ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>east</Value>"))
            .body(not(containsString("<Value>west</Value>")));

        // A third region has neither.
        docdb("ap-south-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then().statusCode(404);

        // Deleting one leaves the other alone.
        docdb("us-east-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);

        docdb("us-east-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", ID)
        .when().post("/").then().statusCode(404);

        docdb("eu-west-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("arn:aws:rds:eu-west-1:000000000000:cluster:" + ID));

        // Its tags survived the other region's delete too.
        docdb("eu-west-1", "ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:eu-west-1:000000000000:cluster:" + ID)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>west</Value>"));

        docdb("eu-west-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }

    @Test
    void instancesAreScopedTheSameWay() {
        createCluster("us-east-1", "east");
        createCluster("eu-west-1", "west");
        String instanceId = "shared-instance";

        for (String region : new String[]{"us-east-1", "eu-west-1"}) {
            docdb(region, "CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBClusterIdentifier", ID)
                    .formParam("Engine", "docdb")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("arn:aws:rds:" + region + ":000000000000:db:" + instanceId));
        }

        // Each cluster lists only the instance created against it.
        for (String region : new String[]{"us-east-1", "eu-west-1"}) {
            docdb(region, "DescribeDBInstances")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("arn:aws:rds:" + region + ":000000000000:db:" + instanceId));

            docdb(region, "DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
            .when().post("/").then().statusCode(200);

            docdb(region, "DeleteDBCluster")
                    .formParam("DBClusterIdentifier", ID)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void aRecordWrittenBeforeRegionsWereInTheKeyBelongsToTheDefaultRegion() {
        // Written the way an earlier Floci wrote it: the service call runs outside a request, so
        // it lands under the default region — which is where such a record belongs.
        String legacyId = "pre-rekey-cluster";
        service.createDbCluster(legacyId, "5.0.0", "admin", "secret99password", false);

        docdb("us-east-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", legacyId)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString(legacyId));

        // And it is not visible from anywhere else, nor does it block that name there.
        docdb("eu-west-1", "DescribeDBClusters")
                .formParam("DBClusterIdentifier", legacyId)
        .when().post("/").then().statusCode(404);

        docdb("eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", legacyId)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("arn:aws:rds:eu-west-1:000000000000:cluster:" + legacyId));

        assertEquals("arn:aws:rds:us-east-1:000000000000:cluster:" + legacyId,
                service.getDbCluster(legacyId).getDbClusterArn());
        assertTrue(service.hasCluster(legacyId, "us-east-1"));
        assertTrue(service.hasCluster(legacyId, "eu-west-1"));

        docdb("eu-west-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", legacyId)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
        service.deleteDbCluster(legacyId);
    }

    @Test
    void anInstanceCannotAttachToAClusterInAnotherRegion() {
        // The reference has to resolve in the region the create is made from — a cluster that
        // exists only elsewhere is not there to attach to.
        createCluster("eu-west-1", "west");

        docdb("us-east-1", "CreateDBInstance")
                .formParam("DBInstanceIdentifier", "orphan-instance")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "docdb")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));

        docdb("eu-west-1", "DeleteDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true")
        .when().post("/").then().statusCode(200);
    }
}
