package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.regex.Pattern;

/**
 * ListFunctionsByCodeSigningConfig: GET /2020-04-22/code-signing-configs/{CodeSigningConfigArn}/functions.
 *
 * <p>A separate class from {@link LambdaCodeSigningController} because it lives
 * under a different API version prefix (/2020-04-22 vs /2020-06-30) — JAX-RS
 * class-level {@code @Path} can only be one literal prefix, and a class without
 * one falls through to a more general catch-all route (S3's bucket-path matcher)
 * instead of matching here.</p>
 *
 * <p>Floci does not implement code signing config management — there is no
 * CreateCodeSigningConfig / PutFunctionCodeSigningConfig, so no code signing
 * config can ever exist. That does <em>not</em> make an unconditional empty list
 * the right answer: botocore models both {@code InvalidParameterValueException}
 * and {@code ResourceNotFoundException} for this operation, and the only resource
 * the path parameter can name is a code signing config. A malformed ARN is
 * therefore a 400 and a well-formed ARN is a 404, because every well-formed ARN
 * necessarily names a config that does not exist here. Returning 200 with an
 * empty list would tell a caller that the config exists and simply has no
 * functions attached, which is a different — and false — statement.</p>
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

    /**
     * Validation order matches AWS: request parameters are rejected before the
     * resource is resolved, so a bad {@code MaxItems} is reported as such rather
     * than being masked by the not-found that every well-formed ARN produces here.
     */
    @GET
    @Path("/code-signing-configs/{codeSigningConfigArn}/functions")
    public Response listFunctionsByCodeSigningConfig(
            @PathParam("codeSigningConfigArn") String codeSigningConfigArn,
            @QueryParam("Marker") String marker,
            @QueryParam("MaxItems") String maxItems) {
        requireValidArn(codeSigningConfigArn);
        parseMaxItems(maxItems);
        // Marker is accepted and carries no state: the result set is always empty,
        // so any marker addresses a page past the end and NextMarker is never emitted.

        throw new AwsException("ResourceNotFoundException",
                "The code signing configuration " + codeSigningConfigArn + " does not exist.", 404);
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
