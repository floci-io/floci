package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackUpdateSnapshot;
import io.github.hectorvent.floci.services.cloudformation.model.TemplateSummary;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.UpdateCleanupResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CloudFormation stack lifecycle management — Create, Update, Delete stacks via ChangeSets.
 */
@ApplicationScoped
public class CloudFormationService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(CloudFormationService.class);

    /**
     * The reason CloudFormation gives a change set that submits what the stack already has, and
     * the condition under which UpdateStack answers "No updates are to be performed."
     */
    static final String NO_CHANGES_REASON =
            "The submitted information didn't contain changes. Submit different information to create a change set.";

    private static final int MAX_OPERATION_THREADS = 16;
    private static final int MAX_QUEUED_OPERATIONS = 128;

    // Texts a client may match on, kept here so each has one place to change. All but
    // SKIP_NESTED_UNSUPPORTED are the texts real CloudFormation emits, measured against a real
    // account; that one is Floci's own, for a skip AWS accepts and Floci does not support.
    private static final String UPDATE_REFUSED = "Stack:%s is in %s state and can not be updated.";
    private static final String CONTINUE_ROLLBACK_REFUSED =
            "RollbackUpdatedStack cannot be called from current stack status";
    private static final String SKIP_NOT_UPDATE_FAILED =
            "Only the resources in UPDATE_FAILED state are allowed to be skipped";
    private static final String SKIP_NOT_IN_STACK = "Resources with logicalIds [%s] do not belong to stack %s";
    private static final String SKIP_NESTED_STACK_MISSING = "Stack [%s] does not exist";
    private static final String SKIP_NESTED_UNSUPPORTED =
            "Skipping a resource of a nested stack is not supported by Floci: %s";
    private static final String SKIPPED_RESOURCE_REASON = "Resource skipped during UpdateRollback";
    private static final String FORCED_ROLLBACK_COMPLETE_REASON =
            "One or more resources were skipped during UpdateRollback. These resources were not rolled"
                    + " back and may have drifted from the template. Run drift detection (DetectStackDrift)"
                    + " to review their current state before your next update.";
    private static final String FORCE_WITH_SKIPS = "ForceRollback and ResourcesToSkip are mutually exclusive";
    private static final String CONTINUE_STACK_MISSING = "Stack: %s does not exist";
    /** The StackName pattern ContinueUpdateRollback and DescribeStacks validate a name against. */
    private static final Pattern STACK_NAME =
            Pattern.compile("[a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]*");
    private static final Pattern RESOURCE_TO_SKIP =
            Pattern.compile("[a-zA-Z0-9]+|[a-zA-Z][-a-zA-Z0-9]*\\.[a-zA-Z0-9]+");

    private final ConcurrentHashMap<String, Stack> stacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeletedStackEntry> deletedStacks = new ConcurrentHashMap<>();
    // Account-scoped exports registry: account:region:exportName -> exportValue
    private final ConcurrentHashMap<String, String> exports = new ConcurrentHashMap<>();
    private final ExecutorService executor = newOperationExecutor();

    static ThreadPoolExecutor newOperationExecutor() {
        return new ThreadPoolExecutor(
                MAX_OPERATION_THREADS, MAX_OPERATION_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_OPERATIONS),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private final CfnResourceDispatcher dispatcher;
    private final S3Service s3Service;
    private final SsmService ssmService;
    private final GetStackOutputRoleAuthorizer stackOutputRoles;
    private final CfnDynamicReferences dynamicReferences;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final SamTransformProcessor samTransformProcessor;
    private final AwsIncludeProcessor awsIncludeProcessor;
    private final Clock clock;

    // Persisted state so stacks survive a restart (criteria #10, #11). The in-memory maps above are
    // the live working copy; these backends are write-through + loaded on startup. Legacy records
    // without an account owner are attributed to the configured default account by the storage layer.
    private final AccountAwareStorageBackend<Stack> stackBackend;
    private final AccountAwareStorageBackend<String> exportBackend;


    @Inject
    public CloudFormationService(CfnResourceDispatcher dispatcher, S3Service s3Service,
                                 SsmService ssmService, GetStackOutputRoleAuthorizer stackOutputRoles,
                                 CfnDynamicReferences dynamicReferences,
                                 ObjectMapper objectMapper, EmulatorConfig config,
                                 RegionResolver regionResolver, Clock clock,
                                 StorageFactory storageFactory) {
        this.dispatcher = dispatcher;
        this.s3Service = s3Service;
        this.ssmService = ssmService;
        this.stackOutputRoles = stackOutputRoles;
        this.dynamicReferences = dynamicReferences;
        this.objectMapper = objectMapper;
        this.config = config;
        this.regionResolver = regionResolver;
        this.samTransformProcessor = new SamTransformProcessor(objectMapper);
        this.awsIncludeProcessor = new AwsIncludeProcessor(objectMapper, s3Service);
        this.clock = clock;
        this.stackBackend = storageFactory.create(
                "cloudformation", "cloudformation-stacks.json", new TypeReference<Map<String, Stack>>() {});
        this.exportBackend = storageFactory.create(
                "cloudformation", "cloudformation-exports.json", new TypeReference<Map<String, String>>() {});
    }

    @PostConstruct
    void loadPersistedState() {
        for (var entry : stackBackend.scanAllAccountEntries(k -> true)) {
            Stack stack = entry.value();
            if (stack.getAccountId() == null) {
                stack.setAccountId(entry.accountId());
            }
            stacks.put(stackKey(stack.getAccountId(), stack.getStackName(), stack.getRegion()), stack);
        }
        for (var entry : exportBackend.scanAllAccountEntries(k -> true)) {
            exports.put(accountExportKey(entry.accountId(), entry.key()), entry.value());
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
        String accountId = ownerAccount(stack);
        stack.setAccountId(accountId);
        stackBackend.putForAccount(accountId, stackStorageKey(stack.getStackName(), stack.getRegion()), stack);
    }

    private void unpersistStack(String accountId, String stackName, String region) {
        stackBackend.deleteForAccount(accountId, stackStorageKey(stackName, region));
    }

    private String ownerAccount(Stack stack) {
        // Persisted stacks are assigned their storage partition's account during startup. The
        // default is only a compatibility attribution for transient ownerless fixtures created
        // directly by callers of the service.
        return stack.getAccountId() != null ? stack.getAccountId() : config.defaultAccountId();
    }

    private String currentAccount() {
        return regionResolver.getAccountId();
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
        return describeStacks(stackName, region, currentAccount());
    }

    public List<Stack> describeStacks(String stackName, String region, String accountId) {
        if (stackName != null && !stackName.isBlank()) {
            Stack stack = resolveStackForDescribe(stackName, region, accountId);
            if (stack == null) {
                throw new AwsException("ValidationError",
                        "Stack with id " + stackName + " does not exist", 400);
            }
            return List.of(stack);
        }
        return stacks.values().stream()
                .filter(s -> accountId.equals(ownerAccount(s)) && region.equals(s.getRegion()))
                .sorted(Comparator.comparing(Stack::getCreationTime))
                .toList();
    }

    /**
     * The stack's current parameter values, or an empty map if it does not exist (yet). Used to
     * resolve {@code UsePreviousValue} on an update before the stack lookup that
     * {@code createChangeSet}/{@code executeChangeSet} would otherwise perform.
     */
    public Map<String, String> currentParameters(String stackName, String region) {
        Stack stack = resolveStack(stackName, region);
        return stack != null ? stack.parametersSnapshot() : Map.of();
    }

    /**
     * The status of a stack, or {@code null} when no stack of that name exists in the region.
     * Unlike {@link #describeStacks}, asking about a stack that is not there is not an error:
     * StackSet deployment uses this to decide what to do with an instance before it acts on it.
     */
    String stackStatus(String stackName, String region) {
        return stackStatus(stackName, region, currentAccount());
    }

    String stackStatus(String stackName, String region, String accountId) {
        Stack stack = resolveStack(stackName, region, accountId);
        return stack != null ? stack.getStatus() : null;
    }

    private static boolean isBeingDeleted(String status) {
        return "DELETE_IN_PROGRESS".equals(status) || "DELETE_COMPLETE".equals(status);
    }

    /**
     * Whether a stack in this status refuses an update, on the two grounds AWS refuses one.
     *
     * <p>A status ending in {@code _IN_PROGRESS} says an operation owns the stack: a create, an
     * update, a rollback or the cleanup phase of a committed update. AWS refuses to start a second
     * one over it, and offers nothing that finishes a phase whose process is gone - DeleteStack is
     * the only way out of one. Refusing keeps every abandoned phase out of the next update's
     * transaction.
     *
     * <p>{@code ROLLBACK_COMPLETE} is terminal for a different reason: a create that failed rolled
     * its resources back, so the stack holds the name and nothing else, and only DeleteStack frees
     * it. The CDK CLI keys on this state and tells the user to delete the stack; a client that is
     * accepted here instead proceeds against a stack AWS would have rejected. It is matched exactly
     * rather than by suffix, because {@code UPDATE_ROLLBACK_COMPLETE} ends the same way and is a
     * perfectly updatable stack: it is where an update that failed and rolled back settles, and
     * retrying the update is how it is repaired.
     *
     * <p>{@code UPDATE_ROLLBACK_FAILED} is refused because the rollback of the last update left
     * resources in an unknown state, and an update would build on them. ContinueUpdateRollback
     * finishes that rollback, and DeleteStack removes the stack; both stay allowed.
     */
    static boolean refusesUpdate(String status) {
        return status != null
                && (status.endsWith("_IN_PROGRESS") || "ROLLBACK_COMPLETE".equals(status)
                        || "UPDATE_ROLLBACK_FAILED".equals(status));
    }

    /**
     * Validates the template source of an {@code UpdateStack} or {@code CreateChangeSet} request:
     * {@code UsePreviousTemplate} excludes {@code TemplateBody} and {@code TemplateURL}, and
     * without it one of the two is required.
     */
    public void validateTemplateSource(String templateBody, String templateUrl, boolean usePreviousTemplate) {
        boolean hasTemplate = (templateBody != null && !templateBody.isBlank())
                || (templateUrl != null && !templateUrl.isBlank());
        if (usePreviousTemplate && hasTemplate) {
            throw new AwsException("ValidationError",
                    "UsePreviousTemplate cannot be specified together with TemplateBody or TemplateURL", 400);
        }
        if (!usePreviousTemplate && !hasTemplate) {
            throw new AwsException("ValidationError",
                    "Either Template URL or Template Body must be specified.", 400);
        }
    }

    /**
     * Creates an UPDATE change set from the template the stack currently holds, as submitted
     * rather than SAM- or Include-expanded, so executing it keeps {@code GetTemplate}'s Original
     * stage intact. The template is read outside the stack's lock, so the change set is only
     * recorded if no other update replaced it in the meantime; otherwise it is read again.
     */
    public ChangeSet createChangeSetFromPreviousTemplate(String stackName, String changeSetName,
                                                         Map<String, String> parameters,
                                                         List<String> capabilities,
                                                         Map<String, String> tags, String region) {
        ChangeSet created = null;
        while (created == null) {
            Stack stack = resolveStack(stackName, region);
            if (stack == null) {
                throw new AwsException("ValidationError",
                        "Stack with id " + stackName + " does not exist", 400);
            }
            String previousTemplate = previousTemplateOf(stack);
            created = createChangeSet(stackName, changeSetName, "UPDATE", previousTemplate, null,
                    parameters, capabilities, tags, region, currentAccount(), false, true);
        }
        return created;
    }

    private static String previousTemplateOf(Stack stack) {
        return stack.getOriginalTemplateBody() != null
                ? stack.getOriginalTemplateBody()
                : stack.getTemplateBody();
    }

    // ── CreateChangeSet ───────────────────────────────────────────────────────

    public ChangeSet createChangeSet(String stackName, String changeSetName, String changeSetType,
                                     String templateBody, String templateUrl,
                                     Map<String, String> parameters, List<String> capabilities,
                                     Map<String, String> tags, String region) {
        return createChangeSet(stackName, changeSetName, changeSetType, templateBody, templateUrl,
                parameters, capabilities, tags, region, regionResolver.getAccountId(), false, false);
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
                parameters, capabilities, tags, region, regionResolver.getAccountId(), true, false);
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
                parameters, capabilities, tags, region, accountId, false, false);
    }

    private ChangeSet createChangeSet(String stackName, String changeSetName, String changeSetType,
                                      String templateBody, String templateUrl,
                                      Map<String, String> parameters, List<String> capabilities,
                                      Map<String, String> tags, String region, String accountId,
                                      boolean attachToReviewInProgressStack, boolean fromPreviousTemplate) {
        String resolvedTemplate = resolveTemplate(templateBody, templateUrl);
        requireValidTemplate(resolvedTemplate);
        boolean isCreateType = "CREATE".equalsIgnoreCase(changeSetType);
        // An update names its stack by name or stack ID; the map is keyed by name. One that names no
        // live stack is keyed as given, so it lands in the missing-stack branch below.
        Stack live = isCreateType ? null : resolveStack(stackName, region, accountId);

        // Ahead of the transform and the preflight below, which both read the parameter values. A
        // request the claim below refuses for its stack alone (an update of a stack that is missing
        // or cannot be updated, a create over a name in use) gets that refusal instead: there is no
        // deployed value for its UsePreviousValue to keep.
        Stack named = isCreateType ? stacks.get(stackKey(accountId, stackName, region)) : live;
        boolean refusedForItsStack = isCreateType
                ? named != null && !(attachToReviewInProgressStack && "REVIEW_IN_PROGRESS".equals(named.getStatus()))
                : named == null || "DELETE_COMPLETE".equals(named.getStatus()) || refusesUpdate(named.getStatus());
        if (!refusedForItsStack) {
            requireParameterValues(resolvedTemplate, parameters);
        }

        // The preflight evaluates AWS::StackName and AWS::StackId as the execution will: an update
        // or a create attaching to a placeholder against that stack, any other create against the
        // id it is given below, allocated now. A create over a name in use is checked as that new
        // stack, never as the stack holding the name; an update of a missing stack, refused below,
        // with the name it gave and an empty id.
        boolean attaches = isCreateType && named != null && !refusedForItsStack;
        String createdStackId = isCreateType && !attaches ? newStackId(stackName, region, accountId) : null;
        Stack identity = isCreateType ? (attaches ? named : null) : live;
        String preflightStackName = identity != null ? identity.getStackName() : stackName;
        String preflightStackId = identity != null ? identity.getStackId()
                : createdStackId != null ? createdStackId : "";

        // Real CloudFormation runs a declared macro (here, only AWS::Serverless-2016-10-31)
        // before it ever evaluates the template's own resources or conditions. On real AWS,
        // create-change-set against a template with an invalid SAM resource (for example a local,
        // unpackaged DefinitionUri) does not fail the API call: it creates the change set and
        // marks it FAILED, with the transform's own error in StatusReason. Match that here instead
        // of throwing, so this failure is reported by the change set, not by a 400 on creation.
        String samTransformFailureReason = samTransformFailureReason(stackName, resolvedTemplate, parameters, region);

        // Reject an unresolvable or circular dependency graph up front, before any stack state is
        // created, so CreateStack/UpdateStack fail synchronously the way real CloudFormation does.
        // Unconditional: validateResourceDependencies already returns immediately for any
        // template declaring the SAM transform, whether or not that transform succeeded, so
        // gating this call on samTransformFailureReason == null duplicates that check for no
        // effect.
        validateGetStackOutputPlacement(resolvedTemplate);
        validateResourceDependencies(resolvedTemplate, parameters, preflightStackName, preflightStackId,
                region, accountId);

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
        String canonicalStackName = live == null ? stackName : live.getStackName();
        // Whether the update has nothing to change is decided here, outside compute(): it reads
        // AWS::Include fragments from S3, runs the SAM transform and resolves SSM parameters. Inside,
        // the verdict stands only if the stack still holds what it was reached against.
        DeployedState checked = isCreateType || live == null ? null : DeployedState.of(live);
        boolean unchangedWhenChecked = checked != null
                && submitsWhatTheStackHas(live, resolvedTemplate, parameters, tags, region);
        ChangeSet[] created = new ChangeSet[1];
        boolean[] unchanged = new boolean[1];
        Stack stack = stacks.compute(stackKey(accountId, canonicalStackName, region), (k, existing) -> {
            Stack target;
            if (existing == null) {
                if (!isCreateType) {
                    throw noStackToUpdate(stackName, region, accountId);
                }
                // The id the preflight saw; a placeholder that left the map since then has none.
                target = newStack(stackName,
                        createdStackId != null ? createdStackId : newStackId(stackName, region, accountId),
                        region, accountId);
                if (tags != null) target.getTags().putAll(tags);
                // A CREATE change set puts a brand-new stack into REVIEW_IN_PROGRESS. Record the
                // matching stack-level event (as AWS and LocalStack do) so DescribeStackEvents is
                // non-empty straight after change-set creation — tooling such as the AWS SAM CLI
                // reads StackEvents[0] there and otherwise fails with an IndexError.
                if (isCreateType) {
                    addEvent(target, target.getStackName(), target.getStackId(),
                            "AWS::CloudFormation::Stack", "REVIEW_IN_PROGRESS", "User Initiated");
                }
            } else {
                String status = existing.getStatus();
                boolean reusableReviewPlaceholder =
                        attachToReviewInProgressStack && "REVIEW_IN_PROGRESS".equals(status);
                if (isCreateType && !reusableReviewPlaceholder) {
                    throw new AwsException("AlreadyExistsException",
                            "Stack [" + stackName + "] already exists", 400);
                }
                // A name whose stack has finished deleting names no live stack, even before it leaves the
                // map, and neither does a stack ID that is not the live stack's.
                if (!isCreateType && !stackName.equals(existing.getStackId())
                        && ("DELETE_COMPLETE".equals(status) || AwsArnUtils.isArn(stackName))) {
                    throw noStackToUpdate(stackName, region, accountId);
                }
                // The message is the one real CloudFormation emits, down to its own "can not"
                // spelling and the stack id carried as "Stack:<arn>" with no space: clients match
                // on this string.
                if (!isCreateType && (refusesUpdate(status) || "DELETE_COMPLETE".equals(status))) {
                    throw cannotBeUpdated(existing.getStackId(), status);
                }
                if (fromPreviousTemplate && !Objects.equals(templateBody, previousTemplateOf(existing))) {
                    return existing;
                }
                target = existing;
                unchanged[0] = unchangedWhenChecked && existing == live
                        && checked.equals(DeployedState.of(existing));
            }

            ChangeSet cs = new ChangeSet();
            cs.setChangeSetId(AwsArnUtils.Arn.of("cloudformation", region, accountId, "changeSet/" + changeSetName + "/" + UUID.randomUUID()).toString());
            cs.setChangeSetName(changeSetName);
            cs.setStackName(canonicalStackName);
            cs.setStackId(target.getStackId());
            cs.setChangeSetType(changeSetType != null ? changeSetType : "UPDATE");
            cs.setTemplateBody(resolvedTemplate);
            cs.setParameters(parameters);
            cs.setCapabilities(capabilities);
            cs.setTags(tags);
            if (samTransformFailureReason != null) {
                cs.setStatus("FAILED");
                cs.setExecutionStatus("UNAVAILABLE");
                cs.setStatusReason(samTransformFailureReason);
            } else if (unchanged[0]) {
                cs.setStatus("FAILED");
                cs.setExecutionStatus("UNAVAILABLE");
                cs.setStatusReason(NO_CHANGES_REASON);
            } else {
                cs.setStatus("CREATE_COMPLETE");
                cs.setExecutionStatus("AVAILABLE");
            }
            target.getChangeSets().put(changeSetName, cs);
            created[0] = cs;
            return target;
        });

        if (created[0] != null) {
            persistStack(stack);
        }
        return created[0];
    }

    /** What {@link #submitsWhatTheStackHas} compares an update against. */
    private record DeployedState(String template, Map<String, String> parameters,
                                 Map<String, String> resolvedParameters, Map<String, String> tags) {
        static DeployedState of(Stack stack) {
            return new DeployedState(stack.getTemplateBody(), stack.parametersSnapshot(),
                    stack.resolvedParametersSnapshot(), stack.tagsSnapshot());
        }
    }

    private static AwsException cannotBeUpdated(String stackId, String status) {
        return new AwsException("ValidationError", UPDATE_REFUSED.formatted(stackId, status), 400);
    }

    // AWS refuses a deleted stack's ID by its state, while a name, or an ID it never issued,
    // does not exist.
    private AwsException noStackToUpdate(String stackName, String region, String accountId) {
        Stack deleted = resolveDeletedStack(stackName, region, accountId);
        if (deleted != null) {
            return cannotBeUpdated(deleted.getStackId(), deleted.getStatus());
        }
        return new AwsException("ValidationError", "Stack with id " + stackName + " does not exist", 400);
    }

    /**
     * Whether an UPDATE has nothing to change: the template it submits comes to the same
     * {@link ComparedTemplate} as the stack's, with the values each is given, the stack's own tags
     * or none are sent, and the stack has no nested stack. CloudFormation then answers "No updates
     * are to be performed." A new Description, Metadata, Rules or an unused parameter, mapping or
     * condition is therefore not an update, while a change to an active output is, as on AWS.
     */
    private boolean submitsWhatTheStackHas(Stack stack, String templateBody, Map<String, String> parameters,
                                           Map<String, String> tags, String region) {
        if (stack.getTemplateBody() == null || templateBody == null) {
            return false;
        }
        // Tags are compared as a set. An update that sends none keeps the stack's, and so has
        // nothing to change; an empty set removes them, which is a change only for a tagged stack.
        if (tags != null && !tags.equals(stack.tagsSnapshot())) {
            return false;
        }
        JsonNode submittedTemplate = parsedOrNull(templateBody);
        JsonNode deployedTemplate = parsedOrNull(stack.getTemplateBody());
        if (submittedTemplate == null || deployedTemplate == null) {
            return false;
        }
        Map<String, String> given = parameters == null ? Map.of() : parameters;
        try {
            // Compared as execution stores it: AWS::Include fragments read again and merged, then
            // the SAM transform applied with the stack's parameter values, which it can resolve
            // into the expansion. A fragment that changed in S3 is a change.
            JsonNode template = awsIncludeProcessor.mergeIncludes(submittedTemplate);
            if (samTransformProcessor.hasSamTransform(template)) {
                template = samTransformProcessor.expandSamTemplate(template,
                        samTransformParameters(template, given, region));
            }
            // A nested stack is always updated, whether or not its template changed: AWS reads the
            // child template again and reports the nested stack as a change on every update.
            for (JsonNode resource : template.path("Resources")) {
                if ("AWS::CloudFormation::Stack".equals(resource.path("Type").asText())) {
                    return false;
                }
            }
            Map<String, String> submitted = resolveSsmParameters(template,
                    resolveDefaultParameters(template, given), region);
            return comparedDeployedTemplate(stack, deployedTemplate, region)
                    .equals(comparedTemplate(template, submitted, stack, region, currentDynamicReferences(stack, region)));
        } catch (AwsException e) {
            // An AWS::Include fragment, the SAM transform or an SSM parameter the update cannot
            // process fails the update itself, which reports it as AWS does: it goes ahead rather
            // than being refused as having nothing to change.
            LOG.debugv("Treating an update as a change; its template did not process: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * {@code templateBody} parsed, or {@code null} when it does not parse: as for
     * validateResourceDependencies, the operation that processes it reports the parser's error.
     */
    private JsonNode parsedOrNull(String templateBody) {
        try {
            return parseTemplate(templateBody);
        } catch (Exception e) {
            LOG.debugv("Not comparing a template that did not parse: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * The stack's deployed template as {@link ComparedTemplate} compares it, with the parameter
     * values and the dynamic reference values it was deployed with. A stack persisted before
     * resolved parameter values or dynamic reference digests were kept compares as different from
     * any update, which converges it and records them for the next time.
     */
    private ObjectNode comparedDeployedTemplate(Stack stack, JsonNode deployedTemplate, String region) {
        Map<String, String> digests = stack.dynamicReferenceDigestsSnapshot();
        return comparedTemplate(deployedTemplate, deployedParameters(stack), stack, region,
                value -> digests.getOrDefault(value, "unrecorded " + UUID.randomUUID()));
    }

    /** The parameter values the stack's template was deployed with. */
    private static Map<String, String> deployedParameters(Stack stack) {
        return stack.resolvedParametersSnapshot().isEmpty()
                ? stack.parametersSnapshot() : stack.resolvedParametersSnapshot();
    }

    /**
     * What each dynamic reference resolves to now, as the stack keeps it. An ssm-secure reference
     * resolves too, wherever it is, since only the digest is kept: AWS::RDS::DBInstance resolves its
     * master credentials with them.
     */
    private UnaryOperator<String> currentDynamicReferences(Stack stack, String region) {
        return value -> {
            try {
                return dynamicReferenceDigest(stack, dynamicReferences.resolveDynamicReferences(value, region, true));
            } catch (AwsException | IllegalStateException e) {
                // What CfnDynamicReferences cannot resolve fails the operation when it runs; until
                // then it is something to change.
                return "unresolvable " + UUID.randomUUID();
            }
        };
    }

    /**
     * Keeps, for the next update to compare against, what each dynamic reference in the template
     * the stack now has resolves to, keyed as {@link ComparedTemplate} hands them over. Run when an
     * operation commits, so one that fails and rolls back records nothing and the stack keeps what
     * its last deployment recorded; a reference the template no longer has is dropped.
     */
    private void recordDynamicReferenceDigests(Stack stack, JsonNode template, String region) {
        UnaryOperator<String> current = currentDynamicReferences(stack, region);
        Map<String, String> digests = new LinkedHashMap<>();
        try {
            comparedTemplate(template, deployedParameters(stack), stack, region, value -> {
                String digest = current.apply(value);
                digests.put(value, digest);
                return digest;
            });
        } catch (AwsException e) {
            // Nothing recorded compares as a change, which the next update converges and records.
            LOG.debugv("Recording no dynamic reference values for stack {0}: {1}", stack.getStackName(),
                    e.getMessage());
            digests.clear();
        }
        stack.replaceDynamicReferenceDigests(digests);
    }

    /**
     * What a dynamic reference resolved to, as the stack keeps it: an HMAC-SHA256 under a random
     * key of the stack's own, so no value is stored, and the same value on two stacks does not
     * compare equal.
     */
    private static String dynamicReferenceDigest(Stack stack, String resolved) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stack.dynamicReferenceKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(resolved.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private ObjectNode comparedTemplate(JsonNode template, Map<String, String> params, Stack stack,
                                        String region, UnaryOperator<String> dynamicReferenceValue) {
        String accountId = ownerAccount(stack);
        Map<String, Boolean> conditions = resolveConditions(template, params, stack, region, accountId);
        Map<String, JsonNode> mappings = new HashMap<>();
        template.path("Mappings").fields().forEachRemaining(e -> mappings.put(e.getKey(), e.getValue()));
        // No dynamic reference resolver: ComparedTemplate hands each reference to dynamicReferenceValue.
        CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(accountId, region,
                stack.getStackName(), stack.getStackId(), params, Map.of(), Map.of(), conditions, mappings,
                objectMapper, name -> null);
        return ComparedTemplate.of(template, objectMapper, engine, conditions, dynamicReferenceValue);
    }

    /**
     * Returns the change set's {@code StatusReason} when {@code templateBody} declares the SAM
     * transform and that transform fails, {@code null} when the transform is absent or succeeds.
     * The wrapping sentence mirrors real CloudFormation's own framing, measured against real AWS,
     * us-east-1: {@code "Transform AWS::Serverless-2016-10-31 failed with: Invalid Serverless
     * Application Specification document. Number of errors found: 1. "} followed by the
     * transform's own per-resource message. The count is always 1: {@code expandSamTemplate}
     * throws on the first bad resource rather than accumulating them.
     */
    private String samTransformFailureReason(String stackName, String templateBody, Map<String, String> parameters,
                                             String region) {
        JsonNode template;
        try {
            template = parseTemplate(templateBody);
        } catch (Exception e) {
            // Template parse failures are reported by validateResourceDependencies and by
            // execution itself, with their own messages; not this transform-specific one.
            return null;
        }
        if (!samTransformProcessor.hasSamTransform(template)) {
            return null;
        }
        JsonNode expanded;
        try {
            expanded = samTransformProcessor.expandSamTemplate(template, samTransformParameters(template, parameters, region));
        } catch (AwsException e) {
            return "Transform AWS::Serverless-2016-10-31 failed with: Invalid Serverless "
                    + "Application Specification document. Number of errors found: 1. "
                    + e.getMessage();
        } catch (Exception e) {
            // Anything other than the transform's own validation error (a ClassCastException or
            // NPE from inside expandSamTemplate) is a real bug, not a template-authoring mistake.
            // Swallowing it here would report the change set CREATE_COMPLETE while the same
            // exception resurfaces unlogged when the change set is later executed. Log it with the
            // stack name and let it fail loud instead.
            LOG.errorv("Stack {0} SAM transform preflight failed unexpectedly: {1}",
                    stackName, e.getMessage());
            throw e;
        }
        // The transform succeeded but produced a template CloudFormation rejects: AWS fails the
        // change set with the template error itself, not a transform failure.
        try {
            requireStringConditionReferencesIn(expanded);
        } catch (AwsException e) {
            return e.getMessage();
        }
        return null;
    }

    // ── DescribeChangeSet ─────────────────────────────────────────────────────

    public ChangeSet describeChangeSet(String stackName, String changeSetName, String region) {
        Stack stack = getStackForChangeSet(stackName, changeSetName, region, currentAccount());
        return getChangeSetOrThrow(stack, changeSetName, region, currentAccount());
    }

    /**
     * Computes the per-resource changes a change set would apply, by diffing its template against
     * the stack's currently deployed template. CREATE-type change sets (and stacks that have never
     * executed a template) report every resource with a truthy {@code Condition} as an Add.
     */
    public List<ResourceChange> computeChangeSetChanges(ChangeSet cs, String region) {
        if ("FAILED".equals(cs.getStatus())) {
            // A SAM transform failure already recorded by createChangeSet (see
            // samTransformFailureReason): the change set carries 0 changes, matching real
            // CloudFormation's own CreateChangeSet response for the same failure.
            return List.of();
        }
        Stack stack = getStackOrThrow(cs.getStackName(), region);
        String accountId = ownerAccount(stack);
        try {
            JsonNode newTemplate = parseTemplate(cs.getTemplateBody());
            // Merge Fn::Transform/AWS::Include snippets before SAM expansion, matching AWS order:
            // an included fragment may itself carry SAM resources.
            newTemplate = awsIncludeProcessor.mergeIncludes(newTemplate);
            if (samTransformProcessor.hasSamTransform(newTemplate)) {
                // The deployed stack's template is always the SAM-expanded form (see
                // executeTemplate); comparing the change set's raw SAM source against it would
                // report every SAM-generated resource as Add/Remove even on a no-op update.
                newTemplate = samTransformProcessor.expandSamTemplate(newTemplate,
                        samTransformParameters(newTemplate, cs.getParameters(), region));
            }
            JsonNode newResources = newTemplate.path("Resources");
            boolean createType = "CREATE".equalsIgnoreCase(cs.getChangeSetType())
                    || stack.getTemplateBody() == null;
            JsonNode oldTemplate = createType
                    ? null
                    : parseTemplate(stack.getTemplateBody());
            JsonNode oldResources = oldTemplate != null
                    ? oldTemplate.path("Resources")
                    : objectMapper.createObjectNode();

            Map<String, String> oldParams = stack.parametersSnapshot();
            // Prefer the SSM-resolved values captured by the last executeTemplate run; fall back to
            // the raw parameters for stacks persisted before resolvedParameters existed.
            Map<String, String> resolvedSnapshot = stack.resolvedParametersSnapshot();
            Map<String, String> oldResolvedParams = resolvedSnapshot.isEmpty() ? oldParams : resolvedSnapshot;
            // A parameter omitted from the update falls back to the template's Default when
            // ExecuteChangeSet actually runs it, so the preview must resolve the same defaults or
            // it will under-report changes to resources that depend on that fallback value.
            Map<String, String> newParams = resolveDefaultParameters(newTemplate,
                    cs.getParameters() != null ? cs.getParameters() : Map.of());
            // ExecuteChangeSet also resolves AWS::SSM::Parameter::Value<String> parameters against
            // the live Parameter Store before applying resource changes, and the stored SSM value
            // can drift between deploys even when the referencing parameter name is unchanged. Diff
            // on the resolved values, like execution does, so the preview agrees with what actually
            // gets applied. A preview must not fail harder than the operation it previews though: if
            // the referenced SSM parameter is missing, fall back to the unresolved values here and
            // let ExecuteChangeSet raise that ValidationError when it actually resolves them.
            Map<String, String> ssmResolvedNewParams;
            try {
                ssmResolvedNewParams = resolveSsmParameters(newTemplate, newParams, region);
            } catch (AwsException e) {
                ssmResolvedNewParams = newParams;
            }
            final Map<String, String> newResolvedParams = ssmResolvedNewParams;
            Set<String> changedParams = new HashSet<>();
            newResolvedParams.forEach((k, v) -> {
                if (!Objects.equals(v, oldResolvedParams.get(k))) {
                    changedParams.add(k);
                }
            });
            // A parameter that was deployed but that the new template no longer declares, and the
            // update does not pass, is absent from the new values, as it is when ExecuteChangeSet
            // runs; flag its disappearance too, not just a value change. One the template still
            // declares has a value or a Default: createChangeSet refuses the update otherwise.
            oldResolvedParams.keySet().forEach(k -> {
                if (!newResolvedParams.containsKey(k)) {
                    changedParams.add(k);
                }
            });

            // A resource whose Condition depends on a changed parameter can flip from excluded to
            // included (or back) even when its own definition text is unchanged; ExecuteChangeSet
            // applies that as an Add or Remove (see hasRemovedOrConditionFalseResources /
            // deleteRemovedOrConditionFalseResources), so the preview must evaluate Conditions too
            // rather than only scanning each resource's own Ref/Sub usage. A resource is "active" in
            // the deployed stack precisely when it's present in stack.getResources() - the same
            Map<String, Boolean> oldConditions = oldTemplate != null
                    ? resolveConditions(oldTemplate, oldResolvedParams, stack, region, regionResolver.getAccountId())
                    : Map.of();
            Map<String, Boolean> newConditions = resolveConditions(
                    newTemplate, newResolvedParams, stack, region, regionResolver.getAccountId());
            Set<String> changedConditions = new HashSet<>();
            newConditions.forEach((name, val) -> {
                if (!Objects.equals(val, oldConditions.get(name))) {
                    changedConditions.add(name);
                }
            });
            oldConditions.forEach((name, val) -> {
                if (!newConditions.containsKey(name)) {
                    changedConditions.add(name);
                }
            });
            Set<String> deployedIds = stack.resourcesSnapshot().keySet();

            // A resource is modified when what CloudFormation compares of it differs (see
            // ComparedTemplate): a DependsOn, or a literal rewritten as a function, is no change,
            // while a used mapping value or a dynamic reference whose stored value moved is. A
            // template that cannot be brought to that form falls back to its definitions as written
            // and the parameters and conditions they read.
            JsonNode[] compared = new JsonNode[2];
            if (oldTemplate != null) {
                try {
                    compared[0] = comparedDeployedTemplate(stack, oldTemplate, region).path("Resources");
                    compared[1] = comparedTemplate(newTemplate, newResolvedParams, stack, region,
                            currentDynamicReferences(stack, region)).path("Resources");
                } catch (AwsException e) {
                    // A condition or a mapping the comparison cannot resolve fails the execution
                    // with its own error; the preview diffs the definitions as written until then.
                    LOG.debugv("Diffing a change set as written; a template did not resolve: {0}", e.getMessage());
                    compared[0] = null;
                }
            }

            Set<String> replacedResourceIds = new HashSet<>();
            List<ResourceChange> changes = new ArrayList<>();
            newResources.fields().forEachRemaining(e -> {
                String logicalId = e.getKey();
                JsonNode newDef = e.getValue();
                String resourceType = newDef.path("Type").asText();
                JsonNode oldDef = oldResources.get(logicalId);
                String newConditionName = newDef.path("Condition").asText(null);
                boolean newActive = newConditionName == null
                        || newConditions.getOrDefault(newConditionName, false);
                if (oldDef == null) {
                    if (newActive) {
                        changes.add(new ResourceChange("Add", logicalId, null, resourceType, null));
                    }
                    return;
                }
                boolean wasDeployed = deployedIds.contains(logicalId);
                if (wasDeployed && !newActive) {
                    // Condition flipped true -> false: the definition text is unchanged, but
                    // ExecuteChangeSet deletes the resource (see deleteRemovedOrConditionFalseResources).
                    changes.add(new ResourceChange("Remove", logicalId,
                            resourcePhysicalId(stack, logicalId), oldDef.path("Type").asText(), null));
                } else if (!wasDeployed && newActive) {
                    // Condition flipped false -> true: never created before, ExecuteChangeSet creates
                    // it now.
                    changes.add(new ResourceChange("Add", logicalId, null, resourceType, null));
                } else if (newActive
                        && ((compared[0] != null
                                ? !compared[0].path(logicalId).equals(compared[1].path(logicalId))
                                : !oldDef.equals(newDef)
                                        || referencesAnyParameter(newDef, changedParams)
                                        || referencesAnyCondition(newDef, changedConditions))
                        || ("AWS::CloudFormation::Stack".equals(resourceType) && isNestedStackChanged(stack, logicalId, newDef, region, accountId)))) {
                    boolean typeChanged = !oldDef.path("Type").asText().equals(resourceType);
                    boolean replacement = typeChanged
                            || requiresReplacement(resourceType, oldDef.path("Properties"), newDef.path("Properties"), changedParams, changedConditions);
                    if (replacement) {
                        replacedResourceIds.add(logicalId);
                    }
                    changes.add(new ResourceChange("Modify", logicalId,
                            resourcePhysicalId(stack, logicalId), resourceType,
                            replacement ? "True" : "False"));
                }
            });
            oldResources.fields().forEachRemaining(e -> {
                if (!newResources.has(e.getKey())) {
                    changes.add(new ResourceChange("Remove", e.getKey(),
                            resourcePhysicalId(stack, e.getKey()),
                            e.getValue().path("Type").asText(), null));
                }
            });

            Set<String> alreadyChangedIds = changes.stream()
                    .map(ResourceChange::logicalResourceId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, Set<String>> inPlaceChangedAttributes = new HashMap<>();
            for (ResourceChange change : changes) {
                if ("Modify".equals(change.action()) && !replacedResourceIds.contains(change.logicalResourceId())) {
                    String logicalId = change.logicalResourceId();
                    JsonNode oldDef = oldResources.get(logicalId);
                    JsonNode newDef = newResources.get(logicalId);
                    String resourceType = newDef != null ? newDef.path("Type").asText() : "";
                    Set<String> attrs = expectedChangedAttributes(resourceType, oldDef, newDef);
                    if (!attrs.isEmpty()) {
                        inPlaceChangedAttributes.put(logicalId, attrs);
                    }
                }
            }

            if (!replacedResourceIds.isEmpty() || !inPlaceChangedAttributes.isEmpty()) {
                Set<String> allResourceIds = new LinkedHashSet<>();
                newResources.fieldNames().forEachRemaining(allResourceIds::add);

                List<String> sortedLogicalIds = topologicalSort(newResources, newConditions);
                boolean anyNewChange = true;
                while (anyNewChange) {
                    anyNewChange = false;
                    for (String logicalId : sortedLogicalIds) {
                        JsonNode newDef = newResources.get(logicalId);
                        String newConditionName = newDef.path("Condition").asText(null);
                        boolean newActive = newConditionName == null
                                || newConditions.getOrDefault(newConditionName, false);
                        if (!newActive || !deployedIds.contains(logicalId)) {
                            continue;
                        }
                        Set<String> propertyDependencies = new LinkedHashSet<>();
                        collectDependencies(newDef.path("Properties"), allResourceIds, propertyDependencies, newConditions);

                        boolean dependsOnReplaced = propertyDependencies.stream().anyMatch(replacedResourceIds::contains);
                        boolean referencesChangedAttr = false;
                        if (!dependsOnReplaced) {
                            for (Map.Entry<String, Set<String>> entry : inPlaceChangedAttributes.entrySet()) {
                                if (referencesAnyAttribute(newDef.path("Properties"), entry.getKey(), entry.getValue(), newConditions)) {
                                    referencesChangedAttr = true;
                                    break;
                                }
                            }
                        }

                        if (dependsOnReplaced || referencesChangedAttr) {
                            String resourceType = newDef.path("Type").asText();
                            JsonNode oldDef = oldResources.get(logicalId);
                            boolean typeChanged = oldDef != null && !oldDef.path("Type").asText().equals(resourceType);

                            boolean createOnlyReferencesReplaced = false;
                            JsonNode props = newDef.path("Properties");
                            if (props != null && props.isObject()) {
                                for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
                                    String field = it.next();
                                    if (CfnCreateOnlyProperties.isCreateOnly(resourceType, field)) {
                                        Set<String> fieldDeps = new LinkedHashSet<>();
                                        collectDependencies(props.get(field), allResourceIds, fieldDeps, newConditions);
                                        if (fieldDeps.stream().anyMatch(replacedResourceIds::contains)) {
                                            createOnlyReferencesReplaced = true;
                                            break;
                                        }
                                    }
                                }
                            }

                            boolean replacement = typeChanged
                                    || (oldDef != null && requiresReplacement(resourceType, oldDef.path("Properties"), newDef.path("Properties"), changedParams, changedConditions))
                                    || createOnlyReferencesReplaced;
                            if (replacement && replacedResourceIds.add(logicalId)) {
                                anyNewChange = true;
                            }
                            if (!alreadyChangedIds.contains(logicalId)) {
                                alreadyChangedIds.add(logicalId);
                                changes.add(new ResourceChange("Modify", logicalId,
                                        resourcePhysicalId(stack, logicalId), resourceType,
                                        replacement ? "True" : "False"));
                                anyNewChange = true;
                                Set<String> attrs = expectedChangedAttributes(resourceType, oldDef, newDef);
                                if (!attrs.isEmpty()) {
                                    inPlaceChangedAttributes.put(logicalId, attrs);
                                }
                            }
                        }
                    }
                }
            }
            return changes;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationError",
                    "Unable to compute changes for change set " + cs.getChangeSetName()
                            + ": " + e.getMessage(), 400);
        }
    }

    /** True if a resource's definition references any of the given (changed) parameter names. */
    private boolean referencesAnyParameter(JsonNode resourceDef, Set<String> parameterNames) {
        if (parameterNames.isEmpty()) {
            return false;
        }
        String json = resourceDef.toString();
        for (String name : parameterNames) {
            if (json.contains("\"Ref\":\"" + name + "\"") || json.contains("${" + name + "}")) {
                return true;
            }
        }
        return false;
    }

    /** True if a resource's definition references any of the given condition names. */
    private boolean referencesAnyCondition(JsonNode resourceDef, Set<String> conditionNames) {
        if (conditionNames.isEmpty()) {
            return false;
        }
        String json = resourceDef.toString();
        for (String name : conditionNames) {
            if (json.contains("\"Condition\":\"" + name + "\"")
                    || json.contains("\"Fn::If\":[\"" + name + "\"")
                    || json.contains("[\"" + name + "\"")) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresReplacement(String resourceType, JsonNode oldProps, JsonNode newProps,
                                        Set<String> changedParams, Set<String> changedConditions) {
        if (oldProps == null || newProps == null || oldProps.isMissingNode() || newProps.isMissingNode()) {
            return false;
        }
        for (Iterator<String> it = newProps.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (CfnCreateOnlyProperties.isCreateOnly(resourceType, field)) {
                JsonNode oldVal = oldProps.get(field);
                JsonNode newVal = newProps.get(field);
                if (oldVal != null && !oldVal.equals(newVal)) {
                    return true;
                }
                if (newVal != null && (referencesAnyParameter(newVal, changedParams) || referencesAnyCondition(newVal, changedConditions))) {
                    return true;
                }
            }
        }
        for (Iterator<String> it = oldProps.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (CfnCreateOnlyProperties.isCreateOnly(resourceType, field) && !newProps.has(field)) {
                return true;
            }
        }
        return false;
    }

    public record ResourceChange(String action, String logicalResourceId, String physicalResourceId,
                                 String resourceType, String replacement) {}

    private String resourcePhysicalId(Stack stack, String logicalId) {
        StackResource resource = stack.getResources().get(logicalId);
        return resource != null ? resource.getPhysicalId() : null;
    }

    // ── ExecuteChangeSet ──────────────────────────────────────────────────────

    public Future<?> executeChangeSet(String stackName, String changeSetName, String region) {
        return executeChangeSet(stackName, changeSetName, region, regionResolver.getAccountId());
    }

    /**
     * Entry point for the {@code ExecuteChangeSet} operation itself, as opposed to the execute that
     * {@code CreateStack}/{@code UpdateStack} run internally right after creating their own change
     * set.
     *
     * <p>Real CloudFormation refuses to execute a change set that is not {@code AVAILABLE}, for
     * example one a failed SAM transform already marked {@code FAILED}/{@code UNAVAILABLE}: it
     * throws {@code InvalidChangeSetStatus}, naming the change set's ARN and its current status,
     * and leaves the stack exactly where it was. Routing that check through a separate entry point
     * keeps {@code CreateStack}/{@code UpdateStack} able to reach {@code CREATE_FAILED} (or its
     * update equivalent) when their own change set failed - executing it unconditionally is how
     * that failure surfaces on those paths, and floci must still expose it there.
     */
    public Future<?> executeChangeSetForRequest(String stackName, String changeSetName, String region) {
        return claimAndSubmitExecution(stackName, changeSetName, region, regionResolver.getAccountId(), true);
    }

    /**
     * Executes a change set, provisioning its resources into {@code accountId}'s namespace.
     *
     * <p>Provisioning runs on a background executor thread that has no inherited request scope, so
     * the downstream service calls would otherwise fall back to the default account. The resources
     * are materialized under a synthetic request scope bound to {@code accountId} so a single-stack
     * deployment lands in the caller's account, and a StackSet instance lands in its target account.
     *
     * <p>Unlike {@link #executeChangeSetForRequest}, this does not require the change set to be
     * {@code AVAILABLE}: {@code CreateStack}/{@code UpdateStack} call this directly right after
     * creating their own change set, and must still reach {@code CREATE_FAILED} (or its update
     * equivalent) when that change set failed, for example from a failed SAM transform. It still
     * refuses one that is already executing or has already executed.
     */
    public Future<?> executeChangeSet(String stackName, String changeSetName, String region, String accountId) {
        return claimAndSubmitExecution(stackName, changeSetName, region, accountId, false);
    }

    private record ClaimedExecution(ChangeSet changeSet, boolean isCreate) {}

    private record ChangeSetState(String status, String executionStatus) {}

    private record StackMutationSnapshot(String status, Instant lastUpdatedTime,
                                         List<StackEvent> events,
                                         Map<String, ChangeSet> changeSets,
                                         Map<ChangeSet, ChangeSetState> changeSetStates) {}

    // compute() holds the stack's per-key lock for the whole claim, so only one racing execution can win.
    private Future<?> claimAndSubmitExecution(String stackNameOrArn, String changeSetName, String region,
                                              String accountId, boolean requireAvailable) {
        String canonicalStackName = getStackForChangeSet(stackNameOrArn, changeSetName, region,
                accountId).getStackName();

        ClaimedExecution[] claimed = new ClaimedExecution[1];
        StackMutationSnapshot[] snapshot = new StackMutationSnapshot[1];
        Stack stack = stacks.compute(stackKey(accountId, canonicalStackName, region), (k, existing) -> {
            if (existing == null) {
                throw new AwsException("ValidationError",
                        "Stack with id " + stackNameOrArn + " does not exist", 400);
            }
            ChangeSet cs = getChangeSetOrThrow(existing, changeSetName, region, accountId);
            if (isBeingDeleted(existing.getStatus())) {
                throw new AwsException("InvalidChangeSetStatus", "ChangeSet [" + cs.getChangeSetId()
                        + "] cannot be executed while its stack is in status [" + existing.getStatus() + "]", 400);
            }
            String executionStatus = cs.getExecutionStatus();
            boolean eligible = requireAvailable
                    ? "AVAILABLE".equals(executionStatus)
                    : executionStatus == null || !executionStatus.startsWith("EXECUTE_");
            if (!eligible) {
                throw invalidChangeSetStatus(cs);
            }
            snapshot[0] = snapshot(existing);
            boolean isCreate = "CREATE".equalsIgnoreCase(cs.getChangeSetType()) ||
                    "CREATE_IN_PROGRESS".equals(existing.getStatus());
            cs.setExecutionStatus("EXECUTE_IN_PROGRESS");
            if (requireAvailable) {
                // CloudFormation deletes every other change set on the stack once one of them executes.
                synchronized (existing.getChangeSets()) {
                    existing.getChangeSets().values().removeIf(other -> other != cs);
                }
            } else {
                for (ChangeSet other : existing.changeSetsSnapshot().values()) {
                    if (other != cs && "AVAILABLE".equals(other.getExecutionStatus())) {
                        other.setExecutionStatus("OBSOLETE");
                    }
                }
            }
            existing.setStatus(isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS");
            existing.setLastUpdatedTime(now());
            addEvent(existing, existing.getStackName(), existing.getStackId(),
                    "AWS::CloudFormation::Stack", isCreate ? "CREATE_IN_PROGRESS" : "UPDATE_IN_PROGRESS", null);
            claimed[0] = new ClaimedExecution(cs, isCreate);
            return existing;
        });
        try {
            persistStack(stack);
            return submitExecution(stack, claimed[0].changeSet(), claimed[0].isCreate(), region, accountId);
        } catch (AwsException e) {
            if ("LimitExceededException".equals(e.getErrorCode())) {
                Stack restored = restoreStack(accountId, canonicalStackName, region, snapshot[0]);
                if (restored != null) {
                    persistStack(restored);
                }
            }
            throw e;
        }
    }

    private AwsException invalidChangeSetStatus(ChangeSet cs) {
        String detail = "FAILED".equals(cs.getStatus())
                ? "status of [" + cs.getStatus() + "]"
                : "execution status of [" + cs.getExecutionStatus() + "]";
        return new AwsException("InvalidChangeSetStatus",
                "ChangeSet [" + cs.getChangeSetId() + "] cannot be executed in its current " + detail, 400);
    }

    private Future<?> submitExecution(Stack stack, ChangeSet cs, boolean isCreate, String region, String accountId) {
        String templateBody = cs.getTemplateBody();
        Map<String, String> params = cs.getParameters() != null ? cs.getParameters() : Map.of();

        return submitOperation(() -> RequestScopes.runAs(accountId, region, () -> {
            executeTemplate(stack, templateBody, params, cs.getTags(), isCreate, region, accountId);
            String status = stack.getStatus();
            cs.setExecutionStatus(status != null && (status.contains("ROLLBACK") || status.endsWith("_FAILED"))
                ? "EXECUTE_FAILED" : "EXECUTE_COMPLETE");
            persistStack(stack);
        }));
    }

    // ── DeleteChangeSet ───────────────────────────────────────────────────────

    public void deleteChangeSet(String stackName, String changeSetName, String region) {
        String accountId = currentAccount();
        boolean byArnAlone = (stackName == null || stackName.isBlank())
                && changeSetName != null && changeSetName.startsWith("arn:");
        Stack stack = byArnAlone
                ? findStackForChangeSetArn(changeSetName, region, accountId)
                : getStackForChangeSet(stackName, changeSetName, region, accountId);
        ChangeSet cs = stack == null ? null : findChangeSet(stack, changeSetName, region, accountId);
        if (cs == null) {
            // CloudFormation answers success for a change set the stack does not have, whatever
            // the stack's status, named by its stack and name or by its ARN alone (measured,
            // eu-west-1, 2026-10-04 and 2026-10-17). The AWS CDK deletes its
            // change set by name before every deploy and relies on it, since it deletes the one
            // that had nothing to change.
            return;
        }
        stack.getChangeSets().remove(cs.getChangeSetName());
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
        Stack stack = resolveStack(stackName, region, accountId);
        if (stack == null) {
            if (stackName != null && stackName.startsWith("arn:")) {
                try {
                    AwsArnUtils.Arn arn = AwsArnUtils.parse(stackName);
                    if (!accountId.equals(arn.accountId()) || !region.equals(arn.region())) {
                        throw new AwsException("ValidationError",
                                "Stack with id " + stackName + " does not exist", 400);
                    }
                } catch (IllegalArgumentException e) {
                    throw new AwsException("ValidationError",
                            "Stack with id " + stackName + " does not exist", 400);
                }
            }
            return CompletableFuture.completedFuture(null); // Already gone — no-op
        }
        // The check, the move to DELETE_IN_PROGRESS and the submission are one step under the
        // stack's lock. A retried or racing delete therefore finds either the stack as it was or a
        // deletion already running, and starts no second one; a stack that took this name since it
        // was resolved is a different stack, and is left alone.
        String stackId = stack.getStackId();
        Future<?>[] submitted = new Future<?>[1];
        stacks.compute(stackKey(accountId, stack.getStackName(), region), (k, existing) -> {
            if (existing == null || !existing.getStackId().equals(stackId)
                    || isBeingDeleted(existing.getStatus())) {
                return existing;
            }
            if (existing.isEnableTerminationProtection()) {
                // Real AWS rejects deletion of a protected stack and leaves it unchanged.
                throw new AwsException("ValidationError",
                        "Stack [" + existing.getStackId()
                                + "] cannot be deleted while TerminationProtection is enabled", 400);
            }
            StackMutationSnapshot snapshot = snapshot(existing);
            existing.setStatus("DELETE_IN_PROGRESS");
            addEvent(existing, existing.getStackName(), existing.getStackId(),
                    "AWS::CloudFormation::Stack", "DELETE_IN_PROGRESS", null);
            try {
                submitted[0] = submitOperation(() -> RequestScopes.runAs(accountId, region,
                        () -> deleteStackResources(existing, region, accountId)));
            } catch (AwsException e) {
                restore(existing, snapshot);
                throw e;
            }
            return existing;
        });
        return submitted[0] != null ? submitted[0] : CompletableFuture.completedFuture(null);
    }

    // ── ContinueUpdateRollback ────────────────────────────────────────────────

    /**
     * Retries the rollback of a stack in {@code UPDATE_ROLLBACK_FAILED}, the only status AWS accepts
     * it from; any other status is refused here, before anything changes, as is a malformed request
     * or a forced rollback that also names skips. The status check and the move to
     * {@code UPDATE_ROLLBACK_IN_PROGRESS} are one step under the stack's lock, so a racing update or
     * second retry is refused. Everything else, the {@code resourcesToSkip} checks against the stack
     * included, runs on the operation executor, because AWS accepts the call and reports a bad skip
     * as the reason the rollback failed again.
     */
    public Future<?> continueUpdateRollback(
            String stackNameOrArn, List<String> resourcesToSkip, boolean forceRollback, String region) {
        validateContinueUpdateRollback(stackNameOrArn, resourcesToSkip);
        if (forceRollback && !resourcesToSkip.isEmpty()) {
            throw new AwsException("ValidationError", FORCE_WITH_SKIPS, 400);
        }
        String accountId = currentAccount();
        Stack found = resolveStack(stackNameOrArn, region, accountId);
        if (found == null) {
            throw new AwsException("ValidationError", CONTINUE_STACK_MISSING.formatted(stackNameOrArn), 400);
        }
        String stackName = found.getStackName();
        List<String> skips = List.copyOf(resourcesToSkip);
        StackMutationSnapshot[] snapshot = new StackMutationSnapshot[1];
        Stack stack = stacks.compute(stackKey(accountId, stackName, region), (k, existing) -> {
            if (existing == null) {
                throw new AwsException("ValidationError", CONTINUE_STACK_MISSING.formatted(stackNameOrArn), 400);
            }
            if (!"UPDATE_ROLLBACK_FAILED".equals(existing.getStatus())) {
                throw new AwsException("ValidationError", CONTINUE_ROLLBACK_REFUSED, 400);
            }
            snapshot[0] = snapshot(existing);
            existing.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");
            addEvent(existing, existing.getStackName(), existing.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_IN_PROGRESS", "User Initiated");
            return existing;
        });
        try {
            persistStack(stack);
            return submitOperation(() -> RequestScopes.runAs(accountId, region,
                    () -> resumeUpdateRollback(stack, skips, forceRollback, region)));
        } catch (AwsException e) {
            if ("LimitExceededException".equals(e.getErrorCode())) {
                Stack restored = restoreStack(accountId, stackName, region, snapshot[0]);
                if (restored != null) {
                    persistStack(restored);
                }
            }
            throw e;
        }
    }

    /**
     * Refuses a malformed ContinueUpdateRollback request as AWS does, before the stack is looked up:
     * every constraint violation of {@code StackName} and {@code ResourcesToSkip} in one
     * {@code ValidationError}, with AWS's texts. A skip has no length limit.
     */
    private static void validateContinueUpdateRollback(String stackNameOrArn, List<String> resourcesToSkip) {
        List<String> violations = new ArrayList<>();
        if (stackNameOrArn == null) {
            violations.add(SmithyValidationErrors.notNull("stackName"));
        } else {
            if (stackNameOrArn.isEmpty()) {
                violations.add("Value '' at 'stackName' failed to satisfy constraint:"
                        + " Member must have length greater than or equal to 1");
            }
            if (!STACK_NAME.matcher(stackNameOrArn).matches()) {
                violations.add("Value '" + stackNameOrArn + "' at 'stackName' failed to satisfy constraint:"
                        + " Member must satisfy regular expression pattern: " + STACK_NAME.pattern());
            }
        }
        if (resourcesToSkip.stream().anyMatch(skip -> !RESOURCE_TO_SKIP.matcher(skip).matches())) {
            violations.add("Value '" + resourcesToSkip + "' at 'resourcesToSkip' failed to satisfy constraint:"
                    + " Member must satisfy constraint: [Member must satisfy regular expression pattern: "
                    + RESOURCE_TO_SKIP.pattern() + "]");
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    private StackMutationSnapshot snapshot(Stack stack) {
        Map<ChangeSet, ChangeSetState> states = new IdentityHashMap<>();
        for (ChangeSet changeSet : stack.changeSetsSnapshot().values()) {
            states.put(changeSet, new ChangeSetState(changeSet.getStatus(), changeSet.getExecutionStatus()));
        }
        return new StackMutationSnapshot(stack.getStatus(), stack.getLastUpdatedTime(),
                stack.eventsSnapshot(), stack.changeSetsSnapshot(), states);
    }

    private void restore(Stack stack, StackMutationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        stack.setStatus(snapshot.status());
        stack.setLastUpdatedTime(snapshot.lastUpdatedTime());
        stack.setEvents(new ArrayList<>(snapshot.events()));
        stack.setChangeSets(new LinkedHashMap<>(snapshot.changeSets()));
        snapshot.changeSetStates().forEach((changeSet, state) -> {
            changeSet.setStatus(state.status());
            changeSet.setExecutionStatus(state.executionStatus());
        });
    }

    private Stack restoreStack(String accountId, String stackName, String region,
                               StackMutationSnapshot snapshot) {
        return stacks.compute(stackKey(accountId, stackName, region), (key, current) -> {
            if (current != null) {
                restore(current, snapshot);
            }
            return current;
        });
    }

    private Future<?> submitOperation(Runnable operation) {
        try {
            return executor.submit(operation);
        } catch (RejectedExecutionException e) {
            throw operationLimitExceeded();
        }
    }

    static AwsException operationLimitExceeded() {
        return new AwsException("LimitExceededException",
                "Too many CloudFormation operations are in progress.", 400);
    }

    // ── GetTemplate ───────────────────────────────────────────────────────────

    private static final String STAGE_PROCESSED = "Processed";
    private static final String STAGE_ORIGINAL = "Original";

    // AWS's own enum order for the ValidationError message (measured against a real account).
    private static final List<String> TEMPLATE_STAGE_ENUM = List.of(STAGE_PROCESSED, STAGE_ORIGINAL);
    // AWS's own StagesAvailable order (Original first), which every GetTemplate call reports.
    private static final List<String> TEMPLATE_STAGES_AVAILABLE = List.of(STAGE_ORIGINAL, STAGE_PROCESSED);

    public String getTemplate(String stackName, String templateStage, String region) {
        // AWS validates TemplateStage before it looks the stack up (measured against a real
        // account: an invalid stage is rejected the same way whether or not the stack exists), so
        // this must run before getStackOrThrow.
        String stage = validateTemplateStage(templateStage);
        Stack stack = getStackOrThrow(stackName, region);
        // Processed is the SAM/AWS::Include-expanded form templateBody holds after executeTemplate.
        // Original (also the default, matching real AWS) is the template exactly as the caller
        // submitted it. originalTemplateBody is only absent for stacks persisted by a floci version
        // predating this field, hence the fallback to templateBody; getTemplateSummary reads the
        // same field for the same reason.
        String body = STAGE_PROCESSED.equals(stage) ? stack.getTemplateBody() : stack.getOriginalTemplateBody();
        if (body == null) {
            body = stack.getTemplateBody();
        }
        return body != null ? body : "{}";
    }

    private String validateTemplateStage(String templateStage) {
        // null means the caller omitted TemplateStage; "" means the caller sent it present and
        // empty. AWS rejects the latter (measured against a real account) and only defaults the
        // former, so this must not treat blank the same as absent.
        if (templateStage == null) {
            return STAGE_ORIGINAL;
        }
        if (!TEMPLATE_STAGE_ENUM.contains(templateStage)) {
            throw SmithyValidationErrors.validationError(
                    "Value '" + templateStage + "' at 'templateStage' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: ["
                            + String.join(", ", TEMPLATE_STAGE_ENUM) + "]");
        }
        return templateStage;
    }

    public List<String> templateStagesAvailable() {
        return TEMPLATE_STAGES_AVAILABLE;
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
        return buildTemplateSummary(readTemplate(resolvedBody));
    }

    /** Each parameter the template declares, with its {@link #parameterType}. */
    private static Map<String, String> parameterTypes(JsonNode template) {
        Map<String, String> types = new HashMap<>();
        template.path("Parameters").properties()
                .forEach(entry -> types.put(entry.getKey(), parameterType(entry.getValue())));
        return types;
    }

    /** A parameter declaration's {@code Type}, {@code String} when the template leaves it out. */
    private static String parameterType(JsonNode declaration) {
        return declaration.hasNonNull("Type") ? declaration.get("Type").asText() : "String";
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
                        parameterType(p)));
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
        // AWS reports AWS::Include in DeclaredTransforms for the embedded Fn::Transform form too,
        // even with no top-level Transform section (measured against us-east-1).
        if (!declaredTransforms.contains(AwsIncludeProcessor.AWS_INCLUDE)
                && awsIncludeProcessor.containsAwsInclude(template)) {
            declaredTransforms.add(AwsIncludeProcessor.AWS_INCLUDE);
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

    /**
     * The missing-stack message is the one AWS uses for this operation, {@code Stack [name] does
     * not exist}, which differs from DescribeStacks' {@code Stack with id name does not exist}.
     * Clients tell the two apart by text: the CDK's stack-event poller swallows exactly the
     * bracketed form once a stack it is watching has been deleted (its stack lookup swallows the
     * other), so any other wording escapes the poller and fails {@code cdk destroy --all} after
     * the first stack.
     */
    public List<StackEvent> describeStackEvents(String stackName, String region) {
        Stack stack = resolveStackForDescribe(stackName, region);
        if (stack == null) {
            throw new AwsException("ValidationError",
                    "Stack [" + stackName + "] does not exist", 400);
        }
        List<StackEvent> events = stack.eventsSnapshot();
        Collections.reverse(events);
        return events;
    }

    // ── DescribeStackResources ────────────────────────────────────────────────

    public List<StackResource> describeStackResources(String stackName, String region) {
        return describeStackResources(stackName, null, null, region);
    }

    /**
     * The stack's resources, narrowed by the two filters {@code DescribeStackResources} accepts.
     *
     * <p>AWS takes either {@code StackName} or {@code PhysicalResourceId}, never both: given a
     * physical id it answers with the whole stack that resource belongs to, rather than that one
     * resource. {@code LogicalResourceId} is only a filter on the result, so naming one the stack
     * does not have is an empty answer; {@code DescribeStackResource}, the singular action where
     * the resource is required rather than optional, is the one that raises for a miss.
     *
     * <p>Both filters being silently ignored is worse than it sounds for a caller that filters and
     * then reads the first member: it gets whichever resource the stack happens to list first.
     * Serverless Framework resolves an AppSync API id exactly that way.
     */
    public List<StackResource> describeStackResources(String stackName, String logicalResourceId,
                                                      String physicalResourceId, String region) {
        return describeStackResourcesView(stackName, logicalResourceId, physicalResourceId, region).resources();
    }

    /** The stack a call resolved to and the resources it answered with; the stack's own name is
     * what AWS stamps on every member, and the caller may not have supplied it. */
    public record StackResourcesView(String stackName, List<StackResource> resources) {
    }

    /** @see #describeStackResources(String, String, String, String) */
    public StackResourcesView describeStackResourcesView(String stackName, String logicalResourceId,
                                                         String physicalResourceId, String region) {
        boolean hasStackName = stackName != null && !stackName.isBlank();
        boolean hasPhysicalId = physicalResourceId != null && !physicalResourceId.isBlank();
        if (!hasStackName && !hasPhysicalId) {
            throw new AwsException("ValidationError",
                    "Either StackName or PhysicalResourceId must be specified.", 400);
        }
        if (hasStackName && hasPhysicalId) {
            throw new AwsException("ValidationError",
                    "Cannot specify both StackName and PhysicalResourceId.", 400);
        }

        // A physical id names the stack, it does not narrow it: AWS returns "the associated
        // resources of the stack that the resource belongs to", which is the whole stack.
        Stack stack = hasStackName
                ? getStackOrThrow(stackName, region)
                : stackContaining(physicalResourceId, region);
        List<StackResource> resources = new ArrayList<>(stack.resourcesSnapshot().values());

        // Only a filter on the result. The reference documents no error for a logical id the
        // stack does not have, and DescribeStackResource, the singular action where the resource
        // is required rather than optional, is the one that raises for a miss.
        if (logicalResourceId != null && !logicalResourceId.isBlank()) {
            resources.removeIf(r -> !logicalResourceId.equals(r.getLogicalId()));
        }
        return new StackResourcesView(stack.getStackName(), resources);
    }

    /**
     * The stack owning a physical id, for the {@code PhysicalResourceId}-only form of the call.
     *
     * <p>Live stacks are considered before deleted ones, newest first within each group.
     * {@code listStacks} answers oldest first and keeps deleted stacks for their retention window,
     * so walking it directly resolves a redeployed resource to the stack that used to own it.
     */
    private Stack stackContaining(String physicalResourceId, String region) {
        List<Stack> candidates = new ArrayList<>(listStacks(region));
        candidates.sort(Comparator
                .comparing((Stack s) -> "DELETE_COMPLETE".equals(s.getStatus()))
                .thenComparing(Stack::getCreationTime, Comparator.reverseOrder()));
        for (Stack stack : candidates) {
            boolean owns = stack.resourcesSnapshot().values().stream()
                    .anyMatch(r -> physicalResourceId.equals(r.getPhysicalId()));
            if (owns) {
                return stack;
            }
        }
        throw new AwsException("ValidationError",
                "Stack for " + physicalResourceId + " does not exist", 400);
    }

    // ── ListStacks ────────────────────────────────────────────────────────────

    /**
     * Summaries for the region's stacks, the ones still within the deleted-stack retention window
     * included. AWS keeps a deleted stack in ListStacks as {@code DELETE_COMPLETE} long after
     * DescribeStacks has stopped answering for it by name, which is how a client reconciles what
     * it deployed against what is left. A name reused after a delete lists twice, once per stack
     * id, as it does on AWS.
     *
     * <p>Reduced by stack id, because a stack being deleted is briefly in both maps: it is
     * retained before it is removed from the live one, so that it is never in neither.
     *
     * <p>The maps are read one after the other, the live one first and in full, rather than as one
     * concatenated pipeline. A delete retains before it removes, so reading in that order leaves a
     * stack mid-handover in at least one of the two views. Building both views up front breaks
     * that: a {@link ConcurrentHashMap} view reflects the table at some point at or since its own
     * creation and is not guaranteed to reflect a modification made after it, so a retained view
     * created before the retain need never show the stack, while a live view created after it can
     * already miss it. Traversing the retained view second does not rescue this, because it is
     * creation and not traversal that bounds the guarantee.
     */
    public List<Stack> listStacks(String region) {
        String accountId = currentAccount();
        Instant current = now();
        Map<String, Stack> byStackId = new LinkedHashMap<>();
        stacks.values().stream()
                .filter(s -> accountId.equals(ownerAccount(s)) && region.equals(s.getRegion()))
                .forEach(s -> byStackId.putIfAbsent(s.getStackId(), s));
        deletedStacks.values().stream()
                .filter(entry -> !entry.isExpired(current))
                .map(DeletedStackEntry::stack)
                .filter(s -> accountId.equals(ownerAccount(s)) && region.equals(s.getRegion()))
                .forEach(s -> byStackId.putIfAbsent(s.getStackId(), s));
        return byStackId.values().stream()
                .sorted(Comparator.comparing(Stack::getCreationTime))
                .toList();
    }

    // ── ListExports ─────────────────────────────────────────────────────────

    public Map<String, ExportEntry> listExports(String region) {
        Map<String, ExportEntry> result = new LinkedHashMap<>();
        String accountId = currentAccount();
        for (Stack stack : stacks.values()) {
            if (!accountId.equals(ownerAccount(stack)) || !region.equals(stack.getRegion())) {
                continue;
            }
            for (var entry : stack.exportsSnapshot().entrySet()) {
                result.put(entry.getKey(), new ExportEntry(entry.getKey(), entry.getValue(), stack.getStackId()));
            }
        }
        return result;
    }

    public record ExportEntry(String name, String value, String exportingStackId) {}

    // ── Private ───────────────────────────────────────────────────────────────

    private void removeStackExports(Stack stack, String region) {
        String accountId = ownerAccount(stack);
        for (String exportName : stack.exportsSnapshot().keySet()) {
            String logicalKey = exportKey(region, exportName);
            exports.remove(accountExportKey(accountId, logicalKey));
            exportBackend.deleteForAccount(accountId, logicalKey);
        }
    }

    private String exportKey(String region, String exportName) {
        return region + ":" + exportName;
    }
    private String accountExportKey(String accountId, String logicalKey) {
        return accountId + ":" + logicalKey;
    }


    private void validateExportNameAvailable(String region, String exportName,
                                             Map<String, String> oldExports,
                                             Map<String, String> newExports) {
        if (newExports.containsKey(exportName)) {
            throw new AwsException("ValidationError",
                    "Export with name " + exportName + " is already defined by this stack", 400);
        }
        if (!oldExports.containsKey(exportName) && exports.containsKey(accountExportKey(currentAccount(), region + ":" + exportName))) {
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

    /**
     * The parameter values the SAM transform sees: the given ones over each {@code Default}, with
     * {@code AWS::SSM::Parameter::Value<String>} parameters already read from Parameter Store, as
     * CloudFormation resolves them before it runs the transform. A parameter that cannot be fetched
     * leaves the values unresolved here; the execution path reports that failure with its own error.
     */
    private Map<String, String> samTransformParameters(JsonNode template, Map<String, String> params, String region) {
        Map<String, String> given = resolveDefaultParameters(template, params);
        try {
            return resolveSsmParameters(template, given, region);
        } catch (AwsException e) {
            LOG.debugv("SAM transform sees unresolved parameters: {0}", e.getMessage());
            return given;
        }
    }

    /**
     * Substitutes {@code AWS::SSM::Parameter::Value<String>}-typed parameter values — which carry
     * an SSM parameter <em>name</em> — with the value stored in Parameter Store for the stack's
     * account and region, as real CloudFormation does before template processing. Missing
     * parameters fail the stack operation with the real AWS ValidationError. The related types
     * {@code AWS::SSM::Parameter::Value<List<String>>} and {@code AWS::SSM::Parameter::Name} are
     * not resolved and pass through verbatim.
     */
    private Map<String, String> resolveSsmParameters(JsonNode template, Map<String, String> params, String region) {
        JsonNode paramDefs = template.path("Parameters");
        if (!paramDefs.isObject()) {
            return params;
        }
        Map<String, String> resolved = new HashMap<>(params);
        List<String> missing = new ArrayList<>();
        paramDefs.fields().forEachRemaining(e -> {
            if (!"AWS::SSM::Parameter::Value<String>".equals(e.getValue().path("Type").asText())) {
                return;
            }
            String parameterName = resolved.get(e.getKey());
            if (parameterName == null || parameterName.isBlank()) {
                return;
            }
            try {
                // A plaintext type: a Secrets Manager reference needs decryption, so it is refused.
                resolved.put(e.getKey(), ssmService.getParameter(parameterName, false, region).getValue());
            } catch (AwsException ex) {
                missing.add(parameterName);
            }
        });
        if (!missing.isEmpty()) {
            throw new AwsException("ValidationError",
                    "Unable to fetch parameters [" + String.join(",", missing)
                            + "] from parameter store for this account", 400);
        }
        return resolved;
    }

    private void executeTemplate(Stack stack, String templateBody, Map<String, String> params,
                                 Map<String, String> tags,
                                 boolean isCreate, String region, String accountId) {
        StackUpdateSnapshot previousState = snapshotForUpdate(stack);
        boolean updateCommitted = false;
        Set<String> attemptedResourceIds = new LinkedHashSet<>();
        try {
            Set<String> changedResourceIds = isCreate
                    ? Set.of()
                    : changedResourceIds(stack, templateBody, params, region);
            JsonNode previousResources = isCreate
                    ? objectMapper.missingNode()
                    : previousResources(stack, previousState, region, accountId);
            JsonNode template = parseTemplate(templateBody);
            stack.setOriginalTemplateBody(templateBody);

            // Merge Fn::Transform/AWS::Include snippets before SAM expansion, matching AWS order:
            // an included fragment may itself carry SAM resources. mergeIncludes returns the same
            // reference, unchanged, when the template carries no AWS::Include, which is what lets
            // the check below tell a real merge apart from a no-op one.
            JsonNode beforeInclude = template;
            template = awsIncludeProcessor.mergeIncludes(template);
            boolean includeMerged = template != beforeInclude;

            // Apply SAM transform if the template declares AWS::Serverless-2016-10-31
            boolean hasSamTransform = samTransformProcessor.hasSamTransform(template);
            if (hasSamTransform) {
                LOG.infov("Applying SAM transform for stack {0}", stack.getStackName());
                template = samTransformProcessor.expandSamTemplate(template, samTransformParameters(template, params, region));
            }

            // Persist the merged/expanded tree, not the raw submitted body, whenever either
            // processor actually changed it, so the change-set baseline diffs against it instead of
            // against a stale Fn::Transform node. A template neither processor touched keeps its
            // submitted body byte for byte.
            // A template CloudFormation rejects outright fails before the stack takes it on, as the
            // synchronous check does for a template no transform touched.
            requireStringConditionReferencesIn(template);
            if (includeMerged || hasSamTransform) {
                templateBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(template);
            }
            stack.setTemplateBody(templateBody);

            // Merge default parameter values from the template with caller-supplied params
            Map<String, String> givenParams = resolveDefaultParameters(template, params);
            stack.replaceParameters(givenParams);
            Map<String, String> resolvedParams = resolveSsmParameters(template, givenParams, region);
            Map<String, String> parameterTypes = parameterTypes(template);
            stack.setResolvedParameters(new LinkedHashMap<>(resolvedParams));

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
            for (StackResource r : stack.resourcesSnapshot().values()) {
                if (r.getPhysicalId() != null) {
                    physicalIds.put(r.getLogicalId(), r.getPhysicalId());
                    resourceAttrs.put(r.getLogicalId(), r.getAttributes());
                }
            }

            StackResource failedResource = null;
            if (resources.isObject()) {
                List<String> sortedLogicalIds = topologicalSort(resources, conditions);
                Set<String> templateIds = new LinkedHashSet<>();
                resources.fieldNames().forEachRemaining(templateIds::add);

                if (!isCreate) {
                    for (String logicalId : sortedLogicalIds) {
                        StackResource existing = stack.getResources().get(logicalId);
                        if (existing != null && dispatcher.requiresUpdateRecovery(existing)) {
                            changedResourceIds.add(logicalId);
                        }
                    }
                }

                for (String logicalId : sortedLogicalIds) {
                    if (!isCreate && !changedResourceIds.contains(logicalId)) {
                        continue;
                    }
                    JsonNode resDef = resources.get(logicalId);
                    String type = resDef.path("Type").asText();
                    String deletionPolicy = resDef.path("DeletionPolicy").asText(null);
                    JsonNode props = resDef.path("Properties");

                    CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                            accountId, region, stack.getStackName(),
                            stack.getStackId(), resolvedParams, physicalIds, resourceAttrs, conditions, mappings, objectMapper,
                            name -> exports.get(accountExportKey(accountId, exportKey(region, name))),
                            value -> dynamicReferences.resolveDynamicReferences(value, region, false),
                            (name, output, outputRegion, roleArn) ->
                                    resolveStackOutput(accountId, name, output, outputRegion, roleArn),
                            parameterTypes,
                            (id, attribute, value) -> dispatcher.attributeValue(
                                    resources.path(id).path("Type").asText(), attribute, value));

                    StackResource resource = stack.getResources().get(logicalId);
                    StackResource previousResource = resource;
                    String priorId = previousResource != null ? previousResource.getPhysicalId() : null;
                    Map<String, String> priorAttrs = new HashMap<>();
                    if (previousResource != null && previousResource.getAttributes() != null) {
                        for (Map.Entry<String, String> entry : previousResource.getAttributes().entrySet()) {
                            if (entry.getValue() != null) {
                                priorAttrs.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                    if (resource == null) {
                        resource = new StackResource();
                        resource.setLogicalId(logicalId);
                        resource.setResourceType(type);
                        stack.getResources().put(logicalId, resource);
                    }

                    // A logical id the stack did not hold is created, even on an update, as AWS
                    // reports it; that includes a resource whose condition just turned true.
                    boolean creating = isCreate || previousResource == null;
                    String inProgressStatus = creating
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
                                engine, region, accountId, isCreate, previousResource);
                    } else {
                        JsonNode previousDefinition = previousResources.path(logicalId);
                        JsonNode priorProps = type.equals(previousDefinition.path("Type").asText())
                                && previousDefinition.path("Properties").isObject()
                                ? previousDefinition.path("Properties") : null;
                        resource = dispatcher.provision(logicalId, type, props.isMissingNode() ? null : props,
                                engine, region, accountId, stack.getStackName(),
                                resource.getPhysicalId(), resource.getAttributes(),
                                event -> addEvent(stack, logicalId, event.getPhysicalResourceId(), type,
                                        event.getResourceStatus(), event.getResourceStatusReason()),
                                priorProps);
                    }
                    resource.setUpdateReplacePolicy(
                            resDef.path("UpdateReplacePolicy").asText(null));
                    if (!creating) {
                        if ("CREATE_COMPLETE".equals(resource.getStatus())) {
                            resource.setStatus("UPDATE_COMPLETE");
                        } else if ("CREATE_FAILED".equals(resource.getStatus())) {
                            resource.setStatus("UPDATE_FAILED");
                        }
                    }
                    // Both branches return a fresh StackResource, so the policy is carried over here
                    // rather than on the instance the loop started with.
                    resource.setDeletionPolicy(deletionPolicy);
                    if ("AWS::CloudFormation::Stack".equals(type) || dispatcher.isCustomResourceType(type)) {
                        recordTemplateUses(resource, collectResourceDependencies(resDef, templateIds, conditions),
                                physicalIds);
                    }
                    stack.getResources().put(logicalId, resource);

                    physicalIds.put(logicalId, resource.getPhysicalId());
                    resourceAttrs.put(logicalId, resource.getAttributes());

                    if (!isCreate) {
                        boolean replaced = dispatcher.hasReplacementUpdate(resource)
                                || (priorId != null && !priorId.equals(resource.getPhysicalId()));
                        if (replaced) {
                            for (String candidateId : sortedLogicalIds) {
                                if (!changedResourceIds.contains(candidateId)) {
                                    Set<String> deps = new LinkedHashSet<>();
                                    collectDependencies(resources.path(candidateId).path("Properties"),
                                            templateIds, deps, conditions);
                                    if (deps.contains(logicalId)) {
                                        changedResourceIds.add(candidateId);
                                    }
                                }
                            }
                        } else {
                            // In-place updates can still change attributes (e.g. LatestVersionNumber of LaunchTemplate)
                            Map<String, String> newAttrs = resource.getAttributes();
                            Set<String> changedAttrNames = new HashSet<>();
                            if (newAttrs != null) {
                                for (Map.Entry<String, String> entry : newAttrs.entrySet()) {
                                    if (!Objects.equals(entry.getValue(), priorAttrs.get(entry.getKey()))) {
                                        changedAttrNames.add(entry.getKey());
                                    }
                                }
                            }
                            for (String priorKey : priorAttrs.keySet()) {
                                if (newAttrs == null || !newAttrs.containsKey(priorKey)) {
                                    changedAttrNames.add(priorKey);
                                }
                            }
                            if (!changedAttrNames.isEmpty()) {
                                for (String candidateId : sortedLogicalIds) {
                                    if (!changedResourceIds.contains(candidateId)) {
                                        if (referencesAnyAttribute(resources.path(candidateId).path("Properties"),
                                                logicalId, changedAttrNames, conditions)) {
                                            changedResourceIds.add(candidateId);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    addEvent(stack, logicalId, resource.getPhysicalId(), type,
                            resource.getStatus(), resource.getStatusReason());

                    if ("CREATE_FAILED".equals(resource.getStatus())
                            || "UPDATE_FAILED".equals(resource.getStatus())) {
                        failedResource = resource;
                        // A provisioner that keeps the failed attempt's identity and tracking for
                        // its own rollback is not restored here; the rollback walker owns it.
                        if (!isCreate && previousResource != null
                                && !dispatcher.retainsFailedUpdateState(resource)) {
                            // Provisioners work on a copy of the stored resource metadata. Keep the
                            // last known-good identity and status when an update attempt fails so a
                            // later retry or stack deletion still manages the original resource.
                            // Preserve any additional resources that the failed attempt could not
                            // clean up, otherwise restoring this object would orphan them.
                            dispatcher.mergeFailedUpdateResourceTracking(previousResource, resource);
                            String rollbackFailure = resource.getAttributes().get(
                                    CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                            if (rollbackFailure == null) {
                                // The rollback walker must know this resource is already restored;
                                // otherwise an earlier UPDATE_COMPLETE status looks like an
                                // unhandled mutation and incorrectly becomes ROLLBACK_FAILED.
                                previousResource.getAttributes().put(
                                        CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR,
                                        "true");
                            } else {
                                // Restoration was attempted eagerly by the provisioner but did not
                                // complete. Carry that failure onto the committed resource so the
                                // rollback walker reports UPDATE_ROLLBACK_FAILED rather than claiming
                                // the stale snapshot is live.
                                previousResource.getAttributes().put(
                                        CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                                        rollbackFailure);
                            }
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
                    name -> exports.get(accountExportKey(accountId, exportKey(region, name))),
                    value -> dynamicReferences.resolveDynamicReferences(value, region, false),
                    (name, output, outputRegion, roleArn) ->
                            resolveStackOutput(accountId, name, output, outputRegion, roleArn),
                    parameterTypes,
                    (id, attribute, value) -> dispatcher.attributeValue(
                            resources.path(id).path("Type").asText(), attribute, value));

            // Resolve outputs before mutating stack/global export state, so failed updates do not
            // leave stale or partially registered exports behind.
            Map<String, String> oldExports = stack.exportsSnapshot();
            Map<String, String> newOutputs = new LinkedHashMap<>();
            Map<String, String> newExports = new LinkedHashMap<>();
            Map<String, String> newOutputExportNames = new LinkedHashMap<>();
            // An output whose Condition is false is left out unresolved, as on AWS: DescribeStacks
            // does not list it, and what it reads is never read (measured, eu-west-1, 2026-10-16).
            Map<String, JsonNode> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> output : template.path("Outputs").properties()) {
                String condition = output.getValue().path("Condition").asText(null);
                if (condition == null || conditions.getOrDefault(condition, false)) {
                    outputs.put(output.getKey(), output.getValue());
                }
            }
            Map<String, String> outputValues = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> output : outputs.entrySet()) {
                try {
                    outputValues.put(output.getKey(), finalEngine.resolveString(output.getValue().path("Value"),
                            "Template format error: Every Value member must be a string."));
                } catch (AwsException e) {
                    LOG.warnv("Stack {0} output {1} failed: {2}", stack.getStackName(), output.getKey(),
                            e.getMessage());
                    rollbackFailedExecution(stack, region, isCreate, e.getMessage(), previousState,
                            attemptedResourceIds);
                    return;
                }
            }
            outputs.forEach((key, outputDef) -> {
                String value = outputValues.get(key);
                newOutputs.put(key, value);

                // Register exports
                JsonNode exportNode = outputDef.path("Export").path("Name");
                if (!exportNode.isMissingNode()) {
                    String exportName = finalEngine.resolve(exportNode);
                    validateExportNameAvailable(region, exportName, oldExports, newExports);
                    newExports.put(exportName, value);
                    newOutputExportNames.put(key, exportName);
                }
            });

            removeStackExports(stack, region);
            stack.replaceOutputs(newOutputs);
            stack.replaceExports(newExports);
            stack.replaceOutputExportNames(newOutputExportNames);
            recordDynamicReferenceDigests(stack, template, region);
            newExports.forEach((exportName, value) -> {
                String logicalKey = region + ":" + exportName;
                String exportKey = accountExportKey(accountId, logicalKey);
                exports.put(exportKey, value);
                exportBackend.putForAccount(accountId, logicalKey, value);
                LOG.infov("Registered export {0} = {1} from stack {2}",
                        exportName, value, stack.getStackName());
            });

            if (!isCreate) {
                updateCommitted = true;
                refreshTemplateUses(stack, resources, conditions, physicalIds);
                if (tags != null) {
                    stack.replaceTags(tags);
                }
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
                // Both cleanup paths feed the single final status/reason writer. A removed resource
                // whose delete failed is tried once more after the replacement cleanup, as an entity
                // a replacement displaced can still use it: an old load balancer on a subnet the same
                // update removed.
                List<StackResource> retried = deleteRemovedOrConditionFalseResources(
                        stack, resources, conditions, region);
                List<UpdateCleanupFailure> displacedFailures = finishCommittedResourceCleanup(stack, region);
                List<UpdateCleanupFailure> cleanupFailures = new ArrayList<>();
                for (StackResource resource : retried) {
                    UpdateCleanupFailure failure = deleteRemovedResource(stack, resource, region, true);
                    if (failure != null) {
                        cleanupFailures.add(failure);
                    }
                }
                cleanupFailures.addAll(displacedFailures);
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
            if (isCreate) {
                stack.setStatusReason(e.getMessage());
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "CREATE_FAILED", e.getMessage());
                stack.setStatus("CREATE_FAILED");
                persistStack(stack);
            } else {
                rollbackFailedUpdate(
                        stack, region, previousState, attemptedResourceIds, e.getMessage());
            }
        }
    }

    private Set<String> changedResourceIds(Stack stack, String templateBody, Map<String, String> params,
                                           String region) {
        ChangeSet changeSet = new ChangeSet();
        changeSet.setStackName(stack.getStackName());
        changeSet.setStackId(stack.getStackId());
        changeSet.setChangeSetType("UPDATE");
        changeSet.setTemplateBody(templateBody);
        changeSet.setParameters(params);

        Set<String> changedResourceIds = new LinkedHashSet<>();
        for (ResourceChange change : computeChangeSetChanges(changeSet, region)) {
            if ("Add".equals(change.action()) || "Modify".equals(change.action())) {
                changedResourceIds.add(change.logicalResourceId());
            }
        }
        return changedResourceIds;
    }


    private boolean isNestedStackChanged(Stack parentStack, String logicalId, JsonNode newDef,
                                         String region, String accountId) {
        if (parentStack == null) {
            return false;
        }
        StackResource existingResource = parentStack.getResources().get(logicalId);
        if (existingResource == null || existingResource.getPhysicalId() == null) {
            return true;
        }
        Stack childStack = resolveStack(existingResource.getPhysicalId(), region, accountId);
        if (childStack == null) {
            return true;
        }
        String templateUrl = newDef.path("Properties").path("TemplateURL").asText(null);
        if (templateUrl == null || templateUrl.isBlank()) {
            return false;
        }
        try {
            String newChildTemplate = fetchTemplateFromS3(templateUrl);
            String currentChildTemplate = childStack.getOriginalTemplateBody() != null
                    ? childStack.getOriginalTemplateBody()
                    : childStack.getTemplateBody();
            return newChildTemplate != null && !newChildTemplate.equals(currentChildTemplate);
        } catch (Exception ignored) {
            // Safe to ignore: if TemplateURL is inaccessible and parent definition didn't change,
            // treat the nested stack as unchanged so the parent update is not blocked.
            return false;
        }
    }

    /**
     * Handles a resource that failed to provision.
     *
     * <p>On a <b>create</b>, rolls back by deleting resources created by the failed execution. On
     * an <b>update</b>, restores the prior resource, template, output, and export state.
     */
    void rollbackFailedExecution(
            Stack stack,
            String region,
            boolean isCreate,
            StackResource failedResource,
            StackUpdateSnapshot previousState,
            Set<String> attemptedResourceIds) {
        LOG.warnv("Stack {0} resource {1} failed: {2}", stack.getStackName(),
                failedResource.getLogicalId(), failedResource.getStatusReason());
        rollbackFailedExecution(stack, region, isCreate, rollbackReason(isCreate, failedResource), previousState,
                attemptedResourceIds);
    }

    /** As above, for a failure that is not one resource's: the reason is the stack's. */
    private void rollbackFailedExecution(
            Stack stack,
            String region,
            boolean isCreate,
            String reason,
            StackUpdateSnapshot previousState,
            Set<String> attemptedResourceIds) {
        stack.setStatusReason(reason);

        if (isCreate) {
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "ROLLBACK_IN_PROGRESS", reason);
            stack.setStatus("ROLLBACK_IN_PROGRESS");
            List<String> rollbackFailures = rollbackCreatedResources(stack, region);
            stack.setLastUpdatedTime(now());
            if (rollbackFailures.isEmpty()) {
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "ROLLBACK_COMPLETE", null);
                stack.setStatus("ROLLBACK_COMPLETE");
                LOG.infov("Stack {0} rolled back to a clean slate (ROLLBACK_COMPLETE)", stack.getStackName());
            } else {
                String rollbackReason = "The following resource(s) failed to roll back: ["
                        + String.join(", ", rollbackFailures) + "].";
                stack.setStatusReason(rollbackReason);
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "ROLLBACK_FAILED", rollbackReason);
                stack.setStatus("ROLLBACK_FAILED");
                LOG.errorv("Stack {0} rollback failed: {1}", stack.getStackName(), rollbackReason);
            }
        } else {
            rollbackFailedUpdate(
                    stack, region, previousState, attemptedResourceIds,
                    reason);
            return;
        }
        persistStack(stack);
    }

    /**
     * The stack's reason for rolling back: a summary naming the failed resource, whose own message
     * stays on its own event. A resource an update adds failed to create, and only a create
     * rollback says the rollback was requested, the default OnFailure counting as the request.
     */
    private static String rollbackReason(boolean isCreate, StackResource failedResource) {
        String operation = "UPDATE_FAILED".equals(failedResource.getStatus()) ? "update" : "create";
        String summary = "The following resource(s) failed to " + operation + ": ["
                + failedResource.getLogicalId() + "].";
        return isCreate ? summary + " Rollback requested by user." : summary;
    }

    /**
     * The stack's reason, or, while that is still the rollback summary, the failed resource's own
     * message, which is what a stack set instance reports as the cause.
     */
    static String failureCause(Stack stack) {
        String reason = stack.getStatusReason();
        List<StackEvent> events = stack.eventsSnapshot();
        int rollbackStart = events.size() - 1;
        while (rollbackStart >= 0 && !isStackEvent(stack, events.get(rollbackStart), ROLLBACK_STARTS)) {
            if (isStackEvent(stack, events.get(rollbackStart), OPERATION_STARTS)) {
                return reason;
            }
            rollbackStart--;
        }
        if (rollbackStart < 0 || !Objects.equals(reason, events.get(rollbackStart).getResourceStatusReason())) {
            return reason;
        }
        for (int i = rollbackStart - 1; i >= 0; i--) {
            StackEvent event = events.get(i);
            if (isOwnEvent(stack, event)) {
                return reason;
            }
            if ("CREATE_FAILED".equals(event.getResourceStatus()) || "UPDATE_FAILED".equals(event.getResourceStatus())) {
                return event.getResourceStatusReason();
            }
        }
        return reason;
    }

    private static boolean isStackEvent(Stack stack, StackEvent event, Set<String> statuses) {
        return isOwnEvent(stack, event) && statuses.contains(event.getResourceStatus());
    }

    /** The stack's own event: a resource may share the stack's name, but never its stack ID. */
    private static boolean isOwnEvent(Stack stack, StackEvent event) {
        return stack.getStackName().equals(event.getLogicalResourceId())
                && Objects.equals(stack.getStackId(), event.getPhysicalResourceId());
    }

    private List<UpdateCleanupFailure> finishCommittedResourceCleanup(Stack stack, String region) {
        return finishCommittedResourceCleanup(stack, region, false);
    }

    private List<UpdateCleanupFailure> finishCommittedResourceCleanup(Stack stack, String region, boolean deleting) {
        List<UpdateCleanupFailure> failures = new ArrayList<>();
        // Dependents go before what they depend on, as when the stack is deleted: a displaced
        // listener has to go before the displaced target group it still forwards to, or that
        // delete fails ResourceInUse three times and leaves the group behind. The template's
        // order, not the map's: a resource a later update added sits after the resources that
        // depend on it, so reversing the map would delete it first.
        List<StackResource> resources = resourcesInCreationOrder(stack, region);
        Collections.reverse(resources);
        for (StackResource resource : resources) {
            String cleanupPhysicalId = dispatcher.updateCleanupPhysicalId(resource);
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
                UpdateCleanupResult result = deleting ? dispatcher.completeDeleteCleanup(resource)
                        : dispatcher.completeUpdate(resource);
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
                    if (deleting) {
                        dispatcher.clearDeleteCleanup(resource);
                    } else {
                        dispatcher.clearUpdate(resource);
                    }
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
                if (deleting) {
                    dispatcher.clearDeleteCleanup(resource);
                } else {
                    dispatcher.clearUpdate(resource);
                }
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
        return stack.resourcesSnapshot().values().stream()
                .anyMatch(dispatcher::hasReplacementUpdate);
    }

    private boolean hasRemovedOrConditionFalseResources(Stack stack, JsonNode resources, Map<String, Boolean> conditions) {
        if (!resources.isObject()) {
            return false;
        }
        for (StackResource resource : stack.resourcesSnapshot().values()) {
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

    private void deleteResourcePhysically(StackResource resource, String region, String accountId)
            throws Exception {
        if ("AWS::CloudFormation::Stack".equals(resource.getResourceType())) {
            Stack child = resolveStack(resource.getPhysicalId(), region, accountId);
            if (child == null) {
                return;
            }
            if (child.isEnableTerminationProtection()) {
                throw new AwsException("ValidationError",
                        "Stack [" + child.getStackId()
                                + "] cannot be deleted while TerminationProtection is enabled", 400);
            }
            child.setStatus("DELETE_IN_PROGRESS");
            addEvent(child, child.getStackName(), child.getStackId(),
                    "AWS::CloudFormation::Stack", "DELETE_IN_PROGRESS", null);
            deleteStackResources(child, region, accountId);
        } else {
            dispatcher.delete(resource, region);
        }
    }

    /**
     * The body of ContinueUpdateRollback, on a stack already moved to UPDATE_ROLLBACK_IN_PROGRESS:
     * checks {@code resourcesToSkip}, reports each skipped resource UPDATE_COMPLETE, runs the
     * rollback walker again over the resources still owed a rollback, and finishes as the rollback
     * of the failed update does. A bad skip touches no resource: the stack returns to
     * UPDATE_ROLLBACK_FAILED with the reason, keeping what a later retry needs. A forced rollback
     * skips every UPDATE_FAILED resource of the stack instead, and says so when it completes.
     */
    void resumeUpdateRollback(Stack stack, List<String> resourcesToSkip, boolean forceRollback, String region) {
        try {
            List<String> skips = forceRollback
                    ? stack.resourcesSnapshot().values().stream()
                            .filter(resource -> "UPDATE_FAILED".equals(resource.getStatus()))
                            .map(StackResource::getLogicalId)
                            .toList()
                    : resourcesToSkip;
            String invalidSkip = invalidSkipReason(stack, skips);
            if (invalidSkip != null) {
                // Reason and event before the status, which is the volatile publishing write.
                stack.setStatusReason(invalidSkip);
                addEvent(stack, stack.getStackName(), stack.getStackId(),
                        "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_FAILED", invalidSkip);
                stack.setStatus("UPDATE_ROLLBACK_FAILED");
                persistStack(stack);
                LOG.warnv("Stack {0} update rollback not continued: {1}", stack.getStackName(), invalidSkip);
                return;
            }
            StackUpdateSnapshot previousState = stack.getUpdateRollbackSnapshot();
            List<String> owedResourceIds = stack.getUpdateRollbackResourceIds();
            if (previousState == null || owedResourceIds == null) {
                // A stack stored before the snapshot was kept: every UPDATE_FAILED resource is owed a
                // rollback, and the template the stack holds now is the one it keeps.
                previousState = snapshotForUpdate(stack);
                owedResourceIds = new ArrayList<>();
                for (StackResource previous : previousState.resources().values()) {
                    if ("UPDATE_FAILED".equals(previous.getStatus())) {
                        owedResourceIds.add(previous.getLogicalId());
                        previous.setStatus("UPDATE_COMPLETE");
                        previous.setStatusReason(null);
                    }
                }
            }
            Set<String> owed = new LinkedHashSet<>(owedResourceIds);
            for (String logicalId : skips) {
                StackResource resource = stack.getResources().get(logicalId);
                resource.setStatus("UPDATE_COMPLETE");
                resource.setStatusReason(SKIPPED_RESOURCE_REASON);
                addEvent(stack, logicalId, resource.getPhysicalId(), resource.getResourceType(),
                        "UPDATE_COMPLETE", SKIPPED_RESOURCE_REASON);
                owed.remove(logicalId);
            }
            // Recorded before and after the walker, so an unexpected failure never re-runs a skip or a done rollback.
            stack.setUpdateRollbackSnapshot(previousState);
            stack.setUpdateRollbackResourceIds(List.copyOf(owed));
            List<String> rollbackFailures = rollbackUpdatedResources(
                    stack, previousState.resources(), owed, region);
            stack.setUpdateRollbackResourceIds(List.copyOf(rollbackFailures));
            finishUpdateRollback(stack, region, previousState, rollbackFailures,
                    forceRollback && !skips.isEmpty() ? FORCED_ROLLBACK_COMPLETE_REASON : null);
        } catch (Exception e) {
            // Nobody reads this worker's future: a stack left UPDATE_ROLLBACK_IN_PROGRESS here could
            // never be continued. The snapshot and the resources still owed a rollback are kept.
            LOG.errorv("Stack {0} update rollback could not continue: {1}",
                    stack.getStackName(), e.getMessage());
            stack.setStatusReason(e.getMessage());
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_FAILED", e.getMessage());
            stack.setStatus("UPDATE_ROLLBACK_FAILED");
            persistStack(stack);
        }
    }

    /**
     * Why AWS would fail the rollback over {@code resourcesToSkip}, or {@code null} when every
     * skip is an UPDATE_FAILED resource of this stack. AWS names a nested stack's resource
     * {@code NestedStackName.ResourceLogicalID}; Floci does not support skipping one.
     */
    private static String invalidSkipReason(Stack stack, List<String> resourcesToSkip) {
        List<String> notInStack = new ArrayList<>();
        boolean notUpdateFailed = false;
        for (String logicalId : resourcesToSkip) {
            int dot = logicalId.indexOf('.');
            if (dot >= 0) {
                String nestedStackId = logicalId.substring(0, dot);
                StackResource nested = stack.getResources().get(nestedStackId);
                if (nested == null || !"AWS::CloudFormation::Stack".equals(nested.getResourceType())) {
                    return SKIP_NESTED_STACK_MISSING.formatted(nestedStackId);
                }
                return SKIP_NESTED_UNSUPPORTED.formatted(logicalId);
            }
            StackResource resource = stack.getResources().get(logicalId);
            if (resource == null) {
                notInStack.add(logicalId);
            } else if (!"UPDATE_FAILED".equals(resource.getStatus())) {
                notUpdateFailed = true;
            }
        }
        if (!notInStack.isEmpty()) {
            return SKIP_NOT_IN_STACK.formatted(String.join(", ", notInStack), stack.getStackName());
        }
        return notUpdateFailed ? SKIP_NOT_UPDATE_FAILED : null;
    }

    private void rollbackFailedUpdate(
            Stack stack,
            String region,
            StackUpdateSnapshot previousState,
            Set<String> attemptedResourceIds,
            String failureReason) {
        stack.setStatusReason(failureReason);
        addEvent(stack, stack.getStackName(), stack.getStackId(),
                "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_IN_PROGRESS", failureReason);
        stack.setStatus("UPDATE_ROLLBACK_IN_PROGRESS");

        List<String> rollbackFailures = rollbackUpdatedResources(
                stack, previousState.resources(), attemptedResourceIds, region);
        finishUpdateRollback(stack, region, previousState, rollbackFailures, null);
    }

    /**
     * Ends an update rollback, the first one and every ContinueUpdateRollback retry alike: restores
     * the parameters, outputs and exports, and the template once every resource rolled back, then
     * settles the stack in UPDATE_ROLLBACK_COMPLETE, with {@code completionReason} as its reason, or
     * UPDATE_ROLLBACK_FAILED. A rollback that fails keeps the snapshot and the resources still owed
     * a rollback on the stack for a retry.
     */
    private void finishUpdateRollback(
            Stack stack,
            String region,
            StackUpdateSnapshot previousState,
            List<String> rollbackFailures,
            String completionReason) {
        List<String> owedResourceIds = List.copyOf(rollbackFailures);
        // Parameters are independent of resource-rollback outcome - always restore them to the last
        // successfully deployed values, even when resource rollback itself fails and the stack lands
        // in UPDATE_ROLLBACK_FAILED, so DescribeStacks and later change-set previews don't keep
        // serving the failed update's attempted values.
        stack.replaceParameters(previousState.parameters());
        stack.replaceResolvedParameters(previousState.resolvedParameters());
        if (rollbackFailures.isEmpty()) {
            stack.setTemplateBody(previousState.templateBody());
            // GetTemplate reads originalTemplateBody for TemplateStage=Original and templateBody
            // for TemplateStage=Processed: without restoring originalTemplateBody here too, a
            // rolled-back update would keep serving the failed attempt's submitted body under
            // Original even though every other piece of state (resources, parameters, outputs)
            // was restored.
            stack.setOriginalTemplateBody(previousState.originalTemplateBody());
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
            stack.setUpdateRollbackSnapshot(null);
            stack.setUpdateRollbackResourceIds(null);
            // Reason and event before the status, which is the volatile publishing write.
            stack.setStatusReason(completionReason);
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_COMPLETE", completionReason);
            stack.setStatus("UPDATE_ROLLBACK_COMPLETE");
            LOG.infov("Stack {0} update rolled back (UPDATE_ROLLBACK_COMPLETE)",
                    stack.getStackName());
        } else {
            String reason = "The following resource(s) failed to roll back: ["
                    + String.join(", ", rollbackFailures) + "].";
            stack.setUpdateRollbackSnapshot(previousState);
            stack.setUpdateRollbackResourceIds(owedResourceIds);
            stack.setStatusReason(reason);
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "UPDATE_ROLLBACK_FAILED", reason);
            stack.setStatus("UPDATE_ROLLBACK_FAILED");
            LOG.errorv("Stack {0} update rollback failed: {1}", stack.getStackName(), reason);
        }
        persistStack(stack);
    }

    private List<String> rollbackUpdatedResources(
            Stack stack,
            Map<String, StackResource> previousResources,
            Set<String> attemptedResourceIds,
            String region) {
        // Dependents first, in the template's order rather than the map's, as the update cleanup
        // walks it: a resource the update added sits after the resources that depend on it, so
        // reversing the map would delete a subnet the update moved a load balancer onto before the
        // load balancer is moved back, and EC2 refuses that delete.
        List<StackResource> resources = resourcesInCreationOrder(stack, region);
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
                        deleteResourcePhysically(resource, region, ownerAccount(stack));
                    }
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "DELETE_COMPLETE",
                            "Resource creation cancelled during update rollback");
                    removedResources.add(resource.getLogicalId());
                } else if (resource.getAttributes().containsKey(
                        CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)) {
                    String reason = resource.getAttributes().remove(
                            CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                    failures.add(resource.getLogicalId());
                    resource.setStatus("UPDATE_FAILED");
                    resource.setStatusReason(reason);
                    addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                            resource.getResourceType(), "UPDATE_FAILED", reason);
                } else if ("true".equals(resource.getAttributes().remove(
                        CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR))
                        || dispatcher.rollbackUpdate(resource,
                                event -> addEvent(stack, resource.getLogicalId(), event.getPhysicalResourceId(),
                                        resource.getResourceType(), event.getResourceStatus(), event.getResourceStatusReason()))) {
                    boolean recoveredPendingUpdate = dispatcher.requiresUpdateRecovery(previous);
                    resource.setStatus(recoveredPendingUpdate ? "UPDATE_COMPLETE" : previous.getStatus());
                    resource.setStatusReason(recoveredPendingUpdate ? null : previous.getStatusReason());
                    restoreTemplateUses(resource, previous);
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
        for (String exportName : stack.exportsSnapshot().keySet()) {
            String logicalKey = exportKey(region, exportName);
            String key = accountExportKey(ownerAccount(stack), logicalKey);
            exports.remove(key);
            try {
                exportBackend.deleteForAccount(ownerAccount(stack), logicalKey);
            } catch (RuntimeException e) {
                storageFailure = appendFailure(storageFailure, e);
            }
        }

        stack.replaceOutputs(previousState.outputs());
        stack.replaceExports(previousState.exports());
        stack.replaceOutputExportNames(previousState.outputExportNames());

        for (Map.Entry<String, String> entry : previousState.exports().entrySet()) {
            String logicalKey = exportKey(region, entry.getKey());
            String key = accountExportKey(ownerAccount(stack), logicalKey);
            exports.put(key, entry.getValue());
            try {
                exportBackend.putForAccount(ownerAccount(stack), logicalKey, entry.getValue());
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

    /**
     * The resources of the processed template an update replaces, so a provisioner can tell a
     * property the update removes from one the stack never declared. All of them, since an update
     * can provision a resource whose own definition did not change. Evaluated under the previous
     * deployment's conditions: a top-level property whose {@code Fn::If} chose {@code AWS::NoValue}
     * was never applied and is left out. Missing when there is no previous template, and when it no
     * longer parses: the update then proceeds as if nothing had been declared.
     */
    private JsonNode previousResources(Stack stack, StackUpdateSnapshot previousState, String region,
                                       String accountId) {
        if (previousState.templateBody() == null) {
            return objectMapper.missingNode();
        }
        try {
            JsonNode previousTemplate = parseTemplate(previousState.templateBody());
            // As the change-set preview does: raw parameters for a stack persisted before
            // resolvedParameters existed.
            Map<String, String> previousParams = previousState.resolvedParameters().isEmpty()
                    ? previousState.parameters() : previousState.resolvedParameters();
            Map<String, Boolean> conditions = resolveConditions(previousTemplate, previousParams, stack, region,
                    accountId);
            ObjectNode resources = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : previousTemplate.path("Resources").properties()) {
                JsonNode definition = entry.getValue();
                ObjectNode applied = resources.putObject(entry.getKey());
                applied.set("Type", definition.path("Type"));
                if (definition.path("Properties").isObject()) {
                    applied.set("Properties", appliedProperties(definition.path("Properties"), conditions));
                }
            }
            return resources;
        } catch (Exception e) {
            LOG.warnv("Previous template of stack {0} could not be evaluated ({1}); its updated resources see no prior properties",
                    stack.getStackName(), e.getMessage());
            return objectMapper.missingNode();
        }
    }

    /**
     * The top-level properties left after each one's {@code Fn::If} is decided, without those that
     * chose {@code AWS::NoValue}. Decided as the template engine decides it: a condition the
     * template does not define is false.
     */
    private ObjectNode appliedProperties(JsonNode properties, Map<String, Boolean> conditions) {
        ObjectNode applied = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            JsonNode value = decideIf(property.getValue(), conditions);
            boolean noValue = value.isObject() && value.size() == 1 && "AWS::NoValue".equals(value.path("Ref").asText());
            if (!noValue) {
                applied.set(property.getKey(), property.getValue());
            }
        }
        return applied;
    }

    /** The branch an {@code Fn::If} chose, following nested ones; any other value as it is. */
    private static JsonNode decideIf(JsonNode value, Map<String, Boolean> conditions) {
        while (value.isObject() && value.size() == 1 && value.path("Fn::If").isArray()
                && value.path("Fn::If").size() >= 3) {
            JsonNode branches = value.path("Fn::If");
            value = conditions.getOrDefault(branches.get(0).asText(), false) ? branches.get(1) : branches.get(2);
        }
        return value;
    }

    private StackUpdateSnapshot snapshotForUpdate(Stack stack) {
        return new StackUpdateSnapshot(
                stack.getTemplateBody(),
                stack.getOriginalTemplateBody(),
                stack.parametersSnapshot(),
                stack.resolvedParametersSnapshot(),
                stack.outputsSnapshot(),
                stack.exportsSnapshot(),
                stack.outputExportNamesSnapshot(),
                copyResources(stack.resourcesSnapshot()));
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

    private record UpdateCleanupFailure(
            String logicalId, String physicalId, String failureReason) {
    }

    /** Deletes every resource created in this execution, in reverse order. */
    private List<String> rollbackCreatedResources(Stack stack, String region) {
        List<StackResource> resources = new ArrayList<>(stack.resourcesSnapshot().values());
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
                deleteResourcePhysically(resource, region, ownerAccount(stack));
                completeResourceDeletion(stack, resource);
            } catch (Exception e) {
                if (isAlreadyDeleted(e)) {
                    completeResourceDeletion(stack, resource);
                    LOG.debugv("Resource {0} ({1}) was already deleted while rolling back stack {2}",
                            resource.getResourceType(), resource.getPhysicalId(), stack.getStackName());
                    continue;
                }
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
     * <p>Dependents go first, in the order {@link #resourcesInCreationOrder} gives rather than the map's:
     * a subnet an earlier update added sits after the load balancer it moved onto it, and the template
     * that named what a removed resource uses is gone. A failed delete is not reported here, since the
     * caller tries it again after the replacement cleanup.
     *
     * @return the resources whose physical deletion failed, for {@link #deleteRemovedResource} to try again
     */
    private List<StackResource> deleteRemovedOrConditionFalseResources(Stack stack, JsonNode resources,
                                                           Map<String, Boolean> conditions, String region) {
        if (!resources.isObject()) {
            return List.of();
        }

        List<StackResource> failed = new ArrayList<>();
        List<StackResource> ordered = resourcesInCreationOrder(stack, region);
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
            if (deleteRemovedResource(stack, resource, region, false) != null) {
                failed.add(resource);
            }
        }

        return failed;
    }

    /**
     * Deletes one removed or condition-disabled resource, and reports a failure only on the last
     * attempt.
     *
     * @return the failure, or null once the resource is deleted
     */
    private UpdateCleanupFailure deleteRemovedResource(Stack stack, StackResource resource, String region,
                                                       boolean lastAttempt) {
        try {
            deleteResourcePhysically(resource, region, ownerAccount(stack));
            addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                    resource.getResourceType(), "DELETE_COMPLETE", null);
            stack.getResources().remove(resource.getLogicalId());
            return null;
        } catch (Exception e) {
            String reason = e.getMessage() != null
                    ? e.getMessage()
                    : "Resource deletion failed during update cleanup";
            UpdateCleanupFailure failure = new UpdateCleanupFailure(
                    resource.getLogicalId(), resource.getPhysicalId(), reason);
            if (!lastAttempt) {
                return failure;
            }
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
            return failure;
        }
    }

    /**
     * The stack's resources in the order the current template creates them, for a delete, an update
     * cleanup or an update rollback that walks them backwards. The resource map keeps insertion
     * order, and a resource added by a later update sits after the resources that depend on it, so
     * reversing the map would delete it first: a certificate still used by a user pool domain, for
     * one. Resources the template no longer names, left behind by a failed cleanup or skipped by
     * ContinueUpdateRollback, are deleted as late as possible, after anything that might still use
     * them, but before any held resource they use: one whose physical id their record names, such
     * as a subnet's {@code VpcId}, one whose id theirs extends, or for a type that takes its
     * parent's id, the parent (see {@link #leftoverPosition}). With their template entry gone, that
     * is the only record of what they depend on, so a leftover that uses nothing held is still
     * deleted last.
     * Conditions are ignored, both on resources and in {@code Fn::If}: a resource depends on what
     * every branch names. The conditions read now need not be the ones a held resource was
     * provisioned under, since a failed rollback restores the parameters but can leave a resource
     * on the branch the failed update chose. When counting every branch closes a cycle, the walk
     * starts from the branches chosen now, evaluated with the SSM-resolved parameter values when
     * there are any, and adds each other-branch dependency that closes no cycle. Insertion order
     * is the fallback when the template cannot be read.
     */
    private List<StackResource> resourcesInCreationOrder(Stack stack, String region) {
        List<StackResource> ordered = new ArrayList<>(stack.resourcesSnapshot().values());
        try {
            JsonNode template = parseTemplate(stack.getTemplateBody());
            JsonNode resources = template.path("Resources");
            if (!resources.isObject()) {
                return ordered;
            }
            // Ordered without conditions: only the resources the stack holds are walked, and each
            // was provisioned, so its dependencies count whatever its condition reads now. A
            // ContinueUpdateRollback retry reads the failed update's template with the parameters
            // the first pass already restored, and a held resource may depend on a condition-false
            // one an earlier cleanup deleted, which would otherwise fail the whole ordering.
            ObjectNode unconditional = resources.deepCopy();
            for (Map.Entry<String, JsonNode> entry : unconditional.properties()) {
                if (entry.getValue() instanceof ObjectNode definition) {
                    definition.remove("Condition");
                }
            }
            ObjectNode everyBranch = unconditional.deepCopy();
            for (Map.Entry<String, JsonNode> entry : everyBranch.properties()) {
                if (entry.getValue() instanceof ObjectNode definition && definition.has("Properties")) {
                    definition.set("Properties", everyIfBranch(definition.get("Properties")));
                }
            }
            // A stack saved before circular templates were rejected may still have one; order it
            // by its dependencies anyway rather than giving up.
            CreationOrder order = creationOrder(everyBranch, Map.of());
            if (!order.circular().isEmpty()) {
                order = withoutBranchCycles(order, stack, template, unconditional, everyBranch, region);
            }
            List<String> creationOrder = order.sorted();
            Map<String, Integer> rank = new HashMap<>();
            for (int i = 0; i < creationOrder.size(); i++) {
                rank.put(creationOrder.get(i), i);
            }
            Map<String, List<StackResource>> holdersByPhysicalId = new HashMap<>();
            Map<String, StackResource> held = new HashMap<>();
            int leftovers = 0;
            for (StackResource resource : ordered) {
                if (!rank.containsKey(resource.getLogicalId())) {
                    leftovers++;
                }
                if (resource.getPhysicalId() != null) {
                    holdersByPhysicalId.computeIfAbsent(resource.getPhysicalId(), id -> new ArrayList<>())
                            .add(resource);
                }
                held.put(resource.getLogicalId(), resource);
            }
            double step = 1.0 / (leftovers + 1);
            Map<String, Double> position = new HashMap<>();
            Map<String, LeftoverReferences> read = new HashMap<>();
            Function<StackResource, LeftoverReferences> references = resource -> read.computeIfAbsent(
                    resource.getLogicalId(), id -> leftoverReferences(resource, held));
            for (StackResource resource : ordered) {
                position.put(resource.getLogicalId(),
                        leftoverPosition(resource, rank, holdersByPhysicalId, step, position, new HashSet<>(),
                                references));
            }
            ordered.sort(Comparator.comparingDouble(r -> position.get(r.getLogicalId())));
        } catch (Exception e) {
            LOG.debugv("Walking stack {0} in insertion order, its template could not be ordered: {1}",
                    stack.getStackName(), e.getMessage());
        }
        return ordered;
    }

    /**
     * The order the branches chosen now give, with every dependency another {@code Fn::If} branch
     * names added to it unless that dependency would close a cycle. Counting every branch can close
     * a cycle no choice of branches has, such as two resources that each use the other under
     * opposite conditions; dropping only the edges that close it keeps every other branch's
     * dependencies. {@code everyBranch}, the order counting every branch, is kept when the
     * conditions cannot be read.
     */
    private CreationOrder withoutBranchCycles(CreationOrder everyBranch, Stack stack, JsonNode template,
                                              ObjectNode unconditional, ObjectNode everyBranchResources,
                                              String region) {
        try {
            Map<String, String> resolvedParameters = stack.resolvedParametersSnapshot();
            Map<String, Boolean> conditions = resolveConditions(template,
                    resolvedParameters.isEmpty() ? stack.parametersSnapshot() : resolvedParameters,
                    stack, region, regionResolver.getAccountId());
            Set<String> ids = new LinkedHashSet<>();
            unconditional.fieldNames().forEachRemaining(ids::add);
            Map<String, Set<String>> dependencies = new HashMap<>();
            for (String id : ids) {
                Set<String> chosen = collectResourceDependencies(unconditional.get(id), ids, conditions);
                chosen.retainAll(ids);
                dependencies.put(id, chosen);
            }
            ObjectNode merged = unconditional.deepCopy();
            for (String id : ids) {
                for (String dependency : collectResourceDependencies(everyBranchResources.get(id), ids, Map.of())) {
                    if (ids.contains(dependency) && !dependency.equals(id)
                            && !dependencies.get(id).contains(dependency)
                            && !dependsOn(dependencies, dependency, id)) {
                        dependencies.get(id).add(dependency);
                        addDependsOn(merged.get(id), dependency);
                    }
                }
            }
            return creationOrder(merged, conditions);
        } catch (RuntimeException e) {
            LOG.debugv("Ordering stack {0} by every Fn::If branch, its conditions could not be read: {1}",
                    stack.getStackName(), e.getMessage());
            return everyBranch;
        }
    }

    /** Whether {@code from} depends on {@code to}, directly or through other resources. */
    private static boolean dependsOn(Map<String, Set<String>> dependencies, String from, String to) {
        Deque<String> pending = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(to)) {
                return true;
            }
            if (seen.add(current)) {
                pending.addAll(dependencies.getOrDefault(current, Set.of()));
            }
        }
        return false;
    }

    /** Adds a resource to a definition's {@code DependsOn}, keeping what it already names. */
    private static void addDependsOn(JsonNode definition, String dependency) {
        if (!(definition instanceof ObjectNode object)) {
            return;
        }
        JsonNode existing = object.path("DependsOn");
        ArrayNode dependsOn = JsonNodeFactory.instance.arrayNode();
        if (existing.isTextual()) {
            dependsOn.add(existing.asText());
        } else if (existing.isArray()) {
            dependsOn.addAll((ArrayNode) existing);
        }
        dependsOn.add(dependency);
        object.set("DependsOn", dependsOn);
    }

    /**
     * A copy of a template value with each {@code Fn::If} replaced by a list of both its branches,
     * so the dependencies collected from it are what either branch names.
     */
    private static JsonNode everyIfBranch(JsonNode node) {
        if (node.isObject()) {
            JsonNode fnIf = node.path("Fn::If");
            if (fnIf.isArray() && fnIf.size() == 3) {
                ArrayNode branches = JsonNodeFactory.instance.arrayNode();
                branches.add(everyIfBranch(fnIf.get(1)));
                branches.add(everyIfBranch(fnIf.get(2)));
                return branches;
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(e -> copy.set(e.getKey(), everyIfBranch(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(everyIfBranch(item)));
            return copy;
        }
        return node;
    }

    /**
     * A resource's place in {@link #resourcesInCreationOrder}: its template rank, or for a leftover
     * one step after the latest held resource it uses, leftovers included, or else -1. A chain of
     * leftovers stays below the next template rank, and a cycle among them is cut where it closes.
     * A leftover uses what its definition named, and a held resource whose physical id one of its
     * values names (both per {@link #leftoverReferences}), unless that resource's id extends the
     * leftover's. A value equal to the leftover's own id names the leftover itself, so it does not
     * make the leftover use a dependent that takes the same id, such as its bucket policy. It still
     * names any other resource that shares the id, such as the event bus of a rule with the bus's
     * name. A leftover also uses the resource whose id its own extends, {@code parent-1} for
     * {@code parent-1/child-1}, unless its provisioner says its id names no parent, and a type whose
     * provisioner says it takes its parent's id, such as a bucket policy, uses whatever else holds
     * that id. None of these three counts a resource whose own definition named the leftover: that
     * one depends on it.
     */
    private static double leftoverPosition(StackResource resource, Map<String, Integer> rank,
                                           Map<String, List<StackResource>> holdersByPhysicalId,
                                           double step, Map<String, Double> position, Set<String> visiting,
                                           Function<StackResource, LeftoverReferences> references) {
        Integer resourceRank = rank.get(resource.getLogicalId());
        if (resourceRank != null) {
            return resourceRank;
        }
        Double known = position.get(resource.getLogicalId());
        if (known != null) {
            return known;
        }
        // A depth-first walk on an explicit stack rather than recursion, so a long chain of
        // leftovers, each naming the next, cannot overflow the thread's stack.
        Deque<LeftoverVisit> path = new ArrayDeque<>();
        path.push(new LeftoverVisit(resource, leftoverUses(resource, holdersByPhysicalId, references)));
        visiting.add(resource.getLogicalId());
        while (true) {
            LeftoverVisit current = path.peek();
            if (current.next < current.uses.size()) {
                StackResource holder = current.uses.get(current.next++);
                Integer holderRank = rank.get(holder.getLogicalId());
                Double holderPosition = holderRank != null
                        ? Double.valueOf(holderRank) : position.get(holder.getLogicalId());
                if (holderPosition != null) {
                    current.placed = Math.max(current.placed, holderPosition + step);
                } else if (!visiting.contains(holder.getLogicalId())) {
                    // A holder already on the path closes a cycle, which is cut here.
                    visiting.add(holder.getLogicalId());
                    path.push(new LeftoverVisit(holder, leftoverUses(holder, holdersByPhysicalId, references)));
                }
                continue;
            }
            path.pop();
            visiting.remove(current.resource.getLogicalId());
            position.put(current.resource.getLogicalId(), current.placed);
            LeftoverVisit parent = path.peek();
            if (parent == null) {
                return current.placed;
            }
            parent.placed = Math.max(parent.placed, current.placed + step);
        }
    }

    /** One leftover on the walk's path: the held resources it uses, how many are done, its place so far. */
    private static final class LeftoverVisit {
        private final StackResource resource;
        private final List<StackResource> uses;
        private int next;
        private double placed = -1;

        private LeftoverVisit(StackResource resource, List<StackResource> uses) {
            this.resource = resource;
            this.uses = uses;
        }
    }

    /** The held resources a leftover uses, per {@link #leftoverPosition}. */
    private static List<StackResource> leftoverUses(StackResource resource,
                                                    Map<String, List<StackResource>> holdersByPhysicalId,
                                                    Function<StackResource, LeftoverReferences> references) {
        LeftoverReferences named = references.apply(resource);
        String own = resource.getPhysicalId();
        List<StackResource> uses = new ArrayList<>(named.declared());
        for (String value : named.values()) {
            if (value == null) {
                continue;
            }
            boolean ownId = value.equals(own);
            for (StackResource holder : holdersByPhysicalId.getOrDefault(value, List.of())) {
                // Its own id names the leftover, not a dependent that takes the same id.
                if (holder != resource && !extendsId(holder.getPhysicalId(), own)
                        && !(ownId && takesParentId(holder, references))
                        && !declares(holder, resource, references)) {
                    uses.add(holder);
                }
            }
        }
        for (String prefix : named.idPrefixNamesParent() ? idPrefixes(own) : List.<String>of()) {
            for (StackResource holder : holdersByPhysicalId.getOrDefault(prefix, List.of())) {
                if (!declares(holder, resource, references)) {
                    uses.add(holder);
                }
            }
        }
        if (named.takesParentId()) {
            // A type that takes its parent's id as its own uses whatever else holds that id.
            for (StackResource holder : holdersByPhysicalId.getOrDefault(own, List.of())) {
                if (holder != resource && !takesParentId(holder, references)
                        && !declares(holder, resource, references)) {
                    uses.add(holder);
                }
            }
        }
        return uses;
    }

    /** Whether {@code holder}'s definition named {@code resource}: then it depends on it, not the reverse. */
    private static boolean declares(StackResource holder, StackResource resource,
                                    Function<StackResource, LeftoverReferences> references) {
        return references.apply(holder).declared().contains(resource);
    }

    /**
     * What a resource's record says it uses; see {@link #leftoverReferences}. {@code takesParentId}
     * and {@code idPrefixNamesParent} are what its type's provisioner says of its physical id, per
     * {@link CfnResourceDispatcher#takesParentPhysicalId} and
     * {@link CfnResourceDispatcher#idPrefixCanNameParent}.
     */
    private record LeftoverReferences(List<String> values, List<StackResource> declared,
                                      boolean takesParentId, boolean idPrefixNamesParent) {
    }

    /** Whether a resource's type takes the physical id of the resource it depends on as its own. */
    private static boolean takesParentId(StackResource resource,
                                         Function<StackResource, LeftoverReferences> references) {
        return references.apply(resource).takesParentId();
    }

    /** The characters after which a physical id may continue as a child's: {@code /}, {@code :} and {@code |}. */
    private static final String ID_SEPARATORS = "/:|";

    /**
     * Whether {@code id} extends {@code parent} past a separator, as {@code parent-1/child-1} does
     * {@code parent-1}: a resource named under another's id belongs to it.
     */
    private static boolean extendsId(String id, String parent) {
        return id != null && parent != null && id.length() > parent.length() && id.startsWith(parent)
                && ID_SEPARATORS.indexOf(id.charAt(parent.length())) >= 0;
    }

    /** Every proper prefix of {@code id} that ends just before a separator, which {@code id} extends. */
    private static List<String> idPrefixes(String id) {
        List<String> prefixes = new ArrayList<>();
        if (id != null) {
            for (int i = 1; i < id.length(); i++) {
                if (ID_SEPARATORS.indexOf(id.charAt(i)) >= 0) {
                    prefixes.add(id.substring(0, i));
                }
            }
        }
        return prefixes;
    }

    /**
     * What a resource's record says it uses, for leftover ordering. Its {@code values} may name a
     * held resource by physical id: for most resources that is every attribute value, as some, such
     * as a subnet's {@code VpcId}, say what the resource was given and the rest describe the
     * resource itself. Two kinds of resource hold attributes that can as easily name a resource
     * depending on them: a custom resource's are its handler's response Data, and a nested stack's
     * {@code Outputs.*} are its child's outputs. Those do not count. A custom resource gives only
     * Floci's own {@code __Floci} values, such as its ServiceToken, with the name of any Lambda
     * function one gives by ARN, a function's physical id being its name. What the definition of
     * either kind named, kept by {@link #recordTemplateUses}, is {@code declared}: the resources
     * {@code held} under those logical ids now, as a later update may have replaced one without
     * touching a resource that only {@code DependsOn} it. A nested stack that kept none, because it
     * was saved before they were kept or its definition names no resource, counts its outputs, as
     * Floci did before.
     */
    private LeftoverReferences leftoverReferences(StackResource resource, Map<String, StackResource> held) {
        List<String> values = new ArrayList<>();
        List<StackResource> declared = new ArrayList<>();
        boolean recorded = false;
        for (String key : resource.getAttributes().keySet()) {
            if (key.startsWith(TEMPLATE_USES_PREFIX)) {
                recorded = true;
                StackResource named = held.get(key.substring(TEMPLATE_USES_PREFIX.length()));
                if (named != null && named != resource) {
                    declared.add(named);
                }
            }
        }
        boolean custom = dispatcher.isCustomResourceType(resource.getResourceType());
        boolean skipOutputs = recorded && "AWS::CloudFormation::Stack".equals(resource.getResourceType());
        for (Map.Entry<String, String> attribute : resource.getAttributes().entrySet()) {
            String key = attribute.getKey();
            if (key.startsWith(TEMPLATE_USES_PREFIX)
                    || (custom ? !key.startsWith("__Floci") : skipOutputs && key.startsWith("Outputs."))) {
                continue;
            }
            values.add(attribute.getValue());
            if (custom) {
                String function = lambdaFunctionName(attribute.getValue());
                if (function != null) {
                    values.add(function);
                }
            }
        }
        String type = resource.getResourceType();
        return new LeftoverReferences(values, declared, dispatcher.takesParentPhysicalId(type),
                dispatcher.idPrefixCanNameParent(type));
    }

    /** The name of the Lambda function {@code value} gives by ARN, qualified or not, or else null. */
    private static String lambdaFunctionName(String value) {
        return AwsArnUtils.resourceIfArnFor(value, "lambda")
                .filter(resource -> resource.startsWith("function:"))
                .map(resource -> resource.substring("function:".length()).split(":", 2)[0])
                .orElse(null);
    }

    void deleteStackResources(Stack stack, String region, String accountId) {
        try {
            List<StackResource> resources = resourcesInCreationOrder(stack, region);
            Collections.reverse(resources); // Dependents go before what they depend on

            List<String> failedResources = new ArrayList<>();
            // The walk below addresses each resource by its physical id, which names the entity the
            // last update left in place. An entity displaced by a replacement whose cleanup phase
            // never ended is named only by the cleanup the resource still carries, so the stack
            // deletes that one too: nothing else ever will.
            for (UpdateCleanupFailure displacedFailure : finishCommittedResourceCleanup(stack, region, true)) {
                failedResources.add(displacedFailure.logicalId());
            }
            for (StackResource resource : resources) {
                if (resource.getPhysicalId() == null || !isDeletableOnStackDelete(resource)) {
                    continue;
                }
                if (skipRetainedResource(stack, resource, false)) {
                    continue;
                }
                addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                        resource.getResourceType(), "DELETE_IN_PROGRESS", null);
                try {
                    deleteResourcePhysically(resource, region, accountId);
                    completeResourceDeletion(stack, resource);
                } catch (Exception e) {
                    if (isAlreadyDeleted(e)) {
                        completeResourceDeletion(stack, resource);
                        LOG.debugv("Resource {0} ({1}) was already deleted while deleting stack {2}",
                                resource.getResourceType(), resource.getPhysicalId(), stack.getStackName());
                        continue;
                    }
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

            stack.setUpdateRollbackSnapshot(null);
            stack.setUpdateRollbackResourceIds(null);
            // Before the status, never after. Status is the volatile publishing write, so a reader
            // that observes DELETE_COMPLETE is guaranteed to observe every write that preceded it.
            // Assigned afterwards, a concurrent DescribeStacks can report the terminal status with
            // no DeletionTime at all.
            stack.setDeletionTime(now());
            stack.setStatus("DELETE_COMPLETE");
            addEvent(stack, stack.getStackName(), stack.getStackId(),
                    "AWS::CloudFormation::Stack", "DELETE_COMPLETE", null);
            removeStackExports(stack, region);
            // Retained before it leaves the live map, never after: the other order leaves a window
            // in which the stack is in neither, and a ListStacks landing there loses it entirely.
            // Overlapping instead is harmless, since both maps hold this same Stack and listStacks
            // reduces by stack id.
            deletedStacks.put(stack.getStackId(), new DeletedStackEntry(
                    stack,
                    now().plusSeconds(config.services().cloudformation().deletedStackRetentionSeconds())));
            stacks.remove(stackKey(ownerAccount(stack), stack.getStackName(), region));
            unpersistStack(ownerAccount(stack), stack.getStackName(), region);
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
     * Whether {@code DeleteStack} owes a delete to a resource that has a physical id. AWS deletes
     * everything the stack manages, whatever status the last operation left it in.
     *
     * <p>{@code UPDATE_FAILED} is what a failed update rollback leaves on a resource it could not
     * restore; its physical id names the committed entity, or an entity a cancelled create made
     * and the rollback already tried to delete. {@code DELETE_FAILED} is a delete to retry.
     * {@code CREATE_FAILED} is deleted only when the provisioner marked the entity as created by
     * this stack, as the create rollback does: a failed create can carry the physical id of an
     * entity that already existed, which the stack must not delete.
     */
    private static boolean isDeletableOnStackDelete(StackResource resource) {
        return switch (resource.getStatus() == null ? "" : resource.getStatus()) {
            case "CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_FAILED", "DELETE_FAILED" -> true;
            case "CREATE_FAILED" -> "true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
            default -> false;
        };
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

    private void completeResourceDeletion(Stack stack, StackResource resource) {
        resource.setStatus("DELETE_COMPLETE");
        resource.setStatusReason(null);
        addEvent(stack, resource.getLogicalId(), resource.getPhysicalId(),
                resource.getResourceType(), "DELETE_COMPLETE", null);
    }

    /** Returns whether a resource deletion failed solely because the resource is already gone. */
    private static boolean isAlreadyDeleted(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof AwsException awsException
                    && (awsException.getHttpStatus() == 404
                    || (awsException.getErrorCode() != null
                    && awsException.getErrorCode().endsWith("NotFoundException")))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Boolean> resolveConditions(JsonNode template, Map<String, String> params,
                                                   Stack stack, String region, String accountId) {
        return resolveConditions(template, params, stack.getStackName(), stack.getStackId(), region, accountId);
    }

    /**
     * Evaluates the template's {@code Conditions} through the template engine, so an operand of
     * {@code Fn::Equals} can be any intrinsic the engine resolves, {@code Fn::FindInMap} and the
     * pseudo parameters included. {@code AWS::StackName} and {@code AWS::StackId} resolve to the
     * name and id given, so every evaluation of one stack's template passes the stack's own; a
     * create's preflight passes the id createChangeSet allocates for the stack it then creates.
     */
    private Map<String, Boolean> resolveConditions(JsonNode template, Map<String, String> params,
                                                   String stackName, String stackId, String region,
                                                   String accountId) {
        Map<String, Boolean> conditions = new HashMap<>();
        JsonNode condNode = template.path("Conditions");
        if (!condNode.isObject()) {
            return conditions;
        }
        Map<String, JsonNode> mappings = new HashMap<>();
        template.path("Mappings").fields().forEachRemaining(e -> mappings.put(e.getKey(), e.getValue()));
        // Every declared parameter has a value or a Default once a template is deployed, but a stack
        // stored by an older Floci may lack one. It compares as "", as it did when that stack was
        // deployed, rather than as its own name, which is how the engine answers a Ref it cannot resolve.
        Map<String, String> conditionParams = new HashMap<>(params);
        template.path("Parameters").fieldNames().forEachRemaining(name -> conditionParams.putIfAbsent(name, ""));
        // A template being deployed with an import in a condition is refused up front
        // (requireStringConditionReferences); one here is a stored template's, which reads it as "".
        CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                accountId, region, stackName, stackId, conditionParams, Map.of(), Map.of(),
                conditions, mappings, objectMapper, name -> "");
        // Two-pass: collect all names first, then evaluate (handles forward references)
        condNode.fields().forEachRemaining(e -> conditions.put(e.getKey(), false));
        condNode.fields().forEachRemaining(e ->
                conditions.put(e.getKey(), evaluateCondition(e.getValue(), conditions, engine)));
        return conditions;
    }

    private static final String NON_STRING_CONDITION_REFERENCE =
            "Template error: every Condition object must have a single String value.";
    private static final String IMPORT_VALUE_IN_CONDITION =
            "Template error: Cannot use Fn::ImportValue in Conditions.";

    /**
     * Rejects a template whose conditions name another one with anything but a string, as
     * CloudFormation does before evaluating any of them: {@code Fn::And}, {@code Fn::Or} and
     * {@code Fn::Not} stop early, so evaluation alone would read such an operand as false or never
     * reach it. SAM's translator emits this shape when a conditional function's event invoke
     * destination is itself conditional. Also rejects {@code Fn::ImportValue} anywhere in a
     * condition, which CloudFormation does not allow there. Checked on the template being deployed
     * only, so a stored template read as the old side of a diff, a rollback or a delete ordering is
     * never a reason an update cannot proceed.
     */
    private static void requireStringConditionReferencesIn(JsonNode template) {
        template.path("Conditions").forEach(CloudFormationService::requireStringConditionReferences);
    }

    private static void requireStringConditionReferences(JsonNode expression) {
        if (expression.isArray()) {
            expression.forEach(CloudFormationService::requireStringConditionReferences);
            return;
        }
        if (!expression.isObject()) {
            return;
        }
        if (expression.has("Condition") && !expression.get("Condition").isTextual()) {
            throw new AwsException("ValidationError", NON_STRING_CONDITION_REFERENCE, 400);
        }
        if (expression.has("Fn::ImportValue")) {
            throw new AwsException("ValidationError", IMPORT_VALUE_IN_CONDITION, 400);
        }
        expression.forEach(CloudFormationService::requireStringConditionReferences);
    }

    /**
     * Refuses a template that declares a parameter the request gives no value and the template no
     * {@code Default}, as CloudFormation refuses CreateStack, UpdateStack and CreateChangeSet,
     * naming every such parameter in the order the template declares them. An empty value is a
     * value. {@code UsePreviousValue} is one too: the request handler has already replaced it with
     * the deployed value, or left the parameter without one when nothing was deployed. A template
     * that does not parse is left for the paths that report that, and so is what an
     * {@code AWS::Include} supplies, which only execution merges: a parameter whose definition uses
     * one, or every parameter when the section itself is one.
     */
    void requireParameterValues(String templateBody, Map<String, String> params) {
        JsonNode template;
        try {
            template = parseTemplate(templateBody);
        } catch (Exception ignored) {
            // No Parameters section to check; executing the change set reports the parse error.
            return;
        }
        JsonNode declared = template.path("Parameters");
        if (AwsIncludeProcessor.AWS_INCLUDE.equals(declared.path("Fn::Transform").path("Name").asText(""))) {
            return;
        }
        Map<String, String> given = resolveDefaultParameters(template, params);
        List<String> missing = new ArrayList<>();
        declared.properties().forEach(parameter -> {
            String name = parameter.getKey();
            if (!"Fn::Transform".equals(name) && !awsIncludeProcessor.containsAwsInclude(parameter.getValue())
                    && !given.containsKey(name)) {
                missing.add(name);
            }
        });
        if (!missing.isEmpty()) {
            throw new AwsException("ValidationError",
                    "Parameters: [" + String.join(", ", missing) + "] must have values", 400);
        }
    }

    /**
     * The values a template deploys with: {@code params} over each parameter's {@code Default}, or
     * {@code params} alone when the template does not parse. UpdateStackSet resolves
     * {@code UsePreviousValue} against these, since a stack set keeps only the values it was given.
     */
    Map<String, String> parametersWithDefaults(String templateBody, Map<String, String> params) {
        try {
            return resolveDefaultParameters(parseTemplate(templateBody), params);
        } catch (Exception ignored) {
            // No Defaults to add from a template that does not parse; the given values stand.
            return params != null ? params : Map.of();
        }
    }

    /**
     * Fails a create/update before any stack state is mutated when a resource that will be created
     * depends on a resource excluded by a false condition, or when resources depend on each other
     * in a cycle. Real CloudFormation rejects such a template synchronously ("Template format
     * error: Unresolved resource dependencies [...]" or "Circular dependency between resources:
     * [...]") rather than skipping the dependent or picking an arbitrary order, so mirror that.
     * Malformed or SAM templates are left for the execution path, which surfaces their own errors.
     * A template carrying an unexpanded {@code Fn::Transform}/{@code AWS::Include} is left for the
     * same reason: a {@code Conditions} section spliced in from a snippet is invisible here, since
     * the merge has not run yet, and treating it as absent would fail a template whose dependency
     * graph the execution path resolves correctly.
     */
    private void validateResourceDependencies(String templateBody, Map<String, String> params,
                                               String stackName, String stackId, String region,
                                               String accountId) {
        JsonNode template;
        try {
            template = parseTemplate(templateBody);
        } catch (Exception e) {
            LOG.debugv("Skipping condition-dependency validation; template did not parse: {0}",
                    e.getMessage());
            return;
        }
        if (samTransformProcessor.hasSamTransform(template) || awsIncludeProcessor.containsAwsInclude(template)) {
            return;
        }
        JsonNode resources = template.path("Resources");
        if (!resources.isObject()) {
            return;
        }

        requireStringConditionReferencesIn(template);
        Map<String, String> resolvedParams = resolveDefaultParameters(template, params);
        // Against the stack name and id the execution will use, which createChangeSet decides.
        Map<String, Boolean> conditions =
                resolveConditions(template, resolvedParams, stackName, stackId, region, accountId);

        // Throws the ValidationError for either case; the order itself is computed again on execution.
        topologicalSort(resources, conditions);
    }

    private boolean evaluateCondition(JsonNode expr, Map<String, Boolean> conditions,
                                      CloudFormationTemplateEngine engine) {
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
                    return conditionOperand(args.get(0), engine).equals(conditionOperand(args.get(1), engine));
                }
            }
            if (expr.has("Fn::Not")) {
                JsonNode args = expr.get("Fn::Not");
                if (args.isArray() && !args.isEmpty()) {
                    return !evaluateCondition(args.get(0), conditions, engine);
                }
            }
            if (expr.has("Fn::And")) {
                for (JsonNode item : expr.get("Fn::And")) {
                    if (!evaluateCondition(item, conditions, engine)) {
                        return false;
                    }
                }
                return true;
            }
            if (expr.has("Fn::Or")) {
                for (JsonNode item : expr.get("Fn::Or")) {
                    if (evaluateCondition(item, conditions, engine)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /**
     * An {@code Fn::Equals} operand as the template engine resolves it. One the engine cannot
     * resolve, such as an {@code Fn::Sub} whose template is not a string, compares as an empty
     * string, as every function did before conditions went through the engine, so a template that
     * evaluated then, stored or new, still evaluates and gets its dependency checks.
     */
    private static String conditionOperand(JsonNode operand, CloudFormationTemplateEngine engine) {
        try {
            return engine.resolve(operand);
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.debugv("Condition operand {0} did not resolve and compares as an empty string: {1}",
                    operand, e.getMessage());
            return "";
        }
    }

    private JsonNode parseTemplate(String templateBody) throws CloudFormationTemplateParser.TemplateFormatException {
        return new CloudFormationTemplateParser(objectMapper).parse(templateBody);
    }

    /**
     * {@code templateBody} parsed, refused as CloudFormation refuses a body that is no template
     * (measured, eu-west-1, 2026-10-21, for ValidateTemplate, CreateStack, UpdateStack,
     * CreateChangeSet, GetTemplateSummary, CreateStackSet and UpdateStackSet):
     * one that reads as neither JSON nor YAML, with the parser's position, and one that reads as a
     * scalar or a list. A null template passes, for the checks that answer it.
     */
    private JsonNode readTemplate(String templateBody) {
        JsonNode parsed;
        try {
            parsed = parseTemplate(templateBody);
        } catch (CloudFormationTemplateParser.TemplateFormatException e) {
            throw new AwsException("ValidationError", "Template format error: " + e.getMessage(), 400);
        }
        if (parsed != null && !parsed.isNull() && !parsed.isObject()) {
            throw new AwsException("ValidationError", "Template format error: unsupported structure.", 400);
        }
        return parsed;
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
        S3TemplateRef ref = parseTemplateUrl(url, config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX));
        try {
            S3Object obj = s3Service.getObject(ref.bucket(), ref.key());
            return new String(obj.getData());
        } catch (AwsException e) {
            // Measured (eu-west-1, 2026-10-20), alike for ValidateTemplate, CreateStack and GetTemplateSummary.
            if ("NoSuchBucket".equals(e.getErrorCode())) {
                throw new AwsException("ValidationError", "S3 error: The specified bucket does not exist\nFor more"
                        + " information check http://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html", 400);
            }
            if ("NoSuchKey".equals(e.getErrorCode())) {
                throw new AwsException("ValidationError", "S3 object does not exist. Bucket: " + ref.bucket()
                        + ", Key: " + ref.key() + ". Error: NoSuchKey", 400);
            }
            LOG.errorv("Failed to fetch CloudFormation template from {0}: {1}", url, e.getMessage());
            throw new RuntimeException("Failed to fetch CloudFormation template from " + url + ": " + e.getMessage(), e);
        } catch (Exception e) {
            LOG.errorv("Failed to fetch CloudFormation template from {0}: {1}", url, e.getMessage());
            throw new RuntimeException("Failed to fetch CloudFormation template from " + url + ": " + e.getMessage(), e);
        }
    }

    /** The bucket and key a {@code TemplateURL} addresses. */
    record S3TemplateRef(String bucket, String key) {}

    /**
     * Splits a {@code TemplateURL} into the bucket and key it addresses. Three forms:
     * <pre>
     *   Virtual-hosted AWS:   https://bucket.s3[.region].amazonaws.com/key
     *   Virtual-hosted local: http://bucket.localhost:4566/key  (or configured/default hostname)
     *   Path-style (both):    https://s3[.region].amazonaws.com/bucket/key
     *                         http://host:port/bucket/key
     * </pre>
     *
     * <p>The oldest condition matched host.endsWith(".amazonaws.com") for virtual-hosted, which
     * incorrectly caught path-style AWS URLs like s3.us-east-1.amazonaws.com and extracted
     * "s3" as the bucket name. Virtual-hosted URLs always have a bucket label before ".s3.".
     * The local hostnames kept that same misreading for longer, because ending with the
     * configured suffix was on its own enough to call a host virtual-hosted: see
     * {@link #isS3ServiceEndpointHost}, which now rules the service endpoint out first.
     *
     * @param hostnameSuffix the configured {@code floci.hostname}, or the default DNS suffix
     */
    static S3TemplateRef parseTemplateUrl(String url, String hostnameSuffix) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        String path = uri.getRawPath();

        boolean isVirtualHosted = host != null
                && !isS3ServiceEndpointHost(host, hostnameSuffix)
                && (host.contains(".s3.")
                    || hasBucketPrefixForSuffix(host, hostnameSuffix)
                    || host.endsWith(".localhost"));

        if (isVirtualHosted) {
            return new S3TemplateRef(host.split("\\.")[0],
                    path.startsWith("/") ? path.substring(1) : path);
        }
        // Path-style: /bucket/key
        String rawPath = path.startsWith("/") ? path.substring(1) : path;
        int slash = rawPath.indexOf('/');
        return new S3TemplateRef(slash > 0 ? rawPath.substring(0, slash) : rawPath,
                slash > 0 ? rawPath.substring(slash + 1) : "");
    }

    /**
     * Whether the host is the S3 <em>service</em> endpoint rather than a bucket-qualified one:
     * {@code s3.<suffix>} and the regional {@code s3.<region>.<suffix>}, for a local hostname as
     * much as for any partition's DNS suffix ({@code amazonaws.com}, {@code amazonaws.com.cn}, ...).
     * A URL against the service endpoint is path-style, so its bucket is the first path segment.
     *
     * <p>The first label alone does not decide it, because a bucket may legally be named
     * {@code s3}: that makes {@code s3.s3.us-east-1.amazonaws.com} and {@code s3.s3.<suffix>}
     * virtual-hosted URLs for that bucket. What separates the two is what follows the first
     * label, so this strips an optional region label and requires the remainder to be an
     * endpoint suffix.
     *
     * <p>{@code S3VirtualHostFilter.extractBucket} draws the same line for the request path, in
     * more detail than a TemplateURL needs.
     */
    private static boolean isS3ServiceEndpointHost(String host, String hostnameSuffix) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("s3")) {
            return true;
        }
        int firstDot = normalizedHost.indexOf('.');
        if (firstDot <= 0 || !isS3ServiceLabel(normalizedHost.substring(0, firstDot))) {
            return false;
        }
        String remainder = stripLeadingLabel(normalizedHost.substring(firstDot + 1), "dualstack");
        int dot = remainder.indexOf('.');
        if (dot > 0 && AwsRegions.isRegionId(remainder.substring(0, dot))) {
            remainder = remainder.substring(dot + 1);
        }
        return isEndpointSuffix(remainder, hostnameSuffix);
    }

    /**
     * The first label of an S3 service endpoint, in the forms
     * {@code S3VirtualHostFilter} recognizes: {@code s3}, {@code s3-fips},
     * {@code s3-accelerate}, {@code s3-website} and {@code s3-website-<region>}, and the
     * legacy {@code s3-<region>}.
     */
    private static boolean isS3ServiceLabel(String label) {
        if (label.equals("s3") || label.equals("s3-fips")
                || label.equals("s3-accelerate") || label.equals("s3-website")) {
            return true;
        }
        if (label.startsWith("s3-website-")) {
            return AwsRegions.isRegionId(label.substring("s3-website-".length()));
        }
        return label.startsWith("s3-") && AwsRegions.isRegionId(label.substring("s3-".length()));
    }

    private static String stripLeadingLabel(String host, String label) {
        return host.startsWith(label + ".") ? host.substring(label.length() + 1) : host;
    }

    private static boolean isEndpointSuffix(String candidate, String hostnameSuffix) {
        if (AwsPartitions.isDnsSuffix(candidate) || candidate.equals("localhost")) {
            return true;
        }
        return hostnameSuffix != null && !hostnameSuffix.isBlank()
                && candidate.equals(hostnameSuffix.toLowerCase(Locale.ROOT));
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

    private static final Set<String> ROLLBACK_STARTS = Set.of("ROLLBACK_IN_PROGRESS", "UPDATE_ROLLBACK_IN_PROGRESS");

    private static final Set<String> OPERATION_STARTS = Set.of("CREATE_IN_PROGRESS", "UPDATE_IN_PROGRESS");

    /**
     * Terminal statuses under which {@link #executeTemplate} actually finished creating or
     * updating a stack's resources and computed its Outputs, including the two "committed but
     * still tidying up" statuses, which still mean Outputs were resolved successfully.
     *
     * <p>Every other terminal status ({@code ROLLBACK_COMPLETE}, {@code ROLLBACK_FAILED},
     * {@code UPDATE_ROLLBACK_COMPLETE}, {@code UPDATE_ROLLBACK_FAILED}, {@code CREATE_FAILED},
     * {@code UPDATE_FAILED}) means the resource loop failed and rolled back before Outputs were
     * ever computed: see {@link #executeTemplate}, which only reaches its Outputs block once the
     * whole resource loop has succeeded. This is deliberately an allow-list rather than a
     * deny-list of failure strings: a nested stack's own {@code rollbackFailedExecution} settles
     * it in one of the ROLLBACK_* statuses before this method ever inspects it, never in
     * {@code CREATE_FAILED}/{@code UPDATE_FAILED}, so checking for the FAILED strings here can
     * never match on a create and silently reports a stack that rolled back to nothing as
     * CREATE_COMPLETE.
     */
    private static final Set<String> NESTED_STACK_SUCCESS_STATUSES = Set.of(
            "CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS");

    private StackResource executeNestedStack(Stack parentStack, String logicalId, JsonNode props,
                                             CloudFormationTemplateEngine engine, String region,
                                             String accountId, boolean isCreate,
                                             StackResource previousResource) {
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

        // Resolved as a property value: Parameters may be an Fn::If choosing between whole maps,
        // and a value that is AWS::NoValue passes nothing, so the child's Default applies. A list,
        // such as an Fn::GetAZs resolves to, is passed joined with commas, and a null as an empty
        // string, as before.
        Map<String, String> childParams = new LinkedHashMap<>();
        JsonNode parameters = props == null ? null : engine.resolveNodeOmittingNoValue(props.get("Parameters"));
        if (parameters != null && parameters.isObject()) {
            parameters.fields().forEachRemaining(e -> {
                JsonNode value = e.getValue();
                if (value.isArray()) {
                    List<String> items = new ArrayList<>();
                    value.forEach(item -> items.add(item.asText()));
                    childParams.put(e.getKey(), String.join(",", items));
                } else {
                    childParams.put(e.getKey(), value.isNull() ? "" : value.asText());
                }
            });
        }
        // CloudFormation creates or updates the child with these values, so the call fails as a
        // CreateStack or UpdateStack would, before the child is created or changed: a parameter
        // without a value, or a Fn::GetStackOutput where the child's template may not hold one
        // (measured: the Child resource fails with the template error, and the parent rolls back).
        try {
            requireParameterValues(childTemplate, childParams);
            validateGetStackOutputPlacement(childTemplate);
        } catch (AwsException e) {
            resource.setStatus("CREATE_FAILED");
            resource.setStatusReason(e.getMessage());
            return resource;
        }

        Stack childStack = null;
        boolean childCreate = isCreate || previousResource == null
                || previousResource.getPhysicalId() == null;
        if (!childCreate) {
            childStack = resolveStack(previousResource.getPhysicalId(), region, accountId);
            childCreate = childStack == null;
        }
        if (childCreate) {
            childStack = newStack(childStackName, newStackId(childStackName, region, accountId), region, accountId);
            childStack.setStatus("CREATE_IN_PROGRESS");
            stacks.put(stackKey(accountId, childStackName, region), childStack);
        }

        executeTemplate(childStack, childTemplate, childParams, null, childCreate, region, accountId);

        resource.setPhysicalId(childStack.getStackId());
        resource.getAttributes().put("Arn", childStack.getStackId());
        childStack.getOutputs().forEach((k, v) -> resource.getAttributes().put("Outputs." + k, v));

        if (NESTED_STACK_SUCCESS_STATUSES.contains(childStack.getStatus())) {
            resource.setStatus("CREATE_COMPLETE");
        } else {
            resource.setStatus("CREATE_FAILED");
            if (childCreate) {
                // This operation created the child, so rolling back or deleting the parent owes it
                // a delete; a child an update only re-applied stays tracked by the prior resource.
                resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            }
            String reason = childStack.getStatusReason();
            if (reason == null || reason.isBlank()) {
                reason = "Nested stack " + childStackName + " rolled back or failed with status " + childStack.getStatus();
            } else {
                reason = "Nested stack " + childStackName + " failed: " + reason;
            }
            resource.setStatusReason(reason);
        }

        return resource;
    }

    /** Prefixes the keys {@link #recordTemplateUses} keeps, one per resource a definition names. */
    private static final String TEMPLATE_USES_PREFIX = CfnRollback.USES_ATTR_PREFIX + "Resource.";

    /**
     * Keeps, under {@link CfnRollback#USES_ATTR_PREFIX} keys, one key per resource a definition
     * names through {@code Ref}, {@code Fn::GetAtt}, {@code Fn::Sub} or {@code DependsOn}, in place of
     * what an earlier operation kept. A custom resource's or nested stack's attributes are outputs, so
     * these are what orders it before the resources it uses once the template no longer names it; a
     * literal value it was given names nothing, even if another resource has that name. The key's
     * logical id decides, and its value, that resource's physical id then, is kept as a record. One
     * provisioned before these keys were kept has none until it changes or an update of its stack
     * commits ({@link #refreshTemplateUses}), so as a leftover before then a custom resource orders
     * by its ServiceToken alone, and a nested stack by its outputs.
     */
    private static void recordTemplateUses(StackResource resource, Set<String> dependencies,
                                           Map<String, String> physicalIds) {
        resource.getAttributes().keySet().removeIf(key -> key.startsWith(TEMPLATE_USES_PREFIX));
        for (String dependency : dependencies) {
            String physicalId = physicalIds.get(dependency);
            if (physicalId != null && !dependency.equals(resource.getLogicalId())) {
                resource.getAttributes().put(TEMPLATE_USES_PREFIX + dependency, physicalId);
            }
        }
    }

    /**
     * Keeps again, as an update commits, what each custom resource and nested stack the template
     * names now names: one whose only change is its {@code DependsOn} is not re-provisioned, so
     * {@link #recordTemplateUses} did not run for it. An update that rolls back never gets here,
     * so the keys from before stay. One whose condition is now false keeps what it was provisioned
     * with: the cleanup about to delete it may fail and leave it held. The update has committed, so
     * a failure here is logged and costs only these keys, not the cleanup that follows.
     */
    private void refreshTemplateUses(Stack stack, JsonNode resources, Map<String, Boolean> conditions,
                                     Map<String, String> physicalIds) {
        if (!resources.isObject()) {
            return;
        }
        try {
            Set<String> templateIds = new LinkedHashSet<>();
            resources.fieldNames().forEachRemaining(templateIds::add);
            for (String logicalId : templateIds) {
                JsonNode definition = resources.get(logicalId);
                String type = definition.path("Type").asText();
                String condition = definition.path("Condition").asText(null);
                StackResource resource = stack.getResources().get(logicalId);
                if (resource != null && (condition == null || conditions.getOrDefault(condition, false))
                        && ("AWS::CloudFormation::Stack".equals(type) || dispatcher.isCustomResourceType(type))) {
                    recordTemplateUses(resource, collectResourceDependencies(definition, templateIds, conditions),
                            physicalIds);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv("Stack {0} keeps the uses its resources recorded before this update: {1}",
                    stack.getStackName(), e.getMessage());
        }
    }

    /** Puts back the {@link #recordTemplateUses} keys a resource held before an update now rolled back. */
    private static void restoreTemplateUses(StackResource resource, StackResource previous) {
        resource.getAttributes().keySet().removeIf(key -> key.startsWith(TEMPLATE_USES_PREFIX));
        previous.getAttributes().forEach((key, value) -> {
            if (key.startsWith(TEMPLATE_USES_PREFIX)) {
                resource.getAttributes().put(key, value);
            }
        });
    }

    private static String newStackId(String stackName, String region, String accountId) {
        return AwsArnUtils.Arn.of("cloudformation", region, accountId, "stack/" + stackName + "/" + UUID.randomUUID()).toString();
    }

    private Stack newStack(String stackName, String stackId, String region, String accountId) {
        Stack stack = new Stack();
        stack.setStackName(stackName);
        stack.setRegion(region);
        stack.setAccountId(accountId);
        stack.setStatus("REVIEW_IN_PROGRESS");
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
        return getStackOrThrow(stackNameOrArn, region, currentAccount());
    }

    private Stack getStackOrThrow(String stackNameOrArn, String region, String accountId) {
        Stack stack = resolveStack(stackNameOrArn, region, accountId);
        if (stack == null) {
            throw new AwsException("ValidationError",
                    "Stack with id " + stackNameOrArn + " does not exist", 400);
        }
        return stack;
    }

    private Stack resolveStackForDescribe(String stackNameOrArn, String region) {
        return resolveStackForDescribe(stackNameOrArn, region, currentAccount());
    }

    private Stack resolveStackForDescribe(String stackNameOrArn, String region, String accountId) {
        Stack stack = resolveStack(stackNameOrArn, region, accountId);
        if (stack != null) {
            return stack;
        }
        return resolveDeletedStack(stackNameOrArn, region, accountId);
    }

    private Stack resolveDeletedStack(String stackNameOrArn, String region, String accountId) {
        if (stackNameOrArn != null && stackNameOrArn.startsWith("arn:")) {
            DeletedStackEntry deleted = deletedStacks.get(stackNameOrArn);
            if (deleted != null) {
                if (deleted.isExpired(now())) {
                    deletedStacks.remove(stackNameOrArn, deleted);
                    return null;
                }
                if (accountId.equals(ownerAccount(deleted.stack()))
                        && region.equals(deleted.stack().getRegion())) {
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
    private String resolveChangeSetName(String changeSetNameOrArn, String region, String accountId) {
        if (changeSetNameOrArn == null || !changeSetNameOrArn.startsWith("arn:")) {
            return changeSetNameOrArn;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(changeSetNameOrArn);
            if (!accountId.equals(arn.accountId()) || !region.equals(arn.region())) {
                throw new AwsException("ValidationError",
                        "Change set " + changeSetNameOrArn + " does not belong to this account or region", 400);
            }
            String resource = arn.resource();
            String[] parts = resource.split("/");
            if (parts.length >= 2 && "changeSet".equals(parts[0])) {
                return parts[1];
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationError",
                    "Invalid change set ARN: " + changeSetNameOrArn, 400);
        }
        throw new AwsException("ValidationError",
                "Invalid change set ARN: " + changeSetNameOrArn, 400);
    }

    private Stack getStackForChangeSet(String stackNameOrArn, String changeSetNameOrArn,
                                       String region, String accountId) {
        if (stackNameOrArn != null && !stackNameOrArn.isBlank()) {
            return getStackOrThrow(stackNameOrArn, region, accountId);
        }
        if (changeSetNameOrArn == null || !changeSetNameOrArn.startsWith("arn:")) {
            throw new AwsException("ValidationError", "StackName is required when ChangeSetName is not an ARN", 400);
        }
        Stack stack = findStackForChangeSetArn(changeSetNameOrArn, region, accountId);
        if (stack == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetNameOrArn + "] does not exist", 400);
        }
        return stack;
    }

    /** The stack holding the change set {@code changeSetArn} names, or {@code null}. */
    private Stack findStackForChangeSetArn(String changeSetArn, String region, String accountId) {
        // A change-set ARN contains its name and UUID, but not its stack name. Search only
        // stacks in the caller's account and region, and match the complete ARN so another
        // stack's change set with the same short name cannot be selected accidentally.
        String changeSetName = resolveChangeSetName(changeSetArn, region, accountId);
        for (Stack stack : stacks.values()) {
            if (accountId.equals(ownerAccount(stack)) && region.equals(stack.getRegion())) {
                ChangeSet changeSet = stack.getChangeSets().get(changeSetName);
                if (changeSet != null && changeSetArn.equals(changeSet.getChangeSetId())) {
                    return stack;
                }
            }
        }
        return null;
    }

    private ChangeSet getChangeSetOrThrow(Stack stack, String changeSetNameOrArn,
                                          String region, String accountId) {
        ChangeSet changeSet = findChangeSet(stack, changeSetNameOrArn, region, accountId);
        if (changeSet == null) {
            throw new AwsException("ChangeSetNotFoundException",
                    "ChangeSet [" + changeSetNameOrArn + "] does not exist", 400);
        }
        return changeSet;
    }

    private ChangeSet findChangeSet(Stack stack, String changeSetNameOrArn,
                                    String region, String accountId) {
        ChangeSet changeSet = stack.getChangeSets().get(
                resolveChangeSetName(changeSetNameOrArn, region, accountId));
        if (changeSet == null || (changeSetNameOrArn != null && changeSetNameOrArn.startsWith("arn:")
                && !changeSetNameOrArn.equals(changeSet.getChangeSetId()))) {
            return null;
        }
        return changeSet;
    }

    /**
     * Reads the output a {@code Fn::GetStackOutput} names, as DescribeStacks does in the
     * referenced account and Region: the consuming stack's account, or the account of
     * {@code roleArn}, a role CloudFormation has to be able to assume, which
     * {@link GetStackOutputRoleAuthorizer} decides. The failures carry the messages AWS reports for
     * them: STS's or DescribeStacks' AccessDenied for the role, DescribeStacks' ValidationError for a
     * missing stack, and a template error for a missing output.
     */
    private String resolveStackOutput(String accountId, String stackName, String outputName,
                                      String region, String roleArn) {
        String partition = AwsRegions.partitionFor(region);
        String ownerAccount = roleArn == null ? accountId : stackOutputRoles.assume(roleArn, accountId, partition);
        // CloudFormation hands the stack name to DescribeStacks as written, a dynamic reference
        // included (it resolves none inside Fn::GetStackOutput), and DescribeStacks validates it.
        if (!STACK_NAME.matcher(stackName).matches()) {
            throw new AwsException("ValidationError", CloudFormationTemplateEngine.sdkErrorMessage(
                    "1 validation error detected: Value '" + stackName + "' at 'stackName' failed to satisfy"
                            + " constraint: Member must satisfy regular expression pattern: "
                            + STACK_NAME.pattern(),
                    "AmazonCloudFormation", 400, "ValidationError"), 400);
        }
        Stack referenced = resolveStack(stackName, region, ownerAccount);
        if (referenced == null) {
            throw new AwsException("ValidationError", CloudFormationTemplateEngine.sdkErrorMessage(
                    "Stack with id " + stackName + " does not exist", "AmazonCloudFormation", 400,
                    "ValidationError"), 400);
        }
        if (roleArn != null) {
            stackOutputRoles.requireDescribeStacks(roleArn, referenced.getStackId(), partition);
        }
        String value = referenced.outputsSnapshot().get(outputName);
        if (value == null) {
            throw new AwsException("ValidationError", "TemplateError: Fn::GetStackOutput references output "
                    + outputName + " from stack " + stackName
                    + ", but this output was not found. The output may have been deleted.", 400);
        }
        return value;
    }

    /** ValidateTemplate's check of a template: one that is no template, and the null values AWS refuses there. */
    public void validateTemplate(String templateBody, String templateUrl) {
        String resolvedBody = resolveTemplateBody(templateBody, templateUrl);
        if (resolvedBody != null) {
            requireValidTemplate(resolvedBody);
        }
    }

    /**
     * The checks ValidateTemplate, CreateStack, UpdateStack, CreateChangeSet, CreateStackSet and
     * UpdateStackSet make of a template before anything is created or kept: a body that is no
     * template ({@link #readTemplate}), then its null values ({@link #requireNoNullValues}).
     */
    void requireValidTemplate(String templateBody) {
        requireNoNullValues(readTemplate(templateBody));
    }

    /**
     * Refuses a template that holds a null value anywhere, as AWS does when ValidateTemplate,
     * CreateStack, UpdateStack or CreateChangeSet is requested, before anything is created
     * (measured, eu-west-1, 2026-10-20): ValidationError "[path] 'null' values are not allowed in
     * templates", naming the first null in document order. Below a resource's Properties the path
     * says Type where Properties would be, as AWS's does; a null Properties is named as it is.
     */
    private static void requireNoNullValues(JsonNode parsed) {
        if (parsed == null || parsed.isNull()) {
            // Measured: a template that is null as a whole ("null", "~") lacks its resources first.
            throw new AwsException("ValidationError",
                    "Template format error: At least one Resources member must be defined.", 400);
        }
        String path = firstNullPath(parsed, "", false);
        if (path != null) {
            throw new AwsException("ValidationError",
                    "[" + path + "] 'null' values are not allowed in templates", 400);
        }
    }

    private static String firstNullPath(JsonNode node, String path, boolean isResource) {
        if (node.isNull()) {
            return path;
        }
        if (node.isObject()) {
            boolean childIsResource = "/Resources".equals(path);
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String name = isResource && "Properties".equals(member.getKey()) && !member.getValue().isNull()
                        ? "Type" : member.getKey();
                String found = firstNullPath(member.getValue(), path + "/" + name, childIsResource);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String found = firstNullPath(node.get(i), path + "/" + i, false);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The places AWS refuses {@code Fn::GetStackOutput} when the stack operation is requested,
     * before anything runs (measured, eu-west-1, 2026-10-04 and 2026-10-17): anywhere in the
     * Conditions section, inside an {@code Fn::ImportValue}, and in an output's
     * {@code Export.Name}, neither of which may depend on another stack. A nested stack's template
     * is checked when the child is created, and the child's resource fails with the same message.
     */
    private void validateGetStackOutputPlacement(String templateBody) {
        JsonNode parsed;
        try {
            parsed = parseTemplate(templateBody);
        } catch (Exception e) {
            // As validateResourceDependencies: a template that does not parse fails where it is
            // processed, with the parser's error.
            LOG.debugv("Skipping Fn::GetStackOutput placement validation; template did not parse: {0}",
                    e.getMessage());
            return;
        }
        JsonNode template;
        try {
            // The rules hold for the template as processed: an AWS::Include fragment can carry it.
            template = awsIncludeProcessor.mergeIncludes(parsed);
        } catch (AwsException e) {
            // A fragment that cannot be read fails the operation as the transform does, where the
            // includes are processed; the request itself is not refused for it on AWS.
            LOG.debugv("Skipping Fn::GetStackOutput placement validation; an AWS::Include failed: {0}",
                    e.getMessage());
            return;
        }
        if (CloudFormationTemplateEngine.containsGetStackOutput(template.path("Conditions"))) {
            throw new AwsException("ValidationError",
                    "Template error: Cannot use Fn::GetStackOutput in Conditions.", 400);
        }
        if (template.findValues("Fn::ImportValue").stream()
                .anyMatch(CloudFormationTemplateEngine::containsGetStackOutput)) {
            throw new AwsException("ValidationError", "Template error: the attribute in Fn::ImportValue must not"
                    + " depend on any resources, imported values, or Fn::GetAZs", 400);
        }
        for (Map.Entry<String, JsonNode> output : template.path("Outputs").properties()) {
            if (CloudFormationTemplateEngine.containsGetStackOutput(output.getValue().path("Export").path("Name"))) {
                throw new AwsException("ValidationError", "Template format error: Output " + output.getKey()
                        + " is malformed.  The Name field of Export must not depend on any resources, imported"
                        + " values, or Fn::GetAZs.", 400);
            }
        }
        requireGetStackOutputArguments(template);
    }

    /**
     * Refuses a {@code Fn::GetStackOutput} without the map of parameters it takes, or without its
     * StackName or OutputName, when the stack operation is requested, with the messages AWS gives
     * (measured, eu-west-1, 2026-10-16). A value of the wrong type is DescribeStacks' to refuse.
     */
    private static void requireGetStackOutputArguments(JsonNode template) {
        for (JsonNode arguments : template.findValues("Fn::GetStackOutput")) {
            if (!arguments.isObject()) {
                throw new AwsException("ValidationError",
                        "TemplateError: Fn::GetStackOutput requires a map of named parameters", 400);
            }
            for (String required : List.of("StackName", "OutputName")) {
                if (!arguments.has(required)) {
                    throw new AwsException("ValidationError", "GetStackOutput: " + required + " is required", 400);
                }
            }
            // findValues does not look inside what it found; a parameter may hold another reference.
            requireGetStackOutputArguments(arguments);
        }
    }

    /**
     * Resolves a live stack by name or ARN. An ARN is a stack id and resolves only to the stack
     * carrying that exact id, never to a newer stack that reused the name.
     */
    private Stack resolveStack(String stackNameOrArn, String region) {
        return resolveStack(stackNameOrArn, region, currentAccount());
    }

    private Stack resolveStack(String stackNameOrArn, String region, String accountId) {
        // Try direct name lookup first (fast path)
        Stack stack = stacks.get(stackKey(accountId, stackNameOrArn, region));
        if (stack != null) {
            return stack;
        }

        // If input looks like an ARN, extract the stack name and retry
        if (stackNameOrArn != null && stackNameOrArn.startsWith("arn:")) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(stackNameOrArn);
                if (!accountId.equals(arn.accountId()) || !region.equals(arn.region())) {
                    return null;
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
            String extractedName = extractStackNameFromArn(stackNameOrArn);
            if (extractedName != null) {
                stack = stacks.get(stackKey(accountId, extractedName, region));
                // A stack id names one stack: a live stack that reused the name is a different
                // stack, so the id resolves to it only when the ids match.
                if (stack != null && stackNameOrArn.equals(stack.getStackId())) {
                    return stack;
                }
            }
            // Fallback: scan by stackId in case the ARN format is unexpected
            for (Stack s : stacks.values()) {
                if (accountId.equals(ownerAccount(s)) && stackNameOrArn.equals(s.getStackId())) {
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
        // A resource left unsorted is in a dependency cycle or depends on one, so no creation
        // order exists; AWS rejects such a template instead of creating it in an arbitrary order.
        CreationOrder order = creationOrder(resources, conditions);
        if (!order.circular().isEmpty()) {
            throw new AwsException("ValidationError",
                    "Circular dependency between resources: [" + String.join(", ", order.circular()) + "]", 400);
        }
        return order.sorted();
    }

    /**
     * The template's resources, dependencies first, and those in a dependency cycle. Resources in or
     * depending on a cycle are still placed in {@code sorted}, each cycle after the cycles it depends
     * on, so every dependency outside a cycle keeps its order.
     */
    private record CreationOrder(List<String> sorted, Set<String> circular) {
    }

    private CreationOrder creationOrder(JsonNode resources, Map<String, Boolean> conditions) {
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

        // Every resource left waits on another one left. Ordering their strongly connected
        // components dependencies first still respects every dependency outside a cycle; only the
        // components that are cycles themselves are circular.
        Set<String> circular = new LinkedHashSet<>();
        if (sorted.size() < activeIds.size()) {
            Set<String> placed = new HashSet<>(sorted);
            for (List<String> component : componentsDependenciesFirst(activeIds, placed, dependencies)) {
                sorted.addAll(component);
                String first = component.get(0);
                if (component.size() > 1 || dependencies.get(first).contains(first)) {
                    circular.addAll(component);
                }
            }
        }
        return new CreationOrder(sorted, circular);
    }

    /**
     * The strongly connected components of the resources not yet placed, each after every component
     * it depends on: Tarjan's algorithm completes a component only once those are complete. The
     * search keeps its own stack so a long dependency chain cannot exhaust the thread's.
     */
    private static List<List<String>> componentsDependenciesFirst(Set<String> activeIds, Set<String> placed,
                                                                  Map<String, Set<String>> dependencies) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> open = new ArrayDeque<>();
        Set<String> onOpen = new HashSet<>();
        List<List<String>> components = new ArrayList<>();
        Deque<Map.Entry<String, Iterator<String>>> path = new ArrayDeque<>();
        for (String root : activeIds) {
            if (placed.contains(root) || index.containsKey(root)) {
                continue;
            }
            String next = root;
            while (next != null || !path.isEmpty()) {
                if (next != null) {
                    index.put(next, index.size());
                    lowLink.put(next, index.get(next));
                    open.push(next);
                    onOpen.add(next);
                    path.push(Map.entry(next, dependencies.get(next).iterator()));
                    next = null;
                }
                String id = path.peek().getKey();
                Iterator<String> deps = path.peek().getValue();
                if (deps.hasNext()) {
                    String dep = deps.next();
                    if (!dependencies.containsKey(dep) || placed.contains(dep)) {
                        continue;
                    }
                    if (!index.containsKey(dep)) {
                        next = dep;
                    } else if (onOpen.contains(dep)) {
                        lowLink.merge(id, index.get(dep), Math::min);
                    }
                    continue;
                }
                path.pop();
                if (!path.isEmpty()) {
                    lowLink.merge(path.peek().getKey(), lowLink.get(id), Math::min);
                }
                if (lowLink.get(id).equals(index.get(id))) {
                    List<String> component = new ArrayList<>();
                    String member;
                    do {
                        member = open.pop();
                        onOpen.remove(member);
                        component.add(member);
                    } while (!member.equals(id));
                    components.add(component);
                }
            }
        }
        return components;
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

    private Set<String> expectedChangedAttributes(String resourceType, JsonNode oldDef, JsonNode newDef) {
        Set<String> changedAttrs = new HashSet<>();
        if ("AWS::EC2::LaunchTemplate".equals(resourceType)) {
            changedAttrs.add("LatestVersionNumber");
        }
        JsonNode oldProps = oldDef != null ? oldDef.path("Properties") : null;
        JsonNode newProps = newDef != null ? newDef.path("Properties") : null;
        if (oldProps != null && newProps != null && oldProps.isObject() && newProps.isObject()) {
            newProps.fieldNames().forEachRemaining(field -> {
                if (!Objects.equals(newProps.get(field), oldProps.get(field))) {
                    changedAttrs.add(field);
                }
            });
        }
        return changedAttrs;
    }

    /**
     * Checks whether a template node references any changed attribute of targetLogicalId
     * (via Fn::GetAtt or Fn::Sub). Used to pull in dependent resources when a resource
     * is updated in-place with modified attributes (e.g. LatestVersionNumber of LaunchTemplate).
     */
    private boolean referencesAnyAttribute(JsonNode node, String targetLogicalId, Set<String> targetAttrNames,
                                           Map<String, Boolean> conditions) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("Fn::GetAtt")) {
                JsonNode getAtt = node.get("Fn::GetAtt");
                String logicalId = null;
                if (getAtt.isArray() && getAtt.size() >= 2) {
                    logicalId = getAtt.get(0).asText();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < getAtt.size(); i++) {
                        if (i > 1) {
                            sb.append('.');
                        }
                        sb.append(getAtt.get(i).asText());
                    }
                    if (targetLogicalId.equals(logicalId)
                            && (targetAttrNames.contains(sb.toString()) || targetAttrNames.contains(getAtt.get(1).asText()))) {
                        return true;
                    }
                } else if (getAtt.isTextual()) {
                    String[] parts = getAtt.textValue().split("\\.", 2);
                    logicalId = parts[0];
                    String attrName = parts.length > 1 ? parts[1] : null;
                    if (targetLogicalId.equals(logicalId) && attrName != null && targetAttrNames.contains(attrName)) {
                        return true;
                    }
                }
            }
            if (node.has("Fn::If")) {
                JsonNode fnIf = node.get("Fn::If");
                if (fnIf.isArray() && fnIf.size() == 3) {
                    if (conditions != null && conditions.containsKey(fnIf.get(0).asText())) {
                        boolean condition = conditions.get(fnIf.get(0).asText());
                        return referencesAnyAttribute(fnIf.get(condition ? 1 : 2), targetLogicalId, targetAttrNames, conditions);
                    }
                    return referencesAnyAttribute(fnIf.get(1), targetLogicalId, targetAttrNames, conditions)
                            || referencesAnyAttribute(fnIf.get(2), targetLogicalId, targetAttrNames, conditions);
                }
            }
            if (node.has("Fn::Sub")) {
                if (subReferencesAttribute(node.get("Fn::Sub"), targetLogicalId, targetAttrNames, conditions)) {
                    return true;
                }
            }
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                if (referencesAnyAttribute(it.next(), targetLogicalId, targetAttrNames, conditions)) {
                    return true;
                }
            }
            return false;
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (referencesAnyAttribute(item, targetLogicalId, targetAttrNames, conditions)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean subReferencesAttribute(JsonNode sub, String targetLogicalId, Set<String> targetAttrNames,
                                           Map<String, Boolean> conditions) {
        String template;
        Set<String> explicitVars = new HashSet<>();

        if (sub.isTextual()) {
            template = sub.textValue();
        } else if (sub.isArray() && sub.size() >= 1) {
            template = sub.get(0).asText();
            if (sub.size() >= 2 && sub.get(1).isObject()) {
                sub.get(1).fieldNames().forEachRemaining(explicitVars::add);
                if (referencesAnyAttribute(sub.get(1), targetLogicalId, targetAttrNames, conditions)) {
                    return true;
                }
            }
        } else {
            return false;
        }

        Matcher matcher = SUB_VAR_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (varName.startsWith("AWS::") || explicitVars.contains(varName)) {
                continue;
            }
            int dot = varName.indexOf('.');
            if (dot > 0) {
                String resourcePart = varName.substring(0, dot);
                String attrPart = varName.substring(dot + 1);
                if (targetLogicalId.equals(resourcePart) && targetAttrNames.contains(attrPart)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stackStorageKey(String stackName, String region) {
        return region + ":" + stackName;
    }

    private static String stackKey(String accountId, String stackName, String region) {
        return accountId + ":" + region + ":" + stackName;
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Stack stack : stacks.values()) {
            String arn = stack.getStackId();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "cloudformation:stack", "cloudformation",
                    parsed.region(), parsed.accountId(),
                    stack.getCreationTime() != null ? stack.getCreationTime() : Instant.now(),
                    stack.tagsSnapshot()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("cloudformation:stack", "cloudformation", true));
    }
}
