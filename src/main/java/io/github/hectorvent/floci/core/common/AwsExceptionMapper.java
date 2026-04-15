package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * JAX-RS exception mapper that converts AwsException to AWS-formatted JSON error responses.
 */
@Provider
public class AwsExceptionMapper implements ExceptionMapper<AwsException> {

    private static final Logger LOG = Logger.getLogger(AwsExceptionMapper.class);

    @Override
    public Response toResponse(AwsException exception) {
        LOG.debugv("Mapping exception: {0} - {1}", exception.getErrorCode(), exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header(AwsHeader.HEADER_X_AMAZON_ERRORTYPE, exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }
}
