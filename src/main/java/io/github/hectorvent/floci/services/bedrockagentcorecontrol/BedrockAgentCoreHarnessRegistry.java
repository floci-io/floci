package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Harness;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.HarnessVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The AgentCore harness control plane: {@code CreateHarness}, {@code GetHarness},
 * {@code UpdateHarness}, {@code DeleteHarness}, {@code ListHarnesses} and
 * {@code ListHarnessVersions}, plus tags on harness ARNs. A metadata registry: a harness settles in
 * {@code READY} at once, and no agent runtime is started for it.
 *
 * <p>Defaults are the ones AWS fills in for a harness created without them: {@code apiFormat}
 * {@code converse_stream} on a Bedrock model, empty {@code systemPrompt}, {@code tools},
 * {@code skills} and {@code allowedTools}, a {@code sliding_window} truncation of 150 messages, no
 * environment variables, and an AgentCore Runtime environment named {@code harness_<name>} with a
 * 900-second idle session timeout, an 8-hour maximum lifetime and {@code PUBLIC} networking.
 */
@ApplicationScoped
public class BedrockAgentCoreHarnessRegistry {

    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_DELETING = "DELETING";
    private static final int MAX_PAGE = 100;
    private static final int MAX_TAGS = 50;
    private static final int MAX_VERSION = 99999;

