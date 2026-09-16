package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketplaceCatalogController {
    private final MarketplaceService service;
    private final ObjectReader strictReader;
    private final RequestContext context;

    @Inject
    public MarketplaceCatalogController(MarketplaceService service, ObjectMapper mapper, RequestContext context) {
        this.service = service;
        this.strictReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.context = context;
    }

    @POST
    @Path("/BatchDescribeEntities")
    public Response batchDescribeEntities(String body) {
        return ok(service.batchDescribeEntities(parse(body), region()));
    }

    @PATCH
    @Path("/CancelChangeSet")
    public Response cancelChangeSet(@QueryParam("catalog") String catalog, @QueryParam("changeSetId") String id) {
        return ok(service.cancelChangeSet(catalog, id, region()));
    }

    @DELETE
    @Path("/DeleteResourcePolicy")
    public Response deleteResourcePolicy(@QueryParam("resourceArn") String arn) {
        return ok(service.deleteResourcePolicy(arn, region()));
    }

    @POST
    @Path("/DescribeAssessment")
    public Response describeAssessment(String body) {
        return ok(service.describeAssessment(parse(body), region()));
    }

    @GET
    @Path("/DescribeChangeSet")
    public Response describeChangeSet(@QueryParam("catalog") String catalog, @QueryParam("changeSetId") String id) {
        return ok(service.describeChangeSet(catalog, id, region()));
    }

    @GET
    @Path("/DescribeEntity")
    public Response describeEntity(@QueryParam("catalog") String catalog, @QueryParam("entityId") String id) {
        return ok(service.describeEntity(catalog, id, region()));
    }

    @GET
    @Path("/GetResourcePolicy")
    public Response getResourcePolicy(@QueryParam("resourceArn") String arn) {
        return ok(service.getResourcePolicy(arn, region()));
    }

    @POST
    @Path("/ListAssessments")
    public Response listAssessments(String body) {
        return ok(service.listAssessments(parse(body), region()));
    }

    @POST
    @Path("/ListChangeSets")
    public Response listChangeSets(String body) {
        return ok(service.listChangeSets(parse(body), region()));
    }

    @POST
    @Path("/ListEntities")
    public Response listEntities(String body) {
        return ok(service.listEntities(parse(body), region()));
    }

    @POST
    @Path("/ListTagsForResource")
    public Response listTagsForResource(String body) {
        return ok(service.catalogListTags(parse(body), region()));
    }

    @POST
    @Path("/PutResourcePolicy")
    public Response putResourcePolicy(String body) {
        return ok(service.putResourcePolicy(parse(body), region()));
    }

    @POST
    @Path("/StartChangeSet")
    public Response startChangeSet(String body) {
        return ok(service.startChangeSet(parse(body), region()));
    }

    @POST
    @Path("/TagResource")
    public Response tagResource(String body) {
        return ok(service.catalogTagResource(parse(body), region()));
    }

    @POST
    @Path("/UntagResource")
    public Response untagResource(String body) {
        return ok(service.catalogUntagResource(parse(body), region()));
    }

    private JsonNode parse(String body) {
        try {
            JsonNode value = strictReader.readTree(body == null || body.isBlank() ? "{}" : body);
            if (value == null || !value.isObject()) {
                throw validation("Request body must be a JSON object.");
            }
            return value;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("Request body is not valid JSON.");
        }
    }

    private String region() {
        return context.getRegion() == null ? "us-east-1" : context.getRegion();
    }

    private static Response ok(JsonNode node) {
        return Response.ok(node).build();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 422);
    }
}
