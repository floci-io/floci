package io.github.hectorvent.floci.services.lambda.durable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The opaque CheckpointToken and CallbackId of the durable execution protocol, standard base64 as
 * AWS documents them. A CallbackId carries its execution ARN, which IAM authorizes against.
 */
public final class DurableTokens {

    private static final String SEPARATOR = "|";
    private static final Pattern CALLBACK_ID_PATTERN = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final int MAX_CALLBACK_ID_LENGTH = 1024;

    private DurableTokens() {
    }

    record CheckpointToken(String executionArn, String invocationId, long sequence) {
    }

    record CallbackId(String executionArn, String operationId) {
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

    /** The nonce keeps an id from matching a later callback that reuses the operation id. */
    static String callbackId(String executionArn, String operationId) {
        return encode(executionArn + SEPARATOR + operationId + SEPARATOR + UUID.randomUUID());
    }

    static Optional<CallbackId> parseCallbackId(String callbackId) {
        if (callbackId == null || callbackId.length() > MAX_CALLBACK_ID_LENGTH
                || !CALLBACK_ID_PATTERN.matcher(callbackId).matches()) {
            return Optional.empty();
        }
        String decoded = decode(callbackId);
        String[] parts = decoded == null ? new String[0] : decoded.split("\\" + SEPARATOR, -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CallbackId(parts[0], parts[1]));
    }

    public static Optional<String> callbackExecutionArn(String callbackId) {
        return parseCallbackId(callbackId).map(CallbackId::executionArn);
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
