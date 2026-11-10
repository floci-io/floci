package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.Certificates;
import io.floci.aws.services.paymentcryptography.crypto.ConcatKdf;
import io.floci.aws.services.paymentcryptography.crypto.Ecdh;
import io.floci.aws.services.paymentcryptography.crypto.RsaOaep;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The key exchange: the forms a key may leave the service in, the forms it may come back
 * in, and the certificates the two parties exchange to do it.
 *
 * <p>The service's own {@code KeyMaterial} union names one form per request, and each form
 * protects the key differently:
 *
 * <ul>
 *   <li>{@code Tr31KeyBlock}: a TR-31 key block under a key the service holds.
 *   <li>{@code KeyCryptogram}: the key material under the receiver's RSA certificate.
 *   <li>{@code Tr34KeyBlock}: a TR-31 key block, signed by the service and wrapped for the
 *       receiver under their certificate.
 *   <li>{@code DiffieHellmanTr31KeyBlock}: a TR-31 key block under a key the two parties
 *       agreed with ECDH.
 *   <li>{@code As2805KeyCryptogram}: the key material under a key encryption key, bound to
 *       its purpose by the AS2805 variant.
 * </ul>
 *
 * <p>The import takes the same forms and two more: a root or trusted public key may arrive
 * as a certificate rather than as a block.
 *
 * <p>The certificates the exchange mints are the service's own. The real service issues them
 * from a certificate authority; this one mints a self-signed certificate over a key pair it
 * makes for the occasion, which is enough for two parties that trust each other's copy of it.
 */
final class PaymentCryptographyKeyExchange {

    /** The forms the export produces, in the order the service lists them. */
    static final List<String> EXPORT_FORMS = List.of(
            "Tr31KeyBlock",
            "KeyCryptogram",
            "DiffieHellmanTr31KeyBlock");

    /** The forms the import takes, in the order the service lists them. */
    static final List<String> IMPORT_FORMS = List.of(
            "Tr31KeyBlock",
            "KeyCryptogram",
            "RootCertificatePublicKey",
            "TrustedCertificatePublicKey",
            "DiffieHellmanTr31KeyBlock");

    /** The key material types that take import parameters. */
    private static final List<String> IMPORT_PARAMETER_TYPES = List.of("KEY_CRYPTOGRAM");

    /** The key material types that take export parameters. */
    private static final List<String> EXPORT_PARAMETER_TYPES = List.of("TR34_KEY_BLOCK");

    /** The prefix an import token's name carries, as the model states it. */
    private static final String IMPORT_TOKEN_PREFIX = "import-token-";

    /** The prefix an export token's name carries, as the model states it. */
    private static final String EXPORT_TOKEN_PREFIX = "export-token-";

    /** How long an export token is good for, in milliseconds. */
    static long exportTokenTtlMs = 30L * 24 * 3600 * 1000;

    /** How long an import token is good for, in milliseconds. */
    static long importTokenTtlMs = 7L * 24 * 3600 * 1000;

    /** The RSA moduli the service makes a wrapping key of. */
    private static final Map<String, Integer> RSA_BITS =
            Map.of("RSA_2048", 2048, "RSA_3072", 3072, "RSA_4096", 4096);

    /** The hexadecimal the key material and the cryptograms are written in. */
    private static final HexFormat HEX = HexFormat.of();

    /** The hexadecimal a cryptogram is written in: the capitals the reference writes it in. */
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    /** The export tokens the service has issued, by token. */
    private final Map<String, Issued> exportTokens = new ConcurrentHashMap<>();

    /** The import tokens the service has issued, by token. */
    private final Map<String, Issued> importTokens = new ConcurrentHashMap<>();

    /** The last export parameters issued, by material type and algorithm. */
    private final Map<String, Issued> lastExportParameters = new ConcurrentHashMap<>();

    /** The last import parameters issued, by material type and algorithm. */
    private final Map<String, Issued> lastImportParameters = new ConcurrentHashMap<>();

    /** The service whose keys the exchange looks up. */
    private final PaymentCryptographyService service;

    /** The randomness the key pairs and the tokens are made from. */
    private final SecureRandom random = new SecureRandom();

    PaymentCryptographyKeyExchange(PaymentCryptographyService service) {
        this.service = service;
    }

    /** A parameter the service issued: the key pair behind it and the token that names it. */
    private static final class Issued {
        private final String token;
        private final PrivateKey privateKey;
        private final byte[] certificateDer;
        private final String algorithm;
        /** The kind of key material the token was issued for. */
        private final String materialType;
        private final long validUntilMs;

        Issued(String token, PrivateKey privateKey, byte[] certificateDer, String algorithm,
               String materialType, long validUntilMs) {
            this.token = token;
            this.privateKey = privateKey;
            this.certificateDer = certificateDer;
            this.algorithm = algorithm;
            this.materialType = materialType;
            this.validUntilMs = validUntilMs;
        }
    }

    /** What an import brings in: the attributes of the key and its material. */
    record Imported(String algorithm, String usage, Map<String, Boolean> modes, boolean exportable,
                    byte[] material, String publicKeyBase64, String privateKeyBase64,
                    String certificatePem, String keyClass) {
    }

    /** What an export produces: the material, its format, and the key it protects. */
    record Exported(String material, String format, String wrappingKeyArn) {
    }

    // ---- the export ----------------------------------------------------------

