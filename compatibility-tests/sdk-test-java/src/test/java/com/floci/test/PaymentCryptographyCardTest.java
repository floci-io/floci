package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.paymentcryptography.PaymentCryptographyClient;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyModesOfUse;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptographydata.PaymentCryptographyDataClient;
import software.amazon.awssdk.services.paymentcryptographydata.model.CardGenerationAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.CardVerificationAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.CardVerificationValue1;
import software.amazon.awssdk.services.paymentcryptographydata.model.DynamicCardVerificationValue;
import software.amazon.awssdk.services.paymentcryptographydata.model.GenerateAuthRequestCryptogramResponse;
import software.amazon.awssdk.services.paymentcryptographydata.model.GenerateCardValidationDataResponse;
import software.amazon.awssdk.services.paymentcryptographydata.model.MajorKeyDerivationMode;
import software.amazon.awssdk.services.paymentcryptographydata.model.SessionKeyAmex;
import software.amazon.awssdk.services.paymentcryptographydata.model.SessionKeyDerivation;
import software.amazon.awssdk.services.paymentcryptographydata.model.SessionKeyEmvCommon;
import software.amazon.awssdk.services.paymentcryptographydata.model.ValidationException;
import software.amazon.awssdk.services.paymentcryptographydata.model.VerificationFailedException;
import software.amazon.awssdk.services.paymentcryptographydata.model.VerificationFailedReason;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment Cryptography card validation values and authorization cryptograms")
class PaymentCryptographyCardTest {

    /** The account number the card values belong to. */
    private static final String PAN = "5081470082639564";

    /** The expiry of the card, in the YYMM form the request takes. */
    private static final String EXPIRY = "3003";

    /** The service code of the first card verification value. */
    private static final String SERVICE_CODE = "201";

    /** The sequence number of the card, which the master key derivation takes. */
    private static final String PSN = "01";

    /** The transaction counter the session key derivation takes. */
    private static final String ATC = "0001";

    /**
     * The transaction data the cryptogram is computed over.
     *
     * <p>The user guide tells the caller to pad the transaction data before sending
     * it, and the data here is padded the way its example is: a whole number of
     * eight-byte blocks, with the {@code 80} and the zeros that method 2 appends.
     */
    private static final String TRANSACTION_DATA =
            "00000000170000000000000008400080008000084016051700000000093800000B1F2201"
                    + "030000000000000000000000000000000000000000000000000000008000000000000000";

