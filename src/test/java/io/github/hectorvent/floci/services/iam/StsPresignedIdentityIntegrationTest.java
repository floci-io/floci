package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsPresignedIdentityIntegrationTest {
    @Inject IamService iam;
    @TestHTTPResource("/") URI baseUri;
    @RegisterExtension final PartitionCleanup cleanup = new PartitionCleanup();

    static Stream<Arguments> identities() {
        return Stream.of("000000000000:us-east-1:aws", "123456789012:cn-north-1:aws-cn")
                .flatMap(scope -> Stream.of("root", "user", "role")
                        .flatMap(kind -> Stream.of("header", "query", "mixed", "form-only", "form-mixed").map(mode -> Arguments.of(scope, kind, mode))));
    }

    @ParameterizedTest
    @MethodSource("identities")
    void callerIdentityRetainsCredentialIdentity(String scope, String kind, String mode) {
        String[] parts = scope.split(":");
        String account = parts[0];
        String region = parts[1];
        String partition = parts[2];
        String name = "presigned-" + UUID.randomUUID().toString().substring(0, 8);
        String rootAuth = auth(account, region, "iam");
        String key = account;
        String token = null;
        String arn = "arn:" + partition + ":iam::" + account + ":root";
        String userId = account;
        if (kind.equals("user")) {
            key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), rootAuth, name).accessKeyId();
            XmlPath user = given().header("Authorization", rootAuth).formParam("Action", "GetUser")
                    .formParam("UserName", name).post("/").then().statusCode(200).extract().xmlPath();
            arn = user.getString("GetUserResponse.GetUserResult.User.Arn");
            userId = user.getString("GetUserResponse.GetUserResult.User.UserId");
        } else if (kind.equals("role")) {
            cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), rootAuth, name));
            String roleArn = given().header("Authorization", rootAuth).formParam("Action", "CreateRole")
                    .formParam("RoleName", name).formParam("AssumeRolePolicyDocument",
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                    + "\"Principal\":{\"AWS\":\"" + arn + "\"},\"Action\":\"sts:AssumeRole\"}]}")
                    .post("/").then().statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
            XmlPath session = given().header("Authorization", auth(account, region, "sts"))
                    .formParam("Action", "AssumeRole").formParam("Version", "2011-06-15")
                    .formParam("RoleArn", roleArn).formParam("RoleSessionName", "identity-session")
                    .post("/").then().statusCode(200).extract().xmlPath();
            key = session.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
            token = session.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
            arn = session.getString("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.Arn");
            userId = session.getString("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.AssumedRoleId");
            String sessionKey = key;
            cleanup.register(() -> iam.unregisterSession(account, sessionKey));
        }
        RequestSpecification request = given().formParam("Action", "GetCallerIdentity")
                .formParam("Version", "2011-06-15");
        if (mode.equals("query")) {
            request.queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                    .queryParam("X-Amz-Credential", key + "/20261013/" + region + "/sts/aws4_request")
                    .queryParam("X-Amz-SignedHeaders", "host").queryParam("X-Amz-Signature", "abc");
            if (token != null) {
                request.queryParam("X-Amz-Security-Token", token);
            }
        } else {
            request.header("Authorization", auth(key, region, "sts"));
            if (token != null) {
                if (mode.startsWith("form-")) {
                    request.formParam("X-Amz-Security-Token", token);
                } else {
                    request.header("X-Amz-Security-Token", token);
                }
            }
        }
        if ((mode.equals("mixed") || mode.equals("form-mixed"))) {
            // A different query credential and token cannot relabel the header's caller.
            request.queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                    .queryParam("X-Amz-Credential", "999999999999/20261013/us-east-1/sts/aws4_request")
                    .queryParam("X-Amz-Security-Token", "wrong-query-token");
        }
        XmlPath identity = request.post("/").then().statusCode(200).extract().xmlPath();
        String expectedArn = arn;
        String expectedUserId = userId;
        assertAll(() -> assertEquals(account, identity.getString("GetCallerIdentityResponse.GetCallerIdentityResult.Account")),
                () -> assertEquals(expectedArn, identity.getString("GetCallerIdentityResponse.GetCallerIdentityResult.Arn")),
                () -> assertEquals(expectedUserId, identity.getString("GetCallerIdentityResponse.GetCallerIdentityResult.UserId")));
    }

    private static String auth(String key, String region, String service) {
        return EnforcementFixtures.auth(key, service).replace("us-east-1", region);
    }
}
