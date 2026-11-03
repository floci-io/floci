package io.github.hectorvent.floci.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.DukptCrypto;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.Hex;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.PinBlockFormat;
import io.github.hectorvent.floci.services.paymentcryptography.crypto.PinCrypto;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
 */
final class PaymentCryptographyPinOperations {

    /** The PIN the service generates, when the caller does not bring one. */
    private static final int GENERATED_PIN_LENGTH = 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentCryptographyService service;

    PaymentCryptographyPinOperations(PaymentCryptographyService service) {
        this.service = service;
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
        String encryptionKeyIdentifier = text(request, "EncryptionKeyIdentifier");
        if (encryptionKeyIdentifier.isEmpty()) {
            throw validation("EncryptionKeyIdentifier is required");
        }
        String verificationKeyIdentifier = text(request, "VerificationKeyIdentifier");
        if (verificationKeyIdentifier.isEmpty()) {
            throw validation("VerificationKeyIdentifier is required");
        }
        PinBlockFormat format = format(text(request, "PinBlockFormat"));
        String pan = requirePan(text(request, "PrimaryAccountNumber"),
                "PrimaryAccountNumber (12-19 digits) is required for Visa PVV verification");
        requireHex(text(request, "EncryptedPinBlock"), format.blockHexLength(),
                "EncryptedPinBlock must be " + format.blockHexLength()
                        + " hexadecimal characters (one " + format.label() + " block)");

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
        int pvki = requirePvki(visa, "VisaPin.PinVerificationKeyIndex");
        String pvv = visa == null ? "" : text(visa, "VerificationValue");
        if (pvv.isEmpty()) {
            throw validation("VisaPin.VerificationValue is required");
        }

        PinLeg pek = resolvePinLeg(encryptionKeyIdentifier, request.get("DukptAttributes"), region,
                "Decrypt", "decrypting a PIN block");
        requireNoDukptForAes(format, request.get("DukptAttributes"));
        requireKeyMatchesFormat(pek, format, "EncryptionKeyIdentifier");

        PaymentCryptographyKey pvk = requiredKey(verificationKeyIdentifier, region);
        requireMode(pvk, "Verify", "verifying a PIN");
        requireSymmetricMaterial(pvk, "verify a PIN with");
        requireTdesKey(pvk, "verify a PIN with");

        String pin;
        try {
            pin = PinCrypto.decipherPinBlock(format, text(request, "EncryptedPinBlock"), pan,
                    pek.keyBytes);
        } catch (RuntimeException e) {
            throw validation("Unable to recover PIN from PIN block: " + e.getMessage());
        }
        // A wrong key, or a wrong KSN, deciphers to garble. That is a failed verification, not
        // a server error.
        if (!isRecoveredPin(pin)) {
            throw verificationFailed("PIN_VERIFICATION_FAILED");
        }
        if (!PinCrypto.computePvv(pan, pvki, pin, pvk.getKeyMaterialHex()).equals(pvv)) {
            throw verificationFailed("PIN_VERIFICATION_FAILED");
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
        String encryptionKeyIdentifier = text(request, "EncryptionKeyIdentifier");
        if (encryptionKeyIdentifier.isEmpty()) {
            throw validation("EncryptionKeyIdentifier is required");
        }
        String generationKeyIdentifier = text(request, "GenerationKeyIdentifier");
        if (generationKeyIdentifier.isEmpty()) {
            throw validation("GenerationKeyIdentifier is required");
        }
        PinBlockFormat format = format(text(request, "PinBlockFormat"));
        String pan = requirePan(text(request, "PrimaryAccountNumber"),
                "PrimaryAccountNumber (12-19 digits) is required");

        JsonNode generationAttributes = request.get("GenerationAttributes");
        String variant = soleVariant(generationAttributes);
        if (!"VisaPin".equals(variant) && !"VisaPinVerificationValue".equals(variant)) {
            throw validation("GenerationAttributes." + (variant == null ? "(missing)" : variant)
                    + " not supported (VisaPin, VisaPinVerificationValue)");
        }
        JsonNode visa = generationAttributes == null ? null : generationAttributes.get(variant);
        int pvki = requirePvki(visa, variant + ".PinVerificationKeyIndex");

        // VisaPinVerificationValue carries the block to verify, so the verification value is
        // computed for a PIN the caller already has rather than a new one.
        String givenBlock = "VisaPinVerificationValue".equals(variant) && visa != null
                ? text(visa, "EncryptedPinBlock")
                : "";
        if ("VisaPinVerificationValue".equals(variant) && givenBlock.isEmpty()) {
            throw validation("VisaPinVerificationValue.EncryptedPinBlock is required");
        }
        Integer pinDataLength = request.has("PinDataLength") ? request.path("PinDataLength").asInt() : null;
        if (pinDataLength != null && pinDataLength != GENERATED_PIN_LENGTH) {
            throw validation("Visa PVV requires a 4-digit PIN (PinDataLength 4)");
        }

        PinLeg pek = resolvePinLeg(encryptionKeyIdentifier, null, region,
                "Encrypt", "encrypting a PIN block");
        PaymentCryptographyKey pvk = requiredKey(generationKeyIdentifier, region);
        requireMode(pvk, "Generate", "generating a PIN verification value");
        requireSymmetricMaterial(pvk, "generate a PIN verification value with");
        requireTdesKey(pvk, "generate a PIN verification value with");
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
            requireHex(givenBlock, format.blockHexLength(),
                    "EncryptedPinBlock must be " + format.blockHexLength()
                            + " hexadecimal characters (one " + format.label() + " block)");
            try {
                pin = PinCrypto.decipherPinBlock(format, givenBlock, pan, pek.keyBytes);
            } catch (RuntimeException e) {
                throw validation("Unable to recover PIN from PIN block: " + e.getMessage());
            }
            if (!isRecoveredPin(pin)) {
                throw verificationFailed("PIN_VERIFICATION_FAILED");
            }
            encryptedPinBlock = givenBlock;
        } else {
            pin = randomPin(GENERATED_PIN_LENGTH);
            encryptedPinBlock = PinCrypto.encipherPinBlock(format, pin, pan, pek.keyBytes, RANDOM);
        }
        String pvv = PinCrypto.computePvv(pan, pvki, pin, pvk.getKeyMaterialHex());

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

    // ---- TranslatePinData -----------------------------------------------------

    /**
     * Re-enciphers a PIN block from one PEK to another.
     */
    Map<String, Object> translatePinData(JsonNode request, String region) {
        String incomingKeyIdentifier = text(request, "IncomingKeyIdentifier");
        if (incomingKeyIdentifier.isEmpty()) {
            throw validation("IncomingKeyIdentifier is required");
        }
        String outgoingKeyIdentifier = text(request, "OutgoingKeyIdentifier");
        if (outgoingKeyIdentifier.isEmpty()) {
            throw validation("OutgoingKeyIdentifier is required");
        }
        if (request.get("IncomingAs2805Attributes") != null) {
            throw validation("AS2805 translation is not supported by this service");
        }

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
        requireHex(text(request, "EncryptedPinBlock"), inFormat.blockHexLength(),
                "EncryptedPinBlock must be " + inFormat.blockHexLength()
                        + " hexadecimal characters (one " + inFormat.label() + " block)");

        JsonNode inAttrs = incoming.get(inVariant);
        JsonNode outAttrs = outgoing.get(outVariant);
        String inPan = inAttrs == null ? "" : text(inAttrs, "PrimaryAccountNumber");
        String outPan = outAttrs == null ? "" : text(outAttrs, "PrimaryAccountNumber");
        if (inPan.isEmpty()) {
            throw validation("IncomingTranslationAttributes." + inVariant
                    + ".PrimaryAccountNumber is required");
        }
        if (outPan.isEmpty()) {
            throw validation("OutgoingTranslationAttributes." + outVariant
                    + ".PrimaryAccountNumber is required");
        }
        requireTranslationPan(inPan, inFormat,
                "IncomingTranslationAttributes." + inVariant + ".PrimaryAccountNumber");
        requireTranslationPan(outPan, outFormat,
                "OutgoingTranslationAttributes." + outVariant + ".PrimaryAccountNumber");

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
                throw validation("unable to translate PIN block: the incoming block does not carry "
                        + "a well-formed " + inFormat.label() + " PIN field");
            }
            pinBlock = PinCrypto.encipherPinBlock(outFormat, pin, outPan, out.keyBytes, RANDOM);
        } catch (AwsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw validation("unable to translate PIN block: " + e.getMessage());
        }

