package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.sns.model.SnsSigningKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.x500.X500Name;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Signs SNS deliveries the way AWS does, so a consumer can verify them with the standard SNS
 * signature check instead of switching verification off locally.
 *
 * <p>The canonical string is the documented {@code Name\nValue\n} sequence over the fields of the
 * message type, signed with SHA1withRSA ({@code SignatureVersion} 1) or SHA256withRSA (2). The
 * certificate is a self-signed RSA 2048 certificate served at
 * {@code {base-url}/_aws/sns/SimpleNotificationService-<fingerprint>.pem}. The file name carries
 * the certificate's fingerprint, as AWS's does, so a new key always gets a new URL and a consumer
 * that caches certificates by URL can never verify against a stale one.
 */
@ApplicationScoped
public class SnsMessageSigner {

    private static final Logger LOG = Logger.getLogger(SnsMessageSigner.class);

    static final String CERTIFICATE_PATH = "/_aws/sns/";
    static final String CERTIFICATE_PREFIX = "SimpleNotificationService-";
    static final String CERTIFICATE_SUFFIX = ".pem";
    static final String SIGNATURE_VERSION_1 = "1";
    static final String SIGNATURE_VERSION_2 = "2";
    private static final String STORE_KEY = "signing-key";
    private static final String COMMON_NAME = "CN=Floci SNS Signing";
    private static final int VALIDITY_DAYS = 3650;
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final AccountAwareStorageBackend<SnsSigningKey> keyStore;
    private final String storeAccountId;
    private final CertificateGenerator certificateGenerator;
    private final String baseUrl;
    private volatile Material material;

    private record Material(SnsSigningKey stored, PrivateKey privateKey, String fileName) {}

    @Inject
    public SnsMessageSigner(StorageFactory storageFactory, EmulatorConfig config,
                            CertificateGenerator certificateGenerator) {
        this(storageFactory.create("sns", "sns-signing-keys.json",
                        new TypeReference<Map<String, SnsSigningKey>>() {
                        }),
                config.defaultAccountId(), certificateGenerator, config.effectiveBaseUrl());
    }

    SnsMessageSigner(AccountAwareStorageBackend<SnsSigningKey> keyStore, String storeAccountId,
                     CertificateGenerator certificateGenerator, String baseUrl) {
        this.keyStore = keyStore;
        this.storeAccountId = storeAccountId;
        this.certificateGenerator = certificateGenerator;
        this.baseUrl = baseUrl;
    }

    /** A signer with its own in-memory key, for services built outside CDI. */
    static SnsMessageSigner inMemory(String baseUrl) {
        String accountId = "000000000000";
        return new SnsMessageSigner(AccountAwareStorageBackend.inMemory(accountId), accountId,
                new CertificateGenerator(), baseUrl);
    }

    /** {@code 2} when the topic asks for SHA256withRSA, otherwise AWS's default {@code 1}. */
    static String normalizeSignatureVersion(String topicSignatureVersion) {
        return SIGNATURE_VERSION_2.equals(topicSignatureVersion) ? SIGNATURE_VERSION_2 : SIGNATURE_VERSION_1;
    }

    /**
     * Appends {@code SignatureVersion}, {@code Signature} and the certificate URL field to a
     * message whose canonical fields are already set. {@code certUrlField} is
     * {@code SigningCertURL} for HTTP and SQS envelopes, {@code SigningCertUrl} in Lambda records.
     */
    public void sign(ObjectNode node, String signatureVersion, String certUrlField) {
        String version = normalizeSignatureVersion(signatureVersion);
        Material current = material();
        node.put("SignatureVersion", version);
        node.put("Signature", signature(canonicalString(node), version, current.privateKey()));
        node.put(certUrlField, baseUrl + CERTIFICATE_PATH + current.fileName());
    }

    /** The absolute URL of the certificate deliveries are currently signed with. */
    public String certificateUrl() {
        return baseUrl + CERTIFICATE_PATH + material().fileName();
    }

    /** The PEM certificate when {@code fileName} names the current one, otherwise empty. */
    public Optional<String> certificatePem(String fileName) {
        Material current = material();
        if (current.fileName().equals(fileName)) {
            return Optional.of(current.stored().getCertificatePem());
        }
        return Optional.empty();
    }

    static String canonicalString(JsonNode node) {
        String type = node.path("Type").asText("");
        List<String> fields = "Notification".equals(type) ? NOTIFICATION_FIELDS : CONFIRMATION_FIELDS;
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            canonical.append(field).append('\n').append(value.asText()).append('\n');
        }
        return canonical.toString();
    }

    static String signatureAlgorithm(String signatureVersion) {
        return SIGNATURE_VERSION_2.equals(signatureVersion) ? "SHA256withRSA" : "SHA1withRSA";
    }

    private static String signature(String canonical, String version, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(signatureAlgorithm(version));
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign SNS message", e);
        }
    }

    private Material material() {
        Material current = material;
        if (current != null) {
            if (keyStore.getForAccount(storeAccountId, STORE_KEY).isEmpty()) {
                keyStore.putForAccount(storeAccountId, STORE_KEY, current.stored());
            }
            return current;
        }
        synchronized (this) {
            if (material == null) {
                material = loadOrCreate();
            }
            return material;
        }
    }

    private Material loadOrCreate() {
        Optional<SnsSigningKey> stored = keyStore.getForAccount(storeAccountId, STORE_KEY);
        if (stored.isPresent()) {
            try {
                Material loaded = toMaterial(stored.get());
                LOG.debugv("Using stored SNS signing certificate {0}", loaded.fileName());
                return loaded;
            } catch (Exception e) {
                LOG.warnv("Stored SNS signing key is unusable ({0}); generating a new one", e.getMessage());
            }
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            X500Name name = new X500Name(COMMON_NAME);
            X509Certificate certificate = certificateGenerator.signCertificate(name, keyPair.getPublic(), name,
                    keyPair.getPrivate(), List.of(), false, null, VALIDITY_DAYS);
            SnsSigningKey key = new SnsSigningKey(certificateGenerator.toPem(certificate),
                    certificateGenerator.toPem(keyPair.getPrivate()));
            Material created = toMaterial(key);
            keyStore.putForAccount(storeAccountId, STORE_KEY, key);
            LOG.infov("Generated SNS signing certificate {0}", created.fileName());
            return created;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the SNS signing key", e);
        }
    }

    private static Material toMaterial(SnsSigningKey key) throws Exception {
        X509Certificate certificate = Pem.parseCertificate(key.getCertificatePem());
        PrivateKey privateKey = Pem.parsePrivateKey(key.getPrivateKeyPem());
        if (!Pem.isPair(privateKey, certificate.getPublicKey())) {
            throw new IllegalStateException("private key does not match the certificate");
        }
        return new Material(key, privateKey, CERTIFICATE_PREFIX + fingerprint(certificate) + CERTIFICATE_SUFFIX);
    }

    private static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot fingerprint the SNS signing certificate", e);
        }
    }
}
