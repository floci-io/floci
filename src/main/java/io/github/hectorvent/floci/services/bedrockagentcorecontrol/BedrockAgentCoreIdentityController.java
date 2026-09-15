package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.WorkloadIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Workload identity operations for the AgentCore control plane. These are RPC-style:
 * the operation name is a literal path segment ({@code POST /identities/<Operation>})
 * and all input travels in the JSON body.
 */
@Path("/identities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreIdentityController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreIdentityController.class);

    private final BedrockAgentCoreIdentityService service;
    private final BedrockAgentCoreCredentialProviderService credentialProviderService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreIdentityController(BedrockAgentCoreIdentityService service,
                                              BedrockAgentCoreCredentialProviderService credentialProviderService,
                                              RegionResolver regionResolver,
                                              ObjectMapper objectMapper) {
        this.service = service;
        this.credentialProviderService = credentialProviderService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/CreateWorkloadIdentity")
    public Response create(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.create(text(req, "name"),
                    urls(req.get("allowedResourceOauth2ReturnUrls")), region);
            return Response.status(201).entity(identityNode(identity, false)).build();
        } catch (Exception e) {
            return error(e, "creating workload identity");
        }
    }

    @POST
    @Path("/GetWorkloadIdentity")
    public Response get(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.get(text(req, "name"), region);
            return Response.ok(identityNode(identity, true)).build();
        } catch (Exception e) {
            return error(e, "getting workload identity");
        }
    }

    @POST
    @Path("/UpdateWorkloadIdentity")
    public Response update(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.update(text(req, "name"),
                    urls(req.get("allowedResourceOauth2ReturnUrls")), region);
            return Response.ok(identityNode(identity, true)).build();
        } catch (Exception e) {
            return error(e, "updating workload identity");
        }
    }

    @POST
    @Path("/DeleteWorkloadIdentity")
    public Response delete(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            service.delete(text(req, "name"), region);
            return Response.noContent().build();
        } catch (Exception e) {
            return error(e, "deleting workload identity");
        }
    }

    @POST
    @Path("/ListWorkloadIdentities")
    public Response list(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
            String nextToken = text(req, "nextToken");
            PaginatedResult<WorkloadIdentity> result = service.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("workloadIdentities");
            for (WorkloadIdentity identity : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("name", identity.getName());
                node.put("workloadIdentityArn", identity.getWorkloadIdentityArn());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing workload identities");
        }
    }

    @POST
    @Path("/CreateApiKeyCredentialProvider")
    public Response createApiKeyCredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode item = credentialProviderService.createApiKey(object(body), region);
            ObjectNode out = item.deepCopy();
            out.remove(List.of("createdTime", "lastUpdatedTime", "tags"));
            return Response.status(201).entity(out).build();
        } catch (Exception e) {
            return error(e, "creating API key credential provider");
        }
    }

    @POST
    @Path("/GetApiKeyCredentialProvider")
    public Response getApiKeyCredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            ObjectNode out = credentialProviderService.getApiKey(text(req, "name"), region);
            out.remove("tags");
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "getting API key credential provider");
        }
    }

    @POST
    @Path("/ListApiKeyCredentialProviders")
    public Response listApiKeyCredentialProviders(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
            String nextToken = text(req, "nextToken");
            PaginatedResult<ObjectNode> result = credentialProviderService.listApiKeys(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode providers = out.putArray("credentialProviders");
            for (ObjectNode item : result.items()) {
                ObjectNode summary = providers.addObject();
                summary.put("name", item.path("name").asText());
                summary.put("credentialProviderArn", item.path("credentialProviderArn").asText());
                summary.put("createdTime", item.path("createdTime").asLong());
                summary.put("lastUpdatedTime", item.path("lastUpdatedTime").asLong());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing API key credential providers");
        }
    }

    @POST
    @Path("/UpdateApiKeyCredentialProvider")
    public Response updateApiKeyCredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode out = credentialProviderService.updateApiKey(object(body), region);
            out.remove("tags");
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "updating API key credential provider");
        }
    }

    @POST
    @Path("/DeleteApiKeyCredentialProvider")
    public Response deleteApiKeyCredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            credentialProviderService.deleteApiKey(text(req, "name"), region);
            return Response.noContent().build();
        } catch (Exception e) {
            return error(e, "deleting API key credential provider");
        }
    }

    @POST
    @Path("/CreateOauth2CredentialProvider")
    public Response createOauth2CredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode item = credentialProviderService.createOauth2(object(body), region);
            ObjectNode out = item.deepCopy();
            out.remove(List.of("createdTime", "lastUpdatedTime", "credentialProviderVendor", "tags"));
            return Response.status(201).entity(out).build();
        } catch (Exception e) {
            return error(e, "creating OAuth2 credential provider");
        }
    }

    @POST
    @Path("/GetOauth2CredentialProvider")
    public Response getOauth2CredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            ObjectNode out = credentialProviderService.getOauth2(text(req, "name"), region);
            out.remove("tags");
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "getting OAuth2 credential provider");
        }
    }

    @POST
    @Path("/ListOauth2CredentialProviders")
    public Response listOauth2CredentialProviders(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
            String nextToken = text(req, "nextToken");
            PaginatedResult<ObjectNode> result = credentialProviderService.listOauth2(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode providers = out.putArray("credentialProviders");
            for (ObjectNode item : result.items()) {
                ObjectNode summary = providers.addObject();
                summary.put("name", item.path("name").asText());
                summary.put("credentialProviderArn", item.path("credentialProviderArn").asText());
                summary.put("credentialProviderVendor", item.path("credentialProviderVendor").asText());
                summary.put("createdTime", item.path("createdTime").asLong());
                summary.put("lastUpdatedTime", item.path("lastUpdatedTime").asLong());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing OAuth2 credential providers");
        }
    }

    @POST
    @Path("/UpdateOauth2CredentialProvider")
    public Response updateOauth2CredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode out = credentialProviderService.updateOauth2(object(body), region);
            out.remove("tags");
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "updating OAuth2 credential provider");
        }
    }

    @POST
    @Path("/DeleteOauth2CredentialProvider")
    public Response deleteOauth2CredentialProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode req = object(body);
            credentialProviderService.deleteOauth2(text(req, "name"), region);
            return Response.noContent().build();
        } catch (Exception e) {
            return error(e, "deleting OAuth2 credential provider");
        }
    }

    private ObjectNode identityNode(WorkloadIdentity identity, boolean full) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", identity.getName());
        node.put("workloadIdentityArn", identity.getWorkloadIdentityArn());
        ArrayNode urls = node.putArray("allowedResourceOauth2ReturnUrls");
        if (identity.getAllowedResourceOauth2ReturnUrls() != null) {
            identity.getAllowedResourceOauth2ReturnUrls().forEach(urls::add);
        }
        if (full) {
            putInstant(node, "createdTime", identity.getCreatedTime());
            putInstant(node, "lastUpdatedTime", identity.getLastUpdatedTime());
        }
        return node;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // Workload-identity timestamps are modeled as epoch seconds (unixTimestamp),
            // unlike the runtime timestamps which are ISO-8601 strings.
            node.put(field, instant.getEpochSecond());
        }
    }

    private ObjectNode object(String body) throws Exception {
        JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!request.isObject()) {
            throw new AwsException("ValidationException", "request body must be a JSON object", 400);
        }
        return (ObjectNode) request;
    }

    private static String text(JsonNode node, String field) {
        return BedrockAgentCoreControllerSupport.text(node, field);
    }

    private static List<String> urls(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
