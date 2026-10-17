package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * The registry's own settings over the JSON protocol: the registry policy, the replication
 * configuration and the scanning configuration. Each test uses its own Region so they do not
 * share state.
 */
@QuarkusTest
class EcrRegistrySettingsIntegrationTest {

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
