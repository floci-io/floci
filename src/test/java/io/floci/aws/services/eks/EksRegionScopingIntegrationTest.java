package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Cluster names are Regional on AWS: a cluster created in one Region is not listed, described or
 * otherwise reachable from another Region of the same account.
 */
@QuarkusTest
class EksRegionScopingIntegrationTest {

    private static final String ACCOUNT = "135792468024";
    private static final String CLUSTER = "region-scoped";

    @Test
    void clusterIsOnlyVisibleInTheRegionItWasCreatedIn() {
        given().header("Authorization", auth("eu-west-1")).contentType("application/json")
                .body(Map.of("name", CLUSTER, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster"))
                .post("/clusters").then().statusCode(200)
                .body("cluster.arn", equalTo("arn:aws:eks:eu-west-1:" + ACCOUNT + ":cluster/" + CLUSTER));

        try {
            given().header("Authorization", auth("eu-west-1")).get("/clusters")
                    .then().statusCode(200).body("clusters", hasItem(CLUSTER));
            given().header("Authorization", auth("eu-west-1")).get("/clusters/" + CLUSTER)
                    .then().statusCode(200).body("cluster.name", equalTo(CLUSTER));

            given().header("Authorization", auth("eu-central-1")).get("/clusters")
                    .then().statusCode(200).body("clusters", not(hasItem(CLUSTER)));
            given().header("Authorization", auth("eu-central-1")).get("/clusters/" + CLUSTER)
                    .then().statusCode(404);
            given().header("Authorization", auth("eu-central-1")).get("/clusters/" + CLUSTER + "/node-groups")
                    .then().statusCode(404);
            given().header("Authorization", auth("eu-central-1")).delete("/clusters/" + CLUSTER)
                    .then().statusCode(404);
        } finally {
            given().header("Authorization", auth("eu-west-1")).delete("/clusters/" + CLUSTER)
                    .then().statusCode(200);
        }
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261110/" + region
                + "/eks/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
