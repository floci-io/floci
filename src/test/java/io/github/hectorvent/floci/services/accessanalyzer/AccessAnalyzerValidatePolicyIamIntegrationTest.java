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
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        for (String path : List.of("/policy/validation", "/%70olicy/validation", "/policy;ignored=yes/validation")) {
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
