package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.DukptCrypto;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.IsoMac;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The data plane of Payment Cryptography: the operations that use a key.
 *
 * <p>The data plane is a service of its own, with its own host and its own sign name, and it
 * is served over rest-json rather than over the control plane's awsJson. The operations are
 * the same either way, so they live here and the two protocol handlers call them.
 *
 * <p>The operations that need the algorithms of the payment schemes are refused rather than
 * answered: the card verification values, the cryptograms and the PIN block formats are the
 * schemes' own, and an answer this service made up would be worse than an error.
 */
@ApplicationScoped
public class PaymentCryptographyDataPlaneHandler {

    /** The shortest MAC the service computes, in bytes. */
    private static final int SHORTEST_MAC = 4;

    /** The longest MAC the service computes, in bytes. */
    private static final int LONGEST_MAC = 8;

    /** The shortest plaintext the service encrypts, in bytes. */
    private static final int SHORTEST_PLAINTEXT = 8;

    /** The longest plaintext the service encrypts, in bytes. */
    private static final int LONGEST_PLAINTEXT = 2032;

    private final PaymentCryptographyService service;
    private final PaymentCryptographyPinOperations pinOperations;
    private final PaymentCryptographyCardValidationOperations cardValidation;
    private final PaymentCryptographyAuthCryptogramOperations authCryptogram;
    private final SecureRandom random;

    @Inject
    public PaymentCryptographyDataPlaneHandler(PaymentCryptographyService service) {
        this(service, new SecureRandom());
    }

    PaymentCryptographyDataPlaneHandler(PaymentCryptographyService service, SecureRandom random) {
        this.service = service;
        this.pinOperations = new PaymentCryptographyPinOperations(service);
        this.cardValidation = new PaymentCryptographyCardValidationOperations(service);
        this.authCryptogram = new PaymentCryptographyAuthCryptogramOperations(service);
        this.random = random;
    }

    /**
     * Encrypts the plaintext under the key the request names.
     *
     * <p>The service encrypts with AES in CBC mode and the padding it calls PKCS#5, and
     * answers with the initialization vector and the ciphertext in one blob.
     */
    public Map<String, Object> encryptData(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        requireMode(key, "Encrypt", "encrypt");
        byte[] plaintext = blob(request, "PlainText", SHORTEST_PLAINTEXT, LONGEST_PLAINTEXT);
        byte[] ciphertext = PaymentCryptographyCrypto.encryptCbc(material(key), plaintext, random);
        return answer(key, Map.of("CipherText", encode(ciphertext)));
    }

    /**
     * Decrypts a blob the service encrypted.
     */
    public Map<String, Object> decryptData(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        requireMode(key, "Decrypt", "decrypt");
        byte[] ciphertext = blob(request, "CipherText", SHORTEST_PLAINTEXT, LONGEST_PLAINTEXT);
        byte[] plaintext;
        try {
            plaintext = PaymentCryptographyCrypto.decryptCbc(material(key), ciphertext);
        } catch (IllegalArgumentException e) {
            throw new AwsException("DecryptFailureException", e.getMessage(), 400);
        }
        return answer(key, Map.of("PlainText", encode(plaintext)));
    }

    /**
     * Decrypts under one key and encrypts under another.
     */
    public Map<String, Object> reEncryptData(JsonNode request, String region) {
        PaymentCryptographyKey incoming = requiredKey(request, region, "IncomingKeyIdentifier");
        requireMode(incoming, "Decrypt", "decrypt");
        PaymentCryptographyKey outgoing = requiredKey(request, region, "OutgoingKeyIdentifier");
        requireMode(outgoing, "Encrypt", "encrypt");
        byte[] ciphertext = blob(request, "CipherText", SHORTEST_PLAINTEXT, LONGEST_PLAINTEXT);
        byte[] plaintext;
        try {
            plaintext = PaymentCryptographyCrypto.decryptCbc(material(incoming), ciphertext);
        } catch (IllegalArgumentException e) {
            throw new AwsException("DecryptFailureException", e.getMessage(), 400);
        }
        byte[] reEncrypted = PaymentCryptographyCrypto.encryptCbc(material(outgoing), plaintext, random);
        return answer(outgoing, Map.of("CipherText", encode(reEncrypted)));
    }

