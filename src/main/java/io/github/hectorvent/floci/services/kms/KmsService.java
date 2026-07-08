package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsGrant;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.kms.model.KmsMessageType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequenceGenerator;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.DIGEST;
import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.RAW;

@ApplicationScoped
public class KmsService {

    private static final Logger LOG = Logger.getLogger(KmsService.class);

    private final StorageBackend<String, KmsKey> keyStore;
    private final StorageBackend<String, KmsAlias> aliasStore;
    private final StorageBackend<String, KmsGrant> grantStore;
    private final RegionResolver regionResolver;
    private final SecureRandom secureRandom;

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
    }

    public byte[] generateRandom(int numberOfBytes) {
        if (numberOfBytes < 1 || numberOfBytes > 1024) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + numberOfBytes + "' at 'numberOfBytes' failed to satisfy constraint: Member must have value greater than or equal to 1 and less than or equal to 1024",
                    400);
        }
        byte[] bytes = new byte[numberOfBytes];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private String buildDefaultKeyPolicy() {
        String account = regionResolver.getAccountId();
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Enable IAM User Permissions\"," +
               "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::" + account + ":root\"}," +
               "\"Action\":\"kms:*\",\"Resource\":\"*\"}]}";
    }

    public KmsKey createKey(String description, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", null, Map.of(), region);
    }

    public KmsKey createKey(String description, String policy, Map<String, String> tags, String region) {
        return createKey(description, "ENCRYPT_DECRYPT", "SYMMETRIC_DEFAULT", policy, tags, region);
    }

    public KmsKey createKey(String description, String keyUsage, String keySpec, String policy, Map<String, String> tags, String region) {
        String keyId = resolveKeyId(tags);
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
        key.setDescription(description);
        key.setKeyUsage(effectiveUsage);
        key.setKeySpec(effectiveSpec);
        key.setPolicy(policy != null ? policy : buildDefaultKeyPolicy());
        key.getTags().putAll(ReservedTags.stripReservedTags(tags));

        generateKeyMaterial(key);

        keyStore.put(region + "::" + keyId, key);
        LOG.infov("Created KMS key: {0} ({1}/{2}) in {3}", keyId, key.getKeyUsage(), key.getKeySpec(), region);
        return key;
    }

    private String resolveKeyId(Map<String, String> tags) {
        String overrideId = ReservedTags.extractOverrideKeyId(tags);
        if (overrideId == null) {
            return UUID.randomUUID().toString();
        }

        String normalized = overrideId.trim();
        if (normalized.isEmpty()) {
            throw new AwsException("TagException", "Override resource ID must not be blank.", 400);
        }
        if (normalized.length() > 256) {
            throw new AwsException("TagException", "Override resource ID must be 256 characters or fewer.", 400);
        }
        return normalized;
    }

    private void generateKeyMaterial(KmsKey key) {
        KmsKeySpec spec = key.getKeySpec();
        try {
            switch (spec.getKeyType()) {
                case HMAC -> {
                    // HMAC keys are symmetric byte strings; generate outside the try block
                    // so ValidationException (400) isn't rewrapped as InternalFailure (500).
                    byte[] material = new byte[hmacKeyByteLength(spec)];
                    new SecureRandom().nextBytes(material);
                    key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(material));
                }
                case SYMMETRIC -> {/* // Use existing mock behavior for symmetric keys */}
                case RSA -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    int size = Integer.parseInt(spec.name().substring(4));
                    generator.initialize(size);
                    KeyPair pair = generator.generateKeyPair();
                    key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                    key.setPublicKeyEncoded(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
                }
                case ECC -> {
                    String curveName = spec.curveName();

                    // For secp256k1 (ECC_SECG_P256K1), instantiate BC's SPI directly.
                    // JCA's ClassLoader.loadClass cannot find BC SPI classes in GraalVM native image
                    // unless they are allocated directly in code (GraalVM escape analysis eliminates
                    // unused allocations, keeping them out of the native image type registry).
                    KeyPairGenerator generator = isSecgP256k1(spec)
                            ? new KeyPairGeneratorSpi.EC()
                            : KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec(curveName));
                    KeyPair pair = generator.generateKeyPair();
                    key.setPrivateKeyEncoded(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                    key.setPublicKeyEncoded(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
                }
                default ->
                        throw new AwsException("InvalidCustomerMasterKeySpecException", "Unsupported key spec: " + spec, 400);
            }
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new AwsException("InternalFailure", "Failed to generate key material: " + e.getMessage(), 500);
        }
    }

    public KmsKey getPublicKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        KmsKeySpec spec = key.getKeySpec();
        if (KmsKeySpec.SYMMETRIC_DEFAULT == spec || isHmac(spec)) {
            throw new AwsException("UnsupportedOperationException", "GetPublicKey is not supported for symmetric keys.", 400);
        }
        return key;
    }

    private static boolean isHmac(KmsKeySpec spec) {
        return spec != null && spec.getKeyType() == KmsKeySpec.KeyType.HMAC;
    }

    private static void validateKeyUsageForSpec(KmsKeyUsage keyUsage, KmsKeySpec spec) {
        if (isHmac(spec) && KmsKeyUsage.GENERATE_VERIFY_MAC != keyUsage) {
            throw new AwsException("ValidationException",
                    "KeyUsage " + keyUsage + " is not compatible with KeySpec " + spec
                            + ". HMAC key specs require KeyUsage GENERATE_VERIFY_MAC.",
                    400);
        }
        if (KmsKeyUsage.GENERATE_VERIFY_MAC == keyUsage && !isHmac(spec)) {
            throw new AwsException("ValidationException",
                    "KeyUsage GENERATE_VERIFY_MAC requires an HMAC KeySpec (HMAC_224, HMAC_256, HMAC_384, or HMAC_512).",
                    400);
        }
    }

    private static int hmacKeyByteLength(KmsKeySpec spec) {
        return switch (spec) {
            case HMAC_224 -> 28;
            case HMAC_256 -> 32;
            case HMAC_384 -> 48;
            case HMAC_512 -> 64;
            default -> throw new AwsException("InvalidCustomerMasterKeySpecException",
                    "Unsupported HMAC key spec: " + spec, 400);
        };
    }

    public KmsKey describeKey(String keyId, String region) {
        return resolveKey(keyId, region);
    }

    public List<KmsKey> listKeys(String region) {
        String prefix = region + "::";
        return keyStore.scan(k -> k.startsWith(prefix));
    }

    public KmsGrant createGrant(String keyId, String granteePrincipal, List<String> operations, String region) {
        return createGrant(keyId, granteePrincipal, operations, null, region);
    }

    public KmsGrant createGrant(String keyId, String granteePrincipal, List<String> operations,
                                String retiringPrincipal, String region) {
        if (keyId == null || keyId.isBlank()) {
            throw new AwsException("ValidationException", "KeyId is required", 400);
        }
        if (granteePrincipal == null || granteePrincipal.isBlank()) {
            throw new AwsException("ValidationException", "GranteePrincipal is required", 400);
        }
        if (operations == null || operations.isEmpty()) {
            throw new AwsException("ValidationException", "Operations is required", 400);
        }

        KmsKey key = resolveKey(keyId, region);
        String grantId = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(tokenBytes);

        KmsGrant grant = new KmsGrant();
        grant.setGrantId(grantId);
        grant.setGrantToken(Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes));
        grant.setKeyId(key.getKeyId());
        grant.setKeyArn(key.getArn());
        grant.setGranteePrincipal(granteePrincipal);
        grant.setRetiringPrincipal(retiringPrincipal);
        grant.setOperations(new ArrayList<>(operations));

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
        if (grant.getRetiringPrincipal() != null) {
            result.put("RetiringPrincipal", grant.getRetiringPrincipal());
        }
        return result;
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

    public void updateKeyDescription(String keyId, String description, String region) {
        KmsKey key = resolveKey(keyId, region);
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

    public void enableKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        if ("PendingDeletion".equals(key.getKeyState())) {
            throw new AwsException("KMSInvalidStateException",
                    "KMS key " + key.getKeyId() + " is pending deletion.", 400);
        }
        key.setEnabled(true);
        key.setKeyState("Enabled");
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Enabled KMS key: {0} in {1}", key.getKeyId(), region);
    }

    public void disableKey(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        key.setEnabled(false);
        key.setKeyState("Disabled");
        keyStore.put(region + "::" + key.getKeyId(), key);
        LOG.infov("Disabled KMS key: {0} in {1}", key.getKeyId(), region);
    }

    private static final int ON_DEMAND_ROTATION_LIMIT = 25;
    public String rotateKeyOnDemand(String keyId, String region) {
        KmsKey key = resolveKey(keyId, region);
        if (!key.isEnabled()) {
            throw new AwsException("DisabledException",
                    "KMS key " + key.getKeyId() + " is disabled.", 400);
        }
        validateRotationSupported(key);
        if (key.getOnDemandRotationCount() >= ON_DEMAND_ROTATION_LIMIT) {
            throw new AwsException("LimitExceededException",
                    "On-demand rotation quota for KMS key " + key.getKeyId() + " is exceeded.", 400);
        }
        key.setOnDemandRotationCount(key.getOnDemandRotationCount() + 1);
        keyStore.put(region + "::" + key.getKeyId(), key);
        return key.getKeyId();
    }

    private void validateRotationSupported(KmsKey key) {
        if (KmsKeyUsage.ENCRYPT_DECRYPT != key.getKeyUsage()
                || KmsKeySpec.SYMMETRIC_DEFAULT != key.getKeySpec()) {
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
        KmsKey key = resolveKey(targetKeyId, region); // Validate key exists and normalize to plain key ID

        String aliasArn = regionResolver.buildArn("kms", region, aliasName);
        KmsAlias alias = new KmsAlias(aliasName, aliasArn, key.getKeyId());
        aliasStore.put(region + "::" + aliasName, alias);
        LOG.infov("Created KMS alias: {0} -> {1}", aliasName, key.getKeyId());
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

    // v2 blob: kms:v2:<keyId>:<nonceHex>:<contextFingerprintHex>:<base64(plaintext)>
    // Nonce makes Encrypt non-deterministic; contextFingerprint binds EncryptionContext as AAD.
    // Legacy v1 (kms:<keyId>:<base64>) still accepted on Decrypt for persistent-store back-compat.
    private static final String BLOB_PREFIX_V2 = "kms:v2:";
    private static final String BLOB_PREFIX_V1 = "kms:";
    private static final int NONCE_BYTES = 8;
    private static final int MIN_MAC_MESSAGE_BYTES = 1;
    private static final int MAX_MAC_MESSAGE_BYTES = 4096;
    private static final int MIN_MAC_BYTES = 1;
    private static final int MAX_MAC_BYTES = 6144;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public byte[] encrypt(String keyId, byte[] plaintext, String region) {
        return encrypt(keyId, plaintext, Map.of(), region);
    }

    public byte[] encrypt(String keyId, byte[] plaintext, Map<String, String> encryptionContext, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);

        byte[] nonceBytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonceHex = HexFormat.of().formatHex(nonceBytes);

        String blob = BLOB_PREFIX_V2
                + kmsKey.getKeyId() + ":"
                + nonceHex + ":"
                + contextFingerprint(encryptionContext) + ":"
                + Base64.getEncoder().encodeToString(plaintext);
        return blob.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] decrypt(byte[] ciphertext, String region) {
        return decrypt(ciphertext, Map.of(), region);
    }

    public byte[] decrypt(byte[] ciphertext, Map<String, String> encryptionContext, String region) {
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
        return Base64.getDecoder().decode(parsed.payload);
    }

    public String decryptToKeyArn(byte[] ciphertext, String region) {
        try {
            return resolveKey(parseBlob(ciphertext).keyId, region).getArn();
        } catch (AwsException e) {
            return null;
        }
    }

    /**
     * Single-pass decrypt + source-key-ARN resolution. {@link #decrypt} and
     * {@link #decryptToKeyArn} remain independent primitives — neither delegates here.
     */
    public DecryptResult decryptAndResolveKey(byte[] ciphertext, Map<String, String> encryptionContext, String region) {
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
        byte[] plaintext = Base64.getDecoder().decode(parsed.payload);
        String keyArn;
        try {
            keyArn = resolveKey(parsed.keyId, region).getArn();
        } catch (AwsException e) {
            keyArn = null;
        }
        return new DecryptResult(plaintext, keyArn);
    }

    public record DecryptResult(byte[] plaintext, String keyArn) {}

    public record GenerateMacResult(byte[] mac, String keyArn) {}

    public record VerifyMacResult(String keyArn) {}

    private record ParsedBlob(String keyId, String nonce, String contextFingerprint, String payload) {}

    private static ParsedBlob parseBlob(byte[] ciphertext) {
        String data = new String(ciphertext, StandardCharsets.UTF_8);
        if (data.startsWith(BLOB_PREFIX_V2)) {
            // v2: keyId, nonce, contextFingerprint, payload
            String[] parts = data.substring(BLOB_PREFIX_V2.length()).split(":", 4);
            if (parts.length < 4) {
                throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
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
        throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
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

    public byte[] sign(String keyId, byte[] message, String algorithm, String region) {
        return sign(keyId, message, algorithm, RAW, region);
    }

    public byte[] sign(String keyId, byte[] message, String algorithm, KmsMessageType messageType, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        if (KmsKeySpec.SYMMETRIC_DEFAULT == kmsKey.getKeySpec()) {
            throw new AwsException("UnsupportedOperationException", "Unsupported key spec for signing.", 400);
        }

        try {
            PrivateKey privateKey = loadPrivateKey(kmsKey.getPrivateKeyEncoded(), kmsKey.getKeySpec());
            String jcaAlgo = switch (messageType) {
                // If message is already a digest, we need a "NONEwith..." algorithm
                case DIGEST -> "NONEwith" + (kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.RSA ? "RSA" : "ECDSA");
                case RAW -> KmsKeySpec.getSignVerifyAlgorithm(algorithm).getJavaName();
            };
            if (messageType == DIGEST && isPKCS1v1_5(kmsKey.getKeySpec().getAlgorithm().getFirst())) {
                // RFC 8017 9.2: PKCS#1 v1.5 signs DigestInfo{hashOID, digest}, not the
                // bare digest, so the signature validates with external verifiers and
                // real KMS (NONEwithRSA only pads the bytes it is given).
                message = wrapInDigestInfo(message, algorithm);
            }
            if (isSecgP256k1(kmsKey.getKeySpec())) {
                return signSecgP256k1(privateKey, message, jcaAlgo);
            }
            Signature sig = Signature.getInstance(jcaAlgo);
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to sign message: " + e.getMessage(), 500);
        }
    }

    public boolean verify(String keyId, byte[] message, byte[] signature, String algorithm, String region) {
        return verify(keyId, message, signature, algorithm, RAW, region);
    }

    public boolean verify(String keyId, byte[] message, byte[] signature, String algorithm, KmsMessageType messageType, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        if (KmsKeySpec.SYMMETRIC_DEFAULT == kmsKey.getKeySpec()) {
            return false;
        }

        try {
            PublicKey publicKey = loadPublicKey(kmsKey.getPublicKeyEncoded(), kmsKey.getKeySpec());
            String jcaAlgo = KmsKeySpec.getSignVerifyAlgorithm(algorithm).getJavaName();

            if (DIGEST.equals(messageType)) {
                jcaAlgo = "NONEwith" + (kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.RSA ? "RSA" : "ECDSA");
                if (isPKCS1v1_5(kmsKey.getKeySpec().getAlgorithm().getFirst())) {
                    // Mirror sign(): verify against DigestInfo{hashOID, digest} (RFC 8017 9.2).
                    message = wrapInDigestInfo(message, algorithm);
                }
            }
            if (isSecgP256k1(kmsKey.getKeySpec())) {
                return verifySecgP256k1(publicKey, message, signature, jcaAlgo);
            }
            Signature sig = Signature.getInstance(jcaAlgo);
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            LOG.warnv("Verification failed for key {0}: {1}", keyId, e.getMessage());
            return false;
        }
    }

    public byte[] generateMac(String keyId, byte[] message, String algorithm, String region) {
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, region);
        return generateMac(kmsKey, message, algorithm);
    }

    public GenerateMacResult generateMacAndResolveKey(String keyId, byte[] message, String algorithm, String region) {
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, region);
        return new GenerateMacResult(generateMac(kmsKey, message, algorithm), kmsKey.getArn());
    }

    private byte[] generateMac(KmsKey kmsKey, byte[] message, String algorithm) {
        validateMacMessageLength(message);

        try {
            byte[] keyBytes = Base64.getDecoder().decode(kmsKey.getPrivateKeyEncoded());
            String jcaAlgorithm = mapMacAlgorithm(algorithm);
            Mac mac = Mac.getInstance(jcaAlgorithm);
            mac.init(new SecretKeySpec(keyBytes, jcaAlgorithm));
            mac.update(message);
            return mac.doFinal();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailure", "Failed to generate MAC: " + e.getMessage(), 500);
        }
    }

    public void verifyMac(String keyId, byte[] message, byte[] mac, String algorithm, String region) {
        validateMacLength(mac);
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, region);
        verifyMac(kmsKey, message, mac, algorithm);
    }

    public VerifyMacResult verifyMacAndResolveKey(String keyId, byte[] message, byte[] mac, String algorithm, String region) {
        validateMacLength(mac);
        KmsKey kmsKey = validateMacOperationKey(keyId, algorithm, region);
        verifyMac(kmsKey, message, mac, algorithm);
        return new VerifyMacResult(kmsKey.getArn());
    }

    private void verifyMac(KmsKey kmsKey, byte[] message, byte[] mac, String algorithm) {
        byte[] expected = generateMac(kmsKey, message, algorithm);
        if (!MessageDigest.isEqual(expected, mac)) {
            throw new AwsException("KMSInvalidMacException", "The MAC is not valid.", 400);
        }
    }

    private KmsKey validateMacOperationKey(String keyId, String algorithm, String region) {
        KmsKey kmsKey = resolveKey(keyId, region);
        KmsKeySpec spec = kmsKey.getKeySpec();
        if (!isHmac(spec) || !KmsKeyUsage.GENERATE_VERIFY_MAC.equals(kmsKey.getKeyUsage())) {
            throw new AwsException("InvalidKeyUsageException",
                    "MAC operations require an HMAC key with KeyUsage GENERATE_VERIFY_MAC.", 400);
        }

        String expectedAlgorithm = kmsKey.getKeySpec().getAlgorithm().getFirst().getAlgName();
        if (!Objects.equals(expectedAlgorithm, algorithm)) {
            throw new AwsException("InvalidKeyUsageException",
                    "MacAlgorithm " + algorithm + " is not valid for KeySpec " + spec + ".", 400);
        }
        return kmsKey;
    }

    private String mapMacAlgorithm(String awsAlgo) {
        return switch (awsAlgo) {
            case "HMAC_SHA_224" -> "HmacSHA224";
            case "HMAC_SHA_256" -> "HmacSHA256";
            case "HMAC_SHA_384" -> "HmacSHA384";
            case "HMAC_SHA_512" -> "HmacSHA512";
            default -> throw new AwsException("InvalidMacAlgorithmException", "Unsupported MAC algorithm: " + awsAlgo, 400);
        };
    }

    private static void validateMacMessageLength(byte[] message) {
        int length = message == null ? 0 : message.length;
        if (length < MIN_MAC_MESSAGE_BYTES || length > MAX_MAC_MESSAGE_BYTES) {
            throw new AwsException("ValidationException",
                    "Message must be between 1 and 4096 bytes for MAC operations.", 400);
        }
    }

    private static void validateMacLength(byte[] mac) {
        int length = mac == null ? 0 : mac.length;
        if (length < MIN_MAC_BYTES || length > MAX_MAC_BYTES) {
            throw new AwsException("ValidationException",
                    "Mac must be between 1 and 6144 bytes for VerifyMac.", 400);
        }
    }

    private PrivateKey loadPrivateKey(String encoded, KmsKeySpec spec) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (isSecgP256k1(spec)) {
            // For secp256k1, use BC's KeyFactorySpi.EC directly as AsymmetricKeyInfoConverter.
            // This bypasses JCA and ClassLoader.loadClass; the allocation is live (generatePrivate
            // is called), so GraalVM's escape analysis keeps the class in the native image.
            AsymmetricKeyInfoConverter converter = new KeyFactorySpi.EC();
            return converter.generatePrivate(PrivateKeyInfo.getInstance(decoded));
        }
        return buildKeyFactory(spec).generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private PublicKey loadPublicKey(String encoded, KmsKeySpec spec) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (isSecgP256k1(spec)) {
            AsymmetricKeyInfoConverter converter = new KeyFactorySpi.EC();
            return converter.generatePublic(SubjectPublicKeyInfo.getInstance(decoded));
        }
        return buildKeyFactory(spec).generatePublic(new X509EncodedKeySpec(decoded));
    }

    /**
     * Wraps a pre-computed digest in the ASN.1 {@code DigestInfo} structure that PKCS#1
     * v1.5 signing prepends before padding (RFC 8017 9.2). Needed for {@code MessageType=DIGEST}
     * RSA signatures because {@code NONEwithRSA} pads only the bytes it is given.
     */
    private byte[] wrapInDigestInfo(byte[] digest, String algorithm) {
        ASN1ObjectIdentifier hashOid;
        if (algorithm.endsWith("SHA_256")) {
            hashOid = NISTObjectIdentifiers.id_sha256;
        } else if (algorithm.endsWith("SHA_384")) {
            hashOid = NISTObjectIdentifiers.id_sha384;
        } else if (algorithm.endsWith("SHA_512")) {
            hashOid = NISTObjectIdentifiers.id_sha512;
        } else {
            throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + algorithm, 400);
        }
        try {
            return new DigestInfo(new AlgorithmIdentifier(hashOid, DERNull.INSTANCE), digest).getEncoded();
        } catch (IOException e) {
            throw new AwsException("InternalFailure", "Failed to encode DigestInfo: " + e.getMessage(), 500);
        }
    }

    public Map<String, Object> generateDataKey(String keyId, String keySpec, int numberOfBytes, String region) {
        return generateDataKey(keyId, keySpec, numberOfBytes, Map.of(), region);
    }

    public Map<String, Object> generateDataKey(String keyId, String keySpec, int numberOfBytes,
                                               Map<String, String> encryptionContext, String region) {
        resolveKey(keyId, region);
        int len = (keySpec != null && keySpec.contains("256")) ? 32 : (numberOfBytes > 0 ? numberOfBytes : 32);

        byte[] plaintext = new byte[len];
        ThreadLocalRandom.current().nextBytes(plaintext);

        byte[] ciphertext = encrypt(keyId, plaintext, encryptionContext, region);

        Map<String, Object> result = new HashMap<>();
        result.put("Plaintext", plaintext);
        result.put("CiphertextBlob", ciphertext);
        result.put("KeyId", resolveKey(keyId, region).getArn());
        return result;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public void tagResource(String keyId, Map<String, String> tags, String region) {
        KmsKey key = resolveKey(keyId, region);
        ReservedTags.rejectReservedTagsOnUpdate(tags);
        key.getTags().putAll(tags);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    public void untagResource(String keyId, List<String> tagKeys, String region) {
        KmsKey key = resolveKey(keyId, region);
        tagKeys.forEach(key.getTags()::remove);
        keyStore.put(region + "::" + key.getKeyId(), key);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static boolean isSecgP256k1(KmsKeySpec spec) {
        return KmsKeySpec.ECC_SECG_P256K1 == spec;
    }

    private static boolean isPKCS1v1_5(KmsKeySpec.Algorithm spec) {
        return spec == KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_256
                || spec == KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_384
                || spec == KmsKeySpec.Algorithm.RSASSA_PKCS1_V1_5_SHA_512;
    }

    /**
     * Signs {@code message} with secp256k1 using BC's lightweight {@link ECDSASigner}.
     *
     * <p>BC's {@code SignatureSpi} subclasses extend {@code java.security.SignatureSpi} (not
     * {@code java.security.Signature}), so they cannot be used as a drop-in {@code Signature}.
     * Using the lightweight API avoids JCA's {@code ClassLoader.loadClass} entirely — every
     * class referenced here is directly allocated in reachable code and is always in GraalVM's
     * native image type registry.</p>
     */
    private static byte[] signSecgP256k1(PrivateKey privateKey, byte[] message, String jcaAlgo) throws Exception {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
        ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(((BCECPrivateKey) privateKey).getD(), domain);

        byte[] hash = "NONEwithECDSA".equals(jcaAlgo) ? message : hashForEcdsa(message, jcaAlgo);

        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ParametersWithRandom(privParams, new SecureRandom()));
        BigInteger[] rs = signer.generateSignature(hash);

        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        DERSequenceGenerator seq = new DERSequenceGenerator(bOut);
        seq.addObject(new ASN1Integer(rs[0]));
        seq.addObject(new ASN1Integer(rs[1]));
        seq.close();
        return bOut.toByteArray();
    }

    /**
     * Verifies a DER-encoded ECDSA signature over secp256k1.
     */
    private static boolean verifySecgP256k1(PublicKey publicKey, byte[] message, byte[] signature, String jcaAlgo) throws Exception {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
        ECPublicKeyParameters pubParams = new ECPublicKeyParameters(((BCECPublicKey) publicKey).getQ(), domain);

        byte[] hash = "NONEwithECDSA".equals(jcaAlgo) ? message : hashForEcdsa(message, jcaAlgo);

        ASN1Sequence asn1 = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(signature));
        BigInteger r = ASN1Integer.getInstance(asn1.getObjectAt(0)).getValue();
        BigInteger s = ASN1Integer.getInstance(asn1.getObjectAt(1)).getValue();

        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, pubParams);
        return verifier.verifySignature(hash, r, s);
    }

    private static byte[] hashForEcdsa(byte[] message, String jcaAlgo) throws Exception {
        String mdAlgo = switch (jcaAlgo) {
            case "SHA256withECDSA" -> "SHA-256";
            case "SHA384withECDSA" -> "SHA-384";
            case "SHA512withECDSA" -> "SHA-512";
            default -> throw new AwsException("InvalidSigningAlgorithmException", "Unsupported EC algorithm: " + jcaAlgo, 400);
        };
        return MessageDigest.getInstance(mdAlgo).digest(message);
    }

    private static KeyFactory buildKeyFactory(KmsKeySpec spec) throws Exception {
        return KeyFactory.getInstance(spec.getKeyType() == KmsKeySpec.KeyType.RSA ? "RSA" : "EC");
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
