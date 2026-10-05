package io.github.hectorvent.floci.services.lambda.durable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The opaque CheckpointToken of the durable execution protocol, standard base64 as AWS documents it.
 */
final class DurableTokens {

    private static final String SEPARATOR = "|";

    private DurableTokens() {
    }

    record CheckpointToken(String executionArn, String invocationId, long sequence) {
    }

    static String checkpointToken(String executionArn, String invocationId, long sequence) {
        return encode(executionArn + SEPARATOR + invocationId + SEPARATOR + sequence);
    }

    static Optional<CheckpointToken> parseCheckpointToken(String token) {
        String decoded = decode(token);
        if (decoded == null) {
            return Optional.empty();
        }
        int last = decoded.lastIndexOf(SEPARATOR);
        int middle = last < 0 ? -1 : decoded.lastIndexOf(SEPARATOR, last - 1);
        if (middle < 0) {
            return Optional.empty();
        }
        try {
            long sequence = Long.parseLong(decoded.substring(last + 1));
            return Optional.of(new CheckpointToken(decoded.substring(0, middle),
                    decoded.substring(middle + 1, last), sequence));
        } catch (NumberFormatException expected) {
            // A token Floci did not mint. The caller answers "Invalid checkpoint token" as AWS does.
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException expected) {
            // Not base64, so not a token Floci minted. The caller rejects it.
            return null;
        }
    }
}
