package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CloudFormation stack lifecycle management — Create, Update, Delete stacks via ChangeSets.
 */
@ApplicationScoped
public class CloudFormationService {

    private static final Logger LOG = Logger.getLogger(CloudFormationService.class);

    private final ConcurrentHashMap<String, Stack> stacks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final CloudFormationResourceProvisioner provisioner;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    @Inject
    public CloudFormationService(CloudFormationResourceProvisioner provisioner, S3Service s3Service,
                                 ObjectMapper objectMapper, EmulatorConfig config) {
        this.provisioner = provisioner;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ── DescribeStacks ────────────────────────────────────────────────────────

    public List<Stack> describeStacks(String stackName, String region) {
        if (stackName != null && !stackName.isBlank()) {
            // Support lookup by ARN: arn:aws:cloudformation:REGION:ACCOUNT:stack/NAME/UUID
            String resolvedName = stackName;
            if (stackName.startsWith("arn:")) {
                String[] parts = stackName.split("/");
                if (parts.length >= 2) {
                    resolvedName = parts[1];
                }
            }
            Stack stack = stacks.get(key(resolvedName, region));
            if (stack == null) {
                throw new AwsException("ValidationError",
                        "Stack with id " + stackName + " does not exist", 400);
            }
            return List.of(stack);
        }
        return stacks.values().stream()
                .filter(s -> region.equals(s.getRegion()))
                .sorted(Comparator.comparing(Stack::getCreationTime))
                .toList();
    }

    // ── CreateChangeSet ───────────────────────────────────────────────────────

    public ChangeSet createChangeSet(String stackName, String changeSetName, String changeSetType,
                                     String templateBody, String templateUrl,
                                     Map<String, String> parameters, List<String> capabilities,
                                     Map<String, String> tags, String region) {
        String resolvedTemplate = resolveTemplate(templateBody, templateUrl);

        Stack stack = stacks.computeIfAbsent(key(stackName, region), k -> {
            Stack s = newStack(stackName, region);
            if (tags != null) s.getTags().putAll(tags);
            return s;
        });

        ChangeSet cs = new ChangeSet();
        cs.setChangeSetId("arn:aws:cloudformation:" + region + ":" + config.defaultAccountId() +
                ":changeSet/" + changeSetName + "/" + UUID.randomUUID());
        cs.setChangeSetName(changeSetName);
        cs.setStackName(stackName);
        cs.setStackId(stack.getStackId());
        cs.setChangeSetType(changeSetType != null ? changeSetType : "CREATE");
        cs.setTemplateBody(resolvedTemplate);
        cs.setParameters(parameters);
        cs.setCapabilities(capabilities);
        cs.setStatus("CREATE_COMPLETE");
        cs.setExecutionStatus("AVAILABLE");

        stack.getChangeSets().put(changeSetName, cs);
        return cs;
    }

    // ── DescribeChangeSet ─────────────────────────────────────────────────────

    public ChangeSet describeChangeSet(String stackName, String changeSetName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        ChangeSet cs = stack.getChangeSets().get(changeSetName);
        if (cs == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetName + "] does not exist", 400);
        }
        return cs;
    }

    // ── ExecuteChangeSet ──────────────────────────────────────────────────────

    public void executeChangeSet(String stackName, String changeSetName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        ChangeSet cs = stack.getChangeSets().get(changeSetName);
        if (cs == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetName + "] does not exist", 400);
        }

        boolean isCreate = "CREATE".equalsIgnoreCase(cs.getChangeSetType()) ||
                "CREATE_IN_PROGRESS".equals(stack.getStatus());

        stack.setStatus(isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS");
        stack.setLastUpdatedTime(Instant.now());
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS", null);

        String templateBody = cs.getTemplateBody();
        Map<String, String> params = cs.getParameters() != null ? cs.getParameters() : Map.of();

        executor.submit(() -> executeTemplate(stack, templateBody, params, isCreate, region));
    }

