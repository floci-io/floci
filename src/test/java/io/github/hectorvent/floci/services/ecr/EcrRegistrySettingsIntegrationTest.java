package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;

import java.net.URI;
import java.util.Collections;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * The registry's own settings over the JSON protocol: the registry policy, the replication
 * configuration and the scanning configuration. Each test uses its own Region so they do not
 * share state.
 */
@QuarkusTest
class EcrRegistrySettingsIntegrationTest {

    @InjectMock
    EcrRegistryManager registryManager;

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Replicate\","
            + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::111111111111:root\"},"
            + "\"Action\":[\"ecr:CreateRepository\",\"ecr:ReplicateImage\"],"
            + "\"Resource\":\"arn:aws:ecr:us-east-1:000000000000:repository/*\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void registryPolicyRoundTrips() {
        String region = "eu-north-1";
        call("GetRegistryPolicy", "{}", region).statusCode(400)
                .body("__type", equalTo("RegistryPolicyNotFoundException"));
        call("PutRegistryPolicy", "{\"policyText\":" + quote(POLICY) + "}", region).statusCode(200)
                .body("registryId", equalTo("000000000000"))
                .body("policyText", equalTo(POLICY));
        call("GetRegistryPolicy", "{}", region).statusCode(200).body("policyText", equalTo(POLICY));
        call("DeleteRegistryPolicy", "{}", region).statusCode(200).body("policyText", equalTo(POLICY));
        call("DeleteRegistryPolicy", "{}", region).statusCode(400)
                .body("__type", equalTo("RegistryPolicyNotFoundException"));
        call("PutRegistryPolicy", "{\"policyText\":\"not json\"}", region).statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void replicationIsDescribedAndCleared() {
        String region = "eu-south-1";
        call("DescribeRegistry", "{}", region).statusCode(200)
                .body("registryId", equalTo("000000000000"))
                .body("replicationConfiguration.rules", hasSize(0));

        String rules = "{\"replicationConfiguration\":{\"rules\":[{\"destinations\":[{\"region\":\"us-west-2\","
                + "\"registryId\":\"000000000000\"}],\"repositoryFilters\":[{\"filter\":\"prod\","
                + "\"filterType\":\"PREFIX_MATCH\"}]},{\"destinations\":[{\"region\":\"eu-west-1\","
                + "\"registryId\":\"111111111111\"}]}]}}";
        call("PutReplicationConfiguration", rules, region).statusCode(200)
                .body("replicationConfiguration.rules", hasSize(2))
                .body("replicationConfiguration.rules[0].repositoryFilters[0].filter", equalTo("prod"));
        call("DescribeRegistry", "{}", region).statusCode(200)
                .body("replicationConfiguration.rules[0].destinations[0].region", equalTo("us-west-2"))
                .body("replicationConfiguration.rules[1].destinations[0].registryId", equalTo("111111111111"))
                .body("replicationConfiguration.rules[1].repositoryFilters", equalTo(null));

        call("PutReplicationConfiguration", "{\"replicationConfiguration\":{\"rules\":[{\"destinations\":"
                + "[{\"region\":\"us-west-2\",\"registryId\":\"123\"}]}]}}", region).statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        call("PutReplicationConfiguration", "{}", region).statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));

        call("PutReplicationConfiguration", "{\"replicationConfiguration\":{\"rules\":[]}}", region).statusCode(200)
                .body("replicationConfiguration.rules", hasSize(0));
        call("DescribeRegistry", "{}", region).statusCode(200).body("replicationConfiguration.rules", hasSize(0));
    }

    @Test
    void scanningConfigurationRoundTrips() {
        String region = "ap-south-2";
        call("GetRegistryScanningConfiguration", "{}", region).statusCode(200)
                .body("registryId", equalTo("000000000000"))
                .body("scanningConfiguration.scanType", equalTo("BASIC"))
                .body("scanningConfiguration.rules", hasSize(0));

        call("PutRegistryScanningConfiguration", "{\"scanType\":\"ENHANCED\",\"rules\":[{\"scanFrequency\":"
                + "\"CONTINUOUS_SCAN\",\"repositoryFilters\":[{\"filter\":\"prod-*\",\"filterType\":\"WILDCARD\"}]}]}",
                region).statusCode(200)
                .body("registryScanningConfiguration.scanType", equalTo("ENHANCED"))
                .body("registryScanningConfiguration.rules[0].scanFrequency", equalTo("CONTINUOUS_SCAN"));
        call("GetRegistryScanningConfiguration", "{}", region).statusCode(200)
                .body("scanningConfiguration.scanType", equalTo("ENHANCED"))
                .body("scanningConfiguration.rules[0].repositoryFilters[0].filter", equalTo("prod-*"));

        call("PutRegistryScanningConfiguration", "{\"scanType\":\"BASIC\",\"rules\":[{\"scanFrequency\":"
                + "\"CONTINUOUS_SCAN\",\"repositoryFilters\":[{\"filter\":\"*\",\"filterType\":\"WILDCARD\"}]}]}",
                region).statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void repositoryScanningBatchRejectsInvalidNames() {
        String region = "eu-west-3";
        for (String body : new String[] {
                "{}", "{\"repositoryNames\":[]}", "{\"repositoryNames\":[\"a\"]}",
                "{\"repositoryNames\":[\"Invalid Name\"]}",
                "{\"repositoryNames\":[" + String.join(",", Collections.nCopies(26, "\"valid-name\"")) + "]}"
        }) {
            call("BatchGetRepositoryScanningConfiguration", body, region).statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
        }
    }

    @Test
    void repositoryScanningBatchSdkRejectsMalformedName() {
        try (EcrClient client = EcrClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.EU_WEST_3)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("000000000000", "test")))
                .build()) {
            InvalidParameterException error = assertThrows(InvalidParameterException.class,
                    () -> client.batchGetRepositoryScanningConfiguration(b -> b.repositoryNames("Invalid Name")));
            assertEquals(400, error.statusCode());
            assertEquals(1, client.batchGetRepositoryScanningConfiguration(b -> b.repositoryNames("sdk-missing"))
                    .failures().size());
        }
    }

    @Test
    void repositoryScanningBatchKeepsValidResultsWhenAnotherNameIsMissing() {
        String region = "eu-west-3";
        when(registryManager.getRepositoryUri("000000000000", region, "batch-scan-present"))
                .thenReturn("localhost:4566/batch-scan-present");
        call("CreateRepository", """
                {"repositoryName":"batch-scan-present","imageScanningConfiguration":{"scanOnPush":true}}
                """, region).statusCode(200);

        call("BatchGetRepositoryScanningConfiguration", """
                {"repositoryNames":["batch-scan-missing","batch-scan-present"]}
                """, region).statusCode(200)
                .body("scanningConfigurations", hasSize(1))
                .body("scanningConfigurations[0].repositoryName", equalTo("batch-scan-present"))
                .body("scanningConfigurations[0].scanOnPush", equalTo(true))
                .body("scanningConfigurations[0].scanFrequency", equalTo("SCAN_ON_PUSH"))
                .body("scanningConfigurations[0].appliedScanFilters", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].repositoryName", equalTo("batch-scan-missing"))
                .body("failures[0].failureCode", equalTo("REPOSITORY_NOT_FOUND"));

        call("BatchGetRepositoryScanningConfiguration", """
                {"repositoryNames":["batch-scan-missing"]}
                """, region).statusCode(200)
                .body("scanningConfigurations", hasSize(0))
                .body("failures", hasSize(1))
                .body("failures[0].failureCode", equalTo("REPOSITORY_NOT_FOUND"));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static ValidatableResponse call(String action, String body, String region) {
        return given()
                .header("X-Amz-Target", PREFIX + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20261016/" + region
                        + "/ecr/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
