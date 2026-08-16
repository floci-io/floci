package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Lambda::Version} and {@code AWS::Lambda::Alias}.
 *
 * <p>Covers only these two types, not {@code AWS::Lambda::Function} — that one still lives in
 * {@code CloudFormationResourceProvisioner}'s switch, which the registry falls through to. The
 * two types are what SAM's {@code AutoPublishAlias} expands into, and without them an
 * alias-qualified invoke ({@code <function>:production}) fails with "Alias not found" even though
 * the template declared the alias and the stack reported CREATE_COMPLETE.
 *
 * <p>The Lambda service already implements both operations, so this is plumbing rather than new
 * service behavior.
 */
@ApplicationScoped
public class LambdaVersionAliasCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(LambdaVersionAliasCfnProvisioner.class);

    private final LambdaService lambdaService;

    @Inject
    public LambdaVersionAliasCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::Version", "AWS::Lambda::Alias");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::Lambda::Version" -> provisionVersion(r, props, ctx);
            case "AWS::Lambda::Alias" -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "LambdaVersionAliasCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!"AWS::Lambda::Alias".equals(resourceType)) {
            // AWS::Lambda::Version has no backing delete: Lambda exposes no DeleteVersion, and
            // deleting the function drops its versions with it.
            return;
        }
        // The physical id is the alias ARN (arn:…:function:<name>:<alias>), so the function name
        // and alias name come back out of it.
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(physicalId);
        if (ref.qualifier() == null) {
            LOG.debugv("Not an alias ARN, nothing to delete: {0}", physicalId);
            return;
        }
        try {
            lambdaService.deleteAlias(region, ref.name(), ref.qualifier());
        } catch (AwsException e) {
            // Already gone (function deleted first, or a partial rollback) — deleting a stack
            // must stay idempotent.
            LOG.debugv("Alias {0} already absent: {1}", physicalId, e.getMessage());
        }
    }

    private void provisionVersion(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = ctx.resolveOptional(props, "FunctionName");
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException(
                    "AWS::Lambda::Version requires FunctionName (" + r.getLogicalId() + ")");
        }
        String description = ctx.resolveOptional(props, "Description");

        // Version resources are immutable in CloudFormation. An update re-invokes provision with
        // the prior physical id, and publishing again would mint a new version and repoint every
        // Ref to it, so the version this resource already published is kept while it still exists.
        String previous = r.getPhysicalId();
        if (previous != null && existingVersionIsCurrent(ctx.region(), functionName, previous)) {
            r.getAttributes().put("Version", previous.substring(previous.lastIndexOf(':') + 1));
            r.getAttributes().put("FunctionArn", previous);
            return;
        }

        LambdaFunction version = lambdaService.publishVersion(ctx.region(), functionName, description);

        // Matches real CloudFormation: Ref returns the qualified ARN, GetAtt Version the version
        // number (what the Alias resource references), GetAtt FunctionArn the qualified ARN.
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
        if (functionName == null || functionName.isBlank() || aliasName == null || aliasName.isBlank()) {
            throw new IllegalArgumentException(
                    "AWS::Lambda::Alias requires FunctionName and Name (" + r.getLogicalId() + ")");
        }
        String functionVersion = ctx.resolveOptional(props, "FunctionVersion");
        String description = ctx.resolveOptional(props, "Description");

        Map<String, Double> routingConfig = parseRoutingConfig(props, ctx);

        LambdaAlias alias;
        try {
            alias = lambdaService.createAlias(ctx.region(), functionName, aliasName,
                    functionVersion, description, routingConfig);
        } catch (AwsException e) {
            // An alias surviving from a prior deploy makes create conflict; converge on it rather
            // than failing the stack, so a redeploy is idempotent.
            if (!"ResourceConflictException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Alias {0} exists on {1}, updating instead", aliasName, functionName);
            // An absent RoutingConfig clears any previous weights, since an empty map clears in
            // LambdaService#updateAlias while null keeps what is there.
            alias = lambdaService.updateAlias(ctx.region(), functionName, aliasName,
                    functionVersion, description, routingConfig != null ? routingConfig : Map.of());
        }

        r.setPhysicalId(alias.getAliasArn());
        r.getAttributes().put("AliasArn", alias.getAliasArn());
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
