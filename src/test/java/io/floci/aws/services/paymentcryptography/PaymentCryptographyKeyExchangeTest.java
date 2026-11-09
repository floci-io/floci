package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.paymentcryptography.crypto.Certificates;
import io.floci.aws.services.paymentcryptography.crypto.RsaOaep;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The certificate-based half of the key exchange.
 *
 * <p>A key that leaves the service under a certificate is wrapped for the receiver, and the
 * certificates the two sides exchange are the ones the service mints for the occasion. A key
 * that comes back is checked against the authority that signed the certificate, and against
 * the signature the sender made over it.
 */
class PaymentCryptographyKeyExchangeTest {

    private PaymentCryptographyService service;

    /** The key a key block is wrapped under. */
    private PaymentCryptographyKey protection;

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        protection = service.createKey("us-east-1",
                Map.of("KeyAlgorithm", "AES_256",
                        "KeyClass", "SYMMETRIC_KEY",
                        "KeyUsage", "TR31_K1_KEY_BLOCK_PROTECTION_KEY",
                        "KeyModesOfUse", Map.of("Wrap", true, "Unwrap", true)),
                Map.of("Wrap", true, "Unwrap", true), true, true, Map.of());
    }


    @Test
    void theImportParametersCarryACertificateAndAToken() {
        Map<String, Object> parameters = service.parametersForImport("TR34_KEY_BLOCK", "RSA_2048");

        assertNotNull(parameters.get("ImportToken"));
        assertNotNull(parameters.get("WrappingKeyCertificate"));
        assertNotNull(parameters.get("WrappingKeyCertificateChain"));
        assertEquals("RSA_2048", parameters.get("WrappingKeyAlgorithm"));
        assertNotNull(parameters.get("ParametersValidUntilTimestamp"));
        assertNotNull(Certificates
                .parseCertificate((String) parameters.get("WrappingKeyCertificate")));
    }

    @Test
    void theExportParametersCarryACertificateAndAToken() {
        Map<String, Object> parameters = service.parametersForExport("TR34_KEY_BLOCK", "RSA_2048");

        assertNotNull(parameters.get("ExportToken"));
        assertNotNull(parameters.get("SigningKeyCertificate"));
        assertNotNull(parameters.get("SigningKeyCertificateChain"));
        assertEquals("RSA_2048", parameters.get("SigningKeyAlgorithm"));
        assertNotNull(parameters.get("ParametersValidUntilTimestamp"));
        assertNotNull(Certificates
                .parseCertificate((String) parameters.get("SigningKeyCertificate")));
    }

    @Test
    void eachTokenIsNamedForTheOperationThatIssuedIt() {
        String importToken = (String) service.parametersForImport("TR34_KEY_BLOCK", "RSA_2048")
                .get("ImportToken");
        String exportToken = (String) service.parametersForExport("TR34_KEY_BLOCK", "RSA_2048")
                .get("ExportToken");

        assertTrue(importToken.startsWith("import-token-"), importToken);
        assertTrue(exportToken.startsWith("export-token-"), exportToken);
    }

    @Test
    void aMaterialTypeThatTakesNoParametersIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.parametersForImport("TR31_KEY_BLOCK", "RSA_2048"));
        AwsException refusedExport = assertThrows(AwsException.class,
                () -> service.parametersForExport("KEY_CRYPTOGRAM", "RSA_2048"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("ValidationException", refusedExport.getErrorCode());
    }

    @Test
    void anAlgorithmThatIsNotAKeyAlgorithmIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.parametersForImport("TR34_KEY_BLOCK", "AES_256"));
        AwsException refusedExport = assertThrows(AwsException.class,
                () -> service.parametersForExport("TR34_KEY_BLOCK", "AES_256"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("ValidationException", refusedExport.getErrorCode());
    }




    @Test
    void aKeyThatLeavesUnderACryptogramComesBack() {
        Map<String, Object> importParameters = service.parametersForImport("KEY_CRYPTOGRAM",
                "RSA_2048");

        PaymentCryptographyKey key = createKey("TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        PaymentCryptographyKey authority = createAuthority(
                (String) importParameters.get("WrappingKeyCertificate"));

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyCertificate", importParameters.get("WrappingKeyCertificate"));
        material.put("CertificateAuthorityPublicKeyIdentifier", authority.getKeyId());
        material.put("WrappingSpec", "RSA_OAEP_SHA_256");

        Map<String, Object> answer = service.exportKey("us-east-1", key.getKeyId(), "KeyCryptogram",
                material, "");
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        assertEquals("KEY_CRYPTOGRAM", wrapped.get("WrappedKeyMaterialFormat"));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("ImportToken", importParameters.get("ImportToken"));
        incoming.put("WrappedKeyCryptogram", wrapped.get("KeyMaterial"));
        incoming.put("WrappingKeyIdentifier", authority.getKeyId());
        incoming.put("WrappingSpec", "RSA_OAEP_SHA_256");
        incoming.put("KeyAttributes", Map.of(
                "KeyAlgorithm", "TDES_2KEY",
                "KeyClass", "SYMMETRIC_KEY",
                "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "KeyModesOfUse", Map.of("Encrypt", true, "Decrypt", true)));

        PaymentCryptographyKey imported = service.importKey("us-east-1", "KeyCryptogram", incoming);

        assertEquals(key.getKeyMaterialHex(), imported.getKeyMaterialHex());
    }



    @Test
    void anInitialKeyLeavesInsteadOfTheBaseKey() {
        // The initial key of the DUKPT derivation is derived from the base
        // derivation key, and it is the derived key that leaves.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_B0_BASE_DERIVATION_KEY");
        PaymentCryptographyKey base = service.createKey("us-east-1", attributes,
                Map.of("DeriveKey", Boolean.TRUE), true, true, Map.of());

        Map<String, Object> answer = service.exportKey("us-east-1", base.getKeyId(), "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId()), "12345678900000000001");

        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");
        // The header names the derived key: a PIN encryption key of Triple DES
        // that does not leave again.
        assertEquals("P0", block.substring(5, 7), "the usage of the derived key");
        assertEquals("T", block.substring(7, 8), "the algorithm of the derived key");
        assertEquals("N", block.substring(11, 12), "the derived key does not leave again");
        // And the check value is the derived key's, not the base key's.
        assertNotEquals(base.getKeyCheckValue(), wrapped.get("KeyCheckValue"));
    }

    @Test
    void anInitialKeyCannotBeDerivedFromAnotherKey() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_P0_PIN_ENCRYPTION_KEY");
        PaymentCryptographyKey key = service.createKey("us-east-1", attributes,
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId()),
                        "12345678900000000001"));

        assertEquals("ValidationException", refused.getErrorCode());
    }


    @Test
    void anImportTokenWorksForEveryImportUntilItExpires() {
        // A caller that asks for the parameters once may import several keys under them.
        Map<String, Object> parameters = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048");
        String token = (String) parameters.get("ImportToken");
        // The cryptogram is built under the wrapping key the parameters carry, so it opens.
        X509Certificate certificate =
                Certificates.parseCertificate((String) parameters.get("WrappingKeyCertificate"));
        String cryptogram = HexFormat.of().withUpperCase().formatHex(RsaOaep.encrypt(
                certificate.getPublicKey(),
                HexFormat.of().parseHex("00112233445566778899AABBCCDDEEFF"), "SHA-256"));

        for (int i = 0; i < 2; i++) {
            PaymentCryptographyKey imported = service.importKey("us-east-1", "KeyCryptogram",
                    Map.of("ImportToken", token,
                            "WrappedKeyCryptogram", cryptogram,
                            "KeyAttributes", Map.of("KeyAlgorithm", "AES_128",
                                    "KeyClass", "SYMMETRIC_KEY",
                                    "KeyUsage", "TR31_K1_KEY_BLOCK_PROTECTION_KEY",
                                    "KeyModesOfUse", Map.of("Wrap", true, "Unwrap", true))));
            assertNotNull(imported, "the second import was refused");
        }
    }



    @Test
    void theExpiredTokensAreSwept() {
        // A token that was issued and never quoted again would otherwise stay for the life
        // of the process, and it holds a private key.
        long lifetime = PaymentCryptographyKeyExchange.importTokenTtlMs;
        try {
            PaymentCryptographyKeyExchange.importTokenTtlMs = 0;
            service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048");
            service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048");
        } finally {
            PaymentCryptographyKeyExchange.importTokenTtlMs = lifetime;
        }

        assertEquals(1, service.issuedTokenCount(),
                "the token that expired was not swept");
    }

    @Test
    void thePrivateKeyIsNotWrittenToTheStore() throws Exception {
        // The store is a file in the working directory, and a private key in clear in it is
        // a secret on disk.
        PaymentCryptographyKey pair = createKeyPair("RSA_2048");

        String written = new ObjectMapper().writeValueAsString(pair);

        assertFalse(written.contains(pair.getKeyPrivateKeyBase64()),
                "the private key was written to the store");
        assertFalse(written.contains("keyPrivateKeyBase64"),
                "the private key was written to the store");
    }

    @Test
    void aFormTheServiceDoesNotKnowIsRefused() {
        PaymentCryptographyKey key = createKey("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Nonsense", Map.of(), ""));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    /** A symmetric key of the given algorithm and usage. */
    private PaymentCryptographyKey createKey(String algorithm, String usage) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", usage);
        return service.createKey("us-east-1", attributes,
                Map.of("Encrypt", Boolean.TRUE, "Decrypt", Boolean.TRUE), true, true, Map.of());
    }

    /** A key pair of the given algorithm. */
    private PaymentCryptographyKey createKeyPair(String algorithm) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "ASYMMETRIC_KEY_PAIR");
        attributes.put("KeyUsage", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE");
        return service.createKey("us-east-1", attributes, Map.of(), true, true, Map.of());
    }

    /**
     * The authority of the exchange: the public half of the certificate the receiver brings.
     *
     * <p>A certificate that the service minted for the import is signed by the key it names,
     * so the key that holds its public half is the authority that can vouch for it.
     */
    private PaymentCryptographyKey createAuthority(String certificate) {
        return service.importKey("us-east-1", "TrustedCertificatePublicKey", Map.of(
                "PublicKeyCertificate", certificate,
                "KeyAttributes", Map.of(
                        "KeyAlgorithm", "RSA_2048",
                        "KeyClass", "PUBLIC_KEY",
                        "KeyUsage", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE",
                        "KeyModesOfUse", Map.of("Verify", Boolean.TRUE))));
    }

}
