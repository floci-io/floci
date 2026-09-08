package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JSSE asks a key manager for an alias, then for that alias's key and chain, on every handshake.
 * The manager under test answers from its current delegate and keeps a handshake that started
 * before a reload on the delegate it began with.
 */
class ReloadingKeyManagerTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void serverAliasAndItsMaterialComeFromTheDelegate() {
        X509ExtendedKeyManager inner = delegate("srv", "first");

        ReloadingKeyManager manager = new ReloadingKeyManager(inner);

        String alias = manager.chooseEngineServerAlias("RSA", null, null);
        assertNotNull(alias);
        assertSame(inner.getCertificateChain("srv"), manager.getCertificateChain(alias));
        assertSame(inner.getPrivateKey("srv"), manager.getPrivateKey(alias));
        assertEquals(alias, manager.chooseServerAlias("RSA", null, null), "socket and engine flavours agree");
        assertArrayEquals(new String[] {alias}, manager.getServerAliases("RSA", null));
    }

    @Test
    void reloadServesTheNewDelegateToTheNextHandshake() {
        X509ExtendedKeyManager first = delegate("srv", "first");
        X509ExtendedKeyManager second = delegate("srv", "second");
        ReloadingKeyManager manager = new ReloadingKeyManager(first);

        manager.reload(second);

        String alias = manager.chooseEngineServerAlias("RSA", null, null);
        assertSame(second.getCertificateChain("srv"), manager.getCertificateChain(alias));
        assertSame(second.getPrivateKey("srv"), manager.getPrivateKey(alias));
    }

    @Test
    void anAliasChosenBeforeAReloadKeepsItsOwnChainAndKey() {
        X509ExtendedKeyManager first = delegate("srv", "first");
        X509ExtendedKeyManager second = delegate("srv", "second");
        ReloadingKeyManager manager = new ReloadingKeyManager(first);
        String inFlight = manager.chooseEngineServerAlias("RSA", null, null);

        manager.reload(second);

        assertSame(first.getCertificateChain("srv"), manager.getCertificateChain(inFlight));
        assertSame(first.getPrivateKey("srv"), manager.getPrivateKey(inFlight));
    }

    @Test
    void anAliasChosenBeforeSeveralQuickReloadsStillResolvesItsOwnChainAndKey() {
        X509ExtendedKeyManager first = delegate("srv", "first");
        ReloadingKeyManager manager = new ReloadingKeyManager(first);
        String inFlight = manager.chooseEngineServerAlias("RSA", null, null);

        manager.reload(delegate("srv", "second"));
        manager.reload(delegate("srv", "third"));
        manager.reload(delegate("srv", "fourth"));

        assertSame(first.getCertificateChain("srv"), manager.getCertificateChain(inFlight));
        assertSame(first.getPrivateKey("srv"), manager.getPrivateKey(inFlight));
    }

    @Test
    void aRetiredGenerationIsDroppedOnceNoHandshakeCanStillReferenceIt() {
        AtomicLong clock = new AtomicLong();
        X509ExtendedKeyManager first = delegate("srv", "first");
        ReloadingKeyManager manager = new ReloadingKeyManager(first, clock::get);
        String stale = manager.chooseEngineServerAlias("RSA", null, null);
        manager.reload(delegate("srv", "second"));
        clock.addAndGet(ReloadingKeyManager.RETIRED_GENERATION_RETENTION.toNanos() - 1);
        manager.reload(delegate("srv", "third"));
        assertSame(first.getPrivateKey("srv"), manager.getPrivateKey(stale), "still inside the retention window");

        clock.addAndGet(1);
        manager.reload(delegate("srv", "fourth"));

        assertNull(manager.getCertificateChain(stale));
        assertNull(manager.getPrivateKey(stale));
        assertEquals(2, manager.retiredGenerations(), "the two generations retired inside the window are kept, the first is gone");
    }

    @Test
    void unknownAliasesAndKeyTypesYieldNull() {
        ReloadingKeyManager manager = new ReloadingKeyManager(delegate("srv", "first"));

        assertNull(manager.chooseEngineServerAlias("EC", null, null));
        assertNull(manager.chooseServerAlias("EC", null, null));
        assertEquals(0, manager.getServerAliases("EC", null).length);
        assertNull(manager.getCertificateChain("garbage"));
        assertNull(manager.getPrivateKey("garbage"));
        assertNull(manager.getCertificateChain("999:srv"));
        assertNull(manager.getPrivateKey(null));
    }

    @Test
    void neverActsAsATlsClient() {
        ReloadingKeyManager manager = new ReloadingKeyManager(delegate("srv", "first"));

        assertNull(manager.chooseClientAlias(new String[] {"RSA"}, null, null));
        assertNull(manager.chooseEngineClientAlias(new String[] {"RSA"}, null, null));
        assertEquals(0, manager.getClientAliases("RSA", null).length);
    }

    @Test
    void keyManagerOfLoadsThePemKeyStoreOfTheOptions() {
        var leaf = new CertificateGenerator().generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        PemKeyCertOptions options = new PemKeyCertOptions()
                .addCertValue(Buffer.buffer(leaf.certificatePem()))
                .addKeyValue(Buffer.buffer(leaf.privateKeyPem()));

        ReloadingKeyManager manager = new ReloadingKeyManager(ReloadingKeyManager.keyManagerOf(vertx, options));

        String alias = manager.chooseEngineServerAlias("RSA", null, null);
        assertNotNull(alias);
        assertEquals(new CertificateGenerator().parseCertificate(leaf.certificatePem()), manager.getCertificateChain(alias)[0]);
        assertNotNull(manager.getPrivateKey(alias));
    }

    @Test
    void keyManagerOfRefusesAConfigurationWithoutAKeyStore() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ReloadingKeyManager.keyManagerOf(vertx, null));

        assertEquals("the default TLS configuration has no key store", refused.getMessage());
    }

    @Test
    void keyManagerOfReportsAKeyStoreThatCannotBeLoaded() {
        PemKeyCertOptions broken = new PemKeyCertOptions()
                .addCertValue(Buffer.buffer("not a certificate"))
                .addKeyValue(Buffer.buffer("not a key"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ReloadingKeyManager.keyManagerOf(vertx, broken));

        assertNotNull(refused.getCause());
    }

    /** A delegate with one server alias for RSA whose chain and key are distinct mock objects. */
    private static X509ExtendedKeyManager delegate(String alias, String label) {
        X509ExtendedKeyManager inner = mock(X509ExtendedKeyManager.class, label);
        X509Certificate cert = mock(X509Certificate.class, label + "-cert");
        PrivateKey key = mock(PrivateKey.class, label + "-key");
        when(inner.chooseEngineServerAlias("RSA", null, null)).thenReturn(alias);
        when(inner.chooseServerAlias("RSA", null, null)).thenReturn(alias);
        when(inner.getServerAliases("RSA", null)).thenReturn(new String[] {alias});
        when(inner.getCertificateChain(alias)).thenReturn(new X509Certificate[] {cert});
        when(inner.getPrivateKey(alias)).thenReturn(key);
        return inner;
    }
}
