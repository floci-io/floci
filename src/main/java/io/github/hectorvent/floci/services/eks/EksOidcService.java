package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.OidcIssuerKeyLookup;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.ClusterOidcKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the RSA signing material behind each EKS cluster's IRSA OIDC issuer, mints service-account
 * tokens for it, and resolves issuer URLs back to a verification key for STS.
 *
 * <p>The issuer URL is AWS-shaped ({@code https://oidc.eks.<region>.amazonaws.com/id/<id>}) and
 * deliberately cosmetic: nothing dereferences it. Real IRSA has STS fetch the JWKS, but Floci's STS
 * resolves the key in-process through {@link OidcIssuerKeyLookup}, so the URL only has to be a
 * faithful string for trust-policy construction.
 */
@ApplicationScoped
public class EksOidcService implements OidcIssuerKeyLookup {

    private static final Logger LOG = Logger.getLogger(EksOidcService.class);

    public static final String STS_AUDIENCE = "sts.amazonaws.com";
    private static final int DEFAULT_TOKEN_LIFETIME_SECONDS = 86400;
    private static final int MAX_TOKEN_LIFETIME_SECONDS = 604800;

    private final StorageBackend<String, ClusterOidcKey> keyStore;
    private final ObjectMapper objectMapper;

    @Inject
    public EksOidcService(StorageFactory storageFactory, ObjectMapper objectMapper) {
        this.keyStore = storageFactory.create("eks", "eks-oidc-keys.json",
                new TypeReference<Map<String, ClusterOidcKey>>() {
                });
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the issuer URL for a newly created cluster. The random id mirrors the 32-hex-character
     * identifier real EKS embeds in the issuer path.
     */
    public String newIssuerUrl(String region) {
        String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return "https://oidc.eks." + region + ".amazonaws.com/id/" + id;
    }

    /**
     * Generates and stores the cluster's signing keypair if absent, returning the stored material.
     * Keyed by cluster name so {@code DeleteCluster} can drop it.
     */
    public synchronized ClusterOidcKey ensureKey(String clusterName, String issuer) {
        return ensureKey(null, clusterName, issuer);
    }

    /**
     * As {@link #ensureKey}, but scoped to an explicit account. Startup and other work running
     * without a request context must pass the owning account from the cluster record, since the
     * request-scoped store would otherwise resolve to the default account.
     */
    public synchronized ClusterOidcKey ensureKeyForAccount(String accountId, String clusterName,
                                                           String issuer) {
        return ensureKey(accountId, clusterName, issuer);
    }

    private ClusterOidcKey ensureKey(String accountId, String clusterName, String issuer) {
        Optional<ClusterOidcKey> existing = readKey(accountId, clusterName);
        if (existing.isPresent() && issuer.equals(existing.get().getIssuer())) {
            return existing.get();
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            ClusterOidcKey key = new ClusterOidcKey(
                    issuer,
                    UUID.randomUUID().toString(),
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
            writeKey(accountId, clusterName, key);
            LOG.debugv("Generated IRSA OIDC signing key for EKS cluster {0}", clusterName);
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate EKS OIDC signing keypair for cluster "
                    + clusterName + ": RSA unavailable", e);
        }
    }

    private Optional<ClusterOidcKey> readKey(String accountId, String clusterName) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            return aware.getForAccount(accountId, clusterName);
        }
        return keyStore.get(clusterName);
    }

    private void writeKey(String accountId, String clusterName, ClusterOidcKey key) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.putForAccount(accountId, clusterName, key);
            return;
        }
        keyStore.put(clusterName, key);
    }

    public void deleteKey(String clusterName) {
        keyStore.delete(clusterName);
    }

    public Optional<ClusterOidcKey> findKeyByCluster(String clusterName) {
        return keyStore.get(clusterName);
    }

    @Override
    public Optional<RSAPublicKey> findVerificationKey(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return Optional.empty();
        }
        // Scans every account, not just the caller's. An issuer URL is globally unique, and the
        // account calling AssumeRoleWithWebIdentity need not be the one that owns the cluster.
        // Account-scoped scanning would leave the key unfound, and an unfound issuer is treated as
        // a third-party provider, so the token would be accepted with no validation at all.
        return allKeys().stream()
                .filter(key -> issuer.equals(key.getIssuer()))
                .findFirst()
                .map(key -> toPublicKey(key.getPublicKey()));
    }

    private List<ClusterOidcKey> allKeys() {
        if (keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            return aware.scanAllAccounts();
        }
        return keyStore.scan(k -> true);
    }

    /**
     * Mints an RS256-signed service-account token for {@code namespace}/{@code serviceAccount},
     * shaped like the projected token a pod would present to STS.
     */
    public String mintServiceAccountToken(String clusterName, String issuer, String namespace,
                                          String serviceAccount, String audience, Integer lifetimeSeconds) {
        if (namespace == null || namespace.isBlank()) {
            throw new AwsException("InvalidParameterException", "namespace is required", 400);
        }
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new AwsException("InvalidParameterException", "serviceAccount is required", 400);
        }
        int lifetime = resolveLifetime(lifetimeSeconds);
        String aud = audience == null || audience.isBlank() ? STS_AUDIENCE : audience;

        ClusterOidcKey key = ensureKey(clusterName, issuer);
        long now = Instant.now().getEpochSecond();
        long exp = now + lifetime;
        String subject = "system:serviceaccount:" + namespace + ":" + serviceAccount;

        String header = base64Url(writeJson(buildHeader(key.getKeyId())));
        String payload = base64Url(writeJson(
                buildClaims(issuer, aud, subject, namespace, serviceAccount, now, exp)));

        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput, toPrivateKey(key.getPrivateKey()));
    }

    private int resolveLifetime(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_TOKEN_LIFETIME_SECONDS;
        }
        return Math.min(requested, MAX_TOKEN_LIFETIME_SECONDS);
    }

    private ObjectNode buildHeader(String keyId) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", keyId);
        return header;
    }

    private ObjectNode buildClaims(String issuer, String audience, String subject, String namespace,
                                   String serviceAccount, long issuedAt, long expiresAt) {
        ObjectNode claims = objectMapper.createObjectNode();
        claims.put("iss", issuer);
        claims.putArray("aud").add(audience);
        claims.put("sub", subject);
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt);
        claims.put("exp", expiresAt);
        claims.put("jti", UUID.randomUUID().toString());

        ObjectNode kubernetes = claims.putObject("kubernetes.io");
        kubernetes.put("namespace", namespace);
        kubernetes.putObject("serviceaccount").put("name", serviceAccount);
        return claims;
    }

    /**
     * Serializes a claim set to bytes. Jackson handles escaping, including the control characters a
     * hand-rolled escape would miss and which would otherwise produce a structurally invalid token.
     */
    private byte[] writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize EKS OIDC token claims", e);
        }
    }

    private String sign(String signingInput, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EKS OIDC token signing failed", e);
        }
    }

    RSAPublicKey toPublicKey(String encoded) {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getDecoder().decode(encoded));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load EKS OIDC public key", e);
        }
    }

    private PrivateKey toPrivateKey(String encoded) {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded));
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load EKS OIDC private key", e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
