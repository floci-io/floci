package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.CvvCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The card validation operations.
 *
 * <p>The card verification value is the value the issuer holds and the acquirer asks for: the
 * three digits of the CVV, computed under the card verification key from the account number,
 * the expiry and the service code.
 *
 * <p>The dynamic variants compute the value from the transaction instead: the dynamic card
 * verification value from the account number and the transaction counters, and the dynamic card
 * verification code from the track data. They need a key of their own usage, and one that may
 * derive.
 */
@ApplicationScoped
public class PaymentCryptographyCardValidationOperations {

    /** The service code of the second card verification value, which the service fixes. */
    private static final String CVV2_SERVICE_CODE = "000";

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyCardValidationOperations(PaymentCryptographyService service) {
        this.service = service;
    }

    /**
     * The card validation attributes of a request.
     */
    private static final class CardAttrs {
        /** The static variants: the expiry and the service code. */
        String expiry;
        String serviceCode;
        /** The dynamic variants. */
        String panSequenceNumber;
        String applicationTransactionCounter;
        String trackData;
        String unpredictableNumber;
        /** Which variant this is, for the key rules and the cipher. */
        String variant;
    }

    // ---- GenerateCardValidationData ------------------------------------------

    /**
     * The card validation value of a card account.
     */
    Map<String, Object> generate(JsonNode request, String region) {
        PaymentCryptographyDataRequestShapes.check("GenerateCardValidationData", request);
        String keyIdentifier = text(request, "KeyIdentifier");
        String pan = text(request, "PrimaryAccountNumber");
        Integer validationDataLength = request.hasNonNull("ValidationDataLength")
                ? request.path("ValidationDataLength").asInt()
                : null;
        if (validationDataLength != null && validationDataLength != 3) {
            throw validation("ValidationDataLength other than 3 is not supported by this service");
        }
        CardAttrs attrs = resolveCardAttrs(request.get("GenerationAttributes"));

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        requireKeyForVariant(key, attrs.variant, "Generate");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ValidationData", compute(pan, attrs, key));
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        return out;
    }

    // ---- VerifyCardValidationData --------------------------------------------

    /**
     * The card validation value of a card account, verified.
     */
    Map<String, Object> verify(JsonNode request, String region) {
        PaymentCryptographyDataRequestShapes.check("VerifyCardValidationData", request);
        String keyIdentifier = text(request, "KeyIdentifier");
        String pan = text(request, "PrimaryAccountNumber");
        String validationData = text(request, "ValidationData");
        if (!validationData.matches("[0-9]{3}")) {
            throw validation("ValidationData must be three digits");
        }
        CardAttrs attrs = resolveCardAttrs(request.get("VerificationAttributes"));

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        requireKeyForVariant(key, attrs.variant, "Verify");

        String computed;
        try {
            computed = compute(pan, attrs, key);
        } catch (RuntimeException e) {
            throw validation("Unable to compute the card validation value: " + e.getMessage());
        }
        if (!computed.equals(validationData)) {
            throw verificationFailed("CARD_VALIDATION_DATA_VERIFICATION_FAILED");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        return out;
    }

    // ---- the parts -----------------------------------------------------------

    /** The value for the variant, under the given key. */
    private static String compute(String pan, CardAttrs attrs, PaymentCryptographyKey key) {
        if ("DynamicCardVerificationValue".equals(attrs.variant)) {
            return CvvCrypto.computeDynamicValue(pan, attrs.expiry, attrs.serviceCode,
                    attrs.applicationTransactionCounter, key.getKeyMaterialHex());
        }
        if ("DynamicCardVerificationCode".equals(attrs.variant)) {
            return CvvCrypto.computeDynamicCode(attrs.trackData,
                    attrs.applicationTransactionCounter, attrs.unpredictableNumber,
                    key.getKeyMaterialHex());
        }
        return CvvCrypto.computeCvv(pan, attrs.expiry, attrs.serviceCode, key.getKeyMaterialHex());
    }

    /**
     * The key the variant needs, checked against the service's rules.
     *
     * <p>Getting this wrong is the usual reason a call against the real service is rejected, so
     * it is checked here rather than left to the cipher.
     */
    private static void requireKeyForVariant(PaymentCryptographyKey key, String variant, String mode) {
        String usage = key.getKeyUsage();
        String algorithm = key.getKeyAlgorithm();
        if ("DynamicCardVerificationValue".equals(variant)) {
            requireUsage(key, usage, "TR31_E6_EMV_MKEY_OTHER", variant);
            requireAlgorithm(key, algorithm, "TDES_2KEY", variant);
            requireDerive(key, variant);
            return;
        }
        if ("DynamicCardVerificationCode".equals(variant)) {
            requireUsage(key, usage, "TR31_E4_EMV_MKEY_DYNAMIC_NUMBERS", variant);
            requireAlgorithm(key, algorithm, "TDES_2KEY", variant);
            requireDerive(key, variant);
            return;
        }
        // The static values use the card verification key.
        requireUsage(key, usage, "TR31_C0_CARD_VERIFICATION_KEY", variant);
        requireAlgorithm(key, algorithm, "TDES_2KEY", variant);
        requireMode(key, mode, "Generate".equals(mode)
                ? "generating a card verification value"
                : "verifying a card verification value");
    }

    private static void requireUsage(PaymentCryptographyKey key, String usage, String required,
                                     String variant) {
        if (!required.equals(usage)) {
            throw validation("a " + variant.toLowerCase(Locale.ROOT)
                    + " needs a " + required + " key, but the key is " + usage);
        }
    }

    private static void requireAlgorithm(PaymentCryptographyKey key, String algorithm,
                                         String required, String variant) {
        if (!required.equals(algorithm)) {
            throw validation("a " + variant.toLowerCase(Locale.ROOT)
                    + " needs a " + required + " key, but the key is " + algorithm);
        }
    }

    private static void requireDerive(PaymentCryptographyKey key, String variant) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        boolean derives = modes != null
                && (Boolean.TRUE.equals(modes.get("DeriveKey"))
                        || Boolean.TRUE.equals(modes.get("NoRestrictions")));
        if (!derives) {
            throw validation("a " + variant.toLowerCase(Locale.ROOT)
                    + " needs a key with DeriveKey, but the key does not allow it");
        }
    }

