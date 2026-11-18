package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.appsync.AppSyncService;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.AppSyncAuth;
import io.floci.aws.services.appsync.model.ChannelNamespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes a batch of events to a channel, for the HTTP endpoint and the WebSocket {@code publish}
 * message alike: checks the channel and its namespace, authorizes the publish, checks the events
 * and hands them to the {@link EventsChannelBroker}.
 *
 * <p>A batch holds one to five events, each a string that is itself JSON. Every accepted event gets
 * an identifier and its index in the batch; with no publish handler none is ever marked failed.
 */
@ApplicationScoped
public class EventsPublisher {

    private static final Logger LOG = Logger.getLogger(EventsPublisher.class);
    static final int MAX_EVENTS = 5;

    /** The {@code successful} and {@code failed} lists AppSync answers a publish with. */
    public record Result(List<Map<String, Object>> successful, List<Map<String, Object>> failed) {
    }

    private final AppSyncService appSyncService;
    private final EventsAuthorizer authorizer;
    private final EventsChannelBroker broker;
    private final ObjectMapper objectMapper;

    @Inject
    public EventsPublisher(AppSyncService appSyncService, EventsAuthorizer authorizer, EventsChannelBroker broker,
                           ObjectMapper objectMapper) {
        this.appSyncService = appSyncService;
        this.authorizer = authorizer;
        this.broker = broker;
        this.objectMapper = objectMapper;
    }

    public Result publish(AppSyncService.EventApiOwner owner, Object channelValue, Object eventsValue,
                          Map<String, String> credentials, EventsAuthorizer.SignedHttpRequest signed) {
        EventsChannel channel = channel(channelValue);
        ChannelNamespace namespace = namespace(owner, channel);
        authorizer.authorize(owner, EventsOperation.EVENT_PUBLISH, namespace, channel.path(), credentials, signed);
        List<String> events = events(eventsValue);

        broker.publish(owner.accountId(), owner.api().getApiId(), channel, events);
        List<Map<String, Object>> successful = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("identifier", UUID.randomUUID().toString());
            entry.put("index", i);
            successful.add(entry);
        }
        LOG.debugv("Published {0} event(s) to {1} on Events API {2}", events.size(), channel.path(),
                owner.api().getApiId());
        return new Result(successful, List.of());
    }

    /** The namespace a channel's first segment names, which must exist on the API. */
    ChannelNamespace namespace(AppSyncService.EventApiOwner owner, EventsChannel channel) {
        return appSyncService.findChannelNamespace(owner.accountId(), owner.api().getApiId(), channel.namespace())
                .orElseThrow(() -> AppSyncAuth.badRequest("Channel namespace not found: " + channel.namespace()));
    }

    static EventsChannel channel(Object value) {
        if (!(value instanceof String path)) {
            throw AppSyncAuth.badRequest("A channel is required");
        }
        try {
            return EventsChannel.forPublish(path);
        } catch (IllegalArgumentException e) {
            throw AppSyncAuth.badRequest(e.getMessage());
        }
    }

    List<String> events(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_EVENTS) {
            throw AppSyncAuth.badRequest("A publish carries 1 to " + MAX_EVENTS + " events");
        }
        List<String> events = new ArrayList<>();
        for (Object event : list) {
            if (!(event instanceof String json)) {
                throw AppSyncAuth.badRequest("Each event must be a string holding JSON");
            }
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(json);
            } catch (IOException e) {
                throw new AppSyncTransportException(400, "BadRequestException", "Each event must be valid JSON");
            }
            if (parsed == null || parsed.isMissingNode()) {
                throw AppSyncAuth.badRequest("Each event must be valid JSON");
            }
            events.add(json);
        }
        return events;
    }
}