        Map<String, Object> out2 = new LinkedHashMap<>();
        out2.put("PinBlock", pinBlock);
        out2.put("EncryptionKeyArn", in.record.getArn());
        out2.put("EncryptionKeyCheckValue", in.record.getKeyCheckValue());
        out2.put("OutgoingEncryptionKeyArn", out.record.getArn());
        out2.put("OutgoingEncryptionKeyCheckValue", out.record.getKeyCheckValue());
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
        if (identifier.isEmpty()) {
            throw validation("the key identifier is required");
        }
        PaymentCryptographyKey key = service.findKey(region, identifier);
        if (key == null) {
            throw notFound(identifier);
        }
        if (dukptAttributes == null) {
            requireMode(key, mode, what);
            requireSymmetricMaterial(key, what);
            PaymentCryptographyKeyAlgorithm algorithm = algorithmOf(key);
            return new PinLeg(key, Hex.decode(key.getKeyMaterialHex()), algorithm.aes,
                    algorithm.name());
        }
        // A DUKPT PIN key is derived from the base derivation key, so that is what the key has
        // to allow.
        requireMode(key, "DeriveKey", "deriving a PIN key");

        String ksn = text(dukptAttributes, "KeySerialNumber");
        if (!isHex(ksn)) {
            throw validation("DukptAttributes.KeySerialNumber (hex) is required");
        }
        String derivationType = text(dukptAttributes, "DukptDerivationType");
        if (derivationType.isEmpty()) {
            derivationType = text(dukptAttributes, "DukptKeyDerivationType");
        }
        if (!derivationType.isEmpty() && !"TDES_2KEY".equals(derivationType)) {
            throw validation("DUKPT derivation type '" + derivationType
                    + "' not supported by this service (TDES_2KEY)");
        }
        String keyVariant = text(dukptAttributes, "DukptKeyVariant");
        if (!keyVariant.isEmpty() && !"BIDIRECTIONAL".equals(keyVariant)) {
            throw validation("DukptKeyVariant '" + keyVariant
                    + "' not supported by this service (BIDIRECTIONAL)");
        }
        try {
            // A DUKPT PIN key is a TDES key, whatever the base derivation key is.
            return new PinLeg(key, DukptCrypto.pinKey(key.getKeyMaterialHex(), ksn), false, "TDES");
        } catch (RuntimeException e) {
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

    /**
     * Checks that the key holds material to work with.
     *
     * <p>A key pair can allow verifying, but it has no material to verify with, and the
     * verification value would fail on a null key rather than say why.
     */
    private static void requireSymmetricMaterial(PaymentCryptographyKey key, String what) {
        String material = key.getKeyMaterialHex();
        if (material == null || material.isEmpty()) {
            throw validation("the key " + key.getArn() + " has no material to " + what);
        }
        PaymentCryptographyKeyAlgorithm algorithm = algorithmOf(key);
        if (material.length() != algorithm.bytes * 2) {
            throw validation("the key " + key.getArn() + " does not hold "
                    + algorithm.name() + " material");
        }
    }

    /**
     * The key has to be a TDES one, for the Visa PVV.
     *
     * <p>The PVV is defined over Triple DES: it encrypts the transaction data selector with the
     * verification key and takes the decimalized result. An AES verification key has no such
     * computation, and asking for one would fail inside the cipher rather than say why, so the
     * key is refused here with the reason.
     *
     * @param key the verification key
     * @param what what the key was asked to do, in words
     */
    private static void requireTdesKey(PaymentCryptographyKey key, String what) {
        PaymentCryptographyKeyAlgorithm algorithm = algorithmOf(key);
        if (algorithm.aes) {
            throw validation("the key " + key.getArn()
                    + " is an AES key, and the Visa PVV is defined over Triple DES, so it cannot "
                    + what);
        }
    }

    /** The format of that wire name, or the error that names the ones that are served. */
    private static PinBlockFormat format(String wireName) {
        PinBlockFormat format = PinBlockFormat.from(wireName);
        if (format == null) {
            throw validation("PinBlockFormat '" + (wireName == null || wireName.isEmpty()
                    ? "(missing)" : wireName) + "' not supported by this service ("
                    + PinBlockFormat.supported() + ")");
        }
        return format;
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

    /** The PIN verification key index, which is a number. */
    private static int requirePvki(JsonNode attributes, String label) {
        JsonNode declared = attributes == null ? null : attributes.get("PinVerificationKeyIndex");
        if (declared == null || !declared.isNumber()) {
            throw validation(label + " is required");
        }
        int pvki = declared.asInt();
        if (pvki < 0 || pvki > 9) {
            throw validation(label + " must be between 0 and 9");
        }
        return pvki;
    }

    /** The account number, which the Visa path needs in full. */
    private static String requirePan(String pan, String message) {
        String digits = PinCrypto.digits(pan);
        if (digits.length() < 12 || digits.length() > 19) {
            throw validation(message);
        }
        return digits;
    }

    /**
     * The account number a translation takes.
     *
     * <p>The ISO formats bind the PIN to the twelve-digit account field, and the format 4 one
     * binds it to the whole PAN.
     */
    private static void requireTranslationPan(String pan, PinBlockFormat format, String label) {
        String digits = PinCrypto.digits(pan);
        if (format.isAes()) {
            if (digits.isEmpty() || digits.length() > 19) {
                throw validation(label + " must be 1..19 digits");
            }
            return;
        }
        if (digits.length() != 12 && digits.length() < 13) {
            throw validation(label + " must be the 12-digit account field");
        }
    }

    /** The hexadecimal the request carries, of the length the format calls for. */
    private static void requireHex(String value, int length, String message) {
        if (!isHex(value) || value.length() != length) {
            throw validation(message);
        }
    }

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

    /**
     * Whether the recovered digits are a PIN.
     *
     * <p>A wrong key deciphers to garble, and garble is not four to twelve digits.
     */
    private static boolean isRecoveredPin(String pin) {
        return pin != null && pin.matches("[0-9]{4,12}");
    }

    /** A random PIN of that length. */
    private static String randomPin(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** The text of a request member. */
    private static String text(JsonNode request, String member) {
        JsonNode declared = request.get(member);
        return declared == null || declared.isNull() ? "" : declared.asText("");
    }

    /** The formats a translation may name. */
    private static String translationFormats() {
        StringBuilder sb = new StringBuilder();
        for (PinBlockFormat format : PinBlockFormat.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(format.translationMember());
        }
        return sb.toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundException", "Key not found: " + identifier, 400);
    }

    private static AwsException verificationFailed(String reason) {
        return new AwsException("VerificationFailedException",
                "This request failed verification: " + reason, 400);
    }
}
