package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.soleVariant;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.text;

/**
 * The AS2805 KEK validation: the ceremony two nodes use to prove they hold the same key.
 *
 * <p>The operation has two forms. The request form is the node that starts the exchange: it
 * makes a random number, enciphers it under a variant of the key it holds, and answers with
 * that enciphered number and the answer it expects the other node to send back. The response
 * form is the node that answers: it deciphers the number it was given, inverts it and
 * enciphers the inverted value, which is the answer the first node compares.
 *
 * <p>The key is a Triple DES key encryption key, the one AS2805 calls the terminal major
 * key. The reference does not name the usage the key must have for this operation, so the
 * one the service's own AS2805 import gives that key is the one it asks for, and AS2805's
 * cryptography is all TDES, so a key of another family is refused.
 */
@ApplicationScoped
public class PaymentCryptographyKekValidationOperations {

    /** The usages a key may have to be the key of a KEK validation. */
    private static final List<String> KEK_USAGES = List.of("TR31_K0_KEY_ENCRYPTION_KEY");

    /** The length of the random number when the request does not name one. */
    private static final int DEFAULT_RANDOM_LENGTH = 16;

    /** The lengths the request may name, in bytes. */
    private static final Map<String, Integer> RANDOM_LENGTHS = Map.of(
            "BYTES_8", 8,
            "BYTES_16", 16,
            "BYTES_24", 24);

    /** The hexadecimal the data plane carries its blobs in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private final SecureRandom random;

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyKekValidationOperations(PaymentCryptographyService service) {
        this(service, new SecureRandom());
    }

    PaymentCryptographyKekValidationOperations(PaymentCryptographyService service, SecureRandom random) {
        this.service = service;
        this.random = random;
    }

    /**
     * The KEK validation of a key encryption key.
     */
    Map<String, Object> generate(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("GenerateAs2805KekValidation", request);
        PaymentCryptographyKekValidation.Variant variant =
                variant(text(request, "RandomKeySendVariantMask"));
        JsonNode type = request.get("KekValidationType");
        String form = soleVariant(type);
        if (form == null) {
            throw validation("KekValidationType must specify exactly one variant");
        }
        if (!"KekValidationRequest".equals(form) && !"KekValidationResponse".equals(form)) {
            throw validation("KekValidationType." + form + " not supported by this service "
                    + "(KekValidationRequest, KekValidationResponse)");
        }

        PaymentCryptographyKey key = requiredKey(text(request, "KeyIdentifier"), region);
        PaymentCryptographyKeyAlgorithm algorithm = algorithmOf(key);
        byte[] material = PaymentCryptographyKeyRules.material(key);
        byte[] variantRn = PaymentCryptographyKekValidation.variantKey(
                material, variant.sendMask());
        byte[] variantNotRn = PaymentCryptographyKekValidation.variantKey(
                material, variant.receiveMask());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", PaymentCryptographyCrypto.checkValue(material, algorithm));

        byte[] send;
        byte[] receive;
        if ("KekValidationRequest".equals(form)) {
            // The node that starts the exchange enciphers under the key.
            PaymentCryptographyKeyRules.requireMode(key, "Encrypt",
                    "enciphering a random number for a KEK validation");
            JsonNode attributes = type.get("KekValidationRequest");
            requireAlgorithm(key, algorithm, text(attributes, "DeriveKeyAlgorithm"));
            byte[] rn = PaymentCryptographyKekValidation.oddParity(
                    randomBytes(randomLength(text(attributes, "RandomKeyMaxLength"))));
            send = PaymentCryptographyKekValidation.encrypt(rn, variantRn);
            receive = PaymentCryptographyKekValidation.encrypt(
                    PaymentCryptographyKekValidation.invert(rn), variantNotRn);
        } else {
            // The node that answers deciphers the number and enciphers the inverted one.
            PaymentCryptographyKeyRules.requireMode(key, "Decrypt",
                    "deciphering a random number for a KEK validation");
            PaymentCryptographyKeyRules.requireMode(key, "Encrypt",
                    "enciphering the inverted number for a KEK validation");
            send = HEX.parseHex(text(type.get("KekValidationResponse"), "RandomKeySend"));
            byte[] rn = PaymentCryptographyKekValidation.decrypt(send, variantRn);
            receive = PaymentCryptographyKekValidation.encrypt(
                    PaymentCryptographyKekValidation.invert(rn), variantNotRn);
        }
        out.put("RandomKeySend", HEX.formatHex(send));
        out.put("RandomKeyReceive", HEX.formatHex(receive));
        return out;
    }

    // ---- the parts -----------------------------------------------------------

    /**
     * The variant mask the request names.
     *
     * <p>The mask is one of the model's two, and the shape check settles it before the
     * operation reads it, so a name that is not one of them is a fault of the service rather
     * than of the caller.
     */
    private static PaymentCryptographyKekValidation.Variant variant(String name) {
        return PaymentCryptographyKekValidation.Variant.from(name);
    }

    /**
     * The length of the random number the request names.
     *
     * <p>The length is one of the model's three, and the shape check settles it before the
     * operation reads it. A request that names none gets the length the reference's own
     * example answers with.
     */
    private static int randomLength(String name) {
        if (name.isEmpty()) {
            return DEFAULT_RANDOM_LENGTH;
        }
        Integer length = RANDOM_LENGTHS.get(name);
        if (length == null) {
            throw new IllegalStateException("the random key length " + name + " is not one of them");
        }
        return length;
    }

    /**
     * The algorithm of the key, which is the algorithm the variant is used with.
     *
     * <p>AS2805's cryptography is all TDES: the ceremony runs over Single DES blocks and
     * the key variants are DES key variants, so a key of another family is refused.
     */
    private static PaymentCryptographyKeyAlgorithm algorithmOf(PaymentCryptographyKey key) {
        return PaymentCryptographyKeyRules.requireTdes(key.getKeyAlgorithm(),
                "key " + key.getArn());
    }

    /**
     * The algorithm the request says the derived key is of, which is the key's own.
     *
     * <p>The derived key is the variant of the key the request names, so it is of the same
     * algorithm and the same length. A request that names another one is a request for a
     * key the service does not hold.
     */
    private static void requireAlgorithm(PaymentCryptographyKey key,
                                        PaymentCryptographyKeyAlgorithm algorithm,
                                        String declared) {
        if (declared.isEmpty()) {
            return;
        }
        if (!declared.equals(algorithm.name())) {
            throw validation("DeriveKeyAlgorithm '" + declared + "' is not the algorithm of the "
                    + "key " + key.getArn() + ", which is " + algorithm.name());
        }
    }

    /**
     * The random number, of the length the request names.
     */
    private byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        random.nextBytes(out);
        return out;
    }

    private PaymentCryptographyKey requiredKey(String identifier, String region) {
        PaymentCryptographyKey key =
                PaymentCryptographyKeyRules.requiredKey(service, identifier, region);
        PaymentCryptographyKeyRules.requireUsage(key, KEK_USAGES, "validating a key encryption key");
        return key;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
