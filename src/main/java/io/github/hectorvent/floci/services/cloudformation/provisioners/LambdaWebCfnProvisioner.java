package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * CloudFormation provisioning for {@code AWS::Lambda::WebFunction}, {@code WebFunctionRevision} and
 * {@code WebFunctionEndpoint}. Each physical id is the resource's ARN, the schemas' primaryIdentifier.
 * Every property of a revision is createOnly. A function's createOnly property is its name, an
 * endpoint's are its function, name, type and regions.
 */
@ApplicationScoped
public class LambdaWebCfnProvisioner implements CfnResourceProvisioner {

    static final String FUNCTION = "AWS::Lambda::WebFunction";
    static final String REVISION = "AWS::Lambda::WebFunctionRevision";
    static final String ENDPOINT = "AWS::Lambda::WebFunctionEndpoint";
    private static final Logger LOG = Logger.getLogger(LambdaWebCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ENDPOINT_CREATE_ONLY = Set.of("FunctionName", "EndpointName", "EndpointType", "Regions");
    /** Integer properties. A parameter or mapping value arrives as text, which CloudFormation coerces. */
    private static final Set<String> INTEGER_PROPERTIES =
            Set.of("TimeoutSeconds", "MaxConcurrencyPerEnvironment", "Weight", "MaxEnvironments", "RateLimit");
    private static final Pattern INTEGER_TEXT = Pattern.compile("-?[0-9]{1,10}");

    private final LambdaWebService service;

    @Inject
    public LambdaWebCfnProvisioner(LambdaWebService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FUNCTION, REVISION, ENDPOINT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case FUNCTION -> provisionFunction(r, props, ctx);
            case REVISION -> provisionRevision(r, props, ctx);
            case ENDPOINT -> provisionEndpoint(r, props, ctx);
            default -> throw new IllegalStateException("LambdaWebCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /** A revision an endpoint still routes to keeps its ConflictException, so the stack reports it. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case FUNCTION -> CfnDeletes.safeDelete("web function", physicalId,
                    () -> service.deleteFunction(region, physicalId), "ResourceNotFoundException");
            case REVISION -> CfnDeletes.safeDelete("web function revision", physicalId,
                    () -> service.deleteRevision(region, functionArnOf(physicalId), physicalId), "ResourceNotFoundException");
            case ENDPOINT -> CfnDeletes.safeDelete("web function endpoint", physicalId,
                    () -> service.deleteEndpoint(region, functionArnOf(physicalId), physicalId), "ResourceNotFoundException");
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
        resource.getAttributes().remove(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /** An in-place change is undone from the snapshot taken before it; the snapshot stays until that succeeds. */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            LOG.errorv("Could not parse the Lambda Web update snapshot of {0}: {1}", resource.getLogicalId(), e.getMessage());
            return false;
        }
        String region = snapshot.path("region").asText();
        String functionArn = snapshot.path("functionArn").asText();
        if ("endpoint".equals(snapshot.path("type").asText())) {
            service.updateEndpoint(region, functionArn, snapshot.path("endpointName").asText(), snapshot.get("patch"));
        } else {
            service.replaceTags(region, functionArn,
                    MAPPER.convertValue(snapshot.get("tags"), new TypeReference<Map<String, String>>() {}));
        }
        resource.getAttributes().remove(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    private void provisionFunction(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = require(props, "FunctionName", r, ctx);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        WebFunction fn = ctx.isUpdate() ? find(() -> service.getFunction(ctx.region(), name)) : null;
        if (fn != null && ctx.reusesPriorEntity(fn.functionArn)) {
            if (!tags.equals(fn.tags)) {
                r.getAttributes().put(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR, tagSnapshot(ctx.region(), fn).toString());
                fn = service.replaceTags(ctx.region(), name, tags);
            }
        } else {
            fn = service.createFunctionShell(ctx.region(), name, tags);
        }
        r.setPhysicalId(fn.functionArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", fn.functionArn);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The function is active.");
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(fn.createdAt));
        r.getAttributes().put("UpdatedAt", LambdaWebService.formatTimestamp(fn.updatedAt));
    }

    private void provisionRevision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = require(props, "FunctionName", r, ctx);
        JsonNode request = toCamelCase(ctx.engine().resolveNodeOmittingNoValue(props), Set.of("FunctionName"));
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        WebFunctionRevision revision = null;
        if (reusablePrior(ctx, attributesBefore)) {
            WebFunction fn = find(() -> service.getFunction(ctx.region(), functionName));
            String prior = ctx.priorPhysicalId();
            WebFunctionRevision existing = find(() -> service.getRevision(ctx.region(), functionArnOf(prior), prior));
            if (fn != null && existing != null && existing.functionArn.equals(fn.functionArn)
                    && service.revisionMatches(existing, request)) {
                revision = existing;
            }
        }
        if (revision == null) {
            revision = service.createRevision(ctx.region(), functionName, request);
        }
        r.setPhysicalId(revision.revisionArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", revision.functionArn);
        r.getAttributes().put("RevisionArn", revision.revisionArn);
        r.getAttributes().put("RevisionId", revision.revisionId);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The revision is active.");
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(revision.createdAt));
    }

    private void provisionEndpoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String functionName = require(props, "FunctionName", r, ctx);
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props);
        if (resolved.has("AutoDeploymentMode")) {
            // Not in the registry schema: a CloudFormation endpoint routes by RevisionWeights only.
            throw new IllegalArgumentException(r.getResourceType() + " has no AutoDeploymentMode property ("
                    + r.getLogicalId() + ")");
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        WebFunctionEndpoint endpoint = null;
        if (reusablePrior(ctx, attributesBefore)) {
            WebFunction fn = find(() -> service.getFunction(ctx.region(), functionName));
            String prior = ctx.priorPhysicalId();
            WebFunctionEndpoint existing = find(() -> service.getEndpoint(ctx.region(), functionArnOf(prior), prior));
            if (fn != null && existing != null && existing.functionArn.equals(fn.functionArn)) {
                if (sameIdentity(existing, resolved, ctx.region())) {
                    r.getAttributes().put(CfnRollback.LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR,
                            endpointSnapshot(ctx.region(), existing).toString());
                    endpoint = service.updateEndpoint(ctx.region(), functionName, existing.endpointName,
                            endpointPatch(resolved, ctx)).after();
                } else if (existing.endpointName.equals(resolved.path("EndpointName").asText())) {
                    throw CfnExceptions.customNamedReplacement(existing.endpointName);
                }
            }
        }
        if (endpoint == null) {
            endpoint = service.createEndpoint(ctx.region(), functionName, toCamelCase(resolved, Set.of("FunctionName")));
        }
        r.setPhysicalId(endpoint.endpointArn);
        ReplacementCleanup.record(r, ctx, attributesBefore);
        r.getAttributes().put("FunctionArn", endpoint.functionArn);
        r.getAttributes().put("EndpointArn", endpoint.endpointArn);
        r.getAttributes().put("DomainName", endpoint.domainName);
        r.getAttributes().put("State", "Active");
        r.getAttributes().put("StateReason", "The endpoint is active and invokable.");
        if (endpoint.updateStatus != null) {
            r.getAttributes().put("UpdateStatus", endpoint.updateStatus);
            r.getAttributes().put("UpdateStatusReason", "The endpoint is updated.");
        } else {
            r.getAttributes().remove("UpdateStatus");
            r.getAttributes().remove("UpdateStatusReason");
        }
        r.getAttributes().put("CreatedAt", LambdaWebService.formatTimestamp(endpoint.createdAt));
        r.getAttributes().put("UpdatedAt", LambdaWebService.formatTimestamp(endpoint.updatedAt));
    }

