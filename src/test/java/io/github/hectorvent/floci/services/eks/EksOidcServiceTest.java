package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksOidcServiceTest {

    private static final String ISSUER =
            "https://oidc.eks.us-east-1.amazonaws.com/id/ABCDEF0123456789ABCDEF0123456789";
    private static final String CLUSTER = "irsa-test-cluster";

    private EksOidcService oidcService;

    @BeforeEach
    void setUp() {
        oidcService = new EksOidcService(new StorageFactory(null, null) {
            @Override
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return new InMemoryStorage<>();
            }
        }, new ObjectMapper());
    }

    private String decodePayload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.", -1)[1]));
    }

    @Test
    void newIssuerUrlIsAwsShapedAndUnique() {
        String first = oidcService.newIssuerUrl("us-east-1");
        String second = oidcService.newIssuerUrl("us-east-1");

        assertTrue(first.matches("https://oidc\\.eks\\.us-east-1\\.amazonaws\\.com/id/[A-F0-9]{32}"),
                "unexpected issuer shape: " + first);
        assertNotEquals(first, second);
        assertTrue(oidcService.newIssuerUrl("eu-west-2").contains("oidc.eks.eu-west-2."));
    }

    @Test
    void ensureKeyIsIdempotentForTheSameIssuer() {
        ClusterOidcKey first = oidcService.ensureKey(CLUSTER, ISSUER);
        ClusterOidcKey second = oidcService.ensureKey(CLUSTER, ISSUER);

        assertEquals(first.getKeyId(), second.getKeyId());
        assertEquals(first.getPrivateKey(), second.getPrivateKey());
    }

    @Test
    void ensureKeyRotatesWhenTheIssuerChanges() {
        ClusterOidcKey original = oidcService.ensureKey(CLUSTER, ISSUER);
        ClusterOidcKey rotated = oidcService.ensureKey(CLUSTER,
                "https://oidc.eks.us-east-1.amazonaws.com/id/0000000000000000000000000000BEEF");

        assertNotEquals(original.getPrivateKey(), rotated.getPrivateKey());
        assertTrue(oidcService.findVerificationKey(ISSUER).isEmpty());
    }

    @Test
    void findVerificationKeyResolvesAcrossAccounts() {
        // STS must resolve an issuer regardless of which account owns the cluster: the caller need
        // not be the owner, and an unresolved issuer is treated as a third-party provider — so the
        // token would be accepted with no validation at all.
        StorageBackend<String, ClusterOidcKey> raw = new InMemoryStorage<>();
        var accountAware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        EksOidcService service = new EksOidcService(new StorageFactory(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return (StorageBackend<String, V>) accountAware;
            }
        }, new ObjectMapper());

        service.ensureKeyForAccount("999999999999", CLUSTER, ISSUER);

        // The lookup runs outside any request context, so it resolves to the default account.
        assertTrue(service.findVerificationKey(ISSUER).isPresent());
    }

    @Test
    void findVerificationKeyResolvesOnlyKnownIssuers() {
        oidcService.ensureKey(CLUSTER, ISSUER);

        assertTrue(oidcService.findVerificationKey(ISSUER).isPresent());
        assertTrue(oidcService.findVerificationKey("https://accounts.google.com").isEmpty());
        assertTrue(oidcService.findVerificationKey(null).isEmpty());
        assertTrue(oidcService.findVerificationKey("").isEmpty());
    }

    @Test
    void deleteKeyRemovesSigningMaterial() {
        oidcService.ensureKey(CLUSTER, ISSUER);
        oidcService.deleteKey(CLUSTER);

        assertTrue(oidcService.findKeyByCluster(CLUSTER).isEmpty());
        assertTrue(oidcService.findVerificationKey(ISSUER).isEmpty());
    }

    @Test
    void mintedTokenCarriesKubernetesServiceAccountClaims() {
        oidcService.ensureKey(CLUSTER, ISSUER);
        String payload = decodePayload(oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "my-namespace", "my-service-account", null, null));

        assertTrue(payload.contains("\"sub\":\"system:serviceaccount:my-namespace:my-service-account\""));
        assertTrue(payload.contains("\"kubernetes.io\""));
        assertTrue(payload.contains("\"namespace\":\"my-namespace\""));
        assertTrue(payload.contains("\"name\":\"my-service-account\""));
    }

    @Test
    void mintRejectsMissingNamespaceOrServiceAccount() {
        oidcService.ensureKey(CLUSTER, ISSUER);

        assertThrows(AwsException.class, () -> oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, null, "my-service-account", null, null));
        assertThrows(AwsException.class, () -> oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "  ", "my-service-account", null, null));
        assertThrows(AwsException.class, () -> oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "my-namespace", null, null, null));
        assertThrows(AwsException.class, () -> oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "my-namespace", "  ", null, null));
    }

    @Test
    void mintClampsLifetimeToTheMaximum() {
        oidcService.ensureKey(CLUSTER, ISSUER);
        long now = System.currentTimeMillis() / 1000;

        String payload = decodePayload(oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "my-namespace", "my-service-account", null, Integer.MAX_VALUE));

        long exp = Long.parseLong(payload.replaceAll(".*\"exp\":(\\d+).*", "$1"));
        assertTrue(exp <= now + 604800, "expiry was not clamped to 7 days: " + exp);
    }

    @Test
    void namespaceWithControlCharactersStillProducesAParseableToken() {
        // Claims are serialized with Jackson, so a newline is escaped rather than breaking the JSON.
        oidcService.ensureKey(CLUSTER, ISSUER);
        String payload = decodePayload(oidcService.mintServiceAccountToken(
                CLUSTER, ISSUER, "bad\nnamespace\"quote", "my-service-account", null, null));

        assertFalse(payload.contains("\n"), "raw newline leaked into the JWT payload");
        assertTrue(payload.contains("\\n"));
    }
}
