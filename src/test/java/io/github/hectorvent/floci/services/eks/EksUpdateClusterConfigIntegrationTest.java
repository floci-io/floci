package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksUpdateClusterConfigIntegrationTest {

    private static final String ACCOUNT = "123456789012";

    @Test
    void upgradePolicyUpdateIsDescribedAndReadBack() {
        String name = "update-config-" + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", auth())
                .contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/eks-role",
                        "upgradePolicy", Map.of("supportType", "STANDARD")))
                .post("/clusters")
                .then()
                .statusCode(200)
                .body("cluster.upgradePolicy.supportType", equalTo("STANDARD"));

        try {
            String updateId = given().header("Authorization", auth())
                    .contentType("application/json")
                    .body(Map.of("upgradePolicy", Map.of("supportType", "EXTENDED")))
                    .post("/clusters/" + name + "/update-config")
                    .then()
                    .statusCode(200)
                    .body("update.status", equalTo("Successful"))
                    .body("update.type", equalTo("UpgradePolicyUpdate"))
                    .body("update.params[0].type", equalTo("UpgradePolicy"))
                    .body("update.params[0].value", equalTo("EXTENDED"))
                    .body("update.errors", empty())
                    .extract().path("update.id");

            given().header("Authorization", auth())
                    .get("/clusters/" + name + "/updates/" + updateId)
                    .then()
                    .statusCode(200)
                    .body("update.id", equalTo(updateId))
                    .body("update.status", equalTo("Successful"));

            given().header("Authorization", auth())
                    .get("/clusters/" + name)
                    .then()
                    .statusCode(200)
                    .body("cluster.upgradePolicy.supportType", equalTo("EXTENDED"));

            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body(Map.of("logging", Map.of("clusterLogging",
                            List.of(Map.of("types", List.of("api"), "enabled", true)))))
                    .post("/clusters/" + name + "/update-config")
                    .then()
                    .statusCode(200)
                    .body("update.type", equalTo("LoggingUpdate"));

            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body(Map.of("resourcesVpcConfig", Map.of("endpointPublicAccess", false,
                            "endpointPrivateAccess", true)))
                    .post("/clusters/" + name + "/update-config")
                    .then()
                    .statusCode(200)
                    .body("update.type", equalTo("EndpointAccessUpdate"));

            given().header("Authorization", auth())
                    .get("/clusters/" + name)
                    .then()
                    .statusCode(200)
                    .body("cluster.logging.clusterLogging.find { it.enabled }.types", hasItem("api"))
                    .body("cluster.resourcesVpcConfig.endpointPublicAccess", equalTo(false))
                    .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(true));

            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body(Map.of("zonalShiftConfig", Map.of("enabled", true)))
                    .post("/clusters/" + name + "/update-config")
                    .then()
                    .statusCode(400)
                    .body("__type", containsString("InvalidParameterException"))
                    .body("message", containsString("zonalShiftConfig"));
        } finally {
            given().header("Authorization", auth()).delete("/clusters/" + name);
        }
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260920/us-east-1/eks/aws4_request, SignedHeaders=host, Signature=fake";
    }
}
