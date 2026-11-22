package io.floci.aws.services.iam;

import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testutil.StsRequests.Credentials;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testutil.StsRequests.request;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which caller {@code ChangePassword} refuses with which error. The operation names nobody, so the
 * signing credential is the whole of the question, and a credential with no IAM user behind it is
 * two callers rather than one.
 *
 * <p>Recorded against a real account on 2026-10-29: an assumed-role session is answered
 * {@code NoSuchEntity} 404, where the CLI reference publishes {@code InvalidUserType} for account
 * root credentials. That recording is the whole of the change, so every other caller with no
 * stored user behind its key is here to hold its own error still. Driven through the signed
 * transport because that is the only way the handler sees a session credential at all.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class IamChangePasswordCallerTypeIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final Credentials ROOT = new Credentials("test", "test", null);
    private static final String PASSWORD = "Sup3r$ecret!";
    private static final String NEW_PASSWORD = "An0ther$ecret!";
    /** Unchanged: the sentence the CLI reference's case keeps. */
    private static final String ROOT_REFUSAL = "The request was rejected because the type of user "
            + "for the transaction was incorrect.";

    private final List<String> sessions = new ArrayList<>();
    private final List<Runnable> cleanup = new ArrayList<>();

    @Inject
    IamService iam;

    @AfterEach
    void cleanup() {
        sessions.forEach(key -> iam.unregisterSession(ACCOUNT, key));
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            cleanup.get(i).run();
        }
    }

    /** The recorded caller: an assumed role is not root, so it is not answered root's error. */
    @Test
    void anAssumedRoleSessionIsNoSuchEntity() throws Exception {
        Credentials session = assumedRoleSession();

        changePassword(session)
                .then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message",
                        equalTo("The Access Key with id " + session.key() + " cannot be found."));
    }

    /**
     * The account credential's own session is root's session: {@code InvalidUserType} is published
     * for root however root signs, so this caller keeps it. A privileged session, the one
     * {@code AssumeRoot} mints, reports the same principal as this one and so takes the same
     * branch; what that operation needs set up to answer at all is its own test's work.
     */
    @Test
    void aSessionTheAccountCredentialMintedKeepsInvalidUserType() throws Exception {
        Credentials session = credentials(
                request(ROOT, "sts", "GetSessionToken", Map.of()), "GetSessionToken");

        changePassword(session)
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidUserType"))
                .body("ErrorResponse.Error.Message", equalTo(ROOT_REFUSAL));
    }

    /**
     * A session an IAM user minted carries no stored key either, and no recording of its own. It
     * is not the recorded caller, so this change leaves it the error it already answered.
     */
    @Test
    void aSessionAnIamUserMintedKeepsInvalidUserType() throws Exception {
        Credentials user = userWithAConsolePassword("session-token");
        Credentials session = credentials(
                request(user, "sts", "GetSessionToken", Map.of()), "GetSessionToken");

        changePassword(session)
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidUserType"))
                .body("ErrorResponse.Error.Message", equalTo(ROOT_REFUSAL));
    }

    /** The account credential stands in for root, and that case is unchanged. */
    @Test
    void theAccountCredentialIsStillInvalidUserType() throws Exception {
        changePassword(ROOT)
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidUserType"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message", equalTo(ROOT_REFUSAL));
    }

    /** The control: the caller the operation is for still changes its own password. */
    @Test
    void anIamUserStillChangesItsOwnPassword() throws Exception {
        Credentials user = userWithAConsolePassword("own-password");

        changePassword(user).then().statusCode(200);
    }

    private Response changePassword(Credentials caller) throws Exception {
        return request(caller, "iam", "ChangePassword",
                Map.of("OldPassword", PASSWORD, "NewPassword", NEW_PASSWORD));
    }

    private Credentials assumedRoleSession() throws Exception {
        String role = "change-password-caller-" + UUID.randomUUID();
        Response created = request(ROOT, "iam", "CreateRole", Map.of("RoleName", role,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"));
        created.then().statusCode(200);
        String arn = created.xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        assertNotNull(arn);
        cleanup.add(() -> remove("DeleteRole", Map.of("RoleName", role)));
        Credentials session = credentials(request(ROOT, "sts", "AssumeRole",
                Map.of("RoleArn", arn, "RoleSessionName", "caller")), "AssumeRole");
        assertTrue(session.key().startsWith("ASIA"));
        return session;
    }

    private Credentials userWithAConsolePassword(String label) throws Exception {
        // Short of a UUID: a user name is 64 characters at most and the label is already long.
        String name = "cp-" + label + "-" + Long.toHexString(System.nanoTime());
        request(ROOT, "iam", "CreateUser", Map.of("UserName", name)).then().statusCode(200);
        cleanup.add(() -> remove("DeleteUser", Map.of("UserName", name)));
        request(ROOT, "iam", "CreateLoginProfile", Map.of("UserName", name, "Password", PASSWORD))
                .then().statusCode(200);
        cleanup.add(() -> remove("DeleteLoginProfile", Map.of("UserName", name)));
        Response key = request(ROOT, "iam", "CreateAccessKey", Map.of("UserName", name));
        key.then().statusCode(200);
        String prefix = "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.";
        String accessKeyId = key.xmlPath().getString(prefix + "AccessKeyId");
        cleanup.add(() -> remove("DeleteAccessKey",
                Map.of("UserName", name, "AccessKeyId", accessKeyId)));
        return new Credentials(accessKeyId, key.xmlPath().getString(prefix + "SecretAccessKey"),
                null);
    }

    private Credentials credentials(Response response, String action) {
        response.then().statusCode(200);
        String prefix = action + "Response." + action + "Result.Credentials.";
        String key = response.xmlPath().getString(prefix + "AccessKeyId");
        sessions.add(key);
        Credentials result = new Credentials(key,
                response.xmlPath().getString(prefix + "SecretAccessKey"),
                response.xmlPath().getString(prefix + "SessionToken"));
        assertNotNull(result.secret());
        assertNotNull(result.token());
        return result;
    }

    private void remove(String action, Map<String, String> params) {
        try {
            request(ROOT, "iam", action, params).then().statusCode(200);
        } catch (Exception failure) {
            throw new AssertionError("Fixture cleanup failed: " + action, failure);
        }
    }
}
