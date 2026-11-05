package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CognitoPoolScopedLifecycle.Identity;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CognitoPoolScopedLifecycle.Prior;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.ResourceServer;
import io.github.hectorvent.floci.services.cognito.model.ResourceServerScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CognitoResourceServerCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Cognito::UserPoolResourceServer";

    private final CognitoService cognitoService;
    private final CognitoPoolScopedLifecycle lifecycle;

    @Inject
    public CognitoResourceServerCfnProvisioner(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
        this.lifecycle = new CognitoPoolScopedLifecycle("resource server", "__FlociResourceServerPoolId",
                "__FlociResourceServerUpdate", "identifier",
                identity -> cognitoService.deleteResourceServer(identity.poolId(), identity.name()),
                this::restore);
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        String poolId = required(ctx.resolveOptional(properties, "UserPoolId"), "UserPoolId");
        String identifier = required(ctx.resolveOptional(properties, "Identifier"), "Identifier");
        String name = required(ctx.resolveOptional(properties, "Name"), "Name");
        validateString(poolId, "UserPoolId", 55, "[\\w-]+_[0-9a-zA-Z]+");
        validateString(identifier, "Identifier", 256, "[\\x21\\x23-\\x5B\\x5D-\\x7E]+");
        validateString(name, "Name", 256, "[\\w\\s+=,.@-]+");
        List<ResourceServerScope> scopes = scopes(properties, ctx);

        lifecycle.beginUpdate(resource, ctx);
        Prior prior = lifecycle.prior(resource, ctx);
        Identity current = prior.identity();
        boolean replacement = current != null
                && (!current.poolId().equals(poolId) || !current.name().equals(identifier));
        Identity target = current;
        if (current == null || replacement) {
            cognitoService.createResourceServer(poolId, identifier, name, scopes);
            target = new Identity(poolId, identifier);
        } else {
            ResourceServer existing = cognitoService.describeResourceServer(current.poolId(), current.name());
            ObjectNode snapshot = lifecycle.snapshot(current);
            snapshot.put("name", existing.getName());
            snapshot.set("scopes", CognitoPoolScopedLifecycle.MAPPER.valueToTree(existing.getScopes()));
            lifecycle.saveSnapshot(resource, snapshot);
            cognitoService.updateResourceServer(poolId, identifier, name, scopes);
        }
        lifecycle.commit(resource, ctx, prior, target);
    }

    private void restore(Identity identity, ObjectNode snapshot) {
        cognitoService.updateResourceServer(identity.poolId(), identity.name(), snapshot.path("name").asText(),
                scopes(snapshot.path("scopes")));
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", TYPE + " requires " + property, 400);
        }
        return value;
    }

    private static List<ResourceServerScope> scopes(JsonNode properties, ProvisionContext ctx) {
        if (properties == null || !properties.has("Scopes")) {
            return List.of();
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(properties.get("Scopes"));
        if (resolved != null && resolved.isMissingNode()) {
            return List.of();
        }
        if (resolved == null || !resolved.isArray()) {
            throw new AwsException("ValidationError", TYPE + " Scopes must be a list", 400);
        }
        if (resolved.size() > 100) {
            throw new AwsException("ValidationError", TYPE + " allows at most 100 Scopes", 400);
        }
        List<ResourceServerScope> scopes = new ArrayList<>();
        for (JsonNode node : resolved) {
            if (!node.isObject() || !node.path("ScopeName").isTextual()
                    || !node.path("ScopeDescription").isTextual()
                    || !validScopeName(node.path("ScopeName").asText())
                    || !validScopeDescription(node.path("ScopeDescription").asText())) {
                throw new AwsException("ValidationError", TYPE + " scopes require ScopeName and ScopeDescription", 400);
            }
            ResourceServerScope scope = new ResourceServerScope();
            scope.setScopeName(node.path("ScopeName").asText());
            scope.setScopeDescription(node.path("ScopeDescription").asText());
            scopes.add(scope);
        }
        return scopes;
    }

    private static List<ResourceServerScope> scopes(JsonNode nodes) {
        List<ResourceServerScope> scopes = new ArrayList<>();
        for (JsonNode node : nodes) {
            ResourceServerScope scope = new ResourceServerScope();
            scope.setScopeName(node.path("scopeName").asText());
            scope.setScopeDescription(node.path("scopeDescription").asText(null));
            scopes.add(scope);
        }
        return scopes;
    }

    private static void validateString(String value, String property, int maximum, String pattern) {
        if (value.length() > maximum || !value.matches(pattern)) {
            throw new AwsException("ValidationError", TYPE + " has invalid " + property, 400);
        }
    }

    private static boolean validScopeName(String value) {
        return value.length() <= 256 && value.matches("[\\x21\\x23-\\x2E\\x30-\\x5B\\x5D-\\x7E]+");
    }

    private static boolean validScopeDescription(String value) {
        return !value.isEmpty() && value.length() <= 256;
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
