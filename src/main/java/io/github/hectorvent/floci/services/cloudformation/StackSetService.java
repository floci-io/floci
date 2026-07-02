package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackInstance;
import io.github.hectorvent.floci.services.cloudformation.model.StackSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * CloudFormation StackSets — manages stack sets and provisions their instances across target
 * accounts and regions.
 *
 * <p>A StackSet holds a template and configuration; {@code CreateStackInstances} drives the existing
 * single-stack engine ({@link CloudFormationService}) once per target {@code (account, region)},
 * provisioning the resources into each target account's namespace. The StackSet, instance and
 * operation records themselves live in the administration (caller) account's namespace.
 */
@ApplicationScoped
public class StackSetService {

    private static final Logger LOG = Logger.getLogger(StackSetService.class);
    private static final String INSTANCE_CHANGE_SET = "stackset-instance";
    private static final String UPDATE_CHANGE_SET = "stackset-update";

    private final CloudFormationService cfnService;
    private final StorageBackend<String, StackSet> stackSets;
    private final StorageBackend<String, StackInstance> instances;
    private final StorageBackend<String, StackSetOperation> operations;

    @Inject
    public StackSetService(CloudFormationService cfnService, StorageFactory storageFactory) {
        this.cfnService = cfnService;
        this.stackSets = storageFactory.create("cloudformation", "cloudformation-stacksets.json",
                new TypeReference<Map<String, StackSet>>() {});
        this.instances = storageFactory.create("cloudformation", "cloudformation-stackset-instances.json",
                new TypeReference<Map<String, StackInstance>>() {});
        this.operations = storageFactory.create("cloudformation", "cloudformation-stackset-operations.json",
                new TypeReference<Map<String, StackSetOperation>>() {});
    }

    // ── StackSet lifecycle ─────────────────────────────────────────────────────

