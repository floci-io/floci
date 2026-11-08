package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftInternalAuthTest {

    @Test
    void issuedTokenIsValidOnceAndSingleUse() {
        String token = RedshiftInternalAuth.issueDataApiToken();
        assertNotNull(token);
        assertTrue(token.startsWith("floci-redshift-data:"));

        // First consume must succeed
        assertTrue(RedshiftInternalAuth.consumeDataApiToken(token));

        // Replay of the same token must fail
        assertFalse(RedshiftInternalAuth.consumeDataApiToken(token));
    }

    @Test
    void unauthenticatedOrMalformedTokensAreRejected() {
        assertFalse(RedshiftInternalAuth.consumeDataApiToken(null));
        assertFalse(RedshiftInternalAuth.consumeDataApiToken(""));
        assertFalse(RedshiftInternalAuth.consumeDataApiToken("floci-redshift-data"));
        assertFalse(RedshiftInternalAuth.consumeDataApiToken("floci-redshift-data:unknown-uuid"));
        assertFalse(RedshiftInternalAuth.consumeDataApiToken("other-app:token"));
    }
}
