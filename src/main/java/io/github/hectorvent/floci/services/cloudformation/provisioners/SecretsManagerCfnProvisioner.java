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

import java.util.List;
import java.util.Set;

/**
 * Provisions {@code AWS::SecretsManager::Secret}.
 *
 * <p>Extracted from {@code CloudFormationResourceProvisioner}. The sibling type
 * {@code AWS::SecretsManager::SecretTargetAttachment} lives in
 * {@link SecretTargetAttachmentCfnProvisioner} instead of here, because it has to read an RDS or
 * DocumentDB endpoint to build the connection detail it writes into the secret, so it needs three
 * services where this one needs only its own.
 *
 * <p>The secret deletes by physical id alone, so the id-only delete override serves it and it is
 * not in the engine's {@code DELETE_NEEDS_STACK_RESOURCE} set.
 */
@ApplicationScoped
public class SecretsManagerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SecretsManager::Secret";

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

    private void provisionSecret(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 512, false);
        }
        String description = ctx.resolveOptional(props, "Description");
        String value = resolveSecretValue(props, ctx);
        Secret secret = secretsManagerService.createSecret(name, value, null, description, null,
                List.of(), region);
        r.setPhysicalId(secret.getArn());
        r.getAttributes().put("Arn", secret.getArn());
        r.getAttributes().put("Name", name);
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
