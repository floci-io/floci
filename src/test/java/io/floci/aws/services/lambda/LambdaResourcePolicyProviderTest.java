package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaResourcePolicyProviderTest {

    private static final String OWNER = "111122223333";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:" + OWNER + ":function:my-function";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaService lambdaService;
    private LambdaResourcePolicyProvider provider;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        when(lambdaService.policyStatements(OWNER, "us-east-1", "my-function")).thenReturn(List.of());
        provider = new LambdaResourcePolicyProvider(lambdaService, objectMapper);
    }

    @Test
    void theOwnerComesFromTheArnEvenWithoutAPolicy() {
        List<ResourcePolicy> policies = provider.getResourcePolicies("lambda", FUNCTION_ARN);

        assertEquals(1, policies.size());
        assertNull(policies.getFirst().policyDocument());
        assertEquals(OWNER, policies.getFirst().ownerAccountId());
    }

    @Test
    void everyStatementOnTheFunctionComesBackForAQualifiedArn() throws Exception {
        Map<String, Object> functionGrant = Map.of("Sid", "fn", "Effect", "Allow",
                "Principal", Map.of("AWS", "arn:aws:iam::444455556666:root"),
                "Action", "lambda:InvokeFunction", "Resource", FUNCTION_ARN);
        Map<String, Object> aliasGrant = Map.of("Sid", "alias", "Effect", "Allow",
                "Principal", Map.of("AWS", "arn:aws:iam::444455556666:root"),
                "Action", "lambda:InvokeFunction", "Resource", FUNCTION_ARN + ":live");
        when(lambdaService.policyStatements(OWNER, "us-east-1", "my-function"))
                .thenReturn(List.of(functionGrant, aliasGrant));

        List<ResourcePolicy> policies = provider.getResourcePolicies("lambda", FUNCTION_ARN + ":live");

        JsonNode statements = objectMapper.readTree(policies.getFirst().policyDocument()).get("Statement");
        assertEquals(2, statements.size());
        assertEquals(FUNCTION_ARN, statements.get(0).get("Resource").asText());
        assertEquals(FUNCTION_ARN + ":live", statements.get(1).get("Resource").asText());
        assertEquals(OWNER, policies.getFirst().ownerAccountId());
    }

    @Test
    void aFunctionCheckedUnderAnotherServicesScopeIsStillSupplied() {
        List<ResourcePolicy> policies = provider.getResourcePolicies("secretsmanager", FUNCTION_ARN);

        assertEquals(OWNER, policies.getFirst().ownerAccountId());
    }

    @Test
    void resourcesThatAreNotFunctionsAreLeftToOtherProviders() {
        assertTrue(provider.getResourcePolicies("lambda", "*").isEmpty());
        assertTrue(provider.getResourcePolicies("lambda", "arn:aws:lambda:us-east-1:" + OWNER + ":layer:my-layer:1").isEmpty());
        assertTrue(provider.getResourcePolicies("lambda",
                "arn:aws:lambda:us-east-1:" + OWNER + ":event-source-mapping:fa123456-14a1-4fd2-9fec-83de64ad683d").isEmpty());
        assertTrue(provider.getResourcePolicies("s3", "arn:aws:s3:::bucket/key").isEmpty());
    }
}
