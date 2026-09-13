package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcore.model.Branch;
import io.github.hectorvent.floci.services.bedrockagentcore.model.MemoryEvent;
import io.github.hectorvent.floci.services.bedrockagentcore.model.PayloadType;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AgentCore Memory events: the data plane behind {@code CreateEvent}, {@code ListEvents},
 * {@code GetEvent} and {@code DeleteEvent}.
 *
 * <p>Behaviour measured against real AgentCore in us-west-2, including the parts that are easy to
 * assume wrongly: a malformed memory id is a {@code ValidationException} while a well-formed but
 * unknown one is {@code ResourceNotFoundException}; an unknown actor or session is simply an empty
 * list rather than an error; events come back newest first; and {@code sessionId} is optional on
 * create, with the service generating a UUID when it is omitted.
 */
@ApplicationScoped
public class BedrockAgentCoreEventService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreEventService.class);

    /**
     * A memory id is a name followed by exactly ten alphanumerics. Measured: a nine or eleven
     * character suffix is rejected as malformed before the resource is ever looked up.
     */
    private static final Pattern MEMORY_ID = Pattern.compile("^.+-[A-Za-z0-9]{10}$");

    private static final int MAX_RESULTS_LIMIT = 100;
    private static final String DEFAULT_BRANCH = "main";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, MemoryEvent> eventStore;
    private final BedrockAgentCoreMemoryService memoryService;

    @Inject
    public BedrockAgentCoreEventService(StorageFactory storageFactory,
                                        BedrockAgentCoreMemoryService memoryService) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-events.json",
                new TypeReference<>() {}), memoryService);
    }

    BedrockAgentCoreEventService(StorageBackend<String, MemoryEvent> eventStore,
                                 BedrockAgentCoreMemoryService memoryService) {
        this.eventStore = eventStore;
        this.memoryService = memoryService;
    }

    public MemoryEvent createEvent(String memoryId, String actorId, String sessionId,
                                   Double eventTimestamp, List<PayloadType> payload,
                                   Branch branch, String region) {
        requireMemory(memoryId, region);
        requireField(actorId, "actorId");
        if (eventTimestamp == null) {
            throw new AwsException("ValidationException", "eventTimestamp is required", 400);
        }

        // sessionId is optional: AgentCore assigns a UUID when the caller omits it.
        String resolvedSession = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;

        MemoryEvent event = new MemoryEvent();
        event.setMemoryId(memoryId);
        event.setActorId(actorId);
        event.setSessionId(resolvedSession);
        event.setEventId(nextEventId(eventTimestamp));
        event.setEventTimestamp(eventTimestamp);
        // An empty payload is accepted, so the list is stored as given rather than rejected.
        event.setPayload(payload == null ? List.of() : payload);
        event.setBranch(branch != null ? branch : new Branch(DEFAULT_BRANCH));

        eventStore.put(eventKey(memoryId, actorId, resolvedSession, event.getEventId()), event);
        LOG.debugv("CreateEvent: memory={0} actor={1} session={2} event={3}",
                memoryId, actorId, resolvedSession, event.getEventId());
        return event;
    }

    /**
     * Lists a session's events, newest first.
     *
     * <p>The id embeds a zero-padded timestamp, so ordering by id descending is the same as
     * ordering by time descending, which is what AgentCore returns.
     */
    public List<MemoryEvent> listEvents(String memoryId, String actorId, String sessionId,
                                        Boolean includePayloads, Integer maxResults, String region) {
        requireMemory(memoryId, region);
        int limit = resolveMaxResults(maxResults);

        String prefix = eventKeyPrefix(memoryId, actorId, sessionId);
        List<MemoryEvent> events = new ArrayList<>(eventStore.scan(k -> k.startsWith(prefix)));
        events.sort(Comparator.comparing(MemoryEvent::getEventId).reversed());

        List<MemoryEvent> page = events.size() > limit ? events.subList(0, limit) : events;
        if (Boolean.FALSE.equals(includePayloads)) {
            // Measured: the payload key is absent entirely, not an empty list.
            return page.stream().map(BedrockAgentCoreEventService::withoutPayload).toList();
        }
        return List.copyOf(page);
    }

    public MemoryEvent getEvent(String memoryId, String actorId, String sessionId,
                                String eventId, String region) {
        requireMemory(memoryId, region);
        return eventStore.get(eventKey(memoryId, actorId, sessionId, eventId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Unable to find event with id " + eventId, 404));
    }

    public String deleteEvent(String memoryId, String actorId, String sessionId,
                              String eventId, String region) {
        requireMemory(memoryId, region);
        String key = eventKey(memoryId, actorId, sessionId, eventId);
        if (eventStore.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Unable to find event with id " + eventId, 404);
        }
        eventStore.delete(key);
        LOG.debugv("DeleteEvent: memory={0} event={1}", memoryId, eventId);
        return eventId;
    }

    // ── validation ───────────────────────────────────────────────

    /**
     * A malformed id never reaches a lookup: AgentCore answers {@code ValidationException} for one
     * that cannot be an id at all, and {@code ResourceNotFoundException} only for a well-formed id
     * that does not resolve.
     */
    private void requireMemory(String memoryId, String region) {
        if (memoryId == null || !MEMORY_ID.matcher(memoryId).matches()) {
            throw new AwsException("ValidationException",
                    "Invalid memoryId: not a valid memory ID or ARN", 400);
        }
        try {
            memoryService.get(memoryId, region);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "Memory not found: " + memoryId, 404);
        }
    }

    private static void requireField(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required", 400);
        }
    }

    private static int resolveMaxResults(Integer maxResults) {
        if (maxResults == null) {
            return MAX_RESULTS_LIMIT;
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS_LIMIT) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'maxResults' failed to satisfy constraint: "
                            + "Member must have value less than or equal to " + MAX_RESULTS_LIMIT, 400);
        }
        return maxResults;
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * AgentCore event ids are a zero-padded epoch-millisecond prefix, a {@code #}, then a random
     * suffix, which makes them sort chronologically as plain strings.
     */
    private static String nextEventId(double eventTimestampSeconds) {
        long millis = Math.round(eventTimestampSeconds * 1000d);
        byte[] suffix = new byte[4];
        RANDOM.nextBytes(suffix);
        StringBuilder hex = new StringBuilder();
        for (byte b : suffix) {
            hex.append(String.format("%02x", b));
        }
        return String.format("%019d", millis) + "#" + hex;
    }

    private static MemoryEvent withoutPayload(MemoryEvent source) {
        MemoryEvent copy = new MemoryEvent();
        copy.setMemoryId(source.getMemoryId());
        copy.setActorId(source.getActorId());
        copy.setSessionId(source.getSessionId());
        copy.setEventId(source.getEventId());
        copy.setEventTimestamp(source.getEventTimestamp());
        copy.setBranch(source.getBranch());
        return copy;
    }

    private static String eventKeyPrefix(String memoryId, String actorId, String sessionId) {
        return memoryId + "::" + actorId + "::" + sessionId + "::";
    }

    private static String eventKey(String memoryId, String actorId, String sessionId, String eventId) {
        return eventKeyPrefix(memoryId, actorId, sessionId) + eventId;
    }
}
