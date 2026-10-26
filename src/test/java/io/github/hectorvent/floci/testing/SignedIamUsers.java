package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.restassured.response.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

/**
 * IAM calls with real SigV4 signatures, for classes that run with signature validation on (such as
 * {@link SignedIamEnforcementProfile}), where {@link EnforcementFixtures}' placeholder signatures
 * are refused. Every call is signed as the root {@code test} key.
 *
 * <p>{@link #createUser} makes an IAM user with an access key and one inline policy; closing the
 * returned {@link User} deletes the key, the policy and the user, so a test can hold it in a
 * try-with-resources block.
 */
public final class SignedIamUsers {

    private SignedIamUsers() {
    }

    /** Sends one IAM Query action, signed as the root {@code test} key, to {@code endpoint}. */
    public static Response iam(URI endpoint, String action, Map<String, String> parameters) throws Exception {
        String body = "Action=" + action + "&Version=2010-05-08&" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return given().headers(AwsRequestSigner.signedAs("test", "test", "iam")
                        .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }

    /**
     * Creates a user named {@code namePrefix} plus a random suffix, with one access key and the
     * inline policy {@code policyName} holding {@code policyDocument}. If a later step fails, the
     * parts already made are removed before the failure is rethrown.
     */
    public static User createUser(URI endpoint, String namePrefix, String policyName, String policyDocument)
            throws Exception {
        String name = namePrefix + UUID.randomUUID();
        iam(endpoint, "CreateUser", Map.of("UserName", name)).then().statusCode(200);
        User user = new User(endpoint, name, policyName, null, null);
        try {
            Response credentials = iam(endpoint, "CreateAccessKey", Map.of("UserName", name));
            credentials.then().statusCode(200);
            user = new User(endpoint, name, policyName,
                    credentials.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId"),
                    credentials.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey"));
            iam(endpoint, "PutUserPolicy", Map.of("UserName", name, "PolicyName", policyName,
                    "PolicyDocument", policyDocument)).then().statusCode(200);
            return user;
        } catch (Exception | AssertionError e) {
            try {
                user.close();
            } catch (Exception | AssertionError cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /** An IAM user made by {@link #createUser}; {@link #close} removes it with its key and policy. */
    public record User(URI endpoint, String name, String policyName, String accessKeyId, String secretAccessKey)
            implements AutoCloseable {

        /** A signer for {@code service} with this user's access key, in us-east-1. */
        public AwsRequestSigner signer(String service) {
            return AwsRequestSigner.signedAs(accessKeyId, secretAccessKey, service);
        }

        @Override
        public void close() throws Exception {
            if (accessKeyId != null) {
                iam(endpoint, "DeleteAccessKey", Map.of("UserName", name, "AccessKeyId", accessKeyId))
                        .then().statusCode(200);
            }
            iam(endpoint, "DeleteUserPolicy", Map.of("UserName", name, "PolicyName", policyName));
            iam(endpoint, "DeleteUser", Map.of("UserName", name)).then().statusCode(200);
        }
    }
}
