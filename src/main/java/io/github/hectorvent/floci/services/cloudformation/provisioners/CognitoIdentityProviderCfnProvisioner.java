package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CognitoPoolScopedLifecycle.Identity;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CognitoPoolScopedLifecycle.Prior;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::Cognito::UserPoolIdentityProvider}. {@code Ref} is the bare
 * {@code ProviderName}, while the provider is addressed by its pool and name, so the stack
 * lifecycle is {@link CognitoPoolScopedLifecycle}'s. {@code ProviderDetails}, {@code AttributeMapping}
 * and {@code IdpIdentifiers} update in place and roll back from a snapshot. A change to
 * {@code UserPoolId} or {@code ProviderName} replaces the provider. {@code ProviderType} is
 * create-only too, but the name is always the template's, so changing only the type would need a
 * replacement at the same address, which, as on AWS, is refused before anything is changed.
 */
@ApplicationScoped
public class CognitoIdentityProviderCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolIdentityProvider";

    private final CognitoService cognitoService;
    private final CognitoPoolScopedLifecycle lifecycle;

    @Inject
    public CognitoIdentityProviderCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
        this.lifecycle = new CognitoPoolScopedLifecycle("identity provider", "__FlociIdentityProviderPoolId",
                "__FlociIdentityProviderUpdate", "providerName",
                identity -> cognitoService.deleteIdentityProvider(identity.poolId(), identity.name()),
                this::restore);
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        String poolId = required(ctx.resolveOptional(properties, "UserPoolId"), "UserPoolId");
        String providerName = required(ctx.resolveOptional(properties, "ProviderName"), "ProviderName");
        String providerType = required(ctx.resolveOptional(properties, "ProviderType"), "ProviderType");
        validateString(poolId, "UserPoolId", 55, "[\\w-]+_[0-9a-zA-Z]+");
        validateString(providerName, "ProviderName", 32,
                "[^_\\p{Z}][\\p{L}\\p{M}\\p{S}\\p{N}\\p{P}][^_\\p{Z}]+");
        if (!CognitoService.PROVIDER_TYPES.contains(providerType)) {
            throw new AwsException("ValidationError", TYPE + " has invalid ProviderType", 400);
        }
        Map<String, String> providerDetails = stringMap(properties, "ProviderDetails", ctx);
        if (providerDetails == null) {
            throw new AwsException("ValidationError", TYPE + " requires ProviderDetails", 400);
        }
        Map<String, String> attributeMapping = stringMap(properties, "AttributeMapping", ctx);
        List<String> idpIdentifiers = idpIdentifiers(properties, ctx);

        lifecycle.beginUpdate(resource, ctx);
        Prior prior = lifecycle.prior(resource, ctx);
        Identity current = prior.identity();
        boolean replacement = current != null
                && (!current.poolId().equals(poolId) || !current.name().equals(providerName));
        Identity target = current;
        if (current == null || replacement) {
            cognitoService.createIdentityProvider(poolId, providerName, providerType, providerDetails,
                    attributeMapping, idpIdentifiers);
            target = new Identity(poolId, providerName);
        } else {
            IdentityProvider existing = cognitoService.describeIdentityProvider(current.poolId(), current.name());
            if (!providerType.equals(existing.getProviderType())) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires "
                                + "replacing. Rename " + providerName + " and update the stack again.", 400);
            }
            ObjectNode snapshot = lifecycle.snapshot(current);
            snapshot.set("providerDetails", CognitoPoolScopedLifecycle.MAPPER.valueToTree(existing.getProviderDetails()));
            snapshot.set("attributeMapping",
                    CognitoPoolScopedLifecycle.MAPPER.valueToTree(existing.getAttributeMapping()));
            snapshot.set("idpIdentifiers", CognitoPoolScopedLifecycle.MAPPER.valueToTree(existing.getIdpIdentifiers()));
            lifecycle.saveSnapshot(resource, snapshot);
            cognitoService.updateIdentityProvider(poolId, providerName, providerDetails, attributeMapping,
                    idpIdentifiers);
        }
        lifecycle.commit(resource, ctx, prior, target);
    }

    /** Puts every in-place member back explicitly, so a value the update cleared returns too. */
    private void restore(Identity identity, ObjectNode snapshot) {
        cognitoService.updateIdentityProvider(identity.poolId(), identity.name(),
                snapshotMap(snapshot.path("providerDetails")), snapshotMap(snapshot.path("attributeMapping")),
                snapshotList(snapshot.path("idpIdentifiers")));
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + property, 400);
        }
        return value;
    }

    private static void validateString(String value, String property, int maximum, String pattern) {
        if (value.length() > maximum || !value.matches(pattern)) {
            throw new AwsException("ValidationError", TYPE + " has invalid " + property, 400);
        }
    }

    /** A map-of-strings property, or null when it is absent or resolves to {@code AWS::NoValue}. */
    private static Map<String, String> stringMap(JsonNode properties, String property, ProvisionContext ctx) {
        if (properties == null || !properties.has(property) || properties.get(property).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(properties.get(property));
        if (resolved != null && resolved.isMissingNode()) {
            return null;
        }
        if (resolved == null || !resolved.isObject()) {
            throw new AwsException("ValidationError", TYPE + " " + property + " must be a map of strings", 400);
        }
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = resolved.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isValueNode() || field.getValue().isNull()) {
                throw new AwsException("ValidationError", TYPE + " " + property + " must be a map of strings", 400);
            }
            values.put(field.getKey(), field.getValue().asText());
        }
        return values;
    }

    /** The template's identifiers, or null when it declares none, so an update keeps the stored ones. */
    private static List<String> idpIdentifiers(JsonNode properties, ProvisionContext ctx) {
        if (properties == null || !properties.has("IdpIdentifiers") || properties.get("IdpIdentifiers").isNull()) {
            return null;
        }
        List<String> identifiers = ctx.resolveStringList(properties, "IdpIdentifiers");
        if (identifiers.size() > 50) {
            throw new AwsException("ValidationError", TYPE + " allows at most 50 IdpIdentifiers", 400);
        }
        return identifiers;
    }

    private static Map<String, String> snapshotMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));
        return values;
    }

    private static List<String> snapshotList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    @Override
    public void delete(StackResource resource, String region) {
        lifecycle.delete(resource, region);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return lifecycle.rollbackUpdate(resource);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return lifecycle.hasReplacementUpdate(resource);
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return lifecycle.retainsFailedUpdateState(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return lifecycle.updateCleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return lifecycle.completeUpdate(resource);
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return lifecycle.completeDeleteCleanup(resource);
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        lifecycle.clearDeleteCleanup(resource);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        lifecycle.clearUpdate(resource);
    }
}
