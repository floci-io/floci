package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyModesOfUse;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptographydata.PaymentCryptographyDataClient;
import software.amazon.awssdk.services.paymentcryptographydata.model.GeneratePinDataResponse;
import software.amazon.awssdk.services.paymentcryptographydata.model.PinBlockFormatForPinData;
import software.amazon.awssdk.services.paymentcryptographydata.model.PinData;
import software.amazon.awssdk.services.paymentcryptographydata.model.PinGenerationAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.PinVerificationAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.TranslationIsoFormats;
import software.amazon.awssdk.services.paymentcryptographydata.model.TranslationPinDataIsoFormat034;
import software.amazon.awssdk.services.paymentcryptographydata.model.ValidationException;
import software.amazon.awssdk.services.paymentcryptographydata.model.VerificationFailedException;
import software.amazon.awssdk.services.paymentcryptographydata.model.VisaPin;
import software.amazon.awssdk.services.paymentcryptographydata.model.VisaPinVerification;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment Cryptography PIN operations")
class PaymentCryptographyPinTest {

    /** The account number the PIN belongs to. */
    private static final String PAN = "5081470082639564";

    /** The index of the verification key the PVV is computed under. */
    private static final int VERIFICATION_KEY_INDEX = 1;

    private static PaymentCryptographyDataClient client;
    private static String pinEncryptionKeyArn;
    private static String visaVerificationKeyArn;

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyDataClient();
        // A PIN block is protected by a PIN encryption key, and the verification value is
        // computed under a Visa PIN verification key, which is a key of its own.
        pinEncryptionKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_P0_PIN_ENCRYPTION_KEY, m -> m.encrypt(true).decrypt(true));
        visaVerificationKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_V2_VISA_PIN_VERIFICATION_KEY, m -> m.generate(true).verify(true));
    }

    /**
     * The ARN of a key of the given algorithm, usage and modes of use.
     *
     * <p>Every key this test needs is made the same way, so the request is written once
     * and each key only differs in the attributes it carries.
     *
     * @param algorithm the key's algorithm
     * @param usage the key's usage
     * @param modes the modes of use the key allows
     * @return the ARN of the key that was made
     */
    private static String createKeyArn(KeyAlgorithm algorithm, KeyUsage usage,
                                        Consumer<KeyModesOfUse.Builder> modes) {
        return TestFixtures.paymentCryptographyClient().createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(algorithm)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(usage)
                        .keyModesOfUse(modes))
                .exportable(true))
                .key().keyArn();
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void aPinIsGeneratedAndVerifies() {
        // The service makes a random PIN, so the block and the verification value come from
        // one call: a second call would make another PIN and another value.
        GeneratePinDataResponse generated = client.generatePinData(r -> r
                .encryptionKeyIdentifier(pinEncryptionKeyArn)
                .generationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_0)
                .primaryAccountNumber(PAN)
                .generationAttributes(PinGenerationAttributes.builder()
                        .visaPin(VisaPin.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .build())
                        .build()));

        String block = generated.encryptedPinBlock();
        assertThat(block).isNotBlank();
        assertThat(block).hasSize(16);

        PinData pinData = generated.pinData();
        assertThat(pinData.verificationValue()).isNotBlank();
        // The model's PinData is a union, and the discriminator says which of its two
        // members the answer carries: this one carries the verification value.
        assertThat(pinData.type()).isEqualTo(PinData.Type.VERIFICATION_VALUE);

        // The block verifies against the verification value that goes with it.
        client.verifyPinData(r -> r
                .encryptionKeyIdentifier(pinEncryptionKeyArn)
                .verificationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_0)
                .primaryAccountNumber(PAN)
                .encryptedPinBlock(block)
                .verificationAttributes(PinVerificationAttributes.builder()
                        .visaPin(VisaPinVerification.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .verificationValue(pinData.verificationValue())
                                .build())
                        .build()));
    }

    @Test
    void aPinThatDoesNotVerifyIsRefused() {
        String block = client.generatePinData(r -> r
                .encryptionKeyIdentifier(pinEncryptionKeyArn)
                .generationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_0)
                .primaryAccountNumber(PAN)
                .generationAttributes(PinGenerationAttributes.builder()
                        .visaPin(VisaPin.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .build())
                        .build()))
                .encryptedPinBlock();

        // The verification value is not the one over the PIN, so the verification fails.
        assertThatThrownBy(() -> client.verifyPinData(r -> r
                .encryptionKeyIdentifier(pinEncryptionKeyArn)
                .verificationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_0)
                .primaryAccountNumber(PAN)
                .encryptedPinBlock(block)
                .verificationAttributes(PinVerificationAttributes.builder()
                        .visaPin(VisaPinVerification.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .verificationValue("9999")
                                .build())
                        .build())))
                .isInstanceOf(VerificationFailedException.class);
    }

    @Test
    void aPinBlockTranslatesFromTheAesFormatToTheTdesOne() {
        String aesKeyArn = createKeyArn(KeyAlgorithm.AES_128,
                KeyUsage.TR31_P0_PIN_ENCRYPTION_KEY, m -> m.encrypt(true).decrypt(true));

        String block4 = client.generatePinData(r -> r
                .encryptionKeyIdentifier(aesKeyArn)
                .generationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_4)
                .primaryAccountNumber(PAN)
                .generationAttributes(PinGenerationAttributes.builder()
                        .visaPin(VisaPin.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .build())
                        .build()))
                .encryptedPinBlock();

        assertThat(block4).hasSize(32);

        String block0 = client.translatePinData(r -> r
                .encryptedPinBlock(block4)
                .incomingKeyIdentifier(aesKeyArn)
                .outgoingKeyIdentifier(pinEncryptionKeyArn)
                .incomingTranslationAttributes(TranslationIsoFormats.builder()
                        .isoFormat4(TranslationPinDataIsoFormat034.builder()
                                .primaryAccountNumber(PAN)
                                .build())
                        .build())
                .outgoingTranslationAttributes(TranslationIsoFormats.builder()
                        .isoFormat0(TranslationPinDataIsoFormat034.builder()
                                .primaryAccountNumber(PAN)
                                .build())
                        .build()))
                .pinBlock();

        assertThat(block0).isNotBlank();
        assertThat(block0).hasSize(16);
    }

    @Test
    void aKeyThatMayNotEncryptIsRefused() {
        String readOnlyKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_P0_PIN_ENCRYPTION_KEY, m -> m.decrypt(true));

        assertThatThrownBy(() -> client.generatePinData(r -> r
                .encryptionKeyIdentifier(readOnlyKeyArn)
                .generationKeyIdentifier(visaVerificationKeyArn)
                .pinBlockFormat(PinBlockFormatForPinData.ISO_FORMAT_0)
                .primaryAccountNumber(PAN)
                .generationAttributes(PinGenerationAttributes.builder()
                        .visaPin(VisaPin.builder()
                                .pinVerificationKeyIndex(VERIFICATION_KEY_INDEX)
                                .build())
                        .build())))
                .isInstanceOf(ValidationException.class);
    }
}
