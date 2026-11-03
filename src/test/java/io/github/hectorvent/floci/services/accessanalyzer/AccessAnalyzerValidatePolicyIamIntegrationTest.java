package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.accessanalyzer.AccessAnalyzerClient;
import software.amazon.awssdk.services.accessanalyzer.model.AccessDeniedException;
import software.amazon.awssdk.services.accessanalyzer.model.PolicyType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AccessAnalyzerValidatePolicyIamIntegrationTest {
    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource("/")
    static URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void everyRoutedPathChecksValidatePolicyDespiteForgedOperationHeaders() {
        String user = "validate-policy-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        for (String path : List.of("/policy/validation", "/%70olicy/validation", "/policy;ignored=yes/validation",
                "/policy/%76alidation", "/policy/validation;ignored=yes",
                "/%70olicy;ignored=yes/%76alidation;ignored=yes/",
                "/%70%6f%6c%69%63%79/%76%61%6c%69%64%61%74%69%6f%6e")) {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .header("Authorization", auth(ACCOUNT, "access-analyzer"))
                    .body("{\"policyDocument\":\"{}\",\"policyType\":\"IDENTITY_POLICY\"}")
                    .post(path).then().statusCode(200);
            given().urlEncodingEnabled(false).contentType("application/json")
                    .header("Authorization", auth(credentials.accessKeyId(), "access-analyzer"))
                    .header("X-Amz-Target", "AccessAnalyzer.ListAnalyzers")
                    .queryParam("Action", "ListAnalyzers")
                    .body("{\"policyDocument\":\"{}\",\"policyType\":\"IDENTITY_POLICY\"}")
                    .post(path).then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        }
    }

    @Test
    void anyRoutableReservedOrDotSegmentAliasStillChecksPermission() throws Exception {
        String user = "validate-policy-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String path : List.of("/policy%2Fvalidation", "/policy%3Bignored=yes/validation",
                    "/policy/validation%3Bignored=yes", "/%2570olicy/validation", "/policy/./validation",
                    "/x/../policy/validation", "/policy/%2e/validation", "//policy/validation",
                    "/;x/policy/validation", "/policy//validation", "/policy/%2e%2e/policy/validation",
                    "/policy%2Fvalidation;ignored=yes", "/policy%2Fvalidation/;x")) {
                HttpResponse<String> root = post(client, path, auth(ACCOUNT, "access-analyzer"));
                if (root.statusCode() == 200) {
                    assertTrue(root.body().contains("\"findings\""), path);
                    assertEquals(403, post(client, path, auth(credentials.accessKeyId(), "access-analyzer"))
                            .statusCode(), path);
                } else {
                    assertTrue(root.statusCode() == 400 || root.statusCode() == 404,
                            () -> path + " returned " + root.statusCode() + ": " + root.body());
                }
            }
        }
    }

    private static HttpResponse<String> post(HttpClient client, String path, String authorization) throws Exception {
        URI uri = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
        assertEquals(path, uri.getRawPath());
        HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .header("X-Amz-Target", "AccessAnalyzer.ListAnalyzers")
                .POST(HttpRequest.BodyPublishers.ofString("{\"policyDocument\":\"{}\",\"policyType\":\"IDENTITY_POLICY\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSdkHonorsAnExplicitAllowAndAnOverridingDeny() {
        String user = "validate-policy-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        try (AccessAnalyzerClient client = AccessAnalyzerClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey()))).build()) {
            assertThrows(AccessDeniedException.class, () -> client.validatePolicy(r ->
                    r.policyType(PolicyType.IDENTITY_POLICY).policyDocument("{}")));
            putPolicy(user, """
                    {"Statement":[{"Effect":"Allow","Action":"access-analyzer:ValidatePolicy",
                      "Resource":"arn:aws:access-analyzer:us-east-1:111111111111:analyzer/example"}]}
                    """);
            assertThrows(AccessDeniedException.class, () -> client.validatePolicy(r ->
                    r.policyType(PolicyType.IDENTITY_POLICY).policyDocument("{}")));
            putPolicy(user, """
                    {"Statement":[{"Effect":"Allow","Action":"access-analyzer:ValidatePolicy","Resource":"*"}]}
                    """);
            assertFalse(client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("{}")).findings().isEmpty());
            putPolicy(user, """
                    {"Statement":[{"Effect":"Allow","Action":"*","Resource":"*"},
                      {"Effect":"Deny","Action":"access-analyzer:ValidatePolicy","Resource":"*"}]}
                    """);
            assertThrows(AccessDeniedException.class, () -> client.validatePolicy(r ->
                    r.policyType(PolicyType.IDENTITY_POLICY).policyDocument("{}")));
        }
    }

    private static void putPolicy(String user, String document) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "validation", document);
    }
}
