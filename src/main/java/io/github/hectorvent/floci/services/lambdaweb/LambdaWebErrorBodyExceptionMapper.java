package io.github.hectorvent.floci.services.lambdaweb;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public final class LambdaWebErrorBodyExceptionMapper implements ExceptionMapper<LambdaWebErrorBodyException> {

    @Override
    public Response toResponse(LambdaWebErrorBodyException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.getErrorCode())
                .entity(exception.body())
                .build();
    }
}
