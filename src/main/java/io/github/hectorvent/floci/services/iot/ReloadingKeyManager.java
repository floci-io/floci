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

/**
 * Server key manager for the MQTT TLS listener that answers every handshake from the key material
 * it was last given, so a reissued server certificate is served without rebuilding the listener's
 * SSL context: Vert.x drops the connections it accepts while a context is being rebuilt, whereas
 * JSSE asks the key manager for an alias, then that alias's key and chain, on every handshake.
 *
 * <p>The alias handed to JSSE names the generation it came from, and the previous generation is
 * kept, so a handshake that began before {@link #reload} still reads a matching key and chain. Two
 * reloads inside one handshake's key lookup make that handshake fail; a client retries.
 */
final class ReloadingKeyManager extends X509ExtendedKeyManager {

    private record Generation(long number, X509KeyManager delegate) {
    }

    private volatile Generation current;
    private volatile Generation previous;

    ReloadingKeyManager(X509KeyManager delegate) {
        this.current = new Generation(0, delegate);
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

    /** Serves {@code delegate} from the next handshake on; the one before it stays readable. */
    synchronized void reload(X509KeyManager delegate) {
        previous = current;
        current = new Generation(current.number() + 1, delegate);
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
        candidate = previous;
        return candidate != null && Long.toString(candidate.number()).equals(number) ? candidate : null;
    }
}
