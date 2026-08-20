package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** CRUD for AgentCore memory resources. Metadata registry only. */
@ApplicationScoped
public class BedrockAgentCoreMemoryService {

    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETING = "DELETING";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PAGE = 100;

    private final StorageBackend<String, Memory> storage;
    private final RegionResolver regionResolver;
    private final Set<String> deletedTokens = ConcurrentHashMap.newKeySet();

    @Inject
    public BedrockAgentCoreMemoryService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-memories.json",
                new TypeReference<Map<String, Memory>>() {}), regionResolver);
    }

    BedrockAgentCoreMemoryService(StorageBackend<String, Memory> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public Memory create(String name, Integer eventExpiryDuration, String description,
                         String clientToken, String region) {
        if (clientToken != null) {
            Optional<Memory> replay = storage.scan(k -> k.startsWith(keyPrefix(region))).stream()
                    .filter(m -> clientToken.equals(m.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "name is required", 400);
        }
        if (eventExpiryDuration == null) {
            throw new AwsException("ValidationException", "eventExpiryDuration is required", 400);
        }
        if (eventExpiryDuration < 3 || eventExpiryDuration > 365) {
            throw new AwsException("ValidationException", "eventExpiryDuration must be between 3 and 365", 400);
        }
        Instant now = Instant.now();
        String id = sanitize(name) + "-" + random(10);
        Memory memory = new Memory();
        memory.setMemoryId(id);
        memory.setName(name);
        memory.setStatus(STATUS_ACTIVE);
        memory.setDescription(description);
        memory.setEventExpiryDuration(eventExpiryDuration);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setAccountId(regionResolver.getAccountId());
        memory.setClientToken(clientToken);
        storage.put(key(region, id), memory);
        return memory;
    }

    public Memory get(String id, String region) {
        return storage.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Memory not found: " + id, 404));
    }

    public Memory update(String id, String description, String region) {
        Memory memory = get(id, region);
        if (description != null) {
            memory.setDescription(description);
        }
        memory.setUpdatedAt(Instant.now());
        storage.put(key(region, id), memory);
        return memory;
    }

    public Memory delete(String id, String clientToken, String region) {
        Optional<Memory> found = storage.get(key(region, id));
        if (found.isEmpty()) {
            if (clientToken != null && deletedTokens.contains(region + " " + clientToken)) {
                Memory marker = new Memory();
                marker.setMemoryId(id);
                marker.setStatus(STATUS_DELETING);
                return marker;
            }
            throw new AwsException("ResourceNotFoundException", "Memory not found: " + id, 404);
        }
        Memory memory = found.get();
        storage.delete(key(region, id));
        if (clientToken != null) {
            deletedTokens.add(region + " " + clientToken);
        }
        memory.setStatus(STATUS_DELETING);
        return memory;
    }

    public PaginatedResult<Memory> list(Integer maxResults, String nextToken, String region) {
        String prefix = keyPrefix(region);
        List<Memory> all = storage.scan(k -> k.startsWith(prefix));
        return Pagination.paginate(all, Memory::getMemoryId, maxResults, nextToken, MAX_PAGE, "ValidationException");
    }

    public String arn(Memory memory, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "memory/" + memory.getMemoryId());
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) {
            s = "m" + s;
        }
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM.charAt(ThreadLocalRandom.current().nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String key(String region, String id) {
        return keyPrefix(region) + id;
    }

    private static String keyPrefix(String region) {
        return "memory:" + region + ":";
    }
}
