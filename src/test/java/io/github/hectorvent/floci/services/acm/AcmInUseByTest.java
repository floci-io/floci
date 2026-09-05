package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Security;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code InUseBy} bookkeeping other services drive: a registered consumer blocks
 * {@code DeleteCertificate} with {@code ResourceInUseException}, as on AWS, until it is released.
 */
class AcmInUseByTest {

    private static final String REGION = "us-east-1";
    private static final String CONSUMER = "arn:aws:cloudfront::000000000000:distribution/E1234567890ABC";
    private static final String MISSING = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000";

    private static CertificateGenerator generator;
    private AcmService service;

    @BeforeAll
    static void registerProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        service = new AcmService(new InMemoryStorage<>(), generator, regionResolver, 0);
    }

    private String requestCertificate() {
        return service.requestCertificate("auth.example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION).getArn();
    }

    @Test
    void addInUseByRecordsTheConsumerOnce() {
        String arn = requestCertificate();

        service.addInUseBy(arn, CONSUMER, REGION);
        service.addInUseBy(arn, CONSUMER, REGION);

        assertEquals(List.of(CONSUMER), service.describeCertificate(arn, REGION).getInUseBy());
    }

    @Test
    void aCertificateInUseCannotBeDeleted() {
        String arn = requestCertificate();
        service.addInUseBy(arn, CONSUMER, REGION);

        AwsException failure = assertThrows(AwsException.class, () -> service.deleteCertificate(arn, REGION));

        assertEquals("ResourceInUseException", failure.getErrorCode());
        assertEquals(List.of(CONSUMER), service.describeCertificate(arn, REGION).getInUseBy());
    }

    @Test
    void removeInUseByReleasesTheCertificateForDeletion() {
        String arn = requestCertificate();
        service.addInUseBy(arn, CONSUMER, REGION);

        service.removeInUseBy(arn, CONSUMER, REGION);

        assertEquals(List.of(), service.describeCertificate(arn, REGION).getInUseBy());
        assertDoesNotThrow(() -> service.deleteCertificate(arn, REGION));
    }

    @Test
    void removeInUseByOnAMissingCertificateIsANoOp() {
        assertDoesNotThrow(() -> service.removeInUseBy(MISSING, CONSUMER, REGION));
    }

    @Test
    void addInUseByOnAMissingCertificateIsNotFound() {
        AwsException failure = assertThrows(AwsException.class, () -> service.addInUseBy(MISSING, CONSUMER, REGION));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
    }
}
