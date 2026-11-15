package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.EmvCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.soleVariant;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.text;

/**
 * The authorization request cryptograms.
 *
 * <p>The ARQC is the cryptogram of an authorization request, computed under a session key that
 * the transaction's key derivation produces. The session key is derived from the ICC AC master
 * key, which is itself derived from the account number and the sequence number, so the whole
 * chain is one derivation and the cryptogram is the ISO 9797-1 algorithm 3 retail MAC over the
 * transaction data, cut to eight bytes.
 *
 * <p>Three derivations are served. The EMV common session key is the one the EMV common
 * specification gives. The Mastercard derivation is the proprietary one, which also
 * diversifies with the unpredictable number. The Visa derivation derives no session key at
 * all: the transaction data is enciphered under the ICC master key, which is the Visa Smart
 * Debit and Credit method.
 *
 * <p>Two derivations are refused rather than approximated. The American Express one is
 * proprietary and there is nothing to verify it against, and a plausible wrong cryptogram is
 * worse than an error. The EMV 2000 one is the EMV 4.1 tree derivation, which is over the
 * master key and the ATC, and this service does not implement it.
 */
@ApplicationScoped
public class PaymentCryptographyAuthCryptogramOperations {

    /** The hexadecimal the data plane's blobs are written in. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** The session key derivations. */
    private enum Scheme {
        /** The EMV common session key, derived from the application transaction counter. */
        EMV_COMMON,
        /** The Mastercard proprietary derivation, which also diversifies with the number. */
        MASTERCARD,
        /** No session key: the transaction data is enciphered under the ICC master key. */
        VSDC
    }

    /** The variants that use the EMV common session key. */
    private static final Set<String> COMMON_SK =
            new LinkedHashSet<>(Arrays.asList("EmvCommon"));

    /** The variants that derive nothing and encipher under the ICC master key. */
    private static final Set<String> DIRECT_SK = new LinkedHashSet<>(Arrays.asList("Visa"));

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyAuthCryptogramOperations(PaymentCryptographyService service) {
        this.service = service;
    }

    /** The resolved session-key derivation for a request. */
    private static final class SessionKey {
        final String pan;
        final String psn;
        final String atc;
        final Scheme scheme;
        final String unpredictableNumber;
        final boolean optionB;

        SessionKey(String pan, String psn, String atc, Scheme scheme,
                   String unpredictableNumber, boolean optionB) {
            this.pan = pan;
            this.psn = psn;
            this.atc = atc;
            this.scheme = scheme;
            this.unpredictableNumber = unpredictableNumber;
            this.optionB = optionB;
        }
    }

    // ---- GenerateAuthRequestCryptogram ---------------------------------------

    /**
     * The cryptogram of an authorization request.
     */
    Map<String, Object> generate(JsonNode request, String region) {
        PaymentCryptographyDataRequestShapes.check("GenerateAuthRequestCryptogram", request);
        String keyIdentifier = text(request, "KeyIdentifier");
        String transactionData = PaymentCryptographyAttributes.blocks("TransactionData",
                PaymentCryptographyAttributes.hex("TransactionData",
                        text(request, "TransactionData")));
        SessionKey sk = resolveDerivation(request);

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        PaymentCryptographyKeyRules.requireUsage(key,
                List.of("TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS"), "computing a cryptogram");
        PaymentCryptographyKeyRules.requireAlgorithm(key, "TDES_2KEY", "computing a cryptogram");
        PaymentCryptographyKeyRules.requireDerive(key, "deriving a session key");
        byte[] material = HEX.parseHex(PaymentCryptographyKeyRules.materialHex(key));

        EmvCrypto.Arqc computed = computeArqc(sk, material, transactionData);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        out.put("AuthRequestCryptogram", HEX.formatHex(computed.arqc));
        return out;
    }

    // ---- VerifyAuthRequestCryptogram -----------------------------------------

