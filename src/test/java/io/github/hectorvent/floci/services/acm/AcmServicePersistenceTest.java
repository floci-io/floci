package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.Security;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcmServicePersistenceTest {

    private static final String REGION = "us-east-1";
    private static CertificateGenerator generator;

    @BeforeAll
    static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @Test
    void certificateSurvivesRestart(@TempDir Path dir) {
        // Step 1: Create a service with a persistent backend and create a certificate
        AcmService first = newService(dir);
        Certificate cert = first.requestCertificate(
                "example.com",
                List.of("www.example.com"),
                ValidationMethod.DNS,
                "idempotency-token-123",
                KeyAlgorithm.RSA_2048,
                null,
                null,
                Map.of("Env", "test"),
                REGION
        );
        assertNotNull(cert);
        String arn = cert.getArn();

        // Step 2: Create a second service instance sharing the same storage directory (simulating restart)
        AcmService restarted = newService(dir);
        Certificate loaded = restarted.describeCertificate(arn, REGION);

        // Step 3: Validate that the certificate was correctly restored and properties match
        assertNotNull(loaded);
        assertEquals(arn, loaded.getArn());
        assertEquals("example.com", loaded.getDomainName());
        assertEquals(List.of("example.com", "www.example.com"), loaded.getSubjectAlternativeNames());
        assertEquals("test", loaded.getTags().get("Env"));
    }

    private AcmService newService(Path dir) {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        StorageBackend<String, Certificate> store = load(dir, "acm-certificates.json");
        return new AcmService(store, generator, regionResolver, 0);
    }

    private StorageBackend<String, Certificate> load(Path dir, String file) {
        PersistentStorage<String, Certificate> backend = new PersistentStorage<>(
                dir.resolve(file),
                new TypeReference<Map<String, Certificate>>() {}
        );
        backend.load();
        return backend;
    }
}
