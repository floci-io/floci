package io.github.hectorvent.floci.services.datasync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datasync.model.DataSyncAgent;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocation;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocationType;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTaggable;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * AWS DataSync management plane.
 *
 * <p>Agents report {@code ONLINE} and tasks {@code AVAILABLE} from the moment they are
 * created, so SDK and Terraform waiters finish on their first poll. Every
 * {@code DescribeLocation*} answer is projected out of the create request that produced
 * the location, with the location's {@code LocationUri} derived from the same request.
 * Credential members ({@code Password}, {@code SecretKey}, SAS tokens, Kerberos files)
 * are dropped before the configuration is stored, matching AWS, which never reads them
 * back.
 */
@ApplicationScoped
public class DataSyncService {

    private static final Logger LOG = Logger.getLogger(DataSyncService.class);

    private static final String HEX_ALPHABET = "0123456789abcdef";
    private static final int RESOURCE_ID_LENGTH = 17;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String AGENT_STATUS_ONLINE = "ONLINE";
    public static final String TASK_STATUS_AVAILABLE = "AVAILABLE";
    public static final String DEFAULT_TASK_MODE = "BASIC";

    /** Reported by DescribeAgent and ListAgents; floci runs no agent software. */
    public static final String AGENT_PLATFORM_VERSION = "1.0.0";

    private static final int DEFAULT_MAX_RESULTS = 100;

    private static final Set<String> CREDENTIAL_MEMBERS = Set.of(
            "Password", "SecretKey", "SasConfiguration", "KerberosKeytab", "KerberosKrb5Conf");

