package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@ApplicationScoped
public class DmsService implements Resettable {

    private static final Pattern SUBNET_GROUP_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String SUBNET_GROUP_ID_FILTER = "replication-subnet-group-id";
    private static final int MINIMUM_AVAILABILITY_ZONES = 2;
    private static final String SUBNET_GROUP_ARN_RESOURCE_TYPE = "subgrp";
    private static final int DEFAULT_MAX_RECORDS = 100;
    private static final int MINIMUM_MAX_RECORDS = 20;
    private static final int MAXIMUM_MAX_RECORDS = 100;
    private static final Set<String> RESERVED_TAG_PREFIXES = Set.of("aws:", "dms:");

    private final AccountAwareStorageBackend<ReplicationSubnetGroup> subnetGroups;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;

    @Inject
    public DmsService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver) {
        this.subnetGroups = storageFactory.create("dms", "dms-replication-subnet-groups.json",
                new TypeReference<Map<String, ReplicationSubnetGroup>>() {});
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
    }

    public synchronized ReplicationSubnetGroup createReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireIdentifier(request);
        String description = requireDescription(request);
        List<String> subnetIds = requireSubnetIds(request);
        if (subnetGroups.get(storageKey(region, identifier)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsFault",
                    "The resource you are attempting to create already exists.", 400);
        }

        ReplicationSubnetGroup group = buildSubnetGroup(identifier, description, subnetIds, region);
        group.setTags(readTags(request.get("Tags")));
        subnetGroups.put(storageKey(region, identifier), group);
        return group;
    }

    /**
     * Pages by identifier through the shared opaque-cursor helper, so {@code Marker} stays
     * resumable when a group is created or deleted between requests. DMS documents a default
     * {@code MaxRecords} of 100 and a valid range of 20 to 100, and rejects a value outside that
     * range rather than clamping it.
     */
    public PaginatedResult<ReplicationSubnetGroup> describeReplicationSubnetGroups(JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        List<String> requestedIdentifiers = identifierFilters(request);
        List<ReplicationSubnetGroup> matching;
        if (!requestedIdentifiers.isEmpty()) {
            matching = requestedIdentifiers.stream()
                    .map(identifier -> subnetGroups.get(storageKey(region, identifier))
                            .orElseThrow(() -> notFound(identifier)))
                    .toList();
        } else {
            matching = subnetGroups.scan(key -> key.startsWith(region + "::"));
        }
        return Pagination.paginate(matching, ReplicationSubnetGroup::getReplicationSubnetGroupIdentifier,
                maxRecords, marker, DEFAULT_MAX_RECORDS, MAXIMUM_MAX_RECORDS,
                "InvalidParameterValueException");
    }

    public synchronized void deleteReplicationSubnetGroup(JsonNode request, String region) {
        String identifier = requireIdentifier(request);
        String key = storageKey(region, identifier);
        if (subnetGroups.get(key).isEmpty()) {
            throw notFound(identifier);
        }
        subnetGroups.delete(key);
    }

    public List<ResourceTag> listTagsForResource(JsonNode request, String region) {
        List<String> arnList = arnList(request);
        if (!arnList.isEmpty()) {
            List<ResourceTag> tags = new ArrayList<>();
            for (String arn : arnList) {
                resolveByArn(arn, region).getTags()
                        .forEach((key, value) -> tags.add(new ResourceTag(arn, key, value)));
            }
            return tags;
        }
        String resourceArn = requireResourceArn(request);
        return resolveByArn(resourceArn, region).getTags().entrySet().stream()
                .map(entry -> new ResourceTag(null, entry.getKey(), entry.getValue()))
                .toList();
    }

    public synchronized void addTagsToResource(JsonNode request, String region) {
        String resourceArn = requireResourceArn(request);
        JsonNode tagsNode = request == null ? null : request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            throw invalidParameter("The parameter Tags must be provided.");
        }
        Map<String, String> added = readTags(tagsNode);
        updateTags(resourceArn, region, tags -> tags.putAll(added));
    }

    public synchronized void removeTagsFromResource(JsonNode request, String region) {
        String resourceArn = requireResourceArn(request);
        JsonNode keysNode = request == null ? null : request.get("TagKeys");
        if (keysNode == null || keysNode.isNull()) {
            throw invalidParameter("The parameter TagKeys must be provided.");
        }
        List<String> keys = stringList(keysNode, "TagKeys");
        updateTags(resourceArn, region, tags -> keys.forEach(tags::remove));
    }

    @Override
    public void clear() {
        subnetGroups.clear();
    }

    private ReplicationSubnetGroup buildSubnetGroup(String identifier, String description,
                                                    List<String> subnetIds, String region) {
        Map<String, Subnet> resolved = new LinkedHashMap<>();
        ec2Service.describeSubnets(region, subnetIds, Map.of())
                .forEach(subnet -> resolved.put(subnet.getSubnetId(), subnet));
        List<String> requested = subnetIds.stream().distinct().toList();
        List<String> missing = requested.stream().filter(id -> !resolved.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new AwsException("InvalidSubnet",
                    "The subnet provided is invalid: " + missing + ".", 400);
        }

        // Response order follows the request rather than storage iteration order, so a describe
        // that follows a create returns the same subnet ordering every time.
        Map<String, String> availabilityZones = new LinkedHashMap<>();
        for (String subnetId : requested) {
            String availabilityZone = resolved.get(subnetId).getAvailabilityZone();
            if (availabilityZone == null) {
                throw new AwsException("InvalidSubnet",
                        "Subnet " + subnetId + " has no Availability Zone.", 400);
            }
            availabilityZones.put(subnetId, availabilityZone);
        }

        String vpcId = resolved.get(requested.getFirst()).getVpcId();
        boolean sameVpc = resolved.values().stream()
                .map(Subnet::getVpcId)
                .filter(Objects::nonNull)
                .allMatch(vpcId::equals);
        if (!sameVpc) {
            throw new AwsException("InvalidSubnet",
                    "The subnets provided for replication subnet group " + identifier
                            + " belong to more than one VPC.", 400);
        }

        if (Set.copyOf(availabilityZones.values()).size() < MINIMUM_AVAILABILITY_ZONES) {
            throw new AwsException("ReplicationSubnetGroupDoesNotCoverEnoughAZs",
                    "The replication subnet group does not cover enough Availability Zones (AZs)."
                            + " Edit the replication subnet group and add more AZs.", 400);
        }

        ReplicationSubnetGroup group = new ReplicationSubnetGroup();
        group.setReplicationSubnetGroupIdentifier(identifier);
        group.setReplicationSubnetGroupDescription(description);
        group.setVpcId(vpcId);
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(requested);
        group.setSubnetAvailabilityZones(availabilityZones);
        group.setSupportedNetworkTypes(List.of("IPV4"));
        return group;
    }

    /**
     * AWS stores the identifier as a lowercase string, so every lookup normalises the same way:
     * a group created as "MyGroup" is described and deleted as "mygroup".
     */
    private static String requireIdentifier(JsonNode request) {
        String value = text(request, "ReplicationSubnetGroupIdentifier");
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupIdentifier must be provided"
                    + " and must not be blank.");
        }
        String identifier = value.toLowerCase(Locale.ROOT);
        if (identifier.length() > 255 || !SUBNET_GROUP_IDENTIFIER.matcher(identifier).matches()) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must contain no more than 255"
                    + " alphanumeric characters, periods, underscores, or hyphens.");
        }
        if ("default".equals(identifier)) {
            throw invalidParameter("ReplicationSubnetGroupIdentifier must not be \"default\".");
        }
        return identifier;
    }

    /**
     * A description carrying a control character such as 0x01 is rejected here rather than
     * persisted, per the review of the upstream PR; AWS rejects non-printable control characters
     * in this member. Blank and absent stay the same parameter error they were.
     */
    private static String requireDescription(JsonNode request) {
        String description = text(request, "ReplicationSubnetGroupDescription");
        if (description == null || description.isBlank()) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must be provided"
                    + " and must not be blank.");
        }
        if (description.chars().anyMatch(Character::isISOControl)) {
            throw invalidParameter("The parameter ReplicationSubnetGroupDescription must contain only"
                    + " printable characters.");
        }
        return description;
    }

    /**
     * {@code MaxRecords} is modelled as an integer, so a non-integer value is a serialization error
     * rather than a parameter one. A value outside 20 to 100 is rejected, not clamped, which is
     * what a live account does.
     */
    private static Integer maxRecords(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxRecords");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw serialization("MaxRecords must be an integer.");
        }
        int value = node.intValue();
        if (value < MINIMUM_MAX_RECORDS || value > MAXIMUM_MAX_RECORDS) {
            throw invalidParameter("Invalid value " + value + " for MaxRecords. Must be between "
                    + MINIMUM_MAX_RECORDS + " and " + MAXIMUM_MAX_RECORDS + ".");
        }
        return value;
    }

    private static List<String> requireSubnetIds(JsonNode request) {
        JsonNode node = request == null ? null : request.get("SubnetIds");
        if (node == null || node.isNull()) {
            throw invalidParameter("The parameter SubnetIds must be provided and must not be empty.");
        }
        if (!node.isArray()) {
            throw serialization("SubnetIds must be a list of strings.");
        }
        if (node.isEmpty()) {
            throw invalidParameter("The parameter SubnetIds must be provided and must not be empty.");
        }
        List<String> subnetIds = stringList(node, "SubnetIds");
        if (subnetIds.stream().anyMatch(String::isBlank)) {
            throw invalidParameter("The parameter SubnetIds must contain subnet identifiers.");
        }
        return subnetIds;
    }

    private static List<String> identifierFilters(JsonNode request) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || filters.isNull()) {
            return List.of();
        }
        if (!filters.isArray()) {
            throw serialization("Filters must be a list of Name and Values pairs.");
        }
        List<String> identifiers = new ArrayList<>();
        for (JsonNode filter : filters) {
            if (!filter.isObject()) {
                throw serialization("Filters must be a list of Name and Values pairs.");
            }
            String name = text(filter, "Name");
            if (!SUBNET_GROUP_ID_FILTER.equals(name)) {
                throw invalidParameter("Invalid filter: " + name + ".");
            }
            JsonNode values = filter.get("Values");
            if (values == null || values.isNull()) {
                throw invalidParameter("The filter " + SUBNET_GROUP_ID_FILTER + " must have values.");
            }
            if (!values.isArray()) {
                throw serialization("Filter Values must be a list of strings.");
            }
            if (values.isEmpty()) {
                throw invalidParameter("The filter " + SUBNET_GROUP_ID_FILTER + " must have values.");
            }
            stringList(values, "Filter Values")
                    .forEach(value -> identifiers.add(value.toLowerCase(Locale.ROOT)));
        }
        return identifiers;
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundFault",
                "Replication subnet group " + identifier + " not found.", 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    /**
     * Reads a string member. Absent or explicitly null yields null; a member of any other JSON type
     * is a SerializationException, which is what AWS returns when a json-1.1 member will not
     * deserialize to its modelled type. Coercing it instead (asText on an object yields "") would
     * silently store a wrong value.
     */
    private static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw serialization(field + " must be a string.");
        }
        return node.textValue();
    }

    private static List<String> stringList(JsonNode array, String field) {
        if (!array.isArray()) {
            throw serialization(field + " must be a list of strings.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isTextual()) {
                throw serialization(field + " must be a list of strings.");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    private static String storageKey(String region, String identifier) {
        return region + "::" + identifier;
    }

    private void updateTags(String resourceArn, String region,
                            Consumer<Map<String, String>> mutation) {
        String key = subnetGroupKeyForArn(resourceArn, region);
        ReplicationSubnetGroup group = subnetGroups.get(key).orElseThrow(() -> notFoundForArn(resourceArn));
        Map<String, String> tags = new LinkedHashMap<>(group.getTags());
        mutation.accept(tags);
        group.setTags(tags);
        subnetGroups.put(key, group);
    }

    private ReplicationSubnetGroup resolveByArn(String resourceArn, String region) {
        return subnetGroups.get(subnetGroupKeyForArn(resourceArn, region))
                .orElseThrow(() -> notFoundForArn(resourceArn));
    }

    /**
     * DescribeReplicationSubnetGroups does not return an ARN, so Terraform builds
     * {@code arn:aws:dms:<region>:<account>:subgrp:<id>} itself and tags against that. Anything
     * that is not such an ARN names no DMS resource Floci holds, which is a ResourceNotFoundFault
     * rather than a parameter error.
     *
     * <p>An ARN naming another account or another Region is treated the same way. Tagging is not a
     * cross-account or cross-Region operation on AWS, and here it cannot be one either: storage is
     * scoped to the caller's account and keyed by the caller's Region, so honouring a foreign ARN
     * would silently reach the caller's own group of that name instead of the one named.
     */
    private String subnetGroupKeyForArn(String resourceArn, String callerRegion) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw notFoundForArn(resourceArn);
        }
        String[] resource = arn.resource().split(":", 2);
        if (!"dms".equals(arn.service()) || resource.length != 2
                || !SUBNET_GROUP_ARN_RESOURCE_TYPE.equals(resource[0]) || resource[1].isBlank()) {
            throw notFoundForArn(resourceArn);
        }
        if (!arn.accountId().isBlank() && !arn.accountId().equals(regionResolver.getAccountId())) {
            throw notFoundForArn(resourceArn);
        }
        if (arn.region() != null && !arn.region().isBlank() && !arn.region().equals(callerRegion)) {
            throw notFoundForArn(resourceArn);
        }
        return storageKey(callerRegion, resource[1].toLowerCase(Locale.ROOT));
    }

    private static List<String> arnList(JsonNode request) {
        JsonNode node = request == null ? null : request.get("ResourceArnList");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw serialization("ResourceArnList must be a list of strings.");
        }
        return stringList(node, "ResourceArnList");
    }

    private static String requireResourceArn(JsonNode request) {
        String resourceArn = text(request, "ResourceArn");
        if (resourceArn == null || resourceArn.isBlank()) {
            throw invalidParameter("The parameter ResourceArn must be provided and must not be blank.");
        }
        return resourceArn;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isArray()) {
            throw serialization("Tags must be a list of Key and Value pairs.");
        }
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw serialization("Tags must be a list of Key and Value pairs.");
            }
            String key = text(element, "Key");
            String value = text(element, "Value");
            if (key == null || key.isEmpty() || key.length() > 128 || isReserved(key)) {
                throw invalidParameter("Tag keys must be 1-128 characters and must not start with"
                        + " \"aws:\" or \"dms:\".");
            }
            String tagValue = value == null ? "" : value;
            if (tagValue.length() > 256 || isReserved(tagValue)) {
                throw invalidParameter("Tag values must be at most 256 characters and must not start"
                        + " with \"aws:\" or \"dms:\".");
            }
            tags.put(key, tagValue);
        }
        return tags;
    }

    private static boolean isReserved(String value) {
        return RESERVED_TAG_PREFIXES.stream().anyMatch(value::startsWith);
    }

    private static AwsException notFoundForArn(String resourceArn) {
        return new AwsException("ResourceNotFoundFault",
                "Resource " + resourceArn + " not found.", 400);
    }
}
