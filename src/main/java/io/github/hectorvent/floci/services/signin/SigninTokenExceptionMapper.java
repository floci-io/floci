package io.github.hectorvent.floci.services.signin;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/** Maps AWS Sign-In token failures to their modeled or OAuth wire envelopes. */
@Provider
public final class SigninTokenExceptionMapper implements ExceptionMapper<SigninTokenException> {

    private static final String ERROR_TYPE_HEADER = "X-Amzn-ErrorType";

    @Override
    public Response toResponse(SigninTokenException exception) {
        Response.ResponseBuilder response = Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.modeled()) {
            return response
                    .header(ERROR_TYPE_HEADER, exception.getErrorCode())
                    .entity(Map.of(
                            "error", exception.responseError(),
                            "message", exception.getMessage()))
                    .build();
        }
        return response
                .entity(Map.of(
                        "error", exception.responseError(),
                        "error_description", exception.getMessage()))
                .build();
    }
}
