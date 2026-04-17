package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
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
        if (exception instanceof ConditionalCheckFailedException){
            return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new AwsErrorResponseWithItem(exception.jsonType(), exception.getMessage(), ((ConditionalCheckFailedException) exception).getItem()))
                .build();
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }
}
