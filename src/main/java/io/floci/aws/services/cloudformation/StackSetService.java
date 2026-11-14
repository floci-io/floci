package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.ChangeSet;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackInstance;
import io.floci.aws.services.cloudformation.model.StackSet;
import io.floci.aws.services.cloudformation.model.StackSetAutoDeploymentTarget;
import io.floci.aws.services.cloudformation.model.StackSetOperation;
import io.floci.aws.services.iam.PassedRoleLookup;
import io.floci.aws.services.organizations.AccountPlacementListener;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import io.floci.aws.services.organizations.model.OrganizationalUnit;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * CloudFormation StackSets — manages stack sets and provisions their instances across target
 * accounts and regions.
 *
 * <p>A StackSet holds a template and configuration; {@code CreateStackInstances} drives the existing
 * single-stack engine ({@link CloudFormationService}) once per target {@code (account, region)},
 * provisioning the resources into each target account's namespace. The StackSet, instance and
 * operation records themselves live in the administration (caller) account's namespace.
 *
 * <p>A service-managed stack set with automatic deployment follows the accounts of the organization: an
 * account created in, invited into or moved under one of its targets gets an instance in every Region
 * that target was given, and one no target covers any more loses its instances.
 */
@ApplicationScoped
public class StackSetService implements PassedRoleLookup, AccountPlacementListener {

