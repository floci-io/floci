package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Publishes SES event-publishing notifications to the event destinations attached
 * to a {@link ConfigurationSet}. This MVP only delivers to {@code SnsDestination};
 * Firehose, EventBridge, CloudWatch, and Pinpoint destinations are logged and skipped.
 */
@ApplicationScoped
public class SesEventPublisher {

    private static final Logger LOG = Logger.getLogger(SesEventPublisher.class);

    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SesEventPublisher(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    public void publish(ConfigurationSet configurationSet, String eventType, String messageId,
                        String source, String sourceArn, String sendingAccountId,
                        String subject, List<String> toAddresses, List<String> ccAddresses,
                        List<String> bccAddresses, List<String> envelopeDestinations,
                        List<String> suppressionBounceRecipients,
                        List<String> suppressionComplaintRecipients,
                        List<MessageTag> emailTags, List<MessageHeader> additionalHeaders,
                        Instant timestamp, String defaultRegion) {
        if (configurationSet == null || configurationSet.getEventDestinations().isEmpty()) {
            return;
        }
        ObjectNode payload = SesEventPayload.build(objectMapper, eventType, messageId, source,
                sourceArn, sendingAccountId, subject,
                toAddresses, ccAddresses, bccAddresses, envelopeDestinations,
                suppressionBounceRecipients, suppressionComplaintRecipients,
                configurationSet.getName(), emailTags, additionalHeaders, timestamp);
        String payloadJson = payload.toString();
        for (EventDestination ed : configurationSet.getEventDestinations()) {
            if (!ed.isEnabled()) {
                continue;
            }
            if (ed.getMatchingEventTypes() == null
                    || !ed.getMatchingEventTypes().contains(eventType)) {
                continue;
            }
            try {
                dispatch(ed, eventType, payloadJson, defaultRegion);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to publish SES event %s to destination %s",
                        eventType, ed.getName());
            }
        }
    }

    private void dispatch(EventDestination ed, String eventType, String payloadJson, String defaultRegion) {
        if (ed.getSnsDestination() != null) {
            String topicArn = ed.getSnsDestination().getTopicArn();
            if (topicArn == null || topicArn.isBlank()) {
                LOG.warnf("SES SNS destination %s: event %s not delivered (TopicArn is missing).",
                        ed.getName(), eventType);
                return;
            }
            publishSns(topicArn, payloadJson, defaultRegion);
            return;
        }
        if (ed.getPinpointDestination() != null) {
            LOG.warnf("SES Pinpoint destination %s: event %s not delivered (Pinpoint service not implemented in Floci).",
                    ed.getName(), eventType);
            return;
        }
        if (ed.getKinesisFirehoseDestination() != null
                || ed.getEventBridgeDestination() != null
                || ed.getCloudWatchDestination() != null) {
            LOG.warnf("SES destination %s: event %s not delivered (only SNS publishing is implemented).",
                    ed.getName(), eventType);
            return;
        }
        LOG.warnf("SES destination %s: event %s not delivered (no destination target configured).",
                ed.getName(), eventType);
    }

    private void publishSns(String topicArn, String payload, String defaultRegion) {
        String region = AwsArnUtils.regionOrDefault(topicArn, defaultRegion);
        try {
            snsService.publish(topicArn, null, payload, null, region);
        } catch (AwsException e) {
            LOG.warnf(e, "SES event publish to SNS topic %s skipped", topicArn);
        }
    }
}
