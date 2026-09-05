package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
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
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA384Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.RSABlindedEngine;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.Ed25519phSigner;
import org.bouncycastle.crypto.signers.PSSSigner;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi;
import org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi;
import org.bouncycastle.jcajce.provider.util.AsymmetricKeyInfoConverter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.DIGEST;
import static io.github.hectorvent.floci.services.kms.model.KmsMessageType.RAW;

@ApplicationScoped
public class KmsService implements ResourceProvider {

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
                case ED25519 -> {
                    var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
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

    /** GrantOperation enum from the KMS model (kms/2014-11-01/service-2.json). */
    private static final Set<String> GRANT_OPERATIONS = new LinkedHashSet<>(List.of(
            "Decrypt", "Encrypt", "GenerateDataKey", "GenerateDataKeyWithoutPlaintext",
            "ReEncryptFrom", "ReEncryptTo", "Sign", "Verify", "GetPublicKey", "CreateGrant",
            "RetireGrant", "DescribeKey", "GenerateDataKeyPair", "GenerateDataKeyPairWithoutPlaintext",
            "GenerateMac", "VerifyMac", "DeriveSharedSecret"));

    /** GrantNameType pattern/length from the KMS model (kms/2014-11-01/service-2.json). */
    private static final java.util.regex.Pattern GRANT_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9:/_-]+$");

