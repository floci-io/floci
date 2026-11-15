package io.floci.aws.services.mwaa.proxy;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code WebserverUrl} says {@code https} when {@code floci.tls.enabled} is on, so the proxy has to
 * serve TLS then, and stay plain HTTP otherwise.
 */
class MwaaProxyManagerTlsTest {

    private Vertx vertx;
    private HttpServer backend;
    private MwaaProxyManager manager;
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.TlsConfig tlsConfig = mock(EmulatorConfig.TlsConfig.class);
    private final TlsConfigurationRegistry registry = mock(TlsConfigurationRegistry.class);

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        backend = vertx.createHttpServer()
                .requestHandler(req -> req.response().end("airflow"))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        when(config.tls()).thenReturn(tlsConfig);
        manager = new MwaaProxyManager(vertx, config, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.stopAll();
        vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private int startProxy() throws Exception {
        int port = FreePorts.anyInterfacePort();
        manager.startProxy("env1", port, "127.0.0.1", backend.actualPort(),
                (environmentName, token) -> false, command -> new ContainerExec.Result(0, "", "", false),
                (environmentName, token) -> false, new MwaaWebProxy.AirflowAccount("admin", "pw"));
        return port;
    }

    private String get(int port, boolean ssl) throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions().setSsl(ssl).setTrustAll(true)
                .setVerifyHost(false));
        return client.request(new RequestOptions().setHost("127.0.0.1").setPort(port)
                        .setMethod(HttpMethod.GET).setURI("/home"))
                .compose(request -> request.send())
                .compose(response -> response.body())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).toString();
    }

    @Test
    void theProxyServesHttpsWithFlocisCertificateWhenTlsIsEnabled() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        TlsConfiguration tls = mock(TlsConfiguration.class);
        when(registry.getDefault()).thenReturn(Optional.of(tls));
        when(tls.getKeyStoreOptions()).thenReturn(SelfSignedCertificate.create("localhost").keyCertOptions());

        int port = startProxy();

        assertEquals("airflow", get(port, true));
    }

    @Test
    void aKeyStoreThatCannotBeLoadedKeepsThePreviousCertificateAndOtherFailuresPropagate() throws Exception {
        when(tlsConfig.enabled()).thenReturn(true);
        TlsConfiguration tls = mock(TlsConfiguration.class);
        when(registry.getDefault()).thenReturn(Optional.of(tls));
        when(tls.getKeyStoreOptions()).thenReturn(SelfSignedCertificate.create("localhost").keyCertOptions());
        int port = startProxy();
        TlsConfiguration unloadable = mock(TlsConfiguration.class);
        when(unloadable.getKeyStoreOptions()).thenReturn(new PemKeyCertOptions()
                .addCertValue(Buffer.buffer("not a certificate"))
                .addKeyValue(Buffer.buffer("not a key")));
        TlsConfiguration broken = mock(TlsConfiguration.class);
        when(broken.getKeyStoreOptions()).thenThrow(new UnsupportedOperationException("broken registry"));

        manager.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, unloadable));

        assertEquals("airflow", get(port, true));
        assertThrows(UnsupportedOperationException.class,
                () -> manager.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, broken)));
    }

    @Test
    void theProxyStaysPlainHttpWhenTlsIsDisabled() throws Exception {
        when(tlsConfig.enabled()).thenReturn(false);

        int port = startProxy();

        assertNull(manager.serverKeyCertOptions());
        assertEquals("airflow", get(port, false));
    }
}
