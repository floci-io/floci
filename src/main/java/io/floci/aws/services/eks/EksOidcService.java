package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CompactJws;
import io.floci.aws.core.common.OidcIssuerKeyLookup;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.model.ClusterOidcKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
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

    public static final String STS_AUDIENCE = "sts.amazonaws.com"; // partition-literal: web-identity audience; no source outside the commercial partition (P9)
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
        return "https://" + AwsEndpoints.host("oidc.eks", region) + "/id/" + id;
    }

    /**
     * Generates and stores the cluster's signing keypair if absent, returning the stored material.
     * Keyed by the cluster's storage key ({@link EksService#clusterKey(String, String)}), which
     * carries its Region, so {@code DeleteCluster} can drop it and same-named clusters in two
     * Regions each keep their own key.
     */
    public synchronized ClusterOidcKey ensureKey(String clusterKey, String issuer) {
        return ensureKey(null, clusterKey, issuer);
    }

    /**
     * As {@link #ensureKey}, but scoped to an explicit account. Startup and other work running
     * without a request context must pass the owning account from the cluster record, since the
     * request-scoped store would otherwise resolve to the default account.
     */
    public synchronized ClusterOidcKey ensureKeyForAccount(String accountId, String clusterKey,
                                                           String issuer) {
        return ensureKey(accountId, clusterKey, issuer);
    }

    private ClusterOidcKey ensureKey(String accountId, String clusterKey, String issuer) {
        Optional<ClusterOidcKey> existing = readKey(accountId, clusterKey);
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
            writeKey(accountId, clusterKey, key);
            LOG.debugv("Generated IRSA OIDC signing key for EKS cluster {0}", clusterKey);
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate EKS OIDC signing keypair for cluster "
                    + clusterKey + ": RSA unavailable", e);
        }
    }

    private Optional<ClusterOidcKey> readKey(String accountId, String clusterKey) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            return aware.getForAccount(accountId, clusterKey);
        }
        return keyStore.get(clusterKey);
    }

    private void writeKey(String accountId, String clusterKey, ClusterOidcKey key) {
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.putForAccount(accountId, clusterKey, key);
            return;
        }
        keyStore.put(clusterKey, key);
    }

    public void deleteKey(String clusterKey) {
        keyStore.delete(clusterKey);
    }

    /**
     * Moves the signing key of {@code accountId}'s cluster from {@code legacyKey}, the bare cluster
     * name keys were stored under before cluster names became Regional, to {@code clusterKey}. Only
     * a key minted for {@code issuer} moves, so a same-named cluster's key is never taken over.
     * Keeping the key, rather than minting a new one, keeps tokens already issued verifiable. A key
     * stored before keys were account-prefixed moves too; the issuer, a random URL minted for one
     * cluster, is what proves it belongs to this one.
     */
    public synchronized void migrateLegacyKey(String accountId, String legacyKey, String clusterKey,
                                              String issuer) {
        if (legacyKey.equals(clusterKey) || issuer == null) {
            return;
        }
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.getForAccountMigratingLegacyKeys(accountId, clusterKey, List.of(legacyKey),
                    key -> issuer.equals(key.getIssuer()));
            return;
        }
        Optional<ClusterOidcKey> legacy = readKey(accountId, legacyKey);
        if (legacy.isEmpty() || !issuer.equals(legacy.get().getIssuer())) {
            return;
        }
        if (readKey(accountId, clusterKey).isEmpty()) {
            writeKey(accountId, clusterKey, legacy.get());
        }
        if (accountId != null && keyStore instanceof AccountAwareStorageBackend<ClusterOidcKey> aware) {
            aware.deleteForAccount(accountId, legacyKey);
        } else {
            keyStore.delete(legacyKey);
        }
    }

    public Optional<ClusterOidcKey> findKeyByCluster(String clusterKey) {
        return keyStore.get(clusterKey);
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
    public String mintServiceAccountToken(String clusterKey, String issuer, String namespace,
                                          String serviceAccount, String audience, Integer lifetimeSeconds) {
        if (namespace == null || namespace.isBlank()) {
            throw new AwsException("InvalidParameterException", "namespace is required", 400);
        }
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new AwsException("InvalidParameterException", "serviceAccount is required", 400);
        }
        int lifetime = resolveLifetime(lifetimeSeconds);
        String aud = audience == null || audience.isBlank() ? STS_AUDIENCE : audience;

        ClusterOidcKey key = ensureKey(clusterKey, issuer);
        long now = Instant.now().getEpochSecond();
        long exp = now + lifetime;
        String subject = "system:serviceaccount:" + namespace + ":" + serviceAccount;

        return CompactJws.sign(writeJson(buildHeader(key.getKeyId())),
                writeJson(buildClaims(issuer, aud, subject, namespace, serviceAccount, now, exp)),
                toPrivateKey(key.getPrivateKey()), "SHA256withRSA");
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

    /**
     * Exports the cluster's RSA signing key in PKCS#8 PEM format for the k3s API server's
     * {@code --service-account-signing-key-file} argument.
     */
    public String exportSigningKeyPem(ClusterOidcKey key) {
        if (key == null || key.getPrivateKey() == null) {
            throw new IllegalArgumentException("ClusterOidcKey and its private key must not be null");
        }
        return toPem("PRIVATE KEY", key.getPrivateKey());
    }

    /**
     * Exports the cluster's RSA public key in X.509 PEM format for the k3s API server's
     * {@code --service-account-key-file} argument.
     */
    public String exportPublicKeyPem(ClusterOidcKey key) {
        if (key == null || key.getPublicKey() == null) {
            throw new IllegalArgumentException("ClusterOidcKey and its public key must not be null");
        }
        return toPem("PUBLIC KEY", key.getPublicKey());
    }

    private static String toPem(String type, String base64) {
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        pem.append("-----END ").append(type).append("-----\n");
        return pem.toString();
    }
}
