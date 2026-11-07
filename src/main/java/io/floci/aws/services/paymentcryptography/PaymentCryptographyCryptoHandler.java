package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.Ciphers;
import io.floci.aws.services.paymentcryptography.crypto.DukptCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data plane's encryption and its MACs: the operations that use a key to protect data.
 *
 * <p>The data plane is a service of its own, with its own host and its own sign name, and it
 * is served over rest-json rather than over the control plane's awsJson. The operations are
 * the same either way, so they live here and the protocol handler calls them.
 */
@ApplicationScoped
public class PaymentCryptographyCryptoHandler {

    /** The usages a key may have for the data encryption operations. */
    private static final List<String> DATA_ENCRYPTION_USAGES = List.of(
            "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

    /** The usages a key may have for the MAC operations. */
    private static final List<String> MAC_USAGES = List.of(
            "TR31_M0_ISO_16609_MAC_KEY",
            "TR31_M1_ISO_9797_1_MAC_KEY",
            "TR31_M3_ISO_9797_3_MAC_KEY",
            "TR31_M6_ISO_9797_5_CMAC_KEY",
            "TR31_M7_HMAC_KEY");

    /** The usages a key may have for the derivation a DUKPT MAC key is made by. */
    private static final List<String> DERIVATION_USAGES = List.of(
            "TR31_B0_BASE_DERIVATION_KEY");

    /** The hexadecimal the blobs of the protocol carry themselves in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** The block size of AES, in bytes: the block the cipher works in. */
    private static final int AES_BLOCK = 16;

    /** The block size of Triple DES, in bytes: the block the cipher works in. */
    private static final int TDES_BLOCK = 8;

    /** The shortest MAC the service computes, in bytes. */
    private static final int SHORTEST_MAC = 4;

    /** The longest MAC the ISO 9797 algorithms give, in bytes. */
    private static final int LONGEST_MAC = 8;

    /** The longest MAC the CMAC gives, in bytes: a whole AES block. */
    private static final int LONGEST_CMAC = 16;

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyCryptoHandler(PaymentCryptographyService service) {
        this.service = service;
    }

    /**
     * Encrypts the plaintext under the key the request names.
     *
     * <p>The service encrypts with the cipher the key's algorithm names, in CBC mode, and
     * pads no data, so the plaintext fills whole blocks, and answers with the ciphertext
     * alone: the initialization vector travels in the request's attributes.
     */
    public Map<String, Object> encryptData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("EncryptData", request);
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        requireMode(key, "Encrypt", "encrypt");
        Encryption encryption = encryptionAttributes(request.get("EncryptionAttributes"));
        requireCipherFamily(key, "encrypt");
        requireUsage(key, DATA_ENCRYPTION_USAGES, "encrypt data");
        byte[] plaintext = blob(request, "PlainText");
        requireBlockAligned(plaintext, key, "PlainText");
        byte[] ciphertext = encrypt(material(key), plaintext,
                initializationVector(encryption, key, "encrypting"), key);
        return answer(key, Map.of("CipherText", encode(ciphertext)));
    }

