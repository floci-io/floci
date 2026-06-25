package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssumeRolePolicyEvaluatorTest {

    private final AssumeRolePolicyEvaluator evaluator = new AssumeRolePolicyEvaluator(new ObjectMapper());

    private static final String CALLER_ARN = "arn:aws:iam::111111111111:user/alice";
    private static final String CALLER_ACCOUNT = "111111111111";

    private static String trust(String principal) {
        return """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":%s,"Action":"sts:AssumeRole"}]}
            """.formatted(principal);
    }

    @Test
    void allowsAccountRootPrincipal() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:root\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsBareAccountPrincipal() {
        assertTrue(evaluator.allows(trust("{\"AWS\":\"111111111111\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsExactPrincipalArn() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/alice\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWildcardPrincipal() {
        assertTrue(evaluator.allows(trust("\"*\""), CALLER_ARN, CALLER_ACCOUNT));
        assertTrue(evaluator.allows(trust("{\"AWS\":\"*\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWhenPrincipalListContainsCaller() {
        assertTrue(evaluator.allows(
                trust("{\"AWS\":[\"arn:aws:iam::999999999999:root\",\"arn:aws:iam::111111111111:root\"]}"),
                CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenAccountDoesNotMatch() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::999999999999:root\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenPrincipalArnDiffers() {
        assertFalse(evaluator.allows(
                trust("{\"AWS\":\"arn:aws:iam::111111111111:user/bob\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:AssumeRole"},
              {"Effect":"Deny","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":"sts:AssumeRole"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesWhenActionIsNotAssumeRole() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"*"},"Action":"sts:TagSession"}]}
            """;
        assertFalse(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void allowsWildcardAction() {
        String doc = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"AWS":"111111111111"},"Action":"sts:*"}]}
            """;
        assertTrue(evaluator.allows(doc, CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesServiceOnlyPrincipal() {
        assertFalse(evaluator.allows(
                trust("{\"Service\":\"lambda.amazonaws.com\"}"), CALLER_ARN, CALLER_ACCOUNT));
    }

    @Test
    void deniesBlankOrMalformedDocument() {
        assertFalse(evaluator.allows(null, CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("{}", CALLER_ARN, CALLER_ACCOUNT));
        assertFalse(evaluator.allows("not json", CALLER_ARN, CALLER_ACCOUNT));
    }
}
