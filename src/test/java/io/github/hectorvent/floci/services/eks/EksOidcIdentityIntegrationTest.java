package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * DescribeCluster must expose {@code identity.oidc.issuer} so IRSA callers can build a trust policy
 * referencing the cluster's OIDC provider, and must never expose the signing material behind it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksOidcIdentityIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "oidc-it-cluster";

    @Inject
    EksOidcService oidcService;

    @Test
    @Order(1)
    void createClusterReturnsAwsShapedOidcIssuer() {
        given()
            .contentType(JSON)
            .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
        .when()
            .post("/clusters")
        .then()
            .statusCode(200)
            .body("cluster.identity.oidc.issuer", matchesPattern(
                    "https://oidc\\.eks\\.us-east-1\\.amazonaws\\.com/id/[A-F0-9]{32}"));
    }

    @Test
    @Order(2)
    void describeClusterReturnsStableIssuer() {
        String issuer = given()
        .when()
            .get("/clusters/" + CLUSTER)
        .then()
            .statusCode(200)
            .body("cluster.identity.oidc.issuer", notNullValue())
            .extract().path("cluster.identity.oidc.issuer");

        // The issuer is persisted, not regenerated per describe — a trust policy written once
        // must keep matching.
        given()
        .when()
            .get("/clusters/" + CLUSTER)
        .then()
            .statusCode(200)
            .body("cluster.identity.oidc.issuer", equalTo(issuer));
    }

    @Test
    @Order(3)
    void describeClusterNeverExposesSigningMaterial() {
        // Asserted against the actual stored key rather than a list of guessed field names, so this
        // fails if key material is ever surfaced under any property.
        ClusterOidcKey stored = oidcService.findKeyByCluster(CLUSTER).orElseThrow();

        String body = given()
        .when()
            .get("/clusters/" + CLUSTER)
        .then()
            .statusCode(200)
            .extract().asString();

        assertFalse(body.contains(stored.getPrivateKey()),
                "DescribeCluster response leaked the OIDC private key");
        assertFalse(body.contains(stored.getPublicKey()),
                "DescribeCluster response leaked the OIDC public key");
        // A PKCS#8 RSA private key in base64 always begins with this prefix.
        assertFalse(body.contains("MII"), "DescribeCluster response contains encoded key material");
    }

    @Test
    @Order(4)
    void distinctClustersGetDistinctIssuers() {
        String second = CLUSTER + "-2";
        String firstIssuer = given()
            .when().get("/clusters/" + CLUSTER)
            .then().statusCode(200)
            .extract().path("cluster.identity.oidc.issuer");

        String secondIssuer = given()
            .contentType(JSON)
            .body("{\"name\":\"" + second + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks\"}")
        .when()
            .post("/clusters")
        .then()
            .statusCode(200)
            .extract().path("cluster.identity.oidc.issuer");

        assertNotEquals(firstIssuer, secondIssuer);

        given().when().delete("/clusters/" + second).then().statusCode(200);
    }

    @Test
    @Order(5)
    void deleteClusterSucceeds() {
        given()
        .when()
            .delete("/clusters/" + CLUSTER)
        .then()
            .statusCode(200);
    }
}
