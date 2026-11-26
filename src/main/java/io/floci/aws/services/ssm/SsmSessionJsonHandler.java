package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.ssm.session.SsmSession;
import io.floci.aws.services.ssm.session.SsmSessionService;
import io.floci.aws.services.ssm.session.SsmSessionService.SessionConnection;
import io.floci.aws.services.ssm.session.SsmSessionService.SessionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.floci.aws.services.ssm.SsmJsonDeserialization.serializationException;
import static io.floci.aws.services.ssm.SsmJsonHandler.DOCUMENT_NAME_OR_ARN;

@ApplicationScoped
class SsmSessionJsonHandler {
    private static final Pattern REASON = Pattern.compile("^.{1,256}$");

    private final SsmSessionService sessions;
    private final ObjectMapper mapper;

    @Inject
    SsmSessionJsonHandler(SsmSessionService sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    Response handle(String action, JsonNode request, String region, URI baseUri) {
        return switch (action) {
            case "StartSession" -> start(request, region, baseUri);
            case "ResumeSession" -> connection(sessions.resume(region, sessionId(request), baseUri));
            case "TerminateSession" -> terminate(request, region);
            case "DescribeSessions" -> describe(request, region);
            case "GetConnectionStatus" -> connectionStatus(request, region);
            default -> throw new IllegalArgumentException("Unknown Session Manager action " + action);
        };
    }

    private Response connectionStatus(JsonNode request, String region) {
        String target = requiredText(request.path("Target"), "target", 1, 400);
        return Response.ok(mapper.createObjectNode()
                .put("Target", target)
                .put("Status", sessions.isConnected(region, target) ? "connected" : "notconnected")).build();
    }

    private Response start(JsonNode request, String region, URI baseUri) {
        String target = requiredText(request.path("Target"), "target", 1, 400);
        String name = optionalPattern(request.path("DocumentName"), "documentName", DOCUMENT_NAME_OR_ARN);
        String reason = optionalPattern(request.path("Reason"), "reason", REASON);
        return connection(sessions.start(region, target, name, reason, parameters(request.path("Parameters")), baseUri));
    }

    private Response connection(SessionConnection session) {
        ObjectNode response = mapper.createObjectNode();
        response.put("SessionId", session.sessionId());
        response.put("StreamUrl", session.streamUrl());
        response.put("TokenValue", session.tokenValue());
        return Response.ok(response).build();
    }

    private Response terminate(JsonNode request, String region) {
        String sessionId = sessionId(request);
        sessions.terminate(region, sessionId);
        return Response.ok(mapper.createObjectNode().put("SessionId", sessionId)).build();
    }

    private Response describe(JsonNode request, String region) {
        String state = requiredText(request.path("State"), "state", 1, Integer.MAX_VALUE);
        Integer maxResults = Pagination.parseMaxResults(request, "MaxResults", "SerializationException");
        String nextToken = Pagination.parseNextToken(request, "NextToken", "SerializationException");
        PaginatedResult<SsmSession> page = sessions.describe(region, state, filters(request.path("Filters")),
                maxResults, nextToken);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode entries = response.putArray("Sessions");
        for (SsmSession session : page.items()) {
            ObjectNode entry = entries.addObject();
            entry.put("SessionId", session.sessionId());
            entry.put("Target", session.target());
            entry.put("Owner", session.owner());
            entry.put("Status", session.status());
            entry.put("DocumentName", session.documentName());
            entry.put("AccessType", "Standard");
            entry.put("StartDate", EpochSeconds.fromInstant(session.startDate()));
            if (session.endDate() != null) {
                entry.put("EndDate", EpochSeconds.fromInstant(session.endDate()));
            }
            if (session.reason() != null) {
                entry.put("Reason", session.reason());
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private static Map<String, List<String>> parameters(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw serializationException(node, "a map");
        }
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> parameter : node.properties()) {
            String key = parameter.getKey();
            validateLength("parameters key", key, 1, 255);
            JsonNode array = parameter.getValue();
            if (!array.isArray()) {
                throw serializationException(array, "a list");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : array) {
                values.add(requiredText(value, "parameters." + key + " member", 1, 65535));
            }
            parameters.put(key, values);
        }
        return parameters;
    }

    private static List<SessionFilter> filters(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw serializationException(node, "a list");
        }
        if (node.size() < 1 || node.size() > 6) {
            throw SmithyValidationErrors.validationException(
                    "Value at 'filters' failed to satisfy constraint: Member must contain between 1 and 6 elements");
        }
        List<SessionFilter> filters = new ArrayList<>();
        for (JsonNode filter : node) {
            if (!filter.isObject()) {
                throw serializationException(filter, "a structure");
            }
            filters.add(new SessionFilter(requiredText(filter.path("key"), "filters key", 1, Integer.MAX_VALUE),
                    requiredText(filter.path("value"), "filters value", 1, 400)));
        }
        return filters;
    }

    private static String sessionId(JsonNode request) {
        return requiredText(request.path("SessionId"), "sessionId", 1, 96);
    }

    private static String requiredText(JsonNode node, String path, int minLength, int maxLength) {
        if (node.isMissingNode() || node.isNull()) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull(path));
        }
        String text = readText(node);
        validateLength(path, text, minLength, maxLength);
        return text;
    }

    private static String optionalPattern(JsonNode node, String path, Pattern pattern) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = readText(node);
        if (!pattern.matcher(text).matches()) {
            throw SmithyValidationErrors.validationException("Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: " + pattern.pattern());
        }
        return text;
    }

    private static String readText(JsonNode node) {
        if (!node.isTextual()) {
            throw serializationException(node, "a String");
        }
        return node.textValue();
    }

    private static void validateLength(String path, String value, int min, int max) {
        String violation = SmithyValidationErrors.lengthOutOfRange(path, value, min, max);
        if (violation != null) {
            throw SmithyValidationErrors.validationException(violation);
        }
    }
}