    public StackSet createStackSet(String name, String templateBody, Map<String, String> parameters,
                                   List<String> capabilities, Map<String, String> tags, String description) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationError", "StackSetName must not be empty", 400);
        }
        if (stackSets.get(name).isPresent()) {
            throw new AwsException("NameAlreadyExistsException",
                    "StackSet already exists: " + name, 409);
        }
        // AWS rejects CreateStackSet with no template; the handler resolves TemplateBody/TemplateURL
        // to null when neither is supplied. Without this guard a later CreateStackInstances would
        // deploy empty ("{}") stacks into every target account.
        if (templateBody == null || templateBody.isBlank()) {
            throw new AwsException("ValidationError",
                    "Either TemplateBody or TemplateURL must be specified", 400);
        }
        StackSet ss = new StackSet();
        ss.setStackSetName(name);
        ss.setStackSetId(name + ":" + UUID.randomUUID());
        ss.setTemplateBody(templateBody);
        ss.setDescription(description);
        if (parameters != null) {
            ss.setParameters(new LinkedHashMap<>(parameters));
        }
        if (capabilities != null) {
            ss.getCapabilities().addAll(capabilities);
        }
        if (tags != null) {
            ss.getTags().putAll(tags);
        }
        stackSets.put(name, ss);
        LOG.infov("Created StackSet: {0}", name);
        return ss;
    }

    public StackSet describeStackSet(String name) {
        return getStackSetOrThrow(name);
    }

    public List<StackSet> listStackSets() {
        return stackSets.scan(k -> true);
    }

    public StackSetOperation updateStackSet(String name, String templateBody, Map<String, String> parameters,
                                            List<String> capabilities, Map<String, String> tags,
                                            String description) {
        StackSet ss = getStackSetOrThrow(name);
        if (templateBody != null && !templateBody.isBlank()) {
            ss.setTemplateBody(templateBody);
        }
        if (parameters != null && !parameters.isEmpty()) {
            ss.setParameters(new LinkedHashMap<>(parameters));
        }
        if (capabilities != null && !capabilities.isEmpty()) {
            ss.getCapabilities().clear();
            ss.getCapabilities().addAll(capabilities);
        }
        if (tags != null && !tags.isEmpty()) {
            ss.getTags().clear();
            ss.getTags().putAll(tags);
        }
        if (description != null) {
            ss.setDescription(description);
        }
        stackSets.put(name, ss);

        // Re-apply the updated definition to every existing instance, refreshing each record.
        List<StackInstance> deployed = new ArrayList<>();
        for (StackInstance inst : listStackInstances(name, null, null)) {
            StackInstance updated = deployInstance(ss, inst.getAccount(), inst.getRegion(), UPDATE_CHANGE_SET, "UPDATE");
            instances.put(instanceKey(name, updated.getAccount(), updated.getRegion()), updated);
            deployed.add(updated);
        }
        return recordOperation(name, "UPDATE", deriveOperationStatus(deployed));
    }

    public void deleteStackSet(String name) {
        getStackSetOrThrow(name);
        if (!listStackInstances(name, null, null).isEmpty()) {
            throw new AwsException("StackSetNotEmptyException",
                    "StackSet is not empty. Delete all stack instances first.", 409);
        }
        stackSets.delete(name);
        LOG.infov("Deleted StackSet: {0}", name);
    }

    // ── Instances ──────────────────────────────────────────────────────────────

    public StackSetOperation createStackInstances(String name, List<String> accounts, List<String> regions) {
        StackSet ss = getStackSetOrThrow(name);
        if (accounts == null || accounts.isEmpty() || regions == null || regions.isEmpty()) {
            throw new AwsException("ValidationError",
                    "Accounts and Regions must each contain at least one value", 400);
        }
        List<StackInstance> deployed = new ArrayList<>();
        for (String account : accounts) {
            for (String region : regions) {
                StackInstance inst = deployInstance(ss, account, region, INSTANCE_CHANGE_SET, "CREATE");
                instances.put(instanceKey(name, account, region), inst);
                deployed.add(inst);
            }
        }
        return recordOperation(name, "CREATE", deriveOperationStatus(deployed));
    }

    public List<StackInstance> listStackInstances(String name, String accountFilter, String regionFilter) {
        getStackSetOrThrow(name);
        String prefix = name + ":";
        return instances.scan(k -> k.startsWith(prefix)).stream()
                .filter(i -> accountFilter == null || accountFilter.equals(i.getAccount()))
                .filter(i -> regionFilter == null || regionFilter.equals(i.getRegion()))
                .toList();
    }

    public StackInstance describeStackInstance(String name, String account, String region) {
        getStackSetOrThrow(name);
        if (account == null || region == null) {
            throw new AwsException("ValidationError",
                    "StackInstanceAccount and StackInstanceRegion are required", 400);
        }
        return instances.get(instanceKey(name, account, region))
                .orElseThrow(() -> new AwsException("StackInstanceNotFoundException",
                        "Stack instance for [" + account + "/" + region + "] not found in stack set " + name, 404));
    }

    public StackSetOperation deleteStackInstances(String name, List<String> accounts, List<String> regions,
                                                  boolean retainStacks) {
        getStackSetOrThrow(name);
        if (accounts == null || accounts.isEmpty() || regions == null || regions.isEmpty()) {
            throw new AwsException("ValidationError",
                    "Accounts and Regions must each contain at least one value", 400);
        }
        List<StackInstance> results = new ArrayList<>();
        for (String account : accounts) {
            for (String region : regions) {
                String key = instanceKey(name, account, region);
                StackInstance inst = instances.get(key).orElse(null);
                if (inst == null) {
                    continue;
                }
                // RetainStacks=true detaches the instance from the StackSet but leaves the
                // underlying CloudFormation stack and its resources in the target account.
                if (!retainStacks && !await(cfnService.deleteStack(inst.getStackName(), region, account))) {
                    // The underlying stack delete failed. Match AWS: retain the instance record
                    // (now INOPERABLE) and report the operation as FAILED, rather than silently
                    // dropping the instance and claiming success.
                    inst.setStatus("INOPERABLE");
                    inst.setDetailedStatus("FAILED");
                    inst.setStatusReason("Stack instance deletion failed");
                    instances.put(key, inst);
                    results.add(inst);
                    continue;
                }
                inst.setDetailedStatus("SUCCEEDED");
                instances.delete(key);
                results.add(inst);
            }
        }
        return recordOperation(name, "DELETE", deriveOperationStatus(results));
    }

    public List<StackSetOperation> listStackSetOperations(String name) {
        getStackSetOrThrow(name);
        String prefix = name + ":";
        return operations.scan(k -> k.startsWith(prefix)).stream()
                .sorted((a, b) -> b.getCreationTimestamp().compareTo(a.getCreationTimestamp()))
                .toList();
    }

    public StackSetOperation describeStackSetOperation(String name, String operationId) {
        getStackSetOrThrow(name);
        if (operationId == null || operationId.isBlank()) {
            throw new AwsException("ValidationError", "OperationId is required", 400);
        }
        return operations.get(name + ":" + operationId)
                .orElseThrow(() -> new AwsException("OperationNotFoundException",
                        "Operation " + operationId + " not found for stack set " + name, 404));
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    /**
     * Drives the single-stack engine to materialize one instance's resources into the target
     * account's namespace, then returns a {@link StackInstance} describing it.
     */
    private StackInstance deployInstance(StackSet ss, String account, String region,
                                         String changeSetName, String changeSetType) {
        String stackName = instanceStackName(ss.getStackSetName(), account);
        cfnService.createChangeSet(stackName, changeSetName, changeSetType, ss.getTemplateBody(), null,
                ss.getParameters(), ss.getCapabilities(), ss.getTags(), region);
        await(cfnService.executeChangeSet(stackName, changeSetName, region, account));

        StackInstance inst = new StackInstance();
        inst.setStackSetId(ss.getStackSetId());
        inst.setStackSetName(ss.getStackSetName());
        inst.setAccount(account);
        inst.setRegion(region);
        inst.setStackName(stackName);
        inst.setStackId(resolveStackId(stackName, region, account));
        List<Stack> stacks = cfnService.describeStacks(stackName, region);
        String stackStatus = stacks.isEmpty() ? null : stacks.get(0).getStatus();
        // Only a clean create/update is a success. A failed resource rolls the stack back, so its
        // terminal status is ROLLBACK_COMPLETE (not *_FAILED) — treat anything that is not COMPLETE
        // as a failed instance so the operation status reflects it.
        if (!"CREATE_COMPLETE".equals(stackStatus) && !"UPDATE_COMPLETE".equals(stackStatus)) {
            inst.setStatus("INOPERABLE");
            inst.setDetailedStatus("FAILED");
            inst.setStatusReason(stacks.isEmpty() ? null : stacks.get(0).getStatusReason());
        } else {
            inst.setDetailedStatus("SUCCEEDED");
        }
        return inst;
    }

    private String resolveStackId(String stackName, String region, String account) {
        List<Stack> stacks = cfnService.describeStacks(stackName, region);
        if (!stacks.isEmpty() && stacks.get(0).getStackId() != null) {
            return stacks.get(0).getStackId();
        }
        return AwsArnUtils.Arn.of("cloudformation", region, account, "stack/" + stackName).toString();
    }

    private StackSetOperation recordOperation(String stackSetName, String action, String status) {
        StackSetOperation op = new StackSetOperation(UUID.randomUUID().toString(), stackSetName, action);
        op.setStatus(status);
        op.setEndTimestamp(Instant.now());
        operations.put(stackSetName + ":" + op.getOperationId(), op);
        return op;
    }

    /**
     * An operation is FAILED if any of its instances did not deploy cleanly; otherwise SUCCEEDED.
     * Without this, a failed (INOPERABLE) instance would still report SUCCEEDED to anything polling
     * {@code DescribeStackSetOperation}.
     */
    private static String deriveOperationStatus(List<StackInstance> deployedInstances) {
        boolean anyFailed = deployedInstances.stream()
                .anyMatch(i -> "FAILED".equals(i.getDetailedStatus()));
        return anyFailed ? "FAILED" : "SUCCEEDED";
    }

    private StackSet getStackSetOrThrow(String name) {
        return stackSets.get(name)
                .orElseThrow(() -> new AwsException("StackSetNotFoundException",
                        "StackSet [" + name + "] not found", 404));
    }

    private static String instanceStackName(String stackSetName, String account) {
        return "StackSet-" + stackSetName + "-" + account;
    }

    private static String instanceKey(String stackSetName, String account, String region) {
        return stackSetName + ":" + account + ":" + region;
    }

    /**
     * Waits for an instance operation to complete. Returns {@code true} if it finished cleanly and
     * {@code false} if it failed (the failure is logged). Callers that must react to a failure —
     * {@link #deleteStackInstances} cannot drop an instance whose underlying stack delete failed —
     * inspect the result; fire-and-forget callers can ignore it.
     */
    private boolean await(Future<?> future) {
        try {
            future.get();
            return true;
        } catch (InterruptedException e) {
            // Restore the interrupt flag so a shutdown signal (e.g. Quarkus interrupting the backing
            // ExecutorService) propagates instead of being swallowed and letting the thread run on.
            Thread.currentThread().interrupt();
            LOG.warnv("StackSet instance execution interrupted: {0}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.warnv("StackSet instance execution failed: {0}", e.getMessage());
            return false;
        }
    }
}