    /**
     * The key that leaves the service, in the form the request names.
     *
     * @param region the region the key is in
     * @param key the key that leaves
     * @param form the form the request names, one of {@link #EXPORT_FORMS}
     * @param material the members of the form
     * @return the material, its format, and the key that protects it
     */
    Exported export(String region, PaymentCryptographyKey key, String form,
                    Map<String, Object> material) {
        byte[] clear = clearMaterial(key);
        return switch (form) {
            case "Tr31KeyBlock" -> exportTr31(region, key, clear, material);
            case "KeyCryptogram" -> exportCryptogram(region, key, clear, material);
            case "DiffieHellmanTr31KeyBlock" -> exportEcdh(region, key, clear, material);
            default -> throw validation("KeyMaterial." + form + " is not a form the service exports ("
                    + String.join(", ", EXPORT_FORMS) + ")");
        };
    }

    /** A TR-31 key block, under a key the caller names. */
    private Exported exportTr31(String region, PaymentCryptographyKey key, byte[] clear,
                                Map<String, Object> material) {
        String wrappingKeyIdentifier = text(material, "WrappingKeyIdentifier");
        if (wrappingKeyIdentifier.isEmpty()) {
            throw validation("KeyMaterial.Tr31KeyBlock.WrappingKeyIdentifier is required");
        }
        PaymentCryptographyKey protection = usableKey(wrappingKeyIdentifier, region);
        if (!PaymentCryptographyService.isKeyEncryptionKey(protection.getKeyUsage())) {
            throw validation("the key " + protection.getArn() + " is not a key encryption key, so "
                    + "it cannot wrap a key block");
        }
        if (!PaymentCryptographyService.mayWrap(protection)) {
            throw validation("the key " + protection.getArn() + " does not have the mode of use "
                    + "Wrap, which it needs to wrap a key");
        }
        String tooWeak = PaymentCryptographyKeyStrength.refusal(
                protection.getKeyAlgorithm(), key.getKeyAlgorithm());
        if (tooWeak != null) {
            throw validation(tooWeak);
        }
        String block = PaymentCryptographyTr31.wrap(clear,
                PaymentCryptographyKeyRules.material(protection),
                PaymentCryptographyService.isAes(protection), PaymentCryptographyService.algorithmByte(key),
                PaymentCryptographyService.usageCode(key.getKeyUsage()), PaymentCryptographyService.modeCode(key),
                key.isExportable() ? "E" : "N");
        return new Exported(block, "TR31_KEY_BLOCK", protection.getArn());
    }

    /** The key material, encrypted under the receiver's certificate. */
    private Exported exportCryptogram(String region, PaymentCryptographyKey key, byte[] clear,
                                      Map<String, Object> material) {
        String certText = text(material, "WrappingKeyCertificate");
        if (certText.isEmpty()) {
            throw validation("KeyMaterial.KeyCryptogram.WrappingKeyCertificate is required");
        }
        String caIdentifier = text(material, "CertificateAuthorityPublicKeyIdentifier");
        if (caIdentifier.isEmpty()) {
            throw validation("KeyMaterial.KeyCryptogram.CertificateAuthorityPublicKeyIdentifier "
                    + "is required");
        }
        PaymentCryptographyKey caKey = usableKey(caIdentifier, region);
        PublicKey wrappingKey = verifiedCertificate(certText, caKey, "the wrapping key certificate")
                .getPublicKey();

        // The wrapping key has to be at least as strong as the key it protects, and the size
        // of the RSA modulus is what decides that.
        int modulusBits = modulusBitsOf(wrappingKey);
        String tooWeak = PaymentCryptographyKeyStrength.refusal(
                PaymentCryptographyKeyStrength.rsaBitsOf(modulusBits),
                PaymentCryptographyKeyStrength.bitsOf(key.getKeyAlgorithm()),
                "RSA_" + modulusBits, key.getKeyAlgorithm());
        if (tooWeak != null) {
            throw validation(tooWeak);
        }
        String spec = text(material, "WrappingSpec");
        String digest = RsaOaep.digestFor(spec.isEmpty() ? "RSA_OAEP_SHA_256" : spec);
        if (digest == null) {
            throw validation("WrappingSpec '" + spec + "' not supported "
                    + "(RSA_OAEP_SHA_256, RSA_OAEP_SHA_512)");
        }
        byte[] encrypted = RsaOaep.encrypt(wrappingKey, clear, digest);
        return new Exported(HEX_UPPER.formatHex(encrypted), "KEY_CRYPTOGRAM", null);
    }


