package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreToolsController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreToolsController.class);

    private final BedrockAgentCoreToolsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreToolsController(BedrockAgentCoreToolsService service,
                                           RegionResolver regionResolver,
                                           ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/browsers")
    public Response createBrowser(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode request = object(body);
            ObjectNode browser = service.createBrowser(request, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserArn", browser.path("browserArn").asText());
            response.put("browserId", browser.path("browserId").asText());
            response.put("createdAt", browser.path("createdAt").asText());
            response.put("status", browser.path("status").asText());
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "creating browser");
        }
    }

    @GET
    @Path("/code-interpreters/{codeInterpreterId}")
    public Response getCodeInterpreter(@Context HttpHeaders headers,
                                       @PathParam("codeInterpreterId") String codeInterpreterId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode interpreter = service.getCodeInterpreter(codeInterpreterId, region).deepCopy();
            interpreter.remove("clientToken");
            interpreter.remove("tags");
            return Response.ok(interpreter).build();
        } catch (Exception e) {
            return error(e, "getting code interpreter");
        }
    }

    @DELETE
    @Path("/code-interpreters/{codeInterpreterId}")
    public Response deleteCodeInterpreter(@Context HttpHeaders headers,
                                          @PathParam("codeInterpreterId") String codeInterpreterId,
                                          @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode interpreter = service.deleteCodeInterpreter(codeInterpreterId, clientToken, region);
            ObjectNode response = objectMapper.createObjectNode();
            copyText(interpreter, response, "codeInterpreterId");
            copyText(interpreter, response, "lastUpdatedAt");
            copyText(interpreter, response, "status");
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "deleting code interpreter");
        }
    }

    @POST
    @Path("/code-interpreters")
    public Response listCodeInterpreters(@Context HttpHeaders headers,
                                         @QueryParam("maxResults") String maxResultsParam,
                                         @QueryParam("nextToken") String nextToken,
                                         @QueryParam("type") String type) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            var result = service.listCodeInterpreters(maxResults, nextToken, type, region);
            ObjectNode response = objectMapper.createObjectNode();
            var summaries = response.putArray("codeInterpreterSummaries");
            for (ObjectNode interpreter : result.items()) {
                ObjectNode summary = summaries.addObject();
                copyText(interpreter, summary, "codeInterpreterArn");
                copyText(interpreter, summary, "codeInterpreterId");
                copyText(interpreter, summary, "createdAt");
                copyText(interpreter, summary, "description");
                copyText(interpreter, summary, "lastUpdatedAt");
                copyText(interpreter, summary, "name");
                copyText(interpreter, summary, "status");
            }
            if (result.nextToken() != null) {
                response.put("nextToken", result.nextToken());
            }
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "listing code interpreters");
        }
    }

    @PUT
    @Path("/code-interpreters")
    public Response createCodeInterpreter(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode interpreter = service.createCodeInterpreter(object(body), region);
            ObjectNode response = objectMapper.createObjectNode();
            copyText(interpreter, response, "codeInterpreterArn");
            copyText(interpreter, response, "codeInterpreterId");
            copyText(interpreter, response, "createdAt");
            copyText(interpreter, response, "status");
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "creating code interpreter");
        }
    }

    @PUT
    @Path("/browser-profiles")
    public Response createBrowserProfile(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode profile = service.createBrowserProfile(object(body), region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("createdAt", profile.path("createdAt").asText());
            response.put("profileArn", profile.path("profileArn").asText());
            response.put("profileId", profile.path("profileId").asText());
            response.put("status", profile.path("status").asText());
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "creating browser profile");
        }
    }

    @POST
    @Path("/browser-profiles")
    public Response listBrowserProfiles(@Context HttpHeaders headers,
                                        @QueryParam("maxResults") String maxResultsParam,
                                        @QueryParam("nextToken") String nextToken,
                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            ObjectNode request = object(body);
            String name = request.hasNonNull("name") ? request.get("name").asText() : null;
            var result = service.listBrowserProfiles(maxResults, nextToken, name, region);
            ObjectNode response = objectMapper.createObjectNode();
            var summaries = response.putArray("profileSummaries");
            for (ObjectNode profile : result.items()) {
                ObjectNode summary = summaries.addObject();
                copyText(profile, summary, "createdAt");
                copyText(profile, summary, "description");
                copyText(profile, summary, "lastSavedAt");
                copyText(profile, summary, "lastSavedBrowserId");
                copyText(profile, summary, "lastSavedBrowserSessionId");
                copyText(profile, summary, "lastUpdatedAt");
                copyText(profile, summary, "name");
                copyText(profile, summary, "profileArn");
                copyText(profile, summary, "profileId");
                copyText(profile, summary, "status");
            }
            if (result.nextToken() != null) {
                response.put("nextToken", result.nextToken());
            }
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "listing browser profiles");
        }
    }

    @DELETE
    @Path("/browser-profiles/{profileId}")
    public Response deleteBrowserProfile(@Context HttpHeaders headers,
                                         @PathParam("profileId") String profileId,
                                         @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode profile = service.deleteBrowserProfile(profileId, clientToken, region);
            ObjectNode response = objectMapper.createObjectNode();
            copyText(profile, response, "lastSavedAt");
            copyText(profile, response, "lastUpdatedAt");
            copyText(profile, response, "profileArn");
            copyText(profile, response, "profileId");
            copyText(profile, response, "status");
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "deleting browser profile");
        }
    }

    @GET
    @Path("/browser-profiles/{profileId}")
    public Response getBrowserProfile(@Context HttpHeaders headers, @PathParam("profileId") String profileId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode profile = service.getBrowserProfile(profileId, region).deepCopy();
            profile.remove("clientToken");
            profile.remove("tags");
            return Response.ok(profile).build();
        } catch (Exception e) {
            return error(e, "getting browser profile");
        }
    }

    @GET
    @Path("/browsers/{browserId}")
    public Response getBrowser(@Context HttpHeaders headers, @PathParam("browserId") String browserId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode browser = service.getBrowser(browserId, region);
            ObjectNode response = browser.deepCopy();
            response.remove("clientToken");
            response.remove("tags");
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "getting browser");
        }
    }

    @DELETE
    @Path("/browsers/{browserId}")
    public Response deleteBrowser(@Context HttpHeaders headers,
                                  @PathParam("browserId") String browserId,
                                  @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode browser = service.deleteBrowser(browserId, clientToken, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("browserId", browser.path("browserId").asText());
            response.put("lastUpdatedAt", browser.path("lastUpdatedAt").asText());
            response.put("status", browser.path("status").asText());
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "deleting browser");
        }
    }

    @POST
    @Path("/browsers")
    public Response listBrowsers(@Context HttpHeaders headers,
                                 @QueryParam("maxResults") String maxResultsParam,
                                 @QueryParam("nextToken") String nextToken,
                                 @QueryParam("type") String type) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            var result = service.listBrowsers(maxResults, nextToken, type, region);
            ObjectNode response = objectMapper.createObjectNode();
            var summaries = response.putArray("browserSummaries");
            for (ObjectNode browser : result.items()) {
                ObjectNode summary = summaries.addObject();
                copyText(browser, summary, "browserArn");
                copyText(browser, summary, "browserId");
                copyText(browser, summary, "createdAt");
                copyText(browser, summary, "description");
                copyText(browser, summary, "lastUpdatedAt");
                copyText(browser, summary, "name");
                copyText(browser, summary, "status");
            }
            if (result.nextToken() != null) {
                response.put("nextToken", result.nextToken());
            }
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "listing browsers");
        }
    }

    private static void copyText(ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private ObjectNode object(String body) throws Exception {
        JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!request.isObject()) {
            throw new AwsException("ValidationException", "request body must be a JSON object", 400);
        }
        return (ObjectNode) request;
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
