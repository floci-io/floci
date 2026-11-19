package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Supplies a role's trust policy to IAM policy evaluation as the role's resource-based policy, for
 * the STS actions that name the role, together with the account that owns the role: the one in its
 * ARN, also when the role does not exist or its trust policy cannot be read, so assuming another
 * account's role is judged as cross-account.
 *
 * <p>The STS API Reference (AssumeRole) says "the role's trust policy acts as an IAM resource-based
 * policy". A trust policy names no {@code Resource}, the role it is attached to being its resource,
 * so each statement that names none is given the role's ARN. IAM actions on the role, such as
 * {@code iam:GetRole} or {@code iam:PassRole}, are not governed by its trust policy and get nothing
 * here.
 */
@ApplicationScoped
public class RoleTrustPolicyProvider implements ResourcePolicyProvider {

    private static final Logger LOG = Logger.getLogger(RoleTrustPolicyProvider.class);
    private static final String ROLE_PREFIX = "role/";
    private static final String STS_ACTION_PREFIX = "sts:";

    private final IamService iamService;
    private final ObjectMapper objectMapper;

    @Inject
    public RoleTrustPolicyProvider(IamService iamService, ObjectMapper objectMapper) {
        this.iamService = iamService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        return List.of();
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String action, String resourceArn) {
        if (action == null || !action.startsWith(STS_ACTION_PREFIX) || !AwsArnUtils.isArnFor(resourceArn, "iam")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String target = arn.resource();
        if (!AccountResolver.isAccountId(arn.accountId()) || !target.startsWith(ROLE_PREFIX)
                || target.length() == ROLE_PREFIX.length() || target.contains("*")) {
            return List.of();
        }
        String roleName = target.substring(target.lastIndexOf('/') + 1);
        String trustPolicy = iamService.findRole(arn.accountId(), roleName)
                .filter(role -> IamService.roleArnMatches(resourceArn, role))
                .map(IamRole::getAssumeRolePolicyDocument)
                .flatMap(document -> attachedTo(document, resourceArn))
                .orElse(null);
        return List.of(new ResourcePolicy(trustPolicy, arn.accountId()));
    }

    /**
     * The trust policy with the role's ARN as the {@code Resource} of every statement naming none, or
     * empty when it cannot be read, which STS refuses whatever it says.
     */
    private Optional<String> attachedTo(String trustPolicy, String roleArn) {
        if (trustPolicy == null || trustPolicy.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trustPolicy);
        } catch (Exception e) {
            LOG.debugv("Unreadable trust policy on {0}: {1}", roleArn, e.getMessage());
            return Optional.empty();
        }
        if (!(root instanceof ObjectNode document) || !TrustPolicyStatements.wellFormed(root.path("Statement"))) {
            return Optional.empty();
        }
        JsonNode statements = document.path("Statement");
        List<JsonNode> statementList = new ArrayList<>();
        if (statements.isArray()) {
            statements.forEach(statementList::add);
        } else {
            statementList.add(statements);
        }
        ArrayNode attached = objectMapper.createArrayNode();
        for (JsonNode statement : statementList) {
            ObjectNode copy = ((ObjectNode) statement).deepCopy();
            if (!copy.has("Resource") && !copy.has("NotResource")) {
                copy.put("Resource", roleArn);
            }
            attached.add(copy);
        }
        ObjectNode result = document.deepCopy();
        result.set("Statement", attached);
        try {
            return Optional.of(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            LOG.debugv("Unwritable trust policy on {0}: {1}", roleArn, e.getMessage());
            return Optional.empty();
        }
    }
}