    /**
     * The MAC of a message under the key: the CMAC of the message, cut to the length the
     * caller asks for.
     */
    public Map<String, Object> generateMac(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        MacLeg leg = resolveMacLeg(request.get("GenerationAttributes"), key, region, "Generate",
                "generating a MAC with");
        byte[] message = blob(request, "MessageData", 1, 2048);
        int length = macLength(request);
        byte[] mac = leg.mac.apply(leg.keyBytes, message);
        byte[] answer = new byte[length];
        System.arraycopy(mac, 0, answer, 0, length);
        return answer(key, Map.of("Mac", encode(answer)));
    }

    /**
     * The verdict of a MAC over a message.
     */
    public Map<String, Object> verifyMac(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        MacLeg leg = resolveMacLeg(request.get("VerificationAttributes"), key, region, "Verify",
                "verifying a MAC with");
        byte[] message = blob(request, "MessageData", 1, 2048);
        byte[] mac = blob(request, "Mac", 4, 8);
        int length = request.has("MacLength") ? request.path("MacLength").asInt(mac.length) : mac.length;
        if (!isMacLength(length)) {
            throw new AwsException("ValidationException",
                    "MacLength must be between " + SHORTEST_MAC + " and " + LONGEST_MAC, 400);
        }
        byte[] expected = leg.mac.apply(leg.keyBytes, message);
        byte[] cut = new byte[length];
        System.arraycopy(expected, 0, cut, 0, Math.min(length, expected.length));
        if (mac.length != length || !MessageDigest.isEqual(cut, mac)) {
            // The service does not answer that a MAC is not valid: it fails, because a caller
            // that asks whether a MAC is good gets an error rather than a false.
            throw new AwsException("VerificationFailedException",
                    "This request failed verification.", 400);
        }
        return answer(key, Map.of());
    }

    /**
     * The PIN data of a card account: a PIN block and the verification value that goes with
     * it.
     */
    public Map<String, Object> generatePinData(JsonNode request, String region) {
        return pinOperations.generatePinData(request, region);
    }

    /** The PIN data of a card account, verified against a generation key. */
    public Map<String, Object> verifyPinData(JsonNode request, String region) {
        return pinOperations.verifyPinData(request, region);
    }

    /** The PIN data of one scheme translated into another. */
    public Map<String, Object> translatePinData(JsonNode request, String region) {
        return pinOperations.translatePinData(request, region);
    }

    /** The card validation value of a card account. */
    public Map<String, Object> generateCardValidationData(JsonNode request, String region) {
        return cardValidation.generate(request, region);
    }

    /** The card validation value of a card account, verified. */
    public Map<String, Object> verifyCardValidationData(JsonNode request, String region) {
        return cardValidation.verify(request, region);
    }

    /** The cryptogram of an authorization request, verified. */
    public Map<String, Object> verifyAuthRequestCryptogram(JsonNode request, String region) {
        return authCryptogram.verify(request, region);
    }

    /** The cryptogram of an authorization request, generated. */
    public Map<String, Object> generateAuthRequestCryptogram(JsonNode request, String region) {
        return authCryptogram.generate(request, region);
    }

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

