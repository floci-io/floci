package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
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
import jakarta.ws.rs.core.UriInfo;

/**
 * The code signing configuration resource, all under /2020-04-22/code-signing-configs.
 *
 * <p>A separate class from {@link LambdaCodeSigningController} because it lives
 * under a different API version prefix (/2020-04-22 vs /2020-06-30) — JAX-RS
 * class-level {@code @Path} can only be one literal prefix, and a class without
 * one falls through to a more general catch-all route (S3's bucket-path matcher)
 * instead of matching here.</p>
 *
 * <p>Create, Get, Update, Delete and List manage the configuration itself.
 * ListFunctionsByCodeSigningConfig derives its results from the function records carrying the
 * configuration ARN. It answers a configuration that does not exist with a 404 rather than an
 * empty list, because an empty list would say the configuration exists and simply has no functions.</p>
 *
 * <p>Nothing here verifies a signature. Lambda's own code signing checks are not emulated, so a
 * configuration never gates a deployment.</p>
 */
@Path("/2020-04-22")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningConfigFunctionsController {

    private static final int MAX_ITEMS_MIN = 1;
    private static final int MAX_ITEMS_MAX = 10000;

    private final LambdaCodeSigningConfigService service;
    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCodeSigningConfigFunctionsController(LambdaCodeSigningConfigService service,
                                                      LambdaService lambdaService,
                                                      RegionResolver regionResolver,
                                                      ObjectMapper objectMapper) {
        this.service = service;
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Validation order matches AWS: request parameters are rejected before the
     * resource is resolved, so a bad {@code MaxItems} is reported as such rather
     * than being masked by the not-found that every well-formed ARN produces here.
     */
    @GET
    @Path("/code-signing-configs/{codeSigningConfigArn}/functions")
    public Response listFunctionsByCodeSigningConfig(
            @Context HttpHeaders headers,
            @PathParam("codeSigningConfigArn") String codeSigningConfigArn,
            @QueryParam("Marker") String marker,
            @QueryParam("MaxItems") String maxItems) {
        LambdaCodeSigningConfigService.validateArn(codeSigningConfigArn);
        Integer requested = maxItems == null || maxItems.isBlank() ? null : parseMaxItems(maxItems);
        String region = regionResolver.resolveRegion(headers);
        String accountId = regionResolver.getAccountId();
        service.get(region, accountId, codeSigningConfigArn);
        PaginatedResult<String> page = lambdaService.listFunctionsByCodeSigningConfig(
                region, accountId, codeSigningConfigArn, requested, marker);

        ObjectNode root = objectMapper.createObjectNode();
        root.set("FunctionArns", objectMapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            root.put("NextMarker", page.nextToken());
        }
        return Response.ok(root).build();
    }

    @POST
    @Path("/code-signing-configs")
    public Response createCodeSigningConfig(@Context HttpHeaders headers, ObjectNode body) {
        String region = regionResolver.resolveRegion(headers);
        CodeSigningConfig config = service.create(region, regionResolver.getAccountId(),
                text(body, "Description"), allowedPublishers(body), policies(body));
        return Response.status(Response.Status.CREATED).entity(wrap(config)).build();
    }

    @GET
    @Path("/code-signing-configs/{codeSigningConfigArn}")
    public Response getCodeSigningConfig(@Context HttpHeaders headers,
                                         @PathParam("codeSigningConfigArn") String codeSigningConfigArn) {
        LambdaCodeSigningConfigService.validateArn(codeSigningConfigArn);
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(wrap(service.get(region, regionResolver.getAccountId(), codeSigningConfigArn))).build();
    }

    @PUT
    @Path("/code-signing-configs/{codeSigningConfigArn}")
    public Response updateCodeSigningConfig(@Context HttpHeaders headers,
                                            @PathParam("codeSigningConfigArn") String codeSigningConfigArn,
                                            ObjectNode body) {
        LambdaCodeSigningConfigService.validateArn(codeSigningConfigArn);
        String region = regionResolver.resolveRegion(headers);
        return service.withConfigLock(codeSigningConfigArn, () -> {
            service.get(region, regionResolver.getAccountId(), codeSigningConfigArn);
            CodeSigningConfig config = service.update(region, codeSigningConfigArn,
                    text(body, "Description"), allowedPublishers(body), policies(body));
            return Response.ok(wrap(config)).build();
        });
    }

    @DELETE
    @Path("/code-signing-configs/{codeSigningConfigArn}")
    public Response deleteCodeSigningConfig(@Context HttpHeaders headers,
                                            @PathParam("codeSigningConfigArn") String codeSigningConfigArn) {
        LambdaCodeSigningConfigService.validateArn(codeSigningConfigArn);
        String region = regionResolver.resolveRegion(headers);
        String accountId = regionResolver.getAccountId();
        return service.withConfigLock(codeSigningConfigArn, () -> {
            service.get(region, accountId, codeSigningConfigArn);
            PaginatedResult<String> attached = lambdaService.listFunctionsByCodeSigningConfig(
                    region, accountId, codeSigningConfigArn, 1, null);
            if (!attached.items().isEmpty()) {
                throw new AwsException("ResourceConflictException",
                        "The code signing configuration is still attached to a function.", 409);
            }
            service.delete(region, codeSigningConfigArn);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("/code-signing-configs")
    public Response listCodeSigningConfigs(@Context HttpHeaders headers,
                                           @Context UriInfo uriInfo,
                                           @QueryParam("MaxItems") String maxItems) {
        int limit = parseMaxItems(maxItems);
        String region = regionResolver.resolveRegion(headers);
        String marker = uriInfo.getQueryParameters().getFirst("Marker");
        PaginatedResult<CodeSigningConfig> page = service.list(region, limit, marker);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("CodeSigningConfigs", objectMapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            root.put("NextMarker", page.nextToken());
        }
        return Response.ok(root).build();
    }

    private ObjectNode wrap(CodeSigningConfig config) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("CodeSigningConfig", objectMapper.valueToTree(config));
        return root;
    }

    private static String text(ObjectNode body, String field) {
        return body == null || !body.hasNonNull(field) ? null : body.get(field).asText();
    }

    private CodeSigningConfig.AllowedPublishers allowedPublishers(ObjectNode body) {
        if (body == null || !body.hasNonNull("AllowedPublishers")) {
            return null;
        }
        return objectMapper.convertValue(body.get("AllowedPublishers"),
                CodeSigningConfig.AllowedPublishers.class);
    }

    private CodeSigningConfig.CodeSigningPolicies policies(ObjectNode body) {
        if (body == null || !body.hasNonNull("CodeSigningPolicies")) {
            return null;
        }
        return objectMapper.convertValue(body.get("CodeSigningPolicies"),
                CodeSigningConfig.CodeSigningPolicies.class);
    }

    private int parseMaxItems(String maxItems) {
        if (maxItems == null || maxItems.isBlank()) {
            return MAX_ITEMS_MAX;
        }
        int value;
        try {
            value = Integer.parseInt(maxItems.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValueException",
                    "Value '" + maxItems + "' at 'maxItems' failed to satisfy constraint: "
                            + "Member must be an integer", 400);
        }
        if (value < MAX_ITEMS_MIN || value > MAX_ITEMS_MAX) {
            throw new AwsException("InvalidParameterValueException",
                    "Value '" + value + "' at 'maxItems' failed to satisfy constraint: "
                            + "Member must be between " + MAX_ITEMS_MIN + " and " + MAX_ITEMS_MAX, 400);
        }
        return value;
    }
}
