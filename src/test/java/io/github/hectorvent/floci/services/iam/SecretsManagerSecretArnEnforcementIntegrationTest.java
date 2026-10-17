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
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, a request that names a secret by its name or a partial
 * ARN is authorized against the secret's ARN, random suffix included, as AWS does. The Secrets Manager
 * user guide relies on it: a {@code secret:<name>-??????} resource "enables you to securely grant
 * permissions to a secret that doesn't yet exist".
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SecretsManagerSecretArnEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String SECRET_ARN_PREFIX = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createSecretIsAuthorizedAgainstTheNewSecretsName() {
        String prefix = "app-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:CreateSecret","Resource":"%s%s/*"}]}"""
                .formatted(SECRET_ARN_PREFIX, prefix));

        deleteSecretAfterTheTest(prefix + "/core");
        secretsCall(session, "CreateSecret", "{\"Name\": \"" + prefix + "/core\", \"SecretString\": \"x\"}")
                .then().statusCode(200);
        secretsCall(session, "CreateSecret", "{\"Name\": \"other-" + prefix + "\", \"SecretString\": \"x\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aSecretNamedByItsNameIsAuthorizedAgainstItsFullArn() {
        String name = "app-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = createSecret(name);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s%s-??????"},
                  {"Effect":"Allow","Action":"secretsmanager:DescribeSecret","Resource":"*"},
                  {"Effect":"Deny","Action":"secretsmanager:DescribeSecret","Resource":"%s"}]}"""
                .formatted(SECRET_ARN_PREFIX, name, arn));

        // The documented name-plus-suffix pattern matches a secret read by its name.
        secretsCall(session, "GetSecretValue", "{\"SecretId\": \"" + name + "\"}")
                .then().statusCode(200)
                .body("SecretString", equalTo("x"));
        // A Deny on the stored ARN, suffix included, applies when the request names the secret.
        secretsCall(session, "DescribeSecret", "{\"SecretId\": \"" + name + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aSecretNamedByAPartialArnIsAuthorizedAgainstItsFullArn() {
        String name = "app-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = createSecret(name);
        String partialArn = SECRET_ARN_PREFIX + name;
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s%s-??????"},
                  {"Effect":"Allow","Action":"secretsmanager:DescribeSecret","Resource":"*"},
                  {"Effect":"Deny","Action":"secretsmanager:DescribeSecret","Resource":"%s"}]}"""
                .formatted(SECRET_ARN_PREFIX, name, arn));

        secretsCall(session, "GetSecretValue", "{\"SecretId\": \"" + partialArn + "\"}")
                .then().statusCode(200)
                .body("SecretString", equalTo("x"));
        secretsCall(session, "DescribeSecret", "{\"SecretId\": \"" + partialArn + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void createSecretIgnoresASecretIdTheHandlerDoesNotRead() {
        String existing = "app-" + UUID.randomUUID().toString().substring(0, 8);
        createSecret(existing);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:CreateSecret","Resource":"%s%s-??????"}]}"""
                .formatted(SECRET_ARN_PREFIX, existing));

        // A grant on the existing secret does not authorize creating another one beside it.
        secretsCall(session, "CreateSecret", "{\"Name\": \"other-" + existing + "\", \"SecretId\": \""
                + existing + "\", \"SecretString\": \"x\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private String createSecret(String name) {
        deleteSecretAfterTheTest(name);
        Response response = accountCall("CreateSecret", "{\"Name\": \"" + name + "\", \"SecretString\": \"x\"}");
        response.then().statusCode(200);
        return response.path("ARN");
    }

    private void deleteSecretAfterTheTest(String name) {
        cleanup.register(() -> accountCall("DeleteSecret",
                "{\"SecretId\": \"" + name + "\", \"ForceDeleteWithoutRecovery\": true}").then().statusCode(200));
    }

    /** A session of a new role that its own account may assume, with {@code policy} inline. */
    private Session assumeRoleWith(String policy) {
        int port = baseUri.getPort();
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/",
                "secret-arn-" + UUID.randomUUID().toString().substring(0, 8), policy);
        return EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
    }

    /** A call as the account itself, which the cleanup also makes after RestAssured's port is reset. */
    private Response accountCall(String action, String body) {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }

    private static Response secretsCall(Session session, String action, String body) {
        return given().header("Authorization", auth(session.accessKeyId(), "secretsmanager"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .header("X-Amz-Target", "secretsmanager." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
