package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.paymentcryptography.crypto.DukptCrypto;
import io.floci.aws.services.paymentcryptography.crypto.PinBlockFormat;
import io.floci.aws.services.paymentcryptography.crypto.PinCrypto;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.soleVariant;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyAttributes.text;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyKeyRules.materialHex;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyKeyRules.requireEnabled;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyKeyRules.requireFamily;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyKeyRules.requireMode;
import static io.floci.aws.services.paymentcryptography.PaymentCryptographyKeyRules.requireUsage;

/**
 * The PIN operations, over the Visa PVV path.
 *
 * <p>VerifyPinData deciphers the inbound PIN block under the PEK, recovers the PIN, recomputes
 * the PVV under the PVK and compares. GeneratePinData is the same arithmetic with a fresh
 * random PIN. TranslatePinData re-enciphers a block from one PEK to another, which is the
 * core acquiring operation and the mechanism behind a PEK rotation.
 *
 * <p>PEK encryption: the eight-byte ISO 0 block is enciphered as a single TDES block, no IV
 * and no padding. ISO format 4 is the exception: it is a sixteen-byte block protected by AES,
 * and it binds the PIN to the whole PAN rather than to the twelve-digit account field. The
 * format decides the cipher, so a format and a key from different families are refused
 * together rather than producing a garbled PIN.
 *
 * <p>The IBM 3624 variants, the AS2805 translation and the EMV PIN change MAC are not made.
 * A request that asks for one of them is refused with the error that says so, rather than
 * answered with something of another kind.
 */
@ApplicationScoped
public class PaymentCryptographyPinOperations {

    /** The PIN length the Visa path works over: the verification value is over four. */
    private static final int VISA_PIN_LENGTH = 4;

    /** The usages a key may have for the encryption of a PIN block. */
    private static final List<String> PIN_ENCRYPTION_USAGES = List.of(
            "TR31_P0_PIN_ENCRYPTION_KEY");

    /** The usages a key may have for the Visa PIN verification value. */
    private static final List<String> VISA_VERIFICATION_USAGES = List.of(
            "TR31_V2_VISA_PIN_VERIFICATION_KEY");

    /** The usages a key may have for the derivation a DUKPT PIN key is made by. */
    private static final List<String> DERIVATION_USAGES = List.of(
            "TR31_B0_BASE_DERIVATION_KEY");

    /** The source of the random fill of the format 3 blocks and the format 4 pads. */
    private final SecureRandom random;

    private final PaymentCryptographyService service;

    @Inject
    public PaymentCryptographyPinOperations(PaymentCryptographyService service) {
        this(service, new SecureRandom());
    }

    PaymentCryptographyPinOperations(PaymentCryptographyService service, SecureRandom random) {
        this.service = service;
        this.random = random;
    }

    /**
     * A resolved PIN-encryption leg: the key the answer names, and the working key.
     */
    private static final class PinLeg {
        /** The key the answer names. */
        final PaymentCryptographyKey record;
        /** The working key: the one that protects the PIN block. */
        final byte[] keyBytes;
        /** Whether the working key is an AES key. */
        final boolean aes;
        /** How the working key's algorithm is named in an error message. */
        final String algorithmName;

        PinLeg(PaymentCryptographyKey record, byte[] keyBytes, boolean aes, String algorithmName) {
            this.record = record;
            this.keyBytes = keyBytes;
            this.aes = aes;
            this.algorithmName = algorithmName;
        }
    }

    // ---- VerifyPinData -------------------------------------------------------

    /**
     * Verifies a PIN block against the verification value the issuer holds.
     */
    Map<String, Object> verifyPinData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("VerifyPinData", request);
        String encryptionKeyIdentifier = text(request, "EncryptionKeyIdentifier");
        String verificationKeyIdentifier = text(request, "VerificationKeyIdentifier");
        refuseWrappedKey(request, "EncryptionWrappedKey");
        PinBlockFormat format = format(text(request, "PinBlockFormat"));
        String pan = requirePan(text(request, "PrimaryAccountNumber"),
                "PrimaryAccountNumber is required for Visa PVV verification");
        requireVisaPinLength(request);
        requireExactLength(text(request, "EncryptedPinBlock"), format.blockHexLength(),
                "EncryptedPinBlock must be " + format.blockHexLength()
                        + " characters (one " + format.label() + " block)");

