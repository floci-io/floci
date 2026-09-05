package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Lambda::EventSourceMapping}.
 */
@ApplicationScoped
public class LambdaEventSourceMappingCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            throw new IllegalArgumentException("FunctionName is required for a lambda event source mapping");
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

        String batchSize = ctx.resolveOptional(props, "BatchSize");
        if (batchSize != null) {
            try {
                req.put("BatchSize", Integer.parseInt(batchSize));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "Value of property BatchSize must be an integer.", 400);
            }
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

        if (ctx.isUpdate() && ctx.priorPhysicalId() != null) {
            String uuid = ctx.priorPhysicalId();
            lambdaService.updateEventSourceMapping(uuid, req);
            r.setPhysicalId(uuid);
            r.getAttributes().put("Id", uuid);
        } else {
            var esm = lambdaService.createEventSourceMapping(ctx.region(), req);
            r.setPhysicalId(esm.getUuid());
            r.getAttributes().put("Id", esm.getUuid());
        }
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
