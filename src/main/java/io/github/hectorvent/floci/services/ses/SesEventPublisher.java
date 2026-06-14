package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDestination;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes SES event-publishing notifications to the event destinations attached
 * to a {@link ConfigurationSet}. {@code SnsDestination},
 * {@code KinesisFirehoseDestination}, {@code EventBridgeDestination}, and
 * {@code CloudWatchDestination} are delivered live; Pinpoint destinations are
 * logged and skipped.
 */
@ApplicationScoped
public class SesEventPublisher {

    private static final Logger LOG = Logger.getLogger(SesEventPublisher.class);

    private static final String SES_METRIC_NAMESPACE = "AWS/SES";

    private final SnsService snsService;
    private final FirehoseService firehoseService;
    private final EventBridgeService eventBridgeService;
    private final CloudWatchMetricsService cloudWatchMetricsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SesEventPublisher(SnsService snsService, FirehoseService firehoseService,
                             EventBridgeService eventBridgeService,
                             CloudWatchMetricsService cloudWatchMetricsService,
                             ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.firehoseService = firehoseService;
        this.eventBridgeService = eventBridgeService;
        this.cloudWatchMetricsService = cloudWatchMetricsService;
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
                dispatch(ed, eventType, payloadJson, sourceArn, configurationSet.getName(),
                        emailTags, additionalHeaders, defaultRegion);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to publish SES event %s to destination %s",
                        eventType, ed.getName());
            }
        }
    }

    private void dispatch(EventDestination ed, String eventType, String payloadJson,
                          String sourceArn, String configurationSetName,
                          List<MessageTag> emailTags, List<MessageHeader> additionalHeaders,
                          String defaultRegion) {
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
        if (ed.getKinesisFirehoseDestination() != null) {
            String streamArn = ed.getKinesisFirehoseDestination().getDeliveryStreamArn();
            if (streamArn == null || streamArn.isBlank()) {
                LOG.warnf("SES Firehose destination %s: event %s not delivered (DeliveryStreamArn is missing).",
                        ed.getName(), eventType);
                return;
            }
            publishFirehose(streamArn, payloadJson, ed.getName(), eventType);
            return;
        }
        if (ed.getEventBridgeDestination() != null) {
            String busArn = ed.getEventBridgeDestination().getEventBusArn();
            if (busArn == null || busArn.isBlank()) {
                LOG.warnf("SES EventBridge destination %s: event %s not delivered (EventBusArn is missing).",
                        ed.getName(), eventType);
                return;
            }
            publishEventBridge(busArn, payloadJson, eventType, sourceArn, ed.getName(), defaultRegion);
            return;
        }
        if (ed.getCloudWatchDestination() != null) {
            publishCloudWatch(ed.getCloudWatchDestination(), eventType, configurationSetName,
                    emailTags, additionalHeaders, defaultRegion);
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

    private void publishFirehose(String streamArn, String payloadJson,
                                 String destinationName, String eventType) {
        String streamName = extractArnResourceName(streamArn);
        if (streamName == null) {
            LOG.warnf("SES Firehose destination %s: event %s not delivered "
                    + "(DeliveryStreamArn %s is malformed).",
                    destinationName, eventType, streamArn);
            return;
        }
        try {
            Record record = new Record();
            record.setData(payloadJson.getBytes(StandardCharsets.UTF_8));
            firehoseService.putRecord(streamName, record);
        } catch (Exception e) {
            LOG.warnf(e, "SES event publish to Firehose stream %s skipped", streamName);
        }
    }

    private void publishEventBridge(String busArn, String payloadJson, String eventType,
                                    String sourceArn, String destinationName, String defaultRegion) {
        String busName = extractArnResourceName(busArn);
        if (busName == null) {
            LOG.warnf("SES EventBridge destination %s: event %s not delivered "
                    + "(EventBusArn %s is malformed).",
                    destinationName, eventType, busArn);
            return;
        }
        String region = AwsArnUtils.regionOrDefault(busArn, defaultRegion);
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", "aws.ses");
            entry.put("DetailType", SesEventPayload.eventBridgeDetailType(eventType));
            entry.put("Detail", payloadJson);
            entry.put("EventBusName", busName);
            entry.put("Region", region);
            // Always emit Resources (possibly empty) — EventBridgeService.matchesPattern
            // casts entry.get("Resources") to ArrayNode unconditionally for any rule with
            // a "resources" filter; an absent key NPEs and silently turns the rule into
            // a no-match.
            ArrayNode resources = objectMapper.createArrayNode();
            if (sourceArn != null && !sourceArn.isBlank()) {
                resources.add(sourceArn);
            }
            entry.put("Resources", resources);
            EventBridgeService.PutEventsResult result =
                    eventBridgeService.putEvents(List.of(entry), region);
            if (result.failedCount() > 0) {
                LOG.warnf("SES event publish to EventBridge bus %s skipped: %s",
                        busName, result.entries());
            }
        } catch (Exception e) {
            LOG.warnf(e, "SES event publish to EventBridge bus %s skipped", busName);
        }
    }

    private void publishCloudWatch(CloudWatchDestination dest, String eventType,
                                   String configurationSetName,
                                   List<MessageTag> emailTags,
                                   List<MessageHeader> additionalHeaders,
                                   String defaultRegion) {
        List<Dimension> dimensions = new ArrayList<>();
        if (dest.getDimensionConfigurations() != null) {
            for (CloudWatchDimensionConfiguration cfg : dest.getDimensionConfigurations()) {
                if (cfg == null || cfg.getDimensionName() == null
                        || cfg.getDimensionName().isBlank()) {
                    continue;
                }
                String value = resolveCloudWatchDimensionValue(cfg, configurationSetName,
                        emailTags, additionalHeaders);
                if (value == null || value.isBlank()) {
                    continue;
                }
                dimensions.add(new Dimension(cfg.getDimensionName(), value));
            }
        }
        MetricDatum datum = new MetricDatum();
        datum.setNamespace(SES_METRIC_NAMESPACE);
        datum.setMetricName(SesEventPayload.cloudWatchMetricName(eventType));
        datum.setValue(1.0);
        datum.setUnit("Count");
        datum.setDimensions(dimensions);
        // Leave timestamp unset: CloudWatchMetricsService.putMetricData defaults a zero
        // timestamp to Instant.now().getEpochSecond() (epoch seconds, not milliseconds).
        try {
            cloudWatchMetricsService.putMetricData(SES_METRIC_NAMESPACE, List.of(datum),
                    defaultRegion);
        } catch (Exception e) {
            LOG.warnf(e, "SES event publish to CloudWatch (event %s) skipped", eventType);
        }
    }

    /**
     * Resolves the value of a CloudWatch dimension for a given SES event according to
     * {@code DimensionValueSource}. {@code messageTag} consults the user-supplied
     * {@code emailTags} plus the auto-added {@code ses:configuration-set} tag;
     * {@code emailHeader} consults the email's additional headers (case-insensitive
     * lookup per RFC 5322). When the source can't yield a value, falls back to
     * {@code DefaultDimensionValue}. {@code linkTag} and unknown sources are not
     * resolved against any real input here and immediately fall back to the default.
     *
     * <p>The V1 SES API documents these enum values as camelCase
     * ({@code messageTag} / {@code emailHeader} / {@code linkTag}); the V2 SES API
     * documents the same values as SCREAMING_SNAKE
     * ({@code MESSAGE_TAG} / {@code EMAIL_HEADER} / {@code LINK_TAG}) and the AWS SDK
     * for Java v2 marshals exactly those constant names on the wire. Both forms are
     * accepted here so callers on either protocol land on the same resolution path.
     */
    private static String resolveCloudWatchDimensionValue(CloudWatchDimensionConfiguration cfg,
                                                          String configurationSetName,
                                                          List<MessageTag> emailTags,
                                                          List<MessageHeader> additionalHeaders) {
        String source = cfg.getDimensionValueSource();
        String name = cfg.getDimensionName();
        String defaultValue = cfg.getDefaultDimensionValue();
        if (isMessageTagSource(source)) {
            if ("ses:configuration-set".equals(name)) {
                return configurationSetName;
            }
            if (emailTags != null) {
                for (MessageTag t : emailTags) {
                    if (t != null && name.equals(t.name())
                            && t.value() != null && !t.value().isBlank()) {
                        return t.value();
                    }
                }
            }
            return defaultValue;
        }
        if (isEmailHeaderSource(source)) {
            if (additionalHeaders != null) {
                for (MessageHeader h : additionalHeaders) {
                    if (h != null && name.equalsIgnoreCase(h.name())
                            && h.value() != null && !h.value().isBlank()) {
                        return h.value();
                    }
                }
            }
            return defaultValue;
        }
        return defaultValue;
    }

    private static boolean isMessageTagSource(String source) {
        return "messageTag".equalsIgnoreCase(source) || "MESSAGE_TAG".equalsIgnoreCase(source);
    }

    private static boolean isEmailHeaderSource(String source) {
        return "emailHeader".equalsIgnoreCase(source) || "EMAIL_HEADER".equalsIgnoreCase(source);
    }

    /**
     * Extracts the resource name from an AWS ARN's resource segment that follows the
     * {@code <type>/<name>} convention (e.g., {@code deliverystream/my-stream},
     * {@code event-bus/my-bus}). Returns {@code null} for a malformed ARN or a
     * resource segment with no slash or an empty name after the slash.
     */
    private static String extractArnResourceName(String arn) {
        try {
            String resource = AwsArnUtils.parse(arn).resource();
            int slash = resource.indexOf('/');
            if (slash < 0 || slash == resource.length() - 1) {
                return null;
            }
            return resource.substring(slash + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
