package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the {@code aws.ssm} Parameter Store Change events AWS sends to the default EventBridge bus
 * after a successful parameter mutation. Best effort: a publishing failure is logged and never undoes
 * the write.
 */
@ApplicationScoped
public class SsmEventPublisher {

    private static final Logger LOG = Logger.getLogger(SsmEventPublisher.class);
    private static final String SOURCE = "aws.ssm";
    private static final String DETAIL_TYPE = "Parameter Store Change";

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;

    /** A null {@code eventBridgeService} publishes nothing, for SsmService's test constructors. */
    @Inject
    public SsmEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    /** {@code operation} is Create, Update or Delete. */
    public void parameterChanged(String operation, Parameter parameter, String region) {
        publish(parameter.getArn(),
                detail(operation, parameter.getName(), parameter.getType(), parameter.getDescription()), region);
    }

    /** {@code fromVersion} is empty when the label was not on any version before. */
    public void labelChanged(String arn, ParameterHistory labelled, String label, String fromVersion, String region) {
        ObjectNode detail = detail("LabelParameterVersion", labelled.getName(), labelled.getType(),
                labelled.getDescription());
        detail.put("label", label);
        detail.put("fromVersion", fromVersion);
        detail.put("toVersion", String.valueOf(labelled.getVersion()));
        publish(arn, detail, region);
    }

    private ObjectNode detail(String operation, String name, String type, String description) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("operation", operation);
        detail.put("name", name);
        detail.put("type", type);
        if (description != null) {
            detail.put("description", description);
        }
        return detail;
    }

    private void publish(String arn, ObjectNode detail, String region) {
        if (eventBridgeService == null) {
            return;
        }
        try {
            ArrayNode resources = objectMapper.createArrayNode().add(arn);
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", SOURCE);
            entry.put("DetailType", DETAIL_TYPE);
            entry.put("Detail", objectMapper.writeValueAsString(detail));
            entry.put("Resources", resources);
            eventBridgeService.putEvents(List.of(entry), region);
        } catch (Exception e) {
            LOG.warnv("Failed to publish Parameter Store Change for {0}: {1}", arn, e.getMessage());
        }
    }
}
