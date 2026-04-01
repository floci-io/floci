package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.*;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for SNS actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 */
@ApplicationScoped
public class SnsQueryHandler {

    private static final Logger LOG = Logger.getLogger(SnsQueryHandler.class);

    private final SnsService snsService;

    @Inject
    public SnsQueryHandler(SnsService snsService) {
        this.snsService = snsService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("SNS action: {0}", action);

        return switch (action) {
            case "CreateTopic" -> handleCreateTopic(params, region);
            case "DeleteTopic" -> handleDeleteTopic(params, region);
            case "ListTopics" -> handleListTopics(params, region);
            case "GetTopicAttributes" -> handleGetTopicAttributes(params, region);
            case "SetTopicAttributes" -> handleSetTopicAttributes(params, region);
            case "Subscribe" -> handleSubscribe(params, region);
            case "Unsubscribe" -> handleUnsubscribe(params, region);
            case "ListSubscriptions" -> handleListSubscriptions(params, region);
            case "ListSubscriptionsByTopic" -> handleListSubscriptionsByTopic(params, region);
            case "Publish" -> handlePublish(params, region);
            case "PublishBatch" -> handlePublishBatch(params, region);
            case "GetSubscriptionAttributes" -> handleGetSubscriptionAttributes(params, region);
            case "SetSubscriptionAttributes" -> handleSetSubscriptionAttributes(params, region);
            case "ConfirmSubscription" -> handleConfirmSubscription(params, region);
            case "TagResource" -> handleTagResource(params, region);
            case "UntagResource" -> handleUntagResource(params, region);
            case "ListTagsForResource" -> handleListTagsForResource(params, region);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported by SNS.", AwsNamespaces.SNS, 400);
        };
    }

    private Response handleCreateTopic(MultivaluedMap<String, String> params, String region) {
        String name = getParam(params, "Name");
        Map<String, String> attributes = extractSnsAttributes(params, "Attributes");
        Map<String, String> tags = extractSnsTags(params);
        Topic topic = snsService.createTopic(name, attributes, tags, region);

        String result = new XmlBuilder().elem("TopicArn", topic.getTopicArn()).build();
        return Response.ok(AwsQueryResponse.envelope("CreateTopic", AwsNamespaces.SNS, result)).build();
    }

