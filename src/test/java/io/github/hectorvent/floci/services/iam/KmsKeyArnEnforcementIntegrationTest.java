package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, a KMS operation that names a key is authorized
 * against that key's ARN, resolving a key named by its id, an alias name, an alias ARN or a key
 * ARN to the key it points at, exactly as the KMS API reference resolves the request's key
 * identifier, and a {@code Decrypt} that names no {@code KeyId} against the key that encrypted
 * its {@code CiphertextBlob}. A policy can therefore allow one key and no other, as the IAM
 * User Guide's "Allow access to a KMS key" pattern scopes the policy's Resource to one key ARN,
 * and a Deny scoped to one key ARN fires for that key: the gap issue #6256 describes, where
 * every KMS call was evaluated against {@code key/*}.
 *
 * <p>The same mirror rule covers the members a handler reads that a plain {@code KeyId} does
 * not name: a {@code ReEncrypt} that names no {@code SourceKeyId} is authorized against the key
 * that encrypted its {@code CiphertextBlob}, never against a {@code KeyId} it carries, which
 * the handler ignores, and a token-only {@code RetireGrant} against the key its grant belongs
 * to, which is the key the handler retires the grant under.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class KmsKeyArnEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String KEY_ARN_PREFIX = "arn:aws:kms:us-east-1:" + ACCOUNT + ":key/";
    private static final String ALIAS_ARN_PREFIX = "arn:aws:kms:us-east-1:" + ACCOUNT + ":alias/";
    private static final String PLAINTEXT =
            Base64.getEncoder().encodeToString("kms-iam-enforcement".getBytes(StandardCharsets.UTF_8));

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aPolicyAllowingOneKeyArnAllowsThatKeyAndNoOther() {
        String allowedArn = createKey();
        String otherArn = createKey();
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kms:Encrypt","kms:DescribeKey"],"Resource":"%s"}]}"""
                .formatted(allowedArn));

        keyCall(session, "Encrypt", encryptBody(allowedArn))
                .then().statusCode(200).body("KeyId", equalTo(allowedArn));
        keyCall(session, "DescribeKey", "{\"KeyId\": \"" + allowedArn + "\"}")
                .then().statusCode(200).body("KeyMetadata.Arn", equalTo(allowedArn));
        keyCall(session, "Encrypt", encryptBody(otherArn))
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(session, "DescribeKey", "{\"KeyId\": \"" + otherArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aDenyScopedToOneKeyArnFiresOnlyForThatKey() {
        String keptArn = createKey();
        String deniedArn = createKey();
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Encrypt","Resource":"%s*"},
                  {"Effect":"Deny","Action":"kms:Encrypt","Resource":"%s"}]}"""
                .formatted(KEY_ARN_PREFIX, deniedArn));

        keyCall(session, "Encrypt", encryptBody(keptArn))
                .then().statusCode(200).body("KeyId", equalTo(keptArn));
        keyCall(session, "Encrypt", encryptBody(deniedArn))
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aKeyNamedByAnAliasIsAuthorizedAgainstTheKeyItPointsAt() {
        String keyArn = createKey();
        String aliasName = "alias/" + UUID.randomUUID().toString().substring(0, 8);
        createAlias(aliasName, keyArn);
        String aliasArn = ALIAS_ARN_PREFIX + aliasName.substring("alias/".length());
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Encrypt","Resource":"%s"}]}"""
                .formatted(keyArn));

        keyCall(session, "Encrypt", encryptBody(aliasName))
                .then().statusCode(200).body("KeyId", equalTo(keyArn));
        keyCall(session, "Encrypt", encryptBody(aliasArn))
                .then().statusCode(200).body("KeyId", equalTo(keyArn));
    }

    @Test
    void aDecryptNamingNoKeyIdIsAuthorizedAgainstTheKeyThatEncryptedTheCiphertext() {
        String keyArn = createKey();
        String otherArn = createKey();
        String ciphertext = accountCall("Encrypt", encryptBody(keyArn))
                .then().statusCode(200).extract().path("CiphertextBlob");
        Session onThatKey = assumeRoleWith(decryptOn(keyArn));
        Session onAnotherKey = assumeRoleWith(decryptOn(otherArn));

        keyCall(onThatKey, "Decrypt", "{\"CiphertextBlob\": \"" + ciphertext + "\"}")
                .then().statusCode(200)
                .body("Plaintext", equalTo(PLAINTEXT)).body("KeyId", equalTo(keyArn));
        keyCall(onAnotherKey, "Decrypt", "{\"CiphertextBlob\": \"" + ciphertext + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aDecryptNamingNoKeyTheStoreHoldsFallsBackToEveryKeyAndTheHandlerRefusesIt() {
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Decrypt","Resource":"%s*"}]}"""
                .formatted(KEY_ARN_PREFIX));

        // A ciphertext no stored key answers to names every key, so a wildcard policy still
        // matches it and the handler, not enforcement, refuses it.
        keyCall(session, "Decrypt", "{\"CiphertextBlob\": \"AAAA\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidCiphertextException"));
    }

    /**
     * A caller allowed on one key submits ciphertext another, denied key encrypted and
     * carries a KeyId naming the allowed one, which the handler never reads. The source the
     * request is authorized against is the key that encrypted the ciphertext, so the denied
     * key's data does not come out re-encrypted under the allowed one.
     */
    @Test
    void aReEncryptIsAuthorizedAgainstTheKeyThatEncryptedTheCiphertextNotAKeyIdItCarries() {
        String deniedArn = createKey();
        String allowedArn = createKey();
        String deniedCiphertext = accountCall("Encrypt", encryptBody(deniedArn))
                .then().statusCode(200).extract().path("CiphertextBlob");
        String allowedCiphertext = accountCall("Encrypt", encryptBody(allowedArn))
                .then().statusCode(200).extract().path("CiphertextBlob");
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:ReEncrypt","Resource":"%s*"},
                  {"Effect":"Deny","Action":"kms:ReEncrypt","Resource":"%s"}]}"""
                .formatted(KEY_ARN_PREFIX, deniedArn));

        keyCall(session, "ReEncrypt",
                "{\"CiphertextBlob\": \"" + deniedCiphertext + "\", \"KeyId\": \"" + allowedArn
                        + "\", \"DestinationKeyId\": \"" + allowedArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));

        keyCall(session, "ReEncrypt",
                "{\"CiphertextBlob\": \"" + allowedCiphertext + "\", \"DestinationKeyId\": \""
                        + allowedArn + "\"}")
                .then().statusCode(200).body("KeyId", equalTo(allowedArn))
                .body("SourceKeyId", equalTo(allowedArn));
    }

    /**
     * A token-only RetireGrant is authorized against the key the token's grant belongs to, the
     * key the handler finds the grant under, so a policy allowing retirement on that key alone
     * permits it and one allowing another key does not.
     */
    @Test
    void aTokenOnlyRetireGrantIsAuthorizedAgainstTheKeyItsGrantBelongsTo() {
        String keyArn = createKey();
        String otherArn = createKey();
        Grant grant = createGrantOn(keyArn);
        Session onTheGrantsKey = assumeRoleWith(grantOperationOn("kms:RetireGrant", keyArn));
        Session onAnotherKey = assumeRoleWith(grantOperationOn("kms:RetireGrant", otherArn));

        keyCall(onAnotherKey, "RetireGrant", "{\"GrantToken\": \"" + grant.token() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(onTheGrantsKey, "RetireGrant", "{\"GrantToken\": \"" + grant.token() + "\"}")
                .then().statusCode(200);
        accountCall("ListGrants", "{\"KeyId\": \"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(0));
    }

    /** A wildcard allow with a Deny on the grant's key still fires for a token-only RetireGrant. */
    @Test
    void aDenyOnTheGrantsKeyFiresForATokenOnlyRetireGrant() {
        String keyArn = createKey();
        Grant grant = createGrantOn(keyArn);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:RetireGrant","Resource":"%s*"},
                  {"Effect":"Deny","Action":"kms:RetireGrant","Resource":"%s"}]}"""
                .formatted(KEY_ARN_PREFIX, keyArn));

        keyCall(session, "RetireGrant", "{\"GrantToken\": \"" + grant.token() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        accountCall("ListGrants", "{\"KeyId\": \"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1));
    }

    /**
     * A RevokeGrant naming one key while the grant belongs to another is denied: the request is
     * authorized against both, so the pair cannot revoke a grant the named key does not hold.
     */
    @Test
    void aRevokeGrantNamingOneKeyWhileTheGrantBelongsToAnotherIsDenied() {
        String grantKeyArn = createKey();
        String otherArn = createKey();
        Grant grant = createGrantOn(grantKeyArn);
        Session onTheNamedKey = assumeRoleWith(grantOperationOn("kms:RevokeGrant", otherArn));

        keyCall(onTheNamedKey, "RevokeGrant",
                "{\"KeyId\": \"" + otherArn + "\", \"GrantId\": \"" + grant.id() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        accountCall("ListGrants", "{\"KeyId\": \"" + grantKeyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1));

        Session onTheGrantsKey = assumeRoleWith(grantOperationOn("kms:RevokeGrant", grantKeyArn));
        keyCall(onTheGrantsKey, "RevokeGrant",
                "{\"KeyId\": \"" + grantKeyArn + "\", \"GrantId\": \"" + grant.id() + "\"}")
                .then().statusCode(200);
        accountCall("ListGrants", "{\"KeyId\": \"" + grantKeyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(0));
    }

    private static String decryptOn(String keyArn) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Decrypt","Resource":"%s"}]}"""
                .formatted(keyArn);
    }

    private static String grantOperationOn(String action, String keyArn) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"%s"}]}"""
                .formatted(action, keyArn);
    }

    private static String encryptBody(String keyId) {
        return "{\"KeyId\": \"" + keyId + "\", \"Plaintext\": \"" + PLAINTEXT + "\"}";
    }

    private String createKey() {
        return accountCall("CreateKey", "{\"Description\": \"kms-iam-enforcement\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
    }

    /** The id and the token of a grant the account itself creates on {@code keyArn}. */
    private record Grant(String id, String token) {}

    private Grant createGrantOn(String keyArn) {
        Response created = accountCall("CreateGrant",
                "{\"KeyId\": \"" + keyArn + "\", \"GranteePrincipal\": \"arn:aws:iam::" + ACCOUNT
                        + ":user/grantee\", \"Operations\": [\"Encrypt\"]}");
        created.then().statusCode(200);
        return new Grant(created.jsonPath().getString("GrantId"),
                created.jsonPath().getString("GrantToken"));
    }

    private void createAlias(String aliasName, String keyArn) {
        cleanup.register(() -> accountCall("DeleteAlias", "{\"AliasName\": \"" + aliasName + "\"}")
                .then().statusCode(200));
        accountCall("CreateAlias",
                "{\"AliasName\": \"" + aliasName + "\", \"TargetKeyId\": \"" + keyArn + "\"}")
                .then().statusCode(200);
    }

    /** A session of a new role that its own account may assume, with {@code policy} inline. */
    private Session assumeRoleWith(String policy) {
        int port = baseUri.getPort();
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/",
                "kms-key-arn-" + UUID.randomUUID().toString().substring(0, 8), policy);
        return EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
    }

    /** A call as the account itself, which the cleanup also makes after RestAssured's port is reset. */
    private Response accountCall(String action, String body) {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "kms"))
                .header("X-Amz-Target", "TrentService." + action)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    private static Response keyCall(Session session, String action, String body) {
        return given().header("Authorization", auth(session.accessKeyId(), "kms"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .header("X-Amz-Target", "TrentService." + action)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }
}
