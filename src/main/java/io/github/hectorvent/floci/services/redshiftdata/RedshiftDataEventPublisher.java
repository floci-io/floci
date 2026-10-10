package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
class RedshiftDataEventPublisher {
    private static final Logger LOG = Logger.getLogger(RedshiftDataEventPublisher.class);

    private final EventBridgeService events;
    private final ObjectMapper mapper;
    private final ServiceRegistry registry;

    @Inject
    RedshiftDataEventPublisher(EventBridgeService events, ObjectMapper mapper, ServiceRegistry registry) {
        this.events = events;
        this.mapper = mapper;
        this.registry = registry;
    }

    void publish(RedshiftDataStatementStore.StoredStatement statement) {
        try {
            if (!registry.isServiceEnabled("eventbridge")) {
                LOG.warnv("EventBridge disabled for statement {0}, account {1}, region {2}",
                        statement.id, statement.accountId, statement.region);
                return;
            }
            ObjectNode detail = mapper.createObjectNode();
            detail.put("statementId", statement.id);
            if (statement.statementName != null) {
                detail.put("statementName", statement.statementName);
            }
            if (statement.principal != null) {
                detail.put("principal", statement.principal);
            }
            detail.put("redshiftQueryId", (long) (statement.id.hashCode() & Integer.MAX_VALUE));
            detail.put("state", statement.status.name());
            detail.put("rows", statement.resultRows);
            detail.put("expireAt", statement.expiresAt.getEpochSecond());
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", "aws.redshift-data");
            entry.put("DetailType", "Redshift Data Statement Status Change");
            entry.put("Detail", detail.toString());
            entry.put("EventBusName", "default");
            entry.put("Resources", mapper.createArrayNode().add(statement.resourceArn));
            EventBridgeService.PutEventsResult result = events.putEvents(List.of(entry), statement.region, statement.accountId);
            if (result.failedCount() > 0) {
                LOG.warnv("EventBridge rejected event for statement {0}, account {1}, region {2}: {3}",
                        statement.id, statement.accountId, statement.region, result.entries());
            }
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Event delivery failed for statement {0}, account {1}, region {2}",
                    statement.id, statement.accountId, statement.region);
        }
    }
}
