package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::Cognito::UserPoolDomain}. The physical id is the domain name, as in AWS,
 * and {@code Fn::GetAtt CloudFrontDistribution} is the CloudFront name a custom domain's DNS alias
 * points at. A prefix domain has no distribution of its own in Floci, so for one the attribute is
 * empty rather than the literal {@code LogicalId.CloudFrontDistribution} an unset attribute yields.
 *
 * <p>{@code Routing} (regional failover) is accepted and ignored; nothing in the emulator fails
 * over. {@code AWS::Cognito::UserPool} and {@code UserPoolClient} still live in the legacy switch.
 */
@ApplicationScoped
public class CognitoCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoCfnProvisioner.class);
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private final CognitoService cognitoService;

    public CognitoCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Cognito::UserPoolDomain");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String domain = ctx.resolveOptional(props, "Domain");
        String userPoolId = ctx.resolveOptional(props, "UserPoolId");
        if (domain == null || domain.isBlank() || userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalArgumentException("AWS::Cognito::UserPoolDomain requires Domain and UserPoolId");
        }
        Map<String, Object> customDomainConfig = resolveCustomDomainConfig(props, ctx);
        Integer managedLoginVersion = resolveManagedLoginVersion(props, ctx);

        // Domain and UserPoolId are createOnly in the schema: a change to either replaces the
        // domain, and the prior one is removed once the new one exists. Domain names are unique
        // across pools, so a change to UserPoolId alone fails on the create, as it does on AWS,
        // where CloudFormation also creates the replacement before deleting the original. Anything
        // else, a renewed certificate or another managed login version, is applied in place so the
        // CloudFront distribution a DNS alias points at survives, as on AWS.
        UserPoolDomain existing = ctx.isUpdate() ? findExisting(ctx.priorPhysicalId()) : null;
        UserPoolDomain provisioned;
        if (existing != null && ctx.reusesPriorEntity(domain) && userPoolId.equals(existing.getUserPoolId())) {
            provisioned = cognitoService.updateUserPoolDomain(domain, userPoolId, customDomainConfig, managedLoginVersion);
        } else {
            provisioned = cognitoService.createUserPoolDomain(domain, userPoolId, customDomainConfig, managedLoginVersion);
            if (existing != null) {
                deleteDomain(existing.getDomain(), existing.getUserPoolId());
            }
        }
        r.setPhysicalId(domain);
        // Recorded so delete can name the owning pool: DeleteUserPoolDomain needs both.
        r.getAttributes().put("UserPoolId", userPoolId);
        r.getAttributes().put("CloudFrontDistribution",
                provisioned.getCloudFrontDistribution() == null ? "" : provisioned.getCloudFrontDistribution());
    }

    /** Deletes the domain from the pool recorded at create time; without that record, looks the pool up. */
    @Override
    public void delete(StackResource resource, String region) {
        String userPoolId = resource.getAttributes() == null ? null : resource.getAttributes().get("UserPoolId");
        if (userPoolId == null || userPoolId.isBlank()) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
            return;
        }
        deleteDomain(resource.getPhysicalId(), userPoolId);
    }

    /** Only the domain itself can say which pool owns it here; one that is already gone needs nothing. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        UserPoolDomain existing = findExisting(physicalId);
        if (existing != null) {
            deleteDomain(physicalId, existing.getUserPoolId());
        }
    }

    private void deleteDomain(String domain, String userPoolId) {
        CfnDeletes.safeDelete("user pool domain", domain,
                () -> cognitoService.deleteUserPoolDomain(domain, userPoolId), NOT_FOUND);
    }

    private UserPoolDomain findExisting(String domain) {
        try {
            return cognitoService.describeUserPoolDomain(domain);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("User pool domain {0} is gone", domain);
            return null;
        }
    }

    private static Map<String, Object> resolveCustomDomainConfig(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("CustomDomainConfig")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("CustomDomainConfig"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        resolved.fields().forEachRemaining(field -> {
            // A JSON null is an absent value, not the text "null" that asText() would make of it.
            if (!field.getValue().isNull()) {
                config.put(field.getKey(), field.getValue().asText());
            }
        });
        return config;
    }

    private static Integer resolveManagedLoginVersion(JsonNode props, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, "ManagedLoginVersion");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "AWS::Cognito::UserPoolDomain ManagedLoginVersion must be an integer, got: " + value, e);
        }
    }
}
