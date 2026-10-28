package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.redshift.RedshiftEventCatalog.EventInfo;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Records the events Floci raises for Redshift resources, serves DescribeEvents and the event
 * subscription operations, and publishes matching events to the subscription's SNS topic.
 */
@ApplicationScoped
public class RedshiftEventService {

    private static final Logger LOG = Logger.getLogger(RedshiftEventService.class);

    /** DescribeEvents reports the past 14 days at most. */
    static final Duration RETENTION = Duration.ofDays(14);
    private static final long DEFAULT_WINDOW_MINUTES = 60;
    private static final int MAX_SUBSCRIPTIONS = 20;
    private static final int MAX_TAGS = 50;
    private static final int PAGE_MIN = 20;
    private static final int PAGE_MAX = 100;
    private static final int PRUNE_EVERY = 200;
    private static final String SNS_SUBJECT = "Amazon Redshift Notification";
    private static final Pattern SUBSCRIPTION_NAME = Pattern.compile("[A-Za-z](?:-?[A-Za-z0-9])*");
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final AccountAwareStorageBackend<RedshiftEvent> events;
    private final AccountAwareStorageBackend<EventSubscription> subscriptions;
    private final SnsService snsService;
    private final ServiceCallAuthorizer serviceCallAuthorizer;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    @Inject
    public RedshiftEventService(StorageFactory storageFactory, SnsService snsService,
                                ServiceCallAuthorizer serviceCallAuthorizer, RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this(storageFactory, snsService, serviceCallAuthorizer, regionResolver, objectMapper, Clock.systemUTC());
    }

