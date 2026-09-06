package io.github.hectorvent.floci.services.verifiedpermissions;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedPermissionsOidcSignatureVerifierTest {

    @Test
    void publicIssuerValidationRequiresHttpsAndRejectsLocalNetworkAddresses() throws Exception {
        assertThrows(VerifiedPermissionsOidcSignatureVerifier.VerificationException.class,
                () -> VerifiedPermissionsOidcSignatureVerifier.validatePublicHttpsUri(
                        "http://issuer.example.com", "OIDC issuer"));
        assertDoesNotThrow(() -> VerifiedPermissionsOidcSignatureVerifier.validatePublicHttpsUri(
                "https://issuer.example.com", "OIDC issuer"));
        assertThrows(VerifiedPermissionsOidcSignatureVerifier.VerificationException.class,
                () -> VerifiedPermissionsOidcSignatureVerifier.validatePublicHttpsUri(
                        "https://127.0.0.1", "OIDC issuer"));
        assertThrows(VerifiedPermissionsOidcSignatureVerifier.VerificationException.class,
                () -> VerifiedPermissionsOidcSignatureVerifier.validatePublicHttpsUri(
                        "https://[::1]", "OIDC issuer"));

        assertTrue(VerifiedPermissionsOidcSignatureVerifier.isBlockedPublicAddress(
                InetAddress.getByName("127.0.0.1")));
        assertTrue(VerifiedPermissionsOidcSignatureVerifier.isBlockedPublicAddress(
                InetAddress.getByName("169.254.169.254")));
        assertTrue(VerifiedPermissionsOidcSignatureVerifier.isBlockedPublicAddress(
                InetAddress.getByName("10.0.0.1")));
        assertTrue(VerifiedPermissionsOidcSignatureVerifier.isBlockedPublicAddress(
                InetAddress.getByName("192.168.1.1")));
    }
}
