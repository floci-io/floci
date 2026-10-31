package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

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
    // clientToken idempotency: tokens of completed deletes, so a replayed delete succeeds
    // instead of 404ing. In-memory only (reset on restart), and each entry expires after
    // DeletedTokenLedger.TTL, after which a replay 404s like an unknown token.
    private final DeletedTokenLedger deletedTokens;
    private final Consumer<MemoryDeleted> onDelete;
    // Shared while an event is written, exclusive while a memory is deleted: an event written
    // after its memory's events were dropped would be unreachable.
    private final ReadWriteLock eventWrites = new ReentrantReadWriteLock();

    /** Fired before a memory is deleted, so the data plane can drop the memory's events. */
    public record MemoryDeleted(String memoryId) {}

    @Inject
    public BedrockAgentCoreMemoryService(StorageFactory storageFactory, RegionResolver regionResolver, Clock clock,
                                         Event<MemoryDeleted> deleted) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-memories.json",
                new TypeReference<Map<String, Memory>>() {}), regionResolver, clock, deleted::fire);
    }

    BedrockAgentCoreMemoryService(StorageBackend<String, Memory> storage, RegionResolver regionResolver) {
        this(storage, regionResolver, Clock.systemUTC());
    }

    BedrockAgentCoreMemoryService(StorageBackend<String, Memory> storage, RegionResolver regionResolver, Clock clock) {
        this(storage, regionResolver, clock, deleted -> { });
    }

    BedrockAgentCoreMemoryService(StorageBackend<String, Memory> storage, RegionResolver regionResolver, Clock clock,
                                  Consumer<MemoryDeleted> onDelete) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.deletedTokens = new DeletedTokenLedger(clock);
        this.onDelete = onDelete;
    }

    public Memory create(String name, Integer eventExpiryDuration, String description,
                         String encryptionKeyArn, String memoryExecutionRoleArn,
                         Map<String, String> tags, String clientToken, String region) {
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
        validateExpiry(eventExpiryDuration);
        Instant now = Instant.now();
        String id = sanitize(name) + "-" + random(10);
        Memory memory = new Memory();
        memory.setMemoryId(id);
        memory.setName(name);
        memory.setStatus(STATUS_ACTIVE);
        memory.setDescription(description);
        memory.setEventExpiryDuration(eventExpiryDuration);
        memory.setEncryptionKeyArn(encryptionKeyArn);
        memory.setMemoryExecutionRoleArn(memoryExecutionRoleArn);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setAccountId(regionResolver.getAccountId());
        memory.setClientToken(clientToken);
        if (tags != null) {
            memory.getTags().putAll(tags);
        }
        storage.put(key(region, id), memory);
        return memory;
    }

    public Memory get(String id, String region) {
        return storage.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Memory not found: " + id, 404));
    }

    /** UpdateMemory on the API: a null field is left as it is, as on AWS. */
    public Memory update(String id, String description, Integer eventExpiryDuration,
                         String memoryExecutionRoleArn, String region) {
        return update(id, description, eventExpiryDuration, memoryExecutionRoleArn, false, region);
    }

    /**
     * Updates a memory from arguments that are its whole declared configuration when
     * {@code replaceOmitted} is true: a null description or execution role then clears the stored
     * one, which the API call leaves alone.
     */
    public Memory update(String id, String description, Integer eventExpiryDuration,
                         String memoryExecutionRoleArn, boolean replaceOmitted, String region) {
        // Validate all inputs before mutating: get() returns the live stored object,
        // so a throw after the first setter would leave a partially applied update.
        if (eventExpiryDuration != null) {
            validateExpiry(eventExpiryDuration);
        }
        Memory memory = get(id, region);
        if (description != null || replaceOmitted) {
            memory.setDescription(description);
        }
        if (eventExpiryDuration != null) {
            memory.setEventExpiryDuration(eventExpiryDuration);
        }
        if (memoryExecutionRoleArn != null || replaceOmitted) {
            memory.setMemoryExecutionRoleArn(memoryExecutionRoleArn);
        }
        memory.setUpdatedAt(Instant.now());
        storage.put(key(region, id), memory);
        return memory;
    }

    private static void validateExpiry(int eventExpiryDuration) {
        if (eventExpiryDuration < 3 || eventExpiryDuration > 365) {
            throw new AwsException("ValidationException", "eventExpiryDuration must be between 3 and 365", 400);
        }
    }

    /** Runs an event write for the memory, which cannot be deleted until the write is done. */
    public void writeEvent(String id, String region, Runnable write) {
        eventWrites.readLock().lock();
        try {
            get(id, region);
            write.run();
        } finally {
            eventWrites.readLock().unlock();
        }
    }

    public Memory delete(String id, String clientToken, String region) {
        eventWrites.writeLock().lock();
        try {
            return deleteExclusively(id, clientToken, region);
        } finally {
            eventWrites.writeLock().unlock();
        }
    }

    private Memory deleteExclusively(String id, String clientToken, String region) {
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
        // Events go first: if dropping them fails, the memory is still there to retry the delete.
        onDelete.accept(new MemoryDeleted(id));
        storage.delete(key(region, id));
        if (clientToken != null) {
            deletedTokens.record(region + " " + clientToken);
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

    // ── Tagging ──

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(getByArn(region, arn).getTags());
    }

    public void tagByArn(String region, String arn, Map<String, String> tags) {
        Memory memory = getByArn(region, arn);
        memory.getTags().putAll(tags);
        storage.put(key(region, memory.getMemoryId()), memory);
    }

    public void untagByArn(String region, String arn, List<String> keys) {
        Memory memory = getByArn(region, arn);
        keys.forEach(memory.getTags()::remove);
        storage.put(key(region, memory.getMemoryId()), memory);
    }

    /** The memory a memory ARN names; ValidationException for any other ARN, ResourceNotFoundException when gone. */
    public Memory getByArn(String region, String arn) {
        String resource = BedrockAgentCoreArns.resource(arn);
        if (resource == null || !resource.startsWith("memory/")) {
            throw new AwsException("ValidationException", "Unsupported resource ARN: " + arn, 400);
        }
        return get(resource.substring("memory/".length()), region);
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
