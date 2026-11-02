package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJsonTimestamp;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridgev2.model.EventBusV2;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * EventBridge V2: the {@code AWSEventsV2} Smithy RPC v2 CBOR service, distinct from EventBridge's
 * {@code event-bus} resources and sharing nothing with them but the {@code events} signing name. Its event buses
 * accept events with {@code PutEvents} and deliver each to every running subscriber whose filters it matches.
 */
@ApplicationScoped
public class EventBridgeV2Service implements Resettable {
    static final String ACTIVE = "ACTIVE";
    static final String RUNNING = "RUNNING";

    private static final Logger LOG = Logger.getLogger(EventBridgeV2Service.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][.\\-_A-Za-z0-9]*");
    private static final Pattern DESCRIPTION = Pattern.compile("[^\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029]*");
    private static final Pattern KMS_KEY_IDENTIFIER = Pattern.compile("[a-zA-Z0-9_\\-/:]*");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[\\u0021-\\u007E]+");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final Pattern TAG_KEY = Pattern.compile("\\S([\\s\\S]*\\S)?");
    private static final Pattern TAG_VALUE = Pattern.compile("(\\S([\\s\\S]*\\S)?)?");
    private static final Pattern ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::\\d{12}:role/[\\w+=,.@/-]+");
    private static final Set<String> PARAMETER_BLOCKS = Set.of("LambdaParameters", "SqsParameters", "SnsParameters",
            "KinesisParameters", "StepFunctionsParameters", "HttpParameters", "UniversalTargetParameters",
            "EventBusV2Parameters");
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 25;
    private static final int MAX_TAGS = 200;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_LIST_RESULTS = 100;
    private static final List<String> UPDATABLE_SETTINGS = List.of("Description", "State", "FilterConfiguration",
            "BatchConfiguration", "Transformer", "RetryPolicy", "OnFailureConfiguration", "LogConfiguration");
    private static final String PUT_EVENTS_CONTENT_TYPE = "application/eventbridge+json";

    private final AccountAwareStorageBackend<EventBusV2> buses;
    private final AccountAwareStorageBackend<SubscriberV2> subscribers;
    private final RegionResolver regionResolver;
    private final EventBridgeV2Delivery delivery;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, OrderedDeliveries> deliveriesByBus = new ConcurrentHashMap<>();

    @Inject
    public EventBridgeV2Service(StorageFactory storageFactory, RegionResolver regionResolver,
                                EventBridgeV2Delivery delivery) {
        this(storageFactory.create("eventbridgev2", "eventbridgev2-buses.json",
                        new TypeReference<Map<String, EventBusV2>>() {}),
                storageFactory.create("eventbridgev2", "eventbridgev2-subscribers.json",
                        new TypeReference<Map<String, SubscriberV2>>() {}),
                regionResolver, delivery, Clock.systemUTC());
    }

    EventBridgeV2Service(AccountAwareStorageBackend<EventBusV2> buses,
                         AccountAwareStorageBackend<SubscriberV2> subscribers, RegionResolver regionResolver,
                         EventBridgeV2Delivery delivery, Clock clock) {
        this.buses = buses;
        this.subscribers = subscribers;
        this.regionResolver = regionResolver;
        this.delivery = delivery;
        this.clock = clock;
    }

    // ──────────────────────────── Event buses ────────────────────────────

    /**
     * Creates an event bus, {@code ACTIVE} at once. A retry with the {@code ClientToken} of an earlier create
     * returns that bus when it repeats the request, and fails when it changes it.
     */
    public synchronized EventBusV2 createEventBus(JsonNode request, String region) {
        String name = requireName(request);
        String description = optionalDescription(request);
        String kmsKeyIdentifier = optionalKmsKeyIdentifier(request);
        Integer retentionPeriodInDays = optionalRetentionPeriodInDays(request);
        Map<String, String> tags = readTags(request.get("Tags"));
        String clientToken = optionalClientToken(request);
        String createRequest = withoutClientToken(request);

        if (clientToken != null) {
            Optional<EventBusV2> retried = buses.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(bus -> clientToken.equals(bus.getClientToken()))
                    .findFirst();
            if (retried.isPresent()) {
                if (!createRequest.equals(retried.get().getCreateRequest())) {
                    throw idempotentMismatch(clientToken);
                }
                return retried.get();
            }
        }
        String key = storageKey(region, name);
        if (buses.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "Event bus " + name + " already exists.", 409);
        }

