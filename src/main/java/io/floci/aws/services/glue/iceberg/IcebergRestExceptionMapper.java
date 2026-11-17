package io.floci.aws.services.glue.iceberg;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.Map;

/** Answers an Iceberg REST catalog error with the specification's {@code ErrorModel}, not the AWS JSON envelope. */
@Provider
public final class IcebergRestExceptionMapper implements ExceptionMapper<IcebergRestException> {

    @Override
    public Response toResponse(IcebergRestException exception) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", exception.getMessage());
        error.put("type", exception.type());
        error.put("code", exception.code());
        return Response.status(exception.code())
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", error))
                .build();
    }
}
