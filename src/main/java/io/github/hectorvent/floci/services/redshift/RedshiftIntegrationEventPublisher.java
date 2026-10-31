package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code aws.redshift} events AWS sends to the default EventBridge bus as a zero-ETL integration
 * changes state. The detail types, event IDs, severities and messages come from the Redshift
 * Management Guide's "Zero-ETL integration event notifications" table; only the events Floci's
 * DynamoDB integration can raise are implemented. AWS publishes no JSON example, so the field names of
 * the detail are Floci's own representation of the table's columns.
 * {@link RedshiftService} queues an event while it holds its monitor and publishes the queue once it
 * does not, so a rule's target that calls Redshift never waits on the call that made the change, and
 * events go out in the order their changes were made.
 */
@ApplicationScoped
class RedshiftIntegrationEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedshiftIntegrationEventPublisher.class);
    static final String SOURCE = "aws.redshift";

    /** The implemented rows of the AWS table: category doubles as the detail type's suffix. */
    enum IntegrationEvent {
        CREATED_ACTIVE("0000", "Monitoring", "INFO",
                "Your zero-ETL Integration %s has been created and became ACTIVE at %s."),
        DELETED("0001", "Monitoring", "INFO", "Your zero-ETL Integration %s has been deleted at %s."),
        DELETION_INITIATED("0002", "Monitoring", "INFO",
                "Your zero-ETL Integration %s has initiated deletion at %s."),
        SYNCHRONIZING("0003", "Monitoring", "INFO",
                "Your zero-ETL Integration %s is synchronizing transactional data to the Amazon Redshift data warehouse."),
        FAILED("0007", "Monitoring", "ERROR",
                "Unable to load data due to an internal error. Delete and recreate the integration. "
                        + "If the error persists, contact AWS Support."),
        ACTIVE("0013", "Monitoring", "INFO", "Your zero-ETL Integration %s is now ACTIVE.");

        private final String number;
        private final String category;
        private final String severity;
        private final String message;

        IntegrationEvent(String number, String category, String severity, String message) {
            this.number = number;
            this.category = category;
            this.severity = severity;
            this.message = message;
        }

        String eventId() {
            return "REDSHIFT-INTEGRATION-EVENT-" + number;
        }

        String detailType() {
            return "Redshift Integration " + category;
        }

        String render(String integrationName, String time) {
            return switch (this) {
                case CREATED_ACTIVE, DELETED, DELETION_INITIATED -> message.formatted(integrationName, time);
                case SYNCHRONIZING, ACTIVE -> message.formatted(integrationName);
                case FAILED -> message;
            };
        }
    }

    private record Queued(String detailType, String detail, String resourceArn, String region, String accountId) { }

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final ServiceRegistry registry;
    private final Queue<Queued> queued = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean publishing = new AtomicBoolean();

    @Inject
    RedshiftIntegrationEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper,
                                      RegionResolver regionResolver, ServiceRegistry registry) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.registry = registry;
    }

    /** Queues {@code event} for {@code integration}'s Region, in the calling account. */
    void queue(IntegrationEvent event, Integration integration, String region, Instant at) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("eventId", event.eventId());
        detail.put("severity", event.severity);
        detail.put("category", event.category);
        detail.put("message", event.render(integration.getIntegrationName(), RedshiftService.eventTime(at)));
        detail.put("integrationArn", integration.getIntegrationArn());
        queued.add(new Queued(event.detailType(), detail.toString(), integration.getIntegrationArn(), region,
                accountId(integration)));
    }

    // The consumer thread has no request account, so the integration's own account wins.
    private String accountId(Integration integration) {
        return integration.getAccountId() != null ? integration.getAccountId() : regionResolver.getAccountId();
    }

    /**
     * Publishes the queued events in order. One call publishes at a time: a call made while an event
     * is delivered leaves its events queued behind the rest and returns, and the call already
     * publishing sends them after.
     */
    void publishQueued() {
        while (!queued.isEmpty() && publishing.compareAndSet(false, true)) {
            try {
                Queued next;
                while ((next = queued.poll()) != null) {
                    publish(next);
                }
            } finally {
                publishing.set(false);
            }
        }
    }

    private void publish(Queued event) {
        if (!registry.isServiceEnabled("events")) {
            LOG.debugv("EventBridge is disabled; dropping the {0} event for {1}", event.detailType(),
                    event.resourceArn());
            return;
        }
        RequestScopes.runAs(event.accountId(), event.region(), () -> eventBridgeService.publishServiceEvent(
                SOURCE, event.detailType(), event.detail(), event.resourceArn(), event.region()));
    }
}
