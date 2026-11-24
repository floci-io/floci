package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sts:RoleSessionName} key of an {@code AssumeRoleWithSAML} trust policy, which holds
 * the value of the assertion's {@code https://aws.amazon.com/SAML/Attributes/RoleSessionName}
 * attribute, and is absent when the assertion carries none.
 */
class SAMLTrustPolicyEvaluatorTest {

    private static final String PROVIDER = "arn:aws:iam::000000000000:saml-provider/corp-idp";
    private static final Map<String, List<String>> CLAIMS = Map.of(
            "aud", List.of("https://signin.aws.amazon.com/saml"),
            "iss", List.of("https://idp.example.test/saml"),
            "sub", List.of("saml-subject"));

    private final SAMLTrustPolicyEvaluator evaluator = new SAMLTrustPolicyEvaluator(new ObjectMapper());

    private static String policy(String condition) {
        return """
                {"Statement":[{"Effect":"Allow","Principal":{"Federated":"%s"},
                "Action":"sts:AssumeRoleWithSAML"%s}]}""".formatted(PROVIDER,
                condition == null ? "" : ",\"Condition\":" + condition);
    }

    private boolean allows(String policy, String roleSessionName) {
        return evaluator.allows(policy, PROVIDER, CLAIMS, roleSessionName);
    }

    @Test
    void allowsTheRoleSessionNameThePolicyRequires() {
        assertTrue(allows(policy("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice\"}}"), "alice"));
        assertTrue(allows(policy("{\"StringLike\":{\"sts:RoleSessionName\":\"al*\"}}"), "alice"));
    }

    @Test
    void deniesAnotherRoleSessionName() {
        assertFalse(allows(policy("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice\"}}"), "mallory"));
        assertFalse(allows(policy("{\"StringNotEquals\":{\"sts:RoleSessionName\":\"alice\"}}"), "alice"));
    }

    @Test
    void deniesWhenTheAssertionCarriesNoRoleSessionName() {
        assertFalse(allows(policy("{\"StringEquals\":{\"sts:RoleSessionName\":\"alice\"}}"), null));
    }

    @Test
    void roleSessionNameKeyIsCaseInsensitiveButItsValueIsNot() {
        assertTrue(allows(policy("{\"StringEquals\":{\"STS:rolesessionname\":\"alice\"}}"), "alice"));
        assertFalse(allows(policy("{\"StringEquals\":{\"sts:RoleSessionName\":\"Alice\"}}"), "alice"));
    }

    @Test
    void roleSessionNameAndSubjectConditionsMustBothHold() {
        String policy = policy("{\"StringEquals\":{\"saml:sub\":\"saml-subject\",\"sts:RoleSessionName\":\"alice\"}}");
        assertTrue(allows(policy, "alice"));
        assertFalse(allows(policy, "mallory"));
    }

    @Test
    void aPolicyWithoutConditionIgnoresTheRoleSessionName() {
        assertTrue(allows(policy(null), "alice"));
        assertTrue(allows(policy(null), null));
    }
}
