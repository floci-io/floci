package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationToken;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.DomainView;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.ResourcePolicy;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import io.github.hectorvent.floci.services.codeartifact.model.PackageAsset;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CodeArtifactController {

    private final CodeArtifactService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeArtifactController(CodeArtifactService service, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- domains

    @POST
    @Path("/v1/domain")
    public Response createDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.createDomain(region, domain, text(req, "encryptionKey"), readTagList(req.get("tags")));
        return ok(single("domain", domainDescription(view)));
    }

    @DELETE
    @Path("/v1/domain")
    public Response deleteDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                  @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.deleteDomain(region, domain, domainOwner);
        return ok(single("domain", domainDescription(view)));
    }

    @GET
    @Path("/v1/domain")
    public Response describeDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                    @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.describeDomain(region, domain, domainOwner);
        return ok(single("domain", domainDescription(view)));
    }

    @POST
    @Path("/v1/domains")
    public Response listDomains(@Context HttpHeaders headers, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
        PaginatedResult<DomainView> page = service.listDomains(region, maxResults, text(req, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("domains");
        page.items().forEach(view -> items.add(domainSummary(view)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return ok(response);
    }

    @POST
    @Path("/v1/authorization-token")
    @Consumes(MediaType.WILDCARD)
    public Response getAuthorizationToken(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("duration") String durationSeconds) {
        String region = regionResolver.resolveRegion(headers);
        AuthorizationToken token = service.getAuthorizationToken(region, domain, domainOwner,
                parseDuration(durationSeconds));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("authorizationToken", token.token());
        response.put("expiration", token.expirationEpochSeconds());
        return ok(response);
    }

    private static Long parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "duration must be an integer.", 400);
        }
    }

    @PUT
    @Path("/v1/domain/permissions/policy")
    public Response putDomainPermissionsPolicy(@Context HttpHeaders headers, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.putDomainPermissionsPolicy(region, text(req, "domain"),
                text(req, "domainOwner"), text(req, "policyDocument"), text(req, "policyRevision"));
        return ok(single("policy", resourcePolicy(policy)));
    }

    @GET
    @Path("/v1/domain/permissions/policy")
    public Response getDomainPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.getDomainPermissionsPolicy(region, domain, domainOwner);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @DELETE
    @Path("/v1/domain/permissions/policy")
    public Response deleteDomainPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("policy-revision") String policyRevision) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.deleteDomainPermissionsPolicy(region, domain, domainOwner, policyRevision);
        return ok(single("policy", resourcePolicy(policy)));
    }

    // ------------------------------------------------------------ repositories

    @POST
    @Path("/v1/repository")
    public Response createRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.createRepository(region, domain, domainOwner, repository,
                text(req, "description"), readUpstreams(req.get("upstreams")), readTagList(req.get("tags")));
        return ok(single("repository", repositoryDescription(r)));
    }

    @DELETE
    @Path("/v1/repository")
    public Response deleteRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.deleteRepository(region, domain, domainOwner, repository);
        return ok(single("repository", repositoryDescription(r)));
    }

    @GET
    @Path("/v1/repository")
    public Response describeRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                        @QueryParam("domain-owner") String domainOwner,
                                        @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.describeRepository(region, domain, domainOwner, repository);
        return ok(single("repository", repositoryDescription(r)));
    }

    @PUT
    @Path("/v1/repository")
    public Response updateRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.updateRepository(region, domain, domainOwner, repository,
                text(req, "description"), req.has("upstreams") ? readUpstreams(req.get("upstreams")) : null);
        return ok(single("repository", repositoryDescription(r)));
    }

    @POST
    @Path("/v1/repositories")
    public Response listRepositories(@Context HttpHeaders headers,
                                      @QueryParam("repository-prefix") String repositoryPrefix,
                                      @QueryParam("max-results") String maxResults,
                                      @QueryParam("next-token") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<CodeArtifactRepository> page = service.listRepositories(region, repositoryPrefix,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        return ok(repositorySummaryList(page));
    }

    @POST
    @Path("/v1/domain/repositories")
    public Response listRepositoriesInDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                              @QueryParam("domain-owner") String domainOwner,
                                              @QueryParam("administrator-account") String administratorAccount,
                                              @QueryParam("repository-prefix") String repositoryPrefix,
                                              @QueryParam("max-results") String maxResults,
                                              @QueryParam("next-token") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<CodeArtifactRepository> page = service.listRepositoriesInDomain(region, domain, domainOwner,
                administratorAccount, repositoryPrefix, Pagination.parseMaxResults(maxResults, "ValidationException"),
                nextToken);
        return ok(repositorySummaryList(page));
    }

    @GET
    @Path("/v1/repository/endpoint")
    public Response getRepositoryEndpoint(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("endpointType") String endpointType) {
        String region = regionResolver.resolveRegion(headers);
        String endpoint = service.getRepositoryEndpoint(region, domain, domainOwner, repository, format,
                endpointType);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("repositoryEndpoint", endpoint);
        return ok(response);
    }

    @PUT
    @Path("/v1/repository/permissions/policy")
    public Response putRepositoryPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.putRepositoryPermissionsPolicy(region, domain, domainOwner, repository,
                text(req, "policyDocument"), text(req, "policyRevision"));
        return ok(single("policy", resourcePolicy(policy)));
    }

    @GET
    @Path("/v1/repository/permissions/policy")
    public Response getRepositoryPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.getRepositoryPermissionsPolicy(region, domain, domainOwner, repository);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @DELETE
    @Path("/v1/repository/permissions/policies")
    public Response deleteRepositoryPermissionsPolicy(@Context HttpHeaders headers,
                                                        @QueryParam("domain") String domain,
                                                        @QueryParam("domain-owner") String domainOwner,
                                                        @QueryParam("repository") String repository,
                                                        @QueryParam("policy-revision") String policyRevision) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.deleteRepositoryPermissionsPolicy(region, domain, domainOwner, repository,
                policyRevision);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @POST
    @Path("/v1/repository/external-connection")
    public Response associateExternalConnection(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                  @QueryParam("domain-owner") String domainOwner,
                                                  @QueryParam("repository") String repository,
                                                  @QueryParam("external-connection") String externalConnection) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.associateExternalConnection(region, domain, domainOwner, repository,
                externalConnection);
        return ok(single("repository", repositoryDescription(r)));
    }

    @DELETE
    @Path("/v1/repository/external-connection")
    public Response disassociateExternalConnection(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository,
                                                     @QueryParam("external-connection") String externalConnection) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.disassociateExternalConnection(region, domain, domainOwner, repository,
                externalConnection);
        return ok(single("repository", repositoryDescription(r)));
    }

    // ------------------------------------------------------- package versions

    @POST
    @Path("/v1/package/version/publish")
    @Consumes(MediaType.WILDCARD)
    public Response publishPackageVersion(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("namespace") String namespace,
                                           @QueryParam("package") String packageName,
                                           @QueryParam("version") String version,
                                           @QueryParam("asset") String assetName,
                                           @QueryParam("unfinished") String unfinished,
                                           @HeaderParam("x-amz-content-sha256") String assetSha256,
                                           byte[] body) {
        String region = regionResolver.resolveRegion(headers);
        PublishPackageVersionResult result = service.publishPackageVersion(region, domain, domainOwner, repository,
                format, namespace, packageName, version, assetName, assetSha256, unfinished, body);
        return ok(publishPackageVersionResponse(result));
    }

    @GET
    @Path("/v1/package/version")
    public Response describePackageVersion(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                            @QueryParam("domain-owner") String domainOwner,
                                            @QueryParam("repository") String repository,
                                            @QueryParam("format") String format,
                                            @QueryParam("namespace") String namespace,
                                            @QueryParam("package") String packageName,
                                            @QueryParam("version") String version) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactPackageVersion pv = service.describePackageVersion(region, domain, domainOwner, repository,
                format, namespace, packageName, version);
        return ok(single("packageVersion", packageVersionDescription(pv)));
    }

    @GET
    @Path("/v1/package/version/asset")
    public Response getPackageVersionAsset(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                            @QueryParam("domain-owner") String domainOwner,
                                            @QueryParam("repository") String repository,
                                            @QueryParam("format") String format,
                                            @QueryParam("namespace") String namespace,
                                            @QueryParam("package") String packageName,
                                            @QueryParam("version") String version,
                                            @QueryParam("asset") String assetName,
                                            @QueryParam("revision") String revision) {
        String region = regionResolver.resolveRegion(headers);
        PackageVersionAssetResult result = service.getPackageVersionAsset(region, domain, domainOwner, repository,
                format, namespace, packageName, version, assetName, revision);
        return Response.ok(result.asset().getContent(), MediaType.APPLICATION_OCTET_STREAM)
                .header("X-AssetName", result.asset().getName())
                .header("X-PackageVersion", version)
                .header("X-PackageVersionRevision", result.packageVersionRevision())
                .build();
    }

    // -------------------------------------------------------------------- tags

    @POST
    @Path("/v1/tag")
    public Response tagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        JsonNode req = readTree(body);
        service.tagResource(resourceArn, readTagList(req.get("tags")));
        return ok(objectMapper.createObjectNode());
    }

    @POST
    @Path("/v1/untag")
    public Response untagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        JsonNode req = readTree(body);
        List<String> keys = new ArrayList<>();
        if (req.hasNonNull("tagKeys") && req.get("tagKeys").isArray()) {
            req.get("tagKeys").forEach(n -> keys.add(n.asText()));
        }
        service.untagResource(resourceArn, keys);
        return ok(objectMapper.createObjectNode());
    }

    // ListTagsForResource (POST /v1/tags?resourceArn=) is handled by V1TagsController via
    // CodeArtifactTagHandler; see their javadoc.

    // ----------------------------------------------------------------- helpers

    private ObjectNode domainDescription(DomainView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", view.domain().getArn());
        node.put("assetSizeBytes", 0L);
        node.put("createdTime", view.domain().getCreatedTime());
        node.put("encryptionKey", view.domain().getEncryptionKey());
        node.put("name", view.domain().getName());
        node.put("owner", view.domain().getOwner());
        node.put("repositoryCount", view.repositoryCount());
        // AWS's internal per-domain bucket naming is not publicly documented; this is a synthesized placeholder.
        node.put("s3BucketArn", AwsArnUtils.Arn.global(AwsRegions.partitionFor(view.domain().getRegion()), "s3", "",
                "codeartifact-" + view.domain().getRegion() + "-" + view.domain().getOwner()).toString());
        node.put("status", "Active");
        return node;
    }

    private ObjectNode domainSummary(DomainView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", view.domain().getName());
        node.put("owner", view.domain().getOwner());
        node.put("arn", view.domain().getArn());
        node.put("status", "Active");
        node.put("createdTime", view.domain().getCreatedTime());
        node.put("encryptionKey", view.domain().getEncryptionKey());
        return node;
    }

    private ObjectNode repositoryDescription(CodeArtifactRepository r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("administratorAccount", r.getAdministratorAccount());
        node.put("arn", r.getArn());
        node.put("createdTime", r.getCreatedTime());
        if (r.getDescription() != null) {
            node.put("description", r.getDescription());
        }
        node.put("domainName", r.getDomainName());
        node.put("domainOwner", r.getDomainOwner());
        ArrayNode connections = node.putArray("externalConnections");
        for (ExternalConnection ec : r.getExternalConnections()) {
            ObjectNode c = objectMapper.createObjectNode();
            c.put("externalConnectionName", ec.getExternalConnectionName());
            c.put("packageFormat", ec.getPackageFormat());
            c.put("status", ec.getStatus());
            connections.add(c);
        }
        node.put("name", r.getName());
        ArrayNode upstreams = node.putArray("upstreams");
        for (String upstream : r.getUpstreams()) {
            ObjectNode u = objectMapper.createObjectNode();
            u.put("repositoryName", upstream);
            upstreams.add(u);
        }
        return node;
    }

    private ObjectNode repositorySummary(CodeArtifactRepository r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", r.getName());
        node.put("administratorAccount", r.getAdministratorAccount());
        node.put("domainName", r.getDomainName());
        node.put("domainOwner", r.getDomainOwner());
        node.put("arn", r.getArn());
        if (r.getDescription() != null) {
            node.put("description", r.getDescription());
        }
        node.put("createdTime", r.getCreatedTime());
        return node;
    }

    private ObjectNode repositorySummaryList(PaginatedResult<CodeArtifactRepository> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("repositories");
        page.items().forEach(r -> items.add(repositorySummary(r)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private ObjectNode publishPackageVersionResponse(PublishPackageVersionResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("format", result.packageVersion().getFormat());
        if (result.packageVersion().getNamespace() != null) {
            node.put("namespace", result.packageVersion().getNamespace());
        }
        node.put("package", result.packageVersion().getPackageName());
        node.put("version", result.packageVersion().getVersion());
        node.put("versionRevision", result.packageVersion().getRevision());
        node.put("status", result.packageVersion().getStatus());
        node.set("asset", assetSummary(result.asset()));
        return node;
    }

    private ObjectNode packageVersionDescription(CodeArtifactPackageVersion pv) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("format", pv.getFormat());
        if (pv.getNamespace() != null) {
            node.put("namespace", pv.getNamespace());
        }
        node.put("packageName", pv.getPackageName());
        node.put("version", pv.getVersion());
        node.put("revision", pv.getRevision());
        node.put("status", pv.getStatus());
        if (pv.getPublishedTime() != null) {
            node.put("publishedTime", pv.getPublishedTime());
        }
        ObjectNode origin = node.putObject("origin");
        origin.put("originType", "INTERNAL");
        node.putArray("licenses");
        return node;
    }

    private ObjectNode assetSummary(PackageAsset asset) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", asset.getName());
        node.put("size", asset.getSize());
        ObjectNode hashes = node.putObject("hashes");
        asset.getHashes().forEach(hashes::put);
        return node;
    }

    private ObjectNode resourcePolicy(ResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceArn", policy.resourceArn());
        node.put("revision", policy.revision());
        node.put("document", policy.document());
        return node;
    }

    private ObjectNode single(String field, ObjectNode value) {
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set(field, value);
        return wrapper;
    }

    private Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }

    private Map<String, String> readTagList(JsonNode tagsArray) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsArray == null || tagsArray.isNull()) {
            return tags;
        }
        if (!tagsArray.isArray()) {
            throw new AwsException("ValidationException", "tags must be an array of {key, value} objects.", 400);
        }
        tagsArray.forEach(n -> tags.put(text(n, "key"), text(n, "value")));
        return tags;
    }

    private List<String> readUpstreams(JsonNode upstreamsArray) {
        List<String> upstreams = new ArrayList<>();
        if (upstreamsArray == null || upstreamsArray.isNull()) {
            return upstreams;
        }
        if (!upstreamsArray.isArray()) {
            throw new AwsException("ValidationException", "upstreams must be an array of {repositoryName} objects.",
                    400);
        }
        upstreamsArray.forEach(n -> upstreams.add(text(n, "repositoryName")));
        return upstreams;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