    /**
     * The card validation attributes of a request.
     *
     * <p>The attributes are a union, and exactly one variant of it may be given.
     */
    private static CardAttrs resolveCardAttrs(JsonNode attributes) {
        String variant = soleVariant(attributes);
        if (variant == null) {
            throw validation("attributes must specify exactly one variant");
        }
        switch (variant) {
            case "CardVerificationValue1": {
                JsonNode value = attributes.get(variant);
                String expiry = text(value, "CardExpiryDate");
                String serviceCode = text(value, "ServiceCode");
                CardAttrs out = new CardAttrs();
                out.variant = variant;
                out.expiry = expiry;
                out.serviceCode = serviceCode;
                return out;
            }
            case "CardVerificationValue2": {
                JsonNode value = attributes.get(variant);
                String expiry = text(value, "CardExpiryDate");
                CardAttrs out = new CardAttrs();
                out.variant = variant;
                out.expiry = expiry;
                out.serviceCode = CVV2_SERVICE_CODE;
                return out;
            }
            case "DynamicCardVerificationValue": {
                JsonNode value = attributes.get(variant);
                String expiry = text(value, "CardExpiryDate");
                String serviceCode = text(value, "ServiceCode");
                String psn = text(value, "PanSequenceNumber");
                String atc = text(value, "ApplicationTransactionCounter");
                CardAttrs out = new CardAttrs();
                out.variant = variant;
                out.expiry = expiry;
                out.serviceCode = serviceCode;
                out.panSequenceNumber = psn;
                out.applicationTransactionCounter = atc;
                return out;
            }
            case "DynamicCardVerificationCode": {
                JsonNode value = attributes.get(variant);
                String psn = text(value, "PanSequenceNumber");
                String atc = text(value, "ApplicationTransactionCounter");
                String trackData = text(value, "TrackData");
                String unpredictable = text(value, "UnpredictableNumber");
                CardAttrs out = new CardAttrs();
                out.variant = variant;
                out.panSequenceNumber = psn;
                out.applicationTransactionCounter = atc;
                out.trackData = trackData;
                out.unpredictableNumber = unpredictable;
                return out;
            }
            default:
                throw validation(variant + " not supported by this service "
                        + "(only CardVerificationValue1/CardVerificationValue2"
                        + "/DynamicCardVerificationValue/DynamicCardVerificationCode)");
        }
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

    private PaymentCryptographyKey requiredKey(String identifier, String region) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 400);
        }
        return key;
    }

    /**
     * Checks that the key has the mode of use the operation needs.
     */
    private static void requireMode(PaymentCryptographyKey key, String mode, String what) {
        Map<String, Boolean> modes = key.getKeyModesOfUse();
        if (modes == null) {
            return;
        }
        if (Boolean.TRUE.equals(modes.get("NoRestrictions"))) {
            return;
        }
        if (!Boolean.TRUE.equals(modes.get(mode))) {
            throw new AwsException("InvalidKeyUsageException",
                    "the key " + key.getArn() + " does not have the mode of use " + mode
                            + ", which it needs to " + what, 400);
        }
    }

    /** The text of a request member. */
    private static String text(JsonNode request, String member) {
        if (request == null) {
            return "";
        }
        JsonNode declared = request.get(member);
        return declared == null || declared.isNull() ? "" : declared.asText("");
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException verificationFailed(String reason) {
        return new AwsException("VerificationFailedException",
                "This request failed verification: " + reason, 400);
    }
}
