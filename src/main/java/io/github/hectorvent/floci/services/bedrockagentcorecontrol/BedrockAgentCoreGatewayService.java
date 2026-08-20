package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Gateway;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.GatewayTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** CRUD for AgentCore gateways and their targets. Metadata registry only. */
@ApplicationScoped
public class BedrockAgentCoreGatewayService {

    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_DELETING = "DELETING";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PAGE = 100;

    private final StorageBackend<String, Gateway> storage;
    private final RegionResolver regionResolver;

    @Inject
    public BedrockAgentCoreGatewayService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-gateways.json",
                new TypeReference<Map<String, Gateway>>() {}), regionResolver);
    }

    BedrockAgentCoreGatewayService(StorageBackend<String, Gateway> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public Gateway create(String name, String authorizerType, String roleArn, String description,
                          Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "name is required", 400);
        }
        if (authorizerType == null || authorizerType.isBlank()) {
            throw new AwsException("ValidationException", "authorizerType is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required", 400);
        }
        Instant now = Instant.now();
        String id = sanitize(name) + "-" + random(LOWER, 10);
        Gateway gateway = new Gateway();
        gateway.setGatewayId(id);
        gateway.setName(name);
        gateway.setRoleArn(roleArn);
        gateway.setAuthorizerType(authorizerType);
        gateway.setProtocolType("MCP");
        gateway.setStatus(STATUS_READY);
        gateway.setDescription(description);
        gateway.setGatewayUrl("https://" + id + ".gateway.bedrock-agentcore." + region + ".amazonaws.com");
        gateway.setWorkloadIdentityArn(regionResolver.buildArn(ARN_SERVICE, region,
                "workload-identity-directory/default/workload-identity/" + id));
        gateway.setCreatedAt(now);
        gateway.setUpdatedAt(now);
        gateway.setAccountId(regionResolver.getAccountId());
        if (tags != null) {
            gateway.getTags().putAll(tags);
        }
        storage.put(key(region, id), gateway);
        return gateway;
    }

    // ── Tagging ──

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(findByArn(region, arn).getTags());
    }

    public void tagByArn(String region, String arn, Map<String, String> tags) {
        Gateway gateway = findByArn(region, arn);
        gateway.getTags().putAll(tags);
        storage.put(key(region, gateway.getGatewayId()), gateway);
    }

    public void untagByArn(String region, String arn, List<String> keys) {
        Gateway gateway = findByArn(region, arn);
        keys.forEach(gateway.getTags()::remove);
        storage.put(key(region, gateway.getGatewayId()), gateway);
    }

    private Gateway findByArn(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("gateway/")) {
            throw new AwsException("ValidationException", "Unsupported resource ARN: " + arn, 400);
        }
        return get(parts[5].substring("gateway/".length()), region);
    }

    public Gateway get(String id, String region) {
        return storage.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Gateway not found: " + id, 404));
    }

    public Gateway update(String id, String name, String authorizerType, String roleArn,
                          String description, String region) {
        Gateway gateway = get(id, region);
        if (name != null) {
            gateway.setName(name);
        }
        if (authorizerType != null) {
            gateway.setAuthorizerType(authorizerType);
        }
        if (roleArn != null) {
            gateway.setRoleArn(roleArn);
        }
        if (description != null) {
            gateway.setDescription(description);
        }
        gateway.setUpdatedAt(Instant.now());
        storage.put(key(region, id), gateway);
        return gateway;
    }

    public Gateway delete(String id, String region) {
        Gateway gateway = get(id, region);
        storage.delete(key(region, id));
        gateway.setStatus(STATUS_DELETING);
        return gateway;
    }

    public PaginatedResult<Gateway> list(Integer maxResults, String nextToken, String region) {
        String prefix = keyPrefix(region);
        List<Gateway> all = storage.scan(k -> k.startsWith(prefix));
        return Pagination.paginate(all, Gateway::getGatewayId, maxResults, nextToken, MAX_PAGE, "ValidationException");
    }

    public String gatewayArn(Gateway gateway, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "gateway/" + gateway.getGatewayId());
    }

    // ── Targets ──

    public GatewayTarget createTarget(String gatewayId, String name, JsonNode targetConfiguration,
                                      String description, String region) {
        if (targetConfiguration == null || targetConfiguration.isNull()) {
            throw new AwsException("ValidationException", "targetConfiguration is required", 400);
        }
        Gateway gateway = get(gatewayId, region);
        Instant now = Instant.now();
        GatewayTarget target = new GatewayTarget();
        target.setTargetId(random(ALNUM, 10));
        target.setName(name);
        target.setStatus(STATUS_READY);
        target.setTargetConfiguration(targetConfiguration);
        target.setDescription(description);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        gateway.getTargets().add(target);
        storage.put(key(region, gatewayId), gateway);
        return target;
    }

    public GatewayTarget getTarget(String gatewayId, String targetId, String region) {
        return findTarget(get(gatewayId, region), targetId);
    }

    public GatewayTarget updateTarget(String gatewayId, String targetId, JsonNode targetConfiguration,
                                      String description, String region) {
        Gateway gateway = get(gatewayId, region);
        GatewayTarget target = findTarget(gateway, targetId);
        if (targetConfiguration != null) {
            target.setTargetConfiguration(targetConfiguration);
        }
        if (description != null) {
            target.setDescription(description);
        }
        target.setUpdatedAt(Instant.now());
        storage.put(key(region, gatewayId), gateway);
        return target;
    }

    public GatewayTarget deleteTarget(String gatewayId, String targetId, String region) {
        Gateway gateway = get(gatewayId, region);
        GatewayTarget target = findTarget(gateway, targetId);
        gateway.getTargets().removeIf(t -> targetId.equals(t.getTargetId()));
        target.setStatus(STATUS_DELETING);
        storage.put(key(region, gatewayId), gateway);
        return target;
    }

    public PaginatedResult<GatewayTarget> listTargets(String gatewayId, Integer maxResults, String nextToken, String region) {
        Gateway gateway = get(gatewayId, region);
        return Pagination.paginate(gateway.getTargets(), GatewayTarget::getTargetId, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    private GatewayTarget findTarget(Gateway gateway, String targetId) {
        return gateway.getTargets().stream()
                .filter(t -> t.getTargetId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Gateway target not found: " + targetId, 404));
    }

    private static String sanitize(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (s.isEmpty()) {
            s = "gw";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String random(String alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String key(String region, String id) {
        return keyPrefix(region) + id;
    }

    private static String keyPrefix(String region) {
        return "gateway:" + region + ":";
    }
}