    /** GrantConstraintSourceArnType pattern from the KMS model (kms/2014-11-01/service-2.json). */
    private static final java.util.regex.Pattern GRANT_CONSTRAINT_SOURCE_ARN_PATTERN =
            java.util.regex.Pattern.compile("^arn:aws[a-z0-9-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:.+$");

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
                                + "^arn:aws[a-z0-9-]*:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:.+$", 400);
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
        String grantId = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(tokenBytes);

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

    public void updateAlias(String aliasName, String targetKeyId, String region) {
        String storageKey = region + "::" + aliasName;
        KmsAlias existing = aliasStore.get(storageKey)
                .orElseThrow(() -> new AwsException("NotFoundException", "Alias not found: " + aliasName, 404));

        KmsKey currentKey = resolveKey(existing.getTargetKeyId(), region);
        KmsKey newKey = resolveKey(targetKeyId, region); // Validate key exists and normalize to plain key ID

        if ("PendingDeletion".equals(newKey.getKeyState())) {
            throw new AwsException("KMSInvalidStateException",
                    "KMS key " + newKey.getKeyId() + " is pending deletion.", 400);
        }
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
            throw new AwsException("NotFoundException", "Alias not found", 404);
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

    // v2 blob: kms:v2:<keyId>:<nonceHex>:<contextFingerprintHex>:<base64(plaintext)>
    // Nonce makes Encrypt non-deterministic; contextFingerprint binds EncryptionContext as AAD.
    // Legacy v1 (kms:<keyId>:<base64>) still accepted on Decrypt for persistent-store back-compat.
    private static final String BLOB_PREFIX_V2 = "kms:v2:";
    private static final String BLOB_PREFIX_V1 = "kms:";
    private static final int SHA_512_DIGEST_BYTES = 64;
    private static final int NONCE_BYTES = 8;
    private static final int MIN_MAC_MESSAGE_BYTES = 1;
    private static final int MAX_MAC_MESSAGE_BYTES = 4096;
    private static final int MIN_ENCRYPT_PLAINTEXT_BYTES = 1;
    private static final int MAX_ENCRYPT_PLAINTEXT_BYTES = 4096;
    private static final int MIN_MAC_BYTES = 1;
    private static final int MAX_MAC_BYTES = 6144;
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
        validatePlaintextLength(plaintext, operation);
        KmsKey kmsKey = resolveKey(keyId, region);
        validateKeyIsUsableForCryptoOperations(kmsKey);
        validateKeyUsageForEncryptionOperation(kmsKey, operation);
        validateEncryptionAlgorithmForSpec(algorithm, kmsKey.getKeySpec());

        if (kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.RSA) {
            rejectEncryptionContextForAsymmetricKey(encryptionContext);
            validateRsaPlaintextLength(plaintext, algorithm, kmsKey.getKeySpec());
            byte[] ciphertext = rsaOaep(Cipher.ENCRYPT_MODE, kmsKey, algorithm, plaintext);
            return new EncryptResult(ciphertext, kmsKey.getArn(), algorithm.getAlgName());
        }

        byte[] nonceBytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonceHex = HexFormat.of().formatHex(nonceBytes);

        String blob = BLOB_PREFIX_V2
                + kmsKey.getKeyId() + ":"
                + nonceHex + ":"
                + contextFingerprint(encryptionContext) + ":"
                + Base64.getEncoder().encodeToString(plaintext);
        return new EncryptResult(blob.getBytes(StandardCharsets.UTF_8), kmsKey.getArn(), algorithm.getAlgName());
    }

    public byte[] decrypt(byte[] ciphertext, String region) {
        return decrypt(ciphertext, Map.of(), region);
    }

    public byte[] decrypt(byte[] ciphertext, Map<String, String> encryptionContext, String region) {
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
        return decodePayload(parsed);
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
            validateKeyIsUsableForCryptoOperations(requestKey);
            validateKeyUsageForEncryptionOperation(requestKey, "Decrypt");
            validateEncryptionAlgorithmForSpec(algorithm, requestKey.getKeySpec());
            rejectEncryptionContextForAsymmetricKey(encryptionContext);
            byte[] plaintext = rsaOaep(Cipher.DECRYPT_MODE, requestKey, algorithm, ciphertext);
            return new DecryptResult(plaintext, requestKey.getArn(), algorithm.getAlgName());
        }

        // With the defaulted SYMMETRIC_DEFAULT algorithm, real KMS parses the ciphertext
        // before it compares the algorithm with the named key's spec: Decrypt of a raw RSA
        // ciphertext with an RSA KeyId but no EncryptionAlgorithm answers
        // InvalidCiphertextException, not InvalidKeyUsageException (measured in us-east-1).
        ParsedBlob parsed = parseBlob(ciphertext);
        if (!parsed.contextFingerprint.equals(contextFingerprint(encryptionContext))) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
        }
        byte[] plaintext = decodePayload(parsed);

        if (requestKeyId != null && !requestKeyId.isBlank()) {
            KmsKey requestKey = resolveKey(requestKeyId, region);
            if (!requestKey.getKeyId().equals(parsed.keyId)) {
                throw new AwsException(
                        "IncorrectKeyException",
                        "The request was rejected because the specified KMS key cannot decrypt the data.",
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

    /** A blob whose payload is not valid base64 is a bad ciphertext, not a server fault. */
    private static byte[] decodePayload(ParsedBlob parsed) {
        try {
            return Base64.getDecoder().decode(parsed.payload);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
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
     * [RSAES_OAEP_SHA_256, RSAES_OAEP_SHA_1, SYMMETRIC_DEFAULT, SM2PKE]. A null or blank
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
                            + "[RSAES_OAEP_SHA_256, RSAES_OAEP_SHA_1, SYMMETRIC_DEFAULT, SM2PKE]", 400);
        };
    }

    private static void validateKeyUsageForEncryptionOperation(KmsKey key, String operation) {
        if (KmsKeyUsage.ENCRYPT_DECRYPT != key.getKeyUsage()) {
            throw new AwsException("InvalidKeyUsageException",
                    key.getArn() + " key usage is " + key.getKeyUsage() + " which is not valid for "
                            + operation + ".", 400);
        }
    }

    private static void validateEncryptionAlgorithmForSpec(KmsKeySpec.Algorithm algorithm, KmsKeySpec spec) {
        if (!spec.getAlgorithm().contains(algorithm)) {
            throw new AwsException("InvalidKeyUsageException",
                    "Algorithm " + algorithm.getAlgName() + " is incompatible with key spec " + spec.name() + ".", 400);
        }
    }

    private static void validatePlaintextLength(byte[] plaintext, String operation) {
        int length = plaintext == null ? 0 : plaintext.length;
        if (length < MIN_ENCRYPT_PLAINTEXT_BYTES || length > MAX_ENCRYPT_PLAINTEXT_BYTES) {
            throw new AwsException("ValidationException",
                    "Plaintext must be between 1 and 4096 bytes for " + operation + ".", 400);
        }
    }

    private static void rejectEncryptionContextForAsymmetricKey(Map<String, String> encryptionContext) {
        if (encryptionContext != null && !encryptionContext.isEmpty()) {
            throw new AwsException("ValidationException",
                    "EncryptionContext is not supported when encrypting/decrypting with asymmetric CMKs.", 400);
        }
    }

    /** RFC 8017 7.1.1: OAEP holds at most k - 2*hLen - 2 bytes, k being the modulus length. */
    private static void validateRsaPlaintextLength(byte[] plaintext, KmsKeySpec.Algorithm algorithm, KmsKeySpec spec) {
        int modulusBytes = switch (spec) {
            case RSA_2048 -> 256;
            case RSA_3072 -> 384;
            case RSA_4096 -> 512;
            default -> throw new AwsException("InvalidKeyUsageException",
                    "Algorithm " + algorithm.getAlgName() + " is incompatible with key spec " + spec.name() + ".", 400);
        };
        int digestBytes = algorithm == KmsKeySpec.Algorithm.RSAES_OAEP_SHA_1 ? 20 : 32;
        int maxBytes = modulusBytes - 2 * digestBytes - 2;
        if (plaintext.length > maxBytes) {
            throw new AwsException("ValidationException",
                    "Algorithm " + algorithm.getAlgName() + " and key spec " + spec.name()
                            + " cannot encrypt data larger than " + maxBytes + " bytes.", 400);
        }
    }

    /**
     * RSAES-OAEP with an explicit OAEPParameterSpec. The JDK's named OAEP transformations
     * default MGF1 to SHA-1 whatever the main digest is, while KMS RSAES_OAEP_SHA_256 uses
     * MGF1 over SHA-256, so the parameters are always spelled out.
     */
    private byte[] rsaOaep(int mode, KmsKey key, KmsKeySpec.Algorithm algorithm, byte[] input) {
        try {
            var digest = algorithm == KmsKeySpec.Algorithm.RSAES_OAEP_SHA_1 ? "SHA-1" : "SHA-256";
            var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            var params = new OAEPParameterSpec(digest, "MGF1", new MGF1ParameterSpec(digest),
                    PSource.PSpecified.DEFAULT);
            if (mode == Cipher.ENCRYPT_MODE) {
                cipher.init(mode, loadPublicKey(key.getPublicKeyEncoded(), key.getKeySpec()), params);
            } else {
                cipher.init(mode, loadPrivateKey(key.getPrivateKeyEncoded(), key.getKeySpec()), params);
            }
            return cipher.doFinal(input);
        } catch (Exception e) {
            if (mode == Cipher.DECRYPT_MODE) {
                // Real KMS answers any OAEP failure the same way: the padding check hides
                // whether the bytes were garbage, the wrong length, or made for another key.
                // The log line keeps broken key material or a missing cipher diagnosable.
                LOG.debugv(e, "RSA OAEP decrypt failed for key {0}", key.getKeyId());
                throw new AwsException("InvalidCiphertextException", "The ciphertext is invalid.", 400);
            }
            LOG.warnv(e, "RSA OAEP encrypt failed for key {0}", key.getKeyId());
            throw new AwsException("InternalFailure", "Failed to encrypt: " + e.getMessage(), 500);
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

        var ed25519 = kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.ED25519;
        if (ed25519) {
            validateEd25519Request(kmsKey.getKeySpec(), algorithm, messageType, message);
        }

        try {
            PrivateKey privateKey = loadPrivateKey(kmsKey.getPrivateKeyEncoded(), kmsKey.getKeySpec());
            if (ed25519) {
                return signEd25519(privateKey, message, algorithm);
            }
            if (messageType == DIGEST && isRsaPssRequest(kmsKey.getKeySpec(), algorithm)) {
                return signRsaPssDigest(privateKey, message, algorithm);
            }
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
            var sig = signatureFor(jcaAlgo);
            sig.initSign(privateKey);
            sig.update(message);
            return sig.sign();
        } catch (AwsException e) {
            throw e;
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

        var ed25519 = kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.ED25519;
        if (ed25519) {
            validateEd25519Request(kmsKey.getKeySpec(), algorithm, messageType, message);
        }

        try {
            PublicKey publicKey = loadPublicKey(kmsKey.getPublicKeyEncoded(), kmsKey.getKeySpec());
            if (ed25519) {
                return verifyEd25519(publicKey, message, signature, algorithm);
            }
            String jcaAlgo = KmsKeySpec.getSignVerifyAlgorithm(algorithm).getJavaName();

            if (DIGEST.equals(messageType)) {
                if (isRsaPssRequest(kmsKey.getKeySpec(), algorithm)) {
                    return verifyRsaPssDigest(publicKey, message, signature, algorithm);
                }
                jcaAlgo = "NONEwith" + (kmsKey.getKeySpec().getKeyType() == KmsKeySpec.KeyType.RSA ? "RSA" : "ECDSA");
                if (isPKCS1v1_5(kmsKey.getKeySpec().getAlgorithm().getFirst())) {
                    // Mirror sign(): verify against DigestInfo{hashOID, digest} (RFC 8017 9.2).
                    message = wrapInDigestInfo(message, algorithm);
                }
            }
            if (isSecgP256k1(kmsKey.getKeySpec())) {
                return verifySecgP256k1(publicKey, message, signature, jcaAlgo);
            }
            var sig = signatureFor(jcaAlgo);
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(signature);
        } catch (AwsException e) {
            throw e;
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

        EncryptResult encrypted = encrypt(keyId, plaintext, encryptionContext, null, region, "GenerateDataKey");

        Map<String, Object> result = new HashMap<>();
        result.put("Plaintext", plaintext);
        result.put("CiphertextBlob", encrypted.ciphertext());
        result.put("KeyId", encrypted.keyArn());
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

    /**
     * Validates what an Ed25519 key accepts, matching the errors real KMS returns.
     *
     * <p>ED25519_SHA_512 only takes {@code MessageType=RAW} and ED25519_PH_SHA_512 only takes
     * {@code MessageType=DIGEST}, whose value has to be exactly one SHA-512 digest. Real KMS
     * rejects the other pairing and a wrong digest length with a ValidationException, and rejects
     * any other signing algorithm with an InvalidKeyUsageException. Sign and Verify both enforce
     * all three.
     */
    private static void validateEd25519Request(KmsKeySpec spec, String algorithm, KmsMessageType messageType,
                                               byte[] message) {
        var algo = KmsKeySpec.getSignVerifyAlgorithm(algorithm);
        if (algo != KmsKeySpec.Algorithm.ED25519_SHA_512 && algo != KmsKeySpec.Algorithm.ED25519_PH_SHA_512) {
            throw new AwsException("InvalidKeyUsageException",
                    "Algorithm " + algorithm + " is incompatible with key spec " + spec.name() + ".", 400);
        }
        var required = algo == KmsKeySpec.Algorithm.ED25519_SHA_512 ? KmsMessageType.RAW : KmsMessageType.DIGEST;
        if (messageType != required) {
            throw new AwsException("ValidationException",
                    "Message type " + messageType + " is incompatible with algorithm " + algorithm + ".", 400);
        }
        if (algo == KmsKeySpec.Algorithm.ED25519_PH_SHA_512 && message.length != SHA_512_DIGEST_BYTES) {
            throw new AwsException("ValidationException",
                    "Digest is invalid length for algorithm " + algorithm + ".", 400);
        }
    }

    /**
     * Signs with an Ed25519 key.
     *
     * <p>ED25519_SHA_512 is pure Ed25519 over the message. ED25519_PH_SHA_512 is RFC 8032
     * Ed25519ph, and real KMS applies the SHA-512 pre-hash to the bytes the caller sends rather
     * than treating them as an already computed digest. That is measurably different from
     * MessageType=DIGEST on RSA and ECDSA keys, where the bytes are signed as they arrive.
     *
     * <p>The JDK has no Ed25519ph, so that branch uses BouncyCastle's lightweight signer,
     * instantiated directly rather than resolved through a JCA provider, the same way the
     * secp256k1 path does.
     */
    private static byte[] signEd25519(PrivateKey privateKey, byte[] message, String algorithm) throws Exception {
        if (KmsKeySpec.Algorithm.ED25519_SHA_512.name().equals(algorithm)) {
            var signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        }
        var signer = new Ed25519phSigner(new byte[0]);
        signer.init(true, new Ed25519PrivateKeyParameters(ed25519Seed(privateKey), 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private static boolean verifyEd25519(PublicKey publicKey, byte[] message, byte[] signature, String algorithm)
            throws Exception {
        if (KmsKeySpec.Algorithm.ED25519_SHA_512.name().equals(algorithm)) {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        }
        var verifier = new Ed25519phSigner(new byte[0]);
        verifier.init(false, new Ed25519PublicKeyParameters(ed25519Point(publicKey), 0));
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private static byte[] ed25519Seed(PrivateKey privateKey) throws InvalidKeyException {
        if (privateKey instanceof EdECPrivateKey edEC) {
            return edEC.getBytes().orElseThrow(() -> new InvalidKeyException("Ed25519 private key is not extractable"));
        }
        throw new InvalidKeyException("Expected an Ed25519 private key but got " + privateKey.getAlgorithm());
    }

    private static byte[] ed25519Point(PublicKey publicKey) {
        return SubjectPublicKeyInfo.getInstance(publicKey.getEncoded()).getPublicKeyData().getBytes();
    }

    /**
     * Builds the {@link Signature} for a KMS signing algorithm.
     *
     * <p>BouncyCastle names PSS signatures {@code SHAnnnwithRSA/PSS}, and only its provider
     * answers to that name. The JDK exposes one {@code RSASSA-PSS} Signature whose digest,
     * mask generation function and salt length come from a parameter spec instead. AWS KMS
     * RSASSA_PSS_SHA_nnn uses MGF1 over the same digest with a salt as long as that digest,
     * which is what BouncyCastle's alias defaults to, so a signature made either way verifies
     * against the other. Every other name Floci asks for is a standard JCA name.
     */
    private static Signature signatureFor(String jcaAlgorithm) throws GeneralSecurityException {
        if (!jcaAlgorithm.endsWith("withRSA/PSS")) {
            return Signature.getInstance(jcaAlgorithm);
        }
        var digest = "SHA-" + jcaAlgorithm.substring("SHA".length(), jcaAlgorithm.indexOf("with"));
        var maskGeneration = switch (digest) {
            case "SHA-256" -> MGF1ParameterSpec.SHA256;
            case "SHA-384" -> MGF1ParameterSpec.SHA384;
            case "SHA-512" -> MGF1ParameterSpec.SHA512;
            default -> throw new NoSuchAlgorithmException("Unsupported PSS digest: " + digest);
        };
        var saltLength = MessageDigest.getInstance(digest).getDigestLength();
        var signature = Signature.getInstance("RSASSA-PSS");
        signature.setParameter(new PSSParameterSpec(digest, "MGF1", maskGeneration, saltLength, 1));
        return signature;
    }

    private static boolean isRsaPssRequest(KmsKeySpec spec, String algorithm) {
        return spec.getKeyType() == KmsKeySpec.KeyType.RSA && algorithm.startsWith("RSASSA_PSS");
    }

    /**
     * Signs a pre-computed digest with RSASSA-PSS.
     *
     * <p>Real KMS applies the PSS encoding directly to the digest a {@code MessageType=DIGEST}
     * caller sends. The JDK's {@code RSASSA-PSS} Signature always hashes its input first, so
     * this uses BouncyCastle's lightweight raw PSS signer, instantiated directly like the
     * secp256k1 and Ed25519ph paths. The raw signer defaults to MGF1 over the same digest with
     * a salt as long as that digest, matching {@link #signatureFor(String)}, so RAW and DIGEST
     * signatures verify against each other.
     */
    private static byte[] signRsaPssDigest(PrivateKey privateKey, byte[] digest, String algorithm) throws Exception {
        var signer = rawPssSigner(algorithm);
        signer.init(true, new ParametersWithRandom(PrivateKeyFactory.createKey(privateKey.getEncoded()), SECURE_RANDOM));
        signer.update(digest, 0, digest.length);
        return signer.generateSignature();
    }

    private static boolean verifyRsaPssDigest(PublicKey publicKey, byte[] digest, byte[] signature, String algorithm) throws Exception {
        var signer = rawPssSigner(algorithm);
        signer.init(false, PublicKeyFactory.createKey(publicKey.getEncoded()));
        signer.update(digest, 0, digest.length);
        return signer.verifySignature(signature);
    }

    private static PSSSigner rawPssSigner(String algorithm) {
        Digest digest = switch (algorithm) {
            case "RSASSA_PSS_SHA_256" -> new SHA256Digest();
            case "RSASSA_PSS_SHA_384" -> new SHA384Digest();
            case "RSASSA_PSS_SHA_512" -> new SHA512Digest();
            default -> throw new AwsException("InvalidSigningAlgorithmException", "Unsupported algorithm: " + algorithm, 400);
        };
        return PSSSigner.createRawSigner(new RSABlindedEngine(), digest);
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
        return KeyFactory.getInstance(switch (spec.getKeyType()) {
            case RSA -> "RSA";
            case ED25519 -> "Ed25519";
            default -> "EC";
        });
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

    private static void validateKeyIsUsableForCryptoOperations(KmsKey key) {
        if ("PendingDeletion".equals(key.getKeyState())) {
            throw new AwsException(
                    "KMSInvalidStateException",
                    "KMS key " + key.getKeyId() + " is pending deletion.",
                    400
            );
        }
        if (!key.isEnabled()) {
            throw new AwsException(
                    "DisabledException",
                    "The request was rejected because the specified KMS key is not enabled.",
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
