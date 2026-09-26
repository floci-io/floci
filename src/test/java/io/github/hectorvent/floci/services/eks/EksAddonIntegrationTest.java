package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksAddonIntegrationTest {

    @Test
    void addonLifecycleRoundTrip() {
        String account = "123456789012";
        String name = "addon-it-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/addons";

        createRole(account, roleName);
        createCluster(account, name, "1.30");

        try {
            // 1. Create addon
            Map<String, Object> createReq = Map.of(
                    "addonName", "vpc-cni",
                    "addonVersion", "v1.18.1-eksbuild.1",
                    "serviceAccountRoleArn", roleArn,
                    "configurationValues", "{\"env\":{\"ENABLE_PREFIX_DELEGATION\":\"true\"}}",
                    "clientRequestToken", "token-addon-1",
                    "tags", Map.of("environment", "test")
            );

            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addon.clusterName", equalTo(name))
                    .body("addon.addonName", equalTo("vpc-cni"))
                    .body("addon.addonVersion", equalTo("v1.18.1-eksbuild.1"))
                    .body("addon.status", equalTo("ACTIVE"))
                    .body("addon.serviceAccountRoleArn", equalTo(roleArn))
                    .body("addon.configurationValues", equalTo("{\"env\":{\"ENABLE_PREFIX_DELEGATION\":\"true\"}}"))
                    .body("addon.tags.environment", equalTo("test"))
                    .body("addon.health.issues", empty())
                    .body("addon.podIdentityAssociations", empty())
                    .body("addon.owner", equalTo("aws"))
                    .body("addon.publisher", equalTo("eks"))
                    .body("addon.addonArn", startsWith("arn:aws:eks:us-east-1:" + account + ":addon/" + name + "/vpc-cni/"))
                    .body("addon.createdAt", instanceOf(Number.class))
                    .body("addon.modifiedAt", instanceOf(Number.class));

            // 2. Idempotent retry with same parameters and token
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .body("addon.addonName", equalTo("vpc-cni"));

            // 3. Conflicting request with same token fails
            Map<String, Object> conflictReq = Map.of(
                    "addonName", "vpc-cni",
                    "addonVersion", "v1.18.1-eksbuild.1",
                    "clientRequestToken", "token-addon-1",
                    "tags", Map.of("environment", "production")
            );
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(conflictReq)
                    .post(basePath)
                    .then()
                    .statusCode(400)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("InvalidParameterException"));

            // 4. Duplicate creation with different token fails (409)
            Map<String, Object> duplicateReq = Map.of(
                    "addonName", "vpc-cni",
                    "clientRequestToken", "token-addon-2"
            );
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(duplicateReq)
                    .post(basePath)
                    .then()
                    .statusCode(409)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("ResourceInUseException"));

            // 5. Describe addon
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath + "/vpc-cni")
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addon.addonName", equalTo("vpc-cni"))
                    .body("addon.status", equalTo("ACTIVE"))
                    .body("addon.addonVersion", equalTo("v1.18.1-eksbuild.1"));

            // 6. Update addon
            String updatedRoleName = "updated-" + roleName;
            String updatedRoleArn = "arn:aws:iam::" + account + ":role/" + updatedRoleName;
            createRole(account, updatedRoleName);

            Map<String, Object> updateReq = Map.of(
                    "addonVersion", "v1.18.5-eksbuild.1",
                    "serviceAccountRoleArn", updatedRoleArn,
                    "configurationValues", "{\"env\":{\"ENABLE_PREFIX_DELEGATION\":\"false\"}}"
            );
            String updateId = given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(updateReq)
                    .post(basePath + "/vpc-cni/update")
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("update.status", equalTo("Successful"))
                    .body("update.type", equalTo("AddonUpdate"))
                    .body("update.id", notNullValue())
                    .extract().path("update.id");

            // Verify DescribeUpdate (called by Terraform aws_eks_addon waiter)
            given().header("Authorization", auth(account, "eks"))
                    .queryParam("addonName", "vpc-cni")
                    .get("/clusters/" + name + "/updates/" + updateId)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("update.id", equalTo(updateId))
                    .body("update.status", equalTo("Successful"))
                    .body("update.type", equalTo("AddonUpdate"));

            // Verify describe shows updated values
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath + "/vpc-cni")
                    .then()
                    .statusCode(200)
                    .body("addon.addonVersion", equalTo("v1.18.5-eksbuild.1"))
                    .body("addon.serviceAccountRoleArn", equalTo(updatedRoleArn))
                    .body("addon.configurationValues", equalTo("{\"env\":{\"ENABLE_PREFIX_DELEGATION\":\"false\"}}"));

            // 7. List addons
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addons", hasSize(1))
                    .body("addons[0]", equalTo("vpc-cni"));

            // 8. Delete addon
            given().header("Authorization", auth(account, "eks"))
                    .delete(basePath + "/vpc-cni")
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addon.addonName", equalTo("vpc-cni"))
                    .body("addon.status", equalTo("DELETING"));

            // 9. Describe after delete returns 404
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath + "/vpc-cni")
                    .then()
                    .statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void describeAddonVersionsEndpoints() {
        String account = "123456789012";

        // Query all supported versions
        given().header("Authorization", auth(account, "eks"))
                .get("/addons/supported-versions")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body("addons", hasSize(greaterThanOrEqualTo(5)))
                .body("addons.addonName", hasItems("vpc-cni", "coredns", "kube-proxy", "eks-pod-identity-agent", "aws-ebs-csi-driver"));

        // Query with addonName filter
        given().header("Authorization", auth(account, "eks"))
                .queryParam("addonName", "vpc-cni")
                .get("/addons/supported-versions")
                .then()
                .statusCode(200)
                .body("addons", hasSize(1))
                .body("addons[0].addonName", equalTo("vpc-cni"))
                .body("addons[0].addonVersions", not(empty()));

        // Query with kubernetesVersion filter
        given().header("Authorization", auth(account, "eks"))
                .queryParam("addonName", "vpc-cni")
                .queryParam("kubernetesVersion", "1.29")
                .get("/addons/supported-versions")
                .then()
                .statusCode(200)
                .body("addons", hasSize(1))
                .body("addons[0].addonName", equalTo("vpc-cni"))
                .body("addons[0].addonVersions.addonVersion", hasItem("v1.18.1-eksbuild.1"));

        // Unknown addon name returns empty list 200
        given().header("Authorization", auth(account, "eks"))
                .queryParam("addonName", "non-existent-addon")
                .get("/addons/supported-versions")
                .then()
                .statusCode(200)
                .body("addons", empty());
    }

    @Test
    void addonPodIdentityAssociationLifecycleIntegration() {
        String account = "123456789012";
        String name = "addon-pia-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/addons";

        createRole(account, roleName);
        createCluster(account, name, "1.29");

        try {
            // Create addon with pod identity association
            Map<String, Object> createReq = Map.of(
                    "addonName", "vpc-cni",
                    "podIdentityAssociations", List.of(
                            Map.of("serviceAccount", "aws-node", "roleArn", roleArn)
                    )
            );

            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .body("addon.podIdentityAssociations", hasSize(1));

            // Verify the association was created under kube-system namespace
            given().header("Authorization", auth(account, "eks"))
                    .get("/clusters/" + name + "/pod-identity-associations")
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(1))
                    .body("associations[0].namespace", equalTo("kube-system"))
                    .body("associations[0].serviceAccount", equalTo("aws-node"));

            // Delete addon with preserve=false (default) -> deletes association
            given().header("Authorization", auth(account, "eks"))
                    .delete(basePath + "/vpc-cni")
                    .then()
                    .statusCode(200);

            given().header("Authorization", auth(account, "eks"))
                    .get("/clusters/" + name + "/pod-identity-associations")
                    .then()
                    .statusCode(200)
                    .body("associations", empty());

            // Re-creating addon with same association succeeds (no ResourceInUseException)
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .body("addon.addonName", equalTo("vpc-cni"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void addonOnClusterVersionOutsideCatalogRangeIntegration() {
        String account = "123456789012";
        String name = "addon-k8s-" + UUID.randomUUID().toString().substring(0, 8);
        String basePath = "/clusters/" + name + "/addons";

        createCluster(account, name, "1.35");
        try {
            // Create with no version resolves default version on cluster outside catalog range
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(Map.of("addonName", "vpc-cni"))
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .body("addon.addonName", equalTo("vpc-cni"))
                    .body("addon.status", equalTo("ACTIVE"))
                    .body("addon.addonVersion", notNullValue());

            // In-place update with catalog version succeeds
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(Map.of("addonVersion", "v1.18.1-eksbuild.1"))
                    .post(basePath + "/vpc-cni/update")
                    .then()
                    .statusCode(200)
                    .body("update.status", equalTo("Successful"));

            // In-place update with unknown version fails
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(Map.of("addonVersion", "v99.0.0"))
                    .post(basePath + "/vpc-cni/update")
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void clusterDeletionCleansAddons() {
        String account = "123456789012";
        String name = "addon-clean-" + UUID.randomUUID().toString().substring(0, 8);
        String basePath = "/clusters/" + name + "/addons";

        createCluster(account, name, "1.29");

        given().header("Authorization", auth(account, "eks"))
                .contentType("application/json")
                .body(Map.of("addonName", "vpc-cni"))
                .post(basePath)
                .then()
                .statusCode(200);

        // Delete cluster
        deleteCluster(account, name);

        // Recreate cluster with same name
        createCluster(account, name, "1.29");
        try {
            // Addons must be empty on recreated cluster
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("addons", empty());
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void routeCollisionsAndMissingCluster() {
        String account = "123456789012";

        // Missing cluster returns 404 JSON, not S3 NoSuchBucket XML
        given().header("Authorization", auth(account, "eks"))
                .get("/clusters/nonexistent-cluster/addons")
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"))
                .body("__type", equalTo("ResourceNotFoundException"));

        // Unknown addon creation parameter error
        String name = "addon-err-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(account, name, "1.29");
        try {
            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(Map.of("addonName", "unknown-addon"))
                    .post("/clusters/" + name + "/addons")
                    .then()
                    .statusCode(400)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("InvalidParameterException"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void ebsCsiDriverLifecycleIntegration() {
        String account = "123456789012";
        String name = "addon-ebs-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/addons";

        createRole(account, roleName);
        createCluster(account, name, "1.30");

        try {
            // 1. Create aws-ebs-csi-driver addon without specifying version (resolves default v1.31.0-eksbuild.1)
            Map<String, Object> createReq = Map.of(
                    "addonName", "aws-ebs-csi-driver",
                    "serviceAccountRoleArn", roleArn
            );

            given().header("Authorization", auth(account, "eks"))
                    .contentType("application/json")
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addon.clusterName", equalTo(name))
                    .body("addon.addonName", equalTo("aws-ebs-csi-driver"))
                    .body("addon.addonVersion", equalTo("v1.31.0-eksbuild.1"))
                    .body("addon.status", equalTo("ACTIVE"))
                    .body("addon.owner", equalTo("aws"))
                    .body("addon.publisher", equalTo("eks"))
                    .body("addon.serviceAccountRoleArn", equalTo(roleArn));

            // 2. Describe addon returns it
            given().header("Authorization", auth(account, "eks"))
                    .get(basePath + "/aws-ebs-csi-driver")
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("addon.addonName", equalTo("aws-ebs-csi-driver"))
                    .body("addon.addonVersion", equalTo("v1.31.0-eksbuild.1"))
                    .body("addon.status", equalTo("ACTIVE"));

            // 3. Filter describe-addon-versions by addonName
            given().header("Authorization", auth(account, "eks"))
                    .queryParam("addonName", "aws-ebs-csi-driver")
                    .get("/addons/supported-versions")
                    .then()
                    .statusCode(200)
                    .body("addons", hasSize(1))
                    .body("addons[0].addonName", equalTo("aws-ebs-csi-driver"))
                    .body("addons[0].type", equalTo("storage"))
                    .body("addons[0].owner", equalTo("aws"))
                    .body("addons[0].publisher", equalTo("eks"));

            // 4. Filter describe-addon-versions by addonName and kubernetesVersion
            given().header("Authorization", auth(account, "eks"))
                    .queryParam("addonName", "aws-ebs-csi-driver")
                    .queryParam("kubernetesVersion", "1.32")
                    .get("/addons/supported-versions")
                    .then()
                    .statusCode(200)
                    .body("addons", hasSize(1))
                    .body("addons[0].addonName", equalTo("aws-ebs-csi-driver"))
                    .body("addons[0].addonVersions.addonVersion", hasItem("v1.38.1-eksbuild.1"));
        } finally {
            deleteCluster(account, name);
            deleteRole(account, roleName);
        }
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260920/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=fake";
    }

    private static void createRole(String account, String roleName) {
        given().header("Authorization", auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", "{}")
                .post("/")
                .then()
                .statusCode(200);
    }

    private static void deleteRole(String account, String roleName) {
        given().header("Authorization", auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", roleName)
                .post("/")
                .then()
                .statusCode(200);
    }

    private static void createCluster(String account, String name, String version) {
        Map<String, Object> req = Map.of(
                "name", name,
                "roleArn", "arn:aws:iam::" + account + ":role/eks-role",
                "version", version,
                "accessConfig", Map.of(
                        "authenticationMode", "API",
                        "bootstrapClusterCreatorAdminPermissions", false
                )
        );
        given().header("Authorization", auth(account, "eks"))
                .contentType("application/json")
                .body(req)
                .post("/clusters")
                .then()
                .statusCode(200);
    }

    private static void deleteCluster(String account, String name) {
        given().header("Authorization", auth(account, "eks"))
                .delete("/clusters/" + name);
    }
}