    /**
     * Decrypts a blob the service encrypted.
     */
    public Map<String, Object> decryptData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("DecryptData", request);
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        requireMode(key, "Decrypt", "decrypt");
        Encryption encryption = encryptionAttributes(request.get("DecryptionAttributes"));
        requireCipherFamily(key, "decrypt");
        requireUsage(key, DATA_ENCRYPTION_USAGES, "decrypt data");
        byte[] ciphertext = blob(request, "CipherText");
        requireBlockAligned(ciphertext, key, "CipherText");
        byte[] plaintext = decrypt(material(key), ciphertext,
                initializationVector(encryption, key, "decrypting"), key);
        return answer(key, Map.of("PlainText", encode(plaintext)));
    }

    /**
     * Decrypts under one key and encrypts under another.
     */
    public Map<String, Object> reEncryptData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("ReEncryptData", request);
        PaymentCryptographyKey incoming = requiredKey(request, region, "IncomingKeyIdentifier");
        requireMode(incoming, "Decrypt", "decrypt");
        PaymentCryptographyKey outgoing = requiredKey(request, region, "OutgoingKeyIdentifier");
        requireMode(outgoing, "Encrypt", "encrypt");
        Encryption incomingEncryption =
                encryptionAttributes(request.get("IncomingEncryptionAttributes"));
        Encryption outgoingEncryption =
                encryptionAttributes(request.get("OutgoingEncryptionAttributes"));
        requireCipherFamily(incoming, "decrypt");
        requireCipherFamily(outgoing, "encrypt");
        requireUsage(incoming, DATA_ENCRYPTION_USAGES, "decrypt data");
        requireUsage(outgoing, DATA_ENCRYPTION_USAGES, "encrypt data");
        byte[] ciphertext = blob(request, "CipherText");
        requireBlockAligned(ciphertext, incoming, "CipherText");
        byte[] plaintext = decrypt(material(incoming), ciphertext,
                initializationVector(incomingEncryption, incoming, "decrypting"), incoming);
        requireBlockAligned(plaintext, outgoing, "the plaintext");
        byte[] reEncrypted = encrypt(material(outgoing), plaintext,
                initializationVector(outgoingEncryption, outgoing, "encrypting"), outgoing);
        return answer(outgoing, Map.of("CipherText", encode(reEncrypted)));
    }

    /**
     * The MAC of a message under the key: the CMAC of the message, cut to the length the
     * caller asks for.
     */
    public Map<String, Object> generateMac(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("GenerateMac", request);
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        MacLeg leg = resolveMacLeg(request.get("GenerationAttributes"), key, region, "Generate",
                "generating a MAC with");
        byte[] message = blob(request, "MessageData");
        int length = macLength(request, leg, leg.longest);
        byte[] mac = leg.mac.apply(leg.keyBytes, message);
        byte[] answer = new byte[length];
        System.arraycopy(mac, 0, answer, 0, length);
        return answer(key, Map.of("Mac", encode(answer)));
    }

    /**
     * The verdict of a MAC over a message.
     */
    public Map<String, Object> verifyMac(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("VerifyMac", request);
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        MacLeg leg = resolveMacLeg(request.get("VerificationAttributes"), key, region, "Verify",
                "verifying a MAC with");
        byte[] message = blob(request, "MessageData");
        byte[] mac = blob(request, "Mac");
        int length = macLength(request, leg, mac.length);
        if (length != mac.length) {
            throw new AwsException("ValidationException",
                    "MacLength does not match the length of Mac", 400);
        }
        byte[] expected = leg.mac.apply(leg.keyBytes, message);
        byte[] cut = new byte[length];
        System.arraycopy(expected, 0, cut, 0, Math.min(length, expected.length));
        if (!MessageDigest.isEqual(cut, mac)) {
            // The service does not answer that a MAC is not valid: it fails, because a caller
            // that asks whether a MAC is good gets an error rather than a false.
            throw new AwsException("VerificationFailedException",
                    "This request failed verification.", 400, Map.of("Reason", "INVALID_MAC"));
        }
        return answer(key, Map.of());
    }


    /** The PIN data of a card account, verified against a generation key. */

    /** The PIN data of one scheme translated into another. */

    /** The card validation value of a card account. */

    /** The card validation value of a card account, verified. */

    /** The cryptogram of an authorization request, verified. */

    /** The cryptogram of an authorization request, generated. */

    /** The material of a key translated into another form. */
    public Map<String, Object> translateKeyMaterial(JsonNode request, String region) {
        refused("TranslateKeyMaterial");
        return Map.of();
    }

    /**
     * The operation is not served, and says so.
     */
    private static void refused(String operation) {
        throw new AwsException("ValidationException",
                operation + " is not served: its algorithm belongs to the payment scheme, and this "
                        + "service does not make one up", 400);
    }

    /** The answer of an operation: what the operation made, and which key it made it with. */
    private static Map<String, Object> answer(PaymentCryptographyKey key, Map<String, Object> made) {
        Map<String, Object> out = new LinkedHashMap<>(made);
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        return out;
    }

    /**
     * The cipher the request asks for.
     *
     * <p>The attributes name the mode and, for a symmetric cipher, the initialization vector.
     * The service encrypts in CBC mode, so a request that names another mode is refused
     * rather than answered with a cipher it did not ask for.
     *
     * @param attributes the attributes of the request, which may be absent
     * @return the cipher the request asks for
     */
    private static Encryption encryptionAttributes(JsonNode attributes) {
        if (attributes == null || attributes.isNull()) {
            throw new AwsException("ValidationException",
                    "the encryption attributes are required", 400);
        }
        String variant = soleVariant(attributes);
        if (variant == null) {
            throw new AwsException("ValidationException",
                    "the encryption attributes must specify exactly one variant", 400);
        }
        if (!"Symmetric".equals(variant)) {
            throw new AwsException("ValidationException",
                    "the encryption variant '" + variant + "' is not supported by this service "
                            + "(Symmetric)", 400);
        }
        JsonNode symmetric = attributes.get(variant);
        String mode = symmetric == null ? "" : symmetric.path("Mode").asText();
        if (!"CBC".equals(mode)) {
            throw new AwsException("ValidationException",
                    "the encryption mode '" + (mode.isEmpty() ? "(missing)" : mode)
                            + "' is not supported by this service (CBC)", 400);
        }
        if (symmetric != null && symmetric.hasNonNull("PaddingType")) {
            throw new AwsException("ValidationException",
                    "PaddingType applies to RSA only; the symmetric cipher does not pad", 400);
        }
        byte[] iv = null;
        if (symmetric != null && symmetric.hasNonNull("InitializationVector")) {
            String hex = symmetric.path("InitializationVector").asText();
            if (!hex.isEmpty()) {
                // The model's pattern says the vector is hexadecimal, so it only decodes here.
                iv = HexFormat.of().parseHex(hex);
            }
        }
        return new Encryption(iv);
    }

    /**
     * The initialization vector of a request, checked against the cipher's block size.
     *
     * <p>The length follows the key: an AES key takes a sixteen-byte vector and a Triple DES
     * one an eight-byte one, so a vector for one is not a vector for the other. A vector of
     * the wrong length is refused here, where the caller can act on it, and not by the
     * cipher, where it would be a server error.
     *
     * @param encryption the attributes of the request
     * @param key the key the cipher runs under
     * @param what the operation that is refused, for the message
     * @return the vector, or null when the request names none
     */
    private static byte[] initializationVector(Encryption encryption, PaymentCryptographyKey key,
                                               String what) {
        byte[] iv = encryption.initializationVector();
        if (iv == null) {
            return null;
        }
        int length = isAes(key) ? 16 : 8;
        if (iv.length != length) {
            throw new AwsException("ValidationException",
                    "the initialization vector is " + iv.length + " bytes; " + what + " under a "
                            + (isAes(key) ? "AES" : "Triple DES") + " key takes " + length, 400);
        }
        return iv;
    }

    /**
     * Whether a key is an AES key.
     *
     * <p>The key record knows its algorithm, so the family is read from there and not guessed
     * from the length of the material: a sixteen-byte key is a two-key Triple DES one and an
     * AES-128 one.
     */
    private static boolean isAes(PaymentCryptographyKey key) {
        return PaymentCryptographyService.isAes(key);
    }

    /**
     * Whether the key is a Triple DES one.
     *
     * <p>The reference ciphers with AES and with Triple DES, and the block and the vector follow
     * the key's family: sixteen bytes and a sixteen-byte vector for AES, eight for Triple DES.
     *
     * @param key the key the cipher runs under
     * @return whether the key is a Triple DES one
     */
    private static boolean isTdes(PaymentCryptographyKey key) {
        return PaymentCryptographyService.isTdes(key);
    }

    /**
     * The family a key's algorithm has to be for an operation.
     *
     * <p>The algorithm runs under the key: the ISO 9797 MACs are defined over Triple DES and
     * the CMAC over AES, so a key of the other family would be read as bytes it is not.
     *
     * @param key the key the operation runs under
     * @param aes whether the operation needs an AES key
     * @param what the operation, for the message
     */
    private static void requireFamily(PaymentCryptographyKey key, boolean aes, String what) {
        if (isAes(key) != aes) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is a " + key.getKeyAlgorithm() + " key, and "
                            + what + " needs a " + (aes ? "AES" : "Triple DES") + " one", 400);
        }
    }

    /**
     * The family a key's algorithm belongs to.
     *
     * <p>The cipher runs under the key's own algorithm: an AES key is encrypted with AES and
     * a Triple DES one with Triple DES. A key whose algorithm this service does not cipher
     * with is refused rather than run through the cipher it does have.
     *
     * @param key the key the cipher runs under
     * @param what the operation that is refused, for the message
     */
    private static void requireCipherFamily(PaymentCryptographyKey key, String what) {
        if (!isAes(key) && !isTdes(key)) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is a " + key.getKeyAlgorithm()
                            + " key, and this service does not cipher with that algorithm to "
                            + what, 400);
        }
    }

    /**
     * The ciphertext of a plaintext under the key, in the cipher the key's family runs.
     *
     * @param key the key's material
     * @param plaintext the bytes to encrypt
     * @param initializationVector the vector to encrypt under
     * @param record the key the cipher runs under, whose family names the cipher
     * @return the encrypted bytes
     */
    private byte[] encrypt(byte[] key, byte[] plaintext, byte[] initializationVector,
                           PaymentCryptographyKey record) {
        return isAes(record)
                ? PaymentCryptographyCrypto.encryptCbc(key, plaintext, initializationVector)
                : PaymentCryptographyCrypto.tdesCbcEncrypt(key, plaintext, initializationVector);
    }

    /**
     * The plaintext of a ciphertext the service encrypted under the key.
     *
     * @param key the key's material
     * @param ciphertext the encrypted bytes
     * @param initializationVector the vector to decrypt under
     * @param record the key the cipher runs under, whose family names the cipher
     * @return the plaintext
     */
    private static byte[] decrypt(byte[] key, byte[] ciphertext, byte[] initializationVector,
                                  PaymentCryptographyKey record) {
        return isAes(record)
                ? PaymentCryptographyCrypto.decryptCbc(key, ciphertext, initializationVector)
                : PaymentCryptographyCrypto.tdesCbcDecrypt(key, ciphertext, initializationVector);
    }

    /** The cipher a request asks for. */
    private record Encryption(byte[] initializationVector) {
    }

    /** The key the request names, in the member the operation names it in. */
    private PaymentCryptographyKey requiredKey(JsonNode request, String region, String member) {
        String identifier = request.path(member).asText();
        if (identifier.isEmpty()) {
            throw new AwsException("ValidationException", member + " is required", 400);
        }
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 404);
        }
        requireEnabled(key);
        return key;
    }

    /**
     * A key that is disabled does no work.
     *
     * <p>The service reads a disabled key and tells the caller about it, but it does not
     * encrypt with it, decrypt with it, or make a MAC under it.
     *
     * @param key the key the request names
     */
    private static void requireEnabled(PaymentCryptographyKey key) {
        if (!key.isEnabled()) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " is disabled", 400);
        }
    }

    /**
     * The material of a key.
     *
     * <p>The material is held by the key while the emulator runs and is not written to the
     * store, so a key read back after a restart has none. A request that needs the material
     * is then refused with an error that says so, rather than failing on the missing value.
     *
     * @param key the key the operation runs under
     * @return the key's material
     */
    private static byte[] material(PaymentCryptographyKey key) {
        return PaymentCryptographyCrypto.fromHex(materialHex(key));
    }

    /**
     * The material of a key, as it is held.
     *
     * <p>The material is held by the key while the emulator runs and is not written to the
     * store, so a key read back after a restart has none. A request that needs the material
     * is then refused with an error that says so, rather than failing on the missing value.
     *
     * @param key the key the operation runs under
     * @return the key's material, as hexadecimal
     */
    private static String materialHex(PaymentCryptographyKey key) {
        String hex = key.getKeyMaterialHex();
        if (hex == null || hex.isEmpty()) {
            throw new AwsException("ValidationException",
                    "the material of the key " + key.getArn() + " is not held: it is kept while the "
                            + "emulator runs and is not written to the store, so a key read back after "
                            + "a restart has none", 400);
        }
        return hex;
    }

    /**
     * The block the key's cipher works in: sixteen bytes for AES and eight for Triple DES, as
     * the reference says.
     *
     * @param key the key the cipher runs under
     * @return the block size, in bytes
     */
    private static int blockOf(PaymentCryptographyKey key) {
        return isAes(key) ? AES_BLOCK : TDES_BLOCK;
    }

    /**
     * The data must fill whole blocks of the key's cipher.
     *
     * <p>The cipher pads no data, so the caller supplies whole blocks: a plaintext that does
     * not fill them is refused, rather than padded into a ciphertext that is longer than the
     * data it holds. The block follows the key's cipher, as the reference says: sixteen bytes
     * for AES and eight for Triple DES.
     *
     * @param data the data the request names
     * @param key the key the cipher runs under, whose cipher sets the block size
     * @param member the member the data is, for the message
     */
    private static void requireBlockAligned(byte[] data, PaymentCryptographyKey key, String member) {
        int block = blockOf(key);
        if (data.length % block != 0) {
            throw new AwsException("ValidationException",
                    member + " must be a multiple of " + block + " bytes (one "
                            + (isAes(key) ? "AES" : "TDES") + " block)", 400);
        }
    }

    /**
     * The usage a key has to have for an operation.
     *
     * <p>The usage is what binds a key to its purpose, so an operation that belongs to one
     * purpose is not served with a key of another: a PIN encryption key does not encrypt
     * arbitrary data, and a MAC key does not either. The check is on the usage itself, not
     * on the mode, because a key may carry the mode and still be of another purpose.
     *
     * @param key the key the operation runs under
     * @param usages the usages the operation serves
     * @param what the operation, for the message
     */
    private static void requireUsage(PaymentCryptographyKey key, List<String> usages, String what) {
        String usage = key.getKeyUsage();
        if (usage == null || usages.contains(usage)) {
            return;
        }
        throw new AwsException("ValidationException",
                "the key " + key.getArn() + " is of the usage " + usage + ", and it may not be used to "
                        + what + ", which needs a key of the usage " + String.join(" or ", usages), 400);
    }

    /** The modes of use a key has to have for an operation. */
    private static void requireMode(PaymentCryptographyKey key, String mode, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        if (modes == null) {
            return;
        }
        // A NoRestrictions that is false is not a permission: a request that names the
        // member says nothing about what it allows, so the mode itself decides.
        if (Boolean.TRUE.equals(modes.get("NoRestrictions"))) {
            return;
        }
        if (!Boolean.TRUE.equals(modes.get(mode))) {
            throw new AwsException("ValidationException",
                    "the key " + key.getArn() + " does not have the mode of use " + mode
                            + ", which it needs to " + what, 400);
        }
    }

    /**
     * The MAC a request asks for, and the key it is computed under.
     *
     * <p>The attributes name the algorithm: the ISO 9797-1 algorithms 1 and 3 for the
     * {@code Algorithm} variant, and the DUKPT derivations of the same two for the DUKPT
     * variants. A DUKPT MAC key is derived from the base derivation key, so that is what the
     * key has to allow.
     */
    private MacLeg resolveMacLeg(JsonNode attributes, PaymentCryptographyKey key, String region,
                                 String mode, String what) {
        String variant = soleVariant(attributes);
        if (variant == null) {
            throw new AwsException("ValidationException",
                    "MacAttributes must specify exactly one variant", 400);
        }
        if ("Algorithm".equals(variant)) {
            // The algorithm is a member of the attributes themselves, not of the variant.
            String algorithm = text(attributes, "Algorithm");
            if ("ISO9797_ALGORITHM1".equals(algorithm)) {
                requireMode(key, mode, what);
                requireUsage(key, MAC_USAGES, what);
                // The ISO 9797 MACs are defined over Triple DES, so the key is one.
                requireFamily(key, false, what);
                return new MacLeg(Ciphers::iso9797Alg1, material(key), LONGEST_MAC);
            }
            if ("ISO9797_ALGORITHM3".equals(algorithm)) {
                requireMode(key, mode, what);
                requireUsage(key, MAC_USAGES, what);
                requireFamily(key, false, what);
                return new MacLeg(Ciphers::iso9797Alg3, material(key), LONGEST_MAC);
            }
            if ("CMAC".equals(algorithm)) {
                // The CMAC is the AES-CMAC of RFC 4493, which is a whole block
                // wide, so it is cut to a length the caller asks for.
                requireMode(key, mode, what);
                requireUsage(key, MAC_USAGES, what);
                // The CMAC is defined over AES, so the key is one.
                requireFamily(key, true, what);
                return new MacLeg(PaymentCryptographyCrypto::cmac, material(key), LONGEST_CMAC);
            }
            throw new AwsException("ValidationException",
                    "Algorithm '" + algorithm + "' not supported by this service "
                            + "(ISO9797_ALGORITHM1, ISO9797_ALGORITHM3, CMAC)", 400);
        }
        if ("DukptIso9797Algorithm1".equals(variant) || "DukptIso9797Algorithm3".equals(variant)) {
            // A DUKPT MAC key is derived from the base derivation key, so that is what the key
            // has to allow, and the derivation is over Triple DES, so the key is one.
            requireMode(key, "DeriveKey", "deriving a MAC key");
            requireUsage(key, DERIVATION_USAGES, "deriving a MAC key");
            requireFamily(key, false, "deriving a MAC key");
            JsonNode variantAttributes = attributes.get(variant);
            // The model's pattern says the serial number is hexadecimal, so it only decodes here.
            String ksn = text(variantAttributes, "KeySerialNumber");
            String keyVariant = text(variantAttributes, "DukptKeyVariant");
            if (!keyVariant.isEmpty() && !"REQUEST".equals(keyVariant)) {
                throw new AwsException("ValidationException",
                        variant + ".DukptKeyVariant '" + keyVariant
                                + "' not supported by this service (REQUEST)", 400);
            }
            String derivationType = text(variantAttributes, "DukptDerivationType");
            if (!derivationType.isEmpty() && !"TDES_2KEY".equals(derivationType)) {
                throw new AwsException("ValidationException",
                        variant + ".DukptDerivationType '" + derivationType
                                + "' not supported by this service (TDES_2KEY)", 400);
            }
            byte[] derived;
            try {
                derived = DukptCrypto.macKey(materialHex(key), ksn);
            } catch (IllegalArgumentException e) {
                // The derivation refuses what the request names, so the refusal is the
                // caller's to act on. Anything else is a fault of the service and is
                // left to travel as one.
                throw new AwsException("ValidationException",
                        "DUKPT derivation failed: " + e.getMessage(), 400);
            }
            return new MacLeg("DukptIso9797Algorithm1".equals(variant)
                    ? Ciphers::iso9797Alg1 : Ciphers::iso9797Alg3, derived, LONGEST_MAC);
        }
        throw new AwsException("ValidationException",
                "MacAttributes." + variant + " not supported by this service", 400);
    }

    /** The MAC a request asks for, and the key it is computed under. */
    private static final class MacLeg {
        final MacFunction mac;
        final byte[] keyBytes;
        /** The longest MAC this algorithm gives, in bytes. */
        final int longest;

        MacLeg(MacFunction mac, byte[] keyBytes, int longest) {
            this.mac = mac;
            this.keyBytes = keyBytes;
            this.longest = longest;
        }
    }

    /** A MAC over a message under a key. */
    private interface MacFunction {
        byte[] apply(byte[] key, byte[] message);
    }

    /** The one variant of an attributes structure, or null when there is not exactly one. */
    private static String soleVariant(JsonNode attributes) {
        if (attributes == null || !attributes.isObject()) {
            return null;
        }
        String found = null;
        Iterator<Map.Entry<String, JsonNode>> fields = attributes.fields();
        while (fields.hasNext()) {
            if (found != null) {
                return null;
            }
            found = fields.next().getKey();
        }
        return found;
    }

    /** The text of a member. */
    private static String text(JsonNode request, String member) {
        if (request == null) {
            return "";
        }
        JsonNode declared = request.get(member);
        return declared == null || declared.isNull() ? "" : declared.asText("");
    }

    /**
     * A blob of the request.
     *
     * <p>The data plane carries its blobs as hexadecimal, which is what the reference says it
     * does. The request has already been checked against the model, which says that the
     * member is there, that it is a string and what it holds, so this only decodes it.
     */
    private static byte[] blob(JsonNode request, String member) {
        return PaymentCryptographyCrypto.fromHex(request.get(member).textValue());
    }

    /**
     * The length of the MAC the caller asks for, which the service computes.
     *
     * <p>The model says the length is a whole number of at least four bytes, and the
     * request has been checked against it. What the algorithm gives is shorter than the
     * model allows, so a length above it is refused here.
     */
    private static int macLength(JsonNode request, MacLeg leg, int fallback) {
        int length = request.path("MacLength").asInt(fallback);
        if (length < SHORTEST_MAC || length > leg.longest) {
            throw new AwsException("ValidationException",
                    "MacLength must be between " + SHORTEST_MAC + " and " + leg.longest, 400);
        }
        return length;
    }

    /** A blob as the protocol carries it: hexadecimal. */
    private static String encode(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
