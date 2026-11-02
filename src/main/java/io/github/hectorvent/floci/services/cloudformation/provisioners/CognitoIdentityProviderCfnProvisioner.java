package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Provisions {@code AWS::Cognito::UserPoolIdentityProvider}. {@code Ref} is the bare
 * {@code ProviderName}, while the provider is addressed by its pool and name, so the lifecycle
 * records work on a detached projection whose physical id carries both, as
 * {@link CognitoResourceServerCfnProvisioner} does. {@code ProviderDetails}, {@code AttributeMapping}
 * and {@code IdpIdentifiers} update in place and roll back from a snapshot. A change to
 * {@code UserPoolId} or {@code ProviderName} replaces the provider. {@code ProviderType} is
 * create-only too, but the name is always the template's, so changing only the type would need a
 * replacement at the same address, which, as on AWS, is refused before anything is created.
 */
@ApplicationScoped
public class CognitoIdentityProviderCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolIdentityProvider";
    private static final String POOL_ATTR = "__FlociIdentityProviderPoolId";
    private static final String TYPE_ATTR = "__FlociIdentityProviderType";
    private static final String UPDATE_ATTR = "__FlociIdentityProviderUpdate";
    private static final Set<String> PROVIDER_TYPES =
            Set.of("SAML", "Facebook", "Google", "LoginWithAmazon", "SignInWithApple", "OIDC");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoService cognitoService;

    @Inject
    public CognitoIdentityProviderCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
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
        if (!PROVIDER_TYPES.contains(providerType)) {
            throw new AwsException("ValidationError", TYPE + " has invalid ProviderType", 400);
        }
        Map<String, String> providerDetails = stringMap(properties, "ProviderDetails", ctx);
        if (providerDetails == null) {
            throw new AwsException("ValidationError", TYPE + " requires ProviderDetails", 400);
        }
        Map<String, String> attributeMapping = stringMap(properties, "AttributeMapping", ctx);
        List<String> idpIdentifiers = idpIdentifiers(properties, ctx);

        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(resource);
        }
        if (ctx.isUpdate() && retainsFailedUpdateState(resource)) {
            try {
                rollbackUpdate(resource);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
            } catch (RuntimeException failure) {
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, failure.getMessage());
                throw new IllegalStateException("Could not finish the previous identity provider update rollback",
                        failure);
            }
        }
        String priorPhysicalId = ctx.isUpdate() ? resource.getPhysicalId() : null;
        boolean priorIsStub = ctx.isUpdate()
                && CfnResourceDispatcher.isStub(priorPhysicalId, resource.getAttributes());
        Identity prior = ctx.isUpdate() && !priorIsStub ? identity(resource, priorPhysicalId) : null;
        boolean replacement = prior != null
                && (!prior.poolId().equals(poolId) || !prior.providerName().equals(providerName));
        String priorType = resource.getAttributes().get(TYPE_ATTR);
        if (prior != null && !replacement && priorType != null && !priorType.equals(providerType)) {
            throw new AwsException("ValidationError",
                    "CloudFormation cannot update a stack when a custom-named resource requires "
                            + "replacing. Rename " + providerName + " and update the stack again.", 400);
        }
        Identity target = prior;
        if (prior == null || replacement) {
            cognitoService.createIdentityProvider(poolId, providerName, providerType, providerDetails,
                    attributeMapping, idpIdentifiers);
            target = new Identity(poolId, providerName);
        } else {
            IdentityProvider existing = cognitoService.describeIdentityProvider(prior.poolId(), prior.providerName());
            resource.getAttributes().put(UPDATE_ATTR, snapshot(prior, existing).toString());
            cognitoService.updateIdentityProvider(poolId, providerName, providerDetails, attributeMapping,
                    idpIdentifiers);
        }
        Map<String, String> attributesBefore = new HashMap<>(resource.getAttributes());
        setIdentity(resource, target);
        resource.getAttributes().put(TYPE_ATTR, providerType);
        if (priorIsStub) {
            resource.getAttributes().remove("Arn");
        }
        ProvisionContext cleanupContext = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(),
                ctx.stackName(), priorIsStub ? priorPhysicalId : prior == null ? null : encode(prior),
                ctx.progress(), ctx.priorProperties());
        withProjection(resource, projected -> {
            ReplacementCleanup.record(projected, cleanupContext, attributesBefore);
            return null;
        });
        resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
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

    private record Identity(String poolId, String providerName) {
    }

    private static Identity identity(StackResource resource, String providerName) {
        String poolId = resource.getAttributes().get(POOL_ATTR);
        if (poolId == null || poolId.isBlank() || providerName == null || providerName.isBlank()) {
            throw new IllegalStateException("Missing Cognito identity provider identity for " + resource.getLogicalId());
        }
        return new Identity(poolId, providerName);
    }

    private static Identity identity(JsonNode node) {
        return new Identity(node.path("poolId").asText(), node.path("providerName").asText());
    }

    private static void setIdentity(StackResource resource, Identity identity) {
        resource.setPhysicalId(identity.providerName());
        resource.getAttributes().put(POOL_ATTR, identity.poolId());
    }

    private static ObjectNode snapshot(Identity identity, IdentityProvider provider) {
        ObjectNode snapshot = MAPPER.createObjectNode()
                .put("poolId", identity.poolId())
                .put("providerName", identity.providerName());
        snapshot.set("providerDetails", MAPPER.valueToTree(provider.getProviderDetails()));
        snapshot.set("attributeMapping", MAPPER.valueToTree(provider.getAttributeMapping()));
        snapshot.set("idpIdentifiers", MAPPER.valueToTree(provider.getIdpIdentifiers()));
        return snapshot;
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

    private static String encode(Identity identity) {
        return MAPPER.createArrayNode().add(identity.poolId()).add(identity.providerName()).toString();
    }

    private static Identity decode(String physicalId) {
        try {
            JsonNode address = MAPPER.readTree(physicalId);
            if (address != null && address.isArray() && address.size() == 2
                    && address.get(0).isTextual() && !address.get(0).asText().isBlank()
                    && address.get(1).isTextual() && !address.get(1).asText().isBlank()) {
                return new Identity(address.get(0).asText(), address.get(1).asText());
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid Cognito identity provider cleanup address", invalid);
        }
        throw new IllegalStateException("Invalid Cognito identity provider cleanup address");
    }

    /*
     * Ref is only ProviderName, whereas a provider's delete address also includes its pool.
     * Only this detached view gives ReplacementCleanup the full address; no encoded id is assigned
     * to the stack resource or passed to the template engine.
     */
    private static StackResource project(StackResource resource) {
        StackResource projected = new StackResource();
        projected.setLogicalId(resource.getLogicalId());
        projected.setResourceType(resource.getResourceType());
        projected.setStatus(resource.getStatus());
        projected.setDeletionPolicy(resource.getDeletionPolicy());
        projected.setUpdateReplacePolicy(resource.getUpdateReplacePolicy());
        projected.setAttributes(new HashMap<>(resource.getAttributes()));
        if (resource.getPhysicalId() != null) {
            projected.setPhysicalId(CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())
                    ? resource.getPhysicalId() : encode(identity(resource, resource.getPhysicalId())));
        }
        validateCleanup(projected);
        return projected;
    }

    private static <T> T withProjection(StackResource resource, Function<StackResource, T> operation) {
        StackResource projected = project(resource);
        try {
            return operation.apply(projected);
        } finally {
            // Rollback restores the prior identity before deleting the replacement, even if that delete fails.
            boolean restoredStub = CfnResourceDispatcher.isStub(projected.getPhysicalId(), projected.getAttributes());
            Identity restored = projected.getPhysicalId() == null || restoredStub
                    ? null : decode(projected.getPhysicalId());
            resource.getAttributes().clear();
            resource.getAttributes().putAll(projected.getAttributes());
            if (restoredStub) {
                resource.setPhysicalId(projected.getPhysicalId());
                resource.getAttributes().remove(POOL_ATTR);
                resource.getAttributes().remove(TYPE_ATTR);
            } else if (restored != null) {
                setIdentity(resource, restored);
            }
        }
    }

    private static void requireIdentity(Identity identity) {
        if (identity.poolId().isBlank() || identity.providerName().isBlank()) {
            throw new IllegalStateException("Invalid Cognito identity provider lifecycle identity");
        }
    }

    private static void validateCleanup(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (cleanup == null) {
            return;
        }
        if (cleanup.has("displaced") && !cleanup.path("displaced").isArray()) {
            throw new IllegalStateException("Invalid Cognito identity provider cleanup entries");
        }
        for (JsonNode entry : cleanup.path("displaced")) {
            if (!entry.isObject() || !entry.path("physicalId").isTextual()
                    || entry.path("cleanupAttempts").asInt(0) < 0) {
                throw new IllegalStateException("Invalid Cognito identity provider cleanup entry");
            }
            decode(entry.path("physicalId").asText());
        }
        if (cleanup.hasNonNull("priorPhysicalId")) {
            if (!cleanup.path("priorAttributes").isObject()) {
                throw new IllegalStateException("Invalid Cognito identity provider rollback attributes");
            }
            Map<String, String> priorAttributes = new HashMap<>();
            cleanup.path("priorAttributes").fields()
                    .forEachRemaining(entry -> priorAttributes.put(entry.getKey(), entry.getValue().asText()));
            String priorPhysicalId = cleanup.path("priorPhysicalId").asText();
            if (!CfnResourceDispatcher.isStub(priorPhysicalId, priorAttributes)) {
                decode(priorPhysicalId);
            }
        }
    }

    private void deleteAddress(String resourceType, String physicalId, String region) {
        delete(decode(physicalId));
    }

    private void delete(Identity identity) {
        CfnDeletes.safeDelete("Cognito identity provider", identity.providerName(),
                () -> cognitoService.deleteIdentityProvider(identity.poolId(), identity.providerName()),
                "ResourceNotFoundException");
    }

    @Override
    public void delete(StackResource resource, String region) {
        withProjection(resource, projected -> {
            if (projected.getPhysicalId() != null
                    && !CfnResourceDispatcher.isStub(projected.getPhysicalId(), projected.getAttributes())) {
                deleteAddress(projected.getResourceType(), projected.getPhysicalId(), region);
            }
            projected.getAttributes().remove(UPDATE_ATTR);
            projected.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if (ReplacementCleanup.rollback(projected, this::deleteAddress)) {
                return true;
            }
            ObjectNode snapshot = read(projected, UPDATE_ATTR);
            if (snapshot == null) {
                return false;
            }
            Identity prior = identity(snapshot);
            requireIdentity(prior);
            cognitoService.updateIdentityProvider(prior.poolId(), prior.providerName(),
                    snapshotMap(snapshot.path("providerDetails")), snapshotMap(snapshot.path("attributeMapping")),
                    snapshotList(snapshot.path("idpIdentifiers")));
            projected.setPhysicalId(encode(prior));
            projected.getAttributes().remove(UPDATE_ATTR);
            return true;
        });
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return withProjection(resource, ReplacementCleanup::hasReplacement);
    }

    private static boolean hasPendingRollback(StackResource projected) {
        ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        return projected.getAttributes().containsKey(UPDATE_ATTR)
                || (cleanup != null && cleanup.hasNonNull("priorPhysicalId"));
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return withProjection(resource, CognitoIdentityProviderCfnProvisioner::hasPendingRollback);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return withProjection(resource, projected -> {
            String physicalId = ReplacementCleanup.cleanupPhysicalId(projected);
            return physicalId == null ? null : decode(physicalId).providerName();
        });
    }

    private static UpdateCleanupResult publicResult(UpdateCleanupResult result) {
        String physicalId = result.previousPhysicalId();
        return new UpdateCleanupResult(result.applicable(), result.complete(),
                physicalId == null ? null : decode(physicalId).providerName(),
                result.attempts(), result.failureReason());
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return withProjection(resource, projected -> {
            if ("UPDATE_FAILED".equals(projected.getStatus()) && hasPendingRollback(projected)) {
                throw new IllegalStateException("Identity provider rollback is still pending; its original "
                        + "configuration cannot be discarded by another resource's update cleanup");
            }
            boolean updated = projected.getAttributes().remove(UPDATE_ATTR) != null;
            ObjectNode committed = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            if (committed != null) {
                // Committing the update spends its rollback identity, independently of delete retry debt.
                committed.remove("priorPhysicalId");
                committed.remove("priorAttributes");
                projected.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, committed.toString());
            }
            UpdateCleanupResult cleanup = ReplacementCleanup.complete(projected, this::deleteAddress);
            return !cleanup.applicable() && updated
                    ? new UpdateCleanupResult(true, true, null, 0, null) : publicResult(cleanup);
        });
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return withProjection(resource, projected -> publicResult(ReplacementCleanup.complete(projected,
                this::deleteAddress)));
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        withProjection(resource, projected -> {
            ObjectNode cleanup = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
            JsonNode prior = cleanup == null ? null : cleanup.get("priorPhysicalId");
            JsonNode attributes = cleanup == null ? null : cleanup.get("priorAttributes");
            ReplacementCleanup.clear(projected);
            if (prior != null) {
                ObjectNode remaining = read(projected, CfnRollback.REPLACEMENT_CLEANUP_ATTR);
                if (remaining == null) {
                    remaining = MAPPER.createObjectNode();
                    remaining.putArray("displaced");
                }
                remaining.set("priorPhysicalId", prior);
                remaining.set("priorAttributes", attributes);
                projected.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, remaining.toString());
            }
            return null;
        });
    }

    @Override
    public void clearUpdate(StackResource resource) {
        withProjection(resource, projected -> {
            projected.getAttributes().remove(UPDATE_ATTR);
            ReplacementCleanup.clear(projected);
            return null;
        });
    }

    private static ObjectNode read(StackResource resource, String attribute) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid Cognito identity provider lifecycle record", e);
        }
        throw new IllegalStateException("Invalid Cognito identity provider lifecycle record");
    }
}
