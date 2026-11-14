package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One stored trust policy answers the same on every path that assumes the role: a statement whose {@code Effect}
 * is not exactly {@code Allow} or {@code Deny}, or whose {@code Condition} is not an object of non-empty operator
 * objects, refuses the whole document on {@code sts:AssumeRole}, {@code sts:AssumeRoleWithWebIdentity} and
 * {@code sts:AssumeRoleWithSAML} alike.
 */
class TrustPolicyStatementsTest {

    private static final String ACCOUNT = "111111111111";
    private static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/alice";
    private static final String OIDC_PREFIX = "oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789";
    private static final String OIDC_PROVIDER = "arn:aws:iam::" + ACCOUNT + ":oidc-provider/" + OIDC_PREFIX;
    private static final String SAML_PROVIDER = "arn:aws:iam::" + ACCOUNT + ":saml-provider/corp";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AssumeRolePolicyEvaluator assumeRole =
            new AssumeRolePolicyEvaluator(mapper, new IamPolicyEvaluator(mapper));
    private final WebIdentityTrustPolicyEvaluator webIdentity = new WebIdentityTrustPolicyEvaluator(mapper);
    private final SAMLTrustPolicyEvaluator saml = new SAMLTrustPolicyEvaluator(mapper);

    /** A path that assumes a role, with the principal and action a statement names to reach it. */
    enum Path {
        ASSUME_ROLE("{\"AWS\":\"" + USER + "\"}", "sts:AssumeRole"),
        WEB_IDENTITY("{\"Federated\":\"" + OIDC_PROVIDER + "\"}", "sts:AssumeRoleWithWebIdentity"),
        SAML("{\"Federated\":\"" + SAML_PROVIDER + "\"}", "sts:AssumeRoleWithSAML");

        private final String principal;
        private final String action;

        Path(String principal, String action) {
            this.principal = principal;
            this.action = action;
        }

        /** A statement reaching this path, carrying {@code elements} (its Effect, a Condition) besides. */
        String statement(String elements) {
            String rest = elements.isEmpty() ? "" : elements + ",";
            return "{" + rest + "\"Principal\":" + principal + ",\"Action\":\"" + action + "\"}";
        }
    }

    private boolean allows(Path path, String... statements) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
        return switch (path) {
            case ASSUME_ROLE -> assumeRole.allows(document, new IamService.CallerArns(USER, USER), ACCOUNT, Map.of());
            case WEB_IDENTITY -> webIdentity.allows(document, OIDC_PROVIDER, OIDC_PREFIX, Map.of());
            case SAML -> saml.allows(document, SAML_PROVIDER, Map.of());
        };
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void anAllowGrantsAndADenyBesideItRefuses(Path path) {
        assertTrue(allows(path, path.statement("\"Effect\":\"Allow\"")));
        assertFalse(allows(path, path.statement("\"Effect\":\"Allow\""), path.statement("\"Effect\":\"Deny\"")));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void aStatementNamingNoEffectGrantsNothing(Path path) {
        assertFalse(allows(path, path.statement("")));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void anEffectThatIsNeitherEffectRefusesTheWholeDocument(Path path) {
        String allow = path.statement("\"Effect\":\"Allow\"");
        for (String effect : List.of("\"allow\"", "\"deny\"", "\"Permit\"", "\"\"", "1", "true", "null",
                "[\"Allow\"]", "{\"Allow\":true}")) {
            assertFalse(allows(path, allow, path.statement("\"Effect\":" + effect)), effect);
        }
        assertFalse(allows(path, allow, path.statement("")), "no Effect");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void aMalformedConditionRefusesTheWholeDocument(Path path) {
        String allow = path.statement("\"Effect\":\"Allow\"");
        for (String condition : List.of("{}", "{\"StringEquals\":{}}", "{\"StringEquals\":\"x\"}", "\"x\"", "null")) {
            assertFalse(allows(path, allow, path.statement("\"Effect\":\"Allow\",\"Condition\":" + condition)),
                    condition);
        }
    }
}
