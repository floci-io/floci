package com.floci.test;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.paymentcryptography.PaymentCryptographyClient;
import software.amazon.awssdk.services.paymentcryptography.model.Alias;
import software.amazon.awssdk.services.paymentcryptography.model.DeriveKeyUsage;
import software.amazon.awssdk.services.paymentcryptography.model.ExportKeyResponse;
import software.amazon.awssdk.services.paymentcryptography.model.GetParametersForExportResponse;
import software.amazon.awssdk.services.paymentcryptography.model.GetParametersForImportResponse;
import software.amazon.awssdk.services.paymentcryptography.model.GetPublicKeyCertificateResponse;
import software.amazon.awssdk.services.paymentcryptography.model.ImportKeyResponse;
import software.amazon.awssdk.services.paymentcryptography.model.Key;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyAttributes;
import software.amazon.awssdk.services.paymentcryptography.model.KeyCheckValueAlgorithm;
import software.amazon.awssdk.services.paymentcryptography.model.KeyClass;
import software.amazon.awssdk.services.paymentcryptography.model.KeyMaterialType;
import software.amazon.awssdk.services.paymentcryptography.model.KeyState;
import software.amazon.awssdk.services.paymentcryptography.model.KeyUsage;
import software.amazon.awssdk.services.paymentcryptography.model.ListKeysResponse;
import software.amazon.awssdk.services.paymentcryptography.model.Tag;
import software.amazon.awssdk.services.paymentcryptography.model.WrappedKeyMaterialFormat;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Payment Cryptography control plane through the AWS SDK.
 *
 * <p>The real service is an awsJson 1.0 service whose operations are targeted by
 * {@code PaymentCryptographyControlPlane.<Operation>}, so a real client is the only way to
 * prove that the wire format, the request shape and the answer shape all agree.
 */
@DisplayName("Payment Cryptography")
class PaymentCryptographyTest {

    private static PaymentCryptographyClient client;
    private static final List<String> keyArns = new ArrayList<>();
    private static final List<String> aliasNames = new ArrayList<>();

    @BeforeAll
    static void setup() {
        client = TestFixtures.paymentCryptographyClient();
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        for (String alias : aliasNames) {
            try {
                client.deleteAlias(a -> a.aliasName(alias));
            } catch (Exception ignored) {
                // The key may already be gone, and real AWS refuses to delete an alias of one.
            }
        }
        for (String arn : keyArns) {
            try {
                client.deleteKey(k -> k.keyIdentifier(arn));
            } catch (Exception ignored) {
                // Real AWS wants a key disabled before it is deleted, and wants it gone by now.
            }
        }
        client.close();
    }

    private static Key createAesKey() {
        return createKey(KeyAlgorithm.AES_256);
    }

    /**
     * A Triple DES key, whose check value is the ANSI X9.24 one.
     *
     * <p>The key is created here because creating it computes that value, and the cipher
     * behind it is the one the native image has to carry.
     */
    private static Key createTdesKey() {
        return createKey(KeyAlgorithm.TDES_2_KEY);
    }