    /** The key the request names, in the member the operation names it in. */
    private PaymentCryptographyKey requiredKey(JsonNode request, String region, String member) {
        String identifier = request.path(member).asText();
        if (identifier.isEmpty()) {
            throw new AwsException("ValidationException", member + " is required", 400);
        }
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 400);
        }
        return key;
    }

    /** The material of a key. */
    private static byte[] material(PaymentCryptographyKey key) {
        return PaymentCryptographyCrypto.fromHex(key.getKeyMaterialHex());
    }

    /** The modes of use a key has to have for an operation. */
    private static void requireMode(PaymentCryptographyKey key, String mode, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        if (modes == null) {
            return;
        }
        if (modes.containsKey("NoRestrictions")) {
            return;
        }
        if (!Boolean.TRUE.equals(modes.get(mode))) {
            throw new AwsException("InvalidKeyUsageException",
                    "the key " + key.getArn() + " does not have the mode of use " + mode
                            + ", which it needs to " + what, 400);
        }
    }

    /**
     * A blob of the request.
     *
     * <p>The data plane carries its blobs as hexadecimal, which is what the reference says it
     * does, and the length of each one is bounded.
     */
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
                return new MacLeg(IsoMac::alg1, material(key));
            }
            if ("ISO9797_ALGORITHM3".equals(algorithm)) {
                requireMode(key, mode, what);
                return new MacLeg(IsoMac::alg3, material(key));
            }
            throw new AwsException("ValidationException",
                    "Algorithm '" + algorithm + "' not supported by this service "
                            + "(ISO9797_ALGORITHM1, ISO9797_ALGORITHM3)", 400);
        }
        if ("DukptIso9797Algorithm1".equals(variant) || "DukptIso9797Algorithm3".equals(variant)) {
            // A DUKPT MAC key is derived from the base derivation key, so that is what the key
            // has to allow.
            requireMode(key, "DeriveKey", "deriving a MAC key");
            JsonNode variantAttributes = attributes.get(variant);
            String ksn = text(variantAttributes, "KeySerialNumber");
            if (!isHex(ksn)) {
                throw new AwsException("ValidationException",
                        variant + ".KeySerialNumber (hex) is required", 400);
            }
            String keyVariant = text(variantAttributes, "DukptKeyVariant");
            if (!keyVariant.isEmpty() && !"REQUEST".equals(keyVariant)) {
                throw new AwsException("ValidationException",
                        variant + ".DukptKeyVariant '" + keyVariant
                                + "' not supported by this service (REQUEST)", 400);
            }
            byte[] derived;
            try {
                derived = DukptCrypto.macKey(key.getKeyMaterialHex(), ksn);
            } catch (RuntimeException e) {
                throw new AwsException("ValidationException",
                        "DUKPT derivation failed: " + e.getMessage(), 400);
            }
            return new MacLeg("DukptIso9797Algorithm1".equals(variant)
                    ? IsoMac::alg1 : IsoMac::alg3, derived);
        }
        throw new AwsException("ValidationException",
                "MacAttributes." + variant + " not supported by this service", 400);
    }

    /** The MAC a request asks for, and the key it is computed under. */
    private static final class MacLeg {
        final MacFunction mac;
        final byte[] keyBytes;

        MacLeg(MacFunction mac, byte[] keyBytes) {
            this.mac = mac;
            this.keyBytes = keyBytes;
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

    /** Whether the text is hexadecimal. */
    private static boolean isHex(String value) {
        if (value == null || value.isEmpty() || value.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] blob(JsonNode request, String member, int shortestBytes, int longestBytes) {
        JsonNode declared = request.get(member);
        if (declared == null || declared.isNull()) {
            throw new AwsException("ValidationException", member + " is required", 400);
        }
        if (!declared.isTextual()) {
            throw new AwsException("ValidationException", member + " must be a hexadecimal string", 400);
        }
        String hex = declared.textValue();
        if (hex.length() % 2 != 0) {
            throw new AwsException("ValidationException", member + " must have an even number of characters", 400);
        }
        int length = hex.length() / 2;
        if (length < shortestBytes || length > longestBytes) {
            throw new AwsException("ValidationException",
                    member + " must be between " + shortestBytes + " and " + longestBytes + " bytes", 400);
        }
        try {
            return PaymentCryptographyCrypto.fromHex(hex);
        } catch (RuntimeException e) {
            throw new AwsException("ValidationException", member + " is not hexadecimal", 400);
        }
    }

    /** The length of the MAC the caller asks for, which the service computes. */
    private static int macLength(JsonNode request) {
        int length = request.path("MacLength").asInt(LONGEST_MAC);
        if (!isMacLength(length)) {
            throw new AwsException("ValidationException",
                    "MacLength must be between " + SHORTEST_MAC + " and " + LONGEST_MAC, 400);
        }
        return length;
    }

    private static boolean isMacLength(int length) {
        return length >= SHORTEST_MAC && length <= LONGEST_MAC;
    }

    /** A blob as the protocol carries it: hexadecimal. */
    private static String encode(byte[] bytes) {
        return PaymentCryptographyCrypto.toHex(bytes).toUpperCase(Locale.ROOT);
    }
}
