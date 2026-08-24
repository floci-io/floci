package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One attempt-keyed entry of a mocked service integration response, parsed from the
 * Step Functions Local {@code MockConfigFile.json} format. The attempt key {@code "0"}
 * becomes the range 0..0 and {@code "1-2"} becomes 1..2. Exactly one of
 * {@code returnResult} (a {@code Return} payload) or {@code errorName}/{@code errorCause}
 * (a {@code Throw}) is set.
 */
public record MockedResponseStep(
        int fromAttempt,
        int toAttempt,
        JsonNode returnResult,
        String errorName,
        String errorCause) {

    public boolean covers(int attempt) {
        return attempt >= fromAttempt && attempt <= toAttempt;
    }

    public boolean isThrow() {
        return errorName != null;
    }
}
