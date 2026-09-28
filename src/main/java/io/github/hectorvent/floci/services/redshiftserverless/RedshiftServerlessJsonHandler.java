package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redshift Serverless JSON 1.1 handler. Dispatched from
 * {@link io.github.hectorvent.floci.core.common.AwsJson11Controller}
 * under the {@code RedshiftServerless.} target prefix.
 */
@ApplicationScoped
public class RedshiftServerlessJsonHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessJsonHandler.class);

    /**
     * {@code Namespace.creationDate} carries {@code TimestampFormatTrait(ISO_8601)}, which
     * overrides the epoch-seconds default that awsJson1.1 would otherwise apply. Do not
     * generalise: roughly half the timestamp members in this model carry no format trait and
     * use the epoch default, so check each member's trait before emitting it. Emitting a number here is accepted by the CLI, because
     * botocore coerces it, but strict SDKs reject the response outright.
     */
    private static final DateTimeFormatter CREATION_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final RedshiftServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftServerlessJsonHandler(RedshiftServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Redshift Serverless action: {0}", action);
        try {
            return switch (action) {
                case "CreateNamespace" -> handleCreateNamespace(request, region);
                case "GetNamespace" -> handleGetNamespace(request, region);
                case "ListNamespaces" -> handleListNamespaces(request, region);
                case "UpdateNamespace" -> handleUpdateNamespace(request, region);
                case "DeleteNamespace" -> handleDeleteNamespace(request, region);
                case "ListTagsForResource" -> handleListTagsForResource(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Redshift Serverless error processing action %s", action);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response handleCreateNamespace(JsonNode request, String region) {
        Namespace namespace = service.createNamespace(
                text(request, "namespaceName"),
                text(request, "adminUsername"),
                text(request, "dbName"),
                text(request, "kmsKeyId"),
                text(request, "defaultIamRoleArn"),
                parseStringList(request.path("iamRoles"), "iamRoles"),
                parseStringList(request.path("logExports"), "logExports"),
                parseTagList(request.path("tags"), "tags"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleGetNamespace(JsonNode request, String region) {
        return namespaceResponse(service.getNamespace(text(request, "namespaceName"), region));
    }

    private Response handleListNamespaces(JsonNode request, String region) {
        PaginatedResult<Namespace> page = service.listNamespaces(
                region, parseMaxResults(request), text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("namespaces");
        page.items().forEach(namespace -> items.add(namespaceNode(namespace)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateNamespace(JsonNode request, String region) {
        Namespace namespace = service.updateNamespace(
                text(request, "namespaceName"),
                text(request, "adminUsername"),
                text(request, "kmsKeyId"),
                text(request, "defaultIamRoleArn"),
                parseStringList(request.path("iamRoles"), "iamRoles"),
                parseStringList(request.path("logExports"), "logExports"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleDeleteNamespace(JsonNode request, String region) {
        return namespaceResponse(service.deleteNamespace(text(request, "namespaceName"), region));
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        Map<String, String> tags = service.listTagsForResource(text(request, "resourceArn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagListNode(tags));
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        service.tagResource(text(request, "resourceArn"), parseTagList(request.path("tags"), "tags"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        service.untagResource(text(request, "resourceArn"), parseStringList(request.path("tagKeys"), "tagKeys"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ArrayNode tagListNode(Map<String, String> tags) {
        ArrayNode node = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = node.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return node;
    }

    private Response namespaceResponse(Namespace namespace) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(namespace));
        return Response.ok(response).build();
    }

    /**
     * {@code adminUserPassword} is deliberately absent: AWS never returns it on any namespace
     * operation, and the Terraform provider treats a returned value as a permanent diff.
     */
    private ObjectNode namespaceNode(Namespace namespace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("namespaceName", namespace.getNamespaceName());
        node.put("namespaceId", namespace.getNamespaceId());
        node.put("namespaceArn", namespace.getNamespaceArn());
        node.put("dbName", namespace.getDbName());
        node.put("kmsKeyId", namespace.getKmsKeyId());
        node.put("status", namespace.getStatus());
        if (namespace.getAdminUsername() != null) {
            node.put("adminUsername", namespace.getAdminUsername());
        }
        if (namespace.getDefaultIamRoleArn() != null) {
            node.put("defaultIamRoleArn", namespace.getDefaultIamRoleArn());
        }
        if (namespace.getCreationDate() != null) {
            node.put("creationDate", CREATION_DATE_FORMAT.format(namespace.getCreationDate()));
        }
        ArrayNode iamRoles = node.putArray("iamRoles");
        namespace.getIamRoles().forEach(iamRoles::add);
        ArrayNode logExports = node.putArray("logExports");
        namespace.getLogExports().forEach(logExports::add);
        return node;
    }

    private Integer parseMaxResults(JsonNode request) {
        JsonNode node = request.path("maxResults");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw validation("maxResults must be an integer.");
        }
        return node.asInt();
    }

    /**
     * Absent means absent and wrong means wrong: only a missing or null member returns null, so
     * that callers can distinguish "omitted, keep the stored value" from "supplied". Treating a
     * present member of the wrong type as absent would let a malformed UpdateNamespace silently
     * keep the old roles instead of reporting the request as invalid.
     */
    private List<String> parseStringList(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw validation(field + " must contain only strings.");
            }
            list.add(element.textValue());
        }
        return list;
    }

    private Map<String, String> parseTagList(JsonNode tagsNode, String field) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw validation(field + " must be an array of tags.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (!tag.isObject()) {
                throw validation(field + " must contain only tag objects.");
            }
            String key = tag.path("key").asText(null);
            if (key != null) {
                tags.put(key, tag.path("value").asText(null));
            }
        }
        return tags;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