    private static final Pattern HARNESS_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,39}");
    private static final Pattern ROLE_ARN =
            Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::([0-9]{12})?:role/.+");

    private static final String RUNTIME_ENVIRONMENT = "agentCoreRuntimeEnvironment";
    private static final int DEFAULT_IDLE_SESSION_TIMEOUT = 900;
    private static final int DEFAULT_MAX_LIFETIME = 28800;
    private static final int DEFAULT_TRUNCATION_MESSAGES = 150;

    /** Members replaced whole when a request carries them, in the order {@code GetHarness} returns them. */
    private static final List<String> PLAIN_MEMBERS = List.of("model", "systemPrompt", "tools", "skills",
            "allowedTools", "truncation", "environmentVariables", "maxIterations", "maxTokens", "timeoutSeconds");
    /** Members an update sets or clears through an {@code optionalValue} wrapper. */
    private static final List<String> OPTIONAL_MEMBERS = List.of("environmentArtifact", "authorizerConfiguration",
            "memory");
    /** Every configuration member, in the order {@code GetHarness} returns them. */
    private static final List<String> MEMBER_ORDER = List.of("model", "systemPrompt", "tools", "skills",
            "allowedTools", "truncation", "environment", "environmentArtifact", "environmentVariables",
            "authorizerConfiguration", "memory", "maxIterations", "maxTokens", "timeoutSeconds");

    private final StorageBackend<String, Harness> storage;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreHarnessRegistry(StorageFactory storageFactory, RegionResolver regionResolver,
                                           ObjectMapper objectMapper) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-harnesses.json",
                new TypeReference<Map<String, Harness>>() {}), regionResolver, objectMapper);
    }

    BedrockAgentCoreHarnessRegistry(StorageBackend<String, Harness> storage, RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /** A harness at one of its versions: what {@code GetHarness} and the write operations answer. */
    public record HarnessView(Harness harness, HarnessVersion version) {}

    public synchronized HarnessView create(ObjectNode request, String region) {
        String clientToken = text(request, "clientToken");
        if (clientToken != null) {
            Optional<Harness> replay = storage.scan(key -> key.startsWith(keyPrefix(region))).stream()
                    .filter(harness -> clientToken.equals(harness.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return new HarnessView(replay.get(), replay.get().getCurrentVersion());
            }
        }
        String name = text(request, "harnessName");
        if (name == null || !HARNESS_NAME.matcher(name).matches()) {
            throw validation("harnessName must start with a letter and contain up to 40 letters, digits and "
                    + "underscores");
        }
        String roleArn = requireRoleArn(text(request, "executionRoleArn"));
        Map<String, String> tags = tags(request.get("tags"));
        boolean taken = storage.scan(key -> key.startsWith(keyPrefix(region))).stream()
                .anyMatch(harness -> name.equals(harness.getHarnessName()));
        if (taken) {
            throw new AwsException("ConflictException", "A harness named " + name + " already exists.", 409);
        }

        ObjectNode configuration = objectMapper.createObjectNode();
        for (String member : PLAIN_MEMBERS) {
            copy(request, configuration, member);
        }
        for (String member : OPTIONAL_MEMBERS) {
            copy(request, configuration, member);
        }
        JsonNode environment = request.path("environment").path(RUNTIME_ENVIRONMENT);
        configuration.set("environment", runtimeEnvironment(name, null, environment, region));

        Instant now = Instant.now();
        Harness harness = new Harness();
        harness.setHarnessId(name + "-" + BedrockAgentCoreControllerSupport.randomAlphanumeric(10));
        harness.setHarnessName(name);
        harness.setStatus(STATUS_READY);
        harness.setCreatedAt(now);
        harness.setUpdatedAt(now);
        harness.setClientToken(clientToken);
        harness.getTags().putAll(tags);
        harness.getVersions().add(version("1", now, roleArn, withDefaults(configuration)));
        storage.put(key(region, harness.getHarnessId()), harness);
        return new HarnessView(harness, harness.getCurrentVersion());
    }

    /** The harness at {@code version}, or at its current version when {@code version} is null. */
    public HarnessView get(String id, String version, String region) {
        Harness harness = find(id, region);
        if (version == null) {
            return new HarnessView(harness, harness.getCurrentVersion());
        }
        HarnessVersion found = harness.findVersion(version).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Version " + version + " not found for harness " + id, 404));
        return new HarnessView(harness, found);
    }

    /**
     * Appends the next version. A member the request carries replaces the current one, and an
     * {@code optionalValue} wrapper sets its member, or clears it when the wrapper is empty. The
     * runtime environment keeps its runtime and takes the lifecycle, network and filesystem
     * configuration the request gives.
     */
    public synchronized HarnessView update(String id, ObjectNode request, String region) {
        Harness harness = find(id, region);
        HarnessVersion current = harness.getCurrentVersion();
        String roleArn = request.hasNonNull("executionRoleArn")
                ? requireRoleArn(text(request, "executionRoleArn")) : current.getExecutionRoleArn();
        int next = Integer.parseInt(current.getVersion()) + 1;

        ObjectNode configuration = current.getConfiguration().deepCopy();
        for (String member : PLAIN_MEMBERS) {
            copy(request, configuration, member);
        }
        for (String member : OPTIONAL_MEMBERS) {
            JsonNode wrapper = request.get(member);
            if (wrapper == null || wrapper.isNull()) {
                continue;
            }
            JsonNode value = wrapper.get("optionalValue");
            if (value == null || value.isNull()) {
                configuration.remove(member);
            } else {
                configuration.set(member, value.deepCopy());
            }
        }
        JsonNode environment = request.path("environment").path(RUNTIME_ENVIRONMENT);
        if (environment.isObject()) {
            configuration.set("environment", runtimeEnvironment(harness.getHarnessName(),
                    current.getConfiguration().path("environment").path(RUNTIME_ENVIRONMENT), environment, region));
        }

        Instant now = Instant.now();
        harness.getVersions().add(version(String.valueOf(next), now, roleArn, withDefaults(configuration)));
        harness.setUpdatedAt(now);
        storage.put(key(region, id), harness);
        return new HarnessView(harness, harness.getCurrentVersion());
    }

    /** Removes the harness and answers it as it was, {@code DELETING}. */
    public synchronized HarnessView delete(String id, String region) {
        Harness harness = find(id, region);
        storage.delete(key(region, id));
        harness.setStatus(STATUS_DELETING);
        return new HarnessView(harness, harness.getCurrentVersion());
    }

    public PaginatedResult<Harness> list(Integer maxResults, String nextToken, String region) {
        List<Harness> all = storage.scan(key -> key.startsWith(keyPrefix(region)));
        return Pagination.paginate(all, Harness::getHarnessId, maxResults, nextToken, MAX_PAGE, "ValidationException");
    }

    /** A harness's versions, newest first, as AWS lists them. */
    public PaginatedResult<HarnessVersion> listVersions(String id, Integer maxResults, String nextToken,
                                                        String region) {
        Harness harness = find(id, region);
        return Pagination.paginate(harness.getVersions(),
                version -> String.format("%05d", MAX_VERSION - Integer.parseInt(version.getVersion())),
                maxResults, nextToken, MAX_PAGE, "ValidationException");
    }

    public String arn(Harness harness, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "harness/" + harness.getHarnessId());
    }

    /** Every configuration member, in the order {@code GetHarness} returns them. */
    public static List<String> memberOrder() {
        return MEMBER_ORDER;
    }

    // ── Tagging ──

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(getByArn(region, arn).getTags());
    }

    public synchronized void tagByArn(String region, String arn, Map<String, String> tags) {
        Harness harness = getByArn(region, arn);
        Map<String, String> merged = new HashMap<>(harness.getTags());
        merged.putAll(tags);
        if (merged.size() > MAX_TAGS) {
            throw validation("A harness can have at most " + MAX_TAGS + " tags.");
        }
        harness.setTags(merged);
        storage.put(key(region, harness.getHarnessId()), harness);
    }

    public synchronized void untagByArn(String region, String arn, List<String> keys) {
        Harness harness = getByArn(region, arn);
        keys.forEach(harness.getTags()::remove);
        storage.put(key(region, harness.getHarnessId()), harness);
    }

    /** The harness a harness ARN names; ValidationException for any other ARN, ResourceNotFoundException when gone. */
    public Harness getByArn(String region, String arn) {
        String resource = BedrockAgentCoreArns.resource(arn);
        if (resource == null || !resource.startsWith("harness/")) {
            throw validation("Unsupported resource ARN: " + arn);
        }
        return find(resource.substring("harness/".length()), region);
    }

    private Harness find(String id, String region) {
        // AWS's own wording, "Agent" included.
        return storage.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Agent with name " + id + " not found.", 404));
    }

    /**
     * The AgentCore Runtime environment a harness runs in: the runtime is named after the harness
     * and keeps its id across updates; lifecycle and network configuration default as on AWS.
     */
    private ObjectNode runtimeEnvironment(String harnessName, JsonNode existing, JsonNode requested,
                                          String region) {
        ObjectNode runtime = objectMapper.createObjectNode();
        String runtimeName = "harness_" + harnessName;
        String runtimeId = existing != null && existing.hasNonNull("agentRuntimeId")
                ? existing.get("agentRuntimeId").asText()
                : runtimeName + "-" + BedrockAgentCoreControllerSupport.randomAlphanumeric(10);
        runtime.put("agentRuntimeArn", regionResolver.buildArn(ARN_SERVICE, region, "runtime/" + runtimeId));
        runtime.put("agentRuntimeName", runtimeName);
        runtime.put("agentRuntimeId", runtimeId);

        ObjectNode lifecycle = objectMapper.createObjectNode();
        JsonNode requestedLifecycle = requested.path("lifecycleConfiguration");
        lifecycle.put("idleRuntimeSessionTimeout", requestedLifecycle.path("idleRuntimeSessionTimeout")
                .asInt(DEFAULT_IDLE_SESSION_TIMEOUT));
        lifecycle.put("maxLifetime", requestedLifecycle.path("maxLifetime").asInt(DEFAULT_MAX_LIFETIME));
        runtime.set("lifecycleConfiguration", lifecycle);

        JsonNode network = requested.get("networkConfiguration");
        if (network != null && network.isObject()) {
            runtime.set("networkConfiguration", network.deepCopy());
        } else {
            runtime.putObject("networkConfiguration").put("networkMode", "PUBLIC");
        }
        JsonNode filesystems = requested.get("filesystemConfigurations");
        if (filesystems != null && filesystems.isArray()) {
            runtime.set("filesystemConfigurations", filesystems.deepCopy());
        }
        ObjectNode environment = objectMapper.createObjectNode();
        environment.set(RUNTIME_ENVIRONMENT, runtime);
        return environment;
    }

    /** The configuration with AWS's defaults filled in, its members in the order AWS returns them. */
    private ObjectNode withDefaults(ObjectNode configuration) {
        JsonNode bedrockModel = configuration.path("model").path("bedrockModelConfig");
        if (bedrockModel instanceof ObjectNode model && !model.hasNonNull("apiFormat")) {
            model.put("apiFormat", "converse_stream");
        }
        for (String list : List.of("systemPrompt", "tools", "skills", "allowedTools")) {
            if (!configuration.hasNonNull(list)) {
                configuration.putArray(list);
            }
        }
        if (!configuration.hasNonNull("truncation")) {
            ObjectNode truncation = configuration.putObject("truncation");
            truncation.put("strategy", "sliding_window");
            truncation.putObject("config").putObject("slidingWindow")
                    .put("messagesCount", DEFAULT_TRUNCATION_MESSAGES);
        }
        if (!configuration.hasNonNull("environmentVariables")) {
            configuration.putObject("environmentVariables");
        }
        ObjectNode ordered = objectMapper.createObjectNode();
        for (String member : MEMBER_ORDER) {
            if (configuration.hasNonNull(member)) {
                ordered.set(member, configuration.get(member));
            }
        }
        return ordered;
    }

    private static HarnessVersion version(String number, Instant createdAt, String roleArn, ObjectNode configuration) {
        HarnessVersion version = new HarnessVersion();
        version.setVersion(number);
        version.setCreatedAt(createdAt);
        version.setExecutionRoleArn(roleArn);
        version.setConfiguration(configuration);
        return version;
    }

    private static void copy(ObjectNode from, ObjectNode to, String member) {
        JsonNode value = from.get(member);
        if (value != null && !value.isNull()) {
            to.set(member, value.deepCopy());
        }
    }

    private static String requireRoleArn(String roleArn) {
        if (roleArn == null || !ROLE_ARN.matcher(roleArn).matches()) {
            throw validation("executionRoleArn must be the ARN of an IAM role");
        }
        return roleArn;
    }

    private static Map<String, String> tags(JsonNode node) {
        Map<String, String> tags = BedrockAgentCoreControllerSupport.stringMap(node);
        if (tags == null) {
            return Map.of();
        }
        if (tags.size() > MAX_TAGS) {
            throw validation("A harness can have at most " + MAX_TAGS + " tags.");
        }
        return tags;
    }

    private static String text(JsonNode node, String field) {
        return BedrockAgentCoreControllerSupport.text(node, field);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }


    private static String key(String region, String id) {
        return keyPrefix(region) + id;
    }

    private static String keyPrefix(String region) {
        return "harness:" + region + ":";
    }
}
