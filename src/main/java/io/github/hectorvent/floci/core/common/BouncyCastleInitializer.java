package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.jcajce.provider.asymmetric.EC;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;

/**
 * Ensures BouncyCastle security provider is registered before any CDI beans are injected.
 *
 * <p>This class uses a static initializer block to guarantee the provider is registered
 * during class loading, which happens before CDI injection. The {@code @Startup} annotation
 * ensures this class is loaded early in the application lifecycle.</p>
 *
 * <p>Design decision: Using static initializer + @Startup instead of @Observes StartupEvent
 * because StartupEvent observers run after CDI context is ready, which may be too late if
 * other beans depend on BouncyCastle during their construction.</p>
 *
 * @see <a href="https://quarkus.io/guides/cdi#startup-event">Quarkus CDI Startup</a>
 */
@ApplicationScoped
@Startup
public class BouncyCastleInitializer {

    private static final Logger LOG = Logger.getLogger(BouncyCastleInitializer.class);

    static {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        // BC's ClassUtil.loadClass (used during provider setup) uses ClassLoader.loadClass,
        // which in GraalVM native image only finds classes that are directly allocated in
        // reachable code — not classes listed only in reflect-config.json or via class literals.
        // If EC.Mappings failed to load internally, configure EC algorithms explicitly.
        // Guard prevents IllegalStateException from double-registration in regular JVM mode.
        EC.Mappings ecMappings = new EC.Mappings();
        if (provider.getService("KeyPairGenerator", "EC") == null) {
            ecMappings.configure(provider);
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(provider);
            LOG.info("Registered BouncyCastle security provider (static initializer)");
        }
    }

    /**
     * No-op constructor. The actual initialization happens in the static block above.
     * This bean exists solely to trigger class loading via {@code @Startup}.
     */
    public BouncyCastleInitializer() {
        // Static initializer has already run by the time this constructor is called
    }
}
