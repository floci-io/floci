package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A policy variable resolved on the real request path, rather than against a context handed
 * straight to the evaluator. {@code aws:PrincipalAccount} is one of the keys the enforcement
 * filter already builds for every request, and a bucket named after the account that owns it is
 * what AWS's own examples scope this way.
 *
 * <p>Runs in an account of its own: the emulator is shared, and both buckets here are named after
 * an account id, which is the whole point of the grant being tested.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PolicyVariableEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000777";
    private static final String OTHER_ACCOUNT_ID = "999988887777";
    private static final String PASSWORD = "Sup3r$ecret!";
    private static final String NEW_PASSWORD = "An0ther$ecret!";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    /** Allows listing the one bucket whose name ends in the caller's own account id. */
    private static String allowingOwnAccountBucket(String version, String prefix) {
        return """
                {"Version":"%s","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket",
                   "Resource":"arn:aws:s3:::%s-${aws:PrincipalAccount}"}]}"""
                .formatted(version, prefix);
    }

    @Test
    void aResourceVariableBindsTheGrantToTheCallersOwnAccount() {
        String prefix = "policy-vars-" + suffix();
        String own = createBucket(prefix + "-" + ACCOUNT_ID);
        String other = createBucket(prefix + "-" + OTHER_ACCOUNT_ID);

        String accessKeyId = userWith(allowingOwnAccountBucket("2012-10-17", prefix));

        list(accessKeyId, own).statusCode(200);
        list(accessKeyId, other).statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * The same policy under the version that predates variables, where {@code ${aws:...}} is the
     * text it is written as and names no bucket at all.
     */
    @Test
    void theSameGrantUnderTheOlderVersionNamesNoBucket() {
        String prefix = "literal-vars-" + suffix();
        String own = createBucket(prefix + "-" + ACCOUNT_ID);

        String literal = userWith(allowingOwnAccountBucket("2008-10-17", prefix));
        list(literal, own).statusCode(403).body(containsString("AccessDenied"));

        // The bucket is reachable with the account spelled out, so it is the variable that is
        // inert under the older version and not the grant or the bucket.
        String spelledOut = userWith("""
                {"Version":"2008-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"arn:aws:s3:::%s"}]}"""
                .formatted(own));
        list(spelledOut, own).statusCode(200);
    }

    /**
     * The policy the change exists for: AWS's own {@code IAMUserChangePassword}, attached as the
     * managed policy floci vendors rather than retyped, so a drift in what is vendored fails here
     * too. It scopes {@code iam:ChangePassword} to {@code arn:aws:iam::*:user/${aws:username}} and
     * to {@code arn:aws:iam::*:user/*}{@code /${aws:username}}, the second for a user under a
     * path, so both forms are called.
     */
    @Test
    void theVendoredChangePasswordPolicyBindsToTheCallersOwnUser() {
        Caller flat = consoleUser(null);
        attachChangePassword(flat.userName());
        changePassword(flat.accessKeyId(), PASSWORD, NEW_PASSWORD).statusCode(200);

        Caller underAPath = consoleUser("/eng/");
        attachChangePassword(underAPath.userName());
        changePassword(underAPath.accessKeyId(), PASSWORD, NEW_PASSWORD).statusCode(200);

        // Without the policy the same call is refused, so it is the grant that resolves the
        // variable and not enforcement letting the operation through.
        Caller ungranted = consoleUser(null);
        changePassword(ungranted.accessKeyId(), PASSWORD, NEW_PASSWORD)
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * A permissions boundary resolves variables as an identity policy does, which `iam.md` claims
     * for every document the evaluator reads. The user may list any bucket; the boundary narrows
     * that to the one named after its own account.
     */
    @Test
    void aPermissionsBoundaryVariableNarrowsTheGrant() {
        String prefix = "pv-boundary-" + suffix();
        String own = createBucket(prefix + "-" + ACCOUNT_ID);
        String other = createBucket(prefix + "-" + OTHER_ACCOUNT_ID);

        String userName = "pv-bounded-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, port(),
                EnforcementFixtures.auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        iam("PutUserPolicy", Map.of("UserName", userName, "PolicyName", "wide", "PolicyDocument", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:ListBucket","Resource":"*"}]}"""))
                .statusCode(200);

        String boundaryArn = iam("CreatePolicy", Map.of("PolicyName", "pv-boundary-" + suffix(),
                "PolicyDocument", allowingOwnAccountBucket("2012-10-17", prefix)))
                .statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
        cleanup.register(() -> EnforcementFixtures.removePolicy(port(),
                EnforcementFixtures.auth(ACCOUNT_ID, "iam"), boundaryArn));
        iam("PutUserPermissionsBoundary",
                Map.of("UserName", userName, "PermissionsBoundary", boundaryArn)).statusCode(200);
        cleanup.register(() -> iam("DeleteUserPermissionsBoundary",
                Map.of("UserName", userName)).statusCode(200));

        list(accessKeyId, own).statusCode(200);
        list(accessKeyId, other).statusCode(403).body(containsString("AccessDenied"));
    }

    /** A user name with the access key that signs for it. */
    private record Caller(String userName, String accessKeyId) {}

    /** An IAM user holding a console password, under {@code path} when one is given. */
    private Caller consoleUser(String path) {
        String userName = "pv-cp-" + suffix();
        String auth = EnforcementFixtures.auth(ACCOUNT_ID, "iam");
        cleanup.register(() -> EnforcementFixtures.removeUser(port(), auth, userName));
        iam("CreateUser", path == null
                ? Map.of("UserName", userName)
                : Map.of("UserName", userName, "Path", path)).statusCode(200);
        iam("CreateLoginProfile", Map.of("UserName", userName, "Password", PASSWORD))
                .statusCode(200);
        cleanup.register(() -> iam("DeleteLoginProfile", Map.of("UserName", userName))
                .statusCode(200));
        return new Caller(userName, iam("CreateAccessKey", Map.of("UserName", userName))
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey"
                        + ".AccessKeyId"));
    }

    private void attachChangePassword(String userName) {
        iam("AttachUserPolicy", Map.of("UserName", userName,
                "PolicyArn", "arn:aws:iam::aws:policy/IAMUserChangePassword")).statusCode(200);
    }

    private static ValidatableResponse changePassword(String accessKeyId, String oldPassword,
                                                       String newPassword) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accessKeyId, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ChangePassword")
                .formParam("Version", "2010-05-08")
                .formParam("OldPassword", oldPassword)
                .formParam("NewPassword", newPassword)
        .when()
                .post("/")
        .then();
    }

    private String createBucket(String bucket) {
        cleanup.register(() -> EnforcementFixtures.removeBucket(port(), s3Auth(ACCOUNT_ID), bucket));
        given()
                .header("Authorization", s3Auth(ACCOUNT_ID))
        .when()
                .put("/" + bucket)
        .then()
                .statusCode(200);
        return bucket;
    }

    /** An IAM user in {@link #ACCOUNT_ID} carrying {@code policy} inline. */
    private String userWith(String policy) {
        String user = "policy-vars-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, port(),
                EnforcementFixtures.auth(ACCOUNT_ID, "iam"), user).accessKeyId();
        iam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "p", "PolicyDocument", policy))
                .statusCode(200);
        return accessKeyId;
    }

    private static ValidatableResponse list(String accessKeyId, String bucket) {
        return given()
                .header("Authorization", s3Auth(accessKeyId))
                .queryParam("list-type", "2")
        .when()
                .get("/" + bucket)
        .then();
    }

    private static ValidatableResponse iam(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", EnforcementFixtures.auth(ACCOUNT_ID, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String s3Auth(String accessKeyId) {
        return EnforcementFixtures.auth(accessKeyId, "s3");
    }

    private int port() {
        return baseUri.getPort();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
