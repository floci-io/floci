package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emits a minimal CloudTrail-compatible API event for successful Redshift writes. This simulates
 * the EventBridge route without claiming to capture a full CloudTrail audit record or to implement
 * the separately documented direct integration-event schema.
 */
@ApplicationScoped
public class RedshiftApiEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedshiftApiEventPublisher.class);

    private final EventBridgeService events;
    private final ObjectMapper mapper;
    private final ServiceRegistry registry;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftApiEventPublisher(EventBridgeService events, ObjectMapper mapper,
                                     ServiceRegistry registry, RegionResolver regionResolver) {
        this.events = events;
        this.mapper = mapper;
        this.registry = registry;
        this.regionResolver = regionResolver;
    }

    public void publish(String action) {
        if (action.startsWith("Describe") || action.startsWith("List") || action.startsWith("Get")) {
            return;
        }
        try {
            if (!registry.isServiceEnabled("events")) {
                return;
            }
            String region = regionResolver.getRegion();
            String accountId = regionResolver.getAccountId();
            ObjectNode detail = mapper.createObjectNode();
            detail.put("eventVersion", "1.11");
            detail.put("eventSource", ServicePrincipals.of("redshift"));
            detail.put("eventName", action);
            detail.put("awsRegion", region);
            detail.put("recipientAccountId", accountId);
            detail.put("eventTime", Instant.now().toString());
            detail.put("eventID", UUID.randomUUID().toString());
            detail.put("eventType", "AwsApiCall");
            detail.put("managementEvent", true);
            detail.put("readOnly", false);
            Map<String, Object> entry = EventBridgeService.serviceEventEntry("aws.redshift",
                    "AWS API Call via CloudTrail", detail.toString(), null);
            EventBridgeService.PutEventsResult result = events.putEvents(List.of(entry), region, accountId);
            if (result.failedCount() > 0) {
                LOG.warnv("EventBridge rejected Redshift API event {0}, account {1}, region {2}: {3}",
                        action, accountId, region, result.entries());
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not publish Redshift API event {0}", action);
        }
    }
}