    RedshiftEventService(StorageFactory storageFactory, SnsService snsService,
                         ServiceCallAuthorizer serviceCallAuthorizer, RegionResolver regionResolver,
                         ObjectMapper objectMapper, Clock clock) {
        this.events = storageFactory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        this.subscriptions = storageFactory.create("redshift", "redshift-event-subscriptions.json",
                new TypeReference<Map<String, EventSubscription>>() { });
        this.snsService = snsService;
        this.serviceCallAuthorizer = serviceCallAuthorizer;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ── Recording and delivery ───────────────────────────────────────────────

    /** Never fails the operation that raised the event: a lost event is logged, not surfaced. */
    public void record(EventInfo info, String sourceIdentifier, String message) {
        recordForAccount(events.accountId(), regionResolver.getRegion(), info, sourceIdentifier, message);
    }

    public void recordForAccount(String accountId, String region, EventInfo info, String sourceIdentifier, String message) {
        try {
            Instant now = clock.instant();
            long eventSequence = sequence.incrementAndGet();
            RedshiftEvent event = new RedshiftEvent();
            event.setKey(String.format(Locale.ROOT, "%019d-%010d-%s", now.toEpochMilli(),
                    eventSequence, UUID.randomUUID().toString().substring(0, 8)));
            event.setDate(now);
            event.setSeverity(info.severity());
            event.setEventId(info.id());
            event.setSourceIdentifier(sourceIdentifier);
            event.setSourceType(info.sourceType());
            event.setEventCategories(new ArrayList<>(List.of(info.category())));
            event.setMessage(message);
            events.putForAccount(accountId, key(region, event.getKey()), event);
            if (eventSequence % PRUNE_EVERY == 0) {
                prune(now);
            }
            deliver(event, accountId, region);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not record Redshift event {0} for {1}", info.id(), sourceIdentifier);
        }
    }

    private void prune(Instant now) {
        Instant floor = now.minus(RETENTION);
        for (Map.Entry<String, RedshiftEvent> entry : events.scanAllAccountsWithRawKeys().entrySet()) {
            if (entry.getValue().getDate().isBefore(floor)) {
                events.deleteRaw(entry.getKey());
            }
        }
    }

    private void deliver(RedshiftEvent event, String accountId, String region) {
        for (Delivery delivery : matchingDeliveries(event, accountId, region)) {
            publish(delivery, event, accountId, region);
        }
    }

    /**
     * Filters and topic are read under the lock {@link #modifySubscription} holds, so an event is never matched
     * against one version of a subscription and published to the topic of another.
     */
    private synchronized List<Delivery> matchingDeliveries(RedshiftEvent event, String accountId, String region) {
        List<Delivery> matching = new ArrayList<>();
        for (EventSubscription subscription : subscriptions.scanForAccount(accountId, key -> key.startsWith(region + "/"))) {
            EventSubscription snapshot = snapshot(subscription);
            if (matches(snapshot, event)) {
                matching.add(new Delivery(subscription, snapshot));
            }
        }
        return matching;
    }

    /** {@code stored} identifies the subscription a failure is recorded against; {@code snapshot} is what matched. */
    private record Delivery(EventSubscription stored, EventSubscription snapshot) { }

    private static EventSubscription snapshot(EventSubscription subscription) {
        EventSubscription copy = new EventSubscription();
        copy.setCustomerAwsId(subscription.getCustomerAwsId());
        copy.setCustSubscriptionId(subscription.getCustSubscriptionId());
        copy.setSnsTopicArn(subscription.getSnsTopicArn());
        copy.setStatus(subscription.getStatus());
        copy.setSubscriptionCreationTime(subscription.getSubscriptionCreationTime());
        copy.setSourceType(subscription.getSourceType());
        copy.setSourceIdsList(new ArrayList<>(subscription.getSourceIdsList()));
        copy.setEventCategoriesList(new ArrayList<>(subscription.getEventCategoriesList()));
        copy.setSeverity(subscription.getSeverity());
        copy.setEnabled(subscription.isEnabled());
        return copy;
    }

    private static boolean matches(EventSubscription subscription, RedshiftEvent event) {
        if (!subscription.isEnabled() || !"active".equals(subscription.getStatus())) {
            return false;
        }
        if (subscription.getSourceType() != null && !subscription.getSourceType().equals(event.getSourceType())) {
            return false;
        }
        List<String> sourceIds = subscription.getSourceIdsList();
        if (!sourceIds.isEmpty() && !sourceIds.contains(event.getSourceIdentifier())) {
            return false;
        }
        List<String> categories = subscription.getEventCategoriesList();
        if (!categories.isEmpty() && categories.stream().noneMatch(category ->
                event.getEventCategories().stream().anyMatch(category::equalsIgnoreCase))) {
            return false;
        }
        return subscription.getSeverity() == null || subscription.getSeverity().equals(event.getSeverity());
    }

    private void publish(Delivery delivery, RedshiftEvent event, String accountId, String region) {
        EventSubscription subscription = delivery.snapshot();
        String topicArn = subscription.getSnsTopicArn();
        // A missing topic has no policy to consult, so it would read as a denial; name it for what it is.
        if (!snsService.topicExists(topicArn, AwsArnUtils.parse(topicArn).region())) {
            updateFailureStatus(delivery.stored(), topicArn, "topic-not-exist", accountId, region);
            LOG.warnv("The topic {0} of the subscription does not exist; event {1} was not published",
                    topicArn, event.getEventId());
            return;
        }
        String subscriptionArn = regionResolver.buildArn("redshift", region, accountId,
                "eventsubscription:" + subscription.getCustSubscriptionId());
        // The topic's policy decides alone, as for any service publishing on its own behalf.
        if (serviceCallAuthorizer.authorize("redshift", "sns:Publish", topicArn, subscriptionArn, accountId)
                != Decision.ALLOW) {
            updateFailureStatus(delivery.stored(), topicArn, "no-permission", accountId, region);
            LOG.warnv("The policy of {0} does not let Redshift publish event {1}", topicArn, event.getEventId());
            return;
        }
        try {
            String topicRegion = AwsArnUtils.parse(topicArn).region();
            RequestScopes.runAs(accountId, topicRegion,
                    () -> snsService.publish(topicArn, null, notification(event), SNS_SUBJECT, topicRegion));
        } catch (AwsException e) {
            if ("NotFound".equals(e.getErrorCode())) {
                updateFailureStatus(delivery.stored(), topicArn, "topic-not-exist", accountId, region);
            } else if (isAuthorizationFailure(e)) {
                updateFailureStatus(delivery.stored(), topicArn, "no-permission", accountId, region);
            }
            LOG.warnv(e, "Could not publish Redshift event {0} to {1}", event.getEventId(), topicArn);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not publish Redshift event {0} to {1}", event.getEventId(), topicArn);
        }
    }

    private synchronized void updateFailureStatus(EventSubscription attempted, String topicArn, String status,
                                                   String accountId, String region) {
        String storageKey = key(region, attempted.getCustSubscriptionId());
        subscriptions.getForAccount(accountId, storageKey)
                .filter(current -> current == attempted && topicArn.equals(current.getSnsTopicArn()))
                .ifPresent(current -> {
                    current.setStatus(status);
                    subscriptions.putForAccount(accountId, storageKey, current);
                });
    }

    /** AWS does not document the notification body, so this carries the same facts DescribeEvents reports. */
    private String notification(RedshiftEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Event Source", event.getSourceType());
        body.put("Event Time", EVENT_TIME.format(event.getDate()));
        body.put("Source ID", event.getSourceIdentifier());
        body.put("Event ID", event.getEventId());
        body.put("Event Categories", event.getEventCategories());
        body.put("Severity", event.getSeverity());
        body.put("Event Message", event.getMessage());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the Redshift event notification", e);
        }
    }

