package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class Inspector2Service implements Resettable {
    private static final Set<String> RESOURCE_TYPES = Set.of("EC2", "ECR", "LAMBDA", "LAMBDA_CODE", "CODE_REPOSITORY");

    private final AccountAwareStorageBackend<InspectorState> states;

    @Inject
    public Inspector2Service(StorageFactory storageFactory) {
        this(storageFactory.create("inspector2", "inspector2-state.json",
                new TypeReference<Map<String, InspectorState>>() {}));
    }

    Inspector2Service(AccountAwareStorageBackend<InspectorState> states) {
        this.states = states;
    }

    public InspectorState state(String region) {
        return states.get(region).orElseGet(InspectorState::new);
    }

    public synchronized void enableDelegatedAdmin(String region, String accountId) {
        requireAccountId(accountId);
        InspectorState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different delegated administrator is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.put(region, management);

        InspectorState delegated = states.getForAccount(accountId, region).orElseGet(InspectorState::new);
        delegated.setAdminAccountId(accountId);
        delegated.setStatus("ENABLED");
        delegated.setEnablingPollsRemaining(0);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void disableDelegatedAdmin(String region, String accountId) {
        requireAccountId(accountId);
        InspectorState management = state(region);
        if (!accountId.equals(management.getAdminAccountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "The specified delegated administrator was not found.", 404);
        }
        management.setAdminAccountId(null);
        states.put(region, management);
        InspectorState delegated = states.getForAccount(accountId, region).orElseGet(InspectorState::new);
        delegated.setAdminAccountId(null);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized InspectorState accountStatus(String region) {
        InspectorState state = state(region);
        if ("ENABLING".equals(state.getStatus()) && state.getEnablingPollsRemaining() > 0) {
            InspectorState response = copyState(state);
            state.setEnablingPollsRemaining(state.getEnablingPollsRemaining() - 1);
            states.put(region, state);
            return response;
        }
        if ("ENABLING".equals(state.getStatus())) {
            state.setStatus("ENABLED");
            states.put(region, state);
        }
        return state;
    }

    public synchronized void enable(String region, JsonNode request) {
        JsonNode resourceTypes = request.get("resourceTypes");
        if (resourceTypes == null || !resourceTypes.isArray() || resourceTypes.isEmpty() || resourceTypes.size() > 5) {
            throw new AwsException("ValidationException", "resourceTypes must contain between 1 and 5 resource types.", 400);
        }
        for (JsonNode resourceType : resourceTypes) {
            if (!resourceType.isTextual() || !RESOURCE_TYPES.contains(resourceType.textValue())) {
                throw new AwsException("ValidationException", "resourceTypes contains an invalid resource type.", 400);
            }
        }
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.size() > 100)) {
            throw new AwsException("ValidationException", "accountIds must contain at most 100 account IDs.", 400);
        }
        if (accountIds != null) {
            for (JsonNode accountId : accountIds) {
                requireAccountId(accountId.asText(null));
            }
        }
        InspectorState state = state(region);
        if ("ENABLED".equals(state.getStatus())) {
            return;
        }
        state.setStatus("ENABLING");
        state.setEnablingPollsRemaining(1);
        states.put(region, state);
    }

    public synchronized InspectorState updateOrganizationConfiguration(
            String region, String callerAccountId, JsonNode request) {
        InspectorState state = requireAdministrator(region, callerAccountId);
        JsonNode autoEnable = request.get("autoEnable");
        if (autoEnable == null || !autoEnable.isObject()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }
        state.setAutoEnableEc2(requiredBoolean(autoEnable, "ec2"));
        state.setAutoEnableEcr(requiredBoolean(autoEnable, "ecr"));
        state.setAutoEnableLambda(optionalBoolean(autoEnable, "lambda"));
        state.setAutoEnableLambdaCode(optionalBoolean(autoEnable, "lambdaCode"));
        state.setAutoEnableCodeRepository(optionalBoolean(autoEnable, "codeRepository"));
        states.put(region, state);
        return state;
    }

    public InspectorState organizationConfiguration(String region, String callerAccountId) {
        return requireAdministrator(region, callerAccountId);
    }

    private InspectorState requireAdministrator(String region, String callerAccountId) {
        InspectorState state = state(region);
        if (state.getAdminAccountId() == null || !callerAccountId.equals(state.getAdminAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the delegated Amazon Inspector administrator can manage organization configuration.", 403);
        }
        return state;
    }

    private static InspectorState copyState(InspectorState source) {
        InspectorState copy = new InspectorState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setStatus(source.getStatus());
        copy.setEnablingPollsRemaining(source.getEnablingPollsRemaining());
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

    @Override
    public void clear() {
        states.clear();
    }

    static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException",
                    "delegatedAdminAccountId must be a 12 digit account ID.", 400);
        }
    }
}
