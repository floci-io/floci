package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Read-only EKS sub-resource lists (issue #1754). These routes must be handled by EKS —
 * previously the paths fell through to S3's path-style catch-all and returned NoSuchBucket
 * XML. A missing cluster is the model-declared ResourceNotFoundException (404); an existing
 * cluster returns the documented empty list under each operation's exact result key.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksSubResourceListsIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "subres-it-cluster";

    @ParameterizedTest
    @Order(1)
    @CsvSource({
            "access-entries, accessEntries",
            "addons, addons",
            "fargate-profiles, fargateProfileNames",
            "identity-provider-configs, identityProviderConfigs",
            "pod-identity-associations, associations"
    })
    void missingClusterReturnsResourceNotFound(String path, String key) {
        given()
        .when()
            .get("/clusters/no-such-cluster/" + path)
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", containsString("no-such-cluster"));
    }

    @Test
    @Order(2)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @ParameterizedTest
    @Order(3)
    @CsvSource({
            "access-entries, accessEntries",
            "addons, addons",
            "fargate-profiles, fargateProfileNames",
            "identity-provider-configs, identityProviderConfigs",
            "pod-identity-associations, associations"
    })
    void existingClusterReturnsEmptyListUnderModelKey(String path, String key) {
        given()
        .when()
            .get("/clusters/" + CLUSTER + "/" + path)
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body(key, hasSize(0));
    }

    @Test
    @Order(4)
    void deleteCluster() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }
}