    /** The in-place update. A property the previous template declared and this one dropped is cleared. */
    private static ObjectNode endpointPatch(JsonNode resolved, ProvisionContext ctx) {
        ObjectNode patch = (ObjectNode) toCamelCase(resolved, ENDPOINT_CREATE_ONLY);
        if (!patch.has("scalingConfig") && ctx.previouslyDeclared("ScalingConfig")) {
            patch.putObject("scalingConfig");
        }
        if (!patch.has("throttleConfig") && ctx.previouslyDeclared("ThrottleConfig")) {
            patch.putObject("throttleConfig");
        }
        if (!patch.has("description") && ctx.previouslyDeclared("Description")) {
            patch.put("description", "");
        }
        return patch;
    }

    private static ObjectNode tagSnapshot(String region, WebFunction fn) {
        ObjectNode snapshot = MAPPER.createObjectNode().put("type", "function").put("region", region)
                .put("functionArn", fn.functionArn);
        ObjectNode tags = snapshot.putObject("tags");
        fn.tags.forEach(tags::put);
        return snapshot;
    }

    /** The endpoint's mutable configuration in the shape {@code UpdateWebFunctionEndpoint} takes. */
    private static ObjectNode endpointSnapshot(String region, WebFunctionEndpoint endpoint) {
        ObjectNode snapshot = MAPPER.createObjectNode().put("type", "endpoint").put("region", region)
                .put("functionArn", endpoint.functionArn).put("endpointName", endpoint.endpointName);
        ObjectNode patch = snapshot.putObject("patch");
        patch.put("authType", endpoint.authType);
        patch.put("description", endpoint.description == null ? "" : endpoint.description);
        patch.put("autoDeploymentMode", endpoint.autoDeploymentMode);
        if (!"LatestRevision".equals(endpoint.autoDeploymentMode)) {
            ArrayNode weights = patch.putArray("revisionWeights");
            for (WebFunctionEndpoint.RevisionWeight weight : endpoint.revisionWeights) {
                weights.addObject().put("revisionId", weight.revisionId).put("weight", weight.weight);
            }
        }
        ObjectNode scaling = patch.putObject("scalingConfig");
        if (endpoint.scalingConfig != null && endpoint.scalingConfig.maxEnvironments != null) {
            scaling.put("maxEnvironments", endpoint.scalingConfig.maxEnvironments);
        }
        ObjectNode throttle = patch.putObject("throttleConfig");
        if (endpoint.throttleConfig != null && endpoint.throttleConfig.rateLimit != null) {
            throttle.put("rateLimit", endpoint.throttleConfig.rateLimit);
        }
        return snapshot;
    }

