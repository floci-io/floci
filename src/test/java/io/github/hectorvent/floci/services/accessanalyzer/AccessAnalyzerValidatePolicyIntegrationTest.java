package io.github.hectorvent.floci.services.accessanalyzer;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.accessanalyzer.AccessAnalyzerClient;
import software.amazon.awssdk.services.accessanalyzer.model.PolicyType;
import software.amazon.awssdk.services.accessanalyzer.model.ValidatePolicyResponse;
import software.amazon.awssdk.services.accessanalyzer.model.ValidationException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AccessAnalyzerValidatePolicyIntegrationTest {

    @Test
    void validIdentityPolicyNeedsNoAnalyzerAndHasNoFindings() {
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidatePolicyResponse result = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("""
                            {"Version":"2012-10-17","Statement":[{
                              "Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}
                            """));

            assertTrue(result.findings().isEmpty());
        }
    }

    @Test
    void malformedPolicyJsonReturnsAnErrorFindingThroughTheSdk() {
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidatePolicyResponse result = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("{\n  \"Statement\": [}"));

            assertEquals("JSON_SYNTAX_ERROR", result.findings().getFirst().issueCode());
            assertEquals("ERROR", result.findings().getFirst().findingTypeAsString());
            assertEquals(2, result.findings().getFirst().locations().getFirst().span().start().line());
        }
    }

    @Test
    void findingsPaginateThroughTheSdk() {
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidatePolicyResponse first = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("{}").maxResults(1));
            assertEquals(1, first.findings().size());
            assertNotNull(first.nextToken());
            ValidatePolicyResponse second = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("{}").maxResults(1).nextToken(first.nextToken()));
            assertEquals(1, second.findings().size());
            assertNotEquals(first.findings().getFirst().issueCode(), second.findings().getFirst().issueCode());
            assertNull(second.nextToken());
            assertEquals(2, client.validatePolicyPaginator(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument("{}").maxResults(1)).findings().stream().count());
        }
    }

    @Test
    void grammarFindingLocationsDeserializeThroughTheSdk() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Permit\",\"Action\":\"*\",\"Resource\":\"*\"}]}";
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidatePolicyResponse result = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument(policy));
            assertEquals(1, result.findings().size());
            assertEquals("INVALID_EFFECT", result.findings().getFirst().issueCode());
            assertEquals("Statement", result.findings().getFirst().locations().getFirst().path().getFirst().value());
            assertEquals(0, result.findings().getFirst().locations().getFirst().path().get(1).index());
            assertEquals(policy.indexOf("\"Permit\""), result.findings().getFirst().locations().getFirst().span().start().offset());
        }
    }

    @Test
    void invalidMembersReturnTheModeledValidationException() {
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidationException error = assertThrows(ValidationException.class, () -> client.validatePolicy(r ->
                    r.policyType("OTHER").policyDocument("{}")));
            assertEquals(400, error.statusCode());
            assertThrows(ValidationException.class, () -> client.validatePolicy(r ->
                    r.policyType(PolicyType.IDENTITY_POLICY).policyDocument("{}").maxResults(0)));
            assertThrows(ValidationException.class, () -> client.validatePolicy(r ->
                    r.policyType(PolicyType.IDENTITY_POLICY).policyDocument("{}").nextToken("forged")));
        }
    }

    @Test
    void malformedOuterJsonIsASerializationError() {
        given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261031/us-east-1/access-analyzer/aws4_request")
                .body("{\"policyDocument\":").post("/policy/validation")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"Version\":null,\"Version\":\"2012-10-17\"}",
        "{\"Statement\":{\"Condition\":{\"StringEquals\":{\"aws:username\":\"alice\",\"aws:username\":\"bob\"}}}}"})
    void duplicateKeysAtAnyObjectDepthReturnSdkFindings(String policy) {
        try (AccessAnalyzerClient client = AwsTestClients.create(
                AccessAnalyzerClient.builder(), "test", Region.US_EAST_1)) {
            ValidatePolicyResponse result = client.validatePolicy(r -> r.policyType(PolicyType.IDENTITY_POLICY)
                    .policyDocument(policy));
            assertTrue(result.findings().stream().anyMatch(finding -> "JSON_SYNTAX_ERROR".equals(finding.issueCode())));
        }
    }
}
