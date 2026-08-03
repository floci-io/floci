package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full IRSA path: create a cluster, read its OIDC issuer, create a role whose trust policy pins the
 * federated OIDC provider to a service account, mint a token for that service account, and exchange
 * it via {@code sts:AssumeRoleWithWebIdentity}.
 *
 * <p>Also covers the negative cases that matter — wrong service account, tampered signature, wrong
 * audience — and the compatibility guarantee that an opaque third-party token is still accepted.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksIrsaEndToEndIntegrationTest {

    private static final String JSON = "application/json";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String CLUSTER = "irsa-e2e-cluster";
    private static final String ROLE = "irsa-e2e-role";
    private static final String NAMESPACE = "my-namespace";
    private static final String SERVICE_ACCOUNT = "cell-e2e";
    private static final String ACCOUNT = "000000000000";

    private static String issuer;
    private static String roleArn;

    private static String issuerKeyPrefix() {
        return issuer.replaceFirst("^https://", "");
    }

    @Test
    @Order(1)
    void createClusterAndCaptureIssuer() {
        issuer = given()
            .contentType(JSON)
            .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/eks\"}")
        .when()
            .post("/clusters")
        .then()
            .statusCode(200)
            .body("cluster.identity.oidc.issuer", notNullValue())
            .extract().path("cluster.identity.oidc.issuer");
    }

    @Test
    @Order(2)
    void createRoleWithFederatedTrustPolicy() {
        String prefix = issuerKeyPrefix();
        String trustPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{\
                "Federated":"arn:aws:iam::%s:oidc-provider/%s"},\
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{\
                "%s:sub":"system:serviceaccount:%s:%s","%s:aud":"sts.amazonaws.com"}}}]}"""
                .formatted(ACCOUNT, prefix, prefix, NAMESPACE, SERVICE_ACCOUNT, prefix);

        roleArn = given()
            .contentType(FORM)
            .formParam("Action", "CreateRole")
            .formParam("Version", "2010-05-08")
            .formParam("RoleName", ROLE)
            .formParam("AssumeRolePolicyDocument", trustPolicy)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private String mintToken(String namespace, String serviceAccount, String audience) {
        StringBuilder body = new StringBuilder("{\"namespace\":\"" + namespace
                + "\",\"serviceAccount\":\"" + serviceAccount + "\"");
        if (audience != null) {
            body.append(",\"audience\":\"").append(audience).append("\"");
        }
        body.append("}");

        return given()
            .contentType(JSON)
            .body(body.toString())
        .when()
            .post("/_floci/eks/clusters/" + CLUSTER + "/oidc-token")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .extract().path("token");
    }

    private Response assumeRole(String token) {
        return given()
            .contentType(FORM)
            .formParam("Action", "AssumeRoleWithWebIdentity")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", roleArn)
            .formParam("RoleSessionName", "irsa-e2e-session")
            .formParam("WebIdentityToken", token)
        .when()
            .post("/");
    }

    @Test
    @Order(3)
    void mintedTokenAssumesRoleAndReturnsRealClaims() {
        String token = mintToken(NAMESPACE, SERVICE_ACCOUNT, null);

        assumeRole(token)
        .then()
            .statusCode(200)
            .body(containsString("<AccessKeyId>ASIA"))
            .body(containsString("<SecretAccessKey>"))
            .body(containsString("<SessionToken>"))
            // Real claims from the token, not the old hardcoded placeholders.
            .body(containsString("<SubjectFromWebIdentityToken>system:serviceaccount:"
                    + NAMESPACE + ":" + SERVICE_ACCOUNT + "</SubjectFromWebIdentityToken>"))
            .body(containsString("<Provider>" + issuer + "</Provider>"))
            .body(containsString("<Audience>sts.amazonaws.com</Audience>"));
    }

    @Test
    @Order(4)
    void deniesTokenForDifferentServiceAccount() {
        String token = mintToken(NAMESPACE, "not-the-trusted-sa", null);

        assumeRole(token)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(5)
    void rejectsTamperedSignature() {
        String token = mintToken(NAMESPACE, SERVICE_ACCOUNT, null);
        String[] parts = token.split("\\.", -1);
        // Flip a character mid-signature. The final base64 character of a 256-byte RSA signature
        // carries only a few significant bits, so mutating it can decode to identical bytes and
        // still verify; a middle character always changes the signature.
        int middle = parts[2].length() / 2;
        char original = parts[2].charAt(middle);
        String flipped = parts[2].substring(0, middle)
                + (original == 'A' ? 'B' : 'A')
                + parts[2].substring(middle + 1);

        assumeRole(parts[0] + "." + parts[1] + "." + flipped)
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));
    }

    @Test
    @Order(6)
    void rejectsWrongAudience() {
        String token = mintToken(NAMESPACE, SERVICE_ACCOUNT, "some.other.audience");

        assumeRole(token)
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));
    }

    @Test
    @Order(7)
    void rejectsUnsignedTokenNamingAFlociIssuer() {
        // An unsigned "header.payload." token must not slip through the opaque-token path: its
        // issuer is one Floci hosts, so it has to be validated and rejected, not accepted.
        String token = mintToken(NAMESPACE, SERVICE_ACCOUNT, null);
        String[] parts = token.split("\\.", -1);

        assumeRole(parts[0] + "." + parts[1] + ".")
        .then()
            .statusCode(400)
            .body(containsString("InvalidIdentityToken"));
    }

    @Test
    @Order(8)
    void opaqueThirdPartyTokenRemainsAccepted() {
        // Compatibility guarantee: Floci cannot adjudicate an issuer it does not host, so the
        // historical permissive behaviour is preserved rather than failing the call.
        assumeRole("dummy-token")
        .then()
            .statusCode(200)
            .body(containsString("<AccessKeyId>ASIA"))
            .body(containsString("<SubjectFromWebIdentityToken>web-identity-subject"));
    }

    @Test
    @Order(9)
    void jwksAndDiscoveryEndpointsServeTheSigningKey() {
        given()
        .when()
            .get("/_floci/eks/clusters/" + CLUSTER + "/oidc/.well-known/openid-configuration")
        .then()
            .statusCode(200)
            .body("issuer", equalTo(issuer))
            .body("id_token_signing_alg_values_supported[0]", equalTo("RS256"));

        given()
        .when()
            .get("/_floci/eks/clusters/" + CLUSTER + "/oidc/keys")
        .then()
            .statusCode(200)
            .body("keys[0].kty", equalTo("RSA"))
            .body("keys[0].alg", equalTo("RS256"))
            .body("keys[0].n", notNullValue())
            .body("keys[0].e", equalTo("AQAB"));
    }

    @Test
    @Order(10)
    void mintRejectsMissingServiceAccount() {
        given()
            .contentType(JSON)
            .body("{\"namespace\":\"" + NAMESPACE + "\"}")
        .when()
            .post("/_floci/eks/clusters/" + CLUSTER + "/oidc-token")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(11)
    void mintRejectsUnknownCluster() {
        given()
            .contentType(JSON)
            .body("{\"namespace\":\"" + NAMESPACE + "\",\"serviceAccount\":\"" + SERVICE_ACCOUNT + "\"}")
        .when()
            .post("/_floci/eks/clusters/no-such-cluster/oidc-token")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(12)
    void deletingClusterDropsSigningKey() {
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);

        given()
        .when()
            .get("/_floci/eks/clusters/" + CLUSTER + "/oidc/keys")
        .then()
            .statusCode(404);

        // Cleanup runs as the last ordered step rather than in @AfterAll: Quarkus has already shut
        // the test HTTP server down by then, so an @AfterAll request fails with a connection reset.
        given()
            .contentType(FORM)
            .formParam("Action", "DeleteRole")
            .formParam("Version", "2010-05-08")
            .formParam("RoleName", ROLE)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
