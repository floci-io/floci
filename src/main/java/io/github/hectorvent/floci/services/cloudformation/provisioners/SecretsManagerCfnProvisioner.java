package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.secretsmanager.RandomPasswordGenerator;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions {@code AWS::SecretsManager::Secret}.
 *
 * <p>Extracted from the former CloudFormation monolith. The sibling type
 * {@code AWS::SecretsManager::SecretTargetAttachment} lives in
 * {@link SecretTargetAttachmentCfnProvisioner} instead of here, because it has to read an RDS or
 * DocumentDB endpoint to build the connection detail it writes into the secret, so it needs three
 * services where this one needs only its own.
 *
 * <p>The secret deletes by physical id alone, so the id-only delete override serves it.
 */
@ApplicationScoped
public class SecretsManagerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SecretsManager::Secret";
    /** Whether the secret's name came from the template or was generated, so a removed explicit name replaces. */
    private static final String NAME_MODE_ATTR = "FlociSecretNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";
    /** The GenerateSecretString configuration the current value was generated from, so only a change regenerates. */
    private static final String GENERATE_IDENTITY_ATTR = "FlociSecretGenerateIdentity";
    /**
     * Recorded when the value did not come from GenerateSecretString, so a later switch to it is a
     * change that generates. An absent attribute means a record from before this was tracked.
     */
    private static final String GENERATE_IDENTITY_NONE = "none";
    private static final int GENERATED_NAME_SUFFIX_LENGTH = 12;

    private static final Logger LOG = Logger.getLogger(SecretsManagerCfnProvisioner.class);

    private final SecretsManagerService secretsManagerService;

    @Inject
    public SecretsManagerCfnProvisioner(SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (!TYPE.equals(r.getResourceType())) {
            throw new IllegalStateException(
                    "SecretsManagerCfnProvisioner received an unsupported type: " + r.getResourceType());
        }
        provisionSecret(r, props, ctx);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!TYPE.equals(resourceType)) {
            throw new IllegalStateException(
                    "SecretsManagerCfnProvisioner received an unsupported type: " + resourceType);
        }
        deleteSecretSafe(physicalId, region);
    }

    /**
     * Create, or on {@code UpdateStack} reconcile in place. {@code Name} is the only create-only
     * property in the registry schema, so a secret whose name is unchanged (or was generated and
     * is therefore kept stable) is updated where it stands: description, KMS key and tags are
     * driven to the template, an explicit {@code SecretString} that differs from the current
     * value becomes a new version, and a changed {@code GenerateSecretString} configuration
     * generates a new one (the registry's update handler holds {@code GetRandomPassword} for
     * exactly that), while an unchanged one leaves the value alone so a no-op update does not
     * rotate the password. A changed explicit name, or an explicit name dropped from the
     * template, is a replacement, as on AWS: a new secret is created and the engine removes the
     * displaced one.
     */
    private void provisionSecret(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        Secret prior = priorSecret(ctx.priorPhysicalId(), region);
        String explicitName = ctx.resolveOptional(props, "Name");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String previousNameMode = r.getAttributes().get(NAME_MODE_ATTR);
        if (previousNameMode == null && prior != null) {
            // Stacks persisted before the mode was recorded: a generated name always has the shape
            // generatePhysicalName produces, so anything else must have been explicit.
            previousNameMode = isGeneratedName(prior.getName(), ctx.stackName(), r.getLogicalId())
                    ? NAME_MODE_GENERATED : NAME_MODE_EXPLICIT;
        }
        boolean explicitNameRemoved = prior != null && !hasExplicitName
                && NAME_MODE_EXPLICIT.equals(previousNameMode);
        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (prior != null && !explicitNameRemoved) {
            name = prior.getName();
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 512, false);
        }
        String description = ctx.resolveOptional(props, "Description");
        String kmsKeyId = ctx.resolveOptional(props, "KmsKeyId");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        JsonNode generate = generateSecretStringNode(props, ctx);
        String generateIdentity = generate == null ? null : generate.toString();

        Secret secret;
        if (prior != null && name.equals(prior.getName())) {
            secret = reconcileExisting(prior, props, description, kmsKeyId, tags, generate, generateIdentity,
                    r.getAttributes().get(GENERATE_IDENTITY_ATTR), ctx);
        } else {
            String value = resolveSecretValue(props, ctx);
            secret = secretsManagerService.createSecret(name, value, null, description, kmsKeyId,
                    tagList(tags), region);
        }
        r.setPhysicalId(secret.getArn());
        r.getAttributes().put("Arn", secret.getArn());
        r.getAttributes().put("Name", name);
        r.getAttributes().put(NAME_MODE_ATTR, hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
        r.getAttributes().put(GENERATE_IDENTITY_ATTR,
                generateIdentity != null ? generateIdentity : GENERATE_IDENTITY_NONE);
    }

    /** The resolved {@code GenerateSecretString} object, or null when the template has none. */
    private static JsonNode generateSecretStringNode(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("GenerateSecretString")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("GenerateSecretString"));
        return resolved == null || resolved.isNull() ? null : resolved;
    }

    /** Whether a name has the exact shape {@code generatePhysicalName} produces for this resource. */
    private static boolean isGeneratedName(String name, String stackName, String logicalId) {
        if (name == null || name.length() < GENERATED_NAME_SUFFIX_LENGTH + 1) {
            return false;
        }
        String suffix = name.substring(name.length() - GENERATED_NAME_SUFFIX_LENGTH);
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        String prefix = name.substring(0, name.length() - GENERATED_NAME_SUFFIX_LENGTH);
        String expected = stackName + "-" + logicalId + "-";
        return prefix.equals(expected) || (expected.startsWith(prefix) && prefix.endsWith("-"));
    }

    private Secret priorSecret(String priorPhysicalId, String region) {
        if (priorPhysicalId == null) {
            return null;
        }
        try {
            return secretsManagerService.describeSecret(priorPhysicalId, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Prior secret {0} is gone; creating it afresh", priorPhysicalId);
            return null;
        }
    }

    private Secret reconcileExisting(Secret prior, JsonNode props, String description, String kmsKeyId,
                                     Map<String, String> tags, JsonNode generate, String generateIdentity,
                                     String priorGenerateIdentity, ProvisionContext ctx) {
        String region = ctx.region();
        String arn = prior.getArn();
        Secret secret = secretsManagerService.updateSecret(arn, description, kmsKeyId, region);
        String secretString = ctx.resolveOptional(props, "SecretString");
        if (secretString != null && !secretString.equals(currentValue(arn, region))) {
            secretsManagerService.putSecretValue(arn, secretString, null, null, region, null);
        } else if (secretString == null && generate != null
                && generateConfigChanged(generateIdentity, priorGenerateIdentity)) {
            secretsManagerService.putSecretValue(arn, generateSecretString(generate), null, null, region, null);
        }
        Map<String, String> current = new LinkedHashMap<>();
        if (prior.getTags() != null) {
            for (Secret.Tag tag : prior.getTags()) {
                current.put(tag.key(), tag.value());
            }
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, tags);
        if (!stale.isEmpty()) {
            secretsManagerService.untagResource(arn, stale, region);
        }
        if (!tags.isEmpty() && !tags.equals(current)) {
            secretsManagerService.tagResource(arn, tagList(tags), region);
        }
        return secret;
    }

    /**
     * Whether the value has to be regenerated for {@code generate}. A record persisted before the
     * identity was tracked has none to compare against, and nothing in the current value shows
     * whether its prior template used {@code SecretString} or a different generation policy, so
     * it generates. Before this reconciliation every update of such a secret replaced it with a
     * newly generated value or failed outright, so no stored value was ever kept.
     */
    private static boolean generateConfigChanged(String generateIdentity, String priorGenerateIdentity) {
        return priorGenerateIdentity == null || !Objects.equals(generateIdentity, priorGenerateIdentity);
    }

    private String currentValue(String arn, String region) {
        try {
            return secretsManagerService.getSecretValue(arn, null, null, region).getSecretString();
        } catch (AwsException e) {
            // A secret with no current version yet has nothing to compare against.
            return null;
        }
    }

    private static List<Secret.Tag> tagList(Map<String, String> tags) {
        List<Secret.Tag> list = new ArrayList<>();
        tags.forEach((key, value) -> list.add(new Secret.Tag(key, value)));
        return list;
    }

    private void deleteSecretSafe(String secretId, String region) {
        try {
            secretsManagerService.deleteSecret(secretId, null, true, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Secret already gone, treating as deleted: {0}", secretId);
        }
    }

    /**
     * Resolves the secret value from CloudFormation properties.
     * SecretString and GenerateSecretString are mutually exclusive per AWS spec.
     * If GenerateSecretString is present, a random password is generated.
     * If SecretStringTemplate and GenerateStringKey are specified inside
     * GenerateSecretString, the generated password is embedded in the template JSON.
     */
    private String resolveSecretValue(JsonNode props, ProvisionContext ctx) {
        if (props == null) {
            return "{}";
        }

        // SecretString takes precedence when explicitly set
        String secretString = ctx.resolveOptional(props, "SecretString");
        JsonNode genNode = props.get("GenerateSecretString");

        if (secretString != null && genNode != null && !genNode.isNull()) {
            throw new AwsException("ValidationError",
                    "You can't specify both SecretString and GenerateSecretString", 400);
        }

        if (secretString != null) {
            return secretString;
        }

        if (genNode != null && !genNode.isNull()) {
            return generateSecretString(genNode);
        }

        return "{}";
    }

    private String generateSecretString(JsonNode genNode) {
        String password = RandomPasswordGenerator.generate(genNode);

        String template = null;
        String key = null;
        JsonNode templateNode = genNode.get("SecretStringTemplate");
        JsonNode keyNode = genNode.get("GenerateStringKey");

        if (templateNode != null && !templateNode.isNull()) {
            template = templateNode.asText();
        }
        if (keyNode != null && !keyNode.isNull()) {
            key = keyNode.asText();
        }

        if (template != null && key != null) {
            // Insert the generated password into the template JSON
            try {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode tree = (ObjectNode) mapper.readTree(template);
                tree.put(key, password);
                return mapper.writeValueAsString(tree);
            } catch (Exception e) {
                // If the template is not valid JSON, fall back to raw password
                LOG.warnv("Failed to parse SecretStringTemplate: {0}", e.getMessage());
                return password;
            }
        }

        return password;
    }
}
