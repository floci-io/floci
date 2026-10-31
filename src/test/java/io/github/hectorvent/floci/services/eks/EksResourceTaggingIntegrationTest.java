package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * TagResource, UntagResource and ListTagsForResource take the ARN of any taggable EKS resource, not
 * only a cluster's. A child ARN such as {@code nodegroup/<cluster>/<name>/<id>} used to be read as a
 * cluster named after its last segment, so every child answered ResourceNotFoundException.
 */
@QuarkusTest
class EksResourceTaggingIntegrationTest {

    private static final String ACCOUNT = "112233445566";

    @Test
    void everyTaggableResourceIsTaggedUntaggedAndListedByItsArn() {
        String name = "tags-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "role-" + name;
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + role;
        createRole(role);
        createCluster(name, roleArn);
        try {
            String base = "/clusters/" + name;
            Map<String, String> arns = new LinkedHashMap<>();
            arns.put("cluster", eks().get(base).then().statusCode(200).extract().path("cluster.arn"));
            arns.put("nodegroup", eks().contentType("application/json")
                    .body(Map.of("nodegroupName", "ng1", "subnets", new String[] {"subnet-abc"}, "nodeRole", roleArn,
                            "scalingConfig", Map.of("minSize", 1, "maxSize", 1, "desiredSize", 1)))
                    .post(base + "/node-groups").then().statusCode(200).extract().path("nodegroup.nodegroupArn"));
            arns.put("fargateprofile", eks().contentType("application/json")
                    .body(Map.of("fargateProfileName", "fp1", "podExecutionRoleArn", roleArn,
                            "selectors", new Object[] {Map.of("namespace", "default")}))
                    .post(base + "/fargate-profiles").then().statusCode(200)
                    .extract().path("fargateProfile.fargateProfileArn"));
            arns.put("addon", eks().contentType("application/json").body(Map.of("addonName", "vpc-cni"))
                    .post(base + "/addons").then().statusCode(200).extract().path("addon.addonArn"));
            arns.put("podidentityassociation", eks().contentType("application/json")
                    .body(Map.of("namespace", "default", "serviceAccount", "sa", "roleArn", roleArn))
                    .post(base + "/pod-identity-associations").then().statusCode(200)
                    .extract().path("association.associationArn"));
            arns.put("access-entry", eks().contentType("application/json").body(Map.of("principalArn", roleArn))
                    .post(base + "/access-entries").then().statusCode(200)
                    .extract().path("accessEntry.accessEntryArn"));

            for (Map.Entry<String, String> resource : arns.entrySet()) {
                String arn = resource.getValue();
                eks().contentType("application/json").body(Map.of("tags", Map.of("team", resource.getKey(), "env", "dev")))
                        .post("/tags/" + arn).then().statusCode(lessThan(300));
                eks().get("/tags/" + arn).then().statusCode(200)
                        .body("tags.team", equalTo(resource.getKey()))
                        .body("tags.env", equalTo("dev"));
                eks().queryParam("tagKeys", "env").delete("/tags/" + arn).then().statusCode(lessThan(300));
                eks().get("/tags/" + arn).then().statusCode(200)
                        .body("tags", not(hasKey("env")))
                        .body("tags.team", equalTo(resource.getKey()));
            }

            // Tags written through TagResource are the ones each resource's own describe returns.
            eks().get(base + "/node-groups/ng1").then().statusCode(200).body("nodegroup.tags.team", equalTo("nodegroup"));
            eks().get(base + "/addons/vpc-cni").then().statusCode(200).body("addon.tags.team", equalTo("addon"));

            // A child ARN whose id is not the stored resource's, or whose cluster does not exist, names nothing.
            String staleNodegroup = arns.get("nodegroup").substring(0, arns.get("nodegroup").lastIndexOf('/') + 1)
                    + UUID.randomUUID();
            eks().get("/tags/" + staleNodegroup).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            eks().get("/tags/" + arns.get("addon").replace("/" + name + "/", "/no-such-cluster/")).then()
                    .statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            eks().get("/tags/arn:aws:eks:us-east-1:" + ACCOUNT + ":identityproviderconfig/" + name + "/oidc/x/y")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            eks().get("/tags/" + arns.get("cluster")).then().statusCode(200).body("tags", not(anEmptyMap()));
        } finally {
            eks().delete("/clusters/" + name + "/addons/vpc-cni");
            eks().delete("/clusters/" + name + "/node-groups/ng1");
            eks().delete("/clusters/" + name + "/fargate-profiles/fp1");
            eks().delete("/clusters/" + name).then().statusCode(200);
            given().header("Authorization", auth("iam")).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/");
        }
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", auth("eks"));
    }

    private static void createRole(String role) {
        given().header("Authorization", auth("iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
    }

    private static void createCluster(String name, String roleArn) {
        eks().contentType("application/json")
                .body(Map.of("name", name, "roleArn", roleArn,
                        "accessConfig", Map.of("authenticationMode", "API", "bootstrapClusterCreatorAdminPermissions", false)))
                .post("/clusters").then().statusCode(200);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260916/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
