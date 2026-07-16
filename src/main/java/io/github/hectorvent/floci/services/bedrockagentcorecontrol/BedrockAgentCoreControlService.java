package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.ListResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Business logic for the Amazon Bedrock AgentCore control plane (runtime registry).
 *
 * <p>Stateful CRUD only: no real agent execution. Runtimes reach {@code READY}
 * immediately. Each {@code UpdateAgentRuntime} appends an immutable version snapshot.
 */
@ApplicationScoped
public class BedrockAgentCoreControlService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreControlService.class);

    static final String STATUS_READY = "READY";
    static final String STATUS_DELETING = "DELETING";
    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,47}");
    private static final String ID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 100;

    private final StorageBackend<String, AgentRuntime> storage;
    private final RegionResolver regionResolver;
    private final BedrockAgentCoreIdentityService identityService;
    // clientToken idempotency: tokens of completed deletes, so a replayed delete succeeds
    // instead of 404ing. In-memory only (reset on restart) — sufficient for an emulator.
    private final Set<String> deletedRuntimeTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> deletedEndpointTokens = ConcurrentHashMap.newKeySet();

    @Inject
    public BedrockAgentCoreControlService(StorageFactory storageFactory, RegionResolver regionResolver,
                                          BedrockAgentCoreIdentityService identityService) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-runtimes.json",
                new TypeReference<Map<String, AgentRuntime>>() {}), regionResolver, identityService);
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver) {
        this(storage, regionResolver, null);
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver,
                                   BedrockAgentCoreIdentityService identityService) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.identityService = identityService;
    }

    public AgentRuntime createAgentRuntime(String name, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           String clientToken, String region) {
        if (clientToken != null) {
            Optional<AgentRuntime> replay = storage.scan(k -> k.startsWith(keyPrefix(region))).stream()
                    .filter(r -> clientToken.equals(r.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "agentRuntimeName must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        if (artifact == null || artifact.isNull()) {
            throw new AwsException("ValidationException", "agentRuntimeArtifact is required", 400);
        }
        if (networkConfiguration == null || networkConfiguration.isNull()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required", 400);
        }

        Instant now = Instant.now();
        String id = name + "-" + randomId();
        String uuid = UUID.randomUUID().toString();

        AgentRuntime runtime = new AgentRuntime();
        runtime.setAgentRuntimeId(id);
        runtime.setAgentRuntimeName(name);
        runtime.setUuid(uuid);
        runtime.setRoleArn(roleArn);
        runtime.setDescription(description);
        runtime.setStatus(STATUS_READY);
        runtime.setLatestVersion(1);
        runtime.setCreatedAt(now);
        runtime.setLastUpdatedAt(now);
        runtime.setAccountId(regionResolver.getAccountId());
        runtime.setAgentRuntimeArtifact(artifact);
        runtime.setNetworkConfiguration(networkConfiguration);
        runtime.setAuthorizerConfiguration(authorizerConfiguration);
        runtime.setProtocolConfiguration(protocolConfiguration);
        runtime.setEnvironmentVariables(environmentVariables != null ? new HashMap<>(environmentVariables) : new HashMap<>());
        runtime.setClientToken(clientToken);
        String workloadName = name + "-" + randomId();
        if (identityService != null) {
            runtime.setWorkloadIdentityArn(
                    identityService.createForRuntime(workloadName, region).getWorkloadIdentityArn());
        } else {
            runtime.setWorkloadIdentityArn(regionResolver.buildArn(ARN_SERVICE, region,
                    "workload-identity-directory/default/workload-identity/" + workloadName));
        }
        runtime.getVersions().add(snapshot(runtime, "1", now));
        runtime.getEndpoints().add(newEndpoint("DEFAULT", "1", null, now));

        storage.put(key(region, id), runtime);
        LOG.infov("Created AgentCore runtime {0} (id={1})", name, id);
        return runtime;
    }

    public AgentRuntime getAgentRuntime(String id, String region) {
        return storage.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime not found: " + id, 404));
    }

    /** Returns the snapshot matching {@code version}, or the latest when {@code version} is null. */
    public AgentRuntimeVersion resolveVersion(AgentRuntime runtime, String version) {
        String wanted = version != null ? version : String.valueOf(runtime.getLatestVersion());
        return runtime.getVersions().stream()
                .filter(v -> wanted.equals(v.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime version not found: " + wanted, 404));
    }

    public AgentRuntime updateAgentRuntime(String id, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           String region) {
        if (artifact == null || artifact.isNull()) {
            throw new AwsException("ValidationException", "agentRuntimeArtifact is required", 400);
        }
        if (networkConfiguration == null || networkConfiguration.isNull()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required", 400);
        }
        AgentRuntime runtime = getAgentRuntime(id, region);
        Instant now = Instant.now();

        int newVersion = runtime.getLatestVersion() + 1;
        runtime.setLatestVersion(newVersion);
        runtime.setRoleArn(roleArn);
        runtime.setDescription(description);
        runtime.setAgentRuntimeArtifact(artifact);
        runtime.setNetworkConfiguration(networkConfiguration);
        if (authorizerConfiguration != null) {
            runtime.setAuthorizerConfiguration(authorizerConfiguration);
        }
        if (protocolConfiguration != null) {
            runtime.setProtocolConfiguration(protocolConfiguration);
        }
        if (environmentVariables != null) {
            runtime.setEnvironmentVariables(new HashMap<>(environmentVariables));
        }
        runtime.setLastUpdatedAt(now);
        runtime.getVersions().add(snapshot(runtime, String.valueOf(newVersion), now));

        storage.put(key(region, id), runtime);
        LOG.infov("Updated AgentCore runtime {0} to version {1}", id, newVersion);
        return runtime;
    }

    public AgentRuntime deleteAgentRuntime(String id, String clientToken, String region) {
        Optional<AgentRuntime> found = storage.get(key(region, id));
        if (found.isEmpty()) {
            if (clientToken != null && deletedRuntimeTokens.contains(tokenKey(region, clientToken))) {
                AgentRuntime marker = new AgentRuntime();
                marker.setAgentRuntimeId(id);
                marker.setStatus(STATUS_DELETING);
                return marker;
            }
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore runtime not found: " + id, 404);
        }
        AgentRuntime runtime = found.get();
        storage.delete(key(region, id));
        if (clientToken != null) {
            deletedRuntimeTokens.add(tokenKey(region, clientToken));
        }
        runtime.setStatus(STATUS_DELETING);
        LOG.infov("Deleted AgentCore runtime {0}", id);
        return runtime;
    }

    private static String tokenKey(String region, String clientToken) {
        return region + " " + clientToken;
    }

    public ListResult<AgentRuntime> listAgentRuntimes(int maxResults, String nextToken, String region) {
        String prefix = keyPrefix(region);
        List<AgentRuntime> all = storage.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(AgentRuntime::getAgentRuntimeId))
                .collect(Collectors.toList());
        return paginate(all, AgentRuntime::getAgentRuntimeId, maxResults, nextToken);
    }

    public ListResult<AgentRuntimeVersion> listAgentRuntimeVersions(String id, int maxResults, String nextToken,
                                                                    String region) {
        AgentRuntime runtime = getAgentRuntime(id, region);
        // Cursor must be zero-padded so lexicographic order matches numeric version order;
        // paginate() sorts by the cursor, keeping sort order and resume order consistent.
        return paginate(runtime.getVersions(), v -> pad(v.getVersion()), maxResults, nextToken);
    }

    private static String pad(String version) {
        return String.format("%05d", Integer.parseInt(version));
    }

    public String arn(AgentRuntime runtime, String version, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "agent/" + runtime.getUuid() + ":" + version);
    }

    /**
     * Whether a runtime referenced by an invoke ARN exists. Returns {@code true} for
     * inputs that aren't a full runtime ARN (e.g. a bare agent id), since those can't be
     * validated here — the data plane treats them permissively.
     */
    public boolean runtimeArnExists(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("agent/")) {
            return true;
        }
        String uuid = parts[5].substring("agent/".length());
        String prefix = keyPrefix(region);
        return storage.scan(k -> k.startsWith(prefix)).stream()
                .anyMatch(r -> uuid.equals(r.getUuid()));
    }

    public String endpointArn(AgentRuntimeEndpoint endpoint, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "agentEndpoint/" + endpoint.getUuid());
    }

    // ──────────────────────────── Tagging (Phase 4) ────────────────────────────

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(findByArn(region, arn).getTags());
    }

    public void tagByArn(String region, String arn, Map<String, String> tags) {
        AgentRuntime runtime = findByArn(region, arn);
        runtime.getTags().putAll(tags);
        storage.put(key(region, runtime.getAgentRuntimeId()), runtime);
    }

    public void untagByArn(String region, String arn, List<String> keys) {
        AgentRuntime runtime = findByArn(region, arn);
        keys.forEach(runtime.getTags()::remove);
        storage.put(key(region, runtime.getAgentRuntimeId()), runtime);
    }

    private AgentRuntime findByArn(String region, String arn) {
        String uuid = uuidFromArn(arn);
        String prefix = keyPrefix(region);
        return storage.scan(k -> k.startsWith(prefix)).stream()
                .filter(r -> uuid.equals(r.getUuid()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore resource not found: " + arn, 404));
    }

    private static String uuidFromArn(String arn) {
        // arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("agent/")) {
            throw new AwsException("ValidationException", "Unsupported resource ARN: " + arn, 400);
        }
        return parts[5].substring("agent/".length());
    }

    // ──────────────────────────── Endpoints (Phase 2) ────────────────────────────

    public AgentRuntimeEndpoint createEndpoint(String runtimeId, String name, String agentRuntimeVersion,
                                               String description, String clientToken, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        if (clientToken != null) {
            Optional<AgentRuntimeEndpoint> replay = runtime.getEndpoints().stream()
                    .filter(e -> clientToken.equals(e.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "endpoint name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        boolean exists = runtime.getEndpoints().stream().anyMatch(e -> name.equals(e.getName()));
        if (exists) {
            throw new AwsException("ConflictException",
                    "AgentCore runtime endpoint already exists: " + name, 409);
        }
        String version = agentRuntimeVersion != null ? agentRuntimeVersion
                : String.valueOf(runtime.getLatestVersion());
        AgentRuntimeEndpoint endpoint = newEndpoint(name, version, description, Instant.now());
        endpoint.setClientToken(clientToken);
        runtime.getEndpoints().add(endpoint);
        storage.put(key(region, runtimeId), runtime);
        LOG.infov("Created AgentCore endpoint {0} on runtime {1}", name, runtimeId);
        return endpoint;
    }

    public AgentRuntimeEndpoint getEndpoint(String runtimeId, String name, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        return findEndpoint(runtime, name);
    }

    public AgentRuntimeEndpoint updateEndpoint(String runtimeId, String name, String agentRuntimeVersion,
                                               String description, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        AgentRuntimeEndpoint endpoint = findEndpoint(runtime, name);
        if (agentRuntimeVersion != null) {
            endpoint.setTargetVersion(agentRuntimeVersion);
            endpoint.setLiveVersion(agentRuntimeVersion);
        }
        if (description != null) {
            endpoint.setDescription(description);
        }
        endpoint.setLastUpdatedAt(Instant.now());
        storage.put(key(region, runtimeId), runtime);
        return endpoint;
    }

    public AgentRuntimeEndpoint deleteEndpoint(String runtimeId, String name, String clientToken, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        Optional<AgentRuntimeEndpoint> found = runtime.getEndpoints().stream()
                .filter(e -> e.getName().equals(name)).findFirst();
        if (found.isEmpty()) {
            if (clientToken != null && deletedEndpointTokens.contains(tokenKey(region, clientToken))) {
                AgentRuntimeEndpoint marker = new AgentRuntimeEndpoint();
                marker.setName(name);
                marker.setStatus(STATUS_DELETING);
                return marker;
            }
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore runtime endpoint not found: " + name, 404);
        }
        AgentRuntimeEndpoint endpoint = found.get();
        runtime.getEndpoints().removeIf(e -> name.equals(e.getName()));
        if (clientToken != null) {
            deletedEndpointTokens.add(tokenKey(region, clientToken));
        }
        endpoint.setStatus(STATUS_DELETING);
        storage.put(key(region, runtimeId), runtime);
        return endpoint;
    }

    public ListResult<AgentRuntimeEndpoint> listEndpoints(String runtimeId, int maxResults, String nextToken,
                                                          String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        List<AgentRuntimeEndpoint> all = runtime.getEndpoints().stream()
                .sorted(Comparator.comparing(AgentRuntimeEndpoint::getName))
                .collect(Collectors.toList());
        return paginate(all, AgentRuntimeEndpoint::getName, maxResults, nextToken);
    }

    private AgentRuntimeEndpoint findEndpoint(AgentRuntime runtime, String name) {
        return runtime.getEndpoints().stream()
                .filter(e -> e.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime endpoint not found: " + name, 404));
    }

    private AgentRuntimeEndpoint newEndpoint(String name, String version, String description, Instant now) {
        AgentRuntimeEndpoint endpoint = new AgentRuntimeEndpoint();
        endpoint.setName(name);
        endpoint.setUuid(UUID.randomUUID().toString());
        endpoint.setTargetVersion(version);
        endpoint.setLiveVersion(version);
        endpoint.setStatus(STATUS_READY);
        endpoint.setDescription(description);
        endpoint.setCreatedAt(now);
        endpoint.setLastUpdatedAt(now);
        return endpoint;
    }

    private AgentRuntimeVersion snapshot(AgentRuntime runtime, String version, Instant now) {
        AgentRuntimeVersion snap = new AgentRuntimeVersion();
        snap.setVersion(version);
        snap.setCreatedAt(now);
        snap.setRoleArn(runtime.getRoleArn());
        snap.setDescription(runtime.getDescription());
        snap.setAgentRuntimeArtifact(runtime.getAgentRuntimeArtifact());
        snap.setNetworkConfiguration(runtime.getNetworkConfiguration());
        snap.setAuthorizerConfiguration(runtime.getAuthorizerConfiguration());
        snap.setProtocolConfiguration(runtime.getProtocolConfiguration());
        snap.setEnvironmentVariables(runtime.getEnvironmentVariables() != null
                ? new HashMap<>(runtime.getEnvironmentVariables()) : null);
        return snap;
    }

    private <T> ListResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                       int maxResults, String nextToken) {
        if (maxResults < 0 || maxResults > MAX_PAGE) {
            throw new AwsException("ValidationException",
                    "maxResults must be between 1 and " + MAX_PAGE, 400);
        }
        int limit = maxResults > 0 ? maxResults : DEFAULT_PAGE;
        // Sort by the same key used as the pagination cursor so resume order always matches.
        List<T> sorted = all.stream().sorted(Comparator.comparing(cursorOf)).collect(Collectors.toList());
        String after = decodeToken(nextToken);
        int start = 0;
        if (after != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (cursorOf.apply(sorted.get(i)).compareTo(after) > 0) {
                    start = i;
                    break;
                }
                start = i + 1;
            }
        }
        List<T> page = sorted.stream().skip(start).limit(limit).collect(Collectors.toList());
        String outToken = null;
        if (start + limit < sorted.size() && !page.isEmpty()) {
            outToken = encodeToken(cursorOf.apply(page.get(page.size() - 1)));
        }
        return new ListResult<>(page, outToken);
    }

    private static String encodeToken(String cursor) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid nextToken", 400);
        }
    }

    private static String key(String region, String id) {
        return keyPrefix(region) + id;
    }

    private static String keyPrefix(String region) {
        return "runtime:" + region + ":";
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
