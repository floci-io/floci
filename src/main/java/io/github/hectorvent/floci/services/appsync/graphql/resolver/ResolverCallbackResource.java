package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The resolver callback the GraphQL sidecar calls back into, one HTTP call per execution level.
 *
 * <p>The sidecar runs the query and owns the GraphQL engine; Floci owns the resolvers and the data
 * sources. This is the seam between them: the sidecar sends every field of one execution level it
 * cannot resolve itself, Floci runs each field's resolver, and the answers become those fields'
 * values and the {@code source} of their own children. The contract is {@code graphql/API.md} in
 * floci-io/floci-sidecars.
 *
 * <p>The literal {@code /appsync-resolve} path follows {@code /cfn-response}: a fixed Floci-internal
 * route, presented to the container as an absolute URL, authorised by a per-execution bearer token
 * rather than by AWS credentials.
 */
@Path("/appsync-resolve")
public class ResolverCallbackResource {

    private static final Logger LOG = Logger.getLogger(ResolverCallbackResource.class);
    private static final String BEARER = "Bearer ";

    private final ResolverCallbackSessions sessions;
    private final AppSyncResolverExecutor executor;
    private final ObjectMapper objectMapper;

    @Inject
    public ResolverCallbackResource(ResolverCallbackSessions sessions,
                                    AppSyncResolverExecutor executor,
                                    ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resolve(@HeaderParam("Authorization") String authorization, String body) {
        Optional<ResolverCallbackSessions.Session> session = sessions.find(bearerToken(authorization));
        if (session.isEmpty()) {
            // The sidecar turns any non-200 into a field error on exactly this batch, so an expired
            // or forged token fails those fields rather than the whole operation.
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unknown or expired resolver callback token")).build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Malformed resolver callback body")).build();
        }
        JsonNode invocations = root == null ? null : root.get("invocations");
        if (invocations == null || !invocations.isArray()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Resolver callback body is missing invocations")).build();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : invocations) {
            results.add(answer(session.get(), node));
        }
        return Response.ok(Map.of("results", results)).build();
    }

    private Map<String, Object> answer(ResolverCallbackSessions.Session session, JsonNode node) {
        String id = node.path("id").asText();
        ResolverInvocation invocation = invocationFrom(session, node);
        Resolver resolver;
        try {
            resolver = executor.findResolver(invocation.apiId(), invocation.typeName(), invocation.fieldName());
        } catch (AwsException e) {
            return error(id, new AppSyncResolverError(e.getMessage(), e.getErrorCode(), null, null, null));
        }
        if (resolver == null) {
            // The coordinate had a resolver when the operation was planned and does not now, which a
            // deploy mid-operation can do. Falling back to the source property is what a GraphQL
            // server does for a field with no resolver, so the field reads as it would have.
            return success(id, sourceProperty(invocation));
        }
        ResolverOutcome outcome = executor.execute(resolver, invocation);
        session.appendErrors(outcome.appendedErrors());
        return outcome.failed() ? error(id, outcome.error()) : success(id, outcome.data());
    }

    private ResolverInvocation invocationFrom(ResolverCallbackSessions.Session session, JsonNode node) {
        return new ResolverInvocation(
                session.apiId(),
                node.path("typeName").asText(),
                node.path("fieldName").asText(),
                asMap(node.get("arguments")),
                asPlain(node.get("source")),
                asList(node.get("path")),
                asMap(node.get("variables")),
                asStrings(node.get("selectionSetList")),
                session.identity(),
                session.authType());
    }

    /** What graphql-java's own property fetcher would have read off the parent value. */
    private static Object sourceProperty(ResolverInvocation invocation) {
        return invocation.source() instanceof Map<?, ?> source ? source.get(invocation.fieldName()) : null;
    }

    private static Map<String, Object> success(String id, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        // Deliberately a HashMap entry rather than Map.of: a resolver returning null is a valid
        // value, and Map.of rejects it.
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> error(String id, AppSyncResolverError raised) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", raised.message());
        error.put("type", raised.errorType());
        error.put("data", raised.data());
        error.put("info", raised.errorInfo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("error", error);
        return result;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return authorization.substring(BEARER.length()).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        Object value = asPlain(node);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(JsonNode node) {
        Object value = asPlain(node);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private List<String> asStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> values.add(entry.asText()));
        }
        return values;
    }

    private Object asPlain(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, Object.class);
        } catch (IllegalArgumentException e) {
            LOG.debugv(e, "Unreadable value in a resolver callback invocation");
            return null;
        }
    }
}
