package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
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
    private static final int LONGEST_MAC = 16;

    /** The shortest plaintext the service encrypts, in bytes. */
    private static final int SHORTEST_PLAINTEXT = 8;

    /** The longest plaintext the service encrypts, in bytes. */
    private static final int LONGEST_PLAINTEXT = 2032;

    private final PaymentCryptographyService service;
    private final SecureRandom random;

    @Inject
    public PaymentCryptographyDataPlaneHandler(PaymentCryptographyService service) {
        this(service, new SecureRandom());
    }

    PaymentCryptographyDataPlaneHandler(PaymentCryptographyService service, SecureRandom random) {
        this.service = service;
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
        requireMode(key, "Generate", "generate a MAC with");
        byte[] message = blob(request, "MessageData", 1, 2048);
        int length = macLength(request);
        byte[] mac = PaymentCryptographyCrypto.cmac(material(key), message);
        byte[] answer = new byte[length];
        System.arraycopy(mac, 0, answer, 0, length);
        return answer(key, Map.of("Mac", encode(answer)));
    }

    /**
     * The verdict of a MAC over a message.
     */
    public Map<String, Object> verifyMac(JsonNode request, String region) {
        PaymentCryptographyKey key = requiredKey(request, region, "KeyIdentifier");
        requireMode(key, "Verify", "verify a MAC with");
        byte[] message = blob(request, "MessageData", 1, 2048);
        byte[] mac = blob(request, "Mac", 2, 64);
        int length = request.has("MacLength") ? request.path("MacLength").asInt(mac.length) : mac.length;
        if (!isMacLength(length)) {
            throw new AwsException("ValidationException",
                    "MacLength must be between " + SHORTEST_MAC + " and " + LONGEST_MAC, 400);
        }
        byte[] expected = PaymentCryptographyCrypto.cmac(material(key), message);
        byte[] cut = new byte[length];
        System.arraycopy(expected, 0, cut, 0, Math.min(length, expected.length));
        if (mac.length != length || !java.security.MessageDigest.isEqual(cut, mac)) {
            // The service does not answer that a MAC is not valid: it fails, because a caller
            // that asks whether a MAC is good gets an error rather than a false.
            throw new AwsException("VerificationFailedException",
                    "This request failed verification.", 400);
        }
        return answer(key, Map.of());
    }

    /**
     * The PIN data of a card account.
     *
     * <p>The PIN block formats are the schemes' own, so the operation is refused rather than
     * answered with a block this service made up.
     */
    public Map<String, Object> generatePinData(JsonNode request, String region) {
        refused("GeneratePinData");
        return Map.of();
    }

    /** The PIN data of a card account, verified against a generation key. */
    public Map<String, Object> verifyPinData(JsonNode request, String region) {
        refused("VerifyPinData");
        return Map.of();
    }

    /** The PIN data of one scheme translated into another. */
    public Map<String, Object> translatePinData(JsonNode request, String region) {
        refused("TranslatePinData");
        return Map.of();
    }

    /** The card validation value of a card account. */
    public Map<String, Object> generateCardValidationData(JsonNode request, String region) {
        refused("GenerateCardValidationData");
        return Map.of();
    }

    /** The card validation value of a card account, verified. */
    public Map<String, Object> verifyCardValidationData(JsonNode request, String region) {
        refused("VerifyCardValidationData");
        return Map.of();
    }

    /** The cryptogram of an authorization request, verified. */
    public Map<String, Object> verifyAuthRequestCryptogram(JsonNode request, String region) {
        refused("VerifyAuthRequestCryptogram");
        return Map.of();
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
        return PaymentCryptographyCrypto.toHex(bytes).toUpperCase(java.util.Locale.ROOT);
    }
}