    private static final Logger LOG = Logger.getLogger(StackSetService.class);
    private static final String STACKSETS_SERVICE_PRINCIPAL = ServicePrincipals.of("stacksets.cloudformation");
    private static final String INSTANCE_CHANGE_SET = "stackset-instance";
    private static final String UPDATE_CHANGE_SET = "stackset-update";
    private static final String ORGANIZATIONS_ACCESS_KEY = "organizations-access";
    private static final int STACK_SET_LIMIT = 1000;
    private static final Pattern STACK_SET_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,127}");
    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9][-A-Za-z0-9]{0,127}");
    private static final Pattern OU_OR_ROOT_ID = Pattern.compile("(?:ou-[a-z0-9]{4,32}-[a-z0-9]{8,32}|r-[a-z0-9]{4,32})");
    private static final Pattern REGION_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final CloudFormationService cfnService;
    private final StorageBackend<String, StackSet> stackSets;
    private final StorageBackend<String, StackInstance> instances;
    private final StorageBackend<String, StackSetOperation> operations;
    private final StorageBackend<String, String> organizationsAccess;
    private final StorageBackend<String, StackSetAutoDeploymentTarget> autoDeploymentTargets;
    private final OrganizationsService organizationsService;
    private final RegionResolver regionResolver;
    private final ExecutorService accountPlacementExecutor = newAccountPlacementExecutor();

    private static ExecutorService newAccountPlacementExecutor() {
        ThreadFactory factory = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
                .name("floci-stack-set-auto-deployment-", 0).factory();
        return Executors.newSingleThreadExecutor(task -> {
            Thread worker = factory.newThread(task);
            worker.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            return worker;
        });
    }

    StackSetService(CloudFormationService cfnService, StorageFactory storageFactory) {
        this(cfnService, storageFactory, null, new RegionResolver("us-east-1", "000000000000")); // partition-literal: test-shaped constructor default
    }

    @Inject
    public StackSetService(CloudFormationService cfnService, StorageFactory storageFactory,
                           OrganizationsService organizationsService, RegionResolver regionResolver) {
        this.cfnService = cfnService;
        this.stackSets = storageFactory.create("cloudformation", "cloudformation-stacksets.json",
                new TypeReference<Map<String, StackSet>>() {});
        this.instances = storageFactory.create("cloudformation", "cloudformation-stackset-instances.json",
                new TypeReference<Map<String, StackInstance>>() {});
        this.operations = storageFactory.create("cloudformation", "cloudformation-stackset-operations.json",
                new TypeReference<Map<String, StackSetOperation>>() {});
        this.organizationsAccess = storageFactory.create("cloudformation", "cloudformation-organizations-access.json",
                new TypeReference<Map<String, String>>() {});
        this.autoDeploymentTargets = storageFactory.create("cloudformation", "cloudformation-stackset-auto-targets.json",
                new TypeReference<Map<String, StackSetAutoDeploymentTarget>>() {});
        this.organizationsService = organizationsService;
        this.regionResolver = regionResolver;
    }

    // ── StackSet lifecycle ─────────────────────────────────────────────────────

    public StackSet createStackSet(String name, String templateBody, Map<String, String> parameters,
                                   List<String> capabilities, Map<String, String> tags, String description) {
        return createStackSet(name, templateBody, parameters, capabilities, tags, description,
                "SELF_MANAGED", false, false, false);
    }

    public synchronized StackSet createStackSet(String name, String templateBody, Map<String, String> parameters,
                                                List<String> capabilities, Map<String, String> tags, String description,
                                                String permissionModel, boolean autoDeploymentEnabled,
                                                boolean retainStacksOnAccountRemoval, boolean managedExecutionActive) {
        validateStackSetName(name);
        if (stackSets.get(name).isPresent()) {
            throw new AwsException("NameAlreadyExistsException", "StackSet already exists: " + name, 409);
        }
        if (stackSets.scan(key -> true).size() >= STACK_SET_LIMIT) {
            throw new AwsException("LimitExceededException", "The StackSet quota has been reached.", 400);
        }
        if (templateBody == null || templateBody.isBlank()) {
            throw new AwsException("ValidationError", "Either TemplateBody or TemplateURL must be specified", 400);
        }
        if (templateBody.length() > 51_200) {
            throw new AwsException("ValidationError", "TemplateBody must be no larger than 51200 bytes.", 400);
        }
        if (description != null && (description.isBlank() || description.length() > 1024)) {
            throw new AwsException("ValidationError", "Description must be between 1 and 1024 characters.", 400);
        }
        if (tags != null && tags.size() > 50) {
            throw new AwsException("ValidationError", "A StackSet can have at most 50 tags.", 400);
        }
        String resolvedPermissionModel = permissionModel == null || permissionModel.isBlank()
                ? "SELF_MANAGED" : permissionModel;
        if (!Set.of("SELF_MANAGED", "SERVICE_MANAGED").contains(resolvedPermissionModel)) {
            throw new AwsException("ValidationError", "PermissionModel must be SELF_MANAGED or SERVICE_MANAGED.", 400);
        }
        if ("SERVICE_MANAGED".equals(resolvedPermissionModel) && !isOrganizationsAccessEnabled()) {
            throw new AwsException("ValidationError",
                    "Activate trusted access with AWS Organizations before creating a service-managed StackSet.", 400);
        }
        if ("SELF_MANAGED".equals(resolvedPermissionModel) && (autoDeploymentEnabled || retainStacksOnAccountRemoval)) {
            throw new AwsException("ValidationError", "AutoDeployment is valid only for SERVICE_MANAGED StackSets.", 400);
        }
        requireValidTemplate(templateBody);
        // Every instance is deployed with these parameters, so a template parameter left without a
        // value would fail each one; refuse the stack set instead.
        cfnService.requireParameterValues(templateBody, parameters);

        StackSet ss = new StackSet();
        ss.setStackSetName(name);
        ss.setStackSetId(name + ":" + UUID.randomUUID());
        ss.setTemplateBody(templateBody);
        ss.setDescription(description);
        ss.setPermissionModel(resolvedPermissionModel);
        ss.setAutoDeploymentEnabled(autoDeploymentEnabled);
        ss.setRetainStacksOnAccountRemoval(retainStacksOnAccountRemoval);
        ss.setManagedExecutionActive(managedExecutionActive);
        if (parameters != null) ss.setParameters(new LinkedHashMap<>(parameters));
        if (capabilities != null) ss.getCapabilities().addAll(capabilities);
        if (tags != null) ss.getTags().putAll(tags);
        stackSets.put(name, ss);
        LOG.infov("Created StackSet: {0}", name);
        return ss;
    }

    public boolean isOrganizationsAccessEnabled() {
        return "ENABLED".equals(organizationsAccess.get(ORGANIZATIONS_ACCESS_KEY).orElse("DISABLED"));
    }

    public synchronized void activateOrganizationsAccess() {
        String caller = regionResolver.getAccountId();
        try {
            Organization organization = organizationsService.describeOrganization(caller);
            if (!caller.equals(organization.getMasterAccountId())) {
                throw new AwsException("InvalidOperationException",
                        "Only the organization management account can activate trusted access.", 400);
            }
            if (!"ALL".equals(organization.getFeatureSet())) {
                throw new AwsException("InvalidOperationException",
                        "AWS Organizations must have all features enabled before activating trusted access.", 400);
            }
            organizationsService.enableAWSServiceAccess(caller, STACKSETS_SERVICE_PRINCIPAL);
        } catch (AwsException e) {
            if ("InvalidOperationException".equals(e.getErrorCode())) throw e;
            throw new AwsException("InvalidOperationException",
                    "The calling account must be the management account of an AWS Organization.", 400);
        }
        organizationsAccess.put(ORGANIZATIONS_ACCESS_KEY, "ENABLED");
    }

    /** The id of the stack set of this name, reading only. */
    public Optional<String> stackSetId(String name) {
        return stackSets.get(name).map(StackSet::getStackSetId);
    }

    public StackSet describeStackSet(String name) {
        return getStackSetOrThrow(name);
    }

    /** The StackSet's permission model, which says whether an update naming no administration role passes the default one. */
    @Override
    public Optional<String> stackSetPermissionModel(String stackSetName) {
        return stackSets.get(stackSetName).map(StackSet::getPermissionModel);
    }

    public List<StackSet> listStackSets() {
        return stackSets.scan(k -> true);
    }

    public StackSetOperation updateStackSet(String name, String templateBody, Map<String, String> parameters,
                                            List<String> capabilities, Map<String, String> tags,
                                            String description) {
        return updateStackSet(name, templateBody, parameters, capabilities, tags, description,
                null, null, null, null);
    }

    public synchronized StackSetOperation updateStackSet(String name, String templateBody,
                                                         Map<String, String> parameters,
                                                         List<String> capabilities, Map<String, String> tags,
                                                         String description, String requestedOperationId,
                                                         Boolean autoDeploymentEnabled,
                                                         Boolean retainStacksOnAccountRemoval,
                                                         Boolean managedExecutionActive) {
        return updateStackSet(name, templateBody, parameters, capabilities, tags, description, requestedOperationId,
                autoDeploymentEnabled, retainStacksOnAccountRemoval, managedExecutionActive, false);
    }

    public synchronized StackSetOperation updateStackSetResource(String name, String templateBody,
                                                                 Map<String, String> parameters,
                                                                 List<String> capabilities, Map<String, String> tags,
                                                                 String description, Boolean autoDeploymentEnabled,
                                                                 Boolean retainStacksOnAccountRemoval,
                                                                 Boolean managedExecutionActive) {
        return updateStackSet(name, templateBody, parameters, capabilities, tags, description, null,
                autoDeploymentEnabled, retainStacksOnAccountRemoval, managedExecutionActive, true);
    }

    private StackSetOperation updateStackSet(String name, String templateBody, Map<String, String> parameters,
                                             List<String> capabilities, Map<String, String> tags, String description,
                                             String requestedOperationId, Boolean autoDeploymentEnabled,
                                             Boolean retainStacksOnAccountRemoval, Boolean managedExecutionActive,
                                             boolean replaceSettings) {
        StackSet ss = getStackSetOrThrow(name);
        if (replaceSettings) {
            if ("SERVICE_MANAGED".equals(ss.getPermissionModel())) {
                autoDeploymentEnabled = Boolean.TRUE.equals(autoDeploymentEnabled);
            }
            retainStacksOnAccountRemoval = Boolean.TRUE.equals(retainStacksOnAccountRemoval);
            managedExecutionActive = Boolean.TRUE.equals(managedExecutionActive);
        }
        if (autoDeploymentEnabled != null && !"SERVICE_MANAGED".equals(ss.getPermissionModel())) {
            throw new AwsException("InvalidOperationException",
                    "AutoDeployment is valid only for SERVICE_MANAGED StackSets.", 400);
        }
        // Checked against the template and parameters the update leaves the stack set with, before
        // either is changed or an operation is recorded.
        String keptTemplate = templateBody != null && !templateBody.isBlank() ? templateBody : ss.getTemplateBody();
        requireValidTemplate(keptTemplate);
        Map<String, String> updatedParameters = replaceSettings
                ? (parameters == null ? Map.of() : parameters)
                : (parameters != null && !parameters.isEmpty() ? parameters : ss.getParameters());
        cfnService.requireParameterValues(keptTemplate, updatedParameters);
        String operationId = reserveOperation(name, "UPDATE", requestedOperationId).getOperationId();
        if (templateBody != null && !templateBody.isBlank()) {
            ss.setTemplateBody(templateBody);
        }
        ss.setParameters(new LinkedHashMap<>(updatedParameters));
        if (replaceSettings || (capabilities != null && !capabilities.isEmpty())) {
            ss.getCapabilities().clear();
            if (capabilities != null) {
                ss.getCapabilities().addAll(capabilities);
            }
        }
        if (replaceSettings || (tags != null && !tags.isEmpty())) {
            ss.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
        }
        if (replaceSettings || description != null) {
            ss.setDescription(description);
        }
        if (autoDeploymentEnabled != null) {
            ss.setAutoDeploymentEnabled(autoDeploymentEnabled);
        }
        if (retainStacksOnAccountRemoval != null) ss.setRetainStacksOnAccountRemoval(retainStacksOnAccountRemoval);
        if (managedExecutionActive != null) ss.setManagedExecutionActive(managedExecutionActive);
        stackSets.put(name, ss);

        List<StackInstance> deployed = new ArrayList<>();
        try {
            for (StackInstance inst : listStackInstances(name, null, null)) {
                // An instance whose stack is terminal - a failed create that rolled back, or a phase
                // some earlier operation abandoned - is one the single-stack engine refuses to update.
                // That is one instance failing, not a malformed request: AWS leaves such an instance
                // INOPERABLE, updates the rest, and reports the operation FAILED. Deploying into it
                // anyway would raise the refusal out of here and fail the whole UpdateStackSet call
                // with a 400, updating no instance at all.
                String stackStatus = cfnService.stackStatus(
                        inst.getStackName(), inst.getRegion(), inst.getAccount());
                if (CloudFormationService.refusesUpdate(stackStatus)) {
                    inst.setStatus("INOPERABLE");
                    inst.setDetailedStatus("FAILED");
                    inst.setStatusReason("Stack instance is in " + stackStatus
                            + " state and can not be updated");
                    instances.put(instanceKey(name, inst.getAccount(), inst.getRegion()), inst);
                    deployed.add(inst);
                    continue;
                }
                StackInstance updated =
                        deployInstance(ss, inst.getAccount(), inst.getRegion(), UPDATE_CHANGE_SET, "UPDATE");
                applyDeploymentTargets(updated, deploymentTargetsFor(inst));
                instances.put(instanceKey(name, updated.getAccount(), updated.getRegion()), updated);
                deployed.add(updated);
            }
            completeOperation(name, operationId, deriveOperationStatus(deployed));
            return describeStackSetOperation(name, operationId);
        } catch (RuntimeException e) {
            completeOperation(name, operationId, "FAILED");
            throw e;
        }
    }

    public synchronized void deleteStackSet(String name) {
        getStackSetOrThrow(name);
        if (!listStackInstances(name, null, null).isEmpty()) {
            throw new AwsException("StackSetNotEmptyException",
                    "StackSet is not empty. Delete all stack instances first.", 409);
        }
        stackSets.delete(name);
        LOG.infov("Deleted StackSet: {0}", name);
    }

    public synchronized boolean mutateStackSetResource(String stackSetId, Consumer<StackSet> mutation) {
        if (stackSetId == null || stackSetId.indexOf(':') < 1) {
            return false;
        }
        String name = stackSetId.substring(0, stackSetId.indexOf(':'));
        StackSet stackSet = stackSets.get(name).orElse(null);
        if (stackSet == null || !stackSetId.equals(stackSet.getStackSetId())) {
            return false;
        }
        mutation.accept(stackSet);
        return true;
    }

    /**
     * Deletes every stack instance with its stack, then the stack set, as deleting an
     * {@code AWS::CloudFormation::StackSet} resource does. A stack set already gone is left alone.
     */
    public synchronized void deleteStackSetWithInstances(String name) {
        StackSet stackSet = stackSets.get(name).orElse(null);
        if (stackSet == null) {
            return;
        }
        for (StackInstance instance : listStackInstances(name, null, null)) {
            removeInstance(stackSet, instance, false);
        }
        if (listStackInstances(name, null, null).isEmpty()) {
            deleteAutoDeploymentTargets(name);
        }
        deleteStackSet(name);
    }

    private void deleteAutoDeploymentTargets(String name) {
        for (StackSetAutoDeploymentTarget target : listStackSetAutoDeploymentTargets(name)) {
            autoDeploymentTargets.delete(name + "::" + target.getOrganizationalUnitId());
        }
    }

    // ── Instances ──────────────────────────────────────────────────────────────

    public StackSetOperation createStackInstances(String name, List<String> accounts, List<String> regions) {
        return createStackInstances(name, accounts, regions, List.of(), null);
    }

    public StackSetOperation createStackInstances(String name, List<String> accounts, List<String> regions,
                                                  List<String> organizationalUnits) {
        return createStackInstances(name, accounts, regions, organizationalUnits, null);
    }

    public synchronized StackSetOperation createStackInstances(String name, List<String> accounts, List<String> regions,
                                                               List<String> organizationalUnits, String requestedOperationId) {
        StackSet ss = getStackSetOrThrow(name);
        validateRegions(regions);
        List<String> directAccounts = accounts == null ? List.of() : accounts;
        List<String> targetOus = organizationalUnits == null ? List.of() : organizationalUnits;
        if (!directAccounts.isEmpty() && !targetOus.isEmpty()) {
            throw new AwsException("InvalidOperationException",
                    "Specify Accounts or DeploymentTargets, but not both.", 400);
        }
        if (directAccounts.isEmpty() && targetOus.isEmpty()) {
            throw new AwsException("ValidationError", "Accounts or DeploymentTargets must contain at least one value.", 400);
        }
        if (!targetOus.isEmpty() && !"SERVICE_MANAGED".equals(ss.getPermissionModel())) {
            throw new AwsException("InvalidOperationException",
                    "DeploymentTargets can be used only with SERVICE_MANAGED StackSets.", 400);
        }
        if (!directAccounts.isEmpty() && "SERVICE_MANAGED".equals(ss.getPermissionModel())) {
            throw new AwsException("InvalidOperationException",
                    "Accounts can be used only with SELF_MANAGED StackSets. Use DeploymentTargets for SERVICE_MANAGED StackSets.", 400);
        }
        if (directAccounts.stream().anyMatch(account -> account == null || !account.matches("[0-9]{12}"))) {
            throw SmithyValidationErrors.validationError(
                    "Value '" + directAccounts
                            + "' at 'accounts' failed to satisfy constraint: Member must satisfy constraint: "
                            + "[Member must have length less than or equal to 12, Member must have length "
                            + "greater than or equal to 12, Member must satisfy regular expression pattern: "
                            + "^[0-9]{12}$]");
        }
        for (String ou : targetOus) validateOuOrRoot(ou);

        String operationId = reserveOperation(name, "CREATE", requestedOperationId).getOperationId();
        List<StackInstance> deployed = new ArrayList<>();
        try {
            List<TargetAccount> targets = !directAccounts.isEmpty()
                    ? directAccounts.stream().map(account -> new TargetAccount(account, Set.of())).toList()
                    : resolveOrganizationTargets(targetOus, regions, name);
            for (TargetAccount target : targets) {
                for (String region : regions) {
                    String instanceKey = instanceKey(name, target.accountId(), region);
                    StackInstance existing = instances.get(instanceKey).orElse(null);
                    if (existing != null && !"OUTDATED".equals(existing.getStatus())) {
                        if (!target.organizationalUnitIds().isEmpty()) {
                            Set<String> associations = deploymentTargetsFor(existing);
                            if (associations.addAll(target.organizationalUnitIds())) {
                                applyDeploymentTargets(existing, associations);
                                instances.put(instanceKey, existing);
                            }
                        }
                        continue;
                    }
                    StackInstance inst = deployInstance(ss, target.accountId(), region, INSTANCE_CHANGE_SET, "CREATE");
                    Set<String> associations = existing == null
                            ? new LinkedHashSet<>() : deploymentTargetsFor(existing);
                    associations.addAll(target.organizationalUnitIds());
                    applyDeploymentTargets(inst, associations);
                    instances.put(instanceKey, inst);
                    deployed.add(inst);
                }
            }
            completeOperation(name, operationId, deriveOperationStatus(deployed));
            return describeStackSetOperation(name, operationId);
        } catch (RuntimeException e) {
            // An organizational unit that cannot be resolved, or an instance whose change set is
            // refused: the operation fails rather than stay RUNNING, which would refuse every later one.
            completeOperation(name, operationId, "FAILED");
            throw e;
        }
    }

    public List<StackSetAutoDeploymentTarget> listStackSetAutoDeploymentTargets(String name) {
        getStackSetOrThrow(name);
        String prefix = name + "::";
        return autoDeploymentTargets.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(StackSetAutoDeploymentTarget::getOrganizationalUnitId))
                .toList();
    }

    /**
     * The organization root and OU ids a service-managed stack set targets, as DescribeStackSet's
     * {@code OrganizationalUnitIds} lists them: every target {@code CreateStackInstances} named that still
     * has a Region.
     */
    public List<String> organizationalUnitIds(String name) {
        return listStackSetAutoDeploymentTargets(name).stream()
                .map(StackSetAutoDeploymentTarget::getOrganizationalUnitId)
                .toList();
    }

    @Override
    public void accountPlacementChanged(String managementAccountId, String accountId) {
        String region = regionResolver.getRegion();
        accountPlacementExecutor.execute(() -> RequestScopes.runAs(managementAccountId, region,
                () -> reconcileAccountPlacement(managementAccountId, accountId)));
    }

    private synchronized void reconcileAccountPlacement(String managementAccountId, String accountId) {
        Set<String> ancestors = ancestorsOf(managementAccountId, accountId);
        for (StackSet stackSet : stackSets.scan(key -> true)) {
            if ("SERVICE_MANAGED".equals(stackSet.getPermissionModel()) && stackSet.isAutoDeploymentEnabled()) {
                try {
                    autoDeploy(stackSet, accountId, ancestors);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Automatic deployment failed for StackSet {0}, account {1}",
                            stackSet.getStackSetName(), accountId);
                }
            }
        }
    }

    @PreDestroy
    void stopAccountPlacementExecutor() {
        accountPlacementExecutor.shutdownNow();
    }

    /** The root and OUs above an active member account, empty for the management account or one outside the organization. */
    private Set<String> ancestorsOf(String managementAccountId, String accountId) {
        if (managementAccountId.equals(accountId)) {
            return Set.of();
        }
        try {
            if (!"ACTIVE".equals(organizationsService.describeAccount(managementAccountId, accountId).getStatus())) {
                return Set.of();
            }
            Set<String> ancestors = new LinkedHashSet<>(Arrays.asList(
                    organizationsService.organizationPath(managementAccountId, accountId).split("/")));
            ancestors.remove(accountId);
            return ancestors;
        } catch (AwsException e) {
            if ("AccountNotFoundException".equals(e.getErrorCode())
                    || "AWSOrganizationsNotInUseException".equals(e.getErrorCode())) {
                return Set.of();
            }
            throw e;
        }
    }

    private void autoDeploy(StackSet stackSet, String accountId, Set<String> ancestors) {
        String name = stackSet.getStackSetName();
        Map<String, Set<String>> targetsByRegion = new LinkedHashMap<>();
        for (StackSetAutoDeploymentTarget target : listStackSetAutoDeploymentTargets(name)) {
            if (ancestors.contains(target.getOrganizationalUnitId())) {
                for (String region : target.getRegions()) {
                    targetsByRegion.computeIfAbsent(region, ignored -> new LinkedHashSet<>())
                            .add(target.getOrganizationalUnitId());
                }
            }
        }
        List<StackInstance> removed = listStackInstances(name, accountId, null).stream()
                .filter(instance -> !targetsByRegion.containsKey(instance.getRegion()))
                .toList();
        if (!removed.isEmpty()) {
            runAutomaticOperation(name, "DELETE", () -> {
                for (StackInstance instance : removed) {
                    removeInstance(stackSet, instance, stackSet.isRetainStacksOnAccountRemoval());
                }
                return removed;
            });
        }
        Map<String, Set<String>> regionsToCreate = new LinkedHashMap<>();
        targetsByRegion.forEach((region, targets) -> {
            String key = instanceKey(name, accountId, region);
            StackInstance existing = instances.get(key).orElse(null);
            if (existing != null && !"OUTDATED".equals(existing.getStatus())) {
                applyDeploymentTargets(existing, targets);
                instances.put(key, existing);
            } else {
                regionsToCreate.put(region, targets);
            }
        });
        if (!regionsToCreate.isEmpty()) {
            runAutomaticOperation(name, "CREATE", () -> {
                List<StackInstance> created = new ArrayList<>();
                regionsToCreate.forEach((region, targets) -> {
                    StackInstance instance = deployInstance(stackSet, accountId, region, INSTANCE_CHANGE_SET, "CREATE");
                    applyDeploymentTargets(instance, targets);
                    instances.put(instanceKey(name, accountId, region), instance);
                    created.add(instance);
                });
                return created;
            });
        }
    }

    private void runAutomaticOperation(String name, String action, Supplier<List<StackInstance>> deployment) {
        String operationId = reserveOperation(name, action, null).getOperationId();
        try {
            completeOperation(name, operationId, deriveOperationStatus(deployment.get()));
        } catch (RuntimeException e) {
            completeOperation(name, operationId, "FAILED");
            throw e;
        }
    }

    private void removeInstance(StackSet stackSet, StackInstance instance, boolean retainStacks) {
        String key = instanceKey(stackSet.getStackSetName(), instance.getAccount(), instance.getRegion());
        try {
            if (!retainStacks
                    && !await(cfnService.deleteStack(instance.getStackName(), instance.getRegion(), instance.getAccount()))) {
                instance.setStatus("INOPERABLE");
                instance.setDetailedStatus("FAILED");
                instance.setStatusReason("Stack instance deletion failed");
                instances.put(key, instance);
                return;
            }
        } catch (RuntimeException e) {
            instance.setStatus("INOPERABLE");
            instance.setDetailedStatus("FAILED");
            instance.setStatusReason(e.getMessage());
            instances.put(key, instance);
            LOG.warnv(e, "Deletion failed for StackSet {0}, account {1}, Region {2}",
                    stackSet.getStackSetName(), instance.getAccount(), instance.getRegion());
            return;
        }
        instance.setDetailedStatus("SUCCEEDED");
        instances.delete(key);
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
        return deleteStackInstances(name, accounts, regions, List.of(), retainStacks);
    }

    public StackSetOperation deleteStackInstances(String name, List<String> accounts, List<String> regions,
                                                  List<String> organizationalUnits, boolean retainStacks) {
        StackSet stackSet = getStackSetOrThrow(name);
        validateRegions(regions);
        List<String> directAccounts = accounts == null ? List.of() : accounts;
        List<String> targetOus = organizationalUnits == null ? List.of() : organizationalUnits;
        if (!directAccounts.isEmpty() && !targetOus.isEmpty()) {
            throw new AwsException("InvalidOperationException",
                    "Specify Accounts or DeploymentTargets, but not both.", 400);
        }
        if (directAccounts.isEmpty() && targetOus.isEmpty()) {
            throw new AwsException("ValidationError",
                    "Accounts or DeploymentTargets must contain at least one value.", 400);
        }
        if (!targetOus.isEmpty() && !"SERVICE_MANAGED".equals(stackSet.getPermissionModel())) {
            throw new AwsException("InvalidOperationException",
                    "DeploymentTargets can be used only with SERVICE_MANAGED StackSets.", 400);
        }
        if (!directAccounts.isEmpty() && "SERVICE_MANAGED".equals(stackSet.getPermissionModel())) {
            throw new AwsException("InvalidOperationException",
                    "Accounts can be used only with SELF_MANAGED StackSets. Use DeploymentTargets for SERVICE_MANAGED StackSets.", 400);
        }
        for (String account : directAccounts) {
            validateAccountId(account);
        }
        for (String ou : targetOus) {
            validateOuOrRoot(ou);
        }

        Set<String> requestedRegions = Set.copyOf(regions);
        Set<String> requestedAccounts = Set.copyOf(directAccounts);
        Set<String> requestedDeploymentTargets = Set.copyOf(targetOus);
        List<StackInstance> targetInstances = listStackInstances(name, null, null).stream()
                .filter(inst -> requestedRegions.contains(inst.getRegion()))
                .filter(inst -> !requestedAccounts.isEmpty()
                        ? requestedAccounts.contains(inst.getAccount())
                        : deploymentTargetsFor(inst).stream().anyMatch(requestedDeploymentTargets::contains))
                .toList();

        List<StackInstance> results = new ArrayList<>();
        for (StackInstance inst : targetInstances) {
            String account = inst.getAccount();
            String region = inst.getRegion();
            String key = instanceKey(name, account, region);
            // Service-managed instances can be covered by multiple DeploymentTargets when a
            // parent OU and one of its descendants are both targeted. Removing one target must not
            // tear down the backing stack while another recorded target still covers the instance.
            if (!requestedDeploymentTargets.isEmpty()) {
                Set<String> remainingTargets = deploymentTargetsFor(inst);
                remainingTargets.removeAll(requestedDeploymentTargets);
                if (!remainingTargets.isEmpty()) {
                    applyDeploymentTargets(inst, remainingTargets);
                    instances.put(key, inst);
                    inst.setDetailedStatus("SUCCEEDED");
                    results.add(inst);
                    continue;
                }
            }
            removeInstance(stackSet, inst, retainStacks);
            results.add(inst);
        }
        String operationStatus = deriveOperationStatus(results);
        if ("SUCCEEDED".equals(operationStatus) && !requestedDeploymentTargets.isEmpty()) {
            removeDeploymentTargetRegions(name, requestedDeploymentTargets, requestedRegions);
        }
        return recordOperation(name, "DELETE", operationStatus);
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

    private void removeDeploymentTargetRegions(String stackSetName, Set<String> organizationalUnits,
                                               Set<String> regions) {
        for (String organizationalUnit : organizationalUnits) {
            String key = stackSetName + "::" + organizationalUnit;
            StackSetAutoDeploymentTarget target = autoDeploymentTargets.get(key).orElse(null);
            if (target == null) {
                continue;
            }
            List<String> remainingRegions = target.getRegions().stream()
                    .filter(region -> !regions.contains(region))
                    .toList();
            if (remainingRegions.isEmpty()) {
                autoDeploymentTargets.delete(key);
            } else {
                target.setRegions(remainingRegions);
                autoDeploymentTargets.put(key, target);
            }
        }
    }

    private List<TargetAccount> resolveOrganizationTargets(List<String> organizationalUnits,
                                                           List<String> regions, String stackSetName) {
        return resolveOrganizationTargets(organizationalUnits, regions, stackSetName, true);
    }

    private List<TargetAccount> resolveOrganizationTargets(List<String> organizationalUnits,
                                                           List<String> regions, String stackSetName,
                                                           boolean persistTargets) {
        String caller = regionResolver.getAccountId();
        Organization organization;
        try {
            organization = organizationsService.describeOrganization(caller);
        } catch (AwsException e) {
            throw new AwsException("InvalidOperationException",
                    "SERVICE_MANAGED StackSets require an AWS Organization.", 400);
        }
        LinkedHashMap<String, TargetAccount> targets = new LinkedHashMap<>();
        for (String ou : organizationalUnits) {
            collectOrganizationTargets(caller, organization, ou, ou, targets);
            if (persistTargets) {
                String key = stackSetName + "::" + ou;
                LinkedHashSet<String> targetRegions = new LinkedHashSet<>(autoDeploymentTargets.get(key)
                        .map(StackSetAutoDeploymentTarget::getRegions).orElse(List.of()));
                targetRegions.addAll(regions);
                autoDeploymentTargets.put(key,
                        new StackSetAutoDeploymentTarget(stackSetName, ou, List.copyOf(targetRegions)));
            }
        }
        return new ArrayList<>(targets.values());
    }

    private void collectOrganizationTargets(String caller, Organization organization, String parentId,
                                            String requestedTargetId,
                                            LinkedHashMap<String, TargetAccount> targets) {
        for (OrganizationAccount account : organizationsService.listAccountsForParent(caller, parentId)) {
            if (!organization.getMasterAccountId().equals(account.getId()) && "ACTIVE".equals(account.getStatus())) {
                targets.compute(account.getId(), (ignored, existing) -> {
                    Set<String> associations = existing == null
                            ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.organizationalUnitIds());
                    associations.add(requestedTargetId);
                    return new TargetAccount(account.getId(), associations);
                });
            }
        }
        for (OrganizationalUnit child : organizationsService.listOrganizationalUnitsForParent(caller, parentId)) {
            collectOrganizationTargets(caller, organization, child.getId(), requestedTargetId, targets);
        }
    }

    private StackSetOperation reserveOperation(String stackSetName, String action, String requestedOperationId) {
        String operationId = requestedOperationId == null || requestedOperationId.isBlank()
                ? UUID.randomUUID().toString() : requestedOperationId;
        if (!OPERATION_ID.matcher(operationId).matches()) {
            throw new AwsException("ValidationError", "OperationId is invalid.", 400);
        }
        if (operations.get(stackSetName + ":" + operationId).isPresent()) {
            throw new AwsException("OperationIdAlreadyExistsException",
                    "OperationId " + operationId + " already exists for StackSet " + stackSetName + ".", 409);
        }
        boolean running = operations.scan(key -> key.startsWith(stackSetName + ":")).stream()
                .anyMatch(operation -> "RUNNING".equals(operation.getStatus()));
        if (running) {
            throw new AwsException("OperationInProgressException",
                    "Another operation is currently in progress for StackSet " + stackSetName + ".", 409);
        }
        StackSetOperation operation = new StackSetOperation(operationId, stackSetName, action);
        operation.setStatus("RUNNING");
        operation.setEndTimestamp(null);
        operations.put(stackSetName + ":" + operationId, operation);
        return operation;
    }

    private void completeOperation(String stackSetName, String operationId, String status) {
        StackSetOperation operation = operations.get(stackSetName + ":" + operationId)
                .orElseThrow(() -> new AwsException("OperationNotFoundException",
                        "Operation " + operationId + " not found for stack set " + stackSetName, 404));
        operation.setStatus(status);
        operation.setEndTimestamp(Instant.now());
        operations.put(stackSetName + ":" + operationId, operation);
    }

    private static void validateStackSetName(String name) {
        if (name == null || !STACK_SET_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationError",
                    "StackSetName must start with a letter, contain only alphanumeric characters and hyphens, and be at most 128 characters.", 400);
        }
    }

    private static void validateAccountId(String account) {
        if (account == null || !account.matches("[0-9]{12}")) {
            throw new AwsException("ValidationError", "Accounts must contain 12 digit account IDs.", 400);
        }
    }

    private static void validateOuOrRoot(String value) {
        if (value == null || !OU_OR_ROOT_ID.matcher(value).matches()) {
            throw new AwsException("ValidationError", "DeploymentTargets contains an invalid organizational unit or root ID.", 400);
        }
    }

    private static void validateRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new AwsException("ValidationError", "Regions must contain at least one value.", 400);
        }
        if (regions.stream().anyMatch(region -> region == null || !REGION_PATTERN.matcher(region).matches())) {
            throw new AwsException("ValidationError", "Regions contains an invalid region name.", 400);
        }
    }

    private static Set<String> deploymentTargetsFor(StackInstance instance) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (instance.getDeploymentTargetIds() != null) {
            targets.addAll(instance.getDeploymentTargetIds());
        }
        if (targets.isEmpty() && instance.getOrganizationalUnitId() != null) {
            targets.add(instance.getOrganizationalUnitId());
        }
        return targets;
    }

    private static void applyDeploymentTargets(StackInstance instance, Set<String> targets) {
        List<String> orderedTargets = new ArrayList<>(targets);
        instance.setDeploymentTargetIds(orderedTargets);
        instance.setOrganizationalUnitId(orderedTargets.isEmpty() ? null : orderedTargets.getFirst());
    }

    private record TargetAccount(String accountId, Set<String> organizationalUnitIds) {}

    /**
     * Drives the single-stack engine to materialize one instance's resources into the target
     * account's namespace, then returns a {@link StackInstance} describing it.
     */
    private StackInstance deployInstance(StackSet ss, String account, String region,
                                         String changeSetName, String changeSetType) {
        String stackName = resolveInstanceStackName(ss.getStackSetName(), account, region);
        // Preflight the change set in the target account so AWS::AccountId-based conditions are
        // evaluated against the same account the instance executes in (createChangeSet otherwise runs
        // in the administrator request scope).
        ChangeSet changeSet = cfnService.createChangeSet(stackName, changeSetName, changeSetType,
                ss.getTemplateBody(), null, ss.getParameters(), ss.getCapabilities(), ss.getTags(), region, account);
        // An instance whose stack already has what the stack set describes is left as it is, as on
        // AWS: executing its change set, which has nothing to change, would update the stack anyway.
        // That change set is the engine's own and is not left behind.
        boolean nothingToChange = changeSet != null && "FAILED".equals(changeSet.getStatus())
                && CloudFormationService.NO_CHANGES_REASON.equals(changeSet.getStatusReason());
        if (nothingToChange) {
            RequestScopes.runAs(account, region, () -> cfnService.deleteChangeSet(stackName, changeSetName, region));
        } else {
            await(cfnService.executeChangeSet(stackName, changeSetName, region, account));
        }

        StackInstance inst = new StackInstance();
        inst.setStackSetId(ss.getStackSetId());
        inst.setStackSetName(ss.getStackSetName());
        inst.setAccount(account);
        inst.setRegion(region);
        inst.setStackName(stackName);
        inst.setStackId(resolveStackId(stackName, region, account));
        List<Stack> stacks = cfnService.describeStacks(stackName, region, account);
        String stackStatus = stacks.isEmpty() ? null : stacks.get(0).getStatus();
        // Only a clean create/update is a success. A failed resource rolls the stack back, so its
        // terminal status is ROLLBACK_COMPLETE (not *_FAILED) — treat anything that is not COMPLETE
        // as a failed instance so the operation status reflects it. A stack with nothing to change
        // accepted the preflight and already has what the stack set describes, whatever status an
        // earlier rolled-back update left it in.
        if (nothingToChange) {
            inst.setDetailedStatus("SUCCEEDED");
        } else if (!"CREATE_COMPLETE".equals(stackStatus) && !"UPDATE_COMPLETE".equals(stackStatus)) {
            inst.setStatus("INOPERABLE");
            inst.setDetailedStatus("FAILED");
            inst.setStatusReason(stacks.isEmpty() ? null : CloudFormationService.failureCause(stacks.get(0)));
        } else {
            inst.setDetailedStatus("SUCCEEDED");
        }
        return inst;
    }

    private String resolveStackId(String stackName, String region, String account) {
        List<Stack> stacks = cfnService.describeStacks(stackName, region, account);
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

    /**
     * Refuses a body that is no template, or a template holding a null value, before the stack set
     * stores it, as CreateStackSet and UpdateStackSet do (measured, eu-west-1, 2026-10-20 and
     * 2026-10-21). StackSets passes CloudFormation's message on with the details of the service
     * exception it came in.
     */
    private void requireValidTemplate(String templateBody) {
        try {
            cfnService.requireValidTemplate(templateBody);
        } catch (AwsException e) {
            throw new AwsException(e.getErrorCode(), CloudFormationTemplateEngine.sdkErrorMessage(
                    e.getMessage(), "AmazonCloudFormation", e.getHttpStatus(), e.getErrorCode()), e.getHttpStatus());
        }
    }

    private StackSet getStackSetOrThrow(String name) {
        return stackSets.get(name)
                .orElseThrow(() -> new AwsException("StackSetNotFoundException",
                        "StackSet [" + name + "] not found", 404));
    }

    private static String instanceStackName(String stackSetName, String account) {
        return "StackSet-" + stackSetName + "-" + account;
    }

    private String resolveInstanceStackName(String stackSetName, String account, String region) {
        StackInstance existing = instances.get(instanceKey(stackSetName, account, region)).orElse(null);
        if (existing != null && existing.getStackName() != null) {
            return existing.getStackName();
        }
        String name = instanceStackName(stackSetName, account);
        String status = cfnService.stackStatus(name, region, account);
        if (status != null && !"DELETE_COMPLETE".equals(status)) {
            String suffix = "-" + UUID.randomUUID();
            return name.substring(0, Math.min(name.length(), 128 - suffix.length())) + suffix;
        }
        return name;
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
