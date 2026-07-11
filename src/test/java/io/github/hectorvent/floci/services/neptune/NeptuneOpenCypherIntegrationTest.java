package io.github.hectorvent.floci.services.neptune;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Demonstrates that a Neptune cluster configured with {@code db-type=neo4j} speaks the
 * openCypher query language over the Bolt protocol.
 *
 * <p>The default Gremlin backend ({@code tinkerpop/gremlin-server}) does NOT understand
 * openCypher/Bolt, so before {@code db-type} support existed this test could not pass —
 * the proxied port served a Gremlin WebSocket endpoint, not a Bolt endpoint.
 */
@QuarkusTest
@TestProfile(NeptuneOpenCypherIntegrationTest.Neo4jDbTypeProfile.class)
class NeptuneOpenCypherIntegrationTest {

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER_ID = "opencypher-cluster";

    // Fake SigV4 header whose credential scope service is "neptune" routes the management
    // request (CreateDBCluster/DeleteDBCluster) to the Neptune service.
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260516/us-east-1/neptune/aws4_request, " +
            "SignedHeaders=content-type;host, Signature=test";

    private static final Pattern PORT = Pattern.compile("<Port>(\\d+)</Port>");

    @Test
    void openCypherQueryWorksOverBolt() {
        String createResponse = given()
                .header("Authorization", AUTH)
                .contentType(FORM)
                .formParam("Action", "CreateDBCluster")
                .formParam("DBClusterIdentifier", CLUSTER_ID)
                .formParam("Engine", "neptune")
            .when().post("/")
            .then()
                .statusCode(200)
                .extract().asString();

        int boltPort = parsePort(createResponse);

        try (Driver driver = GraphDatabase.driver(
                "bolt://localhost:" + boltPort, AuthTokens.none())) {
            driver.verifyConnectivity();
            try (Session session = driver.session()) {
                long one = session.run("RETURN 1 AS n").single().get("n").asLong();
                assertEquals(1L, one, "openCypher RETURN should evaluate on the neo4j backend");

                session.run("CREATE (:Person {name: 'Alice'})").consume();
                long count = session.run("MATCH (p:Person) RETURN count(p) AS c")
                        .single().get("c").asLong();
                assertEquals(1L, count, "openCypher CREATE + MATCH should round-trip");
            }
        } finally {
            given()
                    .header("Authorization", AUTH)
                    .contentType(FORM)
                    .formParam("Action", "DeleteDBCluster")
                    .formParam("DBClusterIdentifier", CLUSTER_ID)
                .when().post("/")
                .then()
                    .statusCode(200);
        }
    }

    private static int parsePort(String xml) {
        Matcher m = PORT.matcher(xml);
        if (!m.find()) {
            throw new AssertionError("No <Port> in CreateDBCluster response: " + xml);
        }
        return Integer.parseInt(m.group(1));
    }

    public static final class Neo4jDbTypeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.neptune.db-type", "neo4j");
        }
    }
}
