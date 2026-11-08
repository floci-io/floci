package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptographydata.PaymentCryptographyDataClient;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionDecryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionMode;
import software.amazon.awssdk.services.paymentcryptographydata.model.GenerateMacRequest;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAlgorithm;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.ReEncryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.SymmetricEncryptionAttributes;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Payment Cryptography data plane")
class PaymentCryptographyDataPlaneTest {

    /**
     * A plaintext long enough for the service to encrypt it. The cipher pads no data, so it
     * fills whole blocks.
     */
    private static final String PLAIN_TEXT = hex("the quick brown fox jumps over the lazy dog!!!!!");

    /** A message long enough for the service to make a MAC over it. */
    private static final String MESSAGE = hex("a message to authenticate");

    private static PaymentCryptographyDataClient client;
    private static String keyArn;
    private static String macKeyArn;

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyDataClient();
        keyArn = TestFixtures.paymentCryptographyClient().createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true).decrypt(true).generate(true).verify(true)))
                .exportable(true))
                .key().keyArn();
        // A MAC is made under a key of the MAC usage, so it is one of its own.
        macKeyArn = TestFixtures.paymentCryptographyClient().createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_M6_ISO_9797_5_CMAC_KEY)
                        .keyModesOfUse(m -> m.generate(true).verify(true)))
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
    void aKeyReEncryptsUnderAnother() {
        String otherArn = TestFixtures.paymentCryptographyClient().createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true).decrypt(true)))
                .exportable(true))
                .key().keyArn();

        String cipherText = client.encryptData(r -> r
                .keyIdentifier(keyArn)
                .plainText(PLAIN_TEXT)
                .encryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build()))
                .cipherText();

        // The re-encryption names the key it decrypts under in the path and the key it encrypts
        // under in the body, so the request carries two keys.
        String reEncrypted = client.reEncryptData(r -> r
                .incomingKeyIdentifier(keyArn)
                .outgoingKeyIdentifier(otherArn)
                .cipherText(cipherText)
                .incomingEncryptionAttributes(ReEncryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build())
                .outgoingEncryptionAttributes(ReEncryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build()))
                .cipherText();

        assertThat(reEncrypted).isNotBlank();
        assertThat(reEncrypted).isNotEqualTo(cipherText);

        // The block under the other key decrypts there and not under the first.
        String plainText = client.decryptData(r -> r
                .keyIdentifier(otherArn)
                .cipherText(reEncrypted)
                .decryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build()))
                .plainText();

        assertThat(plainText).isEqualTo(PLAIN_TEXT);
    }

    @Test
    void aKeyMakesAMacAndVerifiesIt() {
        GenerateMacRequest mac = GenerateMacRequest.builder()
                .keyIdentifier(macKeyArn)
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
                .keyIdentifier(macKeyArn)
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
                .keyIdentifier(macKeyArn)
                .messageData(MESSAGE)
                .macLength(16)
                .generationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build()))
                .mac();

        // The failure names the reason the model's shape carries, so a caller can tell a bad
        // MAC from a bad request.
        AwsServiceException refusal = assertThrows(AwsServiceException.class, () -> client.verifyMac(r -> r
                .keyIdentifier(macKeyArn)
                .messageData(hex("another message entirely"))
                .mac(value)
                .macLength(16)
                .verificationAttributes(MacAttributes.builder()
                        .algorithm(MacAlgorithm.CMAC)
                        .build())));
        assertThat(refusal.awsErrorDetails().errorCode()).isEqualTo("VerificationFailedException");
        assertThat(refusal.statusCode()).isEqualTo(400);
    }

    @Test
    void aKeyThatIsNotThereIsRefused() {
        // The identifier matches the model's pattern, so the request reaches the key it names
        // rather than being refused as a malformed one.
        AwsServiceException refusal = assertThrows(AwsServiceException.class, () -> client.encryptData(r -> r
                .keyIdentifier("arn:aws:payment-cryptography:us-east-1:000000000000:key/0123456789abcdef")
                .plainText(PLAIN_TEXT)
                .encryptionAttributes(EncryptionDecryptionAttributes.builder()
                        .symmetric(SymmetricEncryptionAttributes.builder().mode(EncryptionMode.CBC).build())
                        .build())));
        assertThat(refusal.awsErrorDetails().errorCode()).isEqualTo("ResourceNotFoundException");
        assertThat(refusal.statusCode()).isEqualTo(404);
    }

    /** Bytes as the data plane carries them: hexadecimal. */
    private static String hex(String text) {
        StringBuilder sb = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
