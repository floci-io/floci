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
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.TemplateSummary;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutionException;
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
        this.stackBackend = storageFactory.create(
                "cloudformation", "cloudformation-stacks.json", new TypeReference<Map<String, Stack>>() {});
        this.exportBackend = storageFactory.create(
                "cloudformation", "cloudformation-exports.json", new TypeReference<Map<String, String>>() {});
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

    @PreDestroy
    void stop() {
        executor.shutdownNow();
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
        return createChangeSet(stackName, changeSetName, changeSetType, templateBody, templateUrl,
                parameters, capabilities, tags, region, regionResolver.getAccountId(), false);
    }

    /**
     * Entry point for the {@code CreateChangeSet} operation itself, as opposed to the change sets
     * {@code CreateStack}/{@code UpdateStack}/StackSet deployment create internally and execute
     * immediately.
     *
     * <p>The difference matters for exactly one case: a CREATE change set is allowed to attach to a
     * stack already sitting in {@code REVIEW_IN_PROGRESS}, because that status means "a CREATE
     * change set was created here and nobody has executed it yet" - the stack is a placeholder, not
     * a deployment. {@code aws cloudformation deploy} and SAM rely on this: {@code has_stack} in the
     * AWS CLI's deployer treats a {@code REVIEW_IN_PROGRESS} stack as nonexistent and sends a second
     * CREATE change set against it, which real CloudFormation accepts. Routing that exemption
     * through a separate entry point rather than the shared one keeps it off the implicit callers,
     * where a stack is only ever momentarily {@code REVIEW_IN_PROGRESS} - between {@link #newStack}
     * and the execute that immediately follows - and treating that window as reusable would let two
     * racing {@code CreateStack} requests both provision the same template.
     */
    public ChangeSet createChangeSetForRequest(String stackName, String changeSetName, String changeSetType,
                                               String templateBody, String templateUrl,
                                               Map<String, String> parameters, List<String> capabilities,
                                               Map<String, String> tags, String region) {
        return createChangeSet(stackName, changeSetName, changeSetType, templateBody, templateUrl,
                parameters, capabilities, tags, region, regionResolver.getAccountId(), true);
    }

    /**
     * Creates a change set whose condition-dependency preflight is evaluated in {@code accountId}'s
     * context. This matters for StackSet deployments: {@code createChangeSet} runs in the
     * administrator request scope, but the instance is executed in the target account, so a
     * condition using {@code AWS::AccountId} must be preflighted against the same target account the
     * execution will use. Otherwise a resource that is active in the target account is wrongly seen
     * as excluded and its dependents fail with a spurious "Unresolved resource dependencies" error.
     * This parameter changes only the preflight context; change-set and stack identifiers created here
     * remain scoped to the caller account, and the execution account is supplied separately.
     */
    public ChangeSet createChangeSet(String stackName, String changeSetName, String changeSetType,
                                     String templateBody, String templateUrl,
                                     Map<String, String> parameters, List<String> capabilities,
                                     Map<String, String> tags, String region, String accountId) {
        return createChangeSet(stackName, changeSetName, changeSetType, templateBody, templateUrl,
                parameters, capabilities, tags, region, accountId, false);
    }

    private ChangeSet createChangeSet(String stackName, String changeSetName, String changeSetType,
                                      String templateBody, String templateUrl,
                                      Map<String, String> parameters, List<String> capabilities,
                                      Map<String, String> tags, String region, String accountId,
                                      boolean attachToReviewInProgressStack) {
        String resolvedTemplate = resolveTemplate(templateBody, templateUrl);

        // Reject an unresolvable condition dependency graph up front, before any stack state is
        // created, so CreateStack/UpdateStack fail synchronously the way real CloudFormation does.
        validateConditionDependencies(resolvedTemplate, parameters, region, accountId);

        // A CREATE change set against a name that already has a stack of any status - including
        // ROLLBACK_COMPLETE - is a real conflict: AWS requires an explicit DeleteStack before a
        // name can be reused, even when the existing stack already failed to create (see #2207).
        //
        // The one exception is a stack in REVIEW_IN_PROGRESS, and only for the CreateChangeSet
        // operation itself (see createChangeSetForRequest): that status means a CREATE change set
        // exists but has never been executed, so the stack is a placeholder the next CREATE change
        // set attaches to rather than a deployment to conflict with. The AWS CLI's `deploy` and SAM
        // both depend on it - their has_stack treats REVIEW_IN_PROGRESS as nonexistent and sends a
        // second CREATE change set, which real CloudFormation accepts.
        //
        // The existence check and the insert must be one atomic operation: compute() holds the
        // map's per-key lock for the whole call, so two CreateStack requests racing for the same
        // unused name can no longer both see "absent" and then share whichever Stack
        // computeIfAbsent settled on - the second one now finds the first's stack already there
        // and throws, instead of both executing the template concurrently. That race is also why
        // the exemption above is scoped to the explicit operation: on the CreateStack path every
        // brand-new stack is REVIEW_IN_PROGRESS for the moment between newStack() and the execute
        // that follows it, so exempting the status outright would hand the racing request the same
        // stack and reopen exactly this hole.
        //
        // Recording the change set happens inside the same remapping function, for the same reason.
        // Stack#changeSets is a plain LinkedHashMap, so two requests that legitimately share one
        // stack - two UPDATE change sets on a live stack, or two CREATE change sets attaching to the
        // same REVIEW_IN_PROGRESS placeholder - would otherwise both write it after the per-key lock
        // was already released, losing an accepted change set or corrupting the map's links. Only
        // persistStack() stays outside: it is storage I/O, and compute()'s contract is that the
        // remapping function does short, non-blocking work.
        boolean isCreateType = changeSetType == null || "CREATE".equalsIgnoreCase(changeSetType);
        ChangeSet[] created = new ChangeSet[1];
        Stack stack = stacks.compute(key(stackName, region), (k, existing) -> {
            Stack target;
            if (existing == null) {
                target = newStack(stackName, region);
                if (tags != null) target.getTags().putAll(tags);
                // A CREATE change set puts a brand-new stack into REVIEW_IN_PROGRESS. Record the
                // matching stack-level event (as AWS and LocalStack do) so DescribeStackEvents is
                // non-empty straight after change-set creation — tooling such as the AWS SAM CLI
                // reads StackEvents[0] there and otherwise fails with an IndexError.
                // (CreateChangeSet defaults a null type to CREATE.)
                if (isCreateType) {
                    addEvent(target, target.getStackName(), target.getStackId(),
                            "AWS::CloudFormation::Stack", "REVIEW_IN_PROGRESS", "User Initiated");
                }
            } else {
                boolean reusableReviewPlaceholder =
                        attachToReviewInProgressStack && "REVIEW_IN_PROGRESS".equals(existing.getStatus());
                if (isCreateType && !reusableReviewPlaceholder) {
                    throw new AwsException("AlreadyExistsException",
                            "Stack [" + stackName + "] already exists", 400);
                }
                target = existing;
            }

            ChangeSet cs = new ChangeSet();
            cs.setChangeSetId(AwsArnUtils.Arn.of("cloudformation", region, regionResolver.getAccountId(), "changeSet/" + changeSetName + "/" + UUID.randomUUID()).toString());
            cs.setChangeSetName(changeSetName);
            cs.setStackName(stackName);
            cs.setStackId(target.getStackId());
            cs.setChangeSetType(changeSetType != null ? changeSetType : "CREATE");
            cs.setTemplateBody(resolvedTemplate);
            cs.setParameters(parameters);
            cs.setCapabilities(capabilities);
            cs.setStatus("CREATE_COMPLETE");
            cs.setExecutionStatus("AVAILABLE");
            target.getChangeSets().put(changeSetName, cs);
            created[0] = cs;
            return target;
        });

        persistStack(stack);
        return created[0];
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

    // ── GetTemplateSummary ────────────────────────────────────────────────────

    // IAM resource types whose corresponding property, when a literal string, requires
    // CAPABILITY_NAMED_IAM instead of the weaker CAPABILITY_IAM.
    private static final Map<String, String> IAM_RESOURCE_NAME_PROPERTY = Map.of(
            "AWS::IAM::Role", "RoleName",
            "AWS::IAM::User", "UserName",
            "AWS::IAM::Group", "GroupName",
            "AWS::IAM::ManagedPolicy", "ManagedPolicyName",
            "AWS::IAM::InstanceProfile", "InstanceProfileName");

    /**
     * Summarizes a template's Parameters, Resources, Transform and Metadata sections. Accepts the
     * same three input modes as the real API: an existing stack by name, an inline TemplateBody, or
     * a TemplateURL. Floci does not enforce IAM capabilities on CreateStack/UpdateStack, so the
     * Capabilities/CapabilitiesReason fields here are informational only, derived by scanning for
     * AWS::IAM:: resource types.
     */
    public TemplateSummary getTemplateSummary(String stackName, String templateBody, String templateUrl,
                                              String region) {
        String resolvedBody;
        if (stackName != null && !stackName.isBlank()) {
            Stack stack = getStackOrThrow(stackName, region);
            // Summarize the template as submitted, not the SAM-expanded version stack.getTemplateBody()
            // holds post-transform. originalTemplateBody is only absent for stacks persisted by a
            // floci version predating this field; there is no way to recover the pre-transform body
            // for those, so this falls back to the (already-transformed) templateBody until the
            // stack's next CreateChangeSet/UpdateStack call backfills the field.
            resolvedBody = stack.getOriginalTemplateBody() != null
                    ? stack.getOriginalTemplateBody()
                    : stack.getTemplateBody() != null ? stack.getTemplateBody() : "{}";
        } else {
            resolvedBody = resolveTemplateBody(templateBody, templateUrl);
            if (resolvedBody == null) {
                throw new AwsException("ValidationError",
                        "One of StackName, TemplateBody or TemplateURL must be specified.", 400);
            }
        }
        JsonNode template;
        try {
            template = parseTemplate(resolvedBody);
        } catch (Exception e) {
            throw new AwsException("ValidationError", "Template format error: " + e.getMessage(), 400);
        }
        return buildTemplateSummary(template);
    }

    private TemplateSummary buildTemplateSummary(JsonNode template) {
        String description = template.hasNonNull("Description") ? template.get("Description").asText() : null;
        String version = template.hasNonNull("AWSTemplateFormatVersion")
                ? template.get("AWSTemplateFormatVersion").asText()
                : "2010-09-09";

        List<TemplateSummary.ParameterDeclaration> parameters = new ArrayList<>();
        JsonNode paramsNode = template.path("Parameters");
        if (paramsNode.isObject()) {
            paramsNode.fields().forEachRemaining(entry -> {
                JsonNode p = entry.getValue();
                parameters.add(new TemplateSummary.ParameterDeclaration(
                        entry.getKey(),
                        p.hasNonNull("Default") ? p.get("Default").asText() : null,
                        p.path("NoEcho").asBoolean(false),
                        p.hasNonNull("Description") ? p.get("Description").asText() : null,
                        p.hasNonNull("Type") ? p.get("Type").asText() : "String"));
            });
        }

        LinkedHashSet<String> resourceTypes = new LinkedHashSet<>();
        boolean hasNamedIamResource = false;
        JsonNode resourcesNode = template.path("Resources");
        if (resourcesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> resourceEntries = resourcesNode.fields();
            while (resourceEntries.hasNext()) {
                JsonNode resource = resourceEntries.next().getValue();
                JsonNode typeNode = resource.path("Type");
                if (!typeNode.isTextual()) {
                    continue;
                }
                String type = typeNode.asText();
                resourceTypes.add(type);
                String nameProperty = IAM_RESOURCE_NAME_PROPERTY.get(type);
                // Presence alone counts as named, even when the value is an intrinsic function
                // (Ref, Fn::Sub, Fn::Join, ...) that only resolves at deploy time - CloudFormation
                // requires CAPABILITY_NAMED_IAM whenever the property is set at all.
                if (nameProperty != null && resource.path("Properties").has(nameProperty)) {
                    hasNamedIamResource = true;
                }
            }
        }

        List<String> declaredTransforms = new ArrayList<>();
        JsonNode transformNode = template.path("Transform");
        if (transformNode.isTextual()) {
            declaredTransforms.add(transformNode.asText());
        } else if (transformNode.isArray()) {
            transformNode.forEach(t -> {
                if (t.isTextual()) {
                    declaredTransforms.add(t.asText());
                }
            });
        }

        List<String> iamResourceTypes = resourceTypes.stream()
                .filter(t -> t.startsWith("AWS::IAM::"))
                .toList();
        List<String> capabilities = iamResourceTypes.isEmpty()
                ? List.of()
                : List.of(hasNamedIamResource ? "CAPABILITY_NAMED_IAM" : "CAPABILITY_IAM");
        String capabilitiesReason = iamResourceTypes.isEmpty()
                ? null
                : "The following resource(s) require capabilities: [" + String.join(", ", iamResourceTypes) + "]";

        String metadata = template.hasNonNull("Metadata") ? template.get("Metadata").toString() : null;

        return new TemplateSummary(description, parameters, new ArrayList<>(resourceTypes), version,
                declaredTransforms, capabilities, capabilitiesReason, metadata);
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
        StackUpdateSnapshot previousState = snapshotForUpdate(stack);
        boolean updateCommitted = false;
        Set<String> attemptedResourceIds = new LinkedHashSet<>();
        try {
            JsonNode template = parseTemplate(templateBody);
            stack.setOriginalTemplateBody(templateBody);

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
                    String deletionPolicy = resDef.path("DeletionPolicy").asText(null);
                    JsonNode props = resDef.path("Properties");

                    CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                            accountId, region, stack.getStackName(),
                            stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, mappings, objectMapper,
                            name -> exports.get(exportKey(region, name)));

                    StackResource resource = stack.getResources().get(logicalId);
                    StackResource previousResource = resource;
                    if (resource == null) {
                        resource = new StackResource();
                        resource.setLogicalId(logicalId);
                        resource.setResourceType(type);
                        stack.getResources().put(logicalId, resource);
                    }

                    String inProgressStatus = isCreate
                            ? "CREATE_IN_PROGRESS"
                            : "UPDATE_IN_PROGRESS";
                    addEvent(
                            stack,
                            logicalId,
                            resource.getPhysicalId(),
                            type,
                            inProgressStatus,
                            null);
                    attemptedResourceIds.add(logicalId);
                    if ("AWS::CloudFormation::Stack".equals(type)) {
                        resource = executeNestedStack(stack, logicalId,
                                props.isMissingNode() ? null : props,
                                engine, region, accountId, isCreate);
                    } else {
                        resource = provisioner.provision(logicalId, type, props.isMissingNode() ? null : props,
                                engine, region, accountId, stack.getStackName(),
                                resource.getPhysicalId(), resource.getAttributes());
                    }
                    resource.setUpdateReplacePolicy(
                            resDef.path("UpdateReplacePolicy").asText(null));
                    if (!isCreate) {
                        if ("CREATE_COMPLETE".equals(resource.getStatus())) {
                            resource.setStatus("UPDATE_COMPLETE");
                        } else if ("CREATE_FAILED".equals(resource.getStatus())) {
                            resource.setStatus("UPDATE_FAILED");
                        }
                    }
                    // Both branches return a fresh StackResource, so the policy is carried over here
                    // rather than on the instance the loop started with.
                    resource.setDeletionPolicy(deletionPolicy);
                    stack.getResources().put(logicalId, resource);

                    physicalIds.put(logicalId, resource.getPhysicalId());
                    resourceAttrs.put(logicalId, resource.getAttributes());

                    addEvent(stack, logicalId, resource.getPhysicalId(), type,
                            resource.getStatus(), resource.getStatusReason());

                    if ("CREATE_FAILED".equals(resource.getStatus())
                            || "UPDATE_FAILED".equals(resource.getStatus())) {
                        failedResource = resource;
                        if (!isCreate && previousResource != null) {
                            // Provisioners work on a copy of the stored resource metadata. Keep the
                            // last known-good identity and status when an update attempt fails so a
                            // later retry or stack deletion still manages the original resource.
                            // The rollback walker must also know this resource is already restored;
                            // otherwise an earlier UPDATE_COMPLETE status looks like an unhandled
                            // mutation and incorrectly turns a safe rollback into ROLLBACK_FAILED.
                            previousResource.getAttributes().put(
                                    CloudFormationResourceProvisioner.UPDATE_ROLLBACK_RESTORED_ATTR,
                                    "true");
                            stack.getResources().put(logicalId, previousResource);
                        }
                        break;
                    }
                }
            }

            // A resource failed to provision: stop, and (on create) roll back what we built so a
            // corrected re-deploy starts from a clean slate (acceptance criterion #9).
            if (failedResource != null) {
                rollbackFailedExecution(
                        stack, region, isCreate, failedResource, previousState,
                        attemptedResourceIds);
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

            if (!isCreate) {
                updateCommitted = true;
                if (hasReplacementUpdates(stack) || hasRemovedOrConditionFalseResources(stack, resources, conditions)) {
                    stack.setStatus("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS");
                    stack.setLastUpdatedTime(now());
                    addEvent(stack, stack.getStackName(), stack.getStackId(),
                            "AWS::CloudFormation::Stack",
                            "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", null);
                    // The committed template and new physical IDs must be durable before old
                    // resources are deleted during post-update cleanup.
                    persistStack(stack);
                }
                // Both cleanup paths feed the single final status/reason writer.
                List<UpdateCleanupFailure> cleanupFailures =
                        new ArrayList<>(deleteRemovedOrConditionFalseResources(
                                stack, resources, conditions, region));
                cleanupFailures.addAll(finishCommittedResourceCleanup(stack));
                finishCommittedStackUpdate(stack, cleanupFailures);
                return;
            }

            stack.setStatus("CREATE_COMPLETE");
            stack.setLastUpdatedTime(now());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "CREATE_COMPLETE", null);
            persistStack(stack);
            LOG.infov("Stack {0} execution complete: CREATE_COMPLETE", stack.getStackName());

        } catch (Exception e) {
            if (!isCreate && updateCommitted) {
                LOG.errorv(
                        "Stack {0} update cleanup could not finish: {1}",
                        stack.getStackName(), e.getMessage());
                stack.setStatus("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS");
                stack.setStatusReason(e.getMessage());
                stack.setLastUpdatedTime(now());
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack",
                        "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", e.getMessage());
                persistStack(stack);
                return;
            }
            LOG.errorv("Stack {0} execution failed: {1}", stack.getStackName(), e.getMessage());
            String failStatus = isCreate ? "CREATE_FAILED" : "UPDATE_FAILED";
            stack.setStatus(failStatus);
            stack.setStatusReason(e.getMessage());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", failStatus, e.getMessage());
            if (isCreate) {
                persistStack(stack);
            } else {
                rollbackFailedUpdate(
                        stack, region, previousState, attemptedResourceIds, e.getMessage());
            }
        }
    }

    /**
     * Handles a resource that failed to provision.
     *
     * <p>On a <b>create</b>, rolls back by deleting resources created by the failed execution. On
     * an <b>update</b>, restores the prior resource, template, output, and export state.
     */
    private void rollbackFailedExecution(
            Stack stack,
            String region,
            boolean isCreate,
            StackResource failedResource,
            StackUpdateSnapshot previousState,
            Set<String> attemptedResourceIds) {
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
            List<String> rollbackFailures = rollbackCreatedResources(stack, region);
            stack.setLastUpdatedTime(now());
            if (rollbackFailures.isEmpty()) {
                stack.setStatus("ROLLBACK_COMPLETE");
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "ROLLBACK_COMPLETE", null);
                LOG.infov("Stack {0} rolled back to a clean slate (ROLLBACK_COMPLETE)", stack.getStackName());
            } else {
                String reason = "The following resource(s) failed to roll back: ["
                        + String.join(", ", rollbackFailures) + "].";
                stack.setStatus("ROLLBACK_FAILED");
                stack.setStatusReason(reason);
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "ROLLBACK_FAILED", reason);
                LOG.errorv("Stack {0} rollback failed: {1}", stack.getStackName(), reason);
            }
        } else {
            rollbackFailedUpdate(
                    stack, region, previousState, attemptedResourceIds,
                    failedResource.getStatusReason());
            return;
        }
        persistStack(stack);
    }

    private List<UpdateCleanupFailure> finishCommittedResourceCleanup(Stack stack) {
        List<UpdateCleanupFailure> failures = new ArrayList<>();
        for (StackResource resource : stack.getResources().values()) {
            String cleanupPhysicalId = provisioner.updateCleanupPhysicalId(resource);
            if (cleanupPhysicalId != null) {
                addEvent(
                        stack,
                        resource.getLogicalId(),
                        cleanupPhysicalId,
                        resource.getResourceType(),
                        "DELETE_IN_PROGRESS",
                        null);
            }
            while (true) {
                CloudFormationResourceProvisioner.UpdateCleanupResult result =
                        provisioner.completeUpdate(resource);
                if (!result.applicable()) {
                    break;
                }
                if (result.complete()) {
                    if (cleanupPhysicalId != null) {
                        addEvent(
                                stack,
                                resource.getLogicalId(),
                                cleanupPhysicalId,
                                resource.getResourceType(),
                                "DELETE_COMPLETE",
                                null);
                    }
                    provisioner.clearUpdate(resource);
                    break;
                }
                if (result.attempts() < 3) {
                    continue;
                }

                String reason = result.failureReason() != null
                        ? result.failureReason()
                        : "Resource deletion failed during update cleanup";
                failures.add(new UpdateCleanupFailure(
                        resource.getLogicalId(), result.previousPhysicalId(), reason));
                addEvent(
                        stack,
                        resource.getLogicalId(),
                        result.previousPhysicalId(),
                        resource.getResourceType(),
                        "DELETE_FAILED",
                        reason);
                provisioner.clearUpdate(resource);
                break;
            }
        }
        return failures;
    }

    private void finishCommittedStackUpdate(
            Stack stack, List<UpdateCleanupFailure> cleanupFailures) {
        String statusReason = null;
        if (!cleanupFailures.isEmpty()) {
            statusReason = "The following resource(s) could not be deleted during update cleanup: ["
                    + cleanupFailures.stream()
                            .map(failure -> failure.logicalId()
                                    + " (" + failure.physicalId() + ")")
                            .collect(java.util.stream.Collectors.joining(", "))
                    + "].";
        }
        stack.setStatus("UPDATE_COMPLETE");
        stack.setStatusReason(statusReason);
        stack.setLastUpdatedTime(now());
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", "UPDATE_COMPLETE", statusReason);
        persistStack(stack);
        LOG.infov("Stack {0} execution complete: UPDATE_COMPLETE", stack.getStackName());
    }

    private boolean hasReplacementUpdates(Stack stack) {
        return stack.getResources().values().stream()
                .anyMatch(provisioner::hasReplacementUpdate);
    }

    private boolean hasRemovedOrConditionFalseResources(Stack stack, JsonNode resources, Map<String, Boolean> conditions) {
        if (!resources.isObject()) {
            return false;
        }
        for (StackResource resource : stack.getResources().values()) {
            JsonNode resDef = resources.get(resource.getLogicalId());
            if (resDef == null) {
                return true;
            }
            String condition = resDef.path("Condition").asText(null);
            if (condition != null && !conditions.getOrDefault(condition, false)) {
                return true;
            }
        }
        return false;
    }

    private void deleteResourcePhysically(StackResource resource, String region) throws Exception {
        if ("AWS::CloudFormation::Stack".equals(resource.getResourceType())) {
            Future<?> future = deleteStack(resource.getPhysicalId(), region, regionResolver.getAccountId());
            if (future != null) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) {
                        throw ex;
                    }
                    throw e;
                }
            }
            Stack child = resolveStack(resource.getPhysicalId(), region);
            if (child != null && "DELETE_FAILED".equals(child.getStatus())) {
                String reason = child.getStatusReason() != null
                        ? child.getStatusReason()
                        : "Nested stack deletion failed";
                throw new IllegalStateException(reason);
            }
        } else {
            provisioner.delete(resource, region);
        }
    }

    private void rollbackFailedUpdate(
            Stack stack,
            String region,
            StackUpdateSnapshot previousState,
            Set<String> attemptedResourceIds,
            String failureReason) {
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
        stack.setStatusReason(failureReason);
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_IN_PROGRESS", failureReason);

        List<String> rollbackFailures = rollbackUpdatedResources(
                stack, previousState.resources(), attemptedResourceIds, region);
        if (rollbackFailures.isEmpty()) {
            stack.setTemplateBody(previousState.templateBody());
        }
        try {
            restoreOutputAndExportState(stack, region, previousState);
        } catch (Exception e) {
            rollbackFailures.add("Outputs");
            LOG.errorv("Could not restore outputs and exports for stack {0}: {1}",
                    stack.getStackName(), e.getMessage());
        }
        stack.setLastUpdatedTime(now());
        if (rollbackFailures.isEmpty()) {
            stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
            stack.setStatusReason(null);
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_COMPLETE", null);
            LOG.infov("Stack {0} update rolled back (UPDATE_ROLLBACK_COMPLETE)",
                    stack.getStackName());
        } else {
            String reason = "The following resource(s) failed to roll back: ["
                    + String.join(", ", rollbackFailures) + "].";
            stack.setStatus("UPDATE_ROLLBACK_FAILED");
            stack.setStatusReason(reason);
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_FAILED", reason);
            LOG.errorv("Stack {0} update rollback failed: {1}", stack.getStackName(), reason);
        }
        persistStack(stack);
    }

    private List<String> rollbackUpdatedResources(
            Stack stack,
            Map<String, StackResource> previousResources,
            Set<String> attemptedResourceIds,
            String region) {
        List<StackResource> resources = new ArrayList<>(stack.getResources().values());
        Collections.reverse(resources);
        List<String> failures = new ArrayList<>();
        List<String> removedResources = new ArrayList<>();
        for (StackResource resource : resources) {
            if (!attemptedResourceIds.contains(resource.getLogicalId())) {
                continue;
            }
            try {
                StackResource previous = previousResources.get(resource.getLogicalId());
                if (previous == null) {
                    boolean rollbackOwned = "true".equals(resource.getAttributes().get(
                            CfnRollback.ROLLBACK_OWNED_ATTR));
                    if (resource.getPhysicalId() != null || rollbackOwned) {
                        addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                                resource.getResourceType(), "DELETE_IN_PROGRESS",
                                "Resource creation cancelled during update rollback");
                        deleteResourcePhysically(resource, region);
                    }
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_COMPLETE",
                            "Resource creation cancelled during update rollback");
                    removedResources.add(resource.getLogicalId());
                } else if ("true".equals(resource.getAttributes().remove(
                        CloudFormationResourceProvisioner.UPDATE_ROLLBACK_RESTORED_ATTR))
                        || provisioner.rollbackUpdate(resource)) {
                    resource.setStatus(previous.getStatus());
                    resource.setStatusReason(previous.getStatusReason());
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "UPDATE_COMPLETE",
                            "Resource update rolled back");
                } else if (resource.getStatus() != null
                        && resource.getStatus().startsWith("UPDATE_")) {
                    String reason = "Rollback is not implemented for "
                            + resource.getResourceType();
                    failures.add(resource.getLogicalId());
                    resource.setStatus("UPDATE_FAILED");
                    resource.setStatusReason(reason);
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "UPDATE_FAILED", reason);
                }
            } catch (Exception e) {
                failures.add(resource.getLogicalId());
                resource.setStatus("UPDATE_FAILED");
                resource.setStatusReason(e.getMessage());
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "UPDATE_FAILED", e.getMessage());
                LOG.errorv("Could not roll back resource {0}: {1}",
                        resource.getLogicalId(), e.getMessage());
            }
        }
        removedResources.forEach(stack.getResources()::remove);
        return failures;
    }

    private void restoreOutputAndExportState(
            Stack stack, String region, StackUpdateSnapshot previousState) {
        RuntimeException storageFailure = null;
        for (String exportName : new ArrayList<>(stack.getExports().keySet())) {
            String key = exportKey(region, exportName);
            exports.remove(key);
            try {
                exportBackend.deleteForAccount(storageAccount, key);
            } catch (RuntimeException e) {
                storageFailure = appendFailure(storageFailure, e);
            }
        }

        stack.getOutputs().clear();
        stack.getOutputs().putAll(previousState.outputs());
        stack.getExports().clear();
        stack.getExports().putAll(previousState.exports());
        stack.getOutputExportNames().clear();
        stack.getOutputExportNames().putAll(previousState.outputExportNames());

        for (Map.Entry<String, String> entry : previousState.exports().entrySet()) {
            String key = exportKey(region, entry.getKey());
            exports.put(key, entry.getValue());
            try {
                exportBackend.putForAccount(storageAccount, key, entry.getValue());
            } catch (RuntimeException e) {
                storageFailure = appendFailure(storageFailure, e);
            }
        }
        if (storageFailure != null) {
            throw storageFailure;
        }
    }

    private static RuntimeException appendFailure(
            RuntimeException existing, RuntimeException additional) {
        if (existing == null) {
            return additional;
        }
        existing.addSuppressed(additional);
        return existing;
    }

    private StackUpdateSnapshot snapshotForUpdate(Stack stack) {
        return new StackUpdateSnapshot(
                stack.getTemplateBody(),
                new LinkedHashMap<>(stack.getOutputs()),
                new LinkedHashMap<>(stack.getExports()),
                new LinkedHashMap<>(stack.getOutputExportNames()),
                copyResources(stack.getResources()));
    }

    private Map<String, StackResource> copyResources(
            Map<String, StackResource> resources) {
        Map<String, StackResource> copies = new LinkedHashMap<>();
        resources.forEach((logicalId, resource) ->
                copies.put(logicalId, copyResource(resource)));
        return copies;
    }

    private StackResource copyResource(StackResource source) {
        StackResource copy = new StackResource();
        copy.setLogicalId(source.getLogicalId());
        copy.setPhysicalId(source.getPhysicalId());
        copy.setResourceType(source.getResourceType());
        copy.setStatus(source.getStatus());
        copy.setStatusReason(source.getStatusReason());
        copy.setDeletionPolicy(source.getDeletionPolicy());
        copy.setUpdateReplacePolicy(source.getUpdateReplacePolicy());
        copy.setTimestamp(source.getTimestamp());
        copy.setAttributes(new HashMap<>(source.getAttributes()));
        return copy;
    }

    private record StackUpdateSnapshot(
            String templateBody,
            Map<String, String> outputs,
            Map<String, String> exports,
            Map<String, String> outputExportNames,
            Map<String, StackResource> resources) {
    }

    private record UpdateCleanupFailure(
            String logicalId, String physicalId, String failureReason) {
    }

    /** Deletes every resource created in this execution, in reverse order. */
    private List<String> rollbackCreatedResources(Stack stack, String region) {
        List<StackResource> resources = new ArrayList<>(stack.getResources().values());
        Collections.reverse(resources);
        List<String> failedResources = new ArrayList<>();
        for (StackResource resource : resources) {
            boolean completed = "CREATE_COMPLETE".equals(resource.getStatus());
            boolean ownedFailedResource = "CREATE_FAILED".equals(resource.getStatus())
                    && "true".equals(resource.getAttributes().get(
                            CfnRollback.ROLLBACK_OWNED_ATTR));
            if (resource.getPhysicalId() == null || (!completed && !ownedFailedResource)) {
                continue;
            }
            if (skipRetainedResource(stack, resource, true)) {
                continue;
            }
            addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                    resource.getResourceType(), "DELETE_IN_PROGRESS", null);
            try {
                deleteResourcePhysically(resource, region);
                resource.setStatus("DELETE_COMPLETE");
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_COMPLETE", null);
            } catch (Exception e) {
                failedResources.add(resource.getLogicalId());
                resource.setStatus("DELETE_FAILED");
                resource.setStatusReason(e.getMessage());
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_FAILED", e.getMessage());
                LOG.warnv("Failed to roll back {0} ({1}) in stack {2}: {3}",
                        resource.getResourceType(), resource.getPhysicalId(),
                        stack.getStackName(), e.getMessage());
            }
        }
        return failedResources;
    }

    /**
     * Removes resources that were provisioned by an earlier execution but whose resource-level
     * {@code Condition} is false in the current template, or that were removed from the template entirely.
     * Physical deletion honors the resource's retention policy ({@code Retain}, {@code RetainExceptOnCreate}).
     * A failed physical deletion leaves the resource in the underlying service and keeps it under stack management
     * as {@code DELETE_FAILED}, so a later {@code DeleteStack} or {@code UpdateStack} can retry the cleanup
     * instead of orphaning the backing resource.
     *
     * @return one {@link UpdateCleanupFailure} per resource whose physical deletion failed
     */
    private List<UpdateCleanupFailure> deleteRemovedOrConditionFalseResources(Stack stack, JsonNode resources,
                                                           Map<String, Boolean> conditions, String region) {
        if (!resources.isObject()) {
            return List.of();
        }

        List<UpdateCleanupFailure> failures = new ArrayList<>();
        List<StackResource> ordered = new ArrayList<>(stack.getResources().values());
        Collections.reverse(ordered);
        for (StackResource resource : ordered) {
            JsonNode resDef = resources.get(resource.getLogicalId());
            if (resDef != null) {
                String condition = resDef.path("Condition").asText(null);
                if (condition == null || conditions.getOrDefault(condition, false)) {
                    continue;
                }
            }

            if (resource.getPhysicalId() == null || skipRetainedResource(stack, resource, false)) {
                stack.getResources().remove(resource.getLogicalId());
                continue;
            }

            addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                    resource.getResourceType(), "DELETE_IN_PROGRESS", null);
            try {
                deleteResourcePhysically(resource, region);
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_COMPLETE", null);
                stack.getResources().remove(resource.getLogicalId());
            } catch (Exception e) {
                String reason = e.getMessage() != null
                        ? e.getMessage()
                        : "Resource deletion failed during update cleanup";
                failures.add(new UpdateCleanupFailure(
                        resource.getLogicalId(), resource.getPhysicalId(), reason));
                // Keep the resource under stack management as DELETE_FAILED. Removing it here would
                // drop it from DescribeStackResources while the backing resource still exists,
                // orphaning it and preventing a later DeleteStack/UpdateStack from retrying cleanup.
                resource.setStatus("DELETE_FAILED");
                resource.setStatusReason(reason);
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_FAILED", reason);
                LOG.warnv("Failed to delete removed or condition-disabled {0} ({1}) in stack {2}: {3}",
                        resource.getResourceType(), resource.getPhysicalId(),
                        stack.getStackName(), reason);
            }
        }

        return failures;
    }

    private void deleteStackResources(Stack stack, String region) {
        try {
            List<StackResource> resources = new ArrayList<>(stack.getResources().values());
            Collections.reverse(resources); // Delete in reverse order

            List<String> failedResources = new ArrayList<>();
            for (StackResource resource : resources) {
                // CREATE_COMPLETE/UPDATE_COMPLETE: first delete attempt. DELETE_FAILED: a previous
                // delete left the resource behind (e.g. the bucket was non-empty); AWS re-attempts
                // it on retry.
                boolean deletable = "CREATE_COMPLETE".equals(resource.getStatus())
                        || "UPDATE_COMPLETE".equals(resource.getStatus())
                        || "DELETE_FAILED".equals(resource.getStatus());
                if (resource.getPhysicalId() == null || !deletable) {
                    continue;
                }
                if (skipRetainedResource(stack, resource, false)) {
                    continue;
                }
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_IN_PROGRESS", null);
                try {
                    deleteResourcePhysically(resource, region);
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
                persistStack(stack);
                LOG.errorv("Stack {0} delete failed: {1}", stack.getStackName(), reason);
                throw new IllegalStateException(reason);
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
            persistStack(stack);
            throw (e instanceof RuntimeException re ? re : new RuntimeException(e));
        }
    }

    /**
     * Applies a resource's {@code DeletionPolicy}. {@code Retain} keeps the resource on every stack
     * operation; {@code RetainExceptOnCreate} keeps it too, except when the create that made it is
     * rolled back. Every other value — including {@code Snapshot}, which floci cannot snapshot —
     * falls through to a normal delete, matching the default.
     *
     * @return {@code true} when the resource was kept and reported as {@code DELETE_SKIPPED}
     */
    private boolean skipRetainedResource(Stack stack, StackResource resource, boolean createRollback) {
        String policy = resource.getDeletionPolicy();
        boolean retained = "Retain".equals(policy)
                || (!createRollback && "RetainExceptOnCreate".equals(policy));
        if (!retained) {
            return false;
        }
        resource.setStatus("DELETE_SKIPPED");
        addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                resource.getResourceType(), "DELETE_SKIPPED", null);
        LOG.infov("Retained {0} ({1}) in stack {2}: DeletionPolicy {3}",
                resource.getResourceType(), resource.getPhysicalId(), stack.getStackName(), policy);
        return true;
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

    /**
     * Fails a create/update before any stack state is mutated when a resource that will be created
     * depends on a resource excluded by a false condition. Real CloudFormation rejects such a
     * template synchronously ("Template format error: Unresolved resource dependencies [...]")
     * rather than silently skipping the dependent, so mirror that instead of dropping the resource.
     * Malformed or SAM templates are left for the execution path, which surfaces their own errors.
     */
    private void validateConditionDependencies(String templateBody, Map<String, String> params,
                                               String region, String accountId) {
        JsonNode template;
        try {
            template = parseTemplate(templateBody);
        } catch (Exception e) {
            LOG.debugv("Skipping condition-dependency validation; template did not parse: {0}",
                    e.getMessage());
            return;
        }
        if (samTransformProcessor.hasSamTransform(template)) {
            return;
        }
        JsonNode resources = template.path("Resources");
        if (!resources.isObject()) {
            return;
        }

        Map<String, String> resolvedParams = resolveDefaultParameters(template, params);
        Map<String, Boolean> conditions =
                resolveConditions(template, resolvedParams, null, region, accountId);

        Set<String> allIds = new LinkedHashSet<>();
        resources.fieldNames().forEachRemaining(allIds::add);
        Set<String> activeIds = new LinkedHashSet<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (String logicalId : allIds) {
            JsonNode resDef = resources.get(logicalId);
            String condition = resDef.path("Condition").asText(null);
            if (condition == null || conditions.getOrDefault(condition, false)) {
                activeIds.add(logicalId);
            }
            dependencies.put(logicalId, collectResourceDependencies(resDef, allIds, conditions));
        }

        Set<String> unresolved = unresolvedConditionDependencies(activeIds, allIds, dependencies);
        if (!unresolved.isEmpty()) {
            throw unresolvedDependenciesError(unresolved);
        }
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
        Set<String> activeIds = new LinkedHashSet<>();
        for (String logicalId : allIds) {
            JsonNode resDef = resources.get(logicalId);
            String condition = resDef.path("Condition").asText(null);
            if (condition == null || conditions.getOrDefault(condition, false)) {
                activeIds.add(logicalId);
            }
            dependencies.put(logicalId, collectResourceDependencies(resDef, allIds, conditions));
        }

        // AWS rejects a template whose created resources depend on a resource excluded by a false
        // condition rather than silently skipping the dependent (verified against real
        // CloudFormation: CreateStack fails synchronously with "Unresolved resource dependencies").
        // Fn::If-guarded references are safe because collectDependencies only walks the selected
        // branch, so a false-branch reference is never recorded as a dependency.
        Set<String> unresolved = unresolvedConditionDependencies(activeIds, allIds, dependencies);
        if (!unresolved.isEmpty()) {
            throw unresolvedDependenciesError(unresolved);
        }
        dependencies.keySet().retainAll(activeIds);

        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : activeIds) {
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
        for (String id : activeIds) {
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

        for (String id : activeIds) {
            if (!sorted.contains(id)) {
                sorted.add(id);
            }
        }

        return sorted;
    }

    private static final Pattern SUB_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Collects the logical IDs this resource depends on, both through its {@code Properties}
     * (Ref/GetAtt/Fn::Sub, and the selected branch of Fn::If) and its explicit {@code DependsOn}.
     */
    private Set<String> collectResourceDependencies(JsonNode resDef, Set<String> allIds,
                                                    Map<String, Boolean> conditions) {
        Set<String> deps = new LinkedHashSet<>();
        collectDependencies(resDef.path("Properties"), allIds, deps, conditions);

        JsonNode dependsOn = resDef.path("DependsOn");
        if (dependsOn.isTextual()) {
            deps.add(dependsOn.asText());
        } else if (dependsOn.isArray()) {
            for (JsonNode d : dependsOn) {
                deps.add(d.asText());
            }
        }
        return deps;
    }

    /**
     * Returns the logical IDs of resources that are excluded by a false condition yet are still
     * depended upon by a resource that will be created. An empty set means the dependency graph is
     * resolvable for the current condition values.
     */
    private Set<String> unresolvedConditionDependencies(Set<String> activeIds, Set<String> allIds,
                                                        Map<String, Set<String>> dependencies) {
        Set<String> unresolved = new LinkedHashSet<>();
        for (String logicalId : activeIds) {
            for (String dependency : dependencies.get(logicalId)) {
                if (allIds.contains(dependency) && !activeIds.contains(dependency)) {
                    unresolved.add(dependency);
                }
            }
        }
        return unresolved;
    }

    private AwsException unresolvedDependenciesError(Set<String> unresolved) {
        return new AwsException("ValidationError",
                "Template format error: Unresolved resource dependencies ["
                        + String.join(", ", unresolved)
                        + "] in the Resources block of the template", 400);
    }

    private void collectDependencies(JsonNode node, Set<String> allIds, Set<String> deps,
                                     Map<String, Boolean> conditions) {
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
            if (node.has("Fn::If")) {
                JsonNode fnIf = node.get("Fn::If");
                if (fnIf.isArray() && fnIf.size() == 3) {
                    boolean condition = conditions.getOrDefault(fnIf.get(0).asText(), false);
                    collectDependencies(fnIf.get(condition ? 1 : 2), allIds, deps, conditions);
                    return;
                }
            }
            if (node.has("Fn::Sub")) {
                collectSubDependencies(node.get("Fn::Sub"), allIds, deps, conditions);
                return;
            }
            node.fields().forEachRemaining(e -> collectDependencies(e.getValue(), allIds, deps, conditions));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectDependencies(item, allIds, deps, conditions);
            }
        }
    }

    private void collectSubDependencies(JsonNode sub, Set<String> allIds, Set<String> deps,
                                        Map<String, Boolean> conditions) {
        String template;
        Set<String> explicitVars = new HashSet<>();

        if (sub.isTextual()) {
            template = sub.textValue();
        } else if (sub.isArray() && sub.size() >= 1) {
            template = sub.get(0).asText();
            if (sub.size() >= 2 && sub.get(1).isObject()) {
                sub.get(1).fieldNames().forEachRemaining(explicitVars::add);
                collectDependencies(sub.get(1), allIds, deps, conditions);
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
