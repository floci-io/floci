package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * {@code ChangePassword} over the wire, which is the only IAM operation that acts on the caller
 * and gives no way to name anyone else.
 *
 * <p>Driven through the whole stack because two of the things worth checking live outside the
 * handler. The caller is read from the signing access key, so a request signed with a key that
 * belongs to a user has to reach that user and nobody else. And the action name is shared with
 * Cognito, so a request that arrives without a usable credential scope has to be routed to IAM by
 * {@link AwsQueryController}: Cognito is further down that chain and answers
 * {@code UnsupportedOperation} to every form request, so the routing is what decides whether the
 * operation exists at all on that path.
 *
 * <p>Runs in an account of its own, like the other IAM classes that set account-wide state: the
 * emulator is shared, and an account password policy left visible to another class changes what
 * that class's passwords are held to.
 */
@QuarkusTest
class IamChangePasswordIntegrationTest {

    private static final String ACCOUNT = "000000000911";
    private static final String REGION = "us-east-1";
    private static final String PASSWORD = "Sup3r$ecret!";
    private static final String NEW_PASSWORD = "An0ther$ecret!";
    /** Captured: what a wrong old password is refused with, both of the causes named. */
    private static final String WRONG_OLD_PASSWORD = "Either the new password does not conform to "
            + "the account password policy or the old password was incorrect.";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void theSignerChangesItsOwnPassword() {
        String accessKeyId = userWithAConsolePassword("changes-own");

        signedAs(accessKeyId, Map.of("OldPassword", PASSWORD, "NewPassword", NEW_PASSWORD))
                .statusCode(200)
                .body("ChangePasswordResponse.ResponseMetadata.RequestId", not(equalTo("")));

        // Nothing echoes a password back, so the proof the write took is the next call: the old
        // password is no longer the current one and the new one is.
        signedAs(accessKeyId, Map.of("OldPassword", PASSWORD, "NewPassword", "Th1rd$ecret!"))
                .statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
        signedAs(accessKeyId, Map.of("OldPassword", NEW_PASSWORD, "NewPassword", "Th1rd$ecret!"))
                .statusCode(200);
    }

    @Test
    void aSignerWithNoConsolePasswordIsNoSuchEntity() {
        String accessKeyId = userWithoutAConsolePassword("no-profile");

        signedAs(accessKeyId, Map.of("OldPassword", PASSWORD, "NewPassword", NEW_PASSWORD))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void anAccountCredentialIsInvalidUserType() {
        // The account's own credential stands in for the root user, which the CLI reference says
        // this operation answers InvalidUserType for.
        admin("ChangePassword", Map.of("OldPassword", PASSWORD, "NewPassword", NEW_PASSWORD))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidUserType"));
    }

    @Test
    void anUnsignedRequestIsAnsweredByIamRatherThanCognito() {
        // No credential scope, so the service comes from the action name alone. Cognito also
        // publishes ChangePassword; reaching it would answer UnsupportedOperation instead.
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ChangePassword")
                .formParam("Version", "2010-05-08")
                .formParam("OldPassword", PASSWORD)
                .formParam("NewPassword", NEW_PASSWORD)
            .when().post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidUserType"))
                .body(not(containsString("UnsupportedOperation")));
    }

    @Test
    void anAbsentMemberNamesItself() {
        String accessKeyId = userWithAConsolePassword("absent-member");

        signedAs(accessKeyId, Map.of("NewPassword", NEW_PASSWORD))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", containsString("'oldPassword'"));
    }

    /**
     * The two halves of the refusal answer differently, which is what the capture of 2026-10-31
     * settles and what it leaves alone: a new password the account policy refuses is told which
     * rule it broke, where a wrong old password gets the captured sentence, which names both
     * causes without saying which one applied.
     */
    @Test
    void theNewPasswordIsHeldToTheAccountPasswordPolicy() {
        String accessKeyId = userWithAConsolePassword("policy");
        admin("UpdateAccountPasswordPolicy", Map.of("MinimumPasswordLength", "20",
                "RequireSymbols", "true", "RequireNumbers", "true")).statusCode(200);
        cleanup.register(() -> admin("DeleteAccountPasswordPolicy", Map.of()).statusCode(200));

        signedAs(accessKeyId, Map.of("OldPassword", PASSWORD, "NewPassword", "sh0rt!"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("PasswordPolicyViolation"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message", containsString("a minimum length of 20"));

        signedAs(accessKeyId,
                Map.of("OldPassword", "N0tTheOne!", "NewPassword", "L0ngEn0ugh$ecret!!!!"))
                .statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message", equalTo(WRONG_OLD_PASSWORD));

        signedAs(accessKeyId,
                Map.of("OldPassword", PASSWORD, "NewPassword", "L0ngEn0ugh$ecret!!!!"))
                .statusCode(200);
    }

    /** A user holding a console password, with an access key to sign its own requests with. */
    private String userWithAConsolePassword(String label) {
        String userName = user(label);
        admin("CreateLoginProfile", Map.of("UserName", userName, "Password", PASSWORD))
                .statusCode(200);
        cleanup.register(() -> admin("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
        return accessKeyFor(userName);
    }

    /** The same, minus the console password: an IAM user with nothing to change. */
    private String userWithoutAConsolePassword(String label) {
        return accessKeyFor(user(label));
    }

    private String user(String label) {
        String userName = "change-password-" + label;
        admin("CreateUser", Map.of("UserName", userName)).statusCode(200);
        cleanup.register(() -> admin("DeleteUser", Map.of("UserName", userName)).statusCode(200));
        return userName;
    }

    private String accessKeyFor(String userName) {
        String accessKeyId = admin("CreateAccessKey", Map.of("UserName", userName)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey"
                        + ".AccessKeyId");
        cleanup.register(() -> admin("DeleteAccessKey",
                Map.of("UserName", userName, "AccessKeyId", accessKeyId)).statusCode(200));
        return accessKeyId;
    }

    private ValidatableResponse signedAs(String accessKeyId, Map<String, String> params) {
        return iam(accessKeyId, "ChangePassword", params);
    }

    private ValidatableResponse admin(String action, Map<String, String> params) {
        return iam(ACCOUNT, action, params);
    }

    private static ValidatableResponse iam(String accessKeyId, String action,
                                           Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20261012/" + REGION + "/iam/aws4_request, SignedHeaders=host, "
                        + "Signature=abc")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}
