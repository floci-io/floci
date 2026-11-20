package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.testing.EksRequests.eks;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EksAutoModeIntegrationTest {

    private static final String ACCOUNT = "864208642086";
    private static final String REGION = "us-east-1";
    private static final String NODE_ROLE = "arn:aws:iam::" + ACCOUNT + ":role/eks-auto-node";

    @Test
    void anAutoModeClusterCreatesAndDescribesItsConfiguration() {
        String name = "auto-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            assertAutoModeEnabled(eks(ACCOUNT, REGION).body(cluster(name, true, true, true)).post("/clusters").then());
            assertAutoModeEnabled(eks(ACCOUNT, REGION).get("/clusters/" + name).then());
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @Test
    void anAutoModeClusterWithoutLoadBalancingIsRefusedAndNotCreated() {
        String name = "auto-partial-" + UUID.randomUUID().toString().substring(0, 8);

        eks(ACCOUNT, REGION).body(cluster(name, true, true, false)).post("/clusters").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("For EKS Auto Mode, please ensure that all required configs, including "
                        + "computeConfig, kubernetesNetworkConfig, and blockStorage are all either fully enabled "
                        + "or fully disabled."));
        eks(ACCOUNT, REGION).get("/clusters/" + name).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static void assertAutoModeEnabled(ValidatableResponse response) {
        response.statusCode(200)
                .body("cluster.computeConfig.enabled", equalTo(true))
                .body("cluster.computeConfig.nodePools", contains("general-purpose", "system"))
                .body("cluster.computeConfig.nodeRoleArn", equalTo(NODE_ROLE))
                .body("cluster.storageConfig.blockStorage.enabled", equalTo(true))
                .body("cluster.kubernetesNetworkConfig.elasticLoadBalancing.enabled", equalTo(true));
    }

    private static String cluster(String name, boolean compute, boolean blockStorage, boolean loadBalancing) {
        return """
                {"name":"%s","roleArn":"arn:aws:iam::%s:role/eks-auto-cluster",
                 "computeConfig":{"enabled":%b,"nodePools":["general-purpose","system"],"nodeRoleArn":"%s"},
                 "storageConfig":{"blockStorage":{"enabled":%b}},
                 "kubernetesNetworkConfig":{"elasticLoadBalancing":{"enabled":%b}}}"""
                .formatted(name, ACCOUNT, compute, NODE_ROLE, blockStorage, loadBalancing);
    }
}
