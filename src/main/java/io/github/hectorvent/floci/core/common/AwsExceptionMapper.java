package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

@Provider
public class AwsExceptionMapper implements ExceptionMapper<AwsException> {

    private static final Logger LOG = Logger.getLogger(AwsExceptionMapper.class);
    private final ObjectMapper objectMapper;

    @Inject
    public AwsExceptionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Response toResponse(AwsException exception) {
        LOG.debugv("Mapping exception: {0} - {1}", exception.getErrorCode(), exception.getMessage());
        Object entity = exception.getExtendedData() != null
                ? buildExtendedResponse(exception)
                : new AwsErrorResponse(exception.jsonType(), exception.getMessage());
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(entity)
                .build();
    }

    private ObjectNode buildExtendedResponse(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        for (Map.Entry<String, Object> entry : exception.getExtendedData().entrySet()) {
            node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
        }
        return node;
    }
}
