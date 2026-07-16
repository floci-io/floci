package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    @Inject
    public BedrockAgentCoreControlService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-runtimes.json",
                new TypeReference<Map<String, AgentRuntime>>() {}), regionResolver);
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public AgentRuntime createAgentRuntime(String name, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           String region) {
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
        runtime.setWorkloadIdentityArn(regionResolver.buildArn(ARN_SERVICE, region,
                "workload-identity-directory/default/workload-identity/" + name + "-" + randomId()));
        runtime.getVersions().add(snapshot(runtime, "1", now));

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

    public AgentRuntime deleteAgentRuntime(String id, String region) {
        AgentRuntime runtime = getAgentRuntime(id, region);
        storage.delete(key(region, id));
        runtime.setStatus(STATUS_DELETING);
        LOG.infov("Deleted AgentCore runtime {0}", id);
        return runtime;
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
        List<AgentRuntimeVersion> all = runtime.getVersions().stream()
                .sorted(Comparator.comparingInt(v -> Integer.parseInt(v.getVersion())))
                .collect(Collectors.toList());
        return paginate(all, AgentRuntimeVersion::getVersion, maxResults, nextToken);
    }

    public String arn(AgentRuntime runtime, String version, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "agent/" + runtime.getUuid() + ":" + version);
    }

    private AgentRuntimeVersion snapshot(AgentRuntime runtime, String version, Instant now) {
        AgentRuntimeVersion snap = new AgentRuntimeVersion();
        snap.setVersion(version);
        snap.setCreatedAt(now);
        snap.setRoleArn(runtime.getRoleArn());
        snap.setDescription(runtime.getDescription());
        snap.setAgentRuntimeArtifact(runtime.getAgentRuntimeArtifact());
        snap.setNetworkConfiguration(runtime.getNetworkConfiguration());
        snap.setEnvironmentVariables(runtime.getEnvironmentVariables() != null
                ? new HashMap<>(runtime.getEnvironmentVariables()) : null);
        return snap;
    }

    private <T> ListResult<T> paginate(List<T> all, java.util.function.Function<T, String> cursorOf,
                                       int maxResults, String nextToken) {
        int limit = maxResults > 0 ? Math.min(maxResults, MAX_PAGE) : DEFAULT_PAGE;
        String after = decodeToken(nextToken);
        int start = 0;
        if (after != null) {
            for (int i = 0; i < all.size(); i++) {
                if (cursorOf.apply(all.get(i)).compareTo(after) > 0) {
                    start = i;
                    break;
                }
                start = i + 1;
            }
        }
        List<T> page = all.stream().skip(start).limit(limit).collect(Collectors.toList());
        String outToken = null;
        if (start + limit < all.size() && !page.isEmpty()) {
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
