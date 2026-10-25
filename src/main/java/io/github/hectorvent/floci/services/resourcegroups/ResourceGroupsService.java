package io.github.hectorvent.floci.services.resourcegroups;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.resourcegroups.model.ResourceGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ResourceGroupsService implements Resettable {
    static final String RESOURCE_TYPE = "AWS::EC2::CapacityReservation";
    private static final String POOL_TYPE = "AWS::EC2::CapacityReservationPool";
    private static final String GENERIC_TYPE = "AWS::ResourceGroups::Generic";
    static final int MAX_PAGES = 256;
    static final long PAGE_IDLE_MILLIS = 15 * 60 * 1000L;

    private final AccountAwareStorageBackend<ResourceGroup> groups;
    private final Ec2Service ec2;
    private final Clock clock;
    private final Map<String, Page> pages = new LinkedHashMap<>();

    private record Page(String groupArn, String generation, List<String> filter,
                        List<String> remaining, long lastRead) {}

    @Inject
    public ResourceGroupsService(StorageFactory storageFactory, Ec2Service ec2) {
        this(storageFactory.create("resource-groups", "resource-groups.json",
                new TypeReference<Map<String, ResourceGroup>>() {}), ec2, Clock.systemUTC());
    }

    ResourceGroupsService(AccountAwareStorageBackend<ResourceGroup> groups, Ec2Service ec2, Clock clock) {
        this.groups = groups;
        this.ec2 = ec2;
        this.clock = clock;
    }

    public synchronized ResourceGroup create(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("CreateGroup", request);
        String name = text(request, "Name", true);
        validName(name);
        if (name.toLowerCase(Locale.ROOT).startsWith("aws")) {
            throw bad("Group names beginning with AWS are reserved.");
        }
        if (request.hasNonNull("ResourceQuery")) {
            throw bad("Only service-configured capacity reservation pools are supported.");
        }
        List<Map<String, Object>> configuration = configuration(request.get("Configuration"));
        if (request.hasNonNull("Criticality") || request.hasNonNull("Owner") || request.hasNonNull("DisplayName")) {
            throw bad("Unsupported parameter for this operation");
        }
        String description = text(request, "Description", false);
        Map<String, String> tags = tags(request.get("Tags"));
        String key = key(region, name);
        if (groups.getForAccount(account, key).isPresent()) {
            throw bad("A resource group with this name already exists.");
        }
        ResourceGroup group = new ResourceGroup(name,
                AwsArnUtils.Arn.of("resource-groups", region, account, "group/" + name).toString(),
                description, tags, configuration, List.of(), UUID.randomUUID().toString());
        groups.putForAccount(account, key, group);
        return group;
    }

    public synchronized ResourceGroup get(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GetGroup", request);
        return selected(region, account, request);
    }

    public List<String> capacityReservationMembers(String region, String account, String groupArn) {
        // EC2 holds its capacity lock; GroupResources calls EC2 while holding this service's monitor.
        // Read the immutable storage snapshot without acquiring that monitor in the reverse order.
        if (groupArn == null || !groupArn.startsWith("arn:")) {
            throw bad("A capacity reservation group ARN is required.");
        }
        return selected(region, account, JsonNodeFactory.instance.objectNode().put("Group", groupArn)).members();
    }

    public synchronized ResourceGroup delete(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("DeleteGroup", request);
        ResourceGroup group = selected(region, account, request);
        groups.deleteForAccount(account, key(region, group.name()));
        pages.values().removeIf(page -> page.generation().equals(group.generation()));
        return group;
    }

    public synchronized Map<String, Object> add(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("GroupResources", request);
        ResourceGroup group = selected(region, account, request);
        JsonNode resources = request.get("ResourceArns");
        if (resources == null || !resources.isArray() || resources.isEmpty() || resources.size() > 10) {
            throw bad("ResourceArns must contain one to ten resource ARNs.");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (JsonNode resource : resources) {
            if (!resource.isTextual() || resource.asText().length() > 2048
                    || !AwsArnUtils.isArn(resource.asText())) {
                throw bad("ResourceArns must contain valid resource ARNs.");
            }
            requested.add(resource.asText());
        }
        List<String> succeeded = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (String resource : requested) {
            String failure = reservationFailure(region, account, resource);
            if (failure == null) {
                succeeded.add(resource);
            } else {
                failed.add(Map.of("ResourceArn", resource, "ErrorCode", failure,
                        "ErrorMessage", "The reservation identity or state does not allow membership."));
            }
        }
        // Validate every lookup before changing the stored group, including unexpected backend failures.
        Set<String> members = new LinkedHashSet<>(group.members());
        members.addAll(succeeded);
        groups.putForAccount(account, key(region, group.name()), group.withMembers(new ArrayList<>(members)));
        return Map.of("Succeeded", succeeded, "Failed", failed, "Pending", List.of());
    }

    private String reservationFailure(String region, String account, String resource) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        if (!arn.partition().equals(AwsRegions.partitionFor(region)) || !arn.service().equals("ec2")
                || !arn.resource().startsWith("capacity-reservation/")) {
            return "ResourceArnValidationException";
        }
        if (!arn.region().equals(region)) {
            return "ValidationException";
        }
        if (!arn.accountId().equals(account)
                || !arn.resource().matches("capacity-reservation/cr-[0-9a-f]{17}")) {
            return "ResourceGroupsValidationException";
        }
        List<CapacityReservation> found;
        try {
            found = ec2.describeCapacityReservations(region,
                    List.of(arn.resource().substring("capacity-reservation/".length())), Map.of());
        } catch (AwsException error) {
            if ("InvalidCapacityReservationId.NotFound".equals(error.getErrorCode())) {
                return "ResourceGroupsValidationException";
            }
            throw error;
        }
        if (found.size() != 1 || !resource.equals(found.getFirst().getCapacityReservationArn())
                || !account.equals(found.getFirst().getOwnerId())) {
            throw new AwsException("InternalServerErrorException", "Reservation lookup returned an inconsistent identity.", 500);
        }
        return "active".equals(found.getFirst().getState()) ? null : "ResourceGroupsValidationException";
    }

    public synchronized Map<String, Object> list(String region, String account, JsonNode request) {
        ResourceGroupsRequestShapes.check("ListGroupResources", request);
        ResourceGroup group = selected(region, account, request);
        int maximum = 50;
        if (request.has("MaxResults")) {
            JsonNode value = request.get("MaxResults");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 50) {
                throw bad("MaxResults must be between 1 and 50.");
            }
            maximum = value.intValue();
        }
        List<String> filter = filter(request.get("Filters"));
        String token = text(request, "NextToken", false);
        if (token != null && (token.length() > 8192 || !token.matches("[a-zA-Z0-9+/]*={0,2}"))) {
            throw bad("NextToken must be a base64 token.");
        }
        long now = clock.millis();
        pages.values().removeIf(page -> now - page.lastRead() >= PAGE_IDLE_MILLIS);
        List<String> selected;
        if (token == null) {
            selected = group.members();
        } else {
            Page page = pages.get(token);
            if (page == null || !page.groupArn().equals(group.arn())
                    || !page.generation().equals(group.generation()) || !page.filter().equals(filter)) {
                throw bad("NextToken does not match this group and filter, or has expired.");
            }
            pages.remove(token);
            pages.put(token, new Page(page.groupArn(), page.generation(), page.filter(), page.remaining(), now));
            selected = page.remaining().stream().filter(group.members()::contains).toList();
        }
        List<Map<String, String>> identifiers = selected.stream().limit(maximum)
                .map(arn -> Map.of("ResourceArn", arn, "ResourceType", RESOURCE_TYPE)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ResourceIdentifiers", identifiers);
        response.put("Resources", identifiers.stream().map(identifier -> Map.of("Identifier", identifier)).toList());
        if (selected.size() > maximum) {
            if (pages.size() >= MAX_PAGES) {
                pages.remove(pages.keySet().iterator().next());
            }
            String next = Base64.getEncoder().encodeToString(
                    UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
            pages.put(next, new Page(group.arn(), group.generation(), filter,
                    List.copyOf(selected.subList(maximum, selected.size())), now));
            response.put("NextToken", next);
        }
        return response;
    }

    private ResourceGroup selected(String region, String account, JsonNode request) {
        if (request.has("Group") && request.has("GroupName")) {
            throw bad("Specify Group or GroupName, not both.");
        }
        String selected = text(request, request.has("Group") ? "Group" : "GroupName", true);
        String name = selected;
        if (selected.startsWith("arn:")) {
            if (!AwsArnUtils.isArn(selected)) {
                throw bad("Invalid group ARN.");
            }
            AwsArnUtils.Arn arn = AwsArnUtils.parse(selected);
            if (!arn.partition().equals(AwsRegions.partitionFor(region)) || !arn.service().equals("resource-groups")
                    || !arn.region().equals(region) || !arn.accountId().equals(account)
                    || !arn.resource().startsWith("group/")) {
                throw bad("The group ARN must belong to the calling account and region.");
            }
            name = arn.resource().substring("group/".length());
        }
        validName(name);
        ResourceGroup group = groups.getForAccount(account, key(region, name))
                .orElseThrow(() -> new AwsException("NotFoundException", "The resource group was not found.", 404));
        String expected = AwsArnUtils.Arn.of("resource-groups", region, account, "group/" + name).toString();
        if (!group.arn().equals(expected)) {
            throw new AwsException("InternalServerErrorException", "Stored group identity is inconsistent.", 500);
        }
        return group;
    }

    private static List<Map<String, Object>> configuration(JsonNode configuration) {
        if (configuration == null || !configuration.isArray() || configuration.size() != 2) {
            throw bad("A capacity reservation pool Configuration is required.");
        }
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode item : configuration) {
            requireObject(item);
            String type = text(item, "Type", true);
            if (!types.add(type)) {
                throw bad("Configuration types must be distinct.");
            }
            JsonNode parameters = item.get("Parameters");
            if (POOL_TYPE.equals(type)) {
                if (parameters != null && (!parameters.isArray() || !parameters.isEmpty())) {
                    throw bad("CapacityReservationPool does not accept these parameters.");
                }
            } else if (GENERIC_TYPE.equals(type)) {
                if (parameters == null || !parameters.isArray() || parameters.size() != 1) {
                    throw bad("The allowed-resource-types parameter is required.");
                }
                JsonNode parameter = parameters.get(0);
                requireObject(parameter);
                JsonNode values = parameter.get("Values");
                if (!"allowed-resource-types".equals(text(parameter, "Name", true))
                        || values == null || !values.isArray() || values.size() != 1
                        || !values.get(0).isTextual() || !RESOURCE_TYPE.equals(values.get(0).asText())) {
                    throw bad("Only CapacityReservation resources are supported.");
                }
            } else {
                throw bad("Only capacity reservation pool configurations are supported.");
            }
        }
        return List.of(Map.of("Type", POOL_TYPE), Map.of("Type", GENERIC_TYPE, "Parameters",
                List.of(Map.of("Name", "allowed-resource-types", "Values", List.of(RESOURCE_TYPE)))));
    }

    private static List<String> filter(JsonNode filters) {
        if (filters == null || filters.isArray() && filters.isEmpty()) {
            return List.of();
        }
        if (!filters.isArray() || filters.size() != 1) {
            throw bad("Only one resource-type filter is supported.");
        }
        JsonNode filter = filters.get(0);
        requireObject(filter);
        JsonNode values = filter.get("Values");
        if (!"resource-type".equals(text(filter, "Name", true)) || values == null
                || !values.isArray() || values.isEmpty() || values.size() > 5) {
            throw bad("Invalid resource-type filter.");
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || !RESOURCE_TYPE.equals(value.asText())) {
                throw bad("The resource type is not allowed by the group configuration.");
            }
        }
        return List.of(RESOURCE_TYPE);
    }

    private static Map<String, String> tags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || tags.isNull()) {
            return result;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw bad("Tags must be an object containing at most 50 entries.");
        }
        tags.fields().forEachRemaining(entry -> {
            result.put(entry.getKey(), entry.getValue().asText());
        });
        return result;
    }

    private static void validName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,300}")) {
            throw bad("Invalid resource group name.");
        }
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static String text(JsonNode object, String field, boolean required) {
        JsonNode value = object.get(field);
        if ((value == null || value.isNull()) && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || required && value.asText().isEmpty()) {
            throw bad(field + " must be a string.");
        }
        return value.asText();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw bad("A JSON object is required.");
        }
    }

    private static AwsException bad(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    @Override
    public synchronized void clear() {
        groups.clear();
        pages.clear();
    }
}
