package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * CloudFront requests are checked against the caller's policies through its generated routes and request
 * resources: as the action of the operation each route names, on the resource its path names
 * ({@code arn:aws:cloudfront::<account>:cache-policy/<id>} for a cache policy), before the handler changes anything.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudFrontIamIntegrationTest {

    private static final String API = "/2020-05-31";
    private static final String CACHE_POLICY = """
            <CachePolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
              <Name>%s</Name>
              <MinTTL>1</MinTTL>
              <ParametersInCacheKeyAndForwardedToOrigin>
                <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
              </ParametersInCacheKeyAndForwardedToOrigin>
            </CachePolicyConfig>""";

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "cloudfront");

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void callerWithoutPermissionsIsRefusedBeforeAnythingIsCreated() throws Exception {
        String name = "iam-none-" + UUID.randomUUID().toString().substring(0, 8);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cf-iam-")) {
            denied(send(user.signer("cloudfront"), "POST", API + "/cache-policy", CACHE_POLICY.formatted(name)),
                    "cloudfront:CreateCachePolicy");
            denied(send(user.signer("cloudfront"), "GET", API + "/distribution", null), "cloudfront:ListDistributions");
            // CreateDistributionWithTags is no IAM action: AWS checks it as CreateDistribution.
            denied(send(user.signer("cloudfront"), "POST", API + "/distribution?WithTags", "<DistributionConfigWithTags/>"),
                    "cloudfront:CreateDistribution");
        }
        send(admin, "GET", API + "/cache-policy?Type=custom", null).then().statusCode(200)
                .body(not(containsString(name)));
    }

    @Test
    void cachePolicyScopedAllowReadsThatPolicyAndRefusesAnother() throws Exception {
        String allowed = createCachePolicy();
        String other = createCachePolicy();
        try (SignedIamUsers.User user = user("Allow", "[\"cloudfront:GetCachePolicy\",\"cloudfront:GetCachePolicyConfig\"]",
                cachePolicyArn(allowed))) {
            send(user.signer("cloudfront"), "GET", API + "/cache-policy/" + allowed, null).then().statusCode(200)
                    .body(containsString("<Id>" + allowed + "</Id>"));
            denied(send(user.signer("cloudfront"), "GET", API + "/cache-policy/" + other, null),
                    "cloudfront:GetCachePolicy");
            // A config read is checked on the policy its path names, as the policy's read is.
            send(user.signer("cloudfront"), "GET", API + "/cache-policy/" + allowed + "/config", null).then()
                    .statusCode(200).body(containsString("<CachePolicyConfig"));
            denied(send(user.signer("cloudfront"), "GET", API + "/cache-policy/" + other + "/config", null),
                    "cloudfront:GetCachePolicyConfig");
        } finally {
            deleteCachePolicy(allowed);
            deleteCachePolicy(other);
        }
    }

    @Test
    void explicitDenyKeepsACachePolicyFromBeingDeleted() throws Exception {
        String id = createCachePolicy();
        try (SignedIamUsers.User user = user("Deny", "cloudfront:DeleteCachePolicy", cachePolicyArn(id))) {
            String etag = send(admin, "GET", API + "/cache-policy/" + id, null).then().statusCode(200)
                    .extract().header("ETag");
            denied(send(user.signer("cloudfront"), "DELETE", API + "/cache-policy/" + id, null, Map.of("if-match", etag)),
                    "cloudfront:DeleteCachePolicy");
            send(admin, "GET", API + "/cache-policy/" + id, null).then().statusCode(200);
        } finally {
            deleteCachePolicy(id);
        }
    }

    @Test
    void listingNeedsTheListActionNotTheGetAction() throws Exception {
        try (SignedIamUsers.User user = user("Allow", "cloudfront:GetCachePolicy", "*")) {
            denied(send(user.signer("cloudfront"), "GET", API + "/cache-policy", null), "cloudfront:ListCachePolicies");
        }
        try (SignedIamUsers.User user = user("Allow", "cloudfront:ListCachePolicies", "*")) {
            send(user.signer("cloudfront"), "GET", API + "/cache-policy", null).then().statusCode(200)
                    .body(containsString("<CachePolicyList"));
        }
    }

    private String createCachePolicy() throws Exception {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        return send(admin, "POST", API + "/cache-policy", CACHE_POLICY.formatted(name)).then().statusCode(201)
                .extract().xmlPath().getString("CachePolicy.Id");
    }

    private void deleteCachePolicy(String id) throws Exception {
        Response current = send(admin, "GET", API + "/cache-policy/" + id, null);
        if (current.statusCode() == 200) {
            send(admin, "DELETE", API + "/cache-policy/" + id, null, Map.of("if-match", current.header("ETag")))
                    .then().statusCode(204);
        }
    }

    private static String cachePolicyArn(String id) {
        return "arn:aws:cloudfront::000000000000:cache-policy/" + id;
    }

    private SignedIamUsers.User user(String effect, String action, String resource) throws Exception {
        String allow = "Deny".equals(effect) ? "{\"Effect\":\"Allow\",\"Action\":\"cloudfront:*\",\"Resource\":\"*\"}," : "";
        return SignedIamUsers.createUser(endpoint, "cf-iam-", "access",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + allow + "{\"Effect\":\"" + effect
                        + "\",\"Action\":" + (action.startsWith("[") ? action : "\"" + action + "\"")
                        + ",\"Resource\":\"" + resource + "\"}]}");
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        return send(signer, method, path, body, Map.of());
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        // The signer signs the extra headers but returns only its own, so the request carries both.
        RequestSpecification request = given().urlEncodingEnabled(false).headers(headers)
                .headers(signer.headersFor(method, uri, headers, bytes));
        if (body != null) {
            request = request.contentType("application/xml").body(bytes);
        }
        return request.request(method, uri);
    }

    private static void denied(Response response, String action) {
        response.then().statusCode(403).body(containsString("not authorized to perform: " + action));
    }
}