    /** The key a key block is wrapped under. */
    private static Key createWrappingKey() {
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_K1_KEY_BLOCK_PROTECTION_KEY)
                        .keyModesOfUse(m -> m.wrap(true).unwrap(true)))
                .exportable(true))
                .key();
        keyArns.add(key.keyArn());
        return key;
    }

    /**
     * The public half of a certificate, held by the service as a key.
     *
     * <p>The certificate is the one the service minted for the occasion, and it stands on
     * its own, so it comes in as the public key of a root certificate.
     */
    private static Key importPublicKey(String certificate) {
        Key key = client.importKey(r -> r
                .keyMaterial(m -> m.rootCertificatePublicKey(b -> b
                        .publicKeyCertificate(certificate)
                        .keyAttributes(a -> a
                                .keyAlgorithm(KeyAlgorithm.RSA_2048)
                                .keyClass(KeyClass.PUBLIC_KEY)
                                .keyUsage(KeyUsage.TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE)
                                .keyModesOfUse(modes -> modes.verify(true))))))
                .key();
        keyArns.add(key.keyArn());
        return key;
    }

    /** A key of the given algorithm, for the data the cipher protects. */
    private static Key createKey(KeyAlgorithm algorithm) {
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(algorithm)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true).decrypt(true)))
                .exportable(true))
                .key();
        keyArns.add(key.keyArn());
        return key;
    }

    /** The key identifier of an ARN: the part after the last slash. */
    private static String keyIdOf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    @Test
    void aKeyIsCreatedAndReadBackWithItsModesOfUse() {
        Key created = createAesKey();

        assertThat(created.keyArn()).isNotBlank();
        assertThat(created.keyState()).isEqualTo(KeyState.CREATE_COMPLETE);
        assertThat(created.keyCheckValueAlgorithm()).isEqualTo(KeyCheckValueAlgorithm.CMAC);
        assertThat(created.keyCheckValue()).isNotBlank();
        assertThat(created.enabled()).isTrue();
        assertThat(created.exportable()).isTrue();

        KeyAttributes attributes = created.keyAttributes();
        assertThat(attributes.keyAlgorithm()).isEqualTo(KeyAlgorithm.AES_256);
        assertThat(attributes.keyClass()).isEqualTo(KeyClass.SYMMETRIC_KEY);
        assertThat(attributes.keyUsage()).isEqualTo(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY);
        // The modes of use are a nested structure, and the answer must carry the ones asked for.
        assertThat(attributes.keyModesOfUse()).isNotNull();
        assertThat(attributes.keyModesOfUse().encrypt()).isTrue();
        assertThat(attributes.keyModesOfUse().decrypt()).isTrue();

        Key read = client.getKey(k -> k.keyIdentifier(created.keyArn())).key();
        assertThat(read.keyArn()).isEqualTo(created.keyArn());
        assertThat(read.keyAttributes().keyAlgorithm()).isEqualTo(KeyAlgorithm.AES_256);
        assertThat(read.keyAttributes().keyModesOfUse().encrypt()).isTrue();
        assertThat(read.keyAttributes().keyModesOfUse().decrypt()).isTrue();
        assertThat(read.keyCheckValue()).isEqualTo(created.keyCheckValue());
    }

    @Test
    void aTripleDesKeyIsCreatedAndItsCheckValueIsTheX924One() {
        // The check value of a Triple DES key is the ANSI X9.24 one, so it is the leftmost
        // three bytes of encrypting eight zero bytes with the key. The native image has to
        // carry that cipher, so the key is created here and the value is read back.
        Key created = createTdesKey();

        assertThat(created.keyArn()).isNotBlank();
        assertThat(created.keyState()).isEqualTo(KeyState.CREATE_COMPLETE);
        assertThat(created.keyCheckValueAlgorithm()).isEqualTo(KeyCheckValueAlgorithm.ANSI_X9_24);
        assertThat(created.keyCheckValue()).hasSize(6);
        assertThat(created.keyAttributes().keyAlgorithm()).isEqualTo(KeyAlgorithm.TDES_2_KEY);

        Key read = client.getKey(k -> k.keyIdentifier(created.keyArn())).key();
        assertThat(read.keyCheckValue()).isEqualTo(created.keyCheckValue());
        assertThat(read.keyCheckValueAlgorithm()).isEqualTo(KeyCheckValueAlgorithm.ANSI_X9_24);
    }

    @Test
    void theImportAndExportParametersAreServed() {
        GetParametersForImportResponse importParameters = client.getParametersForImport(r -> r
                .keyMaterialType(KeyMaterialType.KEY_CRYPTOGRAM)
                .wrappingKeyAlgorithm(KeyAlgorithm.RSA_2048));

        assertThat(importParameters.importToken()).startsWith("import-token-");
        assertThat(importParameters.wrappingKeyAlgorithm()).isEqualTo(KeyAlgorithm.RSA_2048);
        assertThat(importParameters.wrappingKeyCertificate()).isNotBlank();
        assertThat(importParameters.wrappingKeyCertificateChain()).isNotBlank();
        assertThat(importParameters.parametersValidUntilTimestamp()).isNotNull();

        GetParametersForExportResponse exportParameters = client.getParametersForExport(r -> r
                .keyMaterialType(KeyMaterialType.TR34_KEY_BLOCK)
                .signingKeyAlgorithm(KeyAlgorithm.RSA_2048));

        assertThat(exportParameters.exportToken()).startsWith("export-token-");
        assertThat(exportParameters.signingKeyAlgorithm()).isEqualTo(KeyAlgorithm.RSA_2048);
        assertThat(exportParameters.signingKeyCertificate()).isNotBlank();
        assertThat(exportParameters.signingKeyCertificateChain()).isNotBlank();
        assertThat(exportParameters.parametersValidUntilTimestamp()).isNotNull();
    }

    @Test
    void aKeyLeavesTheServiceAndComesBackThroughTheKeyExchange() {
        Key wrapping = createWrappingKey();
        Key key = createAesKey();

        ExportKeyResponse exported = client.exportKey(r -> r
                .exportKeyIdentifier(key.keyArn())
                .keyMaterial(m -> m.tr31KeyBlock(b -> b.wrappingKeyIdentifier(wrapping.keyArn()))));

        assertThat(exported.wrappedKey().wrappedKeyMaterialFormat())
                .isEqualTo(WrappedKeyMaterialFormat.TR31_KEY_BLOCK);
        assertThat(exported.wrappedKey().wrappingKeyArn()).isEqualTo(wrapping.keyArn());
        assertThat(exported.wrappedKey().keyMaterial()).isNotBlank();
        assertThat(exported.wrappedKey().keyCheckValue()).isNotBlank();

        ImportKeyResponse imported = client.importKey(r -> r
                .keyMaterial(m -> m.tr31KeyBlock(b -> b
                        .wrappingKeyIdentifier(wrapping.keyArn())
                        .wrappedKeyBlock(exported.wrappedKey().keyMaterial()))));

        assertThat(imported.key().keyArn()).isNotBlank();
        assertThat(imported.key().keyAttributes().keyUsage())
                .isEqualTo(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY);
    }

    @Test
    void aKeyIsCreatedDisabledAndWithTags() {
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.AES_256)
                        .keyClass(KeyClass.SYMMETRIC_KEY)
                        .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                        .keyModesOfUse(m -> m.encrypt(true)))
                .exportable(false)
                .enabled(false)
                .tags(Tag.builder().key("owner").value("team").build()))
                .key();
        keyArns.add(key.keyArn());

        assertThat(key.enabled()).isFalse();
        assertThat(key.exportable()).isFalse();

        Map<String, String> tags = client.listTagsForResource(t -> t.resourceArn(key.keyArn())).tags()
                .stream()
                .collect(Collectors.toMap(Tag::key, Tag::value, (a, b) -> a));
        assertThat(tags).containsEntry("owner", "team");
    }

    @Test
    void theKeysAreListedAPageAtATime() {
        createAesKey();
        createAesKey();

        ListKeysResponse first = client.listKeys(r -> r.maxResults(1));
        assertThat(first.keys()).hasSize(1);
        assertThat(first.nextToken()).isNotNull();

        ListKeysResponse second = client.listKeys(r -> r.maxResults(1).nextToken(first.nextToken()));
        assertThat(second.keys()).hasSize(1);
    }

    @Test
    void theKeysOfTheRegionAreListed() {
        Key key = createAesKey();

        assertThat(client.listKeys(r -> r.maxResults(100)).keys())
                .anySatisfy(listed -> assertThat(listed.keyArn()).isEqualTo(key.keyArn()));
    }

    @Test
    void theAliasOfAKeyIsCreatedAndReadBack() {
        Key key = createAesKey();
        String alias = "alias/compat/" + keyIdOf(key.keyArn());
        aliasNames.add(alias);

        client.createAlias(a -> a.aliasName(alias).keyArn(key.keyArn()));

        Alias read = client.getAlias(a -> a.aliasName(alias)).alias();
        assertThat(read.aliasName()).isEqualTo(alias);
        assertThat(read.keyArn()).isEqualTo(key.keyArn());
        assertThat(client.listAliases(r -> r.maxResults(100)).aliases())
                .anySatisfy(listed -> assertThat(listed.aliasName()).isEqualTo(alias));
    }

    @Test
    void aKeyIsTaggedAndTheTagsAreListed() {
        Key key = createAesKey();

        client.tagResource(t -> t
                .resourceArn(key.keyArn())
                .tags(Tag.builder().key("owner").value("compat").build()));

        Map<String, String> tags = client.listTagsForResource(t -> t.resourceArn(key.keyArn())).tags()
                .stream()
                .collect(Collectors.toMap(Tag::key, Tag::value, (a, b) -> a));
        assertThat(tags).containsEntry("owner", "compat");
    }

    /**
     * A delete answers with the key it deleted, its state DELETE_PENDING and the
     * moment it is deleted, so a caller reads the key and not an empty answer.
     */
    @Test
    void aDeletedKeyIsAnsweredWithTheKey() {
        Key created = createAesKey();

        Key deleted = client.deleteKey(k -> k.keyIdentifier(created.keyArn())).key();

        assertThat(deleted).isNotNull();
        assertThat(deleted.keyArn()).isEqualTo(created.keyArn());
        assertThat(deleted.keyState()).isEqualTo(KeyState.DELETE_PENDING);
        assertThat(deleted.deletePendingTimestamp()).isNotNull();
        assertThat(deleted.enabled()).isFalse();
    }

    /** A listing names the state of the keys it wants, and the service answers with those keys alone. */
    @Test
    void theKeysAreListedByTheirState() {
        Key key = createAesKey();

        assertThat(client.listKeys(r -> r.keyState(KeyState.CREATE_COMPLETE).maxResults(100)).keys())
                .anySatisfy(listed -> assertThat(listed.keyArn()).isEqualTo(key.keyArn()));
        assertThat(client.listKeys(r -> r.keyState(KeyState.DELETE_PENDING).maxResults(100)).keys())
                .noneSatisfy(listed -> assertThat(listed.keyArn()).isEqualTo(key.keyArn()));
    }

    /**
     * The key of an alias is optional, so an update naming no key removes the key
     * the alias names, and the alias stays.
     */
    @Test
    void anAliasIsDetachedFromItsKeyByAnUpdateNamingNoKey() {
        Key key = createAesKey();
        String alias = "alias/compat/detached-" + keyIdOf(key.keyArn());
        aliasNames.add(alias);

        client.createAlias(a -> a.aliasName(alias).keyArn(key.keyArn()));

        Alias detached = client.updateAlias(a -> a.aliasName(alias)).alias();

        assertThat(detached.aliasName()).isEqualTo(alias);
        assertThat(detached.keyArn()).isNull();
        assertThat(client.getAlias(a -> a.aliasName(alias)).alias().aliasName()).isEqualTo(alias);
    }

    /**
     * A region the creating client did not sign for. It has to differ from the
     * region of the suite, or the key would be in that region too and the key
     * would be found.
     */
    private static Region anotherRegion() {
        Region region = TestFixtures.region();
        if (region.id().startsWith("cn-")) {
            return region.id().equals("cn-northwest-1")
                    ? Region.of("cn-north-1") : Region.of("cn-northwest-1");
        }
        return Region.US_WEST_2.equals(region) ? Region.US_EAST_1 : Region.US_WEST_2;
    }

    @Test
    void aKeyOfOneRegionIsNotFoundInAnother() {
        Key key = createAesKey();
        PaymentCryptographyClient elsewhere = TestFixtures.paymentCryptographyClient(anotherRegion());
        try {
            // The key is named by its ARN, as the model takes it: a bare id names no key at all.
            assertThatThrownBy(() -> elsewhere.getKey(k -> k.keyIdentifier(key.keyArn())))
                    .isInstanceOf(AwsServiceException.class)
                    .satisfies(thrown -> assertThat(((AwsServiceException) thrown).awsErrorDetails()
                            .errorCode()).isEqualTo("ResourceNotFoundException"));
        } finally {
            elsewhere.close();
        }
    }

    @Test
    void aPublicKeyCertificateIsServedForAKeyPair() throws Exception {
        // The certificate of a key pair is how the pair is handed to a counterparty.
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.ECC_NIST_P256)
                        .keyClass(KeyClass.ASYMMETRIC_KEY_PAIR)
                        .keyUsage(KeyUsage.TR31_S0_ASYMMETRIC_KEY_FOR_DIGITAL_SIGNATURE)
                        .keyModesOfUse(m -> m.sign(true).verify(true)))
                .exportable(true))
                .key();
        keyArns.add(key.keyArn());

        GetPublicKeyCertificateResponse certificate = client.getPublicKeyCertificate(r -> r
                .keyIdentifier(key.keyArn()));

        assertThat(certificate.keyCertificate()).isNotNull();
        // The certificate is base64 of a PEM, so it parses as one.
        X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        Base64.getDecoder().decode(certificate.keyCertificate())));
        assertThat(parsed.getPublicKey()).isNotNull();
        // The chain of a certificate the service minted is the certificate itself.
        assertThat(certificate.keyCertificateChain()).isNotNull();
    }

    @Test
    void aTagIsRemovedFromAKey() {
        Key key = createAesKey();

        client.tagResource(t -> t
                .resourceArn(key.keyArn())
                .tags(Tag.builder().key("owner").value("team").build()));
        assertThat(client.listTagsForResource(r -> r.resourceArn(key.keyArn())).tags())
                .anySatisfy(tag -> assertThat(tag.key()).isEqualTo("owner"));

        client.untagResource(u -> u
                .resourceArn(key.keyArn())
                .tagKeys("owner"));
        assertThat(client.listTagsForResource(r -> r.resourceArn(key.keyArn())).tags())
                .noneSatisfy(tag -> assertThat(tag.key()).isEqualTo("owner"));
    }

    @Test
    void aKeyLeavesUnderAnRsaCryptogramAndComesBack() {
        // The RSA wrap form: the key is encrypted under the counterparty's certificate and
        // comes back under the token the import holds.
        GetParametersForImportResponse parameters = client.getParametersForImport(r -> r
                .keyMaterialType(KeyMaterialType.KEY_CRYPTOGRAM)
                .wrappingKeyAlgorithm(KeyAlgorithm.RSA_2048));

        // The certificate the service minted is the authority that vouches for it.
        Key authority = importPublicKey(parameters.wrappingKeyCertificate());

        Key key = createTdesKey();

        ExportKeyResponse exported = client.exportKey(r -> r
                .exportKeyIdentifier(key.keyArn())
                .keyMaterial(m -> m.keyCryptogram(b -> b
                        .certificateAuthorityPublicKeyIdentifier(authority.keyArn())
                        .wrappingKeyCertificate(parameters.wrappingKeyCertificate())
                        .wrappingSpec("RSA_OAEP_SHA_256"))));

        assertThat(exported.wrappedKey().wrappedKeyMaterialFormat())
                .isEqualTo(WrappedKeyMaterialFormat.KEY_CRYPTOGRAM);
        assertThat(exported.wrappedKey().keyMaterial()).isNotBlank();

        ImportKeyResponse imported = client.importKey(r -> r
                .keyMaterial(m -> m.keyCryptogram(b -> b
                        .importToken(parameters.importToken())
                        .wrappedKeyCryptogram(exported.wrappedKey().keyMaterial())
                        .wrappingSpec("RSA_OAEP_SHA_256")
                        .exportable(true)
                        .keyAttributes(a -> a
                                .keyAlgorithm(KeyAlgorithm.TDES_2_KEY)
                                .keyClass(KeyClass.SYMMETRIC_KEY)
                                .keyUsage(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY)
                                .keyModesOfUse(modes -> modes.encrypt(true).decrypt(true))))));

        assertThat(imported.key().keyArn()).isNotBlank();
        assertThat(imported.key().keyAttributes().keyUsage())
                .isEqualTo(KeyUsage.TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY);
        // The key that comes back is the key that left, so its check value is the one the
        // exported key had.
        assertThat(imported.key().keyCheckValue()).isEqualTo(key.keyCheckValue());
        assertThat(imported.key().keyAttributes().keyAlgorithm()).isEqualTo(KeyAlgorithm.TDES_2_KEY);
        // The key came into the service, so the cleanup has to take it away again.
        keyArns.add(imported.key().keyArn());
    }

    @Test
    void aKeyLeavesUnderAKeyTheTwoPartiesAgreed() throws Exception {
        // The ECDH form: the two parties agree on a key, and the service wraps the key under
        // it. The counterparty's certificate is minted here, signed by an authority the
        // service holds.
        KeyPair authority = rsaKeyPair();
        KeyPair counterparty = eccKeyPair();
        X509Certificate authorityCertificate = certificate(authority, "floci CA", authority, "floci CA");
        X509Certificate counterpartyCertificate =
                certificate(counterparty, "floci peer", authority, "floci CA");

        Key ca = importPublicKey(Base64.getEncoder().encodeToString(authorityCertificate.getEncoded()));

        // The service's own half of the agreement.
        Key pair = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.ECC_NIST_P256)
                        .keyClass(KeyClass.ASYMMETRIC_KEY_PAIR)
                        .keyUsage(KeyUsage.TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT)
                        .keyModesOfUse(m -> m.deriveKey(true)))
                .deriveKeyUsage("TR31_K1_KEY_BLOCK_PROTECTION_KEY")
                .exportable(true))
                .key();
        keyArns.add(pair.keyArn());

        Key key = createTdesKey();
        String peerCertificate = Base64.getEncoder().encodeToString(counterpartyCertificate.getEncoded());

        ExportKeyResponse exported = client.exportKey(r -> r
                .exportKeyIdentifier(key.keyArn())
                .keyMaterial(m -> m.diffieHellmanTr31KeyBlock(b -> b
                        .privateKeyIdentifier(pair.keyArn())
                        .certificateAuthorityPublicKeyIdentifier(ca.keyArn())
                        .publicKeyCertificate(peerCertificate)
                        .deriveKeyAlgorithm("AES_256")
                        .keyDerivationFunction("NIST_SP800")
                        .keyDerivationHashAlgorithm("SHA_256")
                        .derivationData(d -> d.sharedInformation("ADEF567890")))));

        assertThat(exported.wrappedKey().wrappedKeyMaterialFormat())
                .isEqualTo(WrappedKeyMaterialFormat.TR31_KEY_BLOCK);
        assertThat(exported.wrappedKey().keyMaterial()).isNotBlank();
    }

    @Test
    void theTimestampsAreInstants() {
        // A timestamp that is in the wrong unit is still a timestamp, so the answers are
        // checked against a range a clock could have produced.
        Key key = createAesKey();
        assertThat(key.createTimestamp()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(key.createTimestamp()).isBefore(Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(key.usageStartTimestamp()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));

        GetParametersForImportResponse parameters = client.getParametersForImport(r -> r
                .keyMaterialType(KeyMaterialType.KEY_CRYPTOGRAM)
                .wrappingKeyAlgorithm(KeyAlgorithm.RSA_2048));
        assertThat(parameters.parametersValidUntilTimestamp())
                .isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void aKeyIsCreatedWithADeriveKeyUsage() {
        // The usage a derived key is for is named when the key pair is made.
        Key key = client.createKey(r -> r
                .keyAttributes(a -> a
                        .keyAlgorithm(KeyAlgorithm.ECC_NIST_P256)
                        .keyClass(KeyClass.ASYMMETRIC_KEY_PAIR)
                        .keyUsage(KeyUsage.TR31_K3_ASYMMETRIC_KEY_FOR_KEY_AGREEMENT)
                        .keyModesOfUse(m -> m.deriveKey(true)))
                .deriveKeyUsage("TR31_K1_KEY_BLOCK_PROTECTION_KEY")
                .exportable(true))
                .key();
        keyArns.add(key.keyArn());

        assertThat(key.deriveKeyUsage()).isEqualTo(DeriveKeyUsage.TR31_K1_KEY_BLOCK_PROTECTION_KEY);
        assertThat(client.getKey(r -> r.keyIdentifier(key.keyArn())).key().deriveKeyUsage())
                .isEqualTo(DeriveKeyUsage.TR31_K1_KEY_BLOCK_PROTECTION_KEY);
    }

    /** An RSA key pair, for the authority that signs the certificates. */
    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** An elliptic curve key pair, for the counterparty that agrees on a key. */
    private static KeyPair eccKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** A certificate over a key pair, signed by an issuer. */
    private static X509Certificate certificate(KeyPair subject, String subjectName,
                                               KeyPair issuer, String issuerName) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=" + issuerName), BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(86400)),
                new X500Name("CN=" + subjectName), subject.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(issuer.getPrivate())));
    }

}
