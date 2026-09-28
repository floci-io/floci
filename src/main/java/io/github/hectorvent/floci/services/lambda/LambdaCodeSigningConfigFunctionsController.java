package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
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

import java.util.List;
import java.util.regex.Pattern;

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
 * ListFunctionsByCodeSigningConfig reports which functions carry one, and always reports none:
 * attaching a configuration to a function is PutFunctionCodeSigningConfig, which is not
 * implemented, so nothing can be attached. It answers a configuration that does not exist with a
 * 404 rather than an empty list, because an empty list would say the configuration exists and
 * simply has no functions.</p>
 *
 * <p>Nothing here verifies a signature. Lambda's own code signing checks are not emulated, so a
 * configuration never gates a deployment.</p>
 */
@Path("/2020-04-22")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningConfigFunctionsController {

    /**
     * botocore's {@code CodeSigningConfigArn} shape pattern, verbatim. Applied with
     * {@link String#matches(String)} so it is anchored whole-string, which is the
     * right semantics for an ARN; the shape's {@code max: 200} is checked separately
     * because the pattern itself does not bound the partition suffix.
     */
    private static final Pattern CODE_SIGNING_CONFIG_ARN = Pattern.compile(
            "arn:(aws[a-zA-Z-]*)?:lambda:(eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}"
                    + ":\\d{12}:code-signing-config:csc-[a-z0-9]{17}");

    private static final int ARN_MAX_LENGTH = 200;
    private static final int MAX_ITEMS_MIN = 1;
    private static final int MAX_ITEMS_MAX = 10000;

    private final LambdaCodeSigningConfigService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCodeSigningConfigFunctionsController(LambdaCodeSigningConfigService service,
                                                      RegionResolver regionResolver,
                                                      ObjectMapper objectMapper) {
        this.service = service;
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
        requireValidArn(codeSigningConfigArn);
        parseMaxItems(maxItems);
        // Resolving the config is what turns an unknown ARN into the 404. A known one has no
        // functions, since PutFunctionCodeSigningConfig is not implemented, so Marker addresses a
        // page past the end and NextMarker is never emitted.
        service.get(regionResolver.resolveRegion(headers), codeSigningConfigArn);

        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FunctionArns");
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
        requireValidArn(codeSigningConfigArn);
        return Response.ok(wrap(service.get(regionResolver.resolveRegion(headers), codeSigningConfigArn))).build();
    }

    @PUT
    @Path("/code-signing-configs/{codeSigningConfigArn}")
    public Response updateCodeSigningConfig(@Context HttpHeaders headers,
                                            @PathParam("codeSigningConfigArn") String codeSigningConfigArn,
                                            ObjectNode body) {
        requireValidArn(codeSigningConfigArn);
        CodeSigningConfig config = service.update(regionResolver.resolveRegion(headers), codeSigningConfigArn,
                text(body, "Description"), allowedPublishers(body), policies(body));
        return Response.ok(wrap(config)).build();
    }

    @DELETE
    @Path("/code-signing-configs/{codeSigningConfigArn}")
    public Response deleteCodeSigningConfig(@Context HttpHeaders headers,
                                            @PathParam("codeSigningConfigArn") String codeSigningConfigArn) {
        requireValidArn(codeSigningConfigArn);
        service.delete(regionResolver.resolveRegion(headers), codeSigningConfigArn);
        return Response.noContent().build();
    }

    @GET
    @Path("/code-signing-configs")
    public Response listCodeSigningConfigs(@Context HttpHeaders headers,
                                           @QueryParam("Marker") String marker,
                                           @QueryParam("MaxItems") String maxItems) {
        parseMaxItems(maxItems);
        List<CodeSigningConfig> configs = service.list(regionResolver.resolveRegion(headers));
        ObjectNode root = objectMapper.createObjectNode();
        root.set("CodeSigningConfigs", objectMapper.valueToTree(configs));
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

    private void requireValidArn(String arn) {
        if (arn == null || arn.isBlank()
                || arn.length() > ARN_MAX_LENGTH
                || !CODE_SIGNING_CONFIG_ARN.matcher(arn).matches()) {
            throw new AwsException("InvalidParameterValueException",
                    "1 validation error detected: Value '" + arn + "' at 'codeSigningConfigArn' "
                            + "failed to satisfy constraint: Member must satisfy regular expression pattern: "
                            + CODE_SIGNING_CONFIG_ARN.pattern(), 400);
        }
    }

    private void parseMaxItems(String maxItems) {
        if (maxItems == null || maxItems.isBlank()) {
            return;
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
    }
}
