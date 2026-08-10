package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Sink for "this custom resource is still making progress" signals.
 *
 * <p>Lives here rather than in the cloudformation package so the Lambda service can report liveness
 * without depending on CloudFormation: the dependency already runs the other way (the provisioner
 * invokes Lambdas), and pointing it back would make the two packages mutually dependent.
 *
 * <p>The signal is observable generically because the provisioner embeds its correlation token in
 * the {@code ResponseURL} it puts on every event, and the CDK provider framework echoes that same
 * event into each {@code framework.isComplete} poll. So a poll arriving at the Lambda service is
 * proof of progress on a specific pending resource, with nothing here needing to know what the
 * resource does.
 */
public interface CustomResourceLiveness {

    /** Path segment the provisioner uses for its callback URL; the token follows it. */
    String RESPONSE_PATH = "/cfn-response/";

    /** Records progress for {@code token}. Unknown tokens are ignored. */
    void touch(String token);

    /**
     * Extracts the callback token from an invoke payload, if it carries one.
     *
     * @return the token, or empty for the overwhelming majority of invokes, which are ordinary
     *         Lambda calls with no custom-resource event in them
     */
    static Optional<String> tokenIn(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        String body = new String(payload, StandardCharsets.UTF_8);
        int start = body.indexOf(RESPONSE_PATH);
        if (start < 0) {
            return Optional.empty();
        }
        start += RESPONSE_PATH.length();
        int end = start;
        // The token is a UUID; stop at whatever JSON punctuation closes the URL.
        while (end < body.length() && (Character.isLetterOrDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        return end > start ? Optional.of(body.substring(start, end)) : Optional.empty();
    }
}