        JsonNode verificationAttributes = request.get("VerificationAttributes");
        String variant = soleVariant(verificationAttributes);
        if (variant == null) {
            throw validation("VerificationAttributes must specify exactly one variant");
        }
        if (!"VisaPin".equals(variant)) {
            throw validation("VerificationAttributes." + variant
                    + " not supported by this service (only VisaPin)");
        }
        JsonNode visa = verificationAttributes.get("VisaPin");
        int pvki = pvki(visa);
        String pvv = text(visa, "VerificationValue");

        PinLeg pek = resolvePinLeg(encryptionKeyIdentifier, request.get("DukptAttributes"), region,
                "Decrypt", "decrypting a PIN block");
        requireNoDukptForAes(format, request.get("DukptAttributes"));
        requireKeyMatchesFormat(pek, format, "EncryptionKeyIdentifier");

        PaymentCryptographyKey pvk = requiredKey(verificationKeyIdentifier, region);
        requireMode(pvk, "Verify", "verifying a PIN");
        requireUsage(pvk, VISA_VERIFICATION_USAGES, "verifying a PIN");
        // The Visa PVV is defined over Triple DES, so the key is one.
        requireFamily(pvk, false, "verifying a PIN");

        String pin;
        try {
            pin = PinCrypto.decipherPinBlock(format, text(request, "EncryptedPinBlock"), pan,
                    pek.keyBytes);
        } catch (IllegalArgumentException e) {
            // The block did not decipher into a well-formed PIN field: the key is another one,
            // the account number is another one, or the block is corrupted. The reference
            // answers that with the error that says the block is invalid.
            throw invalidPinBlock();
        }
        // A wrong key, or a wrong KSN, deciphers to garble, and what the block carries is
        // then not a PIN. That is an invalid block rather than a PIN that does not verify.
        if (!isRecoveredPin(pin)) {
            throw invalidPinBlock();
        }
        // The Visa verification value is a four digit number, so a block that deciphers to a
        // longer PIN has no value to be compared with.
        if (pin.length() != VISA_PIN_LENGTH) {
            throw verificationFailed();
        }
        if (!PinCrypto.computePvv(pan, pvki, pin, materialHex(pvk)).equals(pvv)) {
            throw verificationFailed();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("EncryptionKeyArn", pek.record.getArn());
        out.put("EncryptionKeyCheckValue", pek.record.getKeyCheckValue());
        out.put("VerificationKeyArn", pvk.getArn());
        out.put("VerificationKeyCheckValue", pvk.getKeyCheckValue());
        return out;
    }

    // ---- GeneratePinData -----------------------------------------------------

    /**
     * Generates a PIN block and the verification value that goes with it.
     */
    Map<String, Object> generatePinData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("GeneratePinData", request);
        String encryptionKeyIdentifier = text(request, "EncryptionKeyIdentifier");
        String generationKeyIdentifier = text(request, "GenerationKeyIdentifier");
        refuseWrappedKey(request, "EncryptionWrappedKey");
        PinBlockFormat format = format(text(request, "PinBlockFormat"));
        String pan = requirePan(text(request, "PrimaryAccountNumber"),
                "PrimaryAccountNumber is required");

        JsonNode generationAttributes = request.get("GenerationAttributes");
        String variant = soleVariant(generationAttributes);
        if (!"VisaPin".equals(variant) && !"VisaPinVerificationValue".equals(variant)) {
            throw validation("GenerationAttributes." + (variant == null ? "(missing)" : variant)
                    + " not supported (VisaPin, VisaPinVerificationValue)");
        }
        JsonNode visa = generationAttributes == null ? null : generationAttributes.get(variant);
        int pvki = pvki(visa);

        // VisaPinVerificationValue carries the block to verify, so the verification value is
        // computed for a PIN the caller already has rather than a new one.
        String givenBlock = "VisaPinVerificationValue".equals(variant) && visa != null
                ? text(visa, "EncryptedPinBlock")
                : "";
        requireVisaPinLength(request);

