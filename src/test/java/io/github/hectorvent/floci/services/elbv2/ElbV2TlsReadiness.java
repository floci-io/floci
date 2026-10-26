package io.github.hectorvent.floci.services.elbv2;

import org.awaitility.core.ThrowingRunnable;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.time.Duration;

import static org.awaitility.Awaitility.with;

final class ElbV2TlsReadiness {

    private ElbV2TlsReadiness() {}

    static void await(ThrowingRunnable assertion) {
        await(Duration.ofSeconds(10), assertion);
    }

    static void await(Duration timeout, ThrowingRunnable assertion) {
        with().atMost(timeout).pollInterval(Duration.ofMillis(100))
                .ignoreExceptionsMatching(failure -> failure instanceof ConnectException
                        || failure instanceof SSLHandshakeException)
                .untilAsserted(assertion);
    }
}