        Instant now = clock.instant();
        EventBusV2 bus = new EventBusV2();
        bus.setName(name);
        bus.setArn(regionResolver.buildArn("events", region, "event-busv2/" + name + "/" + newId()));
        bus.setDescription(description);
        bus.setKmsKeyIdentifier(kmsKeyIdentifier);
        bus.setRetentionPeriodInDays(retentionPeriodInDays);
        bus.setState(ACTIVE);
        bus.setCreationTime(now);
        bus.setLastModifiedTime(now);
        bus.setTags(tags);
        bus.setClientToken(clientToken);
        bus.setCreateRequest(createRequest);
        buses.put(key, bus);
        return bus;
    }

    /**
     * Sets a bus's description, KMS key, retention and tags to what the request gives, clearing those it leaves
     * out, as a CloudFormation update of the bus's template does. The name is the bus's identity and is not read.
     */
    public synchronized EventBusV2 replaceEventBusSettings(String eventBusArn, JsonNode request, String region) {
        EventBusV2 bus = requireEventBus(eventBusArn, region);
        String description = optionalDescription(request);
        String kmsKeyIdentifier = optionalKmsKeyIdentifier(request);
        Integer retentionPeriodInDays = optionalRetentionPeriodInDays(request);
        Map<String, String> tags = readTags(request.get("Tags"));
        bus.setDescription(description);
        bus.setKmsKeyIdentifier(kmsKeyIdentifier);
        bus.setRetentionPeriodInDays(retentionPeriodInDays);
        bus.setTags(tags);
        bus.setLastModifiedTime(clock.instant());
        buses.put(storageKey(region, bus.getName()), bus);
        return bus;
    }

    /** The bus {@code EventBusArn} names. */
    public EventBusV2 describeEventBus(JsonNode request, String region) {
        return requireEventBus(requireText(request, "EventBusArn"), region);
    }

    /** Deletes the bus {@code EventBusArn} names. */
    public void deleteEventBus(JsonNode request, String region) {
        deleteEventBus(requireText(request, "EventBusArn"), region);
    }

    /** Deletes a bus, which must have no subscribers left. */
    public synchronized void deleteEventBus(String eventBusArn, String region) {
        EventBusV2 bus = requireEventBus(eventBusArn, region);
        if (!subscribersOf(bus, region).isEmpty()) {
            throw new AwsException("ResourceInUseException",
                    "Event bus " + bus.getName() + " still has subscribers; delete them first.", 409);
        }
        buses.delete(storageKey(region, bus.getName()));
        deliveriesByBus.remove(bus.getArn());
    }

    /**
     * One page of the Region's buses in name order, those whose name starts with {@code NamePrefix}. Every bus is
     * the caller's own, so an {@code EventBusAccountId} naming another account lists none.
     */
    public PaginatedResult<EventBusV2> listEventBuses(JsonNode request, String region) {
        String namePrefix = optionalNamePrefix(request);
        String accountId = text(request, "EventBusAccountId");
        if (accountId != null && !ACCOUNT_ID.matcher(accountId).matches()) {
            throw invalidInput("EventBusAccountId must be 12 digits.");
        }
        List<EventBusV2> listed = accountId != null && !accountId.equals(regionResolver.getAccountId())
                ? List.of()
                : buses.scan(key -> key.startsWith(region + "::")).stream()
                        .filter(bus -> namePrefix == null || bus.getName().startsWith(namePrefix))
                        .toList();
        return page(listed, EventBusV2::getName, request, "event-busv2");
    }

    // ──────────────────────────── Subscribers ────────────────────────────

    /**
     * Creates a subscriber on a bus of the caller's, delivering each event it accepts that matches every filter
     * to its target, an Amazon SQS queue of the caller's. A retry with the {@code ClientToken} of an earlier create
     * returns that subscriber when it repeats the request, and fails when it changes it.
     */
    public synchronized SubscriberV2 createSubscriber(JsonNode request, String region) {
        String name = requireName(request);
        String eventBusArn = requireText(request, "EventBusArn");
        JsonNode filterConfiguration = optionalStructure(request, "FilterConfiguration");
        if (filterConfiguration != null) {
            SubscriberFilters.validate(filterConfiguration);
        }
        String clientToken = optionalClientToken(request);
        String createRequest = withoutClientToken(request);
        if (clientToken != null) {
            Optional<SubscriberV2> retried = subscribers.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(subscriber -> clientToken.equals(subscriber.getClientToken()))
                    .findFirst();
            if (retried.isPresent()) {
                if (!createRequest.equals(retried.get().getCreateRequest())) {
                    throw idempotentMismatch(clientToken);
                }
                return retried.get();
            }
        }
        EventBusV2 bus = requireEventBus(eventBusArn, region);
        String key = subscriberKey(region, bus.getName(), name);
        if (subscribers.get(key).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "Subscriber " + name + " already exists on event bus " + bus.getName() + ".", 409);
        }

        Instant now = clock.instant();
        SubscriberV2 subscriber = new SubscriberV2();
        subscriber.setName(name);
        subscriber.setArn(regionResolver.buildArn("events", region, "subscriber/" + name + "/" + newId()));
        subscriber.setEventBusArn(bus.getArn());
        subscriber.setType(optionalEnum(request, "Type", Set.of("FIFO", "UNORDERED"), "UNORDERED"));
        subscriber.setStartingPosition(optionalEnum(request, "StartingPosition", Set.of("LATEST", "POINT_IN_TIME"),
                "LATEST"));
        subscriber.setPointInTimeConfiguration(pointInTimeConfiguration(request, subscriber.getStartingPosition()));
        applySettings(subscriber, request, filterConfiguration);
        subscriber.setCreationTime(now);
        subscriber.setLastModifiedTime(now);
        subscriber.setClientToken(clientToken);
        subscriber.setCreateRequest(createRequest);
        subscribers.put(key, subscriber);
        return subscriber;
    }

    /**
     * Sets a subscriber's description, target, filters, transformer, delivery settings, state and tags to what the
     * request gives, clearing those it leaves out, as a CloudFormation update of the subscriber's template does.
     * Its name, bus, type and starting position are its identity and are not read.
     */
    public synchronized SubscriberV2 replaceSubscriberSettings(String subscriberArn, JsonNode request, String region) {
        SubscriberV2 subscriber = requireSubscriber(subscriberArn, region);
        return changeSettings(subscriber, request, region);
    }

    /**
     * Changes the settings an {@code UpdateSubscriber} request gives and leaves those it omits as they are. The
     * target stays the subscriber's own: the request's {@code InvokeConfiguration} carries the role and target
     * parameters but no {@code TargetArn}. Events are not retained, so {@code ResumePosition} is checked and has no
     * effect.
     */
    public synchronized SubscriberV2 updateSubscriber(JsonNode request, String region) {
        SubscriberV2 subscriber = requireSubscriber(requireText(request, "SubscriberArn"), region);
        optionalEnum(request, "ResumePosition", Set.of("LAST_PROCESSED", "LATEST"), null);
        JsonNode invokeConfiguration = optionalStructure(request, "InvokeConfiguration");
        if (invokeConfiguration != null && invokeConfiguration.has("TargetArn")) {
            throw invalidInput("InvokeConfiguration.TargetArn cannot be updated.");
        }

        ObjectNode settings = currentSettings(subscriber);
        for (String field : UPDATABLE_SETTINGS) {
            if (request.hasNonNull(field)) {
                settings.set(field, request.get(field));
            }
        }
        if (invokeConfiguration != null) {
            ObjectNode invoke = invokeConfiguration.deepCopy();
            invoke.set("TargetArn", subscriber.getInvokeConfiguration().get("TargetArn"));
            settings.set("InvokeConfiguration", invoke);
        }
        return changeSettings(subscriber, settings, region);
    }

    /**
     * Stores a new subscriber with {@code subscriber}'s identity and {@code settings}, so a refused change changes
     * nothing and a reader sees the subscriber before or after the change, never part way through it.
     */
    private SubscriberV2 changeSettings(SubscriberV2 subscriber, JsonNode settings, String region) {
        JsonNode filterConfiguration = optionalStructure(settings, "FilterConfiguration");
        if (filterConfiguration != null) {
            SubscriberFilters.validate(filterConfiguration);
        }
        SubscriberV2 changed = new SubscriberV2();
        applySettings(changed, settings, filterConfiguration);
        changed.setName(subscriber.getName());
        changed.setArn(subscriber.getArn());
        changed.setEventBusArn(subscriber.getEventBusArn());
        changed.setType(subscriber.getType());
        changed.setStartingPosition(subscriber.getStartingPosition());
        changed.setPointInTimeConfiguration(subscriber.getPointInTimeConfiguration());
        changed.setCreationTime(subscriber.getCreationTime());
        changed.setLastModifiedTime(clock.instant());
        changed.setClientToken(subscriber.getClientToken());
        changed.setCreateRequest(subscriber.getCreateRequest());
        subscribers.put(subscriberKey(region, busName(subscriber.getEventBusArn()), subscriber.getName()), changed);
        return changed;
    }

    /** The subscriber {@code SubscriberArn} names. */
    public SubscriberV2 describeSubscriber(JsonNode request, String region) {
        return requireSubscriber(requireText(request, "SubscriberArn"), region);
    }

    /**
     * One page of the Region's subscribers, or those of the bus {@code EventBusArn} names, whose name starts with
     * {@code NamePrefix}, in order of bus and then subscriber name.
     */
    public PaginatedResult<SubscriberV2> listSubscribers(JsonNode request, String region) {
        String namePrefix = optionalNamePrefix(request);
        String eventBusArn = text(request, "EventBusArn");
        EventBusV2 bus = eventBusArn == null ? null : requireEventBus(eventBusArn, region);
        List<SubscriberV2> listed = (bus == null ? subscribers.scan(key -> key.startsWith(region + "::"))
                : subscribersOf(bus, region)).stream()
                .filter(subscriber -> namePrefix == null || subscriber.getName().startsWith(namePrefix))
                .toList();
        return page(listed, subscriber -> busName(subscriber.getEventBusArn()) + "::" + subscriber.getName(),
                request, "subscriber");
    }

    /** Deletes the subscriber {@code SubscriberArn} names. */
    public void deleteSubscriber(JsonNode request, String region) {
        deleteSubscriber(requireText(request, "SubscriberArn"), region);
    }

    public synchronized void deleteSubscriber(String subscriberArn, String region) {
        SubscriberV2 subscriber = requireSubscriber(subscriberArn, region);
        subscribers.delete(subscriberKey(region, busName(subscriber.getEventBusArn()), subscriber.getName()));
    }

    public Optional<SubscriberV2> findSubscriber(String subscriberArn, String region) {
        return subscribers.scan(key -> key.startsWith(region + "::")).stream()
                .filter(subscriber -> subscriber.getArn().equals(subscriberArn))
                .findFirst();
    }

    public Optional<EventBusV2> findEventBus(String eventBusArn, String region) {
        return Optional.ofNullable(busName(eventBusArn))
                .flatMap(name -> buses.get(storageKey(region, name)))
                .filter(bus -> bus.getArn().equals(eventBusArn));
    }

    // ──────────────────────────── Events ────────────────────────────

    /** A bus's deliveries waiting to be sent, in sequence order, and the lock held while sending them. */
    private record OrderedDeliveries(Queue<Map.Entry<SubscriberV2, PublishedEvent>> pending, ReentrantLock sending) {
    }

    /** The outcome of one {@code PutEvents} entry: its id and sequence number, or why it was refused. */
    public record PutEventsResult(String eventId, String sequenceNumber, String errorCode, String errorMessage) {
    }

    /**
     * Accepts each entry as an event in the EventBridge envelope and delivers it to every running subscriber of the
     * bus whose filters it matches. An entry whose {@code Detail} is not a JSON object is refused on its own.
     */
    public List<PutEventsResult> putEvents(JsonNode request, String region) {
        String eventBusArn = requireText(request, "EventBusArn");
        JsonNode entries = request.get("Entries");
        if (entries == null || !entries.isArray() || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw invalidInput("Entries must hold 1 to " + MAX_ENTRIES + " entries.");
        }
        List<Instant> times = new ArrayList<>();
        for (JsonNode entry : entries) {
            requireText(entry, "Source");
            requireText(entry, "DetailType");
            times.add(AwsJsonTimestamp.epochSeconds(entry, "Time", "InvalidInputException"));
        }
        List<PutEventsResult> results = new ArrayList<>();
        List<Map.Entry<SubscriberV2, PublishedEvent>> deliveries = new ArrayList<>();
        OrderedDeliveries ordered;
        synchronized (this) {
            EventBusV2 bus = requireEventBus(eventBusArn, region);
            List<SubscriberV2> running = subscribersOf(bus, region).stream()
                    .filter(subscriber -> RUNNING.equals(subscriber.getState()))
                    .toList();
            for (int i = 0; i < entries.size(); i++) {
                JsonNode entry = entries.get(i);
                JsonNode detail = detail(entry);
                if (detail == null) {
                    results.add(new PutEventsResult(null, null, "MalformedDetail", "Detail is malformed."));
                    continue;
                }
                bus.setSequenceNumber(bus.getSequenceNumber() + 1);
                String sequenceNumber = Long.toString(bus.getSequenceNumber());
                PublishedEvent event = envelope(bus, entry, times.get(i), detail, sequenceNumber);
                results.add(new PutEventsResult(event.systemMetadata().get("aws:EventId").asText(), sequenceNumber,
                        null, null));
                for (SubscriberV2 subscriber : running) {
                    if (SubscriberFilters.matches(subscriber.getFilterConfiguration(), event)) {
                        deliveries.add(Map.entry(subscriber, event));
                    }
                }
            }
            buses.put(storageKey(region, bus.getName()), bus);
            ordered = deliveriesByBus.computeIfAbsent(bus.getArn(),
                    arn -> new OrderedDeliveries(new ConcurrentLinkedQueue<>(), new ReentrantLock()));
            ordered.pending().addAll(deliveries);
        }
        send(ordered);
        return results;
    }

    /**
     * Sends a bus's queued deliveries in the order {@code PutEvents} queued them, which is sequence order, holding the
     * bus's sending lock, so a request that took later sequence numbers never sends ahead of an earlier one. Every
     * caller drains after queueing its own deliveries, so none is left queued, and a caller returns only once its own
     * have been sent, by itself or by the caller that held the lock before it.
     */
    private void send(OrderedDeliveries ordered) {
        ordered.sending().lock();
        try {
            Map.Entry<SubscriberV2, PublishedEvent> next;
            while ((next = ordered.pending().poll()) != null) {
                try {
                    delivery.deliver(next.getKey(), next.getValue());
                } catch (RuntimeException e) {
                    LOG.warnv(e, "EventBridge V2 subscriber {0} failed to deliver event {1}", next.getKey().getArn(),
                            next.getValue().systemMetadata().path("aws:EventId").asText());
                }
            }
        } finally {
            ordered.sending().unlock();
        }
    }

    private PublishedEvent envelope(EventBusV2 bus, JsonNode entry, Instant time, JsonNode detail,
                                    String sequenceNumber) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(bus.getArn());
        Instant ingested = clock.instant();
        String eventId = UUID.randomUUID().toString();

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("version", "0");
        data.put("id", eventId);
        data.put("detail-type", entry.get("DetailType").asText());
        data.put("source", entry.get("Source").asText());
        data.put("account", arn.accountId());
        data.put("time", (time == null ? ingested : time).truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("region", arn.region());
        data.set("resources", entry.has("Resources") && entry.get("Resources").isArray()
                ? entry.get("Resources") : JsonNodeFactory.instance.arrayNode());
        data.set("detail", detail);

        ObjectNode system = JsonNodeFactory.instance.objectNode();
        system.put("ContentType", PUT_EVENTS_CONTENT_TYPE);
        JsonNode supplied = entry.path("SystemMetadata");
        if (supplied.hasNonNull("EventGroupId")) {
            system.put("EventGroupId", supplied.get("EventGroupId").asText());
        }
        if (supplied.hasNonNull("DeduplicationId")) {
            system.put("DeduplicationId", supplied.get("DeduplicationId").asText());
        }
        system.put("aws:EventId", eventId);
        system.put("aws:IngestionTime", ingested.toString());
        system.put("aws:SequenceNumber", sequenceNumber);
        system.put("aws:DeliveryType", "LIVE");
        system.put("aws:Source", entry.get("Source").asText());
        system.put("aws:DetailType", entry.get("DetailType").asText());
        return new PublishedEvent(data, JsonNodeFactory.instance.objectNode(), system);
    }

    /** The entry's detail as a JSON object, an empty one when it gives none; null when it is not one. */
    private static JsonNode detail(JsonNode entry) {
        JsonNode detail = entry.get("Detail");
        if (detail == null || detail.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode parsed = MAPPER.readTree(detail.asText());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            // A Detail that is not JSON is the caller's error, reported as that entry's MalformedDetail result.
            return null;
        }
    }

    @Override
    public void clear() {
        buses.clear();
        subscribers.clear();
        deliveriesByBus.clear();
    }

    // ──────────────────────────── Subscriber settings ────────────────────────────

    /** A subscriber's settings in the shape of the request that sets them, for an update to overlay. */
    private static ObjectNode currentSettings(SubscriberV2 subscriber) {
        ObjectNode settings = JsonNodeFactory.instance.objectNode();
        if (subscriber.getDescription() != null) {
            settings.put("Description", subscriber.getDescription());
        }
        settings.set("InvokeConfiguration", subscriber.getInvokeConfiguration());
        settings.set("FilterConfiguration", subscriber.getFilterConfiguration());
        settings.set("Transformer", subscriber.getTransformer());
        settings.set("BatchConfiguration", subscriber.getBatchConfiguration());
        settings.set("RetryPolicy", subscriber.getRetryPolicy());
        settings.set("OnFailureConfiguration", subscriber.getOnFailureConfiguration());
        settings.set("LogConfiguration", subscriber.getLogConfiguration());
        settings.put("State", subscriber.getState());
        settings.set("Tags", MAPPER.valueToTree(subscriber.getTags()));
        return settings;
    }

    private void applySettings(SubscriberV2 subscriber, JsonNode request, JsonNode filterConfiguration) {
        subscriber.setDescription(optionalDescription(request));
        subscriber.setInvokeConfiguration(invokeConfiguration(request));
        subscriber.setFilterConfiguration(filterConfiguration);
        subscriber.setTransformer(transformer(request));
        subscriber.setBatchConfiguration(boundedIntegers(request, "BatchConfiguration",
                Map.of("MaxBatchSize", new int[] {1, 500}, "MaxBatchWindowInSeconds", new int[] {0, 300})));
        JsonNode retryPolicy = boundedIntegers(request, "RetryPolicy",
                Map.of("MaxRetryAttempts", new int[] {0, 185}, "MaxEventAgeInSeconds", new int[] {60, 86400}));
        if (retryPolicy != null) {
            optionalEnum(retryPolicy, "RetryStrategy", Set.of("ALL"), null);
        }
        subscriber.setRetryPolicy(retryPolicy);
        subscriber.setOnFailureConfiguration(onFailureConfiguration(request));
        JsonNode logConfiguration = optionalStructure(request, "LogConfiguration");
        if (logConfiguration != null) {
            optionalEnum(logConfiguration, "Level", Set.of("OFF", "ERROR", "INFO"), null);
            optionalEnum(logConfiguration, "IncludePayload", Set.of("FULL", "ON_ERROR_ONLY"), null);
        }
        subscriber.setLogConfiguration(logConfiguration);
        subscriber.setState(optionalEnum(request, "State", Set.of(RUNNING, "STOPPED"), RUNNING));
        subscriber.setTags(readTags(request.get("Tags")));
    }

    /**
     * The target and its parameters. Floci delivers to an Amazon SQS queue of the caller's, so a target of another
     * service or account, or a parameter block other than {@code SqsParameters}, is refused; a parameter is a
     * literal or a path into the event.
     */
    private JsonNode invokeConfiguration(JsonNode request) {
        JsonNode configuration = optionalStructure(request, "InvokeConfiguration");
        if (configuration == null) {
            throw invalidInput("InvokeConfiguration is required.");
        }
        String roleArn = requireText(configuration, "RoleArn");
        if (roleArn.length() > 1600 || !ROLE_ARN.matcher(roleArn).matches()) {
            throw invalidInput("InvokeConfiguration.RoleArn must be the ARN of an IAM role.");
        }
        String targetArn = requireText(configuration, "TargetArn");
        AwsArnUtils.Arn target = AwsArnUtils.isArn(targetArn) ? AwsArnUtils.parse(targetArn) : null;
        if (target == null) {
            throw invalidInput("InvokeConfiguration.TargetArn must be an ARN.");
        }
        if (!"sqs".equals(target.service())) {
            throw invalidInput("Floci delivers EventBridge V2 events only to Amazon SQS queues; "
                    + targetArn + " is not one.");
        }
        if (!regionResolver.getAccountId().equals(target.accountId())) {
            throw invalidInput("InvokeConfiguration.TargetArn must belong to your account.");
        }
        for (String block : PARAMETER_BLOCKS) {
            if (!"SqsParameters".equals(block) && configuration.hasNonNull(block)) {
                throw invalidInput("InvokeConfiguration." + block + " does not match the target type of "
                        + targetArn + ".");
            }
        }
        JsonNode parameters = optionalStructure(configuration, "SqsParameters");
        if (parameters != null) {
            for (String name : List.of("MessageGroupId", "MessageDeduplicationId", "DelaySeconds")) {
                requireSupportedExpression(text(parameters, name), "SqsParameters." + name);
            }
            JsonNode attributes = optionalStructure(parameters, "MessageAttributes");
            if (attributes != null) {
                for (Map.Entry<String, JsonNode> attribute : attributes.properties()) {
                    requireSupportedExpression(attribute.getKey(), "SqsParameters.MessageAttributes");
                    JsonNode stringValue = attribute.getValue().get("StringValue");
                    if (stringValue != null && stringValue.isTextual()) {
                        requireSupportedExpression(stringValue.textValue(),
                                "SqsParameters.MessageAttributes." + attribute.getKey());
                    }
                }
            }
        }
        return configuration;
    }

    private static void requireSupportedExpression(String value, String parameter) {
        if (value != null && !EventExpressions.isSupported(value)) {
            throw invalidInput(parameter + " is a JSONata expression Floci does not evaluate; it supports a literal "
                    + "or a path such as {% $events.SystemMetadata.EventGroupId %}.");
        }
    }

    private static JsonNode transformer(JsonNode request) {
        JsonNode transformer = optionalStructure(request, "Transformer");
        if (transformer == null) {
            return null;
        }
        String type = optionalEnum(transformer, "Type", Set.of("RAW", "WITH_METADATA", "JSONATA"), "RAW");
        if ("JSONATA".equals(type)) {
            throw invalidInput("Floci does not evaluate a JSONATA transformer; use RAW or WITH_METADATA.");
        }
        return transformer;
    }

    private static JsonNode onFailureConfiguration(JsonNode request) {
        JsonNode configuration = optionalStructure(request, "OnFailureConfiguration");
        if (configuration != null) {
            String arn = text(configuration, "Arn");
            if (arn != null && !AwsArnUtils.isArnFor(arn, "sqs")) {
                throw invalidInput("OnFailureConfiguration.Arn must be the ARN of an Amazon SQS queue.");
            }
        }
        return configuration;
    }

    private static JsonNode pointInTimeConfiguration(JsonNode request, String startingPosition) {
        JsonNode configuration = normalizedPointInTime(optionalStructure(request, "PointInTimeConfiguration"));
        if (configuration != null) {
            optionalEnum(configuration, "PointType", Set.of("HORIZON", "TIMESTAMP"), null);
        } else if ("POINT_IN_TIME".equals(startingPosition)) {
            throw invalidInput("StartingPosition POINT_IN_TIME requires PointInTimeConfiguration.");
        }
        return configuration;
    }

    /**
     * A copy of the point-in-time configuration with its {@code StartingPoint} and {@code EndPoint} as epoch
     * seconds at millisecond precision, the form a response encodes as timestamps; null when there is none.
     */
    private static JsonNode normalizedPointInTime(JsonNode configuration) {
        if (configuration == null || configuration.isNull()) {
            return null;
        }
        if (!(configuration instanceof ObjectNode structure)) {
            throw invalidInput("PointInTimeConfiguration must be a structure.");
        }
        ObjectNode normalized = structure.deepCopy();
        for (String member : List.of("StartingPoint", "EndPoint")) {
            Instant point = AwsJsonTimestamp.epochSeconds(structure, member, "InvalidInputException");
            if (point != null) {
                normalized.put(member, BigDecimal.valueOf(point.toEpochMilli()).movePointLeft(3));
            }
        }
        return normalized;
    }

    /** The structure, each named integer member of it within its bounds. */
    private static JsonNode boundedIntegers(JsonNode request, String field, Map<String, int[]> bounds) {
        JsonNode structure = optionalStructure(request, field);
        if (structure == null) {
            return null;
        }
        bounds.forEach((member, range) -> {
            JsonNode value = structure.get(member);
            if (value != null && !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() < range[0] || value.intValue() > range[1])) {
                throw invalidInput(field + "." + member + " must be between " + range[0] + " and " + range[1] + ".");
            }
        });
        return structure;
    }

    // ──────────────────────────── Lookups ────────────────────────────

    private EventBusV2 requireEventBus(String eventBusArn, String region) {
        return findEventBus(eventBusArn, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Event bus " + eventBusArn + " does not exist.", 404));
    }

    private SubscriberV2 requireSubscriber(String subscriberArn, String region) {
        return findSubscriber(subscriberArn, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Subscriber " + subscriberArn + " does not exist.", 404));
    }

    private List<SubscriberV2> subscribersOf(EventBusV2 bus, String region) {
        String prefix = region + "::" + bus.getName() + "::";
        return subscribers.scan(key -> key.startsWith(prefix)).stream()
                .filter(subscriber -> bus.getArn().equals(subscriber.getEventBusArn()))
                .toList();
    }

    /** The bus name an {@code event-busv2/<name>/<id>} ARN gives; null for any other value. */
    public static String busName(String eventBusArn) {
        if (!AwsArnUtils.isArnFor(eventBusArn, "events")) {
            return null;
        }
        String[] resource = AwsArnUtils.parse(eventBusArn).resource().split("/");
        return resource.length == 3 && "event-busv2".equals(resource[0]) ? resource[1] : null;
    }

    private String newId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private static String subscriberKey(String region, String busName, String name) {
        return region + "::" + busName + "::" + name;
    }

    // ──────────────────────────── Validation ────────────────────────────

    private static String requireName(JsonNode request) {
        String name = text(request, "Name");
        if (name == null) {
            throw invalidInput("Name is required.");
        }
        if (name.isEmpty() || name.length() > 256 || !NAME.matcher(name).matches()) {
            throw invalidInput("Name must be 1-256 characters, start with a letter or digit, and contain only "
                    + "letters, digits, '.', '-' and '_'.");
        }
        return name;
    }

    private static String optionalNamePrefix(JsonNode request) {
        String namePrefix = text(request, "NamePrefix");
        if (namePrefix != null && (namePrefix.isEmpty() || namePrefix.length() > 256
                || !NAME.matcher(namePrefix).matches())) {
            throw invalidInput("NamePrefix must be 1-256 characters, start with a letter or digit, and contain only "
                    + "letters, digits, '.', '-' and '_'.");
        }
        return namePrefix;
    }

    /** One page of {@code items} in {@code cursorOf} order, as the request's {@code MaxResults} and {@code NextToken} ask. */
    private static <T> PaginatedResult<T> page(List<T> items, Function<T, String> cursorOf, JsonNode request,
                                               String namespace) {
        Integer maxResults = Pagination.parseMaxResults(request, "MaxResults", "InvalidInputException");
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_LIST_RESULTS)) {
            throw invalidInput("MaxResults must be between 1 and " + MAX_LIST_RESULTS + ".");
        }
        String nextToken = Pagination.parseNextToken(request, "NextToken", "InvalidInputException");
        if (nextToken != null && (nextToken.isEmpty() || nextToken.length() > 4096)) {
            throw invalidInput("NextToken must be 1-4096 characters.");
        }
        return Pagination.paginate(items, cursorOf, maxResults == null ? MAX_LIST_RESULTS : maxResults, nextToken,
                namespace, token -> invalidInput("NextToken is not valid."));
    }

    private static String optionalDescription(JsonNode request) {
        String description = text(request, "Description");
        if (description != null && (description.length() > 512 || !DESCRIPTION.matcher(description).matches())) {
            throw invalidInput("Description must be at most 512 characters with no control characters or line "
                    + "separators.");
        }
        return description;
    }

    private static String optionalKmsKeyIdentifier(JsonNode request) {
        JsonNode configuration = optionalStructure(request, "EncryptionConfiguration");
        String key = configuration == null ? null : text(configuration, "KmsKeyIdentifier");
        if (key != null && (key.length() > 2048 || !KMS_KEY_IDENTIFIER.matcher(key).matches())) {
            throw invalidInput("EncryptionConfiguration.KmsKeyIdentifier is not a valid KMS key identifier.");
        }
        return key;
    }

    private static Integer optionalRetentionPeriodInDays(JsonNode request) {
        JsonNode configuration = optionalStructure(request, "StorageConfiguration");
        JsonNode days = configuration == null ? null : configuration.get("RetentionPeriodInDays");
        if (days == null || days.isNull()) {
            return null;
        }
        if (!days.canConvertToInt() || !days.isIntegralNumber() || days.intValue() < 1 || days.intValue() > 365) {
            throw invalidInput("StorageConfiguration.RetentionPeriodInDays must be between 1 and 365.");
        }
        return days.intValue();
    }

    private static String optionalClientToken(JsonNode request) {
        String token = text(request, "ClientToken");
        if (token != null && (token.isEmpty() || token.length() > 64 || !CLIENT_TOKEN.matcher(token).matches())) {
            throw invalidInput("ClientToken must be 1-64 printable ASCII characters.");
        }
        return token;
    }

    private static String optionalEnum(JsonNode request, String field, Set<String> values, String defaultValue) {
        String value = text(request, field);
        if (value == null) {
            return defaultValue;
        }
        if (!values.contains(value)) {
            throw invalidInput(field + " must be one of " + String.join(", ", values.stream().sorted().toList()) + ".");
        }
        return value;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw invalidInput("Tags must be a map of tag keys to values.");
        }
        if (node.size() > MAX_TAGS) {
            throw invalidInput("Tags may hold at most " + MAX_TAGS + " tags.");
        }
        for (Map.Entry<String, JsonNode> tag : node.properties()) {
            String key = tag.getKey();
            JsonNode value = tag.getValue();
            if (key.length() > 128 || !TAG_KEY.matcher(key).matches()) {
                throw invalidInput("Tag key " + key + " must be 1-128 characters and not start or end with whitespace.");
            }
            if (!value.isTextual() || value.textValue().length() > 256
                    || !TAG_VALUE.matcher(value.textValue()).matches()) {
                throw invalidInput("Tag value for " + key + " must be at most 256 characters and not start or end "
                        + "with whitespace.");
            }
            tags.put(key, value.textValue());
        }
        return tags;
    }

    /**
     * The request without its {@code ClientToken}, every object's fields in name order, so a retry that sends the same
     * values with its fields or tags in another order compares equal to the request it repeats.
     */
    private static String withoutClientToken(JsonNode request) {
        JsonNode copy = request.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove("ClientToken");
        }
        try {
            return CANONICAL.writeValueAsString(CANONICAL.convertValue(copy, Object.class));
        } catch (JsonProcessingException e) {
            throw invalidInput("The request cannot be compared with the one its ClientToken was used for.");
        }
    }

    private static JsonNode optionalStructure(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw invalidInput(field + " must be a structure.");
        }
        return node;
    }

    private static String requireText(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isEmpty()) {
            throw invalidInput(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidInput(field + " must be a string.");
        }
        return node.textValue();
    }

    private static AwsException idempotentMismatch(String clientToken) {
        return new AwsException("IdempotentParameterMismatchException",
                "ClientToken " + clientToken + " was used with a different request.", 409);
    }

    private static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
