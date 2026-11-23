package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
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
 * What a password the account policy refuses is answered, over the wire and on all three
 * operations that store one.
 *
 * <p>Two of the sentences are recorded against a real account, both on {@code CreateLoginProfile}:
 * the length on 2026-10-29 and the symbol on 2026-11-11. The three character classes left carry
 * no recording and take the same shape, and so does the order a refusal picks a rule in, which no
 * recording covers either because each used a password that broke exactly one rule.
 *
 * <p>Runs in an account of its own, like the other IAM classes that set account-wide state: the
 * emulator is shared, and a password policy left visible to another class changes what that
 * class's passwords are held to.
 */
@QuarkusTest
class IamPasswordRuleWordingIntegrationTest {

    private static final String ACCOUNT = "000000000915";
    private static final String REGION = "us-east-1";
    /** Clears every rule of the policy below, so a refusal is the rule a case leaves broken. */
    private static final String CONFORMING = "L0ngEn0ugh$ecret!!!!";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Recorded: the configured minimum is substituted and nothing else in the sentence varies. */
    @Test
    void aShortPasswordIsAnsweredTheRecordedLengthSentence() {
        policyRequiring(Map.of("MinimumPasswordLength", "20"));
        String userName = user("short");

        refusal("CreateLoginProfile", userName, "Sh0rt$")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have a minimum length of 20"));

        // The refusal stores nothing, so the profile is still there to be created.
        admin("CreateLoginProfile", Map.of("UserName", userName, "Password", CONFORMING))
                .statusCode(200);
        cleanup.register(() -> admin("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
    }

    /** Recorded: wholly fixed text, naming no symbol set, where this package used to list one. */
    @Test
    void aPasswordWithNoSymbolIsAnsweredTheRecordedSymbolSentence() {
        policyRequiring(Map.of("MinimumPasswordLength", "6", "RequireSymbols", "true"));

        refusal("CreateLoginProfile", user("symbol"), "N0Symb0lsHere")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have at least one symbol"));
    }

    /** The three with no recording, each one the only rule its password breaks. */
    @Test
    void theThreeCharacterClassesTakeTheSameShape() {
        policyRequiring(Map.of("MinimumPasswordLength", "6", "RequireUppercaseCharacters", "true",
                "RequireLowercaseCharacters", "true", "RequireNumbers", "true"));

        refusal("CreateLoginProfile", user("upper"), "n0uppercase!")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have at least one uppercase letter"));
        refusal("CreateLoginProfile", user("lower"), "N0LOWERCASE!")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have at least one lowercase letter"));
        refusal("CreateLoginProfile", user("number"), "NoNumbersHere!")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have at least one number"));
    }

    /**
     * One rule and no more, which is what makes the order matter: a password breaking four of
     * them is told about the length, and the other three are not appended to the sentence.
     */
    @Test
    void aPasswordBreakingSeveralRulesIsAnsweredTheFirstOfThem() {
        policyRequiring(Map.of("MinimumPasswordLength", "20", "RequireUppercaseCharacters", "true",
                "RequireNumbers", "true", "RequireSymbols", "true"));

        refusal("CreateLoginProfile", user("several"), "short")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have a minimum length of 20"))
                .body("ErrorResponse.Error.Message", not(containsString("uppercase")))
                .body("ErrorResponse.Error.Message", not(containsString("symbol")))
                .body("ErrorResponse.Error.Message", not(containsString("number")));
    }

    /** The administrator's own reset answers what the creation does. */
    @Test
    void updateLoginProfileAnswersTheSameSentence() {
        policyRequiring(Map.of("MinimumPasswordLength", "20"));
        String userName = userWithAConsolePassword("update");

        refusal("UpdateLoginProfile", userName, "Sh0rt$")
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have a minimum length of 20"));
    }

    /**
     * The self-service half answers it too, which the capture of 2026-10-31 leaves to the model:
     * a wrong old password is {@code AccessDenied} and names both causes, where a new password the
     * policy refuses is told the rule it broke.
     */
    @Test
    void changePasswordAnswersTheSameSentence() {
        policyRequiring(Map.of("MinimumPasswordLength", "20", "RequireSymbols", "true"));
        String userName = userWithAConsolePassword("change");
        String accessKeyId = accessKeyFor(userName);

        iam(accessKeyId, "ChangePassword",
                Map.of("OldPassword", CONFORMING, "NewPassword", "N0Symb0lsHereButL0ngEnough"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("PasswordPolicyViolation"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"))
                .body("ErrorResponse.Error.Message",
                        equalTo("Password should have at least one symbol"));
    }

    /**
     * The reuse window is the one refusal here that keeps a sentence of this package's own, AWS
     * publishing no wording for a reused password and nothing recording one. Pinned so a
     * recording moves this sentence rather than arriving unnoticed.
     */
    @Test
    void aReusedPasswordKeepsThisPackagesOwnSentence() {
        policyRequiring(Map.of("MinimumPasswordLength", "6", "PasswordReusePrevention", "3"));
        String userName = userWithAConsolePassword("reuse");

        refusal("UpdateLoginProfile", userName, CONFORMING)
                .body("ErrorResponse.Error.Message",
                        equalTo("Password does not conform to the account password policy. It must"
                                + " not be the current password or one of the previous 3"
                                + " passwords."));
    }

    /** The control: a password that breaks no rule is stored, so none of the above is a blanket refusal. */
    @Test
    void aConformingPasswordIsStillStored() {
        policyRequiring(Map.of("MinimumPasswordLength", "20", "RequireUppercaseCharacters", "true",
                "RequireLowercaseCharacters", "true", "RequireNumbers", "true",
                "RequireSymbols", "true"));
        String userName = user("conforming");

        admin("CreateLoginProfile", Map.of("UserName", userName, "Password", CONFORMING))
                .statusCode(200);
        cleanup.register(() -> admin("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
    }

    /** A policy for this case alone, removed again when the case ends. */
    private void policyRequiring(Map<String, String> rules) {
        admin("UpdateAccountPasswordPolicy", rules).statusCode(200);
        cleanup.register(() -> admin("DeleteAccountPasswordPolicy", Map.of()).statusCode(200));
    }

    private ValidatableResponse refusal(String action, String userName, String password) {
        return admin(action, Map.of("UserName", userName, "Password", password))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("PasswordPolicyViolation"))
                .body("ErrorResponse.Error.Type", equalTo("Sender"));
    }

    private String user(String label) {
        String userName = "password-rule-" + label;
        admin("CreateUser", Map.of("UserName", userName)).statusCode(200);
        cleanup.register(() -> admin("DeleteUser", Map.of("UserName", userName)).statusCode(200));
        return userName;
    }

    private String userWithAConsolePassword(String label) {
        String userName = user(label);
        admin("CreateLoginProfile", Map.of("UserName", userName, "Password", CONFORMING))
                .statusCode(200);
        cleanup.register(() -> admin("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
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
