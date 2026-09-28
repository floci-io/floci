package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.CustomResourceResponseStore;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Provisions {@code AWS::CloudFormation::CustomResource} and every {@code Custom::*} type, moved out
 * of the former CloudFormation monolith.
 *
 * <p>A custom resource is backed by a Lambda named by its {@code ServiceToken}. CloudFormation
 * invokes that Lambda with a request event and the Lambda PUTs its result to the event's
 * {@code ResponseURL} (it does NOT return it). Floci points {@code ResponseURL} at
 * {@code CfnResponseController} and, because the invoke is synchronous, reads the captured response
 * as soon as the handler returns. Pattern 1 only: single-Lambda synchronous handlers (e.g. CDK
 * BucketDeployment). The async Provider framework (onEvent/isComplete polling) is not emulated.
 *
 * <p>The {@code Custom::*} prefix is not enumerable, so it is not listed in {@link #resourceTypes()};
 * {@link CloudFormationResourceRegistry#forType} routes any unmatched {@code Custom::} type here.
 */
@ApplicationScoped
public class CustomResourceCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CustomResourceCfnProvisioner.class);

    /** Reserved attribute keys used to carry custom-resource state to the later Delete invocation. */
    private static final String CR_SERVICE_TOKEN_ATTR = "__FlociServiceToken";
    private static final String CR_PROPERTIES_ATTR = "__FlociResourceProperties";
    /**
     * How long to wait for the Lambda's ResponseURL callback after the synchronous invoke returns.
     * The invoke already blocks until the handler finishes, so this only covers a PUT that lands
     * fractionally after the container returns control.
     */
    private static final Duration CR_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;
    private final CustomResourceResponseStore customResourceResponseStore;
    private final ContainerReachableEndpoint reachableEndpoint;

    @Inject
    public CustomResourceCfnProvisioner(LambdaService lambdaService, ObjectMapper objectMapper,
                                        CustomResourceResponseStore customResourceResponseStore,
                                        ContainerReachableEndpoint reachableEndpoint) {
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
        this.customResourceResponseStore = customResourceResponseStore;
        this.reachableEndpoint = reachableEndpoint;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CloudFormation::CustomResource");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String accountId = ctx.accountId();
        String stackName = ctx.stackName();
        if (props == null || !props.has("ServiceToken")) {
            throw new AwsException("ValidationError",
                    "Custom resource " + r.getLogicalId() + " is missing ServiceToken", 400);
        }
        String serviceToken = engine.resolve(props.get("ServiceToken"));
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new AwsException("ValidationError",
                    "Custom resource " + r.getLogicalId() + " has an unresolved ServiceToken", 400);
        }

        // Resolve intrinsics to concrete values. CloudFormation keeps ServiceToken inside
        // ResourceProperties (and also surfaces it at the top level of the event), so we leave it
        // in place here. CloudFormation stringifies every scalar in ResourceProperties
        // (true -> "true", 5 -> "5") while preserving list/map structure; handlers (e.g. CDK's)
        // rely on this and call String methods on the values, so we must match it.
        JsonNode resolvedProps = engine.resolveNode(props);
        ObjectNode resolved = resolvedProps.isObject()
                ? ((ObjectNode) resolvedProps).deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resourceProperties = (ObjectNode) stringifyScalars(resolved);

        boolean isUpdate = r.getPhysicalId() != null;
        String requestType = isUpdate ? "Update" : "Create";
        String priorPhysicalId = isUpdate ? r.getPhysicalId() : null;

        // On Update, CloudFormation includes the previous ResourceProperties so the handler can diff.
        // The prior values were stashed at the last create/update; read them before we overwrite below.
        ObjectNode oldResourceProperties = isUpdate ? readStashedProperties(r) : null;

        // CloudFormation invokes a custom resource's Update handler only when its resolved
        // properties changed (UserGuide/template-custom-resources-sns.md: "During a stack update,
        // if no changes are made to a custom resource, CloudFormation will not send any requests
        // to it."). Replaying every custom resource during an unrelated stack update can repeat
        // non-idempotent side effects. The prior resolved properties are already stashed on the
        // resource, so an exact match is a safe no-op that preserves physical ID and attributes.
        if (oldResourceProperties != null && oldResourceProperties.equals(resourceProperties)) {
            return;
        }

        JsonNode response = invokeCustomResourceHandler(serviceToken, requestType, r.getLogicalId(),
                r.getResourceType(), priorPhysicalId, resourceProperties, oldResourceProperties,
                region, accountId, stackName);

        String status = response.path("Status").asText("FAILED");
        if (!"SUCCESS".equals(status)) {
            throw new AwsException("CustomResourceFailed",
                    "Custom resource handler reported FAILED: "
                            + response.path("Reason").asText("(no reason given)"), 400);
        }

        String returnedPhysicalId = response.path("PhysicalResourceId").asText(null);
        if (returnedPhysicalId != null && !returnedPhysicalId.isBlank()) {
            r.setPhysicalId(returnedPhysicalId);
        } else if (priorPhysicalId != null) {
            r.setPhysicalId(priorPhysicalId);
        } else {
            r.setPhysicalId(r.getLogicalId() + "-" + UUID.randomUUID().toString().substring(0, 12));
        }

        // Data.* become Fn::GetAtt attributes on the custom resource.
        JsonNode data = response.path("Data");
        if (data.isObject()) {
            data.fields().forEachRemaining(e ->
                    r.getAttributes().put(e.getKey(), nodeToAttributeValue(e.getValue())));
        }

        // Stash what a later Delete invocation needs (delete() only gets the StackResource).
        r.getAttributes().put(CR_SERVICE_TOKEN_ATTR, serviceToken);
        r.getAttributes().put(CR_PROPERTIES_ATTR, resourceProperties.toString());
    }

    @Override
    public void delete(StackResource r, String region) {
        String serviceToken = r.getAttributes().get(CR_SERVICE_TOKEN_ATTR);
        if (serviceToken == null || serviceToken.isBlank()) {
            LOG.debugv("Custom resource {0} has no stored ServiceToken; skipping Delete", r.getLogicalId());
            return;
        }
        ObjectNode stashed = readStashedProperties(r);
        ObjectNode resourceProperties = stashed != null ? stashed : objectMapper.createObjectNode();
        try {
            JsonNode response = invokeCustomResourceHandler(serviceToken, "Delete", r.getLogicalId(),
                    r.getResourceType(), r.getPhysicalId(), resourceProperties, null, region,
                    accountFromArn(serviceToken), "");
            if (!"SUCCESS".equals(response.path("Status").asText("FAILED"))) {
                LOG.warnv("Custom resource {0} Delete reported FAILED: {1}",
                        r.getLogicalId(), response.path("Reason").asText("(no reason given)"));
            }
        } catch (Exception e) {
            // Best-effort, consistent with the rest of the delete path.
            LOG.debugv("Custom resource {0} Delete invocation failed: {1}", r.getLogicalId(), e.getMessage());
        }
    }

    // Reads the ResourceProperties stashed at the last create/update (CR_PROPERTIES_ATTR).
    // Returns null when nothing is stashed or it cannot be parsed.
    private ObjectNode readStashedProperties(StackResource r) {
        String stored = r.getAttributes().get(CR_PROPERTIES_ATTR);
        if (stored == null) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(stored);
            return parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (Exception e) {
            LOG.debugv("Could not parse stored properties for custom resource {0}: {1}",
                    r.getLogicalId(), e.getMessage());
            return null;
        }
    }

    private JsonNode invokeCustomResourceHandler(String serviceToken, String requestType, String logicalId,
                                                 String resourceType, String physicalId,
                                                 ObjectNode resourceProperties, ObjectNode oldResourceProperties,
                                                 String region, String accountId, String stackName) {
        String token = customResourceResponseStore.register();
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("RequestType", requestType);
            event.put("ResponseURL", reachableEndpoint.baseUrl() + "/cfn-response/" + token);
            event.put("StackId", AwsArnUtils.Arn.of("cloudformation", region, accountId, "stack/"
                    + (stackName == null ? "" : stackName) + "/" + UUID.randomUUID()).toString());
            event.put("RequestId", UUID.randomUUID().toString());
            event.put("ResourceType", resourceType);
            event.put("LogicalResourceId", logicalId);
            if (physicalId != null) {
                event.put("PhysicalResourceId", physicalId);
            }
            event.put("ServiceToken", serviceToken);
            event.set("ResourceProperties", resourceProperties);
            if (oldResourceProperties != null) {
                event.set("OldResourceProperties", oldResourceProperties);
            }

            byte[] payload = objectMapper.writeValueAsBytes(event);
            InvokeResult result = lambdaService.invoke(region, serviceToken, payload,
                    InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                String body = result.getPayload() != null
                        ? new String(result.getPayload(), StandardCharsets.UTF_8) : "";
                throw new AwsException("CustomResourceFailed",
                        "Custom resource handler errored (" + result.getFunctionError() + "): " + body, 400);
            }

            return customResourceResponseStore.await(token, CR_RESPONSE_TIMEOUT, serviceToken, region);
        } catch (AwsException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new AwsException("CustomResourceTimeout",
                    "Timed out waiting for custom resource " + logicalId
                            + " to PUT its response to ResponseURL: " + e.getMessage(), 504);
        } catch (Exception e) {
            throw new AwsException("CustomResourceFailed",
                    "Failed to invoke custom resource " + logicalId + ": " + e.getMessage(), 500);
        }
    }

    private static String nodeToAttributeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /**
     * Mirrors CloudFormation's stringification of custom-resource ResourceProperties: every scalar
     * (boolean, number, text) becomes a string, while object and array structure is preserved.
     * Null is left as-is.
     */
    private JsonNode stringifyScalars(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), stringifyScalars(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            node.forEach(e -> out.add(stringifyScalars(e)));
            return out;
        }
        return objectMapper.getNodeFactory().textNode(node.asText());
    }

    private static String accountFromArn(String arn) {
        String account = AwsArnUtils.accountOrDefault(arn, "000000000000");
        return account.matches("\\d{12}") ? account : "000000000000";
    }
}