    private static boolean reusablePrior(ProvisionContext ctx, Map<String, String> attributesBefore) {
        return ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
    }

    private static boolean sameIdentity(WebFunctionEndpoint existing, JsonNode props, String homeRegion) {
        if (!existing.endpointName.equals(props.path("EndpointName").asText())
                || !existing.endpointType.equals(props.path("EndpointType").asText())) {
            return false;
        }
        Set<String> regions = new LinkedHashSet<>();
        regions.add(homeRegion);
        props.path("Regions").forEach(region -> regions.add(region.asText()));
        return regions.equals(new LinkedHashSet<>(existing.regions));
    }

    private static <T> T find(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("No prior web function entity to reuse: {0}", e.getMessage());
            return null;
        }
    }

    private static String require(JsonNode props, String name, StackResource r, ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(r.getResourceType() + " requires " + name + " (" + r.getLogicalId() + ")");
        }
        return value;
    }

    private static String functionArnOf(String childArn) {
        int child = childArn.indexOf("/revision/");
        if (child < 0) {
            child = childArn.indexOf("/endpoint/");
        }
        return child < 0 ? childArn : childArn.substring(0, child);
    }

    /** The keys of {@code EnvironmentVariables} are the user's own and stay as written. */
    private static JsonNode toCamelCase(JsonNode node, Set<String> skip) {
        if (node == null || !node.isObject()) {
            return MAPPER.createObjectNode();
        }
        ObjectNode out = MAPPER.createObjectNode();
        node.fields().forEachRemaining(entry -> {
            if (skip.contains(entry.getKey())) {
                return;
            }
            String key = Character.toLowerCase(entry.getKey().charAt(0)) + entry.getKey().substring(1);
            JsonNode value = entry.getValue();
            if (INTEGER_PROPERTIES.contains(entry.getKey()) && value.isTextual()
                    && INTEGER_TEXT.matcher(value.textValue()).matches()) {
                value = MAPPER.getNodeFactory().numberNode(Long.parseLong(value.textValue()));
            }
            out.set(key, "EnvironmentVariables".equals(entry.getKey()) ? value : lowerKeys(value));
        });
        return out;
    }

    private static JsonNode lowerKeys(JsonNode node) {
        if (node.isObject()) {
            return toCamelCase(node, Set.of());
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(item -> out.add(lowerKeys(item)));
            return out;
        }
        return node;
    }
}
