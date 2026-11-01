package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
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

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ROLE_ACCOUNT = "222222222222";
    private static final String TRUSTED_ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "333333333333";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String ALLOW_ASSUME_ROLE = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"sts:AssumeRole\",\"Resource\":\"*\"}]}";

    @Test
    void externalIdConditionNeedsTheMatchingExternalId() {
        String role = createRole("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole",
                  "Condition":{"StringEquals":{"sts:ExternalId":"expected-id"}}}]}""".formatted(TRUSTED_ACCOUNT));

        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "sx", null), role);
        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "sx", "other-id"), role);
        assumeRole(TRUSTED_ACCOUNT, role, "sx", "expected-id").then().statusCode(200);
    }

    @Test
    void denyUnlessExternalIdRefusesACallWithoutOne() {
        String role = createRole("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole"},
                  {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::%1$s:root"},"Action":"sts:AssumeRole",
                   "Condition":{"StringNotEquals":{"sts:ExternalId":"expected-id"}}}]}""".formatted(TRUSTED_ACCOUNT));

        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "sx", null), role);
        assertTrustDenied(assumeRole(TRUSTED_ACCOUNT, role, "sx", "other-id"), role);
        assumeRole(TRUSTED_ACCOUNT, role, "sx", "expected-id").then().statusCode(200);
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
        String accessKeyId = createCallerUser("trust-user-" + UUID.randomUUID().toString().substring(0, 8));
        String trustsDefault = createRole(trustAccountRoot(DEFAULT_ACCOUNT));
        String trustsOwnAccount = createRole(trustAccountRoot(OTHER_ACCOUNT));

        assertTrustDenied(assumeRole(accessKeyId, trustsDefault, "sx", null), trustsDefault);
        assumeRole(accessKeyId, trustsOwnAccount, "sx", null).then().statusCode(200);
    }

    @Test
    void aTrustConditionOnTheUserNameMatchesTheCallingUser() {
        String user = "trust-name-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = createCallerUser(user);
        String ownerRoot = "arn:aws:iam::" + OTHER_ACCOUNT + ":root";
        String namesTheCaller = createRole(trustWithCondition(ownerRoot,
                "{\"StringEquals\":{\"aws:username\":\"" + user + "\"}}"));
        String namesSomeoneElse = createRole(trustWithCondition(ownerRoot,
                "{\"StringEquals\":{\"aws:username\":\"someone-else\"}}"));

        assumeRole(accessKeyId, namesTheCaller, "sx", null).then().statusCode(200);
        assertTrustDenied(assumeRole(accessKeyId, namesSomeoneElse, "sx", null), namesSomeoneElse);
    }

    @Test
    void aTrustConditionTellsAUserApartFromARoleSession() {
        String accessKeyId = createCallerUser("trust-type-" + UUID.randomUUID().toString().substring(0, 8));
        String callerRole = "trust-type-role-" + UUID.randomUUID().toString().substring(0, 8);
        createCallerRole(callerRole, "/");
        Session session = assumeCallerRole(callerRole, "/");
        String onlyUsers = createRole(trustWithCondition("*",
                "{\"StringEquals\":{\"aws:PrincipalType\":\"User\"}}"));
        String onlySessions = createRole(trustWithCondition("*",
                "{\"StringEquals\":{\"aws:PrincipalType\":\"AssumedRole\"}}"));

        assumeRole(accessKeyId, onlyUsers, "sx", null).then().statusCode(200);
        assertTrustDenied(assumeRoleWithSession(session, onlyUsers), onlyUsers);
        assumeRoleWithSession(session, onlySessions).then().statusCode(200);
        assertTrustDenied(assumeRole(accessKeyId, onlySessions, "sx", null), onlySessions);
    }

    @Test
    void theAccountRootStandInIsTheAccountItSignsFor() {
        String accessKeyId = createCallerUser("trust-root-" + UUID.randomUUID().toString().substring(0, 8));
        String onlyTheRoot = createRole(trustWithCondition("*",
                "{\"StringEquals\":{\"aws:PrincipalType\":\"Account\",\"aws:userid\":\"" + TRUSTED_ACCOUNT + "\"}}"));

        assumeRole(TRUSTED_ACCOUNT, onlyTheRoot, "sx", null).then().statusCode(200);
        assertTrustDenied(assumeRole(accessKeyId, onlyTheRoot, "sx", null), onlyTheRoot);
    }

    @Test
    void aTrustConditionReadsTheCallersUserId() {
        String accessKeyId = createCallerUser("trust-id-" + UUID.randomUUID().toString().substring(0, 8));
        String callerRole = "trust-id-role-" + UUID.randomUUID().toString().substring(0, 8);
        createCallerRole(callerRole, "/");
        Session session = assumeCallerRole(callerRole, "/");
        // A user's aws:userid is its own unique id; a session's is role-id:session-name, and the
        // session below was issued under the name sx.
        String userIds = createRole(trustWithCondition("*", "{\"StringLike\":{\"aws:userid\":\"AIDA*\"}}"));
        String sessionIds = createRole(trustWithCondition("*", "{\"StringLike\":{\"aws:userid\":\"AROA*:sx\"}}"));

        assumeRole(accessKeyId, userIds, "sx", null).then().statusCode(200);
        assertTrustDenied(assumeRoleWithSession(session, userIds), userIds);
        assumeRoleWithSession(session, sessionIds).then().statusCode(200);
        assertTrustDenied(assumeRole(accessKeyId, sessionIds, "sx", null), sessionIds);
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

    /** A user in {@link #OTHER_ACCOUNT} carrying the sts:AssumeRole its own policies have to allow. */
    private String createCallerUser(String user) {
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(OTHER_ACCOUNT, "iam"), user).accessKeyId();
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", ALLOW_ASSUME_ROLE)
                .header("Authorization", auth(OTHER_ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        return accessKeyId;
    }

    /** A role in the trusted account that its own account may assume, allowed to call AssumeRole. */
    private void createCallerRole(String name, String path) {
        EnforcementFixtures.createRole(cleanup, baseUri.getPort(), TRUSTED_ACCOUNT, path, name, ALLOW_ASSUME_ROLE);
    }

    private Session assumeCallerRole(String name, String path) {
        return EnforcementFixtures.assumeRole(baseUri.getPort(), TRUSTED_ACCOUNT,
                "arn:aws:iam::" + TRUSTED_ACCOUNT + ":role" + path + name);
    }

    private static Response assumeRoleWithSession(Session session, String role) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "AssumeRole")
            .formParam("Version", "2011-06-15")
            .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT + ":role/" + role)
            .formParam("RoleSessionName", "sx")
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
            .formParam("RoleSessionName", "sx")
        .when().post("/");
    }

    private static String trustPrincipal(String principalArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"%s"},"Action":"sts:AssumeRole"}]}""".formatted(principalArn);
    }

    private static String trustWithCondition(String principal, String condition) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"%s"},"Action":"sts:AssumeRole",
                  "Condition":%s}]}""".formatted(principal, condition);
    }

    private static String trustAccountRoot(String account) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(account);
    }

    private String createRole(String trustPolicy) {
        String role = "trust-cond-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(),
                auth(ROLE_ACCOUNT, "iam"), role));
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

}
