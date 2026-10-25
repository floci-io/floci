package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.tls.ReloadingKeyManager;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.vertx.core.http.HttpServerOptions;
import org.mockito.invocation.InvocationOnMock;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ElbV2PublicationTestSupport {
    private ElbV2PublicationTestSupport() { }

    static X509Certificate servedCertificate(HttpServerOptions options, String host) {
        X509ExtendedKeyManager manager = (X509ExtendedKeyManager)
                ReloadingKeyManager.keyManagerOf(null, options.getKeyCertOptions());
        SSLEngine engine = mock(SSLEngine.class);
        ExtendedSSLSession session = mock(ExtendedSSLSession.class);
        when(engine.getHandshakeSession()).thenReturn(session);
        when(session.getRequestedServerNames()).thenReturn(List.of(new SNIHostName(host)));
        String alias = manager.chooseEngineServerAlias("RSA", null, engine);
        return manager.getCertificateChain(alias)[0];
    }

    static void publishRuntime(ElbV2DataPlane plane, Listener listener, String region,
                               Integer previousPort, List<Rule> rules) {
        while (!plane.publishListener(listener, region, previousPort, () -> {
            plane.recompileRules(listener.getListenerArn(), () -> rules);
            return true;
        }, () -> rules, Supplier::get)) {
            // The service retries a runtime reservation changed by another publisher.
        }
    }

    static void removeRuntime(ElbV2DataPlane plane, String arn, Integer storedPort) {
        while (!plane.removeListener(arn, storedPort, () -> true)) {
            // Retry an identity changed while waiting for its port gate.
        }
    }

    static boolean applyPublication(InvocationOnMock call) {
        Function<Supplier<Boolean>, Boolean> admission = call.getArgument(5);
        return admission.apply(() -> call.getArgument(3, BooleanSupplier.class).getAsBoolean());
    }

    static void allowPublications(ElbV2DataPlane dataPlane) {
        lenient().doAnswer(ElbV2PublicationTestSupport::applyPublication)
                .when(dataPlane).publishListener(any(Listener.class), anyString(), any(), any(), any(), any());
        lenient().doAnswer(call -> call.getArgument(1, BooleanSupplier.class).getAsBoolean())
                .when(dataPlane).updateListener(any(), any());
        lenient().doAnswer(call -> call.getArgument(2, BooleanSupplier.class).getAsBoolean())
                .when(dataPlane).removeListener(anyString(), any(), any());
    }

}