        PinLeg pek = resolvePinLeg(encryptionKeyIdentifier, null, region,
                "Encrypt", "encrypting a PIN block");
        PaymentCryptographyKey pvk = requiredKey(generationKeyIdentifier, region);
        requireMode(pvk, "Generate", "generating a PIN verification value");
        requireUsage(pvk, VISA_VERIFICATION_USAGES, "generating a PIN verification value");
        // The Visa PVV is defined over Triple DES, so the key is one.
        requireFamily(pvk, false, "generating a PIN verification value");
        requireKeyMatchesFormat(pek, format, "EncryptionKeyIdentifier");
        if (!givenBlock.isEmpty()) {
            // The caller's own block is decrypted to recover the PIN, so the key has to be
            // allowed to decrypt as well as to encrypt.
            requireMode(pek.record, "Decrypt", "decrypting a PIN block");
        }

        String pin;
        String encryptedPinBlock;
        if (!givenBlock.isEmpty()) {
            // The caller's own block: recover the PIN from it and leave the block as it is.
            requireExactLength(givenBlock, format.blockHexLength(),
                    "EncryptedPinBlock must be " + format.blockHexLength()
                            + " characters (one " + format.label() + " block)");
            try {
                pin = PinCrypto.decipherPinBlock(format, givenBlock, pan, pek.keyBytes);
            } catch (IllegalArgumentException e) {
                // The caller's block did not decipher into a well-formed PIN field under the
                // key it named: the block and the key do not go together.
                throw invalidPinBlock();
            }
            if (!isRecoveredPin(pin)) {
                throw invalidPinBlock();
            }
            encryptedPinBlock = givenBlock;
        } else {
            pin = randomPin(VISA_PIN_LENGTH);
            encryptedPinBlock = PinCrypto.encipherPinBlock(format, pin, pan, pek.keyBytes, random);
        }
        // The Visa verification value is a four digit number, so it is computed over a four
        // digit PIN. A block that carries a longer PIN has no value to be computed, and the
        // computation refuses it, so the length is checked here where the refusal is the
        // caller's to act on.
        if (pin.length() != VISA_PIN_LENGTH) {
            throw validation("the Visa verification value is computed over a four digit PIN, and "
                    + "the PIN block carries " + pin.length() + " digits");
        }
        String pvv = PinCrypto.computePvv(pan, pvki, pin, materialHex(pvk));

