package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SNS JSON protocol handler (application/x-amz-json-1.0).
 * Called by AwsJsonController for SNS_20100331.* targeted requests.
 */
@ApplicationScoped
public class SnsJsonHandler {

    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SnsJsonHandler(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateTopic" -> handleCreateTopic(request, region);
            case "DeleteTopic" -> handleDeleteTopic(request, region);
            case "ListTopics" -> handleListTopics(request, region);
            case "GetTopicAttributes" -> handleGetTopicAttributes(request, region);
            case "SetTopicAttributes" -> handleSetTopicAttributes(request, region);
            case "Subscribe" -> handleSubscribe(request, region);
            case "Unsubscribe" -> handleUnsubscribe(request, region);
            case "ListSubscriptions" -> handleListSubscriptions(request, region);
            case "ListSubscriptionsByTopic" -> handleListSubscriptionsByTopic(request, region);
            case "Publish" -> handlePublish(request, region);
            case "PublishBatch" -> handlePublishBatch(request, region);
            case "GetSubscriptionAttributes" -> handleGetSubscriptionAttributes(request, region);
            case "SetSubscriptionAttributes" -> handleSetSubscriptionAttributes(request, region);
            case "ConfirmSubscription" -> handleConfirmSubscription(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateTopic(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                tags.put(tag.path("Key").asText(), tag.path("Value").asText());
            }
        }
        Topic topic = snsService.createTopic(name, attributes, tags, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TopicArn", topic.getTopicArn());
        return Response.ok(response).build();
    }

    private Response handleDeleteTopic(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        snsService.deleteTopic(topicArn, region);
        return Response.ok().build();
    }

    private Response handleListTopics(JsonNode request, String region) {
        List<Topic> topics = snsService.listTopics(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode topicsArray = response.putArray("Topics");
        for (Topic t : topics) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("TopicArn", t.getTopicArn());
            topicsArray.add(entry);
        }
        return Response.ok(response).build();
    }

    private Response handleGetTopicAttributes(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        Map<String, String> attrs = snsService.getTopicAttributes(topicArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrsNode = response.putObject("Attributes");
        for (var entry : attrs.entrySet()) {
            attrsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleSetTopicAttributes(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        String attributeName = request.path("AttributeName").asText(null);
        String attributeValue = request.path("AttributeValue").asText(null);
        snsService.setTopicAttributes(topicArn, attributeName, attributeValue, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSubscribe(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        String protocol = request.path("Protocol").asText(null);
        String endpoint = request.path("Endpoint").asText(null);
        Subscription sub = snsService.subscribe(topicArn, protocol, endpoint, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriptionArn", sub.getSubscriptionArn());
        return Response.ok(response).build();
    }

    private Response handleUnsubscribe(JsonNode request, String region) {
        String subscriptionArn = request.path("SubscriptionArn").asText(null);
        snsService.unsubscribe(subscriptionArn, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListSubscriptions(JsonNode request, String region) {
        List<Subscription> subs = snsService.listSubscriptions(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Subscriptions");
        for (Subscription s : subs) {
            items.add(subscriptionToNode(s));
        }
        return Response.ok(response).build();
    }

    private Response handleListSubscriptionsByTopic(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        List<Subscription> subs = snsService.listSubscriptionsByTopic(topicArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode subsArray = response.putArray("Subscriptions");
        for (Subscription s : subs) {
            subsArray.add(subscriptionToNode(s));
        }
        return Response.ok(response).build();
    }

    private Response handlePublish(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        String targetArn = request.path("TargetArn").asText(null);
        String phoneNumber = request.path("PhoneNumber").asText(null);
        String message = request.path("Message").asText(null);
        String subject = request.path("Subject").asText(null);

        Map<String, String> attributes = new HashMap<>();
        JsonNode attrsNode = request.path("MessageAttributes");
        if (attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry -> {
                attributes.put(entry.getKey(), entry.getValue().path("StringValue").asText());
            });
        }

        String messageId = snsService.publish(topicArn, targetArn, phoneNumber, message, subject, attributes, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MessageId", messageId);
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                tags.put(tag.path("Key").asText(), tag.path("Value").asText());
            }
        }
        snsService.tagResource(resourceArn, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        List<String> tagKeys = new ArrayList<>();
        JsonNode keysNode = request.path("TagKeys");
        if (keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                tagKeys.add(key.asText());
            }
        }
        snsService.untagResource(resourceArn, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText(null);
        Map<String, String> tags = snsService.listTagsForResource(resourceArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = response.putArray("Tags");
        for (var entry : tags.entrySet()) {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
            tagsArray.add(tag);
        }
        return Response.ok(response).build();
    }

    private Response handlePublishBatch(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        List<Map<String, Object>> entries = new ArrayList<>();

        JsonNode entriesNode = request.path("PublishBatchRequestEntries");
        if (entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("Id", entryNode.path("Id").asText(null));
                entry.put("Message", entryNode.path("Message").asText(null));
                entry.put("Subject", entryNode.path("Subject").asText(null));
                entry.put("MessageGroupId", entryNode.path("MessageGroupId").asText(null));
                entry.put("MessageDeduplicationId", entryNode.path("MessageDeduplicationId").asText(null));

                JsonNode attrsNode = entryNode.path("MessageAttributes");
                if (attrsNode.isObject()) {
                    Map<String, String> messageAttributes = new HashMap<>();
                    attrsNode.fields().forEachRemaining(field ->
                        messageAttributes.put(field.getKey(), field.getValue().path("StringValue").asText())
                    );
                    entry.put("MessageAttributes", messageAttributes);
                }

                entries.add(entry);
            }
        }

        SnsService.BatchPublishResult result = snsService.publishBatch(topicArn, entries, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode successful = response.putArray("Successful");
        for (String[] s : result.successful()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("Id", s[0]);
            item.put("MessageId", s[1]);
            successful.add(item);
        }
        ArrayNode failed = response.putArray("Failed");
        for (String[] f : result.failed()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("Id", f[0]);
            item.put("Code", f[1]);
            item.put("Message", f[2]);
            item.put("SenderFault", Boolean.parseBoolean(f[3]));
            failed.add(item);
        }

        return Response.ok(response).build();
    }

    private Response handleGetSubscriptionAttributes(JsonNode request, String region) {
        String subscriptionArn = request.path("SubscriptionArn").asText(null);
        Map<String, String> attrs = snsService.getSubscriptionAttributes(subscriptionArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrsNode = response.putObject("Attributes");
        for (var entry : attrs.entrySet()) {
            attrsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleSetSubscriptionAttributes(JsonNode request, String region) {
        String subscriptionArn = request.path("SubscriptionArn").asText(null);
        String attributeName = request.path("AttributeName").asText(null);
        String attributeValue = request.path("AttributeValue").asText(null);
        snsService.setSubscriptionAttribute(subscriptionArn, attributeName, attributeValue, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleConfirmSubscription(JsonNode request, String region) {
        String topicArn = request.path("TopicArn").asText(null);
        String token = request.path("Token").asText(null);
        String subscriptionArn = snsService.confirmSubscription(topicArn, token, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("SubscriptionArn", subscriptionArn);
        return Response.ok(response).build();
    }

    private ObjectNode subscriptionToNode(Subscription s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SubscriptionArn", s.getSubscriptionArn());
        node.put("TopicArn", s.getTopicArn());
        node.put("Protocol", s.getProtocol());
        node.put("Endpoint", s.getEndpoint() != null ? s.getEndpoint() : "");
        node.put("Owner", s.getOwner() != null ? s.getOwner() : "");
        return node;
    }

    private Map<String, String> jsonNodeToMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }
}
