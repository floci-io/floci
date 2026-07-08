package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CloudFormation stack lifecycle management — Create, Update, Delete stacks via ChangeSets.
 */
@ApplicationScoped
public class CloudFormationService {

    private static final Logger LOG = Logger.getLogger(CloudFormationService.class);

    private final ConcurrentHashMap<String, Stack> stacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeletedStackEntry> deletedStacks = new ConcurrentHashMap<>();
    // Global exports registry: region:exportName -> exportValue
    private final ConcurrentHashMap<String, String> exports = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final CloudFormationResourceProvisioner provisioner;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final SamTransformProcessor samTransformProcessor;
    private final Clock clock;

    // Persisted state so stacks survive a restart (criteria #10, #11). The in-memory maps above are
    // the live working copy; these backends are write-through + loaded on startup. CloudFormation is
    // account-blind (keyed by stack+region), so everything is stored under one fixed account
    // namespace for thread-consistent access from both request and background executor threads.
    private final AccountAwareStorageBackend<Stack> stackBackend;
    private final AccountAwareStorageBackend<String> exportBackend;
    private final String storageAccount;

    @Inject
    public CloudFormationService(CloudFormationResourceProvisioner provisioner, S3Service s3Service,
                                 ObjectMapper objectMapper, EmulatorConfig config,
                                 RegionResolver regionResolver, Clock clock,
                                 StorageFactory storageFactory) {
        this.provisioner = provisioner;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
        this.config = config;
        this.regionResolver = regionResolver;
        this.samTransformProcessor = new SamTransformProcessor(objectMapper);
        this.clock = clock;
        this.storageAccount = config.defaultAccountId();
        this.stackBackend = asAccountAware(storageFactory.create(
                "cloudformation", "cloudformation-stacks.json", new TypeReference<Map<String, Stack>>() {}));
        this.exportBackend = asAccountAware(storageFactory.create(
                "cloudformation", "cloudformation-exports.json", new TypeReference<Map<String, String>>() {}));
    }

    private static <V> AccountAwareStorageBackend<V> asAccountAware(StorageBackend<String, V> backend) {
        return (AccountAwareStorageBackend<V>) backend;
    }

    @PostConstruct
    void loadPersistedState() {
        for (Stack stack : stackBackend.scanForAccount(storageAccount, k -> true)) {
            stacks.put(key(stack.getStackName(), stack.getRegion()), stack);
        }
        for (String exportKey : exportBackend.keysForAccount(storageAccount)) {
            exportBackend.getForAccount(storageAccount, exportKey)
                    .ifPresent(value -> exports.put(exportKey, value));
        }
        if (!stacks.isEmpty() || !exports.isEmpty()) {
            LOG.infov("Loaded {0} CloudFormation stack(s) and {1} export(s) from storage",
                    stacks.size(), exports.size());
        }
    }

    private void persistStack(Stack stack) {
        stackBackend.putForAccount(storageAccount, key(stack.getStackName(), stack.getRegion()), stack);
    }

    private void unpersistStack(String stackName, String region) {
        stackBackend.deleteForAccount(storageAccount, key(stackName, region));
    }

    /**
     * Sets a stack's termination protection (CloudFormation {@code UpdateTerminationProtection}).
     * Returns the stack so the caller can echo its {@code StackId}.
     */
    public Stack updateTerminationProtection(String stackName, boolean enabled, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        stack.setEnableTerminationProtection(enabled);
        persistStack(stack);
        return stack;
    }

    // ── DescribeStacks ────────────────────────────────────────────────────────

    public List<Stack> describeStacks(String stackName, String region) {
        if (stackName != null && !stackName.isBlank()) {
            Stack stack = resolveStackForDescribe(stackName, region);
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

        // Detect first creation atomically: the mapping function runs at most once per key, so the
        // flag is only set for the thread that actually creates the stack (no double-recording under
        // concurrent CreateChangeSet calls).
        boolean[] stackCreated = {false};
        Stack stack = stacks.computeIfAbsent(key(stackName, region), k -> {
            stackCreated[0] = true;
            Stack s = newStack(stackName, region);
            if (tags != null) s.getTags().putAll(tags);
            return s;
        });

        // A CREATE change set puts a brand-new stack into REVIEW_IN_PROGRESS. Record the matching
        // stack-level event (as AWS and LocalStack do) so DescribeStackEvents is non-empty straight
        // after change-set creation — tooling such as the AWS SAM CLI reads StackEvents[0] there and
        // otherwise fails with an IndexError. (CreateChangeSet defaults a null type to CREATE.)
        boolean isCreateType = changeSetType == null || "CREATE".equalsIgnoreCase(changeSetType);
        if (stackCreated[0] && isCreateType) {
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "REVIEW_IN_PROGRESS", "User Initiated");
        }

        ChangeSet cs = new ChangeSet();
        cs.setChangeSetId(AwsArnUtils.Arn.of("cloudformation", region, regionResolver.getAccountId(), "changeSet/" + changeSetName + "/" + UUID.randomUUID()).toString());
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
        persistStack(stack);
        return cs;
    }

