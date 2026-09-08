package io.github.hectorvent.floci.services.iot;

import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Server key manager for the MQTT TLS listener that answers every handshake from the key material
 * it was last given, so a reissued server certificate is served without rebuilding the listener's
 * SSL context: Vert.x drops the connections it accepts while a context is being rebuilt, whereas
 * JSSE asks the key manager for an alias, then that alias's key and chain, on every handshake.
 *
 * <p>The alias handed to JSSE names the generation it came from, and a retired generation stays
 * readable for {@link #RETIRED_GENERATION_RETENTION} after its replacement, longer than any
 * handshake can live (Vert.x closes a connection whose handshake exceeds its 10 second timeout), so
 * a handshake that began before any number of reloads still reads a matching key and chain.
 * Retired generations are dropped on the next reload after that window.
 */
final class ReloadingKeyManager extends X509ExtendedKeyManager {

    static final Duration RETIRED_GENERATION_RETENTION = Duration.ofSeconds(60);

    private record Generation(long number, X509KeyManager delegate, long retiredAtNanos) {
    }

    private final LongSupplier nanoTime;
    private volatile Generation current;
    /** Superseded generations, newest first, each within the retention window at its last reload. */
    private volatile List<Generation> retired = List.of();

    ReloadingKeyManager(X509KeyManager delegate) {
        this(delegate, System::nanoTime);
    }

    ReloadingKeyManager(X509KeyManager delegate, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.current = new Generation(0, delegate, 0);
    }

    /** The first X.509 key manager of {@code options}, the TLS registry's default key store. */
    static X509KeyManager keyManagerOf(Vertx vertx, KeyCertOptions options) {
        if (options == null) {
            throw new IllegalStateException("the default TLS configuration has no key store");
        }
        KeyManagerFactory factory;
        try {
            factory = options.getKeyManagerFactory(vertx);
        } catch (Exception e) {
            throw new IllegalStateException("the default TLS configuration's key store cannot be loaded: " + e.getMessage(), e);
        }
        if (factory != null) {
            for (KeyManager manager : factory.getKeyManagers()) {
                if (manager instanceof X509KeyManager x509) {
                    return x509;
                }
            }
        }
        throw new IllegalStateException("the default TLS configuration has no X.509 key manager");
    }

    /** Serves {@code delegate} from the next handshake on; the generations before it stay readable for a while. */
    synchronized void reload(X509KeyManager delegate) {
        long now = nanoTime.getAsLong();
        List<Generation> kept = new ArrayList<>();
        kept.add(new Generation(current.number(), current.delegate(), now));
        for (Generation generation : retired) {
            if (now - generation.retiredAtNanos() < RETIRED_GENERATION_RETENTION.toNanos()) {
                kept.add(generation);
            }
        }
        retired = List.copyOf(kept);
        current = new Generation(current.number() + 1, delegate, 0);
    }

    int retiredGenerations() {
        return retired.size();
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        Generation generation = current;
        return qualify(generation, generation.delegate() instanceof X509ExtendedKeyManager extended
                ? extended.chooseEngineServerAlias(keyType, issuers, engine)
                : generation.delegate().chooseServerAlias(keyType, issuers, null));
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        Generation generation = current;
        return qualify(generation, generation.delegate().chooseServerAlias(keyType, issuers, socket));
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        Generation generation = current;
        String[] aliases = generation.delegate().getServerAliases(keyType, issuers);
        if (aliases == null) {
            return new String[0];
        }
        String[] qualified = new String[aliases.length];
        for (int i = 0; i < aliases.length; i++) {
            qualified[i] = qualify(generation, aliases[i]);
        }
        return qualified;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        Generation generation = generationOf(alias);
        return generation == null ? null : generation.delegate().getCertificateChain(unqualified(alias));
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        Generation generation = generationOf(alias);
        return generation == null ? null : generation.delegate().getPrivateKey(unqualified(alias));
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return null;
    }

    private static String qualify(Generation generation, String alias) {
        return alias == null ? null : generation.number() + ":" + alias;
    }

    private static String unqualified(String alias) {
        return alias.substring(alias.indexOf(':') + 1);
    }

    private Generation generationOf(String alias) {
        int separator = alias == null ? -1 : alias.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        String number = alias.substring(0, separator);
        Generation candidate = current;
        if (Long.toString(candidate.number()).equals(number)) {
            return candidate;
        }
        for (Generation generation : retired) {
            if (Long.toString(generation.number()).equals(number)) {
                return generation;
            }
        }
        return null;
    }
}
