package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eventbridgev2.EventBridgeV2Service;
import io.github.hectorvent.floci.services.eventbridgev2.model.EventBusV2;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provisions EventBridge V2's {@code AWS::EventsV2::EventBus} and {@code AWS::EventsV2::Subscriber}. A template's
 * properties are the service's request members, so the resolved properties are the request.
 *
 * <p>Both are named by their ARN, which {@code Ref} returns. An update that keeps a bus's {@code Name} changes its
 * description, encryption, storage and tags in place; one that changes it creates a new bus and leaves the old one
 * to the {@link ReplacementCleanup} record. An update that keeps a subscriber's name, bus, target, type, starting
 * position and point in time changes the rest in place; one that changes any of them replaces it. A replacement that keeps
 * the subscriber's name deletes it before creating the new one, the {@code delete_then_create} replacement its schema
 * declares, after snapshotting it ({@link #DELETED_PRIOR_ATTR}) so a rollback, or the create failing, recreates it
 * under its own ARN. An in-place update of either snapshots it first ({@link #UPDATED_PRIOR_ATTR}) so a rollback
 * puts its settings back.
 */
@ApplicationScoped
public class EventsV2CfnProvisioner implements CfnResourceProvisioner {

    static final String EVENT_BUS = "AWS::EventsV2::EventBus";
    static final String SUBSCRIBER = "AWS::EventsV2::Subscriber";

    /**
     * The subscriber a same-name replacement deleted, as JSON, kept until the update commits or rolls back so a
     * rollback can recreate it under its own ARN.
     */
    static final String DELETED_PRIOR_ATTR = "__FlociEventsV2DeletedSubscriber";

    /**
     * The bus or subscriber an in-place update changed, as JSON from before the change, kept until the update commits
     * or rolls back so a rollback can put its settings back.
     */
    static final String UPDATED_PRIOR_ATTR = "__FlociEventsV2UpdatedPrior";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper SNAPSHOT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d+");
    /** The integer members a template may give as a string, as a parameter reference resolves to one. */
    private static final Map<String, List<String>> INTEGER_MEMBERS = Map.of(
            "StorageConfiguration", List.of("RetentionPeriodInDays"),
            "BatchConfiguration", List.of("MaxBatchSize", "MaxBatchWindowInSeconds"),
            "RetryPolicy", List.of("MaxRetryAttempts", "MaxEventAgeInSeconds"));
    private static final List<String> TIMESTAMP_MEMBERS = List.of("StartingPoint", "EndPoint");

    private final EventBridgeV2Service service;

    @Inject
    public EventsV2CfnProvisioner(EventBridgeV2Service service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(EVENT_BUS, SUBSCRIBER);
    }

    @Override
    public boolean serviceDisabled() {
        return service.isDisabled();
    }

    @Override
    public String serviceEnabledFlag() {
        return "floci.services.eventbridgev2.enabled";
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case EVENT_BUS -> provisionEventBus(r, props, ctx);
            case SUBSCRIBER -> provisionSubscriber(r, props, ctx);
            default -> throw new IllegalStateException("EventsV2CfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    private void provisionEventBus(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(UPDATED_PRIOR_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        ObjectNode request = request(props, ctx);
        String name = requireName(request, EVENT_BUS);
        Optional<EventBusV2> prior = ctx.isUpdate()
                ? service.findEventBus(ctx.priorPhysicalId(), ctx.region()) : Optional.empty();
        EventBusV2 bus;
        if (prior.isPresent() && prior.get().getName().equals(name)) {
            r.getAttributes().put(UPDATED_PRIOR_ATTR, writeSnapshot(prior.get()));
            bus = service.replaceEventBusSettings(ctx.priorPhysicalId(), request, ctx.region());
        } else {
            bus = service.createEventBus(request, ctx.region());
        }
        publishAttributes(r, bus);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private static void publishAttributes(StackResource r, EventBusV2 bus) {
        r.setPhysicalId(bus.getArn());
        r.getAttributes().put("EventBusArn", bus.getArn());
        r.getAttributes().put("State", bus.getState());
        r.getAttributes().put("CreationTime", bus.getCreationTime().toString());
        r.getAttributes().put("LastModifiedTime", bus.getLastModifiedTime().toString());
    }

    private void provisionSubscriber(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (requiresUpdateRecovery(r)) {
            restoreDeletedPrior(r);
        }
        r.getAttributes().remove(DELETED_PRIOR_ATTR);
        r.getAttributes().remove(UPDATED_PRIOR_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        ObjectNode request = request(props, ctx);
        request.remove("ResumePosition");
        requireName(request, SUBSCRIBER);
        Optional<SubscriberV2> prior = ctx.isUpdate()
                ? service.findSubscriber(ctx.priorPhysicalId(), ctx.region()) : Optional.empty();
        SubscriberV2 subscriber;
        if (prior.isPresent() && sameIdentity(prior.get(), request)) {
            r.getAttributes().put(UPDATED_PRIOR_ATTR, writeSnapshot(prior.get()));
            subscriber = service.replaceSubscriberSettings(ctx.priorPhysicalId(), request, ctx.region());
        } else {
            if (prior.isPresent() && prior.get().getName().equals(request.path("Name").asText())) {
                r.getAttributes().put(DELETED_PRIOR_ATTR, writeSnapshot(prior.get()));
                service.deleteSubscriber(prior.get().getArn(), ctx.region());
            }
            try {
                subscriber = service.createSubscriber(request, ctx.region());
            } catch (RuntimeException createFailure) {
                if (r.getAttributes().containsKey(DELETED_PRIOR_ATTR)) {
                    try {
                        restoreDeletedPrior(r);
                    } catch (RuntimeException restoreFailure) {
                        // The snapshot stays on the failed resource, which retainsFailedUpdateState keeps for
                        // the rollback walker, so rollbackUpdate retries the restore and reports it if it fails.
                        createFailure.addSuppressed(restoreFailure);
                    }
                }
                throw createFailure;
            }
        }
        publishAttributes(r, subscriber);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private static void publishAttributes(StackResource r, SubscriberV2 subscriber) {
        r.setPhysicalId(subscriber.getArn());
        r.getAttributes().put("SubscriberArn", subscriber.getArn());
        r.getAttributes().put("BusName", EventBridgeV2Service.busName(subscriber.getEventBusArn()));
        r.getAttributes().put("CreationTime", subscriber.getCreationTime().toString());
        r.getAttributes().put("LastModifiedTime", subscriber.getLastModifiedTime().toString());
    }

    /**
     * Puts back the subscriber a same-name replacement deleted, from the snapshot taken before the delete, and points
     * the resource at it again. The snapshot is spent only once the restore succeeded, so a restore that throws is
     * reported, and retried by a later rollback, rather than claimed.
     */
    private void restoreDeletedPrior(StackResource r) {
        SubscriberV2 snapshot = readSnapshot(r, r.getAttributes().get(DELETED_PRIOR_ATTR), SubscriberV2.class);
        SubscriberV2 restored = service.restoreSubscriber(snapshot,
                AwsArnUtils.parse(snapshot.getArn()).region());
        publishAttributes(r, restored);
        r.getAttributes().remove(DELETED_PRIOR_ATTR);
    }

    /**
     * Puts back the settings an in-place update changed, from the snapshot taken before it. The snapshot is spent only
     * once the restore succeeded, so a restore that throws leaves it for the next attempt.
     */
    private void restoreUpdatedPrior(StackResource r) {
        String raw = r.getAttributes().get(UPDATED_PRIOR_ATTR);
        if (EVENT_BUS.equals(r.getResourceType())) {
            EventBusV2 snapshot = readSnapshot(r, raw, EventBusV2.class);
            publishAttributes(r, service.restoreEventBus(snapshot, AwsArnUtils.parse(snapshot.getArn()).region()));
        } else {
            SubscriberV2 snapshot = readSnapshot(r, raw, SubscriberV2.class);
            publishAttributes(r, service.restoreSubscriber(snapshot, AwsArnUtils.parse(snapshot.getArn()).region()));
        }
        r.getAttributes().remove(UPDATED_PRIOR_ATTR);
    }

    private static String writeSnapshot(Object resource) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(resource);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot snapshot EventBridge V2 resource " + resource, e);
        }
    }

    private static <T> T readSnapshot(StackResource r, String snapshot, Class<T> type) {
        try {
            return SNAPSHOT_MAPPER.readValue(snapshot, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable EventBridge V2 snapshot on " + r.getLogicalId(), e);
        }
    }

    /** Whether the template keeps every property whose change replaces the subscriber. */
    private static boolean sameIdentity(SubscriberV2 prior, JsonNode request) {
        return prior.getName().equals(request.path("Name").asText())
                && prior.getEventBusArn().equals(request.path("EventBusArn").asText())
                && Objects.equals(prior.getInvokeConfiguration().path("TargetArn"),
                        request.path("InvokeConfiguration").path("TargetArn"))
                && prior.getType().equals(request.path("Type").asText("UNORDERED"))
                && prior.getStartingPosition().equals(request.path("StartingPosition").asText("LATEST"))
                && Objects.equals(EventBridgeV2Service.normalizedPointInTime(prior.getPointInTimeConfiguration()),
                        EventBridgeV2Service.normalizedPointInTime(request.get("PointInTimeConfiguration")));
    }

    /**
     * The resolved properties as a request: tags as the map the API takes, integers given as strings as integers,
     * and ISO 8601 timestamps as the epoch seconds the API takes.
     */
    private static ObjectNode request(JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props);
        ObjectNode request = resolved != null && resolved.isObject()
                ? ((ObjectNode) resolved).deepCopy() : MAPPER.createObjectNode();
        request.remove("Tags");
        if (props != null && props.has("Tags")) {
            ObjectNode tags = request.putObject("Tags");
            ctx.resolveTags(props, "Tags").forEach(tags::put);
        }
        INTEGER_MEMBERS.forEach((structure, members) -> {
            if (request.get(structure) instanceof ObjectNode node) {
                for (String member : members) {
                    JsonNode value = node.get(member);
                    if (value != null && value.isTextual() && WHOLE_NUMBER.matcher(value.textValue()).matches()) {
                        node.set(member, IntNode.valueOf(Integer.parseInt(value.textValue())));
                    }
                }
            }
        });
        if (request.get("PointInTimeConfiguration") instanceof ObjectNode pointInTime) {
            for (String member : TIMESTAMP_MEMBERS) {
                JsonNode value = pointInTime.get(member);
                if (value != null && value.isTextual()) {
                    pointInTime.set(member, epochSeconds(member, value.textValue()));
                }
            }
        }
        return request;
    }

    private static JsonNode epochSeconds(String member, String timestamp) {
        try {
            return DecimalNode.valueOf(BigDecimal.valueOf(Instant.parse(timestamp).toEpochMilli()).movePointLeft(3));
        } catch (DateTimeParseException e) {
            throw new AwsException("ValidationError",
                    "PointInTimeConfiguration." + member + " must be an ISO 8601 timestamp, not " + timestamp + ".", 400);
        }
    }

    private static String requireName(JsonNode request, String resourceType) {
        String name = request.path("Name").asText("");
        if (name.isBlank()) {
            throw new AwsException("ValidationError", resourceType + " requires Name", 400);
        }
        return name;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        if (EVENT_BUS.equals(resourceType)) {
            CfnDeletes.safeDelete("EventBridge V2 event bus", physicalId,
                    () -> service.deleteEventBus(physicalId, region), "ResourceNotFoundException");
        } else if (SUBSCRIBER.equals(resourceType)) {
            CfnDeletes.safeDelete("EventBridge V2 subscriber", physicalId,
                    () -> service.deleteSubscriber(physicalId, region), "ResourceNotFoundException");
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    /**
     * A failed update that still owes the subscriber it deleted keeps its own state, snapshot included, rather than
     * being swapped for the pre-update copy and marked restored: the rollback walker then calls
     * {@link #rollbackUpdate}, which recreates the subscriber or reports that it could not.
     */
    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(DELETED_PRIOR_ATTR);
    }

    /** A rollback that could not recreate the deleted subscriber is retried before the next update goes ahead. */
    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return "UPDATE_FAILED".equals(resource.getStatus())
                && resource.getAttributes().containsKey(DELETED_PRIOR_ATTR);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(DELETED_PRIOR_ATTR);
        resource.getAttributes().remove(UPDATED_PRIOR_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(DELETED_PRIOR_ATTR);
        resource.getAttributes().remove(UPDATED_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Rolls back a replacement through its cleanup record and an in-place update from the snapshot taken before it.
     * A same-name replacement deleted the prior subscriber before creating its own, so once the cleanup record has
     * deleted the replacement, the prior one is recreated from its snapshot. A restore that fails throws rather than
     * reporting a rollback that never happened. With none of these on the resource, the provision failed before it
     * changed anything, so there is nothing to undo.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        ReplacementCleanup.rollback(resource, this::delete);
        if (resource.getAttributes().containsKey(DELETED_PRIOR_ATTR)) {
            restoreDeletedPrior(resource);
        } else if (resource.getAttributes().containsKey(UPDATED_PRIOR_ATTR)) {
            restoreUpdatedPrior(resource);
        }
        return true;
    }
}
