package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaUrlConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the types that address a function from outside it:
 * {@code AWS::Lambda::Permission} and {@code AWS::Lambda::Url}. {@code AWS::Lambda::Version} and
 * {@code AWS::Lambda::Alias} belong to {@link LambdaVersionAliasCfnProvisioner}.
 *
 * <p>{@code AWS::Lambda::Url}: the physical id is the {@code FunctionArn} the URL is attached to
 * (the alias ARN when {@code Qualifier} names one), which is both the schema's primary identifier
 * and enough to address the config again, since a qualified ARN parses back into function and
 * qualifier. {@code Fn::GetAtt FunctionUrl} is the URL itself; a template that omits the attribute
 * would otherwise export the literal string {@code "LogicalId.FunctionUrl"}. The CloudFormation
 * property names for the URL are already the ones the Lambda API takes, {@code Cors} members
 * included, so they are passed through rather than translated.
 *
 * <p>The physical id doubles as the delete handle, since {@link #delete(String, String, String)}
 * only receives the physical id. It stores {@code <functionName>|<statementId>}, and '|' cannot
 * appear in a function name or ARN.
 *
 * <p>Stack updates re-provision every resource in the template, so this is idempotent. The previous
 * statement is captured and removed before the replacement is added, since AddPermission rejects a
 * duplicate Sid, and it goes back if the replacement is rejected.
 */
@ApplicationScoped
public class LambdaAddressingCfnProvisioner implements CfnResourceProvisioner {

    private static final String PERMISSION = "AWS::Lambda::Permission";
    private static final String URL = "AWS::Lambda::Url";

    private final LambdaService lambdaService;

    @Inject
    public LambdaAddressingCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(PERMISSION, URL);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case PERMISSION -> provisionPermission(r, props, ctx);
            case URL -> provisionUrl(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaAddressingCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null) {
            return;
        }
        if (URL.equals(resourceType)) {
            // The physical id is the function (or alias) ARN, which resolveWithQualifier splits back
            // into name and qualifier, so no create-time attribute is needed to find the config.
            CfnDeletes.safeDelete("function URL", physicalId,
                    () -> lambdaService.deleteFunctionUrlConfig(region, physicalId, null),
                    "ResourceNotFoundException");
            return;
        }
        if (!PERMISSION.equals(resourceType)) {
            return;
        }
        int sep = physicalId.lastIndexOf('|');
        if (sep <= 0) {
            return;
        }
        try {
            // Null qualifier: this provisioner only ever adds statements on the unqualified
            // function, so that is the resource the statement is scoped to.
            lambdaService.removePermission(region, physicalId.substring(0, sep), null,
                    physicalId.substring(sep + 1));
        } catch (AwsException e) {
            // The statement or its function is already gone, so a DeleteStack retry must not
            // fail the resource for work that is already done.
            if (!"ResourceNotFoundException".equals(e.getErrorCode()) && e.getHttpStatus() != 404) {
                throw e;
            }
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
            lambdaService.addPermission(ctx.region(), functionName, null, request);
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

    private void provisionUrl(StackResource r, JsonNode props, ProvisionContext ctx) {
        String targetFunctionArn = ctx.resolveOptional(props, "TargetFunctionArn");
        if (targetFunctionArn == null || targetFunctionArn.isBlank()) {
            throw new AwsException("ValidationError", URL + " requires TargetFunctionArn.", 400);
        }
        String qualifier = ctx.resolveOptional(props, "Qualifier");
        String authType = ctx.resolveOptional(props, "AuthType");

        Map<String, Object> request = new HashMap<>();
        // AWS_IAM in the template stays AWS_IAM; the service's own default is NONE, which is also
        // what the Lambda API defaults an omitted AuthType to.
        if (authType != null && !authType.isBlank()) {
            request.put("AuthType", authType);
        }
        String invokeMode = ctx.resolveOptional(props, "InvokeMode");
        if (invokeMode != null && !invokeMode.isBlank()) {
            request.put("InvokeMode", invokeMode);
        }
        Map<String, Object> cors = corsRequest(props, ctx);
        boolean updating = hasUrlConfig(ctx.region(), targetFunctionArn, qualifier);
        if (cors != null) {
            request.put("Cors", cors);
        } else if (updating) {
            // Present and null, which is how UpdateFunctionUrlConfig is told to clear the policy;
            // omitting the member leaves the CORS rules the template no longer declares in place.
            request.put("Cors", null);
        }

        // provision is the update path too, and CreateFunctionUrlConfig answers 409 on a function
        // that already has one. Which call to make follows from whether the *intended* target
        // already has a config, not from isUpdate(): TargetFunctionArn and Qualifier are
        // create-only, so an update that changed either has to create against the new function.
        LambdaUrlConfig urlConfig = updating
                ? lambdaService.updateFunctionUrlConfig(ctx.region(), targetFunctionArn, qualifier, request)
                : lambdaService.createFunctionUrlConfig(ctx.region(), targetFunctionArn, qualifier, request);

        String priorPhysicalId = ctx.priorPhysicalId();
        if (ctx.isUpdate() && !urlConfig.getFunctionArn().equals(priorPhysicalId)) {
            // The URL moved to a different function. CloudFormation deletes the entity a replacing
            // update displaced; a function URL is addressed only through its function, so the one
            // left on the previous function would otherwise stay reachable forever.
            CfnDeletes.safeDelete("displaced function URL", priorPhysicalId,
                    () -> lambdaService.deleteFunctionUrlConfig(ctx.region(), priorPhysicalId, null),
                    "ResourceNotFoundException");
        }

        r.setPhysicalId(urlConfig.getFunctionArn());
        r.getAttributes().put("FunctionArn", urlConfig.getFunctionArn());
        r.getAttributes().put("FunctionUrl", urlConfig.getFunctionUrl());
    }

    private boolean hasUrlConfig(String region, String functionName, String qualifier) {
        try {
            return lambdaService.getFunctionUrlConfig(region, functionName, qualifier) != null;
        } catch (AwsException e) {
            // Only "there is no config" means create. A missing function, or a qualifier that does
            // not match the ARN, has to fail the resource rather than be retried as a create.
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * The {@code Cors} block as the Lambda API takes it, or null when the template has none.
     * {@code AllowCredentials} must arrive as a Boolean and {@code MaxAge} as a number: the service
     * reads them with {@code Boolean.TRUE.equals} and an int coercion, so the resolved strings the
     * template engine hands back for scalars would read as false and 0.
     */
    private Map<String, Object> corsRequest(JsonNode props, ProvisionContext ctx) {
        JsonNode cors = props == null ? null : props.get("Cors");
        if (cors == null || cors.isNull() || !cors.isObject()) {
            return null;
        }
        Map<String, Object> request = new HashMap<>();
        putList(request, "AllowHeaders", ctx.resolveStringList(cors, "AllowHeaders"));
        putList(request, "AllowMethods", ctx.resolveStringList(cors, "AllowMethods"));
        putList(request, "AllowOrigins", ctx.resolveStringList(cors, "AllowOrigins"));
        putList(request, "ExposeHeaders", ctx.resolveStringList(cors, "ExposeHeaders"));
        String allowCredentials = ctx.resolveOptional(cors, "AllowCredentials");
        if (allowCredentials != null && !allowCredentials.isBlank()) {
            request.put("AllowCredentials", Boolean.parseBoolean(allowCredentials));
        }
        String maxAge = ctx.resolveOptional(cors, "MaxAge");
        if (maxAge != null && !maxAge.isBlank()) {
            try {
                request.put("MaxAge", Integer.valueOf(maxAge.trim()));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        URL + " Cors.MaxAge must be a number, got: " + maxAge, 400);
            }
        }
        return request;
    }

    private static void putList(Map<String, Object> request, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            request.put(key, values);
        }
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
            lambdaService.removePermission(region, functionName, null, statementId);
        } catch (AwsException ignored) {
            // statement or function already gone — nothing to replace, nothing to restore
            return null;
        }
        return statement == null ? null : new RemovedStatement(functionName, statement);
    }

    private Map<String, Object> findStatement(String region, String functionName, String statementId) {
        try {
            Object policy = lambdaService.getPolicy(region, functionName, null).get("policy");
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

}
