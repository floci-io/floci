package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions the Cognito user pool types.
 *
 * <p>{@code AWS::Cognito::UserPool}: the physical id is the pool id, the whole resolved property
 * set is handed to the service as the API request, and the pool name falls back to a generated one.
 * {@code AWS::Cognito::UserPoolClient}: the physical id is the client id and {@code ClientSecret}
 * is only an attribute when the client has one, as on AWS. Both are updated in place; a provision
 * that leaves either with a new physical id is a replacement, whose displaced entity the
 * {@link ReplacementCleanup} record deletes once the update commits or restores on rollback.
 *
 * <p>{@code AWS::Cognito::UserPoolDomain}: the physical id is the domain name, as in AWS, and
 * {@code Fn::GetAtt CloudFrontDistribution} is the CloudFront name a custom domain's DNS alias
 * points at. A prefix domain has no distribution of its own in Floci, so for one the attribute is
 * empty rather than the literal {@code LogicalId.CloudFrontDistribution} an unset attribute yields.
 * {@code Routing} (regional failover) is accepted and ignored; nothing in the emulator fails over.
 */
@ApplicationScoped
public class CognitoCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CognitoCfnProvisioner.class);
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private static final String USER_POOL = "AWS::Cognito::UserPool";
    private static final String USER_POOL_CLIENT = "AWS::Cognito::UserPoolClient";
    private static final String USER_POOL_DOMAIN = "AWS::Cognito::UserPoolDomain";

    private final CognitoService cognitoService;

    public CognitoCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(USER_POOL, USER_POOL_CLIENT, USER_POOL_DOMAIN);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case USER_POOL -> {
                Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
                provisionUserPool(r, props, ctx);
                ReplacementCleanup.record(r, ctx, attributesBefore);
            }
            case USER_POOL_CLIENT -> {
                Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
                provisionUserPoolClient(r, props, ctx);
                ReplacementCleanup.record(r, ctx, attributesBefore);
            }
            // The domain replaces and removes its predecessor within the provision itself, since the
            // domain name is unique across pools, so it keeps no cleanup record.
            case USER_POOL_DOMAIN -> provisionUserPoolDomain(r, props, ctx);
            default -> throw new IllegalStateException("CognitoCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * A user pool domain deletes from the pool recorded at create time, since DeleteUserPoolDomain
     * needs both; without that record it looks the pool up. The other types delete by id.
     */
    @Override
    public void delete(StackResource resource, String region) {
        if (!USER_POOL_DOMAIN.equals(resource.getResourceType())) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
            return;
        }
        String userPoolId = resource.getAttributes() == null ? null : resource.getAttributes().get("UserPoolId");
        if (userPoolId == null || userPoolId.isBlank()) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
            return;
        }
        deleteDomain(resource.getPhysicalId(), userPoolId);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            // Only the service's own not-found code is tolerated. A pool that still has a domain is
            // refused with InvalidParameterException, and that must fail the stack delete as it does
            // on AWS; in a stack the domain resource depends on the pool, so it is deleted first.
            case USER_POOL -> CfnDeletes.safeDelete("user pool", physicalId,
                    () -> cognitoService.deleteUserPool(physicalId), NOT_FOUND);
            case USER_POOL_CLIENT -> CfnDeletes.safeDelete("user pool client", physicalId,
                    () -> cognitoService.deleteUserPoolClient(physicalId), NOT_FOUND);
            // Only the domain itself can say which pool owns it here; one already gone needs nothing.
            case USER_POOL_DOMAIN -> {
                UserPoolDomain existing = findExisting(physicalId);
                if (existing != null) {
                    deleteDomain(physicalId, existing.getUserPoolId());
                }
            }
            default -> { }
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record. Without one the entity was updated in
     * place, and putting that back needs a snapshot this provisioner does not keep, so the engine
     * reports it as not rolled back, as it did for the switch.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private void provisionUserPool(StackResource r, JsonNode props, ProvisionContext ctx) {
        String poolName = ctx.resolveOptional(props, "UserPoolName");
        if (poolName == null || poolName.isBlank()) {
            poolName = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }

        Map<String, Object> req = new HashMap<>();
        if (props != null) {
            req.putAll(jsonObjectToMap(ctx.engine().resolveNode(props)));
        }
        req.put("PoolName", poolName);

        Map<String, String> tags = ctx.resolveTags(props, "UserPoolTags");
        if (!tags.isEmpty()) {
            req.put("UserPoolTags", tags);
        }

        UserPool pool;
        if (ctx.isUpdate()) {
            req.put("UserPoolId", ctx.priorPhysicalId());
            pool = cognitoService.updateUserPool(req, ctx.region());
        } else {
            pool = cognitoService.createUserPool(req, ctx.region());
        }

        r.setPhysicalId(pool.getId());
        r.getAttributes().put("Arn", pool.getArn());
        r.getAttributes().put("UserPoolId", pool.getId());
        r.getAttributes().put("ProviderName", pool.getName());
        r.getAttributes().put("ProviderURL", cognitoService.getIssuer(pool.getId()));
    }

    private void provisionUserPoolClient(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userPoolId = ctx.resolveOptional(props, "UserPoolId");
        String clientName = ctx.resolveOptional(props, "ClientName");
        if (clientName == null || clientName.isBlank()) {
            clientName = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        boolean generateSecret = Boolean.parseBoolean(resolveOrDefault(props, "GenerateSecret", ctx, "false"));
        boolean allowedOAuthFlowsUserPoolClient =
                Boolean.parseBoolean(resolveOrDefault(props, "AllowedOAuthFlowsUserPoolClient", ctx, "false"));
        List<String> allowedOAuthFlows = ctx.resolveStringList(props, "AllowedOAuthFlows");
        List<String> allowedOAuthScopes = ctx.resolveStringList(props, "AllowedOAuthScopes");

        Map<String, Object> analyticsConfiguration = resolveMapOrNull(props, "AnalyticsConfiguration", ctx);
        List<String> callbackURLs = ctx.resolveStringList(props, "CallbackURLs");
        String defaultRedirectURI = ctx.resolveOptional(props, "DefaultRedirectURI");
        List<String> explicitAuthFlows = ctx.resolveStringList(props, "ExplicitAuthFlows");
        Integer accessTokenValidity = parseIntegerOrNull(props, "AccessTokenValidity", ctx);
        Integer idTokenValidity = parseIntegerOrNull(props, "IdTokenValidity", ctx);
        List<String> logoutURLs = ctx.resolveStringList(props, "LogoutURLs");
        String preventUserExistenceErrors = ctx.resolveOptional(props, "PreventUserExistenceErrors");
        List<String> readAttributes = ctx.resolveStringList(props, "ReadAttributes");
        Integer refreshTokenValidity = parseIntegerOrNull(props, "RefreshTokenValidity", ctx);
        List<String> supportedIdentityProviders = ctx.resolveStringList(props, "SupportedIdentityProviders");
        Map<String, String> tokenValidityUnits = resolveStringMapOrNull(props, "TokenValidityUnits", ctx);
        List<String> writeAttributes = ctx.resolveStringList(props, "WriteAttributes");
        Map<String, Object> refreshTokenRotation = resolveMapOrNull(props, "RefreshTokenRotation", ctx);
        Boolean enableTokenRevocation = parseBooleanOrNull(ctx.resolveOptional(props, "EnableTokenRevocation"));

        UserPoolClient client;
        if (ctx.isUpdate()) {
            client = cognitoService.updateUserPoolClient(
                    userPoolId, ctx.priorPhysicalId(), clientName, allowedOAuthFlowsUserPoolClient,
                    allowedOAuthFlows, allowedOAuthScopes, analyticsConfiguration, callbackURLs,
                    defaultRedirectURI, explicitAuthFlows, accessTokenValidity, idTokenValidity,
                    logoutURLs, preventUserExistenceErrors, readAttributes, refreshTokenValidity,
                    supportedIdentityProviders, tokenValidityUnits, writeAttributes,
                    refreshTokenRotation, enableTokenRevocation);
        } else {
            client = cognitoService.createUserPoolClient(
                    userPoolId, clientName, generateSecret, allowedOAuthFlowsUserPoolClient,
                    allowedOAuthFlows, allowedOAuthScopes, analyticsConfiguration, callbackURLs,
                    defaultRedirectURI, explicitAuthFlows, accessTokenValidity, idTokenValidity,
                    logoutURLs, preventUserExistenceErrors, readAttributes, refreshTokenValidity,
                    supportedIdentityProviders, tokenValidityUnits, writeAttributes,
                    refreshTokenRotation, enableTokenRevocation);
        }

        r.setPhysicalId(client.getClientId());
        r.getAttributes().put("ClientId", client.getClientId());
        r.getAttributes().put("ClientName", client.getClientName());
        // Name is the attribute the registry schema declares read-only for the client.
        r.getAttributes().put("Name", client.getClientName());
        if (client.getClientSecret() != null) {
            r.getAttributes().put("ClientSecret", client.getClientSecret());
        }
    }

    private void provisionUserPoolDomain(StackResource r, JsonNode props, ProvisionContext ctx) {
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

    private static String resolveOrDefault(JsonNode props, String name, ProvisionContext ctx, String defaultValue) {
        String value = ctx.resolveOptional(props, name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static Integer parseIntegerOrNull(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBooleanOrNull(String value) {
        return (value == null || value.isBlank()) ? null : Boolean.valueOf(value);
    }

    private static Map<String, Object> resolveMapOrNull(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        return resolved != null && resolved.isObject() ? jsonObjectToMap(resolved) : null;
    }

    private static Map<String, String> resolveStringMapOrNull(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        resolved.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static Map<String, Object> jsonObjectToMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), jsonNodeToValue(e.getValue())));
        return out;
    }

    private static Object jsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return jsonObjectToMap(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(v -> values.add(jsonNodeToValue(v)));
            return values;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
