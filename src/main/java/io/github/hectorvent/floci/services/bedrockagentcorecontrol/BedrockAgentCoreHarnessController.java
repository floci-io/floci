package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreHarnessService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreHarnessRegistry.HarnessView;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Harness;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.HarnessVersion;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

/**
 * AgentCore harness endpoints under {@code /harnesses}: the control plane ({@code CreateHarness},
 * {@code GetHarness}, {@code UpdateHarness}, {@code DeleteHarness}, {@code ListHarnesses},
 * {@code ListHarnessVersions}) and the data plane's {@code InvokeHarness}.
 *
 * <p>{@code InvokeHarness} is a data-plane operation, but its path is {@code /harnesses/invoke}, and a
 * JAX-RS request is matched against one root resource class only: once this class claims
 * {@code /harnesses}, the data-plane controller rooted at {@code /} is never consulted for it. So the
 * endpoint sits here while the behaviour stays in {@link BedrockAgentCoreHarnessService}, as the
 * memory event endpoints do in {@link BedrockAgentCoreMemoryController}.
 */
@Path("/harnesses")
@Produces(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreHarnessController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreHarnessController.class);
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    private final BedrockAgentCoreHarnessRegistry registry;
    private final BedrockAgentCoreHarnessService invoker;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreHarnessController(BedrockAgentCoreHarnessRegistry registry,
                                             BedrockAgentCoreHarnessService invoker,
                                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.registry = registry;
        this.invoker = invoker;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response createHarness(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            HarnessView view = registry.create(object(body), region);
            return Response.status(201).entity(wrapped(view, region)).build();
        } catch (Exception e) {
            return error(e, "creating harness");
        }
    }

    @GET
    public Response listHarnesses(@Context HttpHeaders headers,
                                  @QueryParam("maxResults") String maxResultsParam,
                                  @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Harness> page = registry.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode harnesses = out.putArray("harnesses");
            for (Harness harness : page.items()) {
                ObjectNode summary = harnesses.addObject();
                summary.put("harnessId", harness.getHarnessId());
                summary.put("harnessName", harness.getHarnessName());
                summary.put("arn", registry.arn(harness, region));
                summary.put("status", harness.getStatus());
                putInstant(summary, "createdAt", harness.getCreatedAt());
                putInstant(summary, "updatedAt", harness.getUpdatedAt());
                summary.put("harnessVersion", harness.getCurrentVersion().getVersion());
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing harnesses");
        }
    }

    @GET
    @Path("/{harnessId}")
    public Response getHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                               @QueryParam("harnessVersion") String harnessVersion) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.get(harnessId, harnessVersion, region), region)).build();
        } catch (Exception e) {
            return error(e, "getting harness");
        }
    }

    @PATCH
    @Path("/{harnessId}")
    @Consumes(MediaType.WILDCARD)
    public Response updateHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.update(harnessId, object(body), region), region)).build();
        } catch (Exception e) {
            return error(e, "updating harness");
        }
    }

    /** {@code deleteManagedMemory} is accepted and ignored: Floci creates no managed memory for a harness. */
    @DELETE
    @Path("/{harnessId}")
    public Response deleteHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                  @QueryParam("clientToken") String clientToken,
                                  @QueryParam("deleteManagedMemory") String deleteManagedMemory) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.delete(harnessId, region), region)).build();
        } catch (Exception e) {
            return error(e, "deleting harness");
        }
    }

    @GET
    @Path("/{harnessId}/versions")
    public Response listHarnessVersions(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                        @QueryParam("maxResults") String maxResultsParam,
                                        @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            Harness harness = registry.get(harnessId, null, region).harness();
            PaginatedResult<HarnessVersion> page = registry.listVersions(harnessId, maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode versions = out.putArray("harnessVersions");
            for (HarnessVersion version : page.items()) {
                ObjectNode summary = versions.addObject();
                summary.put("harnessId", harness.getHarnessId());
                summary.put("harnessName", harness.getHarnessName());
                summary.put("arn", registry.arn(harness, region));
                summary.put("harnessVersion", version.getVersion());
                summary.put("status", harness.getStatus());
                putInstant(summary, "createdAt", version.getCreatedAt());
                putInstant(summary, "updatedAt", version.getCreatedAt());
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing harness versions");
        }
    }

    /**
     * {@code InvokeHarness}, the data plane's streaming operation. {@code harnessArn} arrives as a
     * query parameter and {@code runtimeSessionId} as a header, which is how the SDK binds them; only
     * {@code messages}, {@code model} and {@code tools} are in the body.
     *
     * <p>Request validation happens before the first frame: once the stream is committed the status
     * is already 200 and a problem could only be reported as an in-stream exception frame.
     */
    @POST
    @Blocking
    @Path("/invoke")
    @Consumes(MediaType.WILDCARD)
    public Response invokeHarness(@Context HttpHeaders headers,
                                  @QueryParam("harnessArn") String harnessArn,
                                  @QueryParam("qualifier") String harnessQualifier,
                                  String body) {
        ObjectNode request;
        try {
            JsonNode parsed = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            request = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON", 400);
        }

        // runtimeSessionId and runtimeUserId are headers on the wire, not body fields. Only the
        // session id is read: runtimeUserId reaches the harness on AWS but changes nothing here.
        String runtimeSessionId = headers.getHeaderString(SESSION_HEADER);
        Consumer<OutputStream> stream = invoker.invokeHarness(harnessArn, runtimeSessionId, request);
        LOG.debugv("InvokeHarness: arn={0}, qualifier={1}, session={2}",
                harnessArn, harnessQualifier, runtimeSessionId);

        Response.ResponseBuilder builder = Response.ok(streaming(stream))
                .header("Content-Type", "application/vnd.amazon.eventstream");
        if (runtimeSessionId != null) {
            builder.header(SESSION_HEADER, runtimeSessionId);
        }
        return builder.build();
    }

    /**
     * The explicit return type is what lets {@code GenericEntity} keep {@code StreamingOutput} as
     * the entity type; inlining the constructor erases it to {@code Object}. Same helper as the
     * bedrock-runtime controller.
     */
    private static GenericEntity<StreamingOutput> streaming(Consumer<OutputStream> stream) {
        return new GenericEntity<>(stream::accept, StreamingOutput.class);
    }

    /** {@code {"harness": {...}}}, the members in the order AWS returns them. */
    private ObjectNode wrapped(HarnessView view, String region) {
        Harness harness = view.harness();
        HarnessVersion version = view.version();
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode node = out.putObject("harness");
        node.put("harnessId", harness.getHarnessId());
        node.put("harnessName", harness.getHarnessName());
        node.put("arn", registry.arn(harness, region));
        node.put("status", harness.getStatus());
        node.put("harnessVersion", version.getVersion());
        node.put("executionRoleArn", version.getExecutionRoleArn());
        putInstant(node, "createdAt", harness.getCreatedAt());
        putInstant(node, "updatedAt", version.getCreatedAt());
        for (String member : BedrockAgentCoreHarnessRegistry.memberOrder()) {
            JsonNode value = version.getConfiguration().get(member);
            if (value != null) {
                node.set(member, value.deepCopy());
            }
        }
        return out;
    }

    private ObjectNode object(String body) throws IOException {
        JsonNode parsed = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!parsed.isObject()) {
            throw new AwsException("ValidationException", "Request body must be a JSON object", 400);
        }
        return (ObjectNode) parsed;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // ISO-8601, as AgentCore models these timestamps.
            node.put(field, instant.truncatedTo(ChronoUnit.MILLIS).toString());
        }
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
