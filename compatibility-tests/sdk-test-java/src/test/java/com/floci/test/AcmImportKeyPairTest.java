package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.AcmException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("ACM imported certificate key pairs")
class AcmImportKeyPairTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Mismatched keys are refused without replacing an imported certificate")
    void refusesMismatchedKeysAndAcceptsAMatchingRetry(boolean reimport) throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Local import fixtures and error mapping");
        byte[][] first = AcmTest.generateSelfSignedCert();
        byte[][] replacement = AcmTest.generateSelfSignedCert();
        Set<String> owned = new LinkedHashSet<>();
        try (AcmClient acm = TestFixtures.acmClient()) {
            try {
                String prior = reimport ? importPair(acm, first, null, owned) : null;
                byte[] certificate = reimport ? replacement[0] : first[0];
                byte[] mismatchedKey = reimport ? first[1] : replacement[1];
                AcmException refusal = assertThrows(AcmException.class, () -> {
                    String unexpected = acm.importCertificate(b -> b.certificate(SdkBytes.fromByteArray(certificate))
                            .privateKey(SdkBytes.fromByteArray(mismatchedKey)).certificateArn(prior)).certificateArn();
                    owned.add(unexpected);
                });
                assertThat(refusal.statusCode()).isEqualTo(400);
                assertThat(refusal.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                if (reimport) {
                    assertThat(acm.getCertificate(b -> b.certificateArn(prior)).certificate())
                            .isEqualTo(new String(first[0], StandardCharsets.UTF_8));
                }
                byte[][] matching = reimport ? replacement : first;
                String retry = importPair(acm, matching, prior, owned);
                if (reimport) {
                    assertThat(retry).isEqualTo(prior);
                }
                assertThat(acm.getCertificate(b -> b.certificateArn(retry)).certificate())
                        .isEqualTo(new String(matching[0], StandardCharsets.UTF_8));
            } finally {
                assertAll("Delete every owned imported certificate", owned.stream().map(arn ->
                        (Executable) () -> acm.deleteCertificate(b -> b.certificateArn(arn))));
            }
        }
    }

    private static String importPair(AcmClient acm, byte[][] material, String prior, Set<String> owned) {
        String arn = acm.importCertificate(b -> b.certificate(SdkBytes.fromByteArray(material[0]))
                .privateKey(SdkBytes.fromByteArray(material[1])).certificateArn(prior)).certificateArn();
        owned.add(arn);
        return arn;
    }
}
