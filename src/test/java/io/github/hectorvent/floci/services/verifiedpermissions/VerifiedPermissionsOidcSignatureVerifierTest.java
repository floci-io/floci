package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void unknownKidsTriggerAtMostOneRateLimitedRefresh() throws Exception {
        RSAPublicKey key = org.mockito.Mockito.mock(RSAPublicKey.class);
        class CountingVerifier extends VerifiedPermissionsOidcSignatureVerifier {
            int fetches;

            CountingVerifier() {
                super(new ObjectMapper(), SystemDefaultDnsResolver.INSTANCE);
            }

            @Override
            Map<String, RSAPublicKey> fetchJwks(String issuer) {
                fetches++;
                return Map.of("known", key);
            }
        }

        try (CountingVerifier verifier = new CountingVerifier()) {
            assertSame(key, verifier.resolveKey("https://issuer.example.com", "known"));
            assertNull(verifier.resolveKey("https://issuer.example.com", "missing-one"));
            assertNull(verifier.resolveKey("https://issuer.example.com", "missing-two"));
            assertEquals(2, verifier.fetches);
        }
    }

    @Test
    void unknownKidRefreshAllowsRoutineKeyRotation() throws Exception {
        RSAPublicKey oldKey = org.mockito.Mockito.mock(RSAPublicKey.class);
        RSAPublicKey newKey = org.mockito.Mockito.mock(RSAPublicKey.class);
        class RotatingVerifier extends VerifiedPermissionsOidcSignatureVerifier {
            int fetches;

            RotatingVerifier() {
                super(new ObjectMapper(), SystemDefaultDnsResolver.INSTANCE);
            }

            @Override
            Map<String, RSAPublicKey> fetchJwks(String issuer) {
                fetches++;
                if (fetches == 1) {
                    return Map.of("old", oldKey);
                }
                return Map.of("old", oldKey, "new", newKey);
            }
        }

        try (RotatingVerifier verifier = new RotatingVerifier()) {
            assertSame(oldKey, verifier.resolveKey("https://issuer.example.com", "old"));
            assertSame(newKey, verifier.resolveKey("https://issuer.example.com", "new"));
            assertEquals(2, verifier.fetches);
        }
    }

    @Test
    void failedUnknownKidRefreshDoesNotStartCooldown() throws Exception {
        RSAPublicKey oldKey = org.mockito.Mockito.mock(RSAPublicKey.class);
        RSAPublicKey newKey = org.mockito.Mockito.mock(RSAPublicKey.class);
        class RecoveringVerifier extends VerifiedPermissionsOidcSignatureVerifier {
            int fetches;

            RecoveringVerifier() {
                super(new ObjectMapper(), SystemDefaultDnsResolver.INSTANCE);
            }

            @Override
            Map<String, RSAPublicKey> fetchJwks(String issuer) {
                fetches++;
                if (fetches == 1) {
                    return Map.of("old", oldKey);
                }
                if (fetches == 2) {
                    throw new VerificationException("temporary JWKS failure");
                }
                return Map.of("old", oldKey, "new", newKey);
            }
        }

        try (RecoveringVerifier verifier = new RecoveringVerifier()) {
            assertSame(oldKey, verifier.resolveKey("https://issuer.example.com", "old"));
            assertThrows(VerifiedPermissionsOidcSignatureVerifier.VerificationException.class,
                    () -> verifier.resolveKey("https://issuer.example.com", "new"));
            assertSame(newKey, verifier.resolveKey("https://issuer.example.com", "new"));
            assertEquals(3, verifier.fetches);
        }
    }
}
