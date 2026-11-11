package io.floci.aws.services.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.testing.LocalHttpsServer;
import io.floci.aws.testing.StorageFactories;
import io.floci.aws.testutil.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;

import static io.floci.aws.testing.LocalHttpsServer.hookUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * An HTTPS subscription's endpoint is reached through Floci's outbound trust, so a certificate from a CA in
 * floci.tls.extra-ca-path is accepted, with TLS off.
 */
class SnsHttpsSubscriptionTrustTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    @TempDir
    Path dir;

    /**
     * An endpoint whose certificate chains to a private CA is refused while only the JVM's roots are trusted, and
     * gets the SubscriptionConfirmation once that CA is in the floci.tls.extra-ca-path file.
     */
    @Test
    void sendsTheConfirmationToAnEndpointWhoseCertificateChainsToAnExtraCa() throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("private-ca"));
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        HttpsServer server = httpsEndpoint(privateCa, received);
        try {
            assertRefused(hookUrl(server), Optional.empty(), received);

            subscribe(hookUrl(server), Optional.of(privateCa.certificatePath().toString()));
            assertEquals("SubscriptionConfirmation", received.poll(5, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }

    /** The extra CAs add trust; they do not loosen it: a CA the endpoint's certificate does not chain to is no help. */
    @Test
    void refusesAnEndpointWhoseCaIsNotInTheExtraCaFile() throws Exception {
        FlociCertificateAuthority privateCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("private-ca"));
        FlociCertificateAuthority unrelatedCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("unrelated-ca"));
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        HttpsServer server = httpsEndpoint(privateCa, received);
        try {
            assertRefused(hookUrl(server), Optional.of(unrelatedCa.certificatePath().toString()), received);
        } finally {
            server.stop(0);
        }
    }

    // Asserts that subscribing endpoint fails the TLS handshake, so the endpoint never sees a request.
    private void assertRefused(String endpoint, Optional<String> extraCaPath, BlockingQueue<String> received) {
        LogCapture.awaitRecord(SnsService.class, Duration.ofSeconds(5), record -> refuses(record, endpoint), () -> {
            subscribe(endpoint, extraCaPath);
            return null;
        });
        assertTrue(received.isEmpty(), "the endpoint got " + received);
    }

    // Whether record is the warning that a send to endpoint failed its TLS handshake.
    private static boolean refuses(LogRecord record, String endpoint) {
        Object[] parameters = record.getParameters();
        return record.getMessage().contains("Failed to reach SNS endpoint")
                && parameters != null && parameters.length == 2
                && endpoint.equals(parameters[0])
                && String.valueOf(parameters[1]).contains("SSLHandshakeException");
    }

    // Subscribes endpoint over HTTPS to a new topic, with extraCaPath as the extra CA setting.
    private void subscribe(String endpoint, Optional<String> extraCaPath) {
        SnsService service = service(extraCaPath);
        String topicArn = service.createTopic("trust", null, null, REGION).getTopicArn();
        service.subscribe(topicArn, "https", endpoint, REGION, Map.of());
    }

    // A service built the way CDI builds it, with TLS off.
    private SnsService service(Optional<String> extraCaPath) {
        EmulatorConfig config = StorageFactories.config(dir.resolve("data"), ACCOUNT);
        when(config.effectiveBaseUrl()).thenReturn(BASE_URL);
        when(config.tls().enabled()).thenReturn(false);
        when(config.tls().extraCaPath()).thenReturn(extraCaPath);
        return new SnsService(StorageFactories.open(config, "memory"), config, new RegionResolver(REGION, ACCOUNT),
                null, null, null, new ObjectMapper(), SnsMessageSigner.inMemory(BASE_URL));
    }

    // A localhost HTTPS endpoint serving a certificate ca issued, queueing the SNS message type of each request.
    private static HttpsServer httpsEndpoint(FlociCertificateAuthority ca, BlockingQueue<String> received)
            throws Exception {
        return LocalHttpsServer.hook(ca, exchange -> {
            List<String> type = exchange.getRequestHeaders().get("x-amz-sns-message-type");
            received.add(type == null ? "" : type.get(0));
        });
    }
}