    // ── DescribeChangeSet ─────────────────────────────────────────────────────

    public ChangeSet describeChangeSet(String stackName, String changeSetName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        ChangeSet cs = stack.getChangeSets().get(resolveChangeSetName(changeSetName));
        if (cs == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetName + "] does not exist", 400);
        }
        return cs;
    }

    // ── ExecuteChangeSet ──────────────────────────────────────────────────────

    public Future<?> executeChangeSet(String stackName, String changeSetName, String region) {
        return executeChangeSet(stackName, changeSetName, region, regionResolver.getAccountId());
    }

    /**
     * Executes a change set, provisioning its resources into {@code accountId}'s namespace.
     *
     * <p>Provisioning runs on a background executor thread that has no inherited request scope, so
     * the downstream service calls would otherwise fall back to the default account. The resources
     * are materialized under a synthetic request scope bound to {@code accountId} so a single-stack
     * deployment lands in the caller's account, and a StackSet instance lands in its target account.
     */
    public Future<?> executeChangeSet(String stackName, String changeSetName, String region, String accountId) {
        Stack stack = getStackOrThrow(stackName, region);
        ChangeSet cs = stack.getChangeSets().get(resolveChangeSetName(changeSetName));
        if (cs == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetName + "] does not exist", 400);
        }

        boolean isCreate = "CREATE".equalsIgnoreCase(cs.getChangeSetType()) ||
                "CREATE_IN_PROGRESS".equals(stack.getStatus());

        stack.setStatus(isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS");
        stack.setLastUpdatedTime(now());
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS", null);
        persistStack(stack);

        String templateBody = cs.getTemplateBody();
        Map<String, String> params = cs.getParameters() != null ? cs.getParameters() : Map.of();

        return executor.submit(() -> runUnderAccount(accountId,
                () -> executeTemplate(stack, templateBody, params, isCreate, region, accountId)));
    }

    /**
     * Runs {@code body} under a synthetic CDI request scope whose account is {@code accountId}, so
     * that account-aware storage in the downstream services namespaces provisioned resources under
     * the intended account. Mirrors the pattern used by other background workers.
     */
    private void runUnderAccount(String accountId, Runnable body) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        // Background workers normally have no active scope, so a fresh one is activated and
        // terminated below. But if we ran inside an already-active scope, restore its previous
        // account afterwards so we never leave the overridden account ID behind on a reused thread.
        RequestContext ctx = Arc.container().instance(RequestContext.class).get();
        String previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        try {
            if (accountId != null) {
                ctx.setAccountId(accountId);
            }
            body.run();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
            }
        }
    }

    // ── DeleteChangeSet ───────────────────────────────────────────────────────

    public void deleteChangeSet(String stackName, String changeSetName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        String name = resolveChangeSetName(changeSetName);
        ChangeSet cs = stack.getChangeSets().get(name);
        if (cs == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetName + "] does not exist", 400);
        }
        stack.getChangeSets().remove(name);
        persistStack(stack);
    }

    // ── DeleteStack ───────────────────────────────────────────────────────────

    public void deleteStack(String stackName, String region) {
        deleteStack(stackName, region, regionResolver.getAccountId());
    }

    /**
     * Deletes a stack, removing its resources from {@code accountId}'s namespace. The account must
     * match the one the resources were provisioned into (the caller's account for a single-stack
     * deployment, or the target account for a StackSet instance).
     *
     * @return a future that completes when the resources have been removed; already-gone stacks
     *         complete immediately. Callers that need synchronous deletion (e.g. StackSet instance
     *         removal) can await it.
     */
    public Future<?> deleteStack(String stackName, String region, String accountId) {
        purgeExpiredDeletedStacks();
        Stack stack = resolveStack(stackName, region);
        if (stack == null) {
            return CompletableFuture.completedFuture(null); // Already gone — no-op
        }
        if (stack.isEnableTerminationProtection()) {
            // Real AWS rejects deletion of a protected stack and leaves it unchanged.
            throw new AwsException("ValidationError",
                    "Stack [" + stack.getStackId()
                            + "] cannot be deleted while TerminationProtection is enabled", 400);
        }
        stack.setStatus("DELETE_IN_PROGRESS");
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", "DELETE_IN_PROGRESS", null);

        return executor.submit(() -> runUnderAccount(accountId, () -> deleteStackResources(stack, region)));
    }

    // ── GetTemplate ───────────────────────────────────────────────────────────

    public String getTemplate(String stackName, String region) {
        Stack stack = getStackOrThrow(stackName, region);
        return stack.getTemplateBody() != null ? stack.getTemplateBody() : "{}";
    }

    // ── DescribeStackEvents ───────────────────────────────────────────────────

    public List<StackEvent> describeStackEvents(String stackName, String region) {
        Stack stack = resolveStackForDescribe(stackName, region);
        if (stack == null) {
            throw new AwsException("ValidationError",
                    "Stack with id " + stackName + " does not exist", 400);
        }
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

    // ── ListExports ─────────────────────────────────────────────────────────

    public Map<String, ExportEntry> listExports(String region) {
        Map<String, ExportEntry> result = new LinkedHashMap<>();
        for (Stack stack : stacks.values()) {
            if (!region.equals(stack.getRegion())) {
                continue;
            }
            for (var entry : stack.getExports().entrySet()) {
                result.put(entry.getKey(), new ExportEntry(entry.getKey(), entry.getValue(), stack.getStackId()));
            }
        }
        return result;
    }

    public record ExportEntry(String name, String value, String exportingStackId) {}

    // ── Private ───────────────────────────────────────────────────────────────

    private void removeStackExports(Stack stack, String region) {
        for (String exportName : stack.getExports().keySet()) {
            String exportKey = exportKey(region, exportName);
            exports.remove(exportKey);
            exportBackend.deleteForAccount(storageAccount, exportKey);
        }
    }

    private String exportKey(String region, String exportName) {
        return region + ":" + exportName;
    }

    private void validateExportNameAvailable(String region, String exportName,
                                             Map<String, String> oldExports,
                                             Map<String, String> newExports) {
        if (newExports.containsKey(exportName)) {
            throw new AwsException("ValidationError",
                    "Export with name " + exportName + " is already defined by this stack", 400);
        }
        if (!oldExports.containsKey(exportName) && exports.containsKey(exportKey(region, exportName))) {
            throw new AwsException("ValidationError",
                    "Export with name " + exportName + " is already exported by another stack", 400);
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

    private void executeTemplate(Stack stack, String templateBody, Map<String, String> params,
                                 boolean isCreate, String region, String accountId) {
        try {
            JsonNode template = parseTemplate(templateBody);

            // Apply SAM transform if the template declares AWS::Serverless-2016-10-31
            if (samTransformProcessor.hasSamTransform(template)) {
                LOG.infov("Applying SAM transform for stack {0}", stack.getStackName());
                template = samTransformProcessor.expandSamTemplate(template);
                // Store the expanded template so GetTemplate returns the transformed version
                templateBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(template);
            }

            stack.setTemplateBody(templateBody);

            // Merge default parameter values from the template with caller-supplied params
            Map<String, String> resolvedParams = resolveDefaultParameters(template, params);

            // Resolve conditions first
            Map<String, Boolean> conditions = resolveConditions(template, resolvedParams, stack, region, accountId);

            // Mappings
            Map<String, JsonNode> mappings = new HashMap<>();
            template.path("Mappings").fields().forEachRemaining(e -> mappings.put(e.getKey(), e.getValue()));

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

            StackResource failedResource = null;
            if (resources.isObject()) {
                List<String> sortedLogicalIds = topologicalSort(resources, conditions);

                for (String logicalId : sortedLogicalIds) {
                    JsonNode resDef = resources.get(logicalId);
                    String type = resDef.path("Type").asText();
                    JsonNode props = resDef.path("Properties");

                    CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                            accountId, region, stack.getStackName(),
                            stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, mappings, objectMapper,
                            name -> exports.get(exportKey(region, name)));

                    StackResource resource = stack.getResources().get(logicalId);
                    if (resource == null) {
                        resource = new StackResource();
                        resource.setLogicalId(logicalId);
                        resource.setResourceType(type);
                        stack.getResources().put(logicalId, resource);
                    }

                    addEvent(stack, logicalId, null, type, "CREATE_IN_PROGRESS", null);
                    if ("AWS::CloudFormation::Stack".equals(type)) {
                        resource = executeNestedStack(stack, logicalId,
                                props.isMissingNode() ? null : props,
                                engine, region, accountId, isCreate);
                    } else {
                        resource = provisioner.provision(logicalId, type, props.isMissingNode() ? null : props,
                                engine, region, accountId, stack.getStackName(),
                                resource.getPhysicalId(), resource.getAttributes());
                    }
                    stack.getResources().put(logicalId, resource);

                    physicalIds.put(logicalId, resource.getPhysicalId());
                    resourceAttrs.put(logicalId, resource.getAttributes());

                    addEvent(stack, logicalId, resource.getPhysicalId(), type,
                            resource.getStatus(), resource.getStatusReason());

                    if ("CREATE_FAILED".equals(resource.getStatus())
                            || "UPDATE_FAILED".equals(resource.getStatus())) {
                        failedResource = resource;
                        break;
                    }
                }
            }

            // A resource failed to provision: stop, and (on create) roll back what we built so a
            // corrected re-deploy starts from a clean slate (acceptance criterion #9).
            if (failedResource != null) {
                rollbackFailedExecution(stack, region, isCreate, failedResource);
                return;
            }

            CloudFormationTemplateEngine finalEngine = new CloudFormationTemplateEngine(
                    accountId, region, stack.getStackName(),
                    stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, mappings, objectMapper,
                    name -> exports.get(exportKey(region, name)));

            // Resolve outputs before mutating stack/global export state, so failed updates do not
            // leave stale or partially registered exports behind.
            Map<String, String> oldExports = new LinkedHashMap<>(stack.getExports());
            Map<String, String> newOutputs = new LinkedHashMap<>();
            Map<String, String> newExports = new LinkedHashMap<>();
            Map<String, String> newOutputExportNames = new LinkedHashMap<>();
            JsonNode outputs = template.path("Outputs");
            if (outputs.isObject()) {
                outputs.fields().forEachRemaining(e -> {
                    JsonNode outputDef = e.getValue();
                    String value = finalEngine.resolve(outputDef.path("Value"));
                    newOutputs.put(e.getKey(), value);

                    // Register exports
                    JsonNode exportNode = outputDef.path("Export").path("Name");
                    if (!exportNode.isMissingNode()) {
                        String exportName = finalEngine.resolve(exportNode);
                        validateExportNameAvailable(region, exportName, oldExports, newExports);
                        newExports.put(exportName, value);
                        newOutputExportNames.put(e.getKey(), exportName);
                    }
                });
            }

            removeStackExports(stack, region);
            stack.getOutputs().clear();
            stack.getOutputs().putAll(newOutputs);
            stack.getExports().clear();
            stack.getExports().putAll(newExports);
            stack.getOutputExportNames().clear();
            stack.getOutputExportNames().putAll(newOutputExportNames);
            newExports.forEach((exportName, value) -> {
                String exportKey = exportKey(region, exportName);
                exports.put(exportKey, value);
                exportBackend.putForAccount(storageAccount, exportKey, value);
                LOG.infov("Registered export {0} = {1} from stack {2}",
                        exportName, value, stack.getStackName());
            });

            String completeStatus = isCreate ? "CREATE_COMPLETE" : "UPDATE_COMPLETE";
            stack.setStatus(completeStatus);
            stack.setLastUpdatedTime(now());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", completeStatus, null);
            persistStack(stack);
            LOG.infov("Stack {0} execution complete: {1}", stack.getStackName(), completeStatus);

        } catch (Exception e) {
            LOG.errorv("Stack {0} execution failed: {1}", stack.getStackName(), e.getMessage());
            String failStatus = isCreate ? "CREATE_FAILED" : "UPDATE_FAILED";
            stack.setStatus(failStatus);
            stack.setStatusReason(e.getMessage());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", failStatus, e.getMessage());
            persistStack(stack);
        }
    }

    /**
     * Handles a resource that failed to provision.
     *
     * <p>On a <b>create</b>, rolls back by deleting the resources that were successfully created in
     * this execution, leaving a clean slate ({@code ROLLBACK_COMPLETE}) so a corrected re-deploy can
     * start from scratch. On an <b>update</b>, marks {@code UPDATE_ROLLBACK_COMPLETE} and keeps the
     * prior physical IDs so a corrected re-deploy proceeds (full prior-template restoration is out
     * of scope — see plan Part 7).
     */
    private void rollbackFailedExecution(Stack stack, String region, boolean isCreate,
                                         StackResource failedResource) {
        String failStatus = isCreate ? "CREATE_FAILED" : "UPDATE_FAILED";
        stack.setStatus(failStatus);
        stack.setStatusReason(failedResource.getStatusReason());
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", failStatus, failedResource.getStatusReason());
        LOG.warnv("Stack {0} resource {1} failed: {2}", stack.getStackName(),
                failedResource.getLogicalId(), failedResource.getStatusReason());

        if (isCreate) {
            stack.setStatus("ROLLBACK_IN_PROGRESS");
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "ROLLBACK_IN_PROGRESS", failedResource.getStatusReason());
            rollbackCreatedResources(stack, region);
            stack.setStatus("ROLLBACK_COMPLETE");
            stack.setLastUpdatedTime(now());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "ROLLBACK_COMPLETE", null);
            LOG.infov("Stack {0} rolled back to a clean slate (ROLLBACK_COMPLETE)", stack.getStackName());
        } else {
            stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
            stack.setLastUpdatedTime(now());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_COMPLETE", null);
            LOG.infov("Stack {0} update rolled back (UPDATE_ROLLBACK_COMPLETE)", stack.getStackName());
        }
        persistStack(stack);
    }

    /** Deletes every resource successfully created in this execution, in reverse order. */
    private void rollbackCreatedResources(Stack stack, String region) {
        List<StackResource> resources = new ArrayList<>(stack.getResources().values());
        Collections.reverse(resources);
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
    }

    private void deleteStackResources(Stack stack, String region) {
        try {
            List<StackResource> resources = new ArrayList<>(stack.getResources().values());
            Collections.reverse(resources); // Delete in reverse order

            List<String> failedResources = new ArrayList<>();
            for (StackResource resource : resources) {
                // CREATE_COMPLETE: first delete attempt. DELETE_FAILED: a previous delete left the
                // resource behind (e.g. the bucket was non-empty); AWS re-attempts it on retry.
                boolean deletable = "CREATE_COMPLETE".equals(resource.getStatus())
                        || "DELETE_FAILED".equals(resource.getStatus());
                if (resource.getPhysicalId() == null || !deletable) {
                    continue;
                }
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_IN_PROGRESS", null);
                try {
                    provisioner.delete(resource, region);
                    resource.setStatus("DELETE_COMPLETE");
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_COMPLETE", null);
                } catch (Exception e) {
                    // AWS leaves the stack in DELETE_FAILED when a managed resource cannot be
                    // deleted (e.g. a non-empty S3 bucket raises BucketNotEmpty). The stack must
                    // not be reported as a successful deletion while the resource still exists.
                    // Remaining resources are still attempted, matching AWS.
                    failedResources.add(resource.getLogicalId());
                    resource.setStatus("DELETE_FAILED");
                    resource.setStatusReason(e.getMessage());
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_FAILED", e.getMessage());
                    LOG.warnv("Failed to delete {0} ({1}) in stack {2}: {3}",
                            resource.getResourceType(), resource.getPhysicalId(),
                            stack.getStackName(), e.getMessage());
                }
            }

            if (!failedResources.isEmpty()) {
                String reason = "The following resource(s) failed to delete: ["
                        + String.join(", ", failedResources) + "].";
                stack.setStatus("DELETE_FAILED");
                stack.setStatusReason(reason);
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "DELETE_FAILED", reason);
                LOG.errorv("Stack {0} delete failed: {1}", stack.getStackName(), reason);
                return;
            }

            stack.setStatus("DELETE_COMPLETE");
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "DELETE_COMPLETE", null);
            removeStackExports(stack, region);
            stacks.remove(key(stack.getStackName(), region));
            unpersistStack(stack.getStackName(), region);
            deletedStacks.put(stack.getStackId(), new DeletedStackEntry(
                    stack,
                    now().plusSeconds(config.services().cloudformation().deletedStackRetentionSeconds())));
            LOG.infov("Stack {0} deleted", stack.getStackName());

        } catch (Exception e) {
            LOG.errorv("Stack {0} delete failed: {1}", stack.getStackName(), e.getMessage());
            stack.setStatus("DELETE_FAILED");
            stack.setStatusReason(e.getMessage());
        }
    }

    private Map<String, Boolean> resolveConditions(JsonNode template, Map<String, String> params,
                                                   Stack stack, String region, String accountId) {
        Map<String, Boolean> conditions = new HashMap<>();
        JsonNode condNode = template.path("Conditions");
        if (!condNode.isObject()) {
            return conditions;
        }
        // Two-pass: collect all names first, then evaluate (handles forward references)
        condNode.fields().forEachRemaining(e -> conditions.put(e.getKey(), false));
        condNode.fields().forEachRemaining(e ->
                conditions.put(e.getKey(), evaluateCondition(e.getValue(), params, conditions, region, accountId)));
        return conditions;
    }

    private boolean evaluateCondition(JsonNode expr, Map<String, String> params,
                                      Map<String, Boolean> conditions, String region, String accountId) {
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
                    String left = resolveConditionValue(args.get(0), params, region, accountId);
                    String right = resolveConditionValue(args.get(1), params, region, accountId);
                    return left.equals(right);
                }
            }
            if (expr.has("Fn::Not")) {
                JsonNode args = expr.get("Fn::Not");
                if (args.isArray() && !args.isEmpty()) {
                    return !evaluateCondition(args.get(0), params, conditions, region, accountId);
                }
            }
            if (expr.has("Fn::And")) {
                for (JsonNode item : expr.get("Fn::And")) {
                    if (!evaluateCondition(item, params, conditions, region, accountId)) {
                        return false;
                    }
                }
                return true;
            }
            if (expr.has("Fn::Or")) {
                for (JsonNode item : expr.get("Fn::Or")) {
                    if (evaluateCondition(item, params, conditions, region, accountId)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    private String resolveConditionValue(JsonNode node, Map<String, String> params,
                                         String region, String accountId) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isObject() && node.has("Ref")) {
            String name = node.get("Ref").asText();
            return switch (name) {
                case "AWS::AccountId" -> accountId;
                case "AWS::Region" -> region;
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

    /**
     * Resolves a template body from an inline body or a TemplateURL (fetched from S3), for callers
     * outside this service such as the StackSets handler. Returns {@code null} when neither is given.
     */
    public String resolveTemplateBody(String templateBody, String templateUrl) {
        if ((templateBody == null || templateBody.isBlank())
                && (templateUrl == null || templateUrl.isBlank())) {
            return null;
        }
        return resolveTemplate(templateBody, templateUrl);
    }

    private String fetchTemplateFromS3(String url) {
        // Parse S3 URL — three forms:
        //   Virtual-hosted AWS:   https://bucket.s3[.region].amazonaws.com/key
        //   Virtual-hosted local: http://bucket.localhost:4566/key  (or configured/default hostname)
        //   Path-style (both):    https://s3[.region].amazonaws.com/bucket/key
        //                         http://host:port/bucket/key
        //
        // The old condition matched host.endsWith(".amazonaws.com") for virtual-hosted, which
        // incorrectly caught path-style AWS URLs like s3.us-east-1.amazonaws.com and extracted
        // "s3" as the bucket name. Virtual-hosted URLs always have a bucket label before ".s3.".
        String bucket;
        String key;

        URI uri = URI.create(url);
        String host = uri.getHost();
        String path = uri.getRawPath();

        boolean isVirtualHosted = host != null && (
                host.contains(".s3.")
                || isConfiguredVirtualHostedS3Host(host)
                || host.endsWith(".localhost"));

        if (isVirtualHosted) {
            bucket = host.split("\\.")[0];
            key = path.startsWith("/") ? path.substring(1) : path;
        } else {
            // Path-style: /bucket/key
            String rawPath = path.startsWith("/") ? path.substring(1) : path;
            int slash = rawPath.indexOf('/');
            bucket = slash > 0 ? rawPath.substring(0, slash) : rawPath;
            key = slash > 0 ? rawPath.substring(slash + 1) : "";
        }

        try {
            var obj = s3Service.getObject(bucket, key);
            return new String(obj.getData());
        } catch (Exception e) {
            LOG.errorv("Failed to fetch CloudFormation template from {0}: {1}", url, e.getMessage());
            throw new RuntimeException("Failed to fetch CloudFormation template from " + url + ": " + e.getMessage(), e);
        }
    }

    private boolean isConfiguredVirtualHostedS3Host(String host) {
        String suffix = config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
        return hasBucketPrefixForSuffix(host, suffix);
    }

    private static boolean hasBucketPrefixForSuffix(String host, String suffix) {
        if (host == null || suffix == null || suffix.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        return normalizedHost.length() > normalizedSuffix.length() + 1
                && normalizedHost.endsWith("." + normalizedSuffix);
    }

    private StackResource executeNestedStack(Stack parentStack, String logicalId, JsonNode props,
                                             CloudFormationTemplateEngine engine, String region,
                                             String accountId, boolean isCreate) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType("AWS::CloudFormation::Stack");

        String templateUrl = props != null ? engine.resolve(props.path("TemplateURL")) : null;
        if (templateUrl == null || templateUrl.isBlank()) {
            resource.setStatus("CREATE_FAILED");
            resource.setStatusReason("Missing TemplateURL");
            return resource;
        }

        String childTemplate = fetchTemplateFromS3(templateUrl);
        String childStackName = parentStack.getStackName() + "-" + logicalId;

        Stack childStack = newStack(childStackName, region);
        childStack.setStatus("CREATE_IN_PROGRESS");
        stacks.put(key(childStackName, region), childStack);

        Map<String, String> childParams = new LinkedHashMap<>();
        if (props != null && props.has("Parameters") && props.get("Parameters").isObject()) {
            props.get("Parameters").fields().forEachRemaining(e ->
                    childParams.put(e.getKey(), engine.resolve(e.getValue())));
        }

        executeTemplate(childStack, childTemplate, childParams, isCreate, region, accountId);

        resource.setPhysicalId(childStack.getStackId());
        resource.getAttributes().put("Arn", childStack.getStackId());
        childStack.getOutputs().forEach((k, v) -> resource.getAttributes().put("Outputs." + k, v));

        if ("CREATE_FAILED".equals(childStack.getStatus()) || "UPDATE_FAILED".equals(childStack.getStatus())) {
            resource.setStatus("CREATE_FAILED");
            resource.setStatusReason("Nested stack " + childStackName + " failed: " + childStack.getStatusReason());
        } else {
            resource.setStatus("CREATE_COMPLETE");
        }

        return resource;
    }

    private Stack newStack(String stackName, String region) {
        Stack stack = new Stack();
        stack.setStackName(stackName);
        stack.setRegion(region);
        stack.setStatus("REVIEW_IN_PROGRESS");
        String stackId = AwsArnUtils.Arn.of("cloudformation", region, regionResolver.getAccountId(), "stack/" + stackName + "/" + UUID.randomUUID()).toString();
        stack.setStackId(stackId);
        stack.setCreationTime(now());
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

    private Stack getStackOrThrow(String stackNameOrArn, String region) {
        Stack stack = resolveStack(stackNameOrArn, region);
        if (stack == null) {
            throw new AwsException("ValidationError",
                    "Stack with id " + stackNameOrArn + " does not exist", 400);
        }
        return stack;
    }

    private Stack resolveStackForDescribe(String stackNameOrArn, String region) {
        Stack stack = resolveStack(stackNameOrArn, region);
        if (stack != null) {
            return stack;
        }
        if (stackNameOrArn != null && stackNameOrArn.startsWith("arn:")) {
            DeletedStackEntry deleted = deletedStacks.get(stackNameOrArn);
            if (deleted != null) {
                if (deleted.isExpired(now())) {
                    deletedStacks.remove(stackNameOrArn, deleted);
                    return null;
                }
                if (region.equals(deleted.stack().getRegion())) {
                    return deleted.stack();
                }
            }
        }
        return null;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private void purgeExpiredDeletedStacks() {
        Instant current = now();
        deletedStacks.entrySet().removeIf(entry -> entry.getValue().isExpired(current));
    }

    private record DeletedStackEntry(Stack stack, Instant expiresAt) {
        private boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * Resolves a changeset name from either a short name or a full ARN.
     * The AWS CLI passes the full ARN (arn:aws:cloudformation:…:changeSet/<name>/<uuid>)
     * when referencing a changeset by the ID returned from CreateChangeSet.
     */
    private String resolveChangeSetName(String changeSetNameOrArn) {
        if (changeSetNameOrArn != null && changeSetNameOrArn.startsWith("arn:")) {
            // arn:aws:cloudformation:<region>:<account>:changeSet/<name>/<uuid>
            try {
                String resource = AwsArnUtils.parse(changeSetNameOrArn).resource();
                String[] parts = resource.split("/");
                if (parts.length >= 2) {
                    return parts[1];
                }
            } catch (IllegalArgumentException e) {
                // fall through to return as-is
            }
        }
        return changeSetNameOrArn;
    }

    /**
     * Resolves a stack by name or ARN. When an ARN is provided the stack name
     * is extracted from the ARN path segment ({@code …:stack/<name>/<id>}).
     * Falls back to a linear scan matching on stackId for robustness.
     */
    private Stack resolveStack(String stackNameOrArn, String region) {
        // Try direct name lookup first (fast path)
        Stack stack = stacks.get(key(stackNameOrArn, region));
        if (stack != null) {
            return stack;
        }

        // If input looks like an ARN, extract the stack name and retry
        if (stackNameOrArn != null && stackNameOrArn.startsWith("arn:")) {
            String extractedName = extractStackNameFromArn(stackNameOrArn);
            if (extractedName != null) {
                stack = stacks.get(key(extractedName, region));
                if (stack != null) {
                    return stack;
                }
            }
            // Fallback: scan by stackId in case the ARN format is unexpected
            for (Stack s : stacks.values()) {
                if (stackNameOrArn.equals(s.getStackId())) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the stack name from a CloudFormation stack ARN.
     * Expected format: {@code arn:aws:cloudformation:REGION:ACCOUNT:stack/STACK_NAME/UUID}
     */
    private static String extractStackNameFromArn(String arn) {
        try {
            // resource is "stack/<name>/<uuid>"; split on "/" to get the name
            String resource = AwsArnUtils.parse(arn).resource();
            if (!resource.startsWith("stack/")) {
                return null;
            }
            String afterStack = resource.substring("stack/".length());
            int slash = afterStack.indexOf('/');
            return slash > 0 ? afterStack.substring(0, slash) : afterStack;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> topologicalSort(JsonNode resources, Map<String, Boolean> conditions) {
        Set<String> allIds = new LinkedHashSet<>();
        resources.fieldNames().forEachRemaining(allIds::add);

        Map<String, Set<String>> dependencies = new HashMap<>();
        for (String logicalId : allIds) {
            JsonNode resDef = resources.get(logicalId);

            String condition = resDef.path("Condition").asText(null);
            if (condition != null && !conditions.getOrDefault(condition, false)) {
                continue;
            }

            Set<String> deps = new LinkedHashSet<>();
            collectDependencies(resDef.path("Properties"), allIds, deps);

            JsonNode dependsOn = resDef.path("DependsOn");
            if (dependsOn.isTextual()) {
                deps.add(dependsOn.asText());
            } else if (dependsOn.isArray()) {
                for (JsonNode d : dependsOn) {
                    deps.add(d.asText());
                }
            }

            dependencies.put(logicalId, deps);
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : allIds) {
            inDegree.put(id, 0);
        }
        for (var entry : dependencies.entrySet()) {
            for (String dep : entry.getValue()) {
                if (inDegree.containsKey(dep)) {
                    inDegree.put(entry.getKey(), inDegree.get(entry.getKey()) + 1);
                }
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (String id : allIds) {
            if (inDegree.get(id) == 0) {
                queue.add(id);
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (var entry : dependencies.entrySet()) {
                if (entry.getValue().contains(current)) {
                    int newDegree = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDegree);
                    if (newDegree == 0) {
                        queue.add(entry.getKey());
                    }
                }
            }
        }

        for (String id : allIds) {
            if (!sorted.contains(id)) {
                sorted.add(id);
            }
        }

        return sorted;
    }

    private static final Pattern SUB_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private void collectDependencies(JsonNode node, Set<String> allIds, Set<String> deps) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            if (node.has("Ref")) {
                String ref = node.get("Ref").asText();
                if (allIds.contains(ref)) {
                    deps.add(ref);
                }
                return;
            }
            if (node.has("Fn::GetAtt")) {
                JsonNode getAtt = node.get("Fn::GetAtt");
                String logicalId;
                if (getAtt.isArray() && getAtt.size() >= 1) {
                    logicalId = getAtt.get(0).asText();
                } else {
                    logicalId = getAtt.asText().split("\\.", 2)[0];
                }
                if (allIds.contains(logicalId)) {
                    deps.add(logicalId);
                }
                return;
            }
            if (node.has("Fn::Sub")) {
                collectSubDependencies(node.get("Fn::Sub"), allIds, deps);
                return;
            }
            node.fields().forEachRemaining(e -> collectDependencies(e.getValue(), allIds, deps));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectDependencies(item, allIds, deps);
            }
        }
    }

    private void collectSubDependencies(JsonNode sub, Set<String> allIds, Set<String> deps) {
        String template;
        Set<String> explicitVars = new HashSet<>();

        if (sub.isTextual()) {
            template = sub.textValue();
        } else if (sub.isArray() && sub.size() >= 1) {
            template = sub.get(0).asText();
            if (sub.size() >= 2 && sub.get(1).isObject()) {
                sub.get(1).fieldNames().forEachRemaining(explicitVars::add);
                collectDependencies(sub.get(1), allIds, deps);
            }
        } else {
            return;
        }

        Matcher matcher = SUB_VAR_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (varName.startsWith("AWS::") || explicitVars.contains(varName)) {
                continue;
            }
            int dot = varName.indexOf('.');
            String resourcePart = dot > 0 ? varName.substring(0, dot) : varName;
            if (allIds.contains(resourcePart)) {
                deps.add(resourcePart);
            }
        }
    }

    private static String key(String stackName, String region) {
        return region + ":" + stackName;
    }
}
