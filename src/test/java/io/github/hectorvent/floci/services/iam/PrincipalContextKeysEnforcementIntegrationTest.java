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
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * With {@code iam.enforcement-enabled=true}, the three condition keys that describe who is calling
 * are in the request context: {@code aws:username}, {@code aws:userid} and
 * {@code aws:PrincipalType}.
 *
 * <p>Driven over the wire because the values come from the signing credential rather than from
 * anything in the request, and because what matters is the decision a policy reaches, not the map
 * the filter built. The guide's principal key values list gives each principal its own values, and
 * the two this emulator can be called as are covered here: an IAM user, the only principal with an
 * {@code aws:username}, and a role session, which has none. The rest are pinned on
 * {@code resolveCallerArns} directly, where the branch per principal type lives.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class PrincipalContextKeysEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anIamUserCarriesItsNameItsIdAndItsType() {
        String user = "principal-keys-" + UUID.randomUUID().toString().substring(0, 8);
        // The id is the user's own rather than anything the ARN carries, which is why the key
        // needs the stored user and not a parse of the ARN.
        String userId = createUser(user);
        putUserPolicy(user, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
                   "Condition":{"StringEquals":{"aws:username":"%1$s"}}},
                  {"Effect":"Allow","Action":"iam:ListRoles","Resource":"*",
                   "Condition":{"StringEquals":{"aws:username":"somebody-else"}}},
                  {"Effect":"Allow","Action":"iam:ListGroups","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalType":"User"}}},
                  {"Effect":"Allow","Action":"iam:ListPolicies","Resource":"*",
                   "Condition":{"StringEquals":{"aws:userid":"%2$s"}}}]}""".formatted(user, userId));
        String accessKeyId = createAccessKey(user);

        iamCall(accessKeyId, null, "ListUsers").then().statusCode(200);
        // The key carries this caller's name, so another name does not match it.
        iamCall(accessKeyId, null, "ListRoles").then().statusCode(403);
        iamCall(accessKeyId, null, "ListGroups").then().statusCode(200);
        iamCall(accessKeyId, null, "ListPolicies").then().statusCode(200);
    }

    @Test
    void aRoleSessionCarriesNoUserNameAndIsTypedAsAnAssumedRole() {
        String role = "principal-keys-" + UUID.randomUUID().toString().substring(0, 8);
        String roleArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ListUsers","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalType":"AssumedRole"}}},
                  {"Effect":"Allow","Action":"iam:ListRoles","Resource":"*",
                   "Condition":{"StringEquals":{"aws:username":"%s"}}},
                  {"Effect":"Allow","Action":"iam:ListGroups","Resource":"*",
                   "Condition":{"StringLike":{"aws:userid":"AROA*:%2$s"}}}]}"""
                .formatted(role, EnforcementFixtures.SESSION_NAME));
        Session session = EnforcementFixtures.assumeRole(baseUri.getPort(), ACCOUNT, roleArn);

        iamCall(session.accessKeyId(), session.sessionToken(), "ListUsers").then().statusCode(200);
        // The guide lists aws:username as not present for a role, so a statement that matches on
        // it cannot match: an absent key has no equal value.
        iamCall(session.accessKeyId(), session.sessionToken(), "ListRoles").then().statusCode(403);
        // "role-id:caller-specified-role-name", the shape the guide gives a role session.
        iamCall(session.accessKeyId(), session.sessionToken(), "ListGroups").then().statusCode(200);
    }

    private static String createUser(String user) {
        Response response = given().formParam("Action", "CreateUser").formParam("UserName", user)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/");
        response.then().statusCode(200)
                .body("CreateUserResponse.CreateUserResult.User.UserId",
                        matchesPattern("AIDA[A-Z0-9]{16}"));
        return response.path("CreateUserResponse.CreateUserResult.User.UserId");
    }

    private static void putUserPolicy(String user, String policy) {
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user)
                .formParam("PolicyName", "p").formParam("PolicyDocument", policy)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
    }

    private static String createAccessKey(String user) {
        Response response = given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/");
        response.then().statusCode(200);
        return response.path(
                "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static Response iamCall(String accessKeyId, String sessionToken, String action) {
        RequestSpecification request = given().formParam("Action", action)
                .header("Authorization", auth(accessKeyId, "iam"));
        if (sessionToken != null) {
            request = request.header("X-Amz-Security-Token", sessionToken);
        }
        return request.when().post("/");
    }
}
