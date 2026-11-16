package io.floci.aws.services.iam;

import io.floci.aws.core.common.Totp;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The two MFA condition keys, and the device a session is minted behind.
 *
 * <p>{@code aws:MultiFactorAuthPresent} says "whether multi-factor authentication (MFA) was used to
 * validate the temporary security credentials that made the request", and both it and
 * {@code aws:MultiFactorAuthAge} are "included in the request context only when the principal uses
 * temporary credentials to make the request". A session minted without a device still carries the
 * first one: "In these cases, the aws:MultiFactorAuthPresent key is present in the request and set
 * to a value of false." A long-term key carries neither, which is why the guide recommends
 * {@code BoolIfExists} for a deny and warns that plain {@code Bool} on false "is not a reliable way
 * to check whether a request is authenticated using MFA".
 *
 * <p>The device reaches a session through {@code SerialNumber} and {@code TokenCode} on
 * {@code GetSessionToken} and {@code AssumeRole}. A trust policy may require it, AWS's own example
 * being {@code "Bool": {"aws:MultiFactorAuthPresent": true}}, and then "if the caller does not
 * include valid MFA information, the request to assume the role is denied".
 *
 * <p>The two operations do not carry the authentication equally far, and AWS draws the line
 * itself. "Because temporary credentials returned by GetSessionToken include MFA information, you
 * can check for MFA in individual API operations made by the credentials", while "the temporary
 * credentials returned by AssumeRole do not include MFA information in the context, so you cannot
 * check individual API operations for MFA": the device decides the assume and stops there, since
 * "once the user has assumed the role, no further MFA checks are made".
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class StsMfaEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    private static final String CODE = "ErrorResponse.Error.Code";

    /** Allows the listing only where the request was authenticated with a device. */
    private static final String LIST_WITH_MFA = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["iam:ListUsers","sts:GetSessionToken","sts:AssumeRole"],
               "Resource":"*","Condition":{"Bool":{"aws:MultiFactorAuthPresent":"true"}}},
              {"Effect":"Allow","Action":["sts:GetSessionToken","sts:AssumeRole"],"Resource":"*"}]}""";

    /** The guide's recommended shape: an allow, and a deny for anything not MFA-authenticated. */
    private static final String LIST_DENIED_WITHOUT_MFA = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["iam:ListUsers","sts:GetSessionToken"],"Resource":"*"},
              {"Effect":"Deny","Action":"iam:ListUsers","Resource":"*",
               "Condition":{"BoolIfExists":{"aws:MultiFactorAuthPresent":"false"}}}]}""";

    /** Allows the assume only where the request that makes it was authenticated with a device. */
    private static final String ASSUME_WITH_MFA = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*",
               "Condition":{"Bool":{"aws:MultiFactorAuthPresent":"true"}}}]}""";

    /** Allows the listing only while the authentication is recent. */
    private static final String LIST_WHILE_FRESH = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["iam:ListUsers","sts:GetSessionToken"],"Resource":"*",
               "Condition":{"NumericLessThan":{"aws:MultiFactorAuthAge":"3600"}}},
              {"Effect":"Allow","Action":"sts:GetSessionToken","Resource":"*"}]}""";

    @Test
    void aSessionMintedBehindADeviceIsTheOneThatCarriesThePresentKeyAsTrue() {
        String userName = unique("mfa-present");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);

        // The same user, the same policy, two sessions: the key is the only difference between them.
        listUsers(sessionWith(user, device), 200);
        listUsers(sessionWith(user, null), 403);
        // And the long-term key itself, which publishes no key at all, so a plain Bool cannot match.
        listUsers(user, 403);
    }

    @Test
    void theDenyTheGuideRecommendsRefusesALongTermKeyAndAnMfaLessSession() {
        String userName = unique("mfa-ifexists");
        S3RequestSigner user = userWithPolicy(userName, LIST_DENIED_WITHOUT_MFA);
        Device device = enabledDeviceFor(userName);

        // "This combination of Deny, BoolIfExists, and false denies requests that are not
        // authenticated using MFA... It also denies requests that are made using long-term
        // credentials, such as AWS CLI or AWS API operations made using access keys."
        listUsers(user, 403);
        listUsers(sessionWith(user, null), 403);
        listUsers(sessionWith(user, device), 200);
    }

    @Test
    void theAgeIsPublishedOnlyWhereThereIsAnAuthenticationToMeasure() {
        String userName = unique("mfa-age");
        S3RequestSigner user = userWithPolicy(userName, LIST_WHILE_FRESH);
        Device device = enabledDeviceFor(userName);

        // The device was just used, so the age is inside the hour the policy allows.
        listUsers(sessionWith(user, device), 200);
        // A session that used no device publishes no age, and a Numeric comparison on an absent
        // key does not match, so the same statement cannot allow it.
        listUsers(sessionWith(user, null), 403);
    }

    @Test
    void aCodeTheDeviceDidNotProduceAuthenticatesNothing() {
        String userName = unique("mfa-code");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);

        // "An incorrect MFA code causes the API to return an access denied error."
        getSessionToken(user, device.serialNumber(), "000000").statusCode(403)
                .body(CODE, equalTo("AccessDenied"));
        // A device with no code proves nothing, and a code naming no device names nothing.
        getSessionToken(user, device.serialNumber(), null).statusCode(403);
        getSessionToken(user, null, device.codeNow()).statusCode(403);
        // A device enabled for somebody else is not this caller's to present.
        Device other = enabledDeviceFor(unique("mfa-other"));
        getSessionToken(user, other.serialNumber(), other.codeNow()).statusCode(403);
    }

    @Test
    void aTrustPolicyThatTestsForMfaTakesTheDeviceTheAssumePresents() {
        String userName = unique("mfa-trust");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);
        String roleName = unique("mfa-role");
        createRoleRequiringMfa(roleName, userName);

        // "If the caller does not include valid MFA information, the request to assume the role is
        // denied", and a long-term key on its own carries none.
        assumeRole(user, roleName, null, null).statusCode(403);
        assumeRole(user, roleName, device.serialNumber(), device.codeNow()).statusCode(200);
    }

    @Test
    void aRoleSessionAssumedWithADeviceStillCarriesNoAuthenticationOfItsOwn() {
        String userName = unique("mfa-chain-user");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);
        String roleName = unique("mfa-chain-role");
        createRoleRequiringMfa(roleName, userName);

        // The device got the caller through the trust policy, and that is as far as it goes: "the
        // temporary credentials returned by AssumeRole do not include MFA information in the
        // context, so you cannot check individual API operations for MFA". The role's own policy is
        // the same MFA condition, and the session it minted cannot satisfy it.
        S3RequestSigner roleSession = assumedSession(user, roleName, device);
        listUsers(roleSession, 403);
    }

    @Test
    void aCodeThatMintedASessionMintsNoSecondOne() {
        String userName = unique("mfa-replay");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);
        String code = device.codeNow();

        getSessionToken(user, device.serialNumber(), code).statusCode(200);
        // The code is still inside the window it was generated for, and spent: RFC 6238 has a
        // verifier "MUST NOT accept the second attempt of the OTP generated for the same time
        // step", so one code is one authentication.
        getSessionToken(user, device.serialNumber(), code).statusCode(403)
                .body(CODE, equalTo("AccessDenied"));
    }

    @Test
    void thePolicyForTheAssumeItselfSeesTheDeviceTheCallPresents() {
        String userName = unique("mfa-identity");
        S3RequestSigner user = userWithPolicy(userName, ASSUME_WITH_MFA);
        Device device = enabledDeviceFor(userName);
        String roleName = unique("mfa-identity-role");
        createRoleTrustingUser(roleName, userName);

        // Nothing in the trust policy asks for MFA here: what does is the caller's own permission
        // to call the operation, which is evaluated before the operation runs. AWS denies such a
        // request when "users have not been MFA authenticated", and allows it on the device the
        // call carries.
        assumeRole(user, roleName, device.serialNumber(), device.codeNow()).statusCode(200);
        assumeRole(user, roleName, null, null).statusCode(403);
    }

    @Test
    void aSessionMintedBehindADeviceAssumesARoleThatRequiresOneWithoutPresentingItAgain() {
        String userName = unique("mfa-carry");
        S3RequestSigner user = userWithPolicy(userName, LIST_WITH_MFA);
        Device device = enabledDeviceFor(userName);
        String roleName = unique("mfa-carry-role");
        createRoleRequiringMfa(roleName, userName);

        // The session already authenticated with the device, so the assume it makes is an
        // MFA-authenticated request though it names no device of its own.
        assumeRole(sessionWith(user, device), roleName, null, null).statusCode(200);
        // The session minted without one is refused by the same trust policy.
        assumeRole(sessionWith(user, null), roleName, null, null).statusCode(403);
    }

    /** A virtual device and its seed, which is what produces the codes a client sends. */
    private record Device(String serialNumber, String seed) {

        String codeNow() {
            return Totp.codeAt(seed, Totp.stepAt(Instant.now()));
        }
    }

    private static void listUsers(S3RequestSigner caller, int status) {
        given().filter(caller).formParam("Action", "ListUsers").formParam("Version", "2010-05-08")
            .when().post("/").then().statusCode(status);
    }

    private static ValidatableResponse getSessionToken(S3RequestSigner caller, String serialNumber,
                                                       String tokenCode) {
        RequestSpecification request = given().filter(caller).formParam("Action", "GetSessionToken");
        if (serialNumber != null) {
            request = request.formParam("SerialNumber", serialNumber);
        }
        if (tokenCode != null) {
            request = request.formParam("TokenCode", tokenCode);
        }
        return request.when().post("/").then();
    }

    /** The caller's own session, minted behind {@code device} when one is given. */
    private static S3RequestSigner sessionWith(S3RequestSigner caller, Device device) {
        ExtractableResponse<Response> issued = (device == null
                ? getSessionToken(caller, null, null)
                : getSessionToken(caller, device.serialNumber(), device.codeNow()))
                .statusCode(200).extract();
        String base = "GetSessionTokenResponse.GetSessionTokenResult.Credentials.";
        return S3RequestSigner.signedAs(issued.path(base + "AccessKeyId"),
                issued.path(base + "SecretAccessKey"), issued.path(base + "SessionToken"));
    }

    private static ValidatableResponse assumeRole(S3RequestSigner caller, String roleName,
                                                  String serialNumber, String tokenCode) {
        RequestSpecification request = given().filter(caller).formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + roleName)
                .formParam("RoleSessionName", "mfa-session");
        if (serialNumber != null) {
            request = request.formParam("SerialNumber", serialNumber);
        }
        if (tokenCode != null) {
            request = request.formParam("TokenCode", tokenCode);
        }
        return request.when().post("/").then();
    }

    private static S3RequestSigner assumedSession(S3RequestSigner caller, String roleName, Device device) {
        ExtractableResponse<Response> issued =
                assumeRole(caller, roleName, device.serialNumber(), device.codeNow())
                        .statusCode(200).extract();
        String base = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return S3RequestSigner.signedAs(issued.path(base + "AccessKeyId"),
                issued.path(base + "SecretAccessKey"), issued.path(base + "SessionToken"));
    }

    /** A role only an MFA-authenticated {@code userName} may assume, holding the same condition. */
    private static void createRoleRequiringMfa(String roleName, String userName) {
        iam("CreateRole", "RoleName", roleName, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:user/%s"},"Action":"sts:AssumeRole",
                  "Condition":{"Bool":{"aws:MultiFactorAuthPresent":"true"}}}]}"""
                .formatted(ACCOUNT, userName));
        iam("PutRolePolicy", "RoleName", roleName, "PolicyName", "inline",
                "PolicyDocument", LIST_WITH_MFA);
    }

    /** A role {@code userName} may assume, with nothing in the trust policy about MFA. */
    private static void createRoleTrustingUser(String roleName, String userName) {
        iam("CreateRole", "RoleName", roleName, "AssumeRolePolicyDocument", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:user/%s"},"Action":"sts:AssumeRole"}]}"""
                .formatted(ACCOUNT, userName));
    }

    /** A virtual device created and enabled for a fresh user, the way a client sets one up. */
    private static Device enabledDeviceFor(String userName) {
        if (!userExists(userName)) {
            iam("CreateUser", "UserName", userName);
        }
        String base = "CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.";
        ExtractableResponse<Response> created = given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "CreateVirtualMFADevice")
                .formParam("VirtualMFADeviceName", userName + "-device")
            .when().post("/").then().statusCode(200).extract();
        String seed = new String(Base64.getDecoder().decode(
                created.path(base + "Base32StringSeed").toString().trim()), StandardCharsets.UTF_8);
        String serialNumber = created.path(base + "SerialNumber");
        long step = Totp.stepAt(Instant.now());
        iam("EnableMFADevice", "UserName", userName, "SerialNumber", serialNumber,
                "AuthenticationCode1", Totp.codeAt(seed, step - 1),
                "AuthenticationCode2", Totp.codeAt(seed, step));
        return new Device(serialNumber, seed);
    }

    private static boolean userExists(String userName) {
        return given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "GetUser").formParam("UserName", userName)
            .when().post("/").then().extract().statusCode() == 200;
    }

    private static S3RequestSigner userWithPolicy(String userName, String policy) {
        iam("CreateUser", "UserName", userName);
        iam("PutUserPolicy", "UserName", userName, "PolicyName", "inline", "PolicyDocument", policy);
        ExtractableResponse<Response> key = given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", "CreateAccessKey").formParam("UserName", userName)
            .when().post("/").then().statusCode(200).extract();
        String base = "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.";
        return S3RequestSigner.signedAs(key.path(base + "AccessKeyId"), key.path(base + "SecretAccessKey"));
    }

    private static void iam(String action, String... params) {
        RequestSpecification request = given().header("Authorization", auth(ACCOUNT, "iam"))
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261116/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
