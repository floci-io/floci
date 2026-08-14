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
 * CloudFormation provisioning for {@code AWS::Lambda::Permission}. {@code AWS::Lambda::Version} and
 * {@code AWS::Lambda::Alias} belong to {@link LambdaVersionAliasCfnProvisioner}.
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

    private final LambdaService lambdaService;

    @Inject
    public LambdaAddressingCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::Permission");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (!"AWS::Lambda::Permission".equals(r.getResourceType())) {
            throw new IllegalStateException(
                    "LambdaPermissionCfnProvisioner cannot handle " + r.getResourceType());
        }
        provisionPermission(r, props, ctx);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!"AWS::Lambda::Permission".equals(resourceType) || physicalId == null) {
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
