package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class Inspector2Service implements Resettable {
    private static final List<String> RESOURCE_TYPES = List.of(
            "EC2", "ECR", "LAMBDA", "LAMBDA_CODE", "CODE_REPOSITORY");

    private final AccountAwareStorageBackend<InspectorState> states;
    private final OrganizationsService organizationsService;

    @Inject
    public Inspector2Service(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this(storageFactory.create("inspector2", "inspector2-state.json",
                new TypeReference<Map<String, InspectorState>>() {}), organizationsService);
    }

    Inspector2Service(AccountAwareStorageBackend<InspectorState> states, OrganizationsService organizationsService) {
        this.states = states;
        this.organizationsService = organizationsService;
    }

    public InspectorState state(String region) {
        return states.get(region).orElseGet(InspectorState::new);
    }

    public InspectorState delegatedAdminState(String region, String callerAccountId) {
        requireManagementAccount(callerAccountId);
        return stateForAccount(callerAccountId, region);
    }

    public synchronized void enableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);

        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different delegated administrator is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.putForAccount(managementAccountId, region, management);

        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(accountId);
        for (String resourceType : RESOURCE_TYPES) {
            delegated.setResourceStatus(resourceType, "ENABLED");
        }
        delegated.setStatus("ENABLED");
        resetTransitionPolls(delegated);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void disableDelegatedAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        String managementAccountId = requireManagementAccount(callerAccountId);
        requireSameOrganization(managementAccountId, accountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!accountId.equals(management.getAdminAccountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified delegated administrator was not found.", 404);
        }
        management.setAdminAccountId(null);
        states.putForAccount(managementAccountId, region, management);
        InspectorState delegated = stateForAccount(accountId, region);
        delegated.setAdminAccountId(null);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized InspectorState accountStatus(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        authorizeAccountAccess(region, callerAccountId, accountId);
        InspectorState state = stateForAccount(accountId, region);
        if ("ENABLING".equals(state.getStatus())) {
            return advanceTransition(region, accountId, state, "ENABLING", "ENABLED");
        }
        if ("DISABLING".equals(state.getStatus())) {
            return advanceTransition(region, accountId, state, "DISABLING", "DISABLED");
        }
        return copyState(state);
    }

    public synchronized Map<String, InspectorState> enable(
            String region, String callerAccountId, JsonNode request) {
        return updateResourceStatus(region, callerAccountId, accountIds(request, callerAccountId),
                resourceTypes(request, false), "ENABLING", "ENABLED");
    }

    public synchronized Map<String, InspectorState> disable(
            String region, String callerAccountId, JsonNode request) {
        return updateResourceStatus(region, callerAccountId, accountIds(request, callerAccountId),
                resourceTypes(request, true), "DISABLING", "DISABLED");
    }

    private Map<String, InspectorState> updateResourceStatus(
            String region, String callerAccountId, List<String> accountIds, List<String> resourceTypes,
            String transitionalStatus, String finalStatus) {
        for (String accountId : accountIds) {
            authorizeAccountAccess(region, callerAccountId, accountId);
        }

        Map<String, InspectorState> result = new LinkedHashMap<>();
        for (String accountId : accountIds) {
            InspectorState state = stateForAccount(accountId, region);
            boolean changed = false;
            for (String resourceType : resourceTypes) {
                String current = state.resourceStatus(resourceType);
                if (!finalStatus.equals(current) && !transitionalStatus.equals(current)) {
                    state.setResourceStatus(resourceType, transitionalStatus);
                    changed = true;
                }
            }
            if (changed) {
                state.setStatus(transitionalStatus);
                resetTransitionPolls(state);
                setTransitionPollsRemaining(state, transitionalStatus, 1);
                states.putForAccount(accountId, region, state);
            }
            result.put(accountId, copyState(state));
        }
        return result;
    }

    private InspectorState advanceTransition(String region, String accountId, InspectorState state,
                                             String transitionalStatus, String finalStatus) {
        int pollsRemaining = transitionPollsRemaining(state, transitionalStatus);
        if (pollsRemaining > 0) {
            InspectorState response = copyState(state);
            setTransitionPollsRemaining(state, transitionalStatus, pollsRemaining - 1);
            states.putForAccount(accountId, region, state);
            return response;
        }
        for (String resourceType : RESOURCE_TYPES) {
            if (transitionalStatus.equals(state.resourceStatus(resourceType))) {
                state.setResourceStatus(resourceType, finalStatus);
            }
        }
        state.setStatus(overallStatus(state));
        states.putForAccount(accountId, region, state);
        return copyState(state);
    }

    public synchronized InspectorState updateOrganizationConfiguration(
            String region, String callerAccountId, JsonNode request) {
        InspectorState state = requireAdministrator(region, callerAccountId);
        JsonNode autoEnable = request.get("autoEnable");
        if (autoEnable == null || !autoEnable.isObject()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }

        boolean ec2 = requiredBoolean(autoEnable, "ec2");
        boolean ecr = requiredBoolean(autoEnable, "ecr");
        boolean lambda = optionalBoolean(autoEnable, "lambda");
        boolean lambdaCode = optionalBoolean(autoEnable, "lambdaCode");
        boolean codeRepository = optionalBoolean(autoEnable, "codeRepository");

        state.setAutoEnableEc2(ec2);
        state.setAutoEnableEcr(ecr);
        state.setAutoEnableLambda(lambda);
        state.setAutoEnableLambdaCode(lambdaCode);
        state.setAutoEnableCodeRepository(codeRepository);
        states.putForAccount(callerAccountId, region, state);
        return copyState(state);
    }

    public InspectorState organizationConfiguration(String region, String callerAccountId) {
        return copyState(requireAdministrator(region, callerAccountId));
    }

    private InspectorState requireAdministrator(String region, String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> new AwsException("AccessDeniedException",
                        "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403));
        InspectorState management = stateForAccount(managementAccountId, region);
        if (management.getAdminAccountId() == null || !callerAccountId.equals(management.getAdminAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403);
        }
        return stateForAccount(callerAccountId, region);
    }

    private void authorizeAccountAccess(String region, String callerAccountId, String targetAccountId) {
        if (callerAccountId.equals(targetAccountId)) {
            return;
        }
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("The caller cannot manage the requested Amazon Inspector account."));
        requireSameOrganization(managementAccountId, targetAccountId);
        InspectorState management = stateForAccount(managementAccountId, region);
        if (!callerAccountId.equals(management.getAdminAccountId())) {
            throw accessDenied("Only the delegated Amazon Inspector administrator can manage member accounts.");
        }
    }

    private String requireManagementAccount(String callerAccountId) {
        String managementAccountId = managementAccountFor(callerAccountId)
                .orElseThrow(() -> accessDenied("Only the AWS Organizations management account can perform this operation."));
        if (!callerAccountId.equals(managementAccountId)) {
            throw accessDenied("Only the AWS Organizations management account can perform this operation.");
        }
        return managementAccountId;
    }

    private void requireSameOrganization(String managementAccountId, String accountId) {
        String targetManagementAccount = managementAccountFor(accountId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The specified AWS account is not a member of the organization.", 404));
        if (!managementAccountId.equals(targetManagementAccount)) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified AWS account is not a member of the organization.", 404);
        }
    }

    private Optional<String> managementAccountFor(String accountId) {
        return organizationsService.findManagementAccountForResource(accountId);
    }

    private InspectorState stateForAccount(String accountId, String region) {
        return states.getForAccount(accountId, region).orElseGet(InspectorState::new);
    }

    private static List<String> resourceTypes(JsonNode request, boolean allowEmpty) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null) {
            if (allowEmpty) {
                return List.of();
            }
            throw invalidResourceTypes(false);
        }
        if (!resourceTypes.isArray() || resourceTypes.size() > 5 || (!allowEmpty && resourceTypes.isEmpty())) {
            throw invalidResourceTypes(allowEmpty);
        }
        if (resourceTypes.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(resourceTypes.size());
        for (JsonNode resourceType : resourceTypes) {
            if (!resourceType.isTextual() || !RESOURCE_TYPES.contains(resourceType.textValue())) {
                throw new AwsException("ValidationException", "resourceTypes contains an invalid resource type.", 400);
            }
            if (!result.contains(resourceType.textValue())) {
                result.add(resourceType.textValue());
            }
        }
        return result;
    }

    private static AwsException invalidResourceTypes(boolean allowEmpty) {
        if (allowEmpty) {
            return new AwsException("ValidationException",
                    "resourceTypes must contain between 0 and 5 resource types.", 400);
        }
        return new AwsException("ValidationException",
                "resourceTypes must contain between 1 and 5 resource types.", 400);
    }

    private static List<String> accountIds(JsonNode request, String callerAccountId) {
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.size() > 100)) {
            throw new AwsException("ValidationException", "accountIds must contain at most 100 account IDs.", 400);
        }
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of(callerAccountId);
        }
        List<String> result = new ArrayList<>(accountIds.size());
        for (JsonNode accountId : accountIds) {
            requireAccountId(accountId.asText(null));
            result.add(accountId.asText());
        }
        return result;
    }

    private static String overallStatus(InspectorState state) {
        boolean enabling = RESOURCE_TYPES.stream().anyMatch(type -> "ENABLING".equals(state.resourceStatus(type)));
        if (enabling) {
            return "ENABLING";
        }
        boolean disabling = RESOURCE_TYPES.stream().anyMatch(type -> "DISABLING".equals(state.resourceStatus(type)));
        if (disabling) {
            return "DISABLING";
        }
        boolean enabled = RESOURCE_TYPES.stream().anyMatch(type -> "ENABLED".equals(state.resourceStatus(type)));
        return enabled ? "ENABLED" : "DISABLED";
    }

    private static int transitionPollsRemaining(InspectorState state, String transitionalStatus) {
        return switch (transitionalStatus) {
            case "ENABLING" -> state.getEnablingPollsRemaining();
            case "DISABLING" -> state.getDisablingPollsRemaining();
            default -> throw new IllegalArgumentException("Unsupported transition status: " + transitionalStatus);
        };
    }

    private static void setTransitionPollsRemaining(
            InspectorState state, String transitionalStatus, int pollsRemaining) {
        switch (transitionalStatus) {
            case "ENABLING" -> state.setEnablingPollsRemaining(pollsRemaining);
            case "DISABLING" -> state.setDisablingPollsRemaining(pollsRemaining);
            default -> throw new IllegalArgumentException("Unsupported transition status: " + transitionalStatus);
        }
    }

    private static void resetTransitionPolls(InspectorState state) {
        state.setEnablingPollsRemaining(0);
        state.setDisablingPollsRemaining(0);
    }

    private static InspectorState copyState(InspectorState source) {
        InspectorState copy = new InspectorState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setStatus(source.getStatus());
        copy.setEnablingPollsRemaining(source.getEnablingPollsRemaining());
        copy.setDisablingPollsRemaining(source.getDisablingPollsRemaining());
        copy.setEc2Status(source.getEc2Status());
        copy.setEcrStatus(source.getEcrStatus());
        copy.setLambdaStatus(source.getLambdaStatus());
        copy.setLambdaCodeStatus(source.getLambdaCodeStatus());
        copy.setCodeRepositoryStatus(source.getCodeRepositoryStatus());
        copy.setAutoEnableEc2(source.isAutoEnableEc2());
        copy.setAutoEnableEcr(source.isAutoEnableEcr());
        copy.setAutoEnableLambda(source.isAutoEnableLambda());
        copy.setAutoEnableLambdaCode(source.isAutoEnableLambdaCode());
        copy.setAutoEnableCodeRepository(source.isAutoEnableCodeRepository());
        return copy;
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " is required.", 400);
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable." + field + " must be a boolean.", 400);
        }
        return value.booleanValue();
    }

    private static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    @Override
    public void clear() {
        states.clear();
    }

    static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException",
                    "accountId must be a 12 digit AWS account ID.", 400);
        }
    }
}
