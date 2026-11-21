package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EksRequests.eks;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        eks(ACCOUNT, "eu-west-1")
                .body(Map.of("name", CLUSTER, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster"))
                .post("/clusters").then().statusCode(200)
                .body("cluster.arn", equalTo("arn:aws:eks:eu-west-1:" + ACCOUNT + ":cluster/" + CLUSTER));

        try {
            eks(ACCOUNT, "eu-west-1").get("/clusters")
                    .then().statusCode(200).body("clusters", hasItem(CLUSTER));
            eks(ACCOUNT, "eu-west-1").get("/clusters/" + CLUSTER)
                    .then().statusCode(200).body("cluster.name", equalTo(CLUSTER));

            eks(ACCOUNT, "eu-central-1").get("/clusters")
                    .then().statusCode(200).body("clusters", not(hasItem(CLUSTER)));
            eks(ACCOUNT, "eu-central-1").get("/clusters/" + CLUSTER)
                    .then().statusCode(404);
            eks(ACCOUNT, "eu-central-1").get("/clusters/" + CLUSTER + "/node-groups")
                    .then().statusCode(404);
            eks(ACCOUNT, "eu-central-1").delete("/clusters/" + CLUSTER)
                    .then().statusCode(404);
        } finally {
            eks(ACCOUNT, "eu-west-1").delete("/clusters/" + CLUSTER)
                    .then().statusCode(200);
        }
    }

    /** #7966: a name only has to be unique within a Region of the account. */
    @Test
    void theSameClusterNameCanBeUsedInTwoRegions() {
        String name = "region-shared";
        Map<String, String> issuers = new HashMap<>();
        for (String region : new String[] {"eu-west-1", "eu-central-1"}) {
            issuers.put(region, eks(ACCOUNT, region)
                    .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster"))
                    .post("/clusters").then().statusCode(200)
                    .body("cluster.arn", equalTo("arn:aws:eks:" + region + ":" + ACCOUNT + ":cluster/" + name))
                    .extract().path("cluster.identity.oidc.issuer"));
        }

        try {
            eks(ACCOUNT, "eu-west-1")
                    .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster"))
                    .post("/clusters").then().statusCode(409)
                    .body("__type", equalTo("ResourceInUseException"));

            eks(ACCOUNT, "eu-west-1")
                    .body(Map.of("nodegroupName", "workers",
                            "nodeRole", "arn:aws:iam::" + ACCOUNT + ":role/node",
                            "subnets", List.of("subnet-0123456789abcdef0")))
                    .post("/clusters/" + name + "/node-groups").then().statusCode(200);
            eks(ACCOUNT, "eu-west-1").get("/clusters/" + name + "/node-groups")
                    .then().statusCode(200).body("nodegroups", hasItem("workers"));
            eks(ACCOUNT, "eu-central-1").get("/clusters/" + name + "/node-groups")
                    .then().statusCode(200).body("nodegroups", empty());

            // OIDC discovery resolves the Region it is asked for, and advertises a key route that
            // names it, since a verifier follows that route without the discovery request's context.
            String westKeyId = null;
            for (String region : new String[] {"eu-west-1", "eu-central-1"}) {
                String jwksUri = eks(ACCOUNT, region)
                        .queryParam("region", region)
                        .get("/_floci/eks/clusters/" + name + "/oidc/.well-known/openid-configuration")
                        .then().statusCode(200)
                        .body("issuer", equalTo(issuers.get(region)))
                        .extract().path("jwks_uri");
                assertTrue(jwksUri.endsWith("/oidc/keys?region=" + region), jwksUri);
                String keyId = eks(ACCOUNT, region)
                        .queryParam("region", region)
                        .get("/_floci/eks/clusters/" + name + "/oidc/keys")
                        .then().statusCode(200).extract().path("keys[0].kid");
                if (westKeyId == null) {
                    westKeyId = keyId;
                } else {
                    assertNotEquals(westKeyId, keyId, "each Region's cluster has its own signing key");
                }
            }

            // Minting takes the same ?region=, which wins over the request's Region, so an
            // unsigned caller can pick either cluster.
            for (String region : new String[] {"eu-west-1", "eu-central-1"}) {
                eks(ACCOUNT, "eu-west-1")
                        .queryParam("region", region)
                        .body(Map.of("namespace", "default", "serviceAccount", "app"))
                        .post("/_floci/eks/clusters/" + name + "/oidc-token")
                        .then().statusCode(200)
                        .body("issuer", equalTo(issuers.get(region)));
            }
            eks(ACCOUNT, "eu-west-1")
                    .queryParam("region", "ap-south-1")
                    .body(Map.of("namespace", "default", "serviceAccount", "app"))
                    .post("/_floci/eks/clusters/" + name + "/oidc-token")
                    .then().statusCode(404);

            eks(ACCOUNT, "eu-west-1").delete("/clusters/" + name)
                    .then().statusCode(200);
            eks(ACCOUNT, "eu-central-1").get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.arn", equalTo("arn:aws:eks:eu-central-1:" + ACCOUNT + ":cluster/" + name))
                    .body("cluster", not(hasKey("dockerNameRegion")));
        } finally {
            eks(ACCOUNT, "eu-west-1").delete("/clusters/" + name);
            eks(ACCOUNT, "eu-central-1").delete("/clusters/" + name);
        }
    }
}
