package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.Session;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
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

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
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
 * <p>The developer guide's permissions table gives the rest: {@code ReEncrypt} is checked as
 * {@code kms:ReEncryptFrom} on the key it decrypts with, its {@code SourceKeyId} or else the key
 * that encrypted its {@code CiphertextBlob}, and {@code kms:ReEncryptTo} on the key it encrypts
 * with; {@code CreateAlias} on the alias and the key, {@code UpdateAlias} on the alias, the new
 * key and the key the alias points at now, {@code DeleteAlias} on the alias and its key; and
 * {@code ReplicateKey} as {@code kms:ReplicateKey} on the key and {@code kms:CreateKey}.
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
    void rotationHistoryUsesTheNamedKeyForAuthorization() {
        String allowedArn = createKey();
        String otherArn = createKey();
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:ListKeyRotations","Resource":"%s"}]}"""
                .formatted(allowedArn));

        keyCall(session, "ListKeyRotations", "{\"KeyId\":\"" + allowedArn + "\"}")
                .then().statusCode(200).body("Truncated", equalTo(false));
        keyCall(session, "ListKeyRotations", "{\"KeyId\":\"" + otherArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
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
    void aDecryptWhoseCiphertextNamesNoKeyIsCheckedOnStar() {
        Session onKeys = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Decrypt","Resource":"%s*"}]}"""
                .formatted(KEY_ARN_PREFIX));
        Session onEverything = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:Decrypt","Resource":"*"}]}""");

        // A ciphertext no stored key answers to names no key, so the request is checked on *, which a policy
        // granting every resource covers and the handler then refuses.
        keyCall(onKeys, "Decrypt", "{\"CiphertextBlob\": \"AAAA\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(onEverything, "Decrypt", "{\"CiphertextBlob\": \"AAAA\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidCiphertextException"));
    }

    /**
     * A caller allowed on one key submits ciphertext another, denied key encrypted and
     * carries a KeyId naming the allowed one, which the handler never reads. The source
     * {@code kms:ReEncryptFrom} is checked on is the key that encrypted the ciphertext, so the
     * denied key's data does not come out re-encrypted under the allowed one.
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
                  {"Effect":"Allow","Action":"kms:ReEncrypt*","Resource":"%s*"},
                  {"Effect":"Deny","Action":"kms:ReEncryptFrom","Resource":"%s"}]}"""
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
     * ReEncrypt is checked as the two actions AWS authorizes it by, each on its own key: a
     * caller allowed to re-encrypt from one key and to another may move data only that way, a
     * policy naming neither is refused, and no permission named {@code kms:ReEncrypt} exists to
     * grant it.
     */
    @Test
    void aReEncryptNeedsReEncryptFromOnItsSourceAndReEncryptToOnItsDestination() {
        String sourceArn = createKey();
        String destinationArn = createKey();
        String ciphertext = accountCall("Encrypt", encryptBody(sourceArn))
                .then().statusCode(200).extract().path("CiphertextBlob");
        String reEncrypt = "{\"CiphertextBlob\": \"" + ciphertext + "\", \"SourceKeyId\": \"" + sourceArn
                + "\", \"DestinationKeyId\": \"" + destinationArn + "\"}";
        Session oneWay = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:ReEncryptFrom","Resource":"%s"},
                  {"Effect":"Allow","Action":"kms:ReEncryptTo","Resource":"%s"}]}"""
                .formatted(sourceArn, destinationArn));
        Session backwards = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:ReEncryptFrom","Resource":"%s"},
                  {"Effect":"Allow","Action":"kms:ReEncryptTo","Resource":"%s"}]}"""
                .formatted(destinationArn, sourceArn));
        Session byItsName = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:ReEncrypt","Resource":"*"}]}""");

        keyCall(backwards, "ReEncrypt", reEncrypt)
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(byItsName, "ReEncrypt", reEncrypt)
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(oneWay, "ReEncrypt", reEncrypt)
                .then().statusCode(200).body("KeyId", equalTo(destinationArn)).body("SourceKeyId", equalTo(sourceArn));
    }

    /**
     * A RetireGrant naming its grant by its token alone names no key the table reads, so it is
     * checked on {@code *}: a policy granting retirement on every resource allows it, one scoped
     * to the grant's key does not.
     */
    @Test
    void aTokenOnlyRetireGrantIsCheckedOnStar() {
        String keyArn = createKey();
        Grant refused = createGrantOn(keyArn);
        Grant retired = createGrantOn(keyArn);
        Session onTheGrantsKey = assumeRoleWith(grantOperationOn("kms:RetireGrant", keyArn));
        Session onEverything = assumeRoleWith(grantOperationOn("kms:RetireGrant", "*"));

        keyCall(onTheGrantsKey, "RetireGrant", "{\"GrantToken\": \"" + refused.token() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(onEverything, "RetireGrant", "{\"GrantToken\": \"" + retired.token() + "\"}")
                .then().statusCode(200);
        accountCall("ListGrants", "{\"KeyId\": \"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1));
    }

    /**
     * The alias calls need their action on the alias and on each key the permissions table
     * names: CreateAlias on the key it associates, UpdateAlias on the new key and on the key the
     * alias points at now, DeleteAlias on the key the alias points at.
     */
    @Test
    void theAliasCallsNeedTheirActionOnTheAliasAndItsKeys() {
        String currentArn = createKey();
        String newArn = createKey();
        String aliasName = "alias/" + UUID.randomUUID().toString().substring(0, 8);
        String aliasArn = ALIAS_ARN_PREFIX + aliasName.substring("alias/".length());
        String create = "{\"AliasName\": \"" + aliasName + "\", \"TargetKeyId\": \"" + currentArn + "\"}";
        Session aliasOnly = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kms:CreateAlias","kms:UpdateAlias","kms:DeleteAlias"],
                   "Resource":"%s"}]}""".formatted(aliasArn));
        Session withKeys = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kms:CreateAlias","kms:UpdateAlias","kms:DeleteAlias"],
                   "Resource":["%s","%s"]}]}""".formatted(aliasArn, currentArn));
        Session withBothKeys = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kms:UpdateAlias"],"Resource":["%s","%s","%s"]}]}"""
                .formatted(aliasArn, currentArn, newArn));

        keyCall(aliasOnly, "CreateAlias", create)
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(withKeys, "CreateAlias", create).then().statusCode(200);
        cleanup.register(() -> accountCall("DeleteAlias", "{\"AliasName\": \"" + aliasName + "\"}"));
        String update = "{\"AliasName\": \"" + aliasName + "\", \"TargetKeyId\": \"" + newArn + "\"}";
        keyCall(withKeys, "UpdateAlias", update)
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(withBothKeys, "UpdateAlias", update).then().statusCode(200);
        keyCall(withKeys, "DeleteAlias", "{\"AliasName\": \"" + aliasName + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(withBothKeys, "UpdateAlias",
                "{\"AliasName\": \"" + aliasName + "\", \"TargetKeyId\": \"" + currentArn + "\"}")
                .then().statusCode(200);
        keyCall(withKeys, "DeleteAlias", "{\"AliasName\": \"" + aliasName + "\"}").then().statusCode(200);
    }

    /** ReplicateKey also needs kms:CreateKey, which the permissions table lists beside kms:ReplicateKey. */
    @Test
    void aReplicateKeyNeedsCreateKeyBesideReplicateKey() {
        String keyArn = createKey();
        Session session = assumeRoleWith(grantOperationOn("kms:ReplicateKey", keyArn));

        keyCall(session, "ReplicateKey", "{\"KeyId\": \"" + keyArn + "\", \"ReplicaRegion\": \"us-west-2\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("kms:CreateKey"));
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

    /**
     * A dry run needs the permission the call does: the key the policy allows answers
     * DryRunOperationException, and another key AccessDeniedException, and neither dry run creates a grant.
     */
    @Test
    void aDryRunIsAuthorizedAsTheCallIs() {
        String allowedArn = createKey();
        String otherArn = createKey();
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kms:Encrypt","kms:CreateGrant"],"Resource":"%s"}]}"""
                .formatted(allowedArn));

        keyCall(session, "Encrypt", dryRun(encryptBody(allowedArn)))
                .then().statusCode(400).body("__type", equalTo("DryRunOperationException"));
        keyCall(session, "Encrypt", dryRun(encryptBody(otherArn)))
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        keyCall(session, "CreateGrant", dryRun(grantBody(allowedArn)))
                .then().statusCode(400).body("__type", equalTo("DryRunOperationException"));
        keyCall(session, "CreateGrant", dryRun(grantBody(otherArn)))
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));

        accountCall("ListGrants", "{\"KeyId\": \"" + allowedArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(0));
        accountCall("ListGrants", "{\"KeyId\": \"" + otherArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(0));
    }

    /** {@code body} with {@code "DryRun": true} added. */
    private static String dryRun(String body) {
        return RestAssuredJsonUtils.withMember(body, "DryRun", "true");
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

    /** A CreateGrant request giving a user of the account Encrypt on {@code keyArn}. */
    private static String grantBody(String keyArn) {
        return "{\"KeyId\": \"" + keyArn + "\", \"GranteePrincipal\": \"arn:aws:iam::" + ACCOUNT
                + ":user/grantee\", \"Operations\": [\"Encrypt\"]}";
    }

    /** The id and the token of a grant the account itself creates on {@code keyArn}. */
    private record Grant(String id, String token) {}

    private Grant createGrantOn(String keyArn) {
        Response created = accountCall("CreateGrant", grantBody(keyArn));
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
