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
import java.util.Set;

@ApplicationScoped
public class Inspector2Service implements Resettable {
    private static final Set<String> RESOURCE_TYPES = Set.of(
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
        delegated.setEnablingPollsRemaining(0);
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
        if ("ENABLING".equals(state.getStatus()) && state.getEnablingPollsRemaining() > 0) {
            InspectorState response = copyState(state);
            state.setEnablingPollsRemaining(state.getEnablingPollsRemaining() - 1);
            states.putForAccount(accountId, region, state);
            return response;
        }
        if ("ENABLING".equals(state.getStatus())) {
            for (String resourceType : RESOURCE_TYPES) {
                if ("ENABLING".equals(state.resourceStatus(resourceType))) {
                    state.setResourceStatus(resourceType, "ENABLED");
                }
            }
            state.setStatus(overallStatus(state));
            states.putForAccount(accountId, region, state);
        }
        return copyState(state);
    }

    public synchronized Map<String, InspectorState> enable(
            String region, String callerAccountId, JsonNode request) {
        List<String> resourceTypes = resourceTypes(request);
        List<String> accountIds = accountIds(request, callerAccountId);

        for (String accountId : accountIds) {
            authorizeAccountAccess(region, callerAccountId, accountId);
        }

        Map<String, InspectorState> result = new LinkedHashMap<>();
        for (String accountId : accountIds) {
            InspectorState state = stateForAccount(accountId, region);
            boolean changed = false;
            for (String resourceType : resourceTypes) {
                String current = state.resourceStatus(resourceType);
                if (!"ENABLED".equals(current) && !"ENABLING".equals(current)) {
                    state.setResourceStatus(resourceType, "ENABLING");
                    changed = true;
                }
            }
            if (changed) {
                state.setStatus("ENABLING");
                state.setEnablingPollsRemaining(1);
                states.putForAccount(accountId, region, state);
            }
            result.put(accountId, copyState(state));
        }
        return result;
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

    private java.util.Optional<String> managementAccountFor(String accountId) {
        return organizationsService.findManagementAccountForResource(accountId);
    }

    private InspectorState stateForAccount(String accountId, String region) {
        return states.getForAccount(accountId, region).orElseGet(InspectorState::new);
    }

    private static List<String> resourceTypes(JsonNode request) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null || !resourceTypes.isArray() || resourceTypes.isEmpty() || resourceTypes.size() > 5) {
            throw new AwsException("ValidationException",
                    "resourceTypes must contain between 1 and 5 resource types.", 400);
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
        boolean enabled = RESOURCE_TYPES.stream().anyMatch(type -> "ENABLED".equals(state.resourceStatus(type)));
        return enabled ? "ENABLED" : "DISABLED";
    }

    private static InspectorState copyState(InspectorState source) {
        InspectorState copy = new InspectorState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setStatus(source.getStatus());
        copy.setEnablingPollsRemaining(source.getEnablingPollsRemaining());
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
