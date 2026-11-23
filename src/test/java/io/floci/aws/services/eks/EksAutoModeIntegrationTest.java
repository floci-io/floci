package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.floci.aws.testing.EksRequests.eks;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

    @Test
    void updateClusterConfigTurnsAutoModeOnForAnExistingClusterAsAnUpdateDescribeUpdateFinds() {
        String name = "auto-on-" + UUID.randomUUID().toString().substring(0, 8);
        eks(ACCOUNT, REGION).body("""
                {"name":"%s","roleArn":"arn:aws:iam::%s:role/eks-auto-cluster"}""".formatted(name, ACCOUNT))
                .post("/clusters").then().statusCode(200);
        try {
            String id = eks(ACCOUNT, REGION).body(autoMode(true, true, true, NODE_ROLE))
                    .post("/clusters/" + name + "/update-config").then().statusCode(200)
                    .body("update.type", equalTo("AutoModeUpdate"))
                    .body("update.params.type", containsInAnyOrder("ComputeConfig", "StorageConfig",
                            "KubernetesNetworkConfig"))
                    .extract().path("update.id");
            eks(ACCOUNT, REGION).get("/clusters/" + name + "/updates/" + id).then().statusCode(200)
                    .body("update.type", equalTo("AutoModeUpdate"));
            assertAutoModeEnabled(eks(ACCOUNT, REGION).get("/clusters/" + name).then());
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @Test
    void updateClusterConfigTurnsAutoModeOffAndChangesTheNodePools() {
        String name = "auto-off-" + UUID.randomUUID().toString().substring(0, 8);
        eks(ACCOUNT, REGION).body(cluster(name, true, true, true)).post("/clusters").then().statusCode(200);
        try {
            eks(ACCOUNT, REGION).body("""
                    {"computeConfig":{"enabled":true,"nodePools":["general-purpose"],"nodeRoleArn":"%s"},
                     "storageConfig":{"blockStorage":{"enabled":true}},
                     "kubernetesNetworkConfig":{"elasticLoadBalancing":{"enabled":true}}}""".formatted(NODE_ROLE))
                    .post("/clusters/" + name + "/update-config").then().statusCode(200);
            eks(ACCOUNT, REGION).get("/clusters/" + name).then().statusCode(200)
                    .body("cluster.computeConfig.nodePools", contains("general-purpose"));

            eks(ACCOUNT, REGION).body(autoMode(false, false, false, null))
                    .post("/clusters/" + name + "/update-config").then().statusCode(200)
                    .body("update.type", equalTo("AutoModeUpdate"));
            eks(ACCOUNT, REGION).get("/clusters/" + name).then().statusCode(200)
                    .body("cluster.computeConfig.enabled", equalTo(false))
                    .body("cluster.storageConfig.blockStorage.enabled", equalTo(false))
                    .body("cluster.kubernetesNetworkConfig.elasticLoadBalancing.enabled", equalTo(false));
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // the capabilities enabled apart
            "true|true|false|eks-auto-node|InvalidParameterException|For EKS Auto Mode, please ensure that all "
                    + "required configs, including computeConfig, kubernetesNetworkConfig, and blockStorage are all "
                    + "either fully enabled or fully disabled.",
            // a different node role while the compute capability is on
            "true|true|true|other-node|InvalidParameterException|nodeRoleArn cannot be changed after the compute "
                    + "capability of EKS Auto Mode is enabled",
            // the configuration the cluster already has
            "true|true|true|eks-auto-node|InvalidRequestException|No changes needed for EKS Auto Mode configuration "
                    + "provided"
    })
    void updateClusterConfigRefusesTheAutoModeChangesAwsRefusesAndChangesNothing(
            boolean compute, boolean blockStorage, boolean loadBalancing, String nodeRole, String type, String message) {
        String name = "auto-refuse-" + UUID.randomUUID().toString().substring(0, 8);
        eks(ACCOUNT, REGION).body(cluster(name, true, true, true)).post("/clusters").then().statusCode(200);
        try {
            eks(ACCOUNT, REGION).body(autoMode(compute, blockStorage, loadBalancing,
                            "arn:aws:iam::" + ACCOUNT + ":role/" + nodeRole))
                    .post("/clusters/" + name + "/update-config").then().statusCode(400)
                    .body("__type", equalTo(type))
                    .body("message", equalTo(message));
            assertAutoModeEnabled(eks(ACCOUNT, REGION).get("/clusters/" + name).then());
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @Test
    void turningAutoModeOffOnAClusterThatNeverHadItChangesNothing() {
        String name = "auto-noop-" + UUID.randomUUID().toString().substring(0, 8);
        eks(ACCOUNT, REGION).body("""
                {"name":"%s","roleArn":"arn:aws:iam::%s:role/eks-auto-cluster"}""".formatted(name, ACCOUNT))
                .post("/clusters").then().statusCode(200);
        try {
            eks(ACCOUNT, REGION).body(autoMode(false, false, false, null))
                    .post("/clusters/" + name + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"))
                    .body("message", equalTo("No changes needed for EKS Auto Mode configuration provided"));
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    @Test
    void anAutoModeUpdateWithAnotherFamilyIsRefused() {
        String name = "auto-two-" + UUID.randomUUID().toString().substring(0, 8);
        eks(ACCOUNT, REGION).body(cluster(name, true, true, true)).post("/clusters").then().statusCode(200);
        try {
            eks(ACCOUNT, REGION).body("""
                    {"computeConfig":{"enabled":false},"storageConfig":{"blockStorage":{"enabled":false}},
                     "kubernetesNetworkConfig":{"elasticLoadBalancing":{"enabled":false}},
                     "logging":{"clusterLogging":[{"types":["audit"],"enabled":true}]}}""")
                    .post("/clusters/" + name + "/update-config").then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"))
                    .body("message", equalTo("Only one type of update can be allowed."));
            assertAutoModeEnabled(eks(ACCOUNT, REGION).get("/clusters/" + name).then());
        } finally {
            eks(ACCOUNT, REGION).delete("/clusters/" + name).then().statusCode(200);
        }
    }

    private static String autoMode(boolean compute, boolean blockStorage, boolean loadBalancing, String nodeRole) {
        String computeConfig = compute
                ? "{\"enabled\":true,\"nodePools\":[\"general-purpose\",\"system\"],\"nodeRoleArn\":\"" + nodeRole + "\"}"
                : "{\"enabled\":false}";
        return """
                {"computeConfig":%s,"storageConfig":{"blockStorage":{"enabled":%b}},
                 "kubernetesNetworkConfig":{"elasticLoadBalancing":{"enabled":%b}}}"""
                .formatted(computeConfig, blockStorage, loadBalancing);
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