    // ── DeleteStack ───────────────────────────────────────────────────────────

    public void deleteStack(String stackName, String region) {
        Stack stack = stacks.get(key(stackName, region));
        if (stack == null) {
            return; // Already gone — no-op
        }
        stack.setStatus("DELETE_IN_PROGRESS");
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", "DELETE_IN_PROGRESS", null);

        executor.submit(() -> deleteStackResources(stack, region));
    }

    // ── GetTemplate ───────────────────────────────────────────────────────────

    public String getTemplate(String stackName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        return stack.getTemplateBody() != null ? stack.getTemplateBody() : "{}";
    }

    // ── DescribeStackEvents ───────────────────────────────────────────────────

    public List<StackEvent> describeStackEvents(String stackName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        List<StackEvent> events = new ArrayList<>(stack.getEvents());
        Collections.reverse(events);
        return events;
    }

    // ── DescribeStackResources ────────────────────────────────────────────────

    public List<StackResource> describeStackResources(String stackName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        return new ArrayList<>(stack.getResources().values());
    }

    // ── ListStacks ────────────────────────────────────────────────────────────

    public List<Stack> listStacks(String region) {
        return stacks.values().stream()
                .filter(s -> region.equals(s.getRegion()))
                .sorted(Comparator.comparing(Stack::getCreationTime))
                .toList();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void executeTemplate(Stack stack, String templateBody, Map<String, String> params,
                                 boolean isCreate, String region) {
        try {
            JsonNode template = parseTemplate(templateBody);
            stack.setTemplateBody(templateBody);

            // Merge default parameter values from the template with caller-supplied params
            Map<String, String> resolvedParams = resolveDefaultParameters(template, params);

            // Resolve conditions first
            Map<String, Boolean> conditions = resolveConditions(template, resolvedParams, stack, region);

            // Process resources in order
            JsonNode resources = template.path("Resources");
            Map<String, String> physicalIds = new LinkedHashMap<>();
            Map<String, Map<String, String>> resourceAttrs = new LinkedHashMap<>();

            // First pass: collect existing physicalIds
            for (var r : stack.getResources().values()) {
                if (r.getPhysicalId() != null) {
                    physicalIds.put(r.getLogicalId(), r.getPhysicalId());
                    resourceAttrs.put(r.getLogicalId(), r.getAttributes());
                }
            }

            if (resources.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = resources.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    String logicalId = entry.getKey();
                    JsonNode resDef = entry.getValue();
                    String type = resDef.path("Type").asText();
                    JsonNode props = resDef.path("Properties");

                    CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                            config.defaultAccountId(), region, stack.getStackName(),
                            stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, objectMapper);

                    StackResource resource = stack.getResources().get(logicalId);
                    if (resource == null) {
                        resource = new StackResource();
                        resource.setLogicalId(logicalId);
                        resource.setResourceType(type);
                        stack.getResources().put(logicalId, resource);
                    }

                    addEvent(stack, logicalId, null, type, "CREATE_IN_PROGRESS", null);
                    resource = provisioner.provision(logicalId, type, props.isMissingNode() ? null : props,
                            engine, region, config.defaultAccountId());
                    stack.getResources().put(logicalId, resource);

                    physicalIds.put(logicalId, resource.getPhysicalId());
                    resourceAttrs.put(logicalId, resource.getAttributes());

                    addEvent(stack, logicalId, resource.getPhysicalId(), type,
                            resource.getStatus(), resource.getStatusReason());
                }
            }

            // Resolve outputs
            stack.getOutputs().clear();
            CloudFormationTemplateEngine finalEngine = new CloudFormationTemplateEngine(
                    config.defaultAccountId(), region, stack.getStackName(),
                    stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, objectMapper);

            JsonNode outputs = template.path("Outputs");
            if (outputs.isObject()) {
                outputs.fields().forEachRemaining(e -> {
                    JsonNode outputDef = e.getValue();
                    String value = finalEngine.resolve(outputDef.path("Value"));
                    stack.getOutputs().put(e.getKey(), value);
                });
            }

            String completeStatus = isCreate ? "CREATE_COMPLETE" : "UPDATE_COMPLETE";
            stack.setStatus(completeStatus);
            stack.setLastUpdatedTime(Instant.now());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", completeStatus, null);
            LOG.infov("Stack {0} execution complete: {1}", stack.getStackName(), completeStatus);

        } catch (Exception e) {
            LOG.errorv("Stack {0} execution failed: {1}", stack.getStackName(), e.getMessage());
            String failStatus = isCreate ? "CREATE_FAILED" : "UPDATE_FAILED";
            stack.setStatus(failStatus);
            stack.setStatusReason(e.getMessage());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", failStatus, e.getMessage());
        }
    }

    private void deleteStackResources(Stack stack, String region) {
        try {
            List<StackResource> resources = new ArrayList<>(stack.getResources().values());
            Collections.reverse(resources); // Delete in reverse order

            for (StackResource resource : resources) {
                if (resource.getPhysicalId() != null && "CREATE_COMPLETE".equals(resource.getStatus())) {
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_IN_PROGRESS", null);
                    provisioner.delete(resource.getResourceType(), resource.getPhysicalId(), region);
                    resource.setStatus("DELETE_COMPLETE");
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_COMPLETE", null);
                }
            }

            stack.setStatus("DELETE_COMPLETE");
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "DELETE_COMPLETE", null);
            stacks.remove(key(stack.getStackName(), region));
            LOG.infov("Stack {0} deleted", stack.getStackName());

        } catch (Exception e) {
            LOG.errorv("Stack {0} delete failed: {1}", stack.getStackName(), e.getMessage());
            stack.setStatus("DELETE_FAILED");
            stack.setStatusReason(e.getMessage());
        }
    }

    private Map<String, String> resolveDefaultParameters(JsonNode template, Map<String, String> callerParams) {
        Map<String, String> resolved = new HashMap<>(callerParams != null ? callerParams : Map.of());
        JsonNode paramDefs = template.path("Parameters");
        if (paramDefs.isObject()) {
            paramDefs.fields().forEachRemaining(e -> {
                String paramName = e.getKey();
                JsonNode paramDef = e.getValue();
                if (!resolved.containsKey(paramName) && paramDef.has("Default")) {
                    resolved.put(paramName, paramDef.path("Default").asText());
                }
            });
        }
        return resolved;
    }

    private Map<String, Boolean> resolveConditions(JsonNode template, Map<String, String> params,
                                                   Stack stack, String region) {
        Map<String, Boolean> conditions = new HashMap<>();
        JsonNode condNode = template.path("Conditions");
        if (!condNode.isObject()) {
            return conditions;
        }
        // Two-pass: collect all names first, then evaluate (handles forward references)
        condNode.fields().forEachRemaining(e -> conditions.put(e.getKey(), false));
        condNode.fields().forEachRemaining(e ->
                conditions.put(e.getKey(), evaluateCondition(e.getValue(), params, conditions)));
        return conditions;
    }

    private boolean evaluateCondition(JsonNode expr, Map<String, String> params,
                                      Map<String, Boolean> conditions) {
        if (expr == null || expr.isNull()) {
            return false;
        }
        if (expr.isBoolean()) {
            return expr.booleanValue();
        }
        if (expr.isObject()) {
            if (expr.has("Condition")) {
                return conditions.getOrDefault(expr.get("Condition").asText(), false);
            }
            if (expr.has("Fn::Equals")) {
                JsonNode args = expr.get("Fn::Equals");
                if (args.isArray() && args.size() == 2) {
                    String left = resolveConditionValue(args.get(0), params);
                    String right = resolveConditionValue(args.get(1), params);
                    return left.equals(right);
                }
            }
            if (expr.has("Fn::Not")) {
                JsonNode args = expr.get("Fn::Not");
                if (args.isArray() && !args.isEmpty()) {
                    return !evaluateCondition(args.get(0), params, conditions);
                }
            }
            if (expr.has("Fn::And")) {
                for (JsonNode item : expr.get("Fn::And")) {
                    if (!evaluateCondition(item, params, conditions)) {
                        return false;
                    }
                }
                return true;
            }
            if (expr.has("Fn::Or")) {
                for (JsonNode item : expr.get("Fn::Or")) {
                    if (evaluateCondition(item, params, conditions)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    private String resolveConditionValue(JsonNode node, Map<String, String> params) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isObject() && node.has("Ref")) {
            String name = node.get("Ref").asText();
            return switch (name) {
                case "AWS::AccountId" -> config.defaultAccountId();
                case "AWS::Region" -> "us-east-1";
                case "AWS::NoValue" -> "";
                default -> params.getOrDefault(name, "");
            };
        }
        return node.asText();
    }

    private JsonNode parseTemplate(String templateBody) throws Exception {
        String trimmed = templateBody != null ? templateBody.trim() : "{}";
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return objectMapper.readTree(trimmed);
        }
        // YAML template — use CF-aware parser to handle !Sub, !Ref, !GetAtt etc.
        return new CloudFormationYamlParser(objectMapper).parse(trimmed);
    }

    private String resolveTemplate(String templateBody, String templateUrl) {
        if (templateBody != null && !templateBody.isBlank()) {
            return templateBody;
        }
        if (templateUrl != null && !templateUrl.isBlank()) {
            return fetchTemplateFromS3(templateUrl);
        }
        return "{}";
    }

    private String fetchTemplateFromS3(String url) {
        try {
            // Parse S3 URL: http://host:port/bucket/key or https://bucket.s3.amazonaws.com/key
            String bucket;
            String key;
            if (url.contains(".s3.amazonaws.com") || url.contains(".s3.")) {
                // Virtual-hosted: https://bucket.s3.region.amazonaws.com/key
                String host = url.replaceFirst("https?://", "").split("/")[0];
                bucket = host.split("\\.")[0];
                key = url.substring(url.indexOf('/', url.indexOf("://") + 3) + 1);
            } else {
                // Path-style: http://host:port/bucket/key
                String path = url.replaceFirst("https?://[^/]+/", "");
                int slash = path.indexOf('/');
                bucket = slash > 0 ? path.substring(0, slash) : path;
                key = slash > 0 ? path.substring(slash + 1) : "";
            }
            var obj = s3Service.getObject(bucket, key);
            return new String(obj.getData());
        } catch (Exception e) {
            LOG.warnv("Failed to fetch template from {0}: {1}", url, e.getMessage());
            return "{}";
        }
    }

    private Stack newStack(String stackName, String region) {
        Stack stack = new Stack();
        stack.setStackName(stackName);
        stack.setRegion(region);
        stack.setStatus("REVIEW_IN_PROGRESS");
        String stackId = "arn:aws:cloudformation:" + region + ":" + config.defaultAccountId() +
                ":stack/" + stackName + "/" + UUID.randomUUID();
        stack.setStackId(stackId);
        stack.setCreationTime(Instant.now());
        return stack;
    }

    private void addEvent(Stack stack, String logicalId, String physicalId,
                          String resourceType, String status, String reason) {
        StackEvent event = new StackEvent();
        event.setStackId(stack.getStackId());
        event.setStackName(stack.getStackName());
        event.setLogicalResourceId(logicalId);
        event.setPhysicalResourceId(physicalId);
        event.setResourceType(resourceType);
        event.setResourceStatus(status);
        event.setResourceStatusReason(reason);
        stack.getEvents().add(event);
    }

    private Stack getStackOrThrow(String stackName, String region) {
        Stack stack = stacks.get(key(stackName, region));
        if (stack == null) {
            throw new AwsException("ValidationError",
                    "Stack with id " + stackName + " does not exist", 400);
        }
        return stack;
    }

    private static String key(String stackName, String region) {
        return region + ":" + stackName;
    }
}
