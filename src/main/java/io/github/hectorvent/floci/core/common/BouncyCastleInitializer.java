package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;

/**
 * Ensures BouncyCastle security provider is registered.
 * With quarkus-security extension and quarkus.security.security-providers=BC,
 * this is handled by Quarkus.
 */
@ApplicationScoped
@Startup
public class BouncyCastleInitializer {

    private static final Logger LOG = Logger.getLogger(BouncyCastleInitializer.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            LOG.info("Registered BouncyCastle security provider (manual fallback)");
        } else {
            LOG.info("BouncyCastle provider already registered by Quarkus");
        }
    }

    public BouncyCastleInitializer() {
    }
}
