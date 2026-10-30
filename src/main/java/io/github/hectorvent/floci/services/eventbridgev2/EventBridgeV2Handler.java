package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.eventbridgev2.model.EventBusV2;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * EventBridge V2 handler, dispatched by {@code AwsJsonCborController} for Smithy RPC v2 CBOR requests to
 * {@code /service/AWSEventsV2/operation/<Operation>}.
 */
@ApplicationScoped
public class EventBridgeV2Handler {

    /**
     * The response members the model types as timestamps, which the CBOR encoder tags as such: their names do not
     * end in {@code Timestamp}, the convention it otherwise goes by.
     */
    public static final Set<String> TIMESTAMP_MEMBERS = Set.of("CreationTime", "LastModifiedTime",
            "RetentionWindowStartTime", "StartingPoint", "EndPoint");

    private final EventBridgeV2Service service;
    private final ObjectMapper objectMapper;

    @Inject
    public EventBridgeV2Handler(EventBridgeV2Service service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String operation, JsonNode request, String region) {
        return switch (operation) {
            case "CreateEventBus" -> handleCreateEventBus(request, region);
            case "DescribeEventBus" -> handleDescribeEventBus(request, region);
            case "DeleteEventBus" -> handleDeleteEventBus(request, region);
            case "ListEventBuses" -> handleListEventBuses(request, region);
            case "CreateSubscriber" -> handleCreateSubscriber(request, region);
            case "DescribeSubscriber" -> handleDescribeSubscriber(request, region);
            case "ListSubscribers" -> handleListSubscribers(request, region);
            case "UpdateSubscriber" -> handleUpdateSubscriber(request, region);
            case "DeleteSubscriber" -> handleDeleteSubscriber(request, region);
            case "PutEvents" -> handlePutEvents(request, region);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + operation + " is not supported.", 400);
        };
    }

    private Response handleCreateEventBus(JsonNode request, String region) {
        return Response.ok(eventBus(service.createEventBus(request, region))).build();
    }

    private Response handleDescribeEventBus(JsonNode request, String region) {
        EventBusV2 bus = service.describeEventBus(request, region);
        ObjectNode response = eventBus(bus);
        response.put("LastModifiedTime", epochSeconds(bus.getLastModifiedTime()));
        return Response.ok(response).build();
    }

    /** The members {@code CreateEventBus} and {@code DescribeEventBus} both answer with. */
    private ObjectNode eventBus(EventBusV2 bus) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EventBusArn", bus.getArn());
        response.put("Name", bus.getName());
        if (bus.getDescription() != null) {
            response.put("Description", bus.getDescription());
        }
        if (bus.getKmsKeyIdentifier() != null) {
            response.putObject("EncryptionConfiguration").put("KmsKeyIdentifier", bus.getKmsKeyIdentifier());
        }
        if (bus.getRetentionPeriodInDays() != null) {
            response.putObject("StorageConfiguration").put("RetentionPeriodInDays", bus.getRetentionPeriodInDays());
        }
        response.put("State", bus.getState());
        response.put("CreationTime", epochSeconds(bus.getCreationTime()));
        return response;
    }

    private Response handleDeleteEventBus(JsonNode request, String region) {
        service.deleteEventBus(request, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListEventBuses(JsonNode request, String region) {
        PaginatedResult<EventBusV2> page = service.listEventBuses(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode buses = response.putArray("EventBuses");
        for (EventBusV2 bus : page.items()) {
            ObjectNode summary = buses.addObject();
            summary.put("Name", bus.getName());
            summary.put("EventBusArn", bus.getArn());
            if (bus.getDescription() != null) {
                summary.put("Description", bus.getDescription());
            }
            summary.put("CreationTime", epochSeconds(bus.getCreationTime()));
            summary.put("LastModifiedTime", epochSeconds(bus.getLastModifiedTime()));
            summary.put("State", bus.getState());
            summary.put("EventBusAccountId", AwsArnUtils.parse(bus.getArn()).accountId());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleCreateSubscriber(JsonNode request, String region) {
        SubscriberV2 subscriber = service.createSubscriber(request, region);
        ObjectNode response = subscriberIdentity(subscriber);
        response.put("CreationTime", epochSeconds(subscriber.getCreationTime()));
        return Response.ok(response).build();
    }

    private Response handleUpdateSubscriber(JsonNode request, String region) {
        SubscriberV2 subscriber = service.updateSubscriber(request, region);
        ObjectNode response = subscriberIdentity(subscriber);
        response.put("LastModifiedTime", epochSeconds(subscriber.getLastModifiedTime()));
        return Response.ok(response).build();
    }

    private Response handleDescribeSubscriber(JsonNode request, String region) {
        SubscriberV2 subscriber = service.describeSubscriber(request, region);
        ObjectNode response = subscriberIdentity(subscriber);
        response.set("InvokeConfiguration", subscriber.getInvokeConfiguration());
        if (subscriber.getDescription() != null) {
            response.put("Description", subscriber.getDescription());
        }
        putIfSet(response, "FilterConfiguration", subscriber.getFilterConfiguration());
        putIfSet(response, "BatchConfiguration", subscriber.getBatchConfiguration());
        putIfSet(response, "Transformer", subscriber.getTransformer());
        putIfSet(response, "RetryPolicy", subscriber.getRetryPolicy());
        putIfSet(response, "OnFailureConfiguration", subscriber.getOnFailureConfiguration());
        putIfSet(response, "LogConfiguration", subscriber.getLogConfiguration());
        response.put("Revoked", false);
        response.put("CreationTime", epochSeconds(subscriber.getCreationTime()));
        response.put("LastModifiedTime", epochSeconds(subscriber.getLastModifiedTime()));
        return Response.ok(response).build();
    }

    private Response handleListSubscribers(JsonNode request, String region) {
        PaginatedResult<SubscriberV2> page = service.listSubscribers(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode subscribers = response.putArray("Subscribers");
        for (SubscriberV2 subscriber : page.items()) {
            ObjectNode summary = subscribers.addObject();
            summary.put("SubscriberArn", subscriber.getArn());
            summary.put("Name", subscriber.getName());
            summary.put("EventBusArn", subscriber.getEventBusArn());
            summary.put("TargetArn", subscriber.getInvokeConfiguration().path("TargetArn").asText());
            summary.put("Type", subscriber.getType());
            summary.put("Revoked", false);
            summary.put("State", subscriber.getState());
            summary.put("CreationTime", epochSeconds(subscriber.getCreationTime()));
            summary.put("LastModifiedTime", epochSeconds(subscriber.getLastModifiedTime()));
            summary.put("SubscriberAccountId", AwsArnUtils.parse(subscriber.getArn()).accountId());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    /** The members {@code CreateSubscriber}, {@code UpdateSubscriber} and {@code DescribeSubscriber} all answer with. */
    private ObjectNode subscriberIdentity(SubscriberV2 subscriber) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriberArn", subscriber.getArn());
        response.put("Name", subscriber.getName());
        response.put("EventBusArn", subscriber.getEventBusArn());
        response.put("Type", subscriber.getType());
        response.put("StartingPosition", subscriber.getStartingPosition());
        putIfSet(response, "PointInTimeConfiguration", subscriber.getPointInTimeConfiguration());
        response.put("State", subscriber.getState());
        return response;
    }

    private static void putIfSet(ObjectNode response, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            response.set(field, value);
        }
    }

    private Response handleDeleteSubscriber(JsonNode request, String region) {
        service.deleteSubscriber(request, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutEvents(JsonNode request, String region) {
        List<EventBridgeV2Service.PutEventsResult> results = service.putEvents(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        int failed = 0;
        for (EventBridgeV2Service.PutEventsResult result : results) {
            ObjectNode entry = entries.addObject();
            if (result.errorCode() != null) {
                failed++;
                entry.put("ErrorCode", result.errorCode());
                entry.put("ErrorMessage", result.errorMessage());
            } else {
                entry.put("EventId", result.eventId());
                entry.put("SequenceNumber", result.sequenceNumber());
                entry.put("SuccessCode", "PUBLISHED");
            }
        }
        response.put("FailedEntryCount", failed);
        return Response.ok(response).build();
    }

    private static BigDecimal epochSeconds(Instant instant) {
        return BigDecimal.valueOf(instant.toEpochMilli()).movePointLeft(3);
    }
}
