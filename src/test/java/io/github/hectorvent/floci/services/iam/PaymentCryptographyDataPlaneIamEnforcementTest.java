package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The data plane's requests are authorized by the action of their route, and a policy that
 * denies that action is enforced however the key is named.
 *
 * <p>The key of a data-plane request may be named by its identifier or by its ARN, and a
 * client sends an ARN with its colon and slash encoded. The router rewrites the request onto
 * the internal prefix, and the IAM rules are matched on the rewritten path, which is decoded.
 * So the ARN arrives as a path that holds a real slash, and a rule that stopped at the first
 * slash would not match it: the request would resolve to no action, and the denial would not
 * be enforced.
 *
 * <p>The rest of the repository holds a request to its standard: a request is refused however
 * it is sent. This is that standard for the data plane.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PaymentCryptographyDataPlaneIamEnforcementTest {

    /** The key every test works with. */
    private String keyArn;

    private String keyId;

    /** The caller whose policy the test writes. */
    private UserCredentials caller;

    /** The account the administrator acts in. */
    private static final String ADMIN = "test";

    /** The caller of each test, with a name of its own. */
    private String callerName;

    @TestHTTPResource("/")
    static URI baseUri;

    private final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeEach
    void createTheKeyAndTheCaller() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        keyArn = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "PaymentCryptographyControlPlane.CreateKey")
                .header("Authorization", auth(ADMIN, "payment-cryptography"))
                .body("{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                        + "\"KeyUsage\":\"TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY\","
                        + "\"KeyModesOfUse\":{\"Encrypt\":true,\"Decrypt\":true}},\"Exportable\":true}")
                .when().post("/")
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
        keyId = keyArn.substring(keyArn.lastIndexOf('/') + 1);
        callerName = "payment-cryptography-data-" + Long.toString(System.nanoTime(), 36);
        caller = EnforcementFixtures.createUser(cleanup, port(), auth(ADMIN, "iam"), callerName);
    }

    @Test
    void aDenialIsEnforcedWhenTheKeyIsNamedByItsIdentifier() {
        denyEncryptData();
        assertEquals(403, encrypt("/keys/" + keyId + "/encrypt").extract().statusCode());
    }

    @Test
    void aDenialIsEnforcedWhenTheKeyIsNamedByItsArn() {
        denyEncryptData();
        assertEquals(403, encrypt("/keys/" + keyArn + "/encrypt").extract().statusCode());
    }

    @Test
    void aDenialIsEnforcedWhenTheKeyIsNamedByItsEncodedArn() {
        denyEncryptData();
        String encoded = keyArn.replace(":", "%3A").replace("/", "%2F");
        assertEquals(403, encrypt("/keys/" + encoded + "/encrypt").extract().statusCode());
    }

    @Test
    void aCallerWithNoPermissionIsRefused() {
        // A policy with no statement at all: the caller may do nothing.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        assertEquals(403, encrypt("/keys/" + keyArn + "/encrypt").extract().statusCode());
    }

    @Test
    void aRequestIsServedWhenTheActionIsAllowed() {
        // The action is allowed, so the request is served and the answer carries the ciphertext.
        allowEncryptData();
        ValidatableResponse response = encrypt("/keys/" + keyArn + "/encrypt");
        assertEquals(200, response.extract().statusCode());
        response.body("CipherText", notNullValue());
    }

    /** The request is made as the caller, whose policy is what is under test. */
    private ValidatableResponse encrypt(String path) {
        return given().contentType("application/json")
                .header("Authorization", auth(caller.accessKeyId(), "payment-cryptography"))
                .body("{\"PlainText\":\"" + "00".repeat(16) + "\","
                        + "\"EncryptionAttributes\":{\"Symmetric\":{\"Mode\":\"CBC\"}}}")
                .when().post(path)
                .then();
    }

    /** A policy that allows the one action of the route. */
    private void allowEncryptData() {
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"payment-cryptography:EncryptData\","
                        + "\"Resource\":\"*\"}]}");
    }

    /** A policy that allows everything and denies the one action of the route. */
    private void denyEncryptData() {
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:EncryptData\","
                        + "\"Resource\":\"*\"}]}");
    }

    private static int port() {
        return baseUri.getPort();
    }
}
