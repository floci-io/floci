package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.appsync.AppSyncService;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.AppSyncAuth;
import io.floci.aws.services.appsync.graphql.auth.AuthRequestInfo;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The HTTP publish endpoint of an Events API, {@code POST /event} on its {@code dns.HTTP} host,
 * served at {@code /_aws/appsync/events/<apiId>/event}: the path form a client uses when the API's
 * own host does not resolve to Floci, and where {@link AppSyncEventsHostFilter} moves the host form.
 *
 * <p>The body is {@code {"channel": "/ns/...", "events": ["<json>", ...]}} and a publish answers 200
 * with {@code {"successful": [{"identifier", "index"}], "failed": []}}. A refusal answers with the
 * AppSync data-plane envelope {@code {"errors": [{"errorType", "message"}]}}.
 */
@Path(AppSyncEventsController.PATH_PREFIX + "/{apiId}/event")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class AppSyncEventsController {

    public static final String PATH_PREFIX = "/_aws/appsync/events";

    private static final Logger LOG = Logger.getLogger(AppSyncEventsController.class);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AppSyncService appSyncService;
    private final EventsPublisher publisher;
    private final AppSyncEventsRequestPath requestPath;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncEventsController(AppSyncService appSyncService, EventsPublisher publisher,
                                   AppSyncEventsRequestPath requestPath, ObjectMapper objectMapper) {
        this.appSyncService = appSyncService;
        this.publisher = publisher;
        this.requestPath = requestPath;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response publish(@PathParam("apiId") String apiId, @Context HttpHeaders headers,
                            @Context UriInfo uriInfo, String body) {
        try {
            AppSyncService.EventApiOwner owner = appSyncService.findEventApi(apiId)
                    .orElseThrow(() -> new AppSyncTransportException(404, "NotFoundException",
                            "API not found: " + apiId));
            Map<String, Object> request = parse(body);
            Map<String, String> credentials = headerMap(headers);
            String signedPath = requestPath.signedPath() != null
                    ? requestPath.signedPath() : uriInfo.getRequestUri().getRawPath();
            AuthRequestInfo info = new AuthRequestInfo(null, null, Map.of(), List.of(), UUID.randomUUID().toString(),
                    owner.accountId(), EventsAuthorizer.regionOf(owner.api()), credentials, body);
            EventsPublisher.Result result = publisher.publish(owner, request.get("channel"), request.get("events"),
                    credentials, new EventsAuthorizer.SignedHttpRequest("POST", signedPath, info));
            ObjectNode root = objectMapper.createObjectNode();
            root.set("successful", objectMapper.valueToTree(result.successful()));
            root.set("failed", objectMapper.valueToTree(result.failed()));
            return Response.ok(root).build();
        } catch (AppSyncTransportException e) {
            return error(e.getHttpStatus(), e.getErrorType(), e.getMessage());
        }
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            throw AppSyncAuth.badRequest("The request body is required");
        }
        try {
            Map<String, Object> request = objectMapper.readValue(body, OBJECT_MAP);
            if (request == null) {
                throw AppSyncAuth.badRequest("The request body must be a JSON object");
            }
            return request;
        } catch (IOException e) {
            LOG.debugv("Unreadable Events publish body: {0}", e.getMessage());
            throw AppSyncAuth.badRequest("The request body must be a JSON object");
        }
    }

    private static Map<String, String> headerMap(HttpHeaders headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.getRequestHeaders().entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                map.put(entry.getKey(), String.join(",", entry.getValue()));
            }
        }
        return map;
    }

    private Response error(int status, String errorType, String message) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode errors = root.putArray("errors");
        ObjectNode error = errors.addObject();
        error.put("errorType", errorType);
        error.put("message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", errorType).entity(root).build();
    }
}
