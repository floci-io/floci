package io.github.hectorvent.floci.services.secretsmanager;

import jakarta.ws.rs.core.Response;

public class SecretsManagerJsonHandler {

    public Response dispatch(SecretsManagerRequest request, String region) {
        String action = request.action();
        return switch (action) {
            case "CreateSecret" -> handleCreateSecret(request, region);
            case "GetSecretValue" -> handleGetSecretValue(request, region);
            case "PutSecretValue" -> handlePutSecretValue(request, region);
            case "DeleteSecret" -> handleDeleteSecret(request, region);
            case "GetRandomPassword" -> handleGetRandomPassword(request, region);
            default -> Response.status(400).build();
        };
    }
}
