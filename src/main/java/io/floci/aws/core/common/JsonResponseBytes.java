package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

public final class JsonResponseBytes {

    private JsonResponseBytes() {}

    public static byte[] serializeOrNull(Object entity, ObjectMapper objectMapper,
                                         Logger logger, String failureMessage) {
        try {
            if (entity == null) {
                return new byte[0];
            }
            if (entity instanceof byte[] bytes) {
                return bytes;
            }
            return objectMapper.writeValueAsBytes(entity);
        } catch (Exception e) {
            logger.warn(failureMessage, e);
            return null;
        }
    }
}
