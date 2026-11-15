package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.CvvCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.soleVariant;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.text;

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

    /** The length of the value when the request does not name one. */
    private static final int DEFAULT_VALIDATION_DATA_LENGTH = 3;

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
        int length = validationDataLength == null ? DEFAULT_VALIDATION_DATA_LENGTH
                : validationDataLength;
        CardAttrs attrs = resolveCardAttrs(request.get("GenerationAttributes"));

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        requireKeyForVariant(key, attrs.variant, "Generate");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ValidationData", computeValue(pan, attrs, key, length));
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
        int length = validationData.length();
        CardAttrs attrs = resolveCardAttrs(request.get("VerificationAttributes"));

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        requireKeyForVariant(key, attrs.variant, "Verify");

        String computed = computeValue(pan, attrs, key, length);
        if (!computed.equals(validationData)) {
            throw verificationFailed("the validation data did not verify",
                    "INVALID_VALIDATION_DATA");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        return out;
    }

    // ---- the parts -----------------------------------------------------------

    /**
     * The value for the variant, under the given key.
     *
     * <p>A request the derivation cannot read is refused with the error the model names,
     * rather than answered with a failure the caller cannot catch.
     */
    private static String computeValue(String pan, CardAttrs attrs, PaymentCryptographyKey key,
                                       int length) {
        try {
            return compute(pan, attrs, key, length);
        } catch (RuntimeException e) {
            throw validation("Unable to compute the card validation value: " + e.getMessage());
        }
    }

    /** The value for the variant, under the given key. */
    private static String compute(String pan, CardAttrs attrs, PaymentCryptographyKey key,
                                 int length) {
        if ("DynamicCardVerificationValue".equals(attrs.variant)) {
            return CvvCrypto.computeDynamicValue(pan, attrs.expiry, attrs.serviceCode,
                    attrs.applicationTransactionCounter,
                    PaymentCryptographyKeyRules.materialHex(key),
                    attrs.panSequenceNumber, length);
        }
        return CvvCrypto.computeCvv(pan, attrs.expiry, attrs.serviceCode,
                PaymentCryptographyKeyRules.materialHex(key), length);
    }

    /**
     * The key the variant needs, checked against the service's rules.
     *
     * <p>Getting this wrong is the usual reason a call against the real service is rejected, so
     * it is checked here rather than left to the cipher.
     */
    private static void requireKeyForVariant(PaymentCryptographyKey key, String variant, String mode) {
        if ("DynamicCardVerificationValue".equals(variant)) {
            PaymentCryptographyKeyRules.requireUsage(key,
                    List.of("TR31_E6_EMV_MKEY_OTHER"), "deriving a dynamic card verification value");
            PaymentCryptographyKeyRules.requireAlgorithm(key, "TDES_2KEY",
                    "deriving a dynamic card verification value");
            PaymentCryptographyKeyRules.requireDerive(key, "deriving a dynamic card verification value");
            return;
        }
        // The static values use the card verification key.
        PaymentCryptographyKeyRules.requireUsage(key,
                List.of("TR31_C0_CARD_VERIFICATION_KEY"), "computing a card verification value");
        PaymentCryptographyKeyRules.requireAlgorithm(key, "TDES_2KEY",
                "computing a card verification value");
        PaymentCryptographyKeyRules.requireMode(key, mode, "Generate".equals(mode)
                ? "generating a card verification value"
                : "verifying a card verification value");
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
                String atc = PaymentCryptographyAttributes.hex(
                        variant + ".ApplicationTransactionCounter",
                        text(value, "ApplicationTransactionCounter"));
                CardAttrs out = new CardAttrs();
                out.variant = variant;
                out.expiry = expiry;
                out.serviceCode = serviceCode;
                out.panSequenceNumber = psn;
                out.applicationTransactionCounter = atc;
                return out;
            }
            case "DynamicCardVerificationCode": {
                // Refused rather than approximated: the dynamic code is a Mastercard
                // proprietary construction, and there is nothing published to check a
                // derivation against.
                throw validation("the dynamic card verification code is not implemented: it is a "
                        + "Mastercard proprietary construction and it is not approximated");
            }
            default:
                throw validation(variant + " not supported by this service "
                        + "(only CardVerificationValue1/CardVerificationValue2"
                        + "/DynamicCardVerificationValue/DynamicCardVerificationCode)");
        }
    }

    private PaymentCryptographyKey requiredKey(String identifier, String region) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 404);
        }
        PaymentCryptographyKeyRules.requireEnabled(key);
        return key;
    }

    /**
     * The error a card request is refused with.
     */
    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException verificationFailed(String message, String reason) {
        return new AwsException("VerificationFailedException", message, 400, Map.of("Reason", reason));
    }
}
