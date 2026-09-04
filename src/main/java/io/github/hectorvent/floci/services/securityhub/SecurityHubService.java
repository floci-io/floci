package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAssociation;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SecurityHubService {
    private static final String SELF_MANAGED = "SELF_MANAGED_SECURITY_HUB";
    private static final List<String> LINKING_MODES = List.of(
            "ALL_REGIONS", "ALL_REGIONS_EXCEPT_SPECIFIED", "SPECIFIED_REGIONS", "NO_REGIONS");

    private final AccountAwareStorageBackend<SecurityHubState> states;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityHubService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.states = storageFactory.create("securityhub", "securityhub-state.json",
                new TypeReference<Map<String, SecurityHubState>>() {});
        this.regionResolver = regionResolver;
    }

    public SecurityHubState state(String region) {
        return states.get(region).orElseGet(SecurityHubState::new);
    }

    public synchronized void enableOrganizationAdminAccount(String region, String adminAccountId) {
        requireAccountId(adminAccountId, "AdminAccountId");
        SecurityHubState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(adminAccountId)) {
            throw conflict("A different Security Hub administrator account is already configured.");
        }
        management.setAdminAccountId(adminAccountId);
        save(region, management);

        SecurityHubState delegated = states.getForAccount(adminAccountId, region).orElseGet(SecurityHubState::new);
        delegated.setAdminAccountId(adminAccountId);
        states.putForAccount(adminAccountId, region, delegated);
    }

    public synchronized void enableSecurityHub(String region) {
        SecurityHubState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Security Hub is already enabled for this account.");
        }
        state.setEnabled(true);
        save(region, state);
    }

    public void requireEnabled(String region) {
        if (!state(region).isEnabled()) {
            throw notFound("Security Hub is not enabled for this account.");
        }
    }

    public synchronized SecurityHubState createFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() != null) {
            throw new AwsException("LimitExceededException",
                    "Only one finding aggregator can exist for an account.", 429);
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setAggregatorArn("arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId()
                + ":finding-aggregator/" + UUID.randomUUID());
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public SecurityHubState getFindingAggregator(String region, String findingAggregatorArn) {
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() == null || findingAggregatorArn == null
                || !state.getAggregatorArn().equals(findingAggregatorArn)) {
            throw notFound("The finding aggregator was not found.");
        }
        return state;
    }

    public synchronized SecurityHubState updateFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        String arn = requireText(request, "FindingAggregatorArn", "InvalidInputException");
        if (state.getAggregatorArn() == null || !state.getAggregatorArn().equals(arn)) {
            throw notFound("The finding aggregator was not found.");
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public synchronized void updateOrganizationConfiguration(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAdminAccountId() == null) {
            throw new AwsException("InvalidAccessException",
                    "A Security Hub administrator account is required for central configuration.", 401);
        }
        JsonNode autoEnable = request.get("AutoEnable");
        if (autoEnable == null || !autoEnable.isBoolean()) {
            throw invalid("AutoEnable is required.");
        }
        String autoStandards = text(request, "AutoEnableStandards");
        if (autoStandards != null && !"DEFAULT".equals(autoStandards) && !"NONE".equals(autoStandards)) {
            throw invalid("AutoEnableStandards must be DEFAULT or NONE.");
        }
        JsonNode organizationConfiguration = request.get("OrganizationConfiguration");
        String type = organizationConfiguration != null && organizationConfiguration.isObject()
                ? text(organizationConfiguration, "ConfigurationType") : null;
        if (type != null && !"CENTRAL".equals(type) && !"LOCAL".equals(type)) {
            throw invalid("ConfigurationType must be CENTRAL or LOCAL.");
        }
        String desiredType = type == null ? state.getOrganizationConfigurationType() : type;
        if (desiredType == null) {
            desiredType = "LOCAL";
        }
        state.setOrganizationConfigurationType(desiredType);
        if ("CENTRAL".equals(desiredType)) {
            state.setOrganizationConfigurationStatus("PENDING");
            state.setOrganizationConfigurationPendingPollsRemaining(1);
        } else {
            state.setOrganizationConfigurationStatus("ENABLED");
            state.setOrganizationConfigurationPendingPollsRemaining(0);
        }
        save(region, state);
    }

    public synchronized SecurityHubState organizationConfiguration(String region) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())
                && state.getOrganizationConfigurationPendingPollsRemaining() > 0) {
            SecurityHubState response = copyConfigurationState(state);
            state.setOrganizationConfigurationPendingPollsRemaining(
                    state.getOrganizationConfigurationPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())) {
            state.setOrganizationConfigurationStatus("ENABLED");
            save(region, state);
        }
        return state;
    }

    public synchronized String createConfigurationPolicy(String region, JsonNode request) {
        requireEnabled(region);
        String name = requireText(request, "Name", "InvalidInputException");
        if (name.length() > 128) {
            throw invalid("Name exceeds the maximum length.");
        }
        SecurityHubState state = state(region);
        boolean duplicate = state.getPolicies().values().stream()
                .anyMatch(policy -> name.equals(text(policy, "Name")));
        if (duplicate) {
            throw conflict("A configuration policy with the same name already exists.");
        }
        if (!request.path("ConfigurationPolicy").isObject()) {
            throw invalid("ConfigurationPolicy is required.");
        }
        validateTags(request.get("Tags"));
        String id = UUID.randomUUID().toString();
        state.getPolicies().put(id, request.deepCopy());
        save(region, state);
        return id;
    }

    public JsonNode getConfigurationPolicy(String region, String identifier) {
        String id = normalizePolicyId(identifier);
        JsonNode policy = state(region).getPolicies().get(id);
        if (policy == null) {
            throw notFound("The configuration policy was not found.");
        }
        return policy;
    }

    public synchronized JsonNode updateConfigurationPolicy(String region, String identifier, JsonNode request) {
        String id = normalizePolicyId(identifier);
        SecurityHubState state = state(region);
        JsonNode current = state.getPolicies().get(id);
        if (current == null) {
            throw notFound("The configuration policy was not found.");
        }
        String newName = text(request, "Name");
        if (newName != null) {
            boolean duplicate = state.getPolicies().entrySet().stream()
                    .anyMatch(entry -> !entry.getKey().equals(id)
                            && newName.equals(text(entry.getValue(), "Name")));
            if (duplicate) {
                throw conflict("A configuration policy with the same name already exists.");
            }
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        if (newName != null) {
            merged.put("Name", newName);
        }
        if (request.has("Description")) {
            merged.set("Description", request.get("Description"));
        }
        if (request.has("ConfigurationPolicy")) {
            if (!request.get("ConfigurationPolicy").isObject()) {
                throw invalid("ConfigurationPolicy must be an object.");
            }
            merged.set("ConfigurationPolicy", request.get("ConfigurationPolicy").deepCopy());
        }
        state.getPolicies().put(id, merged);
        save(region, state);
        return merged;
    }

    public synchronized SecurityHubAssociation associate(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        TargetRef target = target(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String policyId = normalizePolicyId(policyIdentifier);
        if (!SELF_MANAGED.equals(policyId) && !state.getPolicies().containsKey(policyId)) {
            throw notFound("The configuration policy was not found.");
        }
        SecurityHubAssociation existing = state.getAssociations().get(target.id());
        if (existing != null && !existing.isDisassociating()
                && policyId.equals(existing.getPolicyId())) {
            throw conflict("The target is already associated with the specified configuration policy.");
        }
        SecurityHubAssociation association = new SecurityHubAssociation(
                target.id(), target.type(), policyId, "PENDING", 1);
        state.getAssociations().put(target.id(), association);
        save(region, state);
        return association.copy();
    }

    public synchronized SecurityHubAssociation association(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        TargetRef target = target(request);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null) {
            throw notFound("The configuration policy association was not found.");
        }
        if ("PENDING".equals(association.getStatus()) && association.getPendingPollsRemaining() > 0) {
            SecurityHubAssociation response = association.copy();
            association.setPendingPollsRemaining(association.getPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(association.getStatus())) {
            if (association.isDisassociating()) {
                state.getAssociations().remove(target.id());
                save(region, state);
                throw notFound("The configuration policy association was not found.");
            }
            association.setStatus("SUCCESS");
            applyPolicyToAccountTarget(region, state, association);
            save(region, state);
        }
        return association.copy();
    }

    public synchronized void disassociate(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        TargetRef target = target(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String requestedPolicyId = normalizePolicyId(policyIdentifier);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null || !requestedPolicyId.equals(association.getPolicyId())) {
            throw notFound("The configuration policy association was not found.");
        }
        if (association.isDisassociating()) {
            throw conflict("The configuration policy association is already being removed.");
        }
        association.setStatus("PENDING");
        association.setPendingPollsRemaining(1);
        association.setDisassociating(true);
        save(region, state);
    }

    private void applyPolicyToAccountTarget(String region, SecurityHubState administrator,
                                            SecurityHubAssociation association) {
        if (!"ACCOUNT".equals(association.getTargetType()) || SELF_MANAGED.equals(association.getPolicyId())) {
            return;
        }
        JsonNode policy = administrator.getPolicies().get(association.getPolicyId());
        JsonNode securityHub = policy == null ? null : policy.path("ConfigurationPolicy").path("SecurityHub");
        if (securityHub == null || !securityHub.isObject() || !securityHub.path("ServiceEnabled").isBoolean()) {
            return;
        }
        SecurityHubState target = states.getForAccount(association.getTargetId(), region)
                .orElseGet(SecurityHubState::new);
        target.setEnabled(securityHub.path("ServiceEnabled").asBoolean());
        states.putForAccount(association.getTargetId(), region, target);
    }

    public List<SecurityHubAssociation> associations(String region) {
        return state(region).getAssociations().values().stream()
                .map(SecurityHubAssociation::copy)
                .toList();
    }

    public List<Map.Entry<String, JsonNode>> policies(String region) {
        return List.copyOf(state(region).getPolicies().entrySet());
    }

    public Map<String, String> tagsForResource(String region, String arn) {
        SecurityHubState state = state(region);
        JsonNode resource = taggedResource(state, region, arn);
        if (resource == null) {
            return Map.of();
        }
        JsonNode tags = resource.get("Tags");
        if (tags == null || !tags.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) result.put(entry.getKey(), entry.getValue().textValue());
        });
        return result;
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        SecurityHubState state = state(region);
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode current = resource.withObject("Tags");
        tags.forEach(current::put);
        validateTags(current);
        save(region, state);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        SecurityHubState state = state(region);
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode tags = resource.withObject("Tags");
        tagKeys.forEach(tags::remove);
        save(region, state);
    }

    private JsonNode taggedResource(SecurityHubState state, String region, String arn) {
        if (arn == null || !arn.startsWith("arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId() + ":")) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        if (arn.equals(hubArn(region)) || arn.equals(state.getAggregatorArn())) return null;
        String id = normalizePolicyId(arn);
        JsonNode policy = state.getPolicies().get(id);
        if (policy == null) throw notFound("The specified Security Hub resource was not found.");
        return policy;
    }

    private ObjectNode policyResource(SecurityHubState state, String region, String arn) {
        JsonNode resource = taggedResource(state, region, arn);
        if (!(resource instanceof ObjectNode object)) {
            throw notFound("The specified Security Hub resource does not support mutable tags in this emulator.");
        }
        return object;
    }

    public String hubArn(String region) {
        return "arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId() + ":hub/default";
    }

    public String policyArn(String region, String id) {
        return "arn:aws:securityhub:" + region + ":" + regionResolver.getAccountId()
                + ":configuration-policy/" + id;
    }

    private static SecurityHubState copyConfigurationState(SecurityHubState source) {
        SecurityHubState copy = new SecurityHubState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setEnabled(source.isEnabled());
        copy.setOrganizationConfigurationType(source.getOrganizationConfigurationType());
        copy.setOrganizationConfigurationStatus(source.getOrganizationConfigurationStatus());
        return copy;
    }

    private static JsonNode validateAggregatorRegions(String mode, JsonNode regions) {
        if (regions != null && !regions.isArray()) {
            throw invalid("Regions must be an array.");
        }
        if ("SPECIFIED_REGIONS".equals(mode) && (regions == null || regions.isEmpty())) {
            throw invalid("Regions is required when RegionLinkingMode is SPECIFIED_REGIONS.");
        }
        if (("NO_REGIONS".equals(mode) || "ALL_REGIONS".equals(mode))
                && regions != null && !regions.isEmpty()) {
            throw invalid("Regions must be empty for the selected RegionLinkingMode.");
        }
        return regions == null ? null : regions.deepCopy();
    }

    private static String requireLinkingMode(JsonNode request) {
        String mode = requireText(request, "RegionLinkingMode", "InvalidInputException");
        if (!LINKING_MODES.contains(mode)) {
            throw invalid("RegionLinkingMode is invalid.");
        }
        return mode;
    }

    private static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw invalid("Tags must be an object with at most 50 entries.");
        }
        tags.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128
                    || !entry.getValue().isTextual() || entry.getValue().textValue().length() > 256) {
                throw invalid("Tags contain an invalid key or value.");
            }
        });
    }

    private void save(String region, SecurityHubState state) {
        states.put(region, state);
    }

    private static String normalizePolicyId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        if (SELF_MANAGED.equals(identifier)) {
            return SELF_MANAGED;
        }
        int slash = identifier.lastIndexOf('/');
        return slash >= 0 ? identifier.substring(slash + 1) : identifier;
    }

    private static TargetRef target(JsonNode input) {
        JsonNode target = input == null ? null : input.get("Target");
        if (target == null || !target.isObject()) {
            throw invalid("Target is required.");
        }
        TargetRef found = null;
        for (Map.Entry<String, String> candidate : Map.of(
                "AccountId", "ACCOUNT", "OrganizationalUnitId", "ORGANIZATIONAL_UNIT", "RootId", "ROOT").entrySet()) {
            String value = text(target, candidate.getKey());
            if (value != null) {
                if (found != null) {
                    throw invalid("Target must contain exactly one identifier.");
                }
                found = new TargetRef(value, candidate.getValue());
            }
        }
        if (found == null) {
            throw invalid("Target identifier is required.");
        }
        if ("ACCOUNT".equals(found.type()) && !found.id().matches("\\d{12}")) {
            throw invalid("AccountId must be a 12 digit account ID.");
        }
        if ("ORGANIZATIONAL_UNIT".equals(found.type()) && !found.id().matches("ou-[a-z0-9]{4,32}-[a-z0-9]{8,32}")) {
            throw invalid("OrganizationalUnitId is invalid.");
        }
        if ("ROOT".equals(found.type()) && !found.id().matches("r-[a-z0-9]{4,32}")) {
            throw invalid("RootId is invalid.");
        }
        return found;
    }

    private static void requireAccountId(String value, String field) {
        if (value == null || !value.matches("\\d{12}")) {
            throw invalid(field + " must be a 12 digit account ID.");
        }
    }

    private static String requireText(JsonNode input, String field, String errorCode) {
        String value = text(input, field);
        if (value == null || value.isBlank()) {
            throw new AwsException(errorCode, field + " is required.", 400);
        }
        return value;
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ResourceConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private record TargetRef(String id, String type) {}
}
