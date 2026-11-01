package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.paymentcryptographydata.PaymentCryptographyDataClient;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionDecryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionMode;
import software.amazon.awssdk.services.paymentcryptographydata.model.GenerateMacRequest;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAlgorithm;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.SymmetricEncryptionAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Payment Cryptography data plane through the AWS SDK.
 *
 * <p>The data plane is a rest-json service of its own, so a real client is the only way to
 * prove that the paths, the request shapes and the answers all agree.
 */
@DisplayName("Payment Cryptography data plane")
class PaymentCryptographyDataPlaneTest {

    /** A plaintext long enough for the service to encrypt it. */
    private static final String PLAIN_TEXT = hex("the quick brown fox jumps over the lazy dog");

    /** A message long enough for the service to make a MAC over it. */
    private static final String MESSAGE = hex("a message to authenticate");

    private static PaymentCryptographyDataClient client;
    private static String keyArn;

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyDataClient();
        keyArn = TestFixtures.paymentCryptographyClient().createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm.AES_256)
                        .keyClass(software.amazon.awssdk.services.paymentcryptography.model.KeyClass.SYMMETRIC_KEY)
                        .keyUsage(software.amazon.awssdk.services.paymentcryptography.model.KeyUsage
                                .TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true).decrypt(true).generate(true).verify(true)))
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
    void aKeyEncryptsAndDecrypts() {
        String cipherText = client.encryptData(r -> r
                .keyIdentifier(keyArn)
                .plainText(PLAIN_TEXT)
                .encryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build()))
                .cipherText();

        assertThat(cipherText).isNotBlank();
        assertThat(cipherText).isNotEqualTo(PLAIN_TEXT);

        String plainText = client.decryptData(r -> r
                .keyIdentifier(keyArn)
                .cipherText(cipherText)
                .decryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build()))
                .plainText();

        assertThat(plainText).isEqualTo(PLAIN_TEXT);
    }

    @Test
    void aKeyMakesAMacAndVerifiesIt() {
        GenerateMacRequest mac = GenerateMacRequest.builder()
                .keyIdentifier(keyArn)
                .messageData(MESSAGE)
                .macLength(16)
                .generationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build())
                .build();

        String value = client.generateMac(mac).mac();

        assertThat(value).isNotBlank();

        // The service fails the request when the MAC is not the one over the message.
        client.verifyMac(r -> r
                .keyIdentifier(keyArn)
                .messageData(MESSAGE)
                .mac(value)
                .macLength(16)
                .verificationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build()));
    }

    @Test
    void aMacOfAnotherMessageIsRefused() {
        String value = client.generateMac(r -> r
                .keyIdentifier(keyArn)
                .messageData(MESSAGE)
                .macLength(16)
                .generationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build()))
                .mac();

        assertThatThrownBy(() -> client.verifyMac(r -> r
                .keyIdentifier(keyArn)
                .messageData(hex("another message entirely"))
                .mac(value)
                .macLength(16)
                .verificationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build())))
                .isNotNull();
    }

    @Test
    void aKeyThatIsNotThereIsRefused() {
        assertThatThrownBy(() -> client.encryptData(r -> r
                .keyIdentifier("arn:aws:payment-cryptography:us-east-1:000000000000:key/nope")
                .plainText(PLAIN_TEXT)
                .encryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build())))
                .isNotNull();
    }

    /** Bytes as the data plane carries them: hexadecimal. */
    private static String hex(String text) {
        StringBuilder sb = new StringBuilder();
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
