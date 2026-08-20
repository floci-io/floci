package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.sns.model.PlatformApplication;
import io.github.hectorvent.floci.services.sns.model.PlatformEndpoint;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Base64;
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
            case "CreatePlatformApplication" -> handleCreatePlatformApplication(request, region);
            case "DeletePlatformApplication" -> handleDeletePlatformApplication(request, region);
            case "GetPlatformApplicationAttributes" -> handleGetPlatformApplicationAttributes(request, region);
            case "SetPlatformApplicationAttributes" -> handleSetPlatformApplicationAttributes(request, region);
            case "ListPlatformApplications" -> handleListPlatformApplications(region);
            case "CreatePlatformEndpoint" -> handleCreatePlatformEndpoint(request, region);
            case "DeleteEndpoint" -> handleDeleteEndpoint(request, region);
            case "GetEndpointAttributes" -> handleGetEndpointAttributes(request, region);
            case "SetEndpointAttributes" -> handleSetEndpointAttributes(request, region);
            case "ListEndpointsByPlatformApplication" -> handleListEndpointsByPlatformApplication(request, region);
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
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        Subscription sub = snsService.subscribe(topicArn, protocol, endpoint, region, attributes);
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
        String messageStructure = request.path("MessageStructure").asText(null);

        Map<String, MessageAttributeValue> attributes = SnsMessageAttributes.parse(request.path("MessageAttributes"));

        String messageId = snsService.publish(topicArn, targetArn, phoneNumber, message, subject,
                messageStructure, attributes, null, null, region);
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
        List<String[]> entryAttributeFailures = new ArrayList<>();

        JsonNode entriesNode = request.path("PublishBatchRequestEntries");
        if (entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                String id = entryNode.path("Id").asText(null);
                Map<String, Object> entry = new HashMap<>();
                entry.put("Id", id);
                entry.put("Message", entryNode.path("Message").asText(null));
                entry.put("Subject", entryNode.path("Subject").asText(null));
                entry.put("MessageGroupId", entryNode.path("MessageGroupId").asText(null));
                entry.put("MessageDeduplicationId", entryNode.path("MessageDeduplicationId").asText(null));

                JsonNode attrsNode = entryNode.path("MessageAttributes");
                if (attrsNode.isObject()) {
                    try {
                        entry.put("MessageAttributes", SnsMessageAttributes.parse(attrsNode));
                    } catch (AwsException e) {
                        // Real AWS SNS surfaces per-entry attribute errors as Failed entries
                        // and keeps processing the rest of the batch, instead of aborting.
                        entryAttributeFailures.add(new String[]{id, e.getErrorCode(), e.getMessage(), "true"});
                        continue;
                    }
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
        List<String[]> allFailed = new ArrayList<>(result.failed());
        allFailed.addAll(entryAttributeFailures);
        for (String[] f : allFailed) {
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


    private Response handleCreatePlatformApplication(JsonNode request, String region) {
        String name = request.path("Name").asText(null);
        String platform = request.path("Platform").asText(null);
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        PlatformApplication app = snsService.createPlatformApplication(name, platform, attributes, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PlatformApplicationArn", app.getArn());
        return Response.ok(response).build();
    }

    private Response handleDeletePlatformApplication(JsonNode request, String region) {
        String arn = request.path("PlatformApplicationArn").asText(null);
        snsService.deletePlatformApplication(arn, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetPlatformApplicationAttributes(JsonNode request, String region) {
        String arn = request.path("PlatformApplicationArn").asText(null);
        Map<String, String> attrs = snsService.getPlatformApplicationAttributes(arn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrsNode = response.putObject("Attributes");
        for (var entry : attrs.entrySet()) {
            attrsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleSetPlatformApplicationAttributes(JsonNode request, String region) {
        String arn = request.path("PlatformApplicationArn").asText(null);
        Map<String, String> attrs = jsonNodeToMap(request.path("Attributes"));
        snsService.setPlatformApplicationAttributes(arn, attrs, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListPlatformApplications(String region) {
        List<PlatformApplication> apps = snsService.listPlatformApplications(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("PlatformApplications");
        for (PlatformApplication app : apps) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("PlatformApplicationArn", app.getArn());
            ObjectNode attrsNode = node.putObject("Attributes");
            for (var entry : app.getAttributes().entrySet()) {
                attrsNode.put(entry.getKey(), entry.getValue());
            }
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    private Response handleCreatePlatformEndpoint(JsonNode request, String region) {
        String appArn = request.path("PlatformApplicationArn").asText(null);
        String token = request.path("Token").asText(null);
        String customUserData = request.path("CustomUserData").asText(null);
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        PlatformEndpoint endpoint = snsService.createPlatformEndpoint(appArn, token, customUserData, attributes, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("EndpointArn", endpoint.getArn());
        return Response.ok(response).build();
    }

    private Response handleDeleteEndpoint(JsonNode request, String region) {
        String arn = request.path("EndpointArn").asText(null);
        snsService.deleteEndpoint(arn, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetEndpointAttributes(JsonNode request, String region) {
        String arn = request.path("EndpointArn").asText(null);
        Map<String, String> attrs = snsService.getEndpointAttributes(arn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrsNode = response.putObject("Attributes");
        for (var entry : attrs.entrySet()) {
            attrsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleSetEndpointAttributes(JsonNode request, String region) {
        String arn = request.path("EndpointArn").asText(null);
        Map<String, String> attrs = jsonNodeToMap(request.path("Attributes"));
        snsService.setEndpointAttributes(arn, attrs, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListEndpointsByPlatformApplication(JsonNode request, String region) {
        String appArn = request.path("PlatformApplicationArn").asText(null);
        List<PlatformEndpoint> endpoints = snsService.listEndpointsByPlatformApplication(appArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Endpoints");
        for (PlatformEndpoint ep : endpoints) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("EndpointArn", ep.getArn());
            ObjectNode attrsNode = node.putObject("Attributes");
            for (var entry : ep.getAttributes().entrySet()) {
                attrsNode.put(entry.getKey(), entry.getValue());
            }
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    private Map<String, String> jsonNodeToMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }
}
