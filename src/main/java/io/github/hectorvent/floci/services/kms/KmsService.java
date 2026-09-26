package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.keytype.KmsKeyType;
import io.github.hectorvent.floci.services.kms.keytype.KmsKeyTypes;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsGrant;
import io.github.hectorvent.floci.services.kms.model.KmsImportParameters;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.RAW;

@ApplicationScoped
public class KmsService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(KmsService.class);

    private static final String AWS_KMS_ORIGIN = "AWS_KMS";
    private static final String EXTERNAL_ORIGIN = "EXTERNAL";
    private static final String PENDING_IMPORT = "PendingImport";
    private static final String PENDING_DELETION = "PendingDeletion";
    private static final String MULTI_REGION_PRIMARY = "PRIMARY";
    private static final String MULTI_REGION_REPLICA = "REPLICA";
    private static final String KEY_MATERIAL_EXPIRES = "KEY_MATERIAL_EXPIRES";
    private static final String KEY_MATERIAL_DOES_NOT_EXPIRE = "KEY_MATERIAL_DOES_NOT_EXPIRE";
    private static final Duration IMPORT_PARAMETERS_VALIDITY = Duration.ofHours(24);
    private static final Duration MAX_KEY_MATERIAL_VALIDITY = Duration.ofDays(365);
    private static final int IMPORT_TOKEN_BYTES = 32;

    private final StorageBackend<String, KmsKey> keyStore;
    private final StorageBackend<String, KmsAlias> aliasStore;
    private final StorageBackend<String, KmsGrant> grantStore;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;
    private final KmsKeyTypes keyTypes;
    // Guards the check-generate-put sequence in ensureBackingKeyMaterial so two concurrent
    // first uses of the same legacy key cannot each mint a different backing key.
    private final Object backingKeyMaterialLock = new Object();

    @Inject
    public KmsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("kms", "kms-keys.json",
                        new TypeReference<>() {}),
                storageFactory.create("kms", "kms-aliases.json",
                        new TypeReference<>() {}),
                storageFactory.create("kms", "kms-grants.json",
                        new TypeReference<>() {}),
                regionResolver);
    }

    KmsService(StorageBackend<String, KmsKey> keyStore,
               StorageBackend<String, KmsAlias> aliasStore,
               StorageBackend<String, KmsGrant> grantStore,
               RegionResolver regionResolver) {
        this(keyStore, aliasStore, grantStore, regionResolver, new SecureRandom());
    }

    KmsService(StorageBackend<String, KmsKey> keyStore,
               StorageBackend<String, KmsAlias> aliasStore,
               StorageBackend<String, KmsGrant> grantStore,
               RegionResolver regionResolver,
               SecureRandom secureRandom) {
        this.keyStore = keyStore;
        this.aliasStore = aliasStore;
        this.grantStore = grantStore;
        this.regionResolver = regionResolver;
        this.secureRandom = secureRandom;
        this.keyTypes = new KmsKeyTypes(secureRandom);
    }

    public byte[] generateRandom(int numberOfBytes) {
        if (numberOfBytes < 1) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + numberOfBytes
                    + "' at 'numberOfBytes' failed to satisfy constraint: Member must have value greater than or equal to 1", 400);
        }
        if (1024 < numberOfBytes) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + numberOfBytes
                    + "' at 'numberOfBytes' failed to satisfy constraint: Member must have value less than or equal to 1024", 400);
        }
        byte[] bytes = new byte[numberOfBytes];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private String buildDefaultKeyPolicy() {
        String account = regionResolver.getAccountId();
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Enable IAM User Permissions\"," +
               "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + regionResolver.buildGlobalArn("iam", account, "root") + "\"}," +
               "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";
    }

    public KmsKey createKey(String description, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null, Map.of(), region);
    }

    public KmsKey createKey(String description, String policy, Map<String, String> tags, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", policy, tags, region);
    }

    public KmsKey createKey(String description, String keyUsage, String keySpec, String policy, Map<String, String> tags, String region) {
        return createKey(description, keyUsage, keySpec, policy, tags, null, region);
    }

    public KmsKey createKey(String description, String keyUsage, String keySpec, String policy,
                            Map<String, String> tags, String origin, String region) {
        return createKey(description, keyUsage, keySpec, policy, tags, origin, false, region);
    }

    public KmsKey createKey(String description, String keyUsage, String keySpec, String policy,
                            Map<String, String> tags, String origin, boolean multiRegion, String region) {
        String keyId = resolveKeyId(tags, multiRegion);
        if (keyStore.get(region + "::" + keyId).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Key already exists", 400);
        }
        String arn = regionResolver.buildArn("kms", region, "key/" + keyId);

        KmsKeyUsage effectiveUsage = Optional.ofNullable(keyUsage)
                .map(KmsKeyUsage::fromString)
                .orElse(KmsKeyUsage.ENCRYPT_DECRYPT);
        KmsKeySpec effectiveSpec = Optional.of(keySpec)
                .map(KmsKeySpec::fromString)
                .orElse(KmsKeySpec.SYMMETRIC_DEFAULT);
        validateKeyUsageForSpec(effectiveUsage, effectiveSpec);

        KmsKey key = new KmsKey();
        key.setKeyId(keyId);
        key.setArn(arn);
        key.setDescription(description == null ? "" : description);
        key.setKeyUsage(effectiveUsage);
        key.setKeySpec(effectiveSpec);
        key.setPolicy(policy != null ? policy : buildDefaultKeyPolicy());
        key.getTags().putAll(ReservedTags.stripReservedTags(tags));
        key.setOrigin(resolveOrigin(origin, effectiveSpec));
        key.setMultiRegion(multiRegion);
        if (multiRegion) {
            key.setMultiRegionKeyType(MULTI_REGION_PRIMARY);
            key.setMultiRegionPrimaryRegion(region);
        }

        if (EXTERNAL_ORIGIN.equals(key.getOrigin())) {
            key.setKeyState(PENDING_IMPORT);
            key.setEnabled(false);
        } else {
            generateKeyMaterial(key, region);
        }

        keyStore.put(region + "::" + keyId, key);
        LOG.infov("Created KMS key: {0} ({1}/{2}, origin {3}) in {4}",
                keyId, key.getKeyUsage(), key.getKeySpec(), key.getOrigin(), region);
        return key;
    }

    private String resolveKeyId(Map<String, String> tags, boolean multiRegion) {
        String overrideId = ReservedTags.extractOverrideKeyId(tags);
        if (overrideId == null) {
            String generatedId = UUID.randomUUID().toString();
            return multiRegion ? "mrk-" + generatedId.replace("-", "") : generatedId;
        }

        String normalized = overrideId.trim();
        if (normalized.isEmpty()) {
            throw new AwsException("TagException", "Override resource ID must not be blank.", 400);
        }
        if (normalized.length() > 256) {
            throw new AwsException("TagException", "Override resource ID must be 256 characters or fewer.", 400);
        }
        if (multiRegion && !normalized.startsWith("mrk-")) {
            throw new AwsException("TagException", "Multi-Region key IDs must start with 'mrk-'.", 400);
        }
        return normalized;
    }

    private void generateKeyMaterial(KmsKey key, String region) {
        try {
            keyTypes.of(key.getKeySpec()).generateKeyMaterial(key, region);
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalFailure", "Failed to generate key material: " + e.getMessage(), 500);
        }
    }

    /**
     * Self-healing for keys persisted before the AES-GCM envelope existed: their JSON has no
     * {@code backingKeys}/{@code currentBackingKeyId}, so the field defaults to an empty map on
     * load (existing {@code @JsonIgnoreProperties(ignoreUnknown = true)} already tolerates the
     * missing field). Material is generated lazily on first use and persisted immediately,
     * mirroring how {@link #expireImportedKeyMaterialIfDue} self-heals imported-key state.
     *
     * <p>Double-checked: the cheap unsynchronized check below lets an already-healed key (the
     * overwhelming majority of calls, once a key has been used once) return without taking the
     * lock. Only a key that still needs healing pays for entering the {@code synchronized}
     * block, where the key is re-read from {@code keyStore} and re-checked before generating,
     * so that if two callers race to heal the same legacy key concurrently, only one backing key
     * is ever minted and every caller ends up with (and returns) the same persisted instance.
     * Without this, two concurrent first uses could each generate a different backing key and
     * each {@code put} their own copy, leaving one of the two backing keys, and any ciphertext
     * encrypted under it, unreachable from the persisted key.
     *
     * @return the key instance to use for this call: either {@code key} unchanged, or the
     *     re-read, healed instance that was just persisted.
     */
    private KmsKey ensureBackingKeyMaterial(KmsKey key, String region) {
        if (KmsKeySpec.SYMMETRIC_DEFAULT != key.getKeySpec() || KmsKeyUsage.ENCRYPT_DECRYPT != key.getKeyUsage()) {
            return key;
        }
        if (EXTERNAL_ORIGIN.equals(key.getOrigin())) {
            if (!hasBackingKeyMaterialSafely(key) && key.getPrivateKeyEncoded() != null) {
                synchronized (backingKeyMaterialLock) {
                    if (!hasBackingKeyMaterial(key)) {
                        byte[] material = decodeBackingMaterial(key.getPrivateKeyEncoded());
                        String materialId = key.getKeyMaterialId() == null
                                ? keyMaterialId(key.getKeyId(), material) : key.getKeyMaterialId();
                        installImportedBackingKey(key, materialId, material);
                        keyStore.put(region + "::" + key.getKeyId(), key);
                    }
                }
            }
            return key;
        }
        if (hasBackingKeyMaterialSafely(key)) {
            return key;
        }
        synchronized (backingKeyMaterialLock) {
            KmsKey current = keyStore.get(region + "::" + key.getKeyId()).orElse(key);
            if (hasBackingKeyMaterial(current)) {
                return current;
            }
            keyTypes.symmetric().addBackingKey(current);
            keyStore.put(region + "::" + current.getKeyId(), current);
            LOG.infov("Generated backing key material for legacy KMS key: {0} in {1}", current.getKeyId(), region);
            return current;
        }
    }

    private static boolean hasBackingKeyMaterial(KmsKey key) {
        return key.getCurrentBackingKeyId() != null && key.getBackingKeys() != null
                && key.getBackingKeys().containsKey(key.getCurrentBackingKeyId());
    }

    private boolean hasBackingKeyMaterialSafely(KmsKey key) {
        synchronized (backingKeyMaterialLock) {
            return hasBackingKeyMaterial(key);
        }
    }

    public KmsKey getPublicKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        requireImportedKeyMaterial(key);
        KmsKeySpec spec = key.getKeySpec();
        if (KmsKeySpec.SYMMETRIC_DEFAULT == spec || isHmac(spec)) {
            throw new AwsException("UnsupportedOperationException", null, 400);
        }
        return key;
    }

    private static boolean isHmac(KmsKeySpec spec) {
        return spec != null && spec.getKeyType() == KmsKeySpec.KeyType.HMAC;
    }

    // AWS UpdateAlias only requires the current and new key to be "the same type (both
    // symmetric or both asymmetric or both HMAC)" - not an exact KeySpec match, so e.g.
    // RSA_2048 and ECC_NIST_P256 are compatible, but SYMMETRIC_DEFAULT and RSA_2048 are not.
    private static boolean sameKeyFamily(KmsKeySpec a, KmsKeySpec b) {
        return isHmac(a) == isHmac(b) && (a == KmsKeySpec.SYMMETRIC_DEFAULT) == (b == KmsKeySpec.SYMMETRIC_DEFAULT);
    }

    private static void validateKeyUsageForSpec(KmsKeyUsage keyUsage, KmsKeySpec spec) {
        if (!spec.allowedKeyUsages().contains(keyUsage)) {
            throw new AwsException("ValidationException",
                    "KeyUsage " + keyUsage + " is not compatible with KeySpec " + spec + ".", 400);
        }
    }

    public KmsKey describeKey(String keyId, String region) {
        return resolveKey(keyId, region);
    }

    public KmsKey replicateKey(String keyId, String description, String policy,
                               Map<String, String> tags, String replicaRegion, String primaryRegion) {
        if (replicaRegion == null || replicaRegion.isBlank()) {
            throw new AwsException("ValidationException", "ReplicaRegion is required.", 400);
        }

        KmsKey primary = resolveKey(keyId, primaryRegion);
        if (!primary.isMultiRegion() || !MULTI_REGION_PRIMARY.equals(primary.getMultiRegionKeyType())) {
            throw new AwsException("UnsupportedOperationException",
                    primary.getArn() + " is not a multi-Region primary key.", 400);
        }
        requireKeyCanBeReplicated(primary);
        if (!AwsRegions.partitionFor(primaryRegion).equals(AwsRegions.partitionFor(replicaRegion))) {
            throw new AwsException("UnsupportedOperationException",
                    "The replica region must be in the same AWS partition as the primary key.", 400);
        }

        String storageKey = replicaRegion + "::" + primary.getKeyId();
        if (keyStore.get(storageKey).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "A replica for this multi-Region key already exists in " + replicaRegion + ".", 400);
        }

        KmsKey replica = new KmsKey();
        replica.setKeyId(primary.getKeyId());
        replica.setArn(regionResolver.buildArn("kms", replicaRegion, "key/" + primary.getKeyId()));
        replica.setDescription(description == null ? "" : description);
        replica.setKeyUsage(primary.getKeyUsage());
        replica.setKeySpec(primary.getKeySpec());
        replica.setPolicy(policy == null ? buildDefaultKeyPolicy() : policy);
        replica.getTags().putAll(ReservedTags.stripReservedTags(tags));
        replica.setOrigin(primary.getOrigin());
        replica.setKeyRotationEnabled(primary.isKeyRotationEnabled());
        replica.setMultiRegion(true);
        replica.setMultiRegionKeyType(MULTI_REGION_REPLICA);
        replica.setMultiRegionPrimaryRegion(primaryRegion);
        replica.setKeyMaterialId(primary.getKeyMaterialId());
        if (EXTERNAL_ORIGIN.equals(primary.getOrigin())) {
            replica.setEnabled(false);
            replica.setKeyState(PENDING_IMPORT);
            replica.setBackingKeys(new HashMap<>());
        } else {
            replica.setEnabled(primary.isEnabled());
            replica.setKeyState(primary.getKeyState());
            replica.setPrivateKeyEncoded(primary.getPrivateKeyEncoded());
            replica.setPublicKeyEncoded(primary.getPublicKeyEncoded());
            replica.setBackingKeys(new HashMap<>(primary.getBackingKeys()));
            replica.setCurrentBackingKeyId(primary.getCurrentBackingKeyId());
        }
        keyStore.put(storageKey, replica);

        LOG.infov("Replicated KMS key {0} from {1} to {2}", primary.getKeyId(), primaryRegion, replicaRegion);
        return replica;
    }

    private static void requireKeyCanBeReplicated(KmsKey key) {
        requireNotPendingDeletion(key);
        requireImportedKeyMaterial(key);
        if (!key.isEnabled()) {
            throw new AwsException("DisabledException", key.getArn() + " is disabled.", 400);
        }
    }

    public List<KmsKey> listKeys(String region) {
        String prefix = region + "::";
        return keyStore.scan(k -> k.startsWith(prefix));
    }

    public List<KmsKey> listAllMultiRegionKeys(String keyId) {
        String suffix = "::" + keyId;
        return keyStore.scan(key -> key.endsWith(suffix)).stream()
                .filter(KmsKey::isMultiRegion)
                .toList();
    }

    /** GrantOperation enum from the KMS model (kms/2014-11-01/service-2.json). */
    private static final Set<String> GRANT_OPERATIONS = new LinkedHashSet<>(List.of(
            "Decrypt", "Encrypt", "GenerateDataKey", "GenerateDataKeyWithoutPlaintext",
            "ReEncryptFrom", "ReEncryptTo", "Sign", "Verify", "GetPublicKey", "CreateGrant",
            "RetireGrant", "DescribeKey", "GenerateDataKeyPair", "GenerateDataKeyPairWithoutPlaintext",
            "GenerateMac", "VerifyMac", "DeriveSharedSecret"));

    /** GrantNameType pattern/length from the KMS model (kms/2014-11-01/service-2.json). */
    private static final java.util.regex.Pattern GRANT_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9:/_-]+$");

    /**
     * GrantConstraintSourceArnType pattern from the KMS model (kms/2014-11-01/service-2.json),
     * quoted verbatim in AWS's validation message; it already accepts every partition.
     */
    private static final java.util.regex.Pattern GRANT_CONSTRAINT_SOURCE_ARN_PATTERN =
            java.util.regex.Pattern.compile("^arn:aws[a-z0-9-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:.+$"); // partition-literal: model pattern quoted in AWS's message

    private static final Set<String> GRANT_CONSTRAINT_MEMBERS =
            Set.of("EncryptionContextSubset", "EncryptionContextEquals", "SourceArn");

    /** Validates a CreateGrant Constraints map against the modeled GrantConstraints shape. */
    private void validateGrantConstraints(Map<String, Object> constraints) {
        if (constraints == null) {
            return;
        }
        for (String member : constraints.keySet()) {
            if (!GRANT_CONSTRAINT_MEMBERS.contains(member)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Unknown parameter in 'constraints': \"" + member
                                + "\", must be one of: " + String.join(", ", GRANT_CONSTRAINT_MEMBERS), 400);
            }
        }
        for (String encryptionContextMember : List.of("EncryptionContextSubset", "EncryptionContextEquals")) {
            Object value = constraints.get(encryptionContextMember);
            if (value == null) {
                continue;
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'constraints." + encryptionContextMember
                                + "' failed to satisfy constraint: Member must be a map of string to string", 400);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Value at 'constraints." + encryptionContextMember
                                    + "' failed to satisfy constraint: Member must be a map of string to string", 400);
                }
            }
        }
        Object sourceArn = constraints.get("SourceArn");
        if (sourceArn != null) {
            if (!(sourceArn instanceof String sourceArnValue)
                    || sourceArnValue.length() < 20 || sourceArnValue.length() > 512
                    || !GRANT_CONSTRAINT_SOURCE_ARN_PATTERN.matcher(sourceArnValue).matches()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'constraints.sourceArn' failed to satisfy "
                                + "constraint: Member must satisfy regular expression pattern: "
                                + "^arn:aws[a-z0-9-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:.+$", 400); // partition-literal: model pattern quoted in AWS's message
            }
        }
    }

    public KmsGrant createGrant(String keyId, String granteePrincipal, List<String> operations, String region) {
        return createGrant(keyId, granteePrincipal, operations, null, null, null, region);
    }

    public KmsGrant createGrant(String keyId, String granteePrincipal, List<String> operations,
                                String retiringPrincipal, String region) {
        return createGrant(keyId, granteePrincipal, operations, retiringPrincipal, null, null, region);
    }

    public KmsGrant createGrant(String keyId, String granteePrincipal, List<String> operations,
                                String retiringPrincipal, String name, Map<String, Object> constraints,
                                String region) {
        if (keyId == null || keyId.isBlank()) {
            throw new AwsException("ValidationException", "KeyId is required", 400);
        }
        if (granteePrincipal == null || granteePrincipal.isBlank()) {
            throw new AwsException("ValidationException", "GranteePrincipal is required", 400);
        }
        if (operations == null || operations.isEmpty()) {
            throw new AwsException("ValidationException", "Operations is required", 400);
        }
        for (String operation : operations) {
            if (!GRANT_OPERATIONS.contains(operation)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + operation + "' at 'operations' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: ["
                                + String.join(", ", GRANT_OPERATIONS) + "]", 400);
            }
        }
        if (name != null) {
            if (name.isEmpty()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + name + "' at 'name' failed to satisfy "
                                + "constraint: Member must have length greater than or equal to 1", 400);
            }
            if (name.length() > 256) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + name + "' at 'name' failed to satisfy "
                                + "constraint: Member must have length less than or equal to 256", 400);
            }
            if (!GRANT_NAME_PATTERN.matcher(name).matches()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + name + "' at 'name' failed to satisfy "
                                + "constraint: Member must satisfy regular expression pattern: "
                                + "^[a-zA-Z0-9:/_-]+$", 400);
            }
        }
        validateGrantConstraints(constraints);

        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        String grantId = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);

        KmsGrant grant = new KmsGrant();
        grant.setGrantId(grantId);
        grant.setGrantToken(Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes));
        grant.setName(name);
        grant.setKeyId(key.getKeyId());
        grant.setKeyArn(key.getArn());
        grant.setGranteePrincipal(granteePrincipal);
        grant.setRetiringPrincipal(retiringPrincipal);
        grant.setOperations(new ArrayList<>(operations));
        grant.setConstraints(constraints == null ? null : new HashMap<>(constraints));

        grantStore.put(region + "::" + grantId, grant);
        LOG.infov("Created KMS grant: {0} for key {1} in {2}", grantId, key.getKeyId(), region);
        return grant;
    }

    private static final int DEFAULT_GRANT_LIMIT = 50;
    private static final int MAX_GRANT_LIMIT = 100;

    public Map<String, Object> listGrants(String keyId, String region, String marker, Integer limit,
                                           String grantIdFilter, String granteePrincipalFilter) {
        // Validate filter mutual exclusivity
        if (grantIdFilter != null && (grantIdFilter.length() > 128)) {
            throw new AwsException("ValidationException", "GrantId exceeds maximum length of 128", 400);
        }

        KmsKey key = resolveKey(keyId, region);
        String prefix = region + "::";

        // Collect and sort grants deterministically by grantId
        List<KmsGrant> sortedGrants = grantStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(grant -> key.getKeyId().equals(grant.getKeyId()))
                .filter(grant -> grantIdFilter == null || grantIdFilter.isBlank()
                        || grantIdFilter.equals(grant.getGrantId()))
                .filter(grant -> granteePrincipalFilter == null || granteePrincipalFilter.isBlank()
                        || granteePrincipalFilter.equals(grant.getGranteePrincipal()))
                .sorted(Comparator.comparing(KmsGrant::getGrantId))
                .toList();

        return paginateGrants(sortedGrants, marker, limit);
    }

    public Map<String, Object> listRetirableGrants(String retiringPrincipal, String region,
                                                    String marker, Integer limit) {
        if (retiringPrincipal == null || retiringPrincipal.isBlank()) {
            throw new AwsException("ValidationException", "RetiringPrincipal is required", 400);
        }

        String prefix = region + "::";

        List<KmsGrant> sortedGrants = grantStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(grant -> retiringPrincipal.equals(grant.getRetiringPrincipal()))
                .sorted(Comparator.comparing(KmsGrant::getGrantId))
                .toList();

        return paginateGrants(sortedGrants, marker, limit);
    }

    public void revokeGrant(String keyId, String grantId, String region) {
        if (keyId == null || keyId.isBlank()) {
            throw new AwsException("ValidationException", "KeyId is required", 400);
        }
        if (grantId == null || grantId.isBlank()) {
            throw new AwsException("ValidationException", "GrantId is required", 400);
        }

        // Resolve the key to validate it exists
        resolveKey(keyId, region);

        String storageKey = region + "::" + grantId;
        if (grantStore.get(storageKey).isEmpty()) {
            throw new AwsException("NotFoundException", "Grant not found: " + grantId, 400);
        }

        grantStore.delete(storageKey);
        LOG.infov("Revoked KMS grant: {0} for key {1} in {2}", grantId, keyId, region);
    }

    public void retireGrant(String grantToken, String keyId, String grantId, String region) {
        boolean hasToken = grantToken != null && !grantToken.isBlank();
        boolean hasKeyAndGrant = keyId != null && !keyId.isBlank() && grantId != null && !grantId.isBlank();

        if (!hasToken && !hasKeyAndGrant) {
            throw new AwsException("ValidationException",
                    "Either GrantToken or both KeyId and GrantId must be provided", 400);
        }

        // Token-based retirement: scan all grants in the region for matching token
        if (hasToken) {
            String prefix = region + "::";
            KmsGrant found = grantStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(g -> grantToken.equals(g.getGrantToken()))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("NotFoundException",
                            "Grant not found for the given grant token", 400));

            // Cross-verify GrantId if provided
            if (grantId != null && !grantId.isBlank() && !grantId.equals(found.getGrantId())) {
                throw new AwsException("NotFoundException", "Grant not found", 400);
            }

            // Cross-verify KeyId if provided
            if (keyId != null && !keyId.isBlank()) {
                KmsKey key = resolveKey(keyId, region);
                if (!key.getKeyId().equals(found.getKeyId())) {
                    throw new AwsException("NotFoundException",
                            "Grant not found for the given key", 400);
                }
            }

            grantStore.delete(region + "::" + found.getGrantId());
            LOG.infov("Retired KMS grant: {0} by token in {1}", found.getGrantId(), region);
            return;
        }

        // KeyId + GrantId retirement (administrative, distinct from RevokeGrant surface)
        resolveKey(keyId, region);
        String storageKey = region + "::" + grantId;
        if (grantStore.get(storageKey).isEmpty()) {
            throw new AwsException("NotFoundException", "Grant not found: " + grantId, 400);
        }
        grantStore.delete(storageKey);
        LOG.infov("Retired KMS grant: {0} for key {1} in {2}", grantId, keyId, region);
    }

    private Map<String, Object> paginateGrants(List<KmsGrant> sortedGrants, String marker, Integer limit) {
        int effectiveLimit = limit != null ? Math.clamp(limit, 1, MAX_GRANT_LIMIT) : DEFAULT_GRANT_LIMIT;

        int startIndex = 0;
        if (marker != null && !marker.isBlank()) {
            String decodedMarker;
            try {
                decodedMarker = new String(Base64.getDecoder().decode(marker), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new AwsException("InvalidMarkerException",
                        "The request was rejected because the marker is not valid.", 400);
            }
            String lastGrantId = decodedMarker;
            boolean found = false;
            for (int i = 0; i < sortedGrants.size(); i++) {
                if (sortedGrants.get(i).getGrantId().equals(lastGrantId)) {
                    startIndex = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AwsException("InvalidMarkerException",
                        "The request was rejected because the marker that specifies where pagination should next begin is not valid.", 400);
            }
        }

        int endIndex = Math.min(startIndex + effectiveLimit, sortedGrants.size());
        List<KmsGrant> page = sortedGrants.subList(startIndex, endIndex);
        boolean truncated = endIndex < sortedGrants.size();

        Map<String, Object> result = new HashMap<>();
        result.put("Grants", page.stream().map(this::grantToMap).toList());
        result.put("Truncated", truncated);
        if (truncated && !page.isEmpty()) {
            String lastGrantId = page.getLast().getGrantId();
            String nextMarker = Base64.getEncoder().encodeToString(lastGrantId.getBytes(StandardCharsets.UTF_8));
            result.put("NextMarker", nextMarker);
        }
        return result;
    }

    private Map<String, Object> grantToMap(KmsGrant grant) {
        Map<String, Object> result = new HashMap<>();
        result.put("GrantId", grant.getGrantId());
        result.put("KeyId", grant.getKeyArn());
        result.put("GranteePrincipal", grant.getGranteePrincipal());
        result.put("Operations", grant.getOperations());
        result.put("CreationDate", grant.getCreationDate());
        if (grant.getName() != null) {
            result.put("Name", grant.getName());
        }
        if (grant.getConstraints() != null) {
            result.put("Constraints", grant.getConstraints());
        }
        if (grant.getRetiringPrincipal() != null) {
            result.put("RetiringPrincipal", grant.getRetiringPrincipal());
        }
        return result;
    }

    public void scheduleKeyDeletion(String keyId, int pendingWindowInDays, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        key.setKeyState("PendingDeletion");
        key.setDeletionDate(Instant.now().plusSeconds((long) pendingWindowInDays * 86400).getEpochSecond());
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    /**
     * A key whose material was never imported, or was deleted or expired while it sat pending
     * deletion, has nothing to come back to: it returns to PendingImport rather than to a usable
     * state that no cryptographic operation could actually serve.
     */
    public void cancelKeyDeletion(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        if (lacksImportedKeyMaterial(key)) {
            key.setKeyState(PENDING_IMPORT);
            key.setEnabled(false);
        } else {
            key.setKeyState("Enabled");
        }
        key.setDeletionDate(0);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    private static boolean lacksImportedKeyMaterial(KmsKey key) {
        return EXTERNAL_ORIGIN.equals(key.getOrigin()) && key.getPrivateKeyEncoded() == null;
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

    /**
     * Lists the policy names attached to a key. KMS supports exactly one key policy, named
     * {@code default}, so the result can never be truncated: {@code Truncated} is always false and
     * {@code NextMarker} is omitted.
     *
     * <p>{@code Limit} and {@code Marker} are accepted at the wire and ignored: the handler simply
     * never reads them, which is exactly what moto's KMS does for this operation. A single policy
     * name fits inside any limit, so the values cannot change the response. Real AWS rejects an
     * out-of-range {@code Limit} server-side (the model bounds LimitType to [1..1000], but SDKs do
     * not fully enforce that client-side — botocore checks only the minimum); not re-validating it
     * here is deliberate leniency, consistent with the emulator's general posture.
     */
    public Map<String, Object> listKeyPolicies(String keyId, String region) {
        String policyName = (String) getKeyPolicy(keyId, region).get("PolicyName");

        Map<String, Object> result = new HashMap<>();
        result.put("PolicyNames", List.of(policyName));
        result.put("Truncated", false);
        return result;
    }

    public void updateKeyDescription(String keyId, String description, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        key.setDescription(description);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Updated description for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    // ──────────────────────────── Key Rotation ────────────────────────────

    public boolean getKeyRotationStatus(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        if (KmsKeyUsage.ENCRYPT_DECRYPT != key.getKeyUsage()
                || KmsKeySpec.SYMMETRIC_DEFAULT != key.getKeySpec()) {
            return false;
        }
        return key.isKeyRotationEnabled();
    }

    public void enableKeyRotation(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        validateRotationOrigin(key);
        validateKeyIsUsableForCryptoOperations(key);
        validateRotationKeySpec(key);
        key.setKeyRotationEnabled(true);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Enabled key rotation for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    public void disableKeyRotation(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        validateRotationOrigin(key);
        validateKeyIsUsableForCryptoOperations(key);
        key.setKeyRotationEnabled(false);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Disabled key rotation for KMS key: {0} in {1}", key.getKeyId(), region);
    }

    public void enableKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        requireImportedKeyMaterial(key);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Enabled KMS key: {0} in {1}", key.getKeyId(), region);
    }

    public void disableKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        requireImportedKeyMaterial(key);
        key.setEnabled(false);
        key.setKeyState("Disabled");
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Disabled KMS key: {0} in {1}", key.getKeyId(), region);
    }

    private static final int ON_DEMAND_ROTATION_LIMIT = 25;
    public String rotateKeyOnDemand(String keyId, String region) {
        synchronized (backingKeyMaterialLock) {
            KmsKey key = resolveKey(keyId, region);
            validateKeyIsUsableForCryptoOperations(key);
            if (MULTI_REGION_REPLICA.equals(key.getMultiRegionKeyType())) {
                throw new AwsException("UnsupportedOperationException",
                        "On-demand rotation is only supported for the multi-Region primary key.", 400);
            }
            validateRotationKeySpec(key);
            if (EXTERNAL_ORIGIN.equals(key.getOrigin())) {
                throw new AwsException("KMSInvalidStateException",
                        "No available key material pending rotation for the key: " + key.getArn() + ".", 400);
            }
            if (key.getOnDemandRotationCount() >= ON_DEMAND_ROTATION_LIMIT) {
                throw new AwsException("LimitExceededException",
                        "On-demand rotation quota for KMS key " + key.getKeyId() + " is exceeded.", 400);
            }
            key.setOnDemandRotationCount(key.getOnDemandRotationCount() + 1);
            // AWS keeps prior backing keys after rotation so ciphertext encrypted under them keeps
            // decrypting.
            keyTypes.symmetric().addBackingKey(key);
            keyStore.put(region + "::" + key.getKeyId(), key);
            if (MULTI_REGION_PRIMARY.equals(key.getMultiRegionKeyType())) {
                synchronizeMultiRegionReplicas(key);
            }
            return key.getKeyId();
        }
    }

    private void synchronizeMultiRegionReplicas(KmsKey primary) {
        for (KmsKey candidate : listAllMultiRegionKeys(primary.getKeyId())) {
            if (!MULTI_REGION_REPLICA.equals(candidate.getMultiRegionKeyType())) {
                continue;
            }
            candidate.setBackingKeys(new HashMap<>(primary.getBackingKeys()));
            candidate.setCurrentBackingKeyId(primary.getCurrentBackingKeyId());
            String replicaRegion = AwsArnUtils.parse(candidate.getArn()).region();
            keyStore.put(replicaRegion + "::" + candidate.getKeyId(), candidate);
        }
    }

    private static void validateRotationOrigin(KmsKey key) {
        if (EXTERNAL_ORIGIN.equals(key.getOrigin())) {
            throw invalidOrigin(key);
        }
    }

    private static void validateRotationKeySpec(KmsKey key) {
        if (KmsKeyUsage.ENCRYPT_DECRYPT != key.getKeyUsage()
                || KmsKeySpec.SYMMETRIC_DEFAULT != key.getKeySpec()) {
            throw new AwsException("UnsupportedOperationException", null, 400);
        }
    }

    /** The wrapping parameters GetParametersForImport hands back to the caller. */
    public record ImportParameters(String keyArn, String publicKeyEncoded, String importToken,
                                   long parametersValidTo) {
    }

    public ImportParameters getParametersForImport(String keyId, String wrappingAlgorithm,
                                                   String wrappingKeySpec, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireExternalOrigin(key);
        requireNotPendingDeletion(key);
        KmsKeyImport.validateWrappingAlgorithm(key.getKeySpec(), wrappingAlgorithm, wrappingKeySpec);

        KmsKeyImport.WrappingKeyPair wrappingKeyPair = KmsKeyImport.generateWrappingKeyPair(wrappingKeySpec);
        KmsImportParameters parameters = new KmsImportParameters();
        parameters.setWrappingPrivateKeyEncoded(wrappingKeyPair.privateKeyEncoded());
        parameters.setWrappingAlgorithm(wrappingAlgorithm);
        parameters.setImportToken(newImportToken());
        parameters.setParametersValidTo(Instant.now().plus(IMPORT_PARAMETERS_VALIDITY).getEpochSecond());
        key.setImportParameters(parameters);
        keyStore.put(region + "::" + key.getKeyId(), key);

        LOG.infov("Issued import parameters for KMS key {0} in {1} ({2}/{3})",
                key.getKeyId(), region, wrappingAlgorithm, wrappingKeySpec);
        return new ImportParameters(key.getArn(), wrappingKeyPair.publicKeyEncoded(),
                parameters.getImportToken(), parameters.getParametersValidTo());
    }

    /**
     * Unwraps and installs key material, which takes the key from PendingImport to Enabled. The
     * import token is spent by the call that uses it, as it is on real KMS.
     */
    public KmsKey importKeyMaterial(String keyId, String importToken, byte[] encryptedKeyMaterial,
                                    String expirationModel, Long validTo, String importType, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireExternalOrigin(key);
        requireNotPendingDeletion(key);
        validateImportType(importType, key);

        KmsImportParameters parameters = requireCurrentImportToken(key);
        if (!parameters.getImportToken().equals(importToken)) {
            throw new AwsException("InvalidImportTokenException",
                    "The import token is invalid or was not issued for this KMS key.", 400);
        }
        String effectiveExpirationModel = resolveExpirationModel(expirationModel, validTo);

        byte[] material = KmsKeyImport.unwrap(parameters.getWrappingPrivateKeyEncoded(),
                parameters.getWrappingAlgorithm(), encryptedKeyMaterial);
        String keyMaterialId = keyMaterialId(key.getKeyId(), material);
        requireSameMaterialAsFirstImport(key, keyMaterialId);

        KmsKeyType keyType = keyTypes.of(key.getKeySpec());
        keyType.importKeyMaterial(key, material);

        if (KmsKeySpec.SYMMETRIC_DEFAULT == key.getKeySpec()) {
            installImportedBackingKey(key, keyMaterialId, material);
        }
        key.setKeyMaterialId(keyMaterialId);
        key.setExpirationModel(effectiveExpirationModel);
        key.setValidTo(KEY_MATERIAL_EXPIRES.equals(effectiveExpirationModel) ? validTo : 0L);
        key.setKeyState("Enabled");
        key.setEnabled(true);
        key.setImportParameters(null);
        keyStore.put(region + "::" + key.getKeyId(), key);

        LOG.infov("Imported key material into KMS key {0} in {1} ({2})",
                key.getKeyId(), region, effectiveExpirationModel);
        return key;
    }

    /**
     * Makes the imported material the backing key of the ciphertext envelope. Its id is the
     * {@code keyMaterialId}, which is derived from the material itself, so re-importing the same
     * material after a delete or expiry reinstates the id that earlier ciphertext names.
     */
    private void installImportedBackingKey(KmsKey key, String keyMaterialId, byte[] material) {
        synchronized (backingKeyMaterialLock) {
            Map<String, String> backingKeys = new HashMap<>();
            backingKeys.put(keyMaterialId, Base64.getEncoder().encodeToString(material));
            key.setBackingKeys(backingKeys);
            key.setCurrentBackingKeyId(keyMaterialId);
        }
    }

    /**
     * Deleting material from a key that is already pending deletion leaves the key state alone,
     * as it does on AWS: PendingDeletion outranks the PendingImport this would otherwise set.
     */
    public KmsKey deleteImportedKeyMaterial(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireExternalOrigin(key);
        clearImportedKeyMaterial(key);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Deleted imported key material for KMS key {0} in {1}", key.getKeyId(), region);
        return key;
    }

    /**
     * Real KMS deletes expired imported key material on its own schedule. With no scheduler here
     * the check runs on the next read of the key instead, which is not observable from outside:
     * nothing can reach a key without going through this path first.
     */
    private KmsKey expireImportedKeyMaterialIfDue(KmsKey key, String region) {
        boolean expires = EXTERNAL_ORIGIN.equals(key.getOrigin())
                && KEY_MATERIAL_EXPIRES.equals(key.getExpirationModel())
                && key.getValidTo() > 0;
        boolean holdsMaterial = !PENDING_IMPORT.equals(key.getKeyState())
                && !PENDING_DELETION.equals(key.getKeyState());
        if (!expires || !holdsMaterial || key.getValidTo() > Instant.now().getEpochSecond()) {
            return key;
        }
        clearImportedKeyMaterial(key);
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Imported key material for KMS key {0} in {1} expired; key is back in PendingImport",
                key.getKeyId(), region);
        return key;
    }

    /**
     * Drops the material but keeps {@code keyMaterialId}: KMS still refuses different material on
     * a later re-import, so what the key was originally given has to outlive the material itself.
     */
    private void clearImportedKeyMaterial(KmsKey key) {
        synchronized (backingKeyMaterialLock) {
            key.setPrivateKeyEncoded(null);
            key.setBackingKeys(new HashMap<>());
            key.setCurrentBackingKeyId(null);
            key.setExpirationModel(null);
            key.setValidTo(0);
            key.setImportParameters(null);
            if (PENDING_DELETION.equals(key.getKeyState())) {
                return;
            }
            key.setEnabled(false);
            key.setKeyState(PENDING_IMPORT);
        }
    }

    private String newImportToken() {
        byte[] token = new byte[IMPORT_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(token);
        return Base64.getEncoder().encodeToString(token);
    }

    private static KmsImportParameters requireCurrentImportToken(KmsKey key) {
        KmsImportParameters parameters = key.getImportParameters();
        if (parameters == null) {
            throw new AwsException("InvalidImportTokenException",
                    "No import parameters are outstanding for this KMS key. "
                            + "Call GetParametersForImport first.", 400);
        }
        if (parameters.getParametersValidTo() < Instant.now().getEpochSecond()) {
            throw new AwsException("ExpiredImportTokenException",
                    "The import token has expired. Call GetParametersForImport for new parameters.", 400);
        }
        return parameters;
    }

    private static String resolveExpirationModel(String expirationModel, Long validTo) {
        String effective = (expirationModel == null || expirationModel.isBlank())
                ? KEY_MATERIAL_EXPIRES : expirationModel;
        switch (effective) {
            case KEY_MATERIAL_EXPIRES -> {
                if (validTo == null) {
                    throw new AwsException("ValidationException",
                            "ValidTo is required when ExpirationModel is KEY_MATERIAL_EXPIRES.", 400);
                }
                validateValidTo(validTo);
            }
            case KEY_MATERIAL_DOES_NOT_EXPIRE -> {
                if (validTo != null) {
                    throw new AwsException("ValidationException",
                            "ValidTo must not be set when ExpirationModel is "
                                    + "KEY_MATERIAL_DOES_NOT_EXPIRE.", 400);
                }
            }
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + expirationModel + "' at 'expirationModel' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: "
                            + "[KEY_MATERIAL_EXPIRES, KEY_MATERIAL_DOES_NOT_EXPIRE]", 400);
        }
        return effective;
    }

    private static void validateValidTo(long validTo) {
        long now = Instant.now().getEpochSecond();
        if (validTo <= now) {
            throw new AwsException("ValidationException",
                    "ValidTo must be a future date and time.", 400);
        }
        if (validTo > now + MAX_KEY_MATERIAL_VALIDITY.toSeconds()) {
            throw new AwsException("ValidationException",
                    "ValidTo must be no more than 365 days from the request date.", 400);
        }
    }

    /**
     * Multi-material rotation, where a symmetric key holds several imported materials at once, is
     * not emulated. NEW_KEY_MATERIAL on a key that already has material is refused outright rather
     * than reported as material that fails to match.
     */
    private static void validateImportType(String importType, KmsKey key) {
        if (importType == null || importType.isBlank()) {
            return;
        }
        switch (importType) {
            case "NEW_KEY_MATERIAL" -> {
                if (key.getKeyMaterialId() != null) {
                    throw new AwsException("UnsupportedOperationException",
                            "Importing additional key material into a KMS key that already has key material "
                                    + "is not supported. Reimport the existing key material instead.", 400);
                }
            }
            case "EXISTING_KEY_MATERIAL" -> {
                if (key.getKeyMaterialId() == null) {
                    throw new AwsException("IncorrectKeyMaterialException",
                            "No key material has ever been imported into this KMS key, so there is no "
                                    + "existing key material to reimport.", 400);
                }
            }
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + importType + "' at 'importType' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: "
                            + "[NEW_KEY_MATERIAL, EXISTING_KEY_MATERIAL]", 400);
        }
    }

    private static void requireSameMaterialAsFirstImport(KmsKey key, String keyMaterialId) {
        if (key.getKeyMaterialId() != null && !key.getKeyMaterialId().equals(keyMaterialId)) {
            throw new AwsException("IncorrectKeyMaterialException",
                    "The key material does not match the key material that was previously imported "
                            + "into this KMS key.", 400);
        }
    }

    /**
     * KMS derives a key material id from the KMS key id and the material itself. Deriving it the
     * same way identifies imported material without keeping a second copy of it: a re-import only
     * has to prove it carries the same bytes, never to have them read back.
     */
    private static String keyMaterialId(String keyId, byte[] material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(keyId.getBytes(StandardCharsets.UTF_8));
            digest.update(material);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AwsException("InternalFailure", "SHA-256 unavailable", 500);
        }
    }

    private static String resolveOrigin(String origin, KmsKeySpec spec) {
        String effective = (origin == null || origin.isBlank()) ? AWS_KMS_ORIGIN : origin;
        return switch (effective) {
            case AWS_KMS_ORIGIN -> effective;
            case EXTERNAL_ORIGIN -> requireImportableSpec(spec);
            case "AWS_CLOUDHSM", "EXTERNAL_KEY_STORE" -> throw new AwsException("UnsupportedOperationException",
                    "Origin " + effective + " is not supported.", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + origin + "' at 'origin' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + "[AWS_KMS, EXTERNAL, AWS_CLOUDHSM, EXTERNAL_KEY_STORE]", 400);
        };
    }

    /**
     * Allows imports for symmetric, HMAC, RSA and ECC key specs. Other key specs are not supported.
     */
    private static String requireImportableSpec(KmsKeySpec spec) {
        if (spec != KmsKeySpec.SYMMETRIC_DEFAULT
                && spec.getKeyType() != KmsKeySpec.KeyType.HMAC
                && spec.getKeyType() != KmsKeySpec.KeyType.RSA
                && spec.getKeyType() != KmsKeySpec.KeyType.ECC) {

            throw new AwsException("UnsupportedOperationException",
                    "Origin EXTERNAL is only supported for SYMMETRIC_DEFAULT, HMAC, RSA and ECC key specs, not "
                            + spec + ".", 400);
        }
        return EXTERNAL_ORIGIN;
    }

    private static void requireExternalOrigin(KmsKey key) {
        if (!EXTERNAL_ORIGIN.equals(key.getOrigin())) {
            throw invalidOrigin(key);
        }
    }

    private static AwsException invalidOrigin(KmsKey key) {
        return new AwsException("UnsupportedOperationException",
                key.getArn() + " origin is " + key.getOrigin() + " which is not valid for this operation.", 400);
    }

    private static void requireNotPendingDeletion(KmsKey key) {
        if (PENDING_DELETION.equals(key.getKeyState())) {
            throw new AwsException("KMSInvalidStateException", key.getArn() + " is pending deletion.", 400);
        }
    }

    private static void requireImportedKeyMaterial(KmsKey key) {
        if (PENDING_IMPORT.equals(key.getKeyState())) {
            throw new AwsException("KMSInvalidStateException", key.getArn() + " is pending import.", 400);
        }
    }

    // ──────────────────────────── Aliases ────────────────────────────

    public void createAlias(String aliasName, String targetKeyId, String region) {
        if (!aliasName.startsWith("alias/")) {
            throw new AwsException("InvalidAliasNameException", "Alias name must begin with 'alias/'", 400);
        }
        KmsKey key = resolveKey(targetKeyId, region); // Validate key exists and normalize to plain key ID
        requireNotPendingDeletion(key);

        String aliasArn = regionResolver.buildArn("kms", region, aliasName);
        KmsAlias alias = new KmsAlias(aliasName, aliasArn, key.getKeyId());
        aliasStore.put(region + "::" + aliasName, alias);
        LOG.infov("Created KMS alias: {0} -> {1}", aliasName, key.getKeyId());
    }

    public void updateAlias(String aliasName, String targetKeyId, String region) {
        String storageKey = region + "::" + aliasName;
        KmsAlias existing = aliasStore.get(storageKey)
                .orElseThrow(() -> aliasNotFound(aliasName, region));

        KmsKey currentKey = resolveKey(existing.getTargetKeyId(), region);
        KmsKey newKey = resolveKey(targetKeyId, region); // Validate key exists and normalize to plain key ID

        requireNotPendingDeletion(newKey);
        if (currentKey.getKeyUsage() != newKey.getKeyUsage() || !sameKeyFamily(currentKey.getKeySpec(), newKey.getKeySpec())) {
            throw new AwsException("ValidationException",
                    "The replacement KMS key must have the same key usage and key type "
                            + "(symmetric, asymmetric, or HMAC) as the alias's current target key.",
                    400);
        }

        existing.setTargetKeyId(newKey.getKeyId());
        aliasStore.put(storageKey, existing);
        LOG.infov("Updated KMS alias: {0} -> {1}", aliasName, newKey.getKeyId());
    }

    public void deleteAlias(String aliasName, String region) {
        String key = region + "::" + aliasName;
        if (aliasStore.get(key).isEmpty()) {
            throw aliasNotFound(aliasName, region);
        }
        aliasStore.delete(key);
    }

    public List<KmsAlias> listAliases(String region) {
        return listAliases(null, region);
    }

    public List<KmsAlias> listAliases(String keyId, String region) {
        String prefix = region + "::";
        List<KmsAlias> all = aliasStore.scan(k -> k.startsWith(prefix));
        if (keyId == null || keyId.isBlank()) {
            return all;
        }
        KmsKey key = resolveKey(keyId, region);
        return all.stream()
                .filter(a -> key.getKeyId().equals(a.getTargetKeyId()))
                .toList();
    }

    // ──────────────────────────── Crypto Ops (Mocks) ────────────────────────────

    // v3 envelope (current): magic "KMS3" + version byte + length-prefixed keyId +
    // length-prefixed backingKeyId + 12-byte GCM IV + AES-256-GCM(ciphertext || 16-byte tag).
    // AAD = every byte up to and including the IV, plus the EncryptionContext fingerprint, so
    // decrypting with the wrong key, the wrong backing key version, or the wrong context, and
    // any bit flip anywhere in the blob, all fail the GCM tag check the same way.
    //
    // v2 blob (legacy, decrypt-only): kms:v2:<keyId>:<nonceHex>:<contextFingerprintHex>:
    // <base64(plaintext)>. This never provided real encryption: the "nonce" and context
    // fingerprint were not bound to anything, so the payload was recoverable plaintext and any
    // ciphertext with a syntactically valid shape "decrypted". Kept read-only so blobs persisted
    // before this fix keep decrypting; Encrypt never produces this format again.
    //
    // v1 blob (legacy, decrypt-only): kms:<keyId>:<base64(plaintext)>. No nonce, no context
    // binding at all. Decrypts only when the caller supplies an empty/null context.
    private static final byte[] ENVELOPE_MAGIC_V3 = {0x4B, 0x4D, 0x53, 0x33}; // "KMS3"
    private static final byte ENVELOPE_VERSION_V3 = 1;
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = KmsKeySpec.SYMMETRIC_DEFAULT.materialByteLength();
    private static final String BLOB_PREFIX_V2 = "kms:v2:";
    private static final String BLOB_PREFIX_V1 = "kms:";
    static final int MAX_PLAINTEXT_BYTES = 4096;
    static final int MAX_CIPHERTEXT_BYTES = 6144;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public byte[] encrypt(String keyId, byte[] plaintext, String region) {
        return encrypt(keyId, plaintext, Map.of(), region);
    }

    public byte[] encrypt(String keyId, byte[] plaintext, Map<String, String> encryptionContext, String region) {
        return encrypt(keyId, plaintext, encryptionContext, null, region).ciphertext();
    }

    public EncryptResult encrypt(String keyId, byte[] plaintext, Map<String, String> encryptionContext,
                                 String encryptionAlgorithm, String region) {
        return encrypt(keyId, plaintext, encryptionContext, encryptionAlgorithm, region, "Encrypt");
    }

    private EncryptResult encrypt(String keyId, byte[] plaintext, Map<String, String> encryptionContext,
                                  String encryptionAlgorithm, String region, String operation) {
        KmsKeySpec.Algorithm algorithm = resolveEncryptionAlgorithm(encryptionAlgorithm);
        validateBlobLength("plaintext", plaintext, MAX_PLAINTEXT_BYTES);
        KmsKey kmsKey = resolveKey(keyId, region);
        validateKeyUsage(kmsKey, KmsKeyUsage.ENCRYPT_DECRYPT, operation);
        validateKeyIsUsableForCryptoOperations(kmsKey);
        validateAlgorithmForSpec(algorithm, kmsKey.getKeySpec());

        if (algorithm != KmsKeySpec.Algorithm.SYMMETRIC_DEFAULT) {
            rejectEncryptionContextForAsymmetricKey(encryptionContext);
            byte[] ciphertext = keyTypes.of(kmsKey.getKeySpec()).encrypt(kmsKey, algorithm, plaintext);
            return new EncryptResult(ciphertext, kmsKey.getArn(), algorithm.getAlgName());
        }

        byte[] envelope = encryptEnvelope(kmsKey, plaintext, encryptionContext);
        return new EncryptResult(envelope, kmsKey.getArn(), algorithm.getAlgName());
    }

    public byte[] decrypt(byte[] ciphertext, String region) {
        return decrypt(ciphertext, Map.of(), region);
    }

    public byte[] decrypt(byte[] ciphertext, Map<String, String> encryptionContext, String region) {
        if (isEnvelopeV3(ciphertext)) {
            EnvelopeV3 envelope = parseEnvelopeV3(ciphertext);
            KmsKey key = resolveEnvelopeKey(envelope.keyId(), region);
            return decryptEnvelopeV3(envelope, key, encryptionContext);
        }
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
        return decodePayload(parsed);
    }

    public String decryptToKeyArn(byte[] ciphertext, String region) {
        try {
            String keyId = isEnvelopeV3(ciphertext) ? parseEnvelopeV3(ciphertext).keyId() : parseBlob(ciphertext).keyId;
            return resolveKey(keyId, region).getArn();
        } catch (AwsException e) {
            return null;
        }
    }

    /**
     * Single-pass decrypt + source-key-ARN resolution. {@link #decrypt} and
     * {@link #decryptToKeyArn} remain independent primitives — neither delegates here.
     */
    public DecryptResult decryptAndResolveKey(
            byte[] ciphertext,
            Map<String, String> encryptionContext,
            String region,
            String requestKeyId
    ) {
        return decryptAndResolveKey(ciphertext, encryptionContext, region, requestKeyId, null);
    }

    public DecryptResult decryptAndResolveKey(
            byte[] ciphertext,
            Map<String, String> encryptionContext,
            String region,
            String requestKeyId,
            String encryptionAlgorithm
    ) {
        KmsKeySpec.Algorithm algorithm = resolveEncryptionAlgorithm(encryptionAlgorithm);
        if (algorithm != KmsKeySpec.Algorithm.SYMMETRIC_DEFAULT) {
            // Raw asymmetric ciphertext carries no key metadata, so real KMS requires KeyId.
            if (requestKeyId == null || requestKeyId.isBlank()) {
                throw new AwsException("ValidationException", "KeyId must not be null", 400);
            }
            KmsKey requestKey = resolveKey(requestKeyId, region);
            validateKeyUsage(requestKey, KmsKeyUsage.ENCRYPT_DECRYPT, "Decrypt");
            validateKeyIsUsableForCryptoOperations(requestKey);
            validateAlgorithmForSpec(algorithm, requestKey.getKeySpec());
            rejectEncryptionContextForAsymmetricKey(encryptionContext);
            byte[] plaintext = keyTypes.of(requestKey.getKeySpec()).decrypt(requestKey, algorithm, ciphertext);
            return new DecryptResult(plaintext, requestKey.getArn(), algorithm.getAlgName());
        }

        if (isEnvelopeV3(ciphertext)) {
            EnvelopeV3 envelope = parseEnvelopeV3(ciphertext);
            KmsKey key = resolveEnvelopeKey(envelope.keyId(), region);
            // A key whose imported material was deleted or expired no longer holds the backing
            // key this blob names; answer with the key's state, as AWS does, not "invalid ciphertext".
            requireImportedKeyMaterial(key);
            byte[] plaintext = decryptEnvelopeV3(envelope, key, encryptionContext);

            if (requestKeyId != null && !requestKeyId.isBlank()) {
                KmsKey requestKey = resolveKey(requestKeyId, region);
                if (!requestKey.getKeyId().equals(key.getKeyId())) {
                    throw new AwsException(
                            "IncorrectKeyException",
                            "The key ID in the request does not identify a CMK that can perform this operation.",
                            400
                    );
                }
                validateKeyIsUsableForCryptoOperations(requestKey);
                return new DecryptResult(plaintext, requestKey.getArn(), algorithm.getAlgName());
            }

            validateKeyIsUsableForCryptoOperations(key);
            return new DecryptResult(plaintext, key.getArn(), algorithm.getAlgName());
        }

        // With the defaulted SYMMETRIC_DEFAULT algorithm, real KMS parses the ciphertext
        // before it compares the algorithm with the named key's spec: Decrypt of a raw RSA
        // ciphertext with an RSA KeyId but no EncryptionAlgorithm answers
        // InvalidCiphertextException, not InvalidKeyUsageException (measured in us-east-1).
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
        byte[] plaintext = decodePayload(parsed);

        if (requestKeyId != null && !requestKeyId.isBlank()) {
            KmsKey requestKey = resolveKey(requestKeyId, region);
            if (!requestKey.getKeyId().equals(parsed.keyId)) {
                throw new AwsException(
                        "IncorrectKeyException",
                        "The key ID in the request does not identify a CMK that can perform this operation.",
                        400
                );
            }
            validateKeyIsUsableForCryptoOperations(requestKey);

            return new DecryptResult(plaintext, requestKey.getArn(), algorithm.getAlgName());
        }

        KmsKey key;
        try {
            key = resolveKey(parsed.keyId, region);
        } catch (AwsException e) {
            key = null;
        }
        if (key == null) {
            return new DecryptResult(plaintext, null, algorithm.getAlgName());
        }
        validateKeyIsUsableForCryptoOperations(key);
        return new DecryptResult(plaintext, key.getArn(), algorithm.getAlgName());
    }

    public record EncryptResult(byte[] ciphertext, String keyArn, String encryptionAlgorithm) {}

    public record DecryptResult(byte[] plaintext, String keyArn, String encryptionAlgorithm) {}

    public record GenerateMacResult(byte[] mac, String keyArn) {}

    public record VerifyMacResult(String keyArn) {}

    /**
     * A parsed v3 envelope. {@code aadHeader} is every byte of the blob up to and including the
     * IV (magic, version, keyId, backingKeyId, IV): the whole header is authenticated, not just
     * checked for equality, so a tampered key id or backing key id fails the GCM tag check
     * instead of silently decrypting under the wrong material.
     */
    private record EnvelopeV3(String keyId, String backingKeyId, byte[] aadHeader, byte[] iv, byte[] ciphertextAndTag) {}

    private static boolean isEnvelopeV3(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length < ENVELOPE_MAGIC_V3.length) {
            return false;
        }
        for (int i = 0; i < ENVELOPE_MAGIC_V3.length; i++) {
            if (ciphertext[i] != ENVELOPE_MAGIC_V3[i]) {
                return false;
            }
        }
        return true;
    }

    /** Any malformed header (bad length prefixes, truncation, unknown version) is a bad ciphertext, never a server fault. */
    private static EnvelopeV3 parseEnvelopeV3(byte[] blob) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(blob);
            byte[] magic = new byte[ENVELOPE_MAGIC_V3.length];
            buffer.get(magic);
            byte version = buffer.get();
            if (version != ENVELOPE_VERSION_V3) {
                throw new AwsException("InvalidCiphertextException", null, 400);
            }
            byte[] keyIdBytes = new byte[buffer.getShort() & 0xFFFF];
            buffer.get(keyIdBytes);
            byte[] backingKeyIdBytes = new byte[buffer.getShort() & 0xFFFF];
            buffer.get(backingKeyIdBytes);
            int headerLength = buffer.position();
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] ciphertextAndTag = new byte[buffer.remaining()];
            buffer.get(ciphertextAndTag);
            if (ciphertextAndTag.length == 0) {
                throw new AwsException("InvalidCiphertextException", null, 400);
            }
            byte[] aadHeader = Arrays.copyOfRange(blob, 0, headerLength + GCM_IV_BYTES);
            return new EnvelopeV3(new String(keyIdBytes, StandardCharsets.UTF_8),
                    new String(backingKeyIdBytes, StandardCharsets.UTF_8), aadHeader, iv, ciphertextAndTag);
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
    }

    private static byte[] buildEnvelopeHeaderAndIv(String keyId, String backingKeyId, byte[] iv) {
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] backingKeyIdBytes = backingKeyId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(ENVELOPE_MAGIC_V3.length + 1
                + 2 + keyIdBytes.length + 2 + backingKeyIdBytes.length + iv.length);
        buffer.put(ENVELOPE_MAGIC_V3);
        buffer.put(ENVELOPE_VERSION_V3);
        buffer.putShort((short) keyIdBytes.length);
        buffer.put(keyIdBytes);
        buffer.putShort((short) backingKeyIdBytes.length);
        buffer.put(backingKeyIdBytes);
        buffer.put(iv);
        return buffer.array();
    }

    /**
     * Builds a v3 envelope: AES-256-GCM under the key's current backing key, with the header
     * (key id + backing key id + IV) and the EncryptionContext fingerprint as AAD.
     */
    private byte[] encryptEnvelope(KmsKey key, byte[] plaintext, Map<String, String> encryptionContext) {
        String backingKeyId;
        byte[] dek;
        synchronized (backingKeyMaterialLock) {
            backingKeyId = key.getCurrentBackingKeyId();
            String materialB64 = key.getBackingKeys() == null ? null : key.getBackingKeys().get(backingKeyId);
            if (materialB64 == null) {
                throw new AwsException("KMSInvalidStateException",
                        "The specified KMS key has no backing key material.", 400);
            }
            dek = decodeBackingMaterial(materialB64);
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        byte[] headerAndIv = buildEnvelopeHeaderAndIv(key.getKeyId(), backingKeyId, iv);
        try {
            Cipher cipher = aesGcmCipher(Cipher.ENCRYPT_MODE, dek, iv,
                    headerAndIv, contextFingerprint(encryptionContext).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext);
            byte[] blob = new byte[headerAndIv.length + ciphertextAndTag.length];
            System.arraycopy(headerAndIv, 0, blob, 0, headerAndIv.length);
            System.arraycopy(ciphertextAndTag, 0, blob, headerAndIv.length, ciphertextAndTag.length);
            return blob;
        } catch (GeneralSecurityException e) {
            throw new AwsException("InternalFailure", "Failed to encrypt: " + e.getMessage(), 500);
        }
    }

    /**
     * Resolves the key named in a v3 envelope header. A lookup failure here can only mean the
     * header was tampered with (this codebase never deletes keys from the store), so it is
     * reported the same way as any other broken envelope: InvalidCiphertextException, never a
     * NotFoundException that would tell an attacker their tampering hit a real key id or not.
     */
    private KmsKey resolveEnvelopeKey(String keyId, String region) {
        try {
            return resolveKey(keyId, region);
        } catch (AwsException e) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
    }

    /**
     * Decrypts a v3 envelope's ciphertext under the given key's backing key material. Does not
     * check the key's enabled/pending-deletion state: callers decide whether and when to run
     * {@link #validateKeyIsUsableForCryptoOperations}, matching the legacy behavior where
     * {@link #decrypt} never checked key state but {@link #decryptAndResolveKey} always did.
     */
    private byte[] decryptEnvelopeV3(EnvelopeV3 envelope, KmsKey key, Map<String, String> encryptionContext) {
        String materialB64;
        synchronized (backingKeyMaterialLock) {
            materialB64 = key.getBackingKeys() == null ? null : key.getBackingKeys().get(envelope.backingKeyId());
        }
        if (materialB64 == null) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
        byte[] dek = decodeBackingMaterial(materialB64);
        try {
            Cipher cipher = aesGcmCipher(Cipher.DECRYPT_MODE, dek, envelope.iv(),
                    envelope.aadHeader(), contextFingerprint(encryptionContext).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(envelope.ciphertextAndTag());
        } catch (GeneralSecurityException e) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
    }

    private static Cipher aesGcmCipher(int mode, byte[] key, byte[] iv, byte[]... aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        for (byte[] part : aad) {
            cipher.updateAAD(part);
        }
        return cipher;
    }

    private static byte[] decodeBackingMaterial(String materialB64) {
        try {
            byte[] material = Base64.getDecoder().decode(materialB64);
            if (material.length != AES_KEY_BYTES) {
                throw new IllegalArgumentException("invalid AES backing key length");
            }
            return material;
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
    }

    private record ParsedBlob(String keyId, String nonce, String contextFingerprint, String payload) {}

    private static ParsedBlob parseBlob(byte[] ciphertext) {
        String data = new String(ciphertext, StandardCharsets.UTF_8);
        if (data.startsWith(BLOB_PREFIX_V2)) {
            // v2: keyId, nonce, contextFingerprint, payload
            String[] parts = data.substring(BLOB_PREFIX_V2.length()).split(":", 4);
            if (parts.length < 4) {
                throw new AwsException("InvalidCiphertextException", null, 400);
            }
            return new ParsedBlob(parts[0], parts[1], parts[2], parts[3]);
        }
        if (data.startsWith(BLOB_PREFIX_V1)) {
            // Legacy v1: kms:<keyId>:<base64>. No nonce, no context binding.
            // Decrypts only when caller supplies empty/null context (fingerprint "").
            String[] parts = data.substring(BLOB_PREFIX_V1.length()).split(":", 2);
            if (parts.length == 2) {
                return new ParsedBlob(parts[0], "", "", parts[1]);
            }
        }
        throw new AwsException("InvalidCiphertextException", null, 400);
    }

    /** A blob whose payload is not valid base64 is a bad ciphertext, not a server fault. */
    private static byte[] decodePayload(ParsedBlob parsed) {
        try {
            return Base64.getDecoder().decode(parsed.payload);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidCiphertextException", null, 400);
        }
    }

    /**
     * Stable fingerprint of an EncryptionContext map. AWS treats EncryptionContext as a
     * case-sensitive exact match, so we hash a length-prefixed serialization of the sorted
     * (key, value) pairs. Returns "" for null / empty, so omitted-context and empty-map
     * ciphertexts are interchangeable (matches AWS).
     */
    private static String contextFingerprint(Map<String, String> ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(ctx).forEach((k, v) -> {
                byte[] kb = k.getBytes(StandardCharsets.UTF_8);
                byte[] vb = (v == null ? "" : v).getBytes(StandardCharsets.UTF_8);
                md.update(ByteBuffer.allocate(4).putInt(kb.length).array());
                md.update(kb);
                md.update(ByteBuffer.allocate(4).putInt(vb.length).array());
                md.update(vb);
            });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AwsException("InternalFailure", "SHA-256 unavailable", 500);
        }
    }

    /**
     * Resolves the wire EncryptionAlgorithm value. Real KMS models the enum as
     * [RSAES_OAEP_SHA_1, RSAES_OAEP_SHA_256, SM2PKE, SYMMETRIC_DEFAULT]. A null or blank
     * value falls back to the SYMMETRIC_DEFAULT default.
     */
    private static KmsKeySpec.Algorithm resolveEncryptionAlgorithm(String encryptionAlgorithm) {
        String name = (encryptionAlgorithm == null || encryptionAlgorithm.isBlank())
                ? "SYMMETRIC_DEFAULT" : encryptionAlgorithm;
        return switch (name) {
            case "SYMMETRIC_DEFAULT" -> KmsKeySpec.Algorithm.SYMMETRIC_DEFAULT;
            case "RSAES_OAEP_SHA_1" -> KmsKeySpec.Algorithm.RSAES_OAEP_SHA_1;
            case "RSAES_OAEP_SHA_256" -> KmsKeySpec.Algorithm.RSAES_OAEP_SHA_256;
            case "SM2PKE" -> throw new AwsException("UnsupportedOperationException",
                    "SM2PKE is not supported.", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + name + "' at 'encryptionAlgorithm' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: "
                            + "[RSAES_OAEP_SHA_1, RSAES_OAEP_SHA_256, SM2PKE, SYMMETRIC_DEFAULT]", 400);
        };
    }

    private static void validateKeyUsage(KmsKey key, KmsKeyUsage keyUsage, String operation) {
        if (keyUsage != key.getKeyUsage()) {
            throw new AwsException("InvalidKeyUsageException",
                    key.getArn() + " key usage is " + key.getKeyUsage() + " which is not valid for "
                            + operation + ".", 400);
        }
    }

    private static void validateAlgorithmForSpec(KmsKeySpec.Algorithm algorithm, KmsKeySpec spec) {
        if (!spec.getAlgorithm().contains(algorithm)) {
            throw new AwsException("InvalidKeyUsageException",
                    "Algorithm " + algorithm.getAlgName() + " is incompatible with key spec " + spec.name() + ".", 400);
        }
    }

    static void validateBlobLength(String member, byte[] value, int max) {
        int length = value == null ? 0 : value.length;
        if (length < 1) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                    + "' failed to satisfy constraint: Member must have length greater than or equal to 1", 400);
        }
        if (max < length) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at '" + member
                    + "' failed to satisfy constraint: Member must have length less than or equal to " + max, 400);
        }
    }

    private static void rejectEncryptionContextForAsymmetricKey(Map<String, String> encryptionContext) {
        if (encryptionContext != null && !encryptionContext.isEmpty()) {
            throw new AwsException("ValidationException",
                    "EncryptionContext is not supported when encrypting/decrypting with asymmetric CMKs.", 400);
        }
    }

    private static final List<String> SIGNING_ALGORITHMS = List.of(
            "RSASSA_PSS_SHA_256", "RSASSA_PSS_SHA_384", "RSASSA_PSS_SHA_512",
            "RSASSA_PKCS1_V1_5_SHA_256", "RSASSA_PKCS1_V1_5_SHA_384", "RSASSA_PKCS1_V1_5_SHA_512",
            "ECDSA_SHA_256", "ECDSA_SHA_384", "ECDSA_SHA_512", "ED25519_SHA_512", "ED25519_PH_SHA_512",
            "SM2DSA", "ML_DSA_SHAKE_256");

    private static final List<String> MAC_ALGORITHMS =
            List.of("HMAC_SHA_384", "HMAC_SHA_256", "HMAC_SHA_224", "HMAC_SHA_512");

    private static final Map<KmsKeySpec.Algorithm, Integer> DIGEST_BYTES = Map.of(
            KmsKeySpec.Algorithm.RSASSA_PSS_SHA_256, 32,
            KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_256, 32,
            KmsKeySpec.Algorithm.ECDSA_SHA_256, 32,
            KmsKeySpec.Algorithm.RSASSA_PSS_SHA_384, 48,
            KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_384, 48,
            KmsKeySpec.Algorithm.ECDSA_SHA_384, 48,
            KmsKeySpec.Algorithm.RSASSA_PSS_SHA_512, 64,
            KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_512, 64,
            KmsKeySpec.Algorithm.ECDSA_SHA_512, 64,
            KmsKeySpec.Algorithm.ED25519_PH_SHA_512, 64);

    public byte[] sign(String keyId, byte[] message, String algorithm, String region) {
        return sign(keyId, message, algorithm, RAW, region);
    }

    public byte[] sign(String keyId, byte[] message, String algorithm, KmsMessageType messageType, String region) {
        KmsKeySpec.Algorithm signingAlgorithm = resolveSigningAlgorithm(algorithm);
        KmsKey kmsKey = resolveKey(keyId, region);
        validateKeyUsage(kmsKey, KmsKeyUsage.SIGN_VERIFY, "Sign");
        validateKeyIsUsableForCryptoOperations(kmsKey);
        validateAlgorithmForSpec(signingAlgorithm, kmsKey.getKeySpec());
        validateDigestLength(signingAlgorithm, messageType, message);
        try {
            return keyTypes.of(kmsKey.getKeySpec()).sign(kmsKey, message, signingAlgorithm, messageType);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to sign message: " + e.getMessage(), 500);
        }
    }

    private static void validateDigestLength(KmsKeySpec.Algorithm algorithm, KmsMessageType messageType,
                                             byte[] message) {
        Integer expected = DIGEST_BYTES.get(algorithm);
        if (messageType == KmsMessageType.DIGEST && expected != null && expected != message.length) {
            throw new AwsException("ValidationException",
                    "Digest is invalid length for algorithm " + algorithm.getAlgName() + ".", 400);
        }
    }

    private static KmsKeySpec.Algorithm resolveSigningAlgorithm(String algorithm) {
        if (!"SYMMETRIC_DEFAULT".equals(algorithm)) {
            validateEnumMember("signingAlgorithm", algorithm, SIGNING_ALGORITHMS);
        }
        return KmsKeySpec.Algorithm.valueOf(algorithm);
    }

    private static void validateEnumMember(String member, String value, List<String> allowed) {
        if (value == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + member + "' failed to satisfy "
                            + "constraint: Member must not be null", 400);
        }
        if (!allowed.contains(value)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + value + "' at '" + member + "' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: ["
                            + String.join(", ", allowed) + "]", 400);
        }
    }

    public void verify(String keyId, byte[] message, byte[] signature, String algorithm, String region) {
        verify(keyId, message, signature, algorithm, RAW, region);
    }

    public void verify(String keyId, byte[] message, byte[] signature, String algorithm, KmsMessageType messageType, String region) {
        KmsKeySpec.Algorithm signingAlgorithm = resolveSigningAlgorithm(algorithm);
        KmsKey kmsKey = resolveKey(keyId, region);
        validateKeyUsage(kmsKey, KmsKeyUsage.SIGN_VERIFY, "Verify");
        validateKeyIsUsableForCryptoOperations(kmsKey);
        validateAlgorithmForSpec(signingAlgorithm, kmsKey.getKeySpec());
        validateDigestLength(signingAlgorithm, messageType, message);
        boolean valid;
        try {
            valid = keyTypes.of(kmsKey.getKeySpec()).verify(kmsKey, message, signature, signingAlgorithm, messageType);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to verify signature: " + e.getMessage(), 500);
        }
        if (!valid) {
            throw new AwsException("KMSInvalidSignatureException", null, 400);
        }
    }

    public byte[] generateMac(String keyId, byte[] message, String algorithm, String region) {
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, "GenerateMac", region);
        return generateMac(kmsKey, message, algorithm);
    }

    public GenerateMacResult generateMacAndResolveKey(String keyId, byte[] message, String algorithm, String region) {
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, "GenerateMac", region);
        return new GenerateMacResult(generateMac(kmsKey, message, algorithm), kmsKey.getArn());
    }

    private byte[] generateMac(KmsKey kmsKey, byte[] message, String algorithm) {
        validateBlobLength("message", message, MAX_PLAINTEXT_BYTES);

        try {
            return keyTypes.of(kmsKey.getKeySpec()).generateMac(kmsKey, message, algorithm);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to generate MAC: " + e.getMessage(), 500);
        }
    }

    public void verifyMac(String keyId, byte[] message, byte[] mac, String algorithm, String region) {
        validateBlobLength("mac", mac, MAX_CIPHERTEXT_BYTES);
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, "VerifyMac", region);
        verifyMac(kmsKey, message, mac, algorithm);
    }

    public VerifyMacResult verifyMacAndResolveKey(String keyId, byte[] message, byte[] mac, String algorithm, String region) {
        validateBlobLength("mac", mac, MAX_CIPHERTEXT_BYTES);
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, "VerifyMac", region);
        verifyMac(kmsKey, message, mac, algorithm);
        return new VerifyMacResult(kmsKey.getArn());
    }

    private void verifyMac(KmsKey kmsKey, byte[] message, byte[] mac, String algorithm) {
        byte[] expected = generateMac(kmsKey, message, algorithm);
        if (!MessageDigest.isEqual(expected, mac)) {
            throw new AwsException("KMSInvalidMacException", null, 400);
        }
    }

    private KmsKey validateMacOperationKey(String keyId, String algorithm, String operation, String region) {
        validateEnumMember("macAlgorithm", algorithm, MAC_ALGORITHMS);
        KmsKey kmsKey = resolveKey(keyId, region);
        validateKeyUsage(kmsKey, KmsKeyUsage.GENERATE_VERIFY_MAC, operation);
        validateKeyIsUsableForCryptoOperations(kmsKey);
        validateAlgorithmForSpec(KmsKeySpec.Algorithm.valueOf(algorithm), kmsKey.getKeySpec());
        return kmsKey;
    }


    public Map<String, Object> generateDataKey(String keyId, String keySpec, Integer numberOfBytes, String region) {
        return generateDataKey(keyId, keySpec, numberOfBytes, Map.of(), region);
    }

    public Map<String, Object> generateDataKey(String keyId, String keySpec, Integer numberOfBytes,
                                               Map<String, String> encryptionContext, String region) {
        return generateDataKey(keyId, keySpec, numberOfBytes, encryptionContext, region, "GenerateDataKey");
    }

    Map<String, Object> generateDataKeyWithoutPlaintext(String keyId, String keySpec, Integer numberOfBytes,
                                                        Map<String, String> encryptionContext, String region) {
        Map<String, Object> dataKey = generateDataKey(keyId, keySpec, numberOfBytes, encryptionContext, region,
                "GenerateDataKeyWithoutPlaintext");
        dataKey.remove("Plaintext");
        return dataKey;
    }

    private Map<String, Object> generateDataKey(String keyId, String keySpec, Integer numberOfBytes,
                                                Map<String, String> encryptionContext, String region,
                                                String operation) {
        KmsKey key = resolveKey(keyId, region);
        if ((keySpec == null) == (numberOfBytes == null)) {
            throw new AwsException("ValidationException", "Please specify either number of bytes or key spec.", 400);
        }
        validateKeyUsage(key, KmsKeyUsage.ENCRYPT_DECRYPT, operation);
        validateKeyIsUsableForCryptoOperations(key);
        if (KmsKeySpec.SYMMETRIC_DEFAULT != key.getKeySpec()) {
            throw new AwsException("InvalidKeyUsageException", "You cannot generate a data key with an asymmetric CMK", 400);
        }
        int len = keySpec == null ? numberOfBytes : "AES_128".equals(keySpec) ? 16 : 32;

        byte[] plaintext = new byte[len];
        secureRandom.nextBytes(plaintext);

        EncryptResult encrypted = encrypt(keyId, plaintext, encryptionContext, null, region, operation);

        Map<String, Object> result = new HashMap<>();
        result.put("Plaintext", plaintext);
        result.put("CiphertextBlob", encrypted.ciphertext());
        result.put("KeyId", encrypted.keyArn());
        return result;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void tagResource(String keyId, Map<String, String> tags, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        ReservedTags.rejectReservedTagsOnUpdate(tags);
        key.getTags().putAll(tags);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    public void untagResource(String keyId, List<String> tagKeys, String region) {
        KmsKey key = resolveKey(keyId, region);
        requireNotPendingDeletion(key);
        tagKeys.forEach(key.getTags()::remove);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private KmsKey resolveKey(String keyIdOrArn, String region) {
        if (AwsArnUtils.isArnFor(keyIdOrArn, "kms")) {
            String arnRegion = AwsArnUtils.parse(keyIdOrArn).region();
            if (!region.equals(arnRegion)) {
                throw new AwsException("NotFoundException", "Invalid arn " + arnRegion, 400);
            }
        }
        String id = keyIdOrArn;
        // Alias arn
        if (id.contains(":alias/")) {
            String aliasName = id.substring(id.lastIndexOf(":") + 1);
            String aliasKey = region + "::" + aliasName;
            id = aliasStore.get(aliasKey)
                    .map(KmsAlias::getTargetKeyId)
                    .orElseThrow(() -> aliasNotFound(aliasName, region));
        } else if (AwsArnUtils.isArnFor(id, "kms")) {
            // Key arn
            id = id.substring(id.lastIndexOf("/") + 1);
        } else if (id.startsWith("alias/")) {
            // Alias name
            String aliasKey = region + "::" + id;
            id = aliasStore.get(aliasKey)
                    .map(KmsAlias::getTargetKeyId)
                    .orElseThrow(() -> aliasNotFound(keyIdOrArn, region));
        }

        // Key id
        String keyId = id;
        KmsKey key = keyStore.get(region + "::" + id)
                .orElseThrow(() -> keyNotFound(keyIdOrArn, keyId, region));
        key = expireImportedKeyMaterialIfDue(key, region);
        key = ensureBackingKeyMaterial(key, region);
        return key;
    }

    private AwsException aliasNotFound(String aliasName, String region) {
        return new AwsException("NotFoundException",
                "Alias " + regionResolver.buildArn("kms", region, aliasName) + " is not found.", 400);
    }

    private static final Pattern KEY_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|mrk-[0-9a-fA-F]{32}");

    private AwsException keyNotFound(String keyIdOrArn, String keyId, String region) {
        if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
            String shown = AwsArnUtils.isArnFor(keyIdOrArn, "kms") ? keyId : "'" + keyId + "'";
            return new AwsException("NotFoundException", "Invalid keyId " + shown, 400);
        }
        return new AwsException("NotFoundException",
                "Key '" + regionResolver.buildArn("kms", region, "key/" + keyId) + "' does not exist", 400);
    }

    private static void validateKeyIsUsableForCryptoOperations(KmsKey key) {
        if (PENDING_DELETION.equals(key.getKeyState())) {
            throw new AwsException(
                    "KMSInvalidStateException",
                    key.getArn() + " is pending deletion.",
                    400
            );
        }
        requireImportedKeyMaterial(key);
        if (!key.isEnabled()) {
            throw new AwsException(
                    "DisabledException",
                    key.getArn() + " is disabled.",
                    400
            );
        }
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (KmsKey key : keyStore.scan(k -> true)) {
            String arn = key.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "kms:key", "kms",
                    parsed.region(), parsed.accountId(),
                    key.getCreationDate() > 0 ? Instant.ofEpochSecond(key.getCreationDate()) : Instant.now(),
                    key.getTags() != null ? key.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("kms:key", "kms", true));
    }
}
