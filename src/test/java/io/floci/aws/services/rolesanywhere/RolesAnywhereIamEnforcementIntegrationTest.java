package io.floci.aws.services.rolesanywhere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Roles Anywhere routes are rewritten onto an internal prefix before the IAM filter runs, so this proves the rewrite
 * does not take a request out of IAM's reach: a key with no policy is refused on the same paths a key with
 * {@code rolesanywhere:*} is allowed through, and a grant scoped to one trust anchor's ARN reaches that anchor alone.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RolesAnywhereIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "741686000041";
    private static final String ARN_PREFIX = "arn:aws:rolesanywhere:us-east-1:" + ACCOUNT + ":";
    private static final String SCOPED_ALIAS = "iam-scoped-" + UUID.randomUUID();

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup cleanup = new PartitionCleanup();

    @RegisterExtension
    final AfterAllCallback classCleanup = cleanup::afterEach;

    private String deniedKey;
    private String allowedKey;
    private String scopedAnchorId;
    private String otherAnchorId;

    @BeforeAll
    void createKeysAndAnchors() {
        scopedAnchorId = createAnchor(auth(ACCOUNT, "rolesanywhere"), "iam-scoped", SCOPED_ALIAS);
        otherAnchorId = createAnchor(auth(ACCOUNT, "rolesanywhere"), "iam-other", null);
        deniedKey = userWithPolicy(null);
        allowedKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"rolesanywhere:*","Resource":"*"}]}
                """);
    }

    @Test
    void aKeyWithoutAPolicyCannotCreateOrReadATrustAnchor() {
        given().header("Authorization", auth(deniedKey, "rolesanywhere"))
                .contentType("application/json")
                .body(anchorBody("iam-denied", null))
                .when().post("/trustanchors")
                .then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));

        given().header("Authorization", auth(deniedKey, "rolesanywhere"))
                .when().get("/trustanchor/" + scopedAnchorId)
                .then().statusCode(403);

        given().header("Authorization", auth(deniedKey, "rolesanywhere"))
                .when().get("/profiles")
                .then().statusCode(403);
    }

    @Test
    void aKeyWithRolesAnywhereAllowCreatesAndReadsATrustAnchor() {
        String id = createAnchor(auth(allowedKey, "rolesanywhere"), "iam-allowed", null);
        cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "rolesanywhere"))
                .delete("/trustanchor/" + id));

        given().header("Authorization", auth(allowedKey, "rolesanywhere"))
                .when().get("/trustanchor/" + id)
                .then().statusCode(200)
                .body("trustAnchor.name", equalTo("iam-allowed"));

        given().header("Authorization", auth(allowedKey, "rolesanywhere"))
                .when().get("/trustanchors")
                .then().statusCode(200);
    }

    @Test
    void aPolicyScopedToOneTrustAnchorReadsThatAnchorAndNotAnother() {
        String scoped = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"rolesanywhere:GetTrustAnchor",
                "Resource":"arn:aws:rolesanywhere:us-east-1:ACCOUNT:trust-anchor/SCOPED"}]}
                """.replace("ACCOUNT", ACCOUNT).replace("SCOPED", scopedAnchorId));

        given().header("Authorization", auth(scoped, "rolesanywhere"))
                .when().get("/trustanchor/" + scopedAnchorId)
                .then().statusCode(200)
                .body("trustAnchor.trustAnchorId", equalTo(scopedAnchorId));

        given().header("Authorization", auth(scoped, "rolesanywhere"))
                .when().get("/trustanchor/" + otherAnchorId)
                .then().statusCode(403);

        // The same grant holds when the path names the anchor by its ARN or its alias ARN, as the handler reads it.
        given().header("Authorization", auth(scoped, "rolesanywhere")).urlEncodingEnabled(false)
                .when().get("/trustanchor/" + ARN_PREFIX + "trust-anchor/" + scopedAnchorId)
                .then().statusCode(200)
                .body("trustAnchor.trustAnchorId", equalTo(scopedAnchorId));

        given().header("Authorization", auth(scoped, "rolesanywhere")).urlEncodingEnabled(false)
                .when().get("/trustanchor/" + ARN_PREFIX + "alias/trust-anchor/" + SCOPED_ALIAS)
                .then().statusCode(200)
                .body("trustAnchor.trustAnchorId", equalTo(scopedAnchorId));

        given().header("Authorization", auth(scoped, "rolesanywhere")).urlEncodingEnabled(false)
                .when().get("/trustanchor/" + ARN_PREFIX + "trust-anchor/" + otherAnchorId)
                .then().statusCode(403);
    }

    private String createAnchor(String authorization, String name, String aliasName) {
        String id = given().port(baseUri.getPort()).header("Authorization", authorization)
                .contentType("application/json")
                .body(anchorBody(name, aliasName))
                .when().post("/trustanchors")
                .then().statusCode(201)
                .extract().path("trustAnchor.trustAnchorId");
        if (authorization.equals(auth(ACCOUNT, "rolesanywhere"))) {
            cleanup.register(() -> given().port(baseUri.getPort()).header("Authorization", authorization)
                    .delete("/trustanchor/" + id));
        }
        return id;
    }

    private static String anchorBody(String name, String aliasName) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("name", name);
        if (aliasName != null) {
            body.put("aliasName", aliasName);
        }
        ObjectNode source = body.putObject("source");
        source.put("sourceType", "CERTIFICATE_BUNDLE");
        source.putObject("sourceData").put("x509CertificateData", RolesAnywhereTestMaterial.CA_PEM);
        return body.toString();
    }

    private String userWithPolicy(String policy) {
        String user = "rolesanywhere-iam-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "rolesanywhere",
                    policy);
        }
        return key;
    }
}
