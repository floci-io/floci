package io.github.hectorvent.floci.services.docdb;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The list form of DescribeDBClusters / DescribeDBInstances covers the whole RDS family.
 *
 * <p>A live account lists a DocumentDB cluster from {@code aws rds describe-db-clusters} and
 * {@code aws docdb describe-db-clusters} alike, with or without an {@code engine} filter; both
 * CLIs sign with the {@code rds} scope, and Floci accepts {@code docdb} as well.
 */
@QuarkusTest
@TestProfile(RdsFamilyListingIntegrationTest.NoContainersProfile.class)
class RdsFamilyListingIntegrationTest {

    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true",
                          "floci.services.docdb.mock", "true");
        }
    }

    private static final String AURORA = "family-aurora";
    private static final String DOCS = "family-docs";
    private static final String DOCS_INSTANCE = "family-docs-1";

    private static io.restassured.specification.RequestSpecification query(String scope, String region, String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/" + region + "/" + scope + "/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    private void createBoth() {
        query("rds", "us-east-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", AURORA)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/").then().statusCode(200);
        query("rds", "us-east-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", DOCS)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/").then().statusCode(200);
        query("rds", "us-east-1", "CreateDBInstance")
                .formParam("DBInstanceIdentifier", DOCS_INSTANCE)
                .formParam("DBInstanceClass", "db.t3.medium")
                .formParam("Engine", "docdb")
                .formParam("DBClusterIdentifier", DOCS)
        .when().post("/").then().statusCode(200);
    }

    @AfterEach
    void cleanUp() {
        query("rds", "us-east-1", "DeleteDBInstance").formParam("DBInstanceIdentifier", DOCS_INSTANCE)
                .when().post("/");
        query("rds", "us-east-1", "DeleteDBCluster").formParam("DBClusterIdentifier", DOCS)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        query("rds", "us-east-1", "DeleteDBCluster").formParam("DBClusterIdentifier", AURORA)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void listFormOnEitherScopeCoversBothStores() {
        createBoth();
        for (String scope : new String[] {"rds", "docdb"}) {
            query(scope, "us-east-1", "DescribeDBClusters")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DBClusterIdentifier>" + AURORA + "</DBClusterIdentifier>"))
                .body(containsString("<DBClusterIdentifier>" + DOCS + "</DBClusterIdentifier>"));
            query(scope, "us-east-1", "DescribeDBInstances")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DBInstanceIdentifier>" + DOCS_INSTANCE + "</DBInstanceIdentifier>"));
        }
    }

    @Test
    void engineFilterSelectsAcrossBothStores() {
        createBoth();
        query("docdb", "us-east-1", "DescribeDBClusters")
                .formParam("Filters.Filter.1.Name", "engine")
                .formParam("Filters.Filter.1.Values.Value.1", "docdb")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterIdentifier>" + DOCS + "</DBClusterIdentifier>"))
            .body(not(containsString("<DBClusterIdentifier>" + AURORA + "</DBClusterIdentifier>")));
        query("rds", "us-east-1", "DescribeDBClusters")
                .formParam("Filters.Filter.1.Name", "engine")
                .formParam("Filters.Filter.1.Values.Value.1", "aurora-postgresql")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterIdentifier>" + AURORA + "</DBClusterIdentifier>"))
            .body(not(containsString("<DBClusterIdentifier>" + DOCS + "</DBClusterIdentifier>")));
    }

    @Test
    void unknownEngineFilterIsRefusedOnEitherScope() {
        createBoth();
        for (String scope : new String[] {"rds", "docdb"}) {
            query(scope, "us-east-1", "DescribeDBClusters")
                    .formParam("Filters.Filter.1.Name", "engine")
                    .formParam("Filters.Filter.1.Values.Value.1", "nothing")
            .when().post("/")
            .then()
                .statusCode(400)
                .body(containsString("Unrecognized engine name: nothing"));
        }
    }

    @Test
    void auroraMemberInstancesAnswerToTheirAuroraEngineName() {
        createBoth();
        String member = "family-aurora-1";
        query("rds", "us-east-1", "CreateDBInstance")
                .formParam("DBInstanceIdentifier", member)
                .formParam("DBInstanceClass", "db.t3.medium")
                .formParam("Engine", "aurora-postgresql")
                .formParam("DBClusterIdentifier", AURORA)
        .when().post("/").then().statusCode(200)
            .body(containsString("<Engine>aurora-postgresql</Engine>"));
        try {
            query("rds", "us-east-1", "DescribeDBInstances")
                    .formParam("Filters.Filter.1.Name", "engine")
                    .formParam("Filters.Filter.1.Values.Value.1", "aurora-postgresql")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DBInstanceIdentifier>" + member + "</DBInstanceIdentifier>"))
                .body(not(containsString("<DBInstanceIdentifier>" + DOCS_INSTANCE + "</DBInstanceIdentifier>")));
            query("rds", "us-east-1", "DescribeDBInstances")
                    .formParam("Filters.Filter.1.Name", "engine")
                    .formParam("Filters.Filter.1.Values.Value.1", "postgres")
            .when().post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<DBInstanceIdentifier>" + member + "</DBInstanceIdentifier>")));
        } finally {
            query("rds", "us-east-1", "DeleteDBInstance").formParam("DBInstanceIdentifier", member)
                    .when().post("/");
        }
    }

    @Test
    void identifierFormStillFaultsForAnUnknownClusterOnEitherScope() {
        createBoth();
        for (String scope : new String[] {"rds", "docdb"}) {
            query(scope, "us-east-1", "DescribeDBClusters")
                    .formParam("DBClusterIdentifier", "family-absent")
            .when().post("/")
            .then()
                .statusCode(404)
                .body(containsString("DBClusterNotFoundFault"));
        }
    }

    @Test
    void bothHalvesOfTheListingFollowTheSignedRegionAwayFromTheDefault() {
        // both stores keyed by the region the request was signed for, not the configured default:
        // records created under eu-west-1 show up there and nowhere else
        String aurora = "family-west-aurora";
        String docs = "family-west-docs";
        query("rds", "eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", aurora)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/").then().statusCode(200);
        query("docdb", "eu-west-1", "CreateDBCluster")
                .formParam("DBClusterIdentifier", docs)
                .formParam("Engine", "docdb")
                .formParam("MasterUsername", "docdbadmin")
                .formParam("MasterUserPassword", "secret99password")
        .when().post("/").then().statusCode(200);
        try {
            for (String scope : new String[] {"rds", "docdb"}) {
                query(scope, "eu-west-1", "DescribeDBClusters")
                .when().post("/")
                .then()
                    .statusCode(200)
                    .body(containsString("<DBClusterIdentifier>" + aurora + "</DBClusterIdentifier>"))
                    .body(containsString("<DBClusterIdentifier>" + docs + "</DBClusterIdentifier>"));
                query(scope, "us-east-1", "DescribeDBClusters")
                .when().post("/")
                .then()
                    .statusCode(200)
                    .body(not(containsString("<DBClusterIdentifier>" + aurora + "</DBClusterIdentifier>")))
                    .body(not(containsString("<DBClusterIdentifier>" + docs + "</DBClusterIdentifier>")));
            }
            // an ARN filter naming the other region's cluster finds nothing either
            query("rds", "us-east-1", "DescribeDBClusters")
                    .formParam("Filters.Filter.1.Name", "db-cluster-id")
                    .formParam("Filters.Filter.1.Values.Value.1",
                            "arn:aws:rds:eu-west-1:000000000000:cluster:" + docs)
            .when().post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("<DBClusterIdentifier>" + docs + "</DBClusterIdentifier>")));
        } finally {
            query("rds", "eu-west-1", "DeleteDBCluster").formParam("DBClusterIdentifier", docs)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
            query("rds", "eu-west-1", "DeleteDBCluster").formParam("DBClusterIdentifier", aurora)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @Test
    void listingIsScopedToTheSignedRegion() {
        createBoth();
        query("rds", "eu-west-1", "DescribeDBClusters")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<DBClusterIdentifier>" + DOCS + "</DBClusterIdentifier>")))
            .body(not(containsString("<DBClusterIdentifier>" + AURORA + "</DBClusterIdentifier>")));
    }
}
