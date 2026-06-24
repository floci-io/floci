package io.github.hectorvent.floci.services.iot.model;

import java.util.List;

/**
 * Parsed response from a custom-authorizer Lambda.
 *
 * @param isAuthenticated         whether the device is allowed to connect
 * @param principalId             principal identifier returned by the authorizer
 * @param policyDocuments         raw IAM-style policy documents (JSON strings) to evaluate for pub/sub
 * @param disconnectAfterSeconds  connection lifetime hint (nullable)
 * @param refreshAfterSeconds     policy refresh hint (nullable)
 */
public record AuthorizerResult(
        boolean isAuthenticated,
        String principalId,
        List<String> policyDocuments,
        Integer disconnectAfterSeconds,
        Integer refreshAfterSeconds) {
}
