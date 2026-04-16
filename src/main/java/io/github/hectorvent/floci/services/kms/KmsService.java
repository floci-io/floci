package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class KmsService {

    private static final Logger LOG = Logger.getLogger(KmsService.class);

    private final StorageBackend<String, KmsKey> keyStore;
    private final StorageBackend<String, KmsAlias> aliasStore;
    private final RegionResolver regionResolver;

    @Inject
    public KmsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("kms", "kms-keys.json",
                        new TypeReference<Map<String, KmsKey>>() {}),
                storageFactory.create("kms", "kms-aliases.json",
                        new TypeReference<Map<String, KmsAlias>>() {}),
                regionResolver);
    }

    KmsService(StorageBackend<String, KmsKey> keyStore,
               StorageBackend<String, KmsAlias> aliasStore,
               RegionResolver regionResolver) {
        this.keyStore = keyStore;
        this.aliasStore = aliasStore;
        this.regionResolver = regionResolver;
    }

    private static final String DEFAULT_KEY_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Enable IAM User Permissions\"," +
            "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"}," +
            "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";

    public KmsKey createKey(String description, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null, Map.of(), region);
    }

    public KmsKey createKey(String description, String policy, Map<String, String> tags, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", policy, tags, region);
    }

    public KmsKey createKey(String description, String keyUsage, String customerMasterKeySpec, String policy, Map<String, String> tags, String region) {
        String keyId = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn("kms", region, "key/" + keyId);

        KmsKey key = new KmsKey();
        key.setKeyId(keyId);
        key.setArn(arn);
        key.setDescription(description);
        key.setKeyUsage(keyUsage != null ? keyUsage : "ENCRYPT_DECRYPT");
        key.setCustomerMasterKeySpec(customerMasterKeySpec != null ? customerMasterKeySpec : "SYMMETRIC_DEFAULT");
        key.setPolicy(policy != null ? policy : DEFAULT_KEY_POLICY);
        if (tags != null) {
            key.getTags().putAll(tags);
        }

        generateKeyMaterial(key);

        keyStore.put(region + "::" + keyId, key);
        LOG.infov("Created KMS key: {0} ({1}/{2}) in {3}", keyId, key.getKeyUsage(), key.getCustomerMasterKeySpec(), region);
        return key;
    }

    private void generateKeyMaterial(KmsKey key) {
        String spec = key.getCustomerMasterKeySpec();
        if ("SYMMETRIC_DEFAULT".equals(spec)) {
            return; // Use existing mock behavior for symmetric keys
        }

        try {
            KeyPairGenerator generator;
            if (spec.startsWith("RSA_")) {
                generator = KeyPairGenerator.getInstance("RSA");
                int size = Integer.parseInt(spec.substring(4));
                generator.initialize(size);
            } else if (isEcKeySpec(spec)) {
                generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
                String curveName = switch (spec) {
                    case "ECC_NIST_P256" -> "secp256r1";
                    case "ECC_NIST_P384" -> "secp384r1";
                    case "ECC_NIST_P521" -> "secp521r1";
                    case "ECC_SECG_P256K1" -> "secp256k1";
                    default -> throw new AwsException("InvalidCustomerMasterKeySpecException", "Unsupported curve: " + spec, 400);
                };
                generator.initialize(new ECGenParameterSpec(curveName));
            } else {
                throw new AwsException("InvalidCustomerMasterKeySpecException", "Unsupported key spec: " + spec, 400);
            }

            KeyPair pair = generator.generateKeyPair();
            key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            key.setPublicKeyEncoded(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to generate key material: " + e.getMessage(), 500);
        }
    }

    public KmsKey getPublicKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        if ("SYMMETRIC_DEFAULT".equals(key.getCustomerMasterKeySpec())) {
            throw new AwsException("UnsupportedOperationException", "GetPublicKey is not supported for symmetric keys.", 400);
        }
        return key;
    }

    public KmsKey describeKey(String keyId, String region) {
        return resolveKey(keyId, region);
    }

    public List<KmsKey> listKeys(String region) {
        String prefix = region + "::";
        return keyStore.scan(k -> k.startsWith(prefix));
    }

    public void scheduleKeyDeletion(String keyId, int pendingWindowInDays, String region) {
        KmsKey key = resolveKey(keyId, region);
        key.setKeyState("PendingDeletion");
        key.setDeletionDate(Instant.now().plusSeconds((long) pendingWindowInDays * 86400).getEpochSecond());
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    public void cancelKeyDeletion(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        key.setKeyState("Enabled");
        key.setDeletionDate(0);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    public Map<String, Object> getKeyPolicy(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        Map<String, Object> result = new HashMap<>();
        result.put("Policy", key.getPolicy());
        result.put("PolicyName", "default");
        return result;
    }

    public void putKeyPolicy(String keyId, String policy, String region) {
        KmsKey key = resolveKey(keyId, region);
        key.setPolicy(policy);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Updated key policy for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    // ──────────────────────────── Key Rotation ────────────────────────────

    public boolean getKeyRotationStatus(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        validateRotationSupported(key);
        return key.isKeyRotationEnabled();
    }

    public void enableKeyRotation(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        validateRotationSupported(key);
        key.setKeyRotationEnabled(true);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Enabled key rotation for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    public void disableKeyRotation(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        validateRotationSupported(key);
        key.setKeyRotationEnabled(false);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Disabled key rotation for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    private void validateRotationSupported(KmsKey key) {
        if (!"ENCRYPT_DECRYPT".equals(key.getKeyUsage())
                || !"SYMMETRIC_DEFAULT".equals(key.getCustomerMasterKeySpec())) {
            throw new AwsException(
                    "UnsupportedOperationException",
                    "You cannot perform this operation on a non-symmetric key or a key with non-ENCRYPT_DECRYPT key usage.",
                    400);
        }
    }

    // ──────────────────────────── Aliases ────────────────────────────

    public void createAlias(String aliasName, String targetKeyId, String region) {
        if (!aliasName.startsWith("alias/")) {
            throw new AwsException("InvalidAliasNameException", "Alias name must begin with 'alias/'", 400);
        }
        resolveKey(targetKeyId, region); // Validate key exists

        String aliasArn = regionResolver.buildArn("kms", region, aliasName);
        KmsAlias alias = new KmsAlias(aliasName, aliasArn, targetKeyId);
        aliasStore.put(region + "::" + aliasName, alias);
        LOG.infov("Created KMS alias: {0} -> {1}", aliasName, targetKeyId);
    }

    public void deleteAlias(String aliasName, String region) {
        String key = region + "::" + aliasName;
        if (aliasStore.get(key).isEmpty()) {
            throw new AwsException("NotFoundException", "Alias not found", 404);
        }
        aliasStore.delete(key);
    }

    public List<KmsAlias> listAliases(String region) {
        String prefix = region + "::";
        return aliasStore.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Crypto Ops (Mocks) ────────────────────────────

    public byte[] encrypt(String keyId, byte[] plaintext, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        // Local mock: prefix with keyId and base64
        String mock = "kms:" + kmsKey.getKeyId() + ":" + Base64.getEncoder().encodeToString(plaintext);
        return mock.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] decrypt(byte[] ciphertext, String region) {
        String data = new String(ciphertext, StandardCharsets.UTF_8);
        if (!data.startsWith("kms:")) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
        String[] parts = data.split(":", 3);
        if (parts.length < 3) throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);

        return Base64.getDecoder().decode(parts[2]);
    }

    public String decryptToKeyArn(byte[] ciphertext, String region) {
        String data = new String(ciphertext, StandardCharsets.UTF_8);
        if (data.startsWith("kms:")) {
            String keyId = data.split(":")[1];
            return resolveKey(keyId, region).getArn();
        }
        return null;
    }

    public byte[] sign(String keyId, byte[] message, String algorithm, String region) {
        return sign(keyId, message, algorithm, "RAW", region);
    }

    public byte[] sign(String keyId, byte[] message, String algorithm, String messageType, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        if ("SYMMETRIC_DEFAULT".equals(kmsKey.getCustomerMasterKeySpec())) {
            throw new AwsException("UnsupportedOperationException", "Unsupported key spec for signing.", 400);
        }

        try {
            PrivateKey privateKey = loadPrivateKey(kmsKey.getPrivateKeyEncoded(), kmsKey.getCustomerMasterKeySpec());
            String jcaAlgo = mapAlgorithm(algorithm);
            
            if ("DIGEST".equals(messageType)) {
                // If message is already a digest, we need a "NONEwith..." algorithm
                jcaAlgo = "NONEwith" + (kmsKey.getCustomerMasterKeySpec().startsWith("RSA") ? "RSA" : "ECDSA");
            }

            Signature sig = isEcKeySpec(kmsKey.getCustomerMasterKeySpec())
                ? Signature.getInstance(jcaAlgo, BouncyCastleProvider.PROVIDER_NAME)
                : Signature.getInstance(jcaAlgo);
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to sign message: " + e.getMessage(), 500);
        }
    }

    public boolean verify(String keyId, byte[] message, byte[] signature, String algorithm, String region) {
        return verify(keyId, message, signature, algorithm, "RAW", region);
    }

    public boolean verify(String keyId, byte[] message, byte[] signature, String algorithm, String messageType, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        if ("SYMMETRIC_DEFAULT".equals(kmsKey.getCustomerMasterKeySpec())) {
            return false;
        }

        try {
            PublicKey publicKey = loadPublicKey(kmsKey.getPublicKeyEncoded(), kmsKey.getCustomerMasterKeySpec());
            String jcaAlgo = mapAlgorithm(algorithm);
            
            if ("DIGEST".equals(messageType)) {
                jcaAlgo = "NONEwith" + (kmsKey.getCustomerMasterKeySpec().startsWith("RSA") ? "RSA" : "ECDSA");
            }

            Signature sig = isEcKeySpec(kmsKey.getCustomerMasterKeySpec())
                ? Signature.getInstance(jcaAlgo, BouncyCastleProvider.PROVIDER_NAME)
                : Signature.getInstance(jcaAlgo);
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            LOG.warnv("Verification failed for key {0}: {1}", keyId, e.getMessage());
            return false;
        }
    }

    private PrivateKey loadPrivateKey(String encoded, String spec) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        KeyFactory factory = buildKeyFactory(spec);
        return factory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private PublicKey loadPublicKey(String encoded, String spec) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        KeyFactory factory = buildKeyFactory(spec);
        return factory.generatePublic(new X509EncodedKeySpec(decoded));
    }

    private String mapAlgorithm(String awsAlgo) {
        return switch (awsAlgo) {
            case "ECDSA_SHA_256" -> "SHA256withECDSA";
            case "ECDSA_SHA_384" -> "SHA384withECDSA";
            case "ECDSA_SHA_512" -> "SHA512withECDSA";
            case "RSASSA_PSS_SHA_256" -> "SHA256withRSA/PSS";
            case "RSASSA_PSS_SHA_384" -> "SHA384withRSA/PSS";
            case "RSASSA_PSS_SHA_512" -> "SHA512withRSA/PSS";
            case "RSASSA_PKCS1_V1_5_SHA_256" -> "SHA256withRSA";
            case "RSASSA_PKCS1_V1_5_SHA_384" -> "SHA384withRSA";
            case "RSASSA_PKCS1_V1_5_SHA_512" -> "SHA512withRSA";
            default -> throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + awsAlgo, 400);
        };
    }

    public Map<String, Object> generateDataKey(String keyId, String keySpec, int numberOfBytes, String region) {
        resolveKey(keyId, region);
        int len = (keySpec != null && keySpec.contains("256")) ? 32 : (numberOfBytes > 0 ? numberOfBytes : 32);
        
        byte[] plaintext = new byte[len];
        ThreadLocalRandom.current().nextBytes(plaintext);
        
        byte[] ciphertext = encrypt(keyId, plaintext, region);
        
        Map<String, Object> result = new HashMap<>();
        result.put("Plaintext", plaintext);
        result.put("CiphertextBlob", ciphertext);
        result.put("KeyId", resolveKey(keyId, region).getArn());
        return result;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void tagResource(String keyId, Map<String, String> tags, String region) {
        KmsKey key = resolveKey(keyId, region);
        key.getTags().putAll(tags);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    public void untagResource(String keyId, List<String> tagKeys, String region) {
        KmsKey key = resolveKey(keyId, region);
        tagKeys.forEach(key.getTags()::remove);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static boolean isEcKeySpec(String spec) {
        return spec.startsWith("ECC_");
    }

    private static KeyFactory buildKeyFactory(String spec) throws Exception {
      return spec.startsWith("RSA")
          ? KeyFactory.getInstance("RSA")
          : KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
    }

    private KmsKey resolveKey(String keyIdOrArn, String region) {
        String id = keyIdOrArn;
        // Alias arn
        if (id.contains(":alias/")) {
            String aliasName = id.substring(id.lastIndexOf(":") + 1);
            String aliasKey = region + "::" + aliasName;
            id = aliasStore.get(aliasKey)
                    .map(KmsAlias::getTargetKeyId)
                    .orElseThrow(() -> new AwsException("NotFoundException", "Alias not found: " + keyIdOrArn, 404));
        } else if (id.startsWith("arn:aws:kms:")) {
            // Key arn
            id = id.substring(id.lastIndexOf("/") + 1);
        } else if (id.startsWith("alias/")) {
            // Alias name
            String aliasKey = region + "::" + id;
            id = aliasStore.get(aliasKey)
                    .map(KmsAlias::getTargetKeyId)
                    .orElseThrow(() -> new AwsException("NotFoundException", "Alias not found: " + keyIdOrArn, 404));
        }

        // Key id
        return keyStore.get(region + "::" + id)
                .orElseThrow(() -> new AwsException("NotFoundException", "Key not found: " + keyIdOrArn, 404));
    }
}
