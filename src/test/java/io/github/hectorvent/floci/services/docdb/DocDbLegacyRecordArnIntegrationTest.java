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

/**
 * Records written before ARNs were stored still have to resolve to themselves.
 *
 * <p>A tag call is answered for the record its ARN names, which needs the record to have one. A
 * cluster persisted by an earlier Floci has none, so the comparison had nothing to compare and the
 * call resolved by identifier alone — the hole that check closes, left open for exactly the records
 * that predate it. The region such a record belongs to is the configured default, which is the
 * region it was in fact created under.
 */
@QuarkusTest
@TestProfile(DocDbLegacyRecordArnIntegrationTest.NoContainersProfile.class)
class DocDbLegacyRecordArnIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.docdb.mock", "true");
        }
    }

    @Inject
    DocDbService service;

    private static final String ID = "legacy-arnless-cluster";
    private static final String HOME_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:" + ID;
    private static final String FOREIGN_ARN = "arn:aws:rds:eu-west-1:000000000000:cluster:" + ID;

    private static io.restassured.specification.RequestSpecification docdb(String region, String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/" + region + "/docdb/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    /** A record as an earlier Floci wrote it: identifier and status, no ARN. */
    private void seedLegacyCluster() {
        service.createDbCluster(ID, "5.0.0", "admin", "secret99password", false);
        service.getDbCluster(ID).setDbClusterArn(null);
    }

    @Test
    void aLegacyRecordIsNotTaggedThroughAnotherRegionsArn() {
        seedLegacyCluster();

        docdb("eu-west-1", "AddTagsToResource")
                .formParam("ResourceName", FOREIGN_ARN)
                .formParam("Tags.Tag.1.Key", "reached")
                .formParam("Tags.Tag.1.Value", "wrong-region")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("DBClusterNotFoundFault"));

        docdb("eu-west-1", "ListTagsForResource")
                .formParam("ResourceName", FOREIGN_ARN)
        .when().post("/")
        .then().statusCode(404);

        // The record kept its own tags, and gained the ARN it should always have had — the
        // default region's, not the region of whoever happened to read it first.
        docdb("us-east-1", "ListTagsForResource")
                .formParam("ResourceName", HOME_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("wrong-region")));

        assertEquals(HOME_ARN, service.getDbCluster(ID).getDbClusterArn());

        service.deleteDbCluster(ID);
    }

    @Test
    void aLegacyRecordCanBeTaggedThroughItsOwnArn() {
        // The backfill has to make the record usable, not merely refuse the wrong ARN.
        seedLegacyCluster();

        docdb("us-east-1", "AddTagsToResource")
                .formParam("ResourceName", HOME_ARN)
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "upgraded")
        .when().post("/")
        .then().statusCode(200);

        docdb("us-east-1", "ListTagsForResource")
                .formParam("ResourceName", HOME_ARN)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Value>upgraded</Value>"));

        service.deleteDbCluster(ID);
    }
}
