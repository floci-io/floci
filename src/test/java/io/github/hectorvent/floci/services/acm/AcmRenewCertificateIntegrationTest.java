package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.core.common.Pem;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.ExportCertificateResponse;
import software.amazon.awssdk.services.acm.model.GetCertificateResponse;
import software.amazon.awssdk.services.acm.model.InvalidArnException;
import software.amazon.awssdk.services.acm.model.Tag;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RenewCertificate on an issued private certificate reissues its material under the same ARN, so
 * GetCertificate and ExportCertificate hand out a leaf whose validity is what DescribeCertificate reports.
 */
@QuarkusTest
class AcmRenewCertificateIntegrationTest {

    private static final String PCA_ARN =
            "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/renew-test";
    private static final String PASSPHRASE = "renew-passphrase";

    @Test
    void renewReissuesTheCertificateWithTheReportedValidity() throws Exception {
        try (AcmClient acm = client()) {
            String arn = acm.requestCertificate(request -> request
                    .domainName("renew-sdk.example.test")
                    .subjectAlternativeNames("renew-sdk.example.test", "api.renew-sdk.example.test")
                    .certificateAuthorityArn(PCA_ARN)
                    .tags(Tag.builder().key("team").value("edge").build()))
                    .certificateArn();
            GetCertificateResponse before = acm.getCertificate(request -> request.certificateArn(arn));

            acm.renewCertificate(request -> request.certificateArn(arn));

            GetCertificateResponse after = acm.getCertificate(request -> request.certificateArn(arn));
            assertNotEquals(before.certificate(), after.certificate(), "renewal issues a new certificate body");
            assertEquals(before.certificateChain(), after.certificateChain(), "still chained to the same CA");

            CertificateDetail detail = acm.describeCertificate(request -> request.certificateArn(arn)).certificate();
            X509Certificate leaf = Pem.parseCertificate(after.certificate());
            assertEquals(arn, detail.certificateArn());
            assertEquals(leaf.getNotBefore().toInstant(), detail.notBefore().truncatedTo(ChronoUnit.SECONDS));
            assertEquals(leaf.getNotAfter().toInstant(), detail.notAfter().truncatedTo(ChronoUnit.SECONDS));
            assertEquals(List.of("renew-sdk.example.test", "api.renew-sdk.example.test"),
                    detail.subjectAlternativeNames());
            assertEquals(List.of("renew-sdk.example.test", "api.renew-sdk.example.test"),
                    leaf.getSubjectAlternativeNames().stream().map(san -> (String) san.get(1)).toList());
            assertEquals(List.of(Tag.builder().key("team").value("edge").build()),
                    acm.listTagsForCertificate(request -> request.certificateArn(arn)).tags());

            ExportCertificateResponse exported = acm.exportCertificate(request -> request
                    .certificateArn(arn)
                    .passphrase(SdkBytes.fromString(PASSPHRASE, StandardCharsets.UTF_8)));
            assertEquals(after.certificate(), exported.certificate(), "ExportCertificate returns the renewed body");
            assertEquals(after.certificateChain(), exported.certificateChain());
            PrivateKey key = decrypt(exported.privateKey(), leaf.getPublicKey().getAlgorithm());
            assertTrue(Pem.isPair(key, leaf.getPublicKey()), "the exported key belongs to the renewed certificate");
        }
    }

    @Test
    void renewIsOnlyForIssuedPrivateCertificates() {
        try (AcmClient acm = client()) {
            String arn = acm.requestCertificate(request -> request.domainName("renew-public.example.test"))
                    .certificateArn();
            String serial = acm.describeCertificate(request -> request.certificateArn(arn)).certificate().serial();

            InvalidArnException failure = assertThrows(InvalidArnException.class,
                    () -> acm.renewCertificate(request -> request.certificateArn(arn)));

            assertEquals(400, failure.statusCode());
            assertEquals(serial, acm.describeCertificate(request -> request.certificateArn(arn)).certificate().serial());
        }
    }

    private static PrivateKey decrypt(String encryptedPem, String keyAlgorithm) throws Exception {
        String base64 = encryptedPem.replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
                .replace("-----END ENCRYPTED PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(Base64.getDecoder().decode(base64));
        AlgorithmParameters parameters = info.getAlgParameters();
        String pbe = parameters.toString();
        SecretKey secret = SecretKeyFactory.getInstance(pbe).generateSecret(new PBEKeySpec(PASSPHRASE.toCharArray()));
        Cipher cipher = Cipher.getInstance(pbe);
        cipher.init(Cipher.DECRYPT_MODE, secret, parameters);
        PKCS8EncodedKeySpec spec = info.getKeySpec(cipher);
        return KeyFactory.getInstance(keyAlgorithm).generatePrivate(spec);
    }

    private static AcmClient client() {
        return AcmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
