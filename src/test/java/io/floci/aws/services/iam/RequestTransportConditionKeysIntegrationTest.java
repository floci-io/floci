package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The global condition keys the IAM User Guide takes from the request itself: {@code aws:SourceIp}, "the requester's
 * IP address", {@code aws:SecureTransport}, "whether the request was sent using TLS", {@code aws:UserAgent}, "the
 * requester's client application", and {@code aws:referer}, present "only if" the caller sends the header. The tests
 * reach Floci over plain HTTP from the loopback address.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RequestTransportConditionKeysIntegrationTest {
    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** The guide's IP address example: "the request must come from the IP range" the policy names. */
    @Test
    void sourceIpIsTheAddressTheRequestComesFrom() {
        UserCredentials loopback = user(allowListUsers("{\"IpAddress\":{\"aws:SourceIp\":[\"127.0.0.0/8\",\"::1/128\"]}}"));
        UserCredentials documentation = user(allowListUsers("{\"IpAddress\":{\"aws:SourceIp\":\"203.0.113.0/24\"}}"));
        UserCredentials notDocumentation = user(allowListUsers(
                "{\"NotIpAddress\":{\"aws:SourceIp\":\"203.0.113.0/24\"}}"));
        try (IamClient fromLoopback = client(loopback); IamClient fromDocumentation = client(documentation);
             IamClient notFromDocumentation = client(notDocumentation)) {
            fromLoopback.listUsers();
            denied(fromDocumentation::listUsers);
            notFromDocumentation.listUsers();
        }
    }

    @Test
    void sourceIpIsNotTakenFromAForwardedForHeader() {
        UserCredentials documentation = user(allowListUsers("{\"IpAddress\":{\"aws:SourceIp\":\"203.0.113.0/24\"}}"));
        listUsers(documentation).header("X-Forwarded-For", "203.0.113.7")
                .when().post("/").then().statusCode(403);
    }

    /** The guide's Boolean example denies a request "if the request is not over SSL". */
    @Test
    void secureTransportIsFalseOverPlainHttp() {
        String name = "transport-user-" + suffix();
        UserCredentials keys = user(name, allowListUsers(null));
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "tls", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"*","Resource":"*",
                "Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}""");
        try (IamClient caller = client(keys)) {
            denied(caller::listUsers);
        }
        UserCredentials requiresTls = user(allowListUsers("{\"Bool\":{\"aws:SecureTransport\":\"true\"}}"));
        try (IamClient caller = client(requiresTls)) {
            denied(caller::listUsers);
        }
    }

    @Test
    void userAgentIsTheClientApplication() {
        UserCredentials keys = user(allowListUsers("{\"StringLike\":{\"aws:UserAgent\":\"reporting-tool/*\"}}"));
        listUsers(keys).header("User-Agent", "reporting-tool/2.1").when().post("/").then().statusCode(200);
        listUsers(keys).header("User-Agent", "other-tool/1.0").when().post("/").then().statusCode(403);
    }

    @Test
    void refererIsPresentOnlyWhenTheRequestCarriesIt() {
        UserCredentials keys = user(allowListUsers("{\"StringLike\":{\"aws:referer\":\"https://intranet.example.com/*\"}}"));
        listUsers(keys).header("Referer", "https://intranet.example.com/reports")
                .when().post("/").then().statusCode(200);
        listUsers(keys).when().post("/").then().statusCode(403);
        UserCredentials absent = user(allowListUsers("{\"Null\":{\"aws:referer\":\"true\"}}"));
        listUsers(absent).when().post("/").then().statusCode(200);
    }

    @Test
    void anAssumeRoleTrustPolicyReadsTheSourceIp() {
        String callerName = "transport-user-" + suffix();
        UserCredentials caller = user(callerName, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""");
        String roleName = "transport-trust-" + suffix();
        String roleArn = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", roleName,
                allowListUsers(null));
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1);
             StsClient sts = AwsTestClients.create(StsClient.builder(), caller.accessKeyId(), caller.secretAccessKey(),
                     Region.US_EAST_1)) {
            admin.updateAssumeRolePolicy(r -> r.roleName(roleName).policyDocument(trust(callerName, "203.0.113.0/24")));
            denied(() -> sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("blocked")));
            admin.updateAssumeRolePolicy(r -> r.roleName(roleName).policyDocument(trust(callerName, "127.0.0.0/8")));
            sts.assumeRole(r -> r.roleArn(roleArn).roleSessionName("allowed"));
        }
    }

    private static String trust(String callerName, String range) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::%s:user/%s"},"Action":"sts:AssumeRole",
                "Condition":{"IpAddress":{"aws:SourceIp":["%s","::1/128"]}}}]}"""
                .formatted(ACCOUNT, callerName, range);
    }

    private static String allowListUsers(String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iam:ListUsers\","
                + "\"Resource\":\"*\"" + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
    }

    private RequestSpecification listUsers(UserCredentials keys) {
        return given().port(baseUri.getPort()).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListUsers").formParam("Version", "2010-05-08")
                .header("Authorization", auth(keys.accessKeyId(), "iam"));
    }

    private UserCredentials user(String policy) {
        return user("transport-user-" + suffix(), policy);
    }

    private UserCredentials user(String name, String policy) {
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "base", policy);
        return keys;
    }

    private static IamClient client(UserCredentials keys) {
        return AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    private static void denied(Runnable operation) {
        AwsServiceException error = assertThrows(AwsServiceException.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