    /**
     * The cryptogram of an authorization request, verified, and the response value that goes
     * with it.
     */
    Map<String, Object> verify(JsonNode request, String region) {
        PaymentCryptographyDataRequestShapes.check("VerifyAuthRequestCryptogram", request);
        String keyIdentifier = text(request, "KeyIdentifier");
        String arqc = text(request, "AuthRequestCryptogram");
        String transactionData = PaymentCryptographyAttributes.blocks("TransactionData",
                PaymentCryptographyAttributes.hex("TransactionData",
                        text(request, "TransactionData")));
        SessionKey sk = resolveDerivation(request);

        // The response attributes are optional, and the answer carries the response value only
        // when the caller asked for one.
        String arpcMethod = null;
        String arc = "0000";
        String csu = null;
        String pad = "";
        JsonNode responseAttributes = request.get("AuthResponseAttributes");
        if (responseAttributes != null) {
            String variant = soleVariant(responseAttributes);
            if (variant == null) {
                throw validation("AuthResponseAttributes must specify exactly one variant");
            }
            if ("ArpcMethod1".equals(variant)) {
                arpcMethod = "1";
                JsonNode method = responseAttributes.get(variant);
                arc = text(method, "AuthResponseCode");
            } else if ("ArpcMethod2".equals(variant)) {
                arpcMethod = "2";
                JsonNode method = responseAttributes.get(variant);
                csu = text(method, "CardStatusUpdate");
                pad = PaymentCryptographyAttributes.hex(
                        "AuthResponseAttributes.ArpcMethod2.ProprietaryAuthenticationData",
                        text(method, "ProprietaryAuthenticationData"));
                if (sk.scheme == Scheme.MASTERCARD) {
                    throw validation("ARPC method 2 is not used in practice with the Mastercard "
                            + "session key derivation (method 1 only)");
                }
                if (sk.scheme == Scheme.VSDC) {
                    throw validation("ARPC method 2 is not used in practice with the Visa session "
                            + "key derivation (method 1 only)");
                }
            } else {
                throw validation("AuthResponseAttributes." + variant
                        + " not supported by this service (ArpcMethod1, ArpcMethod2)");
            }
        }

        PaymentCryptographyKey key = requiredKey(keyIdentifier, region);
        PaymentCryptographyKeyRules.requireUsage(key,
                List.of("TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS"), "computing a cryptogram");
        PaymentCryptographyKeyRules.requireAlgorithm(key, "TDES_2KEY", "computing a cryptogram");
        PaymentCryptographyKeyRules.requireDerive(key, "deriving a session key");
        byte[] material = HEX.parseHex(PaymentCryptographyKeyRules.materialHex(key));

        EmvCrypto.Arqc computed = computeArqc(sk, material, transactionData);
        String expected = HEX.formatHex(computed.arqc);
        if (!expected.equalsIgnoreCase(arqc)) {
            throw verificationFailed("the authorization request cryptogram did not verify",
                    "INVALID_AUTH_REQUEST_CRYPTOGRAM");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("KeyArn", key.getArn());
        out.put("KeyCheckValue", key.getKeyCheckValue());
        // The response key is not the session key. M/Chip 4 v1.1 section 7-4 defines
        // SKARPC := MKAC for the proprietary method, and only the EMV common session key method
        // sets SKARPC := SKAC. Using the session key here would encipher under a key no M/Chip
        // terminal would use.
        byte[] arpcKey = sk.scheme == Scheme.EMV_COMMON
                ? computed.sessionKey
                : masterKeyOf(sk, HEX.parseHex(key.getKeyMaterialHex()));
        if ("1".equals(arpcMethod)) {
            out.put("AuthResponseValue", HEX.formatHex(EmvCrypto.computeArpcMethod1(
                    arpcKey, HEX.parseHex(arqc), arc)));
        } else if ("2".equals(arpcMethod)) {
            // The second method is made from the card status update and the proprietary
            // data; the response code belongs to the first method.
            out.put("AuthResponseValue", HEX.formatHex(EmvCrypto.computeArpcMethod2(
                    arpcKey, HEX.parseHex(arqc), csu, pad)));
        }
        return out;
    }

    // ---- the parts -----------------------------------------------------------

    /**
     * The ARQC for a resolved scheme, and the session key it was computed under.
     *
     * <p>The session key is derived per scheme, so this is the one place the choice is made; the
     * cryptogram itself is always the ISO 9797-1 algorithm 3 retail MAC, cut to eight bytes.
     */
    private static EmvCrypto.Arqc computeArqc(SessionKey sk, byte[] imk, String transactionData) {
        byte[] mk = masterKeyOf(sk, imk);
        byte[] atc = HEX.parseHex(sk.atc);
        byte[] sessionKey;
        switch (sk.scheme) {
            case MASTERCARD:
                sessionKey = EmvCrypto.deriveSessionKeyMastercard(
                        mk, atc, HEX.parseHex(sk.unpredictableNumber));
                break;
            case EMV_COMMON:
                sessionKey = EmvCrypto.deriveSessionKey(mk, atc);
                break;
            case VSDC:
            default:
                // No session key: the transaction data is enciphered under the ICC master key,
                // which is the Visa Smart Debit method.
                sessionKey = mk;
                break;
        }
        byte[] mac = EmvCrypto.retailMac(sessionKey, HEX.parseHex(transactionData),
                EmvCrypto.Padding.NONE);
        return new EmvCrypto.Arqc(Arrays.copyOf(mac, 8), sessionKey);
    }

    /**
     * The ICC AC master key for the resolved derivation, which is also the M/Chip ARPC key.
     *
     * <p>A derivation the request cannot be read for is refused with the error the model
     * names, rather than answered with a failure the caller cannot catch.
     */
    private static byte[] masterKeyOf(SessionKey sk, byte[] imk) {
        try {
            return sk.optionB
                    ? EmvCrypto.deriveIccMasterKeyOptionB(imk, sk.pan, sk.psn)
                    : EmvCrypto.deriveIccMasterKey(imk, sk.pan, sk.psn);
        } catch (IllegalArgumentException e) {
            throw validation(e.getMessage());
        }
    }

    /**
     * Resolves the master-key derivation mode and the session-key derivation attributes, which
     * both cryptogram operations share.
     */
    private static SessionKey resolveDerivation(JsonNode request) {
        boolean optionB = "EMV_OPTION_B".equals(text(request, "MajorKeyDerivationMode"));

        // The shape check settles the derivation attributes, so they are there, and the
        // attributes must name exactly one variant.
        String variant = soleVariant(request.get("SessionKeyDerivationAttributes"));
        if (variant == null) {
            throw validation("SessionKeyDerivationAttributes must specify exactly one variant");
        }
        // Amex is refused rather than approximated: the AEIPS session key derivation is
        // proprietary and there is nothing to verify a derivation against, and a plausible wrong
        // cryptogram is the one outcome worse than an error.
        if ("Amex".equals(variant)) {
            throw validation("SessionKeyDerivationAttributes.Amex requires the AEIPS session key "
                    + "derivation, which this service does not implement. It is not approximated.");
        }
        if ("Emv2000".equals(variant)) {
            throw validation("SessionKeyDerivationAttributes.Emv2000 requires the EMV 4.1 tree "
                    + "derivation, which this service does not implement. It is not approximated.");
        }
        boolean common = COMMON_SK.contains(variant);
        boolean mastercard = "Mastercard".equals(variant);
        if (!common && !mastercard && !DIRECT_SK.contains(variant)) {
            throw validation("SessionKeyDerivationAttributes." + variant
                    + " is not a recognized variant");
        }
        JsonNode attrs = request.get("SessionKeyDerivationAttributes").get(variant);
        String pan = text(attrs, "PrimaryAccountNumber");
        String psn = text(attrs, "PanSequenceNumber");
        String atc = text(attrs, "ApplicationTransactionCounter");
        if (mastercard && optionB) {
            // The reference implementation's own constraint: master key derivation option B is
            // not used in practice with this scheme.
            throw validation("MasterKeyDerivationMode EMV_OPTION_B is not used in practice with "
                    + "the Mastercard session key derivation (EMV_OPTION_A only)");
        }
        String unpredictableNumber = null;
        if (mastercard) {
            // Taken from the request, never inferred: the transaction data is an opaque
            // concatenated block whose layout the caller chooses, so a fixed offset would be
            // right for one CDOL and silently wrong for every other.
            unpredictableNumber = text(attrs, "UnpredictableNumber");
        }
        Scheme scheme = mastercard ? Scheme.MASTERCARD
                : (common ? Scheme.EMV_COMMON : Scheme.VSDC);
        return new SessionKey(pan, psn, atc, scheme, unpredictableNumber, optionB);
    }

    // ---- the helpers ---------------------------------------------------------

    private PaymentCryptographyKey requiredKey(String identifier, String region) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 404);
        }
        PaymentCryptographyKeyRules.requireEnabled(key);
        return key;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException verificationFailed(String message, String reason) {
        return new AwsException("VerificationFailedException", message, 400, Map.of("Reason", reason));
    }
}