        // The model's PinData is a union of the PIN offset and the verification value, and a
        // union carries one of them: this is the verification value.
        Map<String, Object> pinData = new LinkedHashMap<>();
        pinData.put("VerificationValue", pvv);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("EncryptedPinBlock", encryptedPinBlock);
        out.put("PinData", pinData);
        out.put("EncryptionKeyArn", pek.record.getArn());
        out.put("EncryptionKeyCheckValue", pek.record.getKeyCheckValue());
        out.put("GenerationKeyArn", pvk.getArn());
        out.put("GenerationKeyCheckValue", pvk.getKeyCheckValue());
        return out;
    }

    // ---- GenerateMacEmvPinChange ---------------------------------------------

    /**
     * The EMV PIN change MAC.
     *
     * <p>The MAC of an EMV PIN change is taken over the command message with a session key
     * that the EMV secure messaging rules define, and this service does not make that
     * session key. The request is refused with the error that says so, rather than answered
     * with a MAC of another kind.
     *
     * <p>The route answers, so a client that calls the operation learns why it is refused,
     * but the action is not one the service lists as supported.
     */
    Map<String, Object> generateMacEmvPinChange(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("GenerateMacEmvPinChange", request);
        throw validation("the EMV PIN change MAC is not supported by this service");
    }

    // ---- TranslatePinData -----------------------------------------------------

    /**
     * Re-enciphers a PIN block from one PEK to another.
     */
    Map<String, Object> translatePinData(JsonNode request, String region) {
        // The request is checked against the constraints the model states for it.
        PaymentCryptographyDataRequestShapes.check("TranslatePinData", request);
        String incomingKeyIdentifier = text(request, "IncomingKeyIdentifier");
        String outgoingKeyIdentifier = text(request, "OutgoingKeyIdentifier");
        if (request.get("IncomingAs2805Attributes") != null) {
            throw validation("AS2805 translation is not supported by this service");
        }
        refuseWrappedKey(request, "IncomingWrappedKey");
        refuseWrappedKey(request, "OutgoingWrappedKey");

        JsonNode incoming = request.get("IncomingTranslationAttributes");
        JsonNode outgoing = request.get("OutgoingTranslationAttributes");
        String inVariant = soleVariant(incoming);
        String outVariant = soleVariant(outgoing);
        if (inVariant == null) {
            throw validation("IncomingTranslationAttributes must specify exactly one format");
        }
        if (outVariant == null) {
            throw validation("OutgoingTranslationAttributes must specify exactly one format");
        }
        PinBlockFormat inFormat = PinBlockFormat.fromTranslationMember(inVariant);
        PinBlockFormat outFormat = PinBlockFormat.fromTranslationMember(outVariant);
        if (inFormat == null) {
            throw validation("IncomingTranslationAttributes." + inVariant
                    + " not supported (" + translationFormats() + ")");
        }
        if (outFormat == null) {
            throw validation("OutgoingTranslationAttributes." + outVariant
                    + " not supported (" + translationFormats() + ")");
        }

        // The block length follows the incoming format: eight bytes for the TDES formats,
        // sixteen for the AES one.
        requireExactLength(text(request, "EncryptedPinBlock"), inFormat.blockHexLength(),
                "EncryptedPinBlock must be " + inFormat.blockHexLength()
                        + " characters (one " + inFormat.label() + " block)");

        JsonNode inAttrs = incoming.get(inVariant);
        JsonNode outAttrs = outgoing.get(outVariant);
        String inPan = inAttrs == null ? "" : text(inAttrs, "PrimaryAccountNumber");
        String outPan = outAttrs == null ? "" : text(outAttrs, "PrimaryAccountNumber");
        // For PCI compliance the incoming and the outgoing account number are the same one,
        // and a PIN is not translated from one account number to another. The model states
        // that the attributes carry the account number, so the two are read and compared.
        if (!inPan.equals(outPan)) {
            throw validation("the incoming and the outgoing PrimaryAccountNumber must be the "
                    + "same one, and a PIN is not translated from one account number to another");
        }

        PinLeg in = resolvePinLeg(incomingKeyIdentifier, request.get("IncomingDukptAttributes"), region,
                "Decrypt", "decrypting a PIN block");
        PinLeg out = resolvePinLeg(outgoingKeyIdentifier, request.get("OutgoingDukptAttributes"),
                region, "Encrypt", "encrypting a PIN block");
        requireNoDukptForAes(inFormat, request.get("IncomingDukptAttributes"));
        requireNoDukptForAes(outFormat, request.get("OutgoingDukptAttributes"));
        requireKeyMatchesFormat(in, inFormat, "IncomingKeyIdentifier");
        requireKeyMatchesFormat(out, outFormat, "OutgoingKeyIdentifier");

        String pinBlock;
        try {
            String pin = PinCrypto.decipherPinBlock(inFormat, text(request, "EncryptedPinBlock"),
                    inPan, in.keyBytes);
            if (!isRecoveredPin(pin)) {
                // The block deciphered, but what it carries is not a PIN, so it is not a block
                // the service can read.
                throw invalidPinBlock();
            }
            pinBlock = PinCrypto.encipherPinBlock(outFormat, pin, outPan, out.keyBytes, random);
        } catch (IllegalArgumentException e) {
            // The incoming block did not decipher into a well-formed PIN field under the
            // incoming key: the caller's block and key do not go together.
            throw invalidPinBlock();
        }

        Map<String, Object> out2 = new LinkedHashMap<>();
        out2.put("PinBlock", pinBlock);
        out2.put("KeyArn", out.record.getArn());
        out2.put("KeyCheckValue", out.record.getKeyCheckValue());
        return out2;
    }

    // ---- the legs ------------------------------------------------------------

    /**
     * Resolves a PIN-encryption leg.
     *
     * <p>Without DUKPT attributes the identifier names a fixed PEK; with them it names a base
     * derivation key and the working key is the X9.24 DUKPT PIN key for the KSN.
     */
    private PinLeg resolvePinLeg(String identifier, JsonNode dukptAttributes, String region,
                                  String mode, String what) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw notFound(identifier);
        }
        requireEnabled(key);
        if (dukptAttributes == null) {
            requireMode(key, mode, what);
            requireUsage(key, PIN_ENCRYPTION_USAGES, what);
            PaymentCryptographyKeyAlgorithm algorithm = algorithmOf(key);
            return new PinLeg(key, PaymentCryptographyCrypto.fromHex(materialHex(key)), algorithm.aes,
                    algorithm.name());
        }
        // A DUKPT PIN key is derived from the base derivation key, so that is what the key has
        // to allow, and the derivation is over Triple DES, so the key is one.
        requireMode(key, "DeriveKey", "deriving a PIN key");
        requireUsage(key, DERIVATION_USAGES, "deriving a PIN key");
        requireFamily(key, false, "deriving a PIN key");

        // The model's DUKPT attributes come in two shapes. The PIN operations take
        // DukptAttributes, whose derivation type is DukptDerivationType, and the translation
        // takes DukptDerivationAttributes, whose derivation type is DukptKeyDerivationType and
        // whose variant is DukptKeyVariant. The derivation this path makes is the Triple DES
        // one, and the PIN key it derives is the one that both encrypts and decrypts.
        String ksn = text(dukptAttributes, "KeySerialNumber");
        String derivationType = text(dukptAttributes, "DukptDerivationType");
        if (derivationType.isEmpty()) {
            derivationType = text(dukptAttributes, "DukptKeyDerivationType");
        }
        if (!derivationType.isEmpty() && !"TDES_2KEY".equals(derivationType)) {
            throw validation("the DUKPT derivation type '" + derivationType
                    + "' is not supported by this service (TDES_2KEY)");
        }
        String keyVariant = text(dukptAttributes, "DukptKeyVariant");
        if (!keyVariant.isEmpty() && !"BIDIRECTIONAL".equals(keyVariant)) {
            throw validation("the DUKPT key variant '" + keyVariant
                    + "' is not supported by this service (BIDIRECTIONAL)");
        }
        try {
            // A DUKPT PIN key is a TDES key, whatever the base derivation key is.
            return new PinLeg(key, DukptCrypto.pinKey(materialHex(key), ksn), false, "TDES");
        } catch (IllegalArgumentException e) {
            // The derivation refuses what the request names, so the refusal is the caller's to
            // act on.
            throw validation("DUKPT derivation failed: " + e.getMessage());
        }
    }

    /**
     * Refuses DUKPT for a format that needs an AES key.
     *
     * <p>DUKPT derives a TDES PIN key, so it can only ever produce a format 0 or 3 block.
     */
    private static void requireNoDukptForAes(PinBlockFormat format, JsonNode dukptAttributes) {
        if (format.isAes() && dukptAttributes != null) {
            throw validation("DukptAttributes derive a TDES PIN key, but " + format.name()
                    + " PIN blocks require an AES PEK");
        }
    }

    /**
     * Checks that the PIN encryption key suits the PIN block format.
     *
     * <p>Formats 0 and 3 are eight-byte TDES blocks and format 4 is a sixteen-byte AES block. A
     * key from the wrong family cannot produce a block of that shape, so the mismatch is a
     * validation error rather than a garbled PIN.
     */
    private static void requireKeyMatchesFormat(PinLeg leg, PinBlockFormat format, String label) {
        if (leg.aes != format.isAes()) {
            throw validation(label + " must resolve to an "
                    + (format.isAes() ? "AES" : "TDES") + " key for " + format.name()
                    + " PIN blocks (it resolves to " + leg.algorithmName + ")");
        }
    }

    // ---- the helpers ---------------------------------------------------------

    private PaymentCryptographyKey requiredKey(String identifier, String region) {
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw notFound(identifier);
        }
        requireEnabled(key);
        return key;
    }

    private static PaymentCryptographyKeyAlgorithm algorithmOf(PaymentCryptographyKey key) {
        PaymentCryptographyKeyAlgorithm algorithm =
                PaymentCryptographyKeyAlgorithm.from(key.getKeyAlgorithm());
        if (algorithm == null) {
            throw validation("the key " + key.getArn() + " has the algorithm "
                    + key.getKeyAlgorithm() + ", which this service does not make");
        }
        return algorithm;
    }

    /**
     * The PIN length the request declares, which the Visa path fixes at four digits.
     *
     * <p>The Visa verification value is a four digit number, and the algorithm's transformed
     * security parameter is sixteen digits: eleven of account number, one of verification key
     * index and four of PIN. A longer PIN has no place in it, so there is no verification
     * value for one, and a request that declares another length names something this path
     * cannot answer.
     *
     * <p>The model lets the length be between four and twelve, which is the range of the PIN
     * a block holds and the range the IBM 3624 variants need; the Visa verification value is
     * the four digit one.
     *
     * @param request the request, whose PinDataLength member may be absent
     */
    private static void requireVisaPinLength(JsonNode request) {
        JsonNode declared = request.get("PinDataLength");
        if (declared == null || declared.isNull()) {
            return;
        }
        if (!declared.isIntegralNumber() || declared.asInt() != VISA_PIN_LENGTH) {
            throw validation("Visa PVV requires a 4-digit PIN (PinDataLength 4)");
        }
    }

    /**
     * Refuses the wrapped-key form of a PIN encryption key.
     *
     * <p>A wrapped key is a TR-31 key block that the service unwraps with a key-encryption
     * key, and the unwrapping is part of the key exchange. Until it is made, a request that
     * carries one is refused rather than answered as if the key were a fixed one.
     */
    private static void refuseWrappedKey(JsonNode request, String member) {
        if (request.get(member) != null) {
            throw validation(member + " is not supported by this service");
        }
    }

    private static PinBlockFormat format(String wireName) {
        PinBlockFormat format = PinBlockFormat.from(wireName);
        if (format == null) {
            throw validation("PinBlockFormat '" + wireName + "' not supported by this service ("
                    + PinBlockFormat.supported() + ")");
        }
        return format;
    }

    /**
     * The verification key index the attributes name.
     *
     * <p>The model states that the attributes carry the index and the range it holds, and the
     * request has been checked against it, so it is read and not checked again.
     *
     * @param visa the VisaPin attributes of the request
     * @return the index of the verification key
     */
    private static int pvki(JsonNode visa) {
        return visa.path("PinVerificationKeyIndex").asInt();
    }

    /**
     * The account number the request names.
     *
     * <p>The member is optional in the model, so a request that leaves it out is refused
     * here. Its length and its digits are the model's to settle.
     *
     * @param pan the account number of the request
     * @param message what to say when the request leaves it out
     * @return the account number
     */
    private static String requirePan(String pan, String message) {
        if (pan.isEmpty()) {
            throw validation(message);
        }
        return pan;
    }

    private static void requireExactLength(String value, int length, String message) {
        if (value == null || value.length() != length) {
            throw validation(message);
        }
    }

    /**
     * Whether a recovered PIN is one the service can work with.
     *
     * <p>A block that was enciphered under another key deciphers to something that is not a
     * PIN, and what it carries is then not a PIN block the service can read.
     */
    private static boolean isRecoveredPin(String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 12) {
            return false;
        }
        for (int i = 0; i < pin.length(); i++) {
            if (!Character.isDigit(pin.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String randomPin(int length) {
        StringBuilder pin = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            pin.append(random.nextInt(10));
        }
        return pin.toString();
    }

    /** The translation attributes this service accepts, for a message. */
    private static String translationFormats() {
        StringBuilder names = new StringBuilder();
        for (PinBlockFormat format : PinBlockFormat.values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(format.translationMember());
        }
        return names.toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 404);
    }

    /**
     * The PIN did not verify.
     *
     * <p>A PIN that does not verify is not a bad request: the request was well made and the
     * answer is that the PIN is not the one the issuer holds.
     */
    private static AwsException verificationFailed() {
        return new AwsException("VerificationFailedException", "Pin block verification failed.", 400,
                Map.of("Reason", "INVALID_PIN"));
    }

    /**
     * The PIN block the request carries is not one the service can read.
     *
     * <p>The reference answers a block that is not a valid PIN block with this error, and
     * names the three causes: the wrong key, a wrong account number, and a corrupted block.
     *
     * @return the error that says the block is invalid
     */
    private static AwsException invalidPinBlock() {
        return new AwsException("ValidationException",
                "Pin block provided is invalid. Please check your input to ensure all field "
                        + "values are correct.", 400);
    }
}
