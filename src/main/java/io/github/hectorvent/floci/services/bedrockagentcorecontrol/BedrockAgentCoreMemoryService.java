package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.ListResult;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

    @Inject
    public BedrockAgentCoreMemoryService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-memories.json",
                new TypeReference<Map<String, Memory>>() {}), regionResolver);
    }

    BedrockAgentCoreMemoryService(StorageBackend<String, Memory> storage, RegionResolver regionResolver) {
        this.storage = storage;
        this.regionResolver = regionResolver;
    }

    public Memory create(String name, Integer eventExpiryDuration, String description, String region) {
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

    public Memory delete(String id, String region) {
        Memory memory = get(id, region);
        storage.delete(key(region, id));
        memory.setStatus(STATUS_DELETING);
        return memory;
    }

    public ListResult<Memory> list(int maxResults, String nextToken, String region) {
        String prefix = keyPrefix(region);
        List<Memory> all = storage.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(Memory::getMemoryId))
                .collect(Collectors.toList());
        int limit = maxResults > 0 ? Math.min(maxResults, MAX_PAGE) : MAX_PAGE;
        String after = decode(nextToken);
        int start = 0;
        if (after != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getMemoryId().compareTo(after) > 0) {
                    start = i;
                    break;
                }
                start = i + 1;
            }
        }
        List<Memory> page = all.stream().skip(start).limit(limit).collect(Collectors.toList());
        String token = null;
        if (start + limit < all.size() && !page.isEmpty()) {
            token = encode(page.get(page.size() - 1).getMemoryId());
        }
        return new ListResult<>(page, token);
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

    private static String encode(String cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid nextToken", 400);
        }
    }
}
