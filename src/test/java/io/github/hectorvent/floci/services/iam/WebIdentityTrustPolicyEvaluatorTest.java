package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebIdentityTrustPolicyEvaluatorTest {

    private static final String PREFIX =
            "oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789ABCDEF0123456789";
    private static final String PROVIDER_ARN = "arn:aws:iam::000000000000:oidc-provider/" + PREFIX;
    private static final String NAMESPACE = "my-namespace";
    private static final String SERVICE_ACCOUNT = "my-service-account";
    private static final String SUBJECT = "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT;

    private final WebIdentityTrustPolicyEvaluator evaluator =
            new WebIdentityTrustPolicyEvaluator(new ObjectMapper());

    /** The canonical IRSA trust policy shape. */
    private String irsaTrustPolicy(String subject) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": { "Federated": "%s" },
                    "Action": "sts:AssumeRoleWithWebIdentity",
                    "Condition": {
                      "StringEquals": {
                        "%s:sub": "%s",
                        "%s:aud": "sts.amazonaws.com"
                      }
                    }
                  }]
                }""".formatted(PROVIDER_ARN, PREFIX, subject, PREFIX);
    }

    private Map<String, List<String>> claims(String subject) {
        return Map.of("sub", List.of(subject), "aud", List.of("sts.amazonaws.com"));
    }

    private boolean allows(String policy, Map<String, List<String>> claims) {
        return evaluator.allows(policy, PROVIDER_ARN, PREFIX, claims);
    }

    @Test
    void allowsMatchingSubjectAndAudience() {
        assertTrue(allows(irsaTrustPolicy(SUBJECT), claims(SUBJECT)));
    }

    @Test
    void deniesDifferentServiceAccount() {
        assertFalse(allows(irsaTrustPolicy(SUBJECT),
                claims("system:serviceaccount:my-namespace:other-service-account")));
    }

    @Test
    void deniesDifferentNamespace() {
        assertFalse(allows(irsaTrustPolicy(SUBJECT),
                claims("system:serviceaccount:other-ns:" + SERVICE_ACCOUNT)));
    }

    @Test
    void subjectComparisonIsCaseSensitive() {
        // globMatches lowercases both operands; OIDC subjects must not case-fold or a policy for
        // "my-service-account" would admit the distinct service account "My-Service-Account".
        assertFalse(allows(irsaTrustPolicy("system:serviceaccount:my-namespace:My-Service-Account"),
                claims(SUBJECT)));
        assertFalse(allows(irsaTrustPolicy(SUBJECT),
                claims("system:serviceaccount:my-namespace:My-Service-Account")));
    }

    @Test
    void deniesWrongAudience() {
        Map<String, List<String>> claims = Map.of(
                "sub", List.of(SUBJECT), "aud", List.of("some.other.audience"));
        assertFalse(allows(irsaTrustPolicy(SUBJECT), claims));
    }

    @Test
    void deniesUnrelatedOidcProvider() {
        String policy = irsaTrustPolicy(SUBJECT);
        assertFalse(evaluator.allows(policy,
                "arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/OTHER",
                PREFIX, claims(SUBJECT)));
    }

    @Test
    void deniesConditionKeyedToAnotherProvider() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{
                "oidc.eks.us-east-1.amazonaws.com/id/SOMEONEELSE:sub":"%s"}}}]}"""
                .formatted(PROVIDER_ARN, SUBJECT);
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void deniesAwsPrincipalOnlyPolicy() {
        String policy = """
                {"Statement":[{"Effect":"Allow",
                "Principal":{"AWS":"arn:aws:iam::000000000000:root"},
                "Action":"sts:AssumeRoleWithWebIdentity"}]}""";
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void deniesWrongAction() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRole","Condition":{"StringEquals":{"%s:sub":"%s"}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX, SUBJECT);
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void allowsWildcardAction() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:*","Condition":{"StringEquals":{"%s:sub":"%s"}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX, SUBJECT);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void explicitDenyOverridesAllow() {
        String policy = """
                {"Statement":[
                  {"Effect":"Allow","Principal":{"Federated":"%s"},
                   "Action":"sts:AssumeRoleWithWebIdentity",
                   "Condition":{"StringEquals":{"%s:sub":"%s"}}},
                  {"Effect":"Deny","Principal":{"Federated":"%s"},
                   "Action":"sts:AssumeRoleWithWebIdentity"}
                ]}""".formatted(PROVIDER_ARN, PREFIX, SUBJECT, PROVIDER_ARN);
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void allowsSubjectListContainingClaim() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{
                "%s:sub":["system:serviceaccount:my-namespace:other","%s"]}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX, SUBJECT);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void allowsStringLikeWildcardSubject() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringLike":{
                "%s:sub":"system:serviceaccount:my-namespace:*"}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void stringLikeComparisonIsCaseSensitive() {
        // StringLike must not fold case either, or a pattern scoped to one namespace would admit a
        // differently-cased one. The literal part of the pattern is what matters here.
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringLike":{
                "%s:sub":"system:serviceaccount:My-Namespace:*"}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX);
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void stringNotLikeComparisonIsCaseSensitive() {
        // The claim differs from the pattern only by case, so it does NOT match, so StringNotLike
        // is satisfied and the statement allows.
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringNotLike":{
                "%s:sub":"system:serviceaccount:MY-NAMESPACE:*"}}}]}"""
                .formatted(PROVIDER_ARN, PREFIX);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void caseSensitiveGlobHandlesWildcardsAndQuestionMarks() {
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("abc", "abc"));
        assertFalse(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("abc", "ABC"));
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("a*", "abcdef"));
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("*f", "abcdef"));
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("a*e?", "abcdef"));
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("**abc**", "xxabcyy"));
        assertTrue(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("*", ""));
        assertFalse(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("a?c", "ac"));
        assertFalse(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("abc", "abcd"));
        assertFalse(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive(null, "abc"));
        assertFalse(WebIdentityTrustPolicyEvaluator.globMatchesCaseSensitive("abc", null));
    }

    @Test
    void allowsFederatedPrincipalWithoutConditions() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity"}]}""".formatted(PROVIDER_ARN);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void deniesUnsupportedConditionOperator() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"DateGreaterThan":{
                "aws:CurrentTime":"2020-01-01T00:00:00Z"}}}]}""".formatted(PROVIDER_ARN);
        assertFalse(allows(policy, claims(SUBJECT)));
    }

    @Test
    void deniesNullBlankOrMalformedPolicy() {
        assertFalse(allows(null, claims(SUBJECT)));
        assertFalse(allows("", claims(SUBJECT)));
        assertFalse(allows("{not json", claims(SUBJECT)));
    }

    @Test
    void handlesSingleStatementObject() {
        String policy = """
                {"Statement":{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{
                "%s:sub":"%s"}}}}""".formatted(PROVIDER_ARN, PREFIX, SUBJECT);
        assertTrue(allows(policy, claims(SUBJECT)));
    }

    @Test
    void conditionKeyPrefixIsCaseInsensitive() {
        // Condition *keys* are case-insensitive in AWS even though values are not.
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"STS:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{
                "%s:SUB":"%s"}}}]}""".formatted(PROVIDER_ARN, PREFIX.toUpperCase(), SUBJECT);
        assertTrue(allows(policy, claims(SUBJECT)));
    }
}
