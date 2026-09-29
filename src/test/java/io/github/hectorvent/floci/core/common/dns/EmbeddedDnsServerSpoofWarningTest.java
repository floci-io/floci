package io.github.hectorvent.floci.core.common.dns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedDnsServerSpoofWarningTest {

    @Test
    void warnsWhenSpoofIsOnTlsIsOnAndCertificateCannotSeeTheFlag() {
        // Catches: no warning when DNS spoofs amazonaws.com but the certificate has no AWS SANs
        assertTrue(EmbeddedDnsServer.shouldWarnSpoofInvisibleToTls(true, true, false));
    }

    @Test
    void staysQuietWhenCertificateSeesTheFlag() {
        // Catches: warning even though the certificate already carries the AWS SANs
        assertFalse(EmbeddedDnsServer.shouldWarnSpoofInvisibleToTls(true, true, true));
    }

    @Test
    void staysQuietWhenTlsIsOff() {
        // Catches: warning about certificate SANs when there is no TLS at all
        assertFalse(EmbeddedDnsServer.shouldWarnSpoofInvisibleToTls(true, false, false));
    }

    @Test
    void staysQuietWhenSpoofIsOff() {
        // Catches: warning when the spoof flag is not enabled
        assertFalse(EmbeddedDnsServer.shouldWarnSpoofInvisibleToTls(false, true, false));
    }
}