    // ── DescribeEvents / DescribeEventCategories ─────────────────────────────

    public PaginatedResult<RedshiftEvent> describeEvents(String sourceIdentifier, String sourceType, Instant startTime,
                                                         Instant endTime, Integer duration, Integer maxRecords,
                                                         String marker) {
        validatePageSize(maxRecords);
        boolean hasType = sourceType != null && !sourceType.isBlank();
        boolean hasId = sourceIdentifier != null && !sourceIdentifier.isBlank();
        if (hasType) {
            requireSourceType(sourceType);
        }
        if (hasType != hasId) {
            throw new AwsException("InvalidParameterCombination",
                    "SourceType and SourceIdentifier must be provided together.", 400);
        }
        if (duration != null && duration < 1) {
            throw new AwsException("InvalidParameterValue", "Duration must be at least 1 minute.", 400);
        }
        Instant now = clock.instant();
        Instant from;
        Instant to;
        if (startTime != null || endTime != null) {
            from = startTime != null ? startTime : now.minus(RETENTION);
            to = endTime != null ? endTime : now;
        } else {
            from = now.minus(Duration.ofMinutes(duration != null ? duration : DEFAULT_WINDOW_MINUTES));
            to = now;
        }
        if (from.isAfter(to)) {
            throw new AwsException("InvalidParameterValue", "StartTime must not be after EndTime.", 400);
        }
        Instant floor = now.minus(RETENTION);
        Instant start = from.isBefore(floor) ? floor : from;
        List<RedshiftEvent> matching = events.scan(this::inRegion).stream()
                .filter(event -> !event.getDate().isBefore(start) && !event.getDate().isAfter(to))
                .filter(event -> !hasType || sourceType.equals(event.getSourceType())
                        && sourceIdentifier.equals(event.getSourceIdentifier()))
                .sorted(Comparator.comparing(RedshiftEvent::getKey))
                .toList();
        return Pagination.paginate(matching, RedshiftEvent::getKey, maxRecords, marker, PAGE_MAX, PAGE_MAX,
                "InvalidParameterValue", "Marker");
    }

    /** The emitted events grouped by source type; a source type that raises none still appears, empty. */
    public Map<String, List<EventInfo>> describeEventCategories(String sourceType) {
        if (sourceType != null && !sourceType.isBlank()) {
            requireSourceType(sourceType);
        }
        Map<String, List<EventInfo>> byType = new LinkedHashMap<>();
        for (String type : List.of(RedshiftEventCatalog.CLUSTER, RedshiftEventCatalog.PARAMETER_GROUP,
                RedshiftEventCatalog.SECURITY_GROUP, RedshiftEventCatalog.SNAPSHOT,
                RedshiftEventCatalog.SCHEDULED_ACTION)) {
            if (sourceType == null || sourceType.isBlank() || sourceType.equals(type)) {
                byType.put(type, RedshiftEventCatalog.ALL.stream()
                        .filter(info -> info.sourceType().equals(type)).toList());
            }
        }
        return byType;
    }

    private static void requireSourceType(String sourceType) {
        if (!RedshiftEventCatalog.SOURCE_TYPES.contains(sourceType)) {
            throw new AwsException("InvalidParameterValue", "SourceType must be one of "
                    + RedshiftEventCatalog.SOURCE_TYPES.stream().sorted().toList() + ".", 400);
        }
    }

