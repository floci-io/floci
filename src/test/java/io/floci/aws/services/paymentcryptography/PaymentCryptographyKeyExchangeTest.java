package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.paymentcryptography.crypto.Certificates;
import io.floci.aws.services.paymentcryptography.crypto.CertificatesTest;
import io.floci.aws.services.paymentcryptography.crypto.RsaOaep;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
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
        Map<String, Object> parameters = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);

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
        Map<String, Object> parameters = service.parametersForExport("TR34_KEY_BLOCK", "RSA_2048", false);

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
        String importToken = (String) service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false)
                .get("ImportToken");
        String exportToken = (String) service.parametersForExport("TR34_KEY_BLOCK", "RSA_2048", false)
                .get("ExportToken");

        assertTrue(importToken.startsWith("import-token-"), importToken);
        assertTrue(exportToken.startsWith("export-token-"), exportToken);
    }

    @Test
    void aMaterialTypeThatTakesNoParametersIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.parametersForImport("TR31_KEY_BLOCK", "RSA_2048", false));
        AwsException refusedExport = assertThrows(AwsException.class,
                () -> service.parametersForExport("KEY_CRYPTOGRAM", "RSA_2048", false));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("ValidationException", refusedExport.getErrorCode());
    }

    @Test
    void anAlgorithmThatIsNotAKeyAlgorithmIsRefused() {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.parametersForImport("TR34_KEY_BLOCK", "AES_256", false));
        AwsException refusedExport = assertThrows(AwsException.class,
                () -> service.parametersForExport("TR34_KEY_BLOCK", "AES_256", false));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("ValidationException", refusedExport.getErrorCode());
    }




    @Test
    void theBlockCarriesTheHeadersTheRequestNames() {
        // The optional blocks are in the clear and are authenticated with the cipher text, and
        // the exportability and the version sit in the header.
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyIdentifier", protection.getKeyId());
        material.put("KeyBlockHeaders", Map.of(
                "KeyExportability", "NON_EXPORTABLE",
                "KeyVersion", "01",
                "OptionalBlocks", Map.of("AB", "0011")));

        Map<String, Object> answer = service.exportKey("us-east-1",
                createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY").getKeyId(),
                "Tr31KeyBlock", material, "", "");
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");

        // The header carries the version and the exportability the request asked for.
        assertEquals("01", block.substring(9, 11), "the version of the key");
        assertEquals("N", block.substring(11, 12), "the exportability of the key");
        // The optional block sits between the header and the cipher text, and its length is
        // in hexadecimal and covers the whole block, the identifier and the length included.
        assertEquals("AB", block.substring(16, 18), "the optional block's identifier");
        assertEquals("08", block.substring(18, 20), "the optional block's length");
        // The optional blocks take up a whole number of cipher blocks, so the block carries a
        // padding block that fills the rest.
        assertEquals("02", block.substring(12, 14), "the number of optional blocks");
        assertEquals("PB080000", block.substring(24, 32), "the padding block");

        // And the block still opens, so the optional blocks are authenticated with it.
        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                        "WrappedKeyBlock", block), "", true, Map.of());
        assertEquals("TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY", imported.getKeyUsage());
    }

    /**
     * A key that leaves under a cryptogram comes back.
     *
     * <p>The import decrypts what the caller brought under the private key the parameters
     * issued, and that operation is blinded, so a wrong set of CRT values would decrypt to
     * nothing and the key would not come back.
     */
    @Test
    void aKeyThatLeavesUnderACryptogramComesBack() {        Map<String, Object> importParameters = service.parametersForImport("KEY_CRYPTOGRAM",
                "RSA_2048", false);

        PaymentCryptographyKey key = createKey("TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        PaymentCryptographyKey authority = createAuthority(
                (String) importParameters.get("WrappingKeyCertificate"));

        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyCertificate", importParameters.get("WrappingKeyCertificate"));
        material.put("CertificateAuthorityPublicKeyIdentifier", authority.getKeyId());
        material.put("WrappingSpec", "RSA_OAEP_SHA_256");

        Map<String, Object> answer = service.exportKey("us-east-1", key.getKeyId(), "KeyCryptogram",
                material, "", "");
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

        PaymentCryptographyKey imported = service.importKey("us-east-1", "KeyCryptogram", incoming, "", true, Map.of());

        assertEquals(key.getKeyMaterialHex(), imported.getKeyMaterialHex());
    }



    @Test
    void aKeyLeavesUnderAKeyTheTwoPartiesAgreed() {
        // The two parties agree a key with ECDH, and the key leaves in a block under it.
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair counterparty = PaymentCryptographyKeyPairs.ec(
                PaymentCryptographyKeyAlgorithm.ECC_NIST_P256, random);
        Certificates.Minted authorityCertificate = CertificatesTest.mint(authority.getPublic(),
                "floci key-exchange CA", authority.getPrivate(), "floci key-exchange CA");
        // The counterparty's certificate is signed by the authority, which is what lets the
        // service trust it.
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", authority.getPrivate(), "floci key-exchange CA");
        Exchange exchange = agreedExchange(authorityCertificate, counterpartyCertificate);

        Map<?, ?> wrapped = (Map<?, ?>) service.exportKey("us-east-1", exchange.key().getKeyId(),
                "DiffieHellmanTr31KeyBlock", exchange.material(), "", "").get("WrappedKey");

        assertEquals("TR31_KEY_BLOCK", wrapped.get("WrappedKeyMaterialFormat"));

        // The two parties agree the key again, so the block opens under it.
        Map<String, Object> incoming = new LinkedHashMap<>(exchange.material());
        incoming.put("WrappedKeyBlock", wrapped.get("KeyMaterial"));
        PaymentCryptographyKey imported = service.importKey("us-east-1",
                "DiffieHellmanTr31KeyBlock", incoming, "", true, Map.of());

        assertEquals(exchange.key().getKeyMaterialHex(), imported.getKeyMaterialHex());
        assertEquals(exchange.key().getKeyUsage(), imported.getKeyUsage());
    }

    @Test
    void aKeyLeavesUnderAKeyTheTwoPartiesAgreedCarriesTheHeadersTheRequestNames() {
        // The block headers the request names travel with the block under the agreed key,
        // the same as they do under a key the service holds.
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair counterparty = PaymentCryptographyKeyPairs.ec(
                PaymentCryptographyKeyAlgorithm.ECC_NIST_P256, random);
        Certificates.Minted authorityCertificate = CertificatesTest.mint(authority.getPublic(),
                "floci key-exchange CA", authority.getPrivate(), "floci key-exchange CA");
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", authority.getPrivate(), "floci key-exchange CA");
        Exchange exchange = agreedExchange(authorityCertificate, counterpartyCertificate);
        Map<String, Object> material = new LinkedHashMap<>(exchange.material());
        material.put("KeyBlockHeaders", Map.of(
                "KeyExportability", "NON_EXPORTABLE",
                "KeyVersion", "01",
                "OptionalBlocks", Map.of("AB", "0011")));

        Map<?, ?> wrapped = (Map<?, ?>) service.exportKey("us-east-1", exchange.key().getKeyId(),
                "DiffieHellmanTr31KeyBlock", material, "", "").get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");

        // The header carries the version and the exportability the request asked for, and
        // the optional block sits between the header and the cipher text.
        assertEquals("01", block.substring(9, 11), "the version of the key");
        assertEquals("N", block.substring(11, 12), "the exportability of the key");
        assertEquals("AB", block.substring(16, 18), "the optional block's identifier");
        assertEquals("08", block.substring(18, 20), "the optional block's length");
    }

    @Test
    void aCounterpartyCertificateTheAuthorityDidNotSignIsRefused() {
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair other = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair counterparty = PaymentCryptographyKeyPairs.ec(
                PaymentCryptographyKeyAlgorithm.ECC_NIST_P256, random);
        Certificates.Minted authorityCertificate = CertificatesTest.mint(authority.getPublic(),
                "floci key-exchange CA", authority.getPrivate(), "floci key-exchange CA");
        // Signed by somebody else, so the authority cannot vouch for it.
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", other.getPrivate(), "floci somebody else");
        Exchange exchange = agreedExchange(authorityCertificate, counterpartyCertificate);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", exchange.key().getKeyId(),
                        "DiffieHellmanTr31KeyBlock", exchange.material(), "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not signed by the certificate authority"),
                refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anInitialKeyLeavesInsteadOfTheBaseKey(boolean baseMayLeave) {
        // The initial key of the DUKPT derivation is derived from the base
        // derivation key, and it is the derived key that leaves. The base key may not
        // leave itself and still derive that initial key, which the export guide says
        // the standards allow.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_B0_BASE_DERIVATION_KEY");
        PaymentCryptographyKey base = service.createKey("us-east-1", attributes,
                Map.of("DeriveKey", Boolean.TRUE), baseMayLeave, true, Map.of());

        Map<String, Object> answer = service.exportKey("us-east-1", base.getKeyId(), "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId()), "12345678900000000001", "");

        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");
        // The header names the derived key: an initial derivation key of Triple DES, which
        // the receiver may derive with and may export, but not as a plain working key.
        assertEquals("B1", block.substring(5, 7), "the usage of the derived key");
        assertEquals("T", block.substring(7, 8), "the algorithm of the derived key");
        assertEquals("X", block.substring(8, 9), "the derived key is a derivable one");
        assertEquals("S", block.substring(11, 12), "the derived key is a sensitive one");
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
                        "12345678900000000001", ""));

        assertEquals("ValidationException", refused.getErrorCode());
    }


    @Test
    void anImportTokenWorksForEveryImportUntilItExpires() {
        // A caller that asks for the parameters once may import several keys under them.
        Map<String, Object> parameters = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);
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
                                    "KeyModesOfUse", Map.of("Wrap", true, "Unwrap", true))), "", true, Map.of());
            assertNotNull(imported, "the second import was refused");
        }
    }



    @Test
    void theExpiredTokensAreSwept() {
        // A token that was issued and never quoted again would otherwise stay for the life
        // of the process, and it holds a private key. The clock moves past the token's life,
        // so the token is expired and the next issue sweeps it.
        ManualClock clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
        PaymentCryptographyService timed = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                new SecureRandom(),
                new PaymentCryptographyRequestContext(),
                new CertificateGenerator(),
                clock);
        timed.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);
        clock.advance(Duration.ofDays(31));
        timed.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);

        assertEquals(1, timed.issuedTokenCount(),
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
    void aKeyPairHasACertificate() {
        PaymentCryptographyKey key = createKeyPair("RSA_2048");

        Map<String, Object> answer = service.publicKeyCertificate(key);

        String certificate = (String) answer.get("KeyCertificate");
        assertNotNull(certificate);
        assertNotNull(Certificates.parseCertificate(certificate));
    }

    @Test
    void aFormTheServiceDoesNotKnowIsRefused() {
        PaymentCryptographyKey key = createKey("AES_256", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Nonsense", Map.of(), "", ""));
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
        return createKeyPair(algorithm, "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE");
    }

    /** A key pair of the given algorithm, for the given usage. */
    private PaymentCryptographyKey createKeyPair(String algorithm, String usage) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "ASYMMETRIC_KEY_PAIR");
        attributes.put("KeyUsage", usage);
        // The modes are the ones the purpose allows: a key agreement key derives, a signing
        // key signs and verifies.
        Map<String, Boolean> modes = "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT".equals(usage)
                ? Map.of("DeriveKey", true)
                : Map.of("Sign", true, "Verify", true);
        return service.createKey("us-east-1", attributes, modes, true, true, Map.of());
    }

    /**
     * The authority of the exchange: the public half of the certificate the receiver brings.
     *
     * <p>A certificate that the service minted for the import is signed by the key it names,
     * so the key that holds its public half is the authority that can vouch for it.
     */
    private PaymentCryptographyKey createAuthority(String certificate) {
        return createAuthority(certificate, true);
    }

    /** An authority that vouches for a certificate, enabled or not. */
    private PaymentCryptographyKey createAuthority(String certificate, boolean enabled) {
        return service.importKey("us-east-1", "TrustedCertificatePublicKey", Map.of(
                "PublicKeyCertificate", certificate,
                "KeyAttributes", authorityAttributes()), "", enabled, Map.of());
    }

    /** The attributes an authority carries: a public key that may verify. */
    private static Map<String, Object> authorityAttributes() {
        return Map.of(
                "KeyAlgorithm", "RSA_2048",
                "KeyClass", "PUBLIC_KEY",
                "KeyUsage", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE",
                "KeyModesOfUse", Map.of("Verify", Boolean.TRUE));
    }

    /**
     * A key exchange under a key the two parties agreed with ECDH.
     *
     * <p>The service trusts the certificate the counterparty brings because the authority
     * signed it, so the helper imports the authority, makes the agreement key and builds the
     * material of the export.
     *
     * @param authorityCertificate the certificate of the authority that vouches for the
     *                              counterparty
     * @param counterpartyCertificate the certificate the counterparty brings
     * @return the material of the export, and the key that leaves under it
     */
    private Exchange agreedExchange(Certificates.Minted authorityCertificate,
                                    Certificates.Minted counterpartyCertificate) {
        PaymentCryptographyKey authority = createAuthority(authorityCertificate.pem());
        PaymentCryptographyKey agreement = createKeyPair("ECC_NIST_P256",
                "TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT");
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("PrivateKeyIdentifier", agreement.getKeyId());
        material.put("CertificateAuthorityPublicKeyIdentifier", authority.getKeyId());
        material.put("PublicKeyCertificate", counterpartyCertificate.pem());
        material.put("DeriveKeyAlgorithm", "AES_128");
        material.put("KeyDerivationFunction", "ANSI_X963");
        material.put("KeyDerivationHashAlgorithm", "SHA_256");
        material.put("DerivationData", Map.of(
                "SharedInformation", "00112233445566778899AABBCCDDEEFF"));
        return new Exchange(material,
                createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"));
    }

    /** The material of a key exchange, and the key that leaves under it. */
    private record Exchange(Map<String, Object> material, PaymentCryptographyKey key) {
    }


    @Test
    void anAuthorityThatMayNotVerifyIsRefused() {
        // The authority vouches for the other party's certificate, so it has to be allowed to
        // verify.
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        // The cryptogram is wrapped under the receiver's certificate, and a certificate over
        // a 3072-bit modulus is as strong as the key it wraps.
        KeyPair counterparty = PaymentCryptographyKeyPairs.rsa(3072, random);
        Instant now = Instant.now();
        Certificates.Minted caCertificate = CertificatesTest.mint(authority.getPublic(), "floci CA", authority.getPrivate(), "floci CA");
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(), "floci counterparty", authority.getPrivate(), "floci CA");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "RSA_2048");
        attributes.put("KeyClass", "PUBLIC_KEY");
        attributes.put("KeyUsage", "TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE");
        attributes.put("KeyModesOfUse", Map.of("Encrypt", Boolean.TRUE));
        PaymentCryptographyKey ca = service.importKey("us-east-1", "TrustedCertificatePublicKey",
                Map.of("PublicKeyCertificate", caCertificate.pem(), "KeyAttributes", attributes),
                "", true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", createKey("AES_128",
                        "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY").getKeyId(), "KeyCryptogram",
                        Map.of("WrappingKeyCertificate", counterpartyCertificate.pem(),
                                "CertificateAuthorityPublicKeyIdentifier", ca.getKeyId()), "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("Verify"), refused.getMessage());
    }

    @Test
    void anExpiredCertificateIsRefused() {
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        // The cryptogram is wrapped under the receiver's certificate, and a certificate over
        // a 3072-bit modulus is as strong as the key it wraps.
        KeyPair counterparty = PaymentCryptographyKeyPairs.rsa(3072, random);
        Instant now = Instant.now();
        Certificates.Minted caCertificate = CertificatesTest.mint(authority.getPublic(), "floci CA", authority.getPrivate(), "floci CA");
        // The counterparty's certificate has expired, so it protects nothing.
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", authority.getPrivate(), "floci CA",
                now.minusSeconds(7200), now.minusSeconds(3600));
        PaymentCryptographyKey ca = createAuthority(caCertificate.pem());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", createKey("AES_128",
                        "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY").getKeyId(), "KeyCryptogram",
                        Map.of("WrappingKeyCertificate", counterpartyCertificate.pem(),
                                "CertificateAuthorityPublicKeyIdentifier", ca.getKeyId()), "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("validity window"), refused.getMessage());
    }

    @Test
    void aCertificateThatDoesNotParseIsRefused() {
        // The text is not a certificate, so it is refused rather than stored as one.
        AwsException refused = assertThrows(AwsException.class,
                () -> createAuthority("not a certificate at all"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not a certificate"), refused.getMessage());
    }

    @Test
    void aDisabledKeyDoesNotLeave() {
        // A disabled key does no work, so it may not be exported.
        PaymentCryptographyKey key = service.createKey("us-east-1",
                Map.of("KeyAlgorithm", "AES_128", "KeyClass", "SYMMETRIC_KEY",
                        "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                        "KeyModesOfUse", Map.of("Encrypt", true, "Decrypt", true)),
                Map.of("Encrypt", true, "Decrypt", true), true, false, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId()), "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test
    void anImportedKeyGetsEveryModeTheBlockStandsFor() {
        // The mode of use character of the block says what the key may be used for, and
        // both spellings of a direction stand for it. A block that says B gives the key all
        // four of Encrypt, Decrypt, Wrap and Unwrap, whichever use the key has.
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        String block = blockOf(key);
        assertEquals("B", block.substring(8, 9), "the mode of use of the block");

        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(), "WrappedKeyBlock", block),
                "", true, Map.of());

        Map<String, Boolean> modes = imported.getKeyModesOfUse();
        assertEquals(Boolean.TRUE, modes.get("Encrypt"), "the imported key may encrypt");
        assertEquals(Boolean.TRUE, modes.get("Decrypt"), "the imported key may decrypt");
        assertEquals(Boolean.TRUE, modes.get("Wrap"), "the imported key may wrap");
        assertEquals(Boolean.TRUE, modes.get("Unwrap"), "the imported key may unwrap");
    }

    @Test
    void anExportMayNotGiveAKeyAPermissionItDoesNotHave() {
        // A key that may only encrypt is refused an export that says it may decrypt: the
        // modes of use a request names may only narrow the ones the key has.
        PaymentCryptographyKey key = service.createKey("us-east-1",
                Map.of("KeyAlgorithm", "AES_128", "KeyClass", "SYMMETRIC_KEY",
                        "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                        "KeyModesOfUse", Map.of("Encrypt", true)),
                Map.of("Encrypt", true), true, true, Map.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                                "KeyBlockHeaders", Map.of("KeyModesOfUse",
                                        Map.of("Encrypt", true, "Decrypt", true))),
                        "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("Decrypt"), refused.getMessage());
    }

    @Test
    void anExportMayNotLoosenTheExportability() {
        // The exportability says what the receiver may do with the key once it has it, and
        // the model states SENSITIVE as the loosest of the three: it lets the receiver
        // export under a key block, a TR-34 block or a cryptogram, where EXPORTABLE lets it
        // export under a key block or a TR-34 block only. So an exportable key may not
        // leave as SENSITIVE, because that is looser than what the key has.
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                                "KeyBlockHeaders", Map.of("KeyExportability", "SENSITIVE")),
                        "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("looser"), refused.getMessage());
    }

    @Test
    void anExportMayTightenTheExportability() {
        // The other way round: the initial key of the DUKPT derivation is sensitive, and
        // EXPORTABLE is stricter than that, so it may leave as one.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "TDES_2KEY");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_B0_BASE_DERIVATION_KEY");
        PaymentCryptographyKey base = service.createKey("us-east-1", attributes,
                Map.of("DeriveKey", Boolean.TRUE), true, true, Map.of());

        Map<String, Object> answer = service.exportKey("us-east-1", base.getKeyId(), "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                        "KeyBlockHeaders", Map.of("KeyExportability", "EXPORTABLE")),
                "12345678900000000001", "");
        String block = (String) ((Map<?, ?>) answer.get("WrappedKey")).get("KeyMaterial");

        assertEquals("E", block.substring(11, 12), "the exportability of the derived key");
    }

    @Test
    void aDisabledAuthorityDoesNotVouchForACertificate() {
        // The authority does work when it vouches for a certificate, and a disabled key does
        // no work, so a disabled authority is refused.
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair counterparty = PaymentCryptographyKeyPairs.rsa(2048, random);
        Certificates.Minted caCertificate = CertificatesTest.mint(authority.getPublic(), "floci CA",
                authority.getPrivate(), "floci CA");
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", authority.getPrivate(), "floci CA");
        PaymentCryptographyKey ca = createAuthority(caCertificate.pem(), false);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.importKey("us-east-1", "TrustedCertificatePublicKey",
                        Map.of("PublicKeyCertificate", counterpartyCertificate.pem(),
                                "CertificateAuthorityPublicKeyIdentifier", ca.getKeyId(),
                                "KeyAttributes", authorityAttributes()),
                        "", true, Map.of()));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test
    void anImportedKeySaysItCameFromOutsideTheService() {
        // A key that came in says its origin is EXTERNAL, and a key the service made says
        // it is AWS_PAYMENT_CRYPTOGRAPHY.
        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                        "WrappedKeyBlock", blockOf(createKey("AES_128",
                                "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY"))),
                "", true, Map.of());

        assertEquals("EXTERNAL", imported.getKeyOrigin(), "the origin of an imported key");
        assertEquals("AWS_PAYMENT_CRYPTOGRAPHY", protection.getKeyOrigin(),
                "the origin of a key the service made");
    }

    @Test
    void aCryptogramCarriesNoCheckValue() {
        // A key block carries the check value of the key it carries, and a cryptogram
        // carries no key block at all, so it carries no check value either.
        SecureRandom random = new SecureRandom();
        KeyPair authority = PaymentCryptographyKeyPairs.rsa(2048, random);
        KeyPair counterparty = PaymentCryptographyKeyPairs.rsa(3072, random);
        Certificates.Minted caCertificate = CertificatesTest.mint(authority.getPublic(), "floci CA",
                authority.getPrivate(), "floci CA");
        Certificates.Minted counterpartyCertificate = CertificatesTest.mint(counterparty.getPublic(),
                "floci counterparty", authority.getPrivate(), "floci CA");
        PaymentCryptographyKey ca = createAuthority(caCertificate.pem());

        Map<String, Object> answer = service.exportKey("us-east-1",
                createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY").getKeyId(),
                "KeyCryptogram",
                Map.of("WrappingKeyCertificate", counterpartyCertificate.pem(),
                        "CertificateAuthorityPublicKeyIdentifier", ca.getKeyId()), "", "");

        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        assertEquals("KEY_CRYPTOGRAM", wrapped.get("WrappedKeyMaterialFormat"));
        assertFalse(wrapped.containsKey("KeyCheckValue"), "the cryptogram carries a check value");
        assertFalse(wrapped.containsKey("KeyCheckValueAlgorithm"),
                "the cryptogram carries a check value algorithm");
    }

    /** The key block a key leaves under the protection key of the test. */
    private String blockOf(PaymentCryptographyKey key) {
        Map<String, Object> answer = service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId()), "", "");
        return (String) ((Map<?, ?>) answer.get("WrappedKey")).get("KeyMaterial");
    }

    @Test
    void aDataEncryptionKeyMayNotWrapAnExport() {
        // The wrapping key of a key block is a key encryption key, and a data encryption key
        // is not one.
        PaymentCryptographyKey data = service.createKey("us-east-1",
                Map.of("KeyAlgorithm", "AES_256", "KeyClass", "SYMMETRIC_KEY",
                        "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                        "KeyModesOfUse", Map.of("Encrypt", true, "Decrypt", true)),
                Map.of("Encrypt", true, "Decrypt", true), true, true, Map.of());
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", data.getKeyId()), "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("not a key encryption key"), refused.getMessage());
    }

    @Test
    void anImportedKeyThatMayNotLeaveStaysThatWay() {
        // The exportability is a member of the form, beside the attributes and not inside
        // them.
        Map<String, Object> importParameters = service.parametersForImport("KEY_CRYPTOGRAM",
                "RSA_2048", false);
        PaymentCryptographyKey key = createKey("TDES_2KEY", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        PaymentCryptographyKey authority = createAuthority(
                (String) importParameters.get("WrappingKeyCertificate"));
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyCertificate", importParameters.get("WrappingKeyCertificate"));
        material.put("CertificateAuthorityPublicKeyIdentifier", authority.getKeyId());
        material.put("WrappingSpec", "RSA_OAEP_SHA_256");
        Map<String, Object> answer = service.exportKey("us-east-1", key.getKeyId(), "KeyCryptogram",
                material, "", "");
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("ImportToken", importParameters.get("ImportToken"));
        incoming.put("WrappedKeyCryptogram", wrapped.get("KeyMaterial"));
        incoming.put("WrappingKeyIdentifier", authority.getKeyId());
        incoming.put("WrappingSpec", "RSA_OAEP_SHA_256");
        incoming.put("Exportable", Boolean.FALSE);
        incoming.put("KeyAttributes", Map.of(
                "KeyAlgorithm", "TDES_2KEY",
                "KeyClass", "SYMMETRIC_KEY",
                "KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY",
                "KeyModesOfUse", Map.of("Encrypt", true, "Decrypt", true)));

        PaymentCryptographyKey imported = service.importKey("us-east-1", "KeyCryptogram", incoming,
                "", true, Map.of());

        assertFalse(imported.isExportable(), "the key was imported as one that may leave");
    }

    @Test
    void aKeyOfAnotherPurposeMayNotAgree() {
        // The key that agrees has to be a key agreement key.
        PaymentCryptographyKey signing = createKeyPair("ECC_NIST_P256");
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "DiffieHellmanTr31KeyBlock",
                        Map.of("PrivateKeyIdentifier", signing.getKeyId(),
                                "CertificateAuthorityPublicKeyIdentifier", signing.getKeyId(),
                                "PublicKeyCertificate", "not a certificate",
                                "DeriveKeyAlgorithm", "AES_128",
                                "KeyDerivationFunction", "ANSI_X963",
                                "KeyDerivationHashAlgorithm", "SHA_256",
                                "DerivationData", Map.of(
                                        "SharedInformation", "00112233445566778899AABBCCDDEEFF")),
                        "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("key agreement"), refused.getMessage());
    }

    @Test
    void aKeyThatIsNotACurveKeyIsRefused() {
        // An RSA key cannot take part in an agreement, and it is refused rather than read as
        // a curve key.
        PaymentCryptographyKey rsa = createKeyPair("RSA_2048");
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "DiffieHellmanTr31KeyBlock",
                        Map.of("PrivateKeyIdentifier", rsa.getKeyId(),
                                "CertificateAuthorityPublicKeyIdentifier", rsa.getKeyId(),
                                "PublicKeyCertificate", "not a certificate",
                                "DeriveKeyAlgorithm", "AES_128",
                                "KeyDerivationFunction", "ANSI_X963",
                                "KeyDerivationHashAlgorithm", "SHA_256",
                                "DerivationData", Map.of(
                                        "SharedInformation", "00112233445566778899AABBCCDDEEFF")),
                        "", ""));

        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void anExportWithAnotherCheckValueAlgorithmIsRefused() {
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId()), "", "ANSI_X9_24"));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("check value algorithm"), refused.getMessage());
    }

    @Test
    void anImportWithAnotherCheckValueAlgorithmIsRefused() {
        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        Map<String, Object> answer = service.exportKey("us-east-1", key.getKeyId(), "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId()), "", "");
        Map<?, ?> wrapped = (Map<?, ?>) answer.get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");

        AwsException refused = assertThrows(AwsException.class,
                () -> service.importKey("us-east-1", "Tr31KeyBlock",
                        Map.of("WrappingKeyIdentifier", protection.getKeyId(),
                                "WrappedKeyBlock", block),
                        "ANSI_X9_24", true, Map.of()));

        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("check value algorithm"), refused.getMessage());
    }

    @Test
    void theParametersCanBeReused() {
        // A caller that asks for the last parameters gets them, token and certificate alike.
        Map<String, Object> first = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);
        Map<String, Object> again = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", true);
        Map<String, Object> fresh = service.parametersForImport("KEY_CRYPTOGRAM", "RSA_2048", false);

        assertEquals(first.get("ImportToken"), again.get("ImportToken"));
        assertEquals(first.get("WrappingKeyCertificate"), again.get("WrappingKeyCertificate"));
        assertNotEquals(first.get("ImportToken"), fresh.get("ImportToken"));
    }

    @Test
    void theExportParametersOutliveTheirCertificate() {
        // The parameters are good until the certificate they carry is not.
        Map<String, Object> parameters = service.parametersForExport("TR34_KEY_BLOCK", "RSA_2048",
                false);
        String certificate = (String) parameters.get("SigningKeyCertificate");
        long validUntil = ((Number) parameters.get("ParametersValidUntilTimestamp")).longValue();
        X509Certificate parsed = Certificates.parseCertificate(certificate);

        assertTrue(parsed.getNotAfter().toInstant().getEpochSecond() >= validUntil,
                "the certificate expires before the parameters do");
    }

    @Test
    void aSensitiveKeyMayLeaveTheServiceAgain() {
        // The exportability of a block says whether the key that comes in may leave again.
        // A sensitive one may: it is only under a trusted key that it may not be worked with.
        //
        // The block is made here rather than exported, because the service writes the
        // exportability of the block it makes itself: a key it exports is exportable, and
        // the only key it exports as sensitive is the initial DUKPT key, whose usage the
        // model has no name for. A counterparty's block may carry the sensitive one all the
        // same.
        String block = PaymentCryptographyTr31.wrap(
                PaymentCryptographyKeyRules.material(
                        createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY")),
                PaymentCryptographyKeyRules.material(protection),
                true, "A", "D0", "B", "S");

        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(), "WrappedKeyBlock", block),
                "", true, Map.of());

        assertTrue(imported.isExportable(), "the sensitive key may not leave the service again");
    }

    @Test
    void aKeyThatIsNotExportableDoesNotComeBackExportable() {
        // The exportability of a block says whether the key that comes in may leave again.
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyIdentifier", protection.getKeyId());
        material.put("KeyBlockHeaders", Map.of("KeyExportability", "NON_EXPORTABLE"));

        PaymentCryptographyKey key = createKey("AES_128", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        Map<?, ?> wrapped = (Map<?, ?>) service.exportKey("us-east-1", key.getKeyId(),
                "Tr31KeyBlock", material, "", "").get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");

        PaymentCryptographyKey imported = service.importKey("us-east-1", "Tr31KeyBlock",
                Map.of("WrappingKeyIdentifier", protection.getKeyId(), "WrappedKeyBlock", block),
                "", true, Map.of());

        assertFalse(imported.isExportable(), "the key that may not leave came back exportable");
    }

    @Test
    void theModesOfUseTheRequestNamesAreReadTheWayTheKeyHasThem() {
        // A key encryption key wraps and unwraps rather than encrypting and decrypting, so
        // the modes the request names for one are read that way too.
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("WrappingKeyIdentifier", protection.getKeyId());
        material.put("KeyBlockHeaders", Map.of(
                "KeyModesOfUse", Map.of("Wrap", true, "Unwrap", true)));

        PaymentCryptographyKey key = createKey("AES_128", "TR31_K0_KEY_ENCRYPTION_KEY");
        Map<?, ?> wrapped = (Map<?, ?>) service.exportKey("us-east-1", key.getKeyId(),
                "Tr31KeyBlock", material, "", "").get("WrappedKey");
        String block = (String) wrapped.get("KeyMaterial");

        assertEquals("B", block.substring(8, 9), "the mode of use of the key");
    }

    @Test
    void theCertificateOfAKeyCarriesItsChain() {
        // The certificate the service mints is signed by the service, so the chain is the
        // certificate itself.
        PaymentCryptographyKey key = createKeyPair("RSA_2048");

        Map<String, Object> answer = service.publicKeyCertificate(key);

        assertNotNull(answer.get("KeyCertificate"), "the certificate is missing");
        assertNotNull(answer.get("KeyCertificateChain"), "the chain of the certificate is missing");
        X509Certificate parsed = Certificates.parseCertificate(
                (String) answer.get("KeyCertificate"));
        long validity = parsed.getNotAfter().toInstant().getEpochSecond()
                - parsed.getNotBefore().toInstant().getEpochSecond();
        // The certificate is good for ninety days, and it starts an hour early so that a
        // clock a little behind the service's still accepts it.
        assertEquals(90L * 24 * 3600 + 3600, validity,
                "the certificate is not valid for ninety days");
    }

}
