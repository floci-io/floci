package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Lambda::EventSourceMapping}.
 */
@ApplicationScoped
public class LambdaEventSourceMappingCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Options that update in place: omitting one from an UpdateStack template clears it.
    private static final List<String> CLEARABLE_OPTIONS = List.of(
            "MaximumBatchingWindowInSeconds", "MaximumRetryAttempts", "MaximumRecordAgeInSeconds",
            "BisectBatchOnFunctionError", "FilterCriteria", "DestinationConfig", "ScalingConfig");

    private final LambdaService lambdaService;

    @Inject
    public LambdaEventSourceMappingCfnProvisioner(LambdaService lambdaService) {
        this.lambdaService = lambdaService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Lambda::EventSourceMapping");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (!"AWS::Lambda::EventSourceMapping".equals(r.getResourceType())) {
            throw new IllegalStateException(
                    "LambdaEventSourceMappingCfnProvisioner cannot handle " + r.getResourceType());
        }

        Map<String, Object> req = new HashMap<>();
        String functionName = ctx.resolveOptional(props, "FunctionName");
        if (functionName == null || functionName.isBlank()) {
            throw new AwsException("ValidationError",
                    "Property FunctionName is required for AWS::Lambda::EventSourceMapping", 400);
        }
        req.put("FunctionName", functionName);

        String eventSourceArn = ctx.resolveOptional(props, "EventSourceArn");
        if (eventSourceArn != null) {
            req.put("EventSourceArn", eventSourceArn);
        }

        String enabledStr = ctx.resolveOptional(props, "Enabled");
        if (enabledStr != null) {
            req.put("Enabled", Boolean.parseBoolean(enabledStr));
        }

        for (String name : List.of("BatchSize", "MaximumBatchingWindowInSeconds",
                "MaximumRetryAttempts", "MaximumRecordAgeInSeconds")) {
            putInteger(req, props, ctx, name);
        }

        String bisect = ctx.resolveOptional(props, "BisectBatchOnFunctionError");
        if (bisect != null && !bisect.isEmpty()) {
            if (!"true".equalsIgnoreCase(bisect) && !"false".equalsIgnoreCase(bisect)) {
                throw new AwsException("ValidationError", "BisectBatchOnFunctionError must be a boolean", 400);
            }
            req.put("BisectBatchOnFunctionError", Boolean.parseBoolean(bisect));
        }

        for (String name : List.of("FilterCriteria", "DestinationConfig", "ScalingConfig")) {
            if (props != null && props.has(name)) {
                JsonNode resolved = ctx.engine().resolveNode(props.get(name));
                if (resolved == null || resolved.isNull() || (resolved.isTextual() && resolved.textValue().isEmpty())) {
                    continue;
                }
                if (!resolved.isObject()) {
                    throw new AwsException("ValidationError", name + " must be an object", 400);
                }
                req.put(name, MAPPER.convertValue(withoutNoValue(resolved), Map.class));
            }
        }
        if (req.get("ScalingConfig") instanceof Map<?, ?> scaling
                && scaling.get("MaximumConcurrency") instanceof String concurrency) {
            Map<Object, Object> coerced = new HashMap<>(scaling);
            try {
                coerced.put("MaximumConcurrency", Integer.parseInt(concurrency));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "Value of property ScalingConfig.MaximumConcurrency must be an integer.", 400);
            }
            req.put("ScalingConfig", coerced);
        }

        String startingPosition = ctx.resolveOptional(props, "StartingPosition");
        if (startingPosition != null) {
            req.put("StartingPosition", startingPosition);
        }

        String startingPositionTimestamp = ctx.resolveOptional(props, "StartingPositionTimestamp");
        if (startingPositionTimestamp != null) {
            try {
                double timestamp = Double.parseDouble(startingPositionTimestamp);
                if (!Double.isFinite(timestamp)) {
                    throw new NumberFormatException("Non-finite timestamp");
                }
                req.put("StartingPositionTimestamp", timestamp);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "Value of property StartingPositionTimestamp must be a number.", 400);
            }
        }

        List<String> functionResponseTypes = ctx.resolveStringList(props, "FunctionResponseTypes");
        if (!functionResponseTypes.isEmpty()) {
            req.put("FunctionResponseTypes", functionResponseTypes);
        }

        if (props != null && props.has("SelfManagedEventSource")) {
            JsonNode resolvedSource = ctx.engine().resolveNode(props.get("SelfManagedEventSource"));
            if (resolvedSource != null && !resolvedSource.isNull()) {
                req.put("SelfManagedEventSource", MAPPER.convertValue(resolvedSource, Map.class));
            }
        }

        List<String> topics = ctx.resolveStringList(props, "Topics");
        if (!topics.isEmpty()) {
            req.put("Topics", topics);
        }

        if (props != null && props.has("SourceAccessConfigurations") && !props.get("SourceAccessConfigurations").isNull()) {
            JsonNode resolvedAccess = ctx.engine().resolveNode(props.get("SourceAccessConfigurations"));
            if (resolvedAccess != null && !resolvedAccess.isNull()) {
                req.put("SourceAccessConfigurations", MAPPER.convertValue(resolvedAccess, List.class));
            }
        } else if (ctx.isUpdate()) {
            req.put("SourceAccessConfigurations", null);
        }

        if (ctx.isUpdate()) {
            for (String name : CLEARABLE_OPTIONS) {
                req.putIfAbsent(name, null);
            }
        }

        if (ctx.isUpdate() && ctx.priorPhysicalId() != null) {
            String uuid = ctx.priorPhysicalId();
            EventSourceMapping existing = findExisting(uuid);
            if (existing != null) {
                rejectIfChanged("EventSourceArn", existing.getEventSourceArn(), (String) req.get("EventSourceArn"));
                rejectIfChanged("StartingPosition", existing.getStartingPosition(), (String) req.get("StartingPosition"));

                Long existingTs = existing.getStartingPositionTimestamp();
                Long requestedTs = req.get("StartingPositionTimestamp") instanceof Number num
                        ? Math.round(num.doubleValue() * 1000.0)
                        : null;
                if (!Objects.equals(existingTs, requestedTs)) {
                    throw new AwsException("ValidationError",
                            "Updating StartingPositionTimestamp requires resource replacement, which is not supported.", 400);
                }

                if (!Objects.equals(existing.getSelfManagedEventSource(), req.get("SelfManagedEventSource"))) {
                    throw new AwsException("ValidationError",
                            "Updating SelfManagedEventSource requires resource replacement, which is not supported.", 400);
                }
            }
            lambdaService.updateEventSourceMapping(uuid, req);
            String esmArn = AwsArnUtils.Arn.of("lambda", ctx.region(), ctx.accountId(), "event-source-mapping:" + uuid).toString();
            r.setPhysicalId(uuid);
            r.getAttributes().put("Id", uuid);
            r.getAttributes().put("EventSourceMappingArn", esmArn);
        } else {
            var esm = lambdaService.createEventSourceMapping(ctx.region(), req);
            String esmArn = AwsArnUtils.Arn.of("lambda", ctx.region(), ctx.accountId(), "event-source-mapping:" + esm.getUuid()).toString();
            r.setPhysicalId(esm.getUuid());
            r.getAttributes().put("Id", esm.getUuid());
            r.getAttributes().put("EventSourceMappingArn", esmArn);
        }
    }

    private static void putInteger(Map<String, Object> req, JsonNode props, ProvisionContext ctx, String name) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            req.put(name, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "Value of property " + name + " must be an integer.", 400);
        }
    }

    /**
     * Drops the empty text a nested {@code AWS::NoValue} resolves to, as CloudFormation removes such
     * members. Returns null for an empty text node.
     */
    private static JsonNode withoutNoValue(JsonNode node) {
        if (node.isTextual() && node.textValue().isEmpty()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> {
                JsonNode value = withoutNoValue(e.getValue());
                if (value != null) {
                    out.set(e.getKey(), value);
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                JsonNode value = withoutNoValue(item);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        return node;
    }

    private EventSourceMapping findExisting(String uuid) {
        try {
            return lambdaService.getEventSourceMapping(uuid);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private void rejectIfChanged(String property, String existing, String requested) {
        String a = existing == null || existing.isBlank() ? null : existing;
        String b = requested == null || requested.isBlank() ? null : requested;
        if (Objects.equals(a, b)) {
            return;
        }
        throw new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (!"AWS::Lambda::EventSourceMapping".equals(resourceType) || physicalId == null) {
            return;
        }
        CfnDeletes.safeDelete("event source mapping", physicalId,
                () -> lambdaService.deleteEventSourceMapping(physicalId), "ResourceNotFoundException");
    }
}
