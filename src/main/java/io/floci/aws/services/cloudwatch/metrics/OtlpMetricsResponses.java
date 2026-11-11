package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Status;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;

/** OTLP status responses shared by the endpoint and the authentication filters. */
public final class OtlpMetricsResponses {

    private static final String PROTOBUF = "application/x-protobuf";

    private OtlpMetricsResponses() {}

    public static boolean isRequest(ContainerRequestContext context) {
        if (!"POST".equalsIgnoreCase(context.getMethod()) || context.getUriInfo() == null) {
            return false;
        }
        String path = context.getUriInfo().getPath();
        return "v1/metrics".equals(path) || "v1/metrics/".equals(path)
                || "/v1/metrics".equals(path) || "/v1/metrics/".equals(path);
    }

    public static Response error(MediaType requestType, int httpStatus, int code, String message) {
        boolean json = requestType != null && requestType.isCompatible(MediaType.APPLICATION_JSON_TYPE);
        Status status = Status.newBuilder().setCode(code).setMessage(message).build();
        try {
            byte[] body = json ? JsonFormat.printer().print(status).getBytes(StandardCharsets.UTF_8)
                    : status.toByteArray();
            return Response.status(httpStatus).entity(body)
                    .type(json ? MediaType.APPLICATION_JSON : PROTOBUF).build();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("OTLP status response could not be encoded", e);
        }
    }
}
