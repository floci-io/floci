package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.tls.ReloadingKeyManager;
import io.vertx.core.Vertx;
import io.vertx.core.net.KeyCertOptions;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.function.Function;

/**
 * An immutable SNI selection snapshot. Host-qualified aliases keep key and chain lookups on the
 * selected manager; the enclosing ReloadingKeyManager also qualifies them by certificate generation.
 */
final class ElbV2TlsKeyManager extends X509ExtendedKeyManager {

    private final X509KeyManager defaultManager;
    private final Function<String, KeyManagerFactory> managers;

    ElbV2TlsKeyManager(Vertx vertx, KeyCertOptions options) {
        defaultManager = ReloadingKeyManager.keyManagerOf(vertx, options);
        try {
            managers = options.keyManagerFactoryMapper(vertx);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load ELBv2 SNI certificates", failure);
        }
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        String host = "";
        if (engine.getHandshakeSession() instanceof ExtendedSSLSession session) {
            for (SNIServerName name : session.getRequestedServerNames()) {
                if (name instanceof SNIHostName hostname) {
                    host = hostname.getAsciiName().toLowerCase(Locale.ROOT);
                    break;
                }
            }
        }
        X509KeyManager manager = managerFor(host);
        String alias = manager instanceof X509ExtendedKeyManager extended
                ? extended.chooseEngineServerAlias(keyType, issuers, engine)
                : manager.chooseServerAlias(keyType, issuers, null);
        return alias == null ? null : host + ":" + alias;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        String alias = defaultManager.chooseServerAlias(keyType, issuers, socket);
        return alias == null ? null : ":" + alias;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        String[] aliases = defaultManager.getServerAliases(keyType, issuers);
        if (aliases == null) {
            return new String[0];
        }
        String[] qualified = new String[aliases.length];
        for (int i = 0; i < aliases.length; i++) {
            qualified[i] = ":" + aliases[i];
        }
        return qualified;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        int separator = alias == null ? -1 : alias.indexOf(':');
        return separator < 0 ? null : managerFor(alias.substring(0, separator))
                .getCertificateChain(alias.substring(separator + 1));
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        int separator = alias == null ? -1 : alias.indexOf(':');
        return separator < 0 ? null : managerFor(alias.substring(0, separator))
                .getPrivateKey(alias.substring(separator + 1));
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
        return null;
    }

    private X509KeyManager managerFor(String host) {
        KeyManagerFactory factory = host.isEmpty() ? null : managers.apply(host);
        if (factory != null) {
            for (KeyManager manager : factory.getKeyManagers()) {
                if (manager instanceof X509KeyManager x509) {
                    return x509;
                }
            }
        }
        return defaultManager;
    }
}
