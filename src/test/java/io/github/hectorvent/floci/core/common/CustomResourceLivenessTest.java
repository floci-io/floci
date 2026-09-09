package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token extraction is the one part of the heartbeat that can fail silently: if it never matches, the
 * idle budget simply never resets and the original fixed-timeout bug is back, invisibly. So the
 * payloads here are shaped like the ones actually observed on the wire rather than invented.
 */
class CustomResourceLivenessTest {

    private static final String TOKEN = "6ef77fe2-38c8-41b0-bec6-68879687fbc8";

    private Optional<String> tokenIn(String payload) {
        return CustomResourceLiveness.tokenIn(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void findsTheTokenInAProviderFrameworkPoll() {
        // Verbatim shape of the isComplete poll captured from a Prepare-stage run.
        String payload = "{\"RequestType\":\"Create\","
                + "\"ServiceToken\":\"arn:aws:lambda:us-east-1:000000000000:function:framework-onEvent\","
                + "\"ResponseURL\":\"http://localhost.floci.io:4566/cfn-response/" + TOKEN + "\","
                + "\"StackId\":\"arn:aws:cloudformation:us-east-1:000000000000:stack/AWSAccelerator-PrepareStack/abc\","
                + "\"RequestId\":\"req-1\",\"LogicalResourceId\":\"CreateAccounts\","
                + "\"ResourceType\":\"Custom::CreateOrganizationAccounts\","
                + "\"ResourceProperties\":{\"ServiceToken\":\"arn:aws:lambda:...\",\"uuid\":\"72f7b8a8\"}}";

        assertEquals(Optional.of(TOKEN), tokenIn(payload));
    }

    @Test
    void findsTheTokenWhenTheUrlIsTheLastFieldWithNoTrailingPunctuation() {
        assertEquals(Optional.of(TOKEN),
                tokenIn("http://localhost.floci.io:4566/cfn-response/" + TOKEN));
    }

    @Test
    void findsTheTokenAcrossJsonWhitespaceAndSingleQuotedRenderings() {
        assertEquals(Optional.of(TOKEN),
                tokenIn("{ \"ResponseURL\" : 'http://host:4566/cfn-response/" + TOKEN + "' }"));
    }

    @Test
    void ignoresOrdinaryInvokesThatCarryNoCallbackUrl() {
        assertTrue(tokenIn("{\"Records\":[{\"body\":\"hello\"}]}").isEmpty());
        assertTrue(CustomResourceLiveness.tokenIn(new byte[0]).isEmpty());
        assertTrue(CustomResourceLiveness.tokenIn(null).isEmpty());
    }

    @Test
    void ignoresATruncatedUrlWithNoTokenAfterThePath() {
        // The framework's own logging redacts ResponseURL to "..."; that rendering must not produce
        // a bogus token that would touch nothing and mask the real signal.
        assertTrue(tokenIn("{\"ResponseURL\":\"...\"}").isEmpty());
        assertTrue(tokenIn("http://host:4566/cfn-response/").isEmpty());
    }
}
