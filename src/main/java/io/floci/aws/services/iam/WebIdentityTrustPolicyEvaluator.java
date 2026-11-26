package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Evaluates a role's trust policy for {@code sts:AssumeRoleWithWebIdentity}, matching the
 * {@code Federated} principal and the {@code Condition} block that pins an OIDC subject and
 * audience, and the session name the request passes.
 *
 * <p>Separate from {@link AssumeRolePolicyEvaluator} on purpose. That evaluator serves the
 * {@code sts:AssumeRole} path, hardcodes that action, reads only {@code Principal.AWS}, and ignores
 * conditions — widening it would risk regressing a path that already works. The two are
 * complementary: this one only ever considers federated principals.
 *
 * <p>Condition values are compared with exact, case-sensitive equality rather than
 * {@link IamPolicyEvaluator#globMatches} (which lowercases both operands). An OIDC subject like
 * {@code system:serviceaccount:apps:My-Account} must not match a policy naming {@code my-account}:
 * case-folding here would silently grant a different service account.
 */
@ApplicationScoped
public class WebIdentityTrustPolicyEvaluator {

    private static final Logger LOG = Logger.getLogger(WebIdentityTrustPolicyEvaluator.class);
    private static final String WEB_IDENTITY_ACTION = "sts:assumerolewithwebidentity";
    /** What a trust policy also has to allow for the token's session tags to be taken. */
    private static final String TAG_SESSION_ACTION = AssumeRolePolicyEvaluator.TAG_SESSION_ACTION;
    private static final String ROLE_SESSION_NAME_KEY = "sts:RoleSessionName";

    private final ObjectMapper objectMapper;

    @Inject
    public WebIdentityTrustPolicyEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns true if {@code trustPolicyDocument} allows a web-identity token from
     * {@code oidcProviderArn} with the given claim context to assume the role.
     *
     * <p>{@code claims} holds the OIDC condition context keyed <em>without</em> the provider
     * prefix — {@code sub}, {@code aud} — which this evaluator resolves against policy keys of the
     * form {@code <provider-host-and-path>:sub}. {@code roleSessionName} is the request's
     * {@code RoleSessionName}, which a policy reads as {@code sts:RoleSessionName}. {@code roleTags} holds
     * the tags of the role being assumed under the keys the generated table gives its type
     * ({@code aws:ResourceTag/<key>}, {@code iam:ResourceTag/<key>}), which a policy reads on it as on the
     * resource of the request, as AWS includes them when the resource already has tags. An explicit
     * {@code Deny} wins over any {@code Allow}, per AWS precedence.
     */
    public boolean allows(String trustPolicyDocument, String oidcProviderArn, String issuerKeyPrefix,
                          Map<String, List<String>> claims, String roleSessionName,
                          Map<String, List<String>> roleTags) {
        return allows(WEB_IDENTITY_ACTION, trustPolicyDocument, oidcProviderArn, issuerKeyPrefix, claims,
                roleSessionName, roleTags);
    }

    /**
     * Whether the trust policy lets this token's holder tag the session it is assuming, read on the
     * same document with the same principal and the same conditions as the assume itself.
     */
    public boolean allowsTagging(String trustPolicyDocument, String oidcProviderArn, String issuerKeyPrefix,
                                 Map<String, List<String>> claims, String roleSessionName,
                                 Map<String, List<String>> roleTags) {
        return allows(TAG_SESSION_ACTION, trustPolicyDocument, oidcProviderArn, issuerKeyPrefix, claims,
                roleSessionName, roleTags);
    }

    private boolean allows(String action, String trustPolicyDocument, String oidcProviderArn,
                           String issuerKeyPrefix, Map<String, List<String>> claims,
                           String roleSessionName, Map<String, List<String>> roleTags) {
        if (trustPolicyDocument == null || trustPolicyDocument.isBlank()) {
            return false;
        }
        JsonNode statements;
        try {
            statements = objectMapper.readTree(trustPolicyDocument).path("Statement");
        } catch (JsonProcessingException e) {
            LOG.warnv("Failed to parse web-identity trust policy: {0}", e.getMessage());
            return false;
        }
        if (!TrustPolicyStatements.wellFormed(statements)) {
            return false;
        }

        boolean allow = false;
        if (statements.isArray()) {
            for (JsonNode stmt : statements) {
                switch (evaluate(action, stmt, oidcProviderArn, issuerKeyPrefix, claims, roleSessionName, roleTags)) {
                    case DENY -> { return false; }
                    case ALLOW -> allow = true;
                    case NO_MATCH -> { }
                }
            }
            return allow;
        }
        if (statements.isObject()) {
            return evaluate(action, statements, oidcProviderArn, issuerKeyPrefix, claims, roleSessionName,
                    roleTags) == Match.ALLOW;
        }
        return false;
    }

    private enum Match { ALLOW, DENY, NO_MATCH }

    private Match evaluate(String action, JsonNode stmt, String oidcProviderArn, String issuerKeyPrefix,
                           Map<String, List<String>> claims, String roleSessionName,
                           Map<String, List<String>> roleTags) {
        if (!actionApplies(stmt, action)) {
            return Match.NO_MATCH;
        }
        if (!matchesFederatedPrincipal(stmt.get("Principal"), oidcProviderArn)) {
            return Match.NO_MATCH;
        }
        if (!conditionsSatisfied(stmt.get("Condition"), issuerKeyPrefix, claims, roleSessionName, roleTags)) {
            return Match.NO_MATCH;
        }
        return TrustPolicyStatements.denies(stmt) ? Match.DENY : Match.ALLOW;
    }

    /**
     * True when the statement's action element covers {@code action}, the assume itself or the
     * {@code sts:TagSession} a tagged assume also needs. Action names are case-insensitive in AWS
     * policy documents, and wildcards are honoured.
     */
    private boolean actionApplies(JsonNode stmt, String action) {
        JsonNode element = stmt.get("Action");
        if (element != null) {
            return matchesAction(element, action);
        }
        JsonNode notAction = stmt.get("NotAction");
        if (notAction != null) {
            return !matchesAction(notAction, action);
        }
        return false;
    }

    private boolean matchesAction(JsonNode actionNode, String action) {
        if (actionNode.isTextual()) {
            return IamPolicyEvaluator.globMatches(actionNode.asText(), action);
        }
        if (actionNode.isArray()) {
            for (JsonNode entry : actionNode) {
                if (entry.isTextual() && IamPolicyEvaluator.globMatches(entry.asText(), action)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Matches {@code Principal.Federated} against the OIDC provider ARN. Only the federated
     * principal type can match a web-identity caller — an {@code AWS} principal never does.
     */
    private boolean matchesFederatedPrincipal(JsonNode principalNode, String oidcProviderArn) {
        if (principalNode == null || oidcProviderArn == null) {
            return false;
        }
        if (principalNode.isTextual()) {
            return "*".equals(principalNode.asText());
        }
        if (!principalNode.isObject()) {
            return false;
        }
        JsonNode federated = principalNode.get("Federated");
        if (federated == null) {
            return false;
        }
        if (federated.isTextual()) {
            return federatedMatches(federated.asText(), oidcProviderArn);
        }
        if (federated.isArray()) {
            for (JsonNode entry : federated) {
                if (entry.isTextual() && federatedMatches(entry.asText(), oidcProviderArn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean federatedMatches(String declared, String oidcProviderArn) {
        return "*".equals(declared) || declared.equals(oidcProviderArn);
    }

    /**
     * Evaluates the condition block. Every operator block and every key within it must be satisfied
     * (AWS ANDs them). Only the string operators meaningful for OIDC claims are handled; a condition
     * using anything else does not match, which fails closed.
     */
    private boolean conditionsSatisfied(JsonNode conditionNode, String issuerKeyPrefix,
                                        Map<String, List<String>> claims, String roleSessionName,
                                        Map<String, List<String>> roleTags) {
        if (conditionNode == null || conditionNode.isNull()) {
            // No condition constraints — the federated principal alone is enough.
            return true;
        }
        if (!conditionNode.isObject()) {
            return false;
        }

        Iterator<Map.Entry<String, JsonNode>> operators = conditionNode.fields();
        while (operators.hasNext()) {
            Map.Entry<String, JsonNode> operatorEntry = operators.next();
            String operator = operatorEntry.getKey();
            JsonNode keys = operatorEntry.getValue();
            if (!keys.isObject()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> keyIterator = keys.fields();
            while (keyIterator.hasNext()) {
                Map.Entry<String, JsonNode> keyEntry = keyIterator.next();
                if (!keySatisfied(operator, keyEntry.getKey(), keyEntry.getValue(),
                        issuerKeyPrefix, claims, roleSessionName, roleTags)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean keySatisfied(String operator, String conditionKey, JsonNode expected,
                                 String issuerKeyPrefix, Map<String, List<String>> claims,
                                 String roleSessionName, Map<String, List<String>> roleTags) {
        List<String> actual = ROLE_SESSION_NAME_KEY.equalsIgnoreCase(conditionKey)
                ? (roleSessionName == null ? List.of() : List.of(roleSessionName))
                : TrustPolicyStatements.roleValues(conditionKey, roleTags)
                        .orElseGet(() -> resolveClaim(conditionKey, issuerKeyPrefix, claims));
        List<String> expectedValues = textValues(expected);
        if (expectedValues.isEmpty()) {
            return false;
        }

        return switch (operator) {
            case "StringEquals" -> actual.stream().anyMatch(expectedValues::contains);
            case "StringNotEquals" -> actual.stream().noneMatch(expectedValues::contains);
            case "StringLike" -> actual.stream().anyMatch(
                    a -> expectedValues.stream().anyMatch(e -> IamPolicyEvaluator.caseSensitiveGlobMatches(e, a)));
            case "StringNotLike" -> actual.stream().noneMatch(
                    a -> expectedValues.stream().anyMatch(e -> IamPolicyEvaluator.caseSensitiveGlobMatches(e, a)));
            default -> {
                LOG.debugv("Unsupported web-identity trust policy condition operator: {0}", operator);
                yield false;
            }
        };
    }

    /**
     * Resolves a policy condition key such as
     * {@code oidc.eks.us-east-1.amazonaws.com/id/ABC123:sub} to the token's claim values. The key
     * prefix is the issuer with its scheme stripped, which is how the AWS console, eksctl, and
     * Terraform all render it.
     */
    private List<String> resolveClaim(String conditionKey, String issuerKeyPrefix,
                                      Map<String, List<String>> claims) {
        if (conditionKey == null) {
            return List.of();
        }
        int separator = conditionKey.lastIndexOf(':');
        if (separator < 0) {
            return List.of();
        }
        String prefix = conditionKey.substring(0, separator);
        String claimName = conditionKey.substring(separator + 1);

        // The provider prefix must be this token's issuer; a condition pinned to a different
        // provider must not be satisfied by our claims.
        if (!prefix.equalsIgnoreCase(issuerKeyPrefix)) {
            return List.of();
        }
        return claims.getOrDefault(claimName.toLowerCase(), List.of());
    }

    private List<String> textValues(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (node.isArray()) {
            return StreamSupport
                    .stream(node.spliterator(), false)
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .toList();
        }
        return List.of();
    }
}
