package io.floci.aws.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EksRequests.eks;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.IamTestPolicies.EMPTY_POLICY_DOCUMENT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksPodIdentityAssociationIntegrationTest {

    private static final String REGION = "us-east-1";

    @Test
    void associationLifecycleRoundTrip() {
        String account = "123456789012";
        String name = "pia-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/pod-identity-associations";

        createRole(account, roleName);
        createCluster(account, name);

        try {
            // 1. Create association
            Map<String, Object> createReq = Map.of(
                    "namespace", "default",
                    "serviceAccount", "my-service-account",
                    "roleArn", roleArn,
                    "clientRequestToken", "token-abc",
                    "tags", Map.of("env", "test")
            );

            String associationId = eks(account, REGION)
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("association.clusterName", equalTo(name))
                    .body("association.namespace", equalTo("default"))
                    .body("association.serviceAccount", equalTo("my-service-account"))
                    .body("association.roleArn", equalTo(roleArn))
                    .body("association.associationId", startsWith("a-"))
                    .body("association.associationArn", startsWith("arn:aws:eks:us-east-1:" + account + ":podidentityassociation/" + name + "/a-"))
                    .body("association.tags.env", equalTo("test"))
                    .body("association.createdAt", instanceOf(Number.class))
                    .body("association.modifiedAt", instanceOf(Number.class))
                    .extract().path("association.associationId");

            // 2. Idempotent retry with same parameters and token
            eks(account, REGION)
                    .body(createReq)
                    .post(basePath)
                    .then()
                    .statusCode(200)
                    .body("association.associationId", equalTo(associationId));

            // 3. Same token with different parameters returns 400
            Map<String, Object> conflictReq = Map.of(
                    "namespace", "other-ns",
                    "serviceAccount", "my-service-account",
                    "roleArn", roleArn,
                    "clientRequestToken", "token-abc"
            );
            eks(account, REGION)
                    .body(conflictReq)
                    .post(basePath)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            // 4. Duplicate (namespace, serviceAccount) returns 409
            Map<String, Object> duplicateReq = Map.of(
                    "namespace", "default",
                    "serviceAccount", "my-service-account",
                    "roleArn", roleArn
            );
            eks(account, REGION)
                    .body(duplicateReq)
                    .post(basePath)
                    .then()
                    .statusCode(409)
                    .body("__type", equalTo("ResourceInUseException"));

            // 5. Describe association
            eks(account, REGION)
                    .get(basePath + "/" + associationId)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("association.associationId", equalTo(associationId))
                    .body("association.namespace", equalTo("default"))
                    .body("association.serviceAccount", equalTo("my-service-account"));

            // 6. Update association
            String updatedRoleName = "updated-" + roleName;
            String updatedRoleArn = "arn:aws:iam::" + account + ":role/" + updatedRoleName;
            createRole(account, updatedRoleName);

            Map<String, Object> updateReq = Map.of(
                    "roleArn", updatedRoleArn,
                    "disableSessionTags", true
            );
            eks(account, REGION)
                    .body(updateReq)
                    .post(basePath + "/" + associationId)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("association.associationId", equalTo(associationId))
                    .body("association.roleArn", equalTo(updatedRoleArn))
                    .body("association.disableSessionTags", equalTo(true));

            // 7. List associations
            eks(account, REGION)
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("associations", hasSize(1))
                    .body("associations[0].associationId", equalTo(associationId))
                    .body("associations[0].namespace", equalTo("default"))
                    .body("associations[0].serviceAccount", equalTo("my-service-account"));

            // List with filter matching
            eks(account, REGION)
                    .queryParam("namespace", "default")
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(1));

            // List with filter non-matching
            eks(account, REGION)
                    .queryParam("namespace", "other-namespace")
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(0));

            // 8. Delete association
            eks(account, REGION)
                    .delete(basePath + "/" + associationId)
                    .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("association.associationId", equalTo(associationId));

            // 9. Describe after delete returns 404
            eks(account, REGION)
                    .get(basePath + "/" + associationId)
                    .then()
                    .statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void routeCollisionsAndMissingCluster() {
        String account = "123456789012";
        // Missing cluster returns 404 JSON, not S3 NoSuchBucket XML
        eks(account, REGION)
                .get("/clusters/nonexistent-cluster/pod-identity-associations")
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void paginationAndLimits() {
        String account = "123456789012";
        String name = "pia-page-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/pod-identity-associations";

        createRole(account, roleName);
        createCluster(account, name);

        try {
            // Create two associations
            eks(account, REGION)
                    .body(Map.of("namespace", "ns1", "serviceAccount", "sa1", "roleArn", roleArn))
                    .post(basePath).then().statusCode(200);

            eks(account, REGION)
                    .body(Map.of("namespace", "ns2", "serviceAccount", "sa2", "roleArn", roleArn))
                    .post(basePath).then().statusCode(200);

            // maxResults = 1 returns first item and nextToken
            String nextToken = eks(account, REGION)
                    .queryParam("maxResults", "1")
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(1))
                    .body("nextToken", notNullValue())
                    .extract().path("nextToken");

            // Fetch next page
            eks(account, REGION)
                    .queryParam("maxResults", "1")
                    .queryParam("nextToken", nextToken)
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(1))
                    .body("nextToken", nullValue());

            // Invalid maxResults returns 400 InvalidParameterException
            eks(account, REGION)
                    .queryParam("maxResults", "0")
                    .get(basePath)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            eks(account, REGION)
                    .queryParam("maxResults", "invalid")
                    .get(basePath)
                    .then()
                    .statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
        } finally {
            deleteCluster(account, name);
        }
    }

    @Test
    void clusterDeletionCleansAssociations() {
        String account = "123456789012";
        String name = "pia-del-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + roleName;
        String basePath = "/clusters/" + name + "/pod-identity-associations";

        createRole(account, roleName);
        createCluster(account, name);

        eks(account, REGION)
                .body(Map.of("namespace", "default", "serviceAccount", "sa", "roleArn", roleArn))
                .post(basePath).then().statusCode(200);

        // Delete cluster
        deleteCluster(account, name);

        // Recreate cluster with same name
        createCluster(account, name);
        try {
            // Associations must be empty
            eks(account, REGION)
                    .get(basePath)
                    .then()
                    .statusCode(200)
                    .body("associations", hasSize(0));
        } finally {
            deleteCluster(account, name);
        }
    }

    private static void createRole(String account, String roleName) {
        given().header("Authorization", auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", EMPTY_POLICY_DOCUMENT)
                .post("/").then().statusCode(200);
    }

    private static void createCluster(String account, String name) {
        eks(account, REGION)
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + account + ":role/cluster"))
                .post("/clusters").then().statusCode(200);
    }

    private static void deleteCluster(String account, String name) {
        eks(account, REGION)
                .delete("/clusters/" + name).then().statusCode(200);
    }
}
