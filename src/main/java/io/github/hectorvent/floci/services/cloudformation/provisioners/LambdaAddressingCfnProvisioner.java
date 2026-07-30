package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the Lambda addressing resources: {@code AWS::Lambda::Permission},
 * {@code AWS::Lambda::Version} and {@code AWS::Lambda::Alias}.
 *
 * <p>Physical ids double as delete handles, since {@link #delete(String, String, String)} only
 * receives the physical id: a Permission stores {@code <functionName>|<statementId>} ('|' cannot
 * appear in a function name or ARN), a Version stores the version-qualified function ARN (which is
 * also its {@code Ref} value in AWS), and an Alias stores the alias ARN.
 *
 * <p>Stack updates re-provision every resource in the template, so each type is idempotent:
 * a Permission replaces its previous statement, a Version keeps the already-published version
 * (version resources are immutable in CloudFormation), and an Alias updates in place — deleting
 * the previously created alias when its name or function changed, as a replacement would.
 */
@ApplicationScoped
public class LambdaAddressingCfnProvisioner implements CfnResourceProvisioner {

    private final LambdaService lambdaService;

    @Inject
    public LambdaAddressingCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::Permission", "AWS::Lambda::Version", "AWS::Lambda::Alias");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::Lambda::Permission" -> provisionPermission(r, props, ctx);
            case "AWS::Lambda::Version" -> provisionVersion(r, props, ctx);
            case "AWS::Lambda::Alias" -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaAddressingCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::Lambda::Permission" -> {
                int sep = physicalId.lastIndexOf('|');
                if (sep > 0) {
                    lambdaService.removePermission(region, physicalId.substring(0, sep),
                            physicalId.substring(sep + 1));
                }
            }
            case "AWS::Lambda::Version" -> {
                int sep = physicalId.lastIndexOf(':');
                if (sep > 0) {
                    lambdaService.deleteVersion(region, physicalId.substring(0, sep),
                            physicalId.substring(sep + 1));
                }
            }
            case "AWS::Lambda::Alias" -> {
                int sep = physicalId.lastIndexOf(':');
                if (sep > 0) {
                    lambdaService.deleteAlias(region, physicalId.substring(0, sep),
                            physicalId.substring(sep + 1));
                }
            }
            default -> { /* not ours */ }
        }
    }

    private void provisionPermission(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        String statementId = r.getLogicalId();
        // Stack updates re-provision every resource; drop the previous statement first so
        // AddPermission does not reject the duplicate Sid. The old physical id carries the
        // function the statement was originally attached to. AddPermission rejects a duplicate
        // Sid, so the new statement cannot simply be added first — instead the old one is
        // captured before removal and put back if the replacement is rejected.
        RemovedStatement removed = removePreviousStatement(r.getPhysicalId(), ctx.region());

        Map<String, Object> request = new HashMap<>();
        request.put("StatementId", statementId);
        request.put("Action", ctx.resolveOptional(props, "Action"));
        request.put("Principal", ctx.resolveOptional(props, "Principal"));
        String sourceArn = ctx.resolveOptional(props, "SourceArn");
        if (sourceArn != null && !sourceArn.isBlank()) request.put("SourceArn", sourceArn);
        String sourceAccount = ctx.resolveOptional(props, "SourceAccount");
        if (sourceAccount != null && !sourceAccount.isBlank()) request.put("SourceAccount", sourceAccount);
        try {
            lambdaService.addPermission(ctx.region(), functionName, request);
        } catch (RuntimeException failure) {
            // Without this the rejected update leaves the function with no statement at all, and
            // rollback does not restore it: callers that could invoke before the update lose access.
            if (removed != null) {
                try {
                    lambdaService.restorePermissionStatement(ctx.region(), removed.functionName(),
                            removed.statement());
                } catch (RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
        r.setPhysicalId(functionName + "|" + statementId);
    }

    /** A statement taken off a function so it can be put back if the replacement fails. */
    private record RemovedStatement(String functionName, Map<String, Object> statement) {}

    private RemovedStatement removePreviousStatement(String physicalId, String region) {
        if (physicalId == null) {
            return null;
        }
        int sep = physicalId.lastIndexOf('|');
        if (sep <= 0) {
            return null;
        }
        String functionName = physicalId.substring(0, sep);
        String statementId = physicalId.substring(sep + 1);
        Map<String, Object> statement = findStatement(region, functionName, statementId);
        try {
            lambdaService.removePermission(region, functionName, statementId);
        } catch (AwsException ignored) {
            // statement or function already gone — nothing to replace, nothing to restore
            return null;
        }
        return statement == null ? null : new RemovedStatement(functionName, statement);
    }

    private Map<String, Object> findStatement(String region, String functionName, String statementId) {
        try {
            Object policy = lambdaService.getPolicy(region, functionName).get("policy");
            if (policy instanceof Map<?, ?> policyMap
                    && policyMap.get("Statement") instanceof List<?> statements) {
                for (Object candidate : statements) {
                    if (candidate instanceof Map<?, ?> statement
                            && statementId.equals(statement.get("Sid"))) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        statement.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        return copy;
                    }
                }
            }
        } catch (AwsException ignored) {
            // no policy on the function yet
        }
        return null;
    }

    private void provisionVersion(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        // Version resources are immutable: on a stack update, keep the version this resource
        // already published instead of minting a new one (and silently repointing every Ref).
        String previous = r.getPhysicalId();
        if (previous != null && existingVersionIsCurrent(ctx.region(), functionName, previous)) {
            int sep = previous.lastIndexOf(':');
            r.getAttributes().put("Version", previous.substring(sep + 1));
            r.getAttributes().put("FunctionArn", previous);
            return;
        }
        LambdaFunction version = lambdaService.publishVersion(ctx.region(), functionName,
                ctx.resolveOptional(props, "Description"));
        // Ref on AWS::Lambda::Version is the version-qualified function ARN.
        r.setPhysicalId(version.getFunctionArn());
        r.getAttributes().put("Version", version.getVersion());
        r.getAttributes().put("FunctionArn", version.getFunctionArn());
    }

    /** True when {@code physicalId} names a still-published version of the function in the template. */
    private boolean existingVersionIsCurrent(String region, String functionName, String physicalId) {
        int sep = physicalId.lastIndexOf(':');
        if (sep <= 0) {
            return false;
        }
        String version = physicalId.substring(sep + 1);
        String baseArn = physicalId.substring(0, sep);
        try {
            LambdaFunction fn = lambdaService.getFunction(region, functionName);
            if (!baseArn.equals(fn.getFunctionArn().replace(":$LATEST", ""))) {
                return false; // the FunctionName property now points at a different function
            }
            return lambdaService.listVersionsByFunction(region, functionName).stream()
                    .anyMatch(v -> version.equals(v.getVersion()));
        } catch (AwsException e) {
            return false;
        }
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        String aliasName = ctx.resolveOptional(props, "Name");
        String functionVersion = ctx.resolveOptional(props, "FunctionVersion");
        if (functionVersion == null) {
            functionVersion = "$LATEST";
        }
        String description = ctx.resolveOptional(props, "Description");
        Map<String, Double> routingConfig = parseRoutingConfig(props, ctx);

        LambdaAlias alias;
        if (aliasExists(ctx.region(), functionName, aliasName)) {
            // Update in place; an absent RoutingConfig clears any previous weights (empty map =
            // clear in LambdaService#updateAlias, null = keep).
            alias = lambdaService.updateAlias(ctx.region(), functionName, aliasName, functionVersion,
                    description, routingConfig != null ? routingConfig : Map.of());
        } else {
            alias = lambdaService.createAlias(ctx.region(), functionName, aliasName, functionVersion,
                    description, routingConfig);
        }
        // Renaming the alias (or retargeting its function) is a replacement: remove the alias the
        // previous update created, mirroring how AWS::Lambda::Function cleans up after itself.
        String previous = r.getPhysicalId();
        if (previous != null && !previous.equals(alias.getAliasArn())) {
            int sep = previous.lastIndexOf(':');
            if (sep > 0) {
                try {
                    lambdaService.deleteAlias(ctx.region(), previous.substring(0, sep),
                            previous.substring(sep + 1));
                } catch (AwsException ignored) {
                    // replaced alias (or its function) already gone
                }
            }
        }
        r.setPhysicalId(alias.getAliasArn());
        r.getAttributes().put("AliasArn", alias.getAliasArn());
    }

    private boolean aliasExists(String region, String functionName, String aliasName) {
        try {
            lambdaService.getAlias(region, functionName, aliasName);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    /**
     * Parses the CloudFormation {@code RoutingConfig.AdditionalVersionWeights} list
     * ({@code [{FunctionVersion, FunctionWeight}]}) into the service's version-to-weight map.
     */
    private Map<String, Double> parseRoutingConfig(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("RoutingConfig") || props.get("RoutingConfig").isNull()) {
            return null;
        }
        Map<String, Double> routing = new LinkedHashMap<>();
        JsonNode weights = props.get("RoutingConfig").path("AdditionalVersionWeights");
        if (weights.isArray()) {
            for (JsonNode weight : weights) {
                String version = ctx.engine().resolve(weight.get("FunctionVersion"));
                routing.put(version, Double.parseDouble(ctx.engine().resolve(weight.get("FunctionWeight"))));
            }
        }
        return routing;
    }
}
