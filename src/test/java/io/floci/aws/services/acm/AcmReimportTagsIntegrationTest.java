package io.floci.aws.services.acm;

import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.ImportCertificateResponse;
import software.amazon.awssdk.services.acm.model.Tag;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AcmReimportTagsIntegrationTest {

    @Inject
    CertificateGenerator generator;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void reimportPreservesCurrentTagsAndReplacesCertificate(String region) {
        CertificateGenerator.GeneratedCertificate original = certificate();
        CertificateGenerator.GeneratedCertificate replacement = certificate();
        Tag team = Tag.builder().key("team").value("payments").build();
        Tag environment = Tag.builder().key("environment").value("test").build();
        try (AcmClient client = AwsTestClients.create(AcmClient.builder(), "test", Region.of(region))) {
            String arn = client.importCertificate(request -> request
                    .certificate(SdkBytes.fromUtf8String(original.certificatePem()))
                    .privateKey(SdkBytes.fromUtf8String(original.privateKeyPem()))
                    .tags(team)).certificateArn();
            try {
                assertEquals(Set.of(team), tags(client, arn));
                assertNotEquals(original.certificatePem(), replacement.certificatePem());

                reimportAndCheckMaterial(client, arn, replacement);

                assertEquals(Set.of(team), tags(client, arn));
                client.addTagsToCertificate(request -> request.certificateArn(arn).tags(environment));
                client.removeTagsFromCertificate(request -> request.certificateArn(arn).tags(team));
                assertEquals(Set.of(environment), tags(client, arn));

                reimportAndCheckMaterial(client, arn, certificate());

                assertEquals(Set.of(environment), tags(client, arn));
            } finally {
                client.deleteCertificate(request -> request.certificateArn(arn));
            }
        }
    }

    @Test
    void reimportKeepsAnUntaggedCertificateUntagged() {
        CertificateGenerator.GeneratedCertificate original = certificate();
        try (AcmClient client = AwsTestClients.create(AcmClient.builder(), "test", Region.US_EAST_1)) {
            String arn = client.importCertificate(request -> request
                    .certificate(SdkBytes.fromUtf8String(original.certificatePem()))
                    .privateKey(SdkBytes.fromUtf8String(original.privateKeyPem()))).certificateArn();
            try {
                assertEquals(Set.of(), tags(client, arn));

                reimportAndCheckMaterial(client, arn, certificate());

                assertEquals(Set.of(), tags(client, arn));
            } finally {
                client.deleteCertificate(request -> request.certificateArn(arn));
            }
        }
    }

    private CertificateGenerator.GeneratedCertificate certificate() {
        return generator.generateSelfSignedCertificate(
                "reimport-tags.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
    }

    private static Set<Tag> tags(AcmClient client, String arn) {
        return Set.copyOf(client.listTagsForCertificate(request -> request.certificateArn(arn)).tags());
    }

    private static void reimportAndCheckMaterial(AcmClient client, String arn,
                                                 CertificateGenerator.GeneratedCertificate certificate) {
        ImportCertificateResponse response = client.importCertificate(request -> request
                .certificateArn(arn)
                .certificate(SdkBytes.fromUtf8String(certificate.certificatePem()))
                .privateKey(SdkBytes.fromUtf8String(certificate.privateKeyPem())));

        assertEquals(arn, response.certificateArn());
        assertEquals(certificate.certificatePem(),
                client.getCertificate(request -> request.certificateArn(arn)).certificate());
        assertEquals(arn, client.describeCertificate(request -> request.certificateArn(arn))
                .certificate().certificateArn());
    }
}
