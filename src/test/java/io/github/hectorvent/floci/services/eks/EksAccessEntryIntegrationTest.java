package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksAccessEntryIntegrationTest {
    @Test
    void nodeEntryRoundTripUsesJsonAndEncodedPrincipalPaths() {
        String account = "135791357913";
        String name = "entries-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "worker-" + name;
        String principal = "arn:aws:iam::" + account + ":role/path/" + role;
        String path = "/clusters/" + name + "/access-entries";
        String entryPath = path + "/" + URLEncoder.encode(principal, StandardCharsets.UTF_8);
        given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", "/path/")
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
        createCluster(account, name, "API");
        try {
            Map<String, Object> request = Map.of("principalArn", principal, "type", "EC2_LINUX",
                    "tags", Map.of("team", "platform"), "clientRequestToken", "retry-token");
            String arn = given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200)
                    .body("accessEntry.username", equalTo("system:node:{{EC2PrivateDNSName}}"))
                    .body("accessEntry.createdAt", instanceOf(Number.class))
                    .body("accessEntry.tags.team", equalTo("platform"))
                    .body("accessEntry.principalId", nullValue())
                    .extract().path("accessEntry.accessEntryArn");
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200)
                    .body("accessEntry.accessEntryArn", equalTo(arn));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(entryPath).then().statusCode(200).contentType(containsString("application/json"))
                    .body("accessEntry.principalArn", equalTo(principal));
            given().header("Authorization", auth(account, "eks")).get(path).then().statusCode(200)
                    .body("accessEntries", contains(principal)).body("nextToken", nullValue());
            given().header("Authorization", auth("246802468024", "eks")).get(path).then().statusCode(404);
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .delete(entryPath).then().statusCode(200).body(equalTo("{}"));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(entryPath).then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200);
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
            createCluster(account, name, "API");
            given().header("Authorization", auth(account, "eks")).get(path).then().statusCode(200)
                    .body("accessEntries", empty());
        } finally {
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
            given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/").then().statusCode(200);
        }
    }

    @Test
    void accessPoliciesRouteToEksWithEncodedPrincipalAndPolicyArns() {
        String account = "135791357913";
        String name = "policies-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "admin-" + name;
        String principal = "arn:aws:iam::" + account + ":role/path/" + role;
        String policy = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";
        String entryPath = "/clusters/" + name + "/access-entries/" + URLEncoder.encode(principal, StandardCharsets.UTF_8);
        String policiesPath = entryPath + "/access-policies";
        given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", "/path/")
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
        createCluster(account, name, "API");
        try {
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(Map.of("principalArn", principal)).post("/clusters/" + name + "/access-entries")
                    .then().statusCode(200);
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .urlEncodingEnabled(false)
                    .body(Map.of("policyArn", policy, "accessScope", Map.of("type", "cluster")))
                    .post(policiesPath).then().statusCode(200).contentType(containsString("application/json"))
                    .body("clusterName", equalTo(name)).body("principalArn", equalTo(principal))
                    .body("associatedAccessPolicy.policyArn", equalTo(policy))
                    .body("associatedAccessPolicy.accessScope.type", equalTo("cluster"))
                    .body("associatedAccessPolicy.accessScope.namespaces", empty())
                    .body("associatedAccessPolicy.associatedAt", instanceOf(Number.class))
                    .body("associatedAccessPolicy.modifiedAt", instanceOf(Number.class));
            String view = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .urlEncodingEnabled(false)
                    .body(Map.of("policyArn", view, "accessScope",
                            Map.of("type", "namespace", "namespaces", List.of("dev-*", "payments"))))
                    .post(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicy.accessScope.type", equalTo("namespace"))
                    .body("associatedAccessPolicy.accessScope.namespaces", contains("dev-*", "payments"));
            given().header("Authorization", auth(account, "eks")).queryParam("associatedPolicyArn", view)
                    .get("/clusters/" + name + "/access-entries").then().statusCode(200)
                    .body("accessEntries", contains(principal));
            given().header("Authorization", auth(account, "eks"))
                    .queryParam("associatedPolicyArn", "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy")
                    .get("/clusters/" + name + "/access-entries").then().statusCode(200)
                    .body("accessEntries", empty());
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(policiesPath).then().statusCode(200)
                    .body("clusterName", equalTo(name)).body("principalArn", equalTo(principal))
                    .body("associatedAccessPolicies.policyArn", contains(policy, view))
                    .body("associatedAccessPolicies[1].accessScope.namespaces", contains("dev-*", "payments"))
                    .body("nextToken", nullValue());
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(entryPath).then().statusCode(200).body("accessEntry.principalArn", equalTo(principal));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .delete(policiesPath + "/" + URLEncoder.encode(policy, StandardCharsets.UTF_8))
                    .then().statusCode(200).body(equalTo("{}"));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .delete(policiesPath + "/" + URLEncoder.encode(policy, StandardCharsets.UTF_8))
                    .then().statusCode(200).body(equalTo("{}"));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(policiesPath).then().statusCode(200).body("associatedAccessPolicies.policyArn", contains(view));
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .urlEncodingEnabled(false)
                    .body(Map.of("policyArn", policy, "accessScope", Map.of("type", "namespace")))
                    .post(policiesPath).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .delete(entryPath).then().statusCode(200);
        } finally {
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
            given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648", "0", "-1", "101"})
    void invalidMaxResultsReturnsAwsJsonError(String maxResults) {
        String account = "135791357913";
        String name = "pagination-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(account, name, "API");
        try {
            given().header("Authorization", auth(account, "eks")).queryParam("maxResults", maxResults)
                    .get("/clusters/" + name + "/access-entries").then().statusCode(400)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("InvalidParameterException"))
                    .body("message", not(emptyOrNullString()));
        } finally {
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
        }
    }

    @Test
    void configMapModeRejectsEntriesAndUnknownModeIsRejectedAtCreate() {
        String name = "config-" + UUID.randomUUID().toString().substring(0, 8);
        String account = "135791357913";
        createCluster(account, name, "CONFIG_MAP");
        try {
            given().header("Authorization", auth(account, "eks"))
                    .get("/clusters/" + name + "/access-entries").then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"));
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(Map.of("name", name + "-invalid", "accessConfig", Map.of("authenticationMode", "invalid")))
                    .post("/clusters").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        } finally {
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
        }
    }

    @Test
    void aPrincipalWithAnAccessPoliciesPathSegmentKeepsItsEntryRoutes() {
        // An IAM path or role name may be access-policies. Sent as the SDK sends it, %2F and all, such an entry
        // is still read and deleted as an entry, never as one of a shorter principal's access policies.
        String account = "135791357913";
        String name = "odd-" + UUID.randomUUID().toString().substring(0, 8);
        String runner = "runner-" + name;
        String midPath = "arn:aws:iam::" + account + ":role/ci/access-policies/" + runner;
        String named = "arn:aws:iam::" + account + ":role/" + name + "/access-policies";
        String path = "/clusters/" + name + "/access-entries";
        createRole(account, runner, "/ci/access-policies/");
        createRole(account, "access-policies", "/" + name + "/");
        createCluster(account, name, "API");
        try {
            for (String principal : List.of(midPath, named)) {
                String entryPath = path + "/" + URLEncoder.encode(principal, StandardCharsets.UTF_8);
                given().header("Authorization", auth(account, "eks")).contentType("application/json")
                        .body(Map.of("principalArn", principal)).post(path).then().statusCode(200);
                given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                        .get(entryPath).then().statusCode(200).body("accessEntry.principalArn", equalTo(principal));
                given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                        .get(entryPath + "/access-policies").then().statusCode(200)
                        .body("principalArn", equalTo(principal)).body("associatedAccessPolicies", empty());
                given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                        .delete(entryPath).then().statusCode(200).body(equalTo("{}"));
                given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                        .get(entryPath).then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            }
            // Unencoded, the slashes are the router's: a DELETE is a disassociate only when what follows
            // access-policies is a cluster access policy ARN, so this one still deletes the entry.
            given().header("Authorization", auth(account, "eks")).contentType("application/json")
                    .body(Map.of("principalArn", midPath)).post(path).then().statusCode(200);
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .delete(path + "/" + midPath).then().statusCode(200).body(equalTo("{}"));
            given().header("Authorization", auth(account, "eks")).urlEncodingEnabled(false)
                    .get(path + "/" + URLEncoder.encode(midPath, StandardCharsets.UTF_8)).then().statusCode(404);
        } finally {
            given().header("Authorization", auth(account, "eks")).delete("/clusters/" + name).then().statusCode(200);
            deleteRole(account, runner);
            deleteRole(account, "access-policies");
        }
    }

    private static void createRole(String account, String role, String rolePath) {
        given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", rolePath)
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
    }

    private static void deleteRole(String account, String role) {
        given().header("Authorization", auth(account, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/").then().statusCode(200);
    }

    private static void createCluster(String account, String name, String mode) {
        given().header("Authorization", auth(account, "eks")).contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + account + ":role/cluster",
                        "accessConfig", Map.of("authenticationMode", mode, "bootstrapClusterCreatorAdminPermissions", false)))
                .post("/clusters").then().statusCode(200)
                .body("cluster.accessConfig.authenticationMode", equalTo(mode))
                .body("cluster.accessConfig.bootstrapClusterCreatorAdminPermissions", equalTo(false));
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260916/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