    private final StorageBackend<String, DataSyncAgent> agents;
    private final StorageBackend<String, DataSyncLocation> locations;
    private final StorageBackend<String, DataSyncTask> tasks;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public DataSyncService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper mapper) {
        this.agents = storageFactory.create("datasync", "datasync-agents.json",
                new TypeReference<Map<String, DataSyncAgent>>() {});
        this.locations = storageFactory.create("datasync", "datasync-locations.json",
                new TypeReference<Map<String, DataSyncLocation>>() {});
        this.tasks = storageFactory.create("datasync", "datasync-tasks.json",
                new TypeReference<Map<String, DataSyncTask>>() {});
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    /** A page of list results plus the token that resumes after it, or {@code null} at the end. */
    public record Page<T>(List<T> items, String nextToken) {
    }

    // ──────────────────────────── Agents ────────────────────────────

    public DataSyncAgent createAgent(JsonNode request, String region) {
        String activationKey = requiredText(request, "ActivationKey", "CreateAgent");
        DataSyncAgent agent = new DataSyncAgent();
        agent.setAgentArn(regionResolver.buildArn("datasync", region, "agent/agent-" + randomResourceId()));
        agent.setName(request.path("AgentName").asText(""));
        agent.setStatus(AGENT_STATUS_ONLINE);
        agent.setActivationKey(activationKey);
        agent.setVpcEndpointId(optionalText(request, "VpcEndpointId"));
        agent.setSubnetArns(stringList(request.path("SubnetArns")));
        agent.setSecurityGroupArns(stringList(request.path("SecurityGroupArns")));
        agent.setEndpointType(agent.getVpcEndpointId() != null ? "PRIVATE_LINK" : "PUBLIC");
        agent.setPlatformVersion(AGENT_PLATFORM_VERSION);
        agent.setCreationTime(Instant.now());
        agent.setLastConnectionTime(agent.getCreationTime());
        agent.setTags(tagsOf(request.path("Tags")));

        agents.put(agent.getAgentArn(), agent);
        LOG.infov("Created DataSync agent: {0}", agent.getAgentArn());
        return agent;
    }

    public DataSyncAgent getAgent(String agentArn) {
        requireArn(agentArn, "AgentArn");
        return agents.get(agentArn).orElseThrow(() -> new AwsException("InvalidRequestException",
                "Agent " + agentArn + " is not found.", 400));
    }

    public DataSyncAgent updateAgent(String agentArn, String name) {
        DataSyncAgent agent = getAgent(agentArn);
        if (name != null) {
            agent.setName(name);
        }
        agents.put(agentArn, agent);
        return agent;
    }

    public void deleteAgent(String agentArn) {
        getAgent(agentArn);
        agents.delete(agentArn);
        LOG.infov("Deleted DataSync agent: {0}", agentArn);
    }

    public Page<DataSyncAgent> listAgents(String nextToken, int maxResults) {
        List<DataSyncAgent> all = agents.scan(key -> true);
        all.sort(Comparator.comparing(DataSyncAgent::getAgentArn));
        return paginate(all, DataSyncAgent::getAgentArn, nextToken, maxResults);
    }

    // ─────────────────────────── Locations ───────────────────────────

    public DataSyncLocation createLocation(DataSyncLocationType type, JsonNode request, String region) {
        for (String member : type.requiredMembers()) {
            if (!hasValue(request, member)) {
                throw new AwsException("InvalidRequestException",
                        member + " is required for CreateLocation" + type.operationSuffix() + ".", 400);
            }
        }

        ObjectNode configuration = mapper.createObjectNode();
        request.properties().forEach(member -> {
            if (!"Tags".equals(member.getKey())) {
                configuration.set(member.getKey(), member.getValue().deepCopy());
            }
        });
        stripCredentials(configuration);
        applyLocationDefaults(type, configuration, region);

        DataSyncLocation location = new DataSyncLocation();
        location.setLocationArn(regionResolver.buildArn("datasync", region, "location/loc-" + randomResourceId()));
        location.setLocationType(type);
        location.setRegion(region);
        location.setConfiguration(configuration);
        location.setLocationUri(buildLocationUri(type, configuration, region));
        location.setCreationTime(Instant.now());
        location.setTags(tagsOf(request.path("Tags")));

        locations.put(location.getLocationArn(), location);
        LOG.infov("Created DataSync {0} location: {1}", type.operationSuffix(), location.getLocationArn());
        return location;
    }

    public DataSyncLocation getLocation(String locationArn) {
        requireArn(locationArn, "LocationArn");
        return locations.get(locationArn).orElseThrow(() -> new AwsException("InvalidRequestException",
                "Location " + locationArn + " is not found.", 400));
    }

    public DataSyncLocation getLocation(String locationArn, DataSyncLocationType expected) {
        DataSyncLocation location = getLocation(locationArn);
        if (location.getLocationType() != expected) {
            throw new AwsException("InvalidRequestException",
                    "Location " + locationArn + " is a " + location.getLocationType().operationSuffix()
                            + " location, not a " + expected.operationSuffix() + " location.", 400);
        }
        return location;
    }

    public DataSyncLocation updateLocation(DataSyncLocationType type, JsonNode request) {
        String locationArn = requiredText(request, "LocationArn", "UpdateLocation" + type.operationSuffix());
        DataSyncLocation location = getLocation(locationArn, type);

        ObjectNode configuration = location.getConfiguration().deepCopy();
        request.properties().forEach(member -> {
            if (!"LocationArn".equals(member.getKey())) {
                configuration.set(member.getKey(), member.getValue().deepCopy());
            }
        });
        stripCredentials(configuration);
        applyLocationDefaults(type, configuration, location.getRegion());

        location.setConfiguration(configuration);
        location.setLocationUri(buildLocationUri(type, configuration, location.getRegion()));
        locations.put(locationArn, location);
        LOG.infov("Updated DataSync {0} location: {1}", type.operationSuffix(), locationArn);
        return location;
    }

    public void deleteLocation(String locationArn) {
        getLocation(locationArn);
        locations.delete(locationArn);
        LOG.infov("Deleted DataSync location: {0}", locationArn);
    }

    public Page<DataSyncLocation> listLocations(JsonNode filters, String nextToken, int maxResults) {
        List<DataSyncLocation> all = new ArrayList<>();
        for (DataSyncLocation location : locations.scan(key -> true)) {
            if (matchesLocationFilters(location, filters)) {
                all.add(location);
            }
        }
        all.sort(Comparator.comparing(DataSyncLocation::getLocationArn));
        return paginate(all, DataSyncLocation::getLocationArn, nextToken, maxResults);
    }

    // ───────────────────────────── Tasks ─────────────────────────────

    public DataSyncTask createTask(JsonNode request, String region) {
        String sourceArn = requiredText(request, "SourceLocationArn", "CreateTask");
        String destinationArn = requiredText(request, "DestinationLocationArn", "CreateTask");
        getLocation(sourceArn);
        getLocation(destinationArn);

        DataSyncTask task = new DataSyncTask();
        task.setTaskArn(regionResolver.buildArn("datasync", region, "task/task-" + randomResourceId()));
        task.setName(request.path("Name").asText(""));
        task.setStatus(TASK_STATUS_AVAILABLE);
        task.setTaskMode(request.path("TaskMode").asText(DEFAULT_TASK_MODE));
        task.setSourceLocationArn(sourceArn);
        task.setDestinationLocationArn(destinationArn);
        task.setCloudWatchLogGroupArn(optionalText(request, "CloudWatchLogGroupArn"));
        task.setOptions(mergedOptions(request.get("Options")));
        task.setExcludes(filterList(request.get("Excludes")));
        task.setIncludes(filterList(request.get("Includes")));
        task.setSchedule(copyOrNull(request.get("Schedule")));
        task.setManifestConfig(copyOrNull(request.get("ManifestConfig")));
        task.setTaskReportConfig(copyOrNull(request.get("TaskReportConfig")));
        task.setCreationTime(Instant.now());
        task.setTags(tagsOf(request.path("Tags")));

        tasks.put(task.getTaskArn(), task);
        LOG.infov("Created DataSync task: {0}", task.getTaskArn());
        return task;
    }

    public DataSyncTask getTask(String taskArn) {
        requireArn(taskArn, "TaskArn");
        return tasks.get(taskArn).orElseThrow(() -> new AwsException("InvalidRequestException",
                "Task " + taskArn + " is not found.", 400));
    }

    public DataSyncTask updateTask(JsonNode request) {
        String taskArn = requiredText(request, "TaskArn", "UpdateTask");
        DataSyncTask task = getTask(taskArn);

        if (request.hasNonNull("Name")) {
            task.setName(request.get("Name").asText());
        }
        if (request.hasNonNull("CloudWatchLogGroupArn")) {
            task.setCloudWatchLogGroupArn(request.get("CloudWatchLogGroupArn").asText());
        }
        if (request.hasNonNull("Options")) {
            task.setOptions(mergedOptions(request.get("Options")));
        }
        if (request.hasNonNull("Excludes")) {
            task.setExcludes(filterList(request.get("Excludes")));
        }
        if (request.hasNonNull("Includes")) {
            task.setIncludes(filterList(request.get("Includes")));
        }
        if (request.hasNonNull("Schedule")) {
            task.setSchedule(copyOrNull(request.get("Schedule")));
        }
        if (request.hasNonNull("ManifestConfig")) {
            task.setManifestConfig(copyOrNull(request.get("ManifestConfig")));
        }
        if (request.hasNonNull("TaskReportConfig")) {
            task.setTaskReportConfig(copyOrNull(request.get("TaskReportConfig")));
        }

        tasks.put(taskArn, task);
        LOG.infov("Updated DataSync task: {0}", taskArn);
        return task;
    }

    public void deleteTask(String taskArn) {
        getTask(taskArn);
        tasks.delete(taskArn);
        LOG.infov("Deleted DataSync task: {0}", taskArn);
    }

    public Page<DataSyncTask> listTasks(JsonNode filters, String nextToken, int maxResults) {
        List<DataSyncTask> all = new ArrayList<>();
        for (DataSyncTask task : tasks.scan(key -> true)) {
            if (matchesTaskFilters(task, filters)) {
                all.add(task);
            }
        }
        all.sort(Comparator.comparing(DataSyncTask::getTaskArn));
        return paginate(all, DataSyncTask::getTaskArn, nextToken, maxResults);
    }

    // ────────────────────────────── Tags ──────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        return new LinkedHashMap<>(findTaggable(resourceArn).getTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        DataSyncTaggable resource = findTaggable(resourceArn);
        resource.getTags().putAll(tags);
        persist(resourceArn, resource);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        DataSyncTaggable resource = findTaggable(resourceArn);
        tagKeys.forEach(resource.getTags()::remove);
        persist(resourceArn, resource);
    }

    private DataSyncTaggable findTaggable(String resourceArn) {
        requireArn(resourceArn, "ResourceArn");
        return agents.get(resourceArn).<DataSyncTaggable>map(agent -> agent)
                .or(() -> locations.get(resourceArn).map(location -> location))
                .or(() -> tasks.get(resourceArn).map(task -> task))
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Resource " + resourceArn + " is not found.", 400));
    }

    private void persist(String resourceArn, DataSyncTaggable resource) {
        if (resource instanceof DataSyncAgent agent) {
            agents.put(resourceArn, agent);
        } else if (resource instanceof DataSyncLocation location) {
            locations.put(resourceArn, location);
        } else {
            tasks.put(resourceArn, (DataSyncTask) resource);
        }
    }

    // ─────────────────────── Location derivation ───────────────────────

    private void applyLocationDefaults(DataSyncLocationType type, ObjectNode configuration, String region) {
        switch (type) {
            case AZURE_BLOB -> {
                defaultText(configuration, "BlobType", "BLOCK");
                defaultText(configuration, "AccessTier", "HOT");
            }
            case EFS -> defaultText(configuration, "InTransitEncryption", "NONE");
            case FSX_ONTAP -> {
                String svmArn = configuration.path("StorageVirtualMachineArn").asText("");
                String fileSystemId = fsxFileSystemId(svmArn);
                if (!fileSystemId.isEmpty()) {
                    configuration.put("FsxFilesystemArn", "arn:aws:fsx:" + arnRegion(svmArn, region) + ":"
                            + arnAccountId(svmArn) + ":file-system/" + fileSystemId);
                }
            }
            case HDFS -> {
                if (!configuration.hasNonNull("BlockSize")) {
                    configuration.put("BlockSize", 134217728);
                }
                if (!configuration.hasNonNull("ReplicationFactor")) {
                    configuration.put("ReplicationFactor", 3);
                }
                ObjectNode qop = objectMember(configuration, "QopConfiguration");
                defaultText(qop, "RpcProtection", "PRIVACY");
                defaultText(qop, "DataTransferProtection", "PRIVACY");
            }
            case NFS -> defaultMountOptions(configuration);
            case OBJECT_STORAGE -> {
                defaultText(configuration, "ServerProtocol", "HTTPS");
                if (!configuration.hasNonNull("ServerPort")) {
                    configuration.put("ServerPort",
                            "HTTP".equals(configuration.path("ServerProtocol").asText()) ? 80 : 443);
                }
            }
            case S3 -> defaultText(configuration, "S3StorageClass", "STANDARD");
            case SMB -> {
                defaultText(configuration, "AuthenticationType", "NTLM");
                defaultMountOptions(configuration);
            }
            default -> {
                // FSx Lustre, FSx Windows and FSx OpenZFS carry no server-side defaults.
            }
        }
    }

    private void defaultMountOptions(ObjectNode configuration) {
        defaultText(objectMember(configuration, "MountOptions"), "Version", "AUTOMATIC");
    }

    private static ObjectNode objectMember(ObjectNode parent, String member) {
        return parent.get(member) instanceof ObjectNode existing ? existing : parent.putObject(member);
    }

    private static void defaultText(ObjectNode node, String member, String value) {
        if (!node.hasNonNull(member) || node.get(member).asText().isEmpty()) {
            node.put(member, value);
        }
    }

    private void stripCredentials(ObjectNode configuration) {
        CREDENTIAL_MEMBERS.forEach(configuration::remove);
        JsonNode protocol = configuration.get("Protocol");
        if (protocol instanceof ObjectNode protocolNode && protocolNode.get("SMB") instanceof ObjectNode smb) {
            smb.remove("Password");
        }
    }

    String buildLocationUri(DataSyncLocationType type, JsonNode configuration, String region) {
        return switch (type) {
            case AZURE_BLOB -> {
                String authority = configuration.path("ContainerUrl").asText("")
                        .replaceFirst("^https?://", "");
                yield "azure-blob://" + trimTrailingSlashes(authority) + subdirectory(configuration);
            }
            case EFS -> fileSystemUri(type, configuration.path("EfsFilesystemArn").asText(""), region, configuration);
            case FSX_LUSTRE, FSX_OPEN_ZFS, FSX_WINDOWS ->
                    fileSystemUri(type, configuration.path("FsxFilesystemArn").asText(""), region, configuration);
            case FSX_ONTAP -> {
                String svmArn = configuration.path("StorageVirtualMachineArn").asText("");
                yield "fsxn://" + arnRegion(svmArn, region) + "." + fsxFileSystemId(svmArn) + "."
                        + fsxStorageVirtualMachineId(svmArn) + subdirectory(configuration);
            }
            case HDFS -> {
                JsonNode nameNode = configuration.path("NameNodes").path(0);
                int port = nameNode.path("Port").asInt(0);
                yield "hdfs://" + nameNode.path("Hostname").asText("")
                        + (port > 0 ? ":" + port : "") + subdirectory(configuration);
            }
            case NFS -> "nfs://" + configuration.path("ServerHostname").asText("") + subdirectory(configuration);
            case SMB -> "smb://" + configuration.path("ServerHostname").asText("") + subdirectory(configuration);
            case OBJECT_STORAGE -> "object-storage://" + configuration.path("ServerHostname").asText("")
                    + "/" + configuration.path("BucketName").asText("") + subdirectory(configuration);
            case S3 -> {
                String subdirectory = subdirectory(configuration);
                yield "s3://" + s3BucketName(configuration.path("S3BucketArn").asText(""))
                        + ("/".equals(subdirectory) ? "" : subdirectory);
            }
        };
    }

    private String fileSystemUri(DataSyncLocationType type, String fileSystemArn, String region,
                                 JsonNode configuration) {
        String fileSystemId = arnResource(fileSystemArn);
        int slash = fileSystemId.lastIndexOf('/');
        if (slash >= 0) {
            fileSystemId = fileSystemId.substring(slash + 1);
        }
        return type.uriScheme() + "://" + arnRegion(fileSystemArn, region) + "." + fileSystemId
                + subdirectory(configuration);
    }

    private static String subdirectory(JsonNode configuration) {
        String subdirectory = configuration.path("Subdirectory").asText("").trim();
        while (subdirectory.endsWith("/")) {
            subdirectory = subdirectory.substring(0, subdirectory.length() - 1);
        }
        if (subdirectory.isEmpty()) {
            return "/";
        }
        return subdirectory.startsWith("/") ? subdirectory : "/" + subdirectory;
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String s3BucketName(String bucketArn) {
        String resource = arnResource(bucketArn);
        return resource.isEmpty() ? bucketArn : resource;
    }

    private static String fsxFileSystemId(String storageVirtualMachineArn) {
        String[] segments = arnResource(storageVirtualMachineArn).split("/");
        return segments.length > 1 ? segments[1] : "";
    }

    private static String fsxStorageVirtualMachineId(String storageVirtualMachineArn) {
        String[] segments = arnResource(storageVirtualMachineArn).split("/");
        return segments.length > 2 ? segments[2] : "";
    }

    private static String arnRegion(String arn, String fallback) {
        String[] segments = arn.split(":", 6);
        return segments.length > 3 && !segments[3].isEmpty() ? segments[3] : fallback;
    }

    private String arnAccountId(String arn) {
        String[] segments = arn.split(":", 6);
        return segments.length > 4 && !segments[4].isEmpty() ? segments[4] : regionResolver.getAccountId();
    }

    private static String arnResource(String arn) {
        String[] segments = arn.split(":", 6);
        return segments.length > 5 ? segments[5] : "";
    }

    // ──────────────────────────── Filters ────────────────────────────

    private boolean matchesLocationFilters(DataSyncLocation location, JsonNode filters) {
        if (filters == null || !filters.isArray()) {
            return true;
        }
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText("");
            List<String> values = stringList(filter.path("Values"));
            String operator = filter.path("Operator").asText("Equals");
            boolean matched = switch (name) {
                case "LocationUri" -> matchesString(location.getLocationUri(), values, operator);
                case "LocationType" -> matchesString(location.getLocationType().operationSuffix(), values, operator)
                        || matchesString(location.getLocationType().uriScheme(), values, operator);
                case "CreationTime" -> matchesInstant(location.getCreationTime(), values, operator);
                default -> throw new AwsException("InvalidRequestException",
                        "Unsupported ListLocations filter name: " + name, 400);
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesTaskFilters(DataSyncTask task, JsonNode filters) {
        if (filters == null || !filters.isArray()) {
            return true;
        }
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText("");
            List<String> values = stringList(filter.path("Values"));
            String operator = filter.path("Operator").asText("Equals");
            boolean matched = switch (name) {
                case "LocationId" -> matchesString(task.getSourceLocationArn(), values, operator)
                        || matchesString(task.getDestinationLocationArn(), values, operator);
                case "CreationTime" -> matchesInstant(task.getCreationTime(), values, operator);
                default -> throw new AwsException("InvalidRequestException",
                        "Unsupported ListTasks filter name: " + name, 400);
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesString(String attribute, List<String> values, String operator) {
        String actual = attribute != null ? attribute : "";
        return switch (operator) {
            case "Equals", "In" -> values.stream().anyMatch(actual::equalsIgnoreCase);
            case "NotEquals" -> values.stream().noneMatch(actual::equalsIgnoreCase);
            case "Contains" -> values.stream()
                    .anyMatch(value -> actual.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
            case "NotContains" -> values.stream()
                    .noneMatch(value -> actual.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
            case "BeginsWith" -> values.stream()
                    .anyMatch(value -> actual.toLowerCase(Locale.ROOT).startsWith(value.toLowerCase(Locale.ROOT)));
            default -> throw new AwsException("InvalidRequestException",
                    "Unsupported filter operator for a string attribute: " + operator, 400);
        };
    }

    private static boolean matchesInstant(Instant attribute, List<String> values, String operator) {
        Instant actual = attribute != null ? attribute : Instant.EPOCH;
        return switch (operator) {
            case "Equals", "In" -> values.stream().anyMatch(value -> actual.equals(parseInstant(value)));
            case "NotEquals" -> values.stream().noneMatch(value -> actual.equals(parseInstant(value)));
            case "LessThan" -> values.stream().allMatch(value -> actual.isBefore(parseInstant(value)));
            case "LessThanOrEqual" -> values.stream().allMatch(value -> !actual.isAfter(parseInstant(value)));
            case "GreaterThan" -> values.stream().allMatch(value -> actual.isAfter(parseInstant(value)));
            case "GreaterThanOrEqual" -> values.stream().allMatch(value -> !actual.isBefore(parseInstant(value)));
            default -> throw new AwsException("InvalidRequestException",
                    "Unsupported filter operator for CreationTime: " + operator, 400);
        };
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notIso) {
            try {
                return Instant.ofEpochMilli((long) (Double.parseDouble(value) * 1000));
            } catch (NumberFormatException notEpoch) {
                throw new AwsException("InvalidRequestException",
                        "CreationTime filter value is neither an ISO-8601 instant nor an epoch timestamp: "
                                + value, 400);
            }
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private JsonNode mergedOptions(JsonNode requested) {
        ObjectNode options = mapper.createObjectNode();
        options.put("VerifyMode", "POINT_IN_TIME_CONSISTENT");
        options.put("OverwriteMode", "ALWAYS");
        options.put("Atime", "BEST_EFFORT");
        options.put("Mtime", "PRESERVE");
        options.put("Uid", "INT_VALUE");
        options.put("Gid", "INT_VALUE");
        options.put("PreserveDeletedFiles", "PRESERVE");
        options.put("PreserveDevices", "NONE");
        options.put("PosixPermissions", "PRESERVE");
        options.put("BytesPerSecond", -1L);
        options.put("TaskQueueing", "ENABLED");
        options.put("LogLevel", "OFF");
        options.put("TransferMode", "CHANGED");
        options.put("SecurityDescriptorCopyFlags", "OWNER_DACL");
        options.put("ObjectTags", "PRESERVE");
        if (requested != null && requested.isObject()) {
            requested.properties().forEach(member -> options.set(member.getKey(), member.getValue().deepCopy()));
        }
        return options;
    }

    private JsonNode filterList(JsonNode requested) {
        return requested != null && requested.isArray() ? requested.deepCopy() : mapper.createArrayNode();
    }

    private static JsonNode copyOrNull(JsonNode requested) {
        return requested != null && !requested.isNull() ? requested.deepCopy() : null;
    }

    private static <T> Page<T> paginate(List<T> all, Function<T, String> identity,
                                        String nextToken, int maxResults) {
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            for (int i = 0; i < all.size(); i++) {
                if (identity.apply(all.get(i)).equals(nextToken)) {
                    start = i + 1;
                    break;
                }
            }
        }
        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        int end = Math.min(start + limit, all.size());
        List<T> page = new ArrayList<>(all.subList(Math.min(start, all.size()), end));
        String token = end < all.size() && !page.isEmpty() ? identity.apply(page.get(page.size() - 1)) : null;
        return new Page<>(page, token);
    }

    public static Map<String, String> tagsOf(JsonNode tagList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagList != null && tagList.isArray()) {
            for (JsonNode tag : tagList) {
                String key = tag.path("Key").asText("");
                if (!key.isEmpty()) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    public static List<String> stringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(element -> values.add(element.asText("")));
        }
        return values;
    }

    private static boolean hasValue(JsonNode request, String member) {
        JsonNode value = request.get(member);
        return value != null && !value.isNull() && !(value.isTextual() && value.asText().isEmpty());
    }

    private static String requiredText(JsonNode request, String member, String operation) {
        if (!hasValue(request, member)) {
            throw new AwsException("InvalidRequestException", member + " is required for " + operation + ".", 400);
        }
        return request.get(member).asText();
    }

    private static String optionalText(JsonNode request, String member) {
        return request.hasNonNull(member) ? request.get(member).asText() : null;
    }

    private static void requireArn(String arn, String member) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("InvalidRequestException", member + " is required.", 400);
        }
    }

    private static String randomResourceId() {
        StringBuilder id = new StringBuilder(RESOURCE_ID_LENGTH);
        for (int i = 0; i < RESOURCE_ID_LENGTH; i++) {
            id.append(HEX_ALPHABET.charAt(RANDOM.nextInt(HEX_ALPHABET.length())));
        }
        return id.toString();
    }
}
