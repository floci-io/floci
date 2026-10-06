package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dms.model.DmsResource;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** Endpoint, replication instance and task identifiers share one shape in the DMS API reference. */
    private static final Pattern RESOURCE_IDENTIFIER = Pattern.compile("[A-Za-z][0-9A-Za-z-]*");
    private static final int RESOURCE_IDENTIFIER_ARN_MAX = 31;
    private static final String RESOURCE_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK_RUNNING = "running";

    /** Members of {@code Endpoint} that are strings on both CreateEndpoint and ModifyEndpoint. */
    private static final List<String> ENDPOINT_STRING_MEMBERS = List.of("EngineName", "Username",
            "ServerName", "DatabaseName", "ExtraConnectionAttributes", "CertificateArn",
            "ServiceAccessRoleArn", "ExternalTableDefinition");
    /** The engine-specific settings structures CreateEndpoint and ModifyEndpoint accept. */
    private static final List<String> ENDPOINT_SETTINGS = List.of("DynamoDbSettings", "S3Settings",
            "DmsTransferSettings", "MongoDbSettings", "KinesisSettings", "KafkaSettings",
            "ElasticsearchSettings", "NeptuneSettings", "RedshiftSettings", "PostgreSQLSettings",
            "MySQLSettings", "OracleSettings", "SybaseSettings", "MicrosoftSQLServerSettings",
            "IBMDb2Settings", "DocDbSettings", "RedisSettings", "GcpMySQLSettings", "TimestreamSettings");
    /**
     * Every member the DMS model types as {@code SecretString}. DMS accepts them but never returns
     * them, so they are dropped before a settings structure is stored.
     */
    private static final List<String> SECRET_MEMBERS = List.of("AsmPassword", "AuthPassword",
            "CertificatePem", "Password", "S3ObjectUrl", "SaslPassword", "SecurityDbEncryption",
            "SelectionRules", "SslClientKeyPassword");
    private static final Set<String> SSL_MODES = Set.of("none", "require", "verify-ca", "verify-full");
    private static final Set<String> MIGRATION_TYPES = Set.of("full-load", "cdc", "full-load-and-cdc");
    private static final Set<String> START_TYPES =
            Set.of("start-replication", "resume-processing", "reload-target");

    /** One of the three ARN-addressed resource families and the names DMS uses for it. */
    private record Kind(String label, AccountAwareStorageBackend<DmsResource> store, String arnType,
                        String idMember, String arnMember, int maxIdLength, boolean lowercaseId,
                        Map<String, List<String>> filters) {
    }

    /** A stored resource together with the storage key it lives under. */
    private record Found(Kind kind, String key, DmsResource resource) {
    }

    private final AccountAwareStorageBackend<ReplicationSubnetGroup> subnetGroups;
    private final Kind endpoints;
    private final Kind instances;
    private final Kind tasks;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;

    @Inject
    public DmsService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver) {
        this.subnetGroups = storageFactory.create("dms", "dms-replication-subnet-groups.json",
                new TypeReference<Map<String, ReplicationSubnetGroup>>() {});
        TypeReference<Map<String, DmsResource>> type = new TypeReference<>() {};
        this.endpoints = new Kind("Endpoint", storageFactory.create("dms", "dms-endpoints.json", type),
                "endpoint", "EndpointIdentifier", "EndpointArn", 255, false,
                Map.of("endpoint-arn", List.of("EndpointArn"),
                        "endpoint-type", List.of("EndpointType"),
                        "endpoint-id", List.of("EndpointIdentifier"),
                        "engine-name", List.of("EngineName")));
        this.instances = new Kind("Replication instance",
                storageFactory.create("dms", "dms-replication-instances.json", type),
                "rep", "ReplicationInstanceIdentifier", "ReplicationInstanceArn", 63, true,
                Map.of("replication-instance-arn", List.of("ReplicationInstanceArn"),
                        "replication-instance-id", List.of("ReplicationInstanceIdentifier"),
                        "replication-instance-class", List.of("ReplicationInstanceClass"),
                        "engine-version", List.of("EngineVersion")));
        this.tasks = new Kind("Replication task",
                storageFactory.create("dms", "dms-replication-tasks.json", type),
                "task", "ReplicationTaskIdentifier", "ReplicationTaskArn", 255, false,
                Map.of("replication-task-arn", List.of("ReplicationTaskArn"),
                        "replication-task-id", List.of("ReplicationTaskIdentifier"),
                        "migration-type", List.of("MigrationType"),
                        "endpoint-arn", List.of("SourceEndpointArn", "TargetEndpointArn"),
                        "replication-instance-arn", List.of("ReplicationInstanceArn")));
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
    }

    // ---------------------------------------------------------------- endpoints

    public synchronized DmsResource createEndpoint(JsonNode request, String region) {
        String identifier = requireResourceIdentifier(endpoints, request);
        String endpointType = requireEndpointType(request);
        if (text(request, "EngineName") == null || text(request, "EngineName").isBlank()) {
            throw invalidParameter("The parameter EngineName must be provided and must not be blank.");
        }
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.put("EndpointType", endpointType);
        attributes.put("Status", "active");
        attributes.put("SslMode", "none");
        putText(attributes, request, "KmsKeyId");
        applyEndpointMembers(attributes, request, false);
        return create(endpoints, region, identifier, attributes, request);
    }

    public PaginatedResult<DmsResource> describeEndpoints(JsonNode request, String region) {
        return describe(endpoints, request, region);
    }

    /**
     * Settings structures merge member by member into what is stored, as DMS documents for
     * {@code ExactSettings} false (the default); with {@code ExactSettings} true a structure in the
     * request replaces the stored one.
     */
    public synchronized DmsResource modifyEndpoint(JsonNode request, String region) {
        Found found = requireByArn(endpoints, request, endpoints.arnMember(), region);
        ObjectNode attributes = found.resource().getAttributes().deepCopy();
        rename(endpoints, region, found, attributes, request);
        if (text(request, "EndpointType") != null) {
            attributes.put("EndpointType", requireEndpointType(request));
        }
        applyEndpointMembers(attributes, request, bool(request, "ExactSettings", false));
        return save(found, attributes);
    }

    public synchronized DmsResource deleteEndpoint(JsonNode request, String region) {
        Found found = requireByArn(endpoints, request, endpoints.arnMember(), region);
        String arn = arnOf(endpoints, found.resource());
        boolean inUse = regionScan(tasks, region).stream()
                .anyMatch(task -> arn.equals(member(task, "SourceEndpointArn"))
                        || arn.equals(member(task, "TargetEndpointArn")));
        if (inUse) {
            throw invalidState("Endpoint " + arn + " is in use by a replication task and cannot be deleted.");
        }
        DmsResource removed = remove(found);
        removed.getAttributes().put("Status", "deleting");
        return removed;
    }

    // ---------------------------------------------------------------- replication instances

    /**
     * The instance is {@code available} as soon as it is created: there is no host to provision, and
     * the Terraform waiter accepts {@code available} on its first poll.
     */
    public synchronized DmsResource createReplicationInstance(JsonNode request, String region) {
        String identifier = requireResourceIdentifier(instances, request);
        if (text(request, "ReplicationInstanceClass") == null) {
            throw invalidParameter("The parameter ReplicationInstanceClass must be provided.");
        }
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.put("ReplicationInstanceStatus", "available");
        attributes.put("AllocatedStorage", 50);
        attributes.put("InstanceCreateTime", now());
        attributes.putArray("VpcSecurityGroups");
        attributes.put("AvailabilityZone", region + "a");
        attributes.put("PreferredMaintenanceWindow", "sun:06:00-sun:06:30");
        attributes.put("MultiAZ", false);
        attributes.put("EngineVersion", "3.5.4");
        attributes.put("AutoMinorVersionUpgrade", true);
        attributes.put("PubliclyAccessible", bool(request, "PubliclyAccessible", true));
        attributes.put("NetworkType", "IPV4");
        attributes.putArray("ReplicationInstancePrivateIpAddresses");
        attributes.putArray("ReplicationInstancePublicIpAddresses");
        putText(attributes, request, "AvailabilityZone");
        putText(attributes, request, "KmsKeyId");
        putText(attributes, request, "DnsNameServers");

        String subnetGroup = text(request, "ReplicationSubnetGroupIdentifier");
        if (subnetGroup == null) {
            // DMS falls back to the account's "default" group, which is why that name is reserved.
            subnetGroup = "default";
        } else {
            subnetGroup = subnetGroup.toLowerCase(Locale.ROOT);
            if (subnetGroups.get(storageKey(region, subnetGroup)).isEmpty()) {
                throw notFound(subnetGroup);
            }
        }
        attributes.putObject("ReplicationSubnetGroup").put("ReplicationSubnetGroupIdentifier", subnetGroup);
        applyInstanceMembers(attributes, request);
        return create(instances, region, identifier, attributes, request);
    }

    public PaginatedResult<DmsResource> describeReplicationInstances(JsonNode request, String region) {
        return describe(instances, request, region);
    }

    /**
     * Applied at once whatever {@code ApplyImmediately} says: Floci has no maintenance window to
     * defer to, and a change parked in {@code PendingModifiedValues} would read back as drift.
     */
    public synchronized DmsResource modifyReplicationInstance(JsonNode request, String region) {
        Found found = requireByArn(instances, request, instances.arnMember(), region);
        ObjectNode attributes = found.resource().getAttributes().deepCopy();
        rename(instances, region, found, attributes, request);
        applyInstanceMembers(attributes, request);
        return save(found, attributes);
    }

    public synchronized DmsResource deleteReplicationInstance(JsonNode request, String region) {
        Found found = requireByArn(instances, request, instances.arnMember(), region);
        String arn = arnOf(instances, found.resource());
        if (regionScan(tasks, region).stream().anyMatch(task -> arn.equals(member(task, "ReplicationInstanceArn")))) {
            throw invalidState("Replication instance " + arn + " has replication tasks and cannot be deleted.");
        }
        DmsResource removed = remove(found);
        removed.getAttributes().put("ReplicationInstanceStatus", "deleting");
        return removed;
    }

    /**
     * "default" cannot be created, so it is never stored: it resolves to the region's default-VPC
     * subnets at read time, which is the group an instance created without one lands in.
     */
    public Optional<ReplicationSubnetGroup> findReplicationSubnetGroup(String region, String identifier) {
        if (!"default".equals(identifier)) {
            return subnetGroups.get(storageKey(region, identifier));
        }
        List<String> defaultSubnets = ec2Service.describeSubnets(region, List.of(), Map.of()).stream()
                .filter(Subnet::isDefaultForAz)
                .map(Subnet::getSubnetId)
                .toList();
        if (defaultSubnets.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(buildSubnetGroup(identifier, "default", defaultSubnets, region));
        } catch (AwsException ignored) {
            // Safe to swallow: buildSubnetGroup only rejects the default subnets when they cannot
            // form a valid group (a default VPC trimmed below two AZs, or a subnet with no AZ).
            // There is then no usable default group, so this answers exactly as when the region
            // has no default subnets and the caller falls back to the bare identifier.
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- replication tasks

    /** A new task is {@code ready}, the state the Terraform create waiter targets. */
    public synchronized DmsResource createReplicationTask(JsonNode request, String region) {
        String identifier = requireResourceIdentifier(tasks, request);
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        attributes.put("SourceEndpointArn", requireEndpointOfType(request, "SourceEndpointArn", "SOURCE", region));
        attributes.put("TargetEndpointArn", requireEndpointOfType(request, "TargetEndpointArn", "TARGET", region));
        attributes.put("ReplicationInstanceArn",
                arnOf(instances, requireByArn(instances, request, "ReplicationInstanceArn", region).resource()));
        if (text(request, "MigrationType") == null) {
            throw invalidParameter("The parameter MigrationType must be provided.");
        }
        if (text(request, "TableMappings") == null) {
            throw invalidParameter("The parameter TableMappings must be provided.");
        }
        attributes.put("Status", "ready");
        attributes.put("ReplicationTaskCreationDate", now());
        applyTaskMembers(attributes, request);
        return create(tasks, region, identifier, attributes, request);
    }

    public PaginatedResult<DmsResource> describeReplicationTasks(JsonNode request, String region) {
        return describe(tasks, request, region);
    }

    public synchronized DmsResource modifyReplicationTask(JsonNode request, String region) {
        Found found = requireByArn(tasks, request, tasks.arnMember(), region);
        requireNotRunning(found, "modified");
        ObjectNode attributes = found.resource().getAttributes().deepCopy();
        rename(tasks, region, found, attributes, request);
        applyTaskMembers(attributes, request);
        return save(found, attributes);
    }

    public synchronized DmsResource deleteReplicationTask(JsonNode request, String region) {
        Found found = requireByArn(tasks, request, tasks.arnMember(), region);
        requireNotRunning(found, "deleted");
        DmsResource removed = remove(found);
        removed.getAttributes().put("Status", "deleting");
        return removed;
    }

    /**
     * The task goes straight to {@code running} and stays there until stopped: no data moves, so a
     * full-load task never finishes on its own the way it would against real endpoints.
     */
    public synchronized DmsResource startReplicationTask(JsonNode request, String region) {
        Found found = requireByArn(tasks, request, tasks.arnMember(), region);
        String startType = text(request, "StartReplicationTaskType");
        if (startType == null || !START_TYPES.contains(startType)) {
            throw invalidParameter("StartReplicationTaskType must be one of " + START_TYPES + ".");
        }
        requireNotRunning(found, "started");
        ObjectNode attributes = found.resource().getAttributes().deepCopy();
        attributes.put("Status", TASK_RUNNING);
        attributes.put("ReplicationTaskStartDate", now());
        return save(found, attributes);
    }

    public synchronized DmsResource stopReplicationTask(JsonNode request, String region) {
        Found found = requireByArn(tasks, request, tasks.arnMember(), region);
        if (!TASK_RUNNING.equals(member(found.resource(), "Status"))) {
            throw invalidState("Replication task " + arnOf(tasks, found.resource())
                    + " is currently not running.");
        }
        ObjectNode attributes = found.resource().getAttributes().deepCopy();
        attributes.put("Status", "stopped");
        return save(found, attributes);
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
        boolean inUse = regionScan(instances, region).stream()
                .anyMatch(instance -> identifier.equals(instance.getAttributes()
                        .path("ReplicationSubnetGroup").path("ReplicationSubnetGroupIdentifier").asText(null)));
        if (inUse) {
            throw invalidState("Replication subnet group " + identifier
                    + " is in use by a replication instance and cannot be deleted.");
        }
        subnetGroups.delete(key);
    }

    public List<ResourceTag> listTagsForResource(JsonNode request, String region) {
        List<String> arnList = arnList(request);
        if (!arnList.isEmpty()) {
            List<ResourceTag> tags = new ArrayList<>();
            for (String arn : arnList) {
                tagsByArn(arn, region)
                        .forEach((key, value) -> tags.add(new ResourceTag(arn, key, value)));
            }
            return tags;
        }
        String resourceArn = requireResourceArn(request);
        return tagsByArn(resourceArn, region).entrySet().stream()
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
        endpoints.store().clear();
        instances.store().clear();
        tasks.store().clear();
    }

    // ---------------------------------------------------------------- shared resource plumbing

    private DmsResource create(Kind kind, String region, String identifier, ObjectNode attributes,
                               JsonNode request) {
        if (findByIdentifier(kind, region, identifier).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsFault",
                    kind.label() + " " + identifier + " already exists.", 400);
        }
        String resourceId = text(request, "ResourceIdentifier");
        if (resourceId == null) {
            resourceId = randomResourceId();
        } else if (!isValidIdentifier(resourceId, RESOURCE_IDENTIFIER_ARN_MAX)) {
            throw invalidParameter("ResourceIdentifier must be 1-" + RESOURCE_IDENTIFIER_ARN_MAX
                    + " ASCII letters, digits and hyphens, begin with a letter, and not end with a"
                    + " hyphen or contain two consecutive hyphens.");
        }
        String key = storageKey(region, resourceId);
        if (kind.store().get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsFault",
                    "ResourceIdentifier " + resourceId + " is already in use.", 400);
        }
        attributes.put(kind.idMember(), identifier);
        attributes.put(kind.arnMember(), regionResolver.buildArn("dms", region, kind.arnType() + ":" + resourceId));
        DmsResource resource = new DmsResource();
        resource.setAttributes(attributes);
        resource.setTags(readTags(request.get("Tags")));
        kind.store().put(key, resource);
        return resource;
    }

    /**
     * Filters AND across names and OR across a filter's values. Values compare case-insensitively,
     * so {@code endpoint-type=source} matches the {@code SOURCE} DMS returns. A filter that matches
     * nothing is {@code ResourceNotFoundFault}, which is what the Terraform provider's finders
     * treat as gone; an unfiltered describe of nothing is an empty list.
     */
    private PaginatedResult<DmsResource> describe(Kind kind, JsonNode request, String region) {
        Integer maxRecords = maxRecords(request);
        String marker = text(request, "Marker");
        Map<String, List<String>> filters = filters(request, kind.filters().keySet());
        List<DmsResource> matching = regionScan(kind, region).stream()
                .filter(resource -> filters.entrySet().stream().allMatch(filter ->
                        kind.filters().get(filter.getKey()).stream()
                                .map(name -> member(resource, name))
                                .anyMatch(value -> value != null && filter.getValue().stream()
                                        .anyMatch(value::equalsIgnoreCase))))
                .toList();
        if (!filters.isEmpty() && matching.isEmpty()) {
            throw new AwsException("ResourceNotFoundFault",
                    "No " + kind.label() + " found matching provided filters.", 400);
        }
        return Pagination.paginate(matching, resource -> member(resource, kind.idMember()),
                maxRecords, marker, DEFAULT_MAX_RECORDS, MAXIMUM_MAX_RECORDS, "InvalidParameterValueException");
    }

    private void rename(Kind kind, String region, Found found, ObjectNode attributes, JsonNode request) {
        if (text(request, kind.idMember()) == null) {
            return;
        }
        String identifier = requireResourceIdentifier(kind, request);
        if (!identifier.equals(member(found.resource(), kind.idMember()))
                && findByIdentifier(kind, region, identifier).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsFault",
                    kind.label() + " " + identifier + " already exists.", 400);
        }
        attributes.put(kind.idMember(), identifier);
    }

    private DmsResource save(Found found, ObjectNode attributes) {
        found.resource().setAttributes(attributes);
        found.kind().store().put(found.key(), found.resource());
        return found.resource();
    }

    private static DmsResource remove(Found found) {
        found.kind().store().delete(found.key());
        DmsResource removed = new DmsResource();
        removed.setAttributes(found.resource().getAttributes().deepCopy());
        return removed;
    }

    private Optional<DmsResource> findByIdentifier(Kind kind, String region, String identifier) {
        return regionScan(kind, region).stream()
                .filter(resource -> identifier.equals(member(resource, kind.idMember())))
                .findFirst();
    }

    private List<DmsResource> regionScan(Kind kind, String region) {
        return kind.store().scan(key -> key.startsWith(region + "::"));
    }

    /** Resolves the ARN in {@code member} of the request to a stored resource of {@code kind}. */
    private Found requireByArn(Kind kind, JsonNode request, String member, String region) {
        String arn = text(request, member);
        if (arn == null || arn.isBlank()) {
            throw invalidParameter("The parameter " + member + " must be provided and must not be blank.");
        }
        String[] parsed = parseArn(arn, region);
        if (!kind.arnType().equals(parsed[0])) {
            throw notFoundForArn(arn);
        }
        String key = storageKey(region, parsed[1]);
        return kind.store().get(key).map(resource -> new Found(kind, key, resource))
                .orElseThrow(() -> notFoundForArn(arn));
    }

    private Kind kindForArnType(String arnType, String resourceArn) {
        for (Kind kind : List.of(endpoints, instances, tasks)) {
            if (kind.arnType().equals(arnType)) {
                return kind;
            }
        }
        throw notFoundForArn(resourceArn);
    }

    private String requireResourceIdentifier(Kind kind, JsonNode request) {
        String value = text(request, kind.idMember());
        if (value == null || value.isBlank()) {
            throw invalidParameter("The parameter " + kind.idMember() + " must be provided and must not be blank.");
        }
        if (!isValidIdentifier(value, kind.maxIdLength())) {
            throw invalidParameter(kind.idMember() + " must be 1-" + kind.maxIdLength()
                    + " ASCII letters, digits and hyphens, begin with a letter, and not end with a"
                    + " hyphen or contain two consecutive hyphens.");
        }
        return kind.lowercaseId() ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static boolean isValidIdentifier(String value, int maxLength) {
        return value.length() <= maxLength && RESOURCE_IDENTIFIER.matcher(value).matches()
                && !value.contains("--") && !value.endsWith("-");
    }

    private String requireEndpointOfType(JsonNode request, String member, String type, String region) {
        DmsResource endpoint = requireByArn(endpoints, request, member, region).resource();
        String arn = arnOf(endpoints, endpoint);
        if (!type.equals(member(endpoint, "EndpointType"))) {
            throw invalidParameter(member + " " + arn + " is not a " + type.toLowerCase(Locale.ROOT) + " endpoint.");
        }
        return arn;
    }

    /** DMS accepts the lowercase enum value and returns it uppercase. */
    private static String requireEndpointType(JsonNode request) {
        String value = text(request, "EndpointType");
        if (value == null || !Set.of("source", "target").contains(value.toLowerCase(Locale.ROOT))) {
            throw invalidParameter("The parameter EndpointType must be source or target.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static void applyEndpointMembers(ObjectNode attributes, JsonNode request, boolean exactSettings) {
        ENDPOINT_STRING_MEMBERS.forEach(name -> putText(attributes, request, name));
        String sslMode = text(request, "SslMode");
        if (sslMode != null) {
            if (!SSL_MODES.contains(sslMode)) {
                throw invalidParameter("SslMode must be one of " + SSL_MODES + ".");
            }
            attributes.put("SslMode", sslMode);
        }
        Integer port = integer(request, "Port");
        if (port != null) {
            attributes.put("Port", port);
        }
        for (String name : ENDPOINT_SETTINGS) {
            JsonNode settings = request == null ? null : request.get(name);
            if (settings == null || settings.isNull()) {
                continue;
            }
            if (!settings.isObject()) {
                throw serialization(name + " must be a structure.");
            }
            ObjectNode stored = ((ObjectNode) settings).deepCopy();
            stored.remove(SECRET_MEMBERS);
            if (!exactSettings && attributes.get(name) instanceof ObjectNode existing) {
                existing.setAll(stored);
            } else {
                attributes.set(name, stored);
            }
        }
    }

    private static void applyInstanceMembers(ObjectNode attributes, JsonNode request) {
        putText(attributes, request, "ReplicationInstanceClass");
        putText(attributes, request, "PreferredMaintenanceWindow");
        putText(attributes, request, "EngineVersion");
        putText(attributes, request, "NetworkType");
        String instanceClass = attributes.path("ReplicationInstanceClass").asText();
        if (!instanceClass.startsWith("dms.")) {
            throw invalidParameter("Invalid ReplicationInstanceClass " + instanceClass + ".");
        }
        Integer storage = integer(request, "AllocatedStorage");
        if (storage != null) {
            if (storage < 5 || storage > 6144) {
                throw invalidParameter("AllocatedStorage must be between 5 and 6144.");
            }
            attributes.put("AllocatedStorage", storage);
        }
        for (String name : List.of("MultiAZ", "AutoMinorVersionUpgrade")) {
            JsonNode value = request == null ? null : request.get(name);
            if (value != null && !value.isNull()) {
                attributes.put(name, bool(request, name, false));
            }
        }
        JsonNode groups = request == null ? null : request.get("VpcSecurityGroupIds");
        if (groups != null && !groups.isNull()) {
            ArrayNode memberships = attributes.putArray("VpcSecurityGroups");
            stringList(groups, "VpcSecurityGroupIds").forEach(id ->
                    memberships.addObject().put("VpcSecurityGroupId", id).put("Status", "active"));
        }
        JsonNode kerberos = request == null ? null : request.get("KerberosAuthenticationSettings");
        if (kerberos != null && !kerberos.isNull()) {
            if (!kerberos.isObject()) {
                throw serialization("KerberosAuthenticationSettings must be a structure.");
            }
            // Only the members DMS models; anything else a caller sends is neither stored nor echoed.
            ObjectNode stored = attributes.putObject("KerberosAuthenticationSettings");
            for (String member : List.of("KeyCacheSecretId", "KeyCacheSecretIamArn", "Krb5FileContents")) {
                putText(stored, kerberos, member);
            }
        }
    }

    private static void applyTaskMembers(ObjectNode attributes, JsonNode request) {
        String migrationType = text(request, "MigrationType");
        if (migrationType != null) {
            if (!MIGRATION_TYPES.contains(migrationType)) {
                throw invalidParameter("MigrationType must be one of " + MIGRATION_TYPES + ".");
            }
            attributes.put("MigrationType", migrationType);
        }
        for (String name : List.of("TableMappings", "ReplicationTaskSettings")) {
            String value = text(request, name);
            if (value != null) {
                requireJsonObject(name, value);
                attributes.put(name, value);
            }
        }
        putText(attributes, request, "CdcStartPosition");
        putText(attributes, request, "CdcStopPosition");
        putText(attributes, request, "TaskData");
    }

    private static void requireJsonObject(String member, String value) {
        try {
            if (JSON.readTree(value) instanceof ObjectNode) {
                return;
            }
        } catch (JsonProcessingException e) {
            // reported below
        }
        throw invalidParameter(member + " must be a JSON object.");
    }

    private void requireNotRunning(Found found, String verb) {
        if (TASK_RUNNING.equals(member(found.resource(), "Status"))) {
            throw invalidState("Replication task " + arnOf(tasks, found.resource())
                    + " is running and cannot be " + verb + ". Stop it first.");
        }
    }

    private static void putText(ObjectNode attributes, JsonNode request, String name) {
        String value = text(request, name);
        if (value != null) {
            attributes.put(name, value);
        }
    }

    private static String member(DmsResource resource, String name) {
        return resource.getAttributes().path(name).asText(null);
    }

    private static String arnOf(Kind kind, DmsResource resource) {
        return member(resource, kind.arnMember());
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw serialization(field + " must be an integer.");
        }
        return node.intValue();
    }

    private static boolean bool(JsonNode request, String field, boolean defaultValue) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw serialization(field + " must be a boolean.");
        }
        return node.booleanValue();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    /** DMS-generated ARN suffixes are 26 characters of the base32 alphabet. */
    private static String randomResourceId() {
        StringBuilder id = new StringBuilder(26);
        for (int i = 0; i < 26; i++) {
            id.append(RESOURCE_ID_ALPHABET.charAt(RANDOM.nextInt(RESOURCE_ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static AwsException invalidState(String message) {
        return new AwsException("InvalidResourceStateFault", message, 400);
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
        return filters(request, Set.of(SUBNET_GROUP_ID_FILTER)).getOrDefault(SUBNET_GROUP_ID_FILTER, List.of())
                .stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    /** Parses {@code Filters} into name to values, in request order, rejecting a name not in {@code validNames}. */
    private static Map<String, List<String>> filters(JsonNode request, Set<String> validNames) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters == null || filters.isNull()) {
            return parsed;
        }
        if (!filters.isArray()) {
            throw serialization("Filters must be a list of Name and Values pairs.");
        }
        for (JsonNode filter : filters) {
            if (!filter.isObject()) {
                throw serialization("Filters must be a list of Name and Values pairs.");
            }
            String name = text(filter, "Name");
            if (!validNames.contains(name)) {
                throw invalidParameter("Invalid filter: " + name + ".");
            }
            JsonNode values = filter.get("Values");
            if (values == null || values.isNull()) {
                throw invalidParameter("The filter " + name + " must have values.");
            }
            if (!values.isArray()) {
                throw serialization("Filter Values must be a list of strings.");
            }
            if (values.isEmpty()) {
                throw invalidParameter("The filter " + name + " must have values.");
            }
            parsed.computeIfAbsent(name, key -> new ArrayList<>()).addAll(stringList(values, "Filter Values"));
        }
        return parsed;
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
        String[] parsed = parseArn(resourceArn, region);
        if (SUBNET_GROUP_ARN_RESOURCE_TYPE.equals(parsed[0])) {
            String key = storageKey(region, parsed[1].toLowerCase(Locale.ROOT));
            ReplicationSubnetGroup group = subnetGroups.get(key).orElseThrow(() -> notFoundForArn(resourceArn));
            Map<String, String> tags = new LinkedHashMap<>(group.getTags());
            mutation.accept(tags);
            group.setTags(tags);
            subnetGroups.put(key, group);
            return;
        }
        Kind kind = kindForArnType(parsed[0], resourceArn);
        String key = storageKey(region, parsed[1]);
        DmsResource resource = kind.store().get(key).orElseThrow(() -> notFoundForArn(resourceArn));
        Map<String, String> tags = new LinkedHashMap<>(resource.getTags());
        mutation.accept(tags);
        resource.setTags(tags);
        kind.store().put(key, resource);
    }

    private Map<String, String> tagsByArn(String resourceArn, String region) {
        String[] parsed = parseArn(resourceArn, region);
        if (SUBNET_GROUP_ARN_RESOURCE_TYPE.equals(parsed[0])) {
            return subnetGroups.get(storageKey(region, parsed[1].toLowerCase(Locale.ROOT)))
                    .orElseThrow(() -> notFoundForArn(resourceArn)).getTags();
        }
        return kindForArnType(parsed[0], resourceArn).store().get(storageKey(region, parsed[1]))
                .orElseThrow(() -> notFoundForArn(resourceArn)).getTags();
    }

    /**
     * Splits a DMS ARN into its resource type and resource id. DescribeReplicationSubnetGroups does
     * not return an ARN, so Terraform builds {@code arn:aws:dms:<region>:<account>:subgrp:<id>}
     * itself and tags against that; endpoints ({@code endpoint}), replication instances
     * ({@code rep}) and tasks ({@code task}) carry the ARN DMS minted. Anything that is not such an
     * ARN names no DMS resource Floci holds, which is a ResourceNotFoundFault rather than a
     * parameter error.
     *
     * <p>An ARN naming another account or another Region is treated the same way. Tagging is not a
     * cross-account or cross-Region operation on AWS, and here it cannot be one either: storage is
     * scoped to the caller's account and keyed by the caller's Region, so honouring a foreign ARN
     * would silently reach the caller's own resource of that name instead of the one named.
     */
    private String[] parseArn(String resourceArn, String callerRegion) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw notFoundForArn(resourceArn);
        }
        String[] resource = arn.resource().split(":", 2);
        if (!"dms".equals(arn.service()) || resource.length != 2 || resource[1].isBlank()) {
            throw notFoundForArn(resourceArn);
        }
        if (!arn.accountId().isBlank() && !arn.accountId().equals(regionResolver.getAccountId())) {
            throw notFoundForArn(resourceArn);
        }
        if (arn.region() != null && !arn.region().isBlank() && !arn.region().equals(callerRegion)) {
            throw notFoundForArn(resourceArn);
        }
        return resource;
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