    /** A TR-31 key block under a key the two parties agreed with ECDH. */
    private Exported exportEcdh(String region, PaymentCryptographyKey key, byte[] clear,
                                Map<String, Object> material) {
        String privateKeyIdentifier = text(material, "PrivateKeyIdentifier");
        if (privateKeyIdentifier.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.PrivateKeyIdentifier is required");
        }
        PaymentCryptographyKey serviceKey = usableKey(privateKeyIdentifier, region);
        // The key that agrees has to be a key agreement key, and it has to be allowed to
        // derive: a key of another purpose may not be named for an agreement.
        PaymentCryptographyKeyRules.requireUsage(serviceKey,
                List.of("TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT"), "complete a key agreement");
        PaymentCryptographyKeyRules.requireMode(serviceKey, "DeriveKey", "complete a key agreement");
        PrivateKey privateKey = privateKeyOf(serviceKey);
        if (privateKey == null) {
            throw validation("the key " + serviceKey.getArn() + " has no private half, so it cannot "
                    + "complete a key agreement");
        }
        if (!(privateKey instanceof ECPrivateKey)) {
            throw validation("the key " + serviceKey.getArn() + " has a " + privateKey.getAlgorithm()
                    + " private key, not an elliptic curve one, so it cannot complete a key "
                    + "agreement");
        }
        String caIdentifier = text(material, "CertificateAuthorityPublicKeyIdentifier");
        PaymentCryptographyKey caKey = usableKey(caIdentifier.isEmpty() ? "(missing)" : caIdentifier,
                region);
        String certText = text(material, "PublicKeyCertificate");
        if (certText.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.PublicKeyCertificate is required");
        }
        X509Certificate counterparty = verifiedCertificate(certText, caKey,
                "the public key certificate");
        String deriveAlgorithmName = text(material, "DeriveKeyAlgorithm");
        PaymentCryptographyKeyAlgorithm deriveAlgorithm =
                PaymentCryptographyKeyAlgorithm.from(deriveAlgorithmName);
        if (deriveAlgorithm == null || !deriveAlgorithm.symmetric()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.DeriveKeyAlgorithm is '"
                    + deriveAlgorithmName + "', which is not a symmetric algorithm this service "
                    + "supports (TDES_2KEY, TDES_3KEY, AES_128, AES_192, AES_256)");
        }
        String kdf = text(material, "KeyDerivationFunction");
        if (!"NIST_SP800".equals(kdf) && !"ANSI_X963".equals(kdf)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.KeyDerivationFunction '" + kdf
                    + "' not supported (NIST_SP800, ANSI_X963)");
        }
        String hash = text(material, "KeyDerivationHashAlgorithm");
        if (!"SHA_256".equals(hash) && !"SHA_384".equals(hash) && !"SHA_512".equals(hash)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock."
                    + "KeyDerivationHashAlgorithm '" + hash + "' not supported (SHA_256, SHA_384, "
                    + "SHA_512)");
        }
        Map<String, Object> derivationData = object(material, "DerivationData");
        String sharedInformation = derivationData == null
                ? "" : text(derivationData, "SharedInformation");
        if (sharedInformation.isEmpty() || !isHex(sharedInformation)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.DerivationData."
                    + "SharedInformation must be a hexadecimal string");
        }

        PublicKey counterpartyKey = counterparty.getPublicKey();
        if (!(counterpartyKey instanceof ECPublicKey)) {
            throw validation("the public key certificate carries a " + counterpartyKey.getAlgorithm()
                    + " key, not an EC one");
        }
        byte[] secret;
        try {
            secret = Ecdh.agree((ECPrivateKey) privateKey, (ECPublicKey) counterpartyKey);
        } catch (GeneralSecurityException e) {
            throw validation("the key agreement failed: " + e.getMessage());
        }
        byte[] derived = "ANSI_X963".equals(kdf)
                ? ConcatKdf.deriveX963(hash, secret, HEX.parseHex(sharedInformation),
                        deriveAlgorithm.bytes)
                : ConcatKdf.derive(hash, secret, HEX.parseHex(sharedInformation),
                        deriveAlgorithm.bytes);

        // The derived key is the wrapping key, so it has to be at least as strong as the key it
        // protects. The key agreement is only as strong as the curve it was made on, so the
        // weaker of the two decides.
        int curveBits = Math.min(
                PaymentCryptographyKeyStrength.eccBitsOf(serviceKey.getKeyAlgorithm()),
                PaymentCryptographyKeyStrength.eccBitsOf((ECKey) counterparty.getPublicKey()));
        int derivedBits = Math.min(PaymentCryptographyKeyStrength.bitsOf(deriveAlgorithm.name()),
                curveBits);
        String tooWeak = PaymentCryptographyKeyStrength.refusal(derivedBits,
                PaymentCryptographyKeyStrength.bitsOf(key.getKeyAlgorithm()),
                deriveAlgorithm.name(), key.getKeyAlgorithm());
        if (tooWeak != null) {
            throw validation(tooWeak);
        }

        String block = PaymentCryptographyTr31.wrap(clear, derived, deriveAlgorithm.aes,
                PaymentCryptographyService.algorithmByte(key), PaymentCryptographyService.usageCode(key.getKeyUsage()),
                PaymentCryptographyService.modeCode(key), key.isExportable() ? "E" : "N");
        return new Exported(block, "TR31_KEY_BLOCK", null);
    }


    // ---- the import ----------------------------------------------------------

    /**
     * The key that comes into the service, in the form the request names.
     *
     * @param region the region the key comes into
     * @param form the form the request names, one of {@link #IMPORT_FORMS}
     * @param material the members of the form
     * @return what the key is
     */
    Imported importKey(String region, String form, Map<String, Object> material) {
        return switch (form) {
            case "Tr31KeyBlock" -> importTr31(region, material);
            case "KeyCryptogram" -> importCryptogram(material);
            case "RootCertificatePublicKey" -> importPublicKey(region, material, "");
            case "TrustedCertificatePublicKey" -> importPublicKey(region, material,
                    text(material, "CertificateAuthorityPublicKeyIdentifier"));
            case "DiffieHellmanTr31KeyBlock" -> importEcdh(region, material);
            default -> throw validation("KeyMaterial." + form + " is not a form the service imports ("
                    + String.join(", ", IMPORT_FORMS) + ")");
        };
    }

    /** A key out of a TR-31 key block. */
    private Imported importTr31(String region, Map<String, Object> material) {
        String wrappingKeyIdentifier = text(material, "WrappingKeyIdentifier");
        if (wrappingKeyIdentifier.isEmpty()) {
            throw validation("KeyMaterial.Tr31KeyBlock.WrappingKeyIdentifier is required");
        }
        PaymentCryptographyKey protection = usableKey(wrappingKeyIdentifier, region);
        if (!PaymentCryptographyService.isKeyEncryptionKey(protection.getKeyUsage())) {
            throw validation("the key " + protection.getArn() + " is not a key encryption key, so "
                    + "it cannot unwrap a key block");
        }
        if (!PaymentCryptographyService.mayUnwrap(protection)) {
            throw validation("the key " + protection.getArn() + " does not have the mode of use "
                    + "Unwrap, which it needs to unwrap a key");
        }
        String block = text(material, "WrappedKeyBlock");
        if (block.isEmpty()) {
            throw validation("KeyMaterial.Tr31KeyBlock.WrappedKeyBlock is required");
        }
        // The block type picks the derivation, and the derivation needs a key encryption key of
        // the matching family.
        boolean blockIsAes = PaymentCryptographyTr31.typeOf(block);
        if (PaymentCryptographyService.isAes(protection) != blockIsAes) {
            throw validation("a type " + PaymentCryptographyTr31.typeName(block) + " key block needs "
                    + (blockIsAes ? "an AES" : "a TDES") + " wrapping key, but "
                    + protection.getKeyId() + " is " + protection.getKeyAlgorithm());
        }
        byte[] material2;
        try {
            material2 = PaymentCryptographyTr31.unwrap(block,
                    PaymentCryptographyKeyRules.material(protection));
        } catch (RuntimeException e) {
            throw validation("the key block did not open under " + protection.getKeyId() + ": "
                    + e.getMessage());
        }
        String usage = PaymentCryptographyTr31.usageOf(block);
        String mode = PaymentCryptographyTr31.modeOf(block);
        boolean exportable = "E".equals(PaymentCryptographyTr31.exportabilityOf(block));
        String algorithm = PaymentCryptographyService.algorithmOf(block, material2.length);
        // The wrapping key has to be at least as strong as the key it protects, the
        // same rule the export enforces.
        String tooWeak = PaymentCryptographyKeyStrength.refusal(
                protection.getKeyAlgorithm(), algorithm);
        if (tooWeak != null) {
            throw validation(tooWeak);
        }
        return new Imported(algorithm, PaymentCryptographyService.usageName(usage),
                PaymentCryptographyService.modesFor(mode, PaymentCryptographyService.isKeyEncryptionKey(PaymentCryptographyService.usageName(usage))),
                exportable, material2, null, null, null, "SYMMETRIC_KEY");
    }

    /** A key out of an RSA-OAEP cryptogram. */
    private Imported importCryptogram(Map<String, Object> material) {
        Map<String, Object> attributes = object(material, "KeyAttributes");
        if (attributes == null) {
            throw validation("KeyMaterial.KeyCryptogram.KeyAttributes is required");
        }
        String algorithm = String.valueOf(attributes.get("KeyAlgorithm"));
        PaymentCryptographyKeyAlgorithm keyAlgorithm =
                PaymentCryptographyKeyAlgorithm.from(algorithm);
        if (keyAlgorithm == null || !keyAlgorithm.symmetric()) {
            throw validation("KeyAlgorithm '" + algorithm + "' is not a symmetric algorithm this "
                    + "service imports (TDES_2KEY, TDES_3KEY, AES_128, AES_192, AES_256)");
        }
        String cryptogram = text(material, "WrappedKeyCryptogram");
        if (cryptogram.isEmpty() || !isHex(cryptogram)) {
            throw validation("KeyMaterial.KeyCryptogram.WrappedKeyCryptogram (hex) is required");
        }
        String spec = text(material, "WrappingSpec");
        String digest = RsaOaep.digestFor(spec.isEmpty() ? "RSA_OAEP_SHA_256" : spec);
        if (digest == null) {
            throw validation("WrappingSpec '" + spec + "' not supported "
                    + "(RSA_OAEP_SHA_256, RSA_OAEP_SHA_512)");
        }
        String usage = String.valueOf(attributes.get("KeyUsage"));
        if (usage.isEmpty() || "null".equals(usage)) {
            throw validation("KeyMaterial.KeyCryptogram.KeyAttributes.KeyUsage is required");
        }
        // The token is taken last, so a request that is refused for any other reason does not
        // spend it.
        String token = text(material, "ImportToken");
        Issued issued = take(importTokens, token, "KEY_CRYPTOGRAM");
        if (issued == null) {
            throw notFound(token.isEmpty() ? "(missing)" : token);
        }
        byte[] clear;
        try {
            clear = RsaOaep.decrypt(issued.privateKey, HEX.parseHex(cryptogram), digest);
        } catch (RuntimeException e) {
            throw validation("the cryptogram did not open under the import token: " + e.getMessage());
        }
        if (clear.length != keyAlgorithm.bytes) {
            throw validation("the cryptogram carries " + clear.length + " bytes of key, but "
                    + algorithm + " needs " + keyAlgorithm.bytes);
        }
        Map<String, Boolean> modes = modesOf(attributes);
        // The exportability is a member of the form, beside the attributes and not inside
        // them, and the model requires it.
        boolean exportable = !Boolean.FALSE.equals(material.get("Exportable"));
        return new Imported(algorithm, usage, modes, exportable, clear, null, null, null,
                "SYMMETRIC_KEY");
    }



    /** A key out of a block the two parties agreed with ECDH. */
    private Imported importEcdh(String region, Map<String, Object> material) {
        String privateKeyIdentifier = text(material, "PrivateKeyIdentifier");
        if (privateKeyIdentifier.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.PrivateKeyIdentifier is required");
        }
        String caIdentifier = text(material, "CertificateAuthorityPublicKeyIdentifier");
        if (caIdentifier.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock."
                    + "CertificateAuthorityPublicKeyIdentifier is required");
        }
        String certText = text(material, "PublicKeyCertificate");
        if (certText.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.PublicKeyCertificate is required");
        }
        String block = text(material, "WrappedKeyBlock");
        if (block.isEmpty()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.WrappedKeyBlock is required");
        }
        PaymentCryptographyKey serviceKey = usableKey(privateKeyIdentifier, region);
        // The key that agrees has to be a key agreement key, and it has to be allowed to
        // derive: a key of another purpose may not be named for an agreement.
        PaymentCryptographyKeyRules.requireUsage(serviceKey,
                List.of("TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT"), "complete a key agreement");
        PaymentCryptographyKeyRules.requireMode(serviceKey, "DeriveKey", "complete a key agreement");
        PrivateKey privateKey = privateKeyOf(serviceKey);
        if (privateKey == null) {
            throw validation("the key " + serviceKey.getArn() + " has no private half, so it cannot "
                    + "complete a key agreement");
        }
        if (!(privateKey instanceof ECPrivateKey)) {
            throw validation("the key " + serviceKey.getArn() + " has a " + privateKey.getAlgorithm()
                    + " private key, not an elliptic curve one, so it cannot complete a key "
                    + "agreement");
        }
        PaymentCryptographyKey caKey = usableKey(caIdentifier, region);
        X509Certificate counterparty = verifiedCertificate(certText, caKey,
                "the public key certificate");
        PublicKey counterpartyKey = counterparty.getPublicKey();
        if (!"EC".equals(counterpartyKey.getAlgorithm())) {
            throw validation("the public key certificate carries a " + counterpartyKey.getAlgorithm()
                    + " key, not an EC one");
        }
        String deriveAlgorithmName = text(material, "DeriveKeyAlgorithm");
        PaymentCryptographyKeyAlgorithm deriveAlgorithm =
                PaymentCryptographyKeyAlgorithm.from(deriveAlgorithmName);
        if (deriveAlgorithm == null || !deriveAlgorithm.symmetric()) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.DeriveKeyAlgorithm is '"
                    + deriveAlgorithmName + "', which is not a symmetric algorithm this service "
                    + "supports (TDES_2KEY, TDES_3KEY, AES_128, AES_192, AES_256)");
        }
        String kdf = text(material, "KeyDerivationFunction");
        if (!"NIST_SP800".equals(kdf) && !"ANSI_X963".equals(kdf)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.KeyDerivationFunction '" + kdf
                    + "' not supported (NIST_SP800, ANSI_X963)");
        }
        String hash = text(material, "KeyDerivationHashAlgorithm");
        if (!"SHA_256".equals(hash) && !"SHA_384".equals(hash) && !"SHA_512".equals(hash)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock."
                    + "KeyDerivationHashAlgorithm '" + hash + "' not supported (SHA_256, SHA_384, "
                    + "SHA_512)");
        }
        Map<String, Object> derivationData = object(material, "DerivationData");
        String derivationType = derivationData == null ? "" : text(derivationData, "Type");
        if (!derivationType.isEmpty() && !"SHARED_INFORMATION".equals(derivationType)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.DerivationData.Type '"
                    + derivationType + "' not supported (SHARED_INFORMATION)");
        }
        String sharedInformation = derivationData == null ? "" : text(derivationData, "SharedInformation");
        if (sharedInformation.isEmpty() || !isHex(sharedInformation)) {
            throw validation("KeyMaterial.DiffieHellmanTr31KeyBlock.DerivationData."
                    + "SharedInformation must be a hexadecimal string");
        }

        // The two parties agree the key again, so the block opens under it.
        byte[] secret;
        try {
            secret = Ecdh.agree((ECPrivateKey) privateKey, (ECPublicKey) counterpartyKey);
        } catch (GeneralSecurityException e) {
            throw validation("the key agreement failed: " + e.getMessage());
        }
        byte[] derived = "ANSI_X963".equals(kdf)
                ? ConcatKdf.deriveX963(hash, secret, HEX.parseHex(sharedInformation),
                        deriveAlgorithm.bytes)
                : ConcatKdf.derive(hash, secret, HEX.parseHex(sharedInformation),
                        deriveAlgorithm.bytes);

        byte[] clear;
        try {
            clear = PaymentCryptographyTr31.unwrap(block, derived);
        } catch (RuntimeException e) {
            throw validation("the key block did not open under the key the two parties agreed: "
                    + e.getMessage());
        }
        String usage = PaymentCryptographyTr31.usageOf(block);
        String mode = PaymentCryptographyTr31.modeOf(block);
        boolean exportable = "E".equals(PaymentCryptographyTr31.exportabilityOf(block));
        String algorithm = PaymentCryptographyService.algorithmOf(block, clear.length);
        // The derived key protects the imported one, so it has to be as strong as it.
        int derivedBits = Math.min(
                PaymentCryptographyKeyStrength.bitsOf(deriveAlgorithm.name()),
                Math.min(PaymentCryptographyKeyStrength.eccBitsOf(serviceKey.getKeyAlgorithm()),
                        PaymentCryptographyKeyStrength.eccBitsOf((ECKey) counterpartyKey)));
        String tooWeak = PaymentCryptographyKeyStrength.refusal(derivedBits,
                PaymentCryptographyKeyStrength.bitsOf(algorithm), deriveAlgorithm.name(), algorithm);
        if (tooWeak != null) {
            throw validation(tooWeak);
        }
        return new Imported(algorithm, PaymentCryptographyService.usageName(usage),
                PaymentCryptographyService.modesFor(mode, PaymentCryptographyService.isKeyEncryptionKey(PaymentCryptographyService.usageName(usage))),
                exportable, clear, null, null, null, "SYMMETRIC_KEY");
    }


    // ---- the certificates ----------------------------------------------------




    /**
     * The certificate of a public key.
     *
     * <p>A key that arrived with a certificate keeps it. A key the service made has none, so
     * it gets a self-signed one over its own public half.
     */
    Map<String, Object> publicKeyCertificate(PaymentCryptographyKey key) {
        String pem = key.getKeyCertificatePem();
        if (pem == null || pem.isEmpty()) {
            PublicKey publicKey = publicKeyOf(key);
            PrivateKey privateKey = privateKeyOf(key);
            if (publicKey == null || privateKey == null) {
                throw validation("the key " + key.getKeyId() + " has no public key");
            }
            pem = Certificates.mintSelfSigned(new KeyPair(publicKey, privateKey), "floci",
                    Instant.now().minusSeconds(3600), Instant.now().plusSeconds(31536000L), random).pem();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // The reference names the member KeyCertificate.
        out.put("KeyCertificate", Certificates.pemBase64(pem));
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    /** The public half of an asymmetric key, or null when it has none. */
    static PublicKey publicKeyOf(PaymentCryptographyKey key) {
        if (key == null || key.getKeyPublicKeyBase64() == null
                || key.getKeyPublicKeyBase64().isEmpty()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(key.getKeyPublicKeyBase64());
            return KeyFactory.getInstance(keyFactoryAlgorithm(key)).generatePublic(
                    new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw validation("the public key of " + key.getKeyId() + " does not parse: "
                    + e.getMessage());
        }
    }

    /** The private half of an asymmetric key, or null when it has none. */
    static PrivateKey privateKeyOf(PaymentCryptographyKey key) {
        if (key == null || key.getKeyPrivateKeyBase64() == null
                || key.getKeyPrivateKeyBase64().isEmpty()) {
            return null;
        }
        try {
            byte[] der = Base64.getDecoder().decode(key.getKeyPrivateKeyBase64());
            return KeyFactory.getInstance(keyFactoryAlgorithm(key)).generatePrivate(
                    new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw validation("the private key of " + key.getKeyId() + " does not parse: "
                    + e.getMessage());
        }
    }

    /** The name the key factory knows the key's algorithm by. */
    private static String keyFactoryAlgorithm(PaymentCryptographyKey key) {
        PaymentCryptographyKeyAlgorithm algorithm =
                PaymentCryptographyKeyAlgorithm.from(key.getKeyAlgorithm());
        if (algorithm != null && algorithm.rsa) {
            return "RSA";
        }
        return "EC";
    }

    /**
     * The certificate a counterparty brought, checked against the authority that signed it.
     *
     * <p>The authority has to be allowed to verify, its public key has to be there, the
     * signature has to be the authority's, and the certificate has to be inside its validity
     * window: a certificate that has expired protects nothing.
     *
     * @param certText the certificate, in any of the shapes a certificate travels in
     * @param caKey the key of the authority that is to have signed it
     * @param what the certificate is for, in words the caller can act on
     * @return the certificate
     */
    private static X509Certificate verifiedCertificate(String certText, PaymentCryptographyKey caKey,
                                                       String what) {
        requireVerify(caKey, "verifying a certificate");
        PublicKey caPublicKey = publicKeyOf(caKey);
        if (caPublicKey == null) {
            throw validation("the certificate authority " + caKey.getArn() + " has no public key to "
                    + "verify the certificate against");
        }
        X509Certificate certificate;
        try {
            certificate = Certificates.parseCertificate(certText);
        } catch (RuntimeException e) {
            throw validation(what + " is not a certificate: " + e.getMessage());
        }
        try {
            certificate.verify(caPublicKey);
        } catch (GeneralSecurityException e) {
            throw validation(what + " is not signed by the certificate authority "
                    + caKey.getArn() + ": " + e.getMessage());
        }
        try {
            certificate.checkValidity();
        } catch (GeneralSecurityException e) {
            throw validation(what + " is outside its validity window: " + e.getMessage());
        }
        return certificate;
    }

    /** The size of an RSA modulus, in bits. */
    private static int modulusBitsOf(PublicKey key) {
        if (!(key instanceof RSAKey rsa)) {
            throw validation("the wrapping key certificate carries a " + key.getAlgorithm()
                    + " key, not an RSA one");
        }
        return rsa.getModulus().bitLength();
    }

    /** The algorithm a certificate's public key is of. */
    private static String algorithmOf(PublicKey key) {
        if (key instanceof RSAKey rsa) {
            int bits = rsa.getModulus().bitLength();
            return switch (bits) {
                case 2048 -> "RSA_2048";
                case 3072 -> "RSA_3072";
                case 4096 -> "RSA_4096";
                default -> "RSA_" + bits;
            };
        }
        int fieldBits = ((ECKey) key).getParams().getCurve().getField().getFieldSize();
        return switch (fieldBits) {
            case 256 -> "ECC_NIST_P256";
            case 384 -> "ECC_NIST_P384";
            case 521 -> "ECC_NIST_P521";
            default -> "ECC_NIST_" + fieldBits;
        };
    }

    /** The certificate a caller brought, in PEM. */
    private static String certificatePem(String certText) {
        try {
            X509Certificate certificate = Certificates.parseCertificate(certText);
            return Certificates.toPem(certificate.getEncoded());
        } catch (GeneralSecurityException | RuntimeException e) {
            throw validation("the public key certificate is not a certificate: " + e.getMessage());
        }
    }

    /** The clear material of a key, which an export needs. */
    private static byte[] clearMaterial(PaymentCryptographyKey key) {
        return PaymentCryptographyKeyRules.material(key);
    }

    /** The modes of use the attributes name, as the service reads them. */
    private static Map<String, Boolean> modesOf(Map<String, Object> attributes) {
        Map<String, Boolean> modes = new LinkedHashMap<>();
        Object declared = attributes.get("KeyModesOfUse");
        if (declared instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                modes.put(String.valueOf(entry.getKey()),
                        Boolean.TRUE.equals(entry.getValue()));
            }
        }
        return modes;
    }

    /** A member of the material, as text. */
    private static String text(Map<String, Object> material, String name) {
        Object value = material == null ? null : material.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    /** A member of the material, as a map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> material, String name) {
        Object value = material == null ? null : material.get(name);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** Whether a string is hexadecimal. */
    private static boolean isHex(String text) {
        if (text == null || text.isEmpty() || text.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * The parameter a token names, when it is one this service issued for that kind of
     * material and it has not expired.
     *
     * <p>A token stays usable until it expires: a caller that asks for parameters once may
     * import several keys under them.
     *
     * @param tokens the tokens the service has issued
     * @param token the token the request quotes
     * @param materialType the kind of material the token must have been issued for
     * @return the parameter, or null when the token is not one
     */
    private static Issued take(Map<String, Issued> tokens, String token, String materialType) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Issued issued = tokens.get(token);
        if (issued == null || !issued.materialType.equals(materialType)) {
            return null;
        }
        if (issued.validUntilMs < System.currentTimeMillis()) {
            tokens.remove(token);
            return null;
        }
        return issued;
    }

    /**
     * The number of tokens the service holds.
     *
     * <p>For the test that watches the expired ones swept.
     *
     * @return how many tokens are held
     */
    int issuedTokenCount() {
        return exportTokens.size() + importTokens.size();
    }

    /**
     * Drop the tokens that have expired.
     *
     * <p>A token that was issued and never quoted again would otherwise stay for the life of
     * the process, and it holds a private key.
     *
     * @param tokens the tokens the service has issued
     */
    private static void sweep(Map<String, Issued> tokens) {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(issued -> issued.validUntilMs < now);
    }

    /**
     * A token name the counterparty quotes back.
     *
     * <p>The name says which operation issued it, because the model gives the two kinds
     * their own patterns: an import token is named {@code import-token-} and an export token
     * {@code export-token-}, each followed by letters and digits.
     */
    private String token(String prefix) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return prefix + HEX.formatHex(bytes);
    }

    /**
     * The authority has to be allowed to verify.
     *
     * <p>The authority vouches for a certificate, so a key that may not verify may not be
     * named as one.
     *
     * @param key the authority key
     * @param what what the authority was asked to do, in words
     */
    private static void requireVerify(PaymentCryptographyKey key, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        if (modes != null && !Boolean.TRUE.equals(modes.get("Verify"))) {
            throw validation("the key " + key.getArn() + " does not have the mode of use Verify, "
                    + "so it cannot be used for " + what);
        }
    }

    /**
     * The key an identifier names, and it has to be one the service does work with.
     *
     * <p>A disabled key does no work, so it may not be exported, wrapped or agreed with.
     *
     * @param identifier the key's identifier
     * @param region the region the key is in
     * @return the key
     */
    private PaymentCryptographyKey usableKey(String identifier, String region) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw notFound(identifier);
        }
        PaymentCryptographyKeyRules.requireEnabled(key);
        return key;
    }

    /** The error a bad request is refused with. */
    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    /** The error a missing key is refused with. */
    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 400);
    }

    /**
     * The parameters a counterparty needs to import a key into this service.
     *
     * @param materialType the kind of material the counterparty will send
     * @param algorithm the wrapping algorithm, one of the RSA ones
     */
    Map<String, Object> parametersForImport(String materialType, String algorithm, boolean reuse) {
        if (!IMPORT_PARAMETER_TYPES.contains(materialType)) {
            throw validation("KeyMaterialType '" + materialType + "' does not take import "
                    + "parameters here (KEY_CRYPTOGRAM)");
        }
        String wrappingAlgorithm = algorithm.isEmpty() ? "RSA_2048" : algorithm;
        Integer bits = RSA_BITS.get(wrappingAlgorithm);
        if (bits == null) {
            throw validation("WrappingKeyAlgorithm '" + wrappingAlgorithm + "' not supported "
                    + "(RSA_2048, RSA_3072, RSA_4096)");
        }
        String last = materialType + "/" + wrappingAlgorithm;
        Issued previous = lastImportParameters.get(last);
        if (reuse && previous != null && previous.validUntilMs > System.currentTimeMillis()) {
            return importParameters(previous);
        }
        long now = System.currentTimeMillis();
        KeyPair pair = PaymentCryptographyKeyPairs.rsa(bits, random);
        // The certificate lives as long as the token, so the parameters never hand out a
        // certificate that has expired.
        Certificates.Minted certificate = Certificates.mintSelfSigned(pair, "floci key-import KRD",
                Instant.ofEpochMilli(now).minus(1, ChronoUnit.HOURS),
                Instant.ofEpochMilli(now).plus(importTokenTtlMs, ChronoUnit.MILLIS), random);
        Issued issued = new Issued(token(IMPORT_TOKEN_PREFIX), pair.getPrivate(), certificate.der(),
                wrappingAlgorithm, materialType, now + importTokenTtlMs);
        sweep(importTokens);
        importTokens.put(issued.token, issued);
        lastImportParameters.put(last, issued);
        return importParameters(issued);
    }

    /** The import parameters of an issued token, as the answer carries them. */
    private static Map<String, Object> importParameters(Issued issued) {
        String certificate64 = Certificates.pemBase64(Certificates.toPem(issued.certificateDer));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ImportToken", issued.token);
        out.put("WrappingKeyCertificate", certificate64);
        // Self-signed, so the chain is the certificate itself.
        out.put("WrappingKeyCertificateChain", certificate64);
        out.put("WrappingKeyAlgorithm", issued.algorithm);
        out.put("ParametersValidUntilTimestamp", issued.validUntilMs / 1000);
        return out;
    }

    /**
     * The parameters a counterparty needs to import a key this service exported.
     *
     * <p>The service signs the block it exports, so the parameters carry the signing key's
     * certificate and the token that names the export. The export token is issued and kept
     * for as long as the parameters are valid; the model has no member that carries it into
     * an export, so nothing consumes it yet.
     *
     * @param materialType the kind of material the export carries
     * @param algorithm the signing key's algorithm
     * @return the parameters the counterparty quotes back
     */
    Map<String, Object> parametersForExport(String materialType, String algorithm, boolean reuse) {
        if (!EXPORT_PARAMETER_TYPES.contains(materialType)) {
            throw validation("KeyMaterialType '" + materialType + "' does not take export "
                    + "parameters here (" + String.join(", ", EXPORT_PARAMETER_TYPES) + ")");
        }
        PaymentCryptographyKeyAlgorithm signing = PaymentCryptographyKeyAlgorithm.from(algorithm);
        if (signing == null || signing.symmetric()) {
            throw validation("SigningKeyAlgorithm '" + algorithm + "' not supported "
                    + "(RSA_2048, RSA_3072, RSA_4096, ECC_NIST_P256, ECC_NIST_P384, ECC_NIST_P521)");
        }
        String last = materialType + "/" + algorithm;
        Issued previous = lastExportParameters.get(last);
        if (reuse && previous != null && previous.validUntilMs > System.currentTimeMillis()) {
            return exportParameters(previous);
        }
        long now = System.currentTimeMillis();
        KeyPair pair = signing.rsa
                ? PaymentCryptographyKeyPairs.rsa(signing.bytes * 8, random)
                : PaymentCryptographyKeyPairs.ec(signing, random);
        // The certificate lives as long as the token, so the parameters never hand out a
        // certificate that has expired.
        Certificates.Minted certificate = Certificates.mintSelfSigned(pair, "floci key-export KRD",
                Instant.ofEpochMilli(now).minus(1, ChronoUnit.HOURS),
                Instant.ofEpochMilli(now).plus(exportTokenTtlMs, ChronoUnit.MILLIS), random);
        Issued issued = new Issued(token(EXPORT_TOKEN_PREFIX), pair.getPrivate(), certificate.der(),
                algorithm, materialType, now + exportTokenTtlMs);
        sweep(exportTokens);
        exportTokens.put(issued.token, issued);
        lastExportParameters.put(last, issued);
        return exportParameters(issued);
    }

    /** The export parameters of an issued token, as the answer carries them. */
    private static Map<String, Object> exportParameters(Issued issued) {
        String certificate64 = Certificates.pemBase64(Certificates.toPem(issued.certificateDer));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ExportToken", issued.token);
        out.put("SigningKeyCertificate", certificate64);
        // Self-signed, so the chain is the certificate itself.
        out.put("SigningKeyCertificateChain", certificate64);
        out.put("SigningKeyAlgorithm", issued.algorithm);
        out.put("ParametersValidUntilTimestamp", issued.validUntilMs / 1000);
        return out;
    }

    /** A public key out of a certificate. */
    private Imported importPublicKey(String region, Map<String, Object> material,
                                    String caIdentifier) {
        String certText = text(material, "PublicKeyCertificate");
        if (certText.isEmpty()) {
            throw validation("KeyMaterial.PublicKeyCertificate is required");
        }
        X509Certificate certificate;
        try {
            certificate = Certificates.parseCertificate(certText);
        } catch (RuntimeException e) {
            throw validation("the public key certificate is not a certificate: " + e.getMessage());
        }
        // A trusted certificate is one the named authority signed, so it is checked against
        // that authority. A root certificate stands on its own: it is the authority.
        if (!caIdentifier.isEmpty()) {
            PaymentCryptographyKey caKey = service.findKey(region, caIdentifier);
            if (caKey == null) {
                throw notFound(caIdentifier);
            }
            certificate = verifiedCertificate(certText, caKey, "the public key certificate");
        } else {
            try {
                certificate.checkValidity();
            } catch (GeneralSecurityException e) {
                throw validation("the public key certificate is outside its validity window: "
                        + e.getMessage());
            }
        }
        PublicKey publicKey = certificate.getPublicKey();
        Map<String, Object> attributes = object(material, "KeyAttributes");
        if (attributes == null) {
            throw validation("KeyMaterial.KeyAttributes is required");
        }
        String algorithm = algorithmOf(publicKey);
        Map<String, Boolean> modes = modesOf(attributes);
        return new Imported(algorithm, String.valueOf(attributes.get("KeyUsage")), modes, true,
                null, Base64.getEncoder().encodeToString(publicKey.getEncoded()), null,
                certificatePem(certText), "PUBLIC_KEY");
    }
}
