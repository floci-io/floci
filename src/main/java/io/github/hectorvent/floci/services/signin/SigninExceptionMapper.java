package io.github.hectorvent.floci.services.signin;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/** Preserves the AWS Sign-In REST JSON error shape instead of the generic AWS envelope. */
@Provider
public final class SigninExceptionMapper implements ExceptionMapper<SigninException> {

    @Override
    public Response toResponse(SigninException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", exception.error(), "message", exception.getMessage()))
                .build();
    }
}
