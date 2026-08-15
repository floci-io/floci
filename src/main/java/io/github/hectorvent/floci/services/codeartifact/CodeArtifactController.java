package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.RepositoryExternalConnection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS CodeArtifact REST-JSON controller.
 *
 * <p>CodeArtifact uses real REST paths and carries {@code domain}, {@code domain-owner} and
 * {@code repository} as query parameters rather than in the body, so every handler below
 * reads them from the query string exactly as the AWS model declares them.
 *
 * <p>Only the operations declared below are served; anything else falls through to the
 * emulator's not-found handling rather than a stub success.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CodeArtifactController {

    private final CodeArtifactService codeArtifactService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeArtifactController(CodeArtifactService codeArtifactService, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.codeArtifactService = codeArtifactService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Domains ────────────────────────────

    @POST
    @Path("/v1/domain")
    public Response createDomain(@QueryParam("domain") String domain,
                                 @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        CodeArtifactDomain created = codeArtifactService.createDomain(domain,
                text(request, "encryptionKey"), parseTagList(request.get("tags")), region);
        return Response.ok(wrap("domain", domainNode(created, region))).build();
    }

    @GET
    @Path("/v1/domain")
    public Response describeDomain(@QueryParam("domain") String domain,
                                   @QueryParam("domain-owner") String domainOwner,
                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactDomain found = codeArtifactService.describeDomain(domain, domainOwner, region);
        return Response.ok(wrap("domain", domainNode(found, region))).build();
    }

    @DELETE
    @Path("/v1/domain")
    public Response deleteDomain(@QueryParam("domain") String domain,
                                 @QueryParam("domain-owner") String domainOwner,
                                 @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactDomain deleted = codeArtifactService.deleteDomain(domain, domainOwner, region);
        return Response.ok(wrap("domain", domainNode(deleted, region))).build();
    }

    @POST
    @Path("/v1/domains")
    @Consumes(MediaType.WILDCARD)
    public Response listDomains(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("domains");
        for (CodeArtifactDomain domain : codeArtifactService.listDomains(region)) {
            array.add(domainSummaryNode(domain));
        }
        return Response.ok(response).build();
    }

    // ──────────────────── Domain permissions policies ────────────────────

    @PUT
    @Path("/v1/domain/permissions/policy")
    public Response putDomainPermissionsPolicy(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        CodeArtifactDomain domain = codeArtifactService.putDomainPermissionsPolicy(
                text(request, "domain"), text(request, "domainOwner"),
                text(request, "policyRevision"), text(request, "policyDocument"), region);
        return Response.ok(wrap("policy", policyNode(domain.getArn(), domain.getPolicyRevision(),
                domain.getPolicyDocument()))).build();
    }

    @GET
    @Path("/v1/domain/permissions/policy")
    public Response getDomainPermissionsPolicy(@QueryParam("domain") String domain,
                                               @QueryParam("domain-owner") String domainOwner,
                                               @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactDomain found = codeArtifactService.getDomainPermissionsPolicy(domain, domainOwner, region);
        return Response.ok(wrap("policy", policyNode(found.getArn(), found.getPolicyRevision(),
                found.getPolicyDocument()))).build();
    }

    @DELETE
    @Path("/v1/domain/permissions/policy")
    public Response deleteDomainPermissionsPolicy(@QueryParam("domain") String domain,
                                                  @QueryParam("domain-owner") String domainOwner,
                                                  @QueryParam("policy-revision") String policyRevision,
                                                  @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactDomain deleted = codeArtifactService.deleteDomainPermissionsPolicy(
                domain, domainOwner, policyRevision, region);
        return Response.ok(wrap("policy", policyNode(deleted.getArn(), deleted.getPolicyRevision(),
                deleted.getPolicyDocument()))).build();
    }

    // ──────────────────────────── Repositories ────────────────────────────

    @POST
    @Path("/v1/repository")
    public Response createRepository(@QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository,
                                     @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        CodeArtifactRepository created = codeArtifactService.createRepository(domain, domainOwner, repository,
                text(request, "description"), parseUpstreams(request.get("upstreams")),
                parseTagList(request.get("tags")), region);
        return Response.ok(wrap("repository", repositoryNode(created))).build();
    }

    @GET
    @Path("/v1/repository")
    public Response describeRepository(@QueryParam("domain") String domain,
                                       @QueryParam("domain-owner") String domainOwner,
                                       @QueryParam("repository") String repository,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository found = codeArtifactService.describeRepository(domain, domainOwner, repository, region);
        return Response.ok(wrap("repository", repositoryNode(found))).build();
    }

    @PUT
    @Path("/v1/repository")
    public Response updateRepository(@QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository,
                                     @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        CodeArtifactRepository updated = codeArtifactService.updateRepository(domain, domainOwner, repository,
                text(request, "description"), parseUpstreams(request.get("upstreams")),
                request.has("upstreams"), region);
        return Response.ok(wrap("repository", repositoryNode(updated))).build();
    }

    @DELETE
    @Path("/v1/repository")
    public Response deleteRepository(@QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository,
                                     @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository deleted = codeArtifactService.deleteRepository(domain, domainOwner, repository, region);
        return Response.ok(wrap("repository", repositoryNode(deleted))).build();
    }

    @POST
    @Path("/v1/repositories")
    @Consumes(MediaType.WILDCARD)
    public Response listRepositories(@QueryParam("repository-prefix") String repositoryPrefix,
                                     @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(repositorySummaries(
                codeArtifactService.listRepositories(repositoryPrefix, region))).build();
    }

    @POST
    @Path("/v1/domain/repositories")
    @Consumes(MediaType.WILDCARD)
    public Response listRepositoriesInDomain(@QueryParam("domain") String domain,
                                             @QueryParam("domain-owner") String domainOwner,
                                             @QueryParam("repository-prefix") String repositoryPrefix,
                                             @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(repositorySummaries(
                codeArtifactService.listRepositoriesInDomain(domain, domainOwner, repositoryPrefix, region))).build();
    }

    // ────────────────── Repository permissions policies ──────────────────

    @PUT
    @Path("/v1/repository/permissions/policy")
    public Response putRepositoryPermissionsPolicy(@QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("repository") String repository,
                                                   @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        CodeArtifactRepository updated = codeArtifactService.putRepositoryPermissionsPolicy(domain, domainOwner,
                repository, text(request, "policyRevision"), text(request, "policyDocument"), region);
        return Response.ok(wrap("policy", policyNode(updated.getArn(), updated.getPolicyRevision(),
                updated.getPolicyDocument()))).build();
    }

    @GET
    @Path("/v1/repository/permissions/policy")
    public Response getRepositoryPermissionsPolicy(@QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("repository") String repository,
                                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository found =
                codeArtifactService.getRepositoryPermissionsPolicy(domain, domainOwner, repository, region);
        return Response.ok(wrap("policy", policyNode(found.getArn(), found.getPolicyRevision(),
                found.getPolicyDocument()))).build();
    }

    @DELETE
    @Path("/v1/repository/permissions/policies")
    public Response deleteRepositoryPermissionsPolicy(@QueryParam("domain") String domain,
                                                      @QueryParam("domain-owner") String domainOwner,
                                                      @QueryParam("repository") String repository,
                                                      @QueryParam("policy-revision") String policyRevision,
                                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository deleted = codeArtifactService.deleteRepositoryPermissionsPolicy(
                domain, domainOwner, repository, policyRevision, region);
        return Response.ok(wrap("policy", policyNode(deleted.getArn(), deleted.getPolicyRevision(),
                deleted.getPolicyDocument()))).build();
    }

    // ────────────────────── External connections ──────────────────────

    @POST
    @Path("/v1/repository/external-connection")
    @Consumes(MediaType.WILDCARD)
    public Response associateExternalConnection(@QueryParam("domain") String domain,
                                                @QueryParam("domain-owner") String domainOwner,
                                                @QueryParam("repository") String repository,
                                                @QueryParam("external-connection") String externalConnection,
                                                @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository updated = codeArtifactService.associateExternalConnection(
                domain, domainOwner, repository, externalConnection, region);
        return Response.ok(wrap("repository", repositoryNode(updated))).build();
    }

    @DELETE
    @Path("/v1/repository/external-connection")
    public Response disassociateExternalConnection(@QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("repository") String repository,
                                                   @QueryParam("external-connection") String externalConnection,
                                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository updated = codeArtifactService.disassociateExternalConnection(
                domain, domainOwner, repository, externalConnection, region);
        return Response.ok(wrap("repository", repositoryNode(updated))).build();
    }

    @GET
    @Path("/v1/repository/endpoint")
    public Response getRepositoryEndpoint(@QueryParam("domain") String domain,
                                          @QueryParam("domain-owner") String domainOwner,
                                          @QueryParam("repository") String repository,
                                          @QueryParam("format") String format,
                                          @QueryParam("endpointType") String endpointType,
                                          @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("repositoryEndpoint", codeArtifactService.repositoryEndpoint(
                domain, domainOwner, repository, format, endpointType, region));
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    @POST
    @Path("/v1/tag")
    public Response tagResource(@QueryParam("resourceArn") String resourceArn,
                                @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        codeArtifactService.tagResource(resourceArn, parseTagList(request.get("tags")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v1/untag")
    public Response untagResource(@QueryParam("resourceArn") String resourceArn,
                                  @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        codeArtifactService.untagResource(resourceArn, stringList(request.get("tagKeys")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/v1/tags")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(@QueryParam("resourceArn") String resourceArn,
                                        @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = codeArtifactService.listTagsForResource(resourceArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode domainNode(CodeArtifactDomain domain, String region) {
        ObjectNode node = domainSummaryNode(domain);
        node.put("repositoryCount", codeArtifactService.repositoryCount(domain.getName(), region));
        node.put("assetSizeBytes", 0L);
        node.put("s3BucketArn", domain.getS3BucketArn());
        return node;
    }

    private ObjectNode domainSummaryNode(CodeArtifactDomain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", domain.getName());
        node.put("owner", domain.getOwner());
        node.put("arn", domain.getArn());
        node.put("status", domain.getStatus());
        node.put("createdTime", epochSeconds(domain.getCreatedTime()));
        node.put("encryptionKey", domain.getEncryptionKey());
        return node;
    }

    private ObjectNode repositoryNode(CodeArtifactRepository repository) {
        ObjectNode node = repositorySummaryNode(repository);
        ArrayNode upstreams = node.putArray("upstreams");
        for (String upstream : repository.getUpstreams()) {
            upstreams.addObject().put("repositoryName", upstream);
        }
        ArrayNode connections = node.putArray("externalConnections");
        for (RepositoryExternalConnection connection : repository.getExternalConnections()) {
            ObjectNode connectionNode = connections.addObject();
            connectionNode.put("externalConnectionName", connection.getExternalConnectionName());
            connectionNode.put("packageFormat", connection.getPackageFormat());
            connectionNode.put("status", connection.getStatus());
        }
        return node;
    }

    private ObjectNode repositorySummaryNode(CodeArtifactRepository repository) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", repository.getName());
        node.put("administratorAccount", repository.getAdministratorAccount());
        node.put("domainName", repository.getDomainName());
        node.put("domainOwner", repository.getDomainOwner());
        node.put("arn", repository.getArn());
        node.put("description", repository.getDescription() != null ? repository.getDescription() : "");
        node.put("createdTime", epochSeconds(repository.getCreatedTime()));
        return node;
    }

    private ObjectNode repositorySummaries(List<CodeArtifactRepository> found) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("repositories");
        for (CodeArtifactRepository repository : found) {
            array.add(repositorySummaryNode(repository));
        }
        return response;
    }

    private ObjectNode policyNode(String resourceArn, String revision, String document) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceArn", resourceArn);
        node.put("revision", revision);
        node.put("document", document);
        return node;
    }

    private ObjectNode wrap(String key, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(key, value);
        return response;
    }

    private long epochSeconds(Instant instant) {
        return instant != null ? instant.getEpochSecond() : 0L;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Map<String, String> parseTagList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                JsonNode key = tag.get("key");
                JsonNode value = tag.get("value");
                if (key != null && !key.isNull()) {
                    tags.put(key.asText(), value == null || value.isNull() ? "" : value.asText());
                }
            }
        }
        return tags;
    }

    private List<String> parseUpstreams(JsonNode upstreamsNode) {
        List<String> upstreams = new ArrayList<>();
        if (upstreamsNode != null && upstreamsNode.isArray()) {
            for (JsonNode upstream : upstreamsNode) {
                JsonNode repositoryName = upstream.get("repositoryName");
                if (repositoryName != null && !repositoryName.isNull()) {
                    upstreams.add(repositoryName.asText());
                }
            }
        }
        return upstreams;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }
}
