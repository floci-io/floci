package io.floci.aws.services.rds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native lifecycle notifications on the resource account and Region's default EventBridge bus. */
@ApplicationScoped
public class RdsEventPublisher {

    private static final Logger LOG = Logger.getLogger(RdsEventPublisher.class);

    private record Event(String detailType, String detail, String arn, String accountId, String region) {}

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final Queue<Event> queued = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean publishing = new AtomicBoolean();

    @Inject
    public RdsEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    /** Snapshot the completed transition while RDS still holds the resource. */
    void queue(String arn, String identifier, boolean cluster, boolean started) {
        AwsArnUtils.Arn resource = AwsArnUtils.parse(arn);
        ObjectNode detail = objectMapper.createObjectNode();
        detail.putArray("EventCategories").add("notification");
        detail.put("SourceType", cluster ? "DB_CLUSTER" : "DB_INSTANCE");
        detail.put("SourceArn", arn);
        detail.put("SourceIdentifier", identifier);
        detail.put("Date", Instant.now().toString());
        detail.put("Message", "DB " + (cluster ? "cluster" : "instance") + (started ? " started." : " stopped."));
        detail.put("EventID", cluster ? (started ? "RDS-EVENT-0151" : "RDS-EVENT-0150")
                : (started ? "RDS-EVENT-0088" : "RDS-EVENT-0087"));
        queued.add(new Event(cluster ? "RDS DB Cluster Event" : "RDS DB Instance Event",
                detail.toString(), arn, resource.accountId(), resource.region()));
    }

    /**
     * Called after releasing RDS locks, since a rule's target can call RDS again. A reentrant or
     * concurrent call leaves its events for the active publisher, preserving transition order.
     */
    void publishQueued() {
        while (!queued.isEmpty() && publishing.compareAndSet(false, true)) {
            try {
                Event event;
                while ((event = queued.poll()) != null) {
                    publish(event);
                }
            } finally {
                publishing.set(false);
            }
        }
    }

    private void publish(Event event) {
        try {
            RequestScopes.runAs(event.accountId(), event.region(), () -> eventBridgeService.publishServiceEvent(
                    "aws.rds", event.detailType(), event.detail(), event.arn(), event.region()));
        } catch (RuntimeException e) {
            // Event delivery is best effort and must not turn a completed database operation into a fault.
            LOG.warnv(e, "Could not publish RDS lifecycle event for {0}", event.arn());
        }
    }
}