    private Response handleDeleteTopic(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        try {
            snsService.deleteTopic(topicArn, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteTopic", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleListTopics(MultivaluedMap<String, String> params, String region) {
        List<Topic> topics = snsService.listTopics(region);

        var xml = new XmlBuilder().start("Topics");
        for (Topic t : topics) {
            xml.start("member").elem("TopicArn", t.getTopicArn()).end("member");
        }
        xml.end("Topics");
        return Response.ok(AwsQueryResponse.envelope("ListTopics", AwsNamespaces.SNS, xml.build())).build();
    }

    private Response handleGetTopicAttributes(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        try {
            Map<String, String> attrs = snsService.getTopicAttributes(topicArn, region);

            var xml = new XmlBuilder().start("Attributes");
            for (var entry : attrs.entrySet()) {
                xml.start("entry")
                   .elem("key", entry.getKey())
                   .elem("value", entry.getValue())
                   .end("entry");
            }
            xml.end("Attributes");
            return Response.ok(AwsQueryResponse.envelope("GetTopicAttributes", AwsNamespaces.SNS, xml.build())).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleSetTopicAttributes(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        String attributeName = getParam(params, "AttributeName");
        String attributeValue = getParam(params, "AttributeValue");
        try {
            snsService.setTopicAttributes(topicArn, attributeName, attributeValue, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("SetTopicAttributes", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleSubscribe(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        String protocol = getParam(params, "Protocol");
        String endpoint = getParam(params, "Endpoint");
        try {
            Subscription sub = snsService.subscribe(topicArn, protocol, endpoint, region);

            String result = new XmlBuilder().elem("SubscriptionArn", sub.getSubscriptionArn()).build();
            return Response.ok(AwsQueryResponse.envelope("Subscribe", AwsNamespaces.SNS, result)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleUnsubscribe(MultivaluedMap<String, String> params, String region) {
        String subscriptionArn = getParam(params, "SubscriptionArn");
        try {
            snsService.unsubscribe(subscriptionArn, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("Unsubscribe", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleListSubscriptions(MultivaluedMap<String, String> params, String region) {
        List<Subscription> subs = snsService.listSubscriptions(region);
        return buildSubscriptionListResponse("ListSubscriptions", subs);
    }

    private Response handleListSubscriptionsByTopic(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        try {
            List<Subscription> subs = snsService.listSubscriptionsByTopic(topicArn, region);
            return buildSubscriptionListResponse("ListSubscriptionsByTopic", subs);
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response buildSubscriptionListResponse(String action, List<Subscription> subs) {
        var xml = new XmlBuilder().start("Subscriptions");
        for (Subscription s : subs) {
            xml.start("member")
               .elem("TopicArn", s.getTopicArn())
               .elem("Protocol", s.getProtocol())
               .elem("SubscriptionArn", s.getSubscriptionArn())
               .elem("Owner", s.getOwner() != null ? s.getOwner() : "")
               .elem("Endpoint", s.getEndpoint() != null ? s.getEndpoint() : "")
               .end("member");
        }
        xml.end("Subscriptions");
        return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.SNS, xml.build())).build();
    }

    private Response handlePublish(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        String targetArn = getParam(params, "TargetArn");
        String phoneNumber = getParam(params, "PhoneNumber");
        String message = getParam(params, "Message");
        String subject = getParam(params, "Subject");
        String messageGroupId = getParam(params, "MessageGroupId");
        String messageDeduplicationId = getParam(params, "MessageDeduplicationId");

        Map<String, String> attributes = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("MessageAttributes.entry." + i + ".Name");
            if (name == null) break;
            String value = params.getFirst("MessageAttributes.entry." + i + ".Value.StringValue");
            if (value != null) attributes.put(name, value);
        }

        try {
            String messageId = snsService.publish(topicArn, targetArn, phoneNumber, message, subject,
                    attributes, messageGroupId, messageDeduplicationId, region);

            String result = new XmlBuilder().elem("MessageId", messageId).build();
            return Response.ok(AwsQueryResponse.envelope("Publish", AwsNamespaces.SNS, result)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handlePublishBatch(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 1; ; i++) {
            String id = getParam(params, "PublishBatchRequestEntries.member." + i + ".Id");
            if (id == null) break;
            Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("Id", id);
            entry.put("Message", getParam(params, "PublishBatchRequestEntries.member." + i + ".Message"));
            entry.put("Subject", getParam(params, "PublishBatchRequestEntries.member." + i + ".Subject"));
            entry.put("MessageGroupId", getParam(params, "PublishBatchRequestEntries.member." + i + ".MessageGroupId"));
            entry.put("MessageDeduplicationId", getParam(params, "PublishBatchRequestEntries.member." + i + ".MessageDeduplicationId"));
            entries.add(entry);
        }
        try {
            SnsService.BatchPublishResult result = snsService.publishBatch(topicArn, entries, region);

            var xml = new XmlBuilder().start("Successful");
            for (String[] s : result.successful()) {
                xml.start("member").elem("Id", s[0]).elem("MessageId", s[1]).end("member");
            }
            xml.end("Successful").start("Failed");
            for (String[] f : result.failed()) {
                xml.start("member").elem("Id", f[0]).elem("Code", f[1])
                   .elem("Message", f[2]).elem("SenderFault", f[3]).end("member");
            }
            xml.end("Failed");
            return Response.ok(AwsQueryResponse.envelope("PublishBatch", AwsNamespaces.SNS, xml.build())).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleGetSubscriptionAttributes(MultivaluedMap<String, String> params, String region) {
        String subscriptionArn = getParam(params, "SubscriptionArn");
        try {
            Map<String, String> attrs = snsService.getSubscriptionAttributes(subscriptionArn, region);
            var xml = new XmlBuilder().start("Attributes");
            for (var entry : attrs.entrySet()) {
                xml.start("entry").elem("key", entry.getKey()).elem("value", entry.getValue()).end("entry");
            }
            xml.end("Attributes");
            return Response.ok(AwsQueryResponse.envelope("GetSubscriptionAttributes", AwsNamespaces.SNS, xml.build())).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleSetSubscriptionAttributes(MultivaluedMap<String, String> params, String region) {
        String subscriptionArn = getParam(params, "SubscriptionArn");
        String attributeName = getParam(params, "AttributeName");
        String attributeValue = getParam(params, "AttributeValue");
        try {
            snsService.setSubscriptionAttribute(subscriptionArn, attributeName, attributeValue, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("SetSubscriptionAttributes", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleConfirmSubscription(MultivaluedMap<String, String> params, String region) {
        String topicArn = getParam(params, "TopicArn");
        String token = getParam(params, "Token");
        try {
            String subscriptionArn = snsService.confirmSubscription(topicArn, token, region);
            String result = new XmlBuilder().elem("SubscriptionArn", subscriptionArn).build();
            return Response.ok(AwsQueryResponse.envelope("ConfirmSubscription", AwsNamespaces.SNS, result)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleTagResource(MultivaluedMap<String, String> params, String region) {
        String resourceArn = getParam(params, "ResourceArn");
        Map<String, String> tags = extractSnsTags(params);
        try {
            snsService.tagResource(resourceArn, tags, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("TagResource", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleUntagResource(MultivaluedMap<String, String> params, String region) {
        String resourceArn = getParam(params, "ResourceArn");
        List<String> tagKeys = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "TagKeys.member." + i);
            if (key == null) break;
            tagKeys.add(key);
        }
        try {
            snsService.untagResource(resourceArn, tagKeys, region);
            return Response.ok(AwsQueryResponse.envelopeNoResult("UntagResource", AwsNamespaces.SNS)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params, String region) {
        String resourceArn = getParam(params, "ResourceArn");
        try {
            Map<String, String> tags = snsService.listTagsForResource(resourceArn, region);

            var xml = new XmlBuilder().start("Tags");
            for (var entry : tags.entrySet()) {
                xml.start("member")
                   .elem("Key", entry.getKey())
                   .elem("Value", entry.getValue())
                   .end("member");
            }
            xml.end("Tags");
            return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", AwsNamespaces.SNS, xml.build())).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus());
        }
    }

    // --- Helpers ---

    private Map<String, String> extractSnsAttributes(MultivaluedMap<String, String> params, String prefix) {
        Map<String, String> attrs = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, prefix + ".entry." + i + ".key");
            String value = getParam(params, prefix + ".entry." + i + ".value");
            if (key == null) break;
            attrs.put(key, value);
        }
        return attrs;
    }

    private Map<String, String> extractSnsTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "Tags.member." + i + ".Key");
            String value = getParam(params, "Tags.member." + i + ".Value");
            if (key == null) break;
            tags.put(key, value);
        }
        return tags;
    }

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }

    Response xmlErrorResponse(String code, String message, int status) {
        return AwsQueryResponse.error(code, message, AwsNamespaces.SNS, status);
    }
}