    private static void validatePageSize(Integer maxRecords) {
        if (maxRecords != null && (maxRecords < PAGE_MIN || maxRecords > PAGE_MAX)) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + PAGE_MIN + " and " + PAGE_MAX + ".", 400);
        }
    }

    // ── Event subscriptions ──────────────────────────────────────────────────

    /** {@code sourceExists} answers whether a source of the given type and identifier exists. */
    public synchronized EventSubscription createSubscription(String name, String snsTopicArn, String sourceType,
                                                             List<String> sourceIds, List<String> categories,
                                                             String severity, Boolean enabled,
                                                             Map<String, String> tags,
                                                             BiPredicate<String, String> sourceExists) {
        validateSubscriptionName(name);
        if (subscriptions.get(key(name)).isPresent()) {
            throw new AwsException("SubscriptionAlreadyExist", "Subscription " + name + " already exists.", 400);
        }
        if (subscriptions.scan(this::inRegion).size() >= MAX_SUBSCRIPTIONS) {
            throw new AwsException("EventSubscriptionQuotaExceeded",
                    "The request would exceed the allowed number of event subscriptions for this account.", 400);
        }
        validateTags(tags);
        validateFilters(sourceType, sourceIds, categories, severity, sourceExists);
        requireTopic(snsTopicArn);
        EventSubscription subscription = new EventSubscription();
        subscription.setCustomerAwsId(regionResolver.getAccountId());
        subscription.setCustSubscriptionId(name);
        subscription.setSnsTopicArn(snsTopicArn);
        subscription.setStatus("active");
        subscription.setSubscriptionCreationTime(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        applyConfiguration(subscription, sourceType, sourceIds, categories, severity, enabled, tags);
        subscriptions.put(key(name), subscription);
        subscriptions.flush();
        return subscription;
    }

    /** Applies only the members the request names. */
    public synchronized EventSubscription modifySubscription(String name, String snsTopicArn, String sourceType,
                                                             List<String> sourceIds, List<String> categories,
                                                             String severity, Boolean enabled,
                                                             BiPredicate<String, String> sourceExists) {
        EventSubscription subscription = requireSubscription(name);
        if (blankToNull(snsTopicArn) != null) {
            requireTopic(snsTopicArn);
        }
        String effectiveType = blankToNull(sourceType) != null ? sourceType : subscription.getSourceType();
        boolean changedType = !Objects.equals(effectiveType, subscription.getSourceType());
        List<String> effectiveIds = sourceIds != null ? sourceIds
                : changedType ? List.of() : subscription.getSourceIdsList();
        validateFilters(effectiveType, effectiveIds, categories, severity,
                sourceIds != null || changedType ? sourceExists : (type, id) -> true);
        if (blankToNull(snsTopicArn) != null) {
            subscription.setSnsTopicArn(snsTopicArn);
            subscription.setStatus("active");
        }
        subscription.setSourceType(effectiveType);
        subscription.setSourceIdsList(new ArrayList<>(effectiveIds));
        if (categories != null) {
            subscription.setEventCategoriesList(new ArrayList<>(categories));
        }
        if (blankToNull(severity) != null) {
            subscription.setSeverity(severity.toUpperCase(Locale.ROOT));
        }
        if (enabled != null) {
            subscription.setEnabled(enabled);
        }
        subscriptions.put(key(name), subscription);
        subscriptions.flush();
        return subscription;
    }

    public synchronized void deleteSubscription(String name) {
        requireSubscription(name);
        subscriptions.delete(key(name));
        subscriptions.flush();
    }

    public PaginatedResult<EventSubscription> describeSubscriptions(String name, List<String> tagKeys,
                                                                    List<String> tagValues, Integer maxRecords,
                                                                    String marker) {
        validatePageSize(maxRecords);
        List<EventSubscription> matching;
        if (blankToNull(name) != null) {
            matching = List.of(requireSubscription(name));
        } else {
            boolean filterByTag = tagKeys != null && !tagKeys.isEmpty() || tagValues != null && !tagValues.isEmpty();
            matching = subscriptions.scan(this::inRegion).stream()
                    .filter(subscription -> !filterByTag || hasAnyTag(subscription, tagKeys, tagValues))
                    .sorted(Comparator.comparing(EventSubscription::getCustSubscriptionId))
                    .toList();
        }
        return Pagination.paginate(matching, EventSubscription::getCustSubscriptionId, maxRecords, marker,
                PAGE_MAX, PAGE_MAX, "InvalidParameterValue", "Marker");
    }

    private static boolean hasAnyTag(EventSubscription subscription, List<String> keys, List<String> values) {
        return subscription.getTags().entrySet().stream().anyMatch(tag ->
                (keys == null || keys.isEmpty() || keys.contains(tag.getKey()))
                        && (values == null || values.isEmpty() || values.contains(tag.getValue())));
    }

    private EventSubscription requireSubscription(String name) {
        if (blankToNull(name) == null) {
            throw new AwsException("InvalidParameterValue", "SubscriptionName is required", 400);
        }
        return subscriptions.get(key(name)).orElseThrow(() -> new AwsException("SubscriptionNotFound",
                "Event subscription " + name + " not found.", 404));
    }

    private static void validateSubscriptionName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "SubscriptionName is required", 400);
        }
        if (name.length() > 255 || !SUBSCRIPTION_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterValue", "SubscriptionName must contain from 1 to 255 "
                    + "alphanumeric characters or hyphens, start with a letter, and neither end with a hyphen "
                    + "nor contain two consecutive hyphens.", 400);
        }
    }

    private void requireTopic(String snsTopicArn) {
        if (blankToNull(snsTopicArn) == null) {
            throw new AwsException("InvalidParameterValue", "SnsTopicArn is required", 400);
        }
        if (!AwsArnUtils.isArnFor(snsTopicArn, "sns")) {
            throw new AwsException("SNSInvalidTopic", "SnsTopicArn is not a valid Amazon SNS topic ARN.", 400);
        }
        try {
            Map<String, String> attributes = snsService.getTopicAttributes(snsTopicArn,
                    AwsArnUtils.parse(snsTopicArn).region());
            if ("true".equals(attributes.get("FifoTopic"))) {
                throw new AwsException("SNSInvalidTopic", "Redshift event subscriptions require a standard SNS topic.", 400);
            }
        } catch (AwsException e) {
            if ("NotFound".equals(e.getErrorCode())) {
                throw new AwsException("SNSTopicArnNotFound",
                        "An Amazon SNS topic with the ARN " + snsTopicArn + " does not exist.", 404);
            }
            if (isAuthorizationFailure(e)) {
                throw new AwsException("SNSNoAuthorization", "Not authorized to access the SNS topic.", 400);
            }
            throw e;
        }
    }

    private static void validateFilters(String sourceType, List<String> sourceIds, List<String> categories,
                                        String severity, BiPredicate<String, String> sourceExists) {
        boolean hasType = blankToNull(sourceType) != null;
        if (hasType) {
            requireSourceType(sourceType);
        }
        if (sourceIds != null && !sourceIds.isEmpty()) {
            if (!hasType) {
                throw new AwsException("InvalidParameterCombination",
                        "SourceType must be provided when SourceIds are supplied.", 400);
            }
            for (String sourceId : sourceIds) {
                if (!sourceExists.test(sourceType, sourceId)) {
                    throw new AwsException("SourceNotFound", "Source " + sourceId + " of type " + sourceType
                            + " could not be found.", 404);
                }
            }
        }
        if (categories != null) {
            for (String category : categories) {
                if (!RedshiftEventCatalog.CATEGORIES.contains(category.toLowerCase(Locale.ROOT))) {
                    throw new AwsException("SubscriptionCategoryNotFound",
                            "Event category " + category + " is not valid.", 404);
                }
            }
        }
        if (blankToNull(severity) != null
                && !RedshiftEventCatalog.SEVERITIES.contains(severity.toUpperCase(Locale.ROOT))) {
            throw new AwsException("SubscriptionSeverityNotFound",
                    "Severity " + severity + " must be ERROR or INFO.", 404);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String key(String name) {
        return key(regionResolver.getRegion(), name);
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private boolean inRegion(String storedKey) {
        return storedKey.startsWith(regionResolver.getRegion() + "/");
    }

    private static boolean isAuthorizationFailure(AwsException error) {
        return Set.of("AuthorizationError", "AccessDenied", "AccessDeniedException", "Forbidden")
                .contains(error.getErrorCode());
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TagLimitExceededFault", "You have exceeded the number of tags allowed.", 400);
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            if (tag.getKey() == null || tag.getKey().isBlank() || tag.getKey().length() > 128
                    || tag.getKey().toLowerCase(Locale.ROOT).startsWith("aws:")
                    || tag.getValue() == null || tag.getValue().length() > 256) {
                throw new AwsException("InvalidTagFault", "Invalid event subscription tag.", 400);
            }
        }
    }

    public synchronized void addSubscriptionTags(String name, Map<String, String> tags) {
        Map<String, String> updated = new LinkedHashMap<>(requireSubscription(name).getTags());
        updated.putAll(tags);
        replaceSubscriptionTags(name, updated);
    }

    public synchronized void deleteSubscriptionTags(String name, Collection<String> keys) {
        Map<String, String> updated = new LinkedHashMap<>(requireSubscription(name).getTags());
        keys.forEach(updated::remove);
        replaceSubscriptionTags(name, updated);
    }

    public synchronized EventSubscription reconcileSubscription(String name, String snsTopicArn, String sourceType,
                                                                List<String> sourceIds, List<String> categories,
                                                                String severity, Boolean enabled, Map<String, String> tags,
                                                                BiPredicate<String, String> sourceExists) {
        return replaceSubscription(name, snsTopicArn, sourceType, sourceIds, categories, severity,
                enabled, tags, sourceExists, null);
    }

    /** Restores trusted CloudFormation state even when its topic or source has since been deleted. */
    public synchronized EventSubscription restoreSubscription(String name, String snsTopicArn, String sourceType,
                                                              List<String> sourceIds, List<String> categories,
                                                              String severity, Boolean enabled, Map<String, String> tags,
                                                              String status) {
        return replaceSubscription(name, snsTopicArn, sourceType, sourceIds, categories, severity,
                enabled, tags, (type, id) -> true, status);
    }

    public synchronized void replaceSubscriptionTags(String name, Map<String, String> tags) {
        EventSubscription subscription = requireSubscription(name);
        validateTags(tags);
        subscription.setTags(new LinkedHashMap<>(tags));
        subscriptions.put(key(name), subscription);
        subscriptions.flush();
    }

    private EventSubscription replaceSubscription(String name, String snsTopicArn, String sourceType,
                                                   List<String> sourceIds, List<String> categories, String severity,
                                                   Boolean enabled, Map<String, String> tags,
                                                   BiPredicate<String, String> sourceExists, String restoredStatus) {
        EventSubscription prior = requireSubscription(name);
        validateTags(tags);
        validateFilters(sourceType, sourceIds, categories, severity, (type, id) ->
                Objects.equals(type, prior.getSourceType()) && prior.getSourceIdsList().contains(id)
                        || sourceExists.test(type, id));
        boolean changedTopic = !Objects.equals(snsTopicArn, prior.getSnsTopicArn());
        if (restoredStatus == null && changedTopic) {
            requireTopic(snsTopicArn);
        }
        EventSubscription updated = new EventSubscription();
        updated.setCustomerAwsId(prior.getCustomerAwsId());
        updated.setCustSubscriptionId(name);
        updated.setSubscriptionCreationTime(prior.getSubscriptionCreationTime());
        updated.setSnsTopicArn(snsTopicArn);
        updated.setStatus(restoredStatus != null ? restoredStatus : changedTopic ? "active" : prior.getStatus());
        applyConfiguration(updated, sourceType, sourceIds, categories, severity, enabled, tags);
        subscriptions.put(key(name), updated);
        subscriptions.flush();
        return updated;
    }

    private static void applyConfiguration(EventSubscription subscription, String sourceType,
                                           List<String> sourceIds, List<String> categories, String severity,
                                           Boolean enabled, Map<String, String> tags) {
        subscription.setSourceType(blankToNull(sourceType));
        subscription.setSourceIdsList(sourceIds == null ? new ArrayList<>() : new ArrayList<>(sourceIds));
        subscription.setEventCategoriesList(categories == null ? new ArrayList<>() : new ArrayList<>(categories));
        subscription.setSeverity(blankToNull(severity) == null ? null : severity.toUpperCase(Locale.ROOT));
        subscription.setEnabled(enabled == null || enabled);
        subscription.setTags(tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags));
    }
}
