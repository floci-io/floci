package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.floci.aws.testing.EnforcementFixtures.auth;
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

    /** The key the MAC operations use, which is a MAC key and not a data-encryption one. */
    private String macKeyArn;

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
        macKeyArn = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "PaymentCryptographyControlPlane.CreateKey")
                .header("Authorization", auth(ADMIN, "payment-cryptography"))
                .body("{\"KeyAttributes\":{\"KeyAlgorithm\":\"AES_256\",\"KeyClass\":\"SYMMETRIC_KEY\","
                        + "\"KeyUsage\":\"TR31_M6_ISO_9797_5_CMAC_KEY\","
                        + "\"KeyModesOfUse\":{\"Generate\":true,\"Verify\":true}},\"Exportable\":true}")
                .when().post("/")
                .then().statusCode(200)
                    .extract().path("Key.KeyArn");
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
    @Test
    void aDenialOnOneKeyIsEnforcedWhenThatKeyIsNamed() {
        // The request names the key, so the action is checked on that key and a denial
        // limited to it holds.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:EncryptData\","
                        + "\"Resource\":\"" + keyArn + "\"}]}");

        assertEquals(403, encrypt("/keys/" + keyArn + "/encrypt").extract().statusCode());
    }

    @Test
    void anAllowOnOneKeyIsEnoughWhenThatKeyIsNamed() {
        // The policy allows the one key and nothing else, and the request names it.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"payment-cryptography:*\","
                        + "\"Resource\":\"" + keyArn + "\"}]}");

        ValidatableResponse response = encrypt("/keys/" + keyArn + "/encrypt");
        assertEquals(200, response.extract().statusCode());
        response.body("CipherText", notNullValue());
    }

    @Test
    void aKeyIsReadFromThePathWhicheverWayTheRouteIsSpelled() {
        // The route may end in a slash, and the key is named in the path either way, so the
        // action is checked on the key and a denial limited to it holds.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:EncryptData\","
                        + "\"Resource\":\"" + keyArn + "\"}]}");

        assertEquals(403, encrypt("/keys/" + keyArn + "/encrypt/").extract().statusCode());
    }

    private void denyEncryptData() {
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:EncryptData\","
                        + "\"Resource\":\"*\"}]}");
    }

    @Test
    void aDenialOnOneKeyIsEnforcedWhenTheMacNamesIt() {
        // The MAC names its key in the body, and the action is checked on that key.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:GenerateMac\","
                        + "\"Resource\":\"" + macKeyArn + "\"}]}");

        assertEquals(403, generateMac().extract().statusCode());
    }

    @Test
    void anAllowOnOneKeyIsEnoughWhenTheMacNamesIt() {
        // The policy allows the one key and nothing else, and the MAC names it.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"payment-cryptography:*\","
                        + "\"Resource\":\"" + macKeyArn + "\"}]}");

        assertEquals(200, generateMac().extract().statusCode());
    }

    @Test
    void aDenialOnOneKeyIsEnforcedWhenTheReEncryptionNamesIt() {
        // The re-encryption names the incoming key in the path and the outgoing one in the body,
        // and the action is checked on both, so a denial on either of them holds.
        EnforcementFixtures.putUserPolicy(port(), auth(ADMIN, "iam"), callerName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"payment-cryptography:ReEncryptData\","
                        + "\"Resource\":\"" + keyArn + "\"}]}");

        // The denied key is the one the path names, and the body names another.
        assertEquals(403, reEncrypt("/keys/" + keyArn + "/reencrypt", macKeyArn).extract().statusCode());
        // The denied key is the one the body names, and the path names another.
        assertEquals(403, reEncrypt("/keys/" + macKeyArn + "/reencrypt", keyArn).extract().statusCode());
    }

    /** The request is made as the caller, whose policy is what is under test. */
    private ValidatableResponse generateMac() {
        return given().contentType("application/json")
                .header("Authorization", auth(caller.accessKeyId(), "payment-cryptography"))
                .body("{\"KeyIdentifier\":\"" + macKeyArn + "\",\"MessageData\":\"" + "00".repeat(16) + "\","
                        + "\"GenerationAttributes\":{\"Algorithm\":\"CMAC\"}}")
                .when().post("/mac/generate")
                .then();
    }

    /** The request is made as the caller, whose policy is what is under test. */
    private ValidatableResponse reEncrypt(String path, String outgoingKeyArn) {
        return given().contentType("application/json")
                .header("Authorization", auth(caller.accessKeyId(), "payment-cryptography"))
                .body("{\"OutgoingKeyIdentifier\":\"" + outgoingKeyArn + "\",\"CipherText\":\"" + "00".repeat(16) + "\"}")
                .when().post(path)
                .then();
    }

    private static int port() {
        return baseUri.getPort();
    }
}
