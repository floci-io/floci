package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * With {@code iam.enforcement-enabled=true}, STS AssumeRole evaluates the {@code Condition} block of
 * the role's trust policy against the request, and matches the principal of the account that owns
 * the caller's credentials, as AWS does.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AssumeRoleTrustConditionsIntegrationTest {

    private static final String ROLE_ACCOUNT = "222222222222";
    private static final String TRUSTED_ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "333333333333";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Test
    void externalIdConditionNeedsTheMatchingExternalId() {
        String role = createRole("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"sts:ExternalId":"expected-id"}}}]}""".formatted(TRUSTED_ACCOUNT));

        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "s", null), role);
        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "s", "other-id"), role);
        assumeRole(TRUSTED_ACCOUNT, role, "s", "expected-id").then().statusCode(200);
    }

    @Test
    void denyUnlessExternalIdRefusesACallWithoutOne() {
        String role = createRole("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole",
                   "Condition":{"StringNotEquals":{"sts:ExternalId":"expected-id"}}}]}""".formatted(TRUSTED_ACCOUNT));

        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "s", null), role);
        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "s", "other-id"), role);
        assumeRole(TRUSTED_ACCOUNT, role, "s", "expected-id").then().statusCode(200);
    }

    @Test
    void conditionalDenyAppliesOnlyWhenItsConditionMatches() {
        String role = createRole("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole",
                   "Condition":{"StringEquals":{"sts:RoleSessionName":"blocked"}}}]}""".formatted(TRUSTED_ACCOUNT));

        assumeRole(TRUSTED_ACCOUNT, role, "allowed-session", null).then().statusCode(200);
        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "blocked", null), role);
    }

    @Test
    void presignedCallerIsTheCredentialsOwnerNotTheDefaultAccount() {
        String trustsDefault = createRole(trustAccountRoot(DEFAULT_ACCOUNT));
        String trustsOwnAccount = createRole(trustAccountRoot(OTHER_ACCOUNT));

        assertTrustDenied(presignedAssumeRole(OTHER_ACCOUNT, trustsDefault), trustsDefault);
        presignedAssumeRole(OTHER_ACCOUNT, trustsOwnAccount).then().statusCode(200);
    }

    @Test
    void userKeyCallerIsTheUsersAccountNotTheDefaultAccount() {
        String user = "trust-user-" + UUID.randomUUID().toString().substring(0, 8);
        given().formParam("Action", "CreateUser").formParam("UserName", user)
                .header("Authorization", auth(OTHER_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"sts:AssumeRole\",\"Resource\":\"*\"}]}")
                .header("Authorization", auth(OTHER_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        String accessKeyId = given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .header("Authorization", auth(OTHER_ACCOUNT, "iam")).when().post("/").then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String trustsDefault = createRole(trustAccountRoot(DEFAULT_ACCOUNT));
        String trustsOwnAccount = createRole(trustAccountRoot(OTHER_ACCOUNT));

        assertTrustDenied(assumeRole(accessKeyId, trustsDefault, "s", null), trustsDefault);
        assumeRole(accessKeyId, trustsOwnAccount, "s", null).then().statusCode(200);
    }

    @Test
    void roleSessionIsMatchedAsItsRoleWithItsPath() {
        String callerRole = "trust-path-" + UUID.randomUUID().toString().substring(0, 8);
        createCallerRole(callerRole, "/team/");
        Session session = assumeCallerRole(callerRole, "/team/");
        String roleArn = "arn:aws:iam::" + TRUSTED_ACCOUNT + ":role/team/" + callerRole;
        String pathlessArn = "arn:aws:iam::" + TRUSTED_ACCOUNT + ":role/" + callerRole;
        String condition = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole",
                  "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}]}""";
        String namesRole = createRole(trustPrincipal(roleArn));
        String namesPathlessRole = createRole(trustPrincipal(pathlessArn));
        String conditionOnRole = createRole(condition.formatted(TRUSTED_ACCOUNT, roleArn));
        String conditionOnPathlessRole = createRole(condition.formatted(TRUSTED_ACCOUNT, pathlessArn));

        assumeRoleWithSession(session, namesRole).then().statusCode(200);
        assertTrustDenied(assumeRoleWithSession(session, namesPathlessRole), namesPathlessRole);
        assumeRoleWithSession(session, conditionOnRole).then().statusCode(200);
        assertTrustDenied(assumeRoleWithSession(session, conditionOnPathlessRole), conditionOnPathlessRole);
    }

    @Test
    void sessionStillNamesItsRoleAfterASameNamedRoleReplacesIt() {
        String callerRole = "trust-swap-" + UUID.randomUUID().toString().substring(0, 8);
        createCallerRole(callerRole, "/team/");
        Session oldSession = assumeCallerRole(callerRole, "/team/");
        given().formParam("Action", "DeleteRolePolicy").formParam("RoleName", callerRole).formParam("PolicyName", "p")
                .header("Authorization", auth(TRUSTED_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "DeleteRole").formParam("RoleName", callerRole)
                .header("Authorization", auth(TRUSTED_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        createCallerRole(callerRole, "/other/");
        String namesReplacement = createRole(
                trustPrincipal("arn:aws:iam::" + TRUSTED_ACCOUNT + ":role/other/" + callerRole));

        assertTrustDenied(assumeRoleWithSession(oldSession, namesReplacement), namesReplacement);
        assumeRoleWithSession(assumeCallerRole(callerRole, "/other/"), namesReplacement).then().statusCode(200);
    }

    private static void assertTrustDenied(Response response, String role) {
        response.then().statusCode(403)
                .body(containsString("is not authorized to perform: sts:AssumeRole on resource: arn:aws:iam::"
                        + ROLE_ACCOUNT + ":role/" + role));
    }

    private static Response assumeRole(String accessKeyId, String role, String sessionName,
                                       String externalId) {
        RequestSpecification spec = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT + ":role/" + role)
            .formParam("RoleSessionName", sessionName)
            .header("Authorization", auth(accessKeyId, "sts"));
        if (externalId != null) {
            spec.formParam("ExternalId", externalId);
        }
        return spec.when().post("/");
    }

    private record Session(String accessKeyId, String sessionToken) {
    }

    /** A role in the trusted account that its own account may assume, allowed to call AssumeRole. */
    private static void createCallerRole(String name, String path) {
        given().formParam("Action", "CreateRole").formParam("RoleName", name).formParam("Path", path)
                .formParam("AssumeRolePolicyDocument", trustAccountRoot(TRUSTED_ACCOUNT))
                .header("Authorization", auth(TRUSTED_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "PutRolePolicy").formParam("RoleName", name).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"sts:AssumeRole\",\"Resource\":\"*\"}]}")
                .header("Authorization", auth(TRUSTED_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
    }

    private static Session assumeCallerRole(String name, String path) {
        Response response = given().formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + TRUSTED_ACCOUNT + ":role" + path + name)
                .formParam("RoleSessionName", "s")
                .header("Authorization", auth(TRUSTED_ACCOUNT, "sts")).when().post("/");
        response.then().statusCode(200);
        return new Session(response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"));
    }

    private static Response assumeRoleWithSession(Session session, String role) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT + ":role/" + role)
            .formParam("RoleSessionName", "s")
            .header("Authorization", auth(session.accessKeyId(), "sts"))
            .header("X-Amz-Security-Token", session.sessionToken())
        .when().post("/");
    }

    private static Response presignedAssumeRole(String accessKeyId, String role) {
        String amzDate = AMZ_DATE_FMT.format(Instant.now());
        return given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", accessKeyId + "/" + amzDate.substring(0, 8) + "/us-east-1/sts/aws4_request")
            .queryParam("X-Amz-Date", amzDate)
            .queryParam("X-Amz-Expires", "3600")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "abc")
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT + ":role/" + role)
            .formParam("RoleSessionName", "s")
        .when().post("/");
    }

    private static String trustPrincipal(String principalArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"%s"},"Action":"sts:AssumeRole"}]}""".formatted(principalArn);
    }

    private static String trustAccountRoot(String account) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(account);
    }

    private static String createRole(String trustPolicy) {
        String role = "trust-cond-" + UUID.randomUUID().toString().substring(0, 8);
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateRole")
            .formParam("RoleName", role)
            .formParam("AssumeRolePolicyDocument", trustPolicy)
            .header("Authorization", auth(ROLE_ACCOUNT, "iam"))
        .when().post("/")
        .then().statusCode(200);
        return role;
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260929/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
