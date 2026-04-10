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
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
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
        return createKey(description, null, Map.of(), region);
    }

    public KmsKey createKey(String description, String policy, Map<String, String> tags, String region) {
        String keyId = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn("kms", region, "key/" + keyId);

        KmsKey key = new KmsKey();
        key.setKeyId(keyId);
        key.setArn(arn);
        key.setDescription(description);
        key.setPolicy(policy != null ? policy : DEFAULT_KEY_POLICY);
        if (tags != null) {
            key.getTags().putAll(tags);
        }

        keyStore.put(region + "::" + keyId, key);
        LOG.infov("Created KMS key: {0} in {1}", keyId, region);
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
        resolveKey(keyId, region);
        // Local mock signature: "sig:keyId:algo:base64(message)"
        String sig = "sig:" + keyId + ":" + algorithm + ":" + Base64.getEncoder().encodeToString(message);
        return sig.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(String keyId, byte[] message, byte[] signature, String algorithm, String region) {
        resolveKey(keyId, region);
        String actualSig = new String(signature, StandardCharsets.UTF_8);
        String expectedSig = "sig:" + keyId + ":" + algorithm + ":" + Base64.getEncoder().encodeToString(message);
        return actualSig.equals(expectedSig);
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
