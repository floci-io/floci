package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Cognito Identity (federated identity pools) management plane.
 *
 * <p>Operations that are not implemented fall through to a clean
 * {@code UnknownOperationException} rather than a stub success, so callers fail fast
 * instead of stranding a waiter.
 */
@ApplicationScoped
public class CognitoIdentityJsonHandler {

    public Response handle(String action, JsonNode request, String region) throws Exception {
        throw new AwsException("UnknownOperationException",
                "Operation " + action + " is not supported by floci", 400);
    }
}
