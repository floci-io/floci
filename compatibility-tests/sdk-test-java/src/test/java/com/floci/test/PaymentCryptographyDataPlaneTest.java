package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.paymentcryptography.PaymentCryptographyClient;
import software.amazon.awssdk.services.paymentcryptography.model.ExportDiffieHellmanTr31KeyBlock;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptographydata.PaymentCryptographyDataClient;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionDecryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.EncryptionMode;
import software.amazon.awssdk.services.paymentcryptographydata.model.GenerateMacRequest;
import software.amazon.awssdk.services.paymentcryptographydata.model.IncomingDiffieHellmanTr31KeyBlock;
import software.amazon.awssdk.services.paymentcryptographydata.model.KeyDerivationFunction;
import software.amazon.awssdk.services.paymentcryptographydata.model.KeyDerivationHashAlgorithm;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAlgorithm;
import software.amazon.awssdk.services.paymentcryptographydata.model.MacAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.ReEncryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.SymmetricEncryptionAttributes;
import software.amazon.awssdk.services.paymentcryptographydata.model.SymmetricKeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptographydata.model.TranslateKeyMaterialResponse;
import software.amazon.awssdk.services.paymentcryptographydata.model.WrappedKeyMaterialFormat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    /** The keys the tests made, deleted when the class is done with them. */
    private static final List<String> keyArns = new ArrayList<>();

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
        if (keyArns.isEmpty()) {
            return;
        }
        try (PaymentCryptographyClient control = TestFixtures.paymentCryptographyClient()) {
            for (String arn : keyArns) {
                try {
                    control.deleteKey(k -> k.keyIdentifier(arn));
                } catch (Exception ignored) {
                    // The key may already be gone, and real AWS wants a key disabled before
                    // it is deleted.
                }
            }
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

    @Test
    @DisplayName("A key is translated from a key the two parties agreed to another key")
    void aKeyIsTranslatedFromAKeyTheTwoPartiesAgreedToAnotherKey() throws Exception {
        try (PaymentCryptographyClient control = TestFixtures.paymentCryptographyClient()) {
            // The authority that vouches for the other party's certificate, and the other
            // party.
            KeyPair authority = KeyPairGenerator.getInstance("RSA")
                    .generateKeyPair();
            // The curve is the one the service's key pair is on: the two parties have to be
            // on the same curve for the agreement to work.
            KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
            ecGenerator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair counterparty = ecGenerator.generateKeyPair();
            String authorityPem = PaymentCryptographyTest.pem(authority, "floci key-exchange CA",
                    authority, "floci key-exchange CA");
            String counterpartyPem = PaymentCryptographyTest.pem(counterparty, "floci counterparty",
                    authority, "floci key-exchange CA");

            String authorityArn = control.importKey(r -> r
                    .keyMaterial(publicKey -> publicKey.rootCertificatePublicKey(t -> t
                            .publicKeyCertificate(base64(authorityPem))
                            .keyAttributes(a -> a
                                    .keyAlgorithm(KeyAlgorithm.RSA_2048)
                                    .keyClass(KeyClass.PUBLIC_KEY)
                                    .keyUsage(KeyUsage.TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE)
                                    .keyModesOfUse(modes -> modes.verify(true))))))
                    .key().keyArn();
            // The key that agrees with the other party.
            String agreementArn = control.createKey(r -> r
                    .keyAttributes(a -> a
                            .keyAlgorithm(KeyAlgorithm.ECC_NIST_P256)
                            .keyClass(KeyClass.ASYMMETRIC_KEY_PAIR)
                            .keyUsage(KeyUsage.TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT)
                            .keyModesOfUse(m -> m.deriveKey(true)))
                    .exportable(true)).key().keyArn();
            // The key that is translated, and the key it is translated under.
            String keyArn = control.createKey(r -> r
                    .keyAttributes(a -> a
                            .keyAlgorithm(KeyAlgorithm.AES_128)
                            .keyClass(KeyClass.SYMMETRIC_KEY)
                            .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                            .keyModesOfUse(m -> m.encrypt(true).decrypt(true)))
                    .exportable(true)).key().keyArn();
            String kekArn = control.createKey(r -> r
                    .keyAttributes(a -> a
                            .keyAlgorithm(KeyAlgorithm.AES_128)
                            .keyClass(KeyClass.SYMMETRIC_KEY)
                            .keyUsage(KeyUsage.TR31_K0_KEY_ENCRYPTION_KEY)
                            .keyModesOfUse(m -> m.wrap(true).unwrap(true)))
                    .exportable(true)).key().keyArn();
            // Every key the test made is deleted when the class is done with it.
            keyArns.add(authorityArn);
            keyArns.add(agreementArn);
            keyArns.add(keyArn);
            keyArns.add(kekArn);

            String exported = control.exportKey(r -> r
                    .exportKeyIdentifier(keyArn)
                    .keyMaterial(m -> m.diffieHellmanTr31KeyBlock(controlAgreement(
                            authorityArn, agreementArn, counterpartyPem))))
                    .wrappedKey().keyMaterial();

            TranslateKeyMaterialResponse translated = client.translateKeyMaterial(r -> r
                    .incomingKeyMaterial(i -> i.diffieHellmanTr31KeyBlock(dataAgreement(
                            authorityArn, agreementArn, counterpartyPem, exported)))
                    .outgoingKeyMaterial(o -> o.tr31KeyBlock(t -> t.wrappingKeyIdentifier(kekArn))));

            // The block that goes out opens under the key the request named, and it holds the
            // key that came in.
            String keyCheckValue = control.importKey(r -> r
                    .keyMaterial(m -> m.tr31KeyBlock(t -> t
                            .wrappingKeyIdentifier(kekArn)
                            .wrappedKeyBlock(translated.wrappedKey().wrappedKeyMaterial()))))
                    .key().keyCheckValue();

            assertThat(keyCheckValue).isNotBlank();
            assertThat(translated.wrappedKey().wrappedKeyMaterialFormat())
                    .isEqualTo(WrappedKeyMaterialFormat.TR31_KEY_BLOCK);
        }
    }

    /**
     * The agreement the two parties made, in the builder of the control plane.
     *
     * <p>The export that makes the block that comes in is the control plane's, and it names
     * the agreement the same way the translation does, so the members of the agreement are
     * written once for each plane.
     *
     * @param authorityArn the key of the authority that vouches for the counterparty
     * @param agreementArn the key the two parties agree with
     * @param counterpartyPem the certificate the counterparty brings
     * @return the agreement, as the control plane names it
     */
    private static ExportDiffieHellmanTr31KeyBlock controlAgreement(String authorityArn,
                                                                      String agreementArn,
                                                                      String counterpartyPem) {
        return ExportDiffieHellmanTr31KeyBlock.builder()
                .privateKeyIdentifier(agreementArn)
                .certificateAuthorityPublicKeyIdentifier(authorityArn)
                .publicKeyCertificate(base64(counterpartyPem))
                .deriveKeyAlgorithm(CONTROL_DERIVE_ALGORITHM)
                .keyDerivationFunction(CONTROL_KDF)
                .keyDerivationHashAlgorithm(CONTROL_HASH)
                .derivationData(d -> d.sharedInformation(SHARED_INFORMATION))
                .build();
    }

    /**
     * The agreement the two parties made, in the builder of the data plane.
     *
     * @param authorityArn the key of the authority that vouches for the counterparty
     * @param agreementArn the key the two parties agree with
     * @param counterpartyPem the certificate the counterparty brings
     * @param block the block that comes in
     * @return the agreement, as the data plane names it
     */
    private static IncomingDiffieHellmanTr31KeyBlock dataAgreement(String authorityArn,
                                                                    String agreementArn,
                                                                    String counterpartyPem,
                                                                    String block) {
        return IncomingDiffieHellmanTr31KeyBlock.builder()
                .privateKeyIdentifier(agreementArn)
                .certificateAuthorityPublicKeyIdentifier(authorityArn)
                .publicKeyCertificate(base64(counterpartyPem))
                .deriveKeyAlgorithm(SymmetricKeyAlgorithm.AES_128)
                .keyDerivationFunction(KeyDerivationFunction.ANSI_X963)
                .keyDerivationHashAlgorithm(KeyDerivationHashAlgorithm.SHA_256)
                .derivationData(d -> d.sharedInformation(SHARED_INFORMATION))
                .wrappedKeyBlock(block)
                .build();
    }

    /**
     * The algorithm the agreed key is derived in, as the control plane names it.
     *
     * <p>The two planes have their own enums for it, and the export that makes the block
     * that comes in is the control plane's.
     */
    private static final software.amazon.awssdk.services.paymentcryptography.model.SymmetricKeyAlgorithm
            CONTROL_DERIVE_ALGORITHM =
            software.amazon.awssdk.services.paymentcryptography.model.SymmetricKeyAlgorithm.AES_128;
    private static final software.amazon.awssdk.services.paymentcryptography.model.KeyDerivationFunction
            CONTROL_KDF =
            software.amazon.awssdk.services.paymentcryptography.model.KeyDerivationFunction.ANSI_X963;
    private static final software.amazon.awssdk.services.paymentcryptography.model.KeyDerivationHashAlgorithm
            CONTROL_HASH =
            software.amazon.awssdk.services.paymentcryptography.model.KeyDerivationHashAlgorithm.SHA_256;

    /** The shared information the two parties agree with. */
    private static final String SHARED_INFORMATION = "00112233445566778899AABBCCDDEEFF";

    /** The PEM of a certificate over a key pair, signed by an issuer. */
    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(String text) {
        StringBuilder sb = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
