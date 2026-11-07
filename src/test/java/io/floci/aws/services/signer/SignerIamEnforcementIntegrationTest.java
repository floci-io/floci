package io.floci.aws.services.signer;

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
 * Signer routes are rewritten onto an internal prefix before the IAM filter runs, so this proves
 * the rewrite does not take a request out of IAM's reach: a key with no policy is refused on the
 * same paths a key with {@code signer:*} is allowed through.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignerIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "741686000030";
    /** Signer profile names are [0-9a-zA-Z_] only, so no hyphen here. */
    private static final String PROFILE = "IamProbe";

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup cleanup = new PartitionCleanup();

    @RegisterExtension
    final AfterAllCallback classCleanup = cleanup::afterEach;

    private String deniedKey;
    private String allowedKey;

    @BeforeAll
    void createKeys() {
        deniedKey = userWithPolicy(null);
        allowedKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"signer:*","Resource":"*"}]}
                """);
    }

    @Test
    void aKeyWithoutAPolicyCannotCreateOrReadAProfile() {
        given().header("Authorization", auth(deniedKey, "signer"))
                .contentType("application/json")
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .when().put("/signing-profiles/IamDenied")
                .then().statusCode(403);

        given().header("Authorization", auth(deniedKey, "signer"))
                .when().get("/signing-profiles/" + PROFILE)
                .then().statusCode(403);
    }

    @Test
    void aKeyWithSignerAllowCreatesAndReadsAProfile() {
        given().header("Authorization", auth(allowedKey, "signer"))
                .contentType("application/json")
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .when().put("/signing-profiles/" + PROFILE)
                .then().statusCode(200);

        given().header("Authorization", auth(allowedKey, "signer"))
                .when().get("/signing-profiles/" + PROFILE)
                .then().statusCode(200)
                .body("profileName", equalTo(PROFILE))
                .body("status", equalTo("Active"));
    }

    private String userWithPolicy(String policy) {
        String user = "signer-iam-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), user)
                .accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "signer", policy);
        }
        return key;
    }
}
