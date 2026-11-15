package io.floci.aws.core.common;

import io.floci.aws.services.cloudwatch.metrics.OtlpMetricsResponses;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Reformats authentication error responses for OTLP metrics requests.
 * Auth filters produce standard JSON errors; this filter translates them to the OTLP
 * {@code Status} envelope the OTLP spec requires.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class OtlpMetricsAuthResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!OtlpMetricsResponses.isRequest(request)) {
            return;
        }
        int status = response.getStatus();
        boolean incompleteSignature = status == 400 && response.getEntity() instanceof AwsErrorResponse error
                && "IncompleteSignatureException".equals(error.type());
        if (status != 401 && status != 403 && !incompleteSignature) {
            return;
        }
        String message = extractMessage(response);
        Response otlpError = OtlpMetricsResponses.error(request.getMediaType(), 403, 7, message);
        response.setStatus(403);
        response.setEntity(otlpError.getEntity());
        String contentType = otlpError.getMediaType() != null
                ? otlpError.getMediaType().toString() : "application/json";
        response.getHeaders().putSingle("Content-Type", contentType);
    }

    private static String extractMessage(ContainerResponseContext response) {
        Object entity = response.getEntity();
        if (entity instanceof AwsErrorResponse err) {
            return err.message();
        }
        return "Authentication failed.";
    }
}
