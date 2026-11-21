package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * InvokeWithResponseStream, under /2021-11-15. A separate class from {@link LambdaController}
 * because it lives under a different API version prefix, and a class-level {@code @Path} can only
 * be one literal prefix.
 */
@Path("/2021-11-15")
public class LambdaResponseStreamController {

    private static final int REQUEST_LIMIT = 6 * 1024 * 1024;
    private static final List<String> LOG_TYPES = List.of("Tail", "None");
    private static final List<String> INVOCATION_TYPES = List.of("DryRun", "RequestResponse", "Event");

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaResponseStreamController(LambdaService lambdaService, RegionResolver regionResolver,
                                          ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/functions/{functionName}/response-streaming-invocations")
    @Consumes(MediaType.WILDCARD)
    @RunOnVirtualThread
    public Response invokeWithResponseStream(@Context HttpHeaders headers,
                                             @Context UriInfo uriInfo,
                                             @PathParam("functionName") String functionName,
                                             byte[] payload) {
        String qualifier = uriInfo.getQueryParameters().getFirst("Qualifier");
        String region = regionResolver.resolveRegion(headers);
        byte[] body = payload != null ? payload : new byte[0];

        requireWithinLimit(body);
        validate(headers.getHeaderString("X-Amz-Log-Type"), headers.getHeaderString("X-Amz-Invocation-Type"),
                qualifier);
        requireJson(body);
        LambdaService.InvokeTarget target = lambdaService.resolveInvokeTarget(region, functionName, qualifier);
        String clientContext = LambdaController.decodeClientContext(objectMapper,
                headers.getHeaderString("X-Amz-Client-Context"));
        boolean logTail = "Tail".equals(headers.getHeaderString("X-Amz-Log-Type"));
        String traceHeader = headers.getHeaderString("X-Amzn-Trace-Id");

        LambdaInvokeResponseStream stream = new LambdaInvokeResponseStream(objectMapper);
        CompletableFuture<InvokeResult> invocation = new CompletableFuture<>();
        Thread.ofVirtual().name("lambda-invoke-stream").start(() -> {
            try {
                invocation.complete(lambdaService.invokeStreaming(target, region, body, clientContext, traceHeader,
                        logTail, stream));
            } catch (RuntimeException e) {
                invocation.completeExceptionally(e);
            } finally {
                stream.end();
            }
        });
        CompletableFuture.anyOf(stream.started(), invocation).handle((ignored, e) -> null).join();

        String contentType;
        try {
            contentType = stream.remappedContentType(invocation);
        } catch (CompletionException e) {
            if (e.getCause() instanceof AwsException aws) {
                throw aws;
            }
            throw e;
        }
        return Response.ok(stream.body(invocation, logTail))
                .type(LambdaInvokeResponseStream.EVENT_STREAM)
                .header("X-Amz-Executed-Version", target.function().getVersion())
                .header("x-amzn-Remapped-Content-Type", contentType)
                .build();
    }

    private static void requireWithinLimit(byte[] body) {
        if (body.length > REQUEST_LIMIT) {
            throw new AwsException("RequestEntityTooLargeException", body.length
                    + " byte payload is too large for the RequestResponse invocation type (limit "
                    + REQUEST_LIMIT + " bytes)", 413);
        }
    }

    private static void validate(String logType, String invocationType, String qualifier) {
        List<String> violations = new ArrayList<>();
        addEnumViolation(violations, logType, "logType", LOG_TYPES);
        addEnumViolation(violations, invocationType, "invocationType", INVOCATION_TYPES);
        violations.addAll(LambdaService.invokeQualifierViolations(qualifier));
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    private static void addEnumViolation(List<String> violations, String value, String field, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            violations.add(SmithyValidationErrors.notInEnum(value, field, allowed));
        }
    }

    private void requireJson(byte[] body) {
        if (body.length == 0) {
            return;
        }
        try {
            objectMapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("InvalidRequestContentException",
                    "Could not parse request body into json: Could not parse payload into json: " + e.getMessage(),
                    400);
        }
    }
}