    private static PaymentCryptographyDataClient client;
    private static String cardVerificationKeyArn;
    private static String dynamicValueKeyArn;
    private static String applicationCryptogramKeyArn;

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyDataClient();
        // The card verification value is computed under the card verification key, the dynamic
        // one under a key of its own usage that may derive, and the cryptogram under the
        // application cryptogram key.
        cardVerificationKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_C0_CARD_VERIFICATION_KEY, m -> m.generate(true).verify(true));
        dynamicValueKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_E6_EMV_MKEY_OTHER, m -> m.generate(true).verify(true).deriveKey(true));
        applicationCryptogramKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_E0_EMV_MKEY_APP_CRYPTOGRAMS, m -> m.generate(true).deriveKey(true));
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
        try (PaymentCryptographyClient keyClient = TestFixtures.paymentCryptographyClient()) {
            return keyClient.createKey(r -> r
                    .keyAttributes(a -> a
                            .keyAlgorithm(algorithm)
                            .keyClass(KeyClass.SYMMETRIC_KEY)
                            .keyUsage(usage)
                            .keyModesOfUse(modes))
                    .exportable(true))
                    .key().keyArn();
        }
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void theCardVerificationValueIsGeneratedAndVerifies() {
        GenerateCardValidationDataResponse generated = client.generateCardValidationData(r -> r
                .keyIdentifier(cardVerificationKeyArn)
                .primaryAccountNumber(PAN)
                .generationAttributes(CardGenerationAttributes.builder()
                        .cardVerificationValue1(CardVerificationValue1.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .build())
                        .build()));

        String value = generated.validationData();
        assertThat(value).isNotBlank();
        assertThat(value).hasSize(3);
        assertThat(generated.keyArn()).isEqualTo(cardVerificationKeyArn);

        // The value verifies against the one that was generated.
        client.verifyCardValidationData(r -> r
                .keyIdentifier(cardVerificationKeyArn)
                .primaryAccountNumber(PAN)
                .validationData(value)
                .verificationAttributes(CardVerificationAttributes.builder()
                        .cardVerificationValue1(CardVerificationValue1.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .build())
                        .build()));
    }

    @Test
    void aCardVerificationValueThatDoesNotVerifyIsRefused() {
        assertThatThrownBy(() -> client.verifyCardValidationData(r -> r
                .keyIdentifier(cardVerificationKeyArn)
                .primaryAccountNumber(PAN)
                .validationData("999")
                .verificationAttributes(CardVerificationAttributes.builder()
                        .cardVerificationValue1(CardVerificationValue1.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .build())
                        .build())))
                .isInstanceOf(VerificationFailedException.class)
                // The exception carries the reason the model names for a card value that
                // does not verify, so the caller can tell the two failures apart.
                .extracting(e -> ((VerificationFailedException) e).reason())
                .isEqualTo(VerificationFailedReason.INVALID_VALIDATION_DATA);
    }

    @Test
    void theDynamicCardVerificationValueIsGeneratedAndVerifies() {
        GenerateCardValidationDataResponse generated = client.generateCardValidationData(r -> r
                .keyIdentifier(dynamicValueKeyArn)
                .primaryAccountNumber(PAN)
                .generationAttributes(CardGenerationAttributes.builder()
                        .dynamicCardVerificationValue(DynamicCardVerificationValue.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .panSequenceNumber(PSN)
                                .applicationTransactionCounter(ATC)
                                .build())
                        .build()));

        String value = generated.validationData();
        assertThat(value).isNotBlank();
        assertThat(value).hasSize(3);

        client.verifyCardValidationData(r -> r
                .keyIdentifier(dynamicValueKeyArn)
                .primaryAccountNumber(PAN)
                .validationData(value)
                .verificationAttributes(CardVerificationAttributes.builder()
                        .dynamicCardVerificationValue(DynamicCardVerificationValue.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .panSequenceNumber(PSN)
                                .applicationTransactionCounter(ATC)
                                .build())
                        .build()));
    }

    @Test
    void aKeyOfTheWrongUsageIsRefused() {
        String dataKeyArn = createKeyArn(KeyAlgorithm.TDES_2_KEY,
                KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY, m -> m.encrypt(true).decrypt(true));

        assertThatThrownBy(() -> client.generateCardValidationData(r -> r
                .keyIdentifier(dataKeyArn)
                .primaryAccountNumber(PAN)
                .generationAttributes(CardGenerationAttributes.builder()
                        .cardVerificationValue1(CardVerificationValue1.builder()
                                .cardExpiryDate(EXPIRY)
                                .serviceCode(SERVICE_CODE)
                                .build())
                        .build())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theAuthorizationCryptogramIsGeneratedAndVerifies() {
        GenerateAuthRequestCryptogramResponse generated = client.generateAuthRequestCryptogram(r -> r
                .keyIdentifier(applicationCryptogramKeyArn)
                .transactionData(TRANSACTION_DATA)
                .majorKeyDerivationMode(MajorKeyDerivationMode.EMV_OPTION_A)
                .sessionKeyDerivationAttributes(SessionKeyDerivation.builder()
                        .emvCommon(SessionKeyEmvCommon.builder()
                                .primaryAccountNumber(PAN)
                                .panSequenceNumber(PSN)
                                .applicationTransactionCounter(ATC)
                                .build())
                        .build()));

        String cryptogram = generated.authRequestCryptogram();
        assertThat(cryptogram).isNotBlank();
        assertThat(cryptogram).hasSize(16);
        assertThat(generated.keyArn()).isEqualTo(applicationCryptogramKeyArn);

        client.verifyAuthRequestCryptogram(r -> r
                .keyIdentifier(applicationCryptogramKeyArn)
                .transactionData(TRANSACTION_DATA)
                .authRequestCryptogram(cryptogram)
                .majorKeyDerivationMode(MajorKeyDerivationMode.EMV_OPTION_A)
                .sessionKeyDerivationAttributes(SessionKeyDerivation.builder()
                        .emvCommon(SessionKeyEmvCommon.builder()
                                .primaryAccountNumber(PAN)
                                .panSequenceNumber(PSN)
                                .applicationTransactionCounter(ATC)
                                .build())
                        .build()));
    }

    @Test
    void anAuthorizationCryptogramThatDoesNotVerifyIsRefused() {
        assertThatThrownBy(() -> client.verifyAuthRequestCryptogram(r -> r
                .keyIdentifier(applicationCryptogramKeyArn)
                .transactionData(TRANSACTION_DATA)
                .authRequestCryptogram("0000000000000000")
                .majorKeyDerivationMode(MajorKeyDerivationMode.EMV_OPTION_A)
                .sessionKeyDerivationAttributes(SessionKeyDerivation.builder()
                        .emvCommon(SessionKeyEmvCommon.builder()
                                .primaryAccountNumber(PAN)
                                .panSequenceNumber(PSN)
                                .applicationTransactionCounter(ATC)
                                .build())
                        .build())))
                .isInstanceOf(VerificationFailedException.class);
    }

    @Test
    void theAmexCryptogramIsRefusedRatherThanApproximated() {
        // The AEIPS session key derivation is proprietary, so the service answers with the
        // error that says so rather than with a cryptogram of its own making.
        assertThatThrownBy(() -> client.generateAuthRequestCryptogram(r -> r
                .keyIdentifier(applicationCryptogramKeyArn)
                .transactionData(TRANSACTION_DATA)
                .majorKeyDerivationMode(MajorKeyDerivationMode.EMV_OPTION_A)
                .sessionKeyDerivationAttributes(SessionKeyDerivation.builder()
                        .amex(SessionKeyAmex.builder()
                                .primaryAccountNumber(PAN)
                                .panSequenceNumber(PSN)
                                .build())
                        .build())))
                .isInstanceOf(ValidationException.class);
    }
}
